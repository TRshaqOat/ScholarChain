#!/usr/bin/env bash
# Terraform plan for the course environment (spec Phase 13 item 3). Never applies.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/plan.sh --runtime-enabled true|false [--var-file <path>] [--profile <name>]
                       [--region us-east-2] [--out-dir <dir>] [--evidence-dir <dir>] [--dry-run]

Runs terraform init (local backend) and terraform plan -out for infra/terraform/envs/course, prints
the complete plan, and writes into <out-dir>/<UTC stamp>-<commit 12>/ (gitignored, under scratch/):
  plan.tfplan        the saved plan (deploy.sh applies only this file, by checksum)
  plan.sha256        its sha256
  plan.txt           the full human-readable plan, exactly as printed
  plan.json          terraform show -json (holds planned values; stays local, mode 600)
  plan-meta.json     commit, account, Region, runtime_enabled, var file and its sha256, counts
  summary.md         secret-free summary: counts, stateful destroys or replacements (first), IAM
                     changes, high-cost resources
and copies summary.md to <evidence-dir>/<UTC stamp>-<commit 12>.md for the phase evidence.

Flags:
  --runtime-enabled  required: false = cloud window 1, true = cloud window 2 (D-13)
  --var-file         default infra/terraform/envs/course/course.tfvars
  --profile          default $AWS_PROFILE (the human's scholarchain-sdk profile)
  --region           default us-east-2 (the only Region the configuration accepts)
  --out-dir          default scratch/plans
  --evidence-dir     default operations/plans
  --dry-run          validate flags and local files, print the steps, no AWS/Terraform/git call

Environment: AWS_PROFILE (or --profile); TF_VAR_alarm_email (required by the configuration; may be
empty for no alarm subscription; never printed by this script).
Run by: the human architect at the plan gate. Never applies; never uses -auto-approve.
Exit: 0 plan written; 2 usage; 3 dirty tree, wrong account or Region; 1 terraform failure.
EOF
}

RUNTIME=""
VAR_FILE="$SC_TF_DIR/course.tfvars"
OUT_DIR="$SC_ROOT/scratch/plans"
EVIDENCE_DIR="$SC_ROOT/operations/plans"
while [ $# -gt 0 ]; do
  case "$1" in
    --runtime-enabled) RUNTIME="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --var-file) VAR_FILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --out-dir) OUT_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done

case "$RUNTIME" in true | false) ;; *) die "--runtime-enabled true|false is required" 2 ;; esac
[ -f "$VAR_FILE" ] || die "var file not found: $VAR_FILE" 2
VAR_FILE="$(cd "$(dirname "$VAR_FILE")" && pwd)/$(basename "$VAR_FILE")"
[ "${TF_VAR_alarm_email+set}" = set ] || die "set TF_VAR_alarm_email (an empty value means no alarm subscription)" 2
need terraform jq git aws

dry_run_exit \
  "refuse a dirty tree; check terraform $SC_TF_VERSION" \
  "aws sts get-caller-identity; print account and Region $REGION" \
  "terraform init -input=false -lockfile=readonly (local backend)" \
  "terraform plan -input=false -var-file=$VAR_FILE -var runtime_enabled=$RUNTIME -var region=$REGION -out=<dir>/plan.tfplan (printed in full)" \
  "terraform show -json; sha256; summary into $OUT_DIR/<stamp>-<sha12>/ and $EVIDENCE_DIR/"

tf_require_version
require_clean_tree
require_profile
aws_target

COMMIT="$(git_head)"
STAMP="$(utc_stamp)"
DIR="$OUT_DIR/$STAMP-${COMMIT:0:12}"
mkdir -p "$DIR" "$EVIDENCE_DIR"
chmod 700 "$DIR"

step "init"
tf init -input=false -lockfile=readonly

step "plan"
# Full output on the terminal and in plan.txt; nothing is filtered.
tf plan -input=false -lock-timeout=60s -var-file="$VAR_FILE" -var "runtime_enabled=$RUNTIME" \
  -var "region=$REGION" -out="$DIR/plan.tfplan" 2>&1 | tee "$DIR/plan.txt"

step "show"
(umask 077 && tf show -json "$DIR/plan.tfplan" >"$DIR/plan.json")
PLAN_SHA="$(sha256_hex "$DIR/plan.tfplan")"
printf '%s  plan.tfplan\n' "$PLAN_SHA" >"$DIR/plan.sha256"

PLAN_ACCOUNT="$(jq -r '.planned_values.outputs.account_id.value // empty' "$DIR/plan.json")"
if [ -z "$PLAN_ACCOUNT" ]; then
  warn "the plan does not show account_id as a known value; the profile account is $ACCOUNT"
else
  [ "$PLAN_ACCOUNT" = "$ACCOUNT" ] || die "plan account $PLAN_ACCOUNT differs from profile account $ACCOUNT" 3
fi

step "summarize"
# Terraform's own count rule: a replacement is one add and one destroy.
COUNTS="$(jq -c '[.resource_changes[] | select(.mode == "managed") | .change.actions] |
  {add: map(select(index("create"))) | length,
   change: map(select(. == ["update"])) | length,
   destroy: map(select(index("delete"))) | length}' "$DIR/plan.json")"
STATEFUL='["aws_s3_bucket","aws_dynamodb_table","aws_neptune_cluster","aws_neptune_cluster_instance",
  "aws_s3vectors_vector_bucket","aws_s3vectors_index","aws_ecr_repository","aws_cloudwatch_log_group",
  "aws_secretsmanager_secret","aws_sqs_queue"]'
STATEFUL_LINES="$(jq -r --argjson s "$STATEFUL" '.resource_changes[] | select(.mode == "managed")
  | select(.type as $t | $s | index($t)) | select(.change.actions | index("delete"))
  | "- \(.change.actions | join("+")) \(.address)"' "$DIR/plan.json")"
IAM_LINES="$(jq -r '.resource_changes[] | select(.mode == "managed") | select(.type | startswith("aws_iam_"))
  | select(.change.actions != ["no-op"]) | "- \(.change.actions | join("+")) \(.address)"' "$DIR/plan.json")"
COST_LINES="$(jq -r '.resource_changes[] | select(.mode == "managed")
  | select(.type | IN("aws_neptune_cluster_instance","aws_ecs_service","aws_lb","aws_codebuild_project",
    "aws_dynamodb_table","aws_s3vectors_index","aws_appautoscaling_target"))
  | select(.change.actions != ["no-op"]) | "- \(.change.actions | join("+")) \(.address)"' "$DIR/plan.json")"

jq -n --arg commit "$COMMIT" --arg account "$ACCOUNT" --arg region "$REGION" --arg runtime "$RUNTIME" \
  --arg varFile "${VAR_FILE#"$SC_ROOT"/}" --arg varSha "$(sha256_hex "$VAR_FILE")" \
  --arg lockSha "$(sha256_hex "$SC_TF_DIR/.terraform.lock.hcl")" --arg planSha "$PLAN_SHA" \
  --arg createdAt "$(utc_now)" --argjson counts "$COUNTS" \
  '{schemaVersion: "plan-meta-v1", commit: $commit, account: $account, region: $region,
    runtimeEnabled: ($runtime == "true"), varFile: $varFile, varFileSha256: $varSha,
    lockFileSha256: $lockSha, planSha256: $planSha, createdAt: $createdAt, counts: $counts}' \
  >"$DIR/plan-meta.json"

{
  printf '# Terraform plan summary\n\n'
  printf -- '- Plan SHA-256: `%s`\n- Commit: `%s`\n- Account: `%s`\n- Region: `%s`\n' "$PLAN_SHA" "$COMMIT" "$ACCOUNT" "$REGION"
  printf -- '- runtime_enabled: `%s`\n- Var file: `%s`\n- Created: %s\n' "$RUNTIME" "${VAR_FILE#"$SC_ROOT"/}" "$(utc_now)"
  printf -- '- Counts: %s\n\n' "$COUNTS"
  printf '## Stateful destroy or replace (needs explicit human sign-off)\n\n%s\n\n' "${STATEFUL_LINES:-none}"
  printf '## IAM changes\n\n%s\n\n' "${IAM_LINES:-none}"
  printf '## High-cost resources\n\n%s\n' "${COST_LINES:-none}"
} >"$DIR/summary.md"
cp "$DIR/summary.md" "$EVIDENCE_DIR/$STAMP-${COMMIT:0:12}.md"

cat "$DIR/summary.md"
printf '==> PLAN file=%s sha256=%s counts=%s\n' "$DIR/plan.tfplan" "$PLAN_SHA" "$COUNTS"
[ -z "$STATEFUL_LINES" ] || warn "the plan destroys or replaces stateful resources (listed above)"
ok "plan written; nothing applied. Approve by checksum, then: scripts/deploy.sh --plan-file $DIR/plan.tfplan --approved-plan-sha256 $PLAN_SHA"
