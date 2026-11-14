package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.{BlockingKeys, OversizePolicy}
import scholarchain.hadoop.{Blocking, BlockMember, Teams}
import upickle.default.write

/** Phase 11 WO3, spec categories "very large blocking groups" and "large-team pair expansion" (the
  * J05 and J07+J08 pure logic, with no Hadoop runtime), plus the common-name split rule: an
  * oversize block is split or reported, never merged, and no member disappears silently.
  */
class BlockingTeamAdversarialSpec extends AnyFunSuite:
  private val run = StableIds.runId("adv", "code", "config-v1", Seq("dblp=2026-10-03"), 1)
  private val prov =
    Provenance(SchemaVersions.Provenance, SourceType.DBLP, "k", "2026-10-03", run, None, None)

  private def work(i: Int, names: Seq[String], year: Int = 2020): WorkRecord =
    val id = s"dblp:conf/adv/W$i"
    WorkRecord(
      SchemaVersions.WorkRecord,
      id,
      SourceType.DBLP,
      s"conf/adv/W$i",
      Some(s"Paper $i"),
      Some(s"paper $i"),
      Some(year),
      None,
      Some("inproceedings"),
      None,
      None,
      Map.empty,
      names.zipWithIndex
        .map((n, k) =>
          AuthorMention(
            SchemaVersions.AuthorMention,
            s"$id#${k + 1}",
            id,
            k + 1,
            n,
            n.toLowerCase,
            None,
            None,
            Vector.empty,
            prov
          )
        )
        .toVector,
      None,
      prov
    )

  private def member(w: WorkRecord, pos: Int): BlockMember =
    Blocking.members(w).find(_.position.contains(pos)).get

  test(
    "a 5,000-member common-name group at the last level is reported oversize with no member held or merged"
  ) {
    val members = (1 to 5000).map(i => member(work(i, Seq("Wei Wang")), 1))
    val rekeyed = scala.collection.mutable.ArrayBuffer.empty[String]
    val (g, size, dupes) =
      Blocking.group(2, "r2:wang|wei|-", 150, members.iterator.map(write(_)), rekeyed += _)
    assert(size == 5000 && dupes == 0)
    g match
      case Blocking.Group.Oversize(r) => assert(r.size == 5000 && r.key == "r2:wang|wei|-")
      case other                      => fail(s"expected an oversize report, got $other")
    assert(rekeyed.isEmpty, "the last level re-keyed members")
  }

  test("a 5,000-member group below the last level is re-keyed in full, each member exactly once") {
    val members = (1 to 5000).map(i => member(work(i, Seq("Wei Wang", s"Coauthor$i Family$i")), 1))
    val rekeyed = scala.collection.mutable.ArrayBuffer.empty[String]
    val (g, size, _) =
      Blocking.group(0, "r0:wang|w", 150, members.iterator.map(write(_)), rekeyed += _)
    assert(g == Blocking.Group.Rekeyed(5000) && size == 5000)
    val ids = rekeyed.map(j => upickle.default.read[BlockMember](j).id)
    assert(ids.size == 5000 && ids.distinct.size == 5000)
  }

  test(
    "duplicate member values before the overflow are de-duplicated and counted; a kept block is at most max"
  ) {
    val ms = (1 to 150).map(i => member(work(i, Seq("Ada Lovelace")), 1))
    val values = (ms ++ ms.take(20)).iterator.map(write(_))
    val (g, size, dupes) =
      Blocking.group(0, "r0:lovelace|a", 150, values, _ => fail("re-keyed a block of 150"))
    assert(size == 150 && dupes == 20)
    g match
      case Blocking.Group.Kept(b) =>
        assert(b.members.size == 150 && b.members.map(_.id) == b.members.map(_.id).sorted)
        val (pairs, sameWork) = Blocking.pairs(b)
        val ps = pairs.toVector
        assert(ps.size == 150 * 149 / 2 && sameWork == 0)
        assert(ps.forall((a, c) => CanonicalPair.isCanonical(a.id, c.id)))
      case other => fail(s"$other")
  }

  test(
    "common names over three levels: every keyed mention ends in exactly one kept block or one oversize report"
  ) {
    // 3,000 "Wei Wang" mentions: 2,000 with one of 20 coauthor families, 1,000 single-author.
    val mentions = (1 to 3000).map { i =>
      val co = if i <= 2000 then Seq(s"Coauthor Fam${i % 20}") else Seq.empty
      (s"m$i", BlockingKeys.researcher("Wei Wang", co))
    }
    val plan = OversizePolicy.plan[(String, Vector[Vector[String]])](
      mentions,
      _._1,
      _._2,
      BlockingKeys.ResearcherLevels,
      150
    )
    assert(plan.blocks.forall(_.memberIds.size <= 150))
    val inBlocks = plan.blocks.flatMap(_.memberIds).toSet
    val inOversize = plan.oversize.map(_.size).sum
    assert(plan.oversize.exists(_.key == "r2:wang|wei|-"), plan.oversize)
    assert(
      inBlocks.size == 2000 && inOversize == 1000,
      s"${inBlocks.size} in blocks, $inOversize oversize"
    )
    assert(plan.missingKey.isEmpty)
    // The same plan whatever the input order.
    assert(
      OversizePolicy.plan[(String, Vector[Vector[String]])](
        mentions.reverse,
        _._1,
        _._2,
        BlockingKeys.ResearcherLevels,
        150
      ) == plan
    )
  }

  test(
    "pair expansion: empty, solo, and duo teams; mentions of one work never pair inside a block"
  ) {
    assert(Teams.pairs(work(1, Seq())).isEmpty && Teams.pairCount(0) == 0)
    assert(Teams.pairs(work(2, Seq("A B"))).isEmpty && Teams.pairCount(1) == 0)
    val duo = Teams.pairs(work(3, Seq("Zed Z", "Ada A"))).toVector
    assert(duo.size == 1 && CanonicalPair.isCanonical(duo.head.authorA, duo.head.authorB))
    // A repeated name on one work is two positions and one pair; the work's own block never pairs them.
    val twice = work(4, Seq("Wei Wang 0001", "Wei Wang 0001"))
    assert(Teams.pairs(twice).size == 1)
    val block = scholarchain.hadoop.BlockRecord(
      Blocking.SchemaVersion,
      MatchEntityType.RESEARCHER,
      0,
      "r0:wang|w",
      Blocking.members(twice).tail.sortBy(_.id)
    )
    val (ps, sameWork) = Blocking.pairs(block)
    assert(ps.isEmpty && sameWork == 1)
  }

  test("a 2,000-author team expands lazily into exactly n(n-1)/2 canonical, valid pairs") {
    val n = 2000
    val w = work(5, (1 to n).map(i => s"Author$i Family$i"), year = 2019)
    assert(Teams.pairCount(n) == 1999000L)
    var count = 0L
    var maxPos = 0
    Teams.pairs(w).foreach { p =>
      count += 1
      assert(CanonicalPair.isCanonical(p.authorA, p.authorB) && p.authorA != p.authorB)
      if count % 99991 == 1 then assert(Validation.validate(p).isRight, p)
      maxPos = math.max(maxPos, p.authorB.drop(p.authorB.lastIndexOf('#') + 1).toInt)
    }
    assert(count == Teams.pairCount(n) && maxPos == n)
    assert(Teams.bucket(n) == "101+" && Validation.validate(Teams.team(w)).isRight)
    // Overflow guard: the count is a Long for teams beyond 65,536 authors.
    assert(Teams.pairCount(100000) == 4999950000L)
  }
