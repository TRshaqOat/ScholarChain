# Phase 06 human gate: temporal coauthorship graph and Spark analytics

Date: 2026-11-05
Human architect: Achyut Patel

## What was reviewed
- Agent reports for work orders 1 to 5 (agent-report.md) and repair-01 (repair-01.md).
- Code reviews in review-report.md:
  - Review 1: REQUEST_CHANGES, with MAJOR-1 (provenance), MAJOR-2 (core schemas), MAJOR-3 (template ownership) and minors m1 to m8.
  - Review 2: APPROVE, with minors n1 to n3.
- Human sbt check, run twice: core 151, entityResolution 94, sparkAnalytics 35, hadoop 70, 0 failed (evidence/human-sbt-check-1.txt and -2.txt).
- Spark assembly: 0 org/apache/spark and 0 software/amazon/awssdk entries.
- End-to-end local run (local[*], attempt 1, 998 s) on the Phase 5 R2 decisions (run-035e75abfa6798c938cbd534273dbd8d, er-v2), code version src-93bc726cd50a.
  - Pipeline run: run-de1f982ae8f83848b4f869d8718f5e3d.
  - Stage runs: run-10ceed31b3f602bb998eae7c00929ae9 (jobs 1-2), run-d7865ad3f5f8195546c1dab817328ee5 (jobs 3, 4, 8), run-83b496169099a614ec519133077a1993 (jobs 5-7), run-0375535e0174b7330b18a78988c07211 (jobs 9-11).
  - Manifests are in evidence/ (pipeline-manifest.json, load-manifest.json, stage-manifest-*.json).
- Determinism: the repair run matches the work order 5 run (run-eb4284f015763055514b0dacf41132b4) on the row counts of all 30 tables, on xxhash64 key checksums for the core tables per window, and on full-row hashes over the pre-existing columns.

## Phase 6 gate (spec)
- [x] Spark performs real distributed transformations and graph analytics (local[*]; the bounded EMR run is deferred to cloud window 1 by ruling 1 and stays open).
- [x] No complete graph is collected to the driver.
- [x] Distinct coauthor counts and pair weights pass small known-graph tests.
- [x] Connected components, community assignment behavior, path queries, and k-core are demonstrated on hand-checkable fixtures.
- [x] Every bridge measure is defined and its limitations are stated (modules/spark/JOBS.md).
- [x] Time windows are explicit in outputs (all, 2000-2014, 2015-2026).
- [x] Partition and shuffle behavior are documented.
- [x] Code review returns APPROVE (review 2).

## Results
- Canonical researchers 259,057; works 113,205; AUTHORED edges 535,898; venues 288; topics 1,226; institutions 58,404; mention-map rows 1,273,265.
- Per window (publications / researchers in graph / coauthor edges / components / communities):
  - all: 110,257 / 191,318 / 787,726 / 16,091 / 38,034
  - 2000-2014: 45,522 / 66,350 / 217,957 / 7,709 / 15,312
  - 2015-2026: 64,735 / 139,432 / 585,071 / 10,746 / 26,394
- Largest component holds 74% (all), 69% (2000-2014) and 71% (2015-2026) of researchers.
- Researcher refusals: 5,110 clusters.
  - Tier 2: 4,930 clusters, 149,056 members (two ORCIDs 4,679; CONFLICT 4,459; suffix 1,003; conflict group 15).
  - Tier 1: 180 clusters, 9,192 members (CONFLICT 177; two ORCIDs 131; conflict group 15; suffix 14).
  - Work refusals: 334 (168 tier 2, 166 tier 1).
  - All 17 conflict-group rows have memberCount = groupSize.
- Largest cluster: 1,077 mentions (Dhabaleswar K. Panda 0001). The 10 largest are all prolific researchers, with no common-name over-merge.
- Label propagation did not converge within 10 rounds in any window (387 nodes still changing in round 10 in window all).
- Path tests (50 seeded pairs): 37 found, 13 beyond depth 6, 0 unreachable. Sampled mean distance 5.79; 90% effective diameter 7.
- Self-pairs dropped: 95. Integrity violations: canonical 0, community-path 0, summary-export 0, analytics 21 (m6).
- Semantic summaries: 107,979.

## Human rulings recorded at the gate
1. MAJOR-3 (ruling 23): summary-template-v1 is an interim Phase 6 rendering. The rag-engineer owns the production rendering templates, built from the facts column, in Phase 7+8.
2. n2: ruling 27 does not cover a run deleting its own transient checkpoints under scratch/spark/checkpoints/<runId>. The behavior is accepted, with no code change.
3. Ruling 4 reading (review 1 assumption) confirmed: an UNRESOLVED researcher mention means a mention with no ACCEPTED link, and it stays its own canonical researcher.
4. The work-ID choice (smallest member source record ID) and the scr: researcher IDs are accepted as reasoned deviations under ruling 6.
5. m6 (transitive work clustering merges two DBLP records in 5 works) stays a residual risk and goes to Phase 11 as a policy question with the entity-resolution-engineer.

## Decision
ACCEPTED (review 2 APPROVE after one repair round).

## Manual changes
- No manual edits to agent-owned files.
- Per-file provenance: modules/spark/** is agent-generated (spark-engineer, work orders 2 to 5, repaired in repair-01) and accepted after review. The build.sbt change is agent-generated (build-engineer, work order 1) and accepted as-is.
- Human-authored: prompt.md rulings (before work order 1; after review 1, rulings 21 to 27), this gate, the evidence copies, and the 84d90b0 indentation edit to prompt.md.
- experiments/benchmark/benchmark-questions-v1.csv (committed separately in 94cd051) was Claude-drafted in the web chat and reviewed by the human. It is not generated by the runtime prompt under evaluation; disclose in Phase 17.
- To build the review change set, the orchestrator ran git status and git diff read-only. No agent ran git.

## Remaining risks
- The bounded EMR Spark run is still pending (cloud window 1, Nov 12). Nothing has run on s3a or YARN yet. The master rule under spark-submit and the S3A free-disk check are unverified, and single-JVM memory behavior on EMR is unknown.
- Path evidence uses the seeded 50-pair sample. Rerun the path job with the benchmark pairs (B07-B12, B29, B30 in experiments/benchmark/benchmark-questions-v1.csv).
- Inherited from Phase 5: 8.32M LLM-band researcher pairs stay UNRESOLVED (split identities).
- Tier-2 refusals remove the probabilistic links of 149,056 mentions (a recall cost; precision is kept).
- Label propagation does not converge within the 10-round cap.
- Review 1 minors m1, m2, m4, m5 (test-engineer, Phase 11), m6, m7, and m8 (domain-engineer, Phase 11).
- Review 2 minors:
  - n1: identityConfidence is a cluster-level lower bound (singletons 1.0), not a per-mention confidence. Phase 7+8 builders must not read it as per-mention, and JOBS.md:333 needs rewording.
  - n3: the export check does not re-read tables recorded as empty.
- All table schema versions come from one generation constant, so a future column change must bump the affected table explicitly.
- Embedding all 107,979 summaries at the Titan on-demand quota (60 requests per minute) would take about 30 hours, so Phase 7+8 embeds a bounded selection (at least 10,000) or uses batch inference.

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
