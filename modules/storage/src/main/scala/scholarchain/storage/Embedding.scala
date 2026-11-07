package scholarchain.storage

import scholarchain.core.StableIds

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.hashing.MurmurHash3

/** A document-specific refusal (for example the model rejects the input). The document goes to the
  * rejected file and is not retried in the same run.
  */
final class RejectedInputException(message: String) extends RuntimeException(message)

/** Embedding model port. embed throws TransientStoreException for retryable failures,
  * RejectedInputException for a bad document, and anything else to stop the run.
  */
trait Embedder:
  def modelId: String

  /** A fake never claims a real model: its vectors are labelled "fake:<model version>". */
  def isFake: Boolean
  def embed(text: String): Array[Float]

/** Deterministic fake: signed feature hashing of lower-case word tokens into 1024 buckets,
  * normalized. Texts that share words get similar vectors, so retrieval tests are meaningful.
  */
final class HashingEmbedder extends Embedder:
  val modelId = "fake-hashing-embedder-v1"
  val isFake = true
  def embed(text: String): Array[Float] =
    val a = new Array[Float](VectorContract.Dimension)
    text.toLowerCase.split("[^\\p{L}\\p{N}]+").filter(_.nonEmpty).foreach { t =>
      val sign = if MurmurHash3.stringHash(t, 0x5eed) >= 0 then 1f else -1f
      a(Math.floorMod(MurmurHash3.stringHash(t), a.length)) += sign
    }
    if a.forall(_ == 0f) then a(0) = 1f
    val n = math.sqrt(a.map(x => x.toDouble * x).sum).toFloat
    a.map(_ / n)

/** Amazon Titan Text Embeddings V2 request and response bodies (model-parameters-titan-embed-text,
  * accessed 2026-11-05). The SDK call is in live.BedrockTitanEmbedder.
  */
object TitanV2:
  val ModelId = "amazon.titan-embed-text-v2:0"

  def requestBody(text: String): String =
    ujson
      .Obj("inputText" -> text, "dimensions" -> VectorContract.Dimension, "normalize" -> true)
      .render()

  def parseResponse(body: String): Either[String, Array[Float]] =
    scala.util
      .Try(ujson.read(body)("embedding").arr.map(_.num.toFloat).toArray)
      .toEither
      .left
      .map(e => s"unreadable Titan response: ${e.getMessage}".take(200))
      .flatMap(a =>
        Either.cond(a.length == VectorContract.Dimension, a, s"Titan returned ${a.length} values")
      )

/** Paces calls to at most perMinute (1 to 50, ruling 9), single-threaded: call starts are at least
  * ceil(60000 / perMinute) ms apart, so no 60-second window holds more than perMinute starts. The
  * runner applies it only to a real (non-fake) embedder (ruling 29); the fake runs unthrottled.
  */
final class Throttle(perMinute: Int, nowMs: () => Long, sleep: Long => Unit):
  require(perMinute >= 1 && perMinute <= Throttle.MaxPerMinute, s"perMinute $perMinute")
  val intervalMs: Long = (60000 + perMinute - 1) / perMinute
  private var next = Long.MinValue
  def acquire(): Unit =
    val now = nowMs()
    if next > now then sleep(next - now)
    next = math.max(now, next) + intervalMs

object Throttle:
  val MaxPerMinute = 50
  def monotonicMs(): Long = System.nanoTime() / 1000000

enum EmbedStatus:
  case COMPLETE, MAX_REACHED, FAILED

final case class EmbedReport(
    runId: String,
    snapshot: String,
    documents: Int,
    alreadyDone: Int,
    embedded: Int,
    rejected: Int,
    remaining: Int,
    requests: Int,
    status: EmbedStatus,
    failure: Option[String],
    published: Vector[String]
)

/** Manifest-driven embedding batch runner (ruling 11; STORAGE.md, "Embedding runner").
  *
  * The input is a fixed set of validated rendered-document files. The run ID is derived from their
  * sha256 hashes and the embedder model, so a rerun on the same inputs is the same run and resumes
  * it. State lives in the work directory (work/embed/snapshot=<d>/run=<r>/): vectors/<index>/
  * part-00000.jsonl (validated vector records, appended one per line) and rejected.jsonl. A
  * document is skipped when its key is already there with the same textHash and model version, or
  * was rejected. Progress and failures go to the RunLedger. When nothing is pending, the run
  * publishes the texts, the vector backup, and a run manifest to the file object store
  * (write-once).
  */
object EmbeddingRunner:
  val Stage = "embed"
  val CodeVersion = "embed-runner-v1"
  val ProgressEvery = 100

  final case class Settings(
      max: Int,
      policy: RetryPolicy,
      configVersion: String,
      now: () => String
  ):
    require(max >= 1, "--max must be at least 1")

  def runIdFor(fileHashes: Seq[String], modelId: String, configVersion: String): String =
    StableIds.runId(Stage, CodeVersion, configVersion, fileHashes :+ s"model=$modelId", 1)

  def workDir(root: Path, snapshot: String, runId: String): Path =
    root.resolve(s"work/embed/snapshot=$snapshot/run=$runId")

  /** Opens an append-only JSONL file, first cutting a partial last line left by a crash. */
  private def openForAppend(f: Path): Unit =
    Files.createDirectories(f.getParent)
    if Files.exists(f) then
      val bytes = Files.readAllBytes(f)
      val keep = bytes.lastIndexOf('\n'.toByte) + 1
      if keep < bytes.length then
        Using.resource(FileChannel.open(f, StandardOpenOption.WRITE))(_.truncate(keep.toLong))

  private def appendLine(f: Path, line: String): Unit =
    Files.writeString(f, line + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  private def readLines(f: Path): Vector[String] =
    if Files.exists(f) then Files.readAllLines(f, UTF_8).asScala.filter(_.nonEmpty).toVector
    else Vector.empty

  /** Runs one session. Left means the inputs are invalid and nothing was done. */
  def run(
      inputs: Seq[Path],
      root: Path,
      embedder: Embedder,
      throttle: Throttle,
      sleep: Long => Unit,
      ledger: RunLedgerPort,
      windows: Seq[scholarchain.core.config.TimeWindow],
      settings: Settings,
      log: String => Unit
  ): Either[Vector[Violation], EmbedReport] =
    val files = inputs.sortBy(_.toString)
    val (violations, _) = RenderedDocuments.validate(files, windows, None)
    val docs = Vector.newBuilder[RenderedDocument]
    if violations.isEmpty then
      files.foreach(f => RenderedDocuments.foreachLine(f)((_, d) => d.foreach(docs += _)))
    val all = docs.result()
    val model = all.filter(d => !embedder.isFake && d.embeddingModelId != embedder.modelId)
    val snapshots = all.map(_.provenance.snapshot).distinct
    val extra = Vector(
      Option.when(all.isEmpty && violations.isEmpty)("no documents"),
      Option.when(snapshots.size > 1)(s"documents span snapshots ${snapshots.mkString(", ")}"),
      model.headOption.map(d =>
        s"${model.size} documents are for ${d.embeddingModelId}, the embedder is ${embedder.modelId}"
      )
    ).flatten.map(Violation("*", 0, "BAD_INPUT", _))
    if violations.nonEmpty || extra.nonEmpty then Left(violations ++ extra)
    else
      val snapshot = snapshots.head
      val hashes = files.map(f => Hashing.sha256(Files.readAllBytes(f)))
      val runId = runIdFor(hashes, embedder.modelId, settings.configVersion)
      val work = workDir(root, snapshot, runId)
      val rejectedFile = work.resolve("rejected.jsonl")
      def vectorFile(index: String) = work.resolve(s"vectors/$index/part-00000.jsonl")
      def label(d: RenderedDocument) =
        val v = d.metadata("embeddingModelVersion").str
        if embedder.isFake then s"fake:$v" else v
      // Resume state: what earlier sessions of this run wrote.
      val done = mutable.HashMap.empty[String, (String, String)]
      VectorContract.Indexes.foreach { ix =>
        val f = vectorFile(ix.name)
        openForAppend(f)
        readLines(f).foreach { l =>
          val r = VectorRecord.fromJson(l)
          done(r.key) = (r.textHash, r.modelVersion)
        }
      }
      openForAppend(rejectedFile)
      val rejectedKeys = readLines(rejectedFile).map(l => ujson.read(l)("key").str).toSet
      val session = ledger.entries(runId).count(_.entryType == "EMBED_SESSION") + 1
      def entry(name: String, kind: String, details: Map[String, String]) =
        ledger.append(
          LedgerEntry(runId, f"embed#session-$session%04d#$name", kind, settings.now(), details)
        )
      // Ruling 29: the throttle exists for the Bedrock quota, so only a real embedder is paced.
      val throttled = !embedder.isFake
      val pace =
        if throttled then s"one call every ${throttle.intervalMs} ms" else "unthrottled (fake)"
      val alreadyDone = all.count(d => done.get(d.vectorKey).contains((d.textHash, label(d))))
      val pending = all.filter(d =>
        !done.get(d.vectorKey).contains((d.textHash, label(d))) && !rejectedKeys(d.vectorKey)
      )
      log(
        s"run $runId session $session: ${all.size} documents, $alreadyDone already embedded, " +
          s"${pending.size} pending, this session at most ${settings.max}, " +
          pace
      )
      log(s"work directory $work")
      log(s"ledger entries: runId $runId, keys embed#session-$session...")
      entry(
        "start",
        "EMBED_SESSION",
        Map(
          "documents" -> all.size.toString,
          "alreadyDone" -> alreadyDone.toString,
          "previouslyRejected" -> rejectedKeys.size.toString,
          "pending" -> pending.size.toString,
          "max" -> settings.max.toString,
          "intervalMs" -> (if throttled then throttle.intervalMs else 0L).toString,
          "model" -> embedder.modelId
        )
      )
      var embedded = 0
      var rejected = 0
      var requests = 0
      var failure: Option[String] = None
      def progress(name: String) =
        log(s"$name: embedded $embedded, rejected $rejected, requests $requests")
        entry(
          name,
          "EMBED_PROGRESS",
          Map(
            "embedded" -> embedded.toString,
            "rejected" -> rejected.toString,
            "requests" -> requests.toString
          )
        )
      def reject(d: RenderedDocument, reason: String) =
        rejected += 1
        appendLine(
          rejectedFile,
          ujson
            .Obj(
              "index" -> d.index,
              "key" -> d.vectorKey,
              "reason" -> reason.take(500),
              "textHash" -> d.textHash,
              "runId" -> runId
            )
            .render()
        )
        entry(s"reject#${d.vectorKey}", "EMBED_REJECTED", Map("reason" -> reason.take(500)))
      val it = pending.iterator.take(settings.max)
      while failure.isEmpty && it.hasNext do
        val d = it.next()
        try
          val (data, _) = Retry.run(settings.policy, sleep) {
            if throttled then throttle.acquire()
            requests += 1
            embedder.embed(d.text)
          }
          val m = ujson.Obj.from(d.metadata.value)
          m("embeddingModelVersion") = label(d)
          val r = VectorRecord(d.vectorKey, data, m)
          VectorContract.dataProblems(data) ++
            VectorContract.metadataProblems(d.index, d.vectorKey, m) match
            case Nil =>
              appendLine(vectorFile(d.index), VectorRecord.toJson(r).render())
              done(d.vectorKey) = (d.textHash, label(d))
              embedded += 1
              if embedded % ProgressEvery == 0 then progress(f"progress-$embedded%06d")
            case problems => reject(d, s"INVALID_VECTOR: ${problems.mkString("; ")}")
        catch
          case e: RejectedInputException  => reject(d, s"REJECTED_INPUT: ${e.getMessage}")
          case e: TransientStoreException =>
            failure = Some(s"retries exhausted at ${d.vectorKey}: ${e.getMessage}")
          case e: Exception => failure = Some(s"${e.getClass.getSimpleName}: ${e.getMessage}")
      val remaining = all.count(d =>
        !done.get(d.vectorKey).contains((d.textHash, label(d))) && !rejectedKeys(d.vectorKey)
      ) - rejected
      val status =
        if failure.nonEmpty then EmbedStatus.FAILED
        else if remaining > 0 then EmbedStatus.MAX_REACHED
        else EmbedStatus.COMPLETE
      progress("end")
      failure.foreach(f => entry("failure", "EMBED_FAILURE", Map("error" -> f.take(500))))
      val published =
        if status == EmbedStatus.COMPLETE then
          publish(all, root, snapshot, runId, work, files.zip(hashes), rejectedFile)
        else Vector.empty
      entry(
        "status",
        "EMBED_STATUS",
        Map("status" -> status.toString, "published" -> published.size.toString)
      )
      Right(
        EmbedReport(
          runId,
          snapshot,
          all.size,
          alreadyDone,
          embedded,
          rejected,
          remaining,
          requests,
          status,
          failure,
          published
        )
      )

  /** Writes texts, vector backup files, rejected records, and the run manifest to the file object
    * store at root/s3 (write-once; an identical republish is Unchanged).
    */
  private def publish(
      docs: Vector[RenderedDocument],
      root: Path,
      snapshot: String,
      runId: String,
      work: Path,
      inputs: Seq[(Path, String)],
      rejectedFile: Path
  ): Vector[String] =
    val store = FileObjectStore(root.resolve("s3"))
    val entries = Vector.newBuilder[ManifestEntry]
    val source = inputs.map((p, h) => s"${p.getFileName} $h").mkString("; ")
    def put(key: String, bytes: Array[Byte], ref: String): Unit =
      store.loader.putNew(key, bytes) match
        case Right(PutObjectOutcome.Conflict(h)) =>
          throw IllegalStateException(s"$key already holds other content ($h)")
        case Left(p)  => throw IllegalStateException(s"$key: ${p.mkString("; ")}")
        case Right(_) => entries += ManifestEntry(key, Hashing.sha256(bytes), bytes.length, ref)
    docs.foreach(d =>
      put(d.metadata("textRef").str, d.text.getBytes(UTF_8), s"${d.provenance.sourceRecordId}")
    )
    VectorContract.Indexes.foreach { ix =>
      val lines = readLines(work.resolve(s"vectors/${ix.name}/part-00000.jsonl"))
      if lines.nonEmpty then
        val sorted = lines.sortBy(l => ujson.read(l)("key").str)
        put(
          S3Layout.vectorBackup(snapshot, runId, ix.name) + "/part-00000.jsonl",
          sorted.mkString("", "\n", "\n").getBytes(UTF_8),
          source
        )
      val rej = readLines(rejectedFile).filter(l => ujson.read(l)("index").str == ix.name)
      if rej.nonEmpty then
        put(
          S3Layout.rejectedVectors(snapshot, runId, ix.name),
          rej.sorted.mkString("", "\n", "\n").getBytes(UTF_8),
          source
        )
    }
    val manifest = RunManifest(RunManifest.SchemaVersion, Stage, runId, snapshot, entries.result())
    val key = S3Layout.runManifest(Stage, snapshot, runId)
    store.loader.putNew(key, RunManifest.toJson(manifest)) match
      case Right(PutObjectOutcome.Conflict(h)) =>
        throw IllegalStateException(s"$key already holds another manifest ($h)")
      case Left(p) => throw IllegalStateException(s"$key: ${p.mkString("; ")}")
      case _       => ()
    manifest.entries.map(_.key) :+ key
