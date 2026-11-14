package scholarchain.tests.adversarial

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.{Job, TaskAttemptID}
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{SourceType, StableIds}
import scholarchain.hadoop.{DblpEntities, DblpInputFormat, DblpParser, DblpRecordReader, RunContext}

import java.io.File
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Phase 11 work order 4 (ruling 12), spec category "malformed and truncated DBLP XML" at the
  * Hadoop record reader (DblpInputFormat): truncation, split boundaries through the real input
  * format, and record start tags that are not elements (inside an XML comment). Local `file:` paths
  * in JVM temporary directories only; no mini-cluster and no network.
  */
class DblpRecordReaderAdversarialSpec extends AnyFunSuite:
  private val conf = Configuration()
  private lazy val repo =
    Iterator
      .iterate(File(sys.props("user.dir")).getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => File(d, "datasets/samples/dblp").isDirectory)
      .get
  private lazy val parser = DblpParser(
    DblpEntities.parseDtd(
      String(
        Files.readAllBytes(File(repo, "datasets/samples/dblp/dblp-2023-06-28.dtd").toPath),
        ISO_8859_1
      )
    )
  )
  private val ctx = RunContext(
    "adv-j01",
    StableIds.runId("adv", "code", "config-v1", Seq("dblp=2026-10-03"), 1),
    Map(SourceType.DBLP -> "2026-10-03"),
    "2026-10-03",
    "graph-v1"
  )

  private def withFile[A](xml: String, suffix: String = ".xml")(body: (Path, Long) => A): A =
    val dir = Files.createTempDirectory("adv-dblp-reader-")
    try
      val f = dir.resolve("dblp" + suffix)
      if suffix.endsWith(".gz") then
        val gz = java.util.zip.GZIPOutputStream(Files.newOutputStream(f))
        try gz.write(xml.getBytes(ISO_8859_1))
        finally gz.close()
      else Files.write(f, xml.getBytes(ISO_8859_1))
      body(Path(f.toUri), Files.size(f))
    finally scholarchain.tests.support.StoreWorld.deleteTree(dir)

  private def read(file: Path, start: Long, length: Long): Vector[(Long, String)] =
    val rr = DblpRecordReader()
    rr.initialize(
      FileSplit(file, start, length, Array.empty[String]),
      TaskAttemptContextImpl(conf, TaskAttemptID())
    )
    try
      Iterator
        .continually(rr.nextKeyValue())
        .takeWhile(identity)
        .map(_ => (rr.getCurrentKey.get, rr.getCurrentValue.toString))
        .toVector
    finally rr.close()

  /** Records from the splits DblpInputFormat itself makes with the given maximum split size. */
  private def viaInputFormat(file: Path, maxSplit: Long): (Int, Vector[(Long, String)]) =
    val job = Job.getInstance(conf)
    FileInputFormat.setInputPaths(job, file)
    FileInputFormat.setMaxInputSplitSize(job, maxSplit)
    FileInputFormat.setMinInputSplitSize(job, 1L)
    val splits = DblpInputFormat().getSplits(job).asScala.toVector.map(_.asInstanceOf[FileSplit])
    (splits.size, splits.flatMap(s => read(file, s.getStart, s.getLength)))

  private def keyOf(value: String) = """key="([^"]*)"""".r.findFirstMatchIn(value).map(_.group(1))
  private def accepted(value: String) =
    parser
      .parse(value, ctx)
      .toOption
      .map(p => upickle.default.read[scholarchain.core.WorkRecord](p.work.json))

  private val wellFormed =
    """<?xml version="1.0" encoding="ISO-8859-1"?>
      |<!DOCTYPE dblp SYSTEM "dblp.dtd">
      |<dblp>
      |<article key="journals/x/A1"><author>Ada Lovelace</author><title>One.</title><year>2001</year></article>
      |<articles>not a record element</articles>
      |<inproceedings key="conf/x/B2"><author>Grace Hopper</author><booktitle>B</booktitle><title>Two &amp; more.</title><year>2002</year></inproceedings>
      |<article-x key="journals/x/NotOne"><title>no</title></article-x>
      |<article	key="journals/x/T3"><title>Tab before the attribute, Jos&eacute; café.</title><year>2003</year></article>
      |<www key="homepages/x/H"><title>Home</title></www>
      |<article
      |key="journals/x/N4"><title>Newline before the attribute.</title><year>2004</year></article>
      |</dblp>
      |""".stripMargin

  test(
    "splits made by DblpInputFormat at every size give every record once, intact, at its offset"
  ) {
    withFile(wellFormed) { (file, len) =>
      val whole = read(file, 0, len)
      assert(
        whole.flatMap(r => keyOf(r._2)) ==
          Vector("journals/x/A1", "conf/x/B2", "journals/x/T3", "homepages/x/H", "journals/x/N4")
      )
      // Keys are byte offsets of the record's '<' (ISO-8859-1: one byte per character).
      whole.foreach((k, v) => assert(wellFormed.substring(k.toInt).startsWith(v), k))
      assert(
        whole.forall((_, v) =>
          v.endsWith("</article>") || v.endsWith("</inproceedings>") || v.endsWith("</www>")
        )
      )
      for size <- Seq(1L, 2L, 7L, 31L, 64L, 100L, 333L, len) do
        val (n, recs) = viaInputFormat(file, size)
        assert(recs == whole, s"max split size $size ($n splits)")
      // Non-ASCII Latin-1 bytes survive the split reading as Unicode text.
      assert(whole.exists(_._2.contains("café")))
    }
  }

  test(
    "a truncated file never loses or repeats the complete records before the cut, at any split"
  ) {
    val cuts = Vector(
      wellFormed.indexOf("<www") + 3, // inside a start tag name
      wellFormed.indexOf("</www>") + 4, // inside an end tag
      wellFormed.indexOf("Newline") + 3, // inside the last record's text
      wellFormed.indexOf("</dblp>") // only the closing root tag missing
    )
    cuts.foreach { cut =>
      val xml = wellFormed.take(cut)
      withFile(xml) { (file, len) =>
        val whole = read(file, 0, len)
        val complete = whole.filter((_, v) =>
          v.endsWith("</article>") || v.endsWith("</inproceedings>") || v.endsWith("</www>")
        )
        val partial = whole.filterNot(complete.contains)
        // At most one partial record, and it is the last one, returned for a visible reject.
        assert(partial.size <= 1 && partial.forall(_ == whole.last), s"cut $cut: $partial")
        partial.foreach((_, v) =>
          assert(parser.parseXml(v).left.map(_.reason) == Left("malformed-record"), v)
        )
        for size <- Seq(1L, 5L, 64L) do
          assert(viaInputFormat(file, size)._2 == whole, s"cut $cut, split size $size")
      }
    }
    // Degenerate inputs: empty, root only, a split past the end, an empty split.
    withFile("")((file, len) => assert(read(file, 0, len).isEmpty))
    withFile("<dblp>\n</dblp>\n")((file, len) => assert(read(file, 0, len).isEmpty))
    withFile(wellFormed) { (file, len) =>
      assert(read(file, len, 100).isEmpty)
      assert(read(file, 10, 0).isEmpty)
    }
  }

  test("compressed input is one split and reads every record, whatever split it is handed") {
    withFile(wellFormed, ".xml.gz") { (file, len) =>
      val (n, recs) = viaInputFormat(file, 16L)
      assert(n == 1, s"$n splits of a gzip file")
      withFile(wellFormed)((plain, plen) => assert(recs == read(plain, 0, plen)))
      // A reader handed a partial split of a compressed file still reads the whole stream once.
      assert(read(file, 0, 10) == recs)
    }
  }

  test(
    "a record start tag inside an XML comment is not a record, and the records after it stay intact"
  ) {
    // PENDING D17 (owner hadoop-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // Found in work order 4 (D17). DblpInputFormat's contract: a value is a record element. Text
      // inside <!-- ... --> is not an element, so a commented-out record must not become a work, and
      // an unclosed record start inside a comment must not swallow the real records after it.
      val xml =
        """<?xml version="1.0" encoding="ISO-8859-1"?>
          |<dblp>
          |<!-- withdrawn: <article key="journals/x/Fake1"><author>Mallory</author><title>Fake.</title><year>2001</year></article> -->
          |<article key="journals/x/Real1"><author>Ada Lovelace</author><title>Real one.</title><year>2001</year></article>
          |<!-- <article key="journals/x/Open"> was never closed here -->
          |<inproceedings key="conf/x/Real2"><author>Grace Hopper</author><booktitle>B</booktitle><title>Real two.</title><year>2002</year></inproceedings>
          |<article key="journals/x/Real3"><author>Edsger Dijkstra</author><title>Real three.</title><year>2003</year></article>
          |</dblp>
          |""".stripMargin
      withFile(xml) { (file, len) =>
        val recs = read(file, 0, len)
        val keys = recs.flatMap(r => keyOf(r._2))
        val works = recs.flatMap(r => accepted(r._2)).map(_.workId)
        assert(
          keys == Vector("journals/x/Real1", "conf/x/Real2", "journals/x/Real3"),
          s"records read: $keys; works accepted: $works"
        )
        assert(works.size == 3 && !works.exists(_.contains("Fake1")), works)
      }
    }
  }
