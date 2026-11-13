package scholarchain.services

import scholarchain.core.{ErrorCode, IdFormat, JobState, StableIds}
import scholarchain.core.config.ScholarChainConfig
import scholarchain.rag.runtime.{Audit, RuntimeApi, RuntimeSetup}
import scholarchain.storage.{
  Direction,
  JobError,
  JobMessage,
  JobRecord,
  LoadOutcome,
  NeptuneContract,
  Stored
}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.{Failure, Success, Try}
import upickle.default.write

// Routes are thin (ruling 3): they validate, translate, call one RuntimeApi function or one port
// under a CallPolicy, and map the outcome to a status. No retrieval, verification, query building,
// or store logic lives here.

/** Downstream call policies from configuration (services.timeouts, ruling 9). Neptune and the
  * DynamoDB service-state adapters retry reads themselves (live adapters get the services
  * RetryPolicy), so only S3 reads retry here: one retry layer. Conditional writes never retry.
  */
final class Policies(cfg: ScholarChainConfig):
  private val t = cfg.services.timeouts
  val neptune = CallPolicy("neptune", t.neptuneMs.toLong)
  val dynamodb = CallPolicy("dynamodb", t.dynamoDbMs.toLong)
  val s3 = CallPolicy("s3", t.s3Ms.toLong, retryReads = true)
  // SQS calls are never retried (ruling 9: reads only; a receive is not idempotent).
  val sqs = CallPolicy("sqs", t.sqsMs.toLong)
  val syncDeadlineMs: Long = cfg.operations.synchronousDeadlineMs.toLong

  /** The whole synchronous workflow, on the workflow pool: a hard timeout at the sync deadline (the
    * runtime's deadline is cooperative). A passed deadline is 504 and is no downstream failure; a
    * RuntimeFailure is.
    */
  val runtime = CallPolicy(
    "runtime",
    syncDeadlineMs,
    timeoutCode = ErrorCode.DEADLINE_EXCEEDED,
    timeoutIsFailure = false,
    interruptOnTimeout = false,
    workflow = true
  )

object Probes:
  /** Keys and IDs no store holds; a probe read proves the store answers, not that data exists. */

  /** Under the audit prefix of the served runtime run, which query-api and the worker already read
    * (repair-01 m1; SERVICES.md section 4 names the S3 permissions).
    */
  def objectKey(setup: RuntimeSetup): String =
    s"${Audit.base("logs", setup.snapshot, setup.runId)}/readiness-probe"
  val NodeId = "probe:readiness"
  val EvidenceId: String = "ev-" + "0" * 32
  val IdempotencyKey = "readiness-probe"
  val JobId: String = "job-" + "0" * 32

  def fail(e: Either[String, ?]): Unit = e.left.foreach(m => throw DownstreamFailed(m))

/** GET /healthz (liveness: no downstream), GET /readyz (readiness: probes and breakers), and GET
  * /metrics (API key required, ruling 1). Neither health route reveals configuration.
  */
object Ops:
  def routes(
      readiness: Vector[(String, () => Unit)],
      ds: Downstreams,
      metrics: Metrics,
      metricsEnabled: Boolean
  ): Seq[Route] =
    Seq(
      Route("GET", "/healthz", public = true)(_ => Response.ok(Status("ok"))),
      Route("GET", "/readyz", public = true) { _ =>
        val failing = readiness.filterNot((_, probe) => Try(probe()).isSuccess).map(_._1)
        val down = (failing ++ ds.openBreakers).distinct.sorted
        if down.isEmpty then Response.ok(Status("ready"))
        else throw ApiError(ErrorCode.NOT_READY, s"not ready: ${down.mkString(", ")} unavailable")
      }
    ) ++ Option
      .when(metricsEnabled)(Route("GET", "/metrics")(_ => Response(200, metrics.snapshot)))
      .toSeq

  def noQuery(r: Request): Unit =
    if r.query.nonEmpty then
      throw ApiError(ErrorCode.INVALID_FIELD, "this route takes no query parameters")

/** query-api (ruling 1): POST /v1/query (sync or async), GET /v1/jobs/{jobId}, GET
  * /v1/jobs/{jobId}/result, and GET /v1/explain/{queryId}.
  */
final class QueryService(
    cfg: ScholarChainConfig,
    backend: Backend,
    ds: Downstreams,
    log: JsonLog,
    metrics: Metrics,
    limiter: RateLimiter
):
  private val p = Policies(cfg)
  private val setup = backend.setup
  private val ports = backend.ports
  private val IdempotencyKeyMaxChars = 255

  def routes: Seq[Route] = Seq(
    Route("POST", "/v1/query")(query),
    Route("GET", "/v1/jobs/{jobId}")(job),
    Route("GET", "/v1/jobs/{jobId}/result")(result),
    Route("GET", "/v1/explain/{queryId}")(explain)
  )

  def readiness: Vector[(String, () => Unit)] = Vector(
    "s3" -> (() => ds.call(p.s3)(ports.auditReader.get(Probes.objectKey(setup)))),
    "dynamodb" -> (() =>
      ds.call(p.dynamodb)(Probes.fail(backend.idempotency.get(Probes.IdempotencyKey)))
    ),
    "neptune" -> (() => ds.call(p.neptune)(ports.graph.vertex(Probes.NodeId)))
  )

  private def query(r: Request): Response =
    Ops.noQuery(r)
    val key = r.header("Idempotency-Key")
    if key.exists(k => !IdFormat.isVisibleAscii(k) || k.length > IdempotencyKeyMaxChars) then
      throw ApiError(
        ErrorCode.INVALID_FIELD,
        s"Idempotency-Key must be 1 to $IdempotencyKeyMaxChars visible ASCII characters"
      )
    val (body, question) = QueryValidation.parse(r.json(), cfg)
    if !limiter.tryAcquire() then
      throw ApiError(
        ErrorCode.RATE_LIMITED,
        s"at most ${cfg.services.queryApi.queryRatePerMinute} queries per minute per instance"
      )
    val hash = QueryValidation.requestHash(body)
    // A fresh query ID per POST (the nonce): two equal requests without a key are two queries.
    val minted = StableIds.queryId(
      setup.runId,
      StableIds.textHash(question),
      StableIds.textHash(s"$hash ${Ids.fresh("nonce-")}"),
      1
    )
    def run(): Response =
      if body.execution == "async" then enqueue(r, body, minted) else answer(r, body, minted)
    key match
      case None    => run()
      case Some(k) =>
        ds.call(p.dynamodb)(backend.idempotency.claim(k, hash, minted)) match
          case Right(Stored(LoadOutcome.Inserted, _))  => run()
          case Right(Stored(LoadOutcome.Unchanged, s)) => replay(s.queryId)
          case Right(Stored(LoadOutcome.Conflict, _))  =>
            throw ApiError(
              ErrorCode.IDEMPOTENCY_CONFLICT,
              "this Idempotency-Key was used with a different request body"
            )
          case Left(_) =>
            throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the idempotency record was not stored")

  private def answer(r: Request, body: QueryBody, queryId: String): Response =
    val result = ds.call(p.runtime) {
      RuntimeApi.answer(
        QueryValidation.toRuntime(body, queryId),
        ports,
        setup,
        p.syncDeadlineMs
      ) match
        case Right(res)                  => res
        case Left(f) if f.deadlinePassed =>
          throw ApiError(
            ErrorCode.DEADLINE_EXCEEDED,
            s"the synchronous deadline of ${p.syncDeadlineMs} ms passed before a plan was made"
          )
        case Left(f) => throw DownstreamFailed(f.stage.toString)
    }
    metrics.answer(result.audit)
    result.audit.stages.foreach(log.stage(r, queryId, _))
    if result.deadlinePassed then
      throw ApiError(
        ErrorCode.DEADLINE_EXCEEDED,
        s"the synchronous deadline of ${p.syncDeadlineMs} ms passed; the abstention is recorded as $queryId"
      )
    Response.ok(SyncQueryResponse.of(result.answer))

  /** Async (ruling 7): a QUEUED job record holding the canonical request, then one message holding
    * only the job and query IDs; 202 with the job reference. A message that cannot be sent fails
    * the job at once (QUEUED -> FAILED), so no job waits for a message that never comes.
    */
  private def enqueue(r: Request, body: QueryBody, queryId: String): Response =
    val jobId = StableIds.jobId(queryId)
    val request = write(body)
    def stored(j: JobRecord) = j.queryId == queryId && j.request == request
    val job = Try(ds.call(p.dynamodb)(backend.jobs.create(queryId, request))) match
      case Success(Right(Stored(LoadOutcome.Conflict, _))) | Success(Left(_)) =>
        throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the job record was not stored")
      case Success(Right(s)) => s.record
      // A timed-out conditional put has an unknown outcome: read the record back (WO4).
      case Failure(e: ApiError) => readJob(jobId).filter(stored).getOrElse(throw e)
      case Failure(e)           => throw e
    Try(ds.call(p.sqs)(backend.queue.send(JobMessage.of(queryId)))) match
      case Success(_) => ()
      case Failure(e) =>
        Try(
          ds.call(p.dynamodb)(
            backend.jobs.transition(
              jobId,
              JobState.QUEUED,
              JobState.FAILED,
              error = Some(
                JobError(ErrorCode.DOWNSTREAM_FAILURE, "the job could not be queued; submit again")
              )
            )
          )
        )
        throw e match
          case a: ApiError => a
          case _           => ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the job could not be queued")
    metrics.add("jobsQueued")
    log.event(
      "query-api",
      "job-queued",
      "traceId" -> r.traceId,
      "requestId" -> r.requestId,
      "jobId" -> jobId,
      "queryId" -> queryId
    )
    Response.ok(AsyncQueryResponse.of(job), 202)

  private def readJob(jobId: String): Option[JobRecord] =
    Try(ds.call(p.dynamodb)(backend.jobs.get(jobId))).toOption.flatMap(_.toOption).flatten

  /** The stored response of an earlier request with the same key and body: the job reference when
    * the first request was async, otherwise its stored answer.
    */
  private def replay(queryId: String): Response =
    val replayed = Map("Idempotent-Replay" -> "true")
    ds.call(p.dynamodb)(backend.jobs.get(StableIds.jobId(queryId))) match
      case Right(Some(j)) => Response.ok(AsyncQueryResponse.of(j), 202).copy(headers = replayed)
      case Left(_)        =>
        throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the job record could not be read")
      case Right(None) =>
        ds.call(p.s3)(RuntimeApi.storedAnswer(queryId, setup, ports.auditReader)) match
          case Right(Some(a)) => Response.ok(SyncQueryResponse.of(a)).copy(headers = replayed)
          case Right(None)    =>
            throw ApiError(
              ErrorCode.IDEMPOTENCY_CONFLICT,
              "the first request with this Idempotency-Key has no stored answer: it is still running or it failed (use a new key after a failure)"
            )
          case Left(_) =>
            throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the stored answer could not be read")

  private def jobOf(r: Request): JobRecord =
    Ops.noQuery(r)
    val id = r.params("jobId")
    val notFound = ApiError(ErrorCode.JOB_NOT_FOUND, "no job with this ID")
    if !IdFormat.isJobId(id) then throw notFound
    ds.call(p.dynamodb)(backend.jobs.get(id)) match
      case Right(Some(j)) => j
      case Right(None)    => throw notFound
      case Left(_)        =>
        throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the job record could not be read")

  private def job(r: Request): Response = Response.ok(JobResponse.of(jobOf(r)))

  /** SUCCEEDED: the stored answer; QUEUED or RUNNING: 409 JOB_NOT_FINISHED; FAILED: the job's typed
    * error with its own status.
    */
  private def result(r: Request): Response =
    val j = jobOf(r)
    j.state match
      case JobState.QUEUED | JobState.RUNNING =>
        throw ApiError(
          ErrorCode.JOB_NOT_FINISHED,
          s"the job is ${j.state}; poll /v1/jobs/${j.jobId} until it is SUCCEEDED or FAILED"
        )
      case JobState.FAILED =>
        val e = j.error.getOrElse(JobError(ErrorCode.INTERNAL_ERROR, "the job failed"))
        throw ApiError(e.code, e.message)
      case JobState.SUCCEEDED =>
        ds.call(p.s3)(RuntimeApi.storedAnswer(j.queryId, setup, ports.auditReader)) match
          case Right(Some(a)) => Response.ok(SyncQueryResponse.of(a, "asynchronous"))
          case _              =>
            throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the stored answer could not be read")

  private def explain(r: Request): Response =
    Ops.noQuery(r)
    val id = r.params("queryId")
    val notFound = ApiError(ErrorCode.QUERY_NOT_FOUND, "no query with this ID")
    if !IdFormat.isQueryId(id) then throw notFound
    ds.call(p.s3)(RuntimeApi.audit(id, setup, ports.auditReader)) match
      case Right(Some(a)) => Response.ok(ExplainResponse.of(a))
      case Right(None)    => throw notFound
      case Left(_)        =>
        throw ApiError(ErrorCode.DOWNSTREAM_FAILURE, "the audit record could not be read")

/** An opaque neighbor-page cursor: the next offset, bound to (node, direction, depth, edge types),
  * so a cursor from another neighborhood is rejected.
  */
object Cursor:
  def binding(nodeId: String, direction: String, depth: Int, edgeTypes: Seq[String]): String =
    StableIds.hash(
      "neighbors-cursor-v1",
      nodeId,
      direction,
      depth.toString,
      edgeTypes.mkString(",")
    )
  def encode(offset: Int, binding: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(s"v1.$offset.$binding".getBytes(UTF_8))
  def decode(cursor: String, binding: String): Option[Int] =
    Try(new String(Base64.getUrlDecoder.decode(cursor), UTF_8)).toOption.collect {
      case s"v1.$offset.$b" if b == binding && offset.toIntOption.exists(_ >= 0) => offset.toInt
    }

/** graph-evidence-api (ruling 1): evidence by ID, a graph node, and its bounded neighbors. */
final class GraphEvidenceService(cfg: ScholarChainConfig, backend: Backend, ds: Downstreams):
  private val p = Policies(cfg)
  private val graph = backend.ports.graph
  private val limits = graph.limits
  private val edgeTypes = NeptuneContract.Edges.map(_.label).sorted
  private val NeighborParams = Set("direction", "depth", "limit", "edgeTypes", "cursor")

  def routes: Seq[Route] = Seq(
    Route("GET", "/v1/evidence/{evidenceId}")(evidence),
    Route("GET", "/v1/graph/nodes/{nodeId}")(node),
    Route("GET", "/v1/graph/nodes/{nodeId}/neighbors")(neighbors)
  )

  def readiness: Vector[(String, () => Unit)] = Vector(
    "neptune" -> (() => ds.call(p.neptune)(graph.vertex(Probes.NodeId))),
    "dynamodb" -> (() => ds.call(p.dynamodb)(backend.evidence.evidenceItem(Probes.EvidenceId)))
  )

  private def evidence(r: Request): Response =
    Ops.noQuery(r)
    val id = r.params("evidenceId")
    val notFound = ApiError(ErrorCode.EVIDENCE_NOT_FOUND, "no evidence with this ID")
    if !IdFormat.isEvidenceId(id) then throw notFound
    val item = ds.call(p.dynamodb)(backend.evidence.evidenceItem(id)).getOrElse(throw notFound)
    ds.call(p.s3)(RuntimeApi.evidenceOf(item, backend.ports.objects)) match
      case Right(e) => Response.ok(EvidenceResponse.of(e, cfg.services.evidenceMaxContentChars))
      case Left(_)  =>
        throw ApiError(
          ErrorCode.DOWNSTREAM_FAILURE,
          "the evidence payload failed its integrity check"
        )

  private def nodeId(r: Request): String =
    val id = r.params("nodeId")
    if !IdFormat.isNodeId(id) then throw ApiError(ErrorCode.NODE_NOT_FOUND, "no node with this ID")
    id

  private def node(r: Request): Response =
    Ops.noQuery(r)
    val id = nodeId(r)
    ds.call(p.neptune)(graph.vertex(id)) match
      case Some(v) => Response.ok(NodeResponse(GraphViews.node(v)))
      case None    => throw ApiError(ErrorCode.NODE_NOT_FOUND, "no node with this ID")

  private def neighbors(r: Request): Response =
    def bad(m: String) = ApiError(ErrorCode.INVALID_FIELD, m)
    if !r.query.keySet.subsetOf(NeighborParams) then
      throw bad(
        s"unsupported query parameter; allowed: ${NeighborParams.toVector.sorted.mkString(", ")}"
      )
    if r.query.values.exists(_.size > 1) then throw bad("each query parameter may appear once")
    def one(k: String) = r.query.get(k).flatMap(_.headOption)
    def bounded(k: String, max: Int) =
      one(k)
        .flatMap(_.toIntOption)
        .filter(n => n >= 1 && n <= max)
        .getOrElse(throw bad(s"$k must be 1 to $max"))
    val direction = one("direction") match
      case Some("in")   => Direction.IN
      case Some("out")  => Direction.OUT
      case Some("both") => Direction.BOTH
      case _            => throw bad("direction must be in, out, or both")
    val depth = bounded("depth", limits.neighborsMaxDepth)
    val limit = bounded("limit", limits.neighborsMaxLimit)
    val types = one("edgeTypes")
      .map(_.split(",", -1).toVector.map(_.trim))
      .filter(ts => ts.nonEmpty && ts.forall(edgeTypes.contains))
      .map(_.distinct.sorted)
      .getOrElse(
        throw bad(s"edgeTypes must be a comma-separated subset of ${edgeTypes.mkString(", ")}")
      )
    val id = nodeId(r)
    val binding = Cursor.binding(id, direction.toString, depth, types)
    val offset = one("cursor").fold(0)(c =>
      Cursor
        .decode(c, binding)
        .getOrElse(
          throw bad("cursor is not a cursor of this node, direction, depth, and edge types")
        )
    )
    if offset > limits.maxResults - limit then
      throw bad(s"the cursor is past the last page (at most ${limits.maxResults} nodes in all)")
    val n = ds.call(p.neptune)(graph.neighbors(id, direction, depth, limit, types, offset))
    if n.start.isEmpty then throw ApiError(ErrorCode.NODE_NOT_FOUND, "no node with this ID")
    val next = offset + limit
    Response.ok(
      NeighborsResponse(
        id,
        direction.toString.toLowerCase,
        depth,
        limit,
        types,
        n.nodes.map(GraphViews.node),
        n.edges.map(GraphViews.edge),
        n.truncated,
        Option.when(n.truncated && n.nodes.size == limit && next <= limits.maxResults - limit)(
          Cursor.encode(next, binding)
        )
      )
    )
