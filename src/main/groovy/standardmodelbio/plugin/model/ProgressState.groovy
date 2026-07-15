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
        if (!stageId?.trim() || !label?.trim()) {
            throw new IllegalArgumentException('Stage ID and label must be non-empty')
        }
        StageRecord existing = stages[stageId]
        if (existing != null && existing.label != label) {
            throw new IllegalArgumentException("Stage '${stageId}' already has label '${existing.label}'")
        }
        stages.putIfAbsent(stageId, new StageRecord(stageId, label))
    }

    synchronized void mapProcess(String process, String stageId, boolean completionBoundary) {
        if (!process?.trim() || !stages.containsKey(stageId)) {
            throw new IllegalArgumentException("Cannot map process '${process}' to unknown stage '${stageId}'")
        }
        ProcessMapping mapping = new ProcessMapping(stageId, completionBoundary)
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

    synchronized void applySnapshot(ProgressSnapshot snapshot) {
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
        if (task.snapshot != null) {
            if (snapshot.completed < task.snapshot.completed) {
                throw new IllegalArgumentException("Progress regressed for task '${snapshot.taskId}'")
            }
            if (snapshot.total != task.snapshot.total) {
                throw new IllegalArgumentException("Progress denominator changed for task '${snapshot.taskId}'")
            }
        }
        task.snapshot = snapshot
        task.state = snapshot.state
    }

    synchronized void taskCompleted(TaskIdentity identity, boolean cached) {
        ProcessMapping mapping = requireMapping(identity.process)
        requireSource(identity.parentFileId)
        TaskRecord task = tasks[identity.taskId]
        if (task == null || identity.attempt > task.identity.attempt) {
            task = new TaskRecord(identity, mapping.stageId, cached ? 'cached' : 'completed')
            tasks[identity.taskId] = task
        }
        else if (identity.attempt < task.identity.attempt) {
            throw new IllegalArgumentException("Stale completion for task '${identity.taskId}'")
        }
        task.state = cached ? 'cached' : 'completed'
        if (mapping.completionBoundary) {
            stages[mapping.stageId].completedFiles.add(identity.parentFileId)
        }
    }

    synchronized void taskFailed(TaskIdentity identity, String message) {
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
        int expected = sourceFiles.size()
        int completed = stage.completedFiles.size()
        double percent = expected == 0 ? 0d : 100d * completed / expected
        return new StageView(stage.stageId, stage.label, completed, expected, percent)
    }

    synchronized ActiveFileView activeFile(String stageId, String parentFileId) {
        if (!stages.containsKey(stageId)) {
            throw new IllegalArgumentException("Unknown stage '${stageId}'")
        }
        requireSource(parentFileId)
        List<TaskRecord> active = tasks.values().findAll { TaskRecord task ->
            task.stageId == stageId &&
                task.identity.parentFileId == parentFileId &&
                task.state in ['pending', 'submitted', 'running']
        }.toList()
        if (active.isEmpty()) {
            return new ActiveFileView(parentFileId, 0L, null, null, null, null)
        }
        List<TaskRecord> measured = active.findAll { TaskRecord task -> task.snapshot?.total != null }
        if (measured.size() != active.size()) {
            return new ActiveFileView(parentFileId, 0L, null, null, active.first().snapshot?.phase, null)
        }
        long completed = measured.sum(0L) { TaskRecord task -> task.snapshot.completed } as long
        long total = measured.sum(0L) { TaskRecord task -> task.snapshot.total } as long
        double percent = total == 0L ? 0d : 100d * completed / total
        String unit = measured.collect { TaskRecord task -> task.snapshot.unit }.toSet().size() == 1
            ? measured.first().snapshot.unit
            : 'work_units'
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
        if (!sourceFiles.isEmpty() && stage.completedFiles.size() == sourceFiles.size()) {
            return 'completed'
        }
        if (!stageTasks.isEmpty()) {
            return 'running'
        }
        return 'queued'
    }

    synchronized List<ActiveFileView> activeFiles(String stageId, int maximum) {
        LinkedHashSet<String> parentIds = new LinkedHashSet<>()
        tasks.values().each { TaskRecord task ->
            if (task.stageId == stageId && task.state in ['pending', 'submitted', 'running']) {
                parentIds.add(task.identity.parentFileId)
            }
        }
        return parentIds
            .take(Math.max(0, maximum))
            .collect { String parentFileId -> activeFile(stageId, parentFileId) }
    }

    synchronized int errorCount() {
        return tasks.values().count { TaskRecord task -> task.state == 'failed' }.intValue()
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
class ProcessMapping {
    String stageId
    boolean completionBoundary
}

@CompileStatic
class StageRecord {
    final String stageId
    final String label
    final Set<String> completedFiles = new LinkedHashSet<>()

    StageRecord(String stageId, String label) {
        this.stageId = stageId
        this.label = label
    }
}

@CompileStatic
class TaskRecord {
    final TaskIdentity identity
    final String stageId
    String state
    String failure
    ProgressSnapshot snapshot

    TaskRecord(TaskIdentity identity, String stageId, String state) {
        this.identity = identity
        this.stageId = stageId
        this.state = state
    }
}
