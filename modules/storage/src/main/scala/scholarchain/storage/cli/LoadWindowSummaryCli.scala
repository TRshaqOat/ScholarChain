package scholarchain.storage.cli

import scholarchain.core.StableIds
import scholarchain.storage.*

import java.nio.file.{Files, Paths}
import scala.util.Try

/** Converts one Spark CorpusWindowSummaryJob run into the window-summary store object in the file
  * object store `<out>/s3` (STORAGE.md, "Window summaries"), write-once, with a run manifest, then
  * reopens it through the read port. Reads the Spark run only. No live mode: LoadS3Cli copies the
  * manifest's objects to the bucket.
  */
object LoadWindowSummaryCli:
  val Stage = "load-window-summary"
  val CodeVersion = "load-window-summary-v1"
  val UsageText =
    "storage/runMain scholarchain.storage.cli.LoadWindowSummaryCli " +
      "--spark-run <out>/snapshot=<d>/run=<spark run> --out <new root, e.g. scratch/runtime/wo8>"

  def main(args: Array[String]): Unit = Cli.main("LoadWindowSummaryCli", args)(run(_, _))

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(args, Set.empty, Set("spark-run", "out"), UsageText, out, env) { (a, cfg) =>
      for
        dir <- a.req("spark-run").map(Paths.get(_))
        root <- a.req("out").map(Paths.get(_))
        segs <- Cli.runSegments(dir).toRight(s"$dir has no snapshot=/run= segments")
        snapshot = segs._1
        sparkRun = segs._2
        manifestBytes <- Try(Files.readAllBytes(dir.resolve("_manifest.json"))).toEither.left
          .map(e => s"cannot read $dir/_manifest.json: ${e.getMessage}")
        manifest <- Try(ujson.read(manifestBytes)).toEither.left.map(e => s"not JSON: $e")
        _ <- Either.cond(
          Try((manifest("snapshot").str, manifest("runId").str)).toOption
            .contains((snapshot, sparkRun)),
          (),
          s"the manifest's snapshot and runId differ from the path segments of $dir"
        )
        _ <- Cli.freeDiskProblem(root).toLeft(())
      yield
        val manifestHash = Hashing.sha256(manifestBytes)
        WindowSummaries.fromSparkManifest(
          dir.resolve("_manifest.json").toString,
          manifest,
          cfg.graph.windows
        ) match
          case Left(vs) =>
            Cli.report(vs, 5, out)
            1
          case Right(rows) =>
            val runId = StableIds.runId(
              Stage,
              CodeVersion,
              cfg.configVersion,
              Seq(s"source=$sparkRun", manifestHash),
              1
            )
            out(s"load run $runId from spark run $sparkRun snapshot $snapshot")
            val store = FileObjectStore(root.resolve("s3"))
            val key = S3Layout.windowSummary(snapshot, runId)
            val bytes = WindowSummaries.render(rows)
            val runManifest = RunManifest(
              RunManifest.SchemaVersion,
              Stage,
              runId,
              snapshot,
              Vector(
                ManifestEntry(
                  key,
                  Hashing.sha256(bytes),
                  bytes.length.toLong,
                  s"spark ${WindowSummaries.SparkTable} run=$sparkRun _manifest.json $manifestHash"
                )
              )
            )
            val manifestKey = S3Layout.runManifest(Stage, snapshot, runId)
            val outcomes = Vector(
              store.loader.putNew(key, bytes),
              store.loader.putNew(manifestKey, RunManifest.toJson(runManifest))
            )
            def shown(o: Either[List[String], PutObjectOutcome]) =
              o.fold(e => s"Invalid ${e.mkString("; ")}", _.toString)
            out(s"object $key ${shown(outcomes(0))}")
            out(s"manifest $manifestKey ${shown(outcomes(1))}")
            val reopened = WindowSummaries.open(store.reader, key, cfg.graph.windows)
            val problems =
              RunManifest.verify(runManifest, store.reader) ++ reopened.left.getOrElse(Vector.empty)
            reopened.foreach { port =>
              cfg.graph.windows.flatMap(w => port.windowSummary(w.name)).foreach { s =>
                out(
                  s"window ${s.window} researchers ${s.researchers} components ${s.components} " +
                    s"largestComponentFraction ${s.largestComponentFraction.getOrElse("null")} " +
                    s"singletonResearchers ${s.singletonResearchers} communitiesAtLeastMinSize " +
                    s"${s.communitiesAtLeastMinSize} (min ${s.communityMinSize}) runId ${s.provenance.runId}"
                )
              }
            }
            Cli.report(problems, 5, out)
            if problems.isEmpty && outcomes.forall(
                _.exists(!_.isInstanceOf[PutObjectOutcome.Conflict])
              )
            then 0
            else 1
    }
