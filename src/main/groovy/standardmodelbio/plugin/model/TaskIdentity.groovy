package standardmodelbio.plugin.model

import groovy.transform.CompileStatic

@CompileStatic
class TaskIdentity {
    final String taskId
    final String process
    final String fileId
    final String parentFileId
    final int attempt

    TaskIdentity(String taskId, String process, String fileId, String parentFileId, int attempt) {
        if (!taskId || !process || !fileId || !parentFileId) {
            throw new IllegalArgumentException('Task identity fields must be non-empty')
        }
        if (attempt < 1) {
            throw new IllegalArgumentException('Task attempt must be at least 1')
        }
        this.taskId = taskId
        this.process = process
        this.fileId = fileId
        this.parentFileId = parentFileId
        this.attempt = attempt
    }
}
