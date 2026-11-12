package scholarchain.storage.cli

import scholarchain.storage.*
import scholarchain.storage.live.{BedrockTitanEmbedder, LiveAws}

import java.nio.file.Paths

/** The embedding batch runner (STORAGE.md, "Embedding runner"). The fake embedder is the default;
  * Titan V2 runs only with --live --profile <name>, the model ID coming from the configuration
  * (SCHOLARCHAIN_EMBEDDING_MODEL_ID). --max is required and caps the documents embedded in this
  * session. Rerunning the same command resumes the same run.
  */
object EmbedCli:
  val UsageText =
    "storage/runMain scholarchain.storage.cli.EmbedCli --input <rendered-document dir> --max <n> " +
      "[--out scratch/stores] [--per-minute 1..50] [--live --profile <name> [--region <r>]]"

  def main(args: Array[String]): Unit = Cli.main("EmbedCli", args)(run(_, _))

  def run(
      args: Seq[String],
      out: String => Unit,
      sleep: Long => Unit = Thread.sleep,
      nowMs: () => Long = () => Throttle.monotonicMs(),
      env: Map[String, String] = sys.env
  ): Int =
    Cli.withArgs(
      args,
      Cli.LiveFlags,
      Cli.LiveOpts ++ Set("input", "max", "out", "per-minute"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        input <- a.req("input").map(Paths.get(_))
        max <- a.int("max", None).filterOrElse(_ >= 1, "--max must be at least 1")
        perMinute <- a
          .int("per-minute", Some(Throttle.MaxPerMinute))
          .filterOrElse(n => n >= 1 && n <= Throttle.MaxPerMinute, "--per-minute must be 1 to 50")
        live <- Cli.liveTarget(a, cfg.region)
      yield
        val root = Paths.get(a.get("out").getOrElse("scratch/stores"))
        Cli.freeDiskProblem(root) match
          case Some(p) =>
            out(p)
            Cli.LowDisk
          case None =>
            val throttle = Throttle(perMinute, nowMs, sleep)
            val embedder = live match
              case Some(t) =>
                out(
                  s"LIVE: Bedrock ${cfg.models.embeddingModel} in ${t.region}, profile ${t.profile}"
                )
                // The expired-token retry passes the same throttle as every other attempt (N1).
                BedrockTitanEmbedder(
                  LiveAws.bedrock(t),
                  cfg.models.embeddingModel,
                  () => throttle.acquire()
                )
              case None =>
                out("dry run: fake embedder (no AWS call)")
                HashingEmbedder()
            val result = EmbeddingRunner.run(
              Cli.files(input, ".jsonl"),
              root,
              embedder,
              throttle,
              ms =>
                sleep(if live.nonEmpty then ms / 2 + scala.util.Random.nextLong(ms / 2 + 1)
                else ms),
              FileRunLedger(root.resolve("ledger")),
              cfg.graph.windows,
              EmbeddingRunner
                .Settings(max, RetryPolicy(3, 2000, 30000), cfg.configVersion, () => Cli.now()),
              out
            )
            result match
              case Left(vs) =>
                Cli.report(vs, 5, out)
                1
              case Right(r) =>
                out(
                  s"status ${r.status} run ${r.runId} snapshot ${r.snapshot} documents ${r.documents} " +
                    s"alreadyDone ${r.alreadyDone} embedded ${r.embedded} rejected ${r.rejected} " +
                    s"remaining ${r.remaining} requests ${r.requests}"
                )
                r.failure.foreach(f => out(s"failure $f"))
                if r.status == EmbedStatus.MAX_REACHED then
                  out("not finished: rerun the same command (with a --max for the rest) to resume")
                if r.published.nonEmpty then
                  out(s"published ${r.published.size} objects; manifest ${r.published.last}")
                if r.status == EmbedStatus.FAILED then 1 else 0
    }
