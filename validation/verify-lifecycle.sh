#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

nextflow_bin="${NEXTFLOW_BIN:-./nextflow}"
nextflow_version="${NXF_VER:-26.04.6}"
root="validation/.verification"
run_token="${VERIFY_RUN_TOKEN:-$$}"

run_case() {
    local case_name="$1"
    local scenario="$2"
    local mode="$3"
    shift 3
    local case_dir="$root/$case_name"
    mkdir -p "$case_dir"
    NXF_VER="$nextflow_version" "$nextflow_bin" run validation \
        -name "progress-$case_name-$run_token" \
        --scenario "$scenario" \
        --outdir "$case_dir/results" \
        --progress_mode "$mode" \
        -ansi-log false \
        -work-dir "$case_dir/work" \
        "$@" 2>&1 | tee "$case_dir/console.log"
    return "${PIPESTATUS[0]}"
}

audit_path() {
    printf '%s/%s/results/pipeline_info/progress.jsonl' "$root" "$1"
}

rm -rf "$root"
mkdir -p "$root"

run_case success success plain
success_audit="$(audit_path success)"
grep -Fq 'files=1/1' "$root/success/console.log"
grep -Fq 'stage_percent=100.0' "$root/success/console.log"
jq -e -s 'any(.[]; any(.active_files[]?; .phase == "phase-a" and .percent == 80))' "$success_audit" >/dev/null
jq -e -s 'any(.[]; any(.active_files[]?; .phase == "phase-b" and .percent == 0))' "$success_audit" >/dev/null
jq -e -s 'last | .stage.completed_files == 1 and .stage.state == "completed"' "$success_audit" >/dev/null

set +e
run_case failure failure plain
failure_rc=$?
set -e
test "$failure_rc" -ne 0
failure_audit="$(audit_path failure)"
jq -e -s 'any(.[]; .stage.state == "failed" and .stage.completed_files == 0 and .error_count == 1)' "$failure_audit" >/dev/null
jq -e -s 'last | .stage.completed_files == 0 and .stage.state == "failed"' "$failure_audit" >/dev/null

run_case retry retry plain
retry_audit="$(audit_path retry)"
grep -Fq 'Execution is retried (1)' "$root/retry/console.log"
jq -e -s 'any(.[]; any(.active_files[]?; .phase == "phase-b"))' "$retry_audit" >/dev/null
jq -e -s 'last | .stage.completed_files == 1 and .stage.state == "completed" and .error_count == 0' "$retry_audit" >/dev/null

run_case cache cache plain -with-trace "$root/cache/first.trace"
NXF_VER="$nextflow_version" "$nextflow_bin" run validation \
    -resume "progress-cache-$run_token" \
    --scenario cache \
    --outdir "$root/cache/results" \
    --progress_mode plain \
    -ansi-log false \
    -work-dir "$root/cache/work" \
    -with-trace "$root/cache/resume.trace" \
    2>&1 | tee "$root/cache/resume-console.log"
awk -F '\t' '
    NR > 1 {
        for (column = 1; column <= NF; column++) {
            if ($column == "CACHED") found = 1
        }
    }
    END { exit found ? 0 : 1 }
' "$root/cache/resume.trace"
jq -e -s 'last | .stage.completed_files == 1 and .stage.state == "completed"' "$(audit_path cache)" >/dev/null

run_case json success json
grep -F '"schema":"nf-seqlab.dashboard/v1"' "$root/json/console.log" > "$root/json/dashboard.jsonl"
test -s "$root/json/dashboard.jsonl"
while IFS= read -r line; do
    jq -e '.schema == "nf-seqlab.dashboard/v1"' <<<"$line" >/dev/null
done < "$root/json/dashboard.jsonl"

run_case off success off
if grep -Fq '[nf-seqlab progress]' "$root/off/console.log"; then
    echo 'off mode emitted plain dashboard output' >&2
    exit 1
fi
if grep -Fq '"schema":"nf-seqlab.dashboard/v1"' "$root/off/console.log"; then
    echo 'off mode emitted JSON dashboard output' >&2
    exit 1
fi
jq -e -s 'last | .stage.completed_files == 1' "$(audit_path off)" >/dev/null
