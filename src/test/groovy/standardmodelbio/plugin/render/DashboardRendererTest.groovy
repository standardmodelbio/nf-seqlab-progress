package standardmodelbio.plugin.render

import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

class DashboardRendererTest extends Specification {

    def renderer = new DashboardRenderer()

    def 'full layout includes the small wordmark, stage file count, and active file progress'() {
        when:
        String output = renderer.render(view(), capabilities(RenderMode.FULL, 120, true), 0)

        then:
        output.contains('nf-seqlab')
        output.contains('Build SVAR2')
        output.contains('5/22 files')
        output.contains('22.7%')
        output.contains('chr22_AOU_v8_2_allsamples_phased')
        output.contains('35.5%')
        output.readLines().every { it.size() <= 120 }
    }

    def 'compact layout retains counts and percentages while eliding a long file ID'() {
        when:
        String output = renderer.render(view(), capabilities(RenderMode.COMPACT, 70, true), 1)

        then:
        output.contains('nf-seqlab')
        output.contains('5/22')
        output.contains('22.7%')
        output.contains('35.5%')
        output.contains('...')
        output.readLines().every { it.size() <= 70 }
    }

    def 'minimal layout is ASCII and width safe'() {
        when:
        String output = renderer.render(view(), capabilities(RenderMode.MINIMAL, 40, false), 2)

        then:
        output ==~ /[\x00-\x7F]*/
        output.contains('5/22')
        output.readLines().every { it.size() <= 40 }
    }

    def 'full layout renders a single stage label only once'() {
        given:
        def singleStage = new DashboardView(
            'single-run',
            'Single run',
            [new StageDisplay('exact', 'Exact progress', 'completed', 1, 1, 100d)],
            'exact',
            [],
            0,
        )

        when:
        String output = renderer.render(singleStage, capabilities(RenderMode.FULL, 80, true), 0)

        then:
        output.readLines().count { it.contains('Exact progress') } == 1
    }

    def 'plain mode emits one immutable ANSI-free status line'() {
        when:
        String output = renderer.render(view(), capabilities(RenderMode.PLAIN, 120, false), 0)

        then:
        output.readLines().size() == 1
        output.startsWith('[nf-seqlab progress]')
        output.contains('files=5/22')
        output.contains('file_percent=35.5')
        !output.contains('\u001B')
        !output.contains('\r')
    }

    def 'json mode emits normalized machine-readable state'() {
        when:
        String output = renderer.render(view(), capabilities(RenderMode.JSON, 120, false), 0)
        Map payload = new JsonSlurper().parseText(output) as Map

        then:
        payload.schema == 'nf-seqlab.dashboard/v1'
        payload.run_id == 'aou-v8'
        payload.stage.id == 'build_svar2'
        payload.stage.completed_files == 5
        payload.stage.expected_files == 22
        payload.stages*.id == ['prepare', 'build_svar2', 'build_gvl']
        payload.stages.first().state == 'completed'
        payload.stages.first().completed_files == 22
        payload.stages.first().expected_files == 22
        payload.active_files.first().percent == 35.5d
        !output.contains('\u001B')
        !output.contains('\r')
    }

    @Unroll
    def 'layout never exceeds width #width'() {
        given:
        RenderMode mode = width >= 100 ? RenderMode.FULL : width >= 60 ? RenderMode.COMPACT : RenderMode.MINIMAL

        expect:
        renderer.render(view(), capabilities(mode, width, width >= 60), 3)
            .readLines()
            .every { it.size() <= width }

        where:
        width << [40, 59, 60, 80, 99, 100, 120, 200]
    }

    @Unroll
    def 'wide Unicode layout stays within terminal cells on #platform'() {
        given:
        int width = mode == RenderMode.MINIMAL ? 40 : 60

        when:
        String output = renderer.render(wideView(), capabilities(mode, width, true), 4)

        then:
        output.readLines().each { String line ->
            assert expectedCellWidth(line) <= width:
                "${expectedCellWidth(line)} cells exceed ${width}: ${line}"
        }
        output.readLines().every { String line -> hasValidSurrogates(line) }

        where:
        platform  | mode
        'Linux'   | RenderMode.COMPACT
        'macOS'   | RenderMode.COMPACT
        'Windows' | RenderMode.MINIMAL
    }

    def 'cell truncation never splits an emoji surrogate pair'() {
        given:
        DashboardView surrogateBoundary = new DashboardView(
            'unicode-run',
            ('a' * 48) + '\uD83D\uDE00tail',
            [new StageDisplay('exact', 'Exact progress', 'running', 0, 1, 0d)],
            'exact',
            [],
            0,
        )

        when:
        String firstLine = renderer
            .render(surrogateBoundary, capabilities(RenderMode.COMPACT, 60, true), 0)
            .readLines()
            .first()

        then:
        expectedCellWidth(firstLine) <= 60
        hasValidSurrogates(firstLine)
    }

    @Unroll
    def 'sanitizes terminal controls before measuring and rendering #mode'() {
        given:
        DashboardView injected = controlInjectedView()
        int width = mode == RenderMode.FULL ? 100 : mode == RenderMode.COMPACT ? 70 : 48

        when:
        String output = renderer.render(injected, capabilities(mode, width, true), 0)

        then:
        output.readLines().every { String line -> !containsProtocolControl(line) }
        !output.contains('\u001B')
        mode == RenderMode.JSON || output.contains('分析')
        if (mode in [RenderMode.FULL, RenderMode.COMPACT, RenderMode.MINIMAL]) {
            assert output.readLines().every { String line -> TerminalCells.width(line) <= width }
        }

        where:
        mode << [
            RenderMode.FULL,
            RenderMode.COMPACT,
            RenderMode.MINIMAL,
            RenderMode.PLAIN,
            RenderMode.JSON,
        ]
    }

    def 'JSON protocol values are sanitized rather than merely escaped'() {
        when:
        Map payload = new JsonSlurper().parseText(
            renderer.render(controlInjectedView(), capabilities(RenderMode.JSON, 120, false), 0)
        ) as Map

        then:
        payload.run_id == 'run id [2J分析'
        payload.run_name == 'AoU name next 😀'
        payload.stage.id == 'stage id'
        payload.stage.label == 'Build label [31mred 分析'
        payload.active_files.first().file_id == 'chr22 bad [H分析'
        payload.active_files.first().phase == 'read  phase'
        payload.active_files.first().unit == 'rec ords'
        [
            payload.run_id,
            payload.run_name,
            payload.stage.id,
            payload.stage.label,
            payload.stage.state,
            payload.stages.first().id,
            payload.stages.first().label,
            payload.stages.first().state,
            payload.active_files.first().file_id,
            payload.active_files.first().phase,
            payload.active_files.first().state,
            payload.active_files.first().unit,
        ].every { String value -> !containsProtocolControl(value) }
    }

    def 'message sanitizer removes C0 and C1 controls without corrupting Unicode'() {
        expect:
        ProtocolText.sanitize('读取\tmessage\r\n\u001B[31m\u0085😀') ==
            '读取 message   [31m 😀'
    }

    def 'message sanitizer replaces lone surrogates and preserves valid pairs'() {
        given:
        String sanitized = ProtocolText.sanitize('a\uD83Db\uDE00c\uD83D\uDE00d')

        expect:
        sanitized == 'a\uFFFDb\uFFFDc\uD83D\uDE00d'
        hasValidSurrogates(sanitized)
        TerminalCells.width(sanitized) == 8
        TerminalCells.truncate(sanitized, 6) == 'a\uFFFDb\uFFFDc'
        TerminalCells.physicalRows(sanitized, 4) == 2
    }

    def 'full layout shows publish drain: in-flight count, completed files and bytes'() {
        given:
        def publishing = view().withPublish(new PublishDisplay(3, 12L * 1024 * 1024 * 1024, 1, 'aou-v9.chr21.gvl', 95L))

        when:
        String output = renderer.render(publishing, capabilities(RenderMode.FULL, 120, true), 0)

        then:
        output.contains('Publishing')
        output.contains('1 in flight')
        output.contains('3 done')
        output.contains('12.0 GiB')
        output.contains('aou-v9.chr21.gvl')
        output.contains('1m 35s')
        output.readLines().every { it.size() <= 120 }
    }

    def 'publish line is omitted when nothing has been published or is in flight'() {
        when:
        String output = renderer.render(view(), capabilities(RenderMode.FULL, 120, true), 0)

        then:
        !output.contains('Publishing')
    }

    def 'plain and json modes expose publish counters without a clock that would spam the log'() {
        given:
        def publishing = view().withPublish(new PublishDisplay(2, 2048L, -1, 'x.slhap', 7L))

        when:
        String plain = renderer.render(publishing, capabilities(RenderMode.PLAIN, 120, false), 0)
        Map json = new JsonSlurper().parseText(renderer.render(publishing, capabilities(RenderMode.JSON, 120, false), 0)) as Map

        then:
        plain.contains('publish_done=2')
        plain.contains('publish_bytes=2048')
        plain.contains('publish_in_flight=unknown')
        !plain.contains('7s')
        json.publish.completed_files == 2
        json.publish.completed_bytes == 2048
        json.publish.in_flight == null
        json.publish.last_target == 'x.slhap'
    }

    private static DashboardView view() {
        return new DashboardView(
            'aou-v8',
            'AoU V8',
            [
                new StageDisplay('prepare', 'Prepare variants', 'completed', 22, 22, 100d),
                new StageDisplay('build_svar2', 'Build SVAR2', 'running', 5, 22, 100d * 5d / 22d),
                new StageDisplay('build_gvl', 'Build GVL', 'queued', 0, 22, 0d),
            ],
            'build_svar2',
            [
                new FileDisplay(
                    'chr22_AOU_v8_2_allsamples_phased_biobank_cohort',
                    'read',
                    'running',
                    15_648_800_024L,
                    44_090_635_573L,
                    'compressed_bytes',
                    35.5d,
                ),
            ],
            0,
        )
    }

    private static DashboardView wideView() {
        return new DashboardView(
            'unicode-run',
            '分析\uD83D\uDE00' * 30,
            [new StageDisplay('exact', '解析e\u0301\uD83D\uDE00' * 12, 'running', 0, 1, 0d)],
            'exact',
            [new FileDisplay(
                '样本e\u0301\uD83D\uDE00' * 20,
                '阶段\uD83D\uDE00',
                'running',
                1L,
                10L,
                'records',
                10d,
            )],
            0,
        )
    }

    private static DashboardView controlInjectedView() {
        return new DashboardView(
            'run\tid\u001B[2J分析',
            'AoU\rname\nnext\u0085😀',
            [new StageDisplay(
                'stage\nid',
                'Build\tlabel\u001B[31mred\u007F分析',
                'run\u0000ning',
                1,
                2,
                50d,
            )],
            'stage\nid',
            [new FileDisplay(
                'chr22\nbad\u001B[H分析',
                'read\r\nphase',
                'run\u009Bning',
                1L,
                2L,
                'rec\tords',
                50d,
            )],
            0,
        )
    }

    private static boolean containsProtocolControl(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset)
            if (codePoint <= 0x1F || codePoint in 0x7F..0x9F) {
                return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private static int expectedCellWidth(String value) {
        int width = 0
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset)
            int type = Character.getType(codePoint)
            if (type != Character.NON_SPACING_MARK &&
                type != Character.COMBINING_SPACING_MARK &&
                type != Character.ENCLOSING_MARK) {
                width += isExpectedWide(codePoint) ? 2 : 1
            }
            offset += Character.charCount(codePoint)
        }
        return width
    }

    private static boolean isExpectedWide(int codePoint) {
        return codePoint >= 0x1100 && (
            codePoint <= 0x115F ||
                codePoint in 0x2E80..0xA4CF ||
                codePoint in 0xAC00..0xD7A3 ||
                codePoint in 0xF900..0xFAFF ||
                codePoint in 0xFE10..0xFE6F ||
                codePoint in 0xFF00..0xFF60 ||
                codePoint in 0xFFE0..0xFFE6 ||
                codePoint in 0x1F300..0x1FAFF
        )
    }

    private static boolean hasValidSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index)
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false
                }
                index++
            }
            else if (Character.isLowSurrogate(current)) {
                return false
            }
        }
        return true
    }

    private static TerminalCapabilities capabilities(
        RenderMode mode,
        int width,
        boolean unicode
    ) {
        return new TerminalCapabilities(mode, width, false, unicode, mode in [RenderMode.FULL, RenderMode.COMPACT, RenderMode.MINIMAL])
    }
}
