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
  */
enum EvidenceType:
  case WORK_RECORD, AUTHORSHIP, COAUTHOR_EDGE, GRAPH_PATH, METRIC_RECORD, COMPONENT_RECORD,
    COMMUNITY_RECORD, MATCH_DECISION, TEXT_CHUNK, PROFILE

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

/** Node classes of the research graph model; the wire form is the class name. */
enum NodeLabel:
  case Researcher, AuthorMention, Work, Venue, Institution, Topic, TextChunk, DatasetSnapshot
object NodeLabel:
  given ReadWriter[NodeLabel] = enumRW(values, _.toString)

/** Edge classes of the research graph model. */
enum EdgeType:
  case AUTHORED, RESOLVES_TO, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC,
    DERIVED_FROM, SUPPORTED_BY

  /** COAUTHORED_WITH is logically undirected, so its endpoints are stored in canonical order. */
  def undirected: Boolean = this == COAUTHORED_WITH
object EdgeType:
  given ReadWriter[EdgeType] = enumRW(values, _.toString)
