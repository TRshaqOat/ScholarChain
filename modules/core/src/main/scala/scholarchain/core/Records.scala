package scholarchain.core

import upickle.default.ReadWriter

/** Current schema version of every record. Validation accepts exactly these values. A wire-shape
  * change bumps the version here and updates the frozen wire examples in the core tests.
  */
object SchemaVersions:
  val Provenance = "provenance-v1"
  val WorkRecord = "work-record-v1"
  val AuthorMention = "author-mention-v1"
  val CanonicalResearcher = "canonical-researcher-v1"
  val AuthorshipRecord = "authorship-v1"
  val PublicationTeam = "publication-team-v1"
  val CoauthorPairCandidate = "coauthor-pair-candidate-v1"
  val CoauthorEdge = "coauthor-edge-v1"
  val ResearcherMetrics = "researcher-metrics-v1"
  val Venue = "venue-v1"
  val Institution = "institution-v1"
  val Topic = "topic-v1"
  val MatchCandidate = "match-candidate-v1"
  val MatchDecision = "match-decision-v1"
  val GraphNode = "graph-node-v1"
  val GraphEdge = "graph-edge-v1"
  val EvidenceItem = "evidence-v1"
  val EvidenceBundle = "evidence-bundle-v1"
  val QueryPlan = "query-plan-v1"
  val AnswerClaim = "answer-claim-v1"
  val AnswerResponse = "answer-response-v1"

// Conventions for every record below:
// - Option fields serialize as JSON null when absent. No field has a default value.
// - Years are calendar years. Windows are inclusive [windowStartYear, windowEndYear].
// - DOIs are lower-case without a resolver prefix (datasets/schemas/dblp.md, "DOI (derived)").
// - ORCID iDs are bare "NNNN-NNNN-NNNN-NNNX" without the https://orcid.org/ prefix.

/** Where a record came from.
  *
  * @param sourceDataset
  *   the dataset (DBLP, OPENALEX, CROSSREF, ORCID) or the stage (DERIVED, RUNTIME)
  * @param sourceRecordId
  *   the source's own record ID (DBLP key, OpenAlex work URL ID, DOI for Crossref, ORCID iD or a
  *   `synthetic:` fixture ID for ORCID). For DERIVED and RUNTIME records: the stable ID of the
  *   record itself (or the query ID); the run manifest named by runId lists the inputs.
  * @param snapshot
  *   dataset snapshot date (`YYYY-MM-DD`) or, for derived records, the input snapshot label
  * @param runId
  *   pipeline or runtime run ID (`run-<hex32>`, see StableIds.runId)
  * @param policyVersion
  *   match, graph, metric, or fusion policy version, where one applies
  * @param modelVersion
  *   LLM or embedding model version, where one applies
  */
final case class Provenance(
    schemaVersion: String,
    sourceDataset: SourceType,
    sourceRecordId: String,
    snapshot: String,
    runId: String,
    policyVersion: Option[String],
    modelVersion: Option[String]
) derives ReadWriter

/** One author slot on one source work. mentionId = workId + "#" + position (StableIds.mentionId).
  *
  * @param position
  *   1-based author order on the work
  * @param rawName
  *   the name as the source prints it; for DBLP this keeps the homonym suffix ("Wei Wang 0001")
  * @param sourceAuthorId
  *   the source's author ID when it has one (for example `openalex:A123`); candidate evidence only
  * @param institutionIds
  *   namespaced institution IDs from the source (for example `ror:...`, `openalex:I...`)
  */
final case class AuthorMention(
    schemaVersion: String,
    mentionId: String,
    workId: String,
    position: Int,
    rawName: String,
    normalizedName: String,
    orcid: Option[String],
    sourceAuthorId: Option[String],
    institutionIds: Vector[String],
    provenance: Provenance
) derives ReadWriter

/** One work as one source describes it. workId = namespace + ":" + source ID (StableIds).
  *
  * @param title
  *   source title (nullable in every source schema)
  * @param workType
  *   the source's own type value (`inproceedings`, `article`, `conference-paper`, ...)
  * @param externalIds
  *   other identifiers the source supplies, keyed by namespace (for example `mag`, `openalex`)
  * @param abstractTextRef
  *   S3 URI of permitted abstract text; the text itself is never inlined
  */
final case class WorkRecord(
    schemaVersion: String,
    workId: String,
    source: SourceType,
    sourceRecordId: String,
    title: Option[String],
    normalizedTitle: Option[String],
    year: Option[Int],
    venueId: Option[String],
    workType: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    externalIds: Map[String, String],
    authors: Vector[AuthorMention],
    abstractTextRef: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A resolved researcher identity. Unresolved mentions stay separate researchers (never forced to
  * merge).
  *
  * @param identityConfidence
  *   confidence in [0, 1] that the merged mentions are one person
  * @param uncertainty
  *   human-readable statement of remaining identity doubt, when there is any
  */
final case class CanonicalResearcher(
    schemaVersion: String,
    researcherId: String,
    displayName: String,
    alternateNames: Vector[String],
    orcid: Option[String],
    externalIds: Map[String, String],
    identityConfidence: Double,
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter

/** AUTHORED: a canonical researcher wrote a work through one author mention. */
final case class AuthorshipRecord(
    schemaVersion: String,
    researcherId: String,
    workId: String,
    mentionId: String,
    position: Int,
    year: Option[Int],
    matchConfidence: Double,
    provenance: Provenance
) derives ReadWriter

/** The ordered author mentions of one retained work. Team size is mentionIds.size. */
final case class PublicationTeam(
    schemaVersion: String,
    workId: String,
    year: Option[Int],
    mentionIds: Vector[String],
    provenance: Provenance
) derives ReadWriter:
  def teamSize: Int = mentionIds.size

/** One unordered coauthor pair from one work, before identity resolution (Hadoop pair expansion).
  * authorA and authorB are author mention IDs in canonical order (CanonicalPair); each mention ID
  * carries its author position after the final "#".
  */
final case class CoauthorPairCandidate(
    schemaVersion: String,
    authorA: String,
    authorB: String,
    workId: String,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter

/** COAUTHORED_WITH for one canonical researcher pair in one time window.
  *
  * edgeId = StableIds.coauthorEdgeId(researcherA, researcherB, window, provenance.policyVersion).
  * researcherA < researcherB (CanonicalPair).
  */
final case class CoauthorEdge(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    windowStartYear: Int,
    windowEndYear: Int,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    provenance: Provenance
) derives ReadWriter

/** Spark-computed collaboration metrics for one researcher and one window. Bridge measures are the
  * three D-10 measures; each is None when the community stage did not run for this window.
  *
  * publicationCount P_r, distinctCoauthorCount C_r, totalCoauthorOccurrences T_r,
  * averageCoauthorsPerPaper A_r, maximumCoauthorsOnOnePaper M_r (achieved by maximumTeamWorkIds),
  * weightedDegree W_r (edge weight = joint publication count).
  */
final case class ResearcherMetrics(
    schemaVersion: String,
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Double,
    componentId: String,
    componentSize: Long,
    communityId: Option[String],
    crossCommunityDegree: Option[Long],
    communitiesConnected: Option[Long],
    participationCoefficient: Option[Double],
    coreness: Option[Int],
    provenance: Provenance
) derives ReadWriter

/** venueId: namespaced, for example `dblp-venue:conf/ics` or `openalex:S4306419857`. */
final case class Venue(
    schemaVersion: String,
    venueId: String,
    name: String,
    venueType: Option[String],
    externalIds: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** institutionId: namespaced, preferring `ror:<id>` over `openalex:I...`. */
final case class Institution(
    schemaVersion: String,
    institutionId: String,
    name: String,
    countryCode: Option[String],
    externalIds: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** topicId: namespaced, for example `openalex:T10054`; subfield and field follow OpenAlex. */
final case class Topic(
    schemaVersion: String,
    topicId: String,
    name: String,
    subfield: Option[String],
    field: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A blocked candidate pair with its deterministic features, before any decision. */
final case class MatchCandidate(
    schemaVersion: String,
    entityType: MatchEntityType,
    sourceId: String,
    targetId: String,
    blockingKey: String,
    features: Map[String, Double],
    provenance: Provenance
) derives ReadWriter

/** A match decision. The policy version, model version, and run ID live in provenance;
  * provenance.policyVersion is required. decidedAt is an ISO-8601 instant.
  */
final case class MatchDecision(
    schemaVersion: String,
    entityType: MatchEntityType,
    sourceId: String,
    targetId: String,
    features: Map[String, Double],
    rulesFired: Vector[String],
    decision: MatchOutcome,
    confidence: Double,
    reasonCodes: Vector[String],
    promptVersion: Option[String],
    decidedAt: String,
    provenance: Provenance
) derives ReadWriter

/** A research-graph node. nodeId is the entity's stable ID (researcher, work, mention, ...). */
final case class GraphNode(
    schemaVersion: String,
    nodeId: String,
    label: NodeLabel,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** A research-graph edge. For undirected types (COAUTHORED_WITH) fromId < toId (CanonicalPair).
  * edgeId = StableIds.graphEdgeId(edgeType, fromId, toId, policyVersion, evidence).
  */
final case class GraphEdge(
    schemaVersion: String,
    edgeId: String,
    edgeType: EdgeType,
    fromId: String,
    toId: String,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** One piece of evidence a claim can cite.
  *
  * @param subjectIds
  *   the stable IDs this evidence is about, in order (a path lists its hops)
  * @param sourceUri
  *   where the underlying record or text is stored
  * @param score
  *   retrieval score for semantic evidence; None for deterministic facts
  * @param facts
  *   deterministic values a claim can be checked against (for example "distinctCoauthorCount" ->
  *   "142", "windowStartYear" -> "2015")
  */
final case class EvidenceItem(
    schemaVersion: String,
    evidenceId: String,
    evidenceType: EvidenceType,
    subjectIds: Vector[String],
    workId: Option[String],
    sourceUri: String,
    chunkId: Option[String],
    graphPath: Option[Vector[String]],
    score: Option[Double],
    text: Option[String],
    textHash: Option[String],
    facts: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** Evidence for one query. Deterministic graph evidence and semantic context are separate lists.
  * The Phase 9 runtime uses its own rag-evidence-bundle-v1 (modules/rag), not this record.
  */
final case class EvidenceBundle(
    schemaVersion: String,
    queryId: String,
    graphEvidence: Vector[EvidenceItem],
    semanticEvidence: Vector[EvidenceItem],
    provenance: Provenance
) derives ReadWriter

/** Typed plan for one question. Years are inclusive; None means unbounded on that side. The Phase 9
  * runtime uses its own rag-query-plan-v1 (modules/rag), not this record.
  */
final case class QueryPlan(
    schemaVersion: String,
    queryId: String,
    operations: Vector[QueryOperation],
    researcherNames: Vector[String],
    researcherIds: Vector[String],
    yearFrom: Option[Int],
    yearTo: Option[Int],
    topics: Vector[String],
    venues: Vector[String],
    institutions: Vector[String],
    rankingMetric: Option[CollaborationMetric],
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    provenance: Provenance
) derives ReadWriter

/** One answer claim. VERIFIED and PARTIALLY_SUPPORTED claims cite at least one evidence ID. */
final case class AnswerClaim(
    schemaVersion: String,
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    verificationStatus: VerificationStatus,
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter

/** The domain answer to one query. abstained is true when identity or evidence was insufficient. */
final case class AnswerResponse(
    schemaVersion: String,
    queryId: String,
    summary: String,
    claims: Vector[AnswerClaim],
    abstained: Boolean,
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter
