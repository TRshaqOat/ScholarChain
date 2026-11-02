# Phase 02 agent reports

## build-engineer: sbt multi-project build and repository skeleton

Final report, verbatim as returned to the orchestrator.

Human-approved deferral: module source roots, per-module tests, and .gitkeep directories are created by each owning agent in its own phase.

````text
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: phase-02. Create the ScholarChain sbt multi-project build and repository skeleton (verbatim work order from the human architect, delivered by the orchestrator).

The build files are done and `sbt check` passes twice. Three required items are still open because their paths are outside my rows in docs/agents.md: the empty module source roots, one trivial test per module, and the `.gitkeep` top-level directories. Because of that, `sbt check` currently runs 0 tests. The full path list is under BLOCKED-ON EVIDENCE.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/build.sbt
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/build.properties
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/plugins.sbt
- /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala (the version ledger)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/.scalafmt.conf
- /Users/achyutpatel/Developer/Cloud/ScholarChain/.gitignore
- Generated, not authored: /Users/achyutpatel/Developer/Cloud/ScholarChain/.claude/.last-test-run, written only by `sbt check` → markTestRun. It is gitignored.
FILES MODIFIED: none (the tree was clean at 729fb8b)
FILES DELETED: none

MODULE GRAPH (from `sbt -batch "show <p>/projectDependencies"` for each project; checked against the CLAUDE.md table, and it matches exactly):
- core → (none)
- entityResolution → core
- hadoop → core, entityResolution
- sparkAnalytics → core
- storage → core
- rag → core, storage
- services → core, rag, storage
- tests → core, entityResolution, hadoop, sparkAnalytics, storage, rag, services, all at `test` scope only. No production module depends on tests.
- The root project `scholarchain` only aggregates the eight projects. It has no dependsOn and no sources.
- The graph is acyclic. Directories: modules/core, modules/entity-resolution, modules/hadoop, modules/spark, modules/storage, modules/rag, modules/services, tests.
- The `tests` test source roots are tests/contracts, tests/integration, tests/end-to-end and tests/adversarial. Its resources come from tests/fixtures.

VERSION LEDGER (file: /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala, objects `Versions` and `Dependencies`). Dates are Maven Central directory dates or Docker Hub last_pushed dates I looked up today. Every pin is at least 14 days old (cutoff 2026-10-19).
- Scala 3.3.8 (3.3 LTS): 2026-06-10. Brings scala-library 2.13.18 transitively.
- Java release target 17: a constant used for `-release 17` and `--release 17`.
- Spark (spark-sql_2.13 via for3Use2_13) 4.0.4: 2026-07-12. **PROVISIONAL.**
- Hadoop (hadoop-client-api, hadoop-client-runtime) 3.4.1: 2024-10-09. **PROVISIONAL.** It is the hadoop.version Spark 4.0.4 is built against.
- uPickle 4.4.3: 2026-02-14.
- ScalaTest 3.2.20: 2026-03-25.
- logback-classic 1.5.38: 2026-07-09.
- Runtime image `eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8fda3408cfe792db2b666c0e3db8ea4ae263ab4d0c3805933e5355f45`: tag pushed 2026-09-18. The digest is a multi-arch index that includes arm64.

Three versions cannot live in the ledger file, because each tool reads them before project/*.scala loads:
- project/build.properties: sbt 1.13.0, published 2026-08-20.
- project/plugins.sbt: sbt-scalafmt 2.6.2 (2026-07-17), sbt-assembly 2.5.0 (2026-08-27), sbt-native-packager 1.13.0 (2026-10-08).
- .scalafmt.conf: scalafmt 3.11.5 (2026-07-27).

Newer releases I skipped because they are under 14 days old: Scala 3.3.9 (2026-10-22), Spark 4.0.5 (2026-10-21), sbt 1.13.1 (2026-10-28), and Temurin 17.0.20.1_1-jre-noble (pushed 2026-11-01).

SCOPES AND PACKAGING:
- **Scopes**
  - ScalaTest is in Test scope in every project (commonSettings).
  - Hadoop and Spark are Provided.
  - logback-classic is Runtime, and only in services. Hadoop and Spark use the logging their runtime supplies.
  - uPickle is a compile dependency of core and reaches other modules through core.
- **Mixed-classpath check**
  - Every project sets `conflictWarning := ConflictWarning("cross-version", Level.Error, failOnConflict = true)`. I first tried this at ThisBuild, but `inspect` showed the setting is defined per project, so I moved it into commonSettings.
  - Observed: the first `sbt Test/compile` failed with "Modules were resolved with conflicting cross-version suffixes in sparkAnalytics: org.scala-lang.modules:scala-xml _2.13, _3". ScalaTest pulls in scala-xml_3 and Spark pulls in scala-xml_2.13.
  - Fix: sparkAnalytics excludes `scala-xml_3`, keeping the 2.13 build that the Spark runtime uses.
  - After the fix, a scan of all 16 Test and Runtime classpaths found no library with both suffixes.
- **check stays AWS-free and Docker-free**
  - ScalaTest runs with `-l scholarchain.tags.RequiresAws -l scholarchain.tags.RequiresDocker`, so tests carrying those tags never run inside check.
  - The tag objects do not exist yet. Their names must match these strings exactly.
- **Hadoop job jar** (`hadoop/assembly`)
  - sbt-assembly relocates `scala.**` to `scholarchain.shaded.scala.**`; Hadoop itself is excluded because it is Provided (D-03).
  - Scratch probe: the jar has 0 top-level `scala/` entries, 3459 relocated classes, and 0 org/apache/hadoop or org/apache/spark classes.
  - `java -cp hadoop-assembly-0.1.0.jar HadoopProbe` ran on a bare JVM with no Scala on the classpath and printed "hadoop-probe 6 [1,2,3]" (exit 0).
- **Spark job jar** (`sparkAnalytics/assembly`)
  - Excludes scala-library, which the Spark runtime supplies as 2.13. It carries scala3-library_3 unshaded, plus uPickle. Spark is Provided.
  - Probe: 0 copies of scala/collection/immutable/List.class, 1 copy of scala/runtime/LazyVals.class, and 0 Spark or Hadoop classes.
- **No GraphFrames dependency** (D-10).
- **Image** (`services`, sbt-native-packager)
  - Base image is the pinned Temurin 17 JRE digest above.
  - Runs as non-root: user `scholarchain`, uid 10001, `USER 10001:0`.
  - `conf/application.ini` sets `-XX:InitialRAMPercentage=50.0` and `-XX:MaxRAMPercentage=75.0`, and the launcher reads that file.
  - The build command is `docker build --force-rm -t scholarchain-services:<tag> --platform=linux/arm64 .`, with dockerUpdateLatest=false.
  - I checked all of this from `services/Docker/stage` in the scratch copy; no Docker daemon was needed.
- **Aliases**
  - `fmt` = `scalafmtSbt; scalafmtAll`
  - `check` = `scalafmtSbtCheck; scalafmtCheckAll; Test/compile; markTestRun`
  - `packageJobs` = `hadoop/assembly; sparkAnalytics/assembly`
  - `dockerLocal` = `requireImageTag; services/Docker/publishLocal`. This is the only step that needs Docker.
- **markTestRun** (root project) first runs `test` across every aggregated project in Test. Only after that succeeds does it write an ISO timestamp to `<build root>/.claude/.last-test-run`. Running it on its own therefore also runs the tests first.
- **Compose file**: not created. There is no emulator or test dependency that justifies one yet, and check must not need Docker.

ENVIRONMENT INPUTS:
- `SCHOLARCHAIN_IMAGE_TAG`: the immutable image tag (Git SHA). Only dockerLocal reads it, and it fails when the variable is unset or blank. Observed: "dockerLocal needs SCHOLARCHAIN_IMAGE_TAG set to an immutable tag (Git SHA)".
- The build reads no other variable. It contains no secrets, cloud endpoints, account IDs, or deployment steps. The sbt launcher's own JAVA_HOME and SBT_OPTS are not read by the build definition.

.gitignore CONTENTS (verbatim, comments omitted):
.env
.env*
target/
project/target/
project/project/
project/metals.sbt
.bsp/
.bloop/
.metals/
.idea/
.vscode/
*.iml
.DS_Store
.terraform/
*.tfstate
*.tfstate.*
*.tfplan
scratch/
*.log
.claude/settings.local.json
.claude/.last-test-run

COMPATIBILITY RISKS (Scala 3, Hadoop, Spark, EMR):
1. D-01, D-02 and D-03 are still PROPOSED in spec/architect-decisions.md, not RATIFIED. I followed them because this work order requires them.
2. Spark 4.0.4 and Hadoop 3.4.1 are provisional. The AWS capability check must confirm the actual emr-spark-8.0.x component versions, and whether that release runs MapReduce at all (D-03 fallback: EMR 7.x).
3. Scala 3.3.8 compiles and tests against scala-library 2.13.18, but Spark 4.0.4 ships 2.13.16, and the Spark job jar deliberately uses the cluster's copy. Code that links to stdlib methods added after 2.13.16 could fail on EMR. The planned hello-world Spark spike on EMR must cover this.
4. Relocating all of `scala.**` in the Hadoop jar breaks any reflection that loads Scala classes by their original name (for example a class name given in a Hadoop config string). It is verified only on a bare JVM, not on a cluster.
5. No SparkSession test exists yet. Spark 4 on Java 17 will likely need `Test / fork := true` plus Spark's `--add-opens` JVM options. That is a later build-engineer work order, raised once the spark-engineer adds a session test.
6. Provided dependencies are not transitive, so the `tests` project does not see Spark or Hadoop classes. If test-engineer tests touch Spark or Hadoop types, the build needs a change.
7. uPickle 4.4.3 is built with Scala 3.3.7 and ScalaTest 3.2.20 with Scala 3.1.3. Both are readable by 3.3.8; observed compiling in the scratch probe.

COMMANDS RUN:
- Reads: CLAUDE.md, docs/agents.md, spec/architect-decisions.md, and the spec's stack, layout and Phase 2 sections; .claude/hooks/format-scala.sh, verify-tests-ran.sh, and the build-engineer row of guard-paths.sh; .claude/settings.json; docs/agent-runs/tool-versions.md.
- curl to Maven Central and Docker Hub for versions, dates, POMs and image digests.
- `sbt Test/compile` twice: first a failure on the scala-xml suffix conflict, then success.
- `sbt -batch projects 'show buildDependencies' 'inspect sparkAnalytics/conflictWarning' ...`
- `sbt -batch 'show <p>/projectDependencies'` and `export <p>/{Test,Runtime}/fullClasspath` for all 8 projects.
- `sbt check`: failed on formatting of project/Dependencies.scala. Then `sbt fmt`, then `sbt check` (pass), then `sbt check` again (pass).
- Scratch copy of the build at /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/840f33d0-4932-4b02-8cec-f2af5e3e5d7f/scratchpad/buildprobe, with throwaway probe sources. In it: `sbt -batch core/test`, `PROBE_PASS=1 sbt -batch test`, `sbt -batch packageJobs services/Docker/stage`, `java -cp <hadoop jar> HadoopProbe`, unzip listings, `show services/dockerBuildCommand`, and `sbt -batch dockerLocal` with the tag unset.
- `printf '{"hook_event_name":"SubagentStop","stop_hook_active":false}' | CLAUDE_PROJECT_DIR=$PWD .claude/hooks/verify-tests-ran.sh` gave exit 0.
- `git status --porcelain --untracked-files=all`
- One command was blocked by guard-dangerous.sh [protected-path-write]: my first scratch probe, because it named `.claude/.last-test-run`. I did not reword it to retry.

TEST RESULTS:
- Repository `sbt check`, run 1: every step `[success]`, and markTestRun logged "tests passed, wrote .../.claude/.last-test-run" (marker 2026-11-02T22:19:56Z).
- Repository `sbt check`, run 2: identical result, marker refreshed at 16:21:33 local. 0 tests ran in both, because no test sources exist in any module.
- Scratch probe, failing run: core/test showed "Tests: succeeded 0, failed 1" and TestsFailedException.
- Scratch probe, passing run: core 1 succeeded and 0 failed; tests/contracts 1 succeeded and 0 failed. The RequiresAws-tagged test was excluded in both runs.

VALIDATION RESULTS:
- Format gate: works. It caught unformatted Dependencies.scala, and `sbt fmt` fixed it (comment rewrap only).
- Module graph: matches CLAUDE.md and is acyclic.
- Mixed-suffix check: fails when both suffixes appear, as observed; 0 conflicts after the exclusion.
- verify-tests-ran.sh: exit 0 against the current tree and marker.
- format-scala.sh works as written: the scalafmt CLI is not installed (`which scalafmt` found nothing), so the hook exits 0 and does nothing. If the CLI is installed, it reads .scalafmt.conf, including the version pin and the sbt1 and scala212 dialect overrides for build files. No hook change is needed.
- git status shows only the 6 created files. Generated target/ directories under modules/* and tests/ are ignored.

ASSUMPTIONS:
- Project ids and directories come from the CLAUDE.md table.
- organization "scholarchain", version 0.1.0.
- One services image for now: native-packager produces one launcher per main class, so the three D-10 services can share an image until the service-engineer asks for separate projects.
- "Shades" in D-03 means relocation, not just bundling.
- A vacuous pass (0 tests) still lets markTestRun write the marker.

RISKS:
- The required module source roots, the one trivial test per module, and the `.gitkeep` directories were not delivered. They are outside my rows (see BLOCKED-ON). Until they exist, check runs 0 tests.
- markTestRun's no-write-on-failure path is not verified end to end with markTestRun itself; the guard blocked that probe. It rests on sbt semantics: the task body runs only after the `test.all(...)` dependency succeeds. The test failure path itself was observed.
- dockerLocal is unverified: Docker is not installed, although a `/usr/local/bin/docker` path exists, which I did not run.
- The services image has no main class (and no launcher script under that name) until the service-engineer adds a main, and it exposes no ports.
- The scalafmt version is in .scalafmt.conf, which is outside the dependency-updater's value-level exception. Bumping it needs either a build-engineer work order or a factory-engineer exception change.
- Ignoring `.env*` also ignores `.env.example`. That is consistent with CLAUDE.md, which says placeholders never go in `.env*` files.
- Not done (Ponytail): no Spark fork or add-opens settings, no AWS SDK or HTTP library, no `.sbtopts`, `.jvmopts` or `.dockerignore`, no packaging/ directory. None is needed yet.

MANUAL ACTION REQUIRED:
- Optional: install the scalafmt CLI so format-scala.sh formats on write. `sbt check` remains the gate either way.
- Run `dockerLocal` with `SCHOLARCHAIN_IMAGE_TAG=<git sha>` on a machine with Docker once services has a main class.

BLOCKED-ON OWNER: domain-engineer, entity-resolution-engineer, hadoop-engineer, spark-engineer, storage-engineer, rag-engineer, service-engineer, test-engineer, dataset-engineer, infra-engineer, experiment-engineer, documentation-engineer, deploy-engineer, incident-responder, human architect

BLOCKED-ON EVIDENCE:
I did not attempt these writes; they are outside my rows. My docs/agents.md row and guard-paths.sh (lines 118-121) allow only `build.sbt`, `project/*`, `.scalafmt.conf`, `.gitignore`, `.sbtopts`, `.jvmopts`, `.dockerignore`, compose files and `packaging/*`.

Each module owner needs `src/main/scala` and `src/test/scala`, plus one trivial ScalaTest (org.scalatest.funsuite.AnyFunSuite):
- modules/core → domain-engineer. Also `config/.gitkeep`, and the ScalaTest tag objects `scholarchain.tags.RequiresAws` and `scholarchain.tags.RequiresDocker` if they are placed in core.
- modules/entity-resolution → entity-resolution-engineer
- modules/hadoop → hadoop-engineer
- modules/spark → spark-engineer. Its trivial test must not start a SparkSession until forked-test settings exist.
- modules/storage → storage-engineer
- modules/rag → rag-engineer. Also `prompts/runtime/.gitkeep` and `prompts/schemas/.gitkeep`.
- modules/services → service-engineer

`.gitkeep` directories, by owner:
- test-engineer: tests/contracts, tests/integration, tests/end-to-end, tests/adversarial, tests/fixtures
- dataset-engineer: datasets/scripts, datasets/schemas, datasets/samples
- infra-engineer: infra/terraform/environments, infra/terraform/modules, scripts
- experiment-engineer: experiments, load-tests
- documentation-engineer: reports
- deploy-engineer: operations/deployments, operations/pipeline-runs, operations/releases
- incident-responder: docs/incidents
- human architect: labels

After these land, rerun `sbt check` so that it runs tests in every module.

NEXT GATE: sbt check and code-reviewer APPROVE
````
