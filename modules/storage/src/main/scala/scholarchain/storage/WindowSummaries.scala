package scholarchain.storage

import scholarchain.core.config.TimeWindow
import scholarchain.core.{IdFormat, Provenance, SchemaVersions, SourceType}
import upickle.default.{ReadWriter, read, write}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** The Spark runs a window summary was computed from (`sourceRuns` of the Spark contract). */
final case class WindowSummarySourceRuns(
    temporalSummaryExportRunId: String,
    communityPathRunId: String,
    researcherAnalyticsRunId: String,
    canonicalGraphRunId: String,
    inputHadoopRunId: String,
    matchPolicyVersion: String
) derives ReadWriter

/** One row of the Spark `corpus/window-summary` table (`spark-corpus-window-summary-v1`,
  * modules/spark/JOBS.md, "CorpusWindowSummaryJob"), field for field and in column order. A
  * storage-local mirror of that contract; the Spark contract is authoritative. Every value is for
  * `window` only. IDs and fractions are None only when researchers = 0.
  */
final case class WindowSummary(
    window: String,
    windowStartYear: Int,
    windowEndYear: Int,
    researchers: Long,
    coauthorEdges: Long,
    sumJointPublicationCount: Long,
    components: Long,
    largestComponentSize: Long,
    singletonResearchers: Long,
    communities: Long,
    largestCommunitySize: Long,
    singletonCommunities: Long,
    communitiesAtLeastMinSize: Long,
    largestComponentId: Option[String],
    largestCommunityId: Option[String],
    years: Int,
    largestComponentFraction: Option[Double],
    largestCommunityFraction: Option[Double],
    communityMinSize: Int,
    communityConverged: Boolean,
    communityRounds: Int,
    nodeBasis: String,
    sourceRuns: WindowSummarySourceRuns,
    provenance: Provenance,
    schemaVersion: String
) derives ReadWriter

/** Read side of the corpus window summaries (STORAGE.md, "Window summaries"). */
trait WindowSummaryReadPort:
  /** The stored summary of one window, or None when the store has no row for that name. The caller
    * maps the benchmark alias `all` to the first configured window (`all-years`) first.
    */
  def windowSummary(window: String): Option[WindowSummary]

object WindowSummaries:
  val SchemaVersion = "spark-corpus-window-summary-v1"
  val KeyPrefix = "curated/window-summary/"
  val SparkStage = "spark-corpus-window-summary"
  val SparkTable = "corpus/window-summary"

  /** The Spark table schema the store object mirrors, exactly as the Spark run manifest prints it.
    */
  val SparkSchema =
    "window STRING,windowStartYear INT,windowEndYear INT,researchers BIGINT,coauthorEdges BIGINT," +
      "sumJointPublicationCount BIGINT,components BIGINT,largestComponentSize BIGINT," +
      "singletonResearchers BIGINT,communities BIGINT,largestCommunitySize BIGINT," +
      "singletonCommunities BIGINT,communitiesAtLeastMinSize BIGINT,largestComponentId STRING," +
      "largestCommunityId STRING,years INT,largestComponentFraction DOUBLE," +
      "largestCommunityFraction DOUBLE,communityMinSize INT,communityConverged BOOLEAN," +
      "communityRounds INT,nodeBasis STRING,sourceRuns STRUCT<temporalSummaryExportRunId: STRING, " +
      "communityPathRunId: STRING, researcherAnalyticsRunId: STRING, canonicalGraphRunId: STRING, " +
      "inputHadoopRunId: STRING, matchPolicyVersion: STRING>,provenance STRUCT<schemaVersion: " +
      "STRING, sourceDataset: STRING, sourceRecordId: STRING, snapshot: STRING, runId: STRING, " +
      "policyVersion: STRING, modelVersion: STRING>,schemaVersion STRING"

  /** Top-level fields of a line: exactly the Spark columns, so a new or missing column is refused.
    */
  val Fields: Set[String] = SparkSchema
    .replaceAll("<[^>]*>", "")
    .split(',')
    .map(_.trim.takeWhile(_ != ' '))
    .toSet

  private val NullableFields = Vector(
    "largestComponentId",
    "largestCommunityId",
    "largestComponentFraction",
    "largestCommunityFraction"
  )
  private val ComponentId = "comp-[0-9a-f]{32}".r

  /** Rule violations of one row; empty means valid. */
  def problems(s: WindowSummary, windows: Map[String, TimeWindow]): List[String] =
    def check(ok: Boolean, msg: => String) = if ok then Nil else List(msg)
    def fraction(size: Long, f: Option[Double], name: String) =
      check(
        f.exists(x => math.abs(x - size.toDouble / s.researchers.toDouble) <= 1e-12),
        s"$name is not its size / researchers"
      )
    val p = s.provenance
    val r = s.sourceRuns
    val counts = Vector(
      s.researchers,
      s.coauthorEdges,
      s.sumJointPublicationCount,
      s.components,
      s.largestComponentSize,
      s.singletonResearchers,
      s.communities,
      s.largestCommunitySize,
      s.singletonCommunities,
      s.communitiesAtLeastMinSize
    )
    check(s.schemaVersion == SchemaVersion, s"schemaVersion ${s.schemaVersion}") ++
      (windows.get(s.window) match
        case None    => List(s"unknown window ${s.window}")
        case Some(w) =>
          check(
            s.windowStartYear == w.startYear && s.windowEndYear == w.endYear,
            s"window years differ from ${w.name}"
          )) ++
      check(s.years == s.windowEndYear - s.windowStartYear + 1, "years is not end - start + 1") ++
      check(counts.forall(_ >= 0), "a count is negative") ++
      check(
        s.components <= s.researchers && s.communities <= s.researchers &&
          s.singletonResearchers <= s.components && s.singletonCommunities <= s.communities &&
          s.communitiesAtLeastMinSize <= s.communities &&
          s.coauthorEdges <= s.sumJointPublicationCount,
        "counts are inconsistent"
      ) ++
      (if s.researchers == 0 then
         check(
           s.largestComponentId.isEmpty && s.largestCommunityId.isEmpty &&
             s.largestComponentFraction.isEmpty && s.largestCommunityFraction.isEmpty &&
             s.largestComponentSize == 0 && s.largestCommunitySize == 0,
           "an empty window has a largest component or community"
         )
       else
         check(s.largestComponentId.exists(ComponentId.matches), "largestComponentId") ++
           check(s.largestCommunityId.exists(IdFormat.isCommunityId), "largestCommunityId") ++
           check(
             s.largestComponentSize >= 1 && s.largestComponentSize <= s.researchers &&
               s.largestCommunitySize >= 1 && s.largestCommunitySize <= s.researchers,
             "a largest size is outside 1 to researchers"
           ) ++
           fraction(
             s.largestComponentSize,
             s.largestComponentFraction,
             "largestComponentFraction"
           ) ++
           fraction(
             s.largestCommunitySize,
             s.largestCommunityFraction,
             "largestCommunityFraction"
           )) ++
      check(s.communityMinSize >= 1 && s.communityRounds >= 0, "communityMinSize or rounds") ++
      check(s.nodeBasis.trim.nonEmpty, "nodeBasis is empty") ++
      check(
        Vector(
          r.temporalSummaryExportRunId,
          r.communityPathRunId,
          r.researcherAnalyticsRunId,
          r.canonicalGraphRunId,
          r.inputHadoopRunId
        ).forall(IdFormat.isRunId) && IdFormat.Version.matches(r.matchPolicyVersion),
        "sourceRuns"
      ) ++
      check(
        p.schemaVersion == SchemaVersions.Provenance,
        s"provenance.schemaVersion ${p.schemaVersion}"
      ) ++
      check(p.sourceDataset == SourceType.DERIVED, "provenance.sourceDataset is not DERIVED") ++
      check(p.sourceRecordId == s.window, "provenance.sourceRecordId is not the window") ++
      check(IdFormat.isRunId(p.runId), "provenance.runId") ++
      check(ProvenanceRules.Snapshot.matches(p.snapshot), "provenance.snapshot") ++
      check(p.policyVersion.exists(IdFormat.Version.matches), "provenance.policyVersion") ++
      check(p.modelVersion.isEmpty, "provenance.modelVersion must be null")

  /** The store object: one JSON line per row, ordered by start year, end year, window. */
  def render(rows: Seq[WindowSummary]): Array[Byte] =
    rows
      .sortBy(s => (s.windowStartYear, s.windowEndYear, s.window))
      .map(write(_) + "\n")
      .mkString
      .getBytes(UTF_8)

  /** Validates store-object lines (`source` names them in violations). Every line must be exactly
    * the Spark columns and pass `problems`; a window appears once; every configured window is
    * present; all rows come from one provenance run and snapshot. Any violation refuses all rows.
    */
  def parseRows(
      source: String,
      bytes: Array[Byte],
      windows: Seq[TimeWindow]
  ): Either[Vector[Violation], Vector[WindowSummary]] =
    val byWindow = ProvenanceRules.windowsByName(windows)
    val out = Vector.newBuilder[Violation]
    val rows = Vector.newBuilder[WindowSummary]
    new String(bytes, UTF_8).split('\n').iterator.zipWithIndex.foreach { (l, i) =>
      if l.trim.nonEmpty then
        Try(ujson.read(l)).toEither.left.map(e => s"not JSON: ${e.getMessage}").flatMap {
          case o: ujson.Obj if o.value.keySet == Fields =>
            Try(read[WindowSummary](o)).toEither.left.map(e => s"bad value: ${e.getMessage}")
          case o: ujson.Obj =>
            val extra = (o.value.keySet.toSet -- Fields).toVector.sorted.mkString(" ")
            val missing = (Fields -- o.value.keySet.toSet).toVector.sorted.mkString(" ")
            Left(s"fields differ from $SchemaVersion: extra [$extra], missing [$missing]")
          case _ => Left("line is not a JSON object")
        } match
          case Left(e)  => out += Violation(source, i + 1L, "MALFORMED", e.take(200))
          case Right(s) =>
            problems(s, byWindow).foreach(p => out += Violation(source, i + 1L, "BAD_ROW", p))
            rows += s
    }
    val got = rows.result()
    got.groupBy(_.window).toVector.sortBy(_._1).foreach { (w, xs) =>
      if xs.size > 1 then out += Violation(source, 0, "DUPLICATE_WINDOW", w)
    }
    windows.map(_.name).filterNot(got.map(_.window).toSet).foreach { w =>
      out += Violation(source, 0, "MISSING_WINDOW", w)
    }
    val runs = got.map(s => (s.provenance.runId, s.provenance.snapshot)).distinct
    if runs.size > 1 then out += Violation(source, 0, "PROVENANCE", s"rows from ${runs.size} runs")
    val found = out.result()
    Either.cond(found.isEmpty, got, found)

  /** parseRows for a store object, plus the key rules: a valid key under KeyPrefix whose snapshot
    * is the rows' snapshot.
    */
  def parse(
      key: String,
      bytes: Array[Byte],
      windows: Seq[TimeWindow]
  ): Either[Vector[Violation], Vector[WindowSummary]] =
    val keyIssues = (S3Layout.keyProblems(key) ++
      Option.when(!key.startsWith(KeyPrefix))(s"key is not under $KeyPrefix"))
      .map(Violation(key, 0, "BAD_KEY", _))
      .toVector
    parseRows(key, bytes, windows) match
      case Left(vs)   => Left(keyIssues ++ vs)
      case Right(got) =>
        val snapshotIssue = got.headOption
          .map(_.provenance.snapshot)
          .filterNot(snap => key.contains(s"/snapshot=$snap/"))
          .map(snap =>
            Violation(key, 0, "PROVENANCE", s"key snapshot differs from the rows' $snap")
          )
        val found = keyIssues ++ snapshotIssue
        Either.cond(found.isEmpty, got, found)

  /** Reads and validates the store object once, then answers from memory. The fake is a
    * FileObjectStore reader and the live adapter S3ObjectStore's reader (one GetObject). Both
    * stores are write-once, so the copy never goes stale.
    */
  def open(
      objects: ObjectReadPort,
      key: String,
      windows: Seq[TimeWindow]
  ): Either[Vector[Violation], WindowSummaryReadPort] =
    objects
      .get(key)
      .toRight(Vector(Violation(key, 0, "OBJECT_MISSING", "no window-summary object")))
      .flatMap(parse(key, _, windows))
      .map { rows =>
        val byName = rows.map(s => s.window -> s).toMap
        (window: String) => byName.get(window)
      }

  val SparkManifestSchema = "spark-run-manifest-v1"

  /** Store-object rows from a Spark CorpusWindowSummaryJob run manifest (`_manifest.json`). Its
    * `counts.windowSummary` holds every column except nodeBasis, sourceRuns and provenance. Those
    * come from the manifest's `settings.nodeBasis` and `sourceRuns`, and provenance is rebuilt as
    * the job writes it (spark Canonical.provenance: provenance-v1, DERIVED, the window, the run's
    * snapshot and runId, `settings.graphPolicy`, no model). The manifest must list the table with
    * SparkSchema, SchemaVersion, and its row count, and report no integrity violation.
    */
  // shortcut: storage has no Parquet reader, so it reads the manifest copy of the 3 rows; drop
  // this once spark-engineer writes the store object directly (RUNTIME.md F2).
  def fromSparkManifest(
      source: String,
      m: ujson.Value,
      windows: Seq[TimeWindow]
  ): Either[Vector[Violation], Vector[WindowSummary]] =
    def bad(code: String, e: String) = Vector(Violation(source, 0, code, e))
    Try {
      val rows = m("counts")("windowSummary").arr.toVector
      val tableOk = m("tables").arr.exists(t =>
        t("name").str == SparkTable && t("schemaVersion").str == SchemaVersion &&
          t("schema").str == SparkSchema && t("rowCount").num.toLong == rows.size
      )
      val header = Vector(
        Option.when(m("schemaVersion").str != SparkManifestSchema)("manifest schemaVersion"),
        Option.when(m("stage").str != SparkStage)(s"stage is not $SparkStage"),
        Option.when(!tableOk)(s"no $SparkTable table with $SchemaVersion, its schema and row count")
      ).flatten
      val violations = m("counts")("integrityViolations").arr.size
      if header.nonEmpty then Left(header.flatMap(bad("BAD_MANIFEST", _)))
      else if violations > 0 then Left(bad("INTEGRITY_VIOLATIONS", s"the run reports $violations"))
      else
        val runId = m("runId").str
        val snapshot = m("snapshot").str
        Right(rows.map { r =>
          val o = ujson.Obj.from(r.obj)
          NullableFields.foreach(k => if !o.value.contains(k) then o(k) = ujson.Null)
          o("nodeBasis") = m("settings")("nodeBasis").str
          o("sourceRuns") = m("sourceRuns")
          o("provenance") = ujson.Obj(
            "schemaVersion" -> SchemaVersions.Provenance,
            "sourceDataset" -> SourceType.DERIVED.toString,
            "sourceRecordId" -> o.value.getOrElse("window", ujson.Null),
            "snapshot" -> snapshot,
            "runId" -> runId,
            "policyVersion" -> m("settings")("graphPolicy").str,
            "modelVersion" -> ujson.Null
          )
          o.render()
        })
    }.toEither.left
      .map(e => bad("BAD_MANIFEST", s"unreadable manifest: ${e.getMessage}"))
      .flatMap(identity)
      .flatMap(lines => parseRows(source, lines.mkString("\n").getBytes(UTF_8), windows))
