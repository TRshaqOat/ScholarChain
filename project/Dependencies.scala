import sbt._

/** ScholarChain version ledger: every library and base-image version is pinned here, once.
  *
  * Plugin versions are in project/plugins.sbt, sbt.version in project/build.properties, and the
  * scalafmt version in .scalafmt.conf, because those tools read them before this file loads. Dates
  * are Maven Central or Docker Hub publication dates; D-09 requires each pin to be at least 14 days
  * old when chosen.
  */
object Versions {
  // Scala 3.3.7 depends on scala-library 2.13.16, matching the EMR emr-spark-8.0.0 runtime.
  val scala = "3.3.7" // 2025-10-09, Scala 3.3 LTS (stdlib is scala-library 2.13.16)
  val javaRelease = "17" // EMR emr-spark-8.0.x and the runtime image are Java 17

  // Target runtime per AWS release documentation for emr-spark-8.0.0 (released 2026-05-21):
  // Spark 4.0.2-amzn-0, Scala 2.13.16, Hadoop 3.4.2-amzn-1, Java 17 (capability check 2026-11-02).
  val spark = "4.0.2" // 2026-02-02, Scala 2.13.16 build
  val hadoop = "3.4.2" // 2025-08-20, matches EMR Hadoop 3.4.2-amzn-1

  val upickle = "4.4.3" // 2026-02-14, the only JSON library (D-05)
  val scalaTest = "3.2.20" // 2026-03-25, the only test framework (D-05)
  val logback = "1.5.38" // 2026-07-09, logging implementation (Runtime scope only)

  // Multi-arch index digest (includes linux/arm64, D-11); tag pushed 2026-09-18.
  val javaRuntimeImage =
    "eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8fda3408cfe792db2b666c0e3db8ea4ae263ab4d0c3805933e5355f45"
}

object Dependencies {
  val upickle = "com.lihaoyi" %% "upickle" % Versions.upickle
  val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest % Test
  val logback = "ch.qos.logback" % "logback-classic" % Versions.logback % Runtime

  // The EMR runtime supplies Hadoop and Spark, so both stay Provided (still on the Test classpath).
  val hadoop = Seq("hadoop-client-api", "hadoop-client-runtime")
    .map("org.apache.hadoop" % _ % Versions.hadoop % Provided)
  val sparkSql =
    ("org.apache.spark" %% "spark-sql" % Versions.spark % Provided).cross(CrossVersion.for3Use2_13)
}
