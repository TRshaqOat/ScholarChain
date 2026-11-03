package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.*
import scholarchain.entityresolution.OversizePolicy
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
        sample("orcid").getPath,
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

  test("run directory carries the snapshot and run ID; the manifest records attempt 1") {
    assert(first.attempt == 1 && IdFormat.isRunId(first.runId))
    assert(first.runDir.toString.endsWith(s"snapshot=2026-10-03/run=${first.runId}"))
    assert(
      first.runId == StableIds.runId(
        Pipeline.Stage,
        "test-sha",
        "config-v1",
        Seq("dblp=2026-10-03", "openalex=2026-11-03", "crossref=2026-11-03", "orcid=2026-11-03"),
        1
      )
    )
    val m = read[RunManifest](Files.readString(dir(first, "_manifest.json")))
    assert(m.attempt == 1 && m.oversizeTeamThreshold == 5 && m.reducers == 2)
    assert(m.maxBlockSize == MaxBlock && m.maxConflictGroupSize == 100)
    assert(m.entityResolutionRules == "norm-v1.block-v1.feat-v1")
    assert(Files.exists(dir(first, s"_counters/${Jobs.J09}.json")))
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

  test("J02 counters: 84 enrichment records in, 3 synthetic ORCID records rejected to rejects-v1") {
    assert(c(first, Jobs.J02, Counters.Input) == 84 && c(first, Jobs.J02, Counters.Accepted) == 81)
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
    def logical(r: PipelineResult, rel: String) =
      gzLines(dir(r, rel)).map(_.replace(r.runId, "RUN")).sorted
    val blocking = (0 until Blocking.Passes).flatMap(l =>
      Seq("blocks", "oversize").map(d => s"${Jobs.J05Pass(l)}/$d")
    ) :+ s"${Jobs.J06}/candidates"
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
