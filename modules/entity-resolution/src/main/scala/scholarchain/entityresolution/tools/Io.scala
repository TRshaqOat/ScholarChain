package scholarchain.entityresolution.tools

import scholarchain.core.*
import scholarchain.entityresolution.{MatchPolicy, Normalize}
import upickle.default.read

import java.io.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.{Deflater, GZIPInputStream, GZIPOutputStream}
import scala.jdk.CollectionConverters.*

/** Local file helpers for the entity-resolution tools (not used by the pure policy). */
object Io:
  /** Part files of a Hadoop output directory (or the files of any directory), including those of
    * partition subdirectories such as `outcome=ACCEPTED/`, sorted by name with each subdirectory's
    * files in place; hidden and `_` entries skipped. Empty when the directory does not exist.
    */
  def partFiles(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val s = Files.list(dir)
      val entries =
        try
          s.iterator.asScala
            .filterNot(p =>
              p.getFileName.toString.startsWith(".") || p.getFileName.toString.startsWith("_")
            )
            .toVector
            .sortBy(_.getFileName.toString)
        finally s.close()
      entries.flatMap(p =>
        if Files.isDirectory(p) then partFiles(p)
        else if Files.isRegularFile(p) then Vector(p)
        else Vector.empty
      )

  def reader(f: Path): BufferedReader =
    val raw = new BufferedInputStream(Files.newInputStream(f), 1 << 16)
    val in = if f.toString.endsWith(".gz") then new GZIPInputStream(raw, 1 << 16) else raw
    new BufferedReader(new InputStreamReader(in, UTF_8), 1 << 16)

  /** Lines of every part file of the directories, in order; each file is closed when exhausted. */
  def lines(dirs: Path*): Iterator[String] =
    dirs.iterator.flatMap(partFiles).flatMap { f =>
      val r = reader(f)
      Iterator.continually(r.readLine()).takeWhile { l =>
        if l == null then r.close()
        l != null
      }
    }

  /** A gzip writer (fastest level: these are local scratch outputs). */
  def gzWriter(f: Path): BufferedWriter =
    Option(f.getParent).foreach(Files.createDirectories(_))
    val gz =
      new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(f), 1 << 16), 1 << 16):
        `def`.setLevel(Deflater.BEST_SPEED)
    new BufferedWriter(new OutputStreamWriter(gz, UTF_8), 1 << 16)

  def writeString(f: Path, s: String): Unit =
    Option(f.getParent).foreach(Files.createDirectories(_))
    Files.writeString(f, s, UTF_8)

  /** `--name value` options; a flag with no value maps to "true". */
  def options(args: Seq[String]): Map[String, String] =
    def go(rest: List[String], acc: Map[String, String]): Map[String, String] = rest match
      case k :: v :: tail if k.startsWith("--") && !v.startsWith("--") =>
        go(tail, acc + (k.drop(2) -> v))
      case k :: tail if k.startsWith("--") => go(tail, acc + (k.drop(2) -> "true"))
      case Nil                             => acc
      case other :: _ => throw new IllegalArgumentException(s"unexpected argument: $other")
    go(args.toList, Map.empty)

  def required(o: Map[String, String], k: String): String =
    o.getOrElse(k, throw new IllegalArgumentException(s"missing --$k"))

/** RFC 4180 CSV: every cell quoted on write; quoted cells may hold commas, quotes, and newlines. */
object Csv:
  def row(cells: Seq[String]): String =
    cells.map(c => "\"" + c.replace("\"", "\"\"") + "\"").mkString(",")

  def parse(text: String): Vector[Vector[String]] =
    val rows = Vector.newBuilder[Vector[String]]
    var cur = Vector.newBuilder[String]
    val cell = new StringBuilder
    var quoted = false
    var i = 0
    var any = false
    def endCell(): Unit = { cur += cell.toString; cell.clear(); any = true }
    def endRow(): Unit = { endCell(); rows += cur.result(); cur = Vector.newBuilder; any = false }
    while i < text.length do
      val c = text(i)
      if quoted then
        if c == '"' && i + 1 < text.length && text(i + 1) == '"' then { cell += '"'; i += 1 }
        else if c == '"' then quoted = false
        else cell += c
      else if c == '"' then quoted = true
      else if c == ',' then endCell()
      else if c == '\n' then endRow()
      else if c != '\r' then { cell += c; any = true }
      i += 1
    if any || cell.nonEmpty then endRow()
    rows.result()

  /** Rows as header-keyed maps. */
  def records(text: String): Vector[Map[String, String]] =
    parse(text) match
      case header +: body => body.filter(_.exists(_.nonEmpty)).map(r => header.zip(r).toMap)
      case _              => Vector.empty

/** The implemented match policy version a Hadoop run manifest (`matchPolicyVersion`) or an ER
  * Runner summary.json (`policyVersion`) names; fails when it is missing or not implemented.
  */
def runPolicyVersion(manifest: ujson.Value): String =
  val v = manifest.obj.get("policyVersion").orElse(manifest.obj.get("matchPolicyVersion"))
  require(v.isDefined, "manifest names no match policy version")
  MatchPolicy.requireVersion(v.get.str)

/** Records of one Hadoop run directory that the tools read. */
final case class RunInputs(root: Path):
  def j01(d: String): Path = root.resolve("j01-dblp-extract").resolve(d)
  def j02(d: String): Path = root.resolve("j02-source-normalize").resolve(d)
  def exactMatches: Path = root.resolve("j03-j04-exact-identifier/matches")
  def exactConflicts: Path = root.resolve("j03-j04-exact-identifier/conflicts")
  def blockedCandidates: Path = root.resolve("j06-candidate-join/candidates")

  /** J10 MatchDecisions as exported by J09 (partitioned by outcome below this directory). */
  def decisions(et: MatchEntityType): Path =
    root.resolve(s"j09-graph-export/match-decisions/entity_type=$et")
  def manifest: Path = root.resolve("_manifest.json")
  def runId: String = root.getFileName.toString.stripPrefix("run=")

  def works(): Iterator[WorkRecord] = Io.lines(j01("works"), j02("works")).map(read[WorkRecord](_))

/** Works, venues, and institutions of a run, indexed for evidence lookups. */
final class WorkIndex(inputs: RunInputs):
  val works: Map[String, WorkRecord] = inputs.works().map(w => w.workId -> w).toMap
  val venues: Map[String, String] =
    Io.lines(inputs.j01("venues"), inputs.j02("venues"))
      .map(read[Venue](_))
      .map(v => v.venueId -> v.name)
      .toMap
  val institutions: Map[String, String] =
    Io.lines(inputs.j02("institutions"))
      .map(read[Institution](_))
      .map(i => i.institutionId -> i.name)
      .toMap

  def mention(mentionId: String): Option[(AuthorMention, WorkRecord)] =
    val i = mentionId.lastIndexOf('#')
    if i <= 0 then None
    else
      works
        .get(mentionId.take(i))
        .flatMap(w => w.authors.find(_.mentionId == mentionId).map(_ -> w))

  def venueName(w: WorkRecord): String =
    w.venueId
      .flatMap(venues.get)
      .orElse(w.externalIds.get("venue"))
      .orElse(w.externalIds.get("raw_source_name"))
      .orElse(w.externalIds.get("container-title"))
      .getOrElse("")

/** Side-by-side evidence fields for one record of a pair (review CSV and adjudication input). */
object Evidence:
  val Keys: Vector[String] =
    Vector(
      "name",
      "homonym_suffix",
      "orcid",
      "coauthors",
      "title",
      "venue",
      "year",
      "affiliations",
      "doi"
    )
  private val MaxNames = 25

  private def names(ms: Seq[AuthorMention]): String =
    val shown = ms.take(MaxNames).map(_.rawName)
    (shown ++ Option.when(ms.size > MaxNames)(s"... (${ms.size} total)")).mkString("; ")

  def researcher(idx: WorkIndex, mentionId: String): Map[String, String] =
    idx.mention(mentionId).fold(Map("name" -> s"(record $mentionId not found)")) { (m, w) =>
      val (printed, suffix) = Normalize.splitHomonym(m.rawName)
      Map(
        "name" -> printed,
        "homonym_suffix" -> suffix.getOrElse(""),
        "orcid" -> m.orcid.getOrElse(""),
        "coauthors" -> names(w.authors.filterNot(_.position == m.position)),
        "title" -> w.title.getOrElse(""),
        "venue" -> idx.venueName(w),
        "year" -> w.year.fold("")(_.toString),
        "affiliations" -> m.institutionIds
          .map(i => idx.institutions.getOrElse(i, i))
          .mkString("; "),
        "doi" -> w.doi.getOrElse("")
      )
    }

  def work(idx: WorkIndex, workId: String): Map[String, String] =
    idx.works.get(workId).fold(Map("title" -> s"(record $workId not found)")) { w =>
      Map(
        "coauthors" -> names(w.authors),
        "title" -> w.title.getOrElse(""),
        "venue" -> idx.venueName(w),
        "year" -> w.year.fold("")(_.toString),
        "doi" -> w.doi.getOrElse("")
      )
    }

  def side(idx: WorkIndex, t: MatchEntityType, id: String): Map[String, String] = t match
    case MatchEntityType.RESEARCHER => researcher(idx, id)
    case MatchEntityType.WORK       => work(idx, id)

  /** Adjudication fields `a.<key>` and `b.<key>`, non-empty values only. */
  def fields(idx: WorkIndex, t: MatchEntityType, a: String, b: String): Map[String, String] =
    def pre(p: String, m: Map[String, String]) = m.collect {
      case (k, v) if v.nonEmpty => s"$p.$k" -> v
    }
    pre("a", side(idx, t, a)) ++ pre("b", side(idx, t, b))
