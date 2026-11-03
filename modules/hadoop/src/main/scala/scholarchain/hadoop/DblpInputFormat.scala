package scholarchain.hadoop

import org.apache.hadoop.fs.{FSDataInputStream, Path}
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, RecordReader, TaskAttemptContext}

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.ISO_8859_1

/** Input format for DBLP XML that never splits a record across mappers.
  *
  * Contract: key = byte offset of the record's `<` in the file; value = the complete record element
  * (start tag through matching end tag), decoded from ISO-8859-1 (the DBLP release encoding), so
  * the value is Unicode text. A record belongs to the split that contains its first byte; the
  * reader reads past the split end to finish it, and the next split skips forward to the first
  * record that starts inside it. DBLP record elements are flat children of `<dblp>` and never nest,
  * so the first matching end tag closes the record. Compressed files are read as one split.
  */
class DblpInputFormat extends FileInputFormat[LongWritable, Text]:
  override def createRecordReader(split: InputSplit, ctx: TaskAttemptContext) =
    new DblpRecordReader

  override protected def isSplitable(ctx: JobContext, file: Path): Boolean =
    new CompressionCodecFactory(ctx.getConfiguration).getCodec(file) == null

object DblpRecordReader:
  /** Every record element in the DBLP DTD; the mapper decides which ones it supports. */
  val RecordElements: Vector[String] = Vector(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis",
    "www",
    "person",
    "data"
  )

class DblpRecordReader extends RecordReader[LongWritable, Text]:
  import DblpRecordReader.RecordElements

  private var in: InputStream = scala.compiletime.uninitialized
  private var raw: FSDataInputStream = scala.compiletime.uninitialized
  private var start = 0L
  private var end = 0L
  private var pos = 0L
  private var pushedBack = -1
  private val key = new LongWritable
  private val value = new Text
  private val maxName = RecordElements.map(_.length).max

  override def initialize(split: InputSplit, ctx: TaskAttemptContext): Unit =
    val fs = split.asInstanceOf[FileSplit]
    val conf = ctx.getConfiguration
    val path = fs.getPath
    raw = path.getFileSystem(conf).open(path)
    Option(new CompressionCodecFactory(conf).getCodec(path)) match
      case Some(codec) =>
        in = new BufferedInputStream(codec.createInputStream(raw))
        start = 0L; end = Long.MaxValue
      case None =>
        start = fs.getStart; end = start + fs.getLength
        raw.seek(start)
        in = new BufferedInputStream(raw)
    pos = start

  private def read(): Int =
    if pushedBack >= 0 then
      val b = pushedBack; pushedBack = -1; pos += 1; b
    else
      val b = in.read()
      if b >= 0 then pos += 1
      b

  private def unread(b: Int): Unit =
    pushedBack = b; pos -= 1

  /** Scans to the next record start tag whose `<` lies before the split end. Returns the element
    * name, with `<name` already consumed into `buf`.
    */
  private def findStart(buf: ByteArrayOutputStream): Option[String] =
    var found: Option[String] = None
    var eof = false
    while found.isEmpty && !eof do
      val b = read()
      if b < 0 then eof = true
      else if b == '<' then
        val at = pos - 1
        if at >= end then eof = true
        else
          val name = new StringBuilder
          var c = read()
          while c >= 'a' && c <= 'z' && name.length <= maxName do
            name.append(c.toChar); c = read()
          if c >= 0 then unread(c)
          val delimited = c == ' ' || c == '>' || c == '\t' || c == '\n' || c == '\r'
          if delimited && RecordElements.contains(name.toString) then
            key.set(at)
            buf.reset(); buf.write('<'); buf.write(name.toString.getBytes(ISO_8859_1))
            found = Some(name.toString)
    found

  /** Copies bytes into buf through the end tag `</name>`; false on a truncated record. */
  private def readThroughEnd(name: String, buf: ByteArrayOutputStream): Boolean =
    val endTag = s"</$name>".getBytes(ISO_8859_1)
    var matched = 0
    var done = false
    var eof = false
    while !done && !eof do
      val b = read()
      if b < 0 then eof = true
      else
        buf.write(b)
        if b == endTag(matched) then
          matched += 1
          if matched == endTag.length then done = true
        else matched = if b == endTag(0) then 1 else 0
    done

  override def nextKeyValue(): Boolean =
    val buf = new ByteArrayOutputStream(4096)
    findStart(buf) match
      case None       => false
      case Some(name) =>
        // A truncated final record is still returned, so the mapper rejects it visibly.
        readThroughEnd(name, buf)
        value.set(new String(buf.toByteArray, ISO_8859_1))
        true

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text = value
  override def getProgress: Float =
    if end == Long.MaxValue || end == start then 0f
    else math.min(1f, (pos - start).toFloat / (end - start))
  override def close(): Unit = if in != null then in.close()
