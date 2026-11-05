package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

import scala.collection.mutable

/** Spark job 8: k-core coreness per window by iterative peeling, as DataFrame iteration (D-10).
  *
  * Definition: the k-core is the largest subgraph in which every node has degree >= k; the coreness
  * of a node is the largest k whose k-core contains it (simple graph: edge weights are ignored; an
  * isolated node has coreness 0).
  *
  * Peeling: keep the residual degree of every unpeeled node. Each round takes k = max(k, the
  * smallest residual degree), peels every unpeeled node with residual degree <= k, gives it
  * coreness k, removes its edges, and recomputes residual degrees. k never decreases, and it only
  * rises when no unpeeled node has residual degree <= k, so every node peeled at level k is in the
  * k-core and not in the (k+1)-core. Jumping k to the smallest residual degree skips empty levels.
  * All windows run together (key (window, node)); k is shared, which is still correct because a
  * window with no node at or below k peels nothing in that round.
  *
  * Parameters: maxRoundsPerLevel (analytics.kcore-max-iterations; the run fails if one level needs
  * more rounds). State and remaining edges are reliable checkpoints every round. Shuffles per
  * round: two anti-joins of the edges against the peeled set, the residual-degree group-by, and the
  * state join.
  *
  * Limitations: rounds = the sum over coreness levels of the peeling depth at that level, so a
  * graph with many distinct levels (giant author teams form cliques with high coreness) needs many
  * rounds; each round is a pass over the remaining edges. Coreness says nothing about edge weight.
  */
object KCore:

  /** nodes (window, node); edges (window, a, b), undirected. Returns ((window, node, degree,
    * coreness), the rounds as (k, nodes peeled)).
    */
  def apply(
      nodes: DataFrame,
      edges: DataFrame,
      maxRoundsPerLevel: Int
  ): (DataFrame, Seq[(Int, Long)]) =
    var e = MinLabelComponents.symmetric(edges).checkpoint()
    var state = nodes
      .select("window", "node")
      .union(e.select(col("window"), col("src").as("node")))
      .distinct()
      .join(residual(e).withColumnRenamed("residual", "degree"), Seq("window", "node"), "left")
      .select(
        col("window"),
        col("node"),
        coalesce(col("degree"), lit(0L)).as("degree"),
        coalesce(col("degree"), lit(0L)).as("residual"),
        lit(null).cast("int").as("coreness")
      )
      .checkpoint()
    val log = mutable.ArrayBuffer[(Int, Long)]()
    var k = 0
    var roundsAtK = 0
    var smallest = Option(state.where(col("coreness").isNull).agg(min("residual")).head().get(0))
    while smallest.isDefined do
      val m = smallest.get.asInstanceOf[Long].toInt
      if m > k then
        k = m
        roundsAtK = 0
      require(roundsAtK < maxRoundsPerLevel, s"k-core level $k did not finish in $roundsAtK rounds")
      val peel = state
        .where(col("coreness").isNull && col("residual") <= k)
        .select(col("window"), col("node"))
      log += k -> peel.count()
      e = e
        .join(peel.withColumnRenamed("node", "src"), Seq("window", "src"), "left_anti")
        .join(peel.withColumnRenamed("node", "dst"), Seq("window", "dst"), "left_anti")
        .checkpoint()
      state = state
        .join(residual(e).withColumnRenamed("residual", "next"), Seq("window", "node"), "left")
        .select(
          col("window"),
          col("node"),
          col("degree"),
          coalesce(col("next"), lit(0L)).as("residual"),
          when(col("coreness").isNull && col("residual") <= k, lit(k))
            .otherwise(col("coreness"))
            .as("coreness")
        )
        .checkpoint()
      roundsAtK += 1
      smallest = Option(state.where(col("coreness").isNull).agg(min("residual")).head().get(0))
    (state.select("window", "node", "degree", "coreness"), log.toSeq)

  private def residual(e: DataFrame): DataFrame =
    e.groupBy(col("window"), col("src").as("node")).agg(count(lit(1)).as("residual"))
