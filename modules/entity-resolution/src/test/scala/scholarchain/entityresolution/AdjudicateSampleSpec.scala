package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.tools.AdjudicateSample
import scholarchain.entityresolution.tools.AdjudicateSample.Pair
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.bedrockruntime.model.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.mutable
import scala.util.Random

/** Phase 9 work order 6: bounded live adjudication. No test reaches the network: the Bedrock client
  * gets an injected converse function, and the session gets a fake LlmClient.
  */
class AdjudicateSampleSpec extends AnyFunSuite:
  private val t = Thresholds(0.93, 0.35, 0.65, 0.93)
  private val run = "run-" + "a" * 32

  private def decision(
      i: Int,
      rules: Vector[String] = Vector(MatchPolicy.Rule.WeightedScore, MatchPolicy.Rule.BandLlm),
      outcome: MatchOutcome = MatchOutcome.UNRESOLVED
  ) =
    val (s, g) = (s"dblp:conf/a/W$i#1", s"dblp:conf/b/W$i#2")
    MatchDecision(
      SchemaVersions.MatchDecision,
      MatchEntityType.RESEARCHER,
      s,
      g,
      Map("name_similarity" -> 1.0),
      rules,
      outcome,
      0.8,
      Vector("SCORE_IN_LLM_BAND"),
      None,
      "2026-11-04T07:43:47Z",
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        s"r0:x|$s|$g",
        "2026-10-03",
        run,
        Some(MatchPolicy.V2),
        None
      )
    )

  private def pair(i: Int) = Pair(decision(i), Map("a.name" -> "Wei Wang", "b.name" -> "Wei Wang"))

  private def candidate(user: String) =
    ujson.read(user.split("<candidate_data>")(1).split("</candidate_data>")(0))

  /** Echoes the request IDs from the prompt's candidate JSON; counts calls. `onCall` runs before
    * each reply (with the candidate's sourceId); `wrap` reshapes the reply text.
    */
  final class EchoClient(
      decision: String = "ACCEPT",
      fail: Boolean = false,
      wrap: String => String = identity,
      onCall: String => Unit = _ => ()
  ) extends LlmClient:
    var calls = 0
    def modelVersion = "fake-model-v1"
    def complete(system: String, user: String, maxOutputTokens: Int) =
      calls += 1
      val j = candidate(user)
      onCall(j("sourceId").str)
      if fail then Left("AccessDeniedException: no access")
      else
        Right(
          LlmReply(
            wrap(
              s"""{"sourceId":"${j("sourceId").str}","targetId":"${j(
                  "targetId"
                ).str}","decision":"$decision","confidence":0.97,"citedFields":["a.name"],"reasonCodes":["NAME_MATCH"]}"""
            ),
            900,
            60,
            Some("end_turn")
          )
        )

  private def adjudicator(c: LlmClient, cache: java.nio.file.Path, max: Int = 10) =
    Adjudicator(c, JsonlFileCache(cache), AdjudicationBudget(max, 1_000_000), MatchPolicy.V2)

  test("select is a seeded sample of LLM-band decisions only, independent of input order") {
    val band = (1 to 300).toVector.map(decision(_))
    val others = Vector(
      decision(1001, Vector(MatchPolicy.Rule.WeightedScore, MatchPolicy.Rule.BandUnresolved)),
      decision(1002, Vector(MatchPolicy.Rule.ExactIdMatch), MatchOutcome.ACCEPTED)
    )
    val all = band ++ others
    val a = AdjudicateSample.select(all.iterator, 7L, 20)
    assert(a.size == 20 && a.forall(AdjudicateSample.llmBand))
    assert(a == AdjudicateSample.select(Random(3).shuffle(all).iterator, 7L, 20))
    assert(a.map(AdjudicateSample.rank(7L, _)) == a.map(AdjudicateSample.rank(7L, _)).sorted)
    assert(AdjudicateSample.select(all.iterator, 8L, 20).toSet != a.toSet)
    assert(AdjudicateSample.select(others.iterator, 7L, 20).isEmpty)
    // the same unordered pair in reverse ID order shares a rank; one decision is kept
    val d = band.head
    val rev = d.copy(sourceId = d.targetId, targetId = d.sourceId)
    assert(AdjudicateSample.select(Iterator(rev, d), 7L, 5) == Vector(d))
  }

  test("sessions are bounded by --max and resume from the cache without repeating calls") {
    val dir = Files.createTempDirectory("adj-sample")
    val cacheFile = dir.resolve("cache.jsonl")
    val pairs = (1 to 3).toVector.map(pair)
    val c1 = EchoClient()
    val budget1 = AdjudicationBudget(2, 1_000_000)
    val s1 = AdjudicateSample.session(
      pairs,
      Adjudicator(c1, JsonlFileCache(cacheFile), budget1, MatchPolicy.V2),
      t,
      run,
      "2026-11-07T00:00:00Z"
    )
    assert(c1.calls == 2 && s1.decisions.size == 2 && s1.pending == 1 && s1.status == "MAX_REACHED")
    s1.decisions.foreach { d =>
      assert(Validation.validate(d).isRight)
      assert(
        d.decision == MatchOutcome.ACCEPTED && d.rulesFired.last == MatchPolicy.Rule.LlmAdjudication
      )
      assert(d.promptVersion.contains(AdjudicationPrompt.Version))
      assert(d.provenance.modelVersion.contains("fake-model-v1") && d.provenance.runId == run)
      assert(d.provenance.policyVersion.contains(MatchPolicy.V2))
    }
    // a crash left a partial line; it is cut and the session resumes
    Files.writeString(cacheFile, Files.readString(cacheFile) + """{"key":"adj-x","inp""", UTF_8)
    AdjudicateSample.cutPartialLine(cacheFile)
    assert(Files.readString(cacheFile).endsWith("\n"))
    val c2 = EchoClient()
    val s2 = AdjudicateSample.session(
      pairs,
      Adjudicator(c2, JsonlFileCache(cacheFile), AdjudicationBudget(2, 1_000_000), MatchPolicy.V2),
      t,
      run,
      "2026-11-07T01:00:00Z"
    )
    assert(c2.calls == 1 && s2.cached == 2 && s2.decisions.size == 3 && s2.status == "COMPLETE")
  }

  test("the source decision is unchanged; ABSTAIN keeps the pair UNRESOLVED") {
    val p = pair(1)
    val s = AdjudicateSample.session(
      Vector(p),
      Adjudicator(
        EchoClient("ABSTAIN"),
        JsonlFileCache(Files.createTempFile("c", ".jsonl")),
        AdjudicationBudget(5, 1_000_000),
        MatchPolicy.V2
      ),
      t,
      run,
      "2026-11-07T00:00:00Z"
    )
    assert(s.decisions.head.decision == MatchOutcome.UNRESOLVED)
    assert(s.decisions.head.reasonCodes.contains("LLM_ABSTAIN"))
    assert(p.decision == decision(1))
  }

  test("a client error after its retry halts the session; the rest stay pending") {
    val c = EchoClient(fail = true)
    val s = AdjudicateSample.session(
      (1 to 4).toVector.map(pair),
      Adjudicator(
        c,
        JsonlFileCache(Files.createTempFile("c", ".jsonl")),
        AdjudicationBudget(10, 1_000_000),
        MatchPolicy.V2,
        maxAttempts = 2
      ),
      t,
      run,
      "2026-11-07T00:00:00Z"
    )
    assert(c.calls == 2 && s.status == "FAILED" && s.pending == 4 && s.decisions.isEmpty)
    assert(s.halted.exists(_.contains("AccessDeniedException")))
  }

  test("live mode requires --live and --profile before any input is read") {
    val e = intercept[IllegalArgumentException](
      AdjudicateSample.main(Array("--run", "/nonexistent", "--out", "/nonexistent", "--max", "1"))
    )
    assert(e.getMessage.contains("--live"))
    val e2 = intercept[IllegalArgumentException](
      AdjudicateSample.main(Array("--run", "/x", "--out", "/x", "--live", "--profile", "p"))
    )
    assert(e2.getMessage.contains("--max"))
  }

  // ---- Bedrock client: expired-token refresh and throttle on every request (no network) ----

  private def response(text: String) =
    ConverseResponse
      .builder()
      .output(
        ConverseOutput.fromMessage(
          Message
            .builder()
            .role(ConversationRole.ASSISTANT)
            .content(ContentBlock.fromText(text))
            .build()
        )
      )
      .usage(TokenUsage.builder().inputTokens(900).outputTokens(60).totalTokens(960).build())
      .build()

  private def awsError(code: String, msg: String) =
    BedrockRuntimeException
      .builder()
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(msg).build())
      .message(msg)
      .statusCode(403)
      .build()

  private val expired =
    awsError("ExpiredTokenException", "The security token included in the request is expired")

  test("an expired token refreshes the credentials and retries once, through the throttle") {
    val (refreshes, throttled) = (mutable.Buffer.empty[Int], mutable.Buffer.empty[Int])
    val seen = mutable.Buffer.empty[ConverseRequest]
    var n = 0
    val c = BedrockLlmClient(
      "us.anthropic.claude-haiku-4-5-20251001-v1:0",
      r => { seen += r; n += 1; if n == 1 then throw expired else response("ok") },
      () => refreshes += n,
      () => throttled += n
    )
    assert(c.complete("sys", "usr", 400) == Right(LlmReply("ok", 900, 60)))
    assert(refreshes == Seq(1) && throttled == Seq(0, 1) && c.requests == 2)
    val r = seen.head
    assert(r.modelId == "us.anthropic.claude-haiku-4-5-20251001-v1:0")
    assert(r.system.get(0).text == "sys" && r.messages.get(0).content.get(0).text == "usr")
    assert(r.inferenceConfig.maxTokens == 400 && r.inferenceConfig.temperature == 0f)
  }

  test("a second expired token or any other error is not retried again") {
    var refreshes = 0
    val always = BedrockLlmClient("m", _ => throw expired, () => refreshes += 1)
    assert(always.complete("s", "u", 10).isLeft && always.requests == 2 && refreshes == 1)
    val denied = BedrockLlmClient(
      "m",
      _ => throw awsError("AccessDeniedException", "denied"),
      () => refreshes += 1
    )
    assert(denied.complete("s", "u", 10).left.exists(_.contains("denied")))
    assert(denied.requests == 1 && refreshes == 1)
  }

  test("RefreshingCredentials resolves through a delegate rebuilt after refresh") {
    var built = 0
    val creds = RefreshingCredentials { () =>
      built += 1
      val k = s"AKIDEXAMPLE$built"
      (() => AwsBasicCredentials.create(k, "example-secret")): AwsCredentialsProvider
    }
    assert(built == 0)
    assert(creds.resolveCredentials().accessKeyId == "AKIDEXAMPLE1")
    assert(creds.resolveCredentials().accessKeyId == "AKIDEXAMPLE1" && built == 1)
    creds.refresh()
    assert(creds.resolveCredentials().accessKeyId == "AKIDEXAMPLE2" && built == 2)
  }

  test("Throttle spaces call starts at ceil(60000 / perMinute) ms and caps the rate at 8") {
    var now = 0L
    val sleeps = mutable.Buffer.empty[Long]
    val th = Throttle(8, () => now, ms => { sleeps += ms; now += ms })
    (1 to 3).foreach(_ => th.acquire())
    assert(th.intervalMs == 7500 && sleeps == Seq(7500L, 7500L) && now == 15000)
    now += 20000
    th.acquire()
    assert(sleeps.size == 2)
    intercept[IllegalArgumentException](Throttle(9, () => 0L, _ => ()))
    intercept[IllegalArgumentException](Throttle(0, () => 0L, _ => ()))
  }

  // ---- repair-01 part C, ruling 26: throttle with the real clock, reply format, cache, resume ----

  test("Throttle with the real clock and real sleep: first acquire at once, later ones spaced") {
    val th = Throttle(600, () => Throttle.monotonicMs(), Throttle.sleepMs, maxPerMinute = 600)
    assert(th.intervalMs == 100)
    val t0 = System.nanoTime()
    th.acquire()
    val first = (System.nanoTime() - t0) / 1000000
    val starts = (1 to 4).map { _ => th.acquire(); System.nanoTime() }
    val gaps = (t0 +: starts).sliding(2).map(w => (w(1) - w(0)) / 1000000).toVector.tail
    val total = (starts.last - t0) / 1000000
    assert(first < 50, s"first acquire took $first ms")
    assert(gaps.forall(_ >= 98), s"gaps $gaps ms")
    assert(total >= 398 && total < 2000, s"4 spaced acquires took $total ms")
  }

  test("the live throttle's clock and sleep both use milliseconds") {
    val waits = mutable.Buffer.empty[Long]
    val th = Throttle(8, () => Throttle.monotonicMs(), ms => waits += ms)
    th.acquire()
    th.acquire()
    // back to back at 8 per minute: one wait just under 7500 ms (nanoseconds would be ~7.5e9)
    assert(waits.size == 1 && waits.head > 7000 && waits.head <= 7500, s"waits $waits")
    val t0 = System.nanoTime()
    Throttle.sleepMs(100)
    val slept = (System.nanoTime() - t0) / 1000000
    assert(slept >= 99 && slept < 2000, s"sleepMs(100) took $slept ms")
    assert(Throttle.live(8).intervalMs == 7500)
    intercept[IllegalArgumentException](Throttle.live(9))
  }

  test("each answer is appended to cache.jsonl as soon as it returns") {
    val cacheFile = Files.createTempDirectory("adj-cache").resolve("cache.jsonl")
    def lines = if Files.exists(cacheFile) then Files.readAllLines(cacheFile).size else 0
    val seen = mutable.Buffer.empty[Int]
    val s = AdjudicateSample.session(
      (1 to 3).toVector.map(pair),
      adjudicator(EchoClient(onCall = _ => seen += lines), cacheFile),
      t,
      run,
      "2026-11-07T00:00:00Z"
    )
    assert(seen == Seq(0, 1, 2) && lines == 3 && s.status == "COMPLETE")
  }

  test("a run stopped partway resumes from the partial cache without repeating answered pairs") {
    val cacheFile = Files.createTempDirectory("adj-stop").resolve("cache.jsonl")
    val pairs = (1 to 5).toVector.map(pair)
    var n = 0
    // the third call is interrupted, as Ctrl-C interrupts the throttle's sleep
    val stopping = EchoClient(onCall = _ => {
      n += 1; if n == 3 then throw InterruptedException("sleep interrupted")
    })
    intercept[InterruptedException](
      AdjudicateSample.session(
        pairs,
        adjudicator(stopping, cacheFile),
        t,
        run,
        "2026-11-07T00:00:00Z"
      )
    )
    assert(Files.readAllLines(cacheFile).size == 2)
    Files.writeString(cacheFile, Files.readString(cacheFile) + """{"key":"adj-x","inp""", UTF_8)
    AdjudicateSample.cutPartialLine(cacheFile)
    val asked = mutable.Buffer.empty[String]
    val c2 = EchoClient(onCall = asked += _)
    val s2 = AdjudicateSample.session(
      pairs,
      adjudicator(c2, cacheFile),
      t,
      run,
      "2026-11-07T01:00:00Z"
    )
    assert(asked == pairs.drop(2).map(_.decision.sourceId))
    assert(s2.cached == 2 && s2.decisions.size == 5 && s2.status == "COMPLETE")
    assert(Files.readAllLines(cacheFile).size == 5)
  }

  test("a Haiku reply in a Markdown code fence validates through the Bedrock client") {
    val p = pair(1)
    val fenced = s"""```json
{
  "sourceId": "${p.decision.sourceId}",
  "targetId": "${p.decision.targetId}",
  "decision": "ACCEPT",
  "confidence": 0.95,
  "citedFields": ["a.name", "b.name"],
  "reasonCodes": ["NAME_MATCH"]
}
```"""
    val converse: ConverseRequest => ConverseResponse =
      _ => response(fenced).toBuilder.stopReason(StopReason.END_TURN).build()
    val c = BedrockLlmClient("us.anthropic.claude-haiku-4-5-20251001-v1:0", converse)
    val reply = c.complete("s", "u", 400).toOption.get
    assert(reply.stopReason.contains("end_turn"))
    assert(
      AdjudicationPrompt.validate(p.request, reply.text).map(_.decision) == Right(
        LlmDecision.ACCEPT
      )
    )
    assert(
      AdjudicationPrompt
        .validate(p.request, s"```\n${AdjudicationPrompt.unfence(fenced)}\n```")
        .isRight
    )
    val cacheFile = Files.createTempFile("c", ".jsonl")
    val s =
      AdjudicateSample.session(Vector(p), adjudicator(c, cacheFile), t, run, "2026-11-07T00:00:00Z")
    assert(s.failures.isEmpty && s.decisions.head.decision == MatchOutcome.ACCEPTED)
    assert(Files.readAllLines(cacheFile).size == 1)
  }

  test("prose around the JSON or a cut-off reply is still rejected, with its shape recorded") {
    val p = pair(1)
    val prose = EchoClient(wrap = j => s"Here is my decision:\n$j")
    val a = adjudicator(prose, Files.createTempFile("c", ".jsonl")).adjudicate(p.request).get
    assert(prose.calls == 2 && a.decision == LlmDecision.ABSTAIN)
    assert(
      a.failure.contains(
        "malformed JSON (reply 174 chars, starts with a letter, ends with '}', stop reason end_turn)"
      ),
      a.failure
    )
    val cut = LlmReply("""{"sourceId":"x","targ""", 900, 400, Some("max_tokens"))
    assert(AdjudicationPrompt.validate(p.request, cut.text) == Left("malformed JSON"))
    assert(AdjudicationPrompt.shape(cut).contains("ends with a letter, stop reason max_tokens"))
    assert(!AdjudicationPrompt.shape(LlmReply("Wei Wang", 1, 1)).contains("Wei"))
  }

  test("replies that keep failing validation halt the session after 3 pairs in a row") {
    val c = EchoClient(wrap = j => s"Sure. $j")
    val lines = mutable.Buffer.empty[String]
    val s = AdjudicateSample.session(
      (1 to 6).toVector.map(pair),
      adjudicator(c, Files.createTempFile("c", ".jsonl")),
      t,
      run,
      "2026-11-07T00:00:00Z",
      lines += _
    )
    assert(c.calls == 6 && s.status == "FAILED" && s.pending == 3 && s.failures.size == 3)
    assert(s.decisions.forall(_.decision == MatchOutcome.UNRESOLVED) && s.decisions.size == 3)
    assert(s.halted.exists(_.startsWith("3 pairs in a row failed; last: malformed JSON")))
    assert(
      lines.size == 3 && lines.head.startsWith("pair 1/6 RESEARCHER: ABSTAIN FAILED malformed")
    )
    assert(lines.forall(l => !l.contains("Wei Wang")))
    // a success resets the count: calls 5 and 11 succeed, so pairs 3 and 6 pass and 4 fail
    var k = 0
    val mixed = EchoClient(wrap = j => { k += 1; if k % 6 == 5 then j else s"Sure. $j" })
    val s2 = AdjudicateSample.session(
      (1 to 6).toVector.map(pair),
      adjudicator(mixed, Files.createTempFile("c", ".jsonl")),
      t,
      run,
      "2026-11-07T00:00:00Z"
    )
    assert(s2.decisions.size == 6 && s2.halted.isEmpty && s2.failures.size == 4)
    assert(s2.status == "COMPLETE")
  }

  test("Runner.closing closes the SDK client after the body, also when it throws") {
    var closed = 0
    val r: AutoCloseable = () => closed += 1
    assert(scholarchain.entityresolution.tools.Runner.closing(Some(r))(42) == 42 && closed == 1)
    intercept[IllegalStateException](
      scholarchain.entityresolution.tools.Runner.closing(Some(r))(throw IllegalStateException("x"))
    )
    assert(closed == 2)
    assert(scholarchain.entityresolution.tools.Runner.closing(None)("ok") == "ok")
  }
