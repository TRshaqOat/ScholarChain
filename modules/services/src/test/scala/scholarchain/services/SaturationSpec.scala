package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{JobState, StableIds}
import scholarchain.rag.runtime.RuntimeApi
import upickle.default.read

import java.util.concurrent.{CountDownLatch, Executors, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/** Repair-01 F1: long workflows hold only the workflow slots. Store calls and readiness probes keep
  * their own threads, answer within their timeouts, and open no breaker against a healthy store; a
  * workflow that cannot start answers 503 OVERLOADED.
  *
  * Phase 11 work order 1: no assertion measures a duration. Held workflows signal when they have
  * started and stay held until the test releases them, and the hard timeouts under test always fire
  * because a held workflow always overruns them. Three conditions still rely on the real clock
  * (code review 1, section 4c):
  *   - each held workflow must start within its call's 1000 ms hard timeout (sync deadline, or the
  *     worker backstop of deadline 200 ms plus model timeout 800 ms); one given up before it starts
  *     is OVERLOADED, and the test fails at "did not start";
  *   - every 200 and every passing readiness probe needs its store call to start and finish within
  *     its 1000 ms timeout (dynamodb-ms, s3-ms);
  *   - the worker's injected pause still sleeps its real 1 s, which nothing asserts on.
  */
class SaturationSpec extends AnyFunSuite:
  import ServiceFixtures.*

  /** b whose question clock is frozen (no question deadline ever passes) and whose workflows, while
    * hold is set, block at their first clock read, ignoring interrupts, as an overrunning model
    * call does. entered counts the workflows that blocked. Released workflows throw, so they end
    * without writing into the test directory.
    */
  private final class Blocked(b: Backend):
    val hold = AtomicBoolean(false)
    val entered = Semaphore(0)
    private val release = CountDownLatch(1)
    private def now(): Long =
      if hold.get then
        entered.release()
        var released = false
        while !released do
          try
            release.await()
            released = true
          catch case _: InterruptedException => ()
        throw IllegalStateException("released by the test")
      0L
    val backend: Backend = b.copy(setup = b.setup.copy(nowMs = () => now()))
    def drain(): Unit = release.countDown()

  private val Wait = 20L // seconds; reached only when the behaviour under test is broken
  private def noBreakerOpened(m: Metrics): Boolean =
    !m.snapshot("counters").obj.keys.exists(_.startsWith("circuitOpened"))

  private val Limits = Seq(
    "services.query-api.threads = 4", // 4 HTTP and store threads, 2 workflow slots
    "operations.synchronous-deadline-ms = 1000",
    "services.timeouts.dynamodb-ms = 1000",
    "services.timeouts.s3-ms = 1000",
    "services.resilience.breaker-failure-threshold = 1"
  ).mkString("\n")

  test("query-api: with every workflow slot held, jobs, explain, and readiness still answer") {
    withWorld(Limits) { w =>
      val x = Blocked(w.backend)
      val m = Metrics()
      // An answer to explain, stored without a sync POST so no hard timeout applies to it.
      val answered =
        StableIds.queryId(x.backend.setup.runId, StableIds.textHash(Question), Ids.fresh("t"), 1)
      val res = RuntimeApi.answer(
        QueryValidation.toRuntime(QueryBody(Question), answered),
        x.backend.ports,
        x.backend.setup,
        w.cfg.operations.synchronousDeadlineMs.toLong
      )
      assert(res.exists(!_.deadlinePassed), res)
      val (_, jobId) = submit(x.backend)
      w.query(x.backend, m) { c =>
        x.hold.set(true)
        val pool = Executors.newFixedThreadPool(4)
        def post() = pool.submit(() => c.post("/v1/query", queryJson()))
        try
          // 2 POSTs take the 2 workflow slots; both workflows have started and are held.
          val held = Vector.fill(2)(post())
          assert(x.entered.tryAcquire(2, Wait, TimeUnit.SECONDS), "the workflows did not start")
          // Every slot is held: 2 more POSTs cannot start, 503 OVERLOADED.
          Vector
            .fill(2)(post())
            .map(_.get(Wait, TimeUnit.SECONDS))
            .foreach(expectError(_, "OVERLOADED", 503))
          // The held POSTs pass their hard timeout (504) and free their HTTP threads; the
          // workflows overrun it and keep the slots.
          held
            .map(_.get(Wait, TimeUnit.SECONDS))
            .foreach(expectError(_, "DEADLINE_EXCEEDED", 504))
          // The fast routes keep their threads. A 200 means the store call started and answered
          // within its timeout (a call that waits past it is 503, one that overruns it is 504).
          (1 to 3).foreach { _ =>
            val j = c.get(s"/v1/jobs/$jobId")
            assert(j.status == 200 && read[JobResponse](j.body).state == JobState.QUEUED, j.body)
            val e = c.get(s"/v1/explain/$answered")
            assert(e.status == 200, e.body)
            val r = c.get("/readyz", Seq())
            assert(r.status == 200 && r.body == ujson.Obj("status" -> "ready"), r.body)
          }
          assert(noBreakerOpened(m), m.snapshot("counters"))
          // Still no workflow can start: 503 OVERLOADED, and still no breaker.
          expectError(c.post("/v1/query", queryJson()), "OVERLOADED", 503)
          assert(noBreakerOpened(m), m.snapshot("counters"))
          assert(c.get("/readyz", Seq()).status == 200)
          assert(x.entered.availablePermits == 0, "a refused POST ran a workflow")
        finally
          x.drain()
          pool.shutdownNow()
      }
    }
  }

  test("worker: an overrun job holds only its workflow slot; no job is claimed until it frees") {
    withWorld(
      Limits + "\nservices.worker.poll-wait-seconds = 1\nruntime.question.deadline-ms = 200" +
        "\nruntime.llm.timeout-ms = 800"
    ) { w =>
      val clock = Clock()
      val (b0, _) = withQueue(w.backend, w.cfg, clock)
      val x = Blocked(b0)
      val b = x.backend
      val m = Metrics()
      val paused = Semaphore(0)
      val wk = Worker(w.cfg, b, log, m, ms => { paused.release(); Thread.sleep(ms) })
      try
        x.hold.set(true)
        val (_, first) = submit(b)
        // Its workflow overruns the backstop (deadline + model timeout) and keeps its slot.
        assert(wk.pollOnce() == 1)
        assert(x.entered.tryAcquire(1, Wait, TimeUnit.SECONDS), "the workflow did not start")
        val failed = jobOf(b, first)
        assert(failed.state == JobState.FAILED, failed)
        assert(
          failed.error.map(_.code).contains(scholarchain.core.ErrorCode.DEADLINE_EXCEEDED),
          failed
        )
        val later = Vector.fill(2)(submit(b)._2)
        later.foreach(_ => assert(wk.pollOnce() == 1))
        // Store calls and readiness probes keep their threads and their timeouts; no breaker opens.
        wk.readiness.foreach { (name, probe) =>
          val outcome = scala.util.Try(probe())
          assert(outcome.isSuccess, s"$name probe: $outcome")
        }
        assert(wk.ds.openBreakers.isEmpty && noBreakerOpened(m), m.snapshot("counters"))
        // Later jobs are not claimed while the slot is held: they stay QUEUED, messages kept.
        later.foreach(j => assert(jobOf(b, j).state == JobState.QUEUED))
        assert(x.entered.availablePermits == 0, "a later job ran")
        assert(!wk.ds.workflowSlotFree)
        // The poll loop does not receive while no workflow slot is free: its first look ends in a
        // pause, with the later messages visible again and no receive counted.
        clock.advance(w.cfg.services.worker.visibilityTimeoutSeconds.toLong + 1)
        val receives = m.snapshot("counters")("queueReceives").num
        wk.start()
        val looked = paused.tryAcquire(Wait, TimeUnit.SECONDS)
        assert(m.snapshot("counters")("queueReceives").num == receives)
        assert(looked, "the poll loop never paused")
        later.foreach(j => assert(jobOf(b, j).state == JobState.QUEUED))
      finally
        x.drain()
        wk.stop()
    }
  }
