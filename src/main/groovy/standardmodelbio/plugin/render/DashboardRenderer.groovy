package standardmodelbio.plugin.render

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

@CompileStatic
class DashboardRenderer {

    String render(DashboardView view, TerminalCapabilities capabilities, long frame) {
        DashboardView safeView = ProtocolText.sanitize(view)
        switch (capabilities.mode) {
            case RenderMode.FULL:
                return renderFull(safeView, capabilities, frame)
            case RenderMode.COMPACT:
                return renderCompact(safeView, capabilities, frame)
            case RenderMode.MINIMAL:
                return renderMinimal(safeView, capabilities, frame)
            case RenderMode.PLAIN:
                return renderPlain(safeView)
            case RenderMode.JSON:
                return renderJson(safeView)
            case RenderMode.OFF:
                return ''
            default:
                throw new IllegalStateException("Unhandled render mode ${capabilities.mode}")
        }
    }

    private static String renderFull(
        DashboardView view,
        TerminalCapabilities capabilities,
        long frame
    ) {
        StageDisplay stage = view.currentStage()
        List<String> lines = []
        lines << fit("  nf-seqlab  ${view.runName ?: view.runId}", capabilities.width)
        if (view.stages.size() > 1) {
            lines << fit(stageStrip(view.stages, capabilities.unicode), capabilities.width)
        }
        if (stage != null) {
            String count = "${stage.completedFiles}/${stage.expectedFiles} files  ${oneDecimal(stage.percent)}%"
            int barWidth = Math.max(10, Math.min(
                36,
                capabilities.width - TerminalCells.width(stage.label) - TerminalCells.width(count) - 8,
            ))
            lines << fit("${TerminalCells.padRight(stage.label, 18)} ${segmentedBar(stage.completedFiles, stage.expectedFiles, barWidth, capabilities.unicode)}  ${count}", capabilities.width)
        }
        int fileRows = Math.min(view.activeFiles.size(), 4)
        for (int index = 0; index < fileRows; index++) {
            lines << fileLine(view.activeFiles[index], capabilities.width, capabilities.unicode, frame)
        }
        String publishLine = publishLine(view.publish, true)
        if (publishLine) {
            lines << fit(publishLine, capabilities.width)
        }
        if (view.errorCount > 0) {
            lines << fit("Errors: ${view.errorCount} (see task logs)", capabilities.width)
        }
        return lines.join('\n')
    }

    private static String renderCompact(
        DashboardView view,
        TerminalCapabilities capabilities,
        long frame
    ) {
        StageDisplay stage = view.currentStage()
        List<String> lines = [fit("nf-seqlab  ${view.runName ?: view.runId}", capabilities.width)]
        if (stage != null) {
            String suffix = "${stage.completedFiles}/${stage.expectedFiles}  ${oneDecimal(stage.percent)}%"
            int labelWidth = Math.max(8, capabilities.width - TerminalCells.width(suffix) - 2)
            lines << "${TerminalCells.padRight(middleElide(stage.label, labelWidth), labelWidth)}  ${suffix}".toString()
        }
        if (!view.activeFiles.isEmpty()) {
            lines << fileLine(view.activeFiles.first(), capabilities.width, capabilities.unicode, frame)
        }
        String publishLine = publishLine(view.publish, false)
        if (publishLine) {
            lines << fit(publishLine, capabilities.width)
        }
        return lines.collect { String line -> fit(line, capabilities.width) }.join('\n')
    }

    private static String renderMinimal(
        DashboardView view,
        TerminalCapabilities capabilities,
        long frame
    ) {
        StageDisplay stage = view.currentStage()
        List<String> lines = ['nf-seqlab']
        if (stage != null) {
            lines << fit("${middleElide(stage.label, Math.max(6, capabilities.width - 16))} ${stage.completedFiles}/${stage.expectedFiles} ${oneDecimal(stage.percent)}%", capabilities.width)
        }
        if (!view.activeFiles.isEmpty()) {
            FileDisplay file = view.activeFiles.first()
            String progress = file.percent == null ? spinner(frame, false) : "${oneDecimal(file.percent)}%"
            int idWidth = Math.max(4, capabilities.width - TerminalCells.width(progress) - 1)
            lines << fit("${middleElide(file.fileId, idWidth)} ${progress}", capabilities.width)
        }
        String publishLine = publishLine(view.publish, false)
        if (publishLine) {
            lines << fit(publishLine, capabilities.width)
        }
        return lines.join('\n')
    }

    private static String renderPlain(DashboardView view) {
        StageDisplay stage = view.currentStage()
        FileDisplay file = view.activeFiles.isEmpty() ? null : view.activeFiles.first()
        List<String> fields = [
            '[nf-seqlab progress]',
            "run=${token(view.runId)}".toString(),
            "stage=${token(stage?.id ?: 'none')}".toString(),
            "files=${stage?.completedFiles ?: 0}/${stage?.expectedFiles ?: 0}".toString(),
            "stage_percent=${oneDecimal(stage?.percent ?: 0d)}".toString(),
            "state=${token(stage?.state ?: 'queued')}".toString(),
        ]
        if (file != null) {
            fields << "file=${token(file.fileId)}".toString()
            fields << "file_percent=${file.percent == null ? 'indeterminate' : oneDecimal(file.percent)}".toString()
            fields << "phase=${token(file.phase ?: 'running')}".toString()
        }
        if (view.publish?.visible) {
            fields << "publish_in_flight=${view.publish.inFlight < 0 ? 'unknown' : view.publish.inFlight}".toString()
            fields << "publish_done=${view.publish.completedFiles}".toString()
            fields << "publish_bytes=${view.publish.completedBytes}".toString()
        }
        return fields.join(' ')
    }

    private static String renderJson(DashboardView view) {
        StageDisplay stage = view.currentStage()
        Map<String, Object> payload = [
            schema: 'nf-seqlab.dashboard/v1',
            run_id: view.runId,
            run_name: view.runName,
            stage: stage == null ? null : [
                id: stage.id,
                label: stage.label,
                state: stage.state,
                completed_files: stage.completedFiles,
                expected_files: stage.expectedFiles,
                percent: stage.percent,
            ],
            stages: view.stages.collect { StageDisplay entry ->
                [
                    id: entry.id,
                    label: entry.label,
                    state: entry.state,
                    completed_files: entry.completedFiles,
                    expected_files: entry.expectedFiles,
                    percent: entry.percent,
                ]
            },
            active_files: view.activeFiles.collect { FileDisplay file ->
                [
                    file_id: file.fileId,
                    phase: file.phase,
                    state: file.state,
                    completed: file.completed,
                    total: file.total,
                    unit: file.unit,
                    percent: file.percent,
                ]
            },
            error_count: view.errorCount,
            publish: view.publish == null ? null : [
                completed_files: view.publish.completedFiles,
                completed_bytes: view.publish.completedBytes,
                in_flight: view.publish.inFlight < 0 ? null : view.publish.inFlight,
                last_target: view.publish.lastTarget,
                last_age_seconds: view.publish.lastAgeSeconds,
            ],
        ]
        return JsonOutput.toJson(payload)
    }

    /** One line describing the publish drain; empty when nothing was or is being published. */
    static String publishLine(PublishDisplay publish, boolean detailed) {
        if (publish == null || !publish.visible) {
            return ''
        }
        List<String> parts = []
        if (publish.inFlight > 0) {
            parts << "${publish.inFlight} in flight".toString()
        }
        else if (publish.inFlight < 0) {
            parts << 'in flight: unknown'
        }
        parts << "${publish.completedFiles} done (${humanBytes(publish.completedBytes)})".toString()
        if (detailed && publish.lastTarget) {
            String name = publish.lastTarget.tokenize('/').last()
            String age = publish.lastAgeSeconds == null ? '' : " ${humanDuration(publish.lastAgeSeconds)} ago"
            parts << "last: ${middleElide(name, 40)}${age}".toString()
        }
        return "${TerminalCells.padRight('Publishing', 18)} ${parts.join(' · ')}".toString()
    }

    static String humanBytes(long bytes) {
        double value = bytes
        for (String unit : ['B', 'KiB', 'MiB', 'GiB', 'TiB']) {
            if (value < 1024d || unit == 'TiB') {
                return unit == 'B' ? "${bytes} B".toString() : "${oneDecimal(value)} ${unit}".toString()
            }
            value /= 1024d
        }
        return "${bytes} B".toString()
    }

    static String humanDuration(long seconds) {
        if (seconds < 60) {
            return "${seconds}s".toString()
        }
        if (seconds < 3600) {
            return "${(seconds / 60) as long}m ${seconds % 60}s".toString()
        }
        return "${(seconds / 3600) as long}h ${((seconds % 3600) / 60) as long}m".toString()
    }

    private static String stageStrip(List<StageDisplay> stages, boolean unicode) {
        return stages.collect { StageDisplay stage ->
            String marker
            switch (stage.state) {
                case 'completed': marker = unicode ? '●' : '#'; break
                case 'running': marker = unicode ? '▶' : '>'; break
                case 'failed': marker = unicode ? '×' : 'x'; break
                default: marker = unicode ? '○' : 'o'
            }
            return "${marker} ${stage.label}"
        }.join('  ')
    }

    private static String segmentedBar(int completed, int expected, int width, boolean unicode) {
        if (expected <= 0) {
            return continuousBar(null, width, unicode, 0)
        }
        int filled = Math.max(0, Math.min(width, Math.round(width * completed / (float) expected) as int))
        String on = unicode ? '■' : '#'
        String off = unicode ? '·' : '-'
        return on * filled + off * (width - filled)
    }

    private static String continuousBar(Double percent, int width, boolean unicode, long frame) {
        if (percent == null) {
            char[] chars = (unicode ? '·' : '-').multiply(width).toCharArray()
            chars[(int) (frame % width)] = (unicode ? '◆' : '>').charAt(0)
            return new String(chars)
        }
        int filled = Math.max(0, Math.min(width, Math.round(width * percent / 100f) as int))
        String on = unicode ? '█' : '#'
        String off = unicode ? '░' : '-'
        return on * filled + off * (width - filled)
    }

    private static String fileLine(FileDisplay file, int width, boolean unicode, long frame) {
        String percent = file.percent == null ? ' --.-%' : String.format(Locale.ROOT, '%5.1f%%', file.percent)
        int barWidth = Math.max(6, Math.min(24, width.intdiv(4)))
        String bar = continuousBar(file.percent, barWidth, unicode, frame)
        String phase = file.phase ?: file.state
        int reserved = TerminalCells.width(bar) +
            TerminalCells.width(percent) +
            TerminalCells.width(phase) +
            6
        int idWidth = Math.max(4, width - reserved)
        return fit("${TerminalCells.padRight(middleElide(file.fileId, idWidth), idWidth)}  ${bar}  ${percent}  ${phase}", width)
    }

    private static String middleElide(String value, int width) {
        return TerminalCells.middleElide(value, width)
    }

    private static String fit(String value, int width) {
        return TerminalCells.truncate(value, width)
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, '%.1f', value)
    }

    private static String token(String value) {
        return (value ?: '').replaceAll(/\s+/, '_')
    }

    private static String spinner(long frame, boolean unicode) {
        String frames = unicode ? '⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏' : '|/-\\'
        return frames[(int) (frame % frames.size())].toString()
    }
}
