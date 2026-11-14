package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.core.config.ConfigLoader
import scholarchain.entityresolution.*
import scholarchain.hadoop.{DblpEntities, DblpParser, ExactIdentifiers, IndexEntry, RunContext}
import scholarchain.tests.support.StoreWorld
import upickle.default.read

import java.io.File
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files
import java.text.Normalizer

/** Phase 11 WO3, spec categories "Unicode normalization and confusable characters", "duplicate and
  * conflicting DOI records", "common author names and reordered author lists", and "common-name
  * researcher-resolution cases". Records come from the real J01 parser, features and verdicts from
  * the entity-resolution functions (er-v2, configured thresholds): a cross-module path, with no
  * Hadoop runtime.
  */
class IdentityAdversarialSpec extends AnyFunSuite:
  private val cfg = ConfigLoader.load(StoreWorld.baseEnv).fold(e => sys.error(e.mkString), identity)
  private val t = Thresholds.from(cfg.entityResolution)
  private val v2 = MatchPolicy.V2
  private lazy val parser =
    val repo = Iterator
      .iterate(new File(sys.props("user.dir")).getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => new File(d, "datasets/samples/dblp").isDirectory)
      .get
    DblpParser(
      DblpEntities.parseDtd(
        new String(
          Files.readAllBytes(new File(repo, "datasets/samples/dblp/dblp-2023-06-28.dtd").toPath),
          ISO_8859_1
        )
      )
    )
  private val ctx = RunContext(
    "adv-j01",
    StableIds.runId("adv", "code", "config-v1", Seq("dblp=2026-10-03"), 1),
    Map(
      SourceType.DBLP -> "2026-10-03",
      SourceType.OPENALEX -> "2026-11-03",
      SourceType.CROSSREF -> "2026-11-03"
    ),
    "2026-10-03",
    "graph-v1"
  )
  private def work(
      key: String,
      authors: Seq[String],
      title: String = "On Engines",
      year: Int = 2020,
      ee: Seq[String] = Nil
  ) =
    val xml = s"""<article key="$key">${authors.map(a => s"<author>$a</author>").mkString}""" +
      s"""<title>$title</title><year>$year</year>${ee
          .map(e => s"<ee>$e</ee>")
          .mkString}</article>"""
    parser.parse(xml, ctx).fold(r => fail(s"rejected $key: $r"), p => read[WorkRecord](p.work.json))
  private def researcherVerdict(a: WorkRecord, ia: Int, b: WorkRecord, ib: Int) =
    MatchPolicy.decideBlocked(
      MatchEntityType.RESEARCHER,
      Features.researcher(a.authors(ia), a, b.authors(ib), b),
      t,
      v2
    )
  private def keys(n: String, co: Seq[String] = Seq("Grace Hopper")) =
    BlockingKeys.researcher(n, co)

  // ---- Unicode normalization and confusables ----

  test(
    "canonically and compatibly equivalent spellings give one comparable name and one blocking key"
  ) {
    val forms = Vector(
      "José Müller",
      Normalizer.normalize("José Müller", Normalizer.Form.NFD),
      "José Müller",
      "ＪＯＳＥ ＭＵＬＬＥＲ", // fullwidth
      "Müller, José",
      "J̶o̷s̸é M̵ül̶l̷e̸r" // stacked combining marks
    )
    val base = Normalize.name(forms.head)
    forms.foreach { f =>
      assert(Normalize.name(f).comparable == base.comparable, f)
      assert(keys(f) == keys(forms.head), f)
    }
    // Through the DBLP parser: an entity, a numeric reference, and the raw character agree.
    val a = work("journals/x/U1", Seq("Jos&eacute; M&uuml;ller")).authors.head
    val b = work("journals/x/U2", Seq("Jos&#233; M&#252;ller")).authors.head
    val c = work("journals/x/U3", Seq("José Müller")).authors.head
    assert(Set(a, b, c).map(_.normalizedName) == Set(base.comparable))
    assert(Normalize.title("<i>Deep</i> Learning—ﬁne") == Normalize.title("Deep Learning fine"))
  }

  test("invisible format characters inside a name do not split it or change its keys") {
    // PENDING D7 (owner entity-resolution-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // Soft hyphen, zero-width space and joiners, word joiner, BOM, and bidi marks render as
      // nothing; a name carrying one must not get a different family key (Mul<soft hyphen>ler is
      // still Muller), which would move it to another block and split one person's mentions.
      val clean = "Wei M\u00fcller"
      val split = Vector(0x00ad, 0x200b, 0x200c, 0x200d, 0x2060, 0xfeff, 0x200e, 0x202e).filter {
        cp =>
          val dirty = "Wei M\u00fcl" + new String(Character.toChars(cp)) + "ler"
          Normalize.name(dirty).familyKey != Normalize.name(clean).familyKey || keys(dirty) != keys(
            clean
          )
      }
      assert(
        split.isEmpty,
        "these code points change the family key: " + split.map(cp => f"U+$cp%04X").mkString(", ")
      )
    }
  }

  test(
    "a mixed-script homoglyph of a name is not the same name and is never merged on the name alone"
  ) {
    val latin = work("journals/x/H1", Seq("Wei Wang", "Grace Hopper"))
    // Cyrillic small e (U+0435) and a (U+0430) in place of the Latin letters.
    val homoglyph = work("journals/y/H2", Seq("Wеi Wаng", "Edsger Dijkstra"))
    assert(homoglyph.authors.head.rawName.exists(_ == 'е'))
    assert(
      Normalize.name(homoglyph.authors.head.rawName).comparable != Normalize
        .name("Wei Wang")
        .comparable
    )
    val v = researcherVerdict(latin, 0, homoglyph, 0)
    assert(v.outcome != MatchOutcome.ACCEPTED, v)
    assert(v.path != MatchPath.DETERMINISTIC)
  }

  test(
    "names made only of punctuation, digits, or symbols get no blocking key (no giant empty block)"
  ) {
    Vector("", "   ", "-", "...", "😀 😀", "，。", "​")
      .foreach(n =>
        assert(BlockingKeys.researcher(n, Seq("Ada Lovelace")).isEmpty, s"'$n' got a key")
      )
    assert(BlockingKeys.work(Some("<i></i>"), Some(2020)).isEmpty)
    assert(BlockingKeys.work(Some("!!!"), None).isEmpty)
  }

  // ---- common names and reordered author lists ----

  test("two Wei Wang mentions with nothing else in common are never accepted") {
    val a = work("journals/x/C1", Seq("Wei Wang", "Ada Lovelace"))
    val b = work("journals/y/C2", Seq("Wei Wang", "Edsger Dijkstra"), year = 2005)
    val v = researcherVerdict(a, 0, b, 0)
    assert(v.outcome != MatchOutcome.ACCEPTED, v)
  }

  test(
    "DBLP homonym numbers: equal numbers merge deterministically, different numbers never merge"
  ) {
    val a = work("journals/x/S1", Seq("Wei Wang 0001", "Ada Lovelace", "Grace Hopper"))
    val same = work("journals/y/S2", Seq("Wei Wang 0001", "Edsger Dijkstra"))
    val other = work("journals/z/S3", Seq("Wei Wang 0002", "Ada Lovelace", "Grace Hopper"))
    val ok = researcherVerdict(a, 0, same, 0)
    assert(ok.outcome == MatchOutcome.ACCEPTED && ok.path == MatchPath.DETERMINISTIC, ok)
    // Same coauthors, same position, same name: still two DBLP persons.
    val no = researcherVerdict(a, 0, other, 0)
    assert(no.outcome == MatchOutcome.REJECTED || no.outcome == MatchOutcome.CONFLICT, no)
    assert(no.reasonCodes.contains("HOMONYM_SUFFIX_MISMATCH"))
  }

  test("conflicting ORCID iDs on identical names and coauthors are never merged") {
    val a = work("journals/x/O1", Seq("""Wei Wang""", "Ada Lovelace", "Grace Hopper"))
    val b = work("journals/y/O2", Seq("""Wei Wang""", "Ada Lovelace", "Grace Hopper"))
    val fa = a.authors.head.copy(orcid = Some("0000-0002-1825-0097"))
    val fb = b.authors.head.copy(orcid = Some("0000-0001-5109-3700"))
    val f = Features.researcher(fa, a, fb, b)
    assert(f.get(Features.OrcidAgreement).contains(0.0))
    val v = MatchPolicy.decideBlocked(MatchEntityType.RESEARCHER, f, t, v2)
    assert(v.outcome == MatchOutcome.REJECTED || v.outcome == MatchOutcome.CONFLICT, v)
    // An ORCID group whose mentions carry two DBLP numbers is a conflict group under er-v2.
    val members = Vector(
      IndexEntry(
        "mention",
        "dblp:journals/x/O1#1",
        SourceType.DBLP,
        "dblp:journals/x/O1",
        Some("Wei Wang 0001")
      ),
      IndexEntry(
        "mention",
        "dblp:journals/y/O2#1",
        SourceType.DBLP,
        "dblp:journals/y/O2",
        Some("Wei Wang 0002")
      )
    )
    val out = ExactIdentifiers.resolve("orcid:0000-0002-1825-0097", members, ctx, 100, v2)
    assert(out.matches.isEmpty && out.suffixConflict && out.conflicts.size == 1)
  }

  test(
    "reordered author lists: the comma form names the same person; a reordered team is not a deterministic duplicate"
  ) {
    assert(Normalize.name("Lovelace, Ada").comparable == Normalize.name("Ada Lovelace").comparable)
    assert(
      Normalize.name("van der Aalst, Wil").comparable == Normalize
        .name("Wil van der Aalst")
        .comparable
    )
    val a = work(
      "journals/x/R1",
      Seq("Ada Lovelace", "Grace Hopper", "Edsger Dijkstra"),
      title = "Analytical Engines"
    )
    val reordered = work(
      "journals/x/R2",
      Seq("Edsger Dijkstra", "Ada Lovelace", "Grace Hopper"),
      title = "Analytical Engines"
    )
    val wpf = WorkPairFeatures.work(
      a,
      reordered.copy(source = SourceType.OPENALEX, workId = "openalex:W1"),
      v2
    )
    assert(wpf.get(MatchPolicy.AuthorSequenceAgreement).contains(0.0))
    assert(wpf.get(MatchPolicy.AuthorOverlap).contains(1.0))
    // Positions differ, so author-order agreement is below 1, but the person is the same name.
    val f = Features.researcher(a.authors(0), a, reordered.authors(1), reordered)
    assert(f(Features.NameSimilarity) == 1.0 && f(Features.AuthorOrderAgreement) < 1.0)
  }

  test("a generic title with the same year but no author evidence is not identity") {
    val a = work("conf/x/G1", Seq("Ada Lovelace"), title = "Session Introduction.")
    val b = work("conf/y/G2", Seq("Edsger Dijkstra"), title = "Session Introduction.")
    // Two DBLP records are DBLP-curated distinct works.
    val dblp = MatchPolicy.decideBlocked(
      MatchEntityType.WORK,
      Features.work(a, None, b, None) ++ WorkPairFeatures.work(a, b, v2),
      t,
      v2
    )
    assert(dblp.outcome == MatchOutcome.REJECTED, dblp)
    // Against a source without authors: not accepted on the title alone.
    val oa = b.copy(source = SourceType.OPENALEX, workId = "openalex:W9", authors = Vector.empty)
    val v = MatchPolicy.decideBlocked(
      MatchEntityType.WORK,
      Features.work(a, None, oa, None) ++ WorkPairFeatures.work(a, oa, v2),
      t,
      v2
    )
    assert(v.outcome != MatchOutcome.ACCEPTED, v)
  }

  // ---- duplicate and conflicting DOI records ----

  test("DOI spellings of one DOI normalize to one key") {
    val forms = Vector(
      "10.1145/ABC.123",
      "10.1145/abc.123",
      "https://doi.org/10.1145/abc.123",
      "HTTP://DX.DOI.ORG/10.1145/ABC.123",
      " doi:10.1145/abc.123 ",
      "https://dx.doi.org/10.1145/abc.123"
    )
    assert(forms.map(StableIds.normalizeDoi).distinct == Vector("10.1145/abc.123"))
  }

  test("DOI normalization is idempotent") {
    // PENDING D8 (owner domain-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      Vector(
        "doi: 10.1145/abc.123",
        "https://doi.org/ 10.1145/abc.123",
        "doi:https://doi.org/10.1145/abc.123",
        "https://doi.org/doi:10.1145/abc.123"
      ).map(d => (d, StableIds.normalizeDoi(d)))
        .filter((_, once) => StableIds.normalizeDoi(once) != once)
        .map((d, once) => s"'$d' -> '$once' -> '${StableIds.normalizeDoi(once)}'") match
        case Vector() => ()
        case moved    =>
          // A non-idempotent DOI also fails core validation (doi must equal normalizeDoi(doi)).
          fail(s"not idempotent: ${moved.mkString("; ")}")
    }
  }

  test("a percent-encoded DOI URL (as DBLP writes SICI DOIs) is the same DOI as its plain form") {
    // PENDING D9 (owner domain-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // DBLP ee links URL-encode characters such as < and > in SICI DOIs; Crossref and OpenAlex give
      // the plain DOI. Unequal keys would leave one paper as two works (no exact-ID match).
      val plain = "10.1002/(SICI)1097-4571(199806)49:8<693::AID-ASI4>3.0.CO;2-O"
      val ee = "https://doi.org/10.1002/(SICI)1097-4571(199806)49:8%3C693::AID-ASI4%3E3.0.CO;2-O"
      assert(StableIds.normalizeDoi(ee) == StableIds.normalizeDoi(plain))
    }
  }

  test(
    "exact DOI groups: cross-source duplicates match DBLP; a within-source duplicate is a conflict, never a merge"
  ) {
    def e(id: String, src: SourceType) = IndexEntry("work", id, src, id)
    val key = "doi:10.1145/abc.123"
    val ok = ExactIdentifiers.resolve(
      key,
      Vector(
        e("dblp:a/1", SourceType.DBLP),
        e("openalex:W1", SourceType.OPENALEX),
        e("doi:10.1145/abc.123", SourceType.CROSSREF),
        e("openalex:W1", SourceType.OPENALEX)
      ),
      ctx,
      100,
      v2
    )
    assert(
      ok.matches.map(m => (m.sourceId, m.targetId)).toSet == Set(
        "openalex:W1" -> "dblp:a/1",
        "doi:10.1145/abc.123" -> "dblp:a/1"
      )
    )
    assert(ok.duplicates == 1 && ok.conflicts.isEmpty)
    val twoDblp = ExactIdentifiers.resolve(
      key,
      Vector(
        e("dblp:a/1", SourceType.DBLP),
        e("dblp:a/2", SourceType.DBLP),
        e("openalex:W1", SourceType.OPENALEX)
      ),
      ctx,
      100,
      v2
    )
    assert(twoDblp.matches.isEmpty && twoDblp.conflicts.size == 1)
    val decided = MatchPolicy.decide(twoDblp.conflicts.head, CandidateOrigin.EXACT_CONFLICT, t, v2)
    assert(decided.outcome == MatchOutcome.CONFLICT)
    // Above the conflict cap: no pairs, reported as oversize, and still nothing matches.
    val many =
      (1 to 150).map(i => e(s"openalex:W$i", SourceType.OPENALEX)) :+ e("dblp:a/1", SourceType.DBLP)
    val capped = ExactIdentifiers.resolve(key, many, ctx, 100, v2)
    assert(
      capped.matches.isEmpty && capped.conflicts.isEmpty && capped.oversizeConflicts.map(
        _.size
      ) == Vector(150)
    )
  }

  test(
    "a DOI disagreement with otherwise identical bibliographic evidence is a conflict, never accepted"
  ) {
    val a =
      work("journals/x/D1", Seq("Ada Lovelace", "Grace Hopper"), ee = Seq("https://doi.org/10.1/a"))
    val b =
      work("journals/x/D2", Seq("Ada Lovelace", "Grace Hopper"), ee = Seq("https://doi.org/10.1/b"))
        .copy(source = SourceType.OPENALEX, workId = "openalex:W2")
    val v = MatchPolicy.decideBlocked(
      MatchEntityType.WORK,
      Features.work(a, None, b, None) ++ WorkPairFeatures.work(a, b, v2),
      t,
      v2
    )
    assert(v.outcome == MatchOutcome.CONFLICT, v)
    assert(v.reasonCodes.contains("BIBLIOGRAPHIC_EVIDENCE_CONTRADICTS_ID"))
    // Two DOIs on one DBLP record: the first is the work's DOI, the second stays in externalIds.
    val two = work(
      "journals/x/D3",
      Seq("Ada Lovelace"),
      ee = Seq("https://doi.org/10.1/first", "https://doi.org/10.1/second")
    )
    assert(
      two.doi.contains("10.1/first") && two.externalIds.values.exists(_.endsWith("10.1/second"))
    )
  }
