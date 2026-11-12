package scholarchain.entityresolution.tools

import scholarchain.core.*
import scholarchain.core.config.ConfigLoader
import scholarchain.entityresolution.*
import upickle.default.{ReadWriter, read, write}

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*
import scala.util.Try

/** Bounded live adjudication demonstration (Phase 9 work order 6). Draws a seeded, deterministic
  * sample of LLM-band decisions (UNRESOLVED, rule `band-llm`) from one Hadoop run's J10 decisions
  * as exported by J09, adjudicates them with the existing [[Adjudicator]], and writes the
  * adjudicated decisions as a separate result set. It never changes the run's decisions, the match
  * policy, or the thresholds (those come from the run's `_manifest.json`).
  *
  * Usage (repository root):
  *   - `--run <hadoop run dir> --out <dir> [--seed n] [--researchers 180] [--works 20] --plan`:
  *     prints the run ID, sample, and call and time estimates; writes nothing, calls nothing.
  *   - `... --live --profile <name> [--region r] --max <n> --token-budget <tokens> [--per-minute
  *     1..8]`: one session. `--max` caps new (uncached) adjudications per session; each makes at
  *     most `runtime.llm.max-attempts-per-call` attempts, and each attempt at most one more request
  *     after an expired token, all passing the throttle (default `runtime.llm.requests-per-minute`,
  *     at most 8). The model is `models.adjudication-model`.
  *
  * Run directory `<out>/run=<runId>/`; the run ID hashes the Hadoop run, policy, seed, sample
  * sizes, prompt version, and model, so rerunning the command resumes the run:
  *   - `run.json`, `sample.jsonl` (each sampled decision with its exact adjudication input),
  *     written once;
  *   - `cache.jsonl`, the append-only adjudication cache: each schema-valid answer is appended as
  *     soon as it returns (a partial last line left by a crash is cut on start); cached pairs cost
  *     no call on resume. Standard output gets one progress line per pair;
  *   - `session-NNNN/decisions.jsonl`, `failures.jsonl`, `summary.json`, one new folder per
  *     session, never overwritten. The latest COMPLETE session holds every sampled pair.
  */
object AdjudicateSample:
  val ToolVersion = "er-adjudicate-sample-v1"

  /** One sampled decision and the adjudication input built from the run's records. */
  final case class Pair(decision: MatchDecision, fields: Map[String, String]) derives ReadWriter:
    def request: AdjudicationRequest =
      AdjudicationRequest(decision.entityType, decision.sourceId, decision.targetId, fields)

  def llmBand(d: MatchDecision): Boolean =
    d.decision == MatchOutcome.UNRESOLVED && MatchPolicy.pathOf(d.rulesFired) == MatchPath.LLM_BAND

  def rank(seed: Long, d: MatchDecision): String =
    val Seq(a, b) = Seq(d.sourceId, d.targetId).sorted
    StableIds.hash("er-adjudication-sample", seed.toString, d.entityType.toString, a, b)

  /** The `n` LLM-band decisions of smallest rank (a seeded uniform sample that does not depend on
    * input order), in rank order. Two decisions of one unordered pair share a rank; the one with
    * the smaller (sourceId, targetId) is kept. Holds at most `n` decisions.
    */
  def select(ds: Iterator[MatchDecision], seed: Long, n: Int): Vector[MatchDecision] =
    val keep = mutable.TreeMap.empty[String, MatchDecision]
    ds.filter(llmBand).foreach { d =>
      val r = rank(seed, d)
      if keep.get(r).forall(k => (k.sourceId, k.targetId) > (d.sourceId, d.targetId)) then
        keep(r) = d
      if keep.size > n then keep.remove(keep.lastKey)
    }
    keep.values.toVector

  /** The decision after adjudication, recorded under this run with prompt and model versions. */
  def adjudicated(
      d: MatchDecision,
      a: Adjudication,
      t: Thresholds,
      runId: String,
      decidedAt: String
  ): MatchDecision =
    val policy = d.provenance.policyVersion.getOrElse(sys.error("decision has no policy version"))
    val v = MatchPolicy.withAdjudication(
      Verdict(d.decision, d.confidence, d.rulesFired, d.reasonCodes, policy),
      a,
      t
    )
    d.copy(
      rulesFired = v.rulesFired,
      decision = v.outcome,
      confidence = v.confidence,
      reasonCodes = v.reasonCodes,
      promptVersion = Some(a.promptVersion),
      decidedAt = decidedAt,
      provenance = d.provenance.copy(runId = runId, modelVersion = Some(a.modelVersion))
    )

  final case class SessionResult(
      decisions: Vector[MatchDecision],
      failures: Vector[(MatchDecision, String)],
      cached: Int,
      pending: Int,
      halted: Option[String]
  ):
    def status: String =
      if halted.isDefined then "FAILED" else if pending > 0 then "MAX_REACHED" else "COMPLETE"

  /** Adjudicates the pairs in order. Cached pairs cost nothing; a pair the budget does not allow is
    * pending. A pair that still has a client error after its retry halts the session (the rest stay
    * pending), so a denied or unavailable model costs at most one pair's attempts. Other failures
    * (schema, fabricated ID) are ABSTAIN, stay UNRESOLVED, and are not cached; after
    * `maxFailuresInARow` such pairs in a row the session halts too, so a systematic reply-format
    * problem costs a few pairs' attempts, not the whole budget. `progress` gets one line per
    * adjudicated pair (counts, outcome, and failure reason; no candidate text).
    */
  def session(
      pairs: Vector[Pair],
      adj: Adjudicator,
      t: Thresholds,
      runId: String,
      decidedAt: String,
      progress: String => Unit = _ => (),
      maxFailuresInARow: Int = 3
  ): SessionResult =
    val out = Vector.newBuilder[MatchDecision]
    val failures = Vector.newBuilder[(MatchDecision, String)]
    var (cached, pending, failedInARow) = (0, 0, 0)
    var halted: Option[String] = None
    pairs.zipWithIndex.foreach { (p, i) =>
      if halted.isDefined then pending += 1
      else
        adj.adjudicate(p.request) match
          case None    => pending += 1
          case Some(a) =>
            if a.cached then cached += 1
            a.failure.foreach(f => failures += p.decision -> f)
            failedInARow = if a.failure.isDefined then failedInARow + 1 else 0
            if a.failure.exists(_.startsWith("client error")) then
              halted = a.failure
              pending += 1
            else
              val d = adjudicated(p.decision, a, t, runId, decidedAt)
              Validation.validate(d).left.foreach(es => sys.error(s"invalid decision: $es"))
              out += d
              if failedInARow >= maxFailuresInARow then
                halted = a.failure.map(f => s"$failedInARow pairs in a row failed; last: $f")
            progress(
              s"pair ${i + 1}/${pairs.size} ${p.decision.entityType}: " +
                (if a.cached then "cached " else "") + a.decision +
                a.failure.fold("")(f => s" FAILED $f")
            )
    }
    SessionResult(out.result(), failures.result(), cached, pending, halted)

  /** Cuts a partial last line (no trailing newline) left by a crash during an append. */
  def cutPartialLine(f: Path): Unit =
    if Files.exists(f) && Files.size(f) > 0 then
      val raf = RandomAccessFile(f.toFile, "rw")
      try
        var end = raf.length()
        def lastByte = { raf.seek(end - 1); raf.read() }
        while end > 0 && lastByte != '\n' do end -= 1
        raf.setLength(end)
      finally raf.close()

  /** Estimated input tokens of one request (4 characters per token, as the budget estimates). */
  def estimatedInputTokens(p: Pair): Long =
    (AdjudicationPrompt.system.length + AdjudicationPrompt.user(p.request).length) / 4L

  def main(args: Array[String]): Unit =
    val o = Io.options(args.toSeq)
    val plan = o.contains("plan")
    if !plan then
      require(
        o.contains("live") && o.get("profile").exists(_ != "true"),
        "live adjudication needs --live and --profile <name> (or use --plan)"
      )
      Seq("max", "token-budget").foreach(Io.required(o, _))
    val run = RunInputs(Paths.get(Io.required(o, "run")))
    val outRoot = Paths.get(Io.required(o, "out"))
    val cfg = ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity)
    val manifest = ujson.read(Files.readString(run.manifest))
    val policy = runPolicyVersion(manifest)
    val t = ReviewQueue.thresholdsOf(manifest)
    val seed = o.getOrElse("seed", "20261107").toLong
    val targets = Vector(
      MatchEntityType.RESEARCHER -> o.getOrElse("researchers", "180").toInt,
      MatchEntityType.WORK -> o.getOrElse("works", "20").toInt
    )
    val model = cfg.models.adjudicationModel
    require(
      !model.startsWith("example-"),
      s"adjudication model '$model' is a placeholder; set SCHOLARCHAIN_ADJUDICATION_MODEL_ID"
    )
    val inputs = Seq(s"hadoop-run=${run.runId}", s"policy=$policy", s"seed=$seed") ++
      targets.map((et, n) => s"$et=$n") ++
      Seq(s"prompt=${AdjudicationPrompt.Version}", s"model=$model")
    val runId = StableIds.runId("er-adjudication", ToolVersion, cfg.configVersion, inputs, 1)
    val dir = outRoot.resolve(s"run=$runId")
    val sampleFile = dir.resolve("sample.jsonl")
    val cacheFile = dir.resolve("cache.jsonl")

    val pairs: Vector[Pair] =
      if Files.exists(sampleFile) then
        Files.readAllLines(sampleFile, UTF_8).asScala.toVector.map(read[Pair](_))
      else
        val selected = targets.flatMap { (et, n) =>
          val ds = Io
            .lines(run.decisions(et).resolve(s"outcome=${MatchOutcome.UNRESOLVED}"))
            .map(read[MatchDecision](_))
            .map { d =>
              require(d.provenance.policyVersion.contains(policy), s"decision is not $policy")
              d
            }
          select(ds, seed, n)
        }
        val idx = WorkIndex(run)
        selected.map(d => Pair(d, Evidence.fields(idx, d.entityType, d.sourceId, d.targetId)))
    val cachedKeys =
      if Files.exists(cacheFile) then
        // A partial last line (cut before a live session) is skipped here.
        Files
          .readAllLines(cacheFile, UTF_8)
          .asScala
          .flatMap(l => Try(read[CacheEntry](l).key).toOption)
          .toSet
      else Set.empty[String]
    val notCached = pairs.count(p =>
      !cachedKeys(
        AdjudicationCache.key(p.request.inputHash, AdjudicationPrompt.Version, model, policy)
      )
    )
    val perMinute = o.get("per-minute").map(_.toInt).getOrElse(cfg.runtime.llm.requestsPerMinute)
    val throttle = Throttle.live(perMinute)
    val attempts = cfg.runtime.llm.maxAttemptsPerCall
    val est = pairs.map(estimatedInputTokens)
    println(s"run $runId -> $dir")
    println(s"policy $policy, thresholds $t, prompt ${AdjudicationPrompt.Version}, model $model")
    targets.foreach((et, n) =>
      println(s"$et: ${pairs.count(_.decision.entityType == et)} sampled (target $n)")
    )
    println(
      s"not yet cached: $notCached; estimated input tokens per call: mean " +
        s"${if est.isEmpty then 0 else est.sum / est.size}, max ${est.maxOption.getOrElse(0L)}"
    )
    println(
      s"expected requests: $notCached (one per pair); bound: ${notCached * attempts * 2} " +
        s"($attempts attempts x 1 expired-token retry); at $perMinute per minute " +
        s"(${throttle.intervalMs} ms apart) about ${notCached * throttle.intervalMs / 60000 + 1} min"
    )
    if !plan then
      Files.createDirectories(dir)
      if !Files.exists(sampleFile) then
        val tmp = dir.resolve("sample.jsonl.tmp")
        Io.writeString(tmp, pairs.map(write(_)).mkString("", "\n", "\n"))
        Files.move(tmp, sampleFile, StandardCopyOption.ATOMIC_MOVE)
      val runJson = dir.resolve("run.json")
      if !Files.exists(runJson) then
        Io.writeString(
          runJson,
          ujson.write(
            ujson.Obj(
              "schemaVersion" -> "er-adjudication-run-v1",
              "runId" -> runId,
              "tool" -> ToolVersion,
              "configVersion" -> cfg.configVersion,
              "inputs" -> ujson.Arr.from(inputs),
              "hadoopRun" -> run.root.toString,
              "thresholds" -> ujson.Obj(
                "accept" -> t.accept,
                "reject" -> t.reject,
                "llmBandMin" -> t.llmBandMin,
                "llmBandMax" -> t.llmBandMax
              ),
              "sampled" -> pairs.size
            ),
            indent = 2
          ) + "\n"
        )
      cutPartialLine(cacheFile)
      val budget =
        AdjudicationBudget(Io.required(o, "max").toInt, Io.required(o, "token-budget").toLong)
      val region = o.getOrElse("region", cfg.region)
      val (client, sdk) = BedrockLlmClient.live(
        model,
        region,
        Some(o("profile")),
        cfg.runtime.llm.timeoutMs,
        () => throttle.acquire()
      )
      val n = Iterator.from(1).find(i => !Files.exists(dir.resolve(f"session-$i%04d"))).get
      val sdir = dir.resolve(f"session-$n%04d")
      val started = Instant.now().toString
      val r =
        try
          session(
            pairs,
            Adjudicator(client, JsonlFileCache(cacheFile), budget, policy, maxAttempts = attempts),
            t,
            runId,
            started,
            println(_)
          )
        finally sdk.close()
      Io.writeString(
        sdir.resolve("decisions.jsonl"),
        r.decisions.map(write(_)).mkString("", "\n", if r.decisions.isEmpty then "" else "\n")
      )
      Io.writeString(
        sdir.resolve("failures.jsonl"),
        r.failures
          .map((d, f) =>
            ujson.write(
              ujson.Obj(
                "entityType" -> d.entityType.toString,
                "sourceId" -> d.sourceId,
                "targetId" -> d.targetId,
                "failure" -> f
              )
            )
          )
          .mkString("", "\n", if r.failures.isEmpty then "" else "\n")
      )
      val outcomes = r.decisions
        .groupMapReduce(d => s"${d.entityType}|${d.decision}")(_ => 1)(_ + _)
        .toSeq
        .sorted
      val summary = ujson.Obj(
        "schemaVersion" -> "er-adjudication-session-v1",
        "runId" -> runId,
        "session" -> n,
        "status" -> r.status,
        "startedAt" -> started,
        "endedAt" -> Instant.now().toString,
        "profile" -> o("profile"),
        "region" -> region,
        "model" -> model,
        "promptVersion" -> AdjudicationPrompt.Version,
        "policyVersion" -> policy,
        "perMinute" -> perMinute,
        "max" -> budget.maxAdjudications,
        "tokenBudget" -> budget.maxTokens.toDouble,
        "sampled" -> pairs.size,
        "decided" -> r.decisions.size,
        "cached" -> r.cached,
        "newAdjudications" -> budget.adjudications,
        "requests" -> client.requests.toDouble,
        "tokensUsed" -> budget.tokensUsed.toDouble,
        "failures" -> r.failures.size,
        "pending" -> r.pending,
        "halted" -> r.halted.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
        "outcomes" -> ujson.Obj.from(outcomes.map((k, v) => k -> ujson.Num(v)))
      )
      Io.writeString(sdir.resolve("summary.json"), ujson.write(summary, indent = 2) + "\n")
      println(ujson.write(summary, indent = 2))
      r.halted.foreach(h => sys.error(s"session $n FAILED: $h; rerun the command to resume"))
