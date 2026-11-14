package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.{ConfigLoader, TimeWindow}
import scholarchain.core.{JobState, StableIds}
import scholarchain.storage.*
import scholarchain.storage.live.*
import scholarchain.tests.support.StoreWorld
import software.amazon.awssdk.awscore.exception.{AwsErrorDetails, AwsServiceException}
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue as Sdk,
  ConditionalCheckFailedException,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse,
  ReturnValuesOnConditionCheckFailure
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryResponse,
  TimeLimitExceededException
}
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{GetVectorsResponse, PutVectorsResponse}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Phase 11 WO3, spec categories "Neptune timeout", "DynamoDB conditional-write conflict", "S3
  * Vectors throttling or transient failure", and "duplicate vector keys and stale model versions".
  * The live adapters run against JDK dynamic proxies of the SDK client interfaces: no AWS call, no
  * network.
  */
class StoreFailureAdversarialSpec extends AnyFunSuite:
  private val cfg = ConfigLoader.load(StoreWorld.baseEnv).fold(e => sys.error(e.mkString), identity)
  private val limits = GraphReadLimits.from(cfg)

  /** A client proxy whose named operation runs `op`; every other call fails loudly. */
  private def proxy[C](cls: Class[C])(op: PartialFunction[(String, Seq[AnyRef]), AnyRef]): C =
    val h: InvocationHandler = (_: AnyRef, m: Method, args: Array[AnyRef]) =>
      val a = Option(args).fold(Seq.empty[AnyRef])(_.toSeq)
      m.getName match
        case "close"       => null
        case "serviceName" => "proxy"
        case "toString"    => s"proxy-${cls.getSimpleName}"
        case "hashCode"    => Integer.valueOf(System.identityHashCode(this))
        case "equals"      => java.lang.Boolean.FALSE
        case n             => op.applyOrElse((n, a), _ => throw UnsupportedOperationException(n))
    Proxy.newProxyInstance(getClass.getClassLoader, Array(cls), h).asInstanceOf[C]

  private def throttle(
      code: String = "ThrottlingException",
      status: Int = 400
  ): AwsServiceException =
    AwsServiceException
      .builder()
      .message("Rate exceeded (SYNTHETIC)")
      .statusCode(status)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).serviceName("proxy").build())
      .build()

  // ---- Neptune ----

  test("Neptune: a query timeout is GraphReadTimeoutException after one call, never retried") {
    val calls = AtomicInteger()
    val client = proxy(classOf[NeptunedataClient]) { case ("executeOpenCypherQuery", _) =>
      calls.incrementAndGet()
      throw TimeLimitExceededException
        .builder()
        .message("Operation terminated (deadline exceeded)")
        .statusCode(500)
        .build()
    }
    val reader = NeptuneGraphReader(client, limits, RetryPolicy(3, 1, 1), _ => ())
    val e = intercept[GraphReadTimeoutException](reader.vertex("orcid:0000-0002-1825-0097"))
    assert(calls.get == 1, s"${calls.get} calls: a timed-out query was retried")
    assert(
      e.getMessage.contains("graph.vertex") && e.getMessage.contains(limits.queryTimeoutMs.toString)
    )
  }

  test("Neptune: throttling is retried within the policy, then surfaces as a transient failure") {
    val calls = AtomicInteger()
    val empty = ExecuteOpenCypherQueryResponse
      .builder()
      .results(Document.fromList(java.util.List.of()))
      .build()
    def client(failures: Int) = proxy(classOf[NeptunedataClient]) {
      case ("executeOpenCypherQuery", _) =>
        if calls.incrementAndGet() <= failures then throw throttle(status = 429) else empty
    }
    val slept = scala.collection.mutable.ArrayBuffer.empty[Long]
    assert(
      NeptuneGraphReader(client(2), limits, RetryPolicy(3, 10, 40), slept += _)
        .vertex("orcid:0000-0002-1825-0097")
        .isEmpty
    )
    assert(calls.get == 3 && slept.size == 2)
    calls.set(0)
    intercept[TransientStoreException](
      NeptuneGraphReader(client(99), limits, RetryPolicy(3, 10, 40), _ => ())
        .vertex("orcid:0000-0002-1825-0097")
    )
    assert(calls.get == 3)
  }

  /** A port whose frontier reads time out (as Neptune does past its query timeout). */
  private final class TimingOutFrontier(val limits: GraphReadLimits) extends GraphReadPort:
    private def r(id: String) =
      GraphElement(id, "Researcher", "", "", Map[String, Prop]("runId" -> ("run-" + "b" * 32)))
    private def no = throw UnsupportedOperationException("unused")
    protected def readVertex(id: String) = Some(r(id))
    protected def readEdge(id: String) = None
    protected def readVertices(ids: Seq[String]) = ids.map(r).toVector
    protected def readCoauthorEdge(a: String, b: String, window: String) = None
    protected def readCoauthors(id: String, window: String, fetch: Int) = no
    protected def readFrontier(ids: Seq[String], window: String, fetch: Int) =
      throw GraphReadTimeoutException("graph.frontier passed 5000 ms")
    protected def readCommunityMembers(c: String, fetch: Int) = no
    protected def readCommunityNeighbors(c: String, fetch: Int) = no
    protected def readCrossCommunityEdges(id: String, window: String, fetch: Int) = no
    protected def readCommunityFacets(c: String, f: Facet, cap: Int, fetch: Int) = no
    protected def readResearcherFacets(ids: Seq[String], w: TimeWindow, f: Facet, fetch: Int) = no
    protected def readInstitutions(ids: Seq[String], fetch: Int) = no
    protected def readTopicResearchers(ids: Seq[String], w: TimeWindow, fetch: Int) = no
    protected def readLookup(l: LookupLabel, text: String, fetch: Int) = no
    protected def readAuthoredCounts(ids: Seq[String]) = no
    protected def readExpand(ids: Seq[String], d: Direction, t: Seq[String], fetch: Int) = no

  test("Neptune: a frontier timeout during a path search is SEARCH_LIMIT, never 'no path'") {
    val g = TimingOutFrontier(limits)
    val r = g.shortestPath(StoreWorld.ada, StoreWorld.edsger, "all-years", 3, () => 0L)
    assert(r.status == PathStatus.SEARCH_LIMIT, r)
    assert(r.researcherIds.isEmpty && r.hops.isEmpty && r.detail.contains("5000 ms"))
  }

  // ---- DynamoDB conditional writes ----

  /** A proxy with DynamoDB's conditional-put semantics. Work order 4 (code review 1 section 5): it
    * evaluates the request's own ConditionExpression (Condition below), so a wrong expression in
    * DynamoServiceStateTable changes the outcome. A put without a condition, without ALL_OLD on
    * failure, or with an unknown or unused placeholder is refused, as DynamoDB refuses it.
    */
  private def dynamo(
      failPuts: AtomicInteger = AtomicInteger(0),
      calls: AtomicInteger = AtomicInteger()
  ) =
    val items = scala.collection.mutable.HashMap.empty[(String, String), java.util.Map[String, Sdk]]
    def key(m: java.util.Map[String, Sdk]) = (m.get("recordKey").s, m.get("recordType").s)
    proxy(classOf[DynamoDbClient]) {
      case ("getItem", Seq(r: GetItemRequest)) =>
        assert(
          r.consistentRead == java.lang.Boolean.TRUE,
          "a service-state read must be consistent"
        )
        val k = (r.key.get("recordKey").s, r.key.get("recordType").s)
        items
          .synchronized(items.get(k))
          .fold(GetItemResponse.builder().build())(i => GetItemResponse.builder().item(i).build())
      case ("putItem", Seq(r: PutItemRequest)) =>
        calls.incrementAndGet()
        if failPuts.getAndUpdate(n => math.max(0, n - 1)) > 0 then
          throw throttle("ProvisionedThroughputExceededException")
        val cond =
          Option(r.conditionExpression).getOrElse(fail("an unconditional service-state put"))
        assert(
          r.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD,
          "a failed condition must return the stored item"
        )
        val names = r.expressionAttributeNames.asScala.toMap
        val values = r.expressionAttributeValues.asScala.toMap
        items.synchronized {
          val stored = items.get(key(r.item))
          val ok = Condition.eval(cond, names, values, stored.map(_.asScala.toMap))
          if !ok then
            val b =
              ConditionalCheckFailedException.builder().message("The conditional request failed")
            throw stored.fold(b)(b.item).build()
          items(key(r.item)) = r.item
          PutItemResponse.builder().build()
        }
    }

  /** The DynamoDB condition-expression subset the live adapters use: OR, AND, parentheses,
    * attribute_exists, attribute_not_exists, and = <> < <= > >= on names and values. Numbers
    * compare as numbers and strings as strings; a comparison with a missing attribute is false.
    * Every #name and :value must be defined, and every defined one used (DynamoDB's
    * ValidationException otherwise).
    */
  private object Condition:
    private val Token =
      "attribute_not_exists|attribute_exists|<=|>=|<>|[()=<>]|[#:][A-Za-z0-9_]+|AND|OR|\\S+".r
    def eval(
        expr: String,
        names: Map[String, String],
        values: Map[String, Sdk],
        item: Option[Map[String, Sdk]]
    ): Boolean =
      val tokens = Token.findAllIn(expr).toVector
      val usedNames = tokens.filter(_.startsWith("#")).toSet
      val usedValues = tokens.filter(_.startsWith(":")).toSet
      assert(usedNames == names.keySet, s"names used $usedNames, defined ${names.keySet}")
      assert(usedValues == values.keySet, s"values used $usedValues, defined ${values.keySet}")
      var i = 0
      def peek = tokens.lift(i)
      def next() = { val t = tokens(i); i += 1; t }
      def expect(t: String) = assert(next() == t, s"expected $t in $expr")
      def attr(t: String): Option[Sdk] =
        if t.startsWith("#") then item.flatMap(_.get(names(t)))
        else if t.startsWith(":") then Some(values(t))
        else fail(s"unsupported operand $t in $expr")
      def cmp(a: Sdk, b: Sdk): Int =
        if a.n != null && b.n != null then BigDecimal(a.n).compare(BigDecimal(b.n))
        else if a.s != null && b.s != null then a.s.compareTo(b.s)
        else fail(s"incomparable operands in $expr")
      def primary(): Boolean = peek match
        case Some("(") =>
          next(); val v = or(); expect(")"); v
        case Some(f @ ("attribute_exists" | "attribute_not_exists")) =>
          next(); expect("("); val n = next(); expect(")")
          val present = attr(n).isDefined
          if f == "attribute_exists" then present else !present
        case Some(_) =>
          val l = attr(next())
          val op = next()
          val r = attr(next())
          (l, r) match
            case (Some(a), Some(b)) =>
              val c = cmp(a, b)
              op match
                case "="  => c == 0
                case "<>" => c != 0
                case "<"  => c < 0
                case "<=" => c <= 0
                case ">"  => c > 0
                case ">=" => c >= 0
                case o    => fail(s"unsupported operator $o in $expr")
            case _ => false
        case None => fail(s"truncated condition $expr")
      def and(): Boolean =
        var v = primary()
        while peek.contains("AND") do { next(); val r = primary(); v = v && r }
        v
      def or(): Boolean =
        var v = and()
        while peek.contains("OR") do { next(); val r = and(); v = v || r }
        v
      val result = or()
      assert(i == tokens.size, s"trailing tokens in $expr")
      result

  test(
    "DynamoDB proxy self-check: the condition evaluator decides by the expression, not by names"
  ) {
    def n(x: Long) = Sdk.builder().n(x.toString).build()
    def str(x: String) = Sdk.builder().s(x).build()
    val absent = "attribute_not_exists(#pk) OR #e <= :now"
    val names = Map("#pk" -> "recordKey", "#e" -> "expiresAt")
    val live = Map("recordKey" -> str("k"), "expiresAt" -> n(200), "state" -> str("QUEUED"))
    assert(Condition.eval(absent, names, Map(":now" -> n(100)), None))
    assert(!Condition.eval(absent, names, Map(":now" -> n(100)), Some(live)))
    assert(Condition.eval(absent, names, Map(":now" -> n(200)), Some(live)))
    // The same placeholders under a wrong expression give the other answer.
    assert(
      Condition.eval(
        "attribute_not_exists(#pk) OR #e >= :now",
        names,
        Map(":now" -> n(100)),
        Some(live)
      )
    )
    assert(
      !Condition.eval(
        "attribute_exists(#pk) AND #e <= :now",
        names,
        Map(":now" -> n(100)),
        Some(live)
      )
    )
    val st = Map("#s" -> "state")
    assert(Condition.eval("#s = :from", st, Map(":from" -> str("QUEUED")), Some(live)))
    assert(!Condition.eval("#s = :from", st, Map(":from" -> str("RUNNING")), Some(live)))
    assert(!Condition.eval("#s = :from", st, Map(":from" -> str("QUEUED")), None))
    assert(Condition.eval("#s <> :from", st, Map(":from" -> str("RUNNING")), Some(live)))
    // An unused or undefined placeholder is a validation error, as in DynamoDB.
    intercept[org.scalatest.exceptions.TestFailedException](
      Condition.eval("#s = :from", st ++ names, Map(":from" -> str("QUEUED")), Some(live))
    )
  }

  test(
    "DynamoDB: an expired claim can be taken over; an unexpired one cannot (TTL by the request's :now)"
  ) {
    var now = Instant.parse("2026-11-12T00:00:00Z")
    val table = DynamoServiceStateTable(dynamo(), "adv-", () => now, RetryPolicy(3, 1, 1), _ => ())
    val idem = IdempotencyStore(table, 1, () => now)
    val qid = "qry-" + "c" * 32
    val h1 = "sha256:" + "1" * 64
    val h2 = "sha256:" + "2" * 64
    assert(idem.claim("ttl-key", h1, qid).map(_.outcome) == Right(LoadOutcome.Inserted))
    now = now.plusSeconds(3599)
    assert(idem.claim("ttl-key", h2, qid).map(_.outcome) == Right(LoadOutcome.Conflict))
    now = now.plusSeconds(2) // past the one-hour TTL
    assert(idem.claim("ttl-key", h2, qid).map(_.outcome) == Right(LoadOutcome.Inserted))
    // The new claim is the one now stored: the first body is a conflict, the second unchanged.
    assert(idem.claim("ttl-key", h1, qid).map(_.outcome) == Right(LoadOutcome.Conflict))
    assert(idem.claim("ttl-key", h2, qid).map(_.outcome) == Right(LoadOutcome.Unchanged))
  }

  test("DynamoDB: 16 concurrent job claims through the live adapter move the job exactly once") {
    val table =
      DynamoServiceStateTable(dynamo(), "adv-", () => Instant.now, RetryPolicy(3, 1, 1), _ => ())
    val jobs = JobStore(table, 168, () => Instant.now)
    val qid = "qry-" + "b" * 32
    assert(jobs.create(qid, """{"question":"q"}""").map(_.outcome) == Right(LoadOutcome.Inserted))
    val jid = StableIds.jobId(qid)
    val start = java.util.concurrent.CountDownLatch(1)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
    try
      val fs = (1 to 16).map(_ =>
        pool.submit(() =>
          start.await()
          jobs.transition(jid, JobState.QUEUED, JobState.RUNNING)
        )
      )
      start.countDown()
      val results = fs.map(_.get(30, java.util.concurrent.TimeUnit.SECONDS)) // hang guard only
      assert(results.count(_.exists(_.isInstanceOf[JobTransition.Moved])) == 1, results)
      assert(
        results.count {
          case Right(JobTransition.Refused(Some(j))) => j.state == JobState.RUNNING
          case _                                     => false
        } == 15,
        results
      )
      // A transition from a state the job is not in is refused with the stored job.
      jobs.transition(jid, JobState.QUEUED, JobState.FAILED) match
        case Right(JobTransition.Refused(Some(j))) => assert(j.state == JobState.RUNNING)
        case other                                 => fail(s"$other")
    finally pool.shutdownNow()
  }

  test(
    "DynamoDB: racing job claims through the live adapter: one Moved, the rest Refused with the stored job"
  ) {
    val table =
      DynamoServiceStateTable(dynamo(), "adv-", () => Instant.now, RetryPolicy(3, 1, 1), _ => ())
    val jobs = JobStore(table, 168, () => Instant.now)
    val qid = "qry-" + "d" * 32
    assert(jobs.create(qid, """{"question":"q"}""").map(_.outcome) == Right(LoadOutcome.Inserted))
    assert(jobs.create(qid, """{"question":"q"}""").map(_.outcome) == Right(LoadOutcome.Unchanged))
    assert(
      jobs.create(qid, """{"question":"other"}""").map(_.outcome) == Right(LoadOutcome.Conflict)
    )
    val jid = StableIds.jobId(qid)
    assert(
      jobs
        .transition(jid, JobState.QUEUED, JobState.RUNNING)
        .exists(_.isInstanceOf[JobTransition.Moved])
    )
    jobs.transition(jid, JobState.QUEUED, JobState.RUNNING) match
      case Right(JobTransition.Refused(Some(j))) =>
        assert(j.state == JobState.RUNNING && j.startedAt.isDefined)
      case other => fail(s"second claim: $other")
    val idem = IdempotencyStore(table, 24, () => Instant.now)
    val h = "sha256:" + "e" * 64
    assert(idem.claim("k-1", h, qid).map(_.outcome) == Right(LoadOutcome.Inserted))
    assert(
      idem.claim("k-1", "sha256:" + "f" * 64, qid).map(_.outcome) == Right(LoadOutcome.Conflict)
    )
  }

  test(
    "DynamoDB: a throttled conditional write is one attempt and a transient failure, never a retry"
  ) {
    val calls = AtomicInteger()
    val table = DynamoServiceStateTable(
      dynamo(AtomicInteger(5), calls),
      "adv-",
      () => Instant.now,
      RetryPolicy(3, 1, 1),
      _ => ()
    )
    val jobs = JobStore(table, 168, () => Instant.now)
    intercept[TransientStoreException](jobs.create("qry-" + "e" * 32, """{"question":"q"}"""))
    assert(calls.get == 1, s"${calls.get} put attempts")
  }

  // ---- S3 Vectors ----

  private val chunk = StoreWorld.profileChunk
  private def record(text: String, model: String = "", run: String = StoreWorld.renderRun) =
    val r = VectorRecord.fromJson(StoreWorld.vectorLine(chunk))
    val m = ujson.Obj.from(r.metadata.value)
    m("textHash") = StableIds.textHash(text)
    if model.nonEmpty then m("embeddingModelVersion") = model
    m("runId") = run
    VectorRecord(r.key, HashingEmbedder().embed(text), m)

  test(
    "S3 Vectors: a throttled put through the live adapter is retried by the loader and then written"
  ) {
    val puts = AtomicInteger()
    val client = proxy(classOf[S3VectorsClient]) {
      case ("getVectors", _) => GetVectorsResponse.builder().vectors(java.util.List.of()).build()
      case ("putVectors", _) =>
        if puts.incrementAndGet() <= 2 then throw throttle("TooManyRequestsException", 429)
        PutVectorsResponse.builder().build()
    }
    val store = S3VectorStore(client, "adv-vectors")
    val dir = Files.createTempDirectory("adv-ledger-")
    try
      val slept = scala.collection.mutable.ArrayBuffer.empty[Long]
      val s = VectorLoader.load(
        store.loader,
        chunk.index,
        Vector(record(chunk.text)),
        WriteMode.CreateOnly,
        10,
        RetryPolicy(3, 100, 400),
        slept += _,
        FileRunLedger(dir),
        StoreWorld.renderRun,
        1,
        _ => (),
        () => "2026-11-12T00:00:00Z"
      )
      assert(s.written == 1 && s.retries == 2 && puts.get == 3)
      assert(slept.toVector == Vector(100L, 200L), slept)
      puts.set(-100) // every later put throttles
      val failing = proxy(classOf[S3VectorsClient]) {
        case ("getVectors", _) => GetVectorsResponse.builder().vectors(java.util.List.of()).build()
        case ("putVectors", _) =>
          puts.incrementAndGet(); throw throttle("ServiceUnavailableException", 503)
      }
      puts.set(0)
      intercept[TransientStoreException](
        VectorLoader.load(
          S3VectorStore(failing, "adv-vectors").loader,
          chunk.index,
          Vector(record(chunk.text)),
          WriteMode.CreateOnly,
          10,
          RetryPolicy(3, 1, 1),
          _ => (),
          FileRunLedger(dir),
          StoreWorld.renderRun,
          2,
          _ => (),
          () => "2026-11-12T00:00:00Z"
        )
      )
      assert(puts.get == 3, s"${puts.get} put attempts")
    finally StoreWorld.deleteTree(dir)
  }

  test("S3 Vectors: a throttled query is a transient failure, not an empty result") {
    val client = proxy(classOf[S3VectorsClient]) { case ("queryVectors", _) =>
      throw throttle(status = 429)
    }
    val q = HashingEmbedder().embed("question")
    intercept[TransientStoreException](
      S3VectorStore(client, "adv-vectors").reader.query(chunk.index, q, 5, None)
    )
  }

  test("duplicate vector keys: in one batch both are rejected; across the input the first wins") {
    val dir = Files.createTempDirectory("adv-vec-")
    try
      val store = FileVectorStore(dir)
      val a = record(chunk.text)
      val b = record(chunk.text + " (edited)")
      val batch = store.loader.put(chunk.index, Vector(a, b), WriteMode.CreateOnly).toOption.get
      assert(
        batch.written.isEmpty && batch.rejected.map(_._2).toSet == Set("DUPLICATE_KEY_IN_BATCH")
      )
      assert(store.size(chunk.index) == 0)
      val rejected = scala.collection.mutable.ArrayBuffer.empty[ujson.Obj]
      val s = VectorLoader.load(
        store.loader,
        chunk.index,
        Vector(a, a, b),
        WriteMode.CreateOnly,
        10,
        RetryPolicy(3, 1, 1),
        _ => (),
        FileRunLedger(dir.resolve("ledger")),
        StoreWorld.renderRun,
        1,
        rejected += _,
        () => "2026-11-12T00:00:00Z"
      )
      assert((s.written, s.skipped, s.rejected) == (1, 1, 1))
      assert(rejected.map(_("reason").str) == Vector("DUPLICATE_KEY"))
      assert(!rejected.exists(_.value.contains("data")), "a rejected record leaked its vector data")
      // A later load of a different text under the key is a conflict, never a silent overwrite.
      val again = store.loader.put(chunk.index, Vector(b), WriteMode.CreateOnly).toOption.get
      assert(again.written.isEmpty && again.rejected.head._2.startsWith("KEY_CONFLICT"))
      // A supersede that changes the text without a new model or run is refused.
      val same = store.loader.put(chunk.index, Vector(b), WriteMode.Supersede).toOption.get
      assert(same.rejected.head._2.startsWith("SUPERSEDE_WITHOUT_CHANGE"))
    finally StoreWorld.deleteTree(dir)
  }

  test(
    "stale model versions: a vector of another embedding model is never returned to the runtime"
  ) {
    StoreWorld.withWorld() { w =>
      // A stale vector that matches the question perfectly and carries injection text.
      val q = StoreWorld.Question
      val staleText = s"Stale profile. ${StoreWorld.Injection}"
      val key = "chunk-" + StableIds.hash("stale", "ada")
      val ref =
        s"semantic-context/text/snapshot=${StoreWorld.snapshot}/run=${StoreWorld.renderRun}/researcher-profile-v1/$key.txt"
      Files.writeString(w.root.resolve("s3").resolve(ref), staleText)
      val meta = ujson.Obj(
        "vectorKey" -> key,
        "entityIds" -> ujson.Arr(StoreWorld.ada),
        "window" -> "all-years",
        "documentType" -> "researcher-profile",
        "sourceDataset" -> "DERIVED",
        "embeddingModelVersion" -> "fake:amazon.titan-embed-text-v1#rendering-v0",
        "textHash" -> StableIds.textHash(staleText),
        "runId" -> StoreWorld.renderRun,
        "textRef" -> ref
      )
      val line = VectorRecord.toJson(VectorRecord(key, HashingEmbedder().embed(q), meta)).render()
      val f = w.root.resolve("vectors").resolve("researcher-profile-v1.jsonl")
      Files.writeString(f, Files.readString(f) + line + "\n")
      val r = w.ask(q).fold(e => fail(s"$e"), identity)
      val keys = r.bundle.get.semanticEvidence.flatMap(_.chunkId)
      assert(!keys.contains(key), s"the stale-model vector was used: $keys")
      assert(keys.contains(StoreWorld.profileChunk.key))
      assert(
        r.bundle.get.semanticEvidence
          .forall(_.facts.get("embeddingModelVersion").exists(_.endsWith(StoreWorld.modelVersion)))
      )
    }
  }
