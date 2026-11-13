package scholarchain.services

import scholarchain.core.ErrorCode
import scholarchain.core.config.ResilienceConfig
import scholarchain.storage.{GraphReadTimeoutException, RetryPolicy, TransientStoreException}
import scholarchain.storage.live.LiveAws

import java.util.concurrent.{
  BlockingQueue,
  Callable,
  ExecutionException,
  Future,
  LinkedBlockingQueue,
  RejectedExecutionException,
  SynchronousQueue,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/** A consecutive-failure circuit breaker (ruling 9). It opens after `threshold` consecutive
  * failures and fails fast for `openMs`; after that, calls pass again, and the first failure
  * reopens it at once (half-open), while a success closes it.
  */
final class Breaker(threshold: Int, openMs: Long, nowMs: () => Long):
  private var failures = 0
  private var openedAt = Long.MinValue
  def isOpen: Boolean = synchronized(failures >= threshold && nowMs() - openedAt < openMs)
  def success(): Unit = synchronized { failures = 0 }

  /** Records a failure; true when it opened the breaker. */
  def failure(): Boolean = synchronized {
    failures += 1
    if failures >= threshold then
      openedAt = nowMs()
      true
    else false
  }

/** A failure of a downstream call that a route turns into no other status (502). */
final class DownstreamFailed(message: String) extends RuntimeException(message)

/** How one downstream call is bounded (ruling 9): a client-side timeout, retries for reads whose
  * adapter does not retry itself (so there is one retry layer), and which code a timeout maps to.
  * interruptOnTimeout false lets a timed-out call run to its end on the pool (the workflow, so its
  * audit is still written completely). workflow true runs the call on the workflow pool, apart from
  * store calls and readiness probes (repair-01 F1).
  */
final case class CallPolicy(
    downstream: String,
    timeoutMs: Long,
    retryReads: Boolean = false,
    timeoutCode: ErrorCode = ErrorCode.DOWNSTREAM_TIMEOUT,
    timeoutIsFailure: Boolean = true,
    interruptOnTimeout: Boolean = true,
    workflow: Boolean = false
)

/** Runs downstream calls with a timeout and one breaker per downstream name, on two bounded pools
  * so a timed-out call frees the request thread (repair-01 F1):
  *   - store calls and readiness probes: `threads` threads and a queue of `queue`;
  *   - workflows (CallPolicy.workflow): `workflowThreads` threads and no queue, so long workflows
  *     never hold the threads store calls and probes need.
  *
  * A call's timeout counts from its start. A call that cannot start (a full pool, or still waiting
  * when its timeout passes) answers 503 OVERLOADED and touches no breaker: waiting for a thread
  * says nothing about the downstream.
  */
final class Downstreams(
    cfg: ResilienceConfig,
    threads: Int,
    queue: Int,
    metrics: Metrics,
    workflowThreads: Int = 1,
    nowMs: () => Long = () => System.nanoTime() / 1000000,
    sleep: Long => Unit = Thread.sleep
):
  private val breakers = mutable.HashMap.empty[String, Breaker]
  private def breaker(name: String) = synchronized(
    breakers.getOrElseUpdate(
      name,
      Breaker(cfg.breakerFailureThreshold, cfg.breakerOpenMs.toLong, nowMs)
    )
  )
  def openBreakers: Vector[String] =
    synchronized(breakers.toVector).filter(_._2.isOpen).map(_._1).sorted

  private val retry = RetryPolicy(cfg.maxAttempts, cfg.baseDelayMs.toLong, cfg.maxDelayMs.toLong)
  private val count = AtomicInteger()
  private def executor(n: Int, q: BlockingQueue[Runnable], tag: String) = ThreadPoolExecutor(
    n,
    n,
    60,
    TimeUnit.SECONDS,
    q,
    r =>
      val t = Thread(r, s"$tag-${count.incrementAndGet()}")
      t.setDaemon(true)
      t
  )
  private val pool = executor(threads, LinkedBlockingQueue[Runnable](queue), "downstream")
  // Threads are created on first use, so a service without workflows starts none.
  private val workflows = executor(workflowThreads, SynchronousQueue[Runnable](), "workflow")
  def shutdown(): Unit =
    pool.shutdownNow()
    workflows.shutdownNow()

  /** Whether a workflow could start now (the worker receives a job only then). */
  def workflowSlotFree: Boolean = workflows.getActiveCount < workflowThreads

  private val overloaded = ApiError(ErrorCode.OVERLOADED, "the service is at capacity; retry later")

  /** Whether a call has started; the caller and the pool thread race once to decide. */
  private final class Start:
    private val state = AtomicInteger(0) // 0 waiting, 1 started, 2 given up by the caller
    @volatile var atNs = 0L
    def begin(): Boolean =
      atNs = System.nanoTime()
      state.compareAndSet(0, 1)
    def giveUp(): Boolean = state.compareAndSet(0, 2)

  /** The result within timeoutMs of the call's start. A call still waiting to start when timeoutMs
    * has passed is given up: 503 OVERLOADED, never a downstream failure.
    */
  private def await[A](f: Future[A], start: Start, timeoutMs: Long): A =
    try f.get(timeoutMs, TimeUnit.MILLISECONDS)
    catch
      case e: TimeoutException =>
        if start.giveUp() then
          f.cancel(false)
          throw overloaded
        val left = timeoutMs - (System.nanoTime() - start.atNs) / 1000000
        if left <= 0 then throw e else f.get(left, TimeUnit.MILLISECONDS)

  private def retryable(e: Throwable) =
    e.isInstanceOf[TransientStoreException] || LiveAws.isTransient(e)

  private def attempts[A](retryReads: Boolean)(body: => A): A =
    var n = 1
    var result: Option[A] = None
    while result.isEmpty do
      try result = Some(body)
      catch
        case scala.util.control.NonFatal(e)
            if retryReads && n < retry.maxAttempts && retryable(e) =>
          sleep(retry.delayBeforeRetry(n))
          n += 1
    result.get

  /** Runs body under p. An ApiError from body passes through and is no downstream failure. */
  def call[A](p: CallPolicy)(body: => A): A =
    val b = breaker(p.downstream)
    if b.isOpen then
      throw ApiError(ErrorCode.CIRCUIT_OPEN, s"${p.downstream} is unavailable; retry later")
    val start = Start()
    val task: Callable[A] = () =>
      if start.begin() then attempts(p.retryReads)(body) else null.asInstanceOf[A]
    val f =
      try (if p.workflow then workflows else pool).submit(task)
      catch case _: RejectedExecutionException => throw overloaded
    def failed(code: ErrorCode, message: String): Nothing =
      if b.failure() then metrics.add(s"circuitOpened.${p.downstream}")
      throw ApiError(code, message)
    try
      val a = await(f, start, p.timeoutMs)
      b.success()
      a
    catch
      case _: TimeoutException =>
        f.cancel(p.interruptOnTimeout)
        val message = s"${p.downstream} did not answer within ${p.timeoutMs} ms"
        if p.timeoutIsFailure then failed(p.timeoutCode, message)
        else throw ApiError(p.timeoutCode, message)
      case e: ExecutionException =>
        e.getCause match
          case a: ApiError                  => throw a
          case _: GraphReadTimeoutException =>
            failed(ErrorCode.DOWNSTREAM_TIMEOUT, s"${p.downstream} query timed out")
          case _: IllegalArgumentException =>
            // Routes validate first, so a failed check inside a downstream is a server defect
            // (repair-01 m5): 500 with no detail, and no failure of the downstream itself.
            throw ApiError(ErrorCode.INTERNAL_ERROR, "unexpected internal failure")
          case _ => failed(ErrorCode.DOWNSTREAM_FAILURE, s"${p.downstream} failed")
      case _: InterruptedException =>
        f.cancel(true)
        throw ApiError(ErrorCode.OVERLOADED, "the request was interrupted")

/** Accepted requests per minute per instance (ruling 6, 429), over a sliding 60 s window. */
final class RateLimiter(perMinute: Int, nowMs: () => Long = () => System.nanoTime() / 1000000):
  private val times = mutable.ArrayDeque.empty[Long]
  def tryAcquire(): Boolean = synchronized {
    val now = nowMs()
    while times.headOption.exists(_ <= now - 60000) do times.removeHead()
    if times.size < perMinute then
      times.append(now)
      true
    else false
  }
