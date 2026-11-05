package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*

import scala.collection.mutable

/** Spark job 5: bounded shortest paths for selected researcher pairs, and a sampled estimate of the
  * average path length and effective diameter, by breadth-first search over DataFrames (D-10). The
  * graph is never collected: each BFS round is a join of the frontier with the edges.
  *
  * BFS (multi-source, all windows together, key (window, source, node)): round d+1 sends every
  * frontier node at distance d to its neighbours, drops nodes the source already reached, and keeps
  * for each newly reached node the smallest neighbour at distance d as its parent. The path read
  * back through parents is therefore one shortest path, chosen deterministically (at every step
  * back the smallest possible predecessor). A source stops expanding once all its targets are
  * reached. Visited and frontier are reliable checkpoints every round. Shuffles per round: the
  * frontier-edge join on (window, node), the anti-join against visited, and the min-parent group-by
  * on (window, source, node).
  */
object ShortestPaths:
  val Found = "FOUND"
  val Unreachable = "UNREACHABLE"
  val BeyondDepth = "BEYOND_DEPTH"
  val UnknownResearcher = "UNKNOWN_RESEARCHER"

  /** sources (window, source); symmetric edges (window, src, dst); targets (window, source, target)
    * or None (expand every source until maxDepth or until nothing new is reached). Returns (visited
    * (window, source, node, dist, parent), nodes newly reached per round).
    */
  def bfs(
      sources: DataFrame,
      sym: DataFrame,
      maxDepth: Int,
      targets: Option[DataFrame]
  ): (DataFrame, Seq[Long]) =
    var visited = sources
      .select(
        col("window"),
        col("source"),
        col("source").as("node"),
        lit(0).as("dist"),
        lit(null).cast("string").as("parent")
      )
      .distinct()
      .checkpoint()
    var frontier = visited
    val reached = mutable.ArrayBuffer[Long]()
    while reached.size < maxDepth && reached.lastOption.forall(_ > 0) do
      val d = reached.size
      val active = targets.fold(frontier) { t =>
        val pending = t
          .join(
            visited.select(col("window"), col("source"), col("node").as("target")),
            Seq("window", "source", "target"),
            "left_anti"
          )
          .select("window", "source")
          .distinct()
        frontier.join(broadcast(pending), Seq("window", "source"), "left_semi")
      }
      val next = active
        .select(col("window"), col("source"), col("node").as("src"))
        .join(sym, Seq("window", "src"))
        .select(col("window"), col("source"), col("dst").as("node"), col("src").as("parent"))
        .join(visited, Seq("window", "source", "node"), "left_anti")
        .groupBy("window", "source", "node")
        .agg(min("parent").as("parent"))
        .select(col("window"), col("source"), col("node"), lit(d + 1).as("dist"), col("parent"))
        .checkpoint()
      reached += next.count()
      visited = visited.union(next).checkpoint()
      frontier = next
    (visited, reached.toSeq)

  /** Bounded paths for pairs (pairId, window, source, target). membership (window, node,
    * componentId) holds every node of every window; edges (window, researcherA, researcherB,
    * edgeId, jointPublicationCount, firstCollaborationYear, lastCollaborationYear,
    * representativeWorkIds) are canonical coauthor edges. Only pairs whose two ends are in one
    * component are searched. Status: FOUND (pathLength, researcherIds in order from source to
    * target, and one hop per edge with its supporting work IDs), UNREACHABLE (different
    * components), BEYOND_DEPTH (one component, no path of at most maxDepth hops),
    * UNKNOWN_RESEARCHER (an end is not a node of the window). Returns (one row per pair, BFS nodes
    * reached per round).
    */
  def paths(
      pairs: DataFrame,
      membership: DataFrame,
      edges: DataFrame,
      maxDepth: Int
  ): (DataFrame, Seq[Long]) =
    def comp(end: String) =
      membership.select(col("window"), col("node").as(end), col("componentId").as(s"${end}Comp"))
    val classified = pairs
      .select("pairId", "window", "source", "target")
      .join(comp("source"), Seq("window", "source"), "left")
      .join(comp("target"), Seq("window", "target"), "left")
      .withColumn(
        "status",
        when(col("sourceComp").isNull || col("targetComp").isNull, lit(UnknownResearcher))
          .when(col("sourceComp") =!= col("targetComp"), lit(Unreachable))
      )
      .persist()
    val searched = classified.where(col("status").isNull).select("window", "source", "target")
    val sym = MinLabelComponents.symmetric(
      edges.select(col("window"), col("researcherA").as("a"), col("researcherB").as("b"))
    )
    val (visited, reached) = bfs(searched, sym, maxDepth, Some(searched))
    val found = searched
      .join(
        visited.select(col("window"), col("source"), col("node").as("target"), col("dist")),
        Seq("window", "source", "target")
      )
      .select(
        col("window"),
        col("source"),
        col("target"),
        col("dist"),
        col("target").as("cur"),
        array(col("target")).as("researcherIds")
      )
    // Walk back through parents, one hop per round (the source's parent is null). Bounded: at most
    // one row per pair, so the walk side is broadcast and the visited table is never shuffled.
    // Inner join is exact: every cur (the target, then parents) is a visited node.
    val back = visited.select(
      col("window"),
      col("source"),
      col("node").as("cur"),
      col("parent")
    )
    var walk = found
    for _ <- 1 to maxDepth do
      walk = back
        .join(broadcast(walk), Seq("window", "source", "cur"))
        .select(
          col("window"),
          col("source"),
          col("target"),
          col("dist"),
          coalesce(col("parent"), col("cur")).as("cur"),
          when(col("parent").isNotNull, concat(array(col("parent")), col("researcherIds")))
            .otherwise(col("researcherIds"))
            .as("researcherIds")
        )
    val e = edges.select(
      col("window"),
      col("researcherA"),
      col("researcherB"),
      col("edgeId"),
      col("jointPublicationCount"),
      col("firstCollaborationYear"),
      col("lastCollaborationYear"),
      col("representativeWorkIds").as("supportingWorkIds")
    )
    walk = walk.persist()
    val ids = col("researcherIds")
    val hops = walk
      .select(
        col("window"),
        col("source"),
        col("target"),
        ids,
        posexplode(slice(ids, lit(1), size(ids) - 1)).as(Seq("pos", "fromResearcherId"))
      )
      .select(
        col("window"),
        col("source"),
        col("target"),
        (col("pos") + 1).as("hop"),
        col("fromResearcherId"),
        element_at(ids, col("pos") + 2).as("toResearcherId")
      )
      .withColumn("researcherA", least(col("fromResearcherId"), col("toResearcherId")))
      .withColumn("researcherB", greatest(col("fromResearcherId"), col("toResearcherId")))
      .join(e, Seq("window", "researcherA", "researcherB"), "left")
      .groupBy("window", "source", "target")
      .agg(
        array_sort(
          collect_list(
            struct(
              col("hop"),
              col("fromResearcherId"),
              col("toResearcherId"),
              col("edgeId"),
              col("jointPublicationCount"),
              col("firstCollaborationYear"),
              col("lastCollaborationYear"),
              col("supportingWorkIds")
            )
          )
        ).as("hops")
      )
    val result = classified
      .join(walk.drop("cur"), Seq("window", "source", "target"), "left")
      .join(hops, Seq("window", "source", "target"), "left")
      .select(
        col("pairId"),
        col("window"),
        col("source").as("sourceResearcherId"),
        col("target").as("targetResearcherId"),
        coalesce(
          col("status"),
          when(col("dist").isNotNull, lit(Found)).otherwise(lit(BeyondDepth))
        ).as("status"),
        col("dist").as("pathLength"),
        lit(maxDepth).as("maxDepth"),
        when(col("sourceComp") === col("targetComp"), col("sourceComp")).as("componentId"),
        col("researcherIds"),
        when(
          col("dist").isNotNull,
          coalesce(col("hops"), array().cast(hops.schema("hops").dataType))
        )
          .as("hops")
      )
    (result, reached)

  /** Exact BFS distances from sampled sources (window, source) to every node they reach, expanded
    * until nothing new is reached; fails if that takes more than maxRounds rounds. Returns
    * ((window, source, node, dist) without the source itself, nodes reached per round).
    */
  def sampledDistances(
      sources: DataFrame,
      sym: DataFrame,
      maxRounds: Int
  ): (DataFrame, Seq[Long]) =
    val (visited, reached) = bfs(sources, sym, maxRounds, None)
    require(
      reached.lastOption.contains(0L),
      s"sampled BFS did not finish in $maxRounds rounds"
    )
    (visited.where(col("dist") > 0).select("window", "source", "node", "dist"), reached)

  /** Per window, from sampled distances: reachable pairs, mean distance and its standard error over
    * sources, median, effective diameter (the smallest d with at least 90% of the sampled reachable
    * pairs within d hops), the largest observed distance, and the distance histogram.
    */
  def estimate(distances: DataFrame): DataFrame =
    val hist = distances.groupBy("window", "dist").agg(count(lit(1)).as("pairs"))
    val cum = hist
      .withColumn(
        "cumFraction",
        sum("pairs").over(Window.partitionBy("window").orderBy("dist")) /
          sum("pairs").over(Window.partitionBy("window"))
      )
      .groupBy("window")
      .agg(
        sum("pairs").as("reachablePairs"),
        (sum(col("dist") * col("pairs")) / sum("pairs")).as("meanDistance"),
        min(when(col("cumFraction") >= 0.5, col("dist"))).as("medianDistance"),
        min(when(col("cumFraction") >= 0.9, col("dist"))).as("effectiveDiameter90"),
        max("dist").as("maxObservedDistance"),
        array_sort(collect_list(struct(col("dist").as("distance"), col("pairs"))))
          .as("distanceHistogram")
      )
    val perSource = distances
      .groupBy("window", "source")
      .agg(avg("dist").as("m"))
      .groupBy("window")
      .agg(
        (stddev_samp("m") / sqrt(count(lit(1)))).as("meanDistanceStdError"),
        array_sort(collect_list("source")).as("sourceResearcherIds")
      )
    cum.join(perSource, "window")
