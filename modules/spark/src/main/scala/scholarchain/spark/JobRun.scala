package scholarchain.spark

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.functions.*
import org.apache.spark.SparkConf
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.{Column, DataFrame, SaveMode, SparkSession}
import scholarchain.core.StableIds
import scholarchain.core.config.ScholarChainConfig

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.collection.mutable

/** Run scaffolding shared by every Spark job (ruling 14). Output, through the Hadoop FileSystem API
  * (local or s3a): `<out>/snapshot=<snapshot>/run=<runId>/` with Parquet tables, `_started.json`
  * (claims the attempt), `_stages.json`, and `_manifest.json` (written last; a run directory
  * without it is incomplete). The run ID is StableIds.runId over the stage, code version, config
  * version, and the job's fingerprints; the attempt is the smallest unused one unless `--attempt`
  * is given. Nothing is ever overwritten. Constructing a JobRun checks free disk; `start` claims
  * the attempt.
  */
final class JobRun(
    spark: SparkSession,
    val stage: String,
    s: SparkSettings,
    cfg: ScholarChainConfig,
    val snapshot: String,
    val fingerprints: Seq[String]
):
  import JobRun.*
  private val t0 = System.nanoTime()
  val startedAt: String = Instant.now().toString
  val hconf = spark.sparkContext.hadoopConfiguration
  private val outRoot = new Path(s.out, s"snapshot=$snapshot")
  val ofs: FileSystem = outRoot.getFileSystem(hconf)
  private def idFor(a: Int) =
    StableIds.runId(stage, s.codeVersion, cfg.configVersion, fingerprints, a)
  private def dirFor(a: Int) = new Path(outRoot, s"run=${idFor(a)}")
  val attempt: Int = s.attempt.getOrElse(LazyList.from(1).find(a => !ofs.exists(dirFor(a))).get)
  val runId: String = idFor(attempt)
  val runDir: Path = dirFor(attempt)
  require(!ofs.exists(runDir), s"attempt $attempt exists: $runDir; outputs are never overwritten")
  ofs.mkdirs(outRoot)

  /** Free disk (ruling 17): stop before the output volume drops below the floor. */
  def checkDisk(when: String): Long =
    val free = ofs.getStatus(outRoot).getRemaining
    require(
      free >= s.minFreeDiskGiB * GiB,
      f"free disk ${free.toDouble / GiB}%.1f GiB is below ${s.minFreeDiskGiB} GiB $when; stopping"
    )
    free
  val freeBefore: Long = checkDisk("before the run")
  val checkpointDir = new Path(s.checkpointDir, runId)
  private val metrics = new StageMetrics
  private val timings = mutable.LinkedHashMap[String, Double]()
  private val tables = mutable.ArrayBuffer[ujson.Obj]()
  private val inputs = mutable.ArrayBuffer[ujson.Obj]()

  /** The manifest head: identity fields plus the job's own (input, settings). */
  def head(extra: (String, ujson.Value)*): ujson.Obj =
    ujson.Obj.from(
      Seq[(String, ujson.Value)](
        "schemaVersion" -> ManifestSchema,
        "stage" -> stage,
        "runId" -> runId,
        "attempt" -> attempt,
        "runDir" -> runDir.toString,
        "snapshot" -> snapshot,
        "codeVersion" -> s.codeVersion,
        "configVersion" -> cfg.configVersion
      ) ++ extra ++ Seq("fingerprints" -> strs(fingerprints), "startedAt" -> ujson.Str(startedAt))
    )

  /** create(overwrite = false) claims the attempt; a concurrent run of the same attempt fails. */
  def start(head: ujson.Obj): Unit =
    writeText(ofs, new Path(runDir, "_started.json"), ujson.write(head, indent = 2))
    spark.sparkContext.setCheckpointDir(checkpointDir.toString)
    spark.sparkContext.addSparkListener(metrics)

  def step[A](name: String)(body: => A): A =
    spark.sparkContext.setJobDescription(name)
    val t = System.nanoTime()
    val a = body
    timings(name) = timings.getOrElse(name, 0.0) + (System.nanoTime() - t) / 1e9
    a

  /** The table records written so far (path, schema, rowCount, targets, ...). */
  def writtenTables: Seq[ujson.Obj] = tables.toSeq

  def recordInput(path: String, exists: Boolean, rows: Long): Unit =
    inputs += ujson.Obj("path" -> path, "exists" -> exists, "rows" -> rows.toDouble)

  /** Writes one Parquet table (hash-repartitioned on `key` when given) and records it. Sets the
    * table's `schemaVersion` column (rulings 21, 22) and refuses a table without provenance.
    */
  def write(
      name: String,
      df0: DataFrame,
      targets: Seq[String],
      files: Int,
      key: Option[String] = None,
      partitionBy: Seq[String] = Nil
  ): DataFrame = step(s"write $name") {
    val path = new Path(runDir, name).toString
    val version = schemaVersionOf(name)
    val df = df0.withColumn("schemaVersion", lit(version))
    val problems = provenanceProblems(df.schema)
    require(problems.isEmpty, s"$name: ${problems.mkString("; ")}")
    key
      .fold(df.repartition(files))(k => df.repartition(files, col(k)))
      .write
      .mode(SaveMode.ErrorIfExists)
      .partitionBy(partitionBy*)
      .parquet(path)
    val parts = mutable.ArrayBuffer[Long]()
    val it = ofs.listFiles(new Path(path), true)
    while it.hasNext do
      val f = it.next()
      if f.getPath.getName.startsWith("part-") then parts += f.getLen
    // An empty partitioned write leaves no part file to read back: return the written schema,
    // partition columns last (as Parquet discovery orders them).
    val back =
      if parts.isEmpty && partitionBy.nonEmpty then
        df.select((df.columns.filterNot(partitionBy.contains) ++ partitionBy).toSeq.map(col)*)
          .limit(0)
      else spark.read.parquet(path)
    tables += ujson.Obj(
      "name" -> name,
      "schemaVersion" -> version,
      "path" -> path,
      "format" -> "parquet",
      "partitionColumns" -> strs(partitionBy),
      "hashPartitionKey" -> key.fold(ujson.Null)(ujson.Str(_)),
      "writePartitions" -> files,
      "schema" -> back.schema.toDDL,
      "rowCount" -> back.count().toDouble,
      "intendedTargets" -> strs(targets),
      "fileCount" -> parts.size,
      "totalBytes" -> parts.sum.toDouble,
      "minFileBytes" -> parts.minOption.getOrElse(0L).toDouble,
      "maxFileBytes" -> parts.maxOption.getOrElse(0L).toDouble
    )
    checkDisk(s"after writing $name")
    back
  }

  /** Removes this run's checkpoints, then writes `_stages.json` and `_manifest.json` (last). */
  def finish(head: ujson.Obj, extra: (String, ujson.Value)*): ujson.Obj =
    spark.sparkContext.removeSparkListener(metrics)
    checkpointDir.getFileSystem(hconf).delete(checkpointDir, true) // this run's own temp files
    val freeAfter = checkDisk("after the run")
    val manifest = ujson.Obj.from(head.value)
    manifest("inputs") = ujson.Arr.from(inputs)
    manifest("tables") = ujson.Arr.from(tables)
    extra.foreach((k, v) => manifest(k) = v)
    manifest("stepSeconds") = ujson.Obj.from(timings.toSeq.map((k, v) => k -> ujson.Num(v)))
    manifest("stagesByStep") = metrics.byStep
    manifest("stageCount") = metrics.stages.size
    manifest("disk") =
      ujson.Obj("freeBeforeBytes" -> freeBefore.toDouble, "freeAfterBytes" -> freeAfter.toDouble)
    manifest("wallSeconds") = (System.nanoTime() - t0) / 1e9
    manifest("finishedAt") = Instant.now().toString
    writeText(
      ofs,
      new Path(runDir, "_stages.json"),
      ujson.write(ujson.Arr.from(metrics.stages), indent = 2)
    )
    writeText(ofs, new Path(runDir, "_manifest.json"), ujson.write(manifest, indent = 2))
    manifest

object JobRun:
  val ManifestSchema = "spark-run-manifest-v1"
  private val GiB = 1024L * 1024 * 1024

  /** Version of a Spark analytic table's schema (ruling 22). These are Spark tables, not core
    * records; JOBS.md maps each to its nearest core record. Bump `TableSchemaGeneration` (or give a
    * table its own value here) when a table's columns change.
    */
  val TableSchemaGeneration = "v1"
  def schemaVersionOf(table: String): String =
    s"spark-${table.replace('/', '-')}-$TableSchemaGeneration"

  /** Fields a row's provenance struct must carry (core Provenance names; ruling 21). */
  val ProvenanceFields =
    Seq("schemaVersion", "sourceDataset", "sourceRecordId", "snapshot", "runId", "policyVersion")

  /** Why a table schema lacks the columns every written and exported table carries (rulings 21,
    * 22): a `schemaVersion` string and a `provenance` struct with ProvenanceFields. Empty if none.
    */
  def provenanceProblems(schema: StructType): Seq[String] =
    val version = schema.find(_.name == "schemaVersion").map(_.dataType)
    val prov = schema.find(_.name == "provenance").map(_.dataType)
    (if version.contains(StringType) then Nil else Seq("no schemaVersion STRING column")) ++
      (prov match
        case Some(st: StructType) =>
          ProvenanceFields.filterNot(st.fieldNames.contains).map(f => s"provenance has no $f")
        case _ => Seq("no provenance struct column"))

  /** The master to set (ruling 25): none when spark.master is already set (spark-submit --master,
    * or YARN on EMR), else the `master` setting (flag, or the conf's local[*] fallback).
    */
  def masterFor(preset: Option[String], setting: String): Option[String] =
    if preset.isDefined then None else Some(setting)

  def session(s: SparkSettings, appName: String): SparkSession =
    val b = SparkSession.builder()
    masterFor(new SparkConf().getOption("spark.master"), s.master).foreach(b.master)
    val spark = b
      .appName(appName)
      .config("spark.sql.shuffle.partitions", s.shufflePartitions.toString)
      .config("spark.ui.enabled", "false")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    spark

  def readText(fs: FileSystem, p: Path): String =
    val in = fs.open(p)
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  def writeText(fs: FileSystem, p: Path, text: String): Unit =
    val out = fs.create(p, false)
    try out.write(text.getBytes(UTF_8))
    finally out.close()

  private def js(v: Any): ujson.Value = v match
    case null                        => ujson.Null
    case b: java.lang.Boolean        => ujson.Bool(b)
    case n: java.lang.Number         => ujson.Num(n.doubleValue)
    case xs: scala.collection.Seq[?] => ujson.Arr.from(xs.map(js))
    case x                           => ujson.Str(x.toString)

  /** A bounded result (counts, top-N) as JSON rows. Callers only pass small aggregates. */
  def rows(df: DataFrame): ujson.Arr =
    val cols = df.columns.toSeq
    ujson.Arr.from(
      df.collect()
        .toSeq
        .map(r => ujson.Obj.from(cols.zipWithIndex.map((c, i) => c -> js(r.get(i)))))
    )

  def strs(xs: Seq[String]): ujson.Arr = ujson.Arr.from(xs.map(ujson.Str(_)))

  /** Size buckets of the manifest histograms. */
  def sizeBucket(n: Column): Column =
    when(n === 1, "1")
      .when(n === 2, "2")
      .when(n <= 5, "3-5")
      .when(n <= 10, "6-10")
      .when(n <= 100, "11-100")
      .when(n <= 1000, "101-1000")
      .otherwise("1001+")

  /** Per window, the exact min, median, p90, p99, and max of each named numeric column. */
  def distributions(df: DataFrame, names: Seq[String]): ujson.Obj =
    val d = df
      .groupBy("window")
      .agg(
        count(lit(1)).as("rows"),
        names.flatMap(n =>
          Seq(
            min(col(n)).cast("double").as(s"${n}_min"),
            expr(s"percentile($n, array(0.5, 0.9, 0.99))").as(s"${n}_p"),
            max(col(n)).cast("double").as(s"${n}_max")
          )
        )*
      )
      .collect() // one row per window
    ujson.Obj.from(d.toSeq.map { r =>
      r.getAs[String]("window") -> ujson.Obj.from(names.map { n =>
        val ps = r.getAs[scala.collection.Seq[Double]](s"${n}_p")
        n -> ujson.Obj(
          "min" -> r.getAs[Double](s"${n}_min"),
          "median" -> ps(0),
          "p90" -> ps(1),
          "p99" -> ps(2),
          "max" -> r.getAs[Double](s"${n}_max")
        )
      })
    })
