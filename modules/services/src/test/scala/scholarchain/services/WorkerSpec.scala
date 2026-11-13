package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{ErrorCode, JobState, StableIds}
import scholarchain.rag.runtime.Audit
import scholarchain.storage.*
import upickle.default.read

import java.time.Instant
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** The async worker (Phase 10 ruling 7): one run per job however often its message arrives, delete
  * only after a final state, the stuck-RUNNING rule, poison messages and redrive, typed failures,
  * the async POST and jobs routes, and the worker service over HTTP.
  */
class WorkerSpec extends AnyFunSuite:
  import ServiceFixtures.*

  private val Fast = "services.worker.poll-wait-seconds = 1"

  /** b whose audit writer records keys; the count of audit writes of a query (one per run). */
  private def countingAudits(b: Backend): (Backend, String => Int) =
    val keys = ConcurrentLinkedQueue[String]()
    val orig = b.ports.auditWriter
    val port = new ObjectLoadPort:
      def putNew(key: String, bytes: Array[Byte]) =
        keys.add(key)
        orig.putNew(key, bytes)
    (
      b.copy(ports = b.ports.copy(auditWriter = port)),
      q => keys.asScala.count(_ == Audit.auditKey(b.setup.snapshot, b.setup.runId, q))
    )

  private def kv(b: Backend)(body: => Unit): Backend =
    b.copy(ports = b.ports.copy(kv = new KeyValueReadPort:
      def get(t: TableName, pk: String, sk: String): Option[Item] =
        body; b.ports.kv.get(t, pk, sk)
      def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
        body; b.ports.kv.query(t, pk, sk, limit)))

  private def counter(m: Metrics, name: String) = m.snapshot("counters")(name).num.toLong

  test("a redelivered or duplicated message never runs a job twice") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b0, q) = withQueue(w.backend, w.cfg, clock)
      val (b, audits) = countingAudits(b0)
      val m = Metrics()
      val wk = Worker(w.cfg, b, log, m)
      try
        val (qid, jid) = submit(b)
        b.queue.send(JobMessage.of(qid)) // SQS standard queues may deliver a message twice
        assert(wk.pollOnce() == 1)
        val done = jobOf(b, jid)
        assert(done.state == JobState.SUCCEEDED, done)
        assert(
          done.stage.contains("AUDIT") && done.startedAt.isDefined && done.finishedAt.isDefined
        )
        assert(wk.pollOnce() == 1)
        assert(jobOf(b, jid) == done, "the duplicate changed the job")
        assert(audits(qid) == 1 && q.size == 0)
        assert(counter(m, "duplicateDeliveries") == 1 && counter(m, "jobsSucceeded") == 1)

        // Four copies taken by four workers at once: exactly one claim wins and runs.
        val (qid2, jid2) = submit(b)
        (1 to 3).foreach(_ => b.queue.send(JobMessage.of(qid2)))
        val workers = Vector.fill(4)(Worker(w.cfg, b, log, Metrics()))
        val pool = Executors.newFixedThreadPool(4)
        try
          val fs = workers.map(x => pool.submit(new Callable[Int] { def call() = x.pollOnce() }))
          assert(fs.map(_.get(30, TimeUnit.SECONDS)).sum == 4)
        finally
          pool.shutdown()
          workers.foreach(_.stop())
        assert(jobOf(b, jid2).state == JobState.SUCCEEDED && audits(qid2) == 1)
        // Copies kept while the job was RUNNING come back and are deleted as duplicates.
        clock.advance(301)
        (1 to 4).foreach(_ => if q.size > 0 then wk.pollOnce())
        assert(q.size == 0 && q.deadLetters.isEmpty && audits(qid2) == 1)
      finally wk.stop()
    }
  }

  test(
    "a message is deleted only after a final state; a stuck RUNNING job fails at its last delivery"
  ) {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b1, q) = withQueue(w.backend, w.cfg, clock)
      @volatile var finalWritesFail = true
      val table = LocalServiceStateTable(None, () => Instant.now)
      val failing = new ServiceStateTable:
        def get(recordKey: String, recordType: String) = table.get(recordKey, recordType)
        def putIfAbsent(item: Item) = table.putIfAbsent(item)
        def putIfState(item: Item, expected: JobState) =
          if finalWritesFail && ServiceStateContract.job(item).exists(_.state.isFinal) then
            throw IllegalStateException("store down")
          table.putIfState(item, expected)
      val (b, audits) = countingAudits(
        b1.copy(jobs = JobStore(failing, w.cfg.services.ttl.jobHours, () => Instant.now))
      )
      val m = Metrics()
      val wk = Worker(w.cfg, b, log, m)
      try
        val (qid, jid) = submit(b)
        // Delivery 1 runs the job, but its final state cannot be written: the message is kept.
        assert(wk.pollOnce() == 1)
        assert(jobOf(b, jid).state == JobState.RUNNING && q.size == 1 && audits(qid) == 1)
        finalWritesFail = false
        // Delivery 2: still RUNNING and not the last delivery, so left alone and not run again.
        clock.advance(301)
        assert(wk.pollOnce() == 1)
        assert(jobOf(b, jid).state == JobState.RUNNING && q.size == 1 && audits(qid) == 1)
        // Delivery 3 = max-receive-count: RUNNING -> FAILED, then the message is deleted.
        clock.advance(301)
        assert(wk.pollOnce() == 1)
        val j = jobOf(b, jid)
        assert(
          j.state == JobState.FAILED && j.error.map(_.code).contains(ErrorCode.DEADLINE_EXCEEDED)
        )
        assert(q.size == 0 && q.deadLetters.isEmpty && audits(qid) == 1)
        assert(counter(m, "stuckJobsFailed") == 1)
        w.query(b) { c =>
          expectError(c.get(s"/v1/jobs/$jid/result"), "DEADLINE_EXCEEDED", 504)
          val v = read[JobResponse](c.get(s"/v1/jobs/$jid").body)
          assert(v.state == JobState.FAILED && v.error.exists(_.retryable) && v.resultUrl.isEmpty)
        }
      finally wk.stop()
    }
  }

  test("poison messages are never run or deleted; redrive moves them to the dead-letter queue") {
    withWorld(Fast) { w =>
      val clock = Clock()
      val (b0, q) = withQueue(w.backend, w.cfg, clock)
      val reads = AtomicInteger()
      val b = kv(b0)(reads.incrementAndGet())
      val m = Metrics()
      val wk = Worker(w.cfg, b, log, m)
      try
        q.sendRaw("{\"not\": \"a job\"}")
        q.send(JobMessage.of(StableIds.queryId(b.setup.runId, "no", "job", 1))) // no job record
        (1 to w.cfg.services.queue.maxReceiveCount).foreach { _ =>
          assert(wk.pollOnce() == 1 && wk.pollOnce() == 1)
          assert(q.size == 2)
          clock.advance(301)
        }
        assert(wk.pollOnce() == 0) // the next receive attempt redrives both
        assert(q.deadLetters.size == 2 && q.size == 0 && reads.get == 0)
        assert(counter(m, "poisonMessages") == 6 && counter(m, "queueReceives") == 6)
      finally wk.stop()
    }
  }

  test(
    "a failing, late, or undecodable job ends FAILED with its typed error; its message is deleted"
  ) {
    withWorld(s"$Fast\nruntime.question.deadline-ms = 300") { w =>
      val clock = Clock()
      def runOne(b0: Backend, request: Option[String] = None): JobRecord =
        val (b, q) = withQueue(b0, w.cfg, clock)
        val wk = Worker(w.cfg, b, log, Metrics())
        try
          val (_, jid) = submit(b, request = request)
          assert(wk.pollOnce() == 1)
          assert(q.size == 0)
          jobOf(b, jid)
        finally wk.stop()
      val failed = runOne(kv(w.backend)(throw IllegalStateException("down: secret-endpoint")))
      assert(failed.state == JobState.FAILED)
      assert(failed.error.map(_.code).contains(ErrorCode.DOWNSTREAM_FAILURE), failed)
      assert(!failed.error.exists(_.message.contains("secret-endpoint")))
      val late = runOne(kv(w.backend)(Thread.sleep(120)))
      assert(late.error.map(_.code).contains(ErrorCode.DEADLINE_EXCEEDED), late)
      val bad = runOne(w.backend, Some("{}"))
      assert(bad.error.map(_.code).contains(ErrorCode.INTERNAL_ERROR) && bad.startedAt.isEmpty)
      w.query() { c =>
        expectError(c.get(s"/v1/jobs/${failed.jobId}/result"), "DOWNSTREAM_FAILURE", 502)
        expectError(c.get(s"/v1/jobs/${late.jobId}/result"), "DEADLINE_EXCEEDED", 504)
        expectError(c.get(s"/v1/jobs/${bad.jobId}/result"), "INTERNAL_ERROR", 500)
      }
    }
  }

  test("async POST: 202 job reference, job IDs checked first, a send failure fails the job") {
    withWorld(Fast) { w =>
      w.query() { c =>
        val r = c.post("/v1/query", queryJson(extra = Async))
        assert(r.status == 202, r.body)
        val a = read[AsyncQueryResponse](r.body)
        assert(a.jobId == StableIds.jobId(a.queryId) && a.state == JobState.QUEUED)
        assert(a.mode == "asynchronous" && a.statusUrl == s"/v1/jobs/${a.jobId}")
        assert(
          w.backend.queue.receive(10, 0, 0).map(_.message) == Vector(
            Right(JobMessage.of(a.queryId))
          )
        )
        val j = read[JobResponse](c.get(a.statusUrl).body)
        assert(j.state == JobState.QUEUED && j.resultUrl.isEmpty && j.error.isEmpty)
        expectError(c.get(s"${a.statusUrl}/result"), "JOB_NOT_FINISHED", 409)
        expectError(c.get("/v1/jobs/job-" + "0" * 32), "JOB_NOT_FOUND", 404)
        expectError(c.get("/v1/jobs/..%2Fetc/result"), "JOB_NOT_FOUND", 404)
        expectError(c.get(s"${a.statusUrl}?verbose=1"), "INVALID_FIELD", 400)
        expectError(c.get(a.statusUrl, Seq()), "UNAUTHORIZED", 401)
        expectError(c.get(s"${a.statusUrl}/result", Seq()), "UNAUTHORIZED", 401)
      }
      // The queue refuses the message: the job does not stay QUEUED; a replay shows it FAILED.
      val down = w.backend.copy(queue = new JobQueuePort:
        def send(m: JobMessage) = throw IllegalStateException("sqs down")
        def receive(n: Int, s: Int, v: Int) = Vector.empty
        def delete(h: String) = false
        def changeVisibility(h: String, s: Int) = false)
      w.query(down) { c =>
        val key = Seq("Idempotency-Key" -> "async-down-1")
        expectError(c.post("/v1/query", queryJson(extra = Async), key), "DOWNSTREAM_FAILURE", 502)
        val qid = w.backend.idempotency.get("async-down-1").toOption.flatten.get.queryId
        val j = jobOf(w.backend, StableIds.jobId(qid))
        assert(
          j.state == JobState.FAILED && j.error.map(_.code).contains(ErrorCode.DOWNSTREAM_FAILURE)
        )
        val again = c.post("/v1/query", queryJson(extra = Async), key)
        assert(again.status == 202 && again.headers.get("Idempotent-Replay").contains("true"))
        assert(read[AsyncQueryResponse](again.body).state == JobState.FAILED)
        // The same key with the sync body is another request: 409.
        expectError(c.post("/v1/query", queryJson(), key), "IDEMPOTENCY_CONFLICT", 409)
      }
    }
  }

  test("worker service: liveness, readiness, key-protected metrics, and the poll loop") {
    withWorld(Fast) { w =>
      val m = Metrics()
      w.query() { c =>
        val a = read[AsyncQueryResponse](c.post("/v1/query", queryJson(extra = Async)).body)
        w.serve(Apps.worker(w.cfg, w.backend, w.auth, log, m)) { wc =>
          assert(wc.get("/healthz", Seq()).body == ujson.Obj("status" -> "ok"))
          assert(wc.get("/readyz", Seq()).body == ujson.Obj("status" -> "ready"))
          expectError(wc.get("/metrics", Seq()), "UNAUTHORIZED", 401)
          expectError(wc.get("/v1/query"), "ROUTE_NOT_FOUND", 404)
          val until = System.nanoTime() + 20000000000L
          while read[JobResponse](c.get(a.statusUrl).body).state != JobState.SUCCEEDED &&
            System.nanoTime() < until
          do Thread.sleep(50)
          assert(read[JobResponse](c.get(a.statusUrl).body).state == JobState.SUCCEEDED)
          val counters = wc.get("/metrics").body("counters")
          assert(counters("queueReceives").num == 1 && counters("jobsSucceeded").num == 1)
          assert(counters("answers").num == 1 && counters("verifiedClaims").num >= 1)
        }
      }
    }
  }
