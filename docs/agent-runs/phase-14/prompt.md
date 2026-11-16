# Phase 14 prompt: apply and deploy through human approval, cloud window 1 (spec Phases 14 and 15, window 1 of 2)

## Session plan (cloud window 1, Nov 17, 2026)
- Phase 12+13 is accepted (docs/agent-runs/phase-12/commit.txt) and its plan is signed (human-gate.md, commit e73d151). Code review 4 APPROVE covers the code at a5b3df8; every later commit changes only docs/agent-runs/.
- Human, Terminal: pre-checks; a fresh scripts/plan.sh --runtime-enabled false at this prompt's commit; review (43/0/0); the approval record in operations/plans/ (ruling 3).
- Session 1 (NEW Claude Code conversation, Ponytail OFF): work order 1, deploy-engineer runs scripts/deploy.sh for the approved window 1 plan (rulings 2, 4, 5).
- Human, Terminal: scripts/package.sh, then scripts/run-bounded-pipeline.sh --fraction 25, then 50, then 100, one cluster at a time (rulings 6 to 8).
- After the 100% run: the human commits operations/ and docs/agent-runs/phase-14/ (ruling 9). The window 1 stack stays up (idle cost about $0) until window 2. Phase 14 closes after window 2.
- Nothing is committed between the plan and the end of the 100% run (ruling 3).

## Human rulings

1. Scope.
   - Window 1 is the spec Phase 14 apply of the runtime_enabled=false stack (43 resources), the package of the job jars and the services image, and the Phase 15 window 1 runs: size scaling at fractions 25, 50 and 100 (D-13 replaced Experiment B). The fraction 100 run is also the Experiment A baseline record.
   - Window 1 creates no Neptune, DynamoDB, S3 Vectors, SQS, ECS, ALB or secret.
   - The Phase 14 gate items that need the runtime (services healthy and ready, running image digests and task revisions, the smoke query, no critical alarm) belong to window 2. Phase 14 closes after window 2.

2. Who runs what.
   - deploy-engineer runs scripts/deploy.sh: window 1 here; window 2, with scripts/smoke-test.sh, in the window 2 prompt. This is the spec Phase 14 work order.
   - The human runs plan.sh, package.sh, run-bounded-pipeline.sh, load-stores.sh and cleanup.sh from Terminal. scripts/README.md allows the human for package.sh and run-bounded-pipeline.sh. Reason: package.sh (sbt and a CodeBuild build) and each pipeline run (one cluster, up to about 4 hours with the default timeouts) outlast an agent's 10-minute Bash call, and a long poll would mean many permission prompts. /run-pipeline is not used in window 1; ruling 6 is the approval it would need.
   - Fallback: if a tooling problem (a guard hook, a permission rule, expired credentials) blocks the deploy-engineer, the human runs the same deploy.sh command line from Terminal, and a new ruling records it.

3. Plan immediately before apply.
   - This prompt is committed first. At that HEAD the human runs TF_VAR_alarm_email= scripts/plan.sh --runtime-enabled false with AWS_PROFILE=scholarchain-sdk and checks it as in Phase 12 Step P: 43/0/0, account 834519512655, us-east-2, four subnets in us-east-2a and us-east-2b only, no secret values.
   - The human writes operations/plans/<plan dir name>-APPROVED.txt: plan directory, plan sha256, HEAD, the git status result (only operations/ untracked), the docs-only diff check since a5b3df8, expected counts 43/0/0, the time, and "approved by Achyut Patel".
   - deploy.sh requires HEAD to equal the plan's commit. package.sh and run-bounded-pipeline.sh require a clean tree and HEAD equal to the package commit. So from the plan until the 100% run is recorded, nothing is committed and no file is created outside operations/, experiments/results/ and the ignored scratch/.

4. Approval reference for /deploy.
   - Environment: course (account 834519512655, us-east-2).
   - Approved commit and plan sha256: as written in the approval record.
   - Approval reference: operations/plans/<plan dir name>-APPROVED.txt, plus docs/agent-runs/phase-12/human-gate.md (plan signed).
   - Code review: Phase 12 code review 4 APPROVE for a5b3df8 (docs/agent-runs/phase-12/review-report.md). The approval record's diff check shows every later commit changes only docs/agent-runs/.

5. deploy-engineer and orchestrator constraints in window 1.
   - The deploy-engineer runs no git command (Phase 7 ruling 17). Agent law 2's git checks are met by deploy.sh gate 1 (clean tree, HEAD equals the plan's commit) and by the human's approval record.
   - It makes no direct aws CLI call and no mcp__aws-api call. deploy.sh checks the account and Region (gate 3) and prints the TARGET line.
   - It executes only ./scripts/deploy.sh: once with --dry-run, then once without it.
   - It writes only new files under operations/deployments/: its verbatim report, as operations/deployments/window-1-<UTC stamp>.md.
   - The orchestrator does not write docs/agent-runs/phase-14/agent-report.md during the window (an untracked file there stops package.sh and run-bounded-pipeline.sh). It returns the report in the chat. The human copies the operations/deployments/ report to agent-report.md after the 100% run.

6. Window 1 pipeline approval (the pipeline manifest named by /run-pipeline gate 2; escalation 6).
   - Snapshots: DBLP 2026-10-03; OpenAlex, Crossref and ORCID 2026-11-03.
   - Subset rule fraction-v1 (datasets/schemas/fractions.md), nested 25 within 50 within 100. Fraction 100 is the bounded subset (110,867 DBLP works), not a full corpus, and is approved here.
   - Run IDs: the input run is the hash of the fraction and the file hashes (computed by the script). Every Hadoop and Spark output is a new write-once run directory under curated/hadoop/ and curated/spark/.
   - Capacity: emr-spark-8.0.0; 1 primary and at most 2 core m6g.xlarge, on demand. Script defaults only: --reducers 2, --shuffle-partitions 16, --hadoop-timeout-minutes 90, --spark-timeout-minutes 150.
   - Order: 25, then 50, then 100. This overrides the 100-first order in scripts/README.md, so that an S3A, IAM or classpath failure happens on the cheapest cluster.
   - One cluster at a time: the EC2 vCPU quota is 16 and one cluster uses 12. The next run starts only after the previous one exits 0 and its record is checked.

7. Stop rules.
   - Any non-zero exit: stop. Nothing is rerun until the failure is read and a ruling is written. Exit 3 or 4 changed nothing.
   - Exit 6 (timeout): the script prints the cluster ID. The human checks the cluster state and decides whether to terminate it (a human action).
   - AccessDenied or 403 in any step log: stop and keep run.json, steps-result.json and the logs. An IAM widening needs infra-engineer, a code review and a new plan. Never edit IAM in the console.
   - CodeBuild "toomanyrequests" from Docker Hub: stop. dependency-updater moves javaRuntimeImage to the ECR Public mirror after the human confirms the same digest (Phase 12 ruling 11).
   - "==> WARN EMR GROUP OPEN TO THE INTERNET": record it, and check that EMR block public access is on before the next run.
   - Cost: ruling 8.

8. Cost bounds.
   - Month-to-date spend before window 1: $4.14 of the $25 budget (Nov 15). Window 1 cap: $5 estimated.
   - After each cluster, estimate its cost as elapsed cluster hours times $0.60 (3 m6g.xlarge with the EMR fee). Do not start the next fraction if the cumulative window 1 estimate is above $4.

9. Records and evidence.
   - Each run.json records emrSecurityGroups; the evidence quotes openIngress (Phase 12 ruling 22).
   - After the 25% run, check the classpath-probe step log for Java, Hadoop and Spark versions and the Typesafe Config, AWS SDK v2 and Scala entries.
   - D6 to D9 and D17 stay residual; record whether the cloud output counts differ from the local runs.
   - Size scaling uses DBLP works as the x-axis (Phase 12 ruling 17).
   - After the 100% run: gzip any file over 10 MB, then commit operations/ (the script records are evidence, scripts/README.md) and docs/agent-runs/phase-14/ (agent-report.md and evidence/).

10. Residuals before window 2.
   - The M10 remainder, the FU1 decision, D15 and D16 get no code change before window 2. They are residual risks, documented in Phase 17.
   - So the window 1 package (jars and image at this commit) is the one window 2 deploys. The window 2 inputs in course.tfvars (image_tag and run IDs) are settled in the window 2 prompt.

## Work order 1: deploy-engineer (scripts/deploy.sh, cloud window 1)

Use the deploy-engineer agent to deploy the exact approved ScholarChain commit and window 1 Terraform plan to the course environment, under rulings 1 to 5.

Inputs (from the session prompt and the approval record): plan directory, plan sha256, approved commit, approval record path.

Steps:
1. Read this prompt, CLAUDE.md, docs/agents.md, the approval record, and operations/plans/.
2. Run ./scripts/deploy.sh --plan-file <plan dir>/plan.tfplan --approved-plan-sha256 <sha256> --profile scholarchain-sdk --dry-run. If the file is not executable, use bash scripts/deploy.sh with the same arguments. Stop on any non-zero exit.
3. Run the same command without --dry-run, once, in the foreground, with the Bash tool timeout set to 600000 ms. Never rerun it: on any non-zero exit or a tool timeout, stop and report (exit 3 or 4 means nothing was applied; for anything else, report what the output shows).
4. Verify from the files deploy.sh wrote: the new operations/releases/release-*.json (commit, plan sha256, account 834519512655, us-east-2), its .apply.txt ("43 added, 0 changed, 0 destroyed"), and its .outputs.json (data bucket name, ECR repository, CodeBuild project, the emr object; no secret values).
5. Write the report (report contract in .claude/agents/deploy-engineer.md) as a new file operations/deployments/window-1-<UTC stamp>.md, then return it.

Not applicable in window 1 (window 2 items): smoke-test.sh, running image digests, task revisions, service stabilization, alarms. Report them as "window 2".
