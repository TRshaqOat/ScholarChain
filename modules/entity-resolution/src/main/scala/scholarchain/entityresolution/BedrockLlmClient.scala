package scholarchain.entityresolution

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.*

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Live adapter of [[LlmClient]] for Amazon Bedrock (Converse API, temperature 0). The model ID and
  * Region come from configuration (`models.adjudication-model`, `application.region`); credentials
  * come from the default AWS provider chain. Only the local runner constructs it, and only under an
  * explicit adjudication profile; tests never do. Kept apart from MatchPolicy so the pure policy
  * carries no AWS dependency.
  */
final class BedrockLlmClient(modelId: String, region: String) extends LlmClient with AutoCloseable:
  private val client = BedrockRuntimeClient.builder().region(Region.of(region)).build()

  def modelVersion: String = modelId

  def complete(system: String, user: String, maxOutputTokens: Int): Either[String, LlmReply] =
    Try {
      val req = ConverseRequest
        .builder()
        .modelId(modelId)
        .system(SystemContentBlock.fromText(system))
        .messages(
          Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(user)).build()
        )
        .inferenceConfig(
          InferenceConfiguration.builder().maxTokens(maxOutputTokens).temperature(0f).build()
        )
        .build()
      val res = client.converse(req)
      val text = res.output().message().content().asScala.flatMap(c => Option(c.text())).mkString
      LlmReply(text, res.usage().inputTokens().toLong, res.usage().outputTokens().toLong)
    }.toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")

  def close(): Unit = client.close()
