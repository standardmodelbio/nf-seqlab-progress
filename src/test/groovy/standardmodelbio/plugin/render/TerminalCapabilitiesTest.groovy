package standardmodelbio.plugin.render

import spock.lang.Specification
import spock.lang.Unroll

class TerminalCapabilitiesTest extends Specification {

    @Unroll
    def 'auto selects #expected for tty=#tty width=#width env=#env'() {
        expect:
        TerminalCapabilities.detect('auto', tty, width, env, true).mode == expected

        where:
        tty   | width | env                                      || expected
        true  | 120   | [TERM: 'xterm-256color']                 || RenderMode.FULL
        true  | 100   | [TERM: 'screen-256color', TMUX: '/tmp']  || RenderMode.FULL
        true  | 80    | [TERM: 'xterm-256color']                 || RenderMode.COMPACT
        true  | 60    | [TERM: 'xterm-256color']                 || RenderMode.COMPACT
        true  | 59    | [TERM: 'xterm-256color']                 || RenderMode.MINIMAL
        false | 120   | [TERM: 'xterm-256color']                 || RenderMode.PLAIN
        true  | 120   | [TERM: 'dumb']                           || RenderMode.PLAIN
        true  | 120   | [TERM: 'xterm-256color', CI: 'true']     || RenderMode.FULL
        false | 120   | [TERM: 'xterm-256color', CI: 'true']     || RenderMode.PLAIN
        true  | 120   | [TERM: 'xterm-256color', NXF_AGENT_MODE: 'true'] || RenderMode.PLAIN
    }

    def 'explicit JSON and off override terminal detection'() {
        expect:
        TerminalCapabilities.detect('json', false, 20, [:], false).mode == RenderMode.JSON
        TerminalCapabilities.detect('off', true, 200, [:], true).mode == RenderMode.OFF
    }

    def 'explicit animated mode degrades to plain without an ANSI console'() {
        expect:
        TerminalCapabilities.detect('full', false, 120, [:], true).mode == RenderMode.PLAIN
        TerminalCapabilities.detect('compact', true, 80, [TERM: 'dumb'], true).mode == RenderMode.PLAIN
    }

    def 'NO_COLOR preserves animation but disables color'() {
        when:
        def capabilities = TerminalCapabilities.detect(
            'auto',
            true,
            120,
            [TERM: 'xterm-256color', NO_COLOR: '1'],
            true,
        )

        then:
        capabilities.mode == RenderMode.FULL
        !capabilities.color
    }
}
