package scholarchain.storage

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.storage.cli.LoadWindowSummaryCli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable

class WindowSummarySpec extends AnyFunSuite:
  import Fixtures.*

  private val compId = "comp-" + "a" * 32
  private val commId = "comm-" + "b" * 32

  /** A Spark CorpusWindowSummaryJob `_manifest.json` in the shape of the Phase 10 work order 7 run:
    * two populated windows and one empty window, whose null IDs and fractions are left out as Spark
    * row JSON leaves them out.
    */
  private def sparkManifest(): ujson.Obj =
    val rows = windows.map { w =>
      val empty = w.name == windows.last.name
      val r = ujson.Obj(
        "window" -> w.name,
        "windowStartYear" -> w.startYear,
        "windowEndYear" -> w.endYear,
        "researchers" -> (if empty then 0 else 10),
        "coauthorEdges" -> (if empty then 0 else 6),
        "sumJointPublicationCount" -> (if empty then 0 else 8),
        "components" -> (if empty then 0 else 4),
        "largestComponentSize" -> (if empty then 0 else 5),
        "singletonResearchers" -> (if empty then 0 else 2),
        "communities" -> (if empty then 0 else 5),
        "largestCommunitySize" -> (if empty then 0 else 3),
        "singletonCommunities" -> (if empty then 0 else 2),
        "communitiesAtLeastMinSize" -> (if empty then 0 else 1),
        "years" -> (w.endYear - w.startYear + 1),
        "communityMinSize" -> 3,
        "communityConverged" -> false,
        "communityRounds" -> 10,
        "schemaVersion" -> WindowSummaries.SchemaVersion
      )
      if !empty then
        r("largestComponentId") = compId
        r("largestCommunityId") = commId
        r("largestComponentFraction") = 0.5
        r("largestCommunityFraction") = 0.3
      r
    }
    ujson.Obj(
      "schemaVersion" -> "spark-run-manifest-v1",
      "stage" -> WindowSummaries.SparkStage,
      "runId" -> runId,
      "snapshot" -> snapshot,
      "sourceRuns" -> ujson.Obj(
        "temporalSummaryExportRunId" -> otherRunId,
        "communityPathRunId" -> otherRunId,
        "researcherAnalyticsRunId" -> otherRunId,
        "canonicalGraphRunId" -> otherRunId,
        "inputHadoopRunId" -> otherRunId,
        "matchPolicyVersion" -> "er-v2"
      ),
      "settings" -> ujson.Obj("graphPolicy" -> policy, "nodeBasis" -> "DBLP-authored researchers"),
      "tables" -> ujson.Arr(
        ujson.Obj(
          "name" -> WindowSummaries.SparkTable,
          "schemaVersion" -> WindowSummaries.SchemaVersion,
          "schema" -> WindowSummaries.SparkSchema,
          "rowCount" -> rows.size
        )
      ),
      "counts" -> ujson.Obj(
        "windowSummary" -> ujson.Arr.from(rows),
        "integrityViolations" -> ujson.Arr()
      )
    )

  private def rows(): Vector[WindowSummary] =
    WindowSummaries
      .fromSparkManifest("m", sparkManifest(), windows)
      .fold(vs => fail(vs.toString), identity)

  private val key = S3Layout.windowSummary(snapshot, otherRunId)

  private def codes(r: Either[Vector[Violation], ?]): Set[String] =
    r.left.getOrElse(fail("expected violations")).map(_.code).toSet

  test("rows from a Spark manifest carry the rebuilt provenance, nodeBasis and sourceRuns") {
    val got = rows()
    assert(got.map(_.window).toSet == windows.map(_.name).toSet)
    got.foreach { s =>
      assert(s.provenance.runId == runId && s.provenance.snapshot == snapshot)
      assert(s.provenance.sourceRecordId == s.window && s.provenance.policyVersion.contains(policy))
      assert(s.provenance.modelVersion.isEmpty && s.nodeBasis == "DBLP-authored researchers")
      assert(s.sourceRuns.matchPolicyVersion == "er-v2")
    }
    val empty = got.find(_.window == windows.last.name).get
    assert(empty.largestComponentId.isEmpty && empty.largestComponentFraction.isEmpty)
    assert(WindowSummaries.Fields.size == 25 && WindowSummaries.Fields("provenance"))
  }

  test("the port reads the store object back unchanged; a reload is Unchanged") {
    withTemp { dir =>
      val store = FileObjectStore(dir)
      val bytes = WindowSummaries.render(rows())
      assert(store.loader.putNew(key, bytes) == Right(PutObjectOutcome.Inserted))
      assert(
        store.loader.putNew(key, WindowSummaries.render(rows().reverse)) ==
          Right(PutObjectOutcome.Unchanged)
      )
      val port =
        WindowSummaries.open(store.reader, key, windows).fold(vs => fail(vs.toString), identity)
      rows().foreach(s => assert(port.windowSummary(s.window).contains(s)))
      assert(port.windowSummary("all").isEmpty) // the caller maps the benchmark alias
      assert(
        codes(
          WindowSummaries.open(store.reader, S3Layout.windowSummary(snapshot, runId), windows)
        ) ==
          Set("OBJECT_MISSING")
      )
    }
  }

  test("malformed rows, duplicate or missing windows, and mixed runs refuse the whole object") {
    val good = rows()
    val lines = good.map(upickle.default.write(_))
    def parse(ls: Seq[String]) =
      WindowSummaries.parse(key, ls.mkString("\n").getBytes(UTF_8), windows)
    def obj(s: WindowSummary) = ujson.read(upickle.default.write(s)).obj
    val extra = obj(good.head); extra("text") = "long text"
    val missing = obj(good.head); missing.remove("nodeBasis")
    assert(parse(lines).isRight)
    assert(codes(parse(lines.updated(0, "{not json"))) == Set("MALFORMED", "MISSING_WINDOW"))
    assert(codes(parse(lines.updated(0, extra.render()))) == Set("MALFORMED", "MISSING_WINDOW"))
    assert(codes(parse(lines.updated(0, missing.render()))) == Set("MALFORMED", "MISSING_WINDOW"))
    def withRow(s: WindowSummary) = lines.updated(0, upickle.default.write(s))
    val g = good.head
    Seq(
      g.copy(schemaVersion = "spark-corpus-window-summary-v2"),
      g.copy(windowEndYear = g.windowEndYear + 1),
      g.copy(largestComponentFraction = Some(0.9)),
      g.copy(largestComponentId = Some("comp-x")),
      g.copy(singletonResearchers = g.components + 1),
      g.copy(provenance = g.provenance.copy(sourceRecordId = "other")),
      g.copy(provenance = g.provenance.copy(modelVersion = Some("m"))),
      g.copy(sourceRuns = g.sourceRuns.copy(canonicalGraphRunId = "run-1"))
    ).foreach(s => assert(codes(parse(withRow(s))) == Set("BAD_ROW"), s))
    assert(codes(parse(lines :+ lines.head)) == Set("DUPLICATE_WINDOW"))
    assert(codes(parse(lines.drop(1))) == Set("MISSING_WINDOW"))
    assert(
      codes(parse(withRow(g.copy(provenance = g.provenance.copy(runId = otherRunId))))) ==
        Set("PROVENANCE")
    )
    val wrongSnapshot = S3Layout.windowSummary("2026-01-01", otherRunId)
    assert(
      codes(WindowSummaries.parse(wrongSnapshot, lines.mkString("\n").getBytes(UTF_8), windows)) ==
        Set("PROVENANCE")
    )
    val wrongPrefix = s"curated/other/snapshot=$snapshot/run=$otherRunId/part-00000.jsonl"
    assert(
      codes(WindowSummaries.parse(wrongPrefix, lines.mkString("\n").getBytes(UTF_8), windows)) ==
        Set("BAD_KEY")
    )
  }

  test("phase 11 D10: counts no graph can have are refused; real-shaped counts still open") {
    val good = rows()
    val lines = good.map(upickle.default.write(_))
    def rowCodes(s: WindowSummary) =
      WindowSummaries
        .parse(
          key,
          lines.updated(0, upickle.default.write(s)).mkString("\n").getBytes(UTF_8),
          windows
        )
        .left
        .toOption
        .toVector
        .flatten
    // The WO3 control row: 1,200 researchers, 45 components (largest 900, 28 singletons), 130
    // communities (largest 60, 31 singletons), 64 of at least 3.
    def sized(lc: Long, lcm: Long) =
      good.head.copy(
        researchers = 1200,
        coauthorEdges = 2400,
        sumJointPublicationCount = 3600,
        components = 45,
        largestComponentSize = lc,
        singletonResearchers = 28,
        communities = 130,
        largestCommunitySize = lcm,
        singletonCommunities = 31,
        communitiesAtLeastMinSize = 64,
        largestComponentFraction = Some(lc.toDouble / 1200),
        largestCommunityFraction = Some(lcm.toDouble / 1200)
      )
    val control = sized(900, 60)
    assert(rowCodes(control).isEmpty)
    // the fixture row is a forest (edges = researchers - components); a clique of 5 plus 5
    // singletons (edges at the degree bound) is real too
    val g = good.head
    assert(rowCodes(g).isEmpty && g.coauthorEdges == g.researchers - g.components)
    assert(
      rowCodes(
        g.copy(
          coauthorEdges = 10,
          sumJointPublicationCount = 10,
          components = 6,
          singletonResearchers = 5
        )
      ).isEmpty
    )
    val impossible = Vector(
      // the three WO3 failure inputs
      "largest component 900 with 1,000 components" -> control.copy(components = 1000),
      "1,100 communities with a largest of 60" -> control.copy(communities = 1100),
      "64 communities of at least 3 when the largest has 2" -> sized(900, 2),
      // the work order's identities one by one, and the edge bounds
      "largest component above researchers - (components - 1)" ->
        control.copy(components = 302, singletonResearchers = 0),
      "largest community above researchers - (communities - 1)" ->
        control.copy(communities = 1142, singletonCommunities = 1000),
      // each of these community partitions is possible; only communitiesAtLeastMinSize is not
      "communitiesAtLeastMinSize x communityMinSize above researchers" -> sized(900, 3)
        .copy(communities = 500, singletonCommunities = 0, communitiesAtLeastMinSize = 401),
      "communitiesAtLeastMinSize > 0 when the largest community is below the minimum" ->
        sized(900, 2).copy(communities = 650, singletonCommunities = 100),
      "more communities of at least 3 than communities of two or more" ->
        control.copy(communitiesAtLeastMinSize = 100),
      "fewer edges than a spanning forest" -> control.copy(coauthorEdges = 1154),
      "more edges than the degree bound allows" ->
        g.copy(coauthorEdges = 17, sumJointPublicationCount = 17),
      "overflowing counts" -> control.copy(
        coauthorEdges = Long.MaxValue,
        sumJointPublicationCount = Long.MaxValue
      )
    )
    impossible.foreach { (name, s) =>
      val vs = rowCodes(s)
      assert(vs.nonEmpty && vs.forall(_.code == "BAD_ROW"), s"$name: $vs")
    }
  }

  test("a manifest with integrity violations, another schema, or another row count is refused") {
    def from(m: ujson.Obj) = WindowSummaries.fromSparkManifest("m", m, windows)
    val v = sparkManifest();
    v("counts")("integrityViolations") = ujson.Arr(ujson.Obj("check" -> "X"))
    assert(codes(from(v)) == Set("INTEGRITY_VIOLATIONS"))
    val schema = sparkManifest(); schema("tables")(0)("schema") = "window STRING"
    assert(codes(from(schema)) == Set("BAD_MANIFEST"))
    val count = sparkManifest(); count("tables")(0)("rowCount") = 4
    assert(codes(from(count)) == Set("BAD_MANIFEST"))
    val stage = sparkManifest(); stage("stage") = "other"
    assert(codes(from(stage)) == Set("BAD_MANIFEST"))
    val noRows = sparkManifest(); noRows("counts").obj.remove("windowSummary")
    assert(codes(from(noRows)) == Set("BAD_MANIFEST"))
  }

  test("the loader writes once under a new root, reloads as Unchanged, and refuses a conflict") {
    withTemp { dir =>
      val run = dir.resolve(s"spark/snapshot=$snapshot/run=$runId")
      Files.createDirectories(run)
      val manifest = run.resolve("_manifest.json")
      Files.writeString(manifest, sparkManifest().render(indent = 1))
      val before = Files.getLastModifiedTime(manifest)
      val out = dir.resolve("wo8")
      def load(): (Int, Vector[String]) =
        val lines = mutable.ArrayBuffer.empty[String]
        val code = LoadWindowSummaryCli.run(
          Seq("--spark-run", run.toString, "--out", out.toString),
          lines += _,
          Map.empty
        )
        (code, lines.toVector)
      val (c1, l1) = load()
      assert(c1 == 0 && l1.exists(_.endsWith(" Inserted")) && l1.contains("violations 0"), l1)
      val (c2, l2) = load()
      assert(c2 == 0 && l2.count(_.endsWith(" Unchanged")) == 2, l2)
      assert(Files.getLastModifiedTime(manifest) == before)
      val objectKey = l1.collectFirst { case s"object $k Inserted" => k }.get
      val port = WindowSummaries
        .open(FileObjectStore(out.resolve("s3")).reader, objectKey, windows)
        .fold(vs => fail(vs.toString), identity)
      assert(port.windowSummary(windows.head.name).map(_.researchers).contains(10L))
      // Different content under the same key is never overwritten.
      Files.writeString(out.resolve("s3").resolve(objectKey), "changed\n")
      val (c3, l3) = load()
      assert(c3 == 1 && l3.exists(l => l.startsWith("object ") && l.contains("Conflict")), l3)
      assert(Files.readString(out.resolve("s3").resolve(objectKey)) == "changed\n")
    }
  }

  test("the loader refuses a run directory whose manifest names another run") {
    withTemp { dir =>
      val run: Path = dir.resolve(s"spark/snapshot=$snapshot/run=$otherRunId")
      Files.createDirectories(run)
      Files.writeString(run.resolve("_manifest.json"), sparkManifest().render())
      val lines = mutable.ArrayBuffer.empty[String]
      val code = LoadWindowSummaryCli.run(
        Seq("--spark-run", run.toString, "--out", dir.resolve("wo8").toString),
        lines += _,
        Map.empty
      )
      assert(code == 2 && lines.exists(_.contains("differ from the path segments")), lines)
      assert(!Files.exists(dir.resolve("wo8")))
    }
  }
