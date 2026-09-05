package standardmodelbio.plugin.render

import groovy.transform.CompileStatic

@CompileStatic
final class ProtocolText {

    private ProtocolText() {
    }

    static String sanitize(String value) {
        if (value == null) {
            return null
        }
        StringBuilder result = new StringBuilder(value.length())
        for (int offset = 0; offset < value.length();) {
            char current = value.charAt(offset)
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 < value.length() &&
                    Character.isLowSurrogate(value.charAt(offset + 1))) {
                    result.append(current)
                    result.append(value.charAt(offset + 1))
                    offset += 2
                    continue
                }
                result.append('\uFFFD')
                offset++
                continue
            }
            if (Character.isLowSurrogate(current)) {
                result.append('\uFFFD')
                offset++
                continue
            }
            int codePoint = current as int
            result.appendCodePoint(control(codePoint) ? 0x20 : codePoint)
            offset++
        }
        return result.toString()
    }

    static DashboardView sanitize(DashboardView view) {
        List<StageDisplay> stages = view.stages.collect { StageDisplay stage ->
            new StageDisplay(
                sanitize(stage.id),
                sanitize(stage.label),
                sanitize(stage.state),
                stage.completedFiles,
                stage.expectedFiles,
                stage.percent,
            )
        }
        List<FileDisplay> files = view.activeFiles.collect { FileDisplay file ->
            new FileDisplay(
                sanitize(file.fileId),
                sanitize(file.phase),
                sanitize(file.state),
                file.completed,
                file.total,
                sanitize(file.unit),
                file.percent,
            )
        }
        PublishDisplay publish = view.publish == null ? null : new PublishDisplay(
            view.publish.completedFiles,
            view.publish.completedBytes,
            view.publish.inFlight,
            sanitize(view.publish.lastTarget),
            view.publish.lastAgeSeconds,
        )
        return new DashboardView(
            sanitize(view.runId),
            sanitize(view.runName),
            stages,
            sanitize(view.currentStageId),
            files,
            view.errorCount,
            publish,
        )
    }

    private static boolean control(int codePoint) {
        return codePoint <= 0x1F || codePoint in 0x7F..0x9F
    }
}
