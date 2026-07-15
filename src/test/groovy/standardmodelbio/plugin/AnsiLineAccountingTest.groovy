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
}
