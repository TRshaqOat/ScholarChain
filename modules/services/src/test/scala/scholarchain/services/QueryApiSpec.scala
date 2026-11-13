package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.rag.runtime.{Audit, RuntimeApi, RuntimeSchemas}
import scholarchain.storage.{Item, KeyValueReadPort, TableName}
import upickle.default.{read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger

/** query-api over a real localhost socket: sync answers, validation, idempotency, deadlines and
  * downstream failures, authentication, rate limits, explain, and metrics (Phase 10 rulings 5 to
  * 9).
  */
class QueryApiSpec extends AnyFunSuite:
  import ServiceFixtures.*

  private def errorShape(r: Reply, code: String, status: Int): Unit =
    assert(r.status == status, r.body)
    assert(r.error == code, r.body)
    val traceId = r.body("traceId").str
    assert(traceId.nonEmpty && r.headers.get("X-Trace-Id").contains(traceId))
    assert(r.headers.get("X-Request-Id").contains(r.body("requestId").str))
    assert(r.body("retryable").bool == scholarchain.core.ErrorCode.valueOf(code).retryable)
    assert(r.body.obj.keySet == Set("error", "message", "traceId", "requestId", "retryable"))

  test("a synchronous query returns an evidence-backed answer with links; IDs are echoed") {
    withWorld() { w =>
      w.query() { c =>
        val r = c.post(
          "/v1/query",
          queryJson(),
          Seq("X-Request-Id" -> "client-req-1", "X-Trace-Id" -> "client-trace-1")
        )
        assert(r.status == 200, r.body)
        assert(r.headers.get("X-Request-Id").contains("client-req-1"))
        assert(r.headers.get("X-Trace-Id").contains("client-trace-1"))
        assert(r.headers.get("Content-Type").exists(_.startsWith("application/json")))
        val a = read[SyncQueryResponse](r.body)
        assert(a.mode == "synchronous" && !a.answer.abstained)
        assert(a.claims.exists(_.verificationStatus.toString == "VERIFIED"), r.body)
        assert(a.claims.forall(_.evidenceIds.nonEmpty))
        assert(a.links.explain == s"/v1/explain/${a.queryId}")
        assert(
          a.links.evidence.nonEmpty && a.links.evidence.forall(_.startsWith("/v1/evidence/ev-"))
        )
        // An invalid client request ID is replaced.
        val bad = c.post("/v1/query", queryJson(), Seq("X-Request-Id" -> "has space"))
        assert(bad.headers("X-Request-Id").startsWith("req-"))
        // The same body without a key is a new query.
        assert(read[SyncQueryResponse](bad.body).queryId != a.queryId)
        // One log line per request and per workflow stage, with the trace ID.
        val stages =
          logged.filter(l => l.contains("\"type\":\"stage\"") && l.contains("client-trace-1"))
        assert(stages.size == 10, stages)
        assert(
          logged.exists(l =>
            l.contains("\"type\":\"request\"") && l.contains("client-trace-1") &&
              l.contains("\"route\":\"/v1/query\"") && l.contains("\"status\":200")
          )
        )
      }
    }
  }

  test("malformed JSON, invalid fields, a wrong Content-Type, and an oversized body are 400") {
    withWorld("services.max-body-bytes = 2048") { w =>
      w.query() { c =>
        errorShape(c.post("/v1/query", "{\"question\": "), "MALFORMED_JSON", 400)
        errorShape(c.post("/v1/query", "[1, 2]"), "INVALID_FIELD", 400)
        // Types are checked on the JSON value (uPickle alone would read 7 or null as a String).
        Vector(
          "{\"question\": 7}",
          "{\"question\": null}",
          "{\"question\": [\"a\"]}",
          queryJson(extra = ", \"execution\": null"),
          queryJson(extra = ", \"constraints\": {\"yearFrom\": \"2015\"}"),
          queryJson(extra = ", \"constraints\": {\"topics\": \"systems\"}"),
          queryJson(extra = ", \"constraints\": {\"venues\": [1]}"),
          queryJson(extra = ", \"constraints\": 3"),
          queryJson(extra = ", \"retrieval\": {\"graphLimit\": 1.5}"),
          queryJson(extra = ", \"retrieval\": {\"graphLimit\": 1e12}"),
          queryJson(extra = ", \"analysis\": {\"includePaths\": \"yes\"}"),
          queryJson(extra = ", \"output\": {\"includeEvidence\": null}"),
          queryJson(extra = ", \"output\": {\"verbose\": true}")
        ).foreach(b => errorShape(c.post("/v1/query", b), "INVALID_FIELD", 400))
        errorShape(c.post("/v1/query", "{}"), "INVALID_FIELD", 400)
        errorShape(c.post("/v1/query", queryJson("   ")), "INVALID_FIELD", 400)
        errorShape(c.post("/v1/query", queryJson(extra = ", \"colour\": 1")), "INVALID_FIELD", 400)
        errorShape(
          c.post("/v1/query", queryJson(extra = ", \"execution\": \"later\"")),
          "INVALID_FIELD",
          400
        )
        errorShape(
          c.post(
            "/v1/query",
            queryJson(extra = ", \"constraints\": {\"yearFrom\": 2020, \"yearTo\": 2010}")
          ),
          "INVALID_FIELD",
          400
        )
        errorShape(
          c.post("/v1/query", queryJson(extra = ", \"retrieval\": {\"graphLimit\": 0}")),
          "INVALID_FIELD",
          400
        )
        errorShape(
          c.send(
            "POST",
            "/v1/query",
            Some(queryJson()),
            Seq("X-Api-Key" -> ApiKey, "Content-Type" -> "text/plain")
          ),
          "INVALID_FIELD",
          400
        )
        errorShape(c.post("/v1/query", queryJson("q" * 3000)), "REQUEST_TOO_LARGE", 400)
        errorShape(
          c.post("/v1/query", queryJson(), Seq("Idempotency-Key" -> "")),
          "INVALID_FIELD",
          400
        )
        errorShape(c.get("/v1/nothing"), "ROUTE_NOT_FOUND", 404)
        errorShape(c.get("/v1/query"), "ROUTE_NOT_FOUND", 404)
      }
    }
  }

  test("valid JSON with unsupported constraints or retrieval limits is 422") {
    withWorld() { w =>
      w.query() { c =>
        val cases = Vector(
          ", \"constraints\": {\"countries\": [\"NZ\"]}" -> "UNSUPPORTED_CONSTRAINT",
          ", \"analysis\": {\"rankingMetric\": \"WEIGHTED_DEGREE\"}" -> "UNSUPPORTED_CONSTRAINT",
          ", \"analysis\": {\"rankingMetric\": \"H_INDEX\"}" -> "UNSUPPORTED_CONSTRAINT",
          ", \"constraints\": {\"topics\": [\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}" -> "UNSUPPORTED_CONSTRAINT",
          ", \"retrieval\": {\"graphLimit\": 100000}" -> "UNSUPPORTED_RETRIEVAL_MODE",
          ", \"retrieval\": {\"hybrid\": true}" -> "UNSUPPORTED_RETRIEVAL_MODE"
        )
        cases.foreach((extra, code) =>
          errorShape(c.post("/v1/query", queryJson(extra = extra)), code, 422)
        )
        // A supported ranking metric and explicit nulls are accepted.
        val ok = c.post(
          "/v1/query",
          queryJson(extra =
            ", \"analysis\": {\"rankingMetric\": \"DISTINCT_COAUTHOR_COUNT\"}, \"constraints\": {\"yearFrom\": null}"
          )
        )
        assert(ok.status == 200, ok.body)
      }
    }
  }

  test("Idempotency-Key: the same body replays the stored answer; a different body is 409") {
    withWorld() { w =>
      val calls = AtomicInteger()
      val counting = w.backend.copy(ports = w.backend.ports.copy(kv = new KeyValueReadPort:
        def get(t: TableName, pk: String, sk: String): Option[Item] =
          calls.incrementAndGet(); w.backend.ports.kv.get(t, pk, sk)
        def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
          calls.incrementAndGet(); w.backend.ports.kv.query(t, pk, sk, limit)))
      w.query(counting) { c =>
        val key = Seq("Idempotency-Key" -> "order-42")
        val first = c.post("/v1/query", queryJson(), key)
        assert(first.status == 200 && first.headers.get("Idempotent-Replay").isEmpty, first.body)
        val reads = calls.get
        // Same key, same canonical body (other whitespace and an explicit default): the replay.
        val again = c.post(
          "/v1/query",
          s"""{ "execution":"sync",  "question" : ${ujson.write(Question)} }""",
          key
        )
        assert(again.status == 200 && again.headers.get("Idempotent-Replay").contains("true"))
        assert(again.body == first.body)
        assert(calls.get == reads, "the replay ran the workflow again")
        errorShape(
          c.post("/v1/query", queryJson("Who is Grace Hopper?"), key),
          "IDEMPOTENCY_CONFLICT",
          409
        )
        // A claimed key whose query has no stored answer (still running or failed) is 409 too.
        val unknown = scholarchain.core.StableIds.queryId(w.backend.setup.runId, "x", "y", 1)
        val hash = QueryValidation.requestHash(QueryBody("Who?"))
        assert(w.backend.idempotency.claim("pending", hash, unknown).isRight)
        errorShape(
          c.post("/v1/query", queryJson("Who?"), Seq("Idempotency-Key" -> "pending")),
          "IDEMPOTENCY_CONFLICT",
          409
        )
      }
    }
  }

  test("a passed deadline is 504 (cooperative and hard); a failing store is 502; breakers open") {
    withWorld(
      "operations.synchronous-deadline-ms = 300\nservices.resilience.breaker-failure-threshold = 2"
    ) { w =>
      def kv(body: => Unit) = w.backend.copy(ports = w.backend.ports.copy(kv = new KeyValueReadPort:
        def get(t: TableName, pk: String, sk: String): Option[Item] =
          body; w.backend.ports.kv.get(t, pk, sk)
        def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
          body; w.backend.ports.kv.query(t, pk, sk, limit)))
      // Every read sleeps 120 ms: the runtime's own deadline check ends it (cooperative).
      w.query(kv(Thread.sleep(120))) { c =>
        errorShape(c.post("/v1/query", queryJson()), "DEADLINE_EXCEEDED", 504)
      }
      // One read hangs past the deadline: the service's hard timeout answers at the deadline.
      w.query(kv(Thread.sleep(5000))) { c =>
        val t0 = System.nanoTime()
        errorShape(c.post("/v1/query", queryJson()), "DEADLINE_EXCEEDED", 504)
        assert((System.nanoTime() - t0) / 1000000 < 3000)
        // A deadline is no downstream failure: the breaker stays closed.
        errorShape(c.post("/v1/query", queryJson()), "DEADLINE_EXCEEDED", 504)
      }
      val m = Metrics()
      w.query(kv(throw IllegalStateException("store down: secret-endpoint.internal")), m) { c =>
        val r = c.post("/v1/query", queryJson())
        errorShape(r, "DOWNSTREAM_FAILURE", 502)
        assert(!r.body.render().contains("secret-endpoint"))
        errorShape(c.post("/v1/query", queryJson()), "DOWNSTREAM_FAILURE", 502)
        // Two consecutive failures open the runtime breaker: fail fast, and not ready.
        errorShape(c.post("/v1/query", queryJson()), "CIRCUIT_OPEN", 503)
        errorShape(c.get("/readyz", Seq()), "NOT_READY", 503)
        assert(c.get("/healthz", Seq()).status == 200)
        assert(m.snapshot("counters")("circuitOpened.runtime").num == 1)
        assert(m.snapshot("counters")("timeouts").num == 0)
      }
    }
  }

  test("missing or wrong API key is 401 everywhere except /healthz and /readyz") {
    withWorld() { w =>
      w.query() { c =>
        val none = Seq("Content-Type" -> "application/json")
        val wrong = none :+ ("X-Api-Key" -> "wrong-key")
        Seq(
          none,
          wrong,
          none :+ ("X-Api-Key" -> (ApiKey + "x")),
          none :+ ("X-Api-Key" -> ApiKey.dropRight(1))
        ).foreach { h =>
          errorShape(c.send("POST", "/v1/query", Some(queryJson()), h), "UNAUTHORIZED", 401)
          errorShape(c.get("/metrics", h.filter(_._1 != "Content-Type")), "UNAUTHORIZED", 401)
          errorShape(c.get("/v1/explain/qry-" + "0" * 32, h), "UNAUTHORIZED", 401)
          errorShape(c.get("/v1/unknown", h), "UNAUTHORIZED", 401)
        }
        assert(c.get("/healthz", Seq()).status == 200)
        assert(c.get("/readyz", Seq()).status == 200)
      }
    }
  }

  test("health and readiness differ; neither reveals configuration") {
    withWorld() { w =>
      val down = w.backend.copy(ports =
        w.backend.ports.copy(auditReader = new scholarchain.storage.ObjectReadPort:
          def get(key: String): Option[Array[Byte]] = throw IllegalStateException("s3 down")
          def list(prefix: String): Vector[String] = Vector.empty)
      )
      w.query(down) { c =>
        val h = c.get("/healthz", Seq())
        assert(h.status == 200 && h.body == ujson.Obj("status" -> "ok"))
        val r = c.get("/readyz", Seq())
        errorShape(r, "NOT_READY", 503)
        assert(r.body("message").str == "not ready: s3 unavailable")
      }
      w.query() { c =>
        val r = c.get("/readyz", Seq())
        assert(r.status == 200 && r.body == ujson.Obj("status" -> "ready"))
        val text = (r.body.render() + c.get("/healthz", Seq()).body.render()).toLowerCase
        Seq(
          ApiKey,
          "scholarchain_api_key",
          "8080",
          "neptune.example",
          "example-scholarchain",
          w.root.toString.toLowerCase
        )
          .foreach(s => assert(!text.contains(s.toLowerCase), s))
      }
    }
  }

  test("explain returns the plan, stages, versions, evidence, and counters; never hidden text") {
    withWorld() { w =>
      w.query() { c =>
        val a = read[SyncQueryResponse](c.post("/v1/query", queryJson()).body)
        val e = c.get(s"/v1/explain/${a.queryId}")
        assert(e.status == 200, e.body)
        val x = read[ExplainResponse](e.body)
        assert(x.queryId == a.queryId && x.llm == "STUBBED" && x.mode.toString == "FAKE")
        assert(x.stages.size == 10 && x.templateIds.nonEmpty && x.counters.cacheHits == 0)
        assert(x.promptVersions.nonEmpty && x.modelIds.contains("synthesis"))
        assert(
          a.claims
            .flatMap(_.evidenceIds)
            .toSet
            .subsetOf(x.graphEvidenceIds.toSet ++ x.semanticEvidenceIds)
        )
        assert(x.verification.verified >= 1)
        assert(!e.body.render().contains(Question))
        // Malformed and unknown IDs are 404; a malformed one never reaches the store.
        errorShape(c.get("/v1/explain/..%2F..%2Fetc"), "QUERY_NOT_FOUND", 404)
        errorShape(c.get("/v1/explain/qry-" + "0" * 32), "QUERY_NOT_FOUND", 404)
        errorShape(c.get(s"/v1/explain/${a.queryId}?verbose=1"), "INVALID_FIELD", 400)

        // An audit whose removed claims and left-out lines hold model text and check details.
        val audit = RuntimeApi
          .audit(a.queryId, w.backend.setup, w.backend.ports.auditReader)
          .toOption
          .flatten
          .get
        val secret = "MODEL-TEXT-7d1e"
        val planted = audit.copy(
          queryId = scholarchain.core.StableIds.queryId(w.backend.setup.runId, "p", "q", 1),
          removedClaims = Vector(
            scholarchain.rag.runtime.RemovedClaim(
              "claim-9",
              scholarchain.rag.runtime.ClaimKind.METRIC_VALUE,
              s"$secret claim",
              scholarchain.core.VerificationStatus.CONFLICTING,
              Vector(
                scholarchain.rag.runtime.CheckResult(
                  "asserted-values",
                  scholarchain.rag.runtime.CheckOutcome.CONFLICT,
                  s"x: the claim says $secret"
                )
              )
            )
          ),
          droppedLines = Vector(
            scholarchain.rag.runtime.DroppedLine(
              scholarchain.rag.runtime.LinePart.SUMMARY,
              s"$secret line",
              Vector(s"names $secret")
            )
          )
        )
        val key = Audit.auditKey(w.backend.setup.snapshot, w.backend.setup.runId, planted.queryId)
        assert(w.backend.ports.auditWriter.putNew(key, write(planted).getBytes(UTF_8)).isRight)
        val p = c.get(s"/v1/explain/${planted.queryId}")
        assert(p.status == 200, p.body)
        assert(!p.body.render().contains(secret), p.body)
        assert(p.body("removedClaims")(0)("failedRules")(0)("rule").str == "asserted-values")
        assert(p.body("droppedLines")(0)("reasonCount").num == 1)
        assert(audit.schemaVersion == RuntimeSchemas.Audit)
      }
    }
  }

  test("POST /v1/query is rate limited per instance (429 with Retry-After)") {
    withWorld("services.query-api.query-rate-per-minute = 2") { w =>
      w.query() { c =>
        assert(c.post("/v1/query", queryJson()).status == 200)
        assert(c.post("/v1/query", queryJson()).status == 200)
        val r = c.post("/v1/query", queryJson())
        errorShape(r, "RATE_LIMITED", 429)
        assert(r.headers.get("Retry-After").contains("60"))
        // Reads are not limited.
        assert(c.get("/healthz", Seq()).status == 200)
      }
    }
  }

  test("metrics: counts by route and status, latency percentiles, and answer counters") {
    withWorld() { w =>
      w.query() { c =>
        assert(c.post("/v1/query", queryJson()).status == 200)
        c.post("/v1/query", "nope")
        val m = c.get("/metrics")
        assert(m.status == 200, m.body)
        assert(m.body("requests")("/v1/query")("200").num == 1)
        assert(m.body("requests")("/v1/query")("400").num == 1)
        assert(m.body("latencyMs")("/v1/query")("count").num == 2)
        Seq("p50", "p95", "p99").foreach(p => assert(m.body("latencyMs")("/v1/query")(p).num >= 0))
        val counters = m.body("counters")
        assert(counters("answers").num == 1 && counters("verifiedClaims").num >= 1)
        Seq(
          "timeouts",
          "abstentions",
          "llmRequests",
          "llmInputTokens",
          "llmOutputTokens",
          "queueReceives"
        )
          .foreach(k => assert(counters.obj.contains(k), k))
      }
    }
  }

  test("no API key, question, or store error text appears in any log line or response") {
    withWorld() { w =>
      w.query() { c =>
        c.post("/v1/query", queryJson())
        c.post("/v1/query", queryJson(), Seq("X-Api-Key" -> ApiKey.reverse))
        c.post("/v1/query", "{bad")
        c.get("/metrics")
      }
      w.graphEvidence() { c => c.get("/v1/graph/nodes/x") }
    }
    val lines = logged
    assert(lines.nonEmpty)
    assert(lines.forall(l => scala.util.Try(ujson.read(l)).isSuccess))
    Seq(ApiKey, ApiKey.reverse, Question, "secret-endpoint").foreach { s =>
      assert(!lines.exists(_.contains(s)), s"log line holds '$s'")
    }
    assert(!seen.synchronized(seen.toVector).exists(_.contains(ApiKey)), "a response holds the key")
    assert(!ApiKeyAuth(ApiKey).toString.contains(ApiKey))
  }

  test(
    "launcher: fake mode starts on localhost; a missing key or incomplete live arguments stop it"
  ) {
    withWorld() { w =>
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]
      val local = JsonLog(l => lines.synchronized(lines += l))
      def start(args: Seq[String], env: Map[String, String]) =
        Launcher.start(args, Apps.queryApi(_, _, _, _), _ => 0, env, out += _, local)
      assert(start(w.args, w.env - "SCHOLARCHAIN_API_KEY") == Left(2))
      assert(out.exists(_.contains("set the environment variable SCHOLARCHAIN_API_KEY")))
      assert(start(w.args, w.env + ("SCHOLARCHAIN_API_KEY" -> "")) == Left(2))
      assert(start(Seq("--live", "--profile", "p"), w.env) == Left(1))
      assert(out.exists(_.contains("missing --snapshot")))
      assert(start(w.args :+ "--bogus", w.env) == Left(2))
      val svc = start(w.args, w.env).fold(c => fail(s"exit $c: ${out.mkString("\n")}"), identity)
      try
        val started = ujson.read(lines.find(_.contains("\"event\":\"started\"")).get)
        val c = Client(started("port").num.toInt)
        assert(c.get("/healthz", Seq()).status == 200)
        assert(c.post("/v1/query", queryJson()).status == 200)
      finally svc.stop()
      assert(!(out ++ lines).exists(_.contains(ApiKey)))
    }
  }

  test("readiness probes S3 under the audit prefix query-api and the worker read (repair-01 m1)") {
    withWorld() { w =>
      val keys = java.util.concurrent.ConcurrentLinkedQueue[String]()
      val orig = w.backend.ports.auditReader
      val recording = w.backend.copy(ports =
        w.backend.ports.copy(auditReader = new scholarchain.storage.ObjectReadPort:
          def get(key: String): Option[Array[Byte]] =
            keys.add(key); orig.get(key)
          def list(prefix: String): Vector[String] = orig.list(prefix))
      )
      val probe =
        s"${Audit.base("logs", w.backend.setup.snapshot, w.backend.setup.runId)}/readiness-probe"
      w.query(recording) { c => assert(c.get("/readyz", Seq()).status == 200) }
      assert(keys.toArray.toVector == Vector(probe))
      keys.clear()
      val wk = Worker(w.cfg, recording, log, Metrics())
      try wk.readiness.foreach(_._2())
      finally wk.ds.shutdown()
      assert(keys.toArray.toVector == Vector(probe))
    }
  }
