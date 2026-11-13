package scholarchain.services

import scholarchain.core.StableIds
import scholarchain.rag.EmbeddingModels
import scholarchain.storage.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
import java.util.Comparator
import scala.collection.mutable

/** A small store world written as Phase 7 store files (a trimmed copy of the rag RuntimeFixtures
  * world, which services tests cannot import): "Ada Lovelace" (dominant over a one-work namesake),
  * "Grace Hopper", and "Edsger Dijkstra"; coauthor edges ada - grace (W1, W2) and grace - edsger
  * (W12); communities c1 = {ada, grace} and c2 = {edsger}. Services are started on 127.0.0.1:0 and
  * called over a real socket.
  */
object ServiceFixtures:
  val ApiKey = "test-key-5f1b2c9d8e7a6b5c4d3e2f1a"
  val baseEnv: Map[String, String] = Map(
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> EmbeddingModels.TitanTextV2.modelId,
    "SCHOLARCHAIN_API_KEY" -> ApiKey
  )
  val snapshot = "2026-10-03"
  val graphRun: String = StableIds.runId("svc-test-graph", "c", "config-v1", Seq("g"), 1)
  val canonRun: String = StableIds.runId("svc-test-canon", "c", "config-v1", Seq("c"), 1)
  val metricRun: String = StableIds.runId("svc-test-metrics", "c", "config-v1", Seq("m"), 1)
  val policy = "graph-v1"
  def rid(s: String) = StableIds.researcherId(None, "canon-v1.er-v2", s)
  val ada = rid("ada")
  val ada2 = rid("ada2")
  val grace = rid("grace")
  val edsger = rid("edsger")
  val names = Map(
    ada -> "Ada Lovelace",
    ada2 -> "Ada Lovelace",
    grace -> "Grace Hopper",
    edsger -> "Edsger Dijkstra"
  )
  def w(i: Int) = s"dblp:conf/fx/W$i"
  val works: Vector[(String, Int, Vector[String])] =
    (1 to 10)
      .map(i => (w(i), 2000 + i, if i <= 2 then Vector(ada, grace) else Vector(ada)))
      .toVector ++
      Vector((w(11), 2011, Vector(ada2)), (w(12), 2012, Vector(grace, edsger))) ++
      (13 to 15).map(i => (w(i), 2000 + i, Vector(grace))) ++
      (16 to 19).map(i => (w(i), 2000 + i, Vector(edsger)))
  val c1 = StableIds.communityId(policy, "all-years", 2000, 2026, ada)
  val c2 = StableIds.communityId(policy, "all-years", 2000, 2026, edsger)
  val topic = "openalex:T1"
  val inst = "ror:000000001"
  val component = "comp-" + StableIds.hash("component", "1")

  private def prov(spec: FileSpec): Map[String, Prop] =
    Map[String, Prop](
      "runId" -> graphRun,
      "snapshot" -> snapshot,
      "schemaVersion" -> spec.schemaVersion
    ) ++
      (if spec.kind == ElementKind.Edge then Map[String, Prop]("policyVersion" -> policy)
       else Map.empty)
  private def vertex(spec: FileSpec, id: String, props: (String, Prop)*) =
    GraphElement(id, spec.label, "", "", prov(spec) ++ props)
  private def edge(spec: FileSpec, from: String, to: String, props: (String, Prop)*) =
    GraphElement(
      NeptuneContract.edgeId(spec.label, from, to, policy),
      spec.label,
      from,
      to,
      prov(spec) ++ props
    )
  private val windowProps =
    Seq[(String, Prop)]("window" -> "all-years", "windowStartYear" -> 2000, "windowEndYear" -> 2026)
  private def coauthor(x: String, y: String, ws: Vector[(String, Int)]) =
    val (a, b) = if x < y then (x, y) else (y, x)
    GraphElement(
      StableIds.coauthorEdgeId(a, b, 2000, 2026, policy).toOption.get,
      "COAUTHORED_WITH",
      a,
      b,
      prov(NeptuneContract.CoauthoredWith) ++ windowProps ++ Map[String, Prop](
        "jointPublicationCount" -> ws.size.toLong,
        "firstCollaborationYear" -> ws.map(_._2).min,
        "lastCollaborationYear" -> ws.map(_._2).max,
        "supportingWorkIds" -> ws.map(_._1).sorted.mkString(" ")
      )
    )

  def graphFiles: Map[FileSpec, Vector[GraphElement]] =
    import NeptuneContract.*
    Map(
      Researcher -> names.toVector.map((id, n) =>
        vertex(
          Researcher,
          id,
          "displayName" -> n,
          "mentionCount" -> 3L,
          "dblpAnchored" -> true,
          "assignedTier" -> "TIER1",
          "identityConfidence" -> 0.99
        )
      ),
      Work -> works.map((id, y, _) =>
        vertex(Work, id, "title" -> s"Paper ${id.drop(13)}", "year" -> y, "dblpAnchored" -> true)
      ),
      Topic -> Vector(vertex(Topic, topic, "name" -> "Cloud Computing Systems")),
      Institution -> Vector(vertex(Institution, inst, "name" -> "Analytical Engine Institute")),
      Community -> Vector(c1 -> (ada, 2L), c2 -> (edsger, 1L)).map { case (c, (label, size)) =>
        vertex(
          Community,
          c,
          (windowProps ++ Seq[(String, Prop)](
            "communitySize" -> size,
            "internalEdges" -> (size - 1),
            "crossCommunityEdges" -> 1L,
            "communityLabel" -> label
          ))*
        )
      },
      Authored -> works.flatMap((id, y, rs) =>
        rs.zipWithIndex.map((r, k) => edge(Authored, r, id, "position" -> (k + 1), "year" -> y))
      ),
      HasTopic -> Vector(edge(HasTopic, w(1), topic), edge(HasTopic, w(12), topic)),
      AffiliatedWith -> Vector(edge(AffiliatedWith, ada, inst, "via" -> "MENTION")),
      CoauthoredWith -> Vector(
        coauthor(ada, grace, Vector(w(1) -> 2001, w(2) -> 2002)),
        coauthor(grace, edsger, Vector(w(12) -> 2012))
      ),
      MemberOf -> Vector(ada -> c1, grace -> c1, edsger -> c2).map((r, c) =>
        edge(MemberOf, r, c, windowProps*)
      )
    )

  private def S(v: String) = AttributeValue.S(v)
  private def N(v: Any) = AttributeValue.N(v.toString)
  private def provItem(run: String, schema: String, pol: String) =
    Map(
      "runId" -> S(run),
      "snapshot" -> S(snapshot),
      "schemaVersion" -> S(schema),
      "policyVersion" -> S(pol)
    )

  def allItems: Vector[(TableName, Item)] =
    val links = names.toVector.map((id, n) =>
      Map(
        "nameKey" -> S(DynamoContract.nameKey(n).get),
        "researcherId" -> S(id),
        "displayName" -> S(n),
        "matchedName" -> S(n),
        "identityConfidence" -> N("0.99"),
        "mentionCount" -> N(3),
        "dblpAnchored" -> AttributeValue.Bool(true)
      ) ++ provItem(canonRun, "entity-link-v1", "canon-v1.er-v2")
    )
    val metrics = Vector(
      (ada, 10, 1, "2.0", c1, "0.0", 1, 0),
      (grace, 6, 2, "3.0", c1, "0.5", 2, 1),
      (edsger, 5, 1, "1.0", c2, "0.0", 2, 1)
    )
      .map { (id, p, dc, wd, c, pc, cc, cd) =>
        Map(
          "researcherId" -> S(id),
          "metricKey" -> S("WINDOW#all-years"),
          "window" -> S("all-years"),
          "windowStartYear" -> N(2000),
          "windowEndYear" -> N(2026),
          "publicationCount" -> N(p),
          "distinctCoauthorCount" -> N(dc),
          "totalCoauthorOccurrences" -> N(dc),
          "averageCoauthorsPerPaper" -> N("0.3"),
          "maximumCoauthorsOnOnePaper" -> N(1),
          "weightedDegree" -> N(wd),
          "componentId" -> S(component),
          "componentSize" -> N(3),
          "coreness" -> N(1),
          "graphDegree" -> N(dc),
          "maximumTeamWorkIds" -> AttributeValue.L(Vector(S(if id == edsger then w(12) else w(1)))),
          "communityId" -> S(c),
          "communitySize" -> N(if c == c1 then 2 else 1),
          "crossCommunityDegree" -> N(cd),
          "communitiesConnected" -> N(cc),
          "participationCoefficient" -> N(pc),
          "communityRunId" -> S(metricRun)
        ) ++ provItem(metricRun, "researcher-metrics-window-v1", policy)
      }
    val ranking = Map(
      "researcherId" -> S("RANKING#distinctCoauthorCount"),
      "metricKey" -> S("TOP#all-years"),
      "metric" -> S("distinctCoauthorCount"),
      "window" -> S("all-years"),
      "windowStartYear" -> N(2000),
      "windowEndYear" -> N(2026),
      "entries" -> AttributeValue.L(
        Vector(grace -> 2, ada -> 1, edsger -> 1).sortBy((id, v) => (-v, id)).zipWithIndex.map {
          case ((id, v), k) =>
            AttributeValue.M(Map("rank" -> N(k + 1), "researcherId" -> S(id), "value" -> N(v)))
        }
      )
    ) ++ provItem(metricRun, "researcher-ranking-v1", policy)
    links.map(TableName.EntityLinkCache -> _) ++
      (metrics :+ ranking).map(TableName.ResearcherMetricCache -> _)

  def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      val s = Files.walk(p)
      try s.sorted(Comparator.reverseOrder()).forEach(Files.delete(_))
      finally s.close()

  /** Writes the store files and returns the fake-mode arguments of the service mains. */
  def writeStores(root: Path): Vector[String] =
    val seg = s"snapshot=$snapshot/run=$graphRun"
    graphFiles.foreach { (spec, elems) =>
      val dir = root.resolve(s"graph/$seg").resolve(spec.dir).resolve(spec.name)
      Files.createDirectories(dir)
      Files.writeString(
        dir.resolve("part-00000.csv"),
        (spec.header +: elems
          .map(NeptuneContract.renderRow(spec, _))).map(Csv.render).mkString("\n") + "\n",
        UTF_8
      )
    }
    allItems.groupMap(_._1)(_._2).foreach { (t, items) =>
      val dir = root.resolve(s"items/$seg").resolve(t.toString)
      Files.createDirectories(dir)
      Files.writeString(
        dir.resolve("part-00000.jsonl"),
        items.map(DynamoItemFile.renderLine).mkString("\n") + "\n",
        UTF_8
      )
    }
    Files.createDirectories(root.resolve("s3"))
    Files.createDirectories(root.resolve("vectors"))
    Vector(
      "--graph" -> s"graph/$seg",
      "--items" -> s"items/$seg",
      "--objects" -> "s3",
      "--vectors" -> "vectors",
      "--out" -> "out"
    ).flatMap((k, v) => Vector(k, root.resolve(v).toString))

  // ---- corpus window summaries (work order 11): one fixture row per configured window ----
  val summaryRun: String = StableIds.runId("svc-test-window-summary", "c", "config-v1", Seq("s"), 1)
  val summaryLoadRun: String =
    StableIds.runId("svc-test-load-window-summary", "c", "config-v1", Seq("l"), 1)
  val summaryKey: String = S3Layout.windowSummary(snapshot, summaryLoadRun)

  /** (window, researchers, components, largest component, singletons, communities, largest
    * community, singleton communities, communities of at least 3)
    */
  val summaryCounts = Vector(
    ("all-years", 1200L, 45L, 900L, 28L, 130L, 60L, 31L, 64L),
    ("2000-2014", 400L, 33L, 252L, 13L, 71L, 21L, 11L, 37L),
    ("2015-2026", 800L, 22L, 541L, 9L, 90L, 40L, 12L, 47L)
  )
  def summaryRows(windows: Seq[scholarchain.core.config.TimeWindow]): Vector[WindowSummary] =
    summaryCounts.map { (win, r, comps, lc, single, comms, lcm, singleComm, atLeast) =>
      val tw = windows.find(_.name == win).get
      WindowSummary(
        win,
        tw.startYear,
        tw.endYear,
        r,
        r * 2,
        r * 3,
        comps,
        lc,
        single,
        comms,
        lcm,
        singleComm,
        atLeast,
        Some("comp-" + StableIds.hash("component", win)),
        Some(StableIds.communityId(policy, win, tw.startYear, tw.endYear, ada)),
        tw.endYear - tw.startYear + 1,
        Some(lc.toDouble / r.toDouble),
        Some(lcm.toDouble / r.toDouble),
        3,
        false,
        10,
        "researchers with at least one fixture publication in the window",
        WindowSummarySourceRuns(canonRun, metricRun, metricRun, graphRun, canonRun, "er-v2"),
        scholarchain.core.Provenance(
          scholarchain.core.SchemaVersions.Provenance,
          scholarchain.core.SourceType.DERIVED,
          win,
          snapshot,
          summaryRun,
          Some(policy),
          None
        ),
        WindowSummaries.SchemaVersion
      )
    }

  /** Writes bytes as the window-summary object under root; returns the LocalRun arguments. */
  def writeSummaries(root: Path, bytes: Array[Byte]): Vector[String] =
    val f = root.resolve(summaryKey)
    Files.createDirectories(f.getParent)
    Files.write(f, bytes)
    Vector("--window-summary-root", root.toString)

  /** Every log line any test service wrote (the leak test scans them). */
  val logLines: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val log: JsonLog = JsonLog(l => logLines.synchronized(logLines += l))
  def logged: Vector[String] = logLines.synchronized(logLines.toVector)

  /** Every response body and header value any test client received. */
  val seen: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

  final case class Reply(status: Int, body: ujson.Value, headers: Map[String, String]):
    def error: String = body("error").str

  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  final class Client(port: Int):
    def send(
        method: String,
        path: String,
        body: Option[String] = None,
        headers: Seq[(String, String)] =
          Seq("X-Api-Key" -> ApiKey, "Content-Type" -> "application/json")
    ): Reply =
      val b = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .timeout(Duration.ofSeconds(30))
        .method(
          method,
          body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
        )
      headers.foreach((k, v) => b.header(k, v))
      val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
      val hs = Map.from(
        Seq("X-Request-Id", "X-Trace-Id", "Idempotent-Replay", "Retry-After", "Content-Type")
          .flatMap(h => Option(r.headers.firstValue(h).orElse(null)).map(h -> _))
      )
      seen.synchronized(seen ++= (r.body +: hs.values.toSeq))
      Reply(r.statusCode, if r.body.isEmpty then ujson.Null else ujson.read(r.body), hs)
    def get(path: String, headers: Seq[(String, String)] = Seq("X-Api-Key" -> ApiKey)): Reply =
      send("GET", path, None, headers)
    def post(path: String, body: String, headers: Seq[(String, String)] = Seq()): Reply =
      send(
        "POST",
        path,
        Some(body),
        Seq("X-Api-Key" -> ApiKey, "Content-Type" -> "application/json") ++ headers
      )

  /** The store files, the configuration (HOCON overrides under scholarchain), and a backend from
    * the fake composition root.
    */
  final class World(val root: Path, hocon: String, summaries: Boolean = false):
    val env: Map[String, String] =
      if hocon.isEmpty then baseEnv
      else
        val f = root.resolve("test.conf")
        Files.writeString(f, s"scholarchain { $hocon }")
        baseEnv + ("SCHOLARCHAIN_CONFIG_FILE" -> f.toString)
    val cfg =
      scholarchain.core.config.ConfigLoader.load(env).fold(e => sys.error(e.mkString), identity)
    // summaries: the fixture window-summary object under its own root, as the smoke passes it.
    val args: Vector[String] = writeStores(root) ++ (
      if summaries then
        writeSummaries(
          root.resolve("window-summary"),
          WindowSummaries.render(summaryRows(cfg.graph.windows))
        )
      else Vector()
    )
    val backend: Backend = Launcher
      .backend(
        scholarchain.storage.cli.Cli
          .parse(args, Set("live"), args.collect { case s"--$o" => o }.toSet)
          .toOption
          .get,
        cfg,
        _ => ()
      )
      .fold(sys.error, identity)
    val auth = ApiKeyAuth(ApiKey)

    /** Starts a service on 127.0.0.1:0, runs body with a client, and always stops it. */
    def serve[A](svc: HttpService)(body: Client => A): A =
      val port = svc.start("127.0.0.1", 0)
      try body(Client(port))
      finally svc.stop()
    def query[A](b: Backend = backend, metrics: Metrics = Metrics())(body: Client => A): A =
      serve(Apps.queryApi(cfg, b, auth, log, metrics))(body)
    def graphEvidence[A](b: Backend = backend)(body: Client => A): A =
      serve(Apps.graphEvidenceApi(cfg, b, auth, log))(body)

  def withWorld[A](hocon: String = "", summaries: Boolean = false)(body: World => A): A =
    val root = Files.createTempDirectory("services-test-")
    try body(World(root, hocon, summaries))
    finally deleteTree(root)

  val Question = "Have Ada Lovelace and Grace Hopper coauthored a paper?"
  val Async = ", \"execution\": \"async\""

  /** An error reply in the spec error model, with the IDs echoed in the headers. */
  def expectError(r: Reply, code: String, status: Int): Unit =
    import org.scalatest.Assertions.*
    assert(r.status == status, r.body)
    assert(r.error == code, r.body)
    assert(r.headers.get("X-Trace-Id").contains(r.body("traceId").str))
    assert(r.headers.get("X-Request-Id").contains(r.body("requestId").str))
    assert(r.body("retryable").bool == scholarchain.core.ErrorCode.valueOf(code).retryable)
    assert(r.body.obj.keySet == Set("error", "message", "traceId", "requestId", "retryable"))

  /** A settable clock for the fake queue's visibility timeouts. */
  final class Clock:
    @volatile var now: Instant = Instant.now()
    def advance(seconds: Long): Unit = now = now.plusSeconds(seconds)

  /** b with a fresh in-memory queue on clock (max receive count from cfg). */
  def withQueue(
      b: Backend,
      cfg: scholarchain.core.config.ScholarChainConfig,
      clock: Clock
  ): (Backend, InMemoryJobQueue) =
    val q = InMemoryJobQueue(cfg.services.queue.maxReceiveCount, () => clock.now)
    (b.copy(queue = q), q)

  /** A QUEUED job and its message, as query-api's async path writes them; (queryId, jobId). */
  def submit(
      b: Backend,
      question: String = Question,
      request: Option[String] = None
  ): (String, String) =
    val queryId = StableIds.queryId(b.setup.runId, StableIds.textHash(question), Ids.fresh("t"), 1)
    val body = request.getOrElse(upickle.default.write(QueryBody(question)))
    assert(b.jobs.create(queryId, body).isRight)
    b.queue.send(JobMessage.of(queryId))
    (queryId, StableIds.jobId(queryId))

  def jobOf(b: Backend, jobId: String): JobRecord = b.jobs.get(jobId).toOption.flatten.get
  def queryJson(question: String = Question, extra: String = ""): String =
    s"""{"question": ${ujson.write(ujson.Str(question))}$extra}"""
