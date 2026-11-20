## Code review 1 (work orders 1 to 3)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15+16, session 2, code review 1. Review of work orders 1 to 3 in docs/agent-runs/phase-15/prompt.md, under rulings 1 to 5, against the session 1 reports in docs/agent-runs/phase-15/agent-report.md. Read-only review.
VERDICT: APPROVE

I found no CRITICAL or MAJOR findings and seven MINOR ones. Four of them (m1 to m4) affect how window 3 runs. They are cheap to fix and should be routed before HEAD is pinned at the window 3 plan (ruling 3). APPROVE does not close them.

SCOPE REVIEWED:
- `evidence/code-review-1/diff.txt` (all 1,479 lines), `untracked-diff.txt`, `diffstat.txt` and `untracked.txt`.
- Every changed file read in full or around each hunk, in the working tree.
- Context files: `Verifier.scala`, `Evidence.scala` (graphGroups and identity), `LoadNeptuneCli.scala`, `scripts/lib/common.sh`, `scripts/run-experiment.sh`, `modules/observability/main.tf`, `envs/course/outputs.tf`, `.terraform.lock.hcl`, the Phase 14 window 2 and cleanup plan text, `operations/deployments/window-2/neptune-status.txt`, and Phase 14 rulings 31, 34 and 36 to 49.
- All session-1 logs.

## Findings (ranked)

No CRITICAL. No MAJOR.

**m1 (MINOR). The Neptune status loop does not fail fast when the log read finds nothing.**
- Location: `scripts/load-stores.sh:208-218` (the empty-status case is at :214) and `:187-188`.
- Owner: infra-engineer.
- Defect: when a status task exits 0, it always prints `load <id> status ...` (`LoadNeptuneCli.scala:73-76`). A store error makes it exit 1 (`:70-72`), and `run_loader_task` then stops at `load-stores.sh:190`. So an empty status after an exit-0 task means the log read failed, not that the load is still running. The script still treats `""` as in progress. The `|| true` at `:188` also turns an API failure (for example a missing `logs:FilterLogEvents` permission, or a throttle) into an empty result.
- Failure scenario: the phrase pattern misses, or the line has not been ingested yet. The script then starts a status Fargate task about every 3 minutes for `NEPTUNE_MIN` (60) minutes and exits 6, possibly long after the load has completed. The start step has the same race: at `:205` it exits 1 while the bulk load it just started is already running.
- Fix: when the task exit code is 0 and the line is missing, re-read with filter-log-events 2 or 3 times about 10 to 15 s apart (ingestion lag). Then exit 1 and name the stream. Remove `""` from the in-progress set. Apply the same short re-read before the start-line check at `:205`.

**m2 (MINOR). The window 3 load command uses the 60-minute default Neptune timeout.**
- Location: `agent-report.md:205` (the recommended window 3 command) and `scripts/README.md:69-71`. The default is `scripts/load-stores.sh:64` (`NEPTUNE_MIN=60`); `:218` waits through `wait_until`, which exits 6 at `common.sh:140`.
- Owner: infra-engineer (documentation and command). The human decides the value.
- Evidence: in window 2 the db.r6g.large load started at 03:11:42Z, and LOAD_COMPLETED was first recorded at 04:59:47Z, 108 minutes later (`neptune-status.txt:26-28`). The polls in between were not recorded, so the real duration is unverified. This script's Neptune wait has never been run to completion on this graph (ruling 41 used manual tasks).
- Failure scenario: the load takes longer than 60 minutes. The script exits 6, so DynamoDB and Vectors never load and no `run.json` is written, because the record step comes after the exit. The rerun then needs `--skip ...,neptune`, which records no load ID and checks no completion.
- Fix: add `--neptune-timeout-minutes 180` (the flag's maximum, `:95`) to the window 3 command in the README, or record a measured duration that justifies 60.

**m3 (MINOR). D-scale's worst case leaves about 1 minute under maxMinutes 40.**
- Location: `load-tests/exp-d-query-load.py:82` (`COLLECT_BUDGET_S = 150`), `:823-827` and `:429-432` (`collect.json` is written last). Also `experiments/manifests/exp-d-scale-v1.json:19-20,36`.
- Owner: experiment-engineer for the budget. maxMinutes is fixed by work order 3, so changing it is the human architect's decision.
- Defect: the worst-case total is about 38.5 to 39.5 minutes against a 40-minute stop:
  - setup and preflight (cold neighbors took 3.4 to 5.4 s each, ruling 46)
  - 600 s of load, plus a worker tail of about 10 to 30 s (code review 5 residual)
  - 1,500 s of post-observation
  - the poller join, up to 20 s (`:83`)
  - up to 150 s of collection
  - three `ecs_counts` calls (`:413-416`) with no subprocess timeout (120 s CLI read timeout plus retries)

  Phase 14 ruling 34 asked for at least 5 minutes between a harness's own waits and maxMinutes. It was stated as a window 2 operating rule, and its reason (run-experiment.sh signals only the command's PID) still holds.
- Failure scenario: maxMinutes fires during collection. `run-experiment.sh:104-111` sends TERM. Python's default SIGTERM handler ends the process without running `finally`, so `collect.json` is lost. The run record shows `timedOut` and exit 6 (`run-experiment.sh:130`), although `summary.json` is complete.
- Fix: the human raises maxMinutes to 45 by ruling. Or cut `COLLECT_BUDGET_S` to about 90 s, bound `ecs_counts` with a subprocess timeout when `--poll-seconds` is set, and write `collect.json` after every call instead of only at the end.

**m4 (MINOR). The Neptune task records no longer keep the failure diagnostics.**
- Location: `scripts/load-stores.sh:187-188`, `:202` and `:209`.
- Owner: infra-engineer.
- Defect: work order 2 C says "Keep the existing exit codes and records." The record `neptune-<label>-<task>.txt` now keeps only the lines that match the phrase. These lines are no longer recorded:
  - `error: <e>` (`LoadNeptuneCli.scala:71`)
  - `errors parsing ... insert ...` (`:77-79`)
  - Neptune's errorLogs lines (`:80`)
  - any error output from the start task

  Keeping the DEBUG session-token lines out of the record is correct (ruling 41). Losing the diagnostic lines is the defect.
- Failure scenario: a repeat of window 2's LOAD_CANCELLED_DUE_TO_ERRORS (ruling 42 diagnosed OUT_OF_MEMORY_ERROR from exactly these lines). The record then shows only `load <id> status LOAD_CANCELLED_DUE_TO_ERRORS`. The cause survives only in CloudWatch, for the log retention period. The log groups are not runtime-gated (`modules/observability/main.tf:82-86`), so they outlive the runtime cleanup.
- Fix: make the status pattern an OR of the status phrase and `errors parsing`. When the exit code is not 0, save a second filtered read of the error lines that excludes token-bearing lines. Or the human accepts the narrower record, and the README says so.

**m5 (MINOR). The ranked list is the evidence bundle after the budget, not everything that was retrieved.**
- Location: `modules/rag/src/main/scala/scholarchain/rag/cli/Benchmark.scala:256-273` and `Evidence.scala:539-551`.
- Owner: rag-engineer, or the human accepts the limit.
- Defect: work order 1 item 5 asks for the "ranked list of retrieved items". The list stops at the bundle. Up to 15 vector hits (top-k 5 × max-indexes 3) compete for 3,000 semantic tokens (`scholarchain.conf:190-195`). Hits that the budget leaves out are counted, not listed.
- Failure scenario: a relevant hit retrieved at index rank 4 but cut by the token budget is missing from the list. Recall at K is then capped at the bundle size, and recall differences between modes can reflect the budget rather than retrieval. VECTOR_ONLY drops the community and abstract filters (`Semantic.scala:82-84`), so it returns different candidates for the same budget.
- The agent disclosed this (`agent-report.md:66`, RUNTIME.md section 24 limitation 1, the `shortcut:` comment at `Benchmark.scala:259`).
- Fix: list the hits before the budget with an `inBundle` column. `RuntimeResult` does not carry `SemanticStage.Output` today, so this needs plumbing. Or the human records the limit in the experiment E method.

**m6 (MINOR). The scripts README assigns window 3 scripts to agents, but ruling 3 makes them human-run.**
- Location: `scripts/README.md:62-74`.
- Owner: infra-engineer.
- Defect: the order (apply, secret plus state backup, loads, then `deploy.sh --resume`) is correct. But lines 66 and 72-74 give `deploy.sh`, `--resume`, `smoke-test.sh` and `run-experiment.sh` to deploy-engineer and experiment-engineer for "windows 2 and 3". Ruling 3 says every window 3 script is human-run, and ruling 36 made the window 2 deploy human-run. The ruling 3 steps "Neptune on db.r6g.large, then back to db.t4g.medium" and "Neptune warm-up" are also missing. Rulings 42 and 43 deferred documenting the resize to Phase 17, so that part may be intended.
- Failure scenario: an operator follows the README in window 3 and hands `deploy.sh` to an agent, against ruling 3, with ruling 36's long-apply risk.
- Fix: add a line saying that in window 3 every script is human-run (Phase 15 ruling 3), and mention the class change and the warm-up in the window 3 order.

**m7 (MINOR). RUNTIME.md section 14 does not list the new run-ID input.**
- Location: `modules/rag/RUNTIME.md:351`.
- Owner: rag-engineer.
- Defect: the "IDs" paragraph lists the runtime run-ID inputs but not `retrieval=<MODE>`, which `Workflow.scala:114` adds when the mode is not FUSED. Only section 24 (`:630`) states it. RUNTIME.md line 2 says any disagreement between the document and the code is a defect.
- Fix: add "and `retrieval=<MODE>` when the retrieval mode is not FUSED (section 24)".

## Per-check answers

### Work order 1 (rag-engineer)

**(a) FUSED unchanged: PASS (by code reading and tests; no golden output from before the change).**
- Code path: every new branch tests for a mode other than FUSED.
  - `Planner.forMode` returns the plan unchanged unless the mode is GRAPH_ONLY (`Planner.scala:712-713`); it is applied at `Workflow.scala:466`.
  - The graph stages run as before when the mode is not VECTOR_ONLY (`Workflow.scala:515-551`), and `stages.dropRight(1)` still applies for FUSED (`:554`).
  - The two abstentions now read `!vectorOnly && ...` (`:567`, `:581`), which is the same test for FUSED.
  - The new semantic branches run only for VECTOR_ONLY (`Semantic.scala:82-84`), and the new evidence branches only for the other modes (`Evidence.scala:524-526`, `:539`).
  - `AskCli` prints the same startup line for FUSED (the note is empty).
- Audit bytes: `Records.scala:543` adds a last field with a default, which upickle leaves out. `RetrievalModeSpec` (lines 162-165 of the new file) asserts there is no `retrievalMode` key and that the record reads back equal. It passed (`wo1-retrieval-spec-2.log:10,19`).
- Run ID: `Workflow.scala:114` adds an input only for modes other than FUSED. `RetrievalModeSpec` lines 174-184 check this.
- Services: `RuntimeSetup.retrievalMode` defaults to FUSED (`Workflow.scala:69`), and no services file changed.
- Limit: the test compares the new default with an explicit FUSED, both on new code. No test in the rag suite pins a golden hash (I searched `src/test`).
- The only FUSED-visible changes are in the benchmark: schema and run ID v3, the summary line ("..., fused retrieval)"), and the ranked-list files. Items 3 to 5 of the work order require these.

**(b) Keeping rag-audit-v3: acceptable; v4 is not required.**
- The FUSED wire form is byte-identical.
- Audits with other modes come only from local BenchmarkCli runs, under their own runtime run IDs (`Workflow.scala:114`).
- The services never set the mode. `ExplainResponse.of` (`Models.scala:243`) maps fields explicitly.
- The cost: it breaks the file's own convention (v2 and v3 were each bumped for added fields, RUNTIME.md section 14). A consumer that relies on the schema version alone cannot tell which audits carry the field. The human should accept this exception explicitly. Not a finding.

**(c) VECTOR_ONLY drops the identity record: consistent with the work order; the consequence needs a human decision.**
- The work order allows graph facts that name resolution needs; it does not require them. The identity item is built from the resolution output alone, with no extra read (`Evidence.scala:88-113`), so including it would also have met the work order.
- Dropping it makes NO_VERIFIED_CLAIM certain:
  - material claims need a cited graph item (`Verifier.scala:859-865`);
  - descriptive claims are never VERIFIED (`:972-978`);
  - zero verified claims means abstention (`Workflow.scala:684-689`).
- Ruling 31 compares retrieval and records answers, evidence IDs and latency, so the retrieval lists are unaffected. But answer-level results for VECTOR_ONLY are degenerate, and the live run spends planner and synthesis Haiku calls on answers that must abstain. That stays within ruling 31's 10 questions × 3 modes.
- The human decides whether this is intended. Not a defect.

**(d) Ranked-list file: correct and deterministic; the post-budget limit is finding m5.**
- Order: graph items in bundle order, then vector items by score with `evidenceId` as the tie-break (`Benchmark.scala:262-273`, `Evidence.scala:540-542`).
- The file is rebuilt from stored results and written once; a resume writes the same bytes (`BenchmarkCli.scala:127-137`), and the test exercises the resume.
- CSV cells are quoted when needed.
- `source` is the mode's list name and is constant within a file; `origin` gives each item's retriever. That is a reasonable reading of item 5.
- The files are not in the run manifest, but each is a copy of the `retrieved` field of a result that is.

**(e) Injection safety and verification not weakened: PASS.**
- No prompt, `Synthesis`, or `Verifier.scala` change; the verifier stays verifier-v8.
- GRAPH_ONLY only removes semantic input.
- VECTOR_ONLY skips the two abstentions before synthesis (`Workflow.scala:567`, `:581`), so more questions reach synthesis with only untrusted vector text. Verification still requires graph evidence (`RetrievalModeSpec` lines 227-231).
- The existing adversarial suites passed in `sbt check`.
- Pre-existing, not new: an abstained answer still carries LABELLED descriptive claims (`Workflow.scala:690-701`), as in FUSED.

**(f) labels/, experiments/benchmark/ and the services API untouched: PASS.**
- git status shows no path under them.
- `services/Test/compile` succeeded (`wo1-compile-1.log:13,18`).

### Work order 2 (infra-engineer)

**(a) CPU target variable: PASS.**
- Variable: `variables.tf:401-410` (number, default 60, validation `floor(v)==v`, 10 to 90).
- Wiring: `main.tf:181`. Module input `ecs/main.tf:110-113`; it has no default, and the only caller is `main.tf:164`.
- Policy: `target_value = var.cpu_target_percent` at `:413`.
- Setting: `course.tfvars:61-64` sets 15 with a ruling 2 comment.
- Evidence: `wo2-cpu-var-validation.log` accepts 10, 15, 60 and 90 and rejects 9, 91 and 15.5 with the message. `terraform console` exits 0 even on the error, as the agent noted. validate succeeded and fmt exited 0.

**(b) SSE-C pin: 0 to change for the data bucket is inferred, not verified.**
- Verified:
  - The lock file pins aws 6.28.0 (`.terraform.lock.hcl:4-5`).
  - Both attributes exist in that provider's schema: validate passed with the literal values, and the 6.28.0 plans show both attributes.
  - AWS reported `["SSE-C"]` and `false` in the window 2 plan (ruling 38) and in cleanup plans 1 (`p14-w2-cleanup-plan.txt:214-218`) and 2 (`p14-w2-cleanup-plan-2.txt:115-119`). Plan 2 was 15 minutes after apply 1 had set `[]`.
  - With the attribute unset, the provider plans `[]` (`phase-12/.../plan.txt:479`), so declaring it is necessary.
  - The new rule matches the state on every attribute shown. The hidden `kms_master_key_id` was "unchanged" in both plans.
- Inferred:
  - That AWS again reports `["SSE-C"]` after cleanup apply 2 (12:17 CST), which set `[]` once more. No plan has been seen since. If AWS did not restore the block, the plan shows one harmless in-place change toward `["SSE-C"]`, not 0.
  - That nothing in the infrastructure changed since 506eddf apart from this diff. Neither the agent nor I can check that without git.
- The human should accept only that single change if it appears.

**(c) `--skip`: PASS.**
- `:81` uses `flag_val`, which rejects an empty value and values starting with `--`.
- `:88` rejects characters outside `[a-z0-9,-]`, and `:89-91` rejects unknown names, both with exit 2.
- Empty list elements are ignored. Duplicates are accepted and listed twice (cosmetic).
- Exit codes stay 0, 1, 2, 3 and 6.
- `run.json` (`:227-234`): `neptuneLoadId` is null when Neptune is skipped, and `skippedSteps` is added. Each skipped step is noted as "skipped" (`:140-143`, `:198-200`).
- Evidence: `wo2-load-stores-checks.log:7-71`.

**(d) filter-log-events: shape and quoting correct, not tested live; fail-fast is m1.**
- Command (`:187-188`): log group, `--log-stream-names` with the task stream, `--filter-pattern`, `--query 'events[].message'`, `--output text`. CLI v2 pages automatically and applies `--query` to the merged result. That is inferred from how the CLI works, not tested.
- Quoting: `'"started load"'` and `"\"load $LOAD_ID status\""` pass double-quoted phrases to CloudWatch, which match exact phrases. The load ID is limited to `[^ ;]*` by the sed at `:204`. The phrases match `LoadNeptuneCli.scala:75` and `:176`.
- The stream name `neptune-loader/neptune-loader/<task id>` is unchanged and matches the ruling 41 stream form.
- Should the status loop fail fast instead of waiting 60 minutes? Yes, see m1.

**(e) No secret or session token reaches operations/: PASS (inferred).**
- The record keeps only lines that contain the two phrases. Only the CLI's own output contains them, and the start line names no profile.
- In operations/, `x-amz-security-token` appears only in the three window 2 files that ruling 41 already covers. I ran a files-only grep and did not open them. So the laptop loaders' tee'd logs have not carried it.
- The cost of this narrowing is m4.

**(f) README order: the order PASSes.** The README order (apply, secret plus state backup, loads, `--resume`) matches rulings 39 and 3. Actor labels and missing steps are m6; the timeout is m2.

### Work order 3 (experiment-engineer)

**(a) exp-d-query-load-v1 unchanged: PASS.**
- The v1 manifest passes no `--poll-seconds`, so there is no poller (`:806`) and no collection (`:823`).
- The bounds are unchanged (`:732-733`), and the request cap defaults to 20000 (`:697`).
- The v1 dry run is identical to the original (`wo3-dry-run-v1-diff.log:3-4`).
- Self-test scenario 1 checks the same 6 files and 3 describe-services calls with no environment override (`:244-251`).
- run-experiment.sh `--dry-run` with the v1 manifest exits 0.
- What does differ: the hard ceilings are now looser for every manifest.

**(b) Raised ceilings: acceptable. The about 2-minute margin: m3.**
- 600 and 1800 are exactly what the work order needs. 40000 gives about 1.9 times the expected 21,500 requests.
- run-experiment.sh runs only committed manifests and caps maxMinutes at 240.
- Worth noting for the human: the longest run any manifest can now ask for is 4,560 s, against 1,860 s before.

**(c) Read-only allowlist and TZ=UTC: PASS.**
- `aws_read` refuses any call outside `READ_ONLY_CALLS` before starting a subprocess (`:441-442`). All six calls are describe or get. Arguments go as a list, with no shell.
- The self-test refuses `update-service` without starting a subprocess (`:305-306`).
- Every collection call runs with TZ=UTC (`:445`), which the self-test asserts (`:265`). Mutation 2 caught its removal.
- Whether the CLI actually honours TZ is unverified; the agent disclosed this.
- Note: the `ecs-before/after/post-observe` files keep v1's local-time event timestamps. They carry their offsets, so they are unambiguous.

**(d) Collection never changes the exit code or summary.json: PASS.**
- `summary.json` is written in `finally` (`:813-820`) before collection starts (`:823-827`).
- `collect` is wrapped in `try/except Exception`, and `aws_read` never raises (`:443-453`).
- Self-test scenario 3 and mutation 3 confirm this.
- Exception: a maxMinutes TERM (m3).

**(e) Poller thread safety: PASS.**
- The poller shares no mutable state with the workers or the main thread; it has its own file and writer.
- It stops on a `threading.Event`.
- The join is bounded: the subprocess timeout of 20 s kills the `aws` child, and on POSIX `subprocess.run` then only waits for that child.

**(f) Manifest matches ruling 2: PASS.**
- One level at concurrency 8 for 600 s, then 1,500 s of post-observation; the same abort rule as v1; polling every 30 s.
- maxMinutes 40; Haiku and Bedrock calls 0.
- `cpuTargetPercent` 15, with `cpuTargetIs` "experiment setting ... production default 60", and the same statement in the description.
- `expectedFinding` states one scale-out, one scale-in, and no throughput gain while Neptune is the bottleneck, to be reported either way.
- This matches `course.tfvars:64` and the cooldowns at `ecs/main.tf:414-415`.

### Cross-cutting
- Ownership: every change is inside its author's rows (table below).
- Secrets: I grepped the change set and the session-1 logs for access-key, token, secret, private-key and account-ID patterns. The only hits were hashes and AWS's example account `111122223333` in the self-test.

## OWNERSHIP CHECK

| Changed path | Owner | Author matches |
|---|---|---|
| `infra/terraform/README.md`, `envs/course/{course.tfvars,main.tf,variables.tf}`, `modules/{data,ecs}/main.tf` | infra-engineer | yes |
| `scripts/load-stores.sh`, `scripts/README.md` | infra-engineer | yes |
| `load-tests/exp-d-query-load.py` | experiment-engineer | yes |
| `experiments/manifests/exp-d-scale-v1.json` | experiment-engineer | yes |
| `modules/rag/RUNTIME.md`, 9 main Scala files, `RetrievalModeSpec.scala` | rag-engineer | yes |
| `docs/agent-runs/phase-15/agent-report.md` | orchestrator assembly (allowed file name; no `commit.txt` in phase-15) | yes |
| `docs/agent-runs/phase-15/evidence/**` | human architect | writer cannot be verified from the tree |

## FLOOR AND FENCE CHECK
- No change to `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `build.sbt`, `project/**`, `check`, `markTestRun` or the reviewer fence.
- No permission or hook change. No IAM, network or scale-bound change: `service_max_counts` is unchanged, and the CPU target is bounded 10 to 90.
- The session-rule deviations (one `git status`, one `aws --version`) are recorded and accepted in ruling 5.

## EVIDENCE GAPS
- Checkov "all 45 MEDIUM" is not in `wo2-checkov.log`; only the pass and fail counts and the check IDs are.
- The window 3 plan of 89/0/0 is derived, not observed. The agent stated this.
- The filter-pattern match, CLI pagination and the CLI's TZ handling were not tested live. The agent stated this.
- There is no pre-change golden for "FUSED unchanged". The agent stated this.
- The first, failing `sbt check` log was overwritten. shellcheck was not run (not installed).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, from the session-1 logs):
- `wo1-sbt-check.log`: "Tests: succeeded" 191, 111, 122, 115, 49, 46, 141 (10 pending) and 70, all with 0 failed; "markTestRun: tests passed, wrote .../.claude/.last-test-run".
- `wo1-retrieval-spec-2.log`: "Tests: succeeded 6, failed 0".
- `wo3-self-test.log`: "==> OK self-test", exit 0.
- `wo3-dry-run-v1-diff.log`: "IDENTICAL"; 8 bound checks exit 2; 3 mutations exit 4.

VALIDATION RESULTS:
- `wo2-tf-fmt.log`: exit 0.
- `wo2-tf-validate.log`: "Success! The configuration is valid.", aws v6.28.0 from the lock file.
- `wo2-load-stores-checks.log`: `bash -n` exit 0 under bash 3.2.57; dry runs exit 0 and exit 2 as reported.
- `wo3-dry-run-d-scale.log`: the manifest passes the jq checks; run-experiment.sh `--dry-run` exits 0 for both manifests.

ASSUMPTIONS:
- The window 2 Neptune load time in m2 is an upper bound. Intermediate polls were not recorded.
- AWS CLI v2 auto-pagination and `--query` behaviour are taken from known CLI behaviour, not from a run.

RISKS:
- 0 to change depends on AWS restoring the SSE-C block after cleanup apply 2.
- `service_cpu_target_percent = 15` stays in `course.tfvars` after window 3 unless it is reverted.
- The three window 2 files in operations/ that contain `x-amz-security-token` were not opened. They are governed by ruling 41 and are outside this change set.

MANUAL ACTION REQUIRED:
- The human decides on m3 (maxMinutes), m5 (ranked-list method) and check (c) (identity record).
- The human ratifies keeping rag-audit-v3 (check b).
- At the window 3 plan, the human accepts at most the single SSE-C change toward `["SSE-C"]`.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to owners (infra-engineer: m1, m2, m4, m6; experiment-engineer and human: m3; rag-engineer and human: m5, m7), then re-review of any repair; human gate on APPROVE before the window 3 plan pins HEAD.

## Code review 2 (repair-01)

### Summary

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15+16, session 4, code review 2. Read-only re-review of repair-01 (parts A to C) against Phase 15 ruling 6 and the "Repair-01" section of prompt.md, plus a check of window 3 readiness (ruling 3).
VERDICT: APPROVE

All six repaired findings (m1, m2, m3, m4, m6 and m7) are fixed as specified, and nothing regressed. I found no CRITICAL or MAJOR issues. There are five MINOR findings (N1 to N5). N5 is marked PLAUSIBLE because I could not confirm it without running the script. None of the five needs a code change before window 3, and this was the last repair round (ruling 4), so I recommend accepting all five as residual risks. N1 and N3 should be covered by a written operator note before the window, and N4 needs one line of human acceptance. Details are under "Blocking versus residual".

SCOPE REVIEWED:
- `evidence/code-review-2/diff.txt` (all of it), `untracked-diff.txt`, `diffstat.txt` and `untracked.txt`. I compared them with `evidence/code-review-1/diff.txt` by blob index. Since code review 1, only four tracked files changed: `scripts/load-stores.sh` (to 3d87d29), `scripts/README.md` (to 1e4801b), `load-tests/exp-d-query-load.py` (to c8c0df6) and `modules/rag/RUNTIME.md` (to d4d77bc). The untracked manifest changed too. Every infra/** blob and every rag Scala blob is the same as in code review 1.
- Working-tree files, read in full and matched against the diff: `scripts/load-stores.sh`, `scripts/lib/common.sh`, `scripts/run-experiment.sh`, `load-tests/exp-d-query-load.py`, `experiments/manifests/exp-d-scale-v1.json`. I read `scripts/README.md` lines 50-100 and 268-317, and RUNTIME.md sections 14 and 24.
- Context files: `LoadNeptuneCli.scala`, `Cli.scala`, `NeptuneLive.scala`, `Contracts.scala` (RetrievalMode), `Workflow.scala:114`, the ECS log configuration and scaling policy (`infra/terraform/modules/ecs/main.tf`), `.gitignore`, and Phase 14 rulings 38 to 49.
- Logs: everything under `evidence/repair-01/`.

### Findings (ranked)

No CRITICAL. No MAJOR.

**N1 (MINOR). The docs say a rerun of load-stores.sh skips loaded data. For Neptune it starts a second, queued bulk load.**
- Location:
  - `scripts/README.md:309`: "a rerun skips loaded data"
  - `scripts/load-stores.sh:16-17`: "every loader is write-once and idempotent, so a rerun skips what is already loaded"
  - `scripts/load-stores.sh:246`, which always starts a new load
  - `NeptuneLive.scala:50`: `queueRequest(true)`
- Owner: infra-engineer.
- Why this matters now: repair-01 correctly adds exit-1 paths during the status loop, as ruling 6 requires. A filter-log-events API error now exits 1, and so does a missing line after 3 re-reads. Some older exit-1 paths remain over the roughly 30 to 50 serial status tasks of a 2 to 3 h load:
  - `run-task` is called with `aws_once`, so there is no retry;
  - a Fargate start failure gives exit code `None`;
  - a transient Neptune `error:` makes the status task exit 1;
  - the aws login session can expire.
  If the 180-minute timeout fires (exit 6), the load also keeps running on the AWS side.
- Failure scenario: the script stops after `==> NEPTUNE load=<id>`. The operator follows the README and reruns the window 3 command unchanged. The start task stages the data again and starts a second load of the same run. Because of `queueRequest(true)`, the second load is queued behind the first and then re-reads about 29M records as duplicates, which took up to 108 minutes in window 2. That run likely exits 6 at 180 minutes, costs hours of the 10 h window, and keeps db.r6g.large running longer.
- Fix (no code change now): before the window, the human writes an operator note in the phase-15 human gate or `operations/rulings/`. Once `==> NEPTUNE load=<id>` has printed, never rerun without `neptune` in `--skip`. Check the printed load ID with manual `--status` tasks, as in Phase 14 ruling 41, until LOAD_COMPLETED. Then rerun with `--skip s3-store-conversion,s3-embed,s3-window-summary,neptune` and record the load ID next to that run's `run.json`, where `neptuneLoadId` will be null. Phase 17 (infra-engineer): add a `--neptune-load-id <id>` resume flag and correct the two sentences.

**N2 (MINOR). The error read misses the staging-problem lines, and Part A's coverage claim is inaccurate.**
- Location: `scripts/load-stores.sh:173` (`ERROR_PATTERN`) and repair-01.md Part A, which says the pattern covers "`stage:`/`staging refused`" and "Cli.report `violations`".
- Owner: infra-engineer.
- Verified:
  - When staging fails, LoadNeptuneCli prints `stage: <problem>` lines (`LoadNeptuneCli.scala:101`), for example "`<key> is missing from the bucket`", a SHA mismatch, or "timeout after N of M objects". None of these contains any of the nine terms.
  - Only the summary line `staging refused (N problems); nothing loaded` matches (through `refused` and `nothing loaded`).
  - For violations, only the header `violations N` matches. The per-code and per-record lines (`Cli.scala:117-121`) do not.
  - The terms are case-sensitive. JVM launcher lines such as `Error: ...` are missed unless they contain `Exception` or `failed`.
- Failure scenario: the start task fails during staging. `neptune-start-<task>-errors.txt` then holds only "staging refused (1 problems); nothing loaded", with no key and no cause. The cause is only in CloudWatch, which the die message names (`load-stores.sh:236`).
- Fix (Phase 17, or accept now): add `?"stage:"` to `ERROR_PATTERN` and correct the Part A claim. In window 3 the staging source is the set of window 2 objects that staged and loaded successfully (ruling 43), so the risk is low.

**N3 (MINOR). The window 3 order does not say to wait for the upsize to finish before the load.**
- Location: `scripts/README.md:81-83`. The resize back (`:87`) says "waits until available". The resize up does not.
- Owner: infra-engineer (the human may cover it in an operator note).
- Failure scenario: the human starts load-stores.sh while the `--apply-immediately` modification is still pending or in progress. Window 2's resize took 11 minutes (ruling 43). If the instance restarts after `startLoaderJob`, the load fails or is lost mid-way, and the status task exits 1 (then see N1). If it restarts during the start task, the start exits 1 and a rerun is safe.
- Fix: the same operator note says to wait until the instance status is `available`, its class is `db.r6g.large`, and it has no pending modifications, before load-stores.sh.

**N4 (MINOR, scope). Part B lowered `COLLECT_BUDGET_S` from 150 to 90 s and `collectBudgetSeconds` from 150 to 90 in the manifest, which the Part B text did not ask for.**
- Location: `load-tests/exp-d-query-load.py:84`; `experiments/manifests/exp-d-scale-v1.json:22,37`.
- Owner: experiment-engineer. The human decides.
- Assessment:
  - The change is inside the agent's own paths and matches code review 1's own alternative fix for m3.
  - It is needed to meet ruling 6's "at least 5 minutes of margin" under the agent's worst-case sum (check 2).
  - It is safe, and the agent disclosed it.
  - "As the orchestrator permitted" is not a human ruling.
- Fix: one line of human acceptance in the phase-15 human gate. No re-repair.

**N5 (MINOR, PLAUSIBLE). The failure path does not re-read the log.**
- Location: `scripts/load-stores.sh:233-234`.
- Owner: infra-engineer.
- After a non-zero exit, both reads run once, with no lag re-read. Repair-01 treats ingestion lag as real on the exit-0 path, so by the same reasoning the result and error records of a failed task could come out empty or partial.
- The die message names the CloudWatch group and stream, and the log groups outlive the runtime cleanup, so the diagnosis is still available.
- I could not confirm the lag without a live run.
- Fix (Phase 17): apply the same bounded re-read on this path, or accept.

### Blocking versus residual (ruling 4: last repair round)

- **Must block window 3:** none of N1 to N5.
- **Preconditions to settle before the plan pins HEAD (none is a code change):**
  1. The operator note covering N1 and N3, written by the human in the human gate or `operations/rulings/`.
  2. Human acceptance of N4.
  3. Commit the whole change set. The untracked `exp-d-scale-v1.json` and `RetrievalModeSpec.scala` must be committed, because `run-experiment.sh:84-86` refuses an untracked manifest and `require_clean_tree` (`common.sh:71-81`) refuses any untracked file outside `operations/` and `experiments/results/`. The orchestrator's append of this report must come before that commit. After the pin, nothing may be written under `docs/agent-runs/` until cleanup, or `load-stores.sh` and `run-experiment.sh` exit 3.
- **Accept as residual risks:** N1 and N3 (mitigated by the note), N2, N4 and N5.

### Per-check answers

#### Check 1: each repaired finding against ruling 6 and Repair-01

- **m1: FIXED.**
  - Exit-0 path: `run_loader_task` calls `expect_line` (`load-stores.sh:226-229`). That is 1 read plus up to 3 re-reads with `sleep 15` between them (`:196-204`), then `die ... 1` naming `$LOG_GROUP $stream` (`:203`).
  - The start line goes through the same path, with the regex `started load [^ ;]+` (`:246`). The die at `:249` stays as a backstop.
  - API errors: `raw="$(aws_retry ...)" || die ... 1` (`:181-183`). `local raw n` is declared separately, so the status is not masked. Unreadable JSON also exits 1 (`:184`).
  - `""` is gone from the in-progress set (`:259`), so an empty status reaches `*) die ... 1`.
  - `die` calls `exit`, which leaves the shell even though `_load_done` runs inside `wait_until`'s `while ! "$@"`, where errexit is suspended.
  - Fixture cases 2 to 4 pass. They source the real functions, extracted with sed (`r01-a-fixture.log:9,115-139`).
- **m2 and m6: FIXED.**
  - The README window 3 command has `--neptune-timeout-minutes 180` (`README.md:83-84`).
  - Line 76-77 says "every script is human-run ... No agent runs any of these steps."
  - The order: plan, apply, secret and state backup, upsize to db.r6g.large, loads, downsize, `--resume`, warm-up (rulings 46 and 47), smoke, demo and D-scale, archive, cleanup runtime, state backup (`:78-92`).
  - The load-stores section states the default 60, the maximum 180, and that window 3 uses 180 (`:290-291`).
  - The waiting gap is N3.
- **m3: FIXED.**
  - `maxMinutes` is 45 (`exp-d-scale-v1.json:19`).
  - `ecs_timeout = POLL_CALL_TIMEOUT_S if a.poll_seconds else None` (`exp-d-query-load.py:694`) feeds all three `ecs_counts` calls (`:698,702,716`). `TimeoutExpired` is caught and recorded (`:439-440`).
  - `collect.json` is written atomically with `os.replace` (`:538-542`) after every `get` (`:552`), after the no-alarm entry (`:569`) and at the end (`:583`).
  - `head["calls"]` is the same list as `index`, so every save is complete.
  - The self-test asserts each part, and five mutations each fail at their own assertion (`r01-b-mutations.log`).
- **m4: FIXED, with one breadth gap (N2).**
  - The status filter keeps `errors parsing` (`:253`).
  - A non-zero exit saves the result lines and a second `ERROR_PATTERN` read to `-errors.txt`, prints both, and exits 1 (`:230-236`).
  - `SECRET_RE` with `grep -i` covers all six required strings (`:171`). Matching lines are dropped and counted in the record and in a WARN (`:185-190`).
  - Fixture case 1 shows: no `FAKE` value written, 6 lines counted, the status, errors-parsing and errorCode lines kept, a tabbed event kept as one line (`r01-a-fixture.log:98-114`).
- **m7: FIXED.**
  - RUNTIME.md:351 now ends "... and `retrieval=<MODE>` (the enum name, `GRAPH_ONLY` or `VECTOR_ONLY`) when the retrieval mode is not FUSED (section 24)".
  - That matches `Workflow.scala:114`. `Contracts.scala:147-151` has no `toString` override, so the input renders as the enum name.
  - No Scala blob changed.

#### Check 2: the 90 s collection budget, recomputed from the harness code

- **Total.** maxMinutes starts at the fork of the command (`run-experiment.sh:99-102`), so the harness setup counts. The parts:
  - setup: about 3 s, observed in window 2; no subprocess timeout (`:803,812,825`)
  - preflight: 6 GETs × 10 s socket timeout = 60 s. A timeout gives status 0, which exits 3, so 60 s is only reached by slow 2xx responses (`:629-645`).
  - three `ecs_counts` calls × 20 s = 60 s
  - the load: 600 s
  - worker tail: about 30 s. One in-flight GET can use the connect timeout plus the read timeout, about 20 s (`:593`).
  - post-observation: 1,500 s
  - poller join: 20 s (`aws_read` with a 20 s cap, `:489-491`)
  - collection: 90 s. Each call gets `min(60, left)`, and a call with 1 s or less left is skipped (`:547`), so the total cannot exceed about 91 s.
  - Sum: 2,363 s, about 39.4 min, leaving 5.6 min under 45. This matches the agent. With 150 s the sum is 2,423 s, about 40.4 min, leaving 4.6 min, which breaks ruling 6.
- **Assumptions behind the bound.**
  - The bound assumes 6 paths. `request_list` allows up to 10 evidence IDs and 5 researchers (`:395,399-405`), so a window 3 smoke record citing more IDs raises the preflight bound.
  - The setup calls are unbounded apart from the CLI's own timeouts.
  - Neither is likely, and both are disclosed.
- **Is 90 s enough for the seven calls?** Yes.
  - There is one CPU target-tracking policy per service (`ecs/main.tf:403-418`), which gives 2 alarms. The calls are activities, policies, ECS service, alarms, 2 histories and metrics: 7 in all.
  - A typical CLI v2 call takes about 1 to 2 s, so the seven need about 7 to 14 s, at least 6 times under the budget.
  - One hung call uses 60 s and leaves 30 s for the rest. Calls that the budget skips are recorded, never fatal.
  - Metrics, scaling activities and alarm history can be read again later, read-only. ECS service events cannot once cleanup removes the service, but that call is third in the order.
- **Scope:** this is a deviation from the Part B text and needs a one-line human ruling (N4). It does not need another repair.

#### Check 3: regressions

- **load-stores.sh exit codes: unchanged.**
  - 0, 1, 2, 3 and 6 are as before.
  - Pre-existing and not touched by the repair: when the start task runs at top level under `set -e`, a failing `run-task` or `describe-tasks` command substitution (`:213,222`) exits with the aws CLI's code (254 or 255), not 1.
- **run.json: unchanged** from code review 1. `neptuneLoadId` is null only when Neptune is skipped, and `skippedSteps` is added (`:272-279`).
- **The filter-log-events JSON read: correct, by known CLI semantics.**
  - In AWS CLI v2, `--output json` uses a fully buffered formatter. It calls `build_full_result()` across all `nextToken` pages and then applies `--query` once. `--output text` applies `--query` per page, so the switch to JSON is also more correct.
  - The FilterLogEvents paginator's result key includes `events`.
  - An empty result gives `[]`, and a missing key gives `null`. `jq '.[]?'` handles both.
  - Multi-line messages:
    - the awslogs driver here has no multiline pattern (`ecs/main.tf:171-173`), so every printed line is its own event;
    - `gsub("[\\t\\r\\n]"; " ")` collapses any embedded control characters, so there is exactly one record line per event (fixture case 1).
  - None of this was observed live (evidence gap).
- **ERROR_PATTERN syntax: valid in form.** `?"term"` is an OR term. Quoted phrases are exact, case-sensitive substrings, and a colon or hyphen inside quotes is allowed. The pattern is well under the 1,024-character limit.
- **ERROR_PATTERN breadth: wide enough for every failure path except staging details** (N2). It covers `error:`, `errors parsing`, the errorLogs JSON (`errorCode`, from `NeptuneLive.scala:74`), `pre-load`, `staging refused` and `nothing loaded`, and `Cli.exitCode` `<name> failed: <Exception>`, including OutOfMemoryError (through `failed`). It is also wide in the other direction: `failed` and `Exception` can match DEBUG SDK lines, but only after a failure, and every line still passes the credential filter.
- **exp-d-query-load-v1: unchanged.**
  - `ecs_counts` with `timeout=None` makes the same `subprocess.run` call. The new `except` and `r is not None` branches cannot change the outcome when no timeout is set.
  - The v1 dry-run output is IDENTICAL to the work order 3 harness (`r01-b-dry-run-v1-diff.log:5-6`), which session 1 showed to be identical to HEAD.
  - The self-test asserts timeout None for v1 (`:303`).
  - Only the `--help` docstring changed.
- **FUSED retrieval: unchanged.** No rag Scala or test blob changed since code review 1, so its PASS stands.
- **Secrets:**
  - The Neptune records pass only through `SECRET_RE`. The terminal echo (`cat`) prints the filtered files only.
  - The harness still holds the API key in memory only.
  - Collect errors keep at most 300 characters of AWS CLI stderr.
  - A grep of the code-review-2 change set and the repair-01 logs for key, token, private-key and 12-digit patterns found only the example account `111122223333`.

#### Check 4: scope

- Part A touched only `scripts/load-stores.sh` and `scripts/README.md`. Part B touched only `load-tests/exp-d-query-load.py` and `experiments/manifests/exp-d-scale-v1.json`. Part C touched only `modules/rag/RUNTIME.md`.
- No change under `scripts/cleanup.sh`, `labels/`, `experiments/benchmark/`, `modules/services/`, `infra/**` (blobs unchanged), `.claude/**`, `CLAUDE.md`, `docs/agents.md` or the build.
- The work order 1 to 3 changes are unchanged from code review 1.

#### Check 5: readiness for window 3

- **Clean-tree gate:** see the preconditions under "Blocking versus residual".
  - `scratch/` is gitignored, so logs and plans there are fine.
  - Running the harness as a script creates no `__pycache__`.
- **Flags:** the exact window 3 command dry-runs to exit 0 with "(180 min)". `--neptune-timeout-minutes 181` exits 2 (`r01-a-checks.log:7-33`).
- **The 180-minute timeout is used end to end:** `NEPTUNE_MIN` (`:80`), the 5 to 180 check (`:98`), the dry-run line (`:114`), then `wait_until ... $((NEPTUNE_MIN * 60))` (`:263`). The deadline is checked after each iteration, so the real ceiling is about 180 minutes plus one iteration.
- **Polling cadence:** each status iteration is Fargate start and run, `_stopped` polls every 15 s, one immediate read (plus up to 45 s if lagging), then `sleep 120`. That is about 3.5 to 4 minutes per iteration, about 30 status tasks for a 108-minute load and at most about 50 within 180 minutes. The start task's 1,800 s wait is above the 20-minute staging bound (`LoadNeptuneCli.scala:188`).
- **Transient errors mid-load:**
  - `aws_retry` gives 5 SDK attempts for describe-tasks and filter-log-events.
  - `run-task` has one attempt (`aws_once`).
  - Any of the failures listed in N1 ends the script with exit 1 while the load continues. Exit 1 on a filter-log-events error is what ruling 6 asks for, so the gap is the recovery path (N1), not the code.
  - The ruling 3 rule "aws login immediately before each long script" covers session expiry, which was seen in window 2 (ruling 48). load-stores.sh can run for 3 h or more (Neptune, then DynamoDB and Vectors).
- **D-scale:** the 45-minute limit with a 5.6-minute worst-case margin is confirmed (check 2). `run-experiment.sh` checks the deadline every 5 s.

### OWNERSHIP CHECK

| Changed path | Owner (docs/agents.md) | Author | Matches |
|---|---|---|---|
| `scripts/load-stores.sh`, `scripts/README.md` | infra-engineer | infra-engineer (Part A) | yes |
| `load-tests/exp-d-query-load.py`, `experiments/manifests/exp-d-scale-v1.json` | experiment-engineer | experiment-engineer (Part B) | yes |
| `modules/rag/RUNTIME.md` | rag-engineer | rag-engineer (Part C) | yes |
| `infra/terraform/**`, rag Scala, `RetrievalModeSpec.scala` | infra-engineer / rag-engineer | unchanged since code review 1 | yes |
| `docs/agent-runs/phase-15/repair-01.md` | orchestrator assembly (allowed name, no `commit.txt`) | orchestrator | yes |
| `docs/agent-runs/phase-15/evidence/**` | human architect | writer not verifiable from the tree | n/a |

### FLOOR AND FENCE CHECK

- No change to hooks, permissions, `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `build.sbt`, `project/**`, `check`, `markTestRun` or the reviewer fence.
- No IAM, network or scale-bound change.
- Session-rule events:
  - Part B: one guard block ([secret-env-dump] false positive on `kw.get("env")`). It was not worked around; the agent used the Edit tool, as docs/agents.md directs.
  - Part A: used `aws-docs`, which infra-engineer is granted.
  - Neither part made any git, aws, terraform or sbt call (per their reports and the check scripts).

### EVIDENCE GAPS

- Nothing ran against AWS. Not observed live:
  - the CloudWatch acceptance of `ERROR_PATTERN` and the two status patterns;
  - CLI v2 pagination with `--output json --query`;
  - FilterLogEvents ingestion lag;
  - `TZ=UTC` handling;
  - the timeout behaviour of a real `aws` child.
- The load-stores fixture tests `task_lines` and `expect_line` only. `run_loader_task` and the status loop were not run end to end, even with a fake ECS. shellcheck was not run (not installed).
- The worst-case arithmetic is a sum of bounds, not a measurement.
- `.gitignore:28` ignores `*.log`, so `evidence/session-1/*.log` and `evidence/repair-01/*.log` are not staged by a plain `git add`. The human should force-add them or confirm how earlier phases handled this, so the cited logs reach history.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied in the logs):
- `r01-a-fixture.log`: "fixture checks: 20 passed, 0 failed", exit 0 (/bin/bash 3.2.57).
- `r01-b-self-test.log`: "==> OK self-test", exit=0.
- `r01-b-mutations.log`: mutations a to e each exit=4 at their assertion.

VALIDATION RESULTS:
- `r01-a-checks.log`: `bash -n` and `/bin/bash -n` exit 0. Window 3 dry-run exit 0 (180 min); default dry-run exit 0 (60 min); `181`, `--skip bogus` and a missing `--store-run` each exit 2.
- `r01-b-dry-run-v1-diff.log`: v1 IDENTICAL; D-scale differs only in 150 to 90.
- `r01-b-run-experiment-dry-run.log`: both manifests exit 0 ("at most 45 min" and "at most 25 min"); limits read `{"maxMinutes":45,"plannedMinutes":38,"collectBudgetSeconds":90}`.

ASSUMPTIONS: AWS CLI v2 formatter and paginator behaviour and CloudWatch filter-pattern semantics as described in check 3, from known CLI source and documentation, not from a run.

RISKS:
- N1 to N5.
- aws login expiry during a load of 3 h or more.
- The window 3 preflight bound depends on how many IDs the window 3 smoke record cites.

MANUAL ACTION REQUIRED:
- The human accepts N4 and records N1 to N5 as residual risks in the phase-15 human gate.
- The human writes the N1 and N3 operator note before the plan.
- The human commits the full change set, including the untracked manifest and spec, after this report is appended.
- `service_cpu_target_percent` is still to be reverted to 60 after window 3 (ruling 6; not part of repair-01).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of repair-01 with N1 to N5 as residual risks (no further repair round, ruling 4), the commit, then the window 3 plan pins HEAD.

## Code review 3 (work order 4)

### Summary

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15+16, session 6, code review 3 (work order 4). This is a read-only review of the Part A changes (experiment-engineer) and the Part B change (infra-engineer), checked against Phase 15 ruling 11 and the "Work order 4" section of prompt.md.
VERDICT: REQUEST_CHANGES

I found one MAJOR finding and no CRITICAL ones. With the real BenchmarkCli, the harness makes fused answer 12 questions (B01 to B12) while the other two modes answer 10 (B01 to B10). That is 32 runs in total, not 30. The resulting problems:
- It breaks Phase 14 ruling 31 ("at most 10 benchmark questions x 3 modes").
- The maxMinutes margin falls to 3 minutes, but A2 requires 10.
- The three modes no longer answer the same questions.
- For the two fused-only questions, the blind sheets reveal which mode produced what.

The stub self-test cannot catch this, because the stub does not model resume.

There are six MINOR findings. One of them (E-m1) is UNVERIFIED. Part B is correct.

SCOPE REVIEWED:
- The change set: `evidence/code-review-3/diff.txt`, `untracked-diff.txt`, `diffstat.txt` and `untracked.txt`. I read every new file in full in the working tree:
  - `experiments/harness/exp-e-retrieval-modes.sh`
  - `experiments/manifests/exp-e-retrieval-modes-v1.json`
  - `experiments/analysis/summarize-exp-e.py`, `e-label-sheets.py` and `derive-tables.py`
  - `experiments/derived/README.md`, `exp-d/README.md`, `exp-d-scale/README.md` and `size-scaling/README.md`
- The Part B edits in context: `infra/terraform/envs/course/course.tfvars:62-64`, `variables.tf:401-410` and `infra/terraform/README.md:187-191`.
- The real code the harness drives:
  - `modules/rag/.../cli/BenchmarkCli.scala` (all), `Benchmark.scala` (all) and `AskCli.scala` (all)
  - `Workflow.scala:96-116`, `Audit.scala` and `Records.scala:426-544`
  - `SemanticContext.scala:35-74`, `StableIds.scala` (runId and evidenceId) and `RUNTIME.md` sections on evidence IDs and deadlines
  - `storage/cli/Cli.scala:126-138` and `Dynamo.scala` (FileRuntimeRecords, item file format)
- The scripts and build: `scripts/run-experiment.sh` and `scripts/lib/common.sh` (all), `build.sbt:100-160` and `.gitignore`.
- Earlier evidence:
  - Phase 10 `gate-live-benchmark-step1.txt` and `-step2.txt` (real BenchmarkCli output)
  - Phase 14 ruling 31 (`phase-14/prompt.md:259`)
  - The D and F raw layouts under `experiments/results/`
  - `load-tests/exp-d-query-load.py:797`
- Every log under `evidence/wo4/`.

### Findings (ranked)

No CRITICAL.

**E-M1 (MAJOR). With the real BenchmarkCli, fused answers N + 2 questions. Ruling 31 is broken, the A2 margin is broken, the modes answer different questions, and the sheets lose their blinding.**

- Location:
  - `experiments/harness/exp-e-retrieval-modes.sh:208` (check: fused `--max 2`) and `:227-228` (fused again with `--max $N`).
  - The stub at `:69` always prints "0 already done, answering $max now", so it never models resume.
  - `BenchmarkCli.scala:78-81` and `:104-106`: `--max` is applied as `pending.take(max)`, so it limits the number of new pending questions, not the total.
  - Observed in Phase 10: `gate-live-benchmark-step2.txt:7` uses `--max 30` and line 16 prints "2 already done, answering 28 now".
  - Manifest `:11`, `:16`, `:29`, `:30` and `:36` all assume 30 questions ("check 2, fused 8 more").
- Mechanics:
  - The check step and the fused step share one benchmark run. `Workflow.runIdFor` adds `retrieval=` only for non-FUSED modes (`Workflow.scala:114`), and `--max`, `--out` and `--per-minute` are not run-ID inputs (`AskCli.scala:172-183`). Resume therefore works.
  - Step 2 then answers 10 more pending questions, B03 to B12.
  - graph-only and vector-only each answer B01 to B10.
- Failure scenario and impact:
  1. **Ruling 31 is broken.** There are 32 Bedrock question runs, and fused has 12 questions.
  2. **The time margin is too small.** The worst case is 4 x 300 s + 32 x 210 s = 7,920 s = 132 min, which leaves 3 minutes of margin under `maxMinutes` 135. A2 requires at least 10. The manifest's `questionsTotal`, `haikuRequestsMax` (256, not 240) and `embeddingCallsMax` (22, not 20) are also wrong. The budget still holds: 32 x 0.088 = 2.82 USD, under 3.
  3. **The summarizer compares different questions.** It compares fused over 12 questions with the other modes over 10 (`summarize-exp-e.py:124`, `mode_row`), and it scores fused on B11 and B12 too.
  4. **The blinding breaks for B11 and B12.** `e-label-sheets.py:170` takes the union of question IDs, and `:212-219` keeps only the modes present. So B11 and B12 each show a single answer (key `-A`), which can only be fused. Every pooled item for those questions is also fused-only, which reveals "which modes retrieved it". Ruling 11 and A4 forbid this.
- Fix:
  - In step 2, run fused with `--max $((N - 2))` so that fused totals N (fused "resumes" the check's 2 questions, as A1 says). Require N >= 3, or skip fused step 2 when N = 2, because BenchmarkCli rejects `--max 0` (`BenchmarkCli.scala:54`).
  - Make the stub emulate resume: count `questions/*.json` under `--out` and answer at most `--max` pending questions. Assert that each mode ends with exactly N results.
  - Update the dry-run lines and the "recorded command equals the work-order text" assertion.
  - Because this departs from the literal "each with `--max <N>`" text, the human records it in a ruling.
  - The manifest numbers then become correct as written.
- Owner: experiment-engineer, with a human ruling on the command text.
- Must fix before E run: **yes**.

**E-m1 (MINOR, UNVERIFIED). TERM at maxMinutes may not reach the JVM, and the reported "forked JVM" risk is misdescribed.**

- Location: `exp-e-retrieval-modes.sh:179-186` and `:193-197`, plus `run-experiment.sh:108-110`.
- What I checked: rag's `runMain` is not forked. The rag project (`build.sbt:126-127`) has no `run / fork`, unlike `sparkAnalytics` (`:117`) and `services` (`:146`). The Phase 10 log prints `[info] running`, which is the in-process form. So the agent's RISK "if sbt forks one for runMain" does not apply.
- The real dependency: `$!` is the PID of the `sbt` runner script, and `kill -TERM` reaches only that PID. Whether the java child dies depends on how the installed sbt runner handles TERM; the upstream runner runs java without `exec`.
- Why UNVERIFIED: the runner script is outside the repository and not readable here.
- Failure scenario: maxMinutes fires. The harness exits 143 and run-experiment.sh makes the directory read-only. An orphaned JVM keeps calling Bedrock for the rest of the current mode (at most 10 questions) and then fails on the read-only directory.
- Fix: start sbt in its own process group and signal the group, or send TERM to the children too (`pkill -TERM -P "$CHILD"`) and then wait. Add a stub that spawns a child, and test that both die.
- Owner: experiment-engineer.
- Must fix before E run: no. The damage is bounded by `--max` per mode, but the fix is cheap enough for the same repair round.

**E-m2 (MINOR). Raw files land in `<stamp>/raw/raw/<mode>/`, unlike D and F.**

- Location: `exp-e-retrieval-modes.sh:163` (`RAW="$SCHOLARCHAIN_EXPERIMENT_DIR/raw"`) and `run-experiment.sh:99` (`SCHOLARCHAIN_EXPERIMENT_DIR="$DIR/raw"`).
- What I checked: the doubled path is real. D writes directly into the variable (`load-tests/exp-d-query-load.py:797`, giving `exp-d-scale-v1/20261120T001853Z/raw/summary.json`), and so does F (`exp-f-failure-recovery-v1/20261118T062000Z/raw/summary.json`). The run record says `rawResults: "raw/"` (`run-experiment.sh:126`).
- Impact: functionally harmless, because every tool takes `--raw` and the manifest documents the layout (`:49`). But it is inconsistent with the earlier experiments and with run-record.json, and it will be immutable once E runs.
- Fix (recommended): `RAW="$SCHOLARCHAIN_EXPERIMENT_DIR"`, then update the manifest `outputLayout`, `rawOutputs` and `analysis` fields and the documented commands (`--raw <stamp>/raw`). This departs from the literal "`$SCHOLARCHAIN_EXPERIMENT_DIR/raw/<mode>/`" text, so the human decides. Keeping the current layout is acceptable if ruled.
- Owner: experiment-engineer, with a human ruling.
- Must fix before E run: no, but decide before E.

**E-m3 (MINOR). The summarizer does not restrict to the questions all modes share, and it mislabels questions that have nothing to label.**

- Location: `summarize-exp-e.py:119-128` and `:193`.
- What I checked:
  - Per-mode rows and quality metrics use whatever questions each mode has.
  - The Markdown table drops `questions_ids` (`:193`), so a difference in question sets is invisible there.
  - A question with no pooled item, for example one that every mode abstains on before the bundle stage (B09, AMBIGUOUS_RESEARCHER, in Phase 10), is not in `pool`. It is therefore counted under `questions_incompletely_labelled`, although there is nothing to label.
- Failure scenario: a mode stops at a failed question (BenchmarkCli stops at the first failure). Its recall and MRR then come from fewer or different questions, with no warning in the Markdown, and the "incompletely labelled" count invites the human to look for labels that cannot exist.
- Fix:
  - Compute the quality metrics over the intersection of question IDs present in all three modes, and list the excluded IDs.
  - Add a `questions_without_items` category.
  - Print a "question sets differ" line in the Markdown when they do.
- Owner: experiment-engineer.
- Must fix before E run: no (fix before the summary is used).

**E-m4 (MINOR). The D-scale throughput-by-task-count table is computed correctly but needs more caveats.**

- Location: `derive-tables.py:302-305`; `experiments/derived/exp-d-scale/README.md:5`, `:51-52` and `:72-81`; `agent-report.md:421`.
- What I checked:
  - The arithmetic is correct: the two phases sum to 21,962 requests (11,229 + 10,733), which matches the level total.
- Missing caveats:
  - The one-task phase includes the level's warm-up. ALB requests per minute rise from 1,546 to 2,241 before scale-out (lines 72-76), and then run from 2,324 to 2,472 (lines 77-81), so the trend began before the second task.
  - A warmed single task at concurrency 8 already served 35.72 requests per second in window 2 (`exp-d/README.md:19`).
  - Neptune CPU stays at about 97 percent in both phases.
  - "Registered" is not the same as "healthy": the split uses the registration event.
- Impact: the report's "this goes against the manifest's expected finding" overstates the evidence, and the durable derived README gives none of these caveats.
- Fix: add the caveats to the D-scale note. Optionally add a steady-state comparison (the last full one-task minute against the two-task minutes), labelled as such.
- Owner: experiment-engineer.
- Must fix before E run: no.

**E-m5 (MINOR). The summarizer self-test count is overstated.**

- Location: `agent-report.md:386` says "14 pass". `wo4-a3-self-test.log` shows 13 PASS lines, and `summarize-exp-e.py:241-292` makes exactly 13 `check(...)` calls.
- Fix: correct the count in the record.
- Owner: experiment-engineer (the report).
- Must fix before E run: no.

**E-m6 (MINOR, process). The agent repeated a forbidden read-only git command.**

- Location: `agent-report.md:375` (`git -C . status --porcelain ... | head -0`).
- What I checked: ruling 11 forbids git, and ruling 5 (`prompt.md:66`) recorded a session 1 git status deviation as "not to be repeated". The agent reported it honestly, and its output was discarded. No artifact depends on it.
- Fix: human decision (accept as recorded).
- Owner: experiment-engineer.
- Must fix before E run: no.

### Must fix before the E run

- E-M1 (fused total = N, a stub that models resume, and a human ruling on the deviation from the literal command text).
- Decide E-m2 (the output layout) before E, because raw results are immutable afterwards.

### Per-check answers

#### Check 1: A1 to A6 and B1

- **A1 (harness): conforms except E-M1.**
  - Environment and preconditions: it reads SCHOLARCHAIN_EXPERIMENT_DIR and AWS_PROFILE (`:153-158`) and refuses with exit 3 if another sbt is running (`:159-160`).
  - Step 1 gate (`:208-223`) is stricter than the text. It also requires sbt exit 0, and it scans all of stdout and stderr. Being stricter can only stop the run more often, so I accept it.
  - Mode order (`:10`, `:227`): correct, `--per-minute 8`, each mode in its own directory.
  - Command text (`:54-56`): matches the work order. I compared it with the dry-run log, lines 4-8.
  - Records: the `.txt` outputs and `harness.json` (`:171-177`, `:199-202`) are as required.
  - Self-tests: `--dry-run` and `--self-test` exist. The self-test covers the check stop and a failing mode, but not resume (E-M1).
- **A2 (manifest):** the fields match the work order. `metrics` is empty. The run-experiment.sh dry-run passed (exit 0, per `wo4-a2-run-experiment-dry-run.log`). The worst case is wrong under E-M1.
- **A3 (summarizer):** every listed per-mode field is present. It writes CSV and Markdown, and "not labelled yet" when there are no labels. Recall at 5 and 10 states the cap (`:37-39`). MRR and the rating means are computed. It never infers relevance. The self-test uses fixtures. See E-m3.
- **A4 (sheets):** conforms; see check 2.
- **A5 (derived tables):** sources carry sha256 values, gaps are reported, and the tables were run once. The three reported gaps are real. For example, the run.json of `operations/pipeline-runs/20261116T063500Z-fraction25/` is genuinely absent: that folder has `steps.json` and other files but no `run.json`. See E-m4.
- **A6 (checks):** `bash -n`, all four self-tests, the dry-runs and the run-experiment dry-run are logged with exit 0. I found no `__pycache__` under `experiments/` or `load-tests/` (Glob). No sbt check was needed, because no Scala or sbt source changed.
- **B1:** conforms; see check 7.

#### Check 2: labelling safety (ruling 11)

- **Templates:** the `relevant`, `usefulness` and `clarity` columns are written empty (`e-label-sheets.py:210`, `:218`).
- **Relevance sheet:**
  - It renders only the evidence type, the sorted facts and the text (`:118-127`). It never shows the evidence ID, sourceUri, chunkId, score, rank, mode or verification status.
  - Items are deduplicated by evidence ID, sorted by ID and then shuffled with a seed per question (`:195-196`). Item keys are numbered after the shuffle.
  - Deduplicating across modes is valid: evidence IDs come from data run IDs, not the runtime run (`SemanticContext.scala:45,58`; RUNTIME.md "Evidence IDs").
  - The evidence type (and, for semantic items, the `index` fact) shows whether the graph or vector side produced an item. A1 requires the evidence type, so this is inherent, not a defect.
- **Keys:** keys go to a required, separate `--keys-out` directory. The tool refuses nesting in either direction, refuses paths under labels/ or the raw results, and refuses to overwrite (`:158-167`).
- **Summarizer:** metrics come only from the human's relevance and ratings files plus the key files. Values other than 1, 0 or empty are refused. A question with any unlabelled item is not scored (`:110-120`). The fixture labels in the self-test are synthetic and stay in a temporary directory.
- **Open item for the human (not a defect):** the answers sheet shows each claim's verification status and the abstention reason and detail (`:136`, `:141`). These are part of the answer as the API delivers it (assumption 4), but they may influence ratings. Vector-only answers will be recognisable anyway from their NO_VERIFIED_CLAIM abstentions. The human should confirm this is acceptable under ruling 11.
- **Blinding leak:** E-M1 reveals the mode for B11 and B12, and any failed mode reveals the mode through the answer count per question (`:212-219`).

#### Check 3: the harness against the real code

- **"plan sources:" line:** the real line is `plan sources: MODEL 2; outcomes: ANSWERED 2` (`BenchmarkCli.scala:188`; Phase 10 step 1 line 33). The harness keeps the last such line with `grep | tail -1` and matches MODEL as a whole word (`:209`, `:213`). FALLBACK_RULES and BUDGET_EXHAUSTED appear in that line as plan-source and outcome keys (`Benchmark.scala:770-771`), so the scan catches them.
- **"Exception":** CLI output goes to stdout (`Cli.scala:127`), and the harness scans stdout and stderr. A harmless "Exception" string from the SDK on stderr would stop the run. That is a safe failure costing 2 questions, and Phase 10 output contained none.
- **Benchmark run ID:** the real text is `benchmark run run-<32 hex>: ...` (`BenchmarkCli.scala:80`). The harness regex at `:198` matches it and does not match the `runtime run run-...` line.
- **Output files:** the summarizer and sheet paths match the real keys:
  - `questions/<id>.json` (`BenchmarkCli.scala:75`)
  - audit `logs/rag-runtime/snapshot=/run=/<qry>.json` and answer `reports/rag-runtime/.../<qry>.answer.json` (`Audit.scala:27-30`)
  - `dynamodb/snapshot=/run=/EvidenceBundle.jsonl` with `{"Item":...}` lines (`AskCli.scala:294`, `Dynamo.scala:502-505,769`)
  - `payloadRef` objects relative to `s3/` (`Audit.scala:109-116`)
  - Options serialize as null or the value (Phase 9 audits show `"abstentionReason": null`), and `removedClaims` exists (`Records.scala:535`).
- **Step 1 gate and mode order:** both correct. The fused resume works mechanically but yields N + 2 questions (E-M1).
- **`--profile` and AWS_PROFILE:** run-experiment.sh exports AWS_PROFILE and AWS_REGION (`common.sh:83-87`). The harness validates the profile name (`:158`) and passes `--profile $AWS_PROFILE --region us-east-2`. Consistent.
- **TERM:** see E-m1. The JVM is not forked; reaching it through the sbt runner script is UNVERIFIED.
- **Other-process check (`:133-136`):** it does not match itself. awk's own command line holds the bracketed forms (`[s]bt-launch` and so on), which do not match the pattern. The harness, run-experiment.sh and `ps` command lines contain no matching token. It does refuse when an sbt server is running (sbtn, `sbt --client`, or Metals/BSP through `sbt-launch.jar`), and when any process has "runMain" or a standalone "sbt" word in its command line. That is the intended "one sbt process" rule (Phase 12 ruling 8), and the refusal lists the processes. It checks only at start, as the work order asks.

#### Check 4: the output path

Verified: `run-experiment.sh:99` sets the variable to `<stamp>/raw`, and harness `:163` appends `/raw`, giving `<stamp>/raw/raw/<mode>/` and `<stamp>/raw/raw/harness.json`. I recommend changing it to `<stamp>/raw/<mode>/` to match D, F and `rawResults: "raw/"`, with a human ruling (E-m2). Keeping it is functionally safe if documented, as the manifest already does.

#### Check 5: the manifest

- **run-experiment.sh validation (`:64-69`, `:73-75`):**
  - `schemaVersion` is correct.
  - `experimentId` matches `^[a-z0-9][a-z0-9-]{2,63}$`.
  - `command` is an array of strings.
  - maxMinutes 135 is within 1 to 240.
  - `metrics` is an array.
  - `command[0]` is under `experiments/`, contains no `..`, and is executable (a6 log).
  - After commit it also passes the tracked, unchanged-from-HEAD and clean-tree gates (`:84-87`). This needs every new file, including `experiments/derived/**`, committed.
- **Arithmetic as written (30 questions):** 1,200 + 6,300 = 7,500 s = 125 min (correct); 30 x 0.088 = 2.64 USD (correct); 240 Haiku requests (correct). Per question, 210 s is a defensible worst case, because the deadline is cooperative and one call can overrun it by `llm.timeout-ms` (RUNTIME.md lines 608-609).
- **Under E-M1 (32 questions):** 132 min, so the margin is too small.

#### Check 6: the summarizer and derive-tables

- **Recall at K:** |relevant ∩ top min(K, list)| / |relevant pooled| per question, averaged per mode, with the cap stated.
- **MRR:** the first relevant item over the full list, with 0 when none is found. An empty list gives recall 0 and MRR 0, as stated. Questions with no relevant item are excluded from every mode alike. The self-test values check out: for fused B01, R@5 = 0.5, so the mean with B02 is 0.75.
- **Abstention reasons:** from `abstentionReason`.
- **Latency:** nearest rank, the same as `Benchmark.latency` (`Benchmark.scala:722-727`).
- **Real formats:** both tools read the real formats (check 3).
- **derive-tables:** sha256 values cover every file read, and missing sources become gaps. The D-scale table is computed correctly but is under-caveated (E-m4). The question-set issues are in E-m3.

#### Check 7: Part B

- `course.tfvars:64` is `service_cpu_target_percent = 60`. That passes the validation (whole number, 10 to 90; `variables.tf:406-408`) and equals the default (`:404`). The comment cites rulings 6 and 2.
- `README.md:189-191` is one note saying window 3 ran with 15 as a declared experiment setting, and the default 60 is in use.
- No other occurrence of 15 remains outside records.
- fmt and validate were not run (deferred to the human's check.sh).

#### Check 8: scope and deviations

- Paths: every new file is under `experiments/**`, and the Part B edits are under `infra/**`. `agent-report.md` was appended by the orchestrator, which is permitted. Nothing was written under `labels/`, `scratch/stores-live/`, `scratch/er/adjudication-live/` or `experiments/benchmark/`. The tools only reference those paths, for the human's later run.
- The stray `git status`: see E-m6.
- The guard false positive: consistent with the documented `guard-dangerous.sh` behaviour, where a write verb (`chmod`) plus the text `labels/` is blocked (docs/agents.md, Hook limitations). The agent did not work around it in Bash. Using the Edit tool on its own `experiments/**` file is the normal path, not a bypass. Optional factory-engineer follow-up.

### OWNERSHIP CHECK

| Changed path | Owner | Author matches |
|---|---|---|
| `experiments/harness/exp-e-retrieval-modes.sh` | experiment-engineer | yes |
| `experiments/manifests/exp-e-retrieval-modes-v1.json` | experiment-engineer | yes |
| `experiments/analysis/{summarize-exp-e,e-label-sheets,derive-tables}.py` | experiment-engineer | yes |
| `experiments/derived/**` (30 files) | experiment-engineer | yes |
| `infra/terraform/envs/course/course.tfvars` | infra-engineer | yes |
| `infra/terraform/README.md` | infra-engineer | yes |
| `docs/agent-runs/phase-15/agent-report.md` | human (orchestrator assembles reports) | yes (orchestrator append) |

### FLOOR AND FENCE CHECK

- No change to `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `build.sbt`, `project/**`, `markTestRun` or the reviewer fence. Nothing is widened or weakened.
- `EXP_E_SBT` and `EXP_E_PS_FIXTURE` are self-test hooks that only the caller's own environment can set. They are not a fence change.

### EVIDENCE GAPS

- The summarizer self-test is reported as 14 passes; 13 are observed (E-m5).
- The harness has run only against the stub. The real parsing was checked statically here (check 3), but resume was never exercised (E-M1).
- TERM handling against the real sbt runner is unobserved (E-m1).
- Part B: terraform fmt and validate were not observed (stated as such by the agent).
- "Committed raw data" for A5 was not verified with git (stated by the agent). The sources are outside `.gitignore` patterns and do not appear as untracked.
- Possible evidence-retention issue (UNVERIFIED, human-owned): the `evidence/wo4/*.log` files match `.gitignore` `*.log` and do not appear as untracked at session start, so they will not be committed unless force-added.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)
TEST RESULTS (as supplied by the orchestrator, quoted):
- `wo4-a1-self-test.log`: "bash -n ok ... 21 PASS lines ... ==> OK self-test exit=0"
- `wo4-a3-self-test.log`: 13 PASS lines, "==> OK self-test exit=0"
- `wo4-a4-self-test.log`: 18 PASS lines, "==> OK self-test exit=0"
- `wo4-a5-self-test.log`: 5 PASS lines, "==> OK self-test exit=0"
- `wo4-a6-checks.log`: "harness --self-test exit=0; harness --dry-run exit=0; summarize-exp-e --self-test exit=0; e-label-sheets --self-test exit=0; derive-tables --self-test exit=0; __pycache__ count: 0"

VALIDATION RESULTS:
- `wo4-a2-run-experiment-dry-run.log`: "run experiments/harness/exp-e-retrieval-modes.sh --max 10 for at most 135 min ... exit=0"
- `wo4-a1-dry-run.log`: "verbatim match with the work order text" for all four commands, and "--max must be 2 to 10 (exit 2)" for `--max 11`.
- `wo4-a5-derive-run.log`: "3 gap(s) ... exit=0"

ASSUMPTIONS:
- The installed `sbt` is the standard bash runner. This matches the self-test fixture path and the Phase 10 "[info] welcome to sbt 1.13.0" output.
- Per ruling 11, I did not read `experiments/benchmark/`. The figure of 30 questions in the file comes from the Phase 10 output ("30 questions").

RISKS:
- If E-M1 is fixed by any route other than fused `--max N-2`, the manifest arithmetic must be recomputed.
- The answers sheet shows verification statuses and abstention reasons. The human should confirm this is acceptable under ruling 11.
- First live use: a benign "Exception" string on stderr would stop the check step. That is a safe failure costing 2 questions.

MANUAL ACTION REQUIRED:
- The human rules on the E-M1 fix (it departs from the literal command text) and on the E-m2 layout.
- After the repair, the human commits all new `experiments/**` files before running E, because run-experiment.sh refuses an uncommitted manifest, command or tree.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Route E-M1 (and, if the human chooses, E-m1 to E-m5) to experiment-engineer and get human rulings on E-M1 and E-m2. Then re-review the harness, the manifest and the self-test before the human runs E. Part B is ready for the human's check.sh and commit.

## Code review 4 (repair-02)

### Summary

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15+16, session 8, code review 4. This is the one-round re-review of repair-02 of work order 4, checked against Phase 15 ruling 12 (a) to (f) and ruling 11. Read-only.
VERDICT: APPROVE

There are no CRITICAL or MAJOR findings. Ruling 12 (a) to (f) are each implemented, and I checked each against the real code:
- `BenchmarkCli.scala`
- `run-experiment.sh`
- the derived sources

I found nothing that code review 3 accepted as correct and that has since regressed.

I have three MINOR findings. **None must be fixed before the E run.**
- **C4-m1** corrects the recovery step the agent proposed for a partial mode: a rerun through `run-experiment.sh` does not resume.
- **C4-m2** is the open blinding risk the agent reported. The procedure rule is enough only if its recovery branch is corrected. A small code guard is better, and it is needed only if a mode ends early.
- **C4-m3** is a missing caveat in the D-scale README.

SCOPE REVIEWED:
- The change set:
  - `evidence/code-review-4/{diff.txt, untracked-diff.txt, diffstat.txt, untracked.txt}`, compared with `evidence/code-review-3/` (same four files).
  - Every log under `evidence/repair-02/` (r02-a-* to r02-f-*).
  - `repair-02.md`.
- Files I read in full in the working tree:
  - `experiments/harness/exp-e-retrieval-modes.sh`
  - `experiments/manifests/exp-e-retrieval-modes-v1.json`
  - `experiments/analysis/summarize-exp-e.py`
  - `experiments/analysis/e-label-sheets.py`
  - `experiments/derived/exp-d-scale/{README.md, throughput-steady-state.csv, sources.csv}`
- Files I read in the relevant parts:
  - `experiments/analysis/derive-tables.py` (lines 1-110 and 205-365)
  - every `experiments/derived/*/sources.csv`
- Real code:
  - `modules/rag/src/main/scala/scholarchain/rag/cli/BenchmarkCli.scala` (all)
  - `scripts/run-experiment.sh` (all)
  - `scripts/lib/common.sh` (SC_ROOT, DRY_RUN, `dry_run_exit`, the git helpers)

### Findings (ranked)

There are no CRITICAL or MAJOR findings.

**C4-m1 (MINOR). The recovery path "rerun the same command, which resumes" is wrong for a run made through `run-experiment.sh`.**

- Location:
  - `experiments/manifests/exp-e-retrieval-modes-v1.json:19` (`failingModeRule`: "so a rerun of the same command resumes"). This text was already there at code review 3 (`code-review-3/untracked-diff.txt:2118`), and code review 3 missed it.
  - `docs/agent-runs/phase-15/repair-02.md:169` (RISKS: "The mitigation is to rerun the same command, which resumes, before making the sheets").
- Why it is wrong:
  - BenchmarkCli resumes only within the same `--out` (`BenchmarkCli.scala:73-78`).
  - `run-experiment.sh` creates a new `<stamp>` on every run (`:92`) and makes the previous stamp read-only (`:128`).
- Failure input: one mode stops at a failed question (`BenchmarkCli.scala:102-115`, exit 1), and the human follows the documented mitigation. Two things can happen:
  - A rerun through `/experiment` starts a fresh E under a new stamp: the check plus 30 questions again, up to another 2.64 USD and 135 minutes. The first stamp stays as an immutable partial record. Nothing resumes.
  - Running BenchmarkCli by hand against the old stamp would bypass the gated command, and it would fail anyway on the read-only directory.
- Impact: there is no safety or data impact. It is a wrong operating instruction in a manifest whose sha256 is frozen into the run record, and it could lead to an unplanned second Bedrock spend.
- Fix: replace the sentence in `failingModeRule` with: "BenchmarkCli resumes only within one `--out`; a rerun through run-experiment.sh writes a new `<stamp>` and repeats every mode (the earlier stamp is read-only)". Make the same correction to the RISKS text in the record.
- Owner: experiment-engineer.
- Must fix before E: no. It is preferred before the commit, because the manifest is immutable afterwards. Ruling 12(j) makes this the last round, so if the human does not grant a text-only exception, the correction goes into a ruling or the Phase 16 write-up instead.

**C4-m2 (MINOR). `e-label-sheets.py` still builds blind sheets from a partial run. Its only signal is GAP lines, and it exits 0.**

- Location:
  - `experiments/analysis/e-label-sheets.py:170` (`qids` is the union of the question IDs of all modes)
  - `:185-189` (a missing mode becomes a gap only)
  - `:212-219` (only the modes present get answer keys)
  - `:230-233` (it prints GAP lines and exits 0)
- Failure input: graph-only stops at B07 with exit 1, and the human runs the sheets tool.
  - For B07 to B10, the answers sheet shows two answers (A and B) instead of three.
  - The pooled items come from two modes only.
  - The labeller can see that a mode is missing. Together with the recognisable vector-only abstentions (ruling 12(h)), that narrows which answer belongs to which mode. Ruling 11 and A4 forbid this.
- Ruling 12(d) changed only the summarizer, so this is unchanged since code review 3 (check 2: "any failed mode reveals the mode through the answer count per question").
- Fix (small, self-tested):
  - Make `build()` refuse with exit 1 when the three modes' question-ID sets differ, naming the excluded IDs.
  - Optionally add `--common-only`, which pools only the intersection, the same set the summarizer scores. Add a self-test for the refusal and for the intersection.
  - The alternative is the procedure rule in check 4, if its recovery branch is corrected as in C4-m1.
- Owner: experiment-engineer.
- Must fix before E: no. The tool reads the raw results only after the run, and the raw layout does not depend on it. It must be in place, or the procedure rule must be ruled, before sheets are built from a run in which any mode did not exit 0.

**C4-m3 (MINOR). The D-scale README sends readers to the steady-state rows, but those rows are still affected by the same rising request rate.**

- Location:
  - `experiments/analysis/derive-tables.py:339-342`, which generates `experiments/derived/exp-d-scale/README.md:9`: "compare the steady-state rows instead".
  - Line 5 also says "Compare the steady-state rows".
- Evidence (from the README itself):
  - ALB requests per minute keep rising after registration: 2241 at 00:23, then 2324, 2364, 2398, 2422 and 2472 at 00:28.
  - Neptune CPU stays between 96.97 and 97.65 percent throughout.
  - So the steady-state difference, 37.32 against 40.23 requests per second (`throughput-steady-state.csv:2-3`), is confounded by the same trend.
- The agent says this in `repair-02.md:170` ("the difference cannot be attributed to the second task"), but the durable README does not. That repeats the pattern of E-m4.
- The four caveats that ruling 12(e) requires are all present and correct.
- Fix: add one generated caveat saying the ALB rate kept rising through the two-task minutes while Neptune stayed at about 97 percent, so the steady-state difference is not attributable to the second task either. Then rerun `derive-tables.py`. Alternatively, carry this in the Phase 16 write-up.
- Owner: experiment-engineer (generator), or documentation-engineer (write-up only).
- Must fix before E: no.

### Must fix before the E run

None. Before the commit, the human decides whether to allow a text-only fix for C4-m1. Before any sheets are built, the human decides between the C4-m2 code guard and the corrected procedure rule.

### Per-check answers

#### Check 1: ruling 12 (a) to (f)

**(a) E-M1 is fixed.**
- `:59` accepts only digits and N from 3 to 10.
- `:60` `mode_max` gives fused N-2 and the other modes N.
- `:286` runs the check with fused `--max 2`.
- `:305-306` run fused `--max N-2`, graph-only `--max N` and vector-only `--max N`.
- Against the real code:
  - `BenchmarkCli.scala:78` (`pending = qs.filter(not stored)`) and `:104-105` (`pending.take(max)`): `--max` limits the new pending questions. `:54` refuses `--max 0`, so N >= 3 is the right floor.
  - The fused check and the fused mode share one benchmark run: `--max` is not an input to the run ID (`:62-72`), as code review 3 verified.
  - The check passes only on exit 0, which means it answered 2. A fused mode that exits 0 then answers exactly N-2 more. **Harness exit 0 therefore guarantees exactly the first N question IDs in every mode.** Results are written in file order, and a run stops at its first failure (`:102-114`), so the stored set is always a prefix of the file.
- The stub's resume model matches BenchmarkCli:
  - Stub `:84-86` counts `questions/*.json` under `--out` and answers min(max, 30 - done).
  - Its line format is identical to `BenchmarkCli.scala:80-81` ("N questions, D already done, answering M now").
- Self-test evidence (`r02-a-self-test.log`, 42 PASS, exit 0):
  - Invocations `fused|2 fused|2 graph-only|4 vector-only|4`, and the "2 already done, answering 2" line.
  - B01 to B04 in every mode, and maxima `[2,2,4,4]`.
  - The recorded commands equal `command_text`.
  - n3 and n10 with the same IDs.
  - 2, 11, 0 and x are refused before any sbt call.
- Dry-run (`r02-a-dry-run.log:4-8`) shows `--max 2`, `--max 8`, `--max 10` and `--max 10`. `--max 2` gives "must be 3 to 10" with exit 2.

**(b) E-m2 is fixed.**
- `:232` `RAW="$SCHOLARCHAIN_EXPERIMENT_DIR"`, and the dry-run placeholder `<stamp>/raw` (`:207`).
- Manifest `outputLayout`, `rawOutputs` and `analysis` (`:49-51`), and the docstrings at `summarize-exp-e.py:24` and `e-label-sheets.py:22`.
- Grep: no `raw/raw` remains, except the negative assertion at harness `:138`.

**(c) E-m1 is fixed, and the stub test tells the old and new behaviour apart.**
- `set -m` at `:269-273` puts the sbt job in its own process group.
- `on_term` (`:248-261`):
  1. ignores further TERM and INT;
  2. sends TERM to `-$CHILD`;
  3. polls for up to 20 s;
  4. sends KILL to the group, then waits;
  5. writes harness.json with 143.
- The self-test stub spawns `sleep 300` (`:88-91`), and the term case (`:171-192`) asserts that both processes are alive before TERM and gone after it.
- `r02-a-self-test.log:38-43` passes, "stopped in 0 s". That shows `kill -0` on a group whose only member is a zombie fails on this macOS, so the loop does not burn the 20 s.
- `r02-a-term-mutation.log`: with the work order 4 behaviour restored, only "its child ... is gone" fails, with exit 1.
- The whole path stays inside `run-experiment.sh`'s 30 s grace (`:108-110`).
- Remaining limit: this was proven with a stub, not with the installed sbt runner (see RISKS).

**(d) E-m3 is fixed.**
- `question_sets` (`:109-113`).
- Relevance restricted to the intersection (`:138`).
- `questions_without_items` (`:140-142`, `:156`).
- Ratings restricted to the intersection (`:172`).
- `questions_excluded` in the quality rows (`:219`).
- The "Question sets differ ... excluded: ..." line or the "same N" line (`:211-217`).
- The new column (`:225`).
- The self-test expectations check out by hand. With graph-only missing B02:
  - fused B01: R@5 0.5, R@10 1, RR 1;
  - vector-only RR 0.5;
  - excluded `["B02","","B02"]`.
- Per-mode operational rows still cover each mode's own questions. Ruling 12(d) asks this only of the quality metrics, and the "differ" line flags the difference.

**(e) E-m4 is fixed, with C4-m3 above.**
- All four required caveats are generated from data (`derive-tables.py:331-351`).
- The steady-state table is labelled as such (`:305-312`, `:328-330`).
- Spot checks:
  - one-task minute: 2239/60 = 37.32; ALB 2241;
  - two-task minutes: 9655/240 = 40.23; ALB sum 9656;
  - CPU 97.0 to 98.7 over 00:20 to 00:28;
  - reference row 35.72 from `20261118T063904Z/raw/summary.json`.
- `r02-c-derive-match.log`: a fresh build compared with `diff -r` against `experiments/derived` exits 0. The self-test passes 5 of 5, including the byte-identical rebuild.

**(f) E-m5 is fixed.**
- `repair-02.md:122-123` states 13 for work order 4 and 19 now.
- I counted 19 `check(...)` calls in `summarize-exp-e.py:266-358` and 19 PASS lines in `r02-b-summarize-self-test.log`.
- `agent-report.md` is unchanged: the diffstat shows +199 at both code review 3 and code review 4.

#### Check 2: manifest arithmetic and the `run-experiment.sh` gates

**Arithmetic at 30 questions:**

| Quantity | Calculation | Result |
|---|---|---|
| Worst case | 4 × 300 s + (2 + 8 + 10 + 10) × 210 s = 7,500 s | 125 min |
| Margin under maxMinutes 135 | 135 − 125 | 10 min (meets A2's "at least 10" exactly) |
| Haiku requests | 30 × 2 × 2 × 2 | 240 |
| Embedding calls | fused 10 + vector-only 10 | 20 |
| Cost | 30 × (0.040 + 0.048) | 2.64 USD, under the 3 USD budget |

All agree with the manifest (`:11`, `:16`, `:29`, `:30`, `:36`) and with `r02-e-manifest-arithmetic.log`, which takes the maxima from the dry-run.

**`run-experiment.sh` acceptance:**
- Path `experiments/*.json`, not under results (`:62`).
- `schemaVersion`, `experimentId` regex (24 characters), command array of strings, maxMinutes 135 within 1 to 240, and `metrics` an array (`:64-68`).
- `command[0]` under `experiments/`, with no `..` (`:73-74`), and executable (`:75`, passed before `dry_run_exit` at `:77`; `r02-d-run-experiment-dry-run.log` exit 0).
- After the commit it must also pass:
  - the manifest tracked and unchanged from HEAD, and `CMD0` tracked (`:84-86`);
  - `require_clean_tree` (`common.sh:74`, which excludes only `operations/` and `experiments/results/`). So every untracked file under `docs/agent-runs/phase-15/` and `experiments/**` must be committed first, including this review's append.

#### Check 3: no regression since code review 3

- **Labelling safety:**
  - `e-label-sheets.py` has a docstring-only change. The diff length is identical (416 lines in both untracked diffs), and only `:22` changed.
  - The templates are still written empty, and the self-test passes 18 of 18 (`r02-b-label-sheets-self-test.log`).
  - The summarizer still reads only the human's files plus the keys, still refuses label values other than 1, 0 or empty, and keeps its fixture labels in a temporary directory.
- **Step 1 gate:** unchanged (`:286-301`). It requires exit 0, a "plan sources:" line with MODEL as a whole word, and a scan of the check's stdout and stderr for FALLBACK_RULES, BUDGET_EXHAUSTED and Exception.
- **Output-format parsing:** unchanged.
  - The run-ID regex (`:276`) matches `BenchmarkCli.scala:80`.
  - The "plan sources" line matches `:188`.
  - The summarizer and sheets paths are as code review 3 verified.
- **Derived sources and sha256:** every row in `exp-d/`, `exp-f/`, `size-scaling/` and `exp-d-scale/sources.csv` matches code review 3's `untracked-diff.txt` (lines 1473-1482, 1600-1603, 1753-1755 and 1845-1852). The only additions are the two window 2 `summary.json` rows in `exp-d-scale/sources.csv`, read for the reference row, with the same hashes as in `exp-d/`. The three gaps are unchanged (`r02-c-derive-run.log`).

#### Check 4: blinding when a mode stops early

The risk is real; see C4-m2.

The proposed procedure rule has two parts:
- **"Build the sheets only after the summarizer reports the same N questions in every mode."** This is sufficient to prevent a leak if it is followed, because sheets are then never built from a partial run.
- **"Otherwise rerun the same command, which resumes."** This is wrong (C4-m1). Through `run-experiment.sh` a rerun is a whole new E under a new stamp, not a resume.

Because harness exit 0 guarantees identical question sets (check 1(a)), the rule can be stated precisely: "build sheets only when run-record `exitCode` is 0 and the summarizer prints 'Question sets: the same 10 question(s) in every mode.'" If a mode ended early, the human has two choices:
1. Approve a fresh full E run, up to another 2.64 USD.
2. Have the C4-m2 guard (refuse, or `--common-only`) added, then build sheets over the intersection.

A code change is therefore not needed before the E run. It is needed only if the run is partial and the human wants to label it. I recommend the guard anyway, so that the blinding rule is enforced by the tool rather than by memory.

#### Check 5: scope, commands, and the guard false positive

- **Paths:**
  - The only new path since code review 3 is `experiments/derived/exp-d-scale/throughput-steady-state.csv`. Everything else that changed is a work order 4 file under `experiments/**` (compare the two `untracked.txt` files).
  - Tracked diffs outside `experiments/**` are the orchestrator's code review 3 append to `review-report.md` and the unchanged Part B (`infra/` diffstat 3 and 6, the same as at code review 3).
  - `repair-02.md` is the orchestrator-assembled report.
  - No `__pycache__` exists under `experiments/` or `load-tests/` (Glob).
- **Commands:**
  - COMMANDS RUN (`repair-02.md:129-143`) lists no git, aws, terraform or sbt.
  - The only script that could call git or aws is `run-experiment.sh --dry-run`. It exits in `dry_run_exit` (`:77`) before `need git aws` (`:83`), and `common.sh` runs no git when it is sourced (SC_ROOT comes from `cd` and `pwd`, `:16`). The log confirms "no AWS, Terraform, or git call was made".
  - I cannot see the session transcript, so "no git at all" rests on the report and the logs.
- **Guard false positive:**
  - The account is accurate. The self-test text contains both `jq` (the `hj` helper) and `env` (`env SCHOLARCHAIN_EMBEDDING_MODEL_ID`). docs/agents.md (Hook limitations) documents that `guard-dangerous.sh` blocks any command that runs jq and contains the word `env`, and says to write such content with the file tools.
  - The workaround is safe in effect:
    - the Write tool targeted the session scratchpad, outside the repository;
    - the splice target was the agent's own `experiments/**` file;
    - no protected path was involved;
    - the Bash `python3 -I` step reached the permission prompt, because no allow rule covers it.
  - It does not follow the documented route literally: Edit on the owned file directly would have kept the write visible to `guard-paths.sh`. This is not a finding. Note it for ruling 12(i)'s Phase 17 item.

### OWNERSHIP CHECK

| Changed path | Owner | Author matches |
|---|---|---|
| `experiments/harness/exp-e-retrieval-modes.sh` | experiment-engineer | yes |
| `experiments/manifests/exp-e-retrieval-modes-v1.json` | experiment-engineer | yes |
| `experiments/analysis/{summarize-exp-e,e-label-sheets,derive-tables}.py` | experiment-engineer | yes |
| `experiments/derived/**` (including the new `throughput-steady-state.csv`) | experiment-engineer | yes (generated by `derive-tables.py`) |
| `docs/agent-runs/phase-15/repair-02.md`, `review-report.md` append | human (orchestrator assembles reports) | yes |
| `infra/terraform/**` (Part B, unchanged since code review 3) | infra-engineer | yes |

### FLOOR AND FENCE CHECK

- There is no change to `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `build.sbt`, `project/**`, `markTestRun`, the hooks or the reviewer fence.
- `EXP_E_SBT`, `EXP_E_PS_FIXTURE` and `EXP_E_STUB_*` are self-test seams that only the caller's own environment can set.
- `set -m` is scoped to one fork.
- Nothing is widened or weakened.

### EVIDENCE GAPS

- TERM handling has been observed only against a stub. Its effect on the installed sbt runner and its JVM is unobserved.
- The harness has not yet run against the real BenchmarkCli. I verified the resume semantics statically (check 1(a)).
- `derive-tables.py`'s self-test has no assertion for the new steady-state or caveat code. Its correctness rests on my spot checks and on the `diff -r` match of a fresh build.
- "No git" rests on the report and the logs, not on a transcript.
- The `evidence/repair-02/*.log` files match `.gitignore` `*.log`. They are kept only if the human force-adds them (ruling 12(j)).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, quoted from the logs):
- `r02-a-self-test.log`: 42 PASS lines, "==> OK self-test", "self-test exit 0"
- `r02-a-term-mutation.log`: "FAIL term: its child (the JVM's stand-in) is gone after TERM", "mutated self-test exit 1"
- `r02-b-summarize-self-test.log`: 19 PASS, "summarize self-test exit 0"
- `r02-b-label-sheets-self-test.log`: 18 PASS, "label-sheets self-test exit 0"
- `r02-c-derive-self-test.log`: 5 PASS, "derive self-test exit 0"

VALIDATION RESULTS:
- `r02-a-bash-n.log`: "bash -n exit 0"
- `r02-a-dry-run.log`: "dry-run exit 0"; "--max must be 3 to 10 (exit 2)"
- `r02-d-run-experiment-dry-run.log`: "run experiments/harness/exp-e-retrieval-modes.sh --max 10 for at most 135 min", "run-experiment dry-run exit 0"
- `r02-e-manifest-arithmetic.log`: "ARITHMETIC OK", exit 0
- `r02-c-derive-run.log`: "3 gap(s)", exit 0
- `r02-c-derive-match.log`: "diff -r fresh vs experiments/derived exit 0"
- `r02-f-pycache.log`: "(no paths above = none)"

ASSUMPTIONS:
- The installed `sbt` is the standard bash runner, which does not `setsid` its JVM. That is the same assumption code review 3 made.
- Per ruling 11, I did not read `experiments/benchmark/`. "30 questions" comes from the Phase 10 output.

RISKS:
- **Background process group under a terminal (unverified).** With `set -m`, sbt now runs in a background process group of the human's terminal. stdin is `/dev/null` and stdout and stderr go to files. If the runner or JVM nevertheless touches `/dev/tty`, it would stop on SIGTTIN or SIGTTOU and hang until maxMinutes, without Bedrock spend; TERM and then KILL to the group would still end it. The check step would show this within minutes. The self-test ran without a terminal, so it cannot show it.
- **Ctrl-C does not stop E.** This is not a regression. `run-experiment.sh` starts the harness with `&`, so the harness inherits SIGINT as ignored, and the script has no INT trap. Ctrl-C ends `run-experiment.sh` but leaves the harness and sbt running without the maxMinutes watchdog, bounded by `--max`. To abort, send TERM to the harness PID. An INT trap in `run-experiment.sh` would be an infra-engineer change for Phase 17.
- The time margin is exactly 10 minutes, the A2 floor.

MANUAL ACTION REQUIRED:
- Decide whether C4-m1 gets a text-only fix before the commit, or is recorded in a ruling.
- Decide between the C4-m2 guard and the corrected procedure rule before any sheets are built from a run that did not exit 0.
- Commit everything under `experiments/**` and `docs/agent-runs/phase-15/` before E. `require_clean_tree` refuses otherwise.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human accepts code review 4 and rules on C4-m1 to C4-m3, then commits work order 4 and repair-02 (with `git add -f` for the r02 logs). After a passing check.sh, the human runs E through `scripts/run-experiment.sh --manifest experiments/manifests/exp-e-retrieval-modes-v1.json`. Sheets are built only after the condition in check 4 holds.
