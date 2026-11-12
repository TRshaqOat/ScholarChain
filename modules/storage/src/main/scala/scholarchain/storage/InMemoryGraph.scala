package scholarchain.storage

import scholarchain.core.config.TimeWindow

import java.nio.file.Path
import java.util.Locale
import scala.collection.mutable

/** In-memory local fake of the Neptune graph. A reload of an element with the same ID and the same
  * properties is a no-op; the same ID with different properties is refused (Neptune would keep the
  * old values without saying so unless updateSingleCardinalityProperties is set).
  *
  * Reads (`reader(limits)`) implement every graph read template with the same semantics and bounds
  * as live.NeptuneGraphReader, over two indexes: the edge IDs leaving and entering each node.
  */
final class InMemoryGraph:
  private val vertexById = mutable.HashMap.empty[String, GraphElement]
  private val edges = mutable.HashMap.empty[String, GraphElement]
  private val pairIndex = mutable.HashMap.empty[(String, String, String), String]
  private val outAdj = mutable.HashMap.empty[String, Vector[String]]
  private val inAdj = mutable.HashMap.empty[String, Vector[String]]

  def vertexCount: Int = vertexById.size
  def edgeCount: Int = edges.size

  private val CW = NeptuneContract.CoauthoredWith.label
  private val MemberOf = NeptuneContract.MemberOf.label
  private val Authored = NeptuneContract.Authored.label

  private def outE(id: String) = outAdj.getOrElse(id, Vector.empty).map(edges)
  private def inE(id: String) = inAdj.getOrElse(id, Vector.empty).map(edges)
  private def other(e: GraphElement, id: String) = if e.from == id then e.to else e.from
  private def str(e: GraphElement, k: String) = e.props.get(k).collect { case s: String => s }
  private def int(e: GraphElement, k: String) = e.props.get(k).collect { case i: Int => i }
  private def count(e: GraphElement) =
    e.props.get("jointPublicationCount").collect { case l: Long => l }.getOrElse(0L)

  private def coauthorEdges(r: String, window: String) =
    (outE(r) ++ inE(r)).filter(e => e.label == CW && str(e, "window").contains(window))
  private def communityOf(r: String, window: String) =
    outE(r).find(e => e.label == MemberOf && str(e, "window").contains(window)).map(_.to)
  private def members(c: String) = inE(c).filter(_.label == MemberOf).map(_.from)
  private def worksIn(r: String, s: Int, t: Int) =
    outE(r)
      .filter(_.label == Authored)
      .map(_.to)
      .distinct
      .filter(w => vertexById.get(w).flatMap(int(_, "year")).exists(y => s <= y && y <= t))
  private def facetsOf(w: String, f: Facet) =
    val t = if f == Facet.TOPIC then NeptuneContract.HasTopic else NeptuneContract.PublishedIn
    outE(w).filter(_.label == t.label).map(_.to).distinct
  private def facetCounts(works: Iterable[String], f: Facet): Vector[FacetCount] =
    works
      .flatMap(w => facetsOf(w, f).map(_ -> w))
      .groupMapReduce(_._1)(_ => 1L)(_ + _)
      .toVector
      .map((id, n) => FacetCount(vertexById(id), n))
      .sortBy(x => (-x.works, x.facet.id))

  def reader(l: GraphReadLimits): GraphReadPort = new GraphReadPort:
    val limits = l
    private def sync[A](a: => A): A = InMemoryGraph.this.synchronized(a)

    protected def readVertex(id: String) = sync(vertexById.get(id))
    protected def readEdge(id: String) = sync(edges.get(id))
    protected def readVertices(ids: Seq[String]) = sync(ids.flatMap(vertexById.get).toVector)
    protected def readCoauthorEdge(a: String, b: String, window: String) =
      sync(pairIndex.get((a, b, window)).map(edges))

    protected def readCoauthors(r: String, window: String, fetch: Int) = sync(
      coauthorEdges(r, window)
        .sortBy(e => (-count(e), e.id))
        .take(fetch)
        .map { e =>
          val o = other(e, r)
          CoauthorLink(e, o, vertexById.get(o).flatMap(str(_, "displayName")))
        }
    )

    protected def readFrontier(ids: Seq[String], window: String, fetch: Int) = sync(
      ids.iterator
        .flatMap(id => coauthorEdges(id, window).map(e => id -> other(e, id)))
        .take(fetch)
        .toVector
    )

    protected def readCommunityMembers(c: String, fetch: Int) = sync(
      vertexById.get(c).flatMap(str(_, "window")).fold(Vector.empty[CommunityMember]) { w =>
        val ms = members(c)
        val in = ms.toSet
        ms.map(m =>
          CommunityMember(vertexById(m), coauthorEdges(m, w).count(e => in(other(e, m))).toLong)
        ).sortBy(x => (-x.degree, x.researcher.id))
          .take(fetch)
      }
    )

    protected def readCommunityNeighbors(c: String, fetch: Int) = sync(
      vertexById.get(c).flatMap(str(_, "window")).fold(Vector.empty[CommunityLink]) { w =>
        members(c)
          .flatMap(m => coauthorEdges(m, w).flatMap(e => communityOf(other(e, m), w)))
          .filter(_ != c)
          .groupMapReduce(identity)(_ => 1L)(_ + _)
          .toVector
          .map((id, n) => CommunityLink(vertexById(id), n))
          .sortBy(x => (-x.crossEdges, x.community.id))
          .take(fetch)
      }
    )

    protected def readCrossCommunityEdges(r: String, window: String, fetch: Int) = sync(
      communityOf(r, window).fold(Vector.empty[CrossCommunityEdge]) { c =>
        coauthorEdges(r, window)
          .flatMap { e =>
            val o = other(e, r)
            communityOf(o, window)
              .filter(_ != c)
              .map(c2 =>
                CrossCommunityEdge(e, o, vertexById.get(o).flatMap(str(_, "displayName")), c, c2)
              )
          }
          .sortBy(x => (-count(x.edge), x.edge.id))
          .take(fetch)
      }
    )

    protected def readCommunityFacets(c: String, facet: Facet, memberCap: Int, fetch: Int) = sync(
      vertexById.get(c).fold(Vector.empty[FacetCount]) { cv =>
        (int(cv, "windowStartYear"), int(cv, "windowEndYear")) match
          case (Some(s), Some(t)) =>
            val works = members(c).sorted.take(memberCap).flatMap(worksIn(_, s, t)).distinct
            facetCounts(works, facet).take(fetch)
          case _ => Vector.empty
      }
    )

    protected def readResearcherFacets(ids: Seq[String], w: TimeWindow, facet: Facet, fetch: Int) =
      sync(
        ids.sorted.iterator
          .flatMap(id => facetCounts(worksIn(id, w.startYear, w.endYear), facet).map(id -> _))
          .take(fetch)
          .toVector
      )

    protected def readInstitutions(ids: Seq[String], fetch: Int) = sync(
      ids.sorted.iterator
        .flatMap(id =>
          outE(id)
            .filter(_.label == NeptuneContract.AffiliatedWith.label)
            .sortBy(_.to)
            .map(e => id -> InstitutionLink(e, vertexById(e.to)))
        )
        .take(fetch)
        .toVector
    )

    protected def readTopicResearchers(topicIds: Seq[String], w: TimeWindow, fetch: Int) = sync {
      val works = topicIds
        .flatMap(t => inE(t).filter(_.label == NeptuneContract.HasTopic.label).map(_.from))
        .distinct
        .filter(x =>
          vertexById.get(x).flatMap(int(_, "year")).exists(y => w.startYear <= y && y <= w.endYear)
        )
      works
        .flatMap(x => inE(x).filter(_.label == Authored).map(_.from).distinct)
        .groupMapReduce(identity)(_ => 1L)(_ + _)
        .toVector
        .map((r, n) => ResearcherCount(vertexById(r), n))
        .sortBy(x => (-x.works, x.researcher.id))
        .take(fetch)
    }

    protected def readLookup(label: LookupLabel, text: String, fetch: Int) = sync {
      val t = text.toLowerCase(Locale.ROOT)
      vertexById.valuesIterator
        .filter(v =>
          v.label == label.toString && str(v, "name").exists(_.toLowerCase(Locale.ROOT).contains(t))
        )
        .toVector
        .sortBy(v => (str(v, "name").getOrElse(""), v.id))
        .take(fetch)
    }

    protected def readAuthoredCounts(ids: Seq[String]) = sync(
      ids.map(id => id -> outE(id).count(_.label == Authored).toLong).filter(_._2 > 0).toMap
    )

    protected def readExpand(ids: Seq[String], d: Direction, types: Seq[String], fetch: Int) =
      sync {
        val ts = types.toSet
        ids
          .flatMap { id =>
            val es = d match
              case Direction.OUT  => outE(id)
              case Direction.IN   => inE(id)
              case Direction.BOTH => outE(id) ++ inE(id)
            es.filter(e => ts(e.label)).map(e => e -> vertexById(other(e, id)))
          }
          .sortBy((e, n) => (e.id, n.id))
          .take(fetch)
          .toVector
      }

  val loader: GraphLoadPort = new GraphLoadPort:
    def load(
        root: Path,
        windows: Seq[TimeWindow],
        evidence: Option[ObjectReadPort]
    ): Either[Vector[Violation], GraphLoadReport] =
      InMemoryGraph.this.synchronized {
        // Compare while streaming: only new elements are buffered, so a reload holds no copy.
        val newV = Vector.newBuilder[GraphElement]
        val newE = Vector.newBuilder[GraphElement]
        val found = Vector.newBuilder[Violation]
        var sameV, sameE = 0
        val scan = NeptuneCsv.scan(
          root,
          windows,
          evidence,
          (kind, e) =>
            val (existing, fresh) =
              if kind == ElementKind.Vertex then (vertexById, newV) else (edges, newE)
            existing.get(e.id) match
              case None                  => fresh += e
              case Some(old) if old == e =>
                if kind == ElementKind.Vertex then sameV += 1 else sameE += 1
              case Some(_) =>
                found += Violation(
                  "graph",
                  0,
                  "PROPERTY_CONFLICT",
                  s"${e.id} exists with different properties"
                )
        )
        val conflicts = found.result()
        if scan.violations.nonEmpty then Left(scan.violations)
        else if conflicts.nonEmpty then Left(conflicts)
        else Right(add(newV.result(), newE.result(), sameV, sameE))
      }

  private def add(
      newV: Vector[GraphElement],
      newE: Vector[GraphElement],
      sameV: Int,
      sameE: Int
  ): GraphLoadReport =
    newV.foreach(e => vertexById(e.id) = e)
    newE.foreach { e =>
      edges(e.id) = e
      outAdj(e.from) = outAdj.getOrElse(e.from, Vector.empty) :+ e.id
      inAdj(e.to) = inAdj.getOrElse(e.to, Vector.empty) :+ e.id
      if e.label == CW then pairIndex((e.from, e.to, e.props("window").toString)) = e.id
    }
    GraphLoadReport(newV.size, newE.size, sameV, sameE)
