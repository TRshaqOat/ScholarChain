package scholarchain.storage

import scholarchain.core.config.TimeWindow
import scholarchain.core.{IdFormat, StableIds}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A DynamoDB attribute value. The JSON form is DynamoDB JSON ({"S": "..."}, {"N": "1"}, ...), the
  * form S3 import and the SDK use.
  */
enum AttributeValue:
  case S(value: String)
  case N(value: String)
  case Bool(value: Boolean)
  case Null
  case L(values: Vector[AttributeValue])
  case M(values: Map[String, AttributeValue])

object AttributeValue:
  def num(x: Long): AttributeValue = N(x.toString)
  def num(x: Double): AttributeValue = N(canonicalNumber(BigDecimal(x).toString))

  /** DynamoDB's stored text of a number: it trims leading and trailing zeros
    * (HowItWorks.NamingRulesDataTypes.html, "Number", accessed 2026-11-06), so "4.0" reads back as
    * "4" and "0.50" as "0.5". Plain notation, exact (java.math, no 34-digit rounding).
    */
  def canonicalNumber(n: String): String =
    java.math.BigDecimal(n).stripTrailingZeros.toPlainString

  /** The value with every number, at any depth, in canonicalNumber form. */
  def canonical(v: AttributeValue): AttributeValue = v match
    case N(n)  => N(canonicalNumber(n))
    case L(vs) => L(vs.map(canonical))
    case M(m)  => M(canonicalItem(m))
    case other => other

  def canonicalItem(item: Map[String, AttributeValue]): Map[String, AttributeValue] =
    item.map((k, x) => k -> canonical(x))

  /** Same item as DynamoDB stores it: numbers compared by value, not by their text. */
  def sameItem(a: Map[String, AttributeValue], b: Map[String, AttributeValue]): Boolean =
    canonicalItem(a) == canonicalItem(b)

  def toJson(v: AttributeValue): ujson.Value = v match
    case S(s)    => ujson.Obj("S" -> s)
    case N(n)    => ujson.Obj("N" -> n)
    case Bool(b) => ujson.Obj("BOOL" -> b)
    case Null    => ujson.Obj("NULL" -> true)
    case L(vs)   => ujson.Obj("L" -> ujson.Arr.from(vs.map(toJson)))
    case M(m)    =>
      ujson.Obj("M" -> ujson.Obj.from(m.toSeq.sortBy(_._1).map((k, x) => k -> toJson(x))))

  def fromJson(j: ujson.Value): Either[String, AttributeValue] = j match
    case o: ujson.Obj if o.value.size == 1 =>
      o.value.head match
        case ("S", ujson.Str(s)) => Right(S(s))
        case ("N", ujson.Str(n)) =>
          Either.cond(numberOk(n), N(n), s"bad number '$n'")
        case ("BOOL", ujson.Bool(b)) => Right(Bool(b))
        case ("NULL", ujson.True)    => Right(Null)
        case ("L", ujson.Arr(xs))    =>
          xs.toVector
            .foldLeft[Either[String, Vector[AttributeValue]]](Right(Vector.empty))((acc, x) =>
              acc.flatMap(a => fromJson(x).map(a :+ _))
            )
            .map(L(_))
        case ("M", m: ujson.Obj) =>
          m.value.toVector
            .foldLeft[Either[String, Map[String, AttributeValue]]](Right(Map.empty))((acc, kv) =>
              acc.flatMap(a => fromJson(kv._2).map(x => a + (kv._1 -> x)))
            )
            .map(M(_))
        case (t, _) => Left(s"unsupported attribute type '$t'")
    case other => Left(s"not a DynamoDB JSON attribute value: ${other.render().take(80)}")

  private val MinMagnitude = java.math.BigDecimal("1E-130")
  private val MaxMagnitude = java.math.BigDecimal("1E+126")

  /** A Number DynamoDB stores: valid text, at most 38 significant digits, and 0 or a magnitude from
    * 1E-130 to below 1E+126 (HowItWorks.NamingRulesDataTypes.html, "Number", accessed 2026-11-06).
    * The range check also keeps canonicalNumber from expanding an exponent such as 1E+999999999
    * (N4).
    */
  def numberOk(n: String): Boolean =
    Try(java.math.BigDecimal(n)).toOption.exists { d =>
      val a = d.abs
      significantDigits(n) <= 38 &&
      (a.signum == 0 || (a.compareTo(MinMagnitude) >= 0 && a.compareTo(MaxMagnitude) < 0))
    }

  /** Every number at any depth whose text numberOk refuses. */
  def badNumbers(v: AttributeValue): List[String] = v match
    case N(n) if !numberOk(n) => List(n)
    case L(vs)                => vs.toList.flatMap(badNumbers)
    case M(m)                 => m.values.toList.flatMap(badNumbers)
    case _                    => Nil

  def significantDigits(n: String): Int =
    val mantissa = n.takeWhile(c => c != 'e' && c != 'E').filter(_.isDigit)
    math.max(1, mantissa.dropWhile(_ == '0').reverse.dropWhile(_ == '0').length)

type Item = Map[String, AttributeValue]

/** Item size by the DynamoDB rules (CapacityUnitCalculations.html): name bytes plus value bytes;
  * numbers about one byte per two significant digits plus one; List and Map 3 bytes plus one per
  * element.
  */
object ItemSize:
  private def utf8(s: String) = s.getBytes(UTF_8).length

  def of(item: Item): Int = item.map((k, v) => utf8(k) + valueSize(v)).sum

  private def valueSize(v: AttributeValue): Int =
    import AttributeValue.*
    v match
      case S(s)           => utf8(s)
      case N(n)           => (significantDigits(n) + 1) / 2 + 1
      case Bool(_) | Null => 1
      case L(vs)          => 3 + vs.map(x => 1 + valueSize(x)).sum
      case M(m)           => 3 + m.map((k, x) => utf8(k) + 1 + valueSize(x)).sum

  /** Write request units for one standard (non-transactional) write: size rounded up to 1 KB. */
  def writeUnits(item: Item): Int = math.max(1, (of(item) + 1023) / 1024)

enum TableName:
  case RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry,
    ResearcherMetricCache

/** Key schema and item rules of one table (STORAGE.md, "DynamoDB"). Keys are strings. */
final case class TableSpec(
    name: TableName,
    partitionKey: String,
    sortKey: String,
    ttlAttribute: Option[String],
    required: Map[String, Char],
    loadedFromRuns: Boolean,
    checks: (Item, Map[String, TimeWindow]) => List[String]
)

object DynamoContract:
  val ItemHardLimitBytes = 400 * 1024 // DynamoDB maximum item size
  val ItemBudgetBytes = 32 * 1024 // ScholarChain budget: longer data goes to S3 pointers
  val PartitionKeyMaxBytes = 2048
  val SortKeyMaxBytes = 1024
  val TtlAttribute = "expiresAt"
  val MaxInlineIds = 50 // longer ID lists become an S3 pointer attribute <name>Ref
  val QueryMaxLimit = 1000

  /** Item schemaVersion names (STORAGE.md, "Item schema versions"), by table and sort-key prefix.
    * The runtime tables use the rag runtime schema names (modules/rag/RUNTIME.md, section 14).
    */
  def itemSchemaVersion(table: TableName, item: Item): Option[String] = table match
    case TableName.RunLedger             => Some("run-ledger-v1")
    case TableName.EntityLinkCache       => Some("entity-link-v1")
    case TableName.ArtifactRegistry      => Some("artifact-registry-v1")
    case TableName.ResearcherMetricCache =>
      s(item, "metricKey").collect {
        case k if k.startsWith("WINDOW#") => "researcher-metrics-window-v1"
        case k if k.startsWith("CHANGE#") => "researcher-change-v1"
        case k if k.startsWith("TOP#")    => "researcher-ranking-v1"
      }
    case TableName.QuerySession   => Some("rag-query-session-v1")
    case TableName.EvidenceBundle => Some("rag-evidence-bundle-item-v1")

  /** EntityLinkCache key of a name (rule name-v1): drop a trailing DBLP homonym number (" 0001"),
    * then the canonical form of StableIds.canonicalAffiliationName (NFKD, marks removed,
    * lower-case, non-alphanumeric runs to one space, trimmed). None when nothing is left.
    */
  def nameKey(name: String): Option[String] =
    val canonical = StableIds.canonicalAffiliationName(name.trim.replaceAll("\\s+\\d{4}$", ""))
    Option.when(canonical.nonEmpty)(s"name-v1:$canonical")

  private def s(item: collection.Map[String, AttributeValue], k: String): Option[String] =
    item.get(k).collect { case AttributeValue.S(v) => v }
  private def n(item: collection.Map[String, AttributeValue], k: String): Option[BigDecimal] =
    item.get(k).collect { case AttributeValue.N(v) => Try(BigDecimal(v)).toOption }.flatten
  private def check(ok: Boolean, msg: => String): List[String] = if ok then Nil else List(msg)

  private def windowKey(key: String, windows: Map[String, TimeWindow]): Option[TimeWindow] =
    Option.when(key.startsWith("WINDOW#"))(key.drop(7)).flatMap(windows.get)

  /** A list attribute of IDs, or its S3 pointer when longer than MaxInlineIds. */
  private def idList(item: Item, k: String, ok: String => Boolean): List[String] =
    (item.get(k), s(item, s"${k}Ref")) match
      case (Some(AttributeValue.L(xs)), None) =>
        check(xs.size <= MaxInlineIds, s"$k has ${xs.size} IDs; use ${k}Ref") ++
          check(
            xs.forall { case AttributeValue.S(x) => ok(x); case _ => false },
            s"$k holds a value that is not an ID"
          )
      case (None, Some(ref)) => S3Layout.keyProblems(ref).map(p => s"${k}Ref: $p")
      case (None, None)      => List(s"missing $k or ${k}Ref")
      case _                 => List(s"$k and ${k}Ref are both set or $k is not a list")

  val RunLedger = TableSpec(
    TableName.RunLedger,
    "runId",
    "entryKey",
    None,
    Map("entryType" -> 'S', "recordedAt" -> 'S', "schemaVersion" -> 'S'),
    loadedFromRuns = false,
    (i, _) => check(s(i, "runId").exists(IdFormat.isRunId), "runId is not a run ID")
  )
  val EntityLinkCache = TableSpec(
    TableName.EntityLinkCache,
    "nameKey",
    "researcherId",
    None,
    Map(
      "displayName" -> 'S',
      "matchedName" -> 'S',
      "identityConfidence" -> 'N',
      "mentionCount" -> 'N',
      "dblpAnchored" -> 'B'
    ),
    loadedFromRuns = true,
    (i, _) =>
      check(s(i, "researcherId").exists(IdFormat.isResearcherId), "researcherId is not one") ++
        check(
          s(i, "matchedName").flatMap(nameKey) == s(i, "nameKey"),
          "nameKey is not nameKey(matchedName)"
        ) ++
        check(n(i, "identityConfidence").forall(c => c >= 0 && c <= 1), "identityConfidence")
  )
  val QuerySession = TableSpec(
    TableName.QuerySession,
    "sessionId",
    "turnKey",
    Some(TtlAttribute),
    Map("queryId" -> 'S', "createdAt" -> 'S', TtlAttribute -> 'N', "schemaVersion" -> 'S'),
    loadedFromRuns = false,
    (i, _) =>
      check(
        (for c <- s(i, "createdAt"); q <- s(i, "queryId") yield s"$c#$q") == s(i, "turnKey"),
        "turnKey is not createdAt#queryId"
      )
  )
  val EvidenceBundle = TableSpec(
    TableName.EvidenceBundle,
    "queryId",
    "itemKey",
    Some(TtlAttribute),
    Map(TtlAttribute -> 'N', "schemaVersion" -> 'S'),
    loadedFromRuns = false,
    (i, _) =>
      check(
        s(i, "itemKey").exists(k =>
          k == "BUNDLE" || (k.startsWith("EV#") && IdFormat.isEvidenceId(k.drop(3)))
        ),
        "itemKey is not BUNDLE or EV#<evidenceId>"
      ) ++ ((s(i, "payload"), s(i, "payloadRef")) match
        case (Some(_), None)   => Nil
        case (None, Some(ref)) =>
          S3Layout.keyProblems(ref) ++
            check(s(i, "payloadHash").exists(Hashing.Shape.matches), "payloadRef needs payloadHash")
        case _ => List("exactly one of payload and payloadRef is required"))
  )
  val ArtifactRegistry = TableSpec(
    TableName.ArtifactRegistry,
    "artifactKey",
    "versionKey",
    None,
    Map.empty,
    loadedFromRuns = true,
    (i, _) =>
      s(i, "versionKey") match
        case Some("ACTIVE") => Nil
        case Some(k)        =>
          check(
            s(i, "runId").exists(r => k == s"RUN#$r"),
            "versionKey is not ACTIVE or RUN#<runId>"
          ) ++
            check(s(i, "manifestKey").exists(S3Layout.keyProblems(_).isEmpty), "manifestKey") ++
            check(s(i, "contentHash").exists(Hashing.Shape.matches), "contentHash") ++
            check(n(i, "rowCount").exists(_ >= 0), "rowCount")
        case None => Nil
  )
  val WindowMetrics = Vector(
    "windowStartYear",
    "windowEndYear",
    "publicationCount",
    "distinctCoauthorCount",
    "totalCoauthorOccurrences",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "weightedDegree",
    "componentSize",
    "coreness",
    "graphDegree"
  )

  /** Top-50 lists (ruling 21): the WINDOW# attributes that are ranked, one item per (metric,
    * window) in ResearcherMetricCache under partition RANKING#<metric>, sort key TOP#<window>.
    */
  val RankingMetrics: Vector[String] = Vector(
    "publicationCount",
    "distinctCoauthorCount",
    "maximumCoauthorsOnOnePaper",
    "crossCommunityDegree",
    "communitiesConnected",
    "participationCoefficient",
    "coreness"
  )
  val RankingPrefix = "RANKING#"
  val RankingMaxEntries = 50

  private def rankingProblems(i: Item, windows: Map[String, TimeWindow]): List[String] =
    val metric = s(i, "researcherId").getOrElse("").drop(RankingPrefix.length)
    val window = s(i, "metricKey").filter(_.startsWith("TOP#")).map(_.drop(4))
    val entries = i.get("entries") match
      case Some(AttributeValue.L(xs)) =>
        Some(xs.map {
          case AttributeValue.M(m) =>
            (n(m, "rank"), s(m, "researcherId"), n(m, "value")) match
              case (Some(r), Some(id), Some(v)) if m.size == 3 => Some((r, id, v))
              case _                                           => None
          case _ => None
        })
      case _ => None
    check(RankingMetrics.contains(metric), s"unknown ranking metric '$metric'") ++
      check(s(i, "metric").contains(metric), "metric differs from the partition key") ++
      (window.flatMap(windows.get) match
        case None =>
          List(s"ranking sort key ${s(i, "metricKey").getOrElse("")} is not TOP#<window>")
        case Some(w) =>
          check(s(i, "window").contains(w.name), "window differs from the sort key") ++
            check(
              n(i, "windowStartYear").contains(BigDecimal(w.startYear)) &&
                n(i, "windowEndYear").contains(BigDecimal(w.endYear)),
              s"window years differ from ${w.name}"
            )) ++
      (entries match
        case None                             => List("entries must be a list")
        case Some(es) if es.exists(_.isEmpty) =>
          List("each entry must be {rank: N, researcherId: S, value: N} and nothing else")
        case Some(es) =>
          val xs = es.flatten
          check(xs.nonEmpty && xs.size <= RankingMaxEntries, s"${xs.size} entries, 1 to 50") ++
            check(
              xs.zipWithIndex.forall((x, k) => x._1 == BigDecimal(k + 1)),
              "ranks must be 1, 2, 3, ... in list order"
            ) ++
            check(
              xs.forall(x => IdFormat.isResearcherId(x._2)),
              "an entry is not a researcher ID"
            ) ++
            check(xs.map(_._2).distinct.size == xs.size, "a researcher appears twice") ++
            check(
              xs.zip(xs.drop(1)).forall((a, b) => a._3 > b._3 || (a._3 == b._3 && a._2 < b._2)),
              "entries must be ordered by value descending, then researcherId ascending"
            ))

  val ResearcherMetricCache = TableSpec(
    TableName.ResearcherMetricCache,
    "researcherId",
    "metricKey",
    None,
    Map("policyVersion" -> 'S'),
    loadedFromRuns = true,
    (i, windows) =>
      if s(i, "researcherId").exists(_.startsWith(RankingPrefix)) then rankingProblems(i, windows)
      else
        check(s(i, "researcherId").exists(IdFormat.isResearcherId), "researcherId is not one") ++
          (s(i, "metricKey").getOrElse("") match
            case k if k.startsWith("WINDOW#") =>
              windowKey(k, windows) match
                case None    => List(s"unknown window in $k")
                case Some(w) =>
                  WindowMetrics.toList
                    .flatMap(m => check(n(i, m).isDefined, s"missing number $m")) ++
                    check(
                      n(i, "windowStartYear").contains(BigDecimal(w.startYear)) &&
                        n(i, "windowEndYear").contains(BigDecimal(w.endYear)),
                      s"window years differ from ${w.name}"
                    ) ++
                    check(s(i, "componentId").isDefined, "missing componentId") ++
                    idList(i, "maximumTeamWorkIds", IdFormat.isWorkId)
            case k if k.startsWith("CHANGE#") =>
              k.drop(7).split('>') match
                case Array(f, t) if windows.contains(f) && windows.contains(t) =>
                  check(s(i, "presence").exists(Set("BOTH", "FROM_ONLY", "TO_ONLY")), "presence")
                case _ => List(s"bad comparison key $k")
            case k => List(s"metricKey '$k' is not WINDOW#<window> or CHANGE#<from>><to>"))
  )

  val Tables: Map[TableName, TableSpec] =
    Vector(
      RunLedger,
      EntityLinkCache,
      QuerySession,
      EvidenceBundle,
      ArtifactRegistry,
      ResearcherMetricCache
    )
      .map(t => t.name -> t)
      .toMap
  val LoadTables: Set[TableName] =
    Set(
      TableName.RunLedger,
      TableName.EntityLinkCache,
      TableName.ArtifactRegistry,
      TableName.ResearcherMetricCache
    )
  val RuntimeTables: Set[TableName] = Set(TableName.QuerySession, TableName.EvidenceBundle)

  private def typeTag(v: AttributeValue): Char = v match
    case _: AttributeValue.S    => 'S'
    case _: AttributeValue.N    => 'N'
    case _: AttributeValue.Bool => 'B'
    case AttributeValue.Null    => '0'
    case _: AttributeValue.L    => 'L'
    case _: AttributeValue.M    => 'M'

  /** Rule violations of one item; empty means valid. */
  def problems(spec: TableSpec, item: Item, windows: Map[String, TimeWindow]): List[String] =
    def keyIssues(attr: String, max: Int) = s(item, attr) match
      case Some(v) if v.nonEmpty =>
        check(v.getBytes(UTF_8).length <= max, s"key $attr longer than $max bytes")
      case _ => List(s"missing string key attribute $attr")
    val required = spec.required.toList.flatMap((a, t) =>
      check(item.get(a).exists(typeTag(_) == t), s"missing or mistyped attribute $a ($t)")
    )
    val ttl = spec.ttlAttribute match
      case None => check(!item.contains(TtlAttribute), s"$TtlAttribute (TTL) is not allowed here")
      case Some(_) => Nil
    val provenance =
      if !spec.loadedFromRuns then Nil
      else
        check(s(item, "runId").exists(IdFormat.isRunId), "runId missing or malformed") ++
          check(s(item, "snapshot").exists(ProvenanceRules.Snapshot.matches), "snapshot missing") ++
          check(s(item, "schemaVersion").exists(_.nonEmpty), "schemaVersion missing")
    val schema = itemSchemaVersion(spec.name, item).toList.flatMap(v =>
      check(s(item, "schemaVersion").contains(v), s"schemaVersion is not $v")
    )
    val numbers = item.toList
      .sortBy(_._1)
      .flatMap((a, v) =>
        AttributeValue
          .badNumbers(v)
          .map(x => s"attribute $a holds an invalid number '${x.take(40)}'")
      )
    val size = if numbers.isEmpty then ItemSize.of(item) else 0
    numbers ++ keyIssues(spec.partitionKey, PartitionKeyMaxBytes) ++ keyIssues(
      spec.sortKey,
      SortKeyMaxBytes
    ) ++
      required ++ ttl ++ provenance ++ schema ++
      check(size <= ItemBudgetBytes, s"item is $size bytes, budget $ItemBudgetBytes") ++
      (if required.isEmpty && numbers.isEmpty then spec.checks(item, windows) else Nil)

  /** False for an item whose TTL has passed; DynamoDB deletes those only within days, so every read
    * filters them.
    */
  def unexpired(spec: TableSpec, item: Item, nowEpochSeconds: Long): Boolean =
    spec.ttlAttribute.forall(a =>
      item
        .get(a)
        .collect { case AttributeValue.N(v) => Try(BigDecimal(v)).toOption }
        .flatten
        .forall(_ > nowEpochSeconds)
    )

  /** RunLedger entries and ArtifactRegistry RUN# entries are never replaced. */
  def appendOnly(table: TableName, item: Item): Boolean =
    table == TableName.RunLedger ||
      (table == TableName.ArtifactRegistry &&
        item.get("versionKey").exists(_ != AttributeValue.S("ACTIVE")))

  def key(spec: TableSpec, item: Item): (String, String) =
    (s(item, spec.partitionKey).getOrElse(""), s(item, spec.sortKey).getOrElse(""))

/** Item files: JSON Lines, one {"Item": {...}} per line in DynamoDB JSON. */
object DynamoItemFile:
  def renderLine(item: Item): String =
    ujson.Obj("Item" -> AttributeValue.toJson(AttributeValue.M(item))("M")).render()

  def parseLine(line: String): Either[String, Item] =
    Try(ujson.read(line)).toEither.left.map(e => s"not JSON: ${e.getMessage}").flatMap {
      case o: ujson.Obj if o.value.keySet == Set("Item") =>
        AttributeValue.fromJson(ujson.Obj("M" -> o("Item"))).flatMap {
          case AttributeValue.M(m) => Right(m)
          case _                   => Left("Item is not a map")
        }
      case _ => Left("line is not {\"Item\": {...}}")
    }

  /** The .jsonl files under dir, sorted by path. */
  def files(dir: Path): Vector[Path] =
    if !java.nio.file.Files.isDirectory(dir) then Vector.empty
    else
      scala.util.Using.resource(java.nio.file.Files.walk(dir))(
        _.iterator.asScala
          .filter(p =>
            java.nio.file.Files.isRegularFile(p) && p.getFileName.toString.endsWith(".jsonl")
          )
          .toVector
          .sortBy(_.toString)
      )

  /** The table directories of a curated/dynamodb/snapshot=<d>/run=<r> directory: only loader-owned
    * table names, at least one.
    */
  def tableDirs(dir: Path): Either[String, Vector[(TableName, Path)]] =
    if !java.nio.file.Files.isDirectory(dir) then Left(s"$dir is not a directory")
    else
      val ds = scala.util.Using.resource(java.nio.file.Files.list(dir))(
        _.iterator.asScala.filter(java.nio.file.Files.isDirectory(_)).toVector.sortBy(_.toString)
      )
      val named = ds.map(d => d -> TableName.values.find(_.toString == d.getFileName.toString))
      named.collectFirst { case (d, None) => d } match
        case Some(d) => Left(s"$d is not a table name")
        case None    =>
          val ts = named.collect { case (d, Some(t)) => t -> d }
          ts.collectFirst { case (t, _) if !DynamoContract.LoadTables(t) => t } match
            case Some(t) => Left(s"$t is written at runtime only and is not loaded")
            case None    => Either.cond(ts.nonEmpty, ts, s"no table directories under $dir")

  /** Validates every line of the files for one table; returns violations and the item count. */
  def validate(
      table: TableName,
      files: Seq[Path],
      windows: Seq[TimeWindow]
  ): (Vector[Violation], Long) =
    val spec = DynamoContract.Tables(table)
    val byWindow = ProvenanceRules.windowsByName(windows)
    val seen = mutable.HashMap.empty[(String, String), String]
    val out = Vector.newBuilder[Violation]
    var count = 0L
    files.foreach { f =>
      val src = f.toString
      scala.util.Using.resource(java.nio.file.Files.lines(f, UTF_8)) { lines =>
        val it = lines.iterator
        var line = 0L
        while it.hasNext do
          val l = it.next()
          line += 1
          if l.trim.nonEmpty then
            count += 1
            parseLine(l) match
              case Left(e)     => out += Violation(src, line, "BAD_ITEM", e)
              case Right(item) =>
                DynamoContract
                  .problems(spec, item, byWindow)
                  .foreach(p => out += Violation(src, line, "BAD_ITEM", p))
                val k = DynamoContract.key(spec, item)
                seen.get(k) match
                  case Some(at) => out += Violation(src, line, "DUPLICATE_KEY", s"$k first at $at")
                  case None     => seen(k) = s"$src:$line"
      }
    }
    (out.result(), count)

enum LoadOutcome:
  case Inserted, Unchanged, Conflict

enum PutOutcome:
  case Written, ConditionFailed

/** Read side; runtime services get this. Expired TTL items are never returned (DynamoDB deletes
  * them only within a few days, so live reads filter them too).
  */
trait KeyValueReadPort:
  def get(table: TableName, pk: String, sk: String): Option[Item]

  /** Items of one partition whose sort key starts with skPrefix, in sort-key order, at most limit
    * (1 to DynamoContract.QueryMaxLimit).
    */
  def query(table: TableName, pk: String, skPrefix: String, limit: Int): Vector[Item]

/** Load side for the loader-owned tables (RunLedger, EntityLinkCache, ArtifactRegistry,
  * ResearcherMetricCache).
  */
trait KeyValueLoadPort:
  /** Conditional put (attribute_not_exists). An identical existing item is Unchanged; a different
    * one is a Conflict and is not written.
    */
  def putNew(table: TableName, item: Item): Either[List[String], LoadOutcome]

  /** Explicit supersede: put only if the stored item's runId equals expectedRunId. Refused for the
    * append-only RunLedger and ArtifactRegistry RUN# entries.
    */
  def replace(table: TableName, item: Item, expectedRunId: String): Either[List[String], PutOutcome]

/** Runtime writes: create-only puts to QuerySession and EvidenceBundle. */
trait RuntimeRecordPort:
  def create(table: TableName, item: Item): Either[List[String], LoadOutcome]

/** In-memory local fake of the six tables, with conditional writes and TTL. nowEpochSeconds is the
  * clock TTL is read against.
  */
final class InMemoryDynamo(windows: Seq[TimeWindow], nowEpochSeconds: () => Long):
  private val byWindow = ProvenanceRules.windowsByName(windows)
  private val tables =
    TableName.values.map(t => t -> mutable.TreeMap.empty[(String, String), Item]).toMap

  def itemCount(table: TableName): Int = synchronized(tables(table).size)

  private def live(spec: TableSpec, item: Item): Boolean =
    DynamoContract.unexpired(spec, item, nowEpochSeconds())

  /** DynamoDB PutItem with a condition expression. */
  private def put(
      table: TableName,
      item: Item,
      condition: Option[(String, AttributeValue)]
  ): Either[List[String], PutOutcome] = synchronized {
    val spec = DynamoContract.Tables(table)
    DynamoContract.problems(spec, item, byWindow) match
      case Nil =>
        val k = DynamoContract.key(spec, item)
        val existing = tables(table).get(k)
        val ok = condition match
          case None         => existing.isEmpty
          case Some((a, v)) => existing.exists(_.get(a).contains(v))
        if ok then
          tables(table)(k) = AttributeValue.canonicalItem(item) // what DynamoDB would store
          Right(PutOutcome.Written)
        else Right(PutOutcome.ConditionFailed)
      case problems => Left(problems)
  }

  private def putNewIn(allowed: Set[TableName])(table: TableName, item: Item) =
    if !allowed(table) then Left(List(s"$table is not writable through this port"))
    else
      put(table, item, None).map {
        case PutOutcome.Written         => LoadOutcome.Inserted
        case PutOutcome.ConditionFailed =>
          val k = DynamoContract.key(DynamoContract.Tables(table), item)
          if synchronized(tables(table).get(k)).exists(AttributeValue.sameItem(_, item)) then
            LoadOutcome.Unchanged
          else LoadOutcome.Conflict
      }

  val reader: KeyValueReadPort = new KeyValueReadPort:
    def get(table: TableName, pk: String, sk: String): Option[Item] =
      InMemoryDynamo.this.synchronized(
        tables(table).get((pk, sk)).filter(live(DynamoContract.Tables(table), _))
      )
    def query(table: TableName, pk: String, skPrefix: String, limit: Int): Vector[Item] =
      require(limit >= 1 && limit <= DynamoContract.QueryMaxLimit, s"limit $limit out of range")
      val spec = DynamoContract.Tables(table)
      InMemoryDynamo.this.synchronized(
        tables(table)
          .rangeFrom((pk, skPrefix))
          .iterator
          .takeWhile { case ((p, s), _) => p == pk && s.startsWith(skPrefix) }
          .map(_._2)
          .filter(live(spec, _))
          .take(limit)
          .toVector
      )

  val loader: KeyValueLoadPort = new KeyValueLoadPort:
    def putNew(table: TableName, item: Item): Either[List[String], LoadOutcome] =
      putNewIn(DynamoContract.LoadTables)(table, item)
    def replace(table: TableName, item: Item, expectedRunId: String) =
      if !DynamoContract.LoadTables(table) then
        Left(List(s"$table is not writable through this port"))
      else if DynamoContract.appendOnly(table, item) then Left(List(s"$table entry is append-only"))
      else put(table, item, Some("runId" -> AttributeValue.S(expectedRunId)))

  val runtimeWriter: RuntimeRecordPort = new RuntimeRecordPort:
    def create(table: TableName, item: Item): Either[List[String], LoadOutcome] =
      putNewIn(DynamoContract.RuntimeTables)(table, item)

  /** Loads DynamoDB item files into the loader-owned tables, reading the files only. dir is a
    * `curated/dynamodb/snapshot=<d>/run=<r>` directory of table directories. Every file is
    * validated first and nothing is loaded on a violation; an item that conflicts with one already
    * loaded is a CONFLICT violation. Returns the items per table (inserted or unchanged).
    */
  def loadItemFiles(dir: Path): Either[Vector[Violation], Map[TableName, Long]] =
    DynamoItemFile
      .tableDirs(dir)
      .left
      .map(e => Vector(Violation(dir.toString, 0, "BAD_INPUT", e)))
      .flatMap { tables =>
        val files = tables.map((t, d) => t -> DynamoItemFile.files(d))
        val invalid = files.flatMap((t, fs) => DynamoItemFile.validate(t, fs, windows)._1)
        if invalid.nonEmpty then Left(invalid)
        else
          val conflicts = Vector.newBuilder[Violation]
          val counts = files.map { (t, fs) =>
            var n = 0L
            fs.foreach { f =>
              scala.util.Using.resource(java.nio.file.Files.lines(f, UTF_8)) { lines =>
                lines.iterator.asScala.zipWithIndex.filter(_._1.trim.nonEmpty).foreach { (l, k) =>
                  n += 1
                  DynamoItemFile
                    .parseLine(l)
                    .flatMap(i => loader.putNew(t, i).left.map(_.mkString("; "))) match
                    case Right(LoadOutcome.Conflict) =>
                      conflicts += Violation(
                        f.toString,
                        k + 1L,
                        "CONFLICT",
                        "differs from a loaded item"
                      )
                    case Left(e)  => conflicts += Violation(f.toString, k + 1L, "BAD_ITEM", e)
                    case Right(_) => ()
                }
              }
            }
            t -> n
          }
          val found = conflicts.result()
          Either.cond(found.isEmpty, counts.toMap, found)
      }

/** File-backed runtime record store for local runs (Phase 9 ruling 2): create-only QuerySession and
  * EvidenceBundle items, one append-only JSON Lines log per table (`root/<Table>.jsonl`, the item
  * file format), replayed into an in-memory table on open, like FileVectorStore. A partial last
  * line left by a crash was never acknowledged and is cut on open; any other unreadable line stops
  * the open. Reads hide expired items like the live tables.
  */
final class FileRuntimeRecords(root: Path, windows: Seq[TimeWindow], nowEpochSeconds: () => Long):
  private val db = InMemoryDynamo(windows, nowEpochSeconds)
  private def log(t: TableName) = root.resolve(s"$t.jsonl")

  DynamoContract.RuntimeTables.toVector.sortBy(_.ordinal).foreach { t =>
    val f = log(t)
    if java.nio.file.Files.exists(f) then
      val bytes = java.nio.file.Files.readAllBytes(f)
      val complete = bytes.lastIndexOf('\n'.toByte) + 1
      if complete < bytes.length then
        scala.util.Using.resource(
          java.nio.channels.FileChannel.open(f, java.nio.file.StandardOpenOption.WRITE)
        )(
          _.truncate(complete.toLong)
        )
      new String(bytes, 0, complete, UTF_8)
        .split('\n')
        .iterator
        .filter(_.nonEmpty)
        .zipWithIndex
        .foreach { (l, k) =>
          DynamoItemFile
            .parseLine(l)
            .flatMap(i => db.runtimeWriter.create(t, i).left.map(_.mkString("; "))) match
            case Right(LoadOutcome.Inserted) => ()
            case other => throw IllegalStateException(s"$f line ${k + 1}: cannot replay ($other)")
        }
  }

  def itemCount(t: TableName): Int = db.itemCount(t)

  /** Query and get on the runtime tables (the loader tables are empty here). */
  val reader: KeyValueReadPort = db.reader

  val writer: RuntimeRecordPort = new RuntimeRecordPort:
    def create(table: TableName, item: Item): Either[List[String], LoadOutcome] =
      FileRuntimeRecords.this.synchronized {
        val r = db.runtimeWriter.create(table, item)
        if r == Right(LoadOutcome.Inserted) then
          java.nio.file.Files.createDirectories(root)
          java.nio.file.Files.writeString(
            log(table),
            DynamoItemFile.renderLine(item) + "\n",
            UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
          )
        r
      }
