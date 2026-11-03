package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite

/** One test per validation rule; each mutates one valid fixture into exactly one violation. */
class ValidationSpec extends AnyFunSuite:
  import Fixtures.*
  import Validation.validate

  private def rejected[A: Validator](a: A, fragment: String): Unit =
    val result = validate(a)
    assert(result.isLeft, s"expected rejection containing '$fragment'")
    assert(result.left.toOption.get.exists(_.contains(fragment)), result)

  test("every fixture is valid") {
    for e <- all do assert(e.decode(e.encode).isRight, e.name)
  }

  // ---- missing provenance -----------------------------------------------------------------------
  test("missing provenance is rejected") {
    rejected(work.copy(provenance = null), "missing provenance")
    rejected(coauthorEdge.copy(provenance = null), "missing provenance")
    rejected(claim.copy(provenance = null), "missing provenance")
  }

  test("incomplete provenance is rejected") {
    rejected(work.copy(provenance = work.provenance.copy(sourceRecordId = " ")), "sourceRecordId")
    rejected(work.copy(provenance = work.provenance.copy(snapshot = "")), "snapshot")
    rejected(
      work.copy(provenance = work.provenance.copy(runId = "run-2026-11-03")),
      "provenance.runId"
    )
    rejected(
      coauthorEdge.copy(provenance = coauthorEdge.provenance.copy(policyVersion = None)),
      "policyVersion"
    )
    rejected(
      matchDecision.copy(provenance = matchDecision.provenance.copy(policyVersion = None)),
      "policyVersion"
    )
  }

  // ---- confidence -------------------------------------------------------------------------------
  test("confidence outside [0, 1] is rejected") {
    rejected(matchDecision.copy(confidence = 1.01), "confidence must be within [0, 1]")
    rejected(matchDecision.copy(confidence = -0.01), "confidence must be within [0, 1]")
    rejected(matchDecision.copy(confidence = Double.NaN), "confidence must be within [0, 1]")
    rejected(researcher.copy(identityConfidence = 2.0), "identityConfidence")
    rejected(authorship.copy(matchConfidence = -1.0), "matchConfidence")
    rejected(metrics.copy(participationCoefficient = Some(1.5)), "participationCoefficient")
    assert(validate(matchDecision.copy(confidence = 0.0)).isRight)
    assert(validate(matchDecision.copy(confidence = 1.0)).isRight)
  }

  // ---- schema versions --------------------------------------------------------------------------
  test("unsupported schema versions are rejected, including a nested provenance version") {
    rejected(work.copy(schemaVersion = "work-record-v2"), "unsupported schema version")
    rejected(
      work.copy(provenance = work.provenance.copy(schemaVersion = "provenance-v0")),
      "provenance-v0"
    )
    rejected(
      work.copy(authors = Vector(mention1.copy(schemaVersion = "x"))),
      "unsupported schema version 'x'"
    )
  }

  // ---- malformed stable IDs ---------------------------------------------------------------------
  test("malformed stable IDs are rejected") {
    rejected(work.copy(workId = "conf/ics/LiaoHNZCT09"), "malformed workId")
    rejected(researcher.copy(researcherId = "openalex:A5065936105"), "malformed researcherId")
    rejected(mention1.copy(mentionId = workId + "#9"), "must equal workId#position")
    rejected(coauthorEdge.copy(edgeId = "cae-0"), "malformed edgeId")
    rejected(graphEdge.copy(edgeId = edgeIdAB), "malformed edgeId")
    rejected(metricEvidence.copy(evidenceId = "ev-metric-17"), "malformed evidenceId")
    rejected(claim.copy(evidenceIds = Vector("ev-1")), "malformed evidenceId")
    rejected(graphNode.copy(nodeId = workId), "malformed nodeId")
    rejected(work.copy(doi = Some("https://doi.org/10.1145/X")), "not normalized")
    rejected(mention1.copy(orcid = Some("synthetic:orcid-a")), "malformed orcid")
  }

  test("a coauthor edge ID that does not match its formula is rejected") {
    val other =
      StableIds.coauthorEdgeId(researcherA, researcherB, 2015, 2026, "graph-v1").toOption.get
    rejected(coauthorEdge.copy(edgeId = other), "does not match its stable-ID formula")
  }

  test("a synthetic ORCID fixture ID is accepted as a source record ID") {
    val p = prov(SourceType.ORCID, "synthetic:orcid-a")
    assert(
      validate(researcher.copy(researcherId = researcherB, orcid = None, provenance = p)).isRight
    )
  }

  // ---- pair ordering ----------------------------------------------------------------------------
  test("non-canonical and self pairs are rejected") {
    rejected(
      pairCandidate.copy(authorA = pairCandidate.authorB, authorB = pairCandidate.authorA),
      "not in canonical order"
    )
    rejected(pairCandidate.copy(authorB = pairCandidate.authorA), "not in canonical order")
    val swapped = coauthorEdge.copy(researcherA = researcherB, researcherB = researcherA)
    rejected(swapped, "not in canonical order")
    rejected(graphEdge.copy(fromId = researcherB, toId = researcherA), "not in canonical order")
    // Directed edges keep their direction.
    val authored = graphEdge.copy(
      edgeType = EdgeType.AUTHORED,
      fromId = researcherB,
      toId = workId,
      edgeId =
        StableIds.graphEdgeId(EdgeType.AUTHORED, researcherB, workId, "graph-v1", Nil).toOption.get
    )
    assert(validate(authored).isRight)
  }

  test("a pair candidate's mentions must belong to its work") {
    rejected(pairCandidate.copy(authorB = "dblp:conf/ics/Other09#2"), "malformed author mention ID")
  }

  // ---- time windows -----------------------------------------------------------------------------
  test("windows whose start year exceeds the end year are rejected") {
    rejected(metrics.copy(windowStartYear = 2027), "start year 2027 exceeds end year 2026")
    rejected(coauthorEdge.copy(windowStartYear = 2030), "start year 2030 exceeds end year 2026")
    rejected(
      queryPlan.copy(yearFrom = Some(2020), yearTo = Some(2010)),
      "start year 2020 exceeds end year 2010"
    )
    rejected(
      coauthorEdge.copy(firstCollaborationYear = 2010),
      "start year 2010 exceeds end year 2009"
    )
    assert(validate(metrics.copy(windowStartYear = 2026)).isRight) // single-year window
    assert(validate(queryPlan.copy(yearFrom = None)).isRight)
  }

  // ---- other contract rules ---------------------------------------------------------------------
  test("evidence bundles keep graph facts and semantic context apart") {
    rejected(
      bundle.copy(graphEvidence = Vector(profileEvidence)),
      "graphEvidence must not hold semantic"
    )
    rejected(
      bundle.copy(semanticEvidence = Vector(metricEvidence)),
      "semanticEvidence must hold only"
    )
  }

  test("supported claims must cite evidence; unsupported claims may not") {
    rejected(claim.copy(evidenceIds = Vector.empty), "cites no evidence")
    val unsupported =
      claim.copy(evidenceIds = Vector.empty, verificationStatus = VerificationStatus.UNSUPPORTED)
    assert(validate(unsupported).isRight)
  }

  test("match decisions need reason codes and an ISO-8601 timestamp") {
    rejected(matchDecision.copy(reasonCodes = Vector.empty), "reasonCodes")
    rejected(matchDecision.copy(decidedAt = "yesterday"), "ISO-8601")
  }

  test("a null field is rejected, not thrown") {
    assert(validate(pairCandidate.copy(authorA = null)).isLeft)
  }
