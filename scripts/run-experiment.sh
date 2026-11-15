#!/usr/bin/env bash
# Runs one committed experiment manifest (spec Phase 13 item 7). No manifest exists yet; the
# experiment-engineer writes them in Phase 15 in the format below.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/run-experiment.sh --manifest experiments/<path>.json [--profile <name>]
                                 [--region us-east-2] [--results-root experiments/results] [--dry-run]

Manifest (JSON, committed under experiments/ before the run):
  {
    "schemaVersion": "experiment-manifest-v1",
    "experimentId": "<a-z 0-9 ->",                 used in the results path
    "description": "<text>",
    "command": ["<tracked executable under experiments/ or load-tests/>", "<arg>", ...],
    "limits": {"maxMinutes": <1..240>, ...},       maxMinutes is enforced; every limit is recorded
    "metrics": [{"namespace": "AWS/ECS", "metricName": "CPUUtilization",
                 "dimensions": {"ClusterName": "...", "ServiceName": "..."}}, ...]
  }

Gates: the manifest is tracked by Git and unchanged from HEAD; the tree is clean (outside
operations/ and experiments/results/); the command's first element is a tracked executable under
experiments/ or load-tests/ (no other command can run); account and Region are printed first.
The command runs from the repository root with SCHOLARCHAIN_EXPERIMENT_ID,
SCHOLARCHAIN_EXPERIMENT_DIR (its raw results directory), AWS_PROFILE and AWS_REGION, and is stopped
(TERM, then KILL after 30 s) at limits.maxMinutes.

Writes <results-root>/<experimentId>/<UTC stamp>/: raw/ (the command's own outputs), stdout.txt,
stderr.txt (.txt, not .log, because .gitignore ignores *.log), and run-record.json (commit,
manifest path and sha256, limits, start and end times, exit code, account, Region, metric
locations). The directory is made read-only afterwards (raw results are immutable).

Environment: AWS_PROFILE (or --profile).
Run by: experiment-engineer (/experiment) with an approved, committed manifest.
Rollback: none; an experiment changes no deployed resource beyond what its manifest's command does
within the approved bounds.
Exit: 0 command exited 0; 1 command failed; 3 precondition (uncommitted manifest, dirty tree);
6 maxMinutes reached.
EOF
}

MANIFEST=""
RESULTS_ROOT="$SC_ROOT/experiments/results"
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest) MANIFEST="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --results-root) RESULTS_ROOT="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
need jq
[ -f "$MANIFEST" ] || die "--manifest not found: ${MANIFEST:-<none>}" 2
REL="$(cd "$(dirname "$MANIFEST")" && pwd)/$(basename "$MANIFEST")"
REL="${REL#"$SC_ROOT"/}"
case "$REL" in experiments/results/*) die "a manifest cannot live under experiments/results/" 2 ;; experiments/*.json) ;; *) die "the manifest must be a .json file under experiments/" 2 ;; esac

jq -e '.schemaVersion == "experiment-manifest-v1"
  and (.experimentId | type == "string" and test("^[a-z0-9][a-z0-9-]{2,63}$"))
  and (.command | type == "array" and length >= 1 and all(type == "string"))
  and (.limits.maxMinutes | type == "number" and . >= 1 and . <= 240)
  and ((.metrics // []) | type == "array")' "$SC_ROOT/$REL" >/dev/null ||
  die "manifest fails the experiment-manifest-v1 checks (see --help)" 2
EXP_ID="$(jq -r .experimentId "$SC_ROOT/$REL")"
MAX_MIN="$(jq -r '.limits.maxMinutes | floor' "$SC_ROOT/$REL")"
CMD0="$(jq -r '.command[0]' "$SC_ROOT/$REL")"
case "$CMD0" in experiments/* | load-tests/*) ;; *) die "command[0] must be under experiments/ or load-tests/" 2 ;; esac
case "$CMD0" in *..*) die "command[0] must not contain .." 2 ;; esac
[ -x "$SC_ROOT/$CMD0" ] || die "command[0] is not an executable file: $CMD0" 2

dry_run_exit \
  "manifest $REL tracked and unchanged from HEAD; clean tree; $CMD0 tracked" \
  "aws sts get-caller-identity; print account and Region" \
  "run $(jq -r '.command | join(" ")' "$SC_ROOT/$REL") for at most $MAX_MIN min" \
  "write $RESULTS_ROOT/$EXP_ID/<stamp>/run-record.json; make the directory read-only"

need git aws
git -C "$SC_ROOT" ls-files --error-unmatch -- "$REL" >/dev/null 2>&1 || die "manifest $REL is not committed" 3
git -C "$SC_ROOT" diff --quiet HEAD -- "$REL" || die "manifest $REL differs from HEAD (commit it first)" 3
git -C "$SC_ROOT" ls-files --error-unmatch -- "$CMD0" >/dev/null 2>&1 || die "$CMD0 is not committed" 3
require_clean_tree
COMMIT="$(git_head)"
require_profile
aws_target

DIR="$RESULTS_ROOT/$EXP_ID/$(utc_stamp)"
mkdir -p "$DIR/raw"
CMD=()
while IFS= read -r a; do CMD+=("$a"); done < <(jq -r '.command[]' "$SC_ROOT/$REL")

step "run $EXP_ID"
START="$(utc_now)"
(cd "$SC_ROOT" && SCHOLARCHAIN_EXPERIMENT_ID="$EXP_ID" SCHOLARCHAIN_EXPERIMENT_DIR="$DIR/raw" \
  exec "${CMD[@]}" >"$DIR/stdout.txt" 2>"$DIR/stderr.txt") &
PID=$!
DEADLINE=$((SECONDS + MAX_MIN * 60))
TIMED_OUT=0
while kill -0 "$PID" 2>/dev/null; do
  if [ "$SECONDS" -ge "$DEADLINE" ]; then
    TIMED_OUT=1
    warn "maxMinutes $MAX_MIN reached: stopping the command"
    kill -TERM "$PID" 2>/dev/null || true
    sleep 30
    kill -KILL "$PID" 2>/dev/null || true
    break
  fi
  sleep 5
done
RC=0
wait "$PID" || RC=$?
END="$(utc_now)"
tail -20 "$DIR/stdout.txt" || true

jq -n --arg commit "$COMMIT" --arg manifest "$REL" --arg msha "$(sha256_hex "$SC_ROOT/$REL")" \
  --arg start "$START" --arg end "$END" --argjson rc "$RC" --argjson timedOut "$TIMED_OUT" \
  --arg account "$ACCOUNT" --arg region "$REGION" --slurpfile m "$SC_ROOT/$REL" \
  '{schemaVersion: "experiment-run-record-v1", experimentId: $m[0].experimentId, commit: $commit,
    manifest: $manifest, manifestSha256: $msha, limits: $m[0].limits, command: $m[0].command,
    startedAt: $start, endedAt: $end, exitCode: $rc, timedOut: ($timedOut == 1),
    account: $account, region: $region, rawResults: "raw/", stdout: "stdout.txt", stderr: "stderr.txt",
    metricLocations: [($m[0].metrics // [])[] | . + {region: $region, source: "CloudWatch"}]}' >"$DIR/run-record.json"
chmod -R a-w "$DIR"
printf '==> EXPERIMENT id=%s record=%s exit=%s\n' "$EXP_ID" "$DIR/run-record.json" "$RC"
[ "$TIMED_OUT" = 0 ] || die "experiment stopped at maxMinutes $MAX_MIN" 6
[ "$RC" = 0 ] || die "experiment command exited $RC" 1
ok "experiment complete"
