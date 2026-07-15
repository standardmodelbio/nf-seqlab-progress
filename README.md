# nf-seqlab-progress

## Summary

`nf-seqlab-progress` adds an automatic, hierarchical progress dashboard to
nf-seqlab. It combines Nextflow task lifecycle events with structured progress
snapshots from native tools to show:

- the current pipeline stage;
- completed source files and total source files;
- active files, phases, and within-file percentages;
- cached, retried, failed, and indeterminate work.

The compact `nf-seqlab` wordmark and dashboard appear automatically in an
interactive terminal. Redirected output, CI, and agent environments receive
immutable plain-text status lines instead of cursor control sequences.

## Get Started

Pin the plugin in `nextflow.config`:

```groovy
plugins {
    id 'nf-seqlab-progress@0.1.0'
}
```

Import its registration functions in the pipeline entry point:

```nextflow
include {
    registerProgressInputs
    registerProgressStages
} from 'plugin/nf-seqlab-progress'
```

No wrapper command or separate progress process is required. A normal
`nextflow run` uses the animated dashboard when the terminal supports it.

## Examples

Register the complete source-file set before launching tasks, then map process
names to user-facing stages:

```nextflow
workflow {
    registerProgressInputs([
        [file_id: 'chr1', path: '/data/chr1.vcf.gz'],
        [file_id: 'chr22', path: '/data/chr22.vcf.gz'],
    ])

    registerProgressStages(
        [
            [id: 'build_svar2', label: 'Build SVAR2'],
            [id: 'build_gvl', label: 'Build GVL'],
        ],
        [
            [process: 'SEQLAB_BUILD_SVAR2', stage: 'build_svar2', completion_boundary: true],
            [process: 'SEQLAB_BUILD_GVL', stage: 'build_gvl', completion_boundary: true],
        ],
    )
}
```

Tasks provide stable identity through environment inputs:

```nextflow
input:
env 'NF_SEQLAB_PROGRESS_FILE_ID'
env 'NF_SEQLAB_PROGRESS_PARENT_FILE_ID'
env 'NF_SEQLAB_PROGRESS_TASK_ID'
env 'NF_SEQLAB_PROGRESS_ATTEMPT'
```

Native tools atomically replace `.nf-seqlab-progress.json` in the task work
directory. A valid snapshot uses the versioned protocol:

```json
{
  "schema": "nf-seqlab.progress/v1",
  "run_id": "focused-curie",
  "stage_id": "build_svar2",
  "process": "SEQLAB_BUILD_SVAR2",
  "file_id": "chr22",
  "parent_file_id": "chr22",
  "task_id": "task-22",
  "attempt": 1,
  "state": "running",
  "phase": "read",
  "completed": 4409063557,
  "total": 44090635573,
  "unit": "compressed_bytes",
  "percent": 10.0,
  "message": "Reading variants",
  "updated_at": "2026-07-15T03:34:00Z"
}
```

Stage percentages are based on completed source files. A source file counts
only when its configured completion-boundary task succeeds or is restored from
cache. Partial byte, record, region, and chunk progress is shown only on the
active file row and never inflates the completed-file count.

## Display Modes

`params.progress_mode` accepts:

- `auto` (default): full, compact, minimal, or plain based on the environment;
- `full`, `compact`, or `minimal`: force an animated layout;
- `plain`: immutable, ANSI-free status lines;
- `json`: machine-readable dashboard snapshots;
- `off`: disable console progress output.

`params.progress_refresh_seconds` controls native snapshot polling and
`params.progress_max_active_files` limits active file rows. Automatic mode
follows Nextflow's live terminal-width measurement, including resized tmux and
screen panes. `NO_COLOR`, `TERM=dumb`, redirected output, CI, and agent mode are
handled without changing pipeline behavior.

Every state transition is also appended as JSON Lines under
`<outdir>/pipeline_info/progress.jsonl`. This audit stream is independent of
terminal animation and is suitable for post-run inspection.

## Development

Build and test with Java 21:

```bash
./gradlew test assemble
```

Install the development build and run the real Nextflow fixture:

```bash
./gradlew installPlugin
nextflow run validation -ansi-log false -work-dir validation/work
```

The plugin is compiled against Nextflow 25.10.4. CI executes the same plugin
artifact on the supported 25.10 and 26.04 patch releases and runs unit tests on
Linux, macOS, and Windows.

## License

Apache License 2.0. See [`COPYING`](COPYING).
