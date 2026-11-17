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

16. Queued 50% and 100% runs (recorded during the window in operations/rulings/phase-14-ruling-16.txt):
   Phase 14 human ruling 16 (cloud window 1, queued 50% and 100% runs)
   - Ruling 6 required a checked record before each next fraction. For the overnight runs, a script in /tmp (p14-chain.sh, copied to evidence at Step W1) does the check: the newest record of the previous fraction is from HEAD's commit, run-bounded-pipeline.sh exited 0, finalState TERMINATED, all three steps COMPLETED, exactly one Hadoop and one Spark output run, emrSecurityGroups.openIngress empty, no AccessDenied or 403 in the step logs, and the J01 ACCEPTED count equals the fraction's DBLP works (25: 27993; 50: 55598; 100: 110867).
   - Before each run: HEAD is unchanged and equals the package commit, the tree shows only "?? operations/", and the window 1 normalized instance hours are at most 160 (about $4 at $0.60 per cluster hour; ruling 8).
   - Any failed check stops the chain. Nothing is rerun; one cluster at a time. The human reviews every record in the morning before Step W1.
   - Recorded in operations/ because a commit would move HEAD off the package commit; appended to prompt.md at Step W1.
   - Approved by Achyut Patel.

17. Chain gate correction; 25% attempt 3 accepted (recorded during the window in operations/rulings/phase-14-ruling-17.txt):
   Phase 14 human ruling 17 (cloud window 1, chain gate correction; 25% accepted)
   - The ruling 16 chain stopped at 02:43 before any 50% run. Its gate required exactly one Spark output run; the 25% attempt 3 record (20261116T080634Z-fraction25, cluster j-0444514339VPNDMSELEO) lists five.
   - Read-only check (Nov 16): spark-runs-before.txt was empty, and all five Spark run directories were created during this cluster's Spark step (objects 02:27:41 to 02:40:11; step 02:27:15 to 02:40:13). They are the PipelineJob driver run run-0b5cdafae10b5017a8e20526b8b60173 (manifest, stages and started markers only; the runId in the step stdout, attempt 1) and four stage runs: canonical and graph run-eaa0031b91c9055d9bbae0e0cdf85704, analytics run-09582fb1ce6e59e613f1adc1c688811a, analytics and paths run-04f6cfa764ce7b39208d2cb84713f841, summaries, temporal and export run-968b42b2d6d645401569eceeb903c43e. This is the Phase 6 driver design, not a defect. The "exactly one Spark run" expectation was the human's error (written without reading the script; same lesson as rulings 12 and 15).
   - The Spark step read this cluster's Hadoop run (HADOOP-RUN run-0b051c05dfc9b385b76b08818f0425c7). Every other ruling 16 check passed: exit 0, TERMINATED with ALL_STEPS_COMPLETED, three steps COMPLETED, one Hadoop run, J01 ACCEPTED 27993, openIngress [], no AccessDenied or 403, no active cluster, 24 normalized instance hours. 25% attempt 3 is accepted as the 25% size-scaling run.
   - The 0-byte key at curated/spark/snapshot=2026-10-03/ (02:27:41) is an S3A directory marker; no action.
   - Corrected gate in /tmp/p14-chain2.sh (copied to evidence at Step W1): as ruling 16, except sparkRuns must hold exactly 5 entries, stateChangeReason must be ALL_STEPS_COMPLETED, the Spark stdout driver runId (attempt 1, under curated/spark) must be among sparkRuns, and the Spark step HADOOP-RUN must equal the record's Hadoop run. HEAD, clean tree, 160 normalized instance hours, no rerun and one cluster at a time are unchanged. The chain rechecks 25% first, then runs 50% and 100%.
   - Approved by Achyut Patel.

18. 50% Spark out of memory; shuffle partitions 40; order 50, 25, 100 (recorded during the window in operations/rulings/phase-14-ruling-18.txt):
   Phase 14 human ruling 18 (cloud window 1: 50% Spark out of memory; shuffle partitions 40; rerun order 50, 25, 100)
   - 50% attempt 1 (operations/pipeline-runs/20261116T212028Z-fraction50, cluster j-01340552J6069AJL37F6, chain 2 of ruling 17): classpath-probe COMPLETED; hadoop-pipeline COMPLETED in 27.5 min (J01 ACCEPTED 55598, run-06f96f2488d29eddc55405db35b47d84); spark-pipeline FAILED after 18.5 min. Cluster TERMINATED_WITH_ERRORS / STEP_FAILURE, 24 normalized instance hours, openIngress [], no active cluster. The chain stopped; nothing was rerun.
   - Cause (step stderr, read Nov 16): org.apache.spark.memory.SparkOutOfMemoryError [UNABLE_TO_ACQUIRE_MEMORY] in stage 7351, task 0 failed 4 times; ShuffleMapTask over a union with an in-memory hash join (HHJMemoryManager), in job 10 (TemporalSummaryExportJob.scala:442, the summaries table written through JobRun.write). Stage runs for jobs 1-2 (run-6073da216a37e66998e4c9bf138621c4), jobs 3, 4, 8 (run-ede5358add54fa4dab26f0e0bc4048ce) and jobs 5-7 (run-20b32a78cf3dd0225c71f6d5b3085eb0) completed; jobs 9-11 (run-691ce01ec064eaf487eed39b3819fbbf, temporal only) and the driver (run-c8eceb83265a2a5be2bb2270ace714eb) have no manifest. They stay in S3 as incomplete runs and are not used.
   - Defect P14-D6 (configuration): run-bounded-pipeline.sh defaults to --shuffle-partitions 16, while PipelineJob documents and was proven locally at 100% with 40 (modules/spark/JOBS.md). At 50% on 2 m6g.xlarge core nodes (executor 4 cores, about 9 GB), 16 partitions made each hash-join partition too large. No code change in window 1: the flag fixes it. The script default and the JOBS.md "2-3 x executor cores" guidance go to the Phase 17 notes (and an optional later infra-engineer change).
   - Ruling 6 amended: every window 1 size-scaling run uses --shuffle-partitions 40 (other flags default; --reducers 2). The 25% attempt 3 record (16 partitions, accepted by ruling 17) stays as evidence but is not a size-scaling point; 25% is rerun at 40 so all three points share one configuration. Order: 50, then 25, then 100 (the failed fraction first, so a fix that does not hold costs one cluster).
   - Rerun mechanics (read from JobRun.scala, StableIds.scala, step.sh): the attempt is part of the run ID and a rerun takes the smallest unused attempt, so every rerun writes new run directories and never overwrites; step.sh finds the one new Hadoop directory.
   - Chain 3 (/tmp/p14-chain3.sh, copied to evidence at Step W1): as ruling 17, plus configuration.shufflePartitions == 40; the Spark driver attempt may be 1 or higher. Cost: 72 normalized instance hours before the chain; the 160 stop rule (ruling 8) is checked before each run.
   - If 50% fails again: stop, no rerun; the next fix is Spark executor settings in scripts/emr/step.sh (repair by human exception), decided by ruling.
   - Approved by Achyut Patel.

19. Second 50% failure: EMR HybridHashJoin; repair-03 by human exception (Nov 16).
   - 50% attempt 2 (operations/pipeline-runs/20261116T232332Z-fraction50, cluster j-05229833T1R31VERH2IA, chain 3, --shuffle-partitions 40): classpath-probe and hadoop-pipeline COMPLETED (Hadoop 27.2 min); spark-pipeline FAILED after 19.4 min at the same place as attempt 1: SparkOutOfMemoryError UNABLE_TO_ACQUIRE_MEMORY (19), stage 7282 task 6 failed 4 times, TemporalSummaryExportJob.scala:442. The traces name EMR classes: HybridHashJoinGenerator (72), HHJMemoryManager (24), allocateHHJPage (12). Incomplete Spark runs run-690a179ffc1af8789a22c54f0841d03a (driver) and run-877c5ec9b77776fef6f4120632c8a9ef (jobs 9-11, temporal only) are not used. 24 normalized instance hours; no active cluster; nothing rerun.
   - Defect P14-D7: emr-spark-8.0.0 plans these joins as EMR's HybridHashJoin, which builds in-memory hash tables and failed on 2 m6g.xlarge core nodes at 50%. Apache Spark (every local run, including the full 100% run with a 6 GB heap) plans a sort-merge join, which spills. More shuffle partitions did not help (16 and 40 both failed). Source for the switch: NVIDIA/cudf-spark pull request 16253 (third-party, not AWS documentation) reports that EMR 7.13 and 8.0 plan HybridHashJoin and that spark.sql.join.hybridHashJoin.enabled=false gives a sort-merge join as on Apache Spark. No Spark setting can be passed without a code change (JobRun sets only shuffle partitions, UI and cleaner; step.sh passes no --conf).
   - Repair-03 by human exception (a third Phase 14 repair round, anticipated by ruling 18): work order 4 (infra-engineer), then code review 3 (code-reviewer, Ponytail off for the whole session). If code review 3 requests changes, the exit is human escalation; there is no repair-04. Scripts and docs only: no Scala, Terraform, cleanup.sh or other script changes.
   - Mid-window repair rule (rulings 12-15): the human commits the repair, runs package.sh at the new HEAD, then runs one chain at the new package: 50, then 25, then 100, each with --shuffle-partitions 40 (explicit, though the new default is 40). Gate as ruling 18, plus the classpath-probe stdout is quoted in the results ruling (whether the hybridHashJoin key was found). All earlier fraction records stay as evidence; the size-scaling points are only the three new runs.
   - During the session the orchestrator may write docs/agent-runs/phase-14/repair-03.md and review-report.md (they are committed with the repair, as for repair-01 and repair-02); everything else under ruling 5.
   - Cost: 96 normalized instance hours so far (about 2.8 actual cluster hours, about $1.65). The ruling 8 stop (above 160 before the next fraction) stays; expected window 1 total about $3.70 actual.
   - Approved by Achyut Patel.

## Work order 4: infra-engineer (repair-03: P14-D6 and P14-D7; rulings 18 and 19)

Change only scripts/emr/step.sh, scripts/run-bounded-pipeline.sh and scripts/README.md.
1. scripts/emr/step.sh, spark mode: add `--conf spark.sql.join.hybridHashJoin.enabled=false` to the spark-submit command (before `--class`), with a short comment: on emr-spark-8.0.0 EMR plans these joins as HybridHashJoin, which failed with UNABLE_TO_ACQUIRE_MEMORY in job 10 at 50% (P14-D7); the switch gives a sort-merge join as Apache Spark plans locally. Keep every other argument, the exec, the timeout and the positional checks unchanged.
2. scripts/emr/step.sh, classpath mode: after the existing output, print `== hybrid hash join settings in the Spark jars`, then the distinct strings matching `spark.sql.join.hybridHashJoin[A-Za-z.]*` found in /usr/lib/spark/jars/spark-*.jar (for example `unzip -p "$j" 2>/dev/null | grep -a -o ...`), sorted and deduplicated, or `none found`; print `unzip not available` if unzip is missing. Wrap the scan in `timeout 120`. This mode must still never fail (it runs under set +e and exits 0).
3. scripts/run-bounded-pipeline.sh: default shuffle partitions 40 instead of 16 (P14-D6; the PipelineJob default in modules/spark/JOBS.md), with a one-line comment citing ruling 18. Update any usage or help text that states 16. The 2 to 200 bounds stay.
4. scripts/README.md: in the run-bounded-pipeline.sh / EMR part, add two short lines: the hybrid-hash-join switch and why; the shuffle default 40 and why.
Checks: `bash -n` on both scripts; if the step.sh positional-argument harness from repair-02 still exists in scratch/, rerun it and report each case with its captured exit code; show `grep -n -B2 -A5 'spark-submit --master' scripts/emr/step.sh` and the changed lines of run-bounded-pipeline.sh. No aws, no git, no sbt, nothing under scripts/cleanup.sh. Report COMPLETE or BLOCKED-ON with the owner.

### Code review 3: code-reviewer

Review the repair-03 change set (the orchestrator supplies git status and git diff of the three files, written to its scratchpad) against work order 4 and rulings 18 and 19. Check: the --conf sits inside the existing exec and keeps argument order and quoting; the classpath scan cannot fail the step or run unbounded; nothing else changed behaviour; README text matches the code; run-bounded-pipeline.sh stays bash 3.2 compatible. Verdict APPROVE or REQUEST_CHANGES with numbered findings (major or minor).

20. Repair-03 accepted; chain 4 (Nov 16).
   - Code review 3 APPROVE (no critical or major findings). Minor findings 1 (a timed-out or mis-scoped jar scan reads the same as "key absent"), 2 (README describes the scan under spark-pipeline, not classpath-probe or the step.sh header) and 3 (the sort-merge claim is unverified on EMR) are Phase 17 notes for infra-engineer; no repair-04 (ruling 19). Finding 3 is settled by the 50% rerun and the classpath-probe output, quoted in the results ruling with finding 1 in mind.
   - Process deviation: infra-engineer ran read-only git status --porcelain and git diff --stat, against work order 4 and Phase 7 ruling 17. Disclosed, nothing changed; recorded, no action. Evidence gaps (no captured jar-scan log; real GNU timeout not run; harness overall exit code not logged, as in repair-02) are residual.
   - Evidence: the change set and the harness are in evidence/repair-03/.
   - Chain 4 (/tmp/p14-chain4.sh, copied to evidence at Step W1): package.sh at this commit, then 50, 25, 100 with --shuffle-partitions 40. Gate as ruling 18: commit = HEAD; shuffle 40; TERMINATED with ALL_STEPS_COMPLETED; three steps COMPLETED; one Hadoop and five Spark runs; the driver run among them; Spark read the record's Hadoop run; J01 ACCEPTED 55598 / 27993 / 110867; openIngress empty; no AccessDenied or 403. The chain also logs the classpath-probe hybrid hash join lines. Stop rules of rulings 7, 8, 18 and 19 unchanged; if 50% fails again, human escalation (no further repair in window 1).
   - Factory-metrics rows at Step W1: infra-engineer work order 4 (repair-03) COMPLETE; code-reviewer code review 3 APPROVE.
   - Approved by Achyut Patel.

21. Escalation after the third 50% failure; option A+ (recorded during the window in operations/rulings/phase-14-ruling-21.txt):
   Phase 14 human ruling 21 (cloud window 1 escalation after the third 50% failure; option A+)
   - 50% attempt 3 (operations/pipeline-runs/20261117T010927Z-fraction50, cluster j-0442672LR2FYKZBT6WQ, package deef29516bf395ac2b551beb93710b6a48a937a8, --shuffle-partitions 40, hybrid hash join off): classpath-probe and hadoop-pipeline COMPLETED (Hadoop 27.6 min); spark-pipeline FAILED after 18.9 min in job 10 (TemporalSummaryExportJob.scala:442), stage 7276 task 0 failed 4 times. SparkOutOfMemoryError (16), UNABLE_TO_ACQUIRE_MEMORY (5), now inside EMR aggregation classes (IntEncodedHashTable, EntryOffsetMap) in a sort-based aggregate after a sort-merge join. No HybridHashJoin frames: the repair-03 switch took effect. The classpath probe found spark.sql.join.hybridHashJoin.* keys in the Spark jars (the readout showed the first five lines only). Incomplete Spark runs run-9fbd0ad611075346eb3cf7d0f627b521 and run-bf69f4131d44125a499d486e1713b1f3 are not used. 24 normalized instance hours; no active cluster; nothing rerun.
   - Finding P14-D8: on emr-spark-8.0.0 with 2 m6g.xlarge core nodes (4 tasks per executor of about 9 GB), EMR runtime operators in job 10 need more memory per task than Apache Spark does locally (the local 100% run completed with a 6 GB heap). At 50%, jobs 1 to 8 complete and job 10 fails. No documented switch for the EMR aggregation map was found. Not fixed in window 1 (ruling 20).
   - Decision A+ (no code change): chain 5 at package deef295 with --shuffle-partitions 40: 25%, then 100%. 25% must pass the full ruling 20 gate (J01 ACCEPTED 27993) or the chain stops. 100% runs once whatever its Spark outcome; a Spark failure at 100% is accepted in advance as data (Hadoop at full size with J01 ACCEPTED 110867, and how far Spark gets). No fraction is rerun.
   - Reporting: size scaling at package deef295 is Hadoop at 25, 50 and 100%; Spark complete at 25% and failed in job 10 at 50% (and at 100% if so). Earlier records (25% attempt 3 at fa93806, complete; 50% attempts 1 and 2) are supporting evidence. Experiment A baseline: the 25% complete run at deef295, with the 100% Hadoop run as the largest point (amends the plan that named the 100% record).
   - Option B (Spark executor cores 2, by repair) is deferred until after window 2 and needs its own ruling; the window 1 stack stays applied and idle.
   - Cost: 120 normalized instance hours before chain 5 (about 3.7 actual cluster hours, about $2.20). The ruling 8 stop is checked before 25% and before 100% (144 expected before 100%). Expected window 1 total about $3.70 actual, under the $5 cap.
   - Recorded in operations/ because a commit would move HEAD off the package commit; appended to prompt.md at Step W1.
   - Approved by Achyut Patel.

22. Window 1 results; window 1 closed (Nov 17).
   - All runs: us-east-2, account 834519512655, emr-spark-8.0.0, 1 primary + 2 core m6g.xlarge on-demand. Table: evidence/window-1/p14-window1-results.tsv (cluster minutes from cluster.json, step minutes from run.json, J01 from step stdout).
   - 25% attempt 1 (20261116T063500Z): no run.json. 25% attempt 2 (1232927, shuffle 16, j-0518231NIANENLFGS7S): Hadoop 16.7 min; Spark failed after 0.1 min (P14-D5; repair-02). 25% attempt 3 (fa93806, shuffle 16, j-0444514339VPNDMSELEO): complete; cluster 34.3, Hadoop 16.6, Spark 13.0 min (ruling 17).
   - 50% attempts 1 and 2 (fa93806, shuffle 16 and 40) and 3 (deef295, shuffle 40): Hadoop 27.5 / 27.2 / 27.6 min, J01 ACCEPTED 55598 each; Spark failed in job 10 (TemporalSummaryExportJob.scala:442, SparkOutOfMemoryError) after 18.6 / 19.4 / 18.9 min (rulings 18, 19, 21).
   - 25% at deef295 (j-03327281MRPKUSN1LE0M): complete, passed the full gate; cluster 36.3, Hadoop 17.2, Spark 13.3 min; J01 ACCEPTED 27993; Hadoop run run-aa940ec80cef96879f88d1f2407a0fc7; Spark driver run run-9ba997a435161d92fdf3b34bc635f263 (five Spark runs). This is the Experiment A baseline (ruling 21).
   - 100% at deef295 (j-045032852E7NSUSLWWY): Hadoop 53.3 min, J01 ACCEPTED 110867, run-503520c5975c7bd5c73bf45b14f17749; Spark failed in job 10 after 36.3 min (SparkOutOfMemoryError, TemporalSummaryExportJob.scala:442), accepted in advance by ruling 21; cluster 95.6 min; 48 normalized instance hours.
   - Size scaling at deef295 (x = DBLP works 27,993 / 55,598 / 110,867): Hadoop 17.2 / 27.6 / 53.3 min (1.60x, then 1.93x per doubling). Spark completes only at 25% (13.3 min); at 50% and 100% it reaches job 10 and fails (P14-D8).
   - Every record with a cluster: openIngress empty; no AccessDenied or 403; no active cluster after each run.
   - Cost: 344.7 cluster minutes (5.74 h), about $3.32 for EMR instances and fees at on-demand rates (3 x ($0.154 + $0.0385) per hour); 192 normalized instance hours. AWS month-to-date on Nov 17 was $6.88 (billing lags about a day). The AWS spend-limit email forecasting $290.67 is a linear projection from the window 1 burst; nothing was running. The window 1 stack stays applied and idle.
   - Images: the fa93806 and deef295 ECR scans are COMPLETE with identical counts (CRITICAL 10, HIGH 48, MEDIUM 62, LOW 18, UNDEFINED 6). The CRITICAL decision is a window 2 preparation item.
   - Phase 17 notes: P14-D6 (shuffle default), P14-D7 (EMR HybridHashJoin; minor 3 of code review 3 is settled: no HybridHashJoin frames after repair-03, and the probe found spark.sql.join.hybridHashJoin.* keys), P14-D8 (EMR aggregation memory in job 10; option B deferred until after window 2 by its own ruling), and code review 3 minors 1 and 2.
   - Window 1 is closed. Phase 14 stays open until window 2.
   - Approved by Achyut Patel.
