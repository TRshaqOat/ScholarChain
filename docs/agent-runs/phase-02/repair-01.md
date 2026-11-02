# Phase 02 repair-01

## build-engineer: EMR runtime pins and Scala stdlib risk

Final report, verbatim as returned to the orchestrator.

````text
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: phase-02 repair-01 (verbatim human architect work order, passed on by the orchestrator). Items: 1 pin Spark to 4.0.2; 2 pin Hadoop client to 3.4.2 if it is on Maven Central and at least 14 days old; 3 replace the PROVISIONAL label with a comment citing emr-spark-8.0.0; 4 document the scala-library 2.13.18 vs 2.13.16 risk without changing Scala.

Items 3 and 4 are done; they only touch comments, and comments are ledger structure, which I own. Items 1 and 2 are BLOCKED-ON dependency-updater. They change only the version-string values `Versions.spark` and `Versions.hadoop` in the version ledger. Exception 1 in docs/agents.md and my refusal boundary give that change to the dependency-updater, and that holds even though the Spark change is a downgrade. Evidence is below. Both target versions meet D-09.

FILES CREATED: none
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala (comments only, no value changed)
  - Added a RISK comment above `val scala`. It says Scala 3.3.8 depends on scala-library 2.13.18 while the EMR Spark runtime ships 2.13.16. Stdlib members added after 2.13.16 will compile but fail on the cluster, because the Spark job jar uses the runtime's scala-library. The comment also says 3.3.6 and 3.3.7 are the 3.3 LTS releases that use 2.13.16.
  - Replaced the PROVISIONAL line with: "Target runtime per AWS release documentation for emr-spark-8.0.0 (released 2026-05-21): Spark 4.0.2-amzn-0, Scala 2.13.16, Hadoop 3.4.2-amzn-1, Java 17 (capability check 2026-11-02)."
FILES DELETED: none

MODULE GRAPH: unchanged (no edit to build.sbt): core → none; entityResolution → core; hadoop → core, entityResolution; sparkAnalytics → core; storage → core; rag → core, storage; services → core, rag, storage; tests → all seven at Test scope only. It matches CLAUDE.md and is acyclic, as shown in the earlier phase-02 report. I did not re-inspect it because the graph did not change.

VERSION LEDGER: /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala. Current values, all unchanged:
- scala 3.3.8, javaRelease 17
- spark 4.0.4, hadoop 3.4.1 (both still waiting on the dependency-updater)
- upickle 4.4.3, scalaTest 3.2.20, logback 1.5.38
- javaRuntimeImage eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8fda3408cfe792db2b666c0e3db8ea4ae263ab4d0c3805933e5355f45
- Outside the ledger, also unchanged: sbt 1.13.0 (project/build.properties); sbt-scalafmt 2.6.2, sbt-assembly 2.5.0, sbt-native-packager 1.13.0 (project/plugins.sbt); scalafmt 3.11.5 (.scalafmt.conf).

SCOPES AND PACKAGING: unchanged. Hadoop and Spark are Provided, ScalaTest is Test, logback is Runtime (services only). The Hadoop jar relocates `scala.**`. The Spark jar leaves out scala-library and relies on the runtime's 2.13 copy. The services image is arm64, runs as non-root uid 10001, and uses RAM percentages.

ENVIRONMENT INPUTS: SCHOLARCHAIN_IMAGE_TAG (read by dockerLocal only). Nothing else.

COMPATIBILITY RISKS:
1. Scala stdlib mismatch (item 4, documented, not fixed). Scala 3.3.8 depends on scala-library 2.13.18, but EMR emr-spark-8.0.0 ships 2.13.16. Code linked against stdlib members added in 2.13.17 or 2.13.18 compiles and passes local tests, then fails on EMR with NoSuchMethodError or NoClassDefFoundError. The scala-library each Scala 3.3.x LTS release depends on, from the Maven Central POMs:
   - 3.3.0 → 2.13.10 (2023-05-23)
   - 3.3.1 → 2.13.10 (2023-09-05)
   - 3.3.2 → 2.13.12 (2024-02-14)
   - 3.3.3 → 2.13.12 (2024-02-29)
   - 3.3.4 → 2.13.14 (2024-09-25)
   - 3.3.5 → 2.13.15 (2025-01-28)
   - 3.3.6 → 2.13.16 (2025-05-07)
   - 3.3.7 → 2.13.16 (2025-10-09)
   - 3.3.8 → 2.13.18 (2026-06-10)
   - 3.3.9 → 2.13.18 (2026-10-22, under 14 days old)

   So every release from 3.3.0 to 3.3.7 uses 2.13.16 or lower, and all are at least 14 days old. 3.3.7 is the newest of them and matches the runtime exactly. Switching Scala is a human decision; I did not change it.
2. Spark 4.0.2's own POM (spark-parent_2.13) declares hadoop.version 3.4.1 and scala.version 2.13.16. Pinning Hadoop to 3.4.2 therefore matches EMR, but it is one patch above what upstream Spark 4.0.2 is built against. The risk is low because both are Provided and EMR supplies 3.4.2-amzn-1.
3. Two trailing comments will be stale once the dependency-updater changes the values. On `val spark`, the date comment reads "2026-07-12, Scala 2.13.16 build". On `val hadoop`, it reads "2024-10-09, the hadoop.version Spark 4.0.4 is built against". After the change they should read "2026-02-02, Scala 2.13.16 build" and "2025-08-20, matches EMR Hadoop 3.4.2-amzn-1". Either the dependency-updater updates them together with the values (if the orchestrator rules that date comments travel with the value), or I fix them in a follow-up order.
4. Earlier risks remain open: the Scala 3.3.8 / 2.13.16 point now lives in item 1 above; full `scala.**` relocation breaks reflection; Spark tests will need Java 17 `--add-opens` and fork; Provided dependencies are not visible to `tests`.

COMMANDS RUN:
- Read build.sbt, project/*.sbt, project/Dependencies.scala, project/build.properties, docs/agent-runs/phase-02/agent-report.md, and spec/architect-decisions.md (the capability check and D-09).
- curl Maven Central directory listings for hadoop-client-api, hadoop-client-runtime, spark-sql_2.13 and scala3-library_3.
- curl the spark-parent_2.13 4.0.2 POM, and the scala3-library_3 POM for each version from 3.3.0 to 3.3.9.
- `sbt -batch Test/compile`: [success]
- `sbt -batch check`, three times. Runs 1 and 2 are the two required runs; run 3 was grepped for test-count lines.
- `git status --porcelain --untracked-files=all`

TEST RESULTS:
- `sbt check` run 1 and run 2: both scalafmt checks, Test/compile and markTestRun ended with [success]. markTestRun logged "tests passed, wrote .../.claude/.last-test-run".
- Run 3 gave the same result.
- 0 tests ran, and no ScalaTest count line was printed, because there are no test sources yet. Module sources were deferred to their owners with human approval.

VALIDATION RESULTS:
- The format check passed on the edited Dependencies.scala; the new comments fit the formatter.
- No version value changed: the diff touches comment lines only.

EVIDENCE FOR ITEMS 1 AND 2 (for the dependency-updater; D-09 cutoff is 2026-10-19):
- spark-sql_2.13 4.0.2 is on Maven Central, published 2026-02-02 10:33, so it qualifies. Change needed: `Versions.spark` "4.0.4" → "4.0.2".
- hadoop-client-api 3.4.2 was published 2025-08-20 10:59 and hadoop-client-runtime 3.4.2 on 2025-08-20 11:04, so both qualify. Change needed: `Versions.hadoop` "3.4.1" → "3.4.2". There is a newer 3.4.3 (2026-02-12), but it does not match EMR.

ASSUMPTIONS:
- "Bumping an existing version value" includes a downgrade.
- The work order's "Check exception 1" means items 1 and 2 go to the dependency-updater.
- Comment lines in the ledger are structure, and structure is mine.

RISKS:
- Until the dependency-updater acts, the ledger pins Spark 4.0.4 and Hadoop 3.4.1 under a comment naming the EMR 4.0.2 and 3.4.2 runtime.
- The Scala stdlib risk in item 1 of COMPATIBILITY RISKS.

MANUAL ACTION REQUIRED:
- The human decides whether to stay on Scala 3.3.8 (and accept the risk) or move to 3.3.7, which matches the runtime's 2.13.16. If 3.3.7 is chosen, the dependency-updater makes that change.
- The orchestrator decides who updates the two stale trailing date comments (COMPATIBILITY RISKS item 3).

BLOCKED-ON OWNER: dependency-updater (items 1 and 2)
BLOCKED-ON EVIDENCE: docs/agents.md exception 1 says the dependency-updater "may change only the version-string values in that ledger". The build-engineer refusal boundary says "Bumping an existing version value → dependency-updater". Items 1 and 2 change only `Versions.spark` and `Versions.hadoop` in /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala. The Maven Central dates above show both targets meet D-09.
NEXT GATE: dependency-updater sets the two values, then sbt check and code-reviewer APPROVE
````
