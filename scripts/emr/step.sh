#!/usr/bin/env bash
# Runs on the EMR primary node, one call per cluster step (scripts/run-bounded-pipeline.sh uploads
# it beside the job jars and starts it through command-runner.jar). Never run on a laptop.
#
#   step.sh classpath
#       prints the Java, Hadoop, and Spark versions and the classpath entries that matter for the
#       Phase 11 check (Typesafe Config, AWS SDK v2, Scala libraries); never fails the cluster
#   step.sh hadoop <timeout s> <s3 jar> <Pipeline args...>
#       hadoop jar (Main-Class scholarchain.hadoop.Pipeline, J01 to J10 in their documented order)
#   step.sh spark <timeout s> <s3 jar> <bucket> <snapshot> <comma list of earlier Hadoop run dirs, or none> <PipelineJob args...>
#       finds the one Hadoop run directory this cluster created (not in the earlier list, with
#       _manifest.json), then spark-submit PipelineJob (Spark jobs 1 to 11) with --input set to it
#
# EMR command-runner drops empty-string arguments, which shifts every later positional (P14-D5), so
# the caller passes "none" for an empty earlier list, and each mode checks its positionals: present,
# non-empty, and not starting with "--". A failed check exits 2 before any AWS call.
set -euo pipefail

usage_fail() { echo "step.sh: $1" >&2; exit 2; }

# need <count> <names> "$@": the first <count> arguments are the positionals named in <names>
need() {
  local want="$1" names="$2" i=0 a
  shift 2
  [ "$#" -ge "$want" ] || usage_fail "$mode expects $want positional arguments ($names), got $#"
  for a in "$@"; do
    [ "$i" -lt "$want" ] || break
    i=$((i + 1))
    case "$a" in
      "" | --*) usage_fail "$mode positional argument $i of $want ($names) is '$a'; an earlier argument is missing" ;;
    esac
  done
}

[ "$#" -ge 1 ] || usage_fail "missing mode (classpath, hadoop, or spark)"
mode="$1"
shift

# Every AWS CLI call has explicit timeouts (scripts/README.md conventions; code review 1 F7b).
aws_t() { aws --cli-connect-timeout 10 --cli-read-timeout 120 "$@"; }
fetch() { aws_t s3 cp --only-show-errors "$1" "$2"; }

case "$mode" in
  classpath)
    [ "$#" = 0 ] || usage_fail "classpath takes no arguments, got $#"
    set +e
    java -version 2>&1
    hadoop version 2>&1 | head -3
    spark-submit --version 2>&1 | head -12
    echo "== hadoop classpath (config, awssdk, scala)"
    hadoop classpath --glob | tr ':' '\n' | grep -iE 'typesafe|/config-[0-9]|awssdk|bundle-2|scala' | sort -u
    echo "== /usr/lib/spark/jars (config, awssdk, scala)"
    ls /usr/lib/spark/jars | grep -iE 'typesafe|^config-|awssdk|bundle|scala' | sort
    exit 0
    ;;
  hadoop)
    need 2 "timeout s, s3 jar" "$@"
    timeout_s="$1" jar="$2"
    shift 2
    fetch "$jar" /tmp/scholarchain-hadoop.jar
    exec timeout "$timeout_s" hadoop jar /tmp/scholarchain-hadoop.jar "$@"
    ;;
  spark)
    need 5 "timeout s, s3 jar, bucket, snapshot, earlier runs or none" "$@"
    timeout_s="$1" jar="$2" bucket="$3" snapshot="$4" earlier="$5"
    shift 5
    [ "$earlier" != none ] || earlier=""
    base="s3://$bucket/curated/hadoop/snapshot=$snapshot/"
    run="" n=0
    for d in $(aws_t s3 ls "$base" | awk '$1 == "PRE" {sub("/$", "", $2); print $2}'); do
      case ",$earlier," in *",$d,"*) continue ;; esac
      run="$d" n=$((n + 1))
    done
    [ "$n" = 1 ] || { echo "expected exactly one new Hadoop run under $base, found $n" >&2; exit 1; }
    aws_t s3 ls "$base$run/_manifest.json" >/dev/null || { echo "no _manifest.json in $base$run" >&2; exit 1; }
    echo "==> HADOOP-RUN ${base}${run}"
    fetch "$jar" /tmp/scholarchain-spark.jar
    exec timeout "$timeout_s" spark-submit --master yarn --deploy-mode client \
      --class scholarchain.spark.PipelineJob /tmp/scholarchain-spark.jar \
      --input "s3a://$bucket/curated/hadoop/snapshot=$snapshot/$run" "$@"
    ;;
  *)
    echo "unknown mode: $mode" >&2
    exit 2
    ;;
esac
