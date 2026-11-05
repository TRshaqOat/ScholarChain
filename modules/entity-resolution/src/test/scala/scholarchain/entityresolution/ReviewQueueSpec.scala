package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{MatchEntityType, MatchOutcome}
import scholarchain.entityresolution.tools.{Csv, ReviewQueue}
import scholarchain.entityresolution.tools.ReviewQueue.Item

import java.nio.file.Files
import scala.util.Random

class ReviewQueueSpec extends AnyFunSuite:
  private val paths =
    Vector(MatchPath.EXACT_ID, MatchPath.DETERMINISTIC, MatchPath.PROBABILISTIC, MatchPath.LLM_BAND)
  private val items: Vector[Item] =
    (1 to 2000).toVector.map { i =>
      val p = paths(i % paths.size)
      val conf = (i % 100) / 100.0
      Item(
        MatchEntityType.RESEARCHER,
        s"dblp:conf/a/W$i#1",
        s"dblp:conf/b/W$i#2",
        p,
        ReviewQueue.band(conf, Thresholds(0.93, 0.35, 0.65, 0.93)),
        if i % 7 == 0 then "HOMONYM_SUFFIX" else if i % 5 == 0 then "COMMON_NAME" else "NONE",
        if conf >= 0.93 then MatchOutcome.ACCEPTED
        else if conf < 0.35 then MatchOutcome.REJECTED
        else MatchOutcome.UNRESOLVED,
        conf,
        Vector("er-v1:weighted-score"),
        Vector("SCORE_UNRESOLVED"),
        "run-" + "b" * 32
      )
    }

  test("selection is deterministic for a seed and independent of input order") {
    val a = ReviewQueue.select(items.iterator, 7L, 260)
    val b = ReviewQueue.select(Random(1).shuffle(items).iterator, 7L, 260)
    assert(a.size == 260 && a.toSet == b.toSet && a == b)
    assert(ReviewQueue.select(items.iterator, 8L, 260).toSet != a.toSet)
  }

  test("selection is stratified: every non-empty stratum is represented, none dominates") {
    val sel = ReviewQueue.select(items.iterator, 7L, 260)
    val strata = items.map(_.stratum).distinct
    assert(sel.map(_.stratum).distinct.size == strata.size)
    val perStratum = sel.groupBy(_.stratum).values.map(_.size)
    assert(
      perStratum.max - perStratum.min <= 1 || perStratum.min == items
        .groupBy(_.stratum)
        .values
        .map(_.size)
        .min
    )
    assert(
      ReviewQueue.select(items.take(10).iterator, 7L, 260).size == 10
    ) // fewer items than target
  }

  test(
    "the blind CSV shows evidence but never score, decision, path, or stratum; the key file does"
  ) {
    val sel = ReviewQueue.select(items.iterator, 7L, 30)
    val (blind, key) =
      ReviewQueue.exportCsv(sel, 7L, (_, id) => Map("name" -> s"Name of $id", "year" -> "2020"))
    val rows = Csv.records(blind)
    val header = Csv.parse(blind).head
    assert(rows.size == 30)
    Seq("score", "decision", "path", "stratum", "confidence", "band", "hard", "rule", "reason")
      .foreach(w => assert(!header.exists(_.contains(w)), w))
    assert(
      header.takeRight(2) == Vector("label", "notes") && rows.forall(r =>
        r("label").isEmpty && r("notes").isEmpty
      )
    )
    val cells = Csv.parse(blind).flatten.toSet
    sel.foreach { i =>
      assert(
        !cells.contains(i.stratum) && !cells.contains(i.confidence.toString) && !cells.contains(
          i.path.toString
        )
      )
    }
    assert(!blind.contains("er-v1:") && !blind.contains("SCORE_"))
    assert(rows.forall(r => r("a_name").startsWith("Name of ")))
    // Rows are ordered by row ID, not by stratum; the key file maps every row ID to its stratum.
    assert(rows.map(_("row_id")) == rows.map(_("row_id")).sorted)
    val k = Csv.records(key)
    assert(k.map(_("row_id")) == rows.map(_("row_id")))
    assert(
      k.forall(r =>
        r("stratum").nonEmpty && r("decision").nonEmpty && r("policy_version") == "er-v1"
      )
    )
  }

  test("runs of er-v1 or er-v2 are accepted; the strata file records each item's policy version") {
    import scholarchain.entityresolution.tools.runPolicyVersion
    assert(runPolicyVersion(ujson.Obj("matchPolicyVersion" -> "er-v1")) == "er-v1")
    assert(runPolicyVersion(ujson.Obj("matchPolicyVersion" -> "er-v2")) == "er-v2")
    assert(runPolicyVersion(ujson.Obj("policyVersion" -> "er-v2")) == "er-v2")
    assertThrows[IllegalArgumentException](runPolicyVersion(ujson.Obj("policyVersion" -> "er-v3")))
    assertThrows[IllegalArgumentException](runPolicyVersion(ujson.Obj("runId" -> "x")))
    val sel = ReviewQueue.select(items.iterator, 7L, 5).map(_.copy(policyVersion = "er-v2"))
    val (_, key) = ReviewQueue.exportCsv(sel, 7L, (_, _) => Map.empty)
    assert(Csv.records(key).map(_("policy_version")) == Vector.fill(5)("er-v2"))
  }

  test("re-keying keeps row IDs and pairs and takes every decision field from the new run") {
    val sel = ReviewQueue.select(items.iterator, 7L, 6)
    val (_, v1) = ReviewQueue.exportCsv(sel, 7L, (_, _) => Map.empty)
    val queued = Csv.records(v1)
    val changed = sel.head.copy(
      path = MatchPath.DETERMINISTIC,
      band = "B4_ACCEPT",
      decision = MatchOutcome.ACCEPTED,
      confidence = 1.0,
      rulesFired = Vector("er-v2:work-deterministic"),
      policyVersion = "er-v2"
    )
    val v2 = changed +: sel.tail.map(_.copy(policyVersion = "er-v2"))
    // The same pair in reversed ID order, and pairs never queued, are ignored.
    val noise = items.drop(1000).take(50) :+ sel(1).copy(
      sourceId = sel(1).targetId,
      targetId = sel(1).sourceId,
      decision = MatchOutcome.CONFLICT
    )
    val out =
      Csv.records(ReviewQueue.rekey(queued, Random(3).shuffle(v2 ++ noise).iterator).toOption.get)
    assert(
      out.map(r => (r("row_id"), r("a_id"), r("b_id"))) ==
        queued.map(r => (r("row_id"), r("a_id"), r("b_id")))
    )
    assert(out.forall(_("policy_version") == "er-v2"))
    assert(
      out.filter(_("a_id") == changed.sourceId).map(r => (r("decision"), r("path"), r("stratum")))
        == Vector(("ACCEPTED", "DETERMINISTIC", changed.stratum))
    )
    def rest(rs: Seq[Map[String, String]]) =
      rs.filter(_("a_id") != changed.sourceId).map(_ - "policy_version")
    assert(rest(out) == rest(queued))
    assert(ReviewQueue.rekey(queued, v2.tail.iterator).isLeft) // a queued pair has no decision
    assert(ReviewQueue.rekey(queued, (v2 :+ v2.head).iterator).isLeft) // or more than one
  }

  test("CSV round trip keeps commas, quotes, and newlines") {
    val cells = Vector("a,b", "say \"hi\"", "two\nlines", "")
    assert(Csv.parse(Csv.row(cells) + "\n") == Vector(cells))
  }

  test("one pair decided twice (exact and blocked, either ID order) is sampled once") {
    val a = items.head
    val swapped = a.copy(
      sourceId = a.targetId,
      targetId = a.sourceId,
      rulesFired = Vector("er-v1:exact-id-match")
    )
    for order <- Seq(Vector(a, swapped), Vector(swapped, a)) do
      val sel = ReviewQueue.select(order.iterator, 7L, 10)
      assert(sel == Vector(a), order) // a.sourceId sorts first
  }

  test("thresholds are read from an ER run summary or a Hadoop run manifest") {
    val t = Thresholds(0.93, 0.35, 0.65, 0.93)
    val er = ujson.read(
      """{"thresholds":{"accept":0.93,"reject":0.35,"llmBandMin":0.65,"llmBandMax":0.93}}"""
    )
    val hadoop = ujson.read(
      """{"thresholds":{"accept-threshold":0.93,"reject-threshold":0.35,"llm-band-min":0.65,"llm-band-max":0.93}}"""
    )
    assert(ReviewQueue.thresholdsOf(er) == t && ReviewQueue.thresholdsOf(hadoop) == t)
  }

  test("part files of partitioned outputs are read through their subdirectories") {
    val d = Files.createTempDirectory("rq-parts")
    Seq("outcome=B/part-0", "outcome=A/part-1", "outcome=A/part-0", "_SUCCESS", ".x.crc", "part-9")
      .foreach { f =>
        val p = d.resolve(f)
        Files.createDirectories(p.getParent)
        Files.writeString(p, f + "\n")
      }
    Files.createDirectories(d.resolve("_temporary/0"))
    Files.writeString(d.resolve("_temporary/0/part-x"), "temp\n")
    assert(
      tools.Io.lines(d).toVector ==
        Vector("outcome=A/part-0", "outcome=A/part-1", "outcome=B/part-0", "part-9")
    )
  }
