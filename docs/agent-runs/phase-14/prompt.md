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

## Human rulings during cloud window 1

11. Deploy plan path (session 1).
   - The first deploy.sh run exited 1 at apply; nothing was applied. deploy.sh passes --plan-file unchanged to terraform, which runs with -chdir=infra/terraform/envs/course (scripts/lib/common.sh:108, scripts/deploy.sh:121), so a repo-relative plan path does not load; deploy.sh's own gates resolve it from the repo root and passed. The relative path came from the human's session prompt; plan.sh prints the absolute path.
   - Decision (option b): the same deploy.sh command with the absolute plan path, same plan sha256, commit and approval record. It ran once and exited 0: 43 added, 0 changed, 0 destroyed (operations/releases/release-20261116T062349Z-d33ce8e8d507.json). The failed first attempt's report and .apply.txt stay as evidence.
   - Defect P14-D1 (relative --plan-file) goes to repair-01 (ruling 12). Until fixed, every deploy.sh and cleanup.sh call uses the absolute plan path. This ruling was first recorded in operations/rulings/phase-14-ruling-11.txt during the window.

12. Pipeline submit failure and repair-01.
   - Finding: run-bounded-pipeline.sh --fraction 25 stopped at submit (AWS CLI exit 254): ValidationException "Specified application: Hadoop is invalid". No cluster was created (list-clusters empty). The inputs were uploaded write-once under input run run-7a5abdee2b5848146b12f0eef8ef9d99. The partial record operations/pipeline-runs/20261116T063500Z-fraction25/ stays as failure evidence.
   - describe-release-label emr-spark-8.0.0 (Nov 16): AmazonCloudWatchAgent 1.300032.2, JupyterEnterpriseGateway 2.6.0, Livy 0.8.0, Spark 4.0.2. There is no Hadoop application; emr-spark-8.0.0 is a separate line from the emr-7.x labels.
   - Earlier findings: package.sh recorded the ECR scan as raw text ("None\tnull") before the scan finished (P14-D2); the scan later completed with CRITICAL 10, HIGH 48, MEDIUM 62, LOW 18, UNDEFINED 6 (to be reviewed before window 2). run-bounded-pipeline.sh exited with the CLI's raw code 254 instead of a documented code and wrote no run.json (P14-D3).
   - Decision: one repair round, repair-01 (work order 2, infra-engineer), then code review 1 of the repair (code-reviewer), in one NEW session with Ponytail OFF throughout. Scope, and nothing else:
     - P14-D4: run-bounded-pipeline.sh requests --applications Name=Spark only.
     - P14-D1: deploy.sh resolves --plan-file to an absolute path before its first gate, so its gates and terraform read the same file. scripts/cleanup.sh is not touched by any agent (Phase 12 ruling 10); scripts/README.md states that cleanup.sh needs the absolute plan path.
     - P14-D2: package.sh records the scan as JSON (status and severity counts, or null when not yet available), never raw CLI text.
     - P14-D3: a failed create-cluster ends with a "==> FAIL" line and a documented exit code (1), and writes run.json with the failure and the error message.
     - No Terraform change (no new plan), no Scala change, no other script change.
   - Accepted risk: whether `hadoop jar` and the MapReduce framework exist on emr-spark-8.0.0 with only Spark selected is unverified. The 25% run's classpath-probe (CONTINUE) and hadoop-pipeline steps answer it on the cheapest cluster. If MapReduce is missing: stop; a fallback (for example an emr-7.x cluster for the Hadoop jobs, which changes D-03) needs a new ruling.
   - Ruling 3 amended: the deploy is done, so the repair commit is allowed. After APPROVE the human runs check.sh, commits, reruns package.sh at the new HEAD, then the 25% run. The d33ce8e package record stays as evidence; window 2 deploys the newest package.

## Work order 2: infra-engineer (repair-01, ruling 12)

Fix P14-D1 to P14-D4 exactly as ruling 12 scopes them, in scripts/run-bounded-pipeline.sh, scripts/deploy.sh, scripts/package.sh, scripts/lib/common.sh (only if needed) and scripts/README.md. Never touch scripts/cleanup.sh. No AWS, Terraform, git or sbt calls. Validate with bash -n on each changed script and with --dry-run runs (which make no AWS, Terraform or git call): run-bounded-pipeline.sh --fraction 25 --package-record operations/releases/package-d33ce8e8d507145cd46fb39bf319e985bc9a428a.json --dry-run, and deploy.sh with the relative plan path scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan and the approved sha256 with --dry-run, showing that the path is resolved to the absolute one. Save the logs under scratch/phase-14-logs/. Report with the infra-engineer report contract, including the full diff summary per file.

## Code review 1 (code-reviewer, after work order 2)

Review the repair-01 change set (the orchestrator builds it with read-only git diff of the changed files) against ruling 12 only: each of P14-D1 to D4 fixed as scoped, nothing else changed, scripts/cleanup.sh untouched, no secret in output, bash 3.2 compatible. Verdict APPROVE or REQUEST_CHANGES.

13. Repair-01 accepted (code review 1 APPROVE).
   - P14-D1 to D4 fixed as ruling 12 scoped them, in scripts/README.md, deploy.sh, package.sh and run-bounded-pipeline.sh only; scripts/cleanup.sh and scripts/lib/common.sh unchanged. Dry runs and the D2/D3 harness are in evidence/repair-01/ and evidence/agent-logs/.
   - Review finding 1 (pre-edit copies in scratch/phase-14-logs/orig/): minor. They stay in the ignored scratch/ and are not evidence; git holds the originals. Review finding 2 (one read-only git status by the infra-engineer, self-reported): recorded, no action.
   - factory-engineer backlog: an owner row for scratch/phase-NN-logs/, so agents can use the Write tool there (guard-paths blocked it as unowned).
   - Next (ruling 12): the human runs check.sh and commits; package.sh runs again at the new HEAD; then the 25% run.

14. Spark step argument shift (P14-D5) and repair-02 exception.
   - Finding: the 25% run on cluster j-0518231NIANENLFGS7S (record operations/pipeline-runs/20261116T071214Z-fraction25/): classpath-probe COMPLETED, hadoop-pipeline COMPLETED in about 17 minutes (J01 accepted 27,993 works; output run-620fccae97a621ed9437af85f2a590c4; no AccessDenied), spark-pipeline FAILED after 6 seconds: PipelineJob received the output path without --out. No open ingress; no cluster left active; 24 normalized instance hours (about 25 minutes, about $0.30).
   - Cause, confirmed in the files: hadoop-runs-before.txt was empty (the first Hadoop run), so the spark step's Args held "" before "--out". EMR command-runner drops empty-string arguments, so scripts/emr/step.sh (earlier="$5"; shift 5) read "--out" as the earlier-runs list.
   - Ruling 12's accepted risk is resolved: emr-spark-8.0.0 with only Spark selected has Hadoop 3.4.2-amzn-1, and the MapReduce pipeline runs. Classpath: Spark 4.0.2, Scala 2.13.16, AWS SDK v2 bundle 2.41.32 (D-01 as assumed). The node's default java is Corretto 8; Spark runs on Java 17.0.21. Recorded for Phase 17.
   - Decision: a human exception allows a second repair round, repair-02 (work order 3, infra-engineer), then code review 2 (code-reviewer), in one NEW session, Ponytail OFF, scope P14-D5 only. If code review 2 requests changes, the exit is human escalation, not a third round.
   - The next live run cannot exercise the empty case (curated/hadoop now holds a run), so the harness in work order 3 is the evidence. After APPROVE: evidence copy, check.sh, commit, package.sh at the new HEAD, then the 25% run.

## Work order 3: infra-engineer (repair-02, ruling 14)

Fix P14-D5 in scripts/run-bounded-pipeline.sh and scripts/emr/step.sh only (and scripts/README.md if it documents the step arguments). No positional step argument may be empty, because EMR command-runner drops empty-string arguments. Pass the earlier-runs list as a non-empty value, using the sentinel "none" when there is no earlier run, and make step.sh map "none" to an empty list. In every mode, step.sh also checks that it received its expected positional arguments and that none of them starts with "--"; otherwise it exits 2 with a clear message. Change nothing else; never touch scripts/cleanup.sh. No AWS, Terraform, git or sbt calls.
Validate: bash -n on both files; run-bounded-pipeline.sh --fraction 25 --package-record operations/releases/package-1232927b8ac8828dd57f30e93e06e263bd57ac2a.json --dry-run (a step.sh sha256 mismatch against that older record is expected and acceptable if the dry run reports it); and a harness, in the session scratchpad, that runs step.sh's spark and hadoop argument handling with the empty strings removed (as command-runner does) and with stubbed aws, hadoop and spark-submit commands, for an empty and a non-empty earlier list, showing that spark-submit receives --input <run> --out <path> and the remaining flags in order. Save logs under scratch/phase-14-logs/repair-02/ with Bash redirection. Report with the infra-engineer report contract.

## Code review 2 (code-reviewer, after work order 3)

Review the repair-02 change set against ruling 14 only: P14-D5 fixed for the empty and non-empty earlier list, the argument checks in step.sh, nothing else changed, scripts/cleanup.sh untouched, bash 3.2 compatible. Verdict APPROVE or REQUEST_CHANGES.

15. Repair-02 accepted (code review 2 APPROVE).
   - P14-D5 fixed in scripts/run-bounded-pipeline.sh (+2) and scripts/emr/step.sh (+27/-2) only; scripts/README.md, cleanup.sh and lib/common.sh unchanged. The harness, change set and logs are in evidence/repair-02/.
   - Review minors, residual (infra-engineer notes, no repair): the harness's own exit code was not captured in harness.log; three paths (classpath with zero arguments, hadoop with zero positionals, an earlier list of two or more runs) were read in code, not exercised.
   - Work order 3's premise was the human's error: the dry run cannot report the step.sh sha256 mismatch, because dry_run_exit runs before check_sha. The mismatch is recorded in step-sha256.log; package.sh at the new HEAD resolves it.
   - The infra-engineer's session context said "Ponytail mode active" (PONYTAIL_SUBAGENT_MATCHER covers implementation agents), although this was a code-review session with Ponytail OFF; the agent followed the ruling. factory-engineer backlog: the matcher has no notion of review sessions.
   - Next: check.sh, commit, package.sh at the new HEAD, then the 25% run. Hadoop runs again and writes a new write-once run; the earlier list now holds run-620fccae97a621ed9437af85f2a590c4, so step.sh picks only the new run.
