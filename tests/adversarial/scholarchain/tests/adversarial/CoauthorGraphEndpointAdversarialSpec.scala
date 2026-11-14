package scholarchain.tests.adversarial

import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import scholarchain.core.config.ConfigLoader
import scholarchain.spark.{CoauthorGraph, Ctx}
import scholarchain.tests.support.SparkWorld.*
import scholarchain.tests.support.StoreWorld

/** Phase 11 work order 4 (ruling 12), spec category "coauthor edges with unresolved or conflicting
  * researcher endpoints", on the production Spark job 2 code (CoauthorGraph.build and .integrity).
  * A pair candidate whose mention or work has no canonical row is never an edge and is always a
  * named violation; a pair whose two mentions resolved to one researcher is a counted self-pair,
  * never an edge; merged works and reversed pairs never inflate a count.
  */
class CoauthorGraphEndpointAdversarialSpec extends AnyFunSuite:
  private val cfg = ConfigLoader.load(StoreWorld.baseEnv).fold(e => sys.error(e.mkString), identity)
  private val windows = cfg.graph.windows
  private val runId = StableIds.runId("adv-coauthor-graph", "c", "config-v1", Seq("g"), 1)
  private val ctx = Ctx("2026-10-03", runId, "canon-v1.er-v2", "graph-v1", 50)
  private def rid(s: String) = StoreWorld.rid(s)
  private val (ra, rb, rc) = (rid("a"), rid("b"), rid("c"))

  // m1 and m3 are one researcher (a merge); m2 is b; m4 is c. Source works w1 and w2 are one
  // canonical work (a duplicate); w3 is another.
  private val mentionMap = df(
    "mentionId STRING, researcherId STRING",
    Seq("m1", ra),
    Seq("m2", rb),
    Seq("m3", ra),
    Seq("m4", rc),
    Seq("m4", rc) // the same mention twice (a duplicated input row), same researcher
  )
  private val workMap = df(
    "sourceWorkId STRING, workId STRING",
    Seq("w1", "dblp:conf/x/C1"),
    Seq("w2", "dblp:conf/x/C1"),
    Seq("w3", "dblp:conf/x/C3")
  )
  private def pairs(rows: (String, String, String, Int)*) =
    df(
      "authorA STRING, authorB STRING, workId STRING, year INT",
      rows.map((a, b, w, y) => Seq(a, b, w, y))*
    )

  private def build(rows: (String, String, String, Int)*) =
    CoauthorGraph.build(ctx, spark, pairs(rows*), mentionMap, workMap, windows)

  test("unresolved endpoints and missing works are violations, never edges") {
    val out = build(
      ("m1", "mX", "w1", 2005), // mX is in no mention map
      ("mY", "m2", "w3", 2005), // mY too, on the other side
      ("m4", "m2", "wX", 2005), // wX is in no work map
      ("mY", "mZ", "wZ", 2005), // nothing resolves
      ("m1", "m2", "w3", 2005) // the one good pair
    )
    val v = rows(out.violations, "check", "subjectId", "relatedId")
      .map(x => (x(0).toString, x(1).toString, x(2).toString))
      .toSet
    assert(
      v == Set(
        ("PAIR_ENDPOINT_MISSING", "m1|mX", "w1"),
        ("PAIR_ENDPOINT_MISSING", "mY|m2", "w3"),
        ("PAIR_WORK_MISSING", "m4|m2", "wX"),
        ("PAIR_ENDPOINT_MISSING", "mY|mZ", "wZ"),
        ("PAIR_WORK_MISSING", "mY|mZ", "wZ")
      ),
      v
    )
    val e = rows(out.edges, "window", "researcherA", "researcherB", "jointPublicationCount")
    val (lo, hi) = if ra < rb then (ra, rb) else (rb, ra)
    assert(e.map(_.drop(1)).toSet == Set(Seq(lo, hi, 1L)), e)
    // 2005 is inside all-years and 2000-2014, outside 2015-2026.
    assert(e.map(_.head).toSet == Set("all-years", "2000-2014"), e)
    assert(out.selfPairs.count() == 0 && out.outsideWindows.count() == 0)
  }

  test("a pair whose two mentions resolved to one researcher is a self-pair, never a self-edge") {
    val out = build(("m1", "m3", "w3", 2010), ("m3", "m1", "w1", 2011))
    assert(out.edges.count() == 0)
    val self = rows(out.selfPairs, "authorA", "authorB", "researcherId")
    assert(self.toSet == Set(Seq("m1", "m3", ra), Seq("m3", "m1", ra)), self)
    // Provenance names the pair and the canonical policy.
    val p = out.selfPairs.select(col("provenance.sourceRecordId"), col("provenance.policyVersion"))
    assert(
      rows(p, "sourceRecordId", "policyVersion").toSet ==
        Set(Seq("m1|m3", "canon-v1.er-v2"), Seq("m3|m1", "canon-v1.er-v2"))
    )
    assert(out.violations.count() == 0)
  }

  test("merged works, reversed pairs, and duplicated mention rows never inflate a joint count") {
    val out = build(
      ("m1", "m2", "w1", 2003),
      ("m2", "m1", "w2", 2003), // reversed, on the source work merged into the same canonical work
      ("m3", "m2", "w3", 2016), // m3 is a's other mention: same researcher pair, another work
      ("m4", "m2", "w3", 2016) // m4 appears twice in the mention map
    )
    val e = rows(
      out.edges,
      "window",
      "researcherA",
      "researcherB",
      "jointPublicationCount",
      "firstCollaborationYear",
      "lastCollaborationYear",
      "representativeWorkIds"
    ).map(x => (x(0), Set(x(1), x(2))) -> x.drop(3)).toMap
    val ab = Set[Any](ra, rb)
    val cb = Set[Any](rc, rb)
    assert(
      e(("all-years", ab)).take(3) == Seq(2L, 2003, 2016) &&
        e(("all-years", ab))(3).asInstanceOf[scala.collection.Seq[String]].toSeq ==
        Seq("dblp:conf/x/C1", "dblp:conf/x/C3"),
      e
    )
    assert(e(("2000-2014", ab)).take(3) == Seq(1L, 2003, 2003))
    assert(e(("2015-2026", ab)).take(3) == Seq(1L, 2016, 2016))
    assert(e(("all-years", cb)).head == 1L && e(("2015-2026", cb)).head == 1L)
    assert(!e.contains(("2000-2014", cb)))
    // Canonical order and stable IDs: researcherA < researcherB, edge ID from the core formula.
    rows(out.edges, "researcherA", "researcherB", "windowStartYear", "windowEndYear", "edgeId")
      .foreach { x =>
        val (a, b) = (x(0).toString, x(1).toString)
        assert(a < b, x)
        val id =
          StableIds.coauthorEdgeId(a, b, x(2).asInstanceOf[Int], x(3).asInstanceOf[Int], "graph-v1")
        assert(id == Right(x(4)), x)
      }
    assert(out.violations.count() == 0 && out.selfPairs.count() == 0)
  }

  test("a pair outside every window is counted, not an edge; window bounds are inclusive") {
    val out = build(
      ("m1", "m2", "w1", 1999),
      ("m1", "m2", "w3", 2027),
      ("m1", "m4", "w3", 2000),
      ("m2", "m4", "w3", 2026),
      ("m1", "m2", "w3", 2014),
      ("m1", "m2", "w1", 2015)
    )
    assert(out.outsideWindows.count() == 2)
    val e = rows(out.edges, "window", "researcherA", "researcherB", "jointPublicationCount")
      .map(x => (x(0), Set(x(1), x(2))) -> x(3))
      .toMap
    assert(e(("2000-2014", Set[Any](ra, rc))) == 1L && e(("2015-2026", Set[Any](rb, rc))) == 1L)
    assert(e(("2000-2014", Set[Any](ra, rb))) == 1L && e(("2015-2026", Set[Any](ra, rb))) == 1L)
    assert(e(("all-years", Set[Any](ra, rb))) == 2L)
    assert(!e.keys.exists(k => k._1 == "2015-2026" && k._2 == Set[Any](ra, rc)))
  }

  test(
    "integrity: an edge whose endpoint is not a canonical researcher is named, with its edge ID"
  ) {
    val out = build(("m1", "m2", "w3", 2005), ("m4", "m2", "w3", 2005))
    // Only a and b are canonical researchers here; every edge touching c is a violation.
    val researchers = df("researcherId STRING", Seq(ra), Seq(rb))
    val v = rows(CoauthorGraph.integrity(out.edges, researchers), "check", "subjectId", "relatedId")
    val cEdges =
      rows(out.edges.where(col("researcherA") === rc || col("researcherB") === rc), "edgeId")
        .map(_.head.toString)
        .toSet
    assert(cEdges.size == 2, cEdges) // all-years and 2000-2014
    assert(v.map(_.head).toSet == Set("COAUTHOR_EDGE_ENDPOINT_MISSING"))
    assert(v.map(_(1).toString).toSet == cEdges, v)
    assert(
      CoauthorGraph
        .integrity(out.edges, df("researcherId STRING", Seq(ra), Seq(rb), Seq(rc)))
        .count() == 0
    )
    // Both ends missing: one violation per missing end.
    assert(CoauthorGraph.integrity(out.edges, df("researcherId STRING", Seq(ra))).count() == 6)
  }
