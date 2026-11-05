package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Spark jobs 9, 10, and 11 (spec Phase 6): temporal comparison of the windows, semantic-context
  * summaries, and the Parquet export with its load manifest.
  *
  * Input: one complete CommunityPathJob run directory (`--input`), read-only. Its manifest names
  * the ResearcherAnalyticsJob and CanonicalGraphJob runs, which are read too (run IDs must match).
  * Output and run identity: JobRun. The export does not copy tables: `export/load-manifest.json`
  * lists every Parquet table of the four runs (path, schema, row count re-read and checked, target
  * stores), so Phase 7 loaders read one file.
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.TemporalSummaryExportJob --input
  * <community-path run dir> --code-version <git sha> [--attempt n]"`
  */
object TemporalSummaryExportJob:
  val Stage = "spark-temporal-summary-export"
  val ExportSchema = "spark-export-manifest-v1"
  val Stores = Seq("neptune", "dynamodb", "s3vectors", "s3")

  /** Table name -> logical name of the spec's output layout. Other tables keep their own name. */
  val Logical: Map[String, String] = Map(
    "canonical/researchers" -> "nodes/type=researcher",
    "canonical/works" -> "nodes/type=work",
    "canonical/venues" -> "nodes/type=venue",
    "canonical/topics" -> "nodes/type=topic",
    "canonical/institutions" -> "nodes/type=institution",
    "canonical/authored" -> "edges/type=authored",
    "graph/coauthor-edges" -> "edges/type=coauthored_with",
    "canonical/work-topics" -> "edges/type=has_topic",
    "canonical/researcher-institutions" -> "edges/type=affiliated_with",
    "analytics/cross-community-edges" -> "edges/type=cross_community",
    "analytics/researcher-metrics" -> "metrics/type=researcher_collaboration+kcore",
    "analytics/components" -> "metrics/type=components",
    "analytics/communities" -> "metrics/type=communities",
    "analytics/researcher-communities" -> "metrics/type=bridges",
    "paths/path-evidence" -> "path-evidence",
    "summaries/semantic-summaries" -> "semantic-summaries"
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

  import JobRun.rows

  def run(spark: SparkSession, s: SparkSettings, cfg: ScholarChainConfig): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    def manifestOf(p: Path) =
      ujson.read(JobRun.readText(p.getFileSystem(hconf), new Path(p, "_manifest.json")))
    def sizeOf(p: Path) = p.getFileSystem(hconf).getContentSummary(p).getLength
    val cpPath = new Path(s.input)
    val cp = manifestOf(cpPath)
    require(cp("stage").str == CommunityPathJob.Stage, s"${s.input} is not a community-path run")
    val raPath = new Path(cp("input")("path").str)
    val ra = manifestOf(raPath)
    val cgPath = new Path(cp("input")("canonicalGraphPath").str)
    val cg = manifestOf(cgPath)
    require(
      ra("runId").str == cp("input")("researcherAnalyticsRunId").str,
      "researcher-analytics run moved"
    )
    require(cg("runId").str == cp("input")("canonicalGraphRunId").str, "canonical-graph run moved")
    require(cg("runId").str == ra("input")("canonicalGraphRunId").str, "runs do not form one chain")
    val windows = cfg.graph.windows
    val windowNames = windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")
    require(
      cp("fingerprints").arr.exists(_.str == s"config:windows=$windowNames"),
      "configured windows differ from the input run's windows"
    )
    val comparisons = Temporal.comparisons(windows)
    require(comparisons.nonEmpty, "job 9 needs two configured windows that do not overlap")
    val graphPolicy = cp("settings")("graphPolicy").str
    val profileWindow = windows.head.name // ruling 7 calls it "all"; the config names it all-years
    val fingerprints = Seq(
      s"community-path-run=${cp("runId").str}",
      s"researcher-analytics-run=${ra("runId").str}",
      s"canonical-graph-run=${cg("runId").str}",
      s"input:community-path=$cpPath:${sizeOf(cpPath)}",
      s"input:researcher-analytics=$raPath:${sizeOf(raPath)}",
      s"input:canonical-graph=$cgPath:${sizeOf(cgPath)}",
      s"config:graph-policy=$graphPolicy",
      s"config:windows=$windowNames",
      s"config:summary-min-publications=${s.summaryMinPublications}",
      s"config:summary-min-community-size=${s.summaryMinCommunitySize}",
      s"config:summary-template=${Summaries.TemplateVersion}"
    )
    val jr = new JobRun(spark, Stage, s, cfg, cp("snapshot").str, fingerprints)
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "communityPathRunId" -> cp("runId").str,
        "researcherAnalyticsPath" -> raPath.toString,
        "researcherAnalyticsRunId" -> ra("runId").str,
        "canonicalGraphPath" -> cgPath.toString,
        "canonicalGraphRunId" -> cg("runId").str
      ),
      "lineage" -> cp("lineage"),
      "settings" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "shufflePartitions" -> s.shufflePartitions,
        "adaptiveExecution" -> spark.conf.get("spark.sql.adaptive.enabled"),
        "graphPolicy" -> graphPolicy,
        "windows" -> ujson.Arr.from(
          windows.map(w =>
            ujson.Obj("name" -> w.name, "startYear" -> w.startYear, "endYear" -> w.endYear)
          )
        ),
        "comparisons" -> ujson.Arr.from(
          comparisons.map((a, b) => ujson.Str(s"${a.name}->${b.name}"))
        ),
        "comparisonRule" -> "ordered pairs of non-overlapping windows, earlier first",
        "inclusionLimitation" -> InclusionLimitation,
        "summaryTemplateVersion" -> Summaries.TemplateVersion,
        "profileWindow" -> profileWindow,
        "summaryMinPublications" -> s.summaryMinPublications,
        "summaryMinCommunitySize" -> s.summaryMinCommunitySize,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "availableProcessors" -> Runtime.getRuntime.availableProcessors
      )
    )
    jr.start(head)
    import jr.{step, write}
    val ctx =
      Ctx(cp("snapshot").str, jr.runId, cg("settings")("canonicalPolicy").str, graphPolicy, 0)

    // An empty partitioned table has no files to read: use the schema its manifest recorded.
    val recorded =
      Seq(cgPath -> cg, raPath -> ra, cpPath -> cp).map((p, m) => p.toString -> m).toMap
    def input(dir: Path, rel: String, cols: String*): DataFrame = step("read inputs") {
      val p = new Path(dir, rel)
      val t = recorded(dir.toString)("tables").arr.find(_("name").str == rel)
      val raw =
        if t.exists(_("rowCount").num == 0) then
          spark.createDataFrame(java.util.List.of[Row](), StructType.fromDDL(t.get("schema").str))
        else spark.read.parquet(p.toString)
      // Disk only: execution memory goes to the many top-N sorts and aggregations of job 10.
      val df = raw.select(cols.map(col)*).persist(StorageLevel.DISK_ONLY)
      jr.recordInput(p.toString, p.getFileSystem(hconf).exists(p), df.count())
      df
    }
    val metrics = input(
      raPath,
      "analytics/researcher-metrics",
      "window",
      "researcherId",
      "publicationCount",
      "distinctCoauthorCount",
      "totalCoauthorOccurrences",
      "averageCoauthorsPerPaper",
      "maximumCoauthorsOnOnePaper",
      "weightedDegree",
      "coreness",
      "componentId",
      "componentSize"
    )
    val components = input(raPath, "analytics/components", "window", "componentId", "componentSize")
    val memberships = input(
      cpPath,
      "analytics/researcher-communities",
      "window",
      "researcherId",
      "communityId",
      "communitySize",
      "graphDegree",
      "crossCommunityDegree",
      "communitiesConnected",
      "participationCoefficient"
    )
    val communities = input(
      cpPath,
      "analytics/communities",
      "window",
      "windowStartYear",
      "windowEndYear",
      "communityId",
      "communitySize",
      "internalEdges",
      "crossCommunityEdges"
    )
    val crossEdges =
      input(cpPath, "analytics/cross-community-edges", "window", "communityA", "communityB")
    val paths = input(
      cpPath,
      "paths/path-evidence",
      "pairId",
      "window",
      "windowStartYear",
      "windowEndYear",
      "sourceResearcherId",
      "targetResearcherId",
      "status",
      "pathLength",
      "maxDepth",
      "componentId",
      "hops"
    )
    val researchers = input(cgPath, "canonical/researchers", "researcherId", "displayName")
    val authored =
      input(cgPath, "canonical/authored", "researcherId", "workId", "year", "sourceDatasets")
    val works = input(cgPath, "canonical/works", "workId", "title", "venueId")
    val venues = input(cgPath, "canonical/venues", "venueId", "name")
    val topics = input(cgPath, "canonical/topics", "topicId", "name", "subfield", "field")
    val workTopics = input(cgPath, "canonical/work-topics", "workId", "topicId")
    val researcherInstitutions =
      input(
        cgPath,
        "canonical/researcher-institutions",
        "researcherId",
        "institutionId",
        "evidenceIds"
      )
    val institutions = input(cgPath, "canonical/institutions", "institutionId", "name")
    val edges = input(
      cgPath,
      "graph/coauthor-edges",
      "window",
      "researcherA",
      "researcherB",
      "edgeId",
      "jointPublicationCount"
    )
    val w = CoauthorGraph.windows(spark, windows)
    val big = math.max(1, s.shufflePartitions / 5)
    // DBLP authorship per window: the basis of jobs 3 to 8 (a work is in a window by its year).
    val dblpWorks = authored
      .where(array_contains(col("sourceDatasets"), "DBLP"))
      .select("researcherId", "workId", "year")
      .join(works, "workId")
      .join(broadcast(w), col("year").between(col("windowStartYear"), col("windowEndYear")))
      .select("window", "researcherId", "workId", "year", "title", "venueId")
      .persist(StorageLevel.DISK_ONLY)
    // The largest component of each window (ties: smallest componentId), as in CommunityPathJob.
    val largest = components
      .withColumn(
        "r",
        row_number().over(
          Window.partitionBy("window").orderBy(desc("componentSize"), col("componentId"))
        )
      )
      .where(col("r") === 1)
      .select("window", "componentId")

    // ---- Job 9: temporal comparison ------------------------------------------------------------
    val windowSummary = write(
      "temporal/window-summary",
      step("temporal comparison") {
        def per(df: DataFrame, aggs: org.apache.spark.sql.Column*) =
          df.groupBy("window").agg(aggs.head, aggs.tail*)
        def median(c: String) = expr(s"percentile($c, 0.5)")
        Seq(
          per(dblpWorks.select("window", "workId").distinct(), count(lit(1)).as("publications")),
          per(
            metrics,
            count(lit(1)).as("researchers"),
            avg("distinctCoauthorCount").as("meanDistinctCoauthorCount"),
            median("distinctCoauthorCount").as("medianDistinctCoauthorCount"),
            avg("averageCoauthorsPerPaper").as("meanAverageCoauthorsPerPaper"),
            avg("maximumCoauthorsOnOnePaper").as("meanMaximumCoauthorsOnOnePaper"),
            max("maximumCoauthorsOnOnePaper").as("maxMaximumCoauthorsOnOnePaper"),
            avg("coreness").as("meanCoreness"),
            max("coreness").as("maxCoreness")
          ),
          per(
            edges,
            count(lit(1)).as("coauthorEdges"),
            sum("jointPublicationCount").as("sumJointPublicationCount")
          ),
          per(
            components,
            count(lit(1)).as("components"),
            max("componentSize").as("largestComponentSize"),
            sum(when(col("componentSize") === 1, 1L).otherwise(0L)).as("singletonResearchers")
          ),
          per(
            communities,
            count(lit(1)).as("communities"),
            max("communitySize").as("largestCommunitySize"),
            sum(when(col("communitySize") >= s.summaryMinCommunitySize, 1L).otherwise(0L))
              .as("communitiesAtLeastMinSize")
          ),
          per(
            memberships,
            avg("participationCoefficient").as("meanParticipationCoefficient"),
            median("participationCoefficient").as("medianParticipationCoefficient"),
            sum(when(col("crossCommunityDegree") > 0, 1L).otherwise(0L)).as(
              "researchersWithCrossCommunityEdge"
            )
          )
        ).foldLeft(w)((d, x) => d.join(x, Seq("window"), "left"))
          .withColumn("years", col("windowEndYear") - col("windowStartYear") + 1)
          .withColumn("publicationsPerYear", col("publications") / col("years"))
          .withColumn("largestComponentFraction", col("largestComponentSize") / col("researchers"))
          .withColumn(
            "crossCommunityResearcherFraction",
            col("researchersWithCrossCommunityEdge") / col("researchers")
          )
          .withColumn("provenance", ctx.provenance(col("window"), graphPolicy))
          .orderBy("windowStartYear", "windowEndYear")
      },
      Seq("dynamodb", "s3"),
      1
    )
    val changed = step("temporal comparison") {
      comparisons.map { (a, b) =>
        val (c, t) = Temporal.researcherChanges(
          metrics,
          memberships.drop("graphDegree"),
          largest,
          edges,
          a.name,
          b.name
        )
        def tag(df: DataFrame) = df.select(
          (Seq(
            lit(a.name).as("fromWindow"),
            lit(a.startYear).as("fromWindowStartYear"),
            lit(a.endYear).as("fromWindowEndYear"),
            lit(b.name).as("toWindow"),
            lit(b.startYear).as("toWindowStartYear"),
            lit(b.endYear).as("toWindowEndYear")
          ) ++ df.columns.map(col))*
        )
        (tag(c), tag(t))
      }
    }
    def stamp(df: DataFrame, id: org.apache.spark.sql.Column) =
      df.withColumn("provenance", ctx.provenance(id, graphPolicy))
    val changes = write(
      "temporal/researcher-changes",
      stamp(
        changed.map(_._1).reduce(_ union _),
        concat_ws("|", col("researcherId"), col("fromWindow"), col("toWindow"))
      ),
      Seq("dynamodb", "s3"),
      big,
      Some("researcherId")
    )
    val transitions = write(
      "temporal/community-transitions",
      stamp(
        changed.map(_._2).reduce(_ union _),
        concat_ws("|", col("fromCommunityId"), col("toCommunityId"))
      ),
      Seq("s3"),
      1
    )
    val windowComparison = write(
      "temporal/window-comparison",
      comparisons
        .map((a, b) =>
          Temporal
            .comparison(changes.where(col("fromWindow") === a.name && col("toWindow") === b.name))
            .select(
              (Seq(
                lit(a.name).as("fromWindow"),
                lit(a.startYear).as("fromWindowStartYear"),
                lit(a.endYear).as("fromWindowEndYear"),
                lit(b.name).as("toWindow"),
                lit(b.startYear).as("toWindowStartYear"),
                lit(b.endYear).as("toWindowEndYear")
              ) :+ col("*"))*
            )
        )
        .reduce(_ union _)
        .withColumn("inclusionLimitation", lit(InclusionLimitation))
        .transform(stamp(_, concat_ws("->", col("fromWindow"), col("toWindow")))),
      Seq("dynamodb", "s3"),
      1
    )

    // ---- Job 10: semantic-context summaries ----------------------------------------------------
    val in = Summaries.In(
      researchers = researchers,
      metrics = metrics,
      memberships = memberships,
      largest = largest,
      edges = edges,
      dblpWorks = dblpWorks,
      works = works,
      venues = venues,
      topics = topics,
      workTopics = workTopics,
      institutions = researcherInstitutions
        .join(institutions, "institutionId")
        .select(
          col("researcherId"),
          col("institutionId"),
          col("name"),
          size(col("evidenceIds")).as("evidenceCount")
        ),
      communities = communities,
      crossEdges = crossEdges,
      paths = paths,
      windows = w
    )
    val idx = cfg.s3Vectors.indexes
    val summaries = write(
      "summaries/semantic-summaries",
      step("summaries") {
        Seq(
          Summaries.profiles(in, profileWindow, s.summaryMinPublications),
          Summaries.communities(in, s.summaryMinCommunitySize),
          Summaries.paths(in),
          Summaries.topics(in)
        ).reduce(_ unionByName _)
          .withColumn(
            "vectorIndex",
            when(
              col("documentType") === Summaries.ProfileDoc,
              lit(idx.getOrElse("researchers", null))
            )
              .when(
                col("documentType") === Summaries.CommunityDoc,
                lit(idx.getOrElse("communities", null))
              )
              .when(col("documentType") === Summaries.PathDoc, lit(idx.getOrElse("paths", null)))
              .otherwise(lit(idx.getOrElse("topic-institutions", null)))
          )
          .withColumn("provenance", ctx.provenance(col("summaryId"), Summaries.TemplateVersion))
      },
      Seq("s3vectors", "s3"),
      2,
      Some("subjectId"),
      Seq("documentType", "window")
    )

    // ---- Job 11: export (load manifest over every table of the chain) --------------------------
    def entries(m: ujson.Value, tables: Seq[ujson.Value]): Seq[ujson.Obj] =
      val (stage, runId) = (m("stage").str, m("runId").str)
      tables.map { t =>
        val rowCount = t("rowCount").num.toLong
        val verified = step("export") {
          checkedRowCount(spark, t("path").str, t("schema").str, rowCount, runId, m("snapshot").str)
        }
        ujson.Obj(
          "logicalName" -> Logical.getOrElse(t("name").str, t("name").str),
          "table" -> t("name").str,
          "schemaVersion" -> t("schemaVersion"),
          "stage" -> stage,
          "runId" -> runId,
          "codeVersion" -> m("codeVersion"),
          "path" -> t("path").str,
          "format" -> "parquet",
          "partitionColumns" -> t("partitionColumns"),
          "schema" -> t("schema"),
          "rowCount" -> rowCount.toDouble,
          "verifiedRowCount" -> verified.toDouble,
          "intendedTargets" -> t("intendedTargets")
        )
      }
    val upstream =
      Seq(cg, ra, cp).flatMap(m => entries(m, m("tables").arr.toSeq))
    val ownBefore = entries(head, jr.writtenTables)

    // ---- Integrity -----------------------------------------------------------------------------
    def doc(d: String) = summaries.where(col("documentType") === d)
    val mismatched =
      (upstream ++ ownBefore).filter(e => e("rowCount").num != e("verifiedRowCount").num)
    val violations = write(
      "summary-export-violations",
      Violations.stamped(
        ctx,
        Violations(
          "SUMMARY_MISSING",
          metrics
            .where(
              col("window") === profileWindow && col("publicationCount") >= s.summaryMinPublications
            )
            .select(
              col("researcherId").as("subjectId"),
              col("window"),
              lit(Summaries.ProfileDoc).as("d")
            )
            .union(
              communities
                .where(col("communitySize") >= s.summaryMinCommunitySize)
                .select(col("communityId"), col("window"), lit(Summaries.CommunityDoc))
            )
            .union(paths.select(col("pairId"), col("window"), lit(Summaries.PathDoc)))
            .join(
              summaries.select(col("subjectId"), col("window"), col("documentType").as("d")),
              Seq("subjectId", "window", "d"),
              "left_anti"
            ),
          col("subjectId"),
          col("window"),
          col("d")
        ).union(
          Violations(
            "SUMMARY_TEXT_EMPTY",
            summaries.where(col("text").isNull || length(col("text")) === 0),
            col("summaryId"),
            col("window"),
            col("documentType")
          )
        ).union(
          Violations(
            "TEMPORAL_PRESENCE_MISMATCH",
            windowComparison
              .join(
                windowSummary
                  .select(col("window").as("fromWindow"), col("researchers").as("nFrom")),
                "fromWindow"
              )
              .join(
                windowSummary.select(col("window").as("toWindow"), col("researchers").as("nTo")),
                "toWindow"
              )
              .where(
                col("researchersFrom") =!= col("nFrom") || col("researchersTo") =!= col("nTo")
              ),
            concat_ws("->", col("fromWindow"), col("toWindow")),
            col("toWindow"),
            lit("researchers per window differ from researcher-metrics")
          )
        ).union(
          Violations(
            "EXPORT_ROWCOUNT_MISMATCH",
            spark.createDataFrame(
              java.util.Arrays.asList(
                mismatched.map(e =>
                  Row(
                    e("path").str,
                    s"${e("rowCount").num.toLong} != ${e("verifiedRowCount").num.toLong}"
                  )
                )*
              ),
              StructType.fromDDL("path STRING, detail STRING")
            ),
            col("path"),
            lit(null),
            col("detail")
          )
        )
      ),
      Seq("s3"),
      1
    )
    val exported = upstream ++ entries(head, jr.writtenTables)
    val loadManifest = ujson.Obj(
      "schemaVersion" -> ExportSchema,
      "runId" -> jr.runId,
      "snapshot" -> cp("snapshot").str,
      "codeVersion" -> s.codeVersion,
      "inputHadoopRunId" -> cp("lineage")("inputHadoopRunId"),
      "matchPolicyVersion" -> cp("lineage")("matchPolicyVersion"),
      "graphPolicy" -> graphPolicy,
      "windows" -> head("settings")("windows"),
      "chain" -> ujson.Obj(
        "canonicalGraphRunId" -> cg("runId").str,
        "researcherAnalyticsRunId" -> ra("runId").str,
        "communityPathRunId" -> cp("runId").str,
        "temporalSummaryExportRunId" -> jr.runId
      ),
      "note" -> "Parquet tables are exported in place; store-specific formats (Neptune bulk-load CSV, DynamoDB items, S3 Vectors) are Phase 7+8 (ruling 13).",
      "tables" -> ujson.Arr.from(exported),
      "stores" -> ujson.Obj.from(
        Stores.map(st =>
          st -> ujson.Arr.from(
            exported.filter(_("intendedTargets").arr.exists(_.str == st)).map(e => e("logicalName"))
          )
        )
      )
    )
    val loadManifestPath = new Path(jr.runDir, "export/load-manifest.json")
    JobRun.writeText(jr.ofs, loadManifestPath, ujson.write(loadManifest, indent = 2))

    // ---- Counts (bounded aggregates over the written tables) -----------------------------------
    val counts = step("report") {
      ujson.Obj(
        "windowSummary" -> rows(windowSummary.drop("provenance")),
        "windowComparison" -> rows(windowComparison.drop("inclusionLimitation")),
        "researcherChangesByPresence" -> rows(
          changes
            .groupBy("fromWindow", "toWindow", "presence")
            .count()
            .orderBy("fromWindow", "toWindow", "presence")
        ),
        "largestComponentTransitions" -> rows(
          changes
            .groupBy("fromWindow", "toWindow", "largestComponentTransition")
            .count()
            .orderBy("fromWindow", "toWindow", "largestComponentTransition")
        ),
        "crossCommunityTransitions" -> rows(
          changes
            .groupBy("fromWindow", "toWindow", "crossCommunityTransition")
            .count()
            .orderBy("fromWindow", "toWindow", "crossCommunityTransition")
        ),
        "changeDistributions" -> JobRun.distributions(
          changes
            .where(col("presence") === Temporal.Both)
            .withColumn("window", concat_ws("->", col("fromWindow"), col("toWindow"))),
          Seq(
            "deltaDistinctCoauthorCount",
            "deltaAverageCoauthorsPerPaper",
            "deltaMaximumCoauthorsOnOnePaper",
            "deltaCoreness",
            "deltaParticipationCoefficient",
            "retainedCoauthors",
            "communityRetention"
          )
        ),
        "communityTransitionRows" -> transitions.count().toDouble,
        "summariesByTypeAndWindow" -> rows(
          summaries
            .groupBy("documentType", "window")
            .agg(
              count(lit(1)).as("summaries"),
              min(length(col("text"))).as("minTextLength"),
              max(length(col("text"))).as("maxTextLength"),
              avg(length(col("text"))).as("meanTextLength")
            )
            .orderBy("documentType", "window")
        ),
        // One sample per document type: the first subject by (window, subjectId).
        "sampleSummaries" -> rows(
          summaries
            .withColumn(
              "r",
              row_number().over(
                Window.partitionBy("documentType").orderBy(col("window"), col("subjectId"))
              )
            )
            .where(col("r") === 1)
            .select("documentType", "window", "subjectId", "summaryId", "text")
            .orderBy("documentType")
        ),
        "exportTables" -> exported.size,
        "exportRowCountMismatches" -> mismatched.size,
        "loadManifest" -> loadManifestPath.toString,
        "storeTableCounts" -> ujson.Obj.from(
          Stores.map(st => st -> ujson.Num(loadManifest("stores")(st).arr.size))
        ),
        "integrityViolationsByCheck" -> rows(violations.groupBy("check").count().orderBy("check"))
      )
    }

    val manifest =
      jr.finish(head, "loadManifestPath" -> loadManifestPath.toString, "counts" -> counts)
    Seq(
      metrics,
      components,
      memberships,
      communities,
      crossEdges,
      paths,
      researchers,
      authored,
      works,
      venues,
      topics,
      workTopics,
      researcherInstitutions,
      institutions,
      edges,
      dblpWorks
    ).foreach(_.unpersist())
    RunResult(jr.runId, jr.attempt, jr.runDir.toString, manifest)

  /** Job 11's check of one table before it is listed (rulings 21, 22): the table must have the
    * schemaVersion and provenance columns, and every row a schemaVersion, the writing run's runId
    * and snapshot, and a source pointer (provenance.sourceRecordId). A table that fails is refused:
    * the export stops and writes no load manifest. Returns the re-read row count.
    */
  def checkedRowCount(
      spark: SparkSession,
      path: String,
      schemaDdl: String,
      rowCount: Long,
      runId: String,
      snapshot: String
  ): Long =
    // An empty partitioned table has no files to read: check the schema the manifest recorded.
    val df =
      if rowCount == 0 then
        spark.createDataFrame(java.util.List.of[Row](), StructType.fromDDL(schemaDdl))
      else spark.read.parquet(path)
    val missing = JobRun.provenanceProblems(df.schema)
    require(missing.isEmpty, s"export refused $path: ${missing.mkString("; ")}")
    val bad = col("schemaVersion").isNull || col("provenance.sourceRecordId").isNull ||
      !col("provenance.runId").eqNullSafe(runId) || !col("provenance.snapshot").eqNullSafe(snapshot)
    val r = df.agg(count(lit(1)), sum(when(bad, 1L).otherwise(0L))).head()
    val badRows = if r.isNullAt(1) then 0L else r.getLong(1)
    require(
      badRows == 0,
      s"export refused $path: $badRows rows lack schemaVersion, provenance.sourceRecordId, or " +
        s"provenance runId $runId and snapshot $snapshot"
    )
    r.getLong(0)

  /** Job 9's stated limitation (spec: do not compare windows with incompatible inclusion rules
    * without saying so).
    */
  val InclusionLimitation: String =
    "Windows share one canonical identity and one graph definition, but their contents are not " +
      "equally sampled: the DBLP subset (D-10, about 100k works) was selected as a bounded subset, " +
      "so its coverage of venues and years, and the OpenAlex/Crossref/ORCID enrichment driven by " +
      "it, may differ between periods; the windows have different lengths (15 vs 12 years) and " +
      "the last year may be incomplete; communities are computed separately per window by a " +
      "label propagation with a round cap, so community IDs never match across windows. Differences between windows mix real change with " +
      "these coverage differences."
