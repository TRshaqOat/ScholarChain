package scholarchain.storage.cli

import scholarchain.core.StableIds
import scholarchain.storage.*
import scholarchain.storage.live.{LiveAws, NeptuneBulkLoader, S3ObjectStore}

import java.nio.file.Paths

/** Loads a Neptune openCypher CSV directory (graph/neptune/snapshot=<d>/run=<r>/).
  *
  * Dry run (default): validates and loads into the in-memory graph, --passes times. Live (--live
  * --profile, --loader-role-arn): validates the local copy, checks that the local copy and the live
  * bucket both hold exactly the run manifest's objects under that prefix, with the manifest's
  * SHA-256 (preloadProblems), then starts one bulk-load job. --status <loadId> reads a job's
  * status. Dry run only: --expect-coauthor <csv> looks up each expected COAUTHORED_WITH edge after
  * the last pass and exits 1 when one is missing or differs.
  */
object LoadNeptuneCli:
  val Stage = "load-neptune"
  val CodeVersion = "load-neptune-v1"
  val UsageText =
    "storage/runMain scholarchain.storage.cli.LoadNeptuneCli --dir <out>/s3/graph/neptune/snapshot=<d>/run=<r> " +
      "[--out scratch/stores] [--passes n] [--expect-coauthor <csv>] " +
      "[--live --profile <name> [--region <r>] ([--loader-role-arn <arn>] | --status <loadId>)]"

  def main(args: Array[String]): Unit = Cli.main("LoadNeptuneCli", args)(run(_, _))

  /** --loader-role-arn, else the configured neptune.loader-role-arn (M10). A live run needs a
    * non-local configuration, which has already refused the placeholder ARN.
    */
  def loaderRoleArn(a: Args, cfg: scholarchain.core.config.ScholarChainConfig): String =
    a.get("loader-role-arn").getOrElse(cfg.neptune.loaderRoleArn)

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(
      args,
      Cli.LiveFlags,
      Cli.LiveOpts ++ Set("dir", "out", "passes", "loader-role-arn", "status", "expect-coauthor"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      val root = Paths.get(a.get("out").getOrElse("scratch/stores"))
      val s3root = root.resolve("s3")
      for
        live <- Cli.liveTarget(a, cfg.region)
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
                    val prefix = s3root.toAbsolutePath.normalize
                      .relativize(dir.toAbsolutePath.normalize)
                      .toString
                      .replace('\\', '/') + "/"
                    val localStore = FileObjectStore(s3root).reader
                    val manifestKey =
                      S3Layout.runManifest("store-conversion", snapshot, sourceRun)
                    val remote = S3ObjectStore(LiveAws.s3(t), cfg.storage.bucket)
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
                            s"started load $loadId; check it with --live --profile ${t.profile} --status $loadId"
                          )
                          0
      yield result
    }

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
