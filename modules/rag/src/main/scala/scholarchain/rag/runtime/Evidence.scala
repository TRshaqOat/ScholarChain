package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{
  EvidenceItem,
  EvidenceType,
  Provenance,
  SchemaVersions,
  SourceType,
  StableIds
}
import scholarchain.rag.SemanticContext

/** Stage 7 (EVIDENCE_BUILDING, RUNTIME.md section 10): deterministic graph evidence and semantic
  * context as separate, budgeted lists of core EvidenceItems with stable IDs. Facts are canonical
  * text: integers plain, decimals with trailing zeros stripped, booleans true/false, ID lists
  * space-joined, named lists as indexed keys.
  */
object EvidenceBuilder:
  def canon(d: Double): String =
    if d.isWhole && math.abs(d) < 1e15 then d.toLong.toString
    else BigDecimal.decimal(d).bigDecimal.stripTrailingZeros.toPlainString

  /** Prompt rendering of one graph item (also the basis of its token estimate). */
  def render(e: EvidenceItem): String =
    val facts = e.facts.toVector
      .sortBy(_._1)
      .map((k, v) => s"${SemanticContext.escape(k)}: ${SemanticContext.escape(v)}")
    (s"""<item id="${e.evidenceId}" type="${e.evidenceType}">""" +: facts :+ "</item>")
      .mkString("\n")

  private def item(
      t: EvidenceType,
      parts: Seq[String],
      runId: String,
      subjects: Vector[String],
      uri: String,
      facts: Map[String, String],
      prov: Provenance,
      graphPath: Option[Vector[String]] = None,
      workId: Option[String] = None
  ): EvidenceItem =
    EvidenceItem(
      SchemaVersions.EvidenceItem,
      StableIds.evidenceId(t, parts, runId),
      t,
      subjects,
      workId,
      uri,
      None,
      graphPath,
      None,
      None,
      None,
      facts,
      prov
    )

  private def indexed(prefix: String, xs: Vector[NamedCount]): Map[String, String] =
    xs.zipWithIndex.flatMap { (x, i) =>
      Seq(s"$prefix.${i + 1}.id" -> x.id) ++
        Option.when(x.name.nonEmpty)(s"$prefix.${i + 1}.name" -> x.name) ++
        x.count.map(c => s"$prefix.${i + 1}.count" -> c.toString)
    }.toMap

  /** Every graph item, in the bundle order, grouped so that a path is never cut inside its hops. */
  def graphGroups(
      plan: QueryPlan,
      resolutions: Vector[ResearcherResolution],
      g: GraphStageResult,
      cfg: ScholarChainConfig
  ): Vector[Vector[EvidenceItem]] =
    val f = g.facts
    val names = g.names
    def nm(id: String) = names.get(id).filter(_.nonEmpty)
    val windows = cfg.graph.windows.map(w => w.name -> w).toMap
    def win(w: String): Map[String, String] =
      Map("window" -> w) ++ windows
        .get(w)
        .toSeq
        .flatMap(x =>
          Seq("windowStartYear" -> x.startYear.toString, "windowEndYear" -> x.endYear.toString)
        )
    def rprov(id: String, snapshot: String, run: String, policy: Option[String]) =
      Provenance(SchemaVersions.Provenance, SourceType.DERIVED, id, snapshot, run, policy, None)

    val identity =
      resolutions.filter(_.decision == ResolutionDecision.RESOLVED_DOMINANT).flatMap { r =>
        r.candidates.find(c => r.researcherId.contains(c.researcherId)).map { c =>
          val key = r.nameKey.getOrElse("")
          EvidenceItem(
            SchemaVersions.EvidenceItem,
            r.evidenceId.getOrElse(
              StableIds.evidenceId(
                EvidenceType.IDENTITY_RESOLUTION,
                Seq(key, r.name, r.policyVersion),
                c.runId
              )
            ),
            EvidenceType.IDENTITY_RESOLUTION,
            Vector(c.researcherId),
            None,
            s"dynamodb:EntityLinkCache/$key/${c.researcherId}",
            None,
            None,
            None,
            None,
            None,
            Map(
              "name" -> r.name,
              "nameKey" -> key,
              "decision" -> r.decision.toString,
              "researcherId" -> c.researcherId,
              "displayName" -> c.displayName,
              "works" -> c.works.toString,
              "totalWorks" -> r.totalWorks.toString,
              "topShare" -> r.topShare.fold("")(canon),
              "candidateCount" -> r.candidateCount.toString,
              "dominantShare" -> canon(r.dominantShare),
              "minWorks" -> r.minWorks.toString,
              "identityConfidence" -> canon(c.identityConfidence),
              "policyVersion" -> r.policyVersion
            ),
            Provenance(
              SchemaVersions.Provenance,
              SourceType.RUNTIME,
              key,
              c.snapshot,
              c.runId,
              Some(r.policyVersion),
              None
            )
          )
        }
      }

    val metrics = f.metrics.map { m =>
      val x = m.metrics
      val id = x.researcherId
      item(
        EvidenceType.METRIC_RECORD,
        Seq(id, s"WINDOW#${m.window}"),
        x.provenance.runId,
        Vector(id),
        s"dynamodb:ResearcherMetricCache/$id/WINDOW#${m.window}",
        win(m.window) ++ Map(
          "researcherId" -> id,
          "publicationCount" -> x.publicationCount.toString,
          "distinctCoauthorCount" -> x.distinctCoauthorCount.toString,
          "totalCoauthorOccurrences" -> x.totalCoauthorOccurrences.toString,
          "averageCoauthorsPerPaper" -> canon(x.averageCoauthorsPerPaper),
          "maximumCoauthorsOnOnePaper" -> x.maximumCoauthorsOnOnePaper.toString,
          "maximumTeamSize" -> (x.maximumCoauthorsOnOnePaper + 1).toString,
          "maximumTeamWorkIds" -> x.maximumTeamWorkIds.mkString(" "),
          "weightedDegree" -> canon(x.weightedDegree),
          "componentId" -> x.componentId,
          "componentSize" -> x.componentSize.toString,
          "metricVersion" -> m.metricVersion
        ) ++ nm(id).map("displayName" -> _) ++ x.communityId.map("communityId" -> _) ++
          x.crossCommunityDegree.map("crossCommunityDegree" -> _.toString) ++
          x.communitiesConnected.map("communitiesConnected" -> _.toString) ++
          x.participationCoefficient.map("participationCoefficient" -> canon(_)) ++
          x.coreness.map("coreness" -> _.toString) ++ m.communitySize.map(
            "communitySize" -> _.toString
          ) ++
          m.graphDegree.map("graphDegree" -> _.toString) ++ m.communityRunId.map(
            "communityRunId" -> _
          ),
        x.provenance
      )
    }

    val rankings = f.rankings.map { r =>
      val attr = MetricAttributes.attribute(r.metric)
      val team = attr == "maximumCoauthorsOnOnePaper"
      val entries = r.entries.zipWithIndex.flatMap { (e, i) =>
        val k = i + 1
        Seq(
          s"rank.$k.researcherId" -> e.researcherId,
          s"rank.$k.storedRank" -> e.rank.toString,
          s"rank.$k.value" -> canon(e.value)
        ) ++
          e.displayName.map(s"rank.$k.displayName" -> _) ++
          Option.when(team)(s"rank.$k.maximumTeamSize" -> canon(e.value + 1))
      }
      item(
        EvidenceType.RANKING_RECORD,
        Seq(
          s"RANKING#$attr",
          s"TOP#${r.window}",
          s"n=${r.entries.size}",
          s"filter=${if r.filteredBy.isEmpty then "-" else r.filteredBy.sorted.mkString(" ")}"
        ),
        r.provenance.runId,
        r.entries.map(_.researcherId),
        s"dynamodb:ResearcherMetricCache/RANKING#$attr/TOP#${r.window}",
        win(r.window) ++ entries.toMap ++ Map(
          "metric" -> attr,
          "storedSize" -> r.storedSize.toString,
          "returnedSize" -> r.entries.size.toString,
          "filteredBy" -> r.filteredBy.mkString(" "),
          "lastStoredValue" -> canon(r.lastStoredValue),
          "tiedAtCut" -> r.entries.lastOption.exists(_.value == r.lastStoredValue).toString,
          "metricVersion" -> r.metricVersion
        ),
        r.provenance
      )
    }

    val changes = f.changes.map { c =>
      val key = s"${c.comparison.fromWindow}>${c.comparison.toWindow}"
      item(
        EvidenceType.TEMPORAL_CHANGE,
        Seq(c.researcherId, s"CHANGE#$key"),
        c.provenance.runId,
        Vector(c.researcherId),
        s"dynamodb:ResearcherMetricCache/${c.researcherId}/CHANGE#$key",
        c.values ++ Map(
          "window" -> key,
          "researcherId" -> c.researcherId,
          "presence" -> c.presence,
          "metricVersion" -> c.metricVersion
        ) ++
          nm(c.researcherId).map("displayName" -> _),
        c.provenance
      )
    }

    val components =
      g.componentSubjects.flatMap(id => f.metrics.find(_.metrics.researcherId == id)).map { m =>
        val x = m.metrics
        val largest = f.changes
          .filter(_.researcherId == x.researcherId)
          .flatMap { c =>
            Option
              .when(c.comparison.fromWindow == m.window)(c.values.get("fromInLargestComponent"))
              .flatten
              .orElse(
                Option
                  .when(c.comparison.toWindow == m.window)(c.values.get("toInLargestComponent"))
                  .flatten
              )
          }
          .headOption
        item(
          EvidenceType.COMPONENT_RECORD,
          Seq(x.researcherId, s"COMPONENT#${m.window}"),
          x.provenance.runId,
          Vector(x.researcherId),
          s"dynamodb:ResearcherMetricCache/${x.researcherId}/WINDOW#${m.window}",
          win(m.window) ++ Map(
            "researcherId" -> x.researcherId,
            "componentId" -> x.componentId,
            "componentSize" -> x.componentSize.toString
          ) ++
            largest.map("inLargestComponent" -> _) ++ nm(x.researcherId).map("displayName" -> _),
          x.provenance
        )
      }

    def edgeItem(l: CoauthorLink): EvidenceItem =
      val (a, b) = if l.researcherId < l.coauthorId then (l.researcherId, l.coauthorId)
      else (l.coauthorId, l.researcherId)
      item(
        EvidenceType.COAUTHOR_EDGE,
        Seq(l.edgeId),
        l.provenance.runId,
        Vector(a, b),
        s"neptune:${l.edgeId}",
        win(l.window) ++ Map(
          "edgeId" -> l.edgeId,
          "edgeExists" -> "true",
          "researcherA" -> a,
          "researcherB" -> b,
          "jointPublicationCount" -> l.jointPublicationCount.toString,
          "firstCollaborationYear" -> l.firstYear.toString,
          "lastCollaborationYear" -> l.lastYear.toString,
          "supportingWorkIds" -> l.supportingWorkIds.mkString(" ")
        ) ++ nm(a).map("displayNameA" -> _) ++ nm(b).map("displayNameB" -> _) ++
          l.supportingWorkIdsRef.map("supportingWorkIdsRef" -> _),
        l.provenance
      )

    val paths = f.paths.map { p =>
      val found = p.status == PathStatus.FOUND
      val parts =
        if found then Seq(p.window, s"depth=${p.maxDepth}") ++ p.researcherIds
        else Seq(p.window, s"depth=${p.maxDepth}", p.sourceId, p.targetId, p.status.toString)
      val hops = p.hops.flatMap { h =>
        val k = h.hop
        val l = h.link
        Seq(
          s"hop.$k.from" -> l.researcherId,
          s"hop.$k.to" -> l.coauthorId,
          s"hop.$k.edgeId" -> l.edgeId,
          s"hop.$k.jointPublicationCount" -> l.jointPublicationCount.toString,
          s"hop.$k.firstYear" -> l.firstYear.toString,
          s"hop.$k.lastYear" -> l.lastYear.toString
        ) ++ nm(l.researcherId).map(s"hop.$k.fromName" -> _) ++ nm(l.coauthorId).map(
          s"hop.$k.toName" -> _
        )
      }
      val pathItem = item(
        EvidenceType.GRAPH_PATH,
        parts,
        p.graphRunId,
        if found then p.researcherIds else Vector(p.sourceId, p.targetId),
        s"runtime:${p.templateId}",
        win(p.window) ++ hops.toMap ++ Map(
          "status" -> p.status.toString,
          "maxDepth" -> p.maxDepth.toString,
          "source" -> p.sourceId,
          "target" -> p.targetId,
          "researcherIds" -> p.researcherIds.mkString(" ")
        ) ++ p.pathLength.map("pathLength" -> _.toString) ++ nm(p.sourceId)
          .map("sourceName" -> _) ++
          nm(p.targetId).map("targetName" -> _),
        Provenance(
          SchemaVersions.Provenance,
          SourceType.DERIVED,
          p.templateId,
          plan.provenance.snapshot,
          p.graphRunId,
          Some(cfg.graph.policyVersion),
          None
        ),
        graphPath = Option.when(found)(p.researcherIds)
      )
      pathItem +: p.hops.map(h => edgeItem(h.link))
    }

    val edges = f.coauthors.flatMap { c =>
      if c.pairWith.isDefined && c.links.isEmpty then
        val y = c.pairWith.get
        val (a, b) = if c.researcherId < y then (c.researcherId, y) else (y, c.researcherId)
        Vector(
          item(
            EvidenceType.COAUTHOR_EDGE,
            Seq(a, b, c.window, "no-edge"),
            f.graphRunId,
            Vector(a, b),
            "runtime:graph.coauthor-edge",
            win(c.window) ++ Map(
              "edgeExists" -> "false",
              "researcherA" -> a,
              "researcherB" -> b,
              "jointPublicationCount" -> "0"
            ) ++
              nm(a).map("displayNameA" -> _) ++ nm(b).map("displayNameB" -> _),
            Provenance(
              SchemaVersions.Provenance,
              SourceType.DERIVED,
              "graph.coauthor-edge",
              plan.provenance.snapshot,
              f.graphRunId,
              Some(cfg.graph.policyVersion),
              None
            )
          )
        )
      else c.links.map(edgeItem)
    }

    val communities = f.communities.map { c =>
      item(
        EvidenceType.COMMUNITY_RECORD,
        Seq(c.communityId, s"members=${c.members.size}", s"neighbors=${c.neighbors.size}"),
        c.provenance.runId,
        Vector(c.communityId),
        s"neptune:${c.communityId}",
        win(c.window) ++ indexed("member", c.members) ++ indexed("neighbor", c.neighbors) ++ Map(
          "communityId" -> c.communityId,
          "communitySize" -> c.size.toString,
          "internalEdges" -> c.internalEdges.toString,
          "crossCommunityEdges" -> c.crossCommunityEdges.toString,
          "membersReturned" -> c.members.size.toString,
          "membersTruncated" -> c.membersTruncated.toString
        ) ++ c.label.map("communityLabel" -> _),
        c.provenance
      )
    }

    val facets = f.facets.map { x =>
      item(
        EvidenceType.FACET_RECORD,
        Seq(x.subjectId, x.facet.toString, x.window.getOrElse("-"), s"n=${x.entries.size}"),
        f.graphRunId,
        Vector(x.subjectId),
        s"runtime:graph.${x.facet.toString.toLowerCase}-facets",
        x.window.fold(Map.empty[String, String])(win) ++ indexed("entry", x.entries) ++ Map(
          "subjectId" -> x.subjectId,
          "facet" -> x.facet.toString,
          "basis" -> x.basis,
          "truncated" -> x.truncated.toString,
          "returnedSize" -> x.entries.size.toString
        ) ++ nm(x.subjectId).map("displayName" -> _),
        x.provenance
      )
    }

    val works = f.works.map { w =>
      item(
        EvidenceType.WORK_RECORD,
        Seq(w.workId),
        w.provenance.runId,
        Vector(w.workId),
        s"neptune:${w.workId}",
        Map("workId" -> w.workId) ++ w.title.map("title" -> _) ++ w.year.map("year" -> _.toString),
        w.provenance,
        workId = Some(w.workId)
      )
    }

    val seen = collection.mutable.HashSet.empty[String]
    def fresh(g: Vector[EvidenceItem]) = g.filter(e => seen.add(e.evidenceId))
    (identity.map(Vector(_)) ++ metrics.map(Vector(_)) ++ rankings.map(Vector(_)) ++ changes.map(
      Vector(_)
    ) ++
      components.map(Vector(_)) ++ paths ++ edges.map(Vector(_)) ++ communities.map(Vector(_)) ++
      facets.map(Vector(_)) ++ works.map(Vector(_))).map(fresh).filter(_.nonEmpty)

  /** The bounded bundle: graph groups fill the graph share in order, whole groups only; semantic
    * documents fill the semantic share best score first, whole documents only; the item count is
    * capped. Everything left out is counted and listed as OMITTED_BY_BUDGET.
    */
  def build(
      plan: QueryPlan,
      resolutions: Vector[ResearcherResolution],
      g: GraphStageResult,
      semantic: Vector[SemanticResult],
      extraMissing: Vector[MissingEvidence],
      cfg: ScholarChainConfig,
      runId: String
  ): EvidenceBundle =
    val b = cfg.runtime.budget
    def tokens(s: String) = (s.length + b.charsPerToken - 1) / b.charsPerToken
    var count = 0
    var graphTokens = 0
    var omittedGraph = Vector.empty[EvidenceItem]
    val graph = graphGroups(plan, resolutions, g, cfg).flatMap { group =>
      val t = group.map(e => tokens(render(e))).sum
      if graphTokens + t <= b.graphEvidenceTokens && count + group.size <= b.maxEvidenceItems then
        graphTokens += t
        count += group.size
        group
      else
        omittedGraph ++= group
        Vector.empty
    }
    var semanticTokens = 0
    var omittedSemantic = 0
    val ordered = semantic
      .flatMap(_.items)
      .sortBy(e => (-e.score.getOrElse(0d), e.evidenceId))
      .distinctBy(e => e.chunkId.getOrElse(e.evidenceId))
    val sem = ordered.filter { e =>
      val t = tokens(SemanticContext.render(Seq(e), Int.MaxValue)._1)
      val ok = semanticTokens + t <= b.semanticContextTokens && count + 1 <= b.maxEvidenceItems
      if ok then
        semanticTokens += t
        count += 1
      else omittedSemantic += 1
      ok
    }
    val omitted = omittedGraph
      .groupBy(_.evidenceType)
      .toVector
      .sortBy(_._1.ordinal)
      .map((t, es) =>
        MissingEvidence(
          MissingReason.OMITTED_BY_BUDGET,
          t.toString,
          s"${es.size} $t item(s) were left out by the evidence token budget."
        )
      ) ++ Option.when(omittedSemantic > 0)(
      MissingEvidence(
        MissingReason.OMITTED_BY_BUDGET,
        "semantic",
        s"$omittedSemantic semantic document(s) were left out by the context budget."
      )
    )
    EvidenceBundle(
      RuntimeSchemas.EvidenceBundle,
      plan.queryId,
      Vector(plan.window),
      graph,
      sem,
      g.facts.missing ++ extraMissing ++ omitted,
      BundleBudget(
        b.graphEvidenceTokens + b.semanticContextTokens,
        graphTokens,
        semanticTokens,
        omittedGraph.size,
        omittedSemantic
      ),
      g.facts.graphRunId,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.RUNTIME,
        plan.queryId,
        plan.provenance.snapshot,
        runId,
        Some(cfg.retrieval.fusionPolicyVersion),
        None
      )
    )
