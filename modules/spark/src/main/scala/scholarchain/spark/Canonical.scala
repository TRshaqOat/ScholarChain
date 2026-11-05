package scholarchain.spark

import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.{Column, DataFrame}
import scholarchain.core.{SchemaVersions, StableIds}

/** Run-scoped values every builder stamps on its output. */
final case class Ctx(
    snapshot: String,
    runId: String,
    canonicalPolicy: String,
    graphPolicy: String,
    ccMaxRounds: Int
):
  /** Core Provenance for a DERIVED record (sourceRecordId = the record's own ID). */
  def provenance(recordId: Column, policy: String): Column = struct(
    lit(SchemaVersions.Provenance).as("schemaVersion"),
    lit("DERIVED").as("sourceDataset"),
    recordId.as("sourceRecordId"),
    lit(snapshot).as("snapshot"),
    lit(runId).as("runId"),
    lit(policy).as("policyVersion"),
    lit(null).cast("string").as("modelVersion")
  )

  /** Source records passed through unchanged (venues, topics, institutions): their J09 provenance
    * moves to `sourceProvenance` and `provenance` names this run (ruling 21).
    */
  def restamp(df: DataFrame, id: String): DataFrame =
    df.withColumnRenamed("provenance", "sourceProvenance")
      .withColumn("provenance", provenance(col(id), graphPolicy))

/** Integrity violations: (check, subjectId, relatedId, detail). Never dropped silently. */
object Violations:
  def apply(check: String, df: DataFrame, subject: Column, related: Column, detail: Column) =
    df.select(
      lit(check).as("check"),
      subject.as("subjectId"),
      related.cast("string").as("relatedId"),
      detail.cast("string").as("detail")
    )

  /** A violation table as written: provenance names the run; the source pointer is the subject. */
  def stamped(ctx: Ctx, df: DataFrame): DataFrame = df.withColumn(
    "provenance",
    ctx.provenance(
      concat_ws("|", col("check"), col("subjectId"), col("relatedId")),
      ctx.graphPolicy
    )
  )

/** Spark job 1: canonical researchers, works, AUTHORED, venues, topics, institutions. */
object Canonical:
  /** DBLP homonym number, as entity-resolution `Normalize.splitHomonym`. */
  val HomonymPattern = "^(.*\\S)\\s+(\\d{4})$"
  def homonymSuffix(raw: Column): Column =
    val s = regexp_extract(trim(raw), HomonymPattern, 2)
    when(s =!= "", s)

  /** StableIds.normalizeOrcid: the bare iD when the checksum holds, else null. */
  val validOrcid = udf(
    new UDF1[String, String]:
      def call(s: String): String = Option(s).flatMap(StableIds.normalizeOrcid).orNull
    ,
    DataTypes.StringType
  )

  private def endpoints(df: DataFrame): DataFrame =
    df.select(explode(array(col("src"), col("dst"))).as("node")).distinct()

  /** Accepted edges without the edges that touch `bad` nodes. */
  private def withoutNodes(edges: DataFrame, bad: DataFrame): DataFrame =
    edges
      // shortcut: broadcasts the dangling set, which is expected tiny (0 on the R2 input); a
      // corrupt input with millions of dangling endpoints would need a shuffle join instead.
      .join(broadcast(bad.select(col("node").as("src"))), Seq("src"), "left_anti")
      .join(broadcast(bad.select(col("node").as("dst"))), Seq("dst"), "left_anti")

  final case class Clusters(result: Clustering.Result, violations: DataFrame, groupCheck: DataFrame)

  /** Researcher clustering over author mentions.
    *
    * @param accepted
    *   (src, dst, tier, confidence) ACCEPTED researcher decisions; endpoints are mention IDs or
    *   identifier keys (no `#`)
    * @param groups
    *   (identifier, groupSize, origin) J03+J04 suffix-conflict and RESEARCHER oversize groups
    */
  def researchers(
      ctx: Ctx,
      mentions: DataFrame,
      accepted: DataFrame,
      conflicts: DataFrame,
      unresolved: DataFrame,
      groups: DataFrame
  ): Clusters =
    val known = mentions.select(col("mentionId").as("node"))
    val dangling =
      endpoints(accepted).where(col("node").contains("#")).join(known, Seq("node"), "left_anti")
    val edges = withoutNodes(accepted, dangling)
    val keyNodes = endpoints(edges).where(col("node").startsWith("orcid:"))
    val attrs = mentions
      .select(
        col("mentionId").as("node"),
        validOrcid(col("orcid")).as("orcid"),
        homonymSuffix(col("rawName")).as("suffix")
      )
      .union(
        keyNodes.select(
          col("node"),
          validOrcid(regexp_replace(col("node"), "^orcid:", "")).as("orcid"),
          lit(null).cast("string").as("suffix")
        )
      )
    // A group's members are the mentions carrying its identifier (ruling 5d).
    val mentionKeys = mentions
      .select(
        col("mentionId").as("node"),
        explode(array(concat(lit("orcid:"), col("orcid")), col("sourceAuthorId"))).as("identifier")
      )
      .where(col("identifier").isNotNull)
    val members = groups
      .join(mentionKeys, "identifier")
      .select(col("identifier"), col("identifier").as("groupId"), col("node"))
    val result = Clustering.twoTier(
      mentions.select(col("mentionId").as("member")),
      edges,
      conflicts,
      attrs,
      members.select("groupId", "node"),
      endpoints(unresolved),
      ctx.ccMaxRounds
    )
    val violations = Violations(
      "RESEARCHER_DECISION_ENDPOINT_MISSING",
      dangling,
      col("node"),
      lit(null),
      lit(
        "ACCEPTED researcher decision names a mention that is not in author-mentions; edge not used"
      )
    )
    Clusters(result, violations, groupCheck(groups, members))

  /** Work clustering over all source works. Only rule 5b applies (ruling 5), including capped DOI
    * groups: two works of one source carrying a capped group's identifier never share a cluster.
    */
  def works(
      ctx: Ctx,
      works: DataFrame,
      accepted: DataFrame,
      conflicts: DataFrame,
      unresolved: DataFrame,
      groups: DataFrame
  ): Clusters =
    val known = works.select(col("workId").as("node"))
    val dangling = endpoints(accepted).join(known, Seq("node"), "left_anti")
    val edges = withoutNodes(accepted, dangling)
    val workKeys = works
      .select(
        col("workId").as("node"),
        col("source"),
        explode(array(concat(lit("doi:"), col("doi")), concat(lit("arxiv:"), col("arxivId"))))
          .as("identifier")
      )
      .where(col("identifier").isNotNull)
    val members = groups
      .join(workKeys, "identifier")
      .select(
        col("identifier"),
        concat_ws("|", col("identifier"), col("source")).as("groupId"),
        col("node")
      )
    val noAttrs = works.select(
      col("workId").as("node"),
      lit(null).cast("string").as("orcid"),
      lit(null).cast("string").as("suffix")
    )
    val result = Clustering.twoTier(
      works.select(col("workId").as("member")),
      edges,
      conflicts,
      noAttrs.limit(0),
      members.select("groupId", "node"),
      endpoints(unresolved),
      ctx.ccMaxRounds
    )
    val violations = Violations(
      "WORK_DECISION_ENDPOINT_MISSING",
      dangling,
      col("node"),
      lit(null),
      lit("ACCEPTED work decision names a work that is not in works; edge not used")
    )
    Clusters(result, violations, groupCheck(groups, members))

  /** Ruling 5d: the member count found for each group next to the groupSize J03+J04 reported. */
  private def groupCheck(groups: DataFrame, members: DataFrame): DataFrame =
    groups.join(
      members.groupBy("identifier").agg(countDistinct("node").as("memberCount")),
      Seq("identifier"),
      "left"
    )

  /** Mention-to-canonical map: one row per mention with its researcher and why it stayed apart. */
  def mentionMap(ctx: Ctx, mapping: DataFrame, mentions: DataFrame): DataFrame =
    mapping
      .withColumn("researcherId", Ids.researcherId(col("anchor"), ctx.canonicalPolicy))
      .join(
        mentions.select(
          col("mentionId").as("member"),
          col("workId"),
          col("provenance.sourceDataset").as("sourceDataset"),
          col("provenance.sourceRecordId").as("sourceRecordId")
        ),
        "member"
      )
      .select(
        col("member").as("mentionId"),
        col("researcherId"),
        col("workId").as("sourceWorkId"),
        col("sourceDataset"),
        col("sourceRecordId"),
        col("assignedTier"),
        col("clusterMemberCount").as("clusterMentionCount"),
        col("separationReason"),
        col("tier2Rules").as("tier2RefusalRules"),
        col("tier1Rules").as("tier1RefusalRules"),
        col("identityConfidence"),
        ctx.provenance(col("member"), ctx.canonicalPolicy).as("provenance")
      )

  /** Work map: one row per source work with its canonical work ID (the smallest member work ID,
    * which is the DBLP member whenever the cluster has one, because "dblp:" sorts first).
    */
  def workMap(ctx: Ctx, mapping: DataFrame, works: DataFrame): DataFrame =
    mapping
      .join(
        works.select(
          col("workId").as("member"),
          col("source").as("sourceDataset"),
          col("sourceRecordId")
        ),
        "member"
      )
      .select(
        col("member").as("sourceWorkId"),
        col("anchor").as("workId"),
        col("sourceDataset"),
        col("sourceRecordId"),
        col("assignedTier"),
        col("clusterMemberCount"),
        col("separationReason"),
        col("tier2Rules").as("tier2RefusalRules"),
        col("tier1Rules").as("tier1RefusalRules"),
        col("identityConfidence"),
        ctx.provenance(col("member"), ctx.canonicalPolicy).as("provenance")
      )

  private def uncertainty(reason: Column, t2: Column, t1: Column): Column =
    when(
      reason === "REFUSED_TIER2",
      concat(
        lit("tier-2 cluster refused ("),
        array_join(t2, ","),
        lit("); kept the tier-1 cluster")
      )
    ).when(
      reason === "REFUSED_TIER1",
      concat(lit("tier-1 cluster refused ("), array_join(t1, ","), lit("); kept apart"))
    ).when(reason === "UNRESOLVED", lit("UNRESOLVED match decisions exist; not merged"))

  /** Canonical Researcher table (CanonicalResearcher fields plus cluster facts). */
  def researcherTable(ctx: Ctx, mentionMap: DataFrame, mentions: DataFrame): DataFrame =
    mentionMap
      .join(
        mentions.select(col("mentionId"), col("rawName"), validOrcid(col("orcid")).as("orcid")),
        "mentionId"
      )
      .groupBy("researcherId")
      .agg(
        min_by(col("rawName"), col("mentionId")).as("displayName"),
        array_sort(collect_set("rawName")).as("names"),
        array_sort(collect_set("orcid")).as("orcids"),
        count(lit(1)).as("mentionCount"),
        min("mentionId").as("anchorMentionId"),
        array_sort(collect_set("sourceDataset")).as("sourceDatasets"),
        max("assignedTier").as("assignedTier"),
        max("separationReason").as("separationReason"),
        first("tier2RefusalRules", ignoreNulls = true).as("t2"),
        first("tier1RefusalRules", ignoreNulls = true).as("t1"),
        min("identityConfidence").as("identityConfidence")
      )
      .select(
        col("researcherId"),
        col("displayName"),
        array_remove(col("names"), col("displayName")).as("alternateNames"),
        when(size(col("orcids")) === 1, element_at(col("orcids"), 1)).as("orcid"),
        col("mentionCount"),
        col("anchorMentionId"),
        col("sourceDatasets"),
        array_contains(col("sourceDatasets"), "DBLP").as("dblpAnchored"),
        col("assignedTier"),
        col("separationReason"),
        col("identityConfidence"),
        uncertainty(col("separationReason"), col("t2"), col("t1")).as("uncertainty"),
        ctx.provenance(col("researcherId"), ctx.canonicalPolicy).as("provenance")
      )

  /** Canonical Work table. Title, venue, and type come from the canonical (anchor) member; year and
    * DOI fall back to the members when the anchor has none.
    */
  def workTable(ctx: Ctx, workMap: DataFrame, works: DataFrame): DataFrame =
    workMap
      .join(works.withColumnRenamed("workId", "sourceWorkId"), "sourceWorkId")
      .groupBy("workId")
      .agg(
        min_by(col("title"), col("sourceWorkId")).as("title"),
        min_by(col("normalizedTitle"), col("sourceWorkId")).as("normalizedTitle"),
        coalesce(min_by(col("year"), col("sourceWorkId")), min("year")).as("year"),
        coalesce(min_by(col("venueId"), col("sourceWorkId")), min("venueId")).as("venueId"),
        min_by(col("workType"), col("sourceWorkId")).as("workType"),
        coalesce(min_by(col("doi"), col("sourceWorkId")), min("doi")).as("doi"),
        array_sort(collect_list("sourceWorkId")).as("memberWorkIds"),
        array_sort(collect_set("sourceDataset")).as("sourceDatasets"),
        max("assignedTier").as("assignedTier"),
        max("separationReason").as("separationReason"),
        first("tier2RefusalRules", ignoreNulls = true).as("t2"),
        first("tier1RefusalRules", ignoreNulls = true).as("t1"),
        min("identityConfidence").as("identityConfidence")
      )
      .select(
        col("workId"),
        col("title"),
        col("normalizedTitle"),
        col("year"),
        col("venueId"),
        col("workType"),
        col("doi"),
        col("memberWorkIds"),
        col("sourceDatasets"),
        array_contains(col("sourceDatasets"), "DBLP").as("dblpAnchored"),
        col("assignedTier"),
        col("separationReason"),
        col("identityConfidence"),
        uncertainty(col("separationReason"), col("t2"), col("t1")).as("uncertainty"),
        ctx.provenance(col("workId"), ctx.canonicalPolicy).as("provenance")
      )

  /** AUTHORED: one row per (canonical researcher, canonical work), listing the mentions behind it.
    * Mentions whose source work has no work record are integrity violations, not edges.
    */
  def authored(ctx: Ctx, mentionMap: DataFrame, workMap: DataFrame, workTable: DataFrame) =
    val m = mentionMap
      .select("mentionId", "researcherId", "sourceWorkId", "sourceDataset")
      .withColumn("position", regexp_extract(col("mentionId"), "#(\\d+)$", 1).cast("int"))
      .join(workMap.select("sourceWorkId", "workId"), Seq("sourceWorkId"), "left")
    val missing = Violations(
      "AUTHORED_WORK_MISSING",
      m.where(col("workId").isNull),
      col("mentionId"),
      col("sourceWorkId"),
      lit("mention's source work is not in works; no AUTHORED edge")
    )
    val edges = m
      .where(col("workId").isNotNull)
      .groupBy("researcherId", "workId")
      .agg(
        array_sort(collect_list("mentionId")).as("mentionIds"),
        min_by(col("position"), col("mentionId")).as("position"),
        array_sort(collect_set("sourceDataset")).as("sourceDatasets")
      )
      .join(workTable.select("workId", "year"), Seq("workId"), "left")
      .select(
        col("researcherId"),
        col("workId"),
        col("mentionIds"),
        col("position"),
        col("year"),
        col("sourceDatasets"),
        ctx
          .provenance(concat_ws("|", col("researcherId"), col("workId")), ctx.canonicalPolicy)
          .as("provenance")
      )
    (edges, missing)

  /** Every AUTHORED edge must point to an existing researcher and work (job 1 validation). */
  def authoredIntegrity(authored: DataFrame, researchers: DataFrame, works: DataFrame) =
    Violations(
      "AUTHORED_RESEARCHER_MISSING",
      authored.join(researchers.select("researcherId"), Seq("researcherId"), "left_anti"),
      col("researcherId"),
      col("workId"),
      lit("AUTHORED edge names a researcher that is not in researchers")
    ).union(
      Violations(
        "AUTHORED_WORK_NODE_MISSING",
        authored.join(works.select("workId"), Seq("workId"), "left_anti"),
        col("workId"),
        col("researcherId"),
        lit("AUTHORED edge names a work that is not in works")
      )
    )

  /** HAS_TOPIC mapped onto canonical works (OpenAlex primary topic). */
  def workTopics(ctx: Ctx, hasTopic: DataFrame, workMap: DataFrame, topics: DataFrame) =
    val m = hasTopic
      .select(col("fromId").as("sourceWorkId"), col("toId").as("topicId"), col("edgeId"))
      .join(workMap.select("sourceWorkId", "workId"), Seq("sourceWorkId"), "left")
    val links = m
      .where(col("workId").isNotNull)
      .groupBy("workId", "topicId")
      .agg(array_sort(collect_set("edgeId")).as("evidenceEdgeIds"))
      .withColumn(
        "provenance",
        ctx.provenance(concat_ws("|", col("workId"), col("topicId")), ctx.graphPolicy)
      )
    val bad = Violations(
      "HAS_TOPIC_WORK_MISSING",
      m.where(col("workId").isNull),
      col("edgeId"),
      col("sourceWorkId"),
      lit("HAS_TOPIC edge names a work that is not in works")
    ).union(
      Violations(
        "HAS_TOPIC_TOPIC_MISSING",
        links.join(topics.select("topicId"), Seq("topicId"), "left_anti"),
        col("workId"),
        col("topicId"),
        lit("HAS_TOPIC edge names a topic that is not in topics")
      )
    )
    (links, bad)

  /** Researcher-institution links: from mention institution IDs, and from ORCID employment
    * (AFFILIATED_WITH) when exactly one canonical researcher carries that ORCID iD.
    */
  def researcherInstitutions(
      ctx: Ctx,
      mentions: DataFrame,
      mentionMap: DataFrame,
      researchers: DataFrame,
      affiliatedWith: DataFrame,
      institutions: DataFrame
  ) =
    val fromMentions = mentions
      .select(col("mentionId"), explode(col("institutionIds")).as("institutionId"))
      .join(mentionMap.select("mentionId", "researcherId"), "mentionId")
      .select(
        col("researcherId"),
        col("institutionId"),
        lit("MENTION").as("via"),
        col("mentionId").as("evidenceId")
      )
    val owners = researchers
      .where(col("orcid").isNotNull)
      .groupBy("orcid")
      .agg(count(lit(1)).as("n"), min("researcherId").as("researcherId"))
    val aff = affiliatedWith
      .select(
        regexp_replace(col("fromId"), "^orcid:", "").as("orcid"),
        col("toId").as("institutionId"),
        col("edgeId")
      )
      .join(owners, Seq("orcid"), "left")
    val fromOrcid = aff
      .where(col("n") === 1)
      .select(
        col("researcherId"),
        col("institutionId"),
        lit("ORCID_EMPLOYMENT").as("via"),
        col("edgeId").as("evidenceId")
      )
    val links = fromMentions
      .union(fromOrcid)
      .groupBy("researcherId", "institutionId")
      .agg(
        array_sort(collect_set("via")).as("via"),
        array_sort(collect_set("evidenceId")).as("evidenceIds")
      )
      .withColumn(
        "provenance",
        ctx.provenance(concat_ws("|", col("researcherId"), col("institutionId")), ctx.graphPolicy)
      )
    val unlinked = aff.where(col("n").isNull || col("n") =!= 1)
    val bad = Violations(
      "RESEARCHER_INSTITUTION_MISSING",
      links.join(institutions.select("institutionId"), Seq("institutionId"), "left_anti"),
      col("researcherId"),
      col("institutionId"),
      lit("linked institution is not in institutions")
    )
    (links, bad, unlinked)

  /** Works whose venue is not in the venue table. */
  def venueIntegrity(works: DataFrame, venues: DataFrame): DataFrame =
    Violations(
      "WORK_VENUE_MISSING",
      works
        .where(col("venueId").isNotNull)
        .join(venues.select("venueId"), Seq("venueId"), "left_anti"),
      col("workId"),
      col("venueId"),
      lit("canonical work's venue is not in venues")
    )
