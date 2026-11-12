package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import scholarchain.storage.live.{Documents, NeptuneGraphReader, OpenCypherTemplates}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse,
  TimeLimitExceededException
}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

/** Graph read templates on the in-memory fake (Phase 9 ruling 7), on a small fixture graph:
  *
  * A-B, A-C, B-D, C-D, D-E coauthor edges (all-years); F has no coauthor; G-H share 21 works, so
  * their supporting works live in an evidence object. Communities C1 = {A, B, C}, C2 = {D, E}.
  */
class GraphReadSpec extends AnyFunSuite:
  import Fixtures.*
  import NeptuneContract.*

  val Vector(a, b, c, d, e, f) = (1 to 6).map(i => researcher(s"gr$i")).sorted.toVector
  val Vector(g, h) = Vector(researcher("gr-g"), researcher("gr-h")).sorted
  val allYears = windows.find(_.name == "all-years").get
  val t1 = "openalex:T1"
  val t2 = "openalex:T2"
  val v1 = "dblp-venue:conf/v1"
  val i1 = "ror:i1"
  val i2 = "ror:i2"
  val c1 = StableIds.communityId(policy, "all-years", 2000, 2026, a)
  val c2 = StableIds.communityId(policy, "all-years", 2000, 2026, d)
  // work -> (year, authors, topics, venue)
  val works: Vector[(String, Int, Vector[String], Vector[String], Option[String])] = Vector(
    ("dblp:conf/gr/W1", 2005, Vector(a, b), Vector(t1), Some(v1)),
    ("dblp:conf/gr/W2", 2010, Vector(a, c), Vector(t1, t2), Some(v1)),
    ("dblp:conf/gr/W3", 2020, Vector(b, d), Vector(t2), None),
    ("dblp:conf/gr/W4", 2012, Vector(c, d), Vector(t1), None),
    ("dblp:conf/gr/W5", 2018, Vector(d, e), Vector.empty, None)
  ) ++ (10 to 30).map(k => (s"dblp:conf/gr/G$k", 2001, Vector(g, h), Vector.empty, None))
  def w(n: Int) = works(n - 1)._1

  def community(id: String, label: String, size: Long) = vertex(
    Community,
    id,
    "window" -> allYears.name,
    "windowStartYear" -> allYears.startYear,
    "windowEndYear" -> allYears.endYear,
    "communitySize" -> size,
    "internalEdges" -> 1L,
    "crossCommunityEdges" -> 2L,
    "communityLabel" -> label
  )
  def memberOf(r: String, c: String) = edge(
    MemberOf,
    r,
    c,
    "window" -> allYears.name,
    "windowStartYear" -> allYears.startYear,
    "windowEndYear" -> allYears.endYear
  )
  val ghWorks = works.drop(5).map(_._1)
  val ghEdge = coauthor(g, h, ghWorks)
  val ghRef =
    s"curated/coauthor-evidence/snapshot=$snapshot/run=$runId/window=all-years/${ghEdge.id}.json"

  def fixture: Map[FileSpec, Vector[GraphElement]] = Map(
    Researcher -> Vector(a, b, c, d, e, f, g, h).map(researcherV),
    Work -> works.map((id, y, _, _, _) => vertex(Work, id, "year" -> y, "dblpAnchored" -> true)),
    Topic -> Vector(
      vertex(Topic, t1, "name" -> "Graph Theory"),
      vertex(Topic, t2, "name" -> "Distributed Graphs")
    ),
    Venue -> Vector(vertex(Venue, v1, "name" -> "Venue One")),
    Institution -> Vector(
      vertex(Institution, i1, "name" -> "Inst One"),
      vertex(Institution, i2, "name" -> "Inst Two")
    ),
    Community -> Vector(community(c1, a, 3L), community(c2, d, 2L)),
    Authored -> works.flatMap((id, y, rs, _, _) =>
      rs.zipWithIndex.map((r, k) => edge(Authored, r, id, "position" -> (k + 1), "year" -> y))
    ),
    HasTopic -> works.flatMap((id, _, _, ts, _) => ts.map(t => edge(HasTopic, id, t))),
    PublishedIn -> works.flatMap((id, _, _, _, v) => v.map(edge(PublishedIn, id, _))),
    AffiliatedWith -> Vector(
      edge(AffiliatedWith, a, i1, "via" -> "MENTION"),
      edge(AffiliatedWith, a, i2, "via" -> "MENTION ORCID_EMPLOYMENT")
    ),
    CoauthoredWith -> Vector(
      coauthor(a, b, Vector(w(1))),
      coauthor(a, c, Vector(w(2))),
      coauthor(b, d, Vector(w(3))),
      coauthor(c, d, Vector(w(4))),
      coauthor(d, e, Vector(w(5))),
      ghEdge.copy(props = ghEdge.props - "supportingWorkIds" + ("supportingWorkIdsRef" -> ghRef))
    ),
    MemberOf -> (Vector(a, b, c).map(memberOf(_, c1)) ++ Vector(d, e).map(memberOf(_, c2)))
  )

  val limits = GraphReadLimits.from(config)

  /** The fixture loaded into a fresh in-memory graph, with its evidence object store. */
  def withGraph[A](body: (InMemoryGraph, ObjectReadPort) => A): A = withTemp { root =>
    val objects = FileObjectStore(root.resolve("s3"))
    val ev = CoauthorEvidence(
      EvidenceSchemaVersion,
      ghEdge.id,
      "all-years",
      ghWorks.sorted,
      runId,
      snapshot
    )
    objects.loader.putNew(ghRef, upickle.default.write(ev).getBytes(UTF_8))
    val dir = root.resolve("graph")
    writeGraph(dir, fixture)
    val graph = InMemoryGraph()
    assert(graph.loader.load(dir, windows, Some(objects.reader)).isRight)
    body(graph, objects.reader)
  }

  test("fixture loads under the full validator (endpoints, evidence tracing, windows)") {
    withGraph((graph, _) => assert(graph.vertexCount == 8 + works.size + 2 + 1 + 2 + 2))
  }

  test("shortest path: Spark job 5 tie-break (smallest predecessor), hops carry edge provenance") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      val p = r.shortestPath(a, e, "all-years", 6)
      assert(p.status == PathStatus.FOUND)
      // A-B-D-E and A-C-D-E both have 3 hops; D's parent is the smaller of B and C.
      assert(p.researcherIds == Vector(a, b, d, e))
      assert(p.hops.map(h => (h.from, h.to)) == Vector(a -> b, b -> d, d -> e))
      p.hops.foreach { h =>
        assert(h.edge.label == "COAUTHORED_WITH")
        Seq("runId", "snapshot", "schemaVersion", "policyVersion").foreach(k =>
          assert(h.edge.props.contains(k), k)
        )
        assert(h.edge.props("jointPublicationCount") == 1L)
      }
      // Walking back from A, B's parent at distance 2 is D, and A's is the smaller of B and C.
      assert(r.shortestPath(e, a, "all-years", 6).researcherIds == Vector(e, d, b, a))
    }
  }

  test("shortest path statuses: BEYOND_DEPTH, UNREACHABLE, UNKNOWN_RESEARCHER, SEARCH_LIMIT") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      assert(r.shortestPath(a, e, "all-years", 2).status == PathStatus.BEYOND_DEPTH)
      assert(r.shortestPath(a, f, "all-years", 6).status == PathStatus.UNREACHABLE)
      assert(r.shortestPath(a, g, "all-years", 6).status == PathStatus.UNREACHABLE)
      assert(
        r.shortestPath(a, e, "2000-2014", 6).status == PathStatus.UNREACHABLE
      ) // no edges there
      assert(
        r.shortestPath(a, "scr:missing", "all-years", 6).status == PathStatus.UNKNOWN_RESEARCHER
      )
      assert(r.shortestPath(a, w(1), "all-years", 6).status == PathStatus.UNKNOWN_RESEARCHER)
      val small = graph.reader(limits.copy(pathMaxFrontier = 2))
      assert(small.shortestPath(a, e, "all-years", 6).status == PathStatus.SEARCH_LIMIT)
      var clock = 0L
      val slow =
        r.shortestPath(a, e, "all-years", 6, () => { clock += limits.pathTimeoutMs + 1; clock })
      assert(slow.status == PathStatus.SEARCH_LIMIT && slow.detail.contains("path-timeout-ms"))
      assertThrows[IllegalArgumentException](
        r.shortestPath(a, e, "all-years", limits.maxPathDepth + 1)
      )
      assertThrows[IllegalArgumentException](r.shortestPath(a, e, "1999", 3))
      assertThrows[IllegalArgumentException](r.shortestPath(a, a, "all-years", 3))
    }
  }

  test("coauthors: strongest first then edge ID, display names, truncated at the limit") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      val all = r.coauthors(d, "all-years", 10)
      assert(!all.truncated && all.items.map(_.coauthorId).toSet == Set(b, c, e))
      assert(
        all.items.map(_.edge.id) == all.items.map(_.edge.id).sorted
      ) // equal counts: by edge ID
      assert(all.items.forall(l => l.coauthorName.contains(s"Name ${l.coauthorId}")))
      val two = r.coauthors(d, "all-years", 2)
      assert(two.truncated && two.items == all.items.take(2))
      assertThrows[IllegalArgumentException](r.coauthors(d, "all-years", limits.graphLimit + 1))
      assert(r.coauthorEdge(b, a, "all-years").map(_.from).contains(a))
    }
  }

  test(
    "supporting works: inline lists and evidence objects, complete and checked against the count"
  ) {
    withGraph { (graph, objects) =>
      val r = graph.reader(limits)
      val ab = r.coauthorEdge(a, b, "all-years").get
      assert(r.supportingWorks(ab.id, objects) == Right(SupportingWorks(ab.id, Vector(w(1)), None)))
      val gh = r.supportingWorks(ghEdge.id, objects)
      assert(gh == Right(SupportingWorks(ghEdge.id, ghWorks.sorted, Some(ghRef))))
      val empty = FileObjectStore(Path.of("/nonexistent-storage-test")).reader
      assert(r.supportingWorks(ghEdge.id, empty).isLeft)
      assert(r.supportingWorks(w(1), objects).isLeft) // not a coauthor edge
    }
  }

  test("communities: members by internal degree, neighbors by cross edges, cross-community edges") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      val ms = r.communityMembers(c1, 10)
      assert(ms.items.map(m => m.researcher.id -> m.degree) == Vector(a -> 2L, b -> 1L, c -> 1L))
      assert(!ms.truncated && r.communityMembers(c1, 2).truncated)
      val ns = r.communityNeighbors(c1, 5)
      assert(ns.items.map(n => n.community.id -> n.crossEdges) == Vector(c2 -> 2L))
      val x = r.crossCommunityEdges(b, "all-years", 5).items
      assert(x.map(y => (y.otherId, y.communityId, y.otherCommunityId)) == Vector((d, c1, c2)))
      assert(r.crossCommunityEdges(a, "all-years", 5).items.isEmpty)
      assertThrows[IllegalArgumentException](
        r.communityMembers(c1, limits.communityMembersLimit + 1)
      )
    }
  }

  test("facets: community and researcher topics and venues in the window; member cap truncates") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      def counts(xs: Seq[FacetCount]) = xs.map(x => x.facet.id -> x.works)
      val cf = r.communityFacets(c1, Facet.TOPIC, 10)
      assert(counts(cf.items) == Vector(t1 -> 3L, t2 -> 2L) && !cf.truncated)
      val capped =
        graph.reader(limits.copy(communityFacetMemberCap = 1)).communityFacets(c1, Facet.TOPIC, 10)
      assert(
        counts(capped.items) == Vector(t1 -> 2L, t2 -> 1L) && capped.truncated
      ) // member a only
      val rf = r.researcherFacets(Seq(d, a), "2000-2014", Facet.TOPIC, 10)
      assert(
        rf.map(l => l.subjectId -> counts(l.entries)) == Vector(
          d -> Vector(t1 -> 1L),
          a -> Vector(t1 -> 2L, t2 -> 1L)
        )
      )
      val one = r.researcherFacets(Seq(a), "2000-2014", Facet.TOPIC, 1).head
      assert(counts(one.entries) == Vector(t1 -> 2L) && one.truncated)
      assert(
        counts(r.researcherFacets(Seq(a), "all-years", Facet.VENUE, 5).head.entries) == Vector(
          v1 -> 2L
        )
      )
      assert(r.researcherFacets(Seq(f), "all-years", Facet.TOPIC, 5).head.entries.isEmpty)
    }
  }

  test("institutions, topic researchers, lookup, authored counts") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      val in = r.researcherInstitutions(Seq(a, d), 5)
      assert(
        in.map(l => l.researcherId -> l.entries.map(_.institution.id)) == Vector(
          a -> Vector(i1, i2),
          d -> Vector()
        )
      )
      assert(
        in.head.entries.map(_.edge.props("via")) == Vector("MENTION", "MENTION ORCID_EMPLOYMENT")
      )
      assert(r.researcherInstitutions(Seq(a), 1).head.truncated)
      val tr = r.topicResearchers(Seq(t1), "all-years", 10)
      assert(
        tr.items.map(x => x.researcher.id -> x.works) == Vector(a -> 2L, c -> 2L, b -> 1L, d -> 1L)
      )
      assert(r.topicResearchers(Seq(t1), "2015-2026", 10).items.isEmpty)
      val lk = r.lookup(LookupLabel.Topic, "GRAPH", 5)
      assert(lk.items.map(_.id) == Vector(t2, t1) && !lk.truncated) // by name: Distributed, Graph
      assert(r.lookup(LookupLabel.Topic, "graph", 1).truncated)
      assert(r.lookup(LookupLabel.Institution, "two", 5).items.map(_.id) == Vector(i2))
      assertThrows[IllegalArgumentException](
        r.lookup(LookupLabel.Venue, "x" * (limits.maxTermChars + 1), 5)
      )
      assertThrows[IllegalArgumentException](r.lookup(LookupLabel.Venue, "  ", 5))
      assert(r.authoredCounts(Seq(a, f, g)) == Map(a -> 2L, f -> 0L, g -> 21L))
      assertThrows[IllegalArgumentException](
        graph.reader(limits.copy(idBatchSize = 2)).authoredCounts(Seq(a, b, c))
      )
    }
  }

  test("neighbors: direction, depth, edge types, node limit, and provenance on every element") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      val out = r.neighbors(a, Direction.OUT, 1, 10, Seq("AUTHORED"))
      assert(out.nodes.map(_.id).toSet == Set(w(1), w(2)) && out.edges.size == 2 && !out.truncated)
      val two = r.neighbors(a, Direction.BOTH, 2, 10, Seq("COAUTHORED_WITH"))
      assert(two.nodes.map(_.id).toSet == Set(b, c, d) && two.edges.size == 4)
      (two.nodes ++ two.edges).foreach(x =>
        assert(x.props.contains("runId") && x.props.contains("snapshot"))
      )
      assert(
        r.neighbors(a, Direction.IN, 1, 10, Seq("COAUTHORED_WITH")).nodes.isEmpty
      ) // a sorts first
      val cut = r.neighbors(a, Direction.BOTH, 2, 2, Seq("COAUTHORED_WITH"))
      assert(cut.nodes.size == 2 && cut.truncated)
      assert(r.neighbors("scr:missing", Direction.OUT, 1, 5, Seq("AUTHORED")).start.isEmpty)
      assertThrows[IllegalArgumentException](
        r.neighbors(a, Direction.OUT, limits.neighborsMaxDepth + 1, 5, Seq("AUTHORED"))
      )
      assertThrows[IllegalArgumentException](r.neighbors(a, Direction.OUT, 1, 5, Seq("KNOWS")))
    }
  }

  test("user text is only a bound value: an injection string in a lookup changes no element") {
    withGraph { (graph, _) =>
      val r = graph.reader(limits)
      val before = graph.vertexCount -> graph.edgeCount
      assert(r.lookup(LookupLabel.Topic, "x') MATCH (n) DETACH DELETE n //", 5).items.isEmpty)
      assert(graph.vertexCount -> graph.edgeCount == before)
    }
  }

  // ---- Live adapter: request building and response parsing only (no network, ruling 7) ---------

  /** An element in the Neptune openCypher JSON result form (access-graph-opencypher-queries.html).
    */
  def neptuneJson(x: GraphElement): ujson.Value =
    val props = ujson.Obj.from(x.props.toSeq.sortBy(_._1).map {
      case (k, v: String)  => k -> ujson.Str(v)
      case (k, v: Boolean) => k -> ujson.Bool(v)
      case (k, v: Int)     => k -> ujson.Num(v)
      case (k, v: Long)    => k -> ujson.Num(v.toDouble)
      case (k, v: Double)  => k -> ujson.Num(v)
    })
    if x.from.isEmpty then
      ujson.Obj(
        "~id" -> x.id,
        "~entityType" -> "node",
        "~labels" -> ujson.Arr(x.label),
        "~properties" -> props
      )
    else
      ujson.Obj(
        "~id" -> x.id,
        "~entityType" -> "relationship",
        "~start" -> x.from,
        "~end" -> x.to,
        "~type" -> x.label,
        "~properties" -> props
      )

  /** A neptunedata client that answers by template ID; it records every request. */
  final class StubNeptune(answer: (String, ujson.Obj) => ujson.Value) extends NeptunedataClient:
    val requests = scala.collection.mutable.ArrayBuffer.empty[ExecuteOpenCypherQueryRequest]
    def serviceName(): String = "neptunedata"
    def close(): Unit = ()
    def templateOf(r: ExecuteOpenCypherQueryRequest): String =
      val body =
        r.openCypherQuery.stripPrefix(s"USING QUERY:TIMEOUTMILLISECONDS ${limits.queryTimeoutMs} ")
      OpenCypherTemplates.Templates.collectFirst { case (k, t) if t == body => k }.get
    override def executeOpenCypherQuery(
        r: ExecuteOpenCypherQueryRequest
    ): ExecuteOpenCypherQueryResponse =
      requests += r
      val json = answer(templateOf(r), ujson.read(r.parameters).obj)
      ExecuteOpenCypherQueryResponse.builder().results(Documents.toDocument(json)).build()

  /** Answers the path and coauthor templates from the in-memory fake. */
  def fromFake(fake: GraphReadPort)(id: String, p: ujson.Obj): ujson.Value =
    def ids = p("ids").arr.map(_.str).toVector
    def rows(xs: Iterable[ujson.Value]) = ujson.Arr.from(xs)
    id match
      case "graph.vertices" => rows(fake.vertices(ids).map(v => ujson.Obj("n" -> neptuneJson(v))))
      case "graph.frontier" =>
        rows(
          fake
            .frontier(ids, p("window").str)
            .items
            .map((u, v) => ujson.Obj("source" -> u, "neighbor" -> v))
        )
      case "graph.coauthor-edge" =>
        rows(
          fake
            .coauthorEdge(p("a").str, p("b").str, p("window").str)
            .map(x => ujson.Obj("e" -> neptuneJson(x)))
        )
      case "graph.coauthors" =>
        val links = fake.coauthors(p("researcherId").str, p("window").str, limits.graphLimit).items
        rows(
          links
            .take(p("fetch").num.toInt)
            .map(l =>
              ujson.Obj(
                "e" -> neptuneJson(l.edge),
                "coauthorId" -> l.coauthorId,
                "coauthorName" -> l.coauthorName.get
              )
            )
        )
      case other => fail(s"unexpected template $other")

  test("live templates: fixed read-only text behind the timeout hint; every value is a parameter") {
    val writes = "(?i)\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|LOAD|CALL)\\b".r
    OpenCypherTemplates.Templates.foreach { (id, text) =>
      assert(writes.findFirstIn(text).isEmpty, id)
      val r = OpenCypherTemplates.request(id, 5000, ujson.Obj("x" -> 1))
      assert(r.openCypherQuery == s"USING QUERY:TIMEOUTMILLISECONDS 5000 $text", id)
      assert(r.parameters == """{"x":1}""")
    }
    assertThrows[IllegalArgumentException](
      OpenCypherTemplates.request("graph.anything", 5000, ujson.Obj())
    )
    val injection = "x') MATCH (n) DETACH DELETE n //"
    val stub = StubNeptune((_, _) => ujson.Arr())
    val live = NeptuneGraphReader(stub, limits)
    assert(live.lookup(LookupLabel.Topic, injection, 5).items.isEmpty)
    val r = stub.requests.head
    assert(r.openCypherQuery.endsWith(OpenCypherTemplates.Templates("graph.lookup.Topic")))
    assert(!r.openCypherQuery.contains(injection))
    assert(ujson.read(r.parameters) == ujson.Obj("text" -> injection, "fetch" -> 6))
  }

  test("live responses: nodes and relationships parse back to the same typed elements") {
    fixture.values.flatten.foreach { x =>
      val wire = Documents.fromDocument(Documents.toDocument(neptuneJson(x)))
      assert(OpenCypherTemplates.element(wire) == x, x.id)
    }
    assertThrows[IllegalStateException](OpenCypherTemplates.element(ujson.Obj("~id" -> "x")))
    assertThrows[IllegalStateException](OpenCypherTemplates.rows(ujson.Obj()))
  }

  test("live reader over a stub client: the same path and coauthors as the fake, values bound") {
    withGraph { (graph, _) =>
      val fake = graph.reader(limits)
      val stub = StubNeptune(fromFake(fake))
      val live = NeptuneGraphReader(stub, limits)
      assert(live.shortestPath(a, e, "all-years", 6) == fake.shortestPath(a, e, "all-years", 6))
      assert(live.coauthors(d, "all-years", 2) == fake.coauthors(d, "all-years", 2))
      val frontier = stub.requests.find(stub.templateOf(_) == "graph.frontier").get
      assert(ujson.read(frontier.parameters)("window").str == "all-years")
      assert(ujson.read(frontier.parameters)("fetch").num.toInt == limits.pathMaxFrontier + 1)
    }
  }

  test("live reader: a query timeout is not retried (SEARCH_LIMIT for a path); 5xx is retried") {
    withGraph { (graph, _) =>
      val fake = graph.reader(limits)
      val timeout =
        TimeLimitExceededException.builder().statusCode(500).message("timed out").build()
      val stub = StubNeptune((id, p) =>
        if id == "graph.frontier" then throw timeout else fromFake(fake)(id, p)
      )
      val live = NeptuneGraphReader(stub, limits, RetryPolicy(3, 0, 0), _ => ())
      val p = live.shortestPath(a, e, "all-years", 6)
      assert(p.status == PathStatus.SEARCH_LIMIT && p.detail.contains("graph.frontier"))
      assert(stub.requests.count(stub.templateOf(_) == "graph.frontier") == 1)
      var failures = 1
      val flaky = StubNeptune { (id, p) =>
        if failures > 0 then
          failures -= 1
          throw AwsServiceException.builder().statusCode(503).message("busy").build()
        fromFake(fake)(id, p)
      }
      val retried = NeptuneGraphReader(flaky, limits, RetryPolicy(3, 0, 0), _ => ())
      assert(retried.coauthors(d, "all-years", 2) == fake.coauthors(d, "all-years", 2))
      assert(flaky.requests.size == 2)
    }
  }
