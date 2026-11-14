package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.hadoop.{DblpEntities, DblpParser, RunContext}
import upickle.default.read

import java.io.File
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files

/** Phase 11 WO3, spec category "malformed and truncated DBLP XML", through the J01 record parser
  * (pure). The Hadoop record reader (splits, truncation, comments) is
  * DblpRecordReaderAdversarialSpec (work order 4). Every malformed record must become a typed
  * reject, never an exception, a silent accept, a file read, or a network call.
  */
class DblpXmlAdversarialSpec extends AnyFunSuite:
  private lazy val repo =
    Iterator
      .iterate(new File(sys.props("user.dir")).getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => new File(d, "datasets/samples/dblp").isDirectory)
      .get
  private lazy val entities = DblpEntities.parseDtd(
    new String(
      Files.readAllBytes(new File(repo, "datasets/samples/dblp/dblp-2023-06-28.dtd").toPath),
      ISO_8859_1
    )
  )
  private lazy val parser = DblpParser(entities)
  private val ctx = RunContext(
    "adv-j01",
    StableIds.runId("adv", "code", "config-v1", Seq("dblp=2026-10-03"), 1),
    Map(SourceType.DBLP -> "2026-10-03"),
    "2026-10-03",
    "graph-v1"
  )
  private def reason(xml: String): Either[String, WorkRecord] =
    scala.util
      .Try(parser.parse(xml, ctx))
      .fold(
        e => fail(s"the parser threw ${e.getClass.getSimpleName} instead of rejecting"),
        _.left.map(_.reason).map(p => read[WorkRecord](p.work.json))
      )

  test("truncated, mismatched, and unclosed records are malformed-record rejects") {
    Vector(
      """<article key="journals/x/T1"><title>Truncated""",
      """<article key="journals/x/T2"><title>A</title><author>B</author>""",
      """<article key="journals/x/T3"><title>A</author></article>""",
      """<article key="journals/x/T4"><title>A<title></article>""",
      """<article key="journals/x/T5" <title>A</title></article>""",
      """<article key=journals/x/T8><title>A</title></article>""",
      """<article key="journals/x/T9"><title>a &amp b</title></article>""",
      """<article key="journals/x/T10"><title>&#0;</title></article>""",
      """<article key="journals/x/T11"><title>&#xD800;</title></article>""",
      """<article key="journals/x/T12"><title>&#x110000;</title></article>""",
      "",
      "   ",
      "<!-- only a comment -->"
    ).foreach { xml =>
      reason(xml) match
        case Left(r) =>
          assert(Set("malformed-record", "unknown-entity", "missing-key").contains(r), s"$r: $xml")
        case Right(w) => fail(s"accepted ${w.workId}: $xml")
    }
  }

  test(
    "external entities and entity expansion bombs are refused; a missing external DTD changes nothing"
  ) {
    val secret = Files.createTempFile("adv-xxe-", ".txt")
    try
      Files.writeString(secret, "SYNTHETIC-SECRET-FILE-CONTENT")
      val uri = secret.toUri.toString
      val xxe =
        s"""<!DOCTYPE article [<!ENTITY xxe SYSTEM "$uri">]><article key="journals/x/X1"><title>&xxe;</title></article>"""
      assert(reason(xxe) == Left("unknown-entity"))
      val bomb =
        """<!DOCTYPE article [<!ENTITY a "lol"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;">]>""" +
          """<article key="journals/x/X2"><title>&c;</title></article>"""
      assert(reason(bomb) == Left("unknown-entity"))
      // A DOCTYPE that names an external DTD is ignored, not loaded: a missing file changes nothing.
      val missingDtd =
        """<!DOCTYPE article SYSTEM "file:///nonexistent/adv/dblp.dtd"><article key="journals/x/X3"><title>Fine</title></article>"""
      assert(reason(missingDtd).map(_.title) == Right(Some("Fine")))
      // A parameter entity referencing the secret file is never expanded (that nothing is even
      // fetched is shown by the fetch-counting test below).
      val pe =
        s"""<!DOCTYPE article [<!ENTITY % p SYSTEM "$uri"> %p;]><article key="journals/x/X4"><title>T</title></article>"""
      reason(pe) match
        case Right(w) => assert(!w.title.exists(_.contains("SYNTHETIC-SECRET")))
        case Left(_)  => ()
    finally Files.deleteIfExists(secret)
  }

  test("no external entity, parameter entity, or DTD reference is ever fetched (fetches counted)") {
    // Work order 4 (code review 1 section 5): a Left alone does not prove that nothing was read.
    // Every reference points at a loopback server (127.0.0.1, ephemeral port) that counts requests;
    // a parser that resolves any of them fetches it before it can fail.
    val hits = java.util.concurrent.atomic.AtomicInteger()
    val server = com.sun.net.httpserver.HttpServer.create(
      java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress, 0),
      0
    )
    server.createContext(
      "/",
      ex =>
        hits.incrementAndGet()
        val body = """<!ENTITY % q "<!ENTITY leak 'SYNTHETIC-SECRET'>"> %q;""".getBytes("UTF-8")
        ex.sendResponseHeaders(200, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close()
    )
    server.start()
    try
      val base = s"http://127.0.0.1:${server.getAddress.getPort}"
      // Control: the counter sees a real fetch.
      val in = java.net.URI(s"$base/control").toURL.openStream()
      try in.readAllBytes()
      finally in.close()
      assert(hits.get == 1, "the fetch counter does not work")
      hits.set(0)
      val docs = Vector(
        s"""<!DOCTYPE article [<!ENTITY xxe SYSTEM "$base/general">]><article key="journals/x/F1"><title>&xxe;</title></article>""",
        s"""<!DOCTYPE article [<!ENTITY % p SYSTEM "$base/parameter"> %p;]><article key="journals/x/F2"><title>T</title></article>""",
        s"""<!DOCTYPE article [<!ENTITY % p SYSTEM "$base/nested"> %p; %q;]><article key="journals/x/F3"><title>&leak;</title></article>""",
        s"""<!DOCTYPE article SYSTEM "$base/external.dtd"><article key="journals/x/F4"><title>Fine</title></article>""",
        s"""<!DOCTYPE article PUBLIC "-//X//Y//EN" "$base/public.dtd"><article key="journals/x/F5"><title>Fine</title></article>""",
        s"""<?xml-stylesheet href="$base/style.xsl"?><article key="journals/x/F6"><title>Fine</title></article>""",
        s"""<article key="journals/x/F7" xmlns:xi="http://www.w3.org/2001/XInclude"><title><xi:include href="$base/xinclude"/></title></article>"""
      )
      docs.foreach { xml =>
        val r = reason(xml)
        assert(hits.get == 0, s"fetched while parsing: $xml")
        r.foreach(w => assert(!w.title.exists(_.contains("SYNTHETIC-SECRET")), xml))
      }
      // The documents that need no external text still parse to their own title.
      assert(reason(docs(3)).map(_.title) == Right(Some("Fine")))
      assert(reason(docs(4)).map(_.title) == Right(Some("Fine")))
    finally server.stop(0)
  }

  test("missing or blank keys, unsupported types, and malformed years are typed rejects") {
    assert(reason("""<article><title>A</title></article>""") == Left("missing-key"))
    assert(reason("""<article key="   "><title>A</title></article>""") == Left("missing-key"))
    assert(
      reason("""<www key="homepages/x/1"><title>A</title></www>""") == Left(
        "unsupported-record-type"
      )
    )
    assert(
      reason("""<proceedings key="conf/x/2020"><title>A</title></proceedings>""") == Left(
        "unsupported-record-type"
      )
    )
    Vector("2O20", "20 20", "MMXX", "2020.0", "").foreach { y =>
      val r = reason(s"""<article key="journals/x/Y"><title>A</title><year>$y</year></article>""")
      assert(
        r.left.toOption.contains("schema-validation") || (y.isEmpty && r.isRight),
        s"year '$y': $r"
      )
    }
  }

  test(
    "an author with no name (empty, blank, or markup only) is a schema-validation reject (JOBS.md)"
  ) {
    // PENDING D6 (owner hadoop-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // JOBS.md, Reject conditions: "schema-validation ... an author without a name".
      Vector(
        """<article key="journals/x/N1"><author></author><title>A</title></article>""",
        """<article key="journals/x/N2"><author>   </author><title>A</title></article>""",
        """<article key="journals/x/N3"><author><i></i></author><title>A</title></article>""",
        """<article key="journals/x/N4"><author>Ada Lovelace</author><author/><title>A</title></article>"""
      ).foreach { xml =>
        assert(reason(xml) == Left("schema-validation"), s"accepted a nameless author: $xml")
      }
    }
  }

  test("record keys that are not DBLP keys never become malformed work IDs") {
    Vector(
      "journals/x/has space",
      "../../etc/passwd",
      "journals/x/\u0000",
      "journals/x/" + "a" * 5000
    ).foreach { k =>
      val esc = k.replace("\u0000", "&#0;")
      reason(s"""<article key="$esc"><title>A</title></article>""") match
        case Right(w) =>
          assert(
            IdFormat.isWorkId(w.workId) && Validation.validate(w).isRight,
            s"$k -> ${w.workId}"
          )
        case Left(_) => ()
    }
  }

  test(
    "very large records parse: 5,000 authors, a 1 MB title, and repeated names keep every position"
  ) {
    val authors =
      (1 to 5000).map(i => s"<author>Wei Wang ${"%04d".format(i % 3 + 1)}</author>").mkString
    val title = "T" * 1000000
    val p = parser.parse(
      s"""<article key="journals/x/Big"><title>$title</title>$authors<year>2020</year></article>""",
      ctx
    )
    val parsed = p.fold(r => fail(s"rejected: $r"), identity)
    val w = read[WorkRecord](parsed.work.json)
    assert(w.authors.size == 5000 && w.authors.map(_.position) == (1 to 5000).toVector)
    assert(w.authors.map(_.mentionId).distinct.size == 5000)
    assert(parsed.repeatedAuthorNames == 4997)
    assert(w.title.exists(_.length == 1000000))
  }

  test("CDATA, comments, and processing instructions inside a record keep only the text") {
    val r = reason(
      """<article key="journals/x/C1"><title><![CDATA[A <b>bold</b> & title]]></title><!-- c --><?pi x?>""" +
        """<author>Ada <!-- inner -->Lovelace</author></article>"""
    )
    val w = r.fold(e => fail(e), identity)
    assert(w.title.contains("A <b>bold</b> & title"))
    assert(w.authors.map(_.rawName) == Vector("Ada Lovelace"))
  }
