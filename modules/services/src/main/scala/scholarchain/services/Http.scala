package scholarchain.services

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import scholarchain.core.ErrorCode

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.{
  Executor,
  LinkedBlockingQueue,
  RejectedExecutionException,
  ScheduledThreadPoolExecutor,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

/** A typed error that becomes the spec error body (ErrorCode carries status and retryable). The
  * message is fixed service text: never a key, a request body, a question, or an exception message.
  */
final case class ApiError(code: ErrorCode, message: String)
    extends RuntimeException(message, null, false, false)

/** Request and trace IDs (law 3). A client value is echoed when it is 1 to 128 characters of
  * [A-Za-z0-9._:-]; otherwise one is generated.
  */
object Ids:
  private val Valid = "[A-Za-z0-9._:-]{1,128}".r
  def fresh(prefix: String): String = prefix + UUID.randomUUID.toString.replace("-", "")
  def orFresh(client: Option[String], prefix: String): String =
    client.filter(Valid.matches).getOrElse(fresh(prefix))

/** One API key compared in constant time (ruling 5): both sides are hashed to 32 bytes first, so
  * the comparison time depends on neither the key's length nor its content.
  */
final class ApiKeyAuth(key: String):
  require(key.nonEmpty, "the API key is empty")
  private def digest(s: String) = MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8))
  private val expected = digest(key)
  def accepts(header: Option[String]): Boolean =
    MessageDigest.isEqual(digest(header.getOrElse("")), expected)
  override def toString = "ApiKeyAuth(<redacted>)"

/** One HTTP request as routes see it. Path parameters are percent-decoded path segments. */
final class Request(
    val method: String,
    val params: Map[String, String],
    val query: Map[String, Vector[String]],
    headers: String => Option[String],
    readBody: () => Array[Byte],
    val requestId: String,
    val traceId: String
):
  def header(name: String): Option[String] = headers(name)
  lazy val body: Array[Byte] = readBody()

  /** The body as a JSON value: Content-Type must be application/json, and the body must parse. */
  def json(): ujson.Value =
    if !header("Content-Type").exists(_.toLowerCase.startsWith("application/json")) then
      throw ApiError(ErrorCode.INVALID_FIELD, "Content-Type must be application/json")
    val bytes = body // REQUEST_TOO_LARGE surfaces here, not as malformed JSON
    try ujson.read(bytes)
    catch case NonFatal(_) => throw ApiError(ErrorCode.MALFORMED_JSON, "the body is not valid JSON")

final case class Response(status: Int, body: ujson.Value, headers: Map[String, String] = Map.empty)

object Response:
  def ok[A: upickle.default.Writer](a: A, status: Int = 200): Response =
    Response(status, upickle.default.writeJs(a))

/** A route: method, a path template whose {name} segments bind parameters, and whether it needs the
  * API key (only /healthz and /readyz are public).
  */
final case class Route(method: String, template: String, public: Boolean = false)(
    val handle: Request => Response
):
  private val parts = template.split('/').toVector.drop(1)
  def bind(segments: Vector[String]): Option[Map[String, String]] =
    if segments.size != parts.size then None
    else
      parts
        .zip(segments)
        .foldLeft(Option(Map.empty[String, String])) {
          case (Some(m), (p, s)) if p.startsWith("{") => Some(m + (p.drop(1).dropRight(1) -> s))
          case (Some(m), (p, s)) if p == s            => Some(m)
          case _                                      => None
        }

final case class HttpSettings(threads: Int, maxQueuedRequests: Int, maxBodyBytes: Int)

/** The JDK HTTP server (ruling 4) with a bounded pool: `threads` workers and at most
  * `maxQueuedRequests` waiting. A request beyond that is answered 503 OVERLOADED by a one-thread
  * shedding pool.
  */
final class HttpService(
    val name: String,
    routes: Seq[Route],
    settings: HttpSettings,
    auth: ApiKeyAuth,
    log: JsonLog,
    metrics: Metrics,
    onStop: () => Unit = () => (),
    onStart: () => Unit = () => ()
):
  private val shedding = ThreadLocal.withInitial[Boolean](() => false)
  private def pool(n: Int, queue: Int, tag: String) =
    val count = AtomicInteger()
    val factory: ThreadFactory = r =>
      val t = Thread(r, s"$name-$tag-${count.incrementAndGet()}")
      t.setDaemon(true)
      t
    ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, LinkedBlockingQueue[Runnable](queue), factory)
  private val workers = pool(settings.threads, settings.maxQueuedRequests, "http")
  private val shed = pool(1, settings.maxQueuedRequests, "shed")
  private val executor: Executor = r =>
    try workers.execute(r)
    catch
      case _: RejectedExecutionException =>
        val s: Runnable = () =>
          shedding.set(true)
          try r.run()
          finally shedding.set(false)
        // shortcut: when the shedding queue is full too, the 503 is written on the dispatcher.
        try shed.execute(s)
        catch case _: RejectedExecutionException => s.run()
  private val alarms = ScheduledThreadPoolExecutor(
    1,
    r =>
      val t = Thread(r, s"$name-alarm")
      t.setDaemon(true)
      t
  )
  alarms.setRemoveOnCancelPolicy(true)

  /** Runs body; if it still runs after ms, interrupts this thread, which closes the connection a
    * blocked socket read waits on. The interrupt never outlives body.
    */
  private def interruptAfter(ms: Long)(body: => Unit): Unit =
    val self = Thread.currentThread()
    val lock = Object()
    var running = true
    var fired = false
    val alarm = alarms.schedule(
      (() => lock.synchronized { if running then { fired = true; self.interrupt() } }): Runnable,
      ms,
      TimeUnit.MILLISECONDS
    )
    try body
    finally
      val clear = lock.synchronized { running = false; fired }
      alarm.cancel(false)
      if clear then Thread.interrupted()

  @volatile private var server: Option[HttpServer] = None

  /** (method, path template) of every route, for the OpenAPI contract test. */
  def routeTable: Seq[(String, String)] = routes.map(r => r.method -> r.template)

  /** Binds host:port (0 picks a free port) and returns the bound port. */
  def start(host: String, port: Int): Int =
    HttpService.limitRequestReads()
    val s = HttpServer.create(InetSocketAddress(host, port), 0)
    s.createContext("/", ex => handle(ex))
    s.setExecutor(executor)
    s.start()
    server = Some(s)
    onStart()
    s.getAddress.getPort

  def stop(): Unit =
    server.foreach(_.stop(0))
    server = None
    workers.shutdownNow()
    shed.shutdownNow()
    alarms.shutdownNow()
    onStop()

  private def segments(rawPath: String): Vector[String] =
    rawPath.split('/').toVector.drop(1).map(s => URLDecoder.decode(s.replace("+", "%2B"), UTF_8))

  private def queryParams(raw: Option[String]): Map[String, Vector[String]] =
    raw.toVector
      .flatMap(_.split('&'))
      .filter(_.nonEmpty)
      .map { kv =>
        val (k, v) = kv.span(_ != '=')
        URLDecoder.decode(k, UTF_8) -> URLDecoder.decode(v.drop(1), UTF_8)
      }
      .groupMap(_._1)(_._2)

  private def readBody(ex: HttpExchange): Array[Byte] =
    val max = settings.maxBodyBytes
    val tooLarge =
      ApiError(ErrorCode.REQUEST_TOO_LARGE, s"the body is larger than $max bytes")
    if Option(ex.getRequestHeaders.getFirst("Content-Length"))
        .flatMap(_.toLongOption)
        .exists(_ > max)
    then throw tooLarge
    val bytes =
      try ex.getRequestBody.readNBytes(max + 1)
      catch
        case _: java.io.IOException => // closed by the read timeout, or by the client
          throw ApiError(ErrorCode.INVALID_FIELD, "the body was not received in full")
    if bytes.length > max then throw tooLarge
    bytes

  private def handle(ex: HttpExchange): Unit =
    val t0 = System.nanoTime()
    val h = ex.getRequestHeaders
    def header(n: String) = Option(h.getFirst(n))
    val requestId = Ids.orFresh(header("X-Request-Id"), "req-")
    val traceId = Ids.orFresh(header("X-Trace-Id"), "trace-")
    var template = "unmatched"
    var bodyRead = false // the route read the whole body
    val response =
      try
        if shedding.get then
          throw ApiError(ErrorCode.OVERLOADED, "the service is at capacity; retry later")
        val path =
          try segments(ex.getRequestURI.getRawPath)
          catch case NonFatal(_) => throw ApiError(ErrorCode.INVALID_FIELD, "malformed path")
        val method = ex.getRequestMethod
        val found = routes.view
          .filter(_.method == method)
          .flatMap(r => r.bind(path).map(r -> _))
          .headOption
        if !found.exists(_._1.public) && !auth.accepts(header("X-Api-Key")) then
          throw ApiError(ErrorCode.UNAUTHORIZED, "missing or invalid API key")
        found match
          case None =>
            throw ApiError(ErrorCode.ROUTE_NOT_FOUND, "no route for this method and path")
          case Some((route, params)) =>
            template = route.template
            val query =
              try queryParams(Option(ex.getRequestURI.getRawQuery))
              catch
                case NonFatal(_) =>
                  throw ApiError(ErrorCode.INVALID_FIELD, "malformed query string")
            def read() =
              val b = readBody(ex)
              bodyRead = true
              b
            route.handle(Request(method, params, query, header, () => read(), requestId, traceId))
      catch
        case e: ApiError => errorResponse(e, requestId, traceId)
        case NonFatal(_) =>
          errorResponse(
            ApiError(ErrorCode.INTERNAL_ERROR, "unexpected internal failure"),
            requestId,
            traceId
          )
    val unread = !bodyRead && (header("Transfer-Encoding").nonEmpty ||
      header("Content-Length").flatMap(_.toLongOption).exists(_ > 0))
    try
      val bytes = response.body.render().getBytes(UTF_8)
      val out = ex.getResponseHeaders
      out.set("Content-Type", "application/json; charset=utf-8")
      out.set("Cache-Control", "no-store")
      out.set("X-Content-Type-Options", "nosniff")
      out.set("X-Request-Id", requestId)
      out.set("X-Trace-Id", traceId)
      response.headers.foreach((k, v) => out.set(k, v))
      // This connection ends after a body left unread (Phase 11 D5); say so, or a client may send
      // its next request on it.
      if unread then out.set("Connection", "close")
      ex.sendResponseHeaders(response.status, bytes.length.toLong)
      ex.getResponseBody.write(bytes)
    catch case NonFatal(_) => () // the client went away; the request is still logged
    finally
      // The response is already on the wire. ex.close drains a body left unread (at most 64 KiB),
      // so a client still sending it gets no reset; one that never comes is cut after
      // UnreadBodyDrainMs, so it cannot hold this thread (Phase 11 D5).
      if unread then interruptAfter(HttpService.UnreadBodyDrainMs)(ex.close()) else ex.close()
    val ms = (System.nanoTime() - t0) / 1000000
    val code = response.body.objOpt.flatMap(_.get("error")).flatMap(_.strOpt)
    metrics.request(template, response.status, ms)
    log.request(name, traceId, requestId, ex.getRequestMethod, template, response.status, ms, code)

  private def errorResponse(e: ApiError, requestId: String, traceId: String): Response =
    val retryAfter = Option.when(e.code == ErrorCode.RATE_LIMITED)("Retry-After" -> "60")
    Response(
      e.code.httpStatus,
      upickle.default.writeJs(ErrorBody(e.code, e.message, traceId, requestId, e.code.retryable)),
      retryAfter.toMap
    )

object HttpService:
  /** Request read timeout (Phase 11 D5): a request whose line, headers, and body have not all
    * arrived within this many seconds of its first byte loses its connection. The JVM option
    * -Dsun.net.httpserver.maxReqTime=<seconds> overrides it (SERVICES.md section 6).
    */
  val RequestReadTimeoutSeconds = 30

  /** How long after its response a body left unread may still arrive (Phase 11 D5). */
  val UnreadBodyDrainMs = 1000L

  /** Sets the JDK server's request read timeout before the JVM creates its first server. */
  // shortcut: JVM-wide, read once when the first JDK server is created; every JDK server here is an
  // HttpService. Make it a services key (domain-engineer) if one JVM ever needs two values.
  def limitRequestReads(): Unit = synchronized {
    if System.getProperty("sun.net.httpserver.maxReqTime") == null then
      System.setProperty("sun.net.httpserver.maxReqTime", RequestReadTimeoutSeconds.toString)
  }
