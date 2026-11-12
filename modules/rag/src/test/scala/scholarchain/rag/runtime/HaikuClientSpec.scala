package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.rag.live.{BedrockHaikuClient, HaikuMessages}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  BedrockRuntimeException,
  InvokeModelRequest,
  InvokeModelResponse
}

import java.lang.reflect.{InvocationHandler, Method, Proxy}

/** The live Haiku client on request building and response parsing only (ruling 11): a JDK proxy
  * stands in for the SDK client, so no request leaves the JVM.
  */
class HaikuClientSpec extends AnyFunSuite:
  private val req = LlmRequest(
    "us.anthropic.claude-haiku-4-5-20251001-v1:0",
    "rules",
    "<question>\nq\n</question>",
    800,
    0.0
  )
  private val recorded =
    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5","content":[{"type":"text","text":"{\"a\":"},{"type":"text","text":"1}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":412,"output_tokens":37}}"""

  test("the request body is an Anthropic Messages body with temperature and no top_p") {
    val body = ujson.read(HaikuMessages.body(req))
    assert(
      body.obj.keySet == Set("anthropic_version", "max_tokens", "system", "messages", "temperature")
    )
    assert(
      body("anthropic_version").str == "bedrock-2023-05-31" && body(
        "max_tokens"
      ).num == 800 && body("temperature").num == 0.0
    )
    assert(body("system").str == "rules")
    assert(
      body("messages") == ujson.Arr(
        ujson.Obj(
          "role" -> "user",
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> req.user))
        )
      )
    )
    val r = HaikuMessages.request(req)
    assert(
      r.modelId == req.modelId && r.contentType == "application/json" && r.accept == "application/json"
    )
  }

  test("the response parser joins text blocks and reads usage; other bodies are refused") {
    assert(
      HaikuMessages.parse(recorded) == Right(
        LlmResponse("{\"a\":1}", Some(412), Some(37), Some("end_turn"))
      )
    )
    assert(HaikuMessages.parse("""{"type":"error","error":{"type":"overloaded_error"}}""").isLeft)
    assert(HaikuMessages.parse("not json").isLeft)
    assert(
      HaikuMessages.parse("""{"type":"message","content":[]}""") == Right(
        LlmResponse("", None, None, None)
      )
    )
  }

  test(
    "an expired token is retried once, after the caller's throttle hook, and both requests count"
  ) {
    var calls = 0
    var hooks = 0
    val handler = new InvocationHandler:
      def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = m.getName match
        case "invokeModel" =>
          assert(args(0).isInstanceOf[InvokeModelRequest])
          calls += 1
          if calls == 1 then
            throw BedrockRuntimeException
              .builder()
              .statusCode(403)
              .awsErrorDetails(
                AwsErrorDetails
                  .builder()
                  .errorCode("ExpiredTokenException")
                  .errorMessage("expired")
                  .build()
              )
              .build()
          InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(recorded)).build()
        case "close"       => null
        case "serviceName" => "bedrock"
        case other         => throw UnsupportedOperationException(other)
    val sdk = Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[BedrockRuntimeClient]), handler)
      .asInstanceOf[BedrockRuntimeClient]
    val client = BedrockHaikuClient(sdk, () => hooks += 1)
    val out = client.invoke(req)
    assert(out.text == "{\"a\":1}" && calls == 2 && hooks == 1)
    // Review 1 m5: the retry is a second Bedrock request and is counted.
    assert(client.requestsSent == 2)
    client.invoke(req)
    assert(client.requestsSent == 3 && calls == 3)
  }
