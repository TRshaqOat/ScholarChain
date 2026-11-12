package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.QueryOperation
import scholarchain.rag.cli.Benchmark
import scholarchain.rag.live.HaikuMessages
import upickle.default.write

import java.nio.file.Paths

/** Model output handling (ruling 23 Haiku output validation, ruling 27, review 1 m5): one enclosing
  * Markdown code fence is accepted, prose is not, every rejected output records its rule names and
  * shape (never its text), and every Bedrock request is counted. Recorded fixtures only.
  */
class LlmSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private val cfg = config
  private val prompts = PromptSet
    .load(Paths.get(cfg.runtime.promptsDir), "planner-v2", "synthesis-v2")
    .fold(sys.error, identity)
  private val q = "Have Ada Lovelace and Grace Hopper coauthored a paper?"
  private val plan = PlannerOutput(
    RuntimeSchemas.PlannerOutput,
    Vector(
      PlannedOperation(
        QueryOperation.DIRECT_COAUTHORS,
        OperationScope.PAIR,
        Vector(0, 1),
        None,
        None
      )
    ),
    Vector("Ada Lovelace", "Grace Hopper"),
    Vector.empty,
    WindowRequest(WindowKind.DEFAULT, None, None, None),
    None,
    Vector.empty,
    Vector.empty,
    Vector.empty
  )
  // Pretty-printed like a model reply (the shape Haiku produced in the live adjudication evidence).
  private val pretty = write(plan, indent = 2)
  private def parse(text: String) = Planner.parseModel(text, prompts.planner.schema, q, cfg)

  test("one enclosing code fence is accepted; prose, two objects, or an unclosed fence are not") {
    assert(parse(s"```json\n$pretty\n```").contains(plan))
    assert(parse(s"```\n$pretty\n```\n").contains(plan))
    assert(parse(s"  ```JSON\r\n$pretty\r\n```  ").contains(plan))
    for bad <- Vector(
        s"Here is the plan:\n```json\n$pretty\n```",
        s"```json\n$pretty\n```\nThis plan answers the question.",
        s"```json\n$pretty\n$pretty\n```",
        s"```json\n$pretty\n```\n```json\n$pretty\n```",
        s"```json\n$pretty",
        s"Sure! $pretty"
      )
    do assert(parse(bad) == Left(Vector(JsonSchema.NotJson)), bad)
  }

  test("a recorded Haiku body with a fenced, pretty-printed plan parses and validates") {
    val body = ujson
      .Obj(
        "id" -> "msg_bdrk_01",
        "type" -> "message",
        "role" -> "assistant",
        "model" -> "claude-haiku-4-5-20251001",
        "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> s"```json\n$pretty\n```")),
        "stop_reason" -> "end_turn",
        "stop_sequence" -> ujson.Null,
        "usage" -> ujson.Obj("input_tokens" -> 2234, "output_tokens" -> 198)
      )
      .render()
    val r = HaikuMessages.parse(body).fold(fail(_), identity)
    assert(r.stopReason.contains("end_turn") && r.outputTokens.contains(198))
    assert(parse(r.text).contains(plan))
    // The same reply through the workflow: the plan is the model's, and synthesis accepts a fence too.
    val synth = SynthesisOutput(
      RuntimeSchemas.SynthesisOutput,
      "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
      Vector(
        ClaimDraft(
          "claim-1",
          ClaimKind.COAUTHORSHIP,
          "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
          Vector(
            scholarchain.core.StableIds.evidenceId(
              scholarchain.core.EvidenceType.COAUTHOR_EDGE,
              Seq(adaGrace.id),
              graphRun
            )
          ),
          Vector(ada, grace),
          Some("all-years"),
          Map("jointPublicationCount" -> "2")
        )
      ),
      Vector.empty
    )
    val llm =
      RecordedLlm(Vector(Right(r.text), Right(s"```json\n${write(synth, indent = 2)}\n```")))
    withWorld(Some(llm)) { w =>
      val a = w.ask(q).fold(f => fail(s"$f"), identity)
      assert(a.answer.plan.source == PlannerSource.MODEL && a.answer.abstention.isEmpty)
      assert(a.audit.llmCalls.map(_.outcome) == Vector(CallOutcome.OK, CallOutcome.OK))
      assert(a.audit.llmCalls.forall(c => c.failure.isEmpty && c.requests == 1))
    }
  }

  test(
    "a rejected output records its failed rules and shape, never its text; the retry names them"
  ) {
    val secret = "Ada Lovelace secretly wrote 999 papers"
    val prose = s"I think the plan is: {\"note\": \"$secret\"}"
    val llm = RecordedLlm(
      Vector(
        Right(prose),
        Right(s"{\"schemaVersion\": \"planner-output-v1\", \"$secret\": 1}"),
        Right(s"{\"$secret\": 1}"),
        Right("{}")
      )
    )
    withWorld(Some(llm)) { w =>
      val a = w.ask(q).fold(f => fail(s"$f"), identity)
      val calls = a.audit.llmCalls
      assert(calls.map(_.outcome).forall(_ == CallOutcome.INVALID_OUTPUT) && calls.size == 4)
      val first = calls.head.failure.get
      assert(first.startsWith("output-is-json:"), first)
      assert(
        first.endsWith(
          s"(output ${prose.length} chars, starts with a letter, ends with '}', stop reason end_turn)"
        ),
        first
      )
      val second = calls(1).failure.get
      assert(
        second.startsWith("schema: $: missing key operations;") && second.contains("+4 more"),
        second
      )
      // The synthesis retry lists the failed rules (at most 10), the unexpected key among them,
      // never its name.
      assert(llm.requests(3).user.contains("- schema: $: unexpected key"))
      assert(!llm.requests(3).user.contains("secretly"))
      assert(calls.forall(c => !c.failure.exists(f => f.contains("secretly") || f.contains("999"))))
      assert(calls(2).failure.exists(_.contains("starts with '{', ends with '}'")))
      // The audit never holds model text; the retry prompt names the failed rules, not the output.
      assert(!upickle.default.write(a.audit).contains("secretly"))
      assert(
        llm.requests(1).user.contains("- output-is-json: the output is not exactly one JSON object")
      )
      assert(
        !llm.requests(1).user.contains("secretly") && !llm.requests(1).user.contains("I think")
      )
      assert(a.answer.abstention.map(_.reason).contains(AbstentionReason.BUDGET_EXHAUSTED))
    }
  }

  test("every Bedrock request is counted, the expired-token retry included (m5)") {

    /** Sends `perCall` requests per invoke, as the live client does on an expired token. */
    final class Retrying(perCall: Int, inner: RecordedLlm) extends LlmClient:
      private var sent = 0L
      def requestsSent: Long = sent
      def invoke(r: LlmRequest): LlmResponse =
        sent += perCall
        inner.invoke(r)
    def run(perCall: Int) =
      val llm = Retrying(perCall, RecordedLlm(Vector(Right(write(plan)), Right("{}"), Right("{}"))))
      withWorld(Some(llm))(w => w.ask(q).fold(f => fail(s"$f"), identity))
    val r = run(2)
    assert(r.audit.llmCalls.map(_.requests) == Vector(2, 2, 2))
    val question = Benchmark.Question(
      "T",
      "direct_collaboration",
      q,
      Some("Ada Lovelace"),
      Some("Grace Hopper"),
      "all",
      "count",
      shouldAbstain = true
    )
    val ref = Benchmark.PathReference(Vector.empty)
    val res = Benchmark.result(question, r, ref, cfg, 0L)
    assert(res.llm.attempts == 3 && res.llm.requests == 6)
    assert(res.checks.find(_.name == "llm-budget").get.outcome == Benchmark.Outcome.PASS)
    val over = Benchmark.result(question, run(3), ref, cfg, 0L)
    assert(over.checks.find(_.name == "llm-budget").get.outcome == Benchmark.Outcome.FAIL)
  }
