#!/usr/bin/env bash
# Builds the job jars and the services image for one commit (spec Phase 13 item 2, ruling 3).
# No Docker on the laptop: sbt stages the Docker context, CodeBuild builds and pushes it.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/package.sh [--profile <name>] [--region us-east-2] [--record-dir <dir>]
                          [--build-timeout-minutes 30] [--dry-run]

Steps:
  1. clean tree; commit = git rev-parse HEAD (the image tag and the code version)
  2. sbt packageJobs; sha256 of both assembly jars; the Hadoop jar's Main-Class must be
     scholarchain.hadoop.Pipeline (the EMR step relies on it)
  3. SCHOLARCHAIN_IMAGE_TAG=<commit> sbt services/Docker/stage (no Docker), then zip the staged
     context (Dockerfile at the zip root) and take its sha256
  4. upload, write-once by sha256: the jars to s3://<bucket>/artifacts/jobs/<commit>/ and the
     context to s3://<bucket>/artifacts/codebuild/source/<commit>.zip
  5. if ECR has no image tagged <commit>: start one CodeBuild build of that zip with IMAGE_TAG=<commit>
     (no retry), wait for it (bounded); ECR tags are immutable, so an existing tag is reused
  6. read the image digest and the scan-on-push status from ECR
  7. write <record-dir>/package-<commit>.json: commit, jar sha256 values and S3 keys, context
     sha256, image repository, tag, digest, build ID, scan status

Flags:
  --record-dir             default operations/releases
  --build-timeout-minutes  default 30 (the CodeBuild project's own timeout is also 30)
  --dry-run                validate flags, print the steps, no AWS/Terraform/git/sbt call

Environment: AWS_PROFILE (or --profile). sbt runs with SPARK_LOCAL_IP=127.0.0.1 and -J-Xmx6g.
Run by: the human or deploy-engineer, before deploy.sh (cloud window 1 must be applied: ECR,
CodeBuild, and the bucket come from Terraform). Rollback: none needed; artifacts are additive and
keyed by commit.
Exit: 0 recorded; 1 build or verification failed; 3 precondition; 6 timeout.
EOF
}

RECORD_DIR="$SC_ROOT/operations/releases"
BUILD_MIN=30
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --record-dir) RECORD_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --build-timeout-minutes) BUILD_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
printf '%s' "$BUILD_MIN" | grep -Eq '^[0-9]+$' && [ "$BUILD_MIN" -ge 5 ] && [ "$BUILD_MIN" -le 60 ] ||
  die "--build-timeout-minutes must be 5 to 60" 2
need sbt zip unzip jq git aws terraform

HADOOP_JAR="$SC_ROOT/modules/hadoop/target/scala-3.3.7/hadoop-assembly-0.1.0.jar"
SPARK_JAR="$SC_ROOT/modules/spark/target/scala-3.3.7/sparkAnalytics-assembly-0.1.0.jar"
STAGE="$SC_ROOT/modules/services/target/docker/stage"

dry_run_exit \
  "refuse a dirty tree; commit = HEAD" \
  "sbt -batch packageJobs; sha256 ${HADOOP_JAR#"$SC_ROOT"/} and ${SPARK_JAR#"$SC_ROOT"/}" \
  "SCHOLARCHAIN_IMAGE_TAG=<commit> sbt -batch services/Docker/stage; zip ${STAGE#"$SC_ROOT"/}" \
  "aws sts get-caller-identity; terraform output (bucket, CodeBuild project, ECR)" \
  "upload jars and context write-once under artifacts/" \
  "CodeBuild start-build (once) unless ECR has the tag; wait at most ${BUILD_MIN} min" \
  "ECR describe-images: digest and scan status; write $RECORD_DIR/package-<commit>.json"

require_clean_tree
COMMIT="$(git_head)"
require_profile

step "package-jobs"
sbt_run packageJobs
[ -f "$HADOOP_JAR" ] && [ -f "$SPARK_JAR" ] || die "assembly jars not found after packageJobs" 1
MAIN="$(unzip -p "$HADOOP_JAR" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: //p')"
[ "$MAIN" = scholarchain.hadoop.Pipeline ] || die "Hadoop jar Main-Class is '$MAIN', not scholarchain.hadoop.Pipeline" 1
HADOOP_SHA="$(sha256_hex "$HADOOP_JAR")"
SPARK_SHA="$(sha256_hex "$SPARK_JAR")"
info "hadoop $HADOOP_SHA"
info "spark  $SPARK_SHA"

step "stage-image"
rm -rf "$STAGE"
SCHOLARCHAIN_IMAGE_TAG="$COMMIT" sbt_run services/Docker/stage
for f in Dockerfile 4/opt/docker/bin/query-api 4/opt/docker/bin/graph-evidence-api 4/opt/docker/bin/worker opt/docker/prompts; do
  [ -e "$STAGE/$f" ] || die "staged context lacks $f" 1
done
CONTEXT="$SC_ROOT/modules/services/target/docker/context-$COMMIT.zip"
rm -f "$CONTEXT"
(cd "$STAGE" && zip -qrX "$CONTEXT" .)
CONTEXT_SHA="$(sha256_hex "$CONTEXT")"
info "context $CONTEXT_SHA"

aws_target
tf_outputs
BUCKET="$(tfo '.data_bucket_name.value')"
PROJECT="$(tfo '.codebuild_project_name.value')"
SOURCE_PREFIX="$(tfo '.codebuild_source_prefix.value')"
REPO_NAME="$(tfo '.ecr_repository_name.value')"
REPO_URL="$(tfo '.ecr_repository_url.value')"
JOBS_PREFIX="artifacts/jobs/$COMMIT"

step "upload"
s3_put_if_absent "$HADOOP_JAR" "$BUCKET" "$JOBS_PREFIX/hadoop-assembly.jar"
s3_put_if_absent "$SPARK_JAR" "$BUCKET" "$JOBS_PREFIX/sparkAnalytics-assembly.jar"
s3_put_if_absent "$SC_ROOT/scripts/emr/step.sh" "$BUCKET" "$JOBS_PREFIX/step.sh"
SOURCE_KEY="${SOURCE_PREFIX}$COMMIT.zip"
s3_put_if_absent "$CONTEXT" "$BUCKET" "$SOURCE_KEY"

image_digest() {
  aws_retry ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$COMMIT" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null
}

step "image-build"
BUILD_ID=""
if DIGEST="$(image_digest)"; then
  info "ECR already holds $REPO_NAME:$COMMIT (immutable tag); no build"
else
  BUILD_ID="$(aws_once codebuild start-build --project-name "$PROJECT" \
    --source-type-override S3 --source-location-override "$BUCKET/$SOURCE_KEY" \
    --environment-variables-override "name=IMAGE_TAG,value=$COMMIT,type=PLAINTEXT" \
    --query 'build.id' --output text)"
  printf '==> BUILD id=%s\n' "$BUILD_ID"
  build_done() {
    local status
    status="$(aws_retry codebuild batch-get-builds --ids "$BUILD_ID" --query 'builds[0].buildStatus' --output text)"
    info "build $status"
    case "$status" in
      SUCCEEDED) return 0 ;;
      IN_PROGRESS) return 1 ;;
      *) die "build $BUILD_ID ended $status; logs: log group $(tfo '.log_group_names.value.codebuild')" 1 ;;
    esac
  }
  wait_until "CodeBuild $BUILD_ID" $((BUILD_MIN * 60)) 20 build_done
  DIGEST="$(image_digest)" || die "build succeeded but ECR has no $REPO_NAME:$COMMIT" 1
fi
printf '%s' "$DIGEST" | grep -Eq '^sha256:[0-9a-f]{64}$' || die "unexpected image digest: $DIGEST" 1
SCAN="$(aws_retry ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$COMMIT" \
  --query 'imageDetails[0].[imageScanStatus.status, to_string(imageScanFindingsSummary.findingSeverityCounts)]' --output text || echo "unknown")"
printf '==> IMAGE %s:%s digest=%s\n' "$REPO_URL" "$COMMIT" "$DIGEST"
info "scan (status, severity counts): $SCAN"

step "record"
mkdir -p "$RECORD_DIR"
REC="$RECORD_DIR/package-$COMMIT.json"
jq -n --arg commit "$COMMIT" --arg at "$(utc_now)" --arg account "$ACCOUNT" --arg region "$REGION" \
  --arg bucket "$BUCKET" --arg jp "$JOBS_PREFIX" --arg hs "$HADOOP_SHA" --arg ss "$SPARK_SHA" \
  --arg stepSha "$(sha256_hex "$SC_ROOT/scripts/emr/step.sh")" --arg ctx "$SOURCE_KEY" --arg cs "$CONTEXT_SHA" \
  --arg repo "$REPO_URL" --arg digest "$DIGEST" --arg build "$BUILD_ID" --arg scan "$SCAN" \
  '{schemaVersion: "package-record-v1", commit: $commit, createdAt: $at, account: $account, region: $region,
    jobs: [{name: "hadoop", key: ($jp + "/hadoop-assembly.jar"), sha256: $hs, mainClass: "scholarchain.hadoop.Pipeline"},
           {name: "spark", key: ($jp + "/sparkAnalytics-assembly.jar"), sha256: $ss, mainClass: "scholarchain.spark.PipelineJob"},
           {name: "emr-step", key: ($jp + "/step.sh"), sha256: $stepSha}],
    bucket: $bucket, dockerContext: {key: $ctx, sha256: $cs},
    image: {repository: $repo, tag: $commit, digest: $digest, buildId: (if $build == "" then null else $build end), scan: $scan}}' >"$REC"
printf '==> PACKAGE record=%s\n' "$REC"
ok "package complete"
