# Phase 14 repair-04 (work order 7, Part A, experiment-engineer)

Assembled by the orchestrator: the experiment-engineer's final report, verbatim. Subagents do not write under `docs/agent-runs/` (docs/agents.md). Pre-repair snapshot and change set: the orchestrator's session scratchpad (`pre-repair-04/` and `repair-04-changeset.diff`). Orchestrator replay of the offline checks: `scratch/phase-14-logs/repair-04-09-orchestrator-replay.txt`.

---

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: Phase 14, work order 7, Part A, repair-04. Fix code review 4 findings 1-9 (docs/agent-runs/phase-14/review-report.md lines 380-634) in the four work order 5 files only. Rulings 33 and 34 bound the work. Finding 10 is out of scope: it stays residual by ruling 34 and belongs to infra-engineer.

Summary: all nine findings are fixed in place with the Edit tool, and every offline check passes. Nothing ran against AWS or the network, and both harnesses kept their executable bit.

FILES CREATED:
- None in the repository. Logs only, under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/:
  - repair-04-01-bash-n.txt
  - repair-04-02-python-ast.txt
  - repair-04-03-self-test.txt
  - repair-04-03b-self-test-mutants.txt
  - repair-04-03c-self-test-mutants.txt
  - repair-04-03d-self-test-mutants.txt
  - repair-04-04-d-dry-run.txt
  - repair-04-04b-d-dry-run-manifest-flags.txt
  - repair-04-05-f-dry-run.txt
  - repair-04-05b-f-dry-run-manifest-flags.txt
  - repair-04-06-final-recheck.txt
  - repair-04-07-manifest-jq.txt
  - repair-04-07b-manifest-jq-literal.txt
  - repair-04-08-ls-modes.txt

FILES MODIFIED (in place, Edit tool only; nothing recreated, moved or deleted):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/manifests/exp-d-query-load-v1.json
- /Users/achyutpatel/Developer/Cloud/ScholarChain/load-tests/exp-d-query-load.py
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/manifests/exp-f-failure-recovery-v1.json
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/harness/exp-f-failure-recovery.sh

FILES DELETED: none. Raw results: none exist, and none were touched.

MANIFEST:
- experiments/manifests/exp-d-query-load-v1.json and experiments/manifests/exp-f-failure-recovery-v1.json.
- Both are uncommitted (untracked at HEAD 496fe76 per the session git snapshot), so they have no commit SHA yet.
- The human commits them after code review 5.

RUN IDENTIFIERS: none. No experiment ran, and scripts/run-experiment.sh was not run, as the work order requires.
RAW RESULT LOCATIONS AND CHECKSUMS: none (no run).
OBSERVATIONS: none from AWS (no run). The offline results are under TEST RESULTS and VALIDATION RESULTS.
ESTIMATED COST: $0. No AWS, network or Bedrock call was made.
FAILED OR ANOMALOUS RUNS: none of the experiments. The offline check attempts that failed are listed under COMMANDS RUN and kept unedited.

## Findings and fixes (line numbers are after the repair)

**1. MAJOR: D's error-rate abort did not limit any load.**
- Stop event:
  - Each level creates a `threading.Event` (exp-d-query-load.py:353).
  - Every worker checks it before each request (py:316, `while not stop.is_set() and time.monotonic() < deadline`).
  - The monitor's verdict sets it (py:326-327).
  - A worker already waiting on a response stops within the 10 s request timeout. The docstring says so (py:26-35).
- Monitor:
  - `AbortMonitor` (py:108-136) keeps a 10 s sliding window per request kind (evidence, node, neighbors).
  - It holds a running non-2xx count and a run of consecutive 5xx or transport errors per kind. `hard_failure` (py:94-96) counts status 0, meaning a timeout, reset or refused connection, and any status of 500 or above.
  - The shared predicate `rule_reason` (py:99-105) trips when a kind has at least 20 requests in the window and a non-2xx rate strictly above 0.05, or 10 consecutive 5xx or transport errors.
  - Departure from the review's suggestion: the review proposed a separate polling thread. Here the monitor is checked under the level lock at every completed request. This is equivalent and trips one request after the threshold instead of one polling period later. It is also deterministic, so the self-test can drive it.
- Same rule between levels:
  - `level_trip` (py:156-169) applies `rule_reason` to each kind over the whole level, using `stats` (py:139-153), which includes `maxConsecutive5xxOrTransport`.
  - `run_load` (py:388-419) applies it after every level, including the last, before any pause or the post-observation. It also aborts on a level with no completed request, as before.
- Preflight:
  - `preflight` (py:332-348) sends one sequential GET of each path before c=1 and writes raw/preflight.json.
  - Any non-2xx records `aborted` and `abortedAt` (phase "preflight") and returns exit 3 (py:390-396).
- summary.json carries:
  - `abortRule`, `preflight`, `aborted` and `abortedAt` ({utc, phase level|between-levels|preflight, concurrency}) (py:411-416, 512-514).
  - `abortWindowCounts`: per-kind windowRequests, windowNon2xx and consecutive5xxOrTransport at the trip, or the whole-level counts for a between-levels trip.
  - Per level, `stoppedByAbortRule`.
- Flags and bounds:
  - New flags: `--abort-window-seconds`, `--abort-min-requests` and `--abort-consecutive` (py:428-431).
  - `--abort-error-rate` now defaults to 0.05.
  - Bounds (py:68-75, 447-453): rate 0 to 0.05, window 5 to 10 s, minimum requests 1 to 20, consecutive 1 to 10. The manifest cannot loosen the reviewed rule. Out-of-bounds values exit 2, as shown in repair-04-04-d-dry-run.txt.
- Manifest:
  - `command` changes `--abort-error-rate 0.5` to 0.05 and adds `--abort-window-seconds 10 --abort-min-requests 20 --abort-consecutive 10` (exp-d-query-load-v1.json:11-14).
  - `limits.abortErrorRate` is now 0.05 (json:26). New fields: `abortWindowSeconds` 10, `abortMinRequestsPerKind` 20, `abortConsecutive5xxOrTimeouts` 10, `abortScope`, `preflight` and `exitCodes` (json:27-32). `rawOutputs` adds preflight.json (json:52).
- Self-test cases (py:175-204), all with the manifest values. The rule:
  - does not trip at 1/20 or at 5/100 (rate exactly 0.05);
  - trips at 2/20 and at 3/20 4xx (non-2xx includes 4xx);
  - does not trip at 18 requests, which is below the minimum;
  - does not trip once two errors have left the 10 s window (22 requests in total would trip);
  - does not trip at 9 consecutive 503s, and trips at 10;
  - trips at 10 mixed transport errors and 504s;
  - does not trip when a 404 resets the run;
  - trips per kind: the node kind trips while the overall rate is 2/120;
  - records the window counts at the trip;
  - at level scope, does not trip at 5/100 and does trip at 6/100, does not trip at 9 consecutive and does trip at 10 transport errors.
- Checks:
  - repair-04-03-self-test.txt and repair-04-06-final-recheck.txt (`==> OK self-test`, exit 0).
  - repair-04-03d-self-test-mutants.txt: each of 5 in-memory weakenings of the rule is killed. The five are `>` to `>=` on the rate, `>=` to `>` on the minimum and on the consecutive count, no window eviction, and transport errors not counted. The unmutated module passes, exit 0.
  - repair-04-04-d-dry-run.txt: the negative bounds, exit 2 each.
  - repair-04-04b-d-dry-run-manifest-flags.txt: the dry-run prints abortRule and the preflight line, exit 0.

**2. MINOR: throughput used the nominal level length.**
- `t_start = time.monotonic()` is taken before the threads start (py:355), and `elapsed` after `join()` (py:365).
- The level summary has `elapsedSeconds` (py:372), and `throughputPerSecond = requests / elapsed` (py:373). `seconds` stays as the nominal length.
- Check: repair-04-02-python-ast.txt and repair-04-06-final-recheck.txt show the parse passes and the self-test runs. The live value is not exercisable offline (see LIMITATIONS).

**3. MINOR: level percentiles mixed endpoint kinds.**
- Each level summary has `byKind` (py:377): per kind the requests, errors, errorRate, statusCounts, maxConsecutive5xxOrTransport, and latencyMsAll and latencyMs2xx p50, p95 and p99, from the same `stats` function (py:139-153).
- The per-kind lines are also printed (py:381-384).
- Check: repair-04-03-self-test.txt runs `stats` through `level_trip`. The live values are not exercisable offline.

**4. MINOR: D's unexpected failures used exit codes that already meant something else.**
- Terraform outputs are read through `tfo` (py:489-493), which uses `.get` and calls `die(..., 3)` when an output is missing. `alb_arn_suffix` stays optional.
- `ecs_counts` (py:262-286) no longer dies. A non-zero aws exit, unparsable output, or a null service (`json.loads("null")`) is recorded as `{"error": ...}` in ecs-<label>.json and returned.
- The load runs in try/except/finally (py:515-525): an exception records `summary["error"]` (the type name only) and returns 4, and summary.json is always written.
- `__main__` wraps `main` in `except Exception` (py:531-536), which prints the type only and exits 4.
- Exit codes are documented in the docstring (py:36-39) and the manifest (`exitCodes`, json:32): 0 every level ran, 1 abort rule, 2 usage, 3 precondition or non-2xx preflight, 4 unexpected.
- Check: repair-04-02-python-ast.txt and repair-04-06-final-recheck.txt. The error paths need AWS failures, so they were not exercised offline.

**5. MINOR: `set -e` failures in F skipped the documented exit codes and the evidence files.**
- The DLQ precondition read now has `|| die "get-queue-attributes failed for the DLQ" 3` (exp-f-failure-recovery.sh:113).
- The secret read now has `|| die "the API key secret could not be read" 3` (sh:120-121).
- send-message (sh:143-146): on failure the harness writes poison-sent.json with `messageId: null, sendFailed: true` and then exits with `die "send-message failed or timed out; the message may be queued: check both queues before any rerun" 4`. Writing that file first goes slightly beyond the review's fix, so a failed send still leaves evidence.
- `cp` of the last job poll is guarded with `if [ -f ... ]` (sh:176).
- In the DLQ loop, `Q=... || Q=error` and `D=... || D=error` (sh:197-198) record the failed poll in queue-depths.txt and keep polling.
- `logs()` (sh:209-215): on failure it writes `[]` and adds a FAIL check named `logs-<file>`.
- Exit 4 is documented in the usage text (sh:43-45) and the manifest (`exitCodes`, exp-f-failure-recovery-v1.json:25).
- Check: repair-04-01-bash-n.txt and repair-04-06-final-recheck.txt (`/bin/bash -n` under bash 3.2.57, exit 0; dry-run exit 0). The error paths need AWS and were not exercised offline.

**6. MINOR: the log-query start time had no margin for clock skew.**
- `START_MS=$((($(date +%s) - 300) * 1000))` (sh:91), with a one-line why comment (sh:90).
- The usage text states the margin (sh:29-30).
- Check: repair-04-06-final-recheck.txt (`bash -n` and dry-run).

**7. MINOR: the replay checks did not check the states their names claim.**
- replay-while-queued requires submit-2 `.state` QUEUED or RUNNING (sh:159-163). JobState values are QUEUED, RUNNING, SUCCEEDED and FAILED (modules/core/.../Enums.scala:104-105). The replay body is AsyncQueryResponse with `state` (Models.scala:135-136; Services.scala:236-239).
- replay-after-final requires submit-3 `.state` SUCCEEDED or FAILED and `[ -n "$JOB" ]` (sh:186-191). The second condition also closes an empty-equals-empty pass that the old check allowed.
- Both details carry the state.
- The usage text (sh:22-26) and the dry-run plan (sh:70-71) state the expected states.
- Check: repair-04-06-final-recheck.txt (dry-run shows the new lines, exit 0).

**8. MINOR: F did not refuse an inherited xtrace.**
- `case $- in *x*) die "xtrace is on; refusing to handle the API key (no bash -x, no exported SHELLOPTS)" 2 ;; esac` sits at sh:9.
- It runs directly after sourcing common.sh rather than after `set -euo pipefail` at line 5, because `die` is defined in common.sh. It still runs long before the key is read (sh:120).
- The usage text says so (sh:37).
- Check: repair-04-05-f-dry-run.txt and repair-04-06-final-recheck.txt (`/bin/bash -x ... --dry-run` exits 2 with the refusal).
- The exported-SHELLOPTS form was not tested; see COMMANDS RUN, the blocked command.

**9. MINOR: F can run only once per window, and nothing said so.**
- The manifest's `humanCleanup` now starts "One attempt per window; a rerun first needs the human to remove the DLQ message ..." (json:41), and a new `limits.attemptsPerWindow: 1` (json:24) was added.
- The usage text (sh:38-39), the dry-run precondition line (sh:68) and the non-empty-DLQ die message (sh:115-116) all say the same.
- Window 2 run notes: see MANUAL ACTION REQUIRED item 3.
- Check: repair-04-06-final-recheck.txt (the dry-run prints it) and repair-04-07b-manifest-jq-literal.txt (the manifest is still valid).

## Ruling 34 wait budget (each harness's own waits at least 5 min under maxMinutes)

- **D:**
  - The planned time is unchanged at 930 s (15.5 min): 4 x 180 s levels, 3 x 30 s pauses and 120 s post-observation.
  - Worst-case additions are the preflight (up to the number of paths x the 10 s timeout, 140 s for 14 paths) and one in-flight request per level (about 40 s), giving about 18.5 min against maxMinutes 25.
  - An abort shortens the run, because the post-observation is skipped.
- **F:**
  - The repair adds no wait. The DLQ deadline is still 22 min after the send, plus at most one 30 s sleep and one poll, so about 23 min against maxMinutes 30.

## COMMANDS RUN (all from /Users/achyutpatel/Developer/Cloud/ScholarChain)

Read-only Bash, all observed exit 0:
1. `grep` of Services.scala and Models.scala for the replay header and job response, `ls -l` of the four files, and `ls` of scratch/phase-14-logs. Before the edits, the two harnesses were -rwxr-xr-x and the manifests -rw-r--r--.
2. `sed -n` of Models.scala 135-160 and Services.scala 140-160 and 215-245, plus a `grep`.
3. `grep` of `enum JobState` in modules/core.

Offline checks:
4. Logs 01, 02 and 03: `/bin/bash --version | head -1` exit 0 (GNU bash 3.2.57(1)-release); `/bin/bash -n` on F exit 0; the python3 ast parse of D exit 0; `python3 --version` exit 0 (Python 3.13.5); `python3 -B load-tests/exp-d-query-load.py --self-test` exit 0.
5. Blocked, not executed: a combined dry-run command that used jq to read the manifest flags and also contained `env SHELLOPTS=xtrace`. guard-dangerous.sh blocked it with [secret-jq-env], the documented and accepted false positive for jq plus the word "env" (docs/agents.md, Hook limitations). It was not reshaped to get past the guard. The SHELLOPTS case was dropped, and the flags were passed literally with no jq.
6. Logs 04 and 05:
   - Positive cases: D dry-run exit 2 and F dry-run (both `./` and `/bin/bash`) exit 2. This was an invocation error on my side: zsh does not word-split `$D_ARGS` or `$F_ARGS`, so each harness got one argument, and argparse and the usage check rejected it.
   - Negative cases, as intended: D `--abort-error-rate 0.5` exit 2, `--abort-window-seconds 30` exit 2, `--abort-consecutive 11` exit 2; F `/bin/bash -x ... --dry-run` exit 2 (xtrace refused); F `--help` exit 0.
   - The xtrace log shows common.sh's non-secret constants, such as `PROFILE=` (empty), being traced before the refusal.
7. Logs 04b and 05b: D `--dry-run` with the literal manifest flags exit 0; F `./... --dlq-wait-minutes 22 --job-wait-minutes 5 --dry-run` exit 0 and `/bin/bash ...` exit 0.
8. `grep -n` for wording in D, exit 0. Then two Edits to F's dry-run plan text (findings 7 and 9), which 06 rechecks.
9. Logs 03b, 06, 07 and 08:
   - 03b: the mutation check exited 1 from a NameError in my test harness (`__file__` missing in the exec globals). It says nothing about the self-test.
   - 06: `/bin/bash --version`, `/bin/bash -n` on F, the ast parse, the self-test, the F dry-run under `/bin/bash` and under `./`, all exit 0; `/bin/bash -x ... --dry-run 2>/dev/null` exit 2.
   - 07: the jq check printed `true`, exit 0, for both manifests. Its `$` line shows a placeholder with the literal lines 64-68 printed below it by sed; 07b supersedes it.
   - 08: `ls -l` exit 0. Its `find` exit 0 with no output. Correction: log 08's echoed line says `find . -name __pycache__ -not -path "./target/*"`, but the command actually executed was `find load-tests experiments -name __pycache__`.
10. Log 03c: exit 1. The second mutant raised a TypeError, which the harness did not catch; the first mutant was killed.
11. Log 03d: exit 0. All 5 mutants were killed (2 by AssertionError, 3 by TypeError), the unmutated self-test passed, and `find load-tests experiments -name __pycache__` printed nothing.
12. `grep -n` of the final line numbers in the four files, and `sed -n 7,10p` of F, exit 0.
13. Log 07b: the literal run-experiment.sh lines 64-68 jq expression, echoed in full and run on each manifest, printed `true` with exit 0 for both. `sed -n 64,68p scripts/run-experiment.sh` was appended to show the source, exit 0.

Not run: aws, terraform, sbt, any git command, scripts/run-experiment.sh, any network call, MCP aws.

## TEST RESULTS

- `python3 -B load-tests/exp-d-query-load.py --self-test`: `==> OK self-test`, exit 0 (logs 03 and 06).
- Self-test mutation check: 5 of 5 weakenings killed, exit 0 (log 03d).
- No sbt check: no Scala or sbt source changed, and sbt is forbidden by this work order.

## VALIDATION RESULTS

- `/bin/bash -n` on F under GNU bash 3.2.57(1)-release: exit 0 (logs 01 and 06).
- python3 ast parse of D: exit 0 (logs 02 and 06).
- D `--dry-run` with the manifest flags: exit 0, and no terraform, AWS or HTTP call (log 04b). Out-of-bounds abort flags exit 2 (log 04).
- F `--dry-run` with the manifest flags: exit 0 under both `./` (env bash) and `/bin/bash` (logs 05b and 06). F under `bash -x` exits 2 (logs 05 and 06).
- The run-experiment.sh jq expression (literal): `true` and exit 0 for both manifests (log 07b).
- `ls -l` after the edits (log 08): exp-f-failure-recovery.sh -rwxr-xr-x, exp-d-query-load.py -rwxr-xr-x, both manifests -rw-r--r--. The modes are kept.
- No `__pycache__` under load-tests/ or experiments/ (logs 08 and 03d).

## LIMITATIONS AND THREATS TO VALIDITY

- The worker, stop event, preflight, ecs error paths and F's AWS error paths were not exercised end to end. Doing that needs an HTTP server or AWS, and this work order forbids network calls. Only the abort-rule logic (`AbortMonitor`, `rule_reason`, `stats`, `level_trip`) is unit-tested.
- The exported-SHELLOPTS form of finding 8 was not tested, because of the guard block on command 5. It relies on bash setting `$-` from SHELLOPTS at startup.
- D's smoke-record parser is still unexercised against a real query.json. No PASS smoke record exists yet, so the dry-run used placeholders. This evidence gap from code review 4 remains.
- The 10 s window and the minimum of 20 mean a very slow failing kind is caught by the consecutive rule, not the rate rule. This is by design.

## ASSUMPTIONS

- AsyncQueryResponse's `state` field is serialized as the JobState name (QUEUED, RUNNING, SUCCEEDED, FAILED), as code review 4 found for the other enum fields.
- The existing jq selectors `.jobId` and `.state` match the submit response (Models.scala:135-136).
- Exit code 4 has a harness-local meaning, as the review prescribes. It differs from common.sh's "approval mismatch" and is documented in each harness.

## RISKS

- If the human runs F with an exported SHELLOPTS that contains xtrace, the refusal fires only after common.sh has been sourced. Its traced lines hold no secret.
- D's tighter rule (0.05) may abort on a small burst of transient ALB 5xx at c=8. That is the intended trade-off, and the evidence (abortedAt, abortWindowCounts) records it.
- Finding 10 (run-experiment.sh signals only the command's PID) stays residual by ruling 34. The other residual risks of code review 4 are unchanged.

## MANUAL ACTION REQUIRED

1. Code review 5 (Part B) of this repair.
2. After review and commit, the human replays `./scripts/run-experiment.sh --manifest experiments/manifests/exp-d-query-load-v1.json --profile scholarchain-sdk --dry-run`, and the same for exp-f-failure-recovery-v1.json, and records the output.
3. Window 2 run notes:
   - F runs once per window. A rerun first needs the human to remove the DLQ message, which is a human-only destructive action.
   - F exit 4 means the send may have queued the message: check both queues before any rerun.
   - D exit 3 after "preflight" means a path was not 2xx before any load ran.
   - Neither harness runs under `bash -x` or with SHELLOPTS exported (ruling 34).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code review 5 (code-reviewer, Part B), then the human review and commit of the four files. The human replays the run-experiment.sh `--dry-run`, and the window 2 runs come after load-stores.sh and a PASS smoke-test.sh.
