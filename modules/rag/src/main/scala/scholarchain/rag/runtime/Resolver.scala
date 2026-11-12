package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{EvidenceType, StableIds}
import scholarchain.storage.{
  AttributeValue,
  DynamoContract,
  GraphReadPort,
  Item,
  KeyValueReadPort,
  TableName
}

/** The question deadline passed before `what` (review 1 m7); the workflow abstains with
  * BUDGET_EXHAUSTED and skips the remaining stages.
  */
final class DeadlineExceeded(val what: String)
    extends RuntimeException(s"deadline passed before $what")

/** Records the template or key ID, latency, result count, and truncation of every store read (never
  * its parameter values, which may hold user text). Every read first checks the question deadline,
  * so graph and key-value reads stop once it has passed.
  */
final class CallLog(nowMs: () => Long, deadlineMs: Long = Long.MaxValue):
  private var calls = Vector.empty[TemplateCall]
  def all: Vector[TemplateCall] = calls
  def now: Long = nowMs()
  def checkDeadline(what: String): Unit =
    if nowMs() > deadlineMs then throw DeadlineExceeded(what)
  def apply[A](id: String)(op: => A)(size: A => Int, truncated: A => Boolean = (_: A) => false): A =
    checkDeadline(id)
    val t0 = nowMs()
    val a = op
    calls :+= TemplateCall(id, nowMs() - t0, size(a), truncated(a))
    a

/** The resolution port (RUNTIME.md section 5): EntityLinkCache items under a name-v1 key with each
  * researcher's works count. Here: EntityLinkCache query plus graph.authored-counts (the Phase 7 M9
  * interim source until follow-up F1 adds authoredWorkCount to the items).
  */
object ResolutionPort:
  def s(i: Item, k: String): Option[String] = i.get(k).collect { case AttributeValue.S(v) => v }
  def n(i: Item, k: String): Option[BigDecimal] =
    i.get(k).collect { case AttributeValue.N(v) => BigDecimal(v) }
  def b(i: Item, k: String): Option[Boolean] = i.get(k).collect { case AttributeValue.Bool(v) => v }

/** Stage 2 (RESEARCHER_RESOLUTION): dominant-name-v1 (modules/spark/JOBS.md, Phase 7 ruling 30). */
object Resolver:
  import ResolutionPort.*

  /** Spark `trim`: leading and trailing U+0020 only. */
  def sparkTrim(s: String): String = s.dropWhile(_ == ' ').reverse.dropWhile(_ == ' ').reverse

  def resolve(
      name: String,
      kv: KeyValueReadPort,
      graph: GraphReadPort,
      cfg: ScholarChainConfig,
      log: CallLog
  ): ResearcherResolution =
    val r = cfg.runtime.resolver
    val key = DynamoContract.nameKey(name)
    val items = key.fold(Vector.empty[Item])(k =>
      log("kv.entity-link")(kv.query(TableName.EntityLinkCache, k, "", r.maxCandidates))(_.size)
    )
    val truncated = items.size >= r.maxCandidates
    val ids = items.flatMap(s(_, "researcherId")).distinct
    val batches = ids.grouped(graph.limits.idBatchSize).toVector
    val works =
      batches.flatMap(b => log("graph.authored-counts")(graph.authoredCounts(b))(_.size)).toMap
    val wanted = sparkTrim(name)
    val (exact, others) = items.partition(i => s(i, "displayName").contains(wanted))
    val total = exact.flatMap(s(_, "researcherId")).map(works.getOrElse(_, 0L)).sum
    def context(is: Vector[Item]): Vector[NameCandidate] =
      val cids = is.flatMap(s(_, "researcherId"))
      val inst = cids
        .grouped(graph.limits.idBatchSize)
        .flatMap(b =>
          log("graph.researcher-institutions")(
            graph.researcherInstitutions(b, r.candidateInstitutions)
          )(_.size)
        )
        .map(l => l.researcherId -> l.entries)
        .toMap
      is.map { i =>
        val id = s(i, "researcherId").getOrElse("")
        val w = works.getOrElse(id, 0L)
        val co = log("graph.coauthors")(
          graph.coauthors(id, Windows.corpusWindowName(cfg), r.candidateCoauthors)
        )(_.items.size, _.truncated)
        NameCandidate(
          id,
          s(i, "displayName").getOrElse(""),
          s(i, "matchedName").getOrElse(""),
          w,
          Option.when(total > 0 && exact.contains(i))(w.toDouble / total),
          n(i, "identityConfidence").fold(0d)(_.toDouble),
          n(i, "mentionCount").fold(0L)(_.toLong),
          b(i, "dblpAnchored").getOrElse(false),
          s(i, "orcid"),
          inst
            .getOrElse(id, Vector.empty)
            .map(l =>
              NamedCount(
                l.institution.id,
                l.institution.props.get("name").fold("")(_.toString),
                None
              )
            ),
          co.items.map(l =>
            NamedCount(
              l.coauthorId,
              l.coauthorName.getOrElse(""),
              l.edge.props.get("jointPublicationCount").collect { case x: Long => x }
            )
          ),
          s(i, "runId").getOrElse(""),
          s(i, "snapshot").getOrElse("")
        )
      }
    val ranked = context(exact).sortBy(c => (-c.works, c.researcherId))
    val top = ranked.headOption
    val share = top.filter(_ => total > 0).map(_.works.toDouble / total)
    val dominant =
      !truncated && top.exists(t =>
        t.works >= r.dominantMinWorks && share.exists(_ >= r.dominantShare)
      )
    // A cut candidate list never proves absence (review 1 m2): no exact match in it is AMBIGUOUS.
    val decision =
      if ranked.isEmpty && !truncated then ResolutionDecision.UNKNOWN_RESEARCHER
      else if dominant then ResolutionDecision.RESOLVED_DOMINANT
      else ResolutionDecision.AMBIGUOUS
    val chosen = top.filter(_ => dominant)
    ResearcherResolution(
      RuntimeSchemas.Resolution,
      name,
      key,
      r.policyVersion,
      r.dominantShare,
      r.dominantMinWorks.toLong,
      decision,
      chosen.map(_.researcherId),
      share,
      total,
      ranked.size,
      ranked,
      context(others).sortBy(c => (c.displayName, c.researcherId)),
      truncated,
      chosen.map(c =>
        StableIds.evidenceId(
          EvidenceType.IDENTITY_RESOLUTION,
          Seq(key.getOrElse(""), name, r.policyVersion),
          c.runId
        )
      )
    )

  /** The uncertainty line every answer that uses a dominant link carries (section 5, step 7). */
  def linkLine(r: ResearcherResolution): Option[String] =
    for
      id <- r.researcherId
      share <- r.topShare
    yield f"${r.name} was linked to $id by ${r.policyVersion}: ${share}%.4f of ${r.totalWorks} works " +
      s"among ${r.candidateCount} researchers with this display name."

  /** Fixed-template abstention text for unresolved names (never model text). */
  def abstentionText(rs: Vector[ResearcherResolution]): String =
    rs.filter(_.decision != ResolutionDecision.RESOLVED_DOMINANT)
      .map { r =>
        r.decision match
          case ResolutionDecision.UNKNOWN_RESEARCHER =>
            val alt = Option.when(r.otherNameMatches.nonEmpty)(
              s" Researchers with a similar name: ${r.otherNameMatches.map(c => s"${c.displayName} (${c.researcherId})").mkString(", ")}."
            )
            s"No researcher named '${r.name}' is in the graph.${alt.getOrElse("")}"
          case _ if r.candidates.isEmpty =>
            s"'${r.name}' shares its name key with at least ${r.otherNameMatches.size} researchers, and the " +
              "candidate list was cut at its limit before an exact display-name match was found, so " +
              "it cannot be resolved. Which researcher is meant?"
          case _ =>
            val shown = r.candidates
              .map(c => s"${c.displayName} (${c.researcherId}, ${c.works} works)")
              .mkString(", ")
            val more = Option.when(r.otherNameMatches.nonEmpty)(
              s" ${r.otherNameMatches.size} researchers have a similar but different name."
            )
            f"'${r.name}' matches ${r.candidateCount} researchers and none holds ${r.dominantShare}%.2f of " +
              s"their ${r.totalWorks} works with at least ${r.minWorks}${
                  if r.truncated then " (the candidate list was cut at its limit)" else ""
                }: " +
              s"$shown.${more.getOrElse("")} Which researcher is meant?"
      }
      .mkString(" ")
