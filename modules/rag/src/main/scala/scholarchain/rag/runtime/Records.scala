package scholarchain.rag.runtime

import scholarchain.core.{
  AnswerResponse,
  CollaborationMetric,
  EvidenceItem,
  Provenance,
  QueryOperation,
  ResearcherMetrics,
  VerificationStatus
}
import upickle.default.ReadWriter

// Runtime records (modules/rag/RUNTIME.md). Conventions follow the core records: Option fields are
// JSON null when absent, no field has a default, enums are their case names, years are inclusive.

// ---- Request (transport-free form of the spec POST /v1/query body) ----

/** Structured constraints. A Some field overrides the planner model (ruling 4). */
final case class RequestConstraints(
    yearFrom: Option[Int],
    yearTo: Option[Int],
    topics: Option[Vector[String]],
    institutions: Option[Vector[String]],
    venues: Option[Vector[String]]
) derives ReadWriter

final case class RequestAnalysis(
    rankingMetric: Option[CollaborationMetric],
    includeTeamSize: Option[Boolean],
    includeBridgeMetrics: Option[Boolean],
    includeCommunities: Option[Boolean],
    includePaths: Option[Boolean]
) derives ReadWriter

/** Caller limits; each is clamped to its configured maximum, never raised above it. */
final case class RequestRetrieval(
    graphLimit: Option[Int],
    vectorLimit: Option[Int],
    maxPathDepth: Option[Int]
) derives ReadWriter

final case class RequestOutput(
    includeEvidence: Boolean,
    includeScores: Boolean,
    includeUncertainty: Boolean
) derives ReadWriter

final case class QueryRequest(
    schemaVersion: String,
    queryId: String,
    sessionId: Option[String],
    question: String,
    constraints: Option[RequestConstraints],
    analysis: Option[RequestAnalysis],
    retrieval: Option[RequestRetrieval],
    output: Option[RequestOutput]
) derives ReadWriter

// ---- Planning ----

/** A window as asked for. NAMED carries `name`; RANGE carries yearFrom and/or yearTo (an open side
  * is the corpus bound); DEFAULT carries neither.
  */
final case class WindowRequest(
    kind: WindowKind,
    name: Option[String],
    yearFrom: Option[Int],
    yearTo: Option[Int]
) derives ReadWriter

/** Two stored, non-overlapping windows, earlier first (a stored CHANGE# pair). */
final case class WindowComparison(fromWindow: String, toWindow: String) derives ReadWriter

/** One allowlisted operation. `researchers` index into the plan's researcher names (RESEARCHER:
  * one, PAIR: two, CORPUS: none).
  */
final case class PlannedOperation(
    operation: QueryOperation,
    scope: OperationScope,
    researchers: Vector[Int],
    metric: Option[CollaborationMetric],
    topN: Option[Int]
) derives ReadWriter

/** The JSON object the planner model must return (prompts/planner, schema planner-output-v1). It
  * selects only enum values and copies names; the code builds the QueryPlan from it.
  */
final case class PlannerOutput(
    schemaVersion: String,
    operations: Vector[PlannedOperation],
    researcherNames: Vector[String],
    metrics: Vector[CollaborationMetric],
    window: WindowRequest,
    comparison: Option[WindowComparison],
    topics: Vector[String],
    venues: Vector[String],
    institutions: Vector[String]
) derives ReadWriter

/** A researcher name with its code-point offset in the normalized question (ruling 4: every name
  * must occur in the question text).
  */
final case class NameMention(name: String, offset: Int) derives ReadWriter

/** The stored window a request maps to. exact is false when the smallest stored window containing
  * the request is wider; `note` then states that in the answer (ruling 6).
  */
final case class WindowSelection(
    requested: WindowRequest,
    window: String,
    startYear: Int,
    endYear: Int,
    exact: Boolean,
    note: Option[String]
) derives ReadWriter

final case class PlanLimits(
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    topK: Int
) derives ReadWriter

final case class QueryPlan(
    schemaVersion: String,
    queryId: String,
    operations: Vector[PlannedOperation],
    researcherNames: Vector[NameMention],
    metrics: Vector[CollaborationMetric],
    window: WindowSelection,
    comparison: Option[WindowComparison],
    topics: Vector[String],
    venues: Vector[String],
    institutions: Vector[String],
    semanticIndexes: Vector[String],
    limits: PlanLimits,
    source: PlannerSource,
    overriddenFields: Vector[String],
    rejectedItems: Vector[String],
    promptVersion: Option[String],
    modelId: Option[String],
    provenance: Provenance
) derives ReadWriter

// ---- Stage 2: researcher resolution ----

/** An ID with a display name and, where it applies, a count (works, joint works, edges). */
final case class NamedCount(id: String, name: String, count: Option[Long]) derives ReadWriter

final case class NameCandidate(
    researcherId: String,
    displayName: String,
    matchedName: String,
    works: Long,
    share: Option[Double],
    identityConfidence: Double,
    mentionCount: Long,
    dblpAnchored: Boolean,
    orcid: Option[String],
    institutions: Vector[NamedCount],
    topCoauthors: Vector[NamedCount],
    runId: String,
    snapshot: String
) derives ReadWriter

/** dominant-name-v1 applied to one name. candidates are the exact display-name matches (they
  * decide); otherNameMatches share the name-v1 key but not the display name (shown, never counted).
  */
final case class ResearcherResolution(
    schemaVersion: String,
    name: String,
    nameKey: Option[String],
    policyVersion: String,
    dominantShare: Double,
    minWorks: Long,
    decision: ResolutionDecision,
    researcherId: Option[String],
    topShare: Option[Double],
    totalWorks: Long,
    candidateCount: Int,
    candidates: Vector[NameCandidate],
    otherNameMatches: Vector[NameCandidate],
    truncated: Boolean,
    evidenceId: Option[String]
) derives ReadWriter

// ---- Stages 3 to 5: deterministic graph facts ----

/** One WINDOW# item. metricVersion = `<schemaVersion>@<runId>` (community fields: communityRunId).
  */
final case class MetricRecord(
    window: String,
    metrics: ResearcherMetrics,
    communitySize: Option[Long],
    graphDegree: Option[Long],
    communityRunId: Option[String],
    metricVersion: String
) derives ReadWriter

final case class RankingEntry(
    rank: Int,
    researcherId: String,
    value: Double,
    displayName: Option[String]
) derives ReadWriter

/** One RANKING# item, possibly cut to topN or filtered (filteredBy names each applied filter).
  * lastStoredValue is the value at the last stored rank (ties may continue past the stored list).
  */
final case class RankingRecord(
    metric: CollaborationMetric,
    window: String,
    windowStartYear: Int,
    windowEndYear: Int,
    entries: Vector[RankingEntry],
    storedSize: Int,
    lastStoredValue: Double,
    filteredBy: Vector[String],
    metricVersion: String,
    provenance: Provenance
) derives ReadWriter

/** One CHANGE# item; values holds its attributes in canonical number text. */
final case class ChangeRecord(
    researcherId: String,
    comparison: WindowComparison,
    presence: String,
    values: Map[String, String],
    metricVersion: String,
    provenance: Provenance
) derives ReadWriter

/** One COAUTHORED_WITH edge seen from researcherId. supportingWorkIds is the complete sorted list;
  * supportingWorkIdsRef is the S3 key of the evidence object it was read from (edges with more than
  * 20 works), else None.
  */
final case class CoauthorLink(
    edgeId: String,
    researcherId: String,
    coauthorId: String,
    coauthorName: Option[String],
    window: String,
    jointPublicationCount: Long,
    firstYear: Int,
    lastYear: Int,
    supportingWorkIds: Vector[String],
    supportingWorkIdsRef: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A researcher's coauthor edges in a window. pairWith = Some(other): the lookup of one pair's edge
  * (links holds it, or is empty when the pair has no edge in the window). crossCommunity: the
  * researcher's edges to other communities (BRIDGE_RANKING), not its strongest coauthors.
  */
final case class CoauthorList(
    researcherId: String,
    window: String,
    links: Vector[CoauthorLink],
    limit: Int,
    truncated: Boolean,
    pairWith: Option[String],
    crossCommunity: Boolean
) derives ReadWriter

/** Hop n goes from link.researcherId to link.coauthorId. */
final case class PathHop(hop: Int, link: CoauthorLink) derives ReadWriter

final case class PathRecord(
    sourceId: String,
    targetId: String,
    window: String,
    status: PathStatus,
    maxDepth: Int,
    pathLength: Option[Int],
    researcherIds: Vector[String],
    hops: Vector[PathHop],
    templateId: String,
    graphRunId: String
) derives ReadWriter

final case class CommunityRecord(
    communityId: String,
    window: String,
    size: Long,
    internalEdges: Long,
    crossCommunityEdges: Long,
    label: Option[String],
    members: Vector[NamedCount],
    membersTruncated: Boolean,
    neighbors: Vector[NamedCount],
    provenance: Provenance
) derives ReadWriter

/** Topic, venue, or institution counts for a researcher, community, or topic. window is None for
  * institutions (not windowed). basis names the counted relation, for example "AUTHORED works".
  */
final case class FacetRecord(
    subjectId: String,
    facet: FacetKind,
    window: Option[String],
    entries: Vector[NamedCount],
    basis: String,
    truncated: Boolean,
    provenance: Provenance
) derives ReadWriter

final case class WorkRef(
    workId: String,
    title: Option[String],
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter

final case class MissingEvidence(reason: MissingReason, subject: String, detail: String)
    derives ReadWriter

/** One bound graph-template or key-value read (no parameter values, which may hold user text). */
final case class TemplateCall(
    templateId: String,
    latencyMs: Long,
    resultCount: Int,
    truncated: Boolean
) derives ReadWriter

final case class GraphFacts(
    schemaVersion: String,
    queryId: String,
    graphRunId: String,
    metrics: Vector[MetricRecord],
    rankings: Vector[RankingRecord],
    changes: Vector[ChangeRecord],
    coauthors: Vector[CoauthorList],
    paths: Vector[PathRecord],
    communities: Vector[CommunityRecord],
    facets: Vector[FacetRecord],
    works: Vector[WorkRef],
    missing: Vector[MissingEvidence],
    templateCalls: Vector[TemplateCall]
) derives ReadWriter

// ---- Stage 6: semantic context ----

/** One index query. items are semantic EvidenceItems (RAG.md, semantic-context-v1). filter is the
  * JSON of the metadata filter that was sent.
  */
final case class SemanticResult(
    schemaVersion: String,
    index: String,
    embeddingModelVersion: String,
    filter: String,
    topK: Int,
    items: Vector[EvidenceItem],
    droppedCount: Int,
    latencyMs: Long
) derives ReadWriter

// ---- Stage 7: evidence bundle ----

final case class BundleBudget(
    tokenBudget: Int,
    graphTokens: Int,
    semanticTokens: Int,
    omittedGraphItems: Int,
    omittedSemanticItems: Int
) derives ReadWriter

/** Graph evidence (metrics, rankings, edges, paths, communities, facets, works, identity) and
  * semantic evidence are separate lists; no semantic item may support a graph claim.
  */
final case class EvidenceBundle(
    schemaVersion: String,
    queryId: String,
    windows: Vector[WindowSelection],
    graphEvidence: Vector[EvidenceItem],
    semanticEvidence: Vector[EvidenceItem],
    missing: Vector[MissingEvidence],
    budget: BundleBudget,
    graphRunId: String,
    provenance: Provenance
) derives ReadWriter

// ---- Stage 8: synthesis ----

/** One claim as the synthesis model drafts it. values are the facts it asserts, keyed by evidence
  * fact names (for example "distinctCoauthorCount" -> "142", "window" -> "all-years").
  */
final case class ClaimDraft(
    claimId: String,
    kind: ClaimKind,
    text: String,
    evidenceIds: Vector[String],
    subjectIds: Vector[String],
    window: Option[String],
    values: Map[String, String]
) derives ReadWriter

/** The JSON object the synthesis model must return (prompts/synthesis; the schema the prompt file
  * names: synthesis-output-v1, -v2, or -v3, RUNTIME.md section 11).
  */
final case class SynthesisOutput(
    schemaVersion: String,
    summary: String,
    claims: Vector[ClaimDraft],
    uncertainty: Vector[String]
) derives ReadWriter

// ---- Stage 9: verification ----

final case class CheckResult(rule: String, outcome: CheckOutcome, detail: String) derives ReadWriter

final case class ClaimCheck(
    claimId: String,
    status: VerificationStatus,
    checks: Vector[CheckResult],
    action: ClaimAction
) derives ReadWriter

final case class VerificationCounts(
    verified: Int,
    partiallySupported: Int,
    unsupported: Int,
    conflicting: Int
) derives ReadWriter

/** A claim verification removed (n7, audit only): its text and the checks that did not pass. */
final case class RemovedClaim(
    claimId: String,
    kind: ClaimKind,
    text: String,
    status: VerificationStatus,
    failedChecks: Vector[CheckResult]
) derives ReadWriter

/** A model summary or uncertainty line verification left out (n7, audit only), with its reasons. */
final case class DroppedLine(part: LinePart, text: String, reasons: Vector[String])
    derives ReadWriter

final case class VerificationReport(
    schemaVersion: String,
    queryId: String,
    checks: Vector[ClaimCheck],
    counts: VerificationCounts
) derives ReadWriter

// ---- Answer ----

final case class Abstention(reason: AbstentionReason, detail: String) derives ReadWriter

/** The runtime result. response is the core answer; answerHash = StableIds.textHash of its JSON. */
final case class RuntimeAnswer(
    schemaVersion: String,
    response: AnswerResponse,
    plan: QueryPlan,
    resolutions: Vector[ResearcherResolution],
    verification: VerificationReport,
    abstention: Option[Abstention],
    answerHash: String
) derives ReadWriter

// ---- Stage 10: audit ----

/** One model attempt. requests counts the Bedrock requests it sent (2 when the expired-token retry
  * ran; 0 when the attempt was not sent). failure is the short reason of a rejected or failed
  * attempt: rule names with schema paths and the output's shape (length, first and last character
  * class, stop reason), or the exception class; never prompt, retrieved, or model text.
  */
final case class LlmCall(
    stage: Stage,
    modelId: String,
    promptVersion: String,
    schemaVersion: String,
    attempt: Int,
    outcome: CallOutcome,
    requests: Int,
    inputTokens: Option[Int],
    outputTokens: Option[Int],
    latencyMs: Long,
    throttleWaitMs: Long,
    failure: Option[String]
) derives ReadWriter

final case class EmbeddingCall(
    index: String,
    modelVersion: String,
    attempt: Int,
    outcome: CallOutcome,
    inputTokens: Option[Int],
    latencyMs: Long,
    throttleWaitMs: Long
) derives ReadWriter

final case class StageTiming(stage: Stage, outcome: StageOutcome, latencyMs: Long)
    derives ReadWriter

final case class ResolutionSummary(
    name: String,
    decision: ResolutionDecision,
    researcherId: Option[String],
    topShare: Option[Double],
    candidateCount: Int
) derives ReadWriter

/** Write-once audit object (logs/ prefix). Holds no question text (only its hash and length), no
  * prompt text, no raw model output, and no credentials. v3 (n7) adds removedClaims and
  * droppedLines: the parsed text of each removed claim and left-out line with why, for review only;
  * they are never logged, and a service view of the audit leaves their text out
  * (RuntimeApi.withoutModelText).
  */
final case class AuditRecord(
    schemaVersion: String,
    queryId: String,
    sessionId: Option[String],
    runId: String,
    createdAt: String,
    mode: RuntimeMode,
    questionHash: String,
    questionChars: Int,
    plan: QueryPlan,
    resolutions: Vector[ResolutionSummary],
    graphEvidenceIds: Vector[String],
    semanticEvidenceIds: Vector[String],
    metricVersions: Vector[String],
    graphRunId: String,
    vectorIndexes: Vector[String],
    embeddingModelVersion: Option[String],
    promptVersions: Map[String, String],
    modelIds: Map[String, String],
    llmCalls: Vector[LlmCall],
    embeddingCalls: Vector[EmbeddingCall],
    templateCalls: Vector[TemplateCall],
    stages: Vector[StageTiming],
    budget: BundleBudget,
    verification: VerificationCounts,
    removedClaims: Vector[RemovedClaim],
    droppedLines: Vector[DroppedLine],
    abstained: Boolean,
    abstentionReason: Option[AbstentionReason],
    answerHash: String,
    provenance: Provenance,
    // The one defaulted field (experiment E): upickle omits it when FUSED, so a FUSED audit keeps
    // the rag-audit-v3 bytes it had before the mode existed, and a v3 audit without it reads as FUSED.
    retrievalMode: RetrievalMode = RetrievalMode.FUSED
) derives ReadWriter
