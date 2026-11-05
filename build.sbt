// ScholarChain sbt build. Owner: build-engineer. Versions live only in project/Dependencies.scala.
//
// Environment inputs (the only ones this build reads):
//   SCHOLARCHAIN_IMAGE_TAG  immutable image tag (Git SHA) for dockerLocal; required by dockerLocal only.
//   SCHOLARCHAIN_SPARK_HEAP heap (-Xmx) of the forked sparkAnalytics run/runMain JVM; optional,
//                           default 6g. Keep it at most half of physical RAM (phase-06 ruling 17).
//
// Aliases: fmt, check (the definition of done), packageJobs, dockerLocal. check never needs Docker or AWS.

ThisBuild / organization := "scholarchain"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := Versions.scala

val ImageTagEnv = "SCHOLARCHAIN_IMAGE_TAG"
val SparkHeapEnv = "SCHOLARCHAIN_SPARK_HEAP"

// The JVM options Spark 4.0.2 itself adds on Java 17 (org.apache.spark.launcher.JavaModuleOptions).
val SparkJvmOptions = Seq(
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-modules=jdk.incubator.vector",
  "-Djdk.reflect.useDirectMethodHandle=false",
  "-Dio.netty.tryReflectionSetAccessible=true",
  "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED"
) ++ Seq(
  "java.lang",
  "java.lang.invoke",
  "java.lang.reflect",
  "java.io",
  "java.net",
  "java.nio",
  "java.util",
  "java.util.concurrent",
  "java.util.concurrent.atomic",
  "jdk.internal.ref",
  "sun.nio.ch",
  "sun.nio.cs",
  "sun.security.action",
  "sun.util.calendar"
).map(p => s"--add-opens=java.base/$p=ALL-UNNAMED")

lazy val markTestRun =
  taskKey[Unit]("Runs every project's tests, then writes .claude/.last-test-run")
lazy val requireImageTag = taskKey[Unit](s"Fails unless $ImageTagEnv is set")

lazy val commonSettings = Seq(
  // Fail resolution when one library resolves with two suffixes, e.g. both _2.13 and _3 (D-02).
  conflictWarning := ConflictWarning("cross-version", Level.Error, failOnConflict = true),
  libraryDependencies += Dependencies.scalaTest,
  // Pin the Scala 2.13 stdlib to the EMR runtime's version (phase-02 review risk, D-02).
  dependencyOverrides += Dependencies.scalaLibrary,
  scalacOptions ++= Seq("-deprecation", "-feature", "-release", Versions.javaRelease),
  javacOptions ++= Seq("--release", Versions.javaRelease),
  // check stays AWS-free and Docker-free: tests tagged with these names run only outside check.
  Test / testOptions += Tests.Argument(
    TestFrameworks.ScalaTest,
    "-l",
    "scholarchain.tags.RequiresAws",
    "-l",
    "scholarchain.tags.RequiresDocker"
  )
)

def module(id: String, dir: String): Project =
  Project(id, file(s"modules/$dir")).settings(commonSettings, name := id)

lazy val core = module("core", "core")
  .settings(libraryDependencies ++= Seq(Dependencies.upickle, Dependencies.typesafeConfig))

lazy val entityResolution = module("entityResolution", "entity-resolution")
  .dependsOn(core)
  .settings(libraryDependencies += Dependencies.bedrockRuntime) // bounded LLM adjudication

lazy val hadoop = module("hadoop", "hadoop")
  .dependsOn(core, entityResolution)
  .settings(
    libraryDependencies ++= Dependencies.hadoop,
    // hadoop uses only entityResolution's pure functions; EMR supplies the AWS SDK v2, so cut its
    // whole subtree (netty, httpclient, ...) from the classpath and the job jar.
    excludeDependencies += ExclusionRule("software.amazon.awssdk"),
    // The cluster has no Scala 3 stdlib (and EMR 7.x may expose 2.12), so relocate ours (D-03).
    assembly / assemblyShadeRules := Seq(
      ShadeRule.rename("scala.**" -> "scholarchain.shaded.scala.@1").inAll
    )
  )

lazy val sparkAnalytics = module("sparkAnalytics", "spark")
  .dependsOn(core)
  .settings(
    libraryDependencies += Dependencies.sparkSql,
    // ScalaTest brings scala-xml_3 and Spark brings scala-xml_2.13; keep the runtime's 2.13 build.
    excludeDependencies += ExclusionRule("org.scala-lang.modules", "scala-xml_3"),
    // Spark supplies scala-library 2.13 at runtime; the job jar carries only scala3-library_3.
    assembly / assemblyExcludedJars := (assembly / fullClasspath).value.filter(
      _.data.getName.startsWith("scala-library-")
    ),
    // One shared local SparkSession per test JVM: fork, open the JDK modules, run suites serially.
    Test / fork := true,
    Test / parallelExecution := false,
    Test / javaOptions ++= SparkJvmOptions :+ "-Xmx2g",
    // run/runMain put Provided Spark on the classpath, fork, and resolve relative paths from the
    // repository root (so scratch/spark/ means the repo's scratch/spark/).
    Compile / run := Defaults
      .runTask(Compile / fullClasspath, Compile / run / mainClass, Compile / run / runner)
      .evaluated,
    Compile / runMain := Defaults
      .runMainTask(Compile / fullClasspath, Compile / run / runner)
      .evaluated,
    run / fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    run / javaOptions ++= SparkJvmOptions :+ s"-Xmx${sys.env.getOrElse(SparkHeapEnv, "6g")}"
  )

lazy val storage = module("storage", "storage")
  .dependsOn(core)

lazy val rag = module("rag", "rag")
  .dependsOn(core, storage)

lazy val services = module("services", "services")
  .dependsOn(core, rag, storage)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    libraryDependencies += Dependencies.logback,
    dockerBaseImage := Versions.javaRuntimeImage,
    Docker / packageName := "scholarchain-services",
    Docker / version := sys.env.getOrElse(ImageTagEnv, "unset"),
    dockerUpdateLatest := false,
    Docker / daemonUser := "scholarchain",
    Docker / daemonUserUid := Some("10001"),
    dockerBuildOptions += "--platform=linux/arm64",
    Universal / javaOptions ++= Seq(
      "-J-XX:InitialRAMPercentage=50.0",
      "-J-XX:MaxRAMPercentage=75.0"
    )
  )

// Cross-module, integration, end-to-end, and adversarial tests (test-engineer). Test scope only.
lazy val tests = Project("tests", file("tests"))
  .dependsOn(
    Seq(core, entityResolution, hadoop, sparkAnalytics, storage, rag, services)
      .map(p => p % Test: ClasspathDep[ProjectReference]): _*
  )
  .settings(
    commonSettings,
    publish / skip := true,
    Test / unmanagedSourceDirectories := Seq(
      "contracts",
      "integration",
      "end-to-end",
      "adversarial"
    )
      .map(baseDirectory.value / _),
    Test / unmanagedResourceDirectories := Seq(baseDirectory.value / "fixtures")
  )

lazy val root = Project("scholarchain", file("."))
  .aggregate(core, entityResolution, hadoop, sparkAnalytics, storage, rag, services, tests)
  .settings(
    publish / skip := true,
    markTestRun := {
      val _ = test
        .all(ScopeFilter(inAggregates(ThisProject, includeRoot = false), inConfigurations(Test)))
        .value
      // Reached only when every aggregated project's tests passed.
      val marker = (ThisBuild / baseDirectory).value / ".claude" / ".last-test-run"
      IO.write(marker, java.time.Instant.now().toString + "\n")
      streams.value.log.info(s"markTestRun: tests passed, wrote $marker")
    },
    requireImageTag := {
      if (sys.env.get(ImageTagEnv).forall(_.trim.isEmpty))
        sys.error(s"dockerLocal needs $ImageTagEnv set to an immutable tag (Git SHA)")
    }
  )

addCommandAlias("fmt", "; scalafmtSbt; scalafmtAll")
addCommandAlias("check", "; scalafmtSbtCheck; scalafmtCheckAll; Test/compile; markTestRun")
addCommandAlias("packageJobs", "; hadoop/assembly; sparkAnalytics/assembly")
addCommandAlias("dockerLocal", "; requireImageTag; services/Docker/publishLocal")
