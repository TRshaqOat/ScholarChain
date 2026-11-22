# ScholarChain service limits and Region availability

Owner: documentation-engineer. Section 1 holds the account and Region facts the implementation was built against: each was verified by the human, and each has its date and record. Section 3 holds values from public AWS documentation, read on 2026-11-22; those are documentation values, not this account's quotas. An account's quota can be lower or higher than the documented default (see the Bedrock and EC2 pages below), so the values in section 1 are the ones that bound this deployment.

## 1. Account and Region facts (human-verified)

| Item | Value | Verified | Record |
|---|---|---|---|
| Region | us-east-2 (S3 Vectors generally available there) | 2026-11-02 | [`spec/architect-decisions.md`](../spec/architect-decisions.md) D-04 |
| Bedrock on-demand quota, Claude Haiku 4.5 | 10 requests per minute account-wide, shared by every process; 5,000,000 tokens per minute | 2026-11-14 | Phase 17 work order 2 ([`docs/agent-runs/phase-17/prompt.md`](agent-runs/phase-17/prompt.md)); the 10 per minute also in Phase 10 ruling 10 ([`docs/agent-runs/phase-10/prompt.md`](agent-runs/phase-10/prompt.md)) |
| Bedrock on-demand quota, Titan Text Embeddings V2 | 60 requests per minute; 300,000 tokens per minute | 2026-11-14 | Phase 17 work order 2; the 60 per minute also in [`docs/agent-runs/phase-06/human-gate.md`](agent-runs/phase-06/human-gate.md) |
| Haiku model ID | `us.anthropic.claude-haiku-4-5-20251001-v1:0`, a cross-Region inference profile routing to us-east-1, us-east-2 and us-west-2 | 2026-11-14 | Phase 12 ruling 18 ([`docs/agent-runs/phase-12/prompt.md`](agent-runs/phase-12/prompt.md)); [`course.tfvars`](../infra/terraform/envs/course/course.tfvars) `haiku_destination_regions` |
| Embedding model ID | `amazon.titan-embed-text-v2:0`, 1024 dimensions, normalized (stored embedding version `amazon.titan-embed-text-v2:0\|1024\|normalized\|rendering-v1`) | 2026-11-06 | [`docs/agent-runs/phase-07/human-gate.md`](agent-runs/phase-07/human-gate.md) |
| EC2 On-Demand Standard vCPU quota | 16 vCPUs; one EMR cluster (1 primary plus 2 core `m6g.xlarge`) uses 12, so one cluster at a time | before cloud window 1 (Nov 16, 2026) | Phase 14 ruling 6 ([`docs/agent-runs/phase-14/prompt.md`](agent-runs/phase-14/prompt.md)) |
| EMR release | `emr-spark-8.0.0`: Spark 4.0.2, Scala 2.13.16, AWS SDK v2 2.41.32; Hadoop 3.4.2-amzn-1 is present inside the Spark application (there is no separate Hadoop application); Spark runs on Java 17.0.21 while the node default is Corretto 8 | 2026-11-16 | Phase 14 rulings 12 and 14 (describe-release-label and the classpath probe) |
| EMR instance type | `m6g.xlarge` (Graviton, AARCH64) supported on `emr-spark-8.0.0` | 2026-11-14 | Phase 12 ruling 18 |
| YARN maximum container | 11,712 MB | 2026-11-16 | Phase 17 work order 2 (human-verified) |
| Neptune | engine 1.4.8.1 on `db.t4g.medium`, orderable in us-east-2a, 2b and 2c | 2026-11-14 | Phase 12 ruling 18 |
| Neptune bulk loads | the bulk load of this graph ran out of memory on `db.t4g.medium` and completed on `db.r6g.large`; the instance went back to `db.t4g.medium` afterwards | 2026-11-17 to 18 | Phase 14 rulings 42 and 43 |
| ECS | Fargate, ARM64 runtime platform (images are linux/arm64 from the Apple Silicon build) | 2026-11-02 | D-11 |
| Budget | target gross AWS usage below 25 USD (AWS Budget alerts at 10, 25, 50, 75 percent actual and 100 percent forecast); a 60 USD monthly project spend limit as a hard backstop | 2026-11-03 | D-12, D-13 |
| Spend | 16.84 USD month to date before credits | read 2026-11-21 17:00 CST | [`docs/agent-runs/phase-15/evidence/gate/cost-explorer-20261121.txt`](agent-runs/phase-15/evidence/gate/cost-explorer-20261121.txt); Phase 15 ruling 16 point 5 |

## 2. How the implementation stays inside them

| Limit | Control | Record |
|---|---|---|
| Haiku 10 requests per minute | The code throttles every Bedrock call; the configured cap is 8 per minute (`runtime.llm.requests-per-minute`). On ECS, query-api and the worker each get 4 per minute (`llm_requests_per_minute = 4`), and each runs at most 1 task, so the two together stay at 8 (`haiku_calls_per_minute_cap = 8`). The override variable is `SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE`. graph-evidence-api makes no model call. | Phase 10 ruling 10; Phase 12 work order 4; [`course.tfvars`](../infra/terraform/envs/course/course.tfvars) |
| Titan 60 requests per minute | The embedding throttle is 50 per minute; the live embedding of 13,023 vectors ran at 50 per minute with 0 rejected. A question makes at most one embedding call. | Phase 7 gate; [`modules/rag/RUNTIME.md`](../modules/rag/RUNTIME.md) section 9 |
| 16 vCPUs | `run-bounded-pipeline.sh` launches one ephemeral cluster per run (1 primary, at most 2 core, on demand, auto-terminate); the next run starts only after the previous one ends. | Phase 14 ruling 6; [`scripts/README.md`](../scripts/README.md) |
| YARN container size | EMR job 10 still ran out of memory at 50 and 100 percent (P14-D8, open); Spark completed on EMR only at 25 percent. | Phase 14 rulings 19, 21, 22 |
| Neptune memory | Bulk loads run on `db.r6g.large`, then the instance returns to `db.t4g.medium`; a warm-up precedes the smoke test because cold-cache neighbor queries reached the 6 s query timeout. | Phase 14 rulings 42 to 47; [`docs/runbook.md`](runbook.md) |
| ECS scale | Desired count 1 per service; maximums query-api 1, graph-evidence-api 2, worker 1. | `course.tfvars` `service_max_counts` |
| Budget | Each cloud window had a time and cost cap with a stop rule (Phase 14 rulings 8 and 26; Phase 15 ruling 3); the runtime stage is destroyed between windows. | [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md) "Cost" |

## 3. Public documentation consulted (accessed 2026-11-22)

These pages were read through the aws-docs MCP server. The values are what the documentation states; they are not this account's quotas.

| Page | What it states that matters here |
|---|---|
| [Quotas for Amazon Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/quotas.html) | Model inference is controlled by per-model quotas; embedding models are throttled by requests per minute rather than tokens per minute. The quotas that apply to an account might be lower than the published defaults, and the values for an account are checked in Service Quotas. Increases are not granted automatically. |
| [Amazon S3 Vectors limitations and restrictions](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html) | Dimension 1 to 4,096 per vector (ScholarChain uses 1024); up to 10 non-filterable metadata keys per index (ScholarChain declares 3: `vectorKey`, `textHash`, `textRef`); up to 50 metadata keys per vector; filterable metadata up to 2 KB per vector on `CLASSIC` indexes and 40 KB on `ENHANCED` indexes; up to 500 vectors per `PutVectors` call; up to 10,000 top-K results and up to 100 filter constraints per `QueryVectors` request on an `ENHANCED` index; up to 2 billion vectors per index. |
| [emr-spark-8.0.0 release](https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark800-release.html) | Initial release May 21, 2026; standard support until May 20, 2028. Applications listed: AmazonCloudWatchAgent, Delta, Hudi, Iceberg, JupyterEnterpriseGateway, Livy and Spark (4.0.2-amzn-0); no separate Hadoop application. Scala 2.13.16; AWS SDK for Java 2.41.32. |
| [Choosing instance types for Amazon Neptune](https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html) | `t3` and `t4g` exist only in the medium size, are not intended for production, and are not recommended for testing query execution time. About two thirds of instance memory is buffer-pool cache; the page suggests a larger instance when the cache hit rate stays below 99.9 percent. Two query execution threads per vCPU. |
| [Specifying the ARM architecture in an Amazon ECS task definition](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-arm-specifying.html) | ARM is selected with `runtimePlatform.cpuArchitecture = ARM64` and `operatingSystemFamily = LINUX`. |
| [Amazon EC2 instance type quotas](https://docs.aws.amazon.com/ec2/latest/instancetypes/ec2-instance-quotas.html) | On-Demand quotas are in vCPUs per Region; the documented default for Running On-Demand Standard instances is 5, adjustable, and EC2 raises quotas automatically with usage. This account's value at the time was 16 (section 1). |

Pages cited earlier by module owners, with their own access dates: Neptune openCypher compliance (no `shortestPath()`, accessed 2026-11-06, [`modules/rag/RUNTIME.md`](../modules/rag/RUNTIME.md) section 8); the Neptune openCypher bulk-load format and the loader command (accessed 2026-11-05, [`modules/storage/STORAGE.md`](../modules/storage/STORAGE.md) "Neptune").
