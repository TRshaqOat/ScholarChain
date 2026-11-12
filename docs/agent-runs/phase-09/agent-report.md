# Phase 09 agent reports

## Work order 1: rag-engineer

````text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09, session 1, work order 1 (runtime contract). This run resumed after an interruption: the earlier session was cut off during sbt check, after formatting. I inspected the partial work on disk and found it intact. I finished it and ran sbt check twice.

WHAT ALREADY EXISTED (read before writing):
- **core records.** Reused as they are: EvidenceItem, AnswerClaim, AnswerResponse, Provenance, ResearcherMetrics, and the enums QueryOperation (the ruling 4 list plus VENUE_FILTER), CollaborationMetric, VerificationStatus, and EvidenceType.
- **Core QueryPlan and EvidenceBundle (query-plan-v1, evidence-bundle-v1).** Too thin for ruling 4/8: no typed per-operation parameters, no window selection, no budget, no resolutions. Per ruling 1 the runtime defines its own versions in rag and does not use these.
- **rag (Phase 7).** Rendering-v1 templates, the RenderCli and SmokeCli, the embedding model registry, selection-v1, and SemanticContext. SemanticContext already turns vector hits into semantic EvidenceItems and renders the escaped prompt block.
- **storage (STORAGE.md).**
  - Existing read ports: GraphReadPort (vertex, edge, coauthorEdge, coauthors), KeyValueReadPort, ObjectReadPort, VectorReadPort, the Embedder (HashingEmbedder, BedrockTitanEmbedder), and Throttle.
  - Live-call helpers: LiveAws.bedrock, RefreshingCredentials, and guarded (refresh and retry once on an expired token).
  - Not present yet: openCypher templates (M12), a file-backed runtime record store, fixed runtime item schema names, and a works count on EntityLinkCache (M9).
- **Phase 7 store outputs** (read-only, conversion run run-1d6a86491812ff94887270c1ab885e69): Neptune CSV, coauthor evidence, the DynamoDB item files (EntityLinkCache, ResearcherMetricCache with WINDOW#, CHANGE#, and 21 RANKING# items, ArtifactRegistry), and the embedding candidates. There is no window summary or window comparison object, and no component or path-evidence object.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md (21 sections):
  - principles; the stage graph with each stage's input and output types;
  - the request and QueryPlan schema, the operation allowlist, validation, request overrides, and the rule planner;
  - windows; researcher resolution; metrics, rankings, and filters; paths and communities;
  - read operations; semantic context; the evidence bundle and evidence-ID scheme;
  - synthesis; verification rules; abstention; audit; budgets; versioning; fake and live modes; untrusted text;
  - core changes and follow-ups; configuration keys; known failure modes.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Contracts.scala:
  - RuntimeSchemas (13 schema versions);
  - the runtime enums: Stage, StageOutcome, PlannerSource, WindowKind, OperationScope, ResolutionDecision, PathStatus, FacetKind, MissingReason, ClaimKind, CheckOutcome, ClaimAction, AbstentionReason, CallOutcome, RuntimeMode;
  - MetricAttributes, which maps each core metric to its ResearcherMetricCache attribute and names the 7 ranked and 3 bridge metrics.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Records.scala: the runtime records, all `derives ReadWriter`, with no stage logic:
  - request: QueryRequest and its four groups;
  - planning: PlannerOutput, PlannedOperation, WindowRequest, WindowComparison, WindowSelection, NameMention, PlanLimits, QueryPlan;
  - resolution: ResearcherResolution, NameCandidate, NamedCount;
  - graph facts: MetricRecord, RankingRecord, RankingEntry, ChangeRecord, CoauthorLink, CoauthorList, PathHop, PathRecord, CommunityRecord, FacetRecord, WorkRef, MissingEvidence, TemplateCall, GraphFacts;
  - semantic and evidence: SemanticResult, BundleBudget, EvidenceBundle;
  - synthesis and verification: ClaimDraft, SynthesisOutput, CheckResult, ClaimCheck, VerificationCounts, VerificationReport;
  - answer: Abstention, RuntimeAnswer;
  - audit: LlmCall, EmbeddingCall, StageTiming, ResolutionSummary, AuditRecord.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/RuntimeRecordsSpec.scala: 8 tests.
  - Round-trips of every record, with absent and present optional fields; None serializes as JSON null.
  - Enums serialize as their case names, and an unknown enum value fails decoding.
  - MetricAttributes covers every CollaborationMetric, and its ranked list equals storage DynamoContract.RankingMetrics.
  - Schema versions are distinct and well-formed.
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RAG.md (one sentence now points to RUNTIME.md).
- The three new Scala files were reformatted by `sbt rag/scalafmtAll` (layout only).
FILES DELETED: none

ALLOWED PLAN OPERATIONS (RUNTIME.md section 3): the core QueryOperation values, each with allowed scopes, metric rules, reads, and evidence types.
- RESEARCHER_RANKING (CORPUS; one of the 7 ranked metrics)
- MAXIMUM_TEAM_SIZE (RESEARCHER, CORPUS)
- DIRECT_COAUTHORS (RESEARCHER, PAIR)
- SHORTEST_PATH (PAIR)
- COMPONENT_LOOKUP (RESEARCHER, PAIR)
- COMMUNITY_COMPARISON (RESEARCHER, PAIR)
- BRIDGE_RANKING (CORPUS, RESEARCHER; one of the 3 bridge measures, or all three named)
- TEMPORAL_COMPARISON (RESEARCHER; stored CHANGE# pair only)
- TOPIC_FILTER, VENUE_FILTER, INSTITUTION_FILTER (RESEARCHER, CORPUS, or as a filter on the stored top 50)
- SEMANTIC_EXPLANATION (stage 6 only)

Metric lookups for named researchers are implicit stage 3 reads driven by `QueryPlan.metrics`, not an operation. No user text becomes Cypher, PartiQL, a filter expression, or an AWS call. Graph access is only through the fixed templates with bound parameters.

PROMPT, SCHEMA, AND MODEL VERSIONS:
- Record schemas: rag-query-request-v1, planner-output-v1, rag-query-plan-v1, researcher-resolution-v1, rag-graph-facts-v1, rag-semantic-result-v1, rag-evidence-bundle-v1, synthesis-output-v1, claim-verification-v1, rag-answer-v1, rag-audit-v1, rag-query-session-v1, rag-evidence-bundle-item-v1.
- Prompts (to be written in work order 4): planner-v1 and synthesis-v1, under prompts/planner and prompts/synthesis, each with a JSON schema. Each prompt file starts with a `prompt-version:` header line. The runtime records the version and the file's sha256 in the audit.
- Models: Haiku 4.5 `us.anthropic.claude-haiku-4-5-20251001-v1:0` for the planner and synthesis. Titan V2 for embeddings, using the RAG.md version string. models.verification-model is unused in this version.
- No prompt files were created in this work order.

IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR:
- **Rule.** dominant-name-v1, applied in this order:
  1. Look up EntityLinkCache with the shared name-v1 key (DynamoContract.nameKey).
  2. Keep as candidates only the items whose displayName equals the given name trimmed, case and accents included.
  3. Rank by AUTHORED works. The top candidate resolves (RESOLVED_DOMINANT) at share >= 0.9 and works >= 5; otherwise the name is AMBIGUOUS, and with no candidates it is UNKNOWN_RESEARCHER.
- **What is returned.** Every candidate, with ORCID, dblpAnchored, mentions, identityConfidence, works and share, affiliations, and top coauthors. Other-key matches are listed but never counted.
- **Reproduction check.** Counted with grep on the Phase 7 EntityLinkCache files:
  - The exact-display-name candidate sets equal work order 6b: 17, 6, 11, 4, 11, 10, 3, 101, 307, and 0.
  - The key alone would give 4 for Duato, 134 for Lei Zhang, and 420 for Wei Wang. That difference is why the display-name filter step exists.
  - Shares and decisions depend on the works counts, which work order 4 must reproduce.
- **Abstention reasons:**
  - AMBIGUOUS_RESEARCHER and UNKNOWN_RESEARCHER (no synthesis call);
  - INVALID_PLAN, NO_DATA_IN_WINDOW, UNSUPPORTED_REQUEST, INSUFFICIENT_EVIDENCE;
  - BUDGET_EXHAUSTED;
  - NO_VERIFIED_CLAIM (ruling 8).

  Abstention text is a fixed template, never model text.

VERIFICATION RULES (RUNTIME.md section 12): deterministic, with no model.
- Rules: evidence-exists, graph-evidence-required (semantic evidence alone fails a material claim), kind-matches-type, asserted-values, numbers-in-text, window, identity, path-hops, titles.
- Status: any CONFLICT gives CONFLICTING; otherwise any FAIL gives UNSUPPORTED; MISSING with at least one PASS gives PARTIALLY_SUPPORTED; all PASS gives VERIFIED.
- Actions: VERIFIED claims are kept, PARTIALLY_SUPPORTED claims are labelled, UNSUPPORTED and CONFLICTING claims are removed (they stay in the report and the audit).
- The model summary is replaced when it fails the numbers, identity, or titles checks.

LIST 1: CONFIGURATION KEYS FOR WORK ORDER 2 (name, type, default, reason; also in RUNTIME.md section 20):

Models and prompts:

| Key | Type | Default | Reason |
|---|---|---|---|
| models.planner-model (env SCHOLARCHAIN_PLANNER_MODEL_ID) | String | us.anthropic.claude-haiku-4-5-20251001-v1:0 | ruling 3 |
| models.synthesis-model (existing) | String | change the placeholder to the Haiku ID | ruling 3; domain-engineer's call if the placeholder guard should stay |
| runtime.prompts-dir (env SCHOLARCHAIN_PROMPTS_DIR) | String | "prompts" | where the prompt files are read |

Question and planner:

| Key | Type | Default | Reason |
|---|---|---|---|
| runtime.question.max-chars | Int | 2000 | question length bound |
| runtime.question.deadline-ms | Int | 180000 | per-question bound |
| runtime.planner.prompt-version | String | planner-v1 | ruling 13 |
| runtime.planner.max-output-tokens | Int | 800 | output budget |
| runtime.planner.max-operations | Int | 6 | plan validation |
| runtime.planner.max-researcher-names | Int | 4 | plan validation |
| runtime.planner.max-term-chars | Int | 200 | names, filters, lookup text |
| runtime.planner.max-filter-terms | Int | 5 | topics, venues, institutions each |

Synthesis:

| Key | Type | Default | Reason |
|---|---|---|---|
| runtime.synthesis.prompt-version | String | synthesis-v1 | ruling 13 |
| runtime.synthesis.max-output-tokens | Int | 2000 | output budget |
| runtime.synthesis.max-claims | Int | 20 | output validation |

LLM and embedding calls:

| Key | Type | Default | Reason |
|---|---|---|---|
| runtime.llm.requests-per-minute | Int | 8 (valid 1 to 8) | ruling 11 |
| runtime.llm.max-attempts-per-call | Int | 2 (valid 1 to 2) | ruling 3 |
| runtime.llm.timeout-ms | Int | 30000 | per-attempt timeout |
| runtime.llm.temperature | Double | 0.0 | repeatable plans and claims |
| runtime.embedding.requests-per-minute | Int | 50 (valid 1 to 50) | ruling 11 |
| runtime.embedding.timeout-ms | Int | 10000 | per-attempt timeout |

Resolution:

| Key | Type | Default | Reason |
|---|---|---|---|
| runtime.resolver.policy-version | String | dominant-name-v1 | ruling 5 |
| runtime.resolver.dominant-share | Double | 0.9 (above 0.5, at most 1) | ruling 5, Phase 7 ruling 30 |
| runtime.resolver.dominant-min-works | Int | 5 | ruling 5, Phase 7 ruling 30 |
| runtime.resolver.max-candidates | Int | 500 (valid 1 to 1000) | above Wei Wang's 420 key matches |
| runtime.resolver.candidate-coauthors | Int | 3 | candidate context |
| runtime.resolver.candidate-institutions | Int | 3 | candidate context |

Rankings and semantic retrieval:

| Key | Type | Default | Reason |
|---|---|---|---|
| runtime.rankings.stored-size | Int | 50 | the stored top-N (M10) |
| runtime.rankings.default-top-n | Int | 10 | default N |
| runtime.rankings.max-top-n | Int | 1000 | plan validation bound |
| runtime.semantic.top-k | Int | 5 | hits per index |
| runtime.semantic.max-indexes | Int | 3 | indexes per question |

Budgets and audit:

| Key | Type | Default | Reason |
|---|---|---|---|
| runtime.budget.graph-evidence-tokens | Int | 8000 | bundle share |
| runtime.budget.semantic-context-tokens | Int | 3000 | bundle share |
| runtime.budget.max-evidence-items | Int | 300 | bundle bound |
| runtime.budget.chars-per-token | Int | 3 | token estimate |
| runtime.budget.max-input-tokens-per-question | Int | 40000 | cost bound |
| runtime.budget.max-output-tokens-per-question | Int | 5600 | cost bound |
| runtime.audit.ttl-days | Int | 30 | Phase 7 ruling 22 |

Graph reads (storage templates):

| Key | Type | Default | Reason |
|---|---|---|---|
| graph-read.max-results | Int | 1000 | replaces the code constant GraphLimits.MaxResults |
| graph-read.id-batch-size | Int | 100 | batched ID templates |
| graph-read.path-max-frontier | Int | 50000 | path search bound |
| graph-read.path-timeout-ms | Int | 20000 | path search total time |
| graph-read.community-members-limit | Int | 100 | template bound |
| graph-read.community-neighbors-limit | Int | 20 | template bound |
| graph-read.community-facet-member-cap | Int | 2000 | aggregate bound |
| graph-read.facet-limit | Int | 10 | facet entries per subject |
| graph-read.lookup-limit | Int | 20 | name lookup bound |
| graph-read.max-lookup-ids | Int | 10 | topic IDs per aggregate |
| graph-read.neighbors-max-depth | Int | 3 | spec neighbors endpoint |
| graph-read.neighbors-max-limit | Int | 200 | spec neighbors endpoint |

- Cross-key checks: graph-evidence-tokens + semantic-context-tokens <= retrieval.context-token-budget; default-top-n <= stored-size; retrieval.max-path-depth <= 6; every Int >= 1.
- Existing keys used unchanged: graph.windows, s3-vectors.indexes, neptune.query-timeout-ms, retrieval.*, models.embedding-model, operations.*.
- The M10 items (table prefix, loader role ARN) are work order 2's own.

LIST 2: CORE RECORD CHANGES (domain-engineer):
- **C1.** Add four non-semantic EvidenceType values: IDENTITY_RESOLUTION, RANKING_RECORD, TEMPORAL_CHANGE, FACET_RECORD.
  - Without them, the verifier's kind-to-type rule cannot tell a ranking from a metric row, or an identity decision from a match decision.
  - Fallback if declined: MATCH_DECISION, METRIC_RECORD, METRIC_RECORD, and AUTHORSHIP, with a recordKind fact.
  - Whether evidence-v1 must change is domain-engineer's call: the record shape is unchanged, but older readers reject the new names.
- **C2.** Add StableIds.queryId(parts*) = "qry-" + hash("query", ...) and IdFormat.isQueryId (qry-<32 hex>).
- **Nothing else.** Optionally, a Scaladoc note that core QueryPlan and EvidenceBundle are unused by the runtime (ruling 1), with no shape change.

LIST 3: READ OPERATIONS FOR WORK ORDER 3 (storage-engineer; full parameters, limits, and returned fields in RUNTIME.md section 8):
- **Rules for every template.** Every parameter is bound, never concatenated. Every template has config limits, returns a truncated flag, and carries runId, snapshot, and schemaVersion (edges also policyVersion) on every element.
- **Neptune has no shortestPath().** Neptune openCypher does not support shortestPath() (aws-docs, feature-opencypher-compliance, accessed 2026-11-06). The shortest path must therefore be a client-driven bounded BFS over graph.frontier, with Spark job 5's tie-break.
- **Templates:**
  - graph.vertices(ids <= batch)
  - graph.coauthors (exists; add the coauthor displayName)
  - graph.coauthor-edge (exists)
  - graph.supporting-works(edgeId) (complete work IDs, inline or from the evidence object)
  - graph.frontier(ids, window)
  - graph.shortest-path(source, target, window, maxDepth <= retrieval.max-path-depth): status FOUND, BEYOND_DEPTH, UNREACHABLE, UNKNOWN_RESEARCHER, or SEARCH_LIMIT, plus hops with edge, joint count, years, works, and provenance
  - graph.community-members(communityId, limit)
  - graph.community-neighbors(communityId, limit)
  - graph.cross-community-edges(researcherId, window, limit)
  - graph.community-facets(communityId, window, TOPIC|VENUE, limit, member cap)
  - graph.researcher-facets(ids, window, TOPIC|VENUE, perLimit)
  - graph.researcher-institutions(ids, perLimit)
  - graph.topic-researchers(topicIds, window, limit)
  - graph.lookup(Topic|Venue|Institution, text, limit)
  - graph.authored-counts(ids) (the interim works source for M9)
  - graph.neighbors(nodeId, direction, depth 1 to 3, limit 1 to 200, edgeTypes) (Phase 10)
- **Fakes:** every template on InMemoryGraph with the same limits.
- **Also needed:**
  - a file-backed create-only RuntimeRecordPort for QuerySession and EvidenceBundle under scratch/runtime/;
  - a public read-only loader of the DynamoDB item files into InMemoryDynamo (if LoadDynamoCli's path is not reusable);
  - DynamoContract.itemSchemaVersion names: QuerySession rag-query-session-v1, EvidenceBundle rag-evidence-bundle-item-v1.
- **Existing reads used:** kv.metrics.window, kv.metrics.change, kv.ranking, kv.entity-link, kv.artifact-active, obj.coauthor-evidence, obj.text, vec.query.

GRAPH QUERY TEMPLATES: specified above (List 3) and in RUNTIME.md section 8; storage-engineer implements them in work order 3.

BENCHMARK RESULTS: not applicable (work order 5). No benchmark run, no ratings, no labels.

KNOWN FAILURE MODES (RUNTIME.md section 21):
- A dominant-name homonym can be linked to the wrong person.
- The rule planner can miss lower-case names, or take a capitalized non-name as a name (this abstains safely).
- Rankings are capped at 50 with ties at the cut; filters apply only to the stored entries.
- The live path search can stop at SEARCH_LIMIT near hubs.
- The token estimate is a heuristic.
- Verification does not catch qualitative misreadings without numbers or names.
- Fake-embedder semantic hits are bag-of-words.

COMMANDS RUN:
- Reading and inspection: cat, sed, grep, ls, wc, and df on repository docs, sources, spec, and the read-only scratch/stores Phase 7 files. EntityLinkCache candidate counts were taken with grep. experiments/benchmark/benchmark-questions-v1.csv was read only.
- aws-docs MCP: search_documentation and read_documentation (Neptune openCypher compliance).
- `sbt -batch "rag/Test/compile"` (failed: the core.* wildcard import shadowed QueryPlan and EvidenceBundle; fixed with explicit imports).
- `sbt -batch "rag/testOnly scholarchain.rag.runtime.RuntimeRecordsSpec"` (8 succeeded).
- `sbt check` (failed the format check: 1 rag file unformatted).
- `sbt -batch "rag/scalafmtAll"` (reformatted 1 source per configuration).
- `sbt check` piped to tee (killed, exit 137, after formatting; this is where the earlier session was cut off).
- `sbt check` run 1 (exit 0) and `sbt check` run 2 (exit 0).

TEST RESULTS (exact observed):
- **sbt check run 1:** exit 0, 259 s. The six "Tests: succeeded" lines read 166 (4 suites), 23 (4 suites), 60 (7 suites), 94 (9 suites), 43 (7 suites), and 70 (6 suites); 0 failed, canceled, or ignored. markTestRun wrote .claude/.last-test-run (20:18).
- **sbt check run 2:** exit 0, 258 s. The same six lines, identical counts, 0 failed. markTestRun wrote .claude/.last-test-run (20:23).
- **Per-module attribution:** sbt interleaves the output of parallel projects, so I did not attribute each total to a module.
- **New tests:** all 8 RuntimeRecordsSpec tests are listed as passed in the run 1 log.

VALIDATION RESULTS:
- The scalafmt check passes in both runs.
- The new records compile against core and storage with no new dependency.
- The EntityLinkCache exact-display-name counts match work order 6b for all 10 benchmark pair names.
- Free disk: 20 GiB.

ASSUMPTIONS:
- Metric lookups are implicit (QueryPlan.metrics) rather than a new operation, because ruling 4 fixes the allowlist.
- VENUE_FILTER stays allowed, since core defines it and ruling 6 names venue filters.
- Unspecified BRIDGE_RANKING returns all three measures, each named.
- Synthesis has no template fallback in live mode; it abstains with BUDGET_EXHAUSTED. Fake mode uses a template synthesizer.
- The question is embedded once and reused across indexes, which meets ruling 3's 1 Titan call per index.
- "Live" in Phase 9 means live Bedrock calls only. Graph, key-value, and vectors stay local file and in-memory stores; the vector path is passed by the human.
- The audit stores only the question hash and length, never the question text.

RISKS:
- Two QueryPlan and two EvidenceBundle types (core and rag) can be confused. Runtime code must import core names explicitly; RUNTIME.md and the test note this.
- Corpus-level questions (B13, B15, B17, B24) and all-years "in the largest component" stay unanswered (NOT_STORED) until follow-up F2.
- The work order 4 resolver must reproduce the 6b shares from AUTHORED counts. Only the candidate sets were verified here.
- The configuration defaults are provisional and not evaluated.

MANUAL ACTION REQUIRED:
- **Follow-up F1 (spark-engineer, storage-engineer; M9).** Add authoredWorkCount to EntityLinkCache items and make it required (entity-link-v2).
- **Follow-up F2 (spark-engineer, storage-engineer).** Export the window-summary and window-comparison store objects, including the largest component ID, with a storage read.
- **Follow-up F3 (build-engineer, Phase 10).** Package prompts/ into the service image.
- **Ruling decisions for the human:**
  - whether the models.synthesis-model default changes from the placeholder to the Haiku ID (domain-engineer's call);
  - whether the C1 enum additions are accepted, or the fallback mapping applies.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order 2 (domain-engineer: configuration keys and C1/C2), work order 3 (storage-engineer: read ports); then retrieval and groundedness evaluation, code-reviewer APPROVE

Not checked: per-module attribution of the sbt test totals (interleaved output); the 6b top shares, which need AUTHORED counts (work order 4); and the Haiku InvokeModel request and response shape against Bedrock docs (deferred to work order 4).
````

## Work order 2: domain-engineer

````text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 09, session 1, work order 2. Add the runtime configuration keys from work order 1 LIST 1, plus the table prefix and the Neptune loader role ARN (Phase 7 M10), and the core record changes C1 and C2. Update the contract tests and run sbt check twice.

All the work order 1 keys are in, along with both M10 keys, C1 and C2. `sbt check` passed twice. I did not use the proposed name for one key: `runtime.rankings.stored-size` is `analytics.ranking-size` instead. The reason is under RENAMED KEY.

FILES CREATED: none

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/resources/scholarchain.conf
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
  - New typed classes: DynamoDbConfig, RuntimeConfig and its parts (QuestionConfig, PlannerConfig, SynthesisConfig, LlmCallConfig, EmbeddingCallConfig, ResolverConfig, RankingsConfig, SemanticConfig, BudgetConfig, AuditConfig), and GraphReadConfig.
  - New fields: NeptuneConfig.loaderRoleArn, AnalyticsConfig.rankingSize, ModelsConfig.plannerModel, and ScholarChainConfig.dynamoDb, runtime and graphRead.
  - New validation rules, listed under VALIDATION RESULTS.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/Enums.scala (C1)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/StableIds.scala (C2)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/Records.scala (Scaladoc only: notes that the Phase 9 runtime does not use the core QueryPlan and EvidenceBundle; no change to their shape)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSpec.scala (6 new tests; the existing non-local test now also supplies the two M10 values)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/StableIdsSpec.scala (1 new test)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/WireContractSpec.scala (2 new tests)
- `sbt core/scalafmtAll` changed the layout of the edited core files.
- There is no `config/` directory. The versioned configuration file is still `modules/core/src/main/resources/scholarchain.conf`.

FILES DELETED: none

SCHEMA VERSIONS:
- All record schema versions are unchanged. `evidence-v1` stays `evidence-v1`.
- **Why `evidence-v1` stays:**
  - The record's JSON shape and field meanings are the same. The C1 change only adds names to the end of the enum, and every existing value has the same wire name.
  - Every stored `evidence-v1` record still decodes. Records already exist under scratch/stores, for example the coauthor-evidence objects of run-1d6a86491812ff94887270c1ab885e69.
  - A version bump would make those records fail validation, because validation accepts exactly the current version.
  - The only thing that breaks is an old reader seeing a new name. There is no separately deployed old reader, because every module compiles against the same core.
  - A new frozen test fixes the enum's name list and states the rule: adding a name keeps v1, and renaming or removing one needs a version bump.
- **config-version stays `config-v1`.** The change adds keys only:
  - Every new key has a default in the reference file, so existing override files and environments still load.
  - No key was renamed or removed, and no key's meaning changed. `models.synthesis-model` changed its default value, not its meaning.
  - configVersion is an input to run IDs: Render.runIdFor, EmbeddingRunner.runIdFor, and the planned rag-runtime run ID. A bump would change the Phase 7 render run ID (run-b6343...) and fake embed run ID (run-13e2...) with no contract reason.

STABLE ID FORMULAS:
- **New (C2):** `StableIds.queryId(runId, questionHash, requestHash, ordinal)` = "qry-" + H("query", runId, questionHash, requestHash, ordinal).
  - H is the existing 32-hex SHA-256 over the parts joined by U+001F.
  - It is the RUNTIME.md section 15 formula exactly, and no timestamp is an input.
  - It requires a well-formed runId, non-blank hashes, and an ordinal of at least 1.
  - New check `IdFormat.isQueryId` accepts `qry-<32 lower-case hex>`.
  - Frozen value, computed independently with Python hashlib: queryId(run-78764f1e9c70e21f34ef30d82b71089b, "sha256:question", "sha256:request", 1) = qry-e605c59cdcfda782444ceae12233ca77.
  - **Departure from LIST 2:** C2 asked for a free `parts*` argument list. I made the signature typed so the formula is fixed in core. No rag code calls it yet.
- All other formulas are unchanged.

PAIR CANONICALIZATION RULE: unchanged. A is the smaller ID by String.compareTo, and a self-pair is not a pair.

CONFIGURATION PRECEDENCE: unchanged. Highest first:
1. The documented environment variables in ConfigLoader.EnvOverrides.
2. The file named by SCHOLARCHAIN_CONFIG_FILE.
3. The `scholarchain.conf` reference file.

NEW KEYS (all under `scholarchain.`):
- **M10 keys:**
  - `neptune.loader-role-arn`: String, default "arn:aws:iam::000000000000:role/example-neptune-loader" (a fake placeholder). Env SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN.
  - `dynamodb.table-prefix`: String, default "example-scholarchain-" (a fake placeholder). Env SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX.
- **Models:**
  - `models.planner-model`: default us.anthropic.claude-haiku-4-5-20251001-v1:0. Env SCHOLARCHAIN_PLANNER_MODEL_ID.
  - `models.synthesis-model` (existing key): default changed from "example-synthesis-model" to the same Haiku ID.
- **Prompts directory:** `runtime.prompts-dir` = "prompts". Env SCHOLARCHAIN_PROMPTS_DIR.
- **Runtime keys:** every other `runtime.*` key exactly as named and defaulted in work order 1 LIST 1 and RUNTIME.md section 20:
  - question.max-chars and question.deadline-ms;
  - planner.prompt-version, max-output-tokens, max-operations, max-researcher-names, max-term-chars, max-filter-terms;
  - synthesis.prompt-version, max-output-tokens, max-claims;
  - llm.requests-per-minute, max-attempts-per-call, timeout-ms, temperature;
  - embedding.requests-per-minute and embedding.timeout-ms;
  - resolver.policy-version, dominant-share, dominant-min-works, max-candidates, candidate-coauthors, candidate-institutions;
  - rankings.default-top-n and rankings.max-top-n;
  - semantic.top-k and semantic.max-indexes;
  - budget.graph-evidence-tokens, semantic-context-tokens, max-evidence-items, chars-per-token, max-input-tokens-per-question, max-output-tokens-per-question;
  - audit.ttl-days.
- **Graph-read keys:** all 12 `graph-read.*` keys exactly as listed: max-results, id-batch-size, path-max-frontier, path-timeout-ms, community-members-limit, community-neighbors-limit, community-facet-member-cap, facet-limit, lookup-limit, max-lookup-ids, neighbors-max-depth, neighbors-max-limit.

RENAMED KEY:
- The proposed `runtime.rankings.stored-size` is `analytics.ranking-size` (default 50).
- One key now serves both the producer (Spark's `StoreFiles.RankingMax`, a code constant listed in M10) and the runtime. Two keys for one stored number could drift apart.
- This is the only departure from the work order 1 names. No rag code uses the proposed name yet; only RUNTIME.md does.

DECISION ON models.synthesis-model:
- The default is now the real Haiku ID.
- Reason: ruling 3 fixes the model for every environment, so it is a versioned decision, not an environment-specific placeholder. The `s3-vectors.indexes` names already work this way.
- The placeholder guard stays on both the planner and synthesis keys: an "example-" value is still rejected outside local.
- `models.verification-model` stays a placeholder. It is unused in Phase 9, but a non-local environment still has to supply it.

SAMPLE SERIALIZED RECORDS:
- An EvidenceItem with `"schemaVersion":"evidence-v1"` and `"evidenceType":"RANKING_RECORD"` round-trips, and a test checks it. The other fields equal the frozen metricEvidence example.
- Frozen query ID: qry-e605c59cdcfda782444ceae12233ca77.

AFFECTED CONSUMERS (reported only; I edited none of them):
- **storage-engineer:**
  - `LoadDynamoCli --table-prefix` and `DynamoStore(client, tablePrefix, ...)` should default to cfg.dynamoDb.tablePrefix.
  - `LoadNeptuneCli --loader-role-arn` should default to cfg.neptune.loaderRoleArn.
  - STORAGE.md line 407 says core has neither key yet.
  - The `GraphLimits.MaxResults` constant (Neptune.scala:610) should become cfg.graphRead.maxResults.
  - The work order 3 templates should read cfg.graphRead.*.
- **rag-engineer:**
  - RUNTIME.md section 20 should name `analytics.ranking-size` instead of `runtime.rankings.stored-size`.
  - The runtime uses StableIds.queryId with its typed signature and IdFormat.isQueryId, and cfg.runtime.* and cfg.models.plannerModel and synthesisModel.
  - The four new EvidenceType values are available.
  - Contracts.scala ClaimKind already has a value named TEMPORAL_CHANGE. It is a different enum from the new EvidenceType value of the same name; this is not a conflict, but import them carefully.
- **spark-engineer:** `StoreFiles.RankingMax = 50` should read cfg.analytics.rankingSize. The StoreConversionJob comment at line 550 also says 50.
- **service-engineer (Phase 10):** validate `/v1/explain/{queryId}` with IdFormat.isQueryId.
- **infra-engineer (later):** Terraform must name the DynamoDB tables with the same prefix, and output the loader role ARN for SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN.
- **Every module that loads configuration:** no source change is needed. No module outside core constructs these config case classes directly; I checked with grep.

COMMANDS RUN:
- Inspection: grep, sed, cat, df.
- An independent query-ID hash with python3 hashlib.
- Two python3 in-place edits of core files. A third edit, a heredoc for the test file, was blocked by guard-dangerous.sh (secret-env-dump) as a false positive on `(env: ...`. I switched to the Edit tool.
- One `sed -i` to qualify `StableIds.queryId` in StableIdsSpec. The unqualified name clashed with `Fixtures.queryId`.
- `sbt -batch "core/scalafmtAll" "core/test"`: first a compile error (the ambiguous `queryId`), then after the fix 175 succeeded, 0 failed.
- `sbt check` run 1 and `sbt check` run 2.

TEST RESULTS (exact observed):
- **sbt check run 1:** exit 0, 259 s. The six "Tests: succeeded" lines read 175 (4 suites), 23 (4), 60 (7), 94 (9), 43 (7), and 70 (6), with 0 failed, canceled, or ignored. markTestRun wrote .claude/.last-test-run at 20:36.
- **sbt check run 2:** exit 0, 259 s. Identical counts, 0 failed. markTestRun wrote .claude/.last-test-run at 20:40.
- **Core went from 166 to 175 tests (9 new).** Each is listed as passed in the run 1 log:
  - query ID frozen value and guards;
  - EvidenceType names frozen, and only TEXT_CHUNK and PROFILE are semantic;
  - the Phase 9 evidence types round-trip as evidence-v1 and are rejected in semanticEvidence;
  - M10 keys and env overrides;
  - M10 shape and placeholder rejection;
  - Haiku defaults and planner model and prompts-dir overrides;
  - runtime, ranking, and graph-read defaults;
  - 15 bound and cross-key rejection cases;
  - every `runtime` and `graph-read` leaf in the reference is read by the loader.
- The new tests reference symbols that did not exist before, so they would not compile without the change.
- The scalafmt check passed in both runs.

VALIDATION RESULTS (new loader rules):
- Every runtime and graph-read Int is at least 1, and analytics.ranking-size is at least 1.
- llm.requests-per-minute is at most 8 (ruling 11). llm.max-attempts-per-call is at most 2 (ruling 3). embedding.requests-per-minute is at most 50.
- llm.temperature is within [0, 1].
- resolver.dominant-share is above 0.5 and at most 1. resolver.max-candidates is at most 1000.
- graph-evidence-tokens + semantic-context-tokens is at most retrieval.context-token-budget.
- default-top-n is at most analytics.ranking-size and at most max-top-n.
- retrieval.max-path-depth is at most 6 (ruling 7).
- The prompt versions and the resolver policy version have version shape. prompts-dir is not blank.
- table-prefix is 1 to 200 of `[A-Za-z0-9_.-]`.
- loader-role-arn has IAM role ARN shape.
- The placeholder check now also catches `:000000000000:` inside an ARN and a `/example-` role name.
- Free disk: 20 GiB.

ASSUMPTIONS:
- The neighbors depth and limit keys are caps that Phase 10 enforces. Core does not hard-cap them at the spec's 3 and 200.
- The table prefix limit of 200 characters leaves room for the logical table name inside DynamoDB's 255-character limit.
- Core does not check that the loader role's account matches application.aws-account-id.

RISKS:
- The other M10 items are not configuration keys yet: the selection seed and quotas, InlineEvidenceMax, and the storage retry and throttle defaults. This work order limited M10 to the table prefix and the role ARN. M10 is therefore only partly closed, and the human should decide whether it stays open.
- `models.verification-model` is still a placeholder that a non-local environment must supply, even though Phase 9 does not use it.
- The runtime defaults are provisional and not evaluated, as work order 1 states.

MANUAL ACTION REQUIRED:
- The orchestrator routes the AFFECTED CONSUMERS above.
- The human decides on the M10 remainder.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, and code-reviewer APPROVE

Not checked: I did not run any downstream module's code with non-default values for the new keys, because nothing reads them yet.
````

## Work order 3: storage-engineer

````text
STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 09, session 1, work order 3. Covers the graph read port with openCypher templates (ruling 7), the other read ports from work order 1 LIST 3, alignment with the work order 2 configuration keys (including M10), and Phase 7 review 2 minors N1 to N6. All edits are under modules/storage/**.

**Summary.** Every LIST 3 template exists on the fake and as a live openCypher adapter, plus the runtime record store, the read-only item-file loader, and the fixed runtime schema names. N1 to N6 and the two M10 defaults are done, and `sbt check` passed twice.

One finding changed the design. A single-source path search (the first version) hit SEARCH_LIMIT on all 54 benchmark pairs at the default `graph-read.path-max-frontier` of 50,000, even for a 3-hop pair. I replaced it with a bidirectional search that keeps the Spark job 5 tie-break. On the full Phase 7 graph (read only) it reproduced all 54 stored path results: 41 FOUND with identical researcher sequences, 13 BEYOND_DEPTH, 0 differences, slowest 93 ms.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/GraphRead.scala: GraphReadLimits.from(cfg), the result types, and the GraphReadPort trait. Public methods check every bound, then call protected one-template `read*` methods. It also holds the composites: supportingWorks, shortestPath (bidirectional), and neighbors.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/InMemoryGraph.scala: the fake, moved out of Neptune.scala. It adds in/out adjacency for all edge types, and `reader(limits)` implements every template.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/live/NeptuneGraphReader.scala: OpenCypherTemplates (constant texts, request builder, result parser) and NeptuneGraphReader.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/test/scala/scholarchain/storage/GraphReadSpec.scala: 14 tests.

FILES MODIFIED (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/):
- src/main/scala/scholarchain/storage/Neptune.scala: removed `GraphLimits`, the old GraphReadPort, and InMemoryGraph (moved).
- src/main/scala/scholarchain/storage/Dynamo.scala: N4 numberOk range check and badNumbers; runtime item schema versions; `InMemoryDynamo.loadItemFiles`; `DynamoItemFile.files` and `tableDirs`; the new `FileRuntimeRecords`.
- src/main/scala/scholarchain/storage/live/LiveAws.scala: N1, N2, N5 (generation, refreshIfCurrent, guardedWith, isHeadExpiredToken), and the comment fix.
- src/main/scala/scholarchain/storage/live/S3Live.scala: N2.
- src/main/scala/scholarchain/storage/live/BedrockLive.scala: N1, adds a `beforeRetry` parameter.
- src/main/scala/scholarchain/storage/cli/EmbedCli.scala: N1, passes the runner's throttle to the embedder.
- src/main/scala/scholarchain/storage/cli/ValidateCli.scala: N6.
- src/main/scala/scholarchain/storage/cli/LoadDynamoCli.scala: M10 `tablePrefix`; tableDirs moved to Dynamo.scala.
- src/main/scala/scholarchain/storage/cli/LoadNeptuneCli.scala: M10 `loaderRoleArn`; uses `reader(limits)`.
- STORAGE.md: new section "Graph read templates", plus the DynamoDB, live adapter, credentials, CLI, and service-limit updates.
- Tests:
  - NeptuneSpec: adapted to the new reader API.
  - DynamoSpec: +4 tests, and the session item now uses rag-query-session-v1.
  - LiveRequestSpec: +4 tests (N1, N2, N3, N5).
  - CliSpec: +2 tests (N6, M10).
- `sbt storage/scalafmtAll` reformatted 5 main and 11 test sources (layout only). I cannot list exactly which files without git.

FILES DELETED: none that existed before. A temporary test-scope probe, FullGraphProbe.scala, was created for the full-graph measurement and deleted.

GRAPH QUERY TEMPLATES (spec Phase 9 item; full table in STORAGE.md "Graph read templates"):
- **Fixed text, bound values.** Each template is one constant. Enum variants are separate constants where openCypher cannot bind the part: `graph.lookup.{Topic,Venue,Institution}`, `graph.expand.{OUT,IN,BOTH}`, and `graph.{community,researcher}-facets.{TOPIC,VENUE}`. Every caller value is a `$parameter`. Each query is prefixed with `USING QUERY:TIMEOUTMILLISECONDS <neptune.query-timeout-ms>`. A test checks that no template contains CREATE, MERGE, DELETE, DETACH, SET, REMOVE, LOAD, or CALL, and that an injection string reaches only `parameters`.
- **Templates and bounds:**
  - `graph.vertex`, `graph.edge`.
  - `graph.vertices`: at most id-batch-size IDs.
  - `graph.coauthor-edge`.
  - `graph.coauthors`: limit at most retrieval.graph-limit; returns the coauthor displayName.
  - `graph.frontier`: fetch path-max-frontier + 1.
  - `graph.community-members`: at most community-members-limit; ordered by within-community degree in the window, then ID.
  - `graph.community-neighbors`: at most community-neighbors-limit.
  - `graph.cross-community-edges`: at most graph-limit.
  - `graph.community-facets`: at most facet-limit; first community-facet-member-cap members by ID; truncated when the community is larger than the cap.
  - `graph.researcher-facets`, `graph.researcher-institutions`: per researcher, perLimit at most facet-limit, at most max-results rows.
  - `graph.topic-researchers`: at most max-lookup-ids topics.
  - `graph.lookup`: text 1 to runtime.planner.max-term-chars characters, limit at most lookup-limit.
  - `graph.authored-counts`: unknown or unauthored IDs return 0.
  - `graph.expand`, used by `neighbors`: depth at most neighbors-max-depth, limit at most neighbors-max-limit.
  - Composites: `supportingWorks` (inline list or evidence object, count checked) and `shortestPath`.
- **Shared behaviour.** Every element is a GraphElement carrying runId, snapshot, and schemaVersion (edges also policyVersion). Limited results come back as `Bounded(items, truncated)`.
- **Shortest path statuses:** FOUND, BEYOND_DEPTH, UNREACHABLE, UNKNOWN_RESEARCHER, SEARCH_LIMIT. Hops are `PathHop(from, to, edge)`.
- **Live adapter.** A query timeout becomes GraphReadTimeoutException and is not retried; in a path search it gives SEARCH_LIMIT. 5xx errors are retried by RetryPolicy.Default.
- **Departures from RUNTIME.md section 8:**
  - community-facets takes no window; it uses the community's own window.
  - neighbors accepts all six loaded edge types, including MEMBER_OF.
  - graph.expand is an added template.
  - coauthors now returns `Bounded[CoauthorLink]`, and `InMemoryGraph.reader` is now `reader(limits)`. rag does not use the old API: I checked with grep, and sbt check compiles it.

OTHER READ PORTS (LIST 3):
- **`FileRuntimeRecords(root, windows, clock)`** (`writer` and `reader`).
  - One log per table: `root/QuerySession.jsonl` and `root/EvidenceBundle.jsonl`, replayed on open.
  - Creates are create-only. Only an Inserted item is appended.
  - On open, a partial last line is cut; any other unreadable line stops the open.
- **`InMemoryDynamo.loadItemFiles(dir)`.** Read-only; everything is validated first; returns CONFLICT and BAD_INPUT violations.
- **Item schema names.** `DynamoContract.itemSchemaVersion`: QuerySession `rag-query-session-v1`; EvidenceBundle `rag-evidence-bundle-item-v1`.

SCHEMAS AND KEYS:
- Graph labels and edge types are unchanged.
- DynamoDB keys are unchanged. Only the runtime schemaVersion names above are new.
- No change to S3 prefixes or vector indexes.

METADATA FIELDS: unchanged.

BATCH SIZES AND RETRY POLICY:
- Frontier and ID templates run in batches of graph-read.id-batch-size (100).
- Live reads retry with RetryPolicy.Default: 5 attempts, 200 ms doubling to at most 5 s, with jitter. Timeouts are not retried.
- An expired token gets one refresh and one retry. With N5, that is one refresh per expiry across concurrent requests. With N1, the retry runs the caller's `beforeRetry` (the throttle) first.

IDEMPOTENCY STRATEGY:
- Graph reads are read-only.
- Runtime records are create-only: Inserted, Unchanged, or Conflict.
- An item-file reload is Unchanged; a differing item is a CONFLICT and nothing is overwritten.

REVIEW 2 MINORS:
- **N1:** a retry now passes the throttle, and the comment is fixed. EmbedCli wires `throttle.acquire`.
- **N2:** any 403 on HeadObject gets one refresh and one retry; a second 403 propagates.
- **N3:** a new test detects expiry by code alone, using the S3 message text without the phrase; a typo code fails.
- **N4:** Numbers outside 0 or 1E-130 to below 1E+126 are refused, as is invalid text. Every put returns Left instead of throwing.
- **N5:** generation-based refreshIfCurrent, tested with 16 concurrent threads (one rebuild).
- **N6:** ValidateCli prints `INCOMPLETE` and exits 1 when edges-object-not-opened is above 0.
- **M10:** `--table-prefix` defaults to `dynamodb.table-prefix`, and `--loader-role-arn` to `neptune.loader-role-arn`. `GraphLimits.MaxResults` is replaced by graph-read.max-results.

SERVICE LIMITS (all accessed 2026-11-06):
- **openCypher compliance:** no shortestPath(); SKIP and LIMIT need static values; no CALL. https://docs.aws.amazon.com/neptune/latest/userguide/feature-opencypher-compliance.html
- **Query timeout hint:** `USING QUERY:TIMEOUTMILLISECONDS`; the effective timeout is the smaller of it and neptune_query_timeout. https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-query-hints-timeout-hint.html . A search result says engine 1.4.6.0 added it; I did not open the release page.
- **ExecuteOpenCypherQuery:** body `query` and `parameters` (a JSON string); response `results`; reads need neptune-db:ReadDataViaQuery. https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html
- **Result JSON format:** https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-opencypher-queries.html
- **DynamoDB Number range:** 1E-130 to 9.99...E+125, and 0; 38 digits. https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html

CLEANUP PROCEDURE:
- Tests use JVM temporary directories only.
- The probe only read scratch/stores (the graph CSV, evidence objects, and path candidates of run-1d6a86491812ff94887270c1ab885e69) and wrote nothing.
- Nothing was written under scratch/. labels/ and scratch/stores-live/ were not touched.

COMMANDS RUN:
- **Inspection:** cat, sed, grep, find, wc, df, and javap on the neptunedata 2.41.32 jar. Python and Perl in-place edits of storage files.
  - One heredoc edit was blocked by guard-dangerous.sh (graph-destructive): it matched the injection test string. I switched to the Edit tool, as ruling 15 requires.
- **aws-docs MCP:** search_documentation and read_documentation on the pages listed above.
- **sbt, during development:**
  - `sbt -batch storage/Test/compile`, several times; first with errors, which I fixed.
  - `storage/testOnly` for GraphReadSpec and NeptuneSpec, and `storage/test`.
  - `storage/scalafmtAll`.
- **Full-graph probe:** `sbt -batch -J-Xmx6g "storage/Test/runMain scholarchain.storage.FullGraphProbe ..."`, twice. First run: single-source search, 54 of 54 SEARCH_LIMIT. Second run: bidirectional, 54 of 54 equal.
- **`sbt check`** run 1 and run 2.

TEST RESULTS (exact observed; unit tests only, no AWS profile):
- **sbt check run 1:** exit 0, 21:06:18 to 21:10:43. "Tests: succeeded" lines: 175, 23, 94, 84, 43, 70 (489 in total), with 0 failed, canceled, or ignored.
- **sbt check run 2:** exit 0, 21:10:48 to 21:15:10. The same six counts, 0 failed.
- **Marker:** .claude/.last-test-run is at 21:15. `find modules build.sbt project -newer` printed nothing.
- **Storage suite:** 60 tests before, 84 now; all 24 new tests are listed as passed in the logs. Logs: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/f1f37c2b-e081-4915-8e55-3f0eb5330963/scratchpad/check2.log (run 2). Run 1 is in the background-task output file.

VALIDATION RESULTS (full Phase 7 graph, -Xmx6g, read only):
- The load took 32 s: 511,920 vertices and 3,123,311 edges.
- 3,769 MiB of heap were in use after GC.
- All 54 path candidates of collaboration-path-v1 matched: 41 FOUND with identical sequences, 13 BEYOND_DEPTH, 0 differences. The slowest search took 93 ms.
- Free disk: 20 GiB.

ASSUMPTIONS:
- A bound `$fetch` or `$memberCap` parameter counts as a static SKIP/LIMIT value for Neptune. Not verified live.
- Neptune `toLower` matches Java `Locale.ROOT` lower-casing for lookups. They may differ for some non-ASCII text.

RISKS:
- The live adapter has not been run against Neptune.
- The live cost of the community-members degree query on large communities is unmeasured.
- The graph fake (3.8 GiB) plus the full DynamoDB item fake in one JVM may exceed 6 GiB. That is unmeasured and matters for rag work order 4.
- N2 retries a genuine HEAD denial once (one extra request).

MANUAL ACTION REQUIRED (orchestrator routing):
- **rag-engineer:**
  - Use `InMemoryGraph.reader(GraphReadLimits.from(cfg))`, `loadItemFiles`, and `FileRuntimeRecords`.
  - The live Haiku client must call `LiveAws.guardedWith(beforeRetry = throttle.acquire)` (ruling 11).
  - SmokeCli should pass its throttle to BedrockTitanEmbedder; it is a rag-owned file.
  - Update RUNTIME.md section 8 for the departures listed above.
- **infra-engineer:**
  - The Neptune engine must be 1.4.6.0 or later for the timeout hint.
  - The runtime role should have neptune-db:ReadDataViaQuery only.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, rag work order 4 integration, bounded load test (cloud window), code-reviewer APPROVE

Not checked: Neptune behaviour of the templates themselves (no live call), and the combined graph and DynamoDB fake heap.
````

## Work order 4: rag-engineer

````text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09, session 1, work order 4. Runtime stages 1 to 10, the versioned prompts and schemas, the live Haiku client, the runtime CLI, and tests. I also handled the items routed to me from work orders 2 and 3.

**Summary.** Stages 1 to 10 run end to end in fake mode on both the test fixtures and the full Phase 7 stores. `sbt check` passed twice.
- **Heap measurement you asked for:** the full DynamoDB item fake beside the full graph fake does not fit in 6g. The run failed with OutOfMemoryError after 258 s; the graph alone was using 3,770 MiB.
- **What the CLI uses instead:** a read-only, offset-indexed reader over the item files (cli/ItemFileIndex.scala). With it, the CLI peaks at about 4.0 GiB.
- **Resolution check:** on the full stores, the resolver reproduces every work order 6b decision exactly (counts, shares, and researcher IDs).

FILES CREATED (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/):
- Stage code, in modules/rag/src/main/scala/scholarchain/rag/runtime/:
  - JsonSchema.scala: validator for the schema subset the runtime uses.
  - Prompts.scala: versioned prompt loader.
  - Llm.scala: model port, token ledger, and the bounded call loop with the throttle.
  - Planner.scala: stage 1 (question rules, allowlist, window selection, rule planner, validation, request overrides).
  - Resolver.scala: stage 2.
  - GraphStages.scala: stages 3 to 5.
  - Semantic.scala: stage 6.
  - Evidence.scala: stage 7.
  - Synthesis.scala: stage 8 (prompt values, output validation, fake-mode template synthesizer).
  - Verifier.scala: stage 9.
  - Audit.scala: stage 10.
  - Workflow.scala: runs the stages and decides abstentions.
- modules/rag/src/main/scala/scholarchain/rag/live/BedrockHaikuClient.scala
- modules/rag/src/main/scala/scholarchain/rag/cli/AskCli.scala and cli/ItemFileIndex.scala
- Prompts and schemas: prompts/planner/planner-v1.md, prompts/planner/planner-output-v1.schema.json, prompts/synthesis/synthesis-v1.md, prompts/synthesis/synthesis-output-v1.schema.json
- Tests, in modules/rag/src/test/scala/scholarchain/rag/runtime/: RuntimeFixtures.scala, PlannerSpec.scala (8 tests), WorkflowSpec.scala (9), HaikuClientSpec.scala (3), AskCliSpec.scala (3).

FILES MODIFIED:
- runtime/Records.scala:
  - CoauthorList gains `pairWith` and `crossCommunity`.
  - RankingRecord gains `lastStoredValue`.
  - A doc comment is corrected.
- RuntimeRecordsSpec.scala: updated for those record changes.
- cli/SmokeCli.scala: passes its throttle to BedrockTitanEmbedder (routed from work order 3).
- modules/rag/RUNTIME.md:
  - `analytics.ranking-size` replaces `runtime.rankings.stored-size` (routed from work order 2).
  - Section 8 records the work order 3 departures.
  - Notes on the typed queryId signature, C1/C2 marked done, and the ClaimKind/EvidenceType name overlap.
  - CORPUS scopes for three operations, the rule planner's rules, the prompt file format, verifier implementation notes, one manifest per question, and the exact CLI command.
- `sbt rag/scalafmtAll` reformatted 19 sources (layout only).

FILES DELETED: FullStoreProbe.scala, a temporary probe in the rag test tree that I created and deleted in this work order. Its outputs went to the scratchpad only.

ALLOWED PLAN OPERATIONS: the 12 core QueryOperation values, with the scopes in `Allowlist`.
- **Departure:** COMPONENT_LOOKUP, COMMUNITY_COMPARISON, and TEMPORAL_COMPARISON now also allow CORPUS. Corpus-level questions (B13, B15, B17, B24) therefore produce a stated `NOT_STORED` and abstain with UNSUPPORTED_REQUEST, rather than failing as INVALID_PLAN.
- **Where metrics are allowed:** a metric is allowed only on RESEARCHER_RANKING (the 7 stored rankings), BRIDGE_RANKING (the 3 bridge measures), and TEMPORAL_COMPARISON.
- **No free-form queries:** no stage builds Cypher, PartiQL, or AWS calls. The audit shows only allowlisted template and key IDs.

PROMPT, SCHEMA, AND MODEL VERSIONS:
- Prompts planner-v1 and synthesis-v1; output schemas planner-output-v1 and synthesis-output-v1.
- Each prompt file starts with `prompt-version` and `output-schema` lines. The audit records both versions and the sha256 of each prompt and schema file.
- A test checks that the schema enums equal the Scala enums.
- Models: Haiku `us.anthropic.claude-haiku-4-5-20251001-v1:0` for planning and synthesis; the embedding version is `amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1`.
- Record schema versions are unchanged. Two records gained fields while no stored data uses them yet.

IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR:
- **Rule:** dominant-name-v1, with works counted by `graph.authored-counts`.
- **Reproduction on the full stores (candidates and share):**

| Name | Candidates | Share |
|---|---|---|
| Jack J. Dongarra | 17 | 0.9576 |
| Yves Robert | 6 | 0.9775 |
| Rajkumar Buyya | 11 | 0.9645 |
| Albert Y. Zomaya | 4 | 0.9902 |
| Rachid Guerraoui | 11 | 0.9615 |
| Ian T. Foster | 10 | 0.9680 |
| José Duato | 3 | 0.9871 |
| Lei Zhang | 101 | 0.0273 (AMBIGUOUS) |
| Wei Wang | 307 | 0.0128 (AMBIGUOUS) |
| Elena Vasquez-Marlowe | 0 | UNKNOWN_RESEARCHER |

  Every row and researcher ID equals work order 6b.
- **AMBIGUOUS and UNKNOWN_RESEARCHER:** the answer lists every candidate (source identifiers, works, affiliations, coauthors, confidence) and asks which researcher is meant. No synthesis call is made.
- **Other abstentions:** INVALID_PLAN, NO_DATA_IN_WINDOW, UNSUPPORTED_REQUEST (B28: affiliations are not windowed), INSUFFICIENT_EVIDENCE, BUDGET_EXHAUSTED, and NO_VERIFIED_CLAIM.

VERIFICATION RULES: deterministic, as in RUNTIME.md section 12, with these differences (noted in the file):
- Before numbers in the text are checked, cited text values (names, titles, IDs) are removed, so a DBLP homonym number such as "0002" is not read as a claimed number.
- The window rule checks the claim's window field only. A year-range text check would reject legitimate first-to-last collaboration years.
- Claims citing only semantic context fail `graph-evidence-required`.
- UNSUPPORTED and CONFLICTING claims are removed. PARTIALLY_SUPPORTED claims are kept with a label.
- The model's summary and uncertainty lines are kept only if they pass the numbers, names, and titles checks.

GRAPH QUERY TEMPLATES: storage templates only, as in work order 3.

BENCHMARK RESULTS (temporary probe, fake mode, mechanical checks only, no ratings):
- **Graph retrieval:**
  - All 30 questions run.
  - B09 abstains AMBIGUOUS and B12 UNKNOWN_RESEARCHER.
  - B13, B15, B17, B24, and B28 abstain UNSUPPORTED_REQUEST.
  - B10 is FOUND at length 3 via Ivona Brandic and Paolo Romano 0002, matching work order 6b.
  - Verified-claim counts: 1 to 11 per answer.
- **Semantic retrieval:** reported separately; 0 to 5 hits per question. It is context only, and the bundle keeps it apart from graph evidence.
- **Verification:** 0 unsupported claims after one fix. B10, B11, and B30 at first had one unsupported descriptive claim, because the template took subjects from a path document's IDs. Fixed, with a regression test.
- **Timing:** questions answer in 1 to 968 ms after a 32 s graph load and a 16 to 22 s item indexing.
- **Groundedness and injection:** the injection tests pass. Injection text in the question, in a researcher name, in a retrieved profile, and in a paper title changes no plan operation, store call (template IDs), or claim, and a model claim that repeats the injected text is removed.

KNOWN FAILURE MODES: as in RUNTIME.md section 21, plus these:
- Ranking topic filters see only each researcher's top `facet-limit` topics.
- Community candidate context makes one `graph.coauthors` call per candidate (307 for Wei Wang). That is fast in memory but unmeasured live.

COMMANDS RUN:
- Inspection: cat, sed, grep, find, wc, df, and python3 on the read-only scratch/stores files and the benchmark CSV. sed -i and python3 edits applied only to my own files.
- aws-docs MCP: the Bedrock Anthropic Messages request and response page.
- `sbt -batch rag/Test/compile`, several times (fixed compile errors along the way).
- `rag/testOnly`, then `rag/test`.
- `sbt -batch -J-Xmx6g rag/runMain AskCli` on B07: OutOfMemoryError, exit 1. On B10 with the index: exit 0.
- `sbt -batch -J-Xmx6g rag/Test/runMain FullStoreProbe`: the 30 questions.
- `sbt rag/scalafmtAll`, then `sbt check` twice.

TEST RESULTS (exact observed):
- **sbt check run 1:** exit 0, 23:31:54 to 23:36:29. "Tests: succeeded" lines read 175, 46, 84, 94, 43, and 70, with 0 failed.
- **sbt check run 2:** exit 0, 23:36:39 to 23:41:02. Identical counts, 0 failed. `.claude/.last-test-run` was written at 23:41, and no source file is newer than it.
- **Rag suite:** 23 tests before, 46 now. The 23 new tests cover:
  - plan validation and the allowlist;
  - names absent from the question;
  - ambiguous and unknown names;
  - evidence IDs on claims;
  - each verification status;
  - removal and labelling;
  - semantic context never used as graph evidence;
  - injection;
  - the token budget;
  - audit contents;
  - the Haiku client body, response, and retry.

VALIDATION RESULTS:
- Heap, graph plus item index: 3,949 to 4,074 MiB of 6,144.
- Runtime outputs were written only under scratch/runtime/ (one B10 run).
- 20 GiB free disk.

ASSUMPTIONS:
- The OpenAlex injection text is not recorded anywhere in the repository, so the tests use a representative string.
- "Live" covers the model and Titan calls only; the stores stay local.

RISKS:
- ItemFileIndex implements a storage port inside rag. It is marked `shortcut:` for storage-engineer to own.
- The SmokeCli throttle wiring has no unit test, because that path needs AWS.

MANUAL ACTION REQUIRED:
- **storage-engineer:** take ownership of an indexed, read-only item-file reader (the memory finding above).
- **F1 to F3:** still open.
- **Live benchmark:** to be run by the human with the work order 5 command.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order 5 (benchmark harness), then retrieval and groundedness evaluation and a code-reviewer APPROVE.

Not checked: live Bedrock calls (none were made), and graph path equality against the stored path-evidence rows (that check belongs to work order 5).
````

## Work order 5: rag-engineer

````text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09, session 1, work order 5. This covers the benchmark CLI, a fake-mode run on all 30 questions, and the Terminal command for the human's live run. `sbt check` passed twice.

**Summary.**
- The fake run answered all 30 questions. 26 passed every check.
- 4 questions (B13, B15, B17, B24) fail the abstention check. The CSV says they should be answered, but they abstain with UNSUPPORTED_REQUEST because the corpus-level statistics they need are not stored. That is the known follow-up F2 (spark-engineer and storage-engineer).
- The work order 4 carry-over is closed: the path hops for B10, B11 and B30 equal the stored path evidence of run-ee4ffd21aa20dbe4c078a4be96fe8c9f, hop by hop. The direct-pair edges for B07, B08 and B29 also equal the stored hops.

FILES CREATED (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/):
- modules/rag/src/main/scala/scholarchain/rag/cli/Benchmark.scala: the pure parts. CSV reader, stored-path reference, the checks, the per-question result record (`rag-benchmark-result-v1`) and the summary (`rag-benchmark-summary-v1`).
- modules/rag/src/main/scala/scholarchain/rag/cli/BenchmarkCli.scala: the CLI. It loads the stores once, then runs the questions with `--max`, resume by question ID, and stop-on-first-failure. It writes a summary and a run manifest.
- modules/rag/src/test/scala/scholarchain/rag/runtime/BenchmarkSpec.scala: 7 tests.

FILES MODIFIED:
- modules/rag/src/main/scala/scholarchain/rag/cli/AskCli.scala: refactor only, so the two CLIs share one store load. `AskCli.open` loads the stores and returns a `LoadedRuntime` with `ask` and `writeManifest`. The option set and usage text are shared. The AskCli behaviour and its 3 tests are unchanged.
- modules/rag/RUNTIME.md: status line updated, two lines added to section 21, and a new section 22 describing the harness, checks, output layout, resume and failure rules, and both commands.
- `sbt rag/scalafmtAll` reformatted 2 sources (layout only).

FILES DELETED: none.

ALLOWED PLAN OPERATIONS: unchanged from work order 4 (the 12 core QueryOperation values, scopes in `Allowlist`). The `plan-operations` check re-verifies the allowlist on every plan.

PROMPT, SCHEMA, AND MODEL VERSIONS:
- Prompts, schemas and models are unchanged: planner-v1, synthesis-v1, Haiku `us.anthropic.claude-haiku-4-5-20251001-v1:0`, and `amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1`.
- New record schemas: `rag-benchmark-result-v1` and `rag-benchmark-summary-v1`.
- Benchmark run ID: `StableIds.runId("rag-benchmark", "rag-benchmark-v1", configVersion, [runtime run ID, sha256 of the question CSV, sha256 of the path CSV], 1)`. The runtime run ID already includes the mode and the vectors, so fake and live runs never share a directory.

IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR:
- Resolution decisions in the fake run: 26 RESOLVED_DOMINANT, 2 AMBIGUOUS (B09: Lei Zhang and Wei Wang), 1 UNKNOWN_RESEARCHER (B12: Elena Vasquez-Marlowe).
- Outcomes: 23 answered; B09 AMBIGUOUS_RESEARCHER; B12 UNKNOWN_RESEARCHER; B13, B15, B17, B24 and B28 UNSUPPORTED_REQUEST.
- The `resolution` check passes 21 of 21 questions that name a researcher.

VERIFICATION RULES: unchanged from work order 4. The harness adds these mechanical checks, each PASS, FAIL or NOT_APPLICABLE:
- `plan-operations`: every operation is allowlisted, and the plan contains the operation expected for the CSV's `category` column.
- `researcher-names`: the plan's names equal `researcher_a` and `researcher_b`, with none extra and none missing.
- `resolution`: B09 and B12 must match ruling 12; every other named researcher must be RESOLVED_DOMINANT.
- `abstention`: abstained must equal `should_abstain`, and the B09 and B12 reasons must match ruling 12.
- `claim-evidence`: every claim's IDs are in the bundle, and every material claim cites a graph item.
- `verification`: an answer keeps at least one VERIFIED claim, an abstention keeps none, and final claims are only VERIFIED or PARTIALLY_SUPPORTED.
- `bundle-budget`, `llm-budget`, `embedding-budget` (at most 1 Titan call, none without an index queried), and `deadline`.
- `path-hops`: each FOUND path item has the stored researcher sequence and, per hop, the stored joint count and first and last years, and a claim cites it.
- `pair-edges`: each bundle edge whose pair has a stored hop has the stored count and years.

No ratings, relevance labels or quality scores are produced.

BENCHMARK RESULTS (fake mode, all 30 questions, -Xmx6g, mechanical only):
- Runs: runtime run-31d709a11373f510067dcd1657caaca8, benchmark run run-d22ec2f83528a8e95067443cfb114bab.
- Summary file: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/runtime/s3/reports/rag-benchmark/snapshot=2026-10-03/run=run-d22ec2f83528a8e95067443cfb114bab/summary-030.json
- Per-question results are in the `questions/` folder beside it. The manifest is at scratch/runtime/s3/runs/rag-benchmark/snapshot=2026-10-03/run=run-d22ec2f83528a8e95067443cfb114bab/manifest-030.json.

| Check | Pass | Fail | n/a |
|---|---|---|---|
| plan-operations | 30 | 0 | 0 |
| researcher-names | 30 | 0 | 0 |
| resolution | 21 | 0 | 9 |
| abstention | 26 | 4 (B13, B15, B17, B24) | 0 |
| claim-evidence | 23 | 0 | 7 |
| verification | 30 | 0 | 0 |
| bundle-budget | 23 | 0 | 7 |
| llm-budget | 30 | 0 | 0 |
| embedding-budget | 30 | 0 | 0 |
| path-hops | 3 (B10, B11, B30) | 0 | 27 |
| pair-edges | 6 | 0 | 24 |
| deadline | 30 | 0 | 0 |

- **Path detail:**
  - B10 (Buyya to Guerraoui): FOUND, 3 hops, same researchers as the stored path.
  - B11 and B30 (Foster to Duato): FOUND, 3 hops, same researchers as the stored path.
- **Graph retrieval, reported separately:**
  - 1,017 template and key reads in 26 questions, 3,730 rows, 180 truncated reads.
  - 202 graph evidence items, 39,214 tokens; path statuses FOUND 3.
  - Stage 3 to 5 latency: p50 1 ms, p95 221 ms, max 255 ms.
  - B09 alone makes 572 reads, because it loads context for every candidate.
- **Semantic retrieval, reported separately:**
  - 20 embedding calls in 20 questions; 42 hits in 20 questions; 19,192 tokens.
  - Index hits: researcher-profile-v1 18, paper-abstract-v1 5, collaboration-path-v1 3, collaboration-community-v1 2, topic-institution-summary-v1 2.
  - Stage 6 latency: p50 5 ms, p95 336 ms, max 760 ms.
- **Verification:** 109 VERIFIED, 0 partial, 0 unsupported, 0 conflicting; 109 final claims.
- **LLM:** 0 attempts and 0 tokens (fake mode); plan source RULES for all 30.
- **Timing:**
  - Question latency: p50 19 ms, p95 427 ms, max 856 ms; total 2,859 ms.
  - Graph load: 32.7 s, heap 3,768 MiB.
  - Item index: 16.0 s, heap 3,946 MiB of 6,144.
- **Resume on the real stores:** a second identical run reported "30 already done, answering 0 now" and rewrote summary-030 byte-identically.
- **Injection tests:** unchanged from work order 4 and passing in the rag suite.
- **Reference used for path equality:** rag has no Parquet reader (ruling 14), so I used the Phase 7 file scratch/stores/work/neptune-check/snapshot=2026-10-03/run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv (8 hops, built from that run's path-evidence). Before using it, I cross-checked it hop by hop against the `collaboration-path` facts of the same run: 4 pairs, 8 hops, all equal.

LIVE COMMAND (for the human, from Terminal, at the repository root; not run by me):

Step 1, a smoke run of B01 and B02:
```
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -J-Xmx6g "rag/runMain scholarchain.rag.cli.BenchmarkCli --questions experiments/benchmark/benchmark-questions-v1.csv --path-evidence scratch/stores/work/neptune-check/snapshot=2026-10-03/run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv --graph scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --items scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --objects scratch/stores/s3 --vectors scratch/stores-live/s3vectors --out scratch/runtime --live --profile scholarchain-sdk --region us-east-2 --per-minute 8 --max 2"
```

Step 2, the remaining 28 questions: the same command with `--max 30` in place of `--max 2`. It resumes by question ID and skips B01 and B02.
- If a run stops on a failure, rerun the same command and it resumes at the failed question.
- Before step 2, check the step 1 summary: plan sources should read MODEL, not FALLBACK_RULES, and llm outcomes should be OK.

Throttles:
- Haiku: at most 8 per minute (`--per-minute 8`), applied to every attempt, retries included.
- Titan: at most 50 per minute (the config `runtime.embedding.requests-per-minute`), applied to every attempt.

Expected Haiku calls (from the fake run's path through the stages; a live model planner can change plans):
- Nominal: 53 = 30 planner calls + 23 synthesis calls.
- Step 1: 4. Step 2: 49.
- Maximum: 120 (2 calls x 2 attempts x 30 questions).

Expected Titan calls:
- Nominal: 20. Step 1: 2. Step 2: 18.
- Maximum: 30 (1 per question), plus at most one expired-token retry each.

Expected time:
- Step 1: about 2 to 3 minutes (sbt start plus about 50 s of store loading, then 4 Haiku calls spaced 7.5 s).
- Step 2: about 9 to 12 minutes. The Haiku spacing alone needs at least 48 x 7.5 s = 6 minutes.
- Worst case with every retry: about 20 to 25 minutes in total.

Token ceilings per question are 40,000 input and 5,600 output. Those are limits, not a forecast; fake mode records no tokens.

KNOWN FAILURE MODES:
- The 4 abstention FAILs above, until F2 stores the corpus-level statistics.
- In a live run, a question whose Bedrock call never succeeded is not recorded; the run stops so that fallback answers are not stored as results. A question that merely produced invalid model output twice is recorded, with plan source FALLBACK_RULES or abstention BUDGET_EXHAUSTED, and its llm outcomes are visible in the result.
- The category-to-operation map in `plan-operations` is my reading of the CSV's category column. It is a check rule, not a label. The human may want to review it in RUNTIME.md section 22.
- I did not break the 180 truncated reads down by template.

COMMANDS RUN:
- Inspection only: cat, sed, grep, find, wc, df, ls. python3 -I read the benchmark CSV, the Phase 7 reference files, and my own scratch/runtime outputs.
- `sbt -batch rag/compile` and `rag/testOnly` (BenchmarkSpec and AskCliSpec), several times.
- `sbt rag/scalafmtAll rag/Test/scalafmtAll`.
- The fake benchmark command above, twice: the first run answered all 30, the second checked resume. Both had `SCHOLARCHAIN_EMBEDDING_MODEL_ID` set, `-J-Xmx6g`, and `--vectors scratch/stores/s3vectors`, with no live flags.
- `sbt -batch check`, twice.
- No git, no aws, no Bedrock or network calls, and no aws-docs lookups needed.
- One python edit script was blocked by guard-dangerous.sh: a false positive on the text `_.key)`. Per ruling 15 I switched to the Write and Edit tools.

TEST RESULTS (exact observed):
- **sbt check run 1:** exit 0, 23:58:07 to 00:02:33. "Tests: succeeded" lines: 175, 53, 94, 84, 43, 70; 0 failed.
- **sbt check run 2:** exit 0, 00:02:39 to 00:07:02. Same counts (175, 53, 84, 94, 43, 70), 0 failed.
- **Marker:** `.claude/.last-test-run` was written at 00:07:02, and no file under modules/rag or prompts is newer than it.
- **Rag suite:** 46 tests before, 53 now. The 7 new tests in BenchmarkSpec cover:
  - CSV parsing and rejection of bad rows;
  - the reference path search and edge lookup;
  - `--max` and resume in fake mode, with all checks passing, the summary, and the manifest;
  - a differing stored path failing `path-hops` and `pair-edges`;
  - `claim-evidence` failing on semantic-only or unknown IDs;
  - an unreachable model counted as a call failure;
  - usage errors.

VALIDATION RESULTS:
- Heap peaked at 3,946 MiB of 6,144.
- Disk had 20 GiB free before every long run.
- Outputs were written only under scratch/runtime/. Nothing was written under scratch/stores, scratch/spark, scratch/hadoop or experiments/benchmark.
- Nothing under labels/ or scratch/stores-live/ was read or listed. That path appears only as an argument in the live command.

ASSUMPTIONS:
- The expect-benchmark.csv hops stand for the stored path evidence; this rests on the Phase 7 report plus my cross-check.
- For single-name questions, the expected resolution RESOLVED_DOMINANT is inferred from `should_abstain=no`. Ruling 12 names only B09, B12, and the resolved pairs.

RISKS:
- Live plans can differ from the rule planner's, so the call counts and time are estimates.
- ItemFileIndex is still a storage-shaped reader inside rag (from work order 4).

MANUAL ACTION REQUIRED:
- The human runs the live commands above.
- spark-engineer and storage-engineer: F2, which would clear the 4 abstention FAILs.
- storage-engineer: take ownership of the item-file reader (carried from work order 4).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: retrieval and groundedness evaluation, code-reviewer APPROVE

Not checked: no live Bedrock call was made, so the live command, its counts and its timing are unverified until the human runs them.
````

## Work order 6: entity-resolution-engineer

````text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 09, session 1, work order 6. A bounded live adjudication command for about 200 seeded LLM-band pairs, with --max, a throttle of at most 8 per minute on every attempt, resume, output under scratch/ with a run ID, and Phase 7 ruling 35 credentials (refresh, then one retry on an expired token) inside modules/entity-resolution. Fix only what that needs, with tests, and produce the live command without running it.

Summary: the existing adjudication path could not run this demonstration safely, so I fixed the gaps inside the module. The new command is ready for the human to run. Both `sbt check` runs passed. Nothing called AWS. Match policy, thresholds and the Hadoop decision set are unchanged.

GAP CHECK (the existing path, `Runner --adjudicate`, before this change):
- Sample: none. It runs policy over every candidate (26.67M) and adjudicates the first LLM-eligible pairs in file order. That is not a seeded sample, and it writes the full decision set.
- --max: present (`--max-adjudications`) plus a token budget. Kept as is.
- Throttle: none.
- SDK retries: the SDK defaults were on, so hidden retries could bypass any throttle.
- Credentials: the default provider chain, resolved by the SDK. No named profile, no refresh, no expired-token retry.
- Resume: partial. The cache reused results, but the tool refuses an existing run folder and recomputes all 26.67M decisions.
- Output: under a run ID, as required.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/tools/AdjudicateSample.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/AdjudicateSampleSpec.scala (9 tests)

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/BedrockLlmClient.scala
  - The client now takes an injected `converse` function (for tests), `refreshCredentials`, and `beforeRequest` (the throttle hook).
  - Every SDK request runs `beforeRequest` first, including the one retry after an expired token. A request-count counter records each one.
  - `BedrockLlmClient.live(modelId, region, profile: Option[String], timeoutMs, beforeRequest)` builds the client with SDK retries off (`AwsRetryStrategy.doNotRetry()`) and an API-call timeout.
  - New `RefreshingCredentials`: wraps the named profile's ProfileCredentialsProvider; `refresh()` drops it. This reimplements the storage pattern with no module dependency.
  - New `Throttle`: call starts at least ceil(60000/perMinute) ms apart; it refuses more than 8 per minute (ruling 11).
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/tools/Runner.scala
  - `--adjudicate` now builds its client through `BedrockLlmClient.live` with the default credential chain wrapped in RefreshingCredentials, SDK retries off, and the throttle from `runtime.llm.requests-per-minute`.
  - Nothing else changed.

FILES DELETED: none

POLICY VERSION: unchanged. The tool reads `er-v2` from the Hadoop run manifest and requires every sampled decision to carry it.

FEATURES AND RULES: unchanged. The command reuses the existing pieces: `MatchPolicy.pathOf` (LLM_BAND), `Adjudicator`, `AdjudicationPrompt.validate`, `MatchPolicy.withAdjudication`, `Evidence.fields`, and the `JsonlFileCache` cache.
- Sample: `UNRESOLVED` decisions with rule `er-v1:band-llm` and no adjudication, taken from the run's J10 decisions as exported by J09 (`j09-graph-export/match-decisions/entity_type=<T>/outcome=UNRESOLVED`).
- Selection: the n smallest values of `H("er-adjudication-sample", seed, entityType, sorted IDs)`, kept in a bounded TreeMap. It depends only on the seed, never on input order, and keeps one decision per unordered pair.
- Defaults: seed 20261107, 180 RESEARCHER and 20 WORK pairs.

THRESHOLDS: (accept, reject, LLM band) unchanged at 0.93, 0.35, [0.65, 0.93], read from the run's `_manifest.json`.

MODEL AND PROMPT VERSIONS:
- Prompt er-adjudication-v1 and its schema, unchanged.
- Model from `models.adjudication-model`. That key holds a placeholder, which the tool refuses, so the command sets `SCHOLARCHAIN_ADJUDICATION_MODEL_ID=us.anthropic.claude-haiku-4-5-20251001-v1:0`.
- Rate, attempts and timeout come from the existing Haiku keys `runtime.llm.requests-per-minute` (8), `max-attempts-per-call` (2) and `timeout-ms` (30000). `--per-minute 1..8` overrides the rate.

HOW THE COMMAND WORKS:
- Modes: `--plan` prints the run ID, sample counts, token estimate, call count and time, and writes nothing. `--live --profile <p> --max <n> --token-budget <t>` runs one session. Without `--live` and `--profile`, it refuses before reading any input.
- Run ID: `StableIds.runId("er-adjudication", "er-adjudicate-sample-v1", configVersion, [hadoop-run, policy, seed, RESEARCHER=n, WORK=n, prompt, model], 1)`. Running the same command again resumes the same run.
- Files in `scratch/er/adjudication-live/run=<id>/`:
  - `run.json` and `sample.jsonl`, written once. `sample.jsonl` holds each sampled decision with its exact adjudication input, and is written to a temporary file and then moved into place.
  - `cache.jsonl`, append-only. A partial last line left by a crash is cut at the start of a session, and cached pairs cost no call.
  - `session-NNNN/decisions.jsonl`, `failures.jsonl` and `summary.json`, one new folder per session, never overwritten. The summary records status COMPLETE, MAX_REACHED or FAILED, requests, tokens, cached, pending, and outcomes by entity type.
- Stop rule: a pair that still gets a client error after its retry stops the session, and the remaining pairs stay pending. A denied or unavailable model therefore costs at most one pair's attempts. Schema failures and fabricated IDs become ABSTAIN, stay UNRESOLVED, are not cached, and are retried on resume.
- Output decisions are validated by core `Validation`. They add `er-v1:llm-adjudication`, the prompt version, `provenance.modelVersion`, and this run's ID. They are a separate demonstration set: the Hadoop decisions Phase 6 consumed are untouched.

EVALUATION: not run. The work order asks for none, and the gold set was not read. The live sample results will show model behaviour on the LLM band (Phase 5 gate: 31 of 39 sampled band pairs were true matches). Gold evaluation of adjudication needs a human decision.

REPRESENTATIVE ERRORS: none yet, because no live run has happened.

LEAKAGE CHECK: nothing under labels/ was read. AdjudicateSample reads only the Hadoop run (manifest, J09 decisions, J01 and J02 works, venues and institutions) and configuration. Sampling is a hash of IDs and the seed.

COMMANDS RUN:
- `sbt -batch Test/compile`: success.
- `env SCHOLARCHAIN_ADJUDICATION_MODEL_ID=us.anthropic.claude-haiku-4-5-20251001-v1:0 sbt -batch -J-Xmx8g "entityResolution/runMain scholarchain.entityresolution.tools.AdjudicateSample --run scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d --out scratch/er/adjudication-live --plan"`
  - Exit 0, 70.9 s wall, nothing written.
  - Output: `run run-70a26cef3256da28ef1257cb4498d0bb`; RESEARCHER 180 sampled; WORK 20 sampled; 200 not yet cached.
  - Estimated input tokens per call: mean 913, max 1022.
  - Expected requests: 200; upper bound 800; at 8 per minute (7500 ms apart), about 26 min.
- `sbt -batch fmt`: reformatted 2 entity-resolution files.
- `sbt check` twice.
- `df -h`: 20 GiB free.

TEST RESULTS (observed):
- sbt check run 1: exit 0. Per-project totals: 175, 53, 84, 103, 43, 70, all succeeded, 0 failed. markTestRun wrote .claude/.last-test-run.
- sbt check run 2: exit 0, the same counts (175, 53, 103, 84, 43, 70), 0 failed, markTestRun wrote the marker.
- The entityResolution block is the 103: the Phase 5 gate's 94 plus the 9 new AdjudicateSampleSpec tests. They cover:
  - seeded selection: LLM-band only, independent of input order, the seed changes the sample, one decision per pair;
  - `--max` bounds a session, and the resume session makes 1 call with 2 cache hits after a partial cache line is cut;
  - ABSTAIN stays UNRESOLVED and the source decision is unchanged;
  - a client error after its retry stops the session (2 calls, 4 pending);
  - live mode needs `--live`, `--profile` and `--max`;
  - an expired token gives one refresh and one retry, and both requests pass the throttle; the request is built with model ID, system, user, maxTokens and temperature 0;
  - a second expired token or a denial is not retried again;
  - RefreshingCredentials rebuilds its delegate after a refresh;
  - Throttle: 7500 ms at 8 per minute, and 0 or 9 are refused.

VALIDATION RESULTS:
- The plan run on the real Phase 5 run selected the full 200 and built evidence for every pair.
- No network calls: tests inject `converse` and a fake LlmClient.

LIVE COMMAND (for the human, from Terminal, repository root; I did not run it):
```
caffeinate -i env SCHOLARCHAIN_ADJUDICATION_MODEL_ID=us.anthropic.claude-haiku-4-5-20251001-v1:0 sbt -batch -J-Xmx8g "entityResolution/runMain scholarchain.entityresolution.tools.AdjudicateSample --run scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d --out scratch/er/adjudication-live --live --profile scholarchain-sdk --region us-east-2 --max 200 --token-budget 600000 --per-minute 8"
```
- Run ID: run-70a26cef3256da28ef1257cb4498d0bb, folder scratch/er/adjudication-live/run=run-70a26cef3256da28ef1257cb4498d0bb/.
- Expected calls: 200 Haiku requests, one per pair. Hard bounds: at most 200 new adjudications; at most 2 attempts each; at most 1 expired-token retry per attempt (800 in the worst case). The 600,000-token budget stops it earlier, at roughly 450 attempts, since each is estimated at about 1,313 tokens.
- Expected time: about 27 min (200 × 7.5 s, plus about 70 s for sampling and sbt start-up). The worst case under the token budget is about 60 min.
- Expected cost: about 250k input and 25k output tokens, well under $1 at Haiku on-demand prices.
- Preview without calls: add `--plan` instead of `--live ... --per-minute 8`.
- Resume: after Ctrl-C, a crash, or status FAILED or MAX_REACHED, run the same command again.
- Progress: `wc -l scratch/er/adjudication-live/run=run-70a26cef3256da28ef1257cb4498d0bb/cache.jsonl`. Results: the latest `session-NNNN/summary.json`.

ASSUMPTIONS:
- The profile is `scholarchain-sdk` in us-east-2, as named in work order 5. The human should confirm.
- Defaults: 180 RESEARCHER and 20 WORK pairs (about 200 total, in a similar ratio to the gold queue), seed 20261107, output folder scratch/er/adjudication-live.
- The adjudication rate, attempts and timeout reuse the `runtime.llm.*` Haiku keys, because it is the same model and the same account quota. That avoided a core change.

RISKS:
- The Haiku quota (10 per minute) is shared. Do not run this at the same time as the rag live benchmark: 8 + 8 per minute exceeds the quota.
- On-demand Bedrock access for Haiku must be granted. If it is not, the first pair fails twice and the session stops as FAILED, costing 2 requests.
- The token budget is checked against a chars/4 estimate before each call, so one call can overrun it slightly (Phase 5 review minor, unchanged).
- Runner `--adjudicate` behaviour changed: SDK retries are off and it is now throttled. Only the build covers that wiring; it has no test of its own.
- ER tool classes, including the new ones, are still bundled in the hadoop jar but unused. The human accepted this in Phase 5. Hadoop's source scan for adjudication code is unaffected.

MANUAL ACTION REQUIRED:
- The human runs the live command above.
- Optional follow-up for domain-engineer: dedicated `entity-resolution.adjudication.*` configuration keys, if the human wants them separate from `runtime.llm.*`.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: gold-set evaluation, match-policy review, code-reviewer APPROVE. In this phase: Phase 09 code review 1, then the human's live run and a review of its summary.json.

Not checked: the live command has not been run against Bedrock, so the real error code an expired token produces on the Converse API is assumed to match the storage diagnosis (code or message match).
````
