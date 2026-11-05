package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.Features as F
import scholarchain.entityresolution.MatchPolicy.{Rule, V1, V2}
import scholarchain.entityresolution.tools.Calibrate

/** Policy er-v2 against er-v1 on synthetic (fictional) work records. Each expected value is
  * hand-checkable from the feature definitions and the weights in MatchPolicy.
  */
class MatchPolicyV2Spec extends AnyFunSuite:
  private val t = Thresholds(accept = 0.93, reject = 0.35, llmBandMin = 0.65, llmBandMax = 0.93)
  private val run = "run-" + "c" * 32

  private def prov(s: SourceType, id: String) =
    Provenance(SchemaVersions.Provenance, s, id, "2026-10-03", run, None, None)

  private def work(
      id: String,
      source: SourceType,
      title: String,
      year: Int,
      doi: Option[String],
      authors: Seq[String],
      venueId: String
  ) =
    WorkRecord(
      SchemaVersions.WorkRecord,
      id,
      source,
      id.dropWhile(_ != ':').drop(1),
      Some(title),
      None,
      Some(year),
      Some(venueId),
      None,
      doi,
      None,
      Map.empty,
      authors.zipWithIndex.toVector.map { (n, i) =>
        AuthorMention(
          SchemaVersions.AuthorMention,
          s"$id#${i + 1}",
          id,
          i + 1,
          n,
          n,
          None,
          None,
          Vector.empty,
          prov(source, s"$id#${i + 1}")
        )
      },
      None,
      prov(source, id)
    )

  /** The features J10 gives the policy: feat-v1 work features plus the version's work-pair ones.
    * Venue IDs of different namespaces with unrelated names make venue_compatibility 0.
    */
  private def features(a: WorkRecord, b: WorkRecord, version: String) =
    F.work(a, Some("Venue Alpha"), b, Some("Omega Letters")) ++
      WorkPairFeatures.work(a, b, version)

  private def decide(a: WorkRecord, b: WorkRecord, version: String) =
    MatchPolicy.decideBlocked(MatchEntityType.WORK, features(a, b, version), t, version)

  private val authors = Seq("Wei Wang 0001", "Li Zhang")
  private val dblp =
    work(
      "dblp:conf/x/WangZ20",
      SourceType.DBLP,
      "Deep <i>Learning</i>: A Survey.",
      2020,
      None,
      authors,
      "dblp:conf/x"
    )
  private val openAlex =
    work(
      "openalex:W100",
      SourceType.OPENALEX,
      "deep learning - a survey",
      2020,
      None,
      Seq("Wei Wang", "L. Zhang"),
      "openalex:S1"
    )

  test(
    "two OpenAlex works sharing a DOI: er-v2 CONFLICT (SAME_SOURCE_SHARED_DOI), er-v1 ACCEPTED"
  ) {
    val a = openAlex.copy(doi = Some("10.9999/fict.1"))
    val b = openAlex.copy(
      workId = "openalex:W200",
      title = Some("Erratum"),
      doi = Some(
        "https://doi.org/10.9999/FICT.1"
      )
    )
    val f2 = features(a, b, V2)
    assert(f2(MatchPolicy.DoiAgreement) == 1.0 && f2(MatchPolicy.SourceAgreement) == 1.0)
    val v2 = decide(a, b, V2)
    assert(
      v2.outcome == MatchOutcome.CONFLICT && v2.confidence == 0.5 && v2.path == MatchPath.EXACT_ID
    )
    assert(v2.rulesFired == Vector(Rule.IdentifierAgreement, Rule.SameSourceSharedDoi))
    assert(v2.reasonCodes == Vector("EXACT_DOI_MATCH", "SAME_SOURCE_SHARED_DOI"))
    // er-v1 output exactly as on disk from the work order 6 run: accepted on the shared DOI.
    val v1 = decide(a, b, V1)
    assert(
      v1 == Verdict(
        MatchOutcome.ACCEPTED,
        1.0,
        Vector(Rule.IdentifierAgreement),
        Vector("EXACT_DOI_MATCH"),
        V1
      )
    )
    // Across sources a shared DOI is still identity in er-v2.
    val cross = decide(dblp.copy(doi = Some("10.9999/fict.1")), a, V2)
    assert(
      cross.outcome == MatchOutcome.ACCEPTED && cross.rulesFired == Vector(Rule.IdentifierAgreement)
    )
  }

  test("ACM dual-DOI pair, same title, authors, and year, different DOIs: never ACCEPTED") {
    val a = work(
      "doi:10.9999/sigx.1",
      SourceType.CROSSREF,
      "A Fictional Cache Study",
      2017,
      Some("10.9999/sigx.1"),
      authors,
      "issn:0000-0001"
    )
    val b = work(
      "openalex:W300",
      SourceType.OPENALEX,
      "A fictional cache study.",
      2017,
      Some("10.9999/procx.1"),
      Seq("W. Wang", "Li Zhang"),
      "openalex:S2"
    )
    for version <- Seq(V1, V2) do assert(decide(a, b, version).outcome != MatchOutcome.ACCEPTED)
    // er-v2: the new deterministic rule would accept, the DOIs disagree, so the pair is CONFLICT.
    val v2 = decide(a, b, V2)
    assert(v2.outcome == MatchOutcome.CONFLICT && v2.path == MatchPath.EXACT_ID)
    assert(v2.rulesFired.take(2) == Vector(Rule.IdentifierDisagreement, Rule.DeterministicWork))
    assert(
      v2.reasonCodes == Vector(
        "DOI_MISMATCH",
        "EXACT_TITLE_AUTHORS_YEAR_WITHIN_1",
        "BIBLIOGRAPHIC_EVIDENCE_CONTRADICTS_ID"
      )
    )
  }

  test("DOI-less pair: identical normalized title and authors, same year, venue 0: er-v2 rule") {
    val f = features(dblp, openAlex, V2)
    assert(f(MatchPolicy.TitleExactAgreement) == 1.0 && f(F.VenueCompatibility) == 0.0)
    assert(f(MatchPolicy.AuthorSequenceAgreement) == 1.0 && f(MatchPolicy.SourceAgreement) == 0.0)
    val v2 = decide(dblp, openAlex, V2)
    assert(v2.outcome == MatchOutcome.ACCEPTED && v2.path == MatchPath.DETERMINISTIC)
    assert(v2.rulesFired == Vector(Rule.DeterministicWork))
    assert(v2.reasonCodes == Vector("EXACT_TITLE_AUTHORS_YEAR_WITHIN_1"))
    // Score -3 + 12(1-0.8) + 4(1-0.5) + 2(0-0.5) + 6(1-0.5) = 3.4; logistic(3.4) = 0.9677.
    assert(math.abs(v2.confidence - MatchPolicy.logistic(3.4)) < 1e-12)
    // er-v1 needs a compatible venue for its rule, so it accepts the same pair on the score.
    val v1 = decide(dblp, openAlex, V1)
    assert(v1.outcome == MatchOutcome.ACCEPTED && v1.path == MatchPath.PROBABILISTIC)
  }

  test("one-year gap: er-v2 ACCEPTED (DETERMINISTIC), er-v1 LLM band; two years: unchanged") {
    val later = openAlex.copy(year = Some(2021))
    val v2 = decide(dblp, later, V2)
    assert(v2.outcome == MatchOutcome.ACCEPTED && v2.rulesFired == Vector(Rule.DeterministicWork))
    assert(v2.confidence == t.accept) // score logistic(1.4) = 0.802 is below accept
    val v1 = decide(dblp, later, V1)
    assert(v1.outcome == MatchOutcome.UNRESOLVED && v1.path == MatchPath.LLM_BAND)
    assert(math.abs(v1.confidence - MatchPolicy.logistic(1.4)) < 1e-12)
    // |Δyear| = 2 is outside the rule: er-v2 decides exactly like er-v1.
    val twoYears = openAlex.copy(year = Some(2022))
    assert(decide(dblp, twoYears, V2).copy(policyVersion = V1) == decide(dblp, twoYears, V1))
    // Same tokens in another order: title_similarity 1 but not an identical normalized title.
    val reordered = later.copy(title = Some("A Survey: Deep Learning"))
    assert(features(dblp, reordered, V2)(MatchPolicy.TitleExactAgreement) == 0.0)
    assert(decide(dblp, reordered, V2).path != MatchPath.DETERMINISTIC)
    // A different author sequence is outside the rule.
    val swapped = later.copy(authors =
      later.authors.reverse.zipWithIndex.map((m, i) => m.copy(position = i + 1))
    )
    assert(decide(dblp, swapped, V2).path != MatchPath.DETERMINISTIC)
  }

  test("er-v1 unchanged: feature maps, default version, and every decision on er-v1 features") {
    assert(MatchPolicy.Version == V1 && MatchPolicy.Versions == Vector(V1, V2))
    assert(WorkPairFeatures.work(dblp, openAlex) == WorkPairFeatures.work(dblp, openAlex, V1))
    assert(
      WorkPairFeatures.work(dblp, openAlex, V2) -- Seq(
        MatchPolicy.SourceAgreement,
        MatchPolicy.TitleExactAgreement
      ) == WorkPairFeatures.work(dblp, openAlex, V1)
    )
    // Exhaustive grid over the er-v1 feature keys (absent, 0, 0.5, 1): without the two er-v2
    // features, er-v2 decides every pair exactly like er-v1, and both versions' default is er-v1.
    val levels = Vector(None, Some(0.0), Some(0.5), Some(1.0))
    def grid(keys: Vector[String]): Iterator[Map[String, Double]] =
      keys.foldLeft(Iterator(Map.empty[String, Double])) { (acc, k) =>
        acc.flatMap(m => levels.iterator.map(_.fold(m)(v => m + (k -> v))))
      }
    val keys = Map(
      MatchEntityType.RESEARCHER -> Vector(
        F.NameSimilarity,
        F.InitialsCompatibility,
        F.CoauthorOverlap,
        F.AffiliationCompatibility,
        F.AuthorOrderAgreement,
        F.OrcidAgreement,
        F.HomonymSuffixAgreement
      ),
      MatchEntityType.WORK -> Vector(
        F.TitleSimilarity,
        F.YearDistance,
        F.VenueCompatibility,
        MatchPolicy.AuthorOverlap,
        MatchPolicy.AuthorSequenceAgreement,
        MatchPolicy.DoiAgreement,
        MatchPolicy.ArxivAgreement,
        MatchPolicy.DblpRecordAgreement
      )
    )
    var n = 0
    for (et, ks) <- keys; f <- grid(ks) do
      val v1 = MatchPolicy.decideBlocked(et, f, t)
      assert(v1 == MatchPolicy.decideBlocked(et, f, t, V1) && v1.policyVersion == V1)
      assert(MatchPolicy.decideBlocked(et, f, t, V2) == v1.copy(policyVersion = V2), s"$et $f")
      n += 1
    assert(n == 16384 + 65536)
    // Researcher rules are identical even with the er-v2 work features present.
    val r = Map(
      F.NameSimilarity -> 1.0,
      F.HomonymSuffixAgreement -> 1.0,
      MatchPolicy.SourceAgreement -> 1.0,
      MatchPolicy.TitleExactAgreement -> 1.0
    )
    assert(
      MatchPolicy.decideBlocked(MatchEntityType.RESEARCHER, r, t, V2).rulesFired ==
        Vector(Rule.Deterministic)
    )
  }

  test("the decision records the version that decided; unknown versions never run") {
    val c = MatchCandidate(
      SchemaVersions.MatchCandidate,
      MatchEntityType.WORK,
      dblp.workId,
      openAlex.workId,
      "w0:deep",
      features(dblp, openAlex, V2),
      prov(SourceType.DERIVED, "k")
    )
    for version <- Seq(V1, V2) do
      val v = MatchPolicy.decide(c, CandidateOrigin.BLOCKED, t, version)
      val d = MatchPolicy.toDecision(c, c.features, v, run, "2026-11-03T00:00:00Z", None, None)
      assert(Validation.validate(d).isRight && d.provenance.policyVersion.contains(version))
    val exact = MatchPolicy.decide(c, CandidateOrigin.EXACT_MATCH, t, V2)
    assert(exact.policyVersion == V2)
    assertThrows[IllegalArgumentException](
      MatchPolicy.decide(c, CandidateOrigin.BLOCKED, t, "er-v3")
    )
    assertThrows[IllegalArgumentException](WorkPairFeatures.work(dblp, openAlex, "er-v3"))
  }

  test(
    "calibration classifies with the run's policy: a one-year-gap pair is deterministic in er-v2"
  ) {
    val f = features(dblp, openAlex.copy(year = Some(2021)), V2)
    assert(Calibrate.classify(f, MatchEntityType.WORK, V2)._1 == Calibrate.Kind.Deterministic)
    assert(Calibrate.classify(f, MatchEntityType.WORK, V1)._1 == Calibrate.Kind.Scored)
    // The er-v1 rule still counts as deterministic.
    val v1Rule = Map(
      F.TitleSimilarity -> 1.0,
      F.YearDistance -> 1.0,
      MatchPolicy.AuthorSequenceAgreement -> 1.0
    )
    assert(Calibrate.classify(v1Rule, MatchEntityType.WORK, V1)._1 == Calibrate.Kind.Deterministic)
  }

  private val orcid = "orcid:0000-0001-2345-6789"

  test("review 1 finding 1: one ORCID on Wei Wang 0001 and Wei Wang 0002 is an er-v2 conflict") {
    // suffixes 0001 and 0002: two distinct DBLP homonym numbers under one ORCID
    assert(MatchPolicy.orcidGroupSuffixConflict(orcid, Seq("Wei Wang 0001", "Wei Wang 0002"), V2))
    // order and repeats do not matter: {0001, 0002} is still two numbers
    assert(
      MatchPolicy.orcidGroupSuffixConflict(
        orcid,
        Seq("Wei Wang 0002", "Wei Wang", "Wei Wang 0001", "Wei Wang 0002"),
        V2
      )
    )
    assert(Rule.OrcidGroupSuffixConflict == "er-v2:orcid-group-homonym-suffix-conflict")
  }

  test("er-v2: a mention without a suffix, or one shared suffix, is not a conflict") {
    // suffixes {0001}: one number, the unsuffixed mention adds none
    assert(!MatchPolicy.orcidGroupSuffixConflict(orcid, Seq("Wei Wang 0001", "Wei Wang"), V2))
    assert(
      !MatchPolicy.orcidGroupSuffixConflict(orcid, Seq("Wei Wang 0001", "W. Wang 0001"), V2)
    )
    assert(!MatchPolicy.orcidGroupSuffixConflict(orcid, Seq("Wei Wang", "Wang, Wei"), V2))
    assert(!MatchPolicy.orcidGroupSuffixConflict(orcid, Seq.empty, V2))
    // only orcid: groups; an OpenAlex author key is not under this rule
    assert(
      !MatchPolicy.orcidGroupSuffixConflict(
        "openalex:A123",
        Seq("Wei Wang 0001", "Wei Wang 0002"),
        V2
      )
    )
  }

  test("er-v1 unchanged: the reviewer's failure input is not a group conflict") {
    assert(!MatchPolicy.orcidGroupSuffixConflict(orcid, Seq("Wei Wang 0001", "Wei Wang 0002"), V1))
    // the matched pair stays ACCEPTED under er-v1 exactly as before (no candidate features)
    val c = MatchCandidate(
      SchemaVersions.MatchCandidate,
      MatchEntityType.RESEARCHER,
      "dblp:conf/x/A1#1",
      orcid,
      orcid,
      Map.empty,
      prov(SourceType.DERIVED, s"$orcid|dblp:conf/x/A1#1|$orcid")
    )
    val v = MatchPolicy.decide(c, CandidateOrigin.EXACT_MATCH, t, V1)
    assert(v.outcome == MatchOutcome.ACCEPTED && v.reasonCodes == Vector("EXACT_ORCID_MATCH"))
    assertThrows[IllegalArgumentException](
      MatchPolicy.orcidGroupSuffixConflict(orcid, Seq("Wei Wang 0001"), "er-v9")
    )
  }
