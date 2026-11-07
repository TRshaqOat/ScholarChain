package scholarchain.storage

import scholarchain.core.config.TimeWindow

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** One validator finding. line is 1-based; 0 means the whole file or the whole set. */
final case class Violation(source: String, line: Long, code: String, detail: String)

object Hashing:
  /** "sha256:" + 64 lower-case hex digits, the same form as StableIds.textHash. */
  def sha256(bytes: Array[Byte]): String =
    "sha256:" + MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  def sha256(text: String): String = sha256(text.getBytes(UTF_8))

  val Shape = "sha256:[0-9a-f]{64}".r

/** Shared checks for provenance fields every store record carries. */
object ProvenanceRules:
  val Snapshot = "\\d{4}-\\d{2}-\\d{2}".r

  /** Configured windows by name (core graph.windows). */
  def windowsByName(windows: Seq[TimeWindow]): Map[String, TimeWindow] =
    windows.map(w => w.name -> w).toMap

/** A failure worth retrying: throttling, timeouts, 5xx. Everything else is not retried. */
final class TransientStoreException(message: String) extends RuntimeException(message)

/** Bounded exponential backoff. Delay before retry n (n >= 1) is min(maxDelayMs, baseDelayMs *
  * 2^(n-1)). Live adapters add jitter; the fakes stay deterministic.
  */
final case class RetryPolicy(maxAttempts: Int, baseDelayMs: Long, maxDelayMs: Long):
  require(maxAttempts >= 1 && baseDelayMs >= 0 && maxDelayMs >= baseDelayMs)
  def delayBeforeRetry(n: Int): Long = math.min(maxDelayMs, baseDelayMs << math.min(n - 1, 20))

object RetryPolicy:
  val Default = RetryPolicy(maxAttempts = 5, baseDelayMs = 200, maxDelayMs = 5000)

object Retry:
  /** Runs op, retrying only TransientStoreException. Returns the result and the attempts used. The
    * last transient failure is rethrown once maxAttempts is reached.
    */
  def run[A](policy: RetryPolicy, sleep: Long => Unit)(op: => A): (A, Int) =
    def go(attempt: Int): (A, Int) =
      try (op, attempt)
      catch
        case e: TransientStoreException if attempt < policy.maxAttempts =>
          sleep(policy.delayBeforeRetry(attempt))
          go(attempt + 1)
    go(1)
