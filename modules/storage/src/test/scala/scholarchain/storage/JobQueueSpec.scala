package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import scholarchain.storage.live.*
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  ChangeMessageVisibilityRequest,
  ChangeMessageVisibilityResponse,
  DeleteMessageRequest,
  DeleteMessageResponse,
  Message,
  MessageNotInflightException,
  MessageSystemAttributeName,
  ReceiptHandleIsInvalidException,
  ReceiveMessageRequest,
  ReceiveMessageResponse,
  SendMessageRequest,
  SendMessageResponse
}

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

/** Phase 10 work order 4: the job queue port, its in-memory fake, and the live SQS adapter against
  * a stub client (no network).
  */
class JobQueueSpec extends AnyFunSuite:
  private val q1 = "qry-" + "1" * 32
  private val q2 = "qry-" + "2" * 32
  private var t = Instant.parse("2026-11-12T00:00:00Z")
  private val now = () => t

  test("job message: only the job and query IDs; anything else is a poison message") {
    val m = JobMessage.of(q1)
    assert(m.jobId == StableIds.jobId(q1))
    val body = JobMessage.render(m)
    assert(ujson.read(body).obj.keySet == Set("schemaVersion", "jobId", "queryId"))
    assert(JobMessage.parse(body) == Right(m))
    val bad = Seq(
      "not json",
      "[]",
      ujson.Obj("schemaVersion" -> "job-message-v0", "jobId" -> m.jobId, "queryId" -> q1).render(),
      ujson
        .Obj("schemaVersion" -> "job-message-v1", "jobId" -> StableIds.jobId(q2), "queryId" -> q1)
        .render(),
      ujson
        .Obj("schemaVersion" -> "job-message-v1", "jobId" -> m.jobId, "queryId" -> q1, "x" -> 1)
        .render()
    )
    bad.foreach(b => assert(JobMessage.parse(b).isLeft, b))
  }

  test("fake queue: a receive hides the message for the visibility timeout; receives are counted") {
    val queue = InMemoryJobQueue(maxReceiveCount = 3, now)
    val id = queue.send(JobMessage.of(q1))
    val Vector(first) = queue.receive(10, 0, 300): @unchecked
    assert(first.messageId == id && first.receiveCount == 1)
    assert(first.message == Right(JobMessage.of(q1)))
    assert(queue.receive(10, 0, 300).isEmpty) // in flight
    t = t.plusSeconds(299)
    assert(queue.receive(10, 0, 300).isEmpty)
    t = t.plusSeconds(1) // visibility timeout passed: redelivered with a new handle
    val Vector(second) = queue.receive(10, 0, 300): @unchecked
    assert(second.messageId == id && second.receiveCount == 2)
    assert(second.receiptHandle != first.receiptHandle)
    assert(!queue.delete(first.receiptHandle)) // a stale handle deletes nothing
    assert(queue.size == 1)
    assert(queue.delete(second.receiptHandle) && queue.size == 0)
    assert(!queue.delete(second.receiptHandle))
  }

  test("fake queue: after max-receive-count receives the message moves to the dead-letter queue") {
    val queue = InMemoryJobQueue(maxReceiveCount = 2, now)
    queue.sendRaw("poison")
    queue.send(JobMessage.of(q2))
    val firstRound = queue.receive(10, 0, 30)
    assert(firstRound.map(_.receiveCount) == Vector(1, 1))
    assert(firstRound.head.message.isLeft && firstRound(1).message.isRight)
    queue.delete(firstRound(1).receiptHandle)
    t = t.plusSeconds(30)
    assert(queue.receive(10, 0, 30).map(_.receiveCount) == Vector(2))
    t = t.plusSeconds(30)
    assert(queue.receive(10, 0, 30).isEmpty) // the third receive attempt redrives it
    assert(queue.deadLetters == Vector("poison") && queue.size == 0)
  }

  test("fake queue: change visibility, bounds, order, and a long poll woken by a send") {
    val queue = InMemoryJobQueue(maxReceiveCount = 3, now)
    queue.send(JobMessage.of(q1))
    queue.send(JobMessage.of(q2))
    val Vector(a) = queue.receive(1, 0, 60): @unchecked
    assert(a.message.map(_.queryId) == Right(q1)) // send order
    assert(queue.changeVisibility(a.receiptHandle, 0)) // visible again now
    val Vector(again) = queue.receive(1, 0, 60): @unchecked
    assert(again.message.map(_.queryId) == Right(q1) && again.receiveCount == 2)
    assert(!queue.changeVisibility(a.receiptHandle, 10)) // stale handle
    t = t.plusSeconds(60)
    assert(!queue.changeVisibility(again.receiptHandle, 10)) // no longer in flight
    assertThrows[IllegalArgumentException](queue.receive(11, 0, 30))
    assertThrows[IllegalArgumentException](queue.receive(1, 21, 30))
    assertThrows[IllegalArgumentException](queue.receive(1, 0, 43201))
    assertThrows[IllegalArgumentException](queue.changeVisibility(again.receiptHandle, -1))
    val empty = InMemoryJobQueue(maxReceiveCount = 3) // real clock for the long poll
    val sender = Thread(() => { Thread.sleep(200); empty.send(JobMessage.of(q1)); () })
    val started = System.nanoTime
    sender.start()
    val got = empty.receive(1, 5, 30)
    val waitedMs = (System.nanoTime - started) / 1000000L
    sender.join()
    assert(got.size == 1 && waitedMs >= 150 && waitedMs < 4000, s"waited $waitedMs ms")
    val polled = System.nanoTime
    assert(empty.receive(1, 1, 30).isEmpty) // the received message is in flight
    assert((System.nanoTime - polled) / 1000000L >= 900)
  }

  // ---- live SQS against a stub client ----------------------------------------------------------

  private final class StubSqs extends SqsClient:
    var sent = Vector.empty[SendMessageRequest]
    var received = Vector.empty[ReceiveMessageRequest]
    var deleteError: Option[Exception] = None
    var visibilityError: Option[Exception] = None
    def serviceName(): String = "sqs"
    def close(): Unit = ()
    override def sendMessage(r: SendMessageRequest): SendMessageResponse =
      sent :+= r
      SendMessageResponse.builder().messageId("m-1").build()
    override def receiveMessage(r: ReceiveMessageRequest): ReceiveMessageResponse =
      received :+= r
      val m = Message
        .builder()
        .messageId("m-1")
        .receiptHandle("rh-1")
        .body(JobMessage.render(JobMessage.of(q1)))
        .attributes(Map(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT -> "3").asJava)
        .build()
      val poison = Message.builder().messageId("m-2").receiptHandle("rh-2").body("x").build()
      ReceiveMessageResponse.builder().messages(m, poison).build()
    override def deleteMessage(r: DeleteMessageRequest): DeleteMessageResponse =
      deleteError.foreach(e => throw e)
      DeleteMessageResponse.builder().build()
    override def changeMessageVisibility(
        r: ChangeMessageVisibilityRequest
    ): ChangeMessageVisibilityResponse =
      visibilityError.foreach(e => throw e)
      ChangeMessageVisibilityResponse.builder().build()

  test("live SQS: requests carry the timeout (plus the long poll), receive counts are parsed") {
    val url = "https://sqs.us-east-2.amazonaws.com/000000000000/example-scholarchain-jobs"
    val r = SqsRequests.receive(url, 1, 20, 300, 5000L)
    assert(r.queueUrl == url && r.maxNumberOfMessages == 1 && r.waitTimeSeconds == 20)
    assert(r.visibilityTimeout == 300)
    assert(
      r.messageSystemAttributeNames.asScala == Seq(
        MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT
      )
    )
    assert(r.overrideConfiguration.get.apiCallTimeout.get == Duration.ofMillis(25000L))
    val s = SqsRequests.send(url, "b", 5000L)
    assert(s.overrideConfiguration.get.apiCallTimeout.get == Duration.ofMillis(5000L))
    assert(LiveAws.overridesWithTimeout(2000L).apiCallTimeout.get == Duration.ofMillis(2000L))
    assertThrows[IllegalArgumentException](SqsRequests.receive(url, 1, 21, 300, 5000L))
    assertThrows[IllegalArgumentException](SqsRequests.changeVisibility(url, "h", 43201, 5000L))

    val stub = StubSqs()
    val queue = SqsJobQueue(stub, url, 5000L)
    assert(queue.send(JobMessage.of(q1)) == "m-1")
    assert(JobMessage.parse(stub.sent.head.messageBody) == Right(JobMessage.of(q1)))
    val Vector(ok, poison) = queue.receive(10, 20, 300): @unchecked
    assert(ok.receiveCount == 3 && ok.message == Right(JobMessage.of(q1)))
    assert(poison.receiveCount == 1 && poison.message.isLeft)
    assert(queue.delete("rh-1") && queue.changeVisibility("rh-1", 0))
    stub.deleteError = Some(ReceiptHandleIsInvalidException.builder().message("bad").build())
    assert(!queue.delete("old"))
    stub.visibilityError = Some(MessageNotInflightException.builder().message("no").build())
    assert(!queue.changeVisibility("rh-1", 10))
  }
