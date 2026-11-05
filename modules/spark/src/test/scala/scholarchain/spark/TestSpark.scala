package scholarchain.spark

import org.apache.spark.sql.SparkSession

import java.nio.file.Files

/** The one local SparkSession shared by every suite in the forked test JVM. */
object TestSpark:
  lazy val spark: SparkSession =
    val s = SparkSession
      .builder()
      .master("local[2]")
      .appName("sparkAnalytics-tests")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.ui.enabled", "false")
      .getOrCreate()
    s.sparkContext.setLogLevel("ERROR")
    s

  def freshCheckpointDir(): Unit =
    spark.sparkContext.setCheckpointDir(Files.createTempDirectory("sc-ck").toString)
