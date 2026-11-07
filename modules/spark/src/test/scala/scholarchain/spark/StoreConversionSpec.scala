package scholarchain.spark

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Phase 7 work order 6: benchmark path resolution, rankings, store-file rendering, selection-v1,
  * and the store conversion end to end on the Fixture pipeline. Expected values are by hand.
  */
class StoreConversionSpec extends AnyFunSuite:
  private lazy val spark = TestSpark.spark

  private def df(schema: String, rows: Seq[Any]*): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(rows.map(r => Row.fromSeq(r))*),
      StructType.fromDDL(schema)
    )
  private def strings(d: DataFrame): Seq[String] = d.collect().toSeq.map(_.getString(0))

  test("CSV fields follow RFC 4180 and doubles are plain decimals") {
    val d = df(
      "a STRING, b STRING, c STRING, d STRING, e STRING",
      Seq("plain", "x,y", "say \"hi\"", null, "two\nlines")
    )
    val cols = Seq("a", "b", "c", "d", "e").map(n => StoreFiles.str(n, col(n)))
    assert(StoreFiles.header(cols) == "a:String,b:String,c:String,d:String,e:String")
    assert(
      strings(d.select(StoreFiles.csvLine(cols))) ==
        Seq("plain,\"x,y\",\"say \"\"hi\"\"\",,\"two\nlines\"")
    )
    val n = df("x DOUBLE", Seq(0.6666666666666666), Seq(1.0e-4), Seq(3.0), Seq(1.5e7), Seq(null))
    assert(
      n.select(StoreFiles.plain(col("x"))).collect().toSeq.map(_.get(0)) ==
        Seq("0.6666666666666666", "0.0001", "3.0", "15000000", null)
    )
  }

  test("DynamoDB items: typed attributes, null attributes omitted, string lists") {
    val d = df(
      "id STRING, n BIGINT, x DOUBLE, b BOOLEAN, xs ARRAY<STRING>, gone STRING",
      Seq("r1", 5L, 0.5, true, Seq("w1", "w2"), null)
    )
    val item = StoreFiles.item(StoreFiles.attrsOf(d, Set.empty))
    assert(
      strings(d.select(item)) == Seq(
        """{"Item":{"id":{"S":"r1"},"n":{"N":"5"},"x":{"N":"0.5"},"b":{"BOOL":true},""" +
          """"xs":{"L":[{"S":"w1"},{"S":"w2"}]}}}"""
      )
    )
  }

  // b and c tie on 5 publications (b first by ID); a has 3; d has no value and is not ranked.
  test("rankings: value descending, ties by researcherId, nulls not ranked, top n") {
    val metrics = df(
      "window STRING, researcherId STRING, publicationCount BIGINT, distinctCoauthorCount BIGINT, maximumCoauthorsOnOnePaper BIGINT, coreness INT",
      Seq("all", "a", 3L, 1L, 1L, 1),
      Seq("all", "c", 5L, 2L, 2L, 2),
      Seq("all", "b", 5L, 2L, 2L, 2),
      Seq("all", "d", null, 0L, 0L, 0)
    )
    val comms = df(
      "window STRING, researcherId STRING, crossCommunityDegree BIGINT, communitiesConnected BIGINT, participationCoefficient DOUBLE",
      Seq("all", "a", 1L, 2L, 0.5),
      Seq("all", "b", 0L, 1L, 0.0),
      Seq("all", "c", 1L, 2L, 0.6666666666666666)
    )
    val r = StoreFiles.rankings(metrics, comms, 2)
    def top(m: String) = r
      .where(col("metric") === m)
      .orderBy("rank")
      .collect()
      .toSeq
      .map(x =>
        (x.getAs[Int]("rank"), x.getAs[String]("researcherId"), x.getAs[String]("valueText"))
      )
    assert(top("publicationCount") == Seq((1, "b", "5"), (2, "c", "5")))
    assert(top("participationCoefficient") == Seq((1, "c", "0.6666666666666666"), (2, "a", "0.5")))
    assert(r.select("metric").distinct().count() == 7)
  }

  test("entity links: name-v1 keys drop DBLP homonym numbers; one item per key and researcher") {
    assert(StoreFiles.nameKeyOf("Lei Zhang 0001") == "name-v1:lei zhang")
    assert(StoreFiles.nameKeyOf("  José Duato ") == "name-v1:jose duato")
    assert(StoreFiles.nameKeyOf("...") == null)
    val r = df(
      "researcherId STRING, displayName STRING, alternateNames ARRAY<STRING>",
      Seq("r1", "Lei Zhang 0001", Seq("Lei Zhang", "L. Zhang")),
      Seq("r2", "Lei Zhang", null)
    )
    val got = StoreFiles
      .entityLinks(r)
      .select("nameKey", "researcherId", "matchedName")
      .collect()
      .map(x => (x.getString(0), x.getString(1), x.getString(2)))
      .toSet
    assert(
      got == Set(
        ("name-v1:lei zhang", "r1", "Lei Zhang 0001"), // the display name wins over "Lei Zhang"
        ("name-v1:l zhang", "r1", "L. Zhang"),
        ("name-v1:lei zhang", "r2", "Lei Zhang")
      )
    )
  }

  test("benchmark names: dominant-name-v1 (ruling 30) resolves a dominant display-name candidate") {
    val tmp = Files.createTempDirectory("sc-bench")
    val csv = tmp.resolve("b.csv")
    Files.writeString(
      csv,
      "question_id,category,question_text,researcher_a,researcher_b,window,answer_type,should_abstain,notes\n" +
        "B2,x,\"Ann, and Bo?\",Ann Lee,Bo Chen,all,path,no,\n" +
        "B1,x,one name,Ann Lee,,all,count,no,\n" +
        "B3,x,q,Ann Lee,Cy Diaz,2000-2014,path,no,\n" +
        "B4,x,q,Di Ng,Nobody,all,abstain,yes,\n",
      UTF_8
    )
    val qs =
      BenchmarkPathJob.questions(spark, csv.toString, "all-years", Set("all-years", "2000-2014"))
    assert(
      qs == Seq(
        BenchmarkPathJob.Question("B2", "all-years", "Ann Lee", "Bo Chen"),
        BenchmarkPathJob.Question("B3", "2000-2014", "Ann Lee", "Cy Diaz"),
        BenchmarkPathJob.Question("B4", "all-years", "Di Ng", "Nobody")
      )
    )
    val researchers = df(
      "researcherId STRING, displayName STRING, mentionCount BIGINT",
      Seq("r1", "Ann Lee", 10L), // alone, 10 works: share 1.0
      Seq("r2", "B. Chen", 1L), // only its alternate name was "Bo Chen": no candidate
      Seq("r3", "Cy Diaz", 9L), // 9 of 10 works: exactly 0.9, dominant
      Seq("r4", "Cy Diaz", 3L), // 1 work, 3 mentions
      Seq("r5", "Ann Lee 0001", 1L), // not the same printed name
      Seq("r6", "Di Ng", 8L), // 8 of 10: 0.8, not dominant
      Seq("r7", "Di Ng", 2L),
      Seq("r8", "Ed Fox", 4L), // alone but only 4 works: below the minimum of 5
      Seq("r9", "Gu Li", 5L), // a tie: share 0.5; rank by researcherId puts r10 first
      Seq("r10", "Gu Li", 5L)
    )
    val works = Seq("r1" -> 10, "r3" -> 9, "r4" -> 1, "r5" -> 1, "r6" -> 8, "r7" -> 2) ++
      Seq("r8" -> 4, "r9" -> 5, "r10" -> 5)
    val authored = df(
      "researcherId STRING, workId STRING",
      works.flatMap((r, n) => (1 to n).map(i => Seq(r, s"$r-w$i")))*
    )
    val d = BenchmarkPathJob.Dominance(0.9, 5)
    val names = Seq("Ann Lee", "Bo Chen", "Cy Diaz", "Di Ng", "Ed Fox", "Gu Li", "Nobody")
    val res = BenchmarkPathJob.resolve(spark, researchers, authored, names, d)
    import BenchmarkPathJob.Resolution
    assert(
      res("Ann Lee") == Resolution("RESOLVED_DOMINANT", Some("r1"), 1, Some(1.0), 10, 10, Seq("r1"))
    )
    assert(res("Bo Chen") == Resolution("UNKNOWN_RESEARCHER", None, 0, None, 0, 0, Nil))
    assert(
      res("Cy Diaz") == Resolution(
        "RESOLVED_DOMINANT",
        Some("r3"),
        2,
        Some(0.9),
        9,
        10,
        Seq("r3", "r4")
      )
    )
    assert(res("Di Ng") == Resolution("AMBIGUOUS", None, 2, Some(0.8), 8, 10, Seq("r6", "r7")))
    assert(res("Ed Fox") == Resolution("AMBIGUOUS", None, 1, Some(1.0), 4, 4, Seq("r8")))
    assert(res("Gu Li") == Resolution("AMBIGUOUS", None, 2, Some(0.5), 5, 10, Seq("r10", "r9")))
    assert(res("Nobody").status == "UNKNOWN_RESEARCHER")
    import BenchmarkPathJob.pairStatus
    assert(pairStatus(res("Ann Lee"), res("Cy Diaz")) == "RESOLVED_DOMINANT")
    assert(pairStatus(res("Ann Lee"), res("Di Ng")) == "AMBIGUOUS")
    assert(pairStatus(res("Di Ng"), res("Nobody")) == "UNKNOWN_RESEARCHER")
    // A lower share threshold resolves Di Ng; the thresholds are parameters, not constants.
    val lower =
      BenchmarkPathJob.resolve(spark, researchers, authored, Seq("Di Ng"), d.copy(share = 0.8))
    assert(lower("Di Ng").id.contains("r6"))

    // Corpus-wide split identities: Cy Diaz is the one shared name with a dominant candidate; its
    // non-dominant candidate r4 holds 3 mentions and 1 work. Di Ng and Gu Li stay ambiguous.
    val split = BenchmarkPathJob
      .splitIdentities(BenchmarkPathJob.dominance(researchers, authored, d))
      .collect()
      .head
    def v(c: String) = split.getLong(split.fieldIndex(c))
    assert(v("distinctDisplayNames") == 7 && v("researchers") == 10)
    assert(v("namesWithSeveralResearchers") == 3 && v("researchersUnderSharedNames") == 6)
    assert(v("namesWithDominantCandidate") == 1 && v("namesStillAmbiguous") == 2)
    assert(v("nonDominantResearchers") == 1 && v("nonDominantMentions") == 3)
    assert(v("nonDominantAuthoredRows") == 1 && v("dominantAuthoredRows") == 9)
    assert(v("maxCandidatesUnderDominantName") == 2)
  }

  test("selection-v1: frozen rank keys and the metadata lists derived from facts") {
    val k = spark
      .range(1)
      .select(
        Selection.rankKey(
          lit("researcher-profile-v1"),
          lit("scr:canon-v1.er-v2:001fe522e8222b9065d8747681782ee5"),
          lit("all-years")
        ),
        Selection
          .rankKey(lit("paper-abstract-v1"), lit("dblp:conf/ispa/TangY0DHH24"), lit("all-years"))
      )
    val row = k.head()
    assert(row.getString(0) == "c0bd6ae2faf1ff52b9dc44304245b5fc")
    assert(row.getString(1) == "0a3416a1573f16e3b3fe090dbbd145df")
    val docs = df(
      "documentType STRING, facts STRING",
      Seq(
        "collaboration-path",
        """{"pairId":"p1","status":"FOUND","pathLength":2,"sourceResearcherId":"a","targetResearcherId":"c","hops":[{"hop":1,"id":"b"},{"hop":2,"id":"c"}]}"""
      ),
      Seq(
        "collaboration-path",
        """{"pairId":"p2","status":"BEYOND_DEPTH","pathLength":null,"sourceResearcherId":"a","targetResearcherId":"z"}"""
      ),
      Seq(
        "researcher-profile",
        """{"researcherId":"a","communityId":"cm","topics":[{"id":"t1"}],"institutions":[{"id":"i1"},{"id":"i2"}]}"""
      ),
      Seq("topic-summary", """{"topicId":"t1"}"""),
      Seq("paper-summary", """{"workId":"w1","authors":[{"id":"a"}]}""")
    )
    val got = Selection
      .metadataLists(Selection.parsed(docs))
      .select("entityIds", "topicIds", "institutionIds", "communityId")
      .collect()
      .toSeq
      .map(r =>
        (r.getSeq[String](0), r.getSeq[String](1), r.getSeq[String](2), Option(r.getString(3)))
      )
    assert(
      got == Seq(
        (Seq("p1", "a", "b", "c"), Nil, Nil, None),
        (Seq("p2", "a", "z"), Nil, Nil, None),
        (Seq("a"), Seq("t1"), Seq("i1", "i2"), Some("cm")),
        (Seq("t1"), Seq("t1"), Nil, None),
        (Seq("w1"), Nil, Nil, None)
      )
    )
  }

  // Profiles a, b, c, d: the path a -> b -> c makes a, b, c tier 1; d is ranked (tier 2); e is
  // tier 3. Community cA (a's) is tier 1 in all-years. Papers: the path's hop works w1, w2
  // (tier 1, hop order), then the recent works of the profiles (w2 again is skipped; w9 is not
  // eligible), then the rest by hash.
  test("selection-v1: tiers, ranks 1..n, skipped and repeated papers") {
    def summary(doc: String, subject: String, window: String, facts: String) =
      Seq(doc, subject, window, facts)
    val schema = "documentType STRING, subjectId STRING, window STRING, facts STRING"
    val summaries = df(
      schema,
      summary(
        "researcher-profile",
        "e",
        "all-years",
        """{"researcherId":"e","recentWorks":[{"id":"w5"}]}"""
      ),
      summary(
        "researcher-profile",
        "d",
        "all-years",
        """{"researcherId":"d","communityId":"cD","recentWorks":[{"id":"w9"},{"id":"w4"}]}"""
      ),
      summary(
        "researcher-profile",
        "a",
        "all-years",
        """{"researcherId":"a","communityId":"cA","recentWorks":[{"id":"w2"},{"id":"w3"}]}"""
      ),
      summary("researcher-profile", "b", "all-years", """{"researcherId":"b"}"""),
      summary("researcher-profile", "c", "all-years", """{"researcherId":"c"}"""),
      summary("collaboration-community", "cZ", "all-years", "{}"),
      summary("collaboration-community", "cA", "all-years", "{}"),
      summary("collaboration-community", "cD", "2000-2014", "{}"),
      summary("topic-summary", "t1", "2000-2014", "{}"),
      summary("topic-summary", "t1", "all-years", "{}")
    )
    val paths = df(
      schema,
      summary(
        "collaboration-path",
        "p1",
        "all-years",
        """{"status":"FOUND","sourceResearcherId":"a","targetResearcherId":"c","hops":[{"hop":1,"id":"b","workId":"w1"},{"hop":2,"id":"c","workId":"w2"}]}"""
      )
    )
    val eligible =
      df("workId STRING", Seq("w1"), Seq("w2"), Seq("w3"), Seq("w4"), Seq("w5"), Seq("w6"))
    val s = Selection.select(
      summaries,
      paths,
      df("researcherId STRING", Seq("d")),
      eligible,
      Seq("all-years", "2000-2014", "2015-2026"),
      d => s"ix-$d"
    )
    def ordered(d: DataFrame, c: String) =
      d.orderBy("selectionRank").select(c).collect().toSeq.map(_.get(0))
    val profiles = s.profiles.orderBy("selectionRank").select("subjectId", "tier").collect().toSeq
    assert(profiles.take(3).map(_.getInt(1)) == Seq(1, 1, 1))
    assert(profiles.take(3).map(_.getString(0)).toSet == Set("a", "b", "c"))
    assert(profiles.drop(3).map(r => (r.getString(0), r.getInt(1))) == Seq(("d", 2), ("e", 3)))
    assert(ordered(s.profiles, "selectionRank") == (1 to 5))
    assert(
      ordered(s.communities, "subjectId") == Seq("cA", "cZ", "cD")
    ) // tier 1, then window order
    assert(ordered(s.topics, "window") == Seq("all-years", "2000-2014"))
    val papers = ordered(s.papers, "workId")
    assert(papers.take(2) == Seq("w1", "w2"))
    val tier2 = papers.slice(2, 5).toSet
    assert(tier2 == Set("w3", "w4", "w5")) // the profile order decides their order; w9 skipped
    assert(papers.drop(5) == Seq("w6") && papers.distinct.size == papers.size)
  }

  // ---- End to end: Fixture pipeline -> benchmark path run -> store conversion ---------------------
  test("the store conversion writes every store file once, listed with its hash in the manifest") {
    val p = PipelineFixture.result
    val cpDir = p.manifest("stages").arr(2)("runDir").str
    val exportDir = p.manifest("stages").arr(3)("runDir").str
    val tmp = Files.createTempDirectory("sc-stores")
    val bench = tmp.resolve("bench.csv")
    Files.writeString(
      bench,
      "question_id,category,question_text,researcher_a,researcher_b,window,answer_type,should_abstain,notes\n" +
        "B1,x,\"Pat, Quinn?\",Pat Pair,Quinn Pair,all,path,no,\n" +
        "B2,x,q,Pat Pair,Nobody Here,all,abstain,yes,\n",
      UTF_8
    )
    val s = PipelineFixture.base.copy(out = tmp.resolve("spark").toString)
    // The fixture researchers have 2 works each, so the minimum is lowered to 2 (a parameter).
    val pr = BenchmarkPathJob.run(
      spark,
      s.copy(input = cpDir),
      PipelineFixture.cfg,
      bench.toString,
      BenchmarkPathJob.Dominance(0.9, 2)
    )
    val res = pr
      .manifest("counts")("benchmarkResolutions")
      .arr
      .map(r => r("questionId").str -> r("status").str)
      .toMap
    assert(res == Map("B1" -> "RESOLVED_DOMINANT", "B2" -> "UNKNOWN_RESEARCHER"))
    val found = pr.manifest("counts")("benchmarkPathResults").arr
    assert(
      found.map(r => (r("questionId").str, r("status").str, r("pathLength").num)).toSeq == Seq(
        ("B1", "FOUND", 1.0)
      )
    )
    assert(
      pr.manifest("counts")("integrityViolationsByCheck").arr.isEmpty
    ) // seeded paths unchanged

    val storeRoot = tmp.resolve("s3")
    val r = StoreConversionJob.run(
      spark,
      s.copy(input = exportDir),
      PipelineFixture.cfg,
      pr.runDir,
      storeRoot.toString
    )
    val m = ujson.read(Files.readString(storeRoot.resolve(r.storeManifestKey), UTF_8))
    val entries = m("entries").arr.toSeq
    def sha(b: Array[Byte]) =
      "sha256:" + MessageDigest
        .getInstance("SHA-256")
        .digest(b)
        .map(x => f"${x & 0xff}%02x")
        .mkString
    entries.foreach { e =>
      val bytes = Files.readAllBytes(storeRoot.resolve(e("key").str))
      assert(sha(bytes) == e("sha256").str && bytes.length == e("bytes").num.toLong, e("key").str)
      assert(
        e("key").str.contains(s"/snapshot=2026-10-03/run=${r.runId}/") && e(
          "sourceRef"
        ).str.nonEmpty
      )
    }
    // Every file under the store root is listed (no .crc, _SUCCESS, or temp file), plus the manifest.
    val all = Files
      .walk(storeRoot)
      .iterator
      .asScala
      .filter(Files.isRegularFile(_))
      .map(storeRoot.relativize(_).toString)
      .toSet
    assert(all == entries.map(_("key").str).toSet + r.storeManifestKey)

    val neptune = s"graph/neptune/snapshot=2026-10-03/run=${r.runId}"
    def lines(key: String) = Files.readAllLines(storeRoot.resolve(key), UTF_8).asScala.toSeq
    val researcherFile =
      entries.map(_("key").str).find(_.startsWith(s"$neptune/vertices/researcher/")).get
    assert(
      lines(researcherFile).head ==
        ":ID,:LABEL,displayName:String,orcid:String,mentionCount:Long,dblpAnchored:Bool,assignedTier:String," +
        "identityConfidence:Double,runId:String,snapshot:String,schemaVersion:String"
    )
    val families = entries
      .map(_("key").str)
      .filter(_.startsWith(neptune))
      .map(_.split('/').slice(5, 7).mkString("/"))
      .toSet
    assert(families.size == 12)

    // Candidates: per index, ranks 1..n in line order; facts are JSON objects; path facts have names.
    val cands = entries.map(_("key").str).filter(_.startsWith("semantic-context/candidates/"))
    assert(cands.size == 5)
    cands.foreach { k =>
      val rows = lines(k).map(ujson.read(_))
      assert(rows.map(_("selectionRank").num.toInt) == (1 to rows.size), k)
      assert(rows.forall(_("facts").isInstanceOf[ujson.Obj]))
      if k.contains("collaboration-path-v1") then
        assert(
          rows.forall(x =>
            x("facts").obj.contains("sourceName") && x("facts").obj.contains("targetName")
          )
        )
    }
    // ResearcherMetricCache holds one RANKING# item per metric and window.
    val rmc = entries.map(_("key").str).filter(_.contains("/ResearcherMetricCache/")).flatMap(lines)
    val rankingItems =
      rmc.map(ujson.read(_)("Item")).filter(_("researcherId")("S").str.startsWith("RANKING#"))
    assert(rankingItems.size == 7 * 3)
    assert(r.manifest("counts")("rowsPerFamily")("dynamodb-ArtifactRegistry").num == 9)
  }
