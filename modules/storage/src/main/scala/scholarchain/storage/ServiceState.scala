package scholarchain.storage

import scholarchain.core.{ErrorCode, IdFormat, JobState, StableIds}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.util.Try

/** Why a job FAILED: a core error code and a short message the service wrote (never user text,
  * prompt text, model output, or keys).
  */
final case class JobError(code: ErrorCode, message: String)

/** One async query job (Phase 10 ruling 7), item JOB in the ServiceState table. request is the
  * canonical JSON of the accepted POST /v1/query body. stage is a workflow stage name. resultRef is
  * an optional S3 key of the stored answer, set on SUCCEEDED. Timestamps are UTC, in milliseconds.
  */
final case class JobRecord(
    jobId: String,
    queryId: String,
    state: JobState,
    stage: Option[String],
    request: String,
    createdAt: Instant,
    updatedAt: Instant,
    startedAt: Option[Instant],
    finishedAt: Option[Instant],
    error: Option[JobError],
    resultRef: Option[String],
    expiresAt: Long
)

/** One Idempotency-Key (Phase 10 ruling 7), item IDEMPOTENCY in the ServiceState table. recordKey
  * is idem-<sha256 hex of the key>, so the header value itself is never stored. queryId is the
  * query the first request started: its answer, or job StableIds.jobId(queryId), is the stored
  * response.
  */
final case class IdempotencyRecord(
    recordKey: String,
    requestHash: String,
    queryId: String,
    createdAt: Instant,
    expiresAt: Long
)

/** A conditional create: Inserted (this call wrote it), Unchanged (the same record was already
  * there), or Conflict (a different record holds the key; nothing was written). record is the one
  * now stored under the key.
  */
final case class Stored[A](outcome: LoadOutcome, record: A)

enum JobTransition:
  /** The conditional write passed; job is the stored record. */
  case Moved(job: JobRecord)

  /** The job was not in the expected state (another worker moved it first) or does not exist. */
  case Refused(current: Option[JobRecord])

/** Item layout and rules of the ServiceState table (STORAGE.md, "Service state"). */
object ServiceStateContract:
  val JobType = "JOB"
  val IdempotencyType = "IDEMPOTENCY"
  val JobSchema = "service-job-v1"
  val IdempotencySchema = "service-idempotency-v1"

  /** A job holds its request (services.max-body-bytes is 64 KiB), so this table's budget is 96 KB,
    * not the 32 KB of the others; still far under the 400 KB DynamoDB item limit.
    */
  val ItemBudgetBytes = 96 * 1024
  val MaxErrorMessageChars = 500
  private val StageName = "[A-Za-z][A-Za-z0-9_.-]{0,63}".r
  private val IdemKey = "idem-[0-9a-f]{64}".r

  def idempotencyRecordKey(key: String): String =
    "idem-" + Hashing.sha256(key).stripPrefix("sha256:")

  import AttributeValue.*

  private def s(i: Item, k: String) = i.get(k).collect { case S(v) => v }
  private def n(i: Item, k: String) =
    i.get(k).collect { case N(v) => Try(BigDecimal(v).toLongExact).toOption }.flatten
  private def time(i: Item, k: String): Either[String, Option[Instant]] =
    s(i, k) match
      case None    => Right(None)
      case Some(v) =>
        Try(Instant.parse(v)).toEither.left.map(_ => s"$k is not an instant").map(Some(_))
  private def opt(k: String, v: Option[String]) = v.map(x => k -> S(x))

  def jobItem(j: JobRecord): Item =
    Map(
      "recordKey" -> S(j.jobId),
      "recordType" -> S(JobType),
      "queryId" -> S(j.queryId),
      "state" -> S(j.state.toString),
      "request" -> S(j.request),
      "createdAt" -> S(j.createdAt.toString),
      "updatedAt" -> S(j.updatedAt.toString),
      DynamoContract.TtlAttribute -> AttributeValue.num(j.expiresAt),
      "schemaVersion" -> S(JobSchema)
    ) ++ opt("stage", j.stage) ++ opt("startedAt", j.startedAt.map(_.toString)) ++
      opt("finishedAt", j.finishedAt.map(_.toString)) ++
      opt("errorCode", j.error.map(_.code.toString)) ++
      opt("errorMessage", j.error.map(_.message)) ++ opt("resultRef", j.resultRef)

  /** Decodes a JOB item and checks every rule; Left names the first broken one. */
  def job(i: Item): Either[String, JobRecord] =
    for
      _ <- Either.cond(s(i, "recordType").contains(JobType), (), "recordType is not JOB")
      queryId <- s(i, "queryId").filter(IdFormat.isQueryId).toRight("queryId is not a query ID")
      jobId <- s(i, "recordKey")
        .filter(_ == StableIds.jobId(queryId))
        .toRight("recordKey is not jobId(queryId)")
      state <- s(i, "state")
        .flatMap(x => JobState.values.find(_.toString == x))
        .toRight("state is not a JobState")
      request <- s(i, "request")
        .filter(r => Try(ujson.read(r)).toOption.exists(_.objOpt.isDefined))
        .toRight("request is not a JSON object")
      stage <- s(i, "stage") match
        case Some(x) if !StageName.matches(x) => Left("stage is not a stage name")
        case other                            => Right(other)
      created <- time(i, "createdAt").flatMap(_.toRight("missing createdAt"))
      updated <- time(i, "updatedAt").flatMap(_.toRight("missing updatedAt"))
      started <- time(i, "startedAt")
      finished <- time(i, "finishedAt")
      error <- (s(i, "errorCode"), s(i, "errorMessage")) match
        case (None, None)       => Right(None)
        case (Some(c), Some(m)) =>
          ErrorCode.values
            .find(_.toString == c)
            .filter(_ => m.nonEmpty && m.length <= MaxErrorMessageChars)
            .map(code => Some(JobError(code, m)))
            .toRight(s"error must be a known code and 1 to $MaxErrorMessageChars characters")
        case _ => Left("errorCode and errorMessage go together")
      resultRef = s(i, "resultRef")
      expiresAt <- n(i, DynamoContract.TtlAttribute).toRight("expiresAt is not epoch seconds")
      _ <- Either.cond(error.isDefined == (state == JobState.FAILED), (), "error iff FAILED")
      _ <- Either.cond(
        finished.isDefined == state.isFinal,
        (),
        "finishedAt iff SUCCEEDED or FAILED"
      )
      _ <- Either.cond(
        started.isDefined || state == JobState.QUEUED || state == JobState.FAILED,
        (),
        "RUNNING and SUCCEEDED need startedAt"
      )
      _ <- Either.cond(
        !(state == JobState.QUEUED && started.isDefined),
        (),
        "QUEUED has no startedAt"
      )
      _ <- resultRef match
        case None                                   => Right(())
        case Some(_) if state != JobState.SUCCEEDED => Left("resultRef only on SUCCEEDED")
        case Some(r) => S3Layout.keyProblems(r).headOption.map(p => s"resultRef: $p").toLeft(())
    yield JobRecord(
      jobId,
      queryId,
      state,
      stage,
      request,
      created,
      updated,
      started,
      finished,
      error,
      resultRef,
      expiresAt
    )

  def idempotencyItem(r: IdempotencyRecord): Item =
    Map(
      "recordKey" -> S(r.recordKey),
      "recordType" -> S(IdempotencyType),
      "requestHash" -> S(r.requestHash),
      "queryId" -> S(r.queryId),
      "createdAt" -> S(r.createdAt.toString),
      DynamoContract.TtlAttribute -> AttributeValue.num(r.expiresAt),
      "schemaVersion" -> S(IdempotencySchema)
    )

  def idempotency(i: Item): Either[String, IdempotencyRecord] =
    for
      _ <- Either.cond(s(i, "recordType").contains(IdempotencyType), (), "not IDEMPOTENCY")
      key <- s(i, "recordKey").filter(IdemKey.matches).toRight("recordKey is not idem-<sha256>")
      hash <- s(i, "requestHash").filter(Hashing.Shape.matches).toRight("requestHash")
      queryId <- s(i, "queryId").filter(IdFormat.isQueryId).toRight("queryId is not a query ID")
      created <- time(i, "createdAt").flatMap(_.toRight("missing createdAt"))
      expiresAt <- n(i, DynamoContract.TtlAttribute).toRight("expiresAt is not epoch seconds")
    yield IdempotencyRecord(key, hash, queryId, created, expiresAt)

  /** The table rule (DynamoContract.ServiceState): the item decodes as its recordType. */
  def problems(i: Item): List[String] =
    (s(i, "recordType") match
      case Some(JobType)         => job(i).map(_ => ())
      case Some(IdempotencyType) => idempotency(i).map(_ => ())
      case _                     => Left("recordType is not JOB or IDEMPOTENCY")
    ).left.toOption.toList

  /** Every table rule (keys, sizes, TTL, schemaVersion, decoding) of one item. */
  def validate(i: Item): Either[String, Unit] =
    DynamoContract.problems(DynamoContract.ServiceState, i, Map.empty) match
      case Nil => Right(())
      case ps  => Left(ps.mkString("; "))

/** The ServiceState table as JobStore and IdempotencyStore need it: consistent reads and two
  * conditional puts. LocalServiceStateTable is the fake, live.DynamoServiceStateTable the DynamoDB
  * adapter. Items reaching a put have passed ServiceStateContract.validate.
  */
trait ServiceStateTable:
  /** The unexpired item under the key, or None. */
  def get(recordKey: String, recordType: String): Option[Item]

  /** Writes item when no unexpired item has its key; else Left(the stored item), nothing written.
    */
  def putIfAbsent(item: Item): Either[Item, Unit]

  /** Writes item when the stored item's state is expected; else Left(the stored item, if any). */
  def putIfState(item: Item, expected: JobState): Either[Option[Item], Unit]

/** Local fake of the ServiceState table, thread-safe. With log = None it is in memory only. With
  * log = Some(file) (for example scratch/services/<runId>/ServiceState.jsonl) every accepted write
  * is appended as one item-file line before it is applied, and the log is replayed on open, the
  * last line of a key winning. A partial last line left by a crash was never acknowledged and is
  * cut; any other unreadable line stops the open. Reads hide expired items, as DynamoDB reads must.
  */
final class LocalServiceStateTable(log: Option[Path], now: () => Instant) extends ServiceStateTable:
  private val items = mutable.HashMap.empty[(String, String), Item]
  private val spec = DynamoContract.ServiceState

  log.filter(Files.exists(_)).foreach { f =>
    val bytes = Files.readAllBytes(f)
    val complete = bytes.lastIndexOf('\n'.toByte) + 1
    if complete < bytes.length then
      scala.util.Using.resource(java.nio.channels.FileChannel.open(f, StandardOpenOption.WRITE))(
        _.truncate(complete.toLong)
      )
    new String(bytes, 0, complete, UTF_8)
      .split('\n')
      .iterator
      .filter(_.nonEmpty)
      .zipWithIndex
      .foreach { (l, k) =>
        DynamoItemFile.parseLine(l).flatMap(i => ServiceStateContract.validate(i).map(_ => i)) match
          case Right(i) => items(DynamoContract.key(spec, i)) = i
          case Left(e)  => throw IllegalStateException(s"$f line ${k + 1}: cannot replay ($e)")
      }
  }

  private def unexpired(i: Item) = DynamoContract.unexpired(spec, i, now().getEpochSecond)

  private def write(item: Item): Unit =
    log.foreach { f =>
      Option(f.getParent).foreach(Files.createDirectories(_))
      Files.writeString(
        f,
        DynamoItemFile.renderLine(item) + "\n",
        UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    }
    items(DynamoContract.key(spec, item)) = AttributeValue.canonicalItem(item)

  def get(recordKey: String, recordType: String): Option[Item] = synchronized(
    items.get((recordKey, recordType)).filter(unexpired)
  )

  def putIfAbsent(item: Item): Either[Item, Unit] = synchronized {
    items.get(DynamoContract.key(spec, item)).filter(unexpired) match
      case Some(stored) => Left(stored)
      case None         => Right(write(item))
  }

  def putIfState(item: Item, expected: JobState): Either[Option[Item], Unit] = synchronized {
    val stored = items.get(DynamoContract.key(spec, item))
    if stored.exists(_.get("state").contains(AttributeValue.S(expected.toString))) then
      Right(write(item))
    else Left(stored)
  }

private def millis(t: Instant): Instant = t.truncatedTo(ChronoUnit.MILLIS)

/** Async query jobs (Phase 10 ruling 7) over a ServiceStateTable. One job per query: jobId =
  * StableIds.jobId(queryId). Every write is conditional: create only when no unexpired job has the
  * ID, and a transition only from the state the caller names, only along JobState.canMoveTo. The
  * transition graph has no cycle, so the state condition alone means nobody moved the job in
  * between, and a redelivered message can never run a job twice. expiresAt = createdAt + ttlHours
  * (services.ttl.job-hours) and is kept by transitions.
  */
final class JobStore(table: ServiceStateTable, ttlHours: Int, now: () => Instant):
  require(ttlHours >= 1, s"ttlHours $ttlHours must be at least 1")

  /** A QUEUED job for queryId. Unchanged when the same queryId and request are already queued (a
    * retried POST); Conflict when another request holds the job ID. Left for a malformed queryId or
    * request (nothing written).
    */
  def create(queryId: String, request: String): Either[String, Stored[JobRecord]] =
    if !IdFormat.isQueryId(queryId) then Left("queryId is not a query ID")
    else
      val t = millis(now())
      val job = JobRecord(
        StableIds.jobId(queryId),
        queryId,
        JobState.QUEUED,
        None,
        request,
        t,
        t,
        None,
        None,
        None,
        None,
        t.getEpochSecond + ttlHours * 3600L
      )
      val item = ServiceStateContract.jobItem(job)
      ServiceStateContract.validate(item).flatMap { _ =>
        table.putIfAbsent(item) match
          case Right(())    => Right(Stored(LoadOutcome.Inserted, job))
          case Left(stored) =>
            ServiceStateContract
              .job(stored)
              .map(s =>
                Stored(
                  if s.queryId == queryId && s.request == request then LoadOutcome.Unchanged
                  else LoadOutcome.Conflict,
                  s
                )
              )
      }

  /** The unexpired job; Right(None) when there is none or jobId is not job-<32 hex>. Left when the
    * stored item does not decode.
    */
  def get(jobId: String): Either[String, Option[JobRecord]] =
    if !IdFormat.isJobId(jobId) then Right(None)
    else
      table.get(jobId, ServiceStateContract.JobType) match
        case None    => Right(None)
        case Some(i) => ServiceStateContract.job(i).map(Some(_))

  /** Moves the job from `from` to `to` with a conditional write. RUNNING sets startedAt; SUCCEEDED
    * and FAILED set finishedAt; FAILED needs error; resultRef only with SUCCEEDED; stage, when
    * given, replaces the stored one. Refused when the job is not in `from` (for example a second
    * worker on a redelivered message). Left when `from -> to` is not a JobState transition or the
    * new record breaks a rule (nothing written).
    */
  def transition(
      jobId: String,
      from: JobState,
      to: JobState,
      stage: Option[String] = None,
      error: Option[JobError] = None,
      resultRef: Option[String] = None
  ): Either[String, JobTransition] =
    if !from.canMoveTo(to) then Left(s"$from -> $to is not a job transition")
    else
      get(jobId).flatMap {
        case None                           => Right(JobTransition.Refused(None))
        case Some(cur) if cur.state != from => Right(JobTransition.Refused(Some(cur)))
        case Some(cur)                      =>
          val t = millis(now())
          val next = cur.copy(
            state = to,
            stage = stage.orElse(cur.stage),
            updatedAt = t,
            startedAt = if to == JobState.RUNNING then Some(t) else cur.startedAt,
            finishedAt = Option.when(to.isFinal)(t),
            error = error,
            resultRef = resultRef
          )
          val item = ServiceStateContract.jobItem(next)
          ServiceStateContract.validate(item).flatMap { _ =>
            table.putIfState(item, from) match
              case Right(())        => Right(JobTransition.Moved(next))
              case Left(None)       => Right(JobTransition.Refused(None))
              case Left(Some(have)) =>
                ServiceStateContract.job(have).map(j => JobTransition.Refused(Some(j)))
          }
      }

/** Idempotency-Key records (Phase 10 ruling 7) over a ServiceStateTable. The key is stored only as
  * its SHA-256. expiresAt = createdAt + ttlHours (services.ttl.idempotency-hours); an expired
  * record no longer exists for reads or claims.
  */
final class IdempotencyStore(table: ServiceStateTable, ttlHours: Int, now: () => Instant):
  require(ttlHours >= 1, s"ttlHours $ttlHours must be at least 1")

  /** Claims key for a request (requestHash: sha256:<hex> of the canonical body) that starts
    * queryId. Inserted: this request owns the key, go on with queryId. Unchanged: the same body
    * claimed it before; replay the stored record's queryId (whose answer may still be running).
    * Conflict: another body holds the key (409). Left for invalid input (nothing written).
    */
  def claim(
      key: String,
      requestHash: String,
      queryId: String
  ): Either[String, Stored[IdempotencyRecord]] =
    if key.isEmpty then Left("empty idempotency key")
    else if !Hashing.Shape.matches(requestHash) then Left("requestHash is not sha256:<64 hex>")
    else if !IdFormat.isQueryId(queryId) then Left("queryId is not a query ID")
    else
      val t = millis(now())
      val r = IdempotencyRecord(
        ServiceStateContract.idempotencyRecordKey(key),
        requestHash,
        queryId,
        t,
        t.getEpochSecond + ttlHours * 3600L
      )
      val item = ServiceStateContract.idempotencyItem(r)
      ServiceStateContract.validate(item).flatMap { _ =>
        table.putIfAbsent(item) match
          case Right(())    => Right(Stored(LoadOutcome.Inserted, r))
          case Left(stored) =>
            ServiceStateContract
              .idempotency(stored)
              .map(s =>
                Stored(
                  if s.requestHash == requestHash then LoadOutcome.Unchanged
                  else LoadOutcome.Conflict,
                  s
                )
              )
      }

  /** The unexpired record of key, if any. */
  def get(key: String): Either[String, Option[IdempotencyRecord]] =
    if key.isEmpty then Right(None)
    else
      table
        .get(ServiceStateContract.idempotencyRecordKey(key), ServiceStateContract.IdempotencyType)
        .fold[Either[String, Option[IdempotencyRecord]]](Right(None))(i =>
          ServiceStateContract.idempotency(i).map(Some(_))
        )
