package scholarchain.storage

import scholarchain.core.{IdFormat, SourceType}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** One vector index and the document types it holds. */
final case class VectorIndexSpec(name: String, documentTypes: Set[String])

/** S3 Vectors contract (STORAGE.md, "S3 Vectors"). */
object VectorContract:
  val Dimension = 1024 // amazon.titan-embed-text-v2:0, normalize = true
  val DistanceMetric = "cosine"

  // AWS limits (s3-vectors-limitations.html, accessed 2026-11-05).
  val MaxMetadataBytes = 40 * 1024
  val MaxFilterableBytes = 2048
  val MaxMetadataKeys = 50
  val MaxNonFilterableKeys = 10
  val MaxPutBatch = 500
  val MaxGetBatch = 100
  val MaxTopK = 10000
  val MaxKeyLength = 1024
  val MaxFilterConstraints = 100

  // ScholarChain budgets, well under the limits.
  val MetadataBudgetBytes = 4096
  val FilterableBudgetBytes = 1024
  val MaxListEntries = 10
  val NormTolerance = 1e-3

  val NonFilterableKeys: Vector[String] = Vector("vectorKey", "textHash", "textRef")
  val FilterableKeys: Vector[String] = Vector(
    "entityIds",
    "window",
    "documentType",
    "sourceDataset",
    "embeddingModelVersion",
    "runId",
    "topicIds",
    "institutionIds",
    "communityId"
  )
  val RequiredKeys: Set[String] = Set(
    "vectorKey",
    "entityIds",
    "window",
    "documentType",
    "sourceDataset",
    "embeddingModelVersion",
    "textHash",
    "runId",
    "textRef"
  )

  val Indexes: Vector[VectorIndexSpec] = Vector(
    VectorIndexSpec("researcher-profile-v1", Set("researcher-profile")),
    VectorIndexSpec("collaboration-community-v1", Set("collaboration-community")),
    VectorIndexSpec("paper-abstract-v1", Set("paper-summary")),
    VectorIndexSpec("collaboration-path-v1", Set("collaboration-path")),
    VectorIndexSpec("topic-institution-summary-v1", Set("topic-summary", "institution-summary"))
  )
  def index(name: String): Option[VectorIndexSpec] = Indexes.find(_.name == name)

  val PairId = "pair-[0-9a-f]{32}".r

  /** Entity-ID rule of a document type: what entityIds must hold. */
  def entityIdProblems(documentType: String, ids: Vector[String]): List[String] =
    val ok = documentType match
      case "researcher-profile"      => ids.size == 1 && IdFormat.isResearcherId(ids.head)
      case "collaboration-community" =>
        ids.size == 1 && IdFormat.isCommunityId(ids.head)
      case "collaboration-path" =>
        ids.size >= 3 && PairId.matches(ids.head) && ids.tail.forall(IdFormat.isResearcherId)
      case "topic-summary" | "institution-summary" =>
        ids.size == 1 && IdFormat.isNamespacedId(ids.head)
      case "paper-summary" => ids.size == 1 && IdFormat.isWorkId(ids.head)
      case _               => false
    if ok then Nil else List(s"entityIds do not fit $documentType: ${ids.mkString(" ")}")

  private def bytes(m: collection.Map[String, ujson.Value], keys: Iterable[String]): Int =
    keys.iterator
      .filter(m.contains)
      .map(k => k.getBytes(UTF_8).length + m(k).render().getBytes(UTF_8).length)
      .sum

  /** Approximate metadata size: UTF-8 bytes of each key plus its JSON value. The service's exact
    * accounting is not documented; this estimate is kept far below the limits.
    */
  def metadataBytes(m: ujson.Obj): (Int, Int) =
    (bytes(m.value, m.value.keys), bytes(m.value, FilterableKeys))

  /** Rule violations of one vector's metadata in an index; empty means valid. */
  def metadataProblems(indexName: String, key: String, m: ujson.Obj): List[String] =
    val v = m.value
    def str(k: String) = v.get(k).collect { case ujson.Str(s) => s }
    def strList(k: String) = v.get(k).collect {
      case ujson.Arr(xs) if xs.forall(_.isInstanceOf[ujson.Str]) => xs.map(_.str).toVector
    }
    def check(ok: Boolean, msg: => String) = if ok then Nil else List(msg)
    val (total, filterable) = metadataBytes(m)
    val docType = str("documentType").getOrElse("")
    val listChecks = Seq("entityIds", "topicIds", "institutionIds").toList.flatMap(k =>
      v.get(k) match
        case None    => Nil
        case Some(_) =>
          strList(k) match
            case None     => List(s"$k must be a list of strings")
            case Some(xs) =>
              check(xs.size <= MaxListEntries, s"$k has ${xs.size} entries, max $MaxListEntries") ++
                check(xs.nonEmpty, s"$k is empty; omit it") ++
                check(
                  k == "entityIds" || xs.forall(IdFormat.isNamespacedId),
                  s"$k holds a value that is not a namespaced ID"
                )
    )
    RequiredKeys.toList.sorted.flatMap(k => check(v.contains(k), s"missing metadata key $k")) ++
      v.keys.toList.sorted.flatMap(k =>
        check(
          FilterableKeys.contains(k) || NonFilterableKeys.contains(k),
          s"unexpected metadata key $k"
        )
      ) ++
      check(str("vectorKey").contains(key), "vectorKey differs from the vector key") ++
      check(
        index(indexName).exists(_.documentTypes.contains(docType)),
        s"documentType '$docType' not in $indexName"
      ) ++
      strList("entityIds").toList.flatMap(entityIdProblems(docType, _)) ++
      check(str("window").exists(_.nonEmpty), "window must be a non-empty string") ++
      check(
        str("sourceDataset").exists(s => SourceType.values.exists(_.toString == s)),
        "sourceDataset"
      ) ++
      check(str("embeddingModelVersion").exists(_.nonEmpty), "embeddingModelVersion") ++
      check(str("textHash").exists(Hashing.Shape.matches), "textHash must be sha256:<64 hex>") ++
      check(str("runId").exists(IdFormat.isRunId), "runId must be a run ID") ++
      check(
        str("textRef").exists(r =>
          S3Layout.keyProblems(r).isEmpty && r.startsWith("semantic-context/")
        ),
        "textRef must be a semantic-context/ key"
      ) ++
      check(
        v.get("communityId").forall(c => c.strOpt.exists(IdFormat.isCommunityId)),
        "communityId"
      ) ++ listChecks ++
      check(v.size <= MaxMetadataKeys, s"${v.size} metadata keys") ++
      check(
        total <= MetadataBudgetBytes,
        s"metadata about $total bytes, budget $MetadataBudgetBytes"
      ) ++
      check(
        filterable <= FilterableBudgetBytes,
        s"filterable metadata about $filterable bytes, budget $FilterableBudgetBytes"
      )

  /** Vector data rules: right dimension, finite, not all zero (cosine), unit length. */
  def dataProblems(data: Array[Float]): List[String] =
    if data.length != Dimension then List(s"dimension ${data.length}, index has $Dimension")
    else if data.exists(f => f.isNaN || f.isInfinite) then List("NaN or infinite component")
    else
      val norm = math.sqrt(data.map(f => f.toDouble * f).sum)
      if norm == 0 then List("zero vector")
      else if math.abs(norm - 1) > NormTolerance then List(f"not normalized (norm $norm%.4f)")
      else Nil

final case class VectorRecord(key: String, data: Array[Float], metadata: ujson.Obj):
  def textHash: String = metadata.value.get("textHash").flatMap(_.strOpt).getOrElse("")
  def modelVersion: String =
    metadata.value.get("embeddingModelVersion").flatMap(_.strOpt).getOrElse("")
  def runId: String = metadata.value.get("runId").flatMap(_.strOpt).getOrElse("")

object VectorRecord:
  def toJson(r: VectorRecord): ujson.Obj =
    ujson.Obj(
      "key" -> r.key,
      "data" -> ujson.Arr.from(r.data.map(f => ujson.Num(f.toDouble))),
      "metadata" -> r.metadata
    )
  def fromJson(j: ujson.Value): VectorRecord =
    VectorRecord(
      j("key").str,
      j("data").arr.map(_.num.toFloat).toArray,
      j("metadata") match {
        case o: ujson.Obj => o;
        case x            => throw IllegalArgumentException(s"metadata is not an object: $x")
      }
    )
  def fromJson(line: String): VectorRecord = fromJson(ujson.read(line))

/** CreateOnly never replaces a stored vector. Supersede replaces one only when the incoming vector
  * changes the embedding model version or the run.
  */
enum WriteMode:
  case CreateOnly, Supersede

object VectorWriteRule:
  /** Right(true) to write, Right(false) to skip (same text hash and model), Left(reason) to reject.
    */
  def decide(
      existing: Option[VectorRecord],
      in: VectorRecord,
      mode: WriteMode
  ): Either[String, Boolean] =
    existing match
      case None                                                                      => Right(true)
      case Some(e) if e.textHash == in.textHash && e.modelVersion == in.modelVersion => Right(false)
      case Some(e) if mode == WriteMode.CreateOnly                                   =>
        Left(s"KEY_CONFLICT: ${in.key} holds textHash ${e.textHash} model ${e.modelVersion}")
      case Some(e) if e.modelVersion != in.modelVersion || e.runId != in.runId => Right(true)
      case Some(e)                                                             =>
        Left(s"SUPERSEDE_WITHOUT_CHANGE: ${in.key} changes text without a new model or run")

/** Metadata filter, with the operators and array semantics of S3 Vectors metadata filtering. */
enum VectorFilter:
  case Eq(key: String, value: ujson.Value)
  case Ne(key: String, value: ujson.Value)
  case Gt(key: String, value: Double)
  case Gte(key: String, value: Double)
  case Lt(key: String, value: Double)
  case Lte(key: String, value: Double)
  case In(key: String, values: Seq[ujson.Value])
  case Nin(key: String, values: Seq[ujson.Value])
  case Exists(key: String, present: Boolean)
  case StartsWith(key: String, prefix: String)
  case And(filters: Seq[VectorFilter])
  case Or(filters: Seq[VectorFilter])

  def keys: Set[String] = this match
    case And(fs)          => fs.flatMap(_.keys).toSet
    case Or(fs)           => fs.flatMap(_.keys).toSet
    case Eq(k, _)         => Set(k);
    case Ne(k, _)         => Set(k);
    case Gt(k, _)         => Set(k)
    case Gte(k, _)        => Set(k);
    case Lt(k, _)         => Set(k);
    case Lte(k, _)        => Set(k)
    case In(k, _)         => Set(k);
    case Nin(k, _)        => Set(k);
    case Exists(k, _)     => Set(k)
    case StartsWith(k, _) => Set(k)

  /** Constraints as the service counts them: one per value evaluated. */
  def constraints: Int = this match
    case And(fs)    => fs.map(_.constraints).sum
    case Or(fs)     => fs.map(_.constraints).sum
    case In(_, vs)  => vs.size
    case Nin(_, vs) => vs.size
    case _          => 1

  /** Eq on an array value matches when any element is equal. A missing key fails every operator
    * except Ne, Nin, and Exists(false) (assumption: the documentation does not say).
    */
  def matches(m: ujson.Obj): Boolean =
    def values(k: String): Option[Seq[ujson.Value]] = m.value.get(k).map {
      case ujson.Arr(xs) => xs.toSeq
      case x             => Seq(x)
    }
    def num(k: String): Option[Double] = m.value.get(k).collect { case ujson.Num(n) => n }
    this match
      case Eq(k, v)         => values(k).exists(_.contains(v))
      case Ne(k, v)         => !values(k).exists(_.contains(v))
      case Gt(k, v)         => num(k).exists(_ > v)
      case Gte(k, v)        => num(k).exists(_ >= v)
      case Lt(k, v)         => num(k).exists(_ < v)
      case Lte(k, v)        => num(k).exists(_ <= v)
      case In(k, vs)        => values(k).exists(_.exists(vs.contains))
      case Nin(k, vs)       => !values(k).exists(_.exists(vs.contains))
      case Exists(k, p)     => m.value.contains(k) == p
      case StartsWith(k, p) => m.value.get(k).exists(_.strOpt.exists(_.startsWith(p)))
      case And(fs)          => fs.forall(_.matches(m))
      case Or(fs)           => fs.exists(_.matches(m))

final case class VectorHit(key: String, distance: Double, metadata: ujson.Obj)

final case class VectorBatchResult(
    written: Vector[String],
    skipped: Vector[String],
    rejected: Vector[(String, String)]
)

/** Read side; runtime services get only this. */
trait VectorReadPort:
  /** At most VectorContract.MaxGetBatch keys. */
  def get(index: String, keys: Seq[String]): Either[String, Vector[VectorRecord]]

  /** Nearest vectors by cosine distance (1 - cosine similarity), nearest first, ties by key. */
  def query(
      index: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[VectorFilter]
  ): Either[String, Vector[VectorHit]]

/** Load side. Each record is checked and decided on its own; a batch-level Left means the call
  * itself was invalid. TransientStoreException signals a retryable failure of the whole call.
  */
trait VectorLoadPort:
  def put(
      index: String,
      batch: Seq[VectorRecord],
      mode: WriteMode
  ): Either[String, VectorBatchResult]

/** File-backed local fake of the vector bucket: one append-only JSON Lines log per index,
  * root/<index>.jsonl, replayed on open (a later line for a key replaces the earlier one).
  */
final class FileVectorStore(root: Path):
  private val indexes = mutable.Map.empty[String, mutable.LinkedHashMap[String, VectorRecord]]

  /** Test hook: the next n put calls fail with TransientStoreException before writing. */
  var transientFailuresToInject: Int = 0

  private def logOf(index: String) = root.resolve(s"$index.jsonl")

  private def loaded(index: String) = indexes.getOrElseUpdate(
    index, {
      val m = mutable.LinkedHashMap.empty[String, VectorRecord]
      if Files.exists(logOf(index)) then
        Files.readAllLines(logOf(index), UTF_8).asScala.filter(_.nonEmpty).foreach { l =>
          val r = VectorRecord.fromJson(l)
          m(r.key) = r
        }
      m
    }
  )

  def size(index: String): Int = synchronized(loaded(index).size)

  val reader: VectorReadPort = new VectorReadPort:
    def get(index: String, keys: Seq[String]) = FileVectorStore.this.synchronized {
      if VectorContract.index(index).isEmpty then Left(s"unknown index $index")
      else if keys.size > VectorContract.MaxGetBatch then
        Left(s"${keys.size} keys, max ${VectorContract.MaxGetBatch}")
      else Right(keys.flatMap(loaded(index).get).toVector)
    }

    def query(index: String, vector: Array[Float], topK: Int, filter: Option[VectorFilter]) =
      FileVectorStore.this.synchronized {
        val nonFilterable =
          filter.toSeq.flatMap(_.keys).filter(VectorContract.NonFilterableKeys.contains)
        if VectorContract.index(index).isEmpty then Left(s"unknown index $index")
        else if vector.length != VectorContract.Dimension then
          Left(s"query dimension ${vector.length}")
        else if topK < 1 || topK > VectorContract.MaxTopK then Left(s"topK $topK out of range")
        else if nonFilterable.nonEmpty then
          Left(s"filter on non-filterable keys ${nonFilterable.mkString(",")}")
        else if filter.exists(_.constraints > VectorContract.MaxFilterConstraints) then
          Left("too many filter constraints")
        else
          val qn = math.sqrt(vector.map(f => f.toDouble * f).sum)
          if qn == 0 then Left("zero query vector")
          else
            Right(
              loaded(index).values
                .filter(r => filter.forall(_.matches(r.metadata)))
                .map { r =>
                  var dot = 0.0
                  var rn = 0.0
                  var i = 0
                  while i < vector.length do
                    dot += vector(i).toDouble * r.data(i)
                    rn += r.data(i).toDouble * r.data(i)
                    i += 1
                  VectorHit(r.key, 1 - dot / (qn * math.sqrt(rn)), r.metadata)
                }
                .toVector
                .sortBy(h => (h.distance, h.key))
                .take(topK)
            )
      }

  val loader: VectorLoadPort = new VectorLoadPort:
    def put(index: String, batch: Seq[VectorRecord], mode: WriteMode) =
      FileVectorStore.this.synchronized {
        if VectorContract.index(index).isEmpty then Left(s"unknown index $index")
        else if batch.isEmpty || batch.size > VectorContract.MaxPutBatch then
          Left(s"batch of ${batch.size}, allowed 1 to ${VectorContract.MaxPutBatch}")
        else if transientFailuresToInject > 0 then
          transientFailuresToInject -= 1
          throw TransientStoreException("injected transient failure")
        else
          val store = loaded(index)
          val plan = VectorBatchPlan.plan(index, batch, mode, store.get)
          val lines = new StringBuilder
          plan.toWrite.foreach { r =>
            store(r.key) = r
            lines ++= VectorRecord.toJson(r).render() + "\n"
          }
          if lines.nonEmpty then
            Files.createDirectories(root)
            Files.writeString(
              logOf(index),
              lines.toString,
              UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND
            )
          Right(plan.result)
      }

/** The client-side decision for one put call, shared by the fake and the live adapter: each record
  * is validated, then VectorWriteRule decides against the stored vector.
  */
final case class VectorBatchPlan(toWrite: Vector[VectorRecord], result: VectorBatchResult)

object VectorBatchPlan:
  def recordProblems(index: String, r: VectorRecord): List[String] =
    (if r.key.isEmpty || r.key.length > VectorContract.MaxKeyLength then List("bad key length")
     else Nil) ++
      VectorContract.metadataProblems(index, r.key, r.metadata) ++
      VectorContract.dataProblems(r.data)

  def plan(
      index: String,
      batch: Seq[VectorRecord],
      mode: WriteMode,
      existing: String => Option[VectorRecord]
  ): VectorBatchPlan =
    val dupes = batch.groupBy(_.key).collect { case (k, rs) if rs.size > 1 => k }.toSet
    val toWrite = Vector.newBuilder[VectorRecord]
    val skipped = Vector.newBuilder[String]
    val rejected = Vector.newBuilder[(String, String)]
    batch.foreach { r =>
      val problems = recordProblems(index, r)
      if dupes(r.key) then rejected += r.key -> "DUPLICATE_KEY_IN_BATCH"
      else if problems.nonEmpty then rejected += r.key -> s"INVALID: ${problems.mkString("; ")}"
      else
        VectorWriteRule.decide(existing(r.key), r, mode) match
          case Left(reason) => rejected += r.key -> reason
          case Right(false) => skipped += r.key
          case Right(true)  => toWrite += r
    }
    val w = toWrite.result()
    VectorBatchPlan(w, VectorBatchResult(w.map(_.key), skipped.result(), rejected.result()))

/** Vector-record files: JSON Lines {"key", "data", "metadata"} under <index>/part-*.jsonl, the
  * embedding runner's output and the vector backup layout.
  */
object VectorRecordFiles:
  /** The index a file belongs to: its parent directory name. */
  def indexOf(file: Path): Option[String] =
    Option(file.getParent).map(_.getFileName.toString).filter(VectorContract.index(_).nonEmpty)

  def foreachRecord(file: Path)(f: (Long, Either[String, VectorRecord]) => Unit): Unit =
    scala.util.Using.resource(Files.lines(file, UTF_8)) { lines =>
      val it = lines.iterator
      var line = 0L
      while it.hasNext do
        val l = it.next()
        line += 1
        if l.trim.nonEmpty then
          f(
            line,
            scala.util.Try(VectorRecord.fromJson(l)).toEither.left.map(_.getMessage.take(200))
          )
    }

  /** Validates every record (key, metadata, data) and duplicate keys per index. */
  def validate(files: Seq[Path]): (Vector[Violation], Map[String, Int]) =
    val out = Vector.newBuilder[Violation]
    val seen = mutable.HashMap.empty[(String, String), String]
    val counts = mutable.TreeMap.empty[String, Int]
    files.foreach { file =>
      val src = file.toString
      indexOf(file) match
        case None => out += Violation(src, 0, "UNKNOWN_INDEX", "parent directory is not an index")
        case Some(index) =>
          foreachRecord(file) {
            case (line, Left(e))  => out += Violation(src, line, "MALFORMED", e)
            case (line, Right(r)) =>
              VectorBatchPlan
                .recordProblems(index, r)
                .foreach(p => out += Violation(src, line, "BAD_VECTOR", p))
              seen.get(index -> r.key) match
                case Some(at) =>
                  out += Violation(src, line, "DUPLICATE_KEY", s"${r.key} first at $at")
                case None =>
                  seen(index -> r.key) = s"$src:$line"
                  counts(index) = counts.getOrElse(index, 0) + 1
          }
    }
    (out.result(), counts.toMap)

final case class VectorLoadSummary(
    written: Int,
    skipped: Int,
    rejected: Int,
    batches: Int,
    retries: Int
)

/** Batched vector writes with bounded retry of transient failures. Rejected vectors go to the
  * rejected sink (one JSON object each, never the vector data); per-batch counts and retries go to
  * the RunLedger under entry keys vectors#<index>#attempt-<a>#batch-<nnnnn>.
  */
object VectorLoader:
  def load(
      store: VectorLoadPort,
      index: String,
      records: Seq[VectorRecord],
      mode: WriteMode,
      batchSize: Int,
      policy: RetryPolicy,
      sleep: Long => Unit,
      ledger: RunLedgerPort,
      ledgerRunId: String,
      attempt: Int,
      rejectedSink: ujson.Obj => Unit,
      now: () => String
  ): VectorLoadSummary =
    require(batchSize >= 1 && batchSize <= VectorContract.MaxPutBatch, s"batchSize $batchSize")
    def reject(r: VectorRecord, reason: String): Unit =
      rejectedSink(
        ujson.Obj(
          "index" -> index,
          "key" -> r.key,
          "reason" -> reason,
          "textHash" -> r.textHash,
          "runId" -> r.runId
        )
      )
    // First occurrence of a key wins; a later identical one is skipped, a different one rejected.
    val first = mutable.LinkedHashMap.empty[String, VectorRecord]
    var preSkipped = 0
    var preRejected = 0
    records.foreach { r =>
      first.get(r.key) match
        case None => first(r.key) = r
        case Some(f) if f.textHash == r.textHash && f.modelVersion == r.modelVersion =>
          preSkipped += 1
        case Some(_) =>
          preRejected += 1
          reject(r, "DUPLICATE_KEY")
    }
    var written = 0
    var skipped = preSkipped
    var rejected = preRejected
    var retries = 0
    val batches = first.values.toVector.grouped(batchSize).toVector
    batches.zipWithIndex.foreach { (batch, n) =>
      val entryKey = f"vectors#$index#attempt-$attempt#batch-$n%05d"
      def record(status: String, details: Map[String, String]) =
        ledger.append(
          LedgerEntry(ledgerRunId, entryKey, "VECTOR_BATCH", now(), details + ("status" -> status))
        )
      val (result, attempts) =
        try Retry.run(policy, sleep)(store.put(index, batch, mode))
        catch
          case e: TransientStoreException =>
            record(
              "FAILED",
              Map("size" -> batch.size.toString, "attempts" -> policy.maxAttempts.toString)
            )
            throw e
      retries += attempts - 1
      val r = result.fold(
        err => VectorBatchResult(Vector.empty, Vector.empty, batch.map(_.key -> err)),
        identity
      )
      val byKey = batch.map(x => x.key -> x).toMap
      r.rejected.foreach((k, reason) => reject(byKey(k), reason))
      written += r.written.size
      skipped += r.skipped.size
      rejected += r.rejected.size
      record(
        "DONE",
        Map(
          "written" -> r.written.size.toString,
          "skipped" -> r.skipped.size.toString,
          "rejected" -> r.rejected.size.toString,
          "attempts" -> attempts.toString
        )
      )
    }
    VectorLoadSummary(written, skipped, rejected, batches.size, retries)

  /** Local rejected-vectors file (JSON Lines, appended). */
  def fileSink(path: Path): ujson.Obj => Unit = o =>
    Files.createDirectories(path.getParent)
    Files.writeString(
      path,
      o.render() + "\n",
      UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
