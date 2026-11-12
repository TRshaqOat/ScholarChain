package scholarchain.rag.cli

import scholarchain.rag.{EmbeddingModels, Render, Smoke, Templates}
import scholarchain.storage.cli.Cli
import scholarchain.storage.live.{BedrockTitanEmbedder, LiveAws}
import scholarchain.storage.{Embedder, FileVectorStore, HashingEmbedder, Throttle}

import java.nio.file.Paths

/** Retrieval smoke tests over a file vector store (RAG.md, "Retrieval smoke tests").
  *
  * Reads the embedding candidates (for the facts), re-renders them to recover each document's
  * vector key, and queries `--vectors` (a FileVectorStore root, for example
  * scratch/stores/s3vectors). Every query filters on the expected embeddingModelVersion, so fake
  * and real vectors never mix: the default fake embedder expects "fake:<version>" vectors; `--live
  * --profile <name>` (human only, never run by an agent) embeds the queries with Titan V2,
  * throttled to at most 50 per minute, and expects the real version. Exit 0 when every test meets
  * its threshold, 1 otherwise, 2 on a usage error.
  */
object SmokeCli:
  val UsageText =
    "rag/runMain scholarchain.rag.cli.SmokeCli --candidates <embedding-candidate dir> " +
      "--vectors <file vector store root> [--per-test 1..200, default 50] " +
      "[--live --profile <name> [--region <r>] [--per-minute 1..50]]"

  def main(args: Array[String]): Unit = Cli.main("SmokeCli", args)(run(_, _))

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
      Cli.LiveOpts ++ Set("candidates", "vectors", "per-test", "per-minute"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        candidates <- a.req("candidates").map(Paths.get(_))
        vectors <- a.req("vectors").map(Paths.get(_))
        perTest <- a
          .int("per-test", Some(50))
          .filterOrElse(n => n >= 1 && n <= Smoke.MaxPerTest, "--per-test must be 1 to 200")
        perMinute <- a
          .int("per-minute", Some(Throttle.MaxPerMinute))
          .filterOrElse(n => n >= 1 && n <= Throttle.MaxPerMinute, "--per-minute must be 1 to 50")
        model <- EmbeddingModels.forId(cfg.models.embeddingModel)
        live <- Cli.liveTarget(a, cfg.region)
      yield
        val version = EmbeddingModels.version(model, Templates.Version)
        Render.renderAll(
          Cli.files(candidates, ".jsonl"),
          cfg.graph.windows,
          model,
          cfg.configVersion
        ) match
          case Left(vs) =>
            Cli.report(vs, 5, out)
            1
          case Right(r) =>
            val docs = r.documents.zip(r.inputs.map(_.facts))
            val cases = Smoke.cases(
              docs,
              live.fold(EmbeddingModels.fakeLabel(version))(_ => version),
              perTest
            )
            val throttle = Throttle(perMinute, nowMs, sleep)
            val embedder: Embedder = live match
              case Some(t) =>
                out(
                  s"LIVE: Bedrock ${model.modelId} in ${t.region}, profile ${t.profile}, " +
                    s"${cases.size} query embeddings at most $perMinute per minute"
                )
                // The expired-token retry is a second request: it passes the throttle too (N1).
                BedrockTitanEmbedder(LiveAws.bedrock(t), model.modelId, () => throttle.acquire())
              case None =>
                out("dry run: fake embedder (no AWS call)")
                HashingEmbedder()
            val embed = (q: String) =>
              if !embedder.isFake then throttle.acquire()
              embedder.embed(q)
            val results = Smoke.run(cases, FileVectorStore(vectors).reader, embed)
            results.foreach { res =>
              out(
                f"${if res.ok then "PASS" else "FAIL"} ${res.test} ${res.passed}/${res.cases} " +
                  f"rate ${res.rate}%.3f threshold ${res.threshold}%.2f"
              )
              res.failures.take(5).foreach(f => out(s"    $f"))
            }
            if results.forall(_.ok) then 0 else 1
    }
