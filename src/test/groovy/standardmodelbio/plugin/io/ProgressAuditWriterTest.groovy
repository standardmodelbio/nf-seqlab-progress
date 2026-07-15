package standardmodelbio.plugin.io

import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

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
}

