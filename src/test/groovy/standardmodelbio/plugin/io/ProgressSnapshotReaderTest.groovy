package standardmodelbio.plugin.io

import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.TempDir
import standardmodelbio.plugin.model.ProgressSnapshot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ProgressSnapshotReaderTest extends Specification {

    @TempDir
    Path directory

    def reader = new ProgressSnapshotReader()

    def 'missing snapshot is an ordinary empty read'() {
        expect:
        reader.read(directory.resolve('.nf-seqlab-progress.json')) == null
    }

    def 'reads a valid atomically replaced snapshot'() {
        given:
        Path target = directory.resolve('.nf-seqlab-progress.json')
        Path temporary = directory.resolve('.nf-seqlab-progress.json.tmp')
        Files.writeString(temporary, JsonOutput.toJson(snapshot()))
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)

        when:
        ProgressSnapshot result = reader.read(target)

        then:
        result.taskId == 'aa/bbccdd'
        result.completed == 25L
        result.total == 100L
    }

    def 'rejects malformed JSON without deleting the producer file'() {
        given:
        Path target = directory.resolve('.nf-seqlab-progress.json')
        Files.writeString(target, '{bad json')

        when:
        reader.read(target)

        then:
        thrown(IllegalArgumentException)
        Files.exists(target)
    }

    private static Map<String, Object> snapshot() {
        return [
            schema: 'nf-seqlab.progress/v1',
            run_id: 'test-run',
            stage_id: 'build_svar2',
            process: 'SEQLAB_BUILD_SVAR2',
            file_id: 'chr22',
            parent_file_id: 'chr22',
            task_id: 'aa/bbccdd',
            attempt: 1,
            state: 'running',
            phase: 'read',
            completed: 25,
            total: 100,
            unit: 'compressed_bytes',
            percent: 25d,
            message: 'Reading',
            updated_at: '2026-07-15T03:33:00Z',
        ]
    }
}

