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

    private static TerminalCapabilities capabilities(
        RenderMode mode,
        int width,
        boolean unicode
    ) {
        return new TerminalCapabilities(mode, width, false, unicode, mode in [RenderMode.FULL, RenderMode.COMPACT, RenderMode.MINIMAL])
    }
}
