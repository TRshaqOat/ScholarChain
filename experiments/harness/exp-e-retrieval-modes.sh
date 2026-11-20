#!/usr/bin/env bash
# Experiment E: the benchmark questions under three retrieval modes (fused, graph-only,
# vector-only), local runtime against live Bedrock, file-backed vectors (Phase 14 ruling 31;
# Phase 15 ruling 11, work order 4 A1). Run only through scripts/run-experiment.sh with
# experiments/manifests/exp-e-retrieval-modes-v1.json. Bash 3.2; reuses scripts/lib/common.sh.
# Phase 15 ruling 12 (repair-02): fused resumes the check with --max N-2, so every mode answers
# exactly the first N questions; raw files go directly under $SCHOLARCHAIN_EXPERIMENT_DIR; TERM
# stops sbt's whole process group (the JVM too).
set -euo pipefail
# shellcheck source=../../scripts/lib/common.sh
. "$(dirname "$0")/../../scripts/lib/common.sh"

MODES="fused graph-only vector-only"

usage() {
  cat <<'EOF'
Usage: experiments/harness/exp-e-retrieval-modes.sh [--max 10] [--dry-run | --self-test]

Steps (each prints ==> STEP; raw files go only under $SCHOLARCHAIN_EXPERIMENT_DIR, which
run-experiment.sh sets to <stamp>/raw, so the files are <stamp>/raw/<mode>/ and <stamp>/raw/harness.json):
  preconditions  SCHOLARCHAIN_EXPERIMENT_DIR and AWS_PROFILE set (from run-experiment.sh); no other
                 sbt or runMain process running (exit 3 otherwise)
  check          FUSED --max 2 into fused/. Continues only when sbt exits 0, its output has a
                 "plan sources:" line that includes MODEL, and neither that line nor the step's
                 stdout and stderr contain FALLBACK_RULES, BUDGET_EXHAUSTED or Exception; otherwise
                 exit 3 and every output is kept
  modes          fused --max N-2 (BenchmarkCli's --max limits the new pending questions, so fused
                 resumes the check's 2 and ends with N), then graph-only --max N and vector-only
                 --max N, one sbt process at a time, --per-minute 8, into <mode>/. Every mode
                 answers the first N questions. A failing mode is recorded and the next mode runs.
  record         harness.json (rewritten after every step): start and end times, modes, N, each
                 invocation's command, exit code, benchmark run IDs, and the check decision

N is 3 to 10 (ruling 31 caps a mode at 10; fused needs --max N-2 >= 1).
Per invocation: <mode>/<step>-stdout.txt and <step>-stderr.txt (.txt, never .log), and the
BenchmarkCli outputs under <mode>/s3/ and <mode>/dynamodb/.
Bedrock: at most N questions per mode (30 for N = 10; a mode that exits 0 ends with exactly N),
Haiku held to 8 requests per minute.
On TERM (run-experiment.sh maxMinutes) sbt's whole process group (runner and JVM) gets TERM,
then KILL after 20 s if any member is left, and harness.json is kept.
Self-test: EXP_E_SBT replaces sbt and EXP_E_PS_FIXTURE replaces the process list.
Exit: 0 every mode exited 0; 1 a mode failed; 2 usage; 3 precondition or check stop.
EOF
}

N=10
SELF_TEST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --max) N="$(flag_val "$1" "${2-}")"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --help | -h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" 2 ;;
  esac
done
# Ruling 31: at most 10 questions per mode; the check answers 2 and fused then needs --max N-2 >= 1
# (BenchmarkCli rejects --max 0).
printf '%s' "$N" | grep -Eq '^[0-9]+$' && [ "$N" -ge 3 ] && [ "$N" -le 10 ] || die "--max must be 3 to 10" 2
mode_max() { if [ "$1" = fused ]; then printf '%s' $((N - 2)); else printf '%s' "$N"; fi; }

# The exact command of work order 4 A1, with <dir>, <N> and <mode> filled in.
runmain() { # dir max mode
  printf '%s' "rag/runMain scholarchain.rag.cli.BenchmarkCli --questions experiments/benchmark/benchmark-questions-v1.csv --path-evidence scratch/stores/work/neptune-check/snapshot=2026-10-03/run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv --graph scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --items scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --objects scratch/stores/s3 --vectors scratch/stores-live/s3vectors --out $1 --window-summary-root scratch/runtime/wo8/s3 --window-summary-run run-6fbad498beb0d8f570df67665fb97544 --live --profile $AWS_PROFILE --region us-east-2 --per-minute 8 --max $2 --retrieval-mode $3"
}
command_text() { printf 'env SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -batch -J-Xmx6g "%s"' "$(runmain "$@")"; }

self_test() {
  local t rc fails=0
  t="$(mktemp -d "${TMPDIR:-/tmp}/exp-e-selftest.XXXXXX")"
  # Stub sbt: answers like BenchmarkCli, including resume: it counts questions/*.json already under
  # --out and answers at most --max pending questions (of 30), as pending.take(max) does.
  # EXP_E_STUB_PLAN is the plan-sources text; EXP_E_STUB_FAIL names a mode that exits 1;
  # EXP_E_STUB_PIDS makes it spawn a child (as the sbt runner spawns java), write "<own pid> <child
  # pid>" there and wait.
  cat >"$t/sbt" <<'STUB'
#!/usr/bin/env bash
arg="$3"; mode="${arg##*--retrieval-mode }"; out="${arg#*--out }"; out="${out%% *}"
max="${arg##*--max }"; max="${max%% *}"
printf '%s|%s|%s\n' "$mode" "$max" "$SCHOLARCHAIN_EMBEDDING_MODEL_ID" >>"$EXP_E_STUB_LOG"
run="run-$(printf '%032d' "${#mode}")"
q="$out/s3/reports/rag-benchmark/snapshot=2026-10-03/run=$run/questions"
mkdir -p "$q"
done_n="$(find "$out" -path '*/questions/*.json' | wc -l | tr -d ' ')"
n=$((30 - done_n)); [ "$max" -ge "$n" ] || n="$max"
printf 'benchmark run %s: 30 questions, %s already done, answering %s now\n' "$run" "$done_n" "$n"
i=1; while [ "$i" -le "$n" ]; do printf '{}' >"$q/B$(printf '%02d' $((done_n + i))).json"; i=$((i + 1)); done
if [ -n "${EXP_E_STUB_PIDS:-}" ]; then
  sleep 300 &
  printf '%s %s\n' "$$" "$!" >"$EXP_E_STUB_PIDS.tmp" && mv "$EXP_E_STUB_PIDS.tmp" "$EXP_E_STUB_PIDS"
  wait
fi
printf 'plan sources: %s; outcomes: ANSWERED %s\n' "${EXP_E_STUB_PLAN:-MODEL $n}" "$n"
printf 'stub stderr %s\n' "${EXP_E_STUB_ERR:-}" >&2
[ "$mode" != "${EXP_E_STUB_FAIL:-none}" ] || exit 1
STUB
  chmod +x "$t/sbt"
  : >"$t/ps-none"
  printf ' 4242 /usr/bin/java -jar /opt/homebrew/libexec/bin/sbt-launch.jar\n' >"$t/ps-sbt"

  run_case() { # name expected-rc plan fail ps [stderr text [N]]
    local d="$t/$1"
    mkdir -p "$d/raw"
    : >"$d/stub.log"
    rc=0
    # SCHOLARCHAIN_EXPERIMENT_DIR is <case>/raw, as run-experiment.sh sets <stamp>/raw.
    (SCHOLARCHAIN_EXPERIMENT_DIR="$d/raw" AWS_PROFILE=fixture-profile EXP_E_SBT="$t/sbt" EXP_E_STUB_ERR="${6-}" \
      EXP_E_PS_FIXTURE="$t/$5" EXP_E_STUB_LOG="$d/stub.log" EXP_E_STUB_PLAN="$3" EXP_E_STUB_FAIL="$4" \
      bash "$0" --max "${7:-4}" >"$d/out.txt" 2>"$d/err.txt") || rc=$?
    if [ "$rc" = "$2" ]; then printf '    PASS %s exit %s\n' "$1" "$rc"; else
      printf '    FAIL %s exit %s, expected %s\n' "$1" "$rc" "$2"; fails=$((fails + 1)); fi
  }
  expect() { # description command...
    if "${@:2}"; then printf '    PASS %s\n' "$1"; else printf '    FAIL %s\n' "$1"; fails=$((fails + 1)); fi
  }
  hj() { local n="$1"; shift; jq -e "$@" "$t/$n/raw/harness.json" >/dev/null; }
  qids() { find "$t/$1/raw/$2" -path '*/questions/*.json' -exec basename {} .json \; | sort | tr '\n' ' '; }
  alive() { kill -0 "$1" 2>/dev/null; }
  gone() { ! kill -0 "$1" 2>/dev/null; }

  step "self-test"
  run_case ok 0 "MODEL 2" none ps-none
  expect "ok: invocations fused(2), fused(N-2=2), graph-only(4), vector-only(4), embedding model set" \
    test "$(tr '\n' ' ' <"$t/ok/stub.log")" = "fused|2|amazon.titan-embed-text-v2:0 fused|2|amazon.titan-embed-text-v2:0 graph-only|4|amazon.titan-embed-text-v2:0 vector-only|4|amazon.titan-embed-text-v2:0 "
  expect "ok: fused resumed (2 already done, answering 2)" grep -q '30 questions, 2 already done, answering 2 now' "$t/ok/raw/fused/modes-stdout.txt"
  expect "ok: every mode has exactly N=4 results, the same question IDs B01-B04" \
    test "$(qids ok fused)|$(qids ok graph-only)|$(qids ok vector-only)" = "B01 B02 B03 B04 |B01 B02 B03 B04 |B01 B02 B03 B04 "
  expect "ok: harness.json decision, exit codes, maxima, run IDs" hj ok \
    '.check.continue == true and .exitCode == 0 and .n == 4 and ([.invocations[].exitCode] == [0,0,0,0])
     and ([.invocations[].max] == [2,2,4,4])
     and ([.invocations[].benchmarkRunIds[0]] == ["run-00000000000000000000000000000005","run-00000000000000000000000000000005","run-00000000000000000000000000000010","run-00000000000000000000000000000011"])
     and .startedAt != null and .endedAt != null and (.modes == ["fused","graph-only","vector-only"])'
  expect "ok: the recorded commands are the ruling 12(a) text (fused --max N-2, graph-only --max N)" hj ok \
    --arg f "$(AWS_PROFILE=fixture-profile command_text "$t/ok/raw/fused" 2 fused)" \
    --arg g "$(AWS_PROFILE=fixture-profile command_text "$t/ok/raw/graph-only" 4 graph-only)" \
    '.invocations[1].command == $f and .invocations[2].command == $g and (.invocations[2].command | contains("--max 4 --retrieval-mode graph-only"))'
  expect "ok: raw layout <dir>/<mode>/ and <dir>/harness.json (no <dir>/raw/)" \
    test -f "$t/ok/raw/harness.json" -a -d "$t/ok/raw/vector-only" -a ! -e "$t/ok/raw/raw"
  expect "ok: per-step stdout and stderr .txt kept" test -s "$t/ok/raw/fused/check-stdout.txt" -a -s "$t/ok/raw/fused/modes-stderr.txt" -a -s "$t/ok/raw/vector-only/modes-stdout.txt"
  expect "ok: no .log file written" test -z "$(find "$t/ok/raw" -name '*.log')"

  run_case n3 0 "MODEL 2" none ps-none "" 3
  expect "n3: fused --max 1 after the check; each mode exactly 3 results, same IDs" \
    test "$(cut -d'|' -f1,2 <"$t/n3/stub.log" | tr '\n' ' ')|$(qids n3 fused)|$(qids n3 graph-only)|$(qids n3 vector-only)" = "fused|2 fused|1 graph-only|3 vector-only|3 |B01 B02 B03 |B01 B02 B03 |B01 B02 B03 "
  run_case n10 0 "MODEL 2" none ps-none "" 10
  expect "n10: fused 2 + 8; each mode exactly 10 results, same IDs" \
    test "$(cut -d'|' -f1,2 <"$t/n10/stub.log" | tr '\n' ' ')|$(qids n10 fused)|$(qids n10 graph-only)|$(qids n10 vector-only)" = "fused|2 fused|8 graph-only|10 vector-only|10 |B01 B02 B03 B04 B05 B06 B07 B08 B09 B10 |B01 B02 B03 B04 B05 B06 B07 B08 B09 B10 |B01 B02 B03 B04 B05 B06 B07 B08 B09 B10 "
  for bad in 2 11 0 x; do
    run_case "n-$bad" 2 "MODEL 2" none ps-none "" "$bad"
    expect "n-$bad: refused before any sbt call" test ! -s "$t/n-$bad/stub.log"
  done

  run_case fallback 3 "FALLBACK_RULES 1, MODEL 1" none ps-none
  expect "fallback: only the check ran" test "$(wc -l <"$t/fallback/stub.log" | tr -d ' ')" = 1
  expect "fallback: decision recorded, outputs kept" hj fallback '.check.continue == false and .exitCode == 3 and (.invocations | length) == 1'
  expect "fallback: check stdout kept" test -s "$t/fallback/raw/fused/check-stdout.txt"
  run_case rules 3 "RULES 2" none ps-none
  run_case budget 3 "MODEL 2; outcomes: BUDGET_EXHAUSTED 1" none ps-none
  run_case exception 3 "MODEL 2" none ps-none "java.lang.RuntimeException: boom"
  expect "exception: stop reason names it" hj exception '.check.stopReason == "the check output contains: Exception"'
  run_case check-fails 3 "MODEL 2" fused ps-none

  run_case graph-fails 1 "MODEL 2" graph-only ps-none
  expect "graph-fails: vector-only still ran after the failing mode" test "$(tail -1 "$t/graph-fails/stub.log")" = "vector-only|4|amazon.titan-embed-text-v2:0"
  expect "graph-fails: exit codes recorded" hj graph-fails '[.invocations[].exitCode] == [0,0,1,0] and .exitCode == 1'

  run_case other-sbt 3 "MODEL 2" none ps-sbt
  expect "other-sbt: refused before any sbt call" test ! -s "$t/other-sbt/stub.log"
  expect "other-sbt: refusal names the process" grep -q 'sbt-launch' "$t/other-sbt/err.txt"

  # TERM (run-experiment.sh at maxMinutes) while the check's stub waits on its own child.
  local d="$t/term" hp sp="" cp="" i=0 trc=0 secs
  mkdir -p "$d/raw"
  : >"$d/stub.log"
  SCHOLARCHAIN_EXPERIMENT_DIR="$d/raw" AWS_PROFILE=fixture-profile EXP_E_SBT="$t/sbt" EXP_E_PS_FIXTURE="$t/ps-none" \
    EXP_E_STUB_LOG="$d/stub.log" EXP_E_STUB_PIDS="$d/pids" bash "$0" --max 4 >"$d/out.txt" 2>"$d/err.txt" &
  hp=$!
  while [ ! -s "$d/pids" ] && [ "$i" -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
  [ ! -s "$d/pids" ] || read -r sp cp <"$d/pids"
  expect "term: the stub (sbt runner) and its child are running before TERM" alive "${sp:-0}"
  expect "term: the stub's child is running before TERM" alive "${cp:-0}"
  secs=$SECONDS
  kill -TERM "$hp"
  wait "$hp" || trc=$?
  secs=$((SECONDS - secs))
  i=0
  while { alive "${sp:-0}" || alive "${cp:-0}"; } && [ "$i" -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
  expect "term: harness exit 143 (stopped in ${secs} s)" test "$trc" = 143
  expect "term: the stub (sbt runner) is gone after TERM" gone "${sp:-0}"
  expect "term: its child (the JVM's stand-in) is gone after TERM" gone "${cp:-0}"
  [ -z "$sp$cp" ] || kill -KILL $sp $cp 2>/dev/null || true
  expect "term: harness.json kept with exitCode 143 and terminated" hj term '.exitCode == 143 and .check.terminated == true'

  rm -rf "$t"
  [ "$fails" = 0 ] || die "self-test: $fails failure(s)" 1
  ok "self-test"
  exit 0
}
[ "$SELF_TEST" = 0 ] || self_test

# Other sbt or runMain processes (the bracket keeps awk from matching its own command line).
other_sbt() {
  if [ -n "${EXP_E_PS_FIXTURE:-}" ]; then cat "$EXP_E_PS_FIXTURE"; else ps -axo pid=,command=; fi |
    awk -v me="$$" '$1 != me && /[s]bt-launch|xsbt[.]boot|[s]bt[.]ForkMain|[r]unMain|(^|[ \/])[s]btn?( |$)/'
}

OUT_ENV="${SCHOLARCHAIN_EXPERIMENT_DIR:-<stamp>/raw}"
if [ "$DRY_RUN" = 1 ]; then
  AWS_PROFILE="${AWS_PROFILE:-<AWS_PROFILE>}"
  dry_run_exit \
    "refuse (exit 3) if another sbt or runMain process is running" \
    "check: $(command_text "$OUT_ENV/fused" 2 fused)" \
    "continue only if exit 0, a 'plan sources:' line includes MODEL, and no FALLBACK_RULES, BUDGET_EXHAUSTED or Exception" \
    "fused (resumes the check's 2, ends with $N): $(command_text "$OUT_ENV/fused" "$(mode_max fused)" fused)" \
    "graph-only: $(command_text "$OUT_ENV/graph-only" "$(mode_max graph-only)" graph-only)" \
    "vector-only: $(command_text "$OUT_ENV/vector-only" "$(mode_max vector-only)" vector-only)" \
    "write $OUT_ENV/harness.json after every step"
fi

need jq awk
step "preconditions"
[ -n "${SCHOLARCHAIN_EXPERIMENT_DIR:-}" ] && [ -d "$SCHOLARCHAIN_EXPERIMENT_DIR" ] ||
  die "SCHOLARCHAIN_EXPERIMENT_DIR is not a directory (run through scripts/run-experiment.sh)" 3
# sbt splits the runMain argument on spaces, so neither value may hold one.
printf '%s' "$SCHOLARCHAIN_EXPERIMENT_DIR" | grep -Eq '^[A-Za-z0-9_./=-]+$' ||
  die "SCHOLARCHAIN_EXPERIMENT_DIR has characters sbt would split: $SCHOLARCHAIN_EXPERIMENT_DIR" 3
printf '%s' "${AWS_PROFILE:-}" | grep -Eq '^[A-Za-z0-9_.-]+$' || die "AWS_PROFILE is unset or not a plain profile name" 3
OTHERS="$(other_sbt)"
[ -z "$OTHERS" ] || { printf '%s\n' "$OTHERS" >&2; die "another sbt or runMain process is running (listed above); one sbt process at a time" 3; }
ok "no other sbt process"
cd "$SC_ROOT"
RAW="$SCHOLARCHAIN_EXPERIMENT_DIR"
SBT="${EXP_E_SBT:-sbt}"
STARTED="$(utc_now)"
INV='[]'
CHECK='null'
RC_ALL=0

record() { # exit code (null while running)
  jq -n --arg s "$STARTED" --arg e "$(utc_now)" --argjson n "$N" --argjson inv "$INV" --argjson check "$CHECK" \
    --argjson rc "$1" --arg modes "$MODES" \
    '{schemaVersion: "exp-e-harness-v1", startedAt: $s, endedAt: (if $rc == null then null else $e end),
      updatedAt: $e, modes: ($modes | split(" ")), n: $n, checkMax: 2, perMinute: 8, region: "us-east-2",
      check: $check, invocations: $inv, exitCode: $rc}' >"$RAW/harness.json"
}

CHILD=""
on_term() {
  trap '' TERM INT
  if [ -n "$CHILD" ]; then
    # sbt runs in its own process group (CHILD is its leader): TERM reaches the runner and the JVM.
    kill -TERM -- "-$CHILD" 2>/dev/null || true
    local i=0
    while kill -0 -- "-$CHILD" 2>/dev/null && [ "$i" -lt 20 ]; do sleep 1; i=$((i + 1)); done
    kill -KILL -- "-$CHILD" 2>/dev/null || true # within run-experiment.sh's 30 s before its own KILL
    wait "$CHILD" 2>/dev/null || true
  fi
  CHECK="$(jq -c '. // {} | . + {terminated: true}' <<<"$CHECK")"
  record 143
  die "terminated (maxMinutes); the running sbt process group was stopped" 143
}
trap on_term TERM INT

run_step() { # step mode max -> sets LAST_RC
  local dir="$RAW/$2" start rc=0 ids
  mkdir -p "$dir"
  step "$1 $2 --max $3"
  start="$(utc_now)"
  set -m # monitor mode only for this fork: the background job gets its own process group
  env SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 "$SBT" -batch -J-Xmx6g "$(runmain "$dir" "$3" "$2")" \
    >"$dir/$1-stdout.txt" 2>"$dir/$1-stderr.txt" </dev/null &
  CHILD=$!
  set +m
  wait "$CHILD" || rc=$?
  CHILD=""
  ids="$({ grep -Eo 'benchmark run run-[0-9a-f]{32}' "$dir/$1-stdout.txt" || true; } | awk '{print $3}' | jq -Rsc 'split("\n") | map(select(. != ""))')"
  INV="$(jq -c --arg step "$1" --arg mode "$2" --argjson max "$3" --arg cmd "$(command_text "$dir" "$3" "$2")" \
    --arg s "$start" --arg e "$(utc_now)" --argjson rc "$rc" --argjson ids "$ids" --arg out "$2/$1-stdout.txt" --arg err "$2/$1-stderr.txt" \
    '. + [{step: $step, mode: $mode, max: $max, command: $cmd, startedAt: $s, endedAt: $e, exitCode: $rc,
           benchmarkRunIds: $ids, stdout: $out, stderr: $err}]' <<<"$INV")"
  LAST_RC=$rc
  if [ "$rc" = 0 ]; then ok "$2 exit 0"; else warn "$2 exit $rc"; fi
}

record null
run_step check fused 2
PLAN_LINE="$(grep 'plan sources:' "$RAW/fused/check-stdout.txt" | tail -1 || true)"
REASON=""
[ "$LAST_RC" = 0 ] || REASON="sbt exited $LAST_RC"
[ -n "$REASON" ] || [ -n "$PLAN_LINE" ] || REASON="no 'plan sources:' line"
[ -n "$REASON" ] || printf '%s' "$PLAN_LINE" | grep -Eq '(^|[^A-Z_])MODEL([^A-Z_]|$)' || REASON="the plan sources line has no MODEL"
if [ -z "$REASON" ]; then
  BAD="$(cat "$RAW/fused/check-stdout.txt" "$RAW/fused/check-stderr.txt" | grep -Eo 'FALLBACK_RULES|BUDGET_EXHAUSTED|Exception' | sort -u | tr '\n' ' ' || true)"
  [ -z "$BAD" ] || REASON="the check output contains: ${BAD% }"
fi
CHECK="$(jq -cn --arg line "$PLAN_LINE" --arg reason "$REASON" \
  '{planSourcesLine: (if $line == "" then null else $line end), continue: ($reason == ""), stopReason: (if $reason == "" then null else $reason end)}')"
if [ -n "$REASON" ]; then
  record 3
  die "check stopped: $REASON; outputs kept under $RAW/fused" 3
fi
ok "check: $PLAN_LINE"
record null

for m in $MODES; do
  run_step modes "$m" "$(mode_max "$m")"
  [ "$LAST_RC" = 0 ] || RC_ALL=1
  record null
done
record "$RC_ALL"
printf '==> EXP-E n=%s exit codes=%s record=%s\n' "$N" "$(jq -c '[.[].exitCode]' <<<"$INV")" "$RAW/harness.json"
[ "$RC_ALL" = 0 ] || die "a mode failed (see harness.json)" 1
ok "experiment E modes complete"
