package scholarchain.tests.support

import scholarchain.core.config.TimeWindow
import scholarchain.storage.*

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** Phase 11 WO3: one raw HTTP/1.1 exchange over a loopback socket, for requests the JDK client
  * refuses to build (bad Content-Length, malformed escapes, chunked bodies). Requests must say
  * `Connection: close`. The socket timeout is a hang guard only: it fails a test whose server never
  * answers.
  */
object RawHttp:
  def bytes(port: Int, request: Array[Byte], timeoutMs: Int = 30000): (Int, String) =
    val s = Socket()
    try
      s.connect(InetSocketAddress("127.0.0.1", port), 10000)
      s.setSoTimeout(timeoutMs)
      val out = s.getOutputStream
      out.write(request)
      out.flush()
      val in = s.getInputStream
      val buf = ByteArrayOutputStream()
      val chunk = new Array[Byte](8192)
      var n = in.read(chunk)
      while n >= 0 do
        buf.write(chunk, 0, n)
        n = in.read(chunk)
      val text = new String(buf.toByteArray, UTF_8)
      val status = text.linesIterator.nextOption
        .flatMap(_.split(' ').lift(1))
        .flatMap(_.toIntOption)
        .getOrElse(-1)
      (status, text)
    finally s.close()

  def text(port: Int, request: String, timeoutMs: Int = 30000): (Int, String) =
    bytes(port, request.getBytes(ISO_8859_1), timeoutMs)

  /** The JSON body of a raw response, or Null when it has none. */
  def body(response: String): ujson.Value =
    val i = response.indexOf("\r\n\r\n")
    val b = if i < 0 then "" else response.substring(i + 4)
    if b.trim.isEmpty then ujson.Null else scala.util.Try(ujson.read(b)).getOrElse(ujson.Str(b))

/** A graph port whose vertex and expand reads run `onRead` first (block on a latch, or throw), then
  * answer from a fixed graph of one Researcher node with no edges. Templates graph-evidence-api
  * never calls fail loudly.
  */
final class ScriptedGraph(val limits: GraphReadLimits, onRead: String => Unit)
    extends GraphReadPort:
  val known = "orcid:0000-0002-1825-0097"
  private def node = GraphElement(
    known,
    "Researcher",
    "",
    "",
    Map[String, Prop]("runId" -> ("run-" + "a" * 32), "snapshot" -> "2026-10-03")
  )
  private def unused = throw UnsupportedOperationException("not used by this suite")
  protected def readVertex(id: String) =
    onRead(id); Option.when(id == known)(node)
  protected def readEdge(id: String) = None
  protected def readVertices(ids: Seq[String]) = unused
  protected def readCoauthorEdge(a: String, b: String, window: String) = unused
  protected def readCoauthors(researcherId: String, window: String, fetch: Int) = unused
  protected def readFrontier(ids: Seq[String], window: String, fetch: Int) = unused
  protected def readCommunityMembers(communityId: String, fetch: Int) = unused
  protected def readCommunityNeighbors(communityId: String, fetch: Int) = unused
  protected def readCrossCommunityEdges(researcherId: String, window: String, fetch: Int) = unused
  protected def readCommunityFacets(c: String, f: Facet, memberCap: Int, fetch: Int) = unused
  protected def readResearcherFacets(ids: Seq[String], w: TimeWindow, f: Facet, fetch: Int) =
    unused
  protected def readInstitutions(ids: Seq[String], fetch: Int) = unused
  protected def readTopicResearchers(ids: Seq[String], w: TimeWindow, fetch: Int) = unused
  protected def readLookup(label: LookupLabel, text: String, fetch: Int) = unused
  protected def readAuthoredCounts(ids: Seq[String]) = unused
  protected def readExpand(ids: Seq[String], d: Direction, types: Seq[String], fetch: Int) =
    ids.foreach(onRead); Vector.empty
