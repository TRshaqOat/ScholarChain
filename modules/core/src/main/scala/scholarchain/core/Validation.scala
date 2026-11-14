package scholarchain.core

import upickle.default.{ReadWriter, read, write}

import scala.util.Try

/** Returns the rule violations of a value; an empty list means valid. */
trait Validator[A]:
  def errors(a: A): List[String]

/** Contract validation. Rejects missing provenance, confidence outside [0, 1], unsupported schema
  * versions, malformed stable IDs, non-canonical undirected pairs, and windows whose start year
  * exceeds the end year.
  */
object Validation:
  def validate[A](a: A)(using v: Validator[A]): Either[List[String], A] =
    // A null field (JSON null decodes to null for String) must reject, never throw.
    Try(v.errors(a)).fold(e => List(s"invalid record: $e"), identity) match
      case Nil => Right(a)
      case es  => Left(es)

  // ---- Rule helpers (each returns its violations) ---------------------------------------------
  def check(ok: Boolean, msg: => String): List[String] = if ok then Nil else List(msg)

  def schemaVersion(actual: String, expected: String): List[String] =
    check(actual == expected, s"unsupported schema version '$actual' (supported: '$expected')")

  def confidence(field: String, c: Double): List[String] =
    check(c >= 0.0 && c <= 1.0, s"$field must be within [0, 1], got $c")

  def window(field: String, start: Int, end: Int): List[String] =
    check(start <= end, s"$field start year $start exceeds end year $end")

  def canonicalPair(field: String, a: String, b: String): List[String] =
    check(
      CanonicalPair.isCanonical(a, b),
      s"$field pair ($a, $b) is not in canonical order (A < B)"
    )

  def id(field: String, value: String, ok: String => Boolean): List[String] =
    check(value != null && ok(value), s"malformed $field: '$value'")

  def ids(field: String, values: Seq[String], ok: String => Boolean): List[String] =
    values.toList.flatMap(id(field, _, ok))

  def nonBlank(field: String, value: String): List[String] =
    check(value != null && value.trim.nonEmpty, s"$field must not be blank")

/** Validator instances, found without imports through this companion. */
object Validator:
  import Validation.*

  private def provenance(p: Provenance): List[String] =
    if p == null then List("missing provenance") else summon[Validator[Provenance]].errors(p)

  private def policy(p: Provenance, owner: String): List[String] =
    check(p != null && p.policyVersion.nonEmpty, s"$owner requires provenance.policyVersion")

  given Validator[Provenance] = p =>
    schemaVersion(p.schemaVersion, SchemaVersions.Provenance) ++
      check(p.sourceDataset != null, "provenance.sourceDataset is missing") ++
      nonBlank("provenance.sourceRecordId", p.sourceRecordId) ++
      nonBlank("provenance.snapshot", p.snapshot) ++
      id("provenance.runId", p.runId, IdFormat.isRunId) ++
      p.policyVersion.toList.flatMap(id("provenance.policyVersion", _, IdFormat.Version.matches)) ++
      p.modelVersion.toList.flatMap(nonBlank("provenance.modelVersion", _))

  given Validator[AuthorMention] = m =>
    schemaVersion(m.schemaVersion, SchemaVersions.AuthorMention) ++
      id("workId", m.workId, IdFormat.isWorkId) ++
      check(
        m.position >= 1 && m.mentionId == s"${m.workId}#${m.position}",
        s"mentionId '${m.mentionId}' must equal workId#position"
      ) ++
      m.orcid.toList.flatMap(o =>
        check(StableIds.normalizeOrcid(o).contains(o), s"malformed orcid: '$o'")
      ) ++
      ids("institutionId", m.institutionIds, IdFormat.isNamespacedId) ++
      provenance(m.provenance)

  given Validator[WorkRecord] = w =>
    schemaVersion(w.schemaVersion, SchemaVersions.WorkRecord) ++
      id("workId", w.workId, IdFormat.isWorkId) ++
      nonBlank("sourceRecordId", w.sourceRecordId) ++
      w.venueId.toList.flatMap(id("venueId", _, IdFormat.isNamespacedId)) ++
      w.doi.toList.flatMap(d =>
        check(StableIds.normalizeDoi(d) == d, s"doi '$d' is not normalized")
      ) ++
      w.authors.toList.flatMap(summon[Validator[AuthorMention]].errors) ++
      check(
        w.authors.forall(_.workId == w.workId),
        "every author mention must carry the work's ID"
      ) ++
      provenance(w.provenance)

  given Validator[CanonicalResearcher] = r =>
    schemaVersion(r.schemaVersion, SchemaVersions.CanonicalResearcher) ++
      id("researcherId", r.researcherId, IdFormat.isResearcherId) ++
      nonBlank("displayName", r.displayName) ++
      confidence("identityConfidence", r.identityConfidence) ++
      provenance(r.provenance)

  given Validator[AuthorshipRecord] = a =>
    schemaVersion(a.schemaVersion, SchemaVersions.AuthorshipRecord) ++
      id("researcherId", a.researcherId, IdFormat.isResearcherId) ++
      id("workId", a.workId, IdFormat.isWorkId) ++
      check(a.position >= 1, s"author position must be >= 1, got ${a.position}") ++
      check(
        a.mentionId == s"${a.workId}#${a.position}",
        s"mentionId '${a.mentionId}' must equal workId#position"
      ) ++
      confidence("matchConfidence", a.matchConfidence) ++
      provenance(a.provenance)

  given Validator[PublicationTeam] = t =>
    schemaVersion(t.schemaVersion, SchemaVersions.PublicationTeam) ++
      id("workId", t.workId, IdFormat.isWorkId) ++
      check(
        t.mentionIds == t.mentionIds.indices.map(i => s"${t.workId}#${i + 1}"),
        "mentionIds must be workId#1..workId#n in author order"
      ) ++
      provenance(t.provenance)

  given Validator[CoauthorPairCandidate] = p =>
    schemaVersion(p.schemaVersion, SchemaVersions.CoauthorPairCandidate) ++
      id("workId", p.workId, IdFormat.isWorkId) ++
      ids(
        "author mention ID",
        Seq(p.authorA, p.authorB),
        m => m.startsWith(p.workId + "#") && IdFormat.isMentionId(m)
      ) ++
      canonicalPair("author", p.authorA, p.authorB) ++
      provenance(p.provenance)

  given Validator[CoauthorEdge] = e =>
    val expectedId = e.provenance match
      case null => None
      case p    =>
        p.policyVersion.flatMap(pv =>
          StableIds
            .coauthorEdgeId(e.researcherA, e.researcherB, e.windowStartYear, e.windowEndYear, pv)
            .toOption
        )
    schemaVersion(e.schemaVersion, SchemaVersions.CoauthorEdge) ++
      ids("researcherId", Seq(e.researcherA, e.researcherB), IdFormat.isResearcherId) ++
      canonicalPair("researcher", e.researcherA, e.researcherB) ++
      window("window", e.windowStartYear, e.windowEndYear) ++
      window("collaboration years", e.firstCollaborationYear, e.lastCollaborationYear) ++
      check(
        e.firstCollaborationYear >= e.windowStartYear && e.lastCollaborationYear <= e.windowEndYear,
        "collaboration years must lie inside the window"
      ) ++
      check(e.jointPublicationCount >= 1, "jointPublicationCount must be >= 1") ++
      ids("representativeWorkId", e.representativeWorkIds, IdFormat.isWorkId) ++
      policy(e.provenance, "CoauthorEdge") ++
      id("edgeId", e.edgeId, IdFormat.isCoauthorEdgeId) ++
      check(
        expectedId.forall(_ == e.edgeId),
        s"edgeId '${e.edgeId}' does not match its stable-ID formula"
      ) ++
      provenance(e.provenance)

  given Validator[ResearcherMetrics] = m =>
    schemaVersion(m.schemaVersion, SchemaVersions.ResearcherMetrics) ++
      id("researcherId", m.researcherId, IdFormat.isResearcherId) ++
      window("window", m.windowStartYear, m.windowEndYear) ++
      check(
        Seq(
          m.publicationCount,
          m.distinctCoauthorCount,
          m.totalCoauthorOccurrences,
          m.maximumCoauthorsOnOnePaper
        )
          .forall(_ >= 0) && m.averageCoauthorsPerPaper >= 0 && m.weightedDegree >= 0,
        "counts, averages, and weighted degree must be non-negative"
      ) ++
      check(m.componentSize >= 1, "componentSize must be >= 1") ++
      ids("maximumTeamWorkId", m.maximumTeamWorkIds, IdFormat.isWorkId) ++
      m.participationCoefficient.toList.flatMap(confidence("participationCoefficient", _)) ++
      nonBlank("componentId", m.componentId) ++
      provenance(m.provenance)

  given Validator[Venue] = v =>
    schemaVersion(v.schemaVersion, SchemaVersions.Venue) ++
      id("venueId", v.venueId, IdFormat.isNamespacedId) ++ nonBlank("name", v.name) ++ provenance(
        v.provenance
      )

  given Validator[Institution] = i =>
    schemaVersion(i.schemaVersion, SchemaVersions.Institution) ++
      id("institutionId", i.institutionId, IdFormat.isNamespacedId) ++ nonBlank("name", i.name) ++
      provenance(i.provenance)

  given Validator[Topic] = t =>
    schemaVersion(t.schemaVersion, SchemaVersions.Topic) ++
      id("topicId", t.topicId, IdFormat.isNamespacedId) ++ nonBlank("name", t.name) ++ provenance(
        t.provenance
      )

  given Validator[MatchCandidate] = c =>
    schemaVersion(c.schemaVersion, SchemaVersions.MatchCandidate) ++
      ids("candidate ID", Seq(c.sourceId, c.targetId), IdFormat.isVisibleAscii) ++
      nonBlank("blockingKey", c.blockingKey) ++
      check(c.features.values.forall(v => !v.isNaN && !v.isInfinite), "features must be finite") ++
      provenance(c.provenance)

  given Validator[MatchDecision] = d =>
    schemaVersion(d.schemaVersion, SchemaVersions.MatchDecision) ++
      ids("candidate ID", Seq(d.sourceId, d.targetId), IdFormat.isVisibleAscii) ++
      confidence("confidence", d.confidence) ++
      check(
        Try(java.time.Instant.parse(d.decidedAt)).isSuccess,
        s"decidedAt '${d.decidedAt}' is not an ISO-8601 instant"
      ) ++
      check(d.reasonCodes.nonEmpty, "reasonCodes must not be empty") ++
      policy(d.provenance, "MatchDecision") ++
      provenance(d.provenance)

  given Validator[GraphNode] = n =>
    val ok: String => Boolean = n.label match
      case NodeLabel.Researcher    => IdFormat.isResearcherId
      case NodeLabel.Work          => IdFormat.isWorkId
      case NodeLabel.AuthorMention => IdFormat.isMentionId
      case NodeLabel.TextChunk     => IdFormat.isChunkId
      case NodeLabel.Community     => IdFormat.isCommunityId
      case _                       => IdFormat.isNamespacedId
    schemaVersion(n.schemaVersion, SchemaVersions.GraphNode) ++ id(
      "nodeId",
      n.nodeId,
      ok
    ) ++ provenance(n.provenance)

  given Validator[GraphEdge] = e =>
    schemaVersion(e.schemaVersion, SchemaVersions.GraphEdge) ++
      id("edgeId", e.edgeId, IdFormat.isGraphEdgeId) ++
      ids("endpoint", Seq(e.fromId, e.toId), IdFormat.isVisibleAscii) ++
      (if e.edgeType.undirected then
         ids("researcherId", Seq(e.fromId, e.toId), IdFormat.isResearcherId) ++
           canonicalPair(e.edgeType.toString, e.fromId, e.toId)
       else Nil) ++
      (if e.edgeType == EdgeType.MEMBER_OF then
         id("researcherId", e.fromId, IdFormat.isResearcherId) ++
           id("communityId", e.toId, IdFormat.isCommunityId)
       else Nil) ++
      provenance(e.provenance)

  given Validator[EvidenceItem] = e =>
    schemaVersion(e.schemaVersion, SchemaVersions.EvidenceItem) ++
      id("evidenceId", e.evidenceId, IdFormat.isEvidenceId) ++
      check(e.subjectIds.nonEmpty, "subjectIds must not be empty") ++
      ids("subjectId", e.subjectIds, IdFormat.isVisibleAscii) ++
      e.workId.toList.flatMap(id("workId", _, IdFormat.isWorkId)) ++
      e.chunkId.toList.flatMap(id("chunkId", _, IdFormat.isChunkId)) ++
      nonBlank("sourceUri", e.sourceUri) ++
      provenance(e.provenance)

  given Validator[EvidenceBundle] = b =>
    val itemV = summon[Validator[EvidenceItem]]
    schemaVersion(b.schemaVersion, SchemaVersions.EvidenceBundle) ++
      nonBlank("queryId", b.queryId) ++
      (b.graphEvidence ++ b.semanticEvidence).toList.flatMap(itemV.errors) ++
      check(
        b.graphEvidence.forall(!_.evidenceType.isSemantic),
        "graphEvidence must not hold semantic evidence"
      ) ++
      check(
        b.semanticEvidence.forall(_.evidenceType.isSemantic),
        "semanticEvidence must hold only semantic evidence"
      ) ++
      provenance(b.provenance)

  given Validator[QueryPlan] = q =>
    schemaVersion(q.schemaVersion, SchemaVersions.QueryPlan) ++
      nonBlank("queryId", q.queryId) ++
      check(q.operations.nonEmpty, "operations must not be empty") ++
      ids("researcherId", q.researcherIds, IdFormat.isResearcherId) ++
      (for f <- q.yearFrom.toList; t <- q.yearTo.toList; e <- window("year", f, t) yield e) ++
      check(
        q.graphLimit >= 1 && q.vectorLimit >= 1 && q.maxPathDepth >= 1,
        "limits must be >= 1"
      ) ++
      provenance(q.provenance)

  given Validator[AnswerClaim] = c =>
    schemaVersion(c.schemaVersion, SchemaVersions.AnswerClaim) ++
      nonBlank("claimId", c.claimId) ++
      nonBlank("text", c.text) ++
      ids("evidenceId", c.evidenceIds, IdFormat.isEvidenceId) ++
      check(
        c.evidenceIds.nonEmpty || c.verificationStatus == VerificationStatus.UNSUPPORTED,
        s"claim '${c.claimId}' is ${c.verificationStatus} but cites no evidence"
      ) ++
      provenance(c.provenance)

  given Validator[AnswerResponse] = r =>
    schemaVersion(r.schemaVersion, SchemaVersions.AnswerResponse) ++
      nonBlank("queryId", r.queryId) ++
      r.claims.toList.flatMap(summon[Validator[AnswerClaim]].errors) ++
      provenance(r.provenance)

/** JSON wire codec: uPickle (D-05) plus contract validation on the way in. */
object WireCodec:
  def encode[A: ReadWriter](a: A): String = write(a)

  /** Parses and validates; malformed JSON, a missing field (including provenance), an unknown enum
    * value, or any validation rule failure is a Left.
    */
  def decode[A: ReadWriter: Validator](json: String): Either[List[String], A] =
    Try(read[A](json)).toEither.left
      .map(e => List(s"undecodable ${e.getClass.getSimpleName}: ${e.getMessage}"))
      .flatMap(Validation.validate)
