package scholarchain.services

import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import scholarchain.rag.live.BedrockHaikuClient
import scholarchain.rag.runtime.{
  PromptSet,
  RuntimeMode,
  RuntimePorts,
  RuntimeSetup,
  WindowSummarySource,
  Workflow
}
import scholarchain.rag.{EmbeddingModels, Templates}
import scholarchain.storage.cli.{Args, Cli}
import scholarchain.storage.live.{
  BedrockTitanEmbedder,
  DynamoEvidenceLookup,
  DynamoServiceStateTable,
  DynamoStore,
  LiveAws,
  SqsJobQueue,
  LiveTarget,
  NeptuneGraphReader,
  S3ObjectStore,
  S3VectorStore
}
import scholarchain.storage.{
  EvidenceLookupPort,
  FileObjectStore,
  FileRuntimeRecords,
  FileVectorStore,
  GraphReadLimits,
  HashingEmbedder,
  IdempotencyStore,
  InMemoryGraph,
  InMemoryJobQueue,
  ItemFileReader,
  JobQueuePort,
  JobStore,
  LocalServiceStateTable,
  ObjectReadPort,
  RetryPolicy,
  S3Layout,
  Throttle,
  WindowSummaries
}

import java.nio.file.{Path, Paths}
import java.time.Instant

/** Everything a service needs from rag and storage, wired once per process. jobs and queue carry
  * the async path (ruling 7): query-api creates jobs and sends messages, the worker runs them.
  */
final case class Backend(
    setup: RuntimeSetup,
    ports: RuntimePorts,
    evidence: EvidenceLookupPort,
    idempotency: IdempotencyStore,
    jobs: JobStore,
    queue: JobQueuePort
)

/** The local store files of fake mode, in the AskCli layout (graph and items under
  * snapshot=<date>/run=<runId>/); outputs (audits, runtime records, service state) go under out.
  * windowSummaries is the object store root holding the corpus window summaries (read only;
  * default: objects).
  */
final case class FakeStores(
    graph: Path,
    items: Path,
    objects: Path,
    vectors: Path,
    out: Path,
    windowSummaries: Option[Path] = None
)

/** The served runs of live mode (inputs of the runtime run ID, RUNTIME.md section 14). */
final case class LiveRun(
    target: LiveTarget,
    snapshot: String,
    graphRun: String,
    itemsRun: String,
    vectorsRun: String
)

/** The runtime composition root (ruling 13). Fake mode: the Phase 7 local stores, the rule planner
  * and template synthesizer, the hashing embedder; no AWS call. Live mode (never started by an
  * agent): Neptune, DynamoDB, S3, and S3 Vectors read through the read-only port sides (Phase 7
  * M6), Titan for the query embedding, and Haiku only when services.stub-llm is false.
  */
object Composition:
  private val monotonic: () => Long = () => Throttle.monotonicMs()

  /** answers false (graph-evidence-api) never opens the window-summary object (repair-01 m3). */
  def fake(
      cfg: ScholarChainConfig,
      s: FakeStores,
      out: String => Unit,
      answers: Boolean = true
  ): Either[String, Backend] =
    for
      model <- EmbeddingModels.forId(cfg.models.embeddingModel)
      prompts <- loadPrompts(cfg)
      graphSeg <- Cli.runSegments(s.graph).toRight(s"${s.graph} has no snapshot=/run= segments")
      itemsSeg <- Cli.runSegments(s.items).toRight(s"${s.items} has no snapshot=/run= segments")
      summaries <- windowSummaries(
        cfg,
        graphSeg._1,
        FileObjectStore(s.windowSummaries.getOrElse(s.objects)).reader,
        out,
        answers
      )
      graph = InMemoryGraph()
      objects = FileObjectStore(s.objects)
      _ <- graph.loader
        .load(s.graph, cfg.graph.windows, Some(objects.reader))
        .left
        .map(vs => s"the graph files failed validation: ${vs.take(3).mkString("; ")}")
      kv <- ItemFileReader
        .open(s.items, cfg.graph.windows)
        .left
        .map(vs => s"the item files failed validation: ${vs.take(3).mkString("; ")}")
    yield
      val (snapshot, graphRun) = graphSeg
      val version = EmbeddingModels.version(model, Templates.Version)
      val vectorFiles = Cli.files(s.vectors, ".jsonl")
      val runId = Workflow.runIdFor(
        cfg,
        Seq(
          s"graph=$graphRun",
          s"items=${itemsSeg._2}",
          s"vectors=${Cli.contentHash(s.vectors, vectorFiles)}",
          s"embedding=$version"
        ) ++ windowSummaryKey(cfg, snapshot).map(k => s"window-summary=$k"),
        prompts,
        RuntimeMode.FAKE
      )
      val runDir = s.out.resolve("dynamodb").resolve(s"snapshot=$snapshot").resolve(s"run=$runId")
      val audit = FileObjectStore(s.out.resolve("s3"))
      val records =
        FileRuntimeRecords(runDir, cfg.graph.windows, () => Instant.now.getEpochSecond)
      val state = LocalServiceStateTable(Some(runDir.resolve("ServiceState.jsonl")), Instant.now)
      out(s"fake mode: runtime run $runId, snapshot $snapshot, outputs under ${s.out}")
      Backend(
        setup(cfg, prompts, RuntimeMode.FAKE, None, version, graphRun, snapshot, runId),
        RuntimePorts(
          graph.reader(GraphReadLimits.from(cfg)),
          kv.reader,
          objects.reader,
          FileVectorStore(s.vectors).reader,
          HashingEmbedder(),
          audit.reader,
          audit.loader,
          records.writer,
          summaries
        ),
        records.evidenceLookup,
        IdempotencyStore(state, cfg.services.ttl.idempotencyHours, Instant.now),
        JobStore(state, cfg.services.ttl.jobHours, Instant.now),
        // shortcut: in memory, so only services in this JVM share it (LocalRun runs all three).
        InMemoryJobQueue(cfg.services.queue.maxReceiveCount)
      )

  def live(
      cfg: ScholarChainConfig,
      run: LiveRun,
      out: String => Unit,
      answers: Boolean = true
  ): Either[String, Backend] =
    for
      model <- EmbeddingModels.forId(cfg.models.embeddingModel)
      prompts <- loadPrompts(cfg)
      s3 = S3ObjectStore(
        LiveAws.s3(run.target, LiveAws.overridesWithTimeout(cfg.services.timeouts.s3Ms.toLong)),
        cfg.storage.bucket
      )
      summaries <- windowSummaries(cfg, run.snapshot, s3.reader, out, answers)
    yield
      val t = run.target
      val tm = cfg.services.timeouts
      def timeout(ms: Int) = LiveAws.overridesWithTimeout(ms.toLong)
      val r = cfg.services.resilience
      val policy = RetryPolicy(r.maxAttempts, r.baseDelayMs.toLong, r.maxDelayMs.toLong)
      val dynamo = LiveAws.dynamo(t, timeout(tm.dynamoDbMs))
      val prefix = cfg.dynamoDb.tablePrefix
      val store = DynamoStore(dynamo, prefix, cfg.graph.windows, () => Instant.now.getEpochSecond)
      val state = DynamoServiceStateTable(dynamo, prefix, Instant.now, policy)
      val version = EmbeddingModels.version(model, Templates.Version)
      val llmThrottle = Throttle(cfg.runtime.llm.requestsPerMinute, monotonic, Thread.sleep)
      val embedThrottle =
        Throttle(cfg.runtime.embedding.requestsPerMinute, monotonic, Thread.sleep)
      val llm = Option.unless(cfg.services.stubLlm)(
        BedrockHaikuClient(
          LiveAws.bedrock(t, timeout(cfg.runtime.llm.timeoutMs)),
          () => llmThrottle.acquire()
        )
      )
      val runId = Workflow.runIdFor(
        cfg,
        Seq(
          s"graph=${run.graphRun}",
          s"items=${run.itemsRun}",
          s"vectors=${run.vectorsRun}",
          s"embedding=$version"
        ) ++ windowSummaryKey(cfg, run.snapshot).map(k => s"window-summary=$k"),
        prompts,
        RuntimeMode.LIVE
      )
      out(
        s"live mode: runtime run $runId, snapshot ${run.snapshot}, model calls ${
            if llm.isEmpty then "stubbed" else "live"
          }"
      )
      Backend(
        setup(cfg, prompts, RuntimeMode.LIVE, llm, version, run.graphRun, run.snapshot, runId)
          .copy(llmThrottle = llmThrottle, embedThrottle = Some(embedThrottle)),
        RuntimePorts(
          NeptuneGraphReader(
            LiveAws.neptune(t, cfg.neptune.endpoint, cfg.neptune.port, timeout(tm.neptuneMs)),
            GraphReadLimits.from(cfg),
            policy
          ),
          store.reader,
          s3.reader,
          S3VectorStore(
            LiveAws.vectors(t, timeout(tm.s3VectorsMs)),
            cfg.s3Vectors.vectorBucket
          ).reader,
          BedrockTitanEmbedder(
            LiveAws.bedrock(t, timeout(cfg.runtime.embedding.timeoutMs)),
            model.modelId,
            () => embedThrottle.acquire()
          ),
          s3.reader,
          s3.loader, // audits are the runtime's only object writes
          store.runtimeWriter,
          summaries
        ),
        DynamoEvidenceLookup(dynamo, prefix, Instant.now, policy),
        IdempotencyStore(state, cfg.services.ttl.idempotencyHours, Instant.now),
        JobStore(state, cfg.services.ttl.jobHours, Instant.now),
        SqsJobQueue(LiveAws.sqs(t), cfg.services.queue.url, tm.sqsMs.toLong)
      )

  /** The served window-summary key: a runtime run ID input whether or not this service opens it. */
  def windowSummaryKey(cfg: ScholarChainConfig, snapshot: String): Option[String] =
    val run = cfg.runtime.windowSummary.loadRun
    Option.when(run.nonEmpty)(S3Layout.windowSummary(snapshot, run))

  /** The corpus window summaries (work order 11): the object S3Layout.windowSummary(snapshot,
    * runtime.window-summary.load-run), opened and validated once. An empty load run serves none
    * (corpus questions abstain). Any violation stops startup; the message names the key and the
    * violation codes only, never object contents. answers false (a service that answers no
    * questions) never opens it.
    */
  def windowSummaries(
      cfg: ScholarChainConfig,
      snapshot: String,
      objects: ObjectReadPort,
      out: String => Unit,
      answers: Boolean = true
  ): Either[String, Option[WindowSummarySource]] =
    windowSummaryKey(cfg, snapshot) match
      case None =>
        out("window summaries: none served (runtime.window-summary.load-run is empty)")
        Right(None)
      case Some(key) if !answers =>
        out(s"window summaries: $key not opened (this service answers no questions)")
        Right(None)
      case Some(key) =>
        WindowSummaries
          .open(objects, key, cfg.graph.windows)
          .left
          .map(vs =>
            val codes = vs.map(v => if v.line > 0 then s"${v.code} line ${v.line}" else v.code)
            s"the window-summary object $key was refused: ${codes.distinct.take(5).mkString(", ")}"
          )
          .map { p =>
            out(s"window summaries: $key")
            Some(WindowSummarySource(key, p))
          }

  private def loadPrompts(cfg: ScholarChainConfig) =
    PromptSet.load(
      Paths.get(cfg.runtime.promptsDir),
      cfg.runtime.planner.promptVersion,
      cfg.runtime.synthesis.promptVersion
    )

  private def setup(
      cfg: ScholarChainConfig,
      prompts: PromptSet,
      mode: RuntimeMode,
      llm: Option[scholarchain.rag.runtime.LlmClient],
      version: String,
      graphRun: String,
      snapshot: String,
      runId: String
  ) = RuntimeSetup(
    cfg,
    prompts,
    mode,
    llm,
    Throttle(cfg.runtime.llm.requestsPerMinute, monotonic, Thread.sleep),
    None,
    version,
    graphRun,
    snapshot,
    runId,
    monotonic,
    () => Instant.now
  )

/** Builds the HTTP services on a backend; the mains and the local runner use these. */
object Apps:
  /** Store calls and probes get `threads` threads (each HTTP thread holds at most one store call at
    * a time); sync workflows get max(1, threads / 2) slots and no queue, so at least half the HTTP
    * threads stay free for the fast routes (repair-01 F1; SERVICES.md section 2).
    */
  private def downstreams(cfg: ScholarChainConfig, threads: Int, metrics: Metrics) =
    Downstreams(
      cfg.services.resilience,
      threads,
      cfg.services.maxQueuedRequests,
      metrics,
      workflowThreads = math.max(1, threads / 2)
    )

  def queryApi(
      cfg: ScholarChainConfig,
      backend: Backend,
      auth: ApiKeyAuth,
      log: JsonLog,
      metrics: Metrics = Metrics()
  ): HttpService =
    val c = cfg.services.queryApi
    val ds = downstreams(cfg, c.threads, metrics)
    val q = QueryService(cfg, backend, ds, log, metrics, RateLimiter(c.queryRatePerMinute))
    HttpService(
      "query-api",
      q.routes ++ Ops.routes(q.readiness, ds, metrics, cfg.services.metricsEnabled),
      HttpSettings(c.threads, cfg.services.maxQueuedRequests, cfg.services.maxBodyBytes),
      auth,
      log,
      metrics,
      () => ds.shutdown()
    )

  def graphEvidenceApi(
      cfg: ScholarChainConfig,
      backend: Backend,
      auth: ApiKeyAuth,
      log: JsonLog,
      metrics: Metrics = Metrics()
  ): HttpService =
    val c = cfg.services.graphEvidenceApi
    val ds = downstreams(cfg, c.threads, metrics)
    val g = GraphEvidenceService(cfg, backend, ds)
    HttpService(
      "graph-evidence-api",
      g.routes ++ Ops.routes(g.readiness, ds, metrics, cfg.services.metricsEnabled),
      HttpSettings(c.threads, cfg.services.maxQueuedRequests, cfg.services.maxBodyBytes),
      auth,
      log,
      metrics,
      () => ds.shutdown()
    )

  /** The worker (ruling 7): the SQS poll loop, started with the HTTP service, plus health,
    * readiness, and metrics on services.worker.port.
    */
  def worker(
      cfg: ScholarChainConfig,
      backend: Backend,
      auth: ApiKeyAuth,
      log: JsonLog,
      metrics: Metrics = Metrics()
  ): HttpService =
    val c = cfg.services.worker
    val wk = Worker(cfg, backend, log, metrics)
    HttpService(
      "worker",
      Ops.routes(wk.readiness, wk.ds, metrics, cfg.services.metricsEnabled),
      HttpSettings(c.threads, cfg.services.maxQueuedRequests, cfg.services.maxBodyBytes),
      auth,
      log,
      metrics,
      () => wk.stop(),
      () => wk.start()
    )

/** Command line of the service mains. Fake mode binds 127.0.0.1; live mode (--live --profile, for
  * Phase 12 and later; agents never start it) binds all interfaces.
  */
object Launcher:
  val Usage: String =
    "--graph <neptune CSV run dir> --items <dynamodb item run dir> --objects <object store root> " +
      "--vectors <file vector store root> [--out <root, default scratch/services>] " +
      "[--window-summary-root <object store root, default --objects>] | " +
      "--live --profile <name> [--region <r>] --snapshot <date> --graph-run <runId> " +
      "--items-run <runId> --vectors-run <runId>"
  private val Opts = Set(
    "graph",
    "items",
    "objects",
    "vectors",
    "out",
    "profile",
    "region",
    "snapshot",
    "graph-run",
    "items-run",
    "vectors-run",
    "window-summary-root"
  )

  /** The backend the arguments name; Left is a usage or store problem. answers false: the service
    * answers no questions, so the window-summary object is not opened (repair-01 m3).
    */
  def backend(
      a: Args,
      cfg: ScholarChainConfig,
      out: String => Unit,
      answers: Boolean = true
  ): Either[String, Backend] =
    if a.flag("live") then
      for
        profile <- a.req("profile")
        snapshot <- a.req("snapshot")
        graphRun <- a.req("graph-run")
        itemsRun <- a.req("items-run")
        vectorsRun <- a.req("vectors-run")
        b <- Composition.live(
          cfg,
          LiveRun(
            LiveTarget(profile, a.get("region").getOrElse(cfg.region)),
            snapshot,
            graphRun,
            itemsRun,
            vectorsRun
          ),
          out,
          answers
        )
      yield b
    else
      for
        graph <- a.req("graph")
        items <- a.req("items")
        objects <- a.req("objects")
        vectors <- a.req("vectors")
        root = Paths.get(a.get("out").getOrElse("scratch/services"))
        _ <- Cli.freeDiskProblem(root).toLeft(())
        b <- Composition.fake(
          cfg,
          FakeStores(
            Paths.get(graph),
            Paths.get(items),
            Paths.get(objects),
            Paths.get(vectors),
            root,
            a.get("window-summary-root").map(Paths.get(_))
          ),
          out,
          answers
        )
      yield b

  /** Arguments, configuration, key, and backend; or the exit code (2 usage or configuration, 1
    * store).
    */
  def prepare(
      args: Seq[String],
      env: Map[String, String],
      out: String => Unit,
      answers: Boolean = true
  ): Either[Int, (Args, ScholarChainConfig, ApiKeyAuth, Backend)] =
    val parsed = for
      a <- Cli.parse(args, Set("live"), Opts)
      cfg <- ConfigLoader.load(env).left.map(_.mkString("; "))
      // Ruling 5: the key is read once from the variable configuration names, never logged.
      key <- env
        .get(cfg.services.apiKeyEnv)
        .filter(_.nonEmpty)
        .toRight(s"set the environment variable ${cfg.services.apiKeyEnv} to the API key")
    yield (a, cfg, ApiKeyAuth(key))
    parsed match
      case Left(e) =>
        out(s"usage error: $e\nusage: $Usage")
        Left(Cli.Usage)
      case Right((a, cfg, auth)) =>
        backend(a, cfg, out, answers) match
          case Left(e) =>
            out(s"failed: $e")
            Left(1)
          case Right(b) => Right((a, cfg, auth, b))

  /** Fake mode binds 127.0.0.1, live mode all interfaces. */
  def host(a: Args): String = if a.flag("live") then "0.0.0.0" else "127.0.0.1"

  /** Starts one service; returns it, or the exit code (2 usage or configuration, 1 store). */
  def start(
      args: Seq[String],
      build: (ScholarChainConfig, Backend, ApiKeyAuth, JsonLog) => HttpService,
      port: ScholarChainConfig => Int,
      env: Map[String, String] = sys.env,
      out: String => Unit = println,
      log: JsonLog = JsonLog.stdout,
      answers: Boolean = true
  ): Either[Int, HttpService] =
    prepare(args, env, out, answers).map { (a, cfg, auth, b) =>
      val svc = build(cfg, b, auth, log)
      val bound = svc.start(host(a), port(cfg))
      log.event(svc.name, "started", "port" -> bound)
      svc
    }

  def main(
      args: Array[String],
      build: (ScholarChainConfig, Backend, ApiKeyAuth, JsonLog) => HttpService,
      port: ScholarChainConfig => Int
  ): Unit = run(start(args.toSeq, build, port))

  /** Exits with the code, or stops the started service at JVM shutdown. */
  def run(started: Either[Int, HttpService]): Unit =
    started match
      case Left(code) => sys.exit(code)
      case Right(svc) => sys.addShutdownHook(svc.stop())
