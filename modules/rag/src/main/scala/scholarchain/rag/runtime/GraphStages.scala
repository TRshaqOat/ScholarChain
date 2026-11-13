package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{
  CollaborationMetric,
  Provenance,
  QueryOperation,
  ResearcherMetrics,
  SchemaVersions,
  SourceType
}
import scholarchain.storage
import scholarchain.storage.{
  AttributeValue,
  DynamoContract,
  Facet,
  GraphElement,
  GraphReadPort,
  Item,
  KeyValueReadPort,
  LookupLabel,
  ObjectReadPort,
  TableName,
  WindowSummary
}

/** A plan name resolved to one researcher (index into QueryPlan.researcherNames). */
final case class Resolved(index: Int, researcherId: String, name: String)

/** Stages 3 to 5. names: display names of every researcher, community, topic, or work met (for
  * evidence facts). componentSubjects: researchers whose component is asked for. unsupported: the
  * operations that produced only missing-evidence entries. stageMs: the time of each of stages 3 to
  * 5 that ran (review 1 m1); a stage with no work is absent. The corpus fields are the stored
  * window summary rows the CORPUS scopes read (work order 9, F2): components and communities of a
  * window, and (from, to) rows of a stored comparison; corpusSource is their store object;
  * limitations are the fixed lines the rows carry (node basis, convergence, window lengths).
  */
final case class GraphStageResult(
    facts: GraphFacts,
    names: Map[String, String],
    componentSubjects: Vector[String],
    unsupported: Vector[QueryOperation],
    stageMs: Map[Stage, Long],
    corpusComponents: Vector[WindowSummary] = Vector.empty,
    corpusCommunities: Vector[WindowSummary] = Vector.empty,
    corpusComparisons: Vector[(WindowSummary, WindowSummary)] = Vector.empty,
    corpusSource: String = "",
    limitations: Vector[String] = Vector.empty
):
  def corpusEmpty: Boolean =
    corpusComponents.isEmpty && corpusCommunities.isEmpty && corpusComparisons.isEmpty

object GraphStages:
  /** The stage an operation's reads belong to (RUNTIME.md section 2); SEMANTIC_EXPLANATION reads
    * nothing here.
    */
  def stageOf(op: QueryOperation): Option[Stage] =
    import QueryOperation.*
    op match
      case RESEARCHER_RANKING | MAXIMUM_TEAM_SIZE | TEMPORAL_COMPARISON =>
        Some(Stage.COLLABORATION_METRICS)
      case DIRECT_COAUTHORS | SHORTEST_PATH | COMPONENT_LOOKUP => Some(Stage.PATH_FINDING)
      case COMMUNITY_COMPARISON | BRIDGE_RANKING | TOPIC_FILTER | VENUE_FILTER |
          INSTITUTION_FILTER =>
        Some(Stage.COMMUNITY_ANALYSIS)
      case SEMANTIC_EXPLANATION => None

/** Stages 3 (COLLABORATION_METRICS), 4 (PATH_FINDING), and 5 (COMMUNITY_ANALYSIS): deterministic
  * reads through the storage ports only (fixed templates, bound IDs and enum values; RUNTIME.md
  * sections 6 to 8). Every value comes from a store item or graph element with its run ID.
  */
final class GraphStages(
    plan: QueryPlan,
    resolved: Vector[Resolved],
    graph: GraphReadPort,
    kv: KeyValueReadPort,
    objects: ObjectReadPort,
    cfg: ScholarChainConfig,
    log: CallLog,
    graphRunId: String,
    summaries: Option[WindowSummarySource] = None
):
  import ResolutionPort.*
  private val w = plan.window.window
  private val rankings = Vector.newBuilder[RankingRecord]
  private val changes = Vector.newBuilder[ChangeRecord]
  private val coauthors = Vector.newBuilder[CoauthorList]
  private val paths = Vector.newBuilder[PathRecord]
  private val communities = Vector.newBuilder[CommunityRecord]
  private val facets = Vector.newBuilder[FacetRecord]
  private val works = collection.mutable.LinkedHashMap.empty[String, WorkRef]
  private val missing = Vector.newBuilder[MissingEvidence]
  private val names = collection.mutable.HashMap.from(resolved.map(r => r.researcherId -> r.name))
  private val metricOf = collection.mutable.HashMap.empty[String, Option[MetricRecord]]
  private val doneCommunities = collection.mutable.HashSet.empty[String]
  private val doneChanges = collection.mutable.HashSet.empty[(String, WindowComparison)]
  private val components = Vector.newBuilder[String]
  private val corpusComponents = Vector.newBuilder[WindowSummary]
  private val corpusCommunities = Vector.newBuilder[WindowSummary]
  private val corpusComparisons = Vector.newBuilder[(WindowSummary, WindowSummary)]
  private val limitations = Vector.newBuilder[String]
  private def nameOf(id: String) = names.getOrElse(id, id)
  private def miss(r: MissingReason, subject: String, detail: String) =
    missing += MissingEvidence(r, subject, detail)

  private def prov(e: GraphElement): Provenance =
    def p(k: String) = e.props.get(k).map(_.toString)
    Provenance(
      SchemaVersions.Provenance,
      SourceType.DERIVED,
      e.id,
      p("snapshot").getOrElse(""),
      p("runId").getOrElse(""),
      p("policyVersion"),
      None
    )

  private def itemProv(i: Item, id: String): Provenance =
    Provenance(
      SchemaVersions.Provenance,
      SourceType.DERIVED,
      id,
      s(i, "snapshot").getOrElse(""),
      s(i, "runId").getOrElse(""),
      s(i, "policyVersion"),
      None
    )

  /** Display names (and work titles) of IDs, read in batches. */
  private def vertices(ids: Seq[String]): Map[String, GraphElement] =
    ids.distinct
      .grouped(graph.limits.idBatchSize)
      .flatMap(b => log("graph.vertices")(graph.vertices(b))(_.size))
      .map { v =>
        v.props
          .get("displayName")
          .orElse(v.props.get("name"))
          .foreach(n => names(v.id) = n.toString)
        v.id -> v
      }
      .toMap

  private def addWorks(ids: Seq[String]): Unit =
    vertices(ids.filterNot(works.contains)).values.filter(_.label == "Work").foreach { v =>
      works(v.id) = WorkRef(
        v.id,
        v.props.get("title").map(_.toString),
        v.props.get("year").collect { case y: Int => y },
        prov(v)
      )
    }

  // ---- stage 3: metrics ---------------------------------------------------------------------

  private def num(i: Item, k: String): Option[BigDecimal] = n(i, k)

  private def metric(id: String): Option[MetricRecord] =
    metricOf.getOrElseUpdate(
      id, {
        log("kv.metrics.window")(kv.get(TableName.ResearcherMetricCache, id, s"WINDOW#$w"))(
          _.size
        ) match
          case None =>
            miss(
              MissingReason.NO_METRIC_ROW,
              id,
              s"${nameOf(id)} has no DBLP publication in $w, so no metric row."
            )
            None
          case Some(i) =>
            def l(k: String) = num(i, k).fold(0L)(_.toLong)
            val teamIds = i.get("maximumTeamWorkIds") match
              case Some(AttributeValue.L(xs)) => xs.collect { case AttributeValue.S(x) => x }
              case _                          => Vector.empty
            if i.contains("maximumTeamWorkIdsRef") then
              miss(
                MissingReason.TRUNCATED,
                id,
                s"the maximum-team work IDs of ${nameOf(id)} are stored as a pointer and not read"
              )
            val schema = s(i, "schemaVersion").getOrElse("")
            val run = s(i, "runId").getOrElse("")
            Some(
              MetricRecord(
                w,
                ResearcherMetrics(
                  SchemaVersions.ResearcherMetrics,
                  id,
                  l("windowStartYear").toInt,
                  l("windowEndYear").toInt,
                  l("publicationCount"),
                  l("distinctCoauthorCount"),
                  l("totalCoauthorOccurrences"),
                  num(i, "averageCoauthorsPerPaper").fold(0d)(_.toDouble),
                  l("maximumCoauthorsOnOnePaper"),
                  teamIds,
                  num(i, "weightedDegree").fold(0d)(_.toDouble),
                  s(i, "componentId").getOrElse(""),
                  l("componentSize"),
                  s(i, "communityId"),
                  num(i, "crossCommunityDegree").map(_.toLong),
                  num(i, "communitiesConnected").map(_.toLong),
                  num(i, "participationCoefficient").map(_.toDouble),
                  num(i, "coreness").map(_.toInt),
                  itemProv(i, id)
                ),
                num(i, "communitySize").map(_.toLong),
                num(i, "graphDegree").map(_.toLong),
                s(i, "communityRunId"),
                s"$schema@$run"
              )
            )
      }
    )

  private def change(id: String, c: WindowComparison): Boolean =
    if doneChanges.contains((id, c)) then true
    else
      doneChanges += ((id, c))
      log("kv.metrics.change")(
        kv.get(TableName.ResearcherMetricCache, id, s"CHANGE#${c.fromWindow}>${c.toWindow}")
      )(_.size) match
        case None =>
          miss(
            MissingReason.NO_METRIC_ROW,
            id,
            s"${nameOf(id)} has no publication in ${c.fromWindow} or ${c.toWindow}, so no stored comparison."
          )
          false
        case Some(i) =>
          val skip =
            Set("researcherId", "metricKey", "runId", "snapshot", "schemaVersion", "policyVersion")
          val values = i.collect {
            case (k, AttributeValue.N(v)) if !skip(k)    => k -> AttributeValue.canonicalNumber(v)
            case (k, AttributeValue.S(v)) if !skip(k)    => k -> v
            case (k, AttributeValue.Bool(v)) if !skip(k) => k -> v.toString
          }
          changes += ChangeRecord(
            id,
            c,
            s(i, "presence").getOrElse(""),
            values,
            s"${s(i, "schemaVersion").getOrElse("")}@${s(i, "runId").getOrElse("")}",
            itemProv(i, id)
          )
          true

  private def ranking(m: CollaborationMetric, topN: Option[Int]): Boolean =
    val attr = MetricAttributes.attribute(m)
    if !MetricAttributes.ranked.contains(m) then
      miss(MissingReason.NO_STORED_RANKING, attr, s"There is no stored ranking of $attr.")
      false
    else
      log("kv.ranking")(
        kv.get(TableName.ResearcherMetricCache, s"${DynamoContract.RankingPrefix}$attr", s"TOP#$w")
      )(_.size) match
        case None =>
          miss(MissingReason.NOT_STORED, attr, s"No stored ranking of $attr in $w.")
          false
        case Some(i) =>
          val entries = i.get("entries") match
            case Some(AttributeValue.L(xs)) =>
              xs.collect { case AttributeValue.M(e) =>
                (
                  n(e, "rank").fold(0)(_.toInt),
                  s(e, "researcherId").getOrElse(""),
                  n(e, "value").fold(0d)(_.toDouble)
                )
              }
            case _ => Vector.empty
          val wanted = topN.getOrElse(cfg.runtime.rankings.defaultTopN)
          if wanted > entries.size then
            miss(
              MissingReason.NOT_IN_STORED_TOP_N,
              attr,
              s"Requested the top $wanted; only the stored top ${entries.size} of $attr in $w exist."
            )
          val (kept, filteredBy) = filter(entries.map(_._2))
          val shown = entries.filter(e => kept(e._2)).take(wanted)
          vertices(shown.map(_._2))
          rankings += RankingRecord(
            m,
            w,
            plan.window.startYear,
            plan.window.endYear,
            shown.map((r, id, v) => RankingEntry(r, id, v, names.get(id))),
            entries.size,
            entries.lastOption.fold(0d)(_._3),
            filteredBy,
            s"${s(i, "schemaVersion").getOrElse("")}@${s(i, "runId").getOrElse("")}",
            itemProv(i, s"${DynamoContract.RankingPrefix}$attr")
          )
          if filteredBy.nonEmpty then
            missing += MissingEvidence(
              MissingReason.TRUNCATED,
              attr,
              s"Filtered from the stored top ${entries.size}; researchers outside it are not considered."
            )
          true

  /** Topic, venue, and institution filters on stored ranking entries (section 6). */
  private def filter(ids: Vector[String]): (Set[String], Vector[String]) =
    def ids_(label: LookupLabel, terms: Vector[String]): Vector[String] =
      terms.flatMap { t =>
        val found = log("graph.lookup")(graph.lookup(label, t, graph.limits.lookupLimit))(
          _.items.size,
          _.truncated
        )
        if found.items.isEmpty then
          miss(
            MissingReason.UNSUPPORTED_FILTER,
            label.toString,
            s"No ${label.toString.toLowerCase} matches '$t'; that filter was not applied."
          )
        found.items.map(_.id)
      }.distinct
    var keep = ids.toSet
    val by = Vector.newBuilder[String]
    def byFacet(label: LookupLabel, facet: Facet, terms: Vector[String]): Unit =
      val wanted = ids_(label, terms).toSet
      if wanted.nonEmpty then
        val lists = ids
          .grouped(graph.limits.idBatchSize)
          .flatMap(b =>
            log("graph.researcher-facets")(
              graph.researcherFacets(b, w, facet, graph.limits.facetLimit)
            )(_.size)
          )
        val ok = lists.filter(_.entries.exists(e => wanted(e.facet.id))).map(_.subjectId).toSet
        keep = keep.intersect(ok)
        by ++= wanted.toVector.sorted.map(id => s"${facet.toString.toLowerCase}:$id")
    byFacet(LookupLabel.Topic, Facet.TOPIC, plan.topics)
    byFacet(LookupLabel.Venue, Facet.VENUE, plan.venues)
    val inst = ids_(LookupLabel.Institution, plan.institutions).toSet
    if inst.nonEmpty then
      val lists = ids
        .grouped(graph.limits.idBatchSize)
        .flatMap(b =>
          log("graph.researcher-institutions")(
            graph.researcherInstitutions(b, graph.limits.facetLimit)
          )(_.size)
        )
      keep = keep.intersect(
        lists.filter(_.entries.exists(e => inst(e.institution.id))).map(_.researcherId).toSet
      )
      by ++= inst.toVector.sorted.map(id => s"institution:$id")
    (keep, by.result())

  // ---- stage 4: coauthors and paths --------------------------------------------------------

  private def link(e: GraphElement, from: String): CoauthorLink =
    val sw =
      graph.supportingWorks(e.id, objects).fold(err => throw IllegalStateException(err), identity)
    val other = if e.from == from then e.to else e.from
    def int(k: String) = e.props.get(k).collect { case i: Int => i }.getOrElse(0)
    CoauthorLink(
      e.id,
      from,
      other,
      names.get(other),
      e.props.get("window").fold(w)(_.toString),
      e.props.get("jointPublicationCount").collect { case l: Long => l }.getOrElse(0L),
      int("firstCollaborationYear"),
      int("lastCollaborationYear"),
      sw.workIds,
      sw.ref,
      prov(e)
    )

  private def pairEdge(x: String, y: String): Unit =
    val e = log("graph.coauthor-edge")(graph.coauthorEdge(x, y, w))(_.size)
    val links = e.toVector.map(link(_, x))
    links.foreach(l => addWorks(l.supportingWorkIds))
    coauthors += CoauthorList(x, w, links, 1, truncated = false, Some(y), crossCommunity = false)

  private def strongest(x: String, limit: Int): Unit =
    val b = log("graph.coauthors")(graph.coauthors(x, w, limit))(_.items.size, _.truncated)
    b.items.foreach(l => l.coauthorName.foreach(names(l.coauthorId) = _))
    coauthors += CoauthorList(
      x,
      w,
      b.items.map(l => link(l.edge, x)),
      limit,
      b.truncated,
      None,
      crossCommunity = false
    )

  private def path(x: String, y: String): Unit =
    (metric(x), metric(y)) match
      case (Some(mx), Some(my)) if mx.metrics.componentId != my.metrics.componentId =>
        components ++= Vector(x, y)
        paths += PathRecord(
          x,
          y,
          w,
          PathStatus.UNREACHABLE,
          plan.limits.maxPathDepth,
          None,
          Vector.empty,
          Vector.empty,
          "kv.metrics.window",
          graphRunId
        )
      case (Some(_), Some(_)) =>
        val r = log("graph.shortest-path")(graph.shortestPath(x, y, w, plan.limits.maxPathDepth))(
          _.researcherIds.size
        )
        val status = PathStatus.valueOf(r.status.toString)
        val hops = r.hops.zipWithIndex.map((h, k) => PathHop(k + 1, link(h.edge, h.from)))
        vertices(r.researcherIds)
        hops.foreach(h => addWorks(h.link.supportingWorkIds))
        if status == PathStatus.SEARCH_LIMIT then
          miss(
            MissingReason.TRUNCATED,
            s"$x $y",
            "No path was established within the search limits."
          )
        paths += PathRecord(
          x,
          y,
          w,
          status,
          r.maxDepth,
          Option.when(status == PathStatus.FOUND)(r.hops.size),
          r.researcherIds,
          hops.map(h => h.copy(link = h.link.copy(coauthorName = names.get(h.link.coauthorId)))),
          "graph.shortest-path",
          graphRunId
        )
      case _ =>
        paths += PathRecord(
          x,
          y,
          w,
          PathStatus.UNKNOWN_RESEARCHER,
          plan.limits.maxPathDepth,
          None,
          Vector.empty,
          Vector.empty,
          "kv.metrics.window",
          graphRunId
        )

  // ---- stage 5: communities, bridges, facets -----------------------------------------------

  private def community(cid: String): Unit =
    if doneCommunities.add(cid) then
      log("graph.vertex")(graph.vertex(cid))(_.size) match
        case None    => miss(MissingReason.NOT_STORED, cid, s"Community $cid is not in the graph.")
        case Some(v) =>
          def l(k: String) = v.props.get(k).collect { case x: Long => x }.getOrElse(0L)
          val members = log("graph.community-members")(
            graph.communityMembers(
              cid,
              math.min(graph.limits.communityMembersLimit, plan.limits.graphLimit)
            )
          )(_.items.size, _.truncated)
          val neighbors = log("graph.community-neighbors")(
            graph.communityNeighbors(cid, graph.limits.communityNeighborsLimit)
          )(_.items.size, _.truncated)
          members.items.foreach(m =>
            m.researcher.props.get("displayName").foreach(n => names(m.researcher.id) = n.toString)
          )
          communities += CommunityRecord(
            cid,
            v.props.get("window").fold(w)(_.toString),
            l("communitySize"),
            l("internalEdges"),
            l("crossCommunityEdges"),
            v.props.get("communityLabel").map(_.toString),
            members.items.map(m =>
              NamedCount(m.researcher.id, names.getOrElse(m.researcher.id, ""), Some(m.degree))
            ),
            members.truncated,
            neighbors.items.map(c => NamedCount(c.community.id, "", Some(c.crossEdges))),
            prov(v)
          )
          val f = log("graph.community-facets")(
            graph.communityFacets(cid, Facet.TOPIC, graph.limits.facetLimit)
          )(
            _.items.size,
            _.truncated
          )
          facets += FacetRecord(
            cid,
            FacetKind.TOPIC,
            v.props.get("window").map(_.toString),
            f.items.map(c =>
              NamedCount(c.facet.id, c.facet.props.get("name").fold("")(_.toString), Some(c.works))
            ),
            s"distinct works (in the community's window) of at most ${graph.limits.communityFacetMemberCap} members, by topic",
            f.truncated,
            prov(v)
          )

  private def researcherFacets(id: String, facet: Facet): Unit =
    val list = log("graph.researcher-facets")(
      graph.researcherFacets(Seq(id), w, facet, graph.limits.facetLimit)
    )(_.size).head
    facets += FacetRecord(
      id,
      if facet == Facet.TOPIC then FacetKind.TOPIC else FacetKind.VENUE,
      Some(w),
      list.entries.map(c =>
        NamedCount(c.facet.id, c.facet.props.get("name").fold("")(_.toString), Some(c.works))
      ),
      s"AUTHORED works (any source) with a year in $w, by ${facet.toString.toLowerCase}",
      list.truncated,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        id,
        plan.provenance.snapshot,
        graphRunId,
        None,
        None
      )
    )

  private def institutions(id: String): Unit =
    val list = log("graph.researcher-institutions")(
      graph.researcherInstitutions(Seq(id), graph.limits.facetLimit)
    )(_.size).head
    facets += FacetRecord(
      id,
      FacetKind.INSTITUTION,
      None,
      list.entries.map(l =>
        NamedCount(l.institution.id, l.institution.props.get("name").fold("")(_.toString), None)
      ),
      "AFFILIATED_WITH edges (mentions and ORCID employment); not windowed",
      list.truncated,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        id,
        plan.provenance.snapshot,
        graphRunId,
        None,
        None
      )
    )

  private def topicResearchers(topN: Option[Int]): Boolean =
    val topicIds = plan.topics
      .flatMap { t =>
        val found = log("graph.lookup")(
          graph.lookup(LookupLabel.Topic, t, graph.limits.lookupLimit)
        )(_.items.size, _.truncated)
        if found.items.isEmpty then
          miss(MissingReason.UNSUPPORTED_FILTER, "Topic", s"No topic name contains '$t'.")
        found.items.foreach(v => names(v.id) = v.props.get("name").fold("")(_.toString))
        found.items.map(_.id)
      }
      .distinct
      .take(graph.limits.maxLookupIds)
    if topicIds.isEmpty then
      if plan.topics.isEmpty then
        miss(MissingReason.UNSUPPORTED_FILTER, "Topic", "No topic was named.")
      false
    else
      val limit = math.min(topN.getOrElse(cfg.runtime.rankings.defaultTopN), plan.limits.graphLimit)
      val rs = log("graph.topic-researchers")(graph.topicResearchers(topicIds, w, limit))(
        _.items.size,
        _.truncated
      )
      rs.items.foreach(r =>
        r.researcher.props.get("displayName").foreach(n => names(r.researcher.id) = n.toString)
      )
      val p = Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        "graph.topic-researchers",
        plan.provenance.snapshot,
        graphRunId,
        None,
        None
      )
      facets += FacetRecord(
        s"topics:${topicIds.sorted.mkString(",")}",
        FacetKind.TOPIC,
        Some(w),
        rs.items.map(r =>
          NamedCount(r.researcher.id, names.getOrElse(r.researcher.id, ""), Some(r.works))
        ),
        s"researchers by AUTHORED works with a year in $w having any of the matched topics: " +
          topicIds.map(id => s"${names.getOrElse(id, "")} ($id)").mkString("; "),
        rs.truncated,
        p
      )
      true

  // ---- operations ---------------------------------------------------------------------------

  private def corpusOnly(op: QueryOperation): Boolean =
    miss(
      MissingReason.NOT_STORED,
      op.toString,
      s"Corpus-level ${op.toString.toLowerCase.replace('_', ' ')} statistics are not stored in this version."
    )
    false

  /** The stored window summary rows of the given windows (obj.window-summary, read through the
    * validated port), or None when no port is wired (NOT_STORED, as before F2) or a row is missing.
    */
  private def corpusRows(op: QueryOperation, ws: Vector[String]): Option[Vector[WindowSummary]] =
    summaries match
      case None      => Option.when(corpusOnly(op))(Vector.empty)
      case Some(src) =>
        val rows = ws.map(x => x -> log("obj.window-summary")(src.port.windowSummary(x))(_.size))
        rows.collect { case (x, None) => x }.foreach { x =>
          miss(MissingReason.NOT_STORED, x, s"No corpus window summary is stored for $x.")
        }
        val found = rows.flatMap(_._2)
        found.foreach(r =>
          limitations += s"Graph nodes in ${r.window} (corpus counts): ${r.nodeBasis}."
        )
        Option.when(found.size == ws.size)(found)

  private def notConverged(r: WindowSummary): Unit =
    if !r.communityConverged then
      limitations += s"Communities in ${r.window} did not converge: detection stopped after " +
        s"${r.communityRounds} rounds, so community counts depend on that cap and its tie-break."

  private def operation(op: PlannedOperation, ids: Vector[String]): Boolean =
    import QueryOperation.*
    (op.operation, op.scope) match
      case (RESEARCHER_RANKING, _) =>
        ranking(op.metric.getOrElse(CollaborationMetric.DISTINCT_COAUTHOR_COUNT), op.topN)
      case (MAXIMUM_TEAM_SIZE, OperationScope.CORPUS) =>
        ranking(CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER, op.topN)
      case (MAXIMUM_TEAM_SIZE, _) =>
        ids.flatMap(metric).map(m => addWorks(m.metrics.maximumTeamWorkIds)).nonEmpty
      case (DIRECT_COAUTHORS, OperationScope.PAIR) =>
        pairEdge(ids(0), ids(1))
        true
      case (DIRECT_COAUTHORS, _) =>
        ids.foreach(
          strongest(
            _,
            math.min(op.topN.getOrElse(cfg.runtime.rankings.defaultTopN), plan.limits.graphLimit)
          )
        )
        true
      case (SHORTEST_PATH, _) =>
        path(ids(0), ids(1))
        true
      case (COMPONENT_LOOKUP, OperationScope.CORPUS) =>
        corpusRows(COMPONENT_LOOKUP, Vector(w)).map(corpusComponents ++= _).isDefined
      case (COMPONENT_LOOKUP, _) =>
        val found = ids.filter(metric(_).isDefined)
        components ++= found
        val pairs =
          Windows.comparisons(cfg.graph.windows).filter(c => c.fromWindow == w || c.toWindow == w)
        if pairs.isEmpty then
          found.foreach(id =>
            miss(
              MissingReason.NOT_STORED,
              id,
              s"Whether ${nameOf(id)} is in the largest component of $w is not stored in this version."
            )
          )
        else for id <- found; c <- pairs do change(id, c)
        found.nonEmpty
      case (COMMUNITY_COMPARISON, OperationScope.CORPUS) =>
        corpusRows(COMMUNITY_COMPARISON, Vector(w)).map { rs =>
          rs.foreach(notConverged)
          corpusCommunities ++= rs
        }.isDefined
      case (COMMUNITY_COMPARISON, _) =>
        val cids = ids.flatMap(metric).flatMap(_.metrics.communityId)
        cids.foreach(community)
        cids.nonEmpty
      case (BRIDGE_RANKING, OperationScope.CORPUS) =>
        op.metric.fold(MetricAttributes.bridge)(Vector(_)).map(ranking(_, op.topN)).exists(identity)
      case (BRIDGE_RANKING, _) =>
        val found = ids.filter(metric(_).isDefined)
        found.foreach { id =>
          val limit = math.min(cfg.runtime.rankings.defaultTopN, plan.limits.graphLimit)
          val b = log("graph.cross-community-edges")(graph.crossCommunityEdges(id, w, limit))(
            _.items.size,
            _.truncated
          )
          b.items.foreach(c => c.otherName.foreach(names(c.otherId) = _))
          coauthors += CoauthorList(
            id,
            w,
            b.items.map(c => link(c.edge, id)),
            limit,
            b.truncated,
            None,
            crossCommunity = true
          )
        }
        found.nonEmpty
      case (TEMPORAL_COMPARISON, OperationScope.CORPUS) =>
        plan.comparison match
          case None =>
            miss(
              MissingReason.NO_STORED_COMPARISON,
              "comparison",
              "No stored window comparison exists."
            )
            false
          case Some(c) =>
            corpusRows(TEMPORAL_COMPARISON, Vector(c.fromWindow, c.toWindow)).map { rs =>
              val (a, b) = (rs(0), rs(1))
              if a.years != b.years then
                limitations += s"The compared windows differ in length: ${a.window} covers " +
                  s"${a.years} years and ${b.window} covers ${b.years} years; counts are not " +
                  "normalized by window length."
              corpusComparisons += ((a, b))
            }.isDefined
      case (TEMPORAL_COMPARISON, _) =>
        plan.comparison match
          case None =>
            miss(
              MissingReason.NO_STORED_COMPARISON,
              "comparison",
              "No stored window comparison exists."
            )
            false
          case Some(c) => ids.map(change(_, c)).exists(identity)
      case (TOPIC_FILTER, OperationScope.CORPUS) => topicResearchers(op.topN)
      case (TOPIC_FILTER, _)                     =>
        ids.foreach(researcherFacets(_, Facet.TOPIC))
        true
      case (VENUE_FILTER, _) =>
        ids.foreach(researcherFacets(_, Facet.VENUE))
        true
      case (INSTITUTION_FILTER, _) =>
        val allYears = plan.window.window == Windows.corpusWindowName(cfg) && plan.window.exact
        if !allYears then
          ids.foreach(id =>
            miss(
              MissingReason.NOT_WINDOWED,
              id,
              s"Affiliations are not windowed, so ${nameOf(id)}'s affiliation in a specific year or window is not in the data."
            )
          )
          false
        else
          ids.foreach(institutions)
          true
      case (SEMANTIC_EXPLANATION, _) => true

  def run(queryId: String): GraphStageResult =
    val byIndex = resolved.map(r => r.index -> r.researcherId).toMap
    val stageMs = collection.mutable.LinkedHashMap.empty[Stage, Long]
    def timed[A](stage: Stage)(body: => A): A =
      val t0 = log.now
      try body
      finally stageMs(stage) = stageMs.getOrElse(stage, 0L) + (log.now - t0)
    if resolved.nonEmpty then
      timed(Stage.COLLABORATION_METRICS)(resolved.foreach(r => metric(r.researcherId)))
    val unsupported = plan.operations
      .filterNot { op =>
        val ids = op.researchers.flatMap(byIndex.get)
        GraphStages
          .stageOf(op.operation)
          .fold(operation(op, ids))(st => timed(st)(operation(op, ids)))
      }
      .map(_.operation)
    GraphStageResult(
      GraphFacts(
        RuntimeSchemas.GraphFacts,
        queryId,
        graphRunId,
        resolved.flatMap(r => metricOf.get(r.researcherId).flatten).distinct,
        rankings.result(),
        changes.result(),
        coauthors.result(),
        paths.result(),
        communities.result(),
        facets.result(),
        works.values.toVector,
        missing.result(),
        log.all
      ),
      names.toMap,
      components.result().distinct,
      unsupported.distinct,
      stageMs.toMap,
      corpusComponents.result().distinct,
      corpusCommunities.result().distinct,
      corpusComparisons.result().distinct,
      summaries.fold("")(_.key),
      limitations.result().distinct
    )
