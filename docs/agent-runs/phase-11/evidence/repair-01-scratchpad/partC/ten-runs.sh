#!/bin/bash
# Phase 11 repair-01 Part C: each changed services suite, 10 separate sbt runs. Usage: ten-runs.sh <repo> <logdir>
REPO="$1"; LOGS="$2"
cd "$REPO" || exit 2
TMPD=$(dirname "$0")
P=scholarchain.services
SUITES="$P.BuildingBlocksSpec $P.GraphEvidenceApiSpec $P.SaturationSpec $P.CorpusQuerySpec $P.RequestReadSpec $P.QueryApiSpec"
: > "$LOGS/ten-runs-summary.txt"
for i in 1 2 3 4 5 6 7 8 9 10; do
  SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g -batch "services/testOnly $SUITES" > "$LOGS/ten-run-$i.log" 2>&1
  code=$?
  for s in BuildingBlocksSpec GraphEvidenceApiSpec SaturationSpec CorpusQuerySpec RequestReadSpec QueryApiSpec; do
    # per suite: count the passed and failed test lines between its header and the next header
    awk -v s="$s:" '
      /^\[info\] [A-Za-z]+Spec:$/ { on = ($2 == s) ; next }
      on && /^\[info\] - / { if ($0 ~ /\*\*\* FAILED \*\*\*/) f++; else p++ }
      END { printf "%d/%d", p, f }' "$LOGS/ten-run-$i.log" > $TMPD/count-$s
  done
  echo "run $i exit $code BuildingBlocks $(cat $TMPD/count-BuildingBlocksSpec) GraphEvidenceApi $(cat $TMPD/count-GraphEvidenceApiSpec) Saturation $(cat $TMPD/count-SaturationSpec) CorpusQuery $(cat $TMPD/count-CorpusQuerySpec) RequestRead $(cat $TMPD/count-RequestReadSpec) QueryApi $(cat $TMPD/count-QueryApiSpec) | $(grep -h 'Tests: succeeded' "$LOGS/ten-run-$i.log")" >> "$LOGS/ten-runs-summary.txt"
  rm -f $TMPD/count-*
done
