package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Spark jobs 1 and 2 (spec Phase 6): canonical entities and the temporal coauthorship graph.
  *
  * Input: one Hadoop run directory (modules/hadoop/JOBS.md), read-only. Output and run identity:
  * JobRun (`<out>/snapshot=<dblp snapshot>/run=<runId>/`); the fingerprints are the Hadoop run,
  * input size, and analytics configuration.
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.CanonicalGraphJob --input <hadoop run
  * dir> --code-version <git sha> [--out scratch/spark] [--shuffle-partitions 40] [--attempt n]"`
  */
object CanonicalGraphJob:
  val Stage = "spark-canonical-graph"
  val ManifestSchema = JobRun.ManifestSchema

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

  import JobRun.{readText, rows, strs}

  /** The DBLP snapshot named by a Hadoop run manifest (`inputSnapshots` entry `dblp=<date>`). */
  def dblpSnapshot(hm: ujson.Value): String = hm("inputSnapshots").arr
    .map(_.str)
    .collectFirst { case x if x.startsWith("dblp=") => x.drop(5) }
    .getOrElse(sys.error("the Hadoop manifest names no dblp snapshot"))

  def run(spark: SparkSession, s: SparkSettings, cfg: ScholarChainConfig): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    val inPath = new Path(s.input)
    val ifs = inPath.getFileSystem(hconf)
    val hm = ujson.read(readText(ifs, new Path(inPath, "_manifest.json")))
    val hadoopRunId = hm("runId").str
    val snapshot = dblpSnapshot(hm)
    val erVersion = hm("matchPolicyVersion").str
    val canonicalPolicy = s"${s.canonicalPolicyVersion}.$erVersion"
    val windows = cfg.graph.windows
    val fingerprints = Seq(
      s"hadoop-run=$hadoopRunId",
      s"input:hadoop=${s.input}:${ifs.getContentSummary(inPath).getLength}",
      s"config:canonical-policy=$canonicalPolicy",
      s"config:graph-policy=${cfg.graph.policyVersion}",
      s"config:windows=${windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")}",
      s"config:cc-max-iterations=${s.ccMaxIterations}"
    )
    val jr = new JobRun(spark, Stage, s, cfg, snapshot, fingerprints)
    val runId = jr.runId
    val settingsJson = ujson.Obj(
      "master" -> spark.sparkContext.master,
      "shufflePartitions" -> s.shufflePartitions,
      "adaptiveExecution" -> spark.conf.get("spark.sql.adaptive.enabled"),
      "checkpointDir" -> jr.checkpointDir.toString,
      "canonicalPolicy" -> canonicalPolicy,
      "graphPolicy" -> cfg.graph.policyVersion,
      "windows" -> ujson.Arr.from(
        windows.map(w =>
          ujson.Obj("name" -> w.name, "startYear" -> w.startYear, "endYear" -> w.endYear)
        )
      ),
      "ccMaxIterations" -> s.ccMaxIterations,
      "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
      "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble,
      "availableProcessors" -> Runtime.getRuntime.availableProcessors
    )
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "hadoopRunId" -> hadoopRunId,
        "matchPolicyVersion" -> erVersion
      ),
      "settings" -> settingsJson,
      "lineage" -> ujson.Obj(
        "inputHadoopRunId" -> hadoopRunId,
        "matchPolicyVersion" -> erVersion
      )
    )
    jr.start(head)
    import jr.{step, write}

    // ---- Inputs ------------------------------------------------------------------------------
    def input(rel: String, ddl: String, level: StorageLevel = StorageLevel.MEMORY_AND_DISK)(
        project: DataFrame => DataFrame = identity
    ): DataFrame = step("read inputs") {
      val p = new Path(inPath, rel)
      val df = project(Inputs.json(spark, s.input, rel, ddl)).persist(level)
      jr.recordInput(p.toString, ifs.exists(p), df.count())
      df
    }
    import Inputs.Dir
    val mentions = input(Dir.Mentions, Inputs.Mention)()
    val works = input(Dir.Works, Inputs.Work)()
    val pairs = input(Dir.Pairs, Inputs.Pair)()
    def acceptedEdges(df: DataFrame) = df.select(
      col("sourceId").as("src"),
      col("targetId").as("dst"),
      MatchPath.tier(col("rulesFired")).as("tier"),
      MatchPath.of(col("rulesFired")).as("path"),
      col("confidence")
    )
    def ends(df: DataFrame) = df.select(col("sourceId").as("src"), col("targetId").as("dst"))
    val disk = StorageLevel.DISK_ONLY
    val rAcc = input(Dir.decisions("RESEARCHER", "ACCEPTED"), Inputs.Decision, disk)(acceptedEdges)
    val rConf = input(Dir.decisions("RESEARCHER", "CONFLICT"), Inputs.Decision)(ends)
    val rUnres = input(Dir.decisions("RESEARCHER", "UNRESOLVED"), Inputs.Decision, disk)(ends)
    val wAcc = input(Dir.decisions("WORK", "ACCEPTED"), Inputs.Decision)(acceptedEdges)
    val wConf = input(Dir.decisions("WORK", "CONFLICT"), Inputs.Decision)(ends)
    val wUnres = input(Dir.decisions("WORK", "UNRESOLVED"), Inputs.Decision)(ends)
    val suffixGroups = input(Dir.SuffixConflicts, Inputs.SuffixGroup)()
    val oversizeGroups = input(Dir.OversizeConflicts, Inputs.OversizeGroup)()
    val venues = input(Dir.Venues, Inputs.Venue)()
    val topics = input(Dir.Topics, Inputs.Topic)()
    val institutions = input(Dir.Institutions, Inputs.Institution)()
    val hasTopic = input(Dir.HasTopic, Inputs.GraphEdge)()
    val affiliated = input(Dir.AffiliatedWith, Inputs.GraphEdge)()
    val decisionPaths = step("read inputs") {
      rows(
        rAcc
          .withColumn("entityType", lit("RESEARCHER"))
          .union(wAcc.withColumn("entityType", lit("WORK")))
          .groupBy("entityType", "tier", "path")
          .count()
          .orderBy("entityType", "tier", "path")
      )
    }
    val rGroups = suffixGroups
      .select(col("identifier"), col("groupSize"), lit("suffix-conflicts").as("origin"))
      .union(
        oversizeGroups
          .where(col("entityType") === "RESEARCHER")
          .select(col("identifier"), col("groupSize"), lit("oversize-conflicts").as("origin"))
      )
    val wGroups = oversizeGroups
      .where(col("entityType") === "WORK")
      .select(col("identifier"), col("groupSize"), lit("oversize-conflicts").as("origin"))

    val ctx = Ctx(snapshot, runId, canonicalPolicy, cfg.graph.policyVersion, s.ccMaxIterations)
    val p = s.shufflePartitions
    val big = math.max(1, p / 5)

    // ---- Job 1: canonical entities -------------------------------------------------------------
    val rc = step("cluster researchers") {
      Canonical.researchers(ctx, mentions, rAcc.drop("path"), rConf, rUnres, rGroups)
    }
    val mentionMap = write(
      "canonical/mention-map",
      Canonical.mentionMap(ctx, rc.result.mapping, mentions),
      Seq("s3"),
      big,
      Some("researcherId")
    )
    // A refusal row's source pointer is its sample member (a mention or source work ID).
    def refusalTable(df: DataFrame, entityType: String) = df
      .withColumn("entityType", lit(entityType))
      .withColumn(
        "provenance",
        ctx.provenance(
          concat_ws("|", lit(entityType), col("tier"), col("sampleMember")),
          ctx.canonicalPolicy
        )
      )
    val rRefusals = write(
      "canonical/researcher-refusals",
      refusalTable(rc.result.refusals, "RESEARCHER"),
      Seq("s3"),
      1
    )
    val rGroupCheck = step("cluster researchers")(rows(rc.groupCheck.orderBy("identifier")))
    val wc = step("cluster works") {
      Canonical.works(ctx, works, wAcc.drop("path"), wConf, wUnres, wGroups)
    }
    val workMap =
      write(
        "canonical/work-map",
        Canonical.workMap(ctx, wc.result.mapping, works),
        Seq("s3"),
        big,
        Some("workId")
      )
    val wRefusals = write(
      "canonical/work-refusals",
      refusalTable(wc.result.refusals, "WORK"),
      Seq("s3"),
      1
    )
    val wGroupCheck = step("cluster works")(rows(wc.groupCheck.orderBy("identifier")))

    val researchers = write(
      "canonical/researchers",
      Canonical.researcherTable(ctx, mentionMap, mentions),
      Seq("neptune", "dynamodb", "s3"),
      big,
      Some("researcherId")
    )
    val workTable = write(
      "canonical/works",
      Canonical.workTable(ctx, workMap, works),
      Seq("neptune", "dynamodb", "s3"),
      big,
      Some("workId")
    )
    val (authoredDf, authoredMissing) = Canonical.authored(ctx, mentionMap, workMap, workTable)
    val authored =
      write("canonical/authored", authoredDf, Seq("neptune", "dynamodb"), big, Some("researcherId"))
    val venueTable =
      write("canonical/venues", ctx.restamp(venues, "venueId"), Seq("neptune", "s3"), 1)
    val topicTable =
      write("canonical/topics", ctx.restamp(topics, "topicId"), Seq("neptune", "s3"), 1)
    val institutionTable = write(
      "canonical/institutions",
      ctx.restamp(institutions, "institutionId"),
      Seq("neptune", "s3"),
      1
    )
    val (wt, wtBad) = Canonical.workTopics(ctx, hasTopic, workMap, topicTable)
    write("canonical/work-topics", wt, Seq("neptune"), 1)
    val (ri, riBad, riUnlinked) = Canonical.researcherInstitutions(
      ctx,
      mentions,
      mentionMap,
      researchers,
      affiliated,
      institutionTable
    )
    write("canonical/researcher-institutions", ri, Seq("neptune"), 2)
    val orcidAffiliationsUnlinked = step("researcher institutions")(riUnlinked.count())

    // ---- Job 2: coauthorship graph ------------------------------------------------------------
    val g = CoauthorGraph.build(ctx, spark, pairs, mentionMap, workMap, windows)
    val edges = write(
      "graph/coauthor-edges",
      g.edges,
      Seq("neptune", "dynamodb"),
      big,
      Some("researcherA"),
      Seq("window")
    )
    val selfPairs = write("graph/self-pairs", g.selfPairs, Seq("s3"), 1)
    val outsideWindows = step("coauthor graph")(g.outsideWindows.count())

    // ---- Integrity -----------------------------------------------------------------------------
    val violations = write(
      "integrity-violations",
      Violations.stamped(
        ctx,
        rc.violations
          .union(wc.violations)
          .union(authoredMissing)
          .union(Canonical.authoredIntegrity(authored, researchers, workTable))
          .union(Canonical.venueIntegrity(workTable, venueTable))
          .union(wtBad)
          .union(riBad)
          .union(g.violations)
          .union(CoauthorGraph.integrity(edges, researchers))
      ),
      Seq("s3"),
      1
    )

    // ---- Counts (bounded aggregates over the written tables) ------------------------------------
    val counts = step("report") {
      val w = CoauthorGraph.windows(spark, windows)
      val inW = col("year").between(col("windowStartYear"), col("windowEndYear"))
      def bucket(n: org.apache.spark.sql.Column) =
        when(n === 1, "1")
          .when(n === 2, "2")
          .when(n <= 5, "3-5")
          .when(n <= 10, "6-10")
          .when(n <= 20, "11-20")
          .when(n <= 50, "21-50")
          .when(n <= 100, "51-100")
          .when(n <= 500, "101-500")
          .otherwise("501+")
      val worksW = workTable
        .join(w, inW)
        .groupBy("window")
        .agg(
          count(lit(1)).as("works"),
          sum(col("dblpAnchored").cast("long")).as("dblpAnchoredWorks")
        )
      val authW = authored
        .join(researchers.select("researcherId", "dblpAnchored"), "researcherId")
        .join(w, inW)
        .groupBy("window")
        .agg(
          count(lit(1)).as("authoredEdges"),
          countDistinct("researcherId").as("activeResearchers"),
          countDistinct(when(col("dblpAnchored"), col("researcherId")))
            .as("activeDblpAnchoredResearchers")
        )
      val edgesW = edges
        .groupBy("window")
        .agg(
          count(lit(1)).as("coauthorEdges"),
          sum("jointPublicationCount").as("sumJointPublicationCount"),
          max("jointPublicationCount").as("maxJointPublicationCount"),
          max(size(col("representativeWorkIds"))).as("maxSupportingWorkIds")
        )
      val inGraph = edges
        .select(col("window"), explode(array(col("researcherA"), col("researcherB"))).as("r"))
        .groupBy("window")
        .agg(countDistinct("r").as("researchersWithCoauthorEdge"))
      val perWindow = w
        .join(worksW, Seq("window"), "left")
        .join(authW, Seq("window"), "left")
        .join(edgesW, Seq("window"), "left")
        .join(inGraph, Seq("window"), "left")
        .orderBy("windowStartYear", "windowEndYear")
      def refusalCounts(df: DataFrame) =
        df.select(col("tier"), explode(col("rules")).as("rule"))
          .groupBy("tier", "rule")
          .count()
          .orderBy("tier", "rule")
      def refusedClusters(df: DataFrame) =
        df.groupBy("tier")
          .agg(count(lit(1)).as("clusters"), sum("clusterMemberCount").as("members"))
          .orderBy("tier")
      ujson.Obj(
        "decisionEdgesByTierAndPath" -> decisionPaths,
        "researcherRefusalsByTierAndRule" -> rows(refusalCounts(rRefusals)),
        "researcherRefusedClustersByTier" -> rows(refusedClusters(rRefusals)),
        "workRefusalsByTierAndRule" -> rows(refusalCounts(wRefusals)),
        "workRefusedClustersByTier" -> rows(refusedClusters(wRefusals)),
        "researcherConflictGroupCheck" -> rGroupCheck,
        "workConflictGroupCheck" -> wGroupCheck,
        "mentionsByTierAndReason" -> rows(
          mentionMap
            .groupBy("assignedTier", "separationReason")
            .count()
            .orderBy("assignedTier", "separationReason")
        ),
        "worksByTierAndReason" -> rows(
          workMap
            .groupBy("assignedTier", "separationReason")
            .count()
            .orderBy("assignedTier", "separationReason")
        ),
        "researcherClusterSizes" -> rows(
          researchers
            .groupBy(bucket(col("mentionCount")).as("mentions"))
            .agg(count(lit(1)).as("researchers"), min("mentionCount").as("lo"))
            .orderBy("lo")
            .drop("lo")
        ),
        "largestResearcherClusters" -> rows(
          researchers
            .orderBy(desc("mentionCount"), col("researcherId"))
            .select(
              "researcherId",
              "displayName",
              "mentionCount",
              "assignedTier",
              "sourceDatasets",
              "orcid"
            )
            .limit(10)
        ),
        "workClusterSizes" -> rows(
          workTable
            .groupBy(bucket(size(col("memberWorkIds"))).as("members"))
            .agg(count(lit(1)).as("works"), min(size(col("memberWorkIds"))).as("lo"))
            .orderBy("lo")
            .drop("lo")
        ),
        // Skew: refused giant clusters, hub researchers, giant teams (law 5).
        "largestRefusedResearcherClusters" -> rows(
          rRefusals
            .orderBy(desc("clusterMemberCount"), col("tier"), col("clusterKey"))
            .select("tier", "clusterMemberCount", "clusterNodeCount", "sampleMember", "rules")
            .limit(5)
        ),
        "highestDegreeResearchersWidestWindow" -> rows(
          edges
            .where(col("window") === windows.maxBy(w => w.endYear - w.startYear).name)
            .select(explode(array(col("researcherA"), col("researcherB"))).as("researcherId"))
            .groupBy("researcherId")
            .agg(count(lit(1)).as("degree"))
            .orderBy(desc("degree"), col("researcherId"))
            .limit(5)
            .join(researchers.select("researcherId", "displayName"), "researcherId")
            .orderBy(desc("degree"), col("researcherId"))
        ),
        "largestDblpTeams" -> rows(
          mentions
            .where(col("provenance.sourceDataset") === "DBLP")
            .groupBy("workId")
            .agg(count(lit(1)).as("teamSize"))
            .orderBy(desc("teamSize"), col("workId"))
            .limit(5)
        ),
        "worksWithSeveralDblpMembers" -> workTable
          .where(size(filter(col("memberWorkIds"), x => x.startsWith("dblp:"))) > 1)
          .count()
          .toDouble,
        "researchers" -> researchers.count().toDouble,
        "dblpAnchoredResearchers" -> researchers.where(col("dblpAnchored")).count().toDouble,
        "works" -> workTable.count().toDouble,
        "dblpAnchoredWorks" -> workTable.where(col("dblpAnchored")).count().toDouble,
        "authoredEdges" -> authored.count().toDouble,
        "perWindow" -> rows(perWindow),
        "selfPairsDropped" -> selfPairs.count().toDouble,
        "selfPairWorks" -> selfPairs.select("workId").distinct().count().toDouble,
        "pairsOutsideEveryWindow" -> outsideWindows.toDouble,
        "orcidAffiliationsNotLinked" -> orcidAffiliationsUnlinked.toDouble,
        "integrityViolationsByCheck" -> rows(violations.groupBy("check").count().orderBy("check")),
        "connectedComponentRounds" -> ujson.Obj(
          "researcherTier1" -> rc.result.rounds(0),
          "researcherTier2" -> rc.result.rounds(1),
          "workTier1" -> wc.result.rounds(0),
          "workTier2" -> wc.result.rounds(1)
        )
      )
    }

    val manifest = jr.finish(head, "counts" -> counts)
    Seq(mentions, works, pairs, rAcc, rConf, rUnres, wAcc, wConf, wUnres).foreach(_.unpersist())
    RunResult(runId, jr.attempt, jr.runDir.toString, manifest)
