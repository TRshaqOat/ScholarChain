# Phase 02 review report

## code-reviewer: complete phase-02 build diff

Final report, verbatim as returned to the orchestrator.

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: phase-02 review (human architect, via the orchestrator). Review the untracked phase-02 files against docs/agent-runs/phase-02/prompt.md, D-01 to D-05, D-09, D-10, D-11, and the AWS capability check. Report only findings that block ratification. Return APPROVE or REQUEST_CHANGES. Read-only.

VERDICT: APPROVE

No finding blocks ratification. The conditions are in MANUAL ACTION REQUIRED: two short human-gate entries and one tooling fix outside the repository.

SCOPE REVIEWED:
Each of these files was read in full. The whole file is the diff, since every file is untracked.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/build.sbt
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/build.properties
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/plugins.sbt
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/.scalafmt.conf
- /Users/achyutpatel/Developer/Cloud/ScholarChain/.gitignore
- /Users/achyutpatel/Developer/Cloud/ScholarChain/labels/.gitkeep (exists)

Context I read:
- prompt.md, spec/architect-decisions.md, .claude/hooks/verify-tests-ran.sh, .claude/hooks/format-scala.sh
- agent-report.md, repair-01.md, repair-02.md
- .claude/agents/dependency-updater.md (rules only)
- The classpath exports sbt generated under modules/*/target/streams by the most recent sbt run. These are build outputs, not authored files.

FINDINGS (blocking): none

Checks against the work order, verified from the files:

1. No application logic. There are no sources under modules/ or tests/, and the build files contain only build definitions.

2. No floating or latest versions.
   - Every library, plugin, sbt, and scalafmt version is an exact literal.
   - The base image is pinned by tag plus sha256 digest (Dependencies.scala:25-26).
   - No SNAPSHOT, latest, or `+` versions anywhere (grep).

3. D-09 dates are plausible and consistent.
   - The newest pin is sbt-native-packager 1.13.0 from 2026-10-08, which is 25 days old. Every pin clears the 2026-10-19 cutoff.
   - The dates for Scala 3.3.7, Spark 4.0.2, and Hadoop 3.4.2 agree across repair-01 and repair-02.
   - One mismatch that does not affect any pin: agent-report.md says Spark 4.0.5 came out 2026-10-21, repair-02 says 2026-10-27.

4. Scopes are correct, confirmed against the generated classpath exports.
   - ScalaTest is Test scope (Dependencies.scala:31).
   - Hadoop and Spark are Provided (Dependencies.scala:36, 38). hadoop-client-api and hadoop-client-runtime 3.4.2 appear on the hadoop Test classpath.
   - logback-classic 1.5.38 appears on the services runtime classpath only. The services compile classpath has upickle_3 4.4.3 and no logback.
   - core, hadoop, and spark resolve scala-library 2.13.16, which matches emr-spark-8.0.0.

5. for3Use2_13 is used correctly.
   - It appears once, on spark-sql (Dependencies.scala:37-38). The sparkAnalytics compile classpath shows spark-sql_2.13/4.0.2 next to scala3-library_3/3.3.7.

6. The mixed-suffix guard fails on a mixed classpath.
   - build.sbt:20 sets `ConflictWarning("cross-version", Level.Error, failOnConflict = true)` in every module.
   - sbt's `update` runs this check across all resolved configurations, and with failOnConflict it calls sys.error.
   - Its pattern strips both `_3` and `_2.13`, so a scala-xml_3 / scala-xml_2.13 pair is caught.
   - The setting matches sbt 1.x's per-project default (`ConflictWarning.default` is Level.Error, failOnConflict=true). Only the label changes.
   - The observed message ("...suffixes in sparkAnalytics...") carries sbt's default project label. So it was produced before this explicit setting existed, and before the repins.
   - On the current pins, the sparkAnalytics Test classpath holds only scala-xml_2.13/2.3.0, with no scala-xml_3. The orchestrator's passing `sbt check` ran `update` on every project.
   - The exclusion is still needed (build.sbt:58), and the guard currently passes.
   - Re-confirming that it trips on the current pins needs a run (see EVIDENCE GAPS). That run does not block ratification.

7. No GraphFrames (grep: none). This satisfies D-10.

8. The module graph is acyclic and matches the CLAUDE.md table exactly (build.sbt:37-72).
   - tests depends on all seven modules at Test scope only (build.sbt:91-94).
   - root only aggregates.

9. The check alias (build.sbt:128) runs, in order: scalafmtSbtCheck, scalafmtCheckAll, Test/compile, markTestRun.
   - markTestRun runs Test/test across every aggregate, including the `tests` project and its contracts directory.
   - Tests tagged RequiresAws or RequiresDocker are excluded (build.sbt:25-31).
   - Docker is not involved: only dockerLocal reaches DockerPlugin publishing. The orchestrator's check passed on a machine without Docker.

10. markTestRun (build.sbt:112-120) is correct and compatible with the hook.
   - The `test.all(...).value` dependency is lifted by the sbt task macro, so the IO.write body runs only if every aggregated Test/test succeeds. A failed dependency stops the body from running at all.
   - The marker path, `(ThisBuild / baseDirectory) / .claude / .last-test-run`, matches MARKER_REL in verify-tests-ran.sh:27. The orchestrator observed it written there.
   - The hook compares modification times and skips `.claude/*`, so writing a fresh timestamp after the tests is exactly what it expects.

11. .scalafmt.conf works with format-scala.sh.
   - The hook passes `--config <root>/.scalafmt.conf` plus an absolute file path, and always exits 0.
   - The config has a valid `version`, dialect scala3, and fileOverride globs (`**/*.sbt` -> sbt1, `**/project/*.scala` -> scala212) that match absolute paths.
   - The orchestrator's check output shows the sbt-scalafmt checks loaded and passed with this file.

12. Container settings are as required (build.sbt:73-87).
   - The base image is pinned by digest. Whether the digest includes arm64 can't be checked with Read, Grep, and Glob (see EVIDENCE GAPS).
   - The build passes `--platform=linux/arm64`.
   - The image runs as the non-root user `scholarchain`, uid 10001.
   - Memory uses InitialRAMPercentage=50 and MaxRAMPercentage=75 through `Universal / javaOptions`.
   - `dockerUpdateLatest := false`.

13. Environment inputs are documented. SCHOLARCHAIN_IMAGE_TAG is documented at build.sbt:3-4 and is the only variable read (build.sbt:78, 122). There are no secrets, endpoints, Regions, or account IDs.

14. .gitignore covers all 18 required patterns, including `project/project/` (line 8) and `project/target/` (line 7).

OWNERSHIP CHECK:
- build.sbt, project/build.properties, project/plugins.sbt, .scalafmt.conf, .gitignore -> owner build-engineer -> author build-engineer: yes.
- project/Dependencies.scala -> owner build-engineer (structure) and dependency-updater (version values only, exception 1).
  - Version values: author dependency-updater (repair-02): yes.
  - Comment lines 11, 12, 17, and 18, edited by dependency-updater: no, going by the letter of the rules. Exception 1 and the dependency-updater's own Law 1 ("Change a version literal and nothing else") both exclude comment edits.
  - I am not raising this as a MAJOR. repair-02 records that the human's repair-02 work order (decisions A and B) told it to "replace its RISK comment" and "update its date comment". So the human resolved an ambiguous token, and the agent did not act on its own initiative.
  - The new comments are accurate. The classpath exports confirm 3.3.7 -> scala-library 2.13.16, Spark 4.0.2, and Hadoop 3.4.2.
  - Condition: the human-gate must record ruling B, and per-file provenance must read "repaired by another agent". If the human did not in fact direct the comment edits, this becomes a MAJOR finding (owner dependency-updater) and the verdict becomes REQUEST_CHANGES.
- labels/.gitkeep -> owner human architect -> author not stated in any report. The build-engineer listed it as BLOCKED-ON human architect and says it did not attempt it.
  - Condition: the human-gate must record it as human-authored. An agent write here would be CRITICAL.
- Constitutional paths, hooks, settings, spec/**: untouched (git status).

FLOOR AND FENCE CHECK:
- The diff contains no hook, permission, agent-file, or settings change.
- markTestRun does not weaken the floor. The marker is written only after tests pass, and it is gitignored (.gitignore:32).
- The check alias adds nothing that skips tests.
- Out-of-repository observation: this code-reviewer run received a Ponytail "PONYTAIL MODE ACTIVE — level: full" SubagentStart injection. D-11 states that PONYTAIL_SUBAGENT_MATCHER excludes code-reviewer to keep the review independent, so the matcher as installed does not do what D-11 records. I did not let the injected instructions change my review criteria. This is user-scope tooling, not a repository artifact. Owner: human architect.

EVIDENCE GAPS (claims without orchestrator-observed output). None blocks Phase-2 ratification unless noted.

1. Guard trips on the current pins. This is agent-reported and was observed only before the repins.
   - To confirm, in a scratch copy: delete build.sbt line 58, run `sbt sparkAnalytics/update`, and expect "Conflicting cross-version suffixes in: org.scala-lang.modules:scala-xml".
   - Not blocking: the mechanism is built into sbt, and the current classpaths are clean.

2. markTestRun does not write on failure. This was not observed end to end.
   - To confirm, the human runs this in a scratch copy: add a failing ScalaTest, record the marker's modification time, run `sbt check`, and confirm check fails and the marker time is unchanged.
   - Not blocking: the code relies on standard sbt task-dependency behavior.

3. Hadoop relocation, Spark jar contents, and Docker stage were checked only in a scratch copy on the old pins (3.3.8, 4.0.4, 3.4.1).
   - To re-run on the current pins:
     - `sbt packageJobs`
     - `unzip -l modules/hadoop/target/scala-3.3.7/hadoop-assembly-0.1.0.jar`: expect 0 top-level `scala/` entries.
     - `unzip -l modules/spark/target/scala-3.3.7/sparkAnalytics-assembly-0.1.0.jar`: expect no scala-library classes and no Spark or Hadoop classes.
     - `SCHOLARCHAIN_IMAGE_TAG=probe sbt services/Docker/stage`, then inspect the generated Dockerfile (FROM digest, `USER 10001`) and conf/application.ini.
   - Not blocking for Phase 2, since packaging is not part of check and no job code exists. It must be done before the first EMR or image run.

4. arm64 in the image digest. This is agent-reported.
   - To confirm, check that index digest sha256:39d618b8... lists linux/arm64. Use `docker buildx imagetools inspect eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8fda3408cfe792db2b666c0e3db8ea4ae263ab4d0c3805933e5355f45`, or with no Docker, `skopeo inspect --raw docker://<same ref>`.
   - Not blocking now. It is required before dockerLocal or Phase 12.

5. Maven Central and Docker Hub dates. These are agent-reported.
   - Spot-check the newest pin with `curl -s https://repo1.maven.org/maven2/com/github/sbt/sbt-native-packager_2.12_1.0/1.13.0/`.
   - Not blocking.

6. With 0 sources, the Scala 3.3.7 compiler has never run in this tree, including the `-release 17` scalacOption. This is covered by human-approved item (a).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied by the orchestrator, quoted):
"[info] scalafmt: Checking 1 Scala sources ... [info] scalafmt: Checking 2 Scala sources ... [success] ×3 ... [info] markTestRun: tests passed, wrote /Users/achyutpatel/Developer/Cloud/ScholarChain/.claude/.last-test-run [success]". 0 tests ran, per approved item (a).

VALIDATION RESULTS: These come from build outputs sbt generated (target/streams exports), read with Grep, and they agree with the agents' scope and version claims:
- sparkAnalytics Test: scalatest_3/3.2.20, scala-library/2.13.16, scala-xml_2.13/2.3.0, no scala-xml_3.
- hadoop Test: hadoop-client-api and hadoop-client-runtime 3.4.2.
- services: logback in runtime only.

ASSUMPTIONS:
- D-01 to D-03 are treated as RATIFIED, per spec/architect-decisions.md:162. That file's table (lines 16-18) still says PROPOSED, which is for the human to fix.
- Items (a) to (d) are accepted, as instructed.
- The stale `modules/spark/target/scala-3.3.8/` update cache is a leftover from earlier runs and does not reflect the current state.

RISKS (non-blocking, owner build-engineer unless noted):
- Nothing pins scala-library to 2.13.16. A future compile-scope dependency built against a newer 2.13.x could quietly upgrade it and bring back the stdlib mismatch with EMR. A `dependencyOverrides` entry or a check would close this. Owner: build-engineer, when module code lands.
- Tests tagged RequiresAws or RequiresDocker are excluded from every Test/test run, not just check. build.sbt:24 says they "run only outside check", but no task runs them yet.
- Running `services/Docker/publishLocal` directly bypasses requireImageTag and tags the image "unset", a mutable tag (build.sbt:78). This is local only.
- Terraform plan files are ignored only as `*.tfplan`, so the infra-engineer's plan.sh must use that extension (owner infra-engineer).
- Ponytail was injected into this reviewer run, contrary to D-11 (see FLOOR AND FENCE CHECK).

MANUAL ACTION REQUIRED (human):
1. In human-gate.md, record ruling B (dependency-updater comment edits in Dependencies.scala lines 11-12 and 17-18) and the provenance "repaired by another agent".
2. Record labels/.gitkeep as human-authored.
3. Optionally, route to the factory-engineer a ratifiable clarification of exception 1 that says whether a comment describing a version travels with the value.
4. Fix PONYTAIL_SUBAGENT_MATCHER so it excludes code-reviewer, as D-11 states.
5. Before Phase 12, or before the first EMR run, run the commands in EVIDENCE GAPS items 3 and 4.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: The human gate, with the human-gate conditions in MANUAL ACTION REQUIRED items 1 and 2. No findings need routing to owners.

Not checked: I ran nothing, so the image digest's architectures, the Maven Central dates, and the guard tripping on the current pins all rest on agent reports. If ruling B did not cover the comment edits, this verdict becomes REQUEST_CHANGES.
````
