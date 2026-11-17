#!/usr/bin/env bash
set -u
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }
log "waiting for Step 20 (package.sh and the 25% run) to finish"
while pgrep -f 'scripts/package.sh' >/dev/null || pgrep -f 'run-bounded-pipeline.sh --fraction 25' >/dev/null; do sleep 60; done
sleep 60
HEADSHA=$(git rev-parse HEAD)
PR=operations/releases/package-$HEADSHA.json
gate() {
  local F=$1 J=$2 R hits
  R=$(ls -td operations/pipeline-runs/*-fraction$F/ 2>/dev/null | head -1); R=${R%/}
  [ -n "$R" ] && [ -f "$R/run.json" ] || { log "GATE FAIL f$F: no run.json"; return 1; }
  log "checking $R"
  jq -e --arg h "$HEADSHA" '.commit==$h and .finalState=="TERMINATED" and ((.steps|length)==3) and ([.steps[].state]|all(.=="COMPLETED")) and ((.outputs.hadoopRuns|length)==1) and ((.outputs.sparkRuns|length)==1) and ((.emrSecurityGroups.openIngress|length)==0)' "$R/run.json" >/dev/null \
    || { log "GATE FAIL f$F: commit, state, steps, outputs or ingress"; jq -c '{commit,finalState,stateChangeReason,steps,outputs,openIngress:.emrSecurityGroups.openIngress}' "$R/run.json"; return 1; }
  hits=$(find "$R/logs" -type f -exec zgrep -l -i -E 'AccessDenied|403 Forbidden' {} + 2>/dev/null)
  [ -z "$hits" ] || { log "GATE FAIL f$F: AccessDenied/403 in $hits"; return 1; }
  find "$R/logs" -type f -name 'stdout*' -exec gzip -dcf {} \; | grep -q "^j01-dblp-extract scholarchain/ACCEPTED=$J\$" || { log "GATE FAIL f$F: J01 ACCEPTED is not $J"; return 1; }
  log "GATE OK f$F: $(jq -r '.clusterId+" NIH="+(.normalizedInstanceHours|tostring)' "$R/run.json")"
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
  ./scripts/run-bounded-pipeline.sh --fraction "$F" --package-record "$PR" --profile scholarchain-sdk > /tmp/p14-run$F.txt 2>&1
  rc=$?
  grep -E '^==> ' /tmp/p14-run$F.txt | tail -8
  log "fraction $F exit $rc"
  [ "$rc" = 0 ]
}
if gate 25 27993 && cost_ok && pre && run 50 && gate 50 55598 && cost_ok && pre && run 100 && gate 100 110867; then
  log "CHAIN DONE: 25, 50 and 100 all passed"
else
  log "CHAIN STOPPED (reason above); nothing was rerun"
fi
log "active clusters (expect none): $(aws emr list-clusters --active --region us-east-2 --profile scholarchain --query 'Clusters[].Id' --output text)"
