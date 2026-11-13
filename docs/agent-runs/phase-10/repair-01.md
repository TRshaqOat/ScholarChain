# Phase 10 repair-01

## Part A: service-engineer

````text
Part A is COMPLETE. F1, m1, m2, m3 and m5 are fixed. Every new or changed test fails with its fix patched out (8 disable checks, every restore verified by sha256). The smoke passed, and `sbt check` passed with 665 tests and 0 failures on the final tree, in both the console and the JUnit XML.

Four check runs, not two: runs 1 and 2 passed, then I fixed a wrong section number in a code comment (Services.scala:55 said "SERVICES.md section 3"; the S3 permissions are in section 4). Runs 3 and 4 then passed on the final tree; cite those.

No BLOCKED-ON. I did not write docs/agent-runs/phase-10/repair-01.md; appending this report is yours. Paths below are relative to /Users/achyutpatel/Developer/Cloud/ScholarChain. Logs are under scratch/phase-10-logs/repair-01/, written by shell redirection.

**One guard event:** guard-dangerous.sh [secret-keys] blocked a Bash python heredoc that edited Composition.scala. The script text contained `w.key)` from the existing source, which matches its `.key` file-name rule. No secret was involved. As directed, I made those edits with the Edit tool and did not work around the guard.

## F1: pool design

Every service now has two downstream pools in `Downstreams` (Resilience.scala). Workflow calls are selected by the new field `CallPolicy.workflow`. No configuration key was added.

| Service | HTTP threads | Store pool (store calls and readiness probes) | Workflow pool (no queue) |
|---|---|---|---|
| query-api | services.query-api.threads = T (8) | T threads, queue services.max-queued-requests (64) | max(1, T/2) = 4 slots: the sync RuntimeApi.answer only |
| graph-evidence-api | services.graph-evidence-api.threads = G (16) | G threads, queue 64 | 1 slot, never used; threads start only on first use, so none starts |
| worker | services.worker.threads = W (2) | services.worker.concurrency + W = C + W (3), queue 64 | C = 1 slot: the job workflow only |

Which calls run where:
- **query-api store pool:** the idempotency claim; job create, get and transition; the SQS send; the S3 reads of explain, result and replay; the s3, dynamodb and neptune readiness probes.
- **graph-evidence-api store pool:** evidence lookup, evidence payload read, node and neighbor reads, and the neptune and dynamodb probes.
- **worker store pool:** receive, delete, job get and transition, and the dynamodb, s3 and neptune probes.

Why these sizes: each HTTP thread (or poll loop) holds at most one store call at a time. A sync request holds its HTTP thread while its workflow runs, so at most T/2 HTTP threads are ever tied to workflows.

Two rules, in `Downstreams.call` and `await`:
1. A call's timeout counts from its start, not from submission.
2. A call that cannot start answers 503 OVERLOADED and touches no breaker. That is a full workflow pool (refused at once) or a store call still waiting when its timeout passes (given up and never run).

**Worker:**
- `Worker.loop` does not receive while a breaker is open or no workflow slot is free.
- `Worker.begin` does not claim a job when no slot is free. The job stays QUEUED, the message is kept, and the log event is `no-workflow-slot`.

**Corrected behaviour under pool saturation (ruling 22; replaces the WO5 "503 OVERLOADED" risk text):**
- **All query-api workflow slots held** (running sync workflows, or ones past their 504 still running to their cooperative deadline):
  - a new sync POST answers 503 OVERLOADED at once and opens no breaker;
  - async POSTs, the jobs, result and explain routes, replays and /readyz keep their threads and answer within their store timeouts;
  - /readyz stays ready.
- **All worker slots held** (a job past its backstop): no receive and no claim. Readiness and job-state writes are unaffected, and no breaker opens.
- **Store pool full** (store calls overrunning and ignoring the interrupt): a waiting call answers 503 OVERLOADED with no breaker. /readyz reports NOT_READY for that downstream while the pool is full, and recovers as soon as threads free.
- **HTTP pool full:** 503 OVERLOADED, unchanged.

## Minor findings

- **m1:**
  - Change: the S3 readiness probe key moved from the bucket root to `Probes.objectKey(setup)` = `logs/rag-runtime/snapshot=<d>/run=<runtime run ID>/readiness-probe`, a prefix query-api and the worker already read. Both services use it.
  - SERVICES.md section 4 states the S3 permissions per task, including `s3:ListBucket` (it may be limited with an `s3:prefix` condition to logs/rag-runtime/ and reports/rag-runtime/). The same section explains the readiness and unknown-query-ID effects without it.
  - Test: QueryApiSpec "readiness probes S3 under the audit prefix query-api and the worker read (repair-01 m1)".
- **m2:** documentation only, SERVICES.md section 5.
  - It lists every runtime run ID input, says to deploy query-api and the worker together from one image and one argument and environment set, and warns against rolling deploys.
  - The existing startup line is kept unchanged: `fake mode: runtime run <id>, ...` / `live mode: runtime run <id>, ...`. It goes to stdout via `out`, not the JSON log.
  - No code change and no new test, because no behaviour changed.
- **m3:**
  - Change: a new `answers` flag on `Composition.fake`, `live` and `windowSummaries`, and on `Launcher.backend`, `prepare`, `start` and `main`. The default is true; `main/GraphEvidenceApi.scala` passes `answers = false`.
  - graph-evidence-api never opens the window-summary object. It still uses the key as a run ID input (new `Composition.windowSummaryKey`), so its run ID equals query-api's. It prints `window summaries: <key> not opened (this service answers no questions)`.
  - Test: CorpusQuerySpec "graph-evidence-api never opens the window-summary object; same runtime run (repair-01 m3)". With the object deleted, query-api's backend gets OBJECT_MISSING while graph-evidence-api's starts with the same run ID and serves evidence query-api wrote. `Launcher.start` with `answers = false` also starts.
- **m5:**
  - Change: an IllegalArgumentException from inside a downstream call now answers 500 INTERNAL_ERROR, "unexpected internal failure", with a traceId and no detail. It is not counted as a downstream failure.
  - New test: GraphEvidenceApiSpec "an IllegalArgumentException inside a downstream call is 500 with no detail (repair-01 m5)". It checks 500 twice with no breaker opening, /readyz 200, no detail in the body or the logs, and that route validation is still 400.
  - Changed test: BuildingBlocksSpec "downstream calls ..." now expects INTERNAL_ERROR instead of INVALID_FIELD, with no detail and no breaker.

## Tests and disable checks

New tests: 6 (37 to 43 services tests, 7 to 8 suites).
- BuildingBlocksSpec "waiting to start is no downstream failure: 503 OVERLOADED, no breaker (repair-01 F1)"
- SaturationSpec (new suite) "query-api: with every workflow slot held, jobs, explain, and readiness still answer"
- SaturationSpec "worker: an overrun job holds only its workflow slot; no job is claimed until it frees"
- plus the m1, m3 and m5 tests above.

Changed test: BuildingBlocksSpec "downstream calls ..." (m5).

Method: `scratchpad/disable.py` patches one rule, runs `services/testOnly` on the named suites, then restores the file and checks its sha256. Every log ends with "restored sha256: ... OK".

| Disabled | Result | Failing tests and evidence | Log |
|---|---|---|---|
| D0: both F1 rules off (the pre-repair behaviour) | 6/3 | unit: "DOWNSTREAM_TIMEOUT did not equal OVERLOADED"; query-api: job read answered 504 DOWNSTREAM_TIMEOUT "dynamodb did not answer within 1000 ms"; worker: "dynamodb probe: Failure(... dynamodb is unavailable)" (breaker open) | disable-D0-original-behaviour.log |
| D1: queue wait counts toward the timeout | 8/1 | the unit test | disable-D1-queue-wait-counts.log |
| D2: one shared pool | 6/3 | all 3 F1 tests; query-api job read gives 503 instead of 200 | disable-D2-shared-pool.log |
| D3: worker claims without a free slot | 1/1 | worker test, "FAILED did not equal QUEUED" | disable-D3-worker-claims-without-slot.log |
| D4: poll loop receives without a free slot | 1/1 | worker test, "5.0 did not equal 3.0" (receives) | disable-D4-worker-loop-receives-without-slot.log |
| D5: probe key at the bucket root | 12/1 | m1 test | disable-D5-probe-key-at-bucket-root.log |
| D6: graph-evidence-api opens the summaries | 3/1 | m3 test | disable-D6-graph-evidence-opens-summaries.log |
| D7: downstream IllegalArgumentException is 400 | 12/2 | m5 test and "downstream calls ..." | disable-D7-iae-is-400.log |

- D0, D2, D3 and D4 were rerun after I reordered the worker test so it checks readiness and breakers before the slot assertion. The first-run logs are kept as `*-testv1.log`; they also failed.
- `shasum -c` reported OK for all main sources after both disable batches (main-sha256-after-disable.txt, main-sha256-after-disable-2.txt). Those manifests predate the later comment edit to Services.scala.

## SERVICES.md sections

1. Deployables
2. Thread pools (F1)
3. Behaviour under saturation
4. S3 access per task (m1, m3)
5. The runtime run ID: deploy query-api and the worker together (m2)

## Smoke (fake mode, 127.0.0.1, same inputs as WO11, run once)

- Server: smoke-server.log. It printed `window summaries: curated/window-summary/snapshot=2026-10-03/run=run-6fbad498beb0d8f570df67665fb97544/part-00000.jsonl` and runtime run `run-d21e6d611181c34f6794f91d28416e1b`, the same as WO11.
- Client: the WO11 client, unchanged. smoke-1.log has 21 OK lines and ends with `SMOKE PASSED`:
  - sync: 4 of 4 VERIFIED;
  - B13 claims 1 and 2 VERIFIED (7709 components; 46062 of 66350; 0.6942; 3119 singletons);
  - async result 4 of 4 VERIFIED.
- Stop: I killed the forked JVM (pid 1637); the runner exit 143 and sbt exit 1 are expected.
- After: lsof shows nothing on 8080 to 8082, and no sbt or java process is left.
- The stat of scratch/runtime/wo8/s3 and the wo6 fixture stores is identical before and after.
- The smoke key appears in 0 logs. Outputs: scratch/services/smoke-repair01-20261112/ (96K).
- The smoke ran before the comment-only Services.scala edit.

## sbt check (SPARK_LOCAL_IP=127.0.0.1, -J-Xmx6g)

Runs 3 and 4 are on the final tree:

| Project | Run 3 console | Run 3 JUnit (suites) | Run 4 console | Run 4 JUnit (suites) |
|---|---|---|---|---|
| core | 185 | 185 (4) | 185 | 185 (4) |
| entityResolution | 111 | 111 (10) | 111 | 111 (10) |
| hadoop | 70 | 70 (6) | 70 | 70 (6) |
| rag | 102 | 102 (14) | 102 | 102 (14) |
| services | 43 | 43 (8) | 43 | 43 (8) |
| sparkAnalytics | 46 | 46 (8) | 46 | 46 (8) |
| storage | 108 | 108 (11) | 108 | 108 (11) |
| Total | 665 / 0 failed | 665, 0 failures, 0 errors (61) | 665 / 0 failed | 665, 0 failures, 0 errors (61) |

- Run 3: exit 0, completed 5:38:31 PM. Run 4: exit 0, completed 5:42:56 PM. markTestRun wrote the marker each time.
- 0 `*** FAILED` in either log, and no file under modules/services (outside target/) is newer than .claude/.last-test-run.
- Runs 1 and 2 (5:27:20 and 5:31:55 PM) gave the same counts.
- Logs: sbt-check-1..4.log and junit-counts-1..4.txt.

Free disk at the end: 15Gi (df-end.txt).

```text
STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: Phase 10 repair-01 Part A (F1, m1, m2, m3, m5; smoke rerun; sbt check)
FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/SERVICES.md
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/SaturationSpec.scala
FILES MODIFIED:
- modules/services/src/main/scala/scholarchain/services/Resilience.scala (two pools, CallPolicy.workflow, timeout from start, cannot-start = OVERLOADED with no breaker, workflowSlotFree, IllegalArgumentException = INTERNAL_ERROR)
- .../Services.scala (runtime policy on the workflow pool; Probes.objectKey)
- .../Worker.scala (workflow pool of concurrency slots; loop and claim slot checks; probe key)
- .../Composition.scala (pool sizes; answers flag; windowSummaryKey)
- .../main/GraphEvidenceApi.scala (answers = false)
- modules/services/openapi.yaml (503 OVERLOADED description only)
- tests: BuildingBlocksSpec.scala, QueryApiSpec.scala, GraphEvidenceApiSpec.scala, CorpusQuerySpec.scala
FILES DELETED: none
ROUTE TABLE: unchanged (query-api: POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result, GET /v1/explain/{queryId}; graph-evidence-api: GET /v1/evidence/{evidenceId}, GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors; all three: /healthz, /readyz, /metrics)
STATUS-CODE MAPPING: unchanged except:
- 503 OVERLOADED now also means a workflow slot was unavailable or a call could not start within its timeout (never a breaker failure);
- an IllegalArgumentException inside a downstream call is 500 INTERNAL_ERROR (was 400 INVALID_FIELD); route validation is still 400.
TIMEOUT, RETRY, AND CIRCUIT-BREAKER POLICY: timeouts, retries and breakers as in WO5 and WO6, except:
- a timeout counts from the call's start;
- time waiting for a thread never counts against a breaker;
- workflows run on their own no-queue pool (query-api max(1, T/2) slots, worker C slots).
AUTHENTICATION METHOD: unchanged (one X-Api-Key, constant-time SHA-256 comparison, key from the variable named by services.api-key-env)
SERVICE DEPENDENCY GRAPH: unchanged module edges (services -> core, rag, storage). Rag Audit.base is now used for the probe key. graph-evidence-api no longer opens the window-summary object.
COMMANDS RUN (all sbt runs with SPARK_LOCAL_IP=127.0.0.1 and -J-Xmx6g; free disk 15Gi before each):
- df > df-start.txt
- copy of modules/services/src to the scratchpad (backup)
- sbt "services/scalafmtAll; services/Test/scalafmtAll; services/test", 3 runs:
  - services-test-1.log: compile error (ambiguous submit);
  - services-test-2.log: 41/2 (a config bound: neptune-ms must be at least neptune.query-timeout-ms, so I dropped that override);
  - services-test-3.log: 43/0.
- python3 -I scratchpad/disable.py: 8 runs, then 4 reruns (disable-*.log; shasum -c manifests)
- services/testOnly SaturationSpec: 2/0 (saturation-test-v2.log)
- smoke: background LocalRun (smoke-server.log), python3 -I wo11 smoke-client.py (smoke-1.log), kill 1637, lsof, pgrep, and a stat diff of the inputs
- sbt check 4 times (sbt-check-1..4.log) and python3 -I junit_counts.py (junit-counts-1..4.txt)
- df > df-end.txt
- Read-only commands: cat, sed, grep, find, ls, stat, ps, pgrep, lsof
- No git, no aws, no network, no --live or --profile. Nothing under labels/, scratch/stores-live/ or scratch/er/adjudication-live/ was read or listed. Nothing under scratch/stores, scratch/spark, scratch/hadoop or scratch/runtime was modified (find -newer is empty).
TEST RESULTS: sbt check runs 3 and 4: console 665 passed / 0 failed; JUnit XML 665 tests / 0 failures / 0 errors in 61 suites. Per project: core 185, entityResolution 111, hadoop 70, rag 102, services 43 (was 37), sparkAnalytics 46, storage 108. Runs 1 and 2 were identical.
VALIDATION RESULTS:
- Format checks passed inside every check run.
- The contract tests pass.
- 8 of 8 disable checks fail their named tests, and every restore shows sha256 OK.
- Smoke passed.
- Ports 8080 to 8082 are free, and no sbt or java process is left.
- Free disk at the end: 15Gi.
ASSUMPTIONS:
1. query-api workflow slots = max(1, threads/2), with no queue; the store pool = threads. graph-evidence-api keeps a 1-slot workflow pool that is never used. Worker: store pool = concurrency + threads, workflow pool = concurrency.
2. A worker with no free workflow slot neither receives nor claims. The job stays QUEUED and its message is kept.
3. A downstream IllegalArgumentException is a server defect: 500 INTERNAL_ERROR, not counted against the breaker.
4. m2 needed no code change: the existing stdout startup line already names the runtime run ID, and it is kept.
5. graph-evidence-api keeps the window-summary key as a run ID input without opening the object, so run IDs stay equal (fake-mode evidence directory) and query-api's run ID is unchanged.
6. The probe key is logs/rag-runtime/snapshot=<d>/run=<runtime run ID>/readiness-probe.
7. I ran sbt check four times so that two passing runs cover the final tree after a comment-only fix.
RISKS:
- With services.query-api.threads = 1 no HTTP thread is reserved for the fast routes (default 8).
- A sync POST with an Idempotency-Key that gets 503 OVERLOADED leaves its key claimed, so a replay answers 409 until the TTL. This is the deferred idempotency release (ruling 18).
- workflowSlotFree uses ThreadPoolExecutor.getActiveCount, which is approximate. With concurrency > 1 and a leaked slot, two poll threads can race; the loser's job fails 503 OVERLOADED (retryable), with no breaker.
- While the store pool is full, /readyz reports NOT_READY for that downstream (by design, no breaker).
- SaturationSpec and the BuildingBlocks F1 test depend on timing (at least 200 ms of margin). They passed in every run here.
- Live mode is still not constructed or run.
- S3 needs s3:ListBucket for query-api and the worker (Phase 12). Without it, /readyz stays NOT_READY.
MANUAL ACTION REQUIRED: none for the human now. Phase 12 carry-forward (infra-engineer, ruling 23):
- SERVICES.md section 4: the per-task S3 grants, s3:ListBucket for query-api and the worker, and no curated/window-summary/* grant for graph-evidence-api.
- SERVICES.md section 5: deploy query-api and the worker together, with identical run inputs.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: API tests, sbt check, code-reviewer APPROVE (re-review of F1, m1, m2, m3, m5)
```

Not checked: live mode, and how the services behave under a real SIGTERM in the middle of a workflow.
````

## Part B: rag-engineer

````text
Part B is COMPLETE. I fixed m4 in modules/rag/RUNTIME.md (documentation only, no code change). I reran all 19 disable checks on the final verifier-v5 tree: WO1 D1 to D15 and WO9 D1 to D4. Each one fails the same named test or tests as in the original work orders, so no test needed strengthening. Every restore was verified by sha256, and the final check of all 21 rag main sources against the pre-disable manifest found 0 mismatches. `sbt check` passed twice with exit 0: 665 tests and 0 failures on the console and in the JUnit XML (61 suites).

Paths are relative to /Users/achyutpatel/Developer/Cloud/ScholarChain. Every cited log is under scratch/phase-10-logs/repair-01/part-b/ and was written by shell redirection.

## 1. m4: RUNTIME.md changes (old line, then new line)

**:403, section 17**
- Old: "... Without them, corpus questions abstain as before. No configuration key names the served window-summary run yet (section 20)."
- New: "... Without them, corpus questions abstain as before. The CLIs (`AskCli`, `BenchmarkCli`) take the load run from `--window-summary-run` only; they do not read the configuration key `runtime.window-summary.load-run` (section 20), which names the served run for the Phase 10 services."

**:482, section 20**
- Old: "Proposed for domain-engineer (Phase 10 work order 9, not added): `runtime.window-summary.load-run` (env `SCHOLARCHAIN_WINDOW_SUMMARY_RUN`), String, default empty (no window summaries served; corpus questions abstain). When set, it must be `run-<32 hex>`, and the runtime opens `S3Layout.windowSummary(<serving snapshot>, <value>)`. Until it exists, the CLI flags of section 17 name the run."
- New: "`runtime.window-summary.load-run` (env `SCHOLARCHAIN_WINDOW_SUMMARY_RUN`), String, default empty: proposed by Phase 10 work order 9 and added by domain-engineer in Phase 10 work order 10 (`cfg.runtime.windowSummary.loadRun`). Empty serves no window summaries, so corpus questions abstain; otherwise it must be `run-<32 lower-case hex>` (`ConfigLoader.validate` rejects anything else, including blank values). The Phase 10 services read it (work order 11): when it is set, query-api and the worker open `S3Layout.windowSummary(<snapshot>, <value>)` once at startup with `WindowSummaries.open` (a refused or missing object stops startup with exit 1), pass the port as `RuntimePorts.windowSummaries`, and add `window-summary=<key>` to the runtime run ID inputs. graph-evidence-api answers no questions and never opens the object, but it adds the same run ID input, so its run ID equals query-api's (repair-01 m3; `modules/services/SERVICES.md` sections 4 and 5). The snapshot is the `--graph` path segment in fake mode and `--snapshot` in live mode. The rag CLIs do not read this key; they use the flags of section 17."

**Other stale lines I fixed**
- :422, section 19 F2.
  - Old heading: "(... done in Phase 10 work orders 7 to 9)". New: "(... done in Phase 10 work orders 7 to 9, configured and wired in work orders 10 and 11)".
  - Old tail: "...; the services composition does not wire the port yet (service-engineer); no configuration key names the served run (domain-engineer, section 20)."
  - New tail: "The configuration key `runtime.window-summary.load-run` exists (domain-engineer, Phase 10 work order 10; section 20), and the services composition wires the port from it (service-engineer, Phase 10 work order 11, and repair-01 m3; section 23)."
- :74, section 3.
  - Old: "(`RuntimePorts.windowSummaries` None, for example the Phase 10 services until they wire it)".
  - New: "(`RuntimePorts.windowSummaries` None: the CLIs without the section 17 flags, and the Phase 10 services while `runtime.window-summary.load-run` is empty)".
- :593, section 23. Appended: "The services do this (Phase 10 work order 11, `Composition.windowSummaries`) when `runtime.window-summary.load-run` is set (section 20); graph-evidence-api adds the run ID input without opening the object (repair-01 m3)."
- :5, status line. Appended a sentence: "Phase 10 repair-01 part B (code review 1 m4) records the configuration key `runtime.window-summary.load-run` (work order 10) and how the services use it (work order 11, repair-01 m3) in sections 3, 17, 19, 20, and 23; no code changed."
- I took the facts from SERVICES.md section 4, Composition.scala :246-268, and scholarchain.conf :199-204. RUNTIME.md sha256 after the edits: a09bf308c816b9244961b487830ec0967a2f5c5dd347c7a57a6226e60c3dd453.

## 2. Disable checks on the final tree

**Method**
- The WO1 disable.py script no longer exists (its scratchpad is gone), and the old logs record only the patch name. So I rebuilt each patch from the WO1 and WO9 reports and from the pre-Phase-10 code shown in code review 1's change-set diff.
- Driver: scratchpad/part-b/disable.sh with patches.py. Each patch must match exactly once; all 19 were dry-run on a copy first.
- Each run backs up the file, applies one patch, writes the diff into the log, and runs `sbt -batch -J-Xmx4g "rag/testOnly <suites>"`.
- After the run it restores the file and checks it with `shasum -a 256 -c` against main-sha256-before-disable.txt (taken before any disable).
- Suites, as in the originals:
  - WO1: VerifierSpec, LlmSpec and RuntimeApiSpec (33 tests now; 30 at WO1).
  - WO9 D1: LlmSpec.
  - WO9 D2 and D3: VerifierSpec.
  - WO9 D4: CorpusSpec.

| Check | Rule disabled (patch) | Failing test (log line) | Result | Log | sha256 restore |
|---|---|---|---|---|---|
| WO1 D1 | ordinal binds to list position k → `else if false` | R3-1 list-position (:39) | 32/1 | disable-wo1-D1-positions.log | Verifier.scala OK |
| WO1 D2 | year ranges set aside before number words (pre-WO1 order, textProblems) | R3-1 list-position (:45) | 32/1 | disable-wo1-D2-order.log | OK |
| WO1 D3 | Discourse ("First,") rule removed | R3-1 ordering word (:40) | 32/1 | disable-wo1-D3-discourse.log | OK |
| WO1 D4 | "on one paper" measure-name rule → no-op ($0) | R3-2 (:41) | 32/1 | disable-wo1-D4-measure-name.log | OK |
| WO1 D5 | "once again" and "twice as" → no-op | R3-2 (:43) | 32/1 | disable-wo1-D5-fixed-phrases.log | OK |
| WO1 D6 | any ordinal followed by a year is a time marker, rank word or not (pre-WO1 rule) | n4 and review 2 n2 (:44) | 32/1 | disable-wo1-D6-year-exception.log | OK |
| WO1 D7 | ordinals first to tenth only | R3-1 list-position (:40), n5 (:45) | 31/2 | disable-wo1-D7-ordinals-to-tenth.log | OK |
| WO1 D8 | "coauthors per paper" out of average | n6 (:44) | 32/1 | disable-wo1-D8-average-vocabulary.log | OK |
| WO1 D9 | "joint publications" out of weighted | n6 (:44) | 32/1 | disable-wo1-D9-intensity-vocabulary.log | OK |
| WO1 D10 | rank and position branch → `if false` | R3-1 list-position (:39), n4 and n2 (:43) | 31/2 | disable-wo1-D10-rank-numbers.log | OK |
| WO1 D11 | pre-WO1 unknownNames (strip all texts; a part of a text passes) | review 2 n3 (:49) | 32/1 | disable-wo1-D11-unknown-names.log | OK |
| WO1 D12 | audit gets empty removed and dropped (Workflow) | n7 (:48) | 32/1 | disable-wo1-D12-audit-removals.log | Workflow.scala OK |
| WO1 D13 | withoutModelText returns the audit unchanged (RuntimeApi) | n7 (:49) | 32/1 | disable-wo1-D13-service-view.log | RuntimeApi.scala OK |
| WO1 D14 | missingRankIds not applied (Synthesis) | LlmSpec synthesis-output-v3 (:24) | 32/1 | disable-wo1-D14-v3-rank-ids.log | Synthesis.scala OK |
| WO1 D15 | deadlineMs ignored; configured deadline used | RuntimeApiSpec "answer: a passed deadline abstains ..." (:17) | 32/1 | disable-wo1-D15-deadline-param.log | Workflow.scala OK |
| WO9 D1 | cut-off rule → None | LlmSpec ruling 19a (:21) | 5/1 | disable-wo9-D1-cutoff-retry.log | OK |
| WO9 D2 | ungroup not applied | B18 digit-group test (:37) | 22/1 | disable-wo9-D2-digit-groups.log | OK |
| WO9 D3 | comparison side window removed (`w +: Vector.empty`) | B22 comparison-side test (:38) | 22/1 | disable-wo9-D3-comparison-side.log | OK |
| WO9 D4 | `ports.windowSummaries` → None into GraphStages | B13/B15, B17, B24, model-claim, AskCli (:16, :18, :20, :22, :36) | 1/5 | disable-wo9-D4-corpus-port.log | OK |

- No check passed with its rule disabled, so I strengthened no test.
- D6 failed on the first run here, because WO1 had already strengthened the n4 test.
- Summary: disable-summary-1.txt. It ends with the full manifest check: 0 files not OK.
- Final check: main-sha256-final-check.txt, 21 of 21 OK.
- The checks touched only modules/rag/**. D13 changes RuntimeApi.scala, so no services file was involved.

## 3. sbt check (SPARK_LOCAL_IP=127.0.0.1, sbt -J-Xmx6g check)

| Project | Run 1 console | Run 1 JUnit (suites) | Run 2 console | Run 2 JUnit (suites) |
|---|---|---|---|---|
| core | 185 | 185 (4) | 185 | 185 (4) |
| entityResolution | 111 | 111 (10) | 111 | 111 (10) |
| hadoop | 70 | 70 (6) | 70 | 70 (6) |
| rag | 102 | 102 (14) | 102 | 102 (14) |
| services | 43 | 43 (8) | 43 | 43 (8) |
| sparkAnalytics | 46 | 46 (8) | 46 | 46 (8) |
| storage | 108 | 108 (11) | 108 | 108 (11) |
| Total | 665 passed / 0 failed | 665, 0 failures, 0 errors (61) | 665 / 0 | 665, 0 failures, 0 errors (61) |

- Run 1: exit 0, 259 s, completed 5:57:29 PM. markTestRun wrote the marker (sbt-check-1.log:1237).
- Run 2: exit 0, 260 s, completed 6:02:08 PM. Marker written (sbt-check-2.log:1234).
- 0 "*** FAILED" lines in either log. JUnit report times in junit-counts-1.txt and junit-counts-2.txt all fall within each run.
- Logs: sbt-check-1.log, sbt-check-2.log, junit-counts-1.txt, junit-counts-2.txt.

## 4. End state
- Free disk: 15Gi at the start (df-start.txt), 14 GiB before run 1, and 15Gi at the end (df-end.txt).
- No sbt or java process is left (pgrep exit 1). Ports 8080 to 8082 are free (lsof exit 1). All three background shells (the disable batch and two checks) finished with exit 0.
- No file under modules/rag or prompts (outside target/) is newer than .claude/.last-test-run.
- No file under scratch/stores, scratch/spark, scratch/hadoop or scratch/runtime is newer than this session's start.

```text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 10 repair-01 Part B (code review 1 m4; rerun of the WO1 D1-D15 and WO9 D1-D4 disable checks on the final verifier-v5 tree; sbt check twice)
FILES CREATED: none in the repository. Logs (by redirection) under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-10-logs/repair-01/part-b/: df-start.txt, df-end.txt, main-sha256-before-disable.txt, main-sha256-final-check.txt, disable-summary-1.txt, 19 disable-wo1-*/disable-wo9-* logs, sbt-check-1.log, sbt-check-2.log, junit-counts-1.txt, junit-counts-2.txt. Scratchpad (not durable): /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/7d921cd3-74fa-4486-933f-269f99093723/scratchpad/part-b/{patches.py, disable.sh, junit.py}
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md (lines 5, 74, 403, 422, 482, 593; documentation only)
- Verifier.scala, Workflow.scala, Synthesis.scala and RuntimeApi.scala were patched one at a time and restored byte-identical (sha256 OK)
FILES DELETED: none
ALLOWED PLAN OPERATIONS: unchanged
PROMPT, SCHEMA, AND MODEL VERSIONS: unchanged (planner-v2/planner-output-v1, synthesis-v3/synthesis-output-v3, verifier-v5, rag-runtime-v3, rag-audit-v3, us.anthropic.claude-haiku-4-5-20251001-v1:0)
IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR: unchanged
VERIFICATION RULES: unchanged (verifier-v5); every WO1 and WO9 rule still fails a named test when disabled on the final tree
BENCHMARK RESULTS: not rerun (no code change); InjectionSpec passes within rag 102
KNOWN FAILURE MODES: unchanged (RUNTIME.md section 21)
COMMANDS RUN:
- Reads: sed, grep, wc, ls, find, cat, shasum, df, pgrep, lsof; python3 -I dry runs and JUnit counts
- Edit tool on RUNTIME.md; mkdir of the part-b log and scratchpad directories
- disable.sh: 19 runs of sbt -batch -J-Xmx4g "rag/testOnly ...", each followed by restore and shasum -c
- sbt -J-Xmx6g check twice (SPARK_LOCAL_IP=127.0.0.1 on every sbt run)
- Not run: git, aws, network, --live/--profile. Nothing under labels/, scratch/stores-live/ or scratch/er/adjudication-live/ was read or listed.
- I read code review 1's change-set diff in its session scratchpad to rebuild the pre-change code.
TEST RESULTS: both sbt check runs exit 0, console 665 passed / 0 failed; JUnit XML 665 tests / 0 failures / 0 errors in 61 suites; core 185, entityResolution 111, hadoop 70, rag 102, services 43, sparkAnalytics 46, storage 108.
VALIDATION RESULTS:
- 19 of 19 disable checks failed a named test; none needed strengthening
- every restore was sha256 OK, and the final manifest check was 21/21 OK
- no rag source is newer than the marker
- no process left running; ports 8080-8082 free; 15Gi free at the end
ASSUMPTIONS:
1. The original WO1/WO9 patches are not recorded verbatim. I rebuilt each one from the report's description and, for D2, D6, D7 and D11, from the pre-Phase-10 code in code review 1's diff. D2 patches the textProblems order only (the line WO1 cited), not numbersIn.
2. WO1 checks ran the three suites WO1 used; WO9 checks ran the single suite each WO9 log shows.
3. The rag CLIs keep taking the load run from flags only. WO10 called a fallback to the key optional, and Part B asks only for documentation.
RISKS:
- A rebuilt patch may differ slightly from the WO1 original. Each one disables the rule as described and fails the same named test as the original logs.
- The scratchpad scripts are not durable.
MANUAL ACTION REQUIRED: orchestrator appends this report to docs/agent-runs/phase-10/repair-01.md under "## Part B: rag-engineer"
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: retrieval and groundedness evaluation, code-reviewer APPROVE (re-review of repair-01)
```

Not checked: I did not rerun the fake benchmark, because no code changed. If the reviewer wants the original WO1 patch text, it cannot be recovered; only the rebuilt patches in the scratchpad exist.
````
