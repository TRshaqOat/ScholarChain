package scholarchain.storage.cli

import scholarchain.core.StableIds
import scholarchain.storage.*
import scholarchain.storage.live.{LiveAws, NeptuneBulkLoader, S3ObjectStore}

import java.nio.file.{Path, Paths}
import scala.util.Try

/** Loads a Neptune openCypher CSV directory (graph/neptune/snapshot=<d>/run=<r>/).
  *
  * Dry run (default): validates and loads into the in-memory graph, --passes times. Live (--live
  * [--profile], --loader-role-arn; no --profile is the SDK default chain, an ECS task): with
  * --stage-from-bucket it first copies the run's objects from the bucket into <out>/s3
  * (stageFromBucket); then it validates the local copy, checks that the local copy and the live
  * bucket both hold exactly the run manifest's objects under that prefix, with the manifest's
  * SHA-256 (preloadProblems), then starts one bulk-load job. --status <loadId> reads a job's status
  * (no staging). Dry run only: --expect-coauthor <csv> looks up each expected COAUTHORED_WITH edge
  * after the last pass and exits 1 when one is missing or differs.
  */
object LoadNeptuneCli:
  val Stage = "load-neptune"
  val CodeVersion = "load-neptune-v1"
  val UsageText =
    "storage/runMain scholarchain.storage.cli.LoadNeptuneCli --dir <out>/s3/graph/neptune/snapshot=<d>/run=<r> " +
      "[--out scratch/stores] [--passes n] [--expect-coauthor <csv>] " +
      "[--live [--profile <name>] [--region <r>] " +
      "([--stage-from-bucket] [--loader-role-arn <arn>] | --status <loadId>)]"

  def main(args: Array[String]): Unit = Cli.main("LoadNeptuneCli", args)(run(_, _))

  /** --loader-role-arn, else the configured neptune.loader-role-arn (M10). A live run needs a
    * non-local configuration, which has already refused the placeholder ARN.
    */
  def loaderRoleArn(a: Args, cfg: scholarchain.core.config.ScholarChainConfig): String =
    a.get("loader-role-arn").getOrElse(cfg.neptune.loaderRoleArn)

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(
      args,
      Cli.LiveFlags + StageFlag,
      Cli.LiveOpts ++ Set("dir", "out", "passes", "loader-role-arn", "status", "expect-coauthor"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      val root = Paths.get(a.get("out").getOrElse("scratch/stores"))
      val s3root = root.resolve("s3")
      for
        live <- Cli.liveTarget(a, cfg.region, profileOptional = true)
        _ <- Either.cond(
          !a.flag(StageFlag) || (live.nonEmpty && a.get("status").isEmpty),
          (),
          s"--$StageFlag needs --live, and --status needs no staging"
        )
        _ <- if live.nonEmpty then Cli.liveConfig(cfg) else Right(())
        _ <- Either.cond(live.nonEmpty || a.get("status").isEmpty, (), "--status needs --live")
        _ <- Either.cond(
          live.isEmpty || a.get("expect-coauthor").isEmpty,
          (),
          "--expect-coauthor is dry-run only"
        )
        passes <- a.int("passes", Some(1)).filterOrElse(_ >= 1, "--passes must be at least 1")
        result <- (live, a.get("status")) match
          case (Some(t), Some(loadId)) =>
            val loader =
              NeptuneBulkLoader(LiveAws.neptune(t, cfg.neptune.endpoint, cfg.neptune.port))
            Right(
              loader.status(loadId) match
                case Left(e) =>
                  out(s"error: $e")
                  1
                case Right(s) =>
                  out(
                    s"load $loadId status ${s.status} records ${s.totalRecords} duplicates ${s.totalDuplicates}"
                  )
                  out(
                    s"errors parsing ${s.parsingErrors} datatype ${s.datatypeMismatchErrors} insert ${s.insertErrors}"
                  )
                  s.errorLogs.foreach(l => out(s"  $l"))
                  if !s.finished || s.succeeded then 0 else 1
            )
          case _ =>
            for
              dir <- a.req("dir").map(Paths.get(_))
              seg <- Cli.runSegments(dir).toRight("--dir must contain snapshot=<d>/run=<r>")
              (snapshot, sourceRun) = seg
            yield
              val prefix = s3root.toAbsolutePath.normalize
                .relativize(dir.toAbsolutePath.normalize)
                .toString
                .replace('\\', '/') + "/"
              // One S3 client for staging and the pre-load check; built only in a live run.
              lazy val bucket = live.map(t => S3ObjectStore(LiveAws.s3(t), cfg.storage.bucket))
              val staged = bucket
                .filter(_ => a.flag(StageFlag))
                .fold(Vector.empty[String])(b =>
                  stageFromBucket(b.reader, s3root, snapshot, sourceRun, prefix, out)
                )
              if staged.nonEmpty then
                staged.take(20).foreach(p => out(s"stage: $p"))
                out(s"staging refused (${staged.size} problems); nothing loaded")
                1
              else
                val scan = NeptuneCsv.scan(
                  dir,
                  cfg.graph.windows,
                  Some(FileObjectStore(s3root).reader)
                )
                scan.counts.toVector.sortBy(_._1).foreach((k, n) => out(s"count $k $n"))
                if scan.violations.nonEmpty then
                  Cli.report(scan.violations, 5, out)
                  out("nothing loaded")
                  1
                else
                  val runId = StableIds.runId(
                    Stage,
                    CodeVersion,
                    cfg.configVersion,
                    Seq(s"source=$sourceRun", Cli.contentHash(dir, Cli.files(dir, ".csv"))),
                    1
                  )
                  val ledger = FileRunLedger(root.resolve("ledger"))
                  live match
                    case None =>
                      val expect = a.get("expect-coauthor").map(Paths.get(_))
                      dryRun(dir, cfg, s3root, runId, passes, ledger, expect, out)
                    case Some(t) =>
                      val role = loaderRoleArn(a, cfg)
                      val remote = bucket.get
                      val localStore = FileObjectStore(s3root).reader
                      val manifestKey =
                        S3Layout.runManifest("store-conversion", snapshot, sourceRun)
                      val problems = localStore.get(manifestKey) match
                        case None    => Vector(s"no run manifest at $manifestKey")
                        case Some(b) =>
                          preloadProblems(
                            prefix,
                            sourceRun,
                            RunManifest.fromJson(b),
                            localStore
                              .list(prefix)
                              .map(k => k -> localStore.get(k).fold("")(Hashing.sha256))
                              .toMap,
                            remote.reader.list(prefix),
                            remote.sha256
                          )
                      if problems.nonEmpty then
                        problems.take(20).foreach(p => out(s"pre-load: $p"))
                        out(
                          s"pre-load check failed (${problems.size} problems); upload with LoadS3Cli --manifest $manifestKey first"
                        )
                        1
                      else
                        out(
                          s"LIVE: Neptune ${cfg.neptune.endpoint} loads s3://${cfg.storage.bucket}/$prefix"
                        )
                        NeptuneBulkLoader(
                          LiveAws.neptune(t, cfg.neptune.endpoint, cfg.neptune.port)
                        )
                          .start(cfg.storage.bucket, prefix, role, t.region) match
                          case Left(e) =>
                            out(s"error: $e")
                            1
                          case Right(loadId) =>
                            ledger.append(
                              LedgerEntry(
                                runId,
                                s"neptune#load#$loadId",
                                "NEPTUNE_LOAD",
                                Cli.now(),
                                Map("source" -> prefix)
                              )
                            )
                            out(
                              s"started load $loadId; check it with --live${t.profileName
                                  .fold("")(p => s" --profile $p")} --status $loadId"
                            )
                            0
      yield result
    }

  val StageFlag = "stage-from-bucket"

  /** Bound on one staging run, below the 30-minute task wait of scripts/load-stores.sh; each GET is
    * also bounded by the client's 120 s call timeout (LiveAws.overrides).
    */
  val StageTimeoutMs: Long = 20L * 60 * 1000

  /** --stage-from-bucket (an ECS task has no local copy). Copies from the bucket into s3root,
    * write-once: every object of the store-conversion run manifest under the load prefix and under
    * curated/coauthor-evidence/ of the same run, then the manifest itself, last. Only manifest keys
    * are read (no listing), so the object count and bytes are the manifest's, and they are printed
    * first. Each object is written only if it has the manifest's size and SHA-256. Transient GET
    * failures are retried (RetryPolicy.Default; a GET is idempotent). Empty means staged.
    */
  def stageFromBucket(
      remote: ObjectReadPort,
      s3root: Path,
      snapshot: String,
      sourceRun: String,
      prefix: String,
      out: String => Unit,
      timeoutMs: Long = StageTimeoutMs,
      now: () => Long = () => System.currentTimeMillis,
      sleep: Long => Unit = Cli.jitteredSleep
  ): Vector[String] =
    val manifestKey = S3Layout.runManifest("store-conversion", snapshot, sourceRun)
    val loadPrefix = S3Layout.neptuneRoot(snapshot, sourceRun) + "/"
    val prefixes =
      Vector(loadPrefix, S3Layout.base("curated", "coauthor-evidence", snapshot, sourceRun) + "/")
    val local = FileObjectStore(s3root).loader
    var retries = 0
    def get(k: String): Option[Array[Byte]] =
      val (b, attempts) = Retry.run(RetryPolicy.Default, sleep)(remote.get(k))
      retries += attempts - 1
      b
    def write(k: String, b: Array[Byte]): Option[String] = local.putNew(k, b) match
      case Left(ps)                            => Some(s"$k: ${ps.mkString("; ")}")
      case Right(PutObjectOutcome.Conflict(h)) => Some(s"local $k has $h; not overwritten")
      case Right(_)                            => None
    def copy(entry: ManifestEntry): Option[String] =
      val k = entry.key
      get(k) match
        case None => Some(s"$k is missing from the bucket")
        case Some(b) if b.length != entry.bytes || Hashing.sha256(b) != entry.sha256 =>
          Some(
            s"$k is ${Hashing.sha256(b)} (${b.length} bytes), " +
              s"manifest ${entry.sha256} (${entry.bytes} bytes)"
          )
        case Some(b) => write(k, b)
    if prefix != loadPrefix then Vector(s"--dir must be <out>/s3/$loadPrefix")
    else
      get(manifestKey) match
        case None     => Vector(s"no run manifest at $manifestKey in the bucket")
        case Some(mb) =>
          Try(RunManifest.fromJson(mb)).toEither match
            case Left(err) => Vector(s"unreadable manifest $manifestKey: ${err.getMessage}")
            case Right(m)
                if m.stage != "store-conversion" || m.runId != sourceRun || m.snapshot != snapshot =>
              Vector(s"manifest $manifestKey is ${m.stage} run ${m.runId} snapshot ${m.snapshot}")
            case Right(m) =>
              val entries = m.entries.filter(x => prefixes.exists(p => x.key.startsWith(p)))
              val bytes = entries.map(_.bytes).sum
              out(s"stage: ${entries.size} objects, $bytes bytes (from $manifestKey) into $s3root")
              Cli.freeDiskProblem(s3root, bytes + mb.length) match
                case Some(disk) => Vector(disk)
                case None       =>
                  val deadline = now() + timeoutMs
                  val done =
                    entries.iterator.takeWhile(_ => now() < deadline).map(copy).toVector
                  val problems = done.flatten ++ Option.when(done.size < entries.size)(
                    s"timeout after ${done.size} of ${entries.size} objects (${timeoutMs / 1000} s)"
                  )
                  val all = if problems.isEmpty then write(manifestKey, mb).toVector else problems
                  out(
                    s"stage: ${done.size} objects checked, retries $retries, problems ${all.size}"
                  )
                  all

  /** M3: the local copy and the live bucket must hold exactly the manifest's objects under prefix,
    * each with the manifest's SHA-256 (local: hashed here; live: the checksum S3 stored at upload,
    * read with HEAD). Empty means the bulk load may start.
    */
  def preloadProblems(
      prefix: String,
      sourceRun: String,
      manifest: RunManifest,
      localSha: Map[String, String],
      remoteKeys: Vector[String],
      remoteSha: String => Option[String]
  ): Vector[String] =
    val expected = manifest.entries.filter(_.key.startsWith(prefix)).map(e => e.key -> e.sha256)
    val want = expected.toMap
    def diff(name: String, have: Set[String]) =
      val missing = want.keySet -- have
      val extra = have -- want.keySet
      Option.when(missing.nonEmpty || extra.nonEmpty)(
        s"$name: ${missing.size} manifest objects missing, ${extra.size} not in the manifest" +
          (missing ++ extra).headOption.fold("")(k => s", first $k")
      )
    if manifest.runId != sourceRun then Vector(s"manifest run ${manifest.runId} is not $sourceRun")
    else if want.isEmpty then Vector(s"the manifest lists no objects under $prefix")
    else
      val remote = remoteKeys.toSet
      (diff("local copy", localSha.keySet) ++ diff("live bucket", remote)).toVector ++
        expected.flatMap((k, sha) =>
          Option.when(localSha.get(k).exists(_ != sha))(s"local $k is not $sha") ++
            Option.when(remote(k) && !remoteSha(k).contains(sha))(
              s"live $k has checksum ${remoteSha(k).getOrElse("none")}, manifest $sha"
            )
        )

  private def dryRun(
      dir: java.nio.file.Path,
      cfg: scholarchain.core.config.ScholarChainConfig,
      s3root: java.nio.file.Path,
      runId: String,
      passes: Int,
      ledger: RunLedgerPort,
      expect: Option[java.nio.file.Path],
      out: String => Unit
  ): Int =
    out("dry run: in-memory graph (no AWS call)")
    val g = InMemoryGraph()
    val bad = (1 to passes).count { pass =>
      g.loader.load(dir, cfg.graph.windows, Some(FileObjectStore(s3root).reader)) match
        case Left(vs) =>
          Cli.report(vs, 5, out)
          true
        case Right(r) =>
          val session = ledger.entries(runId).count(_.entryType == "LOAD_SESSION") + 1
          ledger.append(
            LedgerEntry(
              runId,
              f"session-$session%04d",
              "LOAD_SESSION",
              Cli.now(),
              Map(
                "verticesAdded" -> r.verticesAdded.toString,
                "edgesAdded" -> r.edgesAdded.toString,
                "verticesUnchanged" -> r.verticesUnchanged.toString,
                "edgesUnchanged" -> r.edgesUnchanged.toString
              )
            )
          )
          out(
            s"pass $pass verticesAdded ${r.verticesAdded} edgesAdded ${r.edgesAdded} " +
              s"verticesUnchanged ${r.verticesUnchanged} edgesUnchanged ${r.edgesUnchanged}"
          )
          false
    }
    val misses = if bad == 0 then
      expect.fold(0)(checkCoauthors(_, g.reader(GraphReadLimits.from(cfg)), out))
    else 0
    if bad == 0 && misses == 0 then 0 else 1

  val ExpectHeader = Vector(
    "window",
    "researcherX",
    "researcherY",
    "jointPublicationCount",
    "firstCollaborationYear",
    "lastCollaborationYear"
  )

  /** Looks up every expected COAUTHORED_WITH edge (CSV with ExpectHeader); returns the misses. */
  def checkCoauthors(file: java.nio.file.Path, g: GraphReadPort, out: String => Unit): Int =
    var bad = 0
    var header = true
    Csv.foreachRecord(file) { (line, row) =>
      if header then
        header = false
        if row != ExpectHeader then
          bad += 1
          out(s"edge-check $file: header must be ${ExpectHeader.mkString(",")}")
      else
        row match
          case Vector(w, x, y, n, first, last) =>
            val found =
              scala.util.Try(g.coauthorEdge(x, y, w)).toOption.flatten // unknown window: MISSING
            val got =
              found.map(e => ExpectHeader.drop(3).map(k => e.props.get(k).fold("")(_.toString)))
            val status =
              if got.contains(Vector(n, first, last)) then "OK"
              else if got.isEmpty then "MISSING"
              else "MISMATCH"
            if status != "OK" then bad += 1
            out(
              s"edge-check $status $w $x $y expected n=$n $first-$last" +
                found.zip(got).fold("")((e, v) => s" found ${e.id} n=${v(0)} ${v(1)}-${v(2)}")
            )
          case _ =>
            bad += 1
            out(s"edge-check $file line $line: expected ${ExpectHeader.size} fields")
    }
    out(s"edge-check misses $bad")
    bad
