package scholarchain.hadoop

import scholarchain.core.{SourceType, StableIds}

import java.io.File
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.{Files, Path as JPath}
import java.util.zip.GZIPInputStream
import scala.io.Source
import scala.jdk.CollectionConverters.*

object TestSupport:
  val runId: String = StableIds.runId("test", "code", "config-v1", Seq("dblp=2026-10-03"), 1)
  val ctx: RunContext = RunContext(
    "test-job",
    runId,
    Map(
      SourceType.DBLP -> "2026-10-03",
      SourceType.OPENALEX -> "2026-11-03",
      SourceType.CROSSREF -> "2026-11-03",
      SourceType.ORCID -> "2026-11-03"
    ),
    "2026-10-03",
    "graph-v1"
  )

  /** The repository root (the directory holding datasets/samples). */
  lazy val repo: File =
    Iterator
      .iterate(new File(sys.props("user.dir")).getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => new File(d, "datasets/samples").isDirectory)
      .getOrElse(sys.error("datasets/samples not found above the working directory"))

  def sample(rel: String): File = new File(repo, s"datasets/samples/$rel")
  def readFile(f: File, cs: java.nio.charset.Charset = UTF_8): String =
    new String(Files.readAllBytes(f.toPath), cs)
  def lines(f: File): Vector[String] = readFile(f).linesIterator.filter(_.trim.nonEmpty).toVector

  lazy val dtdEntities: Map[String, String] =
    DblpEntities.parseDtd(readFile(sample("dblp/dblp-2023-06-28.dtd"), ISO_8859_1))
  lazy val parser = new DblpParser(dtdEntities)

  def tempDir(prefix: String): JPath = Files.createTempDirectory(prefix)

  /** Every JSON line of every gzip part file below dir (recursive). */
  def gzLines(dir: JPath): Vector[String] =
    if !Files.exists(dir) then Vector.empty
    else
      Files
        .walk(dir)
        .iterator
        .asScala
        .filter(p => p.getFileName.toString.startsWith("part-") && p.toString.endsWith(".gz"))
        .toVector
        .sortBy(_.toString)
        .flatMap { p =>
          val src = Source.fromInputStream(new GZIPInputStream(Files.newInputStream(p)), "UTF-8")
          try src.getLines().filter(_.nonEmpty).toVector
          finally src.close()
        }
