package scholarchain.rag.runtime

import scholarchain.storage.{Throttle, TransientStoreException}

import scala.util.control.NonFatal

/** One model request: system rules and the user message (delimited data blocks). */
final case class LlmRequest(
    modelId: String,
    system: String,
    user: String,
    maxTokens: Int,
    temperature: Double
)

/** The model's text and the token counts the response reports (None when it reports none). */
final case class LlmResponse(
    text: String,
    inputTokens: Option[Int],
    outputTokens: Option[Int],
    stopReason: Option[String]
)

/** Model port. invoke throws TransientStoreException for a retryable failure; any other exception
  * is a failed attempt. requestsSent counts every request the client sent, including the
  * expired-token retry inside one invoke (review 1 m5). Live: live.BedrockHaikuClient; tests: a
  * recorded-fixture client.
  */
trait LlmClient:
  def invoke(request: LlmRequest): LlmResponse
  def requestsSent: Long

/** Per-question token ledger (RUNTIME.md section 15). Input is estimated at charsPerToken
  * characters per token before a call and replaced by the reported count when it is larger; output
  * is reserved at each request's max_tokens, so the output ceiling holds whatever the model does.
  */
final class TokenBudget(maxInput: Int, maxOutput: Int, val charsPerToken: Int):
  private var input = 0
  private var output = 0
  def inputUsed: Int = input
  def outputReserved: Int = output
  def estimate(text: String): Int = (text.length + charsPerToken - 1) / charsPerToken
  def admits(r: LlmRequest): Boolean =
    input + estimate(r.system) + estimate(r.user) <= maxInput && output + r.maxTokens <= maxOutput
  def charge(r: LlmRequest, reportedInput: Option[Int]): Unit =
    input += math.max(estimate(r.system) + estimate(r.user), reportedInput.getOrElse(0))
    output += r.maxTokens

/** What one question may spend on model calls: the client, the shared throttle every attempt passes
  * (ruling 11), the token ledger, and the question deadline.
  */
final class LlmSession(
    val client: LlmClient,
    val throttle: Throttle,
    val budget: TokenBudget,
    val maxAttempts: Int,
    val nowMs: () => Long,
    val deadlineMs: Long
)

object LlmCaller:
  /** The result of one stage's model call: the validated value, every attempt, and the rule names
    * of the last validation failure.
    */
  final case class Outcome[A](value: Option[A], calls: Vector[LlmCall], failures: Vector[String])

  /** Up to session.maxAttempts attempts. request(attempt, failures) builds each request (the retry
    * names the failed rules, never the earlier output); parse validates the text. An attempt that
    * would pass the token budget or the deadline is not sent (SKIPPED_BUDGET) and ends the call.
    */
  def call[A](
      s: LlmSession,
      stage: Stage,
      prompt: PromptTemplate,
      request: (Int, Vector[String]) => Either[String, LlmRequest],
      parse: String => Either[Vector[String], A]
  ): Outcome[A] =
    var calls = Vector.empty[LlmCall]
    var failures = Vector.empty[String]
    var result: Option[A] = None
    var attempt = 1
    var stop = false
    def log(
        r: LlmRequest,
        o: CallOutcome,
        resp: Option[LlmResponse],
        ms: Long,
        wait: Long,
        requests: Int,
        failure: Option[String]
    ) =
      calls :+= LlmCall(
        stage,
        r.modelId,
        prompt.version,
        prompt.schemaVersion,
        attempt,
        o,
        requests,
        resp.flatMap(_.inputTokens),
        resp.flatMap(_.outputTokens),
        ms,
        wait,
        failure
      )
    while result.isEmpty && !stop && attempt <= s.maxAttempts do
      request(attempt, failures) match
        case Left(e) =>
          failures = Vector(s"prompt: $e")
          stop = true
        case Right(r) =>
          if s.nowMs() > s.deadlineMs || !s.budget.admits(r) then
            failures = Vector("budget: token or deadline budget exhausted")
            log(r, CallOutcome.SKIPPED_BUDGET, None, 0L, 0L, 0, Some(failures.head))
            stop = true
          else
            val t0 = s.nowMs()
            s.throttle.acquire()
            val t1 = s.nowMs()
            val sent0 = s.client.requestsSent
            def sent = (s.client.requestsSent - sent0).toInt
            try
              val resp = s.client.invoke(r)
              s.budget.charge(r, resp.inputTokens)
              parse(resp.text) match
                case Right(a) =>
                  result = Some(a)
                  log(r, CallOutcome.OK, Some(resp), s.nowMs() - t1, t1 - t0, sent, None)
                case Left(es) =>
                  failures = es
                  log(
                    r,
                    CallOutcome.INVALID_OUTPUT,
                    Some(resp),
                    s.nowMs() - t1,
                    t1 - t0,
                    sent,
                    Some(reason(es, resp))
                  )
            catch
              case e: TransientStoreException =>
                s.budget.charge(r, None)
                failures = Vector("transient: the model call failed transiently")
                val why = s"transient: ${e.getClass.getSimpleName}"
                log(r, CallOutcome.TRANSIENT_ERROR, None, s.nowMs() - t1, t1 - t0, sent, Some(why))
              case NonFatal(e) =>
                s.budget.charge(r, None)
                failures = Vector("failed: the model call failed")
                val why = s"failed: ${e.getClass.getSimpleName}"
                log(r, CallOutcome.FAILED, None, s.nowMs() - t1, t1 - t0, sent, Some(why))
          attempt += 1
    Outcome(result, calls, failures)

  /** Longest audit failure reason (characters). */
  val MaxReason = 600

  /** The audit reason of a rejected output (ruling 23, ruling 27): the failed rule names with their
    * schema paths (never model, prompt, or retrieved text; JsonSchema names no model-chosen key)
    * and the output's shape.
    */
  def reason(failures: Vector[String], r: LlmResponse): String =
    val rules = failures.distinct
    val shown =
      rules.take(5).mkString("; ") + (if rules.size > 5 then s"; +${rules.size - 5} more" else "")
    val shape = s" ${LlmOutput.shape(r.text, r.stopReason)}"
    shown.take(MaxReason - shape.length) + shape

/** Model output text handling shared by the planner and synthesis parsers. */
object LlmOutput:
  // Exactly one Markdown code fence around the whole output (ruling 27, as in modules/entity-resolution).
  private val Fence = """(?s)\A\s*```(?:json|JSON)?[ \t]*\r?\n(.*?)\s*```\s*\z""".r

  /** The output without one enclosing code fence; any other output unchanged (prose stays prose).
    */
  def unfence(text: String): String = text match
    case Fence(body) => body
    case _           => text

  /** Length, first and last character class, and stop reason; never the text itself. */
  def shape(text: String, stopReason: Option[String]): String =
    val t = text.trim
    def kind(c: Option[Char]) = c.fold("nothing") { c =>
      if "{}`[]\"".contains(c) then s"'$c'"
      else if c.isLetter then "a letter"
      else if c.isDigit then "a digit"
      else "other"
    }
    s"(output ${t.length} chars, starts with ${kind(t.headOption)}, ends with " +
      s"${kind(t.lastOption)}, stop reason ${stopReason.getOrElse("unknown")})"
