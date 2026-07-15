package standardmodelbio.plugin.model

import spock.lang.Specification

class ProgressStateTest extends Specification {

    def state = new ProgressState('aou-v8')

    def setup() {
        state.registerSourceFiles(['chr1', 'chr2', 'chr22'])
        state.registerStage('prepare', 'Prepare variants')
        state.registerStage('build_svar2', 'Build SVAR2')
        state.mapProcess('BCFTOOLS_NORM', 'prepare', false)
        state.mapProcess('BCFTOOLS_CONCAT', 'prepare', true)
        state.mapProcess('SEQLAB_BUILD_SVAR2', 'build_svar2', true)
    }

    def 'stage percentage counts completed source files only'() {
        when:
        state.taskStarted(task('one', 'SEQLAB_BUILD_SVAR2', 'chr1', 'chr1', 1))
        state.applySnapshot(snapshot('one', 'chr1', 'chr1', 1, 90, 100))

        then:
        state.stage('build_svar2').completedFiles == 0
        state.stage('build_svar2').expectedFiles == 3
        state.stage('build_svar2').percent == 0d

        when:
        state.taskCompleted(task('one', 'SEQLAB_BUILD_SVAR2', 'chr1', 'chr1', 1), false)

        then:
        state.stage('build_svar2').completedFiles == 1
        state.stage('build_svar2').percent == 100d / 3d
    }

    def 'shards do not count until their parent completion boundary succeeds'() {
        when:
        state.taskStarted(task('s1', 'BCFTOOLS_NORM', 'chr22.shard_1', 'chr22', 1))
        state.taskStarted(task('s2', 'BCFTOOLS_NORM', 'chr22.shard_2', 'chr22', 1))
        state.taskCompleted(task('s1', 'BCFTOOLS_NORM', 'chr22.shard_1', 'chr22', 1), false)
        state.taskCompleted(task('s2', 'BCFTOOLS_NORM', 'chr22.shard_2', 'chr22', 1), false)

        then:
        state.stage('prepare').completedFiles == 0

        when:
        state.taskCompleted(task('concat', 'BCFTOOLS_CONCAT', 'chr22', 'chr22', 1), false)

        then:
        state.stage('prepare').completedFiles == 1
    }

    def 'cached completion counts once'() {
        given:
        def cached = task('cached', 'SEQLAB_BUILD_SVAR2', 'chr2', 'chr2', 1)

        when:
        state.taskCompleted(cached, true)
        state.taskCompleted(cached, true)

        then:
        state.stage('build_svar2').completedFiles == 1
    }

    def 'retry gets a fresh attempt without double counting'() {
        given:
        def first = task('retry', 'SEQLAB_BUILD_SVAR2', 'chr1', 'chr1', 1)
        def second = task('retry', 'SEQLAB_BUILD_SVAR2', 'chr1', 'chr1', 2)

        when:
        state.taskStarted(first)
        state.applySnapshot(snapshot('retry', 'chr1', 'chr1', 1, 70, 100))
        state.taskFailed(first, 'exit 137')
        state.taskStarted(second)

        then:
        state.activeFile('build_svar2', 'chr1').percent == null
        state.stage('build_svar2').completedFiles == 0

        when:
        state.taskCompleted(second, false)

        then:
        state.stage('build_svar2').completedFiles == 1
    }

    def 'rejects a stale snapshot and a percentage regression'() {
        given:
        def current = task('live', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 2)
        state.taskStarted(current)

        when:
        state.applySnapshot(snapshot('live', 'chr22', 'chr22', 1, 50, 100))

        then:
        thrown(IllegalArgumentException)

        when:
        state.applySnapshot(snapshot('live', 'chr22', 'chr22', 2, 50, 100))
        state.applySnapshot(snapshot('live', 'chr22', 'chr22', 2, 49, 100))

        then:
        thrown(IllegalArgumentException)
    }

    private static TaskIdentity task(
        String taskId,
        String process,
        String fileId,
        String parentFileId,
        int attempt
    ) {
        return new TaskIdentity(taskId, process, fileId, parentFileId, attempt)
    }

    private static ProgressSnapshot snapshot(
        String taskId,
        String fileId,
        String parentFileId,
        int attempt,
        long completed,
        long total
    ) {
        return ProgressSnapshot.fromMap([
            schema: 'nf-seqlab.progress/v1',
            run_id: 'aou-v8',
            stage_id: 'build_svar2',
            process: 'SEQLAB_BUILD_SVAR2',
            file_id: fileId,
            parent_file_id: parentFileId,
            task_id: taskId,
            attempt: attempt,
            state: 'running',
            phase: 'read',
            completed: completed,
            total: total,
            unit: 'compressed_bytes',
            percent: 100d * completed / total,
            message: 'Reading',
            updated_at: '2026-07-15T03:18:00Z',
        ])
    }
}
