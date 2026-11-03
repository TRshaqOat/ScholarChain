package scholarchain.hadoop

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.{
  CompressionCodec,
  CompressionCodecFactory,
  DefaultCodec,
  GzipCodec
}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, TextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputFormat, LazyOutputFormat, TextOutputFormat}
import org.apache.hadoop.mapreduce.lib.partition.HashPartitioner
import org.apache.hadoop.mapreduce.{Job, Mapper, Reducer, TaskInputOutputContext}
import scholarchain.core.*
import upickle.default.{ReadWriter, read, write}

import java.nio.charset.StandardCharsets.ISO_8859_1
import scala.jdk.CollectionConverters.*

// Every mapper emits (Text "<kind>\t<id>" or "<identifier>", Text JSON); every reducer writes JSON
// lines through Outputs into <job output>/<dir>/part-r-NNNNN.gz. Tasks keep only per-task state
// built in setup (no shared mutable state); reducers sort their values, so output does not depend
// on shuffle order. Contracts per job are documented in modules/hadoop/JOBS.md.

private type Ctx = TaskInputOutputContext[?, ?, Text, Text]

private object Tasks:
  def emit(ctx: Ctx, k: Keyed): Unit = ctx.write(k.key, new Text(k.json))

  def reject(ctx: Ctx, r: Reject, rec: RejectRecord): Unit =
    Counters.inc(ctx, Counters.Rejected)
    r.counter.foreach(Counters.inc(ctx, _))
    ctx.write(new Text(s"${Reject.Dir}\t${rec.sourceLocation}"), new Text(write(rec)))

  def sortedValues(values: java.lang.Iterable[Text]): Vector[String] =
    values.asScala.map(_.toString).toVector.sorted

  /** The output directory name an input file came from (`.../<dir>/part-r-00000.gz`). */
  def inputDir(ctx: Mapper[?, ?, ?, ?]#Context): String =
    ctx.getInputSplit.asInstanceOf[FileSplit].getPath.getParent.getName

  def decode[A: ReadWriter: Validator](json: String): Either[Reject, A] =
    WireCodec.decode[A](json).left.map(es => Reject.schema(es.mkString("; ")))

  def statsLine(job: String, fields: (String, ujson.Value)*): String =
    ujson.write(ujson.Obj.from(("job" -> ujson.Str(job)) +: fields))

  /** Every line of the part files directly in dir (gzip or plain), in file-name order. */
  def readLines(conf: Configuration, dir: Path): Vector[String] =
    val fs = dir.getFileSystem(conf)
    val codecs = new CompressionCodecFactory(conf)
    if !fs.exists(dir) then Vector.empty
    else
      fs.listStatus(dir)
        .toVector
        .map(_.getPath)
        .filter(_.getName.startsWith("part-"))
        .sortBy(_.toString)
        .flatMap { p =>
          val raw = fs.open(p)
          val in =
            Option(codecs.getCodec(p)).fold[java.io.InputStream](raw)(_.createInputStream(raw))
          val src = scala.io.Source.fromInputStream(in, "UTF-8")
          try src.getLines().filter(_.nonEmpty).toVector
          finally src.close()
        }

  /** Keeps the larger (size, name), ties broken by the smaller name (deterministic stats). */
  def larger(a: (Long, String), size: Long, name: String): (Long, String) =
    if size > a._1 || (size == a._1 && name < a._2) then (size, name) else a

// ---- J01 DblpExtractJob -------------------------------------------------------------------------

/** Map: (LongWritable offset, Text record XML) -> (Text "works\t<workId>" | "venues\t<venueId>" |
  * "rejects-v1\t<file>@<offset>", Text JSON).
  */
class DblpExtractMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var parser: DblpParser = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized
  private var file = ""

  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    val conf = ctx.getConfiguration
    run = RunContext.from(conf)
    val dtd = new Path(Keys.required(conf, Keys.DblpDtd))
    val in = dtd.getFileSystem(conf).open(dtd)
    val text = try new String(in.readAllBytes(), ISO_8859_1)
    finally in.close()
    parser = new DblpParser(DblpEntities.parseDtd(text))
    file = ctx.getInputSplit.asInstanceOf[FileSplit].getPath.toString

  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    Counters.inc(ctx, Counters.Input)
    val xml = value.toString
    parser.parse(xml, run) match
      case Left(r) =>
        Tasks.reject(ctx, r, r.record(run, SourceType.DBLP, s"$file@${offset.get}", xml))
      case Right(p) =>
        Counters.inc(ctx, Counters.Accepted)
        Tasks.emit(ctx, p.work)
        p.venue.foreach(Tasks.emit(ctx, _))
        Counters.inc(ctx, Counters.InvalidOrcid, p.invalidOrcids)
        Counters.inc(ctx, Counters.RepeatedAuthorName, p.repeatedAuthorNames)

/** Shared by J01 and J02. Reduce: (Text "<kind>\t<id>", [Text JSON]) -> JSON lines in <kind>/.
  * works: identical repeats count DUPLICATE; differing repeats count CONFLICT, the smallest record
  * is kept and the others go to rejects-v1/; each kept work also writes its authors to mentions/.
  * Enrichment entities (venues, institutions, topics, edges, researcher-profiles) repeat by design
  * across works: the most frequent value is kept (ties: the smallest). rejects-v1 values pass
  * through.
  */
class NormalizedRecordReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var out: Outputs = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out = new Outputs(ctx); run = RunContext.from(ctx.getConfiguration)

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val (kind, id) = Keyed.split(key)
    val vs = Tasks.sortedValues(values)
    kind match
      case Reject.Dir => vs.foreach(out.write(kind, _))
      case "works"    =>
        val distinct = vs.distinct
        Counters.inc(ctx, Counters.Duplicate, vs.size - distinct.size)
        if distinct.size > 1 then
          Counters.inc(ctx, Counters.Conflict)
          distinct.tail.foreach { v =>
            val w = read[WorkRecord](v)
            val r = Reject(
              "conflicting-duplicate",
              s"work $id has differing records; kept the smallest",
              Some(w.sourceRecordId)
            )
            out.write(Reject.Dir, write(r.record(run, w.source, s"work:$id", v)))
          }
        out.write("works", distinct.head)
        read[WorkRecord](distinct.head).authors.foreach(m =>
          out.write("mentions", WireCodec.encode(m))
        )
      case other =>
        out.write(
          other,
          vs.groupMapReduce(identity)(_ => 1)(_ + _).toVector.minBy((v, n) => (-n, v))._1
        )

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = out.close()

// ---- J02 SourceNormalizeJob ---------------------------------------------------------------------

/** Map: (LongWritable offset, Text JSON line) -> (Text "<kind>\t<id>", Text JSON) or a reject. The
  * source dataset (and so the normalizer) is the one whose configured input path contains the
  * split's file (keys `scholarchain.j02.inputs.<SOURCE>`, set by [[Jobs.sourceNormalize]]).
  */
class SourceNormalizeMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var run: RunContext = scala.compiletime.uninitialized
  private var ds: SourceType = scala.compiletime.uninitialized
  private var file = ""
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    run = RunContext.from(ctx.getConfiguration)
    file = ctx.getInputSplit.asInstanceOf[FileSplit].getPath.toString
    ds = Jobs.Normalizers.keys.toSeq
      .find(d =>
        ctx.getConfiguration
          .getTrimmedStrings(Jobs.sourceKey(d))
          .exists(p => file == p || file.startsWith(p + "/"))
      )
      .getOrElse(sys.error(s"input $file belongs to no configured source"))
  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    val line = value.toString
    if line.trim.nonEmpty then
      Counters.inc(ctx, Counters.Input)
      Jobs.Normalizers(ds)(line, run) match
        case Left(r)   => Tasks.reject(ctx, r, r.record(run, ds, s"$file@${offset.get}", line))
        case Right(ks) =>
          Counters.inc(ctx, Counters.Accepted)
          ks.foreach(Tasks.emit(ctx, _))

// ---- J03+J04 ExactIdentifierJob -----------------------------------------------------------------

/** Map: (LongWritable, Text JSON line of works/ or mentions/) -> (Text identifier, Text IndexEntry
  * JSON). A work with no exact work key counts MISSING_KEY.
  */
class ExactKeyMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var run: RunContext = scala.compiletime.uninitialized
  private var dir = ""
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    run = RunContext.from(ctx.getConfiguration); dir = Tasks.inputDir(ctx)
  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    Counters.inc(ctx, Counters.Input)
    val json = value.toString
    val entries: Either[Reject, Vector[(String, IndexEntry)]] = dir match
      case "works" =>
        Tasks.decode[WorkRecord](json).map { w =>
          if ExactIdentifiers.workKeys(w).isEmpty then Counters.inc(ctx, Counters.MissingKey)
          ExactIdentifiers.workKeys(w).map(_ -> IndexEntry("work", w.workId, w.source, w.workId))
        }
      case "mentions" =>
        Tasks
          .decode[AuthorMention](json)
          .map(m =>
            ExactIdentifiers
              .mentionKeys(m)
              .map(_ -> IndexEntry("mention", m.mentionId, m.provenance.sourceDataset, m.workId))
          )
      case other => Left(Reject("unsupported-input", s"unexpected input directory $other"))
    entries match
      case Left(r) =>
        Tasks.reject(ctx, r, r.record(run, SourceType.DERIVED, s"$dir:offset:${offset.get}", json))
      case Right(es) =>
        Counters.inc(ctx, Counters.Accepted)
        es.foreach((k, e) => ctx.write(new Text(k), new Text(write(e))))

/** Reduce: (Text identifier, [Text IndexEntry]) -> MatchCandidate JSON in matches/ and conflicts/.
  * A conflicting group above entity-resolution.max-conflict-group-size writes no pairs: it counts
  * OVERSIZE_CONFLICT and writes one oversize-conflicts/ line. One stats/ line per reducer with its
  * largest identifier group.
  */
class ExactJoinReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var out: Outputs = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized
  private var maxConflict = 0
  private var largest = (0, "")

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out = new Outputs(ctx); run = RunContext.from(ctx.getConfiguration)
    maxConflict = Keys.required(ctx.getConfiguration, Keys.MaxConflictGroupSize).toInt

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val id = key.toString
    if id.startsWith(Reject.Dir + "\t") then
      values.asScala.map(_.toString).toVector.sorted.foreach(out.write(Reject.Dir, _))
    else
      val entries = Tasks.sortedValues(values).map(read[IndexEntry](_))
      val o = ExactIdentifiers.resolve(id, entries, run, maxConflict)
      Counters.inc(ctx, Counters.Duplicate, o.duplicates)
      if o.conflicted then Counters.inc(ctx, Counters.Conflict)
      Counters.inc(ctx, Counters.OversizeConflict, o.oversizeConflicts.size)
      o.oversizeConflicts.foreach(g =>
        out.write(
          "oversize-conflicts",
          Tasks.statsLine(
            run.job,
            "identifier" -> id,
            "entityType" -> g.entityType.toString,
            "groupSize" -> g.size,
            "maxConflictGroupSize" -> maxConflict
          )
        )
      )
      if o.unmatched then Counters.inc(ctx, Counters.ExactUnmatched)
      Counters.inc(ctx, Counters.ExactMatch, o.matches.size)
      o.matches.foreach(c => out.write("matches", WireCodec.encode(c)))
      o.conflicts.foreach(c => out.write("conflicts", WireCodec.encode(c)))
      if o.groupSize > largest._1 || (o.groupSize == largest._1 && id < largest._2) then
        largest = (o.groupSize, id)

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out.write(
      "stats",
      Tasks.statsLine(run.job, "largestIdentifierGroup" -> largest._1, "identifier" -> largest._2)
    )
    out.close()

// ---- J07+J08 PublicationTeamJob + CoauthorPairExpansionJob --------------------------------------

/** Map: (LongWritable, Text WorkRecord JSON from J01 works/) -> (Text workId, Text WorkRecord). */
class TeamMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var run: RunContext = scala.compiletime.uninitialized
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    run = RunContext.from(ctx.getConfiguration)
  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    Counters.inc(ctx, Counters.Input)
    Tasks.decode[WorkRecord](value.toString) match
      case Left(r) =>
        Tasks.reject(
          ctx,
          r,
          r.record(run, SourceType.DERIVED, s"works:offset:${offset.get}", value.toString)
        )
      case Right(w) => Counters.inc(ctx, Counters.Accepted); ctx.write(new Text(w.workId), value)

/** Reduce: (Text workId, [Text WorkRecord]) -> PublicationTeam in teams/ and n(n-1)/2
  * CoauthorPairCandidate records in pairs/. Teams above graph.oversize-team-threshold count
  * OVERSIZE_TEAM (their pairs are still emitted). One stats/ line per reducer: largest team, its
  * pair count, and the team-size histogram.
  */
class TeamPairReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var out: Outputs = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized
  private var threshold = 0
  private var largest = (-1, "")
  private val histogram = scala.collection.mutable.TreeMap.empty[String, Long]

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out = new Outputs(ctx); run = RunContext.from(ctx.getConfiguration)
    threshold = Keys.required(ctx.getConfiguration, Keys.OversizeTeamThreshold).toInt

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val vs = Tasks.sortedValues(values)
    if key.toString.startsWith(Reject.Dir + "\t") then vs.foreach(out.write(Reject.Dir, _))
    else
      val distinct = vs.distinct
      Counters.inc(ctx, Counters.Duplicate, vs.size - distinct.size)
      if distinct.size > 1 then Counters.inc(ctx, Counters.Conflict)
      val w = read[WorkRecord](distinct.head)
      val team = Teams.team(w)
      Validation.validate(team) match
        case Left(es) =>
          val r = Reject.schema(es.mkString("; "), Some(w.sourceRecordId))
          Counters.inc(ctx, Counters.Rejected); Counters.inc(ctx, Counters.SchemaFailure)
          out.write(Reject.Dir, write(r.record(run, w.source, s"work:${w.workId}", distinct.head)))
        case Right(_) =>
          val n = team.teamSize
          out.write("teams", WireCodec.encode(team))
          if n > threshold then Counters.inc(ctx, Counters.OversizeTeam)
          histogram.updateWith(Teams.bucket(n))(c => Some(c.getOrElse(0L) + 1))
          if n > largest._1 || (n == largest._1 && w.workId < largest._2) then
            largest = (n, w.workId)
          Teams.pairs(w).foreach { p =>
            out.write("pairs", WireCodec.encode(p))
            Counters.inc(ctx, Counters.EmittedPair)
          }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out.write(
      "stats",
      Tasks.statsLine(
        run.job,
        "largestTeamSize" -> largest._1,
        "largestTeamWorkId" -> largest._2,
        "largestTeamPairs" -> ujson.Num(Teams.pairCount(math.max(largest._1, 0)).toDouble),
        "teamSizeHistogram" -> ujson.Obj.from(histogram.map((k, v) => k -> ujson.Num(v.toDouble)))
      )
    )
    out.close()

// ---- J05 BlockingKeyJob (one MapReduce job per blocking level) ---------------------------------

/** Map, pass 0: (LongWritable, Text WorkRecord JSON of J01 or J02 works/) -> (Text level-0 key,
  * Text BlockMember JSON) for the work and for each of its author mentions, with keys from
  * entity-resolution BlockingKeys. A member with no key counts MISSING_KEY and is not blocked.
  *
  * Map, pass k > 0: (LongWritable, Text BlockMember JSON of pass k-1 rekey/) -> (Text level-k key,
  * Text BlockMember JSON), once per level-k key (r2 gives one key per coauthor family name).
  */
class BlockingKeyMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var run: RunContext = scala.compiletime.uninitialized
  private var level = 0
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    run = RunContext.from(ctx.getConfiguration)
    level = Keys.required(ctx.getConfiguration, Keys.BlockLevel).toInt
  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    Counters.inc(ctx, Counters.Input)
    val json = value.toString
    val members =
      if level == 0 then Tasks.decode[WorkRecord](json).map(Blocking.members)
      else
        scala.util
          .Try(read[BlockMember](json))
          .toEither
          .left
          .map(e => Reject.malformed(s"BlockMember: ${e.getMessage}"))
          .map(Vector(_))
    members match
      case Left(r) =>
        val loc = s"p$level:offset:${offset.get}"
        Tasks.reject(ctx, r, r.record(run, SourceType.DERIVED, loc, json))
      case Right(ms) =>
        Counters.inc(ctx, Counters.Accepted)
        ms.foreach { m =>
          if m.keys.isEmpty then Counters.inc(ctx, Counters.MissingKey)
          else
            Counters.inc(ctx, Counters.BlockMember)
            val v = new Text(write(m))
            m.keys(level).foreach(k => ctx.write(new Text(k), v))
        }

/** Reduce: (Text level-k key, [Text BlockMember]) -> one of, by OversizePolicy.action on the group
  * size and entity-resolution.max-block-size: a BlockRecord in blocks/ (Keep, counts BLOCK_KEPT),
  * every member in rekey/ for pass k+1 (Rekey, counts BLOCK_REKEYED), or an OversizeBlockRecord in
  * oversize/ (ReportOversize at the last level, counts OVERSIZE_BLOCK; no candidates). Holds at
  * most max-block-size + 1 members ([[Blocking.group]]). One stats/ line per reducer: the largest
  * group and the largest kept block.
  */
class BlockingKeyReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var out: Outputs = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized
  private var level = 0
  private var maxBlock = 0
  private var largestGroup = (0L, "")
  private var largestKept = (0L, "")

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out = new Outputs(ctx); run = RunContext.from(ctx.getConfiguration)
    level = Keys.required(ctx.getConfiguration, Keys.BlockLevel).toInt
    maxBlock = Keys.required(ctx.getConfiguration, Keys.MaxBlockSize).toInt

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val k = key.toString
    if k.startsWith(Reject.Dir + "\t") then
      Tasks.sortedValues(values).foreach(out.write(Reject.Dir, _))
    else
      val vs = values.asScala.iterator.map(_.toString)
      val (g, size, dupes) = Blocking.group(level, k, maxBlock, vs, out.write("rekey", _))
      Counters.inc(ctx, Counters.Duplicate, dupes)
      largestGroup = Tasks.larger(largestGroup, size, k)
      g match
        case Blocking.Group.Kept(b) =>
          Counters.inc(ctx, Counters.BlockKept)
          out.write("blocks", write(b))
          largestKept = Tasks.larger(largestKept, size, k)
        case Blocking.Group.Rekeyed(_)  => Counters.inc(ctx, Counters.BlockRekeyed)
        case Blocking.Group.Oversize(r) =>
          Counters.inc(ctx, Counters.OversizeBlock)
          out.write("oversize", write(r))

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out.write(
      "stats",
      Tasks.statsLine(
        run.job,
        "level" -> level,
        "maxBlockSize" -> maxBlock,
        "largestGroupSize" -> ujson.Num(largestGroup._1.toDouble),
        "largestGroupKey" -> largestGroup._2,
        "largestKeptBlockSize" -> ujson.Num(largestKept._1.toDouble),
        "largestKeptBlockKey" -> largestKept._2
      )
    )
    out.close()

// ---- J06 CandidateJoinJob -----------------------------------------------------------------------

/** Map: (LongWritable, Text BlockRecord JSON of any J05 pass's blocks/) -> (Text
  * "<entityType>\t<sourceId>\t<targetId>", Text JSON [blockingKey, features]) for every member pair
  * of the block (sourceId < targetId). Features are entity-resolution Features (feat-v1). Two
  * mentions of one work are skipped and count SAME_WORK_PAIR_SKIPPED. Venue names for the venue
  * feature come from a side input read once in setup: the venues/ directories of J01 and J02 (one
  * line per venue, so the table is bounded by the number of venues, not of works).
  */
class CandidatePairMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var run: RunContext = scala.compiletime.uninitialized
  private var venues: Map[String, String] = Map.empty
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    val conf = ctx.getConfiguration
    run = RunContext.from(conf)
    venues = conf
      .getTrimmedStrings(Keys.VenueDirs)
      .toVector
      .flatMap(d => Tasks.readLines(conf, new Path(d)))
      .map(read[Venue](_))
      .map(v => v.venueId -> v.name)
      .toMap
  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    Counters.inc(ctx, Counters.Input)
    val json = value.toString
    scala.util.Try(read[BlockRecord](json)).toEither match
      case Left(e) =>
        val r = Reject.malformed(s"BlockRecord: ${e.getMessage}")
        val loc = s"blocks:offset:${offset.get}"
        Tasks.reject(ctx, r, r.record(run, SourceType.DERIVED, loc, json))
      case Right(block) =>
        Counters.inc(ctx, Counters.Accepted)
        val (pairs, sameWork) = Blocking.pairs(block)
        Counters.inc(ctx, Counters.SameWorkPairSkipped, sameWork)
        pairs.foreach { (x, y) =>
          ctx.write(
            new Text(s"${block.entityType}\t${x.id}\t${y.id}"),
            new Text(write((block.key, Blocking.features(x, y, venues.get))))
          )
        }

/** Reduce: (Text "<entityType>\t<sourceId>\t<targetId>", [Text [blockingKey, features]]) -> one
  * MatchCandidate in candidates/. A pair met in several blocks (r2 is multi-key) is written once,
  * with the smallest blocking key; the extra meetings count DUPLICATE. Features depend only on the
  * two members, so every meeting carries the same features.
  */
class CandidateJoinReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var out: Outputs = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized
  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out = new Outputs(ctx); run = RunContext.from(ctx.getConfiguration)
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val k = key.toString
    if k.startsWith(Reject.Dir + "\t") then
      Tasks.sortedValues(values).foreach(out.write(Reject.Dir, _))
    else
      val Array(t, a, b) = k.split('\t'): @unchecked
      val vs = values.asScala.map(v => read[(String, Map[String, Double])](v.toString)).toVector
      Counters.inc(ctx, Counters.Duplicate, vs.size - 1)
      val (blockingKey, features) = vs.minBy(_._1)
      val c = Blocking.candidate(MatchEntityType.valueOf(t), a, b, blockingKey, features, run)
      Validation.validate(c) match
        case Left(es) =>
          val r = Reject.schema(es.mkString("; "), Some(s"$a|$b"))
          Counters.inc(ctx, Counters.Rejected); Counters.inc(ctx, Counters.SchemaFailure)
          out.write(Reject.Dir, write(r.record(run, SourceType.DERIVED, s"pair:$a|$b", k)))
        case Right(_) =>
          Counters.inc(ctx, Counters.MatchCandidate)
          out.write("candidates", WireCodec.encode(c))
  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = out.close()

// ---- J09 GraphCandidateExportJob ----------------------------------------------------------------

object Export:
  val NullPartition = "__HIVE_DEFAULT_PARTITION__" // Spark reads this partition value as null
  private def year(y: Option[Int]) = y.fold(NullPartition)(_.toString)

  /** Validates one record of an upstream output directory and names its Spark-ready directory
    * (Hive-style partitions) and stable ID.
    */
  def route(kind: String, json: String): Either[Reject, (String, String)] =
    import Tasks.decode
    kind match
      case "works" =>
        decode[WorkRecord](json).map(w =>
          (s"works/source_dataset=${w.source}/publication_year=${year(w.year)}", w.workId)
        )
      case "mentions" =>
        decode[AuthorMention](json).map(m =>
          (s"author-mentions/source_dataset=${m.provenance.sourceDataset}", m.mentionId)
        )
      case "teams" =>
        decode[PublicationTeam](json).map(t =>
          (s"publication-teams/publication_year=${year(t.year)}", t.workId)
        )
      case "pairs" =>
        decode[CoauthorPairCandidate](json)
          .map(p =>
            (
              s"coauthor-pair-candidates/publication_year=${year(p.year)}",
              s"${p.authorA}|${p.authorB}"
            )
          )
      case "venues"       => decode[Venue](json).map(v => ("venues", v.venueId))
      case "institutions" => decode[Institution](json).map(i => ("institutions", i.institutionId))
      case "topics"       => decode[Topic](json).map(t => ("topics", t.topicId))
      case "edges"        =>
        decode[GraphEdge](json).map(e => (s"graph-edges/edge_type=${e.edgeType}", e.edgeId))
      case "researcher-profiles" =>
        decode[GraphNode](json).map(n => ("researcher-profiles", n.nodeId))
      case "candidates" =>
        decode[MatchCandidate](json).map(c =>
          (s"blocking-candidates/entity_type=${c.entityType}", s"${c.sourceId}|${c.targetId}")
        )
      case "matches" | "conflicts" =>
        val dir = if kind == "matches" then "match-candidates" else "exact-id-conflicts"
        decode[MatchCandidate](json)
          .map(c =>
            (s"$dir/entity_type=${c.entityType}", s"${c.sourceId}|${c.targetId}|${c.blockingKey}")
          )
      case other => Left(Reject("unsupported-input", s"unexpected input directory $other"))

/** Map: (LongWritable, Text JSON line of an upstream output) -> (Text "<export dir>\t<id>", Text).
  */
class ExportMapper extends Mapper[LongWritable, Text, Text, Text]:
  private var run: RunContext = scala.compiletime.uninitialized
  private var dir = ""
  override def setup(ctx: Mapper[LongWritable, Text, Text, Text]#Context): Unit =
    Counters.register(ctx)
    run = RunContext.from(ctx.getConfiguration); dir = Tasks.inputDir(ctx)
  override def map(
      offset: LongWritable,
      value: Text,
      ctx: Mapper[LongWritable, Text, Text, Text]#Context
  ): Unit =
    Counters.inc(ctx, Counters.Input)
    Export.route(dir, value.toString) match
      case Left(r) =>
        Tasks.reject(
          ctx,
          r,
          r.record(run, SourceType.DERIVED, s"$dir:offset:${offset.get}", value.toString)
        )
      case Right((d, id)) =>
        Counters.inc(ctx, Counters.Accepted); ctx.write(new Text(s"$d\t$id"), value)

/** Reduce: (Text "<export dir>\t<id>", [Text JSON]) -> one JSON line in <export dir>/. Identical
  * repeats count DUPLICATE; differing repeats count CONFLICT and go to rejects-v1/.
  */
class ExportReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var out: Outputs = scala.compiletime.uninitialized
  private var run: RunContext = scala.compiletime.uninitialized
  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    out = new Outputs(ctx); run = RunContext.from(ctx.getConfiguration)
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val (dir, id) = Keyed.split(key)
    val vs = Tasks.sortedValues(values)
    if dir == Reject.Dir then vs.foreach(out.write(dir, _))
    else
      val distinct = vs.distinct
      Counters.inc(ctx, Counters.Duplicate, vs.size - distinct.size)
      if distinct.size > 1 then
        Counters.inc(ctx, Counters.Conflict)
        distinct.tail.foreach(v =>
          out.write(
            Reject.Dir,
            write(
              Reject("conflicting-duplicate", s"$dir $id differs between inputs; kept the smallest")
                .record(run, SourceType.DERIVED, s"$dir:$id", v)
            )
          )
        )
      out.write(dir, distinct.head)
  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = out.close()

// ---- Job definitions ----------------------------------------------------------------------------

object Jobs:
  val Normalizers: Map[SourceType, (String, RunContext) => Either[Reject, Vector[Keyed]]] = Map(
    SourceType.OPENALEX -> SourceNormalizers.openAlex,
    SourceType.CROSSREF -> SourceNormalizers.crossref,
    SourceType.ORCID -> SourceNormalizers.orcid
  )
  def sourceKey(ds: SourceType): String = s"scholarchain.j02.inputs.$ds"

  val J01 = "j01-dblp-extract"
  val J02 = "j02-source-normalize"
  val J04 = "j03-j04-exact-identifier"
  val J05 = "j05-blocking-key"
  def J05Pass(level: Int): String = s"$J05-p$level"
  val J06 = "j06-candidate-join"
  val J08 = "j07-j08-team-pair-expansion"
  val J09 = "j09-graph-export"

  /** Settings shared by every job: Text/Text shuffle with zlib-compressed map output, explicit
    * HashPartitioner on the shuffle key, an explicit reducer count, and gzip JSON-lines output
    * written lazily (no empty part files) through MultipleOutputs.
    */
  private def base(conf: Configuration, name: String, out: Path, reducers: Int): Job =
    val c = new Configuration(conf)
    c.set(Keys.JobName, name)
    c.setBoolean("mapreduce.map.output.compress", true)
    c.setClass(
      "mapreduce.map.output.compress.codec",
      classOf[DefaultCodec],
      classOf[CompressionCodec]
    )
    val job = Job.getInstance(c, s"scholarchain-$name")
    job.setJarByClass(classOf[NormalizedRecordReducer])
    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[Text])
    job.setOutputKeyClass(classOf[NullWritable])
    job.setOutputValueClass(classOf[Text])
    job.setPartitionerClass(classOf[HashPartitioner[Text, Text]])
    job.setNumReduceTasks(reducers)
    LazyOutputFormat.setOutputFormatClass(job, classOf[TextOutputFormat[NullWritable, Text]])
    FileOutputFormat.setOutputPath(job, out)
    FileOutputFormat.setCompressOutput(job, true)
    FileOutputFormat.setOutputCompressorClass(job, classOf[GzipCodec])
    job

  def dblpExtract(conf: Configuration, dblp: Seq[Path], out: Path, reducers: Int): Job =
    val job = base(conf, J01, out, reducers)
    job.setInputFormatClass(classOf[DblpInputFormat])
    dblp.foreach(FileInputFormat.addInputPath(job, _))
    job.setMapperClass(classOf[DblpExtractMapper])
    job.setReducerClass(classOf[NormalizedRecordReducer])
    job

  def sourceNormalize(
      conf: Configuration,
      openAlex: Seq[Path],
      crossref: Seq[Path],
      orcid: Seq[Path],
      out: Path,
      reducers: Int
  ): Job =
    val inputs = Map(
      SourceType.OPENALEX -> openAlex,
      SourceType.CROSSREF -> crossref,
      SourceType.ORCID -> orcid
    )
    val qualified = inputs.view.mapValues(_.map(p => p.getFileSystem(conf).makeQualified(p))).toMap
    val job = textJob(conf, J02, qualified.values.flatten.toSeq, out, reducers)
    qualified.foreach((ds, ps) =>
      job.getConfiguration.setStrings(sourceKey(ds), ps.map(_.toString)*)
    )
    job.setMapperClass(classOf[SourceNormalizeMapper])
    job.setReducerClass(classOf[NormalizedRecordReducer])
    job

  private def textJob(conf: Configuration, name: String, in: Seq[Path], out: Path, reducers: Int) =
    val job = base(conf, name, out, reducers)
    job.setInputFormatClass(classOf[TextInputFormat])
    in.foreach(FileInputFormat.addInputPath(job, _))
    job

  def exactIdentifier(
      conf: Configuration,
      worksAndMentions: Seq[Path],
      out: Path,
      reducers: Int
  ): Job =
    val job = textJob(conf, J04, worksAndMentions, out, reducers)
    job.setMapperClass(classOf[ExactKeyMapper])
    job.setReducerClass(classOf[ExactJoinReducer])
    job

  /** J05 pass `level`: pass 0 reads J01 and J02 works/, pass k > 0 reads pass k-1 rekey/. */
  def blockingKeys(conf: Configuration, in: Seq[Path], out: Path, reducers: Int, level: Int): Job =
    val job = textJob(conf, J05Pass(level), in, out, reducers)
    job.getConfiguration.setInt(Keys.BlockLevel, level)
    job.setMapperClass(classOf[BlockingKeyMapper])
    job.setReducerClass(classOf[BlockingKeyReducer])
    job

  /** J06: every J05 pass's blocks/, with the J01 and J02 venues/ directories as side input. */
  def candidateJoin(
      conf: Configuration,
      blocks: Seq[Path],
      venueDirs: Seq[Path],
      out: Path,
      reducers: Int
  ): Job =
    val job = textJob(conf, J06, blocks, out, reducers)
    job.getConfiguration.setStrings(Keys.VenueDirs, venueDirs.map(_.toString)*)
    job.setMapperClass(classOf[CandidatePairMapper])
    job.setReducerClass(classOf[CandidateJoinReducer])
    job

  def teamPairs(conf: Configuration, dblpWorks: Seq[Path], out: Path, reducers: Int): Job =
    val job = textJob(conf, J08, dblpWorks, out, reducers)
    job.setMapperClass(classOf[TeamMapper])
    job.setReducerClass(classOf[TeamPairReducer])
    job

  def graphExport(conf: Configuration, upstreamDirs: Seq[Path], out: Path, reducers: Int): Job =
    val job = textJob(conf, J09, upstreamDirs, out, reducers)
    job.setMapperClass(classOf[ExportMapper])
    job.setReducerClass(classOf[ExportReducer])
    job
