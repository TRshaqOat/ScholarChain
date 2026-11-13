# Phase 10: RESTful microservices (spec Phase 10)

Session plan: session 1 (2026-11-12, overnight) runs work orders 1 to 6 in order. Session 2 runs work orders 7 to 9 (FU2) and code review 1. Each work order report is appended verbatim to agent-report.md under "## Work order N: <agent>", and each review to review-report.md, wrapped in a ````text fence. One repair round; minors become residual risks.

## Human rulings before work order 1 (binding)

1. Scope (D-10). Three deployables built from modules/services: query-api, graph-evidence-api, and worker (an SQS consumer). No Step Functions, no cloud resources, no Terraform, and no Dockerfile or image build (no Docker on the build machine; Phases 12 and 13).
   - query-api: POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result, GET /v1/explain/{queryId}.
   - graph-evidence-api: GET /v1/evidence/{evidenceId}, GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors.
   - Every service: GET /healthz, GET /readyz, GET /metrics (JSON counters; API key required).
   - One OpenAPI document, modules/services/openapi.yaml, describes all routes.

2. Phase 9 carry-forward first (Phase 09 ruling 31). Work orders 1 and 2 fix the verifier and the synthesis limits before any service work; the services call the fixed runtime.

3. Ownership. service-engineer owns modules/services/** (REST models, routes, health checks, runtime composition). Runtime logic stays in modules/rag; store and queue adapters (DynamoDB, SQS, Neptune reads) in modules/storage; configuration keys and core records in modules/core; the build in build.sbt and project/ (build-engineer). Routes are thin: no retrieval, verification, query building, or store logic in a route. A change needed outside an agent's paths is BLOCKED-ON the owner, with the exact change; independent work orders continue.

4. HTTP stack and dependencies.
   - Default: the JDK's built-in HTTP server (com.sun.net.httpserver) with uPickle and a bounded thread pool sized by configuration. No new HTTP library.
   - SQS uses the AWS SDK v2 sqs artifact at exactly the version of the SDK artifacts already in the build.
   - Any other new dependency needs a written reason in the build-engineer report and a version pinned at least two weeks old (D-09). A framework (for example an HTTP or effect library) is BLOCKED-ON human.

5. Authentication (D-10 item 9).
   - One API key in the X-Api-Key header, compared in constant time with the value of an environment variable whose name comes from configuration (ECS injects it from Secrets Manager in Phase 12).
   - Missing or wrong key: 401. /healthz and /readyz need no key and reveal no configuration values, endpoints, versions of secrets, or key material.
   - 403 is reserved and unused in the single-key deployment; document it.
   - Keys never appear in logs, errors, or responses.

6. HTTP behavior.
   - JSON only; path versioning /v1.
   - Every response carries X-Request-Id (a valid client value is echoed; otherwise one is generated). Every error body follows the spec error model: a typed error code, message, traceId, and retryable flag.
   - Body size and question length limits come from configuration.
   - Status mapping: malformed JSON or invalid field 400; missing or wrong key 401; unknown query, job, node, or evidence ID 404 (validate the ID format first, for example IdFormat.isQueryId for /v1/explain, Phase 7 M6); idempotency conflict or incompatible job state 409; valid JSON with unsupported constraints or retrieval plan 422; per-instance rate limit on POST /v1/query 429; downstream failure 502; not ready or circuit open 503; deadline 504; unexpected 500 with traceId and no stack trace.

7. Query execution. POST /v1/query takes the spec body plus "execution": "sync" or "async" (default "sync"). Structured request fields keep overriding the model (Phase 9 ruling 4).
   - Idempotency-Key header: the same key with the same canonical body returns the stored response (the answer or the job reference); the same key with a different body is 409. Records expire by a TTL from configuration. Without the header, every POST is a new query.
   - Sync runs the Phase 9 workflow inline under a deadline from configuration (default 40000 ms; live answers took 10 to 33 s). A passed deadline returns 504.
   - Async writes a job record (QUEUED) holding the request, sends an SQS message holding only the job and query IDs, and returns 202 with jobId and statusUrl.
   - The worker long-polls, moves the job QUEUED to RUNNING to SUCCEEDED or FAILED with conditional writes (a redelivered message never runs a job twice), stores the result, and deletes the message only after the final state is written. The visibility timeout exceeds the workflow deadline. Poison messages go to a dead-letter queue after a maximum receive count (Phase 12 creates the queue; the worker reads both settings from configuration).
   - GET /v1/jobs/{jobId} returns state, stage, timestamps, error, and resultUrl. GET /v1/jobs/{jobId}/result returns the answer when SUCCEEDED, 409 while QUEUED or RUNNING, and the typed error when FAILED.

8. Explain, evidence, and graph routes.
   - GET /v1/explain/{queryId} returns the audit record: QueryPlan, stages with status and latency, query-template IDs, indexes, filters, prompt and model versions, evidence IDs, verification outcomes, counters, cache hits, and whether the LLM was live or stubbed. Never prompt text, retrieved text, raw model output, or credentials.
   - GET /v1/evidence/{evidenceId} returns the evidence item with provenance and bounded content (length cap from configuration).
   - Graph routes use only GraphReadPort templates. Neighbors: direction in, out, or both; depth 1 to 3; limit 1 to 200; edgeTypes from an allowlist; opaque cursor pagination. Anything else is 400.

9. Resilience. Every downstream port call has a timeout and at most the configured bounded retries (reads only, retryable errors only). A circuit breaker per downstream (consecutive-failure threshold and open interval from configuration) fails fast while open. LLM calls keep the Phase 9 throttle; the services add no LLM retries of their own.

10. Live LLM quota. Haiku's account quota is 10 requests per minute shared by every process. In the cloud only the worker and synchronous query-api requests call Haiku, and the deployment (Phase 12) caps them so the combined rate stays at or below 8 per minute. Experiment D's load test targets non-LLM endpoints or a stub LLM mode: the services offer a stub LLM mode chosen by configuration and reported in /v1/explain.

11. Stores. Job and idempotency records use the existing runtime tables and contracts where they fit (storage-engineer decides and documents: DynamoDB conditional writes, TTL attribute expiresAt). A new table needs a written reason, because it changes Phase 12. Local tests use file-backed or in-memory fakes. Live adapters are unit-tested on request building and response parsing only.

12. Observability. One JSON log line per request (traceId, requestId, route template, status, latency, error code) and per workflow stage. Never request bodies, questions, keys, or retrieved text. /metrics returns JSON: request counts by route and status, p50/p95/p99 latency, timeouts, LLM requests and tokens, verification status counts, abstentions, and queue receive counts.

13. Local run. A runMain starts all three services in fake mode on localhost, using the Phase 7 fake stores or small fixtures, for a smoke test. A live mode exists behind configuration and --profile for later; agents never start live mode.

14. Constraints (carried from Phase 9).
    - No agent reads, writes, or lists anything under labels/, scratch/stores-live/, or scratch/er/adjudication-live/.
    - No git, except the orchestrator's read-only git status and git diff for a review change set.
    - No aws commands and no AWS or Bedrock API calls. No network downloads, except sbt dependency resolution for artifacts build-engineer adds under ruling 4, and aws-docs MCP documentation lookups.
    - experiments/benchmark/ is read-only. Nothing under scratch/stores/, scratch/spark/, or scratch/hadoop/ is modified or deleted. New outputs go under scratch/runtime/ or scratch/services/ with run IDs.
    - Check free disk before long runs and stop below 5 GiB. Heap at most 6g.
    - When a guard blocks an edit, use the Edit tool; never work around a guard.
    - Every sbt run sets SPARK_LOCAL_IP=127.0.0.1 in the environment.
    - Logs a report cites are written by shell redirection under scratch/phase-10-logs/, with each path stated.

15. Reports. Every report covers files created, changed, and deleted; tests added; sbt check run twice with per-project test counts; any BLOCKED-ON; and the spec Phase 10 report items that apply (route table, status-code mapping, timeout policy, dependency graph, authentication method, exact test counts).

## Work order 1: rag-engineer (Phase 9 ruling 31 carry-forward)

Read docs/agent-runs/phase-09/prompt.md (rulings 29 to 31), review-report.md (Reviews 2 and 3), and repair-02.md. Edit only modules/rag/** and prompts/**.
1. Verifier fixes, each with the review's failure inputs as tests that fail when wrong and verify when correct:
   - R3-1: a list-position ordinal binds to the index k of an asserted hop.k.* or entry.k.* key (and similar list keys); an ordinal outside a ranking claim never fails as an unbound rank.
   - R3-2: number words inside a measure's own name or a fixed phrase ("on one paper", "a single paper", "once again", "twice as many") are not counts.
   - n4: the year exception never skips a rank check. n5: ordinals up to fiftieth are checked (rankings hold the top 50). n6: the measure vocabulary accepts "coauthors per paper" for the average and "joint publications" for intensity, as the synthesis prompt defines them.
   - n7: the audit records each removed claim's text and failing checks, and each dropped line with its reason (audit only, never logs).
   - Review 2 n1, n2, n3, and the stale default-version lines in modules/rag/RUNTIME.md (255, 406, 418) and Records.scala:398.
2. Ranking claims keep requiring rank.k.researcherId: synthesis-output-v3 requires it whenever any rank.k.* key is present, so validation fails and the retry names the field. synthesis-v3 also tells the model to omit any claim it cannot cite with at least one evidence ID and to keep claims concise. Record the versions in RUNTIME.md.
3. Report the values work order 2 must set: the synthesis prompt default, runtime.synthesis.max-output-tokens (the live run cut 4 questions off at 2000), and the per-question output budget, with the arithmetic from the stated per-question maximum.
4. Disable each new rule temporarily, record the failing test names with logs, and restore.
5. A service-facing entry point, if none exists: a plain Scala API (no HTTP) that answers one structured request (the POST /v1/query fields) under a deadline with given ports, and returns the answer, audit record, and evidence bundle; plus the lookups the services need (audit by query ID, evidence item by evidence ID) where those reads belong to rag. Report the exact signatures for work orders 5 and 6.
6. Bump the verifier version (the run ID changes) and rerun the fake benchmark on all 30 questions as a new run; report it next to run-c5befe8f45411f497ea0db779978e564, with every claim whose status changed and why.
Run sbt check twice.

## Work order 2: domain-engineer (configuration and core)

Read the work order 1 report. Edit only modules/core/**.
1. Set the synthesis prompt default, runtime.synthesis.max-output-tokens, and the per-question output budget to the work order 1 values; update tests that pin them.
2. Service configuration keys (name, type, default, reason): ports per service; thread pool sizes; body and question limits; sync deadline (40000 ms); idempotency and job TTLs; the POST /v1/query rate limit; circuit-breaker threshold and open interval; retry counts and backoff; per-downstream timeouts; the evidence content cap; the name of the API key environment variable (never the key); the SQS queue URL and dead-letter settings as documented environment overrides; visibility timeout; worker concurrency and poll wait; the stub-LLM mode flag; metrics on or off.
3. Core records and IDs the services need, if any (for example a jobId format with StableIds and IdFormat, a JobState enum, a shared error-code enum). Keep config-version unless the work order 2 rule of Phase 9 requires a bump, and say why.
Report every new key and every downstream reference, without editing them. Run sbt check twice.

## Work order 3: build-engineer (services build, SQS, packaging, FU3)

Read the work order 1 and 2 reports. Edit only build.sbt, project/**, .scalafmt.conf, and packaging files.
1. Report the current state of modules/services in the build.
2. modules/services depends on core, storage, and rag. Add the AWS SDK v2 sqs artifact to storage at the existing SDK version (ruling 4).
3. Three entry points (query-api, graph-evidence-api, worker) packaged from modules/services, with prompts/ shipped at the path the runtime reads (FU3). If the runtime must read prompts from the classpath instead, report BLOCKED-ON rag-engineer with the exact change. Document the local run commands. No Docker build.
4. sbt check covers the services tests.
Run sbt check twice.

## Work order 4: storage-engineer (job, idempotency, queue, and read ports)

Read the work order 1 to 3 reports. Edit only modules/storage/**.
1. Job and idempotency stores (ruling 11): ports, file-backed fakes, and live DynamoDB adapters with conditional writes and TTL.
2. Read ports the services need that are storage reads: audit record by query ID and evidence item by evidence ID (QuerySession and EvidenceBundle), if work order 1 assigns them to storage; a graph node lookup template if GraphReadPort lacks one (fixed, bound parameters, limits); deterministic ordering for neighbor pagination.
3. A queue port (send, long-poll receive, delete, change visibility) with an in-memory fake that models visibility timeouts and receive counts, and a live SQS adapter using the refreshing credentials provider.
4. STORAGE.md; tests with no network.
Run sbt check twice.

## Work order 5: service-engineer (APIs)

Read the work order 1 to 4 reports, spec Phase 10, and the spec sections REST API contract, Error model, Configuration, Observability requirements, and Security requirements. Edit only modules/services/**.
1. REST models (uPickle), routes, error mapping, authentication, request and trace IDs, limits, rate limiting, idempotency, sync execution, explain, evidence, graph node and neighbors, health and readiness, metrics, timeouts and circuit breakers, and composition from configuration (fake or live ports), per rulings 1 to 13.
2. openapi.yaml with request and response examples; contract tests parse every example against the models.
3. Tests: successful sync query; invalid JSON; unsupported constraint (422); missing evidence (404); idempotent repeat and idempotency conflict; timeout and downstream failure; unauthorized; health versus readiness; neighbor bounds; explain without hidden content; no key or secret in any log line or response. At least one test goes over a real localhost socket.
Run sbt check twice. Report the route table, status-code mapping, timeout policy, dependency graph, authentication method, and exact test counts.

## Work order 6: service-engineer (worker, async path, lifecycle)

Read the work order 5 report.
1. The worker service, the async POST path, the jobs API and result endpoint, redelivery and poison-message handling (ruling 7).
2. A full query-to-evidence lifecycle test with fakes: POST (sync and async), an answer with at least one VERIFIED claim from bounded fixtures, GET evidence for a cited evidence ID, and GET explain.
3. The local runner (ruling 13). Start it in fake mode on localhost, run a short smoke against it (curl or a test client), save the output under scratch/phase-10-logs/, and stop it.
Run sbt check twice. Report the final route table and the Phase 10 report items.

## Work order 7: spark-engineer (FU2 window summaries; session 2)

Read experiments/benchmark/benchmark-questions-v1.csv (read-only) for B13, B15, B17, and B24, and modules/spark/JOBS.md. Write corpus-level window summaries (per window: node and edge counts, component count, largest component size and share, community count, largest community, and any other value those questions need) from the Phase 6 outputs as a new, separate run (never a new conversion run; nothing existing modified). Document the contract in JOBS.md. Run sbt check twice.

## Work order 8: storage-engineer (FU2 read port; session 2)

Read the work order 7 report. A read port and fake for the window summaries, following an existing store pattern, documented in STORAGE.md. Run sbt check twice.

## Work order 9: rag-engineer (FU2 answers; session 2)

Read the work order 7 and 8 reports. Plan operations and evidence for the corpus-level questions, so B13, B15, B17, and B24 answer with verified claims. Rerun the fake benchmark as a new run and report it next to the work order 1 run. Run sbt check twice.

## Code review 1: code-reviewer (session 2)

Review the Phase 10 change set against work orders 1 to 9, rulings 1 to 15, spec Phase 10 (work order and gate), the REST API contract, the Error model, and D-10. Check especially: routes embed no retrieval, verification, or query logic; every error response carries a traceId; health and readiness expose no secrets or configuration; request and response examples match the contract tests; at least one end-to-end API test reaches an evidence-backed answer from bounded fixtures; authentication is constant-time and keys never reach logs; idempotency and job state transitions are safe under redelivery; graph exploration is bounded; the Phase 9 ruling 31 verifier fixes resolve R3-1, R3-2, and n1 to n7 with tests that fail without them; no edits outside owned paths, and no reads of labels/, scratch/stores-live/, or scratch/er/adjudication-live/. Verify each candidate finding against the files. Give critical and major findings in full (severity, file and line, failure input, impact, owner) and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings after session 1

16. Session 1 assumptions accepted as design (all six work orders COMPLETE, sbt check 637/0 twice, no BLOCKED-ON):
    - WO4: the new DynamoDB table ServiceState (job and idempotency records, TTL expiresAt) and the GSI evidenceById on EvidenceBundle. Both are Phase 12 Terraform items.
    - WO4: conditional writes and SQS calls are never retried; reads use services.resilience.
    - WO5: query IDs minted by the service with a random nonce; malformed IDs answer 404 without a store read; REQUEST_TOO_LARGE is 400; async POSTs and replays count against the rate limit.
    - WO6: the stuck-RUNNING policy (FAILED DEADLINE_EXCEEDED on the last delivery); poison messages and messages without a job are left to the redrive policy; a failed send fails the job at once (502); resultRef is not written.
    - WO2: services.stub-llm defaults to true; the worker uses runtime.question.deadline-ms.
    - WO6: the local smoke used test fixtures, not the Phase 7 stores (heap limit).
17. Work order 9 (rag-engineer) also fixes the stale text in modules/rag/RUNTIME.md section 20 (about lines 430, 431 and 455), so it states the current defaults: synthesis-v3, runtime.synthesis.max-output-tokens 4000, runtime.budget.max-output-tokens-per-question 9600.
18. Deferred, not Phase 10 work:
    - A credentials provider for ECS task roles in live mode (storage-engineer, before cloud window 2, with the M10 remainder).
    - Docker entrypoint and the per-task commands bin/query-api, bin/graph-evidence-api and bin/worker; ECS stopTimeout of at least 25 s (Phases 12+13).
    - An idempotency release for failed requests and a traceId on JobRecord: residual risks, optional later.
    - The constant-time key comparison cannot be checked by a functional test (WO5 D3); code review 1 checks it by reading the code.

## Human rulings after the live benchmark (work orders 1 and 2)

19. Live benchmark after work orders 1 and 2 (benchmark run-149a1e0a5d493732e32257518878039e, runtime run-d94e077cc52fa2fbfa628f21a4c4adaa; copies in docs/agent-runs/phase-10/evidence/live-benchmark-run/, with live-llm-failures.txt and live-claim-rules.txt): plan source MODEL 30; ANSWERED 22, NO_VERIFIED_CLAIM 1, BUDGET_EXHAUSTED 1, UNSUPPORTED_REQUEST 4, AMBIGUOUS 1, UNKNOWN 1; 72 verified, 12 unsupported, 1 conflicting; 57 Haiku requests. Work order 9 (rag-engineer) also does:
    a. B29 ended BUDGET_EXHAUSTED because both synthesis attempts stopped with stop reason max_tokens at 4000 output tokens (about 10,300 characters each). When a synthesis attempt stops with max_tokens, the retry message must say the output was cut off and ask for at most half of runtime.synthesis.max-claims claims, one sentence each. Do not raise any token limit or budget. Add a test that fails without the change. Record any prompt or version change in RUNTIME.md.
    b. Read the 13 removed claims in the audits of runtime run-d94e077cc52fa2fbfa628f21a4c4adaa under scratch/runtime/s3/logs/rag-runtime/ (failing rules: numbers-in-text 10, identity 2, context-only 1, window CONFLICT 1). Classify each as removed by design (the claim is not backed by its evidence) or as a verifier defect. Fix clear defects, each with a test built from the failure shape using fixture names (never copy model text into tests or logs). Report anything uncertain as a residual risk. Report a table: question, claim kind, failing rule, by design or defect, fixed or not.
    c. No live calls. The human reruns the live benchmark at the gate.
    Residual, no action in Phase 10: the planner misplans B15, B18, B23 and B24 (as in Phase 9); one live question took 45,712 ms, above the 40,000 ms sync deadline (the async path is the remedy).

## Human rulings after session 2a

20. Session 2a accepted (work orders 7 to 9 COMPLETE; sbt check 656/0 in JUnit XML twice; fake benchmark run-396fa84df47be7a2579b491bec64356c passes every check on 30 of 30, 91 verified, 0 unsupported):
    - WO7: the window-summary run lives under scratch/runtime/wo7/spark (not scratch/spark), as ruling 14 directs.
    - WO8: the window summaries are one S3 object under curated/window-summary/, read through WindowSummaryReadPort; no new table or GSI. The loader builds rows from the Spark _manifest.json, not the Parquet file; accepted as a marked shortcut (it refuses on any schema, count, or violation mismatch). A direct store-object writer is optional later work for spark-engineer.
    - WO9: the ruling 19b classification (2 defects fixed, 11 removals by design), the ruling 19a cut-off retry, verifier-v5, and rag-runtime-v3.
    - Phase 12 carry-forward: copy the window-summary object to the data bucket (LoadS3Cli) and grant s3:GetObject on curated/window-summary/* to every role that runs the rag runtime (query-api, worker).
21. Session 2c runs work orders 10 and 11 (the follow-ups the work order 9 report names). Code review 1 then reviews the change set of work orders 1 to 11 against rulings 1 to 21. In addition to the checks in its text, it checks: the verifier-v5 fixes (digit groups, comparison sides) and the ruling 19a retry; the corpus evidence path for B13, B15, B17, and B24 and its uncertainty lines; the WO8 manifest-based conversion (column mapping and refusal on mismatch); the services test-count explanation from work order 11; and the constant-time key comparison, by reading the code.

## Work order 10: domain-engineer (window-summary key; session 2c)

Read the work order 8 and 9 reports. Edit only modules/core/**.
1. Add runtime.window-summary.load-run: String, default "" (no window summaries served, so corpus questions abstain), environment override SCHOLARCHAIN_WINDOW_SUMMARY_RUN. When not empty it must be run-<32 lower-case hex>.
2. Validation and tests: the default, the override, and rejected values. config-version stays config-v1 (a key added with a default).
Run sbt check twice.

## Work order 11: service-engineer (window-summary wiring; session 2c)

Read the work order 8, 9, and 10 reports. Edit only modules/services/**.
1. Wire WindowSummaryReadPort in Composition.scala as the work order 9 report describes. The key is S3Layout.windowSummary(<snapshot the composition serves>, runtime.window-summary.load-run). Live mode opens it with the S3 objects reader. Fake mode takes a new optional argument --window-summary-root <root> and opens it with a FileObjectStore reader on that root. If fake mode has no snapshot, add the smallest argument needed and record it as an assumption. Pass the WindowSummarySource as the RuntimePorts argument and add window-summary=<key> to the run ID inputs. An empty key leaves the port unset (corpus questions abstain as now). A Left from WindowSummaries.open stops startup with a message that names the key, never file contents. LocalRun passes the argument through.
2. Tests: a corpus question of the B13 shape (components in 2000-2014) answered over a real localhost socket with VERIFIED claims from a fixture window summary; the empty-key case abstains; a bad object stops startup. Update openapi.yaml and its examples only if a response shape changes; contract tests must pass.
3. Find why services shows 34 tests on the sbt console but 35 in the JUnit XML since work order 8. Fix it if it is a test defect; otherwise explain it with evidence.
4. Rerun the work order 6 local smoke (fake mode, 127.0.0.1, the same fixture stores) with --window-summary-root scratch/runtime/wo8/s3 and SCHOLARCHAIN_WINDOW_SUMMARY_RUN=run-6fbad498beb0d8f570df67665fb97544, adding one sync POST of the B13-shaped question. Save the output under scratch/phase-10-logs/wo11/ and stop the server.
Run sbt check twice.

## Human rulings after code review 1

22. Code review 1 (REQUEST_CHANGES: F1 major; m1 to m5 minor) is accepted in full. Every finding is fixed in repair-01, the one repair round. The untracked files under docs/agent-runs/phase-10/evidence/ were copied there by the human from Terminal; no agent wrote them. The report-claim discrepancies the review lists (the WO5 "503 OVERLOADED" risk, WO11 item 4, the WO10 STORAGE.md note) stand corrected by the review; the reports stay verbatim, and the repair-01 Part A report states the corrected behaviour.
23. Phase 12 carry-forward (infra-engineer): the IAM and environment needs that repair-01 documents in modules/services/SERVICES.md for m1 (the readiness probe and reads of missing keys), m2 (query-api and worker must serve the same runtime run ID, so they are deployed together), and m3 (window-summary access only for the tasks that open it).

## Repair 1 (repair-01)

One repair round with two parts, run in order in one session: Part A, then Part B. Before each part, check docs/agent-runs/phase-10/repair-01.md for that part's heading; if present, skip the part. Append each report verbatim to docs/agent-runs/phase-10/repair-01.md under "## Part A: service-engineer" or "## Part B: rag-engineer", wrapped in a ````text fence. Ruling 14 constraints apply. Cited logs go under scratch/phase-10-logs/repair-01/. Each part runs sbt check twice.

### Part A: service-engineer (F1, m1, m2, m3, m5)

Read code review 1 in docs/agent-runs/phase-10/review-report.md and the work order 5, 6, and 11 reports. Edit only modules/services/**.
1. F1. Separate the slow path from the fast path, so that long-running workflows cannot make store calls or readiness probes time out and open breakers against healthy stores. At least: the sync workflow runs on its own bounded pool, separate from store calls and readiness probes; and time a call spends waiting to start does not count as a downstream failure (a call that cannot start in time answers 503 OVERLOADED and touches no breaker). Apply the same rule to the worker if it shares a pool the same way. Add no configuration key: size the pools from existing keys and document the choice. Tests: with every workflow slot held by blocked workflows, GET /v1/jobs/{jobId}, GET /v1/explain/{queryId}, and /readyz still answer within their timeouts and no breaker opens; a call that cannot start gives 503 OVERLOADED. The tests must fail without the fix (a logged disable check, restore verified by sha256).
2. m1. Move the S3 readiness probe key under a prefix the services already read. Write modules/services/SERVICES.md and state there the S3 permissions each service needs so that a missing key reads as not found (s3:ListBucket on the bucket), for readiness and for unknown query IDs.
3. m2. Document in SERVICES.md that query-api and worker must compute the same runtime run ID (same configuration, prompt versions, window-summary run, and store runs) and must be deployed together. Each service logs its runtime run ID at startup (keep the existing line if it already does).
4. m3. graph-evidence-api does not open the window-summary object (it never answers questions); only query-api and worker open it. Add a test. Document in SERVICES.md which task needs which S3 access.
5. m5. An IllegalArgumentException thrown inside a downstream call (not by route validation) answers 500 INTERNAL_ERROR with a traceId and no internal detail, not 400. Add a test.
6. Rerun the work order 11 local smoke once (fake mode, 127.0.0.1, the same inputs) and save it under scratch/phase-10-logs/repair-01/. Stop the server afterwards.
Run sbt check twice.

### Part B: rag-engineer (m4; verifier evidence on the final tree)

Read code review 1 and the work order 1 and 9 reports. Edit only modules/rag/**.
1. m4. Fix modules/rag/RUNTIME.md (about lines 403 and 482, and any other stale line) so it says runtime.window-summary.load-run exists (work order 10) and how the services use it (work order 11).
2. On the final tree (verifier-v5), rerun the work order 1 disable checks D1 to D15 and the work order 9 checks D1 to D4 in the same way: one rule disabled at a time, a named test must fail, restore verified by sha256. Report a table (check, failing test, log). Any check that no longer fails a test is a finding: strengthen the test until it fails, as work order 1 did for D6.
Run sbt check twice.

## Code review 2: code-reviewer

Review the repair-01 changes (the files Parts A and B name in repair-01.md) against code review 1's F1 and m1 to m5, rulings 22 and 23, and the Part B evidence table. For each finding: resolved or not, with file and line and the test that fails without the fix. Check that repair-01 edited nothing outside its owned paths and broke no Phase 10 gate item (routes embed no retrieval, verification, or query logic; every error response carries a traceId; health and readiness expose no secrets; examples match the contract tests; an end-to-end API test reaches an evidence-backed answer). Verify each candidate finding against the files. Give critical and major findings in full (severity, file and line, failure input, impact, owner) and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings after code review 2

24. Code review 2 returned APPROVE (no critical or major findings; minors N1 to N5). F1 and m1 to m5 are resolved, so Phase 10 exits after its one repair round with no escalation. N1 to N5 are residual risks carried to Phase 11: N1 (SERVICES.md:22 states 1 graph-evidence-api workflow slot; the code creates 8, never used), N2 (no test that main/GraphEvidenceApi passes answers = false), N3 (an OVERLOADED sync POST keeps its Idempotency-Key claimed until the TTL; not yet in SERVICES.md or openapi.yaml; the release is deferred by ruling 18), N4 (a worker with no free slot keeps the message, so repeated misses can send it to the DLQ while the job stays QUEUED), N5 (no separate disable check for the word-before-year order in Verifier numbersIn). The files under docs/agent-runs/phase-10/evidence/ (including repair-01-logs, repair-01-scratchpad, code-review-1, and code-review-2) were copied there by the human from Terminal; no agent wrote them.

## Human rulings at the gate

25. Gate sbt checks and final live benchmark. Human check 1 (evidence/human-sbt-check-1.txt) failed one test: SaturationSpec "query-api: with every workflow slot held, jobs, explain, and readiness still answer" decoded an error body where it expected a sync answer (missing keys queryId, mode, answer, claims, links). It started seconds after the live benchmark JVM exited. With no code change, the suite then passed 5 of 5 alone (evidence/saturation-rerun-1..5.txt) and in full checks 2 and 3 (665 tests, 0 failed each). It is recorded as a timing-dependent test (repair-01 Part A disclosed its 200 ms margins). Checks 2 and 3 are the gate checks. Carried to Phase 11: make SaturationSpec deterministic (latches instead of wall-clock margins). Final live benchmark (run-052145faed8cbaed7ae874e3fbf36eae, runtime run-422d912ac162f340f5f31a1b63b65e19, with the window-summary flags): plan source MODEL 30; ANSWERED 24, NO_VERIFIED_CLAIM 3 (B15 misplan; B17 and B24 claims removed), UNSUPPORTED_REQUEST 1, AMBIGUOUS 1, UNKNOWN 1; 85 verified, 15 unsupported, 0 conflicting; failing rules numbers-in-text 13, identity 2, context-only 1; 60 Haiku requests (219,940 in, 39,551 out); 3 INVALID_OUTPUT attempts, each recovered by its retry (one max_tokens cut-off recovered by the ruling 19a retry); max question latency 40,149 ms. Carried to Phase 11: classify the 15 removed live claims (by design or verifier defect), including B17 and B24.
