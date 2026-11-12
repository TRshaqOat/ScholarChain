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
