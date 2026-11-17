#!/usr/bin/env bash
set -u
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }
HEADSHA=$(git rev-parse HEAD)
PR=operations/releases/package-$HEADSHA.json
SP=40
log "chain 3 (ruling 18) at HEAD $HEADSHA, shuffle partitions $SP, order 50 25 100"
gate() {
  local F=$1 J=$2 R RJ hits out d h
  R=$(ls -td operations/pipeline-runs/*-fraction$F/ 2>/dev/null | head -1); R=${R%/}; RJ="$R/run.json"
  [ -n "$R" ] && [ -f "$RJ" ] || { log "GATE FAIL f$F: no run.json"; return 1; }
  log "checking $R"
  jq -e --arg h "$HEADSHA" --argjson sp "$SP" '.commit==$h and .configuration.shufflePartitions==$sp and .finalState=="TERMINATED" and .stateChangeReason=="ALL_STEPS_COMPLETED" and ((.steps|length)==3) and ([.steps[].state]|all(.=="COMPLETED")) and ((.outputs.hadoopRuns|length)==1) and ((.outputs.sparkRuns|length)==5) and ((.emrSecurityGroups.openIngress|length)==0)' "$RJ" >/dev/null \
    || { log "GATE FAIL f$F: commit, shuffle, state, steps, outputs or ingress"; jq -c '{commit,shuffle:.configuration.shufflePartitions,finalState,stateChangeReason,steps:[.steps[]|{name,state}],hadoop:(.outputs.hadoopRuns|length),spark:(.outputs.sparkRuns|length),openIngress:.emrSecurityGroups.openIngress}' "$RJ"; return 1; }
  hits=$(find "$R/logs" -type f -exec zgrep -l -i -E 'AccessDenied|403 Forbidden' {} + 2>/dev/null)
  [ -z "$hits" ] || { log "GATE FAIL f$F: AccessDenied/403 in $hits"; return 1; }
  out=$(find "$R/logs" -type f -name 'stdout*' -exec gzip -dcf {} \;)
  printf '%s\n' "$out" | grep -q "^j01-dblp-extract scholarchain/ACCEPTED=$J\$" || { log "GATE FAIL f$F: J01 ACCEPTED is not $J"; return 1; }
  d=$(printf '%s\n' "$out" | sed -n 's/^runId=\(run-[0-9a-f]\{32\}\) attempt=[0-9][0-9]* runDir=s3a:.*\/curated\/spark\/.*/\1/p' | head -1)
  [ -n "$d" ] && jq -e --arg d "$d" 'any(.outputs.sparkRuns[]; endswith("/run=" + $d))' "$RJ" >/dev/null || { log "GATE FAIL f$F: Spark driver run '$d' not among sparkRuns"; return 1; }
  h=$(printf '%s\n' "$out" | sed -n 's/^==> HADOOP-RUN //p' | head -1)
  [ -n "$h" ] && [ "$h" = "$(jq -r '.outputs.hadoopRuns[0]' "$RJ")" ] || { log "GATE FAIL f$F: Spark read Hadoop run '$h', not the record's"; return 1; }
  log "lines: $(printf '%s\n' "$out" | grep -E '^runId=' | cut -c1-60 | tr '\n' ' ')"
  log "GATE OK f$F: $(jq -r '.clusterId+" NIH="+(.normalizedInstanceHours|tostring)' "$RJ") driver=$d"
}
cost_ok() {
  local nih
  nih=$(cat operations/pipeline-runs/*/run.json 2>/dev/null | jq -s '[.[].normalizedInstanceHours // 0] | add')
  log "window 1 normalized instance hours so far: $nih (stop above 160)"
  [ "$nih" -le 160 ]
}
pre() {
  [ "$(git rev-parse HEAD)" = "$HEADSHA" ] || { log "STOP: HEAD moved"; return 1; }
  [ "$(git status --porcelain)" = "?? operations/" ] || { log "STOP: tree not clean"; git status --short; return 1; }
  [ -f "$PR" ] || { log "STOP: no package record $PR"; return 1; }
}
run() {
  local F=$1 rc
  log "starting fraction $F"
  ./scripts/run-bounded-pipeline.sh --fraction "$F" --shuffle-partitions "$SP" --package-record "$PR" --profile scholarchain-sdk > /tmp/p14-run$F-sp$SP.txt 2>&1
  rc=$?
  grep -E '^==> ' /tmp/p14-run$F-sp$SP.txt | tail -8
  log "fraction $F exit $rc"
  [ "$rc" = 0 ]
}
if cost_ok && pre && run 50 && gate 50 55598 && cost_ok && pre && run 25 && gate 25 27993 && cost_ok && pre && run 100 && gate 100 110867; then
  log "CHAIN DONE: 50, 25 and 100 all passed at shuffle partitions $SP"
else
  log "CHAIN STOPPED (reason above); nothing was rerun"
fi
log "active clusters (expect none): $(aws emr list-clusters --active --region us-east-2 --profile scholarchain --query 'Clusters[].Id' --output text)"
