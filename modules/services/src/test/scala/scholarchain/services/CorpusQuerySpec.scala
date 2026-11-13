package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.VerificationStatus
import scholarchain.rag.runtime.AbstentionReason
import scholarchain.services.main.LocalRun
import upickle.default.read

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

/** Work order 11: the corpus window summaries wired into the services (runtime.window-summary.
  * load-run and --window-summary-root), answered over a real localhost socket.
  */
class CorpusQuerySpec extends AnyFunSuite:
  import ServiceFixtures.*

  private val b13 =
    "How many connected components does the graph have in 2000-2014, and what share of researchers is in the largest one?"
  private val loadRun = s"""runtime.window-summary.load-run = "$summaryLoadRun""""

  test("B13 shape: corpus components in 2000-2014 answered with VERIFIED claims from the summary") {
    withWorld(loadRun, summaries = true) { w =>
      w.query() { q =>
        w.graphEvidence() { g =>
          val r = q.post("/v1/query", queryJson(b13))
          assert(r.status == 200, r.body)
          val a = read[SyncQueryResponse](r.body)
          assert(!a.answer.abstained && a.answer.window.window == "2000-2014", a.answer)
          assert(a.claims.nonEmpty, r.body)
          a.claims.foreach(c => info(s"${c.claimId} ${c.verificationStatus}: ${c.text}"))
          assert(a.claims.forall(_.verificationStatus == VerificationStatus.VERIFIED), r.body)
          assert(a.answer.verification.unsupported == 0 && a.answer.verification.conflicting == 0)
          assert(
            a.claims.exists(
              _.text == "In 2000-2014, the coauthorship graph has 33 connected components; the " +
                "largest connected component has 252 researchers out of 400 researchers, a share of 0.6300."
            ),
            r.body
          )
          // The cited evidence resolves to the stored row: its Spark run, window, and object key.
          val ev = a.claims.head.evidenceIds.head
          val e = g.get(s"/v1/evidence/$ev")
          assert(e.status == 200, e.body)
          val item = read[EvidenceResponse](e.body)
          assert(
            item.provenance.runId == summaryRun && item.provenance.sourceRecordId == "2000-2014"
          )
          assert(item.sourceUri == s"$summaryKey#2000-2014")
          val x = read[ExplainResponse](q.get(a.links.explain).body)
          assert(x.verification.verified == a.claims.size)
        }
      }
      // The served key is a runtime run ID input.
      assert(w.backend.setup.runId != withWorld()(_.backend.setup.runId))
    }
  }

  test("an empty runtime.window-summary.load-run leaves the port unset: corpus questions abstain") {
    withWorld(summaries = true) { w =>
      w.query() { q =>
        val r = q.post("/v1/query", queryJson(b13))
        assert(r.status == 200, r.body)
        val a = read[SyncQueryResponse](r.body)
        assert(a.answer.abstained && a.claims.isEmpty, r.body)
        assert(a.answer.abstention.map(_.reason).contains(AbstentionReason.UNSUPPORTED_REQUEST))
      }
      // No window-summary input: the same runtime run as a world without the root.
      assert(w.backend.setup.runId == withWorld()(_.backend.setup.runId))
    }
  }

  test("LocalRun passes --window-summary-root through; a bad or missing object stops startup") {
    val root = Files.createTempDirectory("services-test-")
    try
      val stores = writeStores(root.resolve("stores"))
      val env = baseEnv + ("SCHOLARCHAIN_WINDOW_SUMMARY_RUN" -> summaryLoadRun)
      def start(extra: Vector[String]) =
        val out = ArrayBuffer.empty[String]
        (
          LocalRun.start(stores ++ extra, env, out += _, JsonLog(_ => ()), _ => 0),
          out.mkString("\n")
        )
      val refused = s"failed: the window-summary object $summaryKey was refused: "

      // Malformed content: exit 1, the key and the code are named, the content is not.
      val marker = "fixture-content-that-must-not-be-echoed"
      val bad = writeSummaries(root.resolve("bad"), s"""{"window": "$marker"}\n""".getBytes(UTF_8))
      val (b, badOut) = start(bad)
      assert(b == Left(1), badOut)
      assert(badOut.contains(refused + "MALFORMED line 1"), badOut)
      assert(!badOut.contains(marker), badOut)

      // No object under the root.
      val (m, missingOut) = start(Vector("--window-summary-root", root.resolve("none").toString))
      assert(m == Left(1) && missingOut.contains(refused + "OBJECT_MISSING"), missingOut)

      // A valid object: the three services start and name the served key.
      val cfg = scholarchain.core.config.ConfigLoader.load(env).toOption.get
      val good = writeSummaries(
        root.resolve("good"),
        scholarchain.storage.WindowSummaries.render(summaryRows(cfg.graph.windows))
      )
      val (s, goodOut) = start(good)
      val svcs = s.fold(c => fail(s"exit $c: $goodOut"), identity)
      svcs.foreach(_.stop())
      assert(svcs.size == 3 && goodOut.contains(s"window summaries: $summaryKey"), goodOut)
    finally deleteTree(root)
  }

  test(
    "graph-evidence-api never opens the window-summary object; same runtime run (repair-01 m3)"
  ) {
    withWorld(loadRun, summaries = true) { w =>
      // query-api answers a corpus question and stores its evidence.
      val a = w.query() { q => read[SyncQueryResponse](q.post("/v1/query", queryJson(b13)).body) }
      assert(!a.answer.abstained, a.answer)
      // Without the object, a question-answering service cannot start; graph-evidence-api can.
      Files.delete(w.root.resolve("window-summary").resolve(summaryKey))
      val args = scholarchain.storage.cli.Cli
        .parse(w.args, Set("live"), w.args.collect { case s"--$o" => o }.toSet)
        .toOption
        .get
      val out = ArrayBuffer.empty[String]
      val asked = Launcher.backend(args, w.cfg, out += _)
      assert(asked.left.exists(_.contains("OBJECT_MISSING")), asked)
      out.clear()
      val ge = Launcher
        .backend(args, w.cfg, out += _, answers = false)
        .fold(e => fail(s"$e: ${out.mkString("\n")}"), identity)
      assert(ge.ports.windowSummaries.isEmpty)
      assert(
        out.contains(
          s"window summaries: $summaryKey not opened (this service answers no questions)"
        )
      )
      // The key is still a run ID input, so evidence written by query-api resolves.
      assert(ge.setup.runId == w.backend.setup.runId)
      w.graphEvidence(ge) { g =>
        assert(g.get(s"/v1/evidence/${a.claims.head.evidenceIds.head}").status == 200)
      }
      // The main of graph-evidence-api starts the same way.
      val svc = Launcher
        .start(
          w.args,
          Apps.graphEvidenceApi(_, _, _, _),
          _ => 0,
          w.env,
          out += _,
          JsonLog(_ => ()),
          answers = false
        )
        .fold(c => fail(s"exit $c: ${out.mkString("\n")}"), identity)
      svc.stop()
    }
  }
