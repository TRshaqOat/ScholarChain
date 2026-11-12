package scholarchain.storage

import scholarchain.core.CanonicalPair
import scholarchain.core.config.{ScholarChainConfig, TimeWindow}

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/** Bounds of every graph read template (Phase 9 ruling 7), all from configuration: `graph-read.*`,
  * `retrieval.graph-limit`, `retrieval.max-path-depth`, `runtime.planner.max-term-chars`,
  * `neptune.query-timeout-ms`, and the windows of `graph.windows`.
  */
final case class GraphReadLimits(
    windows: Map[String, TimeWindow],
    maxResults: Int,
    idBatchSize: Int,
    graphLimit: Int,
    maxPathDepth: Int,
    pathMaxFrontier: Int,
    pathTimeoutMs: Int,
    communityMembersLimit: Int,
    communityNeighborsLimit: Int,
    communityFacetMemberCap: Int,
    facetLimit: Int,
    lookupLimit: Int,
    maxLookupIds: Int,
    maxTermChars: Int,
    neighborsMaxDepth: Int,
    neighborsMaxLimit: Int,
    queryTimeoutMs: Int
):
  def window(name: String): TimeWindow =
    windows.getOrElse(name, throw IllegalArgumentException(s"unknown window '$name'"))

  def range(what: String, n: Int, max: Int): Unit =
    require(n >= 1 && n <= max, s"$what $n out of range 1 to $max")

  def ids(what: String, xs: Seq[String], max: Int): Unit =
    require(xs.nonEmpty && xs.size <= max, s"$what: ${xs.size} IDs, allowed 1 to $max")
    require(xs.forall(_.nonEmpty), s"$what: empty ID")

object GraphReadLimits:
  def from(c: ScholarChainConfig): GraphReadLimits =
    val g = c.graphRead
    GraphReadLimits(
      ProvenanceRules.windowsByName(c.graph.windows),
      g.maxResults,
      g.idBatchSize,
      c.retrieval.graphLimit,
      c.retrieval.maxPathDepth,
      g.pathMaxFrontier,
      g.pathTimeoutMs,
      g.communityMembersLimit,
      g.communityNeighborsLimit,
      g.communityFacetMemberCap,
      g.facetLimit,
      g.lookupLimit,
      g.maxLookupIds,
      c.runtime.planner.maxTermChars,
      g.neighborsMaxDepth,
      g.neighborsMaxLimit,
      c.neptune.queryTimeoutMs
    )

/** A result cut to its limit; truncated is true when the store held more. */
final case class Bounded[A](items: Vector[A], truncated: Boolean)

object Bounded:
  /** rows were fetched with limit + 1, so one row too many means more exist. */
  def cut[A](rows: Vector[A], limit: Int): Bounded[A] = Bounded(rows.take(limit), rows.size > limit)

/** A COAUTHORED_WITH edge seen from researcherId: the other end and its display name. */
final case class CoauthorLink(edge: GraphElement, coauthorId: String, coauthorName: Option[String])

/** The complete sorted supporting work IDs of a COAUTHORED_WITH edge; ref is the evidence object
  * key when the list is not inline.
  */
final case class SupportingWorks(edgeId: String, workIds: Vector[String], ref: Option[String])

enum PathStatus:
  case FOUND, BEYOND_DEPTH, UNREACHABLE, UNKNOWN_RESEARCHER, SEARCH_LIMIT

/** Hop from -> to over edge (the canonical COAUTHORED_WITH edge, with its provenance). */
final case class PathHop(from: String, to: String, edge: GraphElement)

/** researcherIds runs source to target (FOUND only); visited counts the researchers reached. */
final case class PathResult(
    status: PathStatus,
    maxDepth: Int,
    researcherIds: Vector[String],
    hops: Vector[PathHop],
    visited: Int,
    detail: String
)

/** degree: the member's COAUTHORED_WITH edges to other members, in the community's window. */
final case class CommunityMember(researcher: GraphElement, degree: Long)

/** crossEdges: COAUTHORED_WITH edges in the window between the two communities. */
final case class CommunityLink(community: GraphElement, crossEdges: Long)

final case class CrossCommunityEdge(
    edge: GraphElement,
    otherId: String,
    otherName: Option[String],
    communityId: String,
    otherCommunityId: String
)

enum Facet:
  case TOPIC, VENUE

/** works: distinct works (Work.year in the window) with this facet. */
final case class FacetCount(facet: GraphElement, works: Long)
final case class FacetList(subjectId: String, entries: Vector[FacetCount], truncated: Boolean)

/** edge is the AFFILIATED_WITH edge (its `via` property says how); not windowed. */
final case class InstitutionLink(edge: GraphElement, institution: GraphElement)
final case class InstitutionList(
    researcherId: String,
    entries: Vector[InstitutionLink],
    truncated: Boolean
)

final case class ResearcherCount(researcher: GraphElement, works: Long)

enum LookupLabel:
  case Topic, Venue, Institution

enum Direction:
  case OUT, IN, BOTH

/** The nodes reached from start (start excluded) and the edges traversed between them. */
final case class Neighborhood(
    start: Option[GraphElement],
    nodes: Vector[GraphElement],
    edges: Vector[GraphElement],
    truncated: Boolean
)

/** Thrown when Neptune stops a template at its query timeout. Not transient: the same query would
  * time out again.
  */
final class GraphReadTimeoutException(message: String) extends RuntimeException(message)

/** Read side of the graph store (STORAGE.md, "Graph read templates"); runtime services get only
  * this type. Each protected `read*` method is one fixed template whose parameters are bound, never
  * concatenated (law 4): openCypher in live.NeptuneGraphReader, the same semantics on the in-memory
  * fake. The public methods check every parameter against `limits` first, so the fake and the live
  * adapter enforce the same bounds. Every returned element carries runId, snapshot, and
  * schemaVersion (edges also policyVersion) in its properties.
  */
trait GraphReadPort:
  def limits: GraphReadLimits

  protected def readVertex(id: String): Option[GraphElement]
  protected def readEdge(id: String): Option[GraphElement]
  protected def readVertices(ids: Seq[String]): Vector[GraphElement]

  /** a < b (CanonicalPair). */
  protected def readCoauthorEdge(a: String, b: String, window: String): Option[GraphElement]

  /** Strongest first (jointPublicationCount descending, then edge ID), at most fetch rows. */
  protected def readCoauthors(
      researcherId: String,
      window: String,
      fetch: Int
  ): Vector[CoauthorLink]

  /** (id, neighbor) for every COAUTHORED_WITH edge in the window, at most fetch rows. */
  protected def readFrontier(ids: Seq[String], window: String, fetch: Int): Vector[(String, String)]
  protected def readCommunityMembers(communityId: String, fetch: Int): Vector[CommunityMember]
  protected def readCommunityNeighbors(communityId: String, fetch: Int): Vector[CommunityLink]
  protected def readCrossCommunityEdges(
      researcherId: String,
      window: String,
      fetch: Int
  ): Vector[CrossCommunityEdge]

  /** Over the first memberCap members by ID. */
  protected def readCommunityFacets(
      communityId: String,
      facet: Facet,
      memberCap: Int,
      fetch: Int
  ): Vector[FacetCount]

  /** (researcherId, count), ordered by researcherId, works descending, then facet ID. */
  protected def readResearcherFacets(
      ids: Seq[String],
      window: TimeWindow,
      facet: Facet,
      fetch: Int
  ): Vector[(String, FacetCount)]

  /** (researcherId, link), ordered by researcherId, then institution ID. */
  protected def readInstitutions(ids: Seq[String], fetch: Int): Vector[(String, InstitutionLink)]
  protected def readTopicResearchers(
      topicIds: Seq[String],
      window: TimeWindow,
      fetch: Int
  ): Vector[ResearcherCount]
  protected def readLookup(label: LookupLabel, text: String, fetch: Int): Vector[GraphElement]
  protected def readAuthoredCounts(ids: Seq[String]): Map[String, Long]

  /** (edge, other end) of the ids' edges of the given types, ordered by edge ID, then node ID. */
  protected def readExpand(
      ids: Seq[String],
      direction: Direction,
      edgeTypes: Seq[String],
      fetch: Int
  ): Vector[(GraphElement, GraphElement)]

  // ---- graph.vertex, graph.edge, graph.vertices -------------------------------------------------

  final def vertex(id: String): Option[GraphElement] = readVertex(id)
  final def edge(id: String): Option[GraphElement] = readEdge(id)

  final def vertices(ids: Seq[String]): Vector[GraphElement] =
    limits.ids("vertices", ids, limits.idBatchSize)
    readVertices(ids.distinct)

  // ---- graph.coauthor-edge, graph.coauthors, graph.supporting-works -----------------------------

  /** The COAUTHORED_WITH edge of a researcher pair (any order) in a window. */
  final def coauthorEdge(x: String, y: String, window: String): Option[GraphElement] =
    limits.window(window)
    CanonicalPair(x, y).toOption.flatMap((a, b) => readCoauthorEdge(a, b, window))

  /** limit 1 to retrieval.graph-limit. */
  final def coauthors(researcherId: String, window: String, limit: Int): Bounded[CoauthorLink] =
    limits.window(window)
    limits.range("coauthors limit", limit, limits.graphLimit)
    Bounded.cut(readCoauthors(researcherId, window, limit + 1), limit)

  /** Inline list, or the evidence object read from evidence; the count must equal the edge's. */
  final def supportingWorks(
      edgeId: String,
      evidence: ObjectReadPort
  ): Either[String, SupportingWorks] =
    readEdge(edgeId).filter(_.label == NeptuneContract.CoauthoredWith.label) match
      case None    => Left(s"no COAUTHORED_WITH edge $edgeId")
      case Some(e) =>
        val count = e.props.get("jointPublicationCount").collect { case l: Long => l }
        val found = (e.props.get("supportingWorkIds"), e.props.get("supportingWorkIdsRef")) match
          case (Some(ids: String), _) =>
            Right(SupportingWorks(edgeId, ids.split(' ').toVector, None))
          case (_, Some(ref: String)) =>
            evidence.get(ref).toRight(s"evidence object $ref not found").flatMap { b =>
              scala.util
                .Try(upickle.default.read[CoauthorEvidence](new String(b, UTF_8)))
                .toOption
                .filter(ev =>
                  ev.schemaVersion == NeptuneContract.EvidenceSchemaVersion && ev.edgeId == edgeId
                )
                .toRight(s"evidence object $ref does not belong to $edgeId")
                .map(ev => SupportingWorks(edgeId, ev.workIds, Some(ref)))
            }
          case _ => Left(s"$edgeId has no supporting work IDs")
        found.filterOrElse(
          s => count.contains(s.workIds.size.toLong),
          s"supporting work count differs from jointPublicationCount of $edgeId"
        )

  // ---- graph.frontier, graph.shortest-path ------------------------------------------------------

  /** One BFS step: (id, neighbor) pairs; truncated when more than graph-read.path-max-frontier. */
  final def frontier(ids: Seq[String], window: String): Bounded[(String, String)] =
    limits.window(window)
    limits.ids("frontier", ids, limits.idBatchSize)
    Bounded.cut(
      readFrontier(ids.distinct, window, limits.pathMaxFrontier + 1),
      limits.pathMaxFrontier
    )

  /** Bounded bidirectional breadth-first search over graph.frontier (Neptune has no
    * shortestPath()). Full levels are expanded from the side with the smaller frontier until the
    * two balls meet, which gives the length L. The path is then read back from the target with the
    * Spark job 5 rule: each step takes the smallest neighbor one step closer to the source
    * (modules/spark/JOBS.md), so a FOUND path equals the stored path for the same pair and window.
    * Beyond the source ball, "one step closer" is decided with the shortest-path layers, built
    * forward from the meeting layer over the neighbor lists read on the target side.
    *
    * SEARCH_LIMIT when more than graph-read.path-max-frontier researchers are reached on both sides
    * together, a frontier read is cut at that many rows or times out, or graph-read.path-timeout-ms
    * passes; it never means "no path".
    */
  final def shortestPath(
      source: String,
      target: String,
      window: String,
      maxDepth: Int,
      nowMs: () => Long = () => Throttle.monotonicMs()
  ): PathResult =
    limits.window(window)
    limits.range("maxDepth", maxDepth, limits.maxPathDepth)
    require(source != target, "source and target are the same researcher")
    val started = nowMs()
    val known = readVertices(Seq(source, target)).filter(_.label == "Researcher").map(_.id).toSet
    val distS = mutable.HashMap(source -> 0)
    val distT = mutable.HashMap(target -> 0)
    val adjT =
      mutable.HashMap.empty[String, Vector[String]] // neighbor lists read on the target side
    var limit: Option[String] = None
    def result(s: PathStatus, detail: String) =
      PathResult(s, maxDepth, Vector.empty, Vector.empty, distS.size + distT.size, detail)

    /** One frontier read; None (and limit set) when a bound stops the search. */
    def read(ids: Seq[String]): Option[Vector[(String, String)]] =
      if nowMs() - started > limits.pathTimeoutMs then
        limit = Some(s"path-timeout-ms ${limits.pathTimeoutMs} passed")
        None
      else
        try
          val rows = frontier(ids, window)
          if rows.truncated then
            limit = Some(s"a frontier read passed ${limits.pathMaxFrontier} rows")
          Option.when(limit.isEmpty)(rows.items)
        catch
          case e: GraphReadTimeoutException =>
            limit = Some(e.getMessage)
            None

    /** The next full level of one side; keep stores the neighbor lists (target side only). */
    def expand(level: Vector[String], dist: mutable.HashMap[String, Int], d: Int, keep: Boolean) =
      val next = Vector.newBuilder[String]
      val batches = level.sorted.grouped(limits.idBatchSize)
      while limit.isEmpty && batches.hasNext do
        read(batches.next()).foreach(_.foreach { (u, v) =>
          if keep then adjT(u) = adjT.getOrElse(u, Vector.empty) :+ v
          if !dist.contains(v) then
            dist(v) = d
            next += v
        })
        if distS.size + distT.size > limits.pathMaxFrontier then
          limit = Some(s"more than ${limits.pathMaxFrontier} researchers reached")
      next.result()

    if !known(source) || !known(target) then
      result(
        PathStatus.UNKNOWN_RESEARCHER,
        s"not a researcher: ${Seq(source, target).filterNot(known).mkString(" ")}"
      )
    else
      var fs = Vector(source)
      var ft = Vector(target)
      var rs, rt = 0
      var length: Option[Int] = None
      while limit.isEmpty && length.isEmpty && rs + rt < maxDepth && fs.nonEmpty && ft.nonEmpty do
        if fs.size <= ft.size then
          rs += 1
          fs = expand(fs, distS, rs, keep = false)
          length = fs.flatMap(u => distT.get(u).map(rs + _)).minOption
        else
          rt += 1
          ft = expand(ft, distT, rt, keep = true)
          length = ft.flatMap(u => distS.get(u).map(rt + _)).minOption
      (limit, length) match
        case (Some(why), _)  => result(PathStatus.SEARCH_LIMIT, why)
        case (None, Some(l)) =>
          // Shortest-path layers from m0, the first layer where both distances are known.
          val m0 = math.max(0, l - rt)
          val byDistT = distT.toVector.groupMap(_._2)(_._1)
          val layers = mutable.HashMap(m0 -> distS.collect {
            case (u, `m0`) if distT.get(u).contains(l - m0) => u
          }.toSet)
          for m <- m0 until l do
            layers(m + 1) = byDistT
              .getOrElse(l - m - 1, Vector.empty)
              .filter(v => adjT.getOrElse(v, Vector.empty).exists(layers(m)))
              .toSet
          def closer(u: String, k: Int) = // u is one step closer to the source than layer k
            if k - 1 >= m0 then layers(k - 1)(u) else distS.get(u).contains(k - 1)
          var path = Vector(target)
          var k = l
          while limit.isEmpty && k >= 1 do
            val cur = path.head
            adjT.get(cur).orElse(read(Seq(cur)).map(_.map(_._2))).foreach { ns =>
              val parent = ns
                .filter(closer(_, k))
                .minOption
                .getOrElse(
                  throw IllegalStateException(s"no predecessor of $cur at distance ${k - 1}")
                )
              path = parent +: path
            }
            k -= 1
          limit match
            case Some(why) => result(PathStatus.SEARCH_LIMIT, why)
            case None      =>
              val hops = path.zip(path.tail).map { (a, b) =>
                val e = coauthorEdge(a, b, window).getOrElse(
                  throw IllegalStateException(s"frontier edge $a - $b in $window has no edge")
                )
                PathHop(a, b, e)
              }
              PathResult(
                PathStatus.FOUND,
                maxDepth,
                path,
                hops,
                distS.size + distT.size,
                s"length $l"
              )
        case _ if fs.isEmpty || ft.isEmpty => result(PathStatus.UNREACHABLE, s"no path in $window")
        case _ => result(PathStatus.BEYOND_DEPTH, s"no path within $maxDepth hops")

  // ---- communities ------------------------------------------------------------------------------

  /** limit 1 to graph-read.community-members-limit; within-community degree descending, then ID. */
  final def communityMembers(communityId: String, limit: Int): Bounded[CommunityMember] =
    limits.range("community members limit", limit, limits.communityMembersLimit)
    Bounded.cut(readCommunityMembers(communityId, limit + 1), limit)

  /** limit 1 to graph-read.community-neighbors-limit; cross edges descending, then ID. */
  final def communityNeighbors(communityId: String, limit: Int): Bounded[CommunityLink] =
    limits.range("community neighbors limit", limit, limits.communityNeighborsLimit)
    Bounded.cut(readCommunityNeighbors(communityId, limit + 1), limit)

  /** limit 1 to retrieval.graph-limit; joint publications descending, then edge ID. */
  final def crossCommunityEdges(
      researcherId: String,
      window: String,
      limit: Int
  ): Bounded[CrossCommunityEdge] =
    limits.window(window)
    limits.range("cross-community limit", limit, limits.graphLimit)
    Bounded.cut(readCrossCommunityEdges(researcherId, window, limit + 1), limit)

  /** Facets of the works (in the community's own window) of at most
    * graph-read.community-facet-member-cap members (the first by ID); truncated also when the
    * community is larger than the cap.
    */
  final def communityFacets(communityId: String, facet: Facet, limit: Int): Bounded[FacetCount] =
    limits.range("facet limit", limit, limits.facetLimit)
    val cap = limits.communityFacetMemberCap
    val capped = readVertex(communityId)
      .flatMap(_.props.get("communitySize"))
      .collect { case n: Long => n > cap }
      .getOrElse(false)
    val b = Bounded.cut(readCommunityFacets(communityId, facet, cap, limit + 1), limit)
    b.copy(truncated = b.truncated || capped)

  // ---- facets, institutions, topics, lookup, counts ---------------------------------------------

  /** Per researcher, the facets of its AUTHORED works with Work.year in the window (perLimit 1 to
    * graph-read.facet-limit). Every requested ID gets a list.
    */
  final def researcherFacets(
      ids: Seq[String],
      window: String,
      facet: Facet,
      perLimit: Int
  ): Vector[FacetList] =
    limits.ids("researcher facets", ids, limits.idBatchSize)
    limits.range("facet limit", perLimit, limits.facetLimit)
    val w = limits.window(window)
    val rows = Bounded.cut(
      readResearcherFacets(ids.distinct, w, facet, limits.maxResults + 1),
      limits.maxResults
    )
    perSubject(ids.distinct, rows, perLimit)((id, es, t) => FacetList(id, es, t))

  /** Per researcher, AFFILIATED_WITH institutions (not windowed), perLimit 1 to facet-limit. */
  final def researcherInstitutions(ids: Seq[String], perLimit: Int): Vector[InstitutionList] =
    limits.ids("researcher institutions", ids, limits.idBatchSize)
    limits.range("institution limit", perLimit, limits.facetLimit)
    val rows = Bounded.cut(readInstitutions(ids.distinct, limits.maxResults + 1), limits.maxResults)
    perSubject(ids.distinct, rows, perLimit)((id, es, t) => InstitutionList(id, es, t))

  private def perSubject[A, R](ids: Seq[String], rows: Bounded[(String, A)], perLimit: Int)(
      make: (String, Vector[A], Boolean) => R
  ): Vector[R] =
    val by = rows.items.groupMap(_._1)(_._2)
    ids.toVector.map { id =>
      val all = by.getOrElse(id, Vector.empty)
      make(id, all.take(perLimit), all.size > perLimit || rows.truncated)
    }

  /** Researchers by their AUTHORED works in the window that have any of the topics. */
  final def topicResearchers(
      topicIds: Seq[String],
      window: String,
      limit: Int
  ): Bounded[ResearcherCount] =
    limits.ids("topic researchers", topicIds, limits.maxLookupIds)
    limits.range("topic researchers limit", limit, limits.graphLimit)
    Bounded.cut(readTopicResearchers(topicIds.distinct, limits.window(window), limit + 1), limit)

  /** Vertices of the label whose name contains text, case-insensitive, by name then ID. The text is
    * a bound parameter only.
    */
  final def lookup(label: LookupLabel, text: String, limit: Int): Bounded[GraphElement] =
    require(
      text.trim.nonEmpty && text.length <= limits.maxTermChars,
      s"lookup text must be 1 to ${limits.maxTermChars} characters"
    )
    limits.range("lookup limit", limit, limits.lookupLimit)
    Bounded.cut(readLookup(label, text.trim, limit + 1), limit)

  /** AUTHORED edge count per requested researcher (0 when none; resolution works, M9 interim). */
  final def authoredCounts(ids: Seq[String]): Map[String, Long] =
    limits.ids("authored counts", ids, limits.idBatchSize)
    val found = readAuthoredCounts(ids.distinct)
    ids.distinct.map(id => id -> found.getOrElse(id, 0L)).toMap

  // ---- graph.neighbors (Phase 10 endpoint) -------------------------------------------------------

  /** Nodes within depth (1 to graph-read.neighbors-max-depth) of nodeId over the edge types, at
    * most limit (1 to graph-read.neighbors-max-limit) nodes, expanded level by level with
    * graph.expand. Only reached nodes are expanded, so at most limit nodes are ever read.
    */
  final def neighbors(
      nodeId: String,
      direction: Direction,
      depth: Int,
      limit: Int,
      edgeTypes: Seq[String]
  ): Neighborhood =
    limits.range("neighbors depth", depth, limits.neighborsMaxDepth)
    limits.range("neighbors limit", limit, limits.neighborsMaxLimit)
    val allowed = NeptuneContract.Edges.map(_.label).toSet
    require(
      edgeTypes.nonEmpty && edgeTypes.forall(allowed),
      s"edge types must be a non-empty subset of ${allowed.toVector.sorted.mkString(", ")}"
    )
    readVertex(nodeId) match
      case None        => Neighborhood(None, Vector.empty, Vector.empty, truncated = false)
      case Some(start) =>
        val seen = mutable.LinkedHashMap(nodeId -> start)
        val kept = mutable.LinkedHashMap.empty[String, GraphElement]
        var truncated = false
        var level = Vector(nodeId)
        var d = 0
        while d < depth && level.nonEmpty do
          val next = Vector.newBuilder[String]
          level.grouped(limits.idBatchSize).foreach { ids =>
            val rows = Bounded.cut(
              readExpand(ids, direction, edgeTypes.distinct, limits.maxResults + 1),
              limits.maxResults
            )
            truncated ||= rows.truncated
            rows.items.foreach { (e, n) =>
              if !seen.contains(n.id) then
                if seen.size - 1 < limit then
                  seen(n.id) = n
                  next += n.id
                else truncated = true
              if seen.contains(e.from) && seen.contains(e.to) then kept(e.id) = e
            }
          }
          level = next.result()
          d += 1
        Neighborhood(Some(start), seen.values.drop(1).toVector, kept.values.toVector, truncated)
