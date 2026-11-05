package scholarchain.hadoop

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.MatchPolicy
import scholarchain.entityresolution.MatchPolicy.{V1, V2}

class LogicSpec extends AnyFunSuite:
  import TestSupport.*

  private def dblpWork(key: String, n: Int, year: Option[Int] = Some(2020)): WorkRecord =
    val id = StableIds.dblpWorkId(key)
    val prov = ctx.provenance(SourceType.DBLP, key)
    WorkRecord(
      SchemaVersions.WorkRecord,
      id,
      SourceType.DBLP,
      key,
      None,
      None,
      year,
      None,
      None,
      None,
      None,
      Map.empty,
      (1 to n).toVector.map(i =>
        AuthorMention(
          SchemaVersions.AuthorMention,
          s"$id#$i",
          id,
          i,
          s"A$i",
          s"A$i",
          None,
          None,
          Vector.empty,
          prov
        )
      ),
      None,
      prov
    )

  private def pairs(n: Int) =
    Teams.pairs(dblpWork("conf/x/P", n)).map(p => (p.authorA.drop(13), p.authorB.drop(13))).toVector

  test("pair expansion: 0 and 1 authors give no pairs") {
    assert(pairs(0).isEmpty && pairs(1).isEmpty)
  }

  test("pair expansion: 2 authors give exactly the pair (#1, #2)") {
    assert(pairs(2) == Vector(("#1", "#2")))
  }

  test("pair expansion: 3 authors give (#1,#2), (#1,#3), (#2,#3)") {
    assert(pairs(3) == Vector(("#1", "#2"), ("#1", "#3"), ("#2", "#3")))
  }

  test("pair expansion: 12 authors give 66 distinct canonical pairs; #10 sorts before #2") {
    val ps = pairs(12)
    assert(ps.size == 66 && ps.distinct.size == 66 && Teams.pairCount(12) == 66)
    assert(ps.contains(("#10", "#2")) && !ps.contains(("#2", "#10"))) // String order, CanonicalPair
    assert(ps.forall((a, b) => a < b))
  }

  test("pair records carry work ID, year, positions, source provenance, and validate") {
    val w = dblpWork("conf/x/P", 4, Some(2011))
    val ps = Teams.pairs(w).toVector
    assert(
      ps.size == 6 && ps.forall(p =>
        p.workId == w.workId && p.year.contains(2011) && p.provenance == w.provenance
      )
    )
    assert(ps.forall(p => Validation.validate(p).isRight))
    assert(
      ps.flatMap(p => Seq(p.authorA, p.authorB))
        .map(_.split('#').last.toInt)
        .toSet == (1 to 4).toSet
    )
    assert(Teams.pairCount(1000) == 499500L)
  }

  test("a team lists mention IDs in author order and validates") {
    val t = Teams.team(dblpWork("conf/x/T", 3))
    assert(t.mentionIds == Vector(1, 2, 3).map(i => s"dblp:conf/x/T#$i") && t.teamSize == 3)
    assert(Validation.validate(t).isRight)
  }

  test("team-size buckets") {
    assert(
      Seq(0, 1, 10, 11, 20, 21, 50, 51, 100, 101).map(Teams.bucket) ==
        Seq("0", "1", "10", "11-20", "11-20", "21-50", "21-50", "51-100", "51-100", "101+")
    )
  }

  test("exact keys use core DOI and ORCID normalization") {
    val w = dblpWork("conf/x/K", 0).copy(doi = Some("10.1/abc"), arxivId = Some("2101.1"))
    assert(ExactIdentifiers.workKeys(w) == Vector("doi:10.1/abc", "arxiv:2101.1"))
    val m = w.authors.headOption.getOrElse(dblpWork("conf/x/K", 1).authors.head)
    assert(
      ExactIdentifiers.mentionKeys(
        m.copy(orcid = Some("0000-0002-1825-0097"), sourceAuthorId = Some("openalex:A1"))
      ) ==
        Vector("orcid:0000-0002-1825-0097", "openalex:A1")
    )
    assert(
      ExactIdentifiers.mentionKeys(m.copy(orcid = Some("0000-0002-1825-0098"))).isEmpty
    ) // bad checksum
    assert(ExactIdentifiers.workKeys(dblpWork("conf/x/K", 0)).isEmpty)
  }

  private def e(id: String, src: SourceType, workId: String = "") =
    IndexEntry("work", id, src, if workId.isEmpty then id else workId)

  test("exact DOI join: non-DBLP works match the single DBLP work; repeats count as duplicates") {
    val o = ExactIdentifiers.resolve(
      "doi:10.1/a",
      Seq(
        e("dblp:conf/x/A", SourceType.DBLP),
        e("openalex:W1", SourceType.OPENALEX),
        e("doi:10.1/a", SourceType.CROSSREF),
        e("openalex:W1", SourceType.OPENALEX)
      ),
      ctx,
      100,
      V2
    )
    assert(
      o.matches.map(c => (c.sourceId, c.targetId)) == Vector(
        ("doi:10.1/a", "dblp:conf/x/A"),
        ("openalex:W1", "dblp:conf/x/A")
      )
    )
    assert(
      o.matches.forall(c =>
        c.entityType == MatchEntityType.WORK && c.blockingKey == "doi:10.1/a" && c.features.isEmpty
      )
    )
    assert(o.matches.forall(c => Validation.validate(c).isRight))
    assert(o.duplicates == 1 && o.conflicts.isEmpty && o.groupSize == 3)
  }

  test("exact DOI join: two DBLP works with one DOI are a conflict and nothing merges") {
    val o = ExactIdentifiers.resolve(
      "doi:10.1/a",
      Seq(
        e("dblp:conf/x/B", SourceType.DBLP),
        e("dblp:conf/x/A", SourceType.DBLP),
        e("openalex:W1", SourceType.OPENALEX)
      ),
      ctx,
      100,
      V2
    )
    assert(o.matches.isEmpty)
    assert(
      o.conflicts.map(c => (c.sourceId, c.targetId)) == Vector(("dblp:conf/x/A", "dblp:conf/x/B"))
    )
  }

  test("conflict cap: a conflicting group above max-conflict-group-size emits no pairs") {
    val three = Seq("A", "B", "C").map(k => e(s"dblp:conf/x/$k", SourceType.DBLP))
    val capped = ExactIdentifiers.resolve("doi:10.1/a", three, ctx, 2, V2)
    assert(capped.conflicts.isEmpty && capped.matches.isEmpty && capped.conflicted)
    assert(
      capped.oversizeConflicts == Vector(ExactIdentifiers.OversizeConflict(MatchEntityType.WORK, 3))
    )
    assert(!capped.unmatched)
    val open = ExactIdentifiers.resolve("doi:10.1/a", three, ctx, 3, V2)
    assert(open.conflicts.size == 3 && open.oversizeConflicts.isEmpty)
    val slots = (1 to 3).map(i =>
      IndexEntry("mention", s"dblp:conf/x/B#$i", SourceType.DBLP, "dblp:conf/x/B")
    )
    val r = ExactIdentifiers.resolve("orcid:0000-0002-1825-0097", slots, ctx, 2, V2)
    assert(
      r.conflicts.isEmpty && r.oversizeConflicts == Vector(
        ExactIdentifiers.OversizeConflict(MatchEntityType.RESEARCHER, 3)
      )
    )
    // The cap withholds only the clashing work's mentions: a mention of another work still matches.
    val other = IndexEntry("mention", "dblp:conf/x/A#1", SourceType.DBLP, "dblp:conf/x/A")
    val mixed = ExactIdentifiers.resolve("orcid:0000-0002-1825-0097", slots :+ other, ctx, 2, V2)
    assert(mixed.matches.map(_.sourceId) == Vector("dblp:conf/x/A#1"))
    assert(mixed.conflicted && mixed.oversizeConflicts.size == 1 && mixed.conflicts.isEmpty)
  }

  test("exact DOI join: two OpenAlex works with one DOI conflict; no work per DOI is assumed") {
    val o = ExactIdentifiers.resolve(
      "doi:10.1/a",
      Seq(
        e("openalex:W2", SourceType.OPENALEX),
        e("dblp:conf/x/A", SourceType.DBLP),
        e("openalex:W1", SourceType.OPENALEX)
      ),
      ctx,
      100,
      V2
    )
    assert(o.matches.isEmpty && o.conflicted)
    assert(o.conflicts.map(c => (c.sourceId, c.targetId)) == Vector(("openalex:W1", "openalex:W2")))
  }

  test("exact DOI join: no DBLP work means unmatched") {
    assert(
      ExactIdentifiers
        .resolve("doi:10.1/a", Seq(e("openalex:W1", SourceType.OPENALEX)), ctx, 100, V2)
        .unmatched
    )
  }

  test("exact ORCID join: each mention matches the iD; two slots on one work are a conflict") {
    val o = ExactIdentifiers.resolve(
      "orcid:0000-0002-1825-0097",
      Seq(
        IndexEntry("mention", "dblp:conf/x/A#1", SourceType.DBLP, "dblp:conf/x/A"),
        IndexEntry("mention", "openalex:W1#2", SourceType.OPENALEX, "openalex:W1"),
        IndexEntry("mention", "dblp:conf/x/B#1", SourceType.DBLP, "dblp:conf/x/B"),
        IndexEntry("mention", "dblp:conf/x/B#3", SourceType.DBLP, "dblp:conf/x/B")
      ),
      ctx,
      100,
      V2
    )
    assert(o.matches.map(_.sourceId) == Vector("dblp:conf/x/A#1", "openalex:W1#2"))
    assert(
      o.matches.forall(c =>
        c.targetId == "orcid:0000-0002-1825-0097" && c.entityType == MatchEntityType.RESEARCHER
      )
    )
    assert(
      o.conflicts.map(c => (c.sourceId, c.targetId)) == Vector(
        ("dblp:conf/x/B#1", "dblp:conf/x/B#3")
      )
    )
  }

  // Review 1 finding 1 (fictional IDs): one ORCID on DBLP "Wei Wang 0001" and "Wei Wang 0002".
  private val wangOrcid = "orcid:0000-0001-2345-6789"
  private def mention(work: String, pos: Int, name: String) =
    IndexEntry("mention", s"dblp:$work#$pos", SourceType.DBLP, s"dblp:$work", Some(name))
  private val wang =
    Seq(mention("conf/y/B7", 1, "Wei Wang 0002"), mention("conf/x/A1", 1, "Wei Wang 0001"))

  test("orcid suffix conflict (er-v2): Wei Wang 0001 and 0002 under one ORCID never merge") {
    val o = ExactIdentifiers.resolve(wangOrcid, wang, ctx, 100, V2)
    assert(o.suffixConflict && o.conflicted && !o.unmatched && o.matches.isEmpty)
    assert(
      o.conflicts.map(c => (c.entityType, c.sourceId, c.targetId, c.blockingKey)) == Vector(
        (MatchEntityType.RESEARCHER, "dblp:conf/x/A1#1", "dblp:conf/y/B7#1", wangOrcid)
      )
    )
    assert(o.conflicts.forall(c => Validation.validate(c).isRight) && o.oversizeConflicts.isEmpty)
  }

  test("orcid suffix conflict (er-v1): the same input is unchanged, both mentions match the iD") {
    val o = ExactIdentifiers.resolve(wangOrcid, wang, ctx, 100, V1)
    assert(!o.suffixConflict && !o.conflicted && o.conflicts.isEmpty)
    assert(
      o.matches.map(c => (c.sourceId, c.targetId)) == Vector(
        ("dblp:conf/x/A1#1", wangOrcid),
        ("dblp:conf/y/B7#1", wangOrcid)
      )
    )
  }

  test("orcid suffix conflict (er-v2): the whole group conflicts, all n(n-1)/2 pairs, no match") {
    // An unsuffixed mention and a same-work clash are swept into the one conflict group.
    val group = wang ++ Seq(
      mention("conf/z/C3", 2, "Wei Wang"),
      mention("conf/x/A1", 3, "Wei Wang 0001")
    )
    val o = ExactIdentifiers.resolve(wangOrcid, group, ctx, 100, V2)
    assert(o.suffixConflict && o.matches.isEmpty && o.groupSize == 4)
    assert(o.conflicts.size == 6 && o.conflicts.forall(c => c.sourceId < c.targetId))
    // Capped: no pairs, one oversize report, and still nothing matches.
    val capped = ExactIdentifiers.resolve(wangOrcid, group, ctx, 3, V2)
    assert(capped.suffixConflict && capped.matches.isEmpty && capped.conflicts.isEmpty)
    assert(
      capped.oversizeConflicts == Vector(
        ExactIdentifiers.OversizeConflict(MatchEntityType.RESEARCHER, 4)
      )
    )
  }

  test("orcid suffix conflict (er-v2): one suffixed and one unsuffixed mention still match") {
    val pair = Seq(mention("conf/x/A1", 1, "Wei Wang 0001"), mention("conf/y/B7", 1, "Wei Wang"))
    val o = ExactIdentifiers.resolve(wangOrcid, pair, ctx, 100, V2)
    assert(!o.suffixConflict && !o.conflicted && o.matches.size == 2)
    // Not an orcid: key, so the rule does not apply.
    val oa = ExactIdentifiers.resolve("openalex:A1", wang, ctx, 100, V2)
    assert(!oa.suffixConflict && oa.matches.size == 2)
    assert(MatchPolicy.Rule.OrcidGroupSuffixConflict == "er-v2:orcid-group-homonym-suffix-conflict")
  }
