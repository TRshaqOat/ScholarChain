package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader
import scholarchain.core.{Provenance, SchemaVersions, SourceType, StableIds}
import scholarchain.storage.*
import scholarchain.tests.support.StoreWorld

import java.nio.charset.StandardCharsets.UTF_8

/** Phase 11 WO3, ruling 1 target "window-summary object tampering". The corpus window-summary
  * object is the only source of corpus-level counts the runtime states as VERIFIED facts
  * (STORAGE.md "Window summaries"); a tampered object must be refused as a whole.
  */
class WindowSummaryTamperAdversarialSpec extends AnyFunSuite:
  private val cfg = ConfigLoader.load(StoreWorld.baseEnv).fold(e => sys.error(e.mkString), identity)
  private val windows = cfg.graph.windows
  private val snapshot = StoreWorld.snapshot
  private val run = StableIds.runId("adv-window-summary", "c", "config-v1", Seq("s"), 1)
  private val loadRun = StableIds.runId("adv-load-window-summary", "c", "config-v1", Seq("l"), 1)
  private val key = S3Layout.windowSummary(snapshot, loadRun)

  private def row(
      win: String,
      r: Long,
      comps: Long,
      lc: Long,
      single: Long,
      comms: Long,
      lcm: Long,
      sc: Long,
      atLeast: Long
  ) =
    val tw = windows.find(_.name == win).get
    WindowSummary(
      win,
      tw.startYear,
      tw.endYear,
      r,
      r * 2,
      r * 3,
      comps,
      lc,
      single,
      comms,
      lcm,
      sc,
      atLeast,
      Some("comp-" + StableIds.hash("component", win)),
      Some(StableIds.communityId("graph-v1", win, tw.startYear, tw.endYear, StoreWorld.ada)),
      tw.endYear - tw.startYear + 1,
      Some(lc.toDouble / r.toDouble),
      Some(lcm.toDouble / r.toDouble),
      3,
      false,
      10,
      "researchers with at least one synthetic publication in the window",
      WindowSummarySourceRuns(run, run, run, run, run, "er-v2"),
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        win,
        snapshot,
        run,
        Some("graph-v1"),
        None
      ),
      WindowSummaries.SchemaVersion
    )
  private val rows = Vector(
    row("all-years", 1200, 45, 900, 28, 130, 60, 31, 64),
    row("2000-2014", 400, 33, 252, 13, 71, 21, 11, 37),
    row("2015-2026", 800, 22, 541, 9, 90, 40, 12, 47)
  )
  private def bytes(rs: Seq[WindowSummary]) = WindowSummaries.render(rs)
  private def lines = new String(bytes(rows), UTF_8).split('\n').toVector
  private def parse(b: Array[Byte], k: String = key) = WindowSummaries.parse(k, b, windows)
  private def codes(b: Array[Byte], k: String = key) =
    parse(b, k).left.toOption.toVector.flatten.map(_.code).toSet

  test("control: the untampered object opens and answers every configured window") {
    val port = WindowSummaries.open(
      new ObjectReadPort:
        def get(k: String) = Option.when(k == key)(bytes(rows))
        def list(prefix: String) = Vector.empty
      ,
      key,
      windows
    )
    assert(port.isRight, port)
    windows.foreach(w => assert(port.toOption.get.windowSummary(w.name).exists(_.window == w.name)))
  }

  test("a changed count, fraction, ID, or provenance field refuses the whole object") {
    val first = rows.head
    val tampered = Vector(
      "fraction off by 1e-9" -> first.copy(largestComponentFraction =
        first.largestComponentFraction.map(_ + 1e-9)
      ),
      "more components than researchers" -> first.copy(components = 1201),
      "negative singleton count" -> first.copy(singletonResearchers = -1),
      "largest component above researchers" -> first
        .copy(largestComponentSize = 1201, largestComponentFraction = Some(1201.0 / 1200)),
      "edges above joint publications" -> first.copy(coauthorEdges =
        first.sumJointPublicationCount + 1
      ),
      "component ID shape" -> first.copy(largestComponentId =
        Some("comp-SYNTHETIC'; DROP TABLE x")
      ),
      "community ID missing" -> first.copy(largestCommunityId = None),
      "window years moved" -> first.copy(windowStartYear = 1990, years = 37),
      "years inconsistent" -> first.copy(years = 99),
      "schema version" -> first.copy(schemaVersion = "spark-corpus-window-summary-v0"),
      "provenance not DERIVED" -> first.copy(provenance =
        first.provenance.copy(sourceDataset = SourceType.DBLP)
      ),
      "provenance model version" -> first.copy(provenance =
        first.provenance.copy(modelVersion = Some("haiku"))
      ),
      "blank node basis" -> first.copy(nodeBasis = "  "),
      "bad source run" -> first.copy(sourceRuns =
        first.sourceRuns.copy(canonicalGraphRunId = "run-x")
      ),
      "min size zero" -> first.copy(communityMinSize = 0)
    )
    tampered.foreach { (name, t) =>
      val c = codes(bytes(t +: rows.tail))
      assert(c.contains("BAD_ROW"), s"$name was accepted: $c")
    }
  }

  test(
    "counts no coauthorship graph can have are refused (largest component plus the other components exceed the researchers)"
  ) {
    // 1200 researchers, the largest component holds 900, so at most 301 components exist. A tamper
    // that keeps each simple bound (components <= researchers, singletons <= components) but
    // breaks this identity states a count no graph can have; the runtime would serve it as a
    // VERIFIED corpus fact.
    val first = rows.head
    val impossible = Vector(
      "components" -> first.copy(components = 1000, singletonResearchers = 28),
      "communities" -> first.copy(communities = 1100, singletonCommunities = 31),
      "communities at least the minimum size above what the largest community allows" ->
        first.copy(
          largestCommunitySize = 2,
          largestCommunityFraction = Some(2.0 / 1200),
          communitiesAtLeastMinSize = 64
        )
    )
    val accepted = impossible.filter((_, t) => parse(bytes(t +: rows.tail)).isRight).map(_._1)
    assert(accepted.isEmpty, s"impossible counts accepted: ${accepted.mkString("; ")}")
    // Work order 4: each is refused as a bad row (not by some unrelated check), and so are the edge
    // and overflow shapes repair-01 Part E added, on the other windows too.
    val more = impossible ++ Vector(
      "fewer edges than a spanning forest" -> first.copy(coauthorEdges = 1000),
      "more edges than the degree bound allows" -> rows(1).copy(
        coauthorEdges = 60000,
        sumJointPublicationCount = 70000
      ),
      "communities near Long.MaxValue" -> rows(2).copy(
        communities = Long.MaxValue,
        singletonCommunities = Long.MaxValue - 1
      ),
      "an at-least-min-size count that wraps when multiplied" -> rows(2).copy(
        communitiesAtLeastMinSize = Long.MaxValue / 2
      )
    )
    more.foreach { (name, t) =>
      val all = rows.map(r => if r.window == t.window then t else r)
      val c = codes(bytes(all))
      assert(c.contains("BAD_ROW"), s"$name: $c")
    }
    // Control: the three real-shaped rows still open together.
    assert(parse(bytes(rows)).isRight)
  }

  test(
    "structural tampering: extra or missing fields, injected text, duplicates, mixed runs, truncation"
  ) {
    val l = lines
    val injected = l.head.replace(
      "\"schemaVersion\"",
      s"\"note\":\"${StoreWorld.Injection.replace("\"", "'")}\",\"schemaVersion\""
    )
    assert(codes((injected +: l.tail).mkString("\n").getBytes(UTF_8)).contains("MALFORMED"))
    val missing = ujson.read(l.head).obj
    missing.remove("communityRounds")
    assert(codes((missing.render() +: l.tail).mkString("\n").getBytes(UTF_8)).contains("MALFORMED"))
    assert(codes((l :+ l.head).mkString("\n").getBytes(UTF_8)).contains("DUPLICATE_WINDOW"))
    assert(codes(l.tail.mkString("\n").getBytes(UTF_8)).contains("MISSING_WINDOW"))
    val otherRun = StableIds.runId("adv-other", "c", "config-v1", Seq("o"), 1)
    val mixed =
      rows.head.copy(provenance = rows.head.provenance.copy(runId = otherRun)) +: rows.tail
    assert(codes(bytes(mixed)).contains("PROVENANCE"))
    assert(codes(bytes(rows).dropRight(40)).nonEmpty, "a truncated object was accepted")
    assert(codes(Array.emptyByteArray).contains("MISSING_WINDOW"))
    assert(codes("not json at all\n".getBytes(UTF_8)).contains("MALFORMED"))
    // The key's snapshot must be the rows' snapshot, and the key must be under the prefix.
    assert(codes(bytes(rows), S3Layout.windowSummary("2026-01-01", loadRun)).contains("PROVENANCE"))
    assert(
      codes(bytes(rows), "curated/other/" + key.stripPrefix(WindowSummaries.KeyPrefix))
        .contains("BAD_KEY")
    )
    // A window name not in the configuration is refused, even when its years are plausible.
    val renamed = rows.head.copy(
      window = "all-years-tampered",
      provenance = rows.head.provenance.copy(sourceRecordId = "all-years-tampered")
    )
    assert(codes(bytes(renamed +: rows.tail)).nonEmpty)
  }

  test("a missing object refuses to open; nothing is half-opened") {
    val none = new ObjectReadPort:
      def get(k: String) = None
      def list(prefix: String) = Vector.empty
    assert(
      WindowSummaries
        .open(none, key, windows)
        .left
        .toOption
        .exists(_.exists(_.code == "OBJECT_MISSING"))
    )
  }
