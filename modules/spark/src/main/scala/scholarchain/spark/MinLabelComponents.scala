package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

import scala.collection.mutable

/** Spark job 4: connected components of the researcher coauthorship graph, per window, by iterative
  * min-label propagation over DataFrames (D-10).
  *
  * Definition: every node starts with its own ID as its label. In each round every node whose label
  * dropped in the previous round (the frontier; at first every node) sends its label to its
  * neighbours, and every node keeps the smallest label it has seen. The loop stops after a round in
  * which no label changes. The final label (the root) is the smallest node ID of the component. All
  * windows run together: the key is (window, node), so windows never mix.
  *
  * Parameters: maxIterations (the run fails rather than return a partial result). Each round's
  * labels and frontier are reliable checkpoints (the SparkContext checkpoint directory), so the
  * lineage stays one round deep. Shuffles per round: the frontier-edge join on (window, src), the
  * min group-by on (window, node), and the old/new label join on (window, node).
  *
  * Limitations: rounds = the largest distance from any node to its component minimum, plus one
  * confirming round, so a long-diameter component costs more rounds than large-star/small-star. Hub
  * nodes produce large groups in the min aggregation. Isolated nodes keep their own label.
  */
object MinLabelComponents:

  /** nodes (window, node); edges (window, a, b), undirected, any orientation. Returns ((window,
    * node, root), labels changed per round); the last round has 0 changes.
    */
  def apply(nodes: DataFrame, edges: DataFrame, maxIterations: Int): (DataFrame, Seq[Long]) =
    val e = symmetric(edges).checkpoint()
    var labels = nodes
      .select("window", "node")
      .union(e.select(col("window"), col("src").as("node")))
      .distinct()
      .withColumn("label", col("node"))
      .checkpoint()
    var frontier = labels
    val changed = mutable.ArrayBuffer[Long]()
    while changed.lastOption.forall(_ > 0) do
      require(
        changed.size < maxIterations,
        s"min-label components did not converge in $maxIterations rounds"
      )
      val messages = e
        .join(
          frontier.select(col("window"), col("node").as("src"), col("label")),
          Seq("window", "src")
        )
        .select(col("window"), col("dst").as("node"), col("label"))
      val next = labels
        .union(messages)
        .groupBy("window", "node")
        .agg(min("label").as("label"))
        .checkpoint()
      frontier = next
        .join(labels.withColumnRenamed("label", "old"), Seq("window", "node"))
        .where(col("label") < col("old"))
        .select("window", "node", "label")
        .checkpoint()
      changed += frontier.count()
      labels = next
    (labels.withColumnRenamed("label", "root"), changed.toSeq)

  /** (window, src, dst) in both directions, without self-loops or duplicates. */
  def symmetric(edges: DataFrame): DataFrame =
    edges
      .select(col("window"), col("a").as("src"), col("b").as("dst"))
      .union(edges.select(col("window"), col("b").as("src"), col("a").as("dst")))
      .where(col("src") =!= col("dst"))
      .distinct()
