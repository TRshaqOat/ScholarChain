package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.storage.cli.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable

/** CLI behaviour on local fakes only (dry-run defaults). */
class CliSpec extends AnyFunSuite:
  import Fixtures.*

  /** The environment every CLI here reads: empty, so the local defaults apply, whatever the shell
    * running the tests has set (M8).
    */
  private val localEnv = Map.empty[String, String]

  private def capture(f: (String => Unit) => Int): (Int, Vector[String]) =
    val lines = mutable.ArrayBuffer.empty[String]
    val code = f(lines += _)
    (code, lines.toVector)

  test("validator CLI: counts and OK on a valid graph; grouped violations and exit 1 otherwise") {
    withTemp { root =>
      writeGraph(root, graph)
      val (ok, out) =
        capture(ValidateCli.run(Seq("--kind", "neptune", "--dir", root.toString), _, localEnv))
      assert(ok == 0 && out.contains("count researcher 3") && out.contains("OK"))
      // Evidence tracing ran and every rule is printed, zero included (F2, M4).
      assert(out.contains("evidence edges-inline 1") && out.contains("evidence work-ids-traced 2"))
      NeptuneCsv.EvidenceRules.foreach(r => assert(out.contains(s"rule $r 0"), r))
      writeGraph(
        root,
        graph.updated(
          NeptuneContract.Researcher,
          graph(NeptuneContract.Researcher) :+ researcherV(ra)
        )
      )
      val (bad, out2) =
        capture(ValidateCli.run(Seq("--kind", "neptune", "--dir", root.toString), _, localEnv))
      assert(bad == 1 && out2.contains("  DUPLICATE_ID 1"))
      val (usage, _) = capture(ValidateCli.run(Seq("--kind", "graph", "--dir", "x"), _, localEnv))
      assert(usage == Cli.Usage)
    }
  }

  test("Neptune loader dry run: second pass all Unchanged; COAUTHORED_WITH expectations checked") {
    withTemp { root =>
      val dir = root.resolve(s"s3/graph/neptune/snapshot=$snapshot/run=$runId")
      writeGraph(dir, graph)
      val expect = root.resolve("expect.csv")
      def run(rows: String*) =
        Files.writeString(
          expect,
          (LoadNeptuneCli.ExpectHeader.mkString(",") +: rows).mkString("", "\n", "\n"),
          UTF_8
        )
        capture(
          LoadNeptuneCli.run(
            Seq("--dir", dir.toString, "--out", root.toString, "--passes", "2")
              ++ Seq("--expect-coauthor", expect.toString),
            _,
            localEnv
          )
        )
      val (ok, out) = run(s"all-years,$rb,$ra,2,2005,2010")
      assert(ok == 0, out)
      assert(
        out.contains("pass 2 verticesAdded 0 edgesAdded 0 verticesUnchanged 7 edgesUnchanged 7")
      )
      assert(
        out.exists(
          _.startsWith(s"edge-check OK all-years $rb $ra expected n=2 2005-2010 found cae-")
        )
      )
      val (bad, out2) = run(s"all-years,$ra,$rb,3,2005,2010", s"2000-2014,$ra,$rb,2,2005,2010")
      assert(bad == 1 && out2.contains("edge-check misses 2"), out2)
      assert(out2.exists(_.startsWith("edge-check MISMATCH")))
      assert(out2.exists(_.startsWith("edge-check MISSING")))
    }
  }

  test("validator CLI: rendered documents and DynamoDB ranking items") {
    withTemp { root =>
      writeRendered(root.resolve("r"), profiles)
      val (ok, out) =
        capture(
          ValidateCli.run(
            Seq("--kind", "rendered", "--dir", root.resolve("r").toString),
            _,
            localEnv
          )
        )
      assert(ok == 0 && out.contains("count researcher-profile-v1 3"))
      val (b, _) = capture(
        ValidateCli.run(
          Seq("--kind", "rendered", "--dir", root.resolve("r").toString, "--bounds", "10000:15000"),
          _,
          localEnv
        )
      )
      assert(b == 1)
      val t = root.resolve("d/ResearcherMetricCache")
      Files.createDirectories(t)
      Files.writeString(
        t.resolve("part-00000.jsonl"),
        Seq(rankingItem(Seq(ra -> 5L, rb -> 5L)), rankingItem(Seq(rb -> 5L, ra -> 5L)))
          .map(DynamoItemFile.renderLine)
          .mkString("\n"),
        UTF_8
      )
      val (d, out2) =
        capture(
          ValidateCli.run(
            Seq("--kind", "dynamodb", "--dir", root.resolve("d").toString),
            _,
            localEnv
          )
        )
      // Line 1 is ordered (tie by researcher ID); line 2 is not and repeats the key.
      assert(d == 1 && out2.contains("count ResearcherMetricCache 2"))
      assert(
        out2.exists(_.contains("ordered by value descending")) && out2.contains("  DUPLICATE_KEY 1")
      )
    }
  }

  private def dynamoDir(root: Path): Path =
    val d = root.resolve(s"curated/dynamodb/snapshot=$snapshot/run=$runId/ResearcherMetricCache")
    Files.createDirectories(d)
    Files.writeString(
      d.resolve("part-00000.jsonl"),
      DynamoItemFile.renderLine(rankingItem(Seq(ra -> 5L, rb -> 3L))) + "\n",
      UTF_8
    )
    d.getParent

  test("DynamoDB loader dry run: insert, registry skip, item-level recheck") {
    withTemp { root =>
      val dir = dynamoDir(root)
      val args = Seq("--dir", dir.toString, "--out", root.resolve("out").toString, "--passes", "2")
      val (c, out) = capture(LoadDynamoCli.run(args, _, localEnv))
      assert(c == 0, out)
      assert(
        out.exists(l =>
          l.startsWith("pass 1 ResearcherMetricCache") && l.contains("inserted=1") && l.contains(
            "registry=RECORDED"
          )
        )
      )
      assert(
        out.exists(l =>
          l.startsWith("pass 2 ResearcherMetricCache") && l.contains(
            "registry=SKIPPED_SAME_CONTENT"
          )
        )
      )
      val (c2, out2) = capture(LoadDynamoCli.run(args :+ "--recheck", _, localEnv))
      assert(c2 == 0)
      assert(
        out2.exists(l =>
          l.startsWith("pass 2") && l.contains("unchanged=1") && l.contains(
            "registry=ALREADY_RECORDED"
          )
        )
      )
    }
  }

  test(
    "live opt-in: --profile needs --live, live loads need a non-local config"
  ) {
    withTemp { root =>
      val dir = dynamoDir(root).toString
      def code(extra: String*) = capture(LoadDynamoCli.run(Seq("--dir", dir) ++ extra, _, localEnv))
      assert(code("--profile", "p")._1 == Cli.Usage)
      val (c, out) = code("--live", "--profile", "p", "--table-prefix", "x-")
      assert(c == Cli.Usage && out.exists(_.contains("non-local environment")))
      val (e, out2) = capture(
        EmbedCli.run(Seq("--input", root.toString, "--live"), _, nowMs = () => 0L, env = localEnv)
      )
      assert(e == Cli.Usage && out2.exists(_.contains("missing --max")))
    }
  }

  test("embed (fake) then vector load and S3 manifest check, all idempotent on rerun") {
    withTemp { root =>
      val input = writeRendered(root.resolve("in"), profiles)
      val out = root.resolve("stores").toString
      def embed() = capture(
        EmbedCli.run(
          Seq("--input", input.toString, "--max", "10", "--out", out),
          _,
          _ => (),
          () => 0L,
          localEnv
        )
      )
      val (c, lines) = embed()
      assert(c == 0 && lines.exists(_.startsWith("status COMPLETE")), lines)
      val backup =
        Cli.files(root.resolve("stores/s3/vectors/backup"), ".jsonl").head.getParent.getParent
      def loadVectors() =
        capture(LoadVectorsCli.run(Seq("--input", backup.toString, "--out", out), _, localEnv))
      val (v1, l1) = loadVectors()
      assert(
        v1 == 0 && l1.exists(_.startsWith("researcher-profile-v1 written 3 skipped 0 rejected 0")),
        l1
      )
      val (v2, l2) = loadVectors()
      assert(
        v2 == 0 && l2.exists(_.startsWith("researcher-profile-v1 written 0 skipped 3 rejected 0")),
        l2
      )
      val manifest = Cli.files(root.resolve("stores/s3/runs/embed"), ".json").head
      val key = root.resolve("stores/s3").relativize(manifest).toString
      val (s, l3) = capture(
        LoadS3Cli.run(
          Seq("--manifest", key, "--root", root.resolve("stores/s3").toString),
          _,
          localEnv
        )
      )
      assert(s == 0 && l3.exists(_.contains("verified locally")), l3)
      val (c2, again) = embed()
      assert(c2 == 0 && again.exists(_.contains("embedded 0")))
    }
  }

  test("M1: any Throwable, including OutOfMemoryError, exits 1 with a message; never success") {
    def exit(f: () => Int) = capture(out => Cli.exitCode("X", Nil, out)((_, _) => f()))
    val (oom, out) = exit(() => throw new OutOfMemoryError("Java heap space"))
    assert(oom == 1 && out == Vector("X failed: OutOfMemoryError: Java heap space"))
    assert(exit(() => throw new StackOverflowError())._1 == 1)
    assert(exit(() => throw IllegalStateException("x"))._1 == 1)
    assert(exit(() => 0)._1 == 0 && exit(() => 3)._1 == 3)
  }

  test("M8: CLIs read the environment they are given, not the shell's") {
    withTemp { root =>
      val dir = dynamoDir(root).toString
      val args = Seq("--dir", dir, "--live", "--profile", "p", "--table-prefix", "x-")
      // A non-local environment with placeholder names is refused before any client is built.
      val (c, out) =
        capture(LoadDynamoCli.run(args, _, Map("SCHOLARCHAIN_ENVIRONMENT" -> "prod")))
      assert(c == Cli.Usage && out.exists(_.contains("still uses placeholder values")), out)
      val (l, out2) = capture(LoadDynamoCli.run(args, _, localEnv))
      assert(l == Cli.Usage && out2.exists(_.contains("non-local environment")), out2)
    }
  }

  test("M3: the live Neptune pre-load check compares SHA-256 hashes with the run manifest") {
    val prefix = s"graph/neptune/snapshot=$snapshot/run=$runId/"
    val (a, b) = (prefix + "vertices/work/part-00000.csv", prefix + "edges/authored/part-00000.csv")
    val (ha, hb) = (Hashing.sha256("a"), Hashing.sha256("b"))
    val m = RunManifest(
      RunManifest.SchemaVersion,
      "store-conversion",
      runId,
      snapshot,
      Vector(ManifestEntry(a, ha, 1, "src"), ManifestEntry(b, hb, 1, "src"))
    )
    val local = Map(a -> ha, b -> hb)
    val remote = Map(a -> ha, b -> hb)
    def check(l: Map[String, String] = local, r: Map[String, String] = remote) =
      LoadNeptuneCli.preloadProblems(prefix, runId, m, l, r.keys.toVector, r.get)
    assert(check().isEmpty)
    // Same names, different bytes in the bucket: refused (a names-only check would pass).
    assert(check(r = remote.updated(b, ha)).exists(_.startsWith(s"live $b has checksum")))
    assert(check(r = remote - b).exists(_.startsWith("live bucket: 1 manifest objects missing")))
    assert(check(r = remote + (prefix + "x.csv" -> ha)).exists(_.contains("1 not in the manifest")))
    assert(check(l = local.updated(a, hb)).exists(_.startsWith(s"local $a is not")))
    assert(check(r = remote.updated(a, "")).nonEmpty) // no full-object checksum stored
    assert(
      LoadNeptuneCli
        .preloadProblems(prefix, otherRunId, m, local, remote.keys.toVector, remote.get)
        .exists(_.contains("is not"))
    )
    // S3 ChecksumSHA256 (base64) to the manifest form; composite multipart values are not hashes.
    val b64 = java.util.Base64.getEncoder.encodeToString(
      java.security.MessageDigest.getInstance("SHA-256").digest("a".getBytes(UTF_8))
    )
    assert(scholarchain.storage.live.S3Requests.sha256Of(b64).contains(ha))
    assert(scholarchain.storage.live.S3Requests.sha256Of(b64 + "-3").isEmpty)
    assert(scholarchain.storage.live.S3Requests.sha256Of(null).isEmpty)
    val head = scholarchain.storage.live.S3Requests.head("bkt", a)
    assert(head.checksumModeAsString == "ENABLED" && head.bucket == "bkt")
  }

  test("N6: validator exits 1 when evidence objects are left untraced (no --evidence-root)") {
    import NeptuneContract.*
    val works = (1 to 21).map(i => f"dblp:conf/test/L$i%02d").toVector
    val inline = coauthor(ra, rb, works)
    val ref = S3Layout.coauthorEvidence(snapshot, runId, "all-years", inline.id)
    val e =
      inline.copy(props = inline.props - "supportingWorkIds" + ("supportingWorkIdsRef" -> ref))
    val g = graph
      .updated(Work, graph(Work) ++ works.map(w => vertex(Work, w, "dblpAnchored" -> true)))
      .updated(
        Authored,
        graph(Authored) ++ (for r <- Vector(ra, rb); w <- works
        yield edge(Authored, r, w, "position" -> 1))
      )
      .updated(CoauthoredWith, Vector(e))
    withTemp { root =>
      writeGraph(root.resolve("g"), g)
      val objects = FileObjectStore(root.resolve("s3"))
      val ev = CoauthorEvidence(EvidenceSchemaVersion, e.id, "all-years", works, runId, snapshot)
      objects.loader.putNew(ref, upickle.default.write(ev).getBytes(UTF_8))
      val args = Seq("--kind", "neptune", "--dir", root.resolve("g").toString)
      val (untraced, out) = capture(ValidateCli.run(args, _, localEnv))
      assert(untraced == 1 && out.contains("evidence edges-object-not-opened 1"))
      assert(out.exists(_.startsWith("INCOMPLETE: 1 edges")) && !out.contains("OK"))
      val (traced, out2) = capture(
        ValidateCli.run(args ++ Seq("--evidence-root", root.resolve("s3").toString), _, localEnv)
      )
      assert(traced == 0 && out2.contains("evidence edges-object 1") && out2.contains("OK"))
    }
  }

  test("M10: the live table prefix and loader role default to configuration; flags override") {
    val none = Args(Map.empty, Set.empty)
    assert(LoadDynamoCli.tablePrefix(none, config) == config.dynamoDb.tablePrefix)
    assert(LoadDynamoCli.tablePrefix(Args(Map("table-prefix" -> "p-"), Set.empty), config) == "p-")
    assert(LoadNeptuneCli.loaderRoleArn(none, config) == config.neptune.loaderRoleArn)
    val arn = "arn:aws:iam::123456789012:role/loader"
    assert(
      LoadNeptuneCli.loaderRoleArn(Args(Map("loader-role-arn" -> arn), Set.empty), config) == arn
    )
  }

  // ---- Phase 12 work order 5: the in-VPC loader ------------------------------------------------

  test("WO5: --live without --profile selects the SDK default chain in every live loader CLI") {
    import scholarchain.storage.live.{LiveAws, LiveTarget}
    val live = Args(Map.empty, Set("live"))
    val t = Cli.liveTarget(live, "us-east-2", profileOptional = true)
    assert(t == Right(Some(LiveTarget(None, "us-east-2"))))
    assert(
      LiveAws
        .provider(t.toOption.flatten.get)
        .isInstanceOf[software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider]
    )
    val named = Args(Map("profile" -> "p", "region" -> "eu-west-1"), Set("live"))
    assert(
      Cli.liveTarget(named, "us-east-2", profileOptional = true) ==
        Right(Some(LiveTarget(Some("p"), "eu-west-1")))
    )
    // EmbedCli and the rag CLIs keep the strict form.
    assert(Cli.liveTarget(live, "us-east-2") == Left("--live needs --profile <name>"))
    // Each loader accepts --live alone; the local configuration then refuses it before any client.
    withTemp { root =>
      val vectors = root.resolve(s"v/snapshot=$snapshot/run=$runId").toString
      val runs = Vector(
        LoadS3Cli.run(Seq("--manifest", "runs/x/manifest.json", "--live"), _, localEnv),
        LoadDynamoCli.run(Seq("--dir", dynamoDir(root).toString, "--live"), _, localEnv),
        LoadVectorsCli.run(Seq("--input", vectors, "--live"), _, localEnv),
        LoadNeptuneCli.run(Seq("--dir", "x", "--live"), _, localEnv),
        LoadNeptuneCli.run(Seq("--live", "--status", "load-1"), _, localEnv)
      )
      runs.foreach { r =>
        val (c, out) = capture(r)
        assert(c == Cli.Usage && out.exists(_.contains("non-local environment")), out)
        assert(!out.exists(_.contains("needs --profile")), out)
      }
    }
  }

  private val stagePrefix = S3Layout.neptuneRoot(snapshot, runId) + "/"
  private val stageManifestKey = S3Layout.runManifest("store-conversion", snapshot, runId)
  private val stageObjects = Vector(
    stagePrefix + "vertices/researcher/part-00000.csv" -> "id,label\n",
    stagePrefix + "edges/authored/part-00000.csv" -> "from,to\n",
    S3Layout.coauthorEvidence(snapshot, runId, "all-years", "cae-1") -> "{}",
    S3Layout.dynamoItems(snapshot, runId, "ResearcherMetricCache") + "/part-00000.jsonl" -> "{}\n"
  )

  /** A fake bucket holding stageObjects (except the omitted keys, and with overrides of their
    * bytes) and the store-conversion manifest of all of them.
    */
  private def stageBucket(
      root: Path,
      omit: Set[String] = Set.empty,
      tamper: Map[String, String] = Map.empty
  ): (ObjectReadPort, RunManifest) =
    val bucket = FileObjectStore(root.resolve("bucket"))
    val m = RunManifest(
      RunManifest.SchemaVersion,
      "store-conversion",
      runId,
      snapshot,
      stageObjects.map((k, v) => ManifestEntry(k, Hashing.sha256(v), v.length.toLong, "src"))
    )
    stageObjects.foreach((k, v) =>
      if !omit(k) then bucket.loader.putNew(k, tamper.getOrElse(k, v).getBytes(UTF_8))
    )
    bucket.loader.putNew(stageManifestKey, RunManifest.toJson(m))
    (bucket.reader, m)

  private def stage(
      remote: ObjectReadPort,
      s3root: Path,
      prefix: String = stagePrefix,
      now: () => Long = () => 0L
  ): (Vector[String], Vector[String]) =
    val lines = mutable.ArrayBuffer.empty[String]
    val problems = LoadNeptuneCli.stageFromBucket(
      remote,
      s3root,
      snapshot,
      runId,
      prefix,
      lines += _,
      timeoutMs = 1000,
      now = now,
      sleep = _ => ()
    )
    (problems, lines.toVector)

  test("WO5 stage-from-bucket: the staged copy matches the manifest; a rerun is unchanged") {
    withTemp { root =>
      val (bucket, m) = stageBucket(root)
      val s3 = root.resolve("out/s3")
      val (problems, out) = stage(bucket, s3)
      assert(problems.isEmpty, out)
      assert(out.head == s"stage: 3 objects, 19 bytes (from $stageManifestKey) into $s3", out)
      val staged = FileObjectStore(s3).reader
      // The graph prefix, the run's coauthor evidence, and the manifest; nothing else (no dynamodb).
      assert(staged.list("") == (stageObjects.take(3).map(_._1) :+ stageManifestKey).sorted)
      m.entries
        .take(3)
        .foreach(e => assert(staged.get(e.key).map(Hashing.sha256).contains(e.sha256)))
      assert(staged.get(stageManifestKey).map(RunManifest.fromJson).contains(m))
      assert(stage(bucket, s3)._1.isEmpty) // write-once: identical objects are Unchanged
    }
  }

  test("WO5 stage-from-bucket: a sha256 mismatch is refused and not written") {
    withTemp { root =>
      val bad = stageObjects(1)._1
      val (bucket, _) = stageBucket(root, tamper = Map(bad -> "FROM,TO\n")) // same size
      val s3 = root.resolve("out/s3")
      val (problems, out) = stage(bucket, s3)
      assert(problems.size == 1 && problems.head.startsWith(s"$bad is sha256:"), out)
      assert(problems.head.contains(s"manifest ${Hashing.sha256("from,to\n")}"))
      val staged = FileObjectStore(s3).reader
      assert(staged.get(bad).isEmpty && staged.get(stageManifestKey).isEmpty)
      // A local copy with other bytes is never overwritten either.
      val (good, _) = stageBucket(root.resolve("2"))
      FileObjectStore(s3).loader.putNew(bad, "FROM,TO\n".getBytes(UTF_8))
      assert(stage(good, s3)._1.exists(_.startsWith(s"local $bad has")))
    }
  }

  test("WO5 stage-from-bucket: a missing object, a missing manifest, a wrong --dir and a timeout") {
    withTemp { root =>
      val gone = stageObjects(2)._1
      val (bucket, _) = stageBucket(root, omit = Set(gone))
      val s3 = root.resolve("out/s3")
      val (problems, out) = stage(bucket, s3)
      assert(problems == Vector(s"$gone is missing from the bucket"), out)
      assert(FileObjectStore(s3).reader.get(stageManifestKey).isEmpty)
      val empty = FileObjectStore(root.resolve("none")).reader
      assert(stage(empty, s3)._1 == Vector(s"no run manifest at $stageManifestKey in the bucket"))
      val other = S3Layout.neptuneRoot(snapshot, otherRunId) + "/"
      assert(stage(bucket, s3, prefix = other)._1.head.startsWith("--dir must be"))
      var t = 0L
      val (slow, _) = stage(
        stageBucket(root.resolve("3"))._1,
        root.resolve("o3"),
        now = () => {
          t += 600; t
        }
      )
      assert(slow == Vector("timeout after 1 of 3 objects (1 s)"), slow)
    }
  }

  test("WO5 stage-from-bucket needs --live and is not used with --status") {
    val (c, out) =
      capture(LoadNeptuneCli.run(Seq("--dir", "x", "--stage-from-bucket"), _, localEnv))
    assert(c == Cli.Usage && out.exists(_.contains("--stage-from-bucket needs --live")), out)
    val (s, out2) = capture(
      LoadNeptuneCli.run(Seq("--live", "--status", "l", "--stage-from-bucket"), _, localEnv)
    )
    assert(s == Cli.Usage && out2.exists(_.contains("--status needs no staging")), out2)
  }
