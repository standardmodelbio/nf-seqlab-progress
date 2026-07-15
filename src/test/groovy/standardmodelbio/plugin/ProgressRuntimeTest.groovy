package standardmodelbio.plugin

import nextflow.Session
import spock.lang.Specification
import standardmodelbio.plugin.model.TaskIdentity

class ProgressRuntimeTest extends Specification {

    def 'run name is the externally visible progress identifier'() {
        given:
        def session = Mock(Session)
        session.getRunName() >> 'focused-curie'
        session.getUniqueId() >> UUID.fromString('84b037dc-bad7-4f6b-81d2-aa7e6f03b026')

        when:
        def runtime = ProgressRuntimes.getOrCreate(session)

        then:
        runtime.runId == 'focused-curie'
        runtime.runName == 'focused-curie'

        cleanup:
        ProgressRuntimes.remove(session)
    }

    def 'one runtime is shared by extension and observer for a session'() {
        given:
        def session = Mock(Session)

        expect:
        ProgressRuntimes.getOrCreate(session).is(ProgressRuntimes.getOrCreate(session))

        cleanup:
        ProgressRuntimes.remove(session)
    }

    def 'registers complete source totals and stage mappings before tasks'() {
        given:
        def runtime = new ProgressRuntime('test-run', 'Test run')

        when:
        runtime.registerInputs([
            [file_id: 'chr1', path: '/input/chr1.vcf.gz'],
            [file_id: 'chr22', path: '/input/chr22.vcf.gz'],
        ])
        runtime.registerStages(
            [[id: 'build_svar2', label: 'Build SVAR2']],
            [[process: 'SEQLAB_BUILD_SVAR2', stage: 'build_svar2', completion_boundary: true]],
        )

        then:
        runtime.state.stage('build_svar2').expectedFiles == 2
        runtime.state.stage('build_svar2').completedFiles == 0
    }

    def 'rejects a duplicate source ID with a different path'() {
        given:
        def runtime = new ProgressRuntime('test-run', 'Test run')
        runtime.registerInputs([[file_id: 'chr22', path: '/one/chr22.vcf.gz']])

        when:
        runtime.registerInputs([[file_id: 'chr22', path: '/two/chr22.vcf.gz']])

        then:
        thrown(IllegalArgumentException)
    }

    def 'registers optional file subsets for different stages'() {
        given:
        def runtime = new ProgressRuntime('branch-run', 'Branch run')
        runtime.registerInputs([
            [file_id: 'vcf-only', path: '/input/a.vcf.gz'],
            [file_id: 'pgen-only', path: '/input/b.pgen'],
            [file_id: 'both', path: '/input/c.vcf.gz'],
        ])

        when:
        runtime.registerStages(
            [
                [id: 'vcf', label: 'VCF branch', file_ids: ['vcf-only', 'both']],
                [id: 'pgen', label: 'PGEN branch', file_ids: ['pgen-only', 'both']],
            ],
            [
                [process: 'VCF_BUILD', stage: 'vcf', completion_boundary: true],
                [process: 'PGEN_BUILD', stage: 'pgen', completion_boundary: true],
            ],
        )

        then:
        runtime.state.stage('vcf').expectedFiles == 2
        runtime.state.stage('pgen').expectedFiles == 2
    }

    def 'preserves parent completion boundaries during runtime registration'() {
        given:
        def runtime = new ProgressRuntime('mixed-run', 'Mixed run')
        runtime.registerInputs([
            [file_id: 'chr1', path: '/input/chr1.vcf.gz'],
            [file_id: 'chr22', path: '/input/chr22.vcf.gz'],
        ])
        runtime.registerStages(
            [[id: 'normalize', label: 'Normalize variants']],
            [[process: 'NORMALIZE', stage: 'normalize', completion_boundary: 'parent']],
        )

        when:
        runtime.state.taskCompleted(
            new TaskIdentity('shard', 'NORMALIZE', 'chr22.shard_1', 'chr22', 1),
            false,
        )
        runtime.state.taskCompleted(
            new TaskIdentity('whole', 'NORMALIZE', 'chr1', 'chr1', 1),
            false,
        )

        then:
        runtime.state.stage('normalize').completedFiles == 1
    }
}
