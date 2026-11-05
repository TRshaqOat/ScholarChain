package scholarchain.hadoop

import scholarchain.core.*
import scholarchain.entityresolution.{
  BlockingKeys,
  CandidateOrigin,
  Features,
  MatchPolicy,
  OversizePolicy,
  Thresholds,
  Verdict,
  Versions,
  WorkPairFeatures
}
import upickle.default.{ReadWriter, read, write}

import scala.collection.mutable

/** One member of an exact-identifier group: the J03/J04 shuffle value (internal to the job).
  * `rawName` is the mention's printed name (None for works), the input of
  * MatchPolicy.orcidGroupSuffixConflict.
  */
final case class IndexEntry(
    kind: String,
    id: String,
    source: SourceType,
    workId: String,
    rawName: Option[String] = None
) derives ReadWriter

/** Exact-identifier keys and group resolution (J03+J04). DOI and ORCID normalization come from core
  * StableIds; the source records were normalized in J01 and J02.
  */
object ExactIdentifiers:
  /** Work keys: `doi:<normalized DOI>`, `arxiv:<arXiv ID>`. */
  def workKeys(w: WorkRecord): Vector[String] =
    (w.doi.map(StableIds.normalizeDoi).map("doi:" + _) ++ w.arxivId.map("arxiv:" + _)).toVector

  /** Researcher keys: `orcid:<iD>` and the OpenAlex author ID (`openalex:A...`). */
  def mentionKeys(m: AuthorMention): Vector[String] =
    (m.orcid.flatMap(StableIds.normalizeOrcid).map("orcid:" + _) ++
      m.sourceAuthorId.filter(_.startsWith("openalex:A"))).toVector

  def isWorkKey(key: String): Boolean = key.startsWith("doi:") || key.startsWith("arxiv:")

  /** A conflicting group too large to expand into pairs
    * (entity-resolution.max-conflict-group-size).
    */
  final case class OversizeConflict(entityType: MatchEntityType, size: Int)

  final case class Outcome(
      matches: Vector[MatchCandidate],
      conflicts: Vector[MatchCandidate],
      oversizeConflicts: Vector[OversizeConflict],
      duplicates: Int,
      groupSize: Int,
      suffixConflict: Boolean = false
  ):
    def conflicted: Boolean = conflicts.nonEmpty || oversizeConflicts.nonEmpty
    def unmatched: Boolean = matches.isEmpty && !conflicted

  /** Resolves one identifier group. Rules (structural, no scoring):
    *   - Work keys: if any one source has two or more distinct works under the key, the key is a
    *     conflict: every within-source pair is emitted as a conflict and nothing matches. Otherwise
    *     every non-DBLP work matches the single DBLP work (the canonical work of the DBLP-seeded
    *     corpus); with no DBLP work the key is unmatched.
    *   - Researcher keys: if MatchPolicy.orcidGroupSuffixConflict(key, member raw names,
    *     policyVersion) holds (er-v2: an `orcid:` group whose mentions carry two or more distinct
    *     DBLP homonym numbers), the whole group is one conflict group: every member pair is a
    *     conflict, nothing matches, and the Outcome is flagged `suffixConflict`. Otherwise two or
    *     more mentions of the same work under one key are a conflict (one person cannot hold two
    *     author slots); every other mention matches the key itself.
    * A conflicting group with more than maxConflictGroup members emits no pairs; it is reported as
    * an [[OversizeConflict]] instead, and the key still counts as a conflict. The cap changes only
    * the conflict output, not the matches: a capped work key still matches nothing, and under a
    * capped researcher key the mentions of other works still match the key (only the clashing
    * work's mentions are withheld). Candidates carry empty feature maps: exact-ID matches are not
    * blocked candidates.
    */
  def resolve(
      key: String,
      entries: Seq[IndexEntry],
      ctx: RunContext,
      maxConflictGroup: Int,
      policyVersion: String
  ): Outcome =
    val members = entries.distinctBy(_.id).sortBy(_.id).toVector
    val dupes = entries.size - members.size
    def cand(t: MatchEntityType, a: String, b: String) =
      MatchCandidate(
        SchemaVersions.MatchCandidate,
        t,
        a,
        b,
        key,
        Map.empty,
        ctx.provenance(SourceType.DERIVED, s"$key|$a|$b")
      )
    def conflictPairs(t: MatchEntityType, group: Vector[IndexEntry]) =
      if group.size > maxConflictGroup then Vector.empty
      else
        for i <- group.indices.toVector; j <- i + 1 until group.size
        yield cand(t, group(i).id, group(j).id) // members are sorted, so (i, j) is canonical
    def oversize(t: MatchEntityType, groups: Vector[Vector[IndexEntry]]) =
      groups.filter(_.size > maxConflictGroup).map(g => OversizeConflict(t, g.size))
    if isWorkKey(key) then
      val bySource = members.groupBy(_.source)
      val conflicting = bySource.values.filter(_.size > 1).toVector.sortBy(_.head.id)
      if conflicting.nonEmpty then
        Outcome(
          Vector.empty,
          conflicting.flatMap(conflictPairs(MatchEntityType.WORK, _)),
          oversize(MatchEntityType.WORK, conflicting),
          dupes,
          members.size
        )
      else
        val matches = bySource.get(SourceType.DBLP).flatMap(_.headOption) match
          case Some(dblp) =>
            members.filter(_ != dblp).map(o => cand(MatchEntityType.WORK, o.id, dblp.id))
          case None => Vector.empty
        Outcome(matches, Vector.empty, Vector.empty, dupes, members.size)
    else if MatchPolicy.orcidGroupSuffixConflict(key, members.flatMap(_.rawName), policyVersion)
    then
      val r = MatchEntityType.RESEARCHER
      Outcome(
        Vector.empty,
        conflictPairs(r, members),
        oversize(r, Vector(members)),
        dupes,
        members.size,
        suffixConflict = true
      )
    else
      val (clash, ok) = members.groupBy(_.workId).values.toVector.partition(_.size > 1)
      val sortedClash = clash.sortBy(_.head.id)
      Outcome(
        ok.flatten.sortBy(_.id).map(m => cand(MatchEntityType.RESEARCHER, m.id, key)),
        sortedClash.flatMap(conflictPairs(MatchEntityType.RESEARCHER, _)),
        oversize(MatchEntityType.RESEARCHER, sortedClash),
        dupes,
        members.size
      )

/** Publication teams and coauthor-pair expansion (J07+J08). */
object Teams:
  private def ordered(w: WorkRecord) = w.authors.sortBy(_.position).map(_.mentionId)

  /** The ordered author mentions of a work; provenance is the work's source provenance. */
  def team(w: WorkRecord): PublicationTeam =
    PublicationTeam(SchemaVersions.PublicationTeam, w.workId, w.year, ordered(w), w.provenance)

  /** Number of unordered pairs a team of n authors yields: n(n-1)/2. */
  def pairCount(n: Int): Long = n.toLong * (n - 1) / 2

  /** All n(n-1)/2 unordered pairs, each in canonical (CanonicalPair) order, lazily. Author
    * positions are the `#n` suffix of each mention ID.
    */
  def pairs(w: WorkRecord): Iterator[CoauthorPairCandidate] =
    val ids = ordered(w)
    for
      i <- ids.indices.iterator
      j <- (i + 1 until ids.size).iterator
    yield
      val (a, b) =
        CanonicalPair(ids(i), ids(j)).fold(e => throw new IllegalStateException(e), identity)
      CoauthorPairCandidate(
        SchemaVersions.CoauthorPairCandidate,
        a,
        b,
        w.workId,
        w.year,
        w.provenance
      )

  /** Team-size histogram bucket label. */
  def bucket(n: Int): String =
    if n <= 10 then n.toString
    else if n <= 20 then "11-20"
    else if n <= 50 then "21-50"
    else if n <= 100 then "51-100"
    else "101+"

/** One blocking member, the J05 shuffle value and the J05 rekey/ line (hadoop-internal). A
  * researcher member is one author mention (`id` = mention ID, `position` set); a work member is
  * one work (`id` = work ID). `work` is the whole WorkRecord the member comes from, because the
  * feature functions need the mention's coauthors and team size. `keys` are the entity-resolution
  * blocking keys by level (index 0 primary), computed once in J05 pass 0.
  */
final case class BlockMember(
    entityType: MatchEntityType,
    id: String,
    keys: Vector[Vector[String]],
    work: WorkRecord,
    position: Option[Int]
) derives ReadWriter:
  def mention: AuthorMention =
    position
      .flatMap(p => work.authors.find(_.position == p))
      .getOrElse(sys.error(s"$id: no mention"))

/** A kept block (J05 blocks/, hadoop-internal format [[Blocking.SchemaVersion]]): members sorted by
  * ID, at most entity-resolution.max-block-size of them.
  */
final case class BlockRecord(
    schemaVersion: String,
    entityType: MatchEntityType,
    level: Int,
    key: String,
    members: Vector[BlockMember]
) derives ReadWriter

/** A block still above the maximum at its entity's last level (J05 oversize/): no candidates. */
final case class OversizeBlockRecord(
    schemaVersion: String,
    entityType: MatchEntityType,
    level: Int,
    key: String,
    size: Long
) derives ReadWriter

/** J05 and J06 logic. Keys, the oversize rule, and features are the entity-resolution-engineer's
  * pure functions (BlockingKeys, OversizePolicy.action, Features); this object only applies them.
  */
object Blocking:
  val SchemaVersion = "hadoop-block-v1"

  /** Rule versions recorded in every blocked MatchCandidate's provenance.policyVersion. */
  val RuleVersions = s"${Versions.Normalization}.${Versions.Blocking}.${Versions.Features}"

  def levels(t: MatchEntityType): Int = t match
    case MatchEntityType.RESEARCHER => BlockingKeys.ResearcherLevels
    case MatchEntityType.WORK       => BlockingKeys.WorkLevels

  /** Passes J05 runs at most: one per level of the deepest entity. */
  val Passes: Int = math.max(BlockingKeys.ResearcherLevels, BlockingKeys.WorkLevels)

  /** The work member and one researcher member per author mention. Members with no key (empty key
    * list) are returned too; J05 counts them MISSING_KEY and does not block them.
    */
  def members(w: WorkRecord): Vector[BlockMember] =
    val work =
      BlockMember(MatchEntityType.WORK, w.workId, BlockingKeys.work(w.title, w.year), w, None)
    work +: w.authors.map { m =>
      val coauthors = w.authors.filterNot(_.position == m.position).map(_.rawName)
      BlockMember(
        MatchEntityType.RESEARCHER,
        m.mentionId,
        BlockingKeys.researcher(m.rawName, coauthors),
        w,
        Some(m.position)
      )
    }

  /** What one J05 reduce group became. */
  enum Group:
    case Kept(block: BlockRecord)
    case Rekeyed(size: Long)
    case Oversize(report: OversizeBlockRecord)

  /** Applies OversizePolicy.action to one key group, reading the values once and holding at most
    * maxBlockSize + 1 members. Members are buffered (distinct by ID) until the group outgrows
    * maxBlockSize. From then on the buffer and every later value go to `rekey` (Rekey) or are only
    * counted (ReportOversize). Member IDs are unique by construction (J01 and J02 reducers keep one
    * record per work ID, and mention IDs embed the work ID), so streamed values are not
    * de-duplicated.
    *
    * @return
    *   the group outcome, its size, and the number of duplicate values dropped while buffering
    */
  def group(
      level: Int,
      key: String,
      maxBlockSize: Int,
      values: Iterator[String],
      rekey: String => Unit
  ): (Group, Long, Long) =
    val buffer = mutable.TreeMap.empty[String, BlockMember]
    var dupes = 0L
    var entity: Option[MatchEntityType] = None
    var overflow: Option[OversizePolicy.Action] = None
    var size = 0L
    values.foreach { json =>
      overflow match
        case None =>
          val m = read[BlockMember](json)
          entity = entity.orElse(Some(m.entityType))
          if buffer.contains(m.id) then dupes += 1 else buffer(m.id) = m
          if buffer.size > maxBlockSize then
            val a = OversizePolicy.action(level, buffer.size, maxBlockSize, levels(m.entityType))
            overflow = Some(a)
            size = buffer.size
            if a != OversizePolicy.Action.ReportOversize then
              buffer.values.foreach(b => rekey(write(b)))
            buffer.clear()
        case Some(a) =>
          size += 1
          if a != OversizePolicy.Action.ReportOversize then rekey(json)
    }
    val t = entity.getOrElse(sys.error(s"empty group $key"))
    if overflow.isEmpty then size = buffer.size
    val outcome = OversizePolicy.action(level, size, maxBlockSize, levels(t)) match
      case OversizePolicy.Action.Keep =>
        Group.Kept(BlockRecord(SchemaVersion, t, level, key, buffer.values.toVector))
      case OversizePolicy.Action.Rekey(_)       => Group.Rekeyed(size)
      case OversizePolicy.Action.ReportOversize =>
        Group.Oversize(OversizeBlockRecord(SchemaVersion, t, level, key, size))
    (outcome, size, dupes)

  /** The candidate pairs of one kept block, in member order (members are sorted, so a < b is the
    * CanonicalPair order). Two researcher mentions of the same work are never a pair (one person
    * holds one author slot); they are returned separately so J06 can count them.
    */
  def pairs(b: BlockRecord): (Iterator[(BlockMember, BlockMember)], Long) =
    val ms = b.members
    val all = for i <- ms.indices.iterator; j <- (i + 1 until ms.size).iterator yield (ms(i), ms(j))
    val sameWork = ms.groupBy(_.work.workId).values.map(g => g.size.toLong * (g.size - 1) / 2).sum
    (all.filterNot((x, y) => x.work.workId == y.work.workId), sameWork)

  /** Features of one pair, from entity-resolution Features (feat-v1). */
  def features(a: BlockMember, b: BlockMember, venueName: String => Option[String]) =
    a.entityType match
      case MatchEntityType.RESEARCHER => Features.researcher(a.mention, a.work, b.mention, b.work)
      case MatchEntityType.WORK       =>
        Features.work(
          a.work,
          a.work.venueId.flatMap(venueName),
          b.work,
          b.work.venueId.flatMap(venueName)
        )

  /** The blocked MatchCandidate (DERIVED provenance, rule versions as policyVersion). */
  def candidate(
      t: MatchEntityType,
      a: String,
      b: String,
      key: String,
      features: Map[String, Double],
      ctx: RunContext
  ): MatchCandidate =
    MatchCandidate(
      SchemaVersions.MatchCandidate,
      t,
      a,
      b,
      key,
      features,
      ctx.provenance(SourceType.DERIVED, s"$key|$a|$b").copy(policyVersion = Some(RuleVersions))
    )

/** A blocked WORK candidate with both work records (J06 work-pairs/, hadoop-internal format
  * [[Decisions.WorkPairSchemaVersion]]). J10 needs both works for the er-v1 work-pair features, and
  * only J06 holds them (inside the BlockRecords), so J06 hands them on; a map-only J10 needs no
  * join and never loads a works table.
  */
final case class WorkPairInput(
    schemaVersion: String,
    candidate: MatchCandidate,
    workA: WorkRecord,
    workB: WorkRecord
) derives ReadWriter

/** J10 logic. The match policy, its thresholds, and the work-pair features are the
  * entity-resolution-engineer's pure functions (MatchPolicy.decide / decideBlocked,
  * WorkPairFeatures); this object only applies them, exactly as the entity-resolution Runner does
  * without adjudication. It never calls adjudication or any LLM client.
  */
object Decisions:
  val WorkPairSchemaVersion = "hadoop-work-pair-v1"

  /** The J10 input directory a candidate came from, and the origin it stands for. */
  val Origins: Map[String, CandidateOrigin] = Map(
    "matches" -> CandidateOrigin.EXACT_MATCH,
    "conflicts" -> CandidateOrigin.EXACT_CONFLICT,
    "candidates" -> CandidateOrigin.BLOCKED,
    "work-pairs" -> CandidateOrigin.BLOCKED
  )

  /** Features policy `version` sees: blocked work pairs add WorkPairFeatures.work(a, b, version) to
    * feat-v1.
    */
  def features(
      c: MatchCandidate,
      works: Option[(WorkRecord, WorkRecord)],
      version: String
  ): Map[String, Double] =
    works.fold(c.features)((a, b) => c.features ++ WorkPairFeatures.work(a, b, version))

  /** The verdict of policy `version` (no default, so a caller can never fall back to er-v1
    * silently) and the MatchDecision for one candidate (no prompt or model version).
    */
  def decide(
      c: MatchCandidate,
      origin: CandidateOrigin,
      works: Option[(WorkRecord, WorkRecord)],
      t: Thresholds,
      runId: String,
      decidedAt: String,
      version: String
  ): (Verdict, MatchDecision) =
    val f = features(c, works, version)
    val v =
      if origin == CandidateOrigin.BLOCKED then
        MatchPolicy.decideBlocked(c.entityType, f, t, version)
      else MatchPolicy.decide(c, origin, t, version)
    (v, MatchPolicy.toDecision(c, f, v, runId, decidedAt, None, None))

  /** J10 counter name of a decision: `<entityType>.<matchPath>.<outcome>`. */
  def counter(c: MatchCandidate, v: Verdict): String = s"${c.entityType}.${v.path}.${v.outcome}"
