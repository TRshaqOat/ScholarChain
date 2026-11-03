package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import upickle.default.{ReadWriter, read}

class SourceNormalizersSpec extends AnyFunSuite:
  import TestSupport.*

  private def ok(r: Either[Reject, Vector[Keyed]]): Vector[Keyed] =
    r.fold(x => fail(s"rejected: $x"), identity)
  private def of[A: ReadWriter](ks: Vector[Keyed], kind: String): Vector[A] =
    ks.filter(_.kind == kind).map(k => read[A](k.json))

  test("OpenAlex: work, authors, ORCID, author IDs, institutions, venue, and topic are kept") {
    val ks = ok(SourceNormalizers.openAlex(lines(sample("openalex/works-sample.jsonl")).head, ctx))
    val w = of[WorkRecord](ks, "works").head
    assert(
      w.workId == "openalex:W2071130955" && w.sourceRecordId == "W2071130955" && w.source == SourceType.OPENALEX
    )
    assert(
      w.doi.contains("10.1145/1088149.1088173") && w.year.contains(2005) && w.workType.contains(
        "conference-paper"
      )
    )
    assert(w.venueId.contains("openalex:S4306419857") && w.externalIds("mag") == "2071130955")
    val first = w.authors.head
    assert(first.rawName == "Jose Renau" && first.orcid.contains("0000-0001-5128-0506"))
    assert(
      first.sourceAuthorId.contains("openalex:A5065936105") && first.institutionIds == Vector(
        "ror:03s65by71"
      )
    )
    assert(
      w.authors(2).sourceAuthorId.isEmpty && w.authors(2).orcid.isEmpty
    ) // "Wei Liu" has neither
    assert(
      of[Institution](ks, "institutions").exists(i =>
        i.institutionId == "ror:03s65by71" && i.externalIds("openalex") == "openalex:I185103710"
      )
    )
    assert(
      of[Venue](ks, "venues").map(_.name) == Vector("International Conference on Supercomputing")
    )
    assert(
      of[Topic](ks, "topics").map(t => (t.topicId, t.subfield)) == Vector(
        ("openalex:T10054", Some("Hardware and Architecture"))
      )
    )
    val e = of[GraphEdge](ks, "edges").head
    assert(e.edgeType == EdgeType.HAS_TOPIC && e.fromId == w.workId && e.toId == "openalex:T10054")
    assert(e.provenance.policyVersion.contains("graph-v1"))
  }

  test("every committed OpenAlex and Crossref sample line normalizes and validates") {
    assert(
      lines(sample("openalex/works-sample.jsonl")).forall(l =>
        SourceNormalizers.openAlex(l, ctx).isRight
      )
    )
    assert(
      lines(sample("crossref/works-sample.jsonl")).forall(l =>
        SourceNormalizers.crossref(l, ctx).isRight
      )
    )
  }

  test(
    "Crossref: DOI work ID, year from issued, ORCID, ISSN venue, and named affiliations are kept"
  ) {
    val ks = ok(SourceNormalizers.crossref(lines(sample("crossref/works-sample.jsonl")).head, ctx))
    val w = of[WorkRecord](ks, "works").head
    assert(
      w.workId == "doi:10.1145/1088149.1088173" && w.sourceRecordId == "10.1145/1088149.1088173"
    )
    assert(
      w.year.contains(2005) && w.workType.contains("proceedings-article") && w.externalIds(
        "publisher"
      ) == "ACM"
    )
    assert(w.authors.map(_.rawName).take(2) == Vector("Jose Renau", "James Tuck"))
    val aff = StableIds.affiliationNameId("University of California, Santa Cruz").toOption.get
    assert(aff.startsWith("sci:affn-v1:") && w.authors.head.institutionIds == Vector(aff))
    assert(
      of[Institution](ks, "institutions").exists(i =>
        i.institutionId == aff && i.name == "University of California, Santa Cruz"
      )
    )
    val withIssn = ok(
      SourceNormalizers.crossref(
        """{"DOI":"10.1234/X","type":"journal-article","ISSN":["1234-5678"],"container-title":["J"],"issued":{"date-parts":[[null]]},
        |"author":[{"given":"A","family":"B","ORCID":"https://orcid.org/0000-0002-1825-0097"}]}""".stripMargin
          .replace("\n", ""),
        ctx
      )
    )
    val w2 = of[WorkRecord](withIssn, "works").head
    assert(w2.workId == "doi:10.1234/x" && w2.venueId.contains("issn:1234-5678") && w2.year.isEmpty)
    assert(w2.authors.head.orcid.contains("0000-0002-1825-0097"))
    assert(of[Venue](withIssn, "venues").map(_.name) == Vector("J"))
  }

  test(
    "ORCID: the fictional test record becomes a profile node, institutions, and affiliation edges"
  ) {
    val ks = ok(
      SourceNormalizers.orcid(lines(sample("orcid/orcid-fictional-test-record.jsonl")).head, ctx)
    )
    val n = of[GraphNode](ks, "researcher-profiles").head
    assert(n.nodeId == "orcid:0000-0002-1825-0097" && n.label == NodeLabel.Researcher)
    assert(
      n.properties("familyName") == "Carberry" && n.properties(
        "otherName.1"
      ) == "Josiah Stinkney Carberry"
    )
    assert(
      n.properties("externalId.1.type") == "Scopus Author ID" && n.properties(
        "workDoi.1"
      ) == "10.1109/tps.1987.4316723"
    )
    assert(
      of[Institution](ks, "institutions").map(_.institutionId).toSet == Set(
        "ringgold:5468",
        "ringgold:6752"
      )
    )
    val edges = of[GraphEdge](ks, "edges")
    assert(
      edges.map(_.edgeType).toSet == Set(EdgeType.AFFILIATED_WITH) && edges
        .map(_.properties("role"))
        .toSet == Set("Professor")
    )
  }

  test(
    "ORCID: synthetic fixture IDs are rejected as unsupported identifiers, never turned into iDs"
  ) {
    val rs =
      lines(sample("orcid/synthetic-orcid-records.jsonl")).map(SourceNormalizers.orcid(_, ctx))
    assert(rs.forall(_.left.map(_.reason) == Left("unsupported-identifier")))
  }

  test("name-only affiliations get core affiliationNameId; a name without letters is a reject") {
    val cr = ok(
      SourceNormalizers.crossref(
        """{"DOI":"10.1234/aff","title":["<i>Deep</i> Learning."],"author":[{"given":"José","family":"Núñez",
          |"affiliation":[{"name":"Universitat Politècnica de Catalunya, Dept. EE"},{"name":"universitat politecnica de catalunya dept ee"}]}]}""".stripMargin
          .replace("\n", ""),
        ctx
      )
    )
    val w = of[WorkRecord](cr, "works").head
    // Frozen value from the domain-engineer's report; two spellings with one canonical form share it.
    assert(w.authors.head.institutionIds == Vector("sci:affn-v1:981e0a59669da94037cd74352e4b6112"))
    assert(
      w.authors.head.normalizedName == "jose nunez" && w.normalizedTitle.contains("deep learning")
    )
    val orcidNoOrgId =
      """{"orcid":"0000-0002-1825-0097","employments":[{"org":"Universitat Politècnica de Catalunya, Dept. EE"}]}"""
    assert(
      of[Institution](ok(SourceNormalizers.orcid(orcidNoOrgId, ctx)), "institutions")
        .map(_.institutionId) == Vector("sci:affn-v1:981e0a59669da94037cd74352e4b6112")
    )
    val bad = """{"DOI":"10.1234/bad","author":[{"name":"A B","affiliation":[{"name":"--"}]}]}"""
    assert(
      SourceNormalizers.crossref(bad, ctx).left.map(r => (r.reason, r.counter)) ==
        Left(("invalid-affiliation", Some(Counters.SchemaFailure)))
    )
    val badOrcid = """{"orcid":"0000-0002-1825-0097","employments":[{"org":"&"}]}"""
    assert(SourceNormalizers.orcid(badOrcid, ctx).left.map(_.reason) == Left("invalid-affiliation"))
  }

  test("malformed JSON, missing IDs, and nameless authors are rejected with counters") {
    assert(
      SourceNormalizers.openAlex("{not json", ctx).left.map(_.counter) == Left(
        Some(Counters.SchemaFailure)
      )
    )
    assert(SourceNormalizers.openAlex("[1,2]", ctx).left.map(_.reason) == Left("malformed-record"))
    assert(
      SourceNormalizers.openAlex("""{"title":"x"}""", ctx).left.map(_.counter) == Left(
        Some(Counters.MissingKey)
      )
    )
    assert(
      SourceNormalizers.crossref("""{"title":["x"]}""", ctx).left.map(_.counter) == Left(
        Some(Counters.MissingKey)
      )
    )
    assert(
      SourceNormalizers
        .crossref("""{"DOI":"10.1/x","author":[{"given":null}]}""", ctx)
        .left
        .map(_.reason) == Left("schema-validation")
    )
    assert(
      SourceNormalizers
        .openAlex("""{"id":"https://openalex.org/W1","publication_year":"soon"}""", ctx)
        .left
        .map(_.reason) == Left("malformed-record")
    )
    assert(
      SourceNormalizers.crossref("""{"DOI":"10.1/has space"}""", ctx).left.map(_.reason) == Left(
        "schema-validation"
      )
    )
  }
