package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import upickle.default.{read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable

class EmbeddingSpec extends AnyFunSuite:
  import Fixtures.*

  test("rendered documents round trip and valid documents pass") {
    val d = profiles.head
    assert(read[RenderedDocument](write(d)) == d)
    withTemp { dir =>
      val (v, counts) = RenderedDocuments.validate(
        Cli0.files(writeRendered(dir, profiles)),
        windows,
        Some(1 -> 5)
      )
      assert(v.isEmpty, v)
      assert(counts == Map("researcher-profile-v1" -> 3))
    }
  }

  test("rendered documents: wrong hash, key, textRef, text in metadata, duplicates") {
    val d = profiles.head
    def codes(x: RenderedDocument) =
      RenderedDocuments.problems(x, ProvenanceRules.windowsByName(windows))
    assert(codes(d).isEmpty)
    assert(codes(d.copy(text = "changed")).exists(_.contains("textHash")))
    assert(codes(d.copy(vectorKey = "chunk-" + "0" * 32)).exists(_.contains("chunkId")))
    val m = ujson.Obj.from(d.metadata.value)
    m("text") = d.text
    assert(codes(d.copy(metadata = m)).exists(_.contains("unexpected metadata key text")))
    val m2 = ujson.Obj.from(d.metadata.value)
    m2("textRef") = S3Layout.semanticText(snapshot, otherRunId, d.index, d.vectorKey)
    assert(codes(d.copy(metadata = m2)).exists(_.contains("textRef")))
    withTemp { dir =>
      val f = dir.resolve("researcher-profile-v1/part-00000.jsonl")
      Files.createDirectories(f.getParent)
      Files.writeString(f, Seq(write(d), "{bad", write(d)).mkString("\n"), UTF_8)
      val (v, _) = RenderedDocuments.validate(Seq(f), windows, None)
      assert(v.map(_.code) == Vector("MALFORMED", "DUPLICATE_KEY"))
    }
  }

  test("throttle: no 60-second window holds more than 50 call starts") {
    var t = 0L
    val starts = mutable.ArrayBuffer.empty[Long]
    val th = Throttle(50, () => t, ms => t += ms)
    assert(th.intervalMs == 1200)
    (1 to 120).foreach { _ =>
      th.acquire()
      starts += t
      t += 7 // the call itself takes a little time
    }
    assert(starts.indices.dropRight(50).forall(i => starts(i + 50) - starts(i) >= 60000))
    assertThrows[IllegalArgumentException](Throttle(51, () => 0L, _ => ()))
  }

  test("fake embedder is deterministic, normalized, and word-sensitive; Titan V2 bodies parse") {
    val e = HashingEmbedder()
    val a = e.embed("graph mining networks")
    assert(a.toVector == e.embed("graph mining networks").toVector)
    assert(VectorContract.dataProblems(a).isEmpty)
    def dot(x: Array[Float], y: Array[Float]) = x.zip(y).map(_ * _).sum
    assert(dot(a, e.embed("graph networks")) > dot(a, e.embed("protein folding")))
    assert(VectorContract.dataProblems(e.embed("")).isEmpty)
    val body = ujson.read(TitanV2.requestBody("hi"))
    assert(body == ujson.Obj("inputText" -> "hi", "dimensions" -> 1024, "normalize" -> true))
    val ok = ujson.Obj(
      "embedding" -> ujson.Arr.from(unit(3).map(f => ujson.Num(f))),
      "inputTextTokenCount" -> 1
    )
    assert(TitanV2.parseResponse(ok.render()).map(_.toVector) == Right(unit(3).toVector))
    assert(TitanV2.parseResponse("""{"embedding":[1,2,3]}""").isLeft)
    assert(TitanV2.parseResponse("not json").isLeft)
  }

  /** Fake embedder that fails on chosen texts. */
  private class Flaky(fail: String => Option[Exception]) extends Embedder:
    val modelId = "fake-hashing-embedder-v1"
    val isFake = true
    def embed(text: String) =
      fail(text).foreach(e => throw e)
      HashingEmbedder().embed(text)

  /** Injected clock shared by the runner's throttle and the test embedders; no real sleeping. */
  private var clock = 0L

  private def runner(
      root: Path,
      input: Path,
      max: Int,
      embedder: Embedder = HashingEmbedder()
  ): (Either[Vector[Violation], EmbedReport], Int) =
    var sleeps = 0
    val r = EmbeddingRunner.run(
      Cli0.files(input),
      root,
      embedder,
      Throttle(50, () => clock, ms => { clock += ms; sleeps += 1 }),
      _ => (),
      FileRunLedger(root.resolve("ledger")),
      windows,
      EmbeddingRunner
        .Settings(max, RetryPolicy(3, 1, 4), "config-v1", () => "2026-11-05T10:00:00Z"),
      _ => ()
    )
    (r, sleeps)

  test("runner: --max cap, resume by textHash and model, publish once complete, rerun is a no-op") {
    withTemp { root =>
      val input = writeRendered(root.resolve("in"), profiles)
      val (r1, sleeps) = runner(root, input, max = 2)
      val a = r1.toOption.get
      assert(a.status == EmbedStatus.MAX_REACHED && a.embedded == 2 && a.remaining == 1)
      assert(a.requests == 2 && sleeps == 0 && a.published.isEmpty) // fake: never throttled
      val b = runner(root, input, max = 5)._1.toOption.get
      assert(b.runId == a.runId && b.alreadyDone == 2 && b.embedded == 1)
      assert(b.status == EmbedStatus.COMPLETE && b.remaining == 0)
      val store = FileObjectStore(root.resolve("s3"))
      val manifestKey = S3Layout.runManifest("embed", snapshot, a.runId)
      assert(b.published.last == manifestKey)
      val m = RunManifest.fromJson(store.reader.get(manifestKey).get)
      assert(RunManifest.verify(m, store.reader).isEmpty)
      val backup =
        S3Layout.vectorBackup(snapshot, a.runId, "researcher-profile-v1") + "/part-00000.jsonl"
      val lines = new String(store.reader.get(backup).get, UTF_8).linesIterator.toVector
      val recs = lines.map(VectorRecord.fromJson)
      assert(recs.map(_.key) == profiles.map(_.vectorKey).sorted)
      assert(recs.forall(_.modelVersion == s"fake:$modelVersion"))
      assert(
        store.reader
          .get(profiles.head.metadata("textRef").str)
          .map(new String(_, UTF_8))
          .contains(profiles.head.text)
      )
      val c = runner(root, input, max = 5)._1.toOption.get
      assert(
        c.embedded == 0 && c.requests == 0 && c.alreadyDone == 3 && c.status == EmbedStatus.COMPLETE
      )
      val ledger = FileRunLedger(root.resolve("ledger")).entries(a.runId)
      assert(ledger.count(_.entryType == "EMBED_SESSION") == 3)
      assert(
        ledger.filter(_.entryType == "EMBED_STATUS").map(_.details("status")) == Vector(
          "MAX_REACHED",
          "COMPLETE",
          "COMPLETE"
        )
      )
    }
  }

  test("runner: a real embedder is throttled (injected clock), the fake one is not") {
    withTemp { root =>
      val input = writeRendered(root.resolve("in"), profiles)
      val starts = mutable.ArrayBuffer.empty[Long]
      val real = new Embedder:
        val modelId = model
        val isFake = false
        def embed(t: String) =
          starts += clock
          clock += 5 // the call itself takes a little time
          HashingEmbedder().embed(t)
      clock = 0L
      val (r, sleeps) = runner(root, input, 10, real)
      assert(r.toOption.get.status == EmbedStatus.COMPLETE && r.toOption.get.requests == 3)
      assert(sleeps == 2 && starts.zip(starts.drop(1)).forall((a, b) => b - a >= 1200), starts)
      val session = FileRunLedger(root.resolve("ledger"))
        .entries(r.toOption.get.runId)
        .find(_.entryType == "EMBED_SESSION")
      assert(session.map(_.details("intervalMs")).contains("1200"))
    }
    withTemp { root =>
      val input = writeRendered(root.resolve("in"), profiles)
      clock = 0L
      val (r, sleeps) = runner(root, input, 10)
      assert(r.toOption.get.requests == 3 && sleeps == 0 && clock == 0L)
      val session = FileRunLedger(root.resolve("ledger"))
        .entries(r.toOption.get.runId)
        .find(_.entryType == "EMBED_SESSION")
      assert(session.map(_.details("intervalMs")).contains("0"))
    }
  }

  test("runner: rejected documents go to the rejected file; exhausted retries stop the run") {
    withTemp { root =>
      val input = writeRendered(root.resolve("in"), profiles)
      val bad = Flaky(t => Option.when(t.startsWith("Beta"))(RejectedInputException("too odd")))
      val r = runner(root, input, 10, bad)._1.toOption.get
      assert(r.embedded == 2 && r.rejected == 1 && r.status == EmbedStatus.COMPLETE)
      val work = EmbeddingRunner.workDir(root, snapshot, r.runId)
      assert(Files.readString(work.resolve("rejected.jsonl")).contains("REJECTED_INPUT"))
      assert(!Files.readString(work.resolve("rejected.jsonl")).contains("data"))
      assert(runner(root, input, 10, bad)._1.toOption.get.requests == 0)
    }
    withTemp { root =>
      val input = writeRendered(root.resolve("in"), profiles)
      val down = Flaky(t => Option.when(t.startsWith("Beta"))(TransientStoreException("throttled")))
      val r = runner(root, input, 10, down)._1.toOption.get
      assert(r.status == EmbedStatus.FAILED && r.embedded == 1 && r.requests == 4) // 1 + 3 attempts
      val ledger = FileRunLedger(root.resolve("ledger")).entries(r.runId)
      assert(ledger.exists(_.entryType == "EMBED_FAILURE"))
      val again = runner(root, input, 10)._1.toOption.get
      assert(again.runId == r.runId && again.alreadyDone == 1 && again.embedded == 2)
      assert(again.status == EmbedStatus.COMPLETE)
    }
  }

  test("runner: invalid input or a document for another model is refused before any call") {
    withTemp { root =>
      val broken = profiles.head.copy(textHash = StableIds.textHash("x"))
      val input = writeRendered(root.resolve("in"), profiles.tail :+ broken)
      assert(runner(root, input, 10)._1.left.toOption.get.exists(_.code == "BAD_DOCUMENT"))
      val real = new Embedder:
        val modelId = "amazon.titan-embed-text-v2:0-other"
        val isFake = false
        def embed(t: String) = fail("must not be called")
      val ok = writeRendered(root.resolve("ok"), profiles)
      assert(runner(root, ok, 10, real)._1.left.toOption.get.map(_.code) == Vector("BAD_INPUT"))
      assert(!Files.exists(root.resolve("work")))
    }
  }

/** Small helpers for this module's tests. */
object Cli0:
  def files(dir: Path): Vector[Path] = scholarchain.storage.cli.Cli.files(dir, ".jsonl")
