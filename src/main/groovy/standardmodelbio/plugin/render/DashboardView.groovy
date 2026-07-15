package standardmodelbio.plugin.render

import groovy.transform.CompileStatic

@CompileStatic
class DashboardView {
    final String runId
    final String runName
    final List<StageDisplay> stages
    final String currentStageId
    final List<FileDisplay> activeFiles
    final int errorCount

    DashboardView(
        String runId,
        String runName,
        Collection<StageDisplay> stages,
        String currentStageId,
        Collection<FileDisplay> activeFiles,
        int errorCount
    ) {
        this.runId = runId
        this.runName = runName
        this.stages = Collections.unmodifiableList(new ArrayList<>(stages))
        this.currentStageId = currentStageId
        this.activeFiles = Collections.unmodifiableList(new ArrayList<>(activeFiles))
        this.errorCount = errorCount
    }

    StageDisplay currentStage() {
        return stages.find { StageDisplay stage -> stage.id == currentStageId }
    }
}

