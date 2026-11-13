# ScholarChain services: pools, S3 access, and the runtime run ID

Owner: service-engineer. This file covers the deployment facts of `modules/services` that the code alone does not state. Routes, status codes, and bodies are in `openapi.yaml`. Configuration keys and their defaults are in `modules/core/src/main/resources/scholarchain.conf` (`services { ... }`). Phase 10 repair-01 wrote this file for code review 1 findings F1, m1, m2, and m3.

## 1. Deployables

| Task | Main class | Port key (default) | Answers questions |
|---|---|---|---|
| query-api | `scholarchain.services.main.QueryApi` | `services.query-api.port` (8080) | yes: sync `POST /v1/query` runs the rag workflow |
| graph-evidence-api | `scholarchain.services.main.GraphEvidenceApi` | `services.graph-evidence-api.port` (8081) | no |
| worker | `scholarchain.services.main.Worker` | `services.worker.port` (8082) | yes: it runs async jobs |

`scholarchain.services.main.LocalRun` starts all three in one JVM in fake mode on 127.0.0.1 (runMain only).

## 2. Thread pools (F1)

Every downstream call goes through `Downstreams` (`Resilience.scala`). Each call has a timeout and a circuit breaker per downstream. Each service has two downstream pools, so long workflows never take the threads that store calls and readiness probes need. No configuration key was added. The pool sizes come from existing keys:

| Service | HTTP threads | Store pool (store calls and readiness probes) | Workflow pool (no queue) |
|---|---|---|---|
| query-api | `services.query-api.threads` = T (8) | T threads, queue `services.max-queued-requests` (64) | `max(1, T / 2)` slots (4): the sync workflow (`RuntimeApi.answer`) |
| graph-evidence-api | `services.graph-evidence-api.threads` = G (16) | G threads, queue 64 | 1 slot, never used: it runs no workflow, and no thread is started for it |
| worker | `services.worker.threads` = W (2) | `services.worker.concurrency` + W = C + W threads (3), queue 64 | C slots (1): the job workflow |

Which calls run on which pool:

- **query-api store pool:** the idempotency claim, job create, read, and transition (dynamodb), the SQS send, the S3 reads of explain, result, and replay, and the three readiness probes (s3, dynamodb, neptune).
- **query-api workflow pool:** the sync workflow only.
- **graph-evidence-api store pool:** the evidence lookup (dynamodb), the evidence payload read (s3), the node and neighbor reads (neptune), and the two readiness probes (neptune, dynamodb).
- **worker store pool:** the SQS receive, delete, and the job reads and transitions (dynamodb), and the three readiness probes (dynamodb, s3, neptune).
- **worker workflow pool:** the job workflow only.

Why these sizes:

- Each HTTP thread (and each worker poll loop) holds at most one store call at a time. A store pool as large as its callers therefore does not queue in normal operation.
- A sync request holds its HTTP thread while its workflow runs. With at most T / 2 workflow slots, at least T - T / 2 HTTP threads stay free for the job, result, explain, and readiness routes. With T = 1 no thread is reserved. The default is T = 8.
- The worker runs at most C jobs at once (the model quota, ruling 10), so it has C workflow slots.

Two rules apply to every call:

1. **A call's timeout counts from its start, not from its submission.** A call that waited for a thread still gets its full timeout once it runs.
2. **A call that cannot start answers 503 OVERLOADED and touches no breaker.** This covers a full workflow pool (refused at once, because there is no queue) and a store call still waiting for a thread when its timeout passes (given up, and never run). Waiting for a thread says nothing about the health of the downstream.

Only a call that started and then timed out (`DOWNSTREAM_TIMEOUT`) or failed (`DOWNSTREAM_FAILURE`) counts toward its breaker. That rule is unchanged from work order 5.

## 3. Behaviour under saturation

This replaces the work order 5 risk text, "sustained slow model calls can therefore give 503 OVERLOADED". Code review 1 showed that text did not match the code then: queued calls timed out as DOWNSTREAM_TIMEOUT and opened breakers against healthy stores. The behaviour now:

- **Every workflow slot of query-api is held.** The slots may be held by sync workflows still running, or by workflows past their 504 hard timeout. Those run on to their cooperative deadline, which a model call can overrun by up to `runtime.llm.timeout-ms`.
  - A new sync `POST /v1/query` answers 503 OVERLOADED (retryable) at once and touches no breaker.
  - Async POSTs, `GET /v1/jobs/{jobId}`, `GET /v1/jobs/{jobId}/result`, `GET /v1/explain/{queryId}`, idempotent replays, and `/readyz` keep their own threads. They answer within their store timeouts.
  - `/readyz` stays ready, and no breaker opens.
  - Tested: `SaturationSpec` "query-api: with every workflow slot held ...".
- **Every workflow slot of the worker is held** (a job past its backstop, `runtime.question.deadline-ms` + `runtime.llm.timeout-ms`).
  - The poll loop does not receive.
  - A message already received is not claimed. Its job stays QUEUED, and the message comes back after the visibility timeout.
  - Readiness and job-state writes keep their threads, and no breaker opens.
  - Tested: `SaturationSpec` "worker: ...".
- **The store pool is full.** This happens only when store calls overrun their timeouts and ignore the interrupt.
  - A store call still waiting when its timeout passes answers 503 OVERLOADED, and no breaker counts it.
  - A readiness probe that cannot run makes `/readyz` report NOT_READY for that downstream while the pool is full. No breaker opens, so readiness returns as soon as threads free.
  - Tested: `BuildingBlocksSpec` "waiting to start is no downstream failure ...".
- **The HTTP pool is full** (T busy threads plus 64 queued requests). The service answers 503 OVERLOADED, as in work order 5.

## 4. S3 access per task (m1, m3)

S3 answers a GET of a missing key with 404 (NoSuchKey) only when the caller has `s3:ListBucket` on the bucket. Without it, S3 answers 403 (AccessDenied). The storage adapter maps only NoSuchKey to "not found" (`S3ObjectStore`). A 403 is a downstream failure (502) that counts toward the s3 breaker.

Two code paths read keys that usually do not exist:

- **The readiness probe.** query-api and the worker read `logs/rag-runtime/snapshot=<d>/run=<runtime run ID>/readiness-probe`. This key lies under the audit prefix those tasks already read and write (`Probes.objectKey`). It was at the bucket root before repair-01.
  - Without `s3:ListBucket`, `/readyz` is NOT_READY permanently.
- **Unknown query IDs.** explain reads `logs/rag-runtime/.../<queryId>.json`. Result and replay read `reports/rag-runtime/.../<queryId>.answer.json`.
  - Without `s3:ListBucket`, a well-formed but unknown `qry-` ID answers 502 instead of 404, and it counts toward the s3 breaker.

What each task needs, for the object reads and writes that the services wire. The runtime's retrieval reads, for example `curated/coauthor-evidence/*` and `semantic-context/text/*`, are listed in `modules/rag/RUNTIME.md` and `modules/storage/STORAGE.md`.

| Task | `s3:GetObject` | `s3:PutObject` | `s3:ListBucket` on the data bucket |
|---|---|---|---|
| query-api | `logs/rag-runtime/*` (audits, evidence payloads, the readiness probe); `reports/rag-runtime/*` (stored answers); `curated/window-summary/*` (once at startup, when `runtime.window-summary.load-run` is set); the runtime retrieval prefixes | `logs/rag-runtime/*`, `reports/rag-runtime/*` (sync answers; write-once) | **yes**. It may be limited with an `s3:prefix` condition to `logs/rag-runtime/` and `reports/rag-runtime/`. |
| worker | the same as query-api, including `curated/window-summary/*` | the same as query-api (async answers) | **yes**, as for query-api (readiness probe, and the read-back of a write-once object) |
| graph-evidence-api | `logs/rag-runtime/*` (evidence payloads referenced by `payloadRef`) | none | not needed by its code paths. It has no S3 readiness probe, and it reads only keys that an EvidenceBundle item names. |

**Window summaries (m3).** Only query-api and the worker open the window-summary object. graph-evidence-api answers no questions, so `GraphEvidenceApi` starts with `answers = false` and never opens it. It needs no `curated/window-summary/*` grant.
- It still takes the key as a runtime run ID input, so its run ID equals that of query-api. In fake mode, the evidence records directory is named by that run ID.
- With `SCHOLARCHAIN_WINDOW_SUMMARY_RUN` set, it prints `window summaries: <key> not opened (this service answers no questions)`.
- Tested: `CorpusQuerySpec` "graph-evidence-api never opens the window-summary object ...".

## 5. The runtime run ID: deploy query-api and the worker together (m2)

The async result path works only when query-api and the worker compute the **same runtime run ID**. The reasons:
- The worker writes the answer and the audit under `reports/rag-runtime/...` and `logs/rag-runtime/...` of its own run ID.
- query-api reads `GET /v1/jobs/{jobId}/result` and `GET /v1/explain/{queryId}` under its own run ID.
- No result reference is stored (ruling 16).

If the run IDs differ, a SUCCEEDED job's result answers 502, and its explain answers 404.

The run ID is `Workflow.runIdFor` over these inputs. Both tasks must have the same value for every one:

| Input | Set by |
|---|---|
| code version and verifier version | the build (the same image) |
| configuration version | the same configuration file and environment overrides |
| graph, items, and vectors runs | live: `--snapshot`, `--graph-run`, `--items-run`, `--vectors-run`; fake: the `--graph` and `--items` path segments and the vector file contents |
| embedding model version | `models.embedding-model` (`SCHOLARCHAIN_EMBEDDING_MODEL_ID`) |
| window-summary key | `--snapshot` plus `runtime.window-summary.load-run` (`SCHOLARCHAIN_WINDOW_SUMMARY_RUN`) |
| prompt versions | `runtime.planner.prompt-version`, `runtime.synthesis.prompt-version`, and the prompt files |
| planner and synthesis model IDs | `models.planner-model`, `models.synthesis-model` |
| mode | `--live` or fake |

Deployment rules (Phase 12 carry-forward, ruling 23):

- Deploy query-api and the worker together, from one image and one task-definition revision of arguments and environment. A rolling deploy in which one task serves a different revision breaks results for jobs in flight.
- graph-evidence-api should use the same inputs too. In live mode its evidence lookup does not depend on the run ID (it uses the evidenceById index).

Each service prints its runtime run ID once at startup, on standard output. That line already existed and is unchanged:

- `live mode: runtime run <run ID>, snapshot <date>, model calls stubbed|live`
- `fake mode: runtime run <run ID>, snapshot <date>, outputs under <dir>`

Compare the line of the query-api task with that of the worker task after every deployment.
