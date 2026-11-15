#!/usr/bin/env bash
# Smoke test of the deployed runtime through the ALB (spec Phase 13 item 5).
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

DEFAULT_QUESTION="Have Jack J. Dongarra and Yves Robert coauthored a paper?"

usage() {
  cat <<EOF
Usage: scripts/smoke-test.sh [--profile <name>] [--region us-east-2] [--base-url http://<alb dns>]
                             [--question "<text>"] [--record-dir <dir>] [--dry-run]

Checks, in order (each prints ==> OK or ==> FAIL; the first failure stops the run):
  healthz      GET /healthz = 200 {"status":"ok"} (no key; retried, idempotent)
  readyz       GET /readyz = 200 {"status":"ready"} (no key; retried up to 3 minutes)
  query        POST /v1/query {"question": <question>, "execution": "sync"} with a fresh
               Idempotency-Key = 200, a qry- ID, and at least one claim with an evidence ID
               (not retried; max 60 s against the 40 s synchronous deadline)
  evidence     GET /v1/evidence/<first evidence ID of the first such claim> = 200, same evidenceId
  idempotent   the same POST with the same Idempotency-Key = 200, same queryId and answerHash
  auth-error   POST /v1/query without X-Api-Key = 401 {"error":"UNAUTHORIZED"}

The API key is read from Secrets Manager (the ARN from terraform output api_key_secret_arn) into a
shell variable and passed to curl on standard input (--config -), never in argv, a file, or output.

Flags:
  --base-url     default http://<terraform output alb_dns_name>
  --question     default "$DEFAULT_QUESTION" (benchmark B07: one bounded researcher query)
  --record-dir   default operations/releases (writes smoke-<stamp>.json and smoke-<stamp>/ bodies)
  --dry-run      validate flags, print the checks, no AWS/Terraform/HTTP call

Environment: AWS_PROFILE (or --profile).
Run by: deploy-engineer after deploy.sh (and the incident-responder after a rollback).
Rollback: none; on failure the deploy-engineer follows the rollback gate (scripts/rollback.sh).
Exit: 0 all checks passed; 1 a check failed; 3 precondition.
EOF
}

BASE=""
QUESTION="$DEFAULT_QUESTION"
RECORD_DIR="$SC_ROOT/operations/releases"
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --base-url) BASE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --question) QUESTION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --record-dir) RECORD_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
[ -z "$BASE" ] || printf '%s' "$BASE" | grep -Eq '^https?://[A-Za-z0-9.-]+(:[0-9]+)?$' || die "--base-url must be http(s)://host[:port]" 2
need curl jq aws terraform

dry_run_exit \
  "aws sts get-caller-identity; terraform output alb_dns_name and api_key_secret_arn" \
  "GetSecretValue into a variable (never printed)" \
  "healthz, readyz, query (question: $QUESTION), evidence, idempotent repeat, 401 without a key" \
  "write $RECORD_DIR/smoke-<stamp>.json"

require_profile
aws_target
tf_outputs
[ "$(tfo '.runtime_enabled.value')" = true ] || die "runtime_enabled is false: nothing to smoke-test" 3
[ -n "$BASE" ] || BASE="http://$(tfo '.alb_dns_name.value')"
SECRET="$(tfo '.api_key_secret_arn.value')"

STAMP="$(utc_stamp)"
OUT="$RECORD_DIR/smoke-$STAMP"
mkdir -p "$OUT"
RESULTS='[]'

step "api-key"
API_KEY="$(aws_retry secretsmanager get-secret-value --secret-id "$SECRET" --query SecretString --output text)"
[ -n "$API_KEY" ] && [ "$API_KEY" != None ] || die "the API key secret has no value" 3
KEY_ESC="${API_KEY//\\/\\\\}"
KEY_ESC="${KEY_ESC//\"/\\\"}"
unset API_KEY
ok "API key read (not printed)"

# req auth method path out [body-file] [idempotency-key] -> prints the HTTP status
req() {
  local auth="$1" method="$2" path="$3" out="$4" body="${5-}" idem="${6-}" cfg=""
  local args=(-sS -o "$out" -w '%{http_code}' --connect-timeout 10 --max-time 60 -X "$method"
    -H "X-Request-Id: smoke-$STAMP")
  [ -z "$body" ] || args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  [ -z "$idem" ] || args+=(-H "Idempotency-Key: $idem")
  [ "$auth" = no ] || cfg="header = \"X-Api-Key: $KEY_ESC\""
  # curl prints 000 itself when no response arrives.
  printf '%s\n' "$cfg" | curl --config - "${args[@]}" "$BASE$path" || true
}

record() {
  RESULTS="$(jq -c --arg n "$1" --arg s "$2" --arg c "$3" --arg d "$4" '. + [{check: $n, result: $s, http: $c, detail: $d}]' <<<"$RESULTS")"
}

write_record() {
  jq -n --arg base "$BASE" --arg at "$(utc_now)" --arg account "$ACCOUNT" --arg region "$REGION" \
    --arg question "$QUESTION" --arg result "$1" --argjson checks "$RESULTS" \
    '{schemaVersion: "smoke-record-v1", baseUrl: $base, finishedAt: $at, account: $account, region: $region,
      question: $question, result: $result, checks: $checks, bodies: "smoke-'"$STAMP"'/"}' >"$RECORD_DIR/smoke-$STAMP.json"
}

fail() {
  record "$1" FAIL "$2" "$3"
  write_record FAIL
  die "$1: $3 (http $2; bodies in $OUT)" 1
}

pass() {
  record "$1" PASS "$2" "$3"
  ok "$1 $3"
}

step "healthz"
for i in 1 2 3 4 5; do
  CODE="$(req no GET /healthz "$OUT/healthz.json")"
  [ "$CODE" = 200 ] && break
  sleep 5
done
[ "$CODE" = 200 ] && [ "$(jq -r .status "$OUT/healthz.json" 2>/dev/null)" = ok ] || fail healthz "$CODE" "expected 200 ok"
pass healthz "$CODE" "ok"

step "readyz"
for i in $(seq 1 12); do
  CODE="$(req no GET /readyz "$OUT/readyz.json")"
  [ "$CODE" = 200 ] && break
  sleep 15
done
[ "$CODE" = 200 ] && [ "$(jq -r .status "$OUT/readyz.json" 2>/dev/null)" = ready ] || fail readyz "$CODE" "expected 200 ready"
pass readyz "$CODE" "ready"

step "query"
IDEM="smoke-$STAMP"
jq -n --arg q "$QUESTION" '{question: $q, execution: "sync"}' >"$OUT/query-request.json"
CODE="$(req yes POST /v1/query "$OUT/query.json" "$OUT/query-request.json" "$IDEM")"
[ "$CODE" = 200 ] || fail query "$CODE" "expected 200"
QID="$(jq -r .queryId "$OUT/query.json")"
HASH="$(jq -r .answer.answerHash "$OUT/query.json")"
EVID="$(jq -r '[.claims[]? | .evidenceIds[]?][0] // empty' "$OUT/query.json")"
printf '%s' "$QID" | grep -Eq '^qry-[0-9a-f]{32}$' || fail query "$CODE" "no qry- ID"
[ -n "$EVID" ] || fail query "$CODE" "no claim carries an evidence ID (abstained: $(jq -r .answer.abstained "$OUT/query.json"))"
pass query "$CODE" "$QID claims=$(jq '.claims | length' "$OUT/query.json") evidence=$EVID"

step "evidence"
CODE="$(req yes GET "/v1/evidence/$EVID" "$OUT/evidence.json")"
[ "$CODE" = 200 ] && [ "$(jq -r .evidenceId "$OUT/evidence.json")" = "$EVID" ] || fail evidence "$CODE" "expected 200 for $EVID"
pass evidence "$CODE" "$EVID $(jq -r .evidenceType "$OUT/evidence.json")"

step "idempotent"
CODE="$(req yes POST /v1/query "$OUT/query-repeat.json" "$OUT/query-request.json" "$IDEM")"
[ "$CODE" = 200 ] || fail idempotent "$CODE" "expected 200 replay"
[ "$(jq -r .queryId "$OUT/query-repeat.json")" = "$QID" ] && [ "$(jq -r .answer.answerHash "$OUT/query-repeat.json")" = "$HASH" ] ||
  fail idempotent "$CODE" "replay differs from the first answer"
pass idempotent "$CODE" "same queryId and answerHash"

step "auth-error"
CODE="$(req no POST /v1/query "$OUT/unauthorized.json" "$OUT/query-request.json")"
[ "$CODE" = 401 ] && [ "$(jq -r .error "$OUT/unauthorized.json")" = UNAUTHORIZED ] || fail auth-error "$CODE" "expected 401 UNAUTHORIZED"
pass auth-error "$CODE" "UNAUTHORIZED"

write_record PASS
printf '==> SMOKE result=PASS record=%s query=%s\n' "$RECORD_DIR/smoke-$STAMP.json" "$QID"
