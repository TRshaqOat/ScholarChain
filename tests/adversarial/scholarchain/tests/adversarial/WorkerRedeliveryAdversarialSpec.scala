package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{ErrorCode, JobState, StableIds}
import scholarchain.services.*
import scholarchain.storage.*
import scholarchain.tests.support.StoreWorld.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Phase 11 WO3, ruling 1 targets "job redelivery" and "queue flooding", spec category "replayed
  * workflow". The worker runs with poll-wait 1 s on an in-memory queue whose clock the test moves.
  */
class WorkerRedeliveryAdversarialSpec extends AnyFunSuite:
  private val Fast = "services.worker.poll-wait-seconds = 1"

  final class Clock:
    @volatile var now: Instant = Instant.parse("2026-11-12T00:00:00Z")
    def advance(s: Long): Unit = now = now.plusSeconds(s)

  /** b with a queue and job store on clock, and an audit writer that counts workflow runs. */
  private def rig(w: World, clock: Clock) =
    val q = InMemoryJobQueue(w.cfg.services.queue.maxReceiveCount, () => clock.now)
    val table = LocalServiceStateTable(None, () => clock.now)
    val runs = AtomicInteger()
    val orig = w.backend.ports.auditWriter
    val writer = new ObjectLoadPort:
      def putNew(key: String, bytes: Array[Byte]) =
        if key.startsWith("logs/") && key.endsWith(".json") && !key.contains("/evidence/") then
          runs.incrementAndGet()
        orig.putNew(key, bytes)
    val b = w.backend.copy(
      ports = w.backend.ports.copy(auditWriter = writer),
      jobs = JobStore(table, w.cfg.services.ttl.jobHours, () => clock.now),
      queue = q
    )
    (b, q, runs)

  private def job(b: Backend, question: String, request: Option[String] = None) =
    val qid = StableIds.queryId(b.setup.runId, StableIds.textHash(question), Ids.fresh("t"), 1)
    val body = request.getOrElse(upickle.default.write(QueryBody(question)))
    assert(b.jobs.create(qid, body).isRight)
    (qid, StableIds.jobId(qid))
  private def state(b: Backend, jid: String) = b.jobs.get(jid).toOption.flatten.map(_.state)

  test("a forged message pairing a real job with another query ID is poison: never run") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b, q, runs) = rig(w, clock)
      val log = CaptureLog()
      val wk = Worker(w.cfg, b, log.log, Metrics())
      try
        val (qa, ja) = job(b, Question)
        val (qb, _) = job(b, "Who is Grace Hopper?")
        Vector(
          s"""{"schemaVersion":"job-message-v1","jobId":"$ja","queryId":"$qb"}""",
          s"""{"schemaVersion":"job-message-v1","jobId":"$ja","queryId":"$qa","request":{"question":"x"}}""",
          s"""{"schemaVersion":"job-message-v2","jobId":"$ja","queryId":"$qa"}""",
          s"""{"jobId":"$ja","queryId":"$qa"}""",
          s"""[{"schemaVersion":"job-message-v1","jobId":"$ja","queryId":"$qa"}]""",
          "SYNTHETIC poison <script>"
        ).foreach(q.sendRaw)
        val n = q.size
        (1 to w.cfg.services.queue.maxReceiveCount).foreach { _ =>
          (1 to n).foreach(_ => assert(wk.pollOnce() == 1))
          clock.advance(w.cfg.services.worker.visibilityTimeoutSeconds + 1L)
        }
        assert(wk.pollOnce() == 0) // the next receive moves every message to the DLQ
        assert(q.deadLetters.size == n && q.size == 0)
        assert(runs.get == 0, "a forged message ran a workflow")
        assert(state(b, ja).contains(JobState.QUEUED))
        assert(!log.all.exists(_.contains("SYNTHETIC poison")), "a message body reached the log")
      finally wk.stop()
    }
  }

  test("a stored request that does not decode fails the job once and deletes the message") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b, q, runs) = rig(w, clock)
      val m = Metrics()
      val wk = Worker(w.cfg, b, CaptureLog().log, m)
      try
        val (qid, jid) = job(b, Question, request = Some("""{"questionnaire": 1}"""))
        b.queue.send(JobMessage.of(qid))
        b.queue.send(JobMessage.of(qid)) // a duplicate send
        assert(wk.pollOnce() == 1 && wk.pollOnce() == 1)
        val j = b.jobs.get(jid).toOption.flatten.get
        assert(j.state == JobState.FAILED && j.error.map(_.code).contains(ErrorCode.INTERNAL_ERROR))
        assert(q.size == 0 && runs.get == 0)
      finally wk.stop()
    }
  }

  test("a stored request whose question breaks the API rules is not run as a valid answer") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b, q, runs) = rig(w, clock)
      val wk = Worker(w.cfg, b, CaptureLog().log, Metrics())
      try
        // A job record written around the API (or tampered with) carrying a question the API
        // would refuse (control characters, beyond max-chars).
        val bad = "Who\u0000 is Ada Lovelace?" + "x" * (w.cfg.runtime.question.maxChars + 10)
        val (qid, jid) = job(b, "tampered", request = Some(upickle.default.write(QueryBody(bad))))
        b.queue.send(JobMessage.of(qid))
        assert(wk.pollOnce() == 1)
        assert(state(b, jid).contains(JobState.FAILED), state(b, jid))
        assert(q.size == 0)
      finally wk.stop()
    }
  }

  test("an expired job's message is poison: the job is never resurrected or run") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b, q, runs) = rig(w, clock)
      val wk = Worker(w.cfg, b, CaptureLog().log, Metrics())
      try
        val (qid, jid) = job(b, Question)
        clock.advance(w.cfg.services.ttl.jobHours * 3600L + 1)
        b.queue.send(JobMessage.of(qid))
        (1 to w.cfg.services.queue.maxReceiveCount).foreach { _ =>
          assert(wk.pollOnce() == 1)
          clock.advance(w.cfg.services.worker.visibilityTimeoutSeconds + 1L)
        }
        assert(wk.pollOnce() == 0)
        assert(q.deadLetters.size == 1 && runs.get == 0)
        assert(b.jobs.get(jid) == Right(None))
      finally wk.stop()
    }
  }

  test("flooding: 60 poison messages around one real job run that job exactly once") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b, q, runs) = rig(w, clock)
      val m = Metrics()
      val wk = Worker(w.cfg, b, CaptureLog().log, m)
      try
        (1 to 30).foreach(i => q.sendRaw(s"""{"flood": $i}"""))
        val (qid, jid) = job(b, Question)
        b.queue.send(JobMessage.of(qid))
        (1 to 30).foreach(i =>
          q.sendRaw(s"""{"schemaVersion":"job-message-v1","jobId":"job-${"%032x".format(
              i
            )}","queryId":"qry-${"%032x".format(i)}"}""")
        )
        var guard = 0
        while q.size > 0 && guard < 10 do
          while wk.pollOnce() == 1 do ()
          clock.advance(w.cfg.services.worker.visibilityTimeoutSeconds + 1L)
          guard += 1
        assert(state(b, jid).contains(JobState.SUCCEEDED))
        assert(runs.get == 1)
        assert(q.deadLetters.size == 60 && q.size == 0)
        assert(
          m.snapshot("counters")("poisonMessages")
            .num
            .toLong == 60L * w.cfg.services.queue.maxReceiveCount
        )
      finally wk.stop()
    }
  }

  test("async POST flooding is rate limited before any job or message is written") {
    withWorld("services.query-api.query-rate-per-minute = 3") { w =>
      val q = InMemoryJobQueue(w.cfg.services.queue.maxReceiveCount)
      val table = LocalServiceStateTable(None, () => Instant.now)
      val b = w.backend.copy(
        queue = q,
        jobs = JobStore(table, w.cfg.services.ttl.jobHours, () => Instant.now)
      )
      val log = CaptureLog()
      // A frozen clock: the minute never passes, whatever the machine load.
      val limiter = RateLimiter(w.cfg.services.queryApi.queryRatePerMinute, () => 0L)
      val metrics = Metrics()
      val ds = Downstreams(w.cfg.services.resilience, 4, 64, metrics, workflowThreads = 2)
      val svc = HttpService(
        "query-api",
        QueryService(w.cfg, b, ds, log.log, metrics, limiter).routes,
        HttpSettings(4, 64, w.cfg.services.maxBodyBytes),
        w.auth,
        log.log,
        metrics,
        () => ds.shutdown()
      )
      w.serve(svc) { c =>
        val rs = (1 to 12).map(i =>
          c.post("/v1/query", queryJson(s"Who is Ada Lovelace $i?", ", \"execution\": \"async\""))
        )
        assert(rs.count(_.status == 202) == 3)
        val limited = rs.filter(_.status == 429)
        assert(
          limited.size == 9 && limited.forall(r =>
            r.error == "RATE_LIMITED" && r.headers.get("Retry-After").contains("60")
          )
        )
        assert(q.size == 3, s"${q.size} messages queued")
        // Keyed replays of an accepted request also pass the limiter (no free retries).
        val key = Seq("Idempotency-Key" -> "flood-key")
        assert(
          c.post("/v1/query", queryJson(extra = ", \"execution\": \"async\""), key).status == 429
        )
        assert(q.size == 3)
      }
    }
  }

  test("the composed query-api (Apps.queryApi) applies the rate limit to async requests too") {
    // Work order 4 (code review 1 section 5): the test above builds its own QueryService, so it
    // would pass if Apps.queryApi stopped wiring the limiter. With a budget of 1 per minute, the
    // first async POST is accepted and the second is refused before any job or message exists.
    // The limiter here is the production one on the real clock; the only timing assumption is that
    // three loopback requests take less than its 60 s window, each already bounded by the 30 s
    // client hang guard.
    withWorld("services.query-api.query-rate-per-minute = 1") { w =>
      val q = InMemoryJobQueue(w.cfg.services.queue.maxReceiveCount)
      val table = LocalServiceStateTable(None, () => Instant.now)
      val b = w.backend.copy(
        queue = q,
        jobs = JobStore(table, w.cfg.services.ttl.jobHours, () => Instant.now)
      )
      val log = CaptureLog()
      w.query(log.log, b) { c =>
        val async = ", \"execution\": \"async\""
        val first = c.post("/v1/query", queryJson("Who is Ada Lovelace?", async))
        assert(first.status == 202, first.text)
        val second = c.post("/v1/query", queryJson("Who is Grace Hopper?", async))
        assert(second.status == 429 && second.error == "RATE_LIMITED", second.text)
        assert(second.headers.get("Retry-After").contains("60"))
        assert(q.size == 1, s"${q.size} messages queued")
        // The accepted question has its QUEUED job; the refused one sent no message (q.size).
        val job = b.jobs.get(first.body("jobId").str).toOption.flatten
        assert(job.map(_.state).contains(JobState.QUEUED), job)
        // The sync path shares the same budget.
        val sync = c.post("/v1/query", queryJson())
        assert(sync.status == 429 && sync.error == "RATE_LIMITED", sync.text)
      }
    }
  }
