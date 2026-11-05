package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

/** Connected components by the alternating large-star / small-star algorithm (Kiveris et al.,
  * "Connected Components in MapReduce and Beyond", SoCC 2014), as DataFrame iteration (D-10).
  *
  * Definition: the edge set is kept as (child, parent) with child > parent. Large-star links every
  * neighbour v > u of each node u to m(u), the minimum of u and its neighbours. Small-star links
  * each node u and its smaller neighbours to the minimum of them. Both keep connectivity and the
  * component minimum. The loop stops when the edges form stars: no node is both a child and a
  * parent, and every child has one parent. The parent is then the component's minimum node.
  *
  * Parameters: maxIterations (the run fails rather than return a partial result). Each round is
  * checkpointed (reliable checkpoint directory), so lineage stays one round deep. Shuffles per
  * round: two group-bys and two joins over the edge set, plus the star check.
  *
  * Limitations: nodes are LONG keys (callers hash string IDs); isolated nodes are not in the output
  * (callers treat a missing node as its own component). Rounds are O(log^2 n), each a full pass
  * over the current edge set; a giant component skews no single task because work is spread by node
  * key, but its hub nodes do produce large groups in the min aggregation.
  */
object ConnectedComponents:

  /** edges (src LONG, dst LONG), any orientation. Returns ((node, root), rounds), one row per node
    * that appears in an edge; root is the smallest node of its component.
    */
  def apply(edges: DataFrame, maxIterations: Int): (DataFrame, Int) =
    var e = oriented(edges.select(col("src").as("a"), col("dst").as("b"))).checkpoint()
    var rounds = 0
    while !isStar(e) do
      require(
        rounds < maxIterations,
        s"connected components did not converge in $maxIterations rounds"
      )
      e = smallStar(largeStar(e)).checkpoint()
      rounds += 1
    val roots = e.select(col("parent").as("node"), col("parent").as("root")).distinct()
    (e.select(col("child").as("node"), col("parent").as("root")).union(roots), rounds)

  /** (a, b) pairs as (child = larger, parent = smaller), without self-loops or duplicates. */
  private def oriented(df: DataFrame): DataFrame =
    df.where(col("a") =!= col("b"))
      .select(greatest(col("a"), col("b")).as("child"), least(col("a"), col("b")).as("parent"))
      .distinct()

  private def largeStar(e: DataFrame): DataFrame =
    val sym = e
      .select(col("child").as("u"), col("parent").as("v"))
      .union(e.select(col("parent").as("u"), col("child").as("v")))
    val m = sym.groupBy("u").agg(least(col("u"), min("v")).as("m"))
    oriented(sym.join(m, "u").where(col("v") > col("u")).select(col("v").as("a"), col("m").as("b")))

  private def smallStar(e: DataFrame): DataFrame =
    val m = e.groupBy(col("child").as("u")).agg(min("parent").as("m"))
    val smaller = e
      .join(m, e("child") === m("u"))
      .select(col("parent").as("a"), col("m").as("b"))
    oriented(smaller.union(m.select(col("u").as("a"), col("m").as("b"))))

  private def isStar(e: DataFrame): Boolean =
    e.groupBy("child").count().where(col("count") > 1).limit(1).count() == 0 &&
      e.select(col("child").as("n"))
        .join(e.select(col("parent").as("n")), "n")
        .limit(1)
        .count() == 0
