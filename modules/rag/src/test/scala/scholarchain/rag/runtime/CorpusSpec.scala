package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{EvidenceType, SourceType, VerificationStatus}
import scholarchain.rag.cli.AskCli
import scholarchain.storage.FileObjectStore

import java.nio.charset.StandardCharsets.UTF_8

/** Work order 9 (follow-up F2): corpus-level component, community, and window questions (the shapes
  * of benchmark B13, B15, B17, and B24) answered from the stored window summaries through the
  * storage WindowSummaryReadPort, on the fixture world. Every value is a stored row value with the
  * row's provenance and window; the limitations the rows carry are stated.
  */
class CorpusSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private def ok(r: Either[RuntimeFailure, RuntimeResult]) = r.fold(f => fail(s"$f"), identity)
  private val b13 =
    "How many connected components does the graph have in 2000-2014, and what share of researchers is in the largest one?"
  private val b15 = "How many researchers have no coauthors at all?"
  private val b17 = "How many collaboration communities of size 3 or more exist in 2000-2014?"
  private val b24 =
    "Is the largest connected component a larger share of researchers in 2015-2026 than in 2000-2014?"

  private def texts(r: RuntimeResult) = r.answer.response.claims.map(_.text)
  private def allVerified(r: RuntimeResult) =
    r.answer.response.claims.nonEmpty &&
      r.answer.response.claims.forall(_.verificationStatus == VerificationStatus.VERIFIED) &&
      r.answer.verification.counts.unsupported == 0 && r.answer.verification.counts.conflicting == 0

  test("B13 and B15 shapes: corpus component counts of a window, verified, with row provenance") {
    withWorld(summaries = true) { w =>
      val r = ok(w.ask(b13))
      assert(r.answer.abstention.isEmpty, r.answer.abstention)
      assert(allVerified(r), r.answer.verification)
      assert(
        texts(r).contains(
          "In 2000-2014, the coauthorship graph has 33 connected components; the largest connected component " +
            "has 252 researchers out of 400 researchers, a share of 0.6300."
        ),
        texts(r)
      )
      val items = r.bundle.get.graphEvidence
      assert(items.size == 1)
      val e = items.head
      assert(e.evidenceType == EvidenceType.COMPONENT_RECORD)
      assert(e.facts("scope") == "CORPUS" && e.facts("window") == "2000-2014")
      assert(e.facts("windowStartYear") == "2000" && e.facts("windowEndYear") == "2014")
      assert(e.facts("components") == "33" && e.facts("largestComponentFraction") == "0.63")
      assert(e.facts("nodeBasis") == NodeBasis)
      assert(e.facts("metricVersion") == s"spark-corpus-window-summary-v1@$summaryRun")
      // Provenance resolves to the stored row and its Spark run; the source is the store object.
      assert(e.provenance.runId == summaryRun && e.provenance.sourceRecordId == "2000-2014")
      assert(e.provenance.sourceDataset == SourceType.DERIVED)
      assert(e.sourceUri == s"$summaryKey#2000-2014")
      assert(r.audit.templateCalls.map(_.templateId) == Vector("obj.window-summary"))
      assert(r.audit.metricVersions == Vector(s"spark-corpus-window-summary-v1@$summaryRun"))
      // The node basis is stated in the answer.
      assert(
        r.answer.response.uncertainty.exists(
          _.contains(s"Graph nodes in 2000-2014 (corpus counts): $NodeBasis.")
        )
      )

      val s = ok(w.ask(b15))
      assert(s.answer.abstention.isEmpty && allVerified(s), s.answer.verification)
      assert(s.answer.plan.window.window == "all-years")
      assert(
        texts(s).contains(
          "In all-years, 28 singleton researchers (researchers without any coauthor edge in that window) " +
            "are among the 1200 researchers of the coauthorship graph."
        ),
        texts(s)
      )
      assert(
        s.answer.response.uncertainty
          .exists(_.contains(s"Graph nodes in all-years (corpus counts)"))
      )
    }
  }

  test("B17 shape: communities of at least the stored minimum size; non-convergence is stated") {
    withWorld(summaries = true) { w =>
      val r = ok(w.ask(b17))
      assert(r.answer.abstention.isEmpty && allVerified(r), r.answer.verification)
      assert(
        texts(r) == Vector(
          "In 2000-2014, 37 communities have size 3 or more, out of 71 communities; the largest community has 21 researchers.",
          "Community detection in 2000-2014 did not converge within its 10 rounds."
        ),
        texts(r)
      )
      val e = r.bundle.get.graphEvidence.head
      assert(e.evidenceType == EvidenceType.COMMUNITY_RECORD)
      assert(e.facts("communityConverged") == "false" && e.facts("communityMinSize") == "3")
      assert(
        r.answer.response.uncertainty.exists(
          _.contains("Communities in 2000-2014 did not converge: detection stopped after 10 rounds")
        )
      )
    }
  }

  test("B24 shape: the stored pair of windows, with deltas, and the window lengths stated") {
    withWorld(summaries = true) { w =>
      val r = ok(w.ask(b24))
      assert(r.answer.abstention.isEmpty && allVerified(r), r.answer.verification)
      assert(
        texts(r).contains(
          "The largest connected component's share of researchers went from 0.6300 in 2000-2014 to 0.6763 " +
            "in 2015-2026 (change 0.0463), so it is a larger share in 2015-2026 than in 2000-2014."
        ),
        texts(r)
      )
      assert(
        texts(r).contains(
          "The compared windows differ in length: 2000-2014 covers 15 years and 2015-2026 covers 12 years."
        )
      )
      val change =
        r.bundle.get.graphEvidence.find(_.evidenceType == EvidenceType.TEMPORAL_CHANGE).get
      assert(change.facts("window") == "2000-2014>2015-2026")
      assert(change.facts("fromLargestComponentFraction") == "0.63")
      assert(change.facts("toLargestComponentFraction") == "0.67625")
      assert(change.facts("deltaLargestComponentFraction") == "0.04625")
      assert(change.facts("deltaComponents") == "-11")
      assert(change.provenance.runId == summaryRun)
      assert(change.provenance.sourceRecordId == "2000-2014>2015-2026")
      assert(
        r.answer.response.uncertainty.exists(
          _.contains("The compared windows differ in length: 2000-2014 covers 15 years")
        )
      )
    }
  }

  test("a model claim on corpus evidence is still checked: wrong measure or value is removed") {
    withWorld(summaries = true) { w =>
      val r = ok(w.ask(b17))
      val c = Verifier.Context(r.bundle.get, r.answer.resolutions, config)
      val e = r.bundle.get.graphEvidence.head
      def d(text: String, values: Map[String, String]) =
        ClaimDraft(
          "claim-1",
          ClaimKind.COMMUNITY,
          text,
          Vector(e.evidenceId),
          Vector.empty,
          Some("2000-2014"),
          values
        )
      val right = Map("communitiesAtLeastMinSize" -> "37", "communityMinSize" -> "3")
      assert(
        Verifier.check(d("In 2000-2014, 37 communities have size 3 or more.", right), c).status ==
          VerificationStatus.VERIFIED
      )
      // Another threshold than the stored one is not a stored fact.
      assert(
        Verifier.check(d("In 2000-2014, 37 communities have size 5 or more.", right), c).status ==
          VerificationStatus.UNSUPPORTED
      )
      // The count named as researchers is the wrong measure.
      assert(
        Verifier
          .check(d("In 2000-2014, 37 researchers are in communities of size 3 or more.", right), c)
          .status ==
          VerificationStatus.UNSUPPORTED
      )
      assert(
        Verifier
          .check(
            d(
              "In 2000-2014, 38 communities have size 3 or more.",
              right + ("communitiesAtLeastMinSize" -> "38")
            ),
            c
          )
          .status == VerificationStatus.CONFLICTING
      )
    }
  }

  test("Phase 11 work order 2, live B13 and B24 shape: a share written as a percentage") {
    withWorld(summaries = true) { w =>
      val r = ok(w.ask(b24))
      val c = Verifier.Context(r.bundle.get, r.answer.resolutions, config)
      val e = r.bundle.get.graphEvidence
        .find(x => x.evidenceType == EvidenceType.COMPONENT_RECORD)
        .get
      assert(e.facts("window") == "2015-2026" && e.facts("largestComponentFraction") == "0.67625")
      val values = Map(
        "largestComponentSize" -> "541",
        "researchers" -> "800",
        "largestComponentFraction" -> "0.6763"
      )
      def status(text: String, v: Map[String, String] = values) =
        Verifier
          .check(
            ClaimDraft(
              "claim-1",
              ClaimKind.COMPONENT,
              text,
              Vector(e.evidenceId),
              Vector.empty,
              Some("2015-2026"),
              v
            ),
            c
          )
          .status
      def claim(share: String) =
        s"In 2015-2026, the largest connected component has 541 researchers, $share of the 800 researchers."
      // The stored fraction 0.67625 as a percentage, to the precision written.
      for share <- Vector("67.6%", "68%", "67.63 percent", "a 67.6% share") do
        assert(status(claim(share)) == VerificationStatus.VERIFIED, share)
      // Another value, a misplaced point, a count written as a percentage, or a share the claim
      // does not assert, is removed.
      for share <- Vector("67.7%", "67%", "6.8%", "0.68%", "541%") do
        assert(status(claim(share)) == VerificationStatus.UNSUPPORTED, share)
      assert(
        status(claim("67.6%"), values - "largestComponentFraction") ==
          VerificationStatus.UNSUPPORTED
      )
    }
  }

  test("Phase 11 work order 2, live B17 shape: 'collaboration communities' names the count") {
    withWorld(summaries = true) { w =>
      val r = ok(w.ask(b17))
      val c = Verifier.Context(r.bundle.get, r.answer.resolutions, config)
      val e = r.bundle.get.graphEvidence.head
      def status(text: String) =
        Verifier
          .check(
            ClaimDraft(
              "claim-1",
              ClaimKind.COMMUNITY,
              text,
              Vector(e.evidenceId),
              Vector.empty,
              Some("2000-2014"),
              Map("communitiesAtLeastMinSize" -> "37", "communityMinSize" -> "3")
            ),
            c
          )
          .status
      assert(
        status("In 2000-2014, 37 collaboration communities have size 3 or more.") ==
          VerificationStatus.VERIFIED
      )
      // "collaborations" alone still names coauthor edges, not communities.
      assert(
        status("In 2000-2014, 37 collaborations have size 3 or more.") ==
          VerificationStatus.UNSUPPORTED
      )
    }
  }

  test("repair-02 D14 controls: the template's 'without' aside and 'failed to converge' verify") {
    withWorld(summaries = true) { w =>
      def check(q: String, kind: ClaimKind, window: String, v: Map[String, String])(text: String) =
        val r = ok(w.ask(q))
        val c = Verifier.Context(r.bundle.get, r.answer.resolutions, config)
        val e = r.bundle.get.graphEvidence.head
        Verifier
          .check(
            ClaimDraft("claim-1", kind, text, Vector(e.evidenceId), Vector.empty, Some(window), v),
            c
          )
          .status
      val singles = check(
        b15,
        ClaimKind.COMPONENT,
        "all-years",
        Map("singletonResearchers" -> "28", "researchers" -> "1200")
      )
      assert(
        singles(
          "In all-years, 28 singleton researchers (researchers without any coauthor edge in that window) " +
            "are among the 1200 researchers of the coauthorship graph."
        ) == VerificationStatus.VERIFIED
      )
      // Outside the aside, "without" negates the number after it.
      assert(
        singles(
          "In all-years, 28 singleton researchers are without the 1200 researchers of the coauthorship graph."
        ) == VerificationStatus.UNSUPPORTED
      )
      val rounds = check(
        b17,
        ClaimKind.COMMUNITY,
        "2000-2014",
        Map("communityConverged" -> "false", "communityRounds" -> "10")
      )
      assert(
        rounds("Community detection in 2000-2014 failed to converge within its 10 rounds.") ==
          VerificationStatus.VERIFIED
      )
      assert(
        rounds("Community detection in 2000-2014 lacked its 10 rounds.") ==
          VerificationStatus.UNSUPPORTED
      )
    }
  }

  test("without the window-summary port, corpus questions still abstain as NOT_STORED") {
    withWorld() { w =>
      Vector(b13, b15, b17, b24).foreach { q =>
        val a = ok(w.ask(q)).answer
        assert(a.abstention.map(_.reason).contains(AbstentionReason.UNSUPPORTED_REQUEST), q)
      }
    }
  }

  test("AskCli: --window-summary-root and --window-summary-run wire the port; bad use is refused") {
    val root = tempDir()
    try
      val seg = s"snapshot=$snapshot/run=$graphRun"
      writeGraph(root.resolve(s"graph/$seg"))
      writeItems(root.resolve(s"items/$seg"))
      writeVectors(root.resolve("vectors"))
      FileObjectStore(root.resolve("s3")).loader.putNew(profileRef, profileText.getBytes(UTF_8))
      writeSummaries(root.resolve("ws"))
      val base = Vector(
        "--graph",
        root.resolve(s"graph/$seg").toString,
        "--items",
        root.resolve(s"items/$seg").toString,
        "--objects",
        root.resolve("s3").toString,
        "--vectors",
        root.resolve("vectors").toString,
        "--out",
        root.resolve("out").toString,
        "--question",
        b13
      )
      def run(extra: String*): (Int, Vector[String]) =
        val lines = Vector.newBuilder[String]
        val code = AskCli.run(base ++ extra, lines += _, _ => (), () => 0L, env)
        (code, lines.result())
      val (code, out) =
        run(
          "--window-summary-root",
          root.resolve("ws").toString,
          "--window-summary-run",
          summaryLoadRun
        )
      assert(code == 0, out.mkString("\n"))
      assert(out.exists(_.contains(s"window summaries: $summaryKey")), out.mkString("\n"))
      assert(out.exists(l => l.contains("[VERIFIED]") && l.contains("33 connected components")))
      assert(run("--window-summary-root", root.resolve("ws").toString)._1 == 2)
      assert(run("--window-summary-run", summaryLoadRun)._1 == 2)
      assert(
        run(
          "--window-summary-root",
          root.resolve("ws").toString,
          "--window-summary-run",
          "x"
        )._1 == 2
      )
      // An object that is not there (another load run) refuses the stores: exit 1.
      val other = summaryRun
      val (missing, lines) =
        run("--window-summary-root", root.resolve("ws").toString, "--window-summary-run", other)
      assert(missing == 1 && lines.exists(_.contains("OBJECT_MISSING")), lines.mkString("\n"))
    finally deleteTree(root)
  }
