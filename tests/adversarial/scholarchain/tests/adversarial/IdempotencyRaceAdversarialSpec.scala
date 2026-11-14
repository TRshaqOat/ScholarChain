package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.JobState
import scholarchain.services.*
import scholarchain.storage.*
import scholarchain.tests.support.StoreWorld.*

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Phase 11 WO3, ruling 1 target "idempotency races" and spec category "replayed workflow and
  * idempotency". Concurrency is ordered with latches: a held workflow stays held until the test
  * releases it, so no assertion depends on timing.
  */
class IdempotencyRaceAdversarialSpec extends AnyFunSuite:

  /** b whose first key-value read blocks until release (entered counts down when it blocks), and
    * whose audit writer counts writes (one per workflow run).
    */
  private def held(w: World, entered: CountDownLatch, release: CountDownLatch) =
    val first = AtomicBoolean(true)
    val audits = AtomicInteger()
    val b = w.backend
    val kv = new KeyValueReadPort:
      private def gate(): Unit =
        if first.compareAndSet(true, false) then
          entered.countDown()
          release.await(60, TimeUnit.SECONDS)
      def get(t: TableName, pk: String, sk: String): Option[Item] = {
        gate(); b.ports.kv.get(t, pk, sk)
      }
      def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
        gate(); b.ports.kv.query(t, pk, sk, limit)
    val writer = new ObjectLoadPort:
      def putNew(key: String, bytes: Array[Byte]) =
        if key.startsWith("logs/") && key.endsWith(".json") && !key.contains("/evidence/") then
          audits.incrementAndGet()
        b.ports.auditWriter.putNew(key, bytes)
    (b.copy(ports = b.ports.copy(kv = kv, auditWriter = writer)), audits)

  test("same key and body while the first sync query runs: 409, one workflow, then the replay") {
    withWorld() { w =>
      val log = CaptureLog()
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val (b, audits) = held(w, entered, release)
      w.query(log.log, b) { c =>
        val pool = Executors.newSingleThreadExecutor()
        try
          val key = Seq("Idempotency-Key" -> "race-sync-1")
          val first = pool.submit(new Callable[Reply] {
            def call() = c.post("/v1/query", queryJson(), key)
          })
          assert(entered.await(30, TimeUnit.SECONDS), "the first workflow never started")
          // While the first runs: the same body is "still running", a different body a conflict.
          (1 to 5).foreach { _ =>
            val r = c.post("/v1/query", queryJson(), key)
            assert(r.status == 409 && r.error == "IDEMPOTENCY_CONFLICT", r.text)
            assert(r.body("message").str.contains("no stored answer"), r.text)
          }
          val other = c.post("/v1/query", queryJson("Who is Grace Hopper?"), key)
          assert(
            other.status == 409 && other.body("message").str.contains("different request body")
          )
          release.countDown()
          val r1 = first.get(60, TimeUnit.SECONDS)
          assert(r1.status == 200, r1.text)
          val replay = c.post("/v1/query", queryJson(), key)
          assert(replay.status == 200 && replay.headers.get("Idempotent-Replay").contains("true"))
          assert(replay.body == r1.body)
          assert(audits.get == 1, s"${audits.get} workflow runs for one key")
        finally
          release.countDown()
          pool.shutdownNow()
      }
    }
  }

  test("eight concurrent identical keyed POSTs run one workflow and share one query ID") {
    withWorld() { w =>
      val log = CaptureLog()
      val (b, audits) = held(w, CountDownLatch(1), CountDownLatch(0)) // never blocks
      w.query(log.log, b) { c =>
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try
          val fs = (1 to 8).map(_ =>
            pool.submit(new Callable[Reply] {
              def call() =
                start.await(30, TimeUnit.SECONDS)
                c.post("/v1/query", queryJson(), Seq("Idempotency-Key" -> "race-sync-8"))
            })
          )
          start.countDown()
          val rs = fs.map(_.get(60, TimeUnit.SECONDS))
          assert(rs.forall(r => r.status == 200 || r.status == 409), rs.map(_.status))
          val oks = rs.filter(_.status == 200)
          assert(
            oks.count(_.headers.get("Idempotent-Replay").isEmpty) == 1,
            "not exactly one original"
          )
          assert(oks.map(_.body("queryId").str).distinct.size == 1)
          assert(rs.filter(_.status == 409).forall(_.error == "IDEMPOTENCY_CONFLICT"))
          assert(audits.get == 1)
        finally pool.shutdownNow()
      }
    }
  }

  test("async: a racing replay before the job exists is 409; afterwards one job and one message") {
    withWorld() { w =>
      val log = CaptureLog()
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val table = LocalServiceStateTable(None, () => Instant.now)
      // Job creation blocks (after the key claim), so a second request sees the claim but no job.
      val blocking = new ServiceStateTable:
        def get(k: String, t: String) = table.get(k, t)
        def putIfAbsent(item: Item) =
          if item.get("recordType").contains(AttributeValue.S(ServiceStateContract.JobType)) then
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
          table.putIfAbsent(item)
        def putIfState(item: Item, expected: JobState) = table.putIfState(item, expected)
      val q = InMemoryJobQueue(w.cfg.services.queue.maxReceiveCount)
      val b = w.backend.copy(
        idempotency =
          IdempotencyStore(blocking, w.cfg.services.ttl.idempotencyHours, () => Instant.now),
        jobs = JobStore(blocking, w.cfg.services.ttl.jobHours, () => Instant.now),
        queue = q
      )
      w.query(log.log, b) { c =>
        val pool = Executors.newSingleThreadExecutor()
        try
          val key = Seq("Idempotency-Key" -> "race-async-1")
          val body = queryJson(extra = ", \"execution\": \"async\"")
          val first = pool.submit(new Callable[Reply] {
            def call() = c.post("/v1/query", body, key)
          })
          assert(entered.await(30, TimeUnit.SECONDS))
          val racing = c.post("/v1/query", body, key)
          assert(racing.status == 409 && racing.error == "IDEMPOTENCY_CONFLICT", racing.text)
          release.countDown()
          val r1 = first.get(60, TimeUnit.SECONDS)
          assert(r1.status == 202, r1.text)
          val again = c.post("/v1/query", body, key)
          assert(again.status == 202 && again.headers.get("Idempotent-Replay").contains("true"))
          assert(again.body("jobId") == r1.body("jobId"))
          assert(q.size == 1, s"${q.size} messages for one keyed async query")
          // The same key on a sync body is a different request: 409, no second job.
          val sync = c.post("/v1/query", queryJson(), key)
          assert(sync.status == 409 && sync.body("message").str.contains("different request body"))
          assert(q.size == 1)
        finally
          release.countDown()
          pool.shutdownNow()
      }
    }
  }

  test(
    "N3 (documented): a keyed sync POST answered 503 OVERLOADED keeps its key; the retry is 409"
  ) {
    // SERVICES.md section 3 and openapi.yaml (Phase 11 WO1 N3): the claim is not released when the
    // workflow cannot start (deferred by Phase 10 ruling 18). This pins the documented behaviour.
    withWorld("services.query-api.threads = 2") { w =>
      val log = CaptureLog()
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val (b, _) = held(w, entered, release)
      w.query(log.log, b) { c =>
        val pool = Executors.newSingleThreadExecutor()
        try
          // The one workflow slot (max(1, 2 / 2)) is held by an unkeyed query.
          val holder = pool.submit(new Callable[Reply] {
            def call() = c.post("/v1/query", queryJson())
          })
          assert(entered.await(30, TimeUnit.SECONDS))
          val key = Seq("Idempotency-Key" -> "n3-overloaded")
          val busy = c.post("/v1/query", queryJson("Who is Grace Hopper?"), key)
          assert(
            busy.status == 503 && busy.error == "OVERLOADED" && busy.body("retryable").bool,
            busy.text
          )
          release.countDown()
          assert(holder.get(60, TimeUnit.SECONDS).status == 200)
          val retry = c.post("/v1/query", queryJson("Who is Grace Hopper?"), key)
          assert(retry.status == 409 && retry.error == "IDEMPOTENCY_CONFLICT", retry.text)
          // A new key goes through.
          assert(
            c.post(
              "/v1/query",
              queryJson("Who is Grace Hopper?"),
              Seq("Idempotency-Key" -> "n3-new")
            ).status == 200
          )
        finally
          release.countDown()
          pool.shutdownNow()
      }
    }
  }

  test("an expired Idempotency-Key is a new request; the stored record holds only the key hash") {
    var now = Instant.parse("2026-11-12T00:00:00Z")
    val table = LocalServiceStateTable(None, () => now)
    val store = IdempotencyStore(table, 24, () => now)
    val q1 = "qry-" + "1" * 32
    val q2 = "qry-" + "2" * 32
    val h1 = "sha256:" + "a" * 64
    val h2 = "sha256:" + "b" * 64
    assert(
      store.claim("client-key-secret-value", h1, q1).map(_.outcome) == Right(LoadOutcome.Inserted)
    )
    assert(
      store.claim("client-key-secret-value", h1, q2).map(_.outcome) == Right(LoadOutcome.Unchanged)
    )
    assert(
      store.claim("client-key-secret-value", h2, q2).map(_.outcome) == Right(LoadOutcome.Conflict)
    )
    // The stored item never holds the raw header value.
    val stored = table.get(
      ServiceStateContract.idempotencyRecordKey("client-key-secret-value"),
      ServiceStateContract.IdempotencyType
    )
    assert(stored.exists(i => !i.values.exists(_.toString.contains("client-key-secret-value"))))
    now = now.plusSeconds(24 * 3600L + 1)
    val again = store.claim("client-key-secret-value", h2, q2)
    assert(again.map(s => (s.outcome, s.record.queryId)) == Right((LoadOutcome.Inserted, q2)))
  }

  test("job transitions are linearizable: of 16 racing QUEUED -> RUNNING claims exactly one wins") {
    val table = LocalServiceStateTable(None, () => Instant.now)
    val jobs = JobStore(table, 168, () => Instant.now)
    val qid = "qry-" + "c" * 32
    assert(jobs.create(qid, "{\"question\":\"q\"}").isRight)
    val jid = scholarchain.core.StableIds.jobId(qid)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(16)
    try
      val fs = (1 to 16).map(_ =>
        pool.submit(new Callable[Either[String, JobTransition]] {
          def call() =
            start.await(30, TimeUnit.SECONDS)
            jobs.transition(jid, JobState.QUEUED, JobState.RUNNING)
        })
      )
      start.countDown()
      val rs = fs.map(_.get(30, TimeUnit.SECONDS))
      assert(rs.count { case Right(JobTransition.Moved(_)) => true; case _ => false } == 1)
      assert(rs.count {
        case Right(JobTransition.Refused(Some(j))) => j.state == JobState.RUNNING; case _ => false
      } == 15)
      // No path back: a finished job can never be claimed again.
      assert(jobs.transition(jid, JobState.RUNNING, JobState.SUCCEEDED).isRight)
      assert(jobs.transition(jid, JobState.SUCCEEDED, JobState.RUNNING).isLeft)
      assert(jobs.transition(jid, JobState.QUEUED, JobState.RUNNING) match
        case Right(JobTransition.Refused(Some(j))) => j.state == JobState.SUCCEEDED
        case _                                     => false)
    finally pool.shutdownNow()
  }
