#!/usr/bin/env bash
# Cloud window store loads (D-13), in the order the storage contracts need: S3 objects first
# (the Neptune bulk loader and the runtime read them), then Neptune, DynamoDB, S3 Vectors.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/load-stores.sh --store-run <run-..> --vectors-run <run-..>
           [--root scratch/stores/s3] [--vectors-root <root>] [--window-summary-run <run-..>]
           [--window-summary-root scratch/runtime/wo8/s3] [--dynamo-concurrency 8]
           [--neptune-timeout-minutes 60] [--profile <name>] [--region us-east-2]
           [--record-dir <dir>] [--skip <step,...>] [--dry-run]

Order (each step stops the script on failure; every loader is write-once and idempotent, so a
rerun skips what is already loaded):
  1. s3       LoadS3Cli --live --profile, once per run manifest, from the local roots:
              runs/store-conversion/snapshot=2026-10-03/run=<store-run>/manifest.json (graph/neptune,
              curated/dynamodb, curated/coauthor-evidence, semantic-context/candidates),
              runs/embed/snapshot=2026-10-03/run=<vectors-run>/manifest.json (vectors/backup,
              semantic-context/text), and
              runs/load-window-summary/snapshot=2026-10-03/run=<window-summary-run>/manifest.json
              (curated/window-summary, from scratch/runtime/wo8/s3)
  2. neptune  the in-VPC path: one-off ECS task scholarchain-course-neptune-loader (no --profile; task
              role) running LoadNeptuneCli --live --stage-from-bucket --dir
              /tmp/stores/s3/graph/neptune/snapshot=2026-10-03/run=<store-run> --out /tmp/stores, then
              one --status task every 2 minutes until LOAD_COMPLETED (bounded). Task lines are read with
              filter-log-events; after an exit-0 task a missing result line gets up to 3 re-reads 15 s
              apart, then exit 1; a filter-log-events error is exit 1; after a non-zero exit the error
              lines are saved too. A line that could carry a credential is dropped and counted.
  3. dynamodb LoadDynamoCli --live --profile --dir <root>/curated/dynamodb/snapshot=.../run=<store-run>
              (about 876k conditional puts) --concurrency <n>
  4. vectors  LoadVectorsCli --live --profile --input <vectors-root>/vectors/backup/snapshot=.../run=<vectors-run>
Step names for --skip (comma-separated; an unknown name is a usage error, exit 2): s3-store-conversion,
s3-embed, s3-window-summary, neptune, dynamodb, vectors. Skip only steps already completed in this
environment (for example the S3 steps when the objects survived a runtime cleanup); run.json records
each skipped step with status "skipped" and lists them in skippedSteps.
The laptop loaders get exactly the tasks' SCHOLARCHAIN_* variables (terraform output
runtime_environment), so SCHOLARCHAIN_ENVIRONMENT is non-local and the names are the deployed ones.

Defaults: --root scratch/stores/s3, --vectors-root = --root, --window-summary-run
run-6fbad498beb0d8f570df67665fb97544, --record-dir operations/pipeline-runs (writes
store-load-<stamp>/ with every loader log as <step>.txt, the Neptune task result lines (read with
filter-log-events) as neptune-<start|status>-<task>.txt, after a failed task its error lines as
neptune-<start|status>-<task>-errors.txt, and run.json; .txt, not .log, so that Git does not ignore them).
--dry-run validates flags and local manifests, prints the steps, no AWS/Terraform/git/sbt call.

Environment: AWS_PROFILE (or --profile), used by the laptop loaders through LiveAws.
Run by: the human (the storage live loaders are human-run, storage rulings 1 and 26), after the runtime
apply and the API key secret, before deploy.sh --resume (Phase 14 ruling 39). sbt runs with
SPARK_LOCAL_IP=127.0.0.1 and -J-Xmx6g, one process at a time.
Step 2 uses LoadNeptuneCli --live without --profile and --stage-from-bucket (Phase 12 work order 5).
Rollback: none automatic; loads are write-once. Removing loaded data is a human-gated destructive
action (docs/agents.md escalation 2); the runtime cleanup stage destroys the stores.
Exit: 0 all loads done; 1 a loader failed; 3 precondition; 6 timeout.
EOF
}

STORE_RUN=""
VECTORS_RUN=""
ROOT="$SC_ROOT/scratch/stores/s3"
VROOT=""
WS_RUN="run-6fbad498beb0d8f570df67665fb97544"
WS_ROOT="$SC_ROOT/scratch/runtime/wo8/s3"
CONCURRENCY=8
NEPTUNE_MIN=60
RECORD_DIR="$SC_ROOT/operations/pipeline-runs"
STEP_NAMES="s3-store-conversion s3-embed s3-window-summary neptune dynamodb vectors"
SKIP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --store-run) STORE_RUN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --vectors-run) VECTORS_RUN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --root) ROOT="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --vectors-root) VROOT="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --window-summary-run) WS_RUN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --window-summary-root) WS_ROOT="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dynamo-concurrency) CONCURRENCY="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --neptune-timeout-minutes) NEPTUNE_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --record-dir) RECORD_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --skip) SKIP="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
VROOT="${VROOT:-$ROOT}"
case "$SKIP" in *[!a-z0-9,-]*) die "--skip takes comma-separated step names (steps: $STEP_NAMES)" 2 ;; esac
for s in $(printf '%s' "$SKIP" | tr ',' ' '); do
  case " $STEP_NAMES " in *" $s "*) ;; *) die "unknown --skip step '$s' (steps: $STEP_NAMES)" 2 ;; esac
done
skipped() { case ",$SKIP," in *",$1,"*) return 0 ;; esac; return 1; }
for r in "$STORE_RUN" "$VECTORS_RUN" "$WS_RUN"; do is_run_id "$r" || die "run IDs must be run-<32 hex> (--store-run and --vectors-run are required)" 2; done
printf '%s' "$CONCURRENCY" | grep -Eq '^([1-9]|[12][0-9]|3[0-2])$' || die "--dynamo-concurrency must be 1 to 32" 2
printf '%s' "$NEPTUNE_MIN" | grep -Eq '^[0-9]+$' && [ "$NEPTUNE_MIN" -ge 5 ] && [ "$NEPTUNE_MIN" -le 180 ] || die "--neptune-timeout-minutes must be 5 to 180" 2

D="$SC_SNAPSHOT"
M_STORE="runs/store-conversion/snapshot=$D/run=$STORE_RUN/manifest.json"
M_EMBED="runs/embed/snapshot=$D/run=$VECTORS_RUN/manifest.json"
M_WS="runs/load-window-summary/snapshot=$D/run=$WS_RUN/manifest.json"
DYNAMO_DIR="$ROOT/curated/dynamodb/snapshot=$D/run=$STORE_RUN"
VECTORS_DIR="$VROOT/vectors/backup/snapshot=$D/run=$VECTORS_RUN"
for f in "$ROOT/$M_STORE" "$VROOT/$M_EMBED" "$WS_ROOT/$M_WS"; do [ -f "$f" ] || die "run manifest missing: $f" 3; done
[ -d "$DYNAMO_DIR" ] && [ -d "$VECTORS_DIR" ] || die "missing $DYNAMO_DIR or $VECTORS_DIR" 3
need sbt jq git aws terraform

dry_run_exit \
  "skip: ${SKIP:-none}" \
  "refuse a dirty tree (the loaders run from this checkout); account and Region; terraform outputs" \
  "s3: LoadS3Cli --live for $M_STORE ($ROOT), $M_EMBED ($VROOT), $M_WS ($WS_ROOT)" \
  "neptune: ECS run-task scholarchain-course-neptune-loader (LoadNeptuneCli --live --stage-from-bucket, run $STORE_RUN), then --status tasks until LOAD_COMPLETED (${NEPTUNE_MIN} min)" \
  "dynamodb: LoadDynamoCli --live --dir $DYNAMO_DIR --concurrency $CONCURRENCY" \
  "vectors: LoadVectorsCli --live --input $VECTORS_DIR" \
  "write $RECORD_DIR/store-load-<stamp>/run.json"

require_clean_tree
require_profile
aws_target
tf_outputs
[ "$(tfo '.runtime_enabled.value')" = true ] || die "runtime_enabled is false: the stores do not exist" 3

step "environment"
COUNT=0
while IFS= read -r kv; do
  printf '%s' "$kv" | grep -Eq '^SCHOLARCHAIN_[A-Z0-9_]+=.+' || die "unexpected runtime_environment entry" 3
  export "${kv?}"
  COUNT=$((COUNT + 1))
done < <(jq -r '.runtime_environment.value | to_entries[] | "\(.key)=\(.value)"' "$TF_OUT")
[ "$COUNT" -ge 10 ] || die "runtime_environment output has $COUNT entries" 3
ok "$COUNT SCHOLARCHAIN_* variables from terraform output (environment $SCHOLARCHAIN_ENVIRONMENT)"

REC="$RECORD_DIR/store-load-$(utc_stamp)"
mkdir -p "$REC"
STEPS='[]'
note() { STEPS="$(jq -c --arg n "$1" --arg s "$2" --arg t "$3" --arg l "$4" '. + [{step: $n, status: $s, seconds: ($t | tonumber), log: $l}]' <<<"$STEPS")"; }

loader() { # name sbt-command-line
  local name="$1" t0=$SECONDS
  step "$name"
  if skipped "$name"; then
    note "$name" skipped 0 ""
    ok "$name skipped (--skip)"
    return 0
  fi
  if sbt_run "$2" 2>&1 | tee "$REC/$name.txt"; then
    note "$name" ok "$((SECONDS - t0))" "$name.txt"
    ok "$name"
  else
    note "$name" failed "$((SECONDS - t0))" "$name.txt"
    die "$name failed; log $REC/$name.txt" 1
  fi
}

LIVE="--live --profile $PROFILE --region $REGION"
loader s3-store-conversion "storage/runMain scholarchain.storage.cli.LoadS3Cli --manifest $M_STORE --root $ROOT $LIVE"
loader s3-embed "storage/runMain scholarchain.storage.cli.LoadS3Cli --manifest $M_EMBED --root $VROOT $LIVE"
loader s3-window-summary "storage/runMain scholarchain.storage.cli.LoadS3Cli --manifest $M_WS --root $WS_ROOT $LIVE"

# ---- Neptune, inside the VPC --------------------------------------------------------------
CLUSTER="$(tfo '.ecs_cluster_name.value')"
FAMILY="$(tfo '.ecs_task_definition_families.value["neptune-loader"]')"
SUBNETS="$(jq -r '.public_subnet_ids.value | join(",")' "$TF_OUT")"
SG="$(tfo '.security_group_ids.value.loader')"
LOG_GROUP="$(tfo '.log_group_names.value["neptune-loader"]')"

# A recorded line matching this is dropped and counted, never written: the container's DEBUG SDK lines
# carry the task's session token (Phase 14 ruling 41); the full stream stays in CloudWatch.
SECRET_RE='x-amz-security-token|aws_session_token|SessionToken|Authorization:|Credential='
# Second read after a non-zero exit: the LoadNeptuneCli and Cli.exitCode failure lines.
ERROR_PATTERN='?"error:" ?"errors parsing" ?"errorCode" ?"failed" ?"refused" ?"pre-load" ?"violations" ?"nothing loaded" ?"Exception"'

# task_lines <filter pattern> <record file>: writes the lines of $stream (in $LOG_GROUP) that match the
# CloudWatch filter pattern, one event per line. filter-log-events, not the first page of
# get-log-events: the CLI follows every nextToken page (Phase 14 ruling 41). An API error stops the
# script (exit 1); it is never read as "no lines".
task_lines() {
  local raw n
  raw="$(aws_retry logs filter-log-events --log-group-name "$LOG_GROUP" --log-stream-names "$stream" \
    --filter-pattern "$1" --query 'events[].message' --output json)" ||
    die "filter-log-events failed (pattern $1); CloudWatch $LOG_GROUP $stream" 1
  raw="$(jq -r '.[]? | gsub("[\\t\\r\\n]"; " ")' <<<"$raw")" || die "unreadable filter-log-events output; CloudWatch $LOG_GROUP $stream" 1
  n="$(grep -Eic "$SECRET_RE" <<<"$raw")" || true
  { grep -Eiv "$SECRET_RE" <<<"$raw" | sed '/^$/d' || true; } >"$2"
  if [ "$n" != 0 ]; then
    printf '(%s line(s) dropped: could carry a credential)\n' "$n" >>"$2"
    warn "dropped $n line(s) that could carry a credential from $(basename "$2")"
  fi
}

# expect_line <filter pattern> <record file> <grep -E regex>: after an exit-0 task the CLI always
# printed the line, so a miss is ingestion lag or a log-read problem, never "still running": up to
# 3 re-reads 15 s apart, then exit 1 naming the stream.
expect_line() {
  local i
  for i in 0 1 2 3; do
    [ "$i" = 0 ] || { info "no line matching $1 yet; re-read $i of 3 in 15 s"; sleep 15; }
    task_lines "$1" "$2"
    grep -Eq "$3" "$2" && return 0
  done
  die "the task exited 0 but no line matches $1 after 3 re-reads; CloudWatch $LOG_GROUP $stream" 1
}

# run_loader_task <label> <filter pattern> <grep -E regex of the expected line> <LoadNeptuneCli args...>:
# one Fargate task (no retry), waits, saves its result lines; after a non-zero exit also its error lines.
run_loader_task() {
  local label="$1" pattern="$2" want="$3" overrides task id code stream reason
  shift 3
  overrides="$(printf '%s\n' "$@" | jq -cRn '[inputs] as $a | {containerOverrides: [{name: "neptune-loader",
    command: (["-main", "scholarchain.storage.cli.LoadNeptuneCli"] + $a)}]}')"
  task="$(aws_once ecs run-task --cluster "$CLUSTER" --task-definition "$FAMILY" --launch-type FARGATE --count 1 \
    --started-by scholarchain-load-stores --overrides "$overrides" \
    --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=ENABLED}" \
    --query 'tasks[0].taskArn' --output text)"
  [ -n "$task" ] && [ "$task" != None ] || die "run-task started no task ($label)" 1
  id="${task##*/}"
  info "task $id ($label)"
  _stopped() { [ "$(aws_retry ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" --query 'tasks[0].lastStatus' --output text)" = STOPPED ]; }
  wait_until "loader task $id" 1800 15 _stopped
  code="$(aws_retry ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" --query 'tasks[0].containers[0].exitCode' --output text)"
  stream="neptune-loader/neptune-loader/$id"
  LAST_LOG="$REC/neptune-$label-$id.txt"
  LAST_STREAM="$stream"
  if [ "$code" = 0 ]; then
    expect_line "$pattern" "$LAST_LOG" "$want"
    cat "$LAST_LOG"
    return 0
  fi
  reason="$(aws_retry ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" --query 'tasks[0].stoppedReason' --output text)" || reason="unknown"
  warn "loader task $id exited $code ($reason)"
  task_lines "$pattern" "$LAST_LOG"
  task_lines "$ERROR_PATTERN" "$REC/neptune-$label-$id-errors.txt"
  cat "$LAST_LOG" "$REC/neptune-$label-$id-errors.txt"
  die "loader task $id exited $code ($reason); error lines $REC/neptune-$label-$id-errors.txt; full log: CloudWatch $LOG_GROUP $stream" 1
}

step "neptune"
T0=$SECONDS
LOAD_ID=""
if skipped neptune; then
  note neptune skipped 0 ""
  ok "neptune skipped (--skip)"
else
  run_loader_task start '"started load"' 'started load [^ ;]+' \
    --live --stage-from-bucket --dir "/tmp/stores/s3/graph/neptune/snapshot=$D/run=$STORE_RUN" --out /tmp/stores
  LOAD_ID="$(sed -n 's/.*started load \([^ ;]*\).*/\1/p' "$LAST_LOG" | head -1)"
  [ -n "$LOAD_ID" ] || die "no 'started load <id>' line in $LAST_LOG (full log: CloudWatch $LOG_GROUP $LAST_STREAM)" 1
  printf '==> NEPTUNE load=%s\n' "$LOAD_ID"
  NEPTUNE_STATUS=""
  _load_done() {
    run_loader_task status "?\"load $LOAD_ID status\" ?\"errors parsing\"" "^load $LOAD_ID status [A-Z_]+" \
      --live --status "$LOAD_ID"
    NEPTUNE_STATUS="$(sed -n 's/^load [^ ]* status \([A-Z_]*\).*/\1/p' "$LAST_LOG" | head -1)"
    info "load $LOAD_ID $NEPTUNE_STATUS"
    case "$NEPTUNE_STATUS" in
      LOAD_COMPLETED) return 0 ;;
      LOAD_NOT_STARTED | LOAD_IN_PROGRESS | LOAD_IN_QUEUE) sleep 120; return 1 ;;
      *) die "Neptune load $LOAD_ID ended $NEPTUNE_STATUS; log $LAST_LOG (full log: CloudWatch $LOG_GROUP $LAST_STREAM)" 1 ;;
    esac
  }
  wait_until "Neptune load $LOAD_ID" $((NEPTUNE_MIN * 60)) 1 _load_done
  note neptune "$NEPTUNE_STATUS" "$((SECONDS - T0))" "$(basename "$LAST_LOG")"
  ok "neptune load $LOAD_ID completed"
fi

loader dynamodb "storage/runMain scholarchain.storage.cli.LoadDynamoCli --dir $DYNAMO_DIR $LIVE --concurrency $CONCURRENCY"
loader vectors "storage/runMain scholarchain.storage.cli.LoadVectorsCli --input $VECTORS_DIR $LIVE"

step "record"
jq -n --arg at "$(utc_now)" --arg account "$ACCOUNT" --arg region "$REGION" --arg commit "$(git_head)" \
  --arg storeRun "$STORE_RUN" --arg vectorsRun "$VECTORS_RUN" --arg wsRun "$WS_RUN" --arg loadId "$LOAD_ID" \
  --arg skip "$SKIP" --argjson steps "$STEPS" \
  '{schemaVersion: "store-load-record-v1", finishedAt: $at, account: $account, region: $region, commit: $commit,
    snapshot: "2026-10-03", storeRun: $storeRun, vectorsRun: $vectorsRun, windowSummaryRun: $wsRun,
    neptuneLoadId: (if $loadId == "" then null else $loadId end), steps: $steps,
    skippedSteps: ($skip | split(",") | map(select(. != ""))),
    counts: "printed by each loader; see the per-step logs in this directory"}' >"$REC/run.json"
printf '==> STORES record=%s\n' "$REC/run.json"
ok "store loads complete"
