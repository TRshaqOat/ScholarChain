package scholarchain.entityresolution.tools

import scholarchain.core.*
import scholarchain.entityresolution.*
import upickle.default.read

import java.nio.file.{Path, Paths}
import scala.collection.mutable

/** Calibration tools. Reads only Hadoop run outputs, never gold labels.
  *
  * `blocks`: candidate counts and silver blocking recall per max-block-size, by applying
  * BlockingKeys and OversizePolicy.plan to the run's works. Silver positives are pairs of distinct
  * works' mentions that J03+J04 matched to one ORCID iD; a positive is retained when both mentions
  * meet in at least one kept block.
  *
  * `scores`: outcomes of the run's match policy (its manifest's er-v1 or er-v2) on silver-labelled
  * blocked pairs, for a grid of accept and reject thresholds, with the silver identifier features
  * removed before scoring:
  *   - researchers (J06 candidates): positive orcid_agreement = 1, negative orcid_agreement = 0;
  *   - works (J10 decisions as exported by J09, blocked pairs only): positive doi_agreement = 1,
  *     negative doi_agreement = 0, DOI and arXiv features removed. Only cross-source pairs count:
  *     within one source a shared DOI is not identity (OpenAlex multi-work DOIs) and DBLP and
  *     Crossref records are distinct by construction.
  * Calibration data only; gold labels are never read.
  *
  * Usage: `Calibrate blocks --run <hadoop run dir> --out <dir> [--sizes 50,100,...]` or
  * `Calibrate scores --run <hadoop run dir> --out <dir>` (writes score-thresholds.tsv for
  * researchers and work-score-thresholds.tsv for works).
  */
object Calibrate:
  def main(args: Array[String]): Unit =
    val o = Io.options(args.toSeq.drop(1))
    val run = RunInputs(Paths.get(Io.required(o, "run")))
    val out = Paths.get(Io.required(o, "out"))
    args.headOption match
      case Some("blocks") =>
        val sizes = o
          .get("sizes")
          .fold(Vector(50, 100, 200, 300, 500, 1000))(_.split(',').map(_.trim.toInt).toVector)
        val rows = blocks(run, sizes)
        val table = blockTable(rows)
        println(table)
        Io.writeString(out.resolve("block-size.tsv"), table + "\n")
      case Some("scores") =>
        val policy = runPolicyVersion(ujson.read(java.nio.file.Files.readString(run.manifest)))
        for (et, in, file) <- Seq(
            (MatchEntityType.RESEARCHER, run.blockedCandidates, "score-thresholds.tsv"),
            (MatchEntityType.WORK, run.decisions(MatchEntityType.WORK), "work-score-thresholds.tsv")
          )
        do
          val table = scoreTable(silverScores(Io.lines(in), et, policy))
          println(table)
          Io.writeString(out.resolve(file), table + "\n")
      case other =>
        sys.error(s"usage: Calibrate blocks|scores --run <dir> --out <dir> (got $other)")

  // ---- Block size -------------------------------------------------------------------------------

  final case class BlockRow(
      maxBlockSize: Int,
      researcherCandidates: Long,
      workCandidates: Long,
      keptBlocks: Int,
      oversizeBlocks: Int,
      largestKeptBlock: Int,
      researchersWithoutBlock: Int,
      silverPairs: Long,
      silverRetained: Long,
      silverMentions: Long,
      silverMentionsConnected: Long
  ):
    def candidates: Long = researcherCandidates + workCandidates
    def silverRecall: Double =
      if silverPairs == 0 then 0.0 else silverRetained.toDouble / silverPairs
    def silverConnectivity: Double =
      if silverMentions == 0 then 0.0 else silverMentionsConnected.toDouble / silverMentions

  /** One blocking member: its index, its work's index, and its keys by level. */
  final case class Member(index: Int, work: Int, keys: Vector[Vector[String]])

  final case class Population(
      researchers: Vector[Member],
      works: Vector[Member],
      size: Int,
      silver: Vector[(Int, Int)],
      groups: Vector[Vector[Int]]
  )

  /** Members exactly as J05 builds them (Blocking.members in the hadoop module), plus silver pairs.
    */
  def population(works: Iterator[WorkRecord], orcidOfMention: Map[String, String]): Population =
    val ids = mutable.HashMap.empty[String, Int]
    def idx(s: String) = ids.getOrElseUpdate(s, ids.size)
    val rs = Vector.newBuilder[Member]
    val ws = Vector.newBuilder[Member]
    val byOrcid = mutable.HashMap.empty[String, mutable.ArrayBuffer[(Int, Int)]]
    works.foreach { w =>
      val wi = idx(w.workId)
      val wk = BlockingKeys.work(w.title, w.year)
      if wk.nonEmpty then ws += Member(wi, wi, wk)
      w.authors.foreach { m =>
        val mi = idx(m.mentionId)
        val keys = BlockingKeys.researcher(
          m.rawName,
          w.authors.filterNot(_.position == m.position).map(_.rawName)
        )
        if keys.nonEmpty then rs += Member(mi, wi, keys)
        orcidOfMention
          .get(m.mentionId)
          .foreach(o => byOrcid.getOrElseUpdate(o, mutable.ArrayBuffer.empty) += ((mi, wi)))
      }
    }
    val silver = byOrcid.values.toVector.flatMap { g =>
      val s = g.sortBy(_._1)
      for i <- s.indices; j <- i + 1 until s.size if s(i)._2 != s(j)._2 yield (s(i)._1, s(j)._1)
    }
    val groups = byOrcid.values.toVector.map(_.map(_._1).toVector.sorted).filter(_.size > 1)
    Population(rs.result(), ws.result(), ids.size, silver, groups)

  /** Mentions in the largest retained-pair component of their ORCID group, summed over groups: the
    * share of a person's mentions that transitive closure over retained pairs could still join.
    */
  def connected(groups: Vector[Vector[Int]], retained: Seq[(Int, Int)], size: Int): Long =
    val parent = Array.tabulate(size)(identity)
    def find(x: Int): Int =
      var r = x
      while parent(r) != r do r = parent(r)
      var y = x
      while parent(y) != r do { val n = parent(y); parent(y) = r; y = n }
      r
    retained.foreach((a, b) => parent(find(a)) = find(b))
    groups.map(g => g.groupBy(find).values.map(_.size).max.toLong).sum

  /** Candidate pairs and per-member kept blocks for one entity type at one max-block-size. Pairs
    * from levels whose members hold one key each (0 and 1) are counted arithmetically; pairs from
    * multi-key levels are de-duplicated, like J06 does.
    */
  def planCounts(
      members: Vector[Member],
      levels: Int,
      maxBlockSize: Int,
      population: Int,
      skipSameWork: Boolean
  ): (Long, OversizePolicy.Plan, Array[Array[Int]]) =
    val byId = members.map(m => m.index.toString -> m).toMap
    val plan = OversizePolicy.plan[Member](members, _.index.toString, _.keys, levels, maxBlockSize)
    val blocksOf = new Array[Array[Int]](population)
    var count = 0L
    val multi = mutable.ArrayBuilder.make[Long]
    plan.blocks.zipWithIndex.foreach { (b, bi) =>
      val ms = b.memberIds.map(byId)
      ms.foreach(m => blocksOf(m.index) = Option(blocksOf(m.index)).fold(Array(bi))(_ :+ bi))
      val singleKey = ms.forall(_.keys(b.level).size == 1)
      if singleKey then
        val n = ms.size.toLong
        val sameWork =
          if skipSameWork then
            ms.groupBy(_.work).values.map(g => g.size.toLong * (g.size - 1) / 2).sum
          else 0L
        count += n * (n - 1) / 2 - sameWork
      else
        for i <- ms.indices; j <- i + 1 until ms.size if !(skipSameWork && ms(i).work == ms(j).work)
        do
          val (a, c) = (math.min(ms(i).index, ms(j).index), math.max(ms(i).index, ms(j).index))
          multi += a.toLong * population + c
    }
    val codes = multi.result()
    java.util.Arrays.sort(codes)
    count += codes.indices.count(i => i == 0 || codes(i) != codes(i - 1))
    (count, plan, blocksOf)

  def blocks(run: RunInputs, sizes: Seq[Int]): Vector[BlockRow] =
    val orcidOf = Io
      .lines(run.exactMatches)
      .map(read[MatchCandidate](_))
      .filter(c => c.entityType == MatchEntityType.RESEARCHER && c.blockingKey.startsWith("orcid:"))
      .map(c => c.sourceId -> c.blockingKey)
      .toMap
    val pop = population(run.works(), orcidOf)
    System.err.println(
      s"members: ${pop.researchers.size} researcher, ${pop.works.size} work; silver pairs ${pop.silver.size}"
    )
    (sizes :+ Int.MaxValue).toVector.map { m =>
      val (rc, rp, rBlocks) =
        planCounts(pop.researchers, BlockingKeys.ResearcherLevels, m, pop.size, skipSameWork = true)
      val (wc, wp, _) =
        planCounts(pop.works, BlockingKeys.WorkLevels, m, pop.size, skipSameWork = false)
      val retained = pop.silver.filter { (a, b) =>
        val (x, y) = (rBlocks(a), rBlocks(b))
        x != null && y != null && x.exists(y.contains)
      }
      val row = BlockRow(
        m,
        rc,
        wc,
        rp.blocks.size + wp.blocks.size,
        rp.oversize.size + wp.oversize.size,
        (rp.blocks ++ wp.blocks).map(_.memberIds.size).maxOption.getOrElse(0),
        pop.researchers.count(r => rBlocks(r.index) == null),
        pop.silver.size.toLong,
        retained.size.toLong,
        pop.groups.map(_.size.toLong).sum,
        connected(pop.groups, retained, pop.size)
      )
      System.err.println(s"max-block-size $m done: ${row.candidates} candidates")
      row
    }

  def blockTable(rows: Seq[BlockRow]): String =
    val header = Vector(
      "max_block_size",
      "researcher_candidates",
      "work_candidates",
      "total_candidates",
      "kept_blocks",
      "oversize_blocks",
      "largest_kept_block",
      "researcher_mentions_without_block",
      "silver_pairs",
      "silver_retained",
      "silver_recall",
      "silver_mentions",
      "silver_mention_connectivity"
    )
    (header +: rows.map(r =>
      Vector(
        if r.maxBlockSize == Int.MaxValue then "unbounded" else r.maxBlockSize.toString,
        r.researcherCandidates.toString,
        r.workCandidates.toString,
        r.candidates.toString,
        r.keptBlocks.toString,
        r.oversizeBlocks.toString,
        r.largestKeptBlock.toString,
        r.researchersWithoutBlock.toString,
        r.silverPairs.toString,
        r.silverRetained.toString,
        f"${r.silverRecall}%.4f",
        r.silverMentions.toString,
        f"${r.silverConnectivity}%.4f"
      )
    )).map(_.mkString("\t")).mkString("\n")

  // ---- Score thresholds -------------------------------------------------------------------------

  /** How the policy treats a silver pair with its ORCID feature removed. */
  enum Kind:
    case Deterministic, IdDisagreement, Scored, Insufficient

  /** Silver score histograms: counts by (positive, kind, score bin of 1/1000), the silver label
    * feature, and the number of labelled pairs excluded as same-source.
    */
  final case class SilverScores(
      counts: Map[(Boolean, Kind, Int), Long],
      label: String = Features.OrcidAgreement,
      excludedSameSource: Long = 0
  ):
    def total(pos: Boolean): Long = counts.collect { case ((p, _, _), n) if p == pos => n }.sum

  val Bins = 1000
  def bin(p: Double): Int = math.min(Bins - 1, (p * Bins).toInt)

  /** The silver label feature and the identifier features removed before scoring. */
  def silverFeatures(et: MatchEntityType): (String, Set[String]) = et match
    case MatchEntityType.RESEARCHER => (Features.OrcidAgreement, Set(Features.OrcidAgreement))
    case MatchEntityType.WORK       =>
      (MatchPolicy.DoiAgreement, Set(MatchPolicy.DoiAgreement, MatchPolicy.ArxivAgreement))

  /** Kind and score for features without the silver identifiers. Uses the policy's own functions.
    */
  def classify(
      f: Map[String, Double],
      et: MatchEntityType = MatchEntityType.RESEARCHER,
      policy: String = MatchPolicy.Version
  ): (Kind, Double) =
    val probe = Thresholds(1.0, 0.0, 0.0, 1.0) // only to read which rule fires first
    val v = MatchPolicy.decideBlocked(et, f, probe, policy)
    val s = MatchPolicy.score(et, f)
    val kind =
      if v.rulesFired.contains(MatchPolicy.Rule.IdentifierDisagreement) then Kind.IdDisagreement
      else if v.path == MatchPath.DETERMINISTIC then Kind.Deterministic
      else if s.isEmpty then Kind.Insufficient
      else Kind.Scored
    (kind, s.getOrElse(0.5))

  /** Silver scores over MatchCandidate or MatchDecision JSON lines (both carry sourceId, targetId,
    * and features) of one entity type.
    */
  def silverScores(
      lines: Iterator[String],
      et: MatchEntityType = MatchEntityType.RESEARCHER,
      policy: String = MatchPolicy.Version
  ): SilverScores =
    val (label, removed) = silverFeatures(et)
    val counts = mutable.HashMap.empty[(Boolean, Kind, Int), Long].withDefaultValue(0L)
    var sameSource = 0L
    def ns(id: String) = id.takeWhile(_ != ':')
    lines.filter(_.contains(label)).foreach { l =>
      val c = ujson.read(l)
      val f = c("features").obj.view.mapValues(_.num).toMap
      f.get(label).foreach { o =>
        if et == MatchEntityType.WORK && ns(c("sourceId").str) == ns(c("targetId").str) then
          sameSource += 1
        else
          val (k, s) = classify(f -- removed, et, policy)
          counts((o == 1.0, k, bin(s))) += 1
      }
    }
    SilverScores(counts.toMap, label, sameSource)

  /** Outcomes for one threshold pair: (positives accepted, negatives accepted, positives rejected,
    * negatives rejected, positives in the LLM band, negatives in the LLM band) for band [llmMin,
    * accept).
    */
  def outcomes(s: SilverScores, accept: Double, reject: Double, llmMin: Double): Vector[Long] =
    def n(pos: Boolean, pred: (Kind, Int) => Boolean) =
      s.counts.collect { case ((p, k, b), c) if p == pos && pred(k, b) => c }.sum
    val acc = (k: Kind, b: Int) => k == Kind.Deterministic || (k == Kind.Scored && b >= bin(accept))
    val rej = (k: Kind, b: Int) => k == Kind.IdDisagreement || (k == Kind.Scored && b < bin(reject))
    val llm = (k: Kind, b: Int) => k == Kind.Scored && b >= bin(llmMin) && b < bin(accept)
    Vector(n(true, acc), n(false, acc), n(true, rej), n(false, rej), n(true, llm), n(false, llm))

  def scoreTable(s: SilverScores): String =
    val (pos, neg) = (s.total(true), s.total(false))
    val header = Vector(
      "accept",
      "reject",
      "llm_band_min",
      "silver_precision_accepted",
      "silver_recall_accepted",
      "silver_positives_rejected",
      "silver_npv_rejected",
      "positives_in_llm_band",
      "negatives_in_llm_band"
    )
    val grid =
      for
        a <- Vector(0.80, 0.85, 0.90, 0.93, 0.95, 0.97, 0.99)
        r <- Vector(0.10, 0.20, 0.35, 0.50)
      yield (a, r, math.max(r, 0.65).min(a))
    val rows = grid.map { (a, r, l) =>
      val Vector(pa, na, pr, nr, pl, nl) = outcomes(s, a, r, l)
      Vector(
        f"$a%.2f",
        f"$r%.2f",
        f"$l%.2f",
        f"${pa.toDouble / math.max(1, pa + na)}%.4f",
        f"${pa.toDouble / math.max(1, pos)}%.4f",
        f"${pr.toDouble / math.max(1, pos)}%.4f",
        f"${nr.toDouble / math.max(1, pr + nr)}%.4f",
        pl.toString,
        nl.toString
      )
    }
    val kinds = Kind.values.toVector.map { k =>
      val c =
        (p: Boolean) => s.counts.collect { case ((x, kk, _), n) if x == p && kk == k => n }.sum
      s"# kind $k: positives ${c(true)}, negatives ${c(false)}"
    }
    (Vector(
      s"# silver positives $pos, negatives $neg (label ${s.label}; identifiers removed before scoring)",
      s"# same-source labelled pairs excluded: ${s.excludedSameSource}"
    ) ++ kinds ++
      (header +: rows).map(_.mkString("\t"))).mkString("\n")
