package scholarchain.entityresolution

import scholarchain.core.{MatchEntityType, StableIds}
import upickle.default.{ReadWriter, read, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable
import scala.util.Try

/** What one ambiguous pair looks like to the adjudicator. `fields` holds untrusted candidate text
  * (names, titles, affiliations) keyed by field name, for example `a.name` and `b.title`; the model
  * may cite only these keys.
  */
final case class AdjudicationRequest(
    entityType: MatchEntityType,
    sourceId: String,
    targetId: String,
    fields: Map[String, String]
):
  /** Canonical JSON (keys sorted), the data block of the prompt and the input of the cache hash.
    * `<` and `>` are written as < and >, so candidate text can never close the data delimiter.
    */
  def canonicalJson: String =
    val obj = ujson.Obj(
      "entityType" -> entityType.toString,
      "sourceId" -> sourceId,
      "targetId" -> targetId,
      "fields" -> ujson.Obj.from(fields.toVector.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))
    )
    ujson.write(obj).replace("<", "\\u003c").replace(">", "\\u003e")

  def inputHash: String = StableIds.textHash(canonicalJson)

enum LlmDecision:
  case ACCEPT, REJECT, ABSTAIN
object LlmDecision:
  given ReadWriter[LlmDecision] =
    upickle.default.readwriter[String].bimap(_.toString, LlmDecision.valueOf)

/** A schema-valid model answer (prompt er-adjudication-v1, JSON schema in the prompt resources). */
final case class AdjudicationResponse(
    sourceId: String,
    targetId: String,
    decision: LlmDecision,
    confidence: Double,
    citedFields: Vector[String],
    reasonCodes: Vector[String]
) derives ReadWriter

/** The adjudication outcome used by the policy. A failure (schema, fabricated ID, client error,
  * budget) is an ABSTAIN with a failure reason, which keeps the pair UNRESOLVED.
  */
final case class Adjudication(
    decision: LlmDecision,
    confidence: Double,
    citedFields: Vector[String],
    reasonCodes: Vector[String],
    promptVersion: String,
    modelVersion: String,
    cached: Boolean,
    failure: Option[String]
)

/** The versioned prompt and its strict response schema. */
object AdjudicationPrompt:
  val Version = "er-adjudication-v1"
  val PromptResource = s"prompts/$Version.txt"
  val SchemaResource = s"prompts/$Version.schema.json"
  private val UserMarker = "=====USER====="

  /** Reason codes the model may return (the schema's enum). */
  val AllowedReasonCodes: Set[String] = Set(
    "NAME_MATCH",
    "NAME_MISMATCH",
    "INITIALS_COMPATIBLE",
    "INITIALS_INCOMPATIBLE",
    "HOMONYM_SUFFIX_MISMATCH",
    "COAUTHOR_OVERLAP",
    "NO_COAUTHOR_OVERLAP",
    "AFFILIATION_MATCH",
    "AFFILIATION_MISMATCH",
    "TOPIC_CONSISTENT",
    "TOPIC_INCONSISTENT",
    "TITLE_MATCH",
    "TITLE_MISMATCH",
    "YEAR_COMPATIBLE",
    "YEAR_INCOMPATIBLE",
    "VENUE_COMPATIBLE",
    "VENUE_INCOMPATIBLE",
    "INSUFFICIENT_EVIDENCE",
    "SUSPECTED_INSTRUCTIONS_IN_DATA"
  )

  private def resource(name: String): String =
    val in = Option(getClass.getClassLoader.getResourceAsStream(name))
      .getOrElse(throw new IllegalStateException(s"missing resource $name"))
    try new String(in.readAllBytes(), UTF_8)
    finally in.close()

  /** (system prompt, user message template) from the versioned prompt file. */
  private lazy val parts: (String, String) =
    val text = resource(PromptResource)
    val i = text.indexOf(UserMarker)
    require(i > 0, s"$PromptResource has no $UserMarker line")
    (text.take(i).trim, text.drop(i + UserMarker.length).trim)
  def system: String = parts._1
  def userTemplate: String = parts._2

  lazy val schema: String = resource(SchemaResource)

  /** The user message: the template with the canonical candidate JSON in the data block. */
  def user(req: AdjudicationRequest): String =
    userTemplate.replace("{{CANDIDATE_JSON}}", req.canonicalJson)

  /** One Markdown code fence around the whole reply (```json ... ``` or ``` ... ```), which Claude
    * models often add despite the prompt's "no code fence" rule.
    */
  private val Fence = """(?s)\A\s*```(?:json|JSON)?[ \t]*\r?\n(.*?)\s*```\s*\z""".r

  /** The reply without one enclosing code fence; any other reply unchanged. */
  def unfence(reply: String): String = reply match
    case Fence(body) => body
    case _           => reply

  /** Strict validation of a model reply. The reply must be exactly one JSON object (surrounding
    * whitespace and one enclosing Markdown code fence allowed, nothing else) with exactly the
    * schema's keys; IDs must equal the request's; cited fields must be request field names; reason
    * codes must be in [[AllowedReasonCodes]]; confidence must be finite and in [0, 1]; ACCEPT and
    * REJECT must cite at least one field.
    */
  def validate(req: AdjudicationRequest, reply: String): Either[String, AdjudicationResponse] =
    val keys =
      Set("sourceId", "targetId", "decision", "confidence", "citedFields", "reasonCodes")
    for
      json <- Try(ujson.read(unfence(reply).trim)).toEither.left.map(_ => "malformed JSON")
      obj <- json.objOpt.toRight("reply is not a JSON object")
      _ <- Either.cond(obj.keySet == keys, (), s"keys ${obj.keySet.toVector.sorted} != schema")
      r <- Try(read[AdjudicationResponse](json)).toEither.left.map(_ => "schema type mismatch")
      _ <- Either.cond(
        r.sourceId == req.sourceId && r.targetId == req.targetId,
        (),
        "echoed IDs differ from the input IDs (fabricated identifier)"
      )
      _ <- Either.cond(
        !r.confidence.isNaN && r.confidence >= 0 && r.confidence <= 1,
        (),
        "confidence outside [0, 1]"
      )
      _ <- Either.cond(
        r.citedFields.forall(req.fields.contains),
        (),
        "cited field not in the input"
      )
      _ <- Either.cond(
        r.decision == LlmDecision.ABSTAIN || r.citedFields.nonEmpty,
        (),
        "ACCEPT or REJECT must cite input fields"
      )
      _ <- Either.cond(
        r.reasonCodes.nonEmpty && r.reasonCodes.forall(AllowedReasonCodes),
        (),
        "reason codes empty or outside the schema enum"
      )
    yield r

  /** The shape of a rejected reply for diagnosis: length, first and last character class, and the
    * model's stop reason. Never the reply text, which may echo candidate data.
    */
  def shape(reply: LlmReply): String =
    val t = reply.text.trim
    def kind(c: Option[Char]) = c.fold("nothing") { c =>
      if "{}`[]\"".contains(c) then s"'$c'" else if c.isLetter then "a letter" else "other"
    }
    s"(reply ${t.length} chars, starts with ${kind(t.headOption)}, ends with " +
      s"${kind(t.lastOption)}, stop reason ${reply.stopReason.getOrElse("unknown")})"

/** A model reply with its token usage and the model's stop reason (for example `end_turn` or
  * `max_tokens`) when the client reports one.
  */
final case class LlmReply(
    text: String,
    inputTokens: Long,
    outputTokens: Long,
    stopReason: Option[String] = None
)

/** LLM client port. Tests use a fake; [[BedrockLlmClient]] is the live adapter. */
trait LlmClient:
  /** The model version recorded in decisions and cache keys (the model ID). */
  def modelVersion: String
  def complete(system: String, user: String, maxOutputTokens: Int): Either[String, LlmReply]

/** One cached, schema-valid adjudication. */
final case class CacheEntry(
    key: String,
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    response: AdjudicationResponse
) derives ReadWriter

/** Adjudication cache port. A DynamoDB adapter belongs to the storage-engineer. */
trait AdjudicationCache:
  def get(key: String): Option[CacheEntry]
  def put(entry: CacheEntry): Unit

object AdjudicationCache:
  /** "adj-" + H("er-adjudication", inputHash, promptVersion, modelVersion, policyVersion). */
  def key(
      inputHash: String,
      promptVersion: String,
      modelVersion: String,
      policyVersion: String
  ): String =
    "adj-" + StableIds.hash(
      "er-adjudication",
      inputHash,
      promptVersion,
      modelVersion,
      policyVersion
    )

/** Local JSON Lines cache: loads the file once, appends one line per new entry. Not thread-safe. */
final class JsonlFileCache(path: Path) extends AdjudicationCache:
  private val entries = mutable.Map.empty[String, CacheEntry]
  if Files.exists(path) then
    Files.readAllLines(path, UTF_8).forEach { l =>
      if l.trim.nonEmpty then
        val e = read[CacheEntry](l)
        entries(e.key) = e
    }
  def get(key: String): Option[CacheEntry] = entries.get(key)
  def put(entry: CacheEntry): Unit =
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.writeString(
      path,
      write(entry) + "\n",
      UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
    entries(entry.key) = entry

/** Per-run bounds: at most `maxAdjudications` uncached requests and `maxTokens` tokens (input plus
  * output, every attempt counted). Cache hits are free.
  */
final class AdjudicationBudget(val maxAdjudications: Int, val maxTokens: Long):
  require(maxAdjudications >= 0 && maxTokens >= 0, "budget must be non-negative")
  private var used = 0
  private var tokens = 0L
  def adjudications: Int = used
  def tokensUsed: Long = tokens
  def canStart(estimatedTokens: Long): Boolean =
    used < maxAdjudications && tokens + estimatedTokens <= maxTokens
  def canRetry(estimatedTokens: Long): Boolean = tokens + estimatedTokens <= maxTokens
  def start(): Unit = used += 1
  def spend(n: Long): Unit = tokens += n

/** Bounded adjudication: cache first, then at most `maxAttempts` model calls (a bounded retry on
  * malformed or schema-invalid replies), all within the budget. Never alters source records and
  * never returns an identifier that is not in the request.
  */
final class Adjudicator(
    client: LlmClient,
    cache: AdjudicationCache,
    budget: AdjudicationBudget,
    policyVersion: String,
    maxOutputTokens: Int = 400,
    maxAttempts: Int = 2
):
  require(maxAttempts >= 1, "maxAttempts must be >= 1")

  /** Rough pre-call token estimate (4 characters per token) plus the output allowance. */
  private def estimate(system: String, user: String): Long =
    (system.length + user.length) / 4L + maxOutputTokens

  /** None when the budget does not allow a new uncached adjudication (nothing was called). */
  def adjudicate(req: AdjudicationRequest): Option[Adjudication] =
    val pv = AdjudicationPrompt.Version
    val key = AdjudicationCache.key(req.inputHash, pv, client.modelVersion, policyVersion)
    def from(r: AdjudicationResponse, cached: Boolean) =
      Adjudication(
        r.decision,
        r.confidence,
        r.citedFields,
        r.reasonCodes,
        pv,
        client.modelVersion,
        cached,
        None
      )
    def abstain(why: String) =
      Adjudication(
        LlmDecision.ABSTAIN,
        0.0,
        Vector.empty,
        Vector.empty,
        pv,
        client.modelVersion,
        false,
        Some(why)
      )
    cache.get(key) match
      case Some(e) => Some(from(e.response, cached = true))
      case None    =>
        val (system, user) = (AdjudicationPrompt.system, AdjudicationPrompt.user(req))
        val est = estimate(system, user)
        if !budget.canStart(est) then None
        else
          budget.start()
          def attempt(n: Int, lastError: String): Adjudication =
            if n > maxAttempts then abstain(lastError)
            else if n > 1 && !budget.canRetry(est) then
              abstain(s"$lastError; token budget exhausted")
            else
              client.complete(system, user, maxOutputTokens) match
                case Left(err) =>
                  attempt(n + 1, s"client error: $err")
                case Right(reply) =>
                  budget.spend(reply.inputTokens + reply.outputTokens)
                  AdjudicationPrompt.validate(req, reply.text) match
                    case Right(r) =>
                      cache.put(
                        CacheEntry(key, req.inputHash, pv, client.modelVersion, policyVersion, r)
                      )
                      from(r, cached = false)
                    case Left(err) => attempt(n + 1, s"$err ${AdjudicationPrompt.shape(reply)}")
          Some(attempt(1, "not attempted"))
