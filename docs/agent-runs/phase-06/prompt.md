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
