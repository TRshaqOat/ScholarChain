package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.services.*
import scholarchain.storage.{Item, KeyValueReadPort, TableName}
import scholarchain.tests.support.StoreWorld.*

import java.net.http.HttpRequest.BodyPublishers.ofString
import java.util.concurrent.atomic.AtomicInteger

/** Phase 11 WO3, ruling 1 target "API authentication bypass" and spec category "API authentication,
  * authorization". query-api and graph-evidence-api on 127.0.0.1:0.
  */
class ApiAuthAdversarialSpec extends AnyFunSuite:

  private def counting(w: World, reads: AtomicInteger) =
    w.backend.copy(ports = w.backend.ports.copy(kv = new KeyValueReadPort:
      def get(t: TableName, pk: String, sk: String): Option[Item] =
        reads.incrementAndGet(); w.backend.ports.kv.get(t, pk, sk)
      def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
        reads.incrementAndGet(); w.backend.ports.kv.query(t, pk, sk, limit)))

  private def unauthorized(r: Reply, what: String): Unit =
    assert(r.status == 401, s"$what: ${r.status} ${r.text}")
    assert(r.error == "UNAUTHORIZED", what)
    assert(r.body.obj.keySet == Set("error", "message", "traceId", "requestId", "retryable"), what)

  test("path and method tricks never reach a protected query-api route without a valid key") {
    withWorld() { w =>
      val log = CaptureLog()
      val reads = AtomicInteger()
      w.query(log.log, counting(w, reads)) { c =>
        val body = Some(java.net.http.HttpRequest.BodyPublishers.ofString(queryJson()))
        val noKey = Seq("Content-Type" -> "application/json")
        val paths = Vector(
          "/v1/query",
          "/v1/query/", // trailing slash
          "/v1/%71uery", // percent-encoded 'q' decodes to the protected route
          "/v1/./query",
          "/healthz/../v1/query",
          "/readyz/../v1/query"
        )
        val keyHeaders = Vector(
          noKey,
          noKey :+ ("X-Api-Key" -> ""),
          noKey :+ ("X-Api-Key" -> ApiKey.toUpperCase),
          noKey :+ ("X-Api-Key" -> ApiKey.take(ApiKey.length / 2)),
          noKey :+ ("Authorization" -> s"Bearer $ApiKey"),
          noKey :+ ("X-API-KEY-OVERRIDE" -> ApiKey),
          noKey :+ ("X-Api-Key" -> s"$ApiKey,$ApiKey")
        )
        for p <- paths; h <- keyHeaders do unauthorized(c.send("POST", p, body, h), s"POST $p $h")
        // GET routes, metrics, and lookalikes of the public routes.
        val gets = Vector(
          "/metrics",
          "/metrics/",
          "/healthz/../metrics",
          "/healthz/x",
          "/readyz/metrics",
          s"/v1/jobs/job-${"0" * 32}",
          s"/v1/jobs/job-${"0" * 32}/result",
          s"/v1/explain/qry-${"0" * 32}",
          "/v1/explain/%2E%2E%2Fmetrics"
        )
        gets.foreach(p => unauthorized(c.get(p, Seq()), s"GET $p"))
        // The method override headers some frameworks honour do not reach POST without a key.
        unauthorized(
          c.send("GET", "/v1/query", None, Seq("X-HTTP-Method-Override" -> "POST")),
          "override"
        )
        assert(reads.get == 0, "an unauthenticated request reached the key-value store")
        // A valid key under a different header-name case is the same header (HTTP names are
        // case-insensitive): it is accepted.
        val lower = c.send("POST", "/v1/query", body, noKey :+ ("x-api-key" -> ApiKey))
        assert(lower.status == 200, lower.text)
      }
      assert(!log.all.exists(_.contains(ApiKey)), "a log line holds the API key")
    }
  }

  test("graph-evidence-api: no key, no graph or evidence read; encoded IDs do not change that") {
    withWorld() { w =>
      val log = CaptureLog()
      w.graphEvidence(log.log) { c =>
        Vector(
          s"/v1/graph/nodes/${java.net.URLEncoder.encode(ada, "UTF-8")}",
          s"/v1/graph/nodes/${java.net.URLEncoder.encode(ada, "UTF-8")}/neighbors?direction=both&depth=1&limit=1&edgeTypes=COAUTHORED_WITH",
          s"/v1/evidence/ev-${"a" * 32}",
          "/v1/graph/nodes/%2Fhealthz",
          "/v1/evidence/..%2F..%2Fhealthz"
        ).foreach(p => unauthorized(c.get(p, Seq()), p))
        assert(c.get("/healthz", Seq()).status == 200)
      }
    }
  }

  test("unauthenticated requests do not consume the per-instance query rate budget") {
    withWorld("services.query-api.query-rate-per-minute = 2") { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        val guess = Seq("Content-Type" -> "application/json", "X-Api-Key" -> "attacker-guess")
        (1 to 25).foreach { _ =>
          unauthorized(c.send("POST", "/v1/query", Some(ofString(queryJson())), guess), "flood")
        }
        // Both accepted queries of the minute are still available to the key holder.
        assert(c.post("/v1/query", queryJson()).status == 200)
        assert(c.post("/v1/query", queryJson()).status == 200)
        assert(c.post("/v1/query", queryJson()).status == 429)
      }
    }
  }

  test("an unauthenticated oversized body is refused as 401, not read and not 400") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        val huge = queryJson("x" * (2 * w.cfg.services.maxBodyBytes))
        val nope = Seq("Content-Type" -> "application/json", "X-Api-Key" -> "nope")
        unauthorized(c.send("POST", "/v1/query", Some(ofString(huge)), nope), "huge body")
      }
    }
  }

  test("the API key comparison accepts exactly one value, of any length or content") {
    val a = ApiKeyAuth(ApiKey)
    assert(a.accepts(Some(ApiKey)))
    Vector(
      None,
      Some(""),
      Some(ApiKey + " "),
      Some(" " + ApiKey),
      Some(ApiKey + "\u0000"),
      Some(ApiKey.dropRight(1)),
      Some(ApiKey.reverse),
      Some(ApiKey * 2),
      Some("é" + ApiKey.drop(1))
    ).foreach(h => assert(!a.accepts(h), h))
    assert(scala.util.Try(ApiKeyAuth("")).isFailure, "an empty key must never be configured")
    assert(!a.toString.contains(ApiKey))
  }
