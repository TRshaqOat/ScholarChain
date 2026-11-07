package scholarchain.spark

import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.sql.{Column, DataFrame}

/** Spark job 10: semantic-context summaries (ruling 12). Every summary is template text over a
  * `facts` struct of computed metrics and source records; no LLM call, no invented value. The
  * `facts` (stored as JSON) are the structured summary inputs; the text is a deterministic
  * rendering of them (`templateVersion`). Row: documentType, subjectId, window columns, text,
  * textHash (StableIds.textHash), summaryId (StableIds.chunkId(subjectId|window, templateVersion,
  * documentType, None, textHash)), facts, supportingIds (every record ID the text names).
  *
  * Lists are top-N (N = 3, members 5) with explicit orderings and ID tie-breaks, so the same input
  * gives the same text. Shuffles: one row_number window and one collect_list group-by per list, and
  * left joins on the subject key.
  */
object Summaries:
  val TemplateVersion = "summary-template-v1"
  val ProfileDoc = "researcher-profile"
  val CommunityDoc = "collaboration-community"
  val PathDoc = "collaboration-path"
  val TopicDoc = "topic-summary"
  private val TopN = 3
  private val TopMembers = 5

  /** Inputs (column lists are the columns used).
    * @param researchers
    *   researcherId, displayName
    * @param metrics
    *   researcher-metrics: window, researcherId, publicationCount, distinctCoauthorCount,
    *   totalCoauthorOccurrences, averageCoauthorsPerPaper, maximumCoauthorsOnOnePaper,
    *   weightedDegree, coreness, componentId, componentSize
    * @param memberships
    *   researcher-communities: window, researcherId, communityId, communitySize, graphDegree,
    *   crossCommunityDegree, communitiesConnected, participationCoefficient
    * @param largest
    *   window, componentId (the window's largest component)
    * @param edges
    *   window, researcherA, researcherB, edgeId, jointPublicationCount
    * @param dblpWorks
    *   window, researcherId, workId, year, title, venueId (DBLP authorship in the window)
    * @param institutions
    *   researcherId, institutionId, name, evidenceCount
    * @param communities
    *   window, windowStartYear, windowEndYear, communityId, communitySize, internalEdges,
    *   crossCommunityEdges
    * @param crossEdges
    *   window, communityA, communityB
    * @param paths
    *   path-evidence rows
    * @param windows
    *   window, windowStartYear, windowEndYear
    */
  final case class In(
      researchers: DataFrame,
      metrics: DataFrame,
      memberships: DataFrame,
      largest: DataFrame,
      edges: DataFrame,
      dblpWorks: DataFrame,
      works: DataFrame,
      venues: DataFrame,
      topics: DataFrame,
      workTopics: DataFrame,
      institutions: DataFrame,
      communities: DataFrame,
      crossEdges: DataFrame,
      paths: DataFrame,
      windows: DataFrame
  )

  /** Per key, the first n rows by `order` as an array of `value` structs, in rank order. */
  def topN(
      df: DataFrame,
      keys: Seq[String],
      order: Seq[Column],
      n: Int,
      value: Column,
      as: String
  ) =
    df.withColumn("_r", row_number().over(Window.partitionBy(keys.map(col)*).orderBy(order*)))
      .where(col("_r") <= n)
      .groupBy(keys.map(col)*)
      .agg(array_sort(collect_list(struct(col("_r"), value.as("v")))).as("_a"))
      .select(keys.map(col) :+ transform(col("_a"), _.getField("v")).as(as)*)

  private val noIds = array().cast(ArrayType(StringType))
  private def ids(arr: Column, field: String = "id") =
    coalesce(transform(arr, _.getField(field)), noIds)

  /** "<label>a; b; c." or null (skipped by concat_ws) when the list is empty or absent. */
  private def sentence(label: String, arr: Column)(item: Column => Column): Column =
    when(
      arr.isNotNull && size(arr) > 0,
      concat(lit(label), array_join(transform(arr, item), "; "), lit("."))
    )
  private def counted(unit: String)(x: Column) =
    concat(x.getField("name"), lit(" ("), x.getField("n").cast("string"), lit(unit + ")"))
  private def nameOr(id: Column) = coalesce(col("displayName"), id)

  /** Adds textHash, summaryId, and facts JSON, in the output order (the job adds provenance). */
  private def finish(df: DataFrame, doc: String): DataFrame =
    df.select(
      lit(doc).as("documentType"),
      col("subjectId"),
      col("window"),
      col("windowStartYear"),
      col("windowEndYear"),
      col("text"),
      concat(lit("sha256:"), sha2(col("text"), 256)).as("textHash"),
      lit(TemplateVersion).as("templateVersion"),
      to_json(col("facts")).as("facts"),
      array_distinct(col("supportingIds")).as("supportingIds")
    ).withColumn(
      "summaryId",
      concat(
        lit("chunk-"),
        Ids.hash(
          lit("chunk"),
          concat_ws("|", col("subjectId"), col("window")),
          lit(TemplateVersion),
          lit(doc),
          lit(""),
          col("textHash")
        )
      )
    )

  /** Venue and topic lists of works grouped by `keys` (rows: keys..., workId, venueId). */
  private def venuesAndTopics(in: In, works: DataFrame, keys: Seq[String]) =
    val byVenue = works
      .where(col("venueId").isNotNull)
      .select((keys :+ "workId" :+ "venueId").map(col)*)
      .distinct()
      .groupBy((keys :+ "venueId").map(col)*)
      .agg(count(lit(1)).as("n"))
      .join(in.venues, "venueId")
    val byTopic = works
      .select((keys :+ "workId").map(col)*)
      .distinct()
      .join(in.workTopics, "workId")
      .groupBy((keys :+ "topicId").map(col)*)
      .agg(count(lit(1)).as("n"))
      .join(in.topics.select("topicId", "name"), "topicId")
    (
      topN(
        byVenue,
        keys,
        Seq(desc("n"), col("name"), col("venueId")),
        TopN,
        struct(col("venueId").as("id"), col("name"), col("n")),
        "venues"
      ),
      topN(
        byTopic,
        keys,
        Seq(desc("n"), col("name"), col("topicId")),
        TopN,
        struct(col("topicId").as("id"), col("name"), col("n")),
        "topics"
      )
    )

  /** Researcher profiles: researchers with at least `minPublications` in `window`. */
  def profiles(in: In, window: String, minPublications: Int): DataFrame =
    val base = in.metrics
      .where(col("window") === window && col("publicationCount") >= minPublications)
      .join(in.memberships.drop("graphDegree"), Seq("window", "researcherId"), "left")
      .join(
        broadcast(in.largest.select(col("window"), col("componentId"), lit(true).as("inLargest"))),
        Seq("window", "componentId"),
        "left"
      )
      .join(in.researchers, Seq("researcherId"), "left")
      .join(in.windows, "window")
    val who = base.select("researcherId")
    val mine = in.dblpWorks.where(col("window") === window).join(who, "researcherId")
    val years =
      mine.groupBy("researcherId").agg(min("year").as("firstYear"), max("year").as("lastYear"))
    val recent = topN(
      mine,
      Seq("researcherId"),
      Seq(desc("year"), col("workId")),
      TopN,
      struct(col("workId").as("id"), col("title"), col("year")),
      "recentWorks"
    )
    val (venues, topics) = venuesAndTopics(in, mine, Seq("researcherId"))
    val e = in.edges.where(col("window") === window)
    val coauthors = topN(
      e.select(
        col("researcherA").as("researcherId"),
        col("researcherB").as("other"),
        col("edgeId"),
        col("jointPublicationCount")
      ).union(
        e.select(
          col("researcherB"),
          col("researcherA"),
          col("edgeId"),
          col("jointPublicationCount")
        )
      ).join(who, "researcherId")
        .join(
          in.researchers
            .select(col("researcherId").as("other"), col("displayName").as("otherName")),
          Seq("other"),
          "left"
        ),
      Seq("researcherId"),
      Seq(desc("jointPublicationCount"), col("otherName"), col("other")),
      TopN,
      struct(
        col("other").as("id"),
        coalesce(col("otherName"), col("other")).as("name"),
        col("jointPublicationCount").as("n"),
        col("edgeId")
      ),
      "coauthors"
    )
    val insts = topN(
      in.institutions.join(who, "researcherId"),
      Seq("researcherId"),
      Seq(desc("evidenceCount"), col("name"), col("institutionId")),
      TopN,
      struct(col("institutionId").as("id"), col("name")),
      "institutions"
    )
    val all = Seq(years, recent, venues, topics, coauthors, insts)
      .foldLeft(base)((d, x) => d.join(x, Seq("researcherId"), "left"))
      .withColumn("inLargestComponent", coalesce(col("inLargest"), lit(false)))
      .withColumn("name", nameOr(col("researcherId")))
    val text = concat_ws(
      " ",
      format_string(
        "%s (%s), %d-%d: %d publications in the DBLP subset, first in %d and last in %d.",
        col("name"),
        col("researcherId"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("publicationCount"),
        col("firstYear"),
        col("lastYear")
      ),
      format_string(
        "Distinct coauthors (C_r) %d; total coauthor occurrences (T_r) %d; average coauthors per paper (A_r) %.2f; most coauthors on one paper (M_r) %d; weighted degree (W_r) %.0f.",
        col("distinctCoauthorCount"),
        col("totalCoauthorOccurrences"),
        col("averageCoauthorsPerPaper"),
        col("maximumCoauthorsOnOnePaper"),
        col("weightedDegree")
      ),
      format_string(
        "Coreness %d; connected component of %d researchers%s.",
        col("coreness"),
        col("componentSize"),
        when(col("inLargestComponent"), lit(" (the largest)")).otherwise(lit(""))
      ),
      format_string(
        "Community of %d researchers; %d coauthors in other communities; %d communities connected; participation coefficient %.3f.",
        col("communitySize"),
        col("crossCommunityDegree"),
        col("communitiesConnected"),
        col("participationCoefficient")
      ),
      sentence("Most frequent coauthors: ", col("coauthors"))(counted(" joint works")),
      sentence("Frequent topics: ", col("topics"))(counted(" works")),
      sentence("Frequent venues: ", col("venues"))(counted(" works")),
      sentence("Institutions on record (any year): ", col("institutions"))(_.getField("name")),
      sentence("Recent works: ", col("recentWorks"))(x =>
        concat(
          lit("\""),
          x.getField("title"),
          lit("\" ("),
          x.getField("year").cast("string"),
          lit(")")
        )
      )
    )
    finish(
      all.select(
        col("researcherId").as("subjectId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        text.as("text"),
        struct(
          col("researcherId"),
          col("name").as("displayName"),
          col("publicationCount"),
          col("distinctCoauthorCount"),
          col("totalCoauthorOccurrences"),
          col("averageCoauthorsPerPaper"),
          col("maximumCoauthorsOnOnePaper"),
          col("weightedDegree"),
          col("coreness"),
          col("componentId"),
          col("componentSize"),
          col("inLargestComponent"),
          col("communityId"),
          col("communitySize"),
          col("crossCommunityDegree"),
          col("communitiesConnected"),
          col("participationCoefficient"),
          col("firstYear"),
          col("lastYear"),
          col("coauthors"),
          col("topics"),
          col("venues"),
          col("institutions"),
          col("recentWorks")
        ).as("facts"),
        flatten(
          array(
            array(col("researcherId"), col("componentId"), col("communityId")),
            ids(col("coauthors")),
            ids(col("coauthors"), "edgeId"),
            ids(col("topics")),
            ids(col("venues")),
            ids(col("institutions")),
            ids(col("recentWorks"))
          )
        ).as("supportingIds")
      ),
      ProfileDoc
    )

  /** Community summaries: communities with at least `minSize` members, every window. */
  def communities(in: In, minSize: Int): DataFrame =
    val keys = Seq("window", "communityId")
    val cs = in.communities.where(col("communitySize") >= minSize)
    val members = in.memberships
      .select("window", "researcherId", "communityId", "graphDegree")
      .join(cs.select("window", "communityId"), keys)
    val top = topN(
      members.join(in.researchers, Seq("researcherId"), "left"),
      keys,
      Seq(desc("graphDegree"), col("displayName"), col("researcherId")),
      TopMembers,
      struct(
        col("researcherId").as("id"),
        nameOr(col("researcherId")).as("name"),
        col("graphDegree").as("n")
      ),
      "topMembers"
    )
    val works = members.join(in.dblpWorks, Seq("window", "researcherId"))
    val workCount = works.groupBy(keys.map(col)*).agg(countDistinct("workId").as("works"))
    val (venues, topics) = venuesAndTopics(in, works, keys)
    val linked = in.crossEdges
      .select(col("window"), col("communityA").as("communityId"), col("communityB").as("other"))
      .union(in.crossEdges.select(col("window"), col("communityB"), col("communityA")))
      .distinct()
      .groupBy(keys.map(col)*)
      .agg(count(lit(1)).as("linkedCommunities"))
    val all = Seq(top, workCount, venues, topics, linked)
      .foldLeft(cs)((d, x) => d.join(x, keys, "left"))
      .withColumn("linkedCommunities", coalesce(col("linkedCommunities"), lit(0L)))
      .withColumn("works", coalesce(col("works"), lit(0L)))
      .withColumn(
        "density",
        col("internalEdges") / (col("communitySize") * (col("communitySize") - 1) / 2)
      )
    val text = concat_ws(
      " ",
      format_string(
        "Collaboration community %s, %d-%d: %d researchers with %d coauthor edges inside (density %.3f) and %d edges to %d other communities; its members have %d publications in the window.",
        col("communityId"),
        col("windowStartYear"),
        col("windowEndYear"),
        col("communitySize"),
        col("internalEdges"),
        col("density"),
        col("crossCommunityEdges"),
        col("linkedCommunities"),
        col("works")
      ),
      sentence("Most connected members: ", col("topMembers"))(counted(" coauthors")),
      sentence("Frequent topics: ", col("topics"))(counted(" works")),
      sentence("Frequent venues: ", col("venues"))(counted(" works"))
    )
    finish(
      all.select(
        col("communityId").as("subjectId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        text.as("text"),
        struct(
          col("communityId"),
          col("communitySize"),
          col("internalEdges"),
          col("crossCommunityEdges"),
          col("density"),
          col("linkedCommunities"),
          col("works"),
          col("topMembers"),
          col("topics"),
          col("venues")
        ).as("facts"),
        flatten(
          array(
            array(col("communityId")),
            ids(col("topMembers")),
            ids(col("topics")),
            ids(col("venues"))
          )
        ).as("supportingIds")
      ),
      CommunityDoc
    )

  /** Path summaries: one per path-evidence row (the selected pairs). */
  def paths(in: In): DataFrame = paths(in.researchers, in.works, in.paths)

  /** Path summaries from researchers (researcherId, displayName), works (workId, title), and
    * path-evidence rows. The facts carry `sourceName` and `targetName` (display name, or the ID
    * when the name is null), which rendering-v1 needs (modules/rag/SELECTION.md).
    */
  def paths(researchers: DataFrame, works: DataFrame, pathRows: DataFrame): DataFrame =
    val names = researchers.select("researcherId", "displayName")
    val hops = pathRows
      .select(col("pairId"), explode(col("hops")).as("h"))
      .select(
        col("pairId"),
        col("h.hop").as("hop"),
        col("h.toResearcherId").as("researcherId"),
        col("h.edgeId").as("edgeId"),
        col("h.jointPublicationCount").as("n"),
        col("h.firstCollaborationYear").as("first"),
        col("h.lastCollaborationYear").as("last"),
        element_at(col("h.supportingWorkIds"), 1).as("workId")
      )
      .join(names, Seq("researcherId"), "left")
      .join(works.select("workId", "title"), Seq("workId"), "left")
      .groupBy("pairId")
      .agg(
        array_sort(
          collect_list(
            struct(
              col("hop"),
              col("researcherId").as("id"),
              nameOr(col("researcherId")).as("name"),
              col("edgeId"),
              col("n"),
              col("first"),
              col("last"),
              col("workId"),
              coalesce(col("title"), col("workId")).as("title")
            )
          )
        ).as("hopList")
      )
    def nameOf(c: String, as: String) =
      names.select(col("researcherId").as(c), col("displayName").as(as))
    val all = pathRows
      .drop("hops")
      .join(hops, Seq("pairId"), "left")
      .join(nameOf("sourceResearcherId", "sourceName"), Seq("sourceResearcherId"), "left")
      .join(nameOf("targetResearcherId", "targetName"), Seq("targetResearcherId"), "left")
      .withColumn("source", coalesce(col("sourceName"), col("sourceResearcherId")))
      .withColumn("target", coalesce(col("targetName"), col("targetResearcherId")))
    val lead = format_string(
      "In %d-%d, %s and %s",
      col("windowStartYear"),
      col("windowEndYear"),
      col("source"),
      col("target")
    )
    val route = array_join(
      transform(
        col("hopList"),
        h =>
          format_string(
            " -> %s (%d joint works, %d-%d, e.g. \"%s\")",
            h.getField("name"),
            h.getField("n"),
            h.getField("first"),
            h.getField("last"),
            h.getField("title")
          )
      ),
      ""
    )
    val text = when(
      col("status") === ShortestPaths.Found && col("pathLength") > 0,
      concat(
        lead,
        format_string(
          " are joined by a shortest coauthor path of %d hops: %s",
          col("pathLength"),
          col("source")
        ),
        route,
        lit(".")
      )
    ).when(col("status") === ShortestPaths.Found, concat(lead, lit(" are the same researcher.")))
      .when(
        col("status") === ShortestPaths.BeyondDepth,
        concat(
          lead,
          format_string(
            " are in the same connected component, but no coauthor path of at most %d hops joins them.",
            col("maxDepth")
          )
        )
      )
      .when(
        col("status") === ShortestPaths.Unreachable,
        concat(lead, lit(" are in different connected components; no coauthor path joins them."))
      )
      .otherwise(
        concat(
          lead,
          lit(
            ": at least one of them has no DBLP publication in the window, so no path was searched."
          )
        )
      )
    finish(
      all.select(
        col("pairId").as("subjectId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        text.as("text"),
        struct(
          col("pairId"),
          col("status"),
          col("pathLength"),
          col("maxDepth"),
          col("componentId"),
          col("sourceResearcherId"),
          col("targetResearcherId"),
          col("source").as("sourceName"),
          col("target").as("targetName"),
          col("hopList").as("hops")
        ).as("facts"),
        flatten(
          array(
            array(col("pairId"), col("sourceResearcherId"), col("targetResearcherId")),
            ids(col("hopList"), "edgeId"),
            ids(col("hopList"), "workId")
          )
        ).as("supportingIds")
      ),
      PathDoc
    )

  /** Topic summaries: every topic with at least one DBLP-authored work in the window, per window.
    */
  def topics(in: In): DataFrame =
    val keys = Seq("window", "topicId")
    val tw = in.workTopics.join(in.dblpWorks, "workId")
    val totals = tw
      .groupBy(keys.map(col)*)
      .agg(countDistinct("workId").as("works"), countDistinct("researcherId").as("researchers"))
    val top = topN(
      tw.groupBy("window", "topicId", "researcherId")
        .agg(countDistinct("workId").as("n"))
        .join(in.researchers, Seq("researcherId"), "left"),
      keys,
      Seq(desc("n"), col("displayName"), col("researcherId")),
      TopN,
      struct(col("researcherId").as("id"), nameOr(col("researcherId")).as("name"), col("n")),
      "topResearchers"
    )
    val venues = topN(
      tw.where(col("venueId").isNotNull)
        .select("window", "topicId", "workId", "venueId")
        .distinct()
        .groupBy("window", "topicId", "venueId")
        .agg(count(lit(1)).as("n"))
        .join(in.venues, "venueId"),
      keys,
      Seq(desc("n"), col("name"), col("venueId")),
      TopN,
      struct(col("venueId").as("id"), col("name"), col("n")),
      "venues"
    )
    val all = totals
      .join(top, keys, "left")
      .join(venues, keys, "left")
      .join(in.topics, "topicId")
      .join(in.windows, "window")
    val text = concat_ws(
      " ",
      format_string(
        "Topic %s (%s; field %s, subfield %s), %d-%d: %d works by %d researchers in the DBLP subset.",
        col("name"),
        col("topicId"),
        coalesce(col("field"), lit("unknown")),
        coalesce(col("subfield"), lit("unknown")),
        col("windowStartYear"),
        col("windowEndYear"),
        col("works"),
        col("researchers")
      ),
      sentence("Most active researchers: ", col("topResearchers"))(counted(" works")),
      sentence("Frequent venues: ", col("venues"))(counted(" works"))
    )
    finish(
      all.select(
        col("topicId").as("subjectId"),
        col("window"),
        col("windowStartYear"),
        col("windowEndYear"),
        text.as("text"),
        struct(
          col("topicId"),
          col("name"),
          col("field"),
          col("subfield"),
          col("works"),
          col("researchers"),
          col("topResearchers"),
          col("venues")
        ).as("facts"),
        flatten(array(array(col("topicId")), ids(col("topResearchers")), ids(col("venues"))))
          .as("supportingIds")
      ),
      TopicDoc
    )
