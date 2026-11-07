package scholarchain.storage

import scholarchain.core.config.TimeWindow
import scholarchain.core.{CanonicalPair, EdgeType, IdFormat, NodeLabel, StableIds}
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** A typed property value of a vertex or edge. */
type Prop = String | Int | Long | Double | Boolean

/** openCypher bulk-load property types used by ScholarChain (header suffix in `header`). */
enum PropType(val header: String):
  case BoolT extends PropType("Bool")
  case IntT extends PropType("Int")
  case LongT extends PropType("Long")
  case DoubleT extends PropType("Double")
  case StringT extends PropType("String")

  /** Strict parse: the loader treats any Bool value other than "true" as false, and accepts NaN and
    * Infinity for doubles, so both are rejected here before they reach it.
    */
  def parse(s: String): Option[Prop] = this match
    case BoolT   => Option.when(s == "true" || s == "false")(s == "true")
    case IntT    => s.toIntOption
    case LongT   => s.toLongOption
    case DoubleT => s.toDoubleOption.filter(d => !d.isNaN && !d.isInfinite)
    case StringT => Some(s)

  def render(p: Prop): String = p.toString

final case class Column(name: String, tpe: PropType, required: Boolean)

enum ElementKind:
  case Vertex, Edge

/** A vertex (from and to empty) or an edge. label is the vertex label or the edge type. */
final case class GraphElement(
    id: String,
    label: String,
    from: String,
    to: String,
    props: Map[String, Prop]
)

/** One Neptune CSV file family: vertices/<name>/part-*.csv or edges/<name>/part-*.csv. */
final case class FileSpec(
    name: String,
    kind: ElementKind,
    label: String,
    columns: Vector[Column],
    endpoints: Option[(String, String)],
    idOk: String => Boolean,
    rowChecks: (GraphElement, Map[String, TimeWindow]) => List[(String, String)]
):
  val schemaVersion: String = s"neptune-$name-v1"
  private def str(n: String) = Column(n, PropType.StringT, required = true)
  val provenanceColumns: Vector[Column] =
    Vector(str("runId"), str("snapshot"), str("schemaVersion")) ++
      Option.when(kind == ElementKind.Edge)(str("policyVersion"))
  val propColumns: Vector[Column] = columns ++ provenanceColumns
  val systemColumns: Vector[String] =
    if kind == ElementKind.Vertex then Vector(":ID", ":LABEL")
    else Vector(":ID", ":START_ID", ":END_ID", ":TYPE")
  val header: Vector[String] = systemColumns ++ propColumns.map(c => s"${c.name}:${c.tpe.header}")
  def dir: String = if kind == ElementKind.Vertex then "vertices" else "edges"

/** The Neptune openCypher bulk-load contract (STORAGE.md, "Neptune"). */
object NeptuneContract:
  import ElementKind.*
  import PropType.*

  /** COAUTHORED_WITH lists at most this many supporting work IDs inline; longer lists go to an S3
    * evidence object.
    */
  val InlineEvidenceMax = 20
  val AffiliationVia = Set("MENTION", "ORCID_EMPLOYMENT")
  val EvidenceSchemaVersion = "coauthor-evidence-v1"

  /** ID of every edge except COAUTHORED_WITH: "ge-" + H("graph-edge", type, start, end, policy).
    * For core edge types this equals StableIds.graphEdgeId(type, start, end, policy, Nil).
    */
  def edgeId(edgeType: String, start: String, end: String, policyVersion: String): String =
    "ge-" + StableIds.hash("graph-edge", edgeType, start, end, policyVersion)

  private def col(n: String, t: PropType, req: Boolean = false) = Column(n, t, req)
  private def windowCols = Vector(
    col("window", StringT, true),
    col("windowStartYear", IntT, true),
    col("windowEndYear", IntT, true)
  )
  private val noChecks: (GraphElement, Map[String, TimeWindow]) => List[(String, String)] =
    (_, _) => Nil

  def windowIssues(e: GraphElement, windows: Map[String, TimeWindow]): List[(String, String)] =
    (e.props.get("window"), e.props.get("windowStartYear"), e.props.get("windowEndYear")) match
      case (Some(w: String), Some(s: Int), Some(t: Int)) =>
        windows.get(w) match
          case Some(tw) if tw.startYear == s && tw.endYear == t => Nil
          case Some(tw)                                         =>
            List("WINDOW_MISMATCH" -> s"$w is ${tw.startYear}-${tw.endYear}, row has $s-$t")
          case None => List("UNKNOWN_WINDOW" -> w)
      case _ => Nil

  private def geChecks(e: GraphElement, w: Map[String, TimeWindow]) =
    e.props.get("policyVersion") match
      case Some(p: String) if edgeId(e.label, e.from, e.to, p) != e.id =>
        List("EDGE_ID_MISMATCH" -> s"expected ${edgeId(e.label, e.from, e.to, p)}")
      case _ => Nil

  private def coauthorChecks(e: GraphElement, w: Map[String, TimeWindow]) =
    val p = e.props
    val ws = p.get("windowStartYear").collect { case i: Int => i }
    val we = p.get("windowEndYear").collect { case i: Int => i }
    val count = p.get("jointPublicationCount").collect { case l: Long => l }.getOrElse(0L)
    val first = p.get("firstCollaborationYear").collect { case i: Int => i }
    val last = p.get("lastCollaborationYear").collect { case i: Int => i }
    val window = p.get("window").collect { case s: String => s }.getOrElse("")
    val canonical =
      Option.unless(CanonicalPair.isCanonical(e.from, e.to))(
        "NON_CANONICAL_PAIR" -> s"${e.from} must sort before ${e.to}"
      )
    val id = for
      s <- ws; t <- we; pol <- p.get("policyVersion").collect { case v: String => v }
      expected = StableIds.coauthorEdgeId(e.from, e.to, s, t, pol)
      if expected != Right(e.id)
    yield "EDGE_ID_MISMATCH" -> s"expected ${expected.merge}"
    val countOk = Option.when(count < 1)("BAD_COUNT" -> s"jointPublicationCount $count")
    val years = for
      f <- first; l <- last; s <- ws; t <- we
      if !(s <= f && f <= l && l <= t)
    yield "BAD_YEARS" -> s"collaboration years $f-$l outside window $s-$t or reversed"
    val evidence =
      (p.get("supportingWorkIds"), p.get("supportingWorkIdsRef")) match
        case (Some(ids: String), None) =>
          val list = ids.split(' ').toVector
          List(
            Option.when(list.exists(id => !IdFormat.isWorkId(id)))(
              "BAD_EVIDENCE" -> "supportingWorkIds holds a value that is not a work ID"
            ),
            Option.when(list != list.distinct.sorted)(
              "BAD_EVIDENCE" -> "supportingWorkIds must be sorted and distinct"
            ),
            Option.when(list.size.toLong != count)(
              "EVIDENCE_COUNT_MISMATCH" -> s"${list.size} work IDs for jointPublicationCount $count"
            ),
            Option.when(list.size > InlineEvidenceMax)(
              "EVIDENCE_TOO_LONG" -> s"${list.size} inline work IDs; use supportingWorkIdsRef"
            )
          ).flatten
        case (None, Some(ref: String)) =>
          List(
            Option.when(
              S3Layout.keyProblems(ref).nonEmpty || !ref.startsWith("curated/coauthor-evidence/") ||
                !ref.endsWith(s"/window=$window/${e.id}.json")
            )("BAD_EVIDENCE_REF" -> ref),
            Option.when(count <= InlineEvidenceMax)(
              "BAD_EVIDENCE_REF" -> s"lists of at most $InlineEvidenceMax work IDs are inline"
            )
          ).flatten
        case (None, None) =>
          List("MISSING_EVIDENCE" -> "no supportingWorkIds or supportingWorkIdsRef")
        case _ => List("AMBIGUOUS_EVIDENCE" -> "both supportingWorkIds and supportingWorkIdsRef")
    (canonical ++ id ++ countOk ++ years).toList ++ evidence ++ windowIssues(e, w)

  val Researcher = FileSpec(
    "researcher",
    Vertex,
    "Researcher",
    Vector(
      col("displayName", StringT, true),
      col("orcid", StringT),
      col("mentionCount", LongT, true),
      col("dblpAnchored", BoolT, true),
      col("assignedTier", StringT, true),
      col("identityConfidence", DoubleT, true)
    ),
    None,
    IdFormat.isResearcherId,
    (e, _) =>
      e.props
        .get("identityConfidence")
        .collect {
          case d: Double if d < 0 || d > 1 => "BAD_VALUE" -> s"identityConfidence $d"
        }
        .toList
  )
  val Work = FileSpec(
    "work",
    Vertex,
    "Work",
    Vector(
      col("title", StringT),
      col("year", IntT),
      col("workType", StringT),
      col("doi", StringT),
      col("dblpAnchored", BoolT, true)
    ),
    None,
    IdFormat.isWorkId,
    noChecks
  )
  val Venue = FileSpec(
    "venue",
    Vertex,
    "Venue",
    Vector(col("name", StringT, true), col("venueType", StringT)),
    None,
    IdFormat.isNamespacedId,
    noChecks
  )
  val Institution = FileSpec(
    "institution",
    Vertex,
    "Institution",
    Vector(col("name", StringT, true), col("countryCode", StringT)),
    None,
    IdFormat.isNamespacedId,
    noChecks
  )
  val Topic = FileSpec(
    "topic",
    Vertex,
    "Topic",
    Vector(col("name", StringT, true), col("subfield", StringT), col("field", StringT)),
    None,
    IdFormat.isNamespacedId,
    noChecks
  )
  val Community = FileSpec(
    "community",
    Vertex,
    NodeLabel.Community.toString,
    windowCols ++ Vector(
      col("communitySize", LongT, true),
      col("internalEdges", LongT, true),
      col("crossCommunityEdges", LongT, true),
      col("communityLabel", StringT, true)
    ),
    None,
    IdFormat.isCommunityId,
    windowIssues
  )
  val Authored = FileSpec(
    "authored",
    Edge,
    "AUTHORED",
    Vector(col("position", IntT, true), col("year", IntT)),
    Some("Researcher" -> "Work"),
    IdFormat.isGraphEdgeId,
    geChecks
  )
  val CoauthoredWith = FileSpec(
    "coauthored-with",
    Edge,
    "COAUTHORED_WITH",
    windowCols ++ Vector(
      col("jointPublicationCount", LongT, true),
      col("firstCollaborationYear", IntT, true),
      col("lastCollaborationYear", IntT, true),
      col("supportingWorkIds", StringT),
      col("supportingWorkIdsRef", StringT)
    ),
    Some("Researcher" -> "Researcher"),
    IdFormat.isCoauthorEdgeId,
    coauthorChecks
  )
  val PublishedIn = FileSpec(
    "published-in",
    Edge,
    "PUBLISHED_IN",
    Vector.empty,
    Some("Work" -> "Venue"),
    IdFormat.isGraphEdgeId,
    geChecks
  )
  val AffiliatedWith = FileSpec(
    "affiliated-with",
    Edge,
    "AFFILIATED_WITH",
    Vector(col("via", StringT, true)),
    Some("Researcher" -> "Institution"),
    IdFormat.isGraphEdgeId,
    (e, w) =>
      geChecks(e, w) ++ e.props.get("via").collect {
        case v: String if !v.split(' ').forall(AffiliationVia) => "BAD_VALUE" -> s"via '$v'"
      }
  )
  val HasTopic = FileSpec(
    "has-topic",
    Edge,
    "HAS_TOPIC",
    Vector.empty,
    Some("Work" -> "Topic"),
    IdFormat.isGraphEdgeId,
    geChecks
  )
  val MemberOf = FileSpec(
    "member-of",
    Edge,
    EdgeType.MEMBER_OF.toString,
    windowCols,
    Some("Researcher" -> NodeLabel.Community.toString),
    IdFormat.isGraphEdgeId,
    (e, w) => geChecks(e, w) ++ windowIssues(e, w)
  )

  val Vertices: Vector[FileSpec] = Vector(Researcher, Work, Venue, Institution, Topic, Community)
  val Edges: Vector[FileSpec] =
    Vector(Authored, CoauthoredWith, PublishedIn, AffiliatedWith, HasTopic, MemberOf)
  val All: Vector[FileSpec] = Vertices ++ Edges

  /** The CSV fields of one element under spec (the inverse of parsing). */
  def renderRow(spec: FileSpec, e: GraphElement): Vector[String] =
    val system =
      if spec.kind == Vertex then Vector(e.id, e.label) else Vector(e.id, e.from, e.to, e.label)
    system ++ spec.propColumns.map(c => e.props.get(c.name).fold("")(c.tpe.render))

/** The S3 object a supportingWorkIdsRef points to. */
final case class CoauthorEvidence(
    schemaVersion: String,
    edgeId: String,
    window: String,
    workIds: Vector[String],
    runId: String,
    snapshot: String
) derives ReadWriter

/** Result of a streaming scan: violations (capped), data rows per file family, the uncapped
  * violation count per rule, and how much COAUTHORED_WITH evidence was traced to Work vertices.
  */
final case class GraphScan(
    violations: Vector[Violation],
    counts: Map[String, Long],
    ruleCounts: Map[String, Long] = Map.empty,
    evidenceChecks: Map[String, Long] = Map.empty
)

/** Reads and validates a Neptune load directory: root/vertices/<name>/[*].csv and
  * root/edges/<name>/[*].csv, nothing else (the loader loads every object under its source prefix).
  */
object NeptuneCsv:
  val MaxViolations = 10000

  /** Evidence-tracing rules (F2, M4); ValidateCli prints their counts even when zero. */
  val EvidenceRules: Vector[String] = Vector(
    "EVIDENCE_WORK_MISSING",
    "EVIDENCE_NOT_AUTHORED",
    "EVIDENCE_OBJECT_ORDER",
    "EVIDENCE_OBJECT_PROVENANCE"
  )

  final private class Collector:
    val out = Vector.newBuilder[Violation]
    val byCode = mutable.TreeMap.empty[String, Long]
    var n = 0
    def add(v: Violation): Unit =
      n += 1
      byCode(v.code) = byCode.getOrElse(v.code, 0L) + 1
      if n <= MaxViolations then out += v
    def result: Vector[Violation] =
      val r = out.result()
      if n > MaxViolations then r :+ Violation("*", 0, "TRUNCATED", s"$n violations in total")
      else r

  private def specFor(root: Path, file: Path): Option[FileSpec] =
    root.relativize(file).iterator.asScala.map(_.toString).toList match
      case List(dir, name, f) if f.endsWith(".csv") =>
        NeptuneContract.All.find(s => s.dir == dir && s.name == name)
      case _ => None

  /** Validates a load directory and passes every parsed, non-duplicate element to sink.
    *
    * Streams the files: vertex files first, then AUTHORED edges, then the other edge files, so each
    * edge row is checked against the vertices already read and each COAUTHORED_WITH work ID against
    * the Work vertices and the AUTHORED pairs (F2). Only vertex labels and windows, the edge IDs,
    * and the (researcher, work) AUTHORED pairs stay in memory (ruling 27); sink decides what else
    * to keep. The scan can still report violations after sink saw an element, so a sink must not
    * apply anything before the scan is clean.
    */
  def scan(
      root: Path,
      windows: Seq[TimeWindow],
      evidence: Option[ObjectReadPort],
      sink: (ElementKind, GraphElement) => Unit = (_, _) => ()
  ): GraphScan =
    val byWindow = ProvenanceRules.windowsByName(windows)
    val v = Collector()
    // Vertex ID -> (label, window property); only Community vertices have a window.
    val vertexInfo = mutable.HashMap.empty[String, (String, Option[Prop])]
    val edgeIds = mutable.HashSet.empty[String]
    val authored = mutable.HashSet.empty[(String, String)] // (researcher, work)
    val checks = mutable.TreeMap.empty[String, Long]
    def tally(k: String, n: Long) = checks(k) = checks.getOrElse(k, 0L) + n
    val counts = mutable.TreeMap.empty[String, Long]
    val files =
      if !Files.isDirectory(root) then Vector.empty
      else
        Using.resource(Files.walk(root))(
          _.iterator.asScala.filter(p => Files.isRegularFile(p)).toVector
        )
    if files.isEmpty then v.add(Violation(root.toString, 0, "EMPTY_LOAD", "no files"))
    val ordered =
      files
        .map(f => f -> specFor(root, f))
        .sortBy((f, s) =>
          (s.fold(2)(_.kind.ordinal), !s.contains(NeptuneContract.Authored), f.toString)
        )
    ordered.foreach { (file, specOpt) =>
      val src = root.relativize(file).toString
      specOpt match
        case None => v.add(Violation(src, 0, "UNKNOWN_FILE", "not part of the Neptune contract"))
        case Some(spec) =>
          var headerOk = false
          var seenHeader = false
          try
            Csv.foreachRecord(file) { (line, row) =>
              if !seenHeader then
                seenHeader = true
                headerOk = row == spec.header
                if !headerOk then
                  v.add(
                    Violation(src, line, "BAD_HEADER", s"expected ${spec.header.mkString(",")}")
                  )
              else if headerOk then
                counts(spec.name) = counts.getOrElse(spec.name, 0L) + 1
                val (elem, issues) = parseRow(spec, row, byWindow)
                issues.foreach((c, d) => v.add(Violation(src, line, c, d)))
                elem.foreach { e =>
                  if spec.kind == ElementKind.Vertex then
                    if vertexInfo.contains(e.id) then
                      v.add(Violation(src, line, "DUPLICATE_ID", e.id))
                    else
                      vertexInfo(e.id) = (e.label, e.props.get("window"))
                      sink(ElementKind.Vertex, e)
                  else if !edgeIds.add(e.id) then v.add(Violation(src, line, "DUPLICATE_ID", e.id))
                  else
                    sink(ElementKind.Edge, e)
                    if spec == NeptuneContract.Authored then authored += ((e.from, e.to))
                    edgeIssues(spec, e, vertexInfo, authored, evidence, tally)
                      .foreach((c, d) => v.add(Violation(src, line, c, d)))
                }
            }
          catch case e: CsvFormatException => v.add(Violation(src, e.line, "BAD_CSV", e.getMessage))
          if !seenHeader then v.add(Violation(src, 0, "BAD_HEADER", "empty file"))
    }
    GraphScan(v.result, counts.toMap, v.byCode.toMap, checks.toMap)

  /** Endpoint, MEMBER_OF window, evidence-object, and evidence-tracing checks of one edge. */
  private def edgeIssues(
      spec: FileSpec,
      e: GraphElement,
      vertexInfo: collection.Map[String, (String, Option[Prop])],
      authored: collection.Set[(String, String)],
      evidence: Option[ObjectReadPort],
      tally: (String, Long) => Unit
  ): List[(String, String)] =
    val (fromLabel, toLabel) = spec.endpoints.get
    val ends = List(e.from -> fromLabel, e.to -> toLabel).flatMap((end, want) =>
      vertexInfo.get(end) match
        case None                      => List("EDGE_ENDPOINT_MISSING" -> s"${e.id}: $end")
        case Some((l, _)) if l != want => List("EDGE_ENDPOINT_LABEL" -> s"${e.id}: $end is $l")
        case _                         => Nil
    )
    val window =
      if spec != NeptuneContract.MemberOf then Nil
      else
        vertexInfo
          .get(e.to)
          .filter(_._2 != e.props.get("window"))
          .map(_ => "WINDOW_MISMATCH" -> s"${e.id}: community window differs")
          .toList
    val (objectIssues, cited) =
      if spec != NeptuneContract.CoauthoredWith then (Nil, None)
      else
        (e.props.get("supportingWorkIds"), e.props.get("supportingWorkIdsRef"), evidence) match
          case (Some(ids: String), _, _) =>
            tally("edges-inline", 1)
            (Nil, Some(ids.split(' ').toVector))
          case (None, Some(ref: String), Some(store)) =>
            tally("edges-object", 1)
            evidenceObject(e, ref, store)
          case (None, Some(_), None) =>
            tally("edges-object-not-opened", 1) // no evidence root given: shape checked only
            (Nil, None)
          case _ => (Nil, None)
    ends ++ window ++ objectIssues ++ cited.toList.flatMap(
      traceWorks(e, _, vertexInfo, authored, tally)
    )

  /** F2: every cited work is a Work vertex, and both endpoints AUTHORED it. */
  private def traceWorks(
      e: GraphElement,
      works: Vector[String],
      vertexInfo: collection.Map[String, (String, Option[Prop])],
      authored: collection.Set[(String, String)],
      tally: (String, Long) => Unit
  ): List[(String, String)] =
    tally("work-ids-traced", works.size.toLong)
    val (present, missing) = works.partition(w => vertexInfo.get(w).exists(_._1 == "Work"))
    val notAuthored = present.flatMap(w =>
      List(e.from, e.to).filterNot(r => authored((r, w))).map(r => s"$r did not author $w")
    )
    List(
      Option.when(missing.nonEmpty)(
        "EVIDENCE_WORK_MISSING" ->
          s"${e.id}: ${missing.size} of ${works.size} cited works are not Work vertices, first ${missing.head}"
      ),
      Option.when(notAuthored.nonEmpty)(
        "EVIDENCE_NOT_AUTHORED" ->
          s"${e.id}: ${notAuthored.size} missing AUTHORED links, first: ${notAuthored.head}"
      )
    ).flatten

  /** The evidence object's violations and, when it parses, its work IDs for tracing. */
  private def evidenceObject(
      e: GraphElement,
      ref: String,
      store: ObjectReadPort
  ): (List[(String, String)], Option[Vector[String]]) =
    store.get(ref) match
      case None => (List("BAD_EVIDENCE_REF" -> s"${e.id}: evidence object $ref not found"), None)
      case Some(bytes) =>
        scala.util
          .Try(upickle.default.read[CoauthorEvidence](new String(bytes, UTF_8)))
          .toOption match
          case None =>
            val msg =
              s"${e.id}: evidence object $ref is not ${NeptuneContract.EvidenceSchemaVersion}"
            (List("BAD_EVIDENCE_REF" -> msg), None)
          case Some(ev) =>
            val count = e.props.get("jointPublicationCount").collect { case l: Long => l }
            val issues = List(
              Option.when(
                ev.schemaVersion != NeptuneContract.EvidenceSchemaVersion || ev.edgeId != e.id ||
                  count.forall(_ != ev.workIds.size.toLong) ||
                  ev.workIds.exists(w => !IdFormat.isWorkId(w)) ||
                  Some(ev.window) != e.props.get("window")
              )("BAD_EVIDENCE_REF" -> s"${e.id}: evidence object $ref does not match the edge"),
              Option.when(ev.workIds != ev.workIds.distinct.sorted)(
                "EVIDENCE_OBJECT_ORDER" -> s"${e.id}: workIds in $ref must be sorted and distinct"
              ),
              Option.when(
                Some(ev.runId) != e.props.get("runId") || Some(ev.snapshot) != e.props.get(
                  "snapshot"
                )
              )(
                "EVIDENCE_OBJECT_PROVENANCE" ->
                  s"${e.id}: $ref has runId ${ev.runId} snapshot ${ev.snapshot}, the edge has ${e.props
                      .getOrElse("runId", "")} ${e.props.getOrElse("snapshot", "")}"
              )
            ).flatten
            (issues, Some(ev.workIds))

  /** The element (when its system columns are usable) and the row's violations. */
  def parseRow(
      spec: FileSpec,
      row: Vector[String],
      windows: Map[String, TimeWindow]
  ): (Option[GraphElement], List[(String, String)]) =
    if row.size != spec.header.size then
      (None, List("BAD_ROW" -> s"${row.size} fields, header has ${spec.header.size}"))
    else
      val vertex = spec.kind == ElementKind.Vertex
      val id = row(0)
      val (from, to, label) = if vertex then ("", "", row(1)) else (row(1), row(2), row(3))
      val sys = List(
        Option.when(id.isEmpty)("MISSING_ID" -> "empty :ID"),
        Option.when(id.nonEmpty && !spec.idOk(id))("BAD_ID" -> id),
        Option.when(label != spec.label)("BAD_LABEL" -> s"'$label', expected ${spec.label}"),
        Option.when(!vertex && (from.isEmpty || to.isEmpty))("MISSING_ENDPOINT" -> id)
      ).flatten
      val cells = spec.propColumns.zip(row.drop(spec.systemColumns.size))
      val typed: Vector[Either[(String, String), (String, Prop)]] = cells.flatMap { (c, s) =>
        if s.isEmpty then Option.when(c.required)(Left("MISSING_VALUE" -> c.name))
        else
          Some(
            c.tpe
              .parse(s)
              .toRight("BAD_TYPE" -> s"${c.name}: '$s' is not ${c.tpe.header}")
              .map(c.name -> _)
          )
      }
      val props = typed.collect { case Right(kv) => kv }.toMap
      val prov = List(
        props.get("runId").collect {
          case r: String if !IdFormat.isRunId(r) => "BAD_PROVENANCE" -> s"runId $r"
        },
        props.get("snapshot").collect {
          case s: String if !ProvenanceRules.Snapshot.matches(s) =>
            "BAD_PROVENANCE" -> s"snapshot $s"
        },
        props.get("schemaVersion").collect {
          case s: String if s != spec.schemaVersion =>
            "BAD_PROVENANCE" -> s"schemaVersion $s, expected ${spec.schemaVersion}"
        },
        props.get("policyVersion").collect {
          case s: String if !IdFormat.Version.matches(s) => "BAD_PROVENANCE" -> s"policyVersion $s"
        }
      ).flatten
      val basic = sys ++ typed.collect { case Left(issue) => issue } ++ prov
      val elem = Option.when(id.nonEmpty)(GraphElement(id, label, from, to, props))
      (elem, if basic.nonEmpty then basic else elem.toList.flatMap(spec.rowChecks(_, windows)))

/** Bounds every runtime graph read (law 4). Live templates also carry a depth and a timeout. */
object GraphLimits:
  val MaxResults = 1000

/** Read side of the graph store; runtime services get only this type. */
trait GraphReadPort:
  def vertex(id: String): Option[GraphElement]
  def edge(id: String): Option[GraphElement]

  /** The COAUTHORED_WITH edge of a researcher pair (any order) in a window. */
  def coauthorEdge(researcherX: String, researcherY: String, window: String): Option[GraphElement]

  /** A researcher's coauthor edges in a window, strongest first (joint publications descending,
    * then edge ID), at most limit (1 to GraphLimits.MaxResults).
    */
  def coauthors(researcherId: String, window: String, limit: Int): Vector[GraphElement]

final case class GraphLoadReport(
    verticesAdded: Int,
    edgesAdded: Int,
    verticesUnchanged: Int,
    edgesUnchanged: Int
)

/** Load side: validates a whole load directory and applies it all or not at all. */
trait GraphLoadPort:
  def load(
      root: Path,
      windows: Seq[TimeWindow],
      evidence: Option[ObjectReadPort]
  ): Either[Vector[Violation], GraphLoadReport]

/** In-memory local fake of the Neptune graph. A reload of an element with the same ID and the same
  * properties is a no-op; the same ID with different properties is refused (Neptune would keep the
  * old values without saying so unless updateSingleCardinalityProperties is set).
  */
final class InMemoryGraph:
  private val vertices = mutable.HashMap.empty[String, GraphElement]
  private val edges = mutable.HashMap.empty[String, GraphElement]
  private val pairIndex = mutable.HashMap.empty[(String, String, String), String]
  private val adjacency = mutable.HashMap.empty[(String, String), Vector[String]]

  def vertexCount: Int = vertices.size
  def edgeCount: Int = edges.size

  val reader: GraphReadPort = new GraphReadPort:
    def vertex(id: String): Option[GraphElement] = InMemoryGraph.this.synchronized(vertices.get(id))
    def edge(id: String): Option[GraphElement] = InMemoryGraph.this.synchronized(edges.get(id))
    def coauthorEdge(x: String, y: String, window: String): Option[GraphElement] =
      InMemoryGraph.this.synchronized(
        CanonicalPair(x, y).toOption.flatMap((a, b) => pairIndex.get((a, b, window))).map(edges)
      )
    def coauthors(researcherId: String, window: String, limit: Int): Vector[GraphElement] =
      require(limit >= 1 && limit <= GraphLimits.MaxResults, s"limit $limit out of range")
      InMemoryGraph.this.synchronized(
        adjacency
          .getOrElse((researcherId, window), Vector.empty)
          .map(edges)
          .sortBy(e =>
            (
              -e.props.get("jointPublicationCount").collect { case l: Long => l }.getOrElse(0L),
              e.id
            )
          )
          .take(limit)
      )

  val loader: GraphLoadPort = new GraphLoadPort:
    def load(
        root: Path,
        windows: Seq[TimeWindow],
        evidence: Option[ObjectReadPort]
    ): Either[Vector[Violation], GraphLoadReport] =
      InMemoryGraph.this.synchronized {
        // Compare while streaming: only new elements are buffered, so a reload holds no copy.
        val newV = Vector.newBuilder[GraphElement]
        val newE = Vector.newBuilder[GraphElement]
        val found = Vector.newBuilder[Violation]
        var sameV, sameE = 0
        val scan = NeptuneCsv.scan(
          root,
          windows,
          evidence,
          (kind, e) =>
            val (existing, fresh) =
              if kind == ElementKind.Vertex then (vertices, newV) else (edges, newE)
            existing.get(e.id) match
              case None                  => fresh += e
              case Some(old) if old == e =>
                if kind == ElementKind.Vertex then sameV += 1 else sameE += 1
              case Some(_) =>
                found += Violation(
                  "graph",
                  0,
                  "PROPERTY_CONFLICT",
                  s"${e.id} exists with different properties"
                )
        )
        val conflicts = found.result()
        if scan.violations.nonEmpty then Left(scan.violations)
        else if conflicts.nonEmpty then Left(conflicts)
        else Right(add(newV.result(), newE.result(), sameV, sameE))
      }

  private def add(
      newV: Vector[GraphElement],
      newE: Vector[GraphElement],
      sameV: Int,
      sameE: Int
  ): GraphLoadReport =
    newV.foreach(e => vertices(e.id) = e)
    newE.foreach { e =>
      edges(e.id) = e
      if e.label == NeptuneContract.CoauthoredWith.label then
        val w = e.props("window").toString
        pairIndex((e.from, e.to, w)) = e.id
        for r <- Seq(e.from, e.to) do
          adjacency((r, w)) = adjacency.getOrElse((r, w), Vector.empty) :+ e.id
    }
    GraphLoadReport(newV.size, newE.size, sameV, sameE)
