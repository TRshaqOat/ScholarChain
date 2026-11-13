package scholarchain.storage

import upickle.default.{ReadWriter, read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** S3 key layout of the one environment bucket (STORAGE.md, "S3"). Keys are relative to the bucket;
  * the bucket name comes from configuration and never appears in a key or pointer.
  */
object S3Layout:
  val Prefixes: Vector[String] =
    Vector("raw", "curated", "graph", "semantic-context", "vectors", "reports", "logs", "runs")
  val MaxKeyBytes = 1024 // S3 object key limit, UTF-8 bytes
  private val SafeChars = "[A-Za-z0-9!_.*'()=/+-]+".r
  private val RunSegment = "run=run-[0-9a-f]{32}".r

  /** `<prefix>/<artifact>/snapshot=<date>/run=<runId>` */
  def base(prefix: String, artifact: String, snapshot: String, runId: String): String =
    s"$prefix/$artifact/snapshot=$snapshot/run=$runId"

  def neptuneRoot(snapshot: String, runId: String): String =
    base("graph", "neptune", snapshot, runId)
  def coauthorEvidence(snapshot: String, runId: String, window: String, edgeId: String): String =
    s"${base("curated", "coauthor-evidence", snapshot, runId)}/window=$window/$edgeId.json"
  def dynamoItems(snapshot: String, runId: String, table: String): String =
    s"${base("curated", "dynamodb", snapshot, runId)}/$table"
  def semanticText(snapshot: String, runId: String, index: String, vectorKey: String): String =
    s"${base("semantic-context", "text", snapshot, runId)}/$index/$vectorKey.txt"
  def embeddingCandidates(snapshot: String, runId: String, index: String): String =
    s"${base("semantic-context", "candidates", snapshot, runId)}/$index"
  def vectorBackup(snapshot: String, runId: String, index: String): String =
    s"${base("vectors", "backup", snapshot, runId)}/$index"
  def rejectedVectors(snapshot: String, runId: String, index: String): String =
    s"${base("vectors", "rejected", snapshot, runId)}/$index.jsonl"
  def windowSummary(snapshot: String, runId: String): String =
    s"${base("curated", "window-summary", snapshot, runId)}/part-00000.jsonl"
  def runManifest(stage: String, snapshot: String, runId: String): String =
    s"${base("runs", stage, snapshot, runId)}/manifest.json"

  /** Rule violations of a key; empty means valid. */
  def keyProblems(key: String): List[String] =
    val segs = key.split("/", -1).toList
    List(
      Option.when(key.getBytes(UTF_8).length > MaxKeyBytes)(s"key longer than $MaxKeyBytes bytes"),
      Option.when(!SafeChars.matches(key))("key has characters outside the safe set"),
      Option.when(!Prefixes.contains(segs.head))(s"unknown top-level prefix '${segs.head}'"),
      Option.when(segs.exists(s => s.isEmpty || s == "." || s == ".."))("empty or dot segment"),
      Option.when(
        !segs.sliding(2).exists {
          case List(s, r) =>
            s.startsWith("snapshot=") &&
            ProvenanceRules.Snapshot.matches(s.drop(9)) && RunSegment.matches(r)
          case _ => false
        }
      )("key lacks a snapshot=<YYYY-MM-DD>/run=<runId> pair")
    ).flatten

enum PutObjectOutcome:
  case Inserted, Unchanged
  case Conflict(existingSha256: String)

/** Read side of the object store; the runtime gets only this. */
trait ObjectReadPort:
  def get(key: String): Option[Array[Byte]]
  def list(prefix: String): Vector[String]

/** Load side. Objects are write-once: an existing key with other content is a Conflict and is never
  * overwritten (overwriting stored data is a human-gated action).
  */
trait ObjectLoadPort:
  def putNew(key: String, bytes: Array[Byte]): Either[List[String], PutObjectOutcome]

/** One object a run wrote. sourceRef names what it was built from (a Phase 6 table and run, or an
  * input file and line range).
  */
final case class ManifestEntry(key: String, sha256: String, bytes: Long, sourceRef: String)
    derives ReadWriter

final case class RunManifest(
    schemaVersion: String,
    stage: String,
    runId: String,
    snapshot: String,
    entries: Vector[ManifestEntry]
) derives ReadWriter

object RunManifest:
  val SchemaVersion = "store-run-manifest-v1"

  def toJson(m: RunManifest): Array[Byte] = write(m, indent = 2).getBytes(UTF_8)
  def fromJson(bytes: Array[Byte]): RunManifest = read[RunManifest](new String(bytes, UTF_8))

  /** Every entry must exist with the recorded hash and size, and carry a source reference. */
  def verify(m: RunManifest, objects: ObjectReadPort): Vector[Violation] =
    val src = s"manifest:${m.runId}"
    val head = Vector(
      Option.when(m.schemaVersion != SchemaVersion)(s"schemaVersion ${m.schemaVersion}"),
      Option.when(!scholarchain.core.IdFormat.isRunId(m.runId))(s"bad runId ${m.runId}"),
      Option.when(!ProvenanceRules.Snapshot.matches(m.snapshot))(s"bad snapshot ${m.snapshot}")
    ).flatten.map(Violation(src, 0, "MANIFEST_HEADER", _))
    val dupes = m.entries
      .groupBy(_.key)
      .collect { case (k, es) if es.size > 1 => Violation(src, 0, "DUPLICATE_KEY", k) }
    head ++ dupes ++ m.entries.zipWithIndex.flatMap { (e, i) =>
      val line = i + 1L
      val keyIssues = S3Layout.keyProblems(e.key).map(Violation(src, line, "BAD_KEY", _))
      val content = objects.get(e.key) match
        case None => Vector(Violation(src, line, "OBJECT_MISSING", e.key))
        case Some(b) if Hashing.sha256(b) != e.sha256 || b.length != e.bytes =>
          Vector(Violation(src, line, "HASH_MISMATCH", e.key))
        case _ => Vector.empty
      val ref =
        Option.when(e.sourceRef.trim.isEmpty)(Violation(src, line, "MISSING_SOURCE_REF", e.key))
      keyIssues ++ content ++ ref
    }

/** File-backed local fake of the environment bucket: key k is the file root/k. */
final class FileObjectStore(root: Path):
  private def pathOf(key: String): Path = root.resolve(key)

  val reader: ObjectReadPort = new ObjectReadPort:
    def get(key: String): Option[Array[Byte]] =
      Option.when(S3Layout.keyProblems(key).isEmpty && Files.isRegularFile(pathOf(key)))(
        Files.readAllBytes(pathOf(key))
      )
    def list(prefix: String): Vector[String] =
      if !Files.isDirectory(root) then Vector.empty
      else
        Using.resource(Files.walk(root)) { s =>
          s.iterator.asScala
            .filter(p => Files.isRegularFile(p) && !p.getFileName.toString.startsWith(".tmp-"))
            .map(p => root.relativize(p).iterator.asScala.mkString("/"))
            .filter(_.startsWith(prefix))
            .toVector
            .sorted
        }

  val loader: ObjectLoadPort = new ObjectLoadPort:
    def putNew(key: String, bytes: Array[Byte]): Either[List[String], PutObjectOutcome] =
      S3Layout.keyProblems(key) match
        case Nil =>
          val target = pathOf(key)
          if Files.exists(target) then
            val existing = Hashing.sha256(Files.readAllBytes(target))
            Right(
              if existing == Hashing.sha256(bytes) then PutObjectOutcome.Unchanged
              else PutObjectOutcome.Conflict(existing)
            )
          else
            Files.createDirectories(target.getParent)
            val tmp = Files.createTempFile(target.getParent, ".tmp-", "")
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            Right(PutObjectOutcome.Inserted)
        case problems => Left(problems)
