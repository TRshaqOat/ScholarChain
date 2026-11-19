package scholarchain.rag.runtime

import scholarchain.core.CollaborationMetric
import upickle.default.{ReadWriter, readwriter}

/** Schema versions of the Phase 9 runtime records (modules/rag/RUNTIME.md, "Versioning"). A wire
  * change to a record bumps its version here and in RUNTIME.md. Prompt versions and model IDs are
  * configuration, not constants.
  */
object RuntimeSchemas:
  val QueryRequest = "rag-query-request-v1"
  val PlannerOutput = "planner-output-v1"
  val QueryPlan = "rag-query-plan-v1"
  val Resolution = "researcher-resolution-v1"
  val GraphFacts = "rag-graph-facts-v1"
  val SemanticResult = "rag-semantic-result-v1"
  val EvidenceBundle = "rag-evidence-bundle-v1"
  val SynthesisOutput = "synthesis-output-v3"

  /** Synthesis output versions the runtime accepts: v3 (Phase 10 work order 1:
    * rank.<k>.researcherId whenever a rank.<k> fact is asserted, enforced in code), v2 (ruling 17:
    * non-empty values for every claim but DESCRIPTIVE, also enforced in code for v1), and v1. The
    * prompt version named in configuration decides which one a run uses.
    */
  val SynthesisOutputVersions: Set[String] =
    Set("synthesis-output-v1", "synthesis-output-v2", SynthesisOutput)
  val Verification = "claim-verification-v1"
  val Answer = "rag-answer-v1"
  val Audit = "rag-audit-v3"
  val QuerySessionItem = "rag-query-session-v1"
  val EvidenceBundleItem = "rag-evidence-bundle-item-v1"

/** Wire form of every runtime enum: the case name as a JSON string; unknown names fail decoding. */
private def enumRW[E](values: Array[E]): ReadWriter[E] =
  val byName = values.map(v => v.toString -> v).toMap
  readwriter[String].bimap[E](
    _.toString,
    s => byName.getOrElse(s, throw new IllegalArgumentException(s"unknown enum value: $s"))
  )

/** The ten workflow stages (spec Phase 9); the names match the /v1/jobs `stage` field. */
enum Stage:
  case QUERY_PLANNING, RESEARCHER_RESOLUTION, COLLABORATION_METRICS, PATH_FINDING,
    COMMUNITY_ANALYSIS, SEMANTIC_CONTEXT, EVIDENCE_BUILDING, ANSWER_SYNTHESIS, CLAIM_VERIFICATION,
    AUDIT
object Stage:
  given ReadWriter[Stage] = enumRW(values)

enum StageOutcome:
  case OK, SKIPPED, ABSTAINED, FAILED
object StageOutcome:
  given ReadWriter[StageOutcome] = enumRW(values)

/** Who produced the plan: the validated model output, the rule planner (fake mode and tests), or
  * the rule planner after the model output failed validation twice.
  */
enum PlannerSource:
  case MODEL, RULES, FALLBACK_RULES
object PlannerSource:
  given ReadWriter[PlannerSource] = enumRW(values)

/** DEFAULT: no window in the question or request (all-years). NAMED: a stored window name. RANGE:
  * an explicit inclusive year range.
  */
enum WindowKind:
  case DEFAULT, NAMED, RANGE
object WindowKind:
  given ReadWriter[WindowKind] = enumRW(values)

/** What an operation is about: one named researcher, a pair of named researchers, or the corpus. */
enum OperationScope:
  case RESEARCHER, PAIR, CORPUS
object OperationScope:
  given ReadWriter[OperationScope] = enumRW(values)

/** Decisions of dominant-name-v1 (modules/spark/JOBS.md, Phase 7 ruling 30). */
enum ResolutionDecision:
  case RESOLVED_DOMINANT, AMBIGUOUS, UNKNOWN_RESEARCHER
object ResolutionDecision:
  given ReadWriter[ResolutionDecision] = enumRW(values)

/** Path statuses of Spark job 5, plus SEARCH_LIMIT: the bounded runtime search hit its frontier or
  * timeout limit before the depth limit, so neither a path nor its absence is shown.
  */
enum PathStatus:
  case FOUND, BEYOND_DEPTH, UNREACHABLE, UNKNOWN_RESEARCHER, SEARCH_LIMIT
object PathStatus:
  given ReadWriter[PathStatus] = enumRW(values)

enum FacetKind:
  case TOPIC, VENUE, INSTITUTION
object FacetKind:
  given ReadWriter[FacetKind] = enumRW(values)

/** Why a requested fact is not in the bundle (stated in the answer, never filled by the model). */
enum MissingReason:
  case NOT_IN_STORED_TOP_N, NO_STORED_RANKING, NO_METRIC_ROW, NOT_STORED, UNSUPPORTED_FILTER,
    NOT_WINDOWED, NO_STORED_COMPARISON, TRUNCATED, OMITTED_BY_BUDGET, NO_SEMANTIC_MATCH
object MissingReason:
  given ReadWriter[MissingReason] = enumRW(values)

/** The claim families the verifier checks (RUNTIME.md, "Verification rules"). */
enum ClaimKind:
  case IDENTITY, METRIC_VALUE, RANKING, COAUTHORSHIP, PATH, PATH_HOP, COMPONENT, COMMUNITY, BRIDGE,
    TEMPORAL_CHANGE, PUBLICATION, FACET, DESCRIPTIVE
object ClaimKind:
  given ReadWriter[ClaimKind] = enumRW(values)

enum CheckOutcome:
  case PASS, FAIL, MISSING, CONFLICT
object CheckOutcome:
  given ReadWriter[CheckOutcome] = enumRW(values)

/** What happened to a claim after verification. */
enum ClaimAction:
  case KEPT, LABELLED, REMOVED
object ClaimAction:
  given ReadWriter[ClaimAction] = enumRW(values)

/** Which model line verification left out (n7): the summary, or an uncertainty line. */
enum LinePart:
  case SUMMARY, UNCERTAINTY
object LinePart:
  given ReadWriter[LinePart] = enumRW(values)

enum AbstentionReason:
  case AMBIGUOUS_RESEARCHER, UNKNOWN_RESEARCHER, UNSUPPORTED_REQUEST, NO_DATA_IN_WINDOW,
    INSUFFICIENT_EVIDENCE, NO_VERIFIED_CLAIM, INVALID_PLAN, BUDGET_EXHAUSTED
object AbstentionReason:
  given ReadWriter[AbstentionReason] = enumRW(values)

/** Outcome of one model or embedding attempt. */
enum CallOutcome:
  case OK, INVALID_OUTPUT, TRANSIENT_ERROR, FAILED, SKIPPED_BUDGET
object CallOutcome:
  given ReadWriter[CallOutcome] = enumRW(values)

enum RuntimeMode:
  case FAKE, LIVE
object RuntimeMode:
  given ReadWriter[RuntimeMode] = enumRW(values)

/** Which retrievers a run uses (experiment E, Phase 14 ruling 31; RUNTIME.md section 24). FUSED is
  * the default and the behaviour before the mode existed; GRAPH_ONLY queries no vector index;
  * VECTOR_ONLY reads no graph fact beyond researcher resolution.
  */
enum RetrievalMode:
  case FUSED, GRAPH_ONLY, VECTOR_ONLY

  /** The CLI form: fused, graph-only, vector-only. */
  def flag: String = toString.toLowerCase.replace('_', '-')
object RetrievalMode:
  given ReadWriter[RetrievalMode] = enumRW(values)
  def fromFlag(s: String): Option[RetrievalMode] = values.find(_.flag == s)

/** ResearcherMetricCache attribute of each core metric (WINDOW# items; CHANGE# prefixes `from` or
  * `to` and capitalizes). Only the seven `ranked` metrics have stored RANKING# items.
  */
object MetricAttributes:
  import CollaborationMetric.*
  val attribute: Map[CollaborationMetric, String] = Map(
    PUBLICATION_COUNT -> "publicationCount",
    DISTINCT_COAUTHOR_COUNT -> "distinctCoauthorCount",
    TOTAL_COAUTHOR_OCCURRENCES -> "totalCoauthorOccurrences",
    AVERAGE_COAUTHORS_PER_PAPER -> "averageCoauthorsPerPaper",
    MAXIMUM_COAUTHORS_ON_ONE_PAPER -> "maximumCoauthorsOnOnePaper",
    WEIGHTED_DEGREE -> "weightedDegree",
    COMPONENT_SIZE -> "componentSize",
    CROSS_COMMUNITY_DEGREE -> "crossCommunityDegree",
    COMMUNITIES_CONNECTED -> "communitiesConnected",
    PARTICIPATION_COEFFICIENT -> "participationCoefficient",
    CORENESS -> "coreness"
  )
  val ranked: Vector[CollaborationMetric] = Vector(
    PUBLICATION_COUNT,
    DISTINCT_COAUTHOR_COUNT,
    MAXIMUM_COAUTHORS_ON_ONE_PAPER,
    CROSS_COMMUNITY_DEGREE,
    COMMUNITIES_CONNECTED,
    PARTICIPATION_COEFFICIENT,
    CORENESS
  )
  val bridge: Vector[CollaborationMetric] =
    Vector(CROSS_COMMUNITY_DEGREE, COMMUNITIES_CONNECTED, PARTICIPATION_COEFFICIENT)
