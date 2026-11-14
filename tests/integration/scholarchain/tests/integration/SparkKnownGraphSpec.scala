package scholarchain.tests.integration

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader
import scholarchain.core.{Provenance, SchemaVersions, SourceType, StableIds}
import scholarchain.spark.*
import scholarchain.storage.{S3Layout, WindowSummaries, WindowSummary, WindowSummarySourceRuns}
import scholarchain.tests.support.SparkWorld.*
import scholarchain.tests.support.StoreWorld

import scala.collection.mutable

/** Phase 11 work order 4 (ruling 12): the Spark side of the spec categories "distinct-degree,
  * component, bridge, and k-core known-graph cases" and "empty and disconnected graphs", plus
  * communities. Every algorithm runs on the production DataFrame code (scholarchain.spark) and is
  * compared with a hand-worked answer or an independent in-test reference (union-find, min-degree
  * peeling, synchronous label propagation) on small fixed and seeded graphs. Windows share node IDs
  * on purpose: windows must never mix.
  */
class SparkKnownGraphSpec extends AnyFunSuite:
  private val cfg = ConfigLoader.load(StoreWorld.baseEnv).fold(e => sys.error(e.mkString), identity)

  // ---- graphs as (window, a, b) edges plus (window, node) nodes ----
  private type G = (Vector[(String, String)], Vector[(String, String, String)])
  private def nodesDf(ns: Seq[(String, String)]) =
    df("window STRING, node STRING", ns.map((w, n) => Seq(w, n))*)
  private def edgesDf(es: Seq[(String, String, String)]) =
    df("window STRING, a STRING, b STRING", es.map((w, a, b) => Seq(w, a, b))*)
  private def r(i: Int) = f"r$i%02d"
  private def clique(w: String, ids: Seq[Int]): Vector[(String, String, String)] =
    (for i <- ids; j <- ids if i < j yield (w, r(i), r(j))).toVector

  /** Undirected adjacency per window, without self-loops (duplicates and orientation collapse). */
  private def adjacency(g: G): Map[String, Map[String, Set[String]]] =
    val (ns, es) = g
    val windows = (ns.map(_._1) ++ es.map(_._1)).distinct
    windows.map { w =>
      val adj = mutable.Map.empty[String, Set[String]]
      ns.filter(_._1 == w).foreach((_, n) => adj.getOrElseUpdate(n, Set.empty))
      es.filter((x, a, b) => x == w && a != b).foreach { (_, a, b) =>
        adj(a) = adj.getOrElse(a, Set.empty) + b
        adj(b) = adj.getOrElse(b, Set.empty) + a
      }
      w -> adj.toMap
    }.toMap

  /** Reference components: union-find, root = smallest node. */
  private def refComponents(adj: Map[String, Set[String]]): Map[String, String] =
    val parent = mutable.Map.from(adj.keys.map(k => k -> k))
    def find(x: String): String =
      if parent(x) == x then x
      else
        val p = find(parent(x)); parent(x) = p; p
    for (a, ns) <- adj; b <- ns do
      val (x, y) = (find(a), find(b))
      if x != y then if x < y then parent(y) = x else parent(x) = y
    adj.keys.map(k => k -> find(k)).toMap

  /** Reference coreness: repeatedly remove a node of minimum remaining degree. */
  private def refCoreness(adj: Map[String, Set[String]]): Map[String, Int] =
    val deg = mutable.Map.from(adj.map((k, v) => k -> v.size))
    val left = mutable.Set.from(adj.keys)
    val core = mutable.Map.empty[String, Int]
    var k = 0
    while left.nonEmpty do
      val v = left.minBy(n => (deg(n), n))
      k = math.max(k, deg(v))
      core(v) = k
      left -= v
      adj(v).filter(left).foreach(u => deg(u) -= 1)
    core.toMap

  /** Reference synchronous label propagation (own label is one vote; ties to the smallest label).
    */
  private def refLabels(adj: Map[String, Set[String]], max: Int): (Map[String, String], Int) =
    var labels = adj.keys.map(k => k -> k).toMap
    var rounds = 0
    var changed = true
    while rounds < max && changed do
      val next = adj.map { (n, ns) =>
        val votes = (ns.toVector.map(labels) :+ labels(n)).groupMapReduce(identity)(_ => 1)(_ + _)
        n -> votes.toVector.minBy((l, c) => (-c, l))._1
      }
      changed = next != labels
      labels = next
      rounds += 1
    (labels, rounds)

  /** A seeded random simple graph on n nodes with m edge draws (duplicates and loops allowed). */
  private def generated(w: String, seed: Long, n: Int, m: Int): G =
    val rnd = java.util.Random(seed)
    val es = Vector.fill(m)((w, r(rnd.nextInt(n)), r(rnd.nextInt(n))))
    ((0 until n).toVector.map(i => (w, r(i))), es)

  // Window "x": chain 1-2-3, triangle 4-5-6, pair 7-8, isolated 9 (node only), a self-loop on 9,
  // and duplicated edges in both orientations. Window "y": the same IDs, but 3-4 joins chain and
  // triangle, and 8 is alone. A window must never see another window's edges.
  private val twoWindows: G = (
    (1 to 9).toVector.map(i => ("x", r(i))) ++ (1 to 9).toVector.map(i => ("y", r(i))),
    Vector(
      ("x", r(1), r(2)),
      ("x", r(2), r(3)),
      ("x", r(3), r(2)),
      ("x", r(4), r(5)),
      ("x", r(5), r(6)),
      ("x", r(6), r(4)),
      ("x", r(7), r(8)),
      ("x", r(8), r(7)),
      ("x", r(9), r(9)),
      ("y", r(1), r(2)),
      ("y", r(2), r(3)),
      ("y", r(3), r(4)),
      ("y", r(4), r(5)),
      ("y", r(5), r(6))
    )
  )

  private def minLabel(g: G): Map[(String, String), String] =
    val (out, changed) = MinLabelComponents(nodesDf(g._1), edgesDf(g._2), 50)
    assert(changed.last == 0L, changed)
    out
      .collect()
      .map(x => (x.getString(0), x.getString(1)) -> x.getString(2))
      .toMap

  test(
    "components: min-label propagation equals union-find on known and seeded graphs, per window"
  ) {
    val graphs = Vector(
      twoWindows,
      generated("g1", 11L, 30, 28),
      generated("g2", 12L, 24, 40),
      // A long chain with its minimum in the middle, and a star whose hub is the largest ID.
      (
        (1 to 12).toVector.map(i => ("chain", r(i))),
        Vector(7, 3, 9, 1, 12, 5, 10, 2, 8, 11, 4, 6)
          .sliding(2)
          .toVector
          .map(p => ("chain", r(p(0)), r(p(1))))
      ),
      ((1 to 6).toVector.map(i => ("star", r(i))), (1 to 5).toVector.map(i => ("star", r(i), r(6))))
    )
    graphs.foreach { g =>
      val got = minLabel(g)
      val want = adjacency(g).toVector
        .flatMap((w, adj) => refComponents(adj).map((n, root) => (w, n) -> root))
        .toMap
      assert(got == want, s"windows ${g._1.map(_._1).distinct}")
    }
    // The two windows of the known graph differ, as their edges do.
    val known = minLabel(twoWindows)
    assert(known(("x", r(4))) == r(4) && known(("y", r(4))) == r(1))
    assert(known(("x", r(9))) == r(9), "the self-loop node is its own component")
    assert(known(("x", r(8))) == r(7) && known(("y", r(8))) == r(8))
  }

  test("components: large-star/small-star agrees, including negative and extreme LONG node keys") {
    def cc(edges: Seq[(Long, Long)]): Map[Long, Long] =
      val (out, _) = ConnectedComponents(
        df("src BIGINT, dst BIGINT", edges.map((a, b) => Seq(a, b))*),
        50
      )
      out.collect().map(x => x.getLong(0) -> x.getLong(1)).toMap
    def ref(edges: Seq[(Long, Long)]): Map[Long, Long] =
      val adj = edges.filter(_ != _).flatMap((a, b) => Seq(a -> b, b -> a)).groupMap(_._1)(_._2)
      val s = adj.map((k, v) => k.toString -> v.map(_.toString).toSet)
      // String order differs from LONG order, so the root is recomputed as the LONG minimum.
      val comp = refComponents(s).groupMap(_._2)(_._1).values
      comp.flatMap(m => m.map(_.toLong).map(_ -> m.map(_.toLong).min)).toMap
    val extreme = Vector(
      Long.MaxValue -> 0L,
      0L -> -1L,
      -1L -> Long.MinValue,
      42L -> 42L, // a self-loop only: no row (isolated nodes are the caller's)
      7L -> 8L,
      8L -> 7L
    )
    val got = cc(extreme)
    assert(got == ref(extreme), got)
    assert(got(Long.MaxValue) == Long.MinValue && got(8L) == 7L && !got.contains(42L))
    val rnd = java.util.Random(21L)
    val seeded = Vector.fill(40)((rnd.nextInt(30).toLong - 15L, rnd.nextInt(30).toLong - 15L))
    assert(cc(seeded) == ref(seeded))
  }

  test("empty and disconnected graphs: no node, isolated nodes only, and a window with no edges") {
    val noNodes = nodesDf(Nil)
    val noEdges = edgesDf(Nil)
    // Empty: every algorithm returns nothing and stops at once.
    val (cc0, rounds0) = ConnectedComponents(df("src BIGINT, dst BIGINT"), 5)
    assert(cc0.count() == 0 && rounds0 == 0)
    val (ml0, changed0) = MinLabelComponents(noNodes, noEdges, 5)
    assert(ml0.count() == 0 && changed0 == Seq(0L))
    val (k0, log0) = KCore(noNodes, noEdges, 5)
    assert(k0.count() == 0 && log0.isEmpty)
    val (lp0, lr0) = LabelPropagation(noNodes, noEdges, 5)
    assert(lp0.count() == 0 && lr0.size == 1 && lr0.head.values.sum == 0)
    assert(Bridges(lp0, noEdges).count() == 0)
    // Isolated nodes only: each is its own component and community, coreness 0, no bridge measure.
    val iso = nodesDf(Vector(("w", r(3)), ("w", r(1)), ("v", r(1))))
    assert(
      rows(MinLabelComponents(iso, noEdges, 5)._1, "window", "node", "root").toSet ==
        Set(Seq("w", r(3), r(3)), Seq("w", r(1), r(1)), Seq("v", r(1), r(1)))
    )
    assert(
      rows(KCore(iso, noEdges, 5)._1, "window", "node", "degree", "coreness").toSet ==
        Set(Seq("w", r(3), 0L, 0), Seq("w", r(1), 0L, 0), Seq("v", r(1), 0L, 0))
    )
    val (lp, _) = LabelPropagation(iso, noEdges, 5)
    assert(rows(lp, "window", "node", "label").forall(x => x(1) == x(2)))
    val b = Bridges(lp, noEdges)
    assert(
      rows(
        b,
        "degree",
        "crossCommunityDegree",
        "communitiesConnected",
        "participationCoefficient"
      ).toSet ==
        Set(Seq(0L, 0L, 0L, 0.0))
    )
    // A disconnected window next to a connected one: the connected window's edges never leak.
    val (ns, es) = twoWindows
    val k = KCore(nodesDf(ns), edgesDf(es), 10)._1
    val core = rows(k, "window", "node", "coreness").map(x => (x(0), x(1)) -> x(2)).toMap
    assert(core(("x", r(9))) == 0 && core(("x", r(8))) == 1 && core(("y", r(8))) == 0)
  }

  test(
    "k-core: known coreness (clique, cycle, star, tree, Petersen, clique with a tail) and seeded graphs"
  ) {
    val petersen =
      Vector((0, 1), (1, 2), (2, 3), (3, 4), (4, 0), (5, 7), (7, 9), (9, 6), (6, 8), (8, 5)) ++
        (0 to 4).map(i => (i, i + 5))
    val known: G = (
      Vector.empty,
      clique("k5", 1 to 5) ++
        (1 to 6).map(i => ("c6", r(i), r(i % 6 + 1))) ++
        (2 to 7).map(i => ("star", r(1), r(i))) ++
        Vector(
          ("tree", r(1), r(2)),
          ("tree", r(1), r(3)),
          ("tree", r(3), r(4)),
          ("tree", r(3), r(5))
        ) ++
        petersen.map((a, b) => ("petersen", r(a), r(b))) ++
        clique("tail", 1 to 4) ++ Vector(
          ("tail", r(4), r(5)),
          ("tail", r(5), r(6)),
          ("tail", r(6), r(5))
        )
    )
    val (out, _) = KCore(nodesDf(known._1), edgesDf(known._2), 50)
    val got = rows(out, "window", "node", "degree", "coreness")
      .map(x => (x(0).toString, x(1).toString) -> (x(2).asInstanceOf[Long], x(3).asInstanceOf[Int]))
      .toMap
    def all(w: String) = got.filter(_._1._1 == w).values.map(_._2).toSet
    assert(all("k5") == Set(4) && all("c6") == Set(2) && all("star") == Set(1))
    assert(all("tree") == Set(1) && all("petersen") == Set(3))
    assert((1 to 4).forall(i => got(("tail", r(i)))._2 == 3))
    assert(got(("tail", r(5)))._2 == 1 && got(("tail", r(6)))._2 == 1)
    // degree is distinct coauthors: the duplicated 5-6 edge counts once.
    assert(
      got(("tail", r(5)))._1 == 2L && got(("tail", r(6)))._1 == 1L && got(("star", r(1)))._1 == 6L
    )
    // Seeded graphs, two windows sharing node IDs, against the reference peeling.
    val (g1n, g1e) = generated("s1", 31L, 25, 60)
    val (g2n, g2e) = generated("s2", 32L, 25, 35)
    val g: G = (g1n ++ g2n, g1e ++ g2e)
    val (sOut, _) = KCore(nodesDf(g._1), edgesDf(g._2), 50)
    val seeded = rows(sOut, "window", "node", "coreness")
      .map(x => (x(0).toString, x(1).toString) -> x(2).asInstanceOf[Int])
      .toMap
    val want =
      adjacency(g).toVector.flatMap((w, adj) => refCoreness(adj).map((n, c) => (w, n) -> c)).toMap
    assert(seeded == want)
  }

  test(
    "communities: label propagation on known graphs equals the hand-worked and reference labels"
  ) {
    // Two 4-cliques joined by one edge (4-5), two disjoint triangles, one pair, one isolated node.
    val known: G = (
      Vector(("w", r(20))),
      clique("w", 1 to 4) ++ clique("w", 5 to 8) ++ Vector(("w", r(4), r(5))) ++
        clique("w", 10 to 12) ++ clique("w", 13 to 15) ++ Vector(("w", r(17), r(16)))
    )
    val (labels, rounds) = LabelPropagation(nodesDf(known._1), edgesDf(known._2), 10)
    val got = rows(labels, "node", "label").map(x => x(0).toString -> x(1).toString).toMap
    val expected =
      (1 to 4).map(i => r(i) -> r(1)) ++ (5 to 8).map(i => r(i) -> r(5)) ++
        (10 to 12).map(i => r(i) -> r(10)) ++ (13 to 15).map(i => r(i) -> r(13)) ++
        Seq(r(16) -> r(16), r(17) -> r(16), r(20) -> r(20))
    assert(got == expected.toMap)
    assert(rounds.last.values.sum == 0L, "did not converge")
    // Seeded graphs against the synchronous reference, including the round count.
    Vector(generated("p", 41L, 20, 30), generated("q", 42L, 16, 40)).foreach { g =>
      val (l, rs) = LabelPropagation(nodesDf(g._1), edgesDf(g._2), 10)
      val (want, wantRounds) = refLabels(adjacency(g).values.head, 10)
      assert(rows(l, "node", "label").map(x => x(0).toString -> x(1).toString).toMap == want)
      assert(rs.size == wantRounds, s"${rs.size} rounds, reference $wantRounds")
    }
    // Row order and partitioning do not change the labels.
    val shuffled = edgesDf(scala.util.Random(7L).shuffle(known._2)).repartition(3)
    val (again, _) = LabelPropagation(nodesDf(known._1).repartition(2), shuffled, 10)
    assert(rows(again, "node", "label").map(x => x(0).toString -> x(1).toString).toMap == got)
    // The cap: one round only is recorded as not converged, with the round-1 labels kept.
    val (capped, cr) = LabelPropagation(nodesDf(known._1), edgesDf(known._2), 1)
    assert(cr.size == 1 && cr.head.values.sum > 0)
    assert(
      rows(capped, "node", "label").map(x => x(0).toString -> x(1).toString).toMap.apply(r(5)) == r(
        4
      )
    )
  }

  test(
    "bridge measures: hand-computed degree, cross-community degree, communities, participation"
  ) {
    val es =
      clique("w", 1 to 4) ++ clique("w", 5 to 8) ++ Vector(("w", r(4), r(5)), ("w", r(5), r(4)))
    val labels = df(
      "window STRING, node STRING, label STRING",
      ((1 to 4).map(i => Seq("w", r(i), r(1))) ++ (5 to 8).map(i => Seq("w", r(i), r(5))) :+
        Seq("w", r(9), r(9)))*
    )
    val b = rows(
      Bridges(labels, edgesDf(es)),
      "node",
      "degree",
      "crossCommunityDegree",
      "communitiesConnected",
      "participationCoefficient"
    ).map(x => x.head.toString -> x.tail).toMap
    // r04: coauthors 1, 2, 3 (own community) and 5 (other): 1 - ((3/4)^2 + (1/4)^2) = 0.375.
    assert(b(r(4)) == Seq(4L, 1L, 2L, 0.375))
    assert(b(r(5)) == Seq(4L, 1L, 2L, 0.375))
    assert(b(r(1)) == Seq(3L, 0L, 1L, 0.0))
    assert(b(r(9)) == Seq(0L, 0L, 0L, 0.0))
    // Seeded graph: every value within its definition's bounds.
    val g = generated("s", 51L, 20, 35)
    val (l, _) = LabelPropagation(nodesDf(g._1), edgesDf(g._2), 10)
    val adj = adjacency(g)("s")
    val lab = rows(l, "node", "label").map(x => x(0).toString -> x(1).toString).toMap
    rows(
      Bridges(l, edgesDf(g._2)),
      "node",
      "degree",
      "crossCommunityDegree",
      "communitiesConnected",
      "participationCoefficient"
    ).foreach { x =>
      val n = x(0).toString
      val k = adj(n).size.toLong
      val cross = adj(n).count(u => lab(u) != lab(n)).toLong
      val comms = adj(n).map(lab).size.toLong
      val pc =
        if k == 0 then 0.0
        else
          1.0 - adj(n).toVector.groupBy(lab).values.map(v => math.pow(v.size.toDouble / k, 2)).sum
      assert(x(1) == k && x(2) == cross && x(3) == comms, x)
      assert(math.abs(x(4).asInstanceOf[Double] - pc) < 1e-12, x)
      if comms > 0 then assert(x(4).asInstanceOf[Double] <= 1.0 - 1.0 / comms + 1e-12, x)
    }
  }

  test(
    "Spark window summaries of known graphs, an empty window included, pass the storage identities (repair-01 D10)"
  ) {
    // Cross-module: the D10 identities in storage must accept what Spark really produces.
    val ws = cfg.graph.windows
    val graph: G = (
      Vector(("all-years", r(30)), ("all-years", r(31)), ("2000-2014", r(30))),
      clique("all-years", 1 to 4) ++ clique("all-years", 5 to 8) ++ Vector(
        ("all-years", r(4), r(5))
      ) ++
        Vector(("all-years", r(10), r(11))) ++
        (1 to 5).map(i => ("2000-2014", r(i), r(i + 1)))
      // 2015-2026 has no node and no edge.
    )
    val nodes = nodesDf(graph._1)
      .union(
        edgesDf(graph._2).select(col("window"), col("a").as("node"))
      )
      .union(edgesDf(graph._2).select(col("window"), col("b").as("node")))
      .distinct()
    val edges = edgesDf(graph._2)
    val (comp, _) = MinLabelComponents(nodes, edges, 50)
    val (lab, lr) = LabelPropagation(nodes, edges, cfg.analytics.communityMaxIterations)
    val summary = CorpusWindowSummaryJob.summarize(
      CoauthorGraph.windows(spark, ws),
      nodes.withColumnRenamed("node", "researcherId"),
      edges.withColumn("jointPublicationCount", lit(1L)),
      comp
        .groupBy(col("window"), col("root").as("componentId"))
        .agg(count(lit(1)).as("componentSize")),
      lab
        .groupBy(col("window"), col("label").as("communityId"))
        .agg(count(lit(1)).as("communitySize")),
      3
    )
    val run = StableIds.runId("adv-spark-window-summary", "c", "config-v1", Seq("s"), 1)
    val loadRun = StableIds.runId("adv-spark-load", "c", "config-v1", Seq("l"), 1)
    val converged = lr.last.values.sum == 0L
    val got = summary.collect().toVector.map { x =>
      def l(c: String) = x.getAs[Long](c)
      def d(c: String) = Option(x.getAs[java.lang.Double](c)).map(_.doubleValue)
      val w = x.getAs[String]("window")
      val t = ws.find(_.name == w).get
      WindowSummary(
        w,
        t.startYear,
        t.endYear,
        l("researchers"),
        l("coauthorEdges"),
        l("sumJointPublicationCount"),
        l("components"),
        l("largestComponentSize"),
        l("singletonResearchers"),
        l("communities"),
        l("largestCommunitySize"),
        l("singletonCommunities"),
        l("communitiesAtLeastMinSize"),
        Option(x.getAs[String]("largestComponentId"))
          .map(id => "comp-" + StableIds.hash("c", w, id)),
        Option(x.getAs[String]("largestCommunityId"))
          .map(id => StableIds.communityId("graph-v1", w, t.startYear, t.endYear, id)),
        x.getAs[Int]("years"),
        d("largestComponentFraction"),
        d("largestCommunityFraction"),
        3,
        converged,
        lr.size,
        "synthetic nodes of the work order 4 known graphs",
        WindowSummarySourceRuns(run, run, run, run, run, "er-v2"),
        Provenance(
          SchemaVersions.Provenance,
          SourceType.DERIVED,
          w,
          StoreWorld.snapshot,
          run,
          Some("graph-v1"),
          None
        ),
        WindowSummaries.SchemaVersion
      )
    }
    assert(got.map(_.window).toSet == ws.map(_.name).toSet)
    val all = got.find(_.window == "all-years").get
    assert(
      (
        all.researchers,
        all.coauthorEdges,
        all.components,
        all.largestComponentSize,
        all.singletonResearchers
      ) ==
        (12L, 14L, 4L, 8L, 2L)
    )
    assert(
      (all.communities, all.largestCommunitySize, all.communitiesAtLeastMinSize) == (5L, 4L, 2L)
    )
    val empty = got.find(_.window == "2015-2026").get
    assert(
      empty.researchers == 0L && empty.components == 0L && empty.largestComponentFraction.isEmpty
    )
    val key = S3Layout.windowSummary(StoreWorld.snapshot, loadRun)
    val parsed = WindowSummaries.parse(key, WindowSummaries.render(got), ws)
    assert(parsed.isRight, parsed.left.toOption.map(_.take(5)))
  }
