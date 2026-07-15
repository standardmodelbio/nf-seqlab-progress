package standardmodelbio.plugin.render

import groovy.transform.CompileStatic
import groovy.transform.Immutable

@CompileStatic
@Immutable
class TerminalCapabilities {
    RenderMode mode
    int width
    boolean color
    boolean unicode
    boolean cursorAddressing

    static TerminalCapabilities detect(
        String requestedMode,
        boolean tty,
        int requestedWidth,
        Map<String, String> environment,
        boolean unicode
    ) {
        int width = Math.max(20, requestedWidth)
        String requested = (requestedMode ?: 'auto').toLowerCase(Locale.ROOT)
        RenderMode mode
        if (requested == 'auto') {
            boolean plain = !tty ||
                environment['TERM'] == 'dumb' ||
                truthy(environment['CI']) ||
                truthy(environment['NXF_AGENT_MODE'])
            mode = plain
                ? RenderMode.PLAIN
                : width >= 100
                    ? RenderMode.FULL
                    : width >= 60
                        ? RenderMode.COMPACT
                        : RenderMode.MINIMAL
        }
        else {
            try {
                mode = RenderMode.valueOf(requested.toUpperCase(Locale.ROOT))
            }
            catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException(
                    "Unknown progress mode '${requestedMode}'; expected auto, full, compact, minimal, plain, json, or off"
                )
            }
        }
        boolean nonInteractive = !tty ||
            environment['TERM'] == 'dumb' ||
            truthy(environment['CI']) ||
            truthy(environment['NXF_AGENT_MODE'])
        if (mode in [RenderMode.FULL, RenderMode.COMPACT, RenderMode.MINIMAL] && nonInteractive) {
            mode = RenderMode.PLAIN
        }
        boolean interactive = mode in [RenderMode.FULL, RenderMode.COMPACT, RenderMode.MINIMAL]
        boolean color = interactive && !environment.containsKey('NO_COLOR') && environment['TERM'] != 'dumb'
        return new TerminalCapabilities(mode, width, color, unicode, interactive && tty)
    }

    private static boolean truthy(String value) {
        return value != null && value.toLowerCase(Locale.ROOT) in ['1', 'true', 'yes', 'on']
    }
}
