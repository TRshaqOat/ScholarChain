#!/usr/bin/env bash
# Course-environment cleanup (spec "Cleanup requirements", ruling 6). HUMAN ONLY: no agent runs
# this script (docs/agents.md section 8). Two invocations: the first writes and prints a plan and
# the inventory; the second destroys only with --confirm <sha256 of that plan>.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/cleanup.sh --stage runtime|infra|final [--evidence-archive <location>]
                          [--var-file <path>] [--profile <name>] [--region us-east-2]
                          [--out-dir scratch/plans] [--record-dir operations/cleanup] [--dry-run]
       scripts/cleanup.sh --plan-file <dir>/plan.tfplan --confirm <64 hex> [--profile <name>]

Stages (each keeps the Terraform state, local and backed up by the human outside the repository):
  runtime  end of cloud window 2 (D-13): a plan with runtime_enabled = false. Destroys Neptune, the
           DynamoDB tables, the S3 Vectors bucket and indexes, SQS, the API key secret (it enters its
           7-day recovery window), ECS services, task definitions, the ALB, runtime security groups,
           runtime IAM roles, alarms and dashboard. Keeps the network, data bucket, ECR, EMR roles,
           CodeBuild, and log groups.
  infra    after the evidence is archived: plan -destroy of the network, EMR roles and security
           groups, CodeBuild and its role (and any runtime module left). Keeps the data bucket (grading
           evidence), the ECR repository and its images, and every log group.
  final    only after the instructor approved the evidence archive location, given as
           --evidence-archive: plan -destroy of everything. The data bucket (force_destroy = false)
           and the ECR repository (force_delete = false) are deleted only when already empty; the
           script lists what they still hold and never empties them.
Ephemeral EMR clusters are not Terraform resources: active ones are listed in the inventory, never
terminated here.

First invocation: clean tree, account and Region, inventory-before (Terraform state addresses,
resources tagged Project=ScholarChain, active EMR clusters, ECR images, top-level bucket prefixes),
the plan printed in full, every resource it destroys, every resource it keeps with the reason,
and the plan sha256. Nothing is changed.
Second invocation (--plan-file and --confirm): the sha256 must match, HEAD and the account must be
the plan's; then terraform apply <plan file> (saved plan, no -auto-approve), inventory-after, and
<record-dir>/cleanup-<stamp>-<stage>/record.json listing what remains and why.

Environment: AWS_PROFILE (or --profile); TF_VAR_alarm_email (the configuration requires it; may be empty).
Run by: the human architect only. Approval: the --confirm checksum is the documented human confirmation.
Exit: 0 planned or destroyed; 2 usage; 3 precondition; 4 checksum mismatch; 1 terraform failure.
EOF
}

STAGE=""
ARCHIVE=""
VAR_FILE="$SC_TF_DIR/course.tfvars"
OUT_DIR="$SC_ROOT/scratch/plans"
RECORD_DIR="$SC_ROOT/operations/cleanup"
PLAN_FILE=""
CONFIRM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --evidence-archive) ARCHIVE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --var-file) VAR_FILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --out-dir) OUT_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --record-dir) RECORD_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --plan-file) PLAN_FILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --confirm) CONFIRM="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
need terraform jq git aws

# Destroyed by the infra stage; everything else (data bucket, ECR, log groups) is kept.
INFRA_TARGETS="module.network module.emr module.build.aws_codebuild_project.services_image
module.build.aws_iam_role_policy.codebuild module.build.aws_iam_role.codebuild module.ecs module.iam
module.neptune module.dynamodb module.vectors module.messaging module.apikey"

inventory() { # <file prefix>
  step "inventory $(basename "$1")"
  tf show -json | jq '[.. | objects | select(has("address") and .mode? == "managed") | .address] | sort' >"$1.terraform.json"
  aws_retry resourcegroupstaggingapi get-resources --tag-filters Key=Project,Values=ScholarChain \
    --query 'ResourceTagMappingList[].ResourceARN' --output json >"$1.tagged.json"
  aws_retry emr list-clusters --active --query 'Clusters[].[Id, Name, Status.State]' --output json >"$1.emr-active.json"
  local bucket repo
  bucket="$(jq -r '.data_bucket_name.value // empty' "$TF_OUT")"
  repo="$(jq -r '.ecr_repository_name.value // empty' "$TF_OUT")"
  if [ -n "$repo" ]; then
    aws_retry ecr list-images --repository-name "$repo" --query 'imageIds[].imageTag' --output json >"$1.ecr-images.json" || echo '[]' >"$1.ecr-images.json"
  fi
  if [ -n "$bucket" ]; then
    aws_retry s3api list-objects-v2 --bucket "$bucket" --delimiter / --query 'CommonPrefixes[].Prefix' --output json >"$1.bucket-prefixes.json" || echo '[]' >"$1.bucket-prefixes.json"
  fi
  info "terraform: $(jq length "$1.terraform.json") resources; tagged: $(jq length "$1.tagged.json"); active EMR: $(jq length "$1.emr-active.json")"
  [ "$(jq length "$1.emr-active.json")" = 0 ] || warn "active EMR clusters (not Terraform; terminate them by hand if they are stuck): $(jq -c . "$1.emr-active.json")"
}

retained_reason() {
  case "$1" in
    module.data.*) echo "data bucket: grading evidence until the instructor approves the archive (force_destroy=false)" ;;
    module.build.aws_ecr_*) echo "ECR: image history for reproduction (force_delete=false)" ;;
    *aws_cloudwatch_log_group*) echo "log groups: logs kept as evidence (14-day retention)" ;;
    module.foundation.*) echo "data source only" ;;
    *) echo "kept by this stage" ;;
  esac
}

# ---- second invocation: destroy with the confirmed plan -----------------------------------
if [ -n "$CONFIRM$PLAN_FILE" ]; then
  [ -f "$PLAN_FILE" ] || die "--plan-file not found" 2
  is_hex64 "$CONFIRM" || die "--confirm must be the 64-hex sha256 printed by the first invocation" 2
  META="$(dirname "$PLAN_FILE")/cleanup-meta.json"
  [ -f "$META" ] || die "cleanup-meta.json missing beside the plan (write cleanup plans only with this script)" 3
  ACTUAL="$(sha256_hex "$PLAN_FILE")"
  [ "$ACTUAL" = "$CONFIRM" ] && [ "$(jq -r .planSha256 "$META")" = "$ACTUAL" ] ||
    die "plan sha256 $ACTUAL does not equal --confirm $CONFIRM; nothing destroyed" 4
  STAGE="$(jq -r .stage "$META")"
  dry_run_exit "verify HEAD, account and Region against $META" "terraform apply $PLAN_FILE (stage $STAGE)" "inventory-after; record in $RECORD_DIR"
  tf_require_version
  require_clean_tree
  [ "$(git_head)" = "$(jq -r .commit "$META")" ] || die "HEAD is not the plan's commit" 3
  require_profile
  aws_target
  [ "$ACCOUNT" = "$(jq -r .account "$META")" ] || die "account $ACCOUNT is not the plan's" 3
  tf_outputs
  REC="$RECORD_DIR/cleanup-$(utc_stamp)-$STAGE"
  mkdir -p "$REC"
  inventory "$REC/before"
  step "destroy"
  info "the state file stays; back it up outside the repository after this step"
  tf apply -input=false -lock-timeout=60s "$PLAN_FILE" 2>&1 | tee "$REC/apply.txt"
  tf output -json >"$TF_OUT" || echo '{}' >"$TF_OUT"
  inventory "$REC/after"
  jq -r '.[]' "$REC/after.terraform.json" | while IFS= read -r a; do printf '%s\t%s\n' "$a" "$(retained_reason "$a")"; done >"$REC/retained.tsv"
  jq -n --arg stage "$STAGE" --arg plan "$ACTUAL" --arg at "$(utc_now)" --arg account "$ACCOUNT" --arg region "$REGION" \
    --arg commit "$(git_head)" --arg archive "$(jq -r '.evidenceArchive // ""' "$META")" --rawfile retained "$REC/retained.tsv" \
    '{schemaVersion: "cleanup-record-v1", stage: $stage, planSha256: $plan, finishedAt: $at, account: $account,
      region: $region, commit: $commit, evidenceArchive: (if $archive == "" then null else $archive end),
      retained: ($retained | split("\n") | map(select(. != "") | split("\t") | {address: .[0], reason: .[1]})),
      inventories: ["before.*.json", "after.*.json"], stateKept: true}' >"$REC/record.json"
  cat "$REC/retained.tsv"
  printf '==> CLEANUP stage=%s record=%s\n' "$STAGE" "$REC/record.json"
  ok "cleanup $STAGE applied"
  exit 0
fi

# ---- first invocation: inventory and plan, nothing changed --------------------------------
case "$STAGE" in
  runtime | infra) ;;
  final) [ -n "$ARCHIVE" ] || die "--stage final needs --evidence-archive <instructor-approved location>" 2 ;;
  *) die "--stage runtime|infra|final is required" 2 ;;
esac
[ -f "$VAR_FILE" ] || die "var file not found: $VAR_FILE" 2
VAR_FILE="$(cd "$(dirname "$VAR_FILE")" && pwd)/$(basename "$VAR_FILE")"
[ "${TF_VAR_alarm_email+set}" = set ] || die "set TF_VAR_alarm_email (an empty value is fine)" 2
case "$STAGE" in
  runtime) PLAN_ARGS="-var runtime_enabled=false" ;;
  infra) PLAN_ARGS="-destroy$(for t in $INFRA_TARGETS; do printf ' -target=%s' "$t"; done)" ;;
  final) PLAN_ARGS="-destroy" ;;
esac

dry_run_exit \
  "refuse a dirty tree; account and Region; terraform outputs; inventory-before" \
  "terraform plan -input=false -var-file=$VAR_FILE -var region=$REGION $PLAN_ARGS -out=<dir>/plan.tfplan (printed in full)" \
  "list every resource destroyed and every resource kept with its reason; print the sha256" \
  "nothing is destroyed until: scripts/cleanup.sh --plan-file <dir>/plan.tfplan --confirm <sha256>"

tf_require_version
require_clean_tree
require_profile
aws_target
COMMIT="$(git_head)"
tf init -input=false -lockfile=readonly
tf_outputs
DIR="$OUT_DIR/cleanup-$(utc_stamp)-$STAGE"
mkdir -p "$DIR"
chmod 700 "$DIR"
inventory "$DIR/inventory-before"

step "plan $STAGE"
# shellcheck disable=SC2086
tf plan -input=false -lock-timeout=60s -var-file="$VAR_FILE" -var "region=$REGION" $PLAN_ARGS \
  -out="$DIR/plan.tfplan" 2>&1 | tee "$DIR/plan.txt"
(umask 077 && tf show -json "$DIR/plan.tfplan" >"$DIR/plan.json")
SHA="$(sha256_hex "$DIR/plan.tfplan")"

step "resources destroyed"
jq -r '.resource_changes[] | select(.mode == "managed") | select(.change.actions | index("delete"))
  | "- \(.change.actions | join("+")) \(.address)"' "$DIR/plan.json" | tee "$DIR/destroyed.txt"
step "resources kept"
jq -r '[.resource_changes[] | select(.mode == "managed") | select(.change.actions | index("delete")) | .address] as $d
  | .prior_state.values // {} | [.. | objects | select(has("address") and .mode? == "managed") | .address]
  | map(select(. as $a | $d | index($a) | not)) | .[]' "$DIR/plan.json" |
  while IFS= read -r a; do printf -- '- %s: %s\n' "$a" "$(retained_reason "$a")"; done | tee "$DIR/kept.txt"
if [ "$STAGE" = final ]; then
  warn "the data bucket and the ECR repository are deleted only if empty: prefixes $(jq -c . "$DIR/inventory-before.bucket-prefixes.json" 2>/dev/null), images $(jq length "$DIR/inventory-before.ecr-images.json" 2>/dev/null)"
fi

jq -n --arg stage "$STAGE" --arg commit "$COMMIT" --arg account "$ACCOUNT" --arg region "$REGION" \
  --arg sha "$SHA" --arg archive "$ARCHIVE" --arg at "$(utc_now)" \
  '{schemaVersion: "cleanup-meta-v1", stage: $stage, commit: $commit, account: $account, region: $region,
    planSha256: $sha, createdAt: $at, evidenceArchive: (if $archive == "" then null else $archive end)}' >"$DIR/cleanup-meta.json"
printf '==> CLEANUP-PLAN stage=%s file=%s sha256=%s destroys=%s\n' "$STAGE" "$DIR/plan.tfplan" "$SHA" "$(grep -c . "$DIR/destroyed.txt" || true)"
ok "nothing destroyed. To proceed, the human runs: scripts/cleanup.sh --plan-file $DIR/plan.tfplan --confirm $SHA"
