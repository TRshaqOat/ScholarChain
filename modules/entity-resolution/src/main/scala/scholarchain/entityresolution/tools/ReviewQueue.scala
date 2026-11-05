package scholarchain.entityresolution.tools

import scholarchain.core.*
import scholarchain.entityresolution.*
import upickle.default.read

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

/** Gold-set review queue: a seeded, deterministic, stratified sample of er-v1 or er-v2 decisions,
  * exported as a blind CSV for human labelling plus a separate strata file
  * (`review-queue-<seed>-strata.csv`) mapping each row ID to its stratum, path, band, decision, and
  * confidence.
  *
  * Strata: entity type x match path (EXACT_ID, DETERMINISTIC, PROBABILISTIC, LLM_BAND, LLM) x score
  * band (from the decision's confidence and the thresholds) x hard case (ID_CONFLICT,
  * HOMONYM_SUFFIX when either name carries a DBLP homonym number, COMMON_NAME when the name's
  * primary blocking key has at least [[CommonNameMentions]] mentions in the run, else NONE).
  * Selection: within each stratum, items are ranked by H(seed, entityType, unordered {sourceId,
  * targetId}), so the two decisions one pair can get (exact J03+J04 and blocked J06, in either ID
  * order) share a rank and only one is kept (the smaller sourceId, targetId, rules); strata are
  * visited in name order, round-robin, one item per visit, until the target is reached. The result
  * depends only on the seed and the set of decisions, never on input order.
  *
  * The blind CSV holds IDs and side-by-side evidence only (no score, decision, path, or stratum),
  * in row-ID hash order, with empty `label` (MATCH, NON_MATCH, UNSURE) and `notes` columns.
  * Exact-ID researcher decisions whose target is an identifier rather than a mention are not
  * sampled (they have no second record to compare); ORCID-agreeing mention pairs represent that
  * path.
  *
  * Usage: `ReviewQueue --run <hadoop run dir> --out <dir> [--decisions <er run dir>] [--seed n]
  * [--researchers 260] [--works 50] [--rekey <existing strata.csv>]`. Without `--decisions`, the
  * decisions are the Hadoop run's J10 output as exported by J09, with thresholds from the run's
  * `_manifest.json`. With `--rekey`, nothing is sampled: the existing queue is re-keyed (see
  * [[rekey]]) to this run's decisions and written as `review-queue-<seed>-strata-<policy>.csv`.
  * Existing output files are never overwritten.
  */
object ReviewQueue:
  val CommonNameMentions = 100
  val Labels: Vector[String] = Vector("MATCH", "NON_MATCH", "UNSURE")

  /** One sampling candidate: a decision and its stratum. */
  final case class Item(
      entityType: MatchEntityType,
      sourceId: String,
      targetId: String,
      path: MatchPath,
      band: String,
      hard: String,
      decision: MatchOutcome,
      confidence: Double,
      rulesFired: Vector[String],
      reasonCodes: Vector[String],
      runId: String,
      policyVersion: String = MatchPolicy.V1
  ):
    def stratum: String = s"$entityType|$path|$band|$hard"

  def band(confidence: Double, t: Thresholds): String =
    if confidence >= t.accept then "B4_ACCEPT"
    else if confidence >= t.llmBandMin && confidence <= t.llmBandMax then "B3_LLM"
    else if confidence >= t.reject then "B2_UNRESOLVED"
    else "B1_REJECT"

  def rank(seed: Long, i: Item): String =
    val Seq(a, b) = Seq(i.sourceId, i.targetId).sorted
    StableIds.hash("review-queue", seed.toString, i.entityType.toString, a, b)

  def rowId(seed: Long, i: Item): String =
    "rq-" + StableIds
      .hash("review-row", seed.toString, i.entityType.toString, i.sourceId, i.targetId)
      .take(16)

  /** Deterministic stratified selection of `target` items of one entity type (see the object doc).
    * Holds at most `target` items per stratum.
    */
  def select(items: Iterator[Item], seed: Long, target: Int): Vector[Item] =
    val keep = mutable.TreeMap.empty[String, mutable.TreeMap[String, Item]]
    items.foreach { i =>
      val s = keep.getOrElseUpdate(i.stratum, mutable.TreeMap.empty)
      val r = rank(seed, i)
      def tie(x: Item) = (x.sourceId, x.targetId, x.rulesFired.mkString(" "))
      if s.get(r).forall(k => Ordering[(String, String, String)].gt(tie(k), tie(i))) then s(r) = i
      if s.size > target then s.remove(s.lastKey)
    }
    val queues = keep.values.map(_.values.iterator.buffered).toVector
    val out = Vector.newBuilder[Item]
    var n = 0
    while n < target && queues.exists(_.hasNext) do
      queues.foreach(q => if n < target && q.hasNext then { out += q.next(); n += 1 })
    out.result()

  val EvidenceColumns: Vector[String] =
    Vector("a", "b").flatMap(s => Evidence.Keys.map(k => s"${s}_$k"))
  val BlindHeader: Vector[String] =
    Vector("row_id", "entity_type", "a_id", "b_id") ++ EvidenceColumns ++ Vector("label", "notes")
  val KeyHeader: Vector[String] = Vector(
    "row_id",
    "entity_type",
    "a_id",
    "b_id",
    "stratum",
    "path",
    "band",
    "hard_case",
    "decision",
    "confidence",
    "policy_version",
    "er_run_id",
    "rules_fired",
    "reason_codes"
  )

  /** (blind CSV, key CSV) for the selected items, rows in row-ID order. */
  def exportCsv(
      selected: Seq[Item],
      seed: Long,
      evidence: (MatchEntityType, String) => Map[String, String]
  ): (String, String) =
    val rows = selected.map(i => rowId(seed, i) -> i).sortBy(_._1)
    val blind = rows.map { (id, i) =>
      val (a, b) = (evidence(i.entityType, i.sourceId), evidence(i.entityType, i.targetId))
      Vector(id, i.entityType.toString, i.sourceId, i.targetId) ++
        Evidence.Keys.map(a.getOrElse(_, "")) ++ Evidence.Keys.map(b.getOrElse(_, "")) ++ Vector(
          "",
          ""
        )
    }
    val key = rows.map(keyRow)
    (csv(BlindHeader, blind), csv(KeyHeader, key))

  private def csv(h: Vector[String], rs: Seq[Vector[String]]) =
    (h +: rs).map(Csv.row).mkString("", "\n", "\n")

  private def keyRow(id: String, i: Item): Vector[String] = Vector(
    id,
    i.entityType.toString,
    i.sourceId,
    i.targetId,
    i.stratum,
    i.path.toString,
    i.band,
    i.hard,
    i.decision.toString,
    i.confidence.toString,
    i.policyVersion,
    i.runId,
    i.rulesFired.mkString(" "),
    i.reasonCodes.mkString(" ")
  )

  /** Re-keys an existing queue to another run's decisions without resampling: every strata row
    * keeps its row ID and pair, and takes stratum, path, band, hard case, decision, confidence,
    * policy version, run ID, rules, and reasons from the one decision with the same entity type and
    * the same ordered (a_id, b_id). Fails when a row has no such decision or more than one. Rows
    * keep their input order.
    */
  def rekey(queued: Seq[Map[String, String]], items: Iterator[Item]): Either[String, String] =
    def k(et: String, a: String, b: String) = (et, a, b)
    val wanted = queued.map(r => k(r("entity_type"), r("a_id"), r("b_id"))).toSet
    val found = items
      .filter(i => wanted(k(i.entityType.toString, i.sourceId, i.targetId)))
      .toVector
      .groupBy(i => k(i.entityType.toString, i.sourceId, i.targetId))
    val rows = queued.map { r =>
      found.getOrElse(k(r("entity_type"), r("a_id"), r("b_id")), Vector.empty) match
        case Vector(i) => Right(keyRow(r("row_id"), i))
        case is        => Left(s"row ${r("row_id")}: ${is.size} decisions for its pair, expected 1")
    }
    rows
      .collectFirst { case Left(e) => e }
      .toLeft(csv(KeyHeader, rows.collect { case Right(x) => x }))

  /** Thresholds from an ER Runner summary.json or a Hadoop run manifest (hadoop-run-manifest-v3).
    */
  def thresholdsOf(manifest: ujson.Value): Thresholds =
    val th = manifest("thresholds")
    def n(er: String, hadoop: String) = th.obj.get(er).orElse(th.obj.get(hadoop)).get.num
    Thresholds(
      n("accept", "accept-threshold"),
      n("reject", "reject-threshold"),
      n("llmBandMin", "llm-band-min"),
      n("llmBandMax", "llm-band-max")
    )

  def main(args: Array[String]): Unit =
    val o = Io.options(args.toSeq)
    val erRun = o.get("decisions").map(Paths.get(_))
    val run = RunInputs(Paths.get(Io.required(o, "run")))
    val out = Paths.get(Io.required(o, "out"))
    val seed = o.getOrElse("seed", "20261103").toLong
    val targets = Map(
      MatchEntityType.RESEARCHER -> o.getOrElse("researchers", "260").toInt,
      MatchEntityType.WORK -> o.getOrElse("works", "50").toInt
    )
    val manifest =
      ujson.read(Files.readString(erRun.fold(run.manifest)(_.resolve("summary.json"))))
    val policy = runPolicyVersion(manifest)
    val t = thresholdsOf(manifest)
    def decisionDir(et: MatchEntityType) =
      erRun.fold(run.decisions(et))(_.resolve(s"decisions/entity_type=$et"))
    val (blindFile, strataFile) =
      (out.resolve(s"review-queue-$seed.csv"), out.resolve(s"review-queue-$seed-strata.csv"))
    val rekeyFrom = o.get("rekey").map(Paths.get(_))
    val rekeyFile = out.resolve(s"review-queue-$seed-strata-$policy.csv")
    rekeyFrom
      .fold(Seq(blindFile, strataFile))(_ => Seq(rekeyFile))
      .foreach(f => require(!Files.exists(f), s"$f exists; not overwritten"))
    val idx = WorkIndex(run)
    def primaryKey(raw: String) = BlockingKeys.researcher(raw, Nil).headOption.flatMap(_.headOption)
    val mentions =
      idx.works.valuesIterator.flatMap(_.authors).map(m => m.mentionId -> m.rawName).toVector
    val keyCount = mentions.flatMap(m => primaryKey(m._2)).groupMapReduce(identity)(_ => 1)(_ + _)
    // mention ID -> (has a DBLP homonym number, has a common primary blocking key)
    val flags = mentions.map { (id, raw) =>
      id -> (
        Normalize.splitHomonym(raw)._2.isDefined,
        primaryKey(raw).exists(keyCount(_) >= CommonNameMentions)
      )
    }.toMap
    def hard(d: MatchDecision): String =
      val fs = Seq(d.sourceId, d.targetId).flatMap(flags.get)
      if d.decision == MatchOutcome.CONFLICT then "ID_CONFLICT"
      else if d.entityType == MatchEntityType.WORK then "NONE"
      else if fs.exists(_._1) then "HOMONYM_SUFFIX"
      else if fs.exists(_._2) then "COMMON_NAME"
      else "NONE"
    def items(et: MatchEntityType): Iterator[Item] =
      Io.lines(decisionDir(et))
        .map(read[MatchDecision](_))
        .filter(d => et == MatchEntityType.WORK || IdFormat.isMentionId(d.targetId))
        .map { d =>
          require(
            d.provenance.policyVersion.contains(policy),
            s"decision ${d.sourceId}|${d.targetId} is not $policy"
          )
          Item(
            d.entityType,
            d.sourceId,
            d.targetId,
            MatchPolicy.pathOf(d.rulesFired),
            band(d.confidence, t),
            hard(d),
            d.decision,
            d.confidence,
            d.rulesFired,
            d.reasonCodes,
            d.provenance.runId,
            policy
          )
        }
    rekeyFrom match
      case Some(f) =>
        val queued = Csv.records(Files.readString(f))
        val key =
          rekey(queued, MatchEntityType.values.iterator.flatMap(items)).fold(sys.error, identity)
        Io.writeString(rekeyFile, key)
        // Per entity type: rows whose decision or path differs from the input strata file.
        queued.zip(Csv.records(key)).groupBy(_._1("entity_type")).toVector.sortBy(_._1).foreach {
          (et, ps) =>
            val moved =
              ps.filter((a, b) => a("decision") != b("decision") || a("path") != b("path"))
            println(s"$et\trows ${ps.size}\tdecision or path changed ${moved.size}")
            moved
              .groupMapReduce((a, b) =>
                s"${a("decision")}/${a("path")} -> ${b("decision")}/${b("path")}"
              )(_ => 1)(_ + _)
              .toVector
              .sorted
              .foreach((t, n) => println(s"  $t\t$n"))
        }
        println(s"rows: ${queued.size} -> $rekeyFile")
      case None =>
        val selected =
          MatchEntityType.values.toVector.flatMap(et => select(items(et), seed, targets(et)))
        val (blind, key) = exportCsv(selected, seed, (et, id) => Evidence.side(idx, et, id))
        Io.writeString(blindFile, blind)
        // Not "<name>.key.csv": a ".key" suffix reads as a private-key file to people and secret guards.
        Io.writeString(strataFile, key)
        selected
          .groupBy(_.stratum)
          .toVector
          .sortBy(_._1)
          .foreach((s, is) => println(s"$s\t${is.size}"))
        println(s"rows: ${selected.size} -> $blindFile")
