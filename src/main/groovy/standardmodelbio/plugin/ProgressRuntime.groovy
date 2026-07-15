package standardmodelbio.plugin

import groovy.transform.CompileStatic
import standardmodelbio.plugin.model.ProgressState
import standardmodelbio.plugin.render.DashboardView
import standardmodelbio.plugin.render.FileDisplay
import standardmodelbio.plugin.render.StageDisplay

@CompileStatic
class ProgressRuntime {

    final String runId
    final String runName
    final ProgressState state

    private final Map<String, String> sourcePaths = new LinkedHashMap<>()

    ProgressRuntime(String runId, String runName) {
        this.runId = runId
        this.runName = runName
        this.state = new ProgressState(runId)
    }

    synchronized void registerInputs(Collection<Map<String, ?>> inputs) {
        List<String> fileIds = []
        inputs.each { Map<String, ?> input ->
            String fileId = stringValue(input['file_id'] ?: input['id'])
            String path = stringValue(input['path'] ?: input['variants'] ?: '')
            if (!fileId) {
                throw new IllegalArgumentException('Each progress input requires file_id or id')
            }
            String existing = sourcePaths[fileId]
            if (existing != null && existing != path) {
                throw new IllegalArgumentException(
                    "Source file '${fileId}' was registered with both '${existing}' and '${path}'"
                )
            }
            sourcePaths[fileId] = path
            fileIds << fileId
        }
        state.registerSourceFiles(fileIds)
    }

    synchronized void registerStages(
        Collection<Map<String, ?>> stageDefinitions,
        Collection<Map<String, ?>> processDefinitions
    ) {
        stageDefinitions.each { Map<String, ?> definition ->
            state.registerStage(
                required(definition, 'id'),
                required(definition, 'label'),
            )
        }
        processDefinitions.each { Map<String, ?> definition ->
            state.mapProcess(
                required(definition, 'process'),
                required(definition, 'stage'),
                booleanValue(definition['completion_boundary']),
            )
        }
    }

    DashboardView dashboard(int maximumActiveFiles) {
        List<StageDisplay> stageDisplays = state.stageIds().collect { String stageId ->
            def stage = state.stage(stageId)
            return new StageDisplay(
                stage.stageId,
                stage.label,
                state.stageStatus(stageId),
                stage.completedFiles,
                stage.expectedFiles,
                stage.percent,
            )
        }
        StageDisplay current = stageDisplays.find { StageDisplay stage -> stage.state in ['running', 'failed'] }
        if (current == null) {
            current = stageDisplays.find { StageDisplay stage -> stage.state == 'queued' }
        }
        if (current == null && !stageDisplays.isEmpty()) {
            current = stageDisplays.last()
        }
        List<FileDisplay> files = current == null
            ? []
            : state.activeFiles(current.id, maximumActiveFiles).collect { active ->
                new FileDisplay(
                    active.fileId,
                    active.phase,
                    'running',
                    active.completed,
                    active.total,
                    active.unit,
                    active.percent,
                )
            }
        return new DashboardView(
            runId,
            runName,
            stageDisplays,
            current?.id,
            files,
            state.errorCount(),
        )
    }

    private static String required(Map<String, ?> source, String key) {
        String value = stringValue(source[key])
        if (!value) {
            throw new IllegalArgumentException("Progress definition requires '${key}'")
        }
        return value
    }

    private static String stringValue(Object value) {
        return value == null ? '' : value.toString()
    }

    private static boolean booleanValue(Object value) {
        return value != null && value.toString().toBoolean()
    }
}
