## Code review 1: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 10, session 2b, Code review 1. Reviews work orders 1 to 11 against rulings 1 to 21, spec Phase 10 (work order and gate), the REST API contract, the Error model, D-10, and the ruling 21 added checks. Read-only.

VERDICT: REQUEST_CHANGES

There is one MAJOR finding (F1) and no CRITICAL finding. Every gate item, every Phase 9 ruling 31 item and every ruling 21 check passes.

SCOPE REVIEWED
- The change set in /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/e088be50-3ecc-4347-8fa5-df3cb8927486/scratchpad/ (status.txt, diff.txt, change-set.txt).
- Read in full: every services main and test source, RuntimeApi.scala, WindowSummaries.scala, LoadWindowSummaryCli.scala and openapi.yaml (spot-checked).
- Diffs of Verifier, Llm, Synthesis, Evidence, GraphStages, Workflow, Records, Contracts, GraphRead, LiveAws, scholarchain.conf, build.sbt and Dependencies.scala.
- The VerifierSpec R3-1 to n7 tests and the work order 9 tests, LlmSpec 19a, CorpusQuerySpec, LifecycleSpec, QueryApiSpec and ServicesContractSpec.
- Logs under scratch/phase-10-logs/{wo1,wo4,wo5,wo6,wo7,wo9,wo11}, docs/agent-runs/phase-10/evidence/session-2c-logs/wo11/*, and the WO9 benchmark answers under scratch/runtime/wo9/.
- Not read: labels/, scratch/stores-live/, scratch/er/adjudication-live/.

FINDINGS (ranked)

F1. MAJOR (verified against the files). Saturating the shared downstream pool opens circuit breakers against healthy stores.
- Where:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/main/scala/scholarchain/services/Composition.scala:295-296 and :305-307. query-api runs every downstream call (dynamodb, s3, neptune, readiness probes) and the whole sync workflow on one Downstreams pool of `services.query-api.threads` (8) threads with a 64-entry queue.
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/main/scala/scholarchain/services/Services.scala:41-47. The runtime policy has interruptOnTimeout = false, so a sync request that hits the 40 s hard timeout returns 504 and frees its HTTP thread. Its workflow keeps a pool thread until the cooperative deadline, which a model call can overrun by up to runtime.llm.timeout-ms (30 s).
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/main/scala/scholarchain/services/Resilience.scala:113-130. `f.get(p.timeoutMs)` counts from submit, so time spent waiting in the queue counts toward the timeout. A queued call that times out goes through `failed(...)`, which is `b.failure()`.
  - Services.scala:108-114 and Ops.routes :71-76. Readiness probes go through the same pool, and any open breaker makes /readyz NOT_READY.
- Failure input:
  - Live mode with services.stub-llm=false (the Phase 12 target). 10 sync POSTs within one minute, which the rate limit allows, with model calls queued on the 8-per-minute throttle. Ruling 19 recorded a 45,712 ms live question. At least 8 workflows are still running past the 40 s hard timeout.
  - Then about 5 calls to GET /v1/jobs/{id} or the load balancer's /readyz. Each call waits in the queue, times out at dynamodb-ms 2000 or s3-ms 5000, and counts as a failure of a downstream that is healthy.
- Impact:
  - The dynamodb, s3 and neptune breakers open for breaker-open-ms (30 s).
  - Every POST with an Idempotency-Key, every async POST, the job, result and explain routes, and replays answer 503 CIRCUIT_OPEN.
  - /readyz reports NOT_READY. If the target group uses readiness, the task can be pulled from service.
  - Spec Phase 10 wants the fast and slow paths kept separate.
  - This behaviour differs from what work order 5 disclosed. The risk at agent-report.md:1231 says "503 OVERLOADED". OVERLOADED is reached only after 64 queued calls; before that, queued calls time out and open breakers.
- No test covers pool saturation.
- Owner: service-engineer. The fix needs a test that fails without it.

MINOR (each verified against the file)
- m1. The readiness probe and unknown-ID reads depend on S3 IAM that no document states.
  - Services.scala:51 and :109, and Worker.scala:60 probe the key "readiness-probe" at the root of the data bucket. /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/live/S3Live.scala:94 maps only NoSuchKeyException to None.
  - Without s3:GetObject on that root key and s3:ListBucket, S3 answers 403. /readyz is then NOT_READY permanently.
  - Under the same IAM, explain, result or replay for an unknown well-formed qry ID gives 502 and counts toward the s3 breaker.
  - Owner: service-engineer (document it, or move the probe key under a granted prefix). Phase 12 follow-up: infra-engineer.
- m2. The async result path only works when query-api and worker compute the same runtime run ID. No document says so and no code checks it.
  - Services.scala:273-277 (storedAnswer for the reader's setup.runId); Worker.scala:165-169; run ID inputs at Composition.scala:120-130 and :186-196.
  - Any difference between the two tasks breaks it: window-summary run, prompt version, config, or a rolling deploy. A SUCCEEDED job's /result then returns 502, and explain returns 404.
  - This follows from the ruling 16 decision that resultRef is not written, so it is a documentation and risk item, not a design reversal.
  - Owner: service-engineer (document it). Phase 12: infra-engineer.
- m3. Live graph-evidence-api also opens the window-summary object at startup.
  - Composition.scala:165 runs for every main (Launcher.backend :387-406).
  - Ruling 20 and work order 11 grant s3:GetObject on curated/window-summary/* only to query-api and worker. If Phase 12 sets SCHOLARCHAIN_WINDOW_SUMMARY_RUN on every task, graph-evidence-api exits 1 at startup.
  - Owner: service-engineer (document it). Phase 12: infra-engineer.
- m4. Stale text after work orders 10 and 11.
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md:403 says "No configuration key names the served window-summary run yet".
  - RUNTIME.md:482 says the key is "Proposed ... not added".
  - Owner: rag-engineer.
- m5. Resilience.scala:136-138 maps any IllegalArgumentException from a downstream to 400 INVALID_FIELD and does not count it as a failure. A server-side `require` therefore shows up as a client error. This is narrow, because routes validate first. Owner: service-engineer.

SPEC PHASE 10 GATE
- (a) Routes embed no retrieval, verification, or query logic: PASS.
  - Services.scala:116-177: validate, then RuntimeApi.answer.
  - :279-288: RuntimeApi.audit.
  - :328-340: evidenceItem, then RuntimeApi.evidenceOf.
  - :347-409: GraphReadPort.vertex and neighbors only (parameter parsing and cursor binding).
- (b) Every error response carries a traceId: PASS.
  - Http.scala:211-218 and :237-243 (every ApiError and every unexpected NonFatal becomes ErrorBody).
  - Models.scala:26-32 (ErrorBody has error, message, traceId, requestId, retryable).
  - Not covered: responses the JDK server generates itself.
- (c) Health and readiness expose no secrets or configuration: PASS.
  - Services.scala:70-76: liveness returns {"status":"ok"}; NOT_READY names downstreams only.
  - QueryApiSpec.scala:255-284 checks for the key, the variable name, the port, the endpoint, the bucket and the root path.
- (d) Request and response examples match the contract tests: PASS.
  - ServicesContractSpec.scala:24-57: all 29 examples are referenced and round-trip exactly.
  - :59-79: the documented paths equal the three route tables.
  - The openapi.yaml component schemas themselves are not machine-checked against the models; only the examples are.
- (e) An end-to-end API test reaches an evidence-backed answer from bounded fixtures: PASS.
  - LifecycleSpec.scala:31-80: sync and async over real sockets, a VERIFIED claim, the cited evidence returns 200 with provenance, and explain matches.
  - Also CorpusQuerySpec.scala:23-58.

PHASE 9 RULING 31 ITEMS
Tests are in /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/VerifierSpec.scala unless named otherwise. The disable logs are in scratch/phase-10-logs/wo1/.

| Item | Resolved | Test | Fails without the fix (log) |
|---|---|---|---|
| R3-1 | yes | :571, :633 | disable-D1-positions.log:33, D2-order.log:33, D3-discourse.log:34, D10-rank-numbers.log:33, D7-ordinals-to-tenth.log:33 |
| R3-2 | yes | :652 | D4-measure-name.log:35, D5-fixed-phrases.log:35 |
| n1 and the stale default-version lines | yes (documentation only, no test) | none | RUNTIME.md:442 and :467 state the current defaults; "until configuration selects v2" is gone; Records.scala:398 Scaladoc names v1/v2/v3 |
| n2 | yes | :694 | D10-rank-numbers.log:37 |
| n3 | yes | :796 | D11-unknown-names.log:39 |
| n4 | yes | :694 | the first disable run passed 30/0 (D6-year-exception.log:46), as disclosed; after the test was strengthened it fails (D6-year-exception-2.log:27) |
| n5 | yes | :745 | D7-ordinals-to-tenth.log:38 |
| n6 | yes | :767 | D8-average-vocabulary.log:38, D9-intensity-vocabulary.log:38 |
| n7 | yes | :819, plus services QueryApiSpec.scala:286 | D12-audit-removals.log:40, D13-service-view.log:40, wo5/disable-D2-explain-raw-audit.log:32 |
| rank researcherId (WO1 item 2) | yes | LlmSpec | D14-v3-rank-ids.log:19 |

Every wo1 disable log ends with sha256 "OK" for the restored files.

RULING 21 ADDED CHECKS
- verifier-v5 digit groups: PASS.
  - `ungroup` (Grouped regex) is applied first in wordsToDigits.
  - "2001,2002", "1,23" and "12,3456" stay apart.
  - Test: VerifierSpec:923. Fails without the fix: scratch/phase-10-logs/wo9/disable-D2-digit-groups.log:36.
- verifier-v5 comparison sides: PASS.
  - `windowsOf` adds the side window only when every asserted fact is a single-side from<Fact> or to<Fact>. A delta or a mix of sides keeps a>b only.
  - Test: VerifierSpec:965 covers both sides, the wrong side, a delta and a mix. Fails without the fix: wo9/disable-D3-comparison-side.log:37.
- Ruling 19a retry: PASS.
  - Llm.scala:136-137 puts Synthesis.cutOffRule first when the stop reason is max_tokens. Workflow passes it for synthesis only. The rule asks for max(1, max-claims / 2) claims, and no token limit changes.
  - Test: LlmSpec:265-307. Fails without the fix: wo9/disable-D1-cutoff-retry.log:20.
- Corpus evidence path for B13, B15, B17, B24 and its uncertainty lines: PASS.
  - GraphStages.corpusRows reads obj.window-summary. Without a port it reports NOT_STORED and the question abstains.
  - Evidence items carry the row's provenance (its Spark run), and sourceUri is <key>#<window>.
  - The fixed limitation lines are in the WO9 answers:
    - B15 qry-0fef9c33…answer.json:48 (node basis)
    - B17 qry-cad5df20…:48 (node basis and convergence)
    - B13 qry-91b1ddc7…:48 (node basis)
    - B24 qry-d97c892c…:86 (two node-basis lines and the window-length line)
  - Tests: CorpusSpec. Fails without the port: wo9/disable-D4-corpus-port.log (5 of 6 fail).
- WO8 manifest-based conversion: PASS, as the shortcut ruling 20 accepted.
  - Column mapping: WindowSummaries.scala:285-327 adds null for missing nullable fields and adds nodeBasis, sourceRuns and a provenance rebuilt as Spark builds it. parseRows then requires exactly the Spark column set.
  - Refusal: wrong manifest schema, wrong stage, a table whose schema string, schemaVersion or rowCount does not match, any integrity violations, any BAD_ROW, a duplicate or missing window, or mixed runs.
  - LoadWindowSummaryCli.scala:34-39 refuses a manifest whose run or snapshot differs from the path.
  - The Spark manifest rows come from the written table (CorpusWindowSummaryJob.scala:306-316), which supports the mapping.
- Services test-count explanation (WO11): PASS.
  - The stale generated TEST-scholarchain.services.GenerateExamplesSpec.xml (tests="1", timestamp 02:52:47) sat next to the other suites' reports.
  - docs/agent-runs/phase-10/evidence/session-2c-logs/wo11/junit-stale-evidence.txt lists it beside the 15:47 reports, with 0 sources and 0 classes named GenerateExamples*.
  - Final count is 37 = 6+6+6+2+12+3+2 suites, which matches the test( declarations.
- Constant-time key comparison (read from the code): PASS.
  - Http.scala:39-45 keeps the SHA-256 of the key. Each request hashes the header (absent becomes "") and compares with MessageDigest.isEqual over two 32-byte arrays.
  - The comparison time depends on neither the key's content nor its length. Only the attacker's header length affects hashing time.
  - toString is redacted.

OTHER CODE-REVIEW-1 CHECKS
- Authentication and key redaction in logs: PASS.
  - The key is checked before routing (Http.scala:196-197), so unknown routes give 401.
  - The key is read once from the environment variable configuration names. The startup error names the variable only (Composition.scala:441-444).
  - JsonLog writes fixed fields only (Observability.scala:13-69).
  - Tests: QueryApiSpec:393-411 and LifecycleSpec:101. Removing the redaction fails tests: wo5/disable-D1-key-in-log.log:25,36,38.
- Idempotency and job-state safety under redelivery: PASS.
  - JobStore.transition is a read followed by a PutItem conditioned on the state, and only canMoveTo transitions are allowed (ServiceState.scala:352-384).
  - The worker runs a job only on Moved (Worker.scala:145-157). After an unknown write outcome it reads the record back and does not run the job.
  - A message is deleted only in a final state (:235-237). Poison messages are left to the redrive policy.
  - Disable logs: wo6 D1-D5 and wo4 D1-D3 each fail a named test.
  - The release of a failed idempotency key is deferred by ruling 18.
- Graph exploration is bounded: PASS.
  - direction must be in, out or both; depth 1 to 3 and limit 1 to 200 from config; edge types from an allowlist; one value per parameter.
  - The cursor is bound to (node, direction, depth, sorted edge types). offset + limit ≤ max-results (1000) is checked in the route (Services.scala:390) and in the port (GraphRead.scala require).
  - Each read is bounded by neptune-ms. Tests: GraphEvidenceApiSpec:76 and GraphReadSpec.
- Forbidden paths: PASS.
  - Every report states no reads of labels/, scratch/stores-live/ or scratch/er/adjudication-live/.
  - A grep of scratch/phase-10-logs for those paths finds 0 hits.
  - The only hits under docs/agent-runs/phase-10/evidence are live-benchmark-step1.txt:7 and step2.txt:7. They are the human's ruling 19 live benchmark command (--vectors scratch/stores-live/s3vectors), not an agent read.

OWNERSHIP CHECK (path, owner, author matches)
- build.sbt, project/Dependencies.scala: build-engineer (WO3). Yes.
- modules/core/**: domain-engineer (WO2, WO10). Yes.
- modules/rag/** and prompts/synthesis/synthesis-v3.md, synthesis-output-v3.schema.json: rag-engineer (WO1, WO9). Yes.
- modules/spark/JOBS.md and CorpusWindowSummary*: spark-engineer (WO7). Yes.
- modules/storage/**: storage-engineer (WO4, WO8). Yes.
- modules/services/**, including openapi and examples: service-engineer (WO5, WO6, WO11). Yes.
  - WO11 also deleted a generated file, modules/services/target/test-reports/TEST-…GenerateExamplesSpec.xml. It is build output inside its own module tree, and a copy was kept.
- docs/agent-runs/phase-10/agent-report.md: the orchestrator's assembly, which is allowed.
- docs/agent-runs/phase-10/evidence/** (untracked; live-benchmark-run copies, session-1-scratchpad, session-2a-logs, session-2c-logs): human architect row.
  - No agent report claims writing these files.
  - They are presumed to be human copies (ruling 19 names the live-benchmark-run copy). The human should confirm.
- Outputs under scratch/: scratch/runtime/wo7..wo9, scratch/services/smoke-*, and scratch/phase-10-logs/**. Ruling 14 permits these.

FLOOR AND FENCE CHECK: none. Nothing under .claude/, CLAUDE.md, docs/agents.md, .mcp.json, hooks, permissions, check or markTestRun changed.

EVIDENCE GAPS
- The fails-without-fix evidence for R3-1 to n7 was produced on the WO1 tree (verifier-v4). WO9 then changed Verifier.scala (v5), and the WO1 disable checks were not rerun on the final tree. The tests still pass at 659/0.
- Live mode (Composition.live, SqsJobQueue, DynamoServiceStateTable, the S3 window-summary open) was never constructed or run. It was tested against stub SDK clients only, as the reports state.
- The WO8 rebuilt provenance was not compared with the Parquet bytes (pyarrow is unavailable). This is the shortcut ruling 20 accepted.
- WO7, WO8 and WO10 tests were not run on a tree without the change, because they would not compile. The reports state this.
- The WO11 smoke ran once; the work order did not ask for twice.

REPORT-CLAIM DISCREPANCIES
- WO5 RISKS (agent-report.md:1231) says sustained slow model calls give 503 OVERLOADED. In the code, queued calls time out as DOWNSTREAM_TIMEOUT and open breakers first (F1).
- WO11 item 4 says WO6 and WO7 "reported 34 in the JUnit XML". Their tables match the sbt console (scratch/phase-10-logs/wo7/sbt-check-1.log:1124-1126, "Total number of tests run: 34"), not the JUnit XML. WO7 lists "python reads of … JUnit XML" but cites no counts file. No impact.
- WO10 says a STORAGE.md "Window summaries" risk note about the missing configuration key is out of date. No such note exists in STORAGE.md (section at :592-649). That text was only in the WO8 report.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, checked against logs)
- WO11, both sbt check runs: console 659 passed / 0 failed. JUnit XML 659 tests / 0 failures / 0 errors in 60 suites.
- Per project: core 185, entityResolution 111, hadoop 70, rag 102, services 37, sparkAnalytics 46, storage 108.
- markTestRun wrote the marker each time.
- Checked against scratch/phase-10-logs/wo11/sbt-check-2.log:925-1228 and docs/agent-runs/phase-10/evidence/session-2c-logs/wo11/junit-counts-2.txt.

VALIDATION RESULTS
- Every disable log I opened under wo1, wo4, wo5, wo6, wo9 and wo11 shows the named test FAILED. The exception is wo5 D3 (constant-time comparison), which passed 24/0, as disclosed.
- The WO11 smoke shows SMOKE PASSED (scratch/phase-10-logs/wo11/smoke-1.log:53).
- The B13 smoke answer has claim-1 and claim-2 VERIFIED, with evidence ev-b0de284ed5b7a6dd4680db3a6dabcf76 and provenance.runId run-1de2293597c338227290eb382de16957 (smoke-1.log:18-23).

ASSUMPTIONS
- The F1 scenario rests on the configured defaults (8 HTTP threads, 8 downstream threads, queue 64, rate limit 10 per minute, model throttle 8 per minute) and on the live latencies recorded in ruling 19.
- m1 rests on S3's documented behaviour: a GET of a missing key returns 403 without s3:ListBucket, and 403 whenever GetObject is not granted for the key.

RISKS
- F1 shows only under load with slow live model calls, so Phase 10 fake-mode tests cannot catch it.
- m1, m2 and m3 surface first in Phase 12 deployment.

MANUAL ACTION REQUIRED
- Route F1 and m1, m2, m3, m5 to service-engineer, and m4 to rag-engineer.
- Record m1, m2 and m3 for infra-engineer (Phase 12).
- Human: confirm the authorship of docs/agent-runs/phase-10/evidence/**.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to their owners (one repair round per the session plan; minors may become residual risks), then re-review. Human gate on APPROVE.
````

## Code review 2: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 10, code review 2. This re-reviews repair-01 Parts A and B against code review 1's F1 and m1 to m5, rulings 22 and 23, the Part B evidence table, the ownership rows and the Phase 10 gate items. Read-only.

VERDICT: APPROVE

Summary: F1 and m1 to m5 are all resolved, and every fix has a test. I opened every disable log I cite: each one shows the named test FAILED and ends with the sha256 restore OK. All five Phase 10 gate items still hold. Repair-01 wrote only inside its owned paths. There are no CRITICAL or MAJOR findings. Five MINOR findings are listed below; none blocks.

SCOPE REVIEWED
- Work order: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c198676e-1670-4873-a2e6-da055e7b1334/scratchpad/review-2-work-order.md, read in full.
- Change set: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c198676e-1670-4873-a2e6-da055e7b1334/scratchpad/change-set-review-2.txt. I read the header, the git status and the whole "Repair-01 delta" section.
- Current tree, read in full: Resilience.scala, Services.scala, Worker.scala, SaturationSpec.scala and modules/services/SERVICES.md.
- Current tree, read in part:
  - Composition.scala :85-418
  - main/LocalRun.scala, main/QueryApi.scala, main/Worker.scala, main/GraphEvidenceApi.scala
  - Models.scala :420-524 (parse and toRuntime)
  - ServiceFixtures.expectError
  - rag Audit.scala :20-156
  - the RuntimeApi payloadRef lines
  - the Verifier YearRange and wordsToDigits sites
  - ConfigLoader load-run validation
  - the RUNTIME.md stale-phrase grep
- Logs: every disable-D*.log (including the *-testv1 copies), main-sha256-before-disable.txt, main-sha256-after-disable-2.txt, sbt-check-1..4.log, smoke-1.log and smoke-server.log under scratch/phase-10-logs/repair-01/.
- Part B: all 19 part-b/disable-wo*.log, disable-summary-1.txt, main-sha256-final-check.txt and sbt-check-1..2.log.
- Patch scripts: docs/agent-runs/phase-10/evidence/repair-01-scratchpad/disable.py, part-b/patches.py and part-b/disable.sh.
- Not read: labels/, scratch/stores-live/, scratch/er/adjudication-live/.

CODE REVIEW 1 FINDINGS: RESOLUTION
All disable logs are under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-10-logs/repair-01/. "Checked" means I opened the log and saw both the FAILED line and the restore OK.

F1 (MAJOR): RESOLVED.
- The fix:
  - Resilience.scala:103-105: two pools. The store pool has a queue of `queue`; the workflow pool uses a SynchronousQueue (no queue).
  - Resilience.scala:116-135: `Start` and `await`. A call's timeout counts from its start. A call never started is given up as OVERLOADED and touches no breaker.
  - Resilience.scala:161-162: a refused submit is OVERLOADED, thrown before the `try`, so no breaker is touched.
  - Services.scala:42-49 and Worker.scala:55-61: the runtime policies carry `workflow = true`.
  - Composition.scala:320-327: query-api gets max(1, T/2) workflow slots.
  - Worker.scala:39-46 (pools), :93 (the poll loop waits for a slot), :157-159 (no claim without a slot).
- Tests:
  - BuildingBlocksSpec "waiting to start is no downstream failure: 503 OVERLOADED, no breaker (repair-01 F1)".
  - SaturationSpec "query-api: with every workflow slot held, jobs, explain, and readiness still answer".
  - SaturationSpec "worker: an overrun job holds only its workflow slot; no job is claimed until it frees".
- Fails without the fix (checked):
  - disable-D0-original-behaviour.log:18-28. All 3 tests fail. The query-api job read gives 504 DOWNSTREAM_TIMEOUT. The worker's dynamodb probe fails because its breaker is open. Restore at :40.
  - D1-queue-wait-counts.log:16-17, restore at :35.
  - D2-shared-pool.log:14-21, restore at :38.
  - D3-worker-claims-without-slot.log:15-16, restore at :27.
  - D4-worker-loop-receives-without-slot.log:15-16, restore at :27.
- Each restored sha256 equals the matching manifest entry in main-sha256-before-disable.txt (Resilience 7607aafd…, Worker 802da400…).

m1: RESOLVED.
- The fix: Services.scala:57-58 (`Probes.objectKey` = logs/rag-runtime/snapshot=<d>/run=<runId>/readiness-probe), used at Services.scala:116 and Worker.scala:65.
- The S3 grants are documented in SERVICES.md:66-88.
- Test: QueryApiSpec "readiness probes S3 under the audit prefix query-api and the worker read (repair-01 m1)". It covers both query-api and the worker, because both call `Probes.objectKey`.
- Fails without the fix (checked): D5-probe-key-at-bucket-root.log:26-27, `Vector("readiness-probe") did not equal …`. Restore at :40.

m2: RESOLVED (documentation only, as ruled).
- SERVICES.md:90-122 lists every runtime run ID input and the rule to deploy query-api and the worker together.
- The startup line exists in both modes: Composition.scala:143 (fake) and :209-213 (live).
- No test is expected: the behaviour did not change.

m3: RESOLVED.
- The fix:
  - Composition.scala:245-248: `windowSummaryKey`.
  - Composition.scala:263-269: with `answers = false` the object is not opened, but the key is still a run ID input (Composition.scala:134 and :205).
  - main/GraphEvidenceApi.scala:10-15 passes `answers = false`. QueryApi and Worker keep the default (true).
  - LocalRun shares one backend that opens the object. That is correct for a single JVM that also hosts query-api.
- Test: CorpusQuerySpec "graph-evidence-api never opens the window-summary object; same runtime run (repair-01 m3)".
- Fails without the fix (checked): D6-graph-evidence-opens-summaries.log:19-20 (OBJECT_MISSING at CorpusQuerySpec.scala:131). Restore at :31.
- See minor finding N2: the main's own `answers = false` line is not covered by any test.

m4: RESOLVED (documentation only, rag-engineer).
- RUNTIME.md :403 and :482 now state that the key exists (work order 10) and how the services use it (work order 11, repair-01 m3). Lines :5, :74, :422 and :593 were also updated.
- A grep for the stale phrases ("No configuration key names", "Proposed for domain-engineer", ", not added)", "does not wire the port", "until they wire") finds 0 matches.
- The facts match ConfigLoader.scala:694-696 and Composition.scala:245-281.
- No test is expected for a documentation change.

m5: RESOLVED.
- The fix: Resilience.scala:181-184. An IllegalArgumentException inside a downstream becomes INTERNAL_ERROR "unexpected internal failure", with no detail and no breaker failure.
- Route validation still answers 400: the GraphEvidenceApiSpec test checks `direction=up` and gets INVALID_FIELD.
- The error body still carries the traceId: `expectError` (ServiceFixtures.scala:404-411) checks the X-Trace-Id header against the body's traceId, and the five-key body.
- Tests:
  - GraphEvidenceApiSpec "an IllegalArgumentException inside a downstream call is 500 with no detail (repair-01 m5)". With breaker threshold 1 it gets 500 twice, no breaker opens, /readyz is 200, and no detail appears in the body or the logs.
  - BuildingBlocksSpec "downstream calls …", changed to expect INTERNAL_ERROR.
- Fails without the fix (checked): D7-iae-is-400.log:15-16 (BuildingBlocksSpec.scala:80) and :28-29 (400 instead of 500). Restore at :42.
- The only IllegalArgumentException source in rag is Contracts.scala:38 (enum decode of a stored record). Answering 500 there is correct.

ADVERSARIAL REVIEW OF THE F1 POOL DESIGN (no CRITICAL or MAJOR found)
- **Start race.** `begin` writes the volatile `atNs` before its compare-and-set. A failed `giveUp` sees state 1, so it also sees `atNs`. Exactly one of the pool thread and the caller wins.
  - A call given up returns null from the task, and the caller never sees it.
  - A call that starts late gets the rest of its timeout counted from its start. In the worst case the caller waits about twice the timeout. That is by design.
- **Leaked slots and cancelled tasks.** `f.cancel(false)` leaves a cancelled task in the store queue until a thread drains it. Only the 64-entry queue capacity is affected, and only when the store pool is already saturated.
  - A workflow that overruns keeps its slot until its cooperative deadline. Model calls and store calls carry SDK timeouts, so the slot is bounded.
  - /readyz stays ready while the workflow pool is full. The work order asks for that; 503 counts in /metrics are the signal.
- **getActiveCount.** It counts workers that are running a task, under the executor's main lock. It is wrong only in the microseconds between a task finishing and its worker unlocking.
  - With concurrency 1, the worker makes two store calls (`move`, `delete`) before its next slot check, so that window is effectively closed.
  - If the transient does hit, the loop sleeps 1 s (harmless), or `begin` keeps the message (see N4).
  - With concurrency > 1, two poll threads can race, and the loser's job fails 503 OVERLOADED. This is disclosed in the Part A RISKS.
- **Interrupted calls and calls that never start.** An interrupted caller cancels its future with interruption and answers OVERLOADED, with no breaker. `shutdownNow` drops queued tasks, and their callers give up as OVERLOADED.
- **Breaker accounting.** Only these count as failures:
  - a call that started and timed out (`left <= 0`, or the second `get` times out);
  - an ExecutionException other than ApiError or IllegalArgumentException.

  A refused submit, a call that was given up, and an IllegalArgumentException never touch the breaker. `b.success()` runs only after a result is returned.
- **SynchronousQueue hand-off.** With core = max = n and no core timeout, idle threads wait in `take()`, so a submit is refused only when every thread is busy. That makes SaturationSpec's split of 2×504 and 2×503 deterministic: the thread from the earlier `answered` workflow is idle in `take()`.
- **Message visibility and redelivery when no slot is free.** See N4: the message is kept, comes back after the full visibility timeout and counts a receive.
- **Idempotency on 503.** See N3: the key claim at Services.scala:150 comes before the workflow submit at :162.
- **Could a timing-dependent test pass on broken behaviour?** No case found.
  - The query-api saturation test asserts the exact status mix, a 1000 ms bound per fast route, and no `circuitOpened` counter with breaker threshold 1.
  - The worker test asserts the job states, `blocked == 1`, unchanged receive counts and probe latency.
  - D0 to D4 show that each disabled rule fails at least one test.
  - The BuildingBlocks "timeout counts from start" check has about 200 ms of margin (300 ms waiting + 300 ms running against a 500 ms timeout). It passed in all six full `sbt check` runs.
  - D1 on its own is not caught by SaturationSpec, because the separate pools never queue there. The unit test catches it.

PART B EVIDENCE TABLE CHECK
- All 19 logs show a non-empty applied diff at lines 3-11, so no "PATCH FAILED" silently ran an unpatched build.
- Each log shows the named test FAILED (the lines match the Part B table) and `<file>: OK` on restore.
- disable-summary-1.txt ends with "files not OK: 0", and main-sha256-final-check.txt shows 21 of 21 OK.
- Each rebuilt patch disables the rule its row names:
  - D1 and D10 use `if false`.
  - D3 removes the Discourse rule.
  - D4 and D5 turn their rules into no-ops.
  - D8 and D9 drop the vocabulary words.
  - D12 empties the audit's removed and dropped lists. D13 returns the audit unchanged. D14 turns off the rank researcherId check. D15 ignores the deadline parameter.
  - WO9 D1 to D4 disable the cut-off retry, the digit groups, the comparison side and the corpus port.
- Rows where the patch is narrower or combined (minor finding N5):
  - D2 flips only the `textProblems` order (Verifier.scala:526-527). `numbersIn` (Verifier.scala:571) has the same words-then-years order, and no disable check covers it. Part B Assumption 1 discloses this.
  - D5, D6, D7 and D11 each restore a whole pre-WO1 rule, which removes more than one condition at once. For example, D6 removes both `word == "first"` and `!ranked(...)`. They prove only that at least one of the combined conditions is tested.

OTHER CHECKS
- **m1 probe key.** It sits under logs/rag-runtime/…/run=<runId>/. Audit objects there are named `qry-<hex>.json` or `<qry>/evidence/…`, so the probe cannot collide with a real key. /readyz never echoes the key.
- **m3 flag.** Every entry point was checked. QueryApi and Worker use the default (true), GraphEvidenceApi passes false, and LocalRun opens the object once for its shared backend.
- **SERVICES.md against the code.** Sections 2 to 5 match the code, except the graph-evidence-api workflow-slot count (N1).
  - The S3 grants match Audit.scala: logs/ and reports/ are written; runs/ manifests are not written by the services.
  - graph-evidence-api's payloadRef keys are under logs/rag-runtime (Audit.scala:31-32 and :109-115).

FINDINGS (ranked; all MINOR, no CRITICAL or MAJOR)
- **N1. MINOR, documentation versus code** (owner: service-engineer).
  - SERVICES.md:22, the Part A F1 table and Part A Assumption 1 all say graph-evidence-api has "1 slot, never used".
  - Composition.scala:357 builds it with `downstreams(cfg, c.threads, metrics)`, which sets `workflowThreads = max(1, 16/2) = 8` (Composition.scala:320-327).
  - No runtime effect: GraphEvidenceService submits no workflow, and threads start lazily. The documented size is wrong.
- **N2. MINOR, test gap** (owner: service-engineer).
  - No test covers the `answers = false` argument at main/GraphEvidenceApi.scala:14. The m3 test calls `Launcher.backend` and `Launcher.start` with `answers = false` itself.
  - If that argument were removed, all 43 services tests would still pass. A Phase 12 graph-evidence-api task without the curated/window-summary grant would then fail at startup (exit 1).
- **N3. MINOR, documentation** (owner: service-engineer; the behaviour is deferred by ruling 18).
  - A keyed sync POST that is refused with 503 OVERLOADED (`retryable: true`) has already claimed its Idempotency-Key (Services.scala:150, before the workflow submit at :162).
  - A retry with the same key gets 409 until the idempotency TTL.
  - Repair-01 makes this the normal outcome when every workflow slot is held. The Part A RISKS disclose it, but SERVICES.md section 3 (":51 … (retryable)") and the openapi.yaml Unavailable description do not.
- **N4. MINOR, worker redelivery** (owner: service-engineer).
  - On the no-workflow-slot branch (Worker.scala:157-159) the message stays invisible for the whole visibility timeout and counts a receive.
  - `handle` has no last-delivery rule for QUEUED jobs (Worker.scala:143). Repeated hits therefore move the message to the DLQ and leave the job QUEUED until its TTL.
  - With concurrency 1 this is reachable only through the getActiveCount transient; with concurrency > 1, through the disclosed race.
  - SERVICES.md:57 says only that "the message comes back after the visibility timeout".
- **N5. MINOR, evidence coverage** (owner: rag-engineer).
  - The order in `numbersIn` (Verifier.scala:571) has no disable check.
  - The combined patches D5, D6, D7 and D11 show only that one of their combined conditions is tested.
  - The test suites still fail with each row's patch, so no rule is shown to be untested.

OWNERSHIP CHECK (path, owner, author matches)
- Part A, service-engineer, modules/services/**. Author matches for all 12 files:
  - modules/services/SERVICES.md (new)
  - SaturationSpec.scala (new)
  - Resilience.scala, Services.scala, Worker.scala, Composition.scala, main/GraphEvidenceApi.scala
  - openapi.yaml (one description line)
  - BuildingBlocksSpec.scala, QueryApiSpec.scala, GraphEvidenceApiSpec.scala, CorpusQuerySpec.scala
- Part B, rag-engineer, modules/rag/**. Author matches:
  - modules/rag/RUNTIME.md
  - Verifier, Workflow, Synthesis and RuntimeApi.scala: patched one at a time and restored. The sha256 checks are OK, and the orchestrator's comparison shows the rag main sources unchanged since review 1.
- The orchestrator reports the tracked file set unchanged since review 1. project/Dependencies.scala differs only by an extraction artifact, and its mtime predates review 1.
- Nothing changed under build.sbt, project/, modules/core, modules/storage, prompts/ or tests/.
- docs/agent-runs/phase-10/repair-01.md is the orchestrator's allowed assembly.
- docs/agent-runs/phase-10/evidence/repair-01-logs/ and repair-01-scratchpad/ fall under the human architect's row. Neither part claims writing them. They are presumed human copies, as in ruling 22, and the human should confirm.
- modules/services/target/** is generated output and not authored.

FLOOR AND FENCE CHECK: none. Nothing under .claude/, CLAUDE.md, docs/agents.md, .mcp.json, hooks, permission rules, `check` or `markTestRun` changed.

PHASE 10 GATE ITEMS (after repair-01)
- (a) Routes embed no retrieval, verification or query logic: PASS. Route bodies are unchanged; only the probe key and the policy flag changed (Services.scala:42-49, :57-58, :116).
- (b) Every error response carries a traceId: PASS. The new OVERLOADED and INTERNAL_ERROR are ApiErrors rendered through Http.scala as ErrorBody. The m5 test and SaturationSpec use `expectError`, which checks the traceId.
- (c) Health and readiness expose no secrets: PASS. /readyz is unchanged: NOT_READY names downstreams only, and the probe key is never returned.
- (d) Examples match the contract tests: PASS. No example changed, openapi.yaml changed only one description, and ServicesContractSpec passes within services 43/0.
- (e) An end-to-end API test reaches an evidence-backed answer: PASS. LifecycleSpec is unchanged and passing. The repair-01 smoke shows SMOKE PASSED (smoke-1.log:53) on run run-d21e6d611181c34f6794f91d28416e1b, the same run as WO11 (smoke-server.log:10-11).

EVIDENCE GAPS
- I cannot hash the current files (no Bash). Services.scala was edited after the Part A manifests (a comment-only change, per the report). The delta's content agrees with that: line 55 now says "section 4".
- The original WO1 disable patches are unrecoverable. The rebuilt patches are judged on their own above.
- Live mode is still never constructed or run, so the F1 pools are proven only in fake mode with blocked ports.
- Whether .claude/.last-test-run is newer than every changed source is not observable by me. The logs show `markTestRun` wrote the marker in all six runs: Part A sbt-check-1..4 and Part B sbt-check-1..2.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, checked against the logs)
- Part A, runs 3 and 4 (final tree):
  - Console 665 passed / 0 failed.
  - Per project: core 185, entityResolution 111, hadoop 70, rag 102, services 43, sparkAnalytics 46, storage 108.
  - Each run wrote the marker: sbt-check-3.log:1238-1239 (5:38:31 PM) and sbt-check-4.log:1234-1235 (5:42:56 PM).
- Part B, runs 1 and 2: the same counts. Markers at part-b/sbt-check-1.log:1237 and part-b/sbt-check-2.log:1234.
- JUnit counts were not re-read; the supplied value is 665 tests / 0 failures / 0 errors in 61 suites.

VALIDATION RESULTS
- Part A: 8 of 8 disable checks fail their named tests, and every restore is OK (logs above). main-sha256-after-disable-2.txt shows 7 of 7 OK.
- Part B: 19 of 19 checks fail, and the final manifest check is 21 of 21 OK.
- Smoke passed.

ASSUMPTIONS
- The orchestrator's delta and its claim that the tracked set is unchanged are accurate. I spot-checked them against the current Resilience, Services, Worker and Composition files, which match the delta.
- The rag main sources are byte-identical to review 1 (Part B manifests plus the orchestrator's comparison).

RISKS
- The F1 behaviour under real Bedrock latency is unexercised until Phase 12.
- Ruling 18's deferred idempotency release (N3) and the orphaned-QUEUED path (N4) remain open.
- SaturationSpec and the BuildingBlocks F1 test depend on timing (about 200 ms of margin). They passed in every run.

MANUAL ACTION REQUIRED
- Route N1 to N4 to service-engineer and N5 to rag-engineer. They are minor and may be accepted as residual risks.
- Human: confirm the authorship of docs/agent-runs/phase-10/evidence/repair-01-logs/ and repair-01-scratchpad/.
- Phase 12 (ruling 23, infra-engineer):
  - SERVICES.md section 4 S3 grants, including s3:ListBucket for query-api and the worker;
  - no curated/window-summary/* grant for graph-evidence-api;
  - deploy query-api and the worker together.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate on this APPROVE; minors routed to their owners or accepted as residual risks.

VERDICT: APPROVE
````
