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

## Human rulings after session 1

10. Accepted deviations, recorded at the gate.
   - (a) Work order 1 ran read-only git diff and git status, against ruling 8. Nothing was changed.
   - (b) Work order 3 ran scripts/cleanup.sh (--help, three --dry-run stages and one wrong --confirm case) through a shell function wrapper that the guard-dangerous cleanup-script rule did not match. Each run exited before any AWS, Terraform or git call, and the agent disclosed it. Ruling 5 allowed --help and dry-runs of any script, which conflicted with docs/agents.md section 8.
   - Ruling 5 is amended: no agent runs scripts/cleanup.sh in any mode, and no agent invokes a command through a function, alias or wrapper in a way that a hook would otherwise match.
   - The guard gap (the cleanup-script rule anchors on command position) is a minor factory-engineer item carried to the backlog. No factory change in Phase 12.
11. Pins and accepted risks.
   - The human confirmed that hashicorp/aws 6.28.0 was released on January 7, 2026 (D-09 holds), and that the lock file holds the published darwin_arm64 zip hash.
   - The module name apikey is accepted. The human removed the empty modules/secrets/ directory.
   - Accepted as documented risks, for code review 1 to check:
     - the HTTP-only ALB (no domain);
     - the unconditioned s3:ListBucket on query-api and worker (SERVICES.md:83);
     - the 60 waived medium Checkov findings;
     - the Docker Hub base-image pull on CodeBuild. If the first build fails on a rate limit, dependency-updater moves javaRuntimeImage to the ECR Public mirror after the human confirms the same digest (a Phase 14 item).
   - At the gate the human checks, read-only: the Haiku inference-profile Regions, db.t4g.medium orderability for Neptune 1.4.8.1, and m6g.xlarge on emr-spark-8.0.0.
12. Routing of the BLOCKED-ON items. Session 1b runs work orders 4 to 8 before code review 1, so the review sees the final tree. infra-engineer is not rerun: the task definitions and load-stores.sh already use the post-fix forms, and code review 1 checks that they match.
13. The size-scaling runs of cloud window 1 (D-13, Nov 17) need the 25 and 50 percent subsets, so work order 7 has priority. Its outputs are the only new files allowed under scratch/ outside scratch/phase-12-logs/: the new directories scratch/datasets/fraction=25/ and scratch/datasets/fraction=50/. Nothing existing is modified.
14. Findings against the modules/ docs (work order 2 risks a to e):
   - (a) Accepted: graph-evidence-api does not set SCHOLARCHAIN_WINDOW_SUMMARY_RUN (SERVICES.md:117). service-engineer notes this in work order 6.
   - (b) Phase 10 ruling 18's `bin/query-api` command form is superseded by the entryPoint form (work order 2 assumption 1, confirmed by work order 3's staged image). service-engineer documents this in work order 6.
   - (c) hadoop JOBS.md:33 should name the single bucket: work order 8.
   - (d) The unused verification and adjudication model placeholders are a minor residual; Terraform sets them to the Haiku profile ID.
   - (e) The storage CLIs: work order 5.
15. Code review 1 scope (amends "## Code review 1"). Code review 1 reviews work orders 1 to 8 against rulings 1 to 15. It also checks:
   - the ruling 10 deviations;
   - that the ECS task definitions and scripts/load-stores.sh pass arguments that the CLIs accept after work orders 5 and 6;
   - that scripts/run-bounded-pipeline.sh reads the work order 7 layout.

## Session 1b plan
Work orders 4 to 8, in order: 4 domain-engineer, 5 storage-engineer, 6 service-engineer, 7 dataset-engineer, 8 hadoop-engineer. Ruling 8 constraints (as amended by ruling 10, with the ruling 13 exception) apply. Then session 2: code review 1.

## Work order 4: domain-engineer (Haiku cap)

Read the work order 2 report (BLOCKED-ON item 1). Edit only modules/core/**.
1. Add `"SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE" -> "runtime.llm.requests-per-minute"` to ConfigLoader.EnvOverrides. Note the variable name beside the key in scholarchain.conf.
2. Tests: the variable overrides the key; 0, a negative value, a fractional value, and a blank value outside local are each refused. Add a logged disable check with sha256 restore.
3. No config-version bump unless the existing rules require one; say which.
Run sbt check twice.

## Work order 5: storage-engineer (in-VPC loader)

Read the work order 2 report (BLOCKED-ON item 2) and scripts/load-stores.sh (read-only). Edit only modules/storage/**.
1. Cli.liveTarget: `--live` without `--profile` returns a target with no profile, which selects the SDK default chain as in work order 1. Every live loader CLI accepts this. Update the usage text, STORAGE.md:7, :458 and :660, and the LoadNeptuneCli message at line 155.
2. LoadNeptuneCli `--stage-from-bucket` (with --live).
   - Before the existing scan, it copies into <out>/s3, write-once:
     - the store-conversion manifest of the run;
     - every manifest object under the --dir graph prefix;
     - every manifest object under curated/coauthor-evidence/snapshot=<d>/run=<r>/.
   - It verifies each object against the manifest sha256, and refuses on any mismatch or missing object.
   - Then it runs the unchanged scan, preload checks and start.
   - It is bounded: the object count and bytes come from the manifest, and it has a timeout.
   - `--status` needs no staging.
3. Tests with the existing fake object store and no network:
   - the staged copy matches the manifest;
   - a sha256 mismatch is refused;
   - a missing object is refused;
   - no profile selects the default chain.
   Logged disable checks with sha256 restore.
4. Check that the arguments load-stores.sh passes match the CLIs. Report any mismatch as a finding for infra-engineer; do not edit scripts/.
Run sbt check twice.

## Work order 6: service-engineer (optional --profile)

Read the work order 1 report (BLOCKED-ON) and ruling 14. Edit only modules/services/**.
1. Composition Launcher.backend: `--live` without `--profile` builds a target with no profile. Usage and the doc comment become `--live [--profile <name>]`.
2. Test: with no profile, profileName is None; with --profile x, it is Some("x"). Add a logged disable check with sha256 restore.
3. SERVICES.md:
   - ECS starts the services with no --profile (see STORAGE.md "Credentials on ECS").
   - Task definitions use entryPoint /opt/docker/bin/<service>, with arguments only. This supersedes the command form in Phase 10 ruling 18.
   - graph-evidence-api has no SCHOLARCHAIN_WINDOW_SUMMARY_RUN (ruling 14a).
4. Check that the arguments and environment of the ECS task definitions (infra/terraform/modules/ecs/main.tf, read-only) are accepted by the Launcher. Report any mismatch as a finding for infra-engineer.
Run sbt check twice.

## Work order 7: dataset-engineer (size-scaling subsets, D-13; priority)

Read the work order 3 report (BLOCKED-ON item 1) and scripts/run-bounded-pipeline.sh (read-only) for the exact input layout. Edit only datasets/**; outputs go only under the ruling 13 directories.
1. A tool under datasets/scripts/ (for example make_fraction.sh) with `--fraction 25|50`.
   - It writes scratch/datasets/fraction=<p>/ in exactly the layout run-bounded-pipeline.sh reads, for every input that script uploads.
   - Selection is deterministic and nested (25 within 50 within 100): a DBLP record is kept when int(the first 8 hex digits of sha256("fraction-v1|" + key)) mod 100 < p.
   - OpenAlex and Crossref are filtered to the kept DOIs. ORCID is filtered to the iDs linked to kept records.
   - If the pipeline reads DBLP persons, filter them to the kept authors or keep them whole; justify the choice.
   - It writes SHA256SUMS and run-info.json: the fraction, the rule, the source sha256 values, and the counts per input.
2. Run it for 25 and 50. Validation:
   - nesting holds: every 25 key is in 50, and every 50 key is in 100;
   - the counts are near 25 and 50 percent of the 100 percent input;
   - every output parses with the existing dataset validators;
   - `scripts/run-bounded-pipeline.sh --fraction 25 --dry-run` and `--fraction 50 --dry-run` resolve their inputs (dry-run only, no AWS). If either still fails, report it as a finding for infra-engineer.
3. Record the fraction rule and the counts in the dataset manifest and docs.

## Work order 8: hadoop-engineer (documentation)

Edit only modules/hadoop/JOBS.md. Line 33 names `<raw-bucket>` and `<curated-bucket>`. Replace them with the single data bucket and its raw/ and curated/ prefixes, as infra/terraform/README.md defines them. Documentation only, so no sbt check.

## Human rulings after session 1b

16. Session 1b results.
   - Work orders 4 to 8 are COMPLETE with no BLOCKED-ON. sbt check: 839 passed, 0 failed, 10 pending.
   - Known minor stale text is listed here so that code review 1 confirms it without re-deriving it. It is fixed in repair-01 together with any review findings, or carried as a residual risk if there is no repair round.
   - infra-engineer:
     - infra/terraform/envs/course/variables.tf:397;
     - scripts/load-stores.sh:43-44;
     - scripts/run-bounded-pipeline.sh:18-20 and :98;
     - scripts/README.md:304;
     - the run-bounded-pipeline.sh --dry-run output does not print the resolved input paths.
   - storage-engineer: modules/storage/STORAGE.md:487.
17. ORCID does not scale with the size fraction under the fraction-v1 linkage rule: 64.9 percent of records at 25 and 80.0 percent at 50, because a researcher is kept when any one of their works is kept. This is accepted. The D-13 size-scaling analysis uses DBLP works (and the derived authors and edges) as the scaling variable, and the final report states the ORCID behaviour as a limitation. No change.

## Human rulings after code review 1

18. Ruling 11 read-only checks, run by the human (2026-11-14):
   - The US Haiku 4.5 inference profile routes to us-east-1, us-east-2 and us-west-2. course.tfvars:50 also lists us-west-1, which repair-01 removes.
   - Neptune 1.4.8.1 on db.t4g.medium is orderable in us-east-2a, 2b and 2c.
   - emr-spark-8.0.0 supports m6g.xlarge (AARCH64).
19. Code review 1 returned REQUEST_CHANGES: F1 is major, and F2 to F9 are minor. One repair round (ruling 9), repair-01, grouped by owner and run in this order: Part A dataset-engineer, Part B storage-engineer, Part C infra-engineer. Part C runs last so that its check.sh run covers the final tree. Routing decisions:
   - F7a: correct infra/terraform/README.md:237. Do not add an aws:SourceAccount condition to the EMR instance role's EC2 trust; an unverified trust condition could stop clusters from launching.
   - F9: rename the script evidence logs under operations/ to .txt. No .gitignore change (build-engineer is not invoked).
   - The optional .gitignore entry for __pycache__ is not made; Part A removes the cause. The human deleted the existing datasets/scripts/__pycache__/.
20. Code review 2 reviews only the repair-01 changes, and confirms each code review 1 finding as resolved, or open with its reason. If it returns REQUEST_CHANGES, the exit is human escalation: open items become residual risks or Phase 14+ items. No second repair round.
21. Commit and plan order. plan.sh refuses a dirty tree, and deploy.sh requires the plan's commit to equal HEAD. So:
   - after code review 2 (APPROVE or escalation), the human commits Phase 12 with phase_commit 12;
   - the human then runs scripts/plan.sh --runtime-enabled false on that commit (expected 43/0/0), and reads every IAM and subnet line of the full plan;
   - the human signs the plan sha256 in human-gate.md, committed as a follow-up.
   Apply (Phase 14) requires that signed checksum, so the spec gate "human signs the plan before any apply" holds.

## Repair-01

Report to docs/agent-runs/phase-12/repair-01.md, one section per part. Ruling 8 constraints, as amended by ruling 10, apply. Each part cites the code review 1 finding it closes. Each code change gets a logged disable check (sha256 restore) where a test or a check can show it.

### Part A: dataset-engineer (F2)
Edit only datasets/**.
- make_fraction.py must not write bytecode when it loads dblp_extract.py: set sys.dont_write_bytecode = True before exec_module, or equivalent.
- Run datasets/scripts/validate.sh and make_fraction.sh --verify, then confirm that no __pycache__ directory exists anywhere under datasets/.

### Part B: storage-engineer (F8 storage item, ruling 16)
Edit only modules/storage/**.
- Correct the doc comment at LiveAws.scala:31-32, and STORAGE.md:487, to match work orders 5 and 6: --profile is optional for the four loaders and for the services Launcher.
- Run storage/scalafmtCheck and storage/compile. (Part C's check.sh run covers the full sbt check.)

### Part C: infra-engineer (F1, F3 to F8, ruling 16 items, ruling 18)
Edit only infra/** and scripts/**.
1. F1 (major). The EMR instance role:
   - writes and deletes only curated/* and logs/emr/*;
   - reads only raw/*, curated/* and artifacts/*;
   - lists the bucket only with those prefixes.
   Correct infra/terraform/README.md:227 to match. Show by grep or a terraform console expression that no other prefix remains.
2. F3: deploy.sh:148 force-new-deployment uses aws_once.
3. F4: check.sh also runs bash -n on datasets/scripts/*.sh and runs datasets/scripts/validate.sh.
4. F5: haiku_calls_per_minute_cap is validated to be between 1 and 8.
5. F6: replace the aws_availability_zones data source with a variable availability_zones: exactly two distinct names in the Region, default ["us-east-2a", "us-east-2b"]. Neptune is orderable in both (ruling 18).
6. F7:
   - (a) correct the README trust claim (ruling 19);
   - (b) add --cli-connect-timeout and --cli-read-timeout to every aws call in scripts/emr/step.sh and in the bootstrap string of run-bounded-pipeline.sh;
   - (c) make the EMR security-group claim conditional, and add a read-only post-cluster check to run-bounded-pipeline.sh: describe-security-groups for both EMR groups, warn loudly and record any 0.0.0.0/0 or ::/0 ingress.
7. F8 and the ruling 16 items: fix all stale text:
   - variables.tf:397;
   - load-stores.sh:43-44;
   - run-bounded-pipeline.sh:18-20 and :98;
   - scripts/README.md:207, :304 and :313-317;
   - infra/terraform/README.md:36-37 and :218-221.
   The run-bounded-pipeline.sh --dry-run prints each resolved input path with its size and sha256.
8. F9: script evidence logs under operations/ are written as .txt, and scripts/README.md matches.
9. Ruling 18: haiku_destination_regions is ["us-east-1", "us-east-2", "us-west-2"] in course.tfvars and in the variable default.
10. Validation:
   - terraform fmt -check, the ruling 5 init and validate;
   - bash -n on every script;
   - the --dry-run of run-bounded-pipeline.sh --fraction 25 and of deploy.sh (fixture plan);
   - scripts/check.sh once on the final tree, after Parts A and B (it includes sbt check and the dataset validation).
   Report the expected plan counts if they changed.

## Human rulings after code review 2

22. Code review 2 returned APPROVE. Dispositions:
   - The human placed docs/agent-runs/phase-12/evidence/code-review-1/ (a copy of the code review 1 change set, saved before the scratchpad could be wiped).
   - R1 is decided now, before cloud window 1. Repair-02, a human exception to ruling 20 like Phase 11 ruling 20, adds exact-key GetObject grants for the parent keys that an S3A mkdirs or getFileStatus walk can probe. Those keys hold no data. A failure here would cost cloud window 1 a re-plan, a re-apply and a rerun.
   - N1, N2 and the F9 remainder (experiments/results *.log) are fixed in repair-02, because window 2's experiments need committed evidence.
   - N3 is a residual risk, superseded by the first window 1 run's security-groups.json.
   - R2 to R6 are residual risks recorded at the gate. For R3, every pipeline run record quotes emrSecurityGroups.openIngress.
   - The Phase 13 gate item "README uses the scripts" is a Phase 17 documentation-engineer item.
   - Repair-02 and code review 3 run in one session, without Ponytail (ruling 9 requires that only of code-reviewer). Code review 3 reviews only the repair-02 changes. A REQUEST_CHANGES exits to human escalation.

## Repair-02 (human exception, ruling 22)

Report to docs/agent-runs/phase-12/repair-02.md. Rulings 8 and 10 constraints apply.

### Part A: infra-engineer (R1, N1, N2, F9 remainder)
Edit only infra/** and scripts/**.
1. R1. In infra/terraform/modules/emr/main.tf, add s3:GetObject on exact object ARNs (no wildcard) for every parent key above each EMR write root that an S3A mkdirs or getFileStatus walk can probe on a bucket where the prefix is still empty: at least <bucket>/curated and <bucket>/logs/emr. Rules:
   - add no ListBucket grant without a prefix condition, and widen nothing else;
   - explain in a comment why these keys are needed and that they hold no data;
   - update infra/terraform/README.md (IAM summary, about :229);
   - show the final policy statements with Grep.
2. N1: the loader command block in infra/terraform/README.md (about :209-215) adds --stage-from-bucket, matching scripts/load-stores.sh:171.
3. N2: python3 is a documented prerequisite: check.sh:24, the `need` list at :35, and scripts/README.md.
4. F9 remainder: run-experiment.sh writes stdout and stderr as .txt, and scripts/README.md matches.
5. Validation:
   - terraform fmt -check, the ruling 5 init and validate;
   - bash -n on the changed scripts, and run-experiment.sh --help;
   - scripts/check.sh once on the final tree (it includes sbt check and the dataset validation).
   The plan counts should be unchanged (policy content only); say so or report the change.

## Code review 3: code-reviewer

Review only the repair-02 changes. Check them against ruling 22, repair-02 and code review 2 (R1, N1, N2, F9 remainder). Confirm each item RESOLVED or OPEN, with file and line. Also confirm that:
- the new grants are exact keys with no wildcard;
- they give no read access to any data object;
- no other permission changed.
Verify every finding against the working tree. Return APPROVE or REQUEST_CHANGES; under ruling 22, a REQUEST_CHANGES exits to human escalation. Do not edit any file.
