package standardmodelbio.plugin

import nextflow.trace.AnsiLogObserver
import spock.lang.Specification

class AnsiLineAccountingTest extends Specification {

    def 'adds dashboard rows to Nextflow cursor accounting'() {
        given:
        def observer = new AnsiLogObserver()
        AnsiLineAccounting.setPrintedLines(observer, 5)

        when:
        AnsiLineAccounting.addPrintedLines(observer, 3)

        then:
        AnsiLineAccounting.getPrintedLines(observer) == 8
    }

    def 'reports compatibility with the supported Nextflow floor'() {
        expect:
        AnsiLineAccounting.compatible()
    }

    def 'reads the terminal columns measured by Nextflow'() {
        given:
        def observer = new AnsiLogObserver()

        expect:
        AnsiLineAccounting.getTerminalColumns(observer, 120) == 80
    }

    def 'counts the unterminated final row in an ANSI block'() {
        expect:
        AnsiLineAccounting.visibleLines('') == 0
        AnsiLineAccounting.visibleLines('one') == 1
        AnsiLineAccounting.visibleLines('one\ntwo\nthree') == 3
    }

    def 'erases every owned dashboard row from bottom to top'() {
        given:
        def bytes = new ByteArrayOutputStream()
        def output = new PrintStream(bytes)

        when:
        AnsiLineAccounting.eraseOwnedLines(output, 3)

        then:
        String control = bytes.toString('UTF-8')
        control.count('\u001B[2K') == 3
        control.count('\u001B[1A') == 2
        control.endsWith('\r')
    }

    def 'counts physical terminal rows using Unicode cell widths'() {
        expect:
        AnsiLineAccounting.physicalLines('界界界', 4) == 2
        AnsiLineAccounting.physicalLines('e\u0301e\u0301', 2) == 1
        AnsiLineAccounting.physicalLines('\uD83D\uDE00\uD83D\uDE00', 3) == 2
        AnsiLineAccounting.physicalLines('\uD83D\uDC69\u200D\uD83D\uDCBB', 2) == 1
        AnsiLineAccounting.physicalLines('界\nabc', 3) == 2
    }
}
