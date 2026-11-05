# Phase 06 review reports

## Review 1: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 6 code review 1. I checked the full Phase 6 change set against work orders 1 to 5, human rulings 1 to 20, spec Phase 6 (jobs 1 to 11 and the gate), and D-01, D-02, D-09 and D-10.
VERDICT: REQUEST_CHANGES

The two-tier clustering and the refusal rules are correct, including at tier 1. No large table goes to the driver, the algorithms are DataFrame-only with a checkpoint every round, and the fixtures can be checked by hand. Three MAJOR findings block approval. Two are fixes for the spark-engineer: record-level provenance and the core schemas. The third is the ruling 12 ownership question, which needs a recorded human decision rather than a code change.

SCOPE REVIEWED:
- HEAD 84d90b0. Working tree: `build.sbt` (modified), `modules/spark/**` (30 untracked files: `JOBS.md`, the conf file, 21 main sources, 8 test sources), and the orchestrator's `docs/agent-runs/phase-06/agent-report.md`.
- I read in full every touched file in modules/spark, plus the build.sbt diff.
- Cross-checked against:
  - core `Records.scala` and `StableIds.scala`
  - entity-resolution `MatchPolicy.pathOf` and `Normalize.splitHomonym`
  - `modules/hadoop/JOBS.md` (J03+J04 section)
  - `.claude/agents/spark-engineer.md` and `rag-engineer.md`
  - the spec Phase 6 section and architect decisions D-01, D-02, D-09, D-10
- Spot-checked run outputs:
  - scratch/spark/snapshot=2026-10-03/run=run-0efdd4c59c16686e7913348721178b21/_manifest.json (`researcherConflictGroupCheck`)
  - .../run=run-be3b1cfe152896b20a54933ffb5af9e6/export/load-manifest.json (table schemas)
- Nothing under labels/ was read, listed, or searched.

What I checked and found sound:
- **Refusal rules a to d on every path into a cluster.** `Clustering.refused` runs on both the r1 (tier 1) and r2 (tier 2) groupings, over every node including `orcid:` and `openalex:A` key nodes.
  - 5a: ORCIDs from mentions and from keys, normalized by checksum.
  - 5b: CONFLICT decisions inside a cluster.
  - 5c: the same pattern as `Normalize.splitHomonym`; an unsuffixed mention never conflicts, which matches `orcidGroupSuffixConflict`.
  - 5d: two or more distinct members of one group.
  - All four rules are monotone, so "tier-2 kept implies tier-1 kept" holds and the TIER2 > TIER1 > SINGLETON choice in `Clustering.scala:102-114` is correct.
  - UNRESOLVED, REJECTED and CONFLICT decisions never add an edge.
  - The copied `MatchPath` equals `MatchPolicy.pathOf` (`MatchPolicy.scala:391-402`).
- **Driver collection.** Only bounded results are collected: per-window counts, top-N lists, at most 2 x 50 sampled IDs, the pairs file, and per-round counts. `rows(groupCheck)` grows with the number of J03+J04 groups (17 here).
- **Algorithms.**
  - Large-star/small-star, min-label components, k-core peeling, synchronous label propagation and multi-source BFS are all DataFrame iteration, each with a reliable checkpoint every round. There is no GraphFrames and no new library (D-09, D-10).
  - The build adds only JVM options. No dependency changed, and `markTestRun` and `check` are untouched.
- **Determinism.**
  - IDs come from hashes or the smallest member.
  - Label propagation breaks ties by most votes, then smallest label (`min_by` on (-n, label)).
  - The BFS parent is the smallest neighbour; samples are ranked by a seeded hash; every top-N list has an explicit order with an ID tie-break.
  - The `first(...)` aggregates in `researcherTable` and `workTable` are effectively deterministic, because all members of a final cluster carry the same rule arrays.
- **Window columns.** Every time-dependent output has them. The not-windowed institutions are stated as a limitation.
- **Fixtures.** I re-derived these by hand and they match:
  - min-label rounds (5, 1, 0);
  - k-core peel log (0,1) (1,2) (1,1) (1,1) (2,3) (3,4);
  - the label propagation rounds on the two cliques, and the bridge values 0.5 and 0.375;
  - the job 3 P/C/T/A/M/W table;
  - the refusal scenarios in `Fixture.scala`.
- **Bridge measures.** All three are defined, with their ranges and limitations, in `JOBS.md:158-175` and `Communities.scala:68-82`.

FINDINGS:

**MAJOR-1. Store-bound derived tables carry no record-level provenance.**
- Owner: spark-engineer.
- Where:
  - `modules/spark/src/main/scala/scholarchain/spark/ResearcherAnalyticsJob.scala:130-150`: `analytics/components`, targets s3 and dynamodb.
  - `CommunityPathJob.scala:372-389`: `analytics/cross-community-edges`, targets neptune and s3.
  - `Temporal.scala:118-146` with `TemporalSummaryExportJob.scala:343-377`:
    - `temporal/researcher-changes` (dynamodb, s3)
    - `temporal/window-comparison` (dynamodb, s3)
    - `temporal/community-transitions` (s3)
  - Also `Canonical.scala:192-232` (mention-map, work-map), `CoauthorGraph.scala:81-89` (self-pairs) and `Clustering.scala:158-181` (refusals).
- Failure input: any run. In load-manifest.json run-be3b1cfe..., the schemas at lines 277, 383, 449, 465 and 480 have no `provenance` column. Every other table has one.
- Impact: Phase 7+8 loaders will write DynamoDB items and Neptune edges for component membership, cross-community edges and temporal changes that cannot be traced to snapshot, run ID and policy version. A runtime claim such as "moved into the largest component" or "became cross-community" cites a record without provenance. This breaks the CLAUDE.md invariant "Records and claims without provenance are defects."

**MAJOR-2. Exported tables diverge from the core versioned schemas, and the contract change was not routed to domain-engineer.**
- Owner: spark-engineer, to conform or return BLOCKED-ON domain-engineer. domain-engineer defines or versions any new contract; the human decides which.
- Where:
  - `CoauthorGraph.scala:120-132`: `graph/coauthor-edges` has no `schemaVersion`, which core `CoauthorEdge` requires (`Records.scala:177-189`).
  - `Canonical.scala:268-282`: `canonical/researchers` has no `schemaVersion` and no `externalIds` (core `CanonicalResearcher`, `Records.scala:125-135`).
  - `Canonical.scala:339-358`: `canonical/authored` has one row per (researcher, work), with no `mentionId`, `matchConfidence` or `schemaVersion`. Core `AuthorshipRecord` is one row per mention (`Records.scala:138-147`).
  - `ResearcherAnalyticsJob.scala:184-204`: `researcher-metrics` leaves out the core `ResearcherMetrics` fields `communityId`, `crossCommunityDegree`, `communitiesConnected` and `participationCoefficient`. They moved to `researcher-communities`, which has no `schemaVersion`.
  - Components, communities, path-evidence, temporal tables and summaries have no core schema at all.
- Failure input: a Phase 7 loader that decodes `edges/type=authored` with the core `AuthorshipRecord` codec fails on the missing `mentionId` and `matchConfidence`. The same happens for `schemaVersion` on `nodes/type=researcher` and `edges/type=coauthored_with`.
- Impact: this breaks the CLAUDE.md rule "Pipeline stages hand off data only through versioned S3 datasets whose schemas are defined in core." Ruling 15 called for BLOCKED-ON domain-engineer; instead the schemas were forked and the change was listed only under ASSUMPTIONS.

**MAJOR-3. Ownership conflict over summary text rendering (the spark-engineer's question).**
- Owner: human architect, to record a decision in `human-gate.md`. factory-engineer, if exception 5 is to be amended. No code change is needed if the human ratifies the current split.
- Where: `Summaries.scala` (template version at :20; text rendering at :235-281, :366-383, :459-515, :580-595).
- The conflict: ruling 12 says summaries are template text, but several constitutional texts give text rendering to the rag-engineer:
  - `docs/agents.md` exception 5 and the rag-engineer row ("profile rendering templates");
  - `.claude/agents/spark-engineer.md` law 7 and line 40 ("Text rendering ... → rag-engineer").
- Escalation rule 7 says an artifact that matches two rows is not written until the ownership is ratified. Under my law 3 that is at least MAJOR.
- Failure input: Phase 8. The rag-engineer's own templates and chunking for `researcher-profile` would compete with Spark's `summary-template-v1` text and `summaryId`s for the same vector index (`vectorIndex` comes from core `s3-vectors.indexes`).
- My assessment: the split is technically sound. `facts` is the Spark-owned structured input; `text` is a deterministic, versioned rendering that can be replaced; the spark-engineer followed the binding ruling and escalated. The finding closes when the human records which owner keeps the rendering after Phase 6, for example: "summary-template-v1 is an interim Phase 6 rendering; the rag-engineer owns the production templates from `facts` in Phase 7+8".

**MINOR findings (brief):**
- m1. `Canonical.scala:184-189` and `CanonicalGraphJob.scala:168,186`: the ruling 5d check that member count equals groupSize is only written to the manifest, not raised as a violation or a failure.
  - The two definitions differ by construction. J03 counts a non-suffix RESEARCHER oversize group as one work's clashing mentions (hadoop `JOBS.md:83`), and a WORK oversize group per source. Spark counts every mention or work that carries the key.
  - On such input every mention legitimately matched to the key is refused at tier 1 without any alert. In the current data all 17 rows match. Owner: spark-engineer.
- m2. `TemporalSummaryExportJob.scala:660-667`: the inclusion-limitation text hardcodes "15 vs 12 years" and "about 100k works". It becomes false if `graph.windows` changes. Owner: spark-engineer.
- m3. `JobRun.scala:171` with conf line 7: the builder's `.master("local[*]")` overrides `spark-submit --master yarn`. If the EMR launcher does not also pass `--master yarn` as a job argument, the run is local mode on the primary node. The conf comment "the launcher supplies the master" is misleading. Owner: spark-engineer; infra-engineer to note for the scripts.
- m4. `Inputs.scala:53-62`: a missing required input (author-mentions, works, pairs, ACCEPTED decisions) reads as empty, so a wrong run directory produces a "successful" empty run. Owner: spark-engineer.
- m5. `Inputs.scala:83-104`: the `MatchPath` rule-ID copy can drift silently from entity-resolution. A `tests/contracts` equality test would catch it. Owner: test-engineer (Phase 11, D-10).
- m6. Transitive work clustering puts two DBLP records into one canonical work (5 works; the 21 W_r != T_r and coreness < M_r rows). Ruling 5 applies only rule b to works. This is a policy question for the human and entity-resolution-engineer. It is flagged in the outputs, not hidden.
- m7. `Summaries.scala:362-365`: density is NaN when `summary-min-community-size` = 1 (the setting allows it). Owner: spark-engineer.
- m8. The core `analytics.community-max-iterations` (20) disagrees with the Spark conf value (10). There are two sources for one parameter. Owner: domain-engineer, to align with ruling 8.

OWNERSHIP CHECK:
- `build.sbt` → build-engineer → author build-engineer (work order 1): yes.
- `modules/spark/**` (30 files) → spark-engineer → author spark-engineer (work orders 2 to 5): yes, by path. The artifact-class conflict is MAJOR-3.
- `docs/agent-runs/phase-06/agent-report.md` → orchestrator report assembly; the folder has no `commit.txt`: yes.
- `scratch/spark/**`: no ownership row, gitignored, but rulings 14 and 17 name it as the output root. Not in the diff. Work order 5's manual `rm -r` of two failed-run checkpoint directories is local temporary data inside the ruled checkpoint root. Noted, not a finding.
- No edits to modules/core, other modules, scratch/hadoop, or labels/ appear in the diff or in the code.

FLOOR AND FENCE CHECK: No weakening and no widening.
- `build.sbt` adds only fork, javaOptions and run-classpath settings. It does not touch `markTestRun`, `check`, the module graph, or the version ledger.
- No hooks, permissions, agent files or MCP configuration changed.

EVIDENCE GAPS:
- Full-data determinism was compared on counts and row counts only, not table contents or hashes (work orders 2 and 5 say so). Work order 4 had no second full-data run.
- The code-version strings "a8077c6-dirty" and "84d90b0-dirty" were reused across different code states, including after the work order 5 memory fix. No output run can be tied to the reviewed tree. The runs should be repeated with a commit SHA after commit.
- The `sbt check` per-project counts (151/94/32/70) are not attributed to named projects. The Spark 32 equals the per-suite sum (13+2+3+5+6+3).
- The spec gate item "bounded EMR Spark run" is still open (deferred by ruling 1 to cloud window 1).
- I did not observe the marker timestamp myself; it is as stated by the orchestrator.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, quoted): "Each work order reported `sbt check` passing twice. Final counts: 151 / 94 / 32 / 70 succeeded, 0 failed; marker .claude/.last-test-run dated Nov 5 01:28 local." Work order 5: "sparkAnalytics: 32 succeeded, 0 failed … sbt check run 1: 151 / 94 / 32 / 70 succeeded, 0 failed … run 2: the same counts."

VALIDATION RESULTS:
- The manifest spot-check matches the report: `researcherConflictGroupCheck` has 17 rows and every memberCount equals groupSize (including orcid:0000-0001-5832-0347 at 152/152).
- The load-manifest schemas confirm MAJOR-1 and MAJOR-2.

ASSUMPTIONS:
- I read ruling 4's "an UNRESOLVED researcher mention stays its own canonical researcher" as "a mention with no ACCEPTED link". That is what the implementation does (16,229 UNRESOLVED singletons against 9.5M UNRESOLVED decisions). The human should confirm this reading.
- I treated the work-ID deviation (smallest member ID instead of a hash) and the always-`scr:` researcher IDs as ruling-compliant, because reasons were stated as ruling 6 allows.

RISKS:
- The single-JVM memory behaviour on EMR is unverified.
- Label propagation did not converge at 10 rounds in any window.
- The duplicated rule-ID list can go stale.

MANUAL ACTION REQUIRED: The human architect decides MAJOR-3 and records it in `human-gate.md`, and decides m6.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: Route MAJOR-1 and MAJOR-2 to the spark-engineer (MAJOR-2 possibly through domain-engineer), MAJOR-3 to the human architect, and the minor findings to their owners. Then re-review; human gate on APPROVE.
````

## Review 2: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 6, review 2. I checked repair-01 against rulings 21 to 27 and verified that MAJOR-1, MAJOR-2 (as narrowed by ruling 22), m3 (ruling 25) and the code-version fix (ruling 24) are resolved. MAJOR-3 is closed by ruling 23 and the ruling-26 minors are accepted residual risks. I re-raise none of them, because the repair did not make any of them worse.
VERDICT: APPROVE

All four repair items are resolved, with file and line evidence below. The repair introduced no new CRITICAL or MAJOR finding. Three minor items are listed at the end; none of them blocks approval.

SCOPE REVIEWED:
- HEAD 94cd051 working tree, using the orchestrator's scratchpad review2/ files (git-status.txt, git-diff.txt) and the working-tree files read directly.
- `build.sbt`: the diff is the same fork, javaOptions and run-classpath change already reviewed in review 1. `markTestRun`, `check`, the module graph and the ledger are untouched.
- Read in full under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/: JobRun.scala, SparkSettings.scala, scholarchain-spark.conf, Canonical.scala, CanonicalGraphJob.scala, CoauthorGraph.scala, ResearcherAnalyticsJob.scala, CommunityPathJob.scala, TemporalSummaryExportJob.scala, PipelineJob.scala, SparkSettingsSpec.scala and TestSpark.scala.
- Read in part: Temporal.scala, Clustering.scala, the new tests in CanonicalGraphJobSpec.scala and TemporalSummarySpec.scala, and JOBS.md lines 276-369.
- Cross-checked against core Records.scala (Provenance, SchemaVersions, every record shape the mapping table names) and Enums.scala (`SourceType.DERIVED` exists).
- Spot-checked:
  - load-manifest.json of run-0375535e...: all 30 tables, top-level codeVersion, inputHadoopRunId and matchPolicyVersion, and each table's schema DDL.
  - the pipeline _manifest.json of run-de1f982a...: lineage, codeVersion src-93bc726cd50a, master local[*].
- Nothing under labels/ was read, listed or searched.

RESOLUTION OF REVIEW 1 FINDINGS:

MAJOR-1 (provenance, ruling 21): RESOLVED.
- **Shared builder.** `Ctx.provenance` (Canonical.scala:18-26) builds a struct with exactly the core Provenance field names:
  - schemaVersion = provenance-v1
  - sourceDataset = DERIVED (a valid core SourceType)
  - sourceRecordId
  - snapshot
  - runId = the writing stage's jr.runId
  - policyVersion
  - modelVersion
- **Floor in the writer.** `JobRun.write` (JobRun.scala:107-110) adds schemaVersion and refuses any table whose schema lacks the provenance struct or its fields (`provenanceProblems`, :192-199). Every table goes through `write`.
- **Each table named in MAJOR-1:**
  - components: ResearcherAnalyticsJob.scala:150-153
  - cross-community-edges: CommunityPathJob.scala:385
  - researcher-changes, community-transitions, window-comparison: TemporalSummaryExportJob.scala:344-384
  - mention-map and work-map: Canonical.scala:208-260, which also carry the source `sourceDataset` and `sourceRecordId` taken from the J09 provenance
  - self-pairs: CoauthorGraph.scala:81-92
  - refusals: CanonicalGraphJob.scala:167-175
  - the four violation tables: `Violations.stamped`, Canonical.scala:46-52
  - venues, topics, institutions: their J09 provenance moves to `sourceProvenance`, and `provenance` now names this run (Canonical.scala:31-33)
- **Row-level check at export.** `checkedRowCount` (TemporalSummaryExportJob.scala:685-709) re-reads each table. It refuses the export if any row has a null schemaVersion or sourceRecordId, or a runId or snapshot other than the writing stage's. Every sourceRecordId is built with concat_ws or a non-null key, so it is never null.
- **Lineage.** `lineage` = {inputHadoopRunId, matchPolicyVersion} is recorded in:
  - CanonicalGraphJob.scala:87-90, and propagated at ResearcherAnalyticsJob.scala:66, CommunityPathJob.scala:105, TemporalSummaryExportJob.scala:114 and PipelineJob.scala:65-68;
  - load-manifest.json at the top level (TemporalSummaryExportJob.scala:562-564), with codeVersion per table (:459).
- **Observed in the run output:** load-manifest lines 5-7 read src-93bc726cd50a, run-035e75abfa6798c938cbd534273dbd8d and er-v2. Every table schema I read includes the provenance struct and schemaVersion (for example lines 44, 61, 78, 312, 430, 504, 522, 539).

MAJOR-2 (as ruled in 22): RESOLVED.
- Every written table gets `schemaVersion` = `spark-<path>-v1` (JobRun.scala:181-183, :108), and the version is recorded per table in the stage manifest (:131) and the load manifest (TemporalSummaryExportJob.scala:456). Observed on all 30 entries.
- JOBS.md:320-355 maps each exported table to its nearest core record, listing renamed, split-out, added and missing fields.
  - I checked the rows for researchers, works, authored, coauthor-edges, researcher-metrics, researcher-communities, self-pairs, venues, work-topics and cross-community-edges against Records.scala, and they are accurate. The one imprecision is n1 below.
  - AUTHORED stays per researcher and work, the mention map is the RESOLVES_TO link, and community and bridge fields stay in researcher-communities joined on researcherId and window, all as ruling 22 requires.
- Uncertainty stays visible. The mention map keeps assignedTier, separationReason (REFUSED_TIER1, REFUSED_TIER2, UNRESOLVED, NO_ACCEPTED_LINK; Clustering.scala:139-142), both refusal-rule arrays, and identityConfidence.
- modules/core is unchanged.

Ruling 24 (code version): RESOLVED.
- SparkSettings.scala:73-76 requires `--code-version` with no default and refuses a blank value. Tested at SparkSettingsSpec.scala:30-31.
- The value goes into the run ID (JobRun.scala:37-38, `StableIds.runId(stage, s.codeVersion, ...)`) and the manifest head (:71).
- The orchestrator independently recomputed the hash with the ruled command and got 93bc726cd50a, the same value that appears in both manifests. The runs are therefore tied to the reviewed tree. This closes review 1's run-traceability evidence gap.

m3 / ruling 25 (master): RESOLVED.
- `JobRun.masterFor` (JobRun.scala:204-205) returns None when spark.master is preset. `session` (:209) reads it from `new SparkConf()`, which loads the spark.* system properties that spark-submit sets, and only otherwise applies the setting (the flag, or the conf fallback local[*]).
- The misleading conf comment is fixed (scholarchain-spark.conf:6-8).
- TestSpark.scala:12 sets local[2] explicitly.
- The fallback test is SparkSettingsSpec.scala:39-43.
- Manifests now record `spark.sparkContext.master`, the master actually used.

MAJOR-3 and the ruling-26 minors: not re-raised. I found no worsening:
- m1, m2 (InclusionLimitation text, TemporalSummaryExportJob.scala:714-721), m4 and m7 are unchanged;
- the work clustering of m6 is unchanged, and the row counts and full-row hashes were reported equal.

FINDINGS (new, introduced or exposed by the repair; none CRITICAL or MAJOR):
- n1, MINOR, modules/spark/JOBS.md:333.
  - The mapping says core `AuthorshipRecord.matchConfidence` is available "per mention" as the mention map's `identityConfidence`. That value is per cluster: it is the minimum ACCEPTED-edge confidence inside the mention's final cluster, defaulting to 1.0 for singletons (Clustering.scala:118-123, :155).
  - Failure input: a Phase 7 builder of AuthorshipRecord takes the doc at its word. Every mention of a tier-2 cluster then gets the weakest edge's confidence, and UNRESOLVED singletons get 1.0.
  - Impact: the documentation is imprecise, and confidence semantics could be misread downstream. No data is wrong.
  - Owner: spark-engineer, to reword the line (for example "cluster-level lower bound; singletons 1.0").
- n2, MINOR, scope question for the human: JobRun.scala:152 and :214.
  - `JobRun.finish` deletes the run's own `scratch/spark/checkpoints/<runId>`, and `spark.cleaner.referenceTracking.cleanCheckpoints` removes checkpoint files during the run. The repair-01 run did both after ruling 27, which says "No agent deletes anything there."
  - This behaviour is pre-existing and the spark-engineer disclosed it (repair-01 RISKS). It touches only the run's own transient files, not run outputs.
  - Owner: human architect, to state in human-gate.md whether ruling 27 excludes a run's own transient checkpoints. If not, the spark-engineer changes the code.
- n3, MINOR, TemporalSummaryExportJob.scala:694-697.
  - When the recorded rowCount is 0, the export check does not re-read the table files. It validates the recorded DDL instead, so `verifiedRowCount` for empty tables is the recorded value, not an observed one.
  - Failure input: a table recorded as empty whose directory later gains part files. Run directories are write-once (`SaveMode.ErrorIfExists`, no overwrite), so the risk is low.
  - Owner: spark-engineer (optional).

OWNERSHIP CHECK:
- `build.sbt` → build-engineer → author build-engineer (work order 1; not touched in repair-01): yes.
- `modules/spark/**` (JOBS.md, the conf file, 21 main sources, 8 test sources) → spark-engineer → author spark-engineer (work orders 2 to 5, repair-01): yes.
- `docs/agent-runs/phase-06/{agent-report.md, review-report.md, repair-01.md}` → orchestrator report assembly. The folder has no commit.txt, and human-gate.md does not exist yet: yes.
- No change to modules/core, other modules, scratch/hadoop, or labels/. scratch/spark/ outputs are gitignored and are not in the diff.
- The Compare.java comparison program sits in the session scratchpad, outside the repository.

FLOOR AND FENCE CHECK: no weakening and no widening. No hooks, permission rules, agent files, commands or MCP configuration changed. `markTestRun` and `check` are untouched. The repair adds a deterministic refusal (JobRun.write, export checkedRowCount); it removes none.

EVIDENCE GAPS:
- The sbt check counts (151 / 94 / 35 / 70, twice) are the spark-engineer's report only; the orchestrator did not rerun sbt check.
  - Indirect evidence: the orchestrator observed a marker at 11:39:08 with no Scala or sbt source newer than it, and markTestRun writes the marker only after tests pass.
  - The Spark count of 35 matches the test declarations I counted (14 + 3 + 6 + 4 + 5 + 3).
- The WO5-versus-repair comparison (equal row counts, xxhash64 checksums, full-row hashes) is reported only. I spot-checked row counts in the load manifest; I did not observe the Compare.java output.
- The masterFor rule is unit-tested as a pure function only. Its behaviour under real spark-submit or YARN is unverified until cloud window 1, as the spark-engineer states.
- Still open from Phase 6, by ruling 1: the bounded EMR Spark run (cloud window 1).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, quoted):
- From the orchestrator: "`ls -l .claude/.last-test-run` → 2026-11-05 11:39:08; `find modules/spark build.sbt -newer .claude/.last-test-run -type f \( -name '*.scala' -o -name '*.sbt' \)` → no output."
- From repair-01: "sparkAnalytics: 35 succeeded, 0 failed … sbt check run 1: 151 / 94 / 35 / 70 succeeded, 0 failed … run 2: the same counts, marker written."

VALIDATION RESULTS:
- Orchestrator-observed: the assembly jar has 0 org/apache/spark and 0 software/amazon/awssdk entries, and the source hash is 93bc726cd50a, matching the run's code version.
- My spot-checks: the load manifest lists 30 tables, each with schemaVersion, codeVersion src-93bc726cd50a, a provenance struct in its schema DDL, and verifiedRowCount equal to rowCount. The pipeline manifest has lineage {run-035e75abfa6798c938cbd534273dbd8d, er-v2}, attempt 1 and master local[*].

ASSUMPTIONS:
- I accept the reading that "runId (the Phase 6 run)" on a row means the stage run that wrote it. Each stage manifest is self-sufficient (codeVersion, lineage, input run), and the load manifest's `chain` names all four stage runs.
- For DERIVED aggregate rows (window-summary, components, path-length-estimate), I read core's convention (Records.scala:43-44: sourceRecordId is the record's own stable ID, and the run manifest lists the inputs) as meeting ruling 21's source-pointer requirement.

RISKS:
- Behaviour under spark-submit or YARN, and the S3A free-disk check, are unverified until cloud window 1.
- All table schema versions come from one generation constant, so a future column change must bump the affected table explicitly (disclosed by the spark-engineer).

MANUAL ACTION REQUIRED: The human architect decides n2 (whether ruling 27 covers a run's own transient checkpoints) and records it in human-gate.md.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: route n1 and n3 to spark-engineer as optional minors and n2 to the human architect; human gate on APPROVE.

Relevant paths:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/JobRun.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/Canonical.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/TemporalSummaryExportJob.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/SparkSettings.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/JOBS.md
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-0375535e0174b7330b18a78988c07211/export/load-manifest.json
````
