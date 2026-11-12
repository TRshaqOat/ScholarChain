package scholarchain.rag.cli

import scholarchain.core.config.ScholarChainConfig
import scholarchain.rag.live.BedrockHaikuClient
import scholarchain.rag.runtime.*
import scholarchain.rag.{EmbeddingModels, Templates}
import scholarchain.storage.cli.{Args, Cli}
import scholarchain.storage.live.{BedrockTitanEmbedder, LiveAws}
import scholarchain.storage.{
  FileObjectStore,
  FileRuntimeRecords,
  FileVectorStore,
  HashingEmbedder,
  InMemoryGraph,
  ItemFileReader,
  RunManifest,
  GraphReadLimits,
  Throttle
}

import java.nio.file.{Path, Paths}
import java.time.{Duration, Instant}

/** The local stores loaded once and the runtime wired on them: AskCli answers one question with it,
  * BenchmarkCli many (one graph load per run).
  */
final class LoadedRuntime private[cli] (
    val setup: RuntimeSetup,
    val ports: RuntimePorts,
    audit: FileObjectStore,
    val outRoot: Path
):
  def ask(question: String, sessionId: Option[String]): Either[RuntimeFailure, RuntimeResult] =
    Workflow.answer(
      QueryRequest(RuntimeSchemas.QueryRequest, "", sessionId, question, None, None, None, None),
      ports,
      setup
    )

  /** Writes the per-question run manifest (RUNTIME.md section 14); returns every object key. */
  def writeManifest(r: RuntimeResult): Either[String, Vector[String]] =
    val manifest =
      RunManifest(RunManifest.SchemaVersion, "rag-runtime", setup.runId, setup.snapshot, r.written)
    val mk = Audit.manifestKey(setup.snapshot, setup.runId, r.answer.response.queryId)
    Audit
      .putOnce(audit.loader, mk, RunManifest.toJson(manifest), "manifest")
      .map(_ => r.written.map(e => e.key) :+ mk)

/** Answers one question locally with the Phase 9 workflow (RUNTIME.md section 17).
  *
  * Fake mode (default): rule planner, template synthesizer, hashing embedder; no AWS call. `--live
  * --profile <name>` (the human only): Claude Haiku 4.5 for planning and synthesis and Titan V2 for
  * the query embedding, Haiku throttled to --per-minute (1 to 8, every attempt and retry), Titan to
  * runtime.embedding.requests-per-minute. Graph and key-value stores are always the local files
  * loaded read-only; outputs go under --out (default scratch/runtime). Exit 0 for an answer or an
  * abstention, 1 for a failure, 2 for a usage error, 3 for low disk.
  */
object AskCli:
  val StoreUsage =
    "--graph <neptune CSV run dir> --items <dynamodb item run dir> --objects <object store root> " +
      "--vectors <file vector store root> [--out <runtime root, default scratch/runtime>] " +
      "[--live --profile <name> [--region <r>] [--per-minute 1..8]]"
  val UsageText =
    s"rag/runMain scholarchain.rag.cli.AskCli --question <text> [--session <id>] $StoreUsage"
  val StoreOpts: Set[String] =
    Cli.LiveOpts ++ Set("graph", "items", "objects", "vectors", "out", "per-minute")

  def main(args: Array[String]): Unit = Cli.main("AskCli", args)(run(_, _))

  private def heapMiB(): Long =
    System.gc()
    val r = Runtime.getRuntime
    (r.totalMemory - r.freeMemory) >> 20

  def run(
      args: Seq[String],
      out: String => Unit,
      sleep: Long => Unit = Thread.sleep,
      nowMs: () => Long = () => Throttle.monotonicMs(),
      env: Map[String, String] = sys.env
  ): Int =
    RagCli.withArgs(
      args,
      Cli.LiveFlags,
      StoreOpts ++ Set("question", "session"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        question <- a.req("question")
        loaded <- open(a, cfg, out, sleep, nowMs)
      yield loaded.fold(1) { rt =>
        rt.ask(question, a.get("session")) match
          case Left(f) =>
            out(s"FAILED at ${f.stage}: ${f.message}")
            1
          case Right(r) =>
            print(r, out)
            rt.writeManifest(r) match
              case Left(e) =>
                out(s"manifest not written: $e")
                1
              case Right(keys) =>
                out(s"written under ${rt.outRoot.resolve("s3")}: ${keys.mkString(", ")}")
                0
      }
    }

  /** Loads the stores read-only and wires the runtime. Left: a usage problem (exit 2). Right(None):
    * a store failed validation, already reported (exit 1).
    */
  def open(
      a: Args,
      cfg: ScholarChainConfig,
      out: String => Unit,
      sleep: Long => Unit,
      nowMs: () => Long
  ): Either[String, Option[LoadedRuntime]] =
    for
      graphDir <- a.req("graph").map(Paths.get(_))
      itemsDir <- a.req("items").map(Paths.get(_))
      objectsRoot <- a.req("objects").map(Paths.get(_))
      vectorsRoot <- a.req("vectors").map(Paths.get(_))
      outRoot = Paths.get(a.get("out").getOrElse("scratch/runtime"))
      perMinute <- a
        .int("per-minute", Some(cfg.runtime.llm.requestsPerMinute))
        .filterOrElse(n => n >= 1 && n <= 8, "--per-minute must be 1 to 8 (ruling 11)")
      model <- EmbeddingModels.forId(cfg.models.embeddingModel)
      live <- Cli.liveTarget(a, cfg.region)
      graphSeg <- Cli.runSegments(graphDir).toRight(s"$graphDir has no snapshot=/run= segments")
      itemsSeg <- Cli.runSegments(itemsDir).toRight(s"$itemsDir has no snapshot=/run= segments")
      prompts <- PromptSet.load(
        Paths.get(cfg.runtime.promptsDir),
        cfg.runtime.planner.promptVersion,
        cfg.runtime.synthesis.promptVersion
      )
      _ <- Cli.freeDiskProblem(outRoot).toLeft(())
    yield
      val (snapshot, graphRun) = graphSeg
      val itemsRun = itemsSeg._2
      val mode = if live.isDefined then RuntimeMode.LIVE else RuntimeMode.FAKE
      val version = EmbeddingModels.version(model, Templates.Version)
      val vectorFiles = Cli.files(vectorsRoot, ".jsonl")
      val runId = Workflow.runIdFor(
        cfg,
        Seq(
          s"graph=$graphRun",
          s"items=$itemsRun",
          s"vectors=${Cli.contentHash(vectorsRoot, vectorFiles)}",
          s"embedding=$version"
        ),
        prompts,
        mode
      )
      out(s"runtime run $runId (${mode.toString.toLowerCase} mode), snapshot $snapshot")
      val objects = FileObjectStore(objectsRoot)
      val graph = InMemoryGraph()
      val t0 = nowMs()
      graph.loader.load(graphDir, cfg.graph.windows, Some(objects.reader)) match
        case Left(vs) =>
          Cli.report(vs, 5, out)
          None
        case Right(_) =>
          out(
            s"graph loaded: ${graph.vertexCount} vertices, ${graph.edgeCount} edges in ${nowMs() - t0} ms; heap in use ${heapMiB()} MiB"
          )
          val t1 = nowMs()
          ItemFileReader.open(itemsDir, cfg.graph.windows) match
            case Left(vs) =>
              Cli.report(vs, 5, out)
              None
            case Right(kv) =>
              out(
                s"items indexed: ${kv.itemCount} in ${nowMs() - t1} ms; heap in use ${heapMiB()} MiB"
              )
              Some(
                wire(
                  cfg,
                  prompts,
                  live,
                  perMinute,
                  version,
                  model.modelId,
                  graph,
                  kv,
                  objects,
                  vectorsRoot,
                  outRoot,
                  snapshot,
                  graphRun,
                  runId,
                  mode,
                  out,
                  sleep,
                  nowMs
                )
              )

  private def wire(
      cfg: ScholarChainConfig,
      prompts: PromptSet,
      live: Option[scholarchain.storage.live.LiveTarget],
      perMinute: Int,
      version: String,
      embeddingModelId: String,
      graph: InMemoryGraph,
      kv: ItemFileReader,
      objects: FileObjectStore,
      vectorsRoot: Path,
      outRoot: Path,
      snapshot: String,
      graphRun: String,
      runId: String,
      mode: RuntimeMode,
      out: String => Unit,
      sleep: Long => Unit,
      nowMs: () => Long
  ): LoadedRuntime =
    val llmThrottle = Throttle(perMinute, nowMs, sleep)
    val embedThrottle = Throttle(cfg.runtime.embedding.requestsPerMinute, nowMs, sleep)
    val (llm, embedder) = live match
      case Some(t) =>
        out(
          s"LIVE: Bedrock ${cfg.models.plannerModel} (at most $perMinute requests per minute) and $embeddingModelId in ${t.region}, profile ${t.profile}"
        )
        val overrides = LiveAws.overrides.toBuilder
          .apiCallTimeout(Duration.ofMillis(cfg.runtime.llm.timeoutMs.toLong))
          .build()
        val embedOverrides = LiveAws.overrides.toBuilder
          .apiCallTimeout(Duration.ofMillis(cfg.runtime.embedding.timeoutMs.toLong))
          .build()
        (
          Some(BedrockHaikuClient(LiveAws.bedrock(t, overrides), () => llmThrottle.acquire())),
          BedrockTitanEmbedder(
            LiveAws.bedrock(t, embedOverrides),
            embeddingModelId,
            () => embedThrottle.acquire()
          )
        )
      case None =>
        out("fake mode: rule planner, template synthesizer, hashing embedder (no AWS call)")
        (None, HashingEmbedder())
    val audit = FileObjectStore(outRoot.resolve("s3"))
    val records = FileRuntimeRecords(
      outRoot.resolve("dynamodb").resolve(s"snapshot=$snapshot").resolve(s"run=$runId"),
      cfg.graph.windows,
      () => Instant.now.getEpochSecond
    )
    val ports = RuntimePorts(
      graph.reader(GraphReadLimits.from(cfg)),
      kv.reader,
      objects.reader,
      FileVectorStore(vectorsRoot).reader,
      embedder,
      audit.reader,
      audit.loader,
      records.writer
    )
    val setup = RuntimeSetup(
      cfg,
      prompts,
      mode,
      llm,
      llmThrottle,
      Some(embedThrottle),
      version,
      graphRun,
      snapshot,
      runId,
      nowMs,
      () => Instant.now
    )
    LoadedRuntime(setup, ports, audit, outRoot)

  def print(r: RuntimeResult, out: String => Unit): Unit =
    val a = r.answer
    out(
      s"query ${a.response.queryId}; plan ${a.plan.source}: ${a.plan.operations.map(o => s"${o.operation}/${o.scope}").mkString(", ")}" +
        s"; metrics ${a.plan.metrics.mkString(",")}; window ${a.plan.window.window}"
    )
    a.resolutions.foreach(x =>
      out(s"resolution '${x.name}': ${x.decision} ${x.researcherId
          .getOrElse("")} candidates ${x.candidateCount} share ${x.topShare.fold("-")(s => f"$s%.4f")}")
    )
    a.abstention.foreach(x => out(s"ABSTAINED (${x.reason})"))
    out(s"answer: ${a.response.summary}")
    a.response.claims.foreach(c =>
      out(s"  [${c.verificationStatus}] ${c.text} {${c.evidenceIds.mkString(" ")}}")
    )
    a.response.uncertainty.foreach(u => out(s"uncertainty: $u"))
    val v = a.verification.counts
    out(
      s"verification: ${v.verified} verified, ${v.partiallySupported} partial, ${v.unsupported} unsupported, ${v.conflicting} conflicting"
    )
    r.bundle.foreach(b =>
      out(
        s"bundle: ${b.graphEvidence.size} graph items (${b.budget.graphTokens} tokens), ${b.semanticEvidence.size} semantic (${b.budget.semanticTokens} tokens), omitted ${b.budget.omittedGraphItems}+${b.budget.omittedSemanticItems}"
      )
    )
    out(
      s"stages: ${r.audit.stages.map(s => s"${s.stage}=${s.outcome}/${s.latencyMs}ms").mkString(" ")}"
    )
