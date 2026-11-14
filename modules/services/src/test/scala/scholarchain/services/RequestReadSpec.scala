package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite

import java.net.{InetSocketAddress, Socket, SocketException, SocketTimeoutException}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

/** Phase 11 D5 over raw loopback sockets: a declared body that never arrives, or arrives too
  * slowly, cannot hold an HTTP thread, with or without the API key. A body a route refused or never
  * read may still arrive for HttpService.UnreadBodyDrainMs after the response, and every request
  * must arrive within HttpService.RequestReadTimeoutSeconds (SERVICES.md section 6).
  */
class RequestReadSpec extends AnyFunSuite:
  import ServiceFixtures.*

  private def open(port: Int, request: String): Socket =
    val s = Socket()
    s.connect(InetSocketAddress("127.0.0.1", port), 10000)
    s.getOutputStream.write(request.getBytes(ISO_8859_1))
    s.getOutputStream.flush()
    s

  /** What the server sent before it closed the connection; None when guardSeconds passed first. The
    * guard is a hang detector: a healthy server closes long before it.
    */
  private def untilClosed(s: Socket, guardSeconds: Int): Option[String] =
    try
      s.setSoTimeout(guardSeconds * 1000)
      Some(String(s.getInputStream.readAllBytes(), UTF_8))
    catch
      case _: SocketTimeoutException => None
      case _: SocketException        => Some("") // a reset also closes the connection
    finally s.close()

  private def head(method: String, path: String, length: Long, key: Boolean) =
    s"$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
      (if key then s"X-Api-Key: $ApiKey\r\n" else "") +
      s"Content-Type: application/json\r\nContent-Length: $length\r\nConnection: close\r\n\r\n"

  private def status(raw: String): Int =
    raw.linesIterator.nextOption.flatMap(_.split(' ').lift(1)).flatMap(_.toIntOption).getOrElse(-1)

  private def error(raw: String): Option[String] =
    val body = raw.indexOf("\r\n\r\n") match
      case -1 => ""
      case i  => raw.substring(i + 4)
    Option.when(body.nonEmpty)(ujson.read(body)).flatMap(_.obj.get("error")).flatMap(_.strOpt)

  test("D5: a refused or unread body that never arrives does not hold the exchange or the thread") {
    // One HTTP thread: an exchange left open would also hold every later case.
    withWorld("services.query-api.threads = 1") { w =>
      val svc = Apps.queryApi(w.cfg, w.backend, w.auth, log)
      val port = svc.start("127.0.0.1", 0)
      try
        Vector(
          (
            "over the cap, with the key",
            head("POST", "/v1/query", 999999999L, key = true) + queryJson(),
            400,
            Some("REQUEST_TOO_LARGE")
          ),
          (
            "over the cap, no key",
            head("POST", "/v1/query", 999999999L, key = false) + queryJson(),
            401,
            Some("UNAUTHORIZED")
          ),
          ("unread body on /healthz, no key", head("GET", "/healthz", 100L, key = false), 200, None)
        ).foreach { (name, request, expected, code) =>
          // A healthy exchange ends UnreadBodyDrainMs (1 s) after its response. The 5 s guard is far
          // below the 30 s read timeout, the only other end of a body that never comes.
          val raw = untilClosed(open(port, request), 5)
          assert(raw.exists(r => status(r) == expected && error(r) == code), s"$name: $raw")
        }
      finally svc.stop()
    }
  }

  test("D5: a body or header slower than the read timeout loses its connection; threads freed") {
    withWorld("services.query-api.threads = 2") { w =>
      val lines = LinkedBlockingQueue[String]()
      val svc = Apps.queryApi(w.cfg, w.backend, w.auth, JsonLog(l => lines.add(l)))
      val port = svc.start("127.0.0.1", 0)
      try
        // With the key and within the cap, so the route reads the body: 9 of 100 bytes come.
        val slowBody = open(
          port,
          head("POST", "/v1/query", 100L, key = true)
            .replace("Host:", "X-Request-Id: d5-slow-body\r\nHost:") + "{\"questio"
        )
        // No key, and the headers never end.
        val slowHead = open(port, "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        val guard = HttpService.RequestReadTimeoutSeconds * 3
        assert(untilClosed(slowBody, guard).isDefined, "the slow body kept its connection")
        assert(untilClosed(slowHead, guard).isDefined, "the slow header kept its connection")
        // The cut read is the client's failure: 400 INVALID_FIELD in the log, never a 500.
        val logged = Iterator
          .continually(lines.poll(guard, TimeUnit.SECONDS))
          .takeWhile(_ != null)
          .map(ujson.read(_))
          .find(_.obj.get("requestId").flatMap(_.strOpt).contains("d5-slow-body"))
        assert(
          logged.exists(l => l("status").num == 400 && l("error").strOpt.contains("INVALID_FIELD")),
          logged
        )
        assert(Client(port).get("/healthz", Seq()).status == 200)
      finally svc.stop()
    }
  }

  test("D5: a response to a body left unread says Connection: close, so no client reuses it") {
    withWorld() { w =>
      val svc = Apps.queryApi(w.cfg, w.backend, w.auth, log)
      val port = svc.start("127.0.0.1", 0)
      try
        // Keep-alive (no Connection header), the whole body sent at once, refused for the key.
        val body = queryJson()
        val request = "POST /v1/query HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
          s"Content-Type: application/json\r\nContent-Length: ${body.getBytes(UTF_8).length}\r\n" +
          s"\r\n$body"
        val raw = untilClosed(open(port, request), 5)
        val headers = raw.toSeq.flatMap(_.linesIterator.takeWhile(_.nonEmpty))
        assert(
          raw.exists(status(_) == 401) && headers.exists(_.equalsIgnoreCase("Connection: close")),
          raw
        )
      finally svc.stop()
    }
  }
