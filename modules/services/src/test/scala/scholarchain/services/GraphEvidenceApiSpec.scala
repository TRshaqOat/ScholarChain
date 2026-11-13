package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.storage.{EvidenceLookupPort, Item}
import upickle.default.read

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/** graph-evidence-api over a real localhost socket: evidence of an answered query, missing
  * evidence, graph nodes, bounded and paginated neighbors, timeouts, breakers, and load shedding.
  */
class GraphEvidenceApiSpec extends AnyFunSuite:
  import ServiceFixtures.*

  private def enc(s: String) = URLEncoder.encode(s, UTF_8)
  private def code(r: Reply, c: String, status: Int) =
    assert(r.status == status && r.error == c, r.body)
    assert(r.body("traceId").str == r.headers("X-Trace-Id"))

  private def lookup(w: World)(body: String => Unit) =
    w.backend.copy(evidence = new EvidenceLookupPort:
      def evidenceItem(id: String): Option[Item] =
        body(id); w.backend.evidence.evidenceItem(id))

  test("query to evidence: every evidence ID an answer cites resolves with provenance") {
    withWorld("services.evidence-max-content-chars = 10") { w =>
      val a = w.query() { c => read[SyncQueryResponse](c.post("/v1/query", queryJson()).body) }
      w.graphEvidence() { c =>
        a.links.evidence.foreach { link =>
          val r = c.get(link)
          assert(r.status == 200, r.body)
          val e = read[EvidenceResponse](r.body)
          assert(link.endsWith(e.evidenceId))
          assert(e.provenance.runId.nonEmpty && e.sourceUri.nonEmpty)
          assert(e.text.forall(_.length <= 10) && e.textTruncated == e.text.exists(_.length == 10))
        }
      }
    }
  }

  test("missing evidence is 404; a malformed ID never reaches the store") {
    withWorld() { w =>
      val calls = AtomicInteger()
      w.graphEvidence(lookup(w)(_ => calls.incrementAndGet())) { c =>
        code(c.get("/v1/evidence/ev-" + "a" * 32), "EVIDENCE_NOT_FOUND", 404)
        assert(calls.get == 1)
        code(c.get("/v1/evidence/not-an-id"), "EVIDENCE_NOT_FOUND", 404)
        code(c.get("/v1/evidence/" + enc("ev-" + "A" * 32)), "EVIDENCE_NOT_FOUND", 404)
        assert(calls.get == 1)
      }
    }
  }

  test("graph node: found with properties, unknown 404, encoded IDs with slashes work") {
    withWorld() { w =>
      w.graphEvidence() { c =>
        val r = c.get(s"/v1/graph/nodes/${enc(ada)}")
        assert(r.status == 200, r.body)
        val n = read[NodeResponse](r.body).node
        assert(
          n.nodeId == ada && n.label == "Researcher" && n
            .properties("displayName")
            .str == "Ada Lovelace"
        )
        val work = c.get(s"/v1/graph/nodes/${enc(ServiceFixtures.w(1))}")
        assert(work.status == 200 && work.body("node")("properties")("year").num == 2001)
        code(c.get(s"/v1/graph/nodes/${enc("dblp:conf/fx/W999")}"), "NODE_NOT_FOUND", 404)
        code(c.get("/v1/graph/nodes/no-namespace"), "NODE_NOT_FOUND", 404)
      }
    }
  }

  test("neighbors: bounded parameters, allowlisted edge types, and bound cursors") {
    withWorld() { w =>
      w.graphEvidence() { c =>
        val base = s"/v1/graph/nodes/${enc(grace)}/neighbors"
        def q(p: String) = c.get(s"$base?$p")
        val ok = "direction=both&depth=1&limit=10&edgeTypes=COAUTHORED_WITH"
        val r = q(ok)
        assert(r.status == 200, r.body)
        val n = read[NeighborsResponse](r.body)
        assert(
          n.nodes.map(_.nodeId).toSet == Set(ada, edsger) && !n.truncated && n.nextCursor.isEmpty
        )
        assert(n.edges.forall(_.edgeType == "COAUTHORED_WITH") && n.edges.size == 2)
        Vector(
          "direction=sideways&depth=1&limit=10&edgeTypes=COAUTHORED_WITH",
          "depth=1&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=0&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=4&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=x&limit=10&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=0&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=201&edgeTypes=COAUTHORED_WITH",
          "direction=both&depth=1&limit=10",
          "direction=both&depth=1&limit=10&edgeTypes=",
          "direction=both&depth=1&limit=10&edgeTypes=KNOWS",
          "direction=both&depth=1&limit=10&edgeTypes=COAUTHORED_WITH,",
          s"$ok&depth=2",
          s"$ok&maxNodes=5000",
          s"$ok&cursor=garbage"
        ).foreach(p => code(q(p), "INVALID_FIELD", 400))
        // depth 3 and limit 200 are the inclusive maxima.
        assert(q("direction=out&depth=3&limit=200&edgeTypes=AUTHORED,HAS_TOPIC").status == 200)
        code(
          c.get(s"/v1/graph/nodes/${enc("dblp:conf/fx/W999")}/neighbors?$ok"),
          "NODE_NOT_FOUND",
          404
        )

        // Pages of one node concatenate to the whole walk; a cursor is bound to its walk.
        val walk = "direction=both&depth=2&limit=1&edgeTypes=COAUTHORED_WITH,AUTHORED"
        var cursor: Option[String] = None
        val pages = Vector.newBuilder[String]
        var guard = 0
        while
          val p = read[NeighborsResponse](q(walk + cursor.fold("")(x => s"&cursor=$x")).body)
          pages ++= p.nodes.map(_.nodeId)
          cursor = p.nextCursor
          guard += 1
          cursor.isDefined && guard < 50
        do ()
        val all = read[NeighborsResponse](
          q("direction=both&depth=2&limit=200&edgeTypes=COAUTHORED_WITH,AUTHORED").body
        )
        assert(pages.result() == all.nodes.map(_.nodeId) && all.nodes.size > 2)
        val first = read[NeighborsResponse](q(walk).body).nextCursor.get
        code(
          q(s"direction=in&depth=2&limit=1&edgeTypes=COAUTHORED_WITH,AUTHORED&cursor=$first"),
          "INVALID_FIELD",
          400
        )
        code(
          c.get(s"/v1/graph/nodes/${enc(ada)}/neighbors?$walk&cursor=$first"),
          "INVALID_FIELD",
          400
        )
        // Edge types are a set: order and repeats do not change the binding.
        assert(
          q(
            s"direction=both&depth=2&limit=1&edgeTypes=AUTHORED,COAUTHORED_WITH,AUTHORED&cursor=$first"
          ).status == 200
        )
      }
    }
  }

  test("a slow store is 504, a failing one 502, then the breaker fails fast and readiness drops") {
    withWorld(
      "services.timeouts.dynamodb-ms = 200\nservices.resilience.breaker-failure-threshold = 2"
    ) { w =>
      w.graphEvidence(lookup(w)(_ => Thread.sleep(3000))) { c =>
        code(c.get("/v1/evidence/ev-" + "b" * 32), "DOWNSTREAM_TIMEOUT", 504)
      }
      w.graphEvidence(lookup(w)(_ => throw IllegalStateException("table gone"))) { c =>
        code(c.get("/v1/evidence/ev-" + "b" * 32), "DOWNSTREAM_FAILURE", 502)
        code(c.get("/v1/evidence/ev-" + "b" * 32), "DOWNSTREAM_FAILURE", 502)
        code(c.get("/v1/evidence/ev-" + "b" * 32), "CIRCUIT_OPEN", 503)
        // Other downstreams keep working; readiness reports the open breaker; liveness is fine.
        assert(c.get(s"/v1/graph/nodes/${enc(ada)}").status == 200)
        val ready = c.get("/readyz", Seq())
        code(ready, "NOT_READY", 503)
        assert(ready.body("message").str == "not ready: dynamodb unavailable")
        assert(c.get("/healthz", Seq()).status == 200)
      }
    }
  }

  test(
    "an IllegalArgumentException inside a downstream call is 500 with no detail (repair-01 m5)"
  ) {
    withWorld("services.resilience.breaker-failure-threshold = 1") { w =>
      val bad = "ev-" + "d" * 32
      val m = Metrics()
      val b = lookup(w)(id => require(id != bad, "internal-detail-of-a-server-check"))
      w.serve(Apps.graphEvidenceApi(w.cfg, b, w.auth, log, m)) { c =>
        (1 to 2).foreach { _ =>
          val r = c.get(s"/v1/evidence/$bad")
          expectError(r, "INTERNAL_ERROR", 500)
          assert(r.body("message").str == "unexpected internal failure")
          assert(!r.body.render().contains("internal-detail"))
        }
        // A server defect is no failure of the store: no breaker, and readiness holds.
        assert(c.get("/readyz", Seq()).status == 200)
        assert(!m.snapshot("counters").obj.keys.exists(_.startsWith("circuitOpened")))
        // Route validation is still a client error.
        code(c.get(s"/v1/graph/nodes/${enc(ada)}/neighbors?direction=up"), "INVALID_FIELD", 400)
      }
      assert(!logged.exists(_.contains("internal-detail")))
    }
  }

  test("beyond its threads and queue a service sheds load with 503 OVERLOADED") {
    withWorld(
      "services.graph-evidence-api.threads = 1\nservices.max-queued-requests = 1\nservices.timeouts.dynamodb-ms = 10000"
    ) { w =>
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val slow = lookup(w) { _ =>
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
      }
      w.graphEvidence(slow) { c =>
        val pool = Executors.newFixedThreadPool(2)
        try
          val busy = pool.submit(() => c.get("/v1/evidence/ev-" + "c" * 32))
          assert(entered.await(10, TimeUnit.SECONDS))
          val queued = pool.submit(() => c.get("/healthz", Seq()))
          Thread.sleep(300) // let the second request reach the queue
          code(c.get("/healthz", Seq()), "OVERLOADED", 503)
          release.countDown()
          assert(busy.get(10, TimeUnit.SECONDS).status == 404)
          assert(queued.get(10, TimeUnit.SECONDS).status == 200)
        finally
          release.countDown()
          pool.shutdownNow()
      }
    }
  }
