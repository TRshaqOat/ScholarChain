package scholarchain.rag

import scholarchain.core.config.TimeWindow

import java.util.Locale

/** Rendering templates `rendering-v1` (RAG.md, "Rendering templates"). Deterministic text from the
  * facts of one candidate; no LLM. The same facts and window always give the same text, whatever
  * the key order of the facts JSON.
  *
  * Facts are untrusted data. Every string from them is cleaned (control and format characters
  * become spaces, whitespace collapses, length is capped), so a document is one paragraph and its
  * length is bounded. Required facts missing or of the wrong type make the document a Left; an
  * absent optional fact drops its sentence.
  */
object Templates:
  val Version = "rendering-v1"
  val MaxNameChars = 200
  val MaxTitleChars = 300

  val DocumentTypes: Vector[String] = Vector(
    "researcher-profile",
    "collaboration-community",
    "collaboration-path",
    "topic-summary",
    "paper-summary"
  )

  /** One line of untrusted text, at most `max` code points (a cut text ends in "..."). */
  def clean(s: String, max: Int): String =
    val flat = s
      .map(c =>
        if Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ||
          Character.getType(c) == Character.LINE_SEPARATOR ||
          Character.getType(c) == Character.PARAGRAPH_SEPARATOR
        then ' '
        else c
      )
      .replaceAll("(?U)\\s+", " ")
      .trim
    if flat.codePointCount(0, flat.length) <= max then flat
    else flat.substring(0, flat.offsetByCodePoints(0, max - 3)).trim + "..."

  /** Typed reads of a facts object. JSON null counts as absent (Spark omits nulls anyway). */
  final class Facts(val obj: ujson.Obj):
    private def get(k: String) = obj.value.get(k).filter(_ != ujson.Null)
    def has(k: String): Boolean = get(k).nonEmpty
    def str(k: String): Either[String, String] =
      get(k).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty).toRight(s"facts.$k missing")
    def strOpt(k: String): Option[String] = str(k).toOption
    def num(k: String): Either[String, Double] =
      get(k).flatMap(_.numOpt).filter(d => !d.isNaN && !d.isInfinite).toRight(s"facts.$k missing")
    def count(k: String): Either[String, Long] = num(k).flatMap(d =>
      Either.cond(d.isWhole && d >= 0, d.toLong, s"facts.$k is not a whole non-negative number")
    )
    def countOpt(k: String): Option[Long] = count(k).toOption
    def bool(k: String): Option[Boolean] = get(k).flatMap(_.boolOpt)
    def list(k: String): Either[String, Vector[Facts]] = get(k) match
      case None                => Right(Vector.empty)
      case Some(ujson.Arr(xs)) =>
        val objs = xs.collect { case o: ujson.Obj => Facts(o) }.toVector
        Either.cond(objs.size == xs.size, objs, s"facts.$k must be a list of objects")
      case Some(_) => Left(s"facts.$k must be a list")

  private def fixed(v: Double, digits: Int) = String.format(Locale.ROOT, s"%.${digits}f", v)
  private def whole(v: Double) = String.format(Locale.ROOT, "%.0f", v)
  private def plural(n: Long, one: String, many: String) = s"$n ${if n == 1 then one else many}"
  def years(w: TimeWindow): String =
    if w.startYear == w.endYear then s"${w.startYear}" else s"${w.startYear}-${w.endYear}"
  private def span(first: Long, last: Long) =
    if first == last then s"in $first" else s"$first-$last"
  private def name(f: Facts, k: String, fallback: String) =
    clean(f.strOpt(k).getOrElse(fallback), MaxNameChars)

  private def seq[A](xs: Vector[Either[String, A]]): Either[String, Vector[A]] =
    xs.collectFirst { case Left(e) => Left(e) }.getOrElse(Right(xs.collect { case Right(a) => a }))

  /** "Label: a; b; c." or nothing when the list is empty. */
  private def sentence(label: String, items: Vector[String]): Option[String] =
    Option.when(items.nonEmpty)(s"$label: ${items.mkString("; ")}.")

  /** Items "Name (n unit)" of a list of {name, n} objects. */
  private def counted(xs: Vector[Facts], one: String, many: String) = seq(xs.map { x =>
    for
      n <- x.str("name")
      c <- x.count("n")
    yield s"${clean(n, MaxNameChars)} (${plural(c, one, many)})"
  })

  private def join(parts: Seq[Option[String]]): String = parts.flatten.mkString(" ")

  /** The text of one document. */
  def render(documentType: String, facts: ujson.Obj, window: TimeWindow): Either[String, String] =
    val f = Facts(facts)
    val text = documentType match
      case "researcher-profile"      => profile(f, window)
      case "collaboration-community" => community(f, window)
      case "collaboration-path"      => path(f, window)
      case "topic-summary"           => topic(f, window)
      case "paper-summary"           => paper(f)
      case other                     => Left(s"no rendering-v1 template for documentType $other")
    text.flatMap(t => Either.cond(t.nonEmpty, t, "rendered text is empty"))

  private def profile(f: Facts, w: TimeWindow): Either[String, String] =
    for
      id <- f.str("researcherId")
      pubs <- f.count("publicationCount")
      c <- f.count("distinctCoauthorCount")
      t <- f.count("totalCoauthorOccurrences")
      a <- f.num("averageCoauthorsPerPaper")
      m <- f.count("maximumCoauthorsOnOnePaper")
      wd <- f.num("weightedDegree")
      core <- f.count("coreness")
      comp <- f.count("componentSize")
      coauthors <- f.list("coauthors").flatMap(counted(_, "joint work", "joint works"))
      topics <- f.list("topics").flatMap(counted(_, "work", "works"))
      venues <- f.list("venues").flatMap(counted(_, "work", "works"))
      insts <- f.list("institutions").flatMap(xs => seq(xs.map(_.str("name"))))
      recent <- f
        .list("recentWorks")
        .flatMap(xs =>
          seq(
            xs.map(x =>
              for
                title <- x.str("title")
                year <- x.count("year")
              yield s"\"${clean(title, MaxTitleChars)}\" ($year)"
            )
          )
        )
    yield
      val who = name(f, "displayName", id)
      val range = (f.countOpt("firstYear"), f.countOpt("lastYear")) match
        case (Some(a), Some(b)) => s", first in $a and latest in $b"
        case _                  => ""
      val largest = if f.bool("inLargestComponent").contains(true) then ", the largest" else ""
      val communityLine =
        for
          size <- f.countOpt("communitySize")
          cross <- f.countOpt("crossCommunityDegree")
          linked <- f.countOpt("communitiesConnected")
          pc <- f.num("participationCoefficient").toOption
        yield s"Community: a collaboration community of ${plural(size, "researcher", "researchers")}; " +
          s"${plural(cross, "coauthor", "coauthors")} in other communities; " +
          s"${plural(linked, "community", "communities")} connected; participation coefficient ${fixed(pc, 3)}."
      join(
        Seq(
          Some(s"Researcher profile: $who, ${years(w)} (window ${w.name})."),
          Some(
            s"$who has ${plural(pubs, "publication", "publications")} in the DBLP subset in this window$range."
          ),
          Some(
            s"Collaboration: ${plural(c, "distinct coauthor", "distinct coauthors")}; " +
              s"${plural(t, "coauthor occurrence", "coauthor occurrences")} in total; " +
              s"${fixed(a, 2)} coauthors per paper on average; at most $m on one paper; weighted degree ${whole(wd)}."
          ),
          Some(
            s"Network position: coreness $core; a connected component of ${plural(comp, "researcher", "researchers")}$largest."
          ),
          communityLine,
          sentence("Frequent coauthors", coauthors),
          sentence("Frequent topics", topics),
          sentence("Frequent venues", venues),
          sentence("Institutions on record (any year)", insts.map(clean(_, MaxNameChars))),
          sentence("Recent works", recent)
        )
      )

  private def community(f: Facts, w: TimeWindow): Either[String, String] =
    for
      _ <- f.str("communityId")
      size <- f.count("communitySize")
      internal <- f.count("internalEdges")
      cross <- f.count("crossCommunityEdges")
      linked <- f.count("linkedCommunities")
      works <- f.count("works")
      members <- f.list("topMembers").flatMap(counted(_, "coauthor", "coauthors"))
      topics <- f.list("topics").flatMap(counted(_, "work", "works"))
      venues <- f.list("venues").flatMap(counted(_, "work", "works"))
    yield
      val density = f.num("density").toOption.fold("")(d => s" (density ${fixed(d, 3)})")
      join(
        Seq(
          Some(
            s"Collaboration community of ${plural(size, "researcher", "researchers")}, ${years(w)} (window ${w.name})."
          ),
          Some(
            s"Inside it: ${plural(internal, "coauthor link", "coauthor links")}$density. " +
              s"Outside it: ${plural(cross, "link", "links")} to ${plural(linked, "other community", "other communities")}."
          ),
          Some(
            s"Its members have ${plural(works, "publication", "publications")} in the window."
          ),
          sentence("Most connected members", members),
          sentence("Frequent topics", topics),
          sentence("Frequent venues", venues)
        )
      )

  private def path(f: Facts, w: TimeWindow): Either[String, String] =
    for
      _ <- f.str("pairId")
      status <- f.str("status")
      _ <- f.str("sourceResearcherId")
      target <- f.str("targetResearcherId")
      src <- f.str("sourceName").map(clean(_, MaxNameChars))
      tgt <- f.str("targetName").map(clean(_, MaxNameChars))
      lead = s"Collaboration path, ${years(w)} (window ${w.name}), between $src and $tgt:"
      text <- status match
        case "FOUND" =>
          for
            length <- f.count("pathLength")
            hops <- f.list("hops")
            route <- seq(hops.map { h =>
              for
                n <- h.count("n")
                first <- h.count("first")
                last <- h.count("last")
              yield
                val who = name(h, "name", h.strOpt("id").getOrElse("unknown researcher"))
                val example =
                  h.strOpt("title").fold("")(t => s", for example \"${clean(t, MaxTitleChars)}\"")
                s" -> $who (${plural(n, "joint work", "joint works")}, ${span(first, last)}$example)"
            })
            _ <- Either.cond(hops.size == length, (), s"path has ${hops.size} hops, length $length")
            _ <- Either.cond(
              length == 0 || hops.last.strOpt("id").contains(target),
              (),
              "the last hop does not end at the target"
            )
          yield
            if length == 0 then s"$lead they are the same researcher."
            else
              s"$lead a shortest coauthor path of ${plural(length, "hop", "hops")}. Route: $src${route.mkString}."
        case "BEYOND_DEPTH" =>
          f.count("maxDepth")
            .map(d =>
              s"$lead they are in the same connected component, but no coauthor path of at most ${plural(d, "hop", "hops")} joins them."
            )
        case "UNREACHABLE" =>
          Right(
            s"$lead they are in different connected components, so no coauthor path joins them in this window."
          )
        case "UNKNOWN_RESEARCHER" =>
          Right(
            s"$lead at least one of them has no DBLP publication in this window, so no path was searched."
          )
        case other => Left(s"unknown path status $other")
    yield text

  private def topic(f: Facts, w: TimeWindow): Either[String, String] =
    for
      _ <- f.str("topicId")
      n <- f.str("name")
      works <- f.count("works")
      researchers <- f.count("researchers")
      top <- f.list("topResearchers").flatMap(counted(_, "work", "works"))
      venues <- f.list("venues").flatMap(counted(_, "work", "works"))
    yield
      val where = Seq(
        f.strOpt("field").map(x => s"field ${clean(x, MaxNameChars)}"),
        f.strOpt("subfield").map(x => s"subfield ${clean(x, MaxNameChars)}")
      ).flatten
      val area = if where.isEmpty then "" else where.mkString(" (", ", ", ")")
      join(
        Seq(
          Some(s"Topic: ${clean(n, MaxNameChars)}$area, ${years(w)} (window ${w.name})."),
          Some(
            s"${plural(works, "work", "works")} by ${plural(researchers, "researcher", "researchers")} in the DBLP subset have this topic."
          ),
          sentence("Most active researchers", top),
          sentence("Frequent venues", venues)
        )
      )

  private def paper(f: Facts): Either[String, String] =
    for
      _ <- f.str("workId")
      title <- f.str("title")
      year <- f.count("year")
      authorCount <- f.count("authorCount")
      authors <- f
        .list("authors")
        .flatMap(xs => seq(xs.map(a => a.str("id").map(id => name(a, "name", id)))))
      topics <- f
        .list("topics")
        .flatMap(xs => seq(xs.map(_.str("name").map(clean(_, MaxNameChars)))))
    yield
      val kind = f.strOpt("workType").fold("")(t => s", ${clean(t, MaxNameChars)}")
      val venue = f.strOpt("venueName").fold("")(v => s", published in ${clean(v, MaxNameChars)}")
      val more = authorCount - authors.size
      val authorLine = Option.when(authors.nonEmpty)(
        s"Authors (${authorCount}): ${authors.mkString("; ")}" +
          (if more > 0 then s"; and ${plural(more, "more author", "more authors")}." else ".")
      )
      join(
        Seq(
          Some(s"Paper: \"${clean(title, MaxTitleChars)}\" ($year)$kind$venue."),
          authorLine,
          sentence("Topics", topics)
        )
      )
