package scholarchain.storage

import scholarchain.core.{IdFormat, StableIds}

import java.time.Instant
import scala.collection.mutable
import scala.util.Try

/** The body of one job message (Phase 10 ruling 7): the job and query IDs only, never the request.
  * Wire form: {"schemaVersion":"job-message-v1","jobId":...,"queryId":...}.
  */
final case class JobMessage(jobId: String, queryId: String)

object JobMessage:
  val SchemaVersion = "job-message-v1"

  /** The message of queryId's job (jobId = StableIds.jobId(queryId)). */
  def of(queryId: String): JobMessage = JobMessage(StableIds.jobId(queryId), queryId)

  def render(m: JobMessage): String =
    ujson.Obj("schemaVersion" -> SchemaVersion, "jobId" -> m.jobId, "queryId" -> m.queryId).render()

  /** Left for anything but exactly the three fields, with jobId = StableIds.jobId(queryId). */
  def parse(body: String): Either[String, JobMessage] =
    Try(ujson.read(body)).toOption.flatMap(_.objOpt) match
      case Some(o) if o.keySet == Set("schemaVersion", "jobId", "queryId") =>
        (o("schemaVersion").strOpt, o("jobId").strOpt, o("queryId").strOpt) match
          case (Some(SchemaVersion), Some(j), Some(q))
              if IdFormat.isQueryId(q) && j == StableIds.jobId(q) =>
            Right(JobMessage(j, q))
          case _ => Left("job message fields are malformed")
      case _ => Left("job message is not a job-message-v1 JSON object")

/** One received message. receiveCount counts this receive (1 on the first). message is Left for a
  * body that is not a valid job message (a poison message: fail or drop it, never run it).
  */
final case class ReceivedJob(
    messageId: String,
    receiptHandle: String,
    receiveCount: Int,
    message: Either[String, JobMessage]
)

/** SQS bounds (ReceiveMessage, ChangeMessageVisibility; STORAGE.md service limits). */
object JobQueueLimits:
  val MaxMessages = 10
  val MaxWaitSeconds = 20
  val MaxVisibilitySeconds = 43200

  def check(maxMessages: Int, waitSeconds: Int, visibilitySeconds: Int): Unit =
    require(maxMessages >= 1 && maxMessages <= MaxMessages, s"maxMessages $maxMessages: 1 to 10")
    require(waitSeconds >= 0 && waitSeconds <= MaxWaitSeconds, s"waitSeconds $waitSeconds: 0 to 20")
    visibility(visibilitySeconds)

  def visibility(s: Int): Unit =
    require(s >= 0 && s <= MaxVisibilitySeconds, s"visibility $s s: 0 to 43200")

/** The job queue (Phase 10 ruling 7): an SQS standard queue in the cloud (live.SqsJobQueue), an
  * InMemoryJobQueue locally. Delivery is at least once; the job store's conditional transitions
  * make a redelivered message harmless.
  */
trait JobQueuePort:
  /** Sends one message; returns its message ID. */
  def send(message: JobMessage): String

  /** Long poll: waits up to waitSeconds (0 to 20) for visible messages, returns at most maxMessages
    * (1 to 10), each hidden for visibilitySeconds (0 to 43200; services.worker
    * .visibility-timeout-seconds) from this receive.
    */
  def receive(maxMessages: Int, waitSeconds: Int, visibilitySeconds: Int): Vector[ReceivedJob]

  /** Deletes the message received with this handle. False when the handle is not the latest one of
    * a message still in the queue (a stale handle: the message may come back).
    */
  def delete(receiptHandle: String): Boolean

  /** Hides the in-flight message for seconds (0 to 43200) from now. False when the handle is stale
    * or the message is not in flight.
    */
  def changeVisibility(receiptHandle: String, seconds: Int): Boolean

/** Local fake of the job queue, thread-safe, in memory. It models an SQS standard queue with a
  * redrive policy: a received message is hidden until now + visibilitySeconds; each receive counts
  * and gets a new receipt handle, and only the latest handle deletes or changes the message; a
  * message already received maxReceiveCount times (services.queue.max-receive-count) moves to
  * deadLetters on the next receive attempt instead of being delivered. Messages are delivered in
  * send order (SQS standard queues promise no order). Visibility uses now (inject a clock in
  * tests); the long-poll wait uses real time, rechecked every 50 ms, and a send wakes it. Retention
  * periods and SQS duplicate deliveries are not modelled.
  */
final class InMemoryJobQueue(maxReceiveCount: Int, now: () => Instant = () => Instant.now())
    extends JobQueuePort:
  require(maxReceiveCount >= 1, "maxReceiveCount must be at least 1")

  private final class Msg(val id: String, val body: String):
    var visibleAtMs = 0L
    var receives = 0
    var handle: Option[String] = None

  private val messages = mutable.LinkedHashMap.empty[String, Msg]
  private val dead = mutable.ArrayBuffer.empty[String]
  private var sent = 0L

  private def nowMs = now().toEpochMilli

  def send(message: JobMessage): String = sendRaw(JobMessage.render(message))

  /** Sends any body, for poison-message tests. */
  def sendRaw(body: String): String = synchronized {
    sent += 1
    val m = Msg(f"msg-$sent%08d", body)
    m.visibleAtMs = nowMs
    messages(m.id) = m
    notifyAll()
    m.id
  }

  def receive(maxMessages: Int, waitSeconds: Int, visibilitySeconds: Int): Vector[ReceivedJob] =
    JobQueueLimits.check(maxMessages, waitSeconds, visibilitySeconds)
    val deadline = System.nanoTime + waitSeconds * 1000000000L
    synchronized {
      var out = take(maxMessages, visibilitySeconds)
      while out.isEmpty && System.nanoTime < deadline do
        wait(math.max(1L, math.min(50L, (deadline - System.nanoTime) / 1000000L)))
        out = take(maxMessages, visibilitySeconds)
      out
    }

  private def take(max: Int, visibilitySeconds: Int): Vector[ReceivedJob] =
    val t = nowMs
    val out = Vector.newBuilder[ReceivedJob]
    var n = 0
    messages.values.filter(_.visibleAtMs <= t).toVector.foreach { m =>
      if n < max then
        if m.receives >= maxReceiveCount then
          messages.remove(m.id)
          dead += m.body
        else
          m.receives += 1
          m.handle = Some(s"rh-${m.id}-${m.receives}")
          m.visibleAtMs = t + visibilitySeconds * 1000L
          out += ReceivedJob(m.id, m.handle.get, m.receives, JobMessage.parse(m.body))
          n += 1
    }
    out.result()

  private def byHandle(h: String) = messages.values.find(_.handle.contains(h))

  def delete(receiptHandle: String): Boolean = synchronized {
    byHandle(receiptHandle).map(m => messages.remove(m.id)).isDefined
  }

  def changeVisibility(receiptHandle: String, seconds: Int): Boolean =
    JobQueueLimits.visibility(seconds)
    synchronized {
      val t = nowMs
      byHandle(receiptHandle).filter(_.visibleAtMs > t) match
        case Some(m) =>
          m.visibleAtMs = t + seconds * 1000L
          true
        case None => false
    }

  /** Bodies moved to the dead-letter queue, in move order. */
  def deadLetters: Vector[String] = synchronized(dead.toVector)

  /** Messages not deleted and not dead-lettered (visible or in flight). */
  def size: Int = synchronized(messages.size)
