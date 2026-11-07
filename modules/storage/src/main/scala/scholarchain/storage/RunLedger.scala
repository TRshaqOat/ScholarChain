package scholarchain.storage

import scholarchain.core.IdFormat
import upickle.default.{ReadWriter, read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** One RunLedger record (DynamoDB table RunLedger: partition key runId, sort key entryKey).
  * recordedAt is an ISO-8601 instant; details holds counts and reasons as strings.
  */
final case class LedgerEntry(
    runId: String,
    entryKey: String,
    entryType: String,
    recordedAt: String,
    details: Map[String, String]
) derives ReadWriter

enum AppendOutcome:
  case Appended, Unchanged, Conflict

/** Append-only run ledger. An entry key is written once. Re-appending the same entry (ignoring
  * recordedAt, so a retried append after a crash is harmless) is Unchanged; different content under
  * an existing key is a Conflict and is not written.
  */
trait RunLedgerPort:
  def append(entry: LedgerEntry): AppendOutcome
  def entries(runId: String): Vector[LedgerEntry]

/** File-backed local fake: one JSON Lines file per run, root/<runId>.jsonl, opened for append. */
final class FileRunLedger(root: Path) extends RunLedgerPort:
  private val cache = mutable.Map.empty[String, mutable.LinkedHashMap[String, LedgerEntry]]

  private def fileOf(runId: String): Path =
    require(IdFormat.isRunId(runId), s"not a run ID: $runId")
    root.resolve(s"$runId.jsonl")

  private def loaded(runId: String): mutable.LinkedHashMap[String, LedgerEntry] =
    cache.getOrElseUpdate(
      runId, {
        val m = mutable.LinkedHashMap.empty[String, LedgerEntry]
        val f = fileOf(runId)
        if Files.exists(f) then
          Files.readAllLines(f, UTF_8).asScala.filter(_.nonEmpty).foreach { l =>
            val e = read[LedgerEntry](l)
            m.getOrElseUpdate(e.entryKey, e)
          }
        m
      }
    )

  def append(entry: LedgerEntry): AppendOutcome = synchronized {
    require(entry.entryKey.nonEmpty && entry.entryType.nonEmpty, "entryKey and entryType required")
    val m = loaded(entry.runId)
    m.get(entry.entryKey) match
      case Some(e) if e.copy(recordedAt = entry.recordedAt) == entry => AppendOutcome.Unchanged
      case Some(_)                                                   => AppendOutcome.Conflict
      case None                                                      =>
        Files.createDirectories(root)
        Files.writeString(
          fileOf(entry.runId),
          write(entry) + "\n",
          UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
        m(entry.entryKey) = entry
        AppendOutcome.Appended
  }

  def entries(runId: String): Vector[LedgerEntry] = synchronized(loaded(runId).values.toVector)

/** RunLedger on the DynamoDB RunLedger table (live loaders), through the key-value ports. Same
  * semantics as FileRunLedger: a retried append that differs only in recordedAt is Unchanged.
  */
final class KeyValueRunLedger(reader: KeyValueReadPort, loader: KeyValueLoadPort)
    extends RunLedgerPort:
  import AttributeValue.{M, S}
  val SchemaVersion = "run-ledger-v1"

  private def toItem(e: LedgerEntry): Item = Map(
    "runId" -> S(e.runId),
    "entryKey" -> S(e.entryKey),
    "entryType" -> S(e.entryType),
    "recordedAt" -> S(e.recordedAt),
    "schemaVersion" -> S(SchemaVersion),
    "details" -> M(e.details.map((k, v) => k -> S(v)))
  )
  private def toEntry(i: Item): LedgerEntry =
    def s(k: String) = i.get(k).collect { case S(v) => v }.getOrElse("")
    val details = i.get("details").collect { case M(m) => m.collect { case (k, S(v)) => k -> v } }
    LedgerEntry(
      s("runId"),
      s("entryKey"),
      s("entryType"),
      s("recordedAt"),
      details.getOrElse(Map.empty)
    )

  def append(entry: LedgerEntry): AppendOutcome =
    loader.putNew(TableName.RunLedger, toItem(entry)) match
      case Left(p)                      => throw IllegalArgumentException(p.mkString("; "))
      case Right(LoadOutcome.Inserted)  => AppendOutcome.Appended
      case Right(LoadOutcome.Unchanged) => AppendOutcome.Unchanged
      case Right(LoadOutcome.Conflict)  =>
        reader.get(TableName.RunLedger, entry.runId, entry.entryKey).map(toEntry) match
          case Some(e) if e.copy(recordedAt = entry.recordedAt) == entry => AppendOutcome.Unchanged
          case _                                                         => AppendOutcome.Conflict

  // shortcut: at most 1,000 entries per run are read back; page the query if a run outgrows that.
  def entries(runId: String): Vector[LedgerEntry] =
    reader.query(TableName.RunLedger, runId, "", DynamoContract.QueryMaxLimit).map(toEntry)
