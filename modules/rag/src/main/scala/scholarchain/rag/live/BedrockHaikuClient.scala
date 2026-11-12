package scholarchain.rag.live

import scholarchain.rag.runtime.{LlmClient, LlmRequest, LlmResponse}
import scholarchain.storage.live.LiveAws
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

/** Anthropic Messages request and response bodies for Bedrock InvokeModel
  * (model-parameters-anthropic-claude-messages-request-response.html, accessed 2026-11-06). Claude
  * Haiku 4.5 accepts temperature or top_p, not both; only temperature is sent.
  */
object HaikuMessages:
  val AnthropicVersion = "bedrock-2023-05-31"

  def body(r: LlmRequest): String =
    ujson
      .Obj(
        "anthropic_version" -> AnthropicVersion,
        "max_tokens" -> r.maxTokens,
        "system" -> r.system,
        "messages" -> ujson.Arr(
          ujson.Obj(
            "role" -> "user",
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> r.user))
          )
        ),
        "temperature" -> r.temperature
      )
      .render()

  def request(r: LlmRequest): InvokeModelRequest =
    InvokeModelRequest
      .builder()
      .modelId(r.modelId)
      .contentType("application/json")
      .accept("application/json")
      .body(SdkBytes.fromUtf8String(body(r)))
      .build()

  /** The concatenated text blocks and the reported usage; Left for a body that is not a message. */
  def parse(body: String): Either[String, LlmResponse] =
    scala.util
      .Try(ujson.read(body))
      .toOption
      .collect { case o: ujson.Obj if o.value.get("type").contains(ujson.Str("message")) => o }
      .toRight("the response body is not an Anthropic message")
      .map { o =>
        val text = o.value.get("content").toVector.flatMap(_.arr).collect {
          case b: ujson.Obj if b.value.get("type").contains(ujson.Str("text")) =>
            b.value.get("text").flatMap(_.strOpt).getOrElse("")
        }
        def usage(k: String) =
          o.value.get("usage").flatMap(_.objOpt).flatMap(_.get(k)).flatMap(_.numOpt).map(_.toInt)
        LlmResponse(
          text.mkString,
          usage("input_tokens"),
          usage("output_tokens"),
          o.value.get("stop_reason").flatMap(_.strOpt)
        )
      }

/** Live Claude Haiku 4.5 client (ruling 11), used only behind --live --profile; the human runs it.
  * The caller passes every attempt through its throttle; the expired-token retry is a second
  * request, so beforeRetry (the same throttle's acquire) paces it too (Phase 7 review 2 N1), and
  * requestsSent counts it (review 1 m5). SDK retries are off (LiveAws.overrides), so the runtime's
  * bounded attempts are the only retry layer.
  */
final class BedrockHaikuClient(client: BedrockRuntimeClient, beforeRetry: () => Unit)
    extends LlmClient:
  private val sent = java.util.concurrent.atomic.AtomicLong()
  def requestsSent: Long = sent.get

  def invoke(r: LlmRequest): LlmResponse =
    val response = LiveAws.guardedWith(beforeRetry) {
      sent.incrementAndGet()
      client.invokeModel(HaikuMessages.request(r))
    }
    HaikuMessages
      .parse(response.body.asUtf8String)
      .fold(e => throw IllegalStateException(e), identity)
