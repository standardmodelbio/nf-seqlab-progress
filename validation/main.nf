nextflow.enable.dsl = 2

include {
    registerProgressInputs
    registerProgressStages
} from 'plugin/nf-seqlab-progress'

process EXACT_PROGRESS {
    tag 'chr22'

    input:
    env 'NF_SEQLAB_PROGRESS_FILE_ID'
    env 'NF_SEQLAB_PROGRESS_PARENT_FILE_ID'
    env 'NF_SEQLAB_PROGRESS_TASK_ID'
    env 'NF_SEQLAB_PROGRESS_ATTEMPT'

    output:
    path 'done.txt'

    script:
    """
    write_progress() {
        completed=\$1
        percent=\$2
        cat > .nf-seqlab-progress.json.tmp <<JSON
{"schema":"nf-seqlab.progress/v1","run_id":"${workflow.runName}","stage_id":"exact","process":"EXACT_PROGRESS","file_id":"chr22","parent_file_id":"chr22","task_id":"validation-task","attempt":1,"state":"running","phase":"read","completed":\${completed},"total":100,"unit":"records","percent":\${percent},"message":"Synthetic validation","updated_at":"\$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
JSON
        mv .nf-seqlab-progress.json.tmp .nf-seqlab-progress.json
    }

    write_progress 25 25
    sleep 0.3
    write_progress 75 75
    sleep 0.3
    touch done.txt
    """
}

workflow {
    registerProgressInputs([
        [file_id: 'chr22', path: 'synthetic://chr22'],
    ])
    registerProgressStages(
        [
            [id: 'exact', label: 'Exact progress'],
        ],
        [
            [process: 'EXACT_PROGRESS', stage: 'exact', completion_boundary: true],
        ],
    )

    EXACT_PROGRESS(
        channel.value('chr22'),
        channel.value('chr22'),
        channel.value('validation-task'),
        channel.value('1'),
    )
}
