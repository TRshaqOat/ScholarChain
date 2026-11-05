package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.{
  CandidateOrigin,
  MatchPolicy,
  OversizePolicy,
  Thresholds,
  WorkPairFeatures
}
import upickle.default.read

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.{Files, Path as JPath}

/** Runs the whole job chain with Hadoop's local job runner on datasets/samples (output in a temp
  * directory outside the repository), twice, and checks counters, outputs, and determinism.
  */
class PipelineSpec extends AnyFunSuite:
  import TestSupport.*

  private val out = tempDir("scholarchain-hadoop-it")
  // graph.oversize-team-threshold and entity-resolution.max-block-size lowered through the
  // documented config-file layer, so oversize teams and every J05 pass occur on the samples.
  private val MaxBlock = 2
  private val confFile =
    Files.writeString(
      Files.createTempFile("scholarchain", ".conf"),
      "scholarchain.graph.oversize-team-threshold = 5\n" +
        s"scholarchain.entity-resolution.max-block-size = $MaxBlock\n"
    )
  private val env = Map(scholarchain.core.config.ConfigLoader.ConfigFileEnv -> confFile.toString)

  // The fictional record twice more, as full v3.0 API records (the fetch_orcid.sh format): with the
  // committed projection, three records share one orcid-identifier.path.
  private val orcidV3 = Files.writeString(
    Files.createTempFile(tempDir("scholarchain-orcid-v3"), "records", ".jsonl"),
    s"$orcidV3FictionalRecord\n$orcidV3FictionalRecord\n"
  )

  private val opts = PipelineOptions
    .parse(
      Seq(
        "--out",
        out.toString,
        "--code-version",
        "test-sha",
        "--dblp",
        sample("dblp/dblp-sample.xml").getPath,
        "--dblp-dtd",
        sample("dblp/dblp-2023-06-28.dtd").getPath,
        "--dblp-snapshot",
        "2026-10-03",
        "--openalex",
        sample("openalex/works-sample.jsonl").getPath,
        "--openalex-snapshot",
        "2026-11-03",
        "--crossref",
        sample("crossref/works-sample.jsonl").getPath,
        "--crossref-snapshot",
        "2026-11-03",
        "--orcid",
        s"${sample("orcid").getPath},$orcidV3",
        "--orcid-snapshot",
        "2026-11-03",
        "--reducers",
        "2"
      )
    )
    .fold(e => fail(e), identity)

  private def conf =
    val c = new Configuration()
    c.setLong("mapreduce.input.fileinputformat.split.maxsize", 4096) // several DBLP mappers
    c

  private lazy val first = Pipeline.run(conf, opts, env).fold(e => fail(e), identity)
  private lazy val second = Pipeline.run(conf, opts, env).fold(e => fail(e), identity)
  // The configured default is er-v2 (first, second); er-v1 stays selectable through the env layer.
  private val PolicyEnv = "SCHOLARCHAIN_ER_POLICY_VERSION"
  private lazy val v1Run = Pipeline
    .run(
      conf,
      opts.copy(out = tempDir("scholarchain-hadoop-v1").toString),
      env + (PolicyEnv -> "er-v1")
    )
    .fold(e => fail(e), identity)

  private def dir(r: PipelineResult, rel: String): JPath =
    JPath.of(r.runDir.toUri.getPath).resolve(rel)
  private def c(r: PipelineResult, job: String, name: String, group: String = Counters.Group) =
    r.counters(job).getOrElse(s"$group/$name", 0L)

  // Independent expectations from the raw sample XML.
  private val sampleXml = readFile(sample("dblp/dblp-sample.xml"), ISO_8859_1)
  private val teamSizes = """(?s)<(article|inproceedings)[ >].*?</\1>""".r
    .findAllIn(sampleXml)
    .map("<author[ >]".r.findAllIn(_).size)
    .toVector

  /** Bytes of a file, or of every file below a directory (Hadoop's checksum files excluded). */
  private def bytes(f: java.io.File): Long =
    if f.isDirectory then f.listFiles.toSeq.filterNot(_.getName.endsWith(".crc")).map(bytes).sum
    else f.length

  test("run ID: snapshots plus input fingerprints (path and bytes); manifest v3 records them") {
    assert(first.attempt == 1 && IdFormat.isRunId(first.runId))
    assert(first.runDir.toString.endsWith(s"snapshot=2026-10-03/run=${first.runId}"))
    def print(name: String, path: String) = s"input:$name=$path:${bytes(new java.io.File(path))}"
    val prints = Vector(
      print("dblp", opts.dblp.head),
      print("dblp-dtd", opts.dblpDtd),
      print("openalex", opts.openAlex.head),
      print("crossref", opts.crossref.head),
      print("orcid", opts.orcid(0)),
      print("orcid", opts.orcid(1))
    )
    val snapshots =
      Seq("dblp=2026-10-03", "openalex=2026-11-03", "crossref=2026-11-03", "orcid=2026-11-03")
    assert(
      first.runId == StableIds.runId(
        Pipeline.Stage,
        "test-sha",
        "config-v1",
        snapshots ++ prints,
        1
      )
    )
    val m = read[RunManifest](Files.readString(dir(first, "_manifest.json")))
    assert(m.schemaVersion == "hadoop-run-manifest-v3" && m.inputFingerprints == prints)
    assert(m.attempt == 1 && m.oversizeTeamThreshold == 5 && m.reducers == 2)
    assert(m.maxBlockSize == MaxBlock && m.maxConflictGroupSize == 100)
    assert(m.entityResolutionRules == "norm-v1.block-v1.feat-v1" && m.matchPolicyVersion == "er-v2")
    assert(
      m.thresholds == Map(
        "accept-threshold" -> 0.93,
        "reject-threshold" -> 0.35,
        "llm-band-min" -> 0.65,
        "llm-band-max" -> 0.93
      )
    )
    assert(scala.util.Try(java.time.Instant.parse(m.startedAt)).isSuccess)
    assert(Files.exists(dir(first, s"_counters/${Jobs.J09}.json")))
  }

  test("input fingerprints: a changed input size changes the run ID; a missing input fails") {
    val f = Files.writeString(Files.createTempFile("scholarchain-fp", ".jsonl"), "{}\n")
    val o = opts.copy(crossref = Seq(f.toString))
    val before = Pipeline.fingerprints(new Configuration(), o).fold(e => fail(e), identity)
    Files.writeString(f, "{}\n{}\n")
    val after = Pipeline.fingerprints(new Configuration(), o).fold(e => fail(e), identity)
    assert(before.contains(s"input:crossref=$f:3") && after.contains(s"input:crossref=$f:6"))
    def id(p: Vector[String]) = StableIds.runId(Pipeline.Stage, "x", "config-v1", p, 1)
    assert(id(before) != id(after))
    val missing = Pipeline.fingerprints(new Configuration(), o.copy(orcid = Seq(s"$f.absent")))
    assert(missing.left.exists(_.contains("input --orcid not found")))
  }

  test("J01 counters: 40 records in, 40 accepted, none rejected; works and mentions written") {
    assert(c(first, Jobs.J01, Counters.Input) == 40 && c(first, Jobs.J01, Counters.Accepted) == 40)
    assert(c(first, Jobs.J01, Counters.Rejected) == 0)
    // Standard counters are visible at zero, not missing.
    assert(
      Counters.Standard.forall(n => first.counters(Jobs.J01).contains(s"${Counters.Group}/$n"))
    )
    assert(gzLines(dir(first, s"${Jobs.J01}/works")).size == 40)
    assert(gzLines(dir(first, s"${Jobs.J01}/mentions")).size == teamSizes.sum)
  }

  test("J02 counters: 86 enrichment records in, 3 synthetic ORCID records rejected to rejects-v1") {
    assert(c(first, Jobs.J02, Counters.Input) == 86 && c(first, Jobs.J02, Counters.Accepted) == 83)
    // Three records of one ORCID iD (projection + two v3.0 copies) give one profile.
    assert(c(first, Jobs.J02, Counters.OrcidDuplicate) == 2)
    assert(c(first, Jobs.J02, Counters.Conflict) == 0)
    assert(c(first, Jobs.J02, Counters.Rejected) == 3)
    val rejects = gzLines(dir(first, s"${Jobs.J02}/${Reject.Dir}")).map(read[RejectRecord](_))
    assert(
      rejects.size == 3 && rejects.forall(r =>
        r.reason == "unsupported-identifier" && r.schemaVersion == Reject.SchemaVersion
      )
    )
    assert(rejects.forall(r => r.runId == first.runId && r.sourceDataset == SourceType.ORCID))
    assert(rejects.forall(_.sourceLocation.matches(".*/synthetic-orcid-records\\.jsonl@\\d+")))
    assert(gzLines(dir(first, s"${Jobs.J02}/works")).size == 80)
    assert(gzLines(dir(first, s"${Jobs.J02}/researcher-profiles")).size == 1)
  }

  test("J03+J04: every enrichment work matches its DBLP work by DOI; candidates validate") {
    val ms = gzLines(dir(first, s"${Jobs.J04}/matches")).map(read[MatchCandidate](_))
    val works = ms.filter(_.entityType == MatchEntityType.WORK)
    assert(works.size == 80 && works.forall(_.targetId.startsWith("dblp:")))
    assert(c(first, Jobs.J04, Counters.ExactMatch) == ms.size)
    assert(ms.forall(m => Validation.validate(m).isRight && m.provenance.runId == first.runId))
    assert(ms.exists(_.entityType == MatchEntityType.RESEARCHER))
  }

  test("J07+J08: one team per work, n(n-1)/2 pairs per team, oversize teams counted") {
    val teams = gzLines(dir(first, s"${Jobs.J08}/teams")).map(read[PublicationTeam](_))
    assert(teams.size == 40 && teams.map(_.teamSize).sorted == teamSizes.sorted)
    val expectedPairs = teamSizes.map(n => n * (n - 1) / 2).sum
    assert(c(first, Jobs.J08, Counters.EmittedPair) == expectedPairs)
    assert(gzLines(dir(first, s"${Jobs.J08}/pairs")).size == expectedPairs)
    assert(c(first, Jobs.J08, Counters.OversizeTeam) == teamSizes.count(_ > 5))
    val stats = gzLines(dir(first, s"${Jobs.J08}/stats")).map(ujson.read(_))
    assert(stats.map(_("largestTeamSize").num.toInt).max == teamSizes.max)
  }

  test("J09: Spark-ready partitioned outputs; every record validates and carries provenance") {
    val exported = dir(first, Jobs.J09)
    assert(gzLines(exported.resolve("works")).size == 120)
    assert(Files.isDirectory(exported.resolve("works/source_dataset=DBLP/publication_year=2009")))
    val pairs =
      gzLines(exported.resolve("coauthor-pair-candidates")).map(read[CoauthorPairCandidate](_))
    assert(pairs.size == teamSizes.map(n => n * (n - 1) / 2).sum)
    assert(
      pairs.forall(p =>
        Validation.validate(p).isRight && p.provenance.sourceDataset == SourceType.DBLP
      )
    )
    assert(gzLines(exported.resolve("publication-teams")).size == 40)
    assert(
      c(first, Jobs.J09, Counters.SchemaFailure) == 0 && c(first, Jobs.J09, Counters.Rejected) == 0
    )
  }

  test("a rerun of the same inputs gets attempt 2, a new run ID, and logically equal output") {
    assert(second.attempt == 2 && second.runId != first.runId)
    def startedAt(r: PipelineResult) =
      read[RunManifest](Files.readString(dir(r, "_manifest.json"))).startedAt
    def logical(r: PipelineResult, rel: String) =
      gzLines(dir(r, rel)).map(_.replace(r.runId, "RUN").replace(startedAt(r), "AT")).sorted
    val blocking = (0 until Blocking.Passes).flatMap(l =>
      Seq("blocks", "oversize").map(d => s"${Jobs.J05Pass(l)}/$d")
    ) ++ Seq(s"${Jobs.J06}/candidates", s"${Jobs.J06}/work-pairs", s"${Jobs.J10}/decisions")
    val compared =
      Seq(s"${Jobs.J09}", s"${Jobs.J02}/${Reject.Dir}", s"${Jobs.J04}/conflicts") ++ blocking
    for rel <- compared do assert(logical(first, rel) == logical(second, rel), rel)
    assert(first.counters == second.counters)
  }

  test("--attempt: parsed when positive; an existing attempt is refused, never overwritten") {
    assert(PipelineOptions.parse(Seq("--attempt", "0")).isLeft)
    val explicit = opts.copy(attempt = Some(1))
    assert(first.attempt == 1)
    val refused = Pipeline.run(conf, explicit, env)
    assert(refused.left.exists(_.contains("attempt 1 already exists")))
  }

  private def j05(r: PipelineResult, d: String) =
    (0 until Blocking.Passes).flatMap(l => gzLines(dir(r, s"${Jobs.J05Pass(l)}/$d"))).toVector

  test("J05: every pass together gives exactly the OversizePolicy.plan blocks of all works") {
    val works =
      (gzLines(dir(first, s"${Jobs.J01}/works")) ++ gzLines(dir(first, s"${Jobs.J02}/works")))
        .map(read[WorkRecord](_))
    val members = works.flatMap(Blocking.members)
    val plans = Seq(MatchEntityType.RESEARCHER, MatchEntityType.WORK).map(t =>
      OversizePolicy.plan[BlockMember](
        members.filter(_.entityType == t),
        _.id,
        _.keys,
        Blocking.levels(t),
        MaxBlock
      )
    )
    val blocks = j05(first, "blocks").map(read[BlockRecord](_))
    val oversize = j05(first, "oversize").map(read[OversizeBlockRecord](_))
    val jobBlocks = blocks.map(b => (b.level, b.key, b.members.map(_.id)))
    val planBlocks = plans.flatMap(_.blocks).map(b => (b.level, b.key, b.memberIds)).toVector
    assert(jobBlocks.sortBy(b => (b._1, b._2)) == planBlocks.sortBy(b => (b._1, b._2)))
    assert(
      oversize.map(o => (o.level, o.key, o.size)).sorted ==
        plans.flatMap(_.oversize).map(o => (o.level, o.key, o.size.toLong)).toVector.sorted
    )
    // Every pass ran on the samples, so re-keying and the last level are both exercised.
    assert((0 until Blocking.Passes).forall(l => first.counters.contains(Jobs.J05Pass(l))))
    def counted(n: String) = (0 until Blocking.Passes).map(l => c(first, Jobs.J05Pass(l), n)).sum
    assert(counted(Counters.OversizeBlock) == oversize.size && oversize.nonEmpty)
    assert(counted(Counters.BlockKept) == blocks.size && blocks.forall(_.members.size <= MaxBlock))
    assert(c(first, Jobs.J05Pass(0), Counters.MissingKey) == plans.map(_.missingKey.size).sum)
    assert(c(first, Jobs.J05Pass(0), Counters.Input) == works.size)
  }

  test("J06: one feature-carrying candidate per distinct pair of kept blocks; exported for Spark") {
    val blocks = j05(first, "blocks").map(read[BlockRecord](_))
    val meetings = blocks.flatMap(blk =>
      Blocking.pairs(blk)._1.map((x, y) => (blk.entityType, x.id, y.id) -> blk.key).toVector
    )
    val expected = meetings.map(_._1).distinct
    val cands = gzLines(dir(first, s"${Jobs.J06}/candidates")).map(read[MatchCandidate](_))
    assert(cands.map(m => (m.entityType, m.sourceId, m.targetId)).toSet == expected.toSet)
    assert(cands.size == expected.size)
    assert(c(first, Jobs.J06, Counters.MatchCandidate) == cands.size && cands.nonEmpty)
    assert(cands.forall(m => m.sourceId < m.targetId && Validation.validate(m).isRight))
    assert(cands.forall(_.provenance.policyVersion.contains(Blocking.RuleVersions)))
    assert(cands.exists(_.entityType == MatchEntityType.WORK))
    assert(cands.exists(_.features.contains("name_similarity")))
    // A pair that met in several r2 blocks keeps its smallest blocking key.
    val smallest = meetings.groupMapReduce(_._1)(_._2)((a, b) => if a < b then a else b)
    assert(cands.forall(m => smallest((m.entityType, m.sourceId, m.targetId)) == m.blockingKey))
    assert(c(first, Jobs.J06, Counters.Duplicate) == meetings.size - expected.size)
    assert(gzLines(dir(first, s"${Jobs.J09}/blocking-candidates")).size == cands.size)
  }

  test("policy version: an unknown configured version fails before any job or run directory") {
    val o = opts.copy(out = tempDir("scholarchain-hadoop-v3").toString)
    val r = Pipeline.run(conf, o, env + (PolicyEnv -> "er-v3"))
    assert(r.left.exists(_.contains("policy-version er-v3 is not implemented (er-v1, er-v2)")))
    assert(!Files.exists(JPath.of(o.out).resolve("snapshot=2026-10-03")))
  }

  test("J10 er-v2 (configured default): one decision per candidate, recomputed; exported") {
    checkDecisions(first, MatchPolicy.V2)
  }

  test("J10 er-v1 (env override): one decision per candidate, recomputed; manifest records it") {
    assert(
      read[RunManifest](
        Files.readString(dir(v1Run, "_manifest.json"))
      ).matchPolicyVersion == "er-v1"
    )
    checkDecisions(v1Run, MatchPolicy.V1)
    // Researcher rules are identical in er-v1 and er-v2, so researcher decisions are too.
    def researchers(r: PipelineResult) =
      gzLines(dir(r, s"${Jobs.J10}/decisions"))
        .map(read[MatchDecision](_))
        .filter(_.entityType == MatchEntityType.RESEARCHER)
        .map(d =>
          d.provenance.sourceRecordId ->
            (d.decision, d.rulesFired, d.reasonCodes, d.confidence, d.features)
        )
        .toMap
    assert(researchers(v1Run) == researchers(first) && researchers(first).nonEmpty)
  }

  // Review 1 finding 1 (fictional IDs): one ORCID on DBLP "Wei Wang 0001" (conf/x/A1) and
  // "Wei Wang 0002" (conf/y/B7), disjoint coauthors, added as a second --dblp input.
  private val wangOrcid = "0000-0001-2345-6789"
  private val wangXml = Files.writeString(
    tempDir("scholarchain-suffix").resolve("dblp-suffix.xml"),
    s"""<?xml version="1.0" encoding="ISO-8859-1"?>
       |<!DOCTYPE dblp SYSTEM "dblp-2023-06-28.dtd">
       |<dblp>
       |<inproceedings mdate="2026-01-01" key="conf/x/A1">
       |<author orcid="$wangOrcid">Wei Wang 0001</author>
       |<author>Alice Fictional</author>
       |<title>A first fictional paper.</title>
       |<year>2020</year>
       |<booktitle>X</booktitle>
       |</inproceedings>
       |<inproceedings mdate="2026-01-01" key="conf/y/B7">
       |<author orcid="$wangOrcid">Wei Wang 0002</author>
       |<author>Bob Fictional</author>
       |<title>A second fictional paper.</title>
       |<year>2021</year>
       |<booktitle>Y</booktitle>
       |</inproceedings>
       |</dblp>
       |""".stripMargin
  )
  private def wangRun(policy: String) = Pipeline
    .run(
      conf,
      opts.copy(
        out = tempDir(s"scholarchain-suffix-$policy").toString,
        dblp = opts.dblp :+ wangXml.toString
      ),
      env + (PolicyEnv -> policy)
    )
    .fold(e => fail(e), identity)
  private lazy val wangV2 = wangRun(MatchPolicy.V2)
  private lazy val wangV1 = wangRun(MatchPolicy.V1)
  private val (wangA, wangB) = ("dblp:conf/x/A1#1", "dblp:conf/y/B7#1")

  private def wangDecisions(r: PipelineResult) =
    gzLines(dir(r, s"${Jobs.J10}/decisions"))
      .map(read[MatchDecision](_))
      .filter(d => Set(wangA, wangB)(d.sourceId) || Set(wangA, wangB)(d.targetId))

  test("orcid suffix conflict er-v2: the group goes to conflicts/, is counted, decides CONFLICT") {
    val key = s"orcid:$wangOrcid"
    def cands(rel: String) = gzLines(dir(wangV2, rel)).map(read[MatchCandidate](_))
    assert(!cands(s"${Jobs.J04}/matches").exists(_.blockingKey == key))
    assert(
      cands(s"${Jobs.J04}/conflicts")
        .filter(_.blockingKey == key)
        .map(c => (c.sourceId, c.targetId)) == Vector((wangA, wangB))
    )
    assert(c(wangV2, Jobs.J04, Counters.OrcidSuffixConflict) == 1)
    val line = gzLines(dir(wangV2, s"${Jobs.J04}/suffix-conflicts")).map(ujson.read(_))
    assert(line.map(l => (l("identifier").str, l("groupSize").num.toInt)) == Vector((key, 2)))
    assert(line.forall(_("rule").str == MatchPolicy.Rule.OrcidGroupSuffixConflict))
    // J10: the exact-ID decisions (sourceRecordId `<key>|a|b`) are one EXACT_CONFLICT -> CONFLICT;
    // no mention matches the iD. Any blocked decision on the pair (r0:wang|w) is not ACCEPTED either.
    val ds = wangDecisions(wangV2)
    val exact = ds.filter(_.provenance.sourceRecordId.startsWith(key + "|"))
    assert(
      exact.map(d => (d.sourceId, d.targetId, d.decision)) == Vector(
        (wangA, wangB, MatchOutcome.CONFLICT)
      )
    )
    assert(exact.forall(_.rulesFired.contains(MatchPolicy.Rule.ExactGroupConflict)))
    assert(
      !ds.exists(d =>
        d.decision == MatchOutcome.ACCEPTED && Set(d.sourceId, d.targetId) == Set(wangA, wangB)
      )
    )
    checkDecisions(wangV2, MatchPolicy.V2)
  }

  test("orcid suffix conflict er-v1: the same input is unchanged, both mentions match the iD") {
    val key = s"orcid:$wangOrcid"
    def cands(rel: String) = gzLines(dir(wangV1, rel)).map(read[MatchCandidate](_))
    assert(
      cands(s"${Jobs.J04}/matches").filter(_.blockingKey == key).map(_.sourceId).sorted ==
        Vector(wangA, wangB)
    )
    assert(!cands(s"${Jobs.J04}/conflicts").exists(_.blockingKey == key))
    assert(c(wangV1, Jobs.J04, Counters.OrcidSuffixConflict) == 0)
    assert(gzLines(dir(wangV1, s"${Jobs.J04}/suffix-conflicts")).isEmpty)
    assert(
      wangDecisions(wangV1)
        .filter(_.targetId == key)
        .map(d => (d.sourceId, d.decision))
        .sortBy(_._1) ==
        Vector((wangA, MatchOutcome.ACCEPTED), (wangB, MatchOutcome.ACCEPTED))
    )
    checkDecisions(wangV1, MatchPolicy.V1)
  }

  /** J10 output of run `r` against an independent recomputation under policy `version`. */
  private def checkDecisions(first: PipelineResult, version: String): Unit =
    def cands(rel: String) = gzLines(dir(first, rel)).map(read[MatchCandidate](_))
    val matches = cands(s"${Jobs.J04}/matches")
    val conflicts = cands(s"${Jobs.J04}/conflicts")
    val blocked = cands(s"${Jobs.J06}/candidates")
    val pairs = gzLines(dir(first, s"${Jobs.J06}/work-pairs")).map(read[WorkPairInput](_))
    val blockedWorks = blocked.filter(_.entityType == MatchEntityType.WORK)
    assert(pairs.map(_.candidate).toSet == blockedWorks.toSet && pairs.nonEmpty)
    assert(c(first, Jobs.J06, Counters.WorkPair) == pairs.size)
    val ds = gzLines(dir(first, s"${Jobs.J10}/decisions")).map(read[MatchDecision](_))
    assert(ds.size == matches.size + conflicts.size + blocked.size)
    assert(c(first, Jobs.J10, Counters.Accepted) == ds.size)
    assert(c(first, Jobs.J10, Counters.DeferredWorkCandidate) == blockedWorks.size)
    assert(
      c(first, Jobs.J10, Counters.Input) ==
        ds.size + blockedWorks.size + c(first, Jobs.J10, Counters.Rejected)
    )
    assert(c(first, Jobs.J10, Counters.Rejected) == 0)
    // Independent recomputation with the entity-resolution functions and the J01/J02 works.
    val works =
      (gzLines(dir(first, s"${Jobs.J01}/works")) ++ gzLines(dir(first, s"${Jobs.J02}/works")))
        .map(read[WorkRecord](_))
        .map(w => w.workId -> w)
        .toMap
    val t = Thresholds(0.93, 0.35, 0.65, 0.93)
    def expected(c: MatchCandidate, o: CandidateOrigin) =
      if o != CandidateOrigin.BLOCKED then (MatchPolicy.decide(c, o, t, version), c.features)
      else
        val f =
          if c.entityType == MatchEntityType.RESEARCHER then c.features
          else c.features ++ WorkPairFeatures.work(works(c.sourceId), works(c.targetId), version)
        (MatchPolicy.decideBlocked(c.entityType, f, t, version), f)
    val all = matches.map((_, CandidateOrigin.EXACT_MATCH)) ++
      conflicts.map((_, CandidateOrigin.EXACT_CONFLICT)) ++
      blocked.map((_, CandidateOrigin.BLOCKED))
    val byRecord = ds.map(d => d.provenance.sourceRecordId -> d).toMap
    assert(byRecord.size == ds.size)
    val startedAt = read[RunManifest](Files.readString(dir(first, "_manifest.json"))).startedAt
    for (cand, origin) <- all do
      val d = byRecord(cand.provenance.sourceRecordId)
      val (v, f) = expected(cand, origin)
      assert(
        (d.decision, d.rulesFired, d.reasonCodes, d.confidence, d.features) ==
          (v.outcome, v.rulesFired, v.reasonCodes, v.confidence, f)
      )
      assert(
        (d.entityType, d.sourceId, d.targetId) == (cand.entityType, cand.sourceId, cand.targetId)
      )
      assert(Validation.validate(d).isRight && d.decidedAt == startedAt)
      assert(d.provenance.policyVersion.contains(version) && d.provenance.runId == first.runId)
      assert(d.promptVersion.isEmpty && d.provenance.modelVersion.isEmpty)
    // Counters per entity type, match path, and outcome.
    val counted = all
      .map((cand, o) =>
        s"${cand.entityType}.${expected(cand, o)._1.path}.${expected(cand, o)._1.outcome}"
      )
      .groupMapReduce(identity)(_ => 1L)(_ + _)
    val group = first.counters(Jobs.J10).collect {
      case (k, n) if k.startsWith(Counters.DecisionGroup + "/") =>
        k.stripPrefix(Counters.DecisionGroup + "/") -> n
    }
    assert(group == counted && group.values.sum == ds.size)
    assert(counted.keySet.exists(_.endsWith(".ACCEPTED")) && counted.size > 2)
    // J09 exports the decisions partitioned by entity type and outcome (the decision value).
    val exported = dir(first, s"${Jobs.J09}/match-decisions")
    assert(gzLines(exported).size == ds.size)
    for d <- ds.map(d => (d.entityType, d.decision)).distinct do
      val part = exported.resolve(s"entity_type=${d._1}/outcome=${d._2}")
      assert(gzLines(part).map(read[MatchDecision](_)).forall(x => (x.entityType, x.decision) == d))
