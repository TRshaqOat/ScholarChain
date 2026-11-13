package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{ErrorCode, JobState, StableIds}
import scholarchain.storage.live.*
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue as Sdk,
  ConditionalCheckFailedException,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse,
  QueryRequest,
  QueryResponse,
  ReturnValuesOnConditionCheckFailure
}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardOpenOption}
import java.time.Instant
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Phase 10 work order 4: job and idempotency stores, evidence by ID, and their live requests. No
  * network: the live adapters run against a stub DynamoDbClient.
  */
class ServiceStateSpec extends AnyFunSuite:
  import AttributeValue.*
  import Fixtures.*

  private var t = Instant.parse("2026-11-12T00:00:00.123Z")
  private val now = () => t
  private def q(n: Int) = "qry-" + f"$n%032x"
  private val (q1, q2) = (q(1), q(2))
  private val req = """{"question":"Who collaborated with X?","execution":"async"}"""
  private val h1 = Hashing.sha256("body-1")
  private val h2 = Hashing.sha256("body-2")
  private val answerKey = s"reports/rag-answers/snapshot=$snapshot/run=$runId/$q1.json"

  private def local() = LocalServiceStateTable(None, now)

  test("jobs: create is conditional; a retried create is Unchanged, another request a Conflict") {
    val js = JobStore(local(), 168, now)
    val Right(created) = js.create(q1, req): @unchecked
    assert(created.outcome == LoadOutcome.Inserted)
    val j = created.record
    assert(j.jobId == StableIds.jobId(q1) && j.state == JobState.QUEUED && j.stage.isEmpty)
    assert(j.createdAt == t && j.expiresAt == t.getEpochSecond + 168 * 3600L)
    assert(js.create(q1, req) == Right(Stored(LoadOutcome.Unchanged, j)))
    assert(js.create(q1, """{"question":"other"}""") == Right(Stored(LoadOutcome.Conflict, j)))
    assert(js.get(j.jobId) == Right(Some(j)))
    assert(js.get(StableIds.jobId(q2)) == Right(None))
    assert(js.get("job-not-an-id") == Right(None))
    assert(js.create("qry-bad", req).isLeft)
    assert(js.create(q2, "not json").isLeft && js.create(q2, "[1]").isLeft)
    assert(js.get(StableIds.jobId(q2)) == Right(None)) // nothing written on Left
  }

  test("jobs: only JobState.canMoveTo transitions, each conditional on the stored state") {
    val js = JobStore(local(), 168, now)
    val id = js.create(q1, req).toOption.get.record.jobId
    assert(js.transition(id, JobState.QUEUED, JobState.SUCCEEDED).isLeft)
    assert(js.transition(id, JobState.RUNNING, JobState.RUNNING).isLeft)
    val queued = js.get(id).toOption.flatten.get
    assert(
      js.transition(id, JobState.RUNNING, JobState.SUCCEEDED) ==
        Right(JobTransition.Refused(Some(queued)))
    )
    t = t.plusSeconds(2)
    val Right(JobTransition.Moved(running)) =
      js.transition(id, JobState.QUEUED, JobState.RUNNING, stage = Some("PLAN")): @unchecked
    assert(
      running.startedAt.contains(t) && running.stage.contains("PLAN") && running.updatedAt == t
    )
    // a redelivered message: the second worker's QUEUED -> RUNNING is refused
    assert(
      js.transition(id, JobState.QUEUED, JobState.RUNNING) ==
        Right(JobTransition.Refused(Some(running)))
    )
    assert(js.transition(id, JobState.RUNNING, JobState.FAILED).isLeft) // FAILED needs an error
    assert(
      js.transition(
        id,
        JobState.RUNNING,
        JobState.SUCCEEDED,
        error = Some(JobError(ErrorCode.INTERNAL_ERROR, "x"))
      ).isLeft
    )
    assert(js.transition(id, JobState.RUNNING, JobState.SUCCEEDED, resultRef = Some("x")).isLeft)
    assert(
      js.transition(id, JobState.RUNNING, JobState.SUCCEEDED, stage = Some("no stage!")).isLeft
    )
    assert(js.get(id) == Right(Some(running))) // nothing written by any Left
    val Right(JobTransition.Moved(done)) =
      js.transition(
        id,
        JobState.RUNNING,
        JobState.SUCCEEDED,
        resultRef = Some(answerKey)
      ): @unchecked
    assert(done.finishedAt.contains(t) && done.stage.contains("PLAN") && done.error.isEmpty)
    assert(
      js.transition(
        id,
        JobState.RUNNING,
        JobState.FAILED,
        error = Some(JobError(ErrorCode.INTERNAL_ERROR, "x"))
      ) ==
        Right(JobTransition.Refused(Some(done)))
    )
    // QUEUED -> FAILED ends a job that never ran (poison message)
    val other = js.create(q2, req).toOption.get.record.jobId
    val err = JobError(ErrorCode.INTERNAL_ERROR, "stored request does not decode")
    val Right(JobTransition.Moved(failed)) =
      js.transition(other, JobState.QUEUED, JobState.FAILED, error = Some(err)): @unchecked
    assert(failed.error.contains(err) && failed.startedAt.isEmpty && failed.finishedAt.isDefined)
    assert(
      js.transition(StableIds.jobId(q(3)), JobState.QUEUED, JobState.RUNNING) ==
        Right(JobTransition.Refused(None))
    )
  }

  test("jobs: eight workers race QUEUED -> RUNNING on one job and exactly one wins") {
    val js = JobStore(local(), 168, now)
    val id = js.create(q1, req).toOption.get.record.jobId
    val pool = Executors.newFixedThreadPool(8)
    val go = CountDownLatch(1)
    try
      val results = (1 to 8).map(_ =>
        pool.submit(new java.util.concurrent.Callable[Either[String, JobTransition]]:
          def call() =
            go.await()
            js.transition(id, JobState.QUEUED, JobState.RUNNING))
      )
      go.countDown()
      val outs = results.map(_.get(10, TimeUnit.SECONDS))
      assert(outs.count(_.exists(_.isInstanceOf[JobTransition.Moved])) == 1)
      assert(outs.count(_.exists(_.isInstanceOf[JobTransition.Refused])) == 7)
    finally pool.shutdownNow()
  }

  test("idempotency: claim, replay, conflict, TTL expiry, and the key is stored only as its hash") {
    val table = local()
    val is = IdempotencyStore(table, 24, now)
    val Right(first) = is.claim("client-key-1", h1, q1): @unchecked
    assert(first.outcome == LoadOutcome.Inserted && first.record.queryId == q1)
    assert(first.record.recordKey == "idem-" + Hashing.sha256("client-key-1").drop(7))
    val stored = table.get(first.record.recordKey, ServiceStateContract.IdempotencyType).get
    assert(!stored.values.exists(_ == S("client-key-1")))
    // the same body replays the first query; another body is a conflict
    assert(is.claim("client-key-1", h1, q2) == Right(Stored(LoadOutcome.Unchanged, first.record)))
    assert(is.claim("client-key-1", h2, q2) == Right(Stored(LoadOutcome.Conflict, first.record)))
    assert(is.get("client-key-1") == Right(Some(first.record)))
    t = t.plusSeconds(24 * 3600L) // expiresAt reached: gone for reads and claims
    assert(is.get("client-key-1") == Right(None))
    assert(is.claim("client-key-1", h2, q2).map(_.outcome) == Right(LoadOutcome.Inserted))
    assert(is.claim("", h1, q1).isLeft)
    assert(is.claim("k", "not-a-hash", q1).isLeft)
    assert(is.claim("k", h1, "qry-bad").isLeft)
    val js = JobStore(table, 1, now)
    val id = js.create(q1, req).toOption.get.record.jobId
    t = t.plusSeconds(3600L)
    assert(js.get(id) == Right(None)) // an expired job is gone
    assert(js.create(q1, """{"question":"again"}""").map(_.outcome) == Right(LoadOutcome.Inserted))
  }

  test(
    "file-backed fake: the log replays on reopen, a torn last line is cut, a bad line stops it"
  ) {
    withTemp { dir =>
      val f = dir.resolve("services/ServiceState.jsonl")
      val js = JobStore(LocalServiceStateTable(Some(f), now), 168, now)
      val is = IdempotencyStore(LocalServiceStateTable(Some(f), now), 24, now)
      val id = js.create(q1, req).toOption.get.record.jobId
      val running = js.transition(id, JobState.QUEUED, JobState.RUNNING) match
        case Right(JobTransition.Moved(j)) => j
        case other                         => fail(other.toString)
      val claim = is.claim("k", h1, q1).toOption.get.record
      Files.writeString(f, """{"Item": {"recordKey"""", UTF_8, StandardOpenOption.APPEND)
      val reopened = LocalServiceStateTable(Some(f), now)
      assert(JobStore(reopened, 168, now).get(id) == Right(Some(running)))
      assert(
        IdempotencyStore(reopened, 24, now).claim("k", h1, q2) ==
          Right(Stored(LoadOutcome.Unchanged, claim))
      )
      assert(Files.readString(f).endsWith("\n")) // the torn line was cut
      Files.writeString(f, "garbage\n", UTF_8, StandardOpenOption.APPEND)
      assertThrows[IllegalStateException](LocalServiceStateTable(Some(f), now))
    }
  }

  test("ServiceState rules: 96 KB budget, fixed schema versions, and no other port writes it") {
    val js = JobStore(local(), 168, now)
    def body(n: Int) = ujson.Obj("question" -> "x" * n).render()
    assert(js.create(q1, body(70000)).isRight) // a 64 KiB request body fits
    assert(js.create(q2, body(100000)).left.exists(_.contains("budget")))
    val item = ServiceStateContract.jobItem(js.get(StableIds.jobId(q1)).toOption.flatten.get)
    val spec = DynamoContract.ServiceState
    assert(DynamoContract.problems(spec, item, Map.empty).isEmpty)
    assert(
      DynamoContract.problems(spec, item + ("schemaVersion" -> S("job-v0")), Map.empty).nonEmpty
    )
    assert(
      DynamoContract
        .problems(spec, item + ("recordKey" -> S(StableIds.jobId(q2))), Map.empty)
        .nonEmpty
    )
    assert(DynamoContract.problems(spec, item + ("recordType" -> S("OTHER")), Map.empty).nonEmpty)
    val db = InMemoryDynamo(windows, () => t.getEpochSecond)
    assert(db.runtimeWriter.create(TableName.ServiceState, item).isLeft)
    assert(db.loader.putNew(TableName.ServiceState, item).isLeft)
  }

  // ---- evidence by ID -------------------------------------------------------------------------

  private val ev = "ev-" + "e" * 32
  private def evItem(queryId: String, expiresAt: Long, payload: String): Item = Map(
    "queryId" -> S(queryId),
    "itemKey" -> S(s"EV#$ev"),
    "expiresAt" -> num(expiresAt),
    "schemaVersion" -> S("rag-evidence-bundle-item-v1"),
    "payload" -> S(payload)
  )

  test("evidence by ID: the smallest unexpired queryId's EV# item, in memory and file-backed") {
    val nowSec = 1000L
    val db = InMemoryDynamo(windows, () => nowSec)
    assert(db.evidenceLookup.evidenceItem(ev).isEmpty)
    db.runtimeWriter.create(TableName.EvidenceBundle, evItem(q2, 5000, "two"))
    db.runtimeWriter.create(TableName.EvidenceBundle, evItem(q1, 900, "one-expired"))
    assert(db.evidenceLookup.evidenceItem(ev).flatMap(_.get("payload")).contains(S("two")))
    db.runtimeWriter.create(TableName.EvidenceBundle, evItem(q(0), 5000, "zero"))
    assert(db.evidenceLookup.evidenceItem(ev).flatMap(_.get("payload")).contains(S("zero")))
    assert(db.evidenceLookup.evidenceItem("ev-" + "f" * 32).isEmpty)
    assert(db.evidenceLookup.evidenceItem("EV#" + ev).isEmpty) // malformed: no key is built
    withTemp { dir =>
      val recs = FileRuntimeRecords(dir, windows, () => nowSec)
      recs.writer.create(TableName.EvidenceBundle, evItem(q2, 5000, "two"))
      val reopened = FileRuntimeRecords(dir, windows, () => nowSec)
      assert(reopened.evidenceLookup.evidenceItem(ev).flatMap(_.get("payload")).contains(S("two")))
    }
  }

  // ---- live adapters against a stub client ------------------------------------------------------

  /** A DynamoDbClient that evaluates the two ServiceState conditions on a map (no network). */
  private final class StubDynamo extends DynamoDbClient:
    val items = scala.collection.mutable.HashMap.empty[(String, String), java.util.Map[String, Sdk]]
    var gets = 0
    var failNextGet = 0
    var failNextPut = false
    var lastQuery: Option[QueryRequest] = None
    var queryItems: Vector[java.util.Map[String, Sdk]] = Vector.empty
    def serviceName(): String = "dynamodb"
    def close(): Unit = ()
    private def key(m: java.util.Map[String, Sdk]) = (m.get("recordKey").s, m.get("recordType").s)
    override def getItem(r: GetItemRequest): GetItemResponse =
      gets += 1
      if failNextGet > 0 then
        failNextGet -= 1
        throw SdkClientException.create("connection reset")
      items
        .get(key(r.key))
        .fold(GetItemResponse.builder().build())(i => GetItemResponse.builder().item(i).build())
    override def putItem(r: PutItemRequest): PutItemResponse =
      if failNextPut then
        failNextPut = false
        throw SdkClientException.create("timeout")
      val k = key(r.item)
      val stored = items.get(k)
      val ok = r.conditionExpression match
        case "attribute_not_exists(#pk) OR #e <= :now" =>
          stored.forall(
            _.get("expiresAt").n.toLong <= r.expressionAttributeValues.get(":now").n.toLong
          )
        case "#s = :from" =>
          stored.exists(_.get("state").s == r.expressionAttributeValues.get(":from").s)
        case other => fail(s"unexpected condition $other")
      if !ok then
        val b = ConditionalCheckFailedException.builder().message("failed")
        throw stored.fold(b)(b.item(_)).build()
      items(k) = r.item
      PutItemResponse.builder().build()
    override def query(r: QueryRequest): QueryResponse =
      lastQuery = Some(r)
      QueryResponse.builder().items(queryItems.asJava).build()

  private val fast = RetryPolicy(3, 0, 0)

  test("live ServiceState: conditional PutItem requests, ALL_OLD outcomes, and reads-only retry") {
    val pi = ServiceStateRequests.putIfAbsent("p-ServiceState", Map("recordKey" -> S("x")), 77L)
    assert(pi.tableName == "p-ServiceState")
    assert(pi.conditionExpression == "attribute_not_exists(#pk) OR #e <= :now")
    assert(pi.expressionAttributeNames.asScala == Map("#pk" -> "recordKey", "#e" -> "expiresAt"))
    assert(pi.expressionAttributeValues.get(":now").n == "77")
    assert(pi.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD)
    val ps = ServiceStateRequests.putIfState("t", Map("recordKey" -> S("x")), JobState.RUNNING)
    assert(
      ps.conditionExpression == "#s = :from" && ps.expressionAttributeNames.get("#s") == "state"
    )
    assert(ps.expressionAttributeValues.get(":from").s == "RUNNING")
    assert(ps.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD)

    val stub = StubDynamo()
    val table = DynamoServiceStateTable(stub, "p-", now, fast, _ => ())
    val js = JobStore(table, 168, now)
    val is = IdempotencyStore(table, 24, now)
    val j = js.create(q1, req).toOption.get.record
    assert(js.create(q1, req) == Right(Stored(LoadOutcome.Unchanged, j)))
    assert(js.create(q1, """{"x":1}""").map(_.outcome) == Right(LoadOutcome.Conflict))
    val Right(JobTransition.Moved(running)) =
      js.transition(j.jobId, JobState.QUEUED, JobState.RUNNING): @unchecked
    assert(
      js.transition(j.jobId, JobState.QUEUED, JobState.RUNNING) ==
        Right(JobTransition.Refused(Some(running)))
    )
    assert(is.claim("k", h1, q1).map(_.outcome) == Right(LoadOutcome.Inserted))
    assert(is.claim("k", h2, q2).map(_.outcome) == Right(LoadOutcome.Conflict))
    // a read is retried by the policy; a conditional write is not
    stub.failNextGet = 2
    val before = stub.gets
    assert(js.get(j.jobId) == Right(Some(running)))
    assert(stub.gets - before == 3)
    stub.failNextPut = true
    assertThrows[TransientStoreException](js.create(q2, req))
    assert(js.get(StableIds.jobId(q2)) == Right(None))
  }

  test("live evidence by ID: the evidenceById GSI query, unexpired only, first item") {
    val r = ServiceStateRequests.evidenceById("p-EvidenceBundle", ev, 1000L)
    assert(r.tableName == "p-EvidenceBundle" && r.indexName == DynamoContract.EvidenceByIdIndex)
    assert(r.keyConditionExpression == "#k = :k" && r.filterExpression == "#e > :now")
    assert(r.expressionAttributeValues.get(":k").s == s"EV#$ev")
    assert(r.expressionAttributeValues.get(":now").n == "1000")
    assert(r.scanIndexForward && r.limit == ServiceStateRequests.EvidenceLookupLimit)
    assert(Option(r.consistentRead).forall(!_.booleanValue)) // a GSI read is eventually consistent
    val stub = StubDynamo()
    val nowAt = Instant.ofEpochSecond(1000L)
    val lookup = DynamoEvidenceLookup(stub, "p-", () => nowAt, fast, _ => ())
    stub.queryItems = Vector(evItem(q1, 900, "expired"), evItem(q2, 5000, "two"))
      .map(DynamoCodec.toSdkItem)
    assert(lookup.evidenceItem(ev).flatMap(_.get("payload")).contains(S("two")))
    stub.lastQuery = None
    assert(lookup.evidenceItem("not-an-id").isEmpty && stub.lastQuery.isEmpty)
  }
