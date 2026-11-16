#!/usr/bin/env bash
# One bounded Hadoop + Spark pipeline run on an ephemeral EMR cluster (spec Phase 13 item 6,
# ruling 2, D-13). The cluster is never a Terraform resource; it terminates itself after its steps.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/run-bounded-pipeline.sh --fraction 25|50|100 --package-record <package-<sha>.json>
           [--profile <name>] [--region us-east-2] [--reducers 2] [--shuffle-partitions 16]
           [--hadoop-timeout-minutes 90] [--spark-timeout-minutes 150] [--record-dir <dir>] [--dry-run]

Inputs (DBLP snapshot 2026-10-03; OpenAlex, Crossref, ORCID snapshot 2026-11-03):
  --fraction 100  scratch/datasets/dblp/snapshot=2026-10-03/full/works.xml,
                  scratch/datasets/{openalex,crossref}/full/works.jsonl,
                  scratch/datasets/orcid/full/records.jsonl.gz
  --fraction 25|50 scratch/datasets/fraction=<p>/{dblp/works.xml, openalex/works.jsonl,
                  crossref/works.jsonl, orcid/records.jsonl.gz}, written by
                  datasets/scripts/make_fraction.sh (rule fraction-v1; exit 3 if one is missing)
  DTD             scratch/datasets/dblp/snapshot=2026-10-03/raw/dblp.dtd
  Every resolved input path is printed with its size and sha256 (also in --dry-run).

Steps:
  1. clean tree; HEAD = the package record's commit; local jars and scripts/emr/step.sh hash to it
  2. account and Region; terraform outputs (bucket, the emr object); ruling 2 bounds re-checked:
     release emr-spark-8.0.0, 1 primary, 1 to 2 core, Graviton type, on-demand
  3. write-once uploads (skipped when present with the same sha256): inputs to
     raw/<dataset>/snapshot=<d>/run=<input run>/ (input run = hash of the fraction and file hashes),
     jars and step.sh to artifacts/jobs/<commit>/
  4. one create-cluster call (no retry), applications Spark only, auto-terminate, three steps:
     classpath-probe (CONTINUE), hadoop-pipeline J01..J10 (TERMINATE_CLUSTER),
     spark-pipeline jobs 1..11 (TERMINATE_CLUSTER); each job step has a timeout on the node
  5. wait for TERMINATED (bounded); collect step timings, instance hours, the new Hadoop and Spark
     run directories and their _manifest.json, and the step logs (classpath evidence)
  6. read-only describe-security-groups of both EMR-managed groups: any 0.0.0.0/0 or ::/0 ingress
     (which Terraform cannot see, it ignores their rule drift) is warned about and recorded
  7. write <record-dir>/<stamp>-fraction<p>/run.json plus the submitted steps, cluster description,
     security groups, manifests, and logs

Flags: --record-dir default operations/pipeline-runs; --dry-run validates flags and local inputs,
prints the steps, makes no AWS/Terraform/git call.
Environment: AWS_PROFILE (or --profile).
Run by: deploy-engineer (/run-pipeline) or the human, after cloud window 1 is applied and
scripts/package.sh recorded the jars. Rollback: nothing to roll back; outputs are new write-once
run directories, and the cluster terminates itself (a hung step is killed by its timeout).
Exit: 0 all steps completed and manifests found; 1 a step or check failed (cluster ID recorded),
or create-cluster failed (run.json records the CLI exit code and error under "failure", clusterId null);
3 precondition; 6 timeout (the cluster ID is printed; the human may terminate it).
EOF
}

FRACTION=""
PACKAGE_RECORD=""
REDUCERS=2
SHUFFLE=16
HADOOP_MIN=90
SPARK_MIN=150
RECORD_DIR="$SC_ROOT/operations/pipeline-runs"
ENRICH_SNAPSHOT="2026-11-03"
while [ $# -gt 0 ]; do
  case "$1" in
    --fraction) FRACTION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --package-record) PACKAGE_RECORD="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --profile) PROFILE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --region) REGION="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --reducers) REDUCERS="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --shuffle-partitions) SHUFFLE="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --hadoop-timeout-minutes) HADOOP_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --spark-timeout-minutes) SPARK_MIN="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --record-dir) RECORD_DIR="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done

case "$FRACTION" in 25 | 50 | 100) ;; *) die "--fraction 25|50|100 is required" 2 ;; esac
[ -f "$PACKAGE_RECORD" ] || die "--package-record not found: ${PACKAGE_RECORD:-<none>}" 2
in_range() { printf '%s' "$2" | grep -Eq '^[0-9]+$' && [ "$2" -ge "$3" ] && [ "$2" -le "$4" ] || die "$1 must be $3 to $4" 2; }
in_range --reducers "$REDUCERS" 1 16
in_range --shuffle-partitions "$SHUFFLE" 2 200
in_range --hadoop-timeout-minutes "$HADOOP_MIN" 10 240
in_range --spark-timeout-minutes "$SPARK_MIN" 10 240
need jq git aws terraform

DS="$SC_ROOT/scratch/datasets"
DTD="$DS/dblp/snapshot=$SC_SNAPSHOT/raw/dblp.dtd"
if [ "$FRACTION" = 100 ]; then
  DBLP="$DS/dblp/snapshot=$SC_SNAPSHOT/full/works.xml"
  OPENALEX="$DS/openalex/full/works.jsonl"
  CROSSREF="$DS/crossref/full/works.jsonl"
  ORCID="$DS/orcid/full/records.jsonl.gz"
else
  F="$DS/fraction=$FRACTION"
  DBLP="$F/dblp/works.xml"
  OPENALEX="$F/openalex/works.jsonl"
  CROSSREF="$F/crossref/works.jsonl"
  ORCID="$F/orcid/records.jsonl.gz"
fi
for f in "$DBLP" "$DTD" "$OPENALEX" "$CROSSREF" "$ORCID"; do
  [ -f "$f" ] || die "input missing: ${f#"$SC_ROOT"/}$([ "$FRACTION" != 100 ] && echo ' (write it with: bash datasets/scripts/make_fraction.sh)')" 3
done

# "<path> <sha256> <bytes>" per input, after a fraction line: printed here (dry-run too) and hashed
# into the input run ID in step upload-inputs.
step "inputs"
INPUT_LINES="$(
  printf 'fraction=%s\n' "$FRACTION"
  for f in "$DBLP" "$DTD" "$OPENALEX" "$CROSSREF" "$ORCID"; do
    printf '%s %s %s\n' "${f#"$SC_ROOT"/}" "$(sha256_hex "$f")" "$(wc -c <"$f" | tr -d ' ')"
  done
)"
printf '%s\n' "$INPUT_LINES" | sed 1d | while read -r p s b; do info "input $p bytes=$b sha256=$s"; done

COMMIT="$(jq -er .commit "$PACKAGE_RECORD")" || die "package record has no commit" 3
is_sha40 "$COMMIT" || die "package record commit is not a Git SHA" 3
HADOOP_JAR="$SC_ROOT/modules/hadoop/target/scala-3.3.7/hadoop-assembly-0.1.0.jar"
SPARK_JAR="$SC_ROOT/modules/spark/target/scala-3.3.7/sparkAnalytics-assembly-0.1.0.jar"
STEP_SH="$SC_ROOT/scripts/emr/step.sh"

dry_run_exit \
  "refuse a dirty tree; HEAD must be $COMMIT; jar and step.sh sha256 must equal the package record" \
  "aws sts get-caller-identity; terraform output (bucket, emr); bounds check" \
  "write-once upload of 5 inputs (fraction $FRACTION) under raw/, jars under artifacts/jobs/$COMMIT/" \
  "aws emr create-cluster (once): emr-spark-8.0.0, --applications Name=Spark, 1 primary + core per the emr output, auto-terminate; steps classpath-probe, hadoop-pipeline (${HADOOP_MIN} min), spark-pipeline (${SPARK_MIN} min, shuffle $SHUFFLE, reducers $REDUCERS)" \
  "poll describe-cluster until TERMINATED; collect steps, instance hours, run manifests, step logs" \
  "describe-security-groups (read-only) of both EMR groups; warn about and record any 0.0.0.0/0 or ::/0 ingress" \
  "write $RECORD_DIR/<stamp>-fraction$FRACTION/run.json"

require_clean_tree
[ "$(git_head)" = "$COMMIT" ] || die "HEAD $(git_head) is not the package record's commit $COMMIT" 3
check_sha() {
  [ "$(sha256_hex "$2")" = "$(jq -r --arg n "$1" '.jobs[] | select(.name == $n) | .sha256' "$PACKAGE_RECORD")" ] ||
    die "$2 does not hash to the package record's $1 sha256 (rerun scripts/package.sh)" 3
}
check_sha hadoop "$HADOOP_JAR"
check_sha spark "$SPARK_JAR"
check_sha emr-step "$STEP_SH"

require_profile
aws_target
tf_outputs
BUCKET="$(tfo '.data_bucket_name.value')"
EMR="$(jq -c '.emr.value' "$TF_OUT")"
emr() { jq -er "$1" <<<"$EMR"; }
RELEASE="$(emr .release_label)"
ITYPE="$(emr .instance_type)"
PRIMARY="$(emr .primary_instance_count)"
CORE="$(emr .core_instance_count)"
LOG_URI="$(emr .log_uri)"

step "bounds"
[ "$RELEASE" = emr-spark-8.0.0 ] || die "release $RELEASE is not emr-spark-8.0.0 (D-01)" 3
[ "$PRIMARY" = 1 ] && [ "$CORE" -ge 1 ] && [ "$CORE" -le 2 ] || die "ruling 2: 1 primary and 1 to 2 core; got $PRIMARY and $CORE" 3
printf '%s' "$ITYPE" | grep -Eq '^[a-z][0-9]+g[a-z]*\.[a-z0-9]+$' || die "$ITYPE is not a Graviton type" 3
ok "bounds: $RELEASE, $PRIMARY primary + $CORE core $ITYPE, on-demand"

STAMP="$(utc_stamp)"
REC="$RECORD_DIR/$STAMP-fraction$FRACTION"
mkdir -p "$REC/manifests" "$REC/logs"

step "upload-inputs"
LINES="$REC/input-hashes.txt"
printf '%s\n' "$INPUT_LINES" >"$LINES"
INPUT_RUN="run-$(sha256_hex "$LINES" | cut -c1-32)"
info "input run $INPUT_RUN"
k_dblp="raw/dblp/snapshot=$SC_SNAPSHOT/run=$INPUT_RUN/works.xml"
k_dtd="raw/dblp/snapshot=$SC_SNAPSHOT/run=$INPUT_RUN/dblp.dtd"
k_oa="raw/openalex/snapshot=$ENRICH_SNAPSHOT/run=$INPUT_RUN/works.jsonl"
k_cr="raw/crossref/snapshot=$ENRICH_SNAPSHOT/run=$INPUT_RUN/works.jsonl"
k_or="raw/orcid/snapshot=$ENRICH_SNAPSHOT/run=$INPUT_RUN/records.jsonl.gz"
s3_put_if_absent "$DBLP" "$BUCKET" "$k_dblp"
s3_put_if_absent "$DTD" "$BUCKET" "$k_dtd"
s3_put_if_absent "$OPENALEX" "$BUCKET" "$k_oa"
s3_put_if_absent "$CROSSREF" "$BUCKET" "$k_cr"
s3_put_if_absent "$ORCID" "$BUCKET" "$k_or"

step "upload-jobs"
JP="artifacts/jobs/$COMMIT"
s3_put_if_absent "$HADOOP_JAR" "$BUCKET" "$JP/hadoop-assembly.jar"
s3_put_if_absent "$SPARK_JAR" "$BUCKET" "$JP/sparkAnalytics-assembly.jar"
s3_put_if_absent "$STEP_SH" "$BUCKET" "$JP/step.sh"

list_runs() {
  { aws_retry s3 ls "s3://$BUCKET/$1" 2>/dev/null || true; } | awk '$1 == "PRE" {sub("/$", "", $2); print $2}'
}
HADOOP_ROOT="curated/hadoop/snapshot=$SC_SNAPSHOT/"
SPARK_ROOT="curated/spark/snapshot=$SC_SNAPSHOT/"
list_runs "$HADOOP_ROOT" >"$REC/hadoop-runs-before.txt"
list_runs "$SPARK_ROOT" >"$REC/spark-runs-before.txt"
EARLIER="$(paste -sd, "$REC/hadoop-runs-before.txt")"

step "submit"
S3A="s3a://$BUCKET"
BOOT="set -euo pipefail; aws --cli-connect-timeout 10 --cli-read-timeout 120 s3 cp --only-show-errors s3://$BUCKET/$JP/step.sh /tmp/scholarchain-step.sh; exec bash /tmp/scholarchain-step.sh \"\$@\""
jq -n --arg boot "$BOOT" --arg hjar "s3://$BUCKET/$JP/hadoop-assembly.jar" --arg sjar "s3://$BUCKET/$JP/sparkAnalytics-assembly.jar" \
  --arg ht "$((HADOOP_MIN * 60))" --arg st "$((SPARK_MIN * 60))" --arg bucket "$BUCKET" --arg snap "$SC_SNAPSHOT" \
  --arg earlier "$EARLIER" --arg commit "$COMMIT" --arg reducers "$REDUCERS" --arg shuffle "$SHUFFLE" \
  --arg dblp "$S3A/$k_dblp" --arg dtd "$S3A/$k_dtd" --arg oa "$S3A/$k_oa" --arg cr "$S3A/$k_cr" --arg or "$S3A/$k_or" \
  --arg es "$ENRICH_SNAPSHOT" --arg hout "$S3A/curated/hadoop" --arg sout "$S3A/curated/spark" \
  '[{Type: "CUSTOM_JAR", Name: "classpath-probe", ActionOnFailure: "CONTINUE", Jar: "command-runner.jar",
     Args: ["bash", "-c", $boot, "step", "classpath"]},
    {Type: "CUSTOM_JAR", Name: "hadoop-pipeline", ActionOnFailure: "TERMINATE_CLUSTER", Jar: "command-runner.jar",
     Args: ["bash", "-c", $boot, "step", "hadoop", $ht, $hjar, "--out", $hout, "--code-version", $commit,
            "--dblp", $dblp, "--dblp-dtd", $dtd, "--dblp-snapshot", $snap, "--openalex", $oa, "--openalex-snapshot", $es,
            "--crossref", $cr, "--crossref-snapshot", $es, "--orcid", $or, "--orcid-snapshot", $es, "--reducers", $reducers]},
    {Type: "CUSTOM_JAR", Name: "spark-pipeline", ActionOnFailure: "TERMINATE_CLUSTER", Jar: "command-runner.jar",
     Args: ["bash", "-c", $boot, "step", "spark", $st, $sjar, $bucket, $snap, $earlier, "--out", $sout,
            "--checkpoint-dir", "hdfs:///scholarchain/spark/checkpoints", "--code-version", $commit,
            "--shuffle-partitions", $shuffle]}]' >"$REC/steps.json"
jq -n --arg t "$ITYPE" --argjson c "$CORE" \
  '[{InstanceGroupType: "MASTER", InstanceCount: 1, InstanceType: $t},
    {InstanceGroupType: "CORE", InstanceCount: $c, InstanceType: $t}]' >"$REC/instance-groups.json"

# emr-spark-8.0.0 offers no Hadoop application (ruling 12, P14-D4): request Spark only.
# A failed call is recorded in run.json and ends with exit 1, never the CLI's raw code (P14-D3).
CC_ERR="$(mktemp "${TMPDIR:-/tmp}/sc-create-cluster.XXXXXX")"
CC_RC=0
CLUSTER_ID="$(aws_once emr create-cluster --name "scholarchain-course-pipeline-f$FRACTION-${COMMIT:0:12}" \
  --release-label "$RELEASE" --applications Name=Spark \
  --service-role "$(emr .service_role)" \
  --ec2-attributes "InstanceProfile=$(emr .instance_profile),SubnetId=$(emr .subnet_id),EmrManagedMasterSecurityGroup=$(emr .primary_security_group_id),EmrManagedSlaveSecurityGroup=$(emr .core_security_group_id)" \
  --instance-groups "file://$REC/instance-groups.json" --steps "file://$REC/steps.json" \
  --log-uri "$LOG_URI" --auto-terminate \
  --tags for-use-with-amazon-emr-managed-policies=true Project=ScholarChain Environment=course Course=CS441 \
  Owner=scholarchain-human-architect Expiration=2026-12-15 ManagedBy=scripts/run-bounded-pipeline.sh \
  --query ClusterId --output text 2>"$CC_ERR")" || CC_RC=$?
cat "$CC_ERR" >&2
if [ "$CC_RC" != 0 ]; then
  jq -n --arg commit "$COMMIT" --arg fraction "$FRACTION" --arg snap "$SC_SNAPSHOT" --arg es "$ENRICH_SNAPSHOT" \
    --arg inputRun "$INPUT_RUN" --arg account "$ACCOUNT" --arg region "$REGION" --arg release "$RELEASE" \
    --arg itype "$ITYPE" --argjson core "$CORE" --arg reducers "$REDUCERS" --arg shuffle "$SHUFFLE" \
    --rawfile inputs "$LINES" --rawfile error "$CC_ERR" --argjson rc "$CC_RC" \
    '{schemaVersion: "pipeline-run-record-v1", commit: $commit, codeVersion: $commit, fraction: ($fraction | tonumber),
      snapshots: {dblp: $snap, openalex: $es, crossref: $es, orcid: $es}, inputRun: $inputRun,
      inputs: ($inputs | split("\n") | map(select(test(" ")) | split(" ") | {path: .[0], sha256: .[1], bytes: (.[2] | tonumber)})),
      account: $account, region: $region,
      configuration: {releaseLabel: $release, instanceType: $itype, primary: 1, core: $core, market: "ON_DEMAND",
                      reducers: ($reducers | tonumber), shufflePartitions: ($shuffle | tonumber)},
      clusterId: null, finalState: null, stateChangeReason: null, normalizedInstanceHours: null, steps: [],
      outputs: {hadoopRuns: [], sparkRuns: []}, emrSecurityGroups: {checked: false, openIngress: [], file: null},
      logs: "logs/", manifests: "manifests/",
      failure: {step: "submit", command: "aws emr create-cluster", cliExitCode: $rc, error: ($error | gsub("^\\s+|\\s+$"; ""))}}' >"$REC/run.json"
  rm -f "$CC_ERR"
  die "create-cluster failed (AWS CLI exit $CC_RC), recorded in $REC/run.json; no cluster ID was returned: check list-clusters before any rerun" 1
fi
rm -f "$CC_ERR"
printf '==> CLUSTER id=%s\n' "$CLUSTER_ID"
printf '%s\n' "$CLUSTER_ID" >"$REC/cluster-id.txt"

terminated() {
  local s
  s="$(aws_retry emr describe-cluster --cluster-id "$CLUSTER_ID" --query 'Cluster.Status.State' --output text)"
  info "$(utc_now) cluster $s"
  case "$s" in TERMINATED | TERMINATED_WITH_ERRORS) return 0 ;; *) return 1 ;; esac
}
step "wait"
wait_until "cluster $CLUSTER_ID to terminate (terminate it by hand only if it is stuck)" \
  $(((30 + HADOOP_MIN + SPARK_MIN + 15) * 60)) 60 terminated

step "collect"
aws_retry emr describe-cluster --cluster-id "$CLUSTER_ID" >"$REC/cluster.json"
aws_retry emr list-steps --cluster-id "$CLUSTER_ID" >"$REC/steps-result.json"
list_runs "$HADOOP_ROOT" >"$REC/hadoop-runs-after.txt"
list_runs "$SPARK_ROOT" >"$REC/spark-runs-after.txt"
NEW_HADOOP="$(comm -13 <(sort "$REC/hadoop-runs-before.txt") <(sort "$REC/hadoop-runs-after.txt"))"
NEW_SPARK="$(comm -13 <(sort "$REC/spark-runs-before.txt") <(sort "$REC/spark-runs-after.txt"))"
for r in $NEW_HADOOP; do aws_retry s3 cp --only-show-errors "s3://$BUCKET/$HADOOP_ROOT$r/_manifest.json" "$REC/manifests/hadoop-$r.json" || warn "no manifest in $HADOOP_ROOT$r"; done
for r in $NEW_SPARK; do aws_retry s3 cp --only-show-errors "s3://$BUCKET/$SPARK_ROOT$r/_manifest.json" "$REC/manifests/spark-$r.json" || warn "no manifest in $SPARK_ROOT$r"; done

# EMR pushes step logs to S3 a few minutes after termination; wait at most 10 minutes.
LOG_PREFIX="${LOG_URI%/}/$CLUSTER_ID/steps/"
logs_ready() { [ "$(aws_retry s3 ls --recursive "$LOG_PREFIX" 2>/dev/null | grep -c 'stdout.gz' || true)" -ge 3 ]; }
if (wait_until "step logs under $LOG_PREFIX" 600 30 logs_ready); then :; else warn "step logs incomplete; fetched what exists"; fi
aws_retry s3 cp --only-show-errors --recursive "$LOG_PREFIX" "$REC/logs/" || warn "no step logs fetched"

# Terraform ignores ingress drift on the EMR-managed groups (code review 1 F7c), so check read-only
# what EMR added. Warns and records; never changes a rule (the fix is a human action).
step "emr-security-groups"
OPEN_INGRESS_JQ='[.SecurityGroups[] | .GroupId as $g | .IpPermissions[]? | . as $p
  | ([.IpRanges[]? | select(.CidrIp == "0.0.0.0/0") | .CidrIp] + [.Ipv6Ranges[]? | select(.CidrIpv6 == "::/0") | .CidrIpv6])[]
  | {groupId: $g, protocol: $p.IpProtocol, fromPort: ($p.FromPort // "all"), toPort: ($p.ToPort // "all"), source: .}]'
SG_CHECKED=false
SG_OPEN='[]'
if aws_retry ec2 describe-security-groups --group-ids "$(emr .primary_security_group_id)" "$(emr .core_security_group_id)" \
  >"$REC/security-groups.json"; then
  SG_CHECKED=true
  SG_OPEN="$(jq -c "$OPEN_INGRESS_JQ" "$REC/security-groups.json")"
  if [ "$SG_OPEN" = '[]' ]; then
    ok "no 0.0.0.0/0 or ::/0 ingress on the EMR groups"
  else
    jq -r '.[] | "\(.groupId) \(.protocol) \(.fromPort)-\(.toPort) from \(.source)"' <<<"$SG_OPEN" |
      while IFS= read -r l; do warn "EMR GROUP OPEN TO THE INTERNET: $l (recorded; the human reviews and removes it)"; done
  fi
else
  warn "describe-security-groups failed: the EMR ingress check is UNVERIFIED for this run"
fi

STATE="$(jq -r .Cluster.Status.State "$REC/cluster.json")"
REASON="$(jq -r .Cluster.Status.StateChangeReason.Code "$REC/cluster.json")"
HOURS="$(jq -r .Cluster.NormalizedInstanceHours "$REC/cluster.json")"
jq -n --arg commit "$COMMIT" --arg fraction "$FRACTION" --arg snap "$SC_SNAPSHOT" --arg es "$ENRICH_SNAPSHOT" \
  --arg inputRun "$INPUT_RUN" --arg cluster "$CLUSTER_ID" --arg state "$STATE" --arg reason "$REASON" \
  --arg hours "$HOURS" --arg account "$ACCOUNT" --arg region "$REGION" --arg release "$RELEASE" --arg itype "$ITYPE" \
  --argjson core "$CORE" --arg reducers "$REDUCERS" --arg shuffle "$SHUFFLE" --arg bucket "$BUCKET" \
  --arg hadoop "$NEW_HADOOP" --arg spark "$NEW_SPARK" --rawfile inputs "$LINES" --slurpfile steps "$REC/steps-result.json" \
  --argjson sgChecked "$SG_CHECKED" --argjson sgOpen "$SG_OPEN" \
  '{schemaVersion: "pipeline-run-record-v1", commit: $commit, codeVersion: $commit, fraction: ($fraction | tonumber),
    snapshots: {dblp: $snap, openalex: $es, crossref: $es, orcid: $es}, inputRun: $inputRun,
    inputs: ($inputs | split("\n") | map(select(test(" ")) | split(" ") | {path: .[0], sha256: .[1], bytes: (.[2] | tonumber)})),
    account: $account, region: $region,
    configuration: {releaseLabel: $release, instanceType: $itype, primary: 1, core: $core, market: "ON_DEMAND",
                    reducers: ($reducers | tonumber), shufflePartitions: ($shuffle | tonumber)},
    clusterId: $cluster, finalState: $state, stateChangeReason: $reason, normalizedInstanceHours: ($hours | tonumber? // null),
    steps: [$steps[0].Steps[] | {name: .Name, state: .Status.State, start: .Status.Timeline.StartDateTime,
             end: .Status.Timeline.EndDateTime}],
    outputs: {hadoopRuns: ($hadoop | split("\n") | map(select(. != "")) | map("s3://" + $bucket + "/curated/hadoop/snapshot=" + $snap + "/" + .)),
              sparkRuns: ($spark | split("\n") | map(select(. != "")) | map("s3://" + $bucket + "/curated/spark/snapshot=" + $snap + "/" + .))},
    emrSecurityGroups: {checked: $sgChecked, openIngress: $sgOpen, file: "security-groups.json"},
    logs: "logs/", manifests: "manifests/"}' >"$REC/run.json"
printf '==> PIPELINE record=%s cluster=%s state=%s reason=%s instance-hours=%s\n' "$REC/run.json" "$CLUSTER_ID" "$STATE" "$REASON" "$HOURS"

[ "$REASON" = ALL_STEPS_COMPLETED ] || die "cluster ended $STATE/$REASON; see $REC/steps-result.json and $REC/logs/" 1
[ -n "$NEW_HADOOP" ] && [ -n "$NEW_SPARK" ] || die "no new Hadoop or Spark run directory found" 1
ok "pipeline run recorded"
