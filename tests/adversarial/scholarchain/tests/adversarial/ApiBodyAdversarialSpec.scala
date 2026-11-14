package scholarchain.tests.adversarial

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.tests.support.RawHttp
import scholarchain.tests.support.StoreWorld.*

import java.io.ByteArrayInputStream
import java.net.http.HttpRequest.BodyPublishers
import java.nio.charset.StandardCharsets.UTF_8

/** Phase 11 WO3, spec category "API malformed bodies", and ruling 1 target "injection through every
  * text field" at the HTTP boundary. Every malformed input must give a typed 4xx error body, never
  * 500, never a hang, and never an echo of the input.
  */
class ApiBodyAdversarialSpec extends AnyFunSuite:

  private def clientError(r: Reply, what: String, codes: Set[String] = Set.empty): Unit =
    assert(r.status >= 400 && r.status < 500, s"$what: ${r.status} ${r.text.take(300)}")
    assert(
      r.body.objOpt.exists(_.contains("traceId")),
      s"$what: not the error model: ${r.text.take(200)}"
    )
    if codes.nonEmpty then assert(codes.contains(r.error), s"$what: ${r.error}")

  test("deeply nested JSON within the size cap is a 400, not a stack overflow or a hang") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        val depth = (w.cfg.services.maxBodyBytes - 100) / 2
        val arrays = s"""{"question": "q", "output": ${"[" * depth}${"]" * depth}}"""
        assert(arrays.length < w.cfg.services.maxBodyBytes)
        clientError(
          c.post("/v1/query", arrays),
          "nested arrays",
          Set("INVALID_FIELD", "MALFORMED_JSON")
        )
        val objects =
          s"""{"question": "q", "constraints": ${"""{"a":""" * (depth / 3)}1${"}" * (depth / 3)}}"""
        assert(objects.length < w.cfg.services.maxBodyBytes)
        clientError(
          c.post("/v1/query", objects),
          "nested objects",
          Set("INVALID_FIELD", "MALFORMED_JSON", "UNSUPPORTED_CONSTRAINT")
        )
        // The service is still healthy afterwards.
        assert(c.post("/v1/query", queryJson()).status == 200)
      }
    }
  }

  test("numbers outside the int range, fractions, strings, and booleans are 400 in int fields") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        Vector(
          "1e400",
          "-1e400",
          "2147483648",
          "-2147483649",
          "9007199254740993",
          "2000.5",
          "\"2000\"",
          "true",
          "[2000]",
          "{}"
        ).foreach { v =>
          clientError(
            c.post("/v1/query", queryJson(extra = s""", "constraints": {"yearFrom": $v}""")),
            s"yearFrom $v",
            Set("INVALID_FIELD")
          )
          clientError(
            c.post("/v1/query", queryJson(extra = s""", "retrieval": {"graphLimit": $v}""")),
            s"graphLimit $v",
            Set("INVALID_FIELD")
          )
        }
        // A whole number written as a decimal is still a whole number.
        assert(
          c.post("/v1/query", queryJson(extra = """, "constraints": {"yearFrom": 2001.0}"""))
            .status == 200
        )
      }
    }
  }

  test("question text: control characters, blanks, and length boundaries") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        val max = w.cfg.runtime.question.maxChars
        Vector(
          "",
          "   ",
          "\n\t",
          "Who\u0000is Ada?",
          "Who\u0007is Ada?",
          "Who\u001bis Ada?",
          "x" * (max + 1)
        )
          .foreach(q =>
            clientError(c.post("/v1/query", queryJson(q)), q.take(20), Set("INVALID_FIELD"))
          )
        // Exactly the maximum is accepted (answered, or abstained, but 200).
        assert(c.post("/v1/query", queryJson("Who is " + "a" * (max - 7))).status == 200)
      }
    }
  }

  test("a body that is not valid UTF-8 is MALFORMED_JSON, never answered as a different question") {
    // PENDING D15 (owner service-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // Work order 4 (code review 1 section 5): RFC 8259 section 8.1 requires UTF-8, and openapi.yaml
      // answers a body that is not valid JSON with 400 MALFORMED_JSON. A lenient decode replaces the
      // bytes with U+FFFD and answers a question the client never sent.
      withWorld() { w =>
        val log = CaptureLog()
        w.query(log.log) { c =>
          def body(bad: Array[Byte]) =
            "{\"question\": \"Have Ada Lovelace and Grace Hopper coauthored a paper".getBytes(
              UTF_8
            ) ++
              bad ++ "?\"}".getBytes(UTF_8)
          val answered = Vector(
            "truncated two-byte sequence" -> Array(0xc3.toByte),
            "bytes never valid in UTF-8" -> Array(0xff.toByte, 0xfe.toByte),
            "overlong encoding of '/'" -> Array(0xc0.toByte, 0xaf.toByte),
            "encoded surrogate" -> Array(0xed.toByte, 0xa0.toByte, 0x80.toByte)
          ).flatMap { (name, bad) =>
            val r = c.send("POST", "/v1/query", Some(BodyPublishers.ofByteArray(body(bad))))
            Option.when(!(r.status == 400 && r.error == "MALFORMED_JSON"))(
              s"$name: ${r.status} ${r.text.take(80)}"
            )
          }
          assert(answered.isEmpty, s"invalid UTF-8 not refused: ${answered.mkString("; ")}")
        }
      }
    }
  }

  test("a JSON-escaped lone surrogate in the question is a typed 400, never an answer") {
    // PENDING D16 (owner service-engineer): fails without a production fix; see tests/TESTS.md.
    pendingUntilFixed {
      // A lone surrogate has no UTF-8 form: hashing or logging the question silently changes it.
      withWorld() { w =>
        val log = CaptureLog()
        w.query(log.log) { c =>
          val accepted = Vector("\\ud800", "\\udfff", "\\udc00\\ud800").flatMap { esc =>
            val r = c.post(
              "/v1/query",
              s"""{"question": "Have Ada Lovelace and Grace Hopper coauthored a paper $esc?"}"""
            )
            Option.when(!(r.status == 400 && Set("INVALID_FIELD", "MALFORMED_JSON")(r.error)))(
              s"$esc: ${r.status} ${r.text.take(80)}"
            )
          }
          assert(accepted.isEmpty, s"lone surrogates accepted: ${accepted.mkString("; ")}")
          // Control: a valid surrogate pair (an emoji) is a normal question.
          assert(
            c.post(
              "/v1/query",
              queryJson("Have Ada Lovelace and Grace Hopper coauthored a paper \uD83D\uDE00?")
            ).status == 200
          )
        }
      }
    }
  }

  /** Bodies that are not one JSON text (trailing data, unclosed, a byte-order mark). */
  private def malformed(b: String) =
    b.endsWith("trailing") || b == "{\"question\": \"q\"" || b.startsWith("\uFEFF")

  test("the body must be one JSON object with known fields; JSON nulls only where optional") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        Vector(
          "[]",
          "\"Who is Ada?\"",
          "42",
          "null",
          """{"question": null}""",
          """{"question": ["Who"]}""",
          """{"question": "q", "execution": null}""",
          """{"question": "q", "execution": "ASYNC"}""",
          """{"question": "q", "execution": "batch"}""",
          """{"question": "q", "output": null}""",
          """{"question": "q", "output": {"includeEvidence": "yes"}}""",
          """{"question": "q", "__proto__": {}}""",
          """{"question": "q", "constraints": []}""",
          """{"question": "q", "constraints": {"topics": [1, 2]}}""",
          """{"question": "q", "constraints": {"topics": [" "]}}""",
          """{"question": "q", "constraints": {"yearFrom": 2015, "yearTo": 2001}}""",
          """{"Question": "q"}""",
          "{\"question\": \"q\"} trailing",
          "{\"question\": \"q\"",
          "﻿{\"question\": \"q\"}"
        ).foreach { b =>
          // Work order 4: the exact code, so a body that parses as something else is caught.
          val code = if malformed(b) then "MALFORMED_JSON" else "INVALID_FIELD"
          clientError(c.post("/v1/query", b), b.take(40), Set(code))
        }
        // Unsupported structured requests are 422, not 400.
        Vector(
          """{"question": "q", "constraints": {"topics": ["a","b","c","d","e","f"]}}""",
          """{"question": "q", "analysis": {"rankingMetric": "WEIGHTED_DEGREE ' OR 1=1"}}""",
          """{"question": "q", "analysis": {"bogus": true}}""",
          """{"question": "q", "retrieval": {"maxPathDepth": 7}}"""
        ).foreach { b =>
          val r = c.post("/v1/query", b)
          assert(r.status == 422, s"$b: ${r.status} ${r.text}")
          assert(!r.text.contains("OR 1=1"), "the error echoes the request")
        }
      }
    }
  }

  test("Content-Type, Content-Length, and chunked transfer abuse give typed 4xx errors") {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        val body = queryJson()
        Vector(Seq(), Seq("Content-Type" -> "text/plain"), Seq("Content-Type" -> "application/xml"))
          .foreach { ct =>
            val r = c.send(
              "POST",
              "/v1/query",
              Some(BodyPublishers.ofString(body)),
              Seq("X-Api-Key" -> ApiKey) ++ ct
            )
            clientError(r, s"content type $ct", Set("INVALID_FIELD"))
          }
        // A chunked body (no Content-Length) larger than the cap: REQUEST_TOO_LARGE.
        val big = queryJson("Who is " + "a" * (w.cfg.services.maxBodyBytes + 10))
        val chunked =
          c.send(
            "POST",
            "/v1/query",
            Some(BodyPublishers.ofInputStream(() => ByteArrayInputStream(big.getBytes(UTF_8))))
          )
        clientError(chunked, "chunked oversize", Set("REQUEST_TOO_LARGE"))
        // A Content-Length shorter than the body: the server reads only that much.
        val short = s"POST /v1/query HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Api-Key: $ApiKey\r\n" +
          "Content-Type: application/json\r\nContent-Length: 5\r\nConnection: close\r\n\r\n" + body
        val (s1, r1) = RawHttp.text(c.port, short)
        assert(s1 == 400, r1)
        assert(c.get("/healthz", Seq()).status == 200)
      }
    }
  }

  test(
    "a declared body that never arrives does not hold the exchange open (refused, 401, or unread)"
  ) {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        // The client declares a body and sends little or none of it. The service must still
        // complete its response and end the exchange; it must not hold an HTTP thread waiting for
        // (or draining) a body it refused or never reads. The 5 s socket timeout is only a hang
        // guard: a healthy service answers in milliseconds. Each case is (request, status).
        def req(head: String, length: Long, sent: String) =
          s"$head HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
            s"Content-Length: $length\r\nConnection: close\r\n\r\n$sent"
        val cases = Vector(
          "over the cap, with the key" -> (
            req(s"POST /v1/query", 999999999L, queryJson())
              .replace("Host:", s"X-Api-Key: $ApiKey\r\nHost:"),
            400
          ),
          "over the cap, no key" -> (req("POST /v1/query", 999999999L, queryJson()), 401),
          "unread body on /healthz, no key" -> (req("GET /healthz", 100L, ""), 200)
        )
        val stuck = cases.flatMap { case (name, (request, expected)) =>
          scala.util.Try(RawHttp.text(c.port, request, timeoutMs = 5000)) match
            case scala.util.Success((s, _)) if s == expected => None
            case scala.util.Success((s, raw)) => Some(s"$name: status $s ${raw.take(120)}")
            case scala.util.Failure(e)        => Some(s"$name: ${e.getClass.getSimpleName}")
        }
        assert(stuck.isEmpty, s"exchanges not completed: ${stuck.mkString("; ")}")
      }
    }
  }

  /** Reads until the server closes the connection; None when the hang guard passes first. */
  private def untilClosed(port: Int, request: String, guardMs: Int): Option[String] =
    val s = java.net.Socket()
    try
      s.connect(java.net.InetSocketAddress("127.0.0.1", port), 10000)
      s.getOutputStream.write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
      s.getOutputStream.flush()
      s.setSoTimeout(guardMs)
      Some(String(s.getInputStream.readAllBytes(), UTF_8))
    catch
      case _: java.net.SocketTimeoutException => None
      case _: java.net.SocketException        => Some("")
    finally s.close()

  test(
    "repair-01 D5 on graph-evidence-api: an unread body that never comes frees the only thread; Connection: close"
  ) {
    // Work order 4: D5 was found and fixed on query-api; graph-evidence-api shares HttpService. With
    // one HTTP thread, an exchange still waiting for the body would hold every later request. The
    // 10 s guard is a hang detector only (the drain bound is HttpService.UnreadBodyDrainMs).
    withWorld("services.graph-evidence-api.threads = 1") { w =>
      val log = CaptureLog()
      w.graphEvidence(log.log) { c =>
        val node = java.net.URLEncoder.encode(ada, UTF_8)
        Vector(
          s"GET /v1/graph/nodes/$node HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Api-Key: $ApiKey\r\n" +
            "Content-Length: 100\r\n\r\n",
          s"GET /v1/evidence/ev-${"a" * 32} HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Api-Key: $ApiKey\r\n" +
            "Content-Length: 100\r\n\r\n",
          "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n\r\n",
          "GET /v1/graph/nodes/x HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n\r\n"
        ).foreach { req =>
          val raw = untilClosed(c.port, req, 10000)
          val what = req.linesIterator.next()
          assert(raw.isDefined, s"$what: the connection was held open")
          val head = raw.get.linesIterator.takeWhile(_.nonEmpty).toVector
          assert(head.headOption.exists(_.startsWith("HTTP/1.1 ")), s"$what: $head")
          assert(head.exists(_.equalsIgnoreCase("Connection: close")), s"$what: $head")
          // The single thread is free again: a normal request answers.
          assert(c.get("/healthz", Seq()).status == 200, what)
        }
      }
    }
  }

  test("repair-01 D5: starting any service sets the JDK request read timeout for this JVM") {
    // HttpService.limitRequestReads runs before the first JDK server; the tests JVM sets no
    // -Dsun.net.httpserver.maxReqTime, so the services' own value must be in force.
    withWorld() { w =>
      w.graphEvidence(CaptureLog().log)(c => assert(c.get("/healthz", Seq()).status == 200))
      assert(
        sys.props
          .get("sun.net.httpserver.maxReqTime")
          .contains(scholarchain.services.HttpService.RequestReadTimeoutSeconds.toString)
      )
      assert(scholarchain.services.HttpService.RequestReadTimeoutSeconds == 30)
    }
  }

  test(
    "Idempotency-Key and request-ID headers: bounded, visible ASCII, never echoed when invalid"
  ) {
    withWorld() { w =>
      val log = CaptureLog()
      w.query(log.log) { c =>
        Vector("a" * 256, "has space", "two  spaces")
          .foreach(k =>
            clientError(
              c.post("/v1/query", queryJson(), Seq("Idempotency-Key" -> k)),
              k.take(20),
              Set("INVALID_FIELD")
            )
          )
        assert(
          c.post("/v1/query", queryJson(), Seq("Idempotency-Key" -> ("k" * 255))).status == 200
        )
        // Invalid client IDs are replaced, so they never reach a header, a body, or a log line.
        val marker = "SYNTHETIC-REQID-<script>alert(1)</script>"
        val r = c.get("/healthz", Seq("X-Request-Id" -> marker, "X-Trace-Id" -> ("t" * 129)))
        assert(r.status == 200)
        assert(!r.headers.values.exists(v => v.contains("script") || v == "t" * 129))
        assert(
          r.headers("X-Request-Id").startsWith("req-") && r
            .headers("X-Trace-Id")
            .startsWith("trace-")
        )
      }
      assert(!log.all.exists(l => l.contains("SYNTHETIC-REQID") || l.contains("t" * 129)))
    }
  }
