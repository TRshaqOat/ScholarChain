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
| graph-evidence-api | `services.graph-evidence-api.threads` = G (16) | G threads, queue 64 | `max(1, G / 2)` slots (8), never used: it runs no workflow, and a slot's thread starts only on first use, so none starts |
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
  - **Idempotency-Key on that 503.** The key is claimed before the workflow is submitted, and the claim is not released when the workflow cannot start (the release is deferred by Phase 10 ruling 18). The key therefore stays claimed until its TTL (`services.ttl.idempotency-hours`, 24). A retry with the same key and body answers 409 IDEMPOTENCY_CONFLICT ("no stored answer"), not a new attempt. To retry after a 503, send a new Idempotency-Key.
  - Async POSTs, `GET /v1/jobs/{jobId}`, `GET /v1/jobs/{jobId}/result`, `GET /v1/explain/{queryId}`, idempotent replays, and `/readyz` keep their own threads. They answer within their store timeouts.
  - `/readyz` stays ready, and no breaker opens.
  - Tested: `SaturationSpec` "query-api: with every workflow slot held ...".
- **Every workflow slot of the worker is held** (a job past its backstop, `runtime.question.deadline-ms` + `runtime.llm.timeout-ms`).
  - The poll loop does not receive.
  - A message already received is not claimed. Its job stays QUEUED, and the worker keeps the message (it is neither deleted nor made visible early). It comes back after the full visibility timeout, and that receive counts toward `services.queue.max-receive-count`.
  - **Repeated misses.** A QUEUED job has no last-delivery rule (only a RUNNING job does). If its message is received without a free slot `max-receive-count` times (3), the queue's redrive policy moves the message to the dead-letter queue, and the job stays QUEUED until its TTL (`services.ttl.job-hours`, 168); `GET /v1/jobs/{jobId}` keeps answering QUEUED. With `services.worker.concurrency` = 1 this needs the brief `getActiveCount` window after a job ends; with concurrency > 1, two poll loops racing for the last slot.
  - Readiness and job-state writes keep their threads, and no breaker opens.
  - Tested: `SaturationSpec` "worker: ...".
- **The store pool is full.** This happens only when store calls overrun their timeouts and ignore the interrupt.
  - A store call still waiting when its timeout passes answers 503 OVERLOADED, and no breaker counts it.
  - A readiness probe that cannot run makes `/readyz` report NOT_READY for that downstream while the pool is full. No breaker opens, so readiness returns as soon as threads free.
  - Tested: `BuildingBlocksSpec` "waiting to start is no downstream failure ...".
- **The HTTP pool is full** (T busy threads plus 64 queued requests). The service answers 503 OVERLOADED, as in work order 5. A queued request that has not been read 30 s after it arrived loses its connection instead (section 6).

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
- When `SCHOLARCHAIN_WINDOW_SUMMARY_RUN` is set, it still takes the key as a runtime run ID input, so its run ID equals that of query-api. In fake mode, the evidence records directory is named by that run ID. On ECS it is not set (Phase 12 ruling 14a; section 5).
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
- graph-evidence-api does not set `SCHOLARCHAIN_WINDOW_SUMMARY_RUN` (Phase 12 ruling 14a, accepted), so its runtime run ID differs from that of query-api and the worker. This is harmless: in live mode its evidence lookup does not depend on the run ID (it uses the evidenceById index), and it answers no questions. Every other input above is the same for all three tasks.

Each service prints its runtime run ID once at startup, on standard output. That line already existed and is unchanged:

- `live mode: runtime run <run ID>, snapshot <date>, model calls stubbed|live`
- `fake mode: runtime run <run ID>, snapshot <date>, outputs under <dir>`

Compare the line of the query-api task with that of the worker task after every deployment.

## 5a. Starting the services on ECS (Phase 12 work order 6)

Command line (`Launcher.Usage`): `--live [--profile <name>] [--region <r>] --snapshot <date> --graph-run <runId> --items-run <runId> --vectors-run <runId>`. The API key comes from the variable `services.api-key-env` names (`SCHOLARCHAIN_API_KEY`, injected by ECS from Secrets Manager).

- **No `--profile` on ECS.** `--live` without `--profile` builds `LiveTarget(None, region)`, which selects the SDK default credentials chain, so the task role supplies the credentials. `--profile <name>` gives `LiveTarget(Some(name), region)` (a human-run laptop start). What the task definition must and must not set for credentials is in `modules/storage/STORAGE.md` "Credentials on ECS". The region is `--region`, else the core `application.region` (`SCHOLARCHAIN_AWS_REGION`). Tested: `LauncherSpec`, with the task-definition arguments and variable names, no client built and no AWS call.
- **Entry point.** The image keeps the sbt-native-packager default ENTRYPOINT `/opt/docker/bin/services`, which needs `-main <class>` because the package has several main classes. ECS appends `command` to the entrypoint, so a command `bin/query-api` would stop with "You need to pass -main argument". Each task definition therefore sets `entryPoint` to `/opt/docker/bin/<service>` (`query-api`, `graph-evidence-api`, `worker`) and passes only the arguments above as `command`. This supersedes the `bin/query-api` command form of Phase 10 ruling 18 (Phase 12 ruling 14b; the staged image of Phase 12 work order 3 has the three start scripts).
- **Window summaries.** query-api and the worker set `SCHOLARCHAIN_WINDOW_SUMMARY_RUN`; graph-evidence-api does not (ruling 14a, section 5).

## 6. Request read timeout and unread bodies (Phase 11 D5)

The JDK HTTP server reads a request's line, headers, and body on an HTTP thread. When a route had not read the whole body, the server drained it (up to 64 KiB) as the exchange ended, after the response was written, and it waited for that body with no time limit. A client that declared a body and never sent it therefore held an HTTP thread and its connection indefinitely, with or without the API key (code review 1, D5). Two limits now apply:

| Limit | Value | Configured where |
|---|---|---|
| Request read timeout: the JDK property `sun.net.httpserver.maxReqTime` | 30 s | the services-local constant `HttpService.RequestReadTimeoutSeconds`, set by `HttpService.start` before the JVM creates its first server (`HttpService.limitRequestReads`). A JVM option `-Dsun.net.httpserver.maxReqTime=<seconds>` set at start wins. |
| Unread-body drain bound | 1000 ms | the services-local constant `HttpService.UnreadBodyDrainMs` |

Neither is a `services.*` key; adding one is a `modules/core` change (domain-engineer).

- **Request read timeout.** A request whose line, headers, and body have not all arrived 30 s after its first byte loses its connection; the client gets no response. This covers a slow or missing body that a route is reading, and headers that never end. A body read cut this way is logged 400 INVALID_FIELD ("the body was not received in full"), not 500. A response that is slow to compute is not limited: the request is complete once its body has been read.
  - The 30 s also counts the time a request waits in the HTTP queue (`services.max-queued-requests`) for a thread, because the JDK reads the request on that thread. A request still queued after 30 s is dropped the same way.
  - The property is JVM-wide and read once, when the JVM creates its first JDK HTTP server. Every JDK server in this repository is an `HttpService`, so it always applies.
- **Unread bodies.** A response to a request whose body the route refused or never read (401, 400 before the body is read, 400 REQUEST_TOO_LARGE, 503 OVERLOADED when shed) is written at once with `Connection: close`. The server then drains what arrives of that body (at most 64 KiB) for at most 1000 ms and closes the connection. A client still sending a small body therefore reads its response rather than a connection reset, and a body that never comes holds the thread for at most 1000 ms: the HTTP thread is interrupted, which closes that connection. When a request is shed on the dispatcher thread (the shed queue is full too), the same 1000 ms bound applies to the dispatcher.
- Tested: `RequestReadSpec` (raw sockets on 127.0.0.1:0):
  - the D5 inputs (999999999 declared bytes with and without the key, and `GET /healthz` with `Content-Length: 100` and no body) are answered 400, 401, and 200 and closed within the 5 s guard, one after another on one HTTP thread;
  - a 9-of-100-byte body and an unfinished header are closed at the timeout, the body read is logged 400 INVALID_FIELD, and the threads serve again;
  - a keep-alive request whose full body was refused gets `Connection: close`.
