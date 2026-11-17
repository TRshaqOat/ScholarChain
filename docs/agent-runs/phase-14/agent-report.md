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
