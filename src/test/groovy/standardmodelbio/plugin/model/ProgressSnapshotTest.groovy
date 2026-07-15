package standardmodelbio.plugin.model

import spock.lang.Specification
import spock.lang.Unroll

class ProgressSnapshotTest extends Specification {

    def 'parses a valid running snapshot'() {
        when:
        def snapshot = ProgressSnapshot.fromMap(validSnapshot())

        then:
        snapshot.schema == 'nf-seqlab.progress/v1'
        snapshot.fileId == 'chr22'
        snapshot.parentFileId == 'chr22'
        snapshot.attempt == 1
        snapshot.completed == 15_648_800_024L
        snapshot.total == 44_090_635_573L
        snapshot.percent == 35.49d
    }

    @Unroll
    def 'rejects invalid snapshot field #field'() {
        given:
        def payload = validSnapshot()
        payload[field] = value

        when:
        ProgressSnapshot.fromMap(payload)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(field)

        where:
        field       | value
        'schema'    | 'other/v1'
        'file_id'   | ''
        'attempt'   | 0
        'state'     | 'maybe'
        'phase'     | ''
        'completed' | -1
        'total'     | 0
        'percent'   | Double.NaN
        'percent'   | 101d
    }

    def 'rejects completed units beyond the denominator'() {
        given:
        def payload = validSnapshot()
        payload.completed = payload.total + 1L

        when:
        ProgressSnapshot.fromMap(payload)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('completed')
    }

    private static Map<String, Object> validSnapshot() {
        return [
            schema: 'nf-seqlab.progress/v1',
            run_id: 'aou-v8',
            stage_id: 'build_svar2',
            process: 'SEQLAB_BUILD_SVAR2',
            file_id: 'chr22',
            parent_file_id: 'chr22',
            task_id: '99/b6e5e2',
            attempt: 1,
            state: 'running',
            phase: 'read',
            completed: 15_648_800_024L,
            total: 44_090_635_573L,
            unit: 'compressed_bytes',
            percent: 35.49d,
            message: 'Reading phased VCF',
            updated_at: '2026-07-15T03:18:00Z',
        ]
    }
}
