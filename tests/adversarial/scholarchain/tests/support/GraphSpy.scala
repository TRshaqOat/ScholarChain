package scholarchain.tests.support

import scholarchain.storage.*
import scholarchain.storage.live.{NeptuneGraphReader, OpenCypherTemplates}
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse
}

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Phase 11 work order 4. A graph port that forwards every protected read to `inner` and records
  * the method name and arguments, so a test can see which values the runtime actually sent to the
  * graph store, and replay exactly those calls through the live Neptune reader.
  *
  * Scala `protected` members are public in bytecode; reflection reaches them on any port.
  */
final class GraphSpy(inner: GraphReadPort) extends GraphReadPort:
  val calls: ConcurrentLinkedQueue[(String, Vector[Any])] = ConcurrentLinkedQueue()
  def limits: GraphReadLimits = inner.limits
  def recorded: Vector[(String, Vector[Any])] = calls.asScala.toVector

  private def forward[A](name: String, args: Any*): A =
    calls.add(name -> args.toVector)
    GraphSpy.invoke(inner, name, args.toVector).asInstanceOf[A]

  protected def readVertex(id: String) = forward("readVertex", id)
  protected def readEdge(id: String) = forward("readEdge", id)
  protected def readVertices(ids: Seq[String]) = forward("readVertices", ids)
  protected def readCoauthorEdge(a: String, b: String, window: String) =
    forward("readCoauthorEdge", a, b, window)
  protected def readCoauthors(researcherId: String, window: String, fetch: Int) =
    forward("readCoauthors", researcherId, window, fetch)
  protected def readFrontier(ids: Seq[String], window: String, fetch: Int) =
    forward("readFrontier", ids, window, fetch)
  protected def readCommunityMembers(communityId: String, fetch: Int) =
    forward("readCommunityMembers", communityId, fetch)
  protected def readCommunityNeighbors(communityId: String, fetch: Int) =
    forward("readCommunityNeighbors", communityId, fetch)
  protected def readCrossCommunityEdges(researcherId: String, window: String, fetch: Int) =
    forward("readCrossCommunityEdges", researcherId, window, fetch)
  protected def readCommunityFacets(c: String, f: Facet, memberCap: Int, fetch: Int) =
    forward("readCommunityFacets", c, f, memberCap, fetch)
  protected def readResearcherFacets(
      ids: Seq[String],
      w: scholarchain.core.config.TimeWindow,
      f: Facet,
      fetch: Int
  ) = forward("readResearcherFacets", ids, w, f, fetch)
  protected def readInstitutions(ids: Seq[String], fetch: Int) =
    forward("readInstitutions", ids, fetch)
  protected def readTopicResearchers(
      ids: Seq[String],
      w: scholarchain.core.config.TimeWindow,
      fetch: Int
  ) = forward("readTopicResearchers", ids, w, fetch)
  protected def readLookup(label: LookupLabel, text: String, fetch: Int) =
    forward("readLookup", label, text, fetch)
  protected def readAuthoredCounts(ids: Seq[String]) = forward("readAuthoredCounts", ids)
  protected def readExpand(ids: Seq[String], d: Direction, types: Seq[String], fetch: Int) =
    forward("readExpand", ids, d, types, fetch)

object GraphSpy:
  private val methods: Map[String, Method] =
    classOf[GraphReadPort].getDeclaredMethods
      .filter(_.getName.startsWith("read"))
      .map { m =>
        m.setAccessible(true)
        m.getName -> m
      }
      .toMap

  /** Calls the protected read method `name` of `port` with `args` (virtual dispatch). */
  def invoke(port: GraphReadPort, name: String, args: Vector[Any]): Any =
    val m = methods.getOrElse(name, throw IllegalArgumentException(s"no read method $name"))
    try m.invoke(port, args.map(_.asInstanceOf[AnyRef])*)
    catch case e: InvocationTargetException => throw e.getCause

  /** A NeptunedataClient proxy that records every openCypher request and answers with no rows. No
    * network: every other client method fails loudly.
    */
  final class CapturingNeptune:
    val requests: ConcurrentLinkedQueue[ExecuteOpenCypherQueryRequest] = ConcurrentLinkedQueue()
    val client: NeptunedataClient =
      val h: InvocationHandler = (_: AnyRef, m: Method, args: Array[AnyRef]) =>
        m.getName match
          case "executeOpenCypherQuery" =>
            requests.add(args(0).asInstanceOf[ExecuteOpenCypherQueryRequest])
            ExecuteOpenCypherQueryResponse
              .builder()
              .results(Document.fromList(java.util.List.of()))
              .build()
          case "close"       => null
          case "serviceName" => "capturing-neptune"
          case "toString"    => "capturing-neptune"
          case "hashCode"    => Integer.valueOf(System.identityHashCode(this))
          case "equals"      => java.lang.Boolean.FALSE
          case n             => throw UnsupportedOperationException(n)
      Proxy
        .newProxyInstance(getClass.getClassLoader, Array(classOf[NeptunedataClient]), h)
        .asInstanceOf[NeptunedataClient]
    def all: Vector[ExecuteOpenCypherQueryRequest] = requests.asScala.toVector

  /** The template ID whose fixed text this request carries behind the timeout hint, if any. */
  def templateOf(r: ExecuteOpenCypherQueryRequest, timeoutMs: Int): Option[String] =
    OpenCypherTemplates.Templates.collectFirst {
      case (id, text) if r.openCypherQuery == s"USING QUERY:TIMEOUTMILLISECONDS $timeoutMs $text" =>
        id
    }

  /** Replays recorded port calls through a live NeptuneGraphReader over a capturing client. */
  def replayLive(
      recorded: Vector[(String, Vector[Any])],
      limits: GraphReadLimits
  ): Vector[ExecuteOpenCypherQueryRequest] =
    val neptune = CapturingNeptune()
    val live = NeptuneGraphReader(neptune.client, limits, RetryPolicy(1, 1, 1), _ => ())
    recorded.foreach((name, args) => invoke(live, name, args))
    neptune.all
