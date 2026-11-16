#!/usr/bin/env bash
# Applies one approved saved plan and verifies the deployed revision (spec Phase 13 item 4,
# ruling 6). The approval is the plan's sha256, given explicitly; nothing else is applied.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/deploy.sh --plan-file <dir>/plan.tfplan --approved-plan-sha256 <64 hex>
                         [--package-record <operations/releases/package-<sha>.json>]
                         [--profile <name>] [--region us-east-2] [--release-dir <dir>]
                         [--stable-timeout-minutes 20] [--resume] [--dry-run]

Gates, in order:
  1. clean tree (outside operations/ and experiments/results/), HEAD = the plan's commit
  2. sha256(plan file) = --approved-plan-sha256, else exit 4 and nothing is applied
  3. the profile's account and Region = the plan's (plan-meta.json beside the plan file)
  4. terraform apply <plan file> (a saved plan: no prompt, no -auto-approve, no other input);
     skipped with --resume
  5. runtime_enabled only:
     - the API key secret must have a value (DescribeSecret, the value is never read); if not, exit 5:
       the human sets it (infra/terraform/README.md), then the same command runs again with --resume
     - --resume forces a new deployment of query-api, graph-evidence-api and worker (together)
     - waits until every service is stable (one COMPLETED deployment, running = desired), bounded
     - every running task's image digest = the package record's digest, and its task definition image
       = <repository>:<package record tag>
     - query-api and the worker print the same runtime run ID (SERVICES.md section 5)
  6. writes the release record <release-dir>/release-<UTC stamp>-<commit 12>.json and the apply log
     (.apply.txt, not .log, so that Git does not ignore it)

Flags:
  --plan-file              required; written by scripts/plan.sh; a relative path is resolved against the
                           current directory to an absolute path before the first gate
  --approved-plan-sha256   required; the checksum the human signed at the plan gate
  --package-record         required when the plan has runtime_enabled = true (scripts/package.sh)
  --release-dir            default operations/releases
  --stable-timeout-minutes default 20
  --resume                 no apply: the plan was applied already (for example before the secret
                           value existed); still requires the same plan file and checksum
  --dry-run                validate flags and local files, print the steps, no AWS/Terraform/git call

Environment: AWS_PROFILE (or --profile).
Run by: deploy-engineer through the gated command, after the human approved the plan checksum
(docs/agents.md section 8 and escalation 4/5).
Rollback: a failed ECS deployment is rolled back by the ECS circuit breaker; otherwise
scripts/rollback.sh --to-release <earlier release record>. Terraform changes are not rolled back
automatically.
Exit: 0 deployed and verified; 1 verification failed; 3 precondition; 4 checksum mismatch;
5 waiting for the secret value; 6 timeout.
EOF
}

PLAN_FILE=""
APPROVED=""
PACKAGE_RECORD=""
RELEASE_DIR="$SC_ROOT/operations/releases"
STABLE_MIN=20
RESUME=0
while [ $# -gt 0 ]; do
  case "$1" in
    --plan-file) PLAN_FILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --approved-plan-sha256) APPROVED="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --package-record) PACKAGE_RECORD="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --release-dir) RELEASE_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --stable-timeout-minutes) STABLE_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --resume) RESUME=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done

[ -f "$PLAN_FILE" ] || die "--plan-file not found: ${PLAN_FILE:-<none>}" 2
# terraform runs with -chdir (tf in lib/common.sh), so every gate and the apply read one absolute path
# (ruling 12, P14-D1). Bash 3.2: no realpath or readlink -f.
PLAN_FILE="$(CDPATH='' cd -- "$(dirname "$PLAN_FILE")" && pwd -P)/$(basename "$PLAN_FILE")"
info "plan file $PLAN_FILE"
is_hex64 "$APPROVED" || die "--approved-plan-sha256 must be 64 lower-case hex" 2
printf '%s' "$STABLE_MIN" | grep -Eq '^[0-9]+$' && [ "$STABLE_MIN" -ge 1 ] && [ "$STABLE_MIN" -le 60 ] ||
  die "--stable-timeout-minutes must be 1 to 60" 2
META="$(dirname "$PLAN_FILE")/plan-meta.json"
[ -f "$META" ] || die "plan-meta.json missing beside the plan file (write plans only with scripts/plan.sh)" 3
need terraform jq git aws

step "approval"
ACTUAL="$(sha256_hex "$PLAN_FILE")"
[ "$ACTUAL" = "$APPROVED" ] || die "plan sha256 $ACTUAL does not equal the approved $APPROVED; nothing applied" 4
[ "$(jq -r .planSha256 "$META")" = "$ACTUAL" ] || die "plan-meta.json names another plan" 4
ok "plan checksum approved: $ACTUAL"

RUNTIME="$(jq -r .runtimeEnabled "$META")"
if [ "$RUNTIME" = true ]; then
  [ -f "$PACKAGE_RECORD" ] || die "--package-record is required for a runtime_enabled plan" 2
  IMAGE_TAG="$(jq -er .image.tag "$PACKAGE_RECORD")" || die "package record has no image.tag" 3
  IMAGE_DIGEST="$(jq -er .image.digest "$PACKAGE_RECORD")" || die "package record has no image.digest" 3
  is_sha40 "$IMAGE_TAG" || die "package image tag is not a Git SHA: $IMAGE_TAG" 3
fi

dry_run_exit \
  "refuse a dirty tree; HEAD must equal plan commit $(jq -r .commit "$META")" \
  "aws sts get-caller-identity; account must be $(jq -r .account "$META"), Region $(jq -r .region "$META")" \
  "$([ "$RESUME" = 1 ] && echo 'skip apply (--resume)' || echo "terraform apply $PLAN_FILE (saved plan, output shown in full)")" \
  "terraform output -json into the release directory" \
  "runtime_enabled=$RUNTIME: secret value check, $([ "$RESUME" = 1 ] && echo 'force a new deployment, ')wait stable (${STABLE_MIN} min), verify digests and runtime run IDs" \
  "write $RELEASE_DIR/release-<stamp>-<sha12>.json"

tf_require_version
require_clean_tree
COMMIT="$(git_head)"
[ "$COMMIT" = "$(jq -r .commit "$META")" ] || die "HEAD $COMMIT is not the plan's commit $(jq -r .commit "$META")" 3
require_profile
[ "$REGION" = "$(jq -r .region "$META")" ] || die "Region $REGION is not the plan's $(jq -r .region "$META")" 3
aws_target
[ "$ACCOUNT" = "$(jq -r .account "$META")" ] || die "account $ACCOUNT is not the plan's $(jq -r .account "$META")" 3

STAMP="$(utc_stamp)"
REL="$RELEASE_DIR/release-$STAMP-${COMMIT:0:12}"
mkdir -p "$RELEASE_DIR"

if [ "$RESUME" = 0 ]; then
  step "apply"
  info "state stays local (ruling 1): back up terraform state outside the repository after this step"
  tf apply -input=false -lock-timeout=60s "$PLAN_FILE" 2>&1 | tee "$REL.apply.txt"
  ok "apply"
else
  step "apply"
  info "SKIPPED (--resume)"
fi

tf_outputs
cp "$TF_OUT" "$REL.outputs.json"
[ "$(tfo '.runtime_enabled.value')" = "$RUNTIME" ] || die "state runtime_enabled differs from the plan's" 3

SERVICES_JSON='[]'
RUN_IDS='{}'
if [ "$RUNTIME" = true ]; then
  CLUSTER="$(tfo '.ecs_cluster_name.value')"
  REPO="$(tfo '.ecr_repository_url.value')"
  SECRET="$(tfo '.api_key_secret_arn.value')"
  step "secret-value"
  CURRENT="$(aws_retry secretsmanager describe-secret --secret-id "$SECRET" \
    --query 'length(VersionIdsToStages || `{}`)' --output text)"
  if [ "$CURRENT" = 0 ]; then
    die "the API key secret has no value yet: the human sets it (infra/terraform/README.md, Human actions 5), then rerun this command with --resume" 5
  fi
  ok "secret has a version (value not read)"

  if [ "$RESUME" = 1 ]; then
    step "force-new-deployment"
    for s in $SC_SERVICES; do
      # Not idempotent (each call starts another deployment): one attempt (code review 1 F3).
      aws_once ecs update-service --cluster "$CLUSTER" --service "$s" --force-new-deployment \
        --query 'service.serviceName' --output text
    done
  fi

  ecs_wait_stable "$CLUSTER" $((STABLE_MIN * 60))
  ecs_verify_image "$CLUSTER" "$REPO:$IMAGE_TAG" "$IMAGE_DIGEST"
  SERVICES_JSON="$ECS_SERVICES_JSON"
  ecs_verify_run_ids "$(jq -c '.log_group_names.value' "$TF_OUT")"
fi

step "record"
jq -n --arg commit "$COMMIT" --arg plan "$ACTUAL" --arg account "$ACCOUNT" --arg region "$REGION" \
  --arg at "$(utc_now)" --argjson runtime "$RUNTIME" --argjson resume "$([ "$RESUME" = 1 ] && echo true || echo false)" \
  --arg tag "${IMAGE_TAG:-}" --arg digest "${IMAGE_DIGEST:-}" --arg pkg "${PACKAGE_RECORD#"$SC_ROOT"/}" \
  --argjson services "$SERVICES_JSON" --argjson runIds "$RUN_IDS" --arg outputs "$(basename "$REL").outputs.json" \
  '{schemaVersion: "release-record-v1", commit: $commit, planSha256: $plan, account: $account, region: $region,
    environment: "course", deployedAt: $at, runtimeEnabled: $runtime, resumed: $resume,
    image: (if $tag == "" then null else {tag: $tag, digest: $digest, packageRecord: $pkg} end),
    services: $services, runtimeRunIds: $runIds, outputsFile: $outputs, verified: true}' >"$REL.json"
printf '==> RELEASE record=%s commit=%s plan=%s\n' "$REL.json" "$COMMIT" "$ACTUAL"
ok "deploy complete; next: scripts/smoke-test.sh"
