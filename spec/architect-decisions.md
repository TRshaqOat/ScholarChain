# ScholarChain Architect Notes and Proposed Decisions

Owner: human architect (human-owned, read-only for all agents)
Provenance: drafted with Claude (claude.ai planning session, Oct 2026), reviewed and ratified by the architect.
Disclose this provenance in the final report as human-curated, AI-assisted planning input.

Status legend: PROPOSED (not yet ratified), RATIFIED, OPEN (needs verification or instructor answer).
Change a PROPOSED item to RATIFIED only after you have checked it yourself.

---

## 1. Platform decisions

| ID | Decision | Status |
|---|---|---|
| D-01 | Spark jobs run on EMR release label `emr-spark-8.0.x` (Spark 4.0, Scala 2.13, Java 17). EMR 7.x ships Spark 3.5 on Scala 2.12, which Scala 3 cannot interoperate with. | PROPOSED |
| D-02 | Scala 3 modules depend on Spark via `CrossVersion.for3Use2_13`. Never allow both `_2.13` and `_3` builds of the same library on the classpath. Prefer DataFrame and Java APIs over typed Dataset encoders. | PROPOSED |
| D-03 | Hadoop MapReduce jobs are written against the Java MapReduce API. Hadoop has no Scala binary constraint, so the Scala 3 standard library is shaded into the job jar. | PROPOSED |
| D-04 | Region: us-east-1 or us-east-2 (S3 Vectors GA since Dec 2025 in both). | PROPOSED |
| D-05 | JSON: uPickle. Tests: pick ScalaTest or MUnit once. | PROPOSED |
| D-06 | No OpenSearch tier. S3 Vectors is the only semantic store. | PROPOSED |
| D-07 | Course spec lives at `spec/ScholarChain-spec.md`, not at the root `README.md` (root README is owned by documentation-engineer). | PROPOSED |
| D-08 | The AgenticScalaAppTutorial repo is a read-only pattern reference in a sibling directory. It is never forked, copied, or merged into this repository. | PROPOSED |

## 2. Week-1 verification spike (OPEN until done)

- [ ] `emr-spark-8.0.x` can also run Hadoop MapReduce jobs on EC2. If not, run MapReduce on an EMR 7.x cluster (see D-03, which makes that safe) and Spark on `emr-spark-8.0.x`.
- [ ] A Scala 3 hello-world Spark job built with `for3Use2_13` runs on the chosen EMR release.
- [ ] GraphFrames has a release for the exact Spark 4.0 / Scala 2.13 combination. If not, connected components and label propagation must be hand-written.
- [ ] Bedrock model access requested and granted in the chosen region (embedding model and synthesis model).
- [ ] Neptune (or Neptune Serverless) available in the region; plan bastion or SSM tunnel for laptop access (Neptune is VPC-only).
- [ ] S3 Vectors Terraform resources work with the pinned AWS provider version.
- [ ] Concrete MCP servers chosen for GitHub, read-only AWS API, Terraform docs, AWS docs; each confirmed to run least-privilege.
- [ ] Claude Code version recorded in the tool-version record.

## 3. Known landmines and mitigations

- **Phase 3 dataset overlap.** Random samples of DBLP and OpenAlex/ORCID/Crossref will barely link. Drive every enrichment subset from the DBLP subset's DOIs and author names.
- **Phase 4 DBLP parsing.** `dblp.xml` uses character entities defined in `dblp.dtd`; the parser must load the DTD. Records must not be split across mappers. Test multiline titles, Unicode, and records near split boundaries from day one.
- **Phase 4 giant teams.** Pair expansion is n(n-1)/2 per paper; count and report oversize teams; blocking groups need a maximum size and fallback keys.
- **Phase 5 free evidence.** DBLP already disambiguates homonyms with numeric suffixes (for example "Wei Wang 0001"), and some DBLP person records carry ORCIDs. Use these as strong identity evidence.
- **Phase 6 algorithms not provided by libraries.** k-core (iterative peeling) and approximate betweenness (sampled sources, documented) must be implemented. Cheap bridge measures: cross-community degree and participation coefficient as DataFrame aggregations.
- **Phase 6 skew.** Hub researchers and giant teams create skewed shuffles; checkpoint iterative jobs; never collect the graph to the driver.
- **Phase 7 Neptune.** Bulk loader needs a specific CSV header format, an IAM role, and S3 in the same region.
- **Phase 12 ordering.** S3 and EMR are needed from Phase 4, but the full Terraform phase is Phase 12 and the console is forbidden. Have infra-engineer write a minimal foundation module (buckets, EMR, IAM) early and grow it into Phase 12.
- **Phase 15 scale-in.** ECS target tracking scales in only after a sustained period below target (roughly 15 minutes). Make experiment windows long enough to capture both scale-out and scale-in.

## 4. Agent-system notes for Phase 1

- The `tools` field restricts which tools an agent has, not which paths it may write. Enforce path ownership with agent instructions plus permission rules and the PreToolUse hook. Check what the hook input exposes about the calling agent.
- Bash can write files. Give code-reviewer Read, Grep, Glob only, or keep Bash with deny rules for any file-modifying command.
- `spec/**` must be declared human-owned and read-only in `docs/agents.md`.
- Spec inconsistency: post-Phase-1 probe #1 names `feature-implementer`, which is not in the required agent list. Ask the instructor or substitute `domain-engineer` and record the substitution in `phase-01/human-gate.md`.

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

| Week | Focus |
|---|---|
| 1 | Phases 0-2 plus verification spike; DBLP subset downloaded |
| 2 | Phase 3; gold-set labeling begins (about 20 min/day) |
| 3 | Phase 4 Hadoop |
| 4 | Phase 5 entity resolution |
| 5 | Phase 6 Spark (protect this week) |
| 6 | Phases 7-8 |
| 7 | Phases 9-10 |
| 8 | Phases 11-14 |
| 9 | Phases 15-16 |
| 9.5 | Phase 17, report, video, cleanup |

Scope cuts: no OpenSearch, stay at the 100k-works minimum until late, run the 1M-edge scale experiment once as a deliberate burst.

## 7. Open questions for the instructor

- [ ] Individual or team project?
- [ ] `feature-implementer` probe: which agent should it target?
- [ ] Course AWS account, region restrictions, and budget cap.
- [ ] May bounded enrichment subsets be fetched through dataset APIs (for example DOI-filtered OpenAlex queries) rather than full snapshots?
