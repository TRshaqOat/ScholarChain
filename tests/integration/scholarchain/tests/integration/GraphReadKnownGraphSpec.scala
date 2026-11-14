package scholarchain.tests.integration

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader
import scholarchain.storage.*
import scholarchain.tests.support.StoreWorld
import scholarchain.tests.support.StoreWorld.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Phase 11 WO3, spec categories "empty and disconnected graphs" and the shortest-path and
  * distinct-degree "known-graph cases", on the store side the runtime reads (the in-memory graph
  * fake, loaded from Phase 7 Neptune CSV). Components, bridges, communities, and k-core are Spark
  * outputs: SparkKnownGraphSpec covers them (work order 4, ruling 12).
  *
  * Known graph (all-years): ada - grace (2 works), grace - edsger (1), edsger - mallory (1); wei1,
  * wei2, and ada2 have no coauthor edge.
  */
class GraphReadKnownGraphSpec extends AnyFunSuite:
  private val cfg = ConfigLoader.load(StoreWorld.baseEnv).fold(e => sys.error(e.mkString), identity)

  private val noObjects = new ObjectReadPort:
    def get(key: String) = None
    def list(prefix: String) = Vector.empty

  private def graph(files: Map[FileSpec, Vector[GraphElement]]): GraphReadPort =
    load(files).fold(v => fail(v.take(3).mkString("; ")), identity)

  private def load(
      files: Map[FileSpec, Vector[GraphElement]]
  ): Either[Vector[Violation], GraphReadPort] =
    val root = Files.createTempDirectory("adv-graph-")
    try
      NeptuneContract.All.foreach { spec =>
        val dir = root.resolve(spec.dir).resolve(spec.name)
        Files.createDirectories(dir)
        val rows = files.getOrElse(spec, Vector.empty).map(NeptuneContract.renderRow(spec, _))
        Files.writeString(
          dir.resolve("part-00000.csv"),
          (spec.header +: rows).map(Csv.render).mkString("\n") + "\n",
          UTF_8
        )
      }
      val g = InMemoryGraph()
      g.loader.load(root, cfg.graph.windows, None).map(_ => g.reader(GraphReadLimits.from(cfg)))
    finally StoreWorld.deleteTree(root)

  private lazy val known = graph(StoreWorld.graphFiles)

  test(
    "shortest paths on the known graph: lengths, hop order, the smallest predecessor, and bounds"
  ) {
    val p = known.shortestPath(ada, edsger, "all-years", 3)
    assert(p.status == PathStatus.FOUND && p.researcherIds == Vector(ada, grace, edsger), p)
    assert(p.hops.map(h => (h.from, h.to)) == Vector(ada -> grace, grace -> edsger))
    assert(p.hops.head.edge.props("jointPublicationCount") == 2L)
    val three = known.shortestPath(ada, mallory, "all-years", 3)
    assert(three.status == PathStatus.FOUND && three.researcherIds.size == 4)
    // Symmetric: the reverse path is the same chain reversed.
    assert(
      known.shortestPath(edsger, ada, "all-years", 3).researcherIds == Vector(edsger, grace, ada)
    )
    assert(known.shortestPath(ada, mallory, "all-years", 2).status == PathStatus.BEYOND_DEPTH)
  }

  test(
    "disconnected and isolated researchers: UNREACHABLE, never a path; unknown IDs are UNKNOWN_RESEARCHER"
  ) {
    assert(known.shortestPath(ada, wei1, "all-years", 6).status == PathStatus.UNREACHABLE)
    assert(known.shortestPath(wei1, wei2, "all-years", 6).status == PathStatus.UNREACHABLE)
    assert(known.shortestPath(ada, ada2, "all-years", 6).status == PathStatus.UNREACHABLE)
    val unknown = StoreWorld.rid("nobody")
    assert(known.shortestPath(ada, unknown, "all-years", 3).status == PathStatus.UNKNOWN_RESEARCHER)
    // A work or topic ID is not a researcher end.
    assert(known.shortestPath(ada, w(1), "all-years", 3).status == PathStatus.UNKNOWN_RESEARCHER)
    assert(
      scala.util.Try(known.shortestPath(ada, ada, "all-years", 3)).isFailure,
      "a self path was searched"
    )
    assert(
      scala.util.Try(known.shortestPath(ada, edsger, "1999-2000", 3)).isFailure,
      "an unknown window was read"
    )
    assert(
      scala.util.Try(known.shortestPath(ada, edsger, "all-years", 99)).isFailure,
      "depth beyond max-path-depth"
    )
  }

  test(
    "an empty graph (researchers, no coauthor edge) answers UNREACHABLE and empty neighbor lists"
  ) {
    val empty =
      graph(Map(NeptuneContract.Researcher -> StoreWorld.graphFiles(NeptuneContract.Researcher)))
    assert(empty.shortestPath(ada, grace, "all-years", 6).status == PathStatus.UNREACHABLE)
    assert(empty.coauthors(ada, "all-years", 10).items.isEmpty)
    assert(empty.coauthorEdge(ada, grace, "all-years").isEmpty)
    val n = empty.neighbors(ada, Direction.BOTH, 3, 200, Vector("COAUTHORED_WITH", "AUTHORED"))
    assert(n.start.isDefined && n.nodes.isEmpty && n.edges.isEmpty && !n.truncated)
    val nothing = graph(Map.empty)
    assert(nothing.vertex(ada).isEmpty)
    assert(nothing.shortestPath(ada, grace, "all-years", 3).status == PathStatus.UNKNOWN_RESEARCHER)
  }

  test(
    "distinct degree versus weighted degree on the known graph: grace has 2 coauthors and 3 joint works"
  ) {
    val links = known.coauthors(grace, "all-years", 10).items
    assert(links.map(_.coauthorId).toSet == Set(ada, edsger))
    assert(links.map(_.edge.props("jointPublicationCount").asInstanceOf[Long]).sum == 3L)
    // Strongest first (jointPublicationCount descending).
    assert(links.head.coauthorId == ada)
    // Each edge's supporting works match its count.
    links.foreach(l =>
      assert(
        known.supportingWorks(l.edge.id, noObjects).map(_.workIds.size.toLong) == Right(
          l.edge.props("jointPublicationCount")
        )
      )
    )
    // Bounded: a limit of 1 returns the strongest only, flagged truncated.
    val one = known.coauthors(grace, "all-years", 1)
    assert(one.items.map(_.coauthorId) == Vector(ada) && one.truncated)
  }

  test("an edge whose supporting-work count disagrees with its jointPublicationCount is refused") {
    val good = StoreWorld.coauthor(ada, grace, Vector(w(1) -> 2001, w(2) -> 2002))
    // Control: the untampered edge loads and its supporting works match its count.
    val g0 = graph(StoreWorld.graphFiles + (NeptuneContract.CoauthoredWith -> Vector(good)))
    assert(g0.supportingWorks(good.id, noObjects).map(_.workIds.size) == Right(2))
    // Work order 4 (code review 1 section 5): the refusal must be the count check itself, at load,
    // on the tampered row; any other load failure no longer passes.
    def mismatch(e: GraphElement) =
      load(StoreWorld.graphFiles + (NeptuneContract.CoauthoredWith -> Vector(e))) match
        case Left(v)  => v
        case Right(_) =>
          fail(
            s"loaded an edge with ${e.props("jointPublicationCount")} for ${e.props("supportingWorkIds")}"
          )
    Vector(5L, 1L, 0L).foreach { n =>
      val v = mismatch(good.copy(props = good.props + ("jointPublicationCount" -> n)))
      val m = v.filter(_.code == "EVIDENCE_COUNT_MISMATCH")
      assert(m.size == 1 && m.head.line == 2 && m.head.source.contains("coauthored-with"), v)
      assert(m.head.detail == s"2 work IDs for jointPublicationCount $n", v)
      // A count of 0 is also refused on its own (BAD_COUNT); no other check fires.
      val others = if n == 0L then Set("BAD_COUNT") else Set.empty[String]
      assert(v.map(_.code).toSet - "EVIDENCE_COUNT_MISMATCH" == others, v)
    }
    // The same work listed twice is not two joint publications.
    val doubled = good.copy(props =
      good.props + ("supportingWorkIds" -> s"${w(1)} ${w(1)}") + ("jointPublicationCount" -> 2L)
    )
    val dv = mismatch(doubled)
    assert(dv.nonEmpty, "an edge counting one work twice was loaded")
  }
