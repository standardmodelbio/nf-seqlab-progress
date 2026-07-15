package standardmodelbio.plugin

import groovy.transform.CompileStatic
import nextflow.trace.AnsiLogObserver

import java.lang.reflect.Field

@CompileStatic
class AnsiLineAccounting {

    private static final Field PRINTED_LINES = resolvePrintedLines()
    private static final Field TERMINAL_COLUMNS = resolveField('cols')

    static boolean compatible() {
        return PRINTED_LINES != null
    }

    static int getPrintedLines(AnsiLogObserver observer) {
        requireCompatible()
        return PRINTED_LINES.getInt(observer)
    }

    static void setPrintedLines(AnsiLogObserver observer, int value) {
        requireCompatible()
        PRINTED_LINES.setInt(observer, value)
    }

    static void addPrintedLines(AnsiLogObserver observer, int additionalLines) {
        setPrintedLines(observer, getPrintedLines(observer) + additionalLines)
    }

    static int getTerminalColumns(AnsiLogObserver observer, int fallback) {
        if (TERMINAL_COLUMNS == null) {
            return fallback
        }
        int columns = TERMINAL_COLUMNS.getInt(observer)
        return columns > 0 ? columns : fallback
    }

    private static Field resolvePrintedLines() {
        return resolveField('printedLines')
    }

    private static Field resolveField(String name) {
        try {
            Field field = AnsiLogObserver.getDeclaredField(name)
            field.accessible = true
            return field
        }
        catch (ReflectiveOperationException ignored) {
            return null
        }
    }

    private static void requireCompatible() {
        if (!compatible()) {
            throw new IllegalStateException(
                'This Nextflow version does not expose compatible ANSI line accounting'
            )
        }
    }
}
