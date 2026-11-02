Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch.

Inputs to read first: CLAUDE.md, docs/agents.md, spec/ScholarChain-spec.md (Phase 2), spec/architect-decisions.md (D-01 to D-05, D-09, D-10, D-11).

Scope rule: every item in this work order is required. Minimal implementations are welcome, but no listed item may be skipped or deferred without reporting it under RISKS.

Required projects:
- core
- hadoop
- entityResolution
- sparkAnalytics
- storage
- rag
- services

Required principles:
- Scala 3 everywhere. Use a Scala 3 LTS release.
- sbt 1.x: pin sbt.version=1.13.0 in project/build.properties (matches the locally installed launcher; sbt 2 is out of scope for this project unless a documented compatibility problem requires it).
- Spark dependencies use CrossVersion.for3Use2_13 and target Spark 4.0 / Scala 2.13 / Java 17, matching EMR emr-spark-8.0.x (D-01, D-02). Never allow both _2.13 and _3 builds of the same library on the classpath; add a check or exclusion that makes a mixed classpath fail.
- Do not add a GraphFrames dependency; graph algorithms will use DataFrame iteration (D-10).
- Hadoop jobs use the Java MapReduce API. The hadoop module's job jar shades the Scala 3 standard library (D-03).
- Use Hadoop and Spark Java or DataFrame APIs where that avoids unsupported Scala binary assumptions.
- Pin every dependency and plugin version in a single version ledger. No floating or latest versions. Every pinned version must be a release at least two weeks old (D-09).
- If an EMR-provided version (Spark, Hadoop) cannot be verified from the repository, pin a documented provisional version and list it under RISKS for the AWS capability check to confirm.
- JSON: uPickle for the entire Scala codebase.
- Tests: ScalaTest, used consistently in every module.
- Keep Hadoop and Spark dependencies Provided when the runtime supplies them.
- Keep test-only libraries in Test scope.
- Keep the logging implementation in Runtime scope.
- Use scalafmt with the Scala 3 dialect, and confirm it works with .claude/hooks/format-scala.sh as written. If the hook needs a change, report BLOCKED-ON factory-engineer instead of editing it.
- Create a markTestRun task that updates .claude/.last-test-run only after tests pass, compatible with .claude/hooks/verify-tests-ran.sh.
- Define aliases: fmt, check, packageJobs, and dockerLocal.
- check must run format checking, compilation, tests, contract tests that do not need AWS, and markTestRun. check must not require Docker; Docker is not installed on the development machine, and only dockerLocal may depend on it.
- Container images must use a pinned Java 17 runtime image that supports linux/arm64 (the development machine is Apple Silicon and ECS Fargate will use the ARM64 runtime platform, D-11), a non-root user, and percentage-based memory limits.
- Build behavior may depend only on explicitly documented environment variables.
- No secrets, cloud endpoints, or deployment procedures in the build definition.

Create:
- build.sbt
- project/build.properties
- project/plugins.sbt
- the version ledger (for example project/Dependencies.scala)
- .scalafmt.conf
- .gitignore, covering at least: .env and .env*, target/, project/target/, project/project/, .bsp/, .bloop/, .metals/, .idea/, *.tfstate, *.tfstate.*, .terraform/, *.tfplan, scratch/, .claude/settings.local.json, .claude/.last-test-run, .DS_Store, *.log
- a local compose file for emulators or test dependencies only when justified
- empty module source roots, with one trivial test per module so sbt check exercises every module
- empty required top-level directories from the approved ownership map, kept with .gitkeep files

Do not create application logic, Terraform, deployment scripts, or agent-control files.

Verify on a clean repository state with sbt Test/compile and then sbt check once the marker test exists. Run sbt check a second time to confirm it is stable.

Report, in the standard report contract: the complete version ledger with the release date of each pin, the module dependency graph (it must be acyclic), the environment input surface, packaging choices, the .gitignore contents, and any compatibility risk involving Scala 3, Hadoop, Spark, or EMR.
