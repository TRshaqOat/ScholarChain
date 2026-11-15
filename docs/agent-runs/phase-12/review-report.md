## Code review 1: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 (spec Phases 12 and 13, merged by D-10), session 2, code review 1. HEAD 655436b5c2c1ca7bd8cd41bec5aca81a16b13c20, work orders 1 to 8 uncommitted.
VERDICT: REQUEST_CHANGES

The verdict rests on one open MAJOR finding: the EMR instance role IAM is broader than the jobs need (F1). Everything else is MINOR.

SCOPE REVIEWED:
- The change set file /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/763afe08-b82c-4ea1-a8c8-c9ea56c1e078/scratchpad/phase-12-cr1-changeset.txt, all 7,923 lines: git status, git diff HEAD, and every untracked file.
- Checked against the working tree:
  - infra/terraform/** (all 13 modules, plus envs/course and README.md);
  - scripts/** (all 11 scripts, lib/common.sh, emr/step.sh, README.md);
  - modules/storage (Cli, LiveAws, Dynamo, ObjectStore, LoadNeptuneCli, live adapters);
  - modules/services (Composition Launcher, Services and Worker readiness, SERVICES.md, openapi.yaml);
  - modules/core (ConfigLoader, scholarchain.conf);
  - modules/hadoop Pipeline.scala options and modules/spark SparkSettings and JobRun;
  - datasets/scripts (lib.sh, make_fraction.*);
  - .gitignore, build.sbt services settings, .claude/commands/deploy.md;
  - spec Phases 12 and 13, and "Cleanup requirements";
  - docs/agent-runs/phase-12/prompt.md and agent-report.md;
  - scratch/phase-12-logs/ (wo3-check-sh, wo3-docker-stage, wo6-sbt-check-2, wo7-pipeline-dry-run).
- Not read: labels/, scratch/stores-live/, scratch/er/adjudication-live/.

FINDINGS (ranked)

F1. MAJOR. EMR instance role grants write, delete and read on prefixes the EMR jobs never use.
- Where: infra/terraform/modules/emr/main.tf:45 (read_prefixes), :48 (write_prefixes), :147 and :154-158 (the statements).
- Failure input: any EMR job run by scripts/run-bounded-pipeline.sh. The jobs only read raw/, curated/hadoop, artifacts/jobs/ and write curated/hadoop, curated/spark, logs/emr/:
  - Hadoop --out is s3a://<bucket>/curated/hadoop and Spark --out is s3a://<bucket>/curated/spark (run-bounded-pipeline.sh, the steps JSON).
  - JOBS.md and JobRun.scala:35 write only under <out>/snapshot=<d>/run=<r>.
  - No Hadoop or Spark main source writes graph/, semantic-context/, runs/ or reports/ (grep).
  - WO3 assumption 3 confirms StoreConversion is not run on EMR.
- Even so, the role holds s3:PutObject and s3:DeleteObject on graph/*, semantic-context/*, runs/* and reports/*, and GetObject on graph/, semantic-context/, runs/ and reports/.
- Impact: a buggy or misconfigured EMR step (for example a wrong --out) can overwrite or delete-mark:
  - the Neptune bulk-load CSVs (graph/neptune);
  - the store-conversion run manifests (runs/), which are the M3 integrity anchor;
  - the evidence archives (reports/evidence/).
- This fails the Phase 12 gate "IAM policies are scoped to actual resources and actions". Versioning limits the damage but does not make the grant least-privilege.
- Law 5 makes over-broad IAM at least MAJOR.
- Fix direction: write and delete on curated/* and logs/emr/* only; read on raw/*, curated/*, artifacts/*.
- The README IAM summary (infra/terraform/README.md:227) and the WO2 report repeat the over-broad list as intended.
- Owner: infra-engineer.

F2. MINOR. WO7 tool writes an untracked .pyc into datasets/scripts/__pycache__/, which dirties the tree and blocks every gated script.
- Where:
  - datasets/scripts/make_fraction.py:36-38: importlib spec_from_file_location plus exec_module of dblp_extract.py.
  - `python3 -I` does not imply -B, so SourceFileLoader caches bytecode.
  - The file datasets/scripts/__pycache__/dblp_extract.cpython-313.pyc exists now (22,772 bytes, in git status).
  - .gitignore has no __pycache__ entry.
- Failure input: run `datasets/scripts/validate.sh` (it now runs make_fraction.py --self-test, validate.sh:26) or make_fraction.sh. Then run scripts/plan.sh, deploy.sh, package.sh, run-bounded-pipeline.sh, load-stores.sh, run-experiment.sh or cleanup.sh.
- Impact:
  - require_clean_tree (scripts/lib/common.sh) lists the .pyc and exits 3. Every cloud window 1 script stops until a human deletes it, and the dataset gate recreates it on each run.
  - A broad `git add datasets/` would commit a binary build artifact.
  - The WO7 report's FILES CREATED does not disclose this file.
  - It fails closed, so the severity is MINOR.
- Owner: dataset-engineer (set sys.dont_write_bytecode, or run with -B, before exec_module). Optional second owner: build-engineer, to add `__pycache__/` to .gitignore.

F3. MINOR. A non-idempotent ECS call goes through the retrying wrapper, against ruling 6 and the scripts/README retry rule.
- Where: scripts/deploy.sh:148 (`aws_retry ecs update-service ... --force-new-deployment`), inside the --resume path.
- Failure input: a read timeout after ECS has accepted the call. The CLI's standard retry sends it again.
- Impact: a second forced deployment cycles the tasks again. That is benign in end state, but it breaks "retries only for idempotent calls", and scripts/README.md:21-23 lists only "same desired state" calls for aws_retry.
- Fix direction: use aws_once.
- Owner: infra-engineer.

F4. MINOR. check.sh does not run all non-cloud validation.
- Where: scripts/check.sh:50-55 (bash_syntax_ok covers only .claude/hooks/*.sh, scripts/*.sh and scripts/*/*.sh) and the gate list at :~75-81.
- Failure input: a syntax error in datasets/scripts/*.sh, or a failing dataset validation (datasets/scripts/validate.sh: manifest, samples, the make_fraction self-test). check.sh still exits 0.
- Impact: the Phase 13 gate "a clean checkout can run all non-cloud validation through the documented script" and Phase 13 item 1 ("schema validation, shell syntax checks") are only partly met. The dataset-engineer's validation is reachable only by hand.
- Owner: infra-engineer.

F5. MINOR. The Haiku call cap is itself unbounded.
- Where: infra/terraform/envs/course/variables.tf:390-394 (`haiku_calls_per_minute_cap`, no validation), used by the llm_requests_per_minute validation at :401-404.
- Failure input: haiku_calls_per_minute_cap = 32 in course.tfvars with service_max_counts query_api = 2 and worker = 2.
- Impact:
  - The "at most 8 live Haiku calls per minute" bound (WO2 carry-forward) passes validation at 32 per minute. The core loader still caps each process at 8.
  - The bound is only as strong as the tfvars review. The plan summary does not flag it as a high-cost change.
- Owner: infra-engineer.

F6. MINOR. Availability zones come from an unpinned data source, so the plan can show an unexplained network replacement.
- Where: infra/terraform/modules/foundation/main.tf:29-36 (aws_availability_zones state = "available") and :~59 (slice(sort(names), 0, 2)). Consumed by network/main.tf aws_subnet.*.availability_zone, which forces a new subnet.
- Failure input: us-east-2a reports a non-available state during an AWS event when plan.sh runs.
- Impact:
  - The AZ list shifts to b and c, and every subnet is planned for replacement, cascading to the Neptune subnet group, the ALB and the ECS services.
  - plan.sh's STATEFUL list (plan.sh:~108) does not include subnets, so the summary would not flag it. Only a full read of the plan would.
  - Checkov flagged this as CKV_AWS_394 and WO2 waived it as minor.
- Fix direction: pin the two AZ IDs in a variable.
- Owner: infra-engineer.

F7. MINOR. Documentation claims the code does not back.
- (a) infra/terraform/README.md:237 and WO2 report :162 say every role trusts one service principal with aws:SourceAccount.
  - The EMR instance role trust (infra/terraform/modules/emr/main.tf:115-124) has no condition.
  - Owner: infra-engineer.
- (b) scripts/README.md:21 says every AWS CLI call has --cli-connect-timeout 10 --cli-read-timeout 120.
  - scripts/emr/step.sh:18 (fetch), :43 (aws s3 ls) and the BOOT string in run-bounded-pipeline.sh (`aws s3 cp`) use the CLI defaults.
  - Owner: infra-engineer.
- (c) infra/terraform/modules/emr/main.tf:~168-171 says no EMR group admits traffic from the internet.
  - The groups ignore ingress drift (`ignore_changes = [ingress, egress]`), so Terraform can neither show nor enforce what EMR adds.
  - The claim is unverified until the first cluster runs.
  - Owner: infra-engineer.

F8. MINOR. Stale text beyond the ruling 16 list.
- scripts/README.md:207: "which no tool produces yet ... exit 3 until they exist".
- scripts/README.md:313-317: the storage and service dependencies are described as open, and "deploy.sh ... fails at the stable wait until the launcher change is merged".
- infra/terraform/README.md:36-37: the empty modules/secrets/ "could not be removed". The directory no longer exists (verified).
- infra/terraform/README.md:218-221: "Two storage changes are needed first (BLOCKED-ON storage-engineer)".
- Owner of these four: infra-engineer.
- modules/storage/src/main/scala/scholarchain/storage/live/LiveAws.scala:31-32: the doc comment says the storage CLIs build a LiveTarget "only from both --live and --profile". WO5 made --profile optional in the four loaders.
- Owner: storage-engineer.

F9. MINOR. Script log evidence is gitignored.
- Where: .gitignore:28 (`*.log`) against:
  - deploy.sh `$REL.apply.log`;
  - load-stores.sh `$REC/<loader>.log` and `neptune-*.log`;
  - cleanup.sh `apply.log`.
- scripts/README.md:509-520 and the load-stores Evidence text present these as committed evidence.
- Impact: the apply and loader logs never reach Git. archive-evidence.sh still copies them with cp -R, so it is an evidence-path gap, not a loss.
- Owner: build-engineer (.gitignore exception for operations/**), or infra-engineer (rename to .txt). Route through the orchestrator.

No CRITICAL finding: I found no secret, no constitutional change, and no weakening of the floor.

RULING 16 STALE TEXT: each confirmed to exist as described.
- infra/terraform/envs/course/variables.tf:397: exists ("Takes effect only once the core loader honours that variable (BLOCKED-ON domain-engineer, see README)"). WO4 merged the override.
- scripts/load-stores.sh:43-44: exists ("Depends on (not yet merged): storage-engineer ... Until then step 2 fails at the task").
- scripts/run-bounded-pipeline.sh:18-20: exists ("BLOCKED-ON dataset-engineer: no tool produces these yet").
- scripts/run-bounded-pipeline.sh:98: exists (die message "25/50 percent subsets: BLOCKED-ON dataset-engineer").
- scripts/README.md:304: exists (the Open dependencies item, lines 303-304, "No tool under `datasets/` produces them").
- The run-bounded-pipeline.sh --dry-run output does not print the resolved input paths: confirmed (wo7-pipeline-dry-run.log and the dry_run_exit argument list).
- modules/storage/STORAGE.md:487: exists ("`Launcher.backend` ... still requires `--profile`"). WO6 removed that requirement.

SPEC GATE ITEMS

Phase 12:
- No unexplained stateful destroy or replacement (judged from code): PASS with a caveat.
  - The only designed stateful destroy is the runtime_enabled true-to-false teardown (Neptune, 7 tables, the vector bucket and 5 indexes, SQS, the secret with a 7-day window). It is documented in infra/terraform/README.md and the WO2 report.
  - Force-new settings (the S3 Vectors index settings, the table names through the prefix) are documented.
  - Caveat: F6 can produce an unexplained network replacement.
- IAM scoped to actual resources and actions: FAIL.
  - The cause is F1, the EMR instance role prefixes.
  - Everything else matches code use:
    - DynamoDB: GetItem, Query and PutItem only. The code calls only getItem, query and putItem.
    - graph-evidence-api: Query on the GSI ARN only. Its readiness probe uses evidenceItem, a GSI query (Services.scala:332).
    - SQS: Send for query-api; Receive, Delete and ChangeMessageVisibility for the worker. This matches ServiceLive.scala:247-267.
    - S3 Vectors: QueryVectors and GetVectors. This matches VectorsLive.
    - Bedrock: InvokeModel only. BedrockHaikuClient and BedrockLive use invokeModel.
    - Neptune: ReadDataViaQuery for the services; StartLoaderJob and GetLoaderJobStatus for the loader. This matches NeptuneLive and NeptuneGraphReader.
    - S3 prefixes match SERVICES.md section 4 and RUNTIME.md "obj.*".
  - The unconditioned ListBucket on query-api and the worker is an accepted risk (ruling 11).
- Buckets and databases not public: PASS.
  - Data bucket: all four public access blocks, BucketOwnerEnforced, TLS-only policy.
  - Vector bucket and ECR: no policy.
  - Neptune: private subnets, security group ingress from the four task groups only.
  - DynamoDB: no resource policy.
  - The only CIDR ingress is the ALB on port 80 (ruling 1; the HTTP-only risk is accepted).
- Default capacity bounded: PASS with F5.
  - ECS: desired 1, maximum validated 1 to 2. Fargate sizes validated (at most 2 vCPU, 8 GiB).
  - EMR: release pinned, 1 primary plus 1 to 2 core, a Graviton type, on-demand. Re-checked in run-bounded-pipeline.sh.
  - Neptune: class allowlist.
  - CodeBuild: concurrency 1, 30-minute timeout.
- Secrets absent from source and outputs: PASS.
  - The secret has no version. Outputs give the ARN and name only.
  - course.tfvars has no secrets and no email (alarm_email comes from TF_VAR).
  - The smoke test passes the key to curl on stdin (--config -) only.
  - deploy.sh uses DescribeSecret and never reads the value.

Phase 13:
- A clean checkout runs all non-cloud validation through check.sh: PARTIAL.
  - check.sh exists and passed with exit 0 at WO3 (wo3-check-sh.log:1713). That was a working tree, not a clean checkout, and before WO4 to WO8. It was not rerun on the final tree.
  - It omits dataset validation and datasets shell syntax (F4).
- The plan checksum is recorded: PASS by code.
  - plan.sh writes plan.sha256 and plan-meta.json (scratch/plans, gitignored) and summary.md with the SHA to operations/plans.
  - archive-evidence.sh copies sha256, meta and summary, never the plan or its JSON.
  - Not exercised.
- Deployment cannot proceed without the documented human approval: PASS by code.
  - deploy.sh applies only the saved plan whose sha256 equals --approved-plan-sha256, and that also matches plan-meta.json.
  - It refuses a dirty tree and a HEAD, account or Region that differs from the plan's. It never uses -auto-approve.
  - Caveat: the checksum is computable by anyone who holds the plan file. The human approval itself is enforced by /deploy (.claude/commands/deploy.md:3,13) and the permission prompt, not by the script.
- The smoke test exercises a real bounded query: PASS by code.
  - B07 sync POST /v1/query with an Idempotency-Key.
  - It requires a qry- ID and a claim with an evidence ID, then GETs /v1/evidence/<id>, replays for the same queryId and answerHash, and checks 401 UNAUTHORIZED.
  - Field names match openapi.yaml: SyncQueryResponse queryId, answer.answerHash, claims[].evidenceIds; EvidenceResponse evidenceId and evidenceType; ErrorBody error; Status ok and ready; the X-Request-Id pattern.

RULING 15 CHECKS
- Ruling 10 deviations: confirmed as recorded and disclosed.
  - (a) WO1: agent-report.md:61.
  - (b) WO3: the cleanup.sh wrapper runs, agent-report.md:505.
  - I found no further cleanup.sh or wrapper invocation in WO4 to WO8. WO5 states it at :719. WO7 ran run-bounded-pipeline.sh --dry-run directly, as WO7 required.
  - The guard gap stays a factory-engineer backlog item; no Phase 12 constitutional change was made.
- The ECS task definitions and load-stores.sh pass arguments the CLIs accept after WO5 and WO6: PASS.
  - Services: entryPoint /opt/docker/bin/<service>, with command `--live --region --snapshot --graph-run --items-run --vectors-run`.
    - Launcher.Opts holds all of these, and Launcher.backend no longer requires --profile (Composition.scala:404-452).
    - The 20 environment names all exist in ConfigLoader.EnvOverrides.
    - LauncherSpec loads the same 20 names. That list is a hand copy, so it can drift from Terraform.
  - Loader task: `-main scholarchain.storage.cli.LoadNeptuneCli --live --stage-from-bucket --dir /tmp/stores/s3/graph/neptune/snapshot=<d>/run=<r> --out /tmp/stores`, and `--live --status <id>`.
    - These are accepted: LiveFlags + StageFlag; options dir, out and status (LoadNeptuneCli.scala:41-57).
    - The prefix equals S3Layout.neptuneRoot, so the stageFromBucket --dir check passes.
    - The output formats match load-stores.sh's sed patterns: "started load <id>; ..." and "load <id> status <S> ..." (LoadNeptuneCli.scala:75,176).
    - The task-role S3 grants cover the staged keys, the ListBucket prefix and the HeadObject checks.
  - Laptop loaders: these forms are accepted (Cli.LiveOpts plus each CLI's options):
    - `LoadS3Cli --manifest runs/<stage>/... --root`;
    - `LoadDynamoCli --dir ... --concurrency`;
    - `LoadVectorsCli --input ...`;
    - all with `--live --profile --region`.
  - The stage names store-conversion, embed and load-window-summary match the code.
- run-bounded-pipeline.sh reads the WO7 layout: PASS.
  - It reads scratch/datasets/fraction=<p>/{dblp/works.xml, openalex/works.jsonl, crossref/works.jsonl, orcid/records.jsonl.gz} and the shared raw/dblp.dtd.
  - make_fraction.sh writes exactly that layout (SCRATCH=scratch/datasets in lib.sh:18; NAMES in make_fraction.py; the DTD path).
  - Both dry-runs exited 0 (wo7-pipeline-dry-run.log).
  - The Hadoop and Spark arguments match Pipeline.scala:49-57 and SparkSettings.

OWNERSHIP CHECK (each changed path, its owner, and whether the author matches)

| Path | Owner | Author matches |
|---|---|---|
| datasets/dataset-manifest.yaml, datasets/scripts/validate.sh, datasets/scripts/make_fraction.{py,sh}, datasets/schemas/fractions.md | dataset-engineer | yes |
| datasets/scripts/__pycache__/ | dataset-engineer's tree | yes, but a generated, undisclosed artifact (F2) |
| modules/core/** (scholarchain.conf, ConfigLoader.scala, ConfigLoaderSpec.scala) | domain-engineer | yes |
| modules/hadoop/JOBS.md | hadoop-engineer | yes |
| modules/services/** (SERVICES.md, Composition.scala, LauncherSpec.scala) | service-engineer | yes |
| modules/storage/** (STORAGE.md, cli/*, live/LiveAws.scala, CliSpec, LiveRequestSpec) | storage-engineer | yes |
| infra/** and scripts/** | infra-engineer | yes |
| docs/agent-runs/phase-12/agent-report.md | orchestrator report assembly | allowed (the folder has no commit.txt) |

No change falls outside its owner's rows.

FLOOR AND FENCE CHECK
- No change to CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, hooks, permission rules, markTestRun or any reviewer fence.
- build.sbt and the version ledger are unchanged.
- No widening of tools, paths or MCP access.
- The ruling 10 guard gap (cleanup-script rule anchored on command position) is a pre-existing limitation, carried to the backlog, not a new weakening.
- Scripts never use -auto-approve.
- terraform apply runs only on a saved, checksum-matched plan.
- cleanup.sh requires --confirm <plan sha256> and is human-only by instruction and guard.

EVIDENCE GAPS
- scripts/check.sh was last run at WO3 (wo3-check-sh.log), before the WO4 to WO8 changes, and not from a clean checkout. No run on the final tree is recorded.
- sbt check on the final Scala tree (839 passed, 0 failed, 10 pending) is supported: wo6-sbt-check-2.log:984-1452 gives 191+111+116+115+49+141+46+70 = 839, and markTestRun wrote the marker. WO7 and WO8 changed no Scala or sbt files.
- The WO3 claims "-main is honoured" and "prompts are at /opt/docker/prompts" are not in a cited log. wo3-docker-stage.log shows only success, plus the warning "sbt-native-packager wasn't able to identify the docker version. Some features may not be enabled". The first CodeBuild build is the real test of the staged Dockerfile.
- Costs, the Neptune 1.4.8.1 release date and orderability, m6g.xlarge on emr-spark-8.0.0, and the Haiku profile Regions are UNVERIFIED and listed for the human gate (ruling 11).
- shellcheck was never run (SKIPPED: not installed).
- Every AWS call shape (create-cluster steps, the run-task overrides, get-metric-data) is unverified until cloud window 1 or 2.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.

TEST RESULTS (as supplied, quoted):
- Ruling 16: "sbt check: 839 passed, 0 failed, 10 pending."
- WO3: "check.sh exit 0, with all 7 gates OK (shellcheck SKIPPED)."
- WO7: "validate.sh: exit 0, 83 PASS, 0 FAIL"; make_fraction --verify "every line PASS".

VALIDATION RESULTS (as supplied):
- terraform fmt -check exit 0, and validate reported "Success! The configuration is valid." (WO2, WO3).
- bash -n passed on all 13 script files under bash 3.2.57.
- Checkov: 350 passed, 60 failed, all MEDIUM and waived (ruling 11 accepts this).
- My own review found no bash 4 constructs in scripts/ (no associative arrays, mapfile, ${x,,}, namerefs or [[ -v ]]).

ASSUMPTIONS
- The sbt-native-packager forwarder scripts process a later `-main` argument over the forwarder's own, as the WO2 and WO3 reports state from the plugin template. This is not verified by me.
- The ECS task execution role trust accepts the aws:SourceArn ArnLike arn:aws:ecs:<region>:<account>:* condition. This is unverified; if it were rejected, tasks would fail to start, which is not a security issue.

RISKS
- EMR-managed security groups ignore rule drift. After the first cluster, check read-only that no 0.0.0.0/0 ingress (for example port 22) was added. Keep EMR block public access on with no port exception.
- The data bucket's logs/ lifecycle (30 days) and the 14-day log retention will expire runtime audits and ECS logs. If grading needs them, archive them before about Dec 15. archive-evidence.sh does not copy logs/rag-runtime or reports/rag-runtime.
- rollback.sh updates the three services one after another, so query-api and the worker briefly run different revisions.
- The cleanup "final" stage fails at apply while the bucket or ECR is non-empty, and then writes no record.json.
- The cleanup "runtime" plan lists destroys and keeps, but not creates or updates. Only the full printed plan shows those.
- The Docker Hub base-image pull on CodeBuild is an accepted risk (ruling 11).

MANUAL ACTION REQUIRED
- Human: delete datasets/scripts/__pycache__/ before any gated script runs, and do not commit it.
- Human: commit infra/terraform/envs/course/.terraform.lock.hcl with explicit paths.
- Human, at the plan gate: run the read-only checks from ruling 11, and review subnet and IAM lines in the full plan.

BLOCKED-ON OWNER: none (routing only)
BLOCKED-ON EVIDENCE: none

NEXT GATE: findings routed to owners, then re-review; human gate on APPROVE.
- infra-engineer: F1 (MAJOR), F3, F4, F5, F6, F7 and F8 (except LiveAws), and the ruling 16 infra items.
- dataset-engineer: F2.
- storage-engineer: F8 (LiveAws.scala:31-32) and STORAGE.md:487.
- build-engineer: optional .gitignore lines for F2 and F9.
- Re-review needed after the one repair round (ruling 9). Minor items may become residual risks.
````

## Code review 2: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 (spec Phases 12 and 13, merged by D-10), session 4, code review 2. HEAD 3145709e23c92c45ff9cb8dd84f1043920e55882; all Phase 12 changes uncommitted. Scope per ruling 20: only the repair-01 changes, plus what they touch, and the status of every code review 1 finding.
VERDICT: APPROVE

Summary:
- Every code review 1 finding (F1 to F9, including the F7 and F8 sub-items) is RESOLVED in the working tree.
- Every ruling 16 stale-text item, the extra Part C stale-text lines, and ruling 18 are RESOLVED.
- I found three new MINOR items (N1 to N3) and no CRITICAL or MAJOR. All three fail closed or affect only documentation or evidence. Each can be a residual risk or a Phase 14+ item.
- One unverified operational risk (R1, the EMR S3 parent-key probe) is not a repair regression. The human should know about it before cloud window 1.

SCOPE REVIEWED
- Inputs:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/agent-runs/phase-12/prompt.md (rulings 1-21 and the repair-01 work order);
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/agent-runs/phase-12/review-report.md (code review 1);
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/agent-runs/phase-12/repair-01.md (Parts A, B, C);
  - the repair-only diff /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/647b30d8-a29e-43d0-9e41-a7e964cb44a3/scratchpad/phase-12-cr1-to-cr2-repair01.diff, all 789 lines.
- Working-tree files read in full: infra/terraform/modules/emr/main.tf, modules/foundation/main.tf, envs/course/course.tfvars, infra/terraform/README.md, scripts/run-bounded-pipeline.sh, scripts/emr/step.sh, scripts/check.sh, scripts/deploy.sh, scripts/load-stores.sh, scripts/archive-evidence.sh, scripts/lib/common.sh, scripts/README.md, datasets/scripts/validate.sh, datasets/scripts/lib.sh, .gitignore.
- Read in part:
  - envs/course/variables.tf (lines 1-90, 360-440);
  - modules/network/main.tf (20-104);
  - modules/ecs/main.tf (218-252);
  - datasets/scripts/make_fraction.py (1-50);
  - LiveAws.scala (25-45);
  - LoadNeptuneCli.scala (8-82);
  - JobRun.scala (28-47).
- Searched with Grep: the EMR role in iam/main.tf, every AZ consumer, every reader of the old .log names (scripts, infra, modules, .claude/commands), the non-idempotent aws_retry calls, the Hadoop and Spark FileSystem calls, Cli.liveTarget callers, and ConfigLoader.
- Logs read under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-12-logs/: r01a-disable-check.log, r01b-storage-fmt-compile.log, r01c-f1-grep.log, r01c-pipeline-dry-run.log, r01c-deploy-dry-run.log, r01c-check.log (gate and test lines), r01c-disable-check-f4.log, r01c-disable-check-tf.log, and the r01c-fixtures/ listing.
- Not read: labels/, scratch/stores-live/, scratch/er/adjudication-live/. One Grep with glob `*.tf*` was blocked by guard-paths (it could reach state files); I narrowed it to `*.tf` and `*.tfvars`.
- Two corrections to the work order:
  - The EMR instance role is in infra/terraform/modules/emr/main.tf, not modules/iam/main.tf (iam/main.tf:4 says so).
  - There is no EMR bootstrap action. The "bootstrap" is the BOOT string passed to each step through command-runner.jar (run-bounded-pipeline.sh:195-208).

CODE REVIEW 1 FINDINGS: STATUS

F1 (MAJOR, EMR instance role): RESOLVED.
- infra/terraform/modules/emr/main.tf:44-54 and :142-177. It grants:
  - GetObject on raw/*, curated/*, artifacts/*;
  - Put, Delete, AbortMultipartUpload and ListMultipartUploadParts on curated/* and logs/emr/*;
  - ListBucket only under StringLike s3:prefix raw/*, curated/*, artifacts/*, logs/emr/*;
  - GetBucketLocation and ListBucketMultipartUploads with no condition.
- No grant remains on graph/, semantic-context/, runs/ or reports/. Evidence: r01c-f1-grep.log:50-51, which shows exit 1 outside comment lines.
- README.md:229 matches the code.
- Trace of every S3 path the instance role touches:
  - Inputs under raw/<ds>/snapshot=/run=/ (run-bounded-pipeline.sh:167-171). Read: GetObject raw/*.
  - Jars and step.sh under artifacts/jobs/<commit>/ (:179-182, :195-196). Read: GetObject artifacts/*.
  - step.sh lists curated/hadoop/snapshot=<d>/ and checks .../run=<r>/_manifest.json (step.sh:43-50). ListBucket prefix matches curated/*.
  - The Spark input is under curated/hadoop (step.sh:55).
  - Outputs go to curated/hadoop and curated/spark (run-bounded-pipeline.sh:200).
  - The EMR log push goes to logs/emr/ (emr/main.tf:254).
- list_runs, the _manifest.json copies, the step-log copy and the security-group check run on the laptop with the human's profile, not the instance role.
- StringLike "p/*" matches "p/" and anything under it. It does not match the bare "p", an empty prefix or an absent prefix. No repository code path lists with those forms. See R1 for the connector-level caveat.

F2 (dataset __pycache__): RESOLVED.
- datasets/scripts/make_fraction.py:36 sets sys.dont_write_bytecode before exec_module (:37-39).
- Glob for **/__pycache__/** under datasets/ finds nothing.
- r01a-disable-check.log:38-41 shows that without the fix the .pyc appears, and :75-76 shows that with the fix nothing appears.
- validate.sh runs dblp_extract.py as __main__ (no cache) and parses with ast only (validate.sh:23-25).

F3 (force-new-deployment retried): RESOLVED.
- scripts/deploy.sh:149-151 uses aws_once, which sets AWS_MAX_ATTEMPTS=1 (scripts/lib/common.sh:96-99).
- The step comes after dry_run_exit (:97-103), so a dry-run only lists it (r01c-deploy-dry-run.log:28).
- The only remaining mutating aws_retry is rollback.sh:118 (update-service --task-definition, the same desired state).

F4 (check.sh misses dataset validation): RESOLVED.
- check.sh:54 adds datasets/scripts/*.sh to bash -n. :69 adds datasets_ok, run through bash. :84 adds the gate.
- Exit codes propagate: gate runs `if "$@"` (:42) and validate.sh exits 0 or 1 (:193-195).
- The working directory does not matter: validate.sh and lib.sh derive their paths from BASH_SOURCE (lib.sh:14-18).
- The config and samples the gate needs are in the tree (datasets/config/subset.v1.json, datasets/samples/SHA256SUMS).
- Evidence: r01c-check.log:257-264 and :269-364 show OK. r01c-disable-check-f4.log:294-305 shows FAIL and exit 1, and :576 shows the pre-fix copy exiting 0.
- The prerequisites are not documented: see N2.

F5 (Haiku cap unbounded): RESOLVED by code.
- envs/course/variables.tf:406-409 validates 1 to 8.
- terraform validate does not evaluate root-variable validations (r01c-disable-check-tf.log:7-9), so the rule is first evaluated at the human's plan.

F6 (AZ data source): RESOLVED by code.
- The data source is gone. modules/foundation/main.tf:28-31 adds the variable, and :50-53 outputs it.
- The root variable is variables.tf:61-70: exactly 2 entries, distinct, each matching ^<region>[a-z]$.
- It is passed at envs/course/main.tf:26 and set at course.tfvars:12.
- Its only consumers are modules/network/main.tf:79 and :92 (public and private subnets).
- The Neptune subnet group takes private_subnet_ids (modules/neptune/main.tf:119). The EMR subnet is public_subnet_ids[0] (envs/course/outputs.tf:59).
- No aws_availability_zones reference remains.
- The validation checks the name pattern, not existence. "us-east-2z" passes validation and fails at plan or apply, so it fails closed.

F7a (trust claim): RESOLVED.
- infra/terraform/README.md:239-242 now names the EMR instance role as the one role without a condition.
- The trust policy is unchanged (emr/main.tf:120-129), as ruling 19 requires.

F7b (CLI timeouts): RESOLVED.
- step.sh:19-20 defines aws_t and fetch. It is used at :37 and :52 (fetch) and :45 and :50 (s3 ls). The classpath mode makes no aws call.
- The BOOT string at run-bounded-pipeline.sh:195 carries both options. They are literal tokens, so the quoting is unaffected. jq passes BOOT as one argument (:196, :202-208).
- --cli-read-timeout is a per-socket-read timeout, not a total transfer limit, so 120 s is sane for the large jar copies.
- scripts/README.md:21-22 matches.

F7c (EMR security-group claim): RESOLVED.
- The claim is now conditional at emr/main.tf:186-191 and README.md:129-133.
- The new step is run-bounded-pipeline.sh:253-273. It is read-only (describe only) and uses the same group IDs from the `emr` output that create-cluster used (:218).
- It detects both IPv4 0.0.0.0/0 (IpRanges) and IPv6 ::/0 (Ipv6Ranges) (:256-258), and FromPort 0 stays 0.
- It warns per rule and records emrSecurityGroups {checked, openIngress, file} (:283, :295). A failed describe call warns UNVERIFIED with checked=false and never fails the run.
- It runs after dry_run_exit (:121), so a dry-run only lists it (r01c-pipeline-dry-run.log:15).
- It runs before the failure exit at :299, so failed clusters are also checked.

F8 (stale text):
- scripts/README.md:207, now :210-216: RESOLVED.
- scripts/README.md:313-317, now the "Dependencies" section :311-320: RESOLVED.
- infra/terraform/README.md:36-37 (modules/secrets): RESOLVED (removed).
- infra/terraform/README.md:218-221, now :221-223: RESOLVED. The code block nine lines above (:209-215) still omits --stage-from-bucket: see N1.
- LiveAws.scala:31-32, now :31-35: RESOLVED. I checked that "EmbedCli and the rag CLIs still need it" is accurate: EmbedCli.scala:41, rag SmokeCli.scala:53 and AskCli.scala:154 call Cli.liveTarget without profileOptional.

F9 (gitignored log evidence): RESOLVED for operations/.
- Writers: deploy.sh:121 ($REL.apply.txt), cleanup.sh:128 (apply.txt), load-stores.sh:125-130 (<step>.txt) and :163-166 (neptune-<label>-<id>.txt).
- Docs: deploy.sh:30, load-stores.sh:37-38, scripts/README.md:51, :141, :247-248, :307.
- No reader uses the old names (Grep over scripts/, infra/, modules/, .claude/commands/).
- archive-evidence.sh copies operations/ whole (:97).
- The EMR step logs fetched into <rec>/logs/ are .gz files, not matched by *.log (.gitignore:28).
- Still open outside ruling 19's operations/ scope: experiments/results/.../stdout.log and stderr.log (run-experiment.sh:100) remain gitignored. Phase 14+ item.

RULING 16 ITEMS
- envs/course/variables.tf:397, now :413: RESOLVED. The claim is verified: ConfigLoader.scala:303 maps the variable and :751 caps it at 8.
- scripts/load-stores.sh:43-44, now :44: RESOLVED.
- scripts/run-bounded-pipeline.sh:18-20, now :18-22, and :98, now :101: RESOLVED.
- scripts/README.md:304: RESOLVED (section replaced, :311-320).
- --dry-run prints resolved inputs: RESOLVED.
  - run-bounded-pipeline.sh:104-113 prints them.
  - r01c-pipeline-dry-run.log:2-7 shows the five inputs, and the independent shasum and wc at :19-24 match.
  - The input run ID is unchanged. $( ) strips the one trailing newline and printf '%s\n' at :164 restores it, so input-hashes.txt has the same bytes as the old per-line printf.
- modules/storage/STORAGE.md:487: RESOLVED. It matches Composition.scala:422-423.

RULING 18 (haiku_destination_regions): RESOLVED.
- course.tfvars:51 and the variables.tf:379 default are ["us-east-1","us-east-2","us-west-2"].
- README.md:103 and :304-306 record the human's 2026-11-14 checks.

PART C ASSUMPTIONS (repair-01.md:315-317)
1. ListBucket conditioned on the union of the read and write prefixes, with GetBucketLocation and ListBucketMultipartUploads unconditioned: SOUND.
   - logs/emr/* in the list set covers any listing by the log pusher.
   - To my knowledge s3:prefix is a condition key only for ListBucket and ListBucketVersions. A StringLike on an absent key would deny ListBucketMultipartUploads, so leaving it unconditioned is right.
   - Cost: in-progress multipart key names across the bucket are visible to the role. That is low sensitivity.
2. AZ names, not AZ IDs: SOUND. The repair work order specified names. Ruling 18's orderability check was done by name in this account. Names are stable per account, and removing the data source removes the drift F6 described.
3. The security-group check warns and never fails: SOUND. It matches "warn loudly and record". The caveat is in R3: the warning goes to stderr in a long run, so the operator's report must surface run.json emrSecurityGroups.openIngress.

FINDINGS (new; ranked; all MINOR)

N1. MINOR. infra/terraform/README.md:209-215. The loader command block omits --stage-from-bucket.
- The block shows `-main scholarchain.storage.cli.LoadNeptuneCli --live --dir /tmp/stores/s3/graph/neptune/... --out /tmp/stores`.
- This contradicts the repaired paragraph at :221-222 and the real call at scripts/load-stores.sh:171.
- Failure input: a human runs the loader task by hand from the README.
- Impact: in a fresh Fargate container there is no local copy. Without staging, LoadNeptuneCli validates the missing local copy and refuses (LoadNeptuneCli.scala:10-17). No load starts, so it fails closed.
- Owner: infra-engineer.
- Disposition: residual risk, or a Phase 14 doc fix (load-stores.sh is the authoritative path).

N2. MINOR. scripts/check.sh:24 and :35. The python3 prerequisite of the new dataset gate is undocumented.
- check.sh:24 ("Needs: jq, bash, terraform 1.16.6, sbt, a JDK") and :35 (`need jq terraform sbt`) do not list python3.
- The dataset-validation gate now needs it (datasets/scripts/validate.sh:15 `require_tools jq python3`).
- scripts/README.md:84-85 lists no tools.
- Failure input: a clean checkout on a machine without python3.
- Impact: the gate fails with "required tool not found: python3", so it fails closed. The spec Phase 13 rule "work from a clean checkout after documented prerequisites are installed" is weakened, because the prerequisite is not documented.
- Owner: infra-engineer.
- Disposition: residual risk, or a Phase 14+ doc fix.

N3. MINOR (Law 4, evidence). repair-01.md:221 has no supporting evidence.
- The claim: "The jq filter was checked against fixtures: the open fixture gave both port-22 entries (IPv4 and IPv6), the closed fixture gave []".
- No log or security-group fixture exists in the cited evidence: r01c-fixtures/ holds only the package and plan fixtures, and a Grep for IpPermissions, ::/0 and OPEN_INGRESS over scratch/phase-12-logs/ finds nothing.
- Impact: low. My own reading of run-bounded-pipeline.sh:256-258 finds the filter correct for IpRanges 0.0.0.0/0 and Ipv6Ranges ::/0. It handles absent FromPort and ToPort (protocol -1) and keeps port 0. But the claim as stated is unevidenced.
- Owner: infra-engineer.
- Disposition: residual risk. The first cloud window 1 run produces real evidence in security-groups.json and run.json.

No CRITICAL or MAJOR finding. I found no secret in any repair change, no constitutional change, and no weakening of the floor.

FOCUS CHECKS (no defect beyond N1 to N3)
- check.sh dataset gate:
  - bash -n covers all 8 datasets/scripts/*.sh (r01c-check.log:257-264).
  - set -e is suppressed inside `if "$@"`, and the gate function returns the child's status.
  - validate.sh deliberately runs `set +e` after sourcing lib.sh's `set -Eeuo pipefail` and aggregates failures.
  - No working-directory dependency.
- deploy.sh aws_once:
  - Exactly one attempt; dry-run path unaffected.
  - A failure exits under set -e with the CLI's status (for example 254), not a documented code. Rerunning --resume is safe. This pattern is shared by every aws_once call and predates the repair (R4).
- run-bounded-pipeline.sh moved hashing:
  - The inputs are hashed once (INPUT_LINES) and reused at :164.
  - The new step comes after the input existence checks (:100-102) and needs no AWS.

OWNERSHIP CHECK (repair-01 changes)
| Path | Owner | Author matches |
|---|---|---|
| datasets/scripts/make_fraction.py | dataset-engineer | yes (Part A) |
| datasets/scripts/__pycache__/ (deleted) | dataset-engineer tree | yes. The human deleted the original (ruling 19); Part A created one in its disable check and deleted it |
| modules/storage/STORAGE.md, modules/storage/src/main/scala/scholarchain/storage/live/LiveAws.scala | storage-engineer | yes (Part B) |
| infra/terraform/modules/emr/main.tf, modules/foundation/main.tf, envs/course/{main.tf,variables.tf,course.tfvars}, infra/terraform/README.md | infra-engineer | yes (Part C) |
| scripts/{check,deploy,cleanup,load-stores,run-bounded-pipeline}.sh, scripts/emr/step.sh, scripts/README.md | infra-engineer | yes (Part C) |
| docs/agent-runs/phase-12/{review-report.md,repair-01.md} | orchestrator report assembly | allowed (no commit.txt) |
| docs/agent-runs/phase-12/evidence/code-review-1/phase-12-cr1-changeset.txt | human architect (docs/agent-runs/**) | author not evidenced in my inputs. It is not one of the four report files the orchestrator may write; the human should confirm they placed it |
No repair change falls outside its owner's rows.

FLOOR AND FENCE CHECK
- No change to CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, hooks, permission rules, markTestRun, the version ledger or build.sbt.
- check.sh still runs validate-agent-files.sh, and it passed 231/231 (r01c-disable-check-f4.log:238).
- The repair narrows IAM (the EMR instance role, and the Haiku foundation-model Regions without us-west-1). It widens nothing.
- Part C disclosed one guard-dangerous false positive (hooks path text inside a copy command). It did not rephrase the Bash command. It wrote the scratchpad copy with the Write tool, into a temporary directory outside the repository, which guard-paths allows. That matches docs/agents.md's guidance to write such content with the file tools, so I do not treat it as a hook workaround.
- The PATH shims in the F4 disable check were test doubles in the agent's scratchpad, not wrappers around a hook-matched command (ruling 10).
- cleanup.sh was not run in any mode.

EVIDENCE GAPS
- The F5 and F6 validations are not exercised (terraform validate skips root-variable validations). The author disclosed this. They are first evaluated at the human's plan.
- The narrowed EMR permissions are not exercised on a cluster (R1).
- The jq fixture claim: N3.
- check.sh passed on the working tree, not on a clean checkout, because everything is uncommitted. Replay after the ruling 21 commit.
- The plan counts (43/0/0; 91 or 89; 134) are code-derived. No plan was run (ruling 5; ruling 21 orders the plan after commit).
- shellcheck: SKIPPED (not installed).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.

TEST RESULTS (as supplied in repair-01.md, quoted, and checked against the logs):
- Part C: "scripts/check.sh ... Exit 0, '==> OK check: every gate passed'". r01c-check.log:1826.
- "sbt: 191+111+116+115+49+141+46+70 = 839 succeeded, 0 failed, 10 pending ... markTestRun ... wrote .claude/.last-test-run". r01c-check.log:1319-1823.
- "Dataset validation ... '[DONE] validate: all checks passed'". r01c-check.log:363.
- Part A: "make_fraction.py --self-test ... exit 0" with the fix disabled and enabled. r01a-disable-check.log:37 and :74.
- Part B: "storage/scalafmtCheck and storage/compile passed". r01b-storage-fmt-compile.log:8 and :11.

VALIDATION RESULTS (as supplied, and checked where logged):
- terraform fmt -check exit 0, init reused aws 6.28.0, validate "Success! The configuration is valid." (r01c-check.log:365-370).
- bash -n OK on all scripts, including datasets/scripts.
- run-bounded-pipeline.sh --fraction 25 --dry-run: exit 0 (r01c-pipeline-dry-run.log:18).
- deploy.sh --dry-run: exit 0 for the correct checksum, exit 4 for the wrong one, exit 0 for --resume (r01c-deploy-dry-run.log).

SPEC GATE ITEMS (ruling 21: the plan and the signed checksum come after the commit, by the human)
Phase 12:
- No unexplained stateful destroy or replacement: MET by code. F6 removed the AZ-drift replacement path, and no managed resource was added or removed. The plan evidence is pending under ruling 21, and the human reads every subnet line.
- IAM scoped to actual resources and actions: MET by code (F1 resolved, with the R1 caveat). Ruling 11 accepts the unconditioned ListBucket on query-api and the worker.
- Buckets and databases not public: MET. Repair-01 did not change this.
- Default capacity bounded: MET by code (F5 resolved). The validation runs first at plan.
- Secrets absent from source and plan output: MET for source; no repair change adds a secret. Plan output is pending. TF_VAR_alarm_email (an address, not a secret) appears in the plan, as documented.
- Human reviews and signs the plan before any apply: PENDING by design (ruling 21). It is enforced: deploy.sh applies only the plan whose sha256 equals --approved-plan-sha256 and plan-meta.json (deploy.sh:84-86), as the dry-run evidence shows.
- Code reviewer approves the infrastructure diff: MET by this APPROVE.
Phase 13:
- A clean checkout runs all non-cloud validation through the documented script: MET on the working tree. All 8 gates passed, including dataset validation (r01c-check.log). Replay from a clean checkout is pending after the commit. N2 (python3 not documented) weakens the "documented prerequisites" part.
- Terraform planning reproducible, plan checksum archived: MET by code (plan.sh writes the sha256; archive-evidence.sh:88-94 copies plan.sha256, plan-meta.json and summary.md). Not yet exercised.
- Deployment cannot proceed without the documented human approval: MET by code and dry-run (exit 4 on a mismatch). The /deploy command and the permission prompt carry the human step.
- Smoke tests exercise a real bounded collaboration query: MET by code. Not exercised until window 2.
- The same scripts are used in the README reproduction instructions and final demonstration: NOT MET yet. The root README.md references none of scripts/check, plan, deploy, package, run-bounded-pipeline, smoke-test, archive-evidence, cleanup, load-stores or run-experiment (Grep count 0). This is a documentation-engineer item after the evidence exists (lifecycle stage 20), not a repair-01 defect. Phase 14+ item.

ASSUMPTIONS
- To my knowledge, s3:prefix is not a condition key for s3:ListBucketMultipartUploads. If it were, assumption 1 would still be safe: leaving the action unconditioned is the broader option, not the failing one.
- The sbt-native-packager -main handling and the ECS trust conditions are as assumed in code review 1. Repair-01 did not change them.

RISKS
- R1 (most important; unverified; not introduced by repair-01): first Hadoop step and the S3 parent probe.
  - The instance role has GetObject only on "curated/*", which does not cover the bare object key "curated".
  - JobRun.scala:44 calls mkdirs on <out>/snapshot=<d>, and Hadoop output commit setup also calls mkdirs.
  - If s3a:// on emr-spark-8.0.0 is served by Apache S3A, its mkdirs walks up the parents. On a bucket whose curated/ is still empty (the first Hadoop step only), it can HEAD the key "curated" and get 403 AccessDenied, which S3A does not treat as not-found.
  - The narrower repair adds a related case: any ListBucket with an empty or absent prefix (a HeadBucket or bucket-existence probe) is now denied.
  - Both fail closed: the step fails, the cluster terminates, and about one cluster-hour is lost.
  - The GetObject part was identical before repair-01. Code review 1 did not raise it.
  - Whether it happens depends on the connector and its probe settings, which I cannot verify from the repository.
  - Human choice before cloud window 1: accept it and watch the first run's step logs, or route a reviewed widening to infra-engineer (GetObject on the exact keys `<bucket>/curated` and `<bucket>/logs/emr`, which grants no data).
- R2: the security-group check runs only after the cluster terminates, and not at all on a wait timeout (exit 6, :234-235). Any open ingress is therefore found only after the exposure window.
- R3: open ingress is reported by WARN on stderr and in run.json, without failing the run. The deploy-engineer's /run-pipeline report must quote emrSecurityGroups.openIngress.
- R4: a failure of an aws_once call (now including force-new-deployment) exits with the CLI's status, not a documented exit code, and can leave the three services partly redeployed. Rerunning `deploy.sh --resume` is safe.
- R5: experiments/results stdout.log and stderr.log are still gitignored (outside F9's scope).
- R6: the F5 and F6 validations run first at the human's plan. A typo in course.tfvars shows up there, not in check.sh.

MANUAL ACTION REQUIRED (human)
- Confirm the origin of docs/agent-runs/phase-12/evidence/ (human-owned path, see the ownership check) before committing.
- At the ruling 21 plan, check:
  - 43/0/0;
  - the EMR instance policy JSON matches emr/main.tf:142-177;
  - the subnets show us-east-2a and us-east-2b with no replacement;
  - the Haiku foundation-model ARNs cover exactly us-east-1, us-east-2 and us-west-2.
- Decide R1 before cloud window 1: accept it, or route a widening.

OPEN ITEMS AND DISPOSITION (ruling 20: no second repair round)
| Item | Severity | Owner | Recommended disposition |
|---|---|---|---|
| N1 README loader block lacks --stage-from-bucket | MINOR | infra-engineer | residual risk (load-stores.sh is authoritative); doc fix in Phase 14 |
| N2 python3 prerequisite undocumented | MINOR | infra-engineer | residual risk; Phase 14+ doc fix |
| N3 jq fixture claim unevidenced | MINOR | infra-engineer | residual risk; superseded by the first window 1 run's security-groups.json |
| R1 S3 parent-key probe on first Hadoop step | risk (unverified) | infra-engineer | human decision before cloud window 1 |
| F9 remainder: experiments/results *.log ignored | MINOR (outside scope) | infra-engineer or build-engineer | Phase 14+ item, before the first experiment |
| Phase 13 gate: README uses the scripts | gate item | documentation-engineer | Phase 14+ (after evidence exists) |

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate on APPROVE (ruling 21): human commit with phase_commit 12, then scripts/plan.sh --runtime-enabled false on that commit, then the signed plan sha256 in human-gate.md. N1 to N3 recorded as residual risks or Phase 14+ items; R1 decided before cloud window 1.
````

## Code review 3: code-reviewer

````text
Code review 3 returns REQUEST_CHANGES because of one MAJOR finding (N4). N1, N2 and the F9 remainder are fixed, and the R1 grant is exactly what was ordered. But the R1 grant is very likely not to work, by the repository's own documented S3 rule. Under ruling 22 this goes to human escalation; the decision should come before cloud window 1.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 (spec Phases 12 and 13, merged by D-10), session 5, code review 3. HEAD 49d71f48b93d94883e4bc2cd5e4bec44caf5199a; all Phase 12 changes uncommitted. Scope per ruling 22: only the repair-02 changes (R1, N1, N2, F9 remainder), plus the three grant confirmations, ruling 8/10 compliance, and ownership.
VERDICT: REQUEST_CHANGES (under ruling 22 this exits to human escalation, not to another repair round)

Summary:
- N1, N2 and the F9 remainder are RESOLVED in the working tree.
- R1 is coded exactly as repair-02 ordered. The three confirmations hold: the keys are exact, no wildcard, no data is readable, and no other permission changed.
- The grant very likely does not do its job. The new comment says the grant "only turns that 403 into 404" (emr/main.tf:57-58). This repository's own design says a missing key answers 404 only to a caller holding s3:ListBucket (iam/main.tf:181-183, "Unconditioned on purpose"; SERVICES.md:70).
- The EMR instance role holds ListBucket only under StringLike s3:prefix raw/*, curated/*, artifacts/*, logs/emr/* (emr/main.tf:156-166). A HEAD on the bare key "curated" carries no s3:prefix, and "curated/*" does not match "curated" either.
- So on an empty curated/ the HEAD very likely still answers 403, and R1 is not mitigated. That is new finding N4 (MAJOR). It is not a security defect: it fails closed, and the new grant is harmless.
- Correction to my own earlier review: code review 2 proposed this exact-key GetObject grant as the remedy (review-report.md:547). That proposal had the same gap.
- One MINOR process finding (N5).

SCOPE REVIEWED
- Inputs read in full:
  - docs/agent-runs/phase-12/prompt.md (rulings 1-22, the repair-02 work order, code review 3);
  - docs/agent-runs/phase-12/review-report.md:288-576 (code review 2);
  - docs/agent-runs/phase-12/repair-02.md;
  - the cr2-to-cr3 diff (/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/4078c56e-a696-4232-9c44-5949bde6eb1d/scratchpad/phase-12-cr2-to-cr3-repair02.diff, all 152 lines).
- The diff touches only five repository files: infra/terraform/modules/emr/main.tf, infra/terraform/README.md, scripts/check.sh, scripts/run-experiment.sh and scripts/README.md. The other hunks are the HEAD line and the repair-02.md status line.
- Working-tree files read:
  - in full: infra/terraform/modules/emr/main.tf, scripts/check.sh, scripts/run-experiment.sh;
  - in part: infra/terraform/README.md:195-249, scripts/README.md:40-94, scripts/load-stores.sh:160-179, scripts/lib/common.sh:40-43, infra/terraform/modules/data/main.tf:35-119.
- Searched with Grep:
  - readers of the old stdout.log and stderr.log names, in scripts/, infra/terraform (*.tf, *.md, *.tfvars), .claude/commands and .claude/agents;
  - every infra *.tf resource ARN and every "curated" and "logs/emr" reference;
  - aws_s3_object in infra;
  - bare-key writers of curated and logs/emr in scripts/ and modules/**/*.scala;
  - mkdirs in modules/*/src/main;
  - ListBucket and 404 rationale in iam/main.tf and SERVICES.md;
  - the .gitignore *.log rule;
  - the infra-engineer tools line.
- Logs read: scratch/phase-12-logs/r02a-r1-grep.log, r02a-terraform.log, r02a-bash-n-help.log and r02a-disable-check-n2.log in full, and r02a-check.log through a Grep of its gate, test and marker lines (plus a [PASS] count).
- Not read: labels/, scratch/stores-live/, scratch/er/adjudication-live/.
- One repository-wide Grep was blocked by guard-paths [secret-search] because its exclusion glob named *.tfstate*. I narrowed it to the specific directories above.

ITEM STATUS
R1 (EMR parent-key probe): the work order is RESOLVED as written; the risk itself stays OPEN (see N4).
- infra/terraform/modules/emr/main.tf:60: parent_keys = ["curated", "logs/emr"].
- :174-178: statement "ProbeWriteRootParents", s3:GetObject on "${local.b}/${k}", where local.b = var.data_bucket_arn (:43).
- The required comment is at :54-59. The README IAM summary is at infra/terraform/README.md:229.
- The final statements are shown in r02a-r1-grep.log:13-28, which matches the working tree at :151-188.
- No ListBucket was added.

N1 (README loader block): RESOLVED.
- infra/terraform/README.md:211 reads "-main scholarchain.storage.cli.LoadNeptuneCli --live --stage-from-bucket", with --dir and --out at :212.
- This matches scripts/load-stores.sh:171. The --status line (:214) matches load-stores.sh:177.

N2 (python3 prerequisite): RESOLVED.
- scripts/check.sh:24 (help text) and :35 (need jq python3 terraform sbt).
- need() exits 2 on a missing tool (scripts/lib/common.sh:42), as scripts/README.md:85-86 now states.
- Disable check: r02a-disable-check-n2.log:4-5 shows the fixed script exiting 2 before any gate. :16-26 shows the pre-fix copy failing late at dataset-validation with exit 1. :28-29 shows the restore sha256 matching.

F9 remainder (experiments/results *.log): RESOLVED.
- scripts/run-experiment.sh:100 (redirects), :118 (tail), :126 (run-record fields) and :31-32 (help text).
- scripts/README.md:269 (evidence) and :50-52 (convention).
- No reader of stdout.log or stderr.log remains in scripts/, infra/terraform, .claude/commands or .claude/agents.
- archive-evidence.sh:98 copies experiments/results whole, so it needs no change.
- .gitignore:28 is *.log, so .txt files are tracked.

THE THREE CONFIRMATIONS
1. Exact keys, no wildcard: CONFIRMED.
   - Both values at emr/main.tf:60 are literal keys with no * or ?.
   - The resource template at :177 adds no wildcard, and the ARNs come out as <bucket-arn>/curated and <bucket-arn>/logs/emr.
   - Terraform resolves ${...} before the policy exists, so no IAM policy variable appears.
2. No read access to any data object: CONFIRMED.
   - No Terraform object creates either key: there is no aws_s3_object in infra/, and local.prefixes at data/main.tf:45-55 is used only by lifecycle filters (:149, :167) and an output (:191).
   - No script writes a bare key: scripts/ has no /curated or /logs/emr that ends without a "/".
   - The EMR LogUri ends with "/" (emr/main.tf:267).
   - Every Scala use of "curated" is a path segment followed by more segments (ObjectStore.scala:15-39, StoreConversionJob.scala:125-126, LoadNeptuneCli.scala:211).
   - The role's own writes (curated/*, logs/emr/*) cannot match the bare keys.
   - Residual: an admin principal could PUT such a key by hand. The role already reads all of curated/*, so the exposure is negligible.
3. No other permission changed: CONFIRMED.
   - The only .tf hunks in the cr2-to-cr3 diff are the local at emr/main.tf:54-60 and the new statement at :174-178.
   - No trust policy, other statement, role, module, variable, output or lock-file line changed.
   - parent_keys is used only at :60 and :177.

FINDINGS (new; ranked)

N4. MAJOR. R1's stated mechanism is contradicted by the repository's own S3 rule, so the mitigation is very likely ineffective.
- Where: infra/terraform/modules/emr/main.tf:56-58 (comment), :174-178 (grant) and :156-166 (the conditioned ListBucket). The claim also appears at infra/terraform/README.md:229 and in repair-02.md lines 20, 137 and 89.
- The claim: the exact-key GetObject "only turns that 403 into 404".
- The evidence against it:
  - infra/terraform/modules/iam/main.tf:181-183 says S3 answers a GET of a missing key with 404 "only to a caller that holds s3:ListBucket on the bucket; otherwise 403". That is why query-api's ListBucket is unconditioned. SERVICES.md:70 says the same.
  - The EMR role's only ListBucket is conditioned on s3:prefix StringLike raw/*, curated/*, artifacts/*, logs/emr/*.
  - A HEAD of the key "curated" is a GetObject request and carries no s3:prefix. Even if S3 used the key itself as the prefix, "curated/*" does not match "curated".
  - Only if S3 skipped policy conditions in its implicit ListBucket check would the grant produce a 404. Nothing in the repository supports that, and the author gives no evidence for it.
- Failure input: the first window 1 Hadoop step on a bucket whose curated/ is empty.
  - The S3A mkdirs parent walk LISTs "curated/", which is allowed and empty, then HEADs "curated".
  - With GetObject now allowed but the key missing and no ListBucket that applies, S3 answers 403. S3A raises AccessDeniedException.
  - The same applies to "logs/emr" if anything walks it.
- Impact:
  - The step fails and the cluster terminates. That costs about one cluster-hour, plus the re-plan, re-apply and rerun that ruling 22 ordered repair-02 to avoid.
  - The comment and README tell the plan reviewer that R1 is fixed.
  - No security impact. The new grant is harmless and could stay.
- Owner: infra-engineer for the comment, README and report claims. The remedy is a human decision: ruling 22 forbids "ListBucket ... without a prefix condition", which rules out the IAM-only change that would plausibly work. This is outside the reviewer's power to fix (Law 1).

N5. MINOR (process, ruling 8). The infra-engineer inspected files with Bash grep instead of the Grep tool.
- Where: repair-02.md:28 ("Grep is not available in this session, so I used Bash grep") and :67 (a stdout.log search over scripts/ and infra/).
- Ruling 8 says "Use the Read and Grep tools, not Bash, to inspect scripts". The R1 item says "show the final policy statements with Grep".
- .claude/agents/infra-engineer.md:4 does list Grep. I cannot verify that it was unavailable in that session.
- Impact: none on the tree. The output in r02a-r1-grep.log matches the working tree. The deviation was disclosed.
- Owner: infra-engineer. The human records it as an accepted deviation, like ruling 10(a).

No CRITICAL finding. No secret in any repair-02 change.

RULING 8/10 COMPLIANCE (from repair-02.md and the r02a logs)
- No git, no aws, and no plan, apply or state command. cleanup.sh was not run. The terraform commands were only the ruling 5 set (r02a-terraform.log).
- Edits were only in infra/** and scripts/**. New outputs went only to scratch/phase-12-logs/r02a-*.log and the session scratchpad.
- check.sh ran once on the final tree; it passes SPARK_LOCAL_IP=127.0.0.1 and -J-Xmx6g (check.sh:21). There was one sbt process, and housekeeping was reported: pgrep exit 1, 10 GiB free.
- The disable check:
  - put the pre-fix check.sh in place temporarily and restored it with a matching sha256 (r02a-disable-check-n2.log:28-29);
  - used a PATH bin without python3 and a no-op sbt shim. These are test doubles, not wrappers around a hook-matched command (ruling 10).
- The guard-dangerous block (`ls -l .claude/.last-test-run` and `rm -f` of a scratchpad copy): the agent dropped the blocked parts and did not rephrase them. I do not count this as a hook workaround.
- The sed -i edits on owned scripts are not prohibited.
- Deviation: N5.

OWNERSHIP CHECK
| Path | Owner | Author matches |
|---|---|---|
| infra/terraform/modules/emr/main.tf | infra-engineer | yes |
| infra/terraform/README.md | infra-engineer | yes |
| scripts/check.sh | infra-engineer | yes |
| scripts/run-experiment.sh | infra-engineer | yes |
| scripts/README.md | infra-engineer | yes |
| docs/agent-runs/phase-12/repair-02.md | orchestrator report assembly | allowed (repair-NN.md; the phase-12 folder has no commit.txt, checked by Glob) |
| scratch/phase-12-logs/r02a-*.log | agent log location (ruling 8) | allowed |
No repair-02 change is outside its owner's rows.

FLOOR AND FENCE CHECK
- No change to CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, hooks, permission rules, markTestRun, build.sbt or the version ledger.
- check.sh still runs validate-agent-files.sh, and the constitution gate passed (r02a-check.log:236).
- The IAM widening is exactly two exact-key GetObject resources on the EMR instance role, within ruling 22. No ListBucket, trust or action widening.
- check.sh now fails closed earlier (exit 2 on a missing python3); nothing in the floor is weakened.

EVIDENCE GAPS
- The R1 claim that the grant turns 403 into 404 has no evidence and conflicts with iam/main.tf:181-183 (N4). It cannot be tested locally; the author said so.
- The S3A probe order on emr-spark-8.0.0 is unverified (repair-02.md ASSUMPTIONS 2).
- The F9 rename is not exercised, because it needs AWS and a committed manifest. That was disclosed, and the code reads correctly.
- The plan counts (43/0/0; 91 or 89; 134 from empty state) come from the code; no plan was run (ruling 5).
- check.sh passed on the working tree, not on a clean checkout.
- shellcheck was SKIPPED (not installed).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.

TEST RESULTS (as supplied in repair-02.md, quoted, and checked against r02a-check.log):
- "scripts/check.sh on the final tree ... exit 0, '==> OK check: every gate passed'". r02a-check.log:1825.
- "sbt: 191+111+116+115+49+141+46+70 = 839 succeeded, 0 failed, 10 pending". r02a-check.log:1378, 1383, 1388, 1500, 1652, 1793, 1798, 1820.
- "markTestRun: tests passed, wrote .../.claude/.last-test-run". r02a-check.log:1822.
- "dataset-validation (15 [PASS] markers, 0 [FAIL] markers, '[DONE] validate: all checks passed')". The log has 15 [PASS] markers, [DONE] at :362, and no [FAIL] line in the gate output.

VALIDATION RESULTS (as supplied, and checked in the logs):
- terraform fmt -check exit 0; init exit 0 reusing aws v6.28.0; validate "Success! The configuration is valid." (r02a-terraform.log:7-29).
- bash -n ok for check.sh and run-experiment.sh. run-experiment.sh --help and check.sh --help both exit 0 and show the new text (r02a-bash-n-help.log).

ASSUMPTIONS
- S3 decides 404 versus 403 for a missing key from s3:ListBucket, as this repository itself documents (iam/main.tf:181-183, SERVICES.md:70).
- S3 does not evaluate an s3:prefix condition as satisfied for a GetObject or HeadObject request. I cannot test this without AWS. Even if it did, the configured values ("curated/*", "logs/emr/*") do not match the bare keys.
- The S3A parent walk is a directory LIST, then a file HEAD, as in repair-02.md ASSUMPTIONS 2. Also unverified on emr-spark-8.0.0.

RISKS
- R1 stays effectively OPEN despite repair-02 (N4). It fails closed, at a cost of about one cluster-hour, and needs a re-plan and re-apply if an IAM change is then required. Decision options for the human (not reviewer fixes):
  - accept R1 as a residual risk and watch the first Hadoop step's logs;
  - approve a ListBucket change that ruling 22 excludes;
  - have the laptop step put an object under curated/ before the first step, so the directory probe stops the walk before it HEADs "curated". Whether that works depends on the S3A probe order.
- Files the experiment command writes under raw/ named *.log are still gitignored. That is the experiment harness's concern (experiment-engineer), not run-experiment.sh.

MANUAL ACTION REQUIRED (human)
- Decide N4 and R1 before cloud window 1, before the ruling 21 commit and plan if an IAM change is chosen. Whatever the disposition, correct or annotate the claims at emr/main.tf:56-58, README.md:229 and repair-02.md.
- Record N5 as an accepted process deviation, or not.
- At the ruling 21 plan: confirm 43/0/0 and that ProbeWriteRootParents lists exactly the two exact-key ARNs.

BLOCKED-ON OWNER: none (N4's remedy is a human decision under ruling 22; text corrections are infra-engineer)
BLOCKED-ON EVIDENCE: none
NEXT GATE: human escalation (ruling 22). The human decides N4/R1 and N5, then commits with phase_commit 12, runs the plan and signs the sha256 (ruling 21).
````

## Code review 4: code-reviewer

````text
## Code review 4: code-reviewer

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 (spec Phases 12 and 13, merged by D-10), session 6, code review 4. HEAD d3fc7cb5d19fa0a583aa0362a572c055229ff7ed, all Phase 12 changes uncommitted. Scope under ruling 23: only the repair-03 changes, checked against ruling 23 and code review 3 (N4, N5). Reviewed without Ponytail (ruling 9).
VERDICT: APPROVE (no CRITICAL or MAJOR finding; three MINOR findings, which go to the gate as residual risks under ruling 23)
```

**Summary**
- N4 is resolved as ruling 23 orders.
- The EMR instance role's s3:ListBucket now has no condition. Nothing else in that role was widened, except the exact key "logs" that the ruling ordered.
- The ProbeWriteRootParents keys are exact.
- All ten roles in repair-03's table are decided correctly against the code paths. One row (neptune-bulk-loader) rests on a claim about how Neptune reads S3 that has no cited source (M1).
- Every comment and README line that repair-03 changed states the ruling 23 rule correctly.
- Two smaller text issues are M2 and M3.

### SCOPE REVIEWED
- **Inputs read:**
  - prompt.md rulings 1-23, the repair-03 work order and the code review 4 order (the whole file);
  - review-report.md:578-761 (code review 3);
  - repair-03.md in full;
  - the cr3-to-cr4 diff (71 lines, the repair-03 scope).
- **What the diff touches:**
  - infra/terraform/modules/emr/main.tf: locals :52-58, and the ListBucket statement :154-162;
  - infra/terraform/README.md:229;
  - the git status line for repair-03.md.
- **Working-tree files read:**
  - in full: emr/main.tf, iam/main.tf, build/main.tf, scripts/emr/step.sh, S3Live.scala;
  - in part: neptune/main.tf:70-110, README.md:50-249, SERVICES.md:66-87, LoadNeptuneCli.scala:60-292, LiveAws.scala:150-217, Audit.scala:25-34 and :70-155, RuntimeApi.scala:76-98, Services.scala:300-359, package.sh:95-134, common.sh:130-165.
- **Greps:**
  - the identifiers list_prefixes, parent_keys, ListOwnPrefixes, ListDataBucket;
  - 403 and 404 across infra/ and scripts/;
  - every aws_iam_role, s3:ListBucket, s3:GetObject and s3:prefix in *.tf;
  - ListBucket and s3:prefix wording in all infra, scripts, modules and datasets *.md files;
  - S3 calls in run-bounded-pipeline.sh;
  - "logs/" in the hadoop and spark main sources;
  - readiness probes in the services module;
  - environmentFiles in the ecs module;
  - "unexpired only" in the tests;
  - ttl-days in scholarchain.conf.
- **Logs read:** scratch/phase-12-logs/r03a-tf-validate.txt in full; r03a-check.txt through its gate, test and marker lines.
- **Not read:** labels/, scratch/stores-live/, scratch/er/adjudication-live/.

### CONFIRMATIONS (ruling 23 and the code review 4 order)

**1. The EMR instance role's ListBucket is unconditioned, and nothing else was widened: CONFIRMED.**
- emr/main.tf:158-162: sid "ListDataBucket" grants s3:ListBucket on local.b (var.data_bucket_arn, :43), with no condition block.
- The cr3-to-cr4 diff shows exactly these changes in the role:
  - the condition was removed;
  - the sid was renamed from ListOwnPrefixes, and nothing references the old name (Grep);
  - the unused local list_prefixes was removed (Grep: no remaining reference);
  - the comments were rewritten;
  - parent_keys gained "logs".
- These parts are byte-identical to code review 3:
  - BucketLevel :148-152;
  - ReadInputs :164-168 (raw/*, curated/*, artifacts/*);
  - WriteOutputs :176-185 (curated/*, logs/emr/*);
  - the trust policy :125-134;
  - the instance profile and the service role.
- The only other widening is the exact key "logs", which ruling 23 ordered.

**2. The ProbeWriteRootParents keys are exact: CONFIRMED.**
- emr/main.tf:58 reads `parent_keys = ["curated", "logs", "logs/emr"]`. All three are literal keys with no `*` or `?`.
- :173 renders them as `${local.b}/${k}`, which gives `<bucket-arn>/curated`, `<bucket-arn>/logs` and `<bucket-arn>/logs/emr`.
- None of these keys holds data:
  - no Terraform aws_s3_object exists (code review 3);
  - the EMR LogUri ends with "/" (emr/main.tf:263);
  - nothing in hadoop or spark main writes under "logs/" (Grep: no match).

**3. The role table (repair-03.md:47-56): every decision is correct against the code paths.**

| Role | Decision | Correct? | Evidence |
|---|---|---|---|
| emr-instance | changed to unconditioned | yes | emr/main.tf:158-162. Missing-key HEADs under curated/ and raw/ are covered by ReadInputs (:167), and the bare parents by :173. |
| emr-service | no change | yes | The role has no S3 object grant (:96-119). Without GetObject, adding ListBucket could never turn a 403 into a 404, so the 404 dependency cannot arise. Log pushing uses the instance role (LogUri at run-bounded-pipeline.sh:220). That AmazonEMRServicePolicy_v2 reads nothing from the bucket was not re-checked, which the author discloses (see Evidence gaps). |
| codebuild-services-image | no change | yes | The role has GetObject on artifacts/codebuild/source/* and no ListBucket (build/main.tf:112-122). package.sh:109-110 uploads the source key before start-build at :122-123, which overrides the location to that key. A missing source fails the build with either a 403 or a 404. |
| neptune-bulk-loader | no change | correct as far as the repository shows; the stated reason has no source (M1) | ListGraphPrefix is conditioned on s3:prefix graph/* (neptune/main.tf:86-97). The source is `s3://bucket/graph/neptune/snapshot=<d>/run=<r>/` (NeptuneLive.scala:42, LoadNeptuneCli.scala:89-92). "Lists the source prefix and GETs only listed keys" describes Neptune's internal behaviour and is not cited. |
| ecs-exec-services, ecs-exec-loader | no change | yes | Neither role has an S3 grant (iam/main.tf:127-159), and no task definition uses environmentFiles (Grep of ecs/main.tf). |
| task-query-api, task-worker | no change | yes | The role already has an unconditioned ListBucket (iam/main.tf:184-188). The readiness probe key is under logs/rag-runtime/ (Services.scala:57-58, :116; Worker.scala:73), and GetObject covers it (iam/main.tf:194). |
| task-graph-evidence-api | no change | yes | Readiness probes only neptune and dynamodb, with no S3 probe (Services.scala:330-333). The only S3 read is the payloadRef of an EvidenceBundle item (RuntimeApi.scala:87-89). The payload object is written by putOnce before the item that names it (Audit.scala:109-117, :146-150). The TTL is 30 days (scholarchain.conf:203). A missing payload already answers 502 (Services.scala:341-347). A 403 adds only an s3-breaker count. |
| task-neptune-loader | no change | yes | Staging GETs only manifest keys, with no listing (LoadNeptuneCli.scala:190-235). The pre-load HEAD runs only on listed keys, through the `remote(k) &&` short-circuit (:289). ListObjectsV2 of the graph/neptune prefix matches the condition (iam/main.tf:315-319). A 403 is not transient (LiveAws.scala:159-162). The reader maps only NoSuchKey to None (S3Live.scala:94). So a 403 ends in exit 1 with nothing loaded, the same outcome as a 404. load-stores.sh uploads first, so in the normal path every key it GETs exists. |

- No role is missing from the table. The *.tf files define exactly 10 roles: emr ×2, build ×1, neptune ×1, iam 2 execution + 4 task.

**4. Comments and README claims against the ruling 23 S3 rule: CONFIRMED for every changed line.**
- **emr/main.tf:52-54** states that a principal gets 404 only with GetObject on the key and ListBucket on the bucket, and 403 otherwise, and cites iam/main.tf:181-183. The citation resolves to the query-api "Unconditioned on purpose" comment.
- **emr/main.tf:154-157** states:
  - a GetObject or HeadObject request carries no s3:prefix, so a prefix-conditioned ListBucket never applies;
  - the grant exposes key names only, never data;
  - it follows the query-api pattern and ruling 11.
- **README.md:229** states the same rule. It also lists the exact keys curated, logs and logs/emr.
- **"403 into 404":** the claim no longer appears anywhere in infra/ or scripts/ (Grep of 403|404). It remains in docs/agent-runs/phase-12/repair-02.md, which is human-owned (see Manual action).

### FINDINGS (ranked; all MINOR; no CRITICAL, no MAJOR)

**M1. MINOR (Law 4, an uncited claim). The neptune-bulk-loader "no change" decision rests on an unverified description of how Neptune reads S3.**
- **Where:**
  - repair-03.md:52;
  - infra/terraform/modules/neptune/main.tf:86-97 (ListBucket conditioned on s3:prefix graph/*);
  - the README repeats the grant at :142 and :231.
- **Failure input:** in cloud window 2, LoadNeptuneCli starts a load with source `s3://<bucket>/graph/neptune/snapshot=<d>/run=<r>/` (NeptuneLive.scala:42). Suppose Neptune first HEADs or GETs the source URI itself, or any key it has not listed, to tell a file from a folder. That key does not exist. Under the ruling 23 rule, the conditioned ListBucket does not apply to that request, so S3 answers 403 instead of 404.
- **Impact:**
  - The bulk load may fail with an S3 access error.
  - That costs a re-plan and re-apply inside the window.
  - It fails closed, with no security impact.
- **Owner:** infra-engineer.
- **Disposition:** this goes to the gate as a residual risk under ruling 23. Check it on the first window 2 load status, using `--status` and its errorLogs.

**M2. MINOR (documentation accuracy). The comment says an S3A walk HEADs "logs/emr" and "logs", but no code path in the repository puts S3A under logs/emr/.**
- **Where:** emr/main.tf:54-55 ("An S3A getFileStatus or mkdirs walk HEADs each parent of a write root ("curated", "logs/emr", "logs")"), and README.md:229 ("the parent keys an S3A mkdirs or getFileStatus walk HEADs").
- **Evidence:**
  - Only EMR's log pusher writes logs/emr/, through `--log-uri` (run-bounded-pipeline.sh:220), and the log pusher is not S3A.
  - No hadoop or spark main source names "logs/" (Grep: no match).
  - step.sh writes nothing to S3.
  - Repair-02's comment said the opposite ("no S3A walk reaches it"). The reversal comes with no new evidence.
- **Failure input:** none. The grant is harmless, holds no data, and ruling 23 ordered it.
- **Impact:** a plan reviewer reads a defensive grant as a known S3A dependency.
- **Owner:** infra-engineer. This is a residual risk; word it as "defensive" in a later phase.

**M3. MINOR (pre-existing, outside repair-03's diff). SERVICES.md:83 contradicts the ruling 23 rule.**
- **Where:** modules/services/SERVICES.md:83 says query-api's ListBucket "may be limited with an `s3:prefix` condition to `logs/rag-runtime/` and `reports/rag-runtime/`".
- **Why it is wrong:**
  - Ruling 23 says a prefix-conditioned ListBucket never applies to a GET or HEAD.
  - SERVICES.md's own lines :70 and :75 make the readiness probe depend on ListBucket applying.
  - This text is not in the Phase 12 change set (Grep of the cr4 change set found no match), so it is committed text from an earlier phase.
- **Failure input:** a maintainer follows :83 and adds that condition to iam/main.tf:184-188. The readiness-probe GET of `logs/rag-runtime/snapshot=<d>/run=<r>/readiness-probe` (Services.scala:57-58) then gets 403.
- **Impact:** `/readyz` reports NOT_READY permanently, and unknown query IDs answer 502 (SERVICES.md:75, :77).
- **Owner:** service-engineer. Carry it to the backlog as a residual risk; the docs need a one-line correction.

### N4 and N5 (code review 3) status
- **N4: RESOLVED** in the working tree, as ruling 23 ordered.
  - The unconditioned ListBucket is at emr/main.tf:158-162.
  - The parent keys include "logs" (:58).
  - The comments were corrected (:52-57, :154-157), and so was the README (:229).
  - That the ruling 23 S3 rule matches AWS behaviour is the human's decision. Nothing tests it locally.
- **N5:** accepted under ruling 23. The infra-engineer again reports that the Grep tool was unavailable (repair-03.md:103). .claude/agents/infra-engineer.md lists Grep, so I cannot verify the claim. It used Read only, with no Bash grep. Its output matches the working tree.

### OWNERSHIP CHECK
| Path | Owner | Author matches |
|---|---|---|
| infra/terraform/modules/emr/main.tf | infra-engineer | yes |
| infra/terraform/README.md | infra-engineer | yes |
| docs/agent-runs/phase-12/repair-03.md | orchestrator report assembly | allowed (repair-NN.md; phase-12 has no commit.txt, checked by Glob) |
| scratch/phase-12-logs/r03a-*.txt | agent log location (ruling 8) | allowed |

No repair-03 change falls outside its owner's rows.

### FLOOR AND FENCE CHECK
- None of these changed: CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, hooks, permission rules, markTestRun, build.sbt, the version ledger, scripts.
- The constitution gate passed in r03a-check.txt: validate-agent-files 231/231 (:235); guard-dangerous 304/304, guard-paths 142/142 and verify-tests-ran 11/11 (:202-204).
- The IAM widening is the unconditioned ListBucket plus one exact-key GetObject ("logs"). Ruling 23 decided both. The role can now list every key name in the data bucket but cannot read object data outside raw/, curated/ and artifacts/.
- No trust, action or other role changed.

### EVIDENCE GAPS
- The plan counts (43/0/0; 91 or 89; 134) and "1 in-place update" are inferred from the code. No plan was run (ruling 5). The diff adds and removes no resource, which agrees.
- The ruling 23 S3 semantics are unverified against AWS (human-decided):
  - whether a prefix-conditioned ListBucket applies to GET or HEAD;
  - the 404 versus 403 rule.
- The S3A probe order on emr-spark-8.0.0 (repair-03 assumption 1) is unverified until the first window 1 run.
- The text of AmazonEMRServicePolicy_v2 was not re-read (repair-03 assumption 2).
- Neptune's S3 access pattern is unverified (M1).
- r03a-tf-validate.txt records "fmt exit 0" but does not echo the command line.
- shellcheck was SKIPPED (not installed), and check.sh ran on the working tree, not on a clean checkout.
- The claim that the Grep tool was unavailable cannot be verified (N5, accepted).

### FILES, COMMANDS AND RESULTS
- **FILES CREATED:** none
- **FILES MODIFIED:** none
- **COMMANDS RUN:** none (no Bash tool). Read, Grep and Glob only.

**TEST RESULTS** (as supplied in repair-03.md:85-92, quoted, and checked in r03a-check.txt):
- "scripts/check.sh exited 0, with every gate OK": r03a-check.txt:1825 "==> OK check: every gate passed".
- "sbt tests: 839 passed, 0 failed, 10 pending". The suite lines are at :1392, 1397, 1402, 1513, 1571, 1793, 1798 and 1820, and they add up: 191+111+116+115+49+46+141+70 = 839, with 10 pending at :1798.
- "markTestRun: tests passed, wrote .../.claude/.last-test-run": :1822.
- dataset-validation: "[DONE] validate: all checks passed" (:362).
- terraform-fmt OK (:365) and terraform-validate "Success!" (:367).
- shellcheck "SKIPPED: shellcheck not installed" (:266).

**VALIDATION RESULTS** (r03a-tf-validate.txt):
- fmt exit 0 (:2);
- init "Terraform has been successfully initialized!" and exit 0, reusing hashicorp/aws v6.28.0 (:7-19);
- validate "Success! The configuration is valid." and exit 0 (:21-23);
- bash -n ok for all 13 scripts (:25-37).

### ASSUMPTIONS
- The ruling 23 S3 rule is binding law for this review. I did not test it against AWS.
- The cr4 change set reflects the final working tree. The emr/main.tf and README.md:229 that I read match it.

### RISKS
- **M1:** the window 2 Neptune bulk load may fail if Neptune probes unlisted keys.
- **EMR listing:** the EMR role, and so any step code, can now list every key name, including graph/, runs/, reports/ and logs/rag-runtime/. Ruling 23 accepted this.
- **Unknown S3A probes:** whether S3A probes keys outside raw/*, curated/*, artifacts/* and the three exact keys stays unknown until the first window 1 run. A HEAD under logs/emr/* would still get 403, because the role has no GetObject there, but no code path does that.
- **M3:** a future edit that follows SERVICES.md:83 would bring back the N4 failure on query-api and the worker.

### MANUAL ACTION REQUIRED (human)
- Record M1, M2 and M3 as residual risks at the gate (ruling 23).
- Annotate or supersede the wrong "turns that 403 into 404" claim in docs/agent-runs/phase-12/repair-02.md. It is human-owned.
- At the ruling 21 plan, confirm:
  - 43/0/0;
  - ListDataBucket has no Condition;
  - ProbeWriteRootParents lists exactly the three exact-key ARNs (curated, logs, logs/emr).

### HAND-OFF
- **BLOCKED-ON OWNER:** none
- **BLOCKED-ON EVIDENCE:** none
- **NEXT GATE:** findings recorded as residual risks (ruling 23: no further repair round). Then the human commits Phase 12 with phase_commit 12, runs scripts/plan.sh --runtime-enabled false, and signs the plan sha256 in human-gate.md (ruling 21).
````
