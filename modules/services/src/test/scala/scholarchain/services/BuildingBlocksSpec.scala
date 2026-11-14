package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ErrorCode
import scholarchain.core.config.ResilienceConfig
import scholarchain.storage.TransientStoreException

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** The breaker, retries, rate limiter, cursor, IDs, and key check on their own. */
class BuildingBlocksSpec extends AnyFunSuite:
  private def thrown(body: => Any): ApiError =
    intercept[ApiError](body)

  test("breaker: opens after the threshold, fails fast while open, half-opens after the interval") {
    var now = 0L
    val b = Breaker(3, 1000, () => now)
    assert(!b.failure() && !b.failure() && !b.isOpen)
    b.success() // a success resets the count
    assert(!b.failure() && !b.failure() && b.failure() && b.isOpen)
    now = 999
    assert(b.isOpen)
    now = 1000
    assert(!b.isOpen) // half-open: one trial passes
    assert(b.failure() && b.isOpen) // and its failure reopens at once
    now = 2000
    b.success()
    assert(!b.isOpen && !b.failure())
  }

  test("downstream calls: timeout, retries only for retryable reads, breaker per downstream") {
    val m = Metrics()
    var slept = Vector.empty[Long]
    val ds =
      Downstreams(ResilienceConfig(3, 100, 1000, 2, 60000), 2, 2, m, sleep = ms => slept :+= ms)
    try
      val n = AtomicInteger()
      val ok = ds.call(CallPolicy("s3", 1000, retryReads = true)) {
        if n.incrementAndGet() < 3 then throw TransientStoreException("throttled") else "done"
      }
      assert(ok == "done" && n.get == 3 && slept == Vector(100L, 200L))
      // Without retryReads (adapters that retry themselves, writes) one attempt only.
      n.set(0)
      assert(thrown(ds.call(CallPolicy("neptune", 1000)) {
        n.incrementAndGet(); throw TransientStoreException("x")
      }).code == ErrorCode.DOWNSTREAM_FAILURE)
      assert(n.get == 1)
      // A non-retryable error is not retried even for reads.
      n.set(0)
      thrown(ds.call(CallPolicy("dynamodb", 1000, retryReads = true)) {
        n.incrementAndGet(); throw IllegalStateException("x")
      })
      assert(n.get == 1)
      assert(
        thrown(
          ds.call(CallPolicy("dynamodb", 1000))(throw IllegalStateException("x"))
        ).code == ErrorCode.DOWNSTREAM_FAILURE
      )
      assert(
        thrown(ds.call(CallPolicy("dynamodb", 1000))("never runs")).code == ErrorCode.CIRCUIT_OPEN
      )
      assert(
        ds.openBreakers == Vector("dynamodb") && m
          .snapshot("counters")("circuitOpened.dynamodb")
          .num == 1
      )
      assert(
        thrown(
          ds.call(CallPolicy("x", 50))(Thread.sleep(2000))
        ).code == ErrorCode.DOWNSTREAM_TIMEOUT
      )
      // An ApiError from the body is the route's own outcome, not a failure.
      (1 to 3).foreach(_ =>
        thrown(ds.call(CallPolicy("y", 1000))(throw ApiError(ErrorCode.NODE_NOT_FOUND, "x")))
      )
      assert(!ds.openBreakers.contains("y"))
      // A failed check inside a downstream is a server defect (repair-01 m5), not a client error.
      val bug = thrown(ds.call(CallPolicy("z", 1000))(require(false, "internal-detail")))
      assert(bug.code == ErrorCode.INTERNAL_ERROR && !bug.message.contains("internal-detail"))
      assert(!ds.openBreakers.contains("z"))
    finally ds.shutdown()
  }

  test("waiting to start is no downstream failure: 503 OVERLOADED, no breaker (repair-01 F1)") {
    val m = Metrics()
    // One store thread, a queue of 4, one workflow slot; one counted failure would open a breaker.
    val ds = Downstreams(ResilienceConfig(1, 100, 1000, 1, 60000), 1, 4, m, workflowThreads = 1)
    val release = CountDownLatch(1)
    val bg = Executors.newFixedThreadPool(3)
    def hold(p: CallPolicy): CountDownLatch =
      val entered = CountDownLatch(1)
      bg.submit(() => ds.call(p) { entered.countDown(); release.await(10, TimeUnit.SECONDS) })
      assert(entered.await(10, TimeUnit.SECONDS))
      entered
    try
      val ran = AtomicInteger()
      hold(CallPolicy("neptune", 10000)) // the only store thread
      // Queued behind it past its timeout: given up, 503, never run, and no breaker failure.
      (1 to 3).foreach { _ =>
        assert(
          thrown(ds.call(CallPolicy("dynamodb", 100))(ran.incrementAndGet())).code ==
            ErrorCode.OVERLOADED
        )
      }
      // The workflow pool has no queue: a workflow that cannot start is refused at once. Its hour
      // long timeout never passes here, so OVERLOADED can only be the refusal; a queued call would
      // instead run once the held slot frees (at the latest after the hold's 10 s) and answer.
      assert(ds.workflowSlotFree)
      hold(CallPolicy("runtime", 10000, workflow = true))
      assert(!ds.workflowSlotFree)
      val wf = CallPolicy("runtime", 3600000, workflow = true)
      assert(thrown(ds.call(wf)(ran.incrementAndGet())).code == ErrorCode.OVERLOADED)
      assert(
        ds.openBreakers.isEmpty && !m.snapshot("counters").obj.keys.exists(_.startsWith("circuit"))
      )
      release.countDown()
      // A timeout counts from the call's start, not its submission. The only store thread is held
      // until half the timeout has passed since submission (W >= T / 2 < T); the call then runs
      // until its submission deadline has passed (W + R > T) but not its start deadline (R < T).
      // Both orders are explicit clock checks; the remaining condition is that a thread starts
      // within T / 2 of being freed.
      val timeoutMs = 4000L
      val gate = CountDownLatch(1)
      val busy = CountDownLatch(1)
      bg.submit(() =>
        ds.call(CallPolicy("s3", 60000)) { busy.countDown(); gate.await(20, TimeUnit.SECONDS) }
      )
      assert(busy.await(10, TimeUnit.SECONDS))
      val submitted = System.nanoTime()
      def until(ms: Long): Unit =
        while System.nanoTime() - submitted < ms * 1000000 do Thread.sleep(5)
      val opener: Runnable = () => { until(timeoutMs / 2); gate.countDown() }
      bg.submit(opener)
      assert(ds.call(CallPolicy("s3", timeoutMs)) { until(timeoutMs + 200); "ok" } == "ok")
      assert(ran.get == 0 && ds.openBreakers.isEmpty)
    finally
      release.countDown()
      bg.shutdownNow()
      ds.shutdown()
  }

  test("rate limiter: a sliding minute") {
    var now = 0L
    val r = RateLimiter(2, () => now)
    assert(r.tryAcquire() && r.tryAcquire() && !r.tryAcquire())
    now = 59999
    assert(!r.tryAcquire())
    now = 60000 // both slots of time 0 have left the window
    assert(r.tryAcquire() && r.tryAcquire() && !r.tryAcquire())
  }

  test("cursor: round trip, bound to its walk, and opaque garbage rejected") {
    val b = Cursor.binding("scr:x", "BOTH", 2, Seq("AUTHORED"))
    assert(Cursor.decode(Cursor.encode(5, b), b).contains(5))
    assert(
      Cursor.decode(Cursor.encode(5, b), Cursor.binding("scr:x", "IN", 2, Seq("AUTHORED"))).isEmpty
    )
    assert(Cursor.decode(Cursor.encode(-1, b), b).isEmpty)
    assert(Cursor.decode("!!!", b).isEmpty && Cursor.decode("", b).isEmpty)
  }

  test("IDs: valid client values echoed, anything else replaced") {
    assert(Ids.orFresh(Some("abc-1.2:3_x"), "req-") == "abc-1.2:3_x")
    Seq(None, Some(""), Some("a b"), Some("x" * 129), Some("é")).foreach(v =>
      assert(Ids.orFresh(v, "req-").matches("req-[0-9a-f]{32}"), v)
    )
  }

  test("API key: exact match only, any length; the key is never printed") {
    val a = ApiKeyAuth("s3cr3t")
    assert(a.accepts(Some("s3cr3t")))
    Seq(None, Some(""), Some("s3cr3"), Some("s3cr3tt"), Some("S3CR3T"), Some(" s3cr3t")).foreach(
      v => assert(!a.accepts(v), v)
    )
    assert(!a.toString.contains("s3cr3t"))
    intercept[IllegalArgumentException](ApiKeyAuth(""))
  }
