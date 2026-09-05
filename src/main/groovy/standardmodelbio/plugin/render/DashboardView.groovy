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
    final PublishDisplay publish

    DashboardView(
        String runId,
        String runName,
        Collection<StageDisplay> stages,
        String currentStageId,
        Collection<FileDisplay> activeFiles,
        int errorCount
    ) {
        this(runId, runName, stages, currentStageId, activeFiles, errorCount, null)
    }

    DashboardView(
        String runId,
        String runName,
        Collection<StageDisplay> stages,
        String currentStageId,
        Collection<FileDisplay> activeFiles,
        int errorCount,
        PublishDisplay publish
    ) {
        this.runId = runId
        this.runName = runName
        this.stages = Collections.unmodifiableList(new ArrayList<>(stages))
        this.currentStageId = currentStageId
        this.activeFiles = Collections.unmodifiableList(new ArrayList<>(activeFiles))
        this.errorCount = errorCount
        this.publish = publish
    }

    DashboardView withPublish(PublishDisplay publish) {
        return new DashboardView(runId, runName, stages, currentStageId, activeFiles, errorCount, publish)
    }

    StageDisplay currentStage() {
        return stages.find { StageDisplay stage -> stage.id == currentStageId }
    }
}

