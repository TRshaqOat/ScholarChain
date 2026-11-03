# ScholarChain Architect Notes and Proposed Decisions

Owner: human architect (human-owned, read-only for all agents)
Provenance: drafted with Claude (claude.ai planning sessions, Oct-Nov 2026), reviewed and ratified by the architect.
Disclose this provenance in the final report as human-curated, AI-assisted planning input.

Status legend: PROPOSED (not yet ratified), RATIFIED, OPEN (needs verification or instructor answer).
Change a PROPOSED item to RATIFIED only after you have checked it yourself.

---

## 1. Platform decisions

| ID   | Decision | Status |
| ---- | -------- | ------ |
| D-01 | Spark jobs run on EMR release label `emr-spark-8.0.0` (Spark 4.0.2, Scala 2.13.16, Java 17). EMR 7.x ships Spark 3.5 on Scala 2.12, which Scala 3 cannot interoperate with. | RATIFIED 2026-11-02 (AWS release docs; live run pending) |
| D-02 | Scala 3 modules depend on Spark via `CrossVersion.for3Use2_13`. Never allow both `_2.13` and `_3` builds of the same library on the classpath. Prefer DataFrame and Java APIs over typed Dataset encoders. Scala is pinned to 3.3.7 because it depends on scala-library 2.13.16, the version EMR's Spark ships. | RATIFIED 2026-11-02 |
| D-03 | Hadoop MapReduce jobs are written against the Java MapReduce API, with the Scala 3 standard library shaded into the job jar. They run on `emr-spark-8.0.0`, which includes hadoop-mapred and YARN 3.4.2. EMR 7.x remains the fallback if a live run fails. | RATIFIED 2026-11-02 (AWS release docs; live run pending) |
| D-04 | Region: us-east-2 (the project AWS account's region; S3 Vectors GA since Dec 2025). | RATIFIED 2026-11-02 |
| D-05 | JSON: uPickle. Tests: ScalaTest everywhere. | RATIFIED 2026-11-02 |
| D-06 | No OpenSearch tier. S3 Vectors is the only semantic store. | RATIFIED 2026-11-02 |
| D-07 | Course spec lives at `spec/ScholarChain-spec.md`, not at the root `README.md` (root README is owned by documentation-engineer). | RATIFIED 2026-11-02 |
| D-08 | The AgenticScalaAppTutorial repo is a read-only pattern reference in a sibling directory. It is never forked, copied, or merged into this repository. | RATIFIED 2026-11-02 |
| D-09 | Version rule: every dependency, plugin, tool, and MCP server is pinned to an exact version at least two weeks old. No floating or latest versions. | RATIFIED 2026-11-02 |
| D-10 | Schedule-driven scope cuts (see section D-10 below). | RATIFIED 2026-11-02 |
| D-11 | Ponytail plugin and Apple Silicon toolchain (see section D-11 below). | RATIFIED 2026-11-02 |

## 2. Verification items

- [x] `emr-spark-8.0.0` includes Hadoop MapReduce (hadoop-mapred and YARN 3.4.2-amzn-1) alongside Spark 4.0.2, per AWS release documentation checked 2026-11-02.
- [ ] A Scala 3 Spark job and a Hadoop MapReduce job run on `emr-spark-8.0.0`. Requires the Paid plan; first cluster run (optional smoke test around 2026-11-07, otherwise Phase 14).
- [x] GraphFrames: not used. Graph algorithms are hand-written with DataFrame iteration (D-10).
- [ ] Bedrock: Anthropic and Amazon embedding models are listed in us-east-2 (2026-11-02). Invocation access still to be confirmed in the console before Phase 5.
- [x] Neptune API reachable in us-east-2 (describe-db-clusters succeeded 2026-11-02). Access path: loaders and services run inside the VPC (Phase 12).
- [ ] S3 Vectors Terraform resources work with the pinned AWS provider version (Phase 12).
- [ ] Read-only IAM role for the AWS MCP server (Phase 12, low priority under D-10). Until it exists, `SCHOLARCHAIN_AWS_READONLY_PROFILE` stays unset and the aws-api MCP server fails by design. The `scholarchain` CLI profile is full-access and must never be given to an MCP server.
- [x] MCP servers chosen and pinned: github (fine-grained read-only token, ScholarChain repo only), aws-docs 1.2.2, terraform 1.0.18 with `--with mcp==1.30.0` (mcp 2.x removed FastMCP), aws-api 1.5.6 (pending the read-only role above). github, aws-docs, and terraform connected in Phase 1.
- [x] Tool versions recorded in `docs/agent-runs/tool-versions.md` (Terraform to be added when installed).
- [ ] Account upgraded from the AWS Free plan to the Paid plan, with AWS Budgets alerts created first, no later than 2026-11-12.
- [ ] Cloud resources torn down after the demonstration (target 2026-11-20).

## 3. Known landmines and mitigations

- **Phase 3 dataset overlap.** Random samples of DBLP and OpenAlex/ORCID/Crossref will barely link. Drive every enrichment subset from the DBLP subset's DOIs and author names.
- **Phase 4 DBLP parsing.** `dblp.xml` uses character entities defined in `dblp.dtd`; the parser must load the DTD. Records must not be split across mappers. Test multiline titles, Unicode, and records near split boundaries from day one.
- **Phase 4 giant teams.** Pair expansion is n(n-1)/2 per paper; count and report oversize teams; blocking groups need a maximum size and fallback keys.
- **Phase 5 free evidence.** DBLP already disambiguates homonyms with numeric suffixes (for example "Wei Wang 0001"), and some DBLP person records carry ORCIDs. Use these as strong identity evidence.
- **Phase 6 algorithms not provided by libraries.** k-core (iterative peeling) must be implemented. Bridge measures are cross-community degree, communities-connected count, and participation coefficient as DataFrame aggregations (D-10).
- **Phase 6 skew.** Hub researchers and giant teams create skewed shuffles; checkpoint iterative jobs; never collect the graph to the driver.
- **Phase 7 Neptune.** Bulk loader needs a specific CSV header format, an IAM role, and S3 in the same region.
- **EMR 8 storage and SDK.** EMRFS and AWS SDK for Java v1 are removed in `emr-spark-8.0.0`. Jobs use `s3a://` paths and SDK v2 only.
- **Free plan cliff.** On the Free plan, access ends when credits run out, and EMR is blocked. Upgrade to the Paid plan before the first EMR use, with budget alerts in place.
- **Apple Silicon.** Local images are linux/arm64; ECS Fargate must use the ARM64 runtime platform (D-11).
- **Phase 12 ordering.** Resolved by the D-10 schedule: Phases 3 to 11 run locally on bounded samples, Terraform and scripts land 2026-11-11 to 11-12, and the first full cloud run is 2026-11-13.
- **Phase 15 scale-in.** ECS target tracking scales in only after a sustained period below target (roughly 15 minutes). Make experiment windows long enough to capture both scale-out and scale-in.

## 4. Agent-system notes

Phase 1 outcomes:

- The `tools` field restricts which tools an agent has, not which paths it may write. Path ownership is enforced by agent instructions, permission rules, and the `guard-paths.sh` PreToolUse hook.
- code-reviewer has Read, Grep, and Glob only.
- `spec/**` is declared human-owned and read-only in `docs/agents.md`.
- The spec's `feature-implementer` probe was run as written (probe 1b: no such agent exists, build.sbt is owned by build-engineer), with domain-engineer as the substitute non-owner (probe 1).

Phase 2 outcomes:

- The build-engineer refused to create module source roots, tests, and `.gitkeep` directories in other agents' territories. Human-approved deferral: each owning agent creates its own tree and tests in its own phase.
- The build-engineer refused to change version values (dependency-updater territory, docs/agents.md exception 1) and returned BLOCKED-ON; the dependency-updater made the change.
- Human ruling B (2026-11-02): comments that describe a pinned value (its date, its rationale, and any RISK comment about that value) travel with the value and are updated by the dependency-updater.

Lessons to carry forward:

- Broad auto-approve allow rules caused the critical bypasses in Phase 1. Risky commands fall back to the permission prompt, where the human is the gate. Do not chase perfect regex guards.
- Stopping rule: ratify when only minor findings remain and document them as residual risks; repair again only for critical or major findings. One repair round per phase.
- A probe passes only on a hook or permission block message. A model refusal is INCONCLUSIVE.
- At permission prompts choose the one-time allow, never "always allow" (it writes `.claude/settings.local.json`).
- Agents cannot write `.patch` files in phase folders. The human creates patches with `diff -u` and records the sha256. `/private/tmp` scratchpads are wiped on reboot, so copy evidence out promptly.
- Deny protected-path writes such as `rm -rf spec` at the permission prompt (accepted residual risk).
- Never accept tool offers to configure AWS MCP servers with the full-access profile (for example the `aws login` prompt).

## 5. Evidence to capture as you go

Per phase, `docs/agent-runs/phase-NN/`: `prompt.md`, `agent-report.md`, `review-report.md`, `test-report.md` (Phase 11), `repair-NN.md` when needed, `human-gate.md`, `commit.txt`.

Running factory-metrics log (`docs/agent-runs/factory-metrics.md`, feeds Phase 16, impossible to reconstruct later), one row per agent invocation:

```text
date | phase | agent | status | first-pass accepted (y/n) | findings critical/major/minor | repair iteration | manual edits | hooks triggered | notes
```

Per-file provenance in each human-gate: agent-generated accepted as-is, repaired by another agent, manually edited, or human-authored.

Also: constitutional ratifications, gold labels (200), benchmark questions (30), relevance labels, answer ratings, run IDs and manifests, Terraform plan checksums and approvals, cost budget, experiment manifests committed before runs, raw metrics (large ones in S3 with checksums), incident reports.

`human-gate.md` template:

```text
Phase:
Reviewed:
Accepted / rejected:
Manual changes (files and reason):
File provenance:
Remaining risk:
Accepted commit:
```

## 6. Schedule (deadline Wed Nov 25, 2026, 11PM CST; revised 2026-11-02 per D-10)

| Date | Work | Human side-work |
| ---- | ---- | --------------- |
| Mon Nov 2 | Phase 2 build | Scope cuts, AWS capability check, Ponytail |
| Tue Nov 3 | Phase 3 datasets and contracts | Run bounded downloads |
| Wed Nov 4 | Phase 4 Hadoop (local) | |
| Thu Nov 5 | Phase 5 entity resolution | Label 200 gold pairs |
| Fri Nov 6 | Phase 6 Spark (local) | Write 30 benchmark questions |
| Sat Nov 7 | Phases 7+8 storage and embeddings | Optional: Paid plan upgrade and EMR smoke test; install Terraform |
| Sun Nov 8 | Phase 9 runtime agents | |
| Mon Nov 9 | Phase 10 services | |
| Tue Nov 10 | Phase 11 adversarial tests and full review | |
| Wed-Thu Nov 11-12 | Phases 12+13 Terraform and scripts | Review plan; Paid plan upgrade by Nov 12 |
| Fri Nov 13 | Phase 14 deploy, EMR pipeline, store loads | Approve apply |
| Sat-Sun Nov 14-15 | Buffer: integration fixes | |
| Mon-Tue Nov 16-17 | Phases 15+16 experiments and evaluation | Rate answers |
| Wed Nov 18 | Freeze raw results; Phase 17 documentation | |
| Thu Nov 19 | Final review and video | Record video |
| Fri Nov 20 | Teardown and cleanup evidence | |
| Sat-Tue Nov 21-24 | Deferred backlog in priority order (D-10) | |
| Wed Nov 25 | Tag and submit by 6 PM | |

Scope cuts are listed in D-10.

## 7. Open questions for the instructor

The instructor is not expected to be available for these. Working assumptions, documented for the final report:

- Individual project.
- `feature-implementer` probe: run as written in Phase 1, with domain-engineer as the substitute non-owner.
- AWS: the student's own account in us-east-2, upgraded from the Free plan to the Paid plan for EMR, with budget alerts.
- Enrichment subsets are fetched through dataset APIs, driven by the DBLP subset's DOIs, and documented in the manifest.
- Actual scale (works, edges, vectors) is reported honestly; any shortfall against spec minimums is explained in the final report.

## Decision status (human ratification log)

- 2026-11-02: D-04 to D-09 RATIFIED by the human architect. D-05 test framework selected: ScalaTest.
- 2026-11-02: D-01 to D-03 RATIFIED on AWS release documentation evidence (see the AWS capability check below); live verification pending the first EMR run.
- 2026-11-02: D-10 and D-11 RATIFIED.
- 2026-11-02: Phase 2 human decisions: Scala 3.3.7, Spark 4.0.2, Hadoop 3.4.2 to match `emr-spark-8.0.0`; ruling B (section 4).

## D-10 Schedule-driven scope (RATIFIED 2026-11-02)

Context: 23 days remain before the 2026-11-25 23:00 CST deadline, and Phase 2 was not yet complete. Scope is cut to protect every item in the spec's "Minimum acceptance criteria". Deferred items return only if time remains.

Cuts and substitutions:
1. Datasets: DBLP + OpenAlex + Crossref + ORCID only, as DOI-driven bounded subsets of a ~100k-work DBLP subset.
2. Merged phases: 7+8 (storage and embeddings), 12+13 (Terraform and scripts), 15+16 (experiments and evaluation). The test-engineer runs once, in Phase 11.
3. Spark graph algorithms use DataFrame iteration; no GraphFrames dependency.
4. Bridge measures: cross-community degree, communities-connected count, participation coefficient. Approximate betweenness deferred.
5. Temporal analysis: three fixed windows (all years, 2000-2014, 2015-2026). Effective-diameter estimation deferred.
6. Async work: SQS plus a worker service; no Step Functions.
7. Three runtime services: query-api, graph-evidence-api, worker.
8. Network and security: two-AZ VPC; ECS tasks and EMR in public subnets behind security groups; Neptune in private subnets; no NAT gateway; AWS-managed encryption; local Terraform state (gitignored, backed up outside the repo), remote-state design documented only.
9. API authentication: one API key stored in Secrets Manager.
10. Experiments: A (EMR baseline), D (query load with ECS scale-out and scale-in), E (retrieval modes), F (task-kill recovery). B and C deferred.
11. No OpenSearch (D-06), no GitHub Actions, no security scanner beyond terraform validate.
12. Phase evidence: prompt, agent report, review report, repair report when needed, short human gate, commit hash, factory-metrics row.

Deferred backlog, in priority order: approximate betweenness; experiment B; experiment C; effective diameter; checkov scan; Step Functions.

## D-11 Ponytail plugin and Apple Silicon toolchain (RATIFIED 2026-11-02)

- Ponytail v5.3.2 (commit 16352ac229eb08a9598dcae48fe9f2b342567d86, released 2026-10-16) is installed as a user-scope Claude Code plugin from a local clone at ~/Developer/tools/ponytail-v5.3.2, so it never auto-updates from main (D-09).
- PONYTAIL_DEFAULT_MODE=full. PONYTAIL_SUBAGENT_MATCHER limits injection to implementation agents. code-reviewer, test-engineer, factory-engineer, deploy-engineer, and incident-responder run without it, to keep review and test independence.
- /ponytail-review never replaces the code-reviewer agent gate.
- Ponytail is not part of the repository and changes no constitutional file. It is optional for workflow replay and is recorded in docs/agent-runs/tool-versions.md.
- Verified 2026-11-02: guard-dangerous.sh still blocks with Ponytail active (probe: echo 'terraform destroy' -> BLOCKED [terraform-apply-destroy]).
- Correction: the development machine is Apple Silicon (arm64), not Intel. Local JDK is the Temurin aarch64 build. Container images target linux/arm64, and ECS Fargate will use the ARM64 runtime platform (applied in Phase 12).

## AWS capability check (2026-11-02, read-only CLI and AWS release documentation)

- Account plan: AWS Free plan, $100 credits, free period ends 2027-04-20. EMR and EMR Serverless are denied by an AWS service control policy on this plan. Bedrock, Neptune, S3 Vectors, DynamoDB, ECS, ECR, SQS, EC2, Secrets Manager, and S3 API calls succeed.
- AWS release documentation: emr-spark-8.0.0 (released 2026-05-21) ships Spark 4.0.2-amzn-0, Scala 2.13.16, Amazon Corretto 17 by default, and Hadoop 3.4.2-amzn-1 including hadoop-mapred and YARN, on EC2, EKS, and Serverless. EMRFS and AWS SDK for Java v1 are removed, so jobs use s3a:// paths and SDK v2 only.
- Decision: upgrade to the Paid plan before the first EMR cluster (no later than 2026-11-12, before Phase 14), after creating AWS Budgets alerts. Live verification of D-01 to D-03 happens on the first cluster run.
- Status: D-01, D-02, D-03 RATIFIED (2026-11-02) on documentation evidence; live check pending.

- 2026-11-02 (Phase 2 review): Ponytail was injected into the code-reviewer despite PONYTAIL_SUBAGENT_MATCHER. Fallback until fixed: send /ponytail off before reviewer and test-engineer prompts, and /ponytail full afterwards.
- 2026-11-02 (after Phase 2): Ponytail matcher fixed by setting both variables in the env block of ~/.claude/settings.json. Verified that code-reviewer receives no Ponytail ruleset and build-engineer does. The /ponytail off fallback is no longer needed.

## D-12 Paid plan, cost guardrails, and Bedrock models (RATIFIED 2026-11-03)

- Free plan on-demand Bedrock quotas were 0 for Claude Haiku 4.5 and Titan Text Embeddings V2 (Titan marked not adjustable), and EMR was denied. The account was upgraded to the Paid plan on 2026-11-03 (earlier than the planned 2026-11-12).
- Guardrails: a $60/month project spend limit (the project pauses at the limit); early cost controls skipped because they could block EMR launches and ECS scale-out; AWS Budget "scholarchain-monthly" ($100, IncludeCredit=false, so alerts track gross usage) with email alerts at 10/25/50/75% actual and 100% forecast.
- Credits: $180 after the Free-plan credit activities. Those activities used throwaway console resources (one EC2 instance, a partial Lambda web function and its code bucket, one RDS database and its backups), all deleted afterwards and verified by CLI. They are not project infrastructure; project infrastructure comes only from Terraform.
- Models: synthesis and adjudication use us.anthropic.claude-haiku-4-5-20251001-v1:0 (Claude 3 Haiku has reached end of life); embeddings use amazon.titan-embed-text-v2:0. Anthropic use-case form submitted 2026-11-03. On-demand quotas to be re-verified after the upgrade; bulk embeddings may use Bedrock batch inference.

## D-13 Instructor guidance (received 2026-11-03) and cost plan (RATIFIED 2026-11-03)

Instructor answers:
- Bounded, DOI-driven subsets of OpenAlex, Crossref, and ORCID are approved. Required: document the data selection criteria, integration procedures, matching rules, and resulting coverage, and show how the sources enrich DBLP and how missing or inconsistent information is handled.
- A DBLP subset with fewer than 1M coauthor edges is approved. Required: report the actual numbers of publications, authors, and edges; demonstrate scalability by evaluating performance at different dataset sizes; explain the limitations of the smaller dataset.
- Recommended total AWS spending is below $25. Billing alerts do not enforce a limit.

Decisions:
- Target gross AWS usage below $25 (credits mean out-of-pocket cost is expected to be $0). The AWS Budget is lowered to $25 (gross, alerts at 10/25/50/75% actual and 100% forecast). The $60 project spend limit stays as a hard backstop.
- Short cloud windows instead of a week-long deployment: (1) ephemeral EMR clusters for the pipeline and the size-scaling runs, each terminated after its run; (2) one runtime window of at most about 48 hours for Neptune, ECS, and the stores: load, smoke tests, experiments D/E/F, and demo video recording, then destroy.
- Deferred experiment B is replaced by a data-size scaling experiment: the Hadoop and Spark pipeline on EMR at about 25%, 50%, and 100% of the subset, with run times, counts, and cost per run.
- The final report explicitly covers selection criteria, integration, matching rules, coverage, handling of missing and inconsistent data, and the limitations of the smaller dataset.
