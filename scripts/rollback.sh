#!/usr/bin/env bash
# Moves the three ECS services back to the image of a known-good release record, without Terraform
# (spec Phase 14 rollback gate; docs/agents.md section 8).
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/rollback.sh --to-release <operations/releases/release-<stamp>-<sha>.json>
                           [--profile <name>] [--region us-east-2] [--reason "<text>"]
                           [--stable-timeout-minutes 20] [--record-dir <dir>] [--dry-run]
       scripts/rollback.sh --list [--record-dir <dir>]

--list prints the local release and smoke records (no AWS call), so the operator can pick the last
known-good release: one whose smoke record after it says PASS.

Steps:
  1. the record must be a runtime release of this account and Region with an image tag and digest
  2. ECR must still hold <repository>:<tag> with exactly that digest (tags are immutable)
  3. for query-api, graph-evidence-api and worker together (SERVICES.md section 5): copy the
     service's current task definition, change only the container image to <repository>:<tag>,
     register it as a new revision (one attempt each), and point the service at it
  4. wait until the services are stable (bounded), then verify every running task's digest and that
     query-api and the worker log one runtime run ID
  5. write <record-dir>/rollback-<stamp>.json (previous and new task definitions, image, reason)

No terraform apply. Terraform state still names the newer image, so the next plan shows the task
definitions changing back; to keep the rollback, set image_tag in course.tfvars to the release's tag
and go through plan.sh and deploy.sh.

Flags: --record-dir default operations/releases; --dry-run validates the record, prints the steps,
no AWS/Terraform call.
Environment: AWS_PROFILE (or --profile).
Run by: deploy-engineer for its own failing in-flight deployment, the incident-responder for a live
revision (/rollback); each AWS mutation passes the human's permission prompt.
Exit: 0 rolled back and verified; 1 verification failed; 3 precondition; 6 timeout.
EOF
}

TO=""
REASON=""
STABLE_MIN=20
RECORD_DIR="$SC_ROOT/operations/releases"
LIST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --to-release) TO="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --reason) REASON="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --stable-timeout-minutes) STABLE_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --record-dir) RECORD_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --list) LIST=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
need jq

if [ "$LIST" = 1 ]; then
  step "list"
  for f in "$RECORD_DIR"/release-*.json "$RECORD_DIR"/smoke-*.json "$RECORD_DIR"/rollback-*.json; do
    [ -f "$f" ] || continue
    jq -r --arg f "${f#"$SC_ROOT"/}" '[$f, (.deployedAt // .finishedAt // .rolledBackAt),
      (.image.tag // .result // "-"), (.image.digest // .baseUrl // "-")] | join("  ")' "$f"
  done | sort -k2
  exit 0
fi

[ -f "$TO" ] || die "--to-release not found: ${TO:-<none>}" 2
printf '%s' "$STABLE_MIN" | grep -Eq '^[0-9]+$' && [ "$STABLE_MIN" -ge 1 ] && [ "$STABLE_MIN" -le 60 ] ||
  die "--stable-timeout-minutes must be 1 to 60" 2
[ "$(jq -r .schemaVersion "$TO")" = release-record-v1 ] && [ "$(jq -r .runtimeEnabled "$TO")" = true ] ||
  die "$TO is not a runtime release record" 3
TAG="$(jq -er .image.tag "$TO")" || die "release record has no image tag" 3
DIGEST="$(jq -er .image.digest "$TO")" || die "release record has no image digest" 3
is_sha40 "$TAG" || die "release image tag is not a Git SHA: $TAG" 3
need aws terraform

dry_run_exit \
  "aws sts get-caller-identity; account must be $(jq -r .account "$TO"); terraform output (cluster, repository)" \
  "ECR describe-images $TAG must have digest $DIGEST" \
  "register one task definition revision per service with image <repository>:$TAG; update-service query-api, graph-evidence-api, worker" \
  "wait stable (${STABLE_MIN} min); verify digests and runtime run IDs; write $RECORD_DIR/rollback-<stamp>.json"

require_profile
aws_target
[ "$ACCOUNT" = "$(jq -r .account "$TO")" ] && [ "$REGION" = "$(jq -r .region "$TO")" ] ||
  die "the release record is for $(jq -r .account "$TO")/$(jq -r .region "$TO")" 3
tf_outputs
CLUSTER="$(tfo '.ecs_cluster_name.value')"
REPO="$(tfo '.ecr_repository_url.value')"
REPO_NAME="$(tfo '.ecr_repository_name.value')"

step "image"
HAVE="$(aws_retry ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$TAG" \
  --query 'imageDetails[0].imageDigest' --output text)" || die "ECR has no $REPO_NAME:$TAG" 3
[ "$HAVE" = "$DIGEST" ] || die "ECR $REPO_NAME:$TAG has digest $HAVE, the record says $DIGEST" 3
ok "$REPO:$TAG $DIGEST"

step "register-and-update"
CHANGES='[]'
for s in $SC_SERVICES; do
  OLD="$(aws_retry ecs describe-services --cluster "$CLUSTER" --services "$s" --query 'services[0].taskDefinition' --output text)"
  INPUT="$(mktemp "${TMPDIR:-/tmp}/sc-td.XXXXXX")"
  aws_retry ecs describe-task-definition --task-definition "$OLD" --include TAGS --output json |
    jq --arg img "$REPO:$TAG" '.tags as $t | .taskDefinition
      | {family, taskRoleArn, executionRoleArn, networkMode, containerDefinitions, volumes,
         placementConstraints, requiresCompatibilities, cpu, memory, runtimePlatform, ephemeralStorage}
      | with_entries(select(.value != null))
      | .containerDefinitions[0].image = $img
      | if ($t | length) > 0 then .tags = $t else . end' >"$INPUT"
  NEW="$(aws_once ecs register-task-definition --cli-input-json "file://$INPUT" \
    --query 'taskDefinition.taskDefinitionArn' --output text)"
  rm -f "$INPUT"
  aws_retry ecs update-service --cluster "$CLUSTER" --service "$s" --task-definition "$NEW" \
    --query 'service.serviceName' --output text
  info "$s $OLD -> $NEW"
  CHANGES="$(jq -c --arg s "$s" --arg o "$OLD" --arg n "$NEW" '. + [{service: $s, from: $o, to: $n}]' <<<"$CHANGES")"
done

ecs_wait_stable "$CLUSTER" $((STABLE_MIN * 60))
ecs_verify_image "$CLUSTER" "$REPO:$TAG" "$DIGEST"
ecs_verify_run_ids "$(jq -c '.log_group_names.value' "$TF_OUT")"

step "record"
mkdir -p "$RECORD_DIR"
REC="$RECORD_DIR/rollback-$(utc_stamp).json"
jq -n --arg at "$(utc_now)" --arg account "$ACCOUNT" --arg region "$REGION" --arg to "${TO#"$SC_ROOT"/}" \
  --arg tag "$TAG" --arg digest "$DIGEST" --arg reason "$REASON" --argjson changes "$CHANGES" \
  --argjson services "$ECS_SERVICES_JSON" --argjson runIds "$RUN_IDS" \
  '{schemaVersion: "rollback-record-v1", rolledBackAt: $at, account: $account, region: $region,
    toRelease: $to, image: {tag: $tag, digest: $digest}, reason: $reason, changes: $changes,
    services: $services, runtimeRunIds: $runIds, terraformDrift: "task definitions differ from state until image_tag is planned and applied"}' >"$REC"
printf '==> ROLLBACK record=%s image=%s\n' "$REC" "$TAG"
ok "rollback complete; next: scripts/smoke-test.sh"
