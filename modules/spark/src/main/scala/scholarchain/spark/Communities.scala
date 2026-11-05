package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

import scala.collection.mutable

/** Spark job 6: collaboration communities by synchronous label propagation (Raghavan, Albert, and
  * Kumara 2007) over DataFrames (D-10), all windows together (key (window, node)).
  *
  * Definition: every node starts with its own researcher ID as label. Each round, every node takes
  * the label held by the most of its neighbours and itself (the node's own label is one vote, so a
  * pair or a bipartite pattern cannot swap labels forever); a tie goes to the smallest label
  * (ruling 8). All nodes update at once from the previous round's labels, so the result does not
  * depend on partitioning or row order. The loop stops after a round with no change, or after
  * maxIterations rounds (recorded as not converged; the last labels are kept). Edges are
  * unweighted. A node with no edge keeps its own label (a community of one).
  *
  * Shuffles per round: the edge-label join on (window, src), the vote count group-by on (window,
  * node, label), the winner group-by on (window, node), and the old/new label join. Labels are
  * reliable checkpoints every round.
  */
object LabelPropagation:

  /** nodes (window, node); edges (window, a, b), undirected. Returns ((window, node, label), labels
    * changed per round and window); the loop ran rounds.size rounds.
    */
  def apply(
      nodes: DataFrame,
      edges: DataFrame,
      maxIterations: Int
  ): (DataFrame, Seq[Map[String, Long]]) =
    val sym = MinLabelComponents.symmetric(edges).checkpoint()
    var labels = nodes
      .select("window", "node")
      .union(sym.select(col("window"), col("src").as("node")))
      .distinct()
      .withColumn("label", col("node"))
      .checkpoint()
    val rounds = mutable.ArrayBuffer[Map[String, Long]]()
    while rounds.size < maxIterations && rounds.lastOption.forall(_.values.sum > 0) do
      val votes = sym
        .join(
          labels.select(col("window"), col("node").as("src"), col("label")),
          Seq("window", "src")
        )
        .select(col("window"), col("dst").as("node"), col("label"))
        .union(labels)
        .groupBy("window", "node", "label")
        .agg(count(lit(1)).as("n"))
      // Most votes first, then the smallest label.
      val next = votes
        .groupBy("window", "node")
        .agg(min_by(col("label"), struct(-col("n"), col("label"))).as("label"))
        .checkpoint()
      val changed = next
        .join(labels.withColumnRenamed("label", "old"), Seq("window", "node"))
        .where(col("label") =!= col("old"))
        .groupBy("window")
        .agg(count(lit(1)).as("n"))
        .collect() // at most one row per window
        .map(r => r.getString(0) -> r.getLong(1))
        .toMap
      rounds += changed
      labels = next
    (labels, rounds.toSeq)

/** Spark job 7: the three D-10 bridge measures per researcher and window, from community labels.
  *
  * For researcher r with degree k_r (distinct coauthors in the window) and k_rs coauthors in
  * community s:
  *   - crossCommunityDegree = number of r's coauthors whose community differs from r's;
  *   - communitiesConnected = number of distinct communities among r's coauthors (r's own counts
  *     when a coauthor is in it; 0 for a researcher without coauthors);
  *   - participationCoefficient = 1 - sum over s of (k_rs / k_r)^2 (Guimera and Amaral 2005), in
  *     [0, 1 - 1/communitiesConnected]; 0 when all coauthors share one community, and 0 by
  *     convention for k_r = 0.
  *
  * Edges are unweighted (a coauthor counts once however many joint works). Shuffles: the
  * neighbour-label join on (window, dst), the group-by on (window, node, community), the group-by
  * on (window, node), and the own-label join.
  */
object Bridges:

  /** labels (window, node, label); edges (window, a, b). Returns (window, node, label, degree,
    * crossCommunityDegree, communitiesConnected, participationCoefficient) for every labelled node.
    */
  def apply(labels: DataFrame, edges: DataFrame): DataFrame =
    val perCommunity = MinLabelComponents
      .symmetric(edges)
      .join(
        labels.select(col("window"), col("node").as("dst"), col("label").as("nbr")),
        Seq("window", "dst")
      )
      .groupBy(col("window"), col("src").as("node"), col("nbr"))
      .agg(count(lit(1)).as("k"))
    labels
      .join(perCommunity, Seq("window", "node"), "left")
      .groupBy("window", "node", "label")
      .agg(
        coalesce(sum("k"), lit(0L)).as("degree"),
        coalesce(sum(when(col("nbr") =!= col("label"), col("k"))), lit(0L))
          .as("crossCommunityDegree"),
        count(col("nbr")).as("communitiesConnected"),
        coalesce(sum(col("k") * col("k")), lit(0L)).as("sq")
      )
      .withColumn(
        "participationCoefficient",
        when(col("degree") === 0, lit(0.0))
          .otherwise(lit(1.0) - col("sq") / (col("degree") * col("degree")))
      )
      .drop("sq")
