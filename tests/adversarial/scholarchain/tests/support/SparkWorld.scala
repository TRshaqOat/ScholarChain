package scholarchain.tests.support

import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

import java.nio.file.Files

/** Phase 11 work order 4 (ruling 12). The one local SparkSession of the forked tests JVM, shared by
  * every Spark suite under tests/ (repair-01 Part A: the tests project forks with the Spark JVM
  * options and -Xmx2g). It is never stopped: stopping it would break the suites that run after. The
  * driver binds 127.0.0.1 only, the UI is off, and checkpoints go to a JVM temporary directory
  * removed when the JVM exits. Fixtures stay tiny.
  */
object SparkWorld:
  private lazy val checkpoints = Files.createTempDirectory("tests-spark-checkpoints-")

  lazy val spark: SparkSession =
    val s = SparkSession
      .builder()
      .master("local[2]")
      .appName("scholarchain-tests")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
    s.sparkContext.setLogLevel("ERROR")
    s.sparkContext.setCheckpointDir(checkpoints.toString)
    sys.addShutdownHook(StoreWorld.deleteTree(checkpoints))
    s

  /** A DataFrame from a DDL schema and rows of plain values (null for a SQL null). */
  def df(ddl: String, rows: Seq[Any]*): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(rows.map(r => Row.fromSeq(r))*),
      StructType.fromDDL(ddl)
    )

  /** The rows of a small result as plain Scala tuples, sorted, for exact comparison. */
  def rows(d: DataFrame, cols: String*): Vector[Seq[Any]] =
    d.select(cols.head, cols.tail*).collect().toVector.map(_.toSeq)
