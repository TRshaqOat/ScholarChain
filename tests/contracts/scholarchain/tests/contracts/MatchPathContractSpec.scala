package scholarchain.tests.contracts

import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.entityresolution.{MatchPath as ErPath, MatchPolicy}
import scholarchain.spark.MatchPath
import scholarchain.tests.support.SparkWorld.*

/** Phase 11 work order 4, Phase 06 review m5. The Spark job reads ACCEPTED decisions and derives
  * each one's match path and clustering tier with its own copy of the rule-ID mapping
  * (`scholarchain.spark.MatchPath`, Inputs.scala), because sparkAnalytics may not depend on
  * entityResolution. This contract holds the copy to the original (`MatchPolicy.pathOf`): for every
  * rule ID the policy defines (found by reflection, so a new rule is included automatically), every
  * subset of up to four of them, and seeded larger subsets, both give the same path; and the tier
  * is 1 exactly for the EXACT_ID and DETERMINISTIC paths (Phase 6 ruling 6).
  */
class MatchPathContractSpec extends AnyFunSuite:
  /** Every String constant of MatchPolicy.Rule. */
  private val ruleIds: Vector[String] =
    val r = MatchPolicy.Rule
    r.getClass.getDeclaredMethods.toVector
      .filter(m => m.getParameterCount == 0 && m.getReturnType == classOf[String])
      .map(_.invoke(r).asInstanceOf[String])
      .distinct
      .sorted

  test("the rule IDs found by reflection include every rule the Spark copy names") {
    assert(ruleIds.size >= 17, ruleIds)
    val named = Vector(
      "er-v1:llm-adjudication",
      "er-v1:exact-id-match",
      "er-v1:exact-id-non-authoritative",
      "er-v1:exact-id-group-conflict",
      "er-v1:identifier-agreement",
      "er-v1:identifier-disagreement",
      "er-v2:same-source-shared-doi",
      "er-v1:deterministic-bibliographic",
      "er-v2:deterministic-title-authors-year",
      "er-v1:band-llm"
    )
    assert(named.forall(ruleIds.contains), named.filterNot(ruleIds.contains))
    assert(ruleIds.forall(_.matches("er-v[0-9]+:[a-z0-9-]+")), ruleIds)
  }

  test("Spark MatchPath.of and .tier equal MatchPolicy.pathOf on every small and seeded rule set") {
    val small = (0 to 4).flatMap(k => ruleIds.combinations(k)).toVector
    val rnd = java.util.Random(6L)
    val large = Vector.fill(400)(ruleIds.filter(_ => rnd.nextBoolean()))
    // Unknown and future rule IDs are decision data too: they must not change the path.
    val unknown = Vector(
      Vector("er-v3:exact-id-match"),
      Vector("er-v1:exact-id-match-v2", "er-v1:band-accept"),
      Vector("ER-V1:LLM-ADJUDICATION"),
      Vector(""),
      Vector("er-v1:band-llm", "er-v9:llm-adjudication")
    )
    // Order and duplicates do not matter.
    val shuffled = large.take(50).map(s => scala.util.Random(s.size.toLong).shuffle(s ++ s))
    val sets = (small ++ large ++ unknown ++ shuffled).distinct
    val input = df("i INT, rules ARRAY<STRING>", sets.zipWithIndex.map((s, i) => Seq(i, s))*)
    val got = input
      .select(
        col("i"),
        MatchPath.of(col("rules")).as("path"),
        MatchPath.tier(col("rules")).as("tier")
      )
      .collect()
      .map(x => x.getInt(0) -> (x.getString(1), x.getInt(2)))
      .toMap
    assert(got.size == sets.size)
    val wrong = sets.zipWithIndex.flatMap { (s, i) =>
      val want = MatchPolicy.pathOf(s)
      val wantTier = if want == ErPath.EXACT_ID || want == ErPath.DETERMINISTIC then 1 else 2
      Option.when(got(i) != (want.toString, wantTier))(s"$s: spark ${got(i)}, policy $want")
    }
    assert(wrong.isEmpty, s"${wrong.size} of ${sets.size} differ: ${wrong.take(5).mkString("; ")}")
    // Every path the policy can name is reached by some set, so no branch is untested.
    assert(got.values.map(_._1).toSet == ErPath.values.map(_.toString).toSet)
  }
