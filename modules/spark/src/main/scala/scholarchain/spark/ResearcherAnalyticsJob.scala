package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Spark jobs 3, 4, and 8 (spec Phase 6): researcher statistics, connected components, k-core.
  *
  * Input: one complete CanonicalGraphJob run directory (its `_manifest.json` must exist),
  * read-only: `canonical/researchers`, `canonical/authored`, `canonical/works`,
  * `graph/coauthor-edges`. Output and run identity: JobRun; the fingerprints are the input run, its
  * size, the windows, and the algorithm parameters. The graph of a window: nodes = researchers with
  * a DBLP publication in the window (ResearcherStats), edges = that window's coauthor edges.
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.ResearcherAnalyticsJob --input
  * <canonical-graph run dir> --code-version <git sha> [--shuffle-partitions 40] [--attempt n]"`
  */
object ResearcherAnalyticsJob:
  val Stage = "spark-researcher-analytics"

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

  import JobRun.rows

  def run(spark: SparkSession, s: SparkSettings, cfg: ScholarChainConfig): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    val inPath = new Path(s.input)
    val ifs = inPath.getFileSystem(hconf)
    val im = ujson.read(JobRun.readText(ifs, new Path(inPath, "_manifest.json")))
    require(im("stage").str == CanonicalGraphJob.Stage, s"${s.input} is not a canonical-graph run")
    val inRunId = im("runId").str
    val windows = cfg.graph.windows
    val inWindows = im("settings")("windows").arr
      .map(w => (w("name").str, w("startYear").num.toInt, w("endYear").num.toInt))
    require(
      inWindows.toSeq == windows.map(w => (w.name, w.startYear, w.endYear)),
      s"configured windows differ from the input run's windows $inWindows"
    )
    val graphPolicy = im("settings")("graphPolicy").str
    val maxRoundsPerLevel = cfg.analytics.kcoreMaxIterations
    val fingerprints = Seq(
      s"canonical-graph-run=$inRunId",
      s"input:canonical-graph=${s.input}:${ifs.getContentSummary(inPath).getLength}",
      s"config:graph-policy=$graphPolicy",
      s"config:windows=${windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")}",
      s"config:cc-max-iterations=${s.ccMaxIterations}",
      s"config:kcore-max-iterations=$maxRoundsPerLevel"
    )
    val jr = new JobRun(spark, Stage, s, cfg, im("snapshot").str, fingerprints)
    val head = jr.head(
      "input" -> ujson.Obj("path" -> s.input, "canonicalGraphRunId" -> inRunId),
      "lineage" -> im("lineage"),
      "settings" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "shufflePartitions" -> s.shufflePartitions,
        "adaptiveExecution" -> spark.conf.get("spark.sql.adaptive.enabled"),
        "checkpointDir" -> jr.checkpointDir.toString,
        "checkpointEvery" -> "round",
        "graphPolicy" -> graphPolicy,
        "windows" -> ujson.Arr.from(
          windows.map(w =>
            ujson.Obj("name" -> w.name, "startYear" -> w.startYear, "endYear" -> w.endYear)
          )
        ),
        "componentsAlgorithm" -> "min-label-propagation",
        "ccMaxIterations" -> s.ccMaxIterations,
        "kcoreAlgorithm" -> "iterative-peeling",
        "kcoreMaxRoundsPerLevel" -> maxRoundsPerLevel,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "availableProcessors" -> Runtime.getRuntime.availableProcessors
      )
    )
    jr.start(head)
    import jr.{step, write}
    val ctx = Ctx(
      im("snapshot").str,
      jr.runId,
      im("settings")("canonicalPolicy").str,
      graphPolicy,
      s.ccMaxIterations
    )

    def input(rel: String, cols: String*): DataFrame = step("read inputs") {
      val p = new Path(inPath, rel)
      val df = spark.read.parquet(p.toString).select(cols.map(col)*).persist()
      jr.recordInput(p.toString, ifs.exists(p), df.count())
      df
    }
    val researchers = input("canonical/researchers", "researcherId", "displayName")
    val authored = input("canonical/authored", "researcherId", "workId", "year", "sourceDatasets")
    val works = input("canonical/works", "workId", "memberWorkIds")
    val edges = input(
      "graph/coauthor-edges",
      "window",
      "researcherA",
      "researcherB",
      "jointPublicationCount"
    )
    val w = CoauthorGraph.windows(spark, windows)
    val p = s.shufflePartitions
    val big = math.max(1, p / 5)

    // ---- Job 3 ---------------------------------------------------------------------------------
    val stats = step("researcher statistics") {
      ResearcherStats(authored, edges, w).persist(StorageLevel.MEMORY_AND_DISK)
    }
    val nodes = stats.select(col("window"), col("researcherId").as("node"))
    val g = edges.select(col("window"), col("researcherA").as("a"), col("researcherB").as("b"))

    // ---- Job 4 ---------------------------------------------------------------------------------
    val (labels, ccChanged) = step("connected components") {
      val (l, c) = MinLabelComponents(nodes, g, s.ccMaxIterations)
      (l.persist(), c)
    }
    val components = step("connected components") {
      labels
        .groupBy("window", "root")
        .agg(count(lit(1)).as("componentSize"))
        .join(broadcast(w), "window")
        .select(
          col("window"),
          col("windowStartYear"),
          col("windowEndYear"),
          componentId(
            col("window"),
            col("windowStartYear"),
            col("windowEndYear"),
            col("root"),
            graphPolicy
          )
            .as("componentId"),
          col("root").as("componentRoot"),
          col("componentSize")
        )
        .withColumn(
          "provenance",
          ctx.provenance(concat_ws("|", col("componentId"), col("window")), graphPolicy)
        )
    }
    val componentTable =
      write("analytics/components", components, Seq("s3", "dynamodb"), 1, None, Seq("window"))

    // ---- Job 8 ---------------------------------------------------------------------------------
    val (core, kLog) = step("k-core") {
      val (c, l) = KCore(nodes, g, maxRoundsPerLevel)
      (c.persist(), l)
    }

    // ---- Researcher metrics table --------------------------------------------------------------
    val membership = labels
      .join(
        componentTable.select(
          col("window"),
          col("componentRoot").as("root"),
          col("componentId"),
          col("componentSize")
        ),
        Seq("window", "root")
      )
      .select(
        col("window"),
        col("node").as("researcherId"),
        col("componentId"),
        col("componentSize")
      )
    val metricsDf = stats
      .join(membership, Seq("window", "researcherId"), "left")
      .join(
        core.select(col("window"), col("node").as("researcherId"), col("coreness"), col("degree")),
        Seq("window", "researcherId"),
        "left"
      )
      .select(
        col("researcherId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("publicationCount"),
        col("distinctCoauthorCount"),
        col("totalCoauthorOccurrences"),
        col("averageCoauthorsPerPaper"),
        col("maximumCoauthorsOnOnePaper"),
        col("maximumTeamWorkIds"),
        col("weightedDegree"),
        col("componentId"),
        col("componentSize"),
        col("coreness"),
        col("degree").as("graphDegree"),
        ctx
          .provenance(concat_ws("|", col("researcherId"), col("window")), graphPolicy)
          .as("provenance")
      )
    val metrics = write(
      "analytics/researcher-metrics",
      metricsDf,
      Seq("neptune", "dynamodb", "s3"),
      big,
      Some("researcherId"),
      Seq("window")
    )

    // ---- Integrity and invariants --------------------------------------------------------------
    val endpoints = ResearcherStats.degrees(edges).select("window", "researcherId")
    def invariant(check: String, broken: Column) = Violations(
      check,
      metrics.where(broken),
      col("researcherId"),
      col("window"),
      to_json(
        struct(
          col("publicationCount"),
          col("distinctCoauthorCount"),
          col("totalCoauthorOccurrences"),
          col("maximumCoauthorsOnOnePaper"),
          col("weightedDegree"),
          col("graphDegree"),
          col("coreness"),
          col("componentId")
        )
      )
    )
    val violations = write(
      "analytics/integrity-violations",
      Violations.stamped(
        ctx,
        Violations(
          "METRIC_RESEARCHER_MISSING",
          metrics.join(researchers, Seq("researcherId"), "left_anti"),
          col("researcherId"),
          col("window"),
          lit("metric row names a researcher that is not in researchers")
        ).union(
          Violations(
            "EDGE_ENDPOINT_WITHOUT_PUBLICATION",
            endpoints.join(metrics, Seq("window", "researcherId"), "left_anti"),
            col("researcherId"),
            col("window"),
            lit("coauthor edge endpoint has no DBLP publication in the window")
          )
        ).union(
          invariant(
            "WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES",
            col("weightedDegree") =!= col("totalCoauthorOccurrences")
          )
        ).union(
          invariant("CORENESS_BELOW_MAX_TEAM", col("coreness") < col("maximumCoauthorsOnOnePaper"))
        ).union(
          invariant(
            "METRIC_INVARIANT",
            col("distinctCoauthorCount") > col("totalCoauthorOccurrences") ||
              col("maximumCoauthorsOnOnePaper") > col("totalCoauthorOccurrences") ||
              col("averageCoauthorsPerPaper") > col("maximumCoauthorsOnOnePaper") ||
              col("graphDegree") =!= col("distinctCoauthorCount") ||
              col("coreness") > col("graphDegree") ||
              col("componentId").isNull || col("coreness").isNull ||
              size(col("maximumTeamWorkIds")) === 0
          )
        )
      ),
      Seq("s3"),
      1
    )

    // ---- Counts (bounded aggregates over the written tables) -----------------------------------
    val counts = step("report") {
      val distributions = JobRun.distributions(
        metrics,
        Seq(
          "publicationCount",
          "distinctCoauthorCount",
          "totalCoauthorOccurrences",
          "averageCoauthorsPerPaper",
          "maximumCoauthorsOnOnePaper",
          "weightedDegree",
          "coreness",
          "componentSize"
        )
      )
      val pubs = authored
        .where(array_contains(col("sourceDatasets"), "DBLP"))
        .select("workId", "year")
        .distinct()
        .join(broadcast(w), col("year").between(col("windowStartYear"), col("windowEndYear")))
        .groupBy("window")
        .agg(count(lit(1)).as("publications"))
      val comp = componentTable
        .groupBy("window")
        .agg(
          count(lit(1)).as("components"),
          max("componentSize").as("largestComponentSize"),
          sum(when(col("componentSize") === 1, 1).otherwise(0)).as("singletonResearchers"),
          sum("componentSize").as("researchersInComponents")
        )
      val edgeCounts = edges.groupBy("window").agg(count(lit(1)).as("coauthorEdges"))
      val perWindow = w
        .join(pubs, Seq("window"), "left")
        .join(metrics.groupBy("window").agg(count(lit(1)).as("researchers")), Seq("window"), "left")
        .join(edgeCounts, Seq("window"), "left")
        .join(comp, Seq("window"), "left")
        .withColumn("largestComponentFraction", col("largestComponentSize") / col("researchers"))
        .orderBy("windowStartYear", "windowEndYear")
      ujson.Obj(
        "perWindow" -> rows(perWindow),
        "metricDistributions" -> distributions,
        "componentSizeHistogram" -> rows(
          componentTable
            .groupBy(col("window"), JobRun.sizeBucket(col("componentSize")).as("size"))
            .agg(count(lit(1)).as("components"), min("componentSize").as("lo"))
            .orderBy("window", "lo")
            .drop("lo")
        ),
        "largestComponents" -> rows(
          componentTable
            .withColumn(
              "rank",
              row_number().over(
                Window
                  .partitionBy("window")
                  .orderBy(desc("componentSize"), col("componentId"))
              )
            )
            .where(col("rank") <= 3)
            .select("window", "rank", "componentId", "componentRoot", "componentSize")
            .orderBy("window", "rank")
        ),
        "corenessHistogram" -> rows(
          metrics.groupBy("window", "coreness").count().orderBy("window", "coreness")
        ),
        "highestCorenessResearchers" -> rows(
          metrics
            .withColumn(
              "rank",
              row_number().over(
                Window
                  .partitionBy("window")
                  .orderBy(desc("coreness"), desc("distinctCoauthorCount"), col("researcherId"))
              )
            )
            .where(col("rank") <= 3)
            .join(researchers, "researcherId")
            .select(
              "window",
              "rank",
              "researcherId",
              "displayName",
              "coreness",
              "distinctCoauthorCount"
            )
            .orderBy("window", "rank")
        ),
        "componentsIterations" -> ccChanged.size,
        "componentsLabelsChangedPerRound" -> ujson.Arr.from(
          ccChanged.map(n => ujson.Num(n.toDouble))
        ),
        "kcoreRounds" -> kLog.size,
        "kcoreLevels" -> kLog.map(_._1).distinct.size,
        "kcoreRoundLog" -> ujson.Arr.from(kLog.map((k, n) => ujson.Arr(k, n.toDouble))),
        "integrityViolationsByCheck" -> rows(violations.groupBy("check").count().orderBy("check")),
        // W_r != T_r and coreness < M_r are expected only for researchers on a canonical work with
        // several DBLP members: team(w) counts across them, job 2 pairs only within one of them.
        "mergedDblpWorkChecks" -> {
          val merged = works
            .where(size(filter(col("memberWorkIds"), x => x.startsWith("dblp:"))) > 1)
            .select("workId")
          val onMerged = authored
            .where(array_contains(col("sourceDatasets"), "DBLP"))
            .join(merged, "workId")
            .join(broadcast(w), col("year").between(col("windowStartYear"), col("windowEndYear")))
            .select(col("window"), col("researcherId").as("subjectId"), lit(true).as("onMerged"))
            .distinct()
          ujson.Obj(
            "mergedDblpWorks" -> merged.count().toDouble,
            "byCheck" -> rows(
              violations
                .where(
                  col("check")
                    .isin("WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES", "CORENESS_BELOW_MAX_TEAM")
                )
                .select(col("check"), col("relatedId").as("window"), col("subjectId"))
                .join(onMerged, Seq("window", "subjectId"), "left")
                .groupBy("check")
                .agg(count(lit(1)).as("violations"), count(col("onMerged")).as("onMergedDblpWork"))
                .orderBy("check")
            )
          )
        }
      )
    }

    val manifest = jr.finish(head, "counts" -> counts)
    Seq(researchers, authored, works, edges, stats, labels, core).foreach(_.unpersist())
    RunResult(jr.runId, jr.attempt, jr.runDir.toString, manifest)

  /** "comp-" + H("component", graph policy, window, start, end, root). */
  def componentId(window: Column, start: Column, end: Column, root: Column, policy: String) =
    concat(
      lit("comp-"),
      Ids.hash(
        lit("component"),
        lit(policy),
        window,
        start.cast("string"),
        end.cast("string"),
        root
      )
    )
