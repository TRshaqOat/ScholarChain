package scholarchain.rag.cli

import scholarchain.rag.{EmbeddingModels, Render, Templates}
import scholarchain.storage.cli.Cli
import scholarchain.storage.{
  FileObjectStore,
  Hashing,
  ManifestEntry,
  PutObjectOutcome,
  RenderedDocuments,
  RunManifest,
  S3Layout
}
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths

/** Render CLI (ruling 23): embedding-candidate JSONL (with facts) -> rendered-document-v1 JSONL.
  *
  * Writes, write-once, under `<out>/s3/`:
  *   - `semantic-context/rendered/snapshot=<d>/run=<r>/<index>/part-00000.jsonl` (sorted by key)
  *   - `runs/rag-render/snapshot=<d>/run=<r>/manifest.json`
  *
  * The run ID depends only on the input bytes, the template, the model version, and the
  * configuration version, so a rerun is the same run and reports Unchanged. The model comes from
  * `models.embedding-model` (SCHOLARCHAIN_EMBEDDING_MODEL_ID) and must be a known embedding model.
  * Exit codes as in storage Cli: 0 ok, 1 violations or a write conflict, 2 usage, 3 low disk.
  */
object RenderCli:
  val UsageText =
    "rag/runMain scholarchain.rag.cli.RenderCli --input <embedding-candidate dir> [--out scratch/stores]"

  def main(args: Array[String]): Unit = Cli.main("RenderCli", args)(run(_, _))

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    RagCli.withArgs(args, Set.empty, Set("input", "out"), UsageText, out, env) { (a, cfg) =>
      for
        input <- a.req("input").map(Paths.get(_))
        model <- EmbeddingModels.forId(cfg.models.embeddingModel)
      yield
        val root = Paths.get(a.get("out").getOrElse("scratch/stores"))
        val files = Cli.files(input, ".jsonl")
        Cli.freeDiskProblem(root) match
          case Some(p) =>
            out(p)
            Cli.LowDisk
          case None =>
            Render.renderAll(files, cfg.graph.windows, model, cfg.configVersion) match
              case Left(vs) =>
                Cli.report(vs, 5, out)
                1
              case Right(r) =>
                val store = FileObjectStore(root.resolve("s3"))
                val base = S3Layout.base("semantic-context", "rendered", r.snapshot, r.runId)
                val sourceRef =
                  files
                    .map(f => input.relativize(f).toString)
                    .mkString(s"candidates:$input:", ",", "")
                val objects = r.documents.groupBy(_.index).toVector.sortBy(_._1).map { (ix, ds) =>
                  val body = ds.sortBy(_.vectorKey).map(d => write(d) + "\n").mkString
                  (s"$base/$ix/part-00000.jsonl", body.getBytes(UTF_8))
                }
                val manifest = RunManifest(
                  RunManifest.SchemaVersion,
                  Render.Stage,
                  r.runId,
                  r.snapshot,
                  objects.map((k, b) =>
                    ManifestEntry(k, Hashing.sha256(b), b.length.toLong, sourceRef)
                  )
                )
                val writes = objects :+
                  (
                    S3Layout.runManifest(Render.Stage, r.snapshot, r.runId),
                    RunManifest.toJson(manifest)
                  )
                val outcomes = writes.map((k, b) => k -> store.loader.putNew(k, b))
                outcomes.foreach((k, o) => out(s"${o.fold(_ => "Invalid", _.toString)} $k"))
                val failed = outcomes.collect {
                  case (k, Left(ps))                            => s"$k: ${ps.mkString("; ")}"
                  case (k, Right(PutObjectOutcome.Conflict(_))) => s"$k exists with other content"
                }
                if failed.nonEmpty then
                  failed.foreach(f => out(s"conflict $f"))
                  1
                else
                  val written = objects.map((k, _) => root.resolve("s3").resolve(k))
                  val (vs, counts) = RenderedDocuments.validate(written, cfg.graph.windows, None)
                  out(
                    s"run ${r.runId} snapshot ${r.snapshot} template ${Templates.Version} " +
                      s"model ${EmbeddingModels.version(model, Templates.Version)}"
                  )
                  out(s"candidates ${r.inputs.size} documents ${r.documents.size}")
                  counts.toVector.sorted.foreach((ix, n) => out(s"  $ix $n"))
                  out(s"output ${root.resolve("s3").resolve(base)}")
                  Cli.report(vs, 5, out)
                  if vs.isEmpty then 0 else 1
    }
