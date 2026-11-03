package scholarchain.core

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Undirected pair canonicalization, the one rule for researcher pairs (CoauthorEdge,
  * COAUTHORED_WITH GraphEdge) and author-mention pairs (CoauthorPairCandidate).
  *
  * Rule: A is the smaller ID by String.compareTo, B the larger; equal IDs are not a pair. Stable
  * IDs are visible ASCII only (IdFormat), so this order equals byte order and Spark's binary string
  * order.
  */
object CanonicalPair:
  def isCanonical(a: String, b: String): Boolean = a.compareTo(b) < 0

  /** The canonical (A, B) for two IDs given in any order. */
  def apply(x: String, y: String): Either[String, (String, String)] =
    val c = x.compareTo(y)
    if c == 0 then Left(s"self-pair is not a pair: $x")
    else Right(if c < 0 then (x, y) else (y, x))

/** Stable identifier functions (spec "Required stable ID principles").
  *
  * Hash-based IDs use H(parts) = the first 32 lower-case hex digits (128 bits) of SHA-256 over the
  * UTF-8 bytes of the parts joined by U+001F (unit separator). The first part is always a kind tag,
  * so IDs of different kinds never share a hash input. No part may contain U+001F. In Spark the
  * same value is substring(sha2(concat_ws('\u001f', parts), 256), 1, 32).
  */
object StableIds:
  private val Sep = "\u001f"

  def hash(parts: String*): String =
    require(parts.forall(!_.contains(Sep)), "ID parts must not contain U+001F")
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.mkString(Sep).getBytes(UTF_8))
    digest.take(16).map(b => f"${b & 0xff}%02x").mkString

  /** Full SHA-256 of UTF-8 text, as "sha256:<64 hex>" (the textHash in chunk IDs and evidence). */
  def textHash(text: String): String =
    "sha256:" + MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  // ---- Work IDs: "<namespace>:<authoritative source ID>" --------------------------------------
  // The canonical work in the DBLP-seeded corpus is the DBLP one. Crossref records use doi:.

  /** "dblp:" + DBLP record key, for example dblp:conf/ics/LiaoHNZCT09. */
  def dblpWorkId(dblpKey: String): String = s"dblp:${dblpKey.trim}"

  /** "doi:" + normalized DOI (normalizeDoi). */
  def doiWorkId(doi: String): String = s"doi:${normalizeDoi(doi)}"

  /** "openalex:" + OpenAlex short ID (W...), from either the URL or the short form. */
  def openAlexId(idOrUrl: String): String =
    s"openalex:${idOrUrl.trim.stripPrefix("https://openalex.org/")}"

  /** Lower-case, trimmed, without a doi.org resolver or "doi:" prefix. */
  def normalizeDoi(doi: String): String =
    val d = doi.trim.toLowerCase
    Seq("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/", "doi:")
      .find(d.startsWith)
      .fold(d)(p => d.drop(p.length))

  /** Author mention ID: workId + "#" + 1-based author position. */
  def mentionId(workId: String, position: Int): String =
    require(position >= 1, s"author position must be >= 1, got $position")
    s"$workId#$position"

  // ---- Researcher IDs -------------------------------------------------------------------------

  /** Bare ORCID iD from "https://orcid.org/<iD>" or "<iD>"; None unless the ISO 7064 MOD 11-2
    * checksum holds. A "synthetic:" fixture ID is never an ORCID iD, so it yields None.
    */
  def normalizeOrcid(orcid: String): Option[String] =
    val id = orcid.trim.stripPrefix("https://orcid.org/").stripPrefix("http://orcid.org/")
    Option.when(IdFormat.OrcidShape.matches(id) && orcidChecksumOk(id))(id)

  private def orcidChecksumOk(id: String): Boolean =
    val digits = id.filter(_ != '-')
    val total = digits.init.foldLeft(0)((t, c) => (t + c.asDigit) * 2)
    val check = (12 - total % 11) % 11
    digits.last == (if check == 10 then 'X' else ('0' + check).toChar)

  /** Researcher ID. With an identity-resolution-validated ORCID: "orcid:<iD>" (an invalid iD is a
    * caller error and throws). Otherwise the versioned ScholarChain identity ID
    * "scr:<policyVersion>:" + H("researcher", policyVersion, anchorKey), where anchorKey is the
    * deterministic anchor the resolution policy picks for the identity cluster (for example the
    * DBLP person key, or a mention ID for an unresolved mention).
    */
  def researcherId(
      validatedOrcid: Option[String],
      policyVersion: String,
      anchorKey: String
  ): String =
    validatedOrcid match
      case Some(o) =>
        s"orcid:${normalizeOrcid(o).getOrElse(throw new IllegalArgumentException(s"not an ORCID iD: $o"))}"
      case None =>
        require(IdFormat.Version.matches(policyVersion), s"bad policy version: $policyVersion")
        require(anchorKey.trim.nonEmpty, "anchorKey must not be blank")
        s"scr:$policyVersion:${hash("researcher", policyVersion, anchorKey)}"

  // ---- Hash-based IDs -------------------------------------------------------------------------

  /** "cae-" + H("coauthor-edge", A, B, windowStartYear, windowEndYear, policyVersion), with (A, B)
    * the canonical pair, so argument order does not matter.
    */
  def coauthorEdgeId(
      researcherX: String,
      researcherY: String,
      windowStartYear: Int,
      windowEndYear: Int,
      policyVersion: String
  ): Either[String, String] =
    CanonicalPair(researcherX, researcherY).map((a, b) =>
      "cae-" + hash("coauthor-edge", a, b, s"$windowStartYear", s"$windowEndYear", policyVersion)
    )

  /** "ge-" + H("graph-edge", edgeType, from, to, policyVersion, evidence...), where (from, to) is
    * canonicalized for undirected edge types and evidence (source evidence IDs, for example work
    * IDs or the coauthor edge ID) is sorted and de-duplicated.
    */
  def graphEdgeId(
      edgeType: EdgeType,
      fromId: String,
      toId: String,
      policyVersion: String,
      evidence: Seq[String]
  ): Either[String, String] =
    val ends = if edgeType.undirected then CanonicalPair(fromId, toId) else Right((fromId, toId))
    ends.map((f, t) =>
      "ge-" + hash(
        (Seq("graph-edge", edgeType.toString, f, t, policyVersion) ++ evidence.distinct.sorted)*
      )
    )

  /** Chunk or profile ID: "chunk-" + H("chunk", entityId, sourceVersion, documentType, ordinal or
    * "", textHash). documentType distinguishes profiles from chunks.
    */
  def chunkId(
      entityId: String,
      sourceVersion: String,
      documentType: String,
      ordinal: Option[Int],
      textHash: String
  ): String =
    "chunk-" + hash(
      "chunk",
      entityId,
      sourceVersion,
      documentType,
      ordinal.fold("")(_.toString),
      textHash
    )

  /** "ev-" + H("evidence", evidenceType, runId, subjectIds...), subjectIds in the given order (a
    * path keeps hop order). The same fact from the same run always has the same evidence ID.
    */
  def evidenceId(evidenceType: EvidenceType, subjectIds: Seq[String], runId: String): String =
    "ev-" + hash((Seq("evidence", evidenceType.toString, runId) ++ subjectIds)*)

  /** Run ID, timestamp-independent: "run-" + H("run", stage, codeVersion, configVersion, attempt,
    * inputSnapshots...), inputs sorted and de-duplicated. It is the content hash of the immutable
    * run manifest those fields form; attempt (>= 1) separates reruns of identical inputs.
    */
  def runId(
      stage: String,
      codeVersion: String,
      configVersion: String,
      inputSnapshots: Seq[String],
      attempt: Int
  ): String =
    require(attempt >= 1, s"attempt must be >= 1, got $attempt")
    "run-" + hash(
      (Seq(
        "run",
        stage,
        codeVersion,
        configVersion,
        s"$attempt"
      ) ++ inputSnapshots.distinct.sorted)*
    )

/** Shapes of well-formed stable IDs. Every stable ID is visible ASCII (0x21 to 0x7E). */
object IdFormat:
  private val Hex32 = "[0-9a-f]{32}"
  private val Ascii = "[\\x21-\\x7E]"
  val OrcidShape = "\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]".r
  val Version = "[A-Za-z0-9][A-Za-z0-9._-]*".r
  private val KeyPart = "[\\x21-\\x7E&&[^/#]]+"
  private val Work =
    s"(dblp:$KeyPart(/$KeyPart)+|doi:10\\.\\d{4,9}/[\\x21-\\x7E&&[^A-Z]]+|openalex:W\\d+)".r
  private val Position = "[1-9]\\d*".r
  private val ScResearcher = s"scr:[A-Za-z0-9][A-Za-z0-9._-]*:$Hex32".r
  private val Namespaced = s"[a-z][a-z0-9-]*:$Ascii+".r
  private val CoauthorEdge = s"cae-$Hex32".r
  private val GraphEdge = s"ge-$Hex32".r
  private val Chunk = s"chunk-$Hex32".r
  private val Evidence = s"ev-$Hex32".r
  private val Run = s"run-$Hex32".r

  def isWorkId(s: String): Boolean = Work.matches(s)
  def isResearcherId(s: String): Boolean =
    ScResearcher.matches(s) ||
      (s.startsWith("orcid:") && StableIds.normalizeOrcid(s.drop(6)).contains(s.drop(6)))
  def isMentionId(s: String): Boolean =
    val i = s.lastIndexOf('#')
    i > 0 && isWorkId(s.take(i)) && Position.matches(s.drop(i + 1))
  def isNamespacedId(s: String): Boolean = Namespaced.matches(s)
  def isCoauthorEdgeId(s: String): Boolean = CoauthorEdge.matches(s)
  def isGraphEdgeId(s: String): Boolean = GraphEdge.matches(s)
  def isChunkId(s: String): Boolean = Chunk.matches(s)
  def isEvidenceId(s: String): Boolean = Evidence.matches(s)
  def isRunId(s: String): Boolean = Run.matches(s)
  def isVisibleAscii(s: String): Boolean = s.nonEmpty && s.forall(c => c >= 0x21 && c <= 0x7e)
