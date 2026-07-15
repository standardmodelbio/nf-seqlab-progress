nextflow.enable.dsl = 2

include {
    registerProgressInputs
    registerProgressStages
} from 'plugin/nf-seqlab-progress'

process EXACT_PROGRESS {
    tag 'chr22'

    errorStrategy { params.scenario == 'retry' ? 'retry' : 'terminate' }
    maxRetries 1

    input:
    val meta

    output:
    path 'done.txt'

    script:
    """
    export NF_SEQLAB_PROGRESS_FILE_ID="${meta.file_id}"
    export NF_SEQLAB_PROGRESS_PARENT_FILE_ID="${meta.parent_file_id}"
    export NF_SEQLAB_PROGRESS_TASK_ID="\$(basename "\$(dirname "\$PWD")")/\$(basename "\$PWD")"
    export NF_SEQLAB_PROGRESS_ATTEMPT="${task.attempt}"
    updated_at="\$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    write_progress() {
        phase=\$1
        completed=\$2
        total=\$3
        unit=\$4
        percent=\$5
        state=\$6
        cat > .nf-seqlab-progress.json.tmp <<JSON
{"schema":"nf-seqlab.progress/v1","run_id":"${workflow.runName}","stage_id":"exact","process":"EXACT_PROGRESS","file_id":"\${NF_SEQLAB_PROGRESS_FILE_ID}","parent_file_id":"\${NF_SEQLAB_PROGRESS_PARENT_FILE_ID}","task_id":"\${NF_SEQLAB_PROGRESS_TASK_ID}","attempt":\${NF_SEQLAB_PROGRESS_ATTEMPT},"state":"\${state}","phase":"\${phase}","completed":\${completed},"total":\${total},"unit":"\${unit}","percent":\${percent},"message":"Synthetic validation","updated_at":"\${updated_at}"}
JSON
        mv .nf-seqlab-progress.json.tmp .nf-seqlab-progress.json
    }

    write_progress phase-a 80 100 records 80 running
    sleep 0.3
    write_progress phase-b 0 4 chunks 0 running
    sleep 0.3
    write_progress phase-b 4 4 chunks 100 completed
    sleep 0.3

    if [[ "${params.scenario}" == 'failure' ]]; then
        exit 42
    fi
    if [[ "${params.scenario}" == 'retry' && "${task.attempt}" -eq 1 ]]; then
        exit 42
    fi
    touch done.txt
    """
}

process CACHE_ONLY {
    tag 'chr22'

    input:
    val meta

    output:
    path 'cached.txt'

    script:
    """
    touch cached.txt
    """
}

workflow {
    registerProgressInputs([
        [file_id: 'chr22', path: 'synthetic://chr22'],
    ])
    registerProgressStages(
        [
            [id: 'exact', label: 'Exact progress', file_ids: ['chr22']],
        ],
        [
            [process: 'EXACT_PROGRESS', stage: 'exact', completion_boundary: 'parent'],
            [process: 'CACHE_ONLY', stage: 'exact', completion_boundary: 'parent'],
        ],
    )

    def input = channel.value([file_id: 'chr22', parent_file_id: 'chr22'])
    if (params.scenario == 'cache') {
        CACHE_ONLY(input)
    }
    else {
        EXACT_PROGRESS(input)
    }
}
