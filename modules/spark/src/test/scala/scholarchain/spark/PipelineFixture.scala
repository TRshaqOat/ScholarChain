package scholarchain.spark

import scholarchain.core.config.ConfigLoader

import java.nio.file.{Files, Path}

/** One run of the end-to-end pipeline (jobs 1 to 11) on the Fixture, shared by the suites that need
  * its outputs (the forked test JVM runs suites serially), so it runs once per test JVM.
  */
object PipelineFixture:
  lazy val tmp: Path = Files.createTempDirectory("sc-spark-pipeline")
  lazy val cfg = ConfigLoader.load(Map.empty).toOption.get
  lazy val base: SparkSettings = SparkSettings
    .parse(
      Seq(
        "--input",
        Fixture.writeRun(tmp.resolve("hadoop")).toString,
        "--out",
        tmp.resolve("out").toString,
        "--code-version",
        "test",
        "--master",
        "local[2]",
        "--shuffle-partitions",
        "2",
        "--checkpoint-dir",
        tmp.resolve("ck").toString,
        "--min-free-disk-gib",
        "0",
        "--cc-max-iterations",
        "30",
        "--sample-seed",
        "1"
      )
    )
    .toOption
    .get
  lazy val result: PipelineJob.RunResult = PipelineJob.run(TestSpark.spark, base, cfg)
