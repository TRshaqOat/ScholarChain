package scholarchain.spark

import org.apache.spark.sql.types.*
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.{ConfigLoader, TimeWindow}

import java.nio.file.Files

/** Jobs 3, 4, and 8 on small graphs. Every expected value is worked out by hand in the comments. */
class ResearcherAnalyticsSpec extends AnyFunSuite:
  private lazy val spark = TestSpark.spark
  private val Windows =
    Seq(
      TimeWindow("all", 2000, 2026),
      TimeWindow("early", 2000, 2014),
      TimeWindow("late", 2015, 2026)
    )

  private def df(schema: String, rows: Seq[Any]*): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(rows.map(r => Row.fromSeq(r))*),
      StructType.fromDDL(schema)
    )
  private def edges(rows: (String, String, String)*) =
    df("window STRING, a STRING, b STRING", rows.map((w, a, b) => Seq(w, a, b))*)
  private def nodes(rows: (String, String)*) =
    df("window STRING, node STRING", rows.map((w, n) => Seq(w, n))*)

  // ---- Job 3: a five-node graph ------------------------------------------------------------------
  // DBLP works:  w1 2005 {A, B, C}   w2 2010 {A, B}   w3 2016 {A, D}   w4 2016 {B, D, E}   w5 2020 {E}
  // Not counted: w6 2012 {C} OpenAlex only; w7 (null year) {A}.
  // Coauthor edges as job 2 builds them (pair: joint count):
  //   all:   A-B 2, A-C 1, B-C 1, A-D 1, B-D 1, B-E 1, D-E 1
  //   early: A-B 2, A-C 1, B-C 1
  //   late:  A-D 1, B-D 1, B-E 1, D-E 1
  test("job 3: P, C, T, A, M, W and the M_r work IDs on a five-node graph, per window") {
    val dblp = Seq("DBLP")
    def a(r: String, w: String, y: Integer, src: Seq[String] = dblp) = Seq(r, s"dblp:t/$w", y, src)
    val authored = df(
      "researcherId STRING, workId STRING, year INT, sourceDatasets ARRAY<STRING>",
      a("A", "w1", 2005),
      a("B", "w1", 2005),
      a("C", "w1", 2005),
      a("A", "w2", 2010),
      a("B", "w2", 2010),
      a("A", "w3", 2016),
      a("D", "w3", 2016),
      a("B", "w4", 2016, Seq("DBLP", "OPENALEX")),
      a("D", "w4", 2016),
      a("E", "w4", 2016),
      a("E", "w5", 2020),
      a("C", "w6", 2012, Seq("OPENALEX")),
      a("A", "w7", null)
    )
    def e(w: String, x: String, y: String, n: Long) = Seq(w, x, y, n)
    val coauthor = df(
      "window STRING, researcherA STRING, researcherB STRING, jointPublicationCount LONG",
      e("all", "A", "B", 2),
      e("all", "A", "C", 1),
      e("all", "B", "C", 1),
      e("all", "A", "D", 1),
      e("all", "B", "D", 1),
      e("all", "B", "E", 1),
      e("all", "D", "E", 1),
      e("early", "A", "B", 2),
      e("early", "A", "C", 1),
      e("early", "B", "C", 1),
      e("late", "A", "D", 1),
      e("late", "B", "D", 1),
      e("late", "B", "E", 1),
      e("late", "D", "E", 1)
    )
    val got = ResearcherStats(authored, coauthor, CoauthorGraph.windows(spark, Windows))
      .collect()
      .map { r =>
        (r.getAs[String]("window"), r.getAs[String]("researcherId")) -> (
          r.getAs[Long]("publicationCount"),
          r.getAs[Long]("distinctCoauthorCount"),
          r.getAs[Long]("totalCoauthorOccurrences"),
          r.getAs[Double]("averageCoauthorsPerPaper"),
          r.getAs[Long]("maximumCoauthorsOnOnePaper"),
          r.getAs[scala.collection.Seq[String]]("maximumTeamWorkIds").toSeq.map(_.drop(7)),
          r.getAs[Double]("weightedDegree")
        )
      }
      .toMap
    // (P, C, T, A, M, M_r works, W)
    val expected = Map(
      ("all", "A") -> (3L, 3L, 4L, 4.0 / 3, 2L, Seq("w1"), 4.0), // n = 2, 1, 1
      ("all", "B") -> (3L, 4L, 5L, 5.0 / 3, 2L, Seq("w1", "w4"), 5.0), // n = 2, 1, 2
      ("all", "C") -> (1L, 2L, 2L, 2.0, 2L, Seq("w1"), 2.0),
      ("all", "D") -> (2L, 3L, 3L, 1.5, 2L, Seq("w4"), 3.0), // n = 1, 2
      ("all", "E") -> (2L, 2L, 2L, 1.0, 2L, Seq("w4"), 2.0), // n = 2, 0
      ("early", "A") -> (2L, 2L, 3L, 1.5, 2L, Seq("w1"), 3.0),
      ("early", "B") -> (2L, 2L, 3L, 1.5, 2L, Seq("w1"), 3.0),
      ("early", "C") -> (1L, 2L, 2L, 2.0, 2L, Seq("w1"), 2.0),
      ("late", "A") -> (1L, 1L, 1L, 1.0, 1L, Seq("w3"), 1.0),
      ("late", "B") -> (1L, 2L, 2L, 2.0, 2L, Seq("w4"), 2.0),
      ("late", "D") -> (2L, 3L, 3L, 1.5, 2L, Seq("w4"), 3.0),
      ("late", "E") -> (2L, 2L, 2L, 1.0, 2L, Seq("w4"), 2.0)
    )
    assert(got == expected)
  }

  // ---- Job 4: two components plus an isolated node ------------------------------------------------
  // Window x: chain n7-n5-n2-n3 (root n2), pair n4-n6 (root n4), isolated n9.
  // Window y: pair n9-n7 (root n7), isolated n1; n7 and n9 are in another component here.
  // Labels changed per round: 1st n7->n5, n5->n2, n3->n2, n6->n4, n9(y)->n7 = 5;
  // 2nd n7 -> n2 = 1; 3rd none = 0.
  test("job 4: min-label propagation finds two components and an isolated node per window") {
    TestSpark.freshCheckpointDir()
    val (labels, changed) = MinLabelComponents(
      nodes(("x", "n9"), ("y", "n1"), ("x", "n2")),
      edges(
        ("x", "n7", "n5"),
        ("x", "n2", "n5"),
        ("x", "n3", "n2"),
        ("x", "n6", "n4"),
        ("y", "n9", "n7")
      ),
      10
    )
    val got = labels.collect().map(r => (r.getString(0), r.getString(1)) -> r.getString(2)).toMap
    assert(
      got == Map(
        ("x", "n7") -> "n2",
        ("x", "n5") -> "n2",
        ("x", "n2") -> "n2",
        ("x", "n3") -> "n2",
        ("x", "n4") -> "n4",
        ("x", "n6") -> "n4",
        ("x", "n9") -> "n9",
        ("y", "n9") -> "n7",
        ("y", "n7") -> "n7",
        ("y", "n1") -> "n1"
      )
    )
    assert(changed == Seq(5L, 1L, 0L))
    val e = intercept[IllegalArgumentException](
      MinLabelComponents(nodes(), edges(("x", "n7", "n5"), ("x", "n2", "n5")), 1)
    )
    assert(e.getMessage.contains("did not converge in 1 rounds"))
  }

  // ---- Job 8: a 4-clique attached to a path ------------------------------------------------------
  // Window x: clique {a, b, c, d}, path d-e-f-g, isolated h. Coreness a, b, c, d = 3; e, f, g = 1;
  // h = 0. Window y: triangle {p, q, r} plus s attached to p: p, q, r = 2; s = 1.
  // Peeling (k, nodes peeled): (0, h) (1, g s) (1, f) (1, e) (2, p q r) (3, a b c d).
  private def kcoreInput = (
    nodes(("x", "h")),
    edges(
      ("x", "a", "b"),
      ("x", "a", "c"),
      ("x", "a", "d"),
      ("x", "b", "c"),
      ("x", "b", "d"),
      ("x", "c", "d"),
      ("x", "d", "e"),
      ("x", "e", "f"),
      ("x", "f", "g"),
      ("y", "p", "q"),
      ("y", "q", "r"),
      ("y", "r", "p"),
      ("y", "s", "p")
    )
  )

  test("job 8: k-core peeling gives the known coreness of a 4-clique attached to a path") {
    TestSpark.freshCheckpointDir()
    val (n, e) = kcoreInput
    val (core, log) = KCore(n, e, 10)
    val got =
      core.collect().map(r => (r.getString(0), r.getString(1)) -> (r.getLong(2), r.getInt(3))).toMap
    // (degree, coreness)
    assert(
      got == Map(
        ("x", "a") -> (3L, 3),
        ("x", "b") -> (3L, 3),
        ("x", "c") -> (3L, 3),
        ("x", "d") -> (4L, 3),
        ("x", "e") -> (2L, 1),
        ("x", "f") -> (2L, 1),
        ("x", "g") -> (1L, 1),
        ("x", "h") -> (0L, 0),
        ("y", "p") -> (3L, 2),
        ("y", "q") -> (2L, 2),
        ("y", "r") -> (2L, 2),
        ("y", "s") -> (1L, 1)
      )
    )
    assert(log == Seq(0 -> 1L, 1 -> 2L, 1 -> 1L, 1 -> 1L, 2 -> 3L, 3 -> 4L))
  }

  test("job 8: a level that needs more rounds than the cap fails the run") {
    TestSpark.freshCheckpointDir()
    val (n, e) = kcoreInput
    val err = intercept[IllegalArgumentException](KCore(n, e, 2)) // level 1 needs 3 rounds
    assert(err.getMessage.contains("k-core level 1"))
  }

  // ---- The job end to end on the jobs 1 and 2 fixture run -----------------------------------------
  test("the job writes metrics, components, and a manifest with no integrity violations") {
    val tmp = Files.createTempDirectory("sc-spark-analytics")
    val cfg = ConfigLoader.load(Map.empty).toOption.get
    val base = SparkSettings(
      input = Fixture.writeRun(tmp.resolve("hadoop")).toString,
      out = tmp.resolve("out").toString,
      codeVersion = "test",
      attempt = None,
      master = "local[2]",
      shufflePartitions = 2,
      checkpointDir = tmp.resolve("ck").toString,
      canonicalPolicyVersion = "canon-v1",
      ccMaxIterations = 30,
      minFreeDiskGiB = 0,
      pairs = None,
      communityMaxIterations = 10,
      pathMaxDepth = 6,
      pathSamplePairs = 50,
      pathSampleSources = 20,
      sampleSeed = 1,
      summaryMinPublications = 2,
      summaryMinCommunitySize = 3
    )
    val graph = CanonicalGraphJob.run(spark, base, cfg)
    val r = ResearcherAnalyticsJob.run(spark, base.copy(input = graph.runDir), cfg)
    val m = r.manifest
    assert(m("stage").str == ResearcherAnalyticsJob.Stage)
    assert(m("input")("canonicalGraphRunId").str == graph.runId)
    val tables = m("tables").arr.map(t => t("name").str -> t).toMap
    assert(
      tables.keySet == Set(
        "analytics/components",
        "analytics/researcher-metrics",
        "analytics/integrity-violations"
      )
    )
    assert(tables("analytics/integrity-violations")("rowCount").num == 0)
    val metrics = spark.read.parquet(s"${r.runDir}/analytics/researcher-metrics")
    val components = spark.read.parquet(s"${r.runDir}/analytics/components")
    val edges = spark.read.parquet(s"${graph.runDir}/graph/coauthor-edges")
    import org.apache.spark.sql.functions.*
    for w <- cfg.graph.windows.map(_.name) do
      val mw = metrics.where(col("window") === w)
      val sizes = components.where(col("window") === w).agg(sum("componentSize")).head().get(0)
      assert(Option(sizes).fold(0L)(_.asInstanceOf[Long]) == mw.count(), w)
      val degreeSum = mw.agg(sum("distinctCoauthorCount")).head().get(0)
      assert(
        Option(degreeSum)
          .fold(0L)(_.asInstanceOf[Long]) == 2 * edges.where(col("window") === w).count(),
        w
      )
    assert(metrics.count() > 0 && metrics.where(col("coreness").isNull).count() == 0)
    assert(m("counts")("perWindow").arr.size == cfg.graph.windows.size)
    assert(m("counts")("integrityViolationsByCheck").arr.isEmpty)
    assert(m("counts")("mergedDblpWorkChecks")("byCheck").arr.isEmpty)
    assert(
      m("inputs").arr.map(_("path").str.split("/").takeRight(2).mkString("/")).toSet == Set(
        "canonical/researchers",
        "canonical/authored",
        "canonical/works",
        "graph/coauthor-edges"
      )
    )
    assert(Files.notExists(tmp.resolve("ck").resolve(r.runId))) // checkpoints removed
  }
