#!/bin/bash
# usage: check.sh <n>   (one sbt check run with logs, exit, times, JUnit counts)
n=$1
repo=/Users/achyutpatel/Developer/Cloud/ScholarChain
L=$repo/scratch/phase-11-logs/repair-02
SP=/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c8242cd7-266e-4b64-b940-735f198f56c6/scratchpad/repair-02-partA
cd "$repo"
df -h . > "$L/df-before-check-$n.txt"
start=$(date +%s); date -u +%FT%TZ > "$L/sbt-check-$n.start"
SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g -batch check > "$L/sbt-check-$n.log" 2>&1
echo $? > "$L/sbt-check-$n.exit"
date -u +%FT%TZ > "$L/sbt-check-$n.end"
python3 -I "$SP/junit_counts.py" "$repo" "$start" > "$L/junit-counts-$n.txt"
