package standardmodelbio.plugin.render

import groovy.transform.CompileStatic

@CompileStatic
final class TerminalCells {

    private static final int ZERO_WIDTH_JOINER = 0x200D
    private static final int VARIATION_SELECTOR_16 = 0xFE0F
    private static final int COMBINING_KEYCAP = 0x20E3

    // Generated from wcwidth 0.8.2's Unicode 17.0.0 WIDE_EASTASIAN table (MIT).
    // Source: https://github.com/jquast/wcwidth
    private static final int[][] WIDE_RANGES = new int[][] {
        new int[] { 0x1100, 0x115F },
        new int[] { 0x231A, 0x231B },
        new int[] { 0x2329, 0x232A },
        new int[] { 0x23E9, 0x23EC },
        new int[] { 0x23F0, 0x23F0 },
        new int[] { 0x23F3, 0x23F3 },
        new int[] { 0x25FD, 0x25FE },
        new int[] { 0x2614, 0x2615 },
        new int[] { 0x2630, 0x2637 },
        new int[] { 0x2648, 0x2653 },
        new int[] { 0x267F, 0x267F },
        new int[] { 0x268A, 0x268F },
        new int[] { 0x2693, 0x2693 },
        new int[] { 0x26A1, 0x26A1 },
        new int[] { 0x26AA, 0x26AB },
        new int[] { 0x26BD, 0x26BE },
        new int[] { 0x26C4, 0x26C5 },
        new int[] { 0x26CE, 0x26CE },
        new int[] { 0x26D4, 0x26D4 },
        new int[] { 0x26EA, 0x26EA },
        new int[] { 0x26F2, 0x26F3 },
        new int[] { 0x26F5, 0x26F5 },
        new int[] { 0x26FA, 0x26FA },
        new int[] { 0x26FD, 0x26FD },
        new int[] { 0x2705, 0x2705 },
        new int[] { 0x270A, 0x270B },
        new int[] { 0x2728, 0x2728 },
        new int[] { 0x274C, 0x274C },
        new int[] { 0x274E, 0x274E },
        new int[] { 0x2753, 0x2755 },
        new int[] { 0x2757, 0x2757 },
        new int[] { 0x2795, 0x2797 },
        new int[] { 0x27B0, 0x27B0 },
        new int[] { 0x27BF, 0x27BF },
        new int[] { 0x2B1B, 0x2B1C },
        new int[] { 0x2B50, 0x2B50 },
        new int[] { 0x2B55, 0x2B55 },
        new int[] { 0x2E80, 0x2E99 },
        new int[] { 0x2E9B, 0x2EF3 },
        new int[] { 0x2F00, 0x2FD5 },
        new int[] { 0x2FF0, 0x3029 },
        new int[] { 0x3030, 0x303E },
        new int[] { 0x3041, 0x3096 },
        new int[] { 0x309B, 0x30FF },
        new int[] { 0x3105, 0x312F },
        new int[] { 0x3131, 0x3163 },
        new int[] { 0x3165, 0x318E },
        new int[] { 0x3190, 0x31E5 },
        new int[] { 0x31EF, 0x321E },
        new int[] { 0x3220, 0x3247 },
        new int[] { 0x3250, 0xA48C },
        new int[] { 0xA490, 0xA4C6 },
        new int[] { 0xA960, 0xA97C },
        new int[] { 0xAC00, 0xD7A3 },
        new int[] { 0xF900, 0xFAFF },
        new int[] { 0xFE10, 0xFE19 },
        new int[] { 0xFE30, 0xFE52 },
        new int[] { 0xFE54, 0xFE66 },
        new int[] { 0xFE68, 0xFE6B },
        new int[] { 0xFF01, 0xFF60 },
        new int[] { 0xFFE0, 0xFFE6 },
        new int[] { 0x16FE0, 0x16FE3 },
        new int[] { 0x16FF2, 0x16FF6 },
        new int[] { 0x17000, 0x18CD5 },
        new int[] { 0x18CFF, 0x18D1E },
        new int[] { 0x18D80, 0x18DF2 },
        new int[] { 0x1AFF0, 0x1AFF3 },
        new int[] { 0x1AFF5, 0x1AFFB },
        new int[] { 0x1AFFD, 0x1AFFE },
        new int[] { 0x1B000, 0x1B122 },
        new int[] { 0x1B132, 0x1B132 },
        new int[] { 0x1B150, 0x1B152 },
        new int[] { 0x1B155, 0x1B155 },
        new int[] { 0x1B164, 0x1B167 },
        new int[] { 0x1B170, 0x1B2FB },
        new int[] { 0x1D300, 0x1D356 },
        new int[] { 0x1D360, 0x1D376 },
        new int[] { 0x1F004, 0x1F004 },
        new int[] { 0x1F0CF, 0x1F0CF },
        new int[] { 0x1F18E, 0x1F18E },
        new int[] { 0x1F191, 0x1F19A },
        new int[] { 0x1F1E6, 0x1F202 },
        new int[] { 0x1F210, 0x1F23B },
        new int[] { 0x1F240, 0x1F248 },
        new int[] { 0x1F250, 0x1F251 },
        new int[] { 0x1F260, 0x1F265 },
        new int[] { 0x1F300, 0x1F320 },
        new int[] { 0x1F32D, 0x1F335 },
        new int[] { 0x1F337, 0x1F37C },
        new int[] { 0x1F37E, 0x1F393 },
        new int[] { 0x1F3A0, 0x1F3CA },
        new int[] { 0x1F3CF, 0x1F3D3 },
        new int[] { 0x1F3E0, 0x1F3F0 },
        new int[] { 0x1F3F4, 0x1F3F4 },
        new int[] { 0x1F3F8, 0x1F43E },
        new int[] { 0x1F440, 0x1F440 },
        new int[] { 0x1F442, 0x1F4FC },
        new int[] { 0x1F4FF, 0x1F53D },
        new int[] { 0x1F54B, 0x1F54E },
        new int[] { 0x1F550, 0x1F567 },
        new int[] { 0x1F57A, 0x1F57A },
        new int[] { 0x1F595, 0x1F596 },
        new int[] { 0x1F5A4, 0x1F5A4 },
        new int[] { 0x1F5FB, 0x1F64F },
        new int[] { 0x1F680, 0x1F6C5 },
        new int[] { 0x1F6CC, 0x1F6CC },
        new int[] { 0x1F6D0, 0x1F6D2 },
        new int[] { 0x1F6D5, 0x1F6D8 },
        new int[] { 0x1F6DC, 0x1F6DF },
        new int[] { 0x1F6EB, 0x1F6EC },
        new int[] { 0x1F6F4, 0x1F6FC },
        new int[] { 0x1F7E0, 0x1F7EB },
        new int[] { 0x1F7F0, 0x1F7F0 },
        new int[] { 0x1F90C, 0x1F93A },
        new int[] { 0x1F93C, 0x1F945 },
        new int[] { 0x1F947, 0x1F9FF },
        new int[] { 0x1FA70, 0x1FA7C },
        new int[] { 0x1FA80, 0x1FA8A },
        new int[] { 0x1FA8E, 0x1FAC6 },
        new int[] { 0x1FAC8, 0x1FAC8 },
        new int[] { 0x1FACD, 0x1FADC },
        new int[] { 0x1FADF, 0x1FAEA },
        new int[] { 0x1FAEF, 0x1FAF8 },
        new int[] { 0x20000, 0x2FFFD },
        new int[] { 0x30000, 0x3FFFD },
    }

    private TerminalCells() {
    }

    static int width(String value) {
        if (!value) {
            return 0
        }
        return clusters(value).sum(0) { CellCluster cluster -> cluster.width } as int
    }

    static String truncate(String value, int maximumCells) {
        if (!value || maximumCells <= 0) {
            return ''
        }
        int cells = 0
        int end = 0
        for (CellCluster cluster : clusters(value)) {
            if (cells + cluster.width > maximumCells) {
                break
            }
            cells += cluster.width
            end = cluster.end
        }
        return value.substring(0, end)
    }

    static String takeRight(String value, int maximumCells) {
        if (!value || maximumCells <= 0) {
            return ''
        }
        List<CellCluster> all = clusters(value)
        int cells = 0
        int start = value.length()
        for (int index = all.size() - 1; index >= 0; index--) {
            CellCluster cluster = all[index]
            if (cells + cluster.width > maximumCells) {
                break
            }
            cells += cluster.width
            start = cluster.start
        }
        return value.substring(start)
    }

    static String middleElide(String value, int maximumCells) {
        String text = value ?: ''
        if (width(text) <= maximumCells) {
            return text
        }
        if (maximumCells <= 3) {
            return truncate(text, maximumCells)
        }
        int left = (maximumCells - 3).intdiv(2)
        int right = maximumCells - 3 - left
        return truncate(text, left) + '...' + takeRight(text, right)
    }

    static String padRight(String value, int targetCells) {
        String text = value ?: ''
        int padding = Math.max(0, targetCells - width(text))
        return text + (' ' * padding)
    }

    static int physicalRows(String block, int columns) {
        if (!block) {
            return 0
        }
        int safeColumns = Math.max(1, columns)
        int rows = 0
        for (String line : block.split('\\n', -1)) {
            int cells = width(line)
            rows += Math.max(1, (cells + safeColumns - 1).intdiv(safeColumns))
        }
        return rows
    }

    private static List<CellCluster> clusters(String value) {
        List<CellCluster> result = []
        int offset = 0
        while (offset < value.length()) {
            int start = offset
            int first = value.codePointAt(offset)
            offset += Character.charCount(first)

            if (regionalIndicator(first) && offset < value.length()) {
                int next = value.codePointAt(offset)
                if (regionalIndicator(next)) {
                    offset += Character.charCount(next)
                }
            }

            offset = consumeExtenders(value, offset)
            while (offset < value.length() && value.codePointAt(offset) == ZERO_WIDTH_JOINER) {
                offset += Character.charCount(ZERO_WIDTH_JOINER)
                if (offset < value.length()) {
                    int joined = value.codePointAt(offset)
                    offset += Character.charCount(joined)
                    offset = consumeExtenders(value, offset)
                }
            }
            result.add(new CellCluster(start, offset, clusterWidth(value, start, offset)))
        }
        return result
    }

    private static int consumeExtenders(String value, int offset) {
        int cursor = offset
        while (cursor < value.length()) {
            int codePoint = value.codePointAt(cursor)
            if (!extender(codePoint)) {
                break
            }
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private static int clusterWidth(String value, int start, int end) {
        boolean joined = false
        boolean emojiPresentation = false
        boolean keycap = false
        int regionalIndicators = 0
        int cells = 0
        for (int offset = start; offset < end;) {
            int codePoint = value.codePointAt(offset)
            joined |= codePoint == ZERO_WIDTH_JOINER
            emojiPresentation |= codePoint == VARIATION_SELECTOR_16
            keycap |= codePoint == COMBINING_KEYCAP
            regionalIndicators += regionalIndicator(codePoint) ? 1 : 0
            cells += codePointWidth(codePoint)
            offset += Character.charCount(codePoint)
        }
        if (joined || emojiPresentation || keycap || regionalIndicators >= 2) {
            return cells == 0 ? 0 : 2
        }
        return cells
    }

    private static int codePointWidth(int codePoint) {
        if (codePoint == 0 || extender(codePoint) || codePoint == ZERO_WIDTH_JOINER) {
            return 0
        }
        int type = Character.getType(codePoint)
        if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE) {
            return 0
        }
        return wide(codePoint) ? 2 : 1
    }

    private static boolean extender(int codePoint) {
        int type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK ||
            type == Character.COMBINING_SPACING_MARK ||
            type == Character.ENCLOSING_MARK ||
            codePoint == 0x200C ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F
    }

    private static boolean regionalIndicator(int codePoint) {
        return codePoint in 0x1F1E6..0x1F1FF
    }

    private static boolean wide(int codePoint) {
        int low = 0
        int high = WIDE_RANGES.length - 1
        while (low <= high) {
            int middle = (low + high) >>> 1
            int[] range = WIDE_RANGES[middle]
            if (codePoint < range[0]) {
                high = middle - 1
            }
            else if (codePoint > range[1]) {
                low = middle + 1
            }
            else {
                return true
            }
        }
        return false
    }

    private static final class CellCluster {
        final int start
        final int end
        final int width

        CellCluster(int start, int end, int width) {
            this.start = start
            this.end = end
            this.width = width
        }
    }
}
