package scholarchain.entityresolution

import scholarchain.core.{AuthorMention, StableIds, WorkRecord}

/** A venue as a work names it: its namespaced ID and, when joined from a Venue record, its name. */
final case class VenueRef(id: Option[String], name: Option[String])

/** Match features (rule feat-v1). Every feature is deterministic and, when evidence exists on both
  * sides, a value in [0, 1] where 1 is the strongest agreement. None means "no evidence" (a side is
  * missing the field), which is deliberately different from 0.0 ("the evidence disagrees"); the
  * feature-map builders omit absent features. No feature is a threshold, score, or decision.
  */
object Features:
  // Feature-map keys, stable across releases of feat-v1.
  val NameSimilarity = "name_similarity"
  val InitialsCompatibility = "initials_compatibility"
  val AuthorOrderAgreement = "author_order_agreement"
  val CoauthorOverlap = "coauthor_overlap"
  val AffiliationCompatibility = "affiliation_compatibility"
  val OrcidAgreement = "orcid_agreement"
  val HomonymSuffixAgreement = "homonym_suffix_agreement"
  val TitleSimilarity = "title_similarity"
  val YearDistance = "year_distance"
  val VenueCompatibility = "venue_compatibility"

  private val VenueStopwords = Set("of", "on", "the", "and", "for", "in", "a", "an", "to")

  /** Jaro-Winkler similarity (prefix scale 0.1, prefix up to 4). "martha"/"marhta" gives 0.9611. */
  def jaroWinkler(a: String, b: String): Double =
    if a == b then 1.0
    else if a.isEmpty || b.isEmpty then 0.0
    else
      val window = (math.max(a.length, b.length) / 2 - 1).max(0)
      val used = Array.fill(b.length)(false)
      val matchedA = a.indices.flatMap { i =>
        (math.max(0, i - window) to math.min(b.length - 1, i + window))
          .find(j => !used(j) && b(j) == a(i))
          .map { j => used(j) = true; a(i) }
      }
      val m = matchedA.size.toDouble
      if m == 0 then 0.0
      else
        val matchedB = b.indices.filter(used).map(b(_))
        val transpositions = matchedA.zip(matchedB).count(_ != _) / 2
        val jaro = (m / a.length + m / b.length + (m - transpositions) / m) / 3
        val prefix = a.zip(b).take(4).takeWhile(_ == _).size
        jaro + prefix * 0.1 * (1 - jaro)

  private def jaccard[T](a: Set[T], b: Set[T]): Option[Double] =
    Option.when(a.nonEmpty && b.nonEmpty)((a & b).size.toDouble / (a | b).size)

  /** Jaro-Winkler of the comparable names (homonym suffix excluded). */
  def nameSimilarity(a: ParsedName, b: ParsedName): Option[Double] =
    Option.when(a.comparable.nonEmpty && b.comparable.nonEmpty)(
      jaroWinkler(a.comparable, b.comparable)
    )

  /** Given-name tokens aligned from the start. A pair is compatible when equal or when one is a
    * single letter that starts the other ("j" and "john"). Value: leading compatible pairs before
    * the first incompatible pair, divided by the shorter token count. "J. R. Smith" and "John
    * Smith" give 1.0; "John" and "James" give 0.0; "J. R." and "J. K." give 0.5.
    */
  def initialsCompatibility(a: ParsedName, b: ParsedName): Option[Double] =
    val n = math.min(a.givenNames.size, b.givenNames.size)
    def compatible(x: String, y: String) =
      x == y || (x.length == 1 && y.startsWith(x)) || (y.length == 1 && x.startsWith(y))
    Option.when(n > 0)(
      a.givenNames.zip(b.givenNames).takeWhile(compatible).size.toDouble / n
    )

  /** 1 - |posA - posB| / max(teamSizeA, teamSizeB), with 1-based positions. Same position gives
    * 1.0. None when a position is outside its team.
    */
  def authorOrderAgreement(posA: Int, teamA: Int, posB: Int, teamB: Int): Option[Double] =
    Option.when(posA >= 1 && posA <= teamA && posB >= 1 && posB <= teamB)(
      1.0 - math.abs(posA - posB).toDouble / math.max(teamA, teamB)
    )

  /** Jaccard of coauthor keys (familyKey|first initial) from the coauthors' printed names. */
  def coauthorOverlap(a: Seq[String], b: Seq[String]): Option[Double] =
    def keys(names: Seq[String]) = names
      .map(Normalize.name)
      .filter(_.familyKey.nonEmpty)
      .map(n => s"${n.familyKey}|${n.initials.take(1)}")
      .toSet
    jaccard(keys(a), keys(b))

  /** Overlap coefficient |A ∩ B| / min(|A|, |B|) of namespaced institution IDs, counting only the
    * namespaces both sides use (a ror: ID says nothing against an openalex: ID). None when no
    * namespace is shared.
    */
  def affiliationCompatibility(a: Set[String], b: Set[String]): Option[Double] =
    def ns(id: String) = id.takeWhile(_ != ':')
    val shared = a.map(ns) & b.map(ns)
    val (sa, sb) = (a.filter(i => shared(ns(i))), b.filter(i => shared(ns(i))))
    Option.when(sa.nonEmpty)((sa & sb).size.toDouble / math.min(sa.size, sb.size))

  /** 1.0 when both valid ORCID iDs (StableIds.normalizeOrcid) are equal, 0.0 when they differ (an
    * authoritative conflict), None when either side has no valid iD.
    */
  def orcidAgreement(a: Option[String], b: Option[String]): Option[Double] =
    for x <- a.flatMap(StableIds.normalizeOrcid); y <- b.flatMap(StableIds.normalizeOrcid)
    yield if x == y then 1.0 else 0.0

  /** DBLP homonym numbers: 1.0 when equal, 0.0 when different (DBLP says different people). */
  def homonymSuffixAgreement(a: ParsedName, b: ParsedName): Option[Double] =
    for x <- a.homonymSuffix; y <- b.homonymSuffix yield if x == y then 1.0 else 0.0

  /** Jaccard of distinct normalized title tokens (markup stripped). */
  def titleSimilarity(a: Option[String], b: Option[String]): Option[Double] =
    jaccard(Normalize.titleTokens(a).toSet, Normalize.titleTokens(b).toSet)

  /** 1 / (1 + |yearA - yearB|): 1.0 for the same year, 0.5 one year apart. */
  def yearDistance(a: Option[Int], b: Option[Int]): Option[Double] =
    for x <- a; y <- b yield 1.0 / (1 + math.abs(x - y))

  /** Same-namespace venue IDs: 1.0 when equal, else 0.0. Otherwise, with both names: 1.0 when the
    * normalized names are equal or one is the acronym of the other's significant words ("ICS" and
    * "International Conference on Supercomputing"), else the Jaccard of significant name tokens.
    * None without comparable evidence.
    */
  def venueCompatibility(a: VenueRef, b: VenueRef): Option[Double] =
    def ns(id: String) = id.takeWhile(_ != ':')
    def words(n: String) = Normalize.text(n).split(' ').toVector.filter(_.nonEmpty)
    def significant(n: String) = words(n).filterNot(VenueStopwords)
    def acronym(x: String, y: String) =
      words(x).size == 1 && words(x).head == significant(y).map(_.take(1)).mkString
    (a.id, b.id) match
      case (Some(x), Some(y)) if ns(x) == ns(y) => Some(if x == y then 1.0 else 0.0)
      case _                                    =>
        for x <- a.name; y <- b.name if words(x).nonEmpty && words(y).nonEmpty
        yield
          if words(x) == words(y) || acronym(x, y) || acronym(y, x) then 1.0
          else jaccard(significant(x).toSet, significant(y).toSet).getOrElse(0.0)

  /** Researcher features for two author mentions, each with the work it appears on. */
  def researcher(
      a: AuthorMention,
      aWork: WorkRecord,
      b: AuthorMention,
      bWork: WorkRecord
  ): Map[String, Double] =
    val (na, nb) = (Normalize.name(a.rawName), Normalize.name(b.rawName))
    def coauthors(m: AuthorMention, w: WorkRecord) =
      w.authors.filterNot(_.position == m.position).map(_.rawName)
    Seq(
      NameSimilarity -> nameSimilarity(na, nb),
      InitialsCompatibility -> initialsCompatibility(na, nb),
      AuthorOrderAgreement ->
        authorOrderAgreement(a.position, aWork.authors.size, b.position, bWork.authors.size),
      CoauthorOverlap -> coauthorOverlap(coauthors(a, aWork), coauthors(b, bWork)),
      AffiliationCompatibility ->
        affiliationCompatibility(a.institutionIds.toSet, b.institutionIds.toSet),
      OrcidAgreement -> orcidAgreement(a.orcid, b.orcid),
      HomonymSuffixAgreement -> homonymSuffixAgreement(na, nb)
    ).collect { case (k, Some(v)) => k -> v }.toMap

  /** Work features for two works; venue names come from joined Venue records when available. */
  def work(
      a: WorkRecord,
      aVenueName: Option[String],
      b: WorkRecord,
      bVenueName: Option[String]
  ): Map[String, Double] =
    Seq(
      TitleSimilarity -> titleSimilarity(a.title, b.title),
      YearDistance -> yearDistance(a.year, b.year),
      VenueCompatibility ->
        venueCompatibility(VenueRef(a.venueId, aVenueName), VenueRef(b.venueId, bVenueName))
    ).collect { case (k, Some(v)) => k -> v }.toMap
