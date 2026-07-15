package standardmodelbio.plugin

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.trace.AnsiLogObserver
import nextflow.trace.TraceRecord
import nextflow.trace.WorkflowStats
import nextflow.trace.event.TaskEvent
import org.fusesource.jansi.AnsiConsole
import standardmodelbio.plugin.io.AuditPathResolver
import standardmodelbio.plugin.io.ProgressAuditWriter
import standardmodelbio.plugin.io.ProgressSnapshotReader
import standardmodelbio.plugin.model.ProgressSnapshot
import standardmodelbio.plugin.model.TaskIdentity
import standardmodelbio.plugin.render.DashboardRenderer
import standardmodelbio.plugin.render.DashboardView
import standardmodelbio.plugin.render.RenderMode
import standardmodelbio.plugin.render.TerminalCapabilities

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Slf4j
class SeqlabProgressObserver extends AnsiLogObserver {

    private final ProgressRuntime runtime
    private final boolean ansiClaimed
    private final ProgressSnapshotReader snapshotReader = new ProgressSnapshotReader()
    private final AuditPathResolver auditPathResolver = new AuditPathResolver()
    private final DashboardRenderer dashboardRenderer = new DashboardRenderer()
    private final Map<String, TrackedTask> activeTasks = new ConcurrentHashMap<>()
    private final Set<String> warnedSnapshots = ConcurrentHashMap.newKeySet()
    private final Map<String, String> terminalEnvironmentOverride
    private final Object lifecycleLock = new Object()

    private Session session
    private TerminalCapabilities capabilities = new TerminalCapabilities(
        RenderMode.PLAIN,
        120,
        false,
        false,
        false,
    )
    private ScheduledExecutorService poller
    private ProgressAuditWriter auditWriter
    private int maximumActiveFiles = 4
    private String requestedMode = 'auto'
    private Map<String, String> terminalEnvironment = Collections.emptyMap()
    private boolean terminalUnicode
    private long frame
    private String lastPlainFrame
    private int snapshotWarnings
    private int ownedDashboardLines
    private boolean closing

    SeqlabProgressObserver(ProgressRuntime runtime) {
        this(runtime, false, null)
    }

    SeqlabProgressObserver(ProgressRuntime runtime, boolean ansiClaimed) {
        this(runtime, ansiClaimed, null)
    }

    SeqlabProgressObserver(
        ProgressRuntime runtime,
        boolean ansiClaimed,
        Map<String, String> terminalEnvironmentOverride
    ) {
        this.runtime = runtime
        this.ansiClaimed = ansiClaimed
        this.terminalEnvironmentOverride = terminalEnvironmentOverride == null
            ? null
            : new LinkedHashMap<>(terminalEnvironmentOverride)
    }

    boolean getAnsiClaimed() {
        return ansiClaimed
    }

    int getSnapshotWarnings() {
        return snapshotWarnings
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        Map params = session.config?.params as Map ?: Collections.emptyMap()
        requestedMode = (params['progress_mode'] ?: 'auto').toString()
        terminalEnvironment = terminalEnvironmentOverride == null
            ? new LinkedHashMap<>(System.getenv())
            : new LinkedHashMap<>(terminalEnvironmentOverride)
        int width = integerValue(
            terminalEnvironment['TERMINAL_WIDTH'],
            integerValue(terminalEnvironment['COLUMNS'], 120),
        )
        maximumActiveFiles = integerValue(params['progress_max_active_files'], 4)
        terminalUnicode = StandardCharsets.UTF_8.equals(java.nio.charset.Charset.defaultCharset())
        capabilities = TerminalCapabilities.detect(
            requestedMode,
            session.ansiLog,
            width,
            terminalEnvironment,
            terminalUnicode,
        )

        if (capabilities.cursorAddressing &&
            (!ansiClaimed || !ConsoleSlot.get(session)?.is(this) || !AnsiLineAccounting.compatible())) {
            capabilities = new TerminalCapabilities(RenderMode.PLAIN, width, false, terminalUnicode, false)
        }

        Path auditRoot = auditPathResolver.resolve(params['outdir'], session.outputDir)
        if (auditRoot != null) {
            try {
                auditWriter = new ProgressAuditWriter(
                    auditRoot.resolve('pipeline_info/progress.jsonl')
                )
            }
            catch (Exception error) {
                log.warn "Unable to create nf-seqlab progress audit: ${error.message}"
            }
        }

        if (capabilities.cursorAddressing) {
            super.onFlowCreate(session)
        }
        else {
            renderPlainIfChanged()
        }

        double refreshSeconds = doubleValue(params['progress_refresh_seconds'], 1d)
        long refreshMillis = Math.max(100L, Math.round(refreshSeconds * 1000d))
        poller = Executors.newSingleThreadScheduledExecutor { Runnable runnable ->
            Thread thread = new Thread(runnable, 'nf-seqlab-progress-poller')
            thread.daemon = true
            return thread
        }
        poller.scheduleWithFixedDelay(
            { pollSnapshots() } as Runnable,
            refreshMillis,
            refreshMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    @Override
    void onTaskPending(TaskEvent event) {
        synchronized (lifecycleLock) {
            if (!closing) {
                transition(event, 'pending')
            }
        }
    }

    @Override
    void onTaskSubmit(TaskEvent event) {
        synchronized (lifecycleLock) {
            if (closing) {
                return
            }
            if (capabilities.cursorAddressing) {
                super.onTaskSubmit(event)
            }
            transition(event, 'submitted')
        }
    }

    @Override
    void onTaskStart(TaskEvent event) {
        synchronized (lifecycleLock) {
            if (!closing) {
                transition(event, 'running')
            }
        }
    }

    @Override
    void onTaskComplete(TaskEvent event) {
        synchronized (lifecycleLock) {
            if (closing) {
                return
            }
            TaskIdentity identity = identity(event)
            if (identity == null) {
                return
            }
            pollTrackedSnapshot(identity)
            if (successful(event.trace)) {
                runtime.state.taskCompleted(identity, false)
            }
            else {
                runtime.state.taskFailed(identity, failureMessage(event.trace))
            }
            removeActiveTask(identity)
            recordAndRender()
        }
    }

    @Override
    void onTaskCached(TaskEvent event) {
        synchronized (lifecycleLock) {
            if (closing) {
                return
            }
            TaskIdentity identity = identity(event)
            if (identity == null) {
                return
            }
            pollTrackedSnapshot(identity)
            runtime.state.taskCompleted(identity, true)
            removeActiveTask(identity)
            recordAndRender()
        }
    }

    @Override
    void onFlowError(TaskEvent event) {
        synchronized (lifecycleLock) {
            if (closing) {
                return
            }
            TaskIdentity identity = identity(event)
            if (identity != null) {
                pollTrackedSnapshot(identity)
                runtime.state.taskFailed(identity, failureMessage(event.trace))
                removeActiveTask(identity)
            }
            recordAndRender()
        }
    }

    @Override
    void onFlowComplete() {
        synchronized (lifecycleLock) {
            if (closing) {
                return
            }
            closing = true
        }
        stopPoller()
        synchronized (lifecycleLock) {
            try {
                pollSnapshotsInternal()
                recordAndRender()
                if (capabilities.cursorAddressing) {
                    super.onFlowComplete()
                }
            }
            finally {
                cleanupSafely('progress audit writer') {
                    auditWriter?.close()
                }
                if (session != null) {
                    if (ansiClaimed) {
                        cleanupSafely('ANSI console slot') {
                            ConsoleSlot.clearIfOwned(session, this)
                        }
                    }
                    cleanupSafely('dashboard claim') {
                        DashboardClaims.remove(session)
                    }
                    cleanupSafely('progress runtime') {
                        ProgressRuntimes.remove(session)
                    }
                }
            }
        }
    }

    void pollSnapshots() {
        synchronized (lifecycleLock) {
            if (!closing) {
                pollSnapshotsInternal()
            }
        }
    }

    private void pollSnapshotsInternal() {
        boolean changed = false
        activeTasks.values().each { TrackedTask tracked ->
            changed |= pollTrackedSnapshot(tracked)
        }
        if (changed) {
            recordAndRender()
        }
    }

    private boolean pollTrackedSnapshot(TaskIdentity identity) {
        TrackedTask tracked = activeTasks[identity.taskId]
        return tracked != null && tracked.identity.attempt == identity.attempt
            ? pollTrackedSnapshot(tracked)
            : false
    }

    private boolean pollTrackedSnapshot(TrackedTask tracked) {
        try {
            ProgressSnapshot snapshot = snapshotReader.read(tracked.snapshotPath)
            if (snapshot == null || snapshot == tracked.snapshot) {
                return false
            }
            boolean applied = runtime.state.applySnapshot(snapshot)
            tracked.snapshot = snapshot
            return applied
        }
        catch (Exception error) {
            String warningKey = "${tracked.identity.taskId}:${tracked.identity.attempt}"
            if (warnedSnapshots.add(warningKey)) {
                snapshotWarnings++
                log.warn "Ignoring invalid nf-seqlab progress snapshot for ${warningKey}: ${error.message}"
            }
            return false
        }
    }

    private void stopPoller() {
        ScheduledExecutorService executor = poller
        poller = null
        if (executor == null) {
            return
        }
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn 'Timed out waiting for nf-seqlab progress poller to stop'
            }
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt()
            log.warn 'Interrupted while waiting for nf-seqlab progress poller to stop'
        }
    }

    private static void cleanupSafely(String resource, Closure<?> action) {
        try {
            action.call()
        }
        catch (Exception error) {
            log.warn "Unable to release nf-seqlab ${resource}: ${error.message}"
        }
    }

    @Override
    synchronized protected void renderProgress(WorkflowStats stats) {
        renderProjectedProgress(stats, runtime.dashboard(maximumActiveFiles))
    }

    private synchronized void renderProjectedProgress(WorkflowStats stats, DashboardView view) {
        AnsiLineAccounting.eraseOwnedLines(AnsiConsole.out, ownedDashboardLines)
        ownedDashboardLines = 0
        super.renderProgress(stats)
        if (!capabilities.cursorAddressing) {
            return
        }
        int width = AnsiLineAccounting.getTerminalColumns(this, capabilities.width)
        capabilities = TerminalCapabilities.detect(
            requestedMode,
            true,
            width,
            terminalEnvironment,
            terminalUnicode,
        )
        String dashboard = dashboardRenderer.render(view, capabilities, frame++)
        if (dashboard) {
            printAndCountLines(dashboard)
            ownedDashboardLines = AnsiLineAccounting.physicalLines(dashboard, width)
            AnsiConsole.out.flush()
        }
    }

    private void transition(TaskEvent event, String state) {
        TaskIdentity identity = identity(event)
        if (identity == null) {
            return
        }
        runtime.state.taskTransition(identity, state)
        Path workDir = event.handler?.task?.workDir
        if (workDir != null) {
            activeTasks.entrySet().removeIf { Map.Entry<String, TrackedTask> entry ->
                TaskIdentity tracked = entry.value.identity
                tracked.attempt < identity.attempt &&
                    tracked.process == identity.process &&
                    tracked.fileId == identity.fileId &&
                    tracked.parentFileId == identity.parentFileId
            }
            activeTasks[identity.taskId] = new TrackedTask(
                identity,
                workDir.resolve('.nf-seqlab-progress.json'),
            )
        }
        recordAndRender()
    }

    private void removeActiveTask(TaskIdentity identity) {
        TrackedTask tracked = activeTasks[identity.taskId]
        if (tracked != null && tracked.identity.attempt == identity.attempt) {
            activeTasks.remove(identity.taskId, tracked)
        }
    }

    private TaskIdentity identity(TaskEvent event) {
        TaskRun task = event?.handler?.task
        TraceRecord trace = event?.trace
        if (task == null) {
            return null
        }
        Map<String, String> environment = new LinkedHashMap<>()
        if (task.inputEnv != null) {
            environment.putAll(task.inputEnv)
        }
        try {
            environment.putAll(task.environment)
        }
        catch (Exception ignored) {
        }
        Map metadata = Collections.emptyMap()
        try {
            Object value = task.context?.get('meta')
            if (value instanceof Map) {
                metadata = value as Map
            }
        }
        catch (Exception ignored) {
        }
        String fileId = environment['NF_SEQLAB_PROGRESS_FILE_ID'] ?:
            stringValue(metadata['file_id'] ?: metadata['id'])
        String parentFileId = environment['NF_SEQLAB_PROGRESS_PARENT_FILE_ID'] ?:
            stringValue(metadata['parent_file_id'] ?: metadata['parent_id']) ?:
            fileId
        if (!fileId || !parentFileId) {
            return null
        }
        String process = stringValue(trace?.get('process')) ?: task.name
        process = process?.tokenize(':')?.last()
        String taskId = environment['NF_SEQLAB_PROGRESS_TASK_ID']
        if (!taskId && task.workDir != null) {
            Path parent = task.workDir.parent
            if (parent?.fileName != null && task.workDir.fileName != null) {
                taskId = "${parent.fileName}/${task.workDir.fileName}"
            }
        }
        if (!taskId) {
            taskId = stringValue(trace?.get('task_id'))
        }
        int attempt = integerValue(
            trace?.get('attempt') ?: environment['NF_SEQLAB_PROGRESS_ATTEMPT'],
            task.failCount + 1,
        )
        return taskId && process
            ? new TaskIdentity(taskId, process, fileId, parentFileId, attempt)
            : null
    }

    private void recordAndRender() {
        DashboardView view = runtime.dashboard(maximumActiveFiles)
        writeAudit(view)
        if (capabilities.cursorAddressing && session?.statsObserver != null) {
            renderProjectedProgress(session.statsObserver.quickStats, view)
        }
        else {
            renderPlainIfChanged(view)
        }
    }

    private void renderPlainIfChanged() {
        renderPlainIfChanged(runtime.dashboard(maximumActiveFiles))
    }

    private void renderPlainIfChanged(DashboardView view) {
        if (capabilities.mode == RenderMode.OFF) {
            return
        }
        String output = dashboardRenderer.render(view, capabilities, frame++)
        if (output && output != lastPlainFrame) {
            System.out.println(output)
            lastPlainFrame = output
        }
    }

    private void writeAudit(DashboardView view) {
        if (auditWriter == null) {
            return
        }
        try {
            TerminalCapabilities json = new TerminalCapabilities(RenderMode.JSON, 120, false, false, false)
            String payload = dashboardRenderer.render(view, json, frame)
            auditWriter.append((Map<String, ?>) new JsonSlurper().parseText(payload))
        }
        catch (Exception error) {
            log.warn "Unable to append nf-seqlab progress audit: ${error.message}"
        }
    }

    private static int integerValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.toString())
        }
        catch (NumberFormatException ignored) {
            return fallback
        }
    }

    private static double doubleValue(Object value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.toString())
        }
        catch (NumberFormatException ignored) {
            return fallback
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString()
    }

    private static boolean successful(TraceRecord trace) {
        String status = stringValue(trace?.get('status'))?.toUpperCase(Locale.ROOT)
        return status in ['COMPLETED', 'SUCCEEDED']
    }

    private static String failureMessage(TraceRecord trace) {
        String status = stringValue(trace?.get('status')) ?: 'failed'
        String exit = stringValue(trace?.get('exit'))
        return exit ? "Nextflow task ${status} (exit ${exit})" : "Nextflow task ${status}"
    }
}

class TrackedTask {
    final TaskIdentity identity
    final Path snapshotPath
    ProgressSnapshot snapshot

    TrackedTask(TaskIdentity identity, Path snapshotPath) {
        this.identity = identity
        this.snapshotPath = snapshotPath
    }
}
