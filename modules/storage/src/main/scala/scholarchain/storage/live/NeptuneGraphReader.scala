package scholarchain.storage.live

import scholarchain.core.config.TimeWindow
import scholarchain.storage.*
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  TimeLimitExceededException
}

/** The fixed, read-only openCypher templates of the graph read port (STORAGE.md, "Graph read
  * templates"). Every value from a caller is a bound `$parameter`; a query text is only ever one of
  * these constants, chosen by template ID (and by enum value where openCypher cannot bind a label,
  * a relationship direction, or a facet path), behind the `USING QUERY:TIMEOUTMILLISECONDS` hint
  * with the configured neptune.query-timeout-ms (opencypher-query-hints-timeout-hint.html, accessed
  * 2026-11-06). Result limits are bound as $fetch (the caller's limit plus one, to detect
  * truncation).
  */
object OpenCypherTemplates:
  val Templates: Map[String, String] = Map(
    "graph.vertex" -> "MATCH (n) WHERE id(n) = $id RETURN n",
    "graph.edge" -> "MATCH ()-[r]->() WHERE id(r) = $id RETURN r",
    "graph.vertices" -> "MATCH (n) WHERE id(n) IN $ids RETURN n",
    "graph.coauthor-edge" ->
      ("MATCH (a:Researcher)-[e:COAUTHORED_WITH]->(b:Researcher) " +
        "WHERE id(a) = $a AND id(b) = $b AND e.window = $window RETURN e LIMIT 1"),
    "graph.coauthors" ->
      ("MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher) " +
        "WHERE id(a) = $researcherId AND e.window = $window " +
        "RETURN e, id(b) AS coauthorId, b.displayName AS coauthorName " +
        "ORDER BY e.jointPublicationCount DESC, id(e) ASC LIMIT $fetch"),
    "graph.frontier" ->
      ("MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher) " +
        "WHERE id(a) IN $ids AND e.window = $window " +
        "RETURN id(a) AS source, id(b) AS neighbor LIMIT $fetch"),
    "graph.community-members" ->
      ("MATCH (m:Researcher)-[:MEMBER_OF]->(c:Community) WHERE id(c) = $communityId " +
        "OPTIONAL MATCH (m)-[e:COAUTHORED_WITH]-(o:Researcher)-[:MEMBER_OF]->(c) " +
        "WHERE e.window = c.window " +
        "WITH m, count(e) AS degree " +
        "RETURN m, degree ORDER BY degree DESC, id(m) ASC LIMIT $fetch"),
    "graph.community-neighbors" ->
      ("MATCH (c:Community)<-[:MEMBER_OF]-(m:Researcher)-[e:COAUTHORED_WITH]-(o:Researcher)" +
        "-[:MEMBER_OF]->(c2:Community) " +
        "WHERE id(c) = $communityId AND e.window = c.window AND c2.window = c.window AND c2 <> c " +
        "WITH c2, count(DISTINCT e) AS crossEdges " +
        "RETURN c2, crossEdges ORDER BY crossEdges DESC, id(c2) ASC LIMIT $fetch"),
    "graph.cross-community-edges" ->
      ("MATCH (c:Community)<-[:MEMBER_OF]-(r:Researcher)-[e:COAUTHORED_WITH]-(o:Researcher)" +
        "-[:MEMBER_OF]->(c2:Community) " +
        "WHERE id(r) = $researcherId AND e.window = $window AND c.window = $window " +
        "AND c2.window = $window AND c2 <> c " +
        "RETURN e, id(o) AS otherId, o.displayName AS otherName, id(c) AS communityId, " +
        "id(c2) AS otherCommunityId " +
        "ORDER BY e.jointPublicationCount DESC, id(e) ASC LIMIT $fetch"),
    "graph.community-facets.TOPIC" -> communityFacets("-[:HAS_TOPIC]->(f:Topic)"),
    "graph.community-facets.VENUE" -> communityFacets("-[:PUBLISHED_IN]->(f:Venue)"),
    "graph.researcher-facets.TOPIC" -> researcherFacets("-[:HAS_TOPIC]->(f:Topic)"),
    "graph.researcher-facets.VENUE" -> researcherFacets("-[:PUBLISHED_IN]->(f:Venue)"),
    "graph.researcher-institutions" ->
      ("MATCH (r:Researcher)-[a:AFFILIATED_WITH]->(i:Institution) WHERE id(r) IN $ids " +
        "RETURN id(r) AS researcherId, a, i ORDER BY researcherId, id(i) LIMIT $fetch"),
    "graph.topic-researchers" ->
      ("MATCH (r:Researcher)-[:AUTHORED]->(w:Work)-[:HAS_TOPIC]->(t:Topic) " +
        "WHERE id(t) IN $topicIds AND w.year >= $startYear AND w.year <= $endYear " +
        "WITH r, count(DISTINCT w) AS works " +
        "RETURN r, works ORDER BY works DESC, id(r) ASC LIMIT $fetch"),
    "graph.lookup.Topic" -> lookup("Topic"),
    "graph.lookup.Venue" -> lookup("Venue"),
    "graph.lookup.Institution" -> lookup("Institution"),
    "graph.authored-counts" ->
      ("MATCH (r:Researcher)-[a:AUTHORED]->(:Work) WHERE id(r) IN $ids " +
        "RETURN id(r) AS researcherId, count(a) AS works"),
    "graph.expand.OUT" -> expand("(a)-[r]->(b)"),
    "graph.expand.IN" -> expand("(a)<-[r]-(b)"),
    "graph.expand.BOTH" -> expand("(a)-[r]-(b)")
  )

  private def communityFacets(facetPath: String) =
    "MATCH (m:Researcher)-[:MEMBER_OF]->(c:Community) WHERE id(c) = $communityId " +
      "WITH c, m ORDER BY id(m) LIMIT $memberCap " +
      s"MATCH (m)-[:AUTHORED]->(w:Work)$facetPath " +
      "WHERE w.year >= c.windowStartYear AND w.year <= c.windowEndYear " +
      "WITH f, count(DISTINCT w) AS works " +
      "RETURN f, works ORDER BY works DESC, id(f) ASC LIMIT $fetch"

  private def researcherFacets(facetPath: String) =
    s"MATCH (r:Researcher)-[:AUTHORED]->(w:Work)$facetPath " +
      "WHERE id(r) IN $ids AND w.year >= $startYear AND w.year <= $endYear " +
      "WITH id(r) AS researcherId, f, count(DISTINCT w) AS works " +
      "RETURN researcherId, f, works ORDER BY researcherId, works DESC, id(f) LIMIT $fetch"

  private def lookup(label: String) =
    s"MATCH (n:$label) WHERE toLower(n.name) CONTAINS toLower($$text) " +
      "RETURN n ORDER BY n.name, id(n) LIMIT $fetch"

  private def expand(pattern: String) =
    s"MATCH $pattern WHERE id(a) IN $$ids AND type(r) IN $$edgeTypes " +
      "RETURN r, b ORDER BY id(r), id(b) LIMIT $fetch"

  /** The request for one template: its constant text behind the timeout hint, parameters as JSON.
    */
  def request(
      templateId: String,
      timeoutMs: Int,
      parameters: ujson.Obj
  ): ExecuteOpenCypherQueryRequest =
    val text =
      Templates.getOrElse(templateId, throw IllegalArgumentException(s"no template $templateId"))
    require(timeoutMs >= 1, s"timeout $timeoutMs")
    ExecuteOpenCypherQueryRequest
      .builder()
      .openCypherQuery(s"USING QUERY:TIMEOUTMILLISECONDS $timeoutMs $text")
      .parameters(parameters.render())
      .build()

  /** The rows of a response `results` value (access-graph-opencypher-queries.html, accessed
    * 2026-11-06).
    */
  def rows(results: ujson.Value): Vector[ujson.Obj] = results match
    case ujson.Arr(xs) =>
      xs.toVector.map {
        case o: ujson.Obj => o
        case other        =>
          throw IllegalStateException(s"result row is not an object: ${other.render().take(80)}")
      }
    case other => throw IllegalStateException(s"results is not a list: ${other.render().take(80)}")

  /** A node or relationship value ({"~id", "~labels" | "~start", "~end", "~type", "~properties"})
    * as a GraphElement, with each property typed by the bulk-load contract column of its label.
    */
  def element(v: ujson.Value): GraphElement =
    val o = v.obj
    def s(k: String) = o.get(k).flatMap(_.strOpt).getOrElse(throw IllegalStateException(s"no $k"))
    val (kind, label, from, to) = o.get("~entityType").flatMap(_.strOpt) match
      case Some("node") =>
        val labels = o.get("~labels").flatMap(_.arrOpt).map(_.flatMap(_.strOpt)).getOrElse(Nil)
        (ElementKind.Vertex, labels.headOption.getOrElse(""), "", "")
      case Some("relationship") => (ElementKind.Edge, s("~type"), s("~start"), s("~end"))
      case other => throw IllegalStateException(s"not a node or relationship: $other")
    val columns = NeptuneContract.All
      .find(f => f.kind == kind && f.label == label)
      .fold(Map.empty[String, PropType])(_.propColumns.map(c => c.name -> c.tpe).toMap)
    val props = o
      .get("~properties")
      .flatMap(_.objOpt)
      .fold(Map.empty[String, Prop])(_.toMap.map { (k, x) =>
        k -> prop(k, x, columns.get(k))
      })
    GraphElement(s("~id"), label, from, to, props)

  private def prop(k: String, x: ujson.Value, t: Option[PropType]): Prop = (t, x) match
    case (Some(PropType.IntT), ujson.Num(n)) if n.isWhole  => n.toInt
    case (Some(PropType.LongT), ujson.Num(n)) if n.isWhole => n.toLong
    case (Some(PropType.DoubleT), ujson.Num(n))            => n
    case (Some(PropType.BoolT), ujson.Bool(b))             => b
    case (Some(PropType.StringT), ujson.Str(s))            => s
    case (None, ujson.Str(s))                              => s
    case (None, ujson.Bool(b))                             => b
    case (None, ujson.Num(n)) if n.isWhole && n.abs < 1e15 => n.toLong
    case (None, ujson.Num(n))                              => n
    case _                                                 =>
      throw IllegalStateException(s"property $k has an unexpected value ${x.render().take(40)}")

  def str(row: ujson.Obj, k: String): String =
    row.value.get(k).flatMap(_.strOpt).getOrElse(throw IllegalStateException(s"no $k in row"))
  def optStr(row: ujson.Obj, k: String): Option[String] = row.value.get(k).flatMap(_.strOpt)
  def long(row: ujson.Obj, k: String): Long =
    row.value
      .get(k)
      .flatMap(_.numOpt)
      .map(_.toLong)
      .getOrElse(throw IllegalStateException(s"no $k in row"))

/** Live GraphReadPort over Neptune openCypher (ExecuteOpenCypherQuery), for Phase 10 runtime
  * services. The client must use read-only credentials (neptune-db:ReadDataViaQuery only); every
  * template is a read. Transient failures are retried by policy; a query timeout is not retried and
  * surfaces as GraphReadTimeoutException.
  */
final class NeptuneGraphReader(
    client: NeptunedataClient,
    val limits: GraphReadLimits,
    policy: RetryPolicy = RetryPolicy.Default,
    sleep: Long => Unit = ms => Thread.sleep(ms / 2 + scala.util.Random.nextLong(ms / 2 + 1))
) extends GraphReadPort:
  import OpenCypherTemplates.*

  private def run(templateId: String, params: (String, ujson.Value)*): Vector[ujson.Obj] =
    val r = request(templateId, limits.queryTimeoutMs, ujson.Obj.from(params))
    val response = Retry
      .run(policy, sleep) {
        LiveAws.guarded(
          try client.executeOpenCypherQuery(r)
          catch
            case _: TimeLimitExceededException =>
              throw GraphReadTimeoutException(s"$templateId passed ${limits.queryTimeoutMs} ms")
        )
      }
      ._1
    rows(Documents.fromDocument(response.results))

  private def ids(xs: Seq[String]) = ujson.Arr.from(xs.map(ujson.Str(_)))
  private def years(w: TimeWindow) =
    Seq("startYear" -> ujson.Num(w.startYear), "endYear" -> ujson.Num(w.endYear))

  protected def readVertex(id: String) =
    run("graph.vertex", "id" -> id).headOption.map(r => element(r("n")))
  protected def readEdge(id: String) =
    run("graph.edge", "id" -> id).headOption.map(r => element(r("r")))
  protected def readVertices(xs: Seq[String]) =
    val byId =
      run("graph.vertices", "ids" -> ids(xs)).map(r => element(r("n"))).map(e => e.id -> e).toMap
    xs.flatMap(byId.get).toVector

  protected def readCoauthorEdge(a: String, b: String, window: String) =
    run("graph.coauthor-edge", "a" -> a, "b" -> b, "window" -> window).headOption.map(r =>
      element(r("e"))
    )

  protected def readCoauthors(researcherId: String, window: String, fetch: Int) =
    run("graph.coauthors", "researcherId" -> researcherId, "window" -> window, "fetch" -> fetch)
      .map(r => CoauthorLink(element(r("e")), str(r, "coauthorId"), optStr(r, "coauthorName")))

  protected def readFrontier(xs: Seq[String], window: String, fetch: Int) =
    run("graph.frontier", "ids" -> ids(xs), "window" -> window, "fetch" -> fetch)
      .map(r => str(r, "source") -> str(r, "neighbor"))

  protected def readCommunityMembers(communityId: String, fetch: Int) =
    run("graph.community-members", "communityId" -> communityId, "fetch" -> fetch)
      .map(r => CommunityMember(element(r("m")), long(r, "degree")))

  protected def readCommunityNeighbors(communityId: String, fetch: Int) =
    run("graph.community-neighbors", "communityId" -> communityId, "fetch" -> fetch)
      .map(r => CommunityLink(element(r("c2")), long(r, "crossEdges")))

  protected def readCrossCommunityEdges(researcherId: String, window: String, fetch: Int) =
    run(
      "graph.cross-community-edges",
      "researcherId" -> researcherId,
      "window" -> window,
      "fetch" -> fetch
    ).map(r =>
      CrossCommunityEdge(
        element(r("e")),
        str(r, "otherId"),
        optStr(r, "otherName"),
        str(r, "communityId"),
        str(r, "otherCommunityId")
      )
    )

  protected def readCommunityFacets(communityId: String, facet: Facet, memberCap: Int, fetch: Int) =
    run(
      s"graph.community-facets.$facet",
      "communityId" -> communityId,
      "memberCap" -> memberCap,
      "fetch" -> fetch
    ).map(r => FacetCount(element(r("f")), long(r, "works")))

  protected def readResearcherFacets(xs: Seq[String], w: TimeWindow, facet: Facet, fetch: Int) =
    run(
      s"graph.researcher-facets.$facet",
      (Seq("ids" -> ids(xs), "fetch" -> ujson.Num(fetch)) ++ years(w))*
    )
      .map(r => str(r, "researcherId") -> FacetCount(element(r("f")), long(r, "works")))

  protected def readInstitutions(xs: Seq[String], fetch: Int) =
    run("graph.researcher-institutions", "ids" -> ids(xs), "fetch" -> fetch)
      .map(r => str(r, "researcherId") -> InstitutionLink(element(r("a")), element(r("i"))))

  protected def readTopicResearchers(topicIds: Seq[String], w: TimeWindow, fetch: Int) =
    run(
      "graph.topic-researchers",
      (Seq("topicIds" -> ids(topicIds), "fetch" -> ujson.Num(fetch)) ++ years(w))*
    )
      .map(r => ResearcherCount(element(r("r")), long(r, "works")))

  protected def readLookup(label: LookupLabel, text: String, fetch: Int) =
    run(s"graph.lookup.$label", "text" -> text, "fetch" -> fetch).map(r => element(r("n")))

  protected def readAuthoredCounts(xs: Seq[String]) =
    run("graph.authored-counts", "ids" -> ids(xs))
      .map(r => str(r, "researcherId") -> long(r, "works"))
      .toMap

  protected def readExpand(xs: Seq[String], d: Direction, types: Seq[String], fetch: Int) =
    run(s"graph.expand.$d", "ids" -> ids(xs), "edgeTypes" -> ids(types), "fetch" -> fetch)
      .map(r => element(r("r")) -> element(r("b")))
