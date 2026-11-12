package scholarchain.storage

import scholarchain.core.config.TimeWindow

import java.io.{ByteArrayOutputStream, RandomAccessFile}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable

/** Read-only KeyValueReadPort over a `curated/dynamodb/snapshot=<d>/run=<r>` directory of item
  * files (STORAGE.md, "Indexed item-file reader"). Open reads every file once, validates every item
  * against DynamoContract (as DynamoItemFile.validate does) and keeps only a key index in memory:
  * per table and partition key, the sort keys with their file, byte offset, and line length. Each
  * get or query reads the matched lines from disk, checks them again, and returns them in the
  * canonical number form DynamoDB returns (like InMemoryDynamo). Nothing is ever written.
  *
  * The full Phase 7 items (876,228) do not fit in memory beside the full graph fake in 6 GiB as an
  * InMemoryDynamo; this index does (STORAGE.md gives the measured heap).
  */
final class ItemFileReader private (
    files: Vector[ItemFileReader.FileStamp],
    index: Map[TableName, Map[String, Vector[ItemFileReader.Loc]]],
    windows: Map[String, TimeWindow]
):
  import ItemFileReader.*

  def itemCount(table: TableName): Long =
    index.get(table).fold(0L)(_.values.map(_.size.toLong).sum)
  def itemCount: Long = index.keys.toVector.map(itemCount).sum

  /** The item behind one index entry. Throws IllegalStateException when the file changed since open
    * or the line no longer holds a valid item with the indexed key: the index would be wrong.
    */
  private def readItem(table: TableName, pk: String, l: Loc): Item =
    val f = files(l.file)
    def fail(why: String) =
      throw IllegalStateException(s"${f.path} at byte ${l.offset}: $why since the index was built")
    if !f.unchanged then fail("the file changed")
    val bytes = new Array[Byte](l.length)
    scala.util.Using.resource(RandomAccessFile(f.path.toFile, "r")) { raf =>
      raf.seek(l.offset)
      raf.readFully(bytes)
    }
    val spec = DynamoContract.Tables(table)
    DynamoItemFile.parseLine(new String(bytes, UTF_8)) match
      case Right(item)
          if DynamoContract.key(spec, item) == (pk, l.sk) &&
            DynamoContract.problems(spec, item, windows).isEmpty =>
        AttributeValue.canonicalItem(item) // what DynamoDB would return
      case _ => fail("the line is not the indexed item")

  val reader: KeyValueReadPort = new KeyValueReadPort:
    def get(table: TableName, pk: String, sk: String): Option[Item] =
      index.get(table).flatMap(_.get(pk)).flatMap(_.find(_.sk == sk)).map(readItem(table, pk, _))
    def query(table: TableName, pk: String, skPrefix: String, limit: Int): Vector[Item] =
      require(limit >= 1 && limit <= DynamoContract.QueryMaxLimit, s"limit $limit out of range")
      index
        .get(table)
        .flatMap(_.get(pk))
        .getOrElse(Vector.empty)
        .iterator
        .filter(_.sk.startsWith(skPrefix))
        .take(limit)
        .map(readItem(table, pk, _))
        .toVector

object ItemFileReader:
  /** One indexed line. A class, not a tuple, so file and offset stay unboxed. */
  final class Loc(val sk: String, val file: Int, val offset: Long, val length: Int)

  final case class FileStamp(path: Path, size: Long, modified: java.nio.file.attribute.FileTime):
    def unchanged: Boolean =
      Files.isRegularFile(path) && Files.size(path) == size &&
        Files.getLastModifiedTime(path) == modified

  private def stamp(p: Path) = FileStamp(p, Files.size(p), Files.getLastModifiedTime(p))

  /** At most this many violations are returned (all of them are counted in the last one). */
  val MaxViolations = 100

  /** Indexes dir, a `curated/dynamodb/snapshot=<d>/run=<r>` directory of loader-table directories
    * (DynamoItemFile.tableDirs). Any malformed line, contract violation, or duplicate key fails the
    * open with Left, as does a missing, empty, or runtime-table directory; nothing is half-opened.
    */
  def open(dir: Path, windows: Seq[TimeWindow]): Either[Vector[Violation], ItemFileReader] =
    DynamoItemFile
      .tableDirs(dir)
      .left
      .map(e => Vector(Violation(dir.toString, 0, "BAD_INPUT", e)))
      .flatMap { tables =>
        val byWindow = ProvenanceRules.windowsByName(windows)
        val files = Vector.newBuilder[FileStamp]
        val bad = Vector.newBuilder[Violation]
        var badCount = 0L
        def report(v: Violation) =
          badCount += 1
          if badCount <= MaxViolations then bad += v
        var fileNo = 0
        val index = tables.map { (t, d) =>
          val spec = DynamoContract.Tables(t)
          val m = mutable.HashMap.empty[String, Vector[Loc]]
          DynamoItemFile.files(d).foreach { f =>
            val before = stamp(f)
            files += before
            val no = fileNo
            fileNo += 1
            var line = 0L
            lines(f) { (offset, bytes) =>
              line += 1
              val text = new String(bytes, UTF_8)
              if text.trim.nonEmpty then
                DynamoItemFile.parseLine(text) match
                  case Left(e)     => report(Violation(f.toString, line, "BAD_ITEM", e))
                  case Right(item) =>
                    DynamoContract.problems(spec, item, byWindow) match
                      case Nil =>
                        val (pk, sk) = DynamoContract.key(spec, item)
                        val prior = m.getOrElse(pk, Vector.empty)
                        if prior.exists(_.sk == sk) then
                          report(Violation(f.toString, line, "DUPLICATE_KEY", s"$pk / $sk"))
                        else m(pk) = prior :+ Loc(sk, no, offset, bytes.length)
                      case ps => report(Violation(f.toString, line, "BAD_ITEM", ps.mkString("; ")))
            }
            if !before.unchanged then
              report(Violation(f.toString, 0, "BAD_INPUT", "file changed while it was indexed"))
          }
          // Sort-key order, as InMemoryDynamo and a DynamoDB Query return items.
          t -> m.view.mapValues(_.sortBy(_.sk)).toMap
        }.toMap
        val found = bad.result()
        if found.isEmpty then Right(ItemFileReader(files.result(), index, byWindow))
        else if badCount > found.size then
          Left(found :+ Violation(dir.toString, 0, "TRUNCATED", s"$badCount violations in total"))
        else Left(found)
      }

  /** Calls each(byte offset, bytes) for every line of the file, without the newline. */
  private def lines(f: Path)(each: (Long, Array[Byte]) => Unit): Unit =
    scala.util.Using.resource(Files.newInputStream(f)) { in =>
      val chunk = new Array[Byte](1 << 20)
      val cur = ByteArrayOutputStream()
      var start = 0L
      var pos = 0L
      var n = in.read(chunk)
      while n != -1 do
        var i = 0
        var from = 0
        while i < n do
          if chunk(i) == '\n' then
            cur.write(chunk, from, i - from)
            each(start, cur.toByteArray)
            cur.reset()
            start = pos + i + 1
            from = i + 1
          i += 1
        cur.write(chunk, from, n - from)
        pos += n
        n = in.read(chunk)
      if cur.size > 0 then each(start, cur.toByteArray)
    }
