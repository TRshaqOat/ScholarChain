package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.JobState
import scholarchain.storage.*
import upickle.default.read

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** Repair-01 F1: long workflows hold only the workflow slots. Store calls and readiness probes keep
  * their own threads, answer within their timeouts, and open no breaker against a healthy store; a
  * workflow that cannot start answers 503 OVERLOADED.
  */
class SaturationSpec extends AnyFunSuite:
  import ServiceFixtures.*

  /** b whose store reads block while hold is set, ignoring interrupts, as an overrunning model call
    * does; audits counts the object writes after hold was set (an audit and an answer per
    * workflow).
    */
  private final class Blocked(b: Backend):
    val hold = AtomicBoolean(false)
    val release = CountDownLatch(1)
    val audits = AtomicInteger()
    val blocked = AtomicInteger()
    private def block(): Unit =
      if hold.get then
        blocked.incrementAndGet()
        val until = System.nanoTime() + 20000000000L
        while release.getCount > 0 && System.nanoTime() < until do
          try release.await(100, TimeUnit.MILLISECONDS)
          catch case _: InterruptedException => ()
    val backend: Backend = b.copy(ports =
      b.ports.copy(
        kv = new KeyValueReadPort:
          def get(t: TableName, pk: String, sk: String): Option[Item] =
            block(); b.ports.kv.get(t, pk, sk)
          def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
            block(); b.ports.kv.query(t, pk, sk, limit)
        ,
        auditWriter = new ObjectLoadPort:
          def putNew(key: String, bytes: Array[Byte]) =
            try b.ports.auditWriter.putNew(key, bytes)
            finally if hold.get then audits.incrementAndGet()
      )
    )

    /** Lets the blocked workflows end and waits (bounded) until they wrote their audits and
      * answers, so no write lands in the test directory while it is deleted.
      */
    def drain(started: Int): Unit =
      release.countDown()
      val until = System.nanoTime() + 10000000000L
      while audits.get < 2 * started && System.nanoTime() < until do Thread.sleep(50)
      Thread.sleep(500)

  private def ms(t0: Long) = (System.nanoTime() - t0) / 1000000
  private def timed[A](body: => A): (A, Long) =
    val t0 = System.nanoTime()
    val a = body
    (a, ms(t0))
  private def noBreakerOpened(m: Metrics): Boolean =
    !m.snapshot("counters").obj.keys.exists(_.startsWith("circuitOpened"))

  private val Limits = Seq(
    "services.query-api.threads = 4", // 4 HTTP and store threads, 2 workflow slots
    "operations.synchronous-deadline-ms = 300",
    "services.timeouts.dynamodb-ms = 1000",
    "services.timeouts.s3-ms = 1000",
    "services.resilience.breaker-failure-threshold = 1"
  ).mkString("\n")

  test("query-api: with every workflow slot held, jobs, explain, and readiness still answer") {
    withWorld(Limits) { w =>
      val x = Blocked(w.backend)
      val m = Metrics()
      w.query(x.backend, m) { c =>
        val answered = read[SyncQueryResponse](c.post("/v1/query", queryJson()).body)
        val (_, jobId) = submit(x.backend)
        x.hold.set(true)
        val pool = Executors.newFixedThreadPool(4)
        try
          // 4 POSTs at once: 2 take the workflow slots and overrun their deadline; 2 cannot start.
          val posts = Vector
            .fill(4)(pool.submit(() => c.post("/v1/query", queryJson())))
            .map(_.get(20, TimeUnit.SECONDS))
          // The fast routes keep their threads: answered within their store timeouts (1000 ms).
          (1 to 3).foreach { _ =>
            val (j, jMs) = timed(c.get(s"/v1/jobs/$jobId"))
            assert(j.status == 200 && read[JobResponse](j.body).state == JobState.QUEUED, j.body)
            assert(jMs < 1000, s"job read took $jMs ms")
            val (e, eMs) = timed(c.get(s"/v1/explain/${answered.queryId}"))
            assert(e.status == 200 && eMs < 1000, s"${e.body} after $eMs ms")
            val (r, rMs) = timed(c.get("/readyz", Seq()))
            assert(r.status == 200 && r.body == ujson.Obj("status" -> "ready"), r.body)
            assert(rMs < 3000, s"readiness took $rMs ms")
          }
          assert(noBreakerOpened(m), m.snapshot("counters"))
          val codes = posts.map(r => (r.status, r.error)).sorted
          assert(
            codes == Vector(
              (503, "OVERLOADED"),
              (503, "OVERLOADED"),
              (504, "DEADLINE_EXCEEDED"),
              (504, "DEADLINE_EXCEEDED")
            ),
            codes
          )
          posts.filter(_.status == 503).foreach(expectError(_, "OVERLOADED", 503))
          // A workflow that cannot start: 503 OVERLOADED at once, and still no breaker.
          val (o, oMs) = timed(c.post("/v1/query", queryJson()))
          expectError(o, "OVERLOADED", 503)
          assert(oMs < 1000 && noBreakerOpened(m))
          assert(c.get("/readyz", Seq()).status == 200)
        finally
          x.drain(2)
          pool.shutdownNow()
      }
    }
  }

  test("worker: an overrun job holds only its workflow slot; no job is claimed until it frees") {
    withWorld(
      Limits + "\nservices.worker.poll-wait-seconds = 1\nruntime.question.deadline-ms = 200" +
        "\nruntime.llm.timeout-ms = 200"
    ) { w =>
      val clock = Clock()
      val (b0, _) = withQueue(w.backend, w.cfg, clock)
      val x = Blocked(b0)
      val b = x.backend
      val m = Metrics()
      val wk = Worker(w.cfg, b, log, m)
      try
        x.hold.set(true)
        val (_, first) = submit(b)
        // Its workflow overruns the backstop (deadline + model timeout) and keeps its slot.
        assert(wk.pollOnce() == 1)
        assert(jobOf(b, first).state == JobState.FAILED)
        val later = Vector.fill(2)(submit(b)._2)
        later.foreach(_ => assert(wk.pollOnce() == 1))
        // Store calls and readiness probes keep their threads and their timeouts; no breaker opens.
        wk.readiness.foreach { (name, probe) =>
          val (outcome, took) = timed(scala.util.Try(probe()))
          assert(outcome.isSuccess && took < 1000, s"$name probe: $outcome after $took ms")
        }
        assert(wk.ds.openBreakers.isEmpty && noBreakerOpened(m), m.snapshot("counters"))
        // Later jobs are not claimed while the slot is held: they stay QUEUED, messages kept.
        later.foreach(j => assert(jobOf(b, j).state == JobState.QUEUED))
        assert(x.blocked.get == 1, "a later job ran")
        assert(!wk.ds.workflowSlotFree)
        // The poll loop does not receive while no workflow slot is free.
        clock.advance(w.cfg.services.worker.visibilityTimeoutSeconds.toLong + 1)
        val receives = m.snapshot("counters")("queueReceives").num
        wk.start()
        Thread.sleep(1500)
        assert(m.snapshot("counters")("queueReceives").num == receives)
        later.foreach(j => assert(jobOf(b, j).state == JobState.QUEUED))
      finally
        x.drain(1)
        wk.stop()
    }
  }
