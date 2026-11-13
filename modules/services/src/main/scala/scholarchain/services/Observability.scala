package scholarchain.services

import scholarchain.rag.runtime.{AuditRecord, StageTiming}

import java.time.Instant
import java.util.concurrent.atomic.LongAdder
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/** Structured logs (ruling 12): one JSON line per request and per workflow stage. Only the fields
  * named here are ever written: never a body, a question, a key, or retrieved or model text.
  */
final class JsonLog(sink: String => Unit):
  private def emit(kind: String, fields: (String, ujson.Value)*): Unit =
    val line = ujson.Obj.from(
      Seq("ts" -> ujson.Str(Instant.now.toString), "type" -> ujson.Str(kind)) ++ fields
    )
    sink(line.render())

  private def opt(s: Option[String]): ujson.Value = s.fold(ujson.Null)(ujson.Str(_))

  def request(
      service: String,
      traceId: String,
      requestId: String,
      method: String,
      route: String,
      status: Int,
      latencyMs: Long,
      error: Option[String]
  ): Unit =
    emit(
      "request",
      "service" -> service,
      "traceId" -> traceId,
      "requestId" -> requestId,
      "method" -> method,
      "route" -> route,
      "status" -> status,
      "latencyMs" -> latencyMs.toDouble,
      "error" -> opt(error)
    )

  def stage(r: Request, queryId: String, s: StageTiming): Unit =
    emit(
      "stage",
      "traceId" -> r.traceId,
      "requestId" -> r.requestId,
      "queryId" -> queryId,
      "stage" -> s.stage.toString,
      "outcome" -> s.outcome.toString,
      "latencyMs" -> s.latencyMs.toDouble
    )

  /** A workflow stage of an async job (worker): correlated by job and query ID, which query-api's
    * job-queued event links to the trace and request IDs of the POST.
    */
  def jobStage(jobId: String, queryId: String, s: StageTiming): Unit =
    emit(
      "stage",
      "jobId" -> jobId,
      "queryId" -> queryId,
      "stage" -> s.stage.toString,
      "outcome" -> s.outcome.toString,
      "latencyMs" -> s.latencyMs.toDouble
    )

  def event(service: String, what: String, fields: (String, ujson.Value)*): Unit =
    emit("event", (Seq[(String, ujson.Value)]("service" -> service, "event" -> what) ++ fields)*)

object JsonLog:
  val stdout: JsonLog = JsonLog(line => System.out.synchronized(System.out.println(line)))

/** In-process counters for GET /metrics (ruling 12). Latency percentiles are over the most recent
  * Window requests of each route.
  */
final class Metrics:
  // shortcut: percentiles over a per-route sample of the last 1024 requests; use a histogram if
  // the dashboard needs whole-run percentiles (CloudWatch computes them from the request logs).
  private val Window = 1024
  private val requests = TrieMap.empty[(String, Int), LongAdder]
  private val latencies = TrieMap.empty[String, mutable.ArrayDeque[Long]]
  private val counters = TrieMap.empty[String, LongAdder]

  def add(name: String, n: Long = 1): Unit = counters.getOrElseUpdate(name, LongAdder()).add(n)

  def request(route: String, status: Int, ms: Long): Unit =
    requests.getOrElseUpdate((route, status), LongAdder()).increment()
    val q = latencies.getOrElseUpdate(route, mutable.ArrayDeque.empty[Long])
    q.synchronized {
      q.append(ms)
      if q.size > Window then q.removeHead()
    }
    if status == 504 then add("timeouts")

  /** Model, verification, and abstention counters of one answered query. */
  def answer(a: AuditRecord): Unit =
    add("answers")
    add("llmRequests", a.llmCalls.map(_.requests.toLong).sum)
    add("llmInputTokens", a.llmCalls.flatMap(_.inputTokens).map(_.toLong).sum)
    add("llmOutputTokens", a.llmCalls.flatMap(_.outputTokens).map(_.toLong).sum)
    add("verifiedClaims", a.verification.verified.toLong)
    add("partiallySupportedClaims", a.verification.partiallySupported.toLong)
    add("unsupportedClaims", a.verification.unsupported.toLong)
    add("conflictingClaims", a.verification.conflicting.toLong)
    if a.abstained then add("abstentions")

  private def pct(sorted: Vector[Long], p: Double): Long =
    if sorted.isEmpty then 0L
    else sorted(math.min(sorted.size - 1, math.ceil(p * sorted.size).toInt - 1).max(0))

  def snapshot: ujson.Obj =
    val byRoute = requests.toVector
      .groupMap(_._1._1)((k, v) => k._2.toString -> v.sum())
      .map((route, xs) =>
        route -> ujson.Obj.from(xs.sortBy(_._1).map((s, n) => s -> ujson.Num(n.toDouble)))
      )
    val lat = latencies.toVector.map { (route, q) =>
      val xs = q.synchronized(q.toVector).sorted
      route -> ujson.Obj(
        "count" -> xs.size,
        "p50" -> pct(xs, 0.50).toDouble,
        "p95" -> pct(xs, 0.95).toDouble,
        "p99" -> pct(xs, 0.99).toDouble
      )
    }
    val names = Vector(
      "timeouts",
      "answers",
      "abstentions",
      "llmRequests",
      "llmInputTokens",
      "llmOutputTokens",
      "verifiedClaims",
      "partiallySupportedClaims",
      "unsupportedClaims",
      "conflictingClaims",
      "queueReceives",
      "jobsQueued",
      "jobsSucceeded",
      "jobsFailed",
      "duplicateDeliveries",
      "poisonMessages",
      "stuckJobsFailed"
    )
    ujson.Obj(
      "requests" -> ujson.Obj.from(byRoute.toVector.sortBy(_._1)),
      "latencyMs" -> ujson.Obj.from(lat.sortBy(_._1)),
      "counters" -> ujson.Obj.from(
        (names ++ counters.keys.toVector.filterNot(names.contains).sorted)
          .map(n => n -> ujson.Num(counters.get(n).fold(0L)(_.sum()).toDouble))
      )
    )
