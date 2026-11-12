package scholarchain.rag.runtime

import scholarchain.core.config.ScholarChainConfig
import scholarchain.core.{
  AnswerClaim,
  EvidenceItem,
  EvidenceType,
  Provenance,
  SchemaVersions,
  VerificationStatus
}

import scala.util.matching.Regex

/** Stage 9 (CLAIM_VERIFICATION, RUNTIME.md section 12): rule checks of every drafted claim against
  * the bundle, no model. Only graph items can support a value; semantic items never do.
  */
object Verifier:
  import CheckOutcome.*
  import EvidenceType.*

  val KindTypes: Map[ClaimKind, Set[EvidenceType]] = Map(
    ClaimKind.IDENTITY -> Set(IDENTITY_RESOLUTION),
    ClaimKind.METRIC_VALUE -> Set(METRIC_RECORD, RANKING_RECORD, TEMPORAL_CHANGE),
    ClaimKind.RANKING -> Set(RANKING_RECORD),
    ClaimKind.COAUTHORSHIP -> Set(COAUTHOR_EDGE),
    ClaimKind.PATH -> Set(GRAPH_PATH),
    ClaimKind.PATH_HOP -> Set(GRAPH_PATH),
    ClaimKind.COMPONENT -> Set(COMPONENT_RECORD, METRIC_RECORD),
    ClaimKind.COMMUNITY -> Set(COMMUNITY_RECORD, METRIC_RECORD),
    ClaimKind.BRIDGE -> Set(METRIC_RECORD, RANKING_RECORD),
    ClaimKind.TEMPORAL_CHANGE -> Set(TEMPORAL_CHANGE),
    ClaimKind.PUBLICATION -> Set(WORK_RECORD, COAUTHOR_EDGE, METRIC_RECORD),
    ClaimKind.FACET -> Set(FACET_RECORD)
  )
  val BridgeKeys = Set("crossCommunityDegree", "communitiesConnected", "participationCoefficient")
  val HopFacts = Vector("from", "to", "jointPublicationCount", "firstYear", "lastYear")
  val ContextOnly =
    "Context only: descriptive text retrieved by vector similarity; it is not verified against the graph."

  /** The rule version, part of the runtime run ID so a rule change never resumes earlier results
    * (ruling 29): v1 work order 4, v2 repair-01, v3 repair-02 (number words, asserted names).
    */
  val Version = "verifier-v3"

  private val NumberRe = "\\d+(?:\\.\\d+)?".r
  private val Quoted = "[\"“”]([^\"“”]+)[\"“”]".r
  private val YearRange =
    "\\b((?:19|20)\\d{2})\\s*(?:-|–|—|to|until|through)\\s*((?:19|20)\\d{2})\\b".r
  private val HopKey = "hop\\.(\\d+)\\.(.+)".r
  private val RankKey = "rank\\.(\\d+)\\.storedRank".r
  private val ListKey = "(?:rank|entry|member|neighbor)\\.\\d+\\.(.+)".r
  private val Temporal = "(from|to|delta)([A-Z]\\w*)".r
  // F3: what a semantic-only claim may not state.
  private val Relation =
    ("(?i)\\b(?:co-?authors?|co-?authored|co-?authorship|collaborat\\w*|joint|together|paths?|hops?|" +
      "components?|connected|connects?|communit(?:y|ies)|bridg\\w*|brok\\w*|degree|participation|coreness|" +
      "k-core|teams?|partners?|colleagues?|network|neighbou?rs?|counts?|number of|ranks?|ranked)\\b").r

  // ---- number words (ruling 29, N1) -----------------------------------------------------------

  private val Small = Vector(
    "zero",
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "eleven",
    "twelve",
    "thirteen",
    "fourteen",
    "fifteen",
    "sixteen",
    "seventeen",
    "eighteen",
    "nineteen"
  )
  private val Tens =
    Vector("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
  private val Ordinals = Vector(
    "first",
    "second",
    "third",
    "fourth",
    "fifth",
    "sixth",
    "seventh",
    "eighth",
    "ninth",
    "tenth"
  )
  private val WordValue: Map[String, Int] =
    Small.zipWithIndex.toMap ++ Tens.zipWithIndex.map((w, i) => w -> (20 + 10 * i))
  private def ci(s: String) = s"(?i)\\b(?:$s)\\b".r
  // Zero expressions attached to a measure or relation count as 0; "0 joint" binds to a stored joint
  // publication count of 0 (or edgeExists false). "No coauthorship path" is a path status, not 0.
  private val ZeroRules: Seq[(Regex, String)] = Seq(
    ci("no one") -> "nobody",
    ci(
      "(?:never|not)\\s+(?:\\w+ly\\s+)?(?:co-?authored|co-?author|collaborated|collaborate|" +
        "co-?wrote|co-?write|(?:published|worked|written)\\s+together)"
    ) -> "0 joint",
    ci(
      "no\\s+(?:direct\\s+)?(?:co-?authorships?|collaborations?)" +
        "(?:\\s+(?:edges?|links?|ties?|connections?|relationships?))?(?!\\s+paths?\\b)"
    ) -> "0 joint",
    "(?i)\\bno\\s+((?:(?:direct|joint|shared|common|distinct|unique|other|further|recorded|stored|known)\\s+)*(?:co-?authors?|collaborators?|publications?|papers?|works?|articles?))\\b".r -> "0 $1",
    ci("none") -> "0",
    "(?i)\\bone(?=\\s+(?:another|of)\\b)".r -> "", // "one of", "one another": not a count
    ci("a\\s+single|once") -> "1",
    ci("twice") -> "2"
  )
  // An ordinal followed within four words by a year is a time marker ("first in 2001"), not a rank.
  private val OrdinalRe =
    s"(?i)\\b(${Ordinals.mkString("|")})\\b(?!(?:\\s+[\\p{L}-]+){0,4}\\s+(?:19|20)\\d{2}\\b)".r
  private val CompoundRe =
    s"(?i)\\b(${Tens.mkString("|")})[- ](${Small.slice(1, 10).mkString("|")})\\b".r
  private val CardinalRe = s"(?i)\\b(${WordValue.keys.mkString("|")})\\b".r

  /** Number words written as digits, so they are bound like digits (ruling 29, N1): zero
    * expressions as 0, "a single" and "once" as 1, "twice" as 2, ordinals first to tenth as 1st to
    * 10th (the rank measure), cardinals zero to nineteen, the tens twenty to ninety, and tens-unit
    * compounds. "a" and "an" are articles, not 1.
    */
  def wordsToDigits(text: String): String =
    val zero = ZeroRules.foldLeft(text)((t, r) => r._1.replaceAllIn(t, r._2))
    val ordinal = OrdinalRe.replaceAllIn(
      zero,
      m =>
        val n = Ordinals.indexOf(m.group(1).toLowerCase) + 1
        s"$n${n match { case 1 => "st"; case 2 => "nd"; case 3 => "rd"; case _ => "th" }}"
    )
    val compound = CompoundRe.replaceAllIn(
      ordinal,
      m => (WordValue(m.group(1).toLowerCase) + WordValue(m.group(2).toLowerCase)).toString
    )
    CardinalRe.replaceAllIn(compound, m => WordValue(m.group(1).toLowerCase).toString)

  // ---- numbers (ruling 17) --------------------------------------------------------------------

  private def decimals(s: String) = s.dropWhile(_ != '.').drop(1).length
  private def num(s: String): Option[BigDecimal] =
    Option.when(s.trim.matches("-?\\d+(?:\\.\\d+)?"))(BigDecimal(s.trim))

  /** A written number equals a stored value: integers exactly (0.55 never verifies as 1); a decimal
    * when it is equal, or when at least 2 decimals are written and the stored value rounded half-up
    * to them equals it.
    */
  def numberMatches(written: String, stored: String): Boolean =
    (num(written), num(stored)) match
      case (Some(a), Some(b)) =>
        val d = decimals(written.trim)
        if d == 0 then b.isWhole && a == b
        else a == b || (d >= 2 && b.setScale(d, BigDecimal.RoundingMode.HALF_UP) == a)
      case _ => false

  /** An asserted value equals a stored fact: the same text, or the same number (numberMatches). */
  def same(claim: String, stored: String): Boolean =
    claim == stored || numberMatches(claim, stored)

  // Measure vocabulary: a number bound to a fact must be written next to a name of its measure.
  private def words(s: String): Regex = s"(?i)\\b(?:$s)\\b".r
  val Families: Map[String, Regex] = Map(
    "publications" -> words("publications?|papers?|works?|published|articles?"),
    "coauthors" -> words(
      "distinct co-?authors?|unique co-?authors?|co-?authors?|collaborators?|breadth"
    ),
    "occurrences" -> words("co-?author(?:ship)? occurrences?|occurrences?"),
    "average" -> words("average|mean|per paper"),
    "team" -> words("team size|teams?|authors?"),
    "weighted" -> words("weighted degree|weighted|intensity"),
    "degree" -> words("degree|co-?authors?|neighbou?rs?"),
    "component" -> words("components?|researchers?|members?|nodes?|size"),
    "community" -> words("communit(?:y|ies)|members?|researchers?|size"),
    "crossDegree" -> words("cross-community degree|cross-community|cross community|bridge"),
    "communities" -> words("communit(?:y|ies)|bridge"),
    "participation" -> words("participation coefficient|participation|bridge"),
    "coreness" -> words("coreness|k-core|core"),
    "joint" -> words(
      "joint|together|co-?authored|shared|publications?|papers?|works?|collaborations?"
    ),
    "hops" -> words("hops?|steps?|links?|length|edges?|depth"),
    "edges" -> words("edges?|links?|collaborations?|connections?|ties"),
    "share" -> "(?i)\\b(?:share|fraction|proportion|percent)\\b|%".r,
    "candidates" -> words("researchers?|candidates?|people|persons?"),
    "confidence" -> words("confidence"),
    "rank" -> "(?i)\\b(?:rank\\w*|top|place|position|first|second|third)\\b|#|(?<=\\d)(?:st|nd|rd|th)\\b".r,
    "list" -> words("top|entries|researchers?|listed|stored|first"),
    "retention" -> words("retention|retained|kept|stayed")
  )
  private val FacetCount = words("publications?|papers?|works?|affiliat\\w*|mentions?|counts?")
  private val Base: Map[String, String] = Map(
    "publicationCount" -> "publications",
    "works" -> "publications",
    "totalWorks" -> "publications",
    "minWorks" -> "publications",
    "distinctCoauthorCount" -> "coauthors",
    "maximumCoauthorsOnOnePaper" -> "coauthors",
    "totalCoauthorOccurrences" -> "occurrences",
    "averageCoauthorsPerPaper" -> "average",
    "maximumTeamSize" -> "team",
    "weightedDegree" -> "weighted",
    "graphDegree" -> "degree",
    "componentSize" -> "component",
    "communitySize" -> "community",
    "membersReturned" -> "community",
    "crossCommunityDegree" -> "crossDegree",
    "communitiesConnected" -> "communities",
    "participationCoefficient" -> "participation",
    "coreness" -> "coreness",
    "jointPublicationCount" -> "joint",
    "pathLength" -> "hops",
    "maxDepth" -> "hops",
    "internalEdges" -> "edges",
    "crossCommunityEdges" -> "edges",
    "topShare" -> "share",
    "dominantShare" -> "share",
    "candidateCount" -> "candidates",
    "identityConfidence" -> "confidence",
    "storedRank" -> "rank",
    "returnedSize" -> "list",
    "storedSize" -> "list",
    "communityRetention" -> "retention"
  )
  private val Years =
    Set("year", "firstCollaborationYear", "lastCollaborationYear", "firstYear", "lastYear")

  /** NEAR: a measure name of the family must be the nearest one to the number in its clause. WHOLE:
    * list and change facts; the measure is named once for the whole text. YEAR: a year fact (the
    * value binding is the check).
    */
  enum Scope:
    case NEAR, WHOLE, YEAR

  /** A passed values key with every stored value of it in the cited items, and how a number bound
    * to it must be worded.
    */
  final case class Binding(key: String, stored: Vector[String], family: String, scope: Scope):
    def regex: Regex = family match
      case "facetCount"                 => FacetCount
      case f if f.startsWith("change:") => Families(f.drop(7))
      case f                            => Families(f)
    def binds(n: String): Boolean =
      stored.exists(s => numberMatches(n, s) || numberMatches(n, s.stripPrefix("-")))

  private def isWindowKey(k: String) =
    val l = k.toLowerCase
    l == "window" || l.endsWith("windowstartyear") || l.endsWith("windowendyear")

  /** How a number bound to key k is checked; None: k cannot hold a written number (window bounds,
    * ruling 18, and keys outside the vocabulary).
    */
  def rule(k: String, cited: Seq[EvidenceItem]): Option[(String, Scope)] =
    def metricFamily = cited
      .filter(_.evidenceType == RANKING_RECORD)
      .flatMap(_.facts.get("metric"))
      .flatMap(Base.get)
      .headOption
    def temporal(attr: String) =
      val a = attr.head.toLower +: attr.tail
      if isWindowKey(a) then None
      else Base.get(a).map(f => (s"change:$f", Scope.WHOLE))
    if isWindowKey(k) then None
    else
      k match
        case HopKey(_, f) if Years(f)                  => Some(("year", Scope.YEAR))
        case HopKey(_, f)                              => Base.get(f).map((_, Scope.NEAR))
        case RankKey(_)                                => Some(("rank", Scope.NEAR))
        case s"rank.$_.value"                          => metricFamily.map((_, Scope.WHOLE))
        case s"rank.$_.maximumTeamSize"                => Some(("team", Scope.WHOLE))
        case s"entry.$_.count"                         => Some(("facetCount", Scope.WHOLE))
        case s"member.$_.count" | s"neighbor.$_.count" => Some(("edges", Scope.WHOLE))
        case ListKey(_)                                => None
        case "lastStoredValue"                         => metricFamily.map((_, Scope.WHOLE))
        case "edgeExists"                              => Some(("joint", Scope.NEAR))
        case y if Years(y)                             => Some(("year", Scope.YEAR))
        case b if Base.contains(b)                     => Some((Base(b), Scope.NEAR))
        case Temporal(_, attr)                         => temporal(attr)
        case _                                         => None

  // ---- context ---------------------------------------------------------------------------------

  /** corpusName: whether a name is a researcher name anywhere in the corpus (EntityLinkCache read
    * port, one bounded lookup per name, never a full load). notes: the fixed-template lines (window
    * note, identity links, missing evidence) that model uncertainty lines may restate.
    */
  final case class Context(
      bundle: EvidenceBundle,
      resolutions: Vector[ResearcherResolution],
      cfg: ScholarChainConfig,
      corpusName: String => Boolean = _ => false,
      notes: Vector[String] = Vector.empty
  ):
    val graph: Map[String, EvidenceItem] = bundle.graphEvidence.map(e => e.evidenceId -> e).toMap
    val semanticItems: Map[String, EvidenceItem] =
      bundle.semanticEvidence.map(e => e.evidenceId -> e).toMap
    val semantic: Set[String] = semanticItems.keySet
    val resolvedIds: Set[String] = resolutions.flatMap(_.researcherId).toSet
    val unresolvedNames: Vector[String] =
      resolutions.filter(_.decision != ResolutionDecision.RESOLVED_DOMINANT).map(_.name)
    private val windowNames = cfg.graph.windows.map(_.name).toSet

    /** Display names anywhere in the graph evidence (for the identity rule). */
    val knownNames: Set[String] = bundle.graphEvidence
      .flatMap(_.facts.collect {
        case (k, v)
            if (k.endsWith("displayName") || k.endsWith("Name") || k
              .matches("displayName[AB]")) && v.nonEmpty =>
          v
      })
      .toSet

    /** Researcher names of the bundle (resolved names, candidates and their coauthors, graph
      * evidence names, community members).
      */
    val researcherNames: Set[String] = knownNames ++ resolutions.flatMap(r =>
      Vector(r.name) ++ (r.candidates ++ r.otherNameMatches).flatMap(c =>
        c.displayName +: c.topCoauthors.map(_.name)
      )
    ) ++ bundle.graphEvidence.flatMap(_.facts.collect {
      case (k, v) if k.matches("member\\.\\d+\\.name") => v
    })

    /** researcherNames as whole-word patterns, longest first (ruling 29, N2). */
    val researcherPatterns: Vector[(String, Regex)] = researcherNames.toVector
      .filter(_.exists(_.isLetter))
      .sortBy(n => (-n.length, n))
      .map(n => n -> s"(?<![\\p{L}\\p{N}])${Regex.quote(n)}(?![\\p{L}\\p{N}])".r)

    /** Every text of the bundle (names, titles, topics, venues, institutions, labels, IDs), longest
      * first; window names are kept apart so the window rule still sees them.
      */
    val knownText: Vector[String] =
      ((bundle.graphEvidence ++ bundle.semanticEvidence).flatMap(_.facts.values) ++
        researcherNames ++ resolutions.flatMap(r =>
          (r.candidates ++ r.otherNameMatches).flatMap(_.institutions.map(_.name))
        ))
        // Enum and boolean words in lower case (true, false) are not stripped.
        .filter(v =>
          v.length >= 2 && v.exists(_.isLetter) && !windowNames(v) &&
            (v.exists(_.isDigit) || v.contains(' ') || v.exists(_.isUpper))
        )
        .distinct
        .sortBy(-_.length)
        .toVector
    val knownAll: Vector[String] = (knownText ++ windowNames).distinct.sortBy(-_.length)

    def strip(text: String): String = knownText.foldLeft(text)((t, v) => t.replace(v, " "))

    /** The stored window (name, first year, last year) of a window or comparison name. */
    def windows(name: Option[String]): Vector[(String, Int, Int)] =
      name.toVector
        .flatMap(_.split('>'))
        .flatMap(n => Windows.byName(cfg.graph.windows, n))
        .map(w => (w.name, w.startYear, w.endYear))
        .distinct

  // ---- text rules -----------------------------------------------------------------------------

  /** Clause boundaries: ; : ! ? and a period followed by whitespace or the end. */
  private def clause(t: String, from: Int, to: Int): (Int, Int) =
    def boundary(i: Int) =
      val ch = t(i)
      ";:!?".contains(ch) || (ch == '.' && (i + 1 == t.length || t(i + 1).isWhitespace))
    val s = (from - 1 to 0 by -1).find(boundary).fold(0)(_ + 1)
    val e = (to until t.length).find(boundary).getOrElse(t.length)
    (s, e)

  /** The families whose measure name is nearest to [ns, ne) in its clause, ignoring parenthetical
    * remarks that do not contain the number.
    */
  private def nearest(t: String, ns: Int, ne: Int): Set[String] =
    val (cs, ce) = clause(t, ns, ne)
    val c = t.substring(cs, ce)
    val (a, b) = (ns - cs, ne - cs)
    val groups =
      var depth = 0
      var start = -1
      val out = Vector.newBuilder[(Int, Int)]
      c.indices.foreach { i =>
        if c(i) == '(' then
          if depth == 0 then start = i
          depth += 1
        else if c(i) == ')' && depth > 0 then
          depth -= 1
          if depth == 0 then out += ((start, i + 1))
      }
      out.result().filterNot((s, e) => s <= a && b <= e)
    val view = StringBuilder()
    var i = 0
    groups.foreach { (s, e) =>
      view ++= c.substring(i, s)
      i = e
    }
    view ++= c.substring(i)
    val na = a - groups.filter(_._2 <= a).map((s, e) => e - s).sum
    val nb = na + (b - a)
    val v = view.result()
    val hits = Families.toVector.flatMap((f, r) =>
      r.findAllMatchIn(v).map { m =>
        val d = if m.start >= nb then m.start - nb else if m.end <= na then na - m.end else 0
        (f, d)
      }
    )
    if hits.isEmpty then Set.empty
    else
      val min = hits.map(_._2).min
      hits.filter(_._2 == min).map(_._1).toSet

  private val WindowName = (n: String) => s"(?i)(?<![\\w-])${n.replace("-", "[- ]")}(?![\\w-])".r

  /** Number problems and window problems of a text (rulings 17 and 18). Every number must be bound
    * to a passed values fact with an equal stored value and be named by its measure; a window name
    * or year range must be an allowed window, unless both years are asserted year facts.
    */
  private def textProblems(
      text: String,
      bindings: Seq[Binding],
      allowed: Seq[(String, Int, Int)],
      extraRanges: Set[(Int, Int)],
      freeNumbers: Set[String],
      c: Context
  ): (Vector[String], Vector[String]) =
    val t = c.strip(text)
    val allowedNames = allowed.map(_._1).toSet
    val windowProblems = Vector.newBuilder[String]
    c.cfg.graph.windows
      .filter(w => w.name.exists(_.isLetter) && !allowedNames(w.name))
      .filter(w => WindowName(w.name).findFirstIn(t).isDefined)
      .foreach(w => windowProblems += s"names the window ${w.name}")
    def yearFact(y: String) =
      bindings.exists(b => b.scope == Scope.YEAR && b.binds(y)) || freeNumbers(y)
    val t2 = wordsToDigits(
      YearRange.replaceAllIn(
        t,
        m =>
          val (a, b) = (m.group(1).toInt, m.group(2).toInt)
          val ok = allowed.exists((_, s, e) => s == a && e == b) || extraRanges((a, b)) ||
            (yearFact(m.group(1)) && yearFact(m.group(2)))
          if !ok then
            windowProblems += s"names $a-$b, which is not the window ${
                if allowed.isEmpty then "(none)" else allowed.map(_._1).mkString(" or ")
              }"
          " " * m.matched.length
      )
    )
    val numberProblems = NumberRe
      .findAllMatchIn(t2)
      .toVector
      .flatMap { m =>
        val n = m.matched
        val bound = bindings.filter(_.binds(n))
        if freeNumbers(n) || bound.exists(_.scope == Scope.YEAR) then None
        else if bound.isEmpty then Some(s"$n is not an asserted fact of a cited item")
        else if bound.exists(b => b.scope == Scope.WHOLE && b.regex.findFirstIn(t2).isDefined)
        then None
        else
          val near = nearest(t2, m.start, m.end)
          Option.when(!bound.exists(b => b.scope == Scope.NEAR && near(b.family)))(
            s"$n is not written next to the name of ${bound.map(_.key).distinct.mkString(" or ")}"
          )
      }
    (numberProblems, windowProblems.result())

  private def numbersIn(text: String, c: Context): Set[String] =
    NumberRe.findAllIn(wordsToDigits(YearRange.replaceAllIn(c.strip(text), " "))).toSet

  private def rangesIn(text: String, c: Context): Set[(Int, Int)] =
    YearRange.findAllMatchIn(c.strip(text)).map(m => (m.group(1).toInt, m.group(2).toInt)).toSet

  /** Ruling 29 (N2): the (researcher ID, name) pairs an asserted key k of item e is about: a hop's
    * from and to, a rank's researcher, an edge's two endpoints, a path's source and target (every
    * researcher on it for researcherIds), a community's members, a facet's subject and entries, or
    * the item's researcher. Ranking list facts (metric, sizes) and works are about nobody.
    */
  private def researchersOf(k: String, e: EvidenceItem): Vector[(String, String)] =
    def pair(id: String, name: String) =
      e.facts.get(id).map(_ -> e.facts.getOrElse(name, "")).toVector
    def hop(n: String) =
      pair(s"hop.$n.from", s"hop.$n.fromName") ++ pair(s"hop.$n.to", s"hop.$n.toName")
    def indexed(p: String) =
      val r = s"$p\\.(\\d+)\\.id".r
      e.facts.keys.toVector
        .collect { case r(n) => n }
        .flatMap(n => pair(s"$p.$n.id", s"$p.$n.name"))
    val ends = pair("source", "sourceName") ++ pair("target", "targetName")
    k match
      case HopKey(n, _)  => hop(n)
      case s"rank.$n.$_" => pair(s"rank.$n.researcherId", s"rank.$n.displayName")
      case _             =>
        e.evidenceType match
          case GRAPH_PATH if k == "researcherIds" =>
            e.facts.keys.toVector.collect { case HopKey(n, "from") => n }.flatMap(hop) ++ ends
          case GRAPH_PATH    => ends
          case COAUTHOR_EDGE =>
            pair("researcherA", "displayNameA") ++ pair("researcherB", "displayNameB")
          case COMMUNITY_RECORD             => indexed("member")
          case FACET_RECORD                 => pair("subjectId", "displayName") ++ indexed("entry")
          case RANKING_RECORD | WORK_RECORD => Vector.empty
          case _                            => pair("researcherId", "displayName")

  /** Ruling 29 (N2): the researcher names a claim may write: the researchers of the cited graph
    * items whose facts its values assert (researchersOf; window keys assert no researcher), with
    * the resolved and candidate names of those researchers. A DESCRIPTIVE claim may also name its
    * resolved subjects (it is context only). Resolution candidates and their coauthors alone never
    * allow a name.
    */
  def allowedNames(d: ClaimDraft, cited: Seq[EvidenceItem], c: Context): Set[String] =
    val rs = for
      (k, v) <- d.values.toVector if !isWindowKey(k)
      e <- cited if e.facts.get(k).exists(same(v, _))
      r <- researchersOf(k, e)
    yield r
    val ids = rs.map(_._1).toSet ++
      (if d.kind == ClaimKind.DESCRIPTIVE then d.subjectIds.filter(c.resolvedIds) else Nil)
    rs.map(_._2).filter(_.nonEmpty).toSet ++ c.resolutions
      .filter(_.researcherId.exists(ids))
      .flatMap(r => r.name +: r.candidates.filter(x => ids(x.researcherId)).map(_.displayName))

  /** Researcher names of the bundle (researcherNames: graph, resolution, candidate, coauthor, and
    * member names) written in the text that are not allowed, matched whole-word and longest first
    * (a matched name is removed before shorter ones are tried); and unresolved names used.
    */
  private def namesBad(
      text: String,
      allowed: Set[String],
      evidence: Seq[EvidenceItem],
      c: Context
  ): (Vector[String], Vector[String]) =
    // A name inside a cited work's title is title text, not a statement about that researcher.
    val untitled = evidence
      .filter(_.evidenceType == WORK_RECORD)
      .flatMap(_.facts.get("title"))
      .sortBy(-_.length)
      .foldLeft(text)((t, x) => t.replace(x, " "))
    val foreign = c.researcherPatterns
      .foldLeft((untitled, Vector.empty[String])) { case ((t, bad), (n, r)) =>
        if r.findFirstIn(t).isEmpty then (t, bad)
        else (r.replaceAllIn(t, " "), if allowed(n) then bad else bad :+ n)
      }
      ._2
      .sorted
    val unresolved = c.unresolvedNames.filter(n => text.contains(n))
    (foreign, unresolved)

  /** Name-like spans (two or more capitalized tokens, RulePlanner.names) that are no text of the
    * bundle (ruling 20): each with whether it is a corpus researcher name.
    */
  def unknownNames(text: String, c: Context): Vector[(String, Boolean)] =
    val stripped = c.knownAll.foldLeft(text)((t, v) => t.replace(v, " "))
    RulePlanner
      .names(stripped)
      .filterNot(s => c.knownAll.exists(_.contains(s)))
      .map(s => (s, c.corpusName(s)))

  private def titlesBad(text: String, cited: Seq[EvidenceItem]): Vector[String] =
    def norm(t: String) = t.replaceAll("\\s+", " ").trim.stripSuffix(".").trim
    val raw = cited.filter(_.evidenceType == WORK_RECORD).flatMap(_.facts.get("title"))
    val titles = raw.map(norm).toSet
    // A cited title quoted exactly is removed first, so a title holding a quote mark still passes.
    val rest = raw
      .sortBy(-_.length)
      .foldLeft(text)((t, x) => t.replace(s"\"$x\"", " ").replace(s"“$x”", " "))
    Quoted.findAllMatchIn(rest).map(_.group(1)).filterNot(q => titles.contains(norm(q))).toVector

  private def describe(spans: Vector[(String, Boolean)]): String =
    spans
      .map((s, corpus) =>
        if corpus then s"'$s' (a corpus researcher who is not in the evidence)"
        else s"'$s' (not a researcher, title, venue, topic, or institution of the evidence)"
      )
      .mkString(", ")

  // ---- claims -----------------------------------------------------------------------------------

  /** The passed values of a claim as bindings; rank keys only for a RANKING claim that also names
    * the researcher of that rank (ruling 17).
    */
  private def rankOk(d: ClaimDraft, k: String, cited: Seq[EvidenceItem]): Boolean = k match
    case RankKey(n) =>
      d.kind == ClaimKind.RANKING && d.values
        .get(s"rank.$n.researcherId")
        .exists(id => cited.exists(_.facts.get(s"rank.$n.researcherId").contains(id)))
    case _ => true

  private def bindings(d: ClaimDraft, cited: Seq[EvidenceItem]): Vector[Binding] =
    d.values.toVector.sortBy(_._1).flatMap { (k, v) =>
      val holders = cited.filter(_.facts.contains(k))
      for
        (family, scope) <- rule(k, cited)
        if rankOk(d, k, cited) && holders.exists(e => same(v, e.facts(k)))
      yield Binding(
        k,
        holders.map(_.facts(k)).distinct.toVector.flatMap(stored(k, _)),
        family,
        scope
      )
    }

  // edgeExists false: the pair has 0 joint publications in the window ("never coauthored" binds).
  private def stored(k: String, v: String): Option[String] =
    if k != "edgeExists" then Some(v) else Option.when(v == "false")("0")

  def check(d: ClaimDraft, c: Context): ClaimCheck =
    val cited = d.evidenceIds.flatMap(c.graph.get)
    val unknown = d.evidenceIds.filterNot(id => c.graph.contains(id) || c.semantic.contains(id))
    val material = d.kind != ClaimKind.DESCRIPTIVE
    val checks = Vector.newBuilder[CheckResult]
    def add(rule: String, o: CheckOutcome, detail: String) = checks += CheckResult(rule, o, detail)

    add(
      "evidence-exists",
      if d.evidenceIds.isEmpty || unknown.nonEmpty then FAIL else PASS,
      if d.evidenceIds.isEmpty then "no evidence cited"
      else if unknown.nonEmpty then s"not in the bundle: ${unknown.mkString(" ")}"
      else "all cited items are in the bundle"
    )
    if material then
      add(
        "graph-evidence-required",
        if cited.isEmpty then FAIL else PASS,
        if cited.isEmpty then s"vector similarity is not evidence for a ${d.kind} claim"
        else s"${cited.size} graph item(s) cited"
      )
      val allowed = KindTypes(d.kind)
      val typeOk = cited.exists(e => allowed(e.evidenceType)) &&
        (d.kind != ClaimKind.BRIDGE || d.values.keySet.exists(BridgeKeys) ||
          cited.exists(e =>
            e.evidenceType == RANKING_RECORD && e.facts.get("metric").exists(BridgeKeys)
          ))
      add(
        "kind-matches-type",
        if typeOk then PASS else FAIL,
        s"${d.kind} needs ${allowed.mkString(" or ")}"
      )
      if d.values.isEmpty then add("values-required", FAIL, s"a ${d.kind} claim asserts no fact")

    d.values.toVector.sortBy(_._1).foreach { (k, v) =>
      val holders = cited.filter(_.facts.contains(k))
      if holders.isEmpty then
        add("asserted-values", MISSING, s"$k: no cited graph item has this fact")
      else if holders.exists(e => same(v, e.facts(k))) then add("asserted-values", PASS, s"$k")
      else
        add(
          "asserted-values",
          CONFLICT,
          s"$k: the claim says $v, the evidence says ${holders.map(_.facts(k)).distinct.mkString(" or ")}"
        )
    }
    d.values.keys.toVector.sorted.filterNot(rankOk(d, _, cited)).foreach { k =>
      add("rank", FAIL, s"$k: a rank is asserted only by a RANKING claim naming its researcher")
    }

    val bound = bindings(d, cited)
    val (badNumbers, badWindows) =
      textProblems(d.text, bound, c.windows(d.window), Set.empty, Set.empty, c)
    if material || badNumbers.nonEmpty then
      add(
        "numbers-in-text",
        if badNumbers.isEmpty then PASS else FAIL,
        if badNumbers.isEmpty then "every number is an asserted, named fact"
        else badNumbers.mkString("; ")
      )

    val windowed = cited.flatMap(_.facts.get("window")).distinct
    if material && d.kind != ClaimKind.IDENTITY && windowed.nonEmpty then
      d.window match
        case None                            => add("window", MISSING, "the claim names no window")
        case Some(w) if windowed.contains(w) => add("window", PASS, w)
        case Some(w)                         =>
          add(
            "window",
            CONFLICT,
            s"the claim says $w, the evidence covers ${windowed.mkString(" ")}"
          )
    if badWindows.nonEmpty then add("window", CONFLICT, s"the text ${badWindows.mkString("; ")}")

    val subjectsOk = d.subjectIds.filterNot(id =>
      c.resolvedIds(id) || cited.exists(e =>
        e.subjectIds.contains(id) || e.facts.values.exists(_ == id)
      )
    )
    val (foreign, unresolved) = namesBad(d.text, allowedNames(d, cited, c), cited, c)
    val spans = unknownNames(d.text, c)
    if unresolved.nonEmpty && material then
      add("identity", CONFLICT, s"an unresolved name is used: ${unresolved.mkString(", ")}")
    else if subjectsOk.nonEmpty || foreign.nonEmpty || spans.nonEmpty then
      add(
        "identity",
        FAIL,
        (Option.when(subjectsOk.nonEmpty || foreign.nonEmpty)(
          s"not resolved or cited: ${(subjectsOk ++ foreign).mkString(", ")}"
        ) ++ Option.when(spans.nonEmpty)(s"names ${describe(spans)}")).mkString("; ")
      )
    else add("identity", PASS, "every subject and name is resolved or cited")

    if d.kind == ClaimKind.PATH then
      val p = cited.filter(_.evidenceType == GRAPH_PATH)
      val keys = Seq("pathLength", "researcherIds", "status").filter(d.values.contains)
      if keys.isEmpty then
        add("path-hops", MISSING, "the claim asserts no pathLength, researcherIds, or status")
      else if p.exists(e => keys.forall(k => e.facts.get(k).exists(same(d.values(k), _)))) then
        add("path-hops", PASS, keys.mkString(" "))
      else add("path-hops", CONFLICT, "the path differs from the GRAPH_PATH evidence")
    if d.kind == ClaimKind.PATH_HOP then
      val hops = d.values.keys.collect { case HopKey(k, _) => k }.toVector.distinct.sorted
      val paths = cited.filter(_.evidenceType == GRAPH_PATH)
      if hops.isEmpty then add("path-hops", FAIL, "the claim names no hop")
      hops.foreach { k =>
        val missing = HopFacts.filterNot(f => d.values.contains(s"hop.$k.$f"))
        val ok = paths.exists(e =>
          HopFacts.forall(f =>
            d.values.get(s"hop.$k.$f").exists(v => e.facts.get(s"hop.$k.$f").exists(same(v, _)))
          )
        )
        if missing.nonEmpty then
          add("path-hops", FAIL, s"hop $k: the claim does not assert ${missing.mkString(", ")}")
        else add("path-hops", if ok then PASS else CONFLICT, s"hop $k")
      }
      val hopEdges = hops.flatMap(k => paths.flatMap(_.facts.get(s"hop.$k.edgeId"))).toSet
      val strayEdges = cited.filter(e =>
        e.evidenceType == COAUTHOR_EDGE && !e.facts.get("edgeId").exists(hopEdges)
      )
      if strayEdges.nonEmpty then
        add("path-hops", FAIL, "a cited coauthor edge is not the edge of an asserted hop")

    val badTitles = titlesBad(d.text, cited)
    if badTitles.nonEmpty then
      add("titles", FAIL, s"${badTitles.size} quoted title(s) are not titles of cited works")

    if !material then
      // Ruling 19: semantic context is never VERIFIED; it stays only as labelled context.
      val stripped = wordsToDigits(c.strip(d.text))
      val people = c.researcherNames.count(n => n.nonEmpty && d.text.contains(n)) + spans.size
      val states = NumberRe.findFirstIn(stripped).isDefined ||
        Relation.findFirstIn(stripped).isDefined
      if people <= 1 && !states then add("context-only", MISSING, ContextOnly)
      else
        add(
          "context-only",
          FAIL,
          "semantic context may name at most one researcher and state no count, relationship, path, component, community, or bridge measure"
        )

    val rs = checks.result()
    val status =
      if rs.exists(_.outcome == CONFLICT) then VerificationStatus.CONFLICTING
      else if rs.exists(_.outcome == FAIL) then VerificationStatus.UNSUPPORTED
      else if rs.exists(_.outcome == MISSING) then
        if rs.exists(_.outcome == PASS) then VerificationStatus.PARTIALLY_SUPPORTED
        else VerificationStatus.UNSUPPORTED
      else VerificationStatus.VERIFIED
    val action = status match
      case VerificationStatus.VERIFIED            => ClaimAction.KEPT
      case VerificationStatus.PARTIALLY_SUPPORTED => ClaimAction.LABELLED
      case _                                      => ClaimAction.REMOVED
    ClaimCheck(d.claimId, status, rs, action)

  /** A summary or uncertainty line passes the claims' numbers, windows, names, and titles rules
    * (ruling 20): its numbers are facts the kept claims asserted (or numbers of the fixed notes),
    * its windows are the answer's or the kept claims' (or ranges of the fixed notes), and every
    * researcher name is one the kept claims may write (ruling 29, N2).
    */
  private def lineOk(
      text: String,
      evidence: Seq[EvidenceItem],
      names: Set[String],
      bound: Seq[Binding],
      allowed: Seq[(String, Int, Int)],
      ranges: Set[(Int, Int)],
      free: Set[String],
      c: Context
  ): Boolean =
    val (foreign, unresolved) = namesBad(text, names, evidence, c)
    val (badNumbers, badWindows) = textProblems(text, bound, allowed, ranges, free, c)
    badNumbers.isEmpty && badWindows.isEmpty && foreign.isEmpty && unresolved.isEmpty &&
    unknownNames(text, c).isEmpty && titlesBad(text, evidence).isEmpty

  final case class Result(
      report: VerificationReport,
      claims: Vector[AnswerClaim],
      summary: String,
      uncertainty: Vector[String]
  )

  def verify(out: SynthesisOutput, c: Context, queryId: String, prov: Provenance): Result =
    val checks = out.claims.map(check(_, c))
    val byId = checks.map(k => k.claimId -> k).toMap
    val keptDrafts = out.claims.filter(d => byId(d.claimId).action != ClaimAction.REMOVED)
    val claims = keptDrafts.map { d =>
      val k = byId(d.claimId)
      val label = Option.when(k.status == VerificationStatus.PARTIALLY_SUPPORTED)(
        if k.checks.exists(x => x.rule == "context-only" && x.outcome == MISSING) then ContextOnly
        else
          "Partially supported: " + k.checks
            .filter(_.outcome == MISSING)
            .map(_.detail)
            .mkString("; ")
      )
      AnswerClaim(
        SchemaVersions.AnswerClaim,
        d.claimId,
        d.text,
        d.evidenceIds,
        k.status,
        label,
        prov
      )
    }
    val keptEvidence = keptDrafts.flatMap(_.evidenceIds).flatMap(c.graph.get).distinct
    val keptBindings = keptDrafts.flatMap(d => bindings(d, d.evidenceIds.flatMap(c.graph.get)))
    val keptNames =
      keptDrafts.flatMap(d => allowedNames(d, d.evidenceIds.flatMap(c.graph.get), c)).toSet
    val answerWindows =
      (c.bundle.windows.map(w => Option(w.window).filter(_.nonEmpty)) ++ keptDrafts.map(_.window))
        .flatMap(c.windows)
        .distinct
    val summary =
      if keptDrafts.nonEmpty && lineOk(
          out.summary,
          keptEvidence,
          keptNames,
          keptBindings,
          answerWindows,
          Set.empty,
          Set.empty,
          c
        )
      then out.summary
      else keptDrafts.map(_.text).mkString(" ")
    val noteRanges = c.notes.flatMap(rangesIn(_, c)).toSet
    val noteNumbers = c.notes.flatMap(numbersIn(_, c)).toSet
    val (lines, dropped) = out.uncertainty.partition(
      lineOk(
        _,
        c.bundle.graphEvidence,
        keptNames,
        keptBindings,
        answerWindows,
        noteRanges,
        noteNumbers,
        c
      )
    )
    val counts = VerificationCounts(
      checks.count(_.status == VerificationStatus.VERIFIED),
      checks.count(_.status == VerificationStatus.PARTIALLY_SUPPORTED),
      checks.count(_.status == VerificationStatus.UNSUPPORTED),
      checks.count(_.status == VerificationStatus.CONFLICTING)
    )
    Result(
      VerificationReport(RuntimeSchemas.Verification, queryId, checks, counts),
      claims,
      summary,
      lines ++ Option.when(dropped.nonEmpty)(
        s"${dropped.size} drafted uncertainty line(s) were left out because they failed verification."
      )
    )
