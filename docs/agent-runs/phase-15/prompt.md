# Phase 15+16 prompt: experiments and evaluation (spec Phases 15 and 16, merged by D-10)

## Human rulings

1. Scope (D-10, D-13). Phases 15 and 16 run as one phase in docs/agent-runs/phase-15/. Experiments: A is the 25 percent EMR run at deef295 (Phase 14 ruling 21), with the Hadoop runs at 25, 50 and 100 percent as size scaling (D-13 replaces B); C stays deferred (D-10); D is the window 2 run (Phase 14 ruling 47) plus D-scale in window 3 (ruling 2); E is a local run (Phase 14 ruling 31, work order 1); F is the window 2 run (Phase 14 ruling 46). Phase 16 covers the spec's evaluation groups 1 to 7. Relevance labels and answer ratings are the human's only: no agent produces, suggests or pre-fills them. Raw results under experiments/results/ are immutable, and failed runs are reported, not dropped.

2. Scale-out design. Evidence: operations/deployments/window-2/post-exp-d-cloudwatch.txt. During experiment D (window 2), graph-evidence-api averaged 20 to 27 percent CPU at concurrency 8 (about 2,150 requests per minute) while Neptune (db.t4g.medium) reached 80 to 93 percent CPU. The policy's 60 percent CPU target cannot be reached before Neptune saturates, so window 2 showed no scale-out. For window 3, the target-tracking CPU target becomes a Terraform variable (default 60, the production value), and course.tfvars sets 15. The manifest and the report state 15 as an experiment setting, not a tuned production value. At concurrency 8 the service ran above 15 percent, and it idles near 0.1 percent, so one sustained level should give one scale-out (3 minutes above target) and one scale-in (about 15 minutes below). Expected finding, to be reported either way: a second task adds no throughput while Neptune is the bottleneck. Not chosen: an evidence-only load at the 60 percent target (nothing shows it can reach 60 percent) and ALB request-count tracking (a larger change with the same threshold question).

3. Window 3 (cloud). Purpose: experiment D-scale (exp-d-scale-v1, work order 3), the demonstration recording (Phase 14 ruling 49), and a smoke test. Order:
   - Before the plan, the human restores and then force-deletes the secret scholarchain-course/api-key, which has been pending deletion since 2026-11-18 12:00 CST; a new secret cannot take that name during the 7-day recovery window.
   - plan.sh: expected 89 to add, 0 to change (after work order 2 part B), 0 to destroy.
   - apply; set the secret; back up the state.
   - load-stores.sh for Neptune, DynamoDB and Vectors only, because the S3 objects from window 2 are still in the bucket (work order 2 part C). The Neptune bulk load runs on db.r6g.large, then the instance goes back to db.t4g.medium (Phase 14 rulings 42 and 43).
   - deploy.sh --resume, a Neptune warm-up, the smoke test, the demonstration recording, D-scale, cleanup runtime, and a state backup.
   - Every script is human-run, with aws login immediately before each long script.
   - Budget: at most 10 h of runtime. Stop and clean up if the month-to-date cost before credits reaches $23 (D-13 keeps the total under $25).
   - HEAD is pinned from the plan until cleanup: nothing is committed in between, and mid-window rulings go to operations/rulings/.

4. Work orders and review. Work orders 1 to 3 run in one Claude Code session (session 1, Ponytail ON). Code review of all three follows in session 2 (code-reviewer, Ponytail OFF), with one repair round. Agents run no git, no aws, no terraform plan, apply or state command, and no Bedrock or Haiku call; they test with fakes, --self-test and --dry-run only. Every report is appended verbatim to agent-report.md, one section per work order. A work order that cannot be done within its scope stops with BLOCKED-ON and the reason.

## Work order 1 (rag-engineer): retrieval modes for experiment E (Phase 14 ruling 31)

Goal: run the same benchmark questions under three retrieval modes locally, and record what each mode retrieved, so that recall at K and MRR can be computed later against human relevance labels.

1. Add an optional retrieval mode: FUSED (the default, today's behaviour), GRAPH_ONLY and VECTOR_ONLY. The planner and the fusion stage honour it: GRAPH_ONLY makes no vector query; VECTOR_ONLY retrieves no graph facts beyond what name resolution needs. Document exactly which stages each mode runs.
2. FUSED must leave today's behaviour unchanged. Every existing test still passes unchanged, and one test shows the default path is the same as before.
3. Record the mode in the audit record and in the benchmark output. If a schema version must change, bump it with tests and say so in the report.
4. BenchmarkCli: add a --retrieval-mode flag with the values fused, graph-only and vector-only (default fused).
5. For each question and mode, BenchmarkCli writes the ranked list of retrieved items to a file in its output directory: rank, source (graph, vector or fused) and stable ID (evidence ID, vector key or graph element ID). It computes no score against labels; labels are the human's (ruling 1).
6. Tests: fake-mode tests for all three modes (which stages run, the audit field, the CLI flag, the ranked-list file). No live run.
7. Docs: a retrieval-modes section in the rag runtime document, including ruling 31's limitation that the vector side is the file-backed export of the live vectors, not the S3 Vectors service.

Not in scope: the services API, Terraform, scripts/, experiments/benchmark/ and labels/. Report: the files changed, the tests run with counts, and logs under scratch/phase-15-logs/.

## Work order 2 (infra-engineer): window 3 preparation (rulings 2 and 3)

A. CPU target variable: replace the hard-coded target_value = 60 in infra/terraform/modules/ecs/main.tf with a module input. Add the root variable service_cpu_target_percent (number, default 60, validation: a whole number from 10 to 90), pass it to the ecs module, and set service_cpu_target_percent = 15 in infra/terraform/envs/course/course.tfvars with a comment citing Phase 15 ruling 2.

B. SSE-C pin: the data bucket's server-side encryption rule declares blocked_encryption_types = ["SSE-C"] (Phase 14 rulings 38 and 48), so a plan from the current state shows no change for the data bucket.

C. scripts/load-stores.sh:
1. Add a flag that skips named completed steps (for example --skip s3-store-conversion,s3-embed,s3-window-summary). An unknown step name is a usage error (exit 2), and the skipped steps are recorded in the run record.
2. Read the Neptune start task's "started load <id>" line with filter-log-events, following every page, instead of the first page of get-log-events (Phase 14 ruling 41).

   Keep the existing exit codes and records. Update scripts/README.md, including the window order: apply, secret, loads, then deploy.sh --resume (Phase 14 ruling 39).

Not in scope: scripts/cleanup.sh (Phase 12 ruling 10), any plan, apply or aws call. Checks: terraform fmt and validate, bash -n, and the script self-tests or --dry-run where they exist. The report states the expected window 3 plan from the current state, derived from code (expected 89 to add, 0 to change, 0 to destroy).

## Work order 3 (experiment-engineer): experiment D-scale (ruling 2)

1. A new manifest, experiments/manifests/exp-d-scale-v1.json: the same request mix as exp-d-query-load-v1, one level at concurrency 8 for 600 s, then 1,500 s of post-observation. maxMinutes 40, no Haiku or Bedrock call. It states the CPU target of 15 percent as an experiment setting (ruling 2) and the expected finding.
2. Harness (load-tests/exp-d-query-load.py):
   a. --poll-seconds (off by default; the new manifest sets 30): during the levels and the post-observation, sample graph-evidence-api's desired, running and pending counts with UTC timestamps into raw/ecs-timeline.csv (read-only describe-services).
   b. After the post-observation, collect into raw/, read-only and with UTC timestamps:
      - the Application Auto Scaling scaling activities for the service;
      - the ECS service events;
      - the alarm history of the target-tracking alarms;
      - CloudWatch 1-minute metrics for the run interval: ECS CPU and memory, ALB HealthyHostCount for the graph-evidence-api target group (found through describe-services), RequestCount, TargetResponseTime p50 and p95, HTTPCode_Target_5XX_Count, and Neptune CPU.
      
      A failed collection is recorded and does not fail the run or hide the load result.
3. exp-d-query-load-v1 must behave exactly as before; tests show it.
4. Self-tests with fakes and --dry-run. No aws call by the agent.

Not in scope: Terraform, scripts/, the services, experiments/benchmark/ and labels/.

5. Session 1 (work orders 1 to 3): all COMPLETE (agent-report.md). Accepted read-only deviations, recorded and not to be repeated: rag-engineer ran git status --porcelain once; experiment-engineer ran aws --version once (no API call). Guard false positives in work order 2 ([cleanup-script] on the text "cleanup.sh" in a heredoc; [protected-path-write] on a jq read under ~/.claude with tee) were not worked around. The human removed load-tests/__pycache__ (left by py_compile); a .gitignore rule for it is a build-engineer follow-up and a Phase 17 note. Code review 1 (session 2) reviews work orders 1 to 3 from the change set in evidence/code-review-1/ and the working tree; one repair round follows only if it requests changes.

6. Code review 1: APPROVE, 0 critical / 0 major / 7 minor (review-report.md). Human decisions:
   - m1, m2, m4 and m6 (infra-engineer), m3 (experiment-engineer) and m7 (rag-engineer) are repaired in repair-01 (one round, ruling 4) before the window 3 plan pins HEAD.
   - m2: the window 3 load command uses --neptune-timeout-minutes 180.
   - m3: exp-d-scale-v1 maxMinutes rises to 45 (at least 5 minutes of margin, Phase 14 ruling 34).
   - m5 is accepted as a limitation: the ranked list is the post-budget bundle, so recall at K is capped at the bundle size. The E method and the report state this.
   - rag-audit-v3 is kept with the default-valued retrievalMode field (the FUSED wire form is unchanged); this exception to the version convention is ratified.
   - VECTOR_ONLY without the identity record is accepted as intended: the verifier requires graph evidence, so VECTOR_ONLY answers abstain with NO_VERIFIED_CLAIM. E reports this as a finding; the Haiku calls it spends stay within ruling 31's bound.
   - At the window 3 plan, the human accepts at most one in-place change, to the data bucket's SSE configuration toward blocked_encryption_types ["SSE-C"]; any other change stops the window.
   - service_cpu_target_percent = 15 is reverted to 60 after window 3 (Phase 17 infra-engineer follow-up, or a README note if not done).
   - The secret scholarchain-course/api-key was restored and force-deleted by the human on Nov 19 (ruling 3).

## Repair-01 (code review 1 findings; one round)

Part A (infra-engineer), scripts/load-stores.sh and scripts/README.md:
- m1: After a Neptune task exits 0, a missing expected line is a log-read problem, not "still running". Re-read with filter-log-events up to 3 times about 15 s apart (ingestion lag). If the line is still missing, exit 1 and name the log group and stream. Remove the empty status from the in-progress set. Apply the same re-read before the start-line check. An API error from filter-log-events is not turned into an empty result: it is reported, and the script exits 1.
- m4: Keep the failure diagnostics. The status record also keeps the "errors parsing" lines. When a Neptune task exits non-zero, a second filtered read saves its error lines. Every recorded line is checked: a line matching x-amz-security-token, X-Amz-Security-Token, aws_session_token, SessionToken, "Authorization:" or "Credential=" is dropped and counted, never written. Show this with a fixture check, without AWS.
- m2 and m6: The README window 3 command adds --neptune-timeout-minutes 180. The README states that in window 3 every script is human-run (Phase 15 ruling 3), and the window 3 order includes the Neptune class change to db.r6g.large for the bulk load and back (Phase 14 rulings 42 and 43) and the Neptune warm-up before the smoke test.
- Keep the exit codes. Checks: bash -n, --dry-run, and the fixture checks.

Part B (experiment-engineer), m3: exp-d-scale-v1 maxMinutes is 45 (ruling 6). When --poll-seconds is above 0, the three ecs_counts calls run with a subprocess timeout. collect.json is written after every collection call, not only at the end. exp-d-query-load-v1 behaves exactly as before. Checks: --self-test, the v1 dry-run diff, and the run-experiment.sh --dry-run for both manifests.

Part C (rag-engineer), m7: RUNTIME.md section 14 lists retrieval=<MODE> as a runtime run-ID input when the mode is not FUSED (section 24). No code change.

The same rules as ruling 4 apply. Each part's report is appended verbatim to docs/agent-runs/phase-15/repair-01.md.

## Human ruling 7 (Nov 19, 2026): code review 2, repair-01 accepted, window 3 go

7. Code review 2 (repair-01): APPROVE, 0/0/5 (N1 to N5; N5 plausible). Repair-01 is accepted; there is no further repair round (ruling 4).
   (a) N4 accepted: Part B lowered COLLECT_BUDGET_S and the manifest's collectBudgetSeconds from 150 to 90 s, beyond the Part B text. The reviewer recomputed the worst case from the harness code: about 39.4 min under maxMinutes 45 (5.6 min margin). With 150 s the margin would be 4.6 min, below ruling 6.
   (b) Residual risks: N1 (a rerun of load-stores.sh after the Neptune load has started starts a second, queued bulk load; the "rerun skips loaded data" sentences are wrong for Neptune), N2 (ERROR_PATTERN misses the `stage:` detail lines; Part A's coverage claim was inaccurate), N3 (the README window 3 order does not say to wait for the upsize), N5 (no lag re-read on the failure path). Phase 17 follow-ups for infra-engineer: a `--neptune-load-id` resume flag and corrected sentences (N1), `?"stage:"` in ERROR_PATTERN (N2), the wait in the README order (N3), the failure-path re-read (N5), and service_cpu_target_percent back to 60 with its README note (ruling 6).
   (c) Window 3 operator rules (N1, N3), binding:
       1. Start load-stores.sh only after describe-db-instances shows scholarchain-course-graph-1 as db.r6g.large and available, with no pending modified values.
       2. Once `==> NEPTUNE load=<id>` has printed, never rerun load-stores.sh without `neptune` in --skip. If the script stops after that line (exit 1 or 6), record the load ID, check it with manual status tasks (Phase 14 ruling 41 procedure) until LOAD_COMPLETED or a failure, and write a mid-window ruling in operations/rulings/ before anything else. The remaining steps then run with `--skip s3-store-conversion,s3-embed,s3-window-summary,neptune`, and the load ID is recorded beside that run's run.json (its neptuneLoadId is null).
       3. Run aws login immediately before load-stores.sh; it can run for 3 h or more.
   (d) Human check.sh on the final working tree (11:24 to 11:28 CST): exit 0; 845 passed, 0 failed, 10 pending (evidence/gate/check-sh-prewindow3.txt). The secret scan matched only the credential filter's own pattern text and FAKE fixture values; no raw session token and no credential value.
   (e) Window 3 goes ahead under ruling 3 at the commit that follows this ruling. From the plan until cleanup, nothing is written under docs/agent-runs/; mid-window rulings go to operations/rulings/ (ruling 3).

## Human rulings 8 and 9 (Nov 19, 2026; written during cloud window 3 in operations/rulings/)

8. Window 3 timing (Nov 19, 2026). Runtime applied 11:40 CST (89 added); secret set; state backup window3-apply; Neptune scholarchain-course-graph-1 resized to db.r6g.large 17:56-18:07Z and confirmed available with no pending modifications before the loads (ruling 7(c) rule 1). A deferral of the loads to 15:30 CST for connectivity was considered and not needed; the loads start about 12:20 CST. The ruling 3 runtime cap (10 h, until 21:40 CST) and the $23 month-to-date stop are unchanged.

9. Window 3 schedule (Nov 19, 2026). Loads completed 15:50 CST (Neptune load 6113dd40 LOAD_COMPLETED, 34,052,689 records; DynamoDB 876,228 items; Vectors 13,023); Neptune back to db.t4g.medium 23:28Z; deploy.sh --resume exit 0; smoke PASS (smoke-20261119T235713Z). The demonstration recording moves to about 21:00 CST because of the human's availability. The ruling 3 runtime cap is extended from 10 h to 12 h (until 23:40 CST, 05:40Z Nov 20); the $23 month-to-date stop is unchanged. D-scale runs before the demonstration; item 20 is then shown from the recorded history (CloudWatch dashboard, scaling activities, ECS service events). Cleanup starts no later than 23:00 CST.

## Human ruling 10 (Nov 19, 2026): cloud window 3 closed

10. Window 3 ran 11:40 to 22:27 CST at c542806, inside the ruling 9 cap.
   (a) Apply: plan 978ce305 (89 add, 0 change, 0 destroy; CPU target 15), applied 89; secret set; state backup window3-apply.
   (b) Loads (load-stores.sh, 13:06 to 15:50): Neptune load 6113dd40 LOAD_COMPLETED, 34,052,689 records, 0 errors, about 70 min on db.r6g.large; DynamoDB 876,228 items (ArtifactRegistry 9, EntityLinkCache 287,780, ResearcherMetricCache 588,439), 0 conflicts; S3 Vectors 13,023 written, 0 rejected. The repaired status loop read every status line live (code review 2 evidence gap closed).
   (c) Deviation: Neptune stayed on db.r6g.large from 17:56Z to 23:28Z; the downsize ran after the loads instead of during the DynamoDB step, about 1.5 h longer than planned (cost only).
   (d) deploy.sh --resume exit 0: digest sha256:5c751b1e on all services; runtime run run-c9f26a9e for query-api and worker. Warm-up after the downsize (two 504s, then 200); smoke PASS on the first attempt (smoke-20261119T235713Z; evidence ev-927665f3, the same as window 2).
   (e) exp-d-scale-v1 (20261120T001853Z) exit 0: 21,962 requests in 600 s, 0 errors, p50 112 ms, p95 683 ms, p99 922 ms; desired count 1 to 2 at 18:24:01 CST and 2 to 1 at 18:46:39 CST (target 15%, ruling 2); all 7 collection calls ok.
   (f) Demonstration items 4, 11 to 15 and 19 to 22 recorded. A9 probes: guard-dangerous.sh blocked all three (recursive S3 delete, EMR terminate, ECS desired count 100) before AWS.
   (g) Evidence archive: scratch/evidence-archive/window-3 (530 files, 12 metric series), uploaded to reports/evidence/window-3/. Cleanup runtime: plan 3352beee, 89 destroyed, 43 kept with reasons (operations/cleanup/cleanup-20261120T041658Z-runtime/); state backup window3-cleanup.
   (h) Open for Phase 16 and 17: explain the Neptune record count (34,052,689 now, 29,054,998 in window 2's second load); service_cpu_target_percent back to 60 (ruling 6); Claude Code CLI updated from 2.1.178 to 2.1.329 on Nov 19 to run the course autograder (tool only, not a project dependency).

## Human ruling 11 and work order 4 (Nov 20, 2026)

11. Work order 4 runs before experiment E (Phase 14 ruling 31; Phase 15 rulings 1 and 6). Session 5 runs Part A, then Part B. Agents make no live call (no aws, no Bedrock or Haiku call, no `--live` run), run no git and no terraform, and never read or write `labels/`, `scratch/stores-live/`, `scratch/er/adjudication-live/` or `experiments/benchmark/`. Python is stdlib only and runs as `python3 -I`; no `py_compile`; no `__pycache__` may be left under `experiments/` or `load-tests/`. Logs go to `scratch/phase-15-logs/wo4-*.log`. Reports are appended verbatim to `docs/agent-runs/phase-15/agent-report.md` under "## Work order 4". Relevance labels and answer ratings are the human's: no tool may produce, suggest, pre-fill or hint at a label or rating. Code review 3 (one round) follows in a separate session; then the human runs E.

## Work order 4

### Part A (experiment-engineer; `experiments/**` only)

A1. Harness `experiments/harness/exp-e-retrieval-modes.sh` (tracked, executable), run through `scripts/run-experiment.sh`:
- Uses SCHOLARCHAIN_EXPERIMENT_DIR and AWS_PROFILE from run-experiment.sh. Refuses to start (exit 3) if any other sbt or runMain process is running.
- Step 1 (check): FUSED with `--max 2` into raw/fused/. It continues only if the step 1 output has a "plan sources:" line that includes MODEL and contains none of FALLBACK_RULES, BUDGET_EXHAUSTED or Exception; otherwise it stops with exit 3 and keeps the outputs.
- Step 2: the modes fused, graph-only and vector-only in that order, one at a time, each with `--max N` (default 10) and `--per-minute 8`, each in its own directory `$SCHOLARCHAIN_EXPERIMENT_DIR/raw/<mode>/` (fused resumes step 1's two questions).
- The command per mode is exactly this, with `<dir>`, `<N>` and `<mode>` filled in:
  `env SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -batch -J-Xmx6g "rag/runMain scholarchain.rag.cli.BenchmarkCli --questions experiments/benchmark/benchmark-questions-v1.csv --path-evidence scratch/stores/work/neptune-check/snapshot=2026-10-03/run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv --graph scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --items scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --objects scratch/stores/s3 --vectors scratch/stores-live/s3vectors --out <dir> --window-summary-root scratch/runtime/wo8/s3 --window-summary-run run-6fbad498beb0d8f570df67665fb97544 --live --profile $AWS_PROFILE --region us-east-2 --per-minute 8 --max <N> --retrieval-mode <mode>"`
- Per mode it saves sbt stdout and stderr as .txt (never .log), and writes `raw/harness.json` (start and end times, modes, N, exit codes, benchmark run IDs, the step 1 decision).
- `--dry-run` prints every command without running sbt. `--self-test` runs the whole flow against a stub runner (an environment variable that replaces sbt) with fixture outputs, including a step 1 stop and a failing mode.

A2. Manifest `experiments/manifests/exp-e-retrieval-modes-v1.json` (experiment-manifest-v1): the harness with N = 10; `limits.maxMinutes` from a worst-case sum with at least 10 minutes of margin, stated in `limits.maxMinutesReason`; limits also record per-minute 8, 10 questions x 3 modes, the local runtime against live Bedrock, and the file-backed vectors (Phase 14 ruling 31); `metrics` empty (local run). It must pass `scripts/run-experiment.sh --manifest ... --dry-run`.

A3. Summarizer `experiments/analysis/summarize-exp-e.py`, read-only on results: per mode, questions, answered, abstained by reason, VERIFIED, PARTIALLY_SUPPORTED and removed claims, latency p50, p95 and max, LLM calls and tokens, embedding calls; CSV and Markdown into `--out`. With `--relevance <csv> --ratings <csv> --keys <dir>` (human files) it adds recall at 5 and 10 (capped at the bundle size; the cap is stated) and MRR per mode, and mean usefulness and clarity per mode; without them it writes "not labelled yet". It never infers relevance. `--self-test` with fixtures.

A4. Labelling sheets `experiments/analysis/e-label-sheets.py`: from the E raw outputs, into an `--out` directory that must not be under `labels/` (the human uses `scratch/e-labelling/`):
- a relevance sheet: per question, the question and the pooled retrieved items of all three modes, deduplicated and shuffled with a fixed seed, each shown by its readable content (evidence type, facts or text), never by mode, rank, score, verification status or which modes retrieved it;
- `relevance-template.csv`: question_id, item_key, relevant (left empty; the human writes 1 or 0 and saves it as `labels/relevance-e-v1.csv`);
- an answers sheet: per question, the three answers under blind keys in shuffled order; `ratings-template.csv`: question_id, answer_key, usefulness, clarity (left empty; 1 to 5);
- key files (item_key and answer_key to mode, rank and evidence ID) written separately, for the summarizer only.
- `--self-test` asserts that no template has a filled label or rating and no sheet shows mode, rank or score.

A5. Derived tables `experiments/analysis/derive-tables.py`: from committed raw data only, reproducible CSV and Markdown tables under `experiments/derived/<name>/` for size scaling (`operations/pipeline-runs/*-fraction25|50|100/run.json` and `docs/agent-runs/phase-14/evidence/window-1/p14-window1-results.tsv`), experiment A if its raw results exist under `experiments/results/`, D (`experiments/results/exp-d-query-load-v1/`), D-scale (`experiments/results/exp-d-scale-v1/20261120T001853Z/`, including the scaling timeline) and F (`experiments/results/exp-f-failure-recovery-v1/`). Every table names its source files and their sha256. Missing data is reported as a gap, never filled in. Run it once; its outputs stay under `experiments/derived/`.

A6. Checks: `bash -n`; every `--self-test`; the dry-runs; `scripts/run-experiment.sh --manifest experiments/manifests/exp-e-retrieval-modes-v1.json --dry-run`; no `__pycache__` under `experiments/` or `load-tests/`. If the test-stop hook requires it, one `sbt check` at the end (SPARK_LOCAL_IP=127.0.0.1; never two sbt processes).

### Part B (infra-engineer)

B1. Ruling 6 revert: `service_cpu_target_percent = 60` in `infra/terraform/envs/course/course.tfvars`, and one note in `infra/terraform/README.md` that window 3 ran with 15 as a declared experiment setting (Phase 15 rulings 2 and 6) and the default is 60. No other change and no terraform command; the human's check.sh covers fmt and validate.

## Human ruling 12 (Nov 20, 2026): code review 3, repair-02 of work order 4

12. Code review 3 (work order 4): REQUEST_CHANGES, 0/1/6. Part B is accepted. Part A goes to repair-02, by human exception to the one-repair-round rule (precedent: Phase 11 ruling 20): work order 4 is new scope after window 3, and its defect shows only against the real BenchmarkCli. Repair-02 is experiment-engineer only, `experiments/**` only, under all of ruling 11's rules. Its report goes to `docs/agent-runs/phase-15/repair-02.md`.
   (a) E-M1: the harness runs step 1 (fused `--max 2`), then fused with `--max N-2`, then graph-only and vector-only with `--max N`, so every mode answers exactly the first N questions; N must be 3 to 10. This replaces "each with `--max N`" in A1. The stub models resume (it counts `questions/*.json` under `--out` and answers at most `--max` pending questions), and the self-test asserts that each mode ends with exactly N results and the same question IDs. The dry-run output and the recorded command text follow the new arguments. The manifest still describes 30 questions; recheck its arithmetic.
   (b) E-m2: raw files go to `$SCHOLARCHAIN_EXPERIMENT_DIR/<mode>/` and `$SCHOLARCHAIN_EXPERIMENT_DIR/harness.json`, so `<stamp>/raw/<mode>/`, matching D and F. This replaces the A1 path text. Update the manifest's layout fields and the documented commands (`--raw <stamp>/raw`).
   (c) E-m1: TERM at maxMinutes must stop the JVM too: start sbt in its own process group (or signal its children) and wait. A stub that spawns a child proves both stop.
   (d) E-m3: the summarizer computes quality metrics over the question IDs present in all three modes and lists the excluded IDs; it adds a questions_without_items category; the Markdown prints a "question sets differ" line when they differ.
   (e) E-m4: the D-scale derived README adds the reviewer's caveats (the one-task phase includes the level's warm-up; window 2's warmed single task served 35.72 requests per second at c=8; Neptune CPU about 97 percent in both phases; target registration is not health), plus a steady-state comparison labelled as such. Rerun derive-tables.py so the committed outputs match the code.
   (f) E-m5: repair-02 states the correct summarizer self-test count; the work order 4 report stays verbatim.
   (g) E-m6 is accepted as recorded (read-only, output discarded, nothing depends on it). A third deviation would not be accepted: repair-02 runs no git at all.
   (h) The answers sheet may show each claim's verification status and the abstention reason: that is the answer as the API delivers it, which is what is rated. The blinding limit (vector-only abstentions are recognisable) is stated in the Phase 16 write-up.
   (i) The guard false positive (a write verb plus the text `labels/` in file content) is a Phase 17 note for factory-engineer; no change now.
   (j) Next: code review 4 (the repair only, one round). Then the human commits work order 4 and repair-02 (with `git add -f` for the evidence logs) and runs E.

## Human ruling 13 (Nov 20, 2026): code review 4, work order 4 accepted, E run

13. Code review 4 (repair-02): APPROVE, 0/0/3. Work order 4 (Parts A and B) and repair-02 are accepted; no further agent round.
   (a) C4-m1: the manifest's failingModeRule and repair-02's RISKS wrongly say a rerun resumes. The governing rule: BenchmarkCli resumes only within one --out; a rerun through run-experiment.sh writes a new <stamp> and repeats every mode, and the earlier stamp stays read-only. The manifest text stays as committed; this ruling governs.
   (b) C4-m2: blind labelling sheets are built only when the E run record's exitCode is 0 and the summarizer prints "Question sets: the same 10 question(s) in every mode." If any mode ended early, no sheets are built and a new ruling decides between a fresh full run and a guard in e-label-sheets.py.
   (c) C4-m3: the Phase 16 write-up states that the ALB request rate kept rising through the two-task minutes while Neptune stayed at about 97 percent CPU, so neither the phase averages nor the steady-state rows attribute the throughput difference to the second task.
   (d) To stop E early, send TERM to the harness process; Ctrl-C does not stop it (run-experiment.sh has no INT trap; Phase 17 note for infra-engineer). TERM against the real sbt runner is unverified.
   (e) Part B (CPU target back to 60) is accepted; check.sh on the committed tree covers terraform fmt and validate.
   (f) E runs locally after this commit and a passing check.sh, with the cost checked first, VS Code quit, and no other sbt or Haiku run.

## Human ruling 14 (Nov 20, 2026): experiment E outcome

1. Run: exp-e-retrieval-modes-v1, stamp 20261120T093705Z, commit 48e1864 (manifest sha256 2be8d16647003ae0699a429050bd05fb35bb0f017bf88a63cb40497cbcec9be6), started by the human with run-experiment.sh and profile scholarchain-sdk. run-experiment.sh exit 0; harness exit codes [0,0,0,0] with maxima [2,8,10,10].
2. Times (UTC): check fused --max 2 09:37:05-09:38:28 (plan sources: MODEL 2; outcomes: ANSWERED 2; continue); fused --max 8 09:38:28-09:41:09; graph-only --max 10 09:41:09-09:44:20; vector-only --max 10 from 09:44:20, harness end 09:47:41. Total 10 min 36 s.
3. Benchmark runs: fused run-25a1816ee6285626bd22bdbcaa862fce (the check and the fused step, resumed as designed by ruling 12 a); graph-only run-8823c6752422c014191a1a1864ebeb11; vector-only as recorded in raw/harness.json.
4. Completeness: 10 question files per mode. summarize-exp-e.py (no labels) prints "Question sets: the same 10 question(s) in every mode." No FALLBACK_RULES, BUDGET_EXHAUSTED or Exception in any mode's stdout or stderr. No session token or credential value in the new files; no file over 10 MB.
5. Observation (not a defect): the run took 10 min 36 s against plannedMinutes 20 and maxMinutes 135 (the handoff's 45-90 min estimate was conservative). No limit was reached; nothing was stopped.
6. The labelling gate of ruling 13 b holds (exit 0 and the same 10 questions in every mode): the blind sheets may be built. No rerun of E (ruling 13 a); raw results under experiments/results/ are immutable (ruling 1).
7. Per-mode counts are in experiments/derived/exp-e/exp-e-summary.md and .csv and are not restated here. Every file under experiments/derived/exp-e*/ and the labelling sheets and keys are tool-generated by the human running summarize-exp-e.py and e-label-sheets.py; none is hand-edited. Relevance labels and answer ratings are the human's alone (ruling 1); disclosed in the gate.
8. check.sh on 48e1864 (03:29 CST Nov 20): every gate passed, 845 passed, 0 failed, 10 pending; saved as evidence/gate/check-sh-wo4.txt.
9. Bedrock cost of E is read from Cost Explorer with the window 2 and 3 costs on Nov 21 (worst case 2.64 USD by the manifest).

## Human ruling 15 (Nov 20, 2026): Phase 16 write-up in two parts

1. The Phase 16 write-up is work order 5, in two parts.
   - Part A (session 9, Nov 20): documentation-engineer writes docs/evaluation.md for evaluation groups 1-7 of the spec (Phase 16) from committed evidence. Two subsections stay marked "Pending (work order 5 part B)": (a) E retrieval quality and answer ratings (recall at 5 and 10, MRR, mean usefulness and clarity per mode), which need the human's labels; (b) the settled cost per window and of E.
   - Part B (session 10, Nov 21): after the human commits labels/relevance-e-v1.csv, labels/ratings-e-v1.csv, experiments/derived/exp-e-labelled/ and ruling 16 (settled costs), documentation-engineer fills only those two subsections and removes the pending marks.
   - Code review 5 (session 11, Ponytail OFF) reviews the whole file after part B. One repair round.
2. Reason: the labelling moves to Nov 21. Every other input is frozen (E raw results committed at 544a310, ruling 14).
3. No agent produces, suggests or reads relevance labels or answer ratings before they are committed (ruling 1). The human's rating anchors are written before rating and quoted in the gate.
4. Phase 15 sessions so far, for group 6 (factory-metrics rows for Phase 15 are added at the gate, so part A takes them from here and from the phase-15 review reports):
   - session 1: WO1 rag-engineer, WO2 infra-engineer, WO3 experiment-engineer, COMPLETE;
   - session 2: code review 1 APPROVE 0/0/7;
   - session 3: repair-01 (infra-engineer, experiment-engineer, rag-engineer), COMPLETE;
   - session 4: code review 2 APPROVE 0/0/5;
   - session 5: WO4 experiment-engineer and infra-engineer, COMPLETE (one read-only git status, deviation, ruling 12 g);
   - session 6: code review 3 REQUEST_CHANGES 0/1/6;
   - session 7: repair-02 (experiment-engineer, by human exception, ruling 12), COMPLETE;
   - session 8: code review 4 APPROVE 0/0/3.
   Accepted read-only deviations: rag-engineer git status once, experiment-engineer aws --version once (ruling 5). Guard false positives were not worked around.

## Work order 5 part A (documentation-engineer): docs/evaluation.md

Goal: one file, docs/evaluation.md, that evaluates ScholarChain by groups 1-7 of the spec's Phase 16, from committed evidence only. Concise; tables where they help; no polish.

Territory and limits:
- Write only docs/evaluation.md. Everything else is read-only.
- Use Read, Grep and Glob only. No Bash, no git, aws, terraform, sbt, python, Bedrock or MCP calls.
- Do not open labels/, scratch/, secrets/ or any file whose path contains e-labelling.
- No text may address a grader or an evaluator. Explaining results and limits is fine.

Rules:
- Every number cites the committed file it comes from (repository path; add the ruling number where a ruling records it). If a source and this work order disagree, use the source and say so in the report.
- In each group, keep "Measured" (facts with sources) separate from "Interpretation".
- Keep negative results: failures, out-of-memory runs, the absence of scale-out in window 2, abstentions, removed claims.
- The derived tables under experiments/derived/ are the inputs. Do not recompute them. A simple ratio is allowed only when both numbers are shown (for example 12/99).
- Where a metric the spec lists was not measured, say so in that group and list it under "Missing data".
- Every experiment except D (two attempts) is a single run. Say that variability was not measured where only one run exists.

Content by group (where to look; find exact files with Glob and Grep, and cite what you find):
1. Entity resolution: the Phase 5 gold evaluation (docs/agent-runs/phase-05/ and the evaluation outputs it cites; 310 human labels, er-v1 vs er-v2): precision, recall, F1, confusion matrix, coverage, unresolved rate, metrics by matching path where reported. Do not open labels/; use the reports.
2. Graph correctness: tests (docs/agent-runs/phase-15/evidence/gate/check-sh-wo4.txt: 845 passed, 0 failed, 10 pending), validator outputs, integrity counters and graph-algorithm tests from Phases 6, 7+8 and 10 evidence, window summaries. Report referential integrity, duplicate and orphan rates, edge-support and algorithm tests where they exist; the rest under Missing data.
3. Retrieval (experiment E): experiments/derived/exp-e/exp-e-summary.md and .csv, the manifest experiments/manifests/exp-e-retrieval-modes-v1.json, ruling 14. Report the per-mode counts (questions, answered, abstained by reason, retrieved items, latency, tokens). Limits: 10 questions and one run; the ranked list is the post-budget bundle, so recall at K is capped at the bundle size (ruling 6, m5); file-backed vectors with exact search, not the S3 Vectors service (Phase 14 ruling 31); VECTOR_ONLY abstains with NO_VERIFIED_CLAIM by design (ruling 6); the blinding limit of the answers sheet (ruling 12 h); precision at K and nDCG not reported (binary labels, no graded relevance); no OpenSearch comparison (D-06). Recall at 5 and 10 and MRR: subsection marked "Pending (work order 5 part B)".
4. Answer quality: verifier removal rates (Phase 9 live 43/73, Phase 10 final 15/100, Phase 11 gate 12/99; confirm each in that phase's gate files, docs/agent-runs/phase-09/, phase-10/, phase-11/), with their by-design classification; E verified, partially supported and removed claims per mode; abstention reasons; residual verifier gaps D18-D20. Human usefulness and clarity ratings: subsection marked "Pending (work order 5 part B)".
5. Performance and elasticity:
   - Experiment A: the 25% deef295 EMR run (Phase 14 ruling 21).
   - Size scaling: experiments/derived/size-scaling/ and docs/agent-runs/phase-14/ window-1 results (Hadoop at 25/50/100%; Spark complete only at 25%; job 10 out of memory at 50% and 100% on EMR, P14-D8).
   - D in window 2: experiments/derived/exp-d/ and operations/deployments/window-2/post-exp-d-cloudwatch.txt (no scale-out; Neptune-bound).
   - D-scale in window 3: experiments/derived/exp-d-scale/ and Phase 15 rulings 2, 10 and 13 c. Report the scale-out and scale-in times and the second target's registration delay, and state that the throughput difference is not attributable to the second task.
   - F: experiments/derived/exp-f/ (retry, DLQ redrive, idempotent replay, recovery time).
   - Cost: window 1 EMR about 3.32 USD (Phase 14 ruling 22). Windows 2 and 3 and E: subsection marked "Pending (work order 5 part B)".
6. Agentic factory: docs/agent-runs/factory-metrics.md, each phase's review-report.md, repair-NN.md and human-gate.md, and ruling 15 point 4 for Phase 15. Report first-pass acceptance by agent, BLOCKED-ON events, ownership violations caught by hooks, review findings by severity, repair rounds per phase (and the human exceptions), manual interventions (human-run scripts, rulings), and agent output that needed substantial human rewrite where recorded. Cells marked ? in factory-metrics.md are missing data.
7. Reproducibility: scripts/check.sh and its evidence; plans by checksum and deploy from an approved plan (operations/plans/, scripts/deploy.sh); bounded pipeline runs (operations/pipeline-runs/); regeneration of the derived tables (experiments/analysis/derive-tables.py, summarize-exp-e.py; give the commands; the human records the result at the gate). Clean-checkout build: pending the Phase 17 fresh-clone run; say so.

Also include:
- The Neptune record-count question: 34,052,689 records (window 3, operations/pipeline-runs/store-load-20261119T190616Z/) vs 29,054,998 (window 2's second load, ruling 43) from the same store run. Look in the committed load records for an explanation (for example a different input, partial loads counted, or the loader's counting). Report it as resolved with the evidence, or as open.
- A "Threats to validity" section (small question sets, single runs, a single labeller, file-backed vectors, local runtime for E, laptop client for D, cold caches after resizes, Cost Explorer lag).
- A "Missing data" section listing every spec metric not measured, and why.

Report (the orchestrator appends it verbatim to docs/agent-runs/phase-15/agent-report.md under "Work order 5 part A"):
- status COMPLETE or BLOCKED-ON;
- the file changed and its line count;
- per group, the source files cited;
- the pending subsections left for part B;
- any source that disagreed with this work order;
- the Neptune record-count finding;
- deviations (expected: none).
