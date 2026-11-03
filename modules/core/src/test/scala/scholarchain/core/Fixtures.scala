package scholarchain.core

import upickle.default.ReadWriter

/** One valid example of every record. Work keys and DOIs come from the CC0 DBLP sample
  * (datasets/samples/dblp); the only ORCID iD is ORCID's fictional test record; person names and
  * the "synthetic:" ID are synthetic (datasets/samples/orcid).
  */
object Fixtures:
  val snapshot = "2026-10-03"
  val fixtureRunId: String =
    StableIds.runId("hadoop-dblp-extract", "git-0000000", "config-v1", Seq("dblp=2026-10-03"), 1)

  def prov(
      src: SourceType,
      recordId: String,
      policy: Option[String] = None,
      model: Option[String] = None
  ): Provenance =
    Provenance(SchemaVersions.Provenance, src, recordId, snapshot, fixtureRunId, policy, model)

  val dblpKey = "conf/ics/LiaoHNZCT09"
  val workId: String = StableIds.dblpWorkId(dblpKey)
  val doi = "10.1145/1542275.1542359"
  val fictionalOrcid = "0000-0002-1825-0097"

  val researcherA: String = StableIds.researcherId(Some(fictionalOrcid), "er-v1", "unused")
  val researcherB: String = StableIds.researcherId(None, "er-v1", "orcid-record:synthetic:orcid-a")

  val mention1: AuthorMention = AuthorMention(
    SchemaVersions.AuthorMention,
    StableIds.mentionId(workId, 1),
    workId,
    1,
    "Sample Synthetic-A",
    "sample synthetic-a",
    Some(fictionalOrcid),
    None,
    Vector("openalex:I185103710"),
    prov(SourceType.DBLP, dblpKey)
  )
  val mention2: AuthorMention = mention1.copy(
    mentionId = StableIds.mentionId(workId, 2),
    position = 2,
    rawName = "Sample Synthetic-B 0001",
    normalizedName = "sample synthetic-b",
    orcid = None,
    institutionIds = Vector.empty
  )

  val work: WorkRecord = WorkRecord(
    SchemaVersions.WorkRecord,
    workId,
    SourceType.DBLP,
    dblpKey,
    Some("Prefetch optimizations on large-scale applications via parameter value prediction."),
    Some("prefetch optimizations on large scale applications via parameter value prediction"),
    Some(2009),
    Some("dblp-venue:conf/ics"),
    Some("inproceedings"),
    Some(doi),
    None,
    Map.empty,
    Vector(mention1, mention2),
    None,
    prov(SourceType.DBLP, dblpKey)
  )

  val researcher: CanonicalResearcher = CanonicalResearcher(
    SchemaVersions.CanonicalResearcher,
    researcherA,
    "Sample Synthetic-A",
    Vector("S. Synthetic-A"),
    Some(fictionalOrcid),
    Map("dblp-person" -> "homepages/00/0000"),
    0.98,
    None,
    prov(SourceType.DERIVED, researcherA, policy = Some("er-v1"))
  )

  val authorship: AuthorshipRecord = AuthorshipRecord(
    SchemaVersions.AuthorshipRecord,
    researcherA,
    workId,
    mention1.mentionId,
    1,
    Some(2009),
    0.98,
    prov(SourceType.DERIVED, mention1.mentionId, policy = Some("er-v1"))
  )

  val team: PublicationTeam = PublicationTeam(
    SchemaVersions.PublicationTeam,
    workId,
    Some(2009),
    Vector(mention1.mentionId, mention2.mentionId),
    prov(SourceType.DBLP, dblpKey)
  )

  val pairCandidate: CoauthorPairCandidate = CoauthorPairCandidate(
    SchemaVersions.CoauthorPairCandidate,
    mention1.mentionId,
    mention2.mentionId,
    workId,
    Some(2009),
    prov(SourceType.DBLP, dblpKey)
  )

  val edgeIdAB: String =
    StableIds.coauthorEdgeId(researcherA, researcherB, 2000, 2026, "graph-v1").toOption.get

  val coauthorEdge: CoauthorEdge = CoauthorEdge(
    SchemaVersions.CoauthorEdge,
    edgeIdAB,
    researcherA,
    researcherB,
    2000,
    2026,
    2L,
    2005,
    2009,
    Vector(workId, StableIds.doiWorkId("10.1145/1088149.1088173")),
    prov(SourceType.DERIVED, edgeIdAB, policy = Some("graph-v1"))
  )

  val metrics: ResearcherMetrics = ResearcherMetrics(
    SchemaVersions.ResearcherMetrics,
    researcherA,
    2000,
    2026,
    2L,
    5L,
    6L,
    3.0,
    5L,
    Vector(workId),
    7.0,
    "cc-1",
    12L,
    Some("community-17"),
    Some(1L),
    Some(2L),
    Some(0.5),
    Some(2),
    prov(SourceType.DERIVED, researcherA, policy = Some("graph-v1"))
  )

  val venue: Venue = Venue(
    SchemaVersions.Venue,
    "dblp-venue:conf/ics",
    "ICS",
    Some("conference"),
    Map("openalex" -> "S4306419857"),
    prov(SourceType.DBLP, "conf/ics")
  )

  val institution: Institution = Institution(
    SchemaVersions.Institution,
    "openalex:I185103710",
    "Synthetic Institute A",
    Some("US"),
    Map.empty,
    prov(SourceType.OPENALEX, "https://openalex.org/I185103710")
  )

  val topic: Topic = Topic(
    SchemaVersions.Topic,
    "openalex:T10054",
    "Parallel Computing and Optimization Techniques",
    Some("Hardware and Architecture"),
    Some("Computer Science"),
    prov(SourceType.OPENALEX, "https://openalex.org/T10054")
  )

  val matchCandidate: MatchCandidate = MatchCandidate(
    SchemaVersions.MatchCandidate,
    MatchEntityType.RESEARCHER,
    mention1.mentionId,
    "synthetic:orcid-a",
    "name:synthetic-a|s",
    Map("nameSimilarity" -> 0.92, "doiOverlap" -> 1.0),
    prov(SourceType.DERIVED, mention1.mentionId)
  )

  val matchDecision: MatchDecision = MatchDecision(
    SchemaVersions.MatchDecision,
    MatchEntityType.RESEARCHER,
    mention1.mentionId,
    "synthetic:orcid-a",
    Map("nameSimilarity" -> 0.92, "doiOverlap" -> 1.0),
    Vector("doi-overlap"),
    MatchOutcome.ACCEPTED,
    0.97,
    Vector("SHARED_DOI"),
    None,
    "2026-11-03T00:00:00Z",
    prov(SourceType.DERIVED, mention1.mentionId, policy = Some("er-v1"))
  )

  val graphNode: GraphNode = GraphNode(
    SchemaVersions.GraphNode,
    researcherA,
    NodeLabel.Researcher,
    Map("displayName" -> "Sample Synthetic-A"),
    prov(SourceType.DERIVED, researcherA, policy = Some("graph-v1"))
  )

  val graphEdgeIdAB: String = StableIds
    .graphEdgeId(EdgeType.COAUTHORED_WITH, researcherA, researcherB, "graph-v1", Seq(edgeIdAB))
    .toOption
    .get

  val graphEdge: GraphEdge = GraphEdge(
    SchemaVersions.GraphEdge,
    graphEdgeIdAB,
    EdgeType.COAUTHORED_WITH,
    researcherA,
    researcherB,
    Map(
      "jointPublicationCount" -> "2",
      "firstCollaborationYear" -> "2005",
      "lastCollaborationYear" -> "2009",
      "coauthorEdgeId" -> edgeIdAB
    ),
    prov(SourceType.DERIVED, graphEdgeIdAB, policy = Some("graph-v1"))
  )

  val metricEvidence: EvidenceItem = EvidenceItem(
    SchemaVersions.EvidenceItem,
    StableIds.evidenceId(EvidenceType.METRIC_RECORD, Seq(researcherA), fixtureRunId),
    EvidenceType.METRIC_RECORD,
    Vector(researcherA),
    None,
    "s3://example-scholarchain-graph/metrics/type=researcher_collaboration/window=all-years/",
    None,
    None,
    None,
    None,
    None,
    Map("distinctCoauthorCount" -> "5", "windowStartYear" -> "2000", "windowEndYear" -> "2026"),
    prov(SourceType.DERIVED, researcherA, policy = Some("graph-v1"))
  )

  val profileText = "Sample Synthetic-A works on prefetching for parallel applications."
  val profileChunkId: String = StableIds.chunkId(
    researcherA,
    fixtureRunId,
    "researcher-profile",
    None,
    StableIds.textHash(profileText)
  )

  val profileEvidence: EvidenceItem = EvidenceItem(
    SchemaVersions.EvidenceItem,
    StableIds.evidenceId(EvidenceType.PROFILE, Seq(profileChunkId), fixtureRunId),
    EvidenceType.PROFILE,
    Vector(profileChunkId),
    None,
    "s3://example-scholarchain-rag/profiles/researcher/",
    Some(profileChunkId),
    None,
    Some(0.842),
    Some(profileText),
    Some(StableIds.textHash(profileText)),
    Map.empty,
    prov(SourceType.DERIVED, profileChunkId, model = Some("example-embedding-model"))
  )

  val queryId = "qry-example-1"
  def runtimeProv: Provenance =
    prov(SourceType.RUNTIME, queryId, model = Some("example-synthesis-model"))

  val bundle: EvidenceBundle = EvidenceBundle(
    SchemaVersions.EvidenceBundle,
    queryId,
    Vector(metricEvidence),
    Vector(profileEvidence),
    runtimeProv
  )

  val queryPlan: QueryPlan = QueryPlan(
    SchemaVersions.QueryPlan,
    queryId,
    Vector(QueryOperation.RESEARCHER_RANKING, QueryOperation.BRIDGE_RANKING),
    Vector.empty,
    Vector.empty,
    Some(2015),
    Some(2026),
    Vector("distributed systems"),
    Vector.empty,
    Vector.empty,
    Some(CollaborationMetric.DISTINCT_COAUTHOR_COUNT),
    100,
    30,
    6,
    runtimeProv
  )

  val claim: AnswerClaim = AnswerClaim(
    SchemaVersions.AnswerClaim,
    "claim-1",
    "The researcher has 5 distinct coauthors in the 2000 to 2026 window.",
    Vector(metricEvidence.evidenceId),
    VerificationStatus.VERIFIED,
    Some("Depends on the er-v1 identity policy and the 2026-10-03 snapshot."),
    runtimeProv
  )

  val answer: AnswerResponse = AnswerResponse(
    SchemaVersions.AnswerResponse,
    queryId,
    "One researcher matched.",
    Vector(claim),
    false,
    None,
    runtimeProv
  )

  /** A record example with its codec and validator, so tests can iterate over every record. */
  final case class Example[A](name: String, value: A)(using
      val rw: ReadWriter[A],
      val v: Validator[A]
  ):
    def encode: String = WireCodec.encode(value)
    def decode(json: String): Either[List[String], A] = WireCodec.decode[A](json)
    def schemaVersionOf: String = ujson.read(encode)("schemaVersion").str

  val all: Vector[Example[?]] = Vector(
    Example("Provenance", work.provenance),
    Example("WorkRecord", work),
    Example("AuthorMention", mention1),
    Example("CanonicalResearcher", researcher),
    Example("AuthorshipRecord", authorship),
    Example("PublicationTeam", team),
    Example("CoauthorPairCandidate", pairCandidate),
    Example("CoauthorEdge", coauthorEdge),
    Example("ResearcherMetrics", metrics),
    Example("Venue", venue),
    Example("Institution", institution),
    Example("Topic", topic),
    Example("MatchCandidate", matchCandidate),
    Example("MatchDecision", matchDecision),
    Example("GraphNode", graphNode),
    Example("GraphEdge", graphEdge),
    Example("EvidenceItem", metricEvidence),
    Example("EvidenceBundle", bundle),
    Example("QueryPlan", queryPlan),
    Example("AnswerClaim", claim),
    Example("AnswerResponse", answer)
  )
