package standardmodelbio.plugin.render

import groovy.transform.CompileStatic

@CompileStatic
class FileDisplay {
    final String fileId
    final String phase
    final String state
    final long completed
    final Long total
    final String unit
    final Double percent

    FileDisplay(
        String fileId,
        String phase,
        String state,
        long completed,
        Long total,
        String unit,
        Double percent
    ) {
        this.fileId = fileId
        this.phase = phase
        this.state = state
        this.completed = completed
        this.total = total
        this.unit = unit
        this.percent = percent
    }
}

