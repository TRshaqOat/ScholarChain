package scholarchain.rag.cli

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{CollaborationMetric, EvidenceItem, EvidenceType, VerificationStatus}
import scholarchain.rag.runtime.*
import upickle.default.{ReadWriter, readwriter}

import scala.collection.mutable

/** The benchmark suite's mechanical checks (ruling 12; RUNTIME.md section 22). Every check compares
  * the runtime's own records (plan, resolutions, abstention, claims, bundle, audit) with the
  * question row, the configured budgets, or the stored path evidence. Nothing here rates an answer,
  * scores relevance, or writes a label.
  */
object Benchmark:
  // v3 (experiment E): retrievalMode and the ranked retrieved list (RUNTIME.md section 24).
  val ResultSchema = "rag-benchmark-result-v3"
  val SummarySchema = "rag-benchmark-summary-v3"
  val CodeVersion = "rag-benchmark-v3"

  /** Bedrock requests one model attempt may send: the request and one expired-token retry
    * (LiveAws.guardedWith; review 1 m5).
    */
  val MaxRequestsPerAttempt = 2

  enum Outcome:
    case PASS, FAIL, NOT_APPLICABLE
  object Outcome:
    given ReadWriter[Outcome] = readwriter[String].bimap(_.toString, Outcome.valueOf)

  final case class Check(name: String, outcome: Outcome, detail: String) derives ReadWriter

  /** One row of the question file. Empty researcher cells are None. */
  final case class Question(
      id: String,
      category: String,
      text: String,
      researcherA: Option[String],
      researcherB: Option[String],
      window: String,
      answerType: String,
      shouldAbstain: Boolean
  ):
    def names: Vector[String] = (researcherA ++ researcherB).toVector

  /** Ruling 12's named decisions for benchmark-questions-v1. Every other named researcher is
    * expected to resolve (RESOLVED_DOMINANT).
    */
  final case class Expectations(
      resolutions: Map[String, Map[String, ResolutionDecision]],
      abstentions: Map[String, AbstentionReason]
  )
  val V1Expectations = Expectations(
    Map(
      "B09" -> Map(
        "Lei Zhang" -> ResolutionDecision.AMBIGUOUS,
        "Wei Wang" -> ResolutionDecision.AMBIGUOUS
      ),
      "B12" -> Map("Elena Vasquez-Marlowe" -> ResolutionDecision.UNKNOWN_RESEARCHER)
    ),
    Map(
      "B09" -> AbstentionReason.AMBIGUOUS_RESEARCHER,
      "B12" -> AbstentionReason.UNKNOWN_RESEARCHER
    )
  )

  // ---- CSV ----

  /** RFC 4180 rows: quoted fields with "" escapes and commas; a quoted newline is refused. */
  def csvRows(text: String): Either[String, Vector[Vector[String]]] =
    val lines = text.split("\r?\n").toVector.zipWithIndex.filter(_._1.nonEmpty)
    lines.foldLeft[Either[String, Vector[Vector[String]]]](Right(Vector.empty)) {
      case (Left(e), _)              => Left(e)
      case (Right(acc), (line, idx)) =>
        val cells = Vector.newBuilder[String]
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while i < line.length do
          val c = line(i)
          if quoted then
            if c == '"' && i + 1 < line.length && line(i + 1) == '"' then
              cell += '"'
              i += 1
            else if c == '"' then quoted = false
            else cell += c
          else if c == '"' then quoted = true
          else if c == ',' then
            cells += cell.result()
            cell.clear()
          else cell += c
          i += 1
        cells += cell.result()
        if quoted then Left(s"line ${idx + 1}: unterminated quoted field")
        else Right(acc :+ cells.result())
    }

  private def table(
      text: String,
      required: Seq[String]
  ): Either[String, Vector[Map[String, String]]] =
    csvRows(text).flatMap { rows =>
      rows.headOption.toRight("empty file").flatMap { header =>
        val missing = required.filterNot(header.contains)
        val ragged = rows.zipWithIndex.drop(1).find(_._1.size != header.size)
        if missing.nonEmpty then Left(s"missing columns ${missing.mkString(", ")}")
        else
          ragged match
            case Some((_, i)) => Left(s"row ${i + 1} has a different number of cells")
            case None         => Right(rows.drop(1).map(r => header.zip(r).toMap))
      }
    }

  val QuestionColumns = Seq(
    "question_id",
    "category",
    "question_text",
    "researcher_a",
    "researcher_b",
    "window",
    "answer_type",
    "should_abstain"
  )

  def questions(text: String): Either[String, Vector[Question]] =
    table(text, QuestionColumns).flatMap { rows =>
      val parsed = rows.map { r =>
        def opt(k: String) = Option(r(k).trim).filter(_.nonEmpty)
        r("should_abstain").trim match
          case "yes" | "no" if r("question_id").trim.nonEmpty && r("question_text").trim.nonEmpty =>
            Right(
              Question(
                r("question_id").trim,
                r("category").trim,
                r("question_text").trim,
                opt("researcher_a"),
                opt("researcher_b"),
                r("window").trim,
                r("answer_type").trim,
                r("should_abstain").trim == "yes"
              )
            )
          case other => Left(s"question '${r("question_id")}': bad row (should_abstain '$other')")
      }
      parsed.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None    =>
          val qs = parsed.collect { case Right(q) => q }
          val dup = qs.groupBy(_.id).collectFirst { case (id, xs) if xs.size > 1 => id }
          dup.fold(Right(qs))(id => Left(s"duplicate question_id $id"))
    }

  // ---- Stored path evidence ----

  /** One stored hop: researcherX to researcherY in a window, as exported from the path run. */
  final case class RefHop(window: String, x: String, y: String, count: Long, first: Int, last: Int)

  val PathColumns = Seq(
    "window",
    "researcherX",
    "researcherY",
    "jointPublicationCount",
    "firstCollaborationYear",
    "lastCollaborationYear"
  )

  /** The stored path evidence of the benchmark pairs (one row per hop). */
  final case class PathReference(hops: Vector[RefHop]):
    private def key(w: String, a: String, b: String) = (w, if a < b then (a, b) else (b, a))
    private val byPair = hops.map(h => key(h.window, h.x, h.y) -> h).toMap

    def edge(window: String, a: String, b: String): Option[RefHop] = byPair.get(key(window, a, b))

    /** The stored researcher sequence joining source and target: breadth-first over the stored hops
      * (the union of the stored benchmark paths), so it is the stored path itself.
      */
    def path(window: String, source: String, target: String): Option[Vector[String]] =
      val adj = hops
        .filter(_.window == window)
        .flatMap(h => Seq(h.x -> h.y, h.y -> h.x))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.sorted)
        .toMap
      val prev = mutable.Map(source -> source)
      val queue = mutable.Queue(source)
      while queue.nonEmpty && !prev.contains(target) do
        val n = queue.dequeue()
        adj.getOrElse(n, Vector.empty).filterNot(prev.contains).foreach { m =>
          prev(m) = n
          queue.enqueue(m)
        }
      Option.when(source != target && prev.contains(target))(
        Iterator.iterate(target)(prev).takeWhile(_ != source).toVector.reverse.prepended(source)
      )

  def pathReference(text: String): Either[String, PathReference] =
    table(text, PathColumns).flatMap { rows =>
      val hops = rows.map(r =>
        for
          n <- r("jointPublicationCount").toLongOption
          f <- r("firstCollaborationYear").toIntOption
          l <- r("lastCollaborationYear").toIntOption
        yield RefHop(r("window"), r("researcherX"), r("researcherY"), n, f, l)
      )
      if hops.exists(_.isEmpty) then Left("a path evidence row has a non-numeric count or year")
      else Right(PathReference(hops.flatten))
    }

  // ---- Per-question result ----

  final case class GraphRetrieval(
      reads: Int,
      readsByTemplate: Map[String, Int],
      rows: Long,
      truncatedReads: Int,
      evidenceItems: Int,
      tokens: Int,
      omittedItems: Int,
      latencyMs: Long,
      pathStatuses: Vector[String]
  ) derives ReadWriter

  final case class SemanticRetrieval(
      embeddingCalls: Int,
      embeddingInputTokens: Long,
      indexes: Vector[String],
      hits: Int,
      tokens: Int,
      omittedItems: Int,
      latencyMs: Long,
      outcome: String
  ) derives ReadWriter

  final case class LlmUsage(
      attempts: Int,
      requests: Int,
      attemptsByStage: Map[String, Int],
      outcomes: Map[String, Int],
      inputTokens: Long,
      outputTokens: Long,
      throttleWaitMs: Long
  ) derives ReadWriter

  /** One retrieved item in the bundle order. source: the list (graph, vector, or fused = the
    * retrieval mode); origin: the retriever that produced the item (graph or vector); elementId:
    * the vector key of a vector item, the store pointer (sourceUri) of a graph item.
    */
  final case class Retrieved(
      rank: Int,
      source: String,
      origin: String,
      evidenceId: String,
      elementId: String
  ) derives ReadWriter

  /** The ranked list of what the question retrieved: graph items in bundle order, then vector items
    * best score first (fusion-v1 keeps the two apart and never merges them into one score).
    * shortcut: items the bundle budget left out are counted (omittedItems), not listed; list them
    * if recall at K must reach past the bundle.
    */
  def retrieved(r: RuntimeResult): Vector[Retrieved] =
    val source = r.audit.retrievalMode match
      case RetrievalMode.FUSED       => "fused"
      case RetrievalMode.GRAPH_ONLY  => "graph"
      case RetrievalMode.VECTOR_ONLY => "vector"
    r.bundle.toVector
      .flatMap(b => b.graphEvidence.map("graph" -> _) ++ b.semanticEvidence.map("vector" -> _))
      .zipWithIndex
      .map { case ((origin, e), i) =>
        Retrieved(i + 1, source, origin, e.evidenceId, e.chunkId.getOrElse(e.sourceUri))
      }

  private def cell(s: String) =
    if s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + s.replace("\"", "\"\"") + "\""
    else s

  /** The ranked-list file of one question (CSV, header first). No score against any label. */
  def retrievedCsv(xs: Vector[Retrieved]): String =
    ("rank,source,origin,evidenceId,elementId" +: xs.map(x =>
      Seq(x.rank.toString, x.source, x.origin, x.evidenceId, x.elementId).map(cell).mkString(",")
    )).mkString("", "\n", "\n")

  final case class Result(
      schemaVersion: String,
      questionId: String,
      category: String,
      mode: String,
      retrievalMode: String,
      runtimeRunId: String,
      queryId: String,
      planSource: String,
      operations: Vector[String],
      metrics: Vector[String],
      window: String,
      resolutions: Vector[ResolutionSummary],
      abstained: Boolean,
      abstentionReason: Option[String],
      verification: VerificationCounts,
      claims: Int,
      graph: GraphRetrieval,
      semantic: SemanticRetrieval,
      llm: LlmUsage,
      latencyMs: Long,
      stageLatencyMs: Map[String, Long],
      checks: Vector[Check],
      answerHash: String,
      retrieved: Vector[Retrieved]
  ) derives ReadWriter

  private val GraphStages =
    Set(Stage.COLLABORATION_METRICS, Stage.PATH_FINDING, Stage.COMMUNITY_ANALYSIS)

  def result(
      q: Question,
      r: RuntimeResult,
      ref: PathReference,
      cfg: ScholarChainConfig,
      latencyMs: Long,
      expect: Expectations = V1Expectations
  ): Result =
    val a = r.answer
    val au = r.audit
    val b = r.bundle
    val stageMs = au.stages.groupMapReduce(_.stage.toString)(_.latencyMs)(_ + _)
    Result(
      ResultSchema,
      q.id,
      q.category,
      au.mode.toString,
      au.retrievalMode.toString,
      au.runId,
      a.response.queryId,
      a.plan.source.toString,
      a.plan.operations.map(o => s"${o.operation}/${o.scope}"),
      (a.plan.metrics ++ a.plan.operations.flatMap(_.metric)).distinct.map(_.toString),
      a.plan.window.window,
      au.resolutions,
      a.response.abstained,
      a.abstention.map(_.reason.toString),
      a.verification.counts,
      a.response.claims.size,
      GraphRetrieval(
        au.templateCalls.size,
        au.templateCalls.groupMapReduce(_.templateId)(_ => 1)(_ + _),
        au.templateCalls.map(_.resultCount.toLong).sum,
        au.templateCalls.count(_.truncated),
        b.fold(0)(_.graphEvidence.size),
        b.fold(0)(_.budget.graphTokens),
        b.fold(0)(_.budget.omittedGraphItems),
        au.stages.filter(s => GraphStages(s.stage)).map(_.latencyMs).sum,
        b.toVector.flatMap(
          _.graphEvidence
            .filter(_.evidenceType == EvidenceType.GRAPH_PATH)
            .flatMap(_.facts.get("status"))
        )
      ),
      SemanticRetrieval(
        au.embeddingCalls.size,
        au.embeddingCalls.flatMap(_.inputTokens).map(_.toLong).sum,
        au.vectorIndexes,
        b.fold(0)(_.semanticEvidence.size),
        b.fold(0)(_.budget.semanticTokens),
        b.fold(0)(_.budget.omittedSemanticItems),
        au.stages.filter(_.stage == Stage.SEMANTIC_CONTEXT).map(_.latencyMs).sum,
        au.stages.find(_.stage == Stage.SEMANTIC_CONTEXT).fold("NONE")(_.outcome.toString)
      ),
      LlmUsage(
        au.llmCalls.size,
        au.llmCalls.map(_.requests).sum,
        au.llmCalls.groupMapReduce(_.stage.toString)(_ => 1)(_ + _),
        au.llmCalls.groupMapReduce(_.outcome.toString)(_ => 1)(_ + _),
        au.llmCalls.flatMap(_.inputTokens).map(_.toLong).sum,
        au.llmCalls.flatMap(_.outputTokens).map(_.toLong).sum,
        au.llmCalls.map(_.throttleWaitMs).sum
      ),
      latencyMs,
      stageMs,
      checks(q, r, ref, cfg, latencyMs, expect),
      a.answerHash,
      retrieved(r)
    )

  /** A model or embedding call that never succeeded because of an error (not invalid output, not a
    * budget skip): the answer fell back or abstained for an infrastructure reason, so the harness
    * records no result and a rerun retries the question.
    */
  def callFailure(au: AuditRecord): Option[String] =
    val broken = Set(CallOutcome.TRANSIENT_ERROR, CallOutcome.FAILED)
    val llm = au.llmCalls.groupBy(_.stage).toVector.sortBy(_._1.ordinal).collectFirst {
      case (st, cs)
          if !cs.exists(_.outcome == CallOutcome.OK) && cs.exists(c => broken(c.outcome)) =>
        s"$st: no model call succeeded (${cs.map(_.outcome).mkString(", ")})"
    }
    llm.orElse(
      au.embeddingCalls
        .find(c => broken(c.outcome))
        .map(c => s"embedding for ${c.index}: ${c.outcome}")
    )

  // ---- Checks ----

  private def check(name: String, ok: Boolean, detail: String) =
    Check(name, if ok then Outcome.PASS else Outcome.FAIL, detail)
  private def na(name: String, detail: String) = Check(name, Outcome.NOT_APPLICABLE, detail)

  private val Bridge = Set(
    CollaborationMetric.CROSS_COMMUNITY_DEGREE,
    CollaborationMetric.COMMUNITIES_CONNECTED,
    CollaborationMetric.PARTICIPATION_COEFFICIENT
  )

  /** What the plan must contain for each question category (the CSV's category column). */
  def categoryOk(category: String, plan: QueryPlan): Option[(String, Boolean)] =
    import scholarchain.core.QueryOperation.*
    val ops = plan.operations
    val metrics = plan.metrics ++ ops.flatMap(_.metric)
    def has(op: scholarchain.core.QueryOperation) = ops.exists(_.operation == op)
    val pair = ops.exists(o => o.operation == DIRECT_COAUTHORS && o.scope == OperationScope.PAIR)
    category match
      case "coauthor_breadth" =>
        Some(
          "DISTINCT_COAUTHOR_COUNT" -> metrics.contains(CollaborationMetric.DISTINCT_COAUTHOR_COUNT)
        )
      case "max_team_size" =>
        Some(
          "MAXIMUM_TEAM_SIZE" -> (has(MAXIMUM_TEAM_SIZE) ||
            metrics.contains(CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER))
        )
      case "direct_collaboration" => Some("DIRECT_COAUTHORS/PAIR" -> pair)
      case "shortest_path"        => Some("SHORTEST_PATH" -> has(SHORTEST_PATH))
      case "components"           => Some("COMPONENT_LOOKUP" -> has(COMPONENT_LOOKUP))
      case "communities"          => Some("COMMUNITY_COMPARISON" -> has(COMMUNITY_COMPARISON))
      case "bridges"  => Some("BRIDGE_RANKING" -> (has(BRIDGE_RANKING) || metrics.exists(Bridge)))
      case "temporal" => Some("TEMPORAL_COMPARISON" -> has(TEMPORAL_COMPARISON))
      case "topics"   => Some("TOPIC_FILTER" -> has(TOPIC_FILTER))
      case "institutions" => Some("INSTITUTION_FILTER" -> has(INSTITUTION_FILTER))
      case "evidence"     =>
        Some("DIRECT_COAUTHORS/PAIR or SHORTEST_PATH" -> (pair || has(SHORTEST_PATH)))
      case _ => None

  def checks(
      q: Question,
      r: RuntimeResult,
      ref: PathReference,
      cfg: ScholarChainConfig,
      latencyMs: Long,
      expect: Expectations = V1Expectations
  ): Vector[Check] =
    val a = r.answer
    val plan = a.plan
    val au = r.audit
    val claims = a.response.claims
    val graphItems = r.bundle.toVector.flatMap(_.graphEvidence)
    val graphIds = graphItems.map(_.evidenceId).toSet
    val allIds = graphIds ++ r.bundle.toVector.flatMap(_.semanticEvidence.map(_.evidenceId))
    val cited = claims.flatMap(_.evidenceIds).toSet

    val planCheck =
      val bad = plan.operations.filterNot(o =>
        Allowlist.scopes.get(o.operation).exists(_(o.scope)) &&
          o.metric.forall(Allowlist.metricAllowed(o.operation, _))
      )
      val ops = plan.operations.map(o => s"${o.operation}/${o.scope}").mkString(", ")
      if bad.nonEmpty then
        check("plan-operations", false, s"not allowlisted: ${bad.mkString(", ")}")
      else
        categoryOk(q.category, plan) match
          case None => na("plan-operations", s"no rule for category '${q.category}'; plan [$ops]")
          case Some((need, ok)) => check("plan-operations", ok, s"needs $need; plan [$ops]")

    val namesCheck =
      val got = plan.researcherNames.map(_.name).toSet
      check(
        "researcher-names",
        got == q.names.toSet,
        s"expected [${q.names.mkString("; ")}], extracted [${got.toVector.sorted.mkString("; ")}]"
      )

    val resolutionCheck =
      if q.names.isEmpty then na("resolution", "no named researcher")
      else
        val want = q.names.map(n =>
          n -> expect.resolutions
            .get(q.id)
            .flatMap(_.get(n))
            .getOrElse(ResolutionDecision.RESOLVED_DOMINANT)
        )
        val got = want.map((n, _) => a.resolutions.find(_.name == n).map(_.decision))
        check(
          "resolution",
          want.map(_._2).map(Some(_)) == got,
          want
            .zip(got)
            .map { case ((n, w), g) => s"$n: expected $w, got ${g.fold("none")(_.toString)}" }
            .mkString("; ")
        )

    val abstentionCheck =
      val reason = expect.abstentions.get(q.id)
      val ok = a.response.abstained == q.shouldAbstain && reason.forall(x =>
        a.abstention.exists(_.reason == x)
      )
      check(
        "abstention",
        ok,
        s"expected ${if q.shouldAbstain then "abstain" else "answer"}${reason.fold("")(x => s" ($x)")}, got " +
          a.abstention.fold("answer")(x => s"abstain (${x.reason})")
      )

    val evidenceCheck =
      if claims.isEmpty then na("claim-evidence", "no claim")
      else
        // Material = every kind but DESCRIPTIVE, which the verifier marks by running
        // graph-evidence-required; a claim with no verifier record counts as material.
        def material(id: String) = a.verification.checks
          .find(_.claimId == id)
          .forall(_.checks.exists(_.rule == "graph-evidence-required"))
        val bad = claims.filterNot(c =>
          c.evidenceIds.nonEmpty && c.evidenceIds.forall(allIds) &&
            (!material(c.claimId) || c.evidenceIds.exists(graphIds))
        )
        val descriptive = claims.count(c => !material(c.claimId))
        check(
          "claim-evidence",
          bad.isEmpty,
          if bad.isEmpty then
            s"${claims.size} claims cite bundle IDs; ${claims.size - descriptive} material claims each cite a graph item; $descriptive descriptive"
          else s"claims without resolvable evidence: ${bad.map(_.claimId).mkString(", ")}"
        )

    val verificationCheck =
      val statuses = claims.map(_.verificationStatus)
      val kept = statuses.forall(s =>
        s == VerificationStatus.VERIFIED || s == VerificationStatus.PARTIALLY_SUPPORTED
      )
      // Ruling 19: only VERIFIED claims that cite graph evidence count.
      val verified = claims.count(c =>
        c.verificationStatus == VerificationStatus.VERIFIED && c.evidenceIds.exists(graphIds)
      )
      val c = a.verification.counts
      val counts =
        s"counts ${c.verified}/${c.partiallySupported}/${c.unsupported}/${c.conflicting}; final claims $verified verified of ${claims.size}"
      if a.response.abstained then
        check(
          "verification",
          verified == 0 && kept,
          s"abstained with no verified claim kept; $counts"
        )
      else check("verification", verified >= 1 && kept, s"answer has a verified claim; $counts")

    val budgetCheck = r.bundle match
      case None    => na("bundle-budget", "no bundle")
      case Some(b) =>
        val bb = cfg.runtime.budget
        val items = b.graphEvidence.size + b.semanticEvidence.size
        check(
          "bundle-budget",
          b.budget.graphTokens <= bb.graphEvidenceTokens &&
            b.budget.semanticTokens <= bb.semanticContextTokens &&
            b.budget.graphTokens + b.budget.semanticTokens <= cfg.retrieval.contextTokenBudget &&
            items <= bb.maxEvidenceItems,
          s"graph ${b.budget.graphTokens}/${bb.graphEvidenceTokens} tokens, semantic ${b.budget.semanticTokens}/${bb.semanticContextTokens}, items $items/${bb.maxEvidenceItems}"
        )

    val llmCheck =
      val max = cfg.runtime.llm.maxAttemptsPerCall
      val byStage = au.llmCalls.groupBy(_.stage)
      val in = au.llmCalls.flatMap(_.inputTokens).sum
      val out = au.llmCalls.flatMap(_.outputTokens).sum
      val requests = au.llmCalls.map(_.requests).sum
      check(
        "llm-budget",
        byStage.keySet.subsetOf(Set(Stage.QUERY_PLANNING, Stage.ANSWER_SYNTHESIS)) &&
          byStage.values.forall(_.size <= max) &&
          au.llmCalls.forall(c => c.requests >= 0 && c.requests <= MaxRequestsPerAttempt) &&
          in <= cfg.runtime.budget.maxInputTokensPerQuestion &&
          out <= cfg.runtime.budget.maxOutputTokensPerQuestion,
        s"${au.llmCalls.size} attempts, $requests requests (${byStage.map((s, xs) => s"$s ${xs.size}").mkString(", ")}), tokens in $in out $out"
      )

    val embedCheck =
      val n = au.embeddingCalls.size
      val ix = au.vectorIndexes.distinct.size
      check("embedding-budget", n <= ix && n <= 1, s"$n embedding calls for $ix indexes queried")

    val paths = graphItems.filter(_.evidenceType == EvidenceType.GRAPH_PATH)
    val pathCheck =
      if paths.isEmpty then na("path-hops", "no path evidence in the bundle")
      else
        val res = paths.map(p => pathAgainst(p, ref, cited))
        if res.forall(_._1.isEmpty) then na("path-hops", res.map(_._2).mkString("; "))
        else check("path-hops", res.forall(_._1.forall(identity)), res.map(_._2).mkString("; "))

    val edges = graphItems.filter(_.evidenceType == EvidenceType.COAUTHOR_EDGE).flatMap { e =>
      for
        w <- e.facts.get("window")
        x <- e.facts.get("researcherA")
        y <- e.facts.get("researcherB")
        h <- ref.edge(w, x, y)
      yield (e, h)
    }
    val edgeCheck =
      if edges.isEmpty then na("pair-edges", "no bundle edge has stored path evidence")
      else
        val bad = edges.filterNot((e, h) =>
          e.facts.get("jointPublicationCount").contains(h.count.toString) &&
            e.facts.get("firstCollaborationYear").contains(h.first.toString) &&
            e.facts.get("lastCollaborationYear").contains(h.last.toString)
        )
        check(
          "pair-edges",
          bad.isEmpty,
          s"${edges.size - bad.size} of ${edges.size} edges equal the stored hop" +
            (if bad.isEmpty then "" else s"; differ: ${bad.map(_._1.evidenceId).mkString(", ")}")
        )

    val deadline = cfg.runtime.question.deadlineMs
    Vector(
      planCheck,
      namesCheck,
      resolutionCheck,
      abstentionCheck,
      evidenceCheck,
      verificationCheck,
      budgetCheck,
      llmCheck,
      embedCheck,
      pathCheck,
      edgeCheck,
      check("deadline", latencyMs <= deadline, s"$latencyMs ms of $deadline")
    )

  /** One GRAPH_PATH item against the stored path: None when the pair has no stored path. */
  private def pathAgainst(
      p: EvidenceItem,
      ref: PathReference,
      cited: Set[String]
  ): (Option[Boolean], String) =
    val f = p.facts
    val (w, s, t) =
      (f.getOrElse("window", ""), f.getOrElse("source", ""), f.getOrElse("target", ""))
    val status = f.getOrElse("status", "")
    ref.path(w, s, t) match
      case None         => (None, s"$s-$t ($status): no stored path evidence for this pair")
      case Some(stored) =>
        val ids = f
          .get("researcherIds")
          .map(_.split(' ').toVector.filter(_.nonEmpty))
          .getOrElse(Vector.empty)
        val hopProblems = (1 until ids.size).flatMap { k =>
          val got = Seq("from", "to", "jointPublicationCount", "firstYear", "lastYear").map(x =>
            f.get(s"hop.$k.$x")
          )
          val want = ref
            .edge(w, ids(k - 1), ids(k))
            .map(h =>
              Seq(ids(k - 1), ids(k), h.count.toString, h.first.toString, h.last.toString)
                .map(Some(_))
            )
          Option.when(want.forall(_ != got))(s"hop $k differs")
        }
        val ok = status == "FOUND" && ids == stored && hopProblems.isEmpty && cited(p.evidenceId)
        (
          Some(ok),
          s"$s-$t: runtime $status ${math.max(0, ids.size - 1)} hops, stored ${stored.size - 1} hops" +
            (if ids == stored then ", same researchers" else ", different researchers") +
            hopProblems.map("; " + _).mkString +
            (if cited(p.evidenceId) then "" else "; no claim cites the path")
        )

  // ---- Summary ----

  final case class Tally(pass: Int, fail: Int, notApplicable: Int) derives ReadWriter

  final case class Latency(p50Ms: Long, p95Ms: Long, maxMs: Long, totalMs: Long) derives ReadWriter

  final case class GraphTotals(
      questionsWithReads: Int,
      reads: Int,
      rows: Long,
      truncatedReads: Int,
      evidenceItems: Int,
      tokens: Int,
      pathStatuses: Map[String, Int],
      latency: Latency
  ) derives ReadWriter

  final case class SemanticTotals(
      questionsWithEmbedding: Int,
      questionsWithHits: Int,
      embeddingCalls: Int,
      hits: Int,
      tokens: Int,
      indexes: Map[String, Int],
      latency: Latency
  ) derives ReadWriter

  final case class Summary(
      schemaVersion: String,
      benchmarkRunId: String,
      runtimeRunId: String,
      mode: String,
      retrievalMode: String,
      questionsTotal: Int,
      questionsDone: Int,
      notDone: Vector[String],
      checks: Map[String, Tally],
      failures: Vector[String],
      planSources: Map[String, Int],
      abstentions: Map[String, Int],
      resolutions: Map[String, Int],
      verification: VerificationCounts,
      claims: Int,
      graph: GraphTotals,
      semantic: SemanticTotals,
      llm: LlmUsage,
      latency: Latency
  ) derives ReadWriter

  def latency(xs: Seq[Long]): Latency =
    if xs.isEmpty then Latency(0, 0, 0, 0)
    else
      val s = xs.sorted.toVector
      def pct(p: Double) = s(math.max(0, math.ceil(p * s.size).toInt - 1))
      Latency(pct(0.5), pct(0.95), s.last, s.sum)

  private def count[A](xs: Seq[A]): Map[String, Int] = xs.groupMapReduce(_.toString)(_ => 1)(_ + _)
  private def sumMaps(ms: Seq[Map[String, Int]]): Map[String, Int] =
    ms.flatten.groupMapReduce(_._1)(_._2)(_ + _)

  /** The summary of every result present, in question-file order (resume-safe: it is rebuilt). */
  def summary(
      benchmarkRunId: String,
      runtimeRunId: String,
      mode: String,
      retrievalMode: String,
      all: Vector[Question],
      results: Vector[Result]
  ): Summary =
    val byId = results.map(r => r.questionId -> r).toMap
    val rs = all.flatMap(q => byId.get(q.id))
    val checks = rs.flatMap(_.checks)
    Summary(
      SummarySchema,
      benchmarkRunId,
      runtimeRunId,
      mode,
      retrievalMode,
      all.size,
      rs.size,
      all.map(_.id).filterNot(byId.contains),
      checks
        .groupBy(_.name)
        .view
        .mapValues { cs =>
          Tally(
            cs.count(_.outcome == Outcome.PASS),
            cs.count(_.outcome == Outcome.FAIL),
            cs.count(_.outcome == Outcome.NOT_APPLICABLE)
          )
        }
        .toMap,
      rs.flatMap(r =>
        r.checks
          .filter(_.outcome == Outcome.FAIL)
          .map(c => s"${r.questionId} ${c.name}: ${c.detail}")
      ),
      count(rs.map(_.planSource)),
      count(rs.map(_.abstentionReason.getOrElse("ANSWERED"))),
      count(rs.flatMap(_.resolutions.map(_.decision))),
      VerificationCounts(
        rs.map(_.verification.verified).sum,
        rs.map(_.verification.partiallySupported).sum,
        rs.map(_.verification.unsupported).sum,
        rs.map(_.verification.conflicting).sum
      ),
      rs.map(_.claims).sum,
      GraphTotals(
        rs.count(_.graph.reads > 0),
        rs.map(_.graph.reads).sum,
        rs.map(_.graph.rows).sum,
        rs.map(_.graph.truncatedReads).sum,
        rs.map(_.graph.evidenceItems).sum,
        rs.map(_.graph.tokens).sum,
        count(rs.flatMap(_.graph.pathStatuses)),
        latency(rs.map(_.graph.latencyMs))
      ),
      SemanticTotals(
        rs.count(_.semantic.embeddingCalls > 0),
        rs.count(_.semantic.hits > 0),
        rs.map(_.semantic.embeddingCalls).sum,
        rs.map(_.semantic.hits).sum,
        rs.map(_.semantic.tokens).sum,
        count(rs.flatMap(_.semantic.indexes)),
        latency(rs.map(_.semantic.latencyMs))
      ),
      LlmUsage(
        rs.map(_.llm.attempts).sum,
        rs.map(_.llm.requests).sum,
        sumMaps(rs.map(_.llm.attemptsByStage)),
        sumMaps(rs.map(_.llm.outcomes)),
        rs.map(_.llm.inputTokens).sum,
        rs.map(_.llm.outputTokens).sum,
        rs.map(_.llm.throttleWaitMs).sum
      ),
      latency(rs.map(_.latencyMs))
    )
