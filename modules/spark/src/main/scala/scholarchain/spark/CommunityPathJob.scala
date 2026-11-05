package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}
import scholarchain.core.{IdFormat, StableIds}
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Spark jobs 5, 6, and 7 (spec Phase 6): bounded path evidence and the sampled path-length
  * estimate, label-propagation communities, and the bridge measures.
  *
  * Input: one complete ResearcherAnalyticsJob run directory (`--input`), read-only:
  * `analytics/researcher-metrics` (the nodes of each window and their components) and
  * `analytics/components`; its manifest names the CanonicalGraphJob run, from which
  * `graph/coauthor-edges` and `canonical/researchers` are read. Pairs: `--pairs <csv file>` (header
  * `window,sourceResearcherId,targetResearcherId`), or without it a seeded sample of
  * `path-sample-pairs` pairs from the largest component of the first configured window. Either way
  * the pairs are copied to `<run dir>/paths/selected-pairs.csv`.
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.CommunityPathJob --input
  * <researcher-analytics run dir> --code-version <git sha> [--pairs <csv>] [--attempt n]"`
  */
object CommunityPathJob:
  val Stage = "spark-community-path"
  val PairsHeader = "window,sourceResearcherId,targetResearcherId"

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

  /** Parses a pairs CSV (header plus `window,source,target` lines). Validates the window names and
    * researcher IDs, and drops duplicate lines. Returns (window, source, target) in file order.
    */
  def parsePairs(text: String, windows: Set[String]): Seq[(String, String, String)] =
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    require(lines.headOption.contains(PairsHeader), s"pairs file must start with $PairsHeader")
    lines.tail.zipWithIndex.map { (l, i) =>
      val f = l.split(",", -1).map(_.trim)
      require(f.length == 3, s"pairs line ${i + 2}: expected 3 fields: $l")
      require(windows(f(0)), s"pairs line ${i + 2}: unknown window ${f(0)}")
      require(
        IdFormat.isResearcherId(f(1)) && IdFormat.isResearcherId(f(2)),
        s"pairs line ${i + 2}: not a researcher ID: $l"
      )
      (f(0), f(1), f(2))
    }.distinct

  def run(spark: SparkSession, s: SparkSettings, cfg: ScholarChainConfig): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    val inPath = new Path(s.input)
    val ifs = inPath.getFileSystem(hconf)
    val am = ujson.read(JobRun.readText(ifs, new Path(inPath, "_manifest.json")))
    require(
      am("stage").str == ResearcherAnalyticsJob.Stage,
      s"${s.input} is not a researcher-analytics run"
    )
    val graphPath = new Path(am("input")("path").str)
    val gfs = graphPath.getFileSystem(hconf)
    val gm = ujson.read(JobRun.readText(gfs, new Path(graphPath, "_manifest.json")))
    require(gm("runId").str == am("input")("canonicalGraphRunId").str, "canonical-graph run moved")
    val windows = cfg.graph.windows
    val windowNames = windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")
    require(
      am("fingerprints").arr.exists(_.str == s"config:windows=$windowNames"),
      s"configured windows differ from the input run's windows"
    )
    val graphPolicy = am("settings")("graphPolicy").str
    val pairsText = s.pairs.map(p => JobRun.readText(new Path(p).getFileSystem(hconf), new Path(p)))
    val fingerprints = Seq(
      s"researcher-analytics-run=${am("runId").str}",
      s"canonical-graph-run=${gm("runId").str}",
      s"input:researcher-analytics=${s.input}:${ifs.getContentSummary(inPath).getLength}",
      s"input:canonical-graph=$graphPath:${gfs.getContentSummary(graphPath).getLength}",
      s"config:graph-policy=$graphPolicy",
      s"config:windows=$windowNames",
      s"config:community-max-iterations=${s.communityMaxIterations}",
      s"config:path-max-depth=${s.pathMaxDepth}",
      s"config:path-sample-sources=${s.pathSampleSources}",
      s"config:sample-seed=${s.sampleSeed}",
      s"config:cc-max-iterations=${s.ccMaxIterations}",
      pairsText.fold(s"pairs=sample:${s.pathSamplePairs}")(t =>
        s"pairs=file:${StableIds.textHash(t)}"
      )
    )
    val jr = new JobRun(spark, Stage, s, cfg, am("snapshot").str, fingerprints)
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "researcherAnalyticsRunId" -> am("runId").str,
        "canonicalGraphPath" -> graphPath.toString,
        "canonicalGraphRunId" -> gm("runId").str,
        "pairsFile" -> s.pairs.fold[ujson.Value](ujson.Null)(ujson.Str(_))
      ),
      "lineage" -> am("lineage"),
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
        "communityAlgorithm" -> "label-propagation (synchronous, self vote, smallest-label tie-break)",
        "communityMaxIterations" -> s.communityMaxIterations,
        "bridgeMeasures" -> JobRun.strs(
          Seq("crossCommunityDegree", "communitiesConnected", "participationCoefficient")
        ),
        "pathAlgorithm" -> "bounded multi-source BFS, smallest-parent tie-break",
        "pathMaxDepth" -> s.pathMaxDepth,
        "pathPairs" -> pairsText.fold[ujson.Value](
          ujson.Obj(
            "source" -> "seeded sample",
            "pairs" -> s.pathSamplePairs,
            "window" -> windows.head.name,
            "from" -> "the window's largest component",
            "method" -> "rank members by H(seed, 'path-pair', window, id); pair ranks 1-2, 3-4, ..."
          )
        )(_ => ujson.Obj("source" -> "file", "file" -> s.pairs.get)),
        "pathLengthEstimate" -> ujson.Obj(
          "sourcesPerWindow" -> s.pathSampleSources,
          "from" -> "each window's largest component",
          "method" -> "rank members by H(seed, 'bfs-source', window, id); exact BFS from each",
          "maxRounds" -> s.ccMaxIterations
        ),
        "sampleSeed" -> s.sampleSeed.toDouble,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "availableProcessors" -> Runtime.getRuntime.availableProcessors
      )
    )
    jr.start(head)
    import jr.{step, write}
    import JobRun.rows
    val ctx =
      Ctx(am("snapshot").str, jr.runId, gm("settings")("canonicalPolicy").str, graphPolicy, 0)

    def input(dir: Path, rel: String, cols: String*): DataFrame = step("read inputs") {
      val p = new Path(dir, rel)
      val df = spark.read.parquet(p.toString).select(cols.map(col)*).persist()
      jr.recordInput(p.toString, p.getFileSystem(hconf).exists(p), df.count())
      df
    }
    val metrics = input(
      inPath,
      "analytics/researcher-metrics",
      "window",
      "researcherId",
      "componentId",
      "graphDegree"
    )
    val components = input(inPath, "analytics/components", "window", "componentId", "componentSize")
    val edges = input(
      graphPath,
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
    val researchers = input(graphPath, "canonical/researchers", "researcherId", "displayName")
    val w = CoauthorGraph.windows(spark, windows)
    val big = math.max(1, s.shufflePartitions / 5)
    val nodes = metrics.select(col("window"), col("researcherId").as("node"))
    val g = edges.select(col("window"), col("researcherA").as("a"), col("researcherB").as("b"))
    val sym = MinLabelComponents.symmetric(g).checkpoint()
    def withWindow(df: DataFrame) = df.join(broadcast(w), "window")
    def rankBy(seed: String) = Window
      .partitionBy("window")
      .orderBy(Ids.hash(lit(s.sampleSeed.toString), lit(seed), col("window"), col("researcherId")))

    // The largest component of each window (ties: smallest componentId).
    val largest = components
      .withColumn(
        "r",
        row_number().over(
          Window.partitionBy("window").orderBy(desc("componentSize"), col("componentId"))
        )
      )
      .where(col("r") === 1)
      .drop("r")
    val inLargest = metrics.join(broadcast(largest), Seq("window", "componentId"))

    // ---- Job 5: selected pairs -----------------------------------------------------------------
    val pairsCsv = step("select pairs") {
      pairsText.getOrElse {
        val ids = inLargest
          .where(col("window") === windows.head.name)
          .withColumn("rank", row_number().over(rankBy("path-pair")))
          .where(col("rank") <= 2 * s.pathSamplePairs)
          .orderBy("rank")
          .select("researcherId")
          .collect() // bounded: 2 x path-sample-pairs IDs
          .map(_.getString(0))
          .toSeq
        (PairsHeader +: ids
          .grouped(2)
          .collect { case Seq(a, b) =>
            s"${windows.head.name},$a,$b"
          }
          .toSeq).mkString("", "\n", "\n")
      }
    }
    val pairsFile = new Path(jr.runDir, "paths/selected-pairs.csv")
    JobRun.writeText(jr.ofs, pairsFile, pairsCsv)
    val pairList = parsePairs(pairsCsv, windows.map(_.name).toSet)
    val pairs = spark
      .createDataFrame(
        java.util.Arrays.asList(pairList.map((a, b, c) => Row(a, b, c))*),
        StructType.fromDDL("window STRING, source STRING, target STRING")
      )
      .join(broadcast(w), "window")
      .withColumn(
        "pairId",
        concat(
          lit("pair-"),
          Ids.hash(
            lit("path-pair"),
            lit(graphPolicy),
            col("window"),
            col("windowStartYear").cast("string"),
            col("windowEndYear").cast("string"),
            col("source"),
            col("target")
          )
        )
      )
    val membership =
      metrics.select(col("window"), col("researcherId").as("node"), col("componentId"))
    val (pathRows, bfsReached) = step("bounded paths") {
      val (p, r) = ShortestPaths.paths(pairs, membership, edges, s.pathMaxDepth)
      (p, r)
    }
    val pathEvidence = write(
      "paths/path-evidence",
      withWindow(pathRows).select(
        col("pairId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("sourceResearcherId"),
        col("targetResearcherId"),
        col("status"),
        col("pathLength"),
        col("maxDepth"),
        col("componentId"),
        col("researcherIds"),
        col("hops"),
        ctx.provenance(col("pairId"), graphPolicy).as("provenance")
      ),
      Seq("neptune", "dynamodb", "s3"),
      1,
      None,
      Seq("window")
    )

    // ---- Job 5: sampled path-length estimate on each window's largest component ----------------
    val (distances, estimateReached) = step("path-length estimate") {
      val sources = inLargest
        .withColumn("rank", row_number().over(rankBy("bfs-source")))
        .where(col("rank") <= s.pathSampleSources)
        .select(col("window"), col("researcherId").as("source"))
      ShortestPaths.sampledDistances(sources, sym, s.ccMaxIterations)
    }
    val estimate = write(
      "paths/path-length-estimate",
      withWindow(ShortestPaths.estimate(distances))
        .join(
          largest.select(
            col("window"),
            col("componentId").as("largestComponentId"),
            col("componentSize").as("largestComponentSize")
          ),
          "window"
        )
        .select(
          col("window"),
          col("windowStartYear"),
          col("windowEndYear"),
          col("largestComponentId"),
          col("largestComponentSize"),
          lit(s.pathSampleSources).as("requestedSources"),
          size(col("sourceResearcherIds")).as("sampledSources"),
          lit(s.sampleSeed).as("sampleSeed"),
          col("sourceResearcherIds"),
          col("reachablePairs"),
          col("meanDistance"),
          col("meanDistanceStdError"),
          col("medianDistance"),
          col("effectiveDiameter90"),
          col("maxObservedDistance"),
          col("distanceHistogram"),
          ctx.provenance(col("largestComponentId"), graphPolicy).as("provenance")
        ),
      Seq("dynamodb", "s3"),
      1
    )

    // ---- Job 6: communities --------------------------------------------------------------------
    val (labels, lpaRounds) = step("label propagation") {
      val (l, r) = LabelPropagation(nodes, g, s.communityMaxIterations)
      (l.persist(), r)
    }
    def communityId(label: Column) = concat(
      lit("comm-"),
      Ids.hash(
        lit("community"),
        lit(graphPolicy),
        col("window"),
        col("windowStartYear").cast("string"),
        col("windowEndYear").cast("string"),
        label
      )
    )
    val edgeLabels = edges
      .join(
        labels.select(col("window"), col("node").as("researcherA"), col("label").as("labelA")),
        Seq("window", "researcherA")
      )
      .join(
        labels.select(col("window"), col("node").as("researcherB"), col("label").as("labelB")),
        Seq("window", "researcherB")
      )
    val endpointCounts = edgeLabels
      .select(col("window"), col("labelA").as("label"), (col("labelA") === col("labelB")).as("in"))
      .union(edgeLabels.select(col("window"), col("labelB"), col("labelA") === col("labelB")))
      .groupBy("window", "label")
      .agg(
        (sum(when(col("in"), 1L).otherwise(0L)) / 2).cast("long").as("internalEdges"),
        sum(when(col("in"), 0L).otherwise(1L)).as("crossCommunityEdges")
      )
    val communities = write(
      "analytics/communities",
      withWindow(
        labels
          .groupBy("window", "label")
          .agg(count(lit(1)).as("communitySize"))
          .join(endpointCounts, Seq("window", "label"), "left")
      ).select(
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        communityId(col("label")).as("communityId"),
        col("label").as("communityLabel"),
        col("communitySize"),
        coalesce(col("internalEdges"), lit(0L)).as("internalEdges"),
        coalesce(col("crossCommunityEdges"), lit(0L)).as("crossCommunityEdges"),
        ctx.provenance(communityId(col("label")), graphPolicy).as("provenance")
      ),
      Seq("neptune", "dynamodb", "s3"),
      1,
      None,
      Seq("window")
    )
    val crossEdges = write(
      "analytics/cross-community-edges",
      withWindow(edgeLabels.where(col("labelA") =!= col("labelB"))).select(
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("edgeId"),
        col("researcherA"),
        col("researcherB"),
        communityId(col("labelA")).as("communityA"),
        communityId(col("labelB")).as("communityB"),
        col("jointPublicationCount"),
        ctx.provenance(col("edgeId"), graphPolicy).as("provenance")
      ),
      Seq("neptune", "s3"),
      1,
      None,
      Seq("window")
    )

    // ---- Job 7: bridge measures, with membership and community size ---------------------------
    val researcherCommunities = write(
      "analytics/researcher-communities",
      withWindow(Bridges(labels, g))
        .join(
          communities.select(
            col("window"),
            col("communityLabel").as("label"),
            col("communityId"),
            col("communitySize")
          ),
          Seq("window", "label")
        )
        .select(
          col("window"),
          col("windowStartYear"),
          col("windowEndYear"),
          col("node").as("researcherId"),
          col("communityId"),
          col("communitySize"),
          col("degree").as("graphDegree"),
          col("crossCommunityDegree"),
          col("communitiesConnected"),
          col("participationCoefficient"),
          ctx
            .provenance(concat_ws("|", col("node"), col("window")), graphPolicy)
            .as("provenance")
        ),
      Seq("neptune", "dynamodb", "s3"),
      big,
      Some("researcherId"),
      Seq("window")
    )

    // ---- Integrity and invariants --------------------------------------------------------------
    val violations = write(
      "analytics/community-path-violations",
      Violations.stamped(
        ctx,
        Violations(
          "COMMUNITY_RESEARCHER_MISMATCH",
          researcherCommunities
            .select("window", "researcherId", "graphDegree")
            .join(
              metrics.select(col("window"), col("researcherId"), col("graphDegree").as("expected")),
              Seq("window", "researcherId"),
              "full"
            )
            .where(
              col("graphDegree").isNull || col("expected").isNull ||
                col("graphDegree") =!= col("expected")
            ),
          col("researcherId"),
          col("window"),
          lit("researcher missing on one side, or degree differs from researcher-metrics")
        ).union(
          Violations(
            "BRIDGE_INVARIANT",
            researcherCommunities.where(
              col("crossCommunityDegree") > col("graphDegree") ||
                col("communitiesConnected") > col("graphDegree") ||
                col("participationCoefficient") < 0 || col("participationCoefficient") >= 1 ||
                (col("graphDegree") > 0 && col("communitiesConnected") === 0)
            ),
            col("researcherId"),
            col("window"),
            to_json(
              struct(
                col("graphDegree"),
                col("crossCommunityDegree"),
                col("communitiesConnected"),
                col("participationCoefficient")
              )
            )
          )
        ).union(
          Violations(
            "PATH_HOP_EDGE_MISSING",
            pathEvidence
              .select(col("pairId"), col("window"), explode(col("hops")).as("h"))
              .where(col("h.edgeId").isNull),
            col("pairId"),
            col("window"),
            concat_ws("|", col("h.fromResearcherId"), col("h.toResearcherId"))
          )
        ).union(
          Violations(
            "PATH_LENGTH_MISMATCH",
            pathEvidence.where(
              col("status") === ShortestPaths.Found &&
                (size(col("hops")) =!= col("pathLength") ||
                  size(col("researcherIds")) =!= col("pathLength") + 1 ||
                  col("pathLength") > col("maxDepth"))
            ),
            col("pairId"),
            col("window"),
            col("pathLength")
          )
        )
      ),
      Seq("s3"),
      1
    )

    // ---- Counts (bounded aggregates over the written tables) -----------------------------------
    val counts = step("report") {
      def top(df: DataFrame, order: Seq[Column], cols: String*) = rows(
        df.withColumn("rank", row_number().over(Window.partitionBy("window").orderBy(order*)))
          .where(col("rank") <= 5)
          .join(researchers, "researcherId")
          .select(("window" +: "rank" +: cols).map(col)*)
          .orderBy("window", "rank")
      )
      val bridgeCols = Seq(
        "researcherId",
        "displayName",
        "communityId",
        "graphDegree",
        "crossCommunityDegree",
        "communitiesConnected",
        "participationCoefficient"
      )
      ujson.Obj(
        "pathStatusCounts" -> rows(
          pathEvidence.groupBy("window", "status").count().orderBy("window", "status")
        ),
        "pathLengthHistogram" -> rows(
          pathEvidence
            .where(col("status") === ShortestPaths.Found)
            .groupBy("window", "pathLength")
            .count()
            .orderBy("window", "pathLength")
        ),
        "pathBfsNodesReachedPerRound" -> ujson.Arr.from(bfsReached.map(n => ujson.Num(n.toDouble))),
        "pathLengthEstimate" -> rows(estimate.drop("provenance", "sourceResearcherIds")),
        "estimateBfsNodesReachedPerRound" -> ujson.Arr.from(
          estimateReached.map(n => ujson.Num(n.toDouble))
        ),
        "communityIterations" -> lpaRounds.size,
        "communityConverged" -> ujson.Obj.from(
          windows.map(x =>
            x.name -> ujson.Bool(lpaRounds.lastOption.forall(_.getOrElse(x.name, 0L) == 0L))
          )
        ),
        "communityLabelsChangedPerRound" -> ujson.Obj.from(
          windows.map(x =>
            x.name -> ujson.Arr.from(
              lpaRounds.map(r => ujson.Num(r.getOrElse(x.name, 0L).toDouble))
            )
          )
        ),
        "communitiesPerWindow" -> rows(
          communities
            .groupBy("window")
            .agg(
              count(lit(1)).as("communities"),
              sum("communitySize").as("researchers"),
              max("communitySize").as("largestCommunitySize"),
              sum(when(col("communitySize") === 1, 1).otherwise(0)).as("singletonCommunities"),
              sum("internalEdges").as("internalEdges"),
              (sum("crossCommunityEdges") / 2).cast("long").as("crossCommunityEdges")
            )
            .withColumn(
              "largestCommunityFraction",
              col("largestCommunitySize") / col("researchers")
            )
            .orderBy("window")
        ),
        "communitySizeHistogram" -> rows(
          communities
            .groupBy(col("window"), JobRun.sizeBucket(col("communitySize")).as("size"))
            .agg(count(lit(1)).as("communities"), min("communitySize").as("lo"))
            .orderBy("window", "lo")
            .drop("lo")
        ),
        "largestCommunities" -> rows(
          communities
            .withColumn(
              "rank",
              row_number().over(
                Window.partitionBy("window").orderBy(desc("communitySize"), col("communityId"))
              )
            )
            .where(col("rank") <= 5)
            .select(
              "window",
              "rank",
              "communityId",
              "communityLabel",
              "communitySize",
              "internalEdges",
              "crossCommunityEdges"
            )
            .orderBy("window", "rank")
        ),
        "crossCommunityEdgeRows" -> crossEdges.count().toDouble,
        "bridgeDistributions" -> JobRun.distributions(
          researcherCommunities,
          Seq(
            "crossCommunityDegree",
            "communitiesConnected",
            "participationCoefficient",
            "communitySize"
          )
        ),
        "researchersWithCrossCommunityEdge" -> rows(
          researcherCommunities
            .groupBy("window")
            .agg(
              count(lit(1)).as("researchers"),
              sum(when(col("crossCommunityDegree") > 0, 1).otherwise(0))
                .as("withCrossCommunityEdge")
            )
            .orderBy("window")
        ),
        "topParticipationCoefficient" -> top(
          researcherCommunities,
          Seq(desc("participationCoefficient"), desc("crossCommunityDegree"), col("researcherId")),
          bridgeCols*
        ),
        "topCrossCommunityDegree" -> top(
          researcherCommunities,
          Seq(desc("crossCommunityDegree"), desc("participationCoefficient"), col("researcherId")),
          bridgeCols*
        ),
        "integrityViolationsByCheck" -> rows(violations.groupBy("check").count().orderBy("check"))
      )
    }

    val manifest = jr.finish(
      head,
      "selectedPairsFile" -> pairsFile.toString,
      "counts" -> counts
    )
    Seq(metrics, components, edges, researchers, labels).foreach(_.unpersist())
    RunResult(jr.runId, jr.attempt, jr.runDir.toString, manifest)
