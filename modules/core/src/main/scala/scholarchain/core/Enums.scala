package scholarchain.core

import upickle.default.{ReadWriter, readwriter}

/** Wire form of every enum: the case name as a JSON string. Unknown names fail decoding. */
private def enumRW[E](values: Array[E], name: E => String): ReadWriter[E] =
  val byName = values.map(v => name(v) -> v).toMap
  readwriter[String].bimap[E](
    name,
    s => byName.getOrElse(s, throw new IllegalArgumentException(s"unknown enum value: $s"))
  )

/** Dataset or stage that produced a record. DERIVED: computed by a ScholarChain pipeline stage.
  * RUNTIME: produced while answering a query.
  */
enum SourceType:
  case DBLP, OPENALEX, CROSSREF, ORCID, DERIVED, RUNTIME
object SourceType:
  given ReadWriter[SourceType] = enumRW(values, _.toString)

/** Entity-linkage outcomes required by the spec's "Entity linkage policy". */
enum MatchOutcome:
  case ACCEPTED, REJECTED, UNRESOLVED, CONFLICT
object MatchOutcome:
  given ReadWriter[MatchOutcome] = enumRW(values, _.toString)

/** What kind of entity a match candidate or decision is about (evaluated separately). */
enum MatchEntityType:
  case RESEARCHER, WORK
object MatchEntityType:
  given ReadWriter[MatchEntityType] = enumRW(values, _.toString)

/** Evidence kinds. Deterministic graph facts and semantic context are kept apart (see
  * [[EvidenceType.isSemantic]]).
  *
  * Runtime kinds (Phase 9, appended; evidence-v1 shape unchanged): IDENTITY_RESOLUTION is a runtime
  * name-to-researcher decision (dominant-name-v1), not a pipeline MATCH_DECISION; RANKING_RECORD is
  * a stored top-N ranking entry, not a per-researcher METRIC_RECORD; TEMPORAL_CHANGE is a stored
  * window-to-window change; FACET_RECORD is a stored topic, venue, or institution facet of a
  * researcher or community. All four are deterministic, never semantic.
  */
enum EvidenceType:
  case WORK_RECORD, AUTHORSHIP, COAUTHOR_EDGE, GRAPH_PATH, METRIC_RECORD, COMPONENT_RECORD,
    COMMUNITY_RECORD, MATCH_DECISION, TEXT_CHUNK, PROFILE, IDENTITY_RESOLUTION, RANKING_RECORD,
    TEMPORAL_CHANGE, FACET_RECORD

  /** Vector-retrieved descriptive text; never proof of an edge or a metric. */
  def isSemantic: Boolean = this == TEXT_CHUNK || this == PROFILE
object EvidenceType:
  given ReadWriter[EvidenceType] = enumRW(values, _.toString)

/** The spec's measured quantities (P_r, C_r, T_r, A_r, M_r, W_r) plus component, D-10 bridge, and
  * k-core measures.
  */
enum CollaborationMetric:
  case PUBLICATION_COUNT, DISTINCT_COAUTHOR_COUNT, TOTAL_COAUTHOR_OCCURRENCES,
    AVERAGE_COAUTHORS_PER_PAPER, MAXIMUM_COAUTHORS_ON_ONE_PAPER, WEIGHTED_DEGREE, COMPONENT_SIZE,
    CROSS_COMMUNITY_DEGREE, COMMUNITIES_CONNECTED, PARTICIPATION_COEFFICIENT, CORENESS
object CollaborationMetric:
  given ReadWriter[CollaborationMetric] = enumRW(values, _.toString)

/** QueryPlan operations (spec Phase 9 QueryPlannerAgent). The rag-engineer's allowlist maps these
  * to storage ports.
  */
enum QueryOperation:
  case RESEARCHER_RANKING, MAXIMUM_TEAM_SIZE, DIRECT_COAUTHORS, SHORTEST_PATH, COMPONENT_LOOKUP,
    COMMUNITY_COMPARISON, BRIDGE_RANKING, TEMPORAL_COMPARISON, TOPIC_FILTER, VENUE_FILTER,
    INSTITUTION_FILTER, SEMANTIC_EXPLANATION
object QueryOperation:
  given ReadWriter[QueryOperation] = enumRW(values, _.toString)

/** Claim-verification outcomes. */
enum VerificationStatus:
  case VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING
object VerificationStatus:
  given ReadWriter[VerificationStatus] = enumRW(values, _.toString)

/** Node classes of the research graph model; the wire form is the class name. Community is a
  * per-window collaboration community (ID: [[StableIds.communityId]]), a Neptune vertex since Phase
  * 7 (modules/storage/STORAGE.md).
  */
enum NodeLabel:
  case Researcher, AuthorMention, Work, Venue, Institution, Topic, TextChunk, DatasetSnapshot,
    Community
object NodeLabel:
  given ReadWriter[NodeLabel] = enumRW(values, _.toString)

/** Edge classes of the research graph model. MEMBER_OF is directed Researcher -> Community; the
  * community ID encodes the window, so there is one edge per researcher per window.
  */
enum EdgeType:
  case AUTHORED, RESOLVES_TO, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC,
    DERIVED_FROM, SUPPORTED_BY, MEMBER_OF

  /** COAUTHORED_WITH is logically undirected, so its endpoints are stored in canonical order. */
  def undirected: Boolean = this == COAUTHORED_WITH
object EdgeType:
  given ReadWriter[EdgeType] = enumRW(values, _.toString)

/** State of an async query job (Phase 10 ruling 7). Writers move a job only along
  * [[JobState.canMoveTo]], with conditional writes, so a redelivered message never runs a job
  * twice. SUCCEEDED and FAILED are final.
  */
enum JobState:
  case QUEUED, RUNNING, SUCCEEDED, FAILED

  def isFinal: Boolean = this == SUCCEEDED || this == FAILED

  /** QUEUED -> RUNNING -> SUCCEEDED or FAILED; QUEUED -> FAILED ends a job that never ran (for
    * example a poison message). Nothing else, and no state moves to itself.
    */
  def canMoveTo(next: JobState): Boolean = (this, next) match
    case (QUEUED, RUNNING) | (QUEUED, FAILED)     => true
    case (RUNNING, SUCCEEDED) | (RUNNING, FAILED) => true
    case _                                        => false
object JobState:
  given ReadWriter[JobState] = enumRW(values, _.toString)

/** Typed error codes of the REST error model (spec "Error model", Phase 10 ruling 6), each with its
  * HTTP status and default retryable flag. Also the error of a FAILED job. The wire form is the
  * case name. Appending a code is compatible; renaming or removing one breaks stored FAILED jobs.
  * FORBIDDEN (403) is reserved and unused in the single-key deployment (ruling 5).
  */
enum ErrorCode(val httpStatus: Int, val retryable: Boolean):
  case MALFORMED_JSON extends ErrorCode(400, false)
  case INVALID_FIELD extends ErrorCode(400, false)
  case REQUEST_TOO_LARGE extends ErrorCode(400, false)
  case UNAUTHORIZED extends ErrorCode(401, false)
  case FORBIDDEN extends ErrorCode(403, false)
  case ROUTE_NOT_FOUND extends ErrorCode(404, false)
  case QUERY_NOT_FOUND extends ErrorCode(404, false)
  case JOB_NOT_FOUND extends ErrorCode(404, false)
  case NODE_NOT_FOUND extends ErrorCode(404, false)
  case EVIDENCE_NOT_FOUND extends ErrorCode(404, false)
  case IDEMPOTENCY_CONFLICT extends ErrorCode(409, false)
  case JOB_NOT_FINISHED extends ErrorCode(409, true) // poll again later
  case UNSUPPORTED_CONSTRAINT extends ErrorCode(422, false)
  case UNSUPPORTED_RETRIEVAL_MODE extends ErrorCode(422, false)
  case RATE_LIMITED extends ErrorCode(429, true)
  case DOWNSTREAM_FAILURE extends ErrorCode(502, true)
  case NOT_READY extends ErrorCode(503, true)
  case CIRCUIT_OPEN extends ErrorCode(503, true)
  case OVERLOADED extends ErrorCode(503, true)
  case DEADLINE_EXCEEDED extends ErrorCode(504, true)
  case DOWNSTREAM_TIMEOUT extends ErrorCode(504, true)
  case INTERNAL_ERROR extends ErrorCode(500, false)
object ErrorCode:
  given ReadWriter[ErrorCode] = enumRW(values, _.toString)
