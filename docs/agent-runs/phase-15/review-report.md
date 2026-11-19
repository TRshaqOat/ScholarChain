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
