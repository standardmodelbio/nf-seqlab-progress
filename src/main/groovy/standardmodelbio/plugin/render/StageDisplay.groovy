package standardmodelbio.plugin.render

import groovy.transform.CompileStatic

@CompileStatic
class StageDisplay {
    final String id
    final String label
    final String state
    final int completedFiles
    final int expectedFiles
    final double percent

    StageDisplay(
        String id,
        String label,
        String state,
        int completedFiles,
        int expectedFiles,
        double percent
    ) {
        this.id = id
        this.label = label
        this.state = state
        this.completedFiles = completedFiles
        this.expectedFiles = expectedFiles
        this.percent = percent
    }
}

