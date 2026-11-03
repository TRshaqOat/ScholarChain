package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.TaskAttemptID
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files

class DblpRecordReaderSpec extends AnyFunSuite:
  import TestSupport.*

  private val conf = new Configuration()

  /** Hand-built ISO-8859-1 file: Latin-1 bytes, DTD entities, a numeric reference beyond Latin-1, a
    * multiline title with inline markup, a record with no optional fields, repeated authors, and a
    * non-record `<booktitle>` that must not be taken for `<book>`.
    */
  val edgeXml: String =
    """<?xml version="1.0" encoding="ISO-8859-1"?>
      |<!DOCTYPE dblp SYSTEM "dblp.dtd">
      |<dblp>
      |<inproceedings mdate="2020-01-01" key="conf/ics/Jos20">
      |<author>José Müller</author>
      |<author>Ren&eacute;e &Ouml;zt&uuml;rk</author>
      |<title>A multiline
      |title with <i>markup</i> and &#8364; costs.</title>
      |<year>2020</year>
      |<booktitle>ICS</booktitle>
      |<ee>https://doi.org/10.1145/ABC.123</ee>
      |</inproceedings>
      |<article key="journals/tpds/Empty21">
      |</article>
      |<article mdate="2021-02-02" key="journals/tpds/Rep21">
      |<author>Wei Wang 0001</author>
      |<author>Wei Wang 0001</author>
      |<author>Ann Lee</author>
      |<title>Repeated authors.</title>
      |<year>2021</year>
      |<journal>IEEE Trans. Parallel Distributed Syst.</journal>
      |</article>
      |</dblp>
      |""".stripMargin

  private def write(xml: String): Path =
    val f = Files.createTempFile("dblp-edge", ".xml")
    Files.write(f, xml.getBytes(ISO_8859_1))
    new Path(f.toUri)

  private def read(file: Path, start: Long, length: Long): Vector[(Long, String)] =
    val rr = new DblpRecordReader
    rr.initialize(
      new FileSplit(file, start, length, Array.empty[String]),
      new TaskAttemptContextImpl(conf, new TaskAttemptID())
    )
    try
      Iterator
        .continually(rr.nextKeyValue())
        .takeWhile(identity)
        .map(_ => (rr.getCurrentKey.get, rr.getCurrentValue.toString))
        .toVector
    finally rr.close()

  private def readSplits(file: Path, len: Long, bounds: Seq[Long]): Vector[(Long, String)] =
    val cuts = (0L +: bounds.filter(b => b > 0 && b < len).distinct.sorted :+ len)
    cuts.sliding(2).toVector.flatMap(c => read(file, c(0), c(1) - c(0)))

  /** Independent expectation: offsets and text of every record element, by regex over the file. */
  private def expected(xml: String): Vector[(Long, String)] =
    """(?s)<(article|inproceedings)[ >].*?</\1>""".r
      .findAllMatchIn(xml)
      .map(m => (m.start.toLong, m.matched))
      .toVector

  test("a two-way split at every byte offset yields every record exactly once, intact") {
    val file = write(edgeXml)
    val len = edgeXml.length.toLong // ISO-8859-1: one byte per char
    val want = expected(edgeXml)
    assert(want.size == 3)
    for b <- 1L until len do assert(readSplits(file, len, Seq(b)) == want, s"split at byte $b")
  }

  test("many small splits, including one-byte splits, yield every record exactly once") {
    val file = write(edgeXml)
    val len = edgeXml.length.toLong
    for size <- Seq(1L, 2L, 3L, 17L, 64L, 257L) do
      assert(
        readSplits(file, len, (size until len by size)) == expected(edgeXml),
        s"split size $size"
      )
  }

  test("the committed DBLP sample reads identically with one split and with 4 KB splits") {
    val f = sample("dblp/dblp-sample.xml")
    val xml = TestSupport.readFile(f, ISO_8859_1)
    val file = new Path(f.toURI)
    val whole = readSplits(file, f.length, Nil)
    assert(whole.size == 40)
    assert(whole == expected(xml))
    assert(readSplits(file, f.length, (4096L until f.length by 4096L)) == whole)
  }

  test("values are decoded from ISO-8859-1, so Latin-1 bytes become Unicode text") {
    val recs = readSplits(write(edgeXml), edgeXml.length, Nil)
    assert(recs.head._2.contains("José Müller"))
  }

  test("a truncated final record is returned so the mapper can reject it") {
    val xml = "<dblp>\n<article key=\"journals/x/T\">\n<title>cut"
    val recs = readSplits(write(xml), xml.length, Nil)
    assert(recs.map(_._2) == Vector("<article key=\"journals/x/T\">\n<title>cut"))
    assert(parser.parseXml(recs.head._2).left.map(_.reason) == Left("malformed-record"))
  }

  test("compressed input is not split and still reads every record") {
    val f = Files.createTempFile("dblp-edge", ".xml.gz")
    val gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(f))
    try gz.write(edgeXml.getBytes(ISO_8859_1))
    finally gz.close()
    val path = new Path(f.toUri)
    assert(read(path, 0, Files.size(f)).map(_._2) == expected(edgeXml).map(_._2))
  }
