package scholarchain.entityresolution

import software.amazon.awssdk.auth.credentials.{
  AwsCredentials,
  AwsCredentialsProvider,
  DefaultCredentialsProvider,
  ProfileCredentialsProvider
}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.*

import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Live adapter of [[LlmClient]] for Amazon Bedrock (Converse API, temperature 0). The model ID and
  * Region come from configuration (`models.adjudication-model`, `application.region`). Kept apart
  * from MatchPolicy so the pure policy carries no AWS dependency; tests inject `converse` and never
  * reach the network.
  *
  * Every SDK request, including the one retry after an expired token, first runs `beforeRequest`
  * (the caller's throttle), so every attempt passes the throttle (Phase 9 ruling 11). On an
  * expired-token error the client calls `refreshCredentials` and retries once (Phase 7 ruling 35);
  * AWS rejects such a request before acting on it, so the retry repeats no effect.
  */
final class BedrockLlmClient(
    modelId: String,
    converse: ConverseRequest => ConverseResponse,
    refreshCredentials: () => Unit = () => (),
    beforeRequest: () => Unit = () => ()
) extends LlmClient:
  private var sent = 0L

  /** SDK requests sent so far (retries included). */
  def requests: Long = sent

  def modelVersion: String = modelId

  def request(system: String, user: String, maxOutputTokens: Int): ConverseRequest =
    ConverseRequest
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

  def complete(system: String, user: String, maxOutputTokens: Int): Either[String, LlmReply] =
    Try {
      val req = request(system, user, maxOutputTokens)
      def send(): ConverseResponse =
        beforeRequest()
        sent += 1
        converse(req)
      val res =
        try send()
        catch
          case e: AwsServiceException if BedrockLlmClient.isExpiredToken(e) =>
            refreshCredentials()
            send()
      val text = res.output().message().content().asScala.flatMap(c => Option(c.text())).mkString
      LlmReply(
        text,
        res.usage().inputTokens().toLong,
        res.usage().outputTokens().toLong,
        Option(res.stopReasonAsString())
      )
    }.toEither.left.map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")

object BedrockLlmClient:
  val ExpiredTokenCodes = Set("ExpiredToken", "ExpiredTokenException")

  /** The session token was expired when AWS checked it (Bedrock: ExpiredTokenException, "The
    * security token included in the request is expired").
    */
  def isExpiredToken(e: AwsServiceException): Boolean =
    Option(e.awsErrorDetails).flatMap(d => Option(d.errorCode)).exists(ExpiredTokenCodes) ||
      Option(e.getMessage).exists(_.contains("security token included in the request is expired"))

  /** A live client and the SDK client to close. Credentials: [[RefreshingCredentials]] over the
    * named profile (`Some`, the bounded live profile) or the default provider chain (`None`, the
    * local Runner). SDK retries are off, so every request is one of ours and passes
    * `beforeRequest`.
    */
  def live(
      modelId: String,
      region: String,
      profile: Option[String],
      timeoutMs: Int,
      beforeRequest: () => Unit = () => ()
  ): (BedrockLlmClient, AutoCloseable) =
    val creds = RefreshingCredentials(() =>
      profile.fold[AwsCredentialsProvider](DefaultCredentialsProvider.builder().build())(
        ProfileCredentialsProvider.create
      )
    )
    val sdk = BedrockRuntimeClient
      .builder()
      .region(Region.of(region))
      .credentialsProvider(creds)
      .overrideConfiguration(
        ClientOverrideConfiguration
          .builder()
          .retryStrategy(AwsRetryStrategy.doNotRetry())
          .apiCallTimeout(Duration.ofMillis(timeoutMs.toLong))
          .build()
      )
      .build()
    (BedrockLlmClient(modelId, sdk.converse(_), () => creds.refresh(), beforeRequest), sdk)

/** Credentials that are never resolved once for good (Phase 7 ruling 35, reimplemented here because
  * entityResolution may not depend on storage). The SDK calls resolveCredentials for every request;
  * the delegate (for a profile, a ProfileCredentialsProvider, which caches and refreshes by the
  * credentials' Expiration) is built on first use, and refresh() drops it so the next request
  * re-reads the profile and reruns credential_process. The adjudication tools are single-threaded.
  */
final class RefreshingCredentials(build: () => AwsCredentialsProvider)
    extends AwsCredentialsProvider:
  private var current: Option[AwsCredentialsProvider] = None

  private def delegate(): AwsCredentialsProvider = synchronized {
    current.getOrElse {
      val p = build()
      current = Some(p)
      p
    }
  }

  def resolveCredentials(): AwsCredentials = delegate().resolveCredentials()

  def refresh(): Unit = synchronized { current = None }

/** Paces call starts at least ceil(60000 / perMinute) ms apart, so no 60-second window holds more
  * than perMinute starts. The first acquire returns at once. Single-threaded. `nowMs` and `sleep`
  * both use milliseconds; [[Throttle.live]] wires the monotonic clock and Thread.sleep. The cap
  * `maxPerMinute` is the Bedrock bound; only tests raise it.
  */
final class Throttle(
    perMinute: Int,
    nowMs: () => Long,
    sleep: Long => Unit,
    maxPerMinute: Int = Throttle.MaxPerMinute
):
  require(
    perMinute >= 1 && perMinute <= maxPerMinute,
    s"per-minute $perMinute outside 1 to $maxPerMinute"
  )
  val intervalMs: Long = (60000L + perMinute - 1) / perMinute
  private var next = Long.MinValue
  def acquire(): Unit =
    val now = nowMs()
    if next > now then sleep(next - now)
    // from the actual start, so a late wake-up never shortens the next gap
    next = math.max(nowMs(), next) + intervalMs

object Throttle:
  /** Phase 9 ruling 11: Claude Haiku 4.5 at most 8 requests per minute (account quota 10). */
  val MaxPerMinute = 8
  def monotonicMs(): Long = System.nanoTime() / 1000000
  def sleepMs(ms: Long): Unit = Thread.sleep(ms)

  /** The throttle the live tools use: monotonic clock and real sleep, both in milliseconds. */
  def live(perMinute: Int): Throttle = Throttle(perMinute, () => monotonicMs(), sleepMs)
