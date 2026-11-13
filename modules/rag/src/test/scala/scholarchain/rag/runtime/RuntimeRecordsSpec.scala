package scholarchain.rag.runtime

import org.scalatest.funsuite.AnyFunSuite
// Explicit imports: core also defines QueryPlan and EvidenceBundle, which a wildcard import would
// put ahead of the rag runtime records of the same names.
import scholarchain.core.{
  AnswerClaim,
  AnswerResponse,
  CollaborationMetric,
  EvidenceItem,
  EvidenceType,
  IdFormat,
  Provenance,
  QueryOperation,
  ResearcherMetrics,
  SchemaVersions,
  SourceType,
  StableIds,
  VerificationStatus
}
import scholarchain.storage.DynamoContract
import upickle.default.{ReadWriter, read, write}

class RuntimeRecordsSpec extends AnyFunSuite:
  private val run = StableIds.runId("rag-runtime", "src-test", "config-v1", Seq("x"), 1)
  private val queryId = "qry-" + StableIds.hash("query", "q1")
  private def prov(id: String, policy: Option[String] = None, model: Option[String] = None) =
    Provenance(SchemaVersions.Provenance, SourceType.RUNTIME, id, "2026-10-03", run, policy, model)
  private val ada = "scr:canon-v1.er-v2:" + StableIds.hash("r", "ada")
  private val grace = "scr:canon-v1.er-v2:" + StableIds.hash("r", "grace")
  private val comm = "comm-" + StableIds.hash("c", "1")

  private def roundTrip[T: ReadWriter](x: T): String =
    val json = write(x)
    assert(read[T](json) == x, json)
    json

  private val windowReq = WindowRequest(WindowKind.RANGE, None, Some(2015), None)
  private val selection =
    WindowSelection(windowReq, "2015-2026", 2015, 2026, exact = true, None)
  private val op = PlannedOperation(
    QueryOperation.RESEARCHER_RANKING,
    OperationScope.CORPUS,
    Vector.empty,
    Some(CollaborationMetric.DISTINCT_COAUTHOR_COUNT),
    Some(10)
  )
  private val plan = QueryPlan(
    RuntimeSchemas.QueryPlan,
    queryId,
    Vector(
      op,
      PlannedOperation(QueryOperation.SHORTEST_PATH, OperationScope.PAIR, Vector(0, 1), None, None)
    ),
    Vector(NameMention("Ada Lovelace", 12), NameMention("Grace Hopper", 29)),
    Vector(CollaborationMetric.PARTICIPATION_COEFFICIENT),
    selection.copy(exact = false, note = Some("2016-2020 uses the stored window 2015-2026")),
    Some(WindowComparison("2000-2014", "2015-2026")),
    Vector("distributed systems"),
    Vector.empty,
    Vector("Institute 0"),
    Vector("researcher-profile-v1"),
    PlanLimits(100, 30, 6, 5),
    PlannerSource.MODEL,
    Vector("constraints.yearFrom"),
    Vector("name not in question: Alan Turing"),
    Some("planner-v1"),
    Some("us.anthropic.claude-haiku-4-5-20251001-v1:0"),
    prov(queryId)
  )
  private val link = CoauthorLink(
    "cae-" + StableIds.hash("e", "1"),
    ada,
    grace,
    Some("Grace Hopper"),
    "all-years",
    23L,
    2007,
    2021,
    Vector.empty,
    Some("curated/coauthor-evidence/snapshot=2026-10-03/run=" + run + "/window=all-years/x.json"),
    prov("cae-1", Some("graph-v1"))
  )
  private val metrics = ResearcherMetrics(
    SchemaVersions.ResearcherMetrics,
    ada,
    2000,
    2026,
    3L,
    4L,
    5L,
    1.25,
    2L,
    Vector("dblp:conf/x/A21"),
    5.0,
    "comp-" + StableIds.hash("k", "1"),
    120L,
    Some(comm),
    Some(1L),
    Some(2L),
    Some(0.375),
    Some(2),
    prov(ada, Some("graph-v1"))
  )
  private val evidence = EvidenceItem(
    SchemaVersions.EvidenceItem,
    StableIds.evidenceId(EvidenceType.METRIC_RECORD, Seq(ada, "WINDOW#all-years"), run),
    EvidenceType.METRIC_RECORD,
    Vector(ada),
    None,
    "dynamodb:ResearcherMetricCache/" + ada + "/WINDOW#all-years",
    None,
    None,
    None,
    None,
    None,
    Map("distinctCoauthorCount" -> "4", "window" -> "all-years"),
    prov(ada, Some("graph-v1"))
  )
  private val semantic = evidence.copy(
    evidenceId = StableIds.evidenceId(EvidenceType.PROFILE, Seq("chunk-1"), run),
    evidenceType = EvidenceType.PROFILE,
    chunkId = Some("chunk-" + StableIds.hash("k", "c")),
    score = Some(0.912),
    text = Some("Researcher profile: Ada Lovelace."),
    textHash = Some(StableIds.textHash("Researcher profile: Ada Lovelace.")),
    facts = Map("index" -> "researcher-profile-v1")
  )
  private val candidate = NameCandidate(
    ada,
    "Ada Lovelace",
    "Ada Lovelace",
    40L,
    Some(0.95),
    0.99,
    41L,
    dblpAnchored = true,
    Some("0000-0002-1825-0097"),
    Vector(NamedCount("ror:01", "Institute 0", Some(3L))),
    Vector(NamedCount(grace, "Grace Hopper", Some(23L))),
    run,
    "2026-10-03"
  )
  private val resolution = ResearcherResolution(
    RuntimeSchemas.Resolution,
    "Ada Lovelace",
    DynamoContract.nameKey("Ada Lovelace"),
    "dominant-name-v1",
    0.9,
    5L,
    ResolutionDecision.RESOLVED_DOMINANT,
    Some(ada),
    Some(0.95),
    42L,
    2,
    Vector(
      candidate,
      candidate.copy(researcherId = grace, works = 2L, share = Some(0.05), orcid = None)
    ),
    Vector(candidate.copy(displayName = "Ada LOVELACE", share = None)),
    truncated = false,
    Some(evidence.evidenceId)
  )
  private val counts = VerificationCounts(3, 1, 1, 0)
  private val verification = VerificationReport(
    RuntimeSchemas.Verification,
    queryId,
    Vector(
      ClaimCheck(
        "claim-1",
        VerificationStatus.VERIFIED,
        Vector(CheckResult("number-in-evidence", CheckOutcome.PASS, "4 = distinctCoauthorCount")),
        ClaimAction.KEPT
      ),
      ClaimCheck(
        "claim-2",
        VerificationStatus.UNSUPPORTED,
        Vector(CheckResult("evidence-exists", CheckOutcome.MISSING, "ev-x not in bundle")),
        ClaimAction.REMOVED
      )
    ),
    counts
  )
  private val budget = BundleBudget(12000, 2100, 900, 0, 3)
  private val response = AnswerResponse(
    SchemaVersions.AnswerResponse,
    queryId,
    "Ada Lovelace has 4 distinct coauthors in all-years.",
    Vector(
      AnswerClaim(
        SchemaVersions.AnswerClaim,
        "claim-1",
        "Ada Lovelace has 4 distinct coauthors in 2000-2026.",
        Vector(evidence.evidenceId),
        VerificationStatus.VERIFIED,
        None,
        prov(queryId)
      )
    ),
    abstained = false,
    Some("Identity linked by dominance (share 0.95)."),
    prov(queryId)
  )

  test("request and planner output round-trip, with absent and present optional fields") {
    roundTrip(
      QueryRequest(
        RuntimeSchemas.QueryRequest,
        queryId,
        Some("sess-1"),
        "Which researchers have the most distinct coauthors since 2015?",
        Some(
          RequestConstraints(
            Some(2015),
            Some(2026),
            Some(Vector("distributed systems")),
            Some(Vector.empty),
            None
          )
        ),
        Some(
          RequestAnalysis(
            Some(CollaborationMetric.DISTINCT_COAUTHOR_COUNT),
            Some(true),
            Some(true),
            None,
            Some(false)
          )
        ),
        Some(RequestRetrieval(Some(100), Some(30), Some(6))),
        Some(RequestOutput(includeEvidence = true, includeScores = true, includeUncertainty = true))
      )
    )
    val bare = roundTrip(
      QueryRequest(RuntimeSchemas.QueryRequest, queryId, None, "q", None, None, None, None)
    )
    assert(ujson.read(bare)("constraints").isNull, "None is JSON null")
    roundTrip(
      PlannerOutput(
        RuntimeSchemas.PlannerOutput,
        plan.operations,
        Vector("Ada Lovelace", "Grace Hopper"),
        Vector(CollaborationMetric.CORENESS),
        WindowRequest(WindowKind.NAMED, Some("2000-2014"), None, None),
        None,
        Vector.empty,
        Vector("Venue0 Symposium"),
        Vector.empty
      )
    )
  }

  test("query plan round-trips and enums are their case names on the wire") {
    val json = ujson.read(roundTrip(plan))
    assert(json("operations")(0)("operation").str == "RESEARCHER_RANKING")
    assert(json("operations")(1)("scope").str == "PAIR")
    assert(json("source").str == "MODEL")
    assert(json("window")("requested")("kind").str == "RANGE")
    assert(json("window")("requested")("yearTo").isNull)
  }

  test("an unknown enum value fails decoding") {
    val json = write(plan).replace("\"RESEARCHER_RANKING\"", "\"RUN_CYPHER\"")
    assertThrows[Exception](read[QueryPlan](json))
    assertThrows[Exception](read[PlannerSource]("\"LLM\""))
  }

  test("resolution and every graph-fact record round-trip") {
    roundTrip(resolution)
    roundTrip(
      resolution.copy(
        decision = ResolutionDecision.UNKNOWN_RESEARCHER,
        nameKey = None,
        researcherId = None,
        topShare = None,
        totalWorks = 0L,
        candidateCount = 0,
        candidates = Vector.empty,
        otherNameMatches = Vector.empty,
        evidenceId = None
      )
    )
    val facts = GraphFacts(
      RuntimeSchemas.GraphFacts,
      queryId,
      run,
      Vector(
        MetricRecord(
          "all-years",
          metrics,
          Some(3L),
          Some(4L),
          Some(run),
          "researcher-metrics-window-v1@" + run
        )
      ),
      Vector(
        RankingRecord(
          CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER,
          "all-years",
          2000,
          2026,
          Vector(
            RankingEntry(1, ada, 286.0, Some("Ada Lovelace")),
            RankingEntry(2, grace, 286.0, None)
          ),
          50,
          286.0,
          Vector("topic:openalex:T1"),
          "researcher-ranking-v1@" + run,
          prov("RANKING#maximumCoauthorsOnOnePaper", Some("graph-v1"))
        )
      ),
      Vector(
        ChangeRecord(
          ada,
          WindowComparison("2000-2014", "2015-2026"),
          "BOTH",
          Map("fromDistinctCoauthorCount" -> "12", "toDistinctCoauthorCount" -> "30"),
          "researcher-change-v1@" + run,
          prov(ada, Some("graph-v1"))
        )
      ),
      Vector(
        CoauthorList(
          ada,
          "all-years",
          Vector(
            link,
            link.copy(supportingWorkIds = Vector("dblp:conf/x/A21"), supportingWorkIdsRef = None)
          ),
          100,
          truncated = true,
          pairWith = None,
          crossCommunity = false
        ),
        CoauthorList(ada, "all-years", Vector.empty, 1, truncated = false, Some(grace), false)
      ),
      Vector(
        PathRecord(
          ada,
          grace,
          "all-years",
          PathStatus.FOUND,
          6,
          Some(1),
          Vector(ada, grace),
          Vector(PathHop(1, link)),
          "path.shortest.v1",
          run
        ),
        PathRecord(
          ada,
          grace,
          "all-years",
          PathStatus.SEARCH_LIMIT,
          6,
          None,
          Vector.empty,
          Vector.empty,
          "path.shortest.v1",
          run
        )
      ),
      Vector(
        CommunityRecord(
          comm,
          "all-years",
          3L,
          2L,
          1L,
          Some(ada),
          Vector(NamedCount(ada, "Ada Lovelace", Some(2L))),
          membersTruncated = false,
          Vector(NamedCount("comm-2", "", Some(1L))),
          prov(comm)
        )
      ),
      Vector(
        FacetRecord(
          ada,
          FacetKind.INSTITUTION,
          None,
          Vector(NamedCount("ror:01", "Institute 0", Some(3L))),
          "AFFILIATED_WITH evidence",
          truncated = false,
          prov(ada)
        )
      ),
      Vector(WorkRef("dblp:conf/x/A21", Some("A study"), Some(2021), prov("dblp:conf/x/A21"))),
      Vector(
        MissingEvidence(
          MissingReason.NOT_IN_STORED_TOP_N,
          "RANKING#coreness/TOP#all-years",
          "rank 60 requested; 50 stored"
        )
      ),
      Vector(TemplateCall("graph.coauthors.v1", 12L, 100, truncated = true))
    )
    roundTrip(facts)
  }

  test("semantic result, bundle, synthesis output, and verification round-trip") {
    roundTrip(
      SemanticResult(
        RuntimeSchemas.SemanticResult,
        "researcher-profile-v1",
        "amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1",
        """{"window":{"$eq":"all-years"}}""",
        5,
        Vector(semantic),
        1,
        40L
      )
    )
    roundTrip(
      EvidenceBundle(
        RuntimeSchemas.EvidenceBundle,
        queryId,
        Vector(selection),
        Vector(evidence),
        Vector(semantic),
        Vector(MissingEvidence(MissingReason.OMITTED_BY_BUDGET, "semantic", "3 items")),
        budget,
        run,
        prov(queryId)
      )
    )
    roundTrip(
      SynthesisOutput(
        RuntimeSchemas.SynthesisOutput,
        "Ada Lovelace has 4 distinct coauthors.",
        Vector(
          ClaimDraft(
            "claim-1",
            ClaimKind.METRIC_VALUE,
            "Ada Lovelace has 4 distinct coauthors in 2000-2026.",
            Vector(evidence.evidenceId),
            Vector(ada),
            Some("all-years"),
            Map("distinctCoauthorCount" -> "4")
          )
        ),
        Vector("Values depend on dominant-name-v1 identity linking.")
      )
    )
    roundTrip(verification)
  }

  test("answer and audit record round-trip") {
    roundTrip(
      RuntimeAnswer(
        RuntimeSchemas.Answer,
        response,
        plan,
        Vector(resolution),
        verification,
        None,
        StableIds.textHash(write(response))
      )
    )
    roundTrip(
      RuntimeAnswer(
        RuntimeSchemas.Answer,
        response.copy(abstained = true, claims = Vector.empty),
        plan,
        Vector(resolution),
        verification,
        Some(Abstention(AbstentionReason.AMBIGUOUS_RESEARCHER, "Lei Zhang: 101 candidates")),
        StableIds.textHash("x")
      )
    )
    roundTrip(
      AuditRecord(
        RuntimeSchemas.Audit,
        queryId,
        Some("sess-1"),
        run,
        "2026-11-06T01:02:03Z",
        RuntimeMode.FAKE,
        StableIds.textHash("question"),
        8,
        plan,
        Vector(
          ResolutionSummary(
            "Ada Lovelace",
            ResolutionDecision.RESOLVED_DOMINANT,
            Some(ada),
            Some(0.95),
            2
          )
        ),
        Vector(evidence.evidenceId),
        Vector(semantic.evidenceId),
        Vector("researcher-metrics-window-v1@" + run),
        run,
        Vector("researcher-profile-v1"),
        Some("fake:amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1"),
        Map("planner" -> "planner-v1", "synthesis" -> "synthesis-v1"),
        Map("planner" -> "us.anthropic.claude-haiku-4-5-20251001-v1:0"),
        Vector(
          LlmCall(
            Stage.QUERY_PLANNING,
            "m",
            "planner-v1",
            RuntimeSchemas.PlannerOutput,
            1,
            CallOutcome.INVALID_OUTPUT,
            2,
            Some(900),
            Some(120),
            800L,
            7500L,
            Some(
              "output-is-json: the output is not exactly one JSON object (output 812 chars, starts with a letter, ends with '}', stop reason end_turn)"
            )
          ),
          LlmCall(
            Stage.ANSWER_SYNTHESIS,
            "m",
            "synthesis-v2",
            RuntimeSchemas.SynthesisOutput,
            1,
            CallOutcome.OK,
            1,
            None,
            None,
            1200L,
            0L,
            None
          )
        ),
        Vector(EmbeddingCall("researcher-profile-v1", "fake:x", 1, CallOutcome.OK, None, 3L, 0L)),
        Vector(TemplateCall("kv.metrics.window.v1", 1L, 1, truncated = false)),
        Stage.values.toVector.map(s => StageTiming(s, StageOutcome.OK, 1L)),
        budget,
        counts,
        Vector(
          RemovedClaim(
            "claim-2",
            ClaimKind.PATH_HOP,
            "The second hop links A and B.",
            VerificationStatus.UNSUPPORTED,
            Vector(CheckResult("numbers-in-text", CheckOutcome.FAIL, "2 is not bound"))
          )
        ),
        Vector(
          DroppedLine(LinePart.SUMMARY, "They have 3 papers.", Vector("3 is not bound")),
          DroppedLine(LinePart.UNCERTAINTY, "Maybe.", Vector("names X"))
        ),
        abstained = false,
        None,
        StableIds.textHash("answer"),
        prov(queryId)
      )
    )
  }

  test("metric attributes cover every core metric, and the ranked ones are the stored rankings") {
    assert(MetricAttributes.attribute.keySet == CollaborationMetric.values.toSet)
    assert(MetricAttributes.ranked.map(MetricAttributes.attribute) == DynamoContract.RankingMetrics)
    assert(MetricAttributes.bridge.forall(MetricAttributes.ranked.contains))
  }

  test("schema versions are distinct") {
    val all = Vector(
      RuntimeSchemas.QueryRequest,
      RuntimeSchemas.PlannerOutput,
      RuntimeSchemas.QueryPlan,
      RuntimeSchemas.Resolution,
      RuntimeSchemas.GraphFacts,
      RuntimeSchemas.SemanticResult,
      RuntimeSchemas.EvidenceBundle,
      RuntimeSchemas.SynthesisOutput,
      RuntimeSchemas.Verification,
      RuntimeSchemas.Answer,
      RuntimeSchemas.Audit,
      RuntimeSchemas.QuerySessionItem,
      RuntimeSchemas.EvidenceBundleItem
    )
    assert(all.distinct.size == all.size)
    assert(all.forall(IdFormat.Version.matches))
  }
