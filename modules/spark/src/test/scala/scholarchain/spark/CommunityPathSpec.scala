package scholarchain.spark

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Jobs 5, 6, and 7 on small graphs. Every expected value is worked out by hand in the comments. */
class CommunityPathSpec extends AnyFunSuite:
  private lazy val spark = TestSpark.spark

  private def df(schema: String, rows: Seq[Any]*): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(rows.map(r => Row.fromSeq(r))*),
      StructType.fromDDL(schema)
    )
  private def nodes(rows: (String, String)*) =
    df("window STRING, node STRING", rows.map((w, n) => Seq(w, n))*)
  private def edges(rows: (String, String, String)*) =
    df("window STRING, a STRING, b STRING", rows.map((w, a, b) => Seq(w, a, b))*)

  /** Canonical coauthor edges: edgeId e:<a>:<b>, one supporting work w:<a>:<b>. */
  private def coauthor(rows: (String, String, String)*) = df(
    "window STRING, researcherA STRING, researcherB STRING, edgeId STRING, jointPublicationCount BIGINT, firstCollaborationYear INT, lastCollaborationYear INT, representativeWorkIds ARRAY<STRING>",
    rows.map { (w, x, y) =>
      val (a, b) = if x < y then (x, y) else (y, x)
      Seq(w, a, b, s"e:$a:$b", 1L, 2010, 2010, Seq(s"w:$a:$b"))
    }*
  )

  // ---- Job 5: bounded paths -----------------------------------------------------------------------
  // Window x: path p1-p2-...-p9 (8 hops); pair q1-q2 (another component); diamond s-a-t, s-b-t.
  // Depth limit 6. Pairs and their hand-worked outcome:
  //   p1->p4 FOUND 3 [p1 p2 p3 p4]     p4->p1 FOUND 3 [p4 p3 p2 p1] (hops reuse canonical edges)
  //   p1->p7 FOUND 6 (exactly the limit)  p1->p9 BEYOND_DEPTH (distance 8 > 6)
  //   p2->q1 UNREACHABLE (other component)  p1->zz UNKNOWN_RESEARCHER (zz is not a node)
  //   p3->p3 FOUND 0 [p3], no hops     s->t FOUND 2 [s a t] (t's parents a and b tie; a < b)
  // BFS sources p1, p4, s, p3; p3's only target is itself (reached at distance 0), so it never
  // expands. New nodes per round:
  //   1: p1->p2; p4->p3,p5; s->a,b = 5      2: p1->p3; p4->p2,p6; s->t = 4
  //   3: p1->p4; p4->p1,p7 = 3 (s and p4 are done after this)
  //   4: p1->p5 = 1   5: p1->p6 = 1   6: p1->p7 = 1, then the depth limit stops the search.
  private val chain = (1 to 8).map(i => ("x", s"p$i", s"p${i + 1}"))
  private val graphX =
    chain ++ Seq(
      ("x", "q1", "q2"),
      ("x", "s", "a"),
      ("x", "a", "t"),
      ("x", "s", "b"),
      ("x", "b", "t")
    )

  test(
    "job 5: bounded BFS finds paths, flags unreachable and beyond-depth pairs, keeps hop works"
  ) {
    TestSpark.freshCheckpointDir()
    val (labels, _) = MinLabelComponents(nodes(), edges(graphX*), 20)
    val membership = labels.select(col("window"), col("node"), col("root").as("componentId"))
    val pairs = df(
      "pairId STRING, window STRING, source STRING, target STRING",
      Seq("P14", "x", "p1", "p4"),
      Seq("P41", "x", "p4", "p1"),
      Seq("P17", "x", "p1", "p7"),
      Seq("P19", "x", "p1", "p9"),
      Seq("P2q", "x", "p2", "q1"),
      Seq("P1z", "x", "p1", "zz"),
      Seq("P33", "x", "p3", "p3"),
      Seq("Pst", "x", "s", "t")
    )
    val (paths, reached) = ShortestPaths.paths(pairs, membership, coauthor(graphX*), 6)
    val got = paths
      .collect()
      .map { r =>
        r.getAs[String]("pairId") -> (
          r.getAs[String]("status"),
          Option(r.getAs[Integer]("pathLength")).map(_.toInt),
          Option(r.getAs[scala.collection.Seq[String]]("researcherIds")).map(_.toSeq),
          Option(r.getAs[String]("componentId"))
        )
      }
      .toMap
    val p = (1 to 9).map(i => s"p$i")
    assert(
      got == Map(
        "P14" -> ("FOUND", Some(3), Some(p.take(4)), Some("p1")),
        "P41" -> ("FOUND", Some(3), Some(p.take(4).reverse), Some("p1")),
        "P17" -> ("FOUND", Some(6), Some(p.take(7)), Some("p1")),
        "P19" -> ("BEYOND_DEPTH", None, None, Some("p1")),
        "P2q" -> ("UNREACHABLE", None, None, None),
        "P1z" -> ("UNKNOWN_RESEARCHER", None, None, None),
        "P33" -> ("FOUND", Some(0), Some(Seq("p3")), Some("p1")),
        "Pst" -> ("FOUND", Some(2), Some(Seq("s", "a", "t")), Some("a"))
      )
    )
    assert(reached == Seq(5L, 4L, 3L, 1L, 1L, 1L))
    // Hops of p4 -> p1, in order, each with the canonical edge and its supporting work.
    val hops = paths
      .where(col("pairId") === "P41")
      .select(explode(col("hops")).as("h"))
      .select("h.hop", "h.fromResearcherId", "h.toResearcherId", "h.edgeId", "h.supportingWorkIds")
      .collect()
      .map(r => (r.getInt(0), r.getString(1), r.getString(2), r.getString(3), r.getSeq[String](4)))
      .toSeq
    assert(
      hops == Seq(
        (1, "p4", "p3", "e:p3:p4", Seq("w:p3:p4")),
        (2, "p3", "p2", "e:p2:p3", Seq("w:p2:p3")),
        (3, "p2", "p1", "e:p1:p2", Seq("w:p1:p2"))
      )
    )
    val noHops = paths.where(col("status") =!= "FOUND" && col("hops").isNotNull).count()
    assert(noHops == 0)
    assert(paths.where(col("pairId") === "P33").select(size(col("hops"))).head().getInt(0) == 0)
  }

  // Path p1-p2-p3-p4, sources p1 and p2. Distances: from p1 1, 2, 3; from p2 1 (p1), 1 (p3), 2.
  // Histogram {1: 3, 2: 2, 3: 1}, 6 pairs; mean 10/6; cumulative 3/6, 5/6, 6/6, so the median is 1
  // and the 90% effective diameter 3. Per-source means 2 and 4/3: sample sd (2/3)/sqrt(2), standard
  // error that / sqrt(2) = 1/3. New nodes per round: 3 (p2; p1, p3), 2 (p3; p4), 1 (p4), 0.
  test("job 5: sampled BFS distances give the hand-computed path-length estimate") {
    TestSpark.freshCheckpointDir()
    val sym =
      MinLabelComponents.symmetric(edges(("x", "p1", "p2"), ("x", "p2", "p3"), ("x", "p3", "p4")))
    val sources = df("window STRING, source STRING", Seq("x", "p1"), Seq("x", "p2"))
    val (d, reached) = ShortestPaths.sampledDistances(sources, sym, 10)
    assert(reached == Seq(3L, 2L, 1L, 0L))
    val e = ShortestPaths.estimate(d).head()
    assert(e.getAs[Long]("reachablePairs") == 6L)
    assert(math.abs(e.getAs[Double]("meanDistance") - 10.0 / 6) < 1e-12)
    assert(math.abs(e.getAs[Double]("meanDistanceStdError") - 1.0 / 3) < 1e-12)
    assert(e.getAs[Int]("medianDistance") == 1)
    assert(e.getAs[Int]("effectiveDiameter90") == 3)
    assert(e.getAs[Int]("maxObservedDistance") == 3)
    assert(
      e.getAs[scala.collection.Seq[Row]]("distanceHistogram").map(r => r.getInt(0) -> r.getLong(1))
        == Seq(1 -> 3L, 2 -> 2L, 3 -> 1L)
    )
    val err = intercept[IllegalArgumentException](ShortestPaths.sampledDistances(sources, sym, 3))
    assert(err.getMessage.contains("did not finish in 3 rounds"))
  }

  // ---- Jobs 6 and 7: two cliques joined through one bridge researcher, plus a tie-break star --------
  // Window w: cliques A = {a1..a4} and B = {b1..b4}; bridge x coauthors a1, a2, b1, b2; isolated i.
  // Votes = neighbours' labels plus the node's own; ties go to the smallest label.
  //   Round 1 (all labels distinct, so every vote is a tie): a2, a3, a4, x -> a1; b2, b3, b4 -> b1
  //   (a1, b1, i keep their own) = 7 changes.
  //   Round 2: x sees a1 (a1, a2, self) 3 vs b1 (b1, b2) 2 -> a1; b1 sees b1 4 vs a1 1; no change.
  //   Communities {a1..a4, x} (label a1), {b1..b4} (label b1), {i}.
  // Bridge measures (degree k, cross-community degree, communities connected, participation 1 - sum
  // (k_s/k)^2):
  //   x: k 4 (a1 a2 | b1 b2), cross 2, connected 2, 1 - (1/4 + 1/4) = 0.5
  //   b1, b2: k 4 (3 in B, x in A), cross 1, connected 2, 1 - (9/16 + 1/16) = 0.375
  //   a1, a2: k 4, all in A: 0, 1, 0.0    a3, a4: k 3: 0, 1, 0.0    b3, b4: k 3: 0, 1, 0.0
  //   i: k 0: 0, 0, 0.0 (by convention)
  // Window v (tie-break): star m with leaves k, n, z.
  //   Round 1: m sees {m, k, n, z} all once -> k; k sees {k, m} -> k; n {n, m} -> m; z {z, m} -> m:
  //   3 changes (m, n, z). Round 2: m sees k (self, k) 2 vs m (n, z) 2 -> k; n sees m (self) 1 vs
  //   k (m) 1 -> k; z likewise -> k: 2 changes. Round 3: all k, 0 changes.
  private val cliques =
    Seq("a", "b").flatMap(c =>
      for i <- 1 to 4; j <- i + 1 to 4 yield ("w", s"$c$i", s"$c$j")
    ) ++ Seq(("w", "x", "a1"), ("w", "x", "a2"), ("w", "x", "b1"), ("w", "x", "b2"))
  private val star = Seq(("v", "m", "k"), ("v", "m", "n"), ("v", "m", "z"))

  private def labelsOf(l: DataFrame) =
    l.collect().map(r => (r.getString(0), r.getString(1)) -> r.getString(2)).toMap

  test("jobs 6 and 7: label propagation separates two cliques; the bridge leads every measure") {
    TestSpark.freshCheckpointDir()
    val (labels, rounds) = LabelPropagation(nodes(("w", "i")), edges(cliques ++ star*), 10)
    val got = labelsOf(labels)
    val a = Seq("a1", "a2", "a3", "a4", "x").map(n => ("w", n) -> "a1")
    val b = Seq("b1", "b2", "b3", "b4").map(n => ("w", n) -> "b1")
    val v = Seq("m", "k", "n", "z").map(n => ("v", n) -> "k")
    assert(got == (a ++ b ++ v :+ (("w", "i") -> "i")).toMap)
    assert(rounds == Seq(Map("w" -> 7L, "v" -> 3L), Map("v" -> 2L), Map()))

    val bridges = Bridges(labels, edges(cliques ++ star*))
      .collect()
      .map(r =>
        (r.getAs[String]("window"), r.getAs[String]("node")) -> (
          r.getAs[Long]("degree"),
          r.getAs[Long]("crossCommunityDegree"),
          r.getAs[Long]("communitiesConnected"),
          r.getAs[Double]("participationCoefficient")
        )
      )
      .toMap
    val expected = Map(
      "x" -> (4L, 2L, 2L, 0.5),
      "b1" -> (4L, 1L, 2L, 0.375),
      "b2" -> (4L, 1L, 2L, 0.375),
      "a1" -> (4L, 0L, 1L, 0.0),
      "a2" -> (4L, 0L, 1L, 0.0),
      "a3" -> (3L, 0L, 1L, 0.0),
      "a4" -> (3L, 0L, 1L, 0.0),
      "b3" -> (3L, 0L, 1L, 0.0),
      "b4" -> (3L, 0L, 1L, 0.0),
      "i" -> (0L, 0L, 0L, 0.0)
    ).map((n, m) => ("w", n) -> m) ++ Map(
      "m" -> (3L, 0L, 1L, 0.0),
      "k" -> (1L, 0L, 1L, 0.0),
      "n" -> (1L, 0L, 1L, 0.0),
      "z" -> (1L, 0L, 1L, 0.0)
    ).map((n, m) => ("v", n) -> m)
    assert(bridges == expected)
    val others = bridges.filter((k, _) => k != ("w", "x")).values
    assert(others.forall(m => m._2 < 2L && m._4 < 0.5)) // x is the strict maximum of both
  }

  test("job 6: labels are deterministic under row order and partitioning; the cap stops early") {
    TestSpark.freshCheckpointDir()
    val all = cliques ++ star
    val (l1, _) = LabelPropagation(nodes(("w", "i")), edges(all.reverse*).repartition(1), 10)
    val (l2, _) = LabelPropagation(
      nodes(("w", "i")),
      edges(all.map((w, a, b) => (w, b, a))*).repartition(5),
      10
    )
    assert(labelsOf(l1) == labelsOf(l2))
    // Cap 1: the round-1 labels are kept (not converged): star m -> k, n -> m, z -> m.
    val (capped, rounds) = LabelPropagation(nodes(), edges(star*), 1)
    assert(rounds == Seq(Map("v" -> 3L)))
    assert(
      labelsOf(capped) == Map(
        ("v", "m") -> "k",
        ("v", "k") -> "k",
        ("v", "n") -> "m",
        ("v", "z") -> "m"
      )
    )
  }

  test("pairs files are validated: header, field count, window, and researcher IDs") {
    val id1 = "scr:canon-v1.er-v2:" + "a" * 32
    val id2 = "scr:canon-v1.er-v2:" + "b" * 32
    val h = CommunityPathJob.PairsHeader
    assert(
      CommunityPathJob.parsePairs(
        s"$h\nall-years,$id1,$id2\n\nall-years,$id1,$id2\n",
        Set("all-years")
      )
        == Seq(("all-years", id1, id2))
    )
    def bad(text: String) =
      intercept[IllegalArgumentException](CommunityPathJob.parsePairs(text, Set("all-years")))
    bad(s"all-years,$id1,$id2")
    bad(s"$h\nall-years,$id1")
    bad(s"$h\n1999,$id1,$id2")
    bad(s"$h\nall-years,$id1,nobody")
  }

  // ---- The job end to end on the jobs 1 to 4 fixture runs -------------------------------------------
  test("the job writes paths, communities, bridges, and a manifest with no integrity violations") {
    val tmp = Files.createTempDirectory("sc-spark-community")
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
    val analytics = ResearcherAnalyticsJob.run(spark, base.copy(input = graph.runDir), cfg)
    val r = CommunityPathJob.run(spark, base.copy(input = analytics.runDir), cfg)
    val m = r.manifest
    assert(m("stage").str == CommunityPathJob.Stage)
    assert(m("input")("canonicalGraphRunId").str == graph.runId)
    val tables = m("tables").arr.map(t => t("name").str -> t).toMap
    assert(
      tables.keySet == Set(
        "paths/path-evidence",
        "paths/path-length-estimate",
        "analytics/communities",
        "analytics/cross-community-edges",
        "analytics/researcher-communities",
        "analytics/community-path-violations"
      )
    )
    assert(tables("analytics/community-path-violations")("rowCount").num == 0)
    def read(name: String) = spark.read.parquet(s"${r.runDir}/$name")
    // Every researcher of every window has one community row; community sizes add up.
    val metrics = spark.read.parquet(s"${analytics.runDir}/analytics/researcher-metrics")
    assert(read("analytics/researcher-communities").count() == metrics.count())
    assert(
      read("analytics/communities").agg(sum("communitySize")).head().getLong(0) == metrics.count()
    )
    // Generated pairs: floor(largest all-years component / 2), all found within the fixture.
    val largest = spark.read
      .parquet(s"${analytics.runDir}/analytics/components")
      .where(col("window") === cfg.graph.windows.head.name)
      .agg(max("componentSize"))
      .head()
      .getLong(0)
    val selected = Files.readString(java.nio.file.Path.of(m("selectedPairsFile").str), UTF_8)
    val pairCount = math.min(50L, largest / 2)
    assert(selected.linesIterator.size == pairCount + 1)
    val paths = read("paths/path-evidence")
    assert(paths.count() == pairCount && paths.where(col("status") =!= "FOUND").count() == 0)
    assert(read("paths/path-length-estimate").count() == cfg.graph.windows.size)
    assert(Files.notExists(tmp.resolve("ck").resolve(r.runId))) // checkpoints removed

    // A pairs file: one generated pair plus a researcher that is not in the graph.
    val pairsFile = tmp.resolve("pairs.csv")
    val first = selected.linesIterator.drop(1).next()
    val ghost = "scr:canon-v1.er-v2:" + "f" * 32
    Files.writeString(
      pairsFile,
      s"${CommunityPathJob.PairsHeader}\n$first\nall-years,$ghost,${first.split(",")(1)}\n"
    )
    val r2 = CommunityPathJob.run(
      spark,
      base.copy(input = analytics.runDir, pairs = Some(pairsFile.toString)),
      cfg
    )
    assert(r2.runId != r.runId)
    val statuses = spark.read
      .parquet(s"${r2.runDir}/paths/path-evidence")
      .collect()
      .map(_.getAs[String]("status"))
      .sorted
      .toSeq
    assert(statuses == Seq("FOUND", "UNKNOWN_RESEARCHER"))
  }
