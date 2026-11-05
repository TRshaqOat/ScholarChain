# Phase 06: temporal coauthorship graph and Spark analytics

Session plan: session 1 (overnight, started 2026-11-04) runs work orders 1 to 5, then code review 1. Each work order report is appended verbatim to agent-report.md under "## Work order N: <agent>", and the review to review-report.md under "## Review 1: code-reviewer", each wrapped in a ````text fence.

## Human rulings before work order 1 (binding)

1. Scope: local Spark only (local[*]) on this laptop. The spec's bounded EMR Spark run is deferred to cloud window 1 (2026-11-12) and stays an open gate item. Nothing runs on AWS in Phase 6.
2. Input: the Phase 5 R2 run, read-only: scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d/. modules/hadoop/JOBS.md describes every output layout. Spark consumes Hadoop outputs (the J09 exports, plus the J03+J04 suffix-conflicts/ and oversize-conflicts/ outputs for conflict groups) and never reparses raw data. Never modify, move, or delete anything under scratch/hadoop/.
3. Graph algorithms use DataFrame iteration only (D-10). No GraphFrames and no new library. Spark 4.0.2 via CrossVersion.for3Use2_13 (D-01, D-02). Prefer DataFrame and Java APIs over typed Dataset encoders.
4. Canonical clusters, for researchers and works, are built from ACCEPTED decisions only. UNRESOLVED, REJECTED, and CONFLICT decisions never link records. An UNRESOLVED researcher mention stays its own canonical researcher.
5. Refusal rules (Phase 5 rulings). A researcher cluster is refused if it would contain:
   a. two different valid ORCIDs;
   b. two records with a CONFLICT decision between them, including J03+J04 conflict groups;
   c. two mentions whose DBLP homonym suffixes disagree;
   d. two or more members of one group listed in J03+J04 suffix-conflicts/ or oversize-conflicts/. A group's members are the researcher mentions carrying that identifier; check that the member count equals groupSize.
   For work clusters, rule b applies, including DOI conflict groups and same-source shared-DOI conflicts. Count refusals by rule (a cluster can break several).
6. Clustering algorithm (the default; deviate only with a stated reason in the report). Two tiers:
   - Tier 1: connected components over EXACT_ID and DETERMINISTIC ACCEPTED edges.
   - Tier 2: connected components over all ACCEPTED edges, which adds PROBABILISTIC.
   - A tier-2 cluster that breaks a refusal rule is refused, and its members fall back to their tier-1 clusters.
   - A tier-1 cluster that breaks a rule is refused, and its members fall back to singletons.
   - Canonical IDs are deterministic (a hash of the smallest member source record ID) and stable across reruns on the same input.
7. Time windows: all, 2000-2014, 2015-2026 (D-10). Every time-dependent output carries a window column.
8. Communities: label propagation with a deterministic tie-break (smallest label), a configurable iteration cap (default 10), and recorded convergence (labels changed per iteration).
9. Bridge measures (D-10): cross-community degree, the number of distinct communities connected, and the participation coefficient. Define each and state its limitations.
10. Dense-subgraph measure: k-core coreness by iterative peeling.
11. Paths: bounded breadth-first search (maximum depth 6) for researcher pairs read from a file.
    - The 30 benchmark questions are not written yet, so generate a seeded sample of 50 pairs from the largest component now. The benchmark list is added later by rerunning the path job only.
    - Estimate average path length or effective diameter on the largest component from a seeded sample of BFS sources (default 20).
12. Semantic-context summaries are template text built from computed metrics and source records only. No LLM call.
13. Export: partitioned Parquet tables (nodes, edges, metrics, communities, components, path evidence, semantic summaries), plus a JSON manifest listing each table's path, schema, row count, and intended target store. Store-specific formats (Neptune bulk-load CSV, DynamoDB items, S3 Vectors) belong to Phase 7+8.
14. All paths go through the Hadoop FileSystem API, so they are s3a-compatible.
    - Output root: scratch/spark/, with the snapshot and run ID in the path.
    - Run IDs are deterministic from inputs, code version, and configuration. A rerun into an existing path increments the attempt.
    - Every run writes a run manifest.
15. Spark configuration lives in modules/spark (resources and command-line flags). Do not edit modules/core or any other module. If a change outside your owned paths seems unavoidable, report BLOCKED-ON with the owner instead of editing.
16. Never collect the graph or any large table to the driver. Use collect or take only for bounded results (counts, top-N, fixtures).
17. Resources:
    - Check `sysctl -n hw.memsize` and keep the driver heap to at most half of RAM. Use local[*], set spark.sql.shuffle.partitions explicitly, and report it.
    - The checkpoint directory for iterative algorithms is scratch/spark/checkpoints/.
    - Check free disk before each full run. If free space would drop below 5 GiB, stop and report BLOCKED-ON with MANUAL ACTION REQUIRED.
18. Tests: hand-checkable fixtures in modules/spark/src/test, with expected values worked out by hand (not computed by the code under test) and one shared local SparkSession. Keep the added sbt check time modest. test-engineer does not run in Phase 6 (D-10).
19. Report publications, canonical researchers, and coauthor edges for each window (instructor requirement).
20. No agent reads, writes, or lists anything under labels/. No aws commands, no network downloads, no git.

## Work order 1: build-engineer

Make the Spark project (sbt project sparkAnalytics, directory modules/spark; confirm both names from build.sbt) ready for local Phase 6 work. First read build.sbt and project/Dependencies.scala and report what already exists. Change only what is missing:

1. Spark SQL 4.0.2 (only the Spark artifacts DataFrames need) via CrossVersion.for3Use2_13, in Provided scope for packaging. No mixed \_2.13/\_3 artifacts; keep or extend the existing mixed-classpath check.
2. Tests can create a local SparkSession on Java 17: forked tests, the --add-opens JVM options Spark 4 needs, and serial test execution for this project.
3. Local runs: `sbt "sparkAnalytics/runMain <main class> <args>"` works with Provided Spark on the run classpath, in a forked JVM whose heap is configurable (default 6g).
4. A Spark assembly for the later EMR run that excludes Spark, Hadoop, and the AWS SDK, reachable from packageJobs or a new packageSpark alias. Report `unzip -Z1 <jar> | grep -c org/apache/spark` and the same for software/amazon/awssdk; both must be 0.
5. No new library beyond Spark artifacts at the already-pinned version (D-09).
   Run sbt check twice. If nothing needs changing, say so and report COMPLETE.

## Work order 2: spark-engineer (jobs 1 and 2: canonical entities and the coauthorship graph)

Implement spec Phase 6 jobs 1 and 2 under the rulings above.

1. Inputs: read the R2 run as described in modules/hadoop/JOBS.md. List every input path and the record count read from it.
2. Canonical builder:
   - Researcher and work clustering per rulings 4 to 6.
   - Researcher, Work, AUTHORED, and Venue tables, plus Topic and Institution where the inputs carry them (say which are absent).
   - A mention-to-canonical mapping table recording why a mention stayed separate: UNRESOLVED, refused at tier 2, or refused at tier 1.
   - Validate that every AUTHORED edge points to existing nodes. Count violations and write them to an integrity-violations output; never drop them silently.
3. Graph builder:
   - Map Hadoop pair candidates onto canonical researcher IDs.
   - Drop self-pairs created by identity resolution, and count them.
   - Canonicalize pair order, and aggregate duplicates.
   - Store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, and the supporting work IDs (or a stable pointer when the list is long).
   - Build the graph for each window (ruling 7).
4. Hand-checkable fixtures:
   - a chain A-B-C where A and C carry different ORCIDs (refused at tier 2, falls back to tier 1);
   - a CONFLICT decision inside a component;
   - DBLP suffixes 0001 and 0002 in one component;
   - a suffix-conflict group with three members plus an unsuffixed mention;
   - an UNRESOLVED mention that stays separate;
   - a self-pair created by a merge;
   - duplicate pairs across two works in different years;
   - pair-order canonicalization;
   - a work cluster with a DOI conflict.
5. Full local run on the input. Report:
   - refusal counts by rule and tier;
   - the cluster size distribution and the 10 largest clusters (size and one representative name);
   - counts of canonical researchers, works, AUTHORED edges, and coauthor edges per window;
   - self-pairs dropped and integrity violations;
   - stage DAG summary, partition counts, and shuffle read and write;
   - wall time, output paths, and free disk before and after.
     Run sbt check twice.

## Work order 3: spark-engineer (jobs 3, 4 and 8: researcher statistics, components, k-core)

Read the work order 2 outputs (paths from its report).

1. Job 3: P_r, C_r, T_r, A_r, M_r, and W_r per researcher and window, with the work IDs responsible for M_r. Define each measure in modules/spark/JOBS.md.
2. Job 4: connected components by iterative min-label propagation over DataFrames, with checkpointing. Store component ID and size per window, and report the fraction of researchers in the largest component and the singleton count.
3. Job 8: k-core coreness by iterative peeling, per window.
4. Fixtures:
   - a five-node graph with P/C/T/A/M/W values worked out by hand;
   - two components plus an isolated node;
   - a 4-clique attached to a path, with known coreness.
5. Full local run. Report:
   - metric distributions (min, median, p90, p99, max);
   - component counts and sizes per window;
   - the coreness distribution and iterations to converge;
   - partition and shuffle metrics, wall time, and output paths.
     Run sbt check twice.

## Work order 4: spark-engineer (jobs 5, 6 and 7: paths, communities, bridges)

Read the work order 2 and 3 outputs.

1. Job 5:
   - Bounded BFS path evidence (ruling 11) for the selected pairs, storing each path with the supporting work IDs for every hop.
   - The sampled average path length or effective diameter estimate, with its sampling parameters.
2. Job 6: label propagation communities (ruling 8). Store community membership, community size, and cross-community edges per window.
3. Job 7: the three bridge measures (ruling 9) per researcher and window, with written definitions and limitations in modules/spark/JOBS.md.
4. Fixtures:
   - a path graph with an unreachable pair and a pair beyond the depth limit;
   - two cliques joined through one bridge researcher (the communities separate, and the bridge researcher has the highest participation coefficient and cross-community degree);
   - a tie-break case showing deterministic labels.
5. Full local run. Report:
   - path-test results (found, unreachable, beyond depth) and path length distribution;
   - community counts and the size distribution per window, plus convergence;
   - bridge measure distributions;
   - partition and shuffle metrics, wall time, and output paths.
     Run sbt check twice.

## Work order 5: spark-engineer (jobs 9, 10 and 11: temporal analysis, summaries, export, end-to-end run)

1. Job 9: compare the windows for distinct coauthors, team sizes, component membership, community membership, and bridge behavior. State the limitation that the subset's inclusion rules may differ across windows.
2. Job 10: template summaries (ruling 12):
   - researcher profiles for researchers with at least 2 publications in window "all";
   - communities of size 3 or more;
   - paths for the selected pairs;
   - topics where available.
     Report the count of each.
3. Job 11: Parquet export and the JSON manifest (ruling 13).
4. One end-to-end driver that runs jobs 1 to 11 in order with a fresh run ID and writes the run manifest. Do a full local run, and check that its counts equal the staged runs from work orders 2 to 4 (determinism).
5. Complete modules/spark/JOBS.md: every job's inputs and outputs, partitioning and shuffle boundaries, checkpointing, measure and bridge definitions with limitations, how to run locally, and the s3a paths for the later EMR run.
6. Report everything the spec lists: stage DAGs, partition counts, shuffle metrics, graph counts, component counts, community counts, metric distributions, path-test results, temporal windows, rejected integrity violations, and output paths, plus publications, researchers, and edges per window.
   Run sbt check twice.

## Code review: code-reviewer

Review the complete Phase 6 change set against work orders 1 to 5, the human rulings above, the spec's Phase 6 section (jobs and gate), and D-01, D-02, D-09, and D-10. The orchestrator supplies the change set: git status, git diff for tracked files, and every untracked file under modules/spark plus any build file, written to its scratchpad (not a phase folder).
Check especially:

- refusal rules a to d are enforced on every path into a cluster, including tier 1;
- no large table is collected to the driver;
- algorithms are DataFrame-only, with checkpointing where iterative;
- outputs are deterministic;
- window columns are present;
- fixtures are genuinely hand-checkable;
- bridge definitions and limitations are written down;
- no edits outside owned paths, and no reads of labels/.
  Verify each candidate finding against the files. Give critical and major findings in full (severity, file and line, failure input, impact, owning agent), and minor findings briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings after review 1 (binding)

Review 1 returned REQUEST_CHANGES with three major findings (MAJOR-1, MAJOR-2, MAJOR-3) and minors m1 to m8. There is one repair round (repair-01), then review 2. The rulings before work order 1 still apply.

21. MAJOR-1 (provenance): accepted; fix it in repair-01. The spec requires every graph element to carry enough provenance to locate the source dataset record and the pipeline run that produced it. Every table that Spark writes under export/ or lists in load-manifest.json, and every table named in MAJOR-1 (components, cross-community edges, the temporal tables, the mention and work maps, self-pairs, refusals), carries on every row:
   - runId (the Phase 6 run) and snapshot;
   - schemaVersion (ruling 22);
   - a source pointer: either the source record IDs directly, or canonical IDs that resolve to source records through the mention and work maps, which themselves carry source and sourceRecordId.
   The run manifest and load-manifest.json also record the input Hadoop run ID (run-035e75abfa6798c938cbd534273dbd8d), the entity-resolution policy version, and the code version (ruling 24). Use the field names of the Provenance record in modules/core where they fit. Read modules/core only; do not edit it.

22. MAJOR-2 (core schemas): closed by ruling, plus a small fix in repair-01. The spec allows the exact fields to differ, but identifiers, versioning, provenance, uncertainty, and time windows are mandatory. The Phase 6 Parquet tables are Spark analytic tables, not serialized core records. There is no change to modules/core and no domain-engineer work in Phase 6. In repair-01:
   - every exported table carries a schemaVersion column, and load-manifest.json records it per table;
   - modules/spark/JOBS.md gets a section that maps each exported table to its nearest core record and lists the fields that are renamed, split out, added, or missing. AUTHORED stays per researcher and work; the per-mention link is the mention map (the RESOLVES_TO relationship). Community and bridge fields stay in their own tables and join to researcher metrics on researcher ID and window;
   - uncertainty stays visible: the mention map keeps how each mention was resolved (tier, UNRESOLVED, or the refusal reason).
   Store-specific shapes that follow the core records are built in Phase 7+8 from these tables (ruling 13).

23. MAJOR-3 (template ownership): closed by ruling, with no code change. Ruling 12 stands for Phase 6: the template summaries are interim semantic-context text written by spark-engineer. From Phase 7+8 on, rag-engineer owns the rendering templates (spec, Phase 7+8). It either adopts the Phase 6 templates by reference or defines its own in modules/rag that render from the Phase 6 Parquet tables. The Spark summary job stays in place until then. This ruling is recorded in human-gate.md. Review 2 does not re-raise this finding.

24. Code version (the reviewer's run-traceability note): fix it in repair-01. Replace the constant code-version label with a required input (a --code-version flag, or a config key that the flag overrides) with no default. The driver refuses to start without it. The value goes into the run ID and the run manifest. For the repair-01 run, pass src-<12 hex>, computed with:
   find modules/spark/src/main build.sbt -type f | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-12
   Report the exact command and the value. On EMR the human passes the git commit SHA. Tests pass a fixed value.

25. m3 (hardcoded local[*]): fix it in repair-01, because it would override YARN on EMR. The code sets a master only when spark.master is not already set (spark-submit or --master sets it). A local runMain falls back to local[*]. The shared test session sets its master explicitly. Add a small test of the fallback rule.

26. Residual risks, not repaired in Phase 6 (they go to human-gate.md and the Phase 11 backlog):
   - m1, m2, m4, and m7;
   - m5, a contract test for the rule-ID list (test-engineer, Phase 11);
   - m6, five canonical works that each merge two DBLP records (the 21 integrity-violation rows stay recorded);
   - m8, the core default of 20 community iterations (domain-engineer, Phase 11; Spark keeps 10);
   - label propagation does not converge within 10 rounds (387 nodes were still changing in round 10 in window all; recorded per ruling 8);
   - tier-2 refusals remove the probabilistic links of 149,056 mentions (a recall cost; precision is kept).

27. Deleting anything under scratch/spark/ is a human decision made after the commit. No agent deletes anything there.

## Repair 1: spark-engineer (repair-01)

Read the Review 1 section of review-report.md (MAJOR-1, MAJOR-2, m3, and the run-traceability note) and rulings 21 to 27. Edit only modules/spark/**. If a change outside it seems unavoidable, report BLOCKED-ON with the owner.
1. Provenance columns and manifest fields (ruling 21).
2. schemaVersion columns and the JOBS.md mapping section (ruling 22).
3. A required code version (ruling 24).
4. The master fallback (ruling 25).
5. Update the fixtures and tests for the new columns. Add a check in the export step that every exported table has the provenance and schemaVersion columns, with a test showing that a table without them is refused.
6. One fresh end-to-end run (jobs 1 to 11) with the repair code and code version src-<hash>. Use the memory settings of the successful work order 5 attempt, and check free disk first (ruling 17). Compare the result with the work order 5 run (run-eb4284f015763055514b0dacf41132b4):
   - the row count of every table;
   - an order-independent checksum (for example, the sum of xxhash64 over the key columns) of researchers, works, AUTHORED, coauthor edges, components, and communities, per window.
   Only added columns are expected to differ. Explain any other difference.
7. Report what changed for each finding (with file paths), the new run ID and output paths, the load-manifest path, the comparison table, sbt check twice, the Spark jar's org/apache/spark and software/amazon/awssdk counts, free disk before and after, and wall time.

## Review 2: code-reviewer

Review the Phase 6 change set again; the orchestrator supplies it as for review 1. Focus on repair-01 against rulings 21 to 27. Verify, with file and line evidence, that MAJOR-1, MAJOR-2 (as ruled in 22), m3, and the code-version fix are resolved. MAJOR-3 is closed by ruling 23, and the minors in ruling 26 are accepted residual risks: do not re-raise them unless the repair made them worse. Report any new critical or major finding introduced by the repair in full, and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.
