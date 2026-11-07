package scholarchain.spark

import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.*
import org.apache.spark.sql.{Column, DataFrame}
import scholarchain.core.StableIds

/** Store-ready rows for the formats of modules/storage/STORAGE.md. Every builder returns DataFrame
  * columns; nothing is collected. The storage validators (`ValidateCli`) are the acceptance test.
  */
object StoreFiles:
  // ---- STORAGE.md constants (storage `NeptuneContract` and `DynamoContract`) -------------------
  val InlineEvidenceMax = 20
  val EvidenceSchema = "coauthor-evidence-v1"
  val MaxInlineIds = 50
  val RankingSchema = "researcher-ranking-v1"
  val RankingMax = 50
  val WindowItemSchema = "researcher-metrics-window-v1"
  val ChangeItemSchema = "researcher-change-v1"
  val EntityLinkSchema = "entity-link-v1"
  val ArtifactSchema = "artifact-registry-v1"

  // ---- Values as text --------------------------------------------------------------------------

  /** A double as plain decimal text (no exponent): Java's shortest form, or 20 fixed decimals with
    * trailing zeros removed when the shortest form has an exponent. Null stays null.
    */
  def plain(c: Column): Column =
    val shortest = format_string("%s", c)
    val fixed =
      regexp_replace(regexp_replace(format_string("%.20f", c), "0+$", ""), "\\.$", "")
    when(c.isNotNull, when(shortest.contains("E"), fixed).otherwise(shortest))

  /** Number text: integral types as is, fractional types through `plain`. */
  def number(c: Column, dt: DataType): Column = dt match
    case DoubleType | FloatType | _: DecimalType => plain(c)
    case _                                       => c.cast("string")

  /** One RFC 4180 field: null is empty (no value); a value with a comma, quote, CR, or LF is
    * double-quoted with inner quotes doubled.
    */
  def csvField(c: Column): Column =
    when(c.isNull, lit(""))
      .when(c.rlike("[,\"\r\n]"), concat(lit("\""), regexp_replace(c, "\"", "\"\""), lit("\"")))
      .otherwise(c)

  // ---- Neptune openCypher CSV ------------------------------------------------------------------

  /** One CSV column: its header (`:ID`, `name:Type`) and its text value. */
  final case class CsvCol(header: String, value: Column)
  def system(h: String, c: Column) = CsvCol(h, c)
  def str(n: String, c: Column) = CsvCol(s"$n:String", c)
  def int(n: String, c: Column) = CsvCol(s"$n:Int", c.cast("string"))
  def long(n: String, c: Column) = CsvCol(s"$n:Long", c.cast("string"))
  def dbl(n: String, c: Column) = CsvCol(s"$n:Double", plain(c))
  def bool(n: String, c: Column) = CsvCol(s"$n:Bool", c.cast("string"))

  /** The provenance columns every element ends with (edges add policyVersion). */
  def provenanceCols(family: String, edge: Boolean, prov: Column = col("provenance")) =
    Seq(
      str("runId", prov.getField("runId")),
      str("snapshot", prov.getField("snapshot")),
      str("schemaVersion", lit(s"neptune-$family-v1"))
    ) ++ Option.when(edge)(str("policyVersion", prov.getField("policyVersion")))

  def header(cols: Seq[CsvCol]): String = cols.map(_.header).mkString(",")
  def csvLine(cols: Seq[CsvCol]): Column = concat_ws(",", cols.map(c => csvField(c.value))*)

  /** "ge-" + H("graph-edge", type, start, end, policy): every edge ID except COAUTHORED_WITH. */
  def graphEdgeId(edgeType: String, start: Column, end: Column, policy: Column): Column =
    concat(lit("ge-"), Ids.hash(lit("graph-edge"), lit(edgeType), start, end, policy))

  // ---- DynamoDB JSON ---------------------------------------------------------------------------

  /** A DynamoDB attribute value of a column, null (attribute omitted) when the value is null. */
  def attr(c: Column, dt: DataType): Column = dt match
    case StringType               => when(c.isNotNull, struct(c.as("S")))
    case BooleanType              => when(c.isNotNull, struct(c.as("BOOL")))
    case _: NumericType           => when(c.isNotNull, struct(number(c, dt).as("N")))
    case ArrayType(StringType, _) =>
      when(c.isNotNull, struct(transform(c, x => struct(x.as("S"))).as("L")))
    case other => throw new IllegalArgumentException(s"no DynamoDB form for $other")

  def strAttr(c: Column): Column = attr(c, StringType)

  /** `{"Item": {...}}` with the attributes in the given order; null attributes are omitted. */
  def item(attrs: Seq[(String, Column)]): Column =
    to_json(struct(struct(attrs.map((n, c) => c.as(n))*).as("Item")))

  /** Every column of df as an attribute (by its type), except those in `skip`. */
  def attrsOf(df: DataFrame, skip: Set[String]): Seq[(String, Column)] =
    df.schema.fields.toSeq
      .filterNot(f => skip(f.name))
      .map(f => f.name -> attr(col(f.name), f.dataType))

  // ---- Top-N rankings (ruling 21) --------------------------------------------------------------

  /** Ranked metrics: (name, source table key) in DynamoContract.RankingMetrics order. */
  val RankingMetrics: Seq[(String, String)] = Seq(
    "publicationCount" -> "metrics",
    "distinctCoauthorCount" -> "metrics",
    "maximumCoauthorsOnOnePaper" -> "metrics",
    "crossCommunityDegree" -> "communities",
    "communitiesConnected" -> "communities",
    "participationCoefficient" -> "communities",
    "coreness" -> "metrics"
  )

  /** Top `n` per (metric, window): value descending, ties by researcherId ascending, null values
    * not ranked. Rows: metric, window, rank, researcherId, value (DOUBLE), valueText (number text).
    * `metrics` is researcher-metrics and `communities` researcher-communities (window,
    * researcherId, the metric columns).
    */
  def rankings(metrics: DataFrame, communities: DataFrame, n: Int = RankingMax): DataFrame =
    RankingMetrics
      .map { (m, src) =>
        val df = if src == "metrics" then metrics else communities
        df.where(col(m).isNotNull)
          .select(
            lit(m).as("metric"),
            col("window"),
            col("researcherId"),
            col(m).cast("double").as("value"),
            number(col(m), df.schema(m).dataType).as("valueText")
          )
      }
      .reduce(_ union _)
      .withColumn(
        "rank",
        row_number().over(
          Window.partitionBy("metric", "window").orderBy(desc("value"), col("researcherId"))
        )
      )
      .where(col("rank") <= n)

  // ---- EntityLinkCache (name-v1) ---------------------------------------------------------------

  /** DynamoContract.nameKey: drop a trailing DBLP homonym number, canonicalise (affn-v1), prefix
    * `name-v1:`; null when nothing is left. The storage validator recomputes it on every item.
    */
  def nameKeyOf(name: String): String =
    if name == null then null
    else
      val c = StableIds.canonicalAffiliationName(name.trim.replaceAll("\\s+\\d{4}$", ""))
      if c.isEmpty then null else s"name-v1:$c"

  val nameKey = udf(
    new UDF1[String, String]:
      def call(s: String): String = nameKeyOf(s)
    ,
    DataTypes.StringType
  )

  /** One row per distinct (nameKey, researcherId) over displayName and alternateNames; the matched
    * name is the display name when it gives the key, else the smallest alternate name.
    */
  def entityLinks(researchers: DataFrame): DataFrame =
    researchers
      .select(
        col("*"),
        posexplode(
          concat(
            array(col("displayName")),
            coalesce(col("alternateNames"), array().cast("array<string>"))
          )
        )
          .as(Seq("pos", "matchedName"))
      )
      .where(col("matchedName").isNotNull)
      .withColumn("nameKey", nameKey(col("matchedName")))
      .where(col("nameKey").isNotNull)
      .withColumn(
        "r",
        row_number().over(
          Window
            .partitionBy("nameKey", "researcherId")
            .orderBy(when(col("pos") === 0, 0).otherwise(1), col("matchedName"))
        )
      )
      .where(col("r") === 1)
      .drop("r", "pos")
