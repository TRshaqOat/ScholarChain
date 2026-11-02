# Phase 02 repair-02

## dependency-updater: Scala, Spark, and Hadoop pins matched to EMR emr-spark-8.0.0

Final report, verbatim as returned to the orchestrator.

````text
STATUS: COMPLETE
OWNER: dependency-updater
WORK ORDER: phase-02 repair-02 (human architect decisions A and B, passed on by the orchestrator). In project/Dependencies.scala: (1) Versions.scala 3.3.8 -> 3.3.7 and replace its RISK comment, (2) Versions.spark 4.0.4 -> 4.0.2 and update its date comment, (3) Versions.hadoop 3.4.1 -> 3.4.2 and update its comment. Run sbt check twice.

FILES CREATED: none
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala (lines 11-12 and 17-18 only)

Resulting lines:
```
  // Scala 3.3.7 depends on scala-library 2.13.16, matching the EMR emr-spark-8.0.0 runtime.
  val scala = "3.3.7" // 2025-10-09, Scala 3.3 LTS (stdlib is scala-library 2.13.16)
  ...
  val spark = "4.0.2" // 2026-02-02, Scala 2.13.16 build
  val hadoop = "3.4.2" // 2025-08-20, matches EMR Hadoop 3.4.2-amzn-1
```
Under ruling B, I updated only comments that describe the three values: the three-line RISK block, which became one line, and three trailing comments. The file header, the emr-spark-8.0.0 target-runtime comment, and everything else are unchanged.

VERSION CHANGES:
- Scala: 3.3.8 -> 3.3.7. Patch downgrade inside the 3.3 LTS line, coupled to Spark/EMR. Approved by the human in decision A. Reason: 3.3.7's scala-library is 2.13.16, the same as EMR emr-spark-8.0.0. Source: https://repo1.maven.org/maven2/org/scala-lang/scala3-library_3/3.3.7/ shows the POM published 2025-10-09 08:32, and the POM lists the dependency scala-library 2.13.16. I fetched it 2026-11-02.
- Spark: 4.0.4 -> 4.0.2. Patch downgrade, coupled to Spark/EMR. Approved by the human in the work order. Reason: matches EMR Spark 4.0.2-amzn-0. Source: https://repo1.maven.org/maven2/org/apache/spark/spark-sql_2.13/4.0.2/ shows the POM published 2026-02-02 10:33. I fetched it 2026-11-02.
- Hadoop: 3.4.1 -> 3.4.2. Patch bump, coupled to Hadoop/EMR. Approved by the human in the work order. Reason: matches EMR Hadoop 3.4.2-amzn-1. Sources: https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-client-api/3.4.2/ (published 2025-08-20 10:59) and https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-client-runtime/3.4.2/ (published 2025-08-20 11:04). Both fetched 2026-11-02.
- D-09 (each pin at least 14 days old, cutoff 2026-10-19): all three targets meet it. My dates match the evidence in docs/agent-runs/phase-02/repair-01.md.
- I made all three changes as one change set because the human asked for them together. My law 3 normally wants one family per change set.

VULNERABILITIES:
- Apache Spark security page (https://spark.apache.org/security.html, fetched 2026-11-02): none of its CVEs lists any 4.0.x release (4.0.0 to 4.0.4) as affected. CVE-2025-55039 was fixed in 4.0.0.
- Still open, not confirmed upstream: Chainguard advisories found by web search on 2026-11-02 list CVE-2026-44249 (netty-handler, CVSS 8.1) as fixed in their spark-4.0-scala-2.13 4.0.3-r1 package. Related entries concern bundled netty-codec-http2 4.1.135.Final at 4.0.4-r1. Sources: https://osv.dev/vulnerability/CGA-gxcm-wc9g-mg5v and https://images.chainguard.dev/security/CGA-fw44-2jgf-2gmc. These are distro rebuilds and do not map to upstream releases. Going back to 4.0.2 may re-expose a transitive netty version that 4.0.3 or 4.0.4 fixed. I did not confirm this against Spark's own dependency tree.
- Hadoop 3.4.2: I checked no advisories specific to it. This is an upward move from 3.4.1.
- Scala 3.3.7: I checked no advisories.

REVERTED BUMPS: none
COMMANDS RUN:
- curl of the Maven Central directory listings for scala3-library_3/3.3.7, spark-sql_2.13/4.0.2, hadoop-client-api/3.4.2 and hadoop-client-runtime/3.4.2, plus the scala3-library_3-3.3.7 POM, to check scala-library
- sbt check (run 1)
- sbt check (run 2, output filtered with grep)
- find modules tests -name '*.scala' (empty), and ls of the Coursier cache (3.3.7, 4.0.2 and 3.4.2 are now resolved)
- WebSearch and WebFetch for the Spark advisories above

TEST RESULTS:
- Run 1: the meta-build recompiled project/Dependencies.scala. The scalafmt check passed. Every task ended with [success]. markTestRun printed "tests passed, wrote .../.claude/.last-test-run". No test count was printed. There are no Scala sources under modules/ or tests/, so 0 tests ran.
- Run 2: same result, every task [success] and markTestRun wrote the marker. 0 tests, because no test sources exist.

VALIDATION RESULTS: sbt check passed twice on the current tree. The new versions resolved. The Coursier cache now holds scala3-library_3 3.3.7, spark-sql_2.13 4.0.2 and hadoop-client-api 3.4.2.
ASSUMPTIONS: Ruling B covers the trailing comments on the three edited lines and the RISK comment above val scala. The emr-spark-8.0.0 target-runtime comment is ledger structure, owned by the build-engineer, and was already accurate, so I left it alone.
RISKS:
- With no module sources, sbt check proves only that the build loads, resolves and passes formatting. It does not yet prove API compatibility with Spark 4.0.2 or Scala 3.3.7.
- Possible transitive netty exposure in Spark 4.0.2, as noted under VULNERABILITIES. Spark is Provided, so the cluster's Spark 4.0.2-amzn-0 jars are what actually run. Exposure depends on how EMR patches its netty, not on this pin.
- Spark 4.0.2's own POM declares hadoop.version 3.4.1, so Hadoop is one patch above upstream Spark's build (repair-01, item 2). The risk is low because both are Provided.
- Upstream Spark 4.0.5 came out on 2026-10-27, which is under 14 days old. Not relevant while the pin stays on EMR's 4.0.2.
MANUAL ACTION REQUIRED: none beyond the approvals already given. Optional: the human decides whether the Spark 4.0.2 netty question needs a dependency-tree check once Spark module code exists.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check, vulnerability review, and code-reviewer APPROVE

Not checked: Hadoop 3.4.2 and Scala 3.3.7 advisories, and Spark 4.0.2's netty dependency tree. Risk: the tests prove nothing yet because there are 0 test sources.
````
