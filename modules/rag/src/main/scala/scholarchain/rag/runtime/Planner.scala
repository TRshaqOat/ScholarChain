package scholarchain.rag.runtime

import scholarchain.core.config.{ScholarChainConfig, TimeWindow}
import scholarchain.core.{
  CollaborationMetric,
  Provenance,
  QueryOperation,
  SchemaVersions,
  SourceType
}
import scholarchain.rag.SemanticContext
import upickle.default.read

import java.text.Normalizer

/** Question text rules (RUNTIME.md section 3): NFC, no control characters other than tab and
  * newline, 1 to runtime.question.max-chars characters. Name matching collapses whitespace runs.
  */
object Question:
  def normalize(q: String, maxChars: Int): Either[String, String] =
    val n = Normalizer.normalize(q, Normalizer.Form.NFC).trim
    if n.isEmpty then Left("the question is empty")
    else if n.length > maxChars then Left(s"the question is longer than $maxChars characters")
    else if n.exists(c => Character.isISOControl(c) && c != '\t' && c != '\n') then
      Left("the question contains control characters")
    else Right(n)

  def collapse(s: String): String = s.replaceAll("\\s+", " ").trim

  /** Code-point offset of term in the collapsed question (case-sensitive), if it occurs. */
  def offset(question: String, term: String): Option[Int] =
    val q = collapse(question)
    val t = collapse(term)
    Option.when(t.nonEmpty)(q.indexOf(t)).filter(_ >= 0).map(i => q.codePointCount(0, i))

/** The operation allowlist (RUNTIME.md section 3): scopes and metric rules per operation. */
object Allowlist:
  import OperationScope.*
  import QueryOperation.*
  val scopes: Map[QueryOperation, Set[OperationScope]] = Map(
    RESEARCHER_RANKING -> Set(CORPUS),
    MAXIMUM_TEAM_SIZE -> Set(RESEARCHER, CORPUS),
    DIRECT_COAUTHORS -> Set(RESEARCHER, PAIR),
    SHORTEST_PATH -> Set(PAIR),
    COMPONENT_LOOKUP -> Set(RESEARCHER, PAIR, CORPUS),
    COMMUNITY_COMPARISON -> Set(RESEARCHER, PAIR, CORPUS),
    BRIDGE_RANKING -> Set(CORPUS, RESEARCHER),
    TEMPORAL_COMPARISON -> Set(RESEARCHER, CORPUS),
    TOPIC_FILTER -> Set(RESEARCHER, CORPUS),
    VENUE_FILTER -> Set(RESEARCHER),
    INSTITUTION_FILTER -> Set(RESEARCHER),
    SEMANTIC_EXPLANATION -> Set(RESEARCHER, PAIR, CORPUS)
  )

  /** None: the operation takes no metric. */
  def metricAllowed(op: QueryOperation, m: CollaborationMetric): Boolean = op match
    case RESEARCHER_RANKING  => MetricAttributes.ranked.contains(m)
    case BRIDGE_RANKING      => MetricAttributes.bridge.contains(m)
    case TEMPORAL_COMPARISON => true
    case _                   => false

  /** Fixed text of what an operation allows, from the tables above: the planner-v2 prompt's rule 2
    * and the rule-2 and rule-4 failure messages (ruling 30). Keep metricText in step with
    * metricAllowed.
    */
  def scopeText(op: QueryOperation): String =
    OperationScope.values.filter(scopes(op)).mkString(" or ")
  def metricText(op: QueryOperation): String = op match
    case RESEARCHER_RANKING  => "null or one of " + MetricAttributes.ranked.mkString(", ")
    case BRIDGE_RANKING      => "null or one of " + MetricAttributes.bridge.mkString(", ")
    case TEMPORAL_COMPARISON => "null or any metric"
    case _                   => "null"
  def ruleText(op: QueryOperation): String =
    s"$op: scope ${scopeText(op)}; metric ${metricText(op)}"

  def arity(s: OperationScope): Int = s match
    case RESEARCHER => 1
    case PAIR       => 2
    case CORPUS     => 0

/** Window selection (RUNTIME.md section 4, ruling 6). */
object Windows:
  def corpus(ws: Seq[TimeWindow]): (Int, Int) = (ws.map(_.startYear).min, ws.map(_.endYear).max)
  def byName(ws: Seq[TimeWindow], name: String): Option[TimeWindow] =
    val n = if name == "all" then "all-years" else name
    ws.find(_.name == n)

  /** The non-overlapping ordered pairs (earlier first): the stored CHANGE# comparisons. */
  def comparisons(ws: Seq[TimeWindow]): Vector[WindowComparison] =
    for
      a <- ws.toVector
      b <- ws.toVector
      if a.endYear < b.startYear
    yield WindowComparison(a.name, b.name)

  /** The window covering the corpus years (all-years). */
  def widest(ws: Seq[TimeWindow]): TimeWindow =
    val (cs, ce) = corpus(ws)
    ws.find(w => w.startYear == cs && w.endYear == ce)
      .getOrElse(ws.maxBy(w => w.endYear - w.startYear))

  def corpusWindowName(cfg: ScholarChainConfig): String = widest(cfg.graph.windows).name

  /** Planner rule 7 (RUNTIME.md section 3): what is wrong with a requested window, in fixed text;
    * empty when it is valid.
    */
  def requestProblems(w: WindowRequest, ws: Seq[TimeWindow]): Vector[String] =
    val years = w.yearFrom.isDefined || w.yearTo.isDefined
    w.kind match
      case WindowKind.DEFAULT =>
        Vector(Option.when(w.name.isDefined)("a name"), Option.when(years)("a year")).flatten
      case WindowKind.NAMED =>
        Vector(
          Option.when(!w.name.exists(byName(ws, _).isDefined))("no stored window name"),
          Option.when(years)("a year")
        ).flatten
      case WindowKind.RANGE =>
        Vector(
          Option.when(w.name.isDefined)("a name"),
          Option.when(!years)("no year"),
          Option.when(w.yearFrom.zip(w.yearTo).exists(_ > _))("yearFrom after yearTo")
        ).flatten

  /** The window forms rule 7 allows, with the stored window names (ruling 30). */
  def allowedText(ws: Seq[TimeWindow]): String =
    s"DEFAULT (name, yearFrom, yearTo null), NAMED (name one of ${ws.map(_.name).mkString(", ")}; " +
      "yearFrom, yearTo null), or RANGE (name null; yearFrom and yearTo years, at most one null; " +
      "yearFrom <= yearTo)"

  /** The stored window of a request; Left(detail) when the requested years do not meet the corpus
    * years (NO_DATA_IN_WINDOW).
    */
  def select(req: WindowRequest, ws: Seq[TimeWindow]): Either[String, WindowSelection] =
    val (cs, ce) = corpus(ws)
    val widest = Windows.widest(ws)
    def exact(w: TimeWindow) =
      WindowSelection(req, w.name, w.startYear, w.endYear, exact = true, None)
    req.kind match
      case WindowKind.DEFAULT => Right(exact(widest))
      case WindowKind.NAMED   =>
        req.name
          .flatMap(byName(ws, _))
          .map(exact)
          .toRight(s"no stored window ${req.name.getOrElse("")}")
      case WindowKind.RANGE =>
        val (f, t) = (req.yearFrom.getOrElse(cs), req.yearTo.getOrElse(ce))
        val (cf, ct) = (math.max(f, cs), math.min(t, ce))
        if cf > ct then Left(s"Requested $f-$t has no year in the corpus years $cs-$ce.")
        else
          val clipped = (cf, ct) != (f, t)
          val w = ws
            .filter(w => w.startYear <= cf && w.endYear >= ct)
            .minBy(w =>
              w.endYear - w.startYear
            ) // minBy keeps the first on ties: configuration order
          val isExact = !clipped && w.startYear == cf && w.endYear == ct
          val clipNote =
            Option.when(clipped)(s"Requested $f-$t was clipped to the corpus years $cf-$ct.")
          val note = Option.when(!isExact)(
            (clipNote.toSeq :+ s"Requested $cf-$ct; the stored window ${w.name} (${w.startYear}-${w.endYear}) " +
              s"is the smallest that contains it, so values cover ${w.startYear}-${w.endYear}.")
              .mkString(" ")
          )
          Right(WindowSelection(req, w.name, w.startYear, w.endYear, isExact, note))

/** Rule-based planner (RULES, FALLBACK_RULES): deterministic, used in fake mode, in tests, and as
  * the fallback (RUNTIME.md section 3). It only selects enum values and copies text spans of the
  * question, exactly like the model's output.
  */
object RulePlanner:
  import CollaborationMetric.*
  import QueryOperation.*

  private val QuestionWords = Set(
    "Which",
    "What",
    "How",
    "Have",
    "Has",
    "Had",
    "Is",
    "Are",
    "Was",
    "Were",
    "Did",
    "Do",
    "Does",
    "Where",
    "When",
    "Who",
    "Whom",
    "Whose",
    "Why",
    "Can",
    "Could",
    "Show",
    "List",
    "Give",
    "Tell",
    "Find",
    "Compare",
    "Between",
    "In",
    "For",
    "The",
    "A",
    "An",
    "And",
    "Of",
    "From",
    "Since"
  )
  private val NameToken = "\\p{Lu}[\\p{L}\\p{M}'’.-]*".r
  private val Homonym = "0\\d{3}".r
  private val Range = "\\b((?:19|20)\\d{2})\\s*[-–]\\s*((?:19|20)\\d{2})\\b".r
  private val Year = "\\b((?:19|20)\\d{2})\\b".r
  private val TopN = "(?i)\\btop\\s+(\\d{1,4})\\b".r
  private val TopicTerm =
    "(?i)\\b(?:papers|works|publications|research)\\s+(?:on|about)\\s+(.+?)(?:\\s+topics?)?\\s*[?.!]?$".r

  /** Maximal runs of capitalized tokens (initials, accents, hyphens, inner apostrophes, a trailing
    * DBLP homonym number), leading question words dropped, at least two tokens.
    */
  def names(question: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var run = Vector.empty[String]
    def flush(): Unit =
      val kept = run.dropWhile(QuestionWords)
      if kept.size >= 2 then out += kept.mkString(" ")
      run = Vector.empty
    question.split("\\s+").foreach { raw =>
      val possessive = raw.matches(".*['’]s[,?!;:.]*")
      val trimmed = raw.replaceAll("['’]s[,?!;:.]*$", "").replaceAll("[,?!;:\"()]+$", "")
      val ends = possessive || trimmed.length < raw.length
      val token =
        if trimmed.endsWith(".") && !trimmed.matches("\\p{Lu}\\.") then trimmed.dropRight(1)
        else trimmed
      if NameToken.matches(token) || (run.nonEmpty && Homonym.matches(token)) then
        run :+= token
        if ends || trimmed.length != token.length then flush()
      else flush()
    }
    flush()
    out.result().distinct

  private def metricsOf(q: String): Vector[CollaborationMetric] =
    def has(ws: String*) = ws.exists(q.contains)
    Vector(
      Option.when(
        has("distinct coauthors", "how many coauthors", "number of coauthors", "breadth")
      )(
        DISTINCT_COAUTHOR_COUNT
      ),
      Option.when(
        has("how many papers", "how many publications", "number of papers", "publication count")
      )(
        PUBLICATION_COUNT
      ),
      Option.when(has("coauthor occurrences"))(TOTAL_COAUTHOR_OCCURRENCES),
      Option.when(has("average team", "average coauthors", "average number of coauthors"))(
        AVERAGE_COAUTHORS_PER_PAPER
      ),
      Option.when(has("weighted degree", "intensity"))(WEIGHTED_DEGREE),
      Option.when(has("component size"))(COMPONENT_SIZE),
      Option.when(has("coreness", "k-core"))(CORENESS)
    ).flatten

  private def bridgeOf(q: String): Vector[CollaborationMetric] =
    def has(ws: String*) = ws.exists(q.contains)
    val all = has("bridge", "bridging", "broker")
    Vector(
      Option.when(all || has("cross-community", "cross community"))(CROSS_COMMUNITY_DEGREE),
      Option.when(
        all || has("communities connected", "communities does", "communities do") ||
          "connects? (the )?(most )?(distinct |different )?communities".r.findFirstIn(q).isDefined
      )(COMMUNITIES_CONNECTED),
      Option.when(all || has("participation coefficient"))(PARTICIPATION_COEFFICIENT)
    ).flatten

  def plan(question: String, ws: Seq[TimeWindow], maxNames: Int): PlannerOutput =
    val q = question.toLowerCase(java.util.Locale.ROOT)
    def has(xs: String*) = xs.exists(q.contains)
    val ns = names(question).take(maxNames)
    val ranges =
      Range.findAllMatchIn(question).map(m => (m.group(1).toInt, m.group(2).toInt)).toVector
    val stored = ranges.flatMap((f, t) => ws.find(w => w.startYear == f && w.endYear == t))
    val comparison = Option
      .when(
        stored.distinct.size == 2 && has("between", "change", "compare", "from", "grow", "differ")
      )(
        stored.distinct.sortBy(_.startYear)
      )
      .collect { case Vector(a, b) if a.endYear < b.startYear => WindowComparison(a.name, b.name) }
    val singleYears =
      Year.findAllMatchIn(Range.replaceAllIn(question, " ")).map(_.group(1).toInt).toVector
    val window =
      if comparison.isDefined then WindowRequest(WindowKind.DEFAULT, None, None, None)
      else if ranges.nonEmpty then
        WindowRequest(WindowKind.RANGE, None, Some(ranges.head._1), Some(ranges.head._2))
      else if has("all years", "all-years") then
        WindowRequest(WindowKind.NAMED, Some("all-years"), None, None)
      else
        singleYears.headOption match
          case Some(y) if has(s"since $y", s"after $y", s"from $y") =>
            WindowRequest(WindowKind.RANGE, None, Some(y), None)
          case Some(y) if has(s"before $y", s"until $y") =>
            WindowRequest(WindowKind.RANGE, None, None, Some(y))
          case Some(y) => WindowRequest(WindowKind.RANGE, None, Some(y), Some(y))
          case None    => WindowRequest(WindowKind.DEFAULT, None, None, None)
    val topics =
      TopicTerm.findFirstMatchIn(question).map(_.group(1).trim).filter(_.nonEmpty).toVector
    val topN = TopN.findFirstMatchIn(question).map(_.group(1).toInt)
    val metrics = metricsOf(q)
    val bridge = bridgeOf(q)
    val one = ns.indices.toVector
    def each(op: QueryOperation, metric: Option[CollaborationMetric] = None) =
      if ns.isEmpty then
        Vector(PlannedOperation(op, OperationScope.CORPUS, Vector.empty, metric, topN))
      else one.map(i => PlannedOperation(op, OperationScope.RESEARCHER, Vector(i), metric, None))
    def pair(op: QueryOperation) =
      PlannedOperation(op, OperationScope.PAIR, Vector(0, 1), None, None)
    val path = ns.size >= 2 && has("shortest", "path")
    val ops = Vector.newBuilder[PlannedOperation]
    if comparison.isDefined || (ns.nonEmpty && has("change", "changed")) then
      ops ++= each(TEMPORAL_COMPARISON, (metrics ++ bridge).headOption)
    else if ns.isEmpty && has("larger share", "share of researchers") && ranges.size >= 2 then
      ops ++= each(TEMPORAL_COMPARISON)
    if path then ops += pair(SHORTEST_PATH)
    else if ns.size >= 2 && has("coauthor", "joint", "collaborat", "together", "papers") then
      ops += pair(DIRECT_COAUTHORS)
    val team = has("largest team", "team size", "biggest team", "largest number of authors") ||
      "authors on (a|one) (single )?paper".r.findFirstIn(q).isDefined
    if team then ops ++= each(MAXIMUM_TEAM_SIZE)
    if has("component") || (ns.isEmpty && has("no coauthors", "isolated", "singleton")) then
      ops ++= each(COMPONENT_LOOKUP)
    if bridge.nonEmpty then
      if ns.isEmpty then bridge.foreach(m => ops ++= each(BRIDGE_RANKING, Some(m)))
      else ops ++= each(BRIDGE_RANKING)
    val community = comparison.isEmpty && bridge.isEmpty && has("communit")
    if community then ops ++= each(COMMUNITY_COMPARISON)
    if has("topic") && !community && (ns.nonEmpty || topics.nonEmpty) then
      ops ++= each(TOPIC_FILTER)
    if ns.nonEmpty && has("venue") then ops ++= each(VENUE_FILTER)
    if ns.nonEmpty && has("institution", "affiliat", "university") then
      ops ++= each(INSTITUTION_FILTER)
    val ranking = ns.isEmpty && topics.isEmpty && !team && bridge.isEmpty &&
      has("most", "highest", "top ", "rank", "largest", "leading")
    if ranking then
      (metrics
        .filter(MetricAttributes.ranked.contains)
        .headOption
        .orElse(
          Option.when(has("papers", "publications"))(PUBLICATION_COUNT)
        ))
        .foreach(m =>
          ops += PlannedOperation(
            RESEARCHER_RANKING,
            OperationScope.CORPUS,
            Vector.empty,
            Some(m),
            topN
          )
        )
    if has("explain", "describe", "summar", " why ") then
      ops += (ns.size match
        case 0 =>
          PlannedOperation(SEMANTIC_EXPLANATION, OperationScope.CORPUS, Vector.empty, None, None)
        case 1 =>
          PlannedOperation(SEMANTIC_EXPLANATION, OperationScope.RESEARCHER, Vector(0), None, None)
        case _ => pair(SEMANTIC_EXPLANATION))
    val bridgeMetrics = if ns.nonEmpty then bridge else Vector.empty
    PlannerOutput(
      RuntimeSchemas.PlannerOutput,
      ops.result().distinct,
      ns,
      if ns.nonEmpty then (metrics ++ bridgeMetrics).distinct else Vector.empty,
      window,
      comparison,
      topics,
      Vector.empty,
      Vector.empty
    )

/** Stage 1 (QUERY_PLANNING): validation of planner output, request overrides, and the QueryPlan. */
object Planner:
  /** Every rule failure of a planner output (RUNTIME.md section 3, rules 1 to 8); empty means
    * valid. A failure reads "<rule> <field>: <what is wrong>; allowed: <what is allowed>" (ruling
    * 30). Both parts are fixed text, enum values, and configuration values, never text that is not
    * already in the question. The part before the first ':' is the rejected-item name.
    */
  def problems(o: PlannerOutput, question: String, cfg: ScholarChainConfig): Vector[String] =
    val p = cfg.runtime.planner
    val ws = cfg.graph.windows
    val chars = s"allowed: 1 to ${p.maxTermChars} characters"
    def terms(field: String, xs: Vector[String], max: Int) =
      Vector(
        Option.when(xs.size > max)(s"rule-6 $field: more than $max; allowed: at most $max"),
        Option.when(xs.exists(t => t.trim.isEmpty || t.length > p.maxTermChars))(
          s"rule-6 $field: blank or longer than ${p.maxTermChars} characters; $chars"
        ),
        Option.when(xs.exists(t => Question.offset(question, t).isEmpty))(
          s"rule-6 $field: a term does not occur in the question; " +
            "allowed: terms copied exactly from the question"
        )
      ).flatten
    val ops = o.operations
    val window = Windows.requestProblems(o.window, ws)
    val pairs = Windows.comparisons(ws).map(c => s"${c.fromWindow} -> ${c.toWindow}")
    Vector(
      Option.when(o.schemaVersion != RuntimeSchemas.PlannerOutput)(
        s"rule-1 schemaVersion: wrong value; allowed: ${RuntimeSchemas.PlannerOutput}"
      ),
      Option.when(ops.size > p.maxOperations)(
        s"rule-2 operations: more than ${p.maxOperations}; allowed: at most ${p.maxOperations}"
      ),
      Option.when(ops.distinctBy(x => (x.operation, x.scope, x.researchers)).size != ops.size)(
        "rule-2 operations: duplicate; allowed: each operation, scope, and researchers once"
      )
    ).flatten ++ ops.flatMap { x =>
      val n = x.operation.toString
      Vector(
        Option.when(!Allowlist.scopes(x.operation).contains(x.scope))(
          s"rule-2 $n: scope ${x.scope} not allowed; allowed: scope ${Allowlist.scopeText(x.operation)}"
        ),
        Option.when(x.researchers.size != Allowlist.arity(x.scope))(
          s"rule-3 $n: researchers do not match ${x.scope}; " +
            "allowed: one index for RESEARCHER, two different indexes for PAIR, none for CORPUS"
        ),
        Option.when(x.researchers.exists(i => i < 0 || i >= o.researcherNames.size))(
          s"rule-3 $n: researcher index out of range; " +
            s"allowed: 0 to the number of researcherNames minus 1 (${o.researcherNames.size} names)"
        ),
        Option.when(x.researchers.distinct.size != x.researchers.size)(
          s"rule-3 $n: repeated researcher; allowed: two different indexes"
        ),
        Option.when(x.metric.exists(m => !Allowlist.metricAllowed(x.operation, m)))(
          s"rule-4 $n: metric not allowed; allowed: metric ${Allowlist.metricText(x.operation)}"
        ),
        Option.when(x.topN.exists(t => t < 1 || t > cfg.runtime.rankings.maxTopN))(
          s"rule-4 $n: topN out of range; allowed: null or 1 to ${cfg.runtime.rankings.maxTopN}"
        )
      ).flatten
    } ++ Vector(
      Option.when(o.researcherNames.size > p.maxResearcherNames)(
        s"rule-5 researcherNames: more than ${p.maxResearcherNames}; " +
          s"allowed: at most ${p.maxResearcherNames}"
      ),
      Option.when(o.researcherNames.distinct.size != o.researcherNames.size)(
        "rule-5 researcherNames: duplicate; allowed: each name once"
      ),
      Option.when(o.researcherNames.exists(t => t.trim.isEmpty || t.length > p.maxTermChars))(
        s"rule-5 researcherNames: blank or longer than ${p.maxTermChars} characters; $chars"
      ),
      Option.when(o.researcherNames.exists(t => Question.offset(question, t).isEmpty))(
        "rule-5 researcherNames: a name does not occur in the question; " +
          "allowed: names copied exactly from the question"
      )
    ).flatten ++ terms("topics", o.topics, p.maxFilterTerms) ++ terms(
      "venues",
      o.venues,
      p.maxFilterTerms
    ) ++
      terms("institutions", o.institutions, p.maxFilterTerms) ++ Option.when(window.nonEmpty)(
        s"rule-7 window: ${o.window.kind} with ${window.mkString(", ")}; " +
          s"allowed: ${Windows.allowedText(ws)}"
      ) ++ o.comparison.toVector.flatMap(c =>
        Option.when(!Windows.comparisons(ws).contains(c))(
          "rule-8 comparison: not a stored window pair; " +
            s"allowed: null or fromWindow -> toWindow one of ${pairs.mkString(", ")}"
        )
      ) ++ Option.when(ops.isEmpty && (o.metrics.isEmpty || o.researcherNames.isEmpty))(
        "rule-2 operations: none, and no metric for a named researcher; " +
          "allowed: at least one operation, or a metric and a researcher name"
      )

  /** Model text -> a valid PlannerOutput, or the failed rule names (schema first). */
  def parseModel(
      text: String,
      schema: ujson.Value,
      question: String,
      cfg: ScholarChainConfig
  ): Either[Vector[String], PlannerOutput] =
    JsonSchema.parseAndValidate(schema, text).flatMap { v =>
      scala.util
        .Try(read[PlannerOutput](v))
        .toEither
        .left
        .map(_ => Vector("schema: the output does not decode as planner-output-v1"))
        .flatMap(o =>
          problems(o, question, cfg) match
            case Vector() => Right(o)
            case es       => Left(es)
        )
    }

  /** The planner prompt values (the question is escaped: it is data inside <question>). */
  def promptValues(
      question: String,
      prompt: PromptTemplate,
      cfg: ScholarChainConfig
  ): Map[String, String] =
    Map(
      "question" -> SemanticContext.escape(question),
      "schema" -> ujson.write(prompt.schema, indent = 2),
      "windows" -> cfg.graph.windows
        .map(w => s"${w.name} (${w.startYear}-${w.endYear})")
        .mkString(", "),
      // planner-v2 (ruling 30): the exact window names, and each operation's scopes and metrics from
      // the validator's own tables.
      "window_names" -> cfg.graph.windows.map(w => s"\"${w.name}\"").mkString(", "),
      "operation_rules" -> QueryOperation.values
        .map(op => s"   ${Allowlist.ruleText(op)}.")
        .mkString("\n"),
      "comparisons" -> Windows
        .comparisons(cfg.graph.windows)
        .map(c => s"${c.fromWindow} -> ${c.toWindow}")
        .mkString(", "),
      "max_operations" -> cfg.runtime.planner.maxOperations.toString,
      "max_names" -> cfg.runtime.planner.maxResearcherNames.toString,
      "max_terms" -> cfg.runtime.planner.maxFilterTerms.toString
    )

  /** QueryPlan from a valid output: request overrides (ruling 4), the window selection, limits, and
    * the semantic indexes chosen by code. Left(Abstention) when the window has no corpus year.
    */
  def build(
      o: PlannerOutput,
      request: QueryRequest,
      question: String,
      source: PlannerSource,
      cfg: ScholarChainConfig,
      queryId: String,
      snapshot: String,
      runId: String,
      promptVersion: Option[String],
      modelId: Option[String],
      rejected: Vector[String]
  ): Either[(Abstention, QueryPlan), QueryPlan] =
    val c = request.constraints
    val a = request.analysis
    val overridden = Vector.newBuilder[String]
    def maxTerms(xs: Vector[String]) =
      xs.filter(t => t.trim.nonEmpty && t.length <= cfg.runtime.planner.maxTermChars)
        .take(cfg.runtime.planner.maxFilterTerms)
    val window =
      if c.exists(x => x.yearFrom.isDefined || x.yearTo.isDefined) then
        overridden += "constraints.years"
        WindowRequest(WindowKind.RANGE, None, c.flatMap(_.yearFrom), c.flatMap(_.yearTo))
      else o.window
    def listOverride(name: String, from: Option[Vector[String]], model: Vector[String]) =
      from.fold(model) { xs =>
        overridden += s"constraints.$name"
        maxTerms(xs)
      }
    val topics = listOverride("topics", c.flatMap(_.topics), o.topics)
    val venues = listOverride("venues", c.flatMap(_.venues), o.venues)
    val institutions = listOverride("institutions", c.flatMap(_.institutions), o.institutions)
    var ops = o.operations
    a.flatMap(_.rankingMetric).filter(MetricAttributes.ranked.contains).foreach { m =>
      overridden += "analysis.rankingMetric"
      val ranking = ops.filter(_.operation == QueryOperation.RESEARCHER_RANKING)
      ops =
        if ranking.isEmpty then
          ops :+ PlannedOperation(
            QueryOperation.RESEARCHER_RANKING,
            OperationScope.CORPUS,
            Vector.empty,
            Some(m),
            None
          )
        else
          ops.map(x =>
            if x.operation == QueryOperation.RESEARCHER_RANKING then x.copy(metric = Some(m)) else x
          )
    }
    val names = o.researcherNames
    def toggle(
        flag: Option[Boolean],
        name: String,
        op: QueryOperation,
        scopes: Vector[PlannedOperation]
    ): Unit =
      flag.foreach { on =>
        overridden += s"analysis.$name"
        if !on then ops = ops.filterNot(_.operation == op)
        else if !ops.exists(_.operation == op) then ops ++= scopes
      }
    def perName(op: QueryOperation) =
      if names.isEmpty then
        Vector(PlannedOperation(op, OperationScope.CORPUS, Vector.empty, None, None))
          .filter(x => Allowlist.scopes(op).contains(x.scope))
      else
        names.indices.toVector.map(i =>
          PlannedOperation(op, OperationScope.RESEARCHER, Vector(i), None, None)
        )
    toggle(
      a.flatMap(_.includeTeamSize),
      "includeTeamSize",
      QueryOperation.MAXIMUM_TEAM_SIZE,
      perName(QueryOperation.MAXIMUM_TEAM_SIZE)
    )
    toggle(
      a.flatMap(_.includeBridgeMetrics),
      "includeBridgeMetrics",
      QueryOperation.BRIDGE_RANKING,
      perName(QueryOperation.BRIDGE_RANKING)
    )
    toggle(
      a.flatMap(_.includeCommunities),
      "includeCommunities",
      QueryOperation.COMMUNITY_COMPARISON,
      perName(QueryOperation.COMMUNITY_COMPARISON)
    )
    toggle(
      a.flatMap(_.includePaths),
      "includePaths",
      QueryOperation.SHORTEST_PATH,
      Option
        .when(names.size >= 2)(
          PlannedOperation(
            QueryOperation.SHORTEST_PATH,
            OperationScope.PAIR,
            Vector(0, 1),
            None,
            None
          )
        )
        .toVector
    )
    ops = ops
      .map { x =>
        if x.operation == QueryOperation.RESEARCHER_RANKING && x.metric.isEmpty then
          x.copy(metric = Some(CollaborationMetric.DISTINCT_COAUTHOR_COUNT))
        else x
      }
      .distinct
      .take(cfg.runtime.planner.maxOperations)
    val r = request.retrieval
    val graphLimit = math.min(
      r.flatMap(_.graphLimit).filter(_ >= 1).getOrElse(cfg.retrieval.graphLimit),
      cfg.retrieval.graphLimit
    )
    val vectorLimit = math.min(
      r.flatMap(_.vectorLimit).filter(_ >= 1).getOrElse(cfg.retrieval.vectorLimit),
      cfg.retrieval.vectorLimit
    )
    val depth = math.min(
      r.flatMap(_.maxPathDepth).filter(_ >= 1).getOrElse(cfg.retrieval.maxPathDepth),
      cfg.retrieval.maxPathDepth
    )
    val limits =
      PlanLimits(graphLimit, vectorLimit, depth, math.min(vectorLimit, cfg.runtime.semantic.topK))
    val selected = Windows.select(window, cfg.graph.windows)
    val selection = selected.getOrElse {
      val (s, e) = Windows.corpus(cfg.graph.windows)
      WindowSelection(window, "", s, e, exact = false, selected.left.toOption)
    }
    val mentions = names.map(n => NameMention(n, Question.offset(question, n).getOrElse(0)))
    val reversed = (window.yearFrom, window.yearTo) match
      case (Some(f), Some(t)) if f > t => Some((f, t))
      case _                           => None
    val plan = QueryPlan(
      RuntimeSchemas.QueryPlan,
      queryId,
      ops,
      mentions,
      o.metrics.distinct,
      selection,
      o.comparison.orElse(
        Option
          .when(ops.exists(_.operation == QueryOperation.TEMPORAL_COMPARISON))(
            Windows.comparisons(cfg.graph.windows).headOption
          )
          .flatten
      ),
      topics,
      venues,
      institutions,
      semanticIndexes(ops, names.nonEmpty, topics.nonEmpty, cfg),
      limits,
      source,
      overridden.result().distinct,
      rejected,
      promptVersion,
      modelId,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.RUNTIME,
        queryId,
        snapshot,
        runId,
        None,
        modelId
      )
    )
    (reversed, selected) match
      case (Some((f, t)), _) =>
        // Review 1 m8: reversed request years are an invalid plan, not an empty window.
        Left(
          (
            Abstention(
              AbstentionReason.INVALID_PLAN,
              s"The requested years are reversed: yearFrom $f is after yearTo $t."
            ),
            plan
          )
        )
      case (_, Left(why)) => Left((Abstention(AbstentionReason.NO_DATA_IN_WINDOW, why), plan))
      case (_, Right(_))  => Right(plan)

  /** Indexes chosen by code, in priority order, at most runtime.semantic.max-indexes (section 9).
    */
  def semanticIndexes(
      ops: Vector[PlannedOperation],
      hasNames: Boolean,
      hasTopics: Boolean,
      cfg: ScholarChainConfig
  ): Vector[String] =
    import QueryOperation.*
    val ix = cfg.s3Vectors.indexes
    def has(o: QueryOperation*) = ops.exists(x => o.contains(x.operation))
    Vector(
      Option.when(hasNames)(ix.get("researchers")),
      Option.when(has(SHORTEST_PATH))(ix.get("paths")),
      Option.when(has(COMMUNITY_COMPARISON))(ix.get("communities")),
      Option.when(
        hasTopics || has(SEMANTIC_EXPLANATION) || ops.exists(x =>
          x.scope == OperationScope.CORPUS && x.operation == RESEARCHER_RANKING
        )
      )(ix.get("topic-institutions")),
      Option.when(has(DIRECT_COAUTHORS, MAXIMUM_TEAM_SIZE))(ix.get("abstracts"))
    ).flatten.flatten.distinct.take(cfg.runtime.semantic.maxIndexes)
