package scholarchain.entityresolution

import scholarchain.core.*
import scholarchain.core.config.EntityResolutionConfig

/** er-v1 decision thresholds (configuration `entity-resolution.*`). A score at or above `accept` is
  * ACCEPTED, below `reject` is REJECTED, anything between is UNRESOLVED; UNRESOLVED scores in
  * [llmBandMin, llmBandMax] are eligible for bounded LLM adjudication.
  */
final case class Thresholds(accept: Double, reject: Double, llmBandMin: Double, llmBandMax: Double):
  require(
    0 <= reject && reject <= llmBandMin && llmBandMin <= llmBandMax && llmBandMax <= accept &&
      accept <= 1,
    s"thresholds must satisfy 0 <= reject <= llmBandMin <= llmBandMax <= accept <= 1: $this"
  )

object Thresholds:
  def from(c: EntityResolutionConfig): Thresholds =
    Thresholds(c.acceptThreshold, c.rejectThreshold, c.llmBandMin, c.llmBandMax)

/** Where a candidate pair came from (the Hadoop output it was read from). */
enum CandidateOrigin:
  /** J03+J04 `matches/`: a record sharing an exact identifier key with its target. */
  case EXACT_MATCH

  /** J03+J04 `conflicts/`: two records of one source (or one work) under one identifier key. */
  case EXACT_CONFLICT

  /** J06 `candidates/`: a blocked pair with feat-v1 features. */
  case BLOCKED

/** The evidence level that decided a pair (spec "Entity linkage policy" hierarchy). LLM_BAND is
  * PROBABILISTIC with a score inside the LLM band; LLM is a pair an adjudication decided.
  */
enum MatchPath:
  case EXACT_ID, DETERMINISTIC, PROBABILISTIC, LLM_BAND, LLM

/** One verdict before it becomes a MatchDecision.
  *
  * @param confidence
  *   the policy's estimate that the pair is the same entity, in [0, 1], for every outcome (so a
  *   REJECTED pair has a low value). Exact-ID matches are 1.0, rule rejections 0.0, contradictions
  *   (CONFLICT) and pairs without enough evidence 0.5.
  * @param policyVersion
  *   the match policy version that produced the verdict; [[MatchPolicy.toDecision]] records it.
  */
final case class Verdict(
    outcome: MatchOutcome,
    confidence: Double,
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    policyVersion: String = MatchPolicy.V1
):
  def path: MatchPath = MatchPolicy.pathOf(rulesFired)
  def llmEligible: Boolean = rulesFired.contains(MatchPolicy.Rule.BandLlm)

/** Match policies er-v1 and er-v2: pure, deterministic, and free of Hadoop, Spark, and AWS
  * dependencies, so a MapReduce job can call [[decide]] per candidate. Every entry point takes the
  * policy version (default er-v1, so callers written for er-v1 reproduce its outputs exactly).
  *
  * er-v1 order of evaluation (first applicable step wins):
  *   1. Origin EXACT_CONFLICT: CONFLICT (`EXACT_ID_GROUP_CONFLICT`).
  *   2. Origin EXACT_MATCH: a `doi:`, `arxiv:`, or `orcid:` key is ACCEPTED (confidence 1.0). Any
  *      other key (OpenAlex author IDs are algorithmic clusters, see the dataset manifest) is
  *      UNRESOLVED with `NON_AUTHORITATIVE_SOURCE_ID`.
  *   3. Blocked pairs, identifier evidence. Agreements: orcid_agreement = 1 (researchers);
  *      doi_agreement = 1 or arxiv_agreement = 1 (works). Disagreements: orcid_agreement = 0 and
  *      homonym_suffix_agreement = 0 (DBLP numbers its homonyms, so two different numbers are DBLP
  *      saying "different people"); doi_agreement = 0, arxiv_agreement = 0, and
  *      dblp_record_agreement = 0 (works: two DBLP records are DBLP-curated distinct works, the
  *      same stance J03+J04 takes when two DBLP works share a DOI).
  *      - agreement and disagreement together: CONFLICT (`IDENTIFIER_CONFLICT`);
  *      - agreement only: ACCEPTED (path EXACT_ID, confidence 1.0);
  *      - disagreement only: CONFLICT when the deterministic rule or the weighted score alone would
  *        have ACCEPTED (`BIBLIOGRAPHIC_EVIDENCE_CONTRADICTS_ID`), else REJECTED (confidence 0.0).
  *        Disagreeing identifiers are never merged.
  *   4. Deterministic bibliographic rule, ACCEPTED with confidence max(score, accept):
  *      - researchers: identical comparable names (name_similarity = 1) with the same DBLP homonym
  *        number (homonym_suffix_agreement = 1), which is the same DBLP person key;
  *      - works: identical title token sets (title_similarity = 1), the same year (year_distance =
  *        1), the same author family-name sequence (author_sequence_agreement = 1), and no venue
  *        disagreement (venue_compatibility absent or > 0).
  *   5. Weighted score (see [[score]]): UNRESOLVED with `INSUFFICIENT_EVIDENCE` when the required
  *      feature is absent (name_similarity for researchers, title_similarity for works); otherwise
  *      the thresholds decide, and an UNRESOLVED score inside the LLM band is marked LLM-eligible.
  *
  * er-v2 (human ruling after phase-05 work order 7) is er-v1 with two WORK-only additions;
  * researcher rules, all weights, and the thresholds are unchanged:
  *   - step 3, after "agreement and disagreement together" and before "agreement only": a blocked
  *     WORK pair with doi_agreement = 1 and source_agreement = 1 (both records from one source) is
  *     CONFLICT (`SAME_SOURCE_SHARED_DOI`), the stance J03+J04 take on a DOI shared within a
  *     source;
  *   - step 4, after the er-v1 work rule: identical normalized titles (title_exact_agreement = 1),
  *     the same author family-name sequence (author_sequence_agreement = 1), and years at most one
  *     apart (year_distance >= 0.5), venue ignored, is ACCEPTED on the DETERMINISTIC path
  *     (`EXACT_TITLE_AUTHORS_YEAR_WITHIN_1`). Like the er-v1 rule it also counts as "would have
  *     ACCEPTED" in step 3, so a DOI disagreement with this evidence is CONFLICT, never ACCEPTED.
  * The two extra features come from [[WorkPairFeatures.work]] called with er-v2.
  *
  * er-v2 also adds one RESEARCHER exact-group rule (human ruling after phase-05 review 1), applied
  * by J03+J04 before step 2 through [[orcidGroupSuffixConflict]]: an `orcid:` group whose member
  * mentions carry two or more distinct DBLP homonym numbers is a conflict group, so its pairs reach
  * step 1 as EXACT_CONFLICT and are never merged.
  */
object MatchPolicy:
  val V1 = "er-v1"
  val V2 = "er-v2"
  val Versions: Vector[String] = Vector(V1, V2)

  /** The version applied when a caller passes none. */
  val Version: String = V1

  /** The version itself when implemented; fails otherwise (an unknown policy never runs). */
  def requireVersion(v: String): String =
    require(
      Versions.contains(v),
      s"match policy $v is not implemented (${Versions.mkString(", ")})"
    )
    v

  /** Rule IDs recorded in MatchDecision.rulesFired. */
  object Rule:
    val ExactIdMatch = "er-v1:exact-id-match"
    val ExactIdNonAuthoritative = "er-v1:exact-id-non-authoritative"
    val ExactGroupConflict = "er-v1:exact-id-group-conflict"
    val IdentifierAgreement = "er-v1:identifier-agreement"
    val IdentifierDisagreement = "er-v1:identifier-disagreement"
    val Deterministic = "er-v1:deterministic-bibliographic"
    val InsufficientEvidence = "er-v1:insufficient-evidence"
    val WeightedScore = "er-v1:weighted-score"
    val AbsentNeutral = "er-v1:absent-features-neutral"
    val BandAccept = "er-v1:band-accept"
    val BandReject = "er-v1:band-reject"
    val BandUnresolved = "er-v1:band-unresolved"
    val BandLlm = "er-v1:band-llm"
    val LlmAdjudication = "er-v1:llm-adjudication"
    val SameSourceSharedDoi = "er-v2:same-source-shared-doi"
    val DeterministicWork = "er-v2:deterministic-title-authors-year"
    val OrcidGroupSuffixConflict = "er-v2:orcid-group-homonym-suffix-conflict"

  /** Work-pair features added by [[WorkPairFeatures]] (er-v1 inputs, not part of feat-v1). */
  val DoiAgreement = "doi_agreement"
  val ArxivAgreement = "arxiv_agreement"
  val AuthorOverlap = "author_overlap"
  val AuthorSequenceAgreement = "author_sequence_agreement"
  val DblpRecordAgreement = "dblp_record_agreement"

  /** Work-pair features added by [[WorkPairFeatures]] for er-v2 only. */
  val SourceAgreement = "source_agreement"
  val TitleExactAgreement = "title_exact_agreement"

  /** One weighted-score term: contributes weight * (value - neutral) log-odds when present. */
  final case class Term(feature: String, weight: Double, neutral: Double)

  /** Researcher score terms. Rationale (calibration on ORCID-agreeing versus ORCID-disagreeing
    * blocked pairs of the full local run, ORCID removed before scoring; see the Calibrate tool):
    * names and initials separate the bulk of non-matches, coauthor overlap is the main
    * corroborating evidence, author order is nearly uninformative. With no corroboration, an exact
    * name alone lands in the UNRESOLVED band, because a matching name is not identity.
    */
  val ResearcherBias: Double = -1.0
  val ResearcherTerms: Vector[Term] = Vector(
    Term(Features.NameSimilarity, 10.0, 0.90),
    Term(Features.InitialsCompatibility, 4.0, 0.50),
    Term(Features.CoauthorOverlap, 12.0, 0.05),
    Term(Features.AffiliationCompatibility, 4.0, 0.50),
    Term(Features.AuthorOrderAgreement, 0.5, 0.50)
  )

  /** Work score terms: title agreement dominates; year, venue, and author overlap corroborate. The
    * bias keeps an identical title, year, and venue without author evidence at logistic(2.4) =
    * 0.917, below the accept threshold, because a matching title is not identity ("Session
    * Introduction." in the local run). No silver work labels exist in the local run, so these are
    * documented priors (reported as a risk).
    */
  val WorkBias: Double = -3.0
  val WorkTerms: Vector[Term] = Vector(
    Term(Features.TitleSimilarity, 12.0, 0.80),
    Term(Features.YearDistance, 4.0, 0.50),
    Term(Features.VenueCompatibility, 2.0, 0.50),
    Term(AuthorOverlap, 6.0, 0.50)
  )

  def terms(t: MatchEntityType): (Double, Vector[Term]) = t match
    case MatchEntityType.RESEARCHER => (ResearcherBias, ResearcherTerms)
    case MatchEntityType.WORK       => (WorkBias, WorkTerms)

  def requiredFeature(t: MatchEntityType): String = t match
    case MatchEntityType.RESEARCHER => Features.NameSimilarity
    case MatchEntityType.WORK       => Features.TitleSimilarity

  /** Weighted score: logistic(bias + sum of weight * (value - neutral)) over the PRESENT terms.
    * Absent-feature rule: an absent feature contributes 0 log-odds (it neither supports nor opposes
    * the match); it is never read as 0.0, which would mean "the evidence disagrees". Identifier
    * features are not score terms; they are rules. None when the required feature is absent.
    */
  def score(t: MatchEntityType, features: Map[String, Double]): Option[Double] =
    val (bias, ts) = terms(t)
    Option.when(features.contains(requiredFeature(t)))(
      logistic(
        bias + ts.flatMap(x => features.get(x.feature).map(v => x.weight * (v - x.neutral))).sum
      )
    )

  def logistic(z: Double): Double = 1.0 / (1.0 + math.exp(-z))

  /** Score terms absent from the features, sorted (recorded as `absent:<feature>` rules). */
  def absentTerms(t: MatchEntityType, features: Map[String, Double]): Vector[String] =
    terms(t)._2.map(_.feature).filterNot(features.contains).sorted

  /** The deterministic rule that fires, as (rule ID, reason code). */
  private def deterministicRule(
      t: MatchEntityType,
      f: Map[String, Double],
      version: String
  ): Option[(String, String)] =
    t match
      case MatchEntityType.RESEARCHER =>
        Option.when(
          f.get(Features.NameSimilarity).contains(1.0) &&
            f.get(Features.HomonymSuffixAgreement).contains(1.0)
        )(Rule.Deterministic -> "SAME_NAME_SAME_HOMONYM_SUFFIX")
      case MatchEntityType.WORK =>
        Option
          .when(
            f.get(Features.TitleSimilarity).contains(1.0) &&
              f.get(Features.YearDistance).contains(1.0) &&
              f.get(AuthorSequenceAgreement).contains(1.0) &&
              f.get(Features.VenueCompatibility).forall(_ > 0.0)
          )(Rule.Deterministic -> "EXACT_TITLE_YEAR_AUTHORS")
          .orElse(
            Option.when(
              version == V2 &&
                f.get(TitleExactAgreement).contains(1.0) &&
                f.get(AuthorSequenceAgreement).contains(1.0) &&
                f.get(Features.YearDistance).exists(_ >= 0.5)
            )(Rule.DeterministicWork -> "EXACT_TITLE_AUTHORS_YEAR_WITHIN_1")
          )

  private def identifierEvidence(
      t: MatchEntityType,
      f: Map[String, Double]
  ): (Vector[String], Vector[String]) =
    val checks = t match
      case MatchEntityType.RESEARCHER =>
        Vector(
          (Features.OrcidAgreement, "EXACT_ORCID_MATCH", "ORCID_MISMATCH"),
          (Features.HomonymSuffixAgreement, "", "HOMONYM_SUFFIX_MISMATCH")
        )
      case MatchEntityType.WORK =>
        Vector(
          (DoiAgreement, "EXACT_DOI_MATCH", "DOI_MISMATCH"),
          (ArxivAgreement, "EXACT_ARXIV_MATCH", "ARXIV_MISMATCH"),
          (DblpRecordAgreement, "", "DBLP_RECORD_MISMATCH")
        )
    val agree = checks.collect { case (k, a, _) if a.nonEmpty && f.get(k).contains(1.0) => a }
    val disagree = checks.collect { case (k, _, d) if f.get(k).contains(0.0) => d }
    (agree, disagree)

  private val AuthoritativeExactNamespaces = Set("doi", "arxiv", "orcid")

  /** Rule [[Rule.OrcidGroupSuffixConflict]]: true when policy `version` makes the researcher
    * exact-identifier group `key` a conflict group because its member mentions (raw printed names)
    * carry two or more distinct DBLP homonym numbers, the same Normalize.name suffix that feeds
    * feat-v1 homonym_suffix_agreement (DBLP saying "different people" under one ORCID). Only
    * `orcid:` keys under er-v2; always false under er-v1. A mention without a suffix never
    * conflicts. Pure; J03+J04 sends the whole group to conflicts/.
    */
  def orcidGroupSuffixConflict(
      key: String,
      memberRawNames: Seq[String],
      version: String
  ): Boolean =
    requireVersion(version) == V2 && key.startsWith("orcid:") &&
      memberRawNames.flatMap(Normalize.name(_).homonymSuffix).distinct.size >= 2

  /** The verdict of policy `version` for one candidate. Pure and deterministic. */
  def decide(
      c: MatchCandidate,
      origin: CandidateOrigin,
      t: Thresholds,
      version: String = Version
  ): Verdict =
    requireVersion(version)
    val v = origin match
      case CandidateOrigin.EXACT_CONFLICT =>
        Verdict(
          MatchOutcome.CONFLICT,
          0.5,
          Vector(Rule.ExactGroupConflict),
          Vector("EXACT_ID_GROUP_CONFLICT")
        )
      case CandidateOrigin.EXACT_MATCH =>
        val ns = c.blockingKey.takeWhile(_ != ':')
        if AuthoritativeExactNamespaces(ns) then
          Verdict(
            MatchOutcome.ACCEPTED,
            1.0,
            Vector(Rule.ExactIdMatch),
            Vector(s"EXACT_${ns.toUpperCase}_MATCH")
          )
        else
          Verdict(
            MatchOutcome.UNRESOLVED,
            0.5,
            Vector(Rule.ExactIdNonAuthoritative),
            Vector("NON_AUTHORITATIVE_SOURCE_ID")
          )
      case CandidateOrigin.BLOCKED => decideBlocked(c.entityType, c.features, t, version)
    v.copy(policyVersion = version)

  /** Steps 3 to 5 for a blocked pair's features under policy `version`. */
  def decideBlocked(
      et: MatchEntityType,
      f: Map[String, Double],
      t: Thresholds,
      version: String = Version
  ): Verdict =
    requireVersion(version)
    val (agree, disagree) = identifierEvidence(et, f)
    val s = score(et, f)
    val det = deterministicRule(et, f, version)
    val scoreRules =
      Vector(Rule.WeightedScore) ++ absentTerms(et, f).map(a => s"${Rule.AbsentNeutral}:$a")
    def wouldAccept = det.isDefined || s.exists(_ >= t.accept)
    val sameSourceDoi = version == V2 && et == MatchEntityType.WORK &&
      f.get(DoiAgreement).contains(1.0) && f.get(SourceAgreement).contains(1.0)
    val v = if agree.nonEmpty && disagree.nonEmpty then
      Verdict(
        MatchOutcome.CONFLICT,
        0.5,
        Vector(Rule.IdentifierAgreement, Rule.IdentifierDisagreement),
        (agree ++ disagree :+ "IDENTIFIER_CONFLICT")
      )
    else if sameSourceDoi then
      Verdict(
        MatchOutcome.CONFLICT,
        0.5,
        Vector(Rule.IdentifierAgreement, Rule.SameSourceSharedDoi),
        agree :+ "SAME_SOURCE_SHARED_DOI"
      )
    else if agree.nonEmpty then
      Verdict(MatchOutcome.ACCEPTED, 1.0, Vector(Rule.IdentifierAgreement), agree)
    else if disagree.nonEmpty then
      if wouldAccept then
        Verdict(
          MatchOutcome.CONFLICT,
          0.5,
          Vector(Rule.IdentifierDisagreement) ++ det.map(_._1) ++ scoreRules,
          disagree ++ det.map(_._2) :+ "BIBLIOGRAPHIC_EVIDENCE_CONTRADICTS_ID"
        )
      else Verdict(MatchOutcome.REJECTED, 0.0, Vector(Rule.IdentifierDisagreement), disagree)
    else
      det match
        case Some((rule, reason)) =>
          Verdict(
            MatchOutcome.ACCEPTED,
            math.max(s.getOrElse(t.accept), t.accept),
            Vector(rule),
            Vector(reason)
          )
        case None =>
          s match
            case None =>
              Verdict(
                MatchOutcome.UNRESOLVED,
                0.5,
                Vector(Rule.InsufficientEvidence),
                Vector("INSUFFICIENT_EVIDENCE")
              )
            case Some(p) => banded(p, t, scoreRules)
    v.copy(policyVersion = version)

  /** Applies the thresholds to a probabilistic score. */
  def banded(p: Double, t: Thresholds, rules: Vector[String]): Verdict =
    if p >= t.accept then
      Verdict(
        MatchOutcome.ACCEPTED,
        p,
        rules :+ Rule.BandAccept,
        Vector("SCORE_AT_OR_ABOVE_ACCEPT")
      )
    else if p < t.reject then
      Verdict(MatchOutcome.REJECTED, p, rules :+ Rule.BandReject, Vector("SCORE_BELOW_REJECT"))
    else if p >= t.llmBandMin && p <= t.llmBandMax then
      Verdict(MatchOutcome.UNRESOLVED, p, rules :+ Rule.BandLlm, Vector("SCORE_IN_LLM_BAND"))
    else
      Verdict(MatchOutcome.UNRESOLVED, p, rules :+ Rule.BandUnresolved, Vector("SCORE_UNRESOLVED"))

  /** The match path a set of fired rules represents. */
  def pathOf(rules: Seq[String]): MatchPath =
    if rules.contains(Rule.LlmAdjudication) then MatchPath.LLM
    else if rules.exists(r =>
        r == Rule.ExactIdMatch || r == Rule.ExactIdNonAuthoritative ||
          r == Rule.ExactGroupConflict || r == Rule.IdentifierAgreement ||
          r == Rule.IdentifierDisagreement || r == Rule.SameSourceSharedDoi
      )
    then MatchPath.EXACT_ID
    else if rules.exists(r => r == Rule.Deterministic || r == Rule.DeterministicWork) then
      MatchPath.DETERMINISTIC
    else if rules.contains(Rule.BandLlm) then MatchPath.LLM_BAND
    else MatchPath.PROBABILISTIC

  /** Folds a validated adjudication into an LLM-eligible verdict. ACCEPT becomes ACCEPTED only when
    * the model's confidence reaches the accept threshold; REJECT becomes REJECTED only when 1 -
    * confidence is below the reject threshold; anything else, including ABSTAIN, stays UNRESOLVED.
    * The confidence becomes the model's match probability (confidence for ACCEPT, 1 - confidence
    * for REJECT).
    */
  def withAdjudication(v: Verdict, a: Adjudication, t: Thresholds): Verdict =
    require(v.llmEligible, "only LLM-band verdicts are adjudicated")
    val rules = v.rulesFired :+ Rule.LlmAdjudication
    val llmCodes = a.reasonCodes.map("LLM_" + _)
    a.decision match
      case LlmDecision.ACCEPT if a.confidence >= t.accept =>
        v.copy(MatchOutcome.ACCEPTED, a.confidence, rules, "LLM_ACCEPT" +: llmCodes)
      case LlmDecision.REJECT if 1 - a.confidence < t.reject =>
        v.copy(MatchOutcome.REJECTED, 1 - a.confidence, rules, "LLM_REJECT" +: llmCodes)
      case LlmDecision.ABSTAIN =>
        v.copy(rulesFired = rules, reasonCodes = v.reasonCodes ++ ("LLM_ABSTAIN" +: llmCodes))
      case _ =>
        v.copy(
          rulesFired = rules,
          reasonCodes = v.reasonCodes ++ ("LLM_LOW_CONFIDENCE" +: llmCodes)
        )

  /** The auditable MatchDecision for a verdict. Provenance is DERIVED, keeps the candidate's
    * sourceRecordId and snapshot, and records the run, the verdict's policy version, and the model
    * version.
    */
  def toDecision(
      c: MatchCandidate,
      features: Map[String, Double],
      v: Verdict,
      runId: String,
      decidedAt: String,
      promptVersion: Option[String],
      modelVersion: Option[String]
  ): MatchDecision =
    MatchDecision(
      SchemaVersions.MatchDecision,
      c.entityType,
      c.sourceId,
      c.targetId,
      features,
      v.rulesFired,
      v.outcome,
      v.confidence,
      v.reasonCodes,
      promptVersion,
      decidedAt,
      Provenance(
        SchemaVersions.Provenance,
        SourceType.DERIVED,
        c.provenance.sourceRecordId,
        c.provenance.snapshot,
        runId,
        Some(v.policyVersion),
        modelVersion
      )
    )

/** Work-pair features for two works (policy inputs, not part of feat-v1). A Hadoop job adds them to
  * a blocked work candidate's features before calling MatchPolicy.decide with the same policy
  * version. Each is absent when either side lacks the evidence.
  *   - doi_agreement, arxiv_agreement: 1.0 equal (DOIs normalized), 0.0 different;
  *   - author_overlap: Jaccard of the authors' family keys (Normalize.name(...).familyKey);
  *   - author_sequence_agreement: 1.0 when the family-key sequences are equal in order, else 0.0;
  *   - dblp_record_agreement: present only when both works are DBLP records; 1.0 for the same work
  *     ID, 0.0 for two different DBLP records.
  * er-v2 adds (er-v1 feature maps stay exactly as they were):
  *   - source_agreement: 1.0 when both records come from one source, else 0.0;
  *   - title_exact_agreement: 1.0 when the Normalize.title forms are equal, else 0.0.
  */
object WorkPairFeatures:
  def work(
      a: WorkRecord,
      b: WorkRecord,
      version: String = MatchPolicy.Version
  ): Map[String, Double] =
    def agree(x: Option[String], y: Option[String]) =
      for p <- x; q <- y yield if p == q then 1.0 else 0.0
    def families(w: WorkRecord) =
      w.authors.sortBy(_.position).map(m => Normalize.name(m.rawName).familyKey).filter(_.nonEmpty)
    val (fa, fb) = (families(a), families(b))
    val both = fa.nonEmpty && fb.nonEmpty
    val v2: Seq[(String, Option[Double])] =
      if MatchPolicy.requireVersion(version) == MatchPolicy.V1 then Nil
      else
        Seq(
          MatchPolicy.SourceAgreement -> Some(if a.source == b.source then 1.0 else 0.0),
          MatchPolicy.TitleExactAgreement -> agree(
            a.title.map(Normalize.title).filter(_.nonEmpty),
            b.title.map(Normalize.title).filter(_.nonEmpty)
          )
        )
    (Seq(
      MatchPolicy.DoiAgreement ->
        agree(a.doi.map(StableIds.normalizeDoi), b.doi.map(StableIds.normalizeDoi)),
      MatchPolicy.ArxivAgreement -> agree(a.arxivId.map(_.trim), b.arxivId.map(_.trim)),
      MatchPolicy.AuthorOverlap ->
        Option.when(both)((fa.toSet & fb.toSet).size.toDouble / (fa.toSet | fb.toSet).size),
      MatchPolicy.AuthorSequenceAgreement -> Option.when(both)(if fa == fb then 1.0 else 0.0),
      MatchPolicy.DblpRecordAgreement ->
        Option.when(a.source == SourceType.DBLP && b.source == SourceType.DBLP)(
          if a.workId == b.workId then 1.0 else 0.0
        )
    ) ++ v2).collect { case (k, Some(v)) => k -> v }.toMap
