package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*

/** Two-tier identity clustering with refusal rules (phase-06 rulings 4 to 6).
  *
  *   - Only ACCEPTED decisions link records; UNRESOLVED, REJECTED, and CONFLICT never do.
  *   - Tier 1: connected components over EXACT_ID and DETERMINISTIC ACCEPTED edges.
  *   - Tier 2: connected components over all ACCEPTED edges (adds PROBABILISTIC), computed on the
  *     tier-1 components contracted to their roots (the same partition, a smaller graph).
  *   - A tier-2 cluster that breaks a refusal rule is refused and its members fall back to their
  *     tier-1 clusters; a tier-1 cluster that breaks a rule is refused and its members become
  *     singletons. Every rule is monotone (a superset of a breaking cluster breaks it too), so a
  *     kept tier-2 cluster never contains a refused tier-1 cluster.
  *
  * Nodes are the clustered records (members) plus identifier keys that appear only as edge
  * endpoints (`orcid:<iD>` targets of exact-ID matches). Keys link members but are not members.
  * Internal node keys are xxhash64 of the string ID; a collision fails the run.
  */
object Clustering:

  /** Refusal rule codes (ruling 5). */
  object Rule:
    val TwoOrcids = "TWO_VALID_ORCIDS" // 5a
    val Conflict = "CONFLICT_DECISION" // 5b
    val Suffix = "HOMONYM_SUFFIX_DISAGREEMENT" // 5c
    val Group = "IDENTIFIER_CONFLICT_GROUP" // 5d

  /** mapping: one row per member (member, clusterKey, anchor = smallest member ID,
    * clusterMemberCount, assignedTier TIER2|TIER1|SINGLETON, separationReason, tier1Rules,
    * tier2Rules, identityConfidence). refusals: one row per refused cluster and tier.
    */
  final case class Result(mapping: DataFrame, refusals: DataFrame, rounds: Seq[Int])

  /** @param members
    *   (member STRING) the records being clustered
    * @param accepted
    *   (src, dst, tier INT 1|2, confidence DOUBLE) ACCEPTED decisions with known endpoints
    * @param conflicts
    *   (src, dst) CONFLICT decisions
    * @param attrs
    *   (node, orcid, suffix): valid ORCID iD and DBLP homonym number per node, nullable
    * @param groups
    *   (groupId, node): members of J03+J04 conflict groups
    * @param unresolved
    *   (node) records with at least one UNRESOLVED decision
    */
  def twoTier(
      members: DataFrame,
      accepted: DataFrame,
      conflicts: DataFrame,
      attrs: DataFrame,
      groups: DataFrame,
      unresolved: DataFrame,
      maxRounds: Int
  ): Result =
    val nodes = members
      .select(col("member").as("node"), lit(true).as("isMember"))
      .union(accepted.select(col("src").as("node"), lit(false).as("isMember")))
      .union(accepted.select(col("dst").as("node"), lit(false).as("isMember")))
      .groupBy("node")
      .agg(max("isMember").as("isMember"))
      .withColumn("id", xxhash64(col("node")))
    val counts = nodes.agg(count(lit(1)), countDistinct("id")).head()
    require(counts.getLong(0) == counts.getLong(1), "xxhash64 collision among node IDs")

    val e = accepted.select(
      xxhash64(col("src")).as("s"),
      xxhash64(col("dst")).as("d"),
      col("tier"),
      col("confidence")
    )
    val (cc1, rounds1) = ConnectedComponents(
      e.where(col("tier") === 1).select(col("s").as("src"), col("d").as("dst")),
      maxRounds
    )
    val withR1 = nodes
      .join(cc1.select(col("node").as("id"), col("root").as("r1")), Seq("id"), "left")
      .withColumn("r1", coalesce(col("r1"), col("id")))
    val r1Of = withR1.select("id", "r1")
    val e2 = e
      .where(col("tier") === 2)
      .join(r1Of.select(col("id").as("s"), col("r1").as("src")), "s")
      .join(r1Of.select(col("id").as("d"), col("r1").as("dst")), "d")
      .where(col("src") =!= col("dst"))
      .select("src", "dst")
    val (cc2, rounds2) = ConnectedComponents(e2, maxRounds)
    val assigned = withR1
      .join(cc2.select(col("node").as("r1"), col("root").as("r2")), Seq("r1"), "left")
      .withColumn("r2", coalesce(col("r2"), col("r1")))
      .checkpoint()

    val ref1 =
      refused(assigned, "r1", conflicts, attrs, groups)
        .withColumnRenamed("rules", "tier1Rules")
        .persist()
    val ref2 =
      refused(assigned, "r2", conflicts, attrs, groups)
        .withColumnRenamed("rules", "tier2Rules")
        .persist()
    val tierCol = when(col("tier2Rules").isNull, lit("TIER2"))
      .when(col("tier1Rules").isNull, lit("TIER1"))
      .otherwise(lit("SINGLETON"))
    val fin = assigned
      .join(ref1, Seq("r1"), "left")
      .join(ref2, Seq("r2"), "left")
      .withColumn("assignedTier", tierCol)
      .withColumn(
        "clusterKey",
        when(col("assignedTier") === "TIER2", col("r2"))
          .when(col("assignedTier") === "TIER1", col("r1"))
          .otherwise(col("id"))
      )
      .checkpoint()

    val keyOf = fin.select("id", "clusterKey")
    val confidence = e
      .join(keyOf.select(col("id").as("s"), col("clusterKey").as("ks")), "s")
      .join(keyOf.select(col("id").as("d"), col("clusterKey").as("kd")), "d")
      .where(col("ks") === col("kd"))
      .groupBy(col("ks").as("clusterKey"))
      .agg(min("confidence").as("minConfidence"))

    val m = fin
      .where(col("isMember"))
      .select(
        col("node").as("member"),
        col("clusterKey"),
        col("assignedTier"),
        col("tier1Rules"),
        col("tier2Rules")
      )
    val stats =
      m.groupBy("clusterKey")
        .agg(min("member").as("anchor"), count(lit(1)).as("clusterMemberCount"))
    val unres =
      unresolved.select(col("node").as("member")).distinct().withColumn("unres", lit(true))
    val reason = when(col("tier2Rules").isNotNull && col("tier1Rules").isNotNull, "REFUSED_TIER1")
      .when(col("tier2Rules").isNotNull, "REFUSED_TIER2")
      .when(col("clusterMemberCount") === 1 && col("unres").isNotNull, "UNRESOLVED")
      .when(col("clusterMemberCount") === 1, "NO_ACCEPTED_LINK")
    val mapping = m
      .join(stats, "clusterKey")
      .join(confidence, Seq("clusterKey"), "left")
      .join(unres, Seq("member"), "left")
      .withColumn("separationReason", reason)
      // Reported tier: TIER2 = the member's tier-2 cluster was kept (it may hold only tier-1
      // edges), TIER1 = it fell back to its tier-1 cluster, SINGLETON = it shares its cluster
      // with no other member (the reason says why).
      .withColumn(
        "assignedTier",
        when(col("clusterMemberCount") === 1, lit("SINGLETON")).otherwise(col("assignedTier"))
      )
      .withColumn("identityConfidence", coalesce(col("minConfidence"), lit(1.0)))
      .drop("unres", "minConfidence")

    def refusalRows(tier: String, key: String, rulesCol: String, ref: DataFrame) =
      ref
        .join(
          assigned
            .groupBy(key)
            .agg(
              sum(col("isMember").cast("long")).as("clusterMemberCount"),
              count(lit(1)).as("clusterNodeCount"),
              min(when(col("isMember"), col("node"))).as("sampleMember")
            ),
          key
        )
        .select(
          lit(tier).as("tier"),
          col(key).as("clusterKey"),
          col("clusterMemberCount"),
          col("clusterNodeCount"),
          col("sampleMember"),
          col(rulesCol).as("rules")
        )
    val refusals =
      refusalRows("TIER2", "r2", "tier2Rules", ref2).union(
        refusalRows("TIER1", "r1", "tier1Rules", ref1)
      )
    Result(mapping, refusals, Seq(rounds1, rounds2))

  /** (key, rules) for every cluster under `key` that breaks at least one rule. */
  private def refused(
      a: DataFrame,
      key: String,
      conflicts: DataFrame,
      attrs: DataFrame,
      groups: DataFrame
  ): DataFrame =
    val k = a.select(col("node"), col(key).as("k"))
    val withAttrs = k.join(attrs, "node")
    def twoDistinct(c: String, rule: String) =
      withAttrs
        .where(col(c).isNotNull)
        .groupBy("k")
        .agg(countDistinct(c).as("n"))
        .where(col("n") >= 2)
        .select(col("k"), lit(rule).as("rule"))
    val conflict = conflicts
      .join(k.select(col("node").as("src"), col("k").as("ks")), "src")
      .join(k.select(col("node").as("dst"), col("k").as("kd")), "dst")
      .where(col("ks") === col("kd"))
      .select(col("ks").as("k"), lit(Rule.Conflict).as("rule"))
    val group = groups
      .join(k, "node")
      .groupBy("k", "groupId")
      .agg(countDistinct("node").as("n"))
      .where(col("n") >= 2)
      .select(col("k"), lit(Rule.Group).as("rule"))
    twoDistinct("orcid", Rule.TwoOrcids)
      .union(conflict)
      .union(twoDistinct("suffix", Rule.Suffix))
      .union(group)
      .groupBy(col("k").as(key))
      .agg(array_sort(collect_set("rule")).as("rules"))
