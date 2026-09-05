package standardmodelbio.plugin.model

import groovy.transform.CompileStatic
import groovy.transform.Immutable

@CompileStatic
class ProgressState {

    final String runId

    private final LinkedHashSet<String> sourceFiles = new LinkedHashSet<>()
    private final LinkedHashMap<String, StageRecord> stages = new LinkedHashMap<>()
    private final Map<String, ProcessMapping> processMappings = new LinkedHashMap<>()
    private final Map<String, TaskRecord> tasks = new LinkedHashMap<>()

    ProgressState(String runId) {
        if (!runId?.trim()) {
            throw new IllegalArgumentException('runId must be non-empty')
        }
        this.runId = runId
    }

    synchronized void registerSourceFiles(Collection<String> fileIds) {
        fileIds.each { String fileId ->
            if (!fileId?.trim()) {
                throw new IllegalArgumentException('fileId must be non-empty')
            }
            sourceFiles.add(fileId)
        }
    }

    synchronized void registerStage(String stageId, String label) {
        registerStage(stageId, label, null)
    }

    synchronized void registerStage(String stageId, String label, Collection<String> fileIds) {
        if (!stageId?.trim() || !label?.trim()) {
            throw new IllegalArgumentException('Stage ID and label must be non-empty')
        }
        Set<String> applicableFiles = null
        if (fileIds != null) {
            applicableFiles = new LinkedHashSet<>()
            fileIds.each { String fileId ->
                requireSource(fileId)
                applicableFiles.add(fileId)
            }
        }
        StageRecord existing = stages[stageId]
        if (existing != null &&
            (existing.label != label || existing.applicableFiles != applicableFiles)) {
            throw new IllegalArgumentException("Stage '${stageId}' already has a different definition")
        }
        stages.putIfAbsent(stageId, new StageRecord(stageId, label, applicableFiles))
    }

    synchronized void mapProcess(String process, String stageId, Object completionBoundary) {
        if (!process?.trim() || !stages.containsKey(stageId)) {
            throw new IllegalArgumentException("Cannot map process '${process}' to unknown stage '${stageId}'")
        }
        ProcessMapping mapping = new ProcessMapping(
            stageId,
            completionBoundaryMode(completionBoundary),
        )
        ProcessMapping existing = processMappings[process]
        if (existing != null && existing != mapping) {
            throw new IllegalArgumentException("Process '${process}' already has a different mapping")
        }
        processMappings[process] = mapping
    }

    synchronized void taskStarted(TaskIdentity identity) {
        taskTransition(identity, 'running')
    }

    synchronized void taskTransition(TaskIdentity identity, String transition) {
        if (!(transition in ['pending', 'submitted', 'running'])) {
            throw new IllegalArgumentException("Unsupported task transition '${transition}'")
        }
        ProcessMapping mapping = requireMapping(identity.process)
        requireSource(identity.parentFileId)
        removeSupersededAttempts(identity)
        TaskRecord existing = tasks[identity.taskId]
        if (existing != null && identity.attempt < existing.identity.attempt) {
            throw new IllegalArgumentException("Stale attempt for task '${identity.taskId}'")
        }
        if (existing != null && identity.attempt == existing.identity.attempt) {
            if (existing.identity.process != identity.process ||
                existing.identity.fileId != identity.fileId ||
                existing.identity.parentFileId != identity.parentFileId) {
                throw new IllegalArgumentException("Task identity changed for '${identity.taskId}'")
            }
            existing.state = transition
            return
        }
        tasks[identity.taskId] = new TaskRecord(identity, mapping.stageId, transition)
    }

    synchronized boolean applySnapshot(ProgressSnapshot snapshot) {
        if (snapshot.runId != runId) {
            throw new IllegalArgumentException("Snapshot run '${snapshot.runId}' does not match '${runId}'")
        }
        TaskRecord task = tasks[snapshot.taskId]
        if (task == null) {
            throw new IllegalArgumentException("Unknown task '${snapshot.taskId}'")
        }
        if (snapshot.attempt != task.identity.attempt) {
            throw new IllegalArgumentException("Stale snapshot attempt for task '${snapshot.taskId}'")
        }
        if (snapshot.process != task.identity.process ||
            snapshot.fileId != task.identity.fileId ||
            snapshot.parentFileId != task.identity.parentFileId ||
            snapshot.stageId != task.stageId) {
            throw new IllegalArgumentException("Snapshot identity does not match task '${snapshot.taskId}'")
        }
        if (task.state in ['completed', 'cached', 'failed', 'cancelled']) {
            return false
        }
        if (task.snapshot != null) {
            if (snapshot.phase == task.snapshot.phase) {
                if (snapshot.completed < task.snapshot.completed) {
                    throw new IllegalArgumentException("Progress regressed for task '${snapshot.taskId}'")
                }
                if (snapshot.total != task.snapshot.total) {
                    throw new IllegalArgumentException("Progress denominator changed for task '${snapshot.taskId}'")
                }
                if (snapshot.unit != task.snapshot.unit) {
                    throw new IllegalArgumentException("Progress unit changed for task '${snapshot.taskId}'")
                }
            }
            else if (task.observedPhases.contains(snapshot.phase)) {
                throw new IllegalArgumentException(
                    "Stale progress phase '${snapshot.phase}' for task '${snapshot.taskId}'"
                )
            }
        }
        task.observedPhases.add(snapshot.phase)
        task.snapshot = snapshot
        task.state = snapshot.state
        return true
    }

    synchronized void taskCompleted(TaskIdentity identity, boolean cached) {
        ProcessMapping mapping = requireMapping(identity.process)
        requireSource(identity.parentFileId)
        if (hasNewerAttempt(identity)) {
            return
        }
        removeSupersededAttempts(identity)
        StageRecord stage = stages[mapping.stageId]
        if (!applicableFiles(stage).contains(identity.parentFileId)) {
            throw new IllegalArgumentException(
                "Source file '${identity.parentFileId}' is not applicable to stage '${mapping.stageId}'"
            )
        }
        TaskRecord task = tasks[identity.taskId]
        if (task == null || identity.attempt > task.identity.attempt) {
            task = new TaskRecord(identity, mapping.stageId, cached ? 'cached' : 'completed')
            tasks[identity.taskId] = task
        }
        else if (identity.attempt < task.identity.attempt) {
            throw new IllegalArgumentException("Stale completion for task '${identity.taskId}'")
        }
        task.state = cached ? 'cached' : 'completed'
        if (marksSourceComplete(identity)) {
            stage.completedFiles.add(identity.parentFileId)
        }
    }

    synchronized void taskFailed(TaskIdentity identity, String message) {
        if (hasNewerAttempt(identity)) {
            return
        }
        TaskRecord task = tasks[identity.taskId]
        if (task == null || task.identity.attempt != identity.attempt) {
            throw new IllegalArgumentException("Unknown or stale failed task '${identity.taskId}'")
        }
        task.state = 'failed'
        task.failure = message
    }

    synchronized StageView stage(String stageId) {
        StageRecord stage = stages[stageId]
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage '${stageId}'")
        }
        int expected = applicableFiles(stage).size()
        int completed = stage.completedFiles.size()
        double percent = expected == 0 ? 0d : 100d * completed / expected
        return new StageView(stage.stageId, stage.label, completed, expected, percent)
    }

    synchronized ActiveFileView activeFile(String stageId, String parentFileId) {
        StageRecord stage = stages[stageId]
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage '${stageId}'")
        }
        requireSource(parentFileId)
        if (stage.completedFiles.contains(parentFileId)) {
            return new ActiveFileView(parentFileId, 0L, null, null, null, null)
        }
        List<TaskRecord> contributors = tasks.values().findAll { TaskRecord task ->
            task.stageId == stageId &&
                task.identity.parentFileId == parentFileId &&
                contributesToActiveFile(task)
        }.toList()
        if (contributors.isEmpty()) {
            return new ActiveFileView(parentFileId, 0L, null, null, null, null)
        }
        List<TaskRecord> measured = contributors.findAll { TaskRecord task -> task.snapshot?.total != null }
        if (measured.size() != contributors.size()) {
            return new ActiveFileView(parentFileId, 0L, null, null, contributors.first().snapshot?.phase, null)
        }
        Set<String> units = measured.collect { TaskRecord task -> task.snapshot.unit }.toSet()
        Set<String> phases = measured.collect { TaskRecord task -> task.snapshot.phase }.toSet()
        if (units.size() != 1 || phases.size() != 1) {
            return new ActiveFileView(
                parentFileId,
                0L,
                null,
                null,
                phases.size() == 1 ? measured.first().snapshot.phase : null,
                null,
            )
        }
        long completed = measured.sum(0L) { TaskRecord task -> task.snapshot.completed } as long
        long total = measured.sum(0L) { TaskRecord task -> task.snapshot.total } as long
        double percent = total == 0L ? 0d : 100d * completed / total
        String unit = measured.first().snapshot.unit
        return new ActiveFileView(parentFileId, completed, total, percent, measured.first().snapshot.phase, unit)
    }

    synchronized List<String> stageIds() {
        return new ArrayList<>(stages.keySet())
    }

    synchronized String stageStatus(String stageId) {
        StageRecord stage = stages[stageId]
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage '${stageId}'")
        }
        List<TaskRecord> stageTasks = tasks.values()
            .findAll { TaskRecord task -> task.stageId == stageId }
            .toList()
        if (stageTasks.any { TaskRecord task -> task.state == 'failed' }) {
            return 'failed'
        }
        Set<String> expectedFiles = applicableFiles(stage)
        if (!expectedFiles.isEmpty() && stage.completedFiles.size() == expectedFiles.size()) {
            return 'completed'
        }
        if (!stageTasks.isEmpty()) {
            return 'running'
        }
        return 'queued'
    }

    synchronized List<ActiveFileView> activeFiles(String stageId, int maximum) {
        StageRecord stage = stages[stageId]
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage '${stageId}'")
        }
        LinkedHashSet<String> parentIds = new LinkedHashSet<>()
        tasks.values().each { TaskRecord task ->
            if (task.stageId == stageId &&
                !stage.completedFiles.contains(task.identity.parentFileId) &&
                contributesToActiveFile(task)) {
                parentIds.add(task.identity.parentFileId)
            }
        }
        return parentIds
            .take(Math.max(0, maximum))
            .collect { String parentFileId -> activeFile(stageId, parentFileId) }
    }

    private int publishedFiles
    private long publishedBytes
    private String lastPublishTarget
    private long lastPublishAtMillis

    /** Record a completed publish (one Nextflow output path, possibly a directory). */
    synchronized void publishCompleted(String target, long bytes, long atMillis) {
        publishedFiles++
        publishedBytes += Math.max(0L, bytes)
        lastPublishTarget = target
        lastPublishAtMillis = atMillis
    }

    synchronized int publishedFiles() {
        return publishedFiles
    }

    synchronized long publishedBytes() {
        return publishedBytes
    }

    synchronized String lastPublishTarget() {
        return lastPublishTarget
    }

    synchronized long lastPublishAtMillis() {
        return lastPublishAtMillis
    }

    synchronized int errorCount() {
        return tasks.values().count { TaskRecord task -> task.state == 'failed' }.intValue()
    }

    synchronized ProgressProjection project(int maximumActiveFiles) {
        List<StageProjection> stageViews = stages.values().collect { StageRecord record ->
            StageView counts = stage(record.stageId)
            return new StageProjection(
                counts.stageId,
                counts.label,
                stageStatus(record.stageId),
                counts.completedFiles,
                counts.expectedFiles,
                counts.percent,
            )
        }
        StageProjection current = stageViews.find { StageProjection stage ->
            stage.state in ['running', 'failed']
        }
        if (current == null) {
            current = stageViews.find { StageProjection stage -> stage.state == 'queued' }
        }
        if (current == null && !stageViews.isEmpty()) {
            current = stageViews.last()
        }
        List<ActiveFileView> files = current == null
            ? []
            : activeFiles(current.stageId, maximumActiveFiles)
        return new ProgressProjection(
            stageViews,
            current?.stageId,
            files,
            errorCount(),
            publishedFiles,
            publishedBytes,
            lastPublishTarget,
            lastPublishAtMillis,
        )
    }

    private ProcessMapping requireMapping(String process) {
        ProcessMapping mapping = processMappings[process]
        if (mapping == null) {
            throw new IllegalArgumentException("Unmapped process '${process}'")
        }
        return mapping
    }

    private void requireSource(String fileId) {
        if (!sourceFiles.contains(fileId)) {
            throw new IllegalArgumentException("Unknown source file '${fileId}'")
        }
    }

    private Set<String> applicableFiles(StageRecord stage) {
        return stage.applicableFiles == null ? sourceFiles : stage.applicableFiles
    }

    private boolean contributesToActiveFile(TaskRecord task) {
        if (task.state in ['pending', 'submitted', 'running']) {
            return true
        }
        return task.state in ['completed', 'cached'] &&
            task.snapshot != null &&
            !marksSourceComplete(task.identity)
    }

    private boolean marksSourceComplete(TaskIdentity identity) {
        ProcessMapping mapping = requireMapping(identity.process)
        return mapping.completionBoundary == 'always' ||
            (mapping.completionBoundary == 'parent' && identity.fileId == identity.parentFileId)
    }

    private void removeSupersededAttempts(TaskIdentity identity) {
        List<String> superseded = tasks.findAll { String taskId, TaskRecord task ->
            sameLogicalTask(task.identity, identity) && task.identity.attempt < identity.attempt
        }.keySet().toList()
        superseded.each { String taskId -> tasks.remove(taskId) }
    }

    private boolean hasNewerAttempt(TaskIdentity identity) {
        return tasks.values().any { TaskRecord task ->
            sameLogicalTask(task.identity, identity) && task.identity.attempt > identity.attempt
        }
    }

    private static boolean sameLogicalTask(TaskIdentity left, TaskIdentity right) {
        return left.process == right.process &&
            left.fileId == right.fileId &&
            left.parentFileId == right.parentFileId
    }

    private static String completionBoundaryMode(Object value) {
        if (value instanceof Boolean) {
            return value as boolean ? 'always' : 'never'
        }
        String normalized = value == null ? 'false' : value.toString().trim().toLowerCase(Locale.ROOT)
        if (normalized == 'true') {
            return 'always'
        }
        if (normalized == 'false') {
            return 'never'
        }
        if (normalized == 'parent') {
            return 'parent'
        }
        throw new IllegalArgumentException(
            "completion_boundary must be true, false, or 'parent'"
        )
    }
}

@CompileStatic
@Immutable
class StageView {
    String stageId
    String label
    int completedFiles
    int expectedFiles
    double percent
}

@CompileStatic
@Immutable
class ActiveFileView {
    String fileId
    long completed
    Long total
    Double percent
    String phase
    String unit
}

@CompileStatic
@Immutable
class StageProjection {
    String stageId
    String label
    String state
    int completedFiles
    int expectedFiles
    double percent
}

@CompileStatic
@Immutable
class ProgressProjection {
    List<StageProjection> stages
    String currentStageId
    List<ActiveFileView> activeFiles
    int errorCount
    int publishedFiles
    long publishedBytes
    String lastPublishTarget
    long lastPublishAtMillis
}

@CompileStatic
@Immutable
class ProcessMapping {
    String stageId
    String completionBoundary
}

@CompileStatic
class StageRecord {
    final String stageId
    final String label
    final Set<String> applicableFiles
    final Set<String> completedFiles = new LinkedHashSet<>()

    StageRecord(String stageId, String label, Set<String> applicableFiles) {
        this.stageId = stageId
        this.label = label
        this.applicableFiles = applicableFiles == null
            ? null
            : Collections.unmodifiableSet(new LinkedHashSet<>(applicableFiles))
    }
}

@CompileStatic
class TaskRecord {
    final TaskIdentity identity
    final String stageId
    final Set<String> observedPhases = new LinkedHashSet<>()
    String state
    String failure
    ProgressSnapshot snapshot

    TaskRecord(TaskIdentity identity, String stageId, String state) {
        this.identity = identity
        this.stageId = stageId
        this.state = state
    }
}
