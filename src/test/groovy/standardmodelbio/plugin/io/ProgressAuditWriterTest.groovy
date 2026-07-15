package standardmodelbio.plugin.io

import groovy.json.JsonSlurper
import nextflow.file.FileHelper
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING

class ProgressAuditWriterTest extends Specification {

    @TempDir
    Path directory

    def 'appends normalized JSON lines and creates pipeline_info'() {
        given:
        Path audit = directory.resolve('pipeline_info/progress.jsonl')

        when:
        def writer = new ProgressAuditWriter(audit)
        writer.append([schema: 'nf-seqlab.dashboard/v1', state: 'running', files: '0/22'])
        writer.append([schema: 'nf-seqlab.dashboard/v1', state: 'completed', files: '22/22'])
        writer.close()

        then:
        Files.isRegularFile(audit)
        def lines = Files.readAllLines(audit)
        lines.size() == 2
        new JsonSlurper().parseText(lines[0]).state == 'running'
        new JsonSlurper().parseText(lines[1]).state == 'completed'
    }

    def 'append after close is rejected'() {
        given:
        def writer = new ProgressAuditWriter(directory.resolve('progress.jsonl'))
        writer.close()

        when:
        writer.append([state: 'late'])

        then:
        thrown(IllegalStateException)
    }

    def 'publishes a complete append-only audit through a non-file provider'() {
        given:
        Path archive = directory.resolve('remote-audit.zip')
        Path stagingDirectory = directory.resolve('audit-staging')
        FileSystem remote = FileSystems.newFileSystem(
            URI.create("jar:${archive.toUri()}"),
            [create: 'true'],
        )
        Path audit = remote.getPath('/bucket/results/pipeline_info/progress.jsonl')
        Files.createDirectories(audit.parent)
        Files.writeString(audit, '{"state":"existing"}\n')

        when:
        def writer = new ProgressAuditWriter(audit, stagingDirectory)
        writer.append([state: 'running'])
        Files.delete(audit)
        writer.append([state: 'completed'])
        writer.close()

        then:
        def lines = Files.readAllLines(audit)
        lines.size() == 3
        new JsonSlurper().parseText(lines[0]).state == 'existing'
        new JsonSlurper().parseText(lines[1]).state == 'running'
        new JsonSlurper().parseText(lines[2]).state == 'completed'
        Files.list(stagingDirectory).withCloseable { it.count() } == 0

        cleanup:
        remote?.close()
    }

    def 'remote burst is asynchronous coalesced and limited to one upload'() {
        given:
        RemoteFixture remote = remoteFixture('coalesced')
        def operations = new ControlledAuditFileOperations()
        operations.blockUploads.set(true)
        def writer = new ProgressAuditWriter(
            remote.audit,
            directory.resolve('coalesced-staging'),
            operations,
            10L,
        )
        def callers = Executors.newSingleThreadExecutor()

        when:
        writer.append([sequence: 1])

        then:
        operations.uploadStarted.await(2, TimeUnit.SECONDS)

        when:
        def burst = callers.submit {
            (2..100).each { int sequence -> writer.append([sequence: sequence]) }
        }

        then:
        burst.get(1, TimeUnit.SECONDS) == null

        when:
        operations.releaseUploads.countDown()
        writer.close()

        then:
        operations.maximumConcurrentUploads.get() == 1
        operations.uploads.get() <= 2
        Files.readAllLines(remote.audit).size() == 100

        cleanup:
        operations?.releaseUploads?.countDown()
        callers?.shutdownNow()
        writer?.close()
        remote?.close()
    }

    def 'close bypasses throttle and flushes the latest generation'() {
        given:
        RemoteFixture remote = remoteFixture('final-flush')
        def operations = new ControlledAuditFileOperations()
        def writer = new ProgressAuditWriter(
            remote.audit,
            directory.resolve('final-flush-staging'),
            operations,
            60_000L,
        )

        when:
        (1..20).each { int sequence -> writer.append([sequence: sequence]) }

        then:
        !operations.uploadStarted.await(100, TimeUnit.MILLISECONDS)

        when:
        writer.close()

        then:
        operations.uploads.get() == 1
        Files.readAllLines(remote.audit).size() == 20

        cleanup:
        writer?.close()
        remote?.close()
    }

    def 'background upload failure is isolated and retried without another append'() {
        given:
        RemoteFixture remote = remoteFixture('retry')
        def operations = new ControlledAuditFileOperations()
        operations.uploadFailuresRemaining.set(1)
        def writer = new ProgressAuditWriter(
            remote.audit,
            directory.resolve('retry-staging'),
            operations,
            10L,
        )

        when:
        writer.append([state: 'latest'])

        then:
        operations.successfulUpload.await(2, TimeUnit.SECONDS)
        operations.uploads.get() == 2

        when:
        writer.close()

        then:
        new JsonSlurper().parseText(Files.readString(remote.audit).trim()).state == 'latest'

        cleanup:
        writer?.close()
        remote?.close()
    }

    def 'final flush retries transient failure within one close call'() {
        given:
        RemoteFixture remote = remoteFixture('close-retry')
        Path staging = directory.resolve('close-retry-staging')
        def operations = new ControlledAuditFileOperations()
        operations.uploadFailuresRemaining.set(1)
        def writer = new ProgressAuditWriter(
            remote.audit,
            staging,
            operations,
            60_000L,
            2_000L,
            3,
            1L,
        )
        writer.append([state: 'latest'])

        when:
        writer.close()

        then:
        operations.uploads.get() == 2
        Files.readString(remote.audit).contains('latest')
        Files.list(staging).withCloseable { it.count() } == 0
        noPublisherThreads()

        cleanup:
        writer?.close()
        remote?.close()
    }

    def 'exhausted final flush fails once retains spool and terminates publisher'() {
        given:
        RemoteFixture remote = remoteFixture('close-exhausted')
        Path staging = directory.resolve('close-exhausted-staging')
        def operations = new ControlledAuditFileOperations()
        operations.uploadFailuresRemaining.set(10)
        def writer = new ProgressAuditWriter(
            remote.audit,
            staging,
            operations,
            60_000L,
            2_000L,
            3,
            1L,
        )
        writer.append([state: 'latest'])

        when:
        writer.close()

        then:
        IOException error = thrown()
        error.message == 'Unable to publish final progress audit after 3 attempts'
        operations.uploads.get() == 3
        Files.list(staging).withCloseable { it.count() } == 1
        noPublisherThreads()

        when: 'close is called again after the terminal failure'
        writer.close()

        then: 'it is a no-op and preserves the recovery spool'
        operations.uploads.get() == 3
        Files.list(staging).withCloseable { it.count() } == 1

        cleanup:
        writer?.close()
        remote?.close()
    }

    def 'hung final upload is interrupted at the close deadline without leaking its executor'() {
        given:
        RemoteFixture remote = remoteFixture('close-timeout')
        Path staging = directory.resolve('close-timeout-staging')
        def operations = new ControlledAuditFileOperations()
        operations.hangUploads.set(true)
        def writer = new ProgressAuditWriter(
            remote.audit,
            staging,
            operations,
            60_000L,
            150L,
            3,
            1L,
        )
        writer.append([state: 'latest'])
        long startedAt = System.nanoTime()

        when:
        writer.close()

        then:
        IOException error = thrown()
        error.message == 'Timed out after 150 ms while flushing progress audit'
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000L
        operations.uploadInterrupted.await(1, TimeUnit.SECONDS)
        Files.list(staging).withCloseable { it.count() } == 1
        noPublisherThreads()

        cleanup:
        operations?.releaseUploads?.countDown()
        writer?.close()
        remote?.close()
    }

    def 'failed spool cleanup is retryable without repeating a successful upload'() {
        given:
        RemoteFixture remote = remoteFixture('cleanup-retry')
        Path staging = directory.resolve('cleanup-retry-staging')
        def operations = new ControlledAuditFileOperations()
        operations.cleanupFailuresRemaining.set(1)
        def writer = new ProgressAuditWriter(remote.audit, staging, operations, 60_000L)
        writer.append([state: 'published'])

        when:
        writer.close()

        then:
        thrown(IOException)
        operations.uploads.get() == 1
        Files.list(staging).withCloseable { it.count() } == 1

        when:
        writer.close()

        then:
        operations.uploads.get() == 1
        Files.list(staging).withCloseable { it.count() } == 0

        cleanup:
        writer?.close()
        remote?.close()
    }

    def 'constructor failure removes remote spool artifacts for #failureMode'() {
        given:
        RemoteFixture remote = remoteFixture("constructor-${failureMode}")
        Path staging = directory.resolve("constructor-${failureMode}-staging")
        Files.createDirectories(staging)
        def operations = new ControlledAuditFileOperations()
        if (failureMode == 'download') {
            Files.writeString(remote.audit, '{"existing":true}\n')
            operations.downloadFailuresRemaining.set(1)
        }
        else {
            operations.failOpen.set(true)
        }

        when:
        new ProgressAuditWriter(remote.audit, staging, operations, 10L)

        then:
        thrown(IOException)
        Files.list(staging).withCloseable { it.count() } == 0

        cleanup:
        remote?.close()

        where:
        failureMode << ['download', 'writer']
    }

    private RemoteFixture remoteFixture(String name) {
        Path archive = directory.resolve("${name}.zip")
        FileSystem fileSystem = FileSystems.newFileSystem(
            URI.create("jar:${archive.toUri()}"),
            [create: 'true'],
        )
        Path audit = fileSystem.getPath('/bucket/results/pipeline_info/progress.jsonl')
        Files.createDirectories(audit.parent)
        return new RemoteFixture(fileSystem, audit)
    }

    private static boolean noPublisherThreads() {
        new PollingConditions(timeout: 2, initialDelay: 0, delay: 0.02).eventually {
            assert Thread.getAllStackTraces().keySet().every { Thread thread ->
                !thread.name.startsWith('nf-seqlab-audit-publisher-')
            }
        }
        return true
    }
}

class RemoteFixture implements Closeable {
    final FileSystem fileSystem
    final Path audit

    RemoteFixture(FileSystem fileSystem, Path audit) {
        this.fileSystem = fileSystem
        this.audit = audit
    }

    @Override
    void close() {
        if (fileSystem?.open) {
            fileSystem.close()
        }
    }
}

class ControlledAuditFileOperations implements AuditFileOperations {
    final AtomicBoolean blockUploads = new AtomicBoolean()
    final AtomicBoolean hangUploads = new AtomicBoolean()
    final AtomicBoolean failOpen = new AtomicBoolean()
    final AtomicInteger downloadFailuresRemaining = new AtomicInteger()
    final AtomicInteger uploadFailuresRemaining = new AtomicInteger()
    final AtomicInteger cleanupFailuresRemaining = new AtomicInteger()
    final AtomicInteger uploads = new AtomicInteger()
    final AtomicInteger concurrentUploads = new AtomicInteger()
    final AtomicInteger maximumConcurrentUploads = new AtomicInteger()
    final CountDownLatch uploadStarted = new CountDownLatch(1)
    final CountDownLatch releaseUploads = new CountDownLatch(1)
    final CountDownLatch successfulUpload = new CountDownLatch(1)
    final CountDownLatch uploadInterrupted = new CountDownLatch(1)

    @Override
    BufferedWriter openAppender(Path path) {
        if (failOpen.get()) {
            throw new IOException('injected writer failure')
        }
        return Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    @Override
    void copy(Path source, Path target) {
        boolean upload = target.fileSystem.provider().scheme != 'file'
        if (!upload) {
            if (downloadFailuresRemaining.getAndUpdate { int value -> Math.max(0, value - 1) } > 0) {
                throw new IOException('injected download failure')
            }
            FileHelper.copyPath(source, target, REPLACE_EXISTING)
            return
        }
        uploads.incrementAndGet()
        int active = concurrentUploads.incrementAndGet()
        maximumConcurrentUploads.accumulateAndGet(active, Math.&max)
        uploadStarted.countDown()
        try {
            if (hangUploads.get()) {
                try {
                    releaseUploads.await()
                }
                catch (InterruptedException error) {
                    uploadInterrupted.countDown()
                    Thread.currentThread().interrupt()
                    throw new IOException('injected interrupted upload', error)
                }
            }
            if (blockUploads.get() && !releaseUploads.await(2, TimeUnit.SECONDS)) {
                throw new IOException('timed out waiting to release upload')
            }
            if (uploadFailuresRemaining.getAndUpdate { int value -> Math.max(0, value - 1) } > 0) {
                throw new IOException('injected upload failure')
            }
            FileHelper.copyPath(source, target, REPLACE_EXISTING)
            successfulUpload.countDown()
        }
        finally {
            concurrentUploads.decrementAndGet()
        }
    }

    boolean deleteIfExists(Path path) {
        if (cleanupFailuresRemaining.getAndUpdate { int value -> Math.max(0, value - 1) } > 0) {
            throw new IOException('injected cleanup failure')
        }
        return Files.deleteIfExists(path)
    }
}
