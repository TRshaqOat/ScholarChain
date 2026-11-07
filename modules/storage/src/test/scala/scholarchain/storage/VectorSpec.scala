package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.StableIds
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable

class VectorSpec extends AnyFunSuite:
  import Fixtures.*

  private val profile =
    candidate("researcher-profile-v1", "researcher-profile", Vector(ra), "Profile of A.")
  private val profileB =
    candidate("researcher-profile-v1", "researcher-profile", Vector(rb), "Profile of B.")
  private val topic = candidate(
    "topic-institution-summary-v1",
    "topic-summary",
    Vector("openalex:T10002"),
    "Topic T."
  )

  /** A candidate line as Spark writes it: the contract fields plus a `facts` object. */
  private def line(c: EmbeddingCandidate, facts: ujson.Value = ujson.Obj("subject" -> "x")) =
    val j = ujson.read(write(c))
    j("facts") = facts
    j.render()

  private def writeLines(dir: Path, lines: Seq[String]): Path =
    val f = dir.resolve("part-00000.jsonl")
    Files.writeString(f, lines.mkString("\n") + "\n", UTF_8)
    f

  private def load(
      store: FileVectorStore,
      records: Seq[VectorRecord],
      mode: WriteMode,
      dir: Path,
      attempt: Int = 1
  ) =
    val rejected = mutable.ArrayBuffer.empty[ujson.Obj]
    val ledger = FileRunLedger(dir.resolve("ledger"))
    val summary = VectorLoader.load(
      store.loader,
      "researcher-profile-v1",
      records,
      mode,
      batchSize = 2,
      RetryPolicy(3, 1, 4),
      _ => (),
      ledger,
      runId,
      attempt,
      rejected += _,
      () => "2026-11-05T10:00:00Z"
    )
    (summary, rejected.toVector, ledger)

  test("configured index names equal the contract") {
    assert(config.s3Vectors.indexes.values.toSet == VectorContract.Indexes.map(_.name).toSet)
  }

  test("candidate JSONL round trips and valid candidates pass") {
    assert(upickle.default.read[EmbeddingCandidate](write(profile)) == profile)
    withTemp { dir =>
      val f = writeLines(dir, Seq(line(profile), line(profileB), line(topic)))
      val (v, counts) = EmbeddingCandidates.validate(Seq(f), windows, None)
      assert(v.isEmpty, v)
      assert(counts == Map("researcher-profile-v1" -> 2, "topic-institution-summary-v1" -> 1))
      val (bounded, _) = EmbeddingCandidates.validate(Seq(f), windows, Some(10000 -> 15000))
      assert(bounded.map(_.code) == Vector("TOTAL_OUT_OF_RANGE"))
    }
  }

  test("missing fields, malformed lines, wrong hashes, and duplicate keys are reported") {
    withTemp { dir =>
      val missing = ujson.read(line(profile))
      missing.obj.remove("textHash")
      val lines = Seq(
        line(profile),
        "{not json",
        missing.render(),
        line(profileB.copy(textHash = StableIds.textHash("other"))),
        line(profileB.copy(entityIds = Vector(w1))),
        line(profileB.copy(vectorIndex = "collaboration-path-v1")),
        line(profile),
        write(topic), // no facts
        line(topic, ujson.Str("{}")),
        line(topic, ujson.Obj())
      )
      val (v, _) = EmbeddingCandidates.validate(Seq(writeLines(dir, lines)), windows, None)
      // Lines 4 to 6 reuse profileB's key, so 5 and 6 are also duplicates of line 4.
      assert(
        v.map(x => (x.line, x.code)).toSet == Set(
          2L -> "MALFORMED",
          3L -> "MALFORMED",
          4L -> "BAD_CANDIDATE",
          5L -> "BAD_CANDIDATE",
          5L -> "DUPLICATE_KEY",
          6L -> "BAD_CANDIDATE",
          6L -> "DUPLICATE_KEY",
          7L -> "DUPLICATE_KEY",
          8L -> "BAD_CANDIDATE",
          9L -> "BAD_CANDIDATE",
          9L -> "DUPLICATE_KEY",
          10L -> "BAD_CANDIDATE",
          10L -> "DUPLICATE_KEY"
        )
      )
      assert(
        v.filter(_.line >= 8).map(_.detail).filter(_.startsWith("facts")) == Vector(
          "facts missing",
          "facts must be a JSON object",
          "facts is an empty object"
        )
      )
    }
  }

  test("metadata: no text, no unknown keys, required keys present, sizes within budget") {
    val m = EmbeddingCandidates.toMetadata(profile, model)
    assert(VectorContract.metadataProblems("researcher-profile-v1", profile.vectorKey, m).isEmpty)
    def bad(f: ujson.Obj => Unit) =
      val c = ujson.Obj.from(m.value)
      f(c)
      VectorContract.metadataProblems("researcher-profile-v1", profile.vectorKey, c)
    assert(bad(_("text") = profile.text).exists(_.contains("unexpected metadata key text")))
    assert(bad(_.value.remove("textRef")).exists(_.contains("missing metadata key textRef")))
    assert(bad(_("runId") = 5).exists(_.contains("runId")))
    assert(
      bad(_("topicIds") = ujson.Arr.from((1 to 11).map(i => ujson.Str(s"openalex:T$i"))))
        .exists(_.contains("max 10"))
    )
    val longTopics = ujson.Arr.from((0 until 10).map(i => ujson.Str(s"openalex:T$i" + "9" * 90)))
    assert(bad(_("topicIds") = longTopics).exists(_.contains("filterable metadata about")))
    assert(
      VectorContract
        .metadataProblems("collaboration-path-v1", profile.vectorKey, m)
        .exists(_.contains("documentType"))
    )
    assert(VectorContract.dataProblems(Array.fill(1024)(0f)) == List("zero vector"))
    assert(VectorContract.dataProblems(Array.fill(1024)(1f)).head.startsWith("not normalized"))
    assert(VectorContract.dataProblems(unit(0).take(10)).head.startsWith("dimension"))
  }

  test("batched load, idempotent reload, duplicate keys, and key conflicts") {
    withTemp { dir =>
      val store = FileVectorStore(dir.resolve("vectors"))
      val a = vectorRecord(profile, unit(0))
      val b = vectorRecord(profileB, unit(1))
      val bChanged =
        vectorRecord(profileB.copy(textHash = StableIds.textHash("new"), text = "new"), unit(2))
      val (s1, rej1, ledger) = load(store, Seq(a, b, a, bChanged), WriteMode.CreateOnly, dir)
      assert(
        s1 == VectorLoadSummary(written = 2, skipped = 1, rejected = 1, batches = 1, retries = 0)
      )
      assert(rej1.map(_("reason").str) == Vector("DUPLICATE_KEY"))
      assert(!rej1.head.value.contains("data"))
      // A fresh store reading the same log sees the same vectors (file-backed).
      val reopened = FileVectorStore(dir.resolve("vectors"))
      val (s2, _, _) = load(reopened, Seq(a, b), WriteMode.CreateOnly, dir, attempt = 2)
      assert(s2.written == 0 && s2.skipped == 2)
      val (s3, rej3, _) = load(reopened, Seq(bChanged), WriteMode.CreateOnly, dir, attempt = 3)
      assert(s3.rejected == 1 && rej3.head("reason").str.startsWith("KEY_CONFLICT"))
      val (s4, rej4, _) = load(reopened, Seq(bChanged), WriteMode.Supersede, dir, attempt = 4)
      assert(s4.rejected == 1 && rej4.head("reason").str.startsWith("SUPERSEDE_WITHOUT_CHANGE"))
      val newModel = vectorRecord(
        profileB.copy(textHash = StableIds.textHash("new"), text = "new"),
        unit(2),
        "titan-v2-1024-b"
      )
      val (s5, _, _) = load(reopened, Seq(newModel), WriteMode.Supersede, dir, attempt = 5)
      assert(s5.written == 1)
      assert(reopened.size("researcher-profile-v1") == 2)
      assert(
        ledger
          .entries(runId)
          .map(_.entryKey)
          .head == "vectors#researcher-profile-v1#attempt-1#batch-00000"
      )
    }
  }

  test("a batch with an in-call duplicate or an invalid vector rejects only those vectors") {
    withTemp { dir =>
      val store = FileVectorStore(dir)
      val a = vectorRecord(profile, unit(0))
      val r = store.loader.put(
        "researcher-profile-v1",
        Seq(a, a, vectorRecord(profileB, unit(1)).copy(metadata = ujson.Obj())),
        WriteMode.CreateOnly
      )
      val res = r.toOption.get
      assert(
        res.written.isEmpty && res.rejected.map(_._2.takeWhile(_ != ':')) == Vector(
          "DUPLICATE_KEY_IN_BATCH",
          "DUPLICATE_KEY_IN_BATCH",
          "INVALID"
        )
      )
      assert(
        store.loader.put("researcher-profile-v1", Seq.fill(501)(a), WriteMode.CreateOnly).isLeft
      )
    }
  }

  test("transient failures are retried within the bound and recorded") {
    withTemp { dir =>
      val store = FileVectorStore(dir.resolve("v"))
      store.transientFailuresToInject = 2
      val (s, _, ledger) =
        load(store, Seq(vectorRecord(profile, unit(0))), WriteMode.CreateOnly, dir)
      assert(s.written == 1 && s.retries == 2)
      assert(ledger.entries(runId).head.details("attempts") == "3")
      store.transientFailuresToInject = 3
      assertThrows[TransientStoreException](
        load(store, Seq(vectorRecord(profileB, unit(1))), WriteMode.CreateOnly, dir, attempt = 2)
      )
      assert(FileRunLedger(dir.resolve("ledger")).entries(runId).last.details("status") == "FAILED")
    }
  }

  test("cosine query with metadata filters") {
    withTemp { dir =>
      val store = FileVectorStore(dir)
      store.loader.put(
        "researcher-profile-v1",
        Seq(vectorRecord(profile, unit(0)), vectorRecord(profileB, unit(1, tilt = 0))),
        WriteMode.CreateOnly
      )
      val q = unit(0)
      val all = store.reader.query("researcher-profile-v1", q, 10, None).toOption.get
      assert(all.map(_.key) == Vector(profile.vectorKey, profileB.vectorKey))
      assert(math.abs(all.head.distance) < 1e-6)
      val onlyB = VectorFilter.Eq("entityIds", ujson.Str(rb))
      assert(
        store.reader
          .query("researcher-profile-v1", q, 10, Some(onlyB))
          .toOption
          .get
          .map(_.key) == Vector(profileB.vectorKey)
      )
      val f = VectorFilter.And(
        Seq(
          VectorFilter.Eq("window", ujson.Str("all-years")),
          VectorFilter.Nin("entityIds", Seq(ujson.Str(ra)))
        )
      )
      assert(
        store.reader
          .query("researcher-profile-v1", q, 1, Some(f))
          .toOption
          .get
          .map(_.key) == Vector(profileB.vectorKey)
      )
      assert(
        store.reader
          .query("researcher-profile-v1", q, 10, Some(VectorFilter.Eq("textHash", ujson.Str("x"))))
          .isLeft
      )
      assert(store.reader.query("researcher-profile-v1", q.take(3), 10, None).isLeft)
      assert(
        store.reader
          .get("researcher-profile-v1", Seq(profile.vectorKey))
          .toOption
          .get
          .map(_.data.toVector) == Vector(q.toVector)
      )
    }
  }
