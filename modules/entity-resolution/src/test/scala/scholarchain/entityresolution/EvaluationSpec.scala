package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.{MatchEntityType, MatchOutcome}
import scholarchain.entityresolution.tools.{EvalRow, Evaluate, Evaluation}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class EvaluationSpec extends AnyFunSuite:
  import MatchOutcome.*
  import MatchPath.*

  private val t = Thresholds(0.93, 0.35, 0.65, 0.93)
  private def row(id: String, path: MatchPath, d: MatchOutcome, c: Double, label: String) =
    EvalRow(id, MatchEntityType.RESEARCHER, path, d, c, label, "", Map("a_name" -> s"name $id"))

  // Synthetic fixture (not gold labels): hand-checked expected values in the tests below.
  private val rows = Vector(
    row("r1", PROBABILISTIC, ACCEPTED, 0.95, "MATCH"), // TP
    row("r2", PROBABILISTIC, ACCEPTED, 0.94, "NON_MATCH"), // FP
    row("r3", PROBABILISTIC, REJECTED, 0.20, "MATCH"), // FN (rejected)
    row("r4", PROBABILISTIC, REJECTED, 0.10, "NON_MATCH"), // TN
    row("r5", LLM_BAND, UNRESOLVED, 0.80, "MATCH"), // FN (unresolved)
    row("r6", PROBABILISTIC, UNRESOLVED, 0.50, "NON_MATCH"), // TN (unresolved)
    row("r7", PROBABILISTIC, ACCEPTED, 0.99, "UNSURE"), // excluded
    row("r8", EXACT_ID, ACCEPTED, 1.0, "MATCH"), // TP
    row("r9", EXACT_ID, CONFLICT, 0.5, "NON_MATCH") // TN
  )

  test(
    "hand-computed metrics: precision, recall, F1, coverage, unresolved rate, confusion matrix"
  ) {
    val m = Evaluation.metrics(rows)
    assert((m.n, m.unsure, m.tp, m.fp, m.fn, m.tn) == (8, 1, 2, 1, 2, 3))
    assert(m.precision.contains(2.0 / 3) && m.recall.contains(0.5))
    assert(math.abs(m.f1.get - 4.0 / 7) < 1e-12)
    assert(m.coverage.contains(0.75) && m.unresolvedRate.contains(0.25))
    assert(
      m.confusion("MATCH") == Map(
        "ACCEPTED" -> 2,
        "REJECTED" -> 1,
        "UNRESOLVED" -> 1,
        "CONFLICT" -> 0
      )
    )
    assert(
      m.confusion("NON_MATCH") == Map(
        "ACCEPTED" -> 1,
        "REJECTED" -> 1,
        "UNRESOLVED" -> 1,
        "CONFLICT" -> 1
      )
    )
    assert(m.confusion("UNSURE")("ACCEPTED") == 1)
  }

  test("metrics by match path, and undefined rates are None") {
    val exact = Evaluation.metrics(rows.filter(_.path == EXACT_ID))
    assert(
      (exact.n, exact.tp, exact.tn) == (2, 1, 1) && exact.precision.contains(1.0) && exact.recall
        .contains(1.0)
    )
    val none = Evaluation.metrics(rows.filter(_.path == LLM))
    assert(
      none.n == 0 && none.precision.isEmpty && none.recall.isEmpty && none.f1.isEmpty && none.coverage.isEmpty
    )
  }

  test("threshold sensitivity moves only score-decided rows") {
    val sens = Evaluation.sensitivity(rows, t)
    val at080 = sens.collectFirst { case ("accept", a, m) if a == 0.80 => m }.get
    // r5 (0.80, LLM band) becomes ACCEPTED: TP 3, FP 1, FN 1.
    assert(
      (at080.tp, at080.fp, at080.fn) == (3, 1, 1) && at080.precision.contains(0.75) && at080.recall
        .contains(0.75)
    )
    val at099 = sens.collectFirst { case ("accept", a, m) if a == 0.99 => m }.get
    // r1 and r2 drop to UNRESOLVED; the exact-ID TP (r8) stays.
    assert((at099.tp, at099.fp) == (1, 0))
    val rej05 = sens.collectFirst { case ("reject", r, m) if r == 0.05 => m }.get
    assert(rej05.confusion("MATCH")("REJECTED") == 0) // r3 (0.20) becomes UNRESOLVED
    assert(sens.map(_._1).distinct == Vector("accept", "reject"))
  }

  test("error lists: false positives, then false negatives with rejections before abstentions") {
    val (fps, fns) = Evaluation.errors(rows)
    assert(fps.map(_.rowId) == Vector("r2"))
    assert(fns.map(_.rowId) == Vector("r3", "r5"))
    assert(fns.head.evidence("a_name") == "name r3")
    val many = (1 to 15).map(i => row(f"x$i%02d", PROBABILISTIC, ACCEPTED, 0.95, "NON_MATCH"))
    assert(Evaluation.errors(many)._1.size == 10)
  }

  test("join checks traceability to the key file and label values") {
    val key = Vector(
      Map(
        "row_id" -> "rq-1",
        "entity_type" -> "WORK",
        "a_id" -> "doi:10.1/a",
        "b_id" -> "dblp:conf/a/B",
        "path" -> "EXACT_ID",
        "decision" -> "ACCEPTED",
        "confidence" -> "1.0"
      )
    )
    val ok = Map(
      "row_id" -> "rq-1",
      "a_id" -> "doi:10.1/a",
      "b_id" -> "dblp:conf/a/B",
      "label" -> " match ",
      "notes" -> "n"
    )
    val joined = Evaluation.join(Vector(ok), key).toOption.get
    assert(
      joined.map(r => (r.label, r.path, r.decision, r.entityType)) == Vector(
        ("MATCH", EXACT_ID, ACCEPTED, MatchEntityType.WORK)
      )
    )
    assert(Evaluation.join(Vector(ok + ("b_id" -> "dblp:conf/a/OTHER")), key).isLeft)
    assert(Evaluation.join(Vector(ok + ("label" -> "PROBABLY")), key).isLeft)
    assert(Evaluation.join(Vector(ok + ("row_id" -> "rq-404")), key).isLeft)
    assert(
      Evaluation.join(Vector(ok + ("label" -> "")), key).toOption.get.isEmpty
    ) // unlabelled rows are skipped
  }

  test(
    "rows are scored on the key file's decision and path, not on the stratum they were sampled in"
  ) {
    val key = Vector(
      Map(
        "row_id" -> "rq-1",
        "entity_type" -> "WORK",
        "a_id" -> "dblp:conf/a/A",
        "b_id" -> "openalex:W1",
        "stratum" -> "WORK|PROBABILISTIC|B2_UNRESOLVED|NONE",
        "path" -> "DETERMINISTIC",
        "decision" -> "ACCEPTED",
        "confidence" -> "1.0",
        "policy_version" -> "er-v2"
      )
    )
    val l = Map("row_id" -> "rq-1", "a_id" -> "dblp:conf/a/A", "b_id" -> "openalex:W1")
    val rows = Evaluation.join(Vector(l + ("label" -> "MATCH")), key).toOption.get
    assert(rows.map(r => (r.path, r.decision)) == Vector((DETERMINISTIC, ACCEPTED)))
    assert(Evaluation.metrics(rows).tp == 1)
  }

  test("the reported policy version comes from the key file, which must name exactly one") {
    def key(vs: String*) = vs.map(v => Map("policy_version" -> v))
    assert(Evaluation.policyVersion(key("er-v2", "er-v2")) == Right("er-v2"))
    assert(Evaluation.policyVersion(key("er-v1")) == Right("er-v1"))
    assert(Evaluation.policyVersion(key("er-v1", "er-v2")).isLeft)
    assert(Evaluation.policyVersion(key("er-v3")).isLeft)
    assert(Evaluation.policyVersion(Vector(Map("row_id" -> "rq-1"))).isLeft)
    val out = Files.createTempDirectory("eval-report")
    Evaluate.report(rows, t, out, "er-v2")
    assert(
      ujson.read(Files.readString(out.resolve("metrics.json")))("policyVersion").str == "er-v2"
    )
  }

  test("the evaluator refuses a labels file outside a labels/ directory") {
    val f = Files.createTempFile("not-labels", ".csv")
    assertThrows[IllegalArgumentException](
      Evaluate.main(
        Array("--labels", f.toString, "--key", f.toString, "--out", f.getParent.toString)
      )
    )
  }

  test("leakage check: only the evaluator mentions labels/ among main sources") {
    val files =
      Files.walk(ModuleFiles.main).iterator.asScala.filter(Files.isRegularFile(_)).toVector
    val mentioning =
      files.filter(f => Files.readString(f).contains("labels/")).map(_.getFileName.toString)
    assert(mentioning == Vector("Evaluate.scala"))
  }
