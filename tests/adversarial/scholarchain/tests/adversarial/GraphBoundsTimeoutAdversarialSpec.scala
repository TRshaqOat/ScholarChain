package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.services.*
import scholarchain.storage.*
import scholarchain.tests.support.ScriptedGraph
import scholarchain.tests.support.StoreWorld.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** Phase 11 WO3, ruling 1 target "unbounded graph requests", spec categories "Neptune timeout" and
  * "API timeouts". A held store call is released by the test after the response, never by a sleep;
  * the service's own timeout is what answers.
  */
class GraphBoundsTimeoutAdversarialSpec extends AnyFunSuite:
  /** Path-segment encoding (a space is %20; URLEncoder's form encoding would send "+"). */
  private def enc(s: String) = URLEncoder.encode(s, UTF_8).replace("+", "%20")

  test("neighbor parameters: overflow, signs, whitespace, repeats, and forged cursors are 400") {
    withWorld() { w =>
      val log = CaptureLog()
      w.graphEvidence(log.log) { c =>
        val base = s"/v1/graph/nodes/${enc(grace)}/neighbors"
        val ok = "direction=both&depth=1&limit=10&edgeTypes=COAUTHORED_WITH"
        assert(c.get(s"$base?$ok").status == 200)
        Vector(
          "direction=both&depth=1&limit=2147483648&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=-1&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=1e2&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=0x10&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=%2010&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=99999999999&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=BOTH&depth=1&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=both&direction=in&depth=1&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=10&edgeTypes=coauthored_with",
          "direction=both&depth=1&limit=10&edgeTypes=COAUTHORED_WITH%0AMATCH%20(n)%20DETACH%20DELETE%20n",
          "direction=both&depth=1&limit=10&edgeTypes=COAUTHORED_WITH&limit=200"
        ).foreach { p =>
          val r = c.get(s"$base?$p")
          assert(r.status == 400 && r.error == "INVALID_FIELD", s"$p: ${r.status} ${r.text}")
        }
        // A cursor forged with the right shape but a huge offset is refused, whatever its binding.
        def cursor(s: String) =
          Base64.getUrlEncoder.withoutPadding.encodeToString(s.getBytes(UTF_8))
        val binding = Cursor.binding(grace, "BOTH", 1, Vector("COAUTHORED_WITH"))
        Vector(
          cursor(s"v1.${w.cfg.graphRead.maxResults}.$binding"),
          cursor(s"v1.2147483647.$binding"),
          cursor(s"v1.-10.$binding"),
          cursor(s"v1.99999999999999999999.$binding"),
          cursor(s"v2.0.$binding"),
          cursor(s"v1.0.$binding.extra"),
          "!!!not-base64!!!",
          "A" * 5000
        ).foreach { cur =>
          val r = c.get(s"$base?$ok&cursor=${enc(cur)}")
          assert(r.status == 400 && r.error == "INVALID_FIELD", s"$cur: ${r.status} ${r.text}")
        }
        // The largest legal page that still fits under max-results answers; one beyond does not.
        val last = w.cfg.graphRead.maxResults - 10
        assert(c.get(s"$base?$ok&cursor=${cursor(s"v1.$last.$binding")}").status == 200)
        assert(c.get(s"$base?$ok&cursor=${cursor(s"v1.${last + 1}.$binding")}").status == 400)
      }
    }
  }

  test("node IDs: injection shapes and oversized IDs are 404 without a graph read") {
    withWorld() { w =>
      val reads = AtomicInteger()
      val graph = ScriptedGraph(GraphReadLimits.from(w.cfg), _ => reads.incrementAndGet())
      val b = w.backend.copy(ports = w.backend.ports.copy(graph = graph))
      val log = CaptureLog()
      w.graphEvidence(log.log, b) { c =>
        Vector(
          "x') MATCH (n) DETACH DELETE n //",
          "orcid:0000-0002-1825-0097' OR '1'='1",
          "orcid:0000-0002-1825-0097\nMATCH (n) RETURN n",
          "orcid:" + "0" * 10000,
          "../../etc/passwd",
          "${jndi:ldap://127.0.0.1/x}",
          " orcid:0000-0002-1825-0097"
        ).foreach { id =>
          val before = reads.get
          val r = c.get(s"/v1/graph/nodes/${enc(id)}")
          assert(
            r.status == 404 && r.error == "NODE_NOT_FOUND",
            s"$id: ${r.status} ${r.text.take(200)}"
          )
          // An ID that fails the node-ID format never reaches the store (ruling 6); one that passes
          // it is only ever a bound parameter (templates are fixed), so a read is allowed.
          if !scholarchain.core.IdFormat.isNodeId(id) then
            assert(reads.get == before, s"$id reached the graph store")
          assert(!r.text.contains("DETACH") && !r.text.contains("jndi"))
        }
        assert(c.get(s"/v1/graph/nodes/${enc(graph.known)}").status == 200)
      }
    }
  }

  test("a Neptune query timeout is 504 DOWNSTREAM_TIMEOUT and counts toward the neptune breaker") {
    withWorld("services.resilience.breaker-failure-threshold = 2") { w =>
      val graph = ScriptedGraph(
        GraphReadLimits.from(w.cfg),
        _ => throw GraphReadTimeoutException("graph.vertex passed 5000 ms")
      )
      val b = w.backend.copy(ports = w.backend.ports.copy(graph = graph))
      val log = CaptureLog()
      val m = Metrics()
      w.serve(Apps.graphEvidenceApi(w.cfg, b, w.auth, log.log, m)) { c =>
        val id = enc(graph.known)
        Vector(1, 2).foreach { _ =>
          val r = c.get(s"/v1/graph/nodes/$id")
          assert(
            r.status == 504 && r.error == "DOWNSTREAM_TIMEOUT" && r.body("retryable").bool,
            r.text
          )
          assert(!r.text.contains("5000 ms passed") && !r.text.contains("graph.vertex"))
        }
        val open = c.get(s"/v1/graph/nodes/$id")
        assert(open.status == 503 && open.error == "CIRCUIT_OPEN", open.text)
        val ready = c.get("/readyz", Seq())
        assert(ready.status == 503 && ready.error == "NOT_READY")
        // Evidence lookups (DynamoDB) do not share the neptune breaker.
        assert(c.get(s"/v1/evidence/ev-${"a" * 32}").status == 404)
        assert(m.snapshot("counters")("circuitOpened.neptune").num == 1)
      }
    }
  }

  test("a Neptune read that never answers is cut at services.timeouts.neptune-ms (504)") {
    withWorld("services.timeouts.neptune-ms = 300\nneptune.query-timeout-ms = 200") { w =>
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val graph = ScriptedGraph(
        GraphReadLimits.from(w.cfg),
        id =>
          if id != "probe:readiness" then
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
      )
      val b = w.backend.copy(ports = w.backend.ports.copy(graph = graph))
      val log = CaptureLog()
      try
        w.graphEvidence(log.log, b) { c =>
          val r = c.get(s"/v1/graph/nodes/${enc(graph.known)}")
          // The read is still held (release is counted down only in finally), so this 504 is the
          // neptune-ms timeout, not a store answer.
          assert(r.status == 504 && r.error == "DOWNSTREAM_TIMEOUT", r.text)
          assert(release.getCount == 1)
          // Work order 4 (finding 4): the timeout may fire before the read thread is scheduled, so
          // the read is awaited rather than assumed started; the 30 s bound is a hang guard only.
          assert(entered.await(30, TimeUnit.SECONDS), "the graph read never started")
          // The held call does not block other routes or readiness.
          assert(c.get("/healthz", Seq()).status == 200)
          assert(c.get(s"/v1/evidence/ev-${"a" * 32}").status == 404)
        }
      finally release.countDown()
    }
  }

  test("the sync query deadline answers 504 while a store read is held; no breaker opens") {
    withWorld(
      "operations.synchronous-deadline-ms = 300\nservices.resilience.breaker-failure-threshold = 1"
    ) { w =>
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val kv = new KeyValueReadPort:
        def get(t: TableName, pk: String, sk: String): Option[Item] =
          entered.countDown(); release.await(60, TimeUnit.SECONDS);
          w.backend.ports.kv.get(t, pk, sk)
        def query(t: TableName, pk: String, sk: String, limit: Int): Vector[Item] =
          entered.countDown(); release.await(60, TimeUnit.SECONDS);
          w.backend.ports.kv.query(t, pk, sk, limit)
      val b = w.backend.copy(ports = w.backend.ports.copy(kv = kv))
      val log = CaptureLog()
      val m = Metrics()
      try
        w.query(log.log, b, m) { c =>
          val r = c.post("/v1/query", queryJson())
          // No store read has returned (release is counted down only in finally), so the 504 is
          // the synchronous deadline.
          assert(r.status == 504 && r.error == "DEADLINE_EXCEEDED", r.text)
          assert(release.getCount == 1)
          // Work order 4 (finding 4): on a cold JVM the deadline can pass before the workflow
          // reaches its first key-value read; the workflow goes on after the 504 (workflows are not
          // interrupted), so the read is awaited, never assumed. 30 s is a hang guard only.
          assert(entered.await(30, TimeUnit.SECONDS), "the workflow never reached a store read")
          assert(!m.snapshot("counters").obj.keys.exists(_.startsWith("circuitOpened")))
          assert(c.get("/readyz", Seq()).status == 200)
        }
      finally release.countDown()
    }
  }
