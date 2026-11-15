# ScholarChain Terraform

Owner: infra-engineer. Spec Phase 12, D-10, D-13, and the Phase 12 human rulings 1 to 21
(`docs/agent-runs/phase-12/prompt.md`). Agents never run `terraform plan`, `apply`, `destroy`,
`import`, or any state command; the human runs the plan through `scripts/plan.sh` and applies only
through the approved path in Phase 14.

## Layout

```
infra/terraform/
  envs/course/              the one root module (ruling 2)
    versions.tf             required_version = "1.16.6"; hashicorp/aws = 6.28.0; default tags
    main.tf                 module composition and the ECS environment contract
    variables.tf            every variable, bound, and validation
    outputs.tf              every value the scripts and ECS need (no secret values)
    course.tfvars           course values (no secrets, no personal data)
    .terraform.lock.hcl     provider lock (commit it)
  modules/
    foundation/             account, Region, partition, the two pinned AZs, name prefix
    network/                VPC, subnets, routes, S3 and DynamoDB gateway endpoints, runtime security groups
    data/                   the environment data bucket
    emr/                    EMR service and instance roles, EMR-managed security groups, log location
    build/                  ECR repository and the CodeBuild image project
    observability/          log groups (always); alarms, dashboard, SNS (runtime only)
    iam/                    ECS task execution roles and task roles (runtime only)
    dynamodb/               the seven runtime tables (runtime only)
    neptune/                Neptune cluster, instance, groups, bulk-load role (runtime only)
    vectors/                S3 Vectors bucket and five indexes (runtime only)
    messaging/              SQS job queue and DLQ (runtime only)
    apikey/                 the API key secret container (runtime only; the work order's "secrets"
                            module, renamed because the permission policy denies any secrets/ path)
    ecs/                    cluster, task definitions, services, ALB (runtime only)
```

No provider other than `hashicorp/aws` is used.

## The two stages (D-13, ruling 2)

`runtime_enabled` (default `false`) selects the cost stage.

| Stage | `runtime_enabled` | Creates |
|---|---|---|
| Cloud window 1 | `false` | network (VPC, 2 public and 2 private subnets, IGW, routes, S3 and DynamoDB gateway endpoints, closed default security group), the data bucket, EMR roles and security groups, the ECR repository, the CodeBuild project and its role, log groups |
| Cloud window 2 | `true` | window 1 plus Neptune, the DynamoDB tables, the S3 Vectors bucket and indexes, SQS, the API key secret, the ECS roles, cluster, task definitions, services and ALB, the runtime security groups, the alarms, the dashboard, and the alarm SNS topic (when `alarm_email` is set) |

EMR clusters are never Terraform resources. `scripts/run-bounded-pipeline.sh` (work order 3) reads
the `emr` output and launches each run as an ephemeral cluster that terminates after its steps.

Ending window 2 means a plan with `runtime_enabled = false`, which destroys every runtime resource,
including the stateful ones (Neptune, the DynamoDB tables, the vector bucket and indexes, SQS, and the
secret, which enters its recovery window). That destroy is expected and is the D-13 cost plan; the
data bucket, ECR, and logs stay.

### Expected resources per stage (the plan is human-run; these are counts from the code)

Window 1 (`runtime_enabled = false`, empty state): **43 to add, 0 to change, 0 to destroy**.

| Module | Resources |
|---|---|
| network | 16: `aws_vpc` 1, `aws_default_security_group` 1, `aws_internet_gateway` 1, `aws_subnet` 4, `aws_route_table` 2, `aws_route` 1, `aws_route_table_association` 4, `aws_vpc_endpoint` 2 |
| data | 7: `aws_s3_bucket` 1, `_ownership_controls` 1, `_public_access_block` 1, `_versioning` 1, `_server_side_encryption_configuration` 1, `_policy` 1, `_lifecycle_configuration` 1 |
| emr | 10: `aws_iam_role` 2, `aws_iam_role_policy_attachment` 1, `aws_iam_role_policy` 2, `aws_iam_instance_profile` 1, `aws_security_group` 2, `aws_vpc_security_group_egress_rule` 2 |
| build | 6: `aws_ecr_repository` 1, `aws_ecr_lifecycle_policy` 1, `aws_cloudwatch_log_group` 1, `aws_iam_role` 1, `aws_iam_role_policy` 1, `aws_codebuild_project` 1 |
| observability | 4: `aws_cloudwatch_log_group` 4 |

Window 2 (from the window 1 state, `runtime_enabled = true`, one ALB CIDR, `alarm_email` set):
**91 to add, 0 to change, 0 to destroy** (89 without an alarm email). From an empty state: 134.

| Module | Resources |
|---|---|
| network | 24: `aws_security_group` 6, `aws_vpc_security_group_ingress_rule` 7 (ALB 1 per CIDR, 2 from the ALB, 4 into Neptune), `aws_vpc_security_group_egress_rule` 11 (ALB 2, HTTPS 4, to Neptune 4, Neptune to the S3 prefix list 1) |
| dynamodb | 7: `aws_dynamodb_table` 7 |
| vectors | 6: `aws_s3vectors_vector_bucket` 1, `aws_s3vectors_index` 5 |
| messaging | 3: `aws_sqs_queue` 2, `aws_sqs_queue_redrive_allow_policy` 1 |
| apikey | 1: `aws_secretsmanager_secret` 1 (no version, so no value) |
| neptune | 7: `aws_iam_role` 1, `aws_iam_role_policy` 1, `aws_neptune_subnet_group` 1, `aws_neptune_cluster_parameter_group` 1, `aws_neptune_parameter_group` 1, `aws_neptune_cluster` 1, `aws_neptune_cluster_instance` 1 |
| iam | 12: `aws_iam_role` 6 (2 execution, 4 task), `aws_iam_role_policy` 6 |
| ecs | 19: `aws_ecs_cluster` 1, `aws_ecs_task_definition` 4, `aws_lb` 1, `aws_lb_target_group` 2, `aws_lb_listener` 1, `aws_lb_listener_rule` 3, `aws_ecs_service` 3, `aws_appautoscaling_target` 3, `aws_appautoscaling_policy` 1 (only services whose maximum is above 1) |
| observability | 12: `aws_sns_topic` 1 and `aws_sns_topic_subscription` 1 (only with an email), `aws_cloudwatch_metric_alarm` 9 (ALB target and ELB 5xx, 2 unhealthy-target, DLQ, 3 ECS CPU, Neptune CPU), `aws_cloudwatch_dashboard` 1 |

## Variables

All variables, their bounds, and their validations are in `envs/course/variables.tf`; the course
values are in `envs/course/course.tfvars`. The ones a human sets or changes:

| Variable | Default | Notes |
|---|---|---|
| `runtime_enabled` | `false` | the cost stage; `true` requires `image_tag` and the three run IDs (validated) |
| `alarm_email` | none (required) | pass `TF_VAR_alarm_email`; `""` creates no subscription. Never in `course.tfvars` |
| `image_tag` | `null` | the 40-hex Git SHA that `scripts/package.sh` built and pushed |
| `runtime_graph_run`, `runtime_items_run`, `runtime_vectors_run` | `null` | `--graph-run`, `--items-run`, `--vectors-run` of the three services |
| `runtime_snapshot` | `2026-10-03` | `--snapshot` |
| `window_summary_run` | `run-6fbad498beb0d8f570df67665fb97544` | `SCHOLARCHAIN_WINDOW_SUMMARY_RUN`, query-api and worker only |
| `availability_zones` | `["us-east-2a", "us-east-2b"]` | exactly two distinct AZs in the Region (validated); pinned, not a data source, so an AZ state change never replans the subnets. A change replaces the subnets |
| `alb_ingress_cidr_blocks` | `["0.0.0.0/0"]` | narrow to the demo client when possible |
| `data_bucket_force_destroy` | `false` | course switch, see Cleanup |
| `emr_instance_type`, `emr_core_instance_count` | `m6g.xlarge`, `2` | Graviton only; core count 1 to 2; primary always 1 |
| `neptune_instance_class` | `db.t4g.medium` | `db.t4g.medium` or `db.t3.medium` only |
| `service_max_counts` | query-api 1, graph-evidence-api 2, worker 1 | each 1 or 2 (ruling 2) |
| `haiku_calls_per_minute_cap` | `8` | validated 1 to 8 |
| `llm_requests_per_minute` | `4` | validated: `llm_requests_per_minute x (query-api max + worker max) <= haiku_calls_per_minute_cap` |
| `haiku_destination_regions` | `["us-east-1", "us-east-2", "us-west-2"]` | the US Haiku 4.5 profile's Regions (ruling 18) |

## Design notes

### Data bucket
- `scholarchain-course-data-<account>-us-east-2`, output `data_bucket_name` (SCHOLARCHAIN_BUCKET).
- Prefixes are the core `storage.prefixes` (`raw/`, `curated/`, `graph/`, `semantic-context/`,
  `vectors/`, `reports/`, `logs/`, `runs/`) plus `artifacts/` (job jars under `artifacts/jobs/`, image
  build contexts under `artifacts/codebuild/source/`). The work order's "rag" prefix is
  `semantic-context/` plus `vectors/`.
- Versioning, SSE-S3, all four public access blocks, `BucketOwnerEnforced`, and a TLS-only policy.
- Lifecycle: incomplete multipart uploads abort after 1 day; `logs/` current objects expire after 30
  days (the Phase 7 ruling 22 audit retention); `artifacts/` after 90 days; noncurrent versions of
  both after 7 days. No rule touches the data prefixes.
- `force_destroy` comes only from `data_bucket_force_destroy` (default `false`). With `false`, a
  destroy of the bucket fails while any object version remains, which protects grading evidence.

### Network and security groups
- `10.40.0.0/16`, two AZs (`availability_zones`, default us-east-2a and us-east-2b). Public subnets (`/20`) for the ALB, the ECS tasks (public IP) and EMR
  nodes; private subnets for Neptune only. No NAT gateway, so the public subnets assign public IPs.
- Security groups by role: the ALB accepts port 80 from `alb_ingress_cidr_blocks` (the only CIDR
  ingress) and sends only to the query-api (8080) and graph-evidence-api (8081) groups; those accept
  only from the ALB; the worker and loader accept nothing; Neptune accepts 8182 only from the four
  task groups and sends only to the S3 prefix list (bulk loader). Task groups send HTTPS to
  `0.0.0.0/0`, because without NAT or interface endpoints the AWS APIs (ECR, Logs, Secrets Manager,
  SQS, Bedrock, S3 Vectors) are public endpoints. The default security group has no rules.
- EMR uses two EMR-managed groups; EMR writes their intra-cluster rules, so Terraform ignores rule
  drift there and revokes the rules on delete. Terraform therefore cannot show or enforce the ingress
  EMR adds: that no rule admits `0.0.0.0/0` or `::/0` is unverified until the first cluster runs.
  `scripts/run-bounded-pipeline.sh` describes both groups after every cluster, records them, and
  warns about any such rule.

### Neptune
- Engine `1.4.8.1` (released 2026-10-06 per the Neptune engine release table, at least 14 days old,
  later than 1.4.6.0), parameter family `neptune1.4`, `db.t4g.medium`, one instance, private subnets,
  storage encrypted with the AWS-managed key, IAM authentication on, `neptune_query_timeout` 30 s.
- Course settings: `deletion_protection = false`, `skip_final_snapshot = true`, backup retention 1
  day. The window lasts about 48 hours and the graph is rebuilt by the bulk loader from `graph/`.
- Bulk-load role `scholarchain-course-neptune-bulk-loader` (trusted by `rds.amazonaws.com`, attached
  to the cluster): `s3:ListBucket` with `s3:prefix` `graph/*` and `s3:GetObject` on `graph/*`.
- Runtime grant: `neptune-db:ReadDataViaQuery` only.

### DynamoDB
- Seven tables named `<dynamodb_table_prefix><Table>`, on demand, string keys as in
  `modules/storage` `DynamoContract`; TTL on `expiresAt` for QuerySession, EvidenceBundle, and
  ServiceState (`recordKey`, `recordType`); GSI `evidenceById` (hash `itemKey`, range `queryId`,
  projection ALL) on EvidenceBundle. Default encryption (AWS owned key).
- PITR off and deletion protection off: the loaded tables are rebuilt from the write-once S3 item
  files by `LoadDynamoCli`, the runtime tables are 30-day TTL records, and the window is ephemeral.

### S3 Vectors
- Vector bucket `scholarchain-course-vectors-<account>` (output `vector_bucket_name`, SSE-S3) and the
  five indexes of `s3-vectors.indexes`: float32, 1024 dimensions, cosine, non-filterable metadata keys
  `vectorKey`, `textHash`, `textRef` (fixed at creation).

### SQS and the API key
- `scholarchain-course-jobs`: visibility 300 s, retention 4 days, long poll 20 s, SQS-managed SSE,
  redrive to `scholarchain-course-jobs-dlq` after 3 receives. The DLQ keeps 14 days and accepts
  redrives only from the job queue.
- `scholarchain-course/api-key`: a Secrets Manager secret with no version. ECS injects it as
  `SCHOLARCHAIN_API_KEY` by ARN into the three services (the loader gets none).

### ECS and the ALB
- Cluster `scholarchain-course-runtime`, Fargate ARM64, one image tag (`image_tag`) for every task.
- Entrypoint: the image keeps the sbt-native-packager default ENTRYPOINT `/opt/docker/bin/services`,
  which needs `-main <class>` because the package has several mains. An ECS `command` is appended to
  the entrypoint, so a command `bin/query-api` would fail. Each task definition therefore sets
  `entryPoint` to `/opt/docker/bin/query-api`, `/opt/docker/bin/graph-evidence-api`, or
  `/opt/docker/bin/worker`, and the `command` holds only the arguments. The image is unchanged.
- Arguments (all three services): `--live --region us-east-2 --snapshot <d> --graph-run <r>
  --items-run <r> --vectors-run <r>`. No `--profile`: credentials come from the task role
  (STORAGE.md "Credentials on ECS"). No `AWS_PROFILE`, key, token, web identity, or `AWS_ROLE_ARN`
  variable is set.
- query-api and the worker share one argument list and one environment (SERVICES.md section 5).
  Environment: `SCHOLARCHAIN_ENVIRONMENT=course`, Region, account, bucket, Neptune endpoint, port,
  and bulk-load role, table prefix, vector bucket, the four model IDs (planner and synthesis Haiku
  4.5; verification and adjudication are not called at runtime but must be non-placeholder outside
  `local`, so they are set to the same Haiku profile), the embedding model, `SCHOLARCHAIN_PROMPTS_DIR
  =/opt/docker/prompts`, `SCHOLARCHAIN_STUB_LLM=false`, the queue and DLQ URLs, the max receive count,
  and `SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE`; query-api and worker also get
  `SCHOLARCHAIN_WINDOW_SUMMARY_RUN`. A precondition refuses any empty value (Phase 11 D12).
- `stopTimeout` 30 s; awslogs to `/scholarchain/scholarchain-course/ecs/<task>`.
- Sizes: 1 vCPU and 2 GiB per service task; 2 vCPU and 8 GiB for the loader.
- Services: desired 1, minimum healthy 100%, deployment circuit breaker with rollback, public IP in
  the public subnets. Application Auto Scaling holds each service between 1 and its maximum; services
  with a maximum above 1 (graph-evidence-api by default) track 60% CPU.
- Haiku bound: each process throttles its own Haiku calls at `runtime.llm.requests-per-minute`, so
  the combined rate is that value times the query-api plus worker task count. Defaults: query-api
  maximum 1, worker maximum 1, 4 per minute each, 8 per minute in total. For the scale-out
  experiment, `service_max_counts.query_api = 2` with `llm_requests_per_minute = 2` (validation
  enforces the product), at the cost of slower synchronous answers.
- ALB: public, HTTP on port 80, idle timeout 60 s (above the 40 s synchronous deadline), invalid
  headers dropped. Rules: `/v1/query`, `/v1/jobs/*`, `/v1/explain/*` and `/healthz`, `/readyz` to
  query-api; `/v1/evidence/*`, `/v1/graph/*` to graph-evidence-api; everything else a fixed 404.
  `/metrics` is not routed. Target health uses `/healthz` (liveness), so a downstream outage that
  `/readyz` reports does not make ECS replace healthy tasks.
- **HTTP risk.** HTTPS needs a domain and a certificate, which the course window does not have. The
  API key therefore travels in clear text in the `X-Api-Key` header. Mitigations: a narrow
  `alb_ingress_cidr_blocks`, a key used only for the window and rotated or deleted after it.

### Neptune loader path (cloud window 2)
Neptune is private, so `LoadNeptuneCli` (StartLoaderJob and GetLoaderJobStatus on the Neptune
endpoint) must run inside the VPC. Chosen path: a one-off ECS task definition
`scholarchain-course-neptune-loader` from the services image, which already holds the storage
classes, run by `scripts/load-stores.sh` with a command override:

```
entryPoint /opt/docker/bin/query-api   (the -main option selects another class)
command    -main scholarchain.storage.cli.LoadNeptuneCli --live --stage-from-bucket
           --dir /tmp/stores/s3/graph/neptune/snapshot=<d>/run=<r> --out /tmp/stores
           [--loader-role-arn <neptune_loader_role_arn>]        (default: SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN)
command    -main scholarchain.storage.cli.LoadNeptuneCli --live --status <loadId>
```

Why not an EMR step: the storage classes are not in the Hadoop or Spark job jars, and a cluster
costs more than a minutes-long Fargate task. The loader task role reads `graph/neptune/*`,
`curated/coauthor-evidence/*`, and `runs/store-conversion/*`, and may only start and read bulk
loads; the load itself reads S3 with the bulk-load role. Network: public subnet, public IP, loader
security group. The live loader CLI runs without `--profile` and stages its local copy from the
bucket with `--stage-from-bucket` (Phase 12 work order 5). The other loads (`LoadS3Cli`, `LoadDynamoCli`, `LoadVectorsCli`) call public AWS
APIs and run from the laptop with a profile.

### IAM summary
| Role | Grants |
|---|---|
| `scholarchain-course-emr-service` | AWS `AmazonEMRServicePolicy_v2` (EC2 actions limited to resources tagged `for-use-with-amazon-emr-managed-policies=true`); `iam:PassRole` on the EMR instance role to `ec2.amazonaws.com` |
| `scholarchain-course-emr-instance` (+ profile) | data bucket: GetBucketLocation, ListBucketMultipartUploads; `s3:ListBucket` unconditioned (ruling 23: S3 answers 404 for a missing key only to a principal with GetObject on that key and ListBucket on the bucket, and a GET or HEAD carries no `s3:prefix`, so a prefix-conditioned ListBucket never applied and every S3A HEAD of a not-yet-existing path got 403; key names only, never data, as for query-api); GetObject on `raw/ curated/ artifacts/`, and on the exact keys `curated`, `logs`, and `logs/emr` (the parent keys an S3A mkdirs or getFileStatus walk HEADs; they hold no data; repair-02 R1, repair-03); Put, Delete, AbortMultipartUpload, ListMultipartUploadParts on `curated/ logs/emr/` (no `graph/`, `semantic-context/`, `runs/`, or `reports/` grant; code review 1 F1) |
| `scholarchain-course-codebuild-services-image` | Logs on its log group; GetObject, GetObjectVersion on `artifacts/codebuild/source/*`; GetBucketLocation, GetBucketAcl; `ecr:GetAuthorizationToken` (no resource scope exists); push and pull actions on the services repository only |
| `scholarchain-course-neptune-bulk-loader` | ListBucket (prefix `graph/*`), GetObject `graph/*` |
| `scholarchain-course-ecs-exec-services` | ECR pull on the services repository, `ecr:GetAuthorizationToken`, logs on the three service log groups, `secretsmanager:GetSecretValue` on the API key secret |
| `scholarchain-course-ecs-exec-loader` | ECR pull, logs on the loader log group; no secret |
| `scholarchain-course-task-query-api` | `s3:ListBucket` (unconditioned, so missing keys answer 404, SERVICES.md section 4); GetObject `logs/rag-runtime/*`, `reports/rag-runtime/*`, `curated/window-summary/*`, `curated/coauthor-evidence/*`, `semantic-context/text/*`; PutObject `logs/rag-runtime/*`, `reports/rag-runtime/*`; DynamoDB GetItem and Query on EntityLinkCache, ResearcherMetricCache, ArtifactRegistry, QuerySession, EvidenceBundle; PutItem on QuerySession, EvidenceBundle; GetItem and PutItem on ServiceState; `neptune-db:ReadDataViaQuery`; `s3vectors:QueryVectors`, `GetVectors` on the five indexes; `bedrock:InvokeModel` on Titan V2, on the Haiku 4.5 US profile, and on the Haiku foundation model in the profile's destination Regions only through that profile; `sqs:SendMessage` on the job queue |
| `scholarchain-course-task-worker` | the query-api grants without SendMessage, plus `sqs:ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility` on the job queue |
| `scholarchain-course-task-graph-evidence-api` | `dynamodb:Query` on `EvidenceBundle/index/evidenceById`; GetObject `logs/rag-runtime/*`; `neptune-db:ReadDataViaQuery`. No window-summary, vector, model, queue, or ServiceState grant |
| `scholarchain-course-task-neptune-loader` | ListBucket (prefixes `graph/neptune/*`, `curated/coauthor-evidence/*`, `runs/store-conversion/*`); GetObject on those prefixes; `neptune-db:StartLoaderJob`, `GetLoaderJobStatus` |

Every role trusts one service principal. Every role except the EMR instance role also carries
`aws:SourceAccount` (and `aws:SourceArn` for ECS and the EMR service role). The EMR instance role
trusts `ec2.amazonaws.com` with no condition: an unverified condition there could stop clusters
from launching (ruling 19). No statement uses a wildcard action. Deployment (the human's `scholarchain-sdk` profile) and
runtime roles are separate.

## Stateful resources and their protections

| Resource | Stage | Protection |
|---|---|---|
| Data bucket | always | versioning, `force_destroy = false`, public access blocked, TLS only; noncurrent versions kept except under `logs/` and `artifacts/` (7 days) |
| ECR repository | always | immutable tags, `force_delete = false` (destroy fails while images exist), lifecycle removes untagged images only |
| Log groups | always | 14-day retention |
| Neptune cluster | runtime | encrypted; deletion protection off and skip-final-snapshot on (course, documented); rebuilt from `graph/` |
| DynamoDB tables | runtime | deletion protection off, PITR off (course, documented); rebuilt from `curated/dynamodb/` |
| S3 Vectors bucket and indexes | runtime | rebuilt from `vectors/backup/` by `LoadVectorsCli`; index settings are force-new, so any change to dimension, metric, or keys replaces the index |
| API key secret | runtime | no value in Terraform; 7-day recovery window on destroy |
| SQS queues | runtime | transient job messages only |

Any plan line that destroys or replaces one of these outside the planned window 2 teardown needs
explicit human sign-off.

## Cost estimate per stage

Prices are us-east-2 on-demand list prices remembered from the AWS pricing pages, which are not on
docs.aws.amazon.com and were not read here: **UNVERIFIED**. The human checks them at the plan gate.

| Stage | Hourly | Per window |
|---|---|---|
| Window 1, idle (nothing running) | about $0.00 (S3 and ECR storage, a few cents per month) | under $0.10 |
| Window 1, one EMR run (1 primary + 2 core `m6g.xlarge`, about $0.154 EC2 + $0.039 EMR each, 3 public IPv4 at $0.005, EBS) | about $0.60 | about $0.60 per cluster-hour; four runs (baseline plus 25/50/100 percent) of about 1 hour each: about $2.50 |
| Image build (CodeBuild ARM small) | about $0.20 per build-hour | about $0.02 per 5-minute build |
| Window 2 (Neptune `db.t4g.medium` about $0.09; 3 Fargate ARM tasks of 1 vCPU and 2 GiB about $0.04 each; ALB about $0.03 with LCUs; 5 public IPv4 about $0.025; Secrets Manager, alarms, dashboard about $0.01) | about $0.27 | 48 hours: about $13, plus one-off loads (DynamoDB about 1.2 M WRU, about $0.75; Neptune I/O and storage under $1; S3 Vectors under $0.10) and Bedrock use (Haiku 4.5, bounded at 8 calls per minute) |

Largest drivers: the window 2 duration (Neptune and Fargate by the hour) and EMR cluster hours.
Leaving `runtime_enabled = true` costs about $6.50 per day.

## Remote-state design (documented only; ruling 1 keeps local state)

Local state: `envs/course/terraform.tfstate` (gitignored by `*.tfstate`), backed up by the human
outside the repository after every apply. A later remote backend would be:

- an S3 bucket `scholarchain-tfstate-<account>-us-east-2`, created once outside this root (a
  bootstrap root or by hand), with versioning, SSE-S3, all public access blocked, a TLS-only and
  deployer-role-only bucket policy, and no lifecycle expiry of state versions;
- `backend "s3" { bucket = ..., key = "scholarchain/course/terraform.tfstate", region = "us-east-2",
  use_lockfile = true, encrypt = true }` (S3-native locking, so no DynamoDB lock table);
- migration with `terraform init -migrate-state` by the human, and reads of state denied to every
  agent as today.

## Human actions

1. **Init** (once, and after any provider or module change):
   `terraform -chdir=infra/terraform/envs/course init -backend=false -input=false` for validation, or
   without `-backend=false` for the local backend used by plan and apply. Commit
   `envs/course/.terraform.lock.hcl`.
2. **Plan** at the Phase 12 gate, through `scripts/plan.sh` (work order 3) with
   `AWS_PROFILE=scholarchain-sdk`, `TF_VAR_alarm_email` set (or empty), and `course.tfvars`. Review
   the add, change, and destroy counts against the tables above, every IAM statement, and any
   stateful destroy or replacement; sign the plan checksum.
3. **Apply** only in Phase 14, only through the approved path (`scripts/deploy.sh` with the approved
   plan checksum), window 1 first.
4. **Before window 2**: verify with read-only calls that `db.t4g.medium` is orderable for engine
   1.4.8.1 (`aws neptune describe-orderable-db-instance-options`), that `m6g.xlarge` is supported by
   `emr-spark-8.0.0` (`aws emr list-supported-instance-types`), and the Haiku 4.5 US profile's
   destination Regions (`aws bedrock get-inference-profile`). Done by the human on 2026-11-14
   (ruling 18): Neptune is orderable in us-east-2a, 2b, and 2c; m6g.xlarge is supported; the profile
   routes to us-east-1, us-east-2, and us-west-2. Repeat them if a value changes.
5. **Secret value** right after the window 2 apply, without the value entering shell history or any
   file in the repository, for example
   `read -rs K && printf '%s' "$K" | aws secretsmanager put-secret-value --secret-id scholarchain-course/api-key --secret-string file:///dev/stdin; unset K`.
   Until the value exists, ECS cannot start the service tasks; `scripts/deploy.sh` then forces a new
   deployment.
6. **Cleanup** is `scripts/cleanup.sh`, human only. `data_bucket_force_destroy` stays `false` until
   the instructor approves the evidence archive.
