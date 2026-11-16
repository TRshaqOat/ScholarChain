# Phase 12 human gate: AWS infrastructure in Terraform and reproducible automation (spec Phases 12 and 13, merged by D-10)

Date: 2026-11-15
Human architect: Achyut Patel

## What was reviewed
- Work orders 1 to 8 (agent-report.md):
  - storage-engineer: ECS credentials; the in-VPC loader with --stage-from-bucket;
  - infra-engineer: Terraform; 11 scripts;
  - domain-engineer: the SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE override;
  - service-engineer: optional --profile;
  - dataset-engineer: the 25 and 50 percent subsets;
  - hadoop-engineer: JOBS.md.
- Repairs:
  - repair-01: dataset, storage and infra (code review 1 F1 to F9);
  - repair-02 (ruling 22): R1, N1, N2, and the F9 remainder;
  - repair-03 (ruling 23): N4, the unconditioned ListBucket on the EMR instance role.
- Code reviews:
  - 1: REQUEST_CHANGES (F1 major);
  - 2: APPROVE;
  - 3: REQUEST_CHANGES (N4 major, human escalation);
  - 4: APPROVE (M1 to M3 minor).
  - Change sets are in evidence/code-review-1 to code-review-4/.
- Final tree, scripts/check.sh: exit 0, every gate OK (shellcheck not installed).
  - sbt: 839 passed, 0 failed, 10 pending (core 191, entityResolution 111, rag 116, storage 115, services 49, sparkAnalytics 46, tests 141 + 10 pending, hadoop 70).
  - Dataset validation: 15 PASS.
  - terraform fmt and validate: OK.
  - bash -n: all scripts.
  - Logs: evidence/agent-logs/r03a-check.txt.
- Human read-only AWS checks (ruling 18):
  - The Haiku 4.5 US profile routes to us-east-1, us-east-2 and us-west-2.
  - Neptune 1.4.8.1 on db.t4g.medium is orderable in 2a, 2b and 2c.
  - emr-spark-8.0.0 supports m6g.xlarge (AARCH64).
  - hashicorp/aws 6.28.0 was released 2026-01-07, and the lock-file hash matches the published darwin_arm64 zip (ruling 11).
- Size fractions (work order 7, fraction-v1, nested):
  - DBLP 27,993 / 55,598 / 110,867.
  - ORCID does not scale (64.9 and 80.0 percent), accepted by ruling 17.

## Phase 12 gate (spec)
- [x] No unexplained stateful destroy or replacement. Judged from code (code reviews 1 to 4). The only designed destroy is the runtime_enabled true-to-false teardown (D-13). The plan evidence is below.
- [x] IAM scoped to actual resources and actions. F1 narrowed the EMR writes to curated/* and logs/emr/*. Two accepted exceptions list key names only: unconditioned ListBucket on query-api and the worker (ruling 11), and on the EMR instance role (ruling 23).
- [x] Buckets and databases not public. The only internet ingress is the HTTP ALB (ruling 11).
- [x] Default capacity bounded: ECS 1 to 2 tasks, EMR 1 primary plus at most 2 core on demand, a Neptune class allowlist, the Haiku cap at most 8 per minute, CodeBuild concurrency 1.
- [x] Secrets absent from source and outputs. The API key secret has no value in Terraform.
- [x] Human reviews and signs the plan before any apply (see "Plan sign-off").
- [x] Code reviewer approves the infrastructure diff (code review 4).

## Phase 13 gate (spec)
- [x] A clean checkout runs all non-cloud validation through scripts/check.sh (working tree; replayed on the committed tree under "Plan sign-off").
- [x] Terraform planning reproducible and plan checksum archived (see "Plan sign-off").
- [x] Deployment cannot proceed without the documented human approval. deploy.sh exits 4 on a checksum mismatch and never uses -auto-approve.
- [x] Smoke tests exercise a real bounded query (by code; run in window 2).
- [ ] The README uses the same scripts: deferred to Phase 17 (ruling 22).

## Plan sign-off (ruling 21)
- Plan: scripts/plan.sh --runtime-enabled false at commit 3e0983e, account 834519512655, us-east-2, TF_VAR_alarm_email empty. Plan directory scratch/plans/20261116T053901Z-3e0983e6a0c7.
- Result: 43 to add, 0 to change, 0 to destroy. Four subnets, in us-east-2a and us-east-2b only. The EMR instance policy JSON is computed at apply (bucket ARN), so the reviewed code (infra/terraform/modules/emr/main.tf, code review 4) is the reference. No secret values (the only match is the ECR login command in the buildspec).
- Plan sha256: 2955206c828fb7a8f39d276d9c8122695cbc7e234d9fac7b862da9b821796011 (evidence/plan/).
- check.sh replay on the committed tree: every gate passed, 839 passed, 0 failed, 10 pending (evidence/plan/check-committed-tree.txt).
- Signed by Achyut Patel, 2026-11-15 23:47 CST.
- deploy.sh requires HEAD to equal the plan's commit, so this plan is gate evidence. The Phase 14 apply uses a fresh plan made immediately before apply (Phase 14 ruling).

## Human rulings recorded at the gate
- Rulings 1 to 24 are in prompt.md.
- Human exceptions to the one-repair-round rule: rulings 22 and 23.
- Accepted deviations: ruling 10 (git reads; the cleanup.sh wrapper) and N5 (Grep reported unavailable).

## Decision
ACCEPTED. Code review 4 APPROVE. M1 to M3 are residual risks (ruling 24).

## Manual changes
- No manual edits to agent-owned files.
- Human-authored: the prompt rulings, this gate, the evidence copies, the deletion of the empty modules/secrets/ (ruling 11) and of datasets/scripts/__pycache__/ (ruling 19), and the read-only AWS checks.
- External assistance: none.
- Process notes:
  - Guard false positives in work orders 1, 2 and 5, repair-01 C and repair-02. No hook was worked around.
  - repair-02.md's "403 into 404" claim is superseded by ruling 23.

## Remaining risks
- Nothing has run in AWS. These are unverified until windows 1 and 2:
  - every CLI call shape;
  - the S3A probe order on emr-spark-8.0.0;
  - the Docker Hub base-image pull on CodeBuild (ruling 11);
  - the cost figures.
- M1: the Neptune bulk loader may need an unconditioned ListBucket (check the first load's --status).
- M2: the logs and logs/emr probe keys are defensive.
- M3: SERVICES.md:83 suggests a prefix-conditioned ListBucket (service-engineer backlog).
- Code review 2 residuals:
  - R2: the EMR security-group check runs only after the cluster ends;
  - R3: open ingress is only warned, and quoted in the run record;
  - R4: aws_once failures exit with the CLI status;
  - N3: the jq fixture claim is unevidenced.
- Rollback updates the three services one after another.
- The final cleanup stage fails while the bucket or ECR is non-empty.
- The HTTP-only ALB: the API key travels in clear text.
- Unconditioned ListBucket on the EMR role: key names across the bucket are visible to step code.
- The 30-day logs/ lifecycle and the 14-day log retention: archive the runtime audits before about Dec 15 if grading needs them.
- shellcheck was not run.
- ORCID scaling (ruling 17).

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
