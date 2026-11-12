package scholarchain.entityresolution.tools

import scholarchain.core.*
import scholarchain.core.config.ConfigLoader
import scholarchain.entityresolution.*
import upickle.default.{read, write}

import java.io.BufferedWriter
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.collection.mutable

/** Local match-policy runner (er-v1 or er-v2, from configuration). Reads one Hadoop run's exact-ID
  * matches and conflicts (J03+J04) and blocked candidates (J06), applies MatchPolicy, optionally
  * adjudicates LLM-band pairs within a bounded budget, and writes validated MatchDecision JSON
  * Lines under `<out>/run=<er run ID>/decisions/entity_type=<T>/outcome=<O>.jsonl.gz`, plus
  * `summary.json`.
  *
  * Thresholds and the policy version come from ConfigLoader (scholarchain.conf, the file named by
  * SCHOLARCHAIN_CONFIG_FILE, environment overrides). Never reads gold labels.
  *
  * Usage: `Runner --run <hadoop run dir> --out <dir> [--code-version <sha>] [--attempt n]` plus,
  * for the explicit live adjudication profile only, `--adjudicate --max-adjudications <n>
  * --token-budget <tokens> --cache <cache.jsonl>`. Live adjudication refuses placeholder model IDs.
  */
object Runner:
  def main(args: Array[String]): Unit =
    val o = Io.options(args.toSeq)
    val run = RunInputs(Paths.get(Io.required(o, "run")))
    val cfg = ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity)
    val er = cfg.entityResolution
    val policy = MatchPolicy.requireVersion(er.policyVersion)
    val t = Thresholds.from(er)
    val codeVersion = o.getOrElse("code-version", "local")
    val inputs = Seq(s"hadoop-run=${run.runId}", s"policy=$policy")
    val outRoot = Paths.get(Io.required(o, "out"))
    def runIdOf(a: Int) =
      StableIds.runId("entity-resolution", codeVersion, cfg.configVersion, inputs, a)
    val attempt = o
      .get("attempt")
      .map(_.toInt)
      .getOrElse(
        Iterator.from(1).find(a => !Files.exists(outRoot.resolve(s"run=${runIdOf(a)}"))).get
      )
    val runId = runIdOf(attempt)
    val dir = outRoot.resolve(s"run=$runId")
    require(!Files.exists(dir), s"$dir exists; outputs are never overwritten")
    val adjudicator = Option.when(o.contains("adjudicate")) {
      val model = cfg.models.adjudicationModel
      require(!model.startsWith("example-"), s"adjudication model '$model' is a placeholder")
      val budget = AdjudicationBudget(
        Io.required(o, "max-adjudications").toInt,
        Io.required(o, "token-budget").toLong
      )
      val cache = JsonlFileCache(Paths.get(Io.required(o, "cache")))
      val throttle = Throttle.live(cfg.runtime.llm.requestsPerMinute)
      val (client, sdk) = BedrockLlmClient.live(
        model,
        cfg.region,
        None,
        cfg.runtime.llm.timeoutMs,
        () => throttle.acquire()
      )
      (Adjudicator(client, cache, budget, policy), budget, sdk)
    }
    val decidedAt = Instant.now().toString
    val summary = closing(adjudicator.map(_._3)) {
      execute(run, WorkIndex(run), t, policy, runId, decidedAt, dir, adjudicator.map(_._1))
    }
    val manifest = ujson.Obj(
      "schemaVersion" -> "er-run-manifest-v1",
      "runId" -> runId,
      "attempt" -> attempt,
      "codeVersion" -> codeVersion,
      "configVersion" -> cfg.configVersion,
      "policyVersion" -> policy,
      "promptVersion" -> AdjudicationPrompt.Version,
      "adjudicationModel" -> adjudicator.fold(ujson.Null: ujson.Value)(_ =>
        cfg.models.adjudicationModel
      ),
      "thresholds" -> ujson.Obj(
        "accept" -> t.accept,
        "reject" -> t.reject,
        "llmBandMin" -> t.llmBandMin,
        "llmBandMax" -> t.llmBandMax
      ),
      "hadoopRun" -> run.root.toString,
      "decidedAt" -> decidedAt,
      "adjudications" -> adjudicator.fold(0)(_._2.adjudications),
      "tokensUsed" -> adjudicator.fold(0L)(_._2.tokensUsed).toDouble,
      "counts" -> summary
    )
    Io.writeString(dir.resolve("summary.json"), ujson.write(manifest, indent = 2) + "\n")
    println(ujson.write(manifest, indent = 2))

  /** Runs `body`, then closes `resource` (the live SDK client, if any), also when `body` throws. */
  def closing[A](resource: Option[AutoCloseable])(body: => A): A =
    try body
    finally resource.foreach(_.close())

  /** Applies match policy `policy` to every candidate of the run and writes the decisions. Returns
    * counts keyed `<entityType>|<path>|<outcome>`, plus `<entityType>|LLM_ELIGIBLE`.
    */
  def execute(
      run: RunInputs,
      idx: WorkIndex,
      t: Thresholds,
      policy: String,
      runId: String,
      decidedAt: String,
      dir: Path,
      adjudicator: Option[Adjudicator]
  ): ujson.Obj =
    val writers = mutable.Map.empty[(MatchEntityType, MatchOutcome), BufferedWriter]
    val counts = mutable.TreeMap.empty[String, Long].withDefaultValue(0L)
    def emit(c: MatchCandidate, origin: CandidateOrigin): Unit =
      val features = (origin, c.entityType) match
        case (CandidateOrigin.BLOCKED, MatchEntityType.WORK) =>
          (for a <- idx.works.get(c.sourceId); b <- idx.works.get(c.targetId)
          yield c.features ++ WorkPairFeatures.work(a, b, policy)).getOrElse(c.features)
        case _ => c.features
      val v0 =
        if origin == CandidateOrigin.BLOCKED then
          MatchPolicy.decideBlocked(c.entityType, features, t, policy)
        else MatchPolicy.decide(c, origin, t, policy)
      if v0.llmEligible then counts(s"${c.entityType}|LLM_ELIGIBLE") += 1
      val adjudicated = adjudicator.filter(_ => v0.llmEligible).flatMap { a =>
        a.adjudicate(
          AdjudicationRequest(
            c.entityType,
            c.sourceId,
            c.targetId,
            Evidence.fields(idx, c.entityType, c.sourceId, c.targetId)
          )
        )
      }
      val v = adjudicated.fold(v0)(MatchPolicy.withAdjudication(v0, _, t))
      val d = MatchPolicy.toDecision(
        c,
        features,
        v,
        runId,
        decidedAt,
        adjudicated.map(_.promptVersion),
        adjudicated.map(_.modelVersion)
      )
      Validation
        .validate(d)
        .left
        .foreach(es => sys.error(s"invalid decision ${c.sourceId}|${c.targetId}: $es"))
      val w = writers.getOrElseUpdate(
        (d.entityType, d.decision),
        Io.gzWriter(
          dir.resolve(s"decisions/entity_type=${d.entityType}/outcome=${d.decision}.jsonl.gz")
        )
      )
      w.write(write(d))
      w.newLine()
      counts(s"${c.entityType}|${v.path}|${v.outcome}") += 1
    try
      Io.lines(run.exactMatches)
        .foreach(l => emit(read[MatchCandidate](l), CandidateOrigin.EXACT_MATCH))
      Io.lines(run.exactConflicts)
        .foreach(l => emit(read[MatchCandidate](l), CandidateOrigin.EXACT_CONFLICT))
      Io.lines(run.blockedCandidates)
        .foreach(l => emit(read[MatchCandidate](l), CandidateOrigin.BLOCKED))
    finally writers.values.foreach(_.close())
    ujson.Obj.from(counts.toSeq.map((k, n) => k -> ujson.Num(n.toDouble)))
