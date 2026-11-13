package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite

/** Stable-ID formulas. Expected literals were computed independently with Python hashlib over the
  * documented formula (SHA-256 over parts joined by U+001F, first 32 hex digits).
  */
class StableIdsSpec extends AnyFunSuite:
  import Fixtures.*
  import StableIds.*

  test("frozen formula values match an independent implementation") {
    assert(fixtureRunId == "run-78764f1e9c70e21f34ef30d82b71089b")
    assert(researcherA == "orcid:0000-0002-1825-0097")
    assert(researcherB == "scr:er-v1:7a47f03bb657358ae076e68ca43dab88")
    assert(edgeIdAB == "cae-adc27e574aecdb4fd1cb8915fa5a31fe")
    assert(graphEdgeIdAB == "ge-59d2e3ea0c2185ed5ae9f8280026bfed")
    assert(
      textHash(profileText) ==
        "sha256:8a34b13ec97c06b38d67d0bde45513216ff58e40d671c4d37109c9e9e2d1880d"
    )
    assert(profileChunkId == "chunk-45c7bab4d9adaa0d45c8d8321de4b12c")
    assert(metricEvidence.evidenceId == "ev-53344ff0d3e40fb09029d37bdf4fb9b8")
  }

  test("IDs are deterministic across calls") {
    assert(researcherId(None, "er-v1", "k") == researcherId(None, "er-v1", "k"))
    assert(runId("s", "c", "v", Seq("a", "b"), 1) == runId("s", "c", "v", Seq("b", "a", "a"), 1))
  }

  test("run IDs ignore input order but change with attempt, code, config, and inputs") {
    val base = runId("s", "c", "v", Seq("a"), 1)
    assert(runId("s", "c", "v", Seq("a"), 2) != base)
    assert(runId("s", "c2", "v", Seq("a"), 1) != base)
    assert(runId("s", "c", "v2", Seq("a"), 1) != base)
    assert(runId("s", "c", "v", Seq("a", "b"), 1) != base)
    assertThrows[IllegalArgumentException](runId("s", "c", "v", Seq("a"), 0))
  }

  test("work IDs are namespace plus authoritative source ID") {
    assert(dblpWorkId("conf/ics/LiaoHNZCT09") == "dblp:conf/ics/LiaoHNZCT09")
    assert(doiWorkId("https://doi.org/10.1145/1542275.1542359") == "doi:10.1145/1542275.1542359")
    assert(doiWorkId("DOI:10.1145/ABC") == "doi:10.1145/abc")
    assert(openAlexId("https://openalex.org/W2071130955") == "openalex:W2071130955")
    assert(mentionId(workId, 3) == "dblp:conf/ics/LiaoHNZCT09#3")
  }

  test("researcher ID uses a validated ORCID, else a versioned ScholarChain identity ID") {
    assert(researcherId(Some("https://orcid.org/0000-0002-1825-0097"), "er-v1", "x") == researcherA)
    assert(researcherId(None, "er-v2", "orcid-record:synthetic:orcid-a") != researcherB)
    assert(researcherId(None, "er-v1", "other-anchor") != researcherB)
  }

  test("ORCID checksum is enforced and synthetic fixture IDs are never ORCID iDs") {
    assert(normalizeOrcid("0000-0002-1825-0097").contains("0000-0002-1825-0097"))
    assert(normalizeOrcid("0000-0002-1825-0098").isEmpty) // wrong check digit
    assert(
      normalizeOrcid("0000-0002-1694-233X").contains("0000-0002-1694-233X")
    ) // X check digit (ORCID documentation example)
    assert(normalizeOrcid("synthetic:orcid-a").isEmpty)
    assertThrows[IllegalArgumentException](researcherId(Some("synthetic:orcid-a"), "er-v1", "x"))
  }

  test("coauthor edge ID is independent of argument order and depends on window and policy") {
    val ab = coauthorEdgeId(researcherA, researcherB, 2000, 2026, "graph-v1")
    assert(coauthorEdgeId(researcherB, researcherA, 2000, 2026, "graph-v1") == ab)
    assert(coauthorEdgeId(researcherA, researcherB, 2015, 2026, "graph-v1") != ab)
    assert(coauthorEdgeId(researcherA, researcherB, 2000, 2026, "graph-v2") != ab)
    assert(coauthorEdgeId(researcherA, researcherA, 2000, 2026, "graph-v1").isLeft)
  }

  test("graph edge ID canonicalizes undirected endpoints only, and evidence order is ignored") {
    val p = "graph-v1"
    val ev = Seq("e2", "e1")
    assert(
      graphEdgeId(EdgeType.COAUTHORED_WITH, researcherB, researcherA, p, ev) ==
        graphEdgeId(EdgeType.COAUTHORED_WITH, researcherA, researcherB, p, ev.reverse)
    )
    assert(
      graphEdgeId(EdgeType.AUTHORED, researcherA, workId, p, ev) !=
        graphEdgeId(EdgeType.AUTHORED, workId, researcherA, p, ev)
    )
    assert(
      graphEdgeId(EdgeType.AUTHORED, researcherA, workId, p, Seq("e1")) !=
        graphEdgeId(EdgeType.AUTHORED, researcherA, workId, p, Seq("e2"))
    )
  }

  test("community ID: frozen value (JOBS.md job 6 formula), shape, and window sensitivity") {
    val c = communityId("graph-v1", "all-years", 2000, 2026, researcherA)
    assert(researcherA == "orcid:0000-0002-1825-0097")
    assert(c == "comm-bf6d9db42012407674c1997bfc2b8749")
    assert(IdFormat.isCommunityId(c) && !IdFormat.isNamespacedId(c))
    assert(communityId("graph-v1", "2015-2026", 2015, 2026, researcherA) != c)
    assert(communityId("graph-v2", "all-years", 2000, 2026, researcherA) != c)
    assert(communityId("graph-v1", "all-years", 2000, 2026, researcherB) != c)
    assert(
      !IdFormat.isCommunityId("comm-" + "A" * 32) && !IdFormat.isCommunityId("comp-" + "a" * 32)
    )
  }

  test("chunk ID changes with any of its inputs") {
    val base = chunkId("e", "v1", "abstract", Some(1), "sha256:x")
    assert(chunkId("e", "v1", "abstract", Some(2), "sha256:x") != base)
    assert(chunkId("e", "v1", "abstract", None, "sha256:x") != base)
    assert(chunkId("e", "v1", "profile", Some(1), "sha256:x") != base)
    assert(chunkId("e", "v1", "abstract", Some(1), "sha256:y") != base)
  }

  test("affiliation-name ID: frozen value, canonical form, version, and empty names") {
    val name = "Universitat Politècnica de Catalunya, Dept. EE"
    assert(canonicalAffiliationName(name) == "universitat politecnica de catalunya dept ee")
    assert(affiliationNameId(name) == Right("sci:affn-v1:981e0a59669da94037cd74352e4b6112"))
    assert(
      affiliationNameId("  UNIVERSITAT politecnica de  Catalunya -- dept ee ") == affiliationNameId(
        name
      )
    )
    assert(affiliationNameId("清华大学") == Right("sci:affn-v1:ed625fa53e12cc4c68183d214dca8dcc"))
    assert(affiliationNameId("Universitat Politecnica de Valencia") != affiliationNameId(name))
    assert(affiliationNameId(" -- , ").isLeft)
    assert(affiliationNameId("a\u001fb") == affiliationNameId("a b")) // separator is not a letter
    val id = affiliationNameId(name).toOption.get
    assert(IdFormat.isAffiliationNameId(id) && IdFormat.isNamespacedId(id))
    assert(!IdFormat.isAffiliationNameId("affiliation-name:981e0a59669da94037cd74352e4b6112"))
  }

  test("ID parts containing the separator are refused") {
    assertThrows[IllegalArgumentException](hash("a\u001fb"))
  }

  test("canonical pair rule: smaller ID first, self-pairs refused") {
    assert(CanonicalPair("b", "a") == Right(("a", "b")))
    assert(CanonicalPair("a", "b") == Right(("a", "b")))
    assert(CanonicalPair("a", "a").isLeft)
    assert(CanonicalPair.isCanonical(researcherA, researcherB))
    assert(!CanonicalPair.isCanonical(researcherB, researcherA))
    // Lexicographic, not numeric: position 10 sorts before position 2.
    assert(CanonicalPair.isCanonical(mentionId(workId, 10), mentionId(workId, 2)))
  }

  test("query ID: frozen value (RUNTIME.md audit formula), shape, input sensitivity, and guards") {
    val q = StableIds.queryId(fixtureRunId, "sha256:question", "sha256:request", 1)
    assert(q == "qry-e605c59cdcfda782444ceae12233ca77")
    assert(IdFormat.isQueryId(q) && !IdFormat.isRunId(q) && !IdFormat.isEvidenceId(q))
    assert(StableIds.queryId(fixtureRunId, "sha256:question", "sha256:request", 2) != q)
    assert(StableIds.queryId(fixtureRunId, "sha256:other", "sha256:request", 1) != q)
    assert(StableIds.queryId(fixtureRunId, "sha256:question", "sha256:other", 1) != q)
    assert(
      StableIds.queryId(runId("s", "c", "v", Nil, 1), "sha256:question", "sha256:request", 1) != q
    )
    assertThrows[IllegalArgumentException](StableIds.queryId("run-x", "h", "r", 1))
    assertThrows[IllegalArgumentException](StableIds.queryId(fixtureRunId, " ", "r", 1))
    assertThrows[IllegalArgumentException](StableIds.queryId(fixtureRunId, "h", "r", 0))
    assert(!IdFormat.isQueryId("qry-" + "A" * 32) && !IdFormat.isQueryId("qry-123"))
  }

  test("Phase 10 job ID: frozen value, one job per query, shape, and guard") {
    val q = "qry-e605c59cdcfda782444ceae12233ca77"
    val j = StableIds.jobId(q)
    // Computed independently: sha256("job" U+001F q), first 32 hex digits.
    assert(j == "job-4adb9f615f3369c2e77c2dac2d758ec4")
    assert(StableIds.jobId(q) == j)
    assert(StableIds.jobId("qry-" + "0" * 32) != j)
    assert(IdFormat.isJobId(j) && !IdFormat.isQueryId(j) && !IdFormat.isJobId(q))
    assert(!IdFormat.isJobId("job-01J") && !IdFormat.isJobId("job-" + "A" * 32))
    assertThrows[IllegalArgumentException](StableIds.jobId("job-01J"))
  }

  test("Phase 10 node ID: every GraphNode ID shape is a node ID; other text is not") {
    for id <- Seq(researcherA, workId, mention1.mentionId, profileChunkId, "ror:03yrm5c26") do
      assert(IdFormat.isNodeId(id), id)
    assert(
      IdFormat.isNodeId(StableIds.communityId("graph-v1", "all-years", 2000, 2026, researcherA))
    )
    for bad <- Seq("", "Researcher", "a b:c", "qry-e605c59cdcfda782444ceae12233ca77", "../etc") do
      assert(!IdFormat.isNodeId(bad), bad)
  }

  test("ID format checks accept well-formed and reject malformed IDs") {
    assert(IdFormat.isWorkId(workId))
    assert(IdFormat.isWorkId("doi:10.1145/1542275.1542359"))
    assert(IdFormat.isWorkId("openalex:W2071130955"))
    assert(!IdFormat.isWorkId("conf/ics/LiaoHNZCT09")) // no namespace
    assert(!IdFormat.isWorkId("doi:10.1145/ABC")) // not normalized
    assert(!IdFormat.isWorkId("dblp:conf/ics/Liaoü09")) // not ASCII
    assert(IdFormat.isResearcherId(researcherA) && IdFormat.isResearcherId(researcherB))
    assert(!IdFormat.isResearcherId("orcid:0000-0002-1825-0098"))
    assert(!IdFormat.isResearcherId("openalex:A5065936105")) // a candidate, not an identity
    assert(!IdFormat.isResearcherId("synthetic:orcid-a"))
    assert(IdFormat.isMentionId(mention1.mentionId))
    assert(!IdFormat.isMentionId(workId + "#0"))
    assert(IdFormat.isCoauthorEdgeId(edgeIdAB) && !IdFormat.isCoauthorEdgeId("cae-123"))
    assert(IdFormat.isGraphEdgeId(graphEdgeIdAB) && !IdFormat.isGraphEdgeId(edgeIdAB))
    assert(IdFormat.isChunkId(profileChunkId))
    assert(
      IdFormat.isEvidenceId(metricEvidence.evidenceId) && !IdFormat.isEvidenceId("ev-metric-17")
    )
    assert(IdFormat.isRunId(fixtureRunId) && !IdFormat.isRunId("run-2026-11-03T00:00:00Z"))
  }
