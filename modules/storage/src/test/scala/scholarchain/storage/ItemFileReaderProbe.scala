package scholarchain.storage

import scholarchain.core.config.ConfigLoader

import java.lang.management.{ManagementFactory, MemoryType}
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** Heap probe for ruling 22 (not a test; run by hand, reads only): loads the full graph fake, then
  * opens ItemFileReader beside it, runs sample lookups, and prints heap in use after GC and the
  * peak: the used heap sampled every 2 ms, and the sum of each heap pool's own peak (an upper
  * bound, since the pools peak at different moments).
  *
  * sbt -batch -J-Xmx6g "storage/Test/runMain scholarchain.storage.ItemFileReaderProbe <graph dir>
  * <item dir> <objects root>"
  */
object ItemFileReaderProbe:
  private def mib(b: Long) = b >> 20
  private def inUse(): Long =
    System.gc()
    val r = Runtime.getRuntime
    mib(r.totalMemory - r.freeMemory)
  private def heapPools = ManagementFactory.getMemoryPoolMXBeans.asScala.filter(
    _.getType == MemoryType.HEAP
  )
  private def poolPeak(): Long = mib(heapPools.map(_.getPeakUsage.getUsed).sum)
  @volatile private var sampled = 0L
  private def peak() = s"peak sampled ${mib(sampled)} MiB, pool-peak sum ${poolPeak()} MiB"

  def main(args: Array[String]): Unit =
    val sampler = Thread(() =>
      val heap = ManagementFactory.getMemoryMXBean
      while true do
        sampled = math.max(sampled, heap.getHeapMemoryUsage.getUsed)
        Thread.sleep(2)
    )
    sampler.setDaemon(true)
    sampler.start()
    val Array(graphDir, itemDir, objectsRoot) = args.map(Paths.get(_))
    val cfg = ConfigLoader.load(sys.env).fold(e => sys.error(e.mkString("; ")), identity)
    val windows = cfg.graph.windows
    println(s"max heap ${mib(Runtime.getRuntime.maxMemory)} MiB; in use at start ${inUse()} MiB")
    heapPools.foreach(_.resetPeakUsage())
    val graph = InMemoryGraph()
    val t0 = System.nanoTime
    graph.loader.load(graphDir, windows, Some(FileObjectStore(objectsRoot).reader)) match
      case Left(vs) => sys.error(vs.take(5).mkString("\n"))
      case Right(_) => ()
    println(
      s"graph: ${graph.vertexCount} vertices, ${graph.edgeCount} edges in " +
        s"${(System.nanoTime - t0) / 1000000} ms; in use ${inUse()} MiB; ${peak()}"
    )
    val t1 = System.nanoTime
    val kv =
      ItemFileReader.open(itemDir, windows).fold(vs => sys.error(vs.mkString("\n")), identity)
    println(
      s"items: ${kv.itemCount} indexed (" +
        TableName.values.map(t => s"$t ${kv.itemCount(t)}").mkString(", ") +
        s") in ${(System.nanoTime - t1) / 1000000} ms; in use ${inUse()} MiB; ${peak()}"
    )
    val t2 = System.nanoTime
    val rankings = for
      m <- DynamoContract.RankingMetrics
      w <- windows
    yield kv.reader.get(
      TableName.ResearcherMetricCache,
      DynamoContract.RankingPrefix + m,
      s"TOP#${w.name}"
    )
    val top = rankings.flatten.headOption
      .flatMap(_.get("entries"))
      .collect { case AttributeValue.L(xs) => xs }
      .getOrElse(Vector.empty)
      .collect { case AttributeValue.M(m) => m("researcherId") }
      .collect { case AttributeValue.S(id) => id }
    val metricItems = top.map(id =>
      kv.reader.query(TableName.ResearcherMetricCache, id, "", DynamoContract.QueryMaxLimit).size
    )
    val names = Seq("Wei Wang", "Lei Zhang", "Jack J. Dongarra").map(n =>
      n -> kv.reader
        .query(TableName.EntityLinkCache, DynamoContract.nameKey(n).get, "", 1000)
        .size
    )
    println(
      s"lookups: ${rankings.count(_.nonEmpty)} of ${rankings.size} ranking items; " +
        s"${metricItems.sum} metric items for the ${top.size} researchers of the first ranking; " +
        s"name candidates ${names.map((n, k) => s"$n $k").mkString(", ")}; " +
        s"${(System.nanoTime - t2) / 1000000} ms"
    )
    println(
      s"final: in use ${inUse()} MiB; ${peak()}; max ${mib(Runtime.getRuntime.maxMemory)} MiB"
    )
