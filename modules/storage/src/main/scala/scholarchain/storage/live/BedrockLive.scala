package scholarchain.storage.live

import scholarchain.storage.{Embedder, RejectedInputException, TitanV2}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  InvokeModelRequest,
  ValidationException
}

/** Live Titan Text Embeddings V2 (1024 dimensions, normalized), one text per InvokeModel call. Used
  * only behind --live --profile; the human runs it (ruling 26). beforeRetry runs before the
  * expired-token retry: pass the caller's throttle (`throttle.acquire`) so that request is paced
  * too (N1).
  */
final class BedrockTitanEmbedder(
    client: BedrockRuntimeClient,
    val modelId: String,
    beforeRetry: () => Unit = () => ()
) extends Embedder:
  require(modelId.startsWith("amazon.titan-embed-text-v2"), s"$modelId is not Titan V2")
  val isFake = false

  def embed(text: String): Array[Float] =
    val response =
      try
        LiveAws.guardedWith(beforeRetry)(client.invokeModel(BedrockRequests.invoke(modelId, text)))
      catch case e: ValidationException => throw RejectedInputException(e.getMessage)
    TitanV2.parseResponse(response.body.asUtf8String) match
      case Right(v)  => v
      case Left(err) => throw RejectedInputException(err)

object BedrockRequests:
  def invoke(modelId: String, text: String): InvokeModelRequest = InvokeModelRequest
    .builder()
    .modelId(modelId)
    .contentType("application/json")
    .accept("application/json")
    .body(SdkBytes.fromUtf8String(TitanV2.requestBody(text)))
    .build()
