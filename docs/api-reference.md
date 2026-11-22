# ScholarChain REST API reference

Owner: documentation-engineer. Taken from the route code in [`modules/services/src/main/scala/scholarchain/services/`](../modules/services/src/main/scala/scholarchain/services/) (`Services.scala`, `Models.scala`, `Http.scala`), the error codes in [`modules/core/src/main/scala/scholarchain/core/Enums.scala`](../modules/core/src/main/scala/scholarchain/core/Enums.scala) (`ErrorCode`), and the examples under [`modules/services/openapi/examples/`](../modules/services/openapi/examples/). The machine-readable contract is [`modules/services/openapi.yaml`](../modules/services/openapi.yaml); `ServicesContractSpec` parses every example against the Scala models. Where this file and the code disagree, the code is right.

The example files were produced by the fake-mode test fixtures (three fictional researchers), except the hand-written query-request examples (`openapi.yaml` info text). They are not results from the deployed corpus. Results from the deployed window 3 runtime are in [`operations/releases/smoke-20261119T235713Z/`](../operations/releases/smoke-20261119T235713Z/); that runtime was destroyed on Nov 20, 2026.

## Services and routes

| Service (port) | Method | Path | Key needed |
|---|---|---|---|
| query-api (8080) | POST | `/v1/query` | yes |
| query-api | GET | `/v1/jobs/{jobId}` | yes |
| query-api | GET | `/v1/jobs/{jobId}/result` | yes |
| query-api | GET | `/v1/explain/{queryId}` | yes |
| graph-evidence-api (8081) | GET | `/v1/evidence/{evidenceId}` | yes |
| graph-evidence-api | GET | `/v1/graph/nodes/{nodeId}` | yes |
| graph-evidence-api | GET | `/v1/graph/nodes/{nodeId}/neighbors` | yes |
| every service, worker (8082) included | GET | `/healthz` | **no** (public) |
| every service | GET | `/readyz` | **no** (public) |
| every service | GET | `/metrics` | yes (present when `services.metrics-enabled` is true; not routed by the ALB) |

Ports are `services.query-api.port`, `services.graph-evidence-api.port` and `services.worker.port` in [`scholarchain.conf`](../modules/core/src/main/resources/scholarchain.conf). Behind the ALB the paths above are routed by prefix (see [`infra/terraform/README.md`](../infra/terraform/README.md) "ECS and the ALB").

## Conventions (all routes)

- **Authentication.** Header `X-Api-Key`, compared in constant time with the value of the environment variable that `services.api-key-env` names (`SCHOLARCHAIN_API_KEY`, injected by ECS from Secrets Manager). Missing or wrong key: 401 `UNAUTHORIZED`. Only `/healthz` and `/readyz` are public. `FORBIDDEN` (403) is reserved and unused in the single-key deployment.
- **Idempotency.** `POST /v1/query` accepts `Idempotency-Key` (1 to 255 visible ASCII characters; stored hashed; expires after `services.ttl.idempotency-hours`, 24). The same key and canonical body replays the stored response with header `Idempotent-Replay: true`. The same key with a different body is 409 `IDEMPOTENCY_CONFLICT`. Without a key every POST is a new query. After a 503 `OVERLOADED` on a sync POST, retry with a new key ([`modules/services/SERVICES.md`](../modules/services/SERVICES.md) section 3).
- **Request and trace IDs.** `X-Request-Id` and `X-Trace-Id` are echoed when the client sends 1 to 128 characters of `[A-Za-z0-9._:-]`; otherwise the service generates `req-<32 hex>` and `trace-<32 hex>`. Both are on every response and in every error body.
- **Content type.** A POST body must be `Content-Type: application/json`, at most `services.max-body-bytes` (65,536) bytes. Responses are `application/json; charset=utf-8` with `Cache-Control: no-store`.
- **Query parameters.** Only the neighbors route takes query parameters; any other route answers 400 `INVALID_FIELD` when one is sent.
- **Malformed IDs** answer the route's 404 code without a store read.
- **Optional response fields** are always present, `null` when absent.
- **Rate limit.** `POST /v1/query` (sync and async) is limited per instance by `services.query-api.query-rate-per-minute` (10); beyond it 429 `RATE_LIMITED` with `Retry-After: 60`.

## POST /v1/query

Answers a collaboration question now (`"execution": "sync"`, the default) or as a job (`"async"`).

Request body (`QueryBody`):

| Field | Type | Notes |
|---|---|---|
| `question` | string, required | 1 to `runtime.question.max-chars` (2,000) characters after NFC normalization; no control characters but tab and newline |
| `constraints` | object or null | `yearFrom`, `yearTo` (integers; `yearFrom` after `yearTo` is 400), `topics`, `institutions`, `venues` (arrays of strings; at most 5 terms of at most 200 characters each, else 422) |
| `analysis` | object or null | `rankingMetric` (one of `PUBLICATION_COUNT`, `DISTINCT_COAUTHOR_COUNT`, `MAXIMUM_COAUTHORS_ON_ONE_PAPER`, `CROSS_COMMUNITY_DEGREE`, `COMMUNITIES_CONNECTED`, `PARTICIPATION_COEFFICIENT`, `CORENESS`; other values 422), `includeTeamSize`, `includeBridgeMetrics`, `includeCommunities`, `includePaths` (booleans) |
| `retrieval` | object or null | `graphLimit`, `vectorLimit`, `maxPathDepth` (integers, at least 1; above `retrieval.graph-limit` 100, `vector-limit` 50, `max-path-depth` 6 is 422) |
| `output` | object | `includeEvidence`, `includeScores`, `includeUncertainty` (booleans, default true); accepted for the spec contract, the answer always carries its evidence IDs |
| `execution` | string | `sync` (default) or `async` |

An unknown top-level or `output` field is 400 `INVALID_FIELD`; an unknown `constraints` or `analysis` field is 422 `UNSUPPORTED_CONSTRAINT`; an unknown `retrieval` field is 422 `UNSUPPORTED_RETRIEVAL_MODE`. Structured fields override the planner (Phase 9 ruling 4).

Examples: [`query-request.json`](../modules/services/openapi/examples/query-request.json) (the spec request), [`query-request-minimal.json`](../modules/services/openapi/examples/query-request-minimal.json), [`query-request-async.json`](../modules/services/openapi/examples/query-request-async.json).

Response 200, sync (`SyncQueryResponse`), example [`query-response-sync.json`](../modules/services/openapi/examples/query-response-sync.json):

| Field | Content |
|---|---|
| `queryId` | `qry-<32 hex>` |
| `mode` | `synchronous` (`asynchronous` on `GET /v1/jobs/{jobId}/result`) |
| `answer` | `summary`, `abstained`, `abstention` (reason and detail), `uncertainty`, `window` (requested and resolved window), `resolutions` (name, decision, researcherId, topShare, candidateCount), `verification` (verified, partiallySupported, unsupported, conflicting), `answerHash` |
| `claims` | core `AnswerClaim` records: `claimId`, `text`, `evidenceIds`, `verificationStatus`, `uncertainty`, `provenance` |
| `links` | `explain` (`/v1/explain/<queryId>`) and `evidence` (one `/v1/evidence/<id>` per cited evidence ID) |

Response 202, async (`AsyncQueryResponse`), example [`query-response-async.json`](../modules/services/openapi/examples/query-response-async.json): `jobId` (`job-<32 hex>`), `queryId`, `mode` (`asynchronous`), `state`, `statusUrl`. If the queue message cannot be sent, the job is moved to FAILED at once and the answer is 502.

Errors: 400, 401, 409, 422, 429, 502, 503, 504, 500 (see the error table).

## GET /v1/jobs/{jobId}

Path parameter `jobId`: `job-<32 hex>`. Response 200 (`JobResponse`): `jobId`, `queryId`, `state` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`), `stage`, `createdAt`, `updatedAt`, `startedAt`, `finishedAt` (UTC), `error` (`error`, `message`, `retryable`, when FAILED), `statusUrl`, `resultUrl` (only when SUCCEEDED). The stored request is never returned; jobs expire after `services.ttl.job-hours` (168). Examples: [`job-response.json`](../modules/services/openapi/examples/job-response.json), [`job-response-failed.json`](../modules/services/openapi/examples/job-response-failed.json). Errors: 400, 401, 404 `JOB_NOT_FOUND`, 502, 503, 504.

## GET /v1/jobs/{jobId}/result

SUCCEEDED: 200 with the stored answer in the sync response shape, `mode` `asynchronous` (example [`job-result-response.json`](../modules/services/openapi/examples/job-result-response.json)). QUEUED or RUNNING: 409 `JOB_NOT_FINISHED` (retryable; poll the job). FAILED: the job's own typed error with that code's status (for example 502, 504, or 500). Other errors: 400, 401, 404 `JOB_NOT_FOUND`, 503.

## GET /v1/explain/{queryId}

Path parameter `queryId`: `qry-<32 hex>`. Response 200 (`ExplainResponse`, example [`explain-response.json`](../modules/services/openapi/examples/explain-response.json)): `queryId`, `runId`, `createdAt`, `auditSchemaVersion`, `mode` (`FAKE` or `LIVE`), `llm` (`STUBBED` or `LIVE`), `plan` (the QueryPlan), `resolutions`, `stages` (outcome and latency), `templateIds`, `templateCalls`, `vectorIndexes`, `embeddingModelVersion`, `promptVersions`, `modelIds`, `graphEvidenceIds`, `semanticEvidenceIds`, `metricVersions`, `graphRunId`, `verification`, `removedClaims` (IDs, kind, status, failed rule names; never their text), `droppedLines`, `abstained`, `abstentionReason`, `llmCalls`, `embeddingCalls`, `budget`, `counters` (`cacheHits` is always 0: the runtime has no cache), `answerHash`. No question, prompt, retrieved, or model text, and no credentials. Errors: 400, 401, 404 `QUERY_NOT_FOUND`, 502, 503, 504.

## GET /v1/evidence/{evidenceId}

Path parameter `evidenceId`: `ev-<32 hex>`. Response 200 (`EvidenceResponse`, example [`evidence-response.json`](../modules/services/openapi/examples/evidence-response.json)): `evidenceId`, `evidenceType`, `subjectIds`, `workId`, `sourceUri`, `chunkId`, `graphPath`, `score`, `text` (cut to `services.evidence-max-content-chars`, 2,000), `textTruncated`, `textHash` (of the full text), `facts`, `provenance`. The lookup uses the eventually consistent GSI `evidenceById`, so a just-written item can briefly be 404. Errors: 400, 401, 404 `EVIDENCE_NOT_FOUND`, 502 (also when the payload fails its integrity check), 503, 504.

## GET /v1/graph/nodes/{nodeId}

Path parameter `nodeId`: a stable node ID, percent-encoded (work IDs contain slashes). Response 200 (`NodeResponse`, example [`node-response.json`](../modules/services/openapi/examples/node-response.json)): `node` with `nodeId`, `label`, `properties` (provenance included). Errors: 400, 401, 404 `NODE_NOT_FOUND`, 502, 503, 504.

## GET /v1/graph/nodes/{nodeId}/neighbors

Query parameters (each at most once; any other parameter is 400):

| Parameter | Required | Values |
|---|---|---|
| `direction` | yes | `in`, `out`, `both` |
| `depth` | yes | 1 to `graph-read.neighbors-max-depth` (3) |
| `limit` | yes | 1 to `graph-read.neighbors-max-limit` (200) |
| `edgeTypes` | yes | comma-separated, non-empty subset of the storage edge labels (`NeptuneContract.Edges`: AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, MEMBER_OF; [`STORAGE.md`](../modules/storage/STORAGE.md) "Edge files") |
| `cursor` | no | the opaque `nextCursor` of the previous page, bound to (node, direction, depth, edge types) |

Response 200 (`NeighborsResponse`, example [`neighbors-response.json`](../modules/services/openapi/examples/neighbors-response.json)): `nodeId`, `direction`, `depth`, `limit`, `edgeTypes`, `nodes`, `edges` (`edgeId`, `edgeType`, `fromId`, `toId`, `properties`), `truncated`, `nextCursor` (present when truncated and a full page was returned). At most `graph-read.max-results` (1,000) nodes over all pages. Errors: 400 `INVALID_FIELD` (missing, repeated, unknown or out-of-bounds parameter; a cursor of another walk; example [`error-invalid-field.json`](../modules/services/openapi/examples/error-invalid-field.json)), 401, 404 `NODE_NOT_FOUND`, 502, 503, 504.

## GET /healthz, GET /readyz, GET /metrics

- `/healthz` (public): liveness, depends on no downstream; 200 `{"status":"ok"}` ([`status-healthz.json`](../modules/services/openapi/examples/status-healthz.json)).
- `/readyz` (public): probe reads of each downstream the service uses, through the same timeouts and breakers, plus no open breaker; 200 `{"status":"ready"}` ([`status-readyz.json`](../modules/services/openapi/examples/status-readyz.json)) or 503 `NOT_READY` naming downstreams only (s3, dynamodb, neptune, runtime), never endpoints or configuration.
- `/metrics` (key required): request counts by route and status, latency percentiles over the last 1,024 requests per route, and counters ([`metrics-response.json`](../modules/services/openapi/examples/metrics-response.json)).

## Error model

Every error body is `ErrorBody`: `error` (the code), `message` (fixed service text, never a key, body, question or exception message), `traceId`, `requestId`, `retryable`. Status and `retryable` are fixed per code in `ErrorCode`.

| Code | HTTP | Retryable | Raised when | Example file |
|---|---|---|---|---|
| `MALFORMED_JSON` | 400 | no | the body is not valid JSON | [`error-malformed-json.json`](../modules/services/openapi/examples/error-malformed-json.json) |
| `INVALID_FIELD` | 400 | no | a field, header, path or query parameter is invalid | [`error-invalid-field.json`](../modules/services/openapi/examples/error-invalid-field.json) |
| `REQUEST_TOO_LARGE` | 400 | no | the body exceeds `services.max-body-bytes` | no example file |
| `UNAUTHORIZED` | 401 | no | missing or wrong `X-Api-Key` | [`error-unauthorized.json`](../modules/services/openapi/examples/error-unauthorized.json) |
| `FORBIDDEN` | 403 | no | reserved, unused (single-key deployment) | no example file |
| `ROUTE_NOT_FOUND` | 404 | no | no route for the method and path | no example file |
| `QUERY_NOT_FOUND` | 404 | no | unknown or malformed query ID | [`error-query-not-found.json`](../modules/services/openapi/examples/error-query-not-found.json) |
| `JOB_NOT_FOUND` | 404 | no | unknown, expired or malformed job ID | [`error-job-not-found.json`](../modules/services/openapi/examples/error-job-not-found.json) |
| `NODE_NOT_FOUND` | 404 | no | unknown or malformed node ID | no example file |
| `EVIDENCE_NOT_FOUND` | 404 | no | unknown or malformed evidence ID | [`error-evidence-not-found.json`](../modules/services/openapi/examples/error-evidence-not-found.json) |
| `IDEMPOTENCY_CONFLICT` | 409 | no | key reused with another body, or the first request has no stored answer | [`error-idempotency-conflict.json`](../modules/services/openapi/examples/error-idempotency-conflict.json) |
| `JOB_NOT_FINISHED` | 409 | yes | result requested while QUEUED or RUNNING | [`error-job-not-finished.json`](../modules/services/openapi/examples/error-job-not-finished.json) |
| `UNSUPPORTED_CONSTRAINT` | 422 | no | unknown constraint or analysis field, unranked `rankingMetric`, too many or too long terms | [`error-unsupported-constraint.json`](../modules/services/openapi/examples/error-unsupported-constraint.json) |
| `UNSUPPORTED_RETRIEVAL_MODE` | 422 | no | unknown retrieval field, a limit above its maximum | [`error-unsupported-retrieval-mode.json`](../modules/services/openapi/examples/error-unsupported-retrieval-mode.json) |
| `RATE_LIMITED` | 429 | yes | per-instance query rate exceeded (`Retry-After: 60`) | [`error-rate-limited.json`](../modules/services/openapi/examples/error-rate-limited.json) |
| `DOWNSTREAM_FAILURE` | 502 | yes | a workflow stage or store failed, or the job message was not sent | [`error-downstream-failure.json`](../modules/services/openapi/examples/error-downstream-failure.json) |
| `NOT_READY` | 503 | yes | `/readyz` with a failing probe or open breaker | [`error-not-ready.json`](../modules/services/openapi/examples/error-not-ready.json) |
| `CIRCUIT_OPEN` | 503 | yes | a downstream breaker is open | no example file |
| `OVERLOADED` | 503 | yes | threads and queue full, every workflow slot busy, or a call that could not start in time | no example file |
| `DEADLINE_EXCEEDED` | 504 | yes | the synchronous deadline (`operations.synchronous-deadline-ms`, 40,000) or a job deadline passed | [`error-deadline-exceeded.json`](../modules/services/openapi/examples/error-deadline-exceeded.json) |
| `DOWNSTREAM_TIMEOUT` | 504 | yes | a started downstream call timed out | no example file |
| `INTERNAL_ERROR` | 500 | no | unexpected failure (trace ID, no stack trace) | no example file |

Eight codes have no example file under `openapi/examples/`; their status and retryable flag come from `ErrorCode` in `Enums.scala`.

## Archived live example (window 3)

The smoke test of Nov 19, 2026 ([`operations/releases/smoke-20261119T235713Z.json`](../operations/releases/smoke-20261119T235713Z.json), result PASS) asked "Have Jack J. Dongarra and Yves Robert coauthored a paper?". Its bodies are [`query.json`](../operations/releases/smoke-20261119T235713Z/query.json) (`qry-c08ceb31eb51c6547cda9e522e3d1427`, 2 verified claims) and [`evidence.json`](../operations/releases/smoke-20261119T235713Z/evidence.json) (`ev-927665f3bbac87eb60685e50cfeb8f9b`, `COAUTHOR_EDGE`, 23 joint publications, 2007 to 2021, window `all-years`). The README section "API query examples" shows the commands.
