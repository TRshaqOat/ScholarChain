package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*

class FeaturesSpec extends AnyFunSuite:
  import Features.*

  private def close(a: Option[Double], b: Double) = a.exists(v => math.abs(v - b) < 1e-4)
  private val orcidA = "0000-0002-1825-0097"
  private val orcidB = "0000-0001-5109-3700"

  test("Jaro-Winkler textbook values") {
    assert(close(Some(jaroWinkler("martha", "marhta")), 0.9611))
    assert(close(Some(jaroWinkler("dwayne", "duane")), 0.84))
    assert(close(Some(jaroWinkler("dixon", "dicksonx")), 0.8133))
    assert(jaroWinkler("abc", "abc") == 1.0)
    assert(jaroWinkler("abc", "xyz") == 0.0)
    assert(jaroWinkler("", "abc") == 0.0)
  }

  test("name similarity compares comparable names, without the homonym suffix") {
    val n = Normalize.name
    assert(nameSimilarity(n("Wang, Wei"), n("Wei Wang 0002")).contains(1.0))
    assert(close(nameSimilarity(n("Martha"), n("Marhta")), 0.9611))
    assert(nameSimilarity(n(""), n("Wei Wang")).isEmpty)
  }

  test("initials compatibility") {
    val n = Normalize.name
    assert(initialsCompatibility(n("J. R. Smith"), n("John Smith")).contains(1.0))
    assert(initialsCompatibility(n("John Smith"), n("James Smith")).contains(0.0))
    assert(initialsCompatibility(n("J. R. Smith"), n("J. K. Smith")).contains(0.5))
    assert(initialsCompatibility(n("Jean-Pierre Roux"), n("J.-P. Roux")).contains(1.0))
    assert(initialsCompatibility(n("Madonna"), n("M. Madonna")).isEmpty)
  }

  test("author-order agreement") {
    assert(authorOrderAgreement(1, 4, 1, 4).contains(1.0))
    assert(authorOrderAgreement(1, 4, 3, 4).contains(0.5))
    assert(authorOrderAgreement(2, 3, 2, 5).contains(1.0))
    assert(authorOrderAgreement(1, 2, 2, 2).contains(0.5))
    assert(authorOrderAgreement(5, 4, 1, 4).isEmpty)
    assert(authorOrderAgreement(0, 4, 1, 4).isEmpty)
  }

  test("coauthor overlap") {
    assert(
      close(coauthorOverlap(Seq("Li Zhang", "Ming Liu"), Seq("L. Zhang", "Hui Chen")), 1.0 / 3)
    )
    assert(coauthorOverlap(Seq("Zhang, Li"), Seq("Li Zhang 0003")).contains(1.0))
    assert(coauthorOverlap(Seq("Li Zhang"), Seq("Hui Chen")).contains(0.0))
    assert(coauthorOverlap(Nil, Seq("Li Zhang")).isEmpty)
  }

  test("affiliation compatibility compares shared namespaces only") {
    assert(affiliationCompatibility(Set("ror:a", "openalex:I1"), Set("openalex:I1")).contains(1.0))
    assert(affiliationCompatibility(Set("ror:a", "ror:b"), Set("ror:b", "ror:c")).contains(0.5))
    assert(affiliationCompatibility(Set("ror:a"), Set("ror:c")).contains(0.0))
    assert(affiliationCompatibility(Set("ror:a"), Set("openalex:I1")).isEmpty)
    assert(affiliationCompatibility(Set.empty, Set("ror:a")).isEmpty)
  }

  test("ORCID agreement: equal, conflicting, invalid, absent") {
    assert(orcidAgreement(Some(orcidA), Some(s"https://orcid.org/$orcidA")).contains(1.0))
    assert(orcidAgreement(Some(orcidA), Some(orcidB)).contains(0.0))
    assert(orcidAgreement(Some(orcidA), Some("0000-0002-1825-0098")).isEmpty)
    assert(orcidAgreement(Some(orcidA), None).isEmpty)
  }

  test("DBLP homonym-suffix agreement") {
    val n = Normalize.name
    assert(homonymSuffixAgreement(n("Wei Wang 0001"), n("Wei Wang 0001")).contains(1.0))
    assert(homonymSuffixAgreement(n("Wei Wang 0001"), n("Wei Wang 0002")).contains(0.0))
    assert(homonymSuffixAgreement(n("Wei Wang 0001"), n("Wei Wang")).isEmpty)
  }

  test("title similarity") {
    assert(titleSimilarity(Some("<i>Deep</i> Learning."), Some("deep learning")).contains(1.0))
    assert(
      close(titleSimilarity(Some("Deep Learning"), Some("Deep Reinforcement Learning")), 2.0 / 3)
    )
    assert(titleSimilarity(Some("Graphs"), Some("Trees")).contains(0.0))
    assert(titleSimilarity(None, Some("x")).isEmpty)
  }

  test("year distance") {
    assert(yearDistance(Some(2020), Some(2020)).contains(1.0))
    assert(yearDistance(Some(2020), Some(2021)).contains(0.5))
    assert(yearDistance(Some(2023), Some(2020)).contains(0.25))
    assert(yearDistance(None, Some(2020)).isEmpty)
  }

  test("venue compatibility: same-namespace IDs, acronyms, name tokens") {
    val ics = VenueRef(Some("dblp-venue:conf/ics"), Some("ICS"))
    assert(venueCompatibility(ics, VenueRef(Some("dblp-venue:conf/ics"), None)).contains(1.0))
    assert(venueCompatibility(ics, VenueRef(Some("dblp-venue:conf/sc"), Some("ICS"))).contains(0.0))
    val full = VenueRef(Some("openalex:S1"), Some("International Conference on Supercomputing"))
    assert(venueCompatibility(ics, full).contains(1.0))
    assert(venueCompatibility(full, ics).contains(1.0))
    assert(
      venueCompatibility(
        VenueRef(None, Some("Journal of Parallel Computing")),
        VenueRef(None, Some("Parallel Computing Letters"))
      ).contains(0.5)
    )
    assert(venueCompatibility(ics, VenueRef(Some("openalex:S1"), None)).isEmpty)
  }

  private def prov(source: SourceType, id: String) =
    Provenance(SchemaVersions.Provenance, source, id, "2026-10-03", "run-" + "0" * 32, None, None)
  private def mkWork(id: String, source: SourceType, title: String, year: Int, venue: String)(
      authors: (String, Option[String], Vector[String])*
  ): WorkRecord =
    val mentions = authors.zipWithIndex.map { case ((name, orcid, insts), i) =>
      AuthorMention(
        SchemaVersions.AuthorMention,
        s"$id#${i + 1}",
        id,
        i + 1,
        name,
        name,
        orcid,
        None,
        insts,
        prov(source, id)
      )
    }.toVector
    WorkRecord(
      SchemaVersions.WorkRecord,
      id,
      source,
      id,
      Some(title),
      None,
      Some(year),
      Some(venue),
      None,
      None,
      None,
      Map.empty,
      mentions,
      None,
      prov(source, id)
    )

  private val dblp = mkWork(
    "dblp:conf/ics/WangZ25",
    SourceType.DBLP,
    "Fast <i>Graph</i> Joins.",
    2025,
    "dblp-venue:conf/ics"
  )(("Wei Wang 0001", None, Vector.empty), ("Li Zhang", None, Vector.empty))
  private val openAlex =
    mkWork("openalex:W1", SourceType.OPENALEX, "Fast graph joins", 2024, "openalex:S1")(
      ("Wang, Wei", Some(orcidA), Vector("ror:x")),
      ("L. Zhang", None, Vector.empty)
    )

  test("researcher feature map holds only features with evidence on both sides") {
    val f = researcher(dblp.authors(0), dblp, openAlex.authors(0), openAlex)
    assert(
      f == Map(
        NameSimilarity -> 1.0,
        InitialsCompatibility -> 1.0,
        AuthorOrderAgreement -> 1.0,
        CoauthorOverlap -> 1.0
      )
    )
    val cross = researcher(dblp.authors(0), dblp, openAlex.authors(1), openAlex)
    assert(cross(AuthorOrderAgreement) == 0.5)
    assert(cross(InitialsCompatibility) == 0.0)
  }

  test("work feature map") {
    assert(
      work(dblp, Some("ICS"), openAlex, Some("International Conference on Supercomputing")) ==
        Map(TitleSimilarity -> 1.0, YearDistance -> 0.5, VenueCompatibility -> 1.0)
    )
    assert(work(dblp, None, openAlex, None) == Map(TitleSimilarity -> 1.0, YearDistance -> 0.5))
  }

  test("every feature stays in [0, 1] and is deterministic across a grid of inputs") {
    val names =
      Seq("Wei Wang 0001", "Wang, W.", "W. Wang", "Ana Silva", "Madonna", "", "J.-P. Müller")
    val titles = Seq(Some("Deep Learning"), Some("On the"), Some("<i>x</i>"), None)
    def values =
      for a <- names; b <- names; t <- titles; u <- titles
      yield
        val (na, nb) = (Normalize.name(a), Normalize.name(b))
        Seq(
          nameSimilarity(na, nb),
          initialsCompatibility(na, nb),
          homonymSuffixAgreement(na, nb),
          coauthorOverlap(Seq(a), Seq(b)),
          titleSimilarity(t, u),
          venueCompatibility(VenueRef(None, t), VenueRef(None, u))
        ).flatten
    assert(values.flatten.forall(v => v >= 0.0 && v <= 1.0))
    assert(values.flatten.nonEmpty)
    assert(values == values)
  }
