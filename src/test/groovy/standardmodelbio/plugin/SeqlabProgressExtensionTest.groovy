package standardmodelbio.plugin

import nextflow.Session
import spock.lang.Specification

class SeqlabProgressExtensionTest extends Specification {

    def session = Mock(Session)
    def extension = new ExposedExtension()

    def setup() {
        session.getRunName() >> 'test-run'
        extension.attach(session)
    }

    def cleanup() {
        ProgressRuntimes.remove(session)
    }

    def 'register functions populate the shared runtime'() {
        when:
        extension.registerProgressInputs([
            [file_id: 'chr1', path: '/input/chr1.vcf.gz'],
            [file_id: 'chr22', path: '/input/chr22.vcf.gz'],
        ])
        extension.registerProgressStages(
            [[id: 'build_gvl', label: 'Build GVL']],
            [[process: 'SEQLAB_BUILD_GVL', stage: 'build_gvl', completion_boundary: true]],
        )

        then:
        ProgressRuntimes.getOrCreate(session).state.stage('build_gvl').expectedFiles == 2
    }

    def 'metadata enrichment preserves shard ID and assigns the parent source'() {
        when:
        Map enriched = extension.withProgressIdentity([
            id: 'chr22.shard_0001',
            parent_id: 'chr22',
            prepare_shard: '0001',
        ])

        then:
        enriched.id == 'chr22.shard_0001'
        enriched.file_id == 'chr22.shard_0001'
        enriched.parent_file_id == 'chr22'
        enriched.prepare_shard == '0001'
    }

    private static class ExposedExtension extends SeqlabProgressExtension {
        void attach(Session session) {
            init(session)
        }
    }
}

