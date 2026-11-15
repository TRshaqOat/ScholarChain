#!/usr/bin/env bash
# Runs on the EMR primary node, one call per cluster step (scripts/run-bounded-pipeline.sh uploads
# it beside the job jars and starts it through command-runner.jar). Never run on a laptop.
#
#   step.sh classpath
#       prints the Java, Hadoop, and Spark versions and the classpath entries that matter for the
#       Phase 11 check (Typesafe Config, AWS SDK v2, Scala libraries); never fails the cluster
#   step.sh hadoop <timeout s> <s3 jar> <Pipeline args...>
#       hadoop jar (Main-Class scholarchain.hadoop.Pipeline, J01 to J10 in their documented order)
#   step.sh spark <timeout s> <s3 jar> <bucket> <snapshot> <comma list of earlier Hadoop run dirs> <PipelineJob args...>
#       finds the one Hadoop run directory this cluster created (not in the earlier list, with
#       _manifest.json), then spark-submit PipelineJob (Spark jobs 1 to 11) with --input set to it
set -euo pipefail

mode="${1:?mode}"
shift

# Every AWS CLI call has explicit timeouts (scripts/README.md conventions; code review 1 F7b).
aws_t() { aws --cli-connect-timeout 10 --cli-read-timeout 120 "$@"; }
fetch() { aws_t s3 cp --only-show-errors "$1" "$2"; }

case "$mode" in
  classpath)
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
    timeout_s="$1" jar="$2"
    shift 2
    fetch "$jar" /tmp/scholarchain-hadoop.jar
    exec timeout "$timeout_s" hadoop jar /tmp/scholarchain-hadoop.jar "$@"
    ;;
  spark)
    timeout_s="$1" jar="$2" bucket="$3" snapshot="$4" earlier="$5"
    shift 5
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
