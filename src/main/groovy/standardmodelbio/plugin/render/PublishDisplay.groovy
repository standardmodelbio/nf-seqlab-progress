package standardmodelbio.plugin.render

import groovy.transform.CompileStatic

/**
 * Publish-drain summary for the dashboard.
 *
 * Nextflow publishes outputs asynchronously after each task completes and only
 * ends the run once that queue drains, so a slow copy (e.g. 60 GB through a
 * FUSE mount) is otherwise invisible: every task is done, yet the run "hangs".
 * {@code inFlight} is the publish executor's active + queued job count
 * ({@code -1} when it cannot be read); the completed counters come from
 * {@code onFilePublish} events.
 */
@CompileStatic
class PublishDisplay {
    final int completedFiles
    final long completedBytes
    final int inFlight
    final String lastTarget
    final Long lastAgeSeconds

    PublishDisplay(int completedFiles, long completedBytes, int inFlight, String lastTarget, Long lastAgeSeconds) {
        this.completedFiles = completedFiles
        this.completedBytes = completedBytes
        this.inFlight = inFlight
        this.lastTarget = lastTarget
        this.lastAgeSeconds = lastAgeSeconds
    }

    boolean isVisible() {
        return completedFiles > 0 || inFlight > 0
    }
}
