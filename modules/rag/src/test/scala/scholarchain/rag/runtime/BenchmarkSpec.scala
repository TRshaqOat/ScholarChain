package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.rag.cli.{Benchmark, BenchmarkCli}
import scholarchain.storage.{FileObjectStore, RunManifest}
import upickle.default.read

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The benchmark harness (ruling 12): CSV parsing, the stored-path reference, the mechanical
  * checks, resume by question ID, --max, and the summary, in fake mode on the fixture files.
  */
class BenchmarkSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private val questionsCsv =
    """question_id,category,question_text,researcher_a,researcher_b,window,answer_type,should_abstain,notes
      |T01,shortest_path,What is the shortest coauthorship path between Ada Lovelace and Edsger Dijkstra?,Ada Lovelace,Edsger Dijkstra,all,path,no,
      |B12,shortest_path,What is the shortest coauthorship path between Ada Lovelace and Elena Vasquez-Marlowe?,Ada Lovelace,Elena Vasquez-Marlowe,all,abstain,yes,invented
      |T03,direct_collaboration,"How many joint papers do Ada Lovelace and Grace Hopper have, and when did they first collaborate?",Ada Lovelace,Grace Hopper,all,count,no,"quoted, with a comma"
      |""".stripMargin

  private def pathsCsv(adaGraceCount: Int) =
    s"""window,researcherX,researcherY,jointPublicationCount,firstCollaborationYear,lastCollaborationYear
       |all-years,$ada,$grace,$adaGraceCount,2001,2002
       |all-years,$grace,$edsger,1,2012,2012
       |""".stripMargin

  private def withFiles[A](body: (Path, Vector[String]) => A): A =
    val root = tempDir()
    try
      val seg = s"snapshot=$snapshot/run=$graphRun"
      writeGraph(root.resolve(s"graph/$seg"))
      writeItems(root.resolve(s"items/$seg"))
      writeVectors(root.resolve("vectors"))
      FileObjectStore(root.resolve("s3")).loader.putNew(profileRef, profileText.getBytes(UTF_8))
      Files.writeString(root.resolve("questions.csv"), questionsCsv, UTF_8)
      body(
        root,
        Vector(
          "--questions",
          root.resolve("questions.csv").toString,
          "--graph",
          root.resolve(s"graph/$seg").toString,
          "--items",
          root.resolve(s"items/$seg").toString,
          "--objects",
          root.resolve("s3").toString,
          "--vectors",
          root.resolve("vectors").toString,
          "--out",
          root.resolve("out").toString
        )
      )
    finally deleteTree(root)

  private def runCli(args: Vector[String]): (Int, Vector[String]) =
    val lines = Vector.newBuilder[String]
    val code = BenchmarkCli.run(args, lines += _, _ => (), () => 0L, env)
    (code, lines.result())

  private def results(root: Path): Map[String, Benchmark.Result] =
    val store = FileObjectStore(root.resolve("out/s3")).reader
    store
      .list("reports/rag-benchmark/")
      .filter(_.contains("/questions/"))
      .map(k => read[Benchmark.Result](new String(store.get(k).get, UTF_8)))
      .map(r => r.questionId -> r)
      .toMap

  private def outcome(r: Benchmark.Result, name: String) =
    r.checks.find(_.name == name).map(_.outcome).get

  test("question CSV: quoted commas and escaped quotes parse; bad rows are refused") {
    val qs = Benchmark.questions(questionsCsv).fold(fail(_), identity)
    assert(qs.map(_.id) == Vector("T01", "B12", "T03"))
    assert(qs(2).text.contains("have, and when") && qs(1).shouldAbstain && !qs(0).shouldAbstain)
    assert(qs(0).names == Vector("Ada Lovelace", "Edsger Dijkstra"))
    assert(
      Benchmark.csvRows("a,\"b \"\"q\"\" c\",d").contains(Vector(Vector("a", "b \"q\" c", "d")))
    )
    assert(Benchmark.csvRows("a,\"b").isLeft)
    val header = questionsCsv.linesIterator.next()
    assert(Benchmark.questions(s"$header\nX,c,q?,,,all,count,maybe,\n").isLeft)
    assert(Benchmark.questions(s"$header\nX,c,q?,,,all,count,no,\nX,c,q?,,,all,count,no,\n").isLeft)
    assert(Benchmark.questions("question_id,category\nX,c\n").isLeft)
  }

  test("stored path reference: the stored researcher sequence and hop lookup in either direction") {
    val ref = Benchmark.pathReference(pathsCsv(2)).fold(fail(_), identity)
    assert(ref.path("all-years", ada, edsger).contains(Vector(ada, grace, edsger)))
    assert(ref.path("all-years", edsger, ada).contains(Vector(edsger, grace, ada)))
    assert(ref.path("2000-2014", ada, edsger).isEmpty && ref.path("all-years", ada, wei1).isEmpty)
    assert(ref.edge("all-years", grace, ada).map(_.count).contains(2L))
    assert(Benchmark.pathReference(pathsCsv(2).replace(",1,2012", ",x,2012")).isLeft)
  }

  test(
    "fake mode: --max 1 answers one question, a rerun resumes by question ID, and the checks pass"
  ) {
    withFiles { (root, args) =>
      Files.writeString(root.resolve("paths.csv"), pathsCsv(2), UTF_8)
      val withRef = args ++ Vector("--path-evidence", root.resolve("paths.csv").toString)
      val (c1, o1) = runCli(withRef ++ Vector("--max", "1"))
      assert(c1 == 0, o1.mkString("\n"))
      assert(results(root).keySet == Set("T01"), o1.mkString("\n"))
      val first = results(root)("T01")
      val (c2, o2) = runCli(withRef)
      assert(c2 == 0, o2.mkString("\n"))
      assert(o2.exists(_.contains("1 already done, answering 2 now")), o2.mkString("\n"))
      val rs = results(root)
      assert(rs.keySet == Set("T01", "B12", "T03"))
      assert(rs("T01") == first) // the done question was not rerun

      val path = rs("T01")
      assert(
        path.operations.contains("SHORTEST_PATH/PAIR") && path.graph.pathStatuses == Vector("FOUND")
      )
      assert(path.checks.forall(_.outcome != Benchmark.Outcome.FAIL), path.checks.mkString("\n"))
      assert(outcome(path, "path-hops") == Benchmark.Outcome.PASS)
      assert(outcome(path, "pair-edges") == Benchmark.Outcome.PASS)
      assert(path.graph.reads > 0 && path.llm.attempts == 0 && path.semantic.embeddingCalls <= 1)

      val unknown = rs("B12")
      assert(unknown.abstentionReason.contains("UNKNOWN_RESEARCHER"))
      assert(outcome(unknown, "resolution") == Benchmark.Outcome.PASS)
      assert(outcome(unknown, "abstention") == Benchmark.Outcome.PASS)
      assert(outcome(unknown, "path-hops") == Benchmark.Outcome.NOT_APPLICABLE)
      assert(unknown.graph.evidenceItems == 0 && unknown.semantic.embeddingCalls == 0)
      assert(outcome(rs("T03"), "pair-edges") == Benchmark.Outcome.PASS)

      val store = FileObjectStore(root.resolve("out/s3")).reader
      val summaries = store.list("reports/rag-benchmark/").filter(_.contains("/summary-"))
      assert(summaries.map(_.split('/').last) == Vector("summary-001.json", "summary-003.json"))
      val sum = read[Benchmark.Summary](new String(store.get(summaries.last).get, UTF_8))
      assert(sum.questionsDone == 3 && sum.notDone.isEmpty && sum.failures.isEmpty, sum.failures)
      assert(sum.abstentions == Map("ANSWERED" -> 2, "UNKNOWN_RESEARCHER" -> 1))
      assert(sum.graph.pathStatuses == Map("FOUND" -> 1) && sum.llm.attempts == 0)
      val manifest = store.list("runs/rag-benchmark/").last
      val m = RunManifest.fromJson(store.get(manifest).get)
      assert(m.entries.size == 4 && RunManifest.verify(m, store).isEmpty)
    }
  }

  test("a stored path that differs from the runtime path fails path-hops and pair-edges") {
    withFiles { (root, args) =>
      Files.writeString(root.resolve("paths.csv"), pathsCsv(3), UTF_8)
      val (code, out) = runCli(
        args ++ Vector("--path-evidence", root.resolve("paths.csv").toString, "--max", "1")
      )
      assert(code == 0, out.mkString("\n"))
      val r = results(root)("T01")
      assert(outcome(r, "path-hops") == Benchmark.Outcome.FAIL)
      assert(r.checks.find(_.name == "path-hops").exists(_.detail.contains("hop 1 differs")))
      assert(outcome(r, "pair-edges") == Benchmark.Outcome.FAIL)
      assert(out.exists(_.startsWith("  FAIL T01 path-hops")), out.mkString("\n"))
    }
  }

  test("claim-evidence fails a material claim that cites only semantic context or an unknown ID") {
    withWorld() { w =>
      val r = w
        .ask("Have Ada Lovelace and Grace Hopper coauthored a paper?")
        .fold(f => fail(s"$f"), identity)
      val q = Benchmark.questions(questionsCsv).fold(fail(_), identity)(2)
      val ref = Benchmark.pathReference(pathsCsv(2)).fold(fail(_), identity)
      def claimCheck(x: RuntimeResult) =
        Benchmark.checks(q, x, ref, config, 0L).find(_.name == "claim-evidence").get
      assert(claimCheck(r).outcome == Benchmark.Outcome.PASS, claimCheck(r))
      val semanticId = r.bundle.get.semanticEvidence.head.evidenceId
      val material = r.answer.verification.checks
        .find(_.checks.exists(_.rule == "graph-evidence-required"))
        .get
        .claimId
      def withIds(ids: Vector[String]) =
        val resp = r.answer.response
        r.copy(answer =
          r.answer.copy(response =
            resp.copy(claims =
              resp.claims.map(c => if c.claimId == material then c.copy(evidenceIds = ids) else c)
            )
          )
        )
      assert(claimCheck(withIds(Vector(semanticId))).outcome == Benchmark.Outcome.FAIL)
      assert(claimCheck(withIds(Vector("ev-unknown"))).outcome == Benchmark.Outcome.FAIL)
    }
  }

  test("a model that never answers is a call failure (no result is recorded); fake mode is not") {
    val down = RecordedLlm(Vector.fill(4)(Left(RuntimeException("service unavailable"))))
    withWorld(llm = Some(down)) { w =>
      val r = w
        .ask("Have Ada Lovelace and Grace Hopper coauthored a paper?")
        .fold(f => fail(s"$f"), identity)
      assert(r.answer.plan.source == PlannerSource.FALLBACK_RULES)
      assert(
        Benchmark
          .callFailure(r.audit)
          .exists(_.startsWith("QUERY_PLANNING: no model call succeeded"))
      )
    }
    withWorld() { w =>
      val r = w
        .ask("Have Ada Lovelace and Grace Hopper coauthored a paper?")
        .fold(f => fail(s"$f"), identity)
      assert(Benchmark.callFailure(r.audit).isEmpty)
    }
  }

  test(
    "usage: questions and path evidence are required, --max is at least 1, live needs a profile"
  ) {
    withFiles { (root, args) =>
      Files.writeString(root.resolve("paths.csv"), pathsCsv(2), UTF_8)
      val ref = Vector("--path-evidence", root.resolve("paths.csv").toString)
      assert(runCli(args)._1 == 2)
      assert(runCli(args ++ ref ++ Vector("--max", "0"))._1 == 2)
      assert(runCli(args ++ ref ++ Vector("--live"))._1 == 2)
      assert(runCli(args.drop(2) ++ ref)._1 == 2)
      assert(!Files.exists(root.resolve("out/s3")))
    }
  }
