package standardmodelbio.plugin

import groovy.transform.CompileStatic
import standardmodelbio.plugin.model.ProgressProjection
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
                optionalFileIds(definition['file_ids']),
            )
        }
        processDefinitions.each { Map<String, ?> definition ->
            state.mapProcess(
                required(definition, 'process'),
                required(definition, 'stage'),
                definition['completion_boundary'],
            )
        }
    }

    DashboardView dashboard(int maximumActiveFiles) {
        ProgressProjection projection = state.project(maximumActiveFiles)
        List<StageDisplay> stageDisplays = projection.stages.collect { stage ->
            return new StageDisplay(
                stage.stageId,
                stage.label,
                stage.state,
                stage.completedFiles,
                stage.expectedFiles,
                stage.percent,
            )
        }
        List<FileDisplay> files = projection.activeFiles.collect { active ->
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
            projection.currentStageId,
            files,
            projection.errorCount,
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

    private static Collection<String> optionalFileIds(Object value) {
        if (value == null) {
            return null
        }
        if (!(value instanceof Collection)) {
            throw new IllegalArgumentException("Progress stage 'file_ids' must be a collection")
        }
        return (value as Collection).collect { Object fileId -> stringValue(fileId) }
    }

}
