package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.TaskAttemptContext
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import scholarchain.core.*
import upickle.default.ReadWriter

/** Counter names. Every job reports into group [[Counters.Group]]; per-output record counts go to
  * [[Counters.OutputGroup]] (counter name = output directory).
  */
object Counters:
  val Group = "scholarchain"
  val OutputGroup = "scholarchain.outputs"
  val Input = "INPUT"
  val Accepted = "ACCEPTED"
  val Rejected = "REJECTED"
  val Duplicate = "DUPLICATE"
  val Conflict = "CONFLICT"
  val OversizeBlock = "OVERSIZE_BLOCK"
  val OversizeConflict = "OVERSIZE_CONFLICT"
  val OversizeTeam = "OVERSIZE_TEAM"
  val EmittedPair = "EMITTED_PAIR"
  val MissingKey = "MISSING_KEY"
  val SchemaFailure = "SCHEMA_FAILURE"
  val InvalidOrcid = "INVALID_ORCID"
  val RepeatedAuthorName = "REPEATED_AUTHOR_NAME"
  val ExactMatch = "EXACT_MATCH"
  val ExactUnmatched = "EXACT_UNMATCHED"
  val BlockMember = "BLOCK_MEMBER"
  val BlockKept = "BLOCK_KEPT"
  val BlockRekeyed = "BLOCK_REKEYED"
  val SameWorkPairSkipped = "SAME_WORK_PAIR_SKIPPED"
  val MatchCandidate = "MATCH_CANDIDATE"

  /** Counters every job reports, registered at zero so they are visible even when never hit. */
  val Standard: Seq[String] =
    Seq(
      Input,
      Accepted,
      Rejected,
      Duplicate,
      Conflict,
      OversizeBlock,
      OversizeConflict,
      OversizeTeam,
      EmittedPair,
      MissingKey,
      SchemaFailure
    )

  def register(ctx: TaskAttemptContext): Unit = Standard.foreach(inc(ctx, _, 0))

  def inc(ctx: TaskAttemptContext, name: String, n: Long = 1): Unit =
    ctx.getCounter(Group, name).increment(n)

/** Hadoop configuration keys the driver sets for every task (tasks read them in setup). */
object Keys:
  val RunId = "scholarchain.run-id"
  val JobName = "scholarchain.job"
  val DblpDtd = "scholarchain.dblp.dtd"
  val OversizeTeamThreshold = "scholarchain.graph.oversize-team-threshold"
  val GraphPolicyVersion = "scholarchain.graph.policy-version"
  val MaxBlockSize = "scholarchain.entity-resolution.max-block-size"
  val MaxConflictGroupSize = "scholarchain.entity-resolution.max-conflict-group-size"

  /** J05 pass number = the blocking level that pass groups by. */
  val BlockLevel = "scholarchain.j05.level"

  /** J06 side input: comma-separated venues/ directories of J01 and J02. */
  val VenueDirs = "scholarchain.j06.venue-dirs"

  /** Snapshot label of derived records (the DBLP seed snapshot). */
  val DerivedSnapshot = "scholarchain.snapshot.derived"
  def snapshot(ds: SourceType): String = s"scholarchain.snapshot.${ds.toString.toLowerCase}"

  def required(conf: Configuration, key: String): String =
    Option(conf.get(key)).filter(_.trim.nonEmpty).getOrElse(sys.error(s"missing job setting $key"))

/** Run-scoped values every pure function needs; built once per task from the configuration. */
final case class RunContext(
    job: String,
    runId: String,
    snapshots: Map[SourceType, String],
    derivedSnapshot: String,
    graphPolicyVersion: String
):
  def provenance(ds: SourceType, recordId: String): Provenance =
    Provenance(
      SchemaVersions.Provenance,
      ds,
      recordId,
      if ds == SourceType.DERIVED then derivedSnapshot else snapshots(ds),
      runId,
      None,
      None
    )

object RunContext:
  def from(conf: Configuration): RunContext =
    RunContext(
      Keys.required(conf, Keys.JobName),
      Keys.required(conf, Keys.RunId),
      Seq(SourceType.DBLP, SourceType.OPENALEX, SourceType.CROSSREF, SourceType.ORCID)
        .flatMap(ds => Option(conf.get(Keys.snapshot(ds))).map(ds -> _))
        .toMap,
      Keys.required(conf, Keys.DerivedSnapshot),
      Keys.required(conf, Keys.GraphPolicyVersion)
    )

/** Versioned reject-record format (owned by hadoop-engineer). One line per rejected input record.
  *
  * @param reason
  *   stable reason code (see JOBS.md "Reject conditions")
  * @param sourceLocation
  *   file and byte offset of the input record (`<path>@<offset>`), or `offset:<n>` when the job
  *   reads several inputs through MultipleInputs
  * @param rawExcerpt
  *   the first [[Reject.ExcerptChars]] characters of the input record
  */
final case class RejectRecord(
    schemaVersion: String,
    job: String,
    reason: String,
    sourceDataset: SourceType,
    sourceLocation: String,
    sourceRecordId: Option[String],
    detail: String,
    rawExcerpt: String,
    snapshot: String,
    runId: String
) derives ReadWriter

/** Why one input record was rejected; counter is the extra failure counter besides REJECTED. */
final case class Reject(
    reason: String,
    detail: String,
    sourceRecordId: Option[String] = None,
    counter: Option[String] = None
):
  def record(ctx: RunContext, ds: SourceType, location: String, raw: String): RejectRecord =
    RejectRecord(
      Reject.SchemaVersion,
      ctx.job,
      reason,
      ds,
      location,
      sourceRecordId,
      detail,
      raw.take(Reject.ExcerptChars),
      ctx.snapshots.getOrElse(ds, ctx.derivedSnapshot),
      ctx.runId
    )

object Reject:
  val SchemaVersion = "hadoop-reject-v1"
  val Dir = "rejects-v1"
  val ExcerptChars = 2000
  def schema(detail: String, id: Option[String] = None): Reject =
    Reject("schema-validation", detail, id, Some(Counters.SchemaFailure))
  def malformed(detail: String, id: Option[String] = None): Reject =
    Reject("malformed-record", detail, id, Some(Counters.SchemaFailure))
  def missingKey(detail: String): Reject =
    Reject("missing-key", detail, None, Some(Counters.MissingKey))

/** One shuffle record: `kind` names the output directory, `id` the record's stable ID. */
final case class Keyed(kind: String, id: String, json: String):
  def key: Text = new Text(s"$kind\t$id")

object Keyed:
  def of[A: ReadWriter: Validator](kind: String, id: String, a: A): Either[Reject, Keyed] =
    Validation.validate(a) match
      case Right(_)   => Right(Keyed(kind, id, WireCodec.encode(a)))
      case Left(errs) => Left(Reject.schema(s"$kind $id: ${errs.mkString("; ")}", Some(id)))

  /** Splits a shuffle key into (kind, id). */
  def split(key: Text): (String, String) =
    val s = key.toString
    val i = s.indexOf('\t')
    (s.take(i), s.drop(i + 1))

  def sequence(xs: Seq[Either[Reject, Keyed]]): Either[Reject, Vector[Keyed]] =
    xs.collectFirst { case Left(r) => r }.toLeft(xs.collect { case Right(k) => k }.toVector)

/** Writes JSON lines to `<job output>/<dir>/part-*` and counts them per directory. */
final class Outputs(ctx: TaskAttemptContext):
  private val mos = new MultipleOutputs[NullWritable, Text](
    ctx.asInstanceOf[org.apache.hadoop.mapreduce.TaskInputOutputContext[?, ?, NullWritable, Text]]
  )
  def write(dir: String, json: String): Unit =
    mos.write(NullWritable.get, new Text(json), s"$dir/part")
    ctx.getCounter(Counters.OutputGroup, dir.takeWhile(_ != '/')).increment(1)
  def close(): Unit = mos.close()
