package scholarchain.entityresolution.tools

import scholarchain.core.{MatchEntityType, MatchOutcome}
import scholarchain.core.config.ConfigLoader
import scholarchain.entityresolution.*

import java.nio.file.{Files, Path, Paths}

/** One labelled review row joined to its key row. */
final case class EvalRow(
    rowId: String,
    entityType: MatchEntityType,
    path: MatchPath,
    decision: MatchOutcome,
    confidence: Double,
    label: String,
    notes: String,
    evidence: Map[String, String]
)

/** Metrics for one group of labelled rows. Rows labelled UNSURE are counted but excluded from every
  * rate. Definitions (n = rows labelled MATCH or NON_MATCH):
  *   - TP: ACCEPTED and MATCH; FP: ACCEPTED and NON_MATCH;
  *   - FN: MATCH and not ACCEPTED (REJECTED, CONFLICT, or UNRESOLVED; an abstention that leaves a
  *     true match apart is a miss);
  *   - precision = TP / (TP + FP); recall = TP / (number of MATCH rows); F1 their harmonic mean;
  *   - coverage = decided (ACCEPTED, REJECTED, CONFLICT) / n; unresolved rate = UNRESOLVED / n.
  * Precision, recall, and F1 are None when undefined (zero denominator).
  */
final case class Metrics(
    n: Int,
    unsure: Int,
    confusion: Map[String, Map[String, Int]],
    tp: Int,
    fp: Int,
    fn: Int,
    tn: Int,
    precision: Option[Double],
    recall: Option[Double],
    f1: Option[Double],
    coverage: Option[Double],
    unresolvedRate: Option[Double]
)

/** Gold-set evaluation, pure. The CLI below is the only code that opens files under `labels/`. */
object Evaluation:
  val Match = "MATCH"
  val NonMatch = "NON_MATCH"
  val Unsure = "UNSURE"

  private def ratio(a: Int, b: Int) = Option.when(b > 0)(a.toDouble / b)

  def metrics(rows: Seq[EvalRow]): Metrics =
    val labelled = rows.filter(r => r.label == Match || r.label == NonMatch)
    val confusion = Vector(Match, NonMatch, Unsure).map { l =>
      l -> MatchOutcome.values.toVector
        .map(o => o.toString -> rows.count(r => r.label == l && r.decision == o))
        .toMap
    }.toMap
    val accepted = (r: EvalRow) => r.decision == MatchOutcome.ACCEPTED
    val tp = labelled.count(r => accepted(r) && r.label == Match)
    val fp = labelled.count(r => accepted(r) && r.label == NonMatch)
    val fn = labelled.count(r => !accepted(r) && r.label == Match)
    val tn = labelled.count(r => !accepted(r) && r.label == NonMatch)
    val p = ratio(tp, tp + fp)
    val rc = ratio(tp, tp + fn)
    val f1 = for x <- p; y <- rc if x + y > 0 yield 2 * x * y / (x + y)
    val unresolved = labelled.count(_.decision == MatchOutcome.UNRESOLVED)
    Metrics(
      labelled.size,
      rows.count(_.label == Unsure),
      confusion,
      tp,
      fp,
      fn,
      tn,
      p,
      rc,
      f1,
      ratio(labelled.size - unresolved, labelled.size),
      ratio(unresolved, labelled.size)
    )

  /** The decision a row would get under other thresholds. Only score-decided rows (PROBABILISTIC
    * and LLM_BAND paths) move; rule-decided and LLM-decided rows keep their decision.
    */
  def redecide(r: EvalRow, t: Thresholds): EvalRow =
    if r.path == MatchPath.PROBABILISTIC || r.path == MatchPath.LLM_BAND then
      r.copy(decision = MatchPolicy.banded(r.confidence, t, Vector.empty).outcome)
    else r

  /** Threshold sensitivity (report only; nothing is tuned): accept sweep with the reject threshold
    * fixed, and reject sweep with the accept threshold fixed. Rows: (sweep, threshold, metrics).
    */
  def sensitivity(rows: Seq[EvalRow], t: Thresholds): Vector[(String, Double, Metrics)] =
    def at(accept: Double, reject: Double) =
      val r = math.min(reject, accept)
      Thresholds(
        accept,
        r,
        math.min(math.max(t.llmBandMin, r), accept),
        math.min(math.max(t.llmBandMax, r), accept)
      )
    val accepts = Vector(0.50, 0.60, 0.70, 0.80, 0.85, 0.90, 0.93, 0.95, 0.97, 0.99)
    val rejects = Vector(0.05, 0.10, 0.20, 0.35, 0.50, 0.65)
    accepts.map(a => ("accept", a, metrics(rows.map(redecide(_, at(a, t.reject)))))) ++
      rejects.map(r => ("reject", r, metrics(rows.map(redecide(_, at(t.accept, r))))))

  /** Up to `limit` false positives and false negatives; false negatives that were REJECTED or
    * CONFLICT come before UNRESOLVED ones. Each list is ordered by row ID.
    */
  def errors(rows: Seq[EvalRow], limit: Int = 10): (Vector[EvalRow], Vector[EvalRow]) =
    val fps =
      rows.filter(r => r.label == NonMatch && r.decision == MatchOutcome.ACCEPTED).sortBy(_.rowId)
    val fns = rows
      .filter(r => r.label == Match && r.decision != MatchOutcome.ACCEPTED)
      .sortBy(r => (r.decision == MatchOutcome.UNRESOLVED, r.rowId))
    (fps.take(limit).toVector, fns.take(limit).toVector)

  /** The one match policy version the key file's `policy_version` column names (an er-v1 strata
    * file or a re-keyed one such as `-strata-er-v2.csv`); fails on none, several, or an unknown
    * one.
    */
  def policyVersion(key: Seq[Map[String, String]]): Either[String, String] =
    key.map(_.getOrElse("policy_version", "")).distinct match
      case Seq(v) if MatchPolicy.Versions.contains(v) => Right(v)
      case vs => Left(s"key file must name one known policy version, found: ${vs.mkString(", ")}")

  /** Joins labelled rows (by row_id) to key rows. Decision, path, and confidence come from the key
    * row's own columns, never from its `stratum`, so a re-keyed file is scored on its current
    * decisions even where they differ from the stratum a row was sampled in. Fails when a row's IDs
    * differ from its key row or a label is not MATCH, NON_MATCH, UNSURE, or blank (blank rows are
    * skipped as unlabelled).
    */
  def join(
      labelled: Seq[Map[String, String]],
      key: Seq[Map[String, String]]
  ): Either[String, Vector[EvalRow]] =
    val byId = key.map(k => k("row_id") -> k).toMap
    val rows = labelled.filter(_.getOrElse("label", "").trim.nonEmpty).map { l =>
      val id = l.getOrElse("row_id", "")
      for
        k <- byId.get(id).toRight(s"row $id is not in the key file")
        _ <- Either.cond(
          l.get("a_id") == k.get("a_id") && l.get("b_id") == k.get("b_id"),
          (),
          s"row $id: IDs differ from the key file"
        )
        label = l("label").trim.toUpperCase
        _ <- Either.cond(
          Set(Match, NonMatch, Unsure)(label),
          (),
          s"row $id: unknown label '$label'"
        )
      yield EvalRow(
        id,
        MatchEntityType.valueOf(k("entity_type")),
        MatchPath.valueOf(k("path")),
        MatchOutcome.valueOf(k("decision")),
        k("confidence").toDouble,
        label,
        l.getOrElse("notes", ""),
        l.filter((c, _) => c == "a_id" || c == "b_id" || ReviewQueue.EvidenceColumns.contains(c))
      )
    }
    rows.collectFirst { case Left(e) => e }.toLeft(rows.collect { case Right(r) => r }.toVector)

/** Gold-set evaluation CLI: `Evaluate --labels labels/<file>.csv --key
  * <review-queue-<seed>-strata[-<policy>].csv> --out <dir>`; thresholds come from ConfigLoader (the
  * sweep centres on them), and the reported policy version from the key file. The labels file must
  * lie under a directory named `labels` and is only read. Outputs `metrics.json`,
  * `sensitivity.tsv`, and `errors.tsv` under --out.
  */
object Evaluate:
  def main(args: Array[String]): Unit =
    val o = Io.options(args.toSeq)
    val labelsPath = Paths.get(Io.required(o, "labels")).toAbsolutePath.normalize
    require(
      Iterator
        .iterate(labelsPath.getParent)(_.getParent)
        .takeWhile(_ != null)
        .exists(p => Option(p.getFileName).exists(_.toString == "labels")),
      s"$labelsPath is not under a labels/ directory"
    )
    val key = Csv.records(Files.readString(Paths.get(Io.required(o, "key"))))
    val labelled = Csv.records(Files.readString(labelsPath))
    val t = Thresholds.from(
      ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity).entityResolution
    )
    val policy = Evaluation.policyVersion(key).fold(e => sys.error(e), identity)
    val rows = Evaluation.join(labelled, key).fold(e => sys.error(e), identity)
    val out = Paths.get(Io.required(o, "out"))
    report(rows, t, out, policy)

  private def num(x: Option[Double]): ujson.Value = x.fold(ujson.Null: ujson.Value)(ujson.Num(_))
  private def fmt(x: Option[Double]) = x.fold("NA")(v => f"$v%.4f")

  def metricsJson(m: Metrics): ujson.Obj = ujson.Obj(
    "n" -> m.n,
    "unsure" -> m.unsure,
    "tp" -> m.tp,
    "fp" -> m.fp,
    "fn" -> m.fn,
    "tn" -> m.tn,
    "precision" -> num(m.precision),
    "recall" -> num(m.recall),
    "f1" -> num(m.f1),
    "coverage" -> num(m.coverage),
    "unresolvedRate" -> num(m.unresolvedRate),
    "confusion" -> ujson.Obj.from(
      m.confusion.toSeq
        .sortBy(_._1)
        .map((l, row) =>
          l -> ujson.Obj.from(row.toSeq.sortBy(_._1).map((o, n) => o -> ujson.Num(n)))
        )
    )
  )

  def report(rows: Vector[EvalRow], t: Thresholds, out: Path, policyVersion: String): Unit =
    val types = MatchEntityType.values.toVector
    val json = ujson.Obj(
      "policyVersion" -> policyVersion,
      "labelledRows" -> rows.size,
      "byEntityType" -> ujson.Obj.from(types.map { et =>
        val rs = rows.filter(_.entityType == et)
        et.toString -> ujson.Obj(
          "overall" -> metricsJson(Evaluation.metrics(rs)),
          "byPath" -> ujson.Obj.from(
            MatchPath.values.toVector.map(p =>
              p.toString -> metricsJson(Evaluation.metrics(rs.filter(_.path == p)))
            )
          )
        )
      })
    )
    Io.writeString(out.resolve("metrics.json"), ujson.write(json, indent = 2) + "\n")
    val sens = types.flatMap { et =>
      Evaluation.sensitivity(rows.filter(_.entityType == et), t).map { (sweep, th, m) =>
        Vector(
          et.toString,
          sweep,
          f"$th%.2f",
          fmt(m.precision),
          fmt(m.recall),
          fmt(m.f1),
          fmt(m.coverage),
          fmt(m.unresolvedRate),
          m.fn.toString
        )
      }
    }
    Io.writeString(
      out.resolve("sensitivity.tsv"),
      (Vector(
        "entity_type",
        "sweep",
        "threshold",
        "precision",
        "recall",
        "f1",
        "coverage",
        "unresolved_rate",
        "false_negatives"
      ) +: sens)
        .map(_.mkString("\t"))
        .mkString("", "\n", "\n")
    )
    val errs = types.flatMap { et =>
      val (fps, fns) = Evaluation.errors(rows.filter(_.entityType == et))
      fps.map("FALSE_POSITIVE" -> _) ++ fns.map("FALSE_NEGATIVE" -> _)
    }
    val evCols = Vector("a_id", "b_id") ++ ReviewQueue.EvidenceColumns
    Io.writeString(
      out.resolve("errors.tsv"),
      ((Vector(
        "kind",
        "row_id",
        "entity_type",
        "path",
        "decision",
        "confidence",
        "label",
        "notes"
      ) ++ evCols) +:
        errs.map((k, r) =>
          Vector(
            k,
            r.rowId,
            r.entityType.toString,
            r.path.toString,
            r.decision.toString,
            r.confidence.toString,
            r.label,
            r.notes
          ) ++
            evCols.map(c => r.evidence.getOrElse(c, ""))
        ))
        .map(_.map(_.replace('\t', ' ').replace('\n', ' ')).mkString("\t"))
        .mkString("", "\n", "\n")
    )
    println(ujson.write(json, indent = 2))
