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
