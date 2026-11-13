package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{JobState, StableIds, VerificationStatus}
import scholarchain.services.main.LocalRun
import upickle.default.read

/** The full query-to-evidence lifecycle with fakes (work order 6 item 2): query-api,
  * graph-evidence-api, and the worker on one backend, over real sockets; and the local runner.
  */
class LifecycleSpec extends AnyFunSuite:
  import ServiceFixtures.*

  /** A cited evidence ID resolves with provenance; explain names it and the verified count. */
  private def traced(q: Client, g: Client, a: SyncQueryResponse): Unit =
    val verified = a.claims.filter(_.verificationStatus == VerificationStatus.VERIFIED)
    assert(verified.nonEmpty, a)
    val ev = verified.head.evidenceIds.head
    assert(a.links.evidence.contains(s"/v1/evidence/$ev"))
    val e = g.get(s"/v1/evidence/$ev")
    assert(e.status == 200, e.body)
    val item = read[EvidenceResponse](e.body)
    assert(item.evidenceId == ev && item.provenance.runId.nonEmpty)
    val x = q.get(a.links.explain)
    assert(x.status == 200, x.body)
    val plan = read[ExplainResponse](x.body)
    assert(plan.queryId == a.queryId && plan.llm == "STUBBED")
    assert((plan.graphEvidenceIds ++ plan.semanticEvidenceIds).contains(ev))
    assert(plan.verification.verified == verified.size)

  test("query to evidence: sync and async answers, cited evidence, and explain") {
    withWorld("services.worker.poll-wait-seconds = 1") { w =>
      val worker = Worker(w.cfg, w.backend, log, Metrics())
      try
        w.query() { q =>
          w.graphEvidence() { g =>
            // Sync.
            val s = q.post("/v1/query", queryJson())
            assert(s.status == 200, s.body)
            traced(q, g, read[SyncQueryResponse](s.body))

            // Async: 202, QUEUED, result not finished, an idempotent replay sends no message.
            val key = Seq("Idempotency-Key" -> "lifecycle-1", "X-Trace-Id" -> "trace-lifecycle-1")
            val r = q.post("/v1/query", queryJson(extra = Async), key)
            assert(r.status == 202, r.body)
            val a = read[AsyncQueryResponse](r.body)
            assert(a.state == JobState.QUEUED && a.jobId == StableIds.jobId(a.queryId))
            expectError(q.get(s"${a.statusUrl}/result"), "JOB_NOT_FINISHED", 409)
            val replay = q.post("/v1/query", queryJson(extra = Async), key)
            assert(replay.status == 202 && replay.headers.get("Idempotent-Replay").contains("true"))
            assert(read[AsyncQueryResponse](replay.body).jobId == a.jobId)

            // The worker takes the one message and runs the job.
            assert(worker.pollOnce() == 1)
            val j = read[JobResponse](q.get(a.statusUrl).body)
            assert(j.state == JobState.SUCCEEDED && j.stage.contains("AUDIT"), j)
            assert(j.startedAt.isDefined && j.finishedAt.isDefined && j.error.isEmpty)
            assert(j.resultUrl.contains(s"${a.statusUrl}/result"))
            val res = q.get(j.resultUrl.get)
            assert(res.status == 200, res.body)
            val answer = read[SyncQueryResponse](res.body)
            assert(answer.mode == "asynchronous" && answer.queryId == a.queryId)
            traced(q, g, answer)
            val again = q.post("/v1/query", queryJson(extra = Async), key)
            assert(read[AsyncQueryResponse](again.body).state == JobState.SUCCEEDED)
            assert(worker.pollOnce() == 0) // no second message was sent

            // The trace ID links the POST to the job; the worker's stage lines name the job.
            assert(
              logged.exists(l =>
                l.contains("\"event\":\"job-queued\"") && l.contains("trace-lifecycle-1") &&
                  l.contains(a.jobId)
              )
            )
            assert(logged.count(l => l.contains("\"type\":\"stage\"") && l.contains(a.jobId)) == 10)
          }
        }
      finally worker.stop()
    }
  }

  test("local runner: three services on one backend, fake mode only") {
    withWorld() { w =>
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]
      val local = JsonLog(l => lines.synchronized(lines += l))
      assert(LocalRun.start(w.args :+ "--live", w.env, out += _, local, _ => 0) == Left(2))
      assert(
        LocalRun.start(w.args, w.env - "SCHOLARCHAIN_API_KEY", out += _, local, _ => 0) == Left(2)
      )
      val svcs =
        LocalRun.start(w.args, w.env, out += _, local, _ => 0).fold(c => fail(s"exit $c"), identity)
      try
        assert(svcs.map(_.name) == Vector("query-api", "graph-evidence-api", "worker"))
        val ports = lines.collect {
          case l if l.contains("\"event\":\"started\"") => ujson.read(l)("port").num.toInt
        }
        assert(ports.size == 3 && ports.distinct.size == 3)
        ports.foreach(p => assert(Client(p).get("/healthz", Seq()).status == 200))
      finally svcs.foreach(_.stop())
      assert(!(out ++ lines).exists(_.contains(ApiKey)))
    }
  }

/** Test-scope helper for the local smoke (not a test): writes the fixture store files under the
  * given directory and prints the LocalRun arguments.
  */
object WriteFixtureStores:
  def main(args: Array[String]): Unit =
    val root = java.nio.file.Paths.get(args(0)).toAbsolutePath
    java.nio.file.Files.createDirectories(root)
    println(ServiceFixtures.writeStores(root).mkString(" "))
