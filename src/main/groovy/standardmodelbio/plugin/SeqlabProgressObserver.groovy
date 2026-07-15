package standardmodelbio.plugin

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.trace.AnsiLogObserver
import nextflow.trace.TraceRecord
import nextflow.trace.WorkflowStats
import nextflow.trace.event.TaskEvent
import standardmodelbio.plugin.io.ProgressAuditWriter
import standardmodelbio.plugin.io.ProgressSnapshotReader
import standardmodelbio.plugin.model.ProgressSnapshot
import standardmodelbio.plugin.model.TaskIdentity
import standardmodelbio.plugin.render.DashboardRenderer
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
    private final DashboardRenderer dashboardRenderer = new DashboardRenderer()
    private final Map<String, TrackedTask> activeTasks = new ConcurrentHashMap<>()
    private final Set<String> warnedSnapshots = ConcurrentHashMap.newKeySet()

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

    SeqlabProgressObserver(ProgressRuntime runtime) {
        this(runtime, false)
    }

    SeqlabProgressObserver(ProgressRuntime runtime, boolean ansiClaimed) {
        this.runtime = runtime
        this.ansiClaimed = ansiClaimed
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
        terminalEnvironment = new LinkedHashMap<>(System.getenv())
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
            capabilities = new TerminalCapabilities(RenderMode.PLAIN, width, false, unicode, false)
        }

        Path auditRoot = resolveAuditRoot(params['outdir'], session.outputDir)
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

    private static Path resolveAuditRoot(Object configuredOutdir, Path fallback) {
        if (configuredOutdir instanceof Path) {
            return configuredOutdir as Path
        }
        String value = configuredOutdir?.toString()?.trim()
        return value ? Path.of(value) : fallback
    }

    @Override
    void onTaskPending(TaskEvent event) {
        transition(event, 'pending')
    }

    @Override
    synchronized void onTaskSubmit(TaskEvent event) {
        if (capabilities.cursorAddressing) {
            super.onTaskSubmit(event)
        }
        transition(event, 'submitted')
    }

    @Override
    void onTaskStart(TaskEvent event) {
        transition(event, 'running')
    }

    @Override
    void onTaskComplete(TaskEvent event) {
        TaskIdentity identity = identity(event)
        if (identity == null) {
            return
        }
        runtime.state.taskCompleted(identity, false)
        activeTasks.remove(identity.taskId)
        recordAndRender()
    }

    @Override
    void onTaskCached(TaskEvent event) {
        TaskIdentity identity = identity(event)
        if (identity == null) {
            return
        }
        runtime.state.taskCompleted(identity, true)
        activeTasks.remove(identity.taskId)
        recordAndRender()
    }

    @Override
    void onFlowError(TaskEvent event) {
        TaskIdentity identity = identity(event)
        if (identity != null && activeTasks.containsKey(identity.taskId)) {
            runtime.state.taskFailed(identity, 'Nextflow task failed')
            activeTasks.remove(identity.taskId)
        }
        recordAndRender()
    }

    @Override
    void onFlowComplete() {
        pollSnapshots()
        recordAndRender()
        poller?.shutdownNow()
        if (capabilities.cursorAddressing) {
            super.onFlowComplete()
        }
        auditWriter?.close()
        if (session != null) {
            DashboardClaims.remove(session)
            ProgressRuntimes.remove(session)
        }
    }

    void pollSnapshots() {
        boolean changed = false
        activeTasks.values().each { TrackedTask tracked ->
            try {
                ProgressSnapshot snapshot = snapshotReader.read(tracked.snapshotPath)
                if (snapshot == null || snapshot.updatedAt == tracked.updatedAt) {
                    return
                }
                runtime.state.applySnapshot(snapshot)
                tracked.updatedAt = snapshot.updatedAt
                changed = true
            }
            catch (Exception error) {
                String warningKey = "${tracked.identity.taskId}:${tracked.identity.attempt}"
                if (warnedSnapshots.add(warningKey)) {
                    snapshotWarnings++
                    log.warn "Ignoring invalid nf-seqlab progress snapshot for ${warningKey}: ${error.message}"
                }
            }
        }
        if (changed) {
            recordAndRender()
        }
    }

    @Override
    synchronized protected void renderProgress(WorkflowStats stats) {
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
        String dashboard = dashboardRenderer.render(runtime.dashboard(maximumActiveFiles), capabilities, frame++)
        if (dashboard) {
            int rows = printAndCountLines(dashboard)
            AnsiLineAccounting.addPrintedLines(this, rows)
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
            activeTasks[identity.taskId] = new TrackedTask(
                identity,
                workDir.resolve('.nf-seqlab-progress.json'),
            )
        }
        recordAndRender()
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
        String fileId = environment['NF_SEQLAB_PROGRESS_FILE_ID']
        String parentFileId = environment['NF_SEQLAB_PROGRESS_PARENT_FILE_ID'] ?: fileId
        if (!fileId || !parentFileId) {
            return null
        }
        String process = stringValue(trace?.get('process')) ?: task.name
        process = process?.tokenize(':')?.last()
        String taskId = environment['NF_SEQLAB_PROGRESS_TASK_ID'] ?: stringValue(trace?.get('task_id'))
        if (!taskId && task.workDir != null) {
            taskId = "${task.workDir.parent?.fileName}/${task.workDir.fileName}"
        }
        int attempt = integerValue(
            environment['NF_SEQLAB_PROGRESS_ATTEMPT'] ?: trace?.get('attempt'),
            task.failCount + 1,
        )
        return taskId && process
            ? new TaskIdentity(taskId, process, fileId, parentFileId, attempt)
            : null
    }

    private void recordAndRender() {
        writeAudit()
        if (capabilities.cursorAddressing && session?.statsObserver != null) {
            renderProgress(session.statsObserver.quickStats)
        }
        else {
            renderPlainIfChanged()
        }
    }

    private void renderPlainIfChanged() {
        if (capabilities.mode == RenderMode.OFF) {
            return
        }
        String output = dashboardRenderer.render(runtime.dashboard(maximumActiveFiles), capabilities, frame++)
        if (output && output != lastPlainFrame) {
            System.out.println(output)
            lastPlainFrame = output
        }
    }

    private void writeAudit() {
        if (auditWriter == null) {
            return
        }
        try {
            TerminalCapabilities json = new TerminalCapabilities(RenderMode.JSON, 120, false, false, false)
            String payload = dashboardRenderer.render(runtime.dashboard(maximumActiveFiles), json, frame)
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
}

class TrackedTask {
    final TaskIdentity identity
    final Path snapshotPath
    java.time.Instant updatedAt

    TrackedTask(TaskIdentity identity, Path snapshotPath) {
        this.identity = identity
        this.snapshotPath = snapshotPath
    }
}
