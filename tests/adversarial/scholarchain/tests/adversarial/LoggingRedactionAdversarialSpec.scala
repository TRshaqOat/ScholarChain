package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import scholarchain.services.*
import scholarchain.storage.*
import scholarchain.tests.support.StoreWorld.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** Phase 11 WO3, spec category "logging redaction" (Phase 10 ruling 12: one JSON line per request
  * and per stage; never bodies, questions, keys, or retrieved text). Every input channel carries a
  * SYNTHETIC marker; no log line and no error body may carry one back.
  */
class LoggingRedactionAdversarialSpec extends AnyFunSuite:
  private val Marker = "SYNTHETICSECRET"
  private val RequestFields =
    Set(
      "ts",
      "type",
      "service",
      "traceId",
      "requestId",
      "method",
      "route",
      "status",
      "latencyMs",
      "error"
    )
  private val StageFields =
    Set("ts", "type", "traceId", "requestId", "queryId", "jobId", "stage", "outcome", "latencyMs")

  private def failingKv(w: World) =
    w.backend.copy(ports = w.backend.ports.copy(kv = new KeyValueReadPort:
      def get(t: TableName, pk: String, sk: String): Option[Item] =
        throw IllegalStateException(s"$Marker-store-error endpoint=$Marker.internal")
      def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
        throw IllegalStateException(s"$Marker-store-error endpoint=$Marker.internal")))

  test("markers in the question, headers, query string, path, and body never reach a log line") {
    withWorld() { w =>
      val log = CaptureLog()
      val seen = scala.collection.mutable.ArrayBuffer.empty[String]
      w.query(log.log) { c =>
        // Error bodies and headers; an answer may name a person the caller's own question named.
        def keep(r: Reply) =
          if r.status >= 400 then seen += r.text
          seen ++= r.headers.values
          r
        keep(c.post("/v1/query", queryJson(s"Who is Ada Lovelace $Marker?")))
        keep(c.post("/v1/query", queryJson(s"Who is $Marker?", ", \"execution\": \"async\"")))
        keep(c.post("/v1/query", queryJson(), Seq("Idempotency-Key" -> s"$Marker-idem")))
        keep(c.post("/v1/query", queryJson(), Seq("X-Api-Key" -> s"$Marker-key")))
        keep(c.post("/v1/query", s"""{"question": "$Marker", "$Marker": 1}"""))
        keep(c.post("/v1/query", s"""{"question": "$Marker" """))
        keep(
          c.post("/v1/query", s"""{"question": "q", "analysis": {"rankingMetric": "$Marker"}}""")
        )
        keep(
          c.post(
            "/v1/query",
            s"""{"question": "q", "constraints": {"topics": ["$Marker"], "$Marker": 1}}"""
          )
        )
        keep(c.get(s"/v1/explain/qry-${"0" * 32}?$Marker=$Marker"))
        keep(c.get(s"/v1/explain/$Marker"))
        keep(c.get(s"/v1/jobs/${URLEncoder.encode(s"job-$Marker", UTF_8)}"))
        keep(c.get(s"/v1/$Marker/x"))
        keep(
          c.get("/healthz", Seq("User-Agent" -> Marker, "X-Request-Id" -> s"$Marker with space"))
        )
        keep(c.get("/metrics", Seq("X-Api-Key" -> ApiKey, "Referer" -> Marker)))
      }
      w.query(log.log, failingKv(w)) { c =>
        val r = c.post("/v1/query", queryJson())
        assert(r.status == 502, r.text)
        seen += r.text
      }
      val lines = log.all
      assert(lines.nonEmpty)
      lines.foreach { l =>
        val j = scala.util.Try(ujson.read(l)).getOrElse(fail(s"not JSON: $l"))
        val keys = j.obj.keySet.toSet
        j("type").str match
          case "request" => assert(keys == RequestFields, l)
          case "stage"   => assert(keys.subsetOf(StageFields), l)
          case "event"   => ()
          case other     => fail(s"unexpected log type $other")
        assert(!l.contains(Marker), s"log line carries a marker: $l")
      }
      // Routes are logged by template, never by raw path.
      assert(
        lines
          .filter(_.contains("\"type\":\"request\""))
          .forall(l =>
            Set(
              "/v1/query",
              "/v1/explain/{queryId}",
              "/v1/jobs/{jobId}",
              "unmatched",
              "/healthz",
              "/metrics"
            )
              .contains(ujson.read(l)("route").str)
          )
      )
      assert(!seen.exists(_.contains(Marker)), "a response echoes a marker")
      assert(!seen.exists(_.contains(ApiKey)), "a response holds the API key")
    }
  }

  test("worker events and job errors carry IDs and codes only, never the question") {
    withWorld("services.worker.poll-wait-seconds = 1") { w =>
      val log = CaptureLog()
      val q = InMemoryJobQueue(w.cfg.services.queue.maxReceiveCount)
      val b = w.backend.copy(queue = q)
      val wk = Worker(w.cfg, b, log.log, Metrics())
      try
        def submit(question: String, backend: Backend) =
          val qid =
            StableIds.queryId(backend.setup.runId, StableIds.textHash(question), Ids.fresh("t"), 1)
          assert(backend.jobs.create(qid, upickle.default.write(QueryBody(question))).isRight)
          backend.queue.send(JobMessage.of(qid))
          StableIds.jobId(qid)
        val ok = submit(s"Have Ada Lovelace and Grace Hopper coauthored a paper $Marker?", b)
        assert(wk.pollOnce() == 1)
        // A failing store: the job fails with a typed error that holds no store message either.
        val bad = b.copy(ports = failingKv(w).ports)
        val wk2 = Worker(w.cfg, bad, log.log, Metrics())
        try
          val failed =
            submit(s"Have Ada Lovelace and Grace Hopper coauthored a paper $Marker?", bad)
          assert(wk2.pollOnce() == 1)
          val j = bad.jobs.get(failed).toOption.flatten.get
          assert(j.state == scholarchain.core.JobState.FAILED, j)
          assert(j.error.exists(e => !e.message.contains(Marker)), j)
        finally wk2.stop()
        assert(
          log.all.nonEmpty && !log.all.exists(_.contains(Marker)),
          log.all.filter(_.contains(Marker))
        )
        assert(b.jobs.get(ok).toOption.flatten.exists(_.error.isEmpty))
      finally wk.stop()
    }
  }

  test("explain never returns question, prompt, retrieved, or model text") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        val m = Marker.toLowerCase
        val r = c.post(
          "/v1/query",
          queryJson(s"Have Ada Lovelace and Grace Hopper coauthored a paper $m?")
        )
        assert(r.status == 200, r.text)
        val x = c.get(r.body("links")("explain").str)
        assert(x.status == 200, x.text)
        val text = x.text
        assert(!text.contains(m), "explain holds the question")
        assert(!text.contains("SYNTHETIC-INJECTION"), "explain holds retrieved text")
      }
    }
  }
