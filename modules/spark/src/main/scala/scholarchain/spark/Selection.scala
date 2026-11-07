package scholarchain.spark

import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.sql.{Column, DataFrame}

/** Embedding-candidate selection `selection-v1`, exactly as modules/rag/SELECTION.md (owner:
  * rag-engineer). Every step is a DataFrame; only the selected rows (at most a few thousand per
  * index) are ordered globally.
  */
object Selection:
  val PolicyVersion = "selection-v1"
  val Seed = "20261003"
  val ProfileQuota = 5000
  val PaperQuota = 1000
  val PaperTemplate = "source-title-v1"
  val PaperDoc = "paper-summary"
  val PaperFactsVersion = "paper-facts-v1"

  /** Community quota per window name (SELECTION.md). */
  val CommunityQuotas: Map[String, Int] =
    Map("all-years" -> 2000, "2000-2014" -> 1000, "2015-2026" -> 1000)

  /** rankKey(index, subjectId, window) = H("embed-select", policy, seed, index, subjectId, window).
    */
  def rankKey(index: Column, subjectId: Column, window: Column): Column =
    Ids.hash(lit("embed-select"), lit(PolicyVersion), lit(Seed), index, subjectId, window)

  /** Hash order, after any leading keys. */
  def hashOrder(lead: Column*): Seq[Column] =
    lead ++ Seq(col("rankKey"), col("subjectId"), col("window"))

  /** Adds selectionRank 1..n in `order`. Only for already bounded frames (one global window). */
  def ranked(df: DataFrame, order: Seq[Column]): DataFrame =
    df.withColumn("selectionRank", row_number().over(Window.orderBy(order*)))

  private def withKey(df: DataFrame, index: String) =
    df.withColumn("rankKey", rankKey(lit(index), col("subjectId"), col("window")))

  private val noIds = array().cast(ArrayType(StringType))
  private def idsOf(arr: Column, field: String = "id") =
    coalesce(transform(arr, _.getField(field)), noIds)

  /** Facts fields read for selection and metadata (the rest stays in the facts JSON). */
  private val FactsSchema =
    "researcherId STRING, communityId STRING, topicId STRING, workId STRING, pairId STRING, " +
      "status STRING, pathLength INT, sourceResearcherId STRING, targetResearcherId STRING, " +
      "hops ARRAY<STRUCT<hop: INT, id: STRING, workId: STRING>>, " +
      "topics ARRAY<STRUCT<id: STRING>>, institutions ARRAY<STRUCT<id: STRING>>, " +
      "recentWorks ARRAY<STRUCT<id: STRING>>"
  def parsed(df: DataFrame): DataFrame =
    df.withColumn("f", from_json(col("facts"), FactsSchema, Map.empty[String, String]))

  /** entityIds, topicIds, institutionIds, communityId from the parsed facts (SELECTION.md table).
    */
  def metadataLists(df: DataFrame): DataFrame =
    val f = col("f")
    val doc = col("documentType")
    val foundPath = f.getField("status") === ShortestPaths.Found && f.getField("pathLength") > 0
    df.withColumn(
      "entityIds",
      when(doc === Summaries.ProfileDoc, array(f.getField("researcherId")))
        .when(doc === Summaries.CommunityDoc, array(f.getField("communityId")))
        .when(doc === Summaries.TopicDoc, array(f.getField("topicId")))
        .when(doc === PaperDoc, array(f.getField("workId")))
        .when(
          foundPath,
          concat(
            array(f.getField("pairId"), f.getField("sourceResearcherId")),
            idsOf(f.getField("hops"))
          )
        )
        .otherwise(
          array(
            f.getField("pairId"),
            f.getField("sourceResearcherId"),
            f.getField("targetResearcherId")
          )
        )
    ).withColumn(
      "topicIds",
      when(doc === Summaries.TopicDoc, array(f.getField("topicId")))
        .when(doc === Summaries.PathDoc, noIds)
        .otherwise(idsOf(f.getField("topics")))
    ).withColumn(
      "institutionIds",
      when(doc === Summaries.ProfileDoc, idsOf(f.getField("institutions"))).otherwise(noIds)
    ).withColumn(
      "communityId",
      when(doc.isin(Summaries.ProfileDoc, Summaries.CommunityDoc), f.getField("communityId"))
    )

  /** The selected rows per index, each with `selectionRank`. Inputs:
    *   - summaries: Phase 6 semantic-summaries (documentType, subjectId, window, text, textHash,
    *     templateVersion, facts, summaryId, provenance)
    *   - pathSummaries: the work order 6 path run's collaboration-path summaries (same columns)
    *   - topIds: researcherId of every all-years ranking entry
    *   - papers: the paper rows of `paperCandidates` (workId, subjectId, window, rankKey, eligible
    *     works only)
    * Returns (paths, profiles, communities, topics, papers), each with the summary columns plus
    * selectionRank (papers: workId, selectionRank).
    */
  final case class Selected(
      paths: DataFrame,
      profiles: DataFrame,
      communities: DataFrame,
      topics: DataFrame,
      papers: DataFrame
  )

  def select(
      summaries: DataFrame,
      pathSummaries: DataFrame,
      topIds: DataFrame,
      eligibleWorks: DataFrame,
      windowOrder: Seq[String],
      index: String => String
  ): Selected =
    val wIdx = windowOrder.zipWithIndex.foldLeft(lit(windowOrder.size)) { case (c, (w, i)) =>
      when(col("window") === w, i).otherwise(c)
    }
    def doc(d: String) = parsed(summaries.where(col("documentType") === d))

    // Paths: every path summary, hash order.
    val paths = ranked(withKey(parsed(pathSummaries), index(Summaries.PathDoc)), hashOrder())
      .persist()

    // Profiles: tier 1 path researchers, tier 2 ranked researchers, tier 3 the rest; 5,000.
    val pathPeople = paths
      .select(
        explode(
          concat(
            array(col("f.sourceResearcherId"), col("f.targetResearcherId")),
            idsOf(col("f.hops"))
          )
        ).as("subjectId")
      )
      .distinct()
      .withColumn("t1", lit(true))
    val ranked2 =
      topIds.select(col("researcherId").as("subjectId")).distinct().withColumn("t2", lit(true))
    val profiles = ranked(
      withKey(doc(Summaries.ProfileDoc), index(Summaries.ProfileDoc))
        .join(broadcast(pathPeople), Seq("subjectId"), "left")
        .join(broadcast(ranked2), Seq("subjectId"), "left")
        .withColumn("tier", when(col("t1"), 1).when(col("t2"), 2).otherwise(3))
        .drop("t1", "t2")
        .orderBy(hashOrder(col("tier"))*)
        .limit(ProfileQuota),
      hashOrder(col("tier"))
    ).persist()

    // Communities: per-window quotas; tier 1 = all-years communities of tier 1 and 2 profiles.
    val firstWindow = windowOrder.head
    val tierOne = profiles
      .where(col("tier") <= 2)
      .select(
        col("f.communityId").as("subjectId"),
        lit(firstWindow).as("window"),
        lit(true).as("t1")
      )
      .where(col("subjectId").isNotNull)
      .distinct()
    val quota = CommunityQuotas.foldLeft(lit(0)) { case (c, (w, q)) =>
      when(col("window") === w, q).otherwise(c)
    }
    val communities = ranked(
      withKey(doc(Summaries.CommunityDoc), index(Summaries.CommunityDoc))
        .join(broadcast(tierOne), Seq("subjectId", "window"), "left")
        .withColumn("tier", when(col("t1"), 1).otherwise(2))
        .drop("t1")
        .withColumn(
          "inWindow",
          row_number().over(Window.partitionBy("window").orderBy(hashOrder(col("tier"))*))
        )
        .where(col("inWindow") <= quota)
        .withColumn("windowIndex", wIdx),
      Seq(col("windowIndex"), col("inWindow"))
    )

    // Topics: every topic summary; window order, then hash order.
    val topics = ranked(
      withKey(doc(Summaries.TopicDoc), index(Summaries.TopicDoc)).withColumn("windowIndex", wIdx),
      hashOrder(col("windowIndex"))
    )

    // Papers: tier 1 hop works of FOUND paths, tier 2 recent works of profiles, tier 3 hash order.
    val paperIndex = index(PaperDoc)
    val t1 = paths
      .where(col("f.status") === ShortestPaths.Found)
      .select(col("selectionRank").as("k1"), explode(col("f.hops")).as("h"))
      .select(lit(1).as("tier"), col("k1"), col("h.hop").as("k2"), col("h.workId").as("workId"))
    val t2 = profiles
      .select(
        lit(2).as("tier"),
        col("selectionRank").as("k1"),
        posexplode(coalesce(col("f.recentWorks"), array().cast("array<struct<id:string>>")))
          .as(Seq("k2", "w"))
      )
      .select(col("tier"), col("k1"), col("k2"), col("w.id").as("workId"))
    val t3 = eligibleWorks
      .select(lit(3).as("tier"), lit(0).as("k1"), lit(0).as("k2"), col("workId"))
    val order = Seq(col("tier"), col("k1"), col("k2"), col("rankKey"), col("workId"))
    val papers = ranked(
      t1.union(t2)
        .union(t3)
        .join(eligibleWorks.select("workId"), "workId") // ineligible works are skipped
        .withColumn(
          "rankKey",
          when(col("tier") === 3, rankKey(lit(paperIndex), col("workId"), lit(firstWindow)))
            .otherwise(lit(""))
        )
        .withColumn("first", row_number().over(Window.partitionBy("workId").orderBy(order*)))
        .where(col("first") === 1) // a work already taken is skipped
        .orderBy(order*)
        .limit(PaperQuota),
      order
    ).select("workId", "selectionRank", "tier")
    Selected(paths, profiles, communities, topics, papers)
