package scholarchain.spark

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import scholarchain.core.IdFormat

import scala.util.Try

/** Settings of one Spark job run: defaults from the resource `scholarchain-spark.conf`, each
  * overridable by a `--<key> <value>` flag. `--input` (the job's input run directory) and
  * `--code-version` are required; `--attempt` (default: the smallest unused attempt) and `--pairs`
  * (CommunityPathJob: a researcher-pairs CSV file; default: a seeded sample) are optional.
  */
final case class SparkSettings(
    input: String,
    out: String,
    codeVersion: String,
    attempt: Option[Int],
    master: String,
    shufflePartitions: Int,
    checkpointDir: String,
    canonicalPolicyVersion: String,
    ccMaxIterations: Int,
    minFreeDiskGiB: Long,
    pairs: Option[String],
    communityMaxIterations: Int,
    pathMaxDepth: Int,
    pathSamplePairs: Int,
    pathSampleSources: Int,
    sampleSeed: Long,
    summaryMinPublications: Int,
    summaryMinCommunitySize: Int
)

object SparkSettings:
  val Resource = "scholarchain-spark.conf"
  private val Defaulted = Seq(
    "out",
    "master",
    "shuffle-partitions",
    "checkpoint-dir",
    "canonical-policy-version",
    "cc-max-iterations",
    "min-free-disk-gib",
    "community-max-iterations",
    "path-max-depth",
    "path-sample-pairs",
    "path-sample-sources",
    "sample-seed",
    "summary-min-publications",
    "summary-min-community-size"
  )
  private val Known = Defaulted.toSet ++ Set("input", "code-version", "attempt", "pairs")

  def parse(args: Seq[String]): Either[String, SparkSettings] =
    if args.size % 2 != 0 then Left(s"flags come in --name value pairs: ${args.mkString(" ")}")
    else
      val pairs = args.grouped(2).map(p => p(0) -> p(1)).toSeq
      val unknown = pairs.map(_._1).filterNot(k => k.startsWith("--") && Known(k.drop(2)))
      if unknown.nonEmpty then Left(s"unknown flags: ${unknown.mkString(", ")}")
      else
        val f = pairs.map((k, v) => k.drop(2) -> v).toMap
        val c = ConfigFactory
          .parseResources(
            getClass.getClassLoader,
            Resource,
            ConfigParseOptions.defaults().setAllowMissing(false)
          )
          .getConfig("scholarchain-spark")
        def s(k: String) = f.getOrElse(k, c.getString(k))
        for
          input <- f.get("input").toRight("--input <hadoop run directory> is required")
          // No default (ruling 24): the git commit SHA on EMR, src-<hash of the sources> locally.
          code <- f
            .get("code-version")
            .filter(_.trim.nonEmpty)
            .toRight("--code-version is required (the git SHA or src-<hash>; no default)")
          r <- Try(
            SparkSettings(
              input,
              s("out"),
              code,
              f.get("attempt").map(_.toInt),
              s("master"),
              s("shuffle-partitions").toInt,
              s("checkpoint-dir"),
              s("canonical-policy-version"),
              s("cc-max-iterations").toInt,
              s("min-free-disk-gib").toLong,
              f.get("pairs"),
              s("community-max-iterations").toInt,
              s("path-max-depth").toInt,
              s("path-sample-pairs").toInt,
              s("path-sample-sources").toInt,
              s("sample-seed").toLong,
              s("summary-min-publications").toInt,
              s("summary-min-community-size").toInt
            )
          ).toEither.left.map(e => s"bad setting: ${e.getMessage}")
          _ <- Either.cond(r.attempt.forall(_ >= 1), (), "--attempt must be >= 1")
          _ <- Either.cond(
            Seq(
              r.shufflePartitions,
              r.ccMaxIterations,
              r.communityMaxIterations,
              r.pathMaxDepth,
              r.pathSamplePairs,
              r.pathSampleSources,
              r.summaryMinPublications,
              r.summaryMinCommunitySize
            ).forall(_ >= 1),
            (),
            "partitions, iterations, depths, and sample sizes must be >= 1"
          )
          _ <- Either.cond(
            IdFormat.Version.matches(r.canonicalPolicyVersion),
            (),
            s"bad canonical-policy-version ${r.canonicalPolicyVersion}"
          )
        yield r

/** Job-specific flags that SparkSettings does not know (BenchmarkPathJob, StoreConversionJob). */
object JobArgs:
  /** Splits the `--name value` pairs whose name is in `names` from the rest, which go to
    * SparkSettings.parse unchanged.
    */
  def split(args: Seq[String], names: Set[String]): (Map[String, String], Seq[String]) =
    val (mine, rest) = args
      .grouped(2)
      .toSeq
      .partition(p => p.size == 2 && p(0).startsWith("--") && names(p(0).drop(2)))
    (mine.map(p => p(0).drop(2) -> p(1)).toMap, rest.flatten)
