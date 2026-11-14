package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{EvidenceType, StableIds, VerificationStatus}
import upickle.default.write

/** Review 1 F1 to F4 (rulings 17 to 20): numbers bound to named facts, windows in the text,
  * semantic-only claims, and names outside the bundle, on the fixture world (no network).
  */
class VerifierSpec extends AnyFunSuite:
  import RuntimeFixtures.*
  import VerificationStatus.*

  private val pairQ = "Have Ada Lovelace and Grace Hopper coauthored a paper?"
  private val pathQ =
    "What is the shortest coauthorship path between Ada Lovelace and Edsger Dijkstra?"
  private def ok(r: Either[RuntimeFailure, RuntimeResult]) = r.fold(f => fail(s"$f"), identity)
  private def ev(t: EvidenceType, parts: Seq[String], run: String) =
    StableIds.evidenceId(t, parts, run)
  private val adaMetric = ev(EvidenceType.METRIC_RECORD, Seq(ada, "WINDOW#all-years"), metricRun)
  private val graceMetric =
    ev(EvidenceType.METRIC_RECORD, Seq(grace, "WINDOW#all-years"), metricRun)
  private val edgeEv = ev(EvidenceType.COAUTHOR_EDGE, Seq(adaGrace.id), graphRun)
  private val profileEv = ev(EvidenceType.PROFILE, Seq(profileKey), renderRun)

  private def draft(
      kind: ClaimKind,
      text: String,
      ids: Vector[String],
      values: Map[String, String],
      window: Option[String] = Some("all-years"),
      subjects: Vector[String] = Vector(ada)
  ) = ClaimDraft("claim-1", kind, text, ids, subjects, window, values)

  private def context(q: String)(body: (Verifier.Context, RuntimeResult) => Unit): Unit =
    withWorld() { w =>
      val r = ok(w.ask(q))
      body(Verifier.Context(r.bundle.get, r.answer.resolutions, config), r)
    }

  private def status(d: ClaimDraft, c: Verifier.Context) = Verifier.check(d, c).status
  private def failed(d: ClaimDraft, c: Verifier.Context) =
    Verifier.check(d, c).checks.filter(_.outcome != CheckOutcome.PASS).map(_.rule).toSet

  test("numbers: integers exact, decimals equal or rounded half-up from at least 2 decimals") {
    import Verifier.numberMatches
    assert(numberMatches("4", "4") && numberMatches("0.55", "0.55") && numberMatches("0.5", "0.5"))
    assert(numberMatches("0.55", "0.5476") && numberMatches("0.548", "0.5476"))
    assert(!numberMatches("1", "0.55")) // review 1 (c): 0.55 never verifies as 1
    assert(!numberMatches("0.6", "0.55") && !numberMatches("0.5", "0.5476"))
    assert(!numberMatches("4", "4.2") && !numberMatches("5", "4") && !numberMatches("x", "x"))
  }

  test("F1 (a): a number must be bound to the named fact it states, not to any fact or to none") {
    context(pairQ) { (c, _) =>
      val text = "In all-years, Ada Lovelace has 10 distinct coauthors."
      // values {}: nothing asserted, the number is bound to nothing.
      val none = draft(ClaimKind.METRIC_VALUE, text, Vector(adaMetric), Map.empty)
      assert(status(none, c) == UNSUPPORTED)
      // It asserts no fact, so it may name nobody either (ruling 29, N2).
      assert(failed(none, c) == Set("values-required", "numbers-in-text", "identity"))
      // 10 is Ada's publicationCount, but the text says distinct coauthors.
      val other =
        draft(ClaimKind.METRIC_VALUE, text, Vector(adaMetric), Map("publicationCount" -> "10"))
      assert(status(other, c) == UNSUPPORTED)
      assert(
        Verifier
          .check(other, c)
          .checks
          .exists(x => x.rule == "numbers-in-text" && x.detail.contains("publicationCount"))
      )
      // The same number named as what it is verifies, and so do both measures side by side.
      val right = draft(
        ClaimKind.METRIC_VALUE,
        "In all-years, Ada Lovelace has 10 publications.",
        Vector(adaMetric),
        Map("publicationCount" -> "10")
      )
      assert(status(right, c) == VERIFIED)
      val both = draft(
        ClaimKind.METRIC_VALUE,
        "In all-years, Ada Lovelace has 1 distinct coauthor over 10 publications.",
        Vector(adaMetric),
        Map("publicationCount" -> "10", "distinctCoauthorCount" -> "1")
      )
      assert(status(both, c) == VERIFIED)
      val swapped =
        both.copy(text = "In all-years, Ada Lovelace has 10 distinct coauthors over 1 publication.")
      assert(status(swapped, c) == UNSUPPORTED)
    }
  }

  test("F1 (a): a rank number verifies only in a RANKING claim, against that researcher's rank") {
    context("Which researchers have the most distinct coauthors?") { (c, _) =>
      val ranking = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.RANKING_RECORD).get
      val k = (1 to 3).find(i => ranking.facts(s"rank.$i.researcherId") == ada).get
      val text = s"Ada Lovelace ranks $k by distinct coauthors in all-years."
      val rankOnly = Map(s"rank.$k.storedRank" -> k.toString)
      val asMetric =
        draft(ClaimKind.METRIC_VALUE, text, Vector(ranking.evidenceId), rankOnly)
      assert(status(asMetric, c) == UNSUPPORTED && failed(asMetric, c).contains("rank"))
      val noResearcher = draft(ClaimKind.RANKING, text, Vector(ranking.evidenceId), rankOnly)
      assert(status(noResearcher, c) == UNSUPPORTED)
      val good = noResearcher.copy(values = rankOnly + (s"rank.$k.researcherId" -> ada))
      assert(status(good, c) == VERIFIED)
      val wrongRank = good.copy(text = s"Ada Lovelace ranks ${k % 3 + 1} by distinct coauthors.")
      assert(status(wrongRank, c) == UNSUPPORTED)
    }
  }

  test("F1 (b): PATH_HOP asserts every fact of its hop, and the joint count is the hop's") {
    context(pathQ) { (c, _) =>
      val p = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
      assert(p.facts("hop.1.jointPublicationCount") == "2")
      assert(p.facts("hop.2.jointPublicationCount") == "1")
      val text = "Ada Lovelace and Grace Hopper have 1 joint publications."
      val names = Map("hop.1.from" -> ada, "hop.1.to" -> grace)
      // Review 1 input (b): only from and to asserted; 1 is hop 2's count.
      val b = draft(ClaimKind.PATH_HOP, text, Vector(p.evidenceId), names, subjects = Vector.empty)
      assert(status(b, c) == UNSUPPORTED)
      assert(failed(b, c) == Set("path-hops", "numbers-in-text"))
      val full = names ++ Map(
        "hop.1.jointPublicationCount" -> "2",
        "hop.1.firstYear" -> "2001",
        "hop.1.lastYear" -> "2002"
      )
      assert(status(b.copy(values = full), c) == UNSUPPORTED) // 1 is still not hop 1's count
      val wrongCount = full + ("hop.1.jointPublicationCount" -> "1")
      assert(status(b.copy(values = wrongCount), c) == CONFLICTING)
      val good = b.copy(
        text =
          "Ada Lovelace and Grace Hopper have 2 joint publications, first in 2001 and last in 2002.",
        values = full
      )
      assert(status(good, c) == VERIFIED)
      // The edge of another hop may not be cited for this hop.
      val stray = ev(EvidenceType.COAUTHOR_EDGE, Seq(graceEdsger.id), graphRun)
      assert(status(good.copy(evidenceIds = Vector(p.evidenceId, stray)), c) == UNSUPPORTED)
    }
  }

  test("F1 (c): a decimal never verifies as a rounded integer") {
    context(pairQ) { (c, _) =>
      val pc = draft(
        ClaimKind.BRIDGE,
        "In all-years, Grace Hopper's participation coefficient is 1.",
        Vector(graceMetric),
        Map("participationCoefficient" -> "1"),
        subjects = Vector(grace)
      )
      assert(status(pc, c) == CONFLICTING)
      val exact = pc.copy(
        text = "In all-years, Grace Hopper's participation coefficient is 0.5.",
        values = Map("participationCoefficient" -> "0.5")
      )
      assert(status(exact, c) == VERIFIED)
    }
  }

  test("F2: a window name or year range in the text must be the claim's window") {
    context(pairQ) { (c, _) =>
      val base = draft(
        ClaimKind.METRIC_VALUE,
        "In 2015-2026, Ada Lovelace has 1 distinct coauthor.",
        Vector(adaMetric),
        Map("distinctCoauthorCount" -> "1")
      )
      // Review 1 input: the field says all-years, the text another window.
      assert(status(base, c) == CONFLICTING)
      assert(
        Verifier.check(base, c).checks.exists(x => x.rule == "window" && x.detail.contains("2015"))
      )
      assert(
        status(
          base.copy(text = "In 2000-2014, Ada Lovelace has 1 distinct coauthor."),
          c
        ) == CONFLICTING
      )
      assert(
        status(base.copy(text = "In 2015, Ada Lovelace has 1 distinct coauthor."), c) == UNSUPPORTED
      )
      assert(
        status(
          base.copy(text = "In all-years (2000-2026), Ada Lovelace has 1 distinct coauthor."),
          c
        ) ==
          VERIFIED
      )
      // First and last collaboration years asserted through values are facts, not a window.
      val edge = draft(
        ClaimKind.COAUTHORSHIP,
        "Ada Lovelace and Grace Hopper have 2 joint publications in all-years, 2001-2002.",
        Vector(edgeEv),
        Map(
          "jointPublicationCount" -> "2",
          "firstCollaborationYear" -> "2001",
          "lastCollaborationYear" -> "2002"
        ),
        subjects = Vector(ada, grace)
      )
      assert(status(edge, c) == VERIFIED)
      assert(status(edge.copy(values = edge.values - "lastCollaborationYear"), c) == CONFLICTING)
    }
  }

  test("F3: a semantic-only claim is never VERIFIED: context only, or removed") {
    context(pairQ) { (c, _) =>
      def d(text: String, subjects: Vector[String] = Vector(ada)) =
        draft(ClaimKind.DESCRIPTIVE, text, Vector(profileEv), Map.empty, None, subjects)
      // Review 1 input: a coauthorship stated from a profile only.
      val f3 = d(
        "Ada Lovelace and Grace Hopper are close collaborators who frequently coauthor.",
        Vector(ada, grace)
      )
      assert(status(f3, c) == UNSUPPORTED && failed(f3, c) == Set("context-only"))
      assert(status(d("Ada Lovelace works on analytical engines."), c) == PARTIALLY_SUPPORTED)
      assert(status(d("Ada Lovelace has 3 students."), c) == UNSUPPORTED)
      assert(status(d("Ada Lovelace is a bridge between fields."), c) == UNSUPPORTED)
    }
    // Through the workflow: the semantic claim is kept as context, the answer still abstains.
    val synth = SynthesisOutput(
      RuntimeSchemas.SynthesisOutput,
      "Ada Lovelace works on analytical engines.",
      Vector(
        ClaimDraft(
          "claim-1",
          ClaimKind.DESCRIPTIVE,
          "Ada Lovelace works on analytical engines.",
          Vector(profileEv),
          Vector(ada),
          None,
          Map.empty
        ),
        ClaimDraft(
          "claim-2",
          ClaimKind.DESCRIPTIVE,
          "Ada Lovelace and Grace Hopper are close collaborators who frequently coauthor.",
          Vector(profileEv),
          Vector(ada, grace),
          None,
          Map.empty
        )
      ),
      Vector.empty
    )
    val llm = RecordedLlm(Vector(Right("{}"), Right("{}"), Right(write(synth))))
    withWorld(Some(llm)) { w =>
      val a = ok(w.ask(pairQ)).answer
      assert(a.abstention.map(_.reason).contains(AbstentionReason.NO_VERIFIED_CLAIM))
      assert(a.verification.counts == VerificationCounts(0, 1, 1, 0))
      assert(
        a.response.claims.map(x => (x.claimId, x.verificationStatus, x.uncertainty)) ==
          Vector(("claim-1", PARTIALLY_SUPPORTED, Some(Verifier.ContextOnly)))
      )
    }
  }

  test(
    "F4: a name outside the bundle fails, whether a corpus researcher or absent from the corpus"
  ) {
    def claim(other: String) = ClaimDraft(
      "claim-1",
      ClaimKind.COAUTHORSHIP,
      s"Ada Lovelace and $other have 2 joint publications in all-years.",
      Vector(edgeEv),
      Vector(ada, grace),
      Some("all-years"),
      Map("jointPublicationCount" -> "2")
    )
    val good = claim("Grace Hopper").copy(claimId = "claim-2")
    def run(bad: ClaimDraft, summary: String, uncertainty: Vector[String]) =
      val synth = SynthesisOutput(
        RuntimeSchemas.SynthesisOutput,
        summary,
        Vector(bad, good),
        uncertainty
      )
      val llm = RecordedLlm(Vector(Right("{}"), Right("{}"), Right(write(synth))))
      withWorld(Some(llm))(w => ok(w.ask(pairQ)))
    // Barbara Liskov is in the corpus (EntityLinkCache) but not in this bundle; Alan Turing is in
    // neither (review 1 input).
    for (other, corpus) <- Vector("Barbara Liskov" -> true, "Alan Turing" -> false) do
      val r = run(
        claim(other),
        s"Ada Lovelace and $other have 2 joint publications.",
        Vector(
          s"$other may be a coauthor.",
          s"Ada Lovelace was linked to $ada by dominant-name-v1: ${f"${10.0 / 11}%.4f"} of 11 works among 2 researchers with this display name."
        )
      )
      val a = r.answer
      val check = a.verification.checks.find(_.claimId == "claim-1").get
      assert(check.status == UNSUPPORTED, check)
      val identity = check.checks.find(_.rule == "identity").get
      assert(identity.outcome == CheckOutcome.FAIL)
      assert(identity.detail.contains(s"'$other'"))
      assert(identity.detail.contains("a corpus researcher") == corpus, identity.detail)
      assert(a.response.claims.map(_.claimId) == Vector("claim-2"))
      // The summary and the uncertainty line naming the outsider are replaced; the restated note stays.
      assert(a.response.summary == good.text)
      val u = a.response.uncertainty.get
      assert(!u.contains(other) && u.contains("1 drafted uncertainty line(s) were left out"))
      // The fixed note and the model's restated copy of it.
      assert(u.split(s"Ada Lovelace was linked to $ada").length == 3, u)
      // The corpus lookups went through the bounded EntityLinkCache read port.
      assert(r.audit.templateCalls.count(_.templateId == "kv.entity-link") >= 3)
  }

  private val edgeClaim = ClaimDraft(
    "claim-1",
    ClaimKind.COAUTHORSHIP,
    "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
    Vector(edgeEv),
    Vector(ada, grace),
    Some("all-years"),
    Map("jointPublicationCount" -> "2")
  )
  private def lines(
      c: Verifier.Context,
      r: RuntimeResult,
      kept: ClaimDraft,
      summary: String,
      uncertainty: Vector[String]
  ) =
    Verifier.verify(
      SynthesisOutput(RuntimeSchemas.SynthesisOutput, summary, Vector(kept), uncertainty),
      c,
      "qry-x",
      r.answer.response.provenance
    )

  test("N1 (ruling 29): number words are written as digits before binding") {
    import Verifier.wordsToDigits as w
    assert(
      w("three joint publications, twenty-one papers, ninety nine works") ==
        "3 joint publications, 21 papers, 99 works"
    )
    assert(w("zero, eleven, nineteen, twenty, forty, ninety") == "0, 11, 19, 20, 40, 90")
    assert(w("a single paper, coauthored once, cited twice") == "1 paper, coauthored 1, cited 2")
    assert(w("ranks first; second; Tenth") == "ranks 1st; 2nd; 10th")
    assert(w("never coauthored; have not collaborated; none") == "0 joint; have 0 joint; 0")
    assert(w("no joint publications; no coauthorship edge") == "0 joint publications; 0 joint")
    // Not numbers: articles, time markers before a year, "one of", paths, other "no" phrases.
    for s <- Vector(
        "a path of a paper",
        "first in 2001 and last in 2002",
        "their first joint publication was in 2001",
        "one of them",
        "no coauthorship path connects them",
        "no stored value",
        "no language model was called"
      )
    do assert(w(s).filter(_.isDigit) == s.filter(_.isDigit), s)
  }

  test("N1 (ruling 29): a wrong count written as words fails; the right word form verifies") {
    context(pairQ) { (c, r) =>
      // Review 2 N1: "three" against a stored 2.
      val three = edgeClaim.copy(text =
        "Ada Lovelace and Grace Hopper have three joint publications in all-years."
      )
      assert(status(three, c) == UNSUPPORTED && failed(three, c) == Set("numbers-in-text"))
      val two =
        edgeClaim.copy(text =
          "Ada Lovelace and Grace Hopper have two joint publications in all-years."
        )
      assert(status(two, c) == VERIFIED)
      val years = edgeClaim.copy(
        text =
          "Ada Lovelace and Grace Hopper have two joint publications in all-years, first in 2001 and last in 2002.",
        values = edgeClaim.values ++ Map(
          "firstCollaborationYear" -> "2001",
          "lastCollaborationYear" -> "2002"
        )
      )
      assert(status(years, c) == VERIFIED)
      assert(status(edgeClaim.copy(text = s"${two.text} They share a strong tie."), c) == VERIFIED)
      // Review 2 N1: "never coauthored" against a stored 2.
      val never =
        edgeClaim.copy(text = "Ada Lovelace and Grace Hopper never coauthored in all-years.")
      assert(status(never, c) == UNSUPPORTED && failed(never, c) == Set("numbers-in-text"))
      assert(status(never.copy(values = Map("jointPublicationCount" -> "0")), c) == CONFLICTING)
      // Review 2 N1: a DESCRIPTIVE claim with a number word is not context only.
      val students = draft(
        ClaimKind.DESCRIPTIVE,
        "Ada Lovelace has three students.",
        Vector(profileEv),
        Map.empty,
        None
      )
      assert(status(students, c) == UNSUPPORTED)
      assert(
        Verifier
          .check(students, c)
          .checks
          .exists(x => x.rule == "context-only" && x.outcome == CheckOutcome.FAIL)
      )
      // Review 2 N1: the summary line with the wrong word count is replaced.
      def say(s: String, u: Vector[String] = Vector.empty) = lines(c, r, edgeClaim, s, u)
      assert(say("They have three joint publications.").summary == edgeClaim.text)
      assert(
        say("They have two joint publications.").summary == "They have two joint publications."
      )
      assert(
        say(edgeClaim.text, Vector("They may have three joint publications.")).uncertainty ==
          Vector("1 drafted uncertainty line(s) were left out because they failed verification.")
      )
    }
    // A zero in words binds to the stored absence of an edge.
    context("Have Ada Lovelace and Edsger Dijkstra coauthored a paper?") { (c, _) =>
      val none = c.bundle.graphEvidence
        .find(e => e.evidenceType == EvidenceType.COAUTHOR_EDGE && e.facts("edgeExists") == "false")
        .get
      val never = draft(
        ClaimKind.COAUTHORSHIP,
        "Ada Lovelace and Edsger Dijkstra never coauthored in all-years.",
        Vector(none.evidenceId),
        Map("edgeExists" -> "false"),
        subjects = Vector(ada, edsger)
      )
      assert(status(never, c) == VERIFIED)
      assert(
        status(
          never.copy(text =
            "Ada Lovelace and Edsger Dijkstra have no joint publications in all-years."
          ),
          c
        ) ==
          VERIFIED
      )
      val one = never.copy(text =
        "Ada Lovelace and Edsger Dijkstra have one joint publication in all-years."
      )
      assert(status(one, c) == UNSUPPORTED)
    }
    // Ordinals are ranks: they bind only to the researcher's stored rank.
    context("Which researchers have the most distinct coauthors?") { (c, _) =>
      val ranking = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.RANKING_RECORD).get
      val k = (1 to 3).find(i => ranking.facts(s"rank.$i.researcherId") == grace).get
      val ordinal = Vector("first", "second", "third")
      val good = draft(
        ClaimKind.RANKING,
        s"Grace Hopper ranks ${ordinal(k - 1)} by distinct coauthors in all-years.",
        Vector(ranking.evidenceId),
        Map(s"rank.$k.storedRank" -> k.toString, s"rank.$k.researcherId" -> grace),
        subjects = Vector(grace)
      )
      assert(status(good, c) == VERIFIED)
      val wrong = good.copy(text = s"Grace Hopper ranks ${ordinal(k % 3)} by distinct coauthors.")
      assert(status(wrong, c) == UNSUPPORTED)
    }
    // "a path" is not a count; "two hops" is bound to the path length.
    context(pathQ) { (c, _) =>
      val p = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
      val path = draft(
        ClaimKind.PATH,
        "There is a path of two hops between Ada Lovelace and Edsger Dijkstra in all-years.",
        Vector(p.evidenceId),
        Map("pathLength" -> "2"),
        subjects = Vector(ada, edsger)
      )
      assert(status(path, c) == VERIFIED)
      assert(status(path.copy(text = path.text.replace("two", "three")), c) == UNSUPPORTED)
    }
  }

  test("N2 (ruling 29): every name in a claim is a researcher of the facts its values assert") {
    context(pathQ) { (c, r) =>
      val p = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
      val hop2 = Verifier.HopFacts.map(f => s"hop.2.$f" -> p.facts(s"hop.2.$f")).toMap
      assert(hop2("hop.2.from") == grace && hop2("hop.2.to") == edsger)
      // Review 2 N2 (a): hop 1's pair with hop 2's facts.
      val a = draft(
        ClaimKind.PATH_HOP,
        "Ada Lovelace and Grace Hopper have 1 joint publication, first in 2012 and last in 2012.",
        Vector(p.evidenceId),
        hop2,
        subjects = Vector.empty
      )
      assert(status(a, c) == UNSUPPORTED && failed(a, c) == Set("identity"))
      assert(
        Verifier
          .check(a, c)
          .checks
          .exists(x => x.rule == "identity" && x.detail.contains("Ada Lovelace"))
      )
      val right = a.copy(text =
        "Grace Hopper and Edsger Dijkstra have 1 joint publication, first in 2012 and last in 2012."
      )
      assert(status(right, c) == VERIFIED)
      // Summary and uncertainty lines may name only the kept claims' researchers.
      def say(s: String, u: Vector[String] = Vector.empty) = lines(c, r, right, s, u)
      assert(
        say("Grace Hopper and Edsger Dijkstra have 1 joint publication.").summary.startsWith(
          "Grace"
        )
      )
      assert(say("Ada Lovelace and Grace Hopper have 1 joint publication.").summary == right.text)
      assert(say(right.text, Vector("Ada Lovelace is the source.")).uncertainty.size == 1)
    }
    context(pairQ) { (c, r) =>
      // Edsger Dijkstra is only in Grace Hopper's resolution context (a top coauthor), never in a
      // graph item of this bundle.
      assert(c.researcherNames("Edsger Dijkstra"))
      assert(!c.bundle.graphEvidence.exists(_.facts.values.exists(_ == "Edsger Dijkstra")))
      // Review 2 N2 (b): the Ada and Edsger claim citing the Ada and Grace edge.
      val b = edgeClaim.copy(text =
        "Ada Lovelace and Edsger Dijkstra have 2 joint publications in all-years."
      )
      assert(status(b, c) == UNSUPPORTED && failed(b, c) == Set("identity"))
      assert(
        Verifier
          .check(b, c)
          .checks
          .exists(x => x.rule == "identity" && x.detail.contains("Edsger Dijkstra"))
      )
      // A resolved subject named against another researcher's metric is not allowed either.
      val metric = draft(
        ClaimKind.METRIC_VALUE,
        "In all-years, Ada Lovelace has 2 distinct coauthors.",
        Vector(graceMetric),
        Map("distinctCoauthorCount" -> "2"),
        subjects = Vector(ada, grace)
      )
      assert(status(metric, c) == UNSUPPORTED && failed(metric, c) == Set("identity"))
      assert(
        status(
          metric.copy(text = "In all-years, Grace Hopper has 2 distinct coauthors."),
          c
        ) == VERIFIED
      )
      def say(s: String, u: Vector[String] = Vector.empty) = lines(c, r, edgeClaim, s, u)
      assert(
        say("Ada Lovelace and Edsger Dijkstra have 2 joint publications.").summary == edgeClaim.text
      )
      assert(
        say(edgeClaim.text, Vector("Edsger Dijkstra may be a coauthor.")).uncertainty.size == 1
      )
    }
  }

  /** A FACET_RECORD of Grace Hopper's topics (three entries), added to a context's bundle. */
  private def withFacet(c: Verifier.Context, extra: Map[String, String] = Map.empty) =
    val base = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.METRIC_RECORD).get
    val facet = base.copy(
      evidenceId = ev(EvidenceType.FACET_RECORD, Seq(grace, "TOPIC", "all-years", "n=3"), graphRun),
      evidenceType = EvidenceType.FACET_RECORD,
      subjectIds = Vector(grace, "TOPIC", "all-years", "n=3"),
      facts = Map(
        "subjectId" -> grace,
        "displayName" -> "Grace Hopper",
        "window" -> "all-years",
        "basis" -> "AUTHORED works",
        "truncated" -> "false",
        "entry.1.id" -> "openalex:T1",
        "entry.1.name" -> "Cloud Computing Systems",
        "entry.1.count" -> "41",
        "entry.2.id" -> "openalex:T2",
        "entry.2.name" -> "Fault Tolerance",
        "entry.2.count" -> "12",
        "entry.3.id" -> "openalex:T3",
        "entry.3.name" -> "Compilers",
        "entry.3.count" -> "5"
      ) ++ extra
    )
    (c.copy(bundle = c.bundle.copy(graphEvidence = c.bundle.graphEvidence :+ facet)), facet)

  private def detail(d: ClaimDraft, c: Verifier.Context, rule: String) =
    Verifier.check(d, c).checks.filter(_.rule == rule).map(_.detail).mkString("; ")

  test("R3-1: a list-position ordinal binds to the index k of an asserted hop or entry") {
    context(pathQ) { (c, _) =>
      val p = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
      def hop(k: Int) = Verifier.HopFacts.map(f => s"hop.$k.$f" -> p.facts(s"hop.$k.$f")).toMap
      // Review 3 R3-1 input (B10 shape): the second hop, all five hop 2 facts asserted.
      val second = draft(
        ClaimKind.PATH_HOP,
        "The second hop links Grace Hopper and Edsger Dijkstra with 1 joint publication in 2012.",
        Vector(p.evidenceId),
        hop(2),
        subjects = Vector.empty
      )
      assert(status(second, c) == VERIFIED, Verifier.check(second, c))
      // The wrong position fails as a position, never as an unbound rank.
      val wrong = second.copy(text = second.text.replace("second", "third"))
      assert(status(wrong, c) == UNSUPPORTED && failed(wrong, c) == Set("numbers-in-text"))
      assert(detail(wrong, c, "numbers-in-text").contains("position k"))
      assert(!detail(wrong, c, "numbers-in-text").contains("rank"))
      val first = draft(
        ClaimKind.PATH_HOP,
        "The first hop links Ada Lovelace and Grace Hopper with two joint publications, first in 2001 and last in 2002.",
        Vector(p.evidenceId),
        hop(1),
        subjects = Vector.empty
      )
      assert(status(first, c) == VERIFIED, Verifier.check(first, c))
      // Both hops in one claim: either position may be written.
      val both = first.copy(
        text = "The first hop has 2 joint publications and the second hop has 1 joint publication.",
        values = hop(1) ++ hop(2)
      )
      assert(status(both, c) == VERIFIED, Verifier.check(both, c))
    }
    context(pairQ) { (c0, _) =>
      val (c, facet) = withFacet(c0)
      // Review 3 R3-1 input (B25 shape): the second most common topic, entry.2 asserted.
      val topic = draft(
        ClaimKind.FACET,
        "Fault Tolerance is Grace Hopper's second most common topic with 12 papers in all-years.",
        Vector(facet.evidenceId),
        Map("entry.2.name" -> "Fault Tolerance", "entry.2.count" -> "12"),
        subjects = Vector(grace)
      )
      assert(status(topic, c) == VERIFIED, Verifier.check(topic, c))
      assert(status(topic.copy(text = topic.text.replace("second", "third")), c) == UNSUPPORTED)
      assert(
        status(topic.copy(text = topic.text.replace("second most", "fiftieth most")), c) ==
          UNSUPPORTED
      )
      // A year range after "first" (blanked before the conversion in verifier-v3) is a time marker.
      val years = edgeClaim.copy(
        text =
          "Ada Lovelace and Grace Hopper first collaborated in 2001-2002 and have 2 joint publications in all-years.",
        values = edgeClaim.values ++ Map(
          "firstCollaborationYear" -> "2001",
          "lastCollaborationYear" -> "2002"
        )
      )
      assert(status(years, c) == VERIFIED, Verifier.check(years, c))
    }
  }

  test("R3-1: an ordering word is no count, and an ordinal outside a ranking is no rank") {
    import Verifier.wordsToDigits as w
    assert(w("First, they met. Second, they wrote; Third, done.").forall(!_.isDigit))
    assert(w("Firstly, x.\nSecondly, y.").forall(!_.isDigit))
    // Mid-sentence ordinals are still positions or ranks.
    assert(w("the first, second, and third hops") == "the 1st, 2nd, and 3rd hops")
    context(pairQ) { (c, r) =>
      val kept = edgeClaim
      val v = lines(
        c,
        r,
        kept,
        "First, Ada Lovelace and Grace Hopper have 2 joint publications.",
        Vector.empty
      )
      assert(v.summary == "First, Ada Lovelace and Grace Hopper have 2 joint publications.")
    }
  }

  test("R3-2: the measure name 'on one paper' and fixed phrases are not counts") {
    import Verifier.wordsToDigits as w
    assert(w("98 authors on one paper") == "98 authors on a paper")
    assert(w("286 coauthors on a single paper") == "286 coauthors on a paper")
    assert(w("the largest team on one paper") == "the largest team on a paper")
    assert(w("once again; twice as many coauthors").forall(!_.isDigit))
    // Outside the measure name, the same words still count.
    assert(w("they collaborated on one paper") == "they collaborated on 1 paper")
    assert(w("they wrote a single paper together") == "they wrote 1 paper together")
    context(pairQ) { (c, _) =>
      // Review 3 R3-2 input (B05 shape): maximumTeamSize with "authors on one paper".
      val team = draft(
        ClaimKind.METRIC_VALUE,
        "Ada Lovelace's largest team had 2 authors on one paper in all-years.",
        Vector(adaMetric),
        Map("maximumTeamSize" -> "2")
      )
      assert(status(team, c) == VERIFIED, Verifier.check(team, c))
      assert(
        status(team.copy(text = team.text.replace("one paper", "a single paper")), c) == VERIFIED
      )
      assert(
        status(team.copy(text = team.text.replace("2 authors", "3 authors")), c) == UNSUPPORTED
      )
    }
    context("Which researchers have the most distinct coauthors?") { (c, _) =>
      // Review 3 R3-2 input (B04 shape): a correct ranking claim with "coauthors on one paper".
      val ranking = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.RANKING_RECORD).get
      assert(ranking.facts("rank.1.researcherId") == grace && ranking.facts("rank.1.value") == "2")
      val b04 = draft(
        ClaimKind.RANKING,
        "Grace Hopper ranks first with 2 distinct coauthors on one paper in all-years.",
        Vector(ranking.evidenceId),
        Map("rank.1.researcherId" -> grace, "rank.1.storedRank" -> "1", "rank.1.value" -> "2"),
        subjects = Vector(grace)
      )
      assert(status(b04, c) == VERIFIED, Verifier.check(b04, c))
      val again = b04.copy(text = "Once again, Grace Hopper ranks first with 2 distinct coauthors.")
      assert(status(again, c) == VERIFIED, Verifier.check(again, c))
    }
  }

  test(
    "n4 and review 2 n2: a rank is checked against a stored rank, never a year or a listed value"
  ) {
    context(pairQ) { (c, r) =>
      val kept = edgeClaim.copy(
        text =
          "Ada Lovelace and Grace Hopper have 2 joint publications in all-years, first in 2001.",
        values = edgeClaim.values ++ Map("firstCollaborationYear" -> "2001")
      )
      assert(status(kept, c) == VERIFIED)
      def say(s: String) = lines(c, r, kept, s, Vector.empty).summary
      // Review 3 n4 input: a single year after the ordinal no longer hides the rank.
      assert(say("Grace Hopper ranks second since 2001.") == kept.text)
      assert(say("Grace Hopper is second since 2001.") == kept.text)
      // A measure word nearer the year binds the year as a year; the ordinal must still be a rank.
      assert(say("Grace Hopper ranks second for joint publications since 2001.") == kept.text)
      assert(say("Grace Hopper is second for joint publications since 2001.") == kept.text)
      assert(say("Grace Hopper ranks first for joint publications since 2001.") == kept.text)
      // "first" before a year without a rank word stays a time marker.
      val time = "Ada Lovelace and Grace Hopper first coauthored in 2001."
      assert(say(time) == time)
    }
    context("Which researchers have the most distinct coauthors?") { (c, _) =>
      val ranking = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.RANKING_RECORD).get
      // Review 2 n2 input: rank 1's value is 2, so "2nd" equals a listed value but is not a rank.
      val n2 = draft(
        ClaimKind.RANKING,
        "Grace Hopper ranks 2nd by distinct coauthors in all-years.",
        Vector(ranking.evidenceId),
        Map("rank.1.value" -> "2", "rank.1.researcherId" -> grace),
        subjects = Vector(grace)
      )
      assert(status(n2, c) == UNSUPPORTED && failed(n2, c) == Set("numbers-in-text"))
      assert(
        status(
          n2.copy(text = "Grace Hopper ranks 2 by distinct coauthors in all-years."),
          c
        ) == UNSUPPORTED
      )
      assert(
        status(
          n2.copy(text = "Grace Hopper is #2 by distinct coauthors in all-years."),
          c
        ) == UNSUPPORTED
      )
      // The value written as a value still verifies.
      val value = n2.copy(text = "Grace Hopper has 2 distinct coauthors in all-years.")
      assert(status(value, c) == VERIFIED, Verifier.check(value, c))
    }
  }

  test("n5: ordinals up to fiftieth (and beyond) are checked") {
    import Verifier.wordsToDigits as w
    assert(
      w(
        "eleventh, twelfth, nineteenth, twentieth, twenty-first, forty-ninth, fiftieth, sixtieth"
      ) ==
        "11th, 12th, 19th, 20th, 21st, 49th, 50th, 60th"
    )
    context("Which researchers have the most distinct coauthors?") { (c, _) =>
      val ranking = c.bundle.graphEvidence.find(_.evidenceType == EvidenceType.RANKING_RECORD).get
      val k = (1 to 3).find(i => ranking.facts(s"rank.$i.researcherId") == grace).get
      val d = draft(
        ClaimKind.RANKING,
        "Grace Hopper ranks twelfth by distinct coauthors in all-years.",
        Vector(ranking.evidenceId),
        Map(s"rank.$k.storedRank" -> k.toString, s"rank.$k.researcherId" -> grace),
        subjects = Vector(grace)
      )
      assert(status(d, c) == UNSUPPORTED && failed(d, c) == Set("numbers-in-text"))
    }
  }

  test(
    "n6: the prompt's measure names verify: coauthors per paper, intensity in joint publications"
  ) {
    context(pairQ) { (c, _) =>
      val avg = draft(
        ClaimKind.METRIC_VALUE,
        "In all-years, Ada Lovelace has an average of 0.3 coauthors per paper.",
        Vector(adaMetric),
        Map("averageCoauthorsPerPaper" -> "0.3")
      )
      assert(status(avg, c) == VERIFIED, Verifier.check(avg, c))
      val intensity = draft(
        ClaimKind.METRIC_VALUE,
        "In all-years, Grace Hopper has a collaboration intensity of 3 joint publications.",
        Vector(graceMetric),
        Map("weightedDegree" -> "3"),
        subjects = Vector(grace)
      )
      assert(status(intensity, c) == VERIFIED, Verifier.check(intensity, c))
      // Another measure's name still fails.
      assert(
        status(
          avg.copy(text = "In all-years, Ada Lovelace has 0.3 publications."),
          c
        ) == UNSUPPORTED
      )
    }
  }

  test("review 2 n3: a name inside a longer bundle text, or cut by a one-word text, is flagged") {
    context(pairQ) { (c0, _) =>
      val (c, _) = withFacet(
        c0,
        Map("entry.4.name" -> "Barbara Liskov Systems Group", "entry.5.name" -> "Turing")
      )
      // Barbara Liskov is a corpus researcher outside this bundle; only a longer text holds her name.
      assert(!c.researcherNames("Barbara Liskov"))
      assert(
        Verifier.unknownNames("Ada Lovelace met Barbara Liskov.", c).map(_._1) == Vector(
          "Barbara Liskov"
        )
      )
      // "Turing" alone is a bundle text; it no longer cuts "Alan Turing" to one word.
      assert(
        Verifier.unknownNames("Ada Lovelace met Alan Turing.", c).map(_._1) == Vector("Alan Turing")
      )
      // Whole bundle texts still pass.
      assert(Verifier.unknownNames("Barbara Liskov Systems Group studies Turing.", c).isEmpty)
      assert(Verifier.unknownNames("Fault Tolerance and Cloud Computing Systems.", c).isEmpty)
    }
  }

  test("n7: the audit records removed claims and left-out lines with their reasons") {
    val bad = edgeClaim.copy(
      claimId = "claim-1",
      text = "Ada Lovelace and Grace Hopper have three joint publications in all-years."
    )
    val good = edgeClaim.copy(claimId = "claim-2")
    val synth = SynthesisOutput(
      RuntimeSchemas.SynthesisOutput,
      "They have 5 joint publications.",
      Vector(bad, good),
      Vector("Alan Turing may be a coauthor.", "The window is all-years.")
    )
    val llm = RecordedLlm(Vector(Right("{}"), Right("{}"), Right(write(synth))))
    withWorld(Some(llm)) { w =>
      val r = ok(w.ask(pairQ))
      val a = r.audit
      assert(a.schemaVersion == "rag-audit-v3")
      assert(!RuntimeApi.llmStubbed(a)) // a model client was given
      assert(
        a.removedClaims.map(x => (x.claimId, x.text, x.status)) == Vector(
          ("claim-1", bad.text, UNSUPPORTED)
        )
      )
      assert(a.removedClaims.head.failedChecks.map(_.rule) == Vector("numbers-in-text"))
      assert(
        a.droppedLines.map(x => (x.part, x.text)) ==
          Vector(
            LinePart.SUMMARY -> synth.summary,
            LinePart.UNCERTAINTY -> "Alan Turing may be a coauthor."
          )
      )
      assert(a.droppedLines(0).reasons.exists(_.contains("5 is not an asserted fact")))
      assert(a.droppedLines(1).reasons.exists(_.contains("Alan Turing")))
      // The written audit object holds them; the answer does not; the service view drops the text.
      val stored = RuntimeApi.audit(a.queryId, w.setup, w.audit.reader).fold(fail(_), identity).get
      assert(stored == a)
      assert(!write(r.answer).contains("three joint publications"))
      val view = RuntimeApi.withoutModelText(a)
      assert(view.removedClaims.forall(_.text.isEmpty) && view.droppedLines.forall(_.text.isEmpty))
      assert(view.removedClaims.head.failedChecks == a.removedClaims.head.failedChecks)
    }
  }

  test("ruling 29: the runtime run ID changes with the verifier version") {
    withWorld() { w =>
      val id = Workflow.runIdFor(config, Seq("g"), w.prompts, RuntimeMode.FAKE)
      assert(
        id == Workflow.runIdFor(config, Seq("g"), w.prompts, RuntimeMode.FAKE, Verifier.Version)
      )
      assert(id != Workflow.runIdFor(config, Seq("g"), w.prompts, RuntimeMode.FAKE, "verifier-v2"))
    }
  }

  test(
    "ruling 20: summary and uncertainty lines get the numbers, windows, names, and titles checks"
  ) {
    context(pairQ) { (c, r) =>
      val good = ClaimDraft(
        "claim-1",
        ClaimKind.COAUTHORSHIP,
        "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
        Vector(edgeEv),
        Vector(ada, grace),
        Some("all-years"),
        Map("jointPublicationCount" -> "2")
      )
      def verify(summary: String, lines: Vector[String] = Vector.empty) =
        Verifier.verify(
          SynthesisOutput(RuntimeSchemas.SynthesisOutput, summary, Vector(good), lines),
          c.copy(notes =
            Vector(
              "Requested 2005-2010; the stored window 2000-2014 (2000-2014) is the smallest that contains it."
            )
          ),
          "qry-x",
          r.answer.response.provenance
        )
      val kept = "They have 2 joint publications in all-years."
      assert(verify(kept).summary == kept)
      for bad <- Vector(
          "They have 3 joint publications in all-years.", // a number no kept claim asserts
          "They have 2 joint publications in 2015-2026.", // another window
          "Ada Lovelace and Alan Turing have 2 joint publications.", // a name outside the bundle
          "They wrote \"A Paper That Does Not Exist\"." // a title not cited
        )
      do assert(verify(bad).summary == good.text, bad)
      // Uncertainty: the fixed note may be restated; another window or number may not.
      val v = verify(
        kept,
        Vector(
          "Requested 2005-2010 maps to the stored window 2000-2014.",
          "Values cover 2015-2026.",
          "There may be 7 more papers."
        )
      )
      assert(v.uncertainty.head == "Requested 2005-2010 maps to the stored window 2000-2014.")
      assert(
        v.uncertainty.tail == Vector(
          "2 drafted uncertainty line(s) were left out because they failed verification."
        )
      )
    }
  }

  test("work order 9, live B18 shape: a number written with digit-group commas is one number") {
    assert(Verifier.ungroup("12,345 and 1,234,567.") == "12345 and 1234567.")
    // Only exact groups of three digits: a year list or a short group stays apart.
    assert(Verifier.ungroup("2001,2002 and 1,23 and 12,3456") == "2001,2002 and 1,23 and 12,3456")
    context(pairQ) { (c0, _) =>
      // Ada's metric item with a component size of 12345 (the failure shape: a COMPONENT claim
      // citing a metric item, its componentSize written with a comma).
      val b = c0.bundle
      val c = c0.copy(bundle =
        b.copy(graphEvidence =
          b.graphEvidence.map(e =>
            if e.evidenceId == adaMetric then e.copy(facts = e.facts + ("componentSize" -> "12345"))
            else e
          )
        )
      )
      val size = Map("componentSize" -> "12345")
      val claim = draft(
        ClaimKind.COMPONENT,
        "In all-years, Ada Lovelace is in a connected component of 12,345 researchers.",
        Vector(adaMetric),
        size
      )
      assert(status(claim, c) == VERIFIED, Verifier.check(claim, c).checks)
      // A wrong grouped number, or a malformed group, still fails.
      val wrong = claim.copy(text =
        "In all-years, Ada Lovelace is in a connected component of 12,346 researchers."
      )
      assert(status(wrong, c) == UNSUPPORTED)
      assert(
        Verifier
          .check(wrong, c)
          .checks
          .exists(x => x.rule == "numbers-in-text" && x.detail.contains("12346 is not"))
      )
      val broken = claim.copy(text =
        "In all-years, Ada Lovelace is in a connected component of 1,2345 researchers."
      )
      assert(status(broken, c) == UNSUPPORTED)
    }
  }

  test(
    "work order 9, live B22 shape: a claim on one side of a stored comparison may take that side's window"
  ) {
    context(pairQ) { (c0, r) =>
      val changeEv =
        ev(EvidenceType.TEMPORAL_CHANGE, Seq(grace, "CHANGE#2000-2014>2015-2026"), metricRun)
      val change = scholarchain.core.EvidenceItem(
        scholarchain.core.SchemaVersions.EvidenceItem,
        changeEv,
        EvidenceType.TEMPORAL_CHANGE,
        Vector(grace),
        None,
        s"dynamodb:ResearcherMetricCache/$grace/CHANGE#2000-2014>2015-2026",
        None,
        None,
        None,
        None,
        None,
        Map(
          "window" -> "2000-2014>2015-2026",
          "fromWindow" -> "2000-2014",
          "toWindow" -> "2015-2026",
          "researcherId" -> grace,
          "displayName" -> "Grace Hopper",
          "fromDistinctCoauthorCount" -> "2",
          "toDistinctCoauthorCount" -> "3",
          "deltaDistinctCoauthorCount" -> "1"
        ),
        r.answer.response.provenance
      )
      val c = c0.copy(bundle = c0.bundle.copy(graphEvidence = c0.bundle.graphEvidence :+ change))
      def d(text: String, window: String, values: Map[String, String]) =
        draft(ClaimKind.METRIC_VALUE, text, Vector(changeEv), values, Some(window), Vector(grace))
      val to = Map("toDistinctCoauthorCount" -> "3")
      val from = Map("fromDistinctCoauthorCount" -> "2")
      // The to side in the to window, and the from side in the from window, verify.
      val toClaim = d("In 2015-2026, Grace Hopper had 3 distinct coauthors.", "2015-2026", to)
      assert(status(toClaim, c) == VERIFIED, Verifier.check(toClaim, c).checks)
      assert(
        status(d("In 2000-2014, Grace Hopper had 2 distinct coauthors.", "2000-2014", from), c) ==
          VERIFIED
      )
      // The other side's window, or a delta (a fact of the pair), keeps the comparison window only.
      assert(
        status(d("In 2000-2014, Grace Hopper had 3 distinct coauthors.", "2000-2014", to), c) ==
          CONFLICTING
      )
      assert(
        status(
          d(
            "In 2015-2026, Grace Hopper had 3 distinct coauthors, 1 more.",
            "2015-2026",
            to + ("deltaDistinctCoauthorCount" -> "1")
          ),
          c
        ) == CONFLICTING
      )
      assert(
        status(
          d("In 2015-2026, Grace Hopper had 3 distinct coauthors.", "2015-2026", to ++ from),
          c
        ) == CONFLICTING
      )
      // The comparison window still verifies a claim on both sides.
      assert(
        status(
          d(
            "Grace Hopper's distinct coauthors went from 2 in 2000-2014 to 3 in 2015-2026.",
            "2000-2014>2015-2026",
            to ++ from
          ),
          c
        ) == VERIFIED
      )
    }
  }

  test("Phase 11 work order 2, live B23 shape: totalWorks is named as the works of the name") {
    context(pairQ) { (c0, _) =>
      // The failure shape: the identity item of a name shared by several researchers, where the
      // resolved researcher has fewer works than all researchers with the name together.
      val b = c0.bundle
      val id = b.graphEvidence
        .find(e =>
          e.evidenceType == EvidenceType.IDENTITY_RESOLUTION &&
            e.facts.get("displayName").contains("Ada Lovelace")
        )
        .get
      val c = c0.copy(bundle =
        b.copy(graphEvidence =
          b.graphEvidence.map(e =>
            if e.evidenceId == id.evidenceId then
              e.copy(facts = e.facts + ("works" -> "40") + ("totalWorks" -> "52"))
            else e
          )
        )
      )
      def d(text: String, values: Map[String, String]) =
        draft(ClaimKind.IDENTITY, text, Vector(id.evidenceId), values, None, id.subjectIds)
      val both = Map("works" -> "40", "totalWorks" -> "52")
      // The template wording verifies.
      val template =
        d("Ada Lovelace: 40 of 52 works among researchers with this display name.", both)
      assert(status(template, c) == VERIFIED, Verifier.check(template, c).checks)
      // The total of every researcher with the name is never the researcher's own count.
      for text <- Vector(
          "Ada Lovelace is a researcher with 52 total works.",
          "Ada Lovelace has 52 works.",
          "Ada Lovelace has 52 publications."
        )
      do
        val k = Verifier.check(d(text, Map("totalWorks" -> "52")), c)
        assert(k.status == UNSUPPORTED, text)
        assert(k.checks.exists(x => x.rule == "numbers-in-text" && x.detail.contains("totalWorks")))
    }
  }

  test("N5 (Phase 10 review 2): fixed notes are read with number words before year ranges") {
    context(pairQ) { (c, r) =>
      val good = ClaimDraft(
        "claim-1",
        ClaimKind.COAUTHORSHIP,
        "Ada Lovelace and Grace Hopper have 2 joint publications in all-years.",
        Vector(edgeEv),
        Vector(ada, grace),
        Some("all-years"),
        Map("jointPublicationCount" -> "2")
      )
      // "first" before a year range is a time marker, not 1st: the note frees no number 1.
      val note = "Requested 2005-2010; the first stored window 2000-2014 contains it."
      val v = Verifier.verify(
        SynthesisOutput(
          RuntimeSchemas.SynthesisOutput,
          good.text,
          Vector(good),
          Vector("Requested 2005-2010 maps to 2000-2014.", "There may be 1 more paper.")
        ),
        c.copy(notes = Vector(note)),
        "qry-x",
        r.answer.response.provenance
      )
      assert(v.uncertainty.head == "Requested 2005-2010 maps to 2000-2014.", v.uncertainty)
      assert(v.dropped.map(_.text) == Vector("There may be 1 more paper."), v.dropped)
    }
  }

  // ---- Phase 11 repair-01 (D1 to D3; inputs of the WO3 adversarial tests, fixture names) ----

  private def adaClaim(text: String, values: Map[String, String]) =
    draft(ClaimKind.METRIC_VALUE, text, Vector(adaMetric), values)
  private val pubs = Map("publicationCount" -> "10")
  private val coauthors = Map("distinctCoauthorCount" -> "1")
  private def removed(c: Verifier.Context, cases: Vector[(String, Map[String, String])]) =
    cases.foreach { (t, v) =>
      val k = Verifier.check(adaClaim(t, v), c)
      assert(k.status == UNSUPPORTED, t)
      assert(k.checks.exists(x => x.rule == "numbers-in-text" && x.outcome == CheckOutcome.FAIL), t)
    }

  test("repair-01 D1: a quantity in words (hundreds of, dozens of, a dozen) is not verified") {
    context(pairQ) { (c, _) =>
      assert(
        status(adaClaim("In all-years, Ada Lovelace has 10 publications.", pubs), c) == VERIFIED
      )
      removed(
        c,
        Vector(
          "In all-years, Ada Lovelace has hundreds of publications." -> pubs,
          "In all-years, Ada Lovelace has dozens of publications." -> pubs,
          "In all-years, Ada Lovelace has scores of publications." -> pubs,
          "In all-years, Ada Lovelace has a dozen distinct coauthors." -> coauthors,
          "In all-years, Ada Lovelace has several publications." -> pubs,
          "In all-years, Ada Lovelace has ten thousand publications." -> pubs
        )
      )
    }
  }

  test("repair-01 D2: a qualified number (more than 10, about 10, 10 or more) is not verified") {
    context(pairQ) { (c, _) =>
      removed(
        c,
        Vector(
          "In all-years, Ada Lovelace has more than 10 publications.",
          "In all-years, Ada Lovelace has fewer than 10 publications.",
          "In all-years, Ada Lovelace has over 10 publications.",
          "In all-years, Ada Lovelace has under 10 publications.",
          "In all-years, Ada Lovelace has at least 10 publications.",
          "In all-years, Ada Lovelace has at most 10 publications.",
          "In all-years, Ada Lovelace has nearly 10 publications.",
          "In all-years, Ada Lovelace has about ten publications.",
          "In all-years, Ada Lovelace has approximately 10 publications.",
          "In all-years, Ada Lovelace has 10 or more publications.",
          "In all-years, Ada Lovelace has 10+ publications."
        ).map(_ -> pubs)
      )
      // "1 coauthor over 10 publications" is a ratio, not "more than 10".
      val both = pubs ++ coauthors
      assert(
        status(
          adaClaim(
            "In all-years, Ada Lovelace has 1 distinct coauthor over 10 publications.",
            both
          ),
          c
        ) ==
          VERIFIED
      )
    }
  }

  test("repair-01 D3: a negated number (does not have 10, it is false that) is not verified") {
    context(pairQ) { (c, _) =>
      removed(
        c,
        Vector(
          "In all-years, Ada Lovelace does not have 10 publications." -> pubs,
          "In all-years, it is false that Ada Lovelace has 10 publications." -> pubs,
          "In all-years, Ada Lovelace has not 1 distinct coauthor." -> coauthors,
          "In all-years, Ada Lovelace never had 10 publications." -> pubs,
          "In all-years, Ada Lovelace doesn't have 10 publications." -> pubs
        )
      )
    }
  }

  test("repair-01 controls: bound qualifiers and the template's negations still verify") {
    context(pathQ) { (c0, _) =>
      // A path search cut at its depth: "at most 4 hops" states the stored maxDepth exactly.
      val b = c0.bundle
      val path = b.graphEvidence.find(_.evidenceType == EvidenceType.GRAPH_PATH).get
      val cut = path.copy(facts = path.facts + ("status" -> "BEYOND_DEPTH") + ("maxDepth" -> "4"))
      val c = c0.copy(bundle =
        b.copy(graphEvidence = b.graphEvidence.map(e => if e == path then cut else e))
      )
      val v = Map("status" -> "BEYOND_DEPTH", "maxDepth" -> "4")
      def d(text: String) =
        draft(ClaimKind.PATH, text, Vector(cut.evidenceId), v, subjects = path.subjectIds)
      val names = "connects Ada Lovelace and Edsger Dijkstra in all-years."
      val template = d(s"No coauthorship path of at most 4 hops $names")
      assert(status(template, c) == VERIFIED, Verifier.check(template, c).checks)
      assert(status(d(s"No coauthorship path of up to 4 hops $names"), c) == VERIFIED)
      // The bound's own direction only: "at least" of a maximum is not its stored value.
      assert(status(d(s"No coauthorship path of at least 4 hops $names"), c) == UNSUPPORTED)
      assert(status(d(s"No coauthorship path of more than 4 hops $names"), c) == UNSUPPORTED)
    }
    // The template's "which is not the largest connected component" after the size.
    context("Which connected component is Ada Lovelace in?") { (c0, _) =>
      val b = c0.bundle
      val comp = b.graphEvidence.find(_.evidenceType == EvidenceType.COMPONENT_RECORD).get
      val notLargest = comp.copy(facts = comp.facts + ("inLargestComponent" -> "false"))
      val c = c0.copy(bundle =
        b.copy(graphEvidence = b.graphEvidence.map(e => if e == comp then notLargest else e))
      )
      val v = Map(
        "componentId" -> comp.facts("componentId"),
        "componentSize" -> comp.facts("componentSize"),
        "inLargestComponent" -> "false"
      )
      val text = s"In all-years, Ada Lovelace is in connected component ${v("componentId")} of " +
        s"${v("componentSize")} researchers, which is not the largest connected component of that window."
      val k = Verifier.check(
        draft(ClaimKind.COMPONENT, text, Vector(notLargest.evidenceId), v),
        c
      )
      assert(k.status == VERIFIED, k.checks)
    }
    // The fake template's own wordings: "is 25 over 10 publications", "Breadth is not intensity:",
    // "Bridge importance is not breadth:"; every material claim verifies.
    for (q, wording) <- Vector(
        "How many distinct coauthors does Grace Hopper have?" -> "Breadth is not intensity:",
        "Is Grace Hopper a bridge between collaboration communities?" -> "Bridge importance is not"
      )
    do
      withWorld() { w =>
        val a = ok(w.ask(q)).answer
        val texts = a.response.claims.map(c => (c.verificationStatus, c.text))
        assert(texts.exists((s, t) => s == VERIFIED && t.contains(wording)), (q, texts))
        assert(a.verification.counts.unsupported == 0, (q, a.verification))
        assert(a.verification.counts.conflicting == 0, (q, a.verification))
      }
  }

  // ---- Phase 11 repair-02 (D14; the work order 4 failure inputs, then their families) ----

  test("repair-02 D14: 'short of 10', 'falls short of 10', and 'lacks 10' are not verified") {
    context(pairQ) { (c, _) =>
      removed(
        c,
        Vector(
          "In all-years, Ada Lovelace has short of 10 publications.",
          "In all-years, Ada Lovelace falls short of 10 publications.",
          "In all-years, Ada Lovelace lacks 10 publications."
        ).map(_ -> pubs)
      )
    }
  }

  test("repair-02 D14: the comparison families are not verified") {
    context(pairQ) { (c, _) =>
      removed(
        c,
        Vector(
          "has just shy of 10",
          "falls well short of 10",
          "falls below 10",
          "rose above 10",
          "goes beyond 10",
          "has in excess of 10",
          "has upwards of 10",
          "has upward of 10",
          "exceeds 10",
          "exceeded 10",
          "has exceeding 10",
          "surpassed 10",
          "has north of 10",
          "has south of 10",
          "is approaching 10",
          "is nearing 10",
          "has near 10",
          "tops 10",
          "has beneath 10",
          "exceeds a total of 10",
          "has over the 10",
          "has 10-ish",
          "has 10ish"
        ).map(t => s"In all-years, Ada Lovelace $t publications." -> pubs) ++ Vector(
          "In all-years, Ada Lovelace's publications reach 10 and counting." -> pubs,
          "In all-years, Ada Lovelace's publications number 10 at a minimum." -> pubs
        )
      )
    }
  }

  test("repair-02 D14: the negation families (lack, without, absent, fail to) are not verified") {
    context(pairQ) { (c, _) =>
      removed(
        c,
        Vector(
          "In all-years, Ada Lovelace lacked 10 publications." -> pubs,
          "In all-years, Ada Lovelace is lacking 10 publications." -> pubs,
          "In all-years, Ada Lovelace's record lacks a total of 10 publications." -> pubs,
          "In all-years, Ada Lovelace is without 10 publications." -> pubs,
          "In all-years, Ada Lovelace is absent from 10 publications." -> pubs,
          "In all-years, Ada Lovelace fails to reach 10 publications." -> pubs,
          "In all-years, Ada Lovelace hardly has 10 publications." -> pubs,
          "In all-years, Ada Lovelace barely has 10 publications." -> pubs,
          "In all-years, it is denied that Ada Lovelace has 10 publications." -> pubs,
          "In all-years, Ada Lovelace lacks 1 distinct coauthor." -> coauthors,
          // A parenthetical without a measure name still negates the number after it.
          "In all-years, Ada Lovelace has (not) 10 publications." -> pubs
        )
      )
    }
  }

  test(
    "repair-02 controls: exact statements, ratios, and an aside defining a measure still verify"
  ) {
    context(pairQ) { (c, _) =>
      val both = pubs ++ coauthors
      Vector(
        "In all-years, Ada Lovelace has 10 publications." -> pubs,
        "In all-years, Ada Lovelace has exactly 10 publications." -> pubs,
        "In all-years, Ada Lovelace has just 10 publications." -> pubs,
        "In all-years, Ada Lovelace has 1 distinct coauthor over 10 publications." -> both,
        "In all-years, Ada Lovelace has 1 distinct coauthor over the 10 publications." -> both,
        // A negation inside an aside that names a measure is about that noun, not the number.
        "In all-years, Ada Lovelace (whose works lack any retraction) has 10 publications." -> pubs
      ).foreach((t, v) =>
        assert(status(adaClaim(t, v), c) == VERIFIED, Verifier.check(adaClaim(t, v), c).checks)
      )
    }
  }
