# Phase 10 human gate: RESTful microservices (spec Phase 10)

Date: 2026-11-12
Human architect: Achyut Patel

## What was reviewed
- Agent reports for work orders 1 to 11 (agent-report.md) and repair-01 Parts A and B (repair-01.md).
- Code review 1: REQUEST_CHANGES (F1 major; m1 to m5 minor). Code review 2: APPROVE (no critical or major; minors N1 to N5) (review-report.md).
- Human sbt check (ruling 25): check 1 failed one timing-dependent test (SaturationSpec, a flake under load); the suite then passed 5 of 5 alone, and checks 2 and 3 passed: core 185, rag 102, storage 108, entityResolution 111, sparkAnalytics 46, hadoop 70, services 43; 665 tests, 0 failed (evidence/human-sbt-check-1.txt, -2.txt, -3.txt; saturation-rerun-1..5.txt).
- Phase 9 carry-forward (Phase 09 ruling 31): R3-1, R3-2, and n1 to n7 resolved in WO1 (verifier-v4), each with a test that fails without the fix; all 19 disable checks rerun on the final verifier-v5 tree (repair-01 Part B). Synthesis-v3 with rank.k.researcherId; synthesis limits 4000 and 9600 tokens.
- Fake benchmark: run-396fa84df47be7a2579b491bec64356c, 27 answered, 91 verified, 28 partial, 0 unsupported, 0 conflicting, every check passes on 30 of 30.
- Live benchmark after WO1 and WO2 (ruling 19): run-149a1e0a5d493732e32257518878039e, ANSWERED 22, 72 verified, 12 unsupported, 1 conflicting (Phase 9: 15 answered; 30 verified, 40 unsupported, 3 conflicting).
- Live benchmark on the final tree (ruling 25): run-052145faed8cbaed7ae874e3fbf36eae (runtime run-422d912ac162f340f5f31a1b63b65e19), ANSWERED 24, 85 verified, 15 unsupported, 0 conflicting, no BUDGET_EXHAUSTED, 60 Haiku requests (evidence/gate-live-*).
- FU2: window-summary Spark run run-1de2293597c338227290eb382de16957, load run run-6fbad498beb0d8f570df67665fb97544. B13, B15, B17, and B24 answer with verified claims in the fake benchmark; live, B13 answers, B15 is misplanned, and the B17 and B24 claims were removed (Phase 11).
- Local services smoke (fake mode, 127.0.0.1): PASSED in WO6 (twice), WO11 (with the B13 corpus question), and repair-01 Part A (evidence/session-1-logs/wo6, session-2c-logs/wo11, repair-01-logs).

## Phase 10 gate (spec)
- [x] Routes do not embed retrieval algorithms.
- [x] Error responses include trace IDs.
- [x] Health checks do not expose secrets.
- [x] Request and response examples match contract tests (29 examples round-trip; documented routes equal the route tables).
- [x] At least one end-to-end API test reaches an evidence-backed answer using bounded fixtures (LifecycleSpec sync and async; CorpusQuerySpec).
- [x] Code review returns APPROVE (code review 2).

## Results
- Route table: query-api (8080) POST /v1/query (sync and async), GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result, GET /v1/explain/{queryId}; graph-evidence-api (8081) GET /v1/evidence/{evidenceId}, GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors; all three including worker (8082) /healthz and /readyz (no key) and /metrics (key).
- Status codes: 200, 202, 400, 401, 403 reserved, 404, 409, 422, 429, 502, 503, 504, 500; every error body has error, message, traceId, requestId, retryable.
- Timeouts: sync deadline 40,000 ms (504); per-downstream timeouts; reads-only retries; one breaker per downstream; workflows on their own no-queue pool, and a call that cannot start is 503 OVERLOADED with no breaker (repair-01 F1).
- Authentication: one X-Api-Key, SHA-256 plus MessageDigest.isEqual, key from the environment variable services.api-key-env names (Secrets Manager in Phase 12).
- Async: job record in the ServiceState table, SQS message with IDs only, worker with conditional transitions, DLQ after 3 receives, stuck-RUNNING policy at the last delivery.
- Dependency graph: services depends on core, rag, and storage; one new library (AWS SDK sqs 2.41.32).
- Test counts: 665 (core 185, rag 102, storage 108, entityResolution 111, sparkAnalytics 46, hadoop 70, services 43).

## Human rulings recorded at the gate
- Rulings 1 to 25 in prompt.md.
- No work order ended BLOCKED-ON. Session 2c (WO10, WO11) was added by ruling 21 to wire the WO9 follow-ups before review.

## Decision
ACCEPTED after one repair round (code review 1 REQUEST_CHANGES, repair-01, code review 2 APPROVE).

## Manual changes
- No manual edits to agent-owned files.
- Per-file provenance: modules/services (service-engineer, WO5, WO6, WO11, repair-01 A), modules/rag and prompts/ (rag-engineer, WO1, WO9, repair-01 B), modules/core (domain-engineer, WO2, WO10), build.sbt and project/ (build-engineer, WO3), modules/storage (storage-engineer, WO4, WO8), modules/spark (spark-engineer, WO7). All agent-generated, accepted after review.
- Human-authored: prompt.md rulings, this gate, evidence copies (including scratchpad scripts and both review change sets), live benchmark runs.
- External assistance: none.
- Process notes: guard false positives (WO2 secret-env-dump, WO9 secret-keys and protected-path-write, repair-01 A secret-keys), each redone with the Edit tool; WO11 deleted a stale generated JUnit file (copy kept); repair-01 Part B rebuilt the WO1 disable patches (originals lost with the scratchpad); human check 1 flake (ruling 25).

## Remaining risks
- Live Neptune, DynamoDB, S3 Vectors, SQS, and the services' live mode are untested until cloud window 2; the services ran only in fake mode locally.
- Haiku quota (10/min account-wide) caps live concurrency; experiment D uses non-LLM endpoints or the stub LLM mode.
- Phase 11: SaturationSpec timing flake (ruling 25); review 2 minors N1 to N5 (ruling 24); classify the 15 removed live claims, including B17 and B24 (ruling 25); planner misplans B15, B18, B23, B24.
- The sync deadline (40 s) is close to the slowest live question (40.1 s); the async path is the remedy.
- WO8 loader reads the Spark manifest, not the Parquet file (ruling 20); GSI evidenceById is eventually consistent; the idempotency release and an ECS credentials provider are deferred (ruling 18).
- Phase 12 carry-forward: rulings 18, 20, and 23 (ServiceState table, evidenceById GSI, SQS queue and DLQ, per-task S3 grants including s3:ListBucket, window-summary object and variable, deploy query-api and worker together, task commands).
- Phase 9 residuals still open: adjudication low-confidence records omit the model's answer.

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
