package standardmodelbio.plugin.model

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.time.Instant

@CompileStatic
@Immutable(knownImmutableClasses = [Instant])
class ProgressSnapshot {

    static final String SCHEMA = 'nf-seqlab.progress/v1'
    static final Set<String> STATES = [
        'queued',
        'pending',
        'submitted',
        'running',
        'completed',
        'cached',
        'failed',
        'cancelled',
    ] as Set<String>

    String schema
    String runId
    String stageId
    String process
    String fileId
    String parentFileId
    String taskId
    int attempt
    String state
    String phase
    long completed
    Long total
    String unit
    Double percent
    String message
    Instant updatedAt

    static ProgressSnapshot fromMap(Map payload) {
        String schema = requiredString(payload, 'schema')
        if (schema != SCHEMA) {
            throw invalid('schema', "must equal ${SCHEMA}")
        }

        int attempt = requiredLong(payload, 'attempt') as int
        if (attempt < 1) {
            throw invalid('attempt', 'must be at least 1')
        }

        String state = requiredString(payload, 'state')
        if (!STATES.contains(state)) {
            throw invalid('state', "must be one of ${STATES.join(', ')}")
        }

        long completed = requiredLong(payload, 'completed')
        if (completed < 0) {
            throw invalid('completed', 'must be non-negative')
        }

        Long total = optionalLong(payload, 'total')
        if (total != null && total <= 0) {
            throw invalid('total', 'must be positive when present')
        }
        if (total != null && completed > total) {
            throw invalid('completed', 'cannot exceed total')
        }

        Double percent = optionalDouble(payload, 'percent')
        if (percent != null && (!Double.isFinite(percent) || percent < 0d || percent > 100d)) {
            throw invalid('percent', 'must be finite and between 0 and 100')
        }

        Instant updatedAt
        try {
            updatedAt = Instant.parse(requiredString(payload, 'updated_at'))
        }
        catch (Exception ignored) {
            throw invalid('updated_at', 'must be an ISO-8601 instant')
        }

        return new ProgressSnapshot(
            schema,
            requiredString(payload, 'run_id'),
            requiredString(payload, 'stage_id'),
            requiredString(payload, 'process'),
            requiredString(payload, 'file_id'),
            requiredString(payload, 'parent_file_id'),
            requiredString(payload, 'task_id'),
            attempt,
            state,
            requiredString(payload, 'phase'),
            completed,
            total,
            requiredString(payload, 'unit'),
            percent,
            optionalString(payload, 'message'),
            updatedAt,
        )
    }

    private static String requiredString(Map payload, String field) {
        Object value = payload[field]
        if (!(value instanceof CharSequence) || value.toString().trim().isEmpty()) {
            throw invalid(field, 'must be a non-empty string')
        }
        return value.toString()
    }

    private static String optionalString(Map payload, String field) {
        Object value = payload[field]
        return value == null ? null : value.toString()
    }

    private static long requiredLong(Map payload, String field) {
        Object value = payload[field]
        if (!(value instanceof Number)) {
            throw invalid(field, 'must be an integer')
        }
        Number number = (Number) value
        long result = number.longValue()
        if (number.doubleValue() != (double) result) {
            throw invalid(field, 'must be an integer')
        }
        return result
    }

    private static Long optionalLong(Map payload, String field) {
        return payload[field] == null ? null : requiredLong(payload, field)
    }

    private static Double optionalDouble(Map payload, String field) {
        Object value = payload[field]
        if (value == null) {
            return null
        }
        if (!(value instanceof Number)) {
            throw invalid(field, 'must be numeric')
        }
        return ((Number) value).doubleValue()
    }

    private static IllegalArgumentException invalid(String field, String reason) {
        return new IllegalArgumentException("Invalid progress field '${field}': ${reason}")
    }
}

