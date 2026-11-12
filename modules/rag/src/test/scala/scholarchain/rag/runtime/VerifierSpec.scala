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
