package scholarchain.storage.cli

import scholarchain.core.StableIds
import scholarchain.storage.*
import scholarchain.storage.live.{LiveAws, S3VectorStore}

import java.nio.file.Paths
import scala.collection.mutable

/** Loads vector-record files (<index>/part-*.jsonl, the embedding runner's vector backup) into the
  * vector store: the file-backed fake at <out>/s3vectors by default, S3 Vectors with --live
  * --profile. Batched (--batch-size, at most 500), transient failures retried, rejected vectors to
  * <out>/work/load-vectors/snapshot=<d>/run=<r>/rejected-<index>.jsonl, batches in the RunLedger.
  * CreateOnly unless --supersede.
  */
object LoadVectorsCli:
  val Stage = "load-vectors"
  val CodeVersion = "load-vectors-v1"
  val UsageText =
    "storage/runMain scholarchain.storage.cli.LoadVectorsCli --input <.../vectors/backup/snapshot=<d>/run=<r>> " +
      "[--out scratch/stores] [--batch-size 1..500] [--supersede] [--live --profile <name> [--region <r>]]"

  def main(args: Array[String]): Unit = Cli.main("LoadVectorsCli", args)(run(_, _))

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(
      args,
      Cli.LiveFlags + "supersede",
      Cli.LiveOpts ++ Set("input", "out", "batch-size"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        input <- a.req("input").map(Paths.get(_))
        seg <- Cli.runSegments(input).toRight("--input must contain snapshot=<d>/run=<r>")
        (snapshot, sourceRun) = seg
        batch <- a
          .int("batch-size", Some(100))
          .filterOrElse(b => b >= 1 && b <= VectorContract.MaxPutBatch, "--batch-size 1 to 500")
        live <- Cli.liveTarget(a, cfg.region)
        _ <- if live.nonEmpty then Cli.liveConfig(cfg) else Right(())
      yield
        val root = Paths.get(a.get("out").getOrElse("scratch/stores"))
        val files = Cli.files(input, ".jsonl")
        val (violations, counts) = VectorRecordFiles.validate(files)
        counts.toVector.sorted.foreach((k, n) => out(s"count $k $n"))
        if files.isEmpty || violations.nonEmpty then
          Cli.report(violations, 5, out)
          out("nothing loaded")
          1
        else
          Cli.freeDiskProblem(root) match
            case Some(p) =>
              out(p)
              Cli.LowDisk
            case None =>
              val mode = if a.flag("supersede") then WriteMode.Supersede else WriteMode.CreateOnly
              val store = live match
                case Some(t) =>
                  out(
                    s"LIVE: S3 Vectors bucket ${cfg.s3Vectors.vectorBucket} in ${t.region}, profile ${t.profile}"
                  )
                  S3VectorStore(LiveAws.vectors(t), cfg.s3Vectors.vectorBucket).loader
                case None =>
                  out(s"dry run: file vector store ${root.resolve("s3vectors")} (no AWS call)")
                  FileVectorStore(root.resolve("s3vectors")).loader
              val runId = StableIds.runId(
                Stage,
                CodeVersion,
                cfg.configVersion,
                Seq(s"source=$sourceRun", Cli.contentHash(input, files), s"mode=$mode"),
                1
              )
              val ledger = FileRunLedger(root.resolve("ledger"))
              val attempt = ledger.entries(runId).count(_.entryType == "LOAD_SESSION") + 1
              ledger.append(
                LedgerEntry(
                  runId,
                  f"session-$attempt%04d",
                  "LOAD_SESSION",
                  Cli.now(),
                  Map("source" -> sourceRun)
                )
              )
              out(s"load run $runId attempt $attempt mode $mode")
              val work = root.resolve(s"work/load-vectors/snapshot=$snapshot/run=$runId")
              val byIndex = mutable.LinkedHashMap.empty[String, Vector[VectorRecord]]
              files.foreach { f =>
                val ix = VectorRecordFiles.indexOf(f).get
                val rs = Vector.newBuilder[VectorRecord]
                VectorRecordFiles.foreachRecord(f)((_, r) => r.foreach(rs += _))
                byIndex(ix) = byIndex.getOrElse(ix, Vector.empty) ++ rs.result()
              }
              val rejected = byIndex.toVector.map { (ix, rs) =>
                val s = VectorLoader.load(
                  store,
                  ix,
                  rs,
                  mode,
                  batch,
                  RetryPolicy.Default,
                  if live.nonEmpty then Cli.jitteredSleep else Thread.sleep,
                  ledger,
                  runId,
                  attempt,
                  VectorLoader.fileSink(work.resolve(s"rejected-$ix.jsonl")),
                  () => Cli.now()
                )
                out(
                  s"$ix written ${s.written} skipped ${s.skipped} rejected ${s.rejected} batches ${s.batches} retries ${s.retries}"
                )
                s.rejected
              }.sum
              if rejected > 0 then out(s"rejected vectors listed under $work")
              if rejected == 0 then 0 else 1
    }
