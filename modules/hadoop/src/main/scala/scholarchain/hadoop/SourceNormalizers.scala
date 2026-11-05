package scholarchain.hadoop

import scholarchain.core.*
import scholarchain.entityresolution.Normalize

import scala.util.Try

/** Pure normalizers, one per enrichment dataset. Each maps one JSON line to core records (as
  * [[Keyed]] shuffle records) or a [[Reject]]. Source IDs, author identifiers, affiliations,
  * topics, and work identifiers are kept; see JOBS.md "J02" for the field mapping.
  */
object SourceNormalizers:

  // ---- JSON access (ujson, part of the uPickle library) ----------------------------------------
  private def field(v: ujson.Value, k: String): Option[ujson.Value] =
    v.objOpt.flatMap(_.get(k)).filter(_ != ujson.Null)
  private def str(v: ujson.Value, k: String): Option[String] =
    field(v, k).map(_.str.trim).filter(_.nonEmpty)
  private def arr(v: ujson.Value, k: String): Vector[ujson.Value] =
    field(v, k).map(_.arr.toVector).getOrElse(Vector.empty)
  private def int(v: ujson.Value, k: String): Option[Int] = field(v, k).map(_.num.toInt)

  private def parse(line: String): Either[Reject, ujson.Value] =
    Try(ujson.read(line)).toEither.left
      .map(e => Reject.malformed(s"invalid JSON: ${e.getMessage.take(200)}"))
      .filterOrElse(_.objOpt.nonEmpty, Reject.malformed("JSON line is not an object"))

  /** Runs a mapping, turning a type error in the source JSON into a malformed-record reject. */
  private def guarded[A](id: Option[String])(f: => Either[Reject, A]): Either[Reject, A] =
    Try(f).toEither.left
      .map(e => Reject.malformed(s"unexpected field type: ${e.getMessage}", id))
      .flatten

  /** An affiliation known only by its name gets core's StableIds.affiliationNameId; a name with no
    * letters or digits rejects the record.
    */
  def unidentifiedInstitutionId(name: String, recordId: String): Either[Reject, String] =
    StableIds
      .affiliationNameId(name)
      .left
      .map(e => Reject("invalid-affiliation", e, Some(recordId), Some(Counters.SchemaFailure)))

  private def title(t: Option[String]) = t.map(Normalize.title).filter(_.nonEmpty)

  private def institution(
      id: String,
      name: String,
      country: Option[String],
      ext: Map[String, String],
      prov: Provenance
  ) =
    Keyed.of(
      "institutions",
      id,
      Institution(SchemaVersions.Institution, id, name, country, ext, prov)
    )

  private def mention(
      workId: String,
      pos: Int,
      name: String,
      orcid: Option[String],
      sourceAuthorId: Option[String],
      institutionIds: Vector[String],
      prov: Provenance
  ) = AuthorMention(
    SchemaVersions.AuthorMention,
    StableIds.mentionId(workId, pos),
    workId,
    pos,
    name,
    Normalize.name(name).comparable,
    orcid.flatMap(StableIds.normalizeOrcid),
    sourceAuthorId,
    institutionIds,
    prov
  )

  // ---- OpenAlex (datasets/schemas/openalex.md) -------------------------------------------------

  def openAlex(line: String, ctx: RunContext): Either[Reject, Vector[Keyed]] =
    parse(line).flatMap { j =>
      val idOpt = str(j, "id")
      guarded(idOpt) {
        idOpt.toRight(Reject.missingKey("OpenAlex work has no id")).flatMap(oaWork(j, _, ctx))
      }
    }

  private def oaWork(
      j: ujson.Value,
      rawId: String,
      ctx: RunContext
  ): Either[Reject, Vector[Keyed]] =
    val workId = StableIds.openAlexId(rawId)
    val recId = workId.stripPrefix("openalex:")
    val prov = ctx.provenance(SourceType.OPENALEX, recId)
    val source = field(j, "primary_location").flatMap(field(_, "source"))
    val venueId = source.flatMap(str(_, "id")).map(StableIds.openAlexId)
    val venue = for s <- source; id <- venueId; name <- str(s, "display_name")
    yield Keyed.of(
      "venues",
      id,
      Venue(
        SchemaVersions.Venue,
        id,
        name,
        str(s, "type"),
        str(s, "issn_l").map("issn_l" -> _).toMap,
        ctx.provenance(SourceType.OPENALEX, id.stripPrefix("openalex:"))
      )
    )
    val topic =
      field(j, "primary_topic").flatMap(t => str(t, "id").map(t -> _)).map { (t, rawTid) =>
        val tid = StableIds.openAlexId(rawTid)
        def named(k: String) = field(t, k).flatMap(v => v.strOpt.orElse(str(v, "display_name")))
        for
          topic <- Keyed.of(
            "topics",
            tid,
            Topic(
              SchemaVersions.Topic,
              tid,
              str(t, "display_name").getOrElse(tid),
              named("subfield"),
              named("field"),
              ctx.provenance(SourceType.OPENALEX, tid.stripPrefix("openalex:"))
            )
          )
          edge <- graphEdge(
            EdgeType.HAS_TOPIC,
            workId,
            tid,
            Map("source" -> "openalex.primary_topic"),
            Seq(workId),
            prov,
            ctx
          )
        yield Vector(topic, edge)
      }
    val authorships = arr(j, "authorships").zipWithIndex.map { (a, i) =>
      val author = field(a, "author")
      val insts = arr(a, "institutions").flatMap { inst =>
        val oaId = str(inst, "id").map(StableIds.openAlexId)
        val ror = str(inst, "ror").map(r => "ror:" + r.stripPrefix("https://ror.org/"))
        ror.orElse(oaId).map { id =>
          (
            id,
            institution(
              id,
              str(inst, "display_name").getOrElse(id),
              str(inst, "country_code"),
              (oaId.map("openalex" -> _) ++ str(inst, "type").map("type" -> _)).toMap,
              ctx.provenance(SourceType.OPENALEX, id)
            )
          )
        }
      }
      str(a, "raw_author_name").orElse(author.flatMap(str(_, "display_name"))) match
        case None       => Left(Reject.schema(s"authorship ${i + 1} has no name", Some(recId)))
        case Some(name) =>
          Right(
            (
              mention(
                workId,
                i + 1,
                name,
                author.flatMap(str(_, "orcid")),
                author.flatMap(str(_, "id")).map(StableIds.openAlexId),
                insts.map(_._1).distinct,
                prov
              ),
              insts.map(_._2)
            )
          )
    }
    for
      as <- authorships
        .collectFirst { case Left(r) => r }
        .toLeft(authorships.collect { case Right(x) => x })
      externalIds = field(j, "ids")
        .flatMap(_.objOpt)
        .map(_.collect {
          case (k, v) if k != "doi" && v.strOpt.nonEmpty => k -> v.str
        }.toMap)
        .getOrElse(Map.empty)
      work <- Keyed.of(
        "works",
        workId,
        WorkRecord(
          SchemaVersions.WorkRecord,
          workId,
          SourceType.OPENALEX,
          recId,
          str(j, "title"),
          title(str(j, "title")),
          int(j, "publication_year"),
          venueId,
          str(j, "type"),
          str(j, "doi").map(StableIds.normalizeDoi),
          None,
          externalIds ++ field(j, "primary_location")
            .flatMap(str(_, "raw_source_name"))
            .map("raw_source_name" -> _),
          as.map(_._1),
          None,
          prov
        )
      )
      rest <- Keyed.sequence(venue.toSeq ++ as.flatMap(_._2))
      topics <- topic.getOrElse(Right(Vector.empty))
    yield (work +: rest) ++ topics

  // ---- Crossref (datasets/schemas/crossref.md) -------------------------------------------------

  def crossref(line: String, ctx: RunContext): Either[Reject, Vector[Keyed]] =
    parse(line).flatMap { j =>
      val doiOpt = str(j, "DOI").map(StableIds.normalizeDoi)
      guarded(doiOpt)(
        doiOpt.toRight(Reject.missingKey("Crossref work has no DOI")).flatMap(crWork(j, _, ctx))
      )
    }

  private def crWork(j: ujson.Value, doi: String, ctx: RunContext): Either[Reject, Vector[Keyed]] =
    val workId = StableIds.doiWorkId(doi)
    val prov = ctx.provenance(SourceType.CROSSREF, doi)
    val issns = arr(j, "ISSN").flatMap(_.strOpt)
    val containerTitle = arr(j, "container-title").flatMap(_.strOpt).headOption
    val venueId = issns.headOption.map(i => s"issn:$i")
    val venue = for id <- venueId; name <- containerTitle
    yield Keyed.of(
      "venues",
      id,
      Venue(
        SchemaVersions.Venue,
        id,
        name,
        None,
        issns.zipWithIndex.map((v, i) => s"issn.${i + 1}" -> v).toMap,
        ctx.provenance(SourceType.CROSSREF, id.stripPrefix("issn:"))
      )
    )
    val year = field(j, "issued")
      .map(d => arr(d, "date-parts"))
      .flatMap(_.headOption)
      .flatMap(_.arr.headOption)
      .flatMap(v => Option.when(v != ujson.Null)(v.num.toInt))
    val authors = arr(j, "author").zipWithIndex.map { (a, i) =>
      val name = str(a, "name").orElse(
        Option(Seq(str(a, "given"), str(a, "family")).flatten.mkString(" ")).filter(_.nonEmpty)
      )
      val affs = arr(a, "affiliation").flatMap(str(_, "name")).distinct.map { n =>
        unidentifiedInstitutionId(n, doi).map(id =>
          (id, institution(id, n, None, Map.empty, ctx.provenance(SourceType.CROSSREF, doi)))
        )
      }
      for
        n <- name.toRight(Reject.schema(s"author ${i + 1} has no name", Some(doi)))
        as <- affs.collectFirst { case Left(r) => r }.toLeft(affs.collect { case Right(x) => x })
      yield (
        mention(workId, i + 1, n, str(a, "ORCID"), None, as.map(_._1).distinct, prov),
        as.map(_._2)
      )
    }
    for
      as <- authors
        .collectFirst { case Left(r) => r }
        .toLeft(authors.collect { case Right(x) => x })
      ext = (Seq("publisher", "member", "URL", "page", "volume", "issue").map(k =>
        k -> str(j, k)
      ) ++
        Seq("container-title" -> containerTitle) ++
        issns.zipWithIndex.map((v, i) => s"issn.${i + 1}" -> Some(v))).collect {
        case (k, Some(v)) => k -> v
      }.toMap
      work <- Keyed.of(
        "works",
        workId,
        WorkRecord(
          SchemaVersions.WorkRecord,
          workId,
          SourceType.CROSSREF,
          doi,
          arr(j, "title").flatMap(_.strOpt).headOption,
          title(arr(j, "title").flatMap(_.strOpt).headOption),
          year,
          venueId,
          str(j, "type"),
          Some(doi),
          None,
          ext,
          as.map(_._1),
          None,
          prov
        )
      )
      rest <- Keyed.sequence(venue.toSeq ++ as.flatMap(_._2))
    yield work +: rest

  // ---- ORCID projection (datasets/schemas/orcid.md) --------------------------------------------

  private def at(v: ujson.Value, path: String*): Option[ujson.Value] =
    path.foldLeft(Option(v))((o, k) => o.flatMap(field(_, k)))
  private def arrAt(v: ujson.Value, path: String*): Vector[ujson.Value] =
    at(v, path*).map(_.arr.toVector).getOrElse(Vector.empty)
  private def strAt(v: ujson.Value, path: String*): ujson.Value =
    at(v, path*).flatMap(_.strOpt).map(ujson.Str(_)).getOrElse(ujson.Null)

  /** Projects a full ORCID API v3.0 record (`fetch_orcid.sh` records.jsonl.gz) to the projection
    * fields of datasets/schemas/orcid.md, with the same paths as the `build_samples.sh` projection.
    * The iD is `orcid-identifier.path`, so records are keyed by the returned iD, not the requested
    * one.
    */
  def projectV3(j: ujson.Value): ujson.Value =
    val employments = arrAt(j, "activities-summary", "employments", "affiliation-group")
      .flatMap(arr(_, "summaries"))
      .flatMap(field(_, "employment-summary"))
      .map { e =>
        val org = field(e, "organization")
        ujson.Obj(
          "org" -> org.fold(ujson.Null)(strAt(_, "name")),
          "country" -> org.fold(ujson.Null)(strAt(_, "address", "country")),
          "org_id" -> org
            .flatMap(field(_, "disambiguated-organization"))
            .fold(ujson.Null)(d =>
              ujson.Obj(
                "source" -> strAt(d, "disambiguation-source"),
                "id" -> strAt(d, "disambiguated-organization-identifier")
              )
            ),
          "department" -> strAt(e, "department-name"),
          "role" -> strAt(e, "role-title"),
          "start_year" -> strAt(e, "start-date", "year", "value"),
          "end_year" -> strAt(e, "end-date", "year", "value")
        )
      }
    val dois = arrAt(j, "activities-summary", "works", "group")
      .flatMap(g => arrAt(g, "external-ids", "external-id"))
      .filter(x => str(x, "external-id-type").contains("doi"))
      .flatMap(str(_, "external-id-value"))
      .map(_.toLowerCase)
      .distinct
      .sorted
    ujson.Obj(
      "orcid" -> strAt(j, "orcid-identifier", "path"),
      "name" -> at(j, "person", "name").fold(ujson.Null)(n =>
        ujson.Obj(
          "given" -> strAt(n, "given-names", "value"),
          "family" -> strAt(n, "family-name", "value"),
          "credit" -> strAt(n, "credit-name", "value")
        )
      ),
      "other_names" -> arrAt(j, "person", "other-names", "other-name")
        .flatMap(str(_, "content"))
        .map(ujson.Str(_)),
      "external_ids" -> arrAt(j, "person", "external-identifiers", "external-identifier").map(x =>
        ujson.Obj("type" -> strAt(x, "external-id-type"), "value" -> strAt(x, "external-id-value"))
      ),
      "employments" -> employments,
      "work_dois" -> dois.map(ujson.Str(_)),
      "last_modified_ms" -> at(j, "history", "last-modified-date", "value").getOrElse(ujson.Null)
    )

  /** An ORCID record becomes a Researcher GraphNode keyed by its iD, Institution records for
    * employments, and AFFILIATED_WITH edges. Accepts the projection (committed samples) or a full
    * v3.0 record (has `orcid-identifier`), which is projected first by [[projectV3]]. Records
    * without a valid iD (including `synthetic:` fixtures) are rejected as unsupported identifiers.
    */
  def orcid(line: String, ctx: RunContext): Either[Reject, Vector[Keyed]] =
    for
      parsed <- parse(line)
      v3 = parsed.obj.contains("orcid-identifier")
      j <- guarded(None)(Right(if v3 then projectV3(parsed) else parsed))
      out <- orcidProjection(j, v3, ctx)
    yield out

  private def orcidProjection(
      j: ujson.Value,
      v3: Boolean,
      ctx: RunContext
  ): Either[Reject, Vector[Keyed]] =
    val raw = str(j, "orcid")
    guarded(raw) {
      for
        r <- raw.toRight(
          Reject.missingKey(
            if v3 then "ORCID record has no orcid-identifier.path"
            else "ORCID record has no orcid"
          )
        )
        id <- StableIds
          .normalizeOrcid(r)
          .toRight(
            Reject("unsupported-identifier", s"'$r' is not a valid ORCID iD", Some(r))
          )
        out <- orcidRecord(j, id, ctx)
      yield out
    }

  private def orcidRecord(
      j: ujson.Value,
      id: String,
      ctx: RunContext
  ): Either[Reject, Vector[Keyed]] =
    val nodeId = s"orcid:$id"
    val prov = ctx.provenance(SourceType.ORCID, id)
    val name = field(j, "name")
    val props =
      (Seq(
        "givenName" -> name.flatMap(str(_, "given")),
        "familyName" -> name.flatMap(str(_, "family")),
        "creditName" -> name.flatMap(str(_, "credit")),
        "lastModifiedMs" -> field(j, "last_modified_ms").map(_.num.toLong.toString)
      ) ++
        arr(j, "other_names")
          .flatMap(_.strOpt)
          .zipWithIndex
          .map((n, i) => s"otherName.${i + 1}" -> Some(n)) ++
        arr(j, "external_ids").zipWithIndex.flatMap((e, i) =>
          Seq(
            s"externalId.${i + 1}.type" -> str(e, "type"),
            s"externalId.${i + 1}.value" -> str(e, "value")
          )
        ) ++
        arr(j, "work_dois")
          .flatMap(_.strOpt)
          .map(StableIds.normalizeDoi)
          .distinct
          .zipWithIndex
          .map((d, i) => s"workDoi.${i + 1}" -> Some(d))).collect { case (k, Some(v)) =>
        k -> v
      }.toMap
    val node = Keyed.of(
      "researcher-profiles",
      nodeId,
      GraphNode(SchemaVersions.GraphNode, nodeId, NodeLabel.Researcher, props, prov)
    )
    val employments = arr(j, "employments").flatMap { e =>
      str(e, "org")
        .map { org =>
          val instId = field(e, "org_id")
            .flatMap(o => for s <- str(o, "source"); v <- str(o, "id") yield s"${s.toLowerCase}:$v")
            .map(Right(_))
            .getOrElse(unidentifiedInstitutionId(org, id))
          val eprops = Seq("department", "role", "start_year", "end_year", "country")
            .flatMap(k => str(e, k).map(k -> _))
            .toMap
          Seq(
            instId.flatMap(
              institution(
                _,
                org,
                str(e, "country"),
                Map.empty,
                ctx.provenance(SourceType.ORCID, id)
              )
            ),
            instId.flatMap(
              graphEdge(
                EdgeType.AFFILIATED_WITH,
                nodeId,
                _,
                eprops,
                eprops.toSeq.sorted.map((k, v) => s"$k=$v"),
                prov,
                ctx
              )
            )
          )
        }
        .getOrElse(Nil)
    }
    Keyed.sequence(node +: employments)

  private def graphEdge(
      t: EdgeType,
      from: String,
      to: String,
      props: Map[String, String],
      evidence: Seq[String],
      prov: Provenance,
      ctx: RunContext
  ): Either[Reject, Keyed] =
    StableIds
      .graphEdgeId(t, from, to, ctx.graphPolicyVersion, evidence)
      .left
      .map(e => Reject.schema(e, Some(from)))
      .flatMap(id =>
        Keyed.of(
          "edges",
          id,
          GraphEdge(
            SchemaVersions.GraphEdge,
            id,
            t,
            from,
            to,
            props,
            prov.copy(policyVersion = Some(ctx.graphPolicyVersion))
          )
        )
      )
