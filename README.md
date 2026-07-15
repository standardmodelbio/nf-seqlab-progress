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
            [id: 'build_svar2', label: 'Build SVAR2', file_ids: ['chr1', 'chr22']],
            [id: 'build_gvl', label: 'Build GVL', file_ids: ['chr22']],
        ],
        [
            [process: 'SEQLAB_BUILD_SVAR2', stage: 'build_svar2', completion_boundary: true],
            [process: 'SEQLAB_NORMALIZE', stage: 'build_gvl', completion_boundary: 'parent'],
            [process: 'SEQLAB_BUILD_GVL', stage: 'build_gvl', completion_boundary: true],
        ],
    )
}
```

Normal nf-seqlab modules participate automatically when their `TaskRun`
context contains a `meta` map. File identity resolves from
`meta.file_id ?: meta.id`, and parent identity resolves from
`meta.parent_file_id ?: meta.parent_id ?: fileId`. Optional managed environment
inputs remain authoritative when a process provides them directly.

Native snapshot producers export managed values inside their scripts. These
shell-local exports are consumed by the producer, while the observer derives
the same task ID from the Nextflow work directory:

```nextflow
script:
"""
export NF_SEQLAB_PROGRESS_FILE_ID="${meta.file_id}"
export NF_SEQLAB_PROGRESS_PARENT_FILE_ID="${meta.parent_file_id ?: meta.file_id}"
export NF_SEQLAB_PROGRESS_TASK_ID="\$(basename "\$(dirname "\$PWD")")/\$(basename "\$PWD")"
export NF_SEQLAB_PROGRESS_ATTEMPT="${task.attempt}"
"""
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
  "task_id": "ed/89cec8...",
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

Within one nonblank `phase`, counters may not regress and the denominator and
unit may not change. The first snapshot for a new phase may reset all three, allowing transitions
such as `80/100 records` in phase A to `0/4 chunks` in phase B. Once a task has
advanced, snapshots from an earlier observed phase are stale and ignored.
Snapshot states may be terminal, but only the corresponding Nextflow lifecycle
completion or cache event marks a source file complete for stage accounting.

Stage percentages are based on completed source files. When a stage declares
`file_ids`, only that subset contributes to its expected and completed counts;
omitting `file_ids` retains the full registered input set. A source file counts
only when its configured completion-boundary task succeeds or is restored from
cache. `completion_boundary: 'parent'` counts unsharded work where
`file_id == parent_file_id`, while `true` always counts and `false` never does.
Partial byte, record, region, and chunk progress is shown only on the active
file row and never inflates the completed-file count. Concurrent snapshots with
different phases or units render as indeterminate rather than being summed.

## Display Modes

`params.progress_mode` accepts:

- `auto` (default): full, compact, minimal, or plain based on the environment;
- `full`, `compact`, or `minimal`: request an animated layout, with a safe
  downgrade to plain output when the console is noninteractive;
- `plain`: immutable, ANSI-free status lines;
- `json`: machine-readable dashboard snapshots;
- `off`: disable console progress output.

`params.progress_refresh_seconds` controls native snapshot polling and
`params.progress_max_active_files` limits active file rows. Automatic mode
follows Nextflow's live terminal-width measurement, including resized tmux and
screen panes. `NO_COLOR`, `TERM=dumb`, redirected output, non-TTY CI, and agent
mode are handled without changing pipeline behavior. CI sessions with a real
TTY retain the appropriate animated layout.

Each dashboard snapshot emitted by the observer is appended as JSON Lines under
`<outdir>/pipeline_info/progress.jsonl`. This is a history of dashboard views,
not a complete task-event log: unchanged frames may be omitted and several
lifecycle changes may be represented by one projection. The file is independent
of terminal animation and is suitable for post-run status inspection.

Local paths and `file://` outputs are appended directly. Cloud output URIs such
as `s3://` and `gs://` are resolved through Nextflow's filesystem providers.
Because object stores do not support safe append, the plugin appends to a local
synchronized spool. A bounded single background worker coalesces updates and
publishes at most once per one-second throttle window. Workflow completion
waits for a final flush of the newest generation; the spool is removed only
after that publication succeeds.

Run names, stage labels, file IDs, phases, units, and other protocol text are
sanitized before terminal-cell measurement and rendering. Tabs, CR/LF, ESC,
and other C0/C1 controls become ordinary spaces without altering Unicode text.

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
artifact on the supported 25.10 and 26.04 patch releases, exercises failure,
retry, cache, and plain/JSON/off paths on all supported versions, validates
tmux and screen on the floor and latest versions, and runs unit tests on Linux,
macOS, and Windows.

## License

Apache License 2.0. See [`COPYING`](COPYING).
