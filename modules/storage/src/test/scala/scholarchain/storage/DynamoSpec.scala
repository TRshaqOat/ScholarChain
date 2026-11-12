package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class DynamoSpec extends AnyFunSuite:
  import AttributeValue.*
  import Fixtures.*

  private def s(v: String) = S(v)

  private def metricItem(researcherId: String, run: String = runId, coreness: Long = 3): Item = Map(
    "researcherId" -> s(researcherId),
    "metricKey" -> s("WINDOW#all-years"),
    "windowStartYear" -> num(2000L),
    "windowEndYear" -> num(2026L),
    "publicationCount" -> num(3L),
    "distinctCoauthorCount" -> num(10L),
    "totalCoauthorOccurrences" -> num(14L),
    "averageCoauthorsPerPaper" -> num(4.666666666666667),
    "maximumCoauthorsOnOnePaper" -> num(8L),
    "maximumTeamWorkIds" -> L(Vector(s(w1))),
    "weightedDegree" -> num(14.0),
    "componentId" -> s("comp-" + "a" * 32),
    "componentSize" -> num(141367L),
    "coreness" -> num(coreness),
    "graphDegree" -> num(10L),
    "communityId" -> s(community),
    "participationCoefficient" -> num(0.18),
    "runId" -> s(run),
    "snapshot" -> s(snapshot),
    "schemaVersion" -> s("researcher-metrics-window-v1"),
    "policyVersion" -> s(policy)
  )

  private def sessionItem(expiresAt: Long): Item = Map(
    "sessionId" -> s("session-1"),
    "turnKey" -> s("2026-11-05T10:00:00Z#q-1"),
    "queryId" -> s("q-1"),
    "createdAt" -> s("2026-11-05T10:00:00Z"),
    "expiresAt" -> num(expiresAt),
    "schemaVersion" -> s("rag-query-session-v1")
  )

  private var now = 1000L
  private def store() = InMemoryDynamo(windows, () => now)

  test("DynamoDB JSON item lines round trip") {
    val item = metricItem(ra) + ("nested" -> M(Map("b" -> Bool(true), "n" -> Null)))
    assert(DynamoItemFile.parseLine(DynamoItemFile.renderLine(item)) == Right(item))
    assert(DynamoItemFile.parseLine("""{"Item":{"a":{"X":"1"}}}""").isLeft)
    assert(DynamoItemFile.parseLine("""{"Item":{"a":{"N":"one"}}}""").isLeft)
    assert(DynamoItemFile.parseLine("not json").isLeft)
  }

  test("conditional writes: insert, identical reload, conflict, explicit supersede") {
    val db = store()
    val t = TableName.ResearcherMetricCache
    assert(db.loader.putNew(t, metricItem(ra)) == Right(LoadOutcome.Inserted))
    assert(db.loader.putNew(t, metricItem(ra)) == Right(LoadOutcome.Unchanged))
    assert(db.loader.putNew(t, metricItem(ra, coreness = 4)) == Right(LoadOutcome.Conflict))
    assert(db.reader.get(t, ra, "WINDOW#all-years").flatMap(_.get("coreness")).contains(num(3L)))
    assert(
      db.loader.replace(t, metricItem(ra, otherRunId, 4), expectedRunId = otherRunId) == Right(
        PutOutcome.ConditionFailed
      )
    )
    assert(
      db.loader.replace(t, metricItem(ra, otherRunId, 4), expectedRunId = runId) == Right(
        PutOutcome.Written
      )
    )
    assert(db.reader.query(t, ra, "WINDOW#", 10).map(_("coreness")) == Vector(num(4L)))
    assert(db.itemCount(t) == 1)
  }

  test("append-only entries and port fences") {
    val db = store()
    val entry: Item = Map(
      "runId" -> s(runId),
      "entryKey" -> s("step#1"),
      "entryType" -> s("STEP"),
      "recordedAt" -> s("2026-11-05T10:00:00Z"),
      "schemaVersion" -> s("run-ledger-v1")
    )
    assert(db.loader.putNew(TableName.RunLedger, entry) == Right(LoadOutcome.Inserted))
    assert(db.loader.replace(TableName.RunLedger, entry, runId).isLeft)
    assert(db.loader.putNew(TableName.QuerySession, sessionItem(2000)).isLeft)
    assert(db.runtimeWriter.create(TableName.ResearcherMetricCache, metricItem(ra)).isLeft)
    assert(
      db.runtimeWriter.create(TableName.QuerySession, sessionItem(2000)) == Right(
        LoadOutcome.Inserted
      )
    )
  }

  test("TTL: only on QuerySession and EvidenceBundle, and expired items are not read") {
    val db = store()
    assert(db.runtimeWriter.create(TableName.QuerySession, sessionItem(1500)).isRight)
    assert(db.reader.query(TableName.QuerySession, "session-1", "", 10).size == 1)
    now = 1500
    assert(db.reader.get(TableName.QuerySession, "session-1", "2026-11-05T10:00:00Z#q-1").isEmpty)
    now = 1000
    assert(db.runtimeWriter.create(TableName.QuerySession, sessionItem(1500) - "expiresAt").isLeft)
    val withTtl = metricItem(rb) + ("expiresAt" -> num(5000L))
    assert(
      db.loader
        .putNew(TableName.ResearcherMetricCache, withTtl)
        .left
        .toOption
        .get
        .exists(_.contains("TTL"))
    )
  }

  test("missing provenance, unknown windows, long lists, and oversize items are rejected") {
    val db = store()
    val t = TableName.ResearcherMetricCache
    assert(db.loader.putNew(t, metricItem(ra) - "runId").isLeft)
    assert(db.loader.putNew(t, metricItem(ra) + ("metricKey" -> s("WINDOW#1999"))).isLeft)
    val ids = L(Vector.tabulate(51)(i => s(s"dblp:conf/test/X$i")))
    assert(db.loader.putNew(t, metricItem(ra) + ("maximumTeamWorkIds" -> ids)).isLeft)
    assert(db.loader.putNew(t, metricItem(ra) + ("blob" -> s("x" * 40000))).isLeft)
    assert(ItemSize.writeUnits(metricItem(ra)) == 1)
  }

  test("ranking items: ordered top lists under RANKING#<metric> / TOP#<window>") {
    val spec = DynamoContract.ResearcherMetricCache
    def problems(i: Item) = DynamoContract.problems(spec, i, ProvenanceRules.windowsByName(windows))
    val (lo, hi) = (ra, rb) // ra < rb
    assert(problems(rankingItem(Seq(lo -> 9L, hi -> 9L, rc -> 2L))).isEmpty)
    assert(problems(rankingItem(Seq(hi -> 9L, lo -> 9L))).exists(_.contains("ordered")))
    assert(problems(rankingItem(Seq(lo -> 1L, hi -> 2L))).exists(_.contains("ordered")))
    assert(problems(rankingItem(Seq(lo -> 2L, lo -> 1L))).exists(_.contains("twice")))
    assert(problems(rankingItem(Seq.tabulate(51)(i => researcher(s"x$i") -> 1L))).nonEmpty)
    assert(problems(rankingItem(Seq(lo -> 1L)) + ("researcherId" -> s("RANKING#hIndex"))).nonEmpty)
    assert(problems(rankingItem(Seq(lo -> 1L)) + ("metricKey" -> s("TOP#1999"))).nonEmpty)
    val gap = rankingItem(Seq(lo -> 2L)) + ("entries" -> L(
      Vector(M(Map("rank" -> num(2L), "researcherId" -> s(lo), "value" -> num(2L))))
    ))
    assert(problems(gap).exists(_.contains("ranks")))
    val db = store()
    assert(
      db.loader.putNew(TableName.ResearcherMetricCache, rankingItem(Seq(lo -> 2L))) == Right(
        LoadOutcome.Inserted
      )
    )
    assert(
      db.reader
        .get(TableName.ResearcherMetricCache, "RANKING#publicationCount", "TOP#all-years")
        .nonEmpty
    )
  }

  test("RunLedger on the key-value ports: append-only, retried append unchanged") {
    val db = store()
    val ledger = KeyValueRunLedger(db.reader, db.loader)
    val e = LedgerEntry(runId, "step#1", "STEP", "2026-11-05T10:00:00Z", Map("rows" -> "3"))
    assert(ledger.append(e) == AppendOutcome.Appended)
    assert(ledger.append(e.copy(recordedAt = "2026-11-05T11:00:00Z")) == AppendOutcome.Unchanged)
    assert(ledger.append(e.copy(details = Map("rows" -> "4"))) == AppendOutcome.Conflict)
    assert(ledger.entries(runId) == Vector(e))
  }

  test("entity link keys follow the name-v1 rule") {
    assert(DynamoContract.nameKey("Wei Wang 0001") == Some("name-v1:wei wang"))
    assert(DynamoContract.nameKey("  José  García-López ") == Some("name-v1:jose garcia lopez"))
    assert(DynamoContract.nameKey("---").isEmpty)
    val item: Item = Map(
      "nameKey" -> s("name-v1:wei wang"),
      "researcherId" -> s(ra),
      "displayName" -> s("Wei Wang 0001"),
      "matchedName" -> s("Wei Wang 0001"),
      "identityConfidence" -> num(0.97),
      "mentionCount" -> num(4L),
      "dblpAnchored" -> Bool(true),
      "runId" -> s(runId),
      "snapshot" -> s(snapshot),
      "schemaVersion" -> s("entity-link-v1")
    )
    val db = store()
    assert(db.loader.putNew(TableName.EntityLinkCache, item) == Right(LoadOutcome.Inserted))
    val oldName = item + ("schemaVersion" -> s("entity-link-cache-v1"))
    assert(
      DynamoContract.problems(DynamoContract.EntityLinkCache, oldName, Map.empty) ==
        List("schemaVersion is not entity-link-v1")
    )
    assert(
      db.loader
        .putNew(TableName.EntityLinkCache, item + ("nameKey" -> s("name-v1:wang wei")))
        .isLeft
    )
  }

  test("item files: malformed lines, bad items, and duplicate keys are reported with counts") {
    withTemp { dir =>
      val f = dir.resolve("part-00000.jsonl")
      val good = DynamoItemFile.renderLine(metricItem(ra))
      val lines = Seq(good, DynamoItemFile.renderLine(metricItem(rb) - "snapshot"), "{oops", good)
      Files.writeString(f, lines.mkString("\n") + "\n", UTF_8)
      val (v, count) = DynamoItemFile.validate(TableName.ResearcherMetricCache, Seq(f), windows)
      assert(count == 4)
      assert(
        v.map(x => (x.line, x.code)).distinct == Vector(
          2L -> "BAD_ITEM",
          3L -> "BAD_ITEM",
          4L -> "DUPLICATE_KEY"
        )
      )
    }
  }

  // F1: DynamoDB trims leading and trailing zeros of numbers, so "4.0" is stored and read as "4".
  private def spark(i: Item): Item = i ++ Map(
    "weightedDegree" -> N("4.0"),
    "participationCoefficient" -> N("0.0"),
    "averageCoauthorsPerPaper" -> N("1.50")
  )

  test("number normalization: DynamoDB's canonical number text, exact to 38 digits") {
    assert(canonicalNumber("4.0") == "4")
    assert(canonicalNumber("0.0") == "0" && canonicalNumber("-0.0") == "0")
    assert(canonicalNumber("1.50") == "1.5" && canonicalNumber("0.50") == "0.5")
    assert(canonicalNumber("007.250") == "7.25" && canonicalNumber("1E+3") == "1000")
    // 37 significant digits survive unrounded (a 34-digit MathContext would round them).
    assert(
      canonicalNumber("12345678901234567890123456789012345.6700") ==
        "12345678901234567890123456789012345.67"
    )
    assert(canonical(L(Vector(M(Map("v" -> N("2.0")))))) == L(Vector(M(Map("v" -> N("2"))))))
    assert(sameItem(spark(metricItem(ra)), canonicalItem(spark(metricItem(ra)))))
    assert(!sameItem(spark(metricItem(ra)), metricItem(ra) + ("weightedDegree" -> N("4.5"))))
  }

  test(
    "fake: an item with 4.0 reloads as Unchanged, against 4.0 or 4; a different value conflicts"
  ) {
    val db = store()
    val t = TableName.ResearcherMetricCache
    assert(db.loader.putNew(t, spark(metricItem(ra))) == Right(LoadOutcome.Inserted))
    val stored = db.reader.get(t, ra, "WINDOW#all-years").get
    assert(stored("weightedDegree") == N("4") && stored("participationCoefficient") == N("0"))
    assert(stored("averageCoauthorsPerPaper") == N("1.5"))
    assert(db.loader.putNew(t, spark(metricItem(ra))) == Right(LoadOutcome.Unchanged))
    assert(db.loader.putNew(t, stored) == Right(LoadOutcome.Unchanged))
    val other = spark(metricItem(ra)) + ("weightedDegree" -> N("4.01"))
    assert(db.loader.putNew(t, other) == Right(LoadOutcome.Conflict))
  }

  test(
    "live adapter: PutItem sends canonical numbers; a 4 read back for a written 4.0 is Unchanged"
  ) {
    import scholarchain.storage.live.{DynamoCodec, DynamoStore}
    import software.amazon.awssdk.services.dynamodb.DynamoDbClient
    import software.amazon.awssdk.services.dynamodb.model.{
      ConditionalCheckFailedException,
      PutItemRequest,
      PutItemResponse
    }
    var sent = Vector.empty[PutItemRequest]
    var storedText: Option[Item] = None // what the fake service returns as ALL_OLD
    val client = new DynamoDbClient:
      def serviceName(): String = "dynamodb"
      def close(): Unit = ()
      override def putItem(r: PutItemRequest): PutItemResponse =
        sent :+= r
        storedText match
          case None      => PutItemResponse.builder().build()
          case Some(old) =>
            throw ConditionalCheckFailedException
              .builder()
              .message("The conditional request failed")
              .item(DynamoCodec.toSdkItem(old))
              .build()
    val db = DynamoStore(client, "p-", windows, () => now)
    val t = TableName.ResearcherMetricCache
    val item = spark(metricItem(ra))
    assert(db.loader.putNew(t, item) == Right(LoadOutcome.Inserted))
    val wire = sent.head.item
    assert(wire.get("weightedDegree").n == "4" && wire.get("participationCoefficient").n == "0")
    assert(wire.get("averageCoauthorsPerPaper").n == "1.5")
    storedText = Some(canonicalItem(item)) // DynamoDB returns "4" for the "4.0" written earlier
    assert(db.loader.putNew(t, item) == Right(LoadOutcome.Unchanged))
    storedText = Some(item) // even the raw text compares by value
    assert(db.loader.putNew(t, item) == Right(LoadOutcome.Unchanged))
    storedText = Some(item + ("weightedDegree" -> N("5")))
    assert(db.loader.putNew(t, item) == Right(LoadOutcome.Conflict))
  }

  test("N4: numbers outside the DynamoDB range or with invalid text are refused, never thrown") {
    Seq("0", "-0.0", "1E-130", "-1E-130", "9.9999999999999999999999999999999999999E+125", "4.0")
      .foreach(n => assert(numberOk(n), n))
    Seq("1E+126", "-1E+126", "1E-131", "1E+999999999", "1E+9999999999", "abc", "", "NaN")
      .foreach(n => assert(!numberOk(n), n))
    assert(DynamoItemFile.parseLine("""{"Item": {"x": {"N": "1E+999999999"}}}""").isLeft)
    val db = store()
    // Items built in code reach the put without parsing: invalid text is a Left, not an exception.
    val bad = metricItem(ra) + ("coreness" -> N("three")) + ("deep" -> L(
      Vector(M(Map("n" -> N("1E+200"))))
    ))
    val problems = db.loader.putNew(TableName.ResearcherMetricCache, bad).left.getOrElse(Nil)
    assert(problems.exists(_.contains("coreness")) && problems.exists(_.contains("deep")))
    assert(
      db.runtimeWriter
        .create(TableName.QuerySession, sessionItem(2000) + ("expiresAt" -> N("x")))
        .isLeft
    )
    assert(db.itemCount(TableName.ResearcherMetricCache) == 0)
  }

  test(
    "runtime item schema versions are fixed: rag-query-session-v1, rag-evidence-bundle-item-v1"
  ) {
    val db = store()
    val bundle: Item = Map(
      "queryId" -> s("qry-0123456789abcdef0123456789abcdef"),
      "itemKey" -> s("BUNDLE"),
      "expiresAt" -> num(2000L),
      "schemaVersion" -> s("rag-evidence-bundle-item-v1"),
      "payload" -> s("{}")
    )
    assert(db.runtimeWriter.create(TableName.EvidenceBundle, bundle) == Right(LoadOutcome.Inserted))
    val wrong = bundle + ("itemKey" -> s("EV#x")) + ("schemaVersion" -> s("evidence-bundle-v1"))
    assert(db.runtimeWriter.create(TableName.EvidenceBundle, wrong).isLeft)
    assert(
      db.runtimeWriter
        .create(TableName.QuerySession, sessionItem(2000) + ("schemaVersion" -> s("other")))
        .isLeft
    )
  }

  test("item files load read-only into the fake; violations and conflicts load nothing") {
    withTemp { root =>
      val dir = root.resolve(s"curated/dynamodb/snapshot=$snapshot/run=$runId")
      def write(t: TableName, items: Item*) =
        val d = dir.resolve(t.toString)
        Files.createDirectories(d)
        Files.writeString(
          d.resolve("part-00000.jsonl"),
          items.map(DynamoItemFile.renderLine).mkString("", "\n", "\n"),
          UTF_8
        )
      write(TableName.ResearcherMetricCache, metricItem(ra), rankingItem(Seq(ra -> 3L)))
      val files = DynamoItemFile.files(dir)
      val bytes = files.map(Files.readAllBytes(_).toVector)
      val db = store()
      assert(db.loadItemFiles(dir) == Right(Map(TableName.ResearcherMetricCache -> 2L)))
      assert(db.reader.get(TableName.ResearcherMetricCache, ra, "WINDOW#all-years").nonEmpty)
      assert(
        db.loadItemFiles(dir) == Right(Map(TableName.ResearcherMetricCache -> 2L))
      ) // unchanged
      assert(files.map(Files.readAllBytes(_).toVector) == bytes) // read only
      write(TableName.ResearcherMetricCache, metricItem(ra, coreness = 9))
      assert(db.loadItemFiles(dir).left.toOption.get.map(_.code) == Vector("CONFLICT"))
      write(TableName.ResearcherMetricCache, metricItem(rb) - "runId")
      val fresh = store()
      assert(
        fresh.loadItemFiles(dir).isLeft && fresh.itemCount(TableName.ResearcherMetricCache) == 0
      )
      Files.createDirectories(dir.resolve("QuerySession"))
      assert(fresh.loadItemFiles(dir).left.toOption.get.map(_.code) == Vector("BAD_INPUT"))
    }
  }

  test("file-backed runtime records: create-only, replayed on open, partial last line cut") {
    withTemp { root =>
      val dir = root.resolve("dynamodb")
      val recs = FileRuntimeRecords(dir, windows, () => now)
      val item = sessionItem(2000)
      assert(recs.writer.create(TableName.QuerySession, item) == Right(LoadOutcome.Inserted))
      assert(recs.writer.create(TableName.QuerySession, item) == Right(LoadOutcome.Unchanged))
      val other = item + ("queryId" -> s("q-1")) + ("createdAt" -> s("2026-11-05T10:00:00Z"))
      assert(
        recs.writer.create(TableName.QuerySession, other + ("expiresAt" -> num(3000L))) ==
          Right(LoadOutcome.Conflict)
      )
      assert(recs.writer.create(TableName.RunLedger, item).isLeft)
      val log = dir.resolve("QuerySession.jsonl")
      assert(Files.readAllLines(log).size == 1)
      Files.writeString(log, """{"Item": {"sessi""", UTF_8, java.nio.file.StandardOpenOption.APPEND)
      val reopened = FileRuntimeRecords(dir, windows, () => now)
      assert(reopened.itemCount(TableName.QuerySession) == 1)
      assert(reopened.reader.query(TableName.QuerySession, "session-1", "", 10) == Vector(item))
      assert(Files.readString(log).endsWith("\n")) // the partial line was cut
      assert(reopened.writer.create(TableName.QuerySession, item) == Right(LoadOutcome.Unchanged))
      Files.writeString(log, "not json\n", UTF_8, java.nio.file.StandardOpenOption.APPEND)
      assertThrows[IllegalStateException](FileRuntimeRecords(dir, windows, () => now))
    }
  }

  // ---- Indexed item-file reader (Phase 9 F6, ruling 22) ---------------------------------------

  private def linkItem(name: String, id: String): Item = Map(
    "nameKey" -> s(DynamoContract.nameKey(name).get),
    "researcherId" -> s(id),
    "displayName" -> s(name),
    "matchedName" -> s(name),
    "identityConfidence" -> N("0.90"),
    "mentionCount" -> num(4L),
    "dblpAnchored" -> Bool(true),
    "runId" -> s(runId),
    "snapshot" -> s(snapshot),
    "schemaVersion" -> s("entity-link-v1")
  )

  private def writeItems(dir: java.nio.file.Path, t: TableName, part: Int, items: Item*) =
    val d = dir.resolve(t.toString)
    Files.createDirectories(d)
    val f = d.resolve(f"part-$part%05d.jsonl")
    Files.writeString(f, items.map(DynamoItemFile.renderLine).mkString("\n"), UTF_8) // no final \n
    f

  test("item-file reader: get and query equal the in-memory fake, from files it never writes") {
    withTemp { root =>
      val dir = root.resolve(s"curated/dynamodb/snapshot=$snapshot/run=$runId")
      val metrics = TableName.ResearcherMetricCache
      val links = TableName.EntityLinkCache
      writeItems(dir, metrics, 0, spark(metricItem(rb)), rankingItem(Seq(ra -> 3L)))
      writeItems(dir, metrics, 1, spark(metricItem(ra)))
      writeItems(dir, links, 0, linkItem("Wei Wang 0002", rb), linkItem("Wei Wang 0001", ra))
      val files = DynamoItemFile.files(dir)
      val bytes = files.map(Files.readAllBytes(_).toVector)
      val kv = ItemFileReader.open(dir, windows).fold(v => fail(v.mkString("\n")), identity)
      val mem = store()
      assert(mem.loadItemFiles(dir).isRight)
      assert(kv.itemCount == 5 && kv.itemCount(metrics) == 3 && kv.itemCount(links) == 2)
      val keys = Seq(
        (metrics, ra, "WINDOW#all-years"),
        (metrics, rb, "WINDOW#all-years"),
        (metrics, "RANKING#publicationCount", "TOP#all-years"),
        (links, "name-v1:wei wang", ra),
        (links, "name-v1:wei wang", rb)
      )
      for (t, pk, sk) <- keys do
        assert(kv.reader.get(t, pk, sk).nonEmpty, s"$t $pk $sk")
        assert(kv.reader.get(t, pk, sk) == mem.reader.get(t, pk, sk))
        for prefix <- Seq("", "WINDOW#", "TOP#", "scr:", "none"); limit <- Seq(1, 10) do
          assert(kv.reader.query(t, pk, prefix, limit) == mem.reader.query(t, pk, prefix, limit))
      // canonical numbers, as DynamoDB returns them; sort-key order across lines and files
      assert(kv.reader.get(metrics, ra, "WINDOW#all-years").get("weightedDegree") == N("4"))
      assert(
        kv.reader.query(links, "name-v1:wei wang", "", 10).map(_("researcherId")) ==
          Vector(s(ra), s(rb))
      )
      assert(kv.reader.get(metrics, ra, "WINDOW#2000-2014").isEmpty)
      assert(kv.reader.query(metrics, "scr:nobody", "", 10).isEmpty)
      assert(kv.reader.get(TableName.QuerySession, "session-1", "x").isEmpty)
      assertThrows[IllegalArgumentException](kv.reader.query(metrics, ra, "", 0))
      assertThrows[IllegalArgumentException](
        kv.reader.query(metrics, ra, "", DynamoContract.QueryMaxLimit + 1)
      )
      assert(files.map(Files.readAllBytes(_).toVector) == bytes) // read only
    }
  }

  test("item-file reader: malformed, invalid, or duplicate items and bad directories fail open") {
    withTemp { root =>
      val dir = root.resolve(s"curated/dynamodb/snapshot=$snapshot/run=$runId")
      val metrics = TableName.ResearcherMetricCache
      def codes = ItemFileReader.open(dir, windows).left.toOption.get.map(v => (v.line, v.code))
      assert(codes == Vector(0L -> "BAD_INPUT")) // missing directory
      val f = writeItems(dir, metrics, 0, metricItem(ra))
      Files.writeString(
        f,
        Seq(
          "",
          "{oops",
          """{"Item":{"researcherId":{"S":"x"}}}""",
          DynamoItemFile.renderLine(metricItem(rb) - "snapshot"),
          DynamoItemFile.renderLine(metricItem(ra))
        ).mkString("\n", "\n", "\n"),
        UTF_8,
        java.nio.file.StandardOpenOption.APPEND
      )
      // line 1 is the good item, line 2 is blank (skipped, still counted)
      assert(
        codes == Vector(3L -> "BAD_ITEM", 4L -> "BAD_ITEM", 5L -> "BAD_ITEM", 6L -> "DUPLICATE_KEY")
      )
      writeItems(dir, metrics, 0, metricItem(ra))
      writeItems(dir, metrics, 1, metricItem(ra)) // duplicate across files
      assert(codes == Vector(1L -> "DUPLICATE_KEY"))
      Files.writeString(f, Seq.fill(150)("{oops").mkString("\n"), UTF_8)
      val many = ItemFileReader.open(dir, windows).left.toOption.get
      assert(many.size == ItemFileReader.MaxViolations + 1 && many.last.code == "TRUNCATED")
      assert(many.last.detail == "150 violations in total")
      writeItems(dir, metrics, 0, metricItem(rb))
      assert(ItemFileReader.open(dir, windows).isRight)
      Files.createDirectories(dir.resolve("QuerySession")) // runtime tables are never loaded
      assert(codes == Vector(0L -> "BAD_INPUT"))
    }
  }

  test("item-file reader: a file changed after open is refused, never misread") {
    withTemp { root =>
      val dir = root.resolve(s"curated/dynamodb/snapshot=$snapshot/run=$runId")
      val metrics = TableName.ResearcherMetricCache
      val f = writeItems(dir, metrics, 0, metricItem(ra), metricItem(rb))
      val kv = ItemFileReader.open(dir, windows).fold(v => fail(v.mkString), identity)
      assert(kv.reader.get(metrics, rb, "WINDOW#all-years") == Some(metricItem(rb)))
      // Same size and modification time, lines swapped: the key check catches it.
      val stamp = Files.getLastModifiedTime(f)
      writeItems(dir, metrics, 0, metricItem(rb), metricItem(ra))
      Files.setLastModifiedTime(f, stamp)
      assertThrows[IllegalStateException](kv.reader.get(metrics, rb, "WINDOW#all-years"))
      // A different size: the file stamp catches it before reading.
      writeItems(dir, metrics, 0, metricItem(ra))
      Files.setLastModifiedTime(f, stamp)
      val e = intercept[IllegalStateException](kv.reader.query(metrics, ra, "", 10))
      assert(e.getMessage.contains("the file changed"))
      Files.delete(f)
      assertThrows[IllegalStateException](kv.reader.get(metrics, ra, "WINDOW#all-years"))
    }
  }
