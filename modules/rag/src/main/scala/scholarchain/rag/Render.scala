package scholarchain.rag

import scholarchain.core.config.TimeWindow
import scholarchain.core.{Provenance, SchemaVersions, SourceType, StableIds}
import scholarchain.storage.{
  EmbeddingCandidate,
  EmbeddingCandidates,
  ProvenanceRules,
  RenderedDocument,
  RenderedDocuments,
  S3Layout,
  Violation
}
import upickle.default.read

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.{Try, Using}

/** One candidate line: the storage embedding-candidate-v1 fields plus the `facts` object
  * (SELECTION.md, "Candidate content").
  */
final case class RenderInput(candidate: EmbeddingCandidate, facts: ujson.Obj)

/** The metadata lists a document's facts imply (SELECTION.md, "Metadata lists derived from facts").
  */
final case class DerivedIds(
    entityIds: Vector[String],
    topicIds: Vector[String],
    institutionIds: Vector[String],
    communityId: Option[String]
)

/** Candidate -> rendered-document-v1 (modules/storage/STORAGE.md, "Rendered-document JSONL"). */
object Render:
  val Stage = "rag-render"
  val CodeVersion = "render-v1"

  def parseLine(line: String): Either[String, RenderInput] =
    Try {
      val json = ujson.read(line)
      val facts = json.obj.get("facts") match
        case Some(o: ujson.Obj) => Right(o)
        case Some(_)            => Left("facts must be a JSON object")
        case None               => Left("facts missing")
      facts.map(RenderInput(read[EmbeddingCandidate](json), _))
    }.toEither.left.map(e => s"unreadable candidate: ${e.getMessage}".take(200)).flatten

  private def ids(f: Templates.Facts, list: String): Vector[String] =
    f.list(list).toOption.toVector.flatten.flatMap(_.strOpt("id"))

  def derive(documentType: String, facts: ujson.Obj): Either[String, DerivedIds] =
    val f = Templates.Facts(facts)
    def one(k: String) = f.str(k).map(Vector(_))
    documentType match
      case "researcher-profile" =>
        one("researcherId").map(
          DerivedIds(_, ids(f, "topics"), ids(f, "institutions"), f.strOpt("communityId"))
        )
      case "collaboration-community" =>
        one("communityId").map(c => DerivedIds(c, ids(f, "topics"), Vector.empty, c.headOption))
      case "collaboration-path" =>
        for
          pair <- f.str("pairId")
          src <- f.str("sourceResearcherId")
          tgt <- f.str("targetResearcherId")
        yield
          val found = f.strOpt("status").contains("FOUND") && f.countOpt("pathLength").exists(_ > 0)
          val rest = if found then ids(f, "hops") else Vector(tgt)
          DerivedIds(pair +: src +: rest, Vector.empty, Vector.empty, None)
      case "topic-summary" => one("topicId").map(t => DerivedIds(t, t, Vector.empty, None))
      case "paper-summary" =>
        one("workId").map(DerivedIds(_, ids(f, "topics"), Vector.empty, None))
      case other => Left(s"no rendering-v1 template for documentType $other")

  /** Checks one input against the storage candidate rules, the selection policy version, and its
    * facts; on success renders it.
    */
  def render(
      in: RenderInput,
      windows: Map[String, TimeWindow],
      modelVersion: String,
      modelId: String,
      snapshot: String,
      runId: String
  ): Either[List[String], RenderedDocument] =
    val c = in.candidate
    val base = EmbeddingCandidates.problems(c, windows) ++
      Option.unless(c.selectionPolicyVersion == Selection.PolicyVersion)(
        s"selectionPolicyVersion ${c.selectionPolicyVersion}, expected ${Selection.PolicyVersion}"
      )
    val doc = for
      d <- derive(c.documentType, in.facts)
      _ <- Either.cond(
        d == DerivedIds(c.entityIds, c.topicIds, c.institutionIds, c.communityId),
        (),
        s"entityIds, topicIds, institutionIds, or communityId differ from the facts ($d)"
      )
      window <- windows.get(c.window).toRight(s"unknown window ${c.window}")
      text <- Templates.render(c.documentType, in.facts, window)
    yield
      val textHash = StableIds.textHash(text)
      val key = StableIds.chunkId(
        s"${d.entityIds.head}|${c.window}",
        Templates.Version,
        c.documentType,
        None,
        textHash
      )
      val m = ujson.Obj(
        "vectorKey" -> key,
        "entityIds" -> ujson.Arr.from(d.entityIds.map(ujson.Str(_))),
        "window" -> c.window,
        "documentType" -> c.documentType,
        "sourceDataset" -> SourceType.DERIVED.toString,
        "embeddingModelVersion" -> modelVersion,
        "textHash" -> textHash,
        "runId" -> runId,
        "textRef" -> S3Layout.semanticText(snapshot, runId, c.vectorIndex, key)
      )
      if d.topicIds.nonEmpty then m("topicIds") = ujson.Arr.from(d.topicIds.map(ujson.Str(_)))
      if d.institutionIds.nonEmpty then
        m("institutionIds") = ujson.Arr.from(d.institutionIds.map(ujson.Str(_)))
      d.communityId.foreach(m("communityId") = _)
      RenderedDocument(
        RenderedDocuments.SchemaVersion,
        key,
        c.vectorIndex,
        c.documentType,
        text,
        textHash,
        Templates.Version,
        modelId,
        m,
        Provenance(
          SchemaVersions.Provenance,
          SourceType.DERIVED,
          c.vectorKey,
          snapshot,
          runId,
          Some(Templates.Version),
          None
        )
      )
    (base, doc) match
      case (Nil, Right(r)) =>
        RenderedDocuments.problems(r, windows) match
          case Nil      => Right(r)
          case problems => Left(problems)
      case (ps, d) => Left(ps ++ d.left.toOption)

  /** The render run: deterministic in the input bytes, the template, and the model version. */
  def runIdFor(fileHashes: Seq[String], modelVersion: String, configVersion: String): String =
    StableIds.runId(
      Stage,
      CodeVersion,
      configVersion,
      fileHashes ++ Seq(s"template=${Templates.Version}", s"model=$modelVersion"),
      1
    )

  final case class Rendered(
      runId: String,
      snapshot: String,
      documents: Vector[RenderedDocument],
      inputs: Vector[RenderInput]
  )

  /** Reads and renders every candidate file. Any violation (a bad line, a duplicate key, a gap in
    * an index's selection ranks, several snapshots) fails the whole set: nothing is rendered.
    */
  def renderAll(
      files: Seq[Path],
      windowList: Seq[TimeWindow],
      model: EmbeddingModelSpec,
      configVersion: String
  ): Either[Vector[Violation], Rendered] =
    val windows = ProvenanceRules.windowsByName(windowList)
    val modelVersion = EmbeddingModels.version(model, Templates.Version)
    val sorted = files.sortBy(_.toString)
    val bad = Vector.newBuilder[Violation]
    val inputs = Vector.newBuilder[(String, Long, RenderInput)]
    sorted.foreach { f =>
      Using.resource(Files.lines(f, UTF_8)) { lines =>
        val it = lines.iterator
        var n = 0L
        while it.hasNext do
          val l = it.next()
          n += 1
          if l.trim.nonEmpty then
            parseLine(l) match
              case Left(e)   => bad += Violation(f.toString, n, "MALFORMED", e)
              case Right(in) => inputs += ((f.toString, n, in))
      }
    }
    val all = inputs.result()
    val snapshots = all.map(_._3.candidate.provenance.snapshot).distinct
    if all.isEmpty then bad += Violation("*", 0, "NO_INPUT", "no candidates")
    if snapshots.size > 1 then
      bad += Violation("*", 0, "SNAPSHOTS", s"candidates span ${snapshots.mkString(", ")}")
    all.groupBy(_._3.candidate.vectorIndex).toVector.sortBy(_._1).foreach { (index, xs) =>
      val ranks = xs.map(_._3.candidate.selectionRank).sorted
      if ranks != (1 to xs.size).toVector then
        bad += Violation("*", 0, "SELECTION_RANKS", s"$index ranks are not exactly 1..${xs.size}")
    }
    val snapshot = snapshots.headOption.getOrElse("")
    val runId = runIdFor(
      sorted.map(f => scholarchain.storage.Hashing.sha256(Files.readAllBytes(f))),
      modelVersion,
      configVersion
    )
    val docs = Vector.newBuilder[RenderedDocument]
    val seen = mutable.HashMap.empty[String, String]
    all.foreach { (src, line, in) =>
      render(in, windows, modelVersion, model.modelId, snapshot, runId) match
        case Left(ps) => ps.foreach(p => bad += Violation(src, line, "BAD_CANDIDATE", p))
        case Right(d) =>
          seen.get(d.vectorKey) match
            case Some(at) =>
              bad += Violation(src, line, "DUPLICATE_KEY", s"${d.vectorKey} first at $at")
            case None =>
              seen(d.vectorKey) = s"$src:$line"
              docs += d
    }
    val violations = bad.result()
    if violations.nonEmpty then Left(violations)
    else Right(Rendered(runId, snapshot, docs.result(), all.map(_._3)))
