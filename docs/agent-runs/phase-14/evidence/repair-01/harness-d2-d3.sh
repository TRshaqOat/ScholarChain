#!/bin/bash
# Phase 14 repair-01 harness (P14-D2, P14-D3). Extracts the changed blocks verbatim from
# scripts/package.sh and scripts/run-bounded-pipeline.sh and evaluates them with stubbed AWS CLI
# output. aws, git, terraform and sbt are shadowed by functions that abort, so no real call can happen.
# Writes only under <this directory>/harness-out/. Run: /bin/bash harness-d2-d3.sh <repo root>
set -euo pipefail
ROOT="$1"
OUT="$(cd "$(dirname "$0")" && pwd)/harness-out"
rm -rf "$OUT"
mkdir -p "$OUT"
# shellcheck source=/dev/null
. "$ROOT/scripts/lib/common.sh"
for c in aws git terraform sbt; do eval "$c() { echo 'HARNESS: real $c call blocked' >&2; exit 99; }"; done

block() { awk -v s="$2" -v e="$3" '$0 ~ s {on=1} on {print} on && $0 ~ e {exit}' "$ROOT/$1"; }

echo "################ P14-D2: package.sh scan record ################"
D2_SCAN="$(block scripts/package.sh '^SCAN="\\$\\(aws_retry ecr' '^info "scan: ')"
D2_REC="$(block scripts/package.sh '^step "record"' "^printf '==> PACKAGE")"
printf -- '--- extracted scan block ---\n%s\n--- extracted record block ---\n%s\n' "$D2_SCAN" "$D2_REC"
COMMIT=d33ce8e8d507145cd46fb39bf319e985bc9a428a ACCOUNT=000000000000 REGION=us-east-2 BUCKET=stub-bucket
JOBS_PREFIX="artifacts/jobs/$COMMIT" HADOOP_SHA=stub SPARK_SHA=stub SOURCE_KEY=stub.zip CONTEXT_SHA=stub
REPO_URL=stub.dkr.ecr.us-east-2.amazonaws.com/stub REPO_NAME=stub BUILD_ID=stub-build
DIGEST="sha256:stub"
d2_case() { # name, CLI exit code, CLI stdout (what --output json prints)
  STUB_RC="$2" STUB_OUT="$3"
  aws_retry() { [ -z "$STUB_OUT" ] || printf '%s\n' "$STUB_OUT"; return "$STUB_RC"; }
  echo; echo "=== case $1 (CLI exit $2, stdout: '$3')"
  RECORD_DIR="$OUT/d2-$1"
  ( eval "$D2_SCAN"; eval "$D2_REC" )
  echo "record .image.scan = $(jq -c .image.scan "$OUT/d2-$1/package-$COMMIT.json")"
}
d2_case no-scan-status 0 '{"status": null, "severityCounts": null}'
d2_case no-image-details 0 'null'
d2_case in-progress 0 '{"status": "IN_PROGRESS", "severityCounts": null}'
d2_case complete 0 '{"status": "COMPLETE", "severityCounts": {"CRITICAL": 10, "HIGH": 48, "MEDIUM": 62, "LOW": 18, "UNDEFINED": 6}}'
d2_case complete-no-findings 0 '{"status": "COMPLETE", "severityCounts": null}'
d2_case cli-failure 255 ''
d2_case empty-output 0 ''

echo; echo "################ P14-D3: run-bounded-pipeline.sh create-cluster ################"
D3="$(block scripts/run-bounded-pipeline.sh '^CC_ERR="\\$\\(mktemp' '^rm -f "\\$CC_ERR"$')"
printf -- '--- extracted block ---\n%s\n' "$D3"
FRACTION=25 SC_SNAPSHOT=2026-10-03 ENRICH_SNAPSHOT=2026-11-03 INPUT_RUN=run-7a5abdee2b5848146b12f0eef8ef9d99
RELEASE=emr-spark-8.0.0 ITYPE=m6g.xlarge CORE=2 REDUCERS=2 SHUFFLE=16 LOG_URI=s3://stub/logs/
emr() { echo "stub$1"; }
d3_case() { # name, CLI exit code, CLI stdout, CLI stderr
  STUB_RC="$2" STUB_OUT="$3" STUB_ERR="$4"
  aws_once() { printf '%s\n' "$*" >"$REC/aws-args.txt"; [ -z "$STUB_ERR" ] || printf '%s\n' "$STUB_ERR" >&2; [ -z "$STUB_OUT" ] || printf '%s\n' "$STUB_OUT"; return "$STUB_RC"; }
  echo; echo "=== case $1 (CLI exit $2)"
  REC="$OUT/d3-$1"
  mkdir -p "$REC/manifests" "$REC/logs"
  LINES="$REC/input-hashes.txt"
  printf 'fraction=25\nscratch/datasets/fraction=25/dblp/works.xml 03e790be 16234807\n' >"$LINES"
  set +e
  ( eval "$D3"; echo "(block finished; the script would continue with CLUSTER_ID=$CLUSTER_ID)" ) 2>&1
  rc=$?
  set -e
  echo "subshell exit=$rc"
  echo "applications argument passed to the CLI: $(grep -o -- '--applications [^-]*' "$REC/aws-args.txt")"
  if [ -f "$REC/run.json" ]; then echo "run.json:"; jq . "$REC/run.json"; else echo "run.json: not written"; fi
  echo "leftover sc-create-cluster temp files: $(ls "${TMPDIR:-/tmp}" | grep -c '^sc-create-cluster\.' || true)"
}
d3_case submit-failed 254 '' $'\nAn error occurred (ValidationException) when calling the RunJobFlow operation: Specified application: Hadoop is invalid'
d3_case submit-ok 0 'j-STUBCLUSTER0001' ''
