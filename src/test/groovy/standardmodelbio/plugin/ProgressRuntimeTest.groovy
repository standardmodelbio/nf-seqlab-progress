package standardmodelbio.plugin

import nextflow.Session
import spock.lang.Specification

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
}
