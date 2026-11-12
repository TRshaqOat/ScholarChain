package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{CollaborationMetric, EvidenceItem, EvidenceType, IdFormat, QueryOperation}
import scholarchain.rag.SemanticContext
import upickle.default.read

/** Stage 8 (ANSWER_SYNTHESIS, RUNTIME.md section 11). */
object Synthesis:
  val ClaimId = "claim-[1-9][0-9]*".r

  /** The prompt values. Every block is escaped data: the question, the graph evidence, the semantic
    * context, and the fixed-template notes (window, identity, missing evidence).
    */
  def promptValues(
      question: String,
      bundle: EvidenceBundle,
      notes: Vector[String],
      prompt: PromptTemplate,
      cfg: ScholarChainConfig
  ): Map[String, String] =
    Map(
      "question" -> SemanticContext.escape(question),
      "graph_evidence" -> bundle.graphEvidence.map(EvidenceBuilder.render).mkString("\n"),
      "semantic_context" -> SemanticContext
        .render(bundle.semanticEvidence, cfg.runtime.budget.semanticContextTokens)
        ._1,
      "notes" -> notes.map(n => s"- ${SemanticContext.escape(n)}").mkString("\n"),
      "schema" -> ujson.write(prompt.schema, indent = 2),
      "max_claims" -> cfg.runtime.synthesis.maxClaims.toString
    )

  /** Rule failures of a synthesis output beyond its schema; empty means valid. */
  def problems(o: SynthesisOutput, cfg: ScholarChainConfig): Vector[String] =
    Vector(
      Option.when(!RuntimeSchemas.SynthesisOutputVersions.contains(o.schemaVersion))(
        "schemaVersion"
      ),
      Option.when(o.claims.size > cfg.runtime.synthesis.maxClaims)(
        s"claims: more than ${cfg.runtime.synthesis.maxClaims}"
      ),
      Option.when(o.claims.exists(c => !ClaimId.matches(c.claimId)))(
        "claims: claimId is not claim-<n>"
      ),
      Option
        .when(o.claims.map(_.claimId).distinct.size != o.claims.size)("claims: duplicate claimId"),
      Option.when(o.claims.exists(_.evidenceIds.exists(id => !IdFormat.isEvidenceId(id))))(
        "claims: an evidence ID is not ev-<32 hex>"
      ),
      // Ruling 17, also for synthesis-output-v1, whose schema does not say it.
      Option.when(o.claims.exists(c => c.kind != ClaimKind.DESCRIPTIVE && c.values.isEmpty))(
        "claims: a claim other than DESCRIPTIVE has no values"
      )
    ).flatten

  def parseModel(
      text: String,
      schema: ujson.Value,
      cfg: ScholarChainConfig
  ): Either[Vector[String], SynthesisOutput] =
    JsonSchema.parseAndValidate(schema, text).flatMap { v =>
      scala.util
        .Try(read[SynthesisOutput](v))
        .toEither
        .left
        .map(_ => Vector("schema: the output does not decode as a synthesis output"))
        .flatMap(o =>
          problems(o, cfg) match
            case Vector() => Right(o)
            case es       => Left(es)
        )
    }

/** Fake-mode synthesizer: deterministic claims drafted from the bundle facts, so verification and
  * the benchmark mechanics run without a model. Its summary says it is a template answer.
  */
object TemplateSynthesizer:
  val Header = "Template answer (fake mode; no language model was called)."

  private val labels: Map[String, String] = Map(
    "publicationCount" -> "publications",
    "distinctCoauthorCount" -> "distinct coauthors (collaboration breadth)",
    "totalCoauthorOccurrences" -> "total coauthor occurrences",
    "averageCoauthorsPerPaper" -> "average coauthors per paper",
    "maximumCoauthorsOnOnePaper" -> "coauthors on the largest team paper",
    "weightedDegree" -> "weighted degree (collaboration intensity: joint publications summed over coauthors)",
    "componentSize" -> "connected-component size",
    "crossCommunityDegree" -> "cross-community degree (bridge measure)",
    "communitiesConnected" -> "communities connected (bridge measure)",
    "participationCoefficient" -> "participation coefficient (bridge measure)",
    "coreness" -> "coreness (k-core)"
  )

  def synthesize(
      plan: QueryPlan,
      bundle: EvidenceBundle,
      resolved: Vector[Resolved]
  ): SynthesisOutput =
    val claims = Vector.newBuilder[ClaimDraft]
    var n = 0
    def claim(
        kind: ClaimKind,
        text: String,
        cite: Seq[EvidenceItem],
        window: Option[String],
        values: Map[String, String]
    ): Unit =
      n += 1
      claims += ClaimDraft(
        s"claim-$n",
        kind,
        text,
        cite.map(_.evidenceId).distinct.toVector,
        cite
          .filterNot(_.evidenceType.isSemantic)
          .flatMap(_.subjectIds)
          .filter(id => IdFormat.isResearcherId(id))
          .distinct
          .toVector, // semantic documents only describe
        window,
        values
      )
    val g = bundle.graphEvidence
    def of(t: EvidenceType) = g.filter(_.evidenceType == t)
    def f(e: EvidenceItem, k: String) = e.facts.getOrElse(k, "")
    def pick(e: EvidenceItem, ks: String*) = ks.flatMap(k => e.facts.get(k).map(k -> _)).toMap
    def name(e: EvidenceItem, id: String, key: String = "displayName") =
      e.facts.get(key).orElse(resolved.find(_.researcherId == id).map(_.name)).getOrElse(id)
    def works(ids: Seq[String]) =
      of(EvidenceType.WORK_RECORD).filter(w => ids.contains(f(w, "workId")))
    def titles(ws: Seq[EvidenceItem]) =
      ws.flatMap(w => w.facts.get("title").map(t => s"\"$t\" (${f(w, "year")})")).mkString("; ")
    // A publication claim asserts the cited works: the first work ID and the year fact, which binds
    // every written year of the cited works (ruling 17).
    def workValues(ws: Seq[EvidenceItem]) =
      ws.headOption.toSeq.flatMap(w => pick(w, "workId", "year")).toMap
    def has(op: QueryOperation, scope: OperationScope*) =
      plan.operations.exists(o => o.operation == op && (scope.isEmpty || scope.contains(o.scope)))

    of(EvidenceType.IDENTITY_RESOLUTION).foreach { e =>
      claim(
        ClaimKind.IDENTITY,
        s"'${f(e, "name")}' was linked to ${f(e, "displayName")} (${f(e, "researcherId")}) by ${f(e, "policyVersion")}: " +
          s"${f(e, "works")} of ${f(e, "totalWorks")} works among researchers with this display name.",
        Seq(e),
        None,
        pick(e, "researcherId", "works", "totalWorks")
      )
    }

    val wanted =
      (plan.metrics.map(MetricAttributes.attribute) ++
        Option.when(has(QueryOperation.MAXIMUM_TEAM_SIZE, OperationScope.RESEARCHER))(
          "maximumCoauthorsOnOnePaper"
        ) ++
        Option
          .when(has(QueryOperation.BRIDGE_RANKING, OperationScope.RESEARCHER))(
            Vector("crossCommunityDegree", "communitiesConnected", "participationCoefficient")
          )
          .toVector
          .flatten).distinct
    of(EvidenceType.METRIC_RECORD).foreach { e =>
      val who = name(e, f(e, "researcherId"))
      val w = f(e, "window")
      val span = s"$w (${f(e, "windowStartYear")}-${f(e, "windowEndYear")})"
      val bridge =
        Vector("crossCommunityDegree", "communitiesConnected", "participationCoefficient").filter(
          k => wanted.contains(k) && e.facts.contains(k)
        )
      wanted.filterNot(bridge.contains).filter(e.facts.contains).foreach { k =>
        if k == "maximumCoauthorsOnOnePaper" then
          val ws = works(f(e, "maximumTeamWorkIds").split(' ').toSeq)
          claim(
            ClaimKind.METRIC_VALUE,
            s"In $span, ${who}'s largest team paper had ${f(e, k)} coauthors, so ${f(e, "maximumTeamSize")} authors (team size).",
            Seq(e),
            Some(w),
            pick(e, k, "maximumTeamSize")
          )
          if ws.nonEmpty then
            claim(
              ClaimKind.PUBLICATION,
              s"That team size is reached on: ${titles(ws)}.",
              e +: ws,
              Some(w),
              workValues(ws)
            )
        else
          claim(
            ClaimKind.METRIC_VALUE,
            s"In $span, $who has ${f(e, k)} ${labels.getOrElse(k, k)}.",
            Seq(e),
            Some(w),
            pick(e, k)
          )
      }
      if wanted.contains("distinctCoauthorCount") then
        claim(
          ClaimKind.METRIC_VALUE,
          s"Breadth is not intensity: in $w, ${who}'s weighted degree (joint publications summed over coauthors) is " +
            s"${f(e, "weightedDegree")} over ${f(e, "publicationCount")} publications.",
          Seq(e),
          Some(w),
          pick(e, "weightedDegree", "publicationCount")
        )
      if bridge.nonEmpty then
        claim(
          ClaimKind.BRIDGE,
          s"In $span, ${bridge.map(k => s"${who}'s ${labels(k)} is ${f(e, k)}").mkString("; ")}.",
          Seq(e),
          Some(w),
          pick(e, bridge*)
        )
        claim(
          ClaimKind.BRIDGE,
          s"Bridge importance is not breadth: $who has ${f(e, "distinctCoauthorCount")} distinct coauthors in $w " +
            s"but connects ${f(e, "communitiesConnected")} communities.",
          Seq(e),
          Some(w),
          pick(e, "distinctCoauthorCount", "communitiesConnected")
        )
    }

    of(EvidenceType.RANKING_RECORD).foreach { e =>
      val k = f(e, "returnedSize").toIntOption.getOrElse(0)
      val team = f(e, "metric") == "maximumCoauthorsOnOnePaper"
      val list = (1 to k)
        .map { i =>
          val v = if team then
            s"${f(e, s"rank.$i.value")} coauthors, ${f(e, s"rank.$i.maximumTeamSize")} authors"
          else f(e, s"rank.$i.value")
          s"${e.facts.getOrElse(s"rank.$i.displayName", f(e, s"rank.$i.researcherId"))} ($v)"
        }
        .mkString("; ")
      val filtered = Option
        .when(f(e, "filteredBy").nonEmpty)(s", filtered from the stored top ${f(e, "storedSize")}")
        .getOrElse("")
      val ties = Option
        .when(f(e, "tiedAtCut") == "true")(" Equal values may continue past the stored list.")
        .getOrElse("")
      claim(
        ClaimKind.RANKING,
        s"Top ${f(e, "returnedSize")} by ${labels.getOrElse(f(e, "metric"), f(e, "metric"))} in ${f(e, "window")}$filtered: $list.$ties",
        Seq(e),
        Some(f(e, "window")),
        pick(e, "metric", "returnedSize") ++ (1 to k).flatMap(i =>
          pick(e, s"rank.$i.researcherId", s"rank.$i.value", s"rank.$i.maximumTeamSize")
        )
      )
    }

    of(EvidenceType.TEMPORAL_CHANGE).foreach { e =>
      val who = name(e, f(e, "researcherId"))
      val (a, b) = (f(e, "fromWindow"), f(e, "toWindow"))
      val attrs = (if wanted.isEmpty then Vector("distinctCoauthorCount") else wanted).map(k =>
        k.head.toUpper +: k.tail
      )
      val moves =
        attrs.filter(k => e.facts.contains(s"from$k") || e.facts.contains(s"to$k")).map { k =>
          val label = labels.getOrElse(k.head.toLower +: k.tail, k)
          val d = e.facts.get(s"delta$k").fold("")(x => s" (change $x)")
          (
            // "no stored value", not "none": "none" is the number 0 to the verifier (ruling 29).
            s"${who}'s $label went from ${e.facts.getOrElse(s"from$k", "no stored value")} in $a to ${e.facts
                .getOrElse(s"to$k", "no stored value")} in $b$d",
            pick(e, s"from$k", s"to$k", s"delta$k")
          )
        }
      val comm =
        if !e.facts.contains("fromCommunityId") || !e.facts.contains("toCommunityId") then None
        else
          val same = f(e, "fromCommunityId") == f(e, "toCommunityId")
          Some(
            (
              s"${who}'s collaboration community ${if same then "stayed" else "changed"} from ${f(e, "fromCommunityId")} " +
                s"(size ${f(e, "fromCommunitySize")}) in $a to ${f(e, "toCommunityId")} (size ${f(e, "toCommunitySize")}) in $b" +
                e.facts.get("communityRetention").fold("")(r => s", community retention $r"),
              pick(
                e,
                "fromCommunityId",
                "toCommunityId",
                "fromCommunitySize",
                "toCommunitySize",
                "communityRetention"
              )
            )
          )
      (moves ++ comm).foreach((t, v) =>
        claim(ClaimKind.TEMPORAL_CHANGE, s"$t.", Seq(e), Some(f(e, "window")), v)
      )
    }

    of(EvidenceType.COMPONENT_RECORD).foreach { e =>
      val largest = e.facts
        .get("inLargestComponent")
        .fold("")(x =>
          if x == "true" then ", which is the largest connected component of that window"
          else ", which is not the largest connected component of that window"
        )
      claim(
        ClaimKind.COMPONENT,
        s"In ${f(e, "window")}, ${name(e, f(e, "researcherId"))} is in connected component ${f(e, "componentId")} of ${f(e, "componentSize")} researchers$largest.",
        Seq(e),
        Some(f(e, "window")),
        pick(e, "componentId", "componentSize", "inLargestComponent")
      )
    }

    val edgesById =
      of(EvidenceType.COAUTHOR_EDGE).flatMap(e => e.facts.get("edgeId").map(_ -> e)).toMap
    of(EvidenceType.GRAPH_PATH).foreach { e =>
      val w = f(e, "window")
      val (a, b) = (name(e, f(e, "source"), "sourceName"), name(e, f(e, "target"), "targetName"))
      f(e, "status") match
        case "FOUND" =>
          val l = f(e, "pathLength").toIntOption.getOrElse(0)
          val hopNames = (1 to l).map(k => e.facts.getOrElse(s"hop.$k.toName", f(e, s"hop.$k.to")))
          claim(
            ClaimKind.PATH,
            s"The shortest coauthorship path in $w between $a and $b has ${f(e, "pathLength")} hops: ${(a +: hopNames).mkString(" - ")}.",
            Seq(e),
            Some(w),
            pick(e, "pathLength", "researcherIds")
          )
          (1 to l).foreach { k =>
            val edge = edgesById.get(f(e, s"hop.$k.edgeId")).toSeq
            val from = e.facts.getOrElse(s"hop.$k.fromName", f(e, s"hop.$k.from"))
            val to = e.facts.getOrElse(s"hop.$k.toName", f(e, s"hop.$k.to"))
            claim(
              ClaimKind.PATH_HOP,
              s"$from and $to have ${f(e, s"hop.$k.jointPublicationCount")} joint publications in $w, first in ${f(e, s"hop.$k.firstYear")} and last in ${f(e, s"hop.$k.lastYear")}.",
              e +: edge,
              Some(w),
              pick(
                e,
                s"hop.$k.from",
                s"hop.$k.to",
                s"hop.$k.jointPublicationCount",
                s"hop.$k.firstYear",
                s"hop.$k.lastYear"
              )
            )
            val ws = works(edge.flatMap(x => f(x, "supportingWorkIds").split(' ')))
            if ws.nonEmpty then
              claim(
                ClaimKind.PUBLICATION,
                s"The collaboration of $from and $to is supported by: ${titles(ws)}.",
                edge ++ ws,
                Some(w),
                // The edge ID asserts the edge, so its two endpoints may be named (ruling 29).
                workValues(ws) ++ edge.flatMap(pick(_, "edgeId"))
              )
          }
        case status =>
          val text = status match
            case "UNREACHABLE" =>
              s"$a and $b are in different connected components in $w, so no coauthorship path connects them."
            case "BEYOND_DEPTH" =>
              s"No coauthorship path of at most ${f(e, "maxDepth")} hops connects $a and $b in $w."
            case "SEARCH_LIMIT" =>
              s"No coauthorship path between $a and $b in $w was established within the search limits."
            // Not "has no publication": that states a count of 0 that no fact here holds.
            case _ => s"$a or $b is not in the $w graph, so no path can be stated."
          claim(ClaimKind.PATH, text, Seq(e), Some(w), pick(e, "status", "maxDepth"))
    }

    val pairs = plan.operations
      .filter(o => o.operation == QueryOperation.DIRECT_COAUTHORS && o.scope == OperationScope.PAIR)
      .flatMap(o =>
        o.researchers.flatMap(i => resolved.find(_.index == i).map(_.researcherId)) match
          case Vector(x, y) => Some(Set(x, y))
          case _            => None
      )
    of(EvidenceType.COAUTHOR_EDGE).filter(e => pairs.contains(e.subjectIds.toSet)).foreach { e =>
      val (a, b) =
        (name(e, f(e, "researcherA"), "displayNameA"), name(e, f(e, "researcherB"), "displayNameB"))
      val w = f(e, "window")
      if f(e, "edgeExists") == "true" then
        claim(
          ClaimKind.COAUTHORSHIP,
          s"$a and $b have ${f(e, "jointPublicationCount")} joint publications in $w, first in ${f(e, "firstCollaborationYear")} and last in ${f(e, "lastCollaborationYear")}.",
          Seq(e),
          Some(w),
          pick(e, "jointPublicationCount", "firstCollaborationYear", "lastCollaborationYear")
        )
        val ws = works(f(e, "supportingWorkIds").split(' ').toSeq)
        if ws.nonEmpty then
          claim(
            ClaimKind.PUBLICATION,
            s"Their joint publications include: ${titles(ws)}.",
            e +: ws,
            Some(w),
            workValues(ws)
          )
      else
        claim(
          ClaimKind.COAUTHORSHIP,
          s"$a and $b have no joint publication in $w.",
          Seq(e),
          Some(w),
          pick(e, "edgeExists")
        )
    }
    if has(QueryOperation.DIRECT_COAUTHORS, OperationScope.RESEARCHER) then
      resolved.foreach { r =>
        of(EvidenceType.COAUTHOR_EDGE)
          .filter(e => e.subjectIds.contains(r.researcherId) && !pairs.contains(e.subjectIds.toSet))
          .take(5)
          .foreach { e =>
            val other = if f(e, "researcherA") == r.researcherId then
              name(e, f(e, "researcherB"), "displayNameB")
            else name(e, f(e, "researcherA"), "displayNameA")
            claim(
              ClaimKind.COAUTHORSHIP,
              s"${r.name} and $other have ${f(e, "jointPublicationCount")} joint publications in ${f(e, "window")}.",
              Seq(e),
              Some(f(e, "window")),
              pick(e, "jointPublicationCount")
            )
          }
      }

    of(EvidenceType.COMMUNITY_RECORD).foreach { e =>
      val members = (1 to f(e, "membersReturned").toIntOption
        .getOrElse(0)).take(10).flatMap(k => e.facts.get(s"member.$k.name")).filter(_.nonEmpty)
      val metric = of(EvidenceType.METRIC_RECORD).filter(
        _.facts.get("communityId").contains(f(e, "communityId"))
      )
      val who = metric.map(m => name(m, f(m, "researcherId"))).mkString(" and ")
      claim(
        ClaimKind.COMMUNITY,
        s"In ${f(e, "window")}, ${
            if who.isEmpty then "the" else s"${who}'s"
          } collaboration community ${f(e, "communityId")} has ${f(e, "communitySize")} members" +
          (if members.isEmpty then "."
           else s"; members with the most links inside it include ${members.mkString(", ")}."),
        e +: metric,
        Some(f(e, "window")),
        pick(e, "communityId", "communitySize")
      )
    }

    of(EvidenceType.FACET_RECORD).foreach { e =>
      val k = f(e, "returnedSize").toIntOption.getOrElse(0)
      val list = (1 to k)
        .map(i =>
          s"${e.facts.getOrElse(s"entry.$i.name", f(e, s"entry.$i.id"))}${e.facts.get(s"entry.$i.count").fold("")(c => s" ($c)")}"
        )
        .mkString("; ")
      val subject = f(e, "subjectId")
      val who =
        e.facts.get("displayName").orElse(resolved.find(_.researcherId == subject).map(_.name))
      val text = (f(e, "facet"), who) match
        case (_, None) if subject.startsWith("topics:") =>
          s"Researchers with the most works on the matched topics in ${f(e, "window")}: $list."
        case (_, None) if subject.startsWith("comm-") =>
          s"The most common topics of the community's works: $list."
        case ("INSTITUTION", Some(n)) =>
          s"$n is affiliated with: $list (affiliations are not windowed)."
        case (facet, n) =>
          s"The most common ${facet.toLowerCase}s of ${n.getOrElse(subject)}'s works in ${f(e, "window")}: $list."
      if k > 0 then
        claim(
          ClaimKind.FACET,
          text,
          Seq(e),
          e.facts.get("window"),
          ((1 to math.min(k, 5)).flatMap(i => pick(e, s"entry.$i.id")) ++
            (1 to k).flatMap(i => pick(e, s"entry.$i.count"))).toMap
        )
    }

    bundle.semanticEvidence.groupBy(_.facts.getOrElse("index", "")).toVector.sortBy(_._1).foreach {
      (index, items) =>
        claim(
          ClaimKind.DESCRIPTIVE,
          // Ruling 19: semantic context is shown as context only, never as a verified fact.
          s"Descriptive context was retrieved from $index by vector similarity (context only).",
          items,
          None,
          Map.empty
        )
    }
    val drafted = claims.result()
    SynthesisOutput(
      RuntimeSchemas.SynthesisOutput,
      (Header +: drafted.filter(_.kind != ClaimKind.DESCRIPTIVE).map(_.text)).mkString(" "),
      drafted,
      Vector.empty
    )
