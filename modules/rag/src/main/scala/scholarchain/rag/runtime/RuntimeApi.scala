package scholarchain.rag.runtime

import scholarchain.core.{EvidenceItem, IdFormat}
import scholarchain.storage.{
  AttributeValue,
  Hashing,
  Item,
  KeyValueReadPort,
  ObjectReadPort,
  TableName
}
import upickle.default.{read, ReadWriter}

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/** The service-facing runtime API (Phase 10 work order 1): plain Scala, no HTTP. Routes call these
  * and add no retrieval, verification, or store logic of their own (Phase 10 ruling 3).
  */
object RuntimeApi:

  /** Answers one structured request (the POST /v1/query fields as a QueryRequest) with the given
    * ports, under a deadline of deadlineMs from now. The result holds the answer, the audit record,
    * the evidence bundle (None when the answer abstained before building one), and deadlinePassed.
    * Left is a stage failure (a store error, a refused write, or a malformed question or query ID).
    */
  def answer(
      request: QueryRequest,
      ports: RuntimePorts,
      setup: RuntimeSetup,
      deadlineMs: Long
  ): Either[RuntimeFailure, RuntimeResult] =
    Workflow.answer(request, ports, setup, Some(deadlineMs))

  private def readObject[A: ReadWriter](
      objects: ObjectReadPort,
      key: String
  ): Either[String, Option[A]] =
    objects.get(key) match
      case None        => Right(None)
      case Some(bytes) =>
        Try(read[A](new String(bytes, UTF_8))).toEither.left
          .map(e => s"$key does not decode: ${e.getClass.getSimpleName}")
          .map(Some(_))

  /** The audit record of a query of this runtime run (setup snapshot and run ID). Right(None) when
    * there is none, or when queryId is not qry-<32 hex> (no key is built from other text).
    */
  def audit(
      queryId: String,
      setup: RuntimeSetup,
      audits: ObjectReadPort
  ): Either[String, Option[AuditRecord]] =
    if !IdFormat.isQueryId(queryId) then Right(None)
    else readObject[AuditRecord](audits, Audit.auditKey(setup.snapshot, setup.runId, queryId))

  /** The stored answer of a query of this runtime run; Right(None) as for audit. */
  def storedAnswer(
      queryId: String,
      setup: RuntimeSetup,
      audits: ObjectReadPort
  ): Either[String, Option[RuntimeAnswer]] =
    if !IdFormat.isQueryId(queryId) then Right(None)
    else readObject[RuntimeAnswer](audits, Audit.answerKey(setup.snapshot, setup.runId, queryId))

  /** The audit without model text (Phase 10 ruling 8): removed claims and left-out lines keep their
    * IDs, statuses, checks, and reasons, never their text.
    */
  def withoutModelText(a: AuditRecord): AuditRecord =
    a.copy(
      removedClaims = a.removedClaims.map(_.copy(text = "")),
      droppedLines = a.droppedLines.map(_.copy(text = ""))
    )

  /** Whether the answer used no language model (fake or stub mode): only then is the plan from the
    * rule planner (PlannerSource.RULES); a live model run plans MODEL or FALLBACK_RULES.
    */
  def llmStubbed(a: AuditRecord): Boolean = a.plan.source == PlannerSource.RULES

  /** One EvidenceBundle EV# item as the core EvidenceItem: the inline payload, or the payloadRef
    * object, whose sha256 must equal payloadHash.
    */
  def evidenceOf(item: Item, objects: ObjectReadPort): Either[String, EvidenceItem] =
    def s(k: String) = item.get(k).collect { case AttributeValue.S(v) => v }
    val text = (s("payload"), s("payloadRef")) match
      case (Some(p), None)   => Right(p)
      case (None, Some(ref)) =>
        objects.get(ref) match
          case None                                                    => Left(s"$ref is missing")
          case Some(b) if s("payloadHash").contains(Hashing.sha256(b)) =>
            Right(new String(b, UTF_8))
          case Some(_) => Left(s"$ref does not match its payloadHash")
      case _ => Left("the item has neither one payload nor one payloadRef")
    text.flatMap(t =>
      Try(read[EvidenceItem](t)).toEither.left.map(e =>
        s"payload does not decode: ${e.getClass.getSimpleName}"
      )
    )

  /** An evidence item of a query's bundle (EvidenceBundle table, key queryId and EV#<evidenceId>).
    * Right(None) when absent or when either ID is malformed. A lookup by evidence ID alone needs a
    * storage index (reported to storage-engineer, Phase 10 work order 4).
    */
  def evidence(
      queryId: String,
      evidenceId: String,
      records: KeyValueReadPort,
      objects: ObjectReadPort
  ): Either[String, Option[EvidenceItem]] =
    if !IdFormat.isQueryId(queryId) || !IdFormat.isEvidenceId(evidenceId) then Right(None)
    else
      records
        .get(TableName.EvidenceBundle, queryId, s"EV#$evidenceId")
        .fold[Either[String, Option[EvidenceItem]]](Right(None))(i =>
          evidenceOf(i, objects).map(Some(_))
        )
