package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.UTF_8

class ObjectStoreSpec extends AnyFunSuite:
  import Fixtures.*

  test("the eight prefixes equal the core storage.prefixes") {
    assert(config.storage.prefixes.all == S3Layout.Prefixes.map(_ + "/"))
  }

  test("key layout: every key has a known prefix, snapshot and run, and safe characters") {
    val keys = Seq(
      S3Layout.neptuneRoot(snapshot, runId) + "/vertices/researcher/part-00000.csv",
      S3Layout.coauthorEvidence(snapshot, runId, "all-years", "cae-" + "0" * 32),
      S3Layout.dynamoItems(snapshot, runId, "RunLedger") + "/part-00000.jsonl",
      S3Layout.semanticText(snapshot, runId, "researcher-profile-v1", "chunk-" + "0" * 32),
      S3Layout.embeddingCandidates(snapshot, runId, "paper-abstract-v1") + "/part-00000.jsonl",
      S3Layout.vectorBackup(snapshot, runId, "paper-abstract-v1") + "/part-00000.jsonl",
      S3Layout.rejectedVectors(snapshot, runId, "paper-abstract-v1"),
      S3Layout.runManifest("store-load", snapshot, runId)
    )
    keys.foreach(k => assert(S3Layout.keyProblems(k).isEmpty, k))
    assert(S3Layout.keyProblems("tmp/x/snapshot=2026-10-03/run=" + runId + "/a").nonEmpty)
    assert(S3Layout.keyProblems("graph/x/a.csv").nonEmpty)
    assert(S3Layout.keyProblems(s"graph/x/snapshot=$snapshot/run=$runId/../a").nonEmpty)
    assert(S3Layout.keyProblems(s"graph/x/snapshot=$snapshot/run=$runId/a b").nonEmpty)
    assert(S3Layout.keyProblems(s"graph/x/snapshot=$snapshot/run=$runId/" + "a" * 1000).nonEmpty)
  }

  test("objects are write-once and the run manifest verifies hashes and sources") {
    withTemp { dir =>
      val store = FileObjectStore(dir)
      val key = S3Layout.semanticText(snapshot, runId, "researcher-profile-v1", "chunk-" + "1" * 32)
      val bytes = "text".getBytes(UTF_8)
      assert(store.loader.putNew(key, bytes) == Right(PutObjectOutcome.Inserted))
      assert(store.loader.putNew(key, bytes) == Right(PutObjectOutcome.Unchanged))
      assert(
        store.loader
          .putNew(key, "other".getBytes(UTF_8))
          .toOption
          .get
          .isInstanceOf[PutObjectOutcome.Conflict]
      )
      assert(store.loader.putNew("bad/key", bytes).isLeft)
      assert(store.reader.list("semantic-context/") == Vector(key))
      val entry = ManifestEntry(
        key,
        Hashing.sha256(bytes),
        bytes.length,
        "summaries/semantic-summaries run-x"
      )
      val m = RunManifest(RunManifest.SchemaVersion, "store-load", runId, snapshot, Vector(entry))
      assert(RunManifest.fromJson(RunManifest.toJson(m)) == m)
      assert(RunManifest.verify(m, store.reader).isEmpty)
      val broken = m.copy(entries =
        Vector(entry.copy(sha256 = Hashing.sha256("x")), entry.copy(sourceRef = " "))
      )
      assert(
        RunManifest.verify(broken, store.reader).map(_.code).toSet == Set(
          "HASH_MISMATCH",
          "MISSING_SOURCE_REF",
          "DUPLICATE_KEY"
        )
      )
    }
  }

  test("the run ledger is append-only and idempotent across reopen") {
    withTemp { dir =>
      val e = LedgerEntry(runId, "step#load", "STEP", "2026-11-05T10:00:00Z", Map("rows" -> "3"))
      val ledger = FileRunLedger(dir)
      assert(ledger.append(e) == AppendOutcome.Appended)
      val reopened = FileRunLedger(dir)
      assert(
        reopened.append(e.copy(recordedAt = "2026-11-05T11:00:00Z")) == AppendOutcome.Unchanged
      )
      assert(reopened.append(e.copy(details = Map("rows" -> "4"))) == AppendOutcome.Conflict)
      assert(reopened.entries(runId) == Vector(e))
    }
  }

  test("retry covers only transient failures, with bounded exponential delay") {
    val delays = scala.collection.mutable.ArrayBuffer.empty[Long]
    var calls = 0
    val (v, attempts) = Retry.run(RetryPolicy(4, 100, 250), delays += _) {
      calls += 1
      if calls < 4 then throw TransientStoreException("throttled") else "ok"
    }
    assert(v == "ok" && attempts == 4 && delays == Seq(100L, 200L, 250L))
    assertThrows[IllegalStateException](
      Retry.run(RetryPolicy.Default, _ => ())(throw IllegalStateException("no"))
    )
  }
