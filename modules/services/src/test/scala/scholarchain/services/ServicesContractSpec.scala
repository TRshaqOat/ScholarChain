package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ErrorCode
import upickle.default.{Reader, Writer, read, writeJs}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** openapi.yaml against the code: every example parses against its model (responses round-trip
  * exactly, so no field is missing or extra), every example file is referenced, and the paths and
  * methods equal the route tables of the three services.
  */
class ServicesContractSpec extends AnyFunSuite:
  import ServiceFixtures.*

  private val root = Paths.get("modules/services")
  private val spec = Files.readString(root.resolve("openapi.yaml"))
  private val examples = root.resolve("openapi/examples")

  private def roundTrip[A: Reader: Writer](v: ujson.Value): Unit =
    assert(writeJs(read[A](v)) == v)

  test("every example is referenced, exists, and parses against its model") {
    val refs = "externalValue: (\\S+)".r.findAllMatchIn(spec).map(_.group(1)).toSet
    val files =
      Files.list(examples).iterator.asScala.map(p => s"openapi/examples/${p.getFileName}").toSet
    assert(refs == files)
    withWorld() { w =>
      files.toVector.sorted.foreach { f =>
        val name = f.stripPrefix("openapi/examples/").stripSuffix(".json")
        val v = ujson.read(Files.readString(root.resolve(f)))
        withClue(name) {
          name match
            case s"query-request$_" =>
              val (body, _) = QueryValidation.parse(v, w.cfg)
              assert(body.question.nonEmpty)
            case "query-response-sync" | "job-result-response" => roundTrip[SyncQueryResponse](v)
            case "query-response-async"                        => roundTrip[AsyncQueryResponse](v)
            case s"job-response$_"                             => roundTrip[JobResponse](v)
            case "explain-response"                            => roundTrip[ExplainResponse](v)
            case "evidence-response"                           => roundTrip[EvidenceResponse](v)
            case "node-response"                               => roundTrip[NodeResponse](v)
            case "neighbors-response"                          => roundTrip[NeighborsResponse](v)
            case s"status-$_"                                  => roundTrip[Status](v)
            case "metrics-response"                            =>
              assert(v.obj.keySet == Set("requests", "latencyMs", "counters"))
            case s"error-$code" =>
              roundTrip[ErrorBody](v)
              val e = read[ErrorBody](v)
              assert(e.error == ErrorCode.valueOf(code.toUpperCase.replace('-', '_')))
              assert(e.retryable == e.error.retryable)
            case other => fail(s"no model for example $other")
        }
      }
    }
  }

  test(
    "the documented paths and methods are exactly the routes of query-api, graph-evidence-api, and worker"
  ) {
    val documented = Vector.newBuilder[(String, String)]
    var path = ""
    spec.linesIterator.dropWhile(_ != "paths:").takeWhile(_ != "components:").foreach {
      case s"  /$p:"                                            => path = s"/$p"
      case s"    $m:" if Set("get", "post", "put", "delete")(m) =>
        documented += m.toUpperCase -> path
      case _ => ()
    }
    withWorld() { w =>
      val services = Seq(
        Apps.queryApi(w.cfg, w.backend, w.auth, log),
        Apps.graphEvidenceApi(w.cfg, w.backend, w.auth, log),
        Apps.worker(w.cfg, w.backend, w.auth, log)
      )
      try assert(documented.result().toSet == services.flatMap(_.routeTable).toSet)
      finally services.foreach(_.stop())
    }
  }
