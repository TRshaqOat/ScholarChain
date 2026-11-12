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
    "live opt-in: --live needs --profile, --profile needs --live, live loads need a non-local config"
  ) {
    withTemp { root =>
      val dir = dynamoDir(root).toString
      def code(extra: String*) = capture(LoadDynamoCli.run(Seq("--dir", dir) ++ extra, _, localEnv))
      assert(code("--live")._2.exists(_.contains("--live needs --profile")))
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
