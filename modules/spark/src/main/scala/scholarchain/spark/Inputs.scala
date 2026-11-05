package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}

/** Readers for the Hadoop run outputs (modules/hadoop/JOBS.md). Spark never reparses raw data. */
object Inputs:
  private val Prov =
    "STRUCT<schemaVersion: STRING, sourceDataset: STRING, sourceRecordId: STRING, snapshot: STRING, runId: STRING, policyVersion: STRING, modelVersion: STRING>"

  // Explicit schemas (no inference): the core record fields each job reads.
  val Mention =
    s"mentionId STRING, workId STRING, position INT, rawName STRING, orcid STRING, sourceAuthorId STRING, institutionIds ARRAY<STRING>, provenance $Prov"
  val Work =
    s"workId STRING, source STRING, sourceRecordId STRING, title STRING, normalizedTitle STRING, year INT, venueId STRING, workType STRING, doi STRING, arxivId STRING, provenance $Prov"
  val Pair = s"authorA STRING, authorB STRING, workId STRING, year INT, provenance $Prov"
  val Decision =
    "entityType STRING, sourceId STRING, targetId STRING, rulesFired ARRAY<STRING>, decision STRING, confidence DOUBLE"
  val SuffixGroup = "identifier STRING, groupSize BIGINT, rule STRING, policyVersion STRING"
  val OversizeGroup =
    "identifier STRING, entityType STRING, groupSize BIGINT, maxConflictGroupSize BIGINT"
  val Venue =
    s"venueId STRING, name STRING, venueType STRING, externalIds MAP<STRING, STRING>, provenance $Prov"
  val Topic = s"topicId STRING, name STRING, subfield STRING, field STRING, provenance $Prov"
  val Institution =
    s"institutionId STRING, name STRING, countryCode STRING, externalIds MAP<STRING, STRING>, provenance $Prov"
  val GraphEdge =
    s"edgeId STRING, edgeType STRING, fromId STRING, toId STRING, properties MAP<STRING, STRING>, provenance $Prov"

  /** Input directories, relative to the Hadoop run directory. */
  object Dir:
    private val J09 = "j09-graph-export"
    val Mentions = s"$J09/author-mentions"
    val Works = s"$J09/works"
    val Pairs = s"$J09/coauthor-pair-candidates"
    val Venues = s"$J09/venues"
    val Topics = s"$J09/topics"
    val Institutions = s"$J09/institutions"
    val HasTopic = s"$J09/graph-edges/edge_type=HAS_TOPIC"
    val AffiliatedWith = s"$J09/graph-edges/edge_type=AFFILIATED_WITH"
    def decisions(entity: String, outcome: String) =
      s"$J09/match-decisions/entity_type=$entity/outcome=$outcome"
    val SuffixConflicts = "j03-j04-exact-identifier/suffix-conflicts"
    val OversizeConflicts = "j03-j04-exact-identifier/oversize-conflicts"

  /** JSON Lines under `runDir/rel` with an explicit schema. FAILFAST: a malformed line fails the
    * run instead of becoming a null row. An absent directory reads as empty, because the Hadoop
    * jobs (LazyOutputFormat) create no directory for an empty output. Partition columns that
    * discovery appends are dropped; every needed value is a record field.
    */
  def json(spark: SparkSession, runDir: String, rel: String, ddl: String): DataFrame =
    val p = new Path(runDir, rel)
    val schema = StructType.fromDDL(ddl)
    if p.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(p) then
      spark.read
        .schema(schema)
        .option("mode", "FAILFAST")
        .json(p.toString)
        .select(schema.fieldNames.toSeq.map(col)*)
    else spark.createDataFrame(java.util.List.of[Row](), schema)

/** Spark forms of the core stable-ID functions (StableIds). */
object Ids:
  /** StableIds.hash: the first 32 hex digits of SHA-256 over the parts joined by U+001F. */
  def hash(parts: Column*): Column = substring(sha2(concat_ws("\u001f", parts*), 256), 1, 32)

  /** StableIds.researcherId(None, policy, anchor). */
  def researcherId(anchor: Column, policy: String): Column =
    concat(lit(s"scr:$policy:"), hash(lit("researcher"), lit(policy), anchor))

  /** StableIds.coauthorEdgeId for an already canonical pair (a < b). */
  def coauthorEdgeId(a: Column, b: Column, start: Column, end: Column, policy: String): Column =
    concat(
      lit("cae-"),
      hash(lit("coauthor-edge"), a, b, start.cast("string"), end.cast("string"), lit(policy))
    )

/** The match path of a decision, from its fired rule IDs. A copy of entity-resolution
  * `MatchPolicy.pathOf` (rule IDs are decision data; spark may not depend on entityResolution).
  */
object MatchPath:
  private val Exact = Seq(
    "er-v1:exact-id-match",
    "er-v1:exact-id-non-authoritative",
    "er-v1:exact-id-group-conflict",
    "er-v1:identifier-agreement",
    "er-v1:identifier-disagreement",
    "er-v2:same-source-shared-doi"
  )
  private val Deterministic =
    Seq("er-v1:deterministic-bibliographic", "er-v2:deterministic-title-authors-year")

  def of(rules: Column): Column =
    when(array_contains(rules, "er-v1:llm-adjudication"), lit("LLM"))
      .when(arrays_overlap(rules, array(Exact.map(lit)*)), lit("EXACT_ID"))
      .when(arrays_overlap(rules, array(Deterministic.map(lit)*)), lit("DETERMINISTIC"))
      .when(array_contains(rules, "er-v1:band-llm"), lit("LLM_BAND"))
      .otherwise(lit("PROBABILISTIC"))

  /** Ruling 6: tier 1 = EXACT_ID and DETERMINISTIC; tier 2 adds every other ACCEPTED path. */
  def tier(rules: Column): Column =
    when(of(rules).isin("EXACT_ID", "DETERMINISTIC"), lit(1)).otherwise(lit(2))
