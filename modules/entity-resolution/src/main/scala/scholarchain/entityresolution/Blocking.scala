package scholarchain.entityresolution

/** Blocking keys (rule block-v1). Each function returns one key list per level: index 0 is the
  * primary level, later indexes are fallbacks. Every level refines the one before it (a level-k key
  * determines the member's level-(k-1) key), so re-keying an oversize block only splits it. An
  * empty result means the record has no usable key (Hadoop counts MISSING_KEY). "-" stands for an
  * absent part. The DBLP homonym suffix is ignored, so "Wei Wang 0001" blocks with "Wei Wang".
  */
object BlockingKeys:
  val ResearcherLevels = 3
  val WorkLevels = 3

  private val TitleStopwords =
    Set(
      "a",
      "an",
      "the",
      "of",
      "on",
      "in",
      "for",
      "and",
      "to",
      "with",
      "via",
      "by",
      "at",
      "from",
      "towards",
      "toward",
      "using"
    )

  /** Researcher keys for one author mention, from its printed name and its coauthors' names.
    *   - r0 (primary): `r0:<familyKey>|<first initial>`, for example `r0:wang|w`
    *   - r1: `r1:<familyKey>|<first given token>`, for example `r1:wang|wei`
    *   - r2: one key per distinct coauthor family key, sorted: `r2:wang|wei|<coauthor familyKey>`,
    *     or `r2:wang|wei|-` for a single-author work. Two mentions share an r2 block when they
    *     share a coauthor family name, so the same pair can meet in several r2 blocks.
    */
  def researcher(rawName: String, coauthorRawNames: Seq[String]): Vector[Vector[String]] =
    val n = Normalize.name(rawName)
    if n.familyKey.isEmpty then Vector.empty
    else
      val first = n.givenNames.headOption.getOrElse("-")
      val r1 = s"${n.familyKey}|$first"
      val r2 = coauthorRawNames
        .map(Normalize.name(_).familyKey)
        .filter(_.nonEmpty)
        .distinct
        .sorted
        .map(c => s"r2:$r1|$c")
        .toVector
      Vector(
        Vector(s"r0:${n.familyKey}|${first.take(1)}"),
        Vector(s"r1:$r1"),
        if r2.isEmpty then Vector(s"r2:$r1|-") else r2
      )

  /** Work keys from the title and year.
    *   - w0 (primary): `w0:<first three title tokens that are not stopwords, joined by _>`
    *     (all-stopword titles use their first three tokens), for example
    *     `w0:deep_residual_learning`
    *   - w1: `w1:<w0 body>|<year or ->`
    *   - w2: `w2:<w1 body>|<every title token, joined by _>`
    */
  def work(title: Option[String], year: Option[Int]): Vector[Vector[String]] =
    val tokens = Normalize.titleTokens(title)
    if tokens.isEmpty then Vector.empty
    else
      val significant = tokens.filterNot(TitleStopwords)
      val w0 = (if significant.isEmpty then tokens else significant).take(3).mkString("_")
      val y = year.fold("-")(_.toString)
      Vector(Vector(s"w0:$w0"), Vector(s"w1:$w0|$y"), Vector(s"w2:$w0|$y|${tokens.mkString("_")}"))

/** Oversize-block policy (rule block-v1) for a configured maximum block size
  * (entity-resolution.max-block-size). A block with at most maxBlockSize members is kept. A larger
  * block is re-keyed: its members are regrouped by their next-level keys, each new block judged the
  * same way. A block still too large at the last level is reported as oversize and yields no
  * candidates, so its members stay unresolved (split, never merged).
  */
object OversizePolicy:
  enum Action:
    case Keep
    case Rekey(nextLevel: Int)
    case ReportOversize

  /** The step a multi-pass job applies to one block of `size` members at `level` of `levels`. */
  def action(level: Int, size: Long, maxBlockSize: Int, levels: Int): Action =
    require(maxBlockSize >= 1, s"maxBlockSize must be >= 1, got $maxBlockSize")
    if size <= maxBlockSize then Action.Keep
    else if level + 1 < levels then Action.Rekey(level + 1)
    else Action.ReportOversize

  /** A block: its level, its key, and its sorted distinct member IDs. */
  final case class Block(level: Int, key: String, memberIds: Vector[String])

  /** An oversize block left at the last level: its level, key, and member count. */
  final case class Oversize(level: Int, key: String, size: Int)

  final case class Plan(
      blocks: Vector[Block],
      oversize: Vector[Oversize],
      missingKey: Vector[String]
  )

  /** Reference in-memory application of [[action]] to a set of members, for tests and small groups.
    * A multi-pass Hadoop job applying [[action]] per block must produce the same blocks. Output is
    * sorted by (level, key) and does not depend on input order.
    *
    * @param keys
    *   per-member keys by level, as returned by [[BlockingKeys]]; empty means no key
    */
  def plan[A](
      members: Seq[A],
      id: A => String,
      keys: A => Vector[Vector[String]],
      levels: Int,
      maxBlockSize: Int
  ): Plan =
    def go(
        level: Int,
        ms: Seq[(String, Vector[Vector[String]])]
    ): (Vector[Block], Vector[Oversize]) =
      val grouped =
        ms.flatMap(m => m._2(level).map(_ -> m)).groupMap(_._1)(_._2).toVector.sortBy(_._1)
      val parts = grouped.map { (key, group) =>
        val distinct = group.distinctBy(_._1)
        action(level, distinct.size, maxBlockSize, levels) match
          case Action.Keep =>
            (Vector(Block(level, key, distinct.map(_._1).sorted.toVector)), Vector.empty)
          case Action.Rekey(next)    => go(next, distinct)
          case Action.ReportOversize => (Vector.empty, Vector(Oversize(level, key, distinct.size)))
      }
      (parts.flatMap(_._1), parts.flatMap(_._2))
    val (keyed, missing) = members.map(m => id(m) -> keys(m)).partition(_._2.nonEmpty)
    require(keyed.forall(_._2.size == levels), s"every keyed member needs $levels key levels")
    val (blocks, oversize) = go(0, keyed)
    val order = Ordering.by[Block, (Int, String)](b => (b.level, b.key))
    Plan(
      blocks.sorted(order),
      oversize.sortBy(o => (o.level, o.key)),
      missing.map(_._1).distinct.sorted.toVector
    )
