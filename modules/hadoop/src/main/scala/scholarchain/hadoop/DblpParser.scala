package scholarchain.hadoop

import scholarchain.core.*
import scholarchain.entityresolution.Normalize

import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.util.Try
import scala.util.matching.Regex

/** DBLP character entities, loaded from the release DTD (`<!ENTITY name "&#NNN;">`). */
object DblpEntities:
  private val Decl = """<!ENTITY\s+([A-Za-z][A-Za-z0-9]*)\s+"([^"]*)"\s*>""".r
  private val Ref = "&([A-Za-z][A-Za-z0-9]*);".r
  private val Builtin = Set("amp", "lt", "gt", "quot", "apos")

  /** General (non-parameter) entities declared in a DTD. */
  def parseDtd(dtd: String): Map[String, String] =
    Decl.findAllMatchIn(dtd).map(m => m.group(1) -> m.group(2)).toMap

  /** Replaces each DTD entity reference by its declared replacement text (a numeric character
    * reference in the DBLP DTD), so a parser without DTD support reads the record. Left names the
    * first undeclared entity.
    */
  def resolve(xml: String, entities: Map[String, String]): Either[String, String] =
    Ref
      .findAllMatchIn(xml)
      .map(_.group(1))
      .find(n => !Builtin(n) && !entities.contains(n))
      .toLeft(
        Ref.replaceAllIn(
          xml,
          m => Regex.quoteReplacement(entities.getOrElse(m.group(1), m.matched))
        )
      )

/** A child element of a DBLP record: name, attributes, and its full text (inline markup such as
  * `<i>` or `<sub>` contributes its text; the tags are dropped).
  */
final case class DblpField(name: String, attrs: Map[String, String], text: String)

/** One parsed DBLP record element. */
final case class DblpRaw(recordType: String, attrs: Map[String, String], fields: Vector[DblpField]):
  def first(name: String): Option[String] = fields.find(_.name == name).map(_.text.trim)
  def all(name: String): Vector[DblpField] = fields.filter(_.name == name)

/** A DBLP record mapped to core contracts, plus per-record observations for counters. */
final case class DblpParsed(
    work: Keyed,
    venue: Option[Keyed],
    invalidOrcids: Int,
    repeatedAuthorNames: Int
)

/** Pure DBLP record parser. Holds only immutable state (the entity map and a configured factory).
  */
final class DblpParser(entities: Map[String, String]):
  private val factory =
    val f = XMLInputFactory.newDefaultFactory()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
    f.setProperty(XMLInputFactory.IS_COALESCING, true)
    f

  /** The record types this pipeline maps to WorkRecord (datasets/config/subset.v1.json). */
  val SupportedTypes = Set("article", "inproceedings")

  def parseXml(xml: String): Either[Reject, DblpRaw] =
    DblpEntities.resolve(xml, entities) match
      case Left(name) =>
        Left(
          Reject(
            "unknown-entity",
            s"entity &$name; is not declared in the DTD",
            None,
            Some(Counters.SchemaFailure)
          )
        )
      case Right(resolved) =>
        Try(readElement(resolved)).toEither.left.map(e =>
          Reject.malformed(s"unparseable XML: ${e.getMessage}")
        )

  private def readElement(xml: String): DblpRaw =
    val r = factory.createXMLStreamReader(new StringReader(xml))
    try
      def attrs = (0 until r.getAttributeCount)
        .map(i => r.getAttributeLocalName(i) -> r.getAttributeValue(i))
        .toMap
      while r.next() != XMLStreamConstants.START_ELEMENT do ()
      val recordType = r.getLocalName
      val recordAttrs = attrs
      val fields = Vector.newBuilder[DblpField]
      var depth = 1
      var current: (String, Map[String, String]) = null
      val text = new StringBuilder
      while depth > 0 do
        r.next() match
          case XMLStreamConstants.START_ELEMENT =>
            if depth == 1 then
              current = (r.getLocalName, attrs); text.clear()
            depth += 1
          case XMLStreamConstants.END_ELEMENT =>
            depth -= 1
            if depth == 1 then fields += DblpField(current._1, current._2, text.toString)
          case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA if depth >= 2 =>
            text.append(r.getText)
          case _ => ()
      DblpRaw(recordType, recordAttrs, fields.result())
    finally r.close()

  /** Parses one record and maps it to a WorkRecord (with its AuthorMentions) and a Venue. */
  def parse(xml: String, ctx: RunContext): Either[Reject, DblpParsed] =
    parseXml(xml).flatMap(toWork(_, ctx))

  def toWork(raw: DblpRaw, ctx: RunContext): Either[Reject, DblpParsed] =
    for
      key <- raw.attrs
        .get("key")
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(
          Reject.missingKey(s"${raw.recordType} record has no key attribute")
        )
      _ <- Either.cond(
        SupportedTypes(raw.recordType),
        (),
        Reject("unsupported-record-type", s"record type ${raw.recordType}", Some(key))
      )
      year <- raw.first("year") match
        case None    => Right(None)
        case Some(y) => y.toIntOption.map(Some(_)).toRight(Reject.schema(s"year '$y'", Some(key)))
      parsed <- build(raw, key, year, ctx)
    yield parsed

  private def build(raw: DblpRaw, key: String, year: Option[Int], ctx: RunContext) =
    val workId = StableIds.dblpWorkId(key)
    val prov = ctx.provenance(SourceType.DBLP, key)
    val ees = raw.all("ee")
    val doi = ees.map(_.text.trim).find(isDoiUrl).map(StableIds.normalizeDoi)
    val arxiv = ees.map(_.text.trim).collectFirst {
      case e if e.startsWith(ArxivPrefix) => e.stripPrefix(ArxivPrefix)
    }
    val venueName = raw.first("booktitle").orElse(raw.first("journal"))
    val venuePrefix = Option(key.split('/').init).filter(_.nonEmpty).map(_.mkString("/"))
    val venueId = venuePrefix.map(p => s"dblp-venue:$p")
    val authorFields = raw.all("author")
    val mentions = authorFields.zipWithIndex.map { (f, i) =>
      val name = f.text.trim
      AuthorMention(
        SchemaVersions.AuthorMention,
        StableIds.mentionId(workId, i + 1),
        workId,
        i + 1,
        name,
        Normalize.name(name).comparable,
        f.attrs.get("orcid").flatMap(StableIds.normalizeOrcid),
        None,
        Vector.empty,
        prov
      )
    }
    val externalIds =
      (Seq("venue" -> venueName) ++
        Seq("volume", "number", "pages", "url", "crossref").map(n => n -> raw.first(n)) ++
        Seq("mdate", "publtype").map(n => n -> raw.attrs.get(n)) ++
        ees.zipWithIndex.flatMap((e, i) =>
          Seq(s"ee.${i + 1}" -> Some(e.text.trim), s"ee.${i + 1}.type" -> e.attrs.get("type"))
        )).collect { case (k, Some(v)) => k -> v }.toMap
    val work = WorkRecord(
      SchemaVersions.WorkRecord,
      workId,
      SourceType.DBLP,
      key,
      raw.first("title"),
      raw.first("title").map(Normalize.title).filter(_.nonEmpty),
      year,
      venueId,
      Some(raw.recordType),
      doi,
      arxiv,
      externalIds,
      mentions,
      None,
      prov
    )
    val venue = for id <- venueId; p <- venuePrefix; name <- venueName
    yield Venue(
      SchemaVersions.Venue,
      id,
      name,
      Some(if p.startsWith("journals/") then "journal" else "conference"),
      Map.empty,
      ctx.provenance(SourceType.DBLP, p)
    )
    for
      w <- Keyed.of("works", workId, work)
      v <- venue.map(v => Keyed.of("venues", v.venueId, v).map(Some(_))).getOrElse(Right(None))
    yield DblpParsed(
      w,
      v,
      authorFields.count(_.attrs.contains("orcid")) - mentions.count(_.orcid.nonEmpty),
      mentions.size - mentions.map(_.rawName).distinct.size
    )

  private val ArxivPrefix = "https://arxiv.org/abs/"
  private val DoiPrefixes =
    Seq("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/")
  private def isDoiUrl(e: String): Boolean = DoiPrefixes.exists(e.toLowerCase.startsWith)
