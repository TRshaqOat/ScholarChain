package scholarchain.spark

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import scholarchain.core.config.{ConfigLoader, TimeWindow}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Jobs 9, 10, and 11, and the end-to-end driver. Every expected value is worked out by hand. */
class TemporalSummarySpec extends AnyFunSuite:
  private lazy val spark = TestSpark.spark

  private def df(schema: String, rows: Seq[Any]*): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(rows.map(r => Row.fromSeq(r))*),
      StructType.fromDDL(schema)
    )

  // ---- Job 9 ---------------------------------------------------------------------------------------
  // Windows e (2000-2009) and l (2010-2020); "all" overlaps both and is not compared.
  // e: triangle a-b-c (component X, the largest, community E1 of 3) and isolated d (Y, E2 of 1).
  // l: path a-b-d-e (component Z, the largest); communities L1 {a,b}, L2 {d,e}; b-d is the cross
  //    edge, so b and d have crossCommunityDegree 1.
  // By hand: a, b, d are in BOTH; c FROM_ONLY; e TO_ONLY.
  //   retained coauthors: a {b,c} vs {b} -> 1; b {a,c} vs {a,d} -> 1; d {} -> 0.
  //   transitions: E1->L1 shared {a,b} = 2; E2->L2 shared {d} = 1.
  //   retention a, b: (2 - 1) / (3 - 1) = 0.5; d: from-community of 1 -> null.
  //   largest component: a, b IN->IN; c IN->ABSENT; d OUT->IN; e ABSENT->IN.
  //   cross community: a NO_CROSS->NO_CROSS; b and d NO_CROSS->CROSS; c NO_CROSS->ABSENT;
  //   e ABSENT->NO_CROSS. delta C: a 1-2 = -1, b 2-2 = 0, d 2-0 = 2. delta M: a, b 1-2 = -1,
  //   d 2-0 = 2. delta coreness: a, b 1-2 = -1, d 1-0 = 1 (differs from delta M for d).
  //   comparison: from 4, to 4, both 3, retaining a coauthor 2, median retention 0.5,
  //   mean C from (2+2+0)/3 = 4/3, to (1+2+2)/3 = 5/3, became cross-community 2.
  test("job 9: researchers are compared across non-overlapping windows by hand-worked rules") {
    val all = TimeWindow("all", 2000, 2020)
    val e = TimeWindow("e", 2000, 2009)
    val l = TimeWindow("l", 2010, 2020)
    assert(Temporal.comparisons(Seq(all, e, l)) == Seq((e, l)))
    val metrics = df(
      "window STRING, researcherId STRING, publicationCount BIGINT, distinctCoauthorCount BIGINT, averageCoauthorsPerPaper DOUBLE, maximumCoauthorsOnOnePaper BIGINT, coreness INT, componentId STRING, componentSize BIGINT",
      Seq("e", "a", 2L, 2L, 1.5, 2L, 2, "X", 3L),
      Seq("e", "b", 1L, 2L, 2.0, 2L, 2, "X", 3L),
      Seq("e", "c", 1L, 2L, 2.0, 2L, 2, "X", 3L),
      Seq("e", "d", 1L, 0L, 0.0, 0L, 0, "Y", 1L),
      Seq("l", "a", 3L, 1L, 1.0, 1L, 1, "Z", 4L),
      Seq("l", "b", 2L, 2L, 1.0, 1L, 1, "Z", 4L),
      Seq("l", "d", 1L, 2L, 2.0, 2L, 1, "Z", 4L),
      Seq("l", "e", 1L, 1L, 1.0, 1L, 1, "Z", 4L)
    )
    val memberships = df(
      "window STRING, researcherId STRING, communityId STRING, communitySize BIGINT, crossCommunityDegree BIGINT, communitiesConnected BIGINT, participationCoefficient DOUBLE",
      Seq("e", "a", "E1", 3L, 0L, 1L, 0.0),
      Seq("e", "b", "E1", 3L, 0L, 1L, 0.0),
      Seq("e", "c", "E1", 3L, 0L, 1L, 0.0),
      Seq("e", "d", "E2", 1L, 0L, 0L, 0.0),
      Seq("l", "a", "L1", 2L, 0L, 1L, 0.0),
      Seq("l", "b", "L1", 2L, 1L, 2L, 0.5),
      Seq("l", "d", "L2", 2L, 1L, 2L, 0.5),
      Seq("l", "e", "L2", 2L, 0L, 1L, 0.0)
    )
    val largest = df("window STRING, componentId STRING", Seq("e", "X"), Seq("l", "Z"))
    val edges = df(
      "window STRING, researcherA STRING, researcherB STRING",
      Seq("e", "a", "b"),
      Seq("e", "a", "c"),
      Seq("e", "b", "c"),
      Seq("l", "a", "b"),
      Seq("l", "b", "d"),
      Seq("l", "d", "e")
    )
    val (changes, transitions) =
      Temporal.researcherChanges(metrics, memberships, largest, edges, "e", "l")
    val got = changes
      .select(
        "researcherId",
        "presence",
        "retainedCoauthors",
        "largestComponentTransition",
        "crossCommunityTransition",
        "communityRetention",
        "deltaDistinctCoauthorCount",
        "deltaMaximumCoauthorsOnOnePaper",
        "deltaCoreness"
      )
      .collect()
      .map(r => r.getString(0) -> r.toSeq.tail)
      .toMap
    assert(got("a") == Seq("BOTH", 1L, "IN->IN", "NO_CROSS->NO_CROSS", 0.5, -1L, -1L, -1L))
    assert(got("b") == Seq("BOTH", 1L, "IN->IN", "NO_CROSS->CROSS", 0.5, 0L, -1L, -1L))
    assert(
      got("c") == Seq("FROM_ONLY", null, "IN->ABSENT", "NO_CROSS->ABSENT", null, null, null, null)
    )
    assert(got("d") == Seq("BOTH", 0L, "OUT->IN", "NO_CROSS->CROSS", null, 2L, 2L, 1L))
    assert(
      got("e") == Seq("TO_ONLY", null, "ABSENT->IN", "ABSENT->NO_CROSS", null, null, null, null)
    )
    assert(
      transitions
        .select("fromCommunityId", "toCommunityId", "sharedResearchers", "fromCommunitySize")
        .collect()
        .map(_.toSeq)
        .toSet == Set(Seq("E1", "L1", 2L, 3L), Seq("E2", "L2", 1L, 1L))
    )
    val c = Temporal.comparison(changes).head()
    def v(n: String) = c.get(c.fieldIndex(n))
    assert(
      Seq("researchersFrom", "researchersTo", "researchersInBoth", "researchersFromOnly")
        .map(v) == Seq(4L, 4L, 3L, 1L)
    )
    assert(v("researchersToOnly") == 1L && v("researchersRetainingACoauthor") == 2L)
    assert(v("medianCommunityRetention") == 0.5 && v("becameCrossCommunity") == 2L)
    assert(v("largestComponentInToIn") == 2L && v("largestComponentOutToIn") == 1L)
    assert(math.abs(c.getAs[Double]("fromMeanDistinctCoauthorCount") - 4.0 / 3) < 1e-12)
    assert(math.abs(c.getAs[Double]("toMeanDistinctCoauthorCount") - 5.0 / 3) < 1e-12)
  }

  // ---- Job 10 --------------------------------------------------------------------------------------
  // One window "all" (2000-2020). Works: w1 "Graph Mining" (2010, KDD, topic t1) by r1, r2, r3;
  // w2 "Data Lakes" (2012, VLDB) by r1, r2. So team(w1) - 1 = 2 and team(w2) - 1 = 1:
  //   r1: P 2, C 2, T 3, A 1.5, M 2, W = jpc(r1,r2) 2 + jpc(r1,r3) 1 = 3; r3: P 1 (no profile).
  // The triangle is one component (the largest) and one community cA of 3 (density 3/3 = 1).
  private def world =
    val names = df(
      "researcherId STRING, displayName STRING",
      Seq("r1", "Ann Lee"),
      Seq("r2", "Bo Chen"),
      Seq("r3", "Cy Diaz")
    )
    def m(id: String, p: Long, t: Long, a: Double, w: Double) =
      Seq("all", id, p, 2L, t, a, 2L, w, 2, "comp1", 3L)
    val hop = Row(1, "r3", "r2", "e23", 1L, 2010, 2010, Seq("w1"))
    Summaries.In(
      researchers = names,
      metrics = df(
        "window STRING, researcherId STRING, publicationCount BIGINT, distinctCoauthorCount BIGINT, totalCoauthorOccurrences BIGINT, averageCoauthorsPerPaper DOUBLE, maximumCoauthorsOnOnePaper BIGINT, weightedDegree DOUBLE, coreness INT, componentId STRING, componentSize BIGINT",
        m("r1", 2, 3, 1.5, 3.0),
        m("r2", 2, 3, 1.5, 3.0),
        m("r3", 1, 2, 2.0, 2.0)
      ),
      memberships = df(
        "window STRING, researcherId STRING, communityId STRING, communitySize BIGINT, graphDegree BIGINT, crossCommunityDegree BIGINT, communitiesConnected BIGINT, participationCoefficient DOUBLE",
        Seq("all", "r1", "cA", 3L, 2L, 0L, 1L, 0.0),
        Seq("all", "r2", "cA", 3L, 2L, 0L, 1L, 0.0),
        Seq("all", "r3", "cA", 3L, 2L, 0L, 1L, 0.0)
      ),
      largest = df("window STRING, componentId STRING", Seq("all", "comp1")),
      edges = df(
        "window STRING, researcherA STRING, researcherB STRING, edgeId STRING, jointPublicationCount BIGINT",
        Seq("all", "r1", "r2", "e12", 2L),
        Seq("all", "r1", "r3", "e13", 1L),
        Seq("all", "r2", "r3", "e23", 1L)
      ),
      dblpWorks = df(
        "window STRING, researcherId STRING, workId STRING, year INT, title STRING, venueId STRING",
        Seq("all", "r1", "w1", 2010, "Graph Mining", "v1"),
        Seq("all", "r2", "w1", 2010, "Graph Mining", "v1"),
        Seq("all", "r3", "w1", 2010, "Graph Mining", "v1"),
        Seq("all", "r1", "w2", 2012, "Data Lakes", "v2"),
        Seq("all", "r2", "w2", 2012, "Data Lakes", "v2")
      ),
      works = df("workId STRING, title STRING", Seq("w1", "Graph Mining"), Seq("w2", "Data Lakes")),
      venues = df("venueId STRING, name STRING", Seq("v1", "KDD"), Seq("v2", "VLDB")),
      topics = df(
        "topicId STRING, name STRING, subfield STRING, field STRING",
        Seq("t1", "Graph Analytics", "Information Systems", "Computer Science")
      ),
      workTopics = df("workId STRING, topicId STRING", Seq("w1", "t1")),
      institutions = df(
        "researcherId STRING, institutionId STRING, name STRING, evidenceCount INT",
        Seq("r1", "i1", "Uni A", 2),
        Seq("r1", "i2", "Lab B", 1)
      ),
      communities = df(
        "window STRING, windowStartYear INT, windowEndYear INT, communityId STRING, communitySize BIGINT, internalEdges BIGINT, crossCommunityEdges BIGINT",
        Seq("all", 2000, 2020, "cA", 3L, 3L, 0L)
      ),
      crossEdges = df("window STRING, communityA STRING, communityB STRING"),
      paths = df(
        "pairId STRING, window STRING, windowStartYear INT, windowEndYear INT, sourceResearcherId STRING, targetResearcherId STRING, status STRING, pathLength INT, maxDepth INT, componentId STRING, hops ARRAY<STRUCT<hop: INT, fromResearcherId: STRING, toResearcherId: STRING, edgeId: STRING, jointPublicationCount: BIGINT, firstCollaborationYear: INT, lastCollaborationYear: INT, supportingWorkIds: ARRAY<STRING>>>",
        Seq("p1", "all", 2000, 2020, "r3", "r2", "FOUND", 1, 6, "comp1", Seq(hop)),
        Seq("p2", "all", 2000, 2020, "r1", "r3", "BEYOND_DEPTH", null, 6, "comp1", Seq()),
        Seq("p3", "all", 2000, 2020, "r1", "rX", "UNKNOWN_RESEARCHER", null, 6, null, Seq())
      ),
      windows = df(
        "window STRING, windowStartYear INT, windowEndYear INT",
        Seq("all", 2000, 2020)
      )
    )

  private def texts(d: DataFrame) =
    d.select("subjectId", "text").collect().map(r => r.getString(0) -> r.getString(1)).toMap

  test("job 10: profile, community, path, and topic texts are the hand-rendered templates") {
    val in = world
    val profiles = Summaries.profiles(in, "all", 2)
    assert(texts(profiles).keySet == Set("r1", "r2")) // r3 has one publication
    assert(
      texts(profiles)("r1") ==
        "Ann Lee (r1), 2000-2020: 2 publications in the DBLP subset, first in 2010 and last in 2012. " +
        "Distinct coauthors (C_r) 2; total coauthor occurrences (T_r) 3; average coauthors per paper (A_r) 1.50; " +
        "most coauthors on one paper (M_r) 2; weighted degree (W_r) 3. " +
        "Coreness 2; connected component of 3 researchers (the largest). " +
        "Community of 3 researchers; 0 coauthors in other communities; 1 communities connected; participation coefficient 0.000. " +
        "Most frequent coauthors: Bo Chen (2 joint works); Cy Diaz (1 joint works). " +
        "Frequent topics: Graph Analytics (1 works). Frequent venues: KDD (1 works); VLDB (1 works). " +
        "Institutions on record (any year): Uni A; Lab B. " +
        "Recent works: \"Data Lakes\" (2012); \"Graph Mining\" (2010)."
    )
    val r1 = profiles.where(col("subjectId") === "r1").head()
    val text = r1.getAs[String]("text")
    assert(r1.getAs[String]("textHash") == StableIds.textHash(text))
    assert(
      r1.getAs[String]("summaryId") ==
        StableIds.chunkId(
          "r1|all",
          Summaries.TemplateVersion,
          Summaries.ProfileDoc,
          None,
          StableIds.textHash(text)
        )
    )
    assert(
      r1.getAs[scala.collection.Seq[String]]("supportingIds").toSet ==
        Set("r1", "comp1", "cA", "r2", "r3", "e12", "e13", "t1", "v1", "v2", "i1", "i2", "w1", "w2")
    )
    val facts = ujson.read(r1.getAs[String]("facts"))
    assert(facts("publicationCount").num == 2 && facts("weightedDegree").num == 3)

    assert(
      texts(Summaries.communities(in, 3)) == Map(
        "cA" -> ("Collaboration community cA, 2000-2020: 3 researchers with 3 coauthor edges inside (density 1.000) " +
          "and 0 edges to 0 other communities; its members have 2 publications in the window. " +
          "Most connected members: Ann Lee (2 coauthors); Bo Chen (2 coauthors); Cy Diaz (2 coauthors). " +
          "Frequent topics: Graph Analytics (1 works). Frequent venues: KDD (1 works); VLDB (1 works).")
      )
    )
    assert(Summaries.communities(in, 4).count() == 0) // below the size threshold

    assert(
      texts(Summaries.paths(in)) == Map(
        "p1" -> ("In 2000-2020, Cy Diaz and Bo Chen are joined by a shortest coauthor path of 1 hops: " +
          "Cy Diaz -> Bo Chen (1 joint works, 2010-2010, e.g. \"Graph Mining\")."),
        "p2" -> ("In 2000-2020, Ann Lee and Cy Diaz are in the same connected component, " +
          "but no coauthor path of at most 6 hops joins them."),
        "p3" -> ("In 2000-2020, Ann Lee and rX: at least one of them has no DBLP publication " +
          "in the window, so no path was searched.")
      )
    )
    assert(
      texts(Summaries.topics(in)) == Map(
        "t1" -> ("Topic Graph Analytics (t1; field Computer Science, subfield Information Systems), " +
          "2000-2020: 1 works by 3 researchers in the DBLP subset. " +
          "Most active researchers: Ann Lee (1 works); Bo Chen (1 works); Cy Diaz (1 works). " +
          "Frequent venues: KDD (1 works).")
      )
    )
  }

  // ---- Job 11: the export's provenance check ------------------------------------------------------
  test("the export refuses a table without schemaVersion and provenance, or with another run's") {
    val tmp = Files.createTempDirectory("sc-spark-export-check")
    val prov = "STRUCT<schemaVersion: STRING, sourceDataset: STRING, sourceRecordId: STRING, " +
      "snapshot: STRING, runId: STRING, policyVersion: STRING, modelVersion: STRING>"
    def p(r: String) = Row("provenance-v1", "DERIVED", "x1", "2026-10-03", r, "graph-v1", null)
    def written(name: String, schema: String, rows: Seq[Any]*): (String, String) =
      val path = tmp.resolve(name).toString
      df(schema, rows*).write.parquet(path)
      (path, schema)
    def check(t: (String, String), rowCount: Long = 1) =
      TemporalSummaryExportJob.checkedRowCount(spark, t._1, t._2, rowCount, "run-a", "2026-10-03")
    val bare = written("bare", "id STRING", Seq("x1"))
    val noVersion = written("no-version", s"id STRING, provenance $prov", Seq("x1", p("run-a")))
    val full = s"id STRING, schemaVersion STRING, provenance $prov"
    val otherRun = written("other-run", full, Seq("x1", "spark-t-v1", p("run-b")))
    val good = written("good", full, Seq("x1", "spark-t-v1", p("run-a")))
    val e = intercept[IllegalArgumentException](check(bare))
    assert(e.getMessage.contains("no schemaVersion") && e.getMessage.contains("no provenance"))
    intercept[IllegalArgumentException](check(noVersion))
    intercept[IllegalArgumentException](check(otherRun))
    // An empty table is checked against the schema its manifest recorded.
    intercept[IllegalArgumentException](check(("unused", "id STRING"), 0))
    assert(check(good) == 1)
    assert(check(("unused", full), 0) == 0)
  }

  // ---- The end-to-end driver on the jobs 1 to 8 fixture -------------------------------------------
  test(
    "the pipeline runs jobs 1 to 11, and the load manifest lists every table with checked counts"
  ) {
    val tmp = Files.createTempDirectory("sc-spark-pipeline")
    val cfg = ConfigLoader.load(Map.empty).toOption.get
    val base = SparkSettings
      .parse(
        Seq(
          "--input",
          Fixture.writeRun(tmp.resolve("hadoop")).toString,
          "--out",
          tmp.resolve("out").toString,
          "--code-version",
          "test",
          "--master",
          "local[2]",
          "--shuffle-partitions",
          "2",
          "--checkpoint-dir",
          tmp.resolve("ck").toString,
          "--min-free-disk-gib",
          "0",
          "--cc-max-iterations",
          "30",
          "--sample-seed",
          "1"
        )
      )
      .toOption
      .get
    val r = PipelineJob.run(spark, base, cfg)
    val m = r.manifest
    assert(m("stage").str == PipelineJob.Stage)
    val stages = m("stages").arr.map(_("stage").str).toSeq
    assert(
      stages == Seq(
        CanonicalGraphJob.Stage,
        ResearcherAnalyticsJob.Stage,
        CommunityPathJob.Stage,
        TemporalSummaryExportJob.Stage
      )
    )
    val exportDir = m("stages").arr.last("runDir").str
    val lm = ujson.read(Files.readString(Path.of(m("loadManifest").str), UTF_8))
    val tables = lm("tables").arr
    assert(tables.size == 15 + 3 + 6 + 6) // the four stages' tables
    assert(tables.forall(t => t("rowCount").num == t("verifiedRowCount").num))
    // Rulings 21, 22, 24: per-table schema version and code version; run-level lineage.
    assert(
      tables.forall(t => t("schemaVersion").str == s"spark-${t("table").str.replace('/', '-')}-v1")
    )
    assert(tables.forall(t => t("codeVersion").str == "test"))
    assert(lm("codeVersion").str == "test" && m("codeVersion").str == "test")
    assert(lm("inputHadoopRunId").str == Fixture.HadoopRunId)
    assert(lm("matchPolicyVersion").str == "er-v2")
    assert(m("lineage")("inputHadoopRunId").str == Fixture.HadoopRunId)
    assert(m("lineage")("matchPolicyVersion").str == "er-v2")
    assert(lm("stores")("s3vectors").arr.map(_.str).toSeq == Seq("semantic-summaries"))
    assert(lm("stores")("neptune").arr.map(_.str).toSet.contains("edges/type=coauthored_with"))
    val counts = m("stages").arr.last("integrityViolationsByCheck").arr
    assert(counts.isEmpty, counts)

    // Every researcher with >= 2 all-years publications has a profile; every community of >= 3 a
    // summary; every selected pair a path summary.
    val raDir = m("stages").arr(1)("runDir").str
    val cpDir = m("stages").arr(2)("runDir").str
    val summaries = spark.read.parquet(s"$exportDir/summaries/semantic-summaries")
    def docs(d: String) = summaries.where(col("documentType") === d)
    val expectedProfiles = spark.read
      .parquet(s"$raDir/analytics/researcher-metrics")
      .where(col("window") === "all-years" && col("publicationCount") >= 2)
      .count()
    assert(expectedProfiles > 0 && docs(Summaries.ProfileDoc).count() == expectedProfiles)
    assert(
      docs(Summaries.CommunityDoc).count() ==
        spark.read.parquet(s"$cpDir/analytics/communities").where(col("communitySize") >= 3).count()
    )
    assert(
      docs(Summaries.PathDoc).count() == spark.read.parquet(s"$cpDir/paths/path-evidence").count()
    )
    // Window summary researchers equal the researcher-metrics rows of each window.
    val perWindow =
      m("perWindow").arr.map(w => w("window").str -> w("researchers").num.toLong).toMap
    val metricRows = spark.read
      .parquet(s"$raDir/analytics/researcher-metrics")
      .groupBy("window")
      .count()
      .collect()
      .map(x => x.getString(0) -> x.getLong(1))
      .toMap
    assert(perWindow == metricRows)

    // A rerun of jobs 9 to 11 on the same input: next attempt, identical summaries.
    val again = TemporalSummaryExportJob.run(spark, base.copy(input = cpDir), cfg)
    assert(again.attempt == 2 && again.runDir != exportDir)
    def ids(dir: String) = spark.read
      .parquet(s"$dir/summaries/semantic-summaries")
      .select("summaryId")
      .collect()
      .map(_.getString(0))
      .toSet
    assert(ids(again.runDir) == ids(exportDir))
  }
