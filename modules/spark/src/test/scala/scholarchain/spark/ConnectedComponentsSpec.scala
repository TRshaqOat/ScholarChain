package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{LongType, StructField, StructType}
import org.scalatest.funsuite.AnyFunSuite

class ConnectedComponentsSpec extends AnyFunSuite:
  private lazy val spark = TestSpark.spark

  private def components(edges: (Long, Long)*): (Map[Long, Long], Int) =
    TestSpark.freshCheckpointDir()
    val schema = StructType(Seq(StructField("src", LongType), StructField("dst", LongType)))
    val df = spark.createDataFrame(
      java.util.Arrays.asList(edges.map((a, b) => Row(a, b))*),
      schema
    )
    val (cc, rounds) = ConnectedComponents(df, 30)
    (cc.collect().map(r => r.getLong(0) -> r.getLong(1)).toMap, rounds)

  test("a chain, a triangle, and a pair: each node gets the smallest node of its component") {
    // Hand-checked: {1..7} -> 1, {10, 11, 12} -> 10, {20, 21} -> 20.
    val (cc, _) = components(
      (7L, 6L),
      (6L, 5L),
      (5L, 4L),
      (4L, 3L),
      (3L, 2L),
      (2L, 1L),
      (10L, 11L),
      (11L, 12L),
      (12L, 10L),
      (21L, 20L)
    )
    assert(
      cc == ((1L to 7L).map(_ -> 1L) ++ Seq(10L, 11L, 12L).map(_ -> 10L) ++ Seq(
        20L -> 20L,
        21L -> 20L
      )).toMap
    )
  }

  test("a graph that is already a star needs no round; self-loops and duplicates are ignored") {
    val (cc, rounds) = components((1L, 2L), (3L, 1L), (1L, 4L), (2L, 1L), (5L, 5L))
    assert(rounds == 0)
    assert(cc == Map(1L -> 1L, 2L -> 1L, 3L -> 1L, 4L -> 1L))
  }

  test("a long chain with its minimum in the middle converges to that minimum") {
    // 9-8-7-6-0-1-2-3-4-5: one component, root 0.
    val chain = Seq(9L, 8L, 7L, 6L, 0L, 1L, 2L, 3L, 4L, 5L)
    val (cc, rounds) = components(chain.zip(chain.tail)*)
    assert(cc == chain.map(_ -> 0L).toMap)
    assert(rounds >= 1)
  }
