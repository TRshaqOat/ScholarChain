# shellcheck shell=bash
# Shared helpers for scripts/*.sh. Sourced, never executed. Bash 3.2 compatible (macOS): no
# associative arrays, no mapfile, no ${x,,}.
#
# Exit codes used by every script:
#   0 success
#   1 a gate or verification failed (evidence is printed)
#   2 usage error or missing tool
#   3 precondition failed (dirty tree, wrong account or Region, missing input or record)
#   4 approval mismatch (plan checksum or confirmation does not match)
#   5 waiting for a documented human action (for example the API key secret value)
#   6 timeout of a cloud operation

set -euo pipefail

SC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SC_TF_DIR="$SC_ROOT/infra/terraform/envs/course"
SC_TF_VERSION="1.16.6"
SC_DEFAULT_REGION="us-east-2"
SC_SNAPSHOT="2026-10-03"
DRY_RUN=0
PROFILE="${AWS_PROFILE:-}"
REGION="$SC_DEFAULT_REGION"

step() { printf '==> STEP %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
ok() { printf '==> OK %s\n' "$*"; }
warn() { printf '==> WARN %s\n' "$*" >&2; }
die() {
  printf '==> FAIL %s (exit %s)\n' "$1" "${2:-1}" >&2
  exit "${2:-1}"
}

# Flag value or a usage error: VAR="$(flag_val "$1" "${2-}")"; shift 2
flag_val() {
  [ -n "${2-}" ] && [ "${2#--}" = "$2" ] || die "$1 needs a value" 2
  printf '%s' "$2"
}

need() {
  local t
  for t in "$@"; do command -v "$t" >/dev/null 2>&1 || die "missing tool: $t" 2; done
}

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
utc_stamp() { date -u +%Y%m%dT%H%M%SZ; }

sha256_hex() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi
}

is_hex64() { printf '%s' "$1" | grep -Eq '^[0-9a-f]{64}$'; }
is_sha40() { printf '%s' "$1" | grep -Eq '^[0-9a-f]{40}$'; }
is_run_id() { printf '%s' "$1" | grep -Eq '^run-[0-9a-f]{32}$'; }

# Dry run: validate flags and local inputs, print the steps, make no AWS, Terraform, or git call.
dry_run_exit() {
  [ "$DRY_RUN" = 1 ] || return 0
  step "dry-run"
  info "no AWS, Terraform, or git call was made; steps that would run:"
  local s
  for s in "$@"; do info "- $s"; done
  ok "dry-run"
  exit 0
}

git_head() { git -C "$SC_ROOT" rev-parse HEAD; }

# Refuses any change outside the generated record trees (operations/, experiments/results/),
# including untracked files: an untracked .tf or .scala file would change what is built or applied.
require_clean_tree() {
  step "clean-tree"
  local dirty
  dirty="$(git -C "$SC_ROOT" status --porcelain --untracked-files=all -- . \
    ':(exclude)operations' ':(exclude)experiments/results')" || die "git status failed" 3
  if [ -n "$dirty" ]; then
    printf '%s\n' "$dirty" | head -50 >&2
    die "dirty working tree: commit or remove the changes above" 3
  fi
  ok "clean-tree at $(git_head)"
}

require_profile() {
  [ -n "$PROFILE" ] || die "set --profile or AWS_PROFILE" 2
  [ "$REGION" = "$SC_DEFAULT_REGION" ] || die "Region must be $SC_DEFAULT_REGION (D-04); got $REGION" 2
  export AWS_PROFILE="$PROFILE" AWS_REGION="$REGION" AWS_PAGER=""
}

# Read calls and idempotent writes (same key and bytes, same desired state): SDK standard retries.
aws_retry() {
  AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=5 aws --profile "$PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 120 "$@"
}

# Non-idempotent calls (create a cluster, start a build, run a task, register a revision): one attempt.
aws_once() {
  AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=1 aws --profile "$PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 120 "$@"
}

# Prints the target before any action, and sets ACCOUNT.
aws_target() {
  step "target"
  ACCOUNT="$(aws_retry sts get-caller-identity --query Account --output text)" || die "cannot resolve the account (credentials?)" 3
  printf '==> TARGET account=%s region=%s profile=%s\n' "$ACCOUNT" "$REGION" "$PROFILE"
}

tf() { terraform -chdir="$SC_TF_DIR" "$@"; }

tf_require_version() {
  local v
  v="$(terraform version -json | jq -r .terraform_version)"
  [ "$v" = "$SC_TF_VERSION" ] || die "terraform $SC_TF_VERSION required (ruling 4); found $v" 2
}

# Reads the root outputs from local state (no cloud call) into TF_OUT and checks the account.
tf_outputs() {
  step "terraform-outputs"
  TF_OUT="$(mktemp "${TMPDIR:-/tmp}/sc-tfout.XXXXXX")"
  tf output -json >"$TF_OUT" || die "terraform output failed (is the environment applied?)" 3
  local acct
  acct="$(tfo '.account_id.value')"
  [ "$acct" = "$ACCOUNT" ] || die "state account $acct differs from the profile account $ACCOUNT" 3
  ok "outputs for account $acct"
}

# One output value by jq path; fails when absent or null (false is a value, so no jq -e).
tfo() {
  local v
  v="$(jq -r "$1" "$TF_OUT")" && [ "$v" != null ] || die "terraform output missing: $1" 3
  printf '%s\n' "$v"
}

# Polls a predicate until it succeeds. The predicate may call die for a terminal failure.
wait_until() {
  local desc="$1" timeout_s="$2" interval_s="$3"
  shift 3
  local deadline=$((SECONDS + timeout_s))
  while ! "$@"; do
    [ "$SECONDS" -lt "$deadline" ] || die "timeout after ${timeout_s}s waiting for $desc" 6
    sleep "$interval_s"
  done
}

# Write-once upload: an object already at the key must carry the same sha256 metadata.
s3_put_if_absent() {
  local f="$1" bucket="$2" key="$3" sha remote err
  sha="$(sha256_hex "$f")"
  err="$(mktemp "${TMPDIR:-/tmp}/sc-head.XXXXXX")"
  if remote="$(aws_retry s3api head-object --bucket "$bucket" --key "$key" \
    --query 'Metadata.sha256' --output text 2>"$err")"; then
    rm -f "$err"
    [ "$remote" = "$sha" ] || die "s3://$bucket/$key exists with sha256 '$remote', local $sha (write-once, not overwritten)" 1
    info "present  s3://$bucket/$key"
  else
    if ! grep -Eq '\(404\)|Not Found' "$err"; then
      cat "$err" >&2
      rm -f "$err"
      die "head-object failed for s3://$bucket/$key" 1
    fi
    rm -f "$err"
    aws_retry s3 cp --only-show-errors --metadata "sha256=$sha" "$f" "s3://$bucket/$key"
    info "uploaded s3://$bucket/$key"
  fi
}

# The three runtime services; query-api and the worker always move together (SERVICES.md section 5).
SC_SERVICES="query-api graph-evidence-api worker"

# Waits until every service has one COMPLETED deployment with running = desired >= 1.
ecs_wait_stable() {
  local cluster="$1" timeout_s="$2"
  _ecs_stable() {
    local s line
    for s in $SC_SERVICES; do
      line="$(aws_retry ecs describe-services --cluster "$cluster" --services "$s" --query \
        'services[0].[length(deployments), deployments[0].rolloutState, runningCount, desiredCount]' --output text)"
      info "$s deployments/rollout/running/desired: $line"
      case "$line" in *FAILED*) die "$s deployment FAILED (the circuit breaker rolls it back); see the ECS service events" 1 ;; esac
      printf '%s' "$line" | awk '{exit !($1 == 1 && $2 == "COMPLETED" && $3 == $4 && $3 >= 1)}' || return 1
    done
  }
  step "wait-stable"
  wait_until "ECS services stable" "$timeout_s" 20 _ecs_stable
  ok "services stable"
}

# Every running task of every service runs <image> with <digest>. Sets ECS_SERVICES_JSON.
ecs_verify_image() {
  local cluster="$1" image="$2" digest="$3" s td td_image tasks digests
  step "verify-digests"
  ECS_SERVICES_JSON='[]'
  for s in $SC_SERVICES; do
    td="$(aws_retry ecs describe-services --cluster "$cluster" --services "$s" --query 'services[0].taskDefinition' --output text)"
    td_image="$(aws_retry ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text)"
    [ "$td_image" = "$image" ] || die "$s task definition image $td_image is not $image" 1
    tasks="$(aws_retry ecs list-tasks --cluster "$cluster" --service-name "$s" --desired-status RUNNING --query 'taskArns' --output text)"
    [ -n "$tasks" ] && [ "$tasks" != None ] || die "$s has no running task" 1
    # shellcheck disable=SC2086
    digests="$(aws_retry ecs describe-tasks --cluster "$cluster" --tasks $tasks \
      --query 'tasks[].containers[0].imageDigest' --output text | tr '\t' '\n' | sort -u)"
    [ "$digests" = "$digest" ] || die "$s runs digest(s) '$digests', expected $digest" 1
    info "$s $td $digests"
    ECS_SERVICES_JSON="$(jq -c --arg s "$s" --arg td "$td" --arg d "$digests" '. + [{service: $s, taskDefinitionArn: $td, imageDigest: $d}]' <<<"$ECS_SERVICES_JSON")"
  done
  ok "running digests equal $digest"
}

# query-api and the worker must log the same runtime run ID (SERVICES.md section 5). Sets RUN_IDS.
ecs_verify_run_ids() {
  local logs="$1" s group rid
  step "runtime-run-ids"
  RUN_IDS='{}'
  for s in query-api worker; do
    group="$(jq -er --arg s "$s" '.[$s]' <<<"$logs")"
    rid="$(aws_retry logs filter-log-events --log-group-name "$group" --start-time $((($(date +%s) - 3600) * 1000)) \
      --filter-pattern '"live mode: runtime run"' --query 'events[-1].message' --output text |
      sed -n 's/.*runtime run \(run-[0-9a-f]*\).*/\1/p')"
    [ -n "$rid" ] || die "no 'live mode: runtime run' line from $s in the last hour" 1
    info "$s runtime run $rid"
    RUN_IDS="$(jq -c --arg s "$s" --arg r "$rid" '. + {($s): $r}' <<<"$RUN_IDS")"
  done
  [ "$(jq -r '."query-api"' <<<"$RUN_IDS")" = "$(jq -r .worker <<<"$RUN_IDS")" ] ||
    die "query-api and worker runtime run IDs differ: $RUN_IDS (SERVICES.md section 5)" 1
  ok "query-api and worker share one runtime run ID"
}

# Runs sbt under the Phase 12 ruling 8 limits (SPARK_LOCAL_IP, heap at most 6g, one process).
sbt_run() {
  if pgrep -f 'sbt-launch|xsbt.boot' >/dev/null 2>&1; then
    die "another sbt process is running; never run two at once" 3
  fi
  (cd "$SC_ROOT" && SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g -batch "$@")
}
