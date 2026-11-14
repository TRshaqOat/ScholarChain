# Phase 12 prompt: AWS infrastructure in Terraform and reproducible automation (spec Phases 12 and 13, merged by D-10)

## Session plan
- Session 1: work orders 1 to 3, in order: 1 storage-engineer, 2 infra-engineer (Terraform), 3 infra-engineer (scripts).
- Session 2: code review 1 (code-reviewer) of the Phase 12 change set.
- Then the human runs terraform init and scripts/plan.sh with the human's credentials, reviews the plan, and signs it at the gate. One repair round at most. Nothing is applied in this phase; apply is Phase 14 (cloud window 1).
- Phase 11 is accepted (docs/agent-runs/phase-11/commit.txt). Phase 12 changes stay uncommitted until the gate.

## Human rulings before work order 1

1. Scope (D-10, D-13). Spec Phases 12 and 13 as cut by D-10:
   - A two-AZ VPC. Public subnets for the ALB, ECS tasks (public IP, no NAT) and EMR. Private subnets for Neptune only. No NAT gateway. S3 and DynamoDB gateway endpoints. Security groups chained by role, never broad CIDR access except the ALB listener.
   - AWS-managed encryption; no customer-managed KMS keys.
   - No Step Functions, EventBridge, OpenSearch or GitHub Actions.
   - Three ECS services: query-api, graph-evidence-api and worker. This is the justified consolidation of spec Phase 12 item 10.
   - Local Terraform state, gitignored and backed up outside the repo by the human; the remote-state design is documented only.
   - The AWS Budget stays outside Terraform (D-12, created by hand).
2. Cost staging (D-13).
   - One root module at infra/terraform/envs/course, composed of modules under infra/terraform/modules/.
   - A variable runtime_enabled (default false) controls Neptune, the DynamoDB tables, the S3 Vectors bucket and indexes, SQS, the Secrets Manager secret, the ECS cluster, services and ALB, and the runtime alarms.
   - With runtime_enabled = false (cloud window 1), the plan creates only: foundation and tags, the VPC, the data bucket, IAM roles, the EMR roles and security groups, the ECR repository, the image build project (ruling 3), and log groups.
   - EMR clusters are not Terraform resources. scripts/run-bounded-pipeline.sh launches each run as an ephemeral cluster that terminates after its steps.
   - Bounded defaults everywhere:
     - Neptune: the cheapest instance class that supports the engine, or serverless at its minimum, with the cost justified.
     - ECS: desired count 1 per service, maximum 2.
     - EMR: at most 1 primary and 2 core instances, ARM64 Graviton where the release supports it, no Spot.
   - Report the estimated hourly cost and the per-window cost of each stage.
3. Images without Docker on the laptop.
   - A CodeBuild project (ARM container, privileged for docker) in Terraform builds the services image and pushes it to ECR.
   - scripts/package.sh runs `sbt services/Docker/stage` with SCHOLARCHAIN_IMAGE_TAG set to the Git SHA (this needs no Docker). It then zips the staged Docker context, uploads it to the artifacts prefix, starts the build, waits with a timeout, and records the image digest.
   - The ECR repository has immutable tags and scan on push.
   - If the staged Dockerfile cannot build on CodeBuild as it is (for example, the base image pull), report BLOCKED-ON build-engineer with the exact change. Do not edit build.sbt.
   - The Hadoop and Spark jobs ship as assembly jars (`sbt packageJobs`), uploaded to the artifacts prefix with sha256 checksums.
4. Pins (D-09).
   - required_version = "1.16.6", exactly.
   - The AWS provider is pinned to one exact version, at least 14 days old, that has the S3 Vectors resources (vector bucket and index). Verify this with the terraform MCP provider docs search, and report the version, its release date and the resource names.
   - .terraform.lock.hcl is committed.
   - No other provider unless it is needed and justified, pinned the same way.
5. Commands.
   - infra-engineer may run:
     - terraform fmt;
     - `terraform -chdir=infra/terraform/envs/course init -backend=false -input=false`, whose only network use is downloading the pinned provider (the human approves that permission prompt once);
     - terraform validate;
     - bash -n on every script;
     - scripts/check.sh;
     - any script's --help, or a dry-run mode that makes no AWS call.
   - infra-engineer never runs terraform plan, apply, destroy, import or any state command, never runs an aws command, and never runs a script against AWS (docs/agents.md section 8).
   - The human runs terraform plan through scripts/plan.sh at the gate, with AWS_PROFILE=scholarchain-sdk. Terraform reads that profile's credential_process; it cannot use the aws login session directly.
   - The terraform MCP RunCheckovScan may be run once, as an optional extra (D-10 deferred Checkov). Report its findings. Fixing them is optional unless a finding is high severity.
6. Script rules (spec Phases 12 and 13).
   - General:
     - Bash strict mode (set -euo pipefail).
     - Compatible with macOS bash 3.2: no associative arrays, no mapfile, no ${x,,}.
     - Runnable from a clean checkout.
     - Greppable step markers (for example `==> STEP name`), explicit exit codes, timeouts on cloud operations, retries only for idempotent calls.
     - No interactive prompts; scripts take explicit flags.
   - AWS access:
     - Every AWS-calling script takes --profile and --region (defaults: AWS_PROFILE and us-east-2) and prints the account and region before acting.
     - No secret values in source or output.
   - deploy.sh:
     - refuses a dirty tree;
     - applies only a saved plan file whose sha256 equals the --approved-plan-sha256 value;
     - never uses -auto-approve;
     - records the deployed revision and image digests.
   - cleanup.sh is human-only. It writes a destroy plan, lists every resource, and destroys only with --confirm <sha256 of that plan>. It keeps what the spec "Cleanup requirements" keep.
   - Never hide terraform plan output.
7. Carry-forward requirements from Phases 7 to 11 are binding; they are listed in work orders 2 and 3. Where a requirement conflicts with the code or documentation in modules/, report it as a finding with file and line; do not edit outside owned paths.
8. Constraints for every agent:
   - Reads and paths:
     - No reads, writes or listing under labels/, scratch/stores-live/ or scratch/er/adjudication-live/. Scripts may name these paths, but agents never read them. Prune both in any find or du.
     - Owned paths only. Nothing under scratch/ is modified or deleted. New agent outputs go only under scratch/phase-12-logs/ (cited logs).
     - docs/agent-runs/** is read-only, except the orchestrator's report appends.
   - Commands and network:
     - No git. No aws commands, and no AWS or Bedrock API calls.
     - Network only as in ruling 5, plus sbt resolution of pinned artifacts.
   - sbt:
     - SPARK_LOCAL_IP=127.0.0.1 for every sbt run.
     - Heap at most 6g.
     - Never two sbt processes at once.
   - Housekeeping:
     - Stop below 5 GiB free disk.
     - Stop background shells when they finish.
   - Hooks:
     - Use the Read and Grep tools, not Bash, to inspect scripts. guard-dangerous blocks Bash text containing terraform apply or destroy, .tfstate, -auto-approve or env dumps. These are expected false positives; never work around a hook.
9. One repair round, grouped by owner. Minor findings may become residual risks. code-reviewer runs without Ponytail.

## Work order 1: storage-engineer (ECS credentials; Phase 10 ruling 18)

Read Phase 10 prompt.md ruling 18, the LiveAws code in modules/storage, and modules/services/SERVICES.md section 4. Edit only modules/storage/**.
1. When no AWS profile is configured, LiveAws uses the AWS SDK default credentials provider chain: environment, ECS container credentials, then instance profile. When a profile is configured, it keeps the named-profile provider. Region handling is unchanged, and credential refresh stays the SDK default.
2. Grep modules/ for every other place that builds AWS credentials or clients (for example the Bedrock and SQS clients). If any is outside modules/storage, report BLOCKED-ON its owner with the exact change.
3. Tests: which provider is chosen with and without a profile, with no network and no AWS. Add a logged disable check with sha256 restore.
4. Document in the storage docs that ECS sets no profile, and which environment variables ECS must set. If a configuration contract change is needed (modules/core), report BLOCKED-ON domain-engineer with the exact change, and do not edit modules/core.
Run sbt check twice.

## Work order 2: infra-engineer (Terraform; spec Phase 12)

Read these first:
- spec Phase 12, D-10 and D-13, and rulings 1 to 9;
- modules/services/SERVICES.md sections 4 to 6, and modules/services/openapi.yaml;
- the storage docs and contracts in modules/storage, for table names, keys and indexes, and the vector index definitions;
- modules/spark/JOBS.md and the hadoop docs, for job inputs and outputs.

Edit only infra/**.

Modules:
- Always: foundation (provider pins, default tags Project=ScholarChain and Environment=course), network, iam, data, emr (roles, security groups, log location; no cluster), build (ECR and CodeBuild).
- Behind runtime_enabled: dynamodb, neptune, vectors, messaging, secrets, ecs (cluster, task definitions, services, ALB), observability.
- An environment variable file infra/terraform/envs/course/course.tfvars with no secrets.

Required contents:
- **Data bucket.**
  - One per environment (exported for SCHOLARCHAIN_BUCKET), with prefixes for raw, curated, graph, rag, artifacts, logs and reports.
  - Versioning, SSE-S3, public access blocked, and lifecycle rules for the logs and artifacts prefixes.
  - force_destroy only through a course variable, documented.
- **S3 Vectors.** A vector bucket (exported for SCHOLARCHAIN_VECTOR_BUCKET) and the 5 indexes: 1024 dimensions, cosine, with the non-filterable metadata keys vectorKey, textHash and textRef declared at creation. Index names come from the storage contracts.
- **DynamoDB.**
  - The existing runtime tables, on demand, named with the dynamodb.table-prefix value.
  - TTL attribute expiresAt on QuerySession, EvidenceBundle and the ServiceState table (keys recordKey and recordType).
  - GSI evidenceById on EvidenceBundle: hash itemKey, range queryId, projection ALL.
  - Point-in-time recovery off for the course environment, justified.
- **SQS.** A job queue with visibility timeout 300 s and SSE, redriving to a DLQ with maxReceiveCount 3. The DLQ retention is longer than the source's.
- **Secrets Manager.** One secret for the API key, with no value in Terraform; the human sets the value. ECS injects it as SCHOLARCHAIN_API_KEY by ARN.
- **Neptune.**
  - Engine 1.4.6.0 or later, in the private subnets, with subnet and parameter groups, encryption and IAM authentication.
  - Deletion protection off and skip-final-snapshot on in the course environment, documented.
  - A bulk-loader IAM role (read on the graph prefix).
  - A runtime grant of neptune-db:ReadDataViaQuery only.
- **ECS Fargate ARM64.**
  - A cluster, and task definitions for query-api, graph-evidence-api and worker from one image tag.
  - Commands bin/query-api, bin/graph-evidence-api and bin/worker; the Docker entrypoint stays the plugin default (Phase 10 ruling 18).
  - stopTimeout at least 25 s.
  - query-api and worker share one argument and environment set (SERVICES.md section 5: identical runtime run ID).
  - Environment:
    - SCHOLARCHAIN_JOB_QUEUE_URL and SCHOLARCHAIN_JOB_DLQ_URL;
    - SCHOLARCHAIN_STUB_LLM=false;
    - SCHOLARCHAIN_WINDOW_SUMMARY_RUN=run-6fbad498beb0d8f570df67665fb97544, on query-api and worker only;
    - no empty SCHOLARCHAIN_* value (Phase 11 D12).
  - Task maximums chosen so that live Haiku calls from query-api and worker together stay at or below 8 per minute.
  - awslogs to one log group per service.
- **ALB.**
  - Public, with routes as openapi.yaml and SERVICES.md define them.
  - Target health /healthz, readiness /readyz.
  - Idle timeout above the 40 s sync deadline (for example 60 s).
  - HTTP is acceptable for the course window if HTTPS needs a domain. Document the risk (the API key travels in a header).
- **IAM** per SERVICES.md section 4:
  - query-api and worker: s3:ListBucket, GetObject on the runtime prefixes and curated/window-summary/*.
  - graph-evidence-api: no window-summary grant, and dynamodb:Query on the evidenceById index.
  - query-api: sqs:SendMessage. worker: ReceiveMessage, DeleteMessage and ChangeMessageVisibility.
  - ServiceState: GetItem and PutItem.
  - Bedrock: invoke only on Titan V2 and on the Haiku 4.5 inference profile, plus the foundation-model ARNs in the regions that profile routes to.
  - Separate task execution roles (image pull, logs, the secret) from task roles.
  - EMR service and instance roles scoped to the data bucket.
  - The CodeBuild role scoped to its source prefix, the ECR repository and its log group.
  - No wildcard actions on data services.
- **Loader path.** Neptune is private, so the cloud window 2 loaders (LoadNeptuneCli at least) must run inside the VPC: either a one-off ECS task definition from the services image with a command override, or an EMR step. Choose one, justify it, and report what it needs (class names and arguments from the storage docs). If the image lacks the loader classes, report BLOCKED-ON build-engineer.
- **Observability.**
  - Log groups with 14-day retention.
  - Alarms for ALB 5xx, unhealthy targets, DLQ depth above 0, ECS CPU and Neptune CPU.
  - One small dashboard.
  - Any alarm email comes from a variable with no default.
- **Outputs.** Every value the scripts and ECS need: bucket names, queue URLs, the ALB DNS name, the repository URL, role ARNs, and the subnet and security group IDs for EMR and one-off tasks.
- **Not Terraform.** The window-summary object copy and all store loads are script steps (work order 3).

Validation:
- Run terraform fmt -check, the ruling 5 init, and terraform validate.
- Run RunCheckovScan once if available (ruling 5).
- Write infra/terraform/README.md: layout, the two stages, variables, the remote-state design, the cost estimate per stage, and the human actions (init, plan, secret value, apply in Phase 14).

Report:
- the files;
- the provider pin evidence;
- the expected resources per stage by type (the plan is human-run, so give expected add counts);
- an IAM summary per role;
- the stateful resources and their protections;
- the high-cost resources;
- assumptions and any BLOCKED-ON.

## Work order 3: infra-engineer (scripts; spec Phase 13)

Read spec Phase 13, the spec Phase 12 required scripts, the spec "Cleanup requirements", docs/agents.md section 8, rulings 1 to 9, and the work order 2 report. Edit only scripts/**, and infra/** for any outputs the scripts need.

Write these scripts, plus scripts/README.md. For every script, the README gives its purpose, flags, required environment variables by name, who runs it (docs/agents.md section 8: the human or deploy-engineer), approval points, the evidence it writes, and its rollback behaviour.
- **check.sh**
  - JSON validity of .claude/settings.json and .mcp.json.
  - bash -n on .claude/hooks/*.sh and scripts/*.sh.
  - sbt check (scalafmt check, compile and tests).
  - terraform fmt -check and validate.
  - No AWS. Exits nonzero on any failure.
- **package.sh**
  - sbt packageJobs and the jar sha256 values.
  - The ruling 3 image build.
  - Writes a package record: commit SHA, jar sha256 values, image tag and digest.
- **plan.sh**
  - Refuses a dirty tree.
  - Runs terraform init (local backend), then plan -out with the var file and runtime_enabled given as a flag.
  - Writes the plan file, its sha256, the commit, the account and region, the add/change/destroy counts, the IAM changes and the stateful replacements, into a gitignored local directory. A secret-free summary copy is written for evidence.
- **deploy.sh**
  - Follows ruling 6.
  - Waits for the ECS services to become stable, with a timeout.
  - Verifies that the running task image digest equals the packaged one.
- **rollback.sh**
  - Moves the ECS services to the last known-good image tag from the release record, with no Terraform apply.
  - Waits until stable.
- **smoke-test.sh**
  - healthz and readyz.
  - One bounded researcher query (sync).
  - Evidence fetched for one returned claim.
  - An idempotent repeat with the same Idempotency-Key.
  - One expected error (401 without a key, or 422).
  - Reads the API key from Secrets Manager into a variable without printing it.
- **run-bounded-pipeline.sh**
  - Uploads the bounded snapshot 2026-10-03 inputs and the job jars if absent.
  - Creates an ephemeral EMR cluster: release emr-spark-8.0.0 (D-01), the ruling 2 bounds, auto-terminate.
  - Runs the Hadoop J01 to J10 and Spark job 1 to 11 steps in their documented order with their documented arguments (modules/hadoop and modules/spark docs).
  - Takes --fraction 25|50|100 for the D-13 size-scaling runs. If no existing tool produces the 25 and 50 percent subsets, report BLOCKED-ON dataset-engineer with the exact need.
  - Records the snapshot, run IDs, configuration, step timings, output locations, cluster ID and instance hours.
  - Phase 11 note: the Typesafe Config and SDK classpath on EMR is checked in cloud window 1; the script records the classpath-related step logs.
- **load-stores.sh**
  - Runs the cloud window 2 loads in this order, with the arguments the storage docs give:
    1. LoadS3Cli, including the window-summary object from scratch/runtime/wo8/s3;
    2. LoadNeptuneCli, through the work order 2 in-VPC path;
    3. LoadDynamoCli (about 876k conditional puts);
    4. LoadVectorsCli.
  - Bounded; records counts.
- **run-experiment.sh**
  - Runs only a committed manifest under experiments/, and refuses an uncommitted one.
  - Records the commit SHA, limits, start and end times, region and metric locations.
  - No manifest is written in this phase.
- **archive-evidence.sh**
  - Gathers test reports, the plan checksum, image digests, run manifests, selected CloudWatch metric exports and experiment outputs.
  - Writes them into a versioned local directory given by a flag, and optionally to an S3 prefix.
- **cleanup.sh**: ruling 6.

Validation:
- bash -n on every script.
- scripts/check.sh once, end to end (it includes sbt check, about 5 min).
- Each script's --help, and its dry-run where one is offered, with no AWS call.

Report:
- the command lines, flags and required environment variables by name;
- the approval points;
- the evidence each script writes;
- rollback behaviour;
- everything not run because it needs AWS.

## Code review 1: code-reviewer

Use the code-reviewer agent for an adversarial read-only review of the Phase 12 change set (infra/**, scripts/** and the modules/storage changes). Check it against spec Phases 12 and 13 (work orders and gates), D-10, D-13, rulings 1 to 9, SERVICES.md sections 4 to 6, openapi.yaml, and the carry-forward requirements in work orders 2 and 3.

Review in this order:
1. Secrets in source, variable files, outputs or script output.
2. IAM scope: actions and resources.
3. Public exposure: buckets, databases and security groups.
4. Stateful resources: destroy and replacement risks, and deletion settings.
5. Bounded capacity and cost.
6. Script safety: approval gates, dirty-tree refusal, plan checksum, cleanup confirmation, strict mode, bash 3.2, timeouts, and retries only for idempotent calls.
7. Contract drift between Terraform, the storage contracts, SERVICES.md and openapi.yaml: table keys, index names, environment variable names, queue settings.
8. The ECS credentials change and its tests.
9. Documentation claims not backed by code.

Verify every candidate finding against the files before reporting it. For each finding, give the severity, file and line, a concrete failure input, the expected impact and the owning agent. Return APPROVE or REQUEST_CHANGES. Do not edit any file.
