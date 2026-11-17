# Phase 14 agent reports

## Work order 1, attempt 1 (deploy-engineer, FAILED; see ruling 11)

# Window 1 deployment record (Phase 14, work order 1)

```text
STATUS: FAILED
OWNER: deploy-engineer
WORK ORDER: Phase 14 work order 1 (cloud window 1). Deploy approved commit d33ce8e8d507145cd46fb39bf319e985bc9a428a and the window 1 Terraform plan (runtime_enabled=false, 43/0/0) to the course environment (account 834519512655, us-east-2, profile scholarchain-sdk) under Phase 14 rulings 1 to 5.
FILES CREATED:
  - operations/deployments/window-1-20261116T061720Z.md (this record)
  - deploy.sh itself wrote operations/releases/release-20261116T061659Z-d33ce8e8d507.apply.txt (322 bytes, the failed apply output). No release-*.json and no .outputs.json were written, because the script stopped before those steps.
FILES MODIFIED: none
APPROVAL REFERENCE: operations/plans/20261116T060222Z-d33ce8e8d507-APPROVED.txt (approved by Achyut Patel, 2026-11-16 00:12 CST), docs/agent-runs/phase-12/human-gate.md (plan signed), Phase 12 code review 4 APPROVE for a5b3df8 (docs-only diff since then, ruling 4).
PRECONDITIONS (all observed in deploy.sh output; ruling 5 forbids my own git, aws, and terraform calls):
  - Plan checksum: "==> OK plan checksum approved: 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63" (dry run and real run). plan-meta.json names the same plan.
  - Clean tree and SHA: "==> OK clean-tree at d33ce8e8d507145cd46fb39bf319e985bc9a428a". This equals the approved commit and the plan's commit.
  - Account and Region: "==> TARGET account=834519512655 region=us-east-2 profile=scholarchain-sdk"
  - Script files unchanged at the commit: covered by the clean-tree gate. I ran no separate git check (ruling 5).
SCRIPTS EXECUTED:
  1. ./scripts/deploy.sh --plan-file scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan --approved-plan-sha256 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63 --profile scholarchain-sdk --dry-run
     -> exit 0
  2. ./scripts/deploy.sh --plan-file scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan --approved-plan-sha256 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63 --profile scholarchain-sdk
     -> exit 1 at "==> STEP apply". Run once, in the foreground. Not rerun.
     Terraform output (also in the .apply.txt):
       Error: Failed to load "scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan" as a plan file
       Error: stat scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan: no such file or directory
DEPLOYED STATE:
  - Nothing was applied. Terraform failed while loading the plan file, before any provider or backend operation, so no resource was created. The release stamp was 20261116T061659Z.
  - This conclusion comes from the output only. I made no AWS or Terraform call to confirm it (ruling 5).
  - Image digests, task revisions, service stabilization, health, readiness, and alarms: window 2.
PIPELINE RUN: not applicable (work order 1 is deploy.sh only)
SMOKE RESULTS: window 2 (smoke-test.sh is not run in window 1)
ROLLBACK ACTION: none. Nothing was applied, so there is nothing to roll back. The window 1 policy is to stop and report.
COMMANDS RUN:
  - The two deploy.sh invocations above.
  - Read-only local inspection to collect evidence:
    - ls of operations/ and operations/plans/
    - ls of operations/releases/
    - grep of scripts/README.md, scripts/lib/common.sh, scripts/plan.sh, scripts/deploy.sh, and scripts/cleanup.sh
    - date -u for this record's stamp
  - No git, aws CLI, mcp__aws-api, or terraform command was run directly.
TEST RESULTS: not applicable (no tests are in scope for a deployment work order)
VALIDATION RESULTS:
  - Gates 1 to 3 passed (checksum, clean tree and HEAD, account and Region).
  - Gate 4 (terraform apply) failed.
  - Required verification of release-*.json, .apply.txt "43 added, 0 changed, 0 destroyed", and .outputs.json: NOT POSSIBLE. The release JSON and outputs JSON do not exist, and the .apply.txt contains only the load error.
ASSUMPTIONS:
  - Root cause, from the script source (read-only):
    - scripts/lib/common.sh:108 defines tf() as terraform -chdir="$SC_TF_DIR" "$@", where SC_TF_DIR is <repo>/infra/terraform/envs/course.
    - scripts/deploy.sh:121 passes --plan-file to terraform unchanged.
    - So a repo-relative plan path (the form documented in scripts/README.md and used in this work order) is resolved against infra/terraform/envs/course, where the file does not exist.
    - deploy.sh's own gates (the -f test, the sha256, plan-meta.json) resolve the same path from the repo root, so they pass.
    - plan.sh writes the plan with an absolute path ($SC_ROOT/scratch/plans), so it does not have this defect.
  - The saved plan should still be applicable, because the state was not changed. This is an inference I did not verify.
RISKS:
  - scripts/cleanup.sh:128 uses the same pattern (tf apply ... "$PLAN_FILE"), so it likely has the same defect with a relative --plan-file. Not run.
  - Window 2 deploy.sh has the same defect.
  - The window 1 timeline (package.sh and the 25/50/100 runs) is blocked until a ruling is made.
MANUAL ACTION REQUIRED: a human ruling on the path forward. Options for the human (I chose and executed none of them):
  (a) infra-engineer fixes deploy.sh (and cleanup.sh) to make --plan-file absolute before calling tf. That needs a code review, a new commit, a fresh plan, and a new approval record.
  (b) A new ruling allows the same deploy.sh command with an absolute --plan-file (/Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan). This uses the same plan, checksum, and commit, with no code change. The defect is still routed to infra-engineer.
  (c) Ruling 2 fallback: the human runs the command from Terminal.
  Also decide whether the stray operations/releases/release-20261116T061659Z-d33ce8e8d507.apply.txt is kept as failure evidence. It is inside operations/, so it does not stop package.sh or the pipeline runs.
BLOCKED-ON OWNER: infra-engineer (deploy.sh plan-path defect), then human architect (ruling for rerun)
BLOCKED-ON EVIDENCE: deploy.sh exit 1. Terraform reported "stat scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan: no such file or directory" under -chdir=infra/terraform/envs/course (scripts/lib/common.sh:108, scripts/deploy.sh:121).
NEXT GATE: human acceptance of the deployment or run record (this failure record) and a new ruling before any rerun
```

## Work order 1, attempt 2 (deploy-engineer, COMPLETE)

# Window 1 deployment record (Phase 14, work order 1, rerun under ruling 11)

```text
STATUS: COMPLETE
OWNER: deploy-engineer
WORK ORDER: Phase 14 work order 1 (cloud window 1), rerun under human ruling 11 (option b). Deploy approved commit d33ce8e8d507145cd46fb39bf319e985bc9a428a and the window 1 Terraform plan (runtime_enabled=false, 43/0/0) to the course environment (account 834519512655, us-east-2, profile scholarchain-sdk), using the absolute plan path. Phase 14 rulings 1 to 5 apply otherwise.
FILES CREATED:
  - operations/deployments/window-1-20261116T062456Z.md (this record; the only file I wrote)
  - deploy.sh itself wrote these files (release stamp 20261116T062349Z):
    - operations/releases/release-20261116T062349Z-d33ce8e8d507.json (500 bytes)
    - operations/releases/release-20261116T062349Z-d33ce8e8d507.apply.txt (12225 bytes, 156 lines)
    - operations/releases/release-20261116T062349Z-d33ce8e8d507.outputs.json (4033 bytes, mode 0600)
  - These evidence files from the earlier attempt are unchanged:
    - operations/deployments/window-1-20261116T061720Z.md
    - operations/releases/release-20261116T061659Z-d33ce8e8d507.apply.txt (still 322 bytes, timestamp 00:16)
FILES MODIFIED: none
APPROVAL REFERENCE:
  - operations/plans/20261116T060222Z-d33ce8e8d507-APPROVED.txt (approved by Achyut Patel, 2026-11-16 00:12 CST)
  - docs/agent-runs/phase-12/human-gate.md (plan signed)
  - operations/rulings/phase-14-ruling-11.txt (option b: same command with the absolute --plan-file; approved by Achyut Patel)
  - Code review gate: Phase 12 code review 4 APPROVE for a5b3df8. The code diff since then is docs-only (ruling 4; the approval record's diff check).
PRECONDITIONS (observed in deploy.sh output, plus a local checksum; ruling 5 forbids my own git, aws, and terraform calls):
  - Plan checksum:
    - Local shasum -a 256 of the plan file = 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63. This equals the approved value, plan.sha256, and plan-meta.json planSha256.
    - deploy.sh: "==> OK plan checksum approved: 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63" (dry run and real run).
  - Clean tree and SHA: "==> OK clean-tree at d33ce8e8d507145cd46fb39bf319e985bc9a428a". This equals the approved commit and plan-meta.json commit.
  - Account and Region: "==> TARGET account=834519512655 region=us-east-2 profile=scholarchain-sdk". This equals plan-meta.json (account 834519512655, us-east-2).
  - Script files unchanged at the commit: covered by the clean-tree gate. I ran no separate git check (ruling 5).
  - Expected counts from plan-meta.json: add 43, change 0, destroy 0.
SCRIPTS EXECUTED:
  1. ./scripts/deploy.sh --plan-file /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan --approved-plan-sha256 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63 --profile scholarchain-sdk --dry-run
     -> exit 0 ("==> OK dry-run"). The dry run listed terraform apply with the absolute plan path.
  2. ./scripts/deploy.sh --plan-file /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan --approved-plan-sha256 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63 --profile scholarchain-sdk
     -> exit 0. Run once, in the foreground, with Bash timeout 600000 ms. Not rerun.
     The script passed these steps: approval, clean-tree, target, apply ("Apply complete! Resources: 43 added, 0 changed, 0 destroyed."), terraform-outputs ("OK outputs for account 834519512655"), and record.
     Final line: "==> OK deploy complete; next: scripts/smoke-test.sh". smoke-test.sh is window 2 and was not run.
DEPLOYED STATE (from deploy.sh output and the files it wrote):
  - Release record operations/releases/release-20261116T062349Z-d33ce8e8d507.json:
    - commit d33ce8e8d507145cd46fb39bf319e985bc9a428a
    - planSha256 9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63
    - account 834519512655, region us-east-2, environment course
    - deployedAt 2026-11-16T06:24:50Z
    - runtimeEnabled false, resumed false, image null, services [], runtimeRunIds {}, verified true
    - All of these match the approval.
  - .apply.txt line 104: "Apply complete! Resources: 43 added, 0 changed, 0 destroyed." This matches the expected 43/0/0.
  - .outputs.json (every output has "sensitive": false; a grep for secret|password|token|AKIA|private_key found nothing in the .json or the .outputs.json):
    - data_bucket_name: scholarchain-course-data-834519512655-us-east-2
    - ecr_repository_name: scholarchain-course-services
    - ecr_repository_url: 834519512655.dkr.ecr.us-east-2.amazonaws.com/scholarchain-course-services
    - codebuild_project_name: scholarchain-course-services-image (ARN arn:aws:codebuild:us-east-2:834519512655:project/scholarchain-course-services-image)
    - codebuild_source_prefix: artifacts/codebuild/source/
    - emr:
      - release_label emr-spark-8.0.0
      - instance_type m6g.xlarge, primary_instance_count 1, core_instance_count 2
      - service_role scholarchain-course-emr-service
      - instance_profile scholarchain-course-emr-instance
      - subnet_id subnet-01ba5443d067a76f4
      - primary_security_group_id sg-06850b56e702003b3
      - core_security_group_id sg-045cdc6a597240486
      - log_uri s3://scholarchain-course-data-834519512655-us-east-2/logs/emr/
      - artifacts_prefix artifacts/jobs/
    - vpc_id vpc-0fd8e31fa7b48c139
    - public_subnet_ids subnet-01ba5443d067a76f4, subnet-0e94ae0d8ed9a3c09
    - private_subnet_ids subnet-08e8a7e37da4af95f, subnet-0d62d88d3791db7bc
    - log_group_names: codebuild, graph-evidence-api, neptune-loader, query-api, worker (all under /scholarchain/scholarchain-course/)
    - region us-east-2, runtime_enabled false, name_prefix scholarchain-course
  - In the apply output, every resource was "Creating" or "Creation complete". There was no destroy, replace, or update line.
  - Running image digests: window 2
  - Task revisions: window 2
  - Service stabilization, health, and readiness: window 2
  - Alarms: window 2
PIPELINE RUN: not applicable (work order 1 is deploy.sh only; the pipeline runs are the human's, ruling 2)
SMOKE RESULTS: window 2 (smoke-test.sh is not run in window 1)
ROLLBACK ACTION: none. The deployment succeeded, and the window 1 policy is to stop and report on failure only.
COMMANDS RUN:
  - The two deploy.sh invocations above.
  - Read-only local inspection:
    - ls of operations/ and its subdirectories, and of the plan directory
    - grep and sed of scripts/README.md (deploy.sh section)
    - cat of plan-meta.json and plan.sha256
    - shasum -a 256 of plan.tfplan
    - test -x scripts/deploy.sh
    - grep of the new .apply.txt for "added" and of the new release .json and .outputs.json for secret-like keys
    - date -u for this record's stamp
  - Read tool: ruling 11, the phase-14 prompt, the approval record, phase-12 human-gate.md, the earlier failure record, the new release .json, .outputs.json, and .apply.txt (lines 100-107).
  - No git, aws CLI, mcp__aws-api, or terraform command was run directly. The aws-api MCP server also failed to connect in this session; it was not used.
TEST RESULTS: not applicable (no tests are in scope for a deployment work order)
VALIDATION RESULTS:
  - Gates 1 to 4 passed: checksum, clean tree and HEAD, account and Region, terraform apply.
  - Outputs and record steps passed.
  - Release JSON: commit, plan sha256, account 834519512655, and us-east-2 all match. PASS.
  - .apply.txt: "43 added, 0 changed, 0 destroyed". PASS.
  - .outputs.json: data bucket, ECR repository, CodeBuild project, and the emr object are present, with no secret values. PASS.
ASSUMPTIONS:
  - The clean-tree gate in deploy.sh stands in for law 2's git checks (ruling 5).
  - deploy.sh's TARGET line stands in for my own sts check (ruling 5).
  - I did not verify the created resources independently in AWS. The evidence is the Terraform apply output and the files deploy.sh wrote.
RISKS:
  - Terraform state is local. deploy.sh printed: "state stays local (ruling 1): back up terraform state outside the repository after this step". I did not touch the state (agents may not read it).
  - The EMR subnet in the outputs (subnet-01ba5443d067a76f4) is one of the public subnets. This matches the reviewed plan. Ruling 7's "EMR GROUP OPEN TO THE INTERNET" watch applies to the pipeline runs.
  - Defect P14-D1 (relative --plan-file in deploy.sh and cleanup.sh) is still open. Per ruling 11 it is routed separately; I did not act on it.
  - .outputs.json is mode 0600 as written by the script. The human's commit after the 100% run should still include it.
MANUAL ACTION REQUIRED:
  - Back up the local Terraform state outside the repository (deploy.sh instruction, ruling 1).
  - Continue the window 1 timeline under rulings 6 to 8: scripts/package.sh, then run-bounded-pipeline.sh at fractions 25, 50, and 100.
  - The orchestrator does not write docs/agent-runs/phase-14/agent-report.md during the window (ruling 5).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human acceptance of the deployment or run record
```

## Work order 5 (experiment-engineer)

```
Experiments D and F are ready for the human to commit. Experiment E is BLOCKED-ON: the deployed query-api has no way to select a retrieval mode, so I wrote no E manifest and no E harness. Nothing ran against AWS. No aws, terraform, git, sbt, MCP or network call was made.

STATUS: BLOCKED-ON (E only; D and F are complete)
OWNER: experiment-engineer
WORK ORDER: Phase 14 work order 5, manifests and harnesses for experiments D, E and F (cloud window 2), rulings 23-30

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/manifests/exp-d-query-load-v1.json
- /Users/achyutpatel/Developer/Cloud/ScholarChain/load-tests/exp-d-query-load.py (Python 3 standard library)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/manifests/exp-f-failure-recovery-v1.json
- /Users/achyutpatel/Developer/Cloud/ScholarChain/experiments/harness/exp-f-failure-recovery.sh (bash 3.2; sources scripts/lib/common.sh as smoke-test.sh does)
- Logs, written by Bash redirection: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/wo5-bash-n.txt, wo5-python-ast.txt, wo5-manifest-jq.txt, wo5-exp-d-dry-run.txt, wo5-exp-f-dry-run.txt, wo5-run-experiment-dry-run.txt

FILES MODIFIED: none
FILES DELETED: none (raw results: always none)
MANIFEST: exp-d-query-load-v1.json and exp-f-failure-recovery-v1.json, both uncommitted. The human commits them; the commit SHA goes into run-record.json at run time.
RUN IDENTIFIERS: none (nothing ran)
RAW RESULT LOCATIONS AND CHECKSUMS: none yet. At run time they go to experiments/results/<experimentId>/<stamp>/raw/ through $SCHOLARCHAIN_EXPERIMENT_DIR.
OBSERVATIONS: none (offline session)

### (a) E: no retrieval-mode selection in the deployed query-api
- `QueryBody.retrieval` accepts only three positive limits: graphLimit, vectorLimit, maxPathDepth.
  - modules/services/src/main/scala/scholarchain/services/Models.scala:52-56 (RetrievalBody)
  - Models.scala:400 (the field allowlist)
  - Models.scala:403-408: any other retrieval field is 422 UNSUPPORTED_RETRIEVAL_MODE.
  - Models.scala:487: `retrieval.$name must be at least 1`, so neither graph nor vectors can be turned off.
  - modules/services/openapi.yaml:654-662 says the same.
- The rag side matches.
  - modules/rag/src/main/scala/scholarchain/rag/runtime/Records.scala:36-41 (RequestRetrieval holds only the three limits)
  - modules/rag/src/main/scala/scholarchain/rag/runtime/Planner.scala:638-652: limits are clamped to at least 1. There is no mode.
- The services image is SCHOLARCHAIN_STUB_LLM=false in infra/terraform/envs/course/main.tf:207. Stubbing model calls (Composition.scala:192, conf `services.stub-llm`) would therefore need a task-definition change. For that reason D also makes no Haiku calls.
- Smallest change needed:
  - rag-engineer: an optional `retrieval.mode` (FUSED as the default, GRAPH_ONLY, VECTOR_ONLY) in RequestRetrieval, honoured by the planner and retrieval fusion (skip semantic retrieval or graph retrieval), and recorded in the audit.
  - service-engineer: the same field in RetrievalBody, the Models.scala:400 allowlist, the validation, and openapi.yaml.
  - This needs a new package and image, which conflicts with ruling 23 (no new package before window 2) and ruling 30 (no code change before window 2), so it also needs a human ruling.

### (b) F: a poison message is retried and reaches the DLQ; it is not deleted
- modules/services/src/main/scala/scholarchain/services/Worker.scala:130: a body that fails JobMessage.parse goes to `poison(m, "malformed message")`.
- Worker.scala:155-157: `poison` only counts the message and logs a `poison-message` event. There is no delete and no visibility change. The class doc at Worker.scala:23-25 says the same ("never deleted; the queue's redrive policy moves it to the dead-letter queue").
- Delete happens only through `settle` and `delete` for a job in a final state (Worker.scala:251-258).
- What makes a body a poison message: modules/storage/src/main/scala/scholarchain/storage/JobQueue.scala:24-31 (anything other than exactly the three job-message-v1 fields is Left).
- Queue settings:
  - Worker receive uses a 300 s visibility timeout (Worker.scala:112; conf `visibility-timeout-seconds = 300`).
  - Terraform: infra/terraform/modules/messaging/main.tf has `visibility_timeout_seconds = 300` and `redrive_policy` maxReceiveCount = var.max_receive_count, which is 3 (envs/course/main.tf:15 and 115). The DLQ keeps messages for 14 days.
- The idempotent replay does not enqueue a second message: modules/services/src/main/scala/scholarchain/services/Services.scala:150 sends Unchanged to replay(), and lines 236-240 return the job reference with `Idempotent-Replay: true` and no queue send.
- I did not need the alternative (one worker task stop), so I proposed no ECS stop call.

### Per experiment
**D, query load**
- Window 2 commands:
  - `./scripts/run-experiment.sh --manifest experiments/manifests/exp-d-query-load-v1.json --profile scholarchain-sdk --dry-run`
  - then the same command without `--dry-run`
- Design:
  - Concurrency levels 1, 2, 4 and 8, 180 s each, 30 s pauses, then 120 s of idle observation.
  - Load is GET only against graph-evidence-api: evidence, node, and neighbors (depth 1, limit 50, COAUTHORED_WITH).
  - IDs come from the latest PASS smoke record in operations/releases. A PASS smoke test must therefore come first.
  - ECS desired, running and pending counts are recorded before and after each level and once at the end.
  - Bounds: at most 20,000 requests per level, a 10 s request timeout, and an abort when a level's error rate is above 0.5.
- Expected about 16 min (930 s planned); maxMinutes 25.
- Haiku calls: 0.
- Cleanup: none.
- Logs: wo5-exp-d-dry-run.txt, wo5-python-ast.txt, wo5-manifest-jq.txt.

**E, retrieval modes**
- Blocked as described in (a). No commands, no minutes, 0 Haiku calls, no files.

**F, failure and recovery**
- Window 2 commands:
  - `./scripts/run-experiment.sh --manifest experiments/manifests/exp-f-failure-recovery-v1.json --profile scholarchain-sdk --dry-run`
  - then the same command without `--dry-run`
- Design:
  - Precondition: the DLQ is empty.
  - One `sqs send-message` of a malformed body to the job queue.
  - One async POST of B07 with an Idempotency-Key, replayed while it is queued and again after it finishes.
  - Job polling, the job result, and DLQ depth polling for up to 22 min.
  - Worker and query-api log events. Checks: receiveCount [1,2,3], exactly one job-queued and one job-finished event, and the DLQ reaches a depth of at least 1.
- Expected about 19 min (the redrive comes at about 15-16 min plus metric lag); maxMinutes 30.
- Haiku calls: 2 expected, at most 4 (one question; planner and synthesis, each with at most one retry).
- Cleanup (human):
  - The poison message stays in scholarchain-course-jobs-dlq for 14 days.
  - The alarm scholarchain-course-jobs-dlq-not-empty will go to ALARM; there is no SNS topic (ruling 27).
  - Leave both until `cleanup.sh --stage runtime`. Removing the message earlier is a human-only action.
  - The job record (TTL 168 h), the idempotency record (TTL 24 h) and the audit and answer objects go with the runtime cleanup.
- Logs: wo5-exp-f-dry-run.txt, wo5-bash-n.txt, wo5-manifest-jq.txt.

**Total:** D plus F is about 35 min of run time, well under the 2 h of ruling 26.

ESTIMATED COST:
- D: under $0.10 (ALB, on-demand DynamoDB reads, and Neptune db.t4g.medium CPU credits, which already run).
- F: under $0.05 (2-4 Haiku calls, a few SQS calls).
- The runtime stack's hourly cost is separate and not included.

FAILED OR ANOMALOUS RUNS: none run.

LIMITATIONS AND THREATS TO VALIDITY:
- D is a closed loop from one laptop, so laptop-to-us-east-2 network time is part of the measured latency.
- Each D level lasts 180 s. CPU target tracking (60%, 60 s scale-out cooldown) may not finish a scale-out in that time, and scale-in (target tracking needs about 15 min of low CPU) will not be seen.
- The D ID set is small: at most 10 evidence IDs and 5 researchers from one smoke answer. That favours caches.
- The ALB metrics' LoadBalancer dimension is the post-apply `alb_arn_suffix` output. The manifests write it as `terraform-output:alb_arn_suffix`, and D records the resolved value in raw/inputs.json.
- Container Insights is disabled, so task counts come from describe-services, not CloudWatch.

COMMANDS RUN (all offline, in the repository root):
- File reads: sed, cat, grep, ls.
- `mkdir -p scratch/phase-14-logs`
- `bash -n experiments/harness/exp-f-failure-recovery.sh`: exit 0.
- `python3 -B -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' load-tests/exp-d-query-load.py`: exit 0.
- The run-experiment.sh jq expression run directly on each manifest: `true`, exit 0, for both. The command[0] prefix and `..` checks pass, and each command[0] file exists.
- `python3 -B load-tests/exp-d-query-load.py --self-test`: OK, exit 0.
- D `--dry-run` with the manifest flags: OK, exit 0. With levels 1,2,4,16: exit 2, as intended.
- `bash experiments/harness/exp-f-failure-recovery.sh --dry-run --dlq-wait-minutes 22 --job-wait-minutes 5`: OK, exit 0. With `--dlq-wait-minutes 10`: exit 2, as intended.
- `./scripts/run-experiment.sh --manifest <each> --profile scholarchain-sdk --dry-run`: exit 2 for both, "command[0] is not an executable file". The files have no executable bit yet, because the work order leaves that to the human. I read the script before running it: before `dry_run_exit` it makes no AWS, git or network call.
- No `__pycache__` was created.

TEST RESULTS: the D percentile self-test passed. There are no other tests, because no Scala or sbt sources changed.
VALIDATION RESULTS: as listed under COMMANDS RUN. The run-experiment.sh dry-run itself fails only on the executable-bit gate.

ASSUMPTIONS:
- The scholarchain-sdk profile may call sqs:SendMessage on the job queue, sqs:GetQueueAttributes, logs:FilterLogEvents, ecs:DescribeServices and secretsmanager:GetSecretValue. I did not verify this.
- `terraform output` from local state is available on the human's laptop, as for smoke-test.sh.
- The smoke answer for B07 carries researcher resolutions and evidence IDs. If it does not, D exits 3.
- Service log lines are the raw JSON that JsonLog prints, with no prefix.

RISKS:
- At c=8, Neptune db.t4g.medium may open graph-evidence-api breakers (503 CIRCUIT_OPEN). D then aborts and records it, and the ALB 5xx alarm may fire.
- F leaves the DLQ alarm in ALARM for the rest of the window. Read it as an expected F result, not an incident.
- F must not run at the same time as the smoke test or another Haiku run (ruling 26).

MANUAL ACTION REQUIRED:
1. Before committing, run `chmod +x load-tests/exp-d-query-load.py experiments/harness/exp-f-failure-recovery.sh`, so git records mode 100755 and run-experiment.sh passes its `-x` gate.
2. Review and commit the four files.
3. In window 2, run D and F only after load-stores.sh and a PASS smoke-test.sh. Run them one at a time.
4. Rule on E (see BLOCKED-ON).

BLOCKED-ON OWNER: E needs rag-engineer and service-engineer, plus a human ruling, because the change contradicts rulings 23 and 30.
BLOCKED-ON EVIDENCE: Models.scala:52-56, 400, 403-408, 487-491; openapi.yaml:654-662; Records.scala:36-41; Planner.scala:638-652.
NEXT GATE: human review and commit of the D and F manifests and harnesses, and a ruling on E. Then the window 2 runs, human review of the raw evidence, and the documentation-engineer write-up once the results are frozen.

I skipped a worker-level duplicate-delivery injection (re-sending a valid job message), because the work order asked only for the poison message plus the idempotent replay. Everything here is checked by syntax checks and dry-runs only: no live HTTP, SQS or ECS path was exercised. The executable bit must be set before commit, or both run-experiment.sh invocations fail with exit 2.
```
