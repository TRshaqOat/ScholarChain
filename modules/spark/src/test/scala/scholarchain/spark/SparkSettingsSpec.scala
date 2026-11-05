package scholarchain.spark

import org.scalatest.funsuite.AnyFunSuite

class SparkSettingsSpec extends AnyFunSuite:
  test("defaults come from scholarchain-spark.conf and flags override them") {
    val s = SparkSettings.parse(
      Seq("--input", "in", "--code-version", "abc", "--shuffle-partitions", "7")
    )
    assert(s.map(_.shufflePartitions) == Right(7))
    assert(s.map(_.master) == Right("local[*]"))
    assert(s.map(_.out) == Right("scratch/spark"))
    assert(s.map(_.checkpointDir) == Right("scratch/spark/checkpoints"))
    assert(s.map(_.minFreeDiskGiB) == Right(5L))
    assert(s.map(_.communityMaxIterations) == Right(10)) // ruling 8
    assert(s.map(_.pathMaxDepth) == Right(6)) // ruling 11
    assert(s.map(x => (x.pathSamplePairs, x.pathSampleSources)) == Right((50, 20)))
    assert(s.map(_.pairs) == Right(None))
    assert(s.map(x => (x.summaryMinPublications, x.summaryMinCommunitySize)) == Right((2, 3)))
    val p = SparkSettings.parse(Seq("--input", "in", "--code-version", "a", "--pairs", "p.csv"))
    assert(p.map(_.pairs) == Right(Some("p.csv")))
  }

  test("missing required flags, unknown flags, and bad values are refused") {
    assert(SparkSettings.parse(Seq("--code-version", "abc")).isLeft)
    assert(SparkSettings.parse(Seq("--input", "in", "--code-version", "a", "--bogus", "1")).isLeft)
    assert(
      SparkSettings.parse(Seq("--input", "in", "--code-version", "a", "--attempt", "0")).isLeft
    )
    assert(SparkSettings.parse(Seq("--input", "in")).isLeft)
    assert(SparkSettings.parse(Seq("--input", "in", "--code-version", " ")).isLeft) // ruling 24
    assert(
      SparkSettings
        .parse(Seq("--input", "in", "--code-version", "a", "--path-max-depth", "0"))
        .isLeft
    )
  }

  test("the master is set only when spark.master is not already set (ruling 25)") {
    assert(JobRun.masterFor(Some("yarn"), "local[*]").isEmpty) // spark-submit --master yarn
    assert(JobRun.masterFor(None, "local[*]").contains("local[*]")) // local runMain fallback
    assert(JobRun.masterFor(None, "local[2]").contains("local[2]")) // --master flag
  }
