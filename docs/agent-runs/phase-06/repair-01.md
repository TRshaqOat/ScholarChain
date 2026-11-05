# Phase 06 repair reports

## Repair 1: spark-engineer

````text
STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 6, repair-01. MAJOR-1 provenance (ruling 21), MAJOR-2 schemaVersion columns and the JOBS.md mapping (ruling 22), required code version (ruling 24), master fallback (ruling 25), tests, one fresh end-to-end run compared with the work order 5 run, under rulings 1 to 27.

All four findings are fixed. The fresh run of jobs 1 to 11 finished with exit 0. Compared with the work order 5 run, all 30 tables have equal row counts, every requested checksum is equal per window, and every pre-existing column is identical. Only stamping columns were added. `sbt check` passed twice: 151 / 94 / 32→35 / 70, i.e. 151 / 94 / 35 / 70 succeeded, 0 failed.

WHAT CHANGED PER FINDING (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/):

- **MAJOR-1, provenance (ruling 21).** Every table a stage writes, so all 30 in load-manifest.json, now carries a `provenance` struct with the core Provenance field names. Its `runId` is the Phase 6 stage run, `snapshot` is 2026-10-03, `sourceDataset` is DERIVED, `sourceRecordId` is the row's own key, and `policyVersion` is the canonical or graph policy or the template version.
  - **Struct added to 13 tables:** components, cross-community-edges, researcher-changes, community-transitions, window-comparison, mention-map, work-map, self-pairs, researcher-refusals, work-refusals, and the four violation tables.
  - **Source pointers:** mention-map and work-map gained `sourceRecordId`; they already carried `sourceDataset`. Self-pairs keep authorA, authorB and workId. Refusal provenance is keyed on entityType|tier|sampleMember.
  - **Pass-through tables:** venues, topics and institutions keep their J09 provenance as a new `sourceProvenance` column, and `provenance` now names this run.
  - **Manifests:**
    - Every stage manifest and the pipeline manifest gain `lineage` = {inputHadoopRunId run-035e75abfa6798c938cbd534273dbd8d, matchPolicyVersion er-v2}.
    - load-manifest.json gains top-level `codeVersion`, `inputHadoopRunId` and `matchPolicyVersion`, plus `schemaVersion` and `codeVersion` per table.
  - **Files:**
    - src/main/scala/scholarchain/spark/Canonical.scala: `Ctx.restamp`, `Violations.stamped`, mentionMap, workMap
    - CanonicalGraphJob.scala, CoauthorGraph.scala, ResearcherAnalyticsJob.scala, CommunityPathJob.scala, TemporalSummaryExportJob.scala, PipelineJob.scala
- **MAJOR-2, schema versions (ruling 22).**
  - `JobRun.write` sets the `schemaVersion` column on every table to `spark-<table path with / as ->-v1` (`JobRun.schemaVersionOf`) and records it in the stage manifest. The export copies it into the load manifest per table.
  - One deliberate value change: researcher-metrics' `schemaVersion` changes from core `researcher-metrics-v1` to `spark-analytics-researcher-metrics-v1`. The table does not match the core record exactly, so claiming the core version would be wrong.
  - JOBS.md has a new section, "Provenance, schema versions, and the core records". It maps every exported table to its nearest core record, with the renamed, split-out, added and missing fields and each table's `sourceRecordId` key. AUTHORED stays per researcher and work, the mention map is the per-mention RESOLVES_TO link, and community and bridge fields stay in researcher-communities. The mention map still records tier, separationReason and refusal rules, so how each mention was resolved stays visible.
- **Export check (item 5).**
  - `TemporalSummaryExportJob.checkedRowCount` re-reads every table. The export is refused (no load manifest, no `_manifest.json`) if a table lacks `schemaVersion` or a provenance struct with the core fields, or if any row lacks schemaVersion or provenance.sourceRecordId, or names another run ID or snapshot.
  - Empty tables are checked against the schema DDL their manifest recorded.
  - `JobRun.write` also refuses to write a table that has no provenance (`JobRun.provenanceProblems`).
- **Ruling 24, code version.** `--code-version` was already a required flag with no default and already went into the run ID and manifest. It now also rejects a blank value (SparkSettings.scala). JOBS.md documents the src-<hash> command.
- **Ruling 25 / m3, master.** `JobRun.masterFor(preset, setting)` sets a master only when `spark.master` is not already set (as read by `new SparkConf()`). spark-submit's `--master` wins, then the job's `--master` flag, then the conf fallback `local[*]`. Manifests now record `spark.sparkContext.master`, the master actually used. The conf comment is fixed (src/main/resources/scholarchain-spark.conf). TestSpark already sets `local[2]` explicitly.

FILES CREATED: none in the repository. The comparison program is outside it: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b1135956-3d44-4589-a41e-14ab42f16273/scratchpad/cmp/Compare.java
FILES MODIFIED (under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/):
- JOBS.md
- src/main/resources/scholarchain-spark.conf
- src/main/scala/scholarchain/spark/: Canonical.scala, CanonicalGraphJob.scala, CoauthorGraph.scala, CommunityPathJob.scala, JobRun.scala, PipelineJob.scala, ResearcherAnalyticsJob.scala, SparkSettings.scala, TemporalSummaryExportJob.scala
- src/test/scala/scholarchain/spark/: CanonicalGraphJobSpec.scala, SparkSettingsSpec.scala, TemporalSummarySpec.scala
FILES DELETED: none. The run's own code removed its checkpoint subdirectories at the end of each stage (existing JobRun.finish behaviour). scratch/spark/checkpoints is empty.

STAGE DAGS AND SHUFFLE BOUNDARIES: unchanged; no new joins.
- The new columns are literals and struct projections.
- The export check adds one aggregate per table, in place of the earlier count.
- Spark stages: jobs 1-2: 685; jobs 3, 4, 8: 3,750; jobs 5-7: 538 (WO5: 537); jobs 9-11: 398 (WO5: 395).
PARTITION COUNTS AND SKEW: local[*], 10 cores. spark.sql.shuffle.partitions = 40, set explicitly. Driver heap 6,442,450,944 B (6g default, the same as the successful WO5 attempt) against hw.memsize 17,179,869,184 B. Hub researchers and giant teams are unchanged.

GRAPH COUNTS (temporal/window-summary of the new run; identical to WO5):

| Window | Publications | Researchers (graph) | Edges | Components | Largest | Fraction | Singletons | Communities |
|---|---|---|---|---|---|---|---|---|
| all-years | 110,257 | 191,318 | 787,726 | 16,091 | 141,367 | 0.7389 | 4,849 | 38,034 |
| 2000-2014 | 45,522 | 66,350 | 217,957 | 7,709 | 46,062 | 0.6942 | 3,119 | 15,312 |
| 2015-2026 | 64,735 | 139,432 | 585,071 | 10,746 | 98,812 | 0.7087 | 1,798 | 26,394 |

ALGORITHMS: unchanged.
METRIC DISTRIBUTIONS: unchanged. All pre-existing columns are equal by hash, see below.
TEMPORAL WINDOWS: all-years 2000-2026, 2000-2014, 2015-2026.
INTEGRITY VIOLATIONS: jobs 1-2: 0. Jobs 3, 4, 8: 21 (CORENESS_BELOW_MAX_TEAM 9, WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES 12, the same as WO5). Jobs 5-7: 0. Jobs 9-11: 0. The export provenance check passed for all 30 tables.

OUTPUT PATHS AND LOAD MANIFESTS (under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/):
- Pipeline: run=run-de1f982ae8f83848b4f869d8718f5e3d/_manifest.json (attempt 1, codeVersion src-93bc726cd50a, 2.1M)
- Stage runs, all attempt 1:
  - jobs 1-2: run-10ceed31b3f602bb998eae7c00929ae9 (446M)
  - jobs 3, 4, 8: run-d7865ad3f5f8195546c1dab817328ee5 (43M)
  - jobs 5-7: run-83b496169099a614ec519133077a1993 (100M)
  - jobs 9-11: run-0375535e0174b7330b18a78988c07211 (160M)
- Load manifest: run=run-0375535e0174b7330b18a78988c07211/export/load-manifest.json. It has 30 tables; by store: neptune 14, dynamodb 13, s3vectors 1, s3 26.

COMPARISON WITH WO5 (pipeline run-eb4284f015763055514b0dacf41132b4, load manifest run-be3b1cfe152896b20a54933ffb5af9e6). Method: Compare.java, sum of xxhash64 cast to decimal(38,0).

- **Row counts:** all 30 tables are EQUAL, and verifiedRowCount equals rowCount for every one. Examples: mention-map 1,273,265; researchers 259,057; works 113,205; authored 535,898; coauthor-edges 1,590,754; components 34,546; communities 79,740; researcher-metrics 397,100; summaries 107,979.
- **Key checksums:** all EQUAL. Each cell is rows:checksum, the same in both runs.

  | Table | Window | Rows:checksum |
  |---|---|---|
  | researchers | (not windowed) | 259057:880236762969725701092 |
  | works | (not windowed) | 113205:-498569757543868983552 |
  | authored | (not windowed) | 535898:-2713420181764390374833 |
  | coauthor-edges | 2000-2014 | 217957:-1261709980253299908541 |
  | coauthor-edges | 2015-2026 | 585071:-2544632313841553697439 |
  | coauthor-edges | all-years | 787726:-7860018896835964090324 |
  | components | 2000-2014 | 7709:-277502345376701538274 |
  | components | 2015-2026 | 10746:-533397756415352290294 |
  | components | all-years | 16091:165047201726648749426 |
  | communities | 2000-2014 | 15312:-1004763806047112284618 |
  | communities | 2015-2026 | 26394:1202674015153059034068 |
  | communities | all-years | 38034:-749256404652263169751 |
  | researcher-metrics (researchers per window) | 2000-2014 | 66350:-669996416199280822758 |
  | researcher-metrics (researchers per window) | 2015-2026 | 139432:3317850214190120888362 |
  | researcher-metrics (researchers per window) | all-years | 191318:-301879110604853608116 |

  Key columns used:
  - researchers: id, name, mentionCount, tier, reason, orcid
  - works: id, year, memberWorkIds
  - authored: researcherId, workId, mentionIds, position
  - edges: all graph columns
  - components: id, root, size
  - communities: id, size, internal and cross edges
- **Full-row check:** a hash over every pre-existing column except provenance and schemaVersion is EQUAL for all 27 non-empty tables. For venues, topics and institutions, the old `provenance` hash equals the new `sourceProvenance` hash.
- **Added columns only:**
  - schemaVersion on 29 tables;
  - provenance on the 13 tables listed above;
  - sourceRecordId on mention-map and work-map;
  - sourceProvenance on venues, topics and institutions.
- **Differences beyond added columns (explained):**
  - `provenance` values on venues, topics and institutions now name the Spark run; the J09 values moved to sourceProvenance.
  - researcher-metrics `schemaVersion` changes from the core value to the Spark one.
  - `provenance.runId` differs everywhere, because the run IDs are new.

COMMANDS RUN:
- Reads: cat, sed, grep, find, wc, ls, du; `python3 -I` to read manifests and to make scripted text edits under modules/spark.
- Environment: `sysctl -n hw.memsize hw.ncpu`; `df -k .` before and after.
- `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/Test/compile`: success.
- `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/test`: 35 succeeded, 0 failed.
- Code version, computed after the last src/main and build.sbt edit:
  `find modules/spark/src/main build.sbt -type f | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-12` → `93bc726cd50a`, so the code version is `src-93bc726cd50a`.
- `/usr/bin/time -p sbt -batch "sparkAnalytics/runMain scholarchain.spark.PipelineJob --input scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d --code-version src-93bc726cd50a --shuffle-partitions 40"`: exit 0, `[success] Total time: 997 s`, real 998.84 s. Manifest wall time 994.5 s. Stage walls 182.8 / 455.3 / 326.4 / 29.5 s; WO5 was 853 s in total.
- `sbt -batch "export sparkAnalytics/Compile/fullClasspath"`, then `java ... Compare.java` (Java single-file program, run from the scratchpad): exit 0.
- `sbt -batch packageJobs`: success. In sparkAnalytics-assembly-0.1.0.jar (3,983,491 B), `unzip -l` finds 0 org/apache/spark entries and 0 software/amazon/awssdk entries.
- `sbt -batch check`, twice.
- No git, aws or network commands. Nothing under labels/ was read or listed. Nothing under scratch/hadoop/ was modified. Nothing under scratch/spark/ was deleted by me.

TEST RESULTS:
- sparkAnalytics: 35 succeeded, 0 failed (32 before plus 3 new tests):
  - CanonicalGraphJobSpec "every table carries schemaVersion and this run's provenance; maps point at sources". All 15 tables have the columns, and every row has schemaVersion, runId, snapshot and a non-null sourceRecordId. Mention dblp:s1/W1#1 has sourceRecordId dblp:s1/W1#1. Work openalex:W2001 maps to (OPENALEX, W2001). Venue provenance.runId is this run, and sourceProvenance.runId is the fixture Hadoop run.
  - TemporalSummarySpec "the export refuses a table without schemaVersion and provenance, or with another run's". Five tables are refused: a bare table, a table with no schemaVersion, a table naming another run, an empty table without the columns, and the same empty case checked against its recorded schema. A good table returns count 1, and a good empty table returns 0.
  - SparkSettingsSpec "the master is set only when spark.master is not already set". Also, a blank --code-version is refused.
  - The pipeline test now also asserts the per-table schemaVersion and codeVersion and the lineage fields.
- sbt check run 1: 151 / 94 / 35 / 70 succeeded, 0 failed, "All tests passed." four times, markTestRun wrote .claude/.last-test-run, `[success] Total time: 259 s`.
- sbt check run 2: the same counts, marker written, `[success] Total time: 259 s`. The marker is dated 2026-11-05 11:39:08, after every source edit.

VALIDATION RESULTS:
- Free disk:
  - `df` before the run: 28,346,348 KiB.
  - Pipeline manifest: 29,025,488,896 B before, 25,763,520,512 B after.
  - `df` after the run: 26,641,248 KiB; after sbt check: 26,573,036 KiB.
  - The run never came near the 5 GiB floor.
- Run traceability: the new run IDs come from src-93bc726cd50a, so they are tied to this tree.

ASSUMPTIONS:
- The "runId (the Phase 6 run)" on a row is the stage run that wrote it. The pipeline manifest maps stage runs to the pipeline run.
- Spark tables use their own schema-version namespace rather than the core strings, per ruling 22.
- schemaVersion is set centrally by JobRun.write rather than per builder.
- "Refused" means the export fails before writing the load manifest. It does not silently drop the table.
- The code-version flag already existed as required with no default, so ruling 24 needed only the blank check and documentation.

RISKS:
- The export check must re-read every table, about 5M rows in total, which added a few seconds.
- The stage wall time was about 140 s longer than WO5 (5-7: 326 vs 245 s). This is likely load variance on the laptop; the DAG is unchanged apart from the export aggregate and 1 extra stage in jobs 5-7.
- Every schema version is "v1" from one generation constant. A future change to one table must bump that table explicitly.
- JobRun.finish deletes each run's own checkpoint subdirectory, which is existing code behaviour. Ruling 27 may need to say whether that is allowed.
- The masterFor rule is unverified on EMR until cloud window 1.

MANUAL ACTION REQUIRED: none. Optional: the human may remove superseded run directories under scratch/spark/ after the commit (ruling 27).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer APPROVE (review 2)

Not checked: the master fallback under a real spark-submit or YARN; mutation testing of the new tests.
````
