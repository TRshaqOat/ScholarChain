#!/usr/bin/env bash
# Gathers the evidence of a run into one versioned directory (spec Phase 13 item 8).
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/archive-evidence.sh --out-dir <new directory> [--plans-dir scratch/plans]
           [--metrics-from <ISO-8601 UTC> --metrics-to <ISO-8601 UTC>] [--s3-prefix reports/evidence/<name>/]
           [--profile <name>] [--region us-east-2] [--dry-run]

Copies into <out-dir> (it must not exist: one directory per archive, never overwritten):
  test-reports/<project>/   sbt JUnit XML from modules/*/target/test-reports and tests/target/test-reports
  plans/<plan>/             plan.sha256, plan-meta.json, summary.md of every plan under --plans-dir
                            (never plan.tfplan, plan.json, or plan.txt: they hold planned values)
  operations/               package, release, smoke and rollback records (image digests), pipeline
                            run and store-load records with their run manifests, plan summaries
  experiments-results/      experiments/results/ (experiment outputs)
  metrics/cloudwatch.json   with --metrics-from/--metrics-to: CloudWatch GetMetricData (60 s) for
                            ECS CPU and memory per service, ALB requests, target 5xx and p90 latency,
                            job queue and DLQ depth, Neptune CPU (runtime outputs only)
  index.json, SHA256SUMS    commit, creation time, sources; a checksum of every file
With --s3-prefix (must start with reports/evidence/ and be empty): uploads the directory to the data
bucket under that prefix.

Without --metrics-* and --s3-prefix no AWS call is made.
--dry-run validates flags, lists the sources found, makes no AWS/Terraform/git call.
Environment: AWS_PROFILE (or --profile) only for metrics or S3.
Run by: the human or deploy-engineer (read-only on the sources; writes only <out-dir> and the prefix).
Rollback: none; delete the new directory to discard an archive.
Exit: 0 archived; 2 usage; 3 the out-dir or S3 prefix already exists.
EOF
}

OUT=""
PLANS="$SC_ROOT/scratch/plans"
FROM=""
TO=""
PREFIX=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir) OUT="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --plans-dir) PLANS="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --metrics-from) FROM="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --metrics-to) TO="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --s3-prefix) PREFIX="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
[ -n "$OUT" ] || die "--out-dir is required" 2
[ ! -e "$OUT" ] || die "$OUT exists; an archive is never overwritten" 3
ISO='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
if [ -n "$FROM$TO" ]; then
  printf '%s' "$FROM" | grep -Eq "$ISO" && printf '%s' "$TO" | grep -Eq "$ISO" || die "--metrics-from and --metrics-to must both be YYYY-MM-DDTHH:MM:SSZ" 2
fi
if [ -n "$PREFIX" ]; then
  case "$PREFIX" in reports/evidence/?*/) ;; *) die "--s3-prefix must look like reports/evidence/<name>/" 2 ;; esac
fi
need jq

REPORTS=""
for d in "$SC_ROOT"/modules/*/target/test-reports "$SC_ROOT"/tests/target/test-reports; do
  [ -d "$d" ] && REPORTS="$REPORTS $d"
done

dry_run_exit \
  "test reports:${REPORTS:- none}" \
  "plans: $PLANS/*/{plan.sha256,plan-meta.json,summary.md}" \
  "operations/ and experiments/results/ trees" \
  "$([ -n "$FROM" ] && echo "CloudWatch GetMetricData $FROM to $TO" || echo "no metrics (no --metrics-from/--metrics-to)")" \
  "$([ -n "$PREFIX" ] && echo "upload to s3://<data bucket>/$PREFIX (must be empty)" || echo "no S3 upload")" \
  "index.json and SHA256SUMS in $OUT"

mkdir -p "$OUT"
step "test-reports"
for d in $REPORTS; do
  p="${d#"$SC_ROOT"/}"
  p="${p%%/target/*}"
  mkdir -p "$OUT/test-reports/${p//\//-}"
  cp -R "$d/." "$OUT/test-reports/${p//\//-}/"
done

step "plans"
for d in "$PLANS"/*/; do
  [ -f "$d/plan.sha256" ] || continue
  n="$(basename "$d")"
  mkdir -p "$OUT/plans/$n"
  for f in plan.sha256 plan-meta.json summary.md; do [ ! -f "$d/$f" ] || cp "$d/$f" "$OUT/plans/$n/"; done
done

step "records"
[ ! -d "$SC_ROOT/operations" ] || cp -R "$SC_ROOT/operations" "$OUT/operations"
[ ! -d "$SC_ROOT/experiments/results" ] || cp -R "$SC_ROOT/experiments/results" "$OUT/experiments-results"

if [ -n "$FROM$PREFIX" ]; then
  require_profile
  aws_target
  tf_outputs
fi

if [ -n "$FROM" ]; then
  step "metrics"
  [ "$(tfo '.runtime_enabled.value')" = true ] || die "runtime metrics need runtime_enabled outputs" 3
  QUERIES="$(jq -c '
    def q($id; $ns; $m; $dims; $stat): {Id: $id, MetricStat: {Metric: {Namespace: $ns, MetricName: $m,
      Dimensions: ($dims | to_entries | map({Name: .key, Value: .value}))}, Period: 60, Stat: $stat}};
    .ecs_cluster_name.value as $c | .alb_arn_suffix.value as $alb
    | (.job_queue_url.value | split("/") | last) as $q | (.job_dlq_url.value | split("/") | last) as $dlq
    | [ (.ecs_service_names.value | to_entries[] | .value as $s | ($s | gsub("-"; "_")) as $i
         | q("cpu_" + $i; "AWS/ECS"; "CPUUtilization"; {ClusterName: $c, ServiceName: $s}; "Average"),
           q("mem_" + $i; "AWS/ECS"; "MemoryUtilization"; {ClusterName: $c, ServiceName: $s}; "Average")),
        q("alb_requests"; "AWS/ApplicationELB"; "RequestCount"; {LoadBalancer: $alb}; "Sum"),
        q("alb_target_5xx"; "AWS/ApplicationELB"; "HTTPCode_Target_5XX_Count"; {LoadBalancer: $alb}; "Sum"),
        q("alb_latency_p90"; "AWS/ApplicationELB"; "TargetResponseTime"; {LoadBalancer: $alb}; "p90"),
        q("queue_depth"; "AWS/SQS"; "ApproximateNumberOfMessagesVisible"; {QueueName: $q}; "Maximum"),
        q("dlq_depth"; "AWS/SQS"; "ApproximateNumberOfMessagesVisible"; {QueueName: $dlq}; "Maximum"),
        q("neptune_cpu"; "AWS/Neptune"; "CPUUtilization"; {DBClusterIdentifier: .neptune_cluster_id.value}; "Average") ]' "$TF_OUT")"
  mkdir -p "$OUT/metrics"
  printf '%s\n' "$QUERIES" >"$OUT/metrics/queries.json"
  aws_retry cloudwatch get-metric-data --start-time "$FROM" --end-time "$TO" \
    --metric-data-queries "file://$OUT/metrics/queries.json" --output json >"$OUT/metrics/cloudwatch.json"
  ok "metrics: $(jq '.MetricDataResults | length' "$OUT/metrics/cloudwatch.json") series"
fi

step "index"
COMMIT="$(git_head 2>/dev/null || echo unknown)"
jq -n --arg commit "$COMMIT" --arg at "$(utc_now)" --arg from "$FROM" --arg to "$TO" --arg prefix "$PREFIX" \
  '{schemaVersion: "evidence-archive-v1", commit: $commit, createdAt: $at,
    metricsWindow: (if $from == "" then null else {from: $from, to: $to} end),
    s3Prefix: (if $prefix == "" then null else $prefix end),
    sources: ["test-reports", "plans", "operations", "experiments-results", "metrics"]}' >"$OUT/index.json"
(cd "$OUT" && find . -type f ! -name SHA256SUMS | LC_ALL=C sort | while IFS= read -r f; do
  printf '%s  %s\n' "$(sha256_hex "$f")" "${f#./}"
done >SHA256SUMS)
ok "$(wc -l <"$OUT/SHA256SUMS" | tr -d ' ') files in $OUT"

if [ -n "$PREFIX" ]; then
  step "upload"
  BUCKET="$(tfo '.data_bucket_name.value')"
  [ "$(aws_retry s3api list-objects-v2 --bucket "$BUCKET" --prefix "$PREFIX" --max-items 1 --query 'length(Contents || `[]`)' --output text)" = 0 ] ||
    die "s3://$BUCKET/$PREFIX is not empty; archives are never overwritten" 3
  aws_retry s3 cp --only-show-errors --recursive "$OUT" "s3://$BUCKET/$PREFIX"
  ok "uploaded to s3://$BUCKET/$PREFIX"
fi
printf '==> ARCHIVE dir=%s\n' "$OUT"
