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

  private def year(y: String) = ujson.Obj("year" -> ujson.Obj("value" -> y))
  private def employment(org: String, ringgold: String, start: String) = ujson.Obj(
    "summaries" -> ujson.Arr(
      ujson.Obj(
        "employment-summary" -> ujson.Obj(
          "department-name" -> "Psychoceramics",
          "role-title" -> "Professor",
          "start-date" -> year(start),
          "end-date" -> ujson.Null,
          "organization" -> ujson.Obj(
            "name" -> org,
            "address" -> ujson.Obj("city" -> "x", "country" -> "US"),
            "disambiguated-organization" -> ujson.Obj(
              "disambiguated-organization-identifier" -> ringgold,
              "disambiguation-source" -> "RINGGOLD"
            )
          )
        )
      )
    )
  )
  private def doiGroup(dois: String*) = ujson.Obj(
    "external-ids" -> ujson.Obj(
      "external-id" -> ujson.Arr.from(
        dois.map(d => ujson.Obj("external-id-type" -> "doi", "external-id-value" -> d)) :+
          ujson.Obj("external-id-type" -> "eid", "external-id-value" -> "2-s2.0-1")
      )
    )
  )

  /** The committed fictional test record (datasets/samples/orcid) in the full ORCID API v3.0 record
    * shape that fetch_orcid.sh writes to records.jsonl.gz: the same field values, at the v3.0 paths
    * the projection reads, plus fields the projection drops (biography, keywords, educations).
    */
  val orcidV3FictionalRecord: String = ujson.write(
    ujson.Obj(
      "orcid-identifier" -> ujson.Obj(
        "uri" -> "https://orcid.org/0000-0002-1825-0097",
        "path" -> "0000-0002-1825-0097",
        "host" -> "orcid.org"
      ),
      "history" -> ujson.Obj(
        "last-modified-date" -> ujson.Obj("value" -> ujson.Num(1788275402435d))
      ),
      "person" -> ujson.Obj(
        "name" -> ujson.Obj(
          "given-names" -> ujson.Obj("value" -> "Josiah"),
          "family-name" -> ujson.Obj("value" -> "Carberry"),
          "credit-name" -> ujson.Null
        ),
        "other-names" -> ujson.Obj(
          "other-name" -> ujson.Arr.from(
            Seq("Josiah Stinkney Carberry", "J. Carberry", "J. S. Carberry")
              .map(n => ujson.Obj("content" -> n))
          )
        ),
        "biography" -> ujson.Obj("content" -> "dropped by the projection"),
        "keywords" -> ujson.Obj("keyword" -> ujson.Arr(ujson.Obj("content" -> "dropped"))),
        "external-identifiers" -> ujson.Obj(
          "external-identifier" -> ujson.Arr(
            ujson.Obj(
              "external-id-type" -> "Scopus Author ID",
              "external-id-value" -> "7007156898"
            )
          )
        )
      ),
      "activities-summary" -> ujson.Obj(
        "educations" -> ujson.Obj("affiliation-group" -> ujson.Arr(employment("x", "1", "1"))),
        "employments" -> ujson.Obj(
          "affiliation-group" -> ujson.Arr(
            employment("Wesleyan University", "5468", "1930"),
            employment("Brown University", "6752", "1929")
          )
        ),
        "works" -> ujson.Obj(
          "group" -> ujson.Arr(
            doiGroup("10.5555/12345678", "10.1109/TPS.1987.4316723"),
            doiGroup("10.5555/12345679", "10.5555/12345680"),
            doiGroup("10.5555/666655554444", "10.5555/987654321", "10.5555/12345678")
          )
        )
      )
    )
  )

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
