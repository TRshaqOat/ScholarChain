package scholarchain.services

import scholarchain.core.{ErrorCode, JobState}
import scholarchain.core.config.ScholarChainConfig
import scholarchain.rag.runtime.{RuntimeApi, Stage}
import scholarchain.storage.{JobError, JobRecord, JobTransition, ReceivedJob}
import upickle.default.read

import scala.util.control.NonFatal
import scala.util.{Success, Try}

/** The async job worker (ruling 7). It long-polls the job queue and moves each job QUEUED ->
  * RUNNING -> SUCCEEDED or FAILED with the job store's conditional writes. Rules:
  *   - Only the worker whose QUEUED -> RUNNING write passed runs the workflow, so a redelivered
  *     message never runs a job twice. When the outcome of that write is unknown (a timeout), the
  *     job is not run.
  *   - A message is deleted only after its job is in a final state (written by this worker, read
  *     back, or found on redelivery).
  *   - Stuck RUNNING: a job still RUNNING when its message comes back is left alone until the last
  *     delivery (receiveCount >= services.queue.max-receive-count). Then it is moved RUNNING ->
  *     FAILED (DEADLINE_EXCEEDED) and its message deleted. The visibility timeout exceeds the job
  *     deadline plus one model timeout, so by then its worker has stopped or overrun.
  *   - Poison: a malformed message, or one whose job does not exist, is never run and never
  *     deleted; the queue's redrive policy moves it to the dead-letter queue after
  *     max-receive-count receives.
  *   - While any downstream breaker is open the worker does not receive (it fails fast).
  */
final class Worker(cfg: ScholarChainConfig, backend: Backend, log: JsonLog, metrics: Metrics):
  private val w = cfg.services.worker
  private val p = Policies(cfg)
  private val jobs = backend.jobs
  private val maxReceives = cfg.services.queue.maxReceiveCount
  private val deadlineMs = cfg.runtime.question.deadlineMs.toLong
  private val Service = "worker"

  /** Store calls: one thread per poll loop plus one per HTTP thread (readiness probes); workflows:
    * one slot per concurrent job, apart from them (repair-01 F1).
    */
  val ds: Downstreams =
    Downstreams(
      cfg.services.resilience,
      w.concurrency + w.threads,
      cfg.services.maxQueuedRequests,
      metrics,
      workflowThreads = w.concurrency
    )

  // A receive may wait poll-wait-seconds longer than an SQS call (services.timeouts.sqs-ms).
  private val receivePolicy = p.sqs.copy(timeoutMs = p.sqs.timeoutMs + w.pollWaitSeconds * 1000L)

  /** The whole job workflow, on the workflow pool: the runtime's cooperative deadline
    * (runtime.question.deadline-ms) ends it; a hard timeout one model timeout later is the
    * backstop, under the visibility timeout.
    */
  private val runtime = CallPolicy(
    "runtime",
    deadlineMs + cfg.runtime.llm.timeoutMs,
    timeoutCode = ErrorCode.DEADLINE_EXCEEDED,
    timeoutIsFailure = false,
    workflow = true
  )

  def readiness: Vector[(String, () => Unit)] = Vector(
    "dynamodb" -> (() => ds.call(p.dynamodb)(Probes.fail(jobs.get(Probes.JobId)))),
    "s3" -> (() => ds.call(p.s3)(backend.ports.auditReader.get(Probes.objectKey(backend.setup)))),
    "neptune" -> (() => ds.call(p.neptune)(backend.ports.graph.vertex(Probes.NodeId)))
  )

  @volatile private var running = false
  private var threads = Vector.empty[Thread]

  def start(): Unit = synchronized {
    running = true
    threads = Vector.tabulate(w.concurrency) { i =>
      val t = Thread(() => loop(), s"worker-poll-${i + 1}")
      t.setDaemon(true)
      t.start()
      t
    }
  }

  /** Stops receiving; waits one poll for the loops, then stops the downstream pool. A job still
    * running stays RUNNING and is ended by the stuck-RUNNING rule on a later delivery.
    */
  def stop(): Unit =
    running = false
    synchronized(threads).foreach(_.join((w.pollWaitSeconds + 1) * 1000L))
    ds.shutdown()

  private def loop(): Unit =
    while running do
      // No receive while a breaker is open or every workflow slot is held by an overrun job.
      if ds.openBreakers.nonEmpty || !ds.workflowSlotFree then Thread.sleep(1000)
      else
        try pollOnce()
        catch
          case NonFatal(_) =>
            log.event(Service, "poll-failed")
            Thread.sleep(1000)

  /** One receive of at most one message, and its processing; the number of messages received. */
  def pollOnce(): Int =
    val got = ds.call(receivePolicy)(
      backend.queue.receive(1, w.pollWaitSeconds, w.visibilityTimeoutSeconds)
    )
    got.foreach(handle)
    got.size

  private def event(what: String, m: ReceivedJob, fields: (String, ujson.Value)*): Unit =
    log.event(
      Service,
      what,
      (Seq[(String, ujson.Value)]("messageId" -> m.messageId, "receiveCount" -> m.receiveCount) ++
        m.message.toOption.toSeq
          .flatMap(j => Seq("jobId" -> ujson.Str(j.jobId), "queryId" -> ujson.Str(j.queryId))) ++
        fields)*
    )

  private def handle(m: ReceivedJob): Unit =
    metrics.add("queueReceives")
    m.message match
      case Left(_)    => poison(m, "malformed message")
      case Right(msg) =>
        Try(ds.call(p.dynamodb)(jobs.get(msg.jobId))) match
          case Success(Right(None))    => poison(m, "no such job")
          case Success(Right(Some(j))) =>
            j.state match
              case JobState.SUCCEEDED | JobState.FAILED =>
                metrics.add("duplicateDeliveries")
                event("duplicate-delivery", m, "state" -> j.state.toString)
                delete(m)
              case JobState.RUNNING if m.receiveCount >= maxReceives =>
                val error = JobError(
                  ErrorCode.DEADLINE_EXCEEDED,
                  s"the job did not finish within $maxReceives deliveries; submit the query again"
                )
                val (mine, now) =
                  move(j.jobId, JobState.RUNNING, JobState.FAILED, None, Some(error))
                if mine then metrics.add("stuckJobsFailed")
                event("stuck-running", m, "failed" -> mine)
                settle(m, now)
              case JobState.RUNNING => event("running-elsewhere", m)
              case JobState.QUEUED  => begin(m, j)
          case _ =>
            event("job-read-failed", m) // the message comes back after its visibility timeout

  private def poison(m: ReceivedJob, why: String): Unit =
    metrics.add("poisonMessages")
    event("poison-message", m, "reason" -> why)

  private def begin(m: ReceivedJob, j: JobRecord): Unit =
    Try(read[QueryBody](j.request)).toOption match
      case None =>
        val error = JobError(ErrorCode.INTERNAL_ERROR, "the stored request does not decode")
        val (_, now) = move(j.jobId, JobState.QUEUED, JobState.FAILED, None, Some(error))
        settle(m, now)
      case Some(_) if !ds.workflowSlotFree =>
        // Not claimed: the job stays QUEUED and its message comes back (repair-01 F1).
        event("no-workflow-slot", m)
      case Some(body) =>
        val first = Some(Stage.QUERY_PLANNING.toString)
        val (mine, now) = move(j.jobId, JobState.QUEUED, JobState.RUNNING, first, None)
        if mine then run(m, j, body)
        else
          event("claim-refused", m, "state" -> now.fold("unknown")(_.state.toString))
          settle(m, now)

  private def run(m: ReceivedJob, j: JobRecord, body: QueryBody): Unit =
    val t0 = System.nanoTime()
    var failedStage: Option[String] = None
    val outcome: Either[JobError, Option[String]] =
      try
        val res = ds.call(runtime) {
          RuntimeApi.answer(
            QueryValidation.toRuntime(body, j.queryId),
            backend.ports,
            backend.setup,
            deadlineMs
          ) match
            case Right(res)                  => Right(res)
            case Left(f) if f.deadlinePassed => Left(f)
            case Left(f)                     =>
              failedStage = Some(f.stage.toString)
              throw DownstreamFailed(f.stage.toString) // counts against the runtime breaker
        }
        res match
          case Left(f) =>
            Left(
              JobError(
                ErrorCode.DEADLINE_EXCEEDED,
                s"the job deadline of $deadlineMs ms passed in ${f.stage}"
              )
            )
          case Right(r) =>
            metrics.answer(r.audit)
            r.audit.stages.foreach(log.jobStage(j.jobId, j.queryId, _))
            val last = r.audit.stages.lastOption.map(_.stage.toString)
            if r.deadlinePassed then
              Left(
                JobError(
                  ErrorCode.DEADLINE_EXCEEDED,
                  s"the job deadline of $deadlineMs ms passed; the abstention is recorded"
                )
              )
            else Right(last)
      catch
        case e: ApiError if e.code == ErrorCode.DOWNSTREAM_FAILURE =>
          Left(JobError(e.code, s"the ${failedStage.getOrElse("runtime")} stage failed"))
        case e: ApiError => Left(JobError(e.code, e.message))
        case NonFatal(_) => Left(JobError(ErrorCode.INTERNAL_ERROR, "unexpected internal failure"))
    val (to, stage, error) = outcome match
      case Right(last) => (JobState.SUCCEEDED, last, None)
      case Left(e)     => (JobState.FAILED, failedStage, Some(e))
    val (_, now) = move(j.jobId, JobState.RUNNING, to, stage, error)
    if now.exists(_.state == to) then
      metrics.add(if to == JobState.SUCCEEDED then "jobsSucceeded" else "jobsFailed")
    event(
      "job-finished",
      m,
      "state" -> now.fold("unknown")(_.state.toString),
      "error" -> error.fold(ujson.Null)(e => ujson.Str(e.code.toString)),
      "latencyMs" -> ((System.nanoTime() - t0) / 1000000).toDouble
    )
    settle(m, now)

  /** A conditional transition: (whether this call moved the job, the record stored now). After a
    * failed or timed-out write the outcome is unknown, so the record is read back (WO4) and the
    * move never counts as this worker's.
    */
  private def move(
      jobId: String,
      from: JobState,
      to: JobState,
      stage: Option[String],
      error: Option[JobError]
  ): (Boolean, Option[JobRecord]) =
    Try(ds.call(p.dynamodb)(jobs.transition(jobId, from, to, stage, error))) match
      case Success(Right(JobTransition.Moved(job)))   => (true, Some(job))
      case Success(Right(JobTransition.Refused(cur))) => (false, cur)
      case _                                          =>
        (false, Try(ds.call(p.dynamodb)(jobs.get(jobId))).toOption.flatMap(_.toOption).flatten)

  /** Deletes the message only when its job is in a final state; otherwise it comes back. */
  private def settle(m: ReceivedJob, now: Option[JobRecord]): Unit =
    if now.exists(_.state.isFinal) then delete(m)
    else event("message-kept", m)

  private def delete(m: ReceivedJob): Unit =
    val ok = Try(ds.call(p.sqs)(backend.queue.delete(m.receiptHandle))).getOrElse(false)
    if !ok then event("delete-failed", m) // a later delivery finds the final state and deletes it
