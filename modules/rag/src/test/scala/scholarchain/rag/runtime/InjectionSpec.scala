package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{QueryOperation, StableIds}
import scholarchain.rag.SemanticContext
import upickle.default.write

/** Review 1 F5 (ruling 21): injection text with <, >, &, ", and the closing tag of every prompt
  * delimiter, in the question, a title, a researcher name, and a retrieved document, sent through
  * the model planner and the synthesis prompt builders with a recording fake model. Each test fails
  * when the escaping of its block is removed (Planner.promptValues, Synthesis.promptValues,
  * EvidenceBuilder.render).
  */
class InjectionSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private val baseQ =
    "Have Ada Lovelace and Grace Hopper coauthored a paper, and who are Edsger Dijkstra's coauthors?"
  private val injectedQ = s"$baseQ $Injection"
  private def ok(r: Either[RuntimeFailure, RuntimeResult]) = r.fold(f => fail(s"$f"), identity)

  /** The model's plan: the pair's edge (W2's injected title) and Edsger's coauthors (Mallory's
    * injected name); Ada resolves, so her injected profile is retrieved.
    */
  private val plan = write(
    PlannerOutput(
      RuntimeSchemas.PlannerOutput,
      Vector(
        PlannedOperation(
          QueryOperation.DIRECT_COAUTHORS,
          OperationScope.PAIR,
          Vector(0, 1),
          None,
          None
        ),
        PlannedOperation(
          QueryOperation.DIRECT_COAUTHORS,
          OperationScope.RESEARCHER,
          Vector(2),
          None,
          None
        )
      ),
      Vector("Ada Lovelace", "Grace Hopper", "Edsger Dijkstra"),
      Vector.empty,
      WindowRequest(WindowKind.DEFAULT, None, None, None),
      None,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
  )
  private val edge = StableIds.evidenceId(
    scholarchain.core.EvidenceType.COAUTHOR_EDGE,
    Seq(adaGrace.id),
    graphRun
  )
  private val synthesis = write(
    SynthesisOutput(
      RuntimeSchemas.SynthesisOutput,
      "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
      Vector(
        ClaimDraft(
          "claim-1",
          ClaimKind.COAUTHORSHIP,
          "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
          Vector(edge),
          Vector(ada, grace),
          Some("all-years"),
          Map("jointPublicationCount" -> "2")
        )
      ),
      Vector.empty
    )
  )

  private def count(s: String, tag: String) =
    s.split(java.util.regex.Pattern.quote(tag), -1).length - 1

  private def run(q: String) =
    val llm = RecordedLlm(Vector(Right(plan), Right(synthesis)))
    withWorld(Some(llm))(w => (ok(w.ask(q)), llm.requests))

  test("planner prompt: one <question> block, holding the injected question only escaped") {
    val (_, requests) = run(injectedQ)
    val user = requests(0).user
    assert(count(user, "<question>") == 1 && count(user, "</question>") == 1, user)
    assert(user.contains(SemanticContext.escape(injectedQ)))
    assert(user.contains("&lt;/question&gt;") && user.contains("&amp;") && user.contains("&quot;"))
    ClosingTags.foreach(t => assert(!user.contains(t) || t == "</question>", t))
  }

  test("synthesis prompt: one block per delimiter; question, title, name, and document escaped") {
    val (r, requests) = run(injectedQ)
    val user = requests(1).user
    val b = r.bundle.get
    for block <- Vector("question", "graph_evidence", "notes") do
      assert(count(user, s"<$block>") == 1 && count(user, s"</$block>") == 1, block)
    assert(count(user, "<semantic_context ") == 1 && count(user, "</semantic_context>") == 1)
    assert(count(user, "<item ") == b.graphEvidence.size)
    assert(count(user, "</item>") == b.graphEvidence.size)
    assert(
      count(user, "<doc ") == b.semanticEvidence.size && count(
        user,
        "</doc>"
      ) == b.semanticEvidence.size
    )
    assert(b.semanticEvidence.nonEmpty)
    // Each injected text reaches the prompt, escaped: the question, W2's title, Mallory's name,
    // and Ada's profile document.
    assert(user.contains(SemanticContext.escape(injectedQ)))
    assert(user.contains(SemanticContext.escape(title(RuntimeFixtures.w(2)))))
    assert(user.contains(SemanticContext.escape(InjectedName)))
    assert(user.contains(SemanticContext.escape(profileText)))
    assert(!requests(1).system.contains(Injection) && !requests(0).user.contains(profileText))
  }

  test("the injected question changes no plan operation, store call, or claim") {
    def shape(r: RuntimeResult) =
      (
        r.answer.plan.source,
        r.answer.plan.operations,
        r.answer.plan.researcherNames.map(_.name),
        r.audit.templateCalls.map(c => (c.templateId, c.resultCount)),
        r.audit.embeddingCalls.map(_.index),
        r.answer.response.claims.map(c => (c.text, c.evidenceIds, c.verificationStatus)),
        r.answer.response.summary
      )
    val (base, _) = run(baseQ)
    val (inj, _) = run(injectedQ)
    assert(base.answer.plan.source == PlannerSource.MODEL)
    assert(shape(inj) == shape(base))
    assert(
      inj.answer.response.claims.map(_.verificationStatus) == Vector(
        scholarchain.core.VerificationStatus.VERIFIED
      )
    )
  }
