package scholarchain.storage.cli

import scholarchain.storage.*
import scholarchain.storage.live.{LiveAws, S3ObjectStore}

import java.nio.file.Paths

/** Copies the objects of one run manifest from the file object store (<root>, default
  * scratch/stores/s3) to the environment bucket. Dry run (default) only verifies the manifest
  * locally. Live (--live [--profile]): verifies locally, writes every object write-once
  * (If-None-Match), writes the manifest last, then verifies the manifest against the bucket.
  */
object LoadS3Cli:
  val UsageText =
    "storage/runMain scholarchain.storage.cli.LoadS3Cli --manifest runs/<stage>/snapshot=<d>/run=<r>/manifest.json " +
      "[--root scratch/stores/s3] [--live [--profile <name>] [--region <r>]]"

  def main(args: Array[String]): Unit = Cli.main("LoadS3Cli", args)(run(_, _))

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(
      args,
      Cli.LiveFlags,
      Cli.LiveOpts ++ Set("manifest", "root"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        manifestKey <- a.req("manifest")
        live <- Cli.liveTarget(a, cfg.region, profileOptional = true)
        _ <- if live.nonEmpty then Cli.liveConfig(cfg) else Right(())
        local = FileObjectStore(Paths.get(a.get("root").getOrElse("scratch/stores/s3")))
        bytes <- local.reader.get(manifestKey).toRight(s"no manifest at $manifestKey")
      yield
        val m = RunManifest.fromJson(bytes)
        val localProblems = RunManifest.verify(m, local.reader)
        out(s"manifest $manifestKey: stage ${m.stage} run ${m.runId} objects ${m.entries.size}")
        if localProblems.nonEmpty then
          Cli.report(localProblems, 5, out)
          1
        else
          live match
            case None =>
              out("dry run: manifest verified locally (no AWS call)")
              0
            case Some(t) =>
              out(s"LIVE: bucket ${cfg.storage.bucket} in ${t.region}, profile ${t.profile}")
              val remote = S3ObjectStore(LiveAws.s3(t), cfg.storage.bucket)
              def put(k: String, b: Array[Byte]) =
                Retry.run(RetryPolicy.Default, Cli.jitteredSleep)(remote.loader.putNew(k, b))._1
              val outcomes =
                (m.entries.map(_.key) :+ manifestKey).map(k => put(k, local.reader.get(k).get))
              val counts = outcomes
                .groupBy {
                  case Right(PutObjectOutcome.Conflict(_)) => "Conflict"
                  case Right(o)                            => o.toString
                  case Left(_)                             => "Invalid"
                }
                .view
                .mapValues(_.size)
                .toMap
              out(counts.toVector.sorted.map((k, n) => s"$k $n").mkString(" "))
              val remoteProblems = RunManifest.verify(m, remote.reader)
              Cli.report(remoteProblems, 5, out)
              if remoteProblems.isEmpty && !counts.contains("Conflict") && !counts.contains(
                  "Invalid"
                )
              then 0
              else 1
    }
