package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*
import scholarchain.core.config.TimeWindow

/** Spark job 9: temporal comparison of the configured windows.
  *
  * Windows are compared only when they do not overlap (`from` ends before `to` starts), so a
  * researcher's two rows describe disjoint publication sets. With the D-10 windows that is
  * 2000-2014 -> 2015-2026; all-years contains both and is not compared with them. Researchers keep
  * one canonical ID across windows (identity is resolved once, in job 1), so rows join on
  * researcherId. Component and community IDs are per window and are never compared directly:
  * component membership is compared as "in the window's largest component", and community
  * membership as the share of a researcher's earlier community that stays with them.
  *
  * Shuffles: the full outer join of the two windows on researcherId; the edge join on (researcherA,
  * researcherB) for retained coauthors and its group-by; the group-by on (from community, to
  * community) and its join back.
  */
object Temporal:
  val Both = "BOTH"
  val FromOnly = "FROM_ONLY"
  val ToOnly = "TO_ONLY"

  /** Ordered pairs of non-overlapping windows, earlier first. */
  def comparisons(ws: Seq[TimeWindow]): Seq[(TimeWindow, TimeWindow)] =
    for a <- ws; b <- ws if a.endYear < b.startYear yield (a, b)

  /** Per-window values compared, prefixed `from` / `to` in the output. */
  val Measures = Seq(
    "publicationCount",
    "distinctCoauthorCount",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "coreness",
    "componentSize",
    "inLargestComponent",
    "communityId",
    "communitySize",
    "crossCommunityDegree",
    "communitiesConnected",
    "participationCoefficient"
  )
  private val Deltas = Seq(
    "distinctCoauthorCount",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "coreness",
    "crossCommunityDegree",
    "participationCoefficient"
  )

  /** metrics (window, researcherId, publicationCount, distinctCoauthorCount,
    * averageCoauthorsPerPaper, maximumCoauthorsOnOnePaper, coreness, componentId, componentSize);
    * memberships (window, researcherId, communityId, communitySize, crossCommunityDegree,
    * communitiesConnected, participationCoefficient); largest (window, componentId), one per
    * window; edges (window, researcherA, researcherB). Returns (one row per researcher of either
    * window, community transitions (fromCommunityId, toCommunityId, sharedResearchers, sizes)).
    *
    *   - presence: BOTH, FROM_ONLY, TO_ONLY (a researcher is in a window when it has a DBLP
    *     publication there).
    *   - delta<X> = to<X> - from<X> (null unless BOTH).
    *   - retainedCoauthors: coauthors in both windows (null unless BOTH).
    *   - largestComponentTransition: IN / OUT / ABSENT, from -> to.
    *   - crossCommunityTransition: CROSS (crossCommunityDegree > 0) / NO_CROSS / ABSENT, from ->
    *     to.
    *   - communityRetention: (s - 1) / (fromCommunitySize - 1), where s counts the researchers in
    *     both windows that share r's from-community and r's to-community (r included); null unless
    *     BOTH or when the from-community has one member.
    */
  def researcherChanges(
      metrics: DataFrame,
      memberships: DataFrame,
      largest: DataFrame,
      edges: DataFrame,
      from: String,
      to: String
  ): (DataFrame, DataFrame) =
    val per = metrics
      .join(memberships, Seq("window", "researcherId"))
      .join(
        broadcast(largest.select(col("window"), col("componentId"), lit(true).as("inLargest"))),
        Seq("window", "componentId"),
        "left"
      )
      .withColumn("inLargestComponent", coalesce(col("inLargest"), lit(false)))
    def side(w: String, p: String) = per
      .where(col("window") === w)
      .select(col("researcherId") +: Measures.map(m => col(m).as(p + m.capitalize))*)
    def place(p: String, in: String) =
      when(col(s"${p}PublicationCount").isNull, lit("ABSENT")).when(col(s"$p$in"), lit("IN"))
    def cross(p: String) = when(col(s"${p}PublicationCount").isNull, lit("ABSENT"))
      .when(col(s"${p}CrossCommunityDegree") > 0, lit("CROSS"))
      .otherwise(lit("NO_CROSS"))
    def pairs(w: String) = edges.where(col("window") === w).select("researcherA", "researcherB")
    val retained = pairs(from)
      .join(pairs(to), Seq("researcherA", "researcherB"))
      .select(explode(array(col("researcherA"), col("researcherB"))).as("researcherId"))
      .groupBy("researcherId")
      .agg(count(lit(1)).as("retained"))
    val joined = side(from, "from")
      .join(side(to, "to"), Seq("researcherId"), "full")
      .withColumn(
        "presence",
        when(col("fromPublicationCount").isNull, lit(ToOnly))
          .when(col("toPublicationCount").isNull, lit(FromOnly))
          .otherwise(lit(Both))
      )
    val transitions = joined
      .where(col("presence") === Both)
      .groupBy("fromCommunityId", "toCommunityId")
      .agg(
        count(lit(1)).as("sharedResearchers"),
        max("fromCommunitySize").as("fromCommunitySize"),
        max("toCommunitySize").as("toCommunitySize")
      )
    val changes = joined
      .join(retained, Seq("researcherId"), "left")
      .join(
        transitions.select("fromCommunityId", "toCommunityId", "sharedResearchers"),
        Seq("fromCommunityId", "toCommunityId"),
        "left"
      )
      .select(
        (Seq(col("researcherId"), col("presence")) ++
          Measures.flatMap(m => Seq(col("from" + m.capitalize), col("to" + m.capitalize))) ++
          Deltas.map(m =>
            (col("to" + m.capitalize) - col("from" + m.capitalize)).as("delta" + m.capitalize)
          ) ++
          Seq(
            when(col("presence") === Both, coalesce(col("retained"), lit(0L)))
              .as("retainedCoauthors"),
            concat(
              place("from", "InLargestComponent").otherwise(lit("OUT")),
              lit("->"),
              place("to", "InLargestComponent").otherwise(lit("OUT"))
            ).as("largestComponentTransition"),
            concat(cross("from"), lit("->"), cross("to")).as("crossCommunityTransition"),
            when(
              col("presence") === Both && col("fromCommunitySize") > 1,
              (col("sharedResearchers") - 1) / (col("fromCommunitySize") - 1)
            ).as("communityRetention")
          ))*
      )
    (changes, transitions)

  /** One row per comparison: researcher counts by presence, and for researchers in both windows the
    * mean from/to values, the median community retention, and the transition counts.
    */
  def comparison(changes: DataFrame): DataFrame =
    def n(c: org.apache.spark.sql.Column) = sum(when(c, 1L).otherwise(0L))
    val both = col("presence") === Both
    def meanBoth(m: String, p: String) =
      avg(when(both, col(p + m.capitalize))).as(s"${p}Mean${m.capitalize}")
    val cols = Seq(
      n(col("presence") =!= ToOnly).as("researchersFrom"),
      n(col("presence") =!= FromOnly).as("researchersTo"),
      n(both).as("researchersInBoth"),
      n(col("presence") === FromOnly).as("researchersFromOnly"),
      n(col("presence") === ToOnly).as("researchersToOnly"),
      n(both && col("retainedCoauthors") > 0).as("researchersRetainingACoauthor"),
      expr("percentile(communityRetention, 0.5)").as("medianCommunityRetention"),
      avg("communityRetention").as("meanCommunityRetention"),
      n(col("largestComponentTransition") === "IN->IN").as("largestComponentInToIn"),
      n(col("largestComponentTransition") === "IN->OUT").as("largestComponentInToOut"),
      n(col("largestComponentTransition") === "OUT->IN").as("largestComponentOutToIn"),
      n(col("largestComponentTransition") === "OUT->OUT").as("largestComponentOutToOut"),
      n(col("crossCommunityTransition") === "NO_CROSS->CROSS").as("becameCrossCommunity"),
      n(col("crossCommunityTransition") === "CROSS->NO_CROSS").as("stoppedCrossCommunity"),
      n(col("crossCommunityTransition") === "CROSS->CROSS").as("stayedCrossCommunity")
    ) ++ Deltas.flatMap(m => Seq(meanBoth(m, "from"), meanBoth(m, "to")))
    changes.agg(cols.head, cols.tail*)
