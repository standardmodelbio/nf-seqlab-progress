package standardmodelbio.plugin

import groovy.json.JsonOutput
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent
import spock.lang.Specification
import spock.lang.TempDir

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

    private static ProgressRuntime configuredRuntime() {
        def runtime = new ProgressRuntime('test-run', 'Test run')
        runtime.registerInputs([[file_id: 'chr22', path: '/input/chr22.vcf.gz']])
        runtime.registerStages(
            [[id: 'build_svar2', label: 'Build SVAR2']],
            [[process: 'SEQLAB_BUILD_SVAR2', stage: 'build_svar2', completion_boundary: true]],
        )
        return runtime
    }

    private TaskEvent taskEvent(String taskId, int attempt, Path workDir) {
        def task = new TaskRun()
        task.name = "SEQLAB_BUILD_SVAR2 (chr22)"
        task.inputEnv = [
            NF_SEQLAB_PROGRESS_FILE_ID: 'chr22',
            NF_SEQLAB_PROGRESS_PARENT_FILE_ID: 'chr22',
            NF_SEQLAB_PROGRESS_TASK_ID: taskId,
        ]
        task.workDir = workDir

        def handler = Mock(TaskHandler)
        handler.getTask() >> task

        def trace = new TraceRecord()
        trace.put('task_id', taskId)
        trace.put('process', 'STANDARDMODELBIO_SEQLAB:SEQLAB:SEQLAB_BUILD_SVAR2')
        trace.put('attempt', attempt)
        return new TaskEvent(handler, trace)
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
