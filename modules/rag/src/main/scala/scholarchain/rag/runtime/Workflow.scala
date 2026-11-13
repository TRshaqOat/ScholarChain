package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{
  AnswerResponse,
  IdFormat,
  Provenance,
  SchemaVersions,
  SourceType,
  StableIds
}
import scholarchain.storage.{
  DynamoContract,
  Embedder,
  GraphReadPort,
  KeyValueReadPort,
  ObjectLoadPort,
  ObjectReadPort,
  RuntimeRecordPort,
  TableName,
  Throttle,
  VectorReadPort,
  WindowSummaryReadPort
}
import upickle.default.write

import java.time.Instant
import scala.util.control.NonFatal

/** The corpus window summaries served (Phase 10 work order 9, follow-up F2): the store object key
  * (curated/window-summary/snapshot=<d>/run=<load run>/part-00000.jsonl, the evidence source
  * pointer and a runtime run ID input) and the validated read port opened on it.
  */
final case class WindowSummarySource(key: String, port: WindowSummaryReadPort)

/** The read and write ports one question uses. objects holds coauthor-evidence objects and semantic
  * text; auditReader and auditWriter are the runtime object store (logs/, reports/, runs/).
  * windowSummaries None: corpus-level component, community, and window questions are stated as
  * NOT_STORED and abstain (RUNTIME.md section 3).
  */
final case class RuntimePorts(
    graph: GraphReadPort,
    kv: KeyValueReadPort,
    objects: ObjectReadPort,
    vectors: VectorReadPort,
    embedder: Embedder,
    auditReader: ObjectReadPort,
    auditWriter: ObjectLoadPort,
    records: RuntimeRecordPort,
    windowSummaries: Option[WindowSummarySource] = None
)

/** Everything fixed for a runtime run. llm None is fake mode: the rule planner and the template
  * synthesizer, no model call.
  */
final case class RuntimeSetup(
    cfg: ScholarChainConfig,
    prompts: PromptSet,
    mode: RuntimeMode,
    llm: Option[LlmClient],
    llmThrottle: Throttle,
    embedThrottle: Option[Throttle],
    embeddingModelVersion: String,
    graphRunId: String,
    snapshot: String,
    runId: String,
    nowMs: () => Long,
    now: () => Instant
)

/** A stage that failed (a store error after the storage retry policy, or a refused write). It is
  * never turned into an answer. deadlinePassed: the question deadline passed before any plan.
  */
final case class RuntimeFailure(stage: Stage, message: String, deadlinePassed: Boolean = false)

/** deadlinePassed: the question deadline had passed when the answer was finished (an abstention at
  * the deadline, or a late answer); a service maps it to its deadline status (Phase 10 ruling 7).
  */
final case class RuntimeResult(
    answer: RuntimeAnswer,
    audit: AuditRecord,
    bundle: Option[EvidenceBundle],
    written: Vector[scholarchain.storage.ManifestEntry],
    deadlinePassed: Boolean = false
)

object Workflow:
  val CodeVersion = "rag-runtime-v3"

  /** The runtime run ID (RUNTIME.md section 14): timestamp-free, over the served store runs, the
    * vector store content, the embedding model version, prompt versions and hashes, model IDs, and
    * the mode.
    */
  def runIdFor(
      cfg: ScholarChainConfig,
      inputs: Seq[String],
      prompts: PromptSet,
      mode: RuntimeMode,
      verifier: String = Verifier.Version
  ): String =
    StableIds.runId(
      "rag-runtime",
      CodeVersion,
      cfg.configVersion,
      inputs ++ prompts.versions.toSeq.sorted.map((k, v) => s"$k=$v") ++
        Seq(
          s"planner-model=${cfg.models.plannerModel}",
          s"synthesis-model=${cfg.models.synthesisModel}",
          s"mode=$mode",
          s"verifier=$verifier"
        ),
      1
    )

  /** qry- ID: 1 plus the audits already written for this run, question, and request. */
  def queryIdFor(
      question: String,
      request: QueryRequest,
      setup: RuntimeSetup,
      audits: ObjectReadPort
  ): String =
    val qh = StableIds.textHash(question)
    val rh = StableIds.textHash(write(request.copy(queryId = "", question = "")))
    Iterator
      .from(1)
      .map(StableIds.queryId(setup.runId, qh, rh, _))
      .find(q => audits.get(Audit.auditKey(setup.snapshot, setup.runId, q)).isEmpty)
      .get

  /** Answers one request. deadlineMs is the question's time budget from now; None takes
    * `runtime.question.deadline-ms` (the CLI).
    */
  def answer(
      request: QueryRequest,
      ports: RuntimePorts,
      setup: RuntimeSetup,
      deadlineMs: Option[Long] = None
  ): Either[RuntimeFailure, RuntimeResult] =
    val cfg = setup.cfg
    val started = setup.nowMs()
    val budgetMs = deadlineMs.getOrElse(cfg.runtime.question.deadlineMs.toLong)
    val deadline = started + budgetMs
    val createdAt = setup.now()
    Question
      .normalize(request.question, cfg.runtime.question.maxChars)
      .left
      .map(RuntimeFailure(Stage.QUERY_PLANNING, _))
      .flatMap { question =>
        val queryId =
          if request.queryId.nonEmpty then request.queryId
          else queryIdFor(question, request, setup, ports.auditReader)
        if !IdFormat.isQueryId(queryId) then
          Left(RuntimeFailure(Stage.QUERY_PLANNING, "queryId is not qry-<32 hex>"))
        else new Run(request, question, queryId, ports, setup, deadline, budgetMs, createdAt).go()
      }

  private final class Run(
      request: QueryRequest,
      question: String,
      queryId: String,
      ports: RuntimePorts,
      setup: RuntimeSetup,
      deadline: Long,
      budgetMs: Long,
      createdAt: Instant
  ):
    private val cfg = setup.cfg
    private var stages = Vector.empty[StageTiming]
    private var llmCalls = Vector.empty[LlmCall]
    private val log = CallLog(setup.nowMs, deadline)
    // What the deadline handler needs to abstain (review 1 m7).
    private var planSoFar: Option[QueryPlan] = None
    private var resolutionsSoFar = Vector.empty[ResearcherResolution]
    private val session = setup.llm.map(c =>
      LlmSession(
        c,
        setup.llmThrottle,
        TokenBudget(
          cfg.runtime.budget.maxInputTokensPerQuestion,
          cfg.runtime.budget.maxOutputTokensPerQuestion,
          cfg.runtime.budget.charsPerToken
        ),
        cfg.runtime.llm.maxAttemptsPerCall,
        setup.nowMs,
        deadline
      )
    )

    /** Runs one stage after checking the question deadline (review 1 m7); a deadline that passes
      * before or inside it (a store read, an embedding, a vector query) ends it ABSTAINED. Planning
      * is not checked here: it reads no store, and each model attempt checks the deadline itself.
      */
    private def timed[A](stage: Stage)(body: => A)(outcome: A => StageOutcome): A =
      val t0 = setup.nowMs()
      try
        if stage != Stage.QUERY_PLANNING then log.checkDeadline(stage.toString)
        val a = body
        stages :+= StageTiming(stage, outcome(a), setup.nowMs() - t0)
        a
      catch
        case _: DeadlineExceeded =>
          stages :+= StageTiming(stage, StageOutcome.ABSTAINED, setup.nowMs() - t0)
          throw DeadlinePassed(stage)
        case NonFatal(e) =>
          stages :+= StageTiming(stage, StageOutcome.FAILED, setup.nowMs() - t0)
          throw StageFailed(stage, e)

    private final case class StageFailed(stage: Stage, cause: Throwable)
        extends RuntimeException(cause)
    private final case class DeadlinePassed(stage: Stage) extends RuntimeException

    private def skip(from: Stage): Unit =
      Stage.values
        .filter(s => s.ordinal >= from.ordinal && s != Stage.AUDIT && !stages.exists(_.stage == s))
        .foreach(s => stages :+= StageTiming(s, StageOutcome.SKIPPED, 0L))

    private def request(
        prompt: PromptTemplate,
        values: Map[String, String],
        model: String,
        maxTokens: Int
    )(
        attempt: Int,
        failures: Vector[String]
    ): Either[String, LlmRequest] =
      for
        system <- prompt.render("system", values)
        user <- prompt.render("user", values)
        retry <-
          if attempt == 1 then Right("")
          else
            prompt.render(
              "retry",
              // Rule names with schema paths only (JsonSchema never echoes model text).
              values + ("failed_rules" -> failures.distinct
                .take(10)
                .map(f => s"- $f")
                .mkString("\n"))
            )
      yield LlmRequest(
        model,
        system,
        if retry.isEmpty then user else s"$user\n\n$retry",
        maxTokens,
        cfg.runtime.llm.temperature
      )

    private def plan(): Either[(Abstention, QueryPlan), QueryPlan] =
      val p = setup.prompts.planner
      val model = session.map { s =>
        val values = Planner.promptValues(question, p, cfg)
        val out = LlmCaller.call(
          s,
          Stage.QUERY_PLANNING,
          p,
          request(p, values, cfg.models.plannerModel, cfg.runtime.planner.maxOutputTokens),
          Planner.parseModel(_, p.schema, question, cfg)
        )
        llmCalls ++= out.calls
        out
      }
      val (output, source, rejected) = model match
        case Some(o) if o.value.isDefined => (o.value.get, PlannerSource.MODEL, Vector.empty)
        case Some(o)                      =>
          (
            RulePlanner.plan(question, cfg.graph.windows, cfg.runtime.planner.maxResearcherNames),
            PlannerSource.FALLBACK_RULES,
            o.failures.map(_.takeWhile(_ != ':')).distinct
          )
        case None =>
          (
            RulePlanner.plan(question, cfg.graph.windows, cfg.runtime.planner.maxResearcherNames),
            PlannerSource.RULES,
            Vector.empty
          )
      val problems = if source == PlannerSource.MODEL then Vector.empty
      else Planner.problems(output, question, cfg)
      val built = Planner.build(
        output,
        request,
        question,
        source,
        cfg,
        queryId,
        setup.snapshot,
        setup.runId,
        Option.when(source == PlannerSource.MODEL || model.isDefined)(p.version),
        Option.when(source == PlannerSource.MODEL)(cfg.models.plannerModel),
        rejected ++ problems
      )
      if problems.nonEmpty then
        val pl = built.fold(_._2, identity)
        Left(
          (
            Abstention(
              AbstentionReason.INVALID_PLAN,
              "The question could not be turned into a supported plan: " + problems
                .map(_.takeWhile(_ != ':'))
                .mkString("; ") + "."
            ),
            pl
          )
        )
      else built

    private def notes(
        plan: QueryPlan,
        resolutions: Vector[ResearcherResolution],
        missing: Vector[MissingEvidence]
    ): Vector[String] =
      (plan.window.note.toVector ++ resolutions.flatMap(Resolver.linkLine) ++ missing.map(
        _.detail
      )).distinct

    private def finish(
        plan: QueryPlan,
        resolutions: Vector[ResearcherResolution],
        bundle: Option[EvidenceBundle],
        semantic: SemanticStage.Output,
        verification: VerificationReport,
        claims: Vector[scholarchain.core.AnswerClaim],
        summary: String,
        uncertainty: Vector[String],
        abstention: Option[Abstention],
        removed: Vector[RemovedClaim] = Vector.empty,
        dropped: Vector[DroppedLine] = Vector.empty
    ): Either[RuntimeFailure, RuntimeResult] =
      val prov = Provenance(
        SchemaVersions.Provenance,
        SourceType.RUNTIME,
        queryId,
        setup.snapshot,
        setup.runId,
        Some(cfg.retrieval.fusionPolicyVersion),
        plan.modelId
      )
      val response = AnswerResponse(
        SchemaVersions.AnswerResponse,
        queryId,
        abstention.fold(summary)(a => a.detail),
        claims,
        abstention.isDefined,
        Option.when(uncertainty.nonEmpty)(uncertainty.mkString(" ")),
        prov
      )
      val answerHash = StableIds.textHash(write(response))
      val answer = RuntimeAnswer(
        RuntimeSchemas.Answer,
        response,
        plan,
        resolutions,
        verification,
        abstention,
        answerHash
      )
      val t0 = setup.nowMs()
      def audit(timings: Vector[StageTiming]) = AuditRecord(
        RuntimeSchemas.Audit,
        queryId,
        request.sessionId,
        setup.runId,
        createdAt.toString,
        setup.mode,
        StableIds.textHash(question),
        question.length,
        plan,
        resolutions.map(r =>
          ResolutionSummary(r.name, r.decision, r.researcherId, r.topShare, r.candidateCount)
        ),
        bundle.toVector.flatMap(_.graphEvidence.map(_.evidenceId)),
        bundle.toVector.flatMap(_.semanticEvidence.map(_.evidenceId)),
        bundle.toVector.flatMap(_.graphEvidence.flatMap(_.facts.get("metricVersion"))).distinct,
        setup.graphRunId,
        semantic.results.map(_.index),
        semantic.call.map(_.modelVersion),
        setup.prompts.versions,
        Map(
          "planner" -> cfg.models.plannerModel,
          "synthesis" -> cfg.models.synthesisModel,
          "embedding" -> setup.embeddingModelVersion
        ),
        llmCalls,
        semantic.call.toVector,
        log.all,
        timings,
        bundle.fold(BundleBudget(0, 0, 0, 0, 0))(_.budget),
        verification.counts,
        removed,
        dropped,
        abstention.isDefined,
        abstention.map(_.reason),
        answerHash,
        prov
      )
      // The audit's own stage time is the time to build it; the write happens after.
      val record = audit(stages :+ StageTiming(Stage.AUDIT, StageOutcome.OK, setup.nowMs() - t0))
      Audit
        .persist(
          answer,
          record,
          bundle,
          createdAt,
          cfg.runtime.audit.ttlDays,
          setup.snapshot,
          ports.auditWriter,
          ports.records
        )
        .left
        .map(RuntimeFailure(Stage.AUDIT, _))
        .map(RuntimeResult(answer, record, bundle, _, setup.nowMs() > deadline))

    private val emptyReport = VerificationReport(
      RuntimeSchemas.Verification,
      queryId,
      Vector.empty,
      VerificationCounts(0, 0, 0, 0)
    )
    private val noSemantic = SemanticStage.Output(Vector.empty, None, Vector.empty)

    private def abstain(
        plan: QueryPlan,
        rs: Vector[ResearcherResolution],
        a: Abstention,
        from: Stage,
        missing: Vector[MissingEvidence] = Vector.empty
    ) =
      skip(from)
      finish(
        plan,
        rs,
        None,
        noSemantic,
        emptyReport,
        Vector.empty,
        a.detail,
        notes(plan, rs, missing),
        Some(a)
      )

    def go(): Either[RuntimeFailure, RuntimeResult] =
      try run()
      catch
        case StageFailed(stage, e) =>
          Left(RuntimeFailure(stage, s"${e.getClass.getSimpleName}: ${e.getMessage}"))
        case DeadlinePassed(stage) =>
          planSoFar match
            case None    => Left(RuntimeFailure(stage, "the question deadline passed", true))
            case Some(p) =>
              abstain(
                p,
                resolutionsSoFar,
                Abstention(
                  AbstentionReason.BUDGET_EXHAUSTED,
                  s"The question deadline of $budgetMs ms passed during " +
                    s"$stage, so no claim is made."
                ),
                stage
              )

    private def run(): Either[RuntimeFailure, RuntimeResult] =
      timed(Stage.QUERY_PLANNING)(plan())(r =>
        if r.isLeft then StageOutcome.ABSTAINED else StageOutcome.OK
      ) match
        case Left((a, p)) => abstain(p, Vector.empty, a, Stage.RESEARCHER_RESOLUTION)
        case Right(p)     =>
          planSoFar = Some(p)
          val rs = timed(Stage.RESEARCHER_RESOLUTION)(
            p.researcherNames.map(n => Resolver.resolve(n.name, ports.kv, ports.graph, cfg, log))
          )(r =>
            if r.forall(_.decision == ResolutionDecision.RESOLVED_DOMINANT) then StageOutcome.OK
            else StageOutcome.ABSTAINED
          )
          resolutionsSoFar = rs
          val ambiguous = rs.exists(_.decision == ResolutionDecision.AMBIGUOUS)
          val unknown = rs.exists(_.decision == ResolutionDecision.UNKNOWN_RESEARCHER)
          if ambiguous || unknown then
            val reason = if ambiguous then AbstentionReason.AMBIGUOUS_RESEARCHER
            else AbstentionReason.UNKNOWN_RESEARCHER
            abstain(
              p,
              rs,
              Abstention(reason, Resolver.abstentionText(rs)),
              Stage.COLLABORATION_METRICS
            )
          else answerResolved(p, rs)

    /** Whether a name is a corpus researcher name: one bounded EntityLinkCache lookup per distinct
      * name (ruling 20), through the logged read port.
      */
    private val corpusNames = collection.mutable.HashMap.empty[String, Boolean]
    private def corpusName(name: String): Boolean =
      corpusNames.getOrElseUpdate(
        name,
        DynamoContract
          .nameKey(name)
          .exists(k =>
            log("kv.entity-link")(ports.kv.query(TableName.EntityLinkCache, k, "", 1))(
              _.size
            ).nonEmpty
          )
      )

    private def answerResolved(
        p: QueryPlan,
        rs: Vector[ResearcherResolution]
    ): Either[RuntimeFailure, RuntimeResult] =
      val resolved = rs.zipWithIndex.flatMap((r, i) => r.researcherId.map(Resolved(i, _, r.name)))
      val g = timed(Stage.COLLABORATION_METRICS)(
        GraphStages(
          p,
          resolved,
          ports.graph,
          ports.kv,
          ports.objects,
          cfg,
          log,
          setup.graphRunId,
          ports.windowSummaries
        ).run(queryId)
      )(_ => StageOutcome.OK)
      // Stages 3 to 5 run as one pass over the plan; each is recorded with its own time, or SKIPPED
      // when the plan gave it nothing to do (review 1 m1).
      stages = stages.dropRight(1) ++
        Vector(Stage.COLLABORATION_METRICS, Stage.PATH_FINDING, Stage.COMMUNITY_ANALYSIS).map(s =>
          g.stageMs
            .get(s)
            .fold(StageTiming(s, StageOutcome.SKIPPED, 0L))(StageTiming(s, StageOutcome.OK, _))
        )
      val f = g.facts
      val empty =
        f.metrics.isEmpty && f.rankings.isEmpty && f.changes.isEmpty && f.coauthors.isEmpty && f.paths.isEmpty &&
          f.communities.isEmpty && f.facets.isEmpty && f.works.isEmpty && g.corpusEmpty
      val onlyUnsupported =
        p.operations.nonEmpty && p.operations.forall(o => g.unsupported.contains(o.operation)) &&
          (p.metrics.isEmpty || f.metrics.isEmpty)
      if onlyUnsupported then
        abstain(
          p,
          rs,
          Abstention(
            AbstentionReason.UNSUPPORTED_REQUEST,
            "This request is not supported by the stored data: " + f.missing
              .map(_.detail)
              .distinct
              .mkString(" ")
          ),
          Stage.SEMANTIC_CONTEXT,
          f.missing
        )
      else if empty then
        abstain(
          p,
          rs,
          Abstention(
            AbstentionReason.INSUFFICIENT_EVIDENCE,
            "The graph and metric stores hold no evidence for this question. " + f.missing
              .map(_.detail)
              .distinct
              .mkString(" ")
          ),
          Stage.SEMANTIC_CONTEXT,
          f.missing
        )
      else
        val sem = timed(Stage.SEMANTIC_CONTEXT)(
          SemanticStage.run(
            question,
            p,
            resolved,
            f,
            ports.embedder,
            setup.embedThrottle,
            ports.vectors,
            ports.objects,
            setup.embeddingModelVersion,
            cfg,
            setup.nowMs,
            log.checkDeadline
          )
        )(o => if o.call.isEmpty then StageOutcome.SKIPPED else StageOutcome.OK)
        val bundle = timed(Stage.EVIDENCE_BUILDING)(
          EvidenceBuilder.build(p, rs, g, sem.results, sem.missing, cfg, setup.runId)
        )(_ => StageOutcome.OK)
        // The limitations the corpus rows carry (node basis, convergence, window lengths) are stated.
        val lines = (notes(p, rs, bundle.missing) ++ g.limitations).distinct
        val synthesis = timed(Stage.ANSWER_SYNTHESIS)(session match
          case None    => Some(TemplateSynthesizer.synthesize(p, bundle, resolved))
          case Some(s) =>
            val prompt = setup.prompts.synthesis
            val values = Synthesis.promptValues(question, bundle, lines, prompt, cfg)
            val out = LlmCaller.call(
              s,
              Stage.ANSWER_SYNTHESIS,
              prompt,
              request(
                prompt,
                values,
                cfg.models.synthesisModel,
                cfg.runtime.synthesis.maxOutputTokens
              ),
              Synthesis.parseModel(_, prompt.schema, cfg),
              Some(Synthesis.cutOffRule(cfg))
            )
            llmCalls ++= out.calls
            out.value)(o => if o.isDefined then StageOutcome.OK else StageOutcome.ABSTAINED)
        synthesis match
          case None =>
            stages :+= StageTiming(Stage.CLAIM_VERIFICATION, StageOutcome.SKIPPED, 0L)
            finish(
              p,
              rs,
              Some(bundle),
              sem,
              emptyReport,
              Vector.empty,
              "",
              lines,
              Some(
                Abstention(
                  AbstentionReason.BUDGET_EXHAUSTED,
                  "No drafted answer passed validation within the model call and token budget; no claim is made."
                )
              )
            )
          case Some(out) =>
            val prov = Provenance(
              SchemaVersions.Provenance,
              SourceType.RUNTIME,
              queryId,
              setup.snapshot,
              setup.runId,
              None,
              p.modelId
            )
            val v = timed(Stage.CLAIM_VERIFICATION)(
              Verifier.verify(
                out,
                Verifier.Context(bundle, rs, cfg, corpusName, lines),
                queryId,
                prov
              )
            )(r => if r.report.counts.verified > 0 then StageOutcome.OK else StageOutcome.ABSTAINED)
            val abstention = Option.when(v.report.counts.verified == 0)(
              Abstention(
                AbstentionReason.NO_VERIFIED_CLAIM,
                "No claim of the drafted answer could be verified against the evidence, so the answer abstains."
              )
            )
            finish(
              p,
              rs,
              Some(bundle),
              sem,
              v.report,
              v.claims,
              v.summary,
              lines ++ v.uncertainty,
              abstention,
              v.removed,
              v.dropped
            )
