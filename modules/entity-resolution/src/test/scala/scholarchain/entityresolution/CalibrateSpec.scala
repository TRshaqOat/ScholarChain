package scholarchain.entityresolution

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.MatchEntityType
import scholarchain.entityresolution.tools.Calibrate
import scholarchain.entityresolution.tools.Calibrate.Member

import scala.util.Random

class CalibrateSpec extends AnyFunSuite:
  /** Random researcher-like members: one key at levels 0 and 1, one or more at level 2. */
  private def members(seed: Int, n: Int): Vector[Member] =
    val r = Random(seed)
    (0 until n).toVector.map { i =>
      val k0 = s"r0:${r.nextInt(3)}"
      val k1 = s"r1:$k0|${r.nextInt(3)}"
      val k2 = (0 to r.nextInt(3)).map(_ => s"r2:$k1|${r.nextInt(4)}").distinct.sorted.toVector
      Member(i, i / 3, Vector(Vector(k0), Vector(k1), k2)) // every 3 members share a work
    }

  test("candidate counts equal the distinct cross-work pairs of the OversizePolicy plan") {
    for seed <- 1 to 5; max <- Seq(1, 2, 4, 10, 1000) do
      val ms = members(seed, 60)
      val (count, plan, _) = Calibrate.planCounts(ms, 3, max, 60, skipSameWork = true)
      val byId = ms.map(m => m.index.toString -> m).toMap
      val brute = plan.blocks.flatMap { b =>
        val xs = b.memberIds.map(byId)
        for i <- xs.indices; j <- i + 1 until xs.size if xs(i).work != xs(j).work
        yield (math.min(xs(i).index, xs(j).index), math.max(xs(i).index, xs(j).index))
      }.distinct
      assert(count == brute.size, s"seed $seed max $max")
  }

  test("connectivity counts the largest retained component of each silver group") {
    // Group {0,1,2,3}: retained 0-1 and 2-3 gives components of 2 and 2; group {4,5}: 4-5 joined.
    assert(
      Calibrate.connected(
        Vector(Vector(0, 1, 2, 3), Vector(4, 5)),
        Seq((0, 1), (2, 3), (4, 5)),
        6
      ) == 4
    )
    assert(Calibrate.connected(Vector(Vector(0, 1, 2)), Seq((0, 1), (1, 2)), 3) == 3)
  }

  test("silver score classification follows the policy rules with ORCID removed") {
    import Calibrate.Kind
    assert(
      Calibrate
        .classify(Map(Features.NameSimilarity -> 1.0, Features.HomonymSuffixAgreement -> 1.0))
        ._1 == Kind.Deterministic
    )
    assert(
      Calibrate
        .classify(Map(Features.NameSimilarity -> 1.0, Features.HomonymSuffixAgreement -> 0.0))
        ._1 == Kind.IdDisagreement
    )
    assert(Calibrate.classify(Map(Features.NameSimilarity -> 0.9))._1 == Kind.Scored)
    assert(Calibrate.classify(Map(Features.CoauthorOverlap -> 0.9))._1 == Kind.Insufficient)
  }

  test("work silver scores use cross-source DOI labels with the DOI and arXiv features removed") {
    import Calibrate.Kind
    def line(src: String, tgt: String, f: (String, Double)*) =
      ujson
        .Obj(
          "sourceId" -> src,
          "targetId" -> tgt,
          "features" -> ujson.Obj.from(f.map((k, v) => k -> ujson.Num(v)))
        )
        .render()
    val title = Features.TitleSimilarity -> 1.0
    val s = Calibrate.silverScores(
      Iterator(
        // positive: with the DOI kept it would be an EXACT_ID accept; removed, the title is scored
        line("dblp:conf/a/X", "openalex:W1", title, MatchPolicy.DoiAgreement -> 1.0),
        // negative: a different DBLP record is still a policy rule after DOI removal
        line(
          "dblp:conf/a/X",
          "doi:10.1/y",
          title,
          MatchPolicy.DoiAgreement -> 0.0,
          MatchPolicy.ArxivAgreement -> 0.0
        ),
        // same source: excluded (OpenAlex multi-work DOIs)
        line("openalex:W1", "openalex:W2", title, MatchPolicy.DoiAgreement -> 1.0),
        // unlabelled: ignored
        line("dblp:conf/a/X", "openalex:W3", title)
      ),
      MatchEntityType.WORK
    )
    assert(s.label == MatchPolicy.DoiAgreement && s.excludedSameSource == 1)
    assert(s.total(true) == 1 && s.total(false) == 1)
    val bin = Calibrate.bin(MatchPolicy.score(MatchEntityType.WORK, Map(title)).get)
    assert(s.counts.get((true, Kind.Scored, bin)).contains(1L))
    assert(
      Calibrate
        .classify(
          Map(title, MatchPolicy.DblpRecordAgreement -> 0.0),
          MatchEntityType.WORK
        )
        ._1 == Kind.IdDisagreement
    )
    assert(Calibrate.scoreTable(s).contains("label doi_agreement"))
  }
