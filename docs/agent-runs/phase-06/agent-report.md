# Phase 06 agent reports

## Work order 1: build-engineer

````text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 6, work order 1. Get sbt project `sparkAnalytics` (directory `modules/spark`) ready for local Spark work.

The Spark project is ready for local Phase 6 work. Spark SQL 4.0.2, the cross-version check and the Spark assembly were already in place. I added forked serial tests with the Java 17 options Spark needs, and forked `run`/`runMain` with Spark on the classpath and a configurable heap. `sbt check` passed twice.

What already existed (read from `build.sbt` and `project/Dependencies.scala`):
- Both names confirmed: `lazy val sparkAnalytics = module("sparkAnalytics", "spark")`, which depends on `core` only.
- Item 1 was already done: `Dependencies.sparkSql = ("org.apache.spark" %% "spark-sql" % Versions.spark % Provided).cross(CrossVersion.for3Use2_13)`, with Spark pinned at 4.0.2.
- The mixed-suffix check is already on for every project: `conflictWarning := ConflictWarning("cross-version", Level.Error, failOnConflict = true)`. I kept it unchanged.
- `scala-xml_3` is already excluded so only Spark's `scala-xml_2.13` remains, and `scala-library-*` is already left out of the assembly.
- Item 4 was already reachable: the `packageJobs` alias runs `hadoop/assembly` and `sparkAnalytics/assembly`. I did not add a `packageSpark` alias.

What was missing and what I added (in the `sparkAnalytics` settings only):
- **Item 2 (tests):**
  - `Test / fork := true` and `Test / parallelExecution := false`.
  - `Test / javaOptions` gets `SparkJvmOptions` plus `-Xmx2g`.
  - `SparkJvmOptions` is exactly the list Spark 4.0.2 adds itself on Java 17. I took it from `org.apache.spark.launcher.JavaModuleOptions` (run through `javap`) in the cached `spark-launcher_2.13-4.0.2.jar`: 15 `--add-opens` entries, `--add-modules=jdk.incubator.vector`, `-XX:+IgnoreUnrecognizedVMOptions`, `-Djdk.reflect.useDirectMethodHandle=false` and `-Dio.netty.tryReflectionSetAccessible=true`.
- **Item 3 (local runs):**
  - `Compile / run` and `Compile / runMain` now use `Compile / fullClasspath`, so Spark (`Provided`) is on the run classpath.
  - `run / fork := true`.
  - `run / baseDirectory` is the repository root, so a relative `scratch/spark/` resolves to the repo's `scratch/spark/`, not `modules/spark/scratch/spark/`.
  - `run / javaOptions` gets `SparkJvmOptions` plus `-Xmx${SCHOLARCHAIN_SPARK_HEAP:-6g}`, documented in the `build.sbt` header.
- **Item 5:** no new library, and no change to the version ledger.

FILES CREATED: none
FILES MODIFIED: /Users/achyutpatel/Developer/Cloud/ScholarChain/build.sbt
FILES DELETED: none

MODULE GRAPH: Unchanged. `core` has no internal dependencies; `entityResolution` -> `core`; `hadoop` -> `core`, `entityResolution`; `sparkAnalytics` -> `core`; `storage` -> `core`; `rag` -> `core`, `storage`; `services` -> `core`, `rag`, `storage`; `tests` -> all modules in Test scope. This matches the CLAUDE.md table. I did not run a separate inspection command because no edge changed.

VERSION LEDGER: /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala, unchanged. Pins: scala 3.3.7, scalaLibrary 2.13.16, javaRelease 17, spark 4.0.2, hadoop 3.4.2, upickle 4.4.3, scalaTest 3.2.20, logback 1.5.38, typesafeConfig 1.4.9, awsSdk 2.41.32, and javaRuntimeImage eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8…5f45. Plugins (in `project/plugins.sbt`): sbt-scalafmt 2.6.2, sbt-assembly 2.5.0, sbt-native-packager 1.13.0. sbt 1.13.0; scalafmt 3.11.5.

SCOPES AND PACKAGING:
- `spark-sql` is `Provided`; its transitive Spark and Hadoop jars stay `Provided` and so are not in the assembly.
- Spark job jar: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/target/scala-3.3.7/sparkAnalytics-assembly-0.1.0.jar (3,507,604 bytes).
  - `unzip -Z1 <jar> | grep -c org/apache/spark` = 0
  - `unzip -Z1 <jar> | grep -c software/amazon/awssdk` = 0
  - `org/apache/hadoop` = 0
  - It contains `scholarchain/core`, upickle, typesafe config and the unshaded Scala 3 library.
- `packageJobs` reported both assembly jars as "up to date" (built at 00:12 today). Their inputs did not change in this order.
- No image changes.

ENVIRONMENT INPUTS:
- `SCHOLARCHAIN_IMAGE_TAG` (existing; `dockerLocal` only).
- `SCHOLARCHAIN_SPARK_HEAP` (new, optional, default `6g`): the heap of the forked `sparkAnalytics` run/runMain JVM. `sysctl -n hw.memsize` = 17179869184 (16 GiB), so 6g is under the half-of-RAM limit in ruling 17. The build does not enforce that limit.

COMPATIBILITY RISKS:
- No library appears with both `_2.13` and `_3` suffixes on the run classpath. Scala 3 jars there: `upickle*_3`, `geny_3`, `scala3-library_3`. Scala 2.13 jars: the Spark jars, json4s, chill, jackson-module-scala, scala-xml, scala-parser-combinators, scala-parallel-collections. The `failOnConflict` check passed.
- The forked JVM printed "Using Scala version 2.13.16, OpenJDK 64-Bit Server VM, 17.0.21", which matches D-01 and D-02.
- The Scala 3 library is bundled but not shaded in the Spark jar (a Phase 2 decision, unlike the shaded hadoop jar). On EMR this depends on Spark not providing another Scala 3 library; that is unverified until the cloud window 1 EMR run (ruling 1, D-01).

COMMANDS RUN:
- Read-only inspection: `cat` of the build files, `.sbtopts`/`.jvmopts` (neither exists), `.scalafmt.conf`, `.gitignore`; `find modules/spark`; `sysctl -n hw.memsize`; `java -version` (Temurin 17.0.21); `df -h .` (24Gi free).
- `unzip` and `javap -v` on the cached spark-launcher jar, in the scratchpad.
- `sbt -batch scalafmtSbt "show sparkAnalytics/Test/fork" "show sparkAnalytics/Test/parallelExecution" "show sparkAnalytics/Test/javaOptions" "show sparkAnalytics/Compile/run/fork" "show sparkAnalytics/Compile/run/javaOptions" "show sparkAnalytics/Compile/run/forkOptions" "export sparkAnalytics/Compile/fullClasspath"`.
- `sbt -batch "sparkAnalytics/runMain org.apache.spark.deploy.SparkSubmit --version" packageJobs`.
- `unzip -Z1` counts on the Spark assembly.
- `sbt -batch check`, run twice in one shell loop.
- `pgrep` and `jstack` to see which tests were running during check run 1.

TEST RESULTS:
- `sbt check` run 1: three projects reported tests (the output does not name them). 151 succeeded, 0 failed; 94 succeeded, 0 failed; 70 succeeded, 0 failed. 315 in total. Each said "All tests passed.", then "markTestRun: tests passed, wrote …/.claude/.last-test-run" and "[success] Total time: 259 s".
- `sbt check` run 2: the same 151/94/70 counts, 0 failed, markTestRun wrote the marker, "[success] Total time: 573 s".
- The marker reads 2026-11-05T04:09:41.310924Z, dated Nov 4 22:09 local, which is newer than `build.sbt` (21:55).
- `sparkAnalytics` has no test sources yet (`modules/spark/src` does not exist), so this order adds no check time. The slow part is the existing hadoop `PipelineSpec` local MapReduce tests (seen in `jstack`).
- The script's `exit=` line was empty because `PIPESTATUS` does not exist in zsh. I am relying on the `[success]` lines, the markTestRun lines and the background task's exit code 0.

VALIDATION RESULTS:
- `show` printed `Test/fork` true, `Test/parallelExecution` false, the full Spark JVM option list plus `-Xmx2g` for tests, and the same list plus `-Xmx6g` for run.
- `forkOptions` showed the working directory as /Users/achyutpatel/Developer/Cloud/ScholarChain.
- The forked `runMain` printed the Spark 4.0.2 banner. sbt shows the forked process's stderr as `[error]` lines; the task ended `[success]`.
- `--add-opens` with a real local SparkSession has not been tried, because there is no Spark source to run yet.

ASSUMPTIONS:
- `SparkSubmit --version` is a good enough proxy for "runMain works with Provided Spark" until the spark-engineer adds a main class.
- A test heap of 2g is enough for small hand-checkable fixtures.
- An environment variable is a better heap control than a custom sbt setting.

RISKS:
- Run 2 took 573 s against 259 s for run 1 with the same tests. The cause is not measured; the hadoop tests dominate the time.
- The Scala 3 library is unshaded in the Spark jar (see COMPATIBILITY RISKS).
- Spark test JVMs are new; their startup time will add to `sbt check` once suites exist.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer APPROVE

I did not check the module graph with an sbt inspection command (no edges changed) and did not rebuild the assembly (it was up to date). A real local SparkSession has not run under these settings; the spark-engineer's first test will be the first one.
````

## Work order 2: spark-engineer

````text
STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 6, work order 2. Spark jobs 1 and 2: canonical entities and the temporal coauthorship graph, under human rulings 1 to 20.

Jobs 1 and 2 are built, tested, and run on the full R2 input:
- **Tests:** 18 module-local tests on hand-checked fixtures pass.
- **sbt check:** passed twice (333 tests each time).
- **Full local run:** 201.6 s wall, 0 integrity violations.
- **Determinism:** I ran the full job three times (attempts 1 to 3). All three gave identical table row counts and identical counts.
- **Path for later work orders:** use attempt 3, which matches the final code:
  `/Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-7488e94af55d68e29273be0617151eb8/`

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/JOBS.md (output layout, algorithms, shuffle boundaries)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/resources/scholarchain-spark.conf
- Main sources in /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/:
  - SparkSettings.scala
  - Inputs.scala (explicit schemas, `Ids`, `MatchPath`)
  - ConnectedComponents.scala
  - Clustering.scala
  - Canonical.scala
  - CoauthorGraph.scala
  - StageMetrics.scala
  - CanonicalGraphJob.scala
- Tests in /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/:
  - TestSpark.scala
  - ConnectedComponentsSpec.scala
  - SparkSettingsSpec.scala
  - Fixture.scala
  - CanonicalGraphJobSpec.scala
- Run outputs under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/ (three run directories, plus an empty checkpoints/ directory).

FILES MODIFIED: none outside the above. Nothing under scratch/hadoop/ was modified, moved, or deleted.
FILES DELETED: only each run's own temporary checkpoint subdirectory, `scratch/spark/checkpoints/<runId>/`, which the job deletes at the end of every run.

STAGE DAGS AND SHUFFLE BOUNDARIES:
Attempt 3, from `_stages.json` and `stagesByStep`: 685 stages, 6,072 tasks. Shuffle read 11,628,689,709 B, shuffle write 10,830,538,384 B, disk spill 1,281,424 B.

Per step (shuffle read / write):
- read inputs: 52 stages, about 0 shuffle.
- cluster researchers: 235 stages, 6,741 / 5,991 MiB.
- write mention-map: 1,088 / 1,088 MiB.
- researcher-refusals: 46.7 MiB.
- cluster works: 145 stages, 690 / 679 MiB.
- work-map: 108.6 MiB.
- researchers: 286 MiB.
- works: 102.9 MiB.
- authored: 161 MiB.
- researcher-institutions: 174.9 MiB.
- coauthor-edges: 908.8 / 848.1 MiB.
- self-pairs: 248 MiB.
- integrity-violations: 403.5 MiB.
- report: 88.7 MiB.

Where the shuffles happen:
- **Each clustering:**
  - node union and distinct;
  - each connected-components round (two group-bys, two joins, star check);
  - tier-1-root contraction joins;
  - refusal joins and group-bys;
  - final mapping joins.
- **Before each Parquet write:** a hash repartition on researcherId, workId, or researcherA.
- **Per output table:** one group-by (researchers, works, authored, edges per (window, pair)).

Connected-components rounds: researcher tier 1 = 3, tier 2 = 4; work tier 1 = 2, tier 2 = 0.

PARTITION COUNTS AND SKEW:
- **Settings:**
  - master local[*], 10 cores;
  - `spark.sql.shuffle.partitions = 40`, set explicitly; AQE on, so it coalesces stages to 12 to 21 tasks;
  - driver heap 6,442,450,944 B (6g, from `SCHOLARCHAIN_SPARK_HEAP`'s default) against 17,179,869,184 B RAM.
- **Inputs:** gzip parts cannot be split, so each input directory reads as 8 tasks (Hadoop used 8 reducers).
- **Write partitions:** 8 for large tables, hashed on the key; 1 or 2 for small ones. Coauthor-edges: 8 x 3 windows = 24 files.
- **File-size spread (min to max bytes):**
  - mention-map 8,119,607 to 8,355,977
  - researchers 3,423,892 to 3,488,080
  - authored 8,318,206 to 8,533,930
  - works 2,224,098 to 2,322,117
  - coauthor-edges 2,806,555 to 11,780,423. This spread comes from the window sizes, not the key.
- **Hub researchers:**
  - Largest researcher cluster: 1,077 mentions.
  - Highest degree in all-years: Ian T. Foster 770, Minyi Guo 671, Franck Cappello 590, Albert Y. Zomaya 525, Martin Schulz 0001 521.
- **Giant teams:** the largest DBLP teams are dblp:journals/jpdc/Aartsen15 (287 authors), then 128, 115, 102, and 95.
- **Largest refused clusters (homonym chains):**
  - tier 2: 1,060 members, rules CONFLICT_DECISION and TWO_VALID_ORCIDS, sample member dblp:conf/IEEEcloud/ChenZX0020#4;
  - tier 1: 761 members, same rules and sample member;
  - tier 2: 773 and 719 members (plus HOMONYM_SUFFIX_DISAGREEMENT) and 697 members.

GRAPH COUNTS:
- **Totals:** 259,057 canonical researchers (191,318 with a DBLP mention); 113,205 canonical works (110,862 with a DBLP member); 535,898 AUTHORED edges.
- **Per window (ruling 19):**

| Window | Works (DBLP-anchored) | AUTHORED | Active researchers (DBLP-anchored) | Coauthor edges | Researchers with an edge | Max jointPublicationCount |
|---|---|---|---|---|---|---|
| all-years 2000-2026 | 113,202 (110,862) | 535,894 | 259,057 (191,318) | 787,726 | 186,469 | 127 |
| 2000-2014 | 47,154 (45,737) | 195,478 | 91,101 (66,912) | 217,957 | 63,231 | 69 |
| 2015-2026 | 66,048 (65,125) | 340,416 | 184,230 (139,523) | 585,071 | 137,634 | 99 |

- **Outside all windows:** 3 works and 4 AUTHORED edges fall outside 2000-2026 (OpenAlex years 1995-1999 or a null year).
- **Self-pairs:** 95 dropped (on 95 works).
- **Pairs outside every window:** 0.
- **Joint publication sum:** 1,019,535 pair rows - 95 self-pairs = 1,019,440 rows. The all-years sum of jointPublicationCount is 1,019,039, so 401 rows repeated a (researcherA, researcherB, canonical work) that was already counted.
- **Not in this work order:** components, singleton-component counts, and communities.

ALGORITHMS:
- **Connected components** (ConnectedComponents.scala):
  - Definition: alternating large-star / small-star (Kiveris et al., 2014), as DataFrame iteration over LONG keys (xxhash64 of the string IDs; a collision fails the run). It stops when the edges form stars; each root is its component's minimum.
  - Parameters: `cc-max-iterations = 60` (the run fails if not converged). Every round is a reliable checkpoint under scratch/spark/checkpoints/<runId>/.
  - Approximation: none.
  - Limitations: O(log^2 n) rounds, each a full pass over the current edges; hub nodes make large min-aggregation groups.
  - Tests: chain, triangle, pair, already-a-star, and a chain whose minimum is in the middle.
- **Two-tier clustering** (Clustering.scala, rulings 4 to 6, the default algorithm with no deviation):
  - Tier 1: EXACT_ID and DETERMINISTIC ACCEPTED edges.
  - Tier 2: all ACCEPTED edges, computed on tier-1 roots.
  - Refusal rules: TWO_VALID_ORCIDS (5a), CONFLICT_DECISION (5b), HOMONYM_SUFFIX_DISAGREEMENT (5c), IDENTIFIER_CONFLICT_GROUP (5d). All four are monotone.
  - Falling back: a refused tier-2 cluster falls back to its tier-1 clusters; a refused tier-1 cluster falls back to singletons.
  - The match path comes from `rulesFired`, using a copy of entity-resolution `MatchPolicy.pathOf`.
- **Coauthor aggregation:** one group-by per (window, canonical pair); jointPublicationCount = distinct canonical works.

METRIC DISTRIBUTIONS:
- **Refusals by tier and rule (researchers):**
  - TIER2: TWO_VALID_ORCIDS 4,679; CONFLICT_DECISION 4,459; HOMONYM_SUFFIX_DISAGREEMENT 1,003; IDENTIFIER_CONFLICT_GROUP 15. Total 4,930 clusters, 149,056 members.
  - TIER1: CONFLICT_DECISION 177; TWO_VALID_ORCIDS 131; IDENTIFIER_CONFLICT_GROUP 15; HOMONYM_SUFFIX_DISAGREEMENT 14. Total 180 clusters, 9,192 members.
- **Refusals (works):** TIER2 CONFLICT_DECISION 168 clusters (642 members); TIER1 CONFLICT_DECISION 166 clusters (634 members).
- **Mention outcomes (1,273,265 mentions):**
  - TIER2 merged: 1,102,060
  - TIER1 with reason REFUSED_TIER2: 72,966
  - SINGLETON with REFUSED_TIER2: 66,898
  - SINGLETON with REFUSED_TIER1: 9,192
  - SINGLETON with UNRESOLVED: 16,229
  - SINGLETON with NO_ACCEPTED_LINK: 5,920
- **Work outcomes:**
  - TIER2: 319,059
  - TIER1 with REFUSED_TIER2: 6
  - SINGLETON: NO_ACCEPTED_LINK 5,300; REFUSED_TIER1 634; UNRESOLVED 28; REFUSED_TIER2 2.
- **Researcher cluster sizes (mentions: researchers):** 1: 98,239; 2: 8,076; 3-5: 101,342; 6-10: 31,536; 11-20: 10,772; 21-50: 6,425; 51-100: 1,781; 101-500: 870; 501+: 16.
- **Ten largest researcher clusters (mentions, display name):** 1,077 Dhabaleswar K. Panda 0001; 1,035 Jack J. Dongarra; 892 Albert Y. Zomaya; 879 Rajkumar Buyya; 782 Ian T. Foster; 713 Franck Cappello; 702 Minyi Guo; 678 José Duato; 671 Rachid Guerraoui; 644 Yves Robert. All ten are TIER2, all three sources, one ORCID each.
- **Work cluster sizes:** 1: 5,964; 2: 2,663; 3-5: 104,578.
- **Accepted decision edges by tier and path:**
  - RESEARCHER: tier 1 EXACT_ID 4,867,814 and DETERMINISTIC 297,105; tier 2 PROBABILISTIC 6,294,917.
  - WORK: tier 1 EXACT_ID 517,810 and DETERMINISTIC 145; tier 2 PROBABILISTIC 4.
- **Conflict-group check (ruling 5d):** 17 rows (16 identifiers). Every member count equals its groupSize. orcid:0000-0001-5832-0347 is listed in both suffix-conflicts and oversize-conflicts, with 152 members and groupSize 152.

TEMPORAL WINDOWS: all-years 2000-2026, 2000-2014, and 2015-2026, all from the core config `graph.windows`. Ruling 7 calls the first one "all"; the config names it "all-years". Every coauthor-edge row carries window, windowStartYear, and windowEndYear, and the table is partitioned by window.

INTEGRITY VIOLATIONS: 0 rows in integrity-violations. The run applies 11 checks:
- RESEARCHER_DECISION_ENDPOINT_MISSING
- WORK_DECISION_ENDPOINT_MISSING
- AUTHORED_WORK_MISSING
- AUTHORED_RESEARCHER_MISSING
- AUTHORED_WORK_NODE_MISSING
- WORK_VENUE_MISSING
- HAS_TOPIC_WORK_MISSING
- HAS_TOPIC_TOPIC_MISSING
- RESEARCHER_INSTITUTION_MISSING
- PAIR_ENDPOINT_MISSING / PAIR_WORK_MISSING
- COAUTHOR_EDGE_ENDPOINT_MISSING

The fixture triggers five of them, and the tests check each one.

Separately, 2,486 of 27,488 ORCID AFFILIATED_WITH edges were not linked, because zero or several researchers carry that iD. They are counted in the manifest, not written as violations.

OUTPUT PATHS AND LOAD MANIFESTS: the run directory is the attempt-3 path above, with these files:
- `_manifest.json` (spark-run-manifest-v1): every input with its row count, and every table with path, schema DDL, row count, write partitioning, file sizes, and intended target stores.
- `_stages.json`
- `_started.json`

Tables, under `canonical/`:
- mention-map (1,273,265 rows)
- researchers (259,057)
- work-map (325,029)
- works (113,205)
- authored (535,898)
- venues (288)
- topics (1,226)
- institutions (58,404)
- work-topics (106,682)
- researcher-institutions (379,708)
- researcher-refusals (5,110)
- work-refusals (334)

Tables, other:
- `graph/coauthor-edges` (1,590,754 rows; partitioned by window)
- `graph/self-pairs` (95)
- `integrity-violations` (0)

None of the entity tables is absent: Topic and Institution are present in the input. Store-specific formats are left to Phase 7+8 (ruling 13).

Inputs read, all under the R2 run directory:

| Input | Rows |
|---|---|
| author-mentions | 1,273,265 |
| works | 325,029 |
| coauthor-pair-candidates | 1,019,535 |
| match-decisions RESEARCHER ACCEPTED | 11,459,836 |
| match-decisions RESEARCHER CONFLICT | 95,862 |
| match-decisions RESEARCHER UNRESOLVED | 9,537,292 |
| match-decisions WORK ACCEPTED | 517,959 |
| match-decisions WORK CONFLICT | 18,392 |
| match-decisions WORK UNRESOLVED | 167 |
| suffix-conflicts | 16 |
| oversize-conflicts | 1 |
| venues | 288 |
| topics | 1,226 |
| institutions | 58,404 |
| graph-edges HAS_TOPIC | 106,682 |
| graph-edges AFFILIATED_WITH | 27,488 |

REJECTED decisions, candidates, and publication teams are not read.

COMMANDS RUN:
- Reads with cat, sed, grep, ls, du, and gzcat (wc and head) on the input; sysctl hw.memsize and hw.ncpu; df -k.
- `sbt -batch sparkAnalytics/compile`.
- `sbt -batch sparkAnalytics/test`, and `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/test` three times.
- `sbt -batch "sparkAnalytics/runMain scholarchain.spark.CanonicalGraphJob --input scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d --code-version a8077c6-dirty --shuffle-partitions 40"`, three times:
  - attempt 1: run-12f50d7628f7c5bb6a8d59043a6e1843
  - attempt 2: run-c99f7ef99cc47583b22dfbc15ae7d467 (adds the skew diagnostics)
  - attempt 3: run-7488e94af55d68e29273be0617151eb8 (final code)
- `sbt -batch check`, twice in a row.
- python3, to read the manifests.

No git, aws, or network commands.

TEST RESULTS:
- sparkAnalytics: 18 succeeded, 0 failed: CanonicalGraphJobSpec 13, SparkSettingsSpec 2, ConnectedComponentsSpec 3. About 30 s.
- sbt check run 1: 151 / 94 / 18 / 70 succeeded, 0 failed. Each project reported "All tests passed." markTestRun wrote the marker. "[success] Total time: 260 s".
- sbt check run 2: the same counts, 0 failed, the marker was written, "[success] Total time: 260 s". The marker reads 2026-11-05T05:05:46.744991Z, newer than every changed source.
- The fixture covers every case the work order lists:
  - a chain A-B-C where A and C carry two ORCIDs (refused at tier 2, falls back to tier 1);
  - a CONFLICT decision inside a component (refused at tier 1);
  - suffixes 0001 and 0002 in one component;
  - a suffix-conflict group of three plus an unsuffixed mention (rules 5b, 5c, and 5d at tier 2; {L, M} kept at tier 1);
  - plus an oversize group refused at tier 1;
  - an UNRESOLVED mention that stays separate;
  - a self-pair created by a merge;
  - duplicate pairs across 2003 and 2016 (all-years count 2, other windows 1 each);
  - pair-order canonicalization, with IDs checked against core StableIds;
  - a work cluster with a DOI conflict;
  - integrity counts, refusal counts by tier and rule, and the manifest;
  - a rerun that gets attempt 2 with identical tables.

VALIDATION RESULTS:
- Attempts 1, 2, and 3 have equal table row counts, equal counts, and equal input counts.
- After each run the checkpoints directory is empty.
- Attempt 3 logged no HintErrorLogger warnings (attempt 1 logged 7).
- Free disk:
  - session start: 24,802,104 KiB
  - before attempt 3: 22,189,756 KiB; after attempt 3: 21,749,016 KiB
  - the manifest's in-JVM readings: 22,721,630,208 B before, 20,625,911,808 B after
  - now: 21,712,944 KiB
  - Each run directory is 410M. Free space never came near the 5 GiB floor.
- Wall time for attempt 3: manifest 201.58 s; `time` real 206.00 s. Slowest steps: cluster researchers 99.4 s, cluster works 19.6 s, read inputs 20.6 s, write mention-map 16.4 s.

ASSUMPTIONS (deviations from the rulings, with reasons):
- **Researcher IDs are always `scr:canon-v1.er-v2:<H(smallest mention ID)>`, never `orcid:`.** When a refused cluster is split, several researchers can carry the same ORCID; `orcid:` IDs would then collide.
- **Work canonical ID is the smallest member work ID itself, not a hash.** This keeps it a valid core work ID, and it equals the DBLP member whenever the cluster has one ("dblp:" sorts first).
- **The canonical universe includes OpenAlex and Crossref mentions and works,** flagged with dblpAnchored, and counts are given both ways. The coauthor graph uses DBLP pairs only.
- **AUTHORED has one row per (researcher, work) with a mentionIds list,** not one per mention as in core AuthorshipRecord. This avoids duplicate graph edges.
- **representativeWorkIds holds the complete sorted list** (at most 127), so no stable pointer is needed.
- **Reported tiers:** TIER2 means the tier-2 cluster was kept, and it may hold only tier-1 edges. SINGLETON means a cluster with one mention.
- **Rule 5c uses the entity-resolution homonym pattern on rawName for any source,** matching the J03 group rule.
- **The code version is "a8077c6-dirty",** taken from the session-start git snapshot, because I may not run git.

RISKS:
- **Duplicated rule IDs:** the MatchPath rule-ID list is copied from entity-resolution (the module boundary forbids the dependency). If entity-resolution changes those rule IDs, the copy goes stale silently.
- **Merged DBLP works:** 5 canonical works contain two or more DBLP works. Their joint publications count once.
- **Refused homonym chains add many singleton researchers:** for example, the 761-mention tier-1 cluster became 761 singletons. 76,090 mentions are singletons by refusal.
- **Run IDs don't follow code changes:** the code-version string was the same while the code changed between attempts. Pass a real SHA once the work is committed.
- **Broadcast shortcut:** the dangling-endpoint anti-join broadcasts the dangling set, which is 0 here. Recorded in a `shortcut:` comment.
- **Not yet verified on EMR:** the EMR Spark run is deferred to cloud window 1.
- **Unused earlier runs:** attempts 1 and 2 remain in scratch/spark (410M each). They are identical in content to attempt 3.

MANUAL ACTION REQUIRED: none. The human may remove the attempt 1 and 2 run directories if wanted.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer APPROVE

What I did not do: I did not mutation-test the tests (the module is new), and I did not compare researcher-ID sets across the full-data attempts (only counts). The main risk is the duplicated MatchPath rule list, which can drift from entity-resolution.
````

## Work order 3: spark-engineer

````text
STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 6, work order 3. Spark jobs 3, 4, and 8: researcher statistics (P_r to W_r), connected components, and k-core, under human rulings 1 to 20.

Jobs 3, 4, and 8 are built, tested, and run on the full work order 2 output.
- **Tests:** 23 module-local tests pass: work order 2's 18, plus 5 new tests on hand-checked fixtures.
- **sbt check:** passed twice in a row.
- **Final full run:** attempt 3, 449.4 s wall.
- **Determinism:** attempts 2 and 3 give identical counts, distributions, histograms, and per-round convergence logs. Attempt 1 matches them on every block it shares.
- **Integrity:** 21 rows across two metric-identity checks, all of them explained. Every one belongs to a researcher on one of the 5 canonical works that merge two DBLP records (work order 2 already reported these 5). Details under INTEGRITY VIOLATIONS.

**Path later work orders must read (attempt 3, final code):**
/Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-65981c133fda5db503b0e2475e30367a/

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/JobRun.scala: run scaffolding shared by every job (run ID and attempt, free-disk checks, `_started.json`, table writes, `_stages.json`, `_manifest.json`, checkpoint cleanup). I extracted it from CanonicalGraphJob so later jobs do not copy it.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/ResearcherStats.scala (job 3)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/MinLabelComponents.scala (job 4)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/KCore.scala (job 8)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/ResearcherAnalyticsJob.scala (the job and its manifest counts)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/ResearcherAnalyticsSpec.scala (5 tests)
- Run directories under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/, 42M each:
  - attempt 1: run=run-7fcb250a09b2fad0243a6d2f36ce8550 (before the invariant checks were split)
  - attempt 2: run=run-236433171080c7f112dbf9ade59181da (before the merged-work attribution covered both checks)
  - attempt 3: run=run-65981c133fda5db503b0e2475e30367a (final)

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/CanonicalGraphJob.scala: now uses JobRun. Its behaviour, manifest fields, run-ID formula, and checkpoint cleanup are unchanged. All 13 of its tests pass.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/JOBS.md: new section "ResearcherAnalyticsJob (spec Phase 6 jobs 3, 4, and 8)". It defines each measure, both algorithms, the output tables, the integrity checks, and the shuffle boundaries.

FILES DELETED: only each run's own temporary checkpoint directory, scratch/spark/checkpoints/<runId>/, which the job deletes at the end. scratch/spark/checkpoints/ is empty now. Nothing under scratch/hadoop/ and none of work order 2's run directories were touched.

STAGE DAGS AND SHUFFLE BOUNDARIES:
Attempt 3, from `stagesByStep`: 3,750 stages, 31,322 tasks, shuffle read 30,868,882,670 B, shuffle write 30,868,850,775 B, disk spill 0.

| Step | Stages | Tasks | Max tasks in a stage | Shuffle read / write | Stage time |
|---|---|---|---|---|---|
| read inputs | 16 | 76 | 10 | 0 | 1.8 s |
| connected components | 183 | 2,154 | 52 | 5,585.7 / 5,585.7 MiB | 41.8 s |
| write components | 8 | 34 | 11 | 5.8 MiB | 0.7 s |
| k-core | 3,476 | 28,696 | 52 | 23,709.3 / 23,709.3 MiB | 486.3 s |
| write researcher-metrics | 9 | 94 | 40 | 82.8 MiB | 2.7 s |
| write integrity-violations | 9 | 94 | 42 | 21.3 MiB | 1.4 s |
| report | 49 | 174 | 20 | 33.9 MiB | 3.9 s |

Job 3 is lazy, so its stages fall under the connected-components and write steps.

Where the shuffles happen:
- **Job 3:** the team-size group-by on workId and its join back, the group-by on (window, researcher), the degree group-by over edges, and the join on (window, researcherId).
- **Job 4, each round:** the frontier-edge join on (window, src), the min group-by on (window, node), and the old/new label join.
- **Job 8, each round:** two anti-joins of the remaining edges against the peeled set, the residual-degree group-by, and the state join.
- **Then:** the component-size group-by, the membership and coreness joins, and a hash repartition on researcherId before the metrics write.

PARTITION COUNTS AND SKEW:
- **Settings:**
  - local[*], 10 cores; `spark.sql.shuffle.partitions = 40`, set explicitly; AQE on, so stages run 10 to 52 tasks.
  - Driver heap 6,442,450,944 B against 17,179,869,184 B RAM (`sysctl -n hw.memsize`), under half.
- **Write partitions:**
  - researcher-metrics: 8 per window, 24 files, 840,071 to 2,411,114 B. The spread follows window size.
  - components: 1 per window, 3 files, 516,725 to 1,075,851 B.
- **Skew sources:**
  - Giant teams: the 287-author work dblp:journals/jpdc/Aartsen15 forms a 286-clique, giving max coreness 286 and max M_r 286.
  - Hubs: max C_r 770 (all-years).
  - One giant component per window: 46,062, 98,812, and 141,367 researchers.
- **Spill:** 0 B.

GRAPH COUNTS (ruling 19, per window):

| Window | DBLP publications | Researchers (graph nodes) | Coauthor edges | Components | Largest component | Largest fraction | Singleton researchers |
|---|---|---|---|---|---|---|---|
| all-years 2000-2026 | 110,257 | 191,318 | 787,726 | 16,091 | 141,367 | 0.7389 | 4,849 |
| 2000-2014 | 45,522 | 66,350 | 217,957 | 7,709 | 46,062 | 0.6942 | 3,119 |
| 2015-2026 | 64,735 | 139,432 | 585,071 | 10,746 | 98,812 | 0.7087 | 1,798 |

- researcher-metrics has 397,100 rows (66,350 + 139,432 + 191,318). Component sizes sum to the window's researcher count in every window.
- Component size histogram (1 / 2 / 3-5 / 6-10 / 11-100 / 1001+):
  - all-years: 4,849 / 3,681 / 5,590 / 1,610 / 360 / 1
  - 2000-2014: 3,119 / 1,756 / 2,194 / 504 / 135 / 1
  - 2015-2026: 1,798 / 2,522 / 4,559 / 1,480 / 386 / 1
- No component has 101 to 1,000 members. The second-largest components are 38 (all-years), 43 (2000-2014), and 70 (2015-2026).
- Communities: not in this work order.

ALGORITHMS:
- **Job 3, measures** (ResearcherStats.scala; full definitions in JOBS.md):
  - Definition: P_r, T_r, A_r = T_r/P_r, M_r, and maximumTeamWorkIds are computed from DBLP authorship. team(w) = distinct canonical researchers with a DBLP mention on canonical work w. C_r = degree in the window's coauthor edges. W_r = sum of jointPublicationCount over those edges. Each measure is its own column, never combined.
  - Approximation: none.
  - Limitation: W_r = T_r by construction (each shared work adds one to both sums), except on works whose two bases differ; the job checks this per row.
- **Job 4, components** (MinLabelComponents.scala):
  - Definition: iterative min-label propagation with a frontier (only nodes whose label dropped send messages). root = the smallest researcherId in the component. componentId = "comp-" + H("component", graph policy, window, start, end, root).
  - Parameters: cc-max-iterations = 60 (the run fails if not converged). Reliable checkpoint every round.
  - Approximation: none.
  - Limitation: rounds = the largest distance to the component minimum plus one confirming round.
  - Deviation: work order 2's large-star/small-star code stays as it is for clustering. I added min-label propagation because this work order asks for it.
- **Job 8, k-core** (KCore.scala):
  - Definition: exact coreness by iterative peeling. Each round, k = max(k, smallest residual degree); every unpeeled node with residual degree <= k gets coreness k. All windows run together with a shared k; JOBS.md gives the correctness argument.
  - Parameters: core `analytics.kcore-max-iterations` = 100, used as the maximum peeling rounds within one level (the run fails beyond it). Reliable checkpoint every round.
  - Approximation: none. Edge weights are ignored.
  - Limitation: rounds = the sum over levels of the peeling depth.
- **Tests:** each algorithm has a hand-checked fixture (see TEST RESULTS).

METRIC DISTRIBUTIONS (exact `percentile`; min / median / p90 / p99 / max):

| Measure | all-years | 2000-2014 | 2015-2026 |
|---|---|---|---|
| P_r | 1/1/4/22/359 | 1/1/4/22/213 | 1/1/3/17/165 |
| C_r | 0/4/15/80/770 | 0/4/13/61/349 | 0/5/15/70/555 |
| T_r | 0/5/19/121/1759 | 0/4/18/100/827 | 0/5/19/109/1058 |
| A_r | 0/4/8/27/286 | 0/3/6/23/114 | 0/4/8/28/286 |
| M_r | 0/4/9/36/286 | 0/3/8/40/114 | 0/4/9/34/286 |
| W_r | 0/5/19/121/1759 | 0/4/18/100/827 | 0/5/19/109/1058 |
| coreness | 0/4/9/36/286 | 0/3/8/40/114 | 0/4/10/34/286 |
| componentSize | 1/141367/141367/141367/141367 | 1/46062/46062/46062/46062 | 1/98812/98812/98812/98812 |

**Coreness distribution:**
- Distinct coreness values: all-years 60, 2000-2014 41, 2015-2026 50.
- all-years counts for coreness 0 to 5: 4,849; 15,122; 26,843; 32,103; 30,383; 25,138.
- The high end is clique-shaped. Each top level holds exactly k+1 researchers: (114, 115), (127, 128), (286, 287).
- Highest coreness:
  - 2015-2026 and all-years: 286 (Lisa Gerhardt, Sofia Vallecorsa, Matthew Dunkman).
  - 2000-2014: 114 (José E. Moreira, John A. Gunnels, Manish Gupta 0002).

**Iterations to converge:**
- Components: 18 rounds. Labels changed per round: 307615, 253592, 244215, 247046, 209845, 116010, 38906, 11953, 3788, 1279, 502, 190, 97, 67, 21, 9, 2, 0.
- k-core: 304 peeling rounds over 60 distinct levels. At most 16 rounds in one level (levels 7 and 12), against the cap of 100.
- The full per-round log, as (k, nodes peeled), is in the manifest under `kcoreRoundLog`.

TEMPORAL WINDOWS: all-years 2000-2026, 2000-2014, and 2015-2026, from the core `graph.windows`. The job fails if they differ from the input run's windows. Every output row carries window, windowStartYear, and windowEndYear, and both tables are partitioned by window. A work's window comes from its canonical year; works with a null year fall in no window.

INTEGRITY VIOLATIONS: analytics/integrity-violations holds 21 rows.
- METRIC_RESEARCHER_MISSING: 0
- EDGE_ENDPOINT_WITHOUT_PUBLICATION: 0
- METRIC_INVARIANT: 0. It covers C>T, M>T, A>M, graph degree != C, coreness > degree, a null component or coreness, and an empty M_r work list.
- WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES: 12
- CORENESS_BELOW_MAX_TEAM: 9

The manifest's `mergedDblpWorkChecks` reports 5 merged DBLP works. It attributes 12 of the 12 W != T rows and 9 of the 9 coreness < M rows to researchers with a publication in that window on one of those works. On those works, team(w) counts authors across both DBLP records, but job 2 pairs authors only within one record. No other row breaks either identity.

OUTPUT PATHS AND LOAD MANIFESTS: run directory /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-65981c133fda5db503b0e2475e30367a/
- `_manifest.json` (spark-run-manifest-v1): inputs with row counts, tables with path, schema DDL, row count, partitioning, file sizes, and intended targets, plus counts, step seconds, stage summaries, and free disk. `_stages.json` and `_started.json` sit beside it.
- `analytics/researcher-metrics`: 397,100 rows, partitioned by window. Columns use the core ResearcherMetrics field names: schemaVersion, P to W, maximumTeamWorkIds, componentId, componentSize, coreness, graphDegree, and DERIVED provenance with graph-v1. Targets: neptune, dynamodb, s3.
- `analytics/components`: 34,546 rows, partitioned by window. Targets: s3, dynamodb.
- `analytics/integrity-violations`: 21 rows. Target: s3.
- Inputs read from work order 2's attempt 3: canonical/researchers (259,057), canonical/authored (535,898), canonical/works (113,205; member IDs only), graph/coauthor-edges (1,590,754).
- Run ID: run-65981c133fda5db503b0e2475e30367a, attempt 3. Fingerprints: canonical-graph-run=run-7488e94af55d68e29273be0617151eb8, input size 424,045,923 B, graph-v1, the three windows, cc-max-iterations=60, kcore-max-iterations=100.

COMMANDS RUN:
- Reads: cat, sed, grep, ls, find, du, and strings on one 3 KB violations Parquet file; `sysctl -n hw.memsize`; `df -k .` before each full run; python3 to read the manifests.
- `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/Test/compile`, then `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/test` three times.
- `sbt -batch "sparkAnalytics/runMain scholarchain.spark.ResearcherAnalyticsJob --input scratch/spark/snapshot=2026-10-03/run=run-7488e94af55d68e29273be0617151eb8 --code-version a8077c6-dirty --shuffle-partitions 40"`, three times (attempts 1, 2, 3), each under zsh `time`.
- `sbt -batch check`, twice in one shell loop.
- No git, aws, or network commands. Nothing under labels/ was read or listed.

TEST RESULTS:
- sparkAnalytics: 23 succeeded, 0 failed (CanonicalGraphJobSpec 13, SparkSettingsSpec 2, ConnectedComponentsSpec 3, ResearcherAnalyticsSpec 5). About 40 s.
- New tests, with expected values worked out by hand in comments:
  1. **Five-node graph** (A to E, five DBLP works, plus one OpenAlex-only and one null-year row that must not count). Checks exact P, C, T, A, M, M_r work IDs, and W for 12 (window, researcher) rows, including the tie M_r works [w1, w4].
  2. **Two components plus an isolated node** in one window, and a second window reusing the IDs. Checks the roots, labels changed per round (5, 1, 0), and the iteration-cap failure.
  3. **4-clique {a,b,c,d} attached to the path d-e-f-g**, plus isolated h, and in a second window a triangle with a pendant node. Checks coreness 3/3/3/3/1/1/1/0 and 2/2/2/1, the degrees, and the peel log (0,1) (1,2) (1,1) (1,1) (2,3) (3,4).
  4. **k-core per-level cap** (cap 2, level 1 needs 3 rounds): the run fails.
  5. **End to end:** CanonicalGraphJob on work order 2's fixture, then this job. Checks the tables, 0 violations, component sizes summing to researchers, the C_r sum equal to twice the edges per window, the inputs list, and checkpoint cleanup.
- **sbt check run 1:** 151 / 94 / 23 / 70 succeeded, 0 failed. "All tests passed." four times; "markTestRun: tests passed, wrote …/.claude/.last-test-run"; "[success] Total time: 259 s".
- **sbt check run 2:** the same counts, 0 failed, marker written, "[success] Total time: 259 s".
- **Marker:** 2026-11-05T05:57:54.454350Z (23:57 local). The newest changed source is 23:40.

VALIDATION RESULTS:
- Attempts 2 and 3 have equal perWindow, metricDistributions, component histograms, largest components, coreness histogram, highest-coreness researchers, component iterations and changes per round, k-core rounds, levels, and round log, and integrityViolationsByCheck. Attempt 1 equals attempt 2 on all of these except the violation breakdown, which changed when I split the checks.
- Wall time, attempt 3: manifest 449.4 s; `time` 7:33.40. k-core 401.3 s, connected components 36.6 s, everything else under 4 s each.
- Free disk: 24,119,904 KiB before attempt 3; manifest readings 24,698,023,936 B before and 24,321,806,336 B after. It never came near the 5 GiB floor.
- The checkpoint directory is empty after each run.

ASSUMPTIONS (deviations, with reasons):
- **Metric universe = researchers with a DBLP publication in the window**, the same basis as job 2's DBLP-pair graph. This gives 191,318 researchers in all-years, which equals work order 2's DBLP-anchored count. OpenAlex-only and Crossref-only researchers get no metric row. DBLP publications (110,257 in all-years) count canonical works with at least one DBLP author mention, so 605 DBLP-anchored works without one are not counted.
- **M_r work IDs:** maximumTeamWorkIds holds the complete sorted list of tied works (at most P_r, max 359).
- **k-core round cap:** core `analytics.kcore-max-iterations` (100) caps rounds per level, not in total. The total is 304 rounds, which a total cap of 100 would have failed.
- **Checkpoint interval:** a reliable checkpoint every round, as in work order 2. Core `analytics.checkpoint-interval` (5) is not used.
- **Table shape:** researcher-metrics has no community or bridge columns yet; the community job adds them. graphDegree is an extra column used for a check.
- **Code version:** "a8077c6-dirty", the session-start snapshot, because I may not run git.
- **Refactor:** CanonicalGraphJob now runs through JobRun. It was re-verified only by its fixture tests, not by a new full-data run.

RISKS:
- **The 5 merged DBLP works** break W_r = T_r and coreness >= M_r for 12 and 9 researcher-window rows. They are flagged, not fixed. Whether two DBLP records should merge into one canonical work is an identity-policy question for entity-resolution-engineer, through the orchestrator.
- **k-core cost** is about 400 s locally (304 rounds, 23.7 GiB shuffled). It grows with the number of distinct coreness levels. EMR behaviour is unverified until cloud window 1.
- **Run IDs don't follow code changes:** the code-version string did not change between attempts, so they differ only by attempt number. Pass a real SHA once the work is committed.
- **Superseded run directories:** attempts 1 and 2 remain (42M each) and were produced by earlier code.

MANUAL ACTION REQUIRED: none. The human may remove attempts 1 and 2 if wanted.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer APPROVE

Not done: no mutation testing of the new tests, and no full-data rerun of CanonicalGraphJob after the JobRun refactor. Main risk: the merged-work identity exceptions, which are reported but not resolved.
````

## Work order 4: spark-engineer

````text
STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 6, work order 4. Spark jobs 5, 6, and 7: bounded path evidence and the sampled path-length estimate, label-propagation communities, and the three bridge measures, under human rulings 1 to 20.

Jobs 5, 6 and 7 are built, tested and run on the full data.
- **New job:** `CommunityPathJob`, stage `spark-community-path`.
- **Tests:** 29 module-local tests pass: the 23 earlier ones plus 6 new hand-checked fixture tests.
- **sbt check:** passed twice.
- **Full local run:** attempt 1, 296.1 s wall time, 0 integrity violations.
- **Main finding:** label propagation did not converge within the 10-round cap (ruling 8) in any window. This is recorded as not converged, not treated as a failure; the per-round counts are under METRIC DISTRIBUTIONS.

**Paths work order 5 must read (attempt 1):**
- Run directory: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-25c1603ffac829d10c6087789c49f958/
- Selected-pairs file: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-25c1603ffac829d10c6087789c49f958/paths/selected-pairs.csv
- Inputs it was built from (unchanged):
  - work order 3: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-65981c133fda5db503b0e2475e30367a/
  - work order 2: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-7488e94af55d68e29273be0617151eb8/

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/ShortestPaths.scala (job 5: bounded BFS, rebuilding each path, the sampled distance estimate)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/Communities.scala (job 6 `LabelPropagation`, job 7 `Bridges`)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/CommunityPathJob.scala (the job, its tables, invariant checks, manifest counts, pairs-file parser)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/CommunityPathSpec.scala (6 tests)
- The run directory above (84M).

FILES MODIFIED:
- modules/spark/src/main/scala/scholarchain/spark/SparkSettings.scala:
  - new flags `--pairs` (optional), `--community-max-iterations`, `--path-max-depth`, `--path-sample-pairs`, `--path-sample-sources`, `--sample-seed`;
  - each must be >= 1.
- modules/spark/src/main/resources/scholarchain-spark.conf: defaults 10, 6, 50, 20, and seed 20261003.
- modules/spark/src/main/scala/scholarchain/spark/JobRun.scala:
  - Added `sizeBucket` and `distributions`, moved out of ResearcherAnalyticsJob.
  - Bug fix in `write`: an empty partitioned table leaves no part file, so reading it back failed. It now returns an empty frame with the written schema. The fixture's empty cross-community-edges table exposed this.
- modules/spark/src/main/scala/scholarchain/spark/ResearcherAnalyticsJob.scala: uses the two moved helpers. Same output.
- modules/spark/src/test/scala/scholarchain/spark/{CanonicalGraphJobSpec,ResearcherAnalyticsSpec}.scala: the new settings fields.
- modules/spark/src/test/scala/scholarchain/spark/SparkSettingsSpec.scala: asserts the new defaults, `--pairs`, and a refusal of depth 0.
- modules/spark/JOBS.md:
  - new section "CommunityPathJob (spec Phase 6 jobs 5, 6, and 7)": definitions, parameters, approximation and limitations for every algorithm and bridge measure, output tables, shuffle boundaries;
  - corrected the work order 3 table note.

FILES DELETED: only this run's temporary checkpoints, scratch/spark/checkpoints/run-25c1603ffac829d10c6087789c49f958/, which the job deletes itself. The checkpoints directory is now empty. Nothing under scratch/hadoop/ and none of the earlier run directories were touched.

STAGE DAGS AND SHUFFLE BOUNDARIES (from the manifest `stagesByStep`):
- **Totals:** 538 stages, 11,366 tasks, shuffle read 23,742,258,819 B, shuffle write 23,715,096,380 B, spill 0.

| Step | Stages | Tasks | Max tasks in a stage | Shuffle read / write (MiB) | Stage time (s) |
|---|---|---|---|---|---|
| read inputs | 18 | 102 | 20 | 212.1 / 212.1 | 3.9 |
| select pairs | 6 | 17 | 10 | 0.1 / 0 | 0.5 |
| bounded paths | 89 | 1,123 | 55 | 3,819.3 / 3,819.3 | 42.0 |
| write path-evidence | 21 | 661 | 55 | 234.0 | 6.3 |
| path-length estimate | 204 | 7,200 | 267 | 11,194.3 / 11,194.3 | 254.8 |
| write path-length-estimate | 13 | 549 | 267 | 1.1 | 5.3 |
| label propagation | 94 | 1,158 | 25 | 5,468.5 / 5,468.5 | 55.7 |
| write communities | 21 | 187 | 12 | 482.4 | 15.6 |
| write cross-community-edges | 10 | 71 | 12 | 442.7 | 4.3 |
| write researcher-communities | 13 | 116 | 20 | 706.5 | 7.2 |
| write violations | 7 | 48 | 24 | 29.5 | 0.6 |
| report | 42 | 134 | 10 | 52.0 / 26.1 | 1.2 |

Where the shuffles happen:
- **BFS, each round:**
  - the frontier-edge join on (window, node);
  - an anti-join against visited on (window, source, node);
  - a min-parent group-by;
  - a pending-target filter, done as a broadcast semi-join.
- **Path walk-back:** one broadcast join per hop, so the visited table is never shuffled. Then the hop-to-edge join on (window, researcherA, researcherB) and a group-by per pair.
- **Label propagation, each round:**
  - the edge-label join on (window, src);
  - the vote count group-by on (window, node, label);
  - the winner group-by `min_by` on (window, node);
  - the old/new label join.
- **Bridges:** the neighbour-label join on (window, dst), then group-bys on (window, node, community) and on (window, node).
- **Communities:** two endpoint-label joins and group-bys on (window, label).
- Full detail is in JOBS.md.

PARTITION COUNTS AND SKEW:
- **Settings:**
  - local[*], 10 cores; `spark.sql.shuffle.partitions = 40`, set explicitly; AQE on.
  - Driver heap 6,442,450,944 B against 17,179,869,184 B RAM (`sysctl -n hw.memsize`), under half.
- **Write partitions (files, smallest to largest):**
  - researcher-communities: 8 per window, 24 files, 776,189 to 2,255,328 B, hashed on researcherId.
  - communities: 1 per window, 3 files, 1,549,124 to 3,848,443 B.
  - cross-community-edges: 1 per window, 3 files, 5,225,368 to 22,086,093 B.
  - path-evidence: 1 file, 51,403 B.
  - path-length-estimate: 1 file, 11,494 B.
- **Skew:**
  - The path-length estimate step peaked at 267 tasks in one stage. Its BFS frontier peaks at 1.8M (source, node) rows in round 6. Hubs reach most of the giant component within 5 to 6 hops.
  - The hub Ian T. Foster (degree 770) has crossCommunityDegree 705.
  - Spill: 0 B.

GRAPH COUNTS:
- **Per window:**

| Window | Researchers | Coauthor edges | Communities | Largest community | Largest fraction | Singleton communities | Internal edges | Cross-community edges |
|---|---|---|---|---|---|---|---|---|
| all-years 2000-2026 | 191,318 | 787,726 | 38,034 | 621 | 0.00325 | 5,137 | 521,398 | 266,328 |
| 2000-2014 | 66,350 | 217,957 | 15,312 | 323 | 0.00487 | 3,247 | 150,572 | 67,385 |
| 2015-2026 | 139,432 | 585,071 | 26,394 | 400 | 0.00287 | 1,971 | 409,101 | 175,970 |

- Internal plus cross-community edges equal the coauthor edges in every window.
- The researcher and edge counts match work order 3.
- Components, largest components and singleton researchers are unchanged from work order 3: largest components 141,367 / 46,062 / 98,812.
- **Publications (ruling 19):** 110,257 / 45,522 / 64,735, from work order 3. This job adds none.
- **Table rows:**
  - path-evidence 50
  - path-length-estimate 3
  - communities 79,740
  - cross-community-edges 509,683
  - researcher-communities 397,100 (equal to work order 3's researcher-metrics)
  - community-path-violations 0

ALGORITHMS (full text in modules/spark/JOBS.md):
- **Job 5, bounded paths** (`ShortestPaths.paths`/`bfs`):
  - Definition: multi-source BFS keyed by (window, source, node). Each newly reached node keeps the smallest neighbour from the previous level as its parent. A source stops once all its targets are reached.
  - Each path is rebuilt through parents. It is a shortest path, and the same input always gives the same one.
  - Each hop carries edgeId, jointPublicationCount, the first and last year, and supportingWorkIds (the edge's complete representativeWorkIds).
  - Status: FOUND, UNREACHABLE (different componentId), BEYOND_DEPTH (same component, longer than the limit), or UNKNOWN_RESEARCHER.
  - Parameters: path-max-depth 6, path-sample-pairs 50, seed 20261003.
  - Approximation: none.
  - Limitations: only one path is kept when several tie; hop count ignores edge weights; cost grows with the size of the 6-hop neighbourhood.
- **Job 5, sampled estimate** (`sampledDistances`/`estimate`):
  - Definition: the 20 members of each window's largest component ranked first by H(seed, "bfs-source", window, id) are the sources. An exact BFS runs from each until nothing new is reached; the run fails beyond cc-max-iterations = 60 rounds.
  - It reports the mean distance, the standard error over sources, the median, the 90% effective diameter, the largest observed distance (a lower bound on the diameter), and the distance histogram.
  - Approximation: only the sources are sampled.
  - Limitations: 20 sources; the hash ranking stands in for a uniform draw. D-10 deferred effective-diameter estimation; ruling 11 asks for it, and this is the sampled form.
- **Job 6, label propagation:**
  - Definition: synchronous label propagation. A node's own label counts as one vote, so a pair or bipartite pattern cannot swap labels forever. Ties go to the smallest label.
  - Edges are unweighted. The cap is community-max-iterations = 10; reaching it is recorded as not converged.
  - communityId = "comm-" + H("community", graph policy, window, start, end, label).
  - Approximation: none, but the method optimises no quality measure such as modularity.
  - Limitations: depends on the tie-break; communities are not comparable across windows; hubs can merge weakly linked groups.
- **Job 7, bridge measures** (`Bridges`):
  - crossCommunityDegree: coauthors in another community.
  - communitiesConnected: distinct communities among the coauthors.
  - participationCoefficient = 1 - sum over s of (k_rs/k_r)^2, and 0 for k_r = 0.
  - Approximation: none.
  - Limitations: inherit the community assignment; unweighted; local (one hop); cross-community degree favours hubs; the participation coefficient is noisy at low degree. Approximate betweenness is deferred (D-10).

METRIC DISTRIBUTIONS:
- **Path tests (all-years, 50 seeded pairs):** FOUND 37, BEYOND_DEPTH 13, UNREACHABLE 0, UNKNOWN_RESEARCHER 0. Every pair comes from the largest component, so none can be unreachable; the fixture covers the unreachable case.
- **Found path length histogram:** 3: 1, 4: 2, 5: 21, 6: 13.
- **BFS nodes reached per round:** 463, 9,745, 135,527, 817,599, 1,911,603, 1,124,271.
- **Sampled estimate (20 sources, seed 20261003):**

| Window | Largest component | Sampled pairs | Mean distance (+/- std error) | Median | Effective diameter 90% | Max observed |
|---|---|---|---|---|---|---|
| all-years | 141,367 | 2,827,320 | 5.786 +/- 0.116 | 6 | 7 | 16 |
| 2000-2014 | 46,062 | 921,220 | 6.333 +/- 0.328 | 6 | 9 | 22 |
| 2015-2026 | 98,812 | 1,976,220 | 6.326 +/- 0.225 | 6 | 8 | 20 |

  The full histograms are in the manifest and in paths/path-length-estimate. The estimate's BFS needed 23 rounds; the last round reached 0.
- **Communities, labels changed per round (10 rounds, converged false in every window):**
  - all-years: 148226, 34822, 9627, 4947, 2462, 1408, 948, 578, 481, 387
  - 2000-2014: 48723, 12652, 3362, 1596, 777, 393, 237, 156, 111, 90
  - 2015-2026: 110666, 21769, 6191, 3253, 1685, 907, 540, 327, 256, 173

  In round 10, 387 of 191,318 nodes (0.2%) still changed in all-years.
- **Community size histogram (1 / 2 / 3-5 / 6-10 / 11-100 / 101-1000):**
  - all-years: 5,137 / 9,262 / 15,274 / 5,642 / 2,650 / 69
  - 2000-2014: 3,247 / 3,990 / 5,280 / 1,820 / 960 / 15
  - 2015-2026: 1,971 / 6,108 / 11,775 / 4,527 / 1,979 / 34
- **Bridge measures (min / median / p90 / p99 / max, exact percentile):**

| Measure | all-years | 2000-2014 | 2015-2026 |
|---|---|---|---|
| crossCommunityDegree | 0/1/6/38/705 | 0/1/4/27/298 | 0/1/5/32/341 |
| communitiesConnected | 0/2/5/18/184 | 0/1/4/13/78 | 0/2/4/15/118 |
| participationCoefficient | 0/0.312/0.714/0.880/0.981 | 0/0/0.667/0.852/0.953 | 0/0.245/0.688/0.866/0.976 |
| communitySize | 1/7/62/437/621 | 1/6/41/231/323 | 1/7/48/321/400 |

- **Researchers with a cross-community edge:** 108,648 of 191,318 (all-years); 33,552 of 66,350; 76,251 of 139,432.
- **Top cross-community degree (all-years):** Ian T. Foster 705 of degree 770; Albert Y. Zomaya 443; Jack J. Dongarra 436; Martin Schulz 0001 432; Guihai Chen 429.
- **Top participation coefficient (all-years):** Kuan-Ching Li 0.981 (degree 232); Xiaosong Ma 0.978; Shui Yu 0001 0.977; Sajal K. Das 0001 0.976; Joel J. P. C. Rodrigues 0.974.

TEMPORAL WINDOWS: all-years 2000-2026, 2000-2014 and 2015-2026, from the core `graph.windows`.
- The job fails if they differ from the input run's windows.
- Every table carries window, windowStartYear and windowEndYear, and is partitioned by window, except path-length-estimate (one row per window).
- The generated pairs are in all-years only (it holds the largest component). A `--pairs` file can name any window.

INTEGRITY VIOLATIONS: analytics/community-path-violations has 0 rows. The four checks:
- COMMUNITY_RESEARCHER_MISMATCH: a full join against researcher-metrics, including a degree check against graphDegree;
- BRIDGE_INVARIANT;
- PATH_HOP_EDGE_MISSING;
- PATH_LENGTH_MISMATCH.

OUTPUT PATHS AND LOAD MANIFESTS (under the run directory above):
- `_manifest.json` (spark-run-manifest-v1): inputs with row counts, the tables (path, schema, rows, partitioning, file sizes, targets), counts, step seconds, stage summaries, disk, and `selectedPairsFile`. `_stages.json` and `_started.json` sit beside it.
- `paths/selected-pairs.csv`: 50 pairs plus the header `window,sourceResearcherId,targetResearcherId`.
- Tables and intended targets:
  - `paths/path-evidence`: neptune, dynamodb, s3
  - `paths/path-length-estimate`: dynamodb, s3
  - `analytics/communities`: neptune, dynamodb, s3
  - `analytics/cross-community-edges`: neptune, s3
  - `analytics/researcher-communities`: neptune, dynamodb, s3
  - `analytics/community-path-violations`: s3
- Run ID run-25c1603ffac829d10c6087789c49f958, attempt 1. Its fingerprints are:
  - both input run IDs and their sizes;
  - the graph policy and the windows;
  - community-max-iterations 10, path-max-depth 6, path-sample-sources 20, seed 20261003, cc-max-iterations 60;
  - pairs=sample:50.
- To add the benchmark pairs later, rerun only this job with `--pairs <csv>`.

COMMANDS RUN:
- Reads: cat, sed, grep, ls, find, du; `df -k .`; `sysctl -n hw.memsize hw.ncpu`; python3 to read manifests.
- `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/Test/compile`
- `sbt -batch sparkAnalytics/test`: 1 failure, the empty partitioned read-back, fixed in JobRun.
- `sbt -batch sparkAnalytics/scalafmtAll "sparkAnalytics/testOnly scholarchain.spark.CommunityPathSpec"`
- `df -k .` before the full run: 23,318,488 KiB free.
- `time sbt -batch "sparkAnalytics/runMain scholarchain.spark.CommunityPathJob --input scratch/spark/snapshot=2026-10-03/run=run-65981c133fda5db503b0e2475e30367a --code-version 84d90b0-dirty --shuffle-partitions 40"`: exit 0, `[success] Total time: 298 s`, zsh time 5:00.02 total.
- `sbt -batch check`, twice in one shell loop.
- No git, aws, or network commands. Nothing under labels/ was read or listed.

TEST RESULTS:
- **sparkAnalytics:** 29 succeeded, 0 failed. CommunityPathSpec 6/6 (about 24 s).
- **New tests, with expected values worked out by hand in comments:**
  1. Path graph p1..p9 (8 hops), a separate pair q1-q2, and a diamond s-a/b-t, at depth 6:
     - p1->p4 FOUND 3, and p4->p1 FOUND 3 in reverse, with each hop's canonical edgeId and supporting work;
     - p1->p7 FOUND 6, exactly at the limit;
     - p1->p9 BEYOND_DEPTH;
     - p2->q1 UNREACHABLE;
     - p1->zz UNKNOWN_RESEARCHER;
     - p3->p3 FOUND 0 with no hops;
     - s->t FOUND [s,a,t], the parent tie-break;
     - BFS nodes reached per round [5,4,3,1,1,1].
  2. Sampled estimate on a 4-node path:
     - 6 pairs, mean 10/6, standard error 1/3, median 1, effective diameter 3, max 3;
     - histogram {1:3, 2:2, 3:1}; nodes reached per round [3,2,1,0];
     - the round cap fails the run.
  3. Two 4-cliques joined through bridge researcher x, plus isolated i (the separate tie-break star is in test 4):
     - communities {a1..a4, x} and {b1..b4};
     - changes per round [7, 0];
     - x has cross-community degree 2 and participation coefficient 0.5, strictly highest; b1 and b2 have 1 and 0.375; all others 0;
     - isolated i: 0, 0, 0.
  4. Tie-break star m-{k,n,z}:
     - all nodes end with label k; changes per round [3, 2, 0];
     - the labels are identical under reversed and flipped edge order and under 1 or 5 partitions;
     - with cap 1, the round-1 labels are kept.
  5. Pairs-file validation: header, field count, window, researcher ID, and duplicate removal.
  6. End to end:
     - Ran CanonicalGraphJob, then ResearcherAnalyticsJob, then CommunityPathJob on the work order 2 fixture: the 6 tables, 0 violations, community rows = metric rows, community sizes summing to the researchers, generated pairs = floor(largest component / 2) all FOUND, 3 estimate rows, checkpoints removed.
     - A rerun with a `--pairs` file gives a new run ID and statuses FOUND and UNKNOWN_RESEARCHER.
- **sbt check run 1:** 151 / 94 / 29 / 70 succeeded, 0 failed. "All tests passed." four times; "markTestRun: tests passed, wrote …/.claude/.last-test-run"; "[success] Total time: 259 s".
- **sbt check run 2:** the same counts, 0 failed, the marker written, "[success] Total time: 259 s".
- **Marker:** 2026-11-05T06:28:35.331308Z (00:28 local). The last source edit was before the 00:12 full run, and only JOBS.md changed after that.

VALIDATION RESULTS:
- researcher-communities has 397,100 rows, equal to researcher-metrics. COMMUNITY_RESEARCHER_MISMATCH = 0, so every degree equals work order 3's graphDegree.
- Internal plus cross edges equal the coauthor edges in each window: 521,398 + 266,328 = 787,726; 150,572 + 67,385 = 217,957; 409,101 + 175,970 = 585,071.
- 509,683 cross-community edge rows = 266,328 + 67,385 + 175,970.
- Disk: manifest 23,877,304,320 B free before and 22,979,059,712 B after; `df` afterwards 23,220,228 KiB. It never came near the 5 GiB floor.
- The checkpoints directory is empty after the run.

ASSUMPTIONS (deviations, with reasons):
- **Communities cap:** community-max-iterations = 10 comes from scholarchain-spark.conf (ruling 8 default; ruling 15 puts Spark settings there). The core `analytics.community-max-iterations` = 20 is not read. Path depth 6 is a spark setting; the core `retrieval.max-path-depth` (also 6) is a runtime bound and is not read.
- **Self vote:** label propagation counts a node's own label as one vote, which stops pair and bipartite label swaps. This is still synchronous with the smallest-label tie-break (ruling 8). Edges are unweighted.
- **Generated pairs:** 50 pairs from the largest component of the first window (all-years), not 50 per window.
- **Path-length estimate:** computed for every window's largest component, not only one.
- **Membership storage:** membership and bridge measures are stored in `analytics/researcher-communities` (core ResearcherMetrics field names). researcher-metrics is not rewritten; work order 5 can join on (window, researcherId).
- **Code version:** "84d90b0-dirty", from the session-start git snapshot, because I may not run git.
- **Input chain:** the job takes the work order 3 run as `--input` and finds the work order 2 run through that run's manifest `input.path`. The path there is relative to the repository root.

RISKS:
- **Not converged:** label propagation did not converge in 10 rounds in any window (387 / 90 / 173 nodes still changing in round 10). The communities are a valid fixed-cap result but not a fixed point. Raising the cap is a human or configuration decision.
- **Small communities:** the communities are small (the largest holds 0.3% to 0.5% of researchers). That is typical of label propagation on sparse coauthor graphs. Bridge values depend directly on it.
- **Beyond depth:** 13 of 50 sampled pairs exceed depth 6, consistent with an effective diameter of 7. Runtime path queries at depth 6 will often return BEYOND_DEPTH.
- **Determinism on full data:** not shown by a second full-data attempt (I did not rerun, to save about 5 minutes). It is shown only by the fixture tests (partition and order independence, and same input giving the same path).
- **Estimate cost:** the path-length estimate dominates the cost (255 s, 11 GiB shuffled). EMR behaviour is unverified until cloud window 1.
- **Empty tables:** an empty partitioned table has no files on disk. Downstream readers must handle that, using the manifest's rowCount 0.

MANUAL ACTION REQUIRED: none. Optional:
- decide whether the label-propagation cap should be raised;
- ask domain-engineer to align the core `analytics.community-max-iterations` (20) with ruling 8 (10).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer APPROVE

Not done: no second full-data attempt to check determinism on the real data, and no mutation testing of the new tests. The main risk is that label propagation did not converge within the ruled cap of 10 rounds.
````

## Work order 5: spark-engineer

````text
STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 6, work order 5. Jobs 9, 10 and 11 (temporal analysis, summaries, export), the end-to-end driver and full local run, and completing JOBS.md, under human rulings 1 to 20.

Jobs 9, 10 and 11 are built and tested, the end-to-end driver works, and a full local run finished. Its counts equal the staged runs from work orders 2 to 4 exactly. `sbt check` passed twice (151 / 94 / 32 / 70 succeeded, 0 failed, each time).

**Where things are:**
- Successful pipeline run (attempt 2): /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-eb4284f015763055514b0dacf41132b4/_manifest.json
- Load manifest: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-be3b1cfe152896b20a54933ffb5af9e6/export/load-manifest.json

**Decision for you:** ruling 12 has Spark write template text, but my agent law 7 and ownership exception 5 give text rendering to the rag-engineer. I followed the ruling. Each summary stores the structured inputs as `facts` and a deterministic rendering as `text` (template version `summary-template-v1`). The rag-engineer can replace the rendering without changing the facts. Please decide whether that split is acceptable.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/Temporal.scala (job 9)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/Summaries.scala (job 10)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/TemporalSummaryExportJob.scala (jobs 9 to 11, stage `spark-temporal-summary-export`)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/PipelineJob.scala (end-to-end driver, stage `spark-pipeline`)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/TemporalSummarySpec.scala (3 tests)
- Run directories under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/:
  - **Successful pipeline** run=run-eb4284f015763055514b0dacf41132b4 (attempt 2, 2.1M). Its stages:
    - jobs 1-2: run-0efdd4c59c16686e7913348721178b21 (attempt 2, 410M)
    - jobs 3, 4, 8: run-6a8784d8c6ca920baba60e361c2ddd7a (84M is jobs 5-7; this one is 42M)
    - jobs 5-7: run-130c679509d6cb10a4b7b425ca019dae (84M)
    - jobs 9-11: run-be3b1cfe152896b20a54933ffb5af9e6 (153M)
  - **Failed first pipeline attempt:**
    - pipeline run-da3b7ba8c6e8f34cf69369a23f6d6df1: incomplete, only `_started.json`
    - three complete stage runs: run-cee5a87f7af7bc7d35e8f31e0ef79cea, run-45b7fe8ab08ae9addfc72f02426233cd, run-6d5a0589a9b7b37d4298524f2949988a
    - jobs 9-11 run-94ea21887ee45f3e20ee1df03b5f9b00: incomplete, 14M, no `_manifest.json`
  - **Standalone jobs 9-11** check of the memory fix: run-b794ac865c62e10d3ffd920cfa0a1324 (complete, 153M)

FILES MODIFIED (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/):
- src/main/scala/scholarchain/spark/JobRun.scala: added a read-only `writtenTables` accessor.
- src/main/scala/scholarchain/spark/CanonicalGraphJob.scala: moved the snapshot lookup into `dblpSnapshot`, which the driver reuses. Behaviour is unchanged.
- src/main/scala/scholarchain/spark/SparkSettings.scala and src/main/resources/scholarchain-spark.conf: new settings `summary-min-publications` (2) and `summary-min-community-size` (3), each must be at least 1.
- src/test/scala/scholarchain/spark/{CanonicalGraphJobSpec,CommunityPathSpec,ResearcherAnalyticsSpec}.scala: the two new constructor arguments.
- src/test/scala/scholarchain/spark/SparkSettingsSpec.scala: asserts the new defaults.
- JOBS.md: new sections for TemporalSummaryExportJob (definitions, limitations, outputs, checks, shuffles), PipelineJob, running locally, the later EMR run with s3a paths, and a job index with inputs, outputs, partitioning, checkpoints, broadcasts and persistence for every job. It is now 328 lines.

FILES DELETED: the failed attempt's own checkpoint directories, scratch/spark/checkpoints/run-94ea21887ee45f3e20ee1df03b5f9b00 and scratch/spark/checkpoints/run-da3b7ba8c6e8f34cf69369a23f6d6df1. The checkpoints directory is now empty. Nothing under scratch/hadoop/ and none of work orders 2 to 4's run directories were touched.

STAGE DAGS AND SHUFFLE BOUNDARIES (successful pipeline, from each stage manifest's `stagesByStep`):

| Stage | Stages | Tasks | Shuffle read / write (MiB) | Spill (B) | Wall (s) |
|---|---|---|---|---|---|
| jobs 1-2 | 685 | 6,072 | 11,089.9 / 10,328.7 | 17,226,215 | 160.0 |
| jobs 3, 4, 8 | 3,750 | 31,322 | 29,438.9 / 29,438.8 | 0 | 422.0 |
| jobs 5-7 | 537 | 11,354 | 22,442.2 / 22,416.3 | 0 | 244.6 |
| jobs 9-11 | 395 | 2,128 | 1,640.8 / 1,640.8 | 0 | 26.2 |

- **Pipeline totals:** 5,367 stages, 50,876 tasks; shuffle read 67,750,362,031 B, write 66,924,990,906 B; wall 853.2 s (manifest), zsh `time` 14:17.04.
- **Spill:** all of it is in "write graph/coauthor-edges". Work order 2's attempt 3 also spilled at that step (1,281,424 B).
- **Jobs 9-11 by step:**
  - writing summaries: 116 stages, 1,111 tasks, 1,441,074,017 B shuffled, 17.1 s wall
  - writing researcher-changes: 34 stages, 192,105,985 B
  - window-summary: 24 stages, 36,311,394 B
  - community-transitions: 17 stages, 43,463,409 B
  - export re-counts: 96 stages, 8,786 B
- **Job 9 shuffles:** the full outer join on researcherId; the edge join on (researcherA, researcherB) and its group-by; the group-by on (from community, to community) and its join back; six window group-bys.
- **Job 10 shuffles:** for each top-N list, a `row_number` window on the subject key and a `collect_list` group-by; left joins on the subject key.
- **Job 11:** a Parquet count per table, no data shuffle.

PARTITION COUNTS AND SKEW:
- local[*], 10 cores; `spark.sql.shuffle.partitions = 40`, set explicitly; AQE on.
- Driver heap 6,442,450,944 B against `sysctl -n hw.memsize` 17,179,869,184 B.
- **New tables:**
  - researcher-changes: 8 files hashed on researcherId, 1,771,661 to 1,809,046 B.
  - semantic-summaries: 2 partitions on subjectId x (documentType, window), giving 16 files of 46,744 to 49,459,411 B. The spread follows document type: profiles are 54,935 rows of up to 2,029 characters.
  - Every other new table: 1 file.
- Hubs and giant teams are unchanged from work orders 2 to 4 (max coreness and M_r 286; largest components as below).

GRAPH COUNTS (per window, from `temporal/window-summary` of the end-to-end run; ruling 19):

| Window | Publications | Canonical researchers (graph) | Coauthor edges | Components | Largest component | Largest fraction | Singleton researchers | Communities | Largest community | Communities of 3+ |
|---|---|---|---|---|---|---|---|---|---|---|
| all-years 2000-2026 | 110,257 | 191,318 | 787,726 | 16,091 | 141,367 | 0.7389 | 4,849 | 38,034 | 621 | 23,635 |
| 2000-2014 | 45,522 | 66,350 | 217,957 | 7,709 | 46,062 | 0.6942 | 3,119 | 15,312 | 323 | 8,075 |
| 2015-2026 | 64,735 | 139,432 | 585,071 | 10,746 | 98,812 | 0.7087 | 1,798 | 26,394 | 400 | 18,315 |

- Publications per year: 4,083.6 / 3,034.8 / 5,394.6. Sum of joint publications: 1,019,039 / 297,320 / 721,719.
- The canonical universe is unchanged: the jobs 1-2 counts equal work order 2's. That is 259,057 researchers in total; per window 259,057 / 91,101 / 184,230 active across all sources (191,318 / 66,912 / 139,523 DBLP-anchored).

ALGORITHMS (full text in JOBS.md):
- **Job 9, temporal comparison:**
  - Definition: compares ordered pairs of windows that do not overlap. With D-10 that is 2000-2014 -> 2015-2026; all-years is summarised only.
  - Per researcher: presence (BOTH, FROM_ONLY, TO_ONLY); from/to and delta of C_r, A_r, M_r, coreness, cross-community degree and participation coefficient; retainedCoauthors; largestComponentTransition (IN, OUT, ABSENT); crossCommunityTransition.
  - communityRetention = (s-1)/(n-1): the share of the earlier community's co-members who share the researcher's later community.
  - Parameters: the configured windows. Approximation: none.
  - Limitations:
    - Component and community IDs are per window and never compared directly.
    - The inclusion limitation is stated in the manifest and on each window-comparison row: the bounded subset's coverage and enrichment may differ by period; the windows are 15 and 12 years; the last year may be partial; communities come from a capped label propagation.
- **Job 10, template summaries:**
  - Definition: every value in the text comes from computed tables or source records. Lists are top 3 (top 5 members) with explicit orderings and ID tie-breaks.
  - IDs: summaryId = StableIds.chunkId(subject|window, template version, documentType, None, textHash). The test checks this against core StableIds.
  - supportingIds lists every record the text names; vectorIndex comes from the core s3-vectors indexes.
  - Approximation: none.
  - Limitations: fixed English phrasing ("1 joint works"); institutions are not windowed; topics exist only for works with an OpenAlex match.
- **Job 11, export:** no copy. The load manifest lists 30 tables with logical names in the spec layout, schema, rowCount, a re-read verifiedRowCount, and intended targets, plus per-store lists.
- **PipelineJob:** runs the four stages in one SparkSession and clears the cache before each one.

METRIC DISTRIBUTIONS:
- **Jobs 3 to 8:** identical to work orders 3 and 4. Every manifest `counts` key is equal: 13 keys for jobs 3, 4, 8 and 17 for jobs 5-7.
- **Job 9, comparison 2000-2014 -> 2015-2026:**
  - Researchers: 66,350 from, 139,432 to; 14,464 in both, 51,886 from-only, 124,968 to-only. 9,538 kept at least one coauthor.
  - Community retention: mean 0.0960, median 0 (p90 0.333, p99 1).
  - Largest component: IN->IN 10,983; IN->OUT 1,136; OUT->IN 1,527; OUT->OUT 818.
  - Cross-community: became 2,738, stopped 1,869, stayed 8,252, never 1,605.
  - Means over researchers in both windows: C_r 11.94 -> 17.97; A_r 3.80 -> 4.74; M_r 5.70 -> 7.19; coreness 5.96 -> 7.59; cross-community degree 5.41 -> 9.36; participation coefficient 0.395 -> 0.456.
  - Deltas (min / median / p90 / p99 / max):
    - C_r: -239 / 1 / 26 / 108 / 415
    - retained coauthors: 0 / 1 / 5 / 16 / 60
  - deltaCoreness and deltaM_r have identical five-number summaries (-112 / 1 / 7 / 32.37 / 124). The unit test now proves the two columns are computed separately (researcher d: deltaM 2, deltaCoreness 1). The equality reflects coreness = M_r for most researchers, which work order 3's distributions already showed.
- **Job 10, summary counts:**
  - researcher profiles: 54,935
  - communities of 3 or more: 50,025 (23,635 all-years, 8,075 for 2000-2014, 18,315 for 2015-2026)
  - paths: 50
  - topics: 2,969 (1,207 / 685 / 1,077)
  - Total 107,979.
  - Text length: profiles 664 to 2,029 characters; communities 343 to 756; paths 128 to 977; topics 238 to 458.

PATH-TEST RESULTS: equal to work order 4. FOUND 37, BEYOND_DEPTH 13, UNREACHABLE 0, UNKNOWN_RESEARCHER 0. Found lengths: 3: 1, 4: 2, 5: 21, 6: 13. The new paths/selected-pairs.csv is byte-identical to work order 4's (`cmp`). The path-length estimate is unchanged.

TEMPORAL WINDOWS: all-years 2000-2026, 2000-2014 and 2015-2026, from the core `graph.windows`.
- The window summary and every summary row carry window, windowStartYear and windowEndYear.
- Comparison rows carry from/to window names and years.
- Summaries are partitioned by (documentType, window).

INTEGRITY VIOLATIONS:
- jobs 1-2: 0.
- jobs 3, 4, 8: 21. WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES 12 and CORENESS_BELOW_MAX_TEAM 9, the same 21 as work order 3, all on the 5 merged DBLP works.
- jobs 5-7: 0.
- jobs 9-11: 0 across SUMMARY_MISSING, SUMMARY_TEXT_EMPTY, TEMPORAL_PRESENCE_MISMATCH and EXPORT_ROWCOUNT_MISMATCH. All 30 exported tables re-read to their manifest row counts.

OUTPUT PATHS AND LOAD MANIFESTS:
- Pipeline manifest: lists the four stage runs, the load manifest path, perWindow, and summaries by type and window.
- Load manifest (`spark-export-manifest-v1`): /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/spark/snapshot=2026-10-03/run=run-be3b1cfe152896b20a54933ffb5af9e6/export/load-manifest.json. Stores: neptune 14 tables, dynamodb 13, s3vectors 1 (semantic-summaries), s3 26.
- New tables (rows): temporal/window-summary 3, temporal/window-comparison 1, temporal/researcher-changes 191,318, temporal/community-transitions 11,140, summaries/semantic-summaries 107,979, summary-export-violations 0.
- Store-specific formats are left to Phase 7+8 (ruling 13).

COMMANDS RUN:
- Reads with cat, sed, grep, find, ls, du; `sysctl -n hw.memsize hw.ncpu`; `df -k .` before each full run; python3 to read manifests; `cmp`.
- `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/Test/compile`.
- `sbt -batch sparkAnalytics/scalafmtAll "sparkAnalytics/testOnly scholarchain.spark.TemporalSummarySpec"`: run 1 failed (an empty upstream table read; fixed), run 2 passed 3/3.
- `sbt -batch sparkAnalytics/test`: 32/32 passed.
- `time sbt -batch "sparkAnalytics/runMain scholarchain.spark.PipelineJob --input scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d --code-version 84d90b0-dirty --shuffle-partitions 40"`:
  - Attempt 1 failed after 908 s with exit 1: SparkOutOfMemoryError UNABLE_TO_ACQUIRE_MEMORY in a sort-based aggregate while writing summaries (TemporalSummaryExportJob.scala:430). Jobs 1 to 8 had completed.
  - Fix: this job's inputs now persist DISK_ONLY, and the pipeline clears the cache before each stage.
  - Verified with standalone `sbt -batch "sparkAnalytics/runMain scholarchain.spark.TemporalSummaryExportJob --input scratch/spark/snapshot=2026-10-03/run=run-6d5a0589a9b7b37d4298524f2949988a --code-version 84d90b0-dirty --shuffle-partitions 40"`: exit 0, 38.7 s.
  - Attempt 2: exit 0, `[success] Total time: 855 s`.
- `python3 -I compare.py` (scratchpad): stage counts against the staged runs. Output "ALL EQUAL" (differences 0 for 28 / 13 / 17 count keys and 15 / 3 / 6 tables, plus input row counts).
- `sbt -batch sparkAnalytics/scalafmtAll "sparkAnalytics/testOnly scholarchain.spark.TemporalSummarySpec scholarchain.spark.SparkSettingsSpec"`: 5/5 passed.
- `sbt -batch check`, twice in one shell loop.
- `rm -r` of the two failed-run checkpoint directories named above.
- No git, aws or network commands. Nothing under labels/ was read or listed.

TEST RESULTS:
- sparkAnalytics: 32 succeeded, 0 failed. That is 29 earlier tests plus the new TemporalSummarySpec 3:
  1. **Job 9 hand-worked fixture.** Windows e and l; all overlaps both and is excluded. Checks presence, retained coauthors, the largest-component and cross-community transitions, retention 0.5/0.5/null, the C, M and coreness deltas, community transitions, and the comparison aggregates (4/4/3/1/1, mean C 4/3 -> 5/3).
  2. **Job 10.** Exact hand-rendered text for one profile, one community, three paths (FOUND, BEYOND_DEPTH, UNKNOWN_RESEARCHER) and one topic. Also checks textHash and summaryId against core StableIds, the supportingIds set, the facts JSON, and the size threshold.
  3. **End to end.** PipelineJob on the work order 2 fixture: four stages in order; 30 load-manifest tables, every verified count equal; the s3vectors list; 0 violations; summary coverage for profiles, communities and paths; window-summary researchers equal to the metric rows. A rerun of jobs 9 to 11 gets attempt 2 with identical summaryIds.
- **sbt check run 1:** 151 / 94 / 32 / 70 succeeded, 0 failed, "All tests passed." four times. "markTestRun: tests passed, wrote …/.claude/.last-test-run". "[success] Total time: 258 s".
- **sbt check run 2:** the same counts, 0 failed, marker written, "[success] Total time: 259 s".
- **Marker:** 2026-11-05T07:28:14.660716Z (01:28 local). The newest source is TemporalSummarySpec.scala at 01:18. JOBS.md (not a source file) was edited after that.

VALIDATION RESULTS:
- **Determinism:** every count, table row count and input row count of the end-to-end jobs 1-8 runs equals work orders 2, 3 and 4. Jobs 9-11's 14 count keys and table row counts are equal between the standalone run and the pipeline run.
- **Disk:**
  - before attempt 1: 23,056,672 KiB
  - before attempt 2: 22,135,804 KiB
  - pipeline manifest readings: 22,500,798,464 B before, 21,613,977,600 B after
  - free after the last run (`df`): 21,218,360 KiB; after sbt check: 21,158,592 KiB
  - Never near the 5 GiB floor.

ASSUMPTIONS (deviations, with reasons):
- **Template text in Spark:** done because ruling 12 and the work order direct it; see the decision above.
- **Profile window:** profiles cover the first configured window, all-years. Ruling 7 calls it "all".
- **Topic summaries:** every window, topics with at least one DBLP-authored work.
- **No paper summaries:** work records hold only an `abstractTextRef` pointer, which Spark does not read.
- **Export in place:** the export does not copy tables. The load manifest points at them, which saves about 0.5 GiB per run.
- **Window comparison:** only non-overlapping pairs are compared.
- **Code version:** "84d90b0-dirty", the session-start snapshot (no git). The memory fix changed the code without changing this string.

RISKS:
- **Memory:** the first full run ran out of memory because a single-JVM pipeline keeps the earlier stages' caches. The fix held locally; EMR memory behaviour is unverified.
- **Run IDs:** they do not follow code changes because the code-version string stayed the same. Pass a real SHA once committed.
- **Label propagation:** still not converged at 10 rounds (work order 4), which also limits community retention.
- **Leftover run directories:** the failed attempt left two incomplete directories (no `_manifest.json`) and three complete stage runs. They are inert but use about 556M.

MANUAL ACTION REQUIRED: none required. Optional:
- decide on the ruling 12 / ownership split above;
- the human may remove superseded run directories.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer APPROVE

Not checked: the full-data determinism comparison covers counts and row counts, not table contents or hashes; no mutation testing of the new tests. Main risk: the single-JVM memory pressure, which is fixed locally but untested on EMR.
````
