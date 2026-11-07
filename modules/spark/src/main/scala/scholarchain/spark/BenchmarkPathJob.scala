package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}
import scholarchain.core.StableIds
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Phase 7 ruling 25: a bounded rerun of job 5 (path evidence) and the path part of job 10 for the
  * seeded pairs of a CommunityPathJob run plus the researcher pairs of the human benchmark file.
  *
  * Input: one complete CommunityPathJob run directory (`--input`), read-only: its
  * `paths/selected-pairs.csv` (the seeded pairs) and `paths/path-evidence` (to check that the
  * seeded paths are unchanged), and the researcher-analytics and canonical-graph runs its manifest
  * names. `--benchmark <csv>`: the benchmark questions file (header `question_id, ...,
  * researcher_a, researcher_b, window, ...`); rows naming both researchers give pairs.
  *
  * Name resolution (rule `dominant-name-v1`, Phase 7 ruling 30): the candidates of a name are the
  * canonical researchers whose `displayName` equals the trimmed name exactly. When one candidate
  * holds at least `--dominant-share` (default 0.9) of the AUTHORED works summed over all candidates
  * and at least `--dominant-min-works` (default 5) works, the name is RESOLVED_DOMINANT to it;
  * otherwise it is AMBIGUOUS; with no candidate UNKNOWN_RESEARCHER. An unresolved pair gets no
  * path. Window `all` is the first configured window (`all-years`).
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.BenchmarkPathJob --input <community-path
  * run dir> --benchmark <csv> --code-version <v> --out scratch/stores/spark --checkpoint-dir
  * scratch/stores/checkpoints"`
  */
object BenchmarkPathJob:
  val Stage = "spark-benchmark-paths"
  val ResolutionRule = "dominant-name-v1"
  val Resolved = "RESOLVED_DOMINANT"
  val Ambiguous = "AMBIGUOUS"
  val Unknown: String = ShortestPaths.UnknownResearcher
  private val SampleIds = 10

  final case class RunResult(runId: String, attempt: Int, runDir: String, manifest: ujson.Obj)

  /** A benchmark question naming two researchers. */
  final case class Question(questionId: String, window: String, nameA: String, nameB: String)

  /** Ruling 30 thresholds: the top candidate's minimum share of the candidates' works (above 0.5,
    * so at most one candidate can dominate) and its minimum work count.
    */
  final case class Dominance(share: Double, minWorks: Long):
    require(share > 0.5 && share <= 1.0, s"dominant share $share not in (0.5, 1]")
    require(minWorks >= 1, s"dominant min works $minWorks < 1")

  /** The resolution of one name: status, the researcher when RESOLVED_DOMINANT, the candidate
    * count, the top candidate's share of the candidates' AUTHORED works (None without candidates or
    * works), the top candidate's and all candidates' works, and the first candidates in rank order.
    */
  final case class Resolution(
      status: String,
      id: Option[String],
      candidates: Long,
      topShare: Option[Double],
      topWorks: Long,
      totalWorks: Long,
      sample: Seq[String]
  )

  def main(args: Array[String]): Unit =
    val (extra, rest) =
      JobArgs.split(args.toSeq, Set("benchmark", "dominant-share", "dominant-min-works"))
    val s = SparkSettings.parse(rest) match
      case Right(v) => v
      case Left(e)  => System.err.println(e); sys.exit(2)
    val benchmark =
      extra.getOrElse("benchmark", { System.err.println("--benchmark <csv>"); sys.exit(2) })
    val dom = Dominance(
      extra.get("dominant-share").fold(0.9)(_.toDouble),
      extra.get("dominant-min-works").fold(5L)(_.toLong)
    )
    val cfg = ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity)
    val spark = JobRun.session(s, Stage)
    try
      val r = run(spark, s, cfg, benchmark, dom)
      println(s"runId=${r.runId} attempt=${r.attempt} runDir=${r.runDir}")
    finally spark.stop()

  /** Questions of the benchmark file that name two researchers, in question order. The window `all`
    * becomes `allWindow`; any other window must be configured.
    */
  def questions(
      spark: SparkSession,
      path: String,
      allWindow: String,
      windows: Set[String]
  ): Seq[Question] =
    spark.read
      .option("header", "true")
      .option("mode", "FAILFAST")
      .schema(
        "question_id STRING, category STRING, question_text STRING, researcher_a STRING, " +
          "researcher_b STRING, window STRING, answer_type STRING, should_abstain STRING, notes STRING"
      )
      .csv(path)
      .where(
        length(trim(col("researcher_a"))) > 0 && length(trim(col("researcher_b"))) > 0
      )
      .select(
        trim(col("question_id")),
        trim(col("window")),
        trim(col("researcher_a")),
        trim(col("researcher_b"))
      )
      .orderBy(col("question_id"))
      .collect() // bounded: the human-authored benchmark file (30 questions)
      .toSeq
      .map { r =>
        val w = if r.getString(1) == "all" then allWindow else r.getString(1)
        require(windows(w), s"benchmark ${r.getString(0)}: unknown window ${r.getString(1)}")
        Question(r.getString(0), w, r.getString(2), r.getString(3))
      }

  /** Rule dominant-name-v1 per display name, over candidates (researcherId, displayName,
    * mentionCount) and authored (researcherId, workId; one row per researcher and work). Candidates
    * are ranked by works descending, then researcherId. One row per name: name, candidates,
    * totalWorks, topId, topWorks, topShare (null when totalWorks is 0), totalMentions, topMentions,
    * sample (the first 10 IDs in rank order), status (RESOLVED_DOMINANT or AMBIGUOUS).
    */
  def dominance(researchers: DataFrame, authored: DataFrame, d: Dominance): DataFrame =
    val works = authored.groupBy("researcherId").agg(count(lit(1)).as("works"))
    val ranked = researchers
      .where(col("displayName").isNotNull)
      .select(
        col("displayName").as("name"),
        col("researcherId"),
        coalesce(col("mentionCount").cast("long"), lit(0L)).as("mentions")
      )
      .join(works, Seq("researcherId"), "left")
      .withColumn("works", coalesce(col("works"), lit(0L)))
      .withColumn(
        "rank",
        row_number().over(
          org.apache.spark.sql.expressions.Window
            .partitionBy("name")
            .orderBy(col("works").desc, col("researcherId"))
        )
      )
    def top(c: String) = max(when(col("rank") === 1, col(c)))
    ranked
      .groupBy("name")
      .agg(
        count(lit(1)).as("candidates"),
        sum("works").as("totalWorks"),
        top("researcherId").as("topId"),
        top("works").as("topWorks"),
        sum("mentions").as("totalMentions"),
        top("mentions").as("topMentions"),
        transform(
          sort_array(
            collect_list(when(col("rank") <= SampleIds, struct(col("rank"), col("researcherId"))))
          ),
          _.getField("researcherId")
        ).as("sample")
      )
      .withColumn(
        "topShare",
        when(col("totalWorks") > 0, col("topWorks").cast("double") / col("totalWorks"))
      )
      .withColumn(
        "status",
        when(
          col("topWorks") >= d.minWorks && col("topShare") >= d.share,
          lit(Resolved)
        ).otherwise(lit(Ambiguous))
      )

  /** Resolves names by rule dominant-name-v1. The candidates are cut to the wanted names (a
    * broadcast join) before ranking; only one row per name reaches the driver.
    */
  def resolve(
      spark: SparkSession,
      researchers: DataFrame,
      authored: DataFrame,
      names: Seq[String],
      d: Dominance
  ): Map[String, Resolution] =
    val wanted = spark.createDataFrame(
      java.util.Arrays.asList(names.distinct.map(Row(_))*),
      StructType.fromDDL("name STRING")
    )
    val cands = researchers.join(broadcast(wanted), col("displayName") === col("name"), "left_semi")
    val found = dominance(cands, authored, d)
      .select(
        "name",
        "status",
        "topId",
        "candidates",
        "topShare",
        "topWorks",
        "totalWorks",
        "sample"
      )
      .collect() // one row per benchmark name
      .map { r =>
        r.getString(0) -> Resolution(
          r.getString(1),
          Option.when(r.getString(1) == Resolved)(r.getString(2)),
          r.getLong(3),
          Option.when(!r.isNullAt(4))(r.getDouble(4)),
          r.getLong(5),
          r.getLong(6),
          r.getSeq[String](7).toSeq
        )
      }
      .toMap
    names.distinct
      .map(n => n -> found.getOrElse(n, Resolution(Unknown, None, 0, None, 0, 0, Nil)))
      .toMap

  /** Corpus-wide scale of split identities (work order 6b, report only): over the display names
    * with more than one canonical researcher, how many have a dominant candidate under the rule,
    * and the candidates, mentions, and works (researcher-work rows) the non-dominant candidates
    * hold; plus the corpus totals for scale. One row.
    */
  def splitIdentities(dom: DataFrame): DataFrame =
    val multi = col("candidates") > 1
    val split = multi && col("status") === Resolved
    def n(c: Column) = sum(when(c, 1L).otherwise(0L))
    def s(c: Column, v: Column) = sum(when(c, v).otherwise(0L))
    dom.agg(
      count(lit(1)).as("distinctDisplayNames"),
      sum("candidates").as("researchers"),
      sum("totalMentions").as("mentions"),
      sum("totalWorks").as("authoredRows"),
      n(multi).as("namesWithSeveralResearchers"),
      s(multi, col("candidates")).as("researchersUnderSharedNames"),
      n(split).as("namesWithDominantCandidate"),
      s(split, col("candidates") - 1).as("nonDominantResearchers"),
      s(split, col("totalMentions") - col("topMentions")).as("nonDominantMentions"),
      s(split, col("totalWorks") - col("topWorks")).as("nonDominantAuthoredRows"),
      s(split, col("topMentions")).as("dominantMentions"),
      s(split, col("topWorks")).as("dominantAuthoredRows"),
      n(multi && col("status") === Ambiguous).as("namesStillAmbiguous"),
      max(when(split, col("candidates"))).as("maxCandidatesUnderDominantName")
    )

  /** A pair is searched only when both names resolved. UNKNOWN_RESEARCHER wins over AMBIGUOUS. */
  def pairStatus(a: Resolution, b: Resolution): String =
    if a.status == Unknown || b.status == Unknown then Unknown
    else if a.status == Ambiguous || b.status == Ambiguous then Ambiguous
    else Resolved

  def run(
      spark: SparkSession,
      s: SparkSettings,
      cfg: ScholarChainConfig,
      benchmark: String,
      dom: Dominance = Dominance(0.9, 5)
  ): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    def manifestOf(p: Path) =
      ujson.read(JobRun.readText(p.getFileSystem(hconf), new Path(p, "_manifest.json")))
    def sizeOf(p: Path) = p.getFileSystem(hconf).getContentSummary(p).getLength
    val cpPath = new Path(s.input)
    val cp = manifestOf(cpPath)
    require(cp("stage").str == CommunityPathJob.Stage, s"${s.input} is not a community-path run")
    val raPath = new Path(cp("input")("path").str)
    val cgPath = new Path(cp("input")("canonicalGraphPath").str)
    require(manifestOf(raPath)("runId").str == cp("input")("researcherAnalyticsRunId").str)
    require(manifestOf(cgPath)("runId").str == cp("input")("canonicalGraphRunId").str)
    val windows = cfg.graph.windows
    val windowNames = windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")
    require(
      cp("fingerprints").arr.exists(_.str == s"config:windows=$windowNames"),
      "configured windows differ from the input run's windows"
    )
    val graphPolicy = cp("settings")("graphPolicy").str
    val benchPath = new Path(benchmark)
    val benchText = JobRun.readText(benchPath.getFileSystem(hconf), benchPath)
    val seededText =
      JobRun.readText(cpPath.getFileSystem(hconf), new Path(cpPath, "paths/selected-pairs.csv"))
    val fingerprints = Seq(
      s"community-path-run=${cp("runId").str}",
      s"input:community-path=$cpPath:${sizeOf(cpPath)}",
      s"input:researcher-analytics=$raPath:${sizeOf(raPath)}",
      s"input:canonical-graph=$cgPath:${sizeOf(cgPath)}",
      s"config:graph-policy=$graphPolicy",
      s"config:windows=$windowNames",
      s"config:path-max-depth=${s.pathMaxDepth}",
      s"config:summary-template=${Summaries.TemplateVersion}",
      s"benchmark=${StableIds.textHash(benchText)}",
      s"resolution-rule=$ResolutionRule:share=${dom.share}:min-works=${dom.minWorks}"
    )
    val jr = new JobRun(spark, Stage, s, cfg, cp("snapshot").str, fingerprints)
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "communityPathRunId" -> cp("runId").str,
        "researcherAnalyticsPath" -> raPath.toString,
        "researcherAnalyticsRunId" -> cp("input")("researcherAnalyticsRunId").str,
        "canonicalGraphPath" -> cgPath.toString,
        "canonicalGraphRunId" -> cp("input")("canonicalGraphRunId").str,
        "benchmarkFile" -> benchmark,
        "benchmarkTextHash" -> StableIds.textHash(benchText)
      ),
      "lineage" -> cp("lineage"),
      "settings" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "shufflePartitions" -> s.shufflePartitions,
        "checkpointDir" -> jr.checkpointDir.toString,
        "graphPolicy" -> graphPolicy,
        "windows" -> ujson.Arr.from(windows.map(w => ujson.Str(w.name))),
        "pathAlgorithm" -> "bounded multi-source BFS, smallest-parent tie-break (job 5)",
        "pathMaxDepth" -> s.pathMaxDepth,
        "resolutionRule" -> (ResolutionRule +
          ": candidates are the researchers whose displayName equals the trimmed name; the top " +
          "candidate (works desc, researcherId) is RESOLVED_DOMINANT when it holds >= share of " +
          "the candidates' AUTHORED works and >= min-works works, else AMBIGUOUS; no candidate " +
          "UNKNOWN_RESEARCHER; window 'all' = " + windows.head.name),
        "dominantShare" -> dom.share,
        "dominantMinWorks" -> dom.minWorks.toDouble,
        "summaryTemplateVersion" -> Summaries.TemplateVersion,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble
      )
    )
    jr.start(head)
    import jr.{step, write}
    val ctx = Ctx(cp("snapshot").str, jr.runId, "", graphPolicy, 0)

    def input(dir: Path, rel: String, cols: String*): DataFrame = step("read inputs") {
      val p = new Path(dir, rel)
      val df = spark.read.parquet(p.toString).select(cols.map(col)*).persist()
      jr.recordInput(p.toString, p.getFileSystem(hconf).exists(p), df.count())
      df
    }
    val metrics =
      input(raPath, "analytics/researcher-metrics", "window", "researcherId", "componentId")
    val edges = input(
      cgPath,
      "graph/coauthor-edges",
      "window",
      "edgeId",
      "researcherA",
      "researcherB",
      "jointPublicationCount",
      "firstCollaborationYear",
      "lastCollaborationYear",
      "representativeWorkIds"
    )
    val researchers =
      input(cgPath, "canonical/researchers", "researcherId", "displayName", "mentionCount")
    val authored = input(cgPath, "canonical/authored", "researcherId", "workId")
    val works = input(cgPath, "canonical/works", "workId", "title")
    val before =
      input(cpPath, "paths/path-evidence", "pairId", "status", "pathLength", "researcherIds")
    val w = CoauthorGraph.windows(spark, windows)

    // ---- Benchmark names -> canonical IDs (dominant-name-v1) ---------------------------------
    val qs = step("resolve benchmark names") {
      questions(spark, benchmark, windows.head.name, windows.map(_.name).toSet)
    }
    val res = step("resolve benchmark names") {
      resolve(spark, researchers, authored, qs.flatMap(q => Seq(q.nameA, q.nameB)), dom)
    }
    val seeded = CommunityPathJob.parsePairs(seededText, windows.map(_.name).toSet)
    val benchPairs = qs.flatMap { q =>
      val (a, b) = (res(q.nameA), res(q.nameB))
      Option.when(pairStatus(a, b) == Resolved)((q.window, a.id.get, b.id.get))
    }
    val pairList = (seeded ++ benchPairs).distinct
    val pairs = CommunityPathJob.pairFrame(spark, pairList, w, graphPolicy)
    val pairIds = pairs
      .select("window", "source", "target", "pairId")
      .collect() // bounded: the selected pairs
      .map(r => (r.getString(0), r.getString(1), r.getString(2)) -> r.getString(3))
      .toMap
    val resolutionRows = qs.map { q =>
      val (a, b) = (res(q.nameA), res(q.nameB))
      val status = pairStatus(a, b)
      Row(
        q.questionId,
        q.window,
        q.nameA,
        q.nameB,
        a.status,
        b.status,
        a.id.orNull,
        b.id.orNull,
        a.candidates,
        b.candidates,
        a.topShare.map(Double.box).orNull,
        b.topShare.map(Double.box).orNull,
        a.topWorks,
        b.topWorks,
        a.totalWorks,
        b.totalWorks,
        a.sample,
        b.sample,
        ResolutionRule,
        status,
        Option.when(status == Resolved)(pairIds((q.window, a.id.get, b.id.get))).orNull
      )
    }
    val resolutions = write(
      "paths/benchmark-pairs",
      spark
        .createDataFrame(
          java.util.Arrays.asList(resolutionRows*),
          StructType.fromDDL(
            "questionId STRING, window STRING, researcherAName STRING, researcherBName STRING, " +
              "researcherAStatus STRING, researcherBStatus STRING, researcherAId STRING, " +
              "researcherBId STRING, researcherACandidates BIGINT, researcherBCandidates BIGINT, " +
              "researcherATopShare DOUBLE, researcherBTopShare DOUBLE, researcherATopWorks BIGINT, " +
              "researcherBTopWorks BIGINT, researcherATotalWorks BIGINT, " +
              "researcherBTotalWorks BIGINT, " +
              "researcherASample ARRAY<STRING>, researcherBSample ARRAY<STRING>, " +
              "resolutionRule STRING, status STRING, pairId STRING"
          )
        )
        .withColumn("provenance", ctx.provenance(col("questionId"), ResolutionRule)),
      Seq("s3"),
      1
    )
    val pairsFile = new Path(jr.runDir, "paths/selected-pairs.csv")
    JobRun.writeText(
      jr.ofs,
      pairsFile,
      (CommunityPathJob.PairsHeader +: pairList.map((a, b, c) => s"$a,$b,$c"))
        .mkString("", "\n", "\n")
    )

    // ---- Job 5 (paths) and job 10 (path summaries) -------------------------------------------
    val membership =
      metrics.select(col("window"), col("researcherId").as("node"), col("componentId"))
    val (pathRows, bfsReached) = step("bounded paths") {
      ShortestPaths.paths(pairs, membership, edges, s.pathMaxDepth)
    }
    val pathEvidence = write(
      "paths/path-evidence",
      CommunityPathJob.evidenceRows(pathRows, w, ctx, graphPolicy),
      Seq("neptune", "dynamodb", "s3"),
      1,
      None,
      Seq("window")
    )
    val summaries = write(
      "summaries/path-summaries",
      step("summaries") {
        Summaries
          .paths(researchers, works, pathEvidence)
          .withColumn("vectorIndex", lit(cfg.s3Vectors.indexes.getOrElse("paths", null)))
          .withColumn("provenance", ctx.provenance(col("summaryId"), Summaries.TemplateVersion))
      },
      Seq("s3vectors", "s3"),
      1,
      Some("subjectId"),
      Seq("documentType", "window")
    )

    // ---- Integrity ----------------------------------------------------------------------------
    val seededIds = spark.createDataFrame(
      java.util.Arrays.asList(seeded.map(p => Row(pairIds(p)))*),
      StructType.fromDDL("pairId STRING")
    )
    val violations = write(
      "paths/benchmark-path-violations",
      Violations.stamped(
        ctx,
        CommunityPathJob
          .pathViolations(pathEvidence)
          .union(
            Violations(
              "SUMMARY_MISSING",
              pathEvidence
                .join(summaries.select(col("subjectId").as("pairId")), Seq("pairId"), "left_anti"),
              col("pairId"),
              col("window"),
              lit(Summaries.PathDoc)
            )
          )
          .union(
            // Job 5 is deterministic: a seeded pair must give the input run's path again.
            Violations(
              "SEEDED_PATH_CHANGED",
              pathEvidence
                .join(broadcast(seededIds), "pairId")
                .join(
                  before.select(
                    col("pairId"),
                    col("status").as("s0"),
                    col("pathLength").as("l0"),
                    col("researcherIds").as("r0")
                  ),
                  Seq("pairId"),
                  "left"
                )
                .where(
                  !col("status").eqNullSafe(col("s0")) || !col("pathLength")
                    .eqNullSafe(col("l0")) ||
                    !col("researcherIds").eqNullSafe(col("r0"))
                ),
              col("pairId"),
              col("window"),
              concat_ws("->", col("s0"), col("status"))
            )
          )
      ),
      Seq("s3"),
      1
    )

    val counts = step("report") {
      ujson.Obj(
        "seededPairs" -> seeded.size,
        "benchmarkQuestionsWithTwoResearchers" -> qs.size,
        "benchmarkPairsResolved" -> benchPairs.distinct.size,
        "selectedPairs" -> pairList.size,
        "benchmarkResolutions" -> JobRun.rows(
          resolutions
            .select(
              "questionId",
              "researcherAName",
              "researcherAStatus",
              "researcherACandidates",
              "researcherATopShare",
              "researcherAId",
              "researcherBName",
              "researcherBStatus",
              "researcherBCandidates",
              "researcherBTopShare",
              "researcherBId",
              "status",
              "pairId"
            )
            .orderBy("questionId")
        ),
        "benchmarkPathResults" -> JobRun.rows(
          resolutions
            .where(col("pairId").isNotNull)
            .select("questionId", "pairId")
            .join(pathEvidence.select("pairId", "status", "pathLength", "researcherIds"), "pairId")
            .orderBy("questionId")
        ),
        "pathStatusCounts" -> JobRun.rows(
          pathEvidence.groupBy("window", "status").count().orderBy("window", "status")
        ),
        "pathLengthHistogram" -> JobRun.rows(
          pathEvidence
            .where(col("status") === ShortestPaths.Found)
            .groupBy("window", "pathLength")
            .count()
            .orderBy("window", "pathLength")
        ),
        "pathBfsNodesReachedPerRound" -> ujson.Arr.from(bfsReached.map(n => ujson.Num(n.toDouble))),
        "pathSummaries" -> summaries.count().toDouble,
        "splitIdentities" -> JobRun.rows(splitIdentities(dominance(researchers, authored, dom))),
        "integrityViolationsByCheck" -> JobRun.rows(
          violations.groupBy("check").count().orderBy("check")
        )
      )
    }
    val manifest = jr.finish(head, "selectedPairsFile" -> pairsFile.toString, "counts" -> counts)
    Seq(metrics, edges, researchers, authored, works, before).foreach(_.unpersist())
    RunResult(jr.runId, jr.attempt, jr.runDir.toString, manifest)
