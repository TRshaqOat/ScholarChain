package scholarchain.spark

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.collection.mutable

/** Phase 7 work order 6: the Phase 6 Parquet tables plus the benchmark path run, converted to the
  * store-ready files of modules/storage/STORAGE.md (rulings 2 to 9, 21, 25).
  *
  * Inputs, read-only: `--input` the Phase 6 TemporalSummaryExportJob run directory (its
  * `export/load-manifest.json` lists every table), and `--paths` a BenchmarkPathJob run directory
  * of the same chain. Outputs, write-once, under `--stores-root` (the local S3 fake,
  * `scratch/stores/s3`) with keys `<prefix>/<artifact>/snapshot=<d>/run=<this run>/...`:
  *   - `graph/neptune/.../{vertices,edges}/<name>/part-NNNNN.csv`: openCypher bulk-load CSV
  *   - `curated/coauthor-evidence/.../window=<w>/<edgeId>.json`: evidence of edges with more than
  *     20 joint works
  *   - `curated/dynamodb/.../<Table>/part-NNNNN.jsonl`: ResearcherMetricCache (WINDOW#, CHANGE#,
  *     RANKING#), EntityLinkCache, ArtifactRegistry
  *   - `semantic-context/candidates/.../<index>/part-00000.jsonl`: embedding candidates
  *     (selection-v1, modules/rag/SELECTION.md), with `facts`
  *   - `runs/store-conversion/.../manifest.json`: store-run-manifest-v1 (key, sha256, bytes,
  *     sourceRef of every object), written last
  *
  * Spark writes each file family as text under the JobRun directory (`--out`); the driver then
  * streams each part, with its header, to its final key while hashing it, and deletes the temp
  * part. The driver holds one 64 KB buffer, never a table.
  */
object StoreConversionJob:
  val Stage = "store-conversion"
  val StoreManifestSchema = "store-run-manifest-v1"
  private val RowsPerPart = 250000L

  final case class RunResult(
      runId: String,
      attempt: Int,
      runDir: String,
      manifest: ujson.Obj,
      storeManifestKey: String
  )

  /** One object written: S3 key, sha256, size, and what it was built from. */
  final case class Entry(key: String, sha256: String, bytes: Long, sourceRef: String)

  def main(args: Array[String]): Unit =
    val (extra, rest) = JobArgs.split(args.toSeq, Set("paths", "stores-root"))
    val s = SparkSettings.parse(rest) match
      case Right(v) => v
      case Left(e)  => System.err.println(e); sys.exit(2)
    val paths =
      extra.getOrElse("paths", { System.err.println("--paths <path run dir>"); sys.exit(2) })
    val cfg = ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity)
    val spark = JobRun.session(s, Stage)
    try
      val r = run(spark, s, cfg, paths, extra.getOrElse("stores-root", "scratch/stores/s3"))
      println(
        s"runId=${r.runId} attempt=${r.attempt} runDir=${r.runDir} manifest=${r.storeManifestKey}"
      )
    finally spark.stop()

  private def hex(b: Array[Byte]) = b.map(x => f"${x & 0xff}%02x").mkString

  /** sha256 over "<relative key> <sha256>" lines in key order (storage `Cli.contentHash`). */
  def contentHash(base: String, entries: Seq[Entry]): String =
    val text = entries
      .sortBy(_.key)
      .map(e => s"${e.key.stripPrefix(base + "/")} ${e.sha256}")
      .mkString("\n")
    "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)))

  def run(
      spark: SparkSession,
      s: SparkSettings,
      cfg: ScholarChainConfig,
      pathRun: String,
      storesRoot: String
  ): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    def readJson(p: Path) = ujson.read(JobRun.readText(p.getFileSystem(hconf), p))
    def sizeOf(p: Path) = p.getFileSystem(hconf).getContentSummary(p).getLength
    val exportDir = new Path(s.input)
    val lm = readJson(new Path(exportDir, "export/load-manifest.json"))
    require(lm("schemaVersion").str == TemporalSummaryExportJob.ExportSchema, "not a load manifest")
    val pathDir = new Path(pathRun)
    val pm = readJson(new Path(pathDir, "_manifest.json"))
    require(pm("stage").str == BenchmarkPathJob.Stage, s"$pathRun is not a benchmark path run")
    require(
      pm("input")("communityPathRunId").str == lm("chain")("communityPathRunId").str,
      "the path run and the export are not one chain"
    )
    val windows = cfg.graph.windows
    val windowNames = windows.map(w => s"${w.name}:${w.startYear}-${w.endYear}")
    require(
      lm("windows").arr.map(w =>
        s"${w("name").str}:${w("startYear").num.toInt}-${w("endYear").num.toInt}"
      ) ==
        windowNames,
      "configured windows differ from the export's windows"
    )
    val snapshot = lm("snapshot").str
    val tables = lm("tables").arr.map(t => t("table").str -> t).toMap
    val runDirs = tables.values.map(t => t("runId").str -> new Path(t("path").str).getParent).toMap
    val fingerprints = Seq(
      s"export-run=${lm("runId").str}",
      s"path-run=${pm("runId").str}",
      s"input:path-run=$pathDir:${sizeOf(pathDir)}",
      s"config:windows=${windowNames.mkString(",")}",
      s"selection=${Selection.PolicyVersion}:${Selection.Seed}",
      s"evidence-inline-max=${StoreFiles.InlineEvidenceMax}",
      s"ranking-max=${StoreFiles.RankingMax}"
    ) ++ lm("tables").arr.map(t =>
      s"input:${t("table").str}=${t("runId").str}:${t("rowCount").num.toLong}"
    )
    val jr = new JobRun(spark, Stage, s, cfg, snapshot, fingerprints)
    val runId = jr.runId
    def base(prefix: String, artifact: String) = s"$prefix/$artifact/snapshot=$snapshot/run=$runId"
    val neptuneBase = base("graph", "neptune")
    val dynamoBase = base("curated", "dynamodb")
    val evidenceBase = base("curated", "coauthor-evidence")
    val candidatesBase = base("semantic-context", "candidates")
    val manifestKey = s"${base("runs", Stage)}/manifest.json"
    val root = new Path(storesRoot)
    // A private instance with checksums off: a local .crc file under graph/ would be loaded too.
    val sfs = FileSystem.newInstance(root.toUri, hconf)
    sfs.setWriteChecksum(false)
    Seq(neptuneBase, dynamoBase, evidenceBase, candidatesBase, manifestKey).foreach(k =>
      require(!sfs.exists(new Path(root, k)), s"$k exists; store objects are write-once")
    )
    val idx = cfg.s3Vectors.indexes
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "loadManifest" -> new Path(exportDir, "export/load-manifest.json").toString,
        "exportRunId" -> lm("runId").str,
        "chain" -> lm("chain"),
        "pathRunDir" -> pathRun,
        "pathRunId" -> pm("runId").str,
        "storesRoot" -> storesRoot
      ),
      "lineage" -> ujson.Obj(
        "inputHadoopRunId" -> lm("inputHadoopRunId"),
        "matchPolicyVersion" -> lm("matchPolicyVersion")
      ),
      "settings" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "shufflePartitions" -> s.shufflePartitions,
        "rowsPerPart" -> RowsPerPart.toDouble,
        "inlineEvidenceMax" -> StoreFiles.InlineEvidenceMax,
        "maxInlineIds" -> StoreFiles.MaxInlineIds,
        "rankingMax" -> StoreFiles.RankingMax,
        "selectionPolicy" -> Selection.PolicyVersion,
        "selectionSeed" -> Selection.Seed,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble
      )
    )
    jr.start(head)
    import jr.step
    val graphPolicy = lm("graphPolicy").str
    val ctx = Ctx(snapshot, runId, "", graphPolicy, 0)

    // ---- Inputs ---------------------------------------------------------------------------------
    def input(name: String, cols: String*): DataFrame = step("read inputs") {
      val t = tables(name)
      val raw =
        if t("rowCount").num == 0 then
          spark.createDataFrame(java.util.List.of[Row](), StructType.fromDDL(t("schema").str))
        else spark.read.parquet(t("path").str)
      val df =
        (if cols.isEmpty then raw else raw.select(cols.map(col)*)).persist(StorageLevel.DISK_ONLY)
      val n = df.count()
      require(
        n == t("rowCount").num.toLong,
        s"$name: $n rows, the load manifest says ${t("rowCount")}"
      )
      jr.recordInput(t("path").str, true, n)
      df
    }
    def pathInput(rel: String): DataFrame = step("read inputs") {
      val p = new Path(pathDir, rel).toString
      val df = spark.read.parquet(p).persist(StorageLevel.DISK_ONLY)
      jr.recordInput(p, true, df.count())
      df
    }
    def ref(names: String*) = names.map(n => s"$n@${tables(n)("runId").str}").mkString(",")
    val pathRef =
      s"paths/path-evidence@${pm("runId").str},summaries/path-summaries@${pm("runId").str}"

    val researchers = input("canonical/researchers")
    val works = input("canonical/works")
    val authored = input("canonical/authored")
    val venues = input("canonical/venues")
    val institutions = input("canonical/institutions")
    val topics = input("canonical/topics")
    val workTopics = input("canonical/work-topics")
    val researcherInstitutions = input("canonical/researcher-institutions")
    val coauthorEdges = input("graph/coauthor-edges")
    val metrics = input("analytics/researcher-metrics")
    val communities = input("analytics/communities")
    val memberships = input("analytics/researcher-communities")
    val changes = input("temporal/researcher-changes")
    val summaries = input("summaries/semantic-summaries")
    val pathEvidence = pathInput("paths/path-evidence")
    val pathSummaries = pathInput("summaries/path-summaries")

    // ---- Publishing -----------------------------------------------------------------------------
    val entries = mutable.ArrayBuffer[Entry]()
    val rowCounts = mutable.LinkedHashMap[String, Long]()

    /** Streams one temp part (with an optional header line) to `key`, hashing it. */
    def copy(part: Path, key: String, header: Option[String], sourceRef: String): Entry =
      val md = MessageDigest.getInstance("SHA-256")
      val out = sfs.create(new Path(root, key), false)
      var bytes = 0L
      def put(b: Array[Byte], n: Int): Unit =
        out.write(b, 0, n); md.update(b, 0, n); bytes += n
      try
        header.foreach { h =>
          val hb = (h + "\n").getBytes(UTF_8); put(hb, hb.length)
        }
        val in = jr.ofs.open(part)
        try
          val buf = new Array[Byte](1 << 16)
          var n = in.read(buf)
          while n >= 0 do
            if n > 0 then put(buf, n)
            n = in.read(buf)
        finally in.close()
      finally out.close()
      Entry(key, "sha256:" + hex(md.digest()), bytes, sourceRef)

    def tempDir(name: String) = new Path(jr.runDir, s"tmp/$name")
    def partsOf(dir: Path): Seq[Path] =
      jr.ofs
        .listStatus(dir)
        .toSeq
        .map(_.getPath)
        .filter(_.getName.startsWith("part-"))
        .sortBy(_.getName)

    /** Writes rows (k, line) as `<keyPrefix>/part-NNNNN.<ext>`: hash-partitioned on k into `parts`
      * files, sorted by k within each, so the same input gives the same bytes.
      */
    def publish(
        name: String,
        lines: DataFrame,
        parts: Int,
        keyPrefix: String,
        ext: String,
        header: Option[String],
        sourceRef: String
    ): Seq[Entry] = step(s"publish $name") {
      val tmp = tempDir(name)
      val df = lines
        .repartition(parts, col("k"))
        .sortWithinPartitions("k")
        .select("line")
        .persist(StorageLevel.DISK_ONLY)
      rowCounts(name) = df.count()
      df.write.text(tmp.toString)
      df.unpersist()
      val nonEmpty = partsOf(tmp).filter(p => jr.ofs.getFileStatus(p).getLen > 0)
      // A family with no rows still gets one (header-only) file.
      val chosen = if nonEmpty.isEmpty then partsOf(tmp).take(1) else nonEmpty
      val written = chosen.zipWithIndex.map((p, i) =>
        copy(p, f"$keyPrefix/part-$i%05d.$ext", header, sourceRef)
      )
      jr.ofs.delete(tmp, true) // this run's own temp files
      entries ++= written
      jr.checkDisk(s"after publishing $name")
      written
    }
    def partsFor(rows: Long) = math.max(1, math.ceil(rows.toDouble / RowsPerPart).toInt)

    import StoreFiles.*

    // ---- Neptune vertices ----------------------------------------------------------------------
    def vertices(
        name: String,
        label: String,
        df: DataFrame,
        id: Column,
        props: Seq[CsvCol],
        src: String
    ) =
      val cols = Seq(system(":ID", id), system(":LABEL", lit(label))) ++ props ++
        provenanceCols(name, edge = false)
      publish(
        s"neptune-vertices-$name",
        df.select(id.as("k"), csvLine(cols).as("line")),
        partsFor(tables(src)("rowCount").num.toLong),
        s"$neptuneBase/vertices/$name",
        "csv",
        Some(header(cols)),
        ref(src)
      )
    def edges(
        name: String,
        edgeType: String,
        df: DataFrame,
        id: Column,
        from: Column,
        to: Column,
        props: Seq[CsvCol],
        src: String
    ) =
      val cols = Seq(
        system(":ID", id),
        system(":START_ID", from),
        system(":END_ID", to),
        system(":TYPE", lit(edgeType))
      ) ++ props ++ provenanceCols(name, edge = true)
      publish(
        s"neptune-edges-$name",
        df.select(id.as("k"), csvLine(cols).as("line")),
        partsFor(tables(src)("rowCount").num.toLong),
        s"$neptuneBase/edges/$name",
        "csv",
        Some(header(cols)),
        ref(src)
      )
    val policy = col("provenance.policyVersion")
    val windowProps = Seq(
      str("window", col("window")),
      int("windowStartYear", col("windowStartYear")),
      int("windowEndYear", col("windowEndYear"))
    )
    step("neptune") {
      vertices(
        "researcher",
        "Researcher",
        researchers,
        col("researcherId"),
        Seq(
          str("displayName", col("displayName")),
          str("orcid", col("orcid")),
          long("mentionCount", col("mentionCount")),
          bool("dblpAnchored", col("dblpAnchored")),
          str("assignedTier", col("assignedTier")),
          dbl("identityConfidence", col("identityConfidence"))
        ),
        "canonical/researchers"
      )
      vertices(
        "work",
        "Work",
        works,
        col("workId"),
        Seq(
          str("title", col("title")),
          int("year", col("year")),
          str("workType", col("workType")),
          str("doi", col("doi")),
          bool("dblpAnchored", col("dblpAnchored"))
        ),
        "canonical/works"
      )
      vertices(
        "venue",
        "Venue",
        venues,
        col("venueId"),
        Seq(str("name", col("name")), str("venueType", col("venueType"))),
        "canonical/venues"
      )
      vertices(
        "institution",
        "Institution",
        institutions,
        col("institutionId"),
        Seq(str("name", col("name")), str("countryCode", col("countryCode"))),
        "canonical/institutions"
      )
      vertices(
        "topic",
        "Topic",
        topics,
        col("topicId"),
        Seq(str("name", col("name")), str("subfield", col("subfield")), str("field", col("field"))),
        "canonical/topics"
      )
      vertices(
        "community",
        "Community",
        communities,
        col("communityId"),
        windowProps ++ Seq(
          long("communitySize", col("communitySize")),
          long("internalEdges", col("internalEdges")),
          long("crossCommunityEdges", col("crossCommunityEdges")),
          str("communityLabel", col("communityLabel"))
        ),
        "analytics/communities"
      )

      // ---- Neptune edges -----------------------------------------------------------------------
      edges(
        "authored",
        "AUTHORED",
        authored,
        graphEdgeId("AUTHORED", col("researcherId"), col("workId"), policy),
        col("researcherId"),
        col("workId"),
        Seq(int("position", col("position")), int("year", col("year"))),
        "canonical/authored"
      )
      val heavy = col("jointPublicationCount") > InlineEvidenceMax
      val evidenceKey = concat(
        lit(s"$evidenceBase/window="),
        col("window"),
        lit("/"),
        col("edgeId"),
        lit(".json")
      )
      edges(
        "coauthored-with",
        "COAUTHORED_WITH",
        coauthorEdges,
        col("edgeId"),
        col("researcherA"),
        col("researcherB"),
        windowProps ++ Seq(
          long("jointPublicationCount", col("jointPublicationCount")),
          int("firstCollaborationYear", col("firstCollaborationYear")),
          int("lastCollaborationYear", col("lastCollaborationYear")),
          str(
            "supportingWorkIds",
            when(!heavy, array_join(array_sort(col("representativeWorkIds")), " "))
          ),
          str("supportingWorkIdsRef", when(heavy, evidenceKey))
        ),
        "graph/coauthor-edges"
      )
      edges(
        "published-in",
        "PUBLISHED_IN",
        works.where(col("venueId").isNotNull),
        graphEdgeId("PUBLISHED_IN", col("workId"), col("venueId"), policy),
        col("workId"),
        col("venueId"),
        Nil,
        "canonical/works"
      )
      edges(
        "affiliated-with",
        "AFFILIATED_WITH",
        researcherInstitutions,
        graphEdgeId("AFFILIATED_WITH", col("researcherId"), col("institutionId"), policy),
        col("researcherId"),
        col("institutionId"),
        Seq(str("via", array_join(array_sort(col("via")), " "))),
        "canonical/researcher-institutions"
      )
      edges(
        "has-topic",
        "HAS_TOPIC",
        workTopics,
        graphEdgeId("HAS_TOPIC", col("workId"), col("topicId"), policy),
        col("workId"),
        col("topicId"),
        Nil,
        "canonical/work-topics"
      )
      edges(
        "member-of",
        "MEMBER_OF",
        memberships,
        graphEdgeId("MEMBER_OF", col("researcherId"), col("communityId"), policy),
        col("researcherId"),
        col("communityId"),
        windowProps,
        "analytics/researcher-communities"
      )

      // ---- COAUTHORED_WITH evidence objects (one per heavy edge; 574 in Phase 6) ---------------
      val tmp = tempDir("coauthor-evidence")
      val ev = coauthorEdges
        .where(heavy)
        .select(
          col("edgeId").as("k"),
          concat(
            evidenceKey,
            lit("\t"),
            to_json(
              struct(
                lit(EvidenceSchema).as("schemaVersion"),
                col("edgeId"),
                col("window"),
                array_sort(col("representativeWorkIds")).as("workIds"),
                col("provenance.runId").as("runId"),
                col("provenance.snapshot").as("snapshot")
              )
            )
          ).as("line")
        )
        .repartition(1, col("k"))
        .sortWithinPartitions("k")
        .select("line")
      ev.write.text(tmp.toString)
      var objects = 0L
      partsOf(tmp).foreach { p =>
        val in = new BufferedReader(new InputStreamReader(jr.ofs.open(p), UTF_8))
        try
          var l = in.readLine()
          while l != null do
            val tab = l.indexOf('\t')
            val (key, body) = (l.substring(0, tab), l.substring(tab + 1).getBytes(UTF_8))
            val out = sfs.create(new Path(root, key), false)
            try out.write(body)
            finally out.close()
            val sha = "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(body))
            entries += Entry(key, sha, body.length.toLong, ref("graph/coauthor-edges"))
            objects += 1
            l = in.readLine()
        finally in.close()
      }
      jr.ofs.delete(tmp, true)
      rowCounts("coauthor-evidence") = objects
    }

    // ---- Rankings (ruling 21) and DynamoDB items ------------------------------------------------
    val rankings = jr.write(
      "rankings/researcher-rankings",
      StoreFiles
        .rankings(metrics, memberships)
        .join(CoauthorGraph.windows(spark, windows), "window")
        .withColumn(
          "provenance",
          ctx.provenance(concat_ws("|", col("metric"), col("window"), col("rank")), graphPolicy)
        ),
      Seq("dynamodb"),
      1
    )
    val prov = col("provenance")
    def provAttrs(schema: String, p: Column = prov) = Seq(
      "runId" -> strAttr(p.getField("runId")),
      "snapshot" -> strAttr(p.getField("snapshot")),
      "schemaVersion" -> strAttr(lit(schema)),
      "policyVersion" -> strAttr(p.getField("policyVersion"))
    )
    val tooLong = metrics.where(size(col("maximumTeamWorkIds")) > MaxInlineIds).count()
    // shortcut: no pointer objects for maximumTeamWorkIds; Phase 6 lists hold at most 17 IDs, so
    // a list over 50 stops the run. Add a STORAGE.md key layout for them if that ever changes.
    require(tooLong == 0, s"$tooLong maximumTeamWorkIds lists exceed $MaxInlineIds IDs")
    val windowItems = metrics
      .join(
        memberships.select(
          col("window"),
          col("researcherId"),
          col("communityId"),
          col("communitySize"),
          col("crossCommunityDegree"),
          col("communitiesConnected"),
          col("participationCoefficient"),
          col("provenance.runId").as("communityRunId")
        ),
        Seq("window", "researcherId"),
        "left"
      )
    val metricCols = Seq(
      "windowStartYear",
      "windowEndYear",
      "publicationCount",
      "distinctCoauthorCount",
      "totalCoauthorOccurrences",
      "averageCoauthorsPerPaper",
      "maximumCoauthorsOnOnePaper",
      "weightedDegree",
      "componentId",
      "componentSize",
      "coreness",
      "graphDegree",
      "maximumTeamWorkIds",
      "communityId",
      "communitySize",
      "crossCommunityDegree",
      "communitiesConnected",
      "participationCoefficient",
      "communityRunId"
    )
    val windowLines = windowItems.select(
      concat_ws("|", col("researcherId"), col("window")).as("k"),
      item(
        Seq(
          "researcherId" -> strAttr(col("researcherId")),
          "metricKey" -> strAttr(concat(lit("WINDOW#"), col("window"))),
          "window" -> strAttr(col("window"))
        ) ++ metricCols.map(c => c -> attr(col(c), windowItems.schema(c).dataType)) ++
          provAttrs(WindowItemSchema)
      ).as("line")
    )
    val changeKey = concat(lit("CHANGE#"), col("fromWindow"), lit(">"), col("toWindow"))
    val changeLines = changes.select(
      concat_ws("|", col("researcherId"), changeKey).as("k"),
      item(
        Seq("researcherId" -> strAttr(col("researcherId")), "metricKey" -> strAttr(changeKey)) ++
          attrsOf(changes, Set("researcherId", "provenance", "schemaVersion")) ++
          provAttrs(ChangeItemSchema)
      ).as("line")
    )
    val rankingLines = rankings
      .groupBy("metric", "window", "windowStartYear", "windowEndYear")
      .agg(
        array_sort(collect_list(struct(col("rank"), col("researcherId"), col("valueText")))).as("e")
      )
      .select(
        concat_ws("|", col("metric"), col("window")).as("k"),
        item(
          Seq(
            "researcherId" -> strAttr(concat(lit("RANKING#"), col("metric"))),
            "metricKey" -> strAttr(concat(lit("TOP#"), col("window"))),
            "metric" -> strAttr(col("metric")),
            "window" -> strAttr(col("window")),
            "windowStartYear" -> attr(
              col("windowStartYear"),
              org.apache.spark.sql.types.IntegerType
            ),
            "windowEndYear" -> attr(col("windowEndYear"), org.apache.spark.sql.types.IntegerType),
            "entries" -> struct(
              transform(
                col("e"),
                x =>
                  struct(
                    struct(
                      struct(x.getField("rank").cast("string").as("N")).as("rank"),
                      struct(x.getField("researcherId").as("S")).as("researcherId"),
                      struct(x.getField("valueText").as("N")).as("value")
                    ).as("M")
                  )
              ).as("L")
            ),
            "runId" -> strAttr(lit(runId)),
            "snapshot" -> strAttr(lit(snapshot)),
            "schemaVersion" -> strAttr(lit(RankingSchema)),
            "policyVersion" -> strAttr(lit(graphPolicy)),
            "metricRunIds" -> strAttr(
              lit(
                s"${tables("analytics/researcher-metrics")("runId").str},${tables("analytics/researcher-communities")("runId").str}"
              )
            )
          )
        ).as("line")
      )
    step("dynamodb") {
      publish(
        "dynamodb-ResearcherMetricCache",
        windowLines.union(changeLines).union(rankingLines),
        partsFor(
          tables("analytics/researcher-metrics")("rowCount").num.toLong +
            tables("temporal/researcher-changes")("rowCount").num.toLong
        ),
        s"$dynamoBase/ResearcherMetricCache",
        "jsonl",
        None,
        ref(
          "analytics/researcher-metrics",
          "analytics/researcher-communities",
          "temporal/researcher-changes"
        )
      )
      val links = entityLinks(researchers)
      publish(
        "dynamodb-EntityLinkCache",
        links.select(
          concat_ws("|", col("nameKey"), col("researcherId")).as("k"),
          item(
            Seq(
              "nameKey" -> strAttr(col("nameKey")),
              "researcherId" -> strAttr(col("researcherId")),
              "displayName" -> strAttr(col("displayName")),
              "matchedName" -> strAttr(col("matchedName")),
              "identityConfidence" -> attr(
                col("identityConfidence"),
                links.schema("identityConfidence").dataType
              ),
              "mentionCount" -> attr(col("mentionCount"), links.schema("mentionCount").dataType),
              "dblpAnchored" -> attr(col("dblpAnchored"), links.schema("dblpAnchored").dataType),
              "orcid" -> strAttr(col("orcid"))
            ) ++ provAttrs(EntityLinkSchema)
          ).as("line")
        ),
        partsFor(tables("canonical/researchers")("rowCount").num.toLong),
        s"$dynamoBase/EntityLinkCache",
        "jsonl",
        None,
        ref("canonical/researchers")
      )
    }

    // ---- Embedding candidates (selection-v1) ----------------------------------------------------
    val first = windows.head
    def index(doc: String) = doc match
      case Summaries.ProfileDoc   => idx("researchers")
      case Summaries.CommunityDoc => idx("communities")
      case Summaries.PathDoc      => idx("paths")
      case Selection.PaperDoc     => idx("abstracts")
      case _                      => idx("topic-institutions")
    val javaTrim = regexp_replace(col("title"), "^[\\x00-\\x20]+|[\\x00-\\x20]+$", "")
    val eligibleWorks = works
      .select(col("workId"), javaTrim.as("t"), col("year"))
      .where(length(col("t")) > 0 && col("year").between(first.startYear, first.endYear))
      .join(
        authored.where(array_contains(col("sourceDatasets"), "DBLP")).select("workId").distinct(),
        "workId",
        "left_semi"
      )
      .select("workId")
    val selected = step("selection") {
      Selection.select(
        summaries,
        pathSummaries,
        rankings.where(col("window") === first.name).select("researcherId"),
        eligibleWorks,
        windows.map(_.name),
        index
      )
    }
    // Paper summaries (paper-facts-v1) for the selected works only.
    val sel = selected.papers.persist()
    val names = researchers.select("researcherId", "displayName")
    val authorsTop = Summaries.topN(
      authored
        .select("workId", "researcherId", "position")
        .join(sel.select("workId"), "workId")
        .join(names, "researcherId"),
      Seq("workId"),
      Seq(col("position"), col("researcherId")),
      10,
      struct(
        col("researcherId").as("id"),
        coalesce(col("displayName"), col("researcherId")).as("name"),
        col("position")
      ),
      "authors"
    )
    val authorCount = authored
      .join(sel.select("workId"), "workId")
      .groupBy("workId")
      .agg(countDistinct("researcherId").as("authorCount"))
    val topicsTop = Summaries.topN(
      workTopics
        .select("workId", "topicId")
        .join(sel.select("workId"), "workId")
        .join(topics.select("topicId", "name"), "topicId"),
      Seq("workId"),
      Seq(col("name"), col("topicId")),
      5,
      struct(col("topicId").as("id"), col("name")),
      "topics"
    )
    val paperText = javaTrim
    val paperRows = sel
      .join(works.select("workId", "title", "year", "workType", "venueId"), "workId")
      .join(venues.select(col("venueId"), col("name").as("venueName")), Seq("venueId"), "left")
      .join(authorCount, Seq("workId"), "left")
      .join(authorsTop, Seq("workId"), "left")
      .join(topicsTop, Seq("workId"), "left")
      .select(
        lit(Selection.PaperDoc).as("documentType"),
        col("workId").as("subjectId"),
        lit(first.name).as("window"),
        paperText.as("text"),
        to_json(
          struct(
            col("workId"),
            paperText.as("title"),
            col("year"),
            col("workType"),
            col("venueId"),
            col("venueName"),
            col("authorCount"),
            col("authors"),
            col("topics")
          )
        ).as("facts"),
        col("selectionRank")
      )
      .withColumn("textHash", concat(lit("sha256:"), sha2(col("text"), 256)))
      .withColumn("templateVersion", lit(Selection.PaperTemplate))
      .withColumn(
        "summaryId",
        concat(
          lit("chunk-"),
          Ids.hash(
            lit("chunk"),
            concat_ws("|", col("subjectId"), col("window")),
            lit(Selection.PaperTemplate),
            lit(Selection.PaperDoc),
            lit(""),
            col("textHash")
          )
        )
      )
      .withColumn(
        "provenance",
        struct(
          lit(scholarchain.core.SchemaVersions.Provenance).as("schemaVersion"),
          lit("DERIVED").as("sourceDataset"),
          col("subjectId").as("sourceRecordId"),
          lit(snapshot).as("snapshot"),
          lit(runId).as("runId"),
          lit(Selection.PaperTemplate).as("policyVersion"),
          lit(null).cast("string").as("modelVersion")
        )
      )
    val candidateCols = Seq(
      "documentType",
      "subjectId",
      "window",
      "text",
      "textHash",
      "templateVersion",
      "facts",
      "summaryId",
      "provenance",
      "selectionRank"
    )
    val byIndex = Seq(
      idx("paths") -> selected.paths,
      idx("researchers") -> selected.profiles,
      idx("communities") -> selected.communities,
      idx("topic-institutions") -> selected.topics,
      idx("abstracts") -> Selection.parsed(paperRows)
    )
    def candidateLine(index: String, df: DataFrame): DataFrame =
      val c = Selection.metadataLists(df.select((candidateCols :+ "f").map(col)*))
      val textRef = concat(
        lit(s"semantic-context/text/snapshot=$snapshot/run=$runId/$index/"),
        col("summaryId"),
        lit(".txt")
      )
      val base = to_json(
        struct(
          lit("embedding-candidate-v1").as("schemaVersion"),
          lit(index).as("vectorIndex"),
          col("summaryId").as("vectorKey"),
          col("documentType"),
          col("entityIds"),
          col("window"),
          col("topicIds"),
          col("institutionIds"),
          col("communityId"),
          col("text"),
          col("textHash"),
          textRef.as("textRef"),
          col("templateVersion"),
          lit(Selection.PolicyVersion).as("selectionPolicyVersion"),
          col("selectionRank"),
          col("provenance")
        ),
        Map("ignoreNullFields" -> "false")
      )
      // The facts JSON object goes in as is (not as a string): `{...base, "facts": {...}}`.
      c.select(
        lpad(col("selectionRank").cast("string"), 8, "0").as("k"),
        concat(base.substr(lit(1), length(base) - 1), lit(",\"facts\":"), col("facts"), lit("}"))
          .as("line")
      )
    val candidateCounts = step("candidates") {
      byIndex.map { (ix, df) =>
        publish(
          s"candidates-$ix",
          candidateLine(ix, df),
          1,
          s"$candidatesBase/$ix",
          "jsonl",
          None,
          if ix == idx("paths") then pathRef
          else if ix == idx("abstracts") then
            ref(
              "canonical/works",
              "canonical/authored",
              "canonical/researchers",
              "canonical/venues",
              "canonical/work-topics",
              "canonical/topics"
            )
          else ref("summaries/semantic-summaries")
        )
        ix -> rowCounts(s"candidates-$ix")
      }
    }

    // ---- ArtifactRegistry (one RUN# item per artifact of this run) -----------------------------
    def under(b: String) = entries.filter(_.key.startsWith(b + "/")).toSeq
    val artifacts = Seq(
      (
        "neptune/graph",
        neptuneBase,
        under(neptuneBase),
        rowCounts.collect { case (k, n) if k.startsWith("neptune-") => n }.sum
      ),
      (
        "dynamodb/ResearcherMetricCache",
        dynamoBase,
        under(s"$dynamoBase/ResearcherMetricCache"),
        rowCounts("dynamodb-ResearcherMetricCache")
      ),
      (
        "dynamodb/EntityLinkCache",
        dynamoBase,
        under(s"$dynamoBase/EntityLinkCache"),
        rowCounts("dynamodb-EntityLinkCache")
      ),
      ("s3/coauthor-evidence", evidenceBase, under(evidenceBase), rowCounts("coauthor-evidence"))
    ) ++ candidateCounts.map((ix, n) =>
      (s"candidates/$ix", candidatesBase, under(s"$candidatesBase/$ix"), n)
    )
    def S(v: String) = ujson.Obj("S" -> v)
    val registry = artifacts
      .map { (key, b, es, rows) =>
        ujson.write(
          ujson.Obj(
            "Item" -> ujson.Obj(
              "artifactKey" -> S(key),
              "versionKey" -> S(s"RUN#$runId"),
              "runId" -> S(runId),
              "snapshot" -> S(snapshot),
              "schemaVersion" -> S(ArtifactSchema),
              "stage" -> S(Stage),
              "keyPrefix" -> S(b),
              "objectCount" -> ujson.Obj("N" -> es.size.toString),
              "manifestKey" -> S(manifestKey),
              "contentHash" -> S(contentHash(b, es)),
              "rowCount" -> ujson.Obj("N" -> rows.toString)
            )
          )
        )
      }
      .mkString("", "\n", "\n")
      .getBytes(UTF_8)
    val registryKey = s"$dynamoBase/ArtifactRegistry/part-00000.jsonl"
    val out = sfs.create(new Path(root, registryKey), false)
    try out.write(registry)
    finally out.close()
    entries += Entry(
      registryKey,
      "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(registry)),
      registry.length.toLong,
      s"store-conversion@$runId"
    )
    rowCounts("dynamodb-ArtifactRegistry") = artifacts.size.toLong

    // ---- Store run manifest, written last --------------------------------------------------------
    val storeManifest = ujson.Obj(
      "schemaVersion" -> StoreManifestSchema,
      "stage" -> Stage,
      "runId" -> runId,
      "snapshot" -> snapshot,
      "entries" -> ujson.Arr.from(
        entries
          .sortBy(_.key)
          .map(e =>
            ujson.Obj(
              "key" -> e.key,
              "sha256" -> e.sha256,
              "bytes" -> e.bytes.toDouble,
              "sourceRef" -> e.sourceRef
            )
          )
      )
    )
    val mout = sfs.create(new Path(root, manifestKey), false)
    try mout.write(ujson.write(storeManifest, indent = 2).getBytes(UTF_8))
    finally mout.close()
    sfs.close()

    val counts = step("report") {
      ujson.Obj(
        "rowsPerFamily" -> ujson.Obj.from(
          rowCounts.toSeq.map((k, n) => k -> ujson.Num(n.toDouble))
        ),
        "objects" -> entries.size,
        "objectBytes" -> entries.map(_.bytes).sum.toDouble,
        "filesPerFamily" -> ujson.Obj.from(
          Seq(neptuneBase, dynamoBase, evidenceBase, candidatesBase).map(b =>
            b -> ujson.Num(under(b).size.toDouble)
          )
        ),
        "candidatesPerIndex" -> ujson.Obj.from(
          candidateCounts.map((k, n) => k -> ujson.Num(n.toDouble))
        ),
        "selectionTiers" -> ujson.Obj(
          "profiles" -> JobRun.rows(selected.profiles.groupBy("tier").count().orderBy("tier")),
          "communities" -> JobRun.rows(
            selected.communities.groupBy("window", "tier").count().orderBy("window", "tier")
          ),
          "papers" -> JobRun.rows(sel.groupBy("tier").count().orderBy("tier"))
        ),
        "rankingsTop1" -> JobRun.rows(
          rankings
            .where(col("rank") === 1)
            .select("metric", "window", "researcherId", "valueText")
            .orderBy("metric", "window")
        ),
        "rankingItems" -> rankings.select("metric", "window").distinct().count().toDouble,
        "storeManifestKey" -> manifestKey
      )
    }
    val manifest = jr.finish(head, "storeManifestKey" -> manifestKey, "counts" -> counts)
    jr.ofs.delete(new Path(jr.runDir, "tmp"), true)
    Seq(
      researchers,
      works,
      authored,
      venues,
      institutions,
      topics,
      workTopics,
      researcherInstitutions,
      coauthorEdges,
      metrics,
      communities,
      memberships,
      changes,
      summaries,
      pathEvidence,
      pathSummaries,
      sel
    ).foreach(_.unpersist())
    selected.paths.unpersist()
    selected.profiles.unpersist()
    RunResult(runId, jr.attempt, jr.runDir.toString, manifest, manifestKey)
