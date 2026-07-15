package standardmodelbio.plugin.render

import spock.lang.Specification
import spock.lang.Unroll

class TerminalCellsTest extends Specification {

    @Unroll
    def 'uses Unicode terminal width for #label'() {
        expect:
        TerminalCells.width(value) == expected

        where:
        label                  | value                    || expected
        'ASCII'                | 'abc'                    || 3
        'combining character'  | 'e\u0301'                || 1
        'CJK ideograph'        | '\u5206'                  || 2
        'BMP emoji'            | '\u231A'                  || 2
        'supplementary emoji'  | '\uD83D\uDE00'            || 2
        'regional flag pair'   | '\uD83C\uDDFA\uD83C\uDDF8' || 2
        'joined emoji sequence' | '\uD83D\uDC69\u200D\uD83D\uDCBB' || 2
    }

    def 'truncation does not split a wide BMP symbol'() {
        expect:
        TerminalCells.truncate('a\u231Ab', 2) == 'a'
        TerminalCells.truncate('a\u231Ab', 3) == 'a\u231A'
    }
}
