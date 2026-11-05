package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.util.GenericOptionsParser
import scholarchain.core.{SourceType, StableIds}
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import scholarchain.entityresolution.MatchPolicy
import upickle.default.{ReadWriter, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** Pipeline arguments. Every path may be local or `s3a://`; list arguments are comma-separated. */
final case class PipelineOptions(
    out: String,
    codeVersion: String,
    dblp: Seq[String],
    dblpDtd: String,
    dblpSnapshot: String,
    openAlex: Seq[String],
    openAlexSnapshot: String,
    crossref: Seq[String],
    crossrefSnapshot: String,
    orcid: Seq[String],
    orcidSnapshot: String,
    reducers: Int,
    attempt: Option[Int] = None
)

object PipelineOptions:
  val Usage: String =
    """usage: Pipeline [hadoop -D options] --out <root> --code-version <git sha>
      |  --dblp <xml[,xml]> --dblp-dtd <dtd> --dblp-snapshot <YYYY-MM-DD>
      |  --openalex <jsonl[,..]> --openalex-snapshot <date> --crossref <jsonl[,..]> --crossref-snapshot <date>
      |  --orcid <jsonl[,..]> --orcid-snapshot <date> [--reducers <n, default 2>]
  [--attempt <n >= 1; default: the smallest unused attempt>]""".stripMargin

  def parse(args: Seq[String]): Either[String, PipelineOptions] =
    val pairs = args.grouped(2).toVector
    if args.size % 2 != 0 || pairs.exists(!_.head.startsWith("--")) then Left(Usage)
    else
      val m = pairs.map(p => p(0).drop(2) -> p(1)).toMap
      def req(k: String) = m.get(k).filter(_.trim.nonEmpty).toRight(s"missing --$k\n$Usage")
      def list(k: String) = req(k).map(_.split(',').toSeq.map(_.trim).filter(_.nonEmpty))
      for
        out <- req("out"); code <- req("code-version")
        dblp <- list("dblp"); dtd <- req("dblp-dtd"); ds <- req("dblp-snapshot")
        oa <- list("openalex"); oas <- req("openalex-snapshot")
        cr <- list("crossref"); crs <- req("crossref-snapshot")
        or <- list("orcid"); ors <- req("orcid-snapshot")
        reducers <- m
          .get("reducers")
          .fold(Right(2))(r => r.toIntOption.filter(_ >= 1).toRight(s"bad --reducers $r"))
        attempt <- m.get("attempt") match
          case None    => Right(None)
          case Some(a) => a.toIntOption.filter(_ >= 1).map(Some(_)).toRight(s"bad --attempt $a")
      yield PipelineOptions(out, code, dblp, dtd, ds, oa, oas, cr, crs, or, ors, reducers, attempt)

/** The immutable run manifest written to `<run dir>/_manifest.json` before any job starts. */
final case class RunManifest(
    schemaVersion: String,
    runId: String,
    stage: String,
    codeVersion: String,
    configVersion: String,
    attempt: Int,
    inputSnapshots: Vector[String],
    inputFingerprints: Vector[String],
    inputs: Map[String, Vector[String]],
    reducers: Int,
    oversizeTeamThreshold: Int,
    graphPolicyVersion: String,
    maxBlockSize: Int,
    maxConflictGroupSize: Int,
    entityResolutionRules: String,
    matchPolicyVersion: String,
    thresholds: Map[String, Double],
    startedAt: String
) derives ReadWriter

final case class PipelineResult(
    runId: String,
    attempt: Int,
    runDir: Path,
    counters: Map[String, Map[String, Long]]
)

/** Driver for the job chain J01 -> J02 -> J03+J04 -> J05 (pass 0..2) -> J06 -> J10 -> J07+J08 ->
  * J09 (sequential). A J05 pass runs only when the pass before it re-keyed at least one block.
  *
  * Run ID: StableIds.runId("hadoop-pipeline", codeVersion, configVersion, input snapshots ++ input
  * fingerprints, attempt). A fingerprint is `input:<name>=<path as given>:<bytes>` (bytes: the file
  * length, or the total of a directory), so changed input data gives a new run ID. The attempt is
  * `--attempt` when given (run scripts pass it; the run fails if that attempt's run directory
  * exists), else the smallest n >= 1 whose run directory does not exist yet, so every rerun of the
  * same inputs gets a new attempt and a new run directory. Output layout:
  * `<out>/snapshot=<dblp snapshot>/run=<runId>/<job>/<dir>/part-r-NNNNN.gz`.
  */
object Pipeline:
  val Stage = "hadoop-pipeline"

  def main(args: Array[String]): Unit =
    val conf = new Configuration()
    val rest = new GenericOptionsParser(conf, args).getRemainingArgs.toSeq
    PipelineOptions.parse(rest).flatMap(run(conf, _, sys.env)) match
      case Left(err) =>
        System.err.println(err); sys.exit(1)
      case Right(r) =>
        println(s"runId=${r.runId} attempt=${r.attempt} runDir=${r.runDir}")
        r.counters.toSeq
          .sortBy(_._1)
          .foreach((job, cs) => cs.toSeq.sorted.foreach((k, v) => println(s"$job $k=$v")))

  def run(
      conf: Configuration,
      o: PipelineOptions,
      env: Map[String, String]
  ): Either[String, PipelineResult] =
    for
      cfg <- ConfigLoader.load(env).left.map(_.mkString("; "))
      _ <- Either.cond(
        MatchPolicy.Versions.contains(cfg.entityResolution.policyVersion),
        (),
        s"configured entity-resolution.policy-version ${cfg.entityResolution.policyVersion} " +
          s"is not implemented (${MatchPolicy.Versions.mkString(", ")})"
      )
      prints <- fingerprints(conf, o)
      result <- claim(conf, o, cfg, prints)
    yield result

  /** `input:<name>=<path as given>:<bytes>` for every input path, in argument order. */
  def fingerprints(conf: Configuration, o: PipelineOptions): Either[String, Vector[String]] =
    val named = Vector(
      "dblp" -> o.dblp,
      "dblp-dtd" -> Seq(o.dblpDtd),
      "openalex" -> o.openAlex,
      "crossref" -> o.crossref,
      "orcid" -> o.orcid
    ).flatMap((n, ps) => ps.map(n -> _))
    named.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) {
      case (Right(acc), (n, p)) =>
        val path = new Path(p)
        val fs = path.getFileSystem(conf)
        if !fs.exists(path) then Left(s"input --$n not found: $p")
        else Right(acc :+ s"input:$n=$p:${fs.getContentSummary(path).getLength}")
      case (failed, _) => failed
    }

  /** Chooses the attempt and its run ID; an attempt whose run directory exists is refused. */
  private def claim(
      base: Configuration,
      o: PipelineOptions,
      cfg: ScholarChainConfig,
      prints: Vector[String]
  ): Either[String, PipelineResult] =
    val configVersion = cfg.configVersion
    val snapshots = Vector(
      s"dblp=${o.dblpSnapshot}",
      s"openalex=${o.openAlexSnapshot}",
      s"crossref=${o.crossrefSnapshot}",
      s"orcid=${o.orcidSnapshot}"
    )
    val root = new Path(new Path(o.out), s"snapshot=${o.dblpSnapshot}")
    val fs = root.getFileSystem(base)
    def idFor(a: Int) =
      StableIds.runId(Stage, o.codeVersion, configVersion, snapshots ++ prints, a)
    def runDirFor(a: Int) = new Path(root, s"run=${idFor(a)}")
    val attempt = o.attempt.getOrElse(LazyList.from(1).find(a => !fs.exists(runDirFor(a))).get)
    if fs.exists(runDirFor(attempt)) then
      Left(s"attempt $attempt already exists: ${runDirFor(attempt)}; outputs are never overwritten")
    else execute(base, o, fs, root, attempt, idFor(attempt), configVersion, snapshots, prints, cfg)

  private def execute(
      base: Configuration,
      o: PipelineOptions,
      fs: FileSystem,
      root: Path,
      attempt: Int,
      runId: String,
      configVersion: String,
      snapshots: Vector[String],
      prints: Vector[String],
      cfg: ScholarChainConfig
  ): Either[String, PipelineResult] =
    val (threshold, graphPolicy) = (cfg.graph.oversizeTeamThreshold, cfg.graph.policyVersion)
    val er = cfg.entityResolution
    val (maxBlock, maxConflict) = (er.maxBlockSize, er.maxConflictGroupSize)
    val thresholds = Seq(
      Keys.AcceptThreshold -> er.acceptThreshold,
      Keys.RejectThreshold -> er.rejectThreshold,
      Keys.LlmBandMin -> er.llmBandMin,
      Keys.LlmBandMax -> er.llmBandMax
    )
    // One decision instant per run: every J10 decision of the run carries it (also in the manifest).
    val startedAt = Instant.now().toString
    val runDir = new Path(root, s"run=$runId")
    val manifest = RunManifest(
      "hadoop-run-manifest-v3",
      runId,
      Stage,
      o.codeVersion,
      configVersion,
      attempt,
      snapshots.sorted,
      prints,
      Map(
        "dblp" -> o.dblp.toVector,
        "dblp-dtd" -> Vector(o.dblpDtd),
        "openalex" -> o.openAlex.toVector,
        "crossref" -> o.crossref.toVector,
        "orcid" -> o.orcid.toVector
      ),
      o.reducers,
      threshold,
      graphPolicy,
      maxBlock,
      maxConflict,
      Blocking.RuleVersions,
      er.policyVersion,
      thresholds.map((k, v) => k.stripPrefix("scholarchain.entity-resolution.") -> v).toMap,
      startedAt
    )
    // create(overwrite = false) claims the attempt; a concurrent run with the same attempt fails here.
    writeFile(
      fs,
      new Path(runDir, "_manifest.json"),
      write(manifest, indent = 2),
      overwrite = false
    )

    val conf = new Configuration(base)
    conf.set(Keys.RunId, runId)
    conf.set(Keys.DblpDtd, o.dblpDtd)
    conf.set(Keys.DerivedSnapshot, o.dblpSnapshot)
    conf.set(Keys.GraphPolicyVersion, graphPolicy)
    conf.setInt(Keys.OversizeTeamThreshold, threshold)
    conf.setInt(Keys.MaxBlockSize, maxBlock)
    conf.setInt(Keys.MaxConflictGroupSize, maxConflict)
    thresholds.foreach((k, v) => conf.set(k, v.toString))
    conf.set(Keys.MatchPolicyVersion, er.policyVersion)
    conf.set(Keys.DecidedAt, startedAt)
    conf.set(Keys.snapshot(SourceType.DBLP), o.dblpSnapshot)
    conf.set(Keys.snapshot(SourceType.OPENALEX), o.openAlexSnapshot)
    conf.set(Keys.snapshot(SourceType.CROSSREF), o.crossrefSnapshot)
    conf.set(Keys.snapshot(SourceType.ORCID), o.orcidSnapshot)

    def dir(job: String) = new Path(runDir, job)
    def subdirs(job: String, names: Set[String]) =
      if !fs.exists(dir(job)) then Vector.empty
      else
        fs.listStatus(dir(job))
          .toVector
          .filter(s => s.isDirectory && names(s.getPath.getName))
          .map(_.getPath)
          .sortBy(_.toString)
    val exportable =
      Set(
        "works",
        "mentions",
        "venues",
        "institutions",
        "topics",
        "edges",
        "researcher-profiles",
        "matches",
        "conflicts",
        "teams",
        "pairs",
        "candidates",
        "decisions"
      )
    val paths = (xs: Seq[String]) => xs.map(new Path(_))

    val passes = (0 until Blocking.Passes).map(Jobs.J05Pass)
    // Pass 0 reads every work; pass k reads pass k-1 rekey/ and is skipped when that is empty.
    def j05(level: Int): Option[Job] =
      val in =
        if level == 0 then subdirs(Jobs.J01, Set("works")) ++ subdirs(Jobs.J02, Set("works"))
        else subdirs(passes(level - 1), Set("rekey"))
      Option.when(in.nonEmpty)(
        Jobs.blockingKeys(conf, in, dir(passes(level)), o.reducers, level)
      )
    def j06(): Option[Job] =
      val blocks = passes.flatMap(subdirs(_, Set("blocks")))
      val venues = subdirs(Jobs.J01, Set("venues")) ++ subdirs(Jobs.J02, Set("venues"))
      Option.when(blocks.nonEmpty)(
        Jobs.candidateJoin(conf, blocks, venues, dir(Jobs.J06), o.reducers)
      )

    val chain: Seq[(String, () => Option[Job])] = Seq(
      Jobs.J01 -> (() => Some(Jobs.dblpExtract(conf, paths(o.dblp), dir(Jobs.J01), o.reducers))),
      Jobs.J02 -> (() =>
        Some(
          Jobs.sourceNormalize(
            conf,
            paths(o.openAlex),
            paths(o.crossref),
            paths(o.orcid),
            dir(Jobs.J02),
            o.reducers
          )
        )
      ),
      Jobs.J04 -> (() =>
        Some(
          Jobs.exactIdentifier(
            conf,
            subdirs(Jobs.J01, Set("works", "mentions")) ++
              subdirs(Jobs.J02, Set("works", "mentions")),
            dir(Jobs.J04),
            o.reducers
          )
        )
      )
    ) ++ passes.indices.map(l => passes(l) -> (() => j05(l))) ++ Seq(
      Jobs.J06 -> (() => j06()),
      Jobs.J10 -> (() =>
        val in = subdirs(Jobs.J04, Set("matches", "conflicts")) ++
          subdirs(Jobs.J06, Set("candidates", "work-pairs"))
        Option.when(in.nonEmpty)(Jobs.matchDecisions(conf, in, dir(Jobs.J10)))
      ),
      Jobs.J08 -> (() =>
        Some(Jobs.teamPairs(conf, subdirs(Jobs.J01, Set("works")), dir(Jobs.J08), o.reducers))
      ),
      Jobs.J09 -> (() =>
        Some(
          Jobs.graphExport(
            conf,
            Seq(Jobs.J01, Jobs.J02, Jobs.J04, Jobs.J06, Jobs.J10, Jobs.J08)
              .flatMap(subdirs(_, exportable)),
            dir(Jobs.J09),
            o.reducers
          )
        )
      )
    )
    chain
      .foldLeft[Either[String, Map[String, Map[String, Long]]]](Right(Map.empty)) {
        case (Right(acc), (name, mk)) =>
          mk() match
            case None                                       => Right(acc) // skipped: no input
            case Some(job) if !job.waitForCompletion(false) =>
              Left(s"job $name failed (run $runId, ${dir(name)})")
            case Some(job) =>
              val cs = counters(job)
              writeFile(
                fs,
                new Path(runDir, s"_counters/$name.json"),
                write(cs, indent = 2),
                overwrite = false
              )
              Right(acc + (name -> cs))
        case (failed, _) => failed
      }
      .map(PipelineResult(runId, attempt, runDir, _))

  /** ScholarChain counters of a finished job, as "group/NAME" -> value (outputs group uses dir
    * names).
    */
  def counters(job: Job): Map[String, Long] =
    job.getCounters.asScala
      .filter(_.getName.startsWith(Counters.Group))
      .flatMap(g => g.asScala.map(c => s"${g.getName}/${c.getName}" -> c.getValue))
      .toMap

  private def writeFile(fs: FileSystem, p: Path, s: String, overwrite: Boolean): Unit =
    val out = fs.create(p, overwrite)
    try out.write(s.getBytes(UTF_8))
    finally out.close()
