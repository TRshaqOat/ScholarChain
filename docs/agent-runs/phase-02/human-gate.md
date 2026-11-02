# Phase 02 human gate

Phase: 02, build and repository skeleton (date 2026-11-02)

Reviewed:
- prompt.md; agent-report.md (build-engineer); repair-01.md (build-engineer, BLOCKED-ON dependency-updater); repair-02.md (dependency-updater); review-report.md (code-reviewer: APPROVE).
- build.sbt, project/build.properties, project/plugins.sbt, project/Dependencies.scala, .scalafmt.conf, .gitignore.
- I ran `sbt check` twice in my own terminal: both PASS. I confirmed target/, project/target/, project/project/, .claude/.last-test-run, and .env are git-ignored.

Accepted / rejected: Accepted. Human decisions recorded for this phase:
- (a) Module source roots, per-module trivial tests, and .gitkeep directories are deferred to each owning agent's phase. sbt check currently runs 0 tests.
- (b) Plugin, sbt, and scalafmt versions live outside Dependencies.scala because those tools load first.
- (c) Decision A: Scala 3.3.7, Spark 4.0.2, Hadoop 3.4.2, to match emr-spark-8.0.0.
- (d) A possible transitive netty exposure in Spark 4.0.2 is accepted; Spark is Provided and EMR's jars run on the cluster.
- Ruling B: comments that describe a pinned value travel with the value. I directed the dependency-updater's comment edits on Dependencies.scala lines 11-12 and 17-18.

Manual changes (files and reason): none to agent-authored files. I created labels/.gitkeep (human-owned directory).

File provenance:
- build.sbt, project/build.properties, project/plugins.sbt, .scalafmt.conf, .gitignore: agent-generated (build-engineer), accepted as-is.
- project/Dependencies.scala: agent-generated (build-engineer), repaired by another agent (dependency-updater, repair-02: version values and describing comments under ruling B). build-engineer repair-01 changed comments only.
- labels/.gitkeep: human-authored.
- prompt.md and human-gate.md: human-authored. Agent reports saved verbatim by the orchestrator.

Remaining risk:
- 0 tests run until owning phases add module code and tests.
- Packaging evidence (Hadoop scala relocation, Spark jar contents, Docker stage, arm64 in the image digest) must be re-run on current pins before the first EMR or image run (review EVIDENCE GAPS 3 and 4).
- scala-library is not pinned with dependencyOverrides; build-engineer to add one when module code lands.
- Tests tagged RequiresAws or RequiresDocker have no task that runs them yet.
- Ponytail was injected into the code-reviewer run despite PONYTAIL_SUBAGENT_MATCHER (D-11). The reviewer states its criteria were unaffected. To fix before Phase 3; until then, /ponytail off before reviewer and test-engineer runs.
- Live EMR verification of D-01 to D-03 is pending the Paid plan upgrade.

Accepted commit: see commit.txt
