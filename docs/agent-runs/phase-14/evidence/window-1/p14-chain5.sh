#!/usr/bin/env bash
set -u
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }
HEADSHA=$(git rev-parse HEAD)
PR=operations/releases/package-$HEADSHA.json
SP=40
log "chain 5 (ruling 21) at HEAD $HEADSHA, shuffle partitions $SP, order 25 then 100"
newest() { local R; R=$(ls -td operations/pipeline-runs/*-fraction$1/ 2>/dev/null | head -1); echo "${R%/}"; }
gate25() {
  local R RJ hits out d h
  R=$(newest 25); RJ="$R/run.json"
  [ -n "$R" ] && [ -f "$RJ" ] || { log "GATE FAIL f25: no run.json"; return 1; }
  log "checking $R"
  out=$(find "$R/logs" -type f -name 'stdout*' -exec gzip -dcf {} \;)
  jq -e --arg h "$HEADSHA" --argjson sp "$SP" '.commit==$h and .configuration.shufflePartitions==$sp and .finalState=="TERMINATED" and .stateChangeReason=="ALL_STEPS_COMPLETED" and ((.steps|length)==3) and ([.steps[].state]|all(.=="COMPLETED")) and ((.outputs.hadoopRuns|length)==1) and ((.outputs.sparkRuns|length)==5) and ((.emrSecurityGroups.openIngress|length)==0)' "$RJ" >/dev/null \
    || { log "GATE FAIL f25: commit, shuffle, state, steps, outputs or ingress"; jq -c '{commit,shuffle:.configuration.shufflePartitions,finalState,stateChangeReason,steps:[.steps[]|{name,state}],hadoop:(.outputs.hadoopRuns|length),spark:(.outputs.sparkRuns|length),openIngress:.emrSecurityGroups.openIngress}' "$RJ"; return 1; }
  hits=$(find "$R/logs" -type f -exec zgrep -l -i -E 'AccessDenied|403 Forbidden' {} + 2>/dev/null)
  [ -z "$hits" ] || { log "GATE FAIL f25: AccessDenied/403 in $hits"; return 1; }
  printf '%s\n' "$out" | grep -q '^j01-dblp-extract scholarchain/ACCEPTED=27993$' || { log "GATE FAIL f25: J01 ACCEPTED is not 27993"; return 1; }
  d=$(printf '%s\n' "$out" | sed -n 's/^runId=\(run-[0-9a-f]\{32\}\) attempt=[0-9][0-9]* runDir=s3a:.*\/curated\/spark\/.*/\1/p' | head -1)
  [ -n "$d" ] && jq -e --arg d "$d" 'any(.outputs.sparkRuns[]; endswith("/run=" + $d))' "$RJ" >/dev/null || { log "GATE FAIL f25: Spark driver run '$d' not among sparkRuns"; return 1; }
  h=$(printf '%s\n' "$out" | sed -n 's/^==> HADOOP-RUN //p' | head -1)
  [ -n "$h" ] && [ "$h" = "$(jq -r '.outputs.hadoopRuns[0]' "$RJ")" ] || { log "GATE FAIL f25: Spark read Hadoop run '$h', not the record's"; return 1; }
  log "GATE OK f25: $(jq -r '.clusterId+" NIH="+(.normalizedInstanceHours|tostring)' "$RJ") driver=$d"
}
info100() {
  local R RJ S
  R=$(newest 100); RJ="$R/run.json"
  [ -n "$R" ] && [ -f "$RJ" ] || { log "INFO f100: no run.json"; return 0; }
  log "100% record $R"
  jq -c '{commit,shuffle:.configuration.shufflePartitions,clusterId,finalState,stateChangeReason,normalizedInstanceHours,steps:[.steps[]|{name,state,start,end}],hadoop:(.outputs.hadoopRuns|length),spark:(.outputs.sparkRuns|length),openIngress:.emrSecurityGroups.openIngress}' "$RJ"
  find "$R/logs" -type f -name 'stdout*' -exec gzip -dcf {} \; | grep -E '^(j01-dblp-extract scholarchain/ACCEPTED|==> HADOOP-RUN|runId=)'
  S=$(dirname "$(zgrep -l 'HADOOP-RUN' "$R"/logs/*/stdout.gz 2>/dev/null | head -1)")/stderr.gz
  [ -f "$S" ] && { gzip -dcf "$S" | grep -E 'ERROR|Job aborted' | grep -v 'Lost task' | cut -c1-200 | head -3; gzip -dcf "$S" | grep -o 'scholarchain\.spark\.[A-Za-z]*Job\$\.run([A-Za-z]*\.scala:[0-9]*)' | sort -u; }
  log "AccessDenied/403 hits: $(find "$R/logs" -type f -exec zgrep -l -i -E 'AccessDenied|403 Forbidden' {} + 2>/dev/null | wc -l | tr -d ' ')"
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
  ./scripts/run-bounded-pipeline.sh --fraction "$F" --shuffle-partitions "$SP" --package-record "$PR" --profile scholarchain-sdk > /tmp/p14-run$F-c5.txt 2>&1
  rc=$?
  grep -E '^==> ' /tmp/p14-run$F-c5.txt | tail -8
  log "fraction $F exit $rc"
  [ "$rc" = 0 ]
}
if cost_ok && pre && run 25 && gate25 && cost_ok && pre; then
  run 100 && log "100% exit 0" || log "100% ended non-zero (accepted in advance by ruling 21; read below)"
  info100
  log "CHAIN 5 DONE"
else
  log "CHAIN 5 STOPPED before 100% (reason above); nothing was rerun"
fi
log "active clusters (expect none): $(aws emr list-clusters --active --region us-east-2 --profile scholarchain --query 'Clusters[].Id' --output text)"
