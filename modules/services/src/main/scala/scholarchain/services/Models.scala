package scholarchain.services

import scholarchain.core.{
  AnswerClaim,
  CollaborationMetric,
  ErrorCode,
  EvidenceItem,
  EvidenceType,
  JobState,
  Provenance,
  StableIds,
  VerificationStatus
}
import scholarchain.core.config.ScholarChainConfig
import scholarchain.rag.runtime.*
import scholarchain.storage.{GraphElement, JobRecord, Prop}
import upickle.default.{ReadWriter, read, write}

import scala.util.control.NonFatal

// REST models (uPickle, the one JSON library). Request models have defaults so a client may omit
// optional fields; response models have none, so every field is always present (null when absent).
// Records from core and rag are reused as they are (law 2: core codecs).

/** The spec error model (ruling 6) plus the request ID (law 3). */
final case class ErrorBody(
    error: ErrorCode,
    message: String,
    traceId: String,
    requestId: String,
    retryable: Boolean
) derives ReadWriter

// ---- POST /v1/query request ----

final case class ConstraintsBody(
    yearFrom: Option[Int] = None,
    yearTo: Option[Int] = None,
    topics: Option[Vector[String]] = None,
    institutions: Option[Vector[String]] = None,
    venues: Option[Vector[String]] = None
) derives ReadWriter

final case class AnalysisBody(
    rankingMetric: Option[String] = None,
    includeTeamSize: Option[Boolean] = None,
    includeBridgeMetrics: Option[Boolean] = None,
    includeCommunities: Option[Boolean] = None,
    includePaths: Option[Boolean] = None
) derives ReadWriter

final case class RetrievalBody(
    graphLimit: Option[Int] = None,
    vectorLimit: Option[Int] = None,
    maxPathDepth: Option[Int] = None
) derives ReadWriter

/** Accepted for the spec contract; the runtime answer always carries its evidence IDs. */
final case class OutputBody(
    includeEvidence: Boolean = true,
    includeScores: Boolean = true,
    includeUncertainty: Boolean = true
) derives ReadWriter

final case class QueryBody(
    question: String,
    constraints: Option[ConstraintsBody] = None,
    analysis: Option[AnalysisBody] = None,
    retrieval: Option[RetrievalBody] = None,
    output: Option[OutputBody] = None,
    execution: String = "sync"
) derives ReadWriter

// ---- POST /v1/query synchronous response ----

final case class AnswerView(
    summary: String,
    abstained: Boolean,
    abstention: Option[Abstention],
    uncertainty: Option[String],
    window: WindowSelection,
    resolutions: Vector[ResolutionSummary],
    verification: VerificationCounts,
    answerHash: String
) derives ReadWriter

final case class QueryLinks(explain: String, evidence: Vector[String]) derives ReadWriter

final case class SyncQueryResponse(
    queryId: String,
    mode: String,
    answer: AnswerView,
    claims: Vector[AnswerClaim],
    links: QueryLinks
) derives ReadWriter

object SyncQueryResponse:
  /** mode: "synchronous" for POST /v1/query, "asynchronous" for GET /v1/jobs/{jobId}/result. */
  def of(a: RuntimeAnswer, mode: String = "synchronous"): SyncQueryResponse =
    val r = a.response
    SyncQueryResponse(
      r.queryId,
      mode,
      AnswerView(
        r.summary,
        r.abstained,
        a.abstention,
        r.uncertainty,
        a.plan.window,
        a.resolutions.map(x =>
          ResolutionSummary(x.name, x.decision, x.researcherId, x.topShare, x.candidateCount)
        ),
        a.verification.counts,
        a.answerHash
      ),
      r.claims,
      QueryLinks(
        s"/v1/explain/${r.queryId}",
        r.claims.flatMap(_.evidenceIds).distinct.map(id => s"/v1/evidence/$id")
      )
    )

// ---- Async: POST /v1/query 202 and GET /v1/jobs/{jobId} ----

/** The job reference of an accepted async query (ruling 7). */
final case class AsyncQueryResponse(
    jobId: String,
    queryId: String,
    mode: String,
    state: JobState,
    statusUrl: String
) derives ReadWriter

object AsyncQueryResponse:
  def of(j: JobRecord): AsyncQueryResponse =
    AsyncQueryResponse(j.jobId, j.queryId, "asynchronous", j.state, s"/v1/jobs/${j.jobId}")

/** Why a job FAILED: the typed code (status and retryable) and the service's message. */
final case class JobErrorView(error: ErrorCode, message: String, retryable: Boolean)
    derives ReadWriter

/** GET /v1/jobs/{jobId}: state, stage, UTC timestamps, error, and resultUrl (only when SUCCEEDED).
  * The stored request is never returned.
  */
final case class JobResponse(
    jobId: String,
    queryId: String,
    state: JobState,
    stage: Option[String],
    createdAt: String,
    updatedAt: String,
    startedAt: Option[String],
    finishedAt: Option[String],
    error: Option[JobErrorView],
    statusUrl: String,
    resultUrl: Option[String]
) derives ReadWriter

object JobResponse:
  def of(j: JobRecord): JobResponse =
    JobResponse(
      j.jobId,
      j.queryId,
      j.state,
      j.stage,
      j.createdAt.toString,
      j.updatedAt.toString,
      j.startedAt.map(_.toString),
      j.finishedAt.map(_.toString),
      j.error.map(e => JobErrorView(e.code, e.message, e.code.retryable)),
      s"/v1/jobs/${j.jobId}",
      Option.when(j.state == JobState.SUCCEEDED)(s"/v1/jobs/${j.jobId}/result")
    )

// ---- GET /v1/explain/{queryId} ----

final case class RuleOutcome(rule: String, outcome: CheckOutcome) derives ReadWriter

/** A claim verification removed: IDs, kind, status, and failed rules; never its text or the check
  * details, which can quote model output (ruling 8).
  */
final case class RemovedClaimView(
    claimId: String,
    kind: ClaimKind,
    status: VerificationStatus,
    failedRules: Vector[RuleOutcome]
) derives ReadWriter

final case class DroppedLineView(part: LinePart, reasonCount: Int) derives ReadWriter

final case class ExplainCounters(
    llmAttempts: Int,
    llmRequests: Int,
    llmRetries: Int,
    llmInputTokens: Long,
    llmOutputTokens: Long,
    llmThrottleWaitMs: Long,
    embeddingCalls: Int,
    templateCalls: Int,
    templateLatencyMs: Long,
    stageLatencyMs: Long,
    cacheHits: Int
) derives ReadWriter

/** The observable derivation of one answer (spec "GET /v1/explain"): the plan, stages, template
  * IDs, indexes, filters (in the plan), prompt and model versions, evidence IDs, verification
  * outcomes, and counters. No question, prompt, retrieved, or model text. The runtime has no cache,
  * so cacheHits is always 0.
  */
final case class ExplainResponse(
    queryId: String,
    runId: String,
    createdAt: String,
    auditSchemaVersion: String,
    mode: RuntimeMode,
    llm: String,
    plan: QueryPlan,
    resolutions: Vector[ResolutionSummary],
    stages: Vector[StageTiming],
    templateIds: Vector[String],
    templateCalls: Vector[TemplateCall],
    vectorIndexes: Vector[String],
    embeddingModelVersion: Option[String],
    promptVersions: Map[String, String],
    modelIds: Map[String, String],
    graphEvidenceIds: Vector[String],
    semanticEvidenceIds: Vector[String],
    metricVersions: Vector[String],
    graphRunId: String,
    verification: VerificationCounts,
    removedClaims: Vector[RemovedClaimView],
    droppedLines: Vector[DroppedLineView],
    abstained: Boolean,
    abstentionReason: Option[AbstentionReason],
    llmCalls: Vector[LlmCall],
    embeddingCalls: Vector[EmbeddingCall],
    budget: BundleBudget,
    counters: ExplainCounters,
    answerHash: String
) derives ReadWriter

object ExplainResponse:
  def of(audit: AuditRecord): ExplainResponse =
    val a = RuntimeApi.withoutModelText(audit)
    ExplainResponse(
      a.queryId,
      a.runId,
      a.createdAt,
      a.schemaVersion,
      a.mode,
      if RuntimeApi.llmStubbed(a) then "STUBBED" else "LIVE",
      a.plan,
      a.resolutions,
      a.stages,
      a.templateCalls.map(_.templateId).distinct.sorted,
      a.templateCalls,
      a.vectorIndexes,
      a.embeddingModelVersion,
      a.promptVersions,
      a.modelIds,
      a.graphEvidenceIds,
      a.semanticEvidenceIds,
      a.metricVersions,
      a.graphRunId,
      a.verification,
      a.removedClaims.map(c =>
        RemovedClaimView(
          c.claimId,
          c.kind,
          c.status,
          c.failedChecks.map(x => RuleOutcome(x.rule, x.outcome))
        )
      ),
      a.droppedLines.map(l => DroppedLineView(l.part, l.reasons.size)),
      a.abstained,
      a.abstentionReason,
      a.llmCalls,
      a.embeddingCalls,
      a.budget,
      ExplainCounters(
        a.llmCalls.size,
        a.llmCalls.map(_.requests).sum,
        a.llmCalls.count(_.attempt > 1),
        a.llmCalls.flatMap(_.inputTokens).map(_.toLong).sum,
        a.llmCalls.flatMap(_.outputTokens).map(_.toLong).sum,
        a.llmCalls.map(_.throttleWaitMs).sum + a.embeddingCalls.map(_.throttleWaitMs).sum,
        a.embeddingCalls.size,
        a.templateCalls.size,
        a.templateCalls.map(_.latencyMs).sum,
        a.stages.map(_.latencyMs).sum,
        0
      ),
      a.answerHash
    )

// ---- GET /v1/evidence/{evidenceId} ----

final case class EvidenceResponse(
    evidenceId: String,
    evidenceType: EvidenceType,
    subjectIds: Vector[String],
    workId: Option[String],
    sourceUri: String,
    chunkId: Option[String],
    graphPath: Option[Vector[String]],
    score: Option[Double],
    text: Option[String],
    textTruncated: Boolean,
    textHash: Option[String],
    facts: Map[String, String],
    provenance: Provenance
) derives ReadWriter

object EvidenceResponse:
  /** The item with its text cut to maxChars (never inside a surrogate pair); textHash is still the
    * hash of the full text.
    */
  def of(e: EvidenceItem, maxChars: Int): EvidenceResponse =
    val cut = e.text.filter(_.length > maxChars).map { t =>
      val n = if Character.isHighSurrogate(t.charAt(maxChars - 1)) then maxChars - 1 else maxChars
      t.take(n)
    }
    EvidenceResponse(
      e.evidenceId,
      e.evidenceType,
      e.subjectIds,
      e.workId,
      e.sourceUri,
      e.chunkId,
      e.graphPath,
      e.score,
      cut.orElse(e.text),
      cut.isDefined,
      e.textHash,
      e.facts,
      e.provenance
    )

// ---- Graph routes ----

final case class NodeView(nodeId: String, label: String, properties: Map[String, ujson.Value])
    derives ReadWriter
final case class EdgeView(
    edgeId: String,
    edgeType: String,
    fromId: String,
    toId: String,
    properties: Map[String, ujson.Value]
) derives ReadWriter
final case class NodeResponse(node: NodeView) derives ReadWriter
final case class NeighborsResponse(
    nodeId: String,
    direction: String,
    depth: Int,
    limit: Int,
    edgeTypes: Vector[String],
    nodes: Vector[NodeView],
    edges: Vector[EdgeView],
    truncated: Boolean,
    nextCursor: Option[String]
) derives ReadWriter

object GraphViews:
  private def props(m: Map[String, Prop]): Map[String, ujson.Value] = m.map { (k, v) =>
    k -> (v match
      case s: String  => ujson.Str(s)
      case b: Boolean => ujson.Bool(b)
      case i: Int     => ujson.Num(i.toDouble)
      case l: Long    => ujson.Num(l.toDouble)
      case d: Double  => ujson.Num(d))
  }
  def node(e: GraphElement): NodeView = NodeView(e.id, e.label, props(e.props))
  def edge(e: GraphElement): EdgeView = EdgeView(e.id, e.label, e.from, e.to, props(e.props))

// ---- Health ----

final case class Status(status: String) derives ReadWriter

/** Validation and translation of the POST /v1/query body (ruling 6): 400 for malformed or invalid
  * fields, 422 for valid JSON asking for something this deployment does not support.
  */
object QueryValidation:
  // Field -> JSON type ("string", "int", "bool", "strings"). uPickle is lenient (it reads a number
  // into a String, and null into a String), so every type is checked on the JSON value first.
  private val Top = Map("question" -> "string", "execution" -> "string")
  private val Nested: Map[String, Map[String, String]] = Map(
    "constraints" -> Map(
      "yearFrom" -> "int",
      "yearTo" -> "int",
      "topics" -> "strings",
      "institutions" -> "strings",
      "venues" -> "strings"
    ),
    "analysis" -> (Map("rankingMetric" -> "string") ++ Seq(
      "includeTeamSize",
      "includeBridgeMetrics",
      "includeCommunities",
      "includePaths"
    ).map(_ -> "bool")),
    "retrieval" -> Seq("graphLimit", "vectorLimit", "maxPathDepth").map(_ -> "int").toMap,
    "output" -> Seq("includeEvidence", "includeScores", "includeUncertainty").map(_ -> "bool").toMap
  )
  // Unknown fields: a constraint or analysis field is an unsupported constraint, a retrieval field
  // an unsupported retrieval mode (422); anywhere else an invalid field (400).
  private val UnknownField: Map[String, String => ApiError] = Map(
    "constraints" -> unsupported,
    "analysis" -> unsupported,
    "retrieval" -> (m => ApiError(ErrorCode.UNSUPPORTED_RETRIEVAL_MODE, m)),
    "output" -> invalid
  )
  // Only the optional structured fields may be null; question, execution, and output flags not.
  private val Nullable = Set("constraints", "analysis", "retrieval")
  val Executions = Set("sync", "async")

  private def invalid(m: String) = ApiError(ErrorCode.INVALID_FIELD, m)
  private def unsupported(m: String) = ApiError(ErrorCode.UNSUPPORTED_CONSTRAINT, m)

  private def typed(v: ujson.Value, t: String): Boolean = (v, t) match
    case (ujson.Str(_), "string") | (ujson.Bool(_), "bool") => true
    case (ujson.Num(n), "int")                              => n.isWhole && n.abs <= Int.MaxValue
    case (ujson.Arr(xs), "strings")                         => xs.forall(_.strOpt.isDefined)
    case _                                                  => false

  private def fields(o: ujson.Obj, where: String, types: Map[String, String], nullable: Boolean) =
    o.value.foreach { (k, v) =>
      if !(v.isNull && nullable) && !typed(v, types(k)) then
        throw invalid(s"$where$k must be ${types(k).replace("strings", "an array of strings")}")
    }

  /** The validated body and its normalized question. Throws ApiError. */
  def parse(json: ujson.Value, cfg: ScholarChainConfig): (QueryBody, String) =
    val o = json.objOpt.getOrElse(throw invalid("the body must be a JSON object"))
    val allowed = Top.keySet ++ Nested.keySet
    if !o.value.keySet.subsetOf(allowed) then
      throw invalid(s"unsupported field; allowed: ${allowed.toVector.sorted.mkString(", ")}")
    if !o.value.contains("question") then throw invalid("question is required")
    fields(ujson.Obj.from(o.value.filter((k, _) => Top.contains(k))), "", Top, false)
    Nested.foreach { (name, types) =>
      o.value.get(name).foreach {
        case ujson.Null if Nullable(name) => ()
        case n: ujson.Obj                 =>
          if !n.value.keySet.subsetOf(types.keySet) then
            throw UnknownField(name)(
              s"$name holds an unsupported field; allowed: ${types.keys.toVector.sorted.mkString(", ")}"
            )
          fields(n, s"$name.", types, Nullable(name))
        case _ => throw invalid(s"$name must be an object")
      }
    }
    val body =
      try read[QueryBody](json)
      catch
        case NonFatal(_) =>
          throw invalid("a field has the wrong type, or the required field question is missing")
    val question = Question
      .normalize(body.question, cfg.runtime.question.maxChars)
      .fold(m => throw invalid(m), identity)
    if !Executions(body.execution) then throw invalid("execution must be sync or async")
    body.constraints.foreach { c =>
      if c.yearFrom.zip(c.yearTo).exists(_ > _) then throw invalid("yearFrom is after yearTo")
      val p = cfg.runtime.planner
      Seq("topics" -> c.topics, "institutions" -> c.institutions, "venues" -> c.venues).foreach {
        (name, terms) =>
          terms.foreach { ts =>
            if ts.exists(_.trim.isEmpty) then throw invalid(s"$name holds a blank term")
            if ts.size > p.maxFilterTerms || ts.exists(_.length > p.maxTermChars) then
              throw unsupported(
                s"$name supports at most ${p.maxFilterTerms} terms of at most ${p.maxTermChars} characters"
              )
          }
      }
    }
    body.analysis.flatMap(_.rankingMetric).foreach { m =>
      val ranked = MetricAttributes.ranked.map(_.toString)
      if !ranked.contains(m) then
        throw unsupported(
          s"rankingMetric must be one of the stored rankings: ${ranked.mkString(", ")}"
        )
    }
    body.retrieval.foreach { r =>
      val l = cfg.retrieval
      Seq(
        ("graphLimit", r.graphLimit, l.graphLimit),
        ("vectorLimit", r.vectorLimit, l.vectorLimit),
        ("maxPathDepth", r.maxPathDepth, l.maxPathDepth)
      ).foreach { (name, v, max) =>
        if v.exists(_ < 1) then throw invalid(s"retrieval.$name must be at least 1")
        if v.exists(_ > max) then
          throw ApiError(
            ErrorCode.UNSUPPORTED_RETRIEVAL_MODE,
            s"retrieval.$name above $max is not supported in this deployment"
          )
      }
    }
    (body, question)

  /** sha256:<hex> of the canonical body: the model written back, so field order, whitespace, and
    * explicit nulls versus omitted fields do not matter.
    */
  def requestHash(body: QueryBody): String = StableIds.textHash(write(body))

  /** The runtime request (structured fields keep overriding the model, Phase 9 ruling 4). */
  def toRuntime(body: QueryBody, queryId: String): QueryRequest =
    QueryRequest(
      RuntimeSchemas.QueryRequest,
      queryId,
      None,
      body.question,
      body.constraints.map(c =>
        RequestConstraints(c.yearFrom, c.yearTo, c.topics, c.institutions, c.venues)
      ),
      body.analysis.map(a =>
        RequestAnalysis(
          a.rankingMetric.map(CollaborationMetric.valueOf),
          a.includeTeamSize,
          a.includeBridgeMetrics,
          a.includeCommunities,
          a.includePaths
        )
      ),
      body.retrieval.map(r => RequestRetrieval(r.graphLimit, r.vectorLimit, r.maxPathDepth)),
      body.output.map(o => RequestOutput(o.includeEvidence, o.includeScores, o.includeUncertainty))
    )
