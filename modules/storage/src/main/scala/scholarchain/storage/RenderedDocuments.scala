package scholarchain.storage

import scholarchain.core.config.TimeWindow
import scholarchain.core.{IdFormat, Provenance, SchemaVersions, StableIds}
import upickle.default.{ReadWriter, read}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.{Try, Using}

/** One line of the rendered-document JSONL the rag render CLI produces (STORAGE.md,
  * "Rendered-document JSONL"). It is the embedding runner's input.
  *
  * metadata is exactly the vector metadata the vector will carry; the text stays out of it.
  * embeddingModelId is the Bedrock model the text is meant for; the runner refuses a document whose
  * model differs from its embedder.
  */
final case class RenderedDocument(
    schemaVersion: String,
    vectorKey: String,
    index: String,
    documentType: String,
    text: String,
    textHash: String,
    templateVersion: String,
    embeddingModelId: String,
    metadata: ujson.Obj,
    provenance: Provenance
) derives ReadWriter

object RenderedDocuments:
  val SchemaVersion = "rendered-document-v1"

  private def str(m: ujson.Obj, k: String) = m.value.get(k).flatMap(_.strOpt)

  /** The vector key rule: the Phase 6 summaryId formula applied to the rendered text, so a changed
    * text always gets a new key.
    */
  def expectedKey(d: RenderedDocument): Option[String] =
    for
      ids <- d.metadata.value.get("entityIds").flatMap(_.arrOpt)
      first <- ids.headOption.flatMap(_.strOpt)
      window <- str(d.metadata, "window")
    yield StableIds.chunkId(s"$first|$window", d.templateVersion, d.documentType, None, d.textHash)

  /** Rule violations of one document; empty means valid. */
  def problems(d: RenderedDocument, windows: Map[String, TimeWindow]): List[String] =
    def check(ok: Boolean, msg: => String) = if ok then Nil else List(msg)
    val p = d.provenance
    val m = d.metadata
    check(d.schemaVersion == SchemaVersion, s"schemaVersion ${d.schemaVersion}") ++
      check(IdFormat.isChunkId(d.vectorKey), s"vectorKey ${d.vectorKey} is not a chunk ID") ++
      check(
        expectedKey(d).forall(_ == d.vectorKey),
        s"vectorKey is not chunkId(entityIds[0]|window, templateVersion, documentType, textHash)"
      ) ++
      check(d.text.trim.nonEmpty, "empty text") ++
      check(
        d.text.length <= EmbeddingCandidates.MaxTextChars,
        s"text has ${d.text.length} characters, max ${EmbeddingCandidates.MaxTextChars}"
      ) ++
      check(StableIds.textHash(d.text) == d.textHash, "textHash is not sha256 of text") ++
      check(IdFormat.Version.matches(d.templateVersion), "templateVersion") ++
      check(IdFormat.isVisibleAscii(d.embeddingModelId), "embeddingModelId") ++
      check(str(m, "textHash").contains(d.textHash), "metadata.textHash differs") ++
      check(str(m, "documentType").contains(d.documentType), "metadata.documentType differs") ++
      check(
        str(m, "window").exists(windows.contains),
        "metadata.window is not a configured window"
      ) ++
      check(str(m, "runId").contains(p.runId), "metadata.runId differs from provenance.runId") ++
      check(
        str(m, "textRef").contains(
          S3Layout.semanticText(p.snapshot, p.runId, d.index, d.vectorKey)
        ),
        "metadata.textRef is not semantic-context/text/snapshot=<d>/run=<r>/<index>/<vectorKey>.txt"
      ) ++
      check(p.schemaVersion == SchemaVersions.Provenance, s"provenance.schemaVersion") ++
      check(IdFormat.isRunId(p.runId), "provenance.runId") ++
      check(ProvenanceRules.Snapshot.matches(p.snapshot), "provenance.snapshot") ++
      check(p.sourceRecordId.trim.nonEmpty, "provenance.sourceRecordId") ++
      check(
        p.policyVersion.contains(d.templateVersion),
        "provenance.policyVersion != templateVersion"
      ) ++
      VectorContract.metadataProblems(d.index, d.vectorKey, m)

  /** Streams one JSONL file; f gets the line number and the parsed document or a parse error. */
  def foreachLine(file: Path)(f: (Long, Either[String, RenderedDocument]) => Unit): Unit =
    Using.resource(Files.lines(file, UTF_8)) { lines =>
      val it = lines.iterator
      var line = 0L
      while it.hasNext do
        val l = it.next()
        line += 1
        if l.trim.nonEmpty then
          f(
            line,
            Try(read[RenderedDocument](l)).toEither.left.map(e => s"${e.getMessage}".take(200))
          )
    }

  /** Validates rendered-document files; returns violations and the document count per index. */
  def validate(
      files: Seq[Path],
      windows: Seq[TimeWindow],
      totalBounds: Option[(Int, Int)]
  ): (Vector[Violation], Map[String, Int]) =
    val byWindow = ProvenanceRules.windowsByName(windows)
    val out = Vector.newBuilder[Violation]
    val seen = mutable.HashMap.empty[String, String]
    val counts = mutable.TreeMap.empty[String, Int]
    files.foreach { f =>
      val src = f.toString
      foreachLine(f) {
        case (line, Left(e))  => out += Violation(src, line, "MALFORMED", e)
        case (line, Right(d)) =>
          problems(d, byWindow).foreach(p => out += Violation(src, line, "BAD_DOCUMENT", p))
          seen.get(d.vectorKey) match
            case Some(at) =>
              out += Violation(src, line, "DUPLICATE_KEY", s"${d.vectorKey} first at $at")
            case None =>
              seen(d.vectorKey) = s"$src:$line"
              counts(d.index) = counts.getOrElse(d.index, 0) + 1
      }
    }
    val total = counts.values.sum
    totalBounds.foreach { (lo, hi) =>
      if total < lo || total > hi then
        out += Violation("*", 0, "TOTAL_OUT_OF_RANGE", s"$total documents, expected $lo to $hi")
    }
    (out.result(), counts.toMap)
