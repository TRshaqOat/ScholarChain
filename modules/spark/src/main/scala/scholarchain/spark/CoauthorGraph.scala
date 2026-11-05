package scholarchain.spark

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import scholarchain.core.config.TimeWindow

/** Spark job 2: the temporal weighted coauthorship graph.
  *
  * Hadoop pair candidates (mention pairs of one DBLP work) are mapped onto canonical researcher IDs
  * and canonical work IDs. A pair whose two mentions resolve to one researcher is a self-pair
  * created by identity resolution: counted and written to `self-pairs`, never an edge. The rest are
  * put in canonical order (researcherA < researcherB, CanonicalPair) and aggregated per window
  * (inclusive years; a pair belongs to every window containing its year): jointPublicationCount =
  * distinct canonical works, first/lastCollaborationYear = min/max year, representativeWorkIds =
  * every supporting canonical work ID, sorted (the complete list, so no pointer is needed). One
  * shuffle: the group-by on (window, researcherA, researcherB).
  */
object CoauthorGraph:
  final case class Output(
      edges: DataFrame,
      selfPairs: DataFrame,
      outsideWindows: DataFrame,
      violations: DataFrame
  )

  def windows(spark: SparkSession, ws: Seq[TimeWindow]): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(ws.map(w => Row(w.name, w.startYear, w.endYear))*),
      StructType(
        Seq(
          StructField("window", StringType, false),
          StructField("windowStartYear", IntegerType, false),
          StructField("windowEndYear", IntegerType, false)
        )
      )
    )

  def build(
      ctx: Ctx,
      spark: SparkSession,
      pairs: DataFrame,
      mentionMap: DataFrame,
      workMap: DataFrame,
      ws: Seq[TimeWindow]
  ): Output =
    val r = mentionMap.select("mentionId", "researcherId")
    val p = pairs
      .select("authorA", "authorB", "workId", "year")
      .join(
        r.select(col("mentionId").as("authorA"), col("researcherId").as("ra")),
        Seq("authorA"),
        "left"
      )
      .join(
        r.select(col("mentionId").as("authorB"), col("researcherId").as("rb")),
        Seq("authorB"),
        "left"
      )
      .join(
        workMap.select(col("sourceWorkId").as("workId"), col("workId").as("canonicalWorkId")),
        Seq("workId"),
        "left"
      )
    val violations = Violations(
      "PAIR_ENDPOINT_MISSING",
      p.where(col("ra").isNull || col("rb").isNull),
      concat_ws("|", col("authorA"), col("authorB")),
      col("workId"),
      lit("pair candidate names a mention that is not in author-mentions; pair not used")
    ).union(
      Violations(
        "PAIR_WORK_MISSING",
        p.where(col("canonicalWorkId").isNull),
        concat_ws("|", col("authorA"), col("authorB")),
        col("workId"),
        lit("pair candidate names a work that is not in works; pair not used")
      )
    )
    val ok = p.where(col("ra").isNotNull && col("rb").isNotNull && col("canonicalWorkId").isNotNull)
    val selfPairs = ok
      .where(col("ra") === col("rb"))
      .select(
        col("authorA"),
        col("authorB"),
        col("workId"),
        col("year"),
        col("ra").as("researcherId"),
        ctx
          .provenance(concat_ws("|", col("authorA"), col("authorB")), ctx.canonicalPolicy)
          .as("provenance")
      )
    val canon = ok
      .where(col("ra") =!= col("rb"))
      .select(
        least(col("ra"), col("rb")).as("researcherA"),
        greatest(col("ra"), col("rb")).as("researcherB"),
        col("canonicalWorkId"),
        col("year")
      )
    val w = broadcast(windows(spark, ws))
    val inWindow = col("year").between(col("windowStartYear"), col("windowEndYear"))
    val outside = canon.join(w, inWindow, "left_anti")
    val edges = canon
      .join(w, inWindow)
      .groupBy("window", "windowStartYear", "windowEndYear", "researcherA", "researcherB")
      .agg(
        countDistinct("canonicalWorkId").as("jointPublicationCount"),
        min("year").as("firstCollaborationYear"),
        max("year").as("lastCollaborationYear"),
        array_sort(collect_set("canonicalWorkId")).as("representativeWorkIds")
      )
      .withColumn(
        "edgeId",
        Ids.coauthorEdgeId(
          col("researcherA"),
          col("researcherB"),
          col("windowStartYear"),
          col("windowEndYear"),
          ctx.graphPolicy
        )
      )
      .select(
        col("edgeId"),
        col("researcherA"),
        col("researcherB"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("jointPublicationCount"),
        col("firstCollaborationYear"),
        col("lastCollaborationYear"),
        col("representativeWorkIds"),
        ctx.provenance(col("edgeId"), ctx.graphPolicy).as("provenance")
      )
    Output(edges, selfPairs, outside, violations)

  /** Coauthor edges whose endpoints are not canonical researchers. */
  def integrity(edges: DataFrame, researchers: DataFrame): DataFrame =
    val ids = researchers.select("researcherId")
    Violations(
      "COAUTHOR_EDGE_ENDPOINT_MISSING",
      edges
        .join(ids.withColumnRenamed("researcherId", "researcherA"), Seq("researcherA"), "left_anti")
        .select(edges.columns.toSeq.map(col)*)
        .union(
          edges
            .join(
              ids.withColumnRenamed("researcherId", "researcherB"),
              Seq("researcherB"),
              "left_anti"
            )
            .select(edges.columns.toSeq.map(col)*)
        ),
      col("edgeId"),
      concat_ws("|", col("researcherA"), col("researcherB")),
      lit("coauthor edge endpoint is not in researchers")
    )
