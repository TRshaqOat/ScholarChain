package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.util.Using

/** Frozen JSON wire contracts. A failure here means a serialized shape or a stable-ID formula
  * changed: bump the record's schema version, update wire/examples-v1.json, and list the affected
  * consumers. On mismatch the current encoding is written to target/wire-examples-actual.json for
  * review.
  */
class WireContractSpec extends AnyFunSuite:
  import Fixtures.*

  private lazy val frozen: ujson.Obj =
    val in = Option(getClass.getResourceAsStream("/wire/examples-v1.json"))
    ujson
      .read(in.fold("{}")(s => Using.resource(Source.fromInputStream(s, "UTF-8"))(_.mkString)))
      .obj

  private lazy val actual: ujson.Obj =
    ujson.Obj.from(all.map(e => e.name -> ujson.read(e.encode)))

  private def dumpActual(): Unit =
    val out = Paths.get("target", "wire-examples-actual.json")
    Files.createDirectories(out.getParent)
    Files.writeString(out, actual.render(indent = 2) + "\n")

  test("every record has a frozen wire example and no frozen example is orphaned") {
    if frozen.value.keySet != actual.value.keySet then dumpActual()
    assert(frozen.value.keySet == actual.value.keySet)
  }

  for e <- all do
    test(s"${e.name}: encoding matches the frozen wire example") {
      val ok = frozen.value.get(e.name).contains(actual(e.name))
      if !ok then dumpActual()
      assert(ok, s"${e.name} encodes as ${actual(e.name)}")
    }

    test(s"${e.name}: the frozen wire example decodes to the fixture") {
      assert(frozen.value.get(e.name).map(j => e.decode(j.render())) == Some(Right(e.value)))
    }

    test(s"${e.name}: round trip is lossless and the fixture is valid") {
      assert(e.decode(e.encode) == Right(e.value))
    }

    test(s"${e.name}: an unsupported schema version is rejected") {
      val json = ujson.read(e.encode)
      json("schemaVersion") = "unsupported-v0"
      val result = e.decode(json.render())
      assert(result.isLeft)
      assert(
        result.left.toOption.get.exists(_.contains("unsupported schema version 'unsupported-v0'"))
      )
    }

    if e.name != "Provenance" then
      test(s"${e.name}: a record without provenance is rejected") {
        val json = ujson.read(e.encode)
        json.obj.remove("provenance")
        assert(e.decode(json.render()).isLeft)
        json("provenance") = ujson.Null
        assert(e.decode(json.render()).left.toOption.get.exists(_.contains("missing provenance")))
      }

  test("every record's schema version is its current SchemaVersions constant") {
    val expected = Map(
      "Provenance" -> SchemaVersions.Provenance,
      "WorkRecord" -> SchemaVersions.WorkRecord,
      "AuthorMention" -> SchemaVersions.AuthorMention,
      "CanonicalResearcher" -> SchemaVersions.CanonicalResearcher,
      "AuthorshipRecord" -> SchemaVersions.AuthorshipRecord,
      "PublicationTeam" -> SchemaVersions.PublicationTeam,
      "CoauthorPairCandidate" -> SchemaVersions.CoauthorPairCandidate,
      "CoauthorEdge" -> SchemaVersions.CoauthorEdge,
      "ResearcherMetrics" -> SchemaVersions.ResearcherMetrics,
      "Venue" -> SchemaVersions.Venue,
      "Institution" -> SchemaVersions.Institution,
      "Topic" -> SchemaVersions.Topic,
      "MatchCandidate" -> SchemaVersions.MatchCandidate,
      "MatchDecision" -> SchemaVersions.MatchDecision,
      "GraphNode" -> SchemaVersions.GraphNode,
      "GraphEdge" -> SchemaVersions.GraphEdge,
      "GraphNode.Community" -> SchemaVersions.GraphNode,
      "GraphEdge.MEMBER_OF" -> SchemaVersions.GraphEdge,
      "EvidenceItem" -> SchemaVersions.EvidenceItem,
      "EvidenceBundle" -> SchemaVersions.EvidenceBundle,
      "QueryPlan" -> SchemaVersions.QueryPlan,
      "AnswerClaim" -> SchemaVersions.AnswerClaim,
      "AnswerResponse" -> SchemaVersions.AnswerResponse
    )
    assert(all.map(e => e.name -> e.schemaVersionOf).toMap == expected)
  }

  test("absent options are JSON null and enums are their case names") {
    val json = ujson.read(WireCodec.encode(work))
    assert(json("arxivId") == ujson.Null)
    assert(json("source") == ujson.Str("DBLP"))
    assert(ujson.read(WireCodec.encode(graphNode))("label") == ujson.Str("Researcher"))
  }

  test("an unknown enum value is rejected") {
    val json = ujson.read(WireCodec.encode(matchDecision))
    json("decision") = "MAYBE"
    assert(WireCodec.decode[MatchDecision](json.render()).isLeft)
  }

  test("evidence-v1 EvidenceType names are frozen; only TEXT_CHUNK and PROFILE are semantic") {
    // Appending a value keeps evidence-v1 (every stored record still decodes); renaming or removing
    // one is a schema-version bump.
    assert(
      EvidenceType.values.map(_.toString).toVector == Vector(
        "WORK_RECORD",
        "AUTHORSHIP",
        "COAUTHOR_EDGE",
        "GRAPH_PATH",
        "METRIC_RECORD",
        "COMPONENT_RECORD",
        "COMMUNITY_RECORD",
        "MATCH_DECISION",
        "TEXT_CHUNK",
        "PROFILE",
        "IDENTITY_RESOLUTION",
        "RANKING_RECORD",
        "TEMPORAL_CHANGE",
        "FACET_RECORD"
      )
    )
    assert(
      EvidenceType.values.filter(_.isSemantic).toSet ==
        Set(EvidenceType.TEXT_CHUNK, EvidenceType.PROFILE)
    )
  }

  test("the Phase 9 evidence types round-trip as evidence-v1 and are graph evidence only") {
    for t <- Seq(
        EvidenceType.IDENTITY_RESOLUTION,
        EvidenceType.RANKING_RECORD,
        EvidenceType.TEMPORAL_CHANGE,
        EvidenceType.FACET_RECORD
      )
    do
      val item = metricEvidence.copy(evidenceType = t)
      val json = WireCodec.encode(item)
      assert(ujson.read(json)("evidenceType") == ujson.Str(t.toString))
      assert(ujson.read(json)("schemaVersion") == ujson.Str("evidence-v1"))
      assert(WireCodec.decode[EvidenceItem](json) == Right(item))
      assert(Validation.validate(bundle.copy(graphEvidence = Vector(item))).isRight)
      assert(
        Validation
          .validate(bundle.copy(semanticEvidence = Vector(item)))
          .left
          .toOption
          .get
          .exists(_.contains("semanticEvidence must hold only semantic evidence"))
      )
  }

  test("malformed JSON is rejected, not thrown") {
    assert(WireCodec.decode[WorkRecord]("{not json").isLeft)
  }
