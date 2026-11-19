package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.rag.cli.{Benchmark, BenchmarkCli}
import scholarchain.storage.{Embedder, FileObjectStore, VectorFilter, VectorHit, VectorReadPort}
import upickle.default.{read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Retrieval modes for experiment E (RUNTIME.md section 24), in fake mode on the fixture world:
  * which stages run in each mode, the audit field, the BenchmarkCli flag, and the ranked-list file.
  */
class RetrievalModeSpec extends AnyFunSuite:
  import RuntimeFixtures.*

  private val pathQ =
    "What is the shortest coauthorship path between Ada Lovelace and Edsger Dijkstra?"
  private def ok(r: Either[RuntimeFailure, RuntimeResult]) = r.fold(f => fail(s"$f"), identity)
  private val queryId = "qry-" + "0" * 32
  private def request(q: String) =
    QueryRequest(RuntimeSchemas.QueryRequest, queryId, None, q, None, None, None, None)

  /** The world's ports with the embedder and the vector store counted. */
  private final class Counted(w: World):
    var embeds = 0
    var queries = Vector.empty[String]
    val ports: RuntimePorts = w.ports.copy(
      embedder = new Embedder:
        def modelId = w.embedder.modelId
        def isFake = w.embedder.isFake
        def embed(text: String) =
          embeds += 1
          w.embedder.embed(text)
      ,
      vectors = new VectorReadPort:
        def get(index: String, keys: Seq[String]) = w.ports.vectors.get(index, keys)
        def query(
            index: String,
            vector: Array[Float],
            topK: Int,
            filter: Option[VectorFilter]
        ): Either[String, Vector[VectorHit]] =
          queries :+= index
          w.ports.vectors.query(index, vector, topK, filter)
    )
    def ask(mode: RetrievalMode): RuntimeResult =
      ok(Workflow.answer(request(pathQ), ports, w.setup.copy(retrievalMode = mode)))

  private def outcomes(r: RuntimeResult) = r.audit.stages.map(s => s.stage -> s.outcome).toMap
  private val graphStages =
    Vector(Stage.COLLABORATION_METRICS, Stage.PATH_FINDING, Stage.COMMUNITY_ANALYSIS)
  // The reads researcher resolution makes (Resolver); nothing else is allowed in VECTOR_ONLY.
  private val resolutionReads =
    Set(
      "kv.entity-link",
      "graph.authored-counts",
      "graph.researcher-institutions",
      "graph.coauthors"
    )

  test("FUSED is the default and the path before the mode existed: same run ID, same audit bytes") {
    withWorld() { w1 =>
      withWorld() { w2 =>
        assert(w1.setup.retrievalMode == RetrievalMode.FUSED)
        val default = ok(Workflow.answer(request(pathQ), w1.ports, w1.setup))
        val fused = ok(
          Workflow.answer(
            request(pathQ),
            w2.ports,
            w2.setup.copy(retrievalMode = RetrievalMode.FUSED)
          )
        )
        assert(default.answer == fused.answer && default.bundle == fused.bundle)
        assert(write(default.audit) == write(fused.audit))
        // The audit is the v3 wire form it had before: no retrievalMode key, read back as FUSED.
        val json = write(default.audit)
        assert(default.audit.schemaVersion == "rag-audit-v3" && !json.contains("retrievalMode"))
        assert(read[AuditRecord](json) == default.audit)
        // FUSED still plans vector indexes and runs the graph and semantic stages.
        val st = outcomes(default)
        assert(default.answer.plan.semanticIndexes.nonEmpty)
        assert(st(Stage.PATH_FINDING) == StageOutcome.OK)
        assert(st(Stage.SEMANTIC_CONTEXT) == StageOutcome.OK)
        val b = default.bundle.get
        assert(b.graphEvidence.nonEmpty && b.semanticEvidence.nonEmpty)
        // The run ID gains a retrieval input only for the other modes.
        def id(m: RetrievalMode) =
          Workflow.runIdFor(config, Seq("g"), w1.prompts, RuntimeMode.FAKE, retrieval = m)
        assert(
          id(RetrievalMode.FUSED) == Workflow.runIdFor(
            config,
            Seq("g"),
            w1.prompts,
            RuntimeMode.FAKE
          )
        )
        assert(RetrievalMode.values.map(id).distinct.length == 3)
      }
    }
  }

  test("GRAPH_ONLY: no embedding, no vector query, no semantic item; graph stages run") {
    withWorld() { w =>
      val c = Counted(w)
      val r = c.ask(RetrievalMode.GRAPH_ONLY)
      assert(c.embeds == 0 && c.queries.isEmpty)
      assert(r.answer.plan.semanticIndexes.isEmpty)
      val st = outcomes(r)
      assert(st(Stage.PATH_FINDING) == StageOutcome.OK)
      assert(st(Stage.SEMANTIC_CONTEXT) == StageOutcome.SKIPPED)
      assert(r.audit.embeddingCalls.isEmpty && r.audit.vectorIndexes.isEmpty)
      val b = r.bundle.get
      assert(b.graphEvidence.nonEmpty && b.semanticEvidence.isEmpty)
      assert(!r.answer.response.abstained)
      assert(r.audit.retrievalMode == RetrievalMode.GRAPH_ONLY)
      val json = write(r.audit)
      assert(json.contains("\"retrievalMode\":\"GRAPH_ONLY\""))
      assert(read[AuditRecord](json) == r.audit)
    }
  }

  test("VECTOR_ONLY: resolution reads only, stages 3 to 5 skipped, vector items only") {
    withWorld() { w =>
      val fused = Counted(w).ask(RetrievalMode.FUSED)
      assert(!fused.audit.templateCalls.map(_.templateId).toSet.subsetOf(resolutionReads))
    }
    withWorld() { w =>
      val c = Counted(w)
      val r = c.ask(RetrievalMode.VECTOR_ONLY)
      val reads = r.audit.templateCalls.map(_.templateId).toSet
      assert(reads.nonEmpty && reads.subsetOf(resolutionReads), reads)
      val st = outcomes(r)
      graphStages.foreach(s => assert(st(s) == StageOutcome.SKIPPED, s))
      assert(st(Stage.SEMANTIC_CONTEXT) == StageOutcome.OK)
      assert(c.embeds == 1 && c.queries.nonEmpty)
      val b = r.bundle.get
      // Not even the identity record of resolution is a bundle item.
      assert(b.graphEvidence.isEmpty && b.semanticEvidence.nonEmpty)
      assert(b.semanticEvidence.exists(_.chunkId.contains(pathKey)))
      // Vector text never proves a fact (law 3): with no graph item, no claim verifies.
      assert(
        r.answer.abstention.map(_.reason).contains(AbstentionReason.NO_VERIFIED_CLAIM),
        r.answer.abstention
      )
      assert(r.audit.retrievalMode == RetrievalMode.VECTOR_ONLY)
      assert(write(r.audit).contains("\"retrievalMode\":\"VECTOR_ONLY\""))
    }
  }

  test("VECTOR_ONLY drops the filters taken from graph facts; FUSED skips those indexes") {
    withWorld() { w =>
      val ix = config.s3Vectors.indexes
      val plan = ok(w.ask(pathQ)).answer.plan
        .copy(semanticIndexes = Vector(ix("communities"), ix("abstracts")))
      val noFacts = GraphFacts(
        RuntimeSchemas.GraphFacts,
        queryId,
        graphRun,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty
      )
      def run(m: RetrievalMode) = SemanticStage.run(
        pathQ,
        plan,
        Vector.empty,
        noFacts,
        w.embedder,
        None,
        w.ports.vectors,
        w.ports.objects,
        modelVersion,
        config,
        w.nowMs,
        _ => (),
        m
      )
      assert(run(RetrievalMode.FUSED).results.isEmpty)
      val v = run(RetrievalMode.VECTOR_ONLY).results
      assert(v.map(_.index) == Vector(ix("communities"), ix("abstracts")))
      assert(v.forall(r => !r.filter.contains("communityId") && !r.filter.contains("entityIds")))
    }
  }

  // ---- BenchmarkCli ----

  private val questionsCsv =
    s"""question_id,category,question_text,researcher_a,researcher_b,window,answer_type,should_abstain
       |T01,shortest_path,$pathQ,Ada Lovelace,Edsger Dijkstra,all,path,no
       |""".stripMargin
  private val pathsCsv =
    s"""window,researcherX,researcherY,jointPublicationCount,firstCollaborationYear,lastCollaborationYear
       |all-years,$ada,$grace,2,2001,2002
       |all-years,$grace,$edsger,1,2012,2012
       |""".stripMargin

  private def withFiles[A](body: (Path, Vector[String]) => A): A =
    val root = tempDir()
    try
      val seg = s"snapshot=$snapshot/run=$graphRun"
      writeGraph(root.resolve(s"graph/$seg"))
      writeItems(root.resolve(s"items/$seg"))
      writeVectors(root.resolve("vectors"))
      val objects = FileObjectStore(root.resolve("s3")).loader
      objects.putNew(profileRef, profileText.getBytes(UTF_8))
      objects.putNew(pathRef, pathText.getBytes(UTF_8))
      Files.writeString(root.resolve("questions.csv"), questionsCsv, UTF_8)
      Files.writeString(root.resolve("paths.csv"), pathsCsv, UTF_8)
      body(
        root,
        Vector(
          "--questions",
          root.resolve("questions.csv").toString,
          "--path-evidence",
          root.resolve("paths.csv").toString,
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

  test(
    "BenchmarkCli --retrieval-mode: one run per mode, the mode recorded, a ranked-list file each"
  ) {
    withFiles { (root, args) =>
      val store = FileObjectStore(root.resolve("out/s3")).reader
      def keys(part: String) = store.list("reports/rag-benchmark/").filter(_.contains(part))
      val modes = Vector(
        (None, "fused", "FUSED"),
        (Some("graph-only"), "graph", "GRAPH_ONLY"),
        (Some("vector-only"), "vector", "VECTOR_ONLY")
      )
      modes.foreach { (flag, source, mode) =>
        val a = args ++ flag.toVector.flatMap(f => Vector("--retrieval-mode", f))
        val (code, out) = runCli(a)
        assert(code == 0, out.mkString("\n"))
        assert(out.exists(_.contains(s"${mode.toLowerCase.replace('_', '-')} retrieval)")), out)
        val (again, out2) = runCli(a) // resume: nothing new, the list is rewritten Unchanged
        assert(again == 0 && out2.exists(_.contains("1 already done")), out2.mkString("\n"))

        val fileFlag = flag.getOrElse("fused")
        val list = keys(s"/retrieval/T01-$fileFlag.csv")
        assert(list.size == 1, keys("/retrieval/"))
        val runDir = list.head.split("/retrieval/").head
        val result =
          read[Benchmark.Result](new String(store.get(s"$runDir/questions/T01.json").get, UTF_8))
        val summary = keys(s"$runDir/summary-").last
        assert(result.schemaVersion == "rag-benchmark-result-v3" && result.retrievalMode == mode)
        assert(
          read[Benchmark.Summary](new String(store.get(summary).get, UTF_8)).retrievalMode == mode
        )
        val rows = new String(store.get(list.head).get, UTF_8).linesIterator.toVector
        assert(rows.head == "rank,source,origin,evidenceId,elementId")
        assert(
          rows.tail.nonEmpty && rows.tail == result.retrieved.map(x =>
            s"${x.rank},${x.source},${x.origin},${x.evidenceId},${x.elementId}"
          )
        )
        assert(result.retrieved.map(_.rank) == (1 to result.retrieved.size).toVector)
        assert(result.retrieved.forall(_.source == source))
        val origins = result.retrieved.map(_.origin).toSet
        source match
          case "fused"  => assert(origins == Set("graph", "vector"))
          case "graph"  => assert(origins == Set("graph"))
          case "vector" => assert(origins == Set("vector"))
        // Graph items name their store pointer; vector items their vector key.
        if source != "graph" then
          assert(result.retrieved.filter(_.origin == "vector").exists(_.elementId == pathKey))
        assert(
          result.retrieved
            .filter(_.origin == "graph")
            .forall(x =>
              x.elementId.startsWith("dynamodb:") || x.elementId.startsWith("neptune:") ||
                x.elementId.startsWith("runtime:")
            )
        )
      }
      // Three modes, three benchmark runs (the runtime run ID differs by mode).
      assert(keys("/questions/T01.json").size == 3)
    }
  }

  test("BenchmarkCli refuses an unknown --retrieval-mode before loading the stores") {
    withFiles { (root, args) =>
      val (code, out) = runCli(args ++ Vector("--retrieval-mode", "hybrid"))
      assert(code == 2 && out.exists(_.contains("--retrieval-mode must be")), out)
      assert(!Files.exists(root.resolve("out/s3")))
    }
  }
