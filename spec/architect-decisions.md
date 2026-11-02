# ScholarChain Architect Notes and Proposed Decisions

Owner: human architect (human-owned, read-only for all agents)
Provenance: drafted with Claude (claude.ai planning session, Oct 2026), reviewed and ratified by the architect.
Disclose this provenance in the final report as human-curated, AI-assisted planning input.

Status legend: PROPOSED (not yet ratified), RATIFIED, OPEN (needs verification or instructor answer).
Change a PROPOSED item to RATIFIED only after you have checked it yourself.

---

## 1. Platform decisions

| ID   | Decision                                                                                                                                                                                                                              | Status   |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| D-01 | Spark jobs run on EMR release label `emr-spark-8.0.x` (Spark 4.0, Scala 2.13, Java 17). EMR 7.x ships Spark 3.5 on Scala 2.12, which Scala 3 cannot interoperate with.                                                                | PROPOSED |
| D-02 | Scala 3 modules depend on Spark via `CrossVersion.for3Use2_13`. Never allow both `_2.13` and `_3` builds of the same library on the classpath. Prefer DataFrame and Java APIs over typed Dataset encoders.                            | PROPOSED |
| D-03 | Hadoop MapReduce jobs are written against the Java MapReduce API. Hadoop has no Scala binary constraint, so the Scala 3 standard library is shaded into the job jar. If `emr-spark-8.0.x` lacks MapReduce, MapReduce runs on EMR 7.x. | PROPOSED |
| D-04 | Region: us-east-2 (the project AWS account's region; S3 Vectors GA since Dec 2025).                                                                                                                                                   | PROPOSED |
| D-05 | JSON: uPickle. Tests: pick ScalaTest or MUnit once and use it everywhere.                                                                                                                                                             | PROPOSED |
| D-06 | No OpenSearch tier. S3 Vectors is the only semantic store.                                                                                                                                                                            | PROPOSED |
| D-07 | Course spec lives at `spec/ScholarChain-spec.md`, not at the root `README.md` (root README is owned by documentation-engineer).                                                                                                       | PROPOSED |
| D-08 | The AgenticScalaAppTutorial repo is a read-only pattern reference in a sibling directory. It is never forked, copied, or merged into this repository.                                                                                 | PROPOSED |
| D-09 | Version rule: every dependency, plugin, tool, and MCP server is pinned to an exact version at least two weeks old. No floating or latest versions.                                                                                    | PROPOSED |

## 2. Verification spike and open infrastructure items (OPEN until done)

- [ ] `emr-spark-8.0.x` can also run Hadoop MapReduce jobs on EC2. If not, run MapReduce on an EMR 7.x cluster (see D-03, which makes that safe) and Spark on `emr-spark-8.0.x`.
- [ ] A Scala 3 hello-world Spark job built with `for3Use2_13` runs on the chosen EMR release.
- [ ] GraphFrames has a release for the exact Spark 4.0 / Scala 2.13 combination. If not, connected components and label propagation must be hand-written.
- [ ] Bedrock model access requested and granted in us-east-2 (embedding model and synthesis model).
- [ ] Neptune (or Neptune Serverless) available in us-east-2; plan bastion or SSM tunnel for laptop access (Neptune is VPC-only).
- [ ] S3 Vectors Terraform resources work with the pinned AWS provider version.
- [ ] Read-only IAM role for the AWS MCP server (week-2 Terraform). Until it exists, `SCHOLARCHAIN_AWS_READONLY_PROFILE` stays unset and the aws-api MCP server fails by design. The `scholarchain` CLI profile is full-access and must never be given to an MCP server.
- [x] MCP servers chosen and pinned: github (fine-grained read-only token, ScholarChain repo only), aws-docs 1.2.2, terraform 1.0.18 with `--with mcp==1.30.0` (mcp 2.x removed FastMCP), aws-api 1.5.6 (pending the read-only role above). github, aws-docs, and terraform connected in Phase 1.
- [ ] Claude Code VS Code extension version, Java, sbt, Scala, Terraform, AWS CLI, and uv versions recorded in the tool-version record.
- [ ] Spike resources torn down after verification.

## 3. Known landmines and mitigations

- **Phase 3 dataset overlap.** Random samples of DBLP and OpenAlex/ORCID/Crossref will barely link. Drive every enrichment subset from the DBLP subset's DOIs and author names.
- **Phase 4 DBLP parsing.** `dblp.xml` uses character entities defined in `dblp.dtd`; the parser must load the DTD. Records must not be split across mappers. Test multiline titles, Unicode, and records near split boundaries from day one.
- **Phase 4 giant teams.** Pair expansion is n(n-1)/2 per paper; count and report oversize teams; blocking groups need a maximum size and fallback keys.
- **Phase 5 free evidence.** DBLP already disambiguates homonyms with numeric suffixes (for example "Wei Wang 0001"), and some DBLP person records carry ORCIDs. Use these as strong identity evidence.
- **Phase 6 algorithms not provided by libraries.** k-core (iterative peeling) and approximate betweenness (sampled sources, documented) must be implemented. Cheap bridge measures: cross-community degree and participation coefficient as DataFrame aggregations.
- **Phase 6 skew.** Hub researchers and giant teams create skewed shuffles; checkpoint iterative jobs; never collect the graph to the driver.
- **Phase 7 Neptune.** Bulk loader needs a specific CSV header format, an IAM role, and S3 in the same region.
- **Phase 12 ordering.** S3 and EMR are needed from Phase 4, but the full Terraform phase is Phase 12 and the console is forbidden. Have infra-engineer write a minimal foundation module (buckets, EMR, IAM, read-only MCP role) in week 2 and grow it into Phase 12.
- **Phase 15 scale-in.** ECS target tracking scales in only after a sustained period below target (roughly 15 minutes). Make experiment windows long enough to capture both scale-out and scale-in.

## 4. Agent-system notes

Phase 1 outcomes:

- The `tools` field restricts which tools an agent has, not which paths it may write. Path ownership is enforced by agent instructions, permission rules, and the `guard-paths.sh` PreToolUse hook.
- code-reviewer has Read, Grep, and Glob only.
- `spec/**` is declared human-owned and read-only in `docs/agents.md`.
- The spec's `feature-implementer` probe was run as written (probe 1b: no such agent exists, build.sbt is owned by build-engineer), with domain-engineer as the substitute non-owner (probe 1). Instructor confirmation is still requested (section 7).

Lessons to carry forward:

- Broad auto-approve allow rules caused the critical bypasses in Phase 1. Risky commands fall back to the permission prompt, where the human is the gate. Do not chase perfect regex guards.
- Stopping rule: ratify when only minor findings remain and document them as residual risks; repair again only for critical or major findings.
- A probe passes only on a hook or permission block message. A model refusal is INCONCLUSIVE.
- At permission prompts choose the one-time allow, never "always allow" (it writes `.claude/settings.local.json`).
- Agents cannot write `.patch` files in phase folders. The human creates patches with `diff -u` and records the sha256. `/private/tmp` scratchpads are wiped on reboot, so copy evidence out promptly.
- During later phases, deny protected-path writes such as `rm -rf spec` at the permission prompt (accepted residual risk).

## 5. Evidence to capture as you go

Per phase, `docs/agent-runs/phase-NN/`: `prompt.md`, `agent-report.md`, `review-report.md`, `test-report.md`, `repair-NN.md`, `human-gate.md`, `commit.txt`.

Running factory-metrics log (feeds Phase 16, impossible to reconstruct later), one row per agent invocation:

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

## 6. Schedule (deadline Wed Nov 25, 2026, 11PM CST)

| Week | Dates        | Focus                                                                          |
| ---- | ------------ | ------------------------------------------------------------------------------ |
| 1    | Oct 20–26    | Phases 1–3; AWS spike; start gold labels (~15/day)                             |
| 2    | Oct 27–Nov 2 | Phase 4 Hadoop; minimal Terraform foundation (S3, EMR, IAM, read-only role)    |
| 3    | Nov 3–9      | Phase 5 entity resolution; Phase 6 Spark                                       |
| 4    | Nov 10–16    | Phases 7–10; full Phase 12 Terraform                                           |
| 5    | Nov 17–25    | Phases 13–14 deploy, 15–16 experiments, freeze Nov 21, Phase 17 docs and video |

Phase 1 ran long; start Phase 2 immediately after ratification.

Scope cuts:

- Three enrichment datasets (OpenAlex, Crossref, ORCID) as DOI-driven bounded subsets of the DBLP subset.
- 100k DBLP works for the integrated run.
- Three deployed runtime services (the spec minimum).
- No OpenSearch (D-06).
- The 1M-edge scale experiment runs once as a deliberate burst, or a smaller scale is requested from the instructor (section 7).

## 7. Open questions for the instructor

- [ ] Individual or team project?
- [ ] `feature-implementer` probe: which agent should it target?
- [ ] Course AWS account, region restrictions, and budget cap.
- [ ] May bounded enrichment subsets be fetched through dataset APIs (for example DOI-filtered OpenAlex queries) rather than full snapshots?
- [ ] Is a scale below the spec minimums (for example fewer than 1M graph edges) acceptable given the budget?

## Decision status (human ratification log)

- 2026-11-02: D-04 to D-09 RATIFIED by the human architect. D-05 test framework selected: ScalaTest.
- 2026-11-02: D-01 to D-03 remain PROPOSED until the AWS capability check confirms an EMR release label with Hadoop MapReduce and Spark 4.x.

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
