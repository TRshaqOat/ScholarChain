# ScholarChain operational scripts

Owner: infra-engineer (authoring only). Spec Phases 12 and 13, D-10, D-13, and the Phase 12 human
rulings 1 to 21 (`docs/agent-runs/phase-12/prompt.md`). The infra-engineer never runs a script against
AWS. Who runs each script is set by `docs/agents.md` section 8 and is repeated below.

## Conventions (every script)

- `set -euo pipefail`, bash 3.2 compatible (macOS), no interactive prompts: every choice is a flag.
- `--help` prints the full contract. `--dry-run` checks flags and local inputs, prints the steps, and
  makes no AWS, Terraform, git, or sbt call.
- Step markers: `==> STEP <name>`, `==> OK ...`, `==> FAIL ... (exit n)`, `==> WARN ...`, plus one
  result line per script (`==> PLAN`, `==> RELEASE`, `==> PACKAGE`, `==> CLUSTER`, `==> PIPELINE`,
  `==> SMOKE`, `==> ROLLBACK`, `==> STORES`, `==> EXPERIMENT`, `==> ARCHIVE`, `==> CLEANUP-PLAN`).
- Exit codes: 0 success; 1 a gate or verification failed; 2 usage error or missing tool;
  3 precondition (dirty tree, wrong account or Region, missing input or record); 4 approval mismatch
  (plan checksum); 5 waiting for a documented human action; 6 cloud-operation timeout.
- AWS: `--profile` (default `$AWS_PROFILE`) and `--region` (default and only allowed value
  `us-east-2`, D-04). Every AWS-calling script prints `==> TARGET account=... region=... profile=...`
  before acting, and checks that the Terraform state belongs to the same account.
- Every AWS CLI call has `--cli-connect-timeout 10 --cli-read-timeout 120`, including those in
  `emr/step.sh` and the step bootstrap that `run-bounded-pipeline.sh` submits. Reads and idempotent
  writes (same key and bytes, same desired state) use SDK standard retries (5 attempts). Calls that are
  not idempotent (create a cluster, start a build, run a task, register a task definition revision,
  force a new ECS deployment) make exactly one attempt. Long waits poll with a deadline (exit 6).
- No secret value is in source, flags, or output. The API key is read only by `smoke-test.sh`, into a
  variable, and reaches curl on standard input.
- Dirty tree: any change outside `operations/` and `experiments/results/` (the record trees the
  scripts themselves write), untracked files included, stops `plan.sh`, `deploy.sh`, `package.sh`,
  `run-bounded-pipeline.sh`, `load-stores.sh`, `run-experiment.sh`, and `cleanup.sh` (exit 3).
- sbt always runs as `SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g -batch ...`, and a script refuses to start
  sbt while another sbt process is running.
- Helpers live in `scripts/lib/common.sh`. `scripts/emr/step.sh` runs on the EMR primary node only.

## Where records go

| Record | Path | Committed? |
|---|---|---|
| Plan file, `plan.json`, `plan.txt` | `scratch/plans/<UTC stamp>-<commit 12>/` | no (`scratch/` is gitignored; `*.tfplan` too) |
| Plan summary (secret-free) | `operations/plans/<UTC stamp>-<commit 12>.md` | evidence |
| Package record | `operations/releases/package-<commit>.json` | evidence |
| Release, smoke, rollback records | `operations/releases/{release,smoke,rollback}-*.json` | evidence |
| Pipeline run records | `operations/pipeline-runs/<stamp>-fraction<p>/` | evidence |
| Store-load records | `operations/pipeline-runs/store-load-<stamp>/` | evidence |
| Experiment raw results | `experiments/results/<experimentId>/<stamp>/` (read-only after the run) | evidence |
| Cleanup plans and records | `scratch/plans/cleanup-*/`, `operations/cleanup/cleanup-*/` | plans no, records evidence |

`operations/**` belongs to the deploy-engineer and `experiments/**` to the experiment-engineer
(`docs/agents.md`); the scripts write there when those agents or the human run them. No new
`.gitignore` line is needed: plan files land under the ignored `scratch/`. Logs the scripts write
under `operations/` and `experiments/results/` end in `.txt`, never `.log`, because `.gitignore`
ignores `*.log`.

## The flow

```text
Cloud window 1 (runtime_enabled = false)
  human:            TF_VAR_alarm_email=... scripts/plan.sh --runtime-enabled false      -> sign the sha256
  deploy-engineer:  scripts/deploy.sh --plan-file <plan> --approved-plan-sha256 <sha>
  deploy-engineer:  scripts/package.sh                                                   -> package record
  deploy-engineer:  scripts/run-bounded-pipeline.sh --fraction 100 --package-record <rec> (then 50, 25)
Cloud window 2 (runtime_enabled = true; image_tag and the three runs committed in course.tfvars)
  human:            scripts/plan.sh --runtime-enabled true                               -> sign the sha256
  deploy-engineer:  scripts/deploy.sh --plan-file <plan> --approved-plan-sha256 <sha> --package-record <rec>
                    -> exit 5 until the human sets the API key secret value; then the same command + --resume
  human:            scripts/load-stores.sh --store-run <run> --vectors-run <run>
  deploy-engineer:  scripts/smoke-test.sh
  experiment-engineer: scripts/run-experiment.sh --manifest experiments/<m>.json
  human or deploy-engineer: scripts/archive-evidence.sh --out-dir <dir> [--metrics-from/--metrics-to]
  human:            scripts/cleanup.sh --stage runtime   (then --plan-file ... --confirm <sha>)
After the evidence is archived: cleanup.sh --stage infra; after the instructor approves the archive: --stage final
```

## check.sh

- **Purpose:** every non-cloud gate of a checkout (spec Phase 13 item 1).
- **Command:** `scripts/check.sh` (only `--help` besides).
- **Gates:** jq parses `.claude/settings.json` and `.mcp.json`; `.claude/hooks/validate-agent-files.sh`
  (agent files and hook self-tests); `bash -n` on `.claude/hooks/*.sh`, `scripts/**/*.sh`, and
  `datasets/scripts/*.sh`; `shellcheck -S error` when installed (otherwise reported SKIPPED);
  `bash datasets/scripts/validate.sh` (dataset manifest, samples, checksums, and the dataset script
  self-tests; run through `bash` because those files are not executable); `terraform fmt -check -recursive
  infra/terraform`; `terraform init -backend=false -lockfile=readonly` and `terraform validate`;
  `sbt check` (scalafmt check, Test/compile, every test, markTestRun). All gates run; exit 1 if any failed.
- **Prerequisites:** jq, bash, python3 (the dataset-validation gate), terraform 1.16.6, sbt, a JDK;
  the script stops with exit 2 when jq, python3, terraform, or sbt is missing.
- **Environment:** none. Network: the first terraform init downloads the pinned provider; sbt resolves
  pinned artifacts. No AWS credentials.
- **Runs it:** anyone, including agents (ruling 5). **Approval points:** none.
- **Evidence:** its output (step markers per gate). **Rollback:** not applicable.

## package.sh

- **Purpose:** job jars and the services image for one commit (spec Phase 13 item 2, ruling 3).
- **Command:** `scripts/package.sh [--profile p] [--region us-east-2] [--record-dir operations/releases] [--build-timeout-minutes 30] [--dry-run]`
- **Steps:** clean tree; `sbt packageJobs`, jar sha256 values, Hadoop `Main-Class` must be
  `scholarchain.hadoop.Pipeline`; `SCHOLARCHAIN_IMAGE_TAG=<commit> sbt services/Docker/stage` (no
  Docker), zip of the staged context with the Dockerfile at its root; write-once uploads to
  `artifacts/jobs/<commit>/` (jars, `emr/step.sh`) and `artifacts/codebuild/source/<commit>.zip`; one
  CodeBuild build with `IMAGE_TAG=<commit>` unless ECR already holds that tag (tags are immutable);
  digest and scan-on-push status from ECR.
- **Environment:** `AWS_PROFILE`. **Runs it:** the human or deploy-engineer after cloud window 1 is
  applied. **Approval points:** none beyond the applied window 1 plan (ECR and CodeBuild come from it).
- **Evidence:** `operations/releases/package-<commit>.json` (commit, jar keys and sha256 values, Docker
  context sha256, repository, tag, digest, build ID, scan status).
- **Rollback:** none needed: artifacts are additive and keyed by commit.

## plan.sh

- **Purpose:** the Terraform plan of the course environment; never applies (spec Phase 13 item 3).
- **Command:** `TF_VAR_alarm_email=<address or empty> scripts/plan.sh --runtime-enabled true|false [--var-file infra/terraform/envs/course/course.tfvars] [--profile p] [--region us-east-2] [--out-dir scratch/plans] [--evidence-dir operations/plans] [--dry-run]`
- **Steps:** terraform 1.16.6; clean tree; account and Region; `terraform init -input=false
  -lockfile=readonly` (local backend); `terraform plan -input=false -var-file=... -var
  runtime_enabled=... -var region=... -out=plan.tfplan`, printed in full and saved as `plan.txt`;
  `terraform show -json` (mode 600); sha256; the plan's account must equal the profile's.
- **Environment:** `AWS_PROFILE` (the human's `scholarchain-sdk`, which Terraform reads through its
  `credential_process`), `TF_VAR_alarm_email` (required by the configuration; never printed by the
  script, though the plan output shows it).
- **Runs it:** the human architect at the plan gate. **Approval points:** the human reviews the plan
  and signs its sha256; that checksum is the only approval `deploy.sh` accepts.
- **Evidence:** in `scratch/plans/<stamp>-<sha12>/`: `plan.tfplan`, `plan.sha256`, `plan.txt`,
  `plan.json`, `plan-meta.json` (commit, account, Region, runtime_enabled, var file and lock file
  sha256, counts), `summary.md` (counts; stateful destroys or replacements first; IAM changes;
  high-cost resources); `summary.md` is copied to `operations/plans/`.
- **Rollback:** not applicable (nothing changes).

## deploy.sh

- **Purpose:** apply exactly one approved saved plan and verify the deployed revision (ruling 6).
- **Command:** `scripts/deploy.sh --plan-file scratch/plans/<dir>/plan.tfplan --approved-plan-sha256 <sha> [--package-record operations/releases/package-<sha>.json] [--profile p] [--region us-east-2] [--release-dir operations/releases] [--stable-timeout-minutes 20] [--resume] [--dry-run]`
- **Gates:** sha256 of the plan file must equal `--approved-plan-sha256` (else exit 4, nothing
  applied); clean tree; HEAD, account, and Region equal the plan's (`plan-meta.json`); `terraform apply
  <plan file>` (a saved plan needs no prompt; `-auto-approve` is never used; no other input is
  accepted). With runtime_enabled: the API key secret must have a version (DescribeSecret; the value
  is never read), else exit 5; `--resume` (no apply) forces a new deployment of all three services;
  bounded wait until every service has one COMPLETED deployment and running = desired; every running
  task's image digest equals the package record's, and its task definition names
  `<repository>:<package tag>`; query-api and the worker log the same runtime run ID.
- **Environment:** `AWS_PROFILE`. **Runs it:** deploy-engineer (`/deploy`). **Approval points:** the
  human's signed plan checksum and approved environment (escalations 4 and 5); the human sets the API
  key secret value between the window 2 apply and `--resume`.
- **Evidence:** `operations/releases/release-<stamp>-<sha12>.json` (commit, plan sha256, account,
  Region, image tag and digest, per-service task definition ARN and running digest, runtime run IDs),
  `.apply.txt` (full apply output), `.outputs.json` (terraform outputs; no secret values).
- **Rollback:** a failing ECS deployment is rolled back by the deployment circuit breaker; otherwise
  `rollback.sh` to the previous release record. Terraform changes are not reverted automatically;
  a revert is a new plan through `plan.sh`.

## rollback.sh

- **Purpose:** move the three services back to the image of a known-good release record, with no
  Terraform apply.
- **Command:** `scripts/rollback.sh --to-release operations/releases/release-<stamp>-<sha12>.json [--reason "<text>"] [--profile p] [--region us-east-2] [--stable-timeout-minutes 20] [--record-dir operations/releases] [--dry-run]`; `scripts/rollback.sh --list` (local records only).
- **Steps:** the record must be a runtime release of this account and Region; ECR must hold its tag
  with its digest; for query-api, graph-evidence-api, and the worker together: copy the current task
  definition, change only the image, register a new revision (one attempt), update the service;
  bounded stable wait; digest and runtime run ID checks.
- **Environment:** `AWS_PROFILE`. **Runs it:** deploy-engineer for its own failing in-flight
  deployment; the incident-responder for a live revision (`/rollback`). **Approval points:** the human
  approves each AWS mutation at the permission prompt.
- **Evidence:** `operations/releases/rollback-<stamp>.json` (previous and new task definitions, image,
  reason, verified digests, run IDs).
- **Rollback:** roll forward with `rollback.sh` to a newer release record, or plan and deploy.
  Terraform state still names the newer image until `image_tag` in `course.tfvars` is changed and
  applied; the next plan shows that drift.

## smoke-test.sh

- **Purpose:** prove the deployed runtime answers a real bounded collaboration query with evidence
  (spec Phase 13 item 5).
- **Command:** `scripts/smoke-test.sh [--profile p] [--region us-east-2] [--base-url http://<alb>] [--question "<text>"] [--record-dir operations/releases] [--dry-run]`
- **Checks:** `/healthz` 200 ok; `/readyz` 200 ready; sync `POST /v1/query` (default: benchmark B07,
  "Have Jack J. Dongarra and Yves Robert coauthored a paper?") with a fresh Idempotency-Key returns 200,
  a `qry-` ID, and a claim with an evidence ID; `GET /v1/evidence/<id>` returns that evidence; the same
  POST and key replay the same queryId and answerHash; a POST without `X-Api-Key` is 401 UNAUTHORIZED.
  GETs are retried; POSTs are not.
- **Environment:** `AWS_PROFILE`. **Runs it:** deploy-engineer after `deploy.sh`; the
  incident-responder after a rollback. **Approval points:** none (read-only apart from one query,
  bounded by the 8 Haiku calls per minute cap).
- **Evidence:** `operations/releases/smoke-<stamp>.json` and the response bodies in
  `operations/releases/smoke-<stamp>/` (no key; answers and evidence only).
- **Rollback:** none; a failure sends the deploy-engineer to the rollback gate.

## run-bounded-pipeline.sh

- **Purpose:** one bounded Hadoop and Spark run on an ephemeral EMR cluster (spec Phase 13 item 6, D-13
  size scaling).
- **Command:** `scripts/run-bounded-pipeline.sh --fraction 25|50|100 --package-record operations/releases/package-<sha>.json [--reducers 2] [--shuffle-partitions 16] [--hadoop-timeout-minutes 90] [--spark-timeout-minutes 150] [--record-dir operations/pipeline-runs] [--profile p] [--region us-east-2] [--dry-run]`
- **Steps:** clean tree, HEAD = the package commit, local jars and `emr/step.sh` hash to the record;
  ruling 2 bounds re-checked from the `emr` output (emr-spark-8.0.0, 1 primary, 1 to 2 core, Graviton,
  on-demand); write-once uploads of the five inputs to
  `raw/<dataset>/snapshot=<d>/run=<input run>/` (input run = hash of the fraction and the file hashes)
  and of the jars; one `create-cluster` (no retry, `--auto-terminate`, tag
  `for-use-with-amazon-emr-managed-policies=true` that the v2 EMR service policy requires) with three
  steps through `command-runner.jar` and `emr/step.sh`:
  1. `classpath-probe` (CONTINUE): Java, Hadoop, Spark versions and the Typesafe Config, AWS SDK v2,
     and Scala entries on the cluster classpath (the Phase 11 check);
  2. `hadoop-pipeline` (TERMINATE_CLUSTER): `hadoop jar` with Main-Class `scholarchain.hadoop.Pipeline`,
     which runs J01, J02, J03+J04, J05 p0..p2, J06, J10, J07+J08, J09 in the documented order with
     `--out s3a://<bucket>/curated/hadoop --code-version <commit> --dblp ... --dblp-dtd ...
     --dblp-snapshot 2026-10-03 --openalex ... --crossref ... --orcid ... (snapshot 2026-11-03)
     --reducers <n>`, killed at its timeout;
  3. `spark-pipeline` (TERMINATE_CLUSTER): finds the one new Hadoop run directory, then
     `spark-submit --master yarn --class scholarchain.spark.PipelineJob` (jobs 1 to 11) with
     `--input <that run> --out s3a://<bucket>/curated/spark --checkpoint-dir
     hdfs:///scholarchain/spark/checkpoints --code-version <commit> --shuffle-partitions <n>`, killed at
     its timeout.
  Then a bounded wait for TERMINATED; collection of step timings, normalized instance hours, the new
  run directories and their `_manifest.json`, and the step logs (bounded wait for EMR to push them);
  then a read-only `ec2 describe-security-groups` of both EMR-managed groups. Terraform ignores their
  rule drift, so this is the only check that EMR added no `0.0.0.0/0` or `::/0` ingress: any such rule
  is printed as `==> WARN EMR GROUP OPEN TO THE INTERNET ...` and recorded; the script changes no rule.
- **Inputs:** fraction 100 uses the full local subset (`scratch/datasets/dblp/snapshot=2026-10-03/full/works.xml`,
  `raw/dblp.dtd`, `scratch/datasets/{openalex,crossref}/full/works.jsonl`,
  `scratch/datasets/orcid/full/records.jsonl.gz`). Fractions 25 and 50 read
  `scratch/datasets/fraction=<p>/{dblp/works.xml,openalex/works.jsonl,crossref/works.jsonl,orcid/records.jsonl.gz}`,
  written by `datasets/scripts/make_fraction.sh` (rule fraction-v1, nested 25 within 50 within 100;
  `datasets/schemas/fractions.md`); a missing file stops the script with exit 3. Every resolved input
  path is printed with its size and sha256 (step `inputs`, also in `--dry-run`).
- **Environment:** `AWS_PROFILE`. EMR jobs use the core configuration defaults (environment `local`
  semantics for thresholds and windows); paths are arguments.
- **Runs it:** deploy-engineer (`/run-pipeline`) or the human. **Approval points:** the applied window 1
  plan and the bounds in it; anything beyond them is a human-gated scale change.
- **Evidence:** `operations/pipeline-runs/<stamp>-fraction<p>/`: `run.json` (commit and code version,
  fraction, snapshots, input keys with sha256 and bytes, input run, configuration, cluster ID, final
  state and reason, step names, states and timestamps, normalized instance hours, output run
  directories, `emrSecurityGroups` with any open ingress), `steps.json` as submitted, `cluster.json`,
  `steps-result.json`, `security-groups.json`, `input-hashes.txt`, `manifests/`, `logs/`.
- **Rollback:** nothing to roll back: outputs are new write-once run directories and the cluster
  terminates itself. On timeout (exit 6) the cluster ID is printed; terminating a stuck cluster is a
  human action.

## load-stores.sh

- **Purpose:** the cloud window 2 store loads, in order: S3, Neptune, DynamoDB, S3 Vectors.
- **Command:** `scripts/load-stores.sh --store-run run-<32 hex> --vectors-run run-<32 hex> [--root scratch/stores/s3] [--vectors-root <root>] [--window-summary-run run-6fbad498beb0d8f570df67665fb97544] [--window-summary-root scratch/runtime/wo8/s3] [--dynamo-concurrency 8] [--neptune-timeout-minutes 60] [--record-dir operations/pipeline-runs] [--profile p] [--region us-east-2] [--dry-run]`
- **Steps:** clean tree; account and Region; runtime outputs present; the laptop loaders get exactly the
  tasks' `SCHOLARCHAIN_*` variables from the `runtime_environment` output.
  1. `LoadS3Cli --manifest <m> --root <root> --live --profile <p> --region <r>` for the store-conversion
     run, the embedding run (vectors backup and rendered text), and the window-summary load run.
  2. Neptune through the in-VPC path: `aws ecs run-task` of `scholarchain-course-neptune-loader` with
     the command `-main scholarchain.storage.cli.LoadNeptuneCli --live --stage-from-bucket --dir
     /tmp/stores/s3/graph/neptune/snapshot=2026-10-03/run=<store-run> --out /tmp/stores` (task role, no
     profile; the loader role ARN comes from `SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN`), the load ID from the
     task log, then a `--live --status <loadId>` task every 2 minutes until `LOAD_COMPLETED` (bounded).
  3. `LoadDynamoCli --dir <root>/curated/dynamodb/snapshot=2026-10-03/run=<store-run> --live --profile <p> --region <r> --concurrency <n>` (about 876k conditional puts).
  4. `LoadVectorsCli --input <vectors-root>/vectors/backup/snapshot=2026-10-03/run=<vectors-run> --live --profile <p> --region <r>`.
- **Environment:** `AWS_PROFILE` (LiveAws reads the named profile). **Runs it:** the human (the storage
  live loaders are human-run, storage rulings 1 and 26). **Approval points:** the applied window 2 plan.
- **Evidence:** `operations/pipeline-runs/store-load-<stamp>/`: one log per loader as `<step>.txt`
  (each prints its counts), the Neptune task logs as `neptune-<start|status>-<task>.txt`, `run.json` (runs, Neptune load ID, per-step status and seconds).
- **Rollback:** none automatic: every load is write-once and a rerun skips loaded data. Removing loaded
  data is a human-gated destructive action; ending window 2 destroys the stores.

## run-experiment.sh

- **Purpose:** run one approved, committed experiment manifest (spec Phase 13 item 7). No manifest is
  written in Phase 12.
- **Command:** `scripts/run-experiment.sh --manifest experiments/<path>.json [--results-root experiments/results] [--profile p] [--region us-east-2] [--dry-run]`
- **Manifest (`experiment-manifest-v1`, JSON):** `experimentId`, `description`, `command` (first element
  a tracked executable under `experiments/` or `load-tests/`), `limits` (`maxMinutes` 1 to 240 is
  enforced; every limit is recorded), `metrics` (CloudWatch namespace, metric, dimensions).
- **Gates:** the manifest is tracked and identical to HEAD; clean tree; the command is tracked; account
  and Region printed; the command is stopped at `maxMinutes` (TERM, then KILL after 30 s).
- **Environment:** `AWS_PROFILE`; the command receives `SCHOLARCHAIN_EXPERIMENT_ID`,
  `SCHOLARCHAIN_EXPERIMENT_DIR`, `AWS_PROFILE`, `AWS_REGION`.
- **Runs it:** experiment-engineer (`/experiment`). **Approval points:** the human approves and commits
  the manifest before the run.
- **Evidence:** `experiments/results/<experimentId>/<stamp>/`: `raw/`, `stdout.txt`, `stderr.txt`,
  `run-record.json` (commit, manifest and its sha256, limits, start and end, exit code, account,
  Region, metric locations), made read-only.
- **Rollback:** none; the experiment's command must stay inside the approved bounds.

## archive-evidence.sh

- **Purpose:** one versioned evidence directory (spec Phase 13 item 8).
- **Command:** `scripts/archive-evidence.sh --out-dir <new dir> [--plans-dir scratch/plans] [--metrics-from <ISO UTC> --metrics-to <ISO UTC>] [--s3-prefix reports/evidence/<name>/] [--profile p] [--region us-east-2] [--dry-run]`
- **Gathers:** sbt JUnit reports, plan checksums and summaries (never the plan file or JSON), the
  `operations/` records (image digests, releases, smoke, rollbacks, pipeline and store-load runs with
  their manifests), `experiments/results/`, optional CloudWatch GetMetricData exports (ECS CPU and
  memory per service, ALB requests, target 5xx and p90 latency, queue and DLQ depth, Neptune CPU),
  `index.json`, and `SHA256SUMS`. The directory must not exist; an S3 prefix must be empty.
- **Environment:** `AWS_PROFILE` only for metrics or S3. **Runs it:** the human or deploy-engineer.
  **Approval points:** none (it writes only the new directory and the empty prefix).
- **Evidence:** the archive itself. **Rollback:** delete the new directory.

## cleanup.sh (human only)

- **Purpose:** the documented cleanup path (spec "Cleanup requirements", ruling 6). No agent runs it;
  `guard-dangerous.sh` blocks direct agent invocations (not every indirect form; see the work order 3
  report).
- **Command, first invocation:** `TF_VAR_alarm_email=... scripts/cleanup.sh --stage runtime|infra|final [--evidence-archive <location>] [--var-file ...] [--out-dir scratch/plans] [--record-dir operations/cleanup] [--profile p] [--region us-east-2] [--dry-run]`.
  It writes the inventory, a plan printed in full, every resource it destroys, every resource it keeps
  with the reason, and the plan sha256. Nothing changes.
- **Command, second invocation:** `scripts/cleanup.sh --plan-file scratch/plans/cleanup-<stamp>-<stage>/plan.tfplan --confirm <sha256>`.
  It checks the checksum, HEAD, and account, applies the saved plan, takes the second inventory, and
  writes `record.json` listing every retained resource and why.
- **Stages and what is kept:**

| Stage | Destroys | Keeps |
|---|---|---|
| `runtime` (end of window 2) | Neptune, DynamoDB tables, S3 Vectors bucket and indexes, SQS, the API key secret (7-day recovery window), ECS services and task definitions, ALB, runtime security groups and IAM roles, alarms, dashboard | network, data bucket, ECR, EMR roles and groups, CodeBuild, log groups, Terraform state |
| `infra` (evidence archived) | network, EMR roles and groups, CodeBuild and its role, any runtime module left | data bucket (grading evidence), ECR repository and images, every log group, Terraform state |
| `final` (needs `--evidence-archive`, the instructor-approved location) | everything in state | Terraform state; the data bucket and ECR repository survive unless already empty (`force_destroy` and `force_delete` are false; the script never empties them) |

  Ephemeral EMR clusters are listed in the inventory, never terminated by the script. KMS: no
  customer-managed key exists (ruling 1).
- **Environment:** `AWS_PROFILE`, `TF_VAR_alarm_email`. **Approval points:** the `--confirm` checksum
  is the documented human confirmation. **Evidence:** `operations/cleanup/cleanup-<stamp>-<stage>/`
  (inventories before and after, `apply.txt`, retained list, record).
- **Rollback:** a destroyed runtime is recreated by `plan.sh --runtime-enabled true` and `deploy.sh`,
  then `load-stores.sh` from the write-once S3 files.

## Dependencies (Phase 12 work orders 3 to 8, all complete)

- **dataset-engineer (work order 7):** `datasets/scripts/make_fraction.sh` writes the 25 and 50 percent
  subsets under `scratch/datasets/fraction=<p>/` (rule fraction-v1, `datasets/schemas/fractions.md`);
  `run-bounded-pipeline.sh` reads that layout.
- **storage-engineer (work order 5):** `LoadNeptuneCli --live` without `--profile`, with
  `--stage-from-bucket`; `load-stores.sh` step 2 uses that form.
- **service-engineer and domain-engineer (work orders 6 and 4):** `--profile` is optional in the
  services launcher, and the core loader honours `SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE`; the ECS task
  definitions use both.
