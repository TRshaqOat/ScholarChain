package scholarchain.rag.runtime

import scholarchain.storage.{
  AttributeValue,
  DynamoContract,
  Hashing,
  Item,
  LoadOutcome,
  ManifestEntry,
  ObjectLoadPort,
  PutObjectOutcome,
  RuntimeRecordPort,
  TableName
}
import upickle.default.write as json

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** Stage 10 (AUDIT, RUNTIME.md section 14, ruling 10): a write-once audit object under logs/, the
  * answer object under reports/, and create-only QuerySession and EvidenceBundle items with a TTL.
  * Nothing written holds the question text, prompt text, model output text, or credentials.
  */
object Audit:
  def base(prefix: String, snapshot: String, runId: String) =
    s"$prefix/rag-runtime/snapshot=$snapshot/run=$runId"
  def auditKey(snapshot: String, runId: String, queryId: String) =
    s"${base("logs", snapshot, runId)}/$queryId.json"
  def answerKey(snapshot: String, runId: String, queryId: String) =
    s"${base("reports", snapshot, runId)}/$queryId.answer.json"
  def evidenceKey(snapshot: String, runId: String, queryId: String, evidenceId: String) =
    s"${base("logs", snapshot, runId)}/$queryId/evidence/$evidenceId.json"
  def manifestKey(snapshot: String, runId: String, queryId: String) =
    s"${base("runs", snapshot, runId)}/manifest-$queryId.json"

  private def S(v: String) = AttributeValue.S(v)
  private def N(v: Long) = AttributeValue.N(v.toString)

  /** Writes one object; an existing key with other content is an error (write-once). */
  def putOnce(
      objects: ObjectLoadPort,
      key: String,
      bytes: Array[Byte],
      source: String
  ): Either[String, ManifestEntry] =
    objects.putNew(key, bytes) match
      case Left(ps)                              => Left(s"$key: ${ps.mkString("; ")}")
      case Right(PutObjectOutcome.Conflict(sha)) => Left(s"$key exists with other content ($sha)")
      case Right(_) => Right(ManifestEntry(key, Hashing.sha256(bytes), bytes.length.toLong, source))

  private def create(records: RuntimeRecordPort, t: TableName, item: Item): Either[String, Unit] =
    records.create(t, item) match
      case Right(LoadOutcome.Inserted | LoadOutcome.Unchanged) => Right(())
      case Right(LoadOutcome.Conflict) => Left(s"$t item exists with other content")
      case Left(ps)                    => Left(s"$t: ${ps.mkString("; ")}")

  def sessionItem(a: AuditRecord, ttlDays: Int, createdAt: Instant, key: String): Item =
    Map(
      "sessionId" -> S(a.sessionId.getOrElse(a.queryId)),
      "turnKey" -> S(s"${a.createdAt}#${a.queryId}"),
      "queryId" -> S(a.queryId),
      "createdAt" -> S(a.createdAt),
      DynamoContract.TtlAttribute -> N(createdAt.getEpochSecond + ttlDays.toLong * 86400),
      "schemaVersion" -> S(RuntimeSchemas.QuerySessionItem),
      "questionHash" -> S(a.questionHash),
      "auditKey" -> S(key),
      "answerHash" -> S(a.answerHash),
      "abstained" -> AttributeValue.Bool(a.abstained),
      "verified" -> N(a.verification.verified),
      "partiallySupported" -> N(a.verification.partiallySupported),
      "unsupported" -> N(a.verification.unsupported),
      "conflicting" -> N(a.verification.conflicting),
      "runId" -> S(a.runId)
    )

  /** BUNDLE header plus one EV# item per evidence item; an item above the 32 KB item budget stores
    * its payload as a write-once object (payloadRef, payloadHash).
    */
  def bundleItems(
      b: EvidenceBundle,
      expiresAt: Long,
      snapshot: String,
      runId: String,
      objects: ObjectLoadPort
  ): Either[String, (Vector[Item], Vector[ManifestEntry])] =
    val common = Map(
      "queryId" -> S(b.queryId),
      DynamoContract.TtlAttribute -> N(expiresAt),
      "schemaVersion" -> S(RuntimeSchemas.EvidenceBundleItem)
    )
    val header = common ++ Map(
      "itemKey" -> S("BUNDLE"),
      "bundleSchemaVersion" -> S(b.schemaVersion),
      "graphRunId" -> S(b.graphRunId),
      "windows" -> S(b.windows.map(_.window).mkString(" ")),
      "graphEvidenceIds" -> S(b.graphEvidence.map(_.evidenceId).mkString(" ")),
      "semanticEvidenceIds" -> S(b.semanticEvidence.map(_.evidenceId).mkString(" ")),
      "missing" -> S(json(b.missing)),
      "budget" -> S(json(b.budget)),
      "payload" -> S(json(b.provenance))
    )
    val entries = Vector.newBuilder[ManifestEntry]
    val items = (b.graphEvidence ++ b.semanticEvidence).map { e =>
      val text = json(e)
      val inline = common ++ Map("itemKey" -> S(s"EV#${e.evidenceId}"), "payload" -> S(text))
      if scholarchain.storage.ItemSize.of(inline) <= DynamoContract.ItemBudgetBytes then
        Right(inline)
      else
        val key = evidenceKey(snapshot, runId, b.queryId, e.evidenceId)
        val bytes = text.getBytes(UTF_8)
        putOnce(objects, key, bytes, s"evidence ${e.evidenceId}").map { m =>
          entries += m
          common ++ Map(
            "itemKey" -> S(s"EV#${e.evidenceId}"),
            "payloadRef" -> S(key),
            "payloadHash" -> S(m.sha256)
          )
        }
    }
    items.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    => Right((header +: items.collect { case Right(i) => i }, entries.result()))

  /** Writes everything for one question; returns the manifest entries of the objects written. */
  def persist(
      answer: RuntimeAnswer,
      audit: AuditRecord,
      bundle: Option[EvidenceBundle],
      createdAt: Instant,
      ttlDays: Int,
      snapshot: String,
      objects: ObjectLoadPort,
      records: RuntimeRecordPort
  ): Either[String, Vector[ManifestEntry]] =
    val key = auditKey(snapshot, audit.runId, audit.queryId)
    val expires = createdAt.getEpochSecond + ttlDays.toLong * 86400
    for
      a <- putOnce(objects, key, json(audit, indent = 2).getBytes(UTF_8), s"audit ${audit.queryId}")
      r <- putOnce(
        objects,
        answerKey(snapshot, audit.runId, audit.queryId),
        json(answer, indent = 2).getBytes(UTF_8),
        s"answer ${audit.queryId}"
      )
      _ <- create(records, TableName.QuerySession, sessionItem(audit, ttlDays, createdAt, key))
      ev <- bundle.fold[Either[String, Vector[ManifestEntry]]](Right(Vector.empty)) { b =>
        bundleItems(b, expires, snapshot, audit.runId, objects).flatMap { (items, es) =>
          items
            .foldLeft[Either[String, Unit]](Right(()))((acc, i) =>
              acc.flatMap(_ => create(records, TableName.EvidenceBundle, i))
            )
            .map(_ => es)
        }
      }
    yield Vector(a, r) ++ ev
