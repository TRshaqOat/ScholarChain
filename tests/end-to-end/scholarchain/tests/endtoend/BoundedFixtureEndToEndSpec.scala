package scholarchain.tests.endtoend

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{JobState, VerificationStatus}
import scholarchain.services.*
import scholarchain.storage.InMemoryJobQueue
import scholarchain.tests.support.StoreWorld.*
import upickle.default.read

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** Phase 11 WO3: one bounded end-to-end fixture across services, rag, storage, and core. The
  * synthetic world carries prompt injection in a title, a name, a profile chunk, and an abstract
  * chunk. Sync and async answers must be evidence-backed and equal, every cited ID must resolve
  * with provenance, the injected text never becomes a citation or a verified number (a title may be
  * quoted as data), and explain and the logs never carry it.
  */
class BoundedFixtureEndToEndSpec extends AnyFunSuite:

  test(
    "sync and async: verified claims, resolvable evidence, explain, and no injected text served"
  ) {
    withWorld("services.worker.poll-wait-seconds = 1") { w =>
      val log = CaptureLog()
      val q = InMemoryJobQueue(w.cfg.services.queue.maxReceiveCount)
      val b = w.backend.copy(queue = q)
      val worker = Worker(w.cfg, b, log.log, Metrics())
      try
        w.query(log.log, b) { api =>
          w.graphEvidence(log.log, b) { ge =>
            val s = api.post("/v1/query", queryJson())
            assert(s.status == 200, s.text)
            val sync = read[SyncQueryResponse](s.body)
            val verified = sync.claims.filter(_.verificationStatus == VerificationStatus.VERIFIED)
            assert(verified.nonEmpty && !sync.answer.abstained)
            sync.claims.flatMap(_.evidenceIds).distinct.foreach { id =>
              val e = ge.get(s"/v1/evidence/${URLEncoder.encode(id, UTF_8)}")
              assert(e.status == 200, e.text)
              val item = read[EvidenceResponse](e.body)
              assert(item.evidenceId == id && item.provenance.runId.startsWith("run-"))
              assert(!item.evidenceType.isSemantic || !verified.exists(_.evidenceIds == Vector(id)))
            }
            val x = api.get(sync.links.explain)
            assert(x.status == 200, x.text)
            assert(read[ExplainResponse](x.body).verification.verified == verified.size)

            val a = api.post("/v1/query", queryJson(extra = ", \"execution\": \"async\""))
            assert(a.status == 202, a.text)
            val job = read[AsyncQueryResponse](a.body)
            assert(worker.pollOnce() == 1)
            val status = read[JobResponse](api.get(job.statusUrl).body)
            assert(status.state == JobState.SUCCEEDED, status)
            val res = api.get(status.resultUrl.get)
            assert(res.status == 200)
            val async = read[SyncQueryResponse](res.body)
            // Same question, same stores: the same verified claims, whatever the path.
            assert(
              async.claims.map(c => (c.text, c.evidenceIds, c.verificationStatus)) ==
                sync.claims.map(c => (c.text, c.evidenceIds, c.verificationStatus))
            )

            // A title may be quoted as data; it must never become an asserted fact or a citation.
            Vector(sync, async).foreach { r =>
              assert(
                !r.claims.exists(_.evidenceIds.contains("ev-ffffffffffffffffffffffffffffffff"))
              )
              val injectedTitle = scholarchain.tests.support.StoreWorld
                .title(scholarchain.tests.support.StoreWorld.w(2))
              assert(
                !r.claims.exists(c =>
                  c.text.replace(injectedTitle, "").contains("999") &&
                    c.verificationStatus == VerificationStatus.VERIFIED
                )
              )
            }
            // explain never returns retrieved text (Phase 10 ruling 8).
            assert(!x.text.contains("SYNTHETIC-INJECTION") && !x.text.contains("DROP TABLE"))
          }
        }
      finally worker.stop()
      assert(!log.all.exists(l => l.contains("SYNTHETIC-INJECTION") || l.contains(ApiKey)))
    }
  }
