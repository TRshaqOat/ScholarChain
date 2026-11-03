package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.{BlockingKeys, Features, OversizePolicy}
import upickle.default.{read, write}

/** J05 and J06 logic on hand-checkable members. The multi-pass reducer logic (Blocking.group, run
  * pass by pass as J05 does) must give the same blocks as the entity-resolution oracle
  * OversizePolicy.plan.
  */
class BlockingSpec extends AnyFunSuite:
  import TestSupport.*

  private def work(
      key: String,
      title: Option[String],
      year: Option[Int],
      names: String*
  ): WorkRecord =
    val id = StableIds.dblpWorkId(key)
    val prov = ctx.provenance(SourceType.DBLP, key)
    WorkRecord(
      SchemaVersions.WorkRecord,
      id,
      SourceType.DBLP,
      key,
      title,
      None,
      year,
      None,
      None,
      None,
      None,
      Map.empty,
      names.toVector.zipWithIndex.map((n, i) =>
        AuthorMention(
          SchemaVersions.AuthorMention,
          s"$id#${i + 1}",
          id,
          i + 1,
          n,
          n,
          None,
          None,
          Vector.empty,
          prov
        )
      ),
      None,
      prov
    )

  private val works = Vector(
    work("conf/x/W1", Some("Deep Residual Learning for Images"), Some(2020), "Wei Wang", "Ann Lee"),
    work(
      "conf/x/W2",
      Some("Deep Residual Learning at Scale"),
      Some(2020),
      "Wei Wang 0001",
      "Bob Lee"
    ),
    work("conf/x/W3", Some("Graph Mining"), Some(2019), "Wen Wang", "Ann Lee"),
    work("conf/x/W4", None, Some(2018), "W. Wang", "Carl Zhou", "---"),
    work(
      "conf/x/W5",
      Some("Deep Residual Learning for Images"),
      Some(2021),
      "Wei Wang",
      "Ann Lee",
      "Bob Smith"
    )
  )
  private val members = works.flatMap(Blocking.members)

  /** J05 in memory: one pass per level, each pass grouping the previous pass's rekey output. */
  private def simulate(ms: Vector[BlockMember], max: Int) =
    var active = ms.filter(_.keys.nonEmpty).map(write(_))
    val kept = Vector.newBuilder[(Int, String, Vector[String])]
    val oversize = Vector.newBuilder[(Int, String, Long)]
    for level <- 0 until Blocking.Passes do
      val next = Vector.newBuilder[String]
      active
        .flatMap(j => read[BlockMember](j).keys(level).map(_ -> j))
        .groupMap(_._1)(_._2)
        .toVector
        .sortBy(_._1)
        .foreach { (k, vs) =>
          Blocking.group(level, k, max, vs.reverseIterator, next += _)._1 match
            case Blocking.Group.Kept(b) =>
              assert(b.members.size <= max && b.members.map(_.id) == b.members.map(_.id).sorted)
              kept += ((level, k, b.members.map(_.id)))
            case Blocking.Group.Oversize(r) => oversize += ((level, k, r.size))
            case Blocking.Group.Rekeyed(_)  => ()
        }
      active = next.result()
    (kept.result().sortBy(b => (b._1, b._2)), oversize.result().sortBy(o => (o._1, o._2)))

  private def oracle(max: Int) =
    val plans = Seq(MatchEntityType.RESEARCHER, MatchEntityType.WORK).map { t =>
      OversizePolicy
        .plan[BlockMember](members.filter(_.entityType == t), _.id, _.keys, Blocking.levels(t), max)
    }
    (
      plans
        .flatMap(_.blocks)
        .map(b => (b.level, b.key, b.memberIds))
        .toVector
        .sortBy(b => (b._1, b._2)),
      plans
        .flatMap(_.oversize)
        .map(o => (o.level, o.key, o.size.toLong))
        .toVector
        .sortBy(o => (o._1, o._2)),
      plans.flatMap(_.missingKey).toSet
    )

  test("members: one work member and one researcher member per mention, with ER blocking keys") {
    val w1 = works(0)
    val ms = Blocking.members(w1)
    assert(
      ms.map(m => (m.entityType, m.id)) == Vector(
        (MatchEntityType.WORK, "dblp:conf/x/W1"),
        (MatchEntityType.RESEARCHER, "dblp:conf/x/W1#1"),
        (MatchEntityType.RESEARCHER, "dblp:conf/x/W1#2")
      )
    )
    assert(ms(0).keys == BlockingKeys.work(w1.title, w1.year))
    assert(ms(1).keys == BlockingKeys.researcher("Wei Wang", Seq("Ann Lee")))
    assert(ms(1).keys.head == Vector("r0:wang|w") && ms(1).mention.rawName == "Wei Wang")
    // No title and no family name: no key (J05 counts MISSING_KEY).
    assert(
      members.filter(_.keys.isEmpty).map(_.id).toSet == Set("dblp:conf/x/W4", "dblp:conf/x/W4#3")
    )
  }

  test("multi-pass J05 logic equals OversizePolicy.plan, for several maximum block sizes") {
    for max <- Seq(1, 2, 3, 100) do
      val (kept, oversize) = simulate(members, max)
      val (pKept, pOversize, pMissing) = oracle(max)
      assert(kept == pKept, s"max $max")
      assert(oversize == pOversize, s"max $max")
      assert(pMissing == Set("dblp:conf/x/W4", "dblp:conf/x/W4#3"))
  }

  test("max 2 by hand: r0 wang|w (5) and r1 wang|wei (3) re-key; r2 wang|wei|lee (3) is oversize") {
    val (kept, oversize) = simulate(members, 2)
    assert(oversize.contains((2, "r2:wang|wei|lee", 3L)))
    assert(oversize.contains((2, "r2:lee|ann|wang", 3L)))
    assert(kept.contains((2, "r2:wang|wei|smith", Vector("dblp:conf/x/W5#1"))))
    assert(kept.contains((1, "r1:wang|wen", Vector("dblp:conf/x/W3#1"))))
    assert(
      kept.contains(
        (1, "w1:deep_residual_learning|2020", Vector("dblp:conf/x/W1", "dblp:conf/x/W2"))
      )
    )
    assert(kept.contains((1, "w1:deep_residual_learning|2021", Vector("dblp:conf/x/W5"))))
    assert(!kept.exists(_._2.startsWith("r0:wang|w")))
  }

  test("group: buffers at most max members, then streams; duplicates are dropped while buffering") {
    val ms =
      (1 to 5).map(i => write(Blocking.members(work(s"conf/x/G$i", None, None, "Wei Wang"))(1)))
    val rekeyed = Vector.newBuilder[String]
    val (g, size, _) = Blocking.group(0, "r0:wang|w", 2, ms.iterator, rekeyed += _)
    assert(g == Blocking.Group.Rekeyed(5) && size == 5)
    assert(
      rekeyed.result().map(read[BlockMember](_).id).sorted == (1 to 5).map(i =>
        s"dblp:conf/x/G$i#1"
      )
    )
    val last =
      Blocking.group(2, "r2:wang|wei|-", 2, ms.iterator, _ => fail("no rekey at the last level"))
    assert(
      last._1 == Blocking.Group.Oversize(
        OversizeBlockRecord(
          Blocking.SchemaVersion,
          MatchEntityType.RESEARCHER,
          2,
          "r2:wang|wei|-",
          5
        )
      )
    )
    val (kept, n, dupes) =
      Blocking.group(0, "r0:wang|w", 2, Iterator(ms(1), ms(0), ms(1)), _ => fail("kept"))
    assert(n == 2 && dupes == 1)
    assert(kept match
      case Blocking.Group.Kept(b) =>
        b.members.map(_.id) == Vector("dblp:conf/x/G1#1", "dblp:conf/x/G2#1")
      case _ => false)
  }

  test("pairs: n members give n(n-1)/2 pairs in ID order; two mentions of one work are skipped") {
    val a = work("conf/x/P1", None, None, "Wei Wang", "Wei Wang")
    val b = work("conf/x/P2", None, None, "Wei Wang")
    val block = BlockRecord(
      Blocking.SchemaVersion,
      MatchEntityType.RESEARCHER,
      0,
      "r0:wang|w",
      (Blocking.members(a).tail ++ Blocking.members(b).tail).sortBy(_.id)
    )
    val (ps, sameWork) = Blocking.pairs(block)
    assert(
      ps.map((x, y) => (x.id, y.id)).toVector == Vector(
        ("dblp:conf/x/P1#1", "dblp:conf/x/P2#1"),
        ("dblp:conf/x/P1#2", "dblp:conf/x/P2#1")
      )
    )
    assert(sameWork == 1)
  }

  test("features and candidates: ER Features applied unchanged; venue names from the side input") {
    val rs = Blocking.members(works(0))(1)
    val rt = Blocking.members(works(1))(1)
    assert(
      Blocking.features(rs, rt, _ => None) == Features.researcher(
        rs.mention,
        rs.work,
        rt.mention,
        rt.work
      )
    )
    val wa = Blocking.members(works(0).copy(venueId = Some("dblp-venue:conf/ics"))).head
    val wb = Blocking.members(works(4).copy(venueId = Some("openalex:S1"))).head
    val names = Map(
      "dblp-venue:conf/ics" -> "ICS",
      "openalex:S1" -> "International Conference on Supercomputing"
    )
    val f = Blocking.features(wa, wb, names.get)
    assert(f(Features.VenueCompatibility) == 1.0 && f(Features.TitleSimilarity) == 1.0)
    assert(f(Features.YearDistance) == 0.5)
    assert(!Blocking.features(wa, wb, _ => None).contains(Features.VenueCompatibility))
    val c =
      Blocking.candidate(MatchEntityType.WORK, wa.id, wb.id, "w0:deep_residual_learning", f, ctx)
    assert(Validation.validate(c).isRight)
    assert(c.provenance.policyVersion.contains("norm-v1.block-v1.feat-v1"))
    assert(c.provenance.sourceDataset == SourceType.DERIVED)
    assert(c.provenance.sourceRecordId == s"w0:deep_residual_learning|${wa.id}|${wb.id}")
  }
