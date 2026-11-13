package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Corpus-level window summaries (Phase 10 work order 7, FU2): one row per configured window with
  * the graph's node and edge counts, components, largest component, singletons, communities, and
  * largest community, recomputed from the Phase 6 Parquet tables and checked against the Phase 6
  * `temporal/window-summary`.
  *
  * Input: one complete TemporalSummaryExportJob run directory (`--input`), read-only; its manifest
  * names the community-path, researcher-analytics, and canonical-graph runs, which must form one
  * chain. Output and run identity: JobRun (a new run; nothing in the input runs is written).
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.CorpusWindowSummaryJob --input
  * <temporal-summary-export run dir> --code-version <v> [--out <root>] [--checkpoint-dir <dir>]"`
  */
object CorpusWindowSummaryJob:
  val Stage = "spark-corpus-window-summary"
  val Table = "corpus/window-summary"
  val ViolationsTable = "corpus/window-summary-violations"
  val NodeBasis =
    "researchers with at least one DBLP publication in the window (one analytics/researcher-metrics " +
      "row each); researchers with only OpenAlex or Crossref authorship are not nodes"

  /** Columns shared with the Phase 6 `temporal/window-summary`; they must be equal. */
  val Phase6Checked = Seq(
    "researchers",
    "coauthorEdges",
    "sumJointPublicationCount",
    "components",
    "largestComponentSize",
    "singletonResearchers",
    "communities",
    "largestCommunitySize"
  )

  final case class RunResult(runId: String, attempt: Int, runDir: String, manifest: ujson.Obj)

  def main(args: Array[String]): Unit =
    val s = SparkSettings.parse(args.toSeq) match
      case Right(v) => v
      case Left(e)  => System.err.println(e); sys.exit(2)
    val cfg = ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity)
    val spark = JobRun.session(s, Stage)
    try
      val r = run(spark, s, cfg)
      println(s"runId=${r.runId} attempt=${r.attempt} runDir=${r.runDir}")
    finally spark.stop()

  /** The per-window values, from four Phase 6 tables (all keyed by `window`):
    *   - metrics: one row per graph node (researcher-metrics);
    *   - edges: coauthor edges with `jointPublicationCount`;
    *   - components: `componentId`, `componentSize`; communities: `communityId`, `communitySize`.
    *
    * Largest component and community: the biggest, ties to the smallest ID (as jobs 5 and 9). Count
    * columns are 0 for a window with no rows; fractions are null when the window has no researcher.
    * `componentMembers` and `communityMembers` (sums of sizes) are kept for the integrity checks.
    */
  def summarize(
      windows: DataFrame,
      metrics: DataFrame,
      edges: DataFrame,
      components: DataFrame,
      communities: DataFrame,
      minCommunitySize: Int
  ): DataFrame =
    def per(df: DataFrame, aggs: Column*) = df.groupBy("window").agg(aggs.head, aggs.tail*)
    def n(c: Column) = sum(when(c, 1L).otherwise(0L))
    def largest(df: DataFrame, id: String, size: String, as: String) = df
      .withColumn(
        "r",
        row_number().over(Window.partitionBy("window").orderBy(desc(size), col(id)))
      )
      .where(col("r") === 1)
      .select(col("window"), col(id).as(as))
    val counts = Seq(
      "researchers",
      "coauthorEdges",
      "sumJointPublicationCount",
      "components",
      "largestComponentSize",
      "singletonResearchers",
      "componentMembers",
      "communities",
      "largestCommunitySize",
      "singletonCommunities",
      "communitiesAtLeastMinSize",
      "communityMembers"
    )
    def share(c: String) =
      when(col("researchers") > 0, col(c) / col("researchers")).cast("double")
    Seq(
      per(metrics, count(lit(1)).as("researchers")),
      per(
        edges,
        count(lit(1)).as("coauthorEdges"),
        sum("jointPublicationCount").as("sumJointPublicationCount")
      ),
      per(
        components,
        count(lit(1)).as("components"),
        max("componentSize").as("largestComponentSize"),
        n(col("componentSize") === 1).as("singletonResearchers"),
        sum("componentSize").as("componentMembers")
      ),
      largest(components, "componentId", "componentSize", "largestComponentId"),
      per(
        communities,
        count(lit(1)).as("communities"),
        max("communitySize").as("largestCommunitySize"),
        n(col("communitySize") === 1).as("singletonCommunities"),
        n(col("communitySize") >= minCommunitySize).as("communitiesAtLeastMinSize"),
        sum("communitySize").as("communityMembers")
      ),
      largest(communities, "communityId", "communitySize", "largestCommunityId")
    ).foldLeft(windows)((d, x) => d.join(x, Seq("window"), "left"))
      .select(
        (windows.columns.toSeq.map(col) ++
          counts.map(c => coalesce(col(c).cast("long"), lit(0L)).as(c)) ++
          Seq(col("largestComponentId"), col("largestCommunityId")))*
      )
      .withColumn("years", col("windowEndYear") - col("windowStartYear") + 1)
      .withColumn("largestComponentFraction", share("largestComponentSize"))
      .withColumn("largestCommunityFraction", share("largestCommunitySize"))
      .withColumn("communityMinSize", lit(minCommunitySize))

  /** Integrity checks of a `summarize` result, one violation row per failure:
    *   - `COMPONENT_MEMBERS_MISMATCH` / `COMMUNITY_MEMBERS_MISMATCH`: the component (community)
    *     sizes of a window do not sum to its researchers (every node is in exactly one of each);
    *   - `PHASE6_WINDOW_SUMMARY_MISMATCH`: a `Phase6Checked` value (plus
    *     `communitiesAtLeastMinSize` when `sameMinSize`) differs from the Phase 6
    *     `temporal/window-summary` row, or the window has no Phase 6 row. subjectId = window,
    *     relatedId = column.
    */
  def violations(summary: DataFrame, phase6: DataFrame, sameMinSize: Boolean): DataFrame =
    def members(check: String, c: String) = Violations(
      check,
      summary.where(col(c) =!= col("researchers")),
      col("window"),
      lit(c),
      concat_ws(" != ", col(c).cast("string"), col("researchers").cast("string"))
    )
    val checked = Phase6Checked ++ Option.when(sameMinSize)("communitiesAtLeastMinSize")
    val p6 = phase6.select(
      (col("window") +: checked.map(c => col(c).cast("long").as(s"p6_$c")))*
    )
    val joined = summary.join(p6, Seq("window"), "left")
    (members("COMPONENT_MEMBERS_MISMATCH", "componentMembers") +:
      members("COMMUNITY_MEMBERS_MISMATCH", "communityMembers") +:
      checked.map(c =>
        Violations(
          "PHASE6_WINDOW_SUMMARY_MISMATCH",
          joined.where(!col(c).eqNullSafe(col(s"p6_$c"))),
          col("window"),
          lit(c),
          concat_ws(
            " != ",
            col(c).cast("string"),
            coalesce(col(s"p6_$c").cast("string"), lit("null"))
          )
        )
      )).reduce(_ union _)

  def run(spark: SparkSession, s: SparkSettings, cfg: ScholarChainConfig): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    def manifestOf(p: Path) =
      ujson.read(JobRun.readText(p.getFileSystem(hconf), new Path(p, "_manifest.json")))
    def sizeOf(p: Path) = p.getFileSystem(hconf).getContentSummary(p).getLength
    val exPath = new Path(s.input)
    val ex = manifestOf(exPath)
    require(ex("stage").str == TemporalSummaryExportJob.Stage, s"${s.input} is not an export run")
    val cpPath = new Path(ex("input")("path").str)
    val raPath = new Path(ex("input")("researcherAnalyticsPath").str)
    val cgPath = new Path(ex("input")("canonicalGraphPath").str)
    val (cp, ra, cg) = (manifestOf(cpPath), manifestOf(raPath), manifestOf(cgPath))
    require(cp("runId").str == ex("input")("communityPathRunId").str, "community-path run moved")
    require(ra("runId").str == ex("input")("researcherAnalyticsRunId").str, "analytics run moved")
    require(cg("runId").str == ex("input")("canonicalGraphRunId").str, "canonical run moved")
    require(cg("runId").str == ra("input")("canonicalGraphRunId").str, "runs do not form one chain")
    require(ra("runId").str == cp("input")("researcherAnalyticsRunId").str, "runs do not chain")
    val windows = cfg.graph.windows
    val windowNames = windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")
    require(
      ex("fingerprints").arr.exists(_.str == s"config:windows=$windowNames"),
      "configured windows differ from the input run's windows"
    )
    val graphPolicy = ex("settings")("graphPolicy").str
    val phase6MinSize = ex("settings")("summaryMinCommunitySize").num.toInt
    val fingerprints = Seq(
      s"temporal-summary-export-run=${ex("runId").str}",
      s"community-path-run=${cp("runId").str}",
      s"researcher-analytics-run=${ra("runId").str}",
      s"canonical-graph-run=${cg("runId").str}",
      s"input:temporal-summary-export=$exPath:${sizeOf(exPath)}",
      s"input:community-path=$cpPath:${sizeOf(cpPath)}",
      s"input:researcher-analytics=$raPath:${sizeOf(raPath)}",
      s"input:canonical-graph=$cgPath:${sizeOf(cgPath)}",
      s"config:graph-policy=$graphPolicy",
      s"config:windows=$windowNames",
      s"config:summary-min-community-size=${s.summaryMinCommunitySize}"
    )
    val jr = new JobRun(spark, Stage, s, cfg, ex("snapshot").str, fingerprints)
    val sourceRuns = ujson.Obj(
      "temporalSummaryExportRunId" -> ex("runId").str,
      "communityPathRunId" -> cp("runId").str,
      "researcherAnalyticsRunId" -> ra("runId").str,
      "canonicalGraphRunId" -> cg("runId").str,
      "inputHadoopRunId" -> ex("lineage")("inputHadoopRunId").str,
      "matchPolicyVersion" -> ex("lineage")("matchPolicyVersion").str
    )
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "temporalSummaryExportRunId" -> ex("runId").str,
        "communityPathPath" -> cpPath.toString,
        "researcherAnalyticsPath" -> raPath.toString,
        "canonicalGraphPath" -> cgPath.toString
      ),
      "lineage" -> ex("lineage"),
      "sourceRuns" -> sourceRuns,
      "settings" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "shufflePartitions" -> s.shufflePartitions,
        "graphPolicy" -> graphPolicy,
        "windows" -> ex("settings")("windows"),
        "communityMinSize" -> s.summaryMinCommunitySize,
        "phase6CommunityMinSize" -> phase6MinSize,
        "nodeBasis" -> NodeBasis,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble
      )
    )
    jr.start(head)
    import jr.{step, write}
    val ctx =
      Ctx(ex("snapshot").str, jr.runId, cg("settings")("canonicalPolicy").str, graphPolicy, 0)

    val recorded =
      Seq(exPath -> ex, cpPath -> cp, raPath -> ra, cgPath -> cg).map((p, m) => p.toString -> m)
    def input(dir: Path, rel: String, cols: String*): DataFrame = step("read inputs") {
      val p = new Path(dir, rel)
      val t = recorded.toMap.apply(dir.toString)("tables").arr.find(_("name").str == rel)
      // An empty partitioned table has no files to read: use the schema its manifest recorded.
      val raw =
        if t.exists(_("rowCount").num == 0) then
          spark.createDataFrame(java.util.List.of[Row](), StructType.fromDDL(t.get("schema").str))
        else spark.read.parquet(p.toString)
      val df = raw.select(cols.map(col)*)
      jr.recordInput(p.toString, p.getFileSystem(hconf).exists(p), df.count())
      df
    }
    val metrics = input(raPath, "analytics/researcher-metrics", "window", "researcherId")
    val components = input(raPath, "analytics/components", "window", "componentId", "componentSize")
    val communities =
      input(cpPath, "analytics/communities", "window", "communityId", "communitySize")
    val edges = input(cgPath, "graph/coauthor-edges", "window", "jointPublicationCount")
    val phase6Cols = ("window" +: Phase6Checked) :+ "communitiesAtLeastMinSize"
    val phase6 = input(exPath, "temporal/window-summary", phase6Cols*)

    // Label-propagation convergence per window, from the community-path manifest (3 values).
    val converged = cp("counts")("communityConverged").obj
    val convergence = spark.createDataFrame(
      java.util.Arrays.asList(
        windows.map(w => Row(w.name, converged.get(w.name).map(_.bool).orNull))*
      ),
      StructType.fromDDL("window STRING, communityConverged BOOLEAN")
    )
    val rounds = cp("counts")("communityIterations").num.toInt

    val summary = step("summarize") {
      summarize(
        CoauthorGraph.windows(spark, windows),
        metrics,
        edges,
        components,
        communities,
        s.summaryMinCommunitySize
      ).join(convergence, Seq("window"), "left")
        .withColumn("communityRounds", lit(rounds))
        .persist()
    }
    val stamped = summary
      .drop("componentMembers", "communityMembers")
      .withColumn("nodeBasis", lit(NodeBasis))
      .withColumn(
        "sourceRuns",
        struct(sourceRuns.value.toSeq.map((k, v) => lit(v.str).as(k))*)
      )
      .withColumn("provenance", ctx.provenance(col("window"), graphPolicy))
      .orderBy("windowStartYear", "windowEndYear")
    val written = write(Table, stamped, Seq("dynamodb", "s3"), 1)
    val violated = write(
      ViolationsTable,
      Violations.stamped(
        ctx,
        violations(summary, phase6, phase6MinSize == s.summaryMinCommunitySize)
      ),
      Seq("s3"),
      1
    )
    val counts = step("report") {
      ujson.Obj(
        "windowSummary" -> JobRun.rows(
          written.drop("provenance", "sourceRuns", "nodeBasis").orderBy("windowStartYear", "window")
        ),
        "integrityViolations" -> JobRun.rows(
          violated.drop("provenance").orderBy("check", "subjectId", "relatedId")
        )
      )
    }
    val manifest = jr.finish(head, "counts" -> counts)
    summary.unpersist()
    RunResult(jr.runId, jr.attempt, jr.runDir.toString, manifest)
