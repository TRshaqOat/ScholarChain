package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.core.config.ConfigLoader
import scholarchain.entityresolution.Features as F
import scholarchain.entityresolution.MatchPolicy.Rule

class MatchPolicySpec extends AnyFunSuite:
  private val t = Thresholds(accept = 0.93, reject = 0.35, llmBandMin = 0.65, llmBandMax = 0.93)
  private val run = "run-" + "a" * 32

  private def cand(et: MatchEntityType, a: String, b: String, key: String, f: Map[String, Double]) =
    MatchCandidate(
      SchemaVersions.MatchCandidate,
      et,
      a,
      b,
      key,
      f,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        s"$key|$a|$b",
        "2026-10-03",
        run,
        None,
        None
      )
    )
  private def researcher(f: (String, Double)*) =
    MatchPolicy.decideBlocked(MatchEntityType.RESEARCHER, f.toMap, t)
  private def work(f: (String, Double)*) =
    MatchPolicy.decideBlocked(MatchEntityType.WORK, f.toMap, t)

  // Same printed name and initials, no other evidence: score logistic(-1 + 1 + 2 - 0.6) = 0.802.
  private val sameNameOnly =
    Seq(
      F.NameSimilarity -> 1.0,
      F.InitialsCompatibility -> 1.0,
      F.CoauthorOverlap -> 0.0,
      F.AuthorOrderAgreement -> 0.5
    )
  private val strongCoauthors =
    Seq(
      F.NameSimilarity -> 1.0,
      F.InitialsCompatibility -> 1.0,
      F.CoauthorOverlap -> 0.5,
      F.AuthorOrderAgreement -> 0.5
    )

  test("exact identifier matches: DOI, arXiv, ORCID accepted with reason codes and confidence 1") {
    for (et, key, code) <- Seq(
        (MatchEntityType.WORK, "doi:10.1145/1", "EXACT_DOI_MATCH"),
        (MatchEntityType.WORK, "arxiv:2101.00001", "EXACT_ARXIV_MATCH"),
        (MatchEntityType.RESEARCHER, "orcid:0000-0002-1825-0097", "EXACT_ORCID_MATCH")
      )
    do
      val v = MatchPolicy.decide(
        cand(et, "openalex:W1", "dblp:conf/x/Y1", key, Map.empty),
        CandidateOrigin.EXACT_MATCH,
        t
      )
      assert(
        v.outcome == MatchOutcome.ACCEPTED && v.confidence == 1.0 && v.reasonCodes == Vector(code)
      )
      assert(v.path == MatchPath.EXACT_ID)
  }

  test("an OpenAlex author ID alone is not authoritative: UNRESOLVED, not LLM-eligible") {
    val c =
      cand(MatchEntityType.RESEARCHER, "openalex:W1#1", "openalex:A5", "openalex:A5", Map.empty)
    val v = MatchPolicy.decide(c, CandidateOrigin.EXACT_MATCH, t)
    assert(
      v.outcome == MatchOutcome.UNRESOLVED && v.reasonCodes == Vector("NON_AUTHORITATIVE_SOURCE_ID")
    )
    assert(!v.llmEligible)
  }

  test("a conflicting exact-ID group is CONFLICT") {
    val c = cand(MatchEntityType.WORK, "dblp:conf/a/B1", "dblp:conf/a/B2", "doi:10.1/x", Map.empty)
    val v = MatchPolicy.decide(c, CandidateOrigin.EXACT_CONFLICT, t)
    assert(v.outcome == MatchOutcome.CONFLICT && v.reasonCodes == Vector("EXACT_ID_GROUP_CONFLICT"))
  }

  test("blocked pair with the same valid ORCID: ACCEPTED on the exact-ID path") {
    val v = researcher((F.OrcidAgreement -> 1.0) +: sameNameOnly*)
    assert(
      v.outcome == MatchOutcome.ACCEPTED && v.confidence == 1.0 && v.path == MatchPath.EXACT_ID
    )
    assert(v.reasonCodes == Vector("EXACT_ORCID_MATCH"))
  }

  test(
    "different ORCIDs never merge: REJECTED on weak evidence, CONFLICT when other evidence would accept"
  ) {
    val weak =
      researcher(F.OrcidAgreement -> 0.0, F.NameSimilarity -> 0.7, F.InitialsCompatibility -> 0.0)
    assert(weak.outcome == MatchOutcome.REJECTED && weak.reasonCodes == Vector("ORCID_MISMATCH"))
    val strong = researcher((F.OrcidAgreement -> 0.0) +: strongCoauthors*)
    assert(strong.outcome == MatchOutcome.CONFLICT)
    assert(strong.reasonCodes.contains("ORCID_MISMATCH"))
    assert(strong.reasonCodes.contains("BIBLIOGRAPHIC_EVIDENCE_CONTRADICTS_ID"))
    val det =
      researcher(F.OrcidAgreement -> 0.0, F.NameSimilarity -> 1.0, F.HomonymSuffixAgreement -> 1.0)
    assert(det.outcome == MatchOutcome.CONFLICT)
  }

  test("same ORCID but different DBLP homonym numbers: CONFLICT") {
    val v =
      researcher(F.OrcidAgreement -> 1.0, F.HomonymSuffixAgreement -> 0.0, F.NameSimilarity -> 1.0)
    assert(v.outcome == MatchOutcome.CONFLICT)
    assert(
      v.reasonCodes == Vector("EXACT_ORCID_MATCH", "HOMONYM_SUFFIX_MISMATCH", "IDENTIFIER_CONFLICT")
    )
  }

  test("different DBLP homonym numbers are strong negative evidence") {
    val v = researcher((F.HomonymSuffixAgreement -> 0.0) +: sameNameOnly*)
    assert(v.outcome == MatchOutcome.REJECTED && v.reasonCodes == Vector("HOMONYM_SUFFIX_MISMATCH"))
    assert(v.confidence == 0.0)
    // Even overwhelming bibliographic evidence cannot merge them; it becomes a CONFLICT for review.
    val strong = researcher((F.HomonymSuffixAgreement -> 0.0) +: strongCoauthors*)
    assert(strong.outcome == MatchOutcome.CONFLICT)
  }

  test(
    "same name and same DBLP homonym number: deterministic ACCEPTED; same number, different name: not"
  ) {
    val v =
      researcher(F.NameSimilarity -> 1.0, F.HomonymSuffixAgreement -> 1.0, F.CoauthorOverlap -> 0.0)
    assert(v.outcome == MatchOutcome.ACCEPTED && v.path == MatchPath.DETERMINISTIC)
    assert(v.reasonCodes == Vector("SAME_NAME_SAME_HOMONYM_SUFFIX") && v.confidence >= t.accept)
    val other = researcher(
      F.NameSimilarity -> 0.92,
      F.HomonymSuffixAgreement -> 1.0,
      F.InitialsCompatibility -> 0.0
    )
    assert(other.path != MatchPath.DETERMINISTIC && other.outcome != MatchOutcome.ACCEPTED)
  }

  test("a matching name alone is not identity: UNRESOLVED in the LLM band") {
    val v = researcher(sameNameOnly*)
    assert(v.outcome == MatchOutcome.UNRESOLVED && v.llmEligible && v.path == MatchPath.LLM_BAND)
    assert(math.abs(v.confidence - MatchPolicy.logistic(1.4)) < 1e-12)
    val corroborated = researcher(strongCoauthors*)
    assert(
      corroborated.outcome == MatchOutcome.ACCEPTED && corroborated.path == MatchPath.PROBABILISTIC
    )
  }

  test("weighted score: logistic of bias plus weighted deviations of present features") {
    val f = Map(
      F.NameSimilarity -> 0.8,
      F.InitialsCompatibility -> 0.0,
      F.CoauthorOverlap -> 0.05,
      F.AuthorOrderAgreement -> 1.0
    )
    // -1 + 10(0.8-0.9) + 4(0-0.5) + 12(0.05-0.05) + 0.5(1-0.5) = -3.75
    assert(
      math.abs(
        MatchPolicy.score(MatchEntityType.RESEARCHER, f).get - MatchPolicy.logistic(-3.75)
      ) < 1e-12
    )
    assert(researcher(f.toSeq*).outcome == MatchOutcome.REJECTED)
  }

  test("absent features are neutral, never read as 0.0, and are recorded") {
    val present = Map(F.NameSimilarity -> 1.0, F.InitialsCompatibility -> 1.0)
    val withZero = present + (F.CoauthorOverlap -> 0.0)
    val sAbsent = MatchPolicy.score(MatchEntityType.RESEARCHER, present).get
    val sZero = MatchPolicy.score(MatchEntityType.RESEARCHER, withZero).get
    assert(math.abs(sAbsent - MatchPolicy.logistic(-1 + 1 + 2)) < 1e-12)
    assert(sZero < sAbsent)
    val v = researcher(present.toSeq*)
    assert(v.rulesFired.contains(s"${Rule.AbsentNeutral}:${F.CoauthorOverlap}"))
    assert(v.rulesFired.contains(s"${Rule.AbsentNeutral}:${F.AffiliationCompatibility}"))
  }

  test("without the required feature the pair is UNRESOLVED for insufficient evidence") {
    val r = researcher(F.CoauthorOverlap -> 1.0)
    assert(
      r.outcome == MatchOutcome.UNRESOLVED && r.reasonCodes == Vector(
        "INSUFFICIENT_EVIDENCE"
      ) && !r.llmEligible
    )
    val w = work(F.YearDistance -> 1.0)
    assert(w.outcome == MatchOutcome.UNRESOLVED && w.reasonCodes == Vector("INSUFFICIENT_EVIDENCE"))
  }

  test(
    "threshold boundaries: accept inclusive, reject exclusive, LLM band inclusive inside UNRESOLVED"
  ) {
    def at(p: Double) = MatchPolicy.banded(p, t, Vector.empty)
    assert(at(0.93).outcome == MatchOutcome.ACCEPTED)
    assert(at(0.9299).outcome == MatchOutcome.UNRESOLVED && at(0.9299).llmEligible)
    assert(at(0.65).llmEligible && at(0.65).outcome == MatchOutcome.UNRESOLVED)
    assert(!at(0.6499).llmEligible && at(0.6499).outcome == MatchOutcome.UNRESOLVED)
    assert(at(0.35).outcome == MatchOutcome.UNRESOLVED)
    assert(at(0.3499).outcome == MatchOutcome.REJECTED)
    val narrow = Thresholds(0.95, 0.2, 0.5, 0.6)
    assert(MatchPolicy.banded(0.7, narrow, Vector.empty).rulesFired == Vector(Rule.BandUnresolved))
  }

  test("thresholds come from configuration and must be ordered") {
    val er = ConfigLoader.load(Map.empty).toOption.get.entityResolution
    assert(
      Thresholds.from(er) == Thresholds(
        er.acceptThreshold,
        er.rejectThreshold,
        er.llmBandMin,
        er.llmBandMax
      )
    )
    assertThrows[IllegalArgumentException](Thresholds(0.5, 0.6, 0.6, 0.6))
  }

  test(
    "works: DOI agreement accepts, DOI disagreement never merges, title+year+authors is deterministic"
  ) {
    assert(
      work(
        MatchPolicy.DoiAgreement -> 1.0,
        F.TitleSimilarity -> 0.2
      ).outcome == MatchOutcome.ACCEPTED
    )
    val exactTitle = Seq(
      F.TitleSimilarity -> 1.0,
      F.YearDistance -> 1.0,
      F.VenueCompatibility -> 1.0,
      MatchPolicy.AuthorOverlap -> 1.0,
      MatchPolicy.AuthorSequenceAgreement -> 1.0
    )
    val det = work(exactTitle*)
    assert(det.outcome == MatchOutcome.ACCEPTED && det.path == MatchPath.DETERMINISTIC)
    assert(det.reasonCodes == Vector("EXACT_TITLE_YEAR_AUTHORS"))
    assert(work((MatchPolicy.DoiAgreement -> 0.0) +: exactTitle*).outcome == MatchOutcome.CONFLICT)
    val diff =
      work(MatchPolicy.DoiAgreement -> 0.0, F.TitleSimilarity -> 0.3, F.YearDistance -> 0.5)
    assert(diff.outcome == MatchOutcome.REJECTED && diff.reasonCodes == Vector("DOI_MISMATCH"))
    val venueClash = exactTitle.toMap + (F.VenueCompatibility -> 0.0)
    assert(work(venueClash.toSeq*).path != MatchPath.DETERMINISTIC)
  }

  test("works: a generic title with different authors (\"Workshop Introduction.\") is not merged") {
    val frontMatter = work(
      F.TitleSimilarity -> 1.0,
      F.YearDistance -> 1.0,
      F.VenueCompatibility -> 1.0,
      MatchPolicy.AuthorOverlap -> 0.0,
      MatchPolicy.AuthorSequenceAgreement -> 0.0
    )
    assert(
      frontMatter.outcome == MatchOutcome.UNRESOLVED && frontMatter.path == MatchPath.PROBABILISTIC
    )
    // -3 + 12(0.2) + 4(0.5) + 2(0.5) + 6(-0.5) = -0.6
    assert(math.abs(frontMatter.confidence - MatchPolicy.logistic(-0.6)) < 1e-12)
    // A matching title, year, and venue without any author evidence is not identity either.
    val titleOnly =
      work(F.TitleSimilarity -> 1.0, F.YearDistance -> 1.0, F.VenueCompatibility -> 1.0)
    assert(titleOnly.outcome == MatchOutcome.UNRESOLVED && titleOnly.llmEligible)
    assert(math.abs(titleOnly.confidence - MatchPolicy.logistic(2.4)) < 1e-12)
  }

  test("works: two different DBLP records never merge (REJECTED, or CONFLICT on strong evidence)") {
    val strong = work(
      MatchPolicy.DblpRecordAgreement -> 0.0,
      F.TitleSimilarity -> 1.0,
      F.YearDistance -> 1.0,
      F.VenueCompatibility -> 0.0,
      MatchPolicy.AuthorOverlap -> 1.0,
      MatchPolicy.AuthorSequenceAgreement -> 1.0
    )
    assert(
      strong.outcome == MatchOutcome.CONFLICT && strong.reasonCodes.contains("DBLP_RECORD_MISMATCH")
    )
    val weak =
      work(MatchPolicy.DblpRecordAgreement -> 0.0, F.TitleSimilarity -> 1.0, F.YearDistance -> 1.0)
    assert(
      weak.outcome == MatchOutcome.REJECTED && weak.reasonCodes == Vector("DBLP_RECORD_MISMATCH")
    )
  }

  test(
    "work-pair features: normalized DOI equality, author overlap and order, absent without evidence"
  ) {
    val prov = Provenance(
      SchemaVersions.Provenance,
      SourceType.DBLP,
      "conf/a/B1",
      "2026-10-03",
      run,
      None,
      None
    )
    def m(pos: Int, name: String) = AuthorMention(
      SchemaVersions.AuthorMention,
      s"dblp:conf/a/B1#$pos",
      "dblp:conf/a/B1",
      pos,
      name,
      name,
      None,
      None,
      Vector.empty,
      prov
    )
    val w = WorkRecord(
      SchemaVersions.WorkRecord,
      "dblp:conf/a/B1",
      SourceType.DBLP,
      "conf/a/B1",
      Some("T"),
      None,
      Some(2020),
      None,
      None,
      Some("10.1/ABC"),
      None,
      Map.empty,
      Vector(m(1, "Wei Wang 0001"), m(2, "Li Zhang")),
      None,
      prov
    )
    val same = WorkPairFeatures.work(w, w.copy(doi = Some("https://doi.org/10.1/abc")))
    assert(
      same == Map(
        MatchPolicy.DoiAgreement -> 1.0,
        MatchPolicy.AuthorOverlap -> 1.0,
        MatchPolicy.AuthorSequenceAgreement -> 1.0,
        MatchPolicy.DblpRecordAgreement -> 1.0
      )
    )
    val swapped = WorkPairFeatures.work(
      w,
      w.copy(doi = Some("10.1/xyz"), authors = Vector(m(1, "Li Zhang"), m(2, "W. Wang")))
    )
    assert(swapped(MatchPolicy.DoiAgreement) == 0.0 && swapped(MatchPolicy.AuthorOverlap) == 1.0)
    assert(swapped(MatchPolicy.AuthorSequenceAgreement) == 0.0)
    assert(
      WorkPairFeatures.work(w, w.copy(doi = None, authors = Vector.empty)) == Map(
        MatchPolicy.DblpRecordAgreement -> 1.0
      )
    )
    val other = w.copy(workId = "dblp:conf/a/B2", source = SourceType.OPENALEX)
    assert(!WorkPairFeatures.work(w, other).contains(MatchPolicy.DblpRecordAgreement))
    assert(
      WorkPairFeatures.work(w, other.copy(source = SourceType.DBLP))(
        MatchPolicy.DblpRecordAgreement
      ) == 0.0
    )
  }

  test(
    "decisions are deterministic and validate as core MatchDecision records with er-v1 provenance"
  ) {
    val c = cand(
      MatchEntityType.RESEARCHER,
      "dblp:conf/a/B1#1",
      "dblp:conf/a/B2#2",
      "r0:wang|w",
      strongCoauthors.toMap
    )
    val v1 = MatchPolicy.decide(c, CandidateOrigin.BLOCKED, t)
    val v2 = MatchPolicy.decide(
      c.copy(features = c.features.toSeq.reverse.toMap),
      CandidateOrigin.BLOCKED,
      t
    )
    assert(v1 == v2)
    val d = MatchPolicy.toDecision(c, c.features, v1, run, "2026-11-03T00:00:00Z", None, None)
    assert(Validation.validate(d).isRight)
    assert(d.provenance.policyVersion.contains("er-v1") && d.provenance.runId == run)
    assert(d.provenance.sourceRecordId == c.provenance.sourceRecordId && d.rulesFired.nonEmpty)
  }

  test(
    "adjudication: confident ACCEPT or REJECT decides; low confidence and ABSTAIN stay UNRESOLVED"
  ) {
    val v = researcher(sameNameOnly*)
    def adj(d: LlmDecision, c: Double) =
      Adjudication(
        d,
        c,
        Vector("a.name"),
        Vector("NAME_MATCH"),
        "er-adjudication-v1",
        "m",
        false,
        None
      )
    val acc = MatchPolicy.withAdjudication(v, adj(LlmDecision.ACCEPT, 0.95), t)
    assert(
      acc.outcome == MatchOutcome.ACCEPTED && acc.path == MatchPath.LLM && acc.reasonCodes.head == "LLM_ACCEPT"
    )
    val rej = MatchPolicy.withAdjudication(v, adj(LlmDecision.REJECT, 0.9), t)
    assert(rej.outcome == MatchOutcome.REJECTED && math.abs(rej.confidence - 0.1) < 1e-12)
    assert(
      MatchPolicy
        .withAdjudication(v, adj(LlmDecision.ACCEPT, 0.7), t)
        .outcome == MatchOutcome.UNRESOLVED
    )
    val ab = MatchPolicy.withAdjudication(v, adj(LlmDecision.ABSTAIN, 0.0), t)
    assert(
      ab.outcome == MatchOutcome.UNRESOLVED && ab.reasonCodes.contains(
        "LLM_ABSTAIN"
      ) && ab.path == MatchPath.LLM
    )
    assertThrows[IllegalArgumentException](
      MatchPolicy.withAdjudication(researcher(strongCoauthors*), adj(LlmDecision.ACCEPT, 0.99), t)
    )
  }

  test("the policy is pure: MatchPolicy.scala imports no AWS, Hadoop, Spark, or file APIs") {
    val src = scala.io.Source.fromFile(
      ModuleFiles.main.resolve("scala/scholarchain/entityresolution/MatchPolicy.scala").toFile
    )
    val text = try src.mkString
    finally src.close()
    Seq("software.amazon", "org.apache.hadoop", "org.apache.spark", "java.nio", "java.io").foreach(
      p => assert(!text.contains(p), p)
    )
  }
