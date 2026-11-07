package scholarchain.rag

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import scholarchain.rag.cli.RenderCli
import scholarchain.storage.cli.Cli
import scholarchain.storage.{
  EmbeddingCandidates,
  ProvenanceRules,
  RenderedDocuments,
  TitanV2,
  VectorContract
}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.mutable

class RenderSpec extends AnyFunSuite:
  import Fixtures.*

  private val model = EmbeddingModels.TitanTextV2
  private val version = EmbeddingModels.version(model, Templates.Version)

  test("model versioning and selection constants") {
    assert(model.modelId == TitanV2.ModelId && model.dimension == VectorContract.Dimension)
    assert(version == "amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1")
    assert(EmbeddingModels.parse(version) == Some(model -> "rendering-v1"))
    assert(EmbeddingModels.parse("other|1024|normalized|rendering-v1").isEmpty)
    assert(EmbeddingModels.forId("example-embedding-model").isLeft)
    // Frozen values from SELECTION.md, computed independently with Python hashlib.
    assert(
      Selection.rankKey(
        "researcher-profile-v1",
        "scr:canon-v1.er-v2:001fe522e8222b9065d8747681782ee5",
        "all-years"
      ) == "c0bd6ae2faf1ff52b9dc44304245b5fc"
    )
    assert(
      Selection.rankKey("paper-abstract-v1", "dblp:conf/ispa/TangY0DHH24", "all-years") ==
        "0a3416a1573f16e3b3fe090dbbd145df"
    )
    assert(Selection.CommunityQuotas.values.sum == 4000)
  }

  test("candidates with facts pass the storage candidate validator unchanged") {
    withTemp { dir =>
      writeCandidates(dir, corpus)
      val (v, counts) = EmbeddingCandidates.validate(Cli.files(dir, ".jsonl"), windows, None)
      assert(v.isEmpty, v)
      assert(counts.values.sum == corpus.size)
    }
  }

  test("rendered documents pass RenderedDocuments.validate and follow the key and hash rules") {
    withTemp { dir =>
      writeCandidates(dir, corpus)
      val r = Render.renderAll(Cli.files(dir, ".jsonl"), windows, model, "config-v1").toOption.get
      assert(r.documents.size == corpus.size)
      val byWindow = ProvenanceRules.windowsByName(windows)
      r.documents.foreach { d =>
        assert(RenderedDocuments.problems(d, byWindow).isEmpty)
        assert(d.textHash == StableIds.textHash(d.text))
        assert(d.metadata("embeddingModelVersion").str == version)
        assert(d.embeddingModelId == model.modelId && !d.metadata.value.contains("text"))
      }
      val path = r.documents.find(_.documentType == "collaboration-path").get
      assert(path.metadata("entityIds").arr.size == 4) // pair, source, two hops
      val again = Render.renderAll(Cli.files(dir, ".jsonl"), windows, model, "config-v1")
      assert(again.toOption.get == r)
    }
  }

  test("bad inputs: missing facts, facts that disagree, rank gaps, wrong policy, duplicates") {
    def violations(ins: Seq[String]) = withTemp { dir =>
      val f = dir.resolve("x/part-00000.jsonl")
      Files.createDirectories(f.getParent)
      Files.writeString(f, ins.mkString("\n"), UTF_8)
      Render.renderAll(Seq(f), windows, model, "config-v1").left.toOption.get.map(_.detail)
    }
    val p = candidate("researcher-profile", profileFacts(0), 1)
    val noFacts = ujson.read(line(p)); noFacts.obj.remove("facts")
    assert(violations(Seq(noFacts.render())).exists(_.contains("facts missing")))
    val other = p.copy(facts = profileFacts(1))
    assert(violations(Seq(line(other))).exists(_.contains("differ from the facts")))
    val gap = candidate("researcher-profile", profileFacts(1), 3)
    assert(violations(Seq(line(p), line(gap))).exists(_.contains("ranks are not exactly 1..2")))
    val policy = p.copy(candidate = p.candidate.copy(selectionPolicyVersion = "selection-v0"))
    assert(violations(Seq(line(policy))).exists(_.contains("selection-v0")))
    val dup = p.copy(candidate = p.candidate.copy(selectionRank = 2))
    val dupes = violations(Seq(line(p), line(dup)))
    assert(dupes.size == 1 && dupes.head.contains(" first at "), dupes)
  }

  test("render CLI writes write-once files and a manifest; a rerun is Unchanged") {
    withTemp { dir =>
      val in = writeCandidates(dir.resolve("candidates"), corpus)
      val out = dir.resolve("stores")
      val log1 = mutable.Buffer.empty[String]
      assert(
        RenderCli.run(Seq("--input", in.toString, "--out", out.toString), log1 += _, env) == 0,
        log1
      )
      assert(
        log1.exists(_.startsWith("Inserted semantic-context/rendered/snapshot=2026-10-03/run=run-"))
      )
      assert(log1.exists(_.startsWith("Inserted runs/rag-render/snapshot=2026-10-03/run=run-")))
      assert(log1.contains("violations 0"))
      val log2 = mutable.Buffer.empty[String]
      assert(RenderCli.run(Seq("--input", in.toString, "--out", out.toString), log2 += _, env) == 0)
      assert(log2.count(_.startsWith("Unchanged ")) == 6) // 5 index files + manifest
      val files = Cli.files(out.resolve("s3/semantic-context/rendered"), ".jsonl")
      val (v, counts) = RenderedDocuments.validate(files, windows, None)
      assert(v.isEmpty && counts.values.sum == corpus.size)
      // Different bytes under an existing key are a conflict, never overwritten.
      Files.writeString(files.head, "changed\n", UTF_8)
      val log3 = mutable.Buffer.empty[String]
      assert(RenderCli.run(Seq("--input", in.toString, "--out", out.toString), log3 += _, env) == 1)
      assert(log3.exists(_.contains("exists with other content")))
      assert(Files.readString(files.head, UTF_8) == "changed\n")
    }
  }

  test("render CLI refuses an unknown model and bad candidates without writing") {
    withTemp { dir =>
      val in = writeCandidates(dir.resolve("candidates"), corpus.take(1))
      val out = dir.resolve("stores")
      val log = mutable.Buffer.empty[String]
      assert(
        RenderCli.run(Seq("--input", in.toString, "--out", out.toString), log += _, Map.empty) == 2
      )
      assert(log.exists(_.contains("SCHOLARCHAIN_EMBEDDING_MODEL_ID")))
      Files.writeString(in.resolve("researcher-profile-v1/part-00001.jsonl"), "{not json}\n", UTF_8)
      assert(RenderCli.run(Seq("--input", in.toString, "--out", out.toString), log += _, env) == 1)
      assert(!Files.exists(out.resolve("s3")))
    }
  }
