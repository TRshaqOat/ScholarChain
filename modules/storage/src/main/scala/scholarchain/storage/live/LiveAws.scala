package scholarchain.storage.live

import scholarchain.storage.TransientStoreException
import software.amazon.awssdk.auth.credentials.{
  AwsCredentials,
  AwsCredentialsProvider,
  ProfileCredentialsProvider
}
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.SdkNumber
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.utils.SdkAutoCloseable

import java.net.URI
import java.time.Duration
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/** The explicit opt-in for live AWS calls: both --live and --profile, neither on by default
  * (rulings 1 and 26). Nothing in this package runs unless a CLI was given one.
  */
final case class LiveTarget(profile: String, region: String)

/** Credentials of one named profile that are never resolved once for good. The SDK calls
  * resolveCredentials for every request; the delegate (a ProfileCredentialsProvider, which caches
  * and refreshes by the credentials' Expiration) is built on first use, and refresh() drops it so
  * the next request re-reads the profile and reruns credential_process.
  */
final class RefreshingCredentials(build: () => AwsCredentialsProvider)
    extends AwsCredentialsProvider
    with SdkAutoCloseable:
  private var current: Option[AwsCredentialsProvider] = None
  @volatile private var gen = 0L

  /** Increases with every refresh; a caller notes it before a request (N5). */
  def generation: Long = gen

  private def delegate(): AwsCredentialsProvider = synchronized {
    current.getOrElse {
      val p = build()
      current = Some(p)
      p
    }
  }

  def resolveCredentials(): AwsCredentials = delegate().resolveCredentials()

  // The old delegate is dropped, not closed: another thread may still be resolving through it.
  def refresh(): Unit = synchronized {
    current = None
    gen += 1
  }

  /** Refreshes only if nobody has since the caller noted seen, so requests that failed together on
    * one expiry rerun credential_process once, not once per thread (N5).
    */
  def refreshIfCurrent(seen: Long): Unit = synchronized {
    if gen == seen then refresh()
  }

  def close(): Unit = refresh()

/** SDK clients and error mapping for the live adapters. Clients are built only from a LiveTarget,
  * share one RefreshingCredentials per profile, and have SDK retries off: the storage RetryPolicy
  * is the one bounded retry layer. An expired-token error refreshes the credentials and retries the
  * call once (ruling 35). That retry is a second request, so a throttled caller passes its throttle
  * as beforeRetry (guardedWith): then every attempt, retries included, passes the throttle (Phase 7
  * review 2 N1). BedrockTitanEmbedder does this when EmbedCli gives it the runner's throttle.
  */
object LiveAws:
  def overrides: ClientOverrideConfiguration = ClientOverrideConfiguration
    .builder()
    .retryStrategy(AwsRetryStrategy.doNotRetry())
    .apiCallTimeout(Duration.ofSeconds(120))
    .build()

  /** overrides with the per-call timeout of one downstream (services.timeouts.*, Phase 10 ruling
    * 9).
    */
  def overridesWithTimeout(timeoutMs: Long): ClientOverrideConfiguration =
    require(timeoutMs >= 1, s"timeout $timeoutMs ms")
    overrides.toBuilder.apiCallTimeout(Duration.ofMillis(timeoutMs)).build()

  private val profiles = TrieMap.empty[String, RefreshingCredentials]

  /** The one refreshing provider of t's profile, created on first request. build replaces the
    * ProfileCredentialsProvider delegate factory (tests only; no profile file is read then).
    */
  def credentials(
      t: LiveTarget,
      build: Option[() => AwsCredentialsProvider] = None
  ): RefreshingCredentials =
    profiles.getOrElseUpdate(
      t.profile,
      RefreshingCredentials(build.getOrElse(() => ProfileCredentialsProvider.create(t.profile)))
    )

  private def configure[B <: AwsClientBuilder[B, ?]](
      b: B,
      t: LiveTarget,
      o: ClientOverrideConfiguration
  ): B = b.region(Region.of(t.region)).credentialsProvider(credentials(t)).overrideConfiguration(o)

  def bedrock(t: LiveTarget, o: ClientOverrideConfiguration = overrides): BedrockRuntimeClient =
    configure(BedrockRuntimeClient.builder(), t, o).build()
  def s3(t: LiveTarget, o: ClientOverrideConfiguration = overrides): S3Client =
    configure(S3Client.builder(), t, o).build()
  def dynamo(t: LiveTarget, o: ClientOverrideConfiguration = overrides): DynamoDbClient =
    configure(DynamoDbClient.builder(), t, o).build()
  def vectors(t: LiveTarget, o: ClientOverrideConfiguration = overrides): S3VectorsClient =
    configure(S3VectorsClient.builder(), t, o).build()
  def sqs(t: LiveTarget, o: ClientOverrideConfiguration = overrides): SqsClient =
    configure(SqsClient.builder(), t, o).build()
  def neptune(
      t: LiveTarget,
      endpoint: String,
      port: Int,
      o: ClientOverrideConfiguration = overrides
  ): NeptunedataClient =
    configure(NeptunedataClient.builder(), t, o)
      .endpointOverride(URI.create(s"https://$endpoint:$port"))
      .build()

  /** Throttling, 5xx, and client-side (network, timeout) failures are retryable. */
  def isTransient(e: Throwable): Boolean = e match
    case s: AwsServiceException => s.isThrottlingException || s.statusCode >= 500
    case _: SdkClientException  => true
    case _                      => false

  val ExpiredTokenCodes = Set("ExpiredToken", "ExpiredTokenException")

  /** The session token was expired when AWS checked it (S3 ExpiredToken; Bedrock, DynamoDB and
    * other JSON services ExpiredTokenException, "The security token included in the request is
    * expired").
    */
  def isExpiredToken(e: AwsServiceException): Boolean =
    Option(e.awsErrorDetails).flatMap(d => Option(d.errorCode)).exists(ExpiredTokenCodes) ||
      Option(e.getMessage).exists(_.contains("security token included in the request is expired"))

  /** S3 HEAD error responses have no body, so an expired token there carries neither the code nor
    * the message; any 403 on HEAD is therefore treated as a possible expiry and gets the one
    * refresh-and-retry (N2). A real denial fails again on the retry and propagates as before.
    */
  def isHeadExpiredToken(e: AwsServiceException): Boolean = isExpiredToken(e) || e.statusCode == 403

  /** Runs op; on an expired-token error (expired), refresh, call beforeRetry, and run it once more.
    * AWS rejects such a request before acting on it, so the second attempt cannot repeat an effect.
    */
  def retryOnExpiredToken[A](
      refresh: () => Unit,
      beforeRetry: () => Unit = () => (),
      expired: AwsServiceException => Boolean = isExpiredToken
  )(op: => A): A =
    try op
    catch
      case e: AwsServiceException if expired(e) =>
        refresh()
        beforeRetry()
        op

  /** Runs op with one refresh-and-retry on an expired token, turning a retryable SDK failure into
    * TransientStoreException.
    */
  def guarded[A](op: => A): A = guardedWith()(op)

  /** guarded with a hook run before the retry (the caller's throttle, N1) and the expiry test. Each
    * profile is refreshed only if no other request refreshed it since this one started (N5).
    */
  def guardedWith[A](
      beforeRetry: () => Unit = () => (),
      expired: AwsServiceException => Boolean = isExpiredToken
  )(op: => A): A =
    val seen = profiles.values.toVector.map(p => p -> p.generation)
    try
      retryOnExpiredToken(
        () => seen.foreach((p, g) => p.refreshIfCurrent(g)),
        beforeRetry,
        expired
      )(op)
    catch
      case e: Exception if isTransient(e) =>
        throw TransientStoreException(s"${e.getClass.getSimpleName}: ${e.getMessage}")

/** ujson <-> SDK Document (S3 Vectors metadata and filters, Neptune loader status payloads). */
object Documents:
  def toDocument(v: ujson.Value): Document = v match
    case ujson.Str(s) => Document.fromString(s)
    case ujson.Num(n) =>
      if n.isWhole && math.abs(n) < 1e15 then Document.fromNumber(n.toLong)
      else Document.fromNumber(n)
    case ujson.Bool(b) => Document.fromBoolean(b)
    case ujson.Null    => Document.fromNull()
    case ujson.Arr(xs) => Document.fromList(xs.map(toDocument).asJava)
    case ujson.Obj(m)  => Document.fromMap(m.map((k, x) => k -> toDocument(x)).toMap.asJava)

  def fromDocument(d: Document): ujson.Value =
    if d == null || d.isNull then ujson.Null
    else if d.isString then ujson.Str(d.asString)
    else if d.isBoolean then ujson.Bool(d.asBoolean)
    else if d.isNumber then ujson.Num(num(d.asNumber))
    else if d.isList then ujson.Arr.from(d.asList.asScala.map(fromDocument))
    else ujson.Obj.from(d.asMap.asScala.toSeq.sortBy(_._1).map((k, x) => k -> fromDocument(x)))

  private def num(n: SdkNumber): Double = n.doubleValue
