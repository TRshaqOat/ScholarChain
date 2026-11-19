# Phase 15+16 repair-01 (code review 1 findings m1 to m4, m6, m7; one round)

Session 3. Work order: the "Repair-01" section of prompt.md, under rulings 4 and 6. m5 is accepted by ruling 6 and is not repaired. Each part's agent report is appended verbatim below.

## Part A (infra-engineer)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 15+16, session 3, repair-01, Part A. Code review 1 findings m1, m2, m4 and m6 in scripts/load-stores.sh and scripts/README.md (Phase 15 rulings 3, 4 and 6). Work orders 1 to 3 were left in place and not reverted.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-a-checks.log (bash -n, shellcheck availability, dry-runs, usage-error exits, --help)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-a-fixture.log (the full fixture harness source, then its output)
- The harness itself is outside the repository at /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/357bb839-b6d7-4ed2-9a8d-b9f85b7147b7/scratchpad/r01-fixture.sh. Its full text is copied verbatim into r01-a-fixture.log.
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/load-stores.sh (now 281 lines)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/README.md
FILES DELETED: none

STATEFUL DESTROY OR REPLACE: none (no Terraform change)
PLAN SUMMARY: not applicable. No Terraform file changed, and no plan was run (ruling 4).
IAM CHANGES: none
HIGH-COST RESOURCES AND BOUNDS: none changed. The README window 3 command sets `--neptune-timeout-minutes 180`, the script's existing maximum, per ruling 6.

PER-FINDING:
- m1 (load-stores.sh):
  - New `task_lines` (:175-191) reads with `filter-log-events ... --output json`. An API error now ends in `die ... 1` naming the pattern, log group and stream. The old `|| true` is gone. Unreadable output also exits 1.
  - New `expect_line` (:193-204): after a task exits 0, the expected line gets 1 read plus up to 3 re-reads, `sleep 15` apart. If it is still missing, the script exits 1 with "the task exited 0 but no line matches <pattern> after 3 re-reads; CloudWatch <group> <stream>".
  - `run_loader_task` (:206-237) takes a third argument, the regex of the expected line, and calls `expect_line` when the exit code is 0 (:226-229).
  - The start step (:246) passes `'started load [^ ;]+'`, so the re-read now also happens before the start-line check. The existing `[ -n "$LOAD_ID" ]` die stays as a backstop.
  - The status step (:253) passes `"^load $LOAD_ID status [A-Z_]+"`.
  - `""` is removed from the in-progress set (:259). An empty status now reaches the `*)` die (exit 1).
  - Check: fixture cases 2, 3 and 4 in r01-a-fixture.log.
    - Case 2: the line appears after 2 empty reads; found after 3 calls.
    - Case 3: never appears; exit 1 after 4 calls (1 read plus 3 re-reads), and the message names `/fixture/neptune-loader` and the stream.
    - Case 4: the fake API fails with AccessDeniedException; exit 1 after 1 call, the error is printed, and the FAIL line names filter-log-events and the stream.
- m4 (load-stores.sh):
  - The status filter is now `?"load <id> status" ?"errors parsing"` (:253).
  - `ERROR_PATTERN` (:173) is `?"error:" ?"errors parsing" ?"errorCode" ?"failed" ?"refused" ?"pre-load" ?"violations" ?"nothing loaded" ?"Exception"`. These cover the failure lines LoadNeptuneCli prints: `error:`, `errors parsing`, errorLogs JSON with `errorCode`, `stage:`/`staging refused`, `pre-load`, Cli.report `violations`, and Cli.exitCode `<name> failed: <Exception>`.
  - On a non-zero exit (:230-236), the script first prints the stoppedReason as a WARN, then saves the result lines and a second read of the error lines to `neptune-<label>-<task>-errors.txt`, prints both, and exits 1 as before.
  - Every recorded line passes through `SECRET_RE` (:171), `x-amz-security-token|aws_session_token|SessionToken|Authorization:|Credential=`, matched with `grep -i`. That covers `X-Amz-Security-Token` and is a superset of the six required strings.
  - A matching line is dropped, never written. The record gets the line "(N line(s) dropped: could carry a credential)", and a `==> WARN` gives the same count.
  - Each CloudWatch event becomes exactly one line, with tabs and newlines replaced by spaces through jq. The old `tr '\t' '\n'` could split a key from its token value onto separate lines.
  - Check: fixture case 1 uses 6 token-bearing lines (one per required string, both cases of the security-token header) and 4 safe lines. Results:
    - no `FAKE` value and no rule term is written;
    - the count of 6 appears in the record and in the WARN;
    - the status, `errors parsing` and `errorCode` lines are kept;
    - a tabbed event stays one line;
    - the record has 5 lines;
    - the script's status sed parses LOAD_IN_PROGRESS.
  - Fixture case 5: the error pattern reaches the API unchanged.
- m2 (README.md):
  - The window 3 command (:83-86) adds `--neptune-timeout-minutes 180`. Rationale: up to 108 minutes in window 2 (Phase 14 ruling 43), and 180 set by Phase 15 ruling 6.
  - The load-stores section (:290-291) states the default of 60, the maximum of 180, and that window 3 uses 180.
  - Check: the dry-run of the exact window 3 command prints "until LOAD_COMPLETED (180 min)" and exits 0. `--neptune-timeout-minutes 181` exits 2 (r01-a-checks.log).
- m6 (README.md, flow :56-95):
  - The window 2 block now shows the deploy.sh rows as human-run (Phase 14 ruling 36).
  - A new window 3 block says every script is human-run, with aws login before each long script and no agent running any step (Phase 15 ruling 3). Its order:
    1. plan
    2. deploy.sh apply
    3. secret and state backup
    4. Neptune scholarchain-course-graph-1 to db.r6g.large (Phase 14 rulings 42 and 43)
    5. load-stores.sh
    6. Neptune back to db.t4g.medium
    7. deploy.sh --resume
    8. Neptune warm-up (Phase 14 rulings 46 and 47), then smoke-test.sh
    9. demonstration recording and exp-d-scale-v1
    10. archive-evidence.sh, cleanup.sh --stage runtime, state backup
  - Check: read-back by grep (line numbers above). This finding is documentation only.

SCRIPT GATES: load-stores.sh. The existing gates are unchanged: clean tree, profile, Region, account, terraform outputs, runtime_enabled, run-ID, concurrency and timeout validation, and `--skip` validation. New gates are fail-closed log reads after exit 0 (re-read, then exit 1), filter-log-events API error as exit 1, and dropping credential-bearing lines from every Neptune record. Exit codes are unchanged: 0 done; 1 loader, task, log-read or status failure; 2 usage; 3 precondition; 6 timeout. Environment variables: AWS_PROFILE (or --profile). Human approval points: unchanged (human-run; the applied runtime plan).
SECURITY SCAN: not applicable (no Terraform change). shellcheck is not installed, so that step was SKIPPED.

COMMANDS RUN:
- Read-only: `cat`, `grep`, `sed -n` on the script, common.sh, LoadNeptuneCli.scala, Cli.scala, phase-15 and phase-14 prompt.md, and operations/deployments/window-2/neptune-load-error-details.txt
- aws-docs read of the CloudWatch filter pattern syntax page, to confirm `?"phrase"` OR terms and case sensitivity
- `python3 -I -` to make a one-line edit to the scratchpad harness (`source <(...)` replaced by a temp file, for bash 3.2)
- `mkdir -p scratch/phase-15-logs`
- `/bin/bash r01-fixture.sh <repo>` writing to r01-a-fixture.log
- `bash -n`, `/bin/bash -n`, `command -v shellcheck`, five `scripts/load-stores.sh ... --dry-run` invocations and one `--help`, writing to r01-a-checks.log
- No git, aws, terraform, sbt, Bedrock or Haiku call.

TEST RESULTS: fixture check under /bin/bash 3.2.57: 20 passed, 0 failed, exit 0.
VALIDATION RESULTS:
- `bash -n` exit 0, and `/bin/bash -n` exit 0.
- shellcheck: not installed (SKIPPED).
- Window 3 dry-run (`--skip s3-store-conversion,s3-embed,s3-window-summary --neptune-timeout-minutes 180`): exit 0, 180 min.
- Dry-run with no `--skip`: exit 0, 60 min.
- `--neptune-timeout-minutes 181`: exit 2.
- `--skip bogus`: exit 2.
- Missing `--store-run`: exit 2.
- `--help` shows the re-read, errors-file and credential lines.
- The README edits came after `bash -n` and the dry-runs; they do not touch the script.

ASSUMPTIONS:
- With `--output json`, the AWS CLI follows every filter-log-events page and applies `--query` to the merged result, as the earlier text form relied on.
- CloudWatch matches quoted `?"..."` terms as case-sensitive phrases, per the AWS doc page read.
- The fixture extracts the real functions from load-stores.sh with sed (top-level `SECRET_RE`, `ERROR_PATTERN`, `task_lines`, `expect_line`). It does not run `run_loader_task` end to end, because that needs ECS, Terraform outputs and a clean tree.

RISKS:
- `ERROR_PATTERN` terms such as "failed" and "Exception" can also match DEBUG SDK lines. The error file may then be large, but only after a failed task, and every line still passes the credential filter.
- The credential filter matches the six required strings only. A token value logged on a line with none of those keywords would not be caught. The Phase 14 ruling 41 follow-up, setting the SDK log level to INFO, is still the root fix.
- On a non-zero exit, if the stream does not exist and filter-log-events returns an error, the script exits 1 from the log read. The stoppedReason WARN is printed before that.
- Nothing has run against AWS.

MANUAL ACTION REQUIRED: the human reviews the diff, and the code-reviewer re-reviews m1, m2, m4 and m6 before the window 3 plan pins HEAD. Install shellcheck if a shellcheck result is wanted. Not done here (out of Part A): the ruling 6 README note about reverting service_cpu_target_percent from 15 to 60 after window 3.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer verdict on repair-01 and human acceptance. Window 3 scripts are human-run only (ruling 3).

Skipped or not checked: no end-to-end run of the Neptune path with a fake ECS, and no shellcheck. Risk to know: the error read's breadth and the keyword-based token filter, as stated above.

## Part B (experiment-engineer)

STATUS: COMPLETE
OWNER: experiment-engineer
WORK ORDER: Phase 15+16, session 3, repair-01, Part B (code review 1 finding m3, Phase 15 ruling 6). Set exp-d-scale-v1 maxMinutes to 45. When --poll-seconds is above 0, give the three ecs_counts calls a subprocess timeout. Write collect.json after every collection call. exp-d-query-load-v1 must behave exactly as before. This was authoring and offline checking only: no live run, no git, no aws, no terraform, no Bedrock or Haiku call.

FILES CREATED (logs only, under scratch/phase-15-logs/):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-b-self-test.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-b-mutations.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-b-dry-run-v1-diff.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-b-run-experiment-dry-run.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-b-harness-diff.log (the full unified diff, work order 3 state against repaired)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-15-logs/r01-b-checks-script.log (the check script I ran, for replay)

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/load-tests/exp-d-query-load.py (sha256 19c4acfe070f293ee61adac295407a71956fc4443120d71d94c11ac09ab4b785 before, f71b900567a6f743c3214d3975d9c2bac1736e333eec2484a90859c4d6f3faa7 after). Line numbers below are in the repaired file:
  - m3 part 1, ecs_counts timeout: `ecs_counts(cluster, label, out_dir, timeout=None)` (:430-447). It passes `timeout=timeout` to subprocess.run and catches `subprocess.TimeoutExpired`, recording it as error "TimeoutExpired". Like the other errors there, this is recorded and never fatal. In run_load, `ecs_timeout = POLL_CALL_TIMEOUT_S if a.poll_seconds else None` (:694) feeds the three calls (:698 before-c<n>, :702 after-c<n>, :716 post-observe). POLL_CALL_TIMEOUT_S is the existing 20 s. When --poll-seconds is 0 the timeout is None, so the subprocess.run call is the same as before.
  - m3 part 2, collect.json written after every call: collect() (:528-583) keeps the index in a `head` dict with the same keys in the same order as before. metricsEndUtc, targetGroupArn and skippedMetricQueries start as null and are filled once known (:578). `save()` (:538) writes collect.json.tmp and then calls os.replace, so a kill mid-write never leaves a truncated file. save() is called after every get() (:552), after the "no target-tracking alarm" index entry (:569) and once at the end (:583). The final collect.json content is unchanged in shape.
  - COLLECT_BUDGET_S changed from 150 to 90 (:84), because the margin arithmetic required it (see VALIDATION RESULTS). The per-call cap stays at min(60 s, budget left).
  - Docstring (:40-46): collect.json is rewritten after every call, and the three describe-services reads time out. This text appears in --help only, not in any dry-run output.
  - Self-test (:264-268, :303, :320, :323, :348-361): the fake subprocess.run now also records the `timeout` keyword and how many calls collect.json indexed at that moment. New assertions:
    (a) v1: all three ecs_counts calls have timeout None.
    (b) D-scale: all three ecs_counts calls have timeout 20.
    (c) The seven collection calls see collect.json indexes of [None, 1, 2, 3, 4, 5, 6] calls.
    (d) In the failure scenario, get-metric-data sees 4 indexed calls (including the no-alarm entry), and no collect.json.tmp remains.
    (e) A new D-scale run where describe-services times out: exit 0, ecsBefore and ecsPostObserve are {"error": "TimeoutExpired"}, ecs-after-c1.json records TimeoutExpired, and collect.json exists.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/manifests/exp-d-scale-v1.json (sha256 d156f109... before, 7afadc5773e674d166b95912aec56e670a2e4f551398f2fea9695f7501cc8033 after). Changes:
  - limits.maxMinutes changed from 40 to 45 (ruling 6).
  - New limits.maxMinutesReason with the worst-case arithmetic.
  - plannedBreakdown "collection at most 150 s" changed to "at most 90 s".
  - collectBudgetSeconds changed from 150 to 90.
  - Nothing else changed. plannedMinutes stays 38.
FILES DELETED: none (raw results: none touched)
MANIFEST: experiments/manifests/exp-d-scale-v1.json (uncommitted; not run). exp-d-query-load-v1.json was not modified.
RUN IDENTIFIERS: none (no run)
RAW RESULT LOCATIONS AND CHECKSUMS: none produced. I read experiments/results/exp-d-query-load-v1/20261118T063904Z (window 2) read-only, for timings only.
OBSERVATIONS: none (no run). Window 2 timings, read from that run's raw files: command start 06:39:04Z, inputs.json 06:39:06.243Z, preflight 06:39:07.181Z, so setup took about 3 s; the six preflight GETs took 0.09 to 0.49 s each; ecs-before-c1 completed at 06:39:09.479Z, about 2.3 s after the preflight began; levels took 180.019 to 180.263 s, a worker tail of at most 0.26 s.
ESTIMATED COST: $0 (offline only)
FAILED OR ANOMALOUS RUNS: none

COMMANDS RUN:
- Read-only: cat, sed, grep and jq on the manifests, logs and window 2 raw files; shasum.
- I copied the work order 3 harness and manifest to the scratchpad as the pre-repair baseline (pre/), with an operations symlink.
- I edited with the Edit tool. One `python3 -I -` heredoc for the self-test edits was blocked by guard-dangerous.sh [secret-env-dump], because the Python text contained `kw.get("env")`. This is the documented false-positive class, and docs/agents.md says to use the file tools for such content, so I made those same edits with the Edit tool. A preceding `python3 -I -` heredoc (docstring and COLLECT_BUDGET_S) ran without a block.
- `bash <scratchpad>/r01b-checks.sh`, run twice. The first run compared pre/ and post/ copies at different paths, so its only v1 dry-run difference was the "ids from:" scratch path. The second run uses one directory for both sides and prints the failing assertion for each mutation; its logs overwrote the first. The script runs:
  - `python3 -I load-tests/exp-d-query-load.py --self-test`
  - five mutated scratch copies with --self-test, plus an importlib traceback of the failing assertion
  - pre and post `--dry-run` with the v1 and the D-scale manifest args, then diff
  - `jq empty` and a jq limits print on the manifest
  - `scripts/run-experiment.sh --manifest experiments/manifests/exp-d-scale-v1.json --dry-run`
  - `scripts/run-experiment.sh --manifest experiments/manifests/exp-d-query-load-v1.json --dry-run`
  - `test -e load-tests/__pycache__`
- `diff -u` of the harness, pre against repaired (r01-b-harness-diff.log).
- I did not run python3 -m py_compile, git, aws, terraform or sbt.

TEST RESULTS:
- `python3 -I load-tests/exp-d-query-load.py --self-test`: "==> OK self-test", exit=0, real 9.7 s (r01-b-self-test.log).
- Mutations (r01-b-mutations.log). Each one removes one part of the repair, and each self-test fails with exit=4 at the stated assertion:
  - a: ecs_timeout = None in D-scale. Fails at :320 (the 3 ecs_counts calls have timeout 20).
  - b: timeout also in v1. Fails at :303 (v1 has no timeout).
  - c: TimeoutExpired not caught in ecs_counts. Fails at :355 `assert code == 0` with AssertionError: 4.
  - d: no save() after each get(). Fails at :323, observed [None, None, None, None, None, None, None].
  - e: no save() after the no-alarm entry. Fails at :348.

VALIDATION RESULTS:
- v1 dry-run diff with the exp-d-query-load-v1 args, work order 3 harness against repaired, same path: IDENTICAL; both exit=0 (r01-b-dry-run-v1-diff.log). The self-test's v1 case (exact file list, three describe-services calls, no TZ override, timeout None) also passes.
- D-scale dry-run diff: only two lines differ, `"collectBudgetSeconds": 150` becomes `90` in bounds, and "at most 150 s" becomes "at most 90 s"; both exit=0.
- run-experiment.sh --dry-run, exp-d-scale-v1: exit=0, "... --poll-seconds 30 for at most 45 min".
- run-experiment.sh --dry-run, exp-d-query-load-v1: exit=0, "for at most 25 min" (unchanged).
- `jq empty` on exp-d-scale-v1.json: exit=0. Limits now read {"maxMinutes":45,"plannedMinutes":38,"collectBudgetSeconds":90}. The manifest diff shows only the four changes listed above.
- load-tests/__pycache__: absent after all checks (`test -e` printed "absent"; `ls load-tests` shows only exp-d-query-load.py).
- Worst case against 45 minutes, with every wait at its bound:
  - setup about 3 s (window 2, unbounded calls)
  - preflight 60 s (6 GETs at the 10 s timeout; a timed-out GET is non-2xx and ends the run with exit 3, so 60 s overstates it)
  - three ecs_counts calls, 60 s (20 s each)
  - load 600 s
  - worker tail 30 s (reviewer's figure)
  - post-observation 1,500 s
  - poller join 20 s
  - collection 90 s
  - Total: 2,363 s, about 39.4 min, leaving 5.6 min under 45. That meets ruling 6's "at least 5 minutes" and Phase 14 ruling 34.
- With COLLECT_BUDGET_S left at 150 the same sum is 2,423 s, about 40.4 min, leaving only 4.6 min. That is under the 5-minute rule, so I cut the budget to the reviewer's suggested 90 s. The expected (not worst) total from window 2 timings is about 35.5 min.

ASSUMPTIONS:
- The work order 3 working-tree harness (sha256 19c4acfe...) is the right baseline for "behaves exactly as before", because I cannot read HEAD without git. Work order 3's log showed its v1 dry-run IDENTICAL to the HEAD harness, so the chain holds by transitivity.
- 90 s is enough for the seven collection calls, which typically take about 2 s each by window 2's describe-services timing.
- The 20 s POLL_CALL_TIMEOUT_S is right for the ecs_counts reads; window 2 took about 2 s per call.

RISKS:
- The setup calls (terraform output, sts, secretsmanager) still have no subprocess timeout. This is out of m3's text and unchanged from v1, and they took about 3 s in window 2.
- A TERM during collection still loses whatever call is in flight. collect.json then holds every call completed so far, but metricsEndUtc, targetGroupArn and skippedMetricQueries stay null if the TERM lands before the metric query is built.
- A kill exactly during save() can leave collect.json.tmp beside the previous complete collect.json. That is visible and never truncated.
- The --help text changed (docstring only). v1's dry-run and runtime outputs did not.

MANUAL ACTION REQUIRED: The human reviews and commits both files before window 3, because run-experiment.sh requires the manifest and harness tracked and unchanged from HEAD. The human also accepts or rejects the COLLECT_BUDGET_S change from 150 to 90, which Part B's text did not name and which I made on the margin arithmetic, as the orchestrator permitted.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer check of repair-01 Part B; human review and commit before the window 3 plan pins HEAD; documentation-engineer write-up only after the window 3 results are frozen.

Not checked: no live AWS behavior. The timeout and per-call write are proven only against the fake subprocess.run.

## Part C (rag-engineer)

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 15+16, session 3, repair-01, Part C. Fix code review 1 finding m7: RUNTIME.md section 14 ("IDs" paragraph) did not list the run-ID input `retrieval=<MODE>`, which is added when the retrieval mode is not FUSED (section 24). This was a documentation-only change, with no code change.
FILES CREATED: none
FILES MODIFIED: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md (line 351 only)
FILES DELETED: none

CHANGE (line 351, section 14 "IDs"):
- The list of runtime run-ID inputs used to end with: `verifier version (`verifier=verifier-v8`; ruling 29)], 1)`.
- It now ends with: `verifier version (`verifier=verifier-v8`; ruling 29), and `retrieval=<MODE>` (the enum name, `GRAPH_ONLY` or `VECTOR_ONLY`) when the retrieval mode is not FUSED (section 24)], 1)`.

CODE CHECKED AGAINST:
- modules/rag/src/main/scala/scholarchain/rag/runtime/Workflow.scala:114: `) ++ Option.when(retrieval != RetrievalMode.FUSED)(s"retrieval=$retrieval"),`. The input is appended last, after `verifier=$verifier` (line 113). This matches where the doc now places it.
- modules/rag/src/main/scala/scholarchain/rag/runtime/Contracts.scala:147-151: `enum RetrievalMode: case FUSED, GRAPH_ONLY, VECTOR_ONLY`. `toString` is not overridden; only `flag` gives the lower-case CLI form. So `$retrieval` renders as the enum name (`GRAPH_ONLY` or `VECTOR_ONLY`), not the CLI flag (`graph-only` or `vector-only`).
- modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala:49: `val Version = "verifier-v8"`. The existing `verifier=verifier-v8` text in the same sentence is still accurate.
- The new wording agrees with section 24, line 630 (`retrieval=<MODE>`).

ALLOWED PLAN OPERATIONS: unchanged
PROMPT, SCHEMA, AND MODEL VERSIONS: unchanged (rag-runtime-v3, verifier-v8, rag-audit-v3)
IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR: unchanged
VERIFICATION RULES: unchanged
BENCHMARK RESULTS: not run (documentation-only)
KNOWN FAILURE MODES: none introduced

COMMANDS RUN:
- `grep -n "RetrievalMode" .../Contracts.scala; grep -n "enum RetrievalMode" -A8 .../Contracts.scala`: showed the enum at lines 147-154 as quoted above.
- `grep -n 'retrieval=<MODE>' modules/rag/RUNTIME.md`: matches at lines 351 (new) and 630 (existing).
- `grep -rn 'val Version' modules/rag/src/main/scala/scholarchain/rag/runtime/ | grep -i verif`: `Verifier.scala:49: val Version = "verifier-v8"`.
- File reads (Read tool): RUNTIME.md lines 340-359 and 615-633, and Workflow.scala lines 95-129.
- No git, aws, terraform, Bedrock or Haiku call. No sbt run. No logs written.

TEST RESULTS: none run. This is a Markdown-only change with no Scala or sbt source touched, so `sbt check` is not needed under the session rules.
VALIDATION RESULTS: the grep above confirms the new text is at line 351, and the code lines it was checked against are quoted above.
ASSUMPTIONS: "the enum name" is the right wording for how the mode is rendered, because the Scala 3 enum's default `toString` is used.
RISKS: none. Documentation only; no code, wire form or run-ID value changes.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of repair-01 (m7 closed), then human acceptance.

Not checked: the rest of RUNTIME.md was not re-audited against the code beyond the m7 scope.
