package standardmodelbio.plugin.io

import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

@CompileStatic
class CoalescingAuditPublisher implements Closeable {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger()

    private final Object closeLock = new Object()
    private final Object stateLock = new Object()
    private final Supplier<Path> snapshotFactory
    private final AuditFileOperations fileOperations
    private final Path targetPath
    private final long delayMillis
    private final long closeTimeoutMillis
    private final int closeAttempts
    private final long retryBackoffMillis
    private final ScheduledThreadPoolExecutor executor
    private final Set<Path> pendingCleanup = new LinkedHashSet<>()

    private ScheduledFuture<?> scheduled
    private long dirtyGeneration
    private long publishedGeneration
    private boolean inFlight
    private boolean closing
    private boolean closed

    CoalescingAuditPublisher(
        Supplier<Path> snapshotFactory,
        AuditFileOperations fileOperations,
        Path targetPath,
        long delayMillis,
        long closeTimeoutMillis,
        int closeAttempts,
        long retryBackoffMillis
    ) {
        this.snapshotFactory = Objects.requireNonNull(snapshotFactory, 'snapshotFactory')
        this.fileOperations = Objects.requireNonNull(fileOperations, 'fileOperations')
        this.targetPath = Objects.requireNonNull(targetPath, 'targetPath')
        this.delayMillis = Math.max(1L, delayMillis)
        this.closeTimeoutMillis = Math.max(1L, closeTimeoutMillis)
        this.closeAttempts = Math.max(1, closeAttempts)
        this.retryBackoffMillis = Math.max(0L, retryBackoffMillis)
        this.executor = new ScheduledThreadPoolExecutor(1, { Runnable runnable ->
            Thread thread = new Thread(
                runnable,
                "nf-seqlab-audit-publisher-${THREAD_SEQUENCE.incrementAndGet()}",
            )
            thread.daemon = true
            return thread
        })
        executor.removeOnCancelPolicy = true
        executor.executeExistingDelayedTasksAfterShutdownPolicy = false
        executor.continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    void markDirty() {
        synchronized (stateLock) {
            if (closing || closed) {
                throw new IllegalStateException('Progress audit publisher is closing')
            }
            dirtyGeneration++
            scheduleLocked()
        }
    }

    @Override
    void close() {
        synchronized (closeLock) {
            synchronized (stateLock) {
                if (closed) {
                    return
                }
                closing = true
                scheduled?.cancel(false)
                scheduled = null
            }

            Future<?> finalFlush = executor.submit { flushFinalWithRetries() }
            long deadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
            IOException failure = null
            try {
                await(finalFlush, deadlineNanos, closeTimeoutMillis)
                retryPendingCleanup()
            }
            catch (IOException error) {
                failure = error
                finalFlush.cancel(true)
            }
            finally {
                synchronized (stateLock) {
                    closed = true
                }
                executor.shutdownNow()
                awaitTermination(deadlineNanos)
            }
            if (failure != null) {
                throw failure
            }
        }
    }

    private void flushFinalWithRetries() {
        Throwable lastFailure = null
        for (int attempt = 1; attempt <= closeAttempts; attempt++) {
            Throwable failure = publishLatest()
            synchronized (stateLock) {
                if (publishedGeneration >= dirtyGeneration) {
                    return
                }
            }
            lastFailure = failure ?: lastFailure
            if (attempt < closeAttempts) {
                pauseBeforeRetry(attempt)
            }
        }
        throw new IOException(
            "Unable to publish final progress audit after ${closeAttempts} attempts",
            lastFailure,
        )
    }

    private void scheduleLocked() {
        if (scheduled != null || inFlight || closing || closed) {
            return
        }
        scheduled = executor.schedule(
            { publishLatest() } as Runnable,
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private Throwable publishLatest() {
        long generation
        synchronized (stateLock) {
            scheduled = null
            if (publishedGeneration >= dirtyGeneration) {
                return null
            }
            inFlight = true
            generation = dirtyGeneration
        }

        Path snapshot = null
        Throwable failure = null
        try {
            snapshot = snapshotFactory.get()
            fileOperations.copy(snapshot, targetPath)
        }
        catch (Throwable error) {
            failure = error
        }
        finally {
            if (snapshot != null) {
                try {
                    Files.deleteIfExists(snapshot)
                }
                catch (Throwable cleanupError) {
                    synchronized (stateLock) {
                        pendingCleanup.add(snapshot)
                    }
                }
            }
            synchronized (stateLock) {
                if (failure == null) {
                    publishedGeneration = Math.max(publishedGeneration, generation)
                }
                inFlight = false
                if (!closing && publishedGeneration < dirtyGeneration) {
                    scheduleLocked()
                }
            }
        }
        return failure
    }

    private void retryPendingCleanup() {
        List<Path> paths
        synchronized (stateLock) {
            paths = new ArrayList<>(pendingCleanup)
        }
        IOException failure = null
        paths.each { Path path ->
            try {
                Files.deleteIfExists(path)
                synchronized (stateLock) {
                    pendingCleanup.remove(path)
                }
            }
            catch (IOException error) {
                if (failure == null) {
                    failure = new IOException('Unable to clean progress audit upload snapshots')
                }
                failure.addSuppressed(error)
            }
        }
        if (failure != null) {
            throw failure
        }
    }

    private void pauseBeforeRetry(int attempt) {
        if (retryBackoffMillis == 0L) {
            return
        }
        try {
            Thread.sleep(Math.multiplyExact(retryBackoffMillis, attempt as long))
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt()
            throw new IOException('Interrupted while retrying progress audit publication', error)
        }
    }

    private static void await(Future<?> future, long deadlineNanos, long timeoutMillis) {
        try {
            long remainingNanos = Math.max(1L, deadlineNanos - System.nanoTime())
            future.get(remainingNanos, TimeUnit.NANOSECONDS)
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt()
            throw new IOException('Interrupted while flushing progress audit', error)
        }
        catch (TimeoutException error) {
            throw new IOException(
                "Timed out after ${timeoutMillis} ms while flushing progress audit",
                error,
            )
        }
        catch (ExecutionException error) {
            Throwable cause = error.cause ?: error
            if (cause instanceof IOException) {
                throw cause as IOException
            }
            throw new IOException('Unable to publish progress audit', cause)
        }
    }

    private void awaitTermination(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            return
        }
        try {
            executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt()
        }
    }
}
