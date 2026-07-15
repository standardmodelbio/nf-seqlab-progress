package standardmodelbio.plugin.io

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Supplier

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING

@CompileStatic
class ProgressAuditWriter implements Closeable {

    static final long DEFAULT_PUBLISH_DELAY_MILLIS = 1_000L
    static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 10_000L
    static final int DEFAULT_CLOSE_ATTEMPTS = 3
    static final long DEFAULT_RETRY_BACKOFF_MILLIS = 100L

    private final Object closeLock = new Object()
    private final BufferedWriter writer
    private final Path targetPath
    private final Path localPath
    private final Path stagingRoot
    private final Path ownedStagingDirectory
    private final boolean remote
    private final AuditFileOperations fileOperations
    private final CoalescingAuditPublisher publisher

    private boolean closeRequested
    private boolean writerClosed
    private boolean publisherClosed
    private boolean publicationFailed
    private boolean cleanupComplete

    ProgressAuditWriter(Path path) {
        this(path, null)
    }

    ProgressAuditWriter(Path path, Path stagingDirectory) {
        this(
            path,
            stagingDirectory,
            new NextflowAuditFileOperations(),
            DEFAULT_PUBLISH_DELAY_MILLIS,
        )
    }

    ProgressAuditWriter(
        Path path,
        Path stagingDirectory,
        AuditFileOperations fileOperations,
        long publishDelayMillis
    ) {
        this(
            path,
            stagingDirectory,
            fileOperations,
            publishDelayMillis,
            DEFAULT_CLOSE_TIMEOUT_MILLIS,
            DEFAULT_CLOSE_ATTEMPTS,
            DEFAULT_RETRY_BACKOFF_MILLIS,
        )
    }

    ProgressAuditWriter(
        Path path,
        Path stagingDirectory,
        AuditFileOperations fileOperations,
        long publishDelayMillis,
        long closeTimeoutMillis,
        int closeAttempts,
        long retryBackoffMillis
    ) {
        this.targetPath = Objects.requireNonNull(path, 'path')
        AuditFileOperations operations = Objects.requireNonNull(fileOperations, 'fileOperations')
        this.remote = !isLocal(path)

        Path createdOwnedDirectory = null
        Path createdLocalPath = null
        BufferedWriter createdWriter = null
        Path createdStagingRoot = null
        try {
            Path parent = remote ? path.parent : path.toAbsolutePath().parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            if (remote) {
                createdOwnedDirectory = stagingDirectory == null
                    ? Files.createTempDirectory('nf-seqlab-progress-audit-')
                    : null
                createdStagingRoot = stagingDirectory ?: createdOwnedDirectory
                Files.createDirectories(createdStagingRoot)
                createdLocalPath = Files.createTempFile(
                    createdStagingRoot,
                    'progress-',
                    '.jsonl',
                )
                if (Files.exists(targetPath)) {
                    operations.copy(targetPath, createdLocalPath)
                }
            }
            else {
                createdLocalPath = targetPath
            }
            createdWriter = operations.openAppender(createdLocalPath)
        }
        catch (Throwable error) {
            cleanupConstructorFailure(
                createdWriter,
                remote ? createdLocalPath : null,
                createdOwnedDirectory,
                operations,
                error,
            )
            throw error
        }

        this.ownedStagingDirectory = createdOwnedDirectory
        this.stagingRoot = createdStagingRoot
        this.localPath = createdLocalPath
        this.writer = createdWriter
        this.fileOperations = operations
        this.publisher = remote
            ? new CoalescingAuditPublisher(
                { snapshotForUpload() } as Supplier<Path>,
                operations,
                targetPath,
                publishDelayMillis,
                closeTimeoutMillis,
                closeAttempts,
                retryBackoffMillis,
            )
            : null
    }

    synchronized void append(Map<String, ?> event) {
        if (closeRequested) {
            throw new IllegalStateException('Progress audit writer is closed')
        }
        writer.write(JsonOutput.toJson(event))
        writer.newLine()
        writer.flush()
        publisher?.markDirty()
    }

    @Override
    void close() {
        synchronized (closeLock) {
            if (publicationFailed) {
                return
            }
            synchronized (this) {
                closeRequested = true
                if (!writerClosed) {
                    writer.close()
                    writerClosed = true
                }
            }
            if (!remote) {
                cleanupComplete = true
                return
            }
            if (!publisherClosed) {
                try {
                    publisher.close()
                }
                catch (Throwable error) {
                    publicationFailed = true
                    throw error
                }
                finally {
                    publisherClosed = true
                }
            }
            if (!cleanupComplete) {
                fileOperations.deleteIfExists(localPath)
                if (ownedStagingDirectory != null) {
                    fileOperations.deleteIfExists(ownedStagingDirectory)
                }
                cleanupComplete = true
            }
        }
    }

    private synchronized Path snapshotForUpload() {
        if (!writerClosed) {
            writer.flush()
        }
        Path snapshot = Files.createTempFile(stagingRoot, 'upload-', '.jsonl')
        try {
            Files.copy(localPath, snapshot, REPLACE_EXISTING)
            return snapshot
        }
        catch (Throwable error) {
            try {
                Files.deleteIfExists(snapshot)
            }
            catch (Throwable cleanupError) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    private static void cleanupConstructorFailure(
        BufferedWriter writer,
        Path localPath,
        Path ownedStagingDirectory,
        AuditFileOperations fileOperations,
        Throwable failure
    ) {
        try {
            writer?.close()
        }
        catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError)
        }
        try {
            if (localPath != null) {
                fileOperations.deleteIfExists(localPath)
            }
        }
        catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError)
        }
        try {
            if (ownedStagingDirectory != null) {
                fileOperations.deleteIfExists(ownedStagingDirectory)
            }
        }
        catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError)
        }
    }

    private static boolean isLocal(Path path) {
        return path.fileSystem.provider().scheme.equalsIgnoreCase('file')
    }
}
