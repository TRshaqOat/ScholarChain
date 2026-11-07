package scholarchain.storage

import java.io.Reader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Using

final class CsvFormatException(val line: Long, message: String)
    extends RuntimeException(s"line $line: $message")

/** RFC 4180 CSV, the dialect the Neptune openCypher loader reads: comma separator, fields with a
  * comma, quote, CR, or LF are double-quoted, and a quote inside them is doubled. Blank lines are
  * skipped. An empty field means "no value".
  */
object Csv:
  def render(fields: Seq[String]): String = fields.map(quote).mkString(",")

  private def quote(s: String): String =
    if s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + s.replace("\"", "\"\"") + "\""
    else s

  /** Streams the records of a UTF-8 file to f with the 1-based line each record starts on. */
  def foreachRecord(path: Path)(f: (Long, Vector[String]) => Unit): Unit =
    Using.resource(Files.newBufferedReader(path, UTF_8))(in => parse(in, f))

  def parseString(text: String): Vector[Vector[String]] =
    val out = Vector.newBuilder[Vector[String]]
    parse(new java.io.StringReader(text), (_, r) => out += r)
    out.result()

  private def parse(in: Reader, f: (Long, Vector[String]) => Unit): Unit =
    val fields = Vector.newBuilder[String]
    val sb = new StringBuilder
    var line = 1L
    var start = 1L
    var inQuotes = false
    var wasQuoted = false
    var any = false
    def endField(): Unit =
      fields += sb.toString
      sb.clear()
      wasQuoted = false
    var c = in.read()
    while c != -1 do
      val ch = c.toChar
      if inQuotes then
        if ch == '"' then
          val n = in.read()
          if n == '"' then
            sb += '"'
            c = in.read()
          else
            inQuotes = false
            c = n
        else
          if ch == '\n' then line += 1
          sb += ch
          c = in.read()
      else
        ch match
          case '"' =>
            if sb.nonEmpty || wasQuoted then throw CsvFormatException(line, "stray quote")
            inQuotes = true
            wasQuoted = true
            any = true
          case ',' =>
            endField()
            any = true
          case '\r' => ()
          case '\n' =>
            if any || sb.nonEmpty then
              endField()
              f(start, fields.result())
              fields.clear()
              any = false
            line += 1
            start = line
          case other =>
            if wasQuoted then throw CsvFormatException(line, "text after a closing quote")
            sb += other
            any = true
        c = in.read()
    if inQuotes then throw CsvFormatException(start, "unterminated quoted field")
    if any || sb.nonEmpty then
      endField()
      f(start, fields.result())
