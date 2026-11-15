package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.storage.live.*
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  DefaultCredentialsProvider,
  ProfileCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.awscore.exception.{AwsErrorDetails, AwsServiceException}
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3vectors.model.{GetOutputVector, QueryOutputVector}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** Live adapters: request building, response parsing, and credentials wiring. Nothing calls AWS
  * (ruling 26). The one test that builds SDK clients gives them an empty in-memory profile file and
  * an injected credentials factory, so no profile or credentials file is read.
  */
class LiveRequestSpec extends AnyFunSuite:
  import Fixtures.*

  test("S3: write-once put carries If-None-Match and a SHA-256 checksum") {
    val bytes = "text".getBytes(UTF_8)
    val r = S3Requests.putNew("b", "semantic-context/x", bytes)
    assert(r.bucket == "b" && r.ifNoneMatch == "*" && r.contentLength == 4L)
    assert(
      r.checksumSHA256 == Base64.getEncoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes)
      )
    )
    def err(code: Int) = S3Exception.builder().statusCode(code).build().asInstanceOf[S3Exception]
    assert(S3Requests.outcomeOf(err(412)).contains("EXISTS"))
    assert(S3Requests.outcomeOf(err(409)).contains("RETRY"))
    assert(S3Requests.outcomeOf(err(403)).isEmpty)
  }

  test("DynamoDB: attribute codec round trip and conditional put requests") {
    import AttributeValue.*
    val item: Item = rankingItem(Seq(ra -> 5L, rb -> 3L)) +
      ("x" -> M(Map("b" -> Bool(true), "n" -> Null)))
    assert(DynamoCodec.fromSdkItem(DynamoCodec.toSdkItem(item)) == Right(item))
    val spec = DynamoContract.ResearcherMetricCache
    val put = DynamoRequests.putNew("p-ResearcherMetricCache", spec, item)
    assert(put.tableName == "p-ResearcherMetricCache")
    assert(put.conditionExpression == "attribute_not_exists(#pk)")
    assert(put.expressionAttributeNames.asScala == Map("#pk" -> "researcherId"))
    assert(put.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD)
    val rep = DynamoRequests.replace("t", item, runId)
    assert(rep.conditionExpression == "#r = :expected")
    assert(rep.expressionAttributeValues.get(":expected").s == runId)
    val q = DynamoRequests.query("t", spec, ra, "WINDOW#", 10)
    assert(q.keyConditionExpression == "#pk = :pk AND begins_with(#sk, :p)" && q.limit == 10)
    assert(DynamoRequests.query("t", spec, ra, "", 10).keyConditionExpression == "#pk = :pk")
    assert(DynamoRequests.get("t", spec, ra, "WINDOW#all-years").consistentRead)
  }

  test("Neptune: loader request and status parsing") {
    val prefix = S3Layout.neptuneRoot(snapshot, runId) + "/"
    val r = NeptuneLoadRequests.start("b", prefix, "arn:aws:iam::000000000000:role/x", "us-east-2")
    assert(r.source == s"s3://b/$prefix" && r.formatAsString == "opencypher")
    assert(r.userProvidedEdgeIds && !r.updateSingleCardinalityProperties && r.failOnError)
    assert(r.s3BucketRegionAsString == "us-east-2" && r.queueRequest)
    assertThrows[IllegalArgumentException](NeptuneLoadRequests.start("b", "raw/x/", "a", "r"))
    assert(NeptuneLoadRequests.loadIdOf(Map("loadId" -> "abc")) == Right("abc"))
    assert(NeptuneLoadRequests.loadIdOf(Map.empty).isLeft)
    val payload = ujson.read(
      """{"feedCount":[{"LOAD_COMPLETED":12}],"overallStatus":{"fullUri":"s3://b/graph/",
        |"runNumber":1,"retryNumber":0,"status":"LOAD_COMPLETED","totalTimeSpent":8,
        |"startTime":1,"totalRecords":1200,"totalDuplicates":3,"parsingErrors":0,
        |"datatypeMismatchErrors":0,"insertErrors":0},
        |"errors":{"startIndex":0,"endIndex":0,"loadId":"abc","errorLogs":[]}}""".stripMargin
    )
    val s = Documents.fromDocument(Documents.toDocument(payload))
    val st = NeptuneLoadRequests.parseStatus(s).toOption.get
    assert(st.status == "LOAD_COMPLETED" && st.totalRecords == 1200 && st.totalDuplicates == 3)
    assert(st.finished && st.succeeded)
    assert(NeptuneLoadRequests.parseStatus(ujson.Obj("x" -> 1)).isLeft)
  }

  test("S3 Vectors: put, get, and query requests; filter syntax; response parsing") {
    val c = candidate("researcher-profile-v1", "researcher-profile", Vector(ra), "Profile of A.")
    val rec = vectorRecord(c, unit(0))
    val put = VectorRequests.put("vb", "researcher-profile-v1", Seq(rec))
    val v = put.vectors.get(0)
    assert(put.vectorBucketName == "vb" && v.key == rec.key && v.data.float32.size == 1024)
    assert(Documents.fromDocument(v.metadata) == rec.metadata)
    val get = VectorRequests.get("vb", "researcher-profile-v1", Seq(rec.key))
    assert(!get.returnData && get.returnMetadata)
    assertThrows[IllegalArgumentException](VectorRequests.get("vb", "x", Seq.fill(101)("k")))
    val f = VectorFilter.And(
      Seq(
        VectorFilter.Eq("window", ujson.Str("all-years")),
        VectorFilter.Nin("entityIds", Seq(ujson.Str(ra)))
      )
    )
    assert(
      VectorRequests.filterJson(f) == ujson.read(
        s"""{"$$and":[{"window":{"$$eq":"all-years"}},{"entityIds":{"$$nin":["$ra"]}}]}"""
      )
    )
    val q = VectorRequests.query("vb", "researcher-profile-v1", unit(0), 5, Some(f))
    assert(
      q.topK == 5 && q.returnDistance && Documents.fromDocument(q.filter) == VectorRequests
        .filterJson(f)
    )
    val got =
      GetOutputVector.builder().key(rec.key).metadata(Documents.toDocument(rec.metadata)).build()
    val parsed = VectorRequests.parseGet(got)
    assert(parsed.textHash == rec.textHash && parsed.modelVersion == rec.modelVersion)
    assert(VectorWriteRule.decide(Some(parsed), rec, WriteMode.CreateOnly) == Right(false))
    val hit = QueryOutputVector
      .builder()
      .key(rec.key)
      .distance(0.25f)
      .metadata(Documents.toDocument(rec.metadata))
      .build()
    assert(VectorRequests.parseHit(hit).distance == 0.25)
  }

  test("Bedrock: Titan V2 invoke request; transient error classification") {
    val r = BedrockRequests.invoke(TitanV2.ModelId, "hello")
    assert(r.modelId == TitanV2.ModelId && r.contentType == "application/json")
    assert(r.body.asUtf8String == TitanV2.requestBody("hello"))
    def svc(code: Int) = AwsServiceException.builder().statusCode(code).message("x").build()
    assert(LiveAws.isTransient(svc(503)) && !LiveAws.isTransient(svc(400)))
    assert(LiveAws.isTransient(SdkClientException.create("timeout")))
    assertThrows[TransientStoreException](LiveAws.guarded(throw svc(500)))
    assertThrows[AwsServiceException](LiveAws.guarded(throw svc(403)))
  }

  // ---- Credential refresh (ruling 35) ---------------------------------------------------------

  private def expired(code: String): AwsServiceException = AwsServiceException
    .builder()
    .statusCode(403)
    .awsErrorDetails(
      AwsErrorDetails
        .builder()
        .errorCode(code)
        .errorMessage("The security token included in the request is expired")
        .build()
    )
    .build()

  /** A delegate factory that counts how often it builds; each build has its own access key. */
  private final class Factory:
    var builds = 0
    def apply(): AwsCredentialsProvider =
      builds += 1
      StaticCredentialsProvider.create(AwsBasicCredentials.create(s"AKIDTEST$builds", "test"))

  test("refreshing credentials: built lazily, re-resolved per call, rebuilt after refresh") {
    val f = Factory()
    val c = RefreshingCredentials(() => f())
    assert(f.builds == 0)
    assert(c.resolveCredentials().accessKeyId == "AKIDTEST1")
    assert(c.resolveCredentials().accessKeyId == "AKIDTEST1" && f.builds == 1)
    c.refresh()
    assert(f.builds == 1) // dropped, not rebuilt until the next request
    assert(c.resolveCredentials().accessKeyId == "AKIDTEST2" && f.builds == 2)
  }

  test("expired token: refresh and retry once; a second expiry or another 403 is not retried") {
    assert(LiveAws.isExpiredToken(expired("ExpiredTokenException")))
    assert(LiveAws.isExpiredToken(expired("ExpiredToken")))
    assert(
      LiveAws.isExpiredToken(
        AwsServiceException
          .builder()
          .statusCode(403)
          .message("The security token included in the request is expired")
          .build()
      )
    )
    assert(
      !LiveAws.isExpiredToken(expired("AccessDeniedException").toBuilder.message("no").build())
    )
    var refreshes = 0
    var calls = 0
    def op(failures: Int, e: => AwsServiceException) =
      calls += 1
      if calls <= failures then throw e else "ok"
    assert(
      LiveAws.retryOnExpiredToken(() => refreshes += 1)(op(1, expired("ExpiredToken"))) == "ok"
    )
    assert(refreshes == 1 && calls == 2)
    refreshes = 0; calls = 0
    assertThrows[AwsServiceException](
      LiveAws.retryOnExpiredToken(() => refreshes += 1)(op(2, expired("ExpiredTokenException")))
    )
    assert(refreshes == 1 && calls == 2)
    refreshes = 0; calls = 0
    val denied = AwsServiceException.builder().statusCode(403).message("denied").build()
    assertThrows[AwsServiceException](
      LiveAws.retryOnExpiredToken(() => refreshes += 1)(op(1, denied))
    )
    assert(refreshes == 0 && calls == 1)
  }

  test("guarded refreshes the profile's credentials on an expired token and retries the call") {
    val f = Factory()
    val t = LiveTarget("unit-test-guarded", "us-east-2")
    val c = LiveAws.credentials(t, Some(() => f()))
    assert(LiveAws.credentials(t) eq c) // one provider per profile, shared by every client
    assert(c.resolveCredentials().accessKeyId == "AKIDTEST1")
    var calls = 0
    val r = LiveAws.guarded {
      calls += 1
      if calls == 1 then throw expired("ExpiredTokenException") else c.resolveCredentials()
    }
    assert(calls == 2 && r.accessKeyId == "AKIDTEST2" && f.builds == 2)
  }

  test("no profile (ECS) selects the SDK default chain; a profile keeps the named profile") {
    // Phase 10 ruling 18. Both providers are lazy: constructing one reads no file, calls nothing.
    val ecs = LiveTarget(None, "us-east-2")
    val named = LiveTarget("unit-test-select", "us-east-2")
    assert(LiveAws.provider(ecs).isInstanceOf[DefaultCredentialsProvider])
    assert(LiveAws.provider(named).isInstanceOf[ProfileCredentialsProvider])
    assert(named.profileName.contains("unit-test-select") && named.region == "us-east-2")
    assert(ecs.profile == LiveTarget.DefaultChainLabel && ecs.region == "us-east-2")
    // One shared refreshing provider for the chain, never the entry of a profile named "default".
    val c = LiveAws.credentials(ecs)
    assert(LiveAws.credentials(ecs) eq c)
    assert(!(LiveAws.credentials(LiveTarget("default", "us-east-2")) eq c))
  }

  test("every live client is built with the profile's refreshing credentials provider") {
    val f = Factory()
    val t = LiveTarget("unit-test-wiring", "us-east-2")
    val c = LiveAws.credentials(t, Some(() => f()))
    val emptyProfiles = ProfileFile
      .builder()
      .content(java.io.ByteArrayInputStream(Array.emptyByteArray))
      .`type`(ProfileFile.Type.CONFIGURATION)
      .build()
    val o = LiveAws.overrides.toBuilder
      .defaultProfileFile(emptyProfiles)
      .defaultProfileName("unit-test-wiring")
      .build()
    val clients = Vector(
      LiveAws.bedrock(t, o),
      LiveAws.s3(t, o),
      LiveAws.dynamo(t, o),
      LiveAws.vectors(t, o),
      LiveAws.neptune(t, "neptune.invalid", 8182, o),
      LiveAws.sqs(t, o)
    )
    try
      clients.foreach { k =>
        val p = k.serviceClientConfiguration.credentialsProvider
        assert(p eq c, k.serviceName)
      }
      assert(f.builds == 0) // building a client resolves nothing
    finally clients.foreach(_.close())
  }

  // ---- Phase 7 review 2 minors (N1, N2, N3, N5) -----------------------------------------------

  test("N1: the expired-token retry of a Titan call passes the caller's throttle first") {
    import software.amazon.awssdk.core.SdkBytes
    import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
    import software.amazon.awssdk.services.bedrockruntime.model.{
      InvokeModelRequest,
      InvokeModelResponse
    }
    val body = ujson.Obj("embedding" -> ujson.Arr.from(Seq.fill(1024)(ujson.Num(0.03125)))).render()
    var calls = 0
    val client = new BedrockRuntimeClient:
      def serviceName(): String = "bedrock-runtime"
      def close(): Unit = ()
      override def invokeModel(r: InvokeModelRequest): InvokeModelResponse =
        calls += 1
        if calls % 2 == 1 then throw expired("ExpiredTokenException")
        InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(body)).build()
    var paced = 0
    assert(
      BedrockTitanEmbedder(client, TitanV2.ModelId, () => paced += 1).embed("x").length == 1024
    )
    assert(calls == 2 && paced == 1)
    // With the runner's throttle: the first attempt and the retry start one interval apart.
    var now = 0L
    val sleeps = scala.collection.mutable.ArrayBuffer.empty[Long]
    val throttle = Throttle(50, () => now, ms => { sleeps += ms; now += ms })
    throttle.acquire() // the runner's acquire before embed
    BedrockTitanEmbedder(client, TitanV2.ModelId, () => throttle.acquire()).embed("x")
    assert(calls == 4 && sleeps.toVector == Vector(throttle.intervalMs))
  }

  test("N2: a 403 on HeadObject gets one refresh and retry; a second 403 propagates") {
    import software.amazon.awssdk.services.s3.S3Client
    import software.amazon.awssdk.services.s3.model.{HeadObjectRequest, HeadObjectResponse}
    val digest = MessageDigest.getInstance("SHA-256").digest("text".getBytes(UTF_8))
    var calls = 0
    var failures = 1
    val client = new S3Client:
      def serviceName(): String = "s3"
      def close(): Unit = ()
      override def headObject(r: HeadObjectRequest): HeadObjectResponse =
        calls += 1
        if failures > 0 then
          failures -= 1
          throw S3Exception.builder().statusCode(403).build() // HEAD errors have no body or code
        HeadObjectResponse
          .builder()
          .checksumSHA256(Base64.getEncoder.encodeToString(digest))
          .build()
    val store = S3ObjectStore(client, "b")
    assert(store.sha256("k").contains(Hashing.sha256("text")) && calls == 2)
    calls = 0
    failures = 2
    assertThrows[S3Exception](store.sha256("k"))
    assert(calls == 2)
    // Only HEAD widens the rule: elsewhere a bare 403 is still not an expiry.
    assert(
      !LiveAws.isExpiredToken(
        S3Exception.builder().statusCode(403).build().asInstanceOf[S3Exception]
      )
    )
  }

  test("N3: an expired token is detected by its error code alone") {
    def coded(code: String) = AwsServiceException
      .builder()
      .statusCode(400)
      .message("The provided token has expired.") // the S3 text: not the JSON-service phrase
      .awsErrorDetails(
        AwsErrorDetails
          .builder()
          .errorCode(code)
          .errorMessage("The provided token has expired.")
          .build()
      )
      .build()
    assert(!coded("ExpiredToken").getMessage.contains("security token included"))
    assert(LiveAws.isExpiredToken(coded("ExpiredToken")))
    assert(LiveAws.isExpiredToken(coded("ExpiredTokenException")))
    assert(!LiveAws.isExpiredToken(coded("ExpiredTokn")))
    assert(!LiveAws.isExpiredToken(coded("AccessDenied")))
  }

  test("N5: requests that fail on one expiry rerun credential_process once") {
    val f = Factory()
    val c = RefreshingCredentials(() => f())
    c.resolveCredentials()
    val seen = c.generation
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    val start = java.util.concurrent.CountDownLatch(1)
    try
      val tasks = (1 to 16).map { _ =>
        val t: Runnable = () =>
          start.await()
          c.refreshIfCurrent(seen)
        pool.submit(t)
      }
      start.countDown()
      tasks.foreach(_.get())
    finally pool.shutdown()
    assert(c.generation == seen + 1)
    c.resolveCredentials()
    assert(f.builds == 2)
    // guarded: a request that started before another request's refresh does not refresh again.
    val g = Factory()
    val p = LiveAws.credentials(LiveTarget("unit-test-n5", "us-east-2"), Some(() => g()))
    p.resolveCredentials()
    val before = p.generation
    var calls = 0
    val r = LiveAws.guarded {
      calls += 1
      if calls == 1 then
        p.refresh() // another request saw the expiry first
        throw expired("ExpiredToken")
      p.resolveCredentials()
    }
    assert(
      calls == 2 && p.generation == before + 1 && g.builds == 2 && r.accessKeyId == "AKIDTEST2"
    )
  }
