#!/usr/bin/env bash
# Experiment F: one poison message (retry, then DLQ) plus an idempotent async replay.
# Run only through scripts/run-experiment.sh with experiments/manifests/exp-f-failure-recovery-v1.json.
# Bash 3.2; reuses scripts/lib/common.sh (the smoke-test.sh input sources and helpers).
set -euo pipefail
# shellcheck source=../../scripts/lib/common.sh
. "$(dirname "$0")/../../scripts/lib/common.sh"
# An inherited xtrace (bash -x, or SHELLOPTS exported) would trace the API key (code review 4 finding 8).
case $- in *x*) die "xtrace is on; refusing to handle the API key (no bash -x, no exported SHELLOPTS)" 2 ;; esac

# Benchmark B07, the smoke-test.sh default question (one bounded researcher query).
QUESTION="Have Jack J. Dongarra and Yves Robert coauthored a paper?"

usage() {
  cat <<EOF
Usage: experiments/harness/exp-f-failure-recovery.sh [--dlq-wait-minutes 22] [--job-wait-minutes 5] [--dry-run]

Steps (each prints ==> STEP; raw files go only under \$SCHOLARCHAIN_EXPERIMENT_DIR):
  preconditions  terraform output (local state); runtime_enabled true; the job DLQ is empty
  poison         ONE aws sqs send-message to the job queue with a body that is not a
                 job-message-v1 object (Worker.scala: logged as poison-message, never deleted,
                 so it is received 3 times, 300 s apart, and SQS redrives it to the DLQ)
  submit         POST /v1/query {"execution":"async"} with Idempotency-Key exp-f-<stamp> = 202
  replay         the same POST and key = 202, Idempotent-Replay: true, the same jobId, state
                 QUEUED or RUNNING
  job            GET /v1/jobs/<jobId> every 10 s until SUCCEEDED or FAILED (--job-wait-minutes)
  result         GET /v1/jobs/<jobId>/result; the same POST and key again = 202, the same jobId,
                 state SUCCEEDED or FAILED
  dlq            get-queue-attributes of both queues every 30 s until the DLQ holds 1 message
                 (--dlq-wait-minutes after the send; a failed poll is recorded and polling goes on)
  logs           worker poison-message and job-finished events, query-api job-queued events
                 (from 300 s before the start, for clock skew; a failed query is a FAIL check)
  verdict        summary.json; exit 0 when every check passed, 1 otherwise

Haiku calls: the one async question (planner and synthesis, 2; at most 4 with one retry each).
The API key is read from Secrets Manager into a variable and reaches curl on standard input only.
The harness refuses to run with xtrace on (bash -x or an exported SHELLOPTS).
One attempt per window: the DLQ must be empty at the start, and the poison message stays in it,
so a rerun first needs the human to remove the DLQ message (a human-only action).
Cleanup (human): the poison message stays in the DLQ (14-day retention) and the DLQ alarm stays in
ALARM until cleanup.sh --stage runtime destroys the queue; the job, idempotency and audit records
expire by TTL or go with the runtime stores.
Exit: 0 all checks passed; 1 a check failed; 2 usage or xtrace on; 3 precondition (including an
AWS read that fails before the poison is sent); 4 send-message failed or timed out (the message
may be queued: check both queues before any rerun).
EOF
}

DLQ_WAIT_MIN=22
JOB_WAIT_MIN=5
while [ $# -gt 0 ]; do
  case "$1" in
    --dlq-wait-minutes) DLQ_WAIT_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --job-wait-minutes) JOB_WAIT_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
printf '%s' "$DLQ_WAIT_MIN" | grep -Eq '^[0-9]+$' && [ "$DLQ_WAIT_MIN" -ge 16 ] && [ "$DLQ_WAIT_MIN" -le 25 ] ||
  die "--dlq-wait-minutes must be 16 to 25 (3 receives x 300 s visibility, then the redrive)" 2
printf '%s' "$JOB_WAIT_MIN" | grep -Eq '^[0-9]+$' && [ "$JOB_WAIT_MIN" -ge 1 ] && [ "$JOB_WAIT_MIN" -le 5 ] ||
  die "--job-wait-minutes must be 1 to 5" 2
need curl jq aws terraform

dry_run_exit \
  "terraform output alb_dns_name, api_key_secret_arn, job_queue_url, job_dlq_url, log_group_names; aws sts get-caller-identity" \
  "precondition: DLQ ApproximateNumberOfMessages + NotVisible = 0 (one attempt per window; a rerun first needs the human to remove the DLQ message)" \
  "aws sqs send-message --queue-url <job_queue_url> --message-body '{\"schemaVersion\":\"exp-f-poison-v1\",\"note\":\"experiment F poison <stamp>\"}' (one attempt)" \
  "POST http://<alb>/v1/query {\"question\":\"$QUESTION\",\"execution\":\"async\"} Idempotency-Key exp-f-<stamp> -> 202 jobId" \
  "POST the same body and key -> 202, Idempotent-Replay: true, same jobId, state QUEUED or RUNNING" \
  "GET /v1/jobs/<jobId> every 10 s for at most $JOB_WAIT_MIN min; GET /v1/jobs/<jobId>/result; POST the same body and key once more -> 202, same jobId, state SUCCEEDED or FAILED" \
  "get-queue-attributes (job queue and DLQ) every 30 s for at most $DLQ_WAIT_MIN min after the send" \
  "aws logs filter-log-events: worker poison-message and job-finished, query-api job-queued" \
  "write \$SCHOLARCHAIN_EXPERIMENT_DIR/summary.json; Haiku calls 2 (at most 4)"

OUT="${SCHOLARCHAIN_EXPERIMENT_DIR:-}"
[ -n "$OUT" ] && [ -d "$OUT" ] || die "SCHOLARCHAIN_EXPERIMENT_DIR is not a directory (run through scripts/run-experiment.sh)" 3
require_profile
aws_target
tf_outputs
[ "$(tfo '.runtime_enabled.value')" = true ] || die "runtime_enabled is false" 3
BASE="http://$(tfo '.alb_dns_name.value')"
SECRET="$(tfo '.api_key_secret_arn.value')"
QUEUE="$(tfo '.job_queue_url.value')"
DLQ="$(tfo '.job_dlq_url.value')"
WORKER_LOG="$(tfo '.log_group_names.value.worker')"
QAPI_LOG="$(tfo '.log_group_names.value["query-api"]')"
STAMP="$(utc_stamp)"
# 300 s margin for laptop clock skew; the events are selected by messageId and jobId (finding 6).
START_MS=$((($(date +%s) - 300) * 1000))
IDEM="exp-f-$STAMP"
jq -n --arg base "$BASE" --arg q "$QUEUE" --arg d "$DLQ" --arg w "$WORKER_LOG" --arg a "$QAPI_LOG" \
  --arg acct "$ACCOUNT" --arg region "$REGION" --arg idem "$IDEM" --arg at "$(utc_now)" \
  --argjson dlqWait "$DLQ_WAIT_MIN" --argjson jobWait "$JOB_WAIT_MIN" \
  '{baseUrl: $base, jobQueueUrl: $q, dlqUrl: $d, workerLogGroup: $w, queryApiLogGroup: $a,
    account: $acct, region: $region, idempotencyKey: $idem, startedAt: $at,
    bounds: {dlqWaitMinutes: $dlqWait, jobWaitMinutes: $jobWait, poisonMessages: 1, asyncQuestions: 1}}' >"$OUT/inputs.json"

CHECKS='[]'
check() { # name PASS|FAIL detail
  CHECKS="$(jq -c --arg n "$1" --arg r "$2" --arg d "$3" '. + [{check: $n, result: $r, detail: $d}]' <<<"$CHECKS")"
  if [ "$2" = PASS ]; then ok "$1 $3"; else warn "$1 FAIL $3"; fi
}

queue_depth() { # url -> "visible notVisible"
  aws_retry sqs get-queue-attributes --queue-url "$1" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query 'Attributes.[ApproximateNumberOfMessages, ApproximateNumberOfMessagesNotVisible]' --output text
}

step "preconditions"
DLQ0="$(queue_depth "$DLQ")" || die "get-queue-attributes failed for the DLQ" 3
printf '%s %s\n' "$(utc_now)" "$DLQ0" >"$OUT/dlq-before.txt"
[ "$(printf '%s' "$DLQ0" | awk '{print $1 + $2}')" = 0 ] ||
  die "the DLQ is not empty ($DLQ0); the evidence would be ambiguous. F runs once per window: a rerun first needs the human to remove the DLQ message" 3
ok "DLQ empty"

step "api-key"
API_KEY="$(aws_retry secretsmanager get-secret-value --secret-id "$SECRET" --query SecretString --output text)" ||
  die "the API key secret could not be read" 3
[ -n "$API_KEY" ] && [ "$API_KEY" != None ] || die "the API key secret has no value" 3
KEY_ESC="${API_KEY//\\/\\\\}"
KEY_ESC="${KEY_ESC//\"/\\\"}"
unset API_KEY
ok "API key read (not printed)"

# req method path out [body-file] -> prints the HTTP status; response headers go to <out>.headers.
# As smoke-test.sh: the key reaches curl on standard input (--config -), never argv or a file.
req() {
  local method="$1" path="$2" out="$3" body="${4-}"
  local args=(-sS -o "$out" -D "$out.headers" -w '%{http_code}' --connect-timeout 10 --max-time 60
    -X "$method" -H "X-Request-Id: $IDEM")
  [ -z "$body" ] || args+=(-H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM" --data-binary "@$body")
  printf 'header = "X-Api-Key: %s"\n' "$KEY_ESC" | curl --config - "${args[@]}" "$BASE$path" || true
}
replayed() { tr -d '\r' <"$1.headers" | grep -iq '^Idempotent-Replay: true$'; }

step "poison"
POISON_BODY="$(jq -cn --arg s "$STAMP" '{schemaVersion: "exp-f-poison-v1", note: ("experiment F poison " + $s)}')"
SENT_AT="$(utc_now)"
SENT_S=$SECONDS
if ! MSG_ID="$(aws_once sqs send-message --queue-url "$QUEUE" --message-body "$POISON_BODY" --query MessageId --output text)"; then
  jq -n --arg at "$SENT_AT" --arg body "$POISON_BODY" '{messageId: null, sentAt: $at, body: $body, sendFailed: true}' >"$OUT/poison-sent.json"
  die "send-message failed or timed out; the message may be queued: check both queues before any rerun" 4
fi
jq -n --arg id "$MSG_ID" --arg at "$SENT_AT" --arg body "$POISON_BODY" '{messageId: $id, sentAt: $at, body: $body}' >"$OUT/poison-sent.json"
ok "poison message $MSG_ID sent at $SENT_AT"

step "submit"
jq -n --arg q "$QUESTION" '{question: $q, execution: "async"}' >"$OUT/query-request.json"
CODE1="$(req POST /v1/query "$OUT/submit-1.json" "$OUT/query-request.json")"
JOB="$(jq -r '.jobId // empty' "$OUT/submit-1.json" 2>/dev/null || true)"
if [ "$CODE1" = 202 ] && [ -n "$JOB" ]; then check submit PASS "202 $JOB"; else check submit FAIL "http $CODE1"; fi

step "replay"
CODE2="$(req POST /v1/query "$OUT/submit-2.json" "$OUT/query-request.json")"
JOB2="$(jq -r '.jobId // empty' "$OUT/submit-2.json" 2>/dev/null || true)"
STATE2="$(jq -r '.state // empty' "$OUT/submit-2.json" 2>/dev/null || true)"
case "$STATE2" in QUEUED | RUNNING) LIVE2=1 ;; *) LIVE2=0 ;; esac
if [ "$CODE2" = 202 ] && [ -n "$JOB" ] && [ "$JOB2" = "$JOB" ] && [ "$LIVE2" = 1 ] && replayed "$OUT/submit-2.json"; then
  check replay-while-queued PASS "202 same jobId, Idempotent-Replay true, state $STATE2"
else check replay-while-queued FAIL "http $CODE2 jobId '$JOB2' state '${STATE2:-none}'"; fi

step "job"
STATE=""
if [ -n "$JOB" ]; then
  END_S=$((SECONDS + JOB_WAIT_MIN * 60))
  while [ "$SECONDS" -lt "$END_S" ]; do
    CODE="$(req GET "/v1/jobs/$JOB" "$OUT/job-poll.json")"
    STATE="$(jq -r '.state // empty' "$OUT/job-poll.json" 2>/dev/null || true)"
    printf '%s %s %s\n' "$(utc_now)" "$CODE" "$STATE" >>"$OUT/job-polls.txt"
    case "$STATE" in SUCCEEDED | FAILED) break ;; esac
    sleep 10
  done
  if [ -f "$OUT/job-poll.json" ]; then cp "$OUT/job-poll.json" "$OUT/job-final.json"; fi
fi
if [ "$STATE" = SUCCEEDED ]; then check job PASS "SUCCEEDED"; else check job FAIL "state '${STATE:-none}'"; fi

step "result"
if [ "$STATE" = SUCCEEDED ]; then
  CODE="$(req GET "/v1/jobs/$JOB/result" "$OUT/result.json")"
  if [ "$CODE" = 200 ]; then check result PASS "200 $(jq -r .queryId "$OUT/result.json")"; else check result FAIL "http $CODE"; fi
fi
CODE3="$(req POST /v1/query "$OUT/submit-3.json" "$OUT/query-request.json")"
JOB3="$(jq -r '.jobId // empty' "$OUT/submit-3.json" 2>/dev/null || true)"
STATE3="$(jq -r '.state // empty' "$OUT/submit-3.json" 2>/dev/null || true)"
case "$STATE3" in SUCCEEDED | FAILED) FINAL3=1 ;; *) FINAL3=0 ;; esac
if [ "$CODE3" = 202 ] && [ -n "$JOB" ] && [ "$JOB3" = "$JOB" ] && [ "$FINAL3" = 1 ] && replayed "$OUT/submit-3.json"; then
  check replay-after-final PASS "202 same jobId, Idempotent-Replay true, state $STATE3"
else check replay-after-final FAIL "http $CODE3 jobId '$JOB3' state '${STATE3:-none}'"; fi

step "dlq"
DLQ_AT=""
END_S=$((SENT_S + DLQ_WAIT_MIN * 60))
while [ "$SECONDS" -lt "$END_S" ]; do
  Q="$(queue_depth "$QUEUE")" || Q=error
  D="$(queue_depth "$DLQ")" || D=error
  printf '%s jobs %s dlq %s\n' "$(utc_now)" "$Q" "$D" >>"$OUT/queue-depths.txt"
  if [ "$(printf '%s' "$D" | awk '{print $1 + $2}')" -ge 1 ]; then
    DLQ_AT="$(utc_now)"
    break
  fi
  sleep 30
done
if [ -n "$DLQ_AT" ]; then check dlq PASS "DLQ holds the message at $DLQ_AT (sent $SENT_AT)"; else check dlq FAIL "DLQ still empty after $DLQ_WAIT_MIN min"; fi

step "logs"
logs() { # group pattern out; a failed query leaves [] and a FAIL check (finding 5)
  if ! aws_retry logs filter-log-events --log-group-name "$1" --start-time "$START_MS" \
    --filter-pattern "$2" --query 'events[].message' --output json >"$3"; then
    printf '[]\n' >"$3"
    check "logs-$(basename "$3" .json)" FAIL "filter-log-events failed for $1 $2"
  fi
}
logs "$WORKER_LOG" '"poison-message"' "$OUT/worker-poison-events.json"
logs "$WORKER_LOG" '"job-finished"' "$OUT/worker-job-finished-events.json"
logs "$QAPI_LOG" '"job-queued"' "$OUT/query-api-job-queued-events.json"
# Each element is a JSON log line; the ones for this message or job are selected.
RECEIVES="$(jq -c --arg id "$MSG_ID" '[.[] | fromjson? | select(.messageId == $id) | .receiveCount] | sort' "$OUT/worker-poison-events.json")"
FINISHED="$(jq --arg j "$JOB" '[.[] | fromjson? | select(.jobId == $j)] | length' "$OUT/worker-job-finished-events.json")"
QUEUED="$(jq --arg j "$JOB" '[.[] | fromjson? | select(.jobId == $j)] | length' "$OUT/query-api-job-queued-events.json")"
if [ "$RECEIVES" = "[1,2,3]" ]; then check poison-retried PASS "receiveCount $RECEIVES"; else check poison-retried FAIL "receiveCount $RECEIVES"; fi
if [ "$FINISHED" = 1 ] && [ "$QUEUED" = 1 ]; then check one-job-run PASS "job-queued 1, job-finished 1"; else check one-job-run FAIL "job-queued $QUEUED, job-finished $FINISHED"; fi

step "verdict"
RESULT=PASS
[ "$(jq '[.[] | select(.result == "FAIL")] | length' <<<"$CHECKS")" = 0 ] || RESULT=FAIL
jq -n --arg r "$RESULT" --arg job "$JOB" --arg msg "$MSG_ID" --arg sent "$SENT_AT" --arg dlq "$DLQ_AT" \
  --arg end "$(utc_now)" --argjson receives "${RECEIVES:-null}" --argjson checks "$CHECKS" \
  '{schemaVersion: "exp-f-summary-v1", result: $r, jobId: $job, poisonMessageId: $msg, poisonSentAt: $sent,
    dlqObservedAt: (if $dlq == "" then null else $dlq end), poisonReceiveCounts: $receives,
    endedAt: $end, checks: $checks}' >"$OUT/summary.json"
printf '==> EXP-F result=%s job=%s poison=%s dlq=%s\n' "$RESULT" "${JOB:-none}" "$MSG_ID" "${DLQ_AT:-none}"
[ "$RESULT" = PASS ] || exit 1
