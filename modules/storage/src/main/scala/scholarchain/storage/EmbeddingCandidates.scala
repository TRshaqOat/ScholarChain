package scholarchain.storage

import scholarchain.core.config.TimeWindow
import scholarchain.core.{IdFormat, Provenance, SchemaVersions, StableIds}
import upickle.default.{ReadWriter, read}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.{Try, Using}

/** One document to embed (a line of the embedding-candidate JSONL the Spark job produces).
  *
  * The text is carried here, in the work-queue file, and written by the loader to textRef in S3; it
  * never goes into vector metadata. vectorKey is the stable document ID: the Phase 6 summaryId
  * where one exists, otherwise StableIds.chunkId over the document.
  */
final case class EmbeddingCandidate(
    schemaVersion: String,
    vectorIndex: String,
    vectorKey: String,
    documentType: String,
    entityIds: Vector[String],
    window: String,
    topicIds: Vector[String],
    institutionIds: Vector[String],
    communityId: Option[String],
    text: String,
    textHash: String,
    textRef: String,
    templateVersion: String,
    selectionPolicyVersion: String,
    selectionRank: Int,
    provenance: Provenance
) derives ReadWriter

object EmbeddingCandidates:
  val SchemaVersion = "embedding-candidate-v1"

  /** Text length cap per document, far under the Titan Text Embeddings V2 input limit; the longest
    * Phase 6 summary is 2,029 characters.
    */
  val MaxTextChars = 8000

  /** Compact vector metadata for a candidate: no text, a pointer and a hash instead. */
  def toMetadata(c: EmbeddingCandidate, embeddingModelVersion: String): ujson.Obj =
    val m = ujson.Obj(
      "vectorKey" -> c.vectorKey,
      "entityIds" -> ujson.Arr.from(c.entityIds.map(ujson.Str(_))),
      "window" -> c.window,
      "documentType" -> c.documentType,
      "sourceDataset" -> c.provenance.sourceDataset.toString,
      "embeddingModelVersion" -> embeddingModelVersion,
      "textHash" -> c.textHash,
      "runId" -> c.provenance.runId,
      "textRef" -> c.textRef
    )
    if c.topicIds.nonEmpty then m("topicIds") = ujson.Arr.from(c.topicIds.map(ujson.Str(_)))
    if c.institutionIds.nonEmpty then
      m("institutionIds") = ujson.Arr.from(c.institutionIds.map(ujson.Str(_)))
    c.communityId.foreach(id => m("communityId") = id)
    m

  /** Rule violations of one candidate; empty means valid. */
  def problems(c: EmbeddingCandidate, windows: Map[String, TimeWindow]): List[String] =
    def check(ok: Boolean, msg: => String) = if ok then Nil else List(msg)
    val p = c.provenance
    check(c.schemaVersion == SchemaVersion, s"schemaVersion ${c.schemaVersion}") ++
      check(IdFormat.isChunkId(c.vectorKey), s"vectorKey ${c.vectorKey} is not a chunk ID") ++
      check(windows.contains(c.window), s"unknown window ${c.window}") ++
      check(c.text.trim.nonEmpty, "empty text") ++
      check(
        c.text.length <= MaxTextChars,
        s"text has ${c.text.length} characters, max $MaxTextChars"
      ) ++
      check(StableIds.textHash(c.text) == c.textHash, "textHash is not StableIds.textHash(text)") ++
      check(
        c.textRef.startsWith("semantic-context/text/") &&
          c.textRef.endsWith(s"/${c.vectorIndex}/${c.vectorKey}.txt"),
        s"textRef ${c.textRef} is not semantic-context/text/.../${c.vectorIndex}/${c.vectorKey}.txt"
      ) ++
      check(IdFormat.Version.matches(c.templateVersion), "templateVersion") ++
      check(IdFormat.Version.matches(c.selectionPolicyVersion), "selectionPolicyVersion") ++
      check(c.selectionRank >= 1, "selectionRank must be >= 1") ++
      check(
        p.schemaVersion == SchemaVersions.Provenance,
        s"provenance.schemaVersion ${p.schemaVersion}"
      ) ++
      check(IdFormat.isRunId(p.runId), "provenance.runId") ++
      check(ProvenanceRules.Snapshot.matches(p.snapshot), "provenance.snapshot") ++
      check(p.sourceRecordId.trim.nonEmpty, "provenance.sourceRecordId") ++
      // The derived metadata must pass the vector rules, including the size budgets.
      VectorContract.metadataProblems(
        c.vectorIndex,
        c.vectorKey,
        toMetadata(c, "model-version-placeholder")
      )

  /** The `facts` field (SELECTION.md): a non-empty JSON object the rag renderer reads. Its content
    * per document type is checked by the rag render CLI, not here.
    */
  def factsProblems(line: ujson.Value): List[String] = line.obj.get("facts") match
    case Some(o: ujson.Obj) if o.value.nonEmpty => Nil
    case Some(_: ujson.Obj)                     => List("facts is an empty object")
    case Some(_)                                => List("facts must be a JSON object")
    case None                                   => List("facts missing")

  /** Validates candidate files. Returns violations and the candidate count per index. When
    * totalBounds is given, the total count must fall inside it (D-13: 10,000 to 15,000).
    */
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
      Using.resource(Files.lines(f, UTF_8)) { lines =>
        val it = lines.iterator
        var line = 0L
        while it.hasNext do
          val l = it.next()
          line += 1
          if l.trim.nonEmpty then
            Try(ujson.read(l)).flatMap(j => Try((j, read[EmbeddingCandidate](j)))).toEither match
              case Left(e)       => out += Violation(src, line, "MALFORMED", e.getMessage.take(200))
              case Right((j, c)) =>
                (factsProblems(j) ++ problems(c, byWindow))
                  .foreach(p => out += Violation(src, line, "BAD_CANDIDATE", p))
                seen.get(c.vectorKey) match
                  case Some(at) =>
                    out += Violation(src, line, "DUPLICATE_KEY", s"${c.vectorKey} first at $at")
                  case None =>
                    seen(c.vectorKey) = s"$src:$line"
                    counts(c.vectorIndex) = counts.getOrElse(c.vectorIndex, 0) + 1
      }
    }
    val total = counts.values.sum
    totalBounds.foreach { (lo, hi) =>
      if total < lo || total > hi then
        out += Violation("*", 0, "TOTAL_OUT_OF_RANGE", s"$total candidates, expected $lo to $hi")
    }
    (out.result(), counts.toMap)
