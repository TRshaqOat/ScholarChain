package scholarchain.storage.live

import scholarchain.core.{IdFormat, JobState}
import scholarchain.storage.*
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue as Sdk,
  ConditionalCheckFailedException,
  PutItemRequest,
  QueryRequest,
  ReturnValuesOnConditionCheckFailure
}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  ChangeMessageVisibilityRequest,
  DeleteMessageRequest,
  Message,
  MessageNotInflightException,
  MessageSystemAttributeName,
  ReceiptHandleIsInvalidException,
  ReceiveMessageRequest,
  SendMessageRequest
}

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

private def jitter(ms: Long): Unit = Thread.sleep(ms / 2 + scala.util.Random.nextLong(ms / 2 + 1))

/** DynamoDB requests of the ServiceState table and the EvidenceBundle evidenceById index. */
object ServiceStateRequests:
  private def str(s: String) = Sdk.builder().s(s).build()
  private def num(n: Long) = Sdk.builder().n(n.toString).build()

  /** Put when no item has the key or the stored one has expired (expiresAt <= now); on failure
    * DynamoDB returns the stored item.
    */
  def putIfAbsent(table: String, item: Item, nowEpochSeconds: Long): PutItemRequest =
    PutItemRequest
      .builder()
      .tableName(table)
      .item(DynamoCodec.toSdkItem(item))
      .conditionExpression("attribute_not_exists(#pk) OR #e <= :now")
      .expressionAttributeNames(
        Map(
          "#pk" -> DynamoContract.ServiceState.partitionKey,
          "#e" -> DynamoContract.TtlAttribute
        ).asJava
      )
      .expressionAttributeValues(Map(":now" -> num(nowEpochSeconds)).asJava)
      .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
      .build()

  /** Put only when the stored job is in state expected; on failure the stored item comes back. */
  def putIfState(table: String, item: Item, expected: JobState): PutItemRequest =
    PutItemRequest
      .builder()
      .tableName(table)
      .item(DynamoCodec.toSdkItem(item))
      .conditionExpression("#s = :from")
      .expressionAttributeNames(Map("#s" -> "state").asJava)
      .expressionAttributeValues(Map(":from" -> str(expected.toString)).asJava)
      .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
      .build()

  /** Items evaluated per evidence lookup page (shortcut: one page; enough unless that many expired
    * bundles, not yet deleted by TTL, hold the same evidence ID).
    */
  val EvidenceLookupLimit = 100

  /** evidenceById: itemKey = EV#<evidenceId>, unexpired, smallest queryId first. A GSI read is
    * eventually consistent.
    */
  def evidenceById(table: String, evidenceId: String, nowEpochSeconds: Long): QueryRequest =
    QueryRequest
      .builder()
      .tableName(table)
      .indexName(DynamoContract.EvidenceByIdIndex)
      .keyConditionExpression("#k = :k")
      .filterExpression("#e > :now")
      .expressionAttributeNames(Map("#k" -> "itemKey", "#e" -> DynamoContract.TtlAttribute).asJava)
      .expressionAttributeValues(
        Map(":k" -> str(s"EV#$evidenceId"), ":now" -> num(nowEpochSeconds)).asJava
      )
      .scanIndexForward(true)
      .limit(EvidenceLookupLimit)
      .build()

/** Live ServiceState table (physical name tablePrefix + "ServiceState"). get is a consistent
  * GetItem, retried by policy (services.resilience; reads only, ruling 9). The two conditional puts
  * are single attempts: a TransientStoreException from one leaves the outcome unknown, so read the
  * record back before deciding.
  */
final class DynamoServiceStateTable(
    client: DynamoDbClient,
    tablePrefix: String,
    now: () => Instant,
    policy: RetryPolicy,
    sleep: Long => Unit = jitter
) extends ServiceStateTable:
  private val spec = DynamoContract.ServiceState
  private val table = tablePrefix + TableName.ServiceState.toString
  private def decode(m: java.util.Map[String, Sdk]): Item =
    DynamoCodec.fromSdkItem(m).fold(e => throw IllegalStateException(e), identity)

  def get(recordKey: String, recordType: String): Option[Item] =
    val r = Retry
      .run(policy, sleep)(
        LiveAws.guarded(
          client.getItem(DynamoRequests.get(table, spec, recordKey, recordType))
        )
      )
      ._1
    Option
      .when(r.hasItem && !r.item.isEmpty)(decode(r.item))
      .filter(DynamoContract.unexpired(spec, _, now().getEpochSecond))

  def putIfAbsent(item: Item): Either[Item, Unit] =
    try
      LiveAws.guarded(
        client.putItem(
          ServiceStateRequests.putIfAbsent(
            table,
            AttributeValue.canonicalItem(item),
            now().getEpochSecond
          )
        )
      )
      Right(())
    catch case e: ConditionalCheckFailedException if e.hasItem => Left(decode(e.item))

  def putIfState(item: Item, expected: JobState): Either[Option[Item], Unit] =
    try
      LiveAws.guarded(
        client.putItem(
          ServiceStateRequests.putIfState(table, AttributeValue.canonicalItem(item), expected)
        )
      )
      Right(())
    catch
      case e: ConditionalCheckFailedException =>
        Left(Option.when(e.hasItem && !e.item.isEmpty)(decode(e.item)))

/** Live EvidenceLookupPort over the evidenceById GSI of tablePrefix + "EvidenceBundle". Retried by
  * policy (a read).
  */
final class DynamoEvidenceLookup(
    client: DynamoDbClient,
    tablePrefix: String,
    now: () => Instant,
    policy: RetryPolicy,
    sleep: Long => Unit = jitter
) extends EvidenceLookupPort:
  private val table = tablePrefix + TableName.EvidenceBundle.toString

  def evidenceItem(evidenceId: String): Option[Item] =
    if !IdFormat.isEvidenceId(evidenceId) then None
    else
      val t = now().getEpochSecond
      Retry
        .run(policy, sleep)(
          LiveAws.guarded(client.query(ServiceStateRequests.evidenceById(table, evidenceId, t)))
        )
        ._1
        .items
        .asScala
        .iterator
        .map(m => DynamoCodec.fromSdkItem(m).fold(e => throw IllegalStateException(e), identity))
        .find(DynamoContract.unexpired(DynamoContract.EvidenceBundle, _, t))

/** SQS requests. Every request carries apiCallTimeout = timeoutMs (services.timeouts.sqs-ms); a
  * receive adds its long-poll wait, so the HTTP call outlives WaitTimeSeconds.
  */
object SqsRequests:
  private def timeout(ms: Long) =
    AwsRequestOverrideConfiguration.builder().apiCallTimeout(Duration.ofMillis(ms)).build()

  def send(queueUrl: String, body: String, timeoutMs: Long): SendMessageRequest =
    SendMessageRequest
      .builder()
      .queueUrl(queueUrl)
      .messageBody(body)
      .overrideConfiguration(timeout(timeoutMs))
      .build()

  def receive(
      queueUrl: String,
      maxMessages: Int,
      waitSeconds: Int,
      visibilitySeconds: Int,
      timeoutMs: Long
  ): ReceiveMessageRequest =
    JobQueueLimits.check(maxMessages, waitSeconds, visibilitySeconds)
    ReceiveMessageRequest
      .builder()
      .queueUrl(queueUrl)
      .maxNumberOfMessages(maxMessages)
      .waitTimeSeconds(waitSeconds)
      .visibilityTimeout(visibilitySeconds)
      .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
      .overrideConfiguration(timeout(timeoutMs + waitSeconds * 1000L))
      .build()

  def delete(queueUrl: String, handle: String, timeoutMs: Long): DeleteMessageRequest =
    DeleteMessageRequest
      .builder()
      .queueUrl(queueUrl)
      .receiptHandle(handle)
      .overrideConfiguration(timeout(timeoutMs))
      .build()

  def changeVisibility(
      queueUrl: String,
      handle: String,
      seconds: Int,
      timeoutMs: Long
  ): ChangeMessageVisibilityRequest =
    JobQueueLimits.visibility(seconds)
    ChangeMessageVisibilityRequest
      .builder()
      .queueUrl(queueUrl)
      .receiptHandle(handle)
      .visibilityTimeout(seconds)
      .overrideConfiguration(timeout(timeoutMs))
      .build()

  /** ApproximateReceiveCount, or 1 when SQS left it out. */
  def received(m: Message): ReceivedJob =
    val count = Option(m.attributes.get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
      .flatMap(_.toIntOption)
      .getOrElse(1)
    ReceivedJob(m.messageId, m.receiptHandle, count, JobMessage.parse(m.body))

/** Live JobQueuePort over one SQS standard queue (services.queue.url). The client comes from
  * LiveAws.sqs (the shared RefreshingCredentials of the profile, SDK retries off). No call is
  * retried here (ruling 9 retries reads only, and none of these is a pure read): the worker's poll
  * loop is the retry, and the job store makes a duplicate send or delivery harmless. The redrive
  * policy (max-receive-count, dead-letter queue) belongs to the queue, created by Terraform.
  */
final class SqsJobQueue(client: SqsClient, queueUrl: String, timeoutMs: Long) extends JobQueuePort:
  require(timeoutMs >= 1, s"timeout $timeoutMs ms")

  def send(message: JobMessage): String =
    LiveAws
      .guarded(
        client.sendMessage(SqsRequests.send(queueUrl, JobMessage.render(message), timeoutMs))
      )
      .messageId

  def receive(maxMessages: Int, waitSeconds: Int, visibilitySeconds: Int): Vector[ReceivedJob] =
    val r = SqsRequests.receive(queueUrl, maxMessages, waitSeconds, visibilitySeconds, timeoutMs)
    LiveAws.guarded(client.receiveMessage(r)).messages.asScala.map(SqsRequests.received).toVector

  /** SQS answers success for an old handle without deleting (DeleteMessage); only an invalid handle
    * gives false.
    */
  def delete(receiptHandle: String): Boolean =
    try
      LiveAws.guarded(client.deleteMessage(SqsRequests.delete(queueUrl, receiptHandle, timeoutMs)))
      true
    catch case _: ReceiptHandleIsInvalidException => false

  def changeVisibility(receiptHandle: String, seconds: Int): Boolean =
    try
      LiveAws.guarded(
        client.changeMessageVisibility(
          SqsRequests.changeVisibility(queueUrl, receiptHandle, seconds, timeoutMs)
        )
      )
      true
    catch case _: ReceiptHandleIsInvalidException | _: MessageNotInflightException => false
