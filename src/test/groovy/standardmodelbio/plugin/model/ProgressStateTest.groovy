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

    def 'a late running snapshot cannot resurrect a terminal task'() {
        given:
        def completed = task('late', 'SEQLAB_BUILD_SVAR2', 'chr1', 'chr1', 1)
        state.taskStarted(completed)
        state.taskCompleted(completed, false)

        when:
        state.applySnapshot(snapshot('late', 'chr1', 'chr1', 1, 75, 100))

        then:
        state.stage('build_svar2').completedFiles == 1
        state.activeFile('build_svar2', 'chr1').percent == null
    }

    def 'stages count only their applicable source file subsets'() {
        given:
        def branched = new ProgressState('branched-run')
        branched.registerSourceFiles(['vcf-only', 'pgen-only', 'both'])
        branched.registerStage('vcf', 'VCF branch', ['vcf-only', 'both'])
        branched.registerStage('pgen', 'PGEN branch', ['pgen-only', 'both'])
        branched.mapProcess('VCF_BUILD', 'vcf', true)
        branched.mapProcess('PGEN_BUILD', 'pgen', true)

        when:
        branched.taskCompleted(task('vcf-done', 'VCF_BUILD', 'vcf-only', 'vcf-only', 1), false)
        branched.taskCompleted(task('pgen-done', 'PGEN_BUILD', 'pgen-only', 'pgen-only', 1), false)

        then:
        branched.stage('vcf').expectedFiles == 2
        branched.stage('vcf').completedFiles == 1
        branched.stage('vcf').percent == 50d
        branched.stage('pgen').expectedFiles == 2
        branched.stage('pgen').completedFiles == 1
        branched.stage('pgen').percent == 50d

        when:
        branched.taskCompleted(task('wrong-branch', 'VCF_BUILD', 'pgen-only', 'pgen-only', 1), false)

        then:
        thrown(IllegalArgumentException)
        branched.stage('vcf').completedFiles == 1
    }

    def 'parent completion boundaries ignore shards but count unsharded files'() {
        given:
        def mixed = new ProgressState('mixed-inputs')
        mixed.registerSourceFiles(['chr1', 'chr22'])
        mixed.registerStage('normalize', 'Normalize variants')
        mixed.mapProcess('NORMALIZE', 'normalize', 'parent')

        when:
        mixed.taskCompleted(task('shard', 'NORMALIZE', 'chr22.shard_1', 'chr22', 1), false)

        then:
        mixed.stage('normalize').completedFiles == 0

        when:
        mixed.taskCompleted(task('unsharded', 'NORMALIZE', 'chr1', 'chr1', 1), false)

        then:
        mixed.stage('normalize').completedFiles == 1
        mixed.stage('normalize').percent == 50d
    }

    def 'mixed snapshot units render active file progress as indeterminate'() {
        given:
        state.taskStarted(task('bytes', 'SEQLAB_BUILD_SVAR2', 'chr22.shard_1', 'chr22', 1))
        state.taskStarted(task('records', 'SEQLAB_BUILD_SVAR2', 'chr22.shard_2', 'chr22', 1))
        state.applySnapshot(snapshot('bytes', 'chr22.shard_1', 'chr22', 1, 50, 100))
        state.applySnapshot(snapshot('records', 'chr22.shard_2', 'chr22', 1, 5, 10, 'records'))

        when:
        def active = state.activeFile('build_svar2', 'chr22')

        then:
        active.completed == 0L
        active.total == null
        active.percent == null
        active.unit == null
    }

    def 'mixed concurrent shard phases render active file progress as indeterminate'() {
        given:
        state.taskStarted(task('phase-a', 'SEQLAB_BUILD_SVAR2', 'chr22.shard_1', 'chr22', 1))
        state.taskStarted(task('phase-b', 'SEQLAB_BUILD_SVAR2', 'chr22.shard_2', 'chr22', 1))
        state.applySnapshot(snapshot(
            'phase-a', 'chr22.shard_1', 'chr22', 1, 80, 100,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'read',
        ))
        state.applySnapshot(snapshot(
            'phase-b', 'chr22.shard_2', 'chr22', 1, 1, 4,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'write',
        ))

        when:
        def active = state.activeFile('build_svar2', 'chr22')

        then:
        active.completed == 0L
        active.total == null
        active.percent == null
        active.phase == null
        active.unit == null
    }

    def 'a retry with a new work directory supersedes a late prior failure'() {
        given:
        def first = task('aa/first', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 1)
        def second = task('bb/second', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 2)
        state.taskStarted(first)

        when:
        state.taskStarted(second)
        state.taskFailed(first, 'late exit 42')

        then:
        state.errorCount() == 0
        state.stageStatus('build_svar2') == 'running'

        when:
        state.taskCompleted(second, false)

        then:
        state.errorCount() == 0
        state.stage('build_svar2').completedFiles == 1
    }

    def 'a stable task ID also ignores failure from a superseded attempt'() {
        given:
        def first = task('stable', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 1)
        def second = task('stable', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 2)
        state.taskStarted(first)
        state.taskStarted(second)

        when:
        state.taskFailed(first, 'late exit 42')

        then:
        noExceptionThrown()
        state.errorCount() == 0
        state.stageStatus('build_svar2') == 'running'
    }

    def 'completed non-boundary shards remain in parent progress aggregation'() {
        given:
        def first = task('norm-1', 'BCFTOOLS_NORM', 'chr22.shard_1', 'chr22', 1)
        def second = task('norm-2', 'BCFTOOLS_NORM', 'chr22.shard_2', 'chr22', 1)
        state.taskStarted(first)
        state.taskStarted(second)
        state.applySnapshot(snapshot(
            'norm-1', 'chr22.shard_1', 'chr22', 1, 50, 50,
            'records', 'BCFTOOLS_NORM', 'prepare',
        ))
        state.applySnapshot(snapshot(
            'norm-2', 'chr22.shard_2', 'chr22', 1, 10, 50,
            'records', 'BCFTOOLS_NORM', 'prepare',
        ))

        expect:
        state.activeFile('prepare', 'chr22').percent == 60d

        when:
        state.taskCompleted(first, false)

        then:
        state.activeFile('prepare', 'chr22').percent == 60d

        when:
        state.taskCompleted(second, false)

        then:
        state.activeFiles('prepare', 4).first().percent == 60d
    }

    def 'one synchronized projection contains stages focus files and errors'() {
        given:
        state.taskStarted(task('projected', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 1))
        state.applySnapshot(snapshot('projected', 'chr22', 'chr22', 1, 25, 100))

        when:
        def projection = state.project(4)

        then:
        projection.stages*.stageId == ['prepare', 'build_svar2']
        projection.stages.find { it.stageId == 'build_svar2' }.state == 'running'
        projection.currentStageId == 'build_svar2'
        projection.activeFiles.first().percent == 25d
        projection.errorCount == 0
    }

    def 'a new phase may reset counters units and denominator before a terminal snapshot'() {
        given:
        def identity = task('phases', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 1)
        state.taskStarted(identity)

        expect:
        state.applySnapshot(snapshot(
            'phases', 'chr22', 'chr22', 1, 80, 100,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))
        state.applySnapshot(snapshot(
            'phases', 'chr22', 'chr22', 1, 0, 4,
            'chunks', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-b', 'running',
        ))
        state.applySnapshot(snapshot(
            'phases', 'chr22', 'chr22', 1, 4, 4,
            'chunks', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-b', 'completed',
        ))
        state.stage('build_svar2').completedFiles == 0
    }

    def 'same phase rejects denominator counter and unit changes'() {
        given:
        def identity = task('same-phase', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 1)
        state.taskStarted(identity)
        state.applySnapshot(snapshot(
            'same-phase', 'chr22', 'chr22', 1, 80, 100,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))

        when:
        state.applySnapshot(snapshot(
            'same-phase', 'chr22', 'chr22', 1, 80, 120,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))

        then:
        thrown(IllegalArgumentException)

        when:
        state.applySnapshot(snapshot(
            'same-phase', 'chr22', 'chr22', 1, 79, 100,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))

        then:
        thrown(IllegalArgumentException)

        when:
        state.applySnapshot(snapshot(
            'same-phase', 'chr22', 'chr22', 1, 90, 100,
            'bytes', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))

        then:
        thrown(IllegalArgumentException)
    }

    def 'a snapshot from an older observed phase cannot replace the current phase'() {
        given:
        def identity = task('late-phase', 'SEQLAB_BUILD_SVAR2', 'chr22', 'chr22', 1)
        state.taskStarted(identity)
        state.applySnapshot(snapshot(
            'late-phase', 'chr22', 'chr22', 1, 80, 100,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))
        state.applySnapshot(snapshot(
            'late-phase', 'chr22', 'chr22', 1, 0, 4,
            'chunks', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-b', 'running',
        ))

        when:
        state.applySnapshot(snapshot(
            'late-phase', 'chr22', 'chr22', 1, 90, 100,
            'records', 'SEQLAB_BUILD_SVAR2', 'build_svar2', 'phase-a', 'running',
        ))

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('phase')
        state.activeFile('build_svar2', 'chr22').phase == 'phase-b'
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
        long total,
        String unit = 'compressed_bytes',
        String process = 'SEQLAB_BUILD_SVAR2',
        String stageId = 'build_svar2',
        String phase = 'read',
        String snapshotState = 'running'
    ) {
        return ProgressSnapshot.fromMap([
            schema: 'nf-seqlab.progress/v1',
            run_id: 'aou-v8',
            stage_id: stageId,
            process: process,
            file_id: fileId,
            parent_file_id: parentFileId,
            task_id: taskId,
            attempt: attempt,
            state: snapshotState,
            phase: phase,
            completed: completed,
            total: total,
            unit: unit,
            percent: 100d * completed / total,
            message: 'Reading',
            updated_at: '2026-07-15T03:18:00Z',
        ])
    }
}
