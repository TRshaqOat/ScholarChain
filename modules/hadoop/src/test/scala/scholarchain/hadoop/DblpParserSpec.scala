package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import upickle.default.read

class DblpParserSpec extends AnyFunSuite:
  import TestSupport.*

  private def work(xml: String): WorkRecord =
    parser.parse(xml, ctx).fold(r => fail(s"rejected: $r"), p => read[WorkRecord](p.work.json))

  test("the DTD defines the character entities, including those used by the sample") {
    assert(dtdEntities("uuml") == "&#252;")
    assert(dtdEntities("eacute") == "&#233;")
    assert(dtdEntities("Ouml") == "&#214;")
    assert(!dtdEntities.contains("field")) // parameter entities are not character entities
    assert(dtdEntities.size > 50)
  }

  test("entities resolve to Unicode; numeric references and builtins pass through the XML parser") {
    val w = work(
      """<inproceedings key="conf/ics/E20"><author>Ren&eacute;e &Ouml;zt&uuml;rk</author>
        |<title>Cost &#8364; &amp; &lt;b&gt; &times;</title><year>2020</year></inproceedings>""".stripMargin
    )
    assert(w.authors.head.rawName == "Renée Öztürk")
    assert(w.title.contains("Cost € & <b> ×"))
  }

  test("an undeclared entity rejects the record as a schema failure") {
    val r = parser.parse("""<article key="journals/x/U"><title>&bogus;</title></article>""", ctx)
    assert(
      r.left.map(x => (x.reason, x.counter)) == Left(
        ("unknown-entity", Some(Counters.SchemaFailure))
      )
    )
  }

  test("multiline titles keep their full text and inline markup contributes its text") {
    val w = work(
      "<article key=\"journals/x/M\"><title>Line one\nline <i>two</i> H<sub>2</sub>O.</title></article>"
    )
    assert(w.title.contains("Line one\nline two H2O."))
  }

  test("Unicode text decoded from ISO-8859-1 bytes is kept") {
    assert(
      work(
        """<article key="journals/x/L"><author>José Müller</author></article>"""
      ).authors.head.rawName == "José Müller"
    )
  }

  test("missing optional fields give None and empty collections, not rejects") {
    val w = work("""<article key="journals/tpds/Empty21"></article>""")
    assert(w.title.isEmpty && w.year.isEmpty && w.doi.isEmpty && w.authors.isEmpty)
    assert(w.venueId.contains("dblp-venue:journals/tpds"))
    assert(Validation.validate(w).isRight)
  }

  test("normalizedName and normalizedTitle come from entity-resolution Normalize (norm-v1)") {
    val w = work(
      """<inproceedings key="conf/ics/N26"><author>Shao-Peng Yang 0001</author><author>M&uuml;ller, J&ouml;rg</author>
        |<title>COMETS: <i>Cost</i>-effective
        |Training.</title></inproceedings>""".stripMargin
    )
    // The homonym suffix stays in rawName only; "Family, Given" is reordered; diacritics fold.
    assert(w.authors.map(_.rawName) == Vector("Shao-Peng Yang 0001", "Müller, Jörg"))
    assert(w.authors.map(_.normalizedName) == Vector("shao peng yang", "jorg muller"))
    assert(w.normalizedTitle.contains("comets cost effective training"))
    assert(
      work("""<article key="journals/x/T"><title>...</title></article>""").normalizedTitle.isEmpty
    )
  }

  test("repeated authors keep separate positions and mention IDs and are counted") {
    val xml =
      """<article key="journals/tpds/Rep21"><author>Wei Wang 0001</author><author>Wei Wang 0001</author><author>Ann Lee</author></article>"""
    val p = parser.parse(xml, ctx).toOption.get
    val w = read[WorkRecord](p.work.json)
    assert(w.authors.map(_.mentionId) == Vector(1, 2, 3).map(i => s"dblp:journals/tpds/Rep21#$i"))
    assert(w.authors.map(_.rawName).take(2) == Vector("Wei Wang 0001", "Wei Wang 0001"))
    assert(p.repeatedAuthorNames == 1)
  }

  test("fields map to the work record with provenance; DOI and arXiv come from ee links") {
    val xml =
      """<inproceedings mdate="2018-11-06" key="conf/ics/LiaoHNZCT09">
        |<author orcid="0000-0002-1825-0097">A B</author><author orcid="0000-0002-1825-0098">C D</author>
        |<title>T.</title><pages>519-520</pages><year>2009</year><booktitle>ICS</booktitle>
        |<ee>https://doi.org/10.1145/1542275.1542359</ee><ee type="oa">https://arxiv.org/abs/2101.00001</ee>
        |<crossref>conf/ics/2009</crossref><url>db/conf/ics/ics2009.html#LiaoHNZCT09</url></inproceedings>""".stripMargin
    val p = parser.parse(xml, ctx).toOption.get
    val w = read[WorkRecord](p.work.json)
    assert(w.workId == "dblp:conf/ics/LiaoHNZCT09" && w.sourceRecordId == "conf/ics/LiaoHNZCT09")
    assert(w.workType.contains("inproceedings") && w.year.contains(2009))
    assert(w.doi.contains("10.1145/1542275.1542359") && w.arxivId.contains("2101.00001"))
    assert(
      w.externalIds("venue") == "ICS" && w.externalIds("ee.2.type") == "oa" && w.externalIds(
        "crossref"
      ) == "conf/ics/2009"
    )
    assert(
      w.externalIds("url") == "db/conf/ics/ics2009.html#LiaoHNZCT09" && w.externalIds(
        "pages"
      ) == "519-520"
    )
    assert(
      w.authors.map(_.orcid) == Vector(Some("0000-0002-1825-0097"), None)
    ) // second fails the checksum
    assert(p.invalidOrcids == 1)
    assert(w.provenance == ctx.provenance(SourceType.DBLP, "conf/ics/LiaoHNZCT09"))
    assert(w.authors.forall(_.provenance.snapshot == "2026-10-03"))
    assert(p.venue.map(v => read[Venue](v.json).venueType) == Some(Some("conference")))
  }

  test("a record without a key is rejected as missing key") {
    assert(
      parser.parse("<article><title>x</title></article>", ctx).left.map(_.counter) == Left(
        Some(Counters.MissingKey)
      )
    )
  }

  test("unsupported record types and malformed years are rejected with reasons") {
    assert(
      parser
        .parse("""<www key="homepages/x/y"><author>X</author></www>""", ctx)
        .left
        .map(_.reason) == Left("unsupported-record-type")
    )
    assert(
      parser
        .parse("""<article key="journals/x/Y"><year>20x1</year></article>""", ctx)
        .left
        .map(_.reason) == Left("schema-validation")
    )
    assert(
      parser
        .parse("""<article key="journals/x/Y"><title>unclosed</article>""", ctx)
        .left
        .map(_.reason) == Left("malformed-record")
    )
  }

  test("every record of the committed sample parses and validates") {
    val xml = readFile(sample("dblp/dblp-sample.xml"), java.nio.charset.StandardCharsets.ISO_8859_1)
    val recs = """(?s)<(article|inproceedings)[ >].*?</\1>""".r.findAllIn(xml).toVector
    val works = recs.map(work)
    assert(works.size == 40 && works.forall(w => Validation.validate(w).isRight))
    assert(works.exists(_.authors.exists(_.rawName == "Ecem Ilgün")))
    assert(works.exists(_.title.exists(_.contains("Topologies & Attributes"))))
  }
