package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import scholarchain.core.{
  EvidenceType,
  Provenance,
  SchemaVersions,
  SourceType,
  StableIds,
  VerificationStatus
}
import scholarchain.rag.runtime.*
import scholarchain.services.{Backend, Launcher}
import scholarchain.storage.cli.Cli
import scholarchain.storage.{
  FileObjectStore,
  S3Layout,
  WindowSummaries,
  WindowSummary,
  WindowSummarySourceRuns
}
import scholarchain.tests.support.StoreWorld.*

import java.nio.file.Files
import java.time.Instant

/** Phase 11 WO3, spec category "unsupported answer claims". Each claim below asserts a stored fact
  * with its correct value in `values`, but its text states something the evidence does not support.
  * The verifier (verifier-v7 since repair-01, verifier-v8 since repair-02; RUNTIME.md section 12)
  * must not keep such a claim as VERIFIED. Positive controls show the same claim verifies when the
  * text matches the fact. Work order 5 adds the verifier-v8 breadth cases and controls, including
  * the corpus template wordings through the services composition (window-summary object, storage
  * validation, rag runtime).
  */
class UnsupportedClaimAdversarialSpec extends AnyFunSuite:
  import VerificationStatus.*

  private val PairQ = "Have Ada Lovelace and Grace Hopper coauthored a paper?"
  private val adaMetric =
    StableIds.evidenceId(EvidenceType.METRIC_RECORD, Seq(ada, "WINDOW#all-years"), metricRun)

  private def withContext(body: Verifier.Context => Unit): Unit =
    withWorld() { w =>
      val r = w.ask(PairQ).fold(f => fail(s"$f"), identity)
      val b = r.bundle.get
      assert(
        b.graphEvidence.exists(_.evidenceId == adaMetric),
        "fixture: ada's metric item missing"
      )
      body(Verifier.Context(b, r.answer.resolutions, w.cfg))
    }

  private def metric(text: String, values: Map[String, String]) =
    ClaimDraft(
      "claim-1",
      ClaimKind.METRIC_VALUE,
      text,
      Vector(adaMetric),
      Vector(ada),
      Some("all-years"),
      values
    )
  private val pubs = Map("publicationCount" -> "10")
  private val coauthors = Map("distinctCoauthorCount" -> "1")
  private def status(d: ClaimDraft, c: Verifier.Context) = Verifier.check(d, c).status

  test(
    "controls: the stored values verify; a wrong digit, a fabricated ID, or a semantic-only claim do not"
  ) {
    withContext { c =>
      assert(status(metric("In all-years, Ada Lovelace has 10 publications.", pubs), c) == VERIFIED)
      assert(
        status(
          metric("In all-years, Ada Lovelace has 1 distinct coauthor.", coauthors),
          c
        ) == VERIFIED
      )
      assert(status(metric("In all-years, Ada Lovelace has 11 publications.", pubs), c) != VERIFIED)
      assert(
        status(
          metric(
            "In all-years, Ada Lovelace has 10 publications.",
            Map("publicationCount" -> "11")
          ),
          c
        ) == CONFLICTING
      )
      val forged = metric("In all-years, Ada Lovelace has 10 publications.", pubs)
        .copy(evidenceIds = Vector("ev-" + "f" * 32))
      assert(status(forged, c) != VERIFIED)
      val profile = StableIds.evidenceId(EvidenceType.PROFILE, Seq(profileChunk.key), renderRun)
      assert(
        status(
          metric("In all-years, Ada Lovelace has 10 publications.", pubs)
            .copy(evidenceIds = Vector(profile)),
          c
        ) != VERIFIED
      )
      // Another researcher's name on Ada's fact.
      assert(status(metric("In all-years, Grace Hopper has 10 publications.", pubs), c) != VERIFIED)
      // A window the cited item is not in.
      assert(status(metric("In 2000-2014, Ada Lovelace has 10 publications.", pubs), c) != VERIFIED)
    }
  }

  test("a multiplier word after the stored digits (10 hundred, 10 thousand) is not that number") {
    withContext { c =>
      Vector(
        "In all-years, Ada Lovelace has 10 hundred publications.",
        "In all-years, Ada Lovelace has ten thousand publications.",
        "In all-years, Ada Lovelace has 10 million publications."
      ).foreach(t => assert(status(metric(t, pubs), c) != VERIFIED, t))
    }
  }

  /** The texts the verifier keeps as VERIFIED; each one is an unsupported claim kept. */
  private def keptOf(c: Verifier.Context, cases: Vector[(String, Map[String, String])]) =
    cases.filter((t, v) => status(metric(t, v), c) == VERIFIED).map(_._1)

  test("a quantity stated only in words (hundreds of, dozens of, a dozen) is not verified") {
    withContext { c =>
      val kept = keptOf(
        c,
        Vector(
          "In all-years, Ada Lovelace has hundreds of publications." -> pubs,
          "In all-years, Ada Lovelace has dozens of publications." -> pubs,
          "In all-years, Ada Lovelace has scores of publications." -> pubs,
          "In all-years, Ada Lovelace has a dozen distinct coauthors." -> coauthors
        )
      )
      assert(kept.isEmpty, s"kept as VERIFIED: $kept")
    }
  }

  test("a comparison the stored value contradicts (more than 10, fewer than 10) is not verified") {
    withContext { c =>
      val kept = keptOf(
        c,
        Vector(
          "In all-years, Ada Lovelace has more than 10 publications." -> pubs,
          "In all-years, Ada Lovelace has fewer than 10 publications." -> pubs,
          "In all-years, Ada Lovelace has over 10 publications." -> pubs,
          "In all-years, Ada Lovelace has under 10 publications." -> pubs
        )
      )
      assert(kept.isEmpty, s"kept as VERIFIED: $kept")
    }
  }

  test("a negated statement of the stored value (does not have 10) is not verified") {
    withContext { c =>
      val kept = keptOf(
        c,
        Vector(
          "In all-years, Ada Lovelace does not have 10 publications." -> pubs,
          "In all-years, it is false that Ada Lovelace has 10 publications." -> pubs,
          "In all-years, Ada Lovelace has not 1 distinct coauthor." -> coauthors
        )
      )
      assert(kept.isEmpty, s"kept as VERIFIED: $kept")
    }
  }

  test(
    "verifier-v7 breadth: other comparison, approximation, vague-quantity, and negation wordings are not verified"
  ) {
    // Work order 4: wordings beyond the D1 to D3 inputs, each false or inexact against a stored 10.
    withContext { c =>
      val kept = keptOf(
        c,
        Vector(
          "has above 10 publications.",
          "has below 10 publications.",
          "has exceeding 10 publications.",
          "has in excess of 10 publications.",
          "has upwards of 10 publications.",
          "has beyond 10 publications.",
          "has well over 10 publications.",
          "has just under 10 publications.",
          "has more than ten publications.",
          "has fewer than ten publications.",
          "has almost 10 publications.",
          "has roughly 10 publications.",
          "has around 10 publications.",
          "has close to 10 publications.",
          "has 10+ publications.",
          "has between 10 and 20 publications.",
          "has 10 to 20 publications.",
          "has never had 10 publications.",
          "has a handful of publications.",
          "has a few publications.",
          "has numerous publications.",
          "has countless publications."
        ).map(t => s"In all-years, Ada Lovelace $t" -> pubs) ++ Vector(
          "It is not the case that Ada Lovelace has 10 publications in all-years." -> pubs,
          "It is untrue that Ada Lovelace has 10 publications in all-years." -> pubs
        )
      )
      assert(kept.isEmpty, s"kept as VERIFIED: $kept")
    }
  }

  test("verifier-v7 controls: exact statements of the stored value still verify") {
    withContext { c =>
      Vector(
        "In all-years, Ada Lovelace has 10 publications.",
        "In all-years, Ada Lovelace has exactly 10 publications.",
        "In all-years, Ada Lovelace has 10 publications in total.",
        "In all-years, Ada Lovelace has ten publications.",
        "In all-years, Ada Lovelace does have 10 publications."
      ).foreach(t => assert(status(metric(t, pubs), c) == VERIFIED, t))
    }
  }

  test(
    "a comparison or negation outside the verifier-v7 word lists (short of 10, lacks 10) is not verified"
  ) {
    // Found in work order 4 (D14). Against a stored 10, "short of 10" and "lacks 10" are false.
    // Fixed in Phase 11 repair-02 (rag-engineer, verifier-v8); work order 5 removed the wrapper.
    withContext { c =>
      val kept = keptOf(
        c,
        Vector(
          "In all-years, Ada Lovelace has short of 10 publications." -> pubs,
          "In all-years, Ada Lovelace falls short of 10 publications." -> pubs,
          "In all-years, Ada Lovelace lacks 10 publications." -> pubs
        )
      )
      assert(kept.isEmpty, s"kept as VERIFIED: $kept")
    }
  }

  // ---- verifier-v8 breadth (Phase 11 work order 5) ---------------------------------------------

  /** Each text must be removed for the stated reason and no other: the claim is UNSUPPORTED and
    * REMOVED, numbers-in-text is its only check that does not pass, and that check's detail names
    * the number and the word ("10 is qualified ('short of')", "10 is negated ('lacks')"). So a case
    * cannot pass because of an unrelated rule (a name, a window, an unbound number).
    */
  private def assertRemovedFor(
      c: Verifier.Context,
      cases: Vector[(String, Map[String, String], String)]
  ): Unit =
    val wrong = cases.flatMap { (t, v, reason) =>
      val k = Verifier.check(metric(t, v), c)
      val notPassed = k.checks.filter(_.outcome != CheckOutcome.PASS)
      val ok = k.status == UNSUPPORTED && k.action == ClaimAction.REMOVED &&
        notPassed.map(x => (x.rule, x.outcome)) == Vector(("numbers-in-text", CheckOutcome.FAIL)) &&
        notPassed.head.detail.contains(reason)
      Option.when(!ok)(
        s"$t -> ${k.status} ${notPassed.map(x => s"${x.rule} ${x.outcome}: ${x.detail}")}" +
          s" (expected only numbers-in-text FAIL with \"$reason\")"
      )
    }
    assert(wrong.isEmpty, wrong.mkString("\n", "\n", ""))

  private def q(n: String, word: String) = s"$n is qualified ('$word')"
  private def neg(n: String, word: String) = s"$n is negated ('$word')"
  private val both = Map("publicationCount" -> "10", "distinctCoauthorCount" -> "1")

  test(
    "verifier-v8 breadth: the comparison words, the filler, and the after-number words repair-02 added are not verified"
  ) {
    // Repair-02 Part A section 1 (Before, Filler, After). Each text is false or inexact against
    // ada's stored publicationCount 10 or distinctCoauthorCount 1.
    withContext { c =>
      val p = "In all-years, Ada Lovelace"
      assertRemovedFor(
        c,
        Vector(
          // Before: comparison words directly before the number.
          (s"$p has short of 10 publications.", pubs, q("10", "short of")),
          (s"$p has just shy of 10 publications.", pubs, q("10", "shy of")),
          (s"$p has upward of 10 publications.", pubs, q("10", "upward of")),
          (s"$p has north of 10 publications.", pubs, q("10", "north of")),
          (s"$p has south of 10 publications.", pubs, q("10", "south of")),
          (s"$p has far from 10 publications.", pubs, q("10", "far from")),
          (s"$p has near 10 publications.", pubs, q("10", "near")),
          (s"$p has well nigh 10 publications.", pubs, q("10", "nigh")),
          (s"$p has approaching 10 publications.", pubs, q("10", "approaching")),
          (s"$p has publications nearing 10.", pubs, q("10", "nearing")),
          (s"$p has publications that exceed 10.", pubs, q("10", "exceed")),
          (s"$p has publications that exceeded 10.", pubs, q("10", "exceeded")),
          (s"$p has publications that surpass 10.", pubs, q("10", "surpass")),
          (s"$p has a publication count that surpasses 10.", pubs, q("10", "surpasses")),
          (s"$p has publications that surpassed 10.", pubs, q("10", "surpassed")),
          (s"$p has publications surpassing 10.", pubs, q("10", "surpassing")),
          (s"$p has publications that outnumber 10.", pubs, q("10", "outnumber")),
          (s"$p has a publication count that outnumbers 10.", pubs, q("10", "outnumbers")),
          (s"$p has publications that outnumbered 10.", pubs, q("10", "outnumbered")),
          (s"$p has publications outnumbering 10.", pubs, q("10", "outnumbering")),
          (s"$p has a publication count that tops 10.", pubs, q("10", "tops")),
          (s"$p has a publication count that topped 10.", pubs, q("10", "topped")),
          (s"$p has a publication count topping 10.", pubs, q("10", "topping")),
          (s"$p has beneath 10 publications.", pubs, q("10", "beneath")),
          // Upper case, and a number written in words.
          // Upper case in one word only: two capitalized words would also be a name-like span.
          (s"$p has SHORT of 10 publications.", pubs, q("10", "SHORT of")),
          (s"$p falls short of ten publications.", pubs, q("10", "short of")),
          (s"$p has north of one distinct coauthor.", coauthors, q("1", "north of")),
          // Filler: "the", "a", "an", "a total of", "a count of" between the word and the number
          // (synthetic texts; grammar aside, the rule is what is tested).
          (s"$p has over the 10 publications.", pubs, q("10", "over the")),
          (s"$p has short of the 10 publications.", pubs, q("10", "short of the")),
          (s"$p has publications that exceed a total of 10.", pubs, q("10", "exceed a total of")),
          (
            s"$p has publications that surpass a count of 10.",
            pubs,
            q("10", "surpass a count of")
          ),
          (s"$p has beyond a 10 publications.", pubs, q("10", "beyond a")),
          (s"$p has upward of an 10 publications.", pubs, q("10", "upward of an")),
          // After: "-ish", "ish", "and counting", "at (a) minimum", "at (a) maximum".
          (s"$p has 10-ish publications.", pubs, q("10", "-ish")),
          (s"$p has 10ish publications.", pubs, q("10", "ish")),
          (s"$p has 10 ish publications.", pubs, q("10", "ish")),
          (s"$p has publications numbering 10 and counting.", pubs, q("10", "and counting")),
          (s"$p has publications numbering 10 at minimum.", pubs, q("10", "at minimum")),
          (s"$p has publications numbering 10 at a minimum.", pubs, q("10", "at a minimum")),
          (s"$p has publications numbering 10 at maximum.", pubs, q("10", "at maximum")),
          (s"$p has publications numbering 10 at a maximum.", pubs, q("10", "at a maximum")),
          (s"$p has 1-ish distinct coauthor.", coauthors, q("1", "-ish"))
        )
      )
    }
  }

  test(
    "verifier-v8 breadth: the negation words repair-02 added, and asides that must not hide them, are not verified"
  ) {
    // Repair-02 Part A section 1 (Negation) and the asidesOut rule: a parenthetical before the
    // number is skipped only when it holds no digit and names a measure.
    withContext { c =>
      val p = "In all-years, Ada Lovelace"
      assertRemovedFor(
        c,
        Vector(
          (s"$p lacks 10 publications.", pubs, neg("10", "lacks")),
          (s"$p lacked 10 publications.", pubs, neg("10", "lacked")),
          (s"$p is lacking 10 publications.", pubs, neg("10", "lacking")),
          (s"$p does lack 10 publications.", pubs, neg("10", "lack")),
          (s"$p LACKS 10 publications.", pubs, neg("10", "LACKS")),
          (s"$p is without 10 publications.", pubs, neg("10", "without")),
          (s"$p is without ten publications.", pubs, neg("10", "without")),
          (
            "In all-years, there is an absence of 10 publications by Ada Lovelace.",
            pubs,
            neg("10", "absence")
          ),
          (s"$p has a record absent 10 publications.", pubs, neg("10", "absent")),
          (s"$p is devoid of 10 publications.", pubs, neg("10", "devoid")),
          (s"$p fails to reach 10 publications.", pubs, neg("10", "fails to")),
          (s"$p failed to reach 10 publications.", pubs, neg("10", "failed to")),
          (s"$p is failing to reach 10 publications.", pubs, neg("10", "failing to")),
          (s"$p did fail to reach 10 publications.", pubs, neg("10", "fail to")),
          (s"$p has hardly 10 publications.", pubs, neg("10", "hardly")),
          (s"$p has scarcely 10 publications.", pubs, neg("10", "scarcely")),
          (s"$p has barely 10 publications.", pubs, neg("10", "barely")),
          (s"$p seldom reached 10 publications.", pubs, neg("10", "seldom")),
          (s"$p rarely reached 10 publications.", pubs, neg("10", "rarely")),
          (
            "In all-years, the records deny that Ada Lovelace has 10 publications.",
            pubs,
            neg("10", "deny")
          ),
          (
            "In all-years, the store denies that Ada Lovelace has 10 publications.",
            pubs,
            neg("10", "denies")
          ),
          (
            "In all-years, it is denied that Ada Lovelace has 10 publications.",
            pubs,
            neg("10", "denied")
          ),
          (
            "In all-years, every researcher except Ada Lovelace has 10 publications.",
            pubs,
            neg("10", "except")
          ),
          (
            "In all-years, excluding Ada Lovelace, each researcher has 10 publications.",
            pubs,
            neg("10", "excluding")
          ),
          (s"$p lacks one distinct coauthor.", coauthors, neg("1", "lacks")),
          (s"$p is without 1 distinct coauthor.", coauthors, neg("1", "without")),
          // Asides: "(not)" names no measure, so it negates; so does an aside with no measure name.
          (s"$p has (not) 10 publications.", pubs, neg("10", "not")),
          (s"$p (reportedly not) has 10 publications.", pubs, neg("10", "not")),
          (s"$p (allegedly lacking) has 10 publications.", pubs, neg("10", "lacking")),
          // An aside naming a measure but holding a digit is kept, so its negation still applies.
          (
            s"$p (not 1 distinct coauthor) has 10 publications.",
            both,
            neg("10", "not")
          ),
          // A negation outside an aside that names a measure is not hidden by the aside.
          (
            s"$p (whose publications are journal papers) lacks 10 publications.",
            pubs,
            neg("10", "lacks")
          )
        )
      )
    }
  }

  test("verifier-v8 controls: exact statements of the stored value still verify") {
    // Each states ada's stored value exactly. The new words appear only where the rules say they do
    // not apply: in another clause, after the number, inside a longer word, or in an aside that
    // names a measure and holds no digit; and the filler words alone, or after a ratio word.
    withContext { c =>
      val p = "In all-years, Ada Lovelace"
      val cases = Vector(
        s"$p has 10 publications." -> pubs,
        s"$p has a total of 10 publications." -> pubs,
        s"$p has a count of 10 publications." -> pubs,
        s"$p has a mere 10 publications." -> pubs,
        s"$p has 10 publications; that is far from nothing." -> pubs,
        s"$p is not without output; she has 10 publications." -> pubs,
        s"$p has 10 publications and is not without coauthors." -> pubs,
        s"$p has 10 publications and has never lacked a coauthor." -> pubs,
        "In all-years, the exceptional Ada Lovelace has 10 publications." -> pubs,
        "In all-years, the unfailing Ada Lovelace has 10 publications." -> pubs,
        s"$p (counting publications without coauthors too) has 10 publications." -> pubs,
        s"$p (whose papers lack no record) has 10 publications." -> pubs,
        s"$p has 1 distinct coauthor over the 10 publications." -> both,
        s"$p has 1 distinct coauthor over a total of 10 publications." -> both,
        s"$p has exactly 1 distinct coauthor." -> coauthors,
        s"$p has one distinct coauthor and lacks any other." -> coauthors
      )
      val notVerified = cases.flatMap { (t, v) =>
        val k = Verifier.check(metric(t, v), c)
        Option.when(k.status != VERIFIED)(
          s"$t -> ${k.status} ${k.checks.filter(_.outcome != CheckOutcome.PASS).map(_.detail)}"
        )
      }
      assert(notVerified.isEmpty, notVerified.mkString("\n", "\n", ""))
    }
  }

  // ---- the corpus template wordings through the services composition ------------------------

  private val NoCoauthorsQ = "How many researchers have no coauthors at all?"
  private val CommunitiesQ =
    "How many collaboration communities of size 3 or more exist in 2000-2014?"
  private val Singletons =
    "In all-years, 28 singleton researchers (researchers without any coauthor edge in that window) " +
      "are among the 1200 researchers of the coauthorship graph."
  private val NotConverged =
    "Community detection in 2000-2014 did not converge within its 10 rounds."

  /** The synthetic corpus window-summary rows (the shape WindowSummaryTamperAdversarialSpec uses):
    * every window has 3 as its community minimum size, did not converge, and stopped after 10
    * rounds.
    */
  private def summaryRows(cfg: ScholarChainConfig, run: String): Vector[WindowSummary] =
    def row(win: String, r: Long, comps: Long, lc: Long, single: Long, comms: Long, lcm: Long)(
        sc: Long,
        atLeast: Long
    ) =
      val tw = cfg.graph.windows.find(_.name == win).get
      WindowSummary(
        win,
        tw.startYear,
        tw.endYear,
        r,
        r * 2,
        r * 3,
        comps,
        lc,
        single,
        comms,
        lcm,
        sc,
        atLeast,
        Some("comp-" + StableIds.hash("component", win)),
        Some(StableIds.communityId("graph-v1", win, tw.startYear, tw.endYear, ada)),
        tw.endYear - tw.startYear + 1,
        Some(lc.toDouble / r.toDouble),
        Some(lcm.toDouble / r.toDouble),
        3,
        false,
        10,
        "researchers with at least one synthetic publication in the window",
        WindowSummarySourceRuns(run, run, run, run, run, "er-v2"),
        Provenance(
          SchemaVersions.Provenance,
          SourceType.DERIVED,
          win,
          snapshot,
          run,
          Some("graph-v1"),
          None
        ),
        WindowSummaries.SchemaVersion
      )
    Vector(
      row("all-years", 1200, 45, 900, 28, 130, 60)(31, 64),
      row("2000-2014", 400, 33, 252, 13, 71, 21)(11, 37),
      row("2015-2026", 800, 22, 541, 9, 90, 40)(12, 47)
    )

  /** The store world plus a window-summary object, served by a backend that Launcher.backend (the
    * services composition root) builds with runtime.window-summary.load-run set, so the object is
    * opened and validated by storage and answered by the rag runtime.
    */
  private def withCorpus(body: (World, Backend, ScholarChainConfig) => Unit): Unit =
    withWorld() { w =>
      val loadRun = StableIds.runId("adv-wo5-load-window-summary", "c", "config-v1", Seq("l"), 1)
      val run = StableIds.runId("adv-wo5-window-summary", "c", "config-v1", Seq("s"), 1)
      val key = S3Layout.windowSummary(snapshot, loadRun)
      FileObjectStore(w.root.resolve("s3")).loader
        .putNew(key, WindowSummaries.render(summaryRows(w.cfg, run)))
        .fold(e => fail(s"fixture: ${e.mkString("; ")}"), _ => ())
      val conf = w.root.resolve("wo5-corpus.conf")
      Files.writeString(conf, s"""scholarchain { runtime.window-summary.load-run = "$loadRun" }""")
      val cfg = ConfigLoader
        .load(baseEnv + ("SCHOLARCHAIN_CONFIG_FILE" -> conf.toString))
        .fold(e => fail(e.mkString("; ")), identity)
      val opened = Vector.newBuilder[String]
      val args = Cli
        .parse(w.args, Set("live"), w.args.collect { case s"--$o" => o }.toSet)
        .fold(e => fail(s"fixture: $e"), identity)
      val b = Launcher
        .backend(args, cfg, line => { opened += line; () })
        .fold(e => fail(e), identity)
      assert(opened.result().contains(s"window summaries: $key"), opened.result())
      body(w, b, cfg)
    }

  private def askCorpus(w: World, b: Backend, question: String) =
    val clock = TickClock()
    val setup = b.setup.copy(nowMs = clock.ms, now = () => Instant.parse("2026-11-06T12:00:00Z"))
    val id = StableIds.queryId(
      b.setup.runId,
      StableIds.textHash(question),
      StableIds.textHash(question + w.root + "corpus"),
      1
    )
    Workflow.answer(w.request(question, id), b.ports, setup).fold(f => fail(s"$f"), identity)

  test(
    "verifier-v8 through the services composition: the corpus template's 'without' aside and 'did not converge' stay VERIFIED; v8 wordings on corpus counts do not"
  ) {
    withCorpus { (w, b, cfg) =>
      // B15 shape: the template's singleton claim, with "without" inside its measure-naming aside.
      val s = askCorpus(w, b, NoCoauthorsQ)
      assert(s.answer.abstention.isEmpty, s.answer.abstention)
      val sClaims = s.answer.response.claims
      assert(
        sClaims.exists(x => x.text == Singletons && x.verificationStatus == VERIFIED),
        sClaims.map(x => (x.text, x.verificationStatus))
      )
      assert(sClaims.forall(_.verificationStatus == VERIFIED), sClaims)
      assert(s.answer.verification.counts.unsupported == 0)
      assert(s.answer.verification.counts.conflicting == 0)
      assert(s.audit.removedClaims.isEmpty && s.audit.droppedLines.isEmpty)
      val sItem = s.bundle.get.graphEvidence
        .find(_.facts.get("singletonResearchers").contains("28"))
        .getOrElse(fail(s"no corpus item: ${s.bundle.get.graphEvidence.map(_.facts)}"))
      assert(sItem.evidenceType == EvidenceType.COMPONENT_RECORD)
      assert(sItem.facts.get("researchers").contains("1200"))
      val sc = Verifier.Context(s.bundle.get, s.answer.resolutions, cfg)
      def singles(text: String) =
        Verifier.check(
          ClaimDraft(
            "claim-1",
            ClaimKind.COMPONENT,
            text,
            Vector(sItem.evidenceId),
            Vector.empty,
            Some("all-years"),
            Map("singletonResearchers" -> "28", "researchers" -> "1200")
          ),
          sc
        )
      assert(singles(Singletons).status == VERIFIED)
      Vector(
        "In all-years, short of 28 singleton researchers are among the 1200 researchers of the coauthorship graph." ->
          q("28", "short of"),
        "In all-years, 28 singleton researchers are among the nearly 1200 researchers of the coauthorship graph." ->
          q("1200", "nearly"),
        "In all-years, 28 singleton researchers (reportedly not) are among the 1200 researchers of the coauthorship graph." ->
          neg("1200", "not"),
        "In all-years, 28 singleton researchers (researchers without any of 1200 edges) are among the 1200 researchers of the coauthorship graph." ->
          neg("1200", "without"),
        "In all-years, 28 singleton researchers are without the 1200 researchers of the coauthorship graph." ->
          neg("1200", "without")
      ).foreach { (t, reason) =>
        val k = singles(t)
        assert(k.status != VERIFIED, t)
        assert(
          k.checks.exists(x => x.rule == "numbers-in-text" && x.detail.contains(reason)),
          s"$t: ${k.checks}"
        )
      }

      // B17 shape: "did not converge" states communityConverged; v8 applies the same lookahead to
      // every negation word ("failed to converge"), and a v8 word before the rounds still fails.
      val r = askCorpus(w, b, CommunitiesQ)
      assert(r.answer.abstention.isEmpty, r.answer.abstention)
      val rClaims = r.answer.response.claims
      assert(
        rClaims.exists(x => x.text == NotConverged && x.verificationStatus == VERIFIED),
        rClaims.map(x => (x.text, x.verificationStatus))
      )
      assert(rClaims.forall(_.verificationStatus == VERIFIED), rClaims)
      val rItem = r.bundle.get.graphEvidence
        .find(_.facts.get("communityRounds").contains("10"))
        .getOrElse(fail(s"no corpus item: ${r.bundle.get.graphEvidence.map(_.facts)}"))
      assert(rItem.facts.get("communityConverged").contains("false"))
      val rc = Verifier.Context(r.bundle.get, r.answer.resolutions, cfg)
      def rounds(text: String) =
        Verifier.check(
          ClaimDraft(
            "claim-1",
            ClaimKind.COMMUNITY,
            text,
            Vector(rItem.evidenceId),
            Vector.empty,
            Some("2000-2014"),
            Map("communityConverged" -> "false", "communityRounds" -> "10")
          ),
          rc
        )
      Vector(
        NotConverged,
        "Community detection in 2000-2014 failed to converge within its 10 rounds.",
        "Community detection in 2000-2014 fails to converge within its 10 rounds.",
        "In 2000-2014, community detection never converged within its 10 rounds."
      ).foreach(t => assert(rounds(t).status == VERIFIED, s"$t: ${rounds(t).checks}"))
      Vector(
        "Community detection in 2000-2014 lacked its 10 rounds." -> neg("10", "lacked"),
        "Community detection in 2000-2014 failed to reach 10 rounds." -> neg("10", "failed to"),
        "Community detection in 2000-2014 did not converge within short of 10 rounds." ->
          q("10", "short of"),
        "Community detection in 2000-2014 stopped after 10-ish rounds." -> q("10", "-ish")
      ).foreach { (t, reason) =>
        val k = rounds(t)
        assert(k.status != VERIFIED, t)
        assert(
          k.checks.exists(x => x.rule == "numbers-in-text" && x.detail.contains(reason)),
          s"$t: ${k.checks}"
        )
      }
    }
  }

  // ---- wordings outside the verifier-v8 lists and outside repair-02's disclosure (work order 5) --

  test(
    "a difference from another count, or a disputed or doubted statement of the stored value, is not verified"
  ) {
    // PENDING D18 (owner rag-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // Found in work order 5. Against ada's stored 10 each text is false or unsupported: "10 more
      // publications than her coauthor" states a difference (Grace Hopper has 6), and the others
      // dispute or doubt the stored fact. None is in the verifier-v8 lists or in the wordings
      // repair-02 reports as knowingly uncovered (RUNTIME.md section 21).
      withContext { c =>
        val kept = keptOf(
          c,
          Vector(
            "In all-years, Ada Lovelace has 10 more publications than her coauthor." -> pubs,
            "In all-years, Ada Lovelace has 10 fewer publications than her coauthor." -> pubs,
            "In all-years, the records dispute that Ada Lovelace has 10 publications." -> pubs,
            "In all-years, it is unlikely that Ada Lovelace has 10 publications." -> pubs,
            "In all-years, it is doubtful that Ada Lovelace has 10 publications." -> pubs
          )
        )
        assert(kept.isEmpty, s"kept as VERIFIED: $kept")
      }
    }
  }

  test(
    "an approximation idiom before the stored value (more or less, give or take, on the order of) is not verified"
  ) {
    // PENDING D19 (owner rag-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // Found in work order 5. Each text states ada's stored 10 inexactly, like "about 10" and
      // "roughly 10", which verifier-v7 removes. None is in the verifier-v8 lists or in the
      // wordings repair-02 reports as knowingly uncovered (RUNTIME.md section 21).
      withContext { c =>
        val kept = keptOf(
          c,
          Vector(
            "more or less",
            "give or take",
            "in the region of",
            "on the order of",
            "practically",
            "virtually",
            "an estimated",
            "possibly",
            "perhaps"
          ).map(w => s"In all-years, Ada Lovelace has $w 10 publications." -> pubs) :+
            ("In all-years, it is unclear whether Ada Lovelace has 10 publications." -> pubs)
        )
        assert(kept.isEmpty, s"kept as VERIFIED: $kept")
      }
    }
  }
