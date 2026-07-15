package standardmodelbio.plugin

import groovy.json.JsonOutput
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskContext
import nextflow.processor.TaskRun
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent
import spock.lang.Specification
import spock.lang.TempDir
import standardmodelbio.plugin.render.DashboardView

import java.nio.file.Files
import java.nio.file.Path

class SeqlabProgressObserverTest extends Specification {

    @TempDir
    Path workDir

    def runtime = configuredRuntime()
    def observer = new SeqlabProgressObserver(runtime)

    def 'V2 factory creates an observer backed by the session runtime'() {
        given:
        def session = Mock(Session)
        session.getRunName() >> 'factory-run'

        when:
        def result = new SeqlabProgressFactory().create(session)

        then:
        result.size() == 1
        result.first() instanceof SeqlabProgressObserver
        result.first() instanceof TraceObserverV2

        cleanup:
        ProgressRuntimes.remove(session)
    }

    def 'task lifecycle and a live snapshot update exact file progress'() {
        given:
        TaskEvent event = taskEvent('task-1', 1, workDir)

        when:
        observer.onTaskStart(event)
        Files.writeString(
            workDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot('task-1', 1, 40, 100)),
        )
        observer.pollSnapshots()

        then:
        runtime.state.activeFile('build_svar2', 'chr22').percent == 40d
        runtime.state.stage('build_svar2').completedFiles == 0

        when:
        observer.onTaskComplete(event)

        then:
        runtime.state.stage('build_svar2').completedFiles == 1
    }

    def 'a cached task counts once without a snapshot'() {
        given:
        TaskEvent event = taskEvent('cached-1', 1, workDir)

        when:
        observer.onTaskCached(event)
        observer.onTaskCached(event)

        then:
        runtime.state.stage('build_svar2').completedFiles == 1
    }

    def 'a failed attempt stays incomplete until its retry succeeds'() {
        given:
        Path retryWorkDir = workDir.resolve('retry')
        Files.createDirectories(retryWorkDir)
        TaskEvent first = taskEvent('retry-1', 1, workDir, 'FAILED')
        TaskEvent second = taskEvent('retry-1', 2, retryWorkDir, 'COMPLETED')

        when:
        observer.onTaskStart(first)
        observer.onTaskComplete(first)
        observer.onFlowError(first)

        then:
        runtime.state.stage('build_svar2').completedFiles == 0
        runtime.state.stageStatus('build_svar2') == 'failed'
        runtime.state.errorCount() == 1

        when:
        observer.onTaskPending(second)
        observer.onTaskStart(second)

        then:
        runtime.state.stage('build_svar2').completedFiles == 0
        runtime.state.stageStatus('build_svar2') == 'running'
        runtime.state.errorCount() == 0

        when:
        observer.onTaskComplete(second)

        then:
        runtime.state.stage('build_svar2').completedFiles == 1
        runtime.state.stageStatus('build_svar2') == 'completed'
        runtime.state.errorCount() == 0
    }

    def 'a stale snapshot is ignored and recorded as one observer warning'() {
        given:
        TaskEvent event = taskEvent('retry-1', 2, workDir)
        observer.onTaskStart(event)
        Files.writeString(
            workDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot('retry-1', 1, 90, 100)),
        )

        when:
        observer.pollSnapshots()
        observer.pollSnapshots()

        then:
        observer.snapshotWarnings == 1
        runtime.state.activeFile('build_svar2', 'chr22').percent == null
    }

    def 'different snapshots sharing an update timestamp are both applied'() {
        given:
        TaskEvent event = taskEvent('same-time', 1, workDir)
        Path progressFile = workDir.resolve('.nf-seqlab-progress.json')
        observer.onTaskStart(event)

        when:
        Files.writeString(progressFile, JsonOutput.toJson(snapshot('same-time', 1, 25, 100)))
        observer.pollSnapshots()

        then:
        runtime.state.activeFile('build_svar2', 'chr22').percent == 25d

        when:
        Files.writeString(progressFile, JsonOutput.toJson(snapshot('same-time', 1, 75, 100)))
        observer.pollSnapshots()

        then:
        runtime.state.activeFile('build_svar2', 'chr22').percent == 75d
    }

    def 'Nextflow trace attempt overrides a stale task environment attempt'() {
        given:
        Path retryWorkDir = workDir.resolve('authoritative-retry')
        Files.createDirectories(retryWorkDir)
        TaskEvent first = taskEvent('authoritative', 1, workDir, 'FAILED', '1')
        TaskEvent second = taskEvent('authoritative', 2, retryWorkDir, 'COMPLETED', '1')
        observer.onTaskStart(first)
        observer.onTaskComplete(first)
        observer.onTaskStart(second)

        when:
        Files.writeString(
            retryWorkDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot('authoritative', 2, 40, 100)),
        )
        observer.pollSnapshots()

        then:
        observer.snapshotWarnings == 0
        runtime.state.activeFile('build_svar2', 'chr22').percent == 40d
    }

    def 'task lifecycle falls back to file identity in TaskRun context metadata'() {
        given:
        TaskEvent event = taskEventFromMeta(
            'context-task',
            1,
            workDir,
            [id: 'chr22.shard_1', parent_id: 'chr22'],
        )

        when:
        observer.onTaskStart(event)
        Files.writeString(
            workDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot(workDirTaskId(workDir), 1, 30, 100) + [
                file_id: 'chr22.shard_1',
                parent_file_id: 'chr22',
            ]),
        )
        observer.pollSnapshots()

        then:
        observer.snapshotWarnings == 0
        runtime.state.activeFile('build_svar2', 'chr22').percent == 30d
    }

    def 'managed task environment remains authoritative over context metadata'() {
        given:
        TaskEvent event = taskEvent('managed-task', 1, workDir)
        def context = Mock(TaskContext)
        context.get('meta') >> [file_id: 'unregistered', parent_file_id: 'unregistered']
        event.handler.task.context = context

        when:
        observer.onTaskStart(event)
        observer.onTaskComplete(event)

        then:
        runtime.state.stage('build_svar2').completedFiles == 1
    }

    def 'work directory suffix is the primary task ID fallback for native snapshots'() {
        given:
        Path nativeWorkDir = workDir.resolve('aa/bb')
        Files.createDirectories(nativeWorkDir)
        TaskEvent event = taskEventFromMeta(
            'trace-task',
            1,
            nativeWorkDir,
            [file_id: 'chr22', parent_file_id: 'chr22'],
        )

        when:
        observer.onTaskStart(event)
        Files.writeString(
            nativeWorkDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot('aa/bb', 1, 45, 100)),
        )
        observer.pollSnapshots()

        then:
        observer.snapshotWarnings == 0
        runtime.state.activeFile('build_svar2', 'chr22').percent == 45d
    }

    def 'new retry work directory supersedes a late failure from the prior attempt'() {
        given:
        Path firstWorkDir = workDir.resolve('aa/first')
        Path secondWorkDir = workDir.resolve('bb/second')
        Files.createDirectories(firstWorkDir)
        Files.createDirectories(secondWorkDir)
        Map<String, String> meta = [file_id: 'chr22', parent_file_id: 'chr22']
        TaskEvent first = taskEventFromMeta('trace-task', 1, firstWorkDir, meta, 'FAILED')
        TaskEvent second = taskEventFromMeta('trace-task', 2, secondWorkDir, meta)
        observer.onTaskStart(first)

        when:
        observer.onTaskPending(second)
        observer.onTaskStart(second)
        observer.onTaskComplete(first)
        observer.onFlowError(first)

        then:
        runtime.state.errorCount() == 0
        runtime.state.stageStatus('build_svar2') == 'running'

        when:
        observer.onTaskComplete(second)

        then:
        runtime.state.errorCount() == 0
        runtime.state.stageStatus('build_svar2') == 'completed'
    }

    def 'late failure cannot remove a newer tracker with the same explicit task ID'() {
        given:
        Path firstWorkDir = workDir.resolve('stable/first')
        Path secondWorkDir = workDir.resolve('stable/second')
        Files.createDirectories(firstWorkDir)
        Files.createDirectories(secondWorkDir)
        TaskEvent first = taskEvent('stable-task', 1, firstWorkDir, 'FAILED')
        TaskEvent second = taskEvent('stable-task', 2, secondWorkDir)
        observer.onTaskStart(first)
        observer.onTaskPending(second)
        observer.onTaskStart(second)

        when:
        observer.onTaskComplete(first)
        observer.onFlowError(first)
        Files.writeString(
            secondWorkDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot('stable-task', 2, 40, 100)),
        )
        observer.pollSnapshots()

        then:
        observer.snapshotWarnings == 0
        runtime.state.errorCount() == 0
        runtime.state.activeFile('build_svar2', 'chr22').percent == 40d
    }

    def 'audit log follows the configured pipeline outdir'() {
        given:
        Path configuredOutdir = workDir.resolve('configured-results')
        def session = Mock(Session)
        session.getConfig() >> [params: [
            outdir: configuredOutdir.toString(),
            progress_mode: 'plain',
            progress_refresh_seconds: 60,
        ]]
        session.getOutputDir() >> workDir.resolve('nextflow-output')
        session.getAnsiLog() >> false

        when:
        observer.onFlowCreate(session)
        observer.onFlowComplete()

        then:
        Files.isRegularFile(configuredOutdir.resolve('pipeline_info/progress.jsonl'))
        !Files.exists(workDir.resolve('nextflow-output/pipeline_info/progress.jsonl'))
    }

    def 'flow completion is idempotent and ignores late task callbacks'() {
        given:
        Path outdir = workDir.resolve('quiesced-results')
        Path audit = outdir.resolve('pipeline_info/progress.jsonl')
        def session = Mock(Session)
        session.getConfig() >> [params: [
            outdir: outdir.toString(),
            progress_mode: 'plain',
            progress_refresh_seconds: 60,
        ]]
        session.getOutputDir() >> outdir
        session.getAnsiLog() >> false
        TaskEvent event = taskEvent('late-completion', 1, workDir)
        observer.onFlowCreate(session)
        observer.onTaskStart(event)
        observer.onFlowComplete()
        int recordsAtClose = Files.readAllLines(audit).size()

        when:
        observer.onFlowComplete()
        observer.onTaskComplete(event)
        observer.pollSnapshots()

        then:
        runtime.state.stage('build_svar2').completedFiles == 0
        Files.readAllLines(audit).size() == recordsAtClose
    }

    def 'terminal completion polls the task snapshot before removing its tracker'() {
        given:
        def shardRuntime = new ProgressRuntime('test-run', 'Shard run')
        shardRuntime.registerInputs([[file_id: 'chr22', path: '/input/chr22.vcf.gz']])
        shardRuntime.registerStages(
            [[id: 'prepare', label: 'Prepare variants']],
            [[process: 'BCFTOOLS_NORM', stage: 'prepare', completion_boundary: false]],
        )
        def shardObserver = new SeqlabProgressObserver(shardRuntime)
        TaskEvent event = taskEventForProcess(
            'terminal-poll',
            1,
            workDir,
            'BCFTOOLS_NORM',
            'chr22.shard_1',
            'chr22',
        )
        shardObserver.onTaskStart(event)
        Files.writeString(
            workDir.resolve('.nf-seqlab-progress.json'),
            JsonOutput.toJson(snapshot('terminal-poll', 1, 75, 100) + [
                stage_id: 'prepare',
                process: 'BCFTOOLS_NORM',
                file_id: 'chr22.shard_1',
                parent_file_id: 'chr22',
            ]),
        )

        when:
        shardObserver.onTaskComplete(event)

        then:
        shardObserver.snapshotWarnings == 0
        shardRuntime.state.activeFile('prepare', 'chr22').percent == 75d
    }

    def 'audit and console render consume one dashboard projection per transition'() {
        given:
        def countingRuntime = new CountingProgressRuntime('test-run', 'Counting run')
        countingRuntime.registerInputs([[file_id: 'chr22', path: '/input/chr22.vcf.gz']])
        countingRuntime.registerStages(
            [[id: 'build_svar2', label: 'Build SVAR2']],
            [[process: 'SEQLAB_BUILD_SVAR2', stage: 'build_svar2', completion_boundary: true]],
        )
        def countingObserver = new SeqlabProgressObserver(countingRuntime)
        def session = Mock(Session)
        session.getConfig() >> [params: [
            outdir: workDir.resolve('one-projection').toString(),
            progress_mode: 'plain',
            progress_refresh_seconds: 60,
        ]]
        session.getOutputDir() >> workDir
        session.getAnsiLog() >> false
        countingObserver.onFlowCreate(session)
        countingRuntime.dashboardCalls = 0

        when:
        countingObserver.onTaskStart(taskEvent('one-projection', 1, workDir))

        then:
        countingRuntime.dashboardCalls == 1

        cleanup:
        countingObserver?.onFlowComplete()
    }

    private static ProgressRuntime configuredRuntime() {
        def runtime = new ProgressRuntime('test-run', 'Test run')
        runtime.registerInputs([[file_id: 'chr22', path: '/input/chr22.vcf.gz']])
        runtime.registerStages(
            [[id: 'build_svar2', label: 'Build SVAR2']],
            [[process: 'SEQLAB_BUILD_SVAR2', stage: 'build_svar2', completion_boundary: true]],
        )
        return runtime
    }

    private TaskEvent taskEvent(
        String taskId,
        int attempt,
        Path workDir,
        String status = 'COMPLETED',
        String environmentAttempt = null
    ) {
        def task = new TaskRun()
        task.name = "SEQLAB_BUILD_SVAR2 (chr22)"
        task.inputEnv = [
            NF_SEQLAB_PROGRESS_FILE_ID: 'chr22',
            NF_SEQLAB_PROGRESS_PARENT_FILE_ID: 'chr22',
            NF_SEQLAB_PROGRESS_TASK_ID: taskId,
        ]
        if (environmentAttempt != null) {
            task.inputEnv.NF_SEQLAB_PROGRESS_ATTEMPT = environmentAttempt
        }
        task.workDir = workDir

        def handler = Mock(TaskHandler)
        handler.getTask() >> task

        def trace = new TraceRecord()
        trace.put('task_id', taskId)
        trace.put('process', 'STANDARDMODELBIO_SEQLAB:SEQLAB:SEQLAB_BUILD_SVAR2')
        trace.put('attempt', attempt)
        trace.put('status', status)
        return new TaskEvent(handler, trace)
    }

    private TaskEvent taskEventFromMeta(
        String taskId,
        int attempt,
        Path workDir,
        Map<String, String> metadata,
        String status = 'COMPLETED'
    ) {
        TaskEvent event = taskEvent(taskId, attempt, workDir, status)
        event.handler.task.inputEnv = [:]
        def context = Mock(TaskContext)
        context.get('meta') >> metadata
        event.handler.task.context = context
        return event
    }

    private TaskEvent taskEventForProcess(
        String taskId,
        int attempt,
        Path workDir,
        String process,
        String fileId,
        String parentFileId,
        String status = 'COMPLETED'
    ) {
        TaskEvent event = taskEvent(taskId, attempt, workDir, status)
        event.handler.task.name = "${process} (${fileId})"
        event.handler.task.inputEnv.NF_SEQLAB_PROGRESS_FILE_ID = fileId
        event.handler.task.inputEnv.NF_SEQLAB_PROGRESS_PARENT_FILE_ID = parentFileId
        event.trace.put('process', "STANDARDMODELBIO_SEQLAB:SEQLAB:${process}")
        return event
    }

    private static String workDirTaskId(Path path) {
        return "${path.parent.fileName}/${path.fileName}"
    }

    private static Map<String, Object> snapshot(
        String taskId,
        int attempt,
        long completed,
        long total
    ) {
        return [
            schema: 'nf-seqlab.progress/v1',
            run_id: 'test-run',
            stage_id: 'build_svar2',
            process: 'SEQLAB_BUILD_SVAR2',
            file_id: 'chr22',
            parent_file_id: 'chr22',
            task_id: taskId,
            attempt: attempt,
            state: 'running',
            phase: 'read',
            completed: completed,
            total: total,
            unit: 'compressed_bytes',
            percent: 100d * completed / total,
            message: 'Reading',
            updated_at: '2026-07-15T03:34:00Z',
        ]
    }
}

class CountingProgressRuntime extends ProgressRuntime {
    int dashboardCalls

    CountingProgressRuntime(String runId, String runName) {
        super(runId, runName)
    }

    @Override
    DashboardView dashboard(int maximumActiveFiles) {
        dashboardCalls++
        return super.dashboard(maximumActiveFiles)
    }
}
