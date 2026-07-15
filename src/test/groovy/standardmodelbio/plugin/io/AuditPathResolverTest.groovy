package standardmodelbio.plugin.io

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.function.Function

class AuditPathResolverTest extends Specification {

    @TempDir
    Path directory

    def 'resolves local paths and file URIs with Nextflow path semantics'() {
        given:
        def resolver = new AuditPathResolver()
        Path local = directory.resolve('local-results')

        expect:
        resolver.resolve(local.toString(), directory) == local
        resolver.resolve(local.toUri().toString(), directory) == local
        resolver.resolve(null, directory) == directory
    }

    def 'delegates cloud URIs without corrupting their scheme or authority'() {
        given:
        List<String> parsed = []
        Path sentinel = directory.resolve('provider-path')
        def resolver = new AuditPathResolver(
            { String value ->
                parsed << value
                sentinel
            } as Function<String, Path>,
        )

        expect:
        resolver.resolve(uri, directory) == sentinel
        parsed == [uri]

        where:
        uri << [
            's3://aou-results/nf-seqlab/run-1',
            'gs://aou-results/nf-seqlab/run-1',
        ]
    }

    def 'passes a Windows path to Nextflow intact'() {
        given:
        List<String> parsed = []
        Path sentinel = directory.resolve('windows-provider-path')
        def resolver = new AuditPathResolver(
            { String value ->
                parsed << value
                sentinel
            } as Function<String, Path>,
        )

        when:
        Path result = resolver.resolve('C:\\Users\\researcher\\nf-seqlab', directory)

        then:
        result == sentinel
        parsed == ['C:\\Users\\researcher\\nf-seqlab']
    }
}
