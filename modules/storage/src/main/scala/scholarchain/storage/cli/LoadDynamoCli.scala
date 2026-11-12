package scholarchain.storage.cli

import scholarchain.core.StableIds
import scholarchain.storage.*
import scholarchain.storage.live.{DynamoStore, LiveAws}

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Loads DynamoDB item files (curated/dynamodb/snapshot=<d>/run=<r>/<Table>/part-*.jsonl).
  *
  * Dry run (default): the in-memory fake, --passes times in one process, ledger under <out>/ledger.
  * Live (--live --profile; --table-prefix defaults to dynamodb.table-prefix): conditional PutItem
  * per item, --concurrency threads, RunLedger table. Every file is validated first; any violation
  * stops before a write. A table whose content hash ArtifactRegistry already records for this
  * source run is skipped unless --recheck.
  */
object LoadDynamoCli:
  val Stage = "load-dynamodb"
  val CodeVersion = "load-dynamodb-v1"
  val UsageText =
    "storage/runMain scholarchain.storage.cli.LoadDynamoCli --dir <.../curated/dynamodb/snapshot=<d>/run=<r>> " +
      "[--out scratch/stores] [--passes n] [--recheck] " +
      "[--live --profile <name> [--region <r>] [--table-prefix <p>] [--concurrency n]]"

  def main(args: Array[String]): Unit = Cli.main("LoadDynamoCli", args)(run(_, _))

  def registryKey(t: TableName): String = s"loaded/dynamodb/$t"

  /** --table-prefix, else the configured dynamodb.table-prefix (M10). */
  def tablePrefix(a: Args, cfg: scholarchain.core.config.ScholarChainConfig): String =
    a.get("table-prefix").getOrElse(cfg.dynamoDb.tablePrefix)

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(
      args,
      Cli.LiveFlags + "recheck",
      Cli.LiveOpts ++ Set("dir", "out", "passes", "table-prefix", "concurrency"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        dir <- a.req("dir").map(Paths.get(_))
        seg <- Cli
          .runSegments(dir)
          .toRight("--dir must contain snapshot=<d>/run=<r>")
        (snapshot, sourceRun) = seg
        live <- Cli.liveTarget(a, cfg.region)
        _ <- if live.nonEmpty then Cli.liveConfig(cfg) else Right(())
        prefix = tablePrefix(a, cfg)
        passes <- a
          .int("passes", Some(1))
          .filterOrElse(
            p => p >= 1 && (live.isEmpty || p == 1),
            "--passes is 1 to n, and 1 when live"
          )
        conc <- a
          .int("concurrency", Some(8))
          .filterOrElse(c => c >= 1 && c <= 32, "--concurrency 1 to 32")
        tables <- DynamoItemFile.tableDirs(dir)
      yield
        val root = Paths.get(a.get("out").getOrElse("scratch/stores"))
        val windows = cfg.graph.windows
        val violations =
          tables.flatMap((t, d) => DynamoItemFile.validate(t, Cli.files(d, ".jsonl"), windows)._1)
        if violations.nonEmpty then
          Cli.report(violations, 5, out)
          out("nothing loaded")
          1
        else
          Cli.freeDiskProblem(root) match
            case Some(p) =>
              out(p)
              Cli.LowDisk
            case None =>
              val now = () => java.time.Instant.now.getEpochSecond
              val (reader, loader, ledger, threads) = live match
                case Some(t) =>
                  out(s"LIVE: DynamoDB tables $prefix* in ${t.region}, profile ${t.profile}")
                  val s = DynamoStore(LiveAws.dynamo(t), prefix, windows, now)
                  (s.reader, s.loader, KeyValueRunLedger(s.reader, s.loader), conc)
                case None =>
                  out("dry run: in-memory DynamoDB fake (no AWS call)")
                  val s = InMemoryDynamo(windows, now)
                  (s.reader, s.loader, FileRunLedger(root.resolve("ledger")), 1)
              val all = tables.flatMap((_, d) => Cli.files(d, ".jsonl"))
              val runId = StableIds.runId(
                Stage,
                CodeVersion,
                cfg.configVersion,
                Seq(s"source=$sourceRun", Cli.contentHash(dir, all)),
                1
              )
              out(s"load run $runId from source run $sourceRun snapshot $snapshot")
              val sleep: Long => Unit = if live.nonEmpty then Cli.jitteredSleep else Thread.sleep
              val pool = Executors.newFixedThreadPool(threads)
              given ExecutionContext = ExecutionContext.fromExecutorService(pool)
              try
                val bad = (1 to passes).map { pass =>
                  val session = ledger.entries(runId).count(_.entryType == "LOAD_SESSION") + 1
                  ledger.append(
                    LedgerEntry(
                      runId,
                      f"session-$session%04d",
                      "LOAD_SESSION",
                      Cli.now(),
                      Map("source" -> sourceRun)
                    )
                  )
                  tables.map { (t, d) =>
                    val r = loadTable(
                      t,
                      d,
                      dir,
                      snapshot,
                      sourceRun,
                      reader,
                      loader,
                      a.flag("recheck"),
                      sleep
                    )
                    ledger.append(
                      LedgerEntry(
                        runId,
                        f"session-$session%04d#$t",
                        "LOAD_TABLE",
                        Cli.now(),
                        r.details
                      )
                    )
                    out(
                      s"pass $pass $t ${r.details.toVector.sorted.map((k, v) => s"$k=$v").mkString(" ")}"
                    )
                    r.conflicts + r.invalid + r.registryConflict
                  }.sum
                }.sum
                if bad == 0 then 0 else 1
              finally pool.shutdown()
    }

  final case class TableResult(
      inserted: Int,
      unchanged: Int,
      conflicts: Int,
      invalid: Int,
      registry: String,
      registryConflict: Int
  ):
    def details: Map[String, String] = Map(
      "inserted" -> inserted.toString,
      "unchanged" -> unchanged.toString,
      "conflict" -> conflicts.toString,
      "invalid" -> invalid.toString,
      "registry" -> registry
    )

  private def loadTable(
      t: TableName,
      d: Path,
      dir: Path,
      snapshot: String,
      sourceRun: String,
      reader: KeyValueReadPort,
      loader: KeyValueLoadPort,
      recheck: Boolean,
      sleep: Long => Unit
  )(using ExecutionContext): TableResult =
    import AttributeValue.*
    val fs = Cli.files(d, ".jsonl")
    val hash = Cli.contentHash(dir, fs)
    val versionKey = s"RUN#$sourceRun"
    val existing = reader.get(TableName.ArtifactRegistry, registryKey(t), versionKey)
    val recorded = existing.flatMap(_.get("contentHash"))
    if recorded.exists(_ != S(hash)) then TableResult(0, 0, 0, 0, "CONFLICT", 1)
    else if recorded.nonEmpty && !recheck then TableResult(0, 0, 0, 0, "SKIPPED_SAME_CONTENT", 0)
    else
      var counts = Map.empty[String, Int].withDefaultValue(0)
      var rows = 0L
      def put(item: Item): String =
        Retry.run(RetryPolicy.Default, sleep)(loader.putNew(t, item))._1 match
          case Right(o) => o.toString
          case Left(_)  => "Invalid"
      fs.foreach { f =>
        Using.resource(Files.lines(f))(
          _.iterator.asScala.filter(_.trim.nonEmpty).grouped(1000).foreach { group =>
            val items = group.map(l =>
              DynamoItemFile.parseLine(l).fold(e => throw IllegalStateException(e), identity)
            )
            rows += items.size
            val outcomes = Await.result(Future.traverse(items)(i => Future(put(i))), Duration.Inf)
            outcomes.foreach(o => counts = counts.updated(o, counts(o) + 1))
          }
        )
      }
      val clean = counts("Conflict") == 0 && counts("Invalid") == 0
      val registry =
        if !clean then "NOT_RECORDED"
        else if existing.nonEmpty then "ALREADY_RECORDED"
        else
          loader.putNew(
            TableName.ArtifactRegistry,
            Map(
              "artifactKey" -> S(registryKey(t)),
              "versionKey" -> S(versionKey),
              "runId" -> S(sourceRun),
              "snapshot" -> S(snapshot),
              "schemaVersion" -> S("artifact-registry-v1"),
              "manifestKey" -> S(S3Layout.runManifest("store-conversion", snapshot, sourceRun)),
              "contentHash" -> S(hash),
              "rowCount" -> AttributeValue.num(rows)
            )
          ) match
            case Right(LoadOutcome.Inserted) => "RECORDED"
            case other                       => s"REGISTRY_$other"
      TableResult(
        counts("Inserted"),
        counts("Unchanged"),
        counts("Conflict"),
        counts("Invalid"),
        registry,
        0
      )
