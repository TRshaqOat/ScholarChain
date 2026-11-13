package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{EvidenceItem, StableIds}
import scholarchain.storage.{AttributeValue, Hashing}
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8

/** The service-facing API (Phase 10 work order 1): one structured request under a deadline, and the
  * audit, answer, and evidence lookups, on the fixture world (no network).
  */
class RuntimeApiSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private val q = "Have Ada Lovelace and Grace Hopper coauthored a paper?"
  private def request(question: String) =
    QueryRequest(RuntimeSchemas.QueryRequest, "", Some("s-1"), question, None, None, None, None)

  test("answer: one request under a deadline gives the answer, the audit, and the bundle") {
    withWorld() { w =>
      val r =
        RuntimeApi.answer(request(q), w.ports, w.setup, 60000L).fold(f => fail(s"$f"), identity)
      assert(!r.deadlinePassed && r.answer.abstention.isEmpty && r.bundle.isDefined)
      assert(r.audit.queryId == r.answer.response.queryId)
      assert(r.answer.response.claims.exists(_.verificationStatus.toString == "VERIFIED"))
      // Structured fields keep overriding the model (ruling 4): a request window is applied.
      val ranged = request(q).copy(constraints =
        Some(RequestConstraints(Some(2000), Some(2014), None, None, None))
      )
      val rr = RuntimeApi.answer(ranged, w.ports, w.setup, 60000L).fold(f => fail(s"$f"), identity)
      assert(rr.answer.plan.window.window == "2000-2014")
      assert(rr.answer.plan.overriddenFields.nonEmpty)
    }
  }

  test("answer: a passed deadline abstains and is flagged; the audit is still written") {
    withWorld() { w =>
      // The fixture clock advances 1 ms per reading, so a 3 ms budget passes inside the workflow.
      val r = RuntimeApi.answer(request(q), w.ports, w.setup, 3L).fold(f => fail(s"$f"), identity)
      assert(r.deadlinePassed)
      assert(r.answer.abstention.map(_.reason).contains(AbstentionReason.BUDGET_EXHAUSTED))
      assert(r.answer.abstention.exists(_.detail.contains("3 ms")))
      assert(RuntimeApi.audit(r.audit.queryId, w.setup, w.audit.reader) == Right(Some(r.audit)))
    }
  }

  test("lookups: audit and answer by query ID, evidence by query and evidence ID") {
    withWorld() { w =>
      val r =
        RuntimeApi.answer(request(q), w.ports, w.setup, 60000L).fold(f => fail(s"$f"), identity)
      val id = r.audit.queryId
      assert(RuntimeApi.audit(id, w.setup, w.audit.reader) == Right(Some(r.audit)))
      assert(RuntimeApi.storedAnswer(id, w.setup, w.audit.reader) == Right(Some(r.answer)))
      assert(RuntimeApi.llmStubbed(r.audit)) // fake mode: the rule planner, no model
      val unknown = StableIds.queryId(w.setup.runId, "x", "y", 1)
      assert(RuntimeApi.audit(unknown, w.setup, w.audit.reader) == Right(None))
      // A malformed ID never becomes a store key.
      assert(RuntimeApi.audit("../../etc", w.setup, w.audit.reader) == Right(None))
      val item = r.bundle.get.graphEvidence.head
      assert(
        RuntimeApi.evidence(id, item.evidenceId, w.records.reader, w.audit.reader) ==
          Right(Some(item))
      )
      val cited = r.answer.response.claims.flatMap(_.evidenceIds).distinct
      assert(cited.nonEmpty)
      cited.foreach(e =>
        assert(RuntimeApi.evidence(id, e, w.records.reader, w.audit.reader).exists(_.isDefined), e)
      )
      val other = StableIds.evidenceId(item.evidenceType, Seq("nothing"), graphRun)
      assert(RuntimeApi.evidence(id, other, w.records.reader, w.audit.reader) == Right(None))
      assert(RuntimeApi.evidence(id, "ev-zz", w.records.reader, w.audit.reader) == Right(None))
    }
  }

  test("evidenceOf: a payloadRef object must match its payloadHash") {
    withWorld() { w =>
      val r =
        RuntimeApi.answer(request(q), w.ports, w.setup, 60000L).fold(f => fail(s"$f"), identity)
      val e: EvidenceItem = r.bundle.get.graphEvidence.head
      val bytes = write(e).getBytes(UTF_8)
      val key = Audit.evidenceKey(w.setup.snapshot, w.setup.runId, "qry-test", e.evidenceId)
      w.audit.loader.putNew(key, bytes)
      def item(hash: String) = Map(
        "payloadRef" -> AttributeValue.S(key),
        "payloadHash" -> AttributeValue.S(hash)
      )
      assert(RuntimeApi.evidenceOf(item(Hashing.sha256(bytes)), w.audit.reader) == Right(e))
      assert(
        RuntimeApi.evidenceOf(item(Hashing.sha256("x".getBytes(UTF_8))), w.audit.reader).isLeft
      )
      assert(RuntimeApi.evidenceOf(Map.empty, w.audit.reader).isLeft)
    }
  }
