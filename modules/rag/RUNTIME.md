# ScholarChain runtime workflow (spec Phase 9)

Owner: rag-engineer. Records: `modules/rag/src/main/scala/scholarchain/rag/runtime/` (`Contracts.scala`, `Records.scala`; round-trip tests in `RuntimeRecordsSpec`). Prompts and output schemas: `prompts/**`. Store contracts: `modules/storage/STORAGE.md`. Rendering, model versions, semantic-context format: `modules/rag/RAG.md`. Selection: `modules/rag/SELECTION.md`. Name rule and rankings: `modules/spark/JOBS.md`.

Status: work order 1 defined the contract and records; work order 4 implemented stages 1 to 10 (`runtime/*.scala`), the prompts and schemas (`prompts/`), the live Haiku client (`live/BedrockHaikuClient.scala`), and the runtime CLI (`cli/AskCli.scala`). Work order 5 added the benchmark harness (`cli/Benchmark.scala`, `cli/BenchmarkCli.scala`; section 22). Repair-01 part B (rulings 17 to 23 and 27) rewrote verification (section 12), added `synthesis-v2` and `synthesis-output-v2`, the fence rule and failure recording for model output (section 3), request counting (section 15), the deadline and per-stage timing (section 2), and moved key-value reads to the storage `ItemFileReader` (section 17); code version `rag-runtime-v2`, benchmark `rag-benchmark-v2`. Repair-02 (ruling 29) added number words and asserted names to verification (section 12, `verifier-v3`, an input of the runtime run ID). Repair-02 (ruling 30) added `planner-v2` and failure messages that name what each planner rule allows (section 3). Where this file and the code constants disagree, that is a defect for rag-engineer. "Ruling n" means the Phase 9 human rulings in `docs/agent-runs/phase-09/prompt.md`.

## 1. Principles

- **User text is data.** The question becomes a typed `QueryPlan` of allowlisted operations with typed, bound parameters. No stage builds Cypher, PartiQL, a filter expression, or an AWS call from user text. Graph reads go only through the fixed storage templates (section 8). DynamoDB keys are built from IDs and enum values, never from raw text. The one exception is the `name-v1` key, which is a normalization, not a concatenation.
- **Facts come from stores.** Every count, team size, path, component, community, bridge value, and coreness comes from ResearcherMetricCache or the graph store, with its schema version and run ID. Vector similarity only selects descriptive text.
- **Two model calls at most.** Only stage 1 (planner) and stage 8 (synthesis) may call an LLM (Claude Haiku 4.5, ruling 3). Every other stage is deterministic code. Verification is rule-based.
- **Abstain, never guess.** An ambiguous or unknown researcher, an unsupported request, or an answer without a verified claim abstains and says why.
- **No hidden reasoning.** Prompts ask for JSON only. No stage stores or returns model reasoning. Explanations are evidence chains: claims with evidence IDs and verification results.

## 2. Stage graph

```text
QueryRequest
  -> [1 QUERY_PLANNING]          planner model (validated) | rule planner      -> QueryPlan
  -> [2 RESEARCHER_RESOLUTION]   dominant-name-v1 over EntityLinkCache         -> ResearcherResolution*
       any AMBIGUOUS or UNKNOWN_RESEARCHER -> abstain (stages 3 to 8 skipped; 9 and 10 still run)
  -> [3 COLLABORATION_METRICS] [4 PATH_FINDING] [5 COMMUNITY_ANALYSIS]   (deterministic; may run in parallel)
                                                                                -> GraphFacts
  -> [6 SEMANTIC_CONTEXT]        one Titan embedding, filtered vector queries  -> SemanticResult*
  -> [7 EVIDENCE_BUILDING]       bounded, versioned bundle                     -> EvidenceBundle
  -> [8 ANSWER_SYNTHESIS]        synthesis model (validated)                   -> SynthesisOutput
  -> [9 CLAIM_VERIFICATION]      rules against the bundle                      -> VerificationReport
  -> RuntimeAnswer
  -> [10 AUDIT]                  write-once log object + QuerySession + EvidenceBundle items
```

| # | Stage (`Stage`) | Input | Output | LLM | Reads |
|---|---|---|---|---|---|
| 1 | QUERY_PLANNING | `QueryRequest`, configuration | `QueryPlan` (model output parsed as `PlannerOutput` first) | Haiku, at most 2 attempts | none |
| 2 | RESEARCHER_RESOLUTION | `QueryPlan.researcherNames` | `Vector[ResearcherResolution]` | no | EntityLinkCache query; `graph.authored-counts`, `graph.researcher-institutions`, `graph.coauthors` (candidate context) |
| 3 | COLLABORATION_METRICS | plan, resolved IDs | `GraphFacts.metrics`, `.rankings`, `.changes` | no | ResearcherMetricCache `WINDOW#`, `CHANGE#`, `RANKING#`; `graph.vertices` (names, titles) |
| 4 | PATH_FINDING | plan, resolved IDs, `WINDOW#` component IDs | `GraphFacts.paths`, `.coauthors`, `.works` | no | `graph.shortest-path`, `graph.coauthor-edge`, `graph.coauthors`, `graph.supporting-works`, `graph.vertices` |
| 5 | COMMUNITY_ANALYSIS | plan, `WINDOW#` community IDs | `GraphFacts.communities`, `.facets`, cross-community `.coauthors` | no | `graph.vertices` (Community), `graph.community-members`, `graph.community-neighbors`, `graph.cross-community-edges`, `graph.community-facets`, `graph.researcher-facets`, `graph.researcher-institutions`, `graph.topic-researchers`, `graph.lookup` |
| 6 | SEMANTIC_CONTEXT | plan, resolved IDs, graph IDs (work, community, pair) | `Vector[SemanticResult]` | no (Titan embedding only) | `Embedder`, `VectorReadPort.query`, `ObjectReadPort` (text by `textRef`) |
| 7 | EVIDENCE_BUILDING | resolutions, `GraphFacts`, semantic results, budgets | `EvidenceBundle` | no | none |
| 8 | ANSWER_SYNTHESIS | question, plan, bundle | `SynthesisOutput` | Haiku, at most 2 attempts | none |
| 9 | CLAIM_VERIFICATION | `SynthesisOutput`, bundle, resolutions | `VerificationReport`, final core `AnswerResponse` | no | none |
| 10 | AUDIT | everything above | `AuditRecord`, store items | no | `ObjectLoadPort.putNew`, `RuntimeRecordPort.create` |

Each stage records a `StageTiming` (outcome `OK`, `SKIPPED`, `ABSTAINED`, `FAILED`, and latency). Stages 3 to 5 run as one pass over the plan (`GraphStages`); each operation's reads are timed under its stage (`GraphStages.stageOf`: rankings, team size, and temporal comparison are stage 3; coauthors, paths, and components stage 4; communities, bridges, and facets stage 5), the metric reads of resolved researchers under stage 3, and a stage the plan gives nothing to do is `SKIPPED` with 0 ms (review 1 m1). The question deadline (`runtime.question.deadline-ms`) is checked before every stage after planning, before every store read (`CallLog`), and before the query embedding and each vector query; when it has passed, the current stage is `ABSTAINED`, the rest are `SKIPPED`, and the answer abstains with `BUDGET_EXHAUSTED` (review 1 m7). Planning reads no store, and each model attempt checks the deadline itself (`SKIPPED_BUDGET`). Every store read records a `TemplateCall` (template or key ID, latency, result count, truncated), never its parameter values. A stage failure (a store error after the storage retry policy) ends the question with `FAILED` in the audit and a typed error to the caller. It is never turned into an answer.

## 3. Request and QueryPlan

### Request (`QueryRequest`, `rag-query-request-v1`)

This is the transport-free form of the spec `POST /v1/query` body. Phase 10 maps HTTP to it. Fields: `queryId`, optional `sessionId`, `question` (1 to `runtime.question.max-chars` characters after NFC normalization; control characters other than tab and newline are rejected), and the optional groups `constraints`, `analysis`, `retrieval`, and `output`. A `Some` field in `constraints` or `analysis` overrides the model (ruling 4), and its name is recorded in `QueryPlan.overriddenFields`. `retrieval` limits are clamped to the configured maxima (`retrieval.graph-limit`, `retrieval.vector-limit`, `retrieval.max-path-depth`), never raised above them.

### Operation allowlist

The operations are the core `QueryOperation` values. They are the ruling 4 list, plus `VENUE_FILTER`, which core already defines and ruling 6 names. Each `PlannedOperation` has `operation`, `scope` (`RESEARCHER`, `PAIR`, `CORPUS`), `researchers` (indexes into `researcherNames`: one for RESEARCHER, two distinct for PAIR, none for CORPUS), and optional `metric` and `topN`.

| Operation | Scopes | metric | Reads (section 8 IDs) | Evidence types |
|---|---|---|---|---|
| RESEARCHER_RANKING | CORPUS | required; one of the 7 ranked metrics (`MetricAttributes.ranked`); default `analysis.rankingMetric`, else DISTINCT_COAUTHOR_COUNT | `kv.ranking`, `graph.vertices` | RANKING_RECORD |
| MAXIMUM_TEAM_SIZE | RESEARCHER, CORPUS | none | RESEARCHER: `kv.metrics.window` (M_r, `maximumTeamWorkIds`), `graph.vertices` (titles). CORPUS: `kv.ranking` of `maximumCoauthorsOnOnePaper` | METRIC_RECORD or RANKING_RECORD, WORK_RECORD |
| DIRECT_COAUTHORS | RESEARCHER (strongest coauthors), PAIR (their edge) | none | `graph.coauthors` or `graph.coauthor-edge`, `graph.supporting-works`, `graph.vertices` | COAUTHOR_EDGE, WORK_RECORD |
| SHORTEST_PATH | PAIR | none | `kv.metrics.window` (component IDs: different components give UNREACHABLE without a search), `graph.shortest-path`, `graph.supporting-works`, `graph.vertices` | GRAPH_PATH, COAUTHOR_EDGE per hop, WORK_RECORD |
| COMPONENT_LOOKUP | RESEARCHER, PAIR, CORPUS (CORPUS: `NOT_STORED`) | none | `kv.metrics.window` (`componentId`, `componentSize`); "in the largest component": `kv.metrics.change` (`fromInLargestComponent`/`toInLargestComponent`) for the two compared windows only | COMPONENT_RECORD, TEMPORAL_CHANGE |
| COMMUNITY_COMPARISON | RESEARCHER, PAIR, CORPUS (CORPUS: `NOT_STORED`) | none | `kv.metrics.window` (`communityId`), `graph.vertices` (Community), `graph.community-members`, `graph.community-neighbors`, `graph.community-facets` | COMMUNITY_RECORD, FACET_RECORD |
| BRIDGE_RANKING | CORPUS, RESEARCHER | optional; one of the 3 bridge measures; none = all three, each named | CORPUS: `kv.ranking`. RESEARCHER: `kv.metrics.window`, `graph.cross-community-edges` | RANKING_RECORD or METRIC_RECORD, COAUTHOR_EDGE |
| TEMPORAL_COMPARISON | RESEARCHER, CORPUS (CORPUS: `NOT_STORED`) | optional; the compared measures (default all `CHANGE#` deltas) | `kv.metrics.change` | TEMPORAL_CHANGE |
| TOPIC_FILTER | RESEARCHER (the researcher's topics), CORPUS (researchers with most works on the topics); also a filter on a ranking | none | `graph.lookup` (Topic), `graph.researcher-facets` (TOPIC), `graph.topic-researchers` | FACET_RECORD |
| VENUE_FILTER | RESEARCHER; filter on a ranking | none | `graph.lookup` (Venue), `graph.researcher-facets` (VENUE) | FACET_RECORD |
| INSTITUTION_FILTER | RESEARCHER (affiliations); filter on a ranking | none | `graph.lookup` (Institution), `graph.researcher-institutions` | FACET_RECORD |
| SEMANTIC_EXPLANATION | RESEARCHER, PAIR, CORPUS | none | stage 6 only | PROFILE, TEXT_CHUNK (semantic) |

Metric lookups for named researchers are not an operation. Stage 3 always reads the `WINDOW#` item of every resolved researcher in the plan window. `QueryPlan.metrics` names the measures the question asks for, for example DISTINCT_COAUTHOR_COUNT for "How many distinct coauthors does X have?". A plan with no operation is valid only when it has at least one metric and one researcher name.

Not supported in this version, each stated in the answer as `MissingEvidence`: corpus-level component, community, and window statistics (B13, B15, B17, B24; follow-up F2), "in the largest component" for `all-years` (F2), a ranking beyond the stored top 50 (`NOT_IN_STORED_TOP_N`), a ranking of a metric with no stored ranking (`NO_STORED_RANKING`: total coauthor occurrences, average coauthors per paper, weighted degree, component size), a year-specific affiliation (`NOT_WINDOWED`), a comparison other than a stored `CHANGE#` pair (`NO_STORED_COMPARISON`), and any composite score.

### QueryPlan (`rag-query-plan-v1`)

Fields: `queryId`, `operations`, `researcherNames` (`NameMention`: name and its code-point offset in the normalized question), `metrics`, `window` (`WindowSelection`, section 4), `comparison` (optional `WindowComparison`), `topics`, `venues`, `institutions`, `semanticIndexes` (chosen by code, section 9), `limits` (`PlanLimits`: graphLimit, vectorLimit, maxPathDepth, topK), `source` (`MODEL`, `RULES`, `FALLBACK_RULES`), `overriddenFields`, `rejectedItems` (what validation dropped and why), `promptVersion`, `modelId`, and `provenance` (`RUNTIME`, sourceRecordId = queryId, the runtime run).

Core also has a `QueryPlan` and an `EvidenceBundle` (`query-plan-v1`, `evidence-bundle-v1`). Ruling 1 places the runtime versions in rag, so the runtime does not use the core ones. A wildcard `import scholarchain.core.*` puts the core names ahead of the rag ones, so runtime code imports core names explicitly.

### Planner model output (`PlannerOutput`, `planner-output-v1`)

The model returns one JSON object: `schemaVersion`, `operations`, `researcherNames`, `metrics`, `window` (`WindowRequest`: `kind` DEFAULT, NAMED, or RANGE, plus `name`, `yearFrom`, `yearTo`), `comparison`, `topics`, `venues`, `institutions`. It chooses only enum values and copies names. It never chooses indexes, limits, IDs, or store keys.

### Validation (every model output, before use)

The output must parse as JSON, carry exactly the schema's keys with no extra key at any level (upickle ignores unknown keys, so the check is explicit), and pass:

1. `schemaVersion` = `planner-output-v1`.
2. At most `runtime.planner.max-operations` operations, no duplicate (operation, scope, researchers), and each scope allowed for its operation (table above).
3. `researchers` indexes match the scope and are below `researcherNames.size`; a PAIR has two distinct indexes.
4. `metric` is allowed for the operation (table above); `topN` is between 1 and `runtime.rankings.max-top-n`.
5. `researcherNames`: at most `runtime.planner.max-researcher-names`, distinct, non-blank, at most `runtime.planner.max-term-chars` characters, and each occurs in the question (ruling 4). Matching is on NFC text with whitespace runs collapsed, case-sensitive, so "Ian T. Foster's" contains "Ian T. Foster". The first occurrence's offset is kept.
6. `topics`, `venues`, `institutions`: at most `runtime.planner.max-filter-terms` each, the same length limit, and each occurs in the question.
7. `window`: NAMED has a stored window name (`all` is an alias of `all-years`) and no year; RANGE has no name, at least one year, and `yearFrom <= yearTo` where both are set; DEFAULT has no other field. A name with years is rejected even when they agree, so a window is never chosen silently between two conflicting fields.
8. `comparison`: two stored windows that do not overlap, earlier first, with a stored `CHANGE#<from>><to>` pair.

Work order 4: the CORPUS scopes of COMPONENT_LOOKUP, COMMUNITY_COMPARISON, and TEMPORAL_COMPARISON are allowed so that corpus-level questions (B13, B15, B17, B24) become a plan whose only result is a stated `NOT_STORED` and an `UNSUPPORTED_REQUEST` abstention, not an `INVALID_PLAN`. Model output fails as a whole on any rule (one retry, then the rule planner); rule-planner output that fails a rule abstains with `INVALID_PLAN`.

A failed check is recorded in `rejectedItems` (rule and field, never the offending text beyond a name already in the question). A whole-output failure triggers the one retry (ruling 3). The retry prompt repeats the original prompt plus the failed rules with their schema paths (at most 10, for example `- schema: $.claims[0].values: too few keys`); it never echoes the model's previous text, and `JsonSchema` never names a key the model chose (`$: unexpected key`, values keys as `$.claims[0].values.*`).

**Planner failure messages (ruling 30).** Every rule failure reads `<rule> <field>: <what is wrong>; allowed: <what is allowed>`, in fixed text built from enum values, the allowlist tables, and configuration (window names, limits), never from the model's text. The same string goes to `LlmCall.failure` and to the retry prompt. `rejectedItems` and the `INVALID_PLAN` abstention keep only the part before the first `:` (for example `rule-7 window`). Examples:
- `rule-2 RESEARCHER_RANKING: scope RESEARCHER not allowed; allowed: scope CORPUS` (the scopes come from `Allowlist.scopeText`; rule-4 metric failures use `Allowlist.metricText`);
- `rule-7 window: NAMED with a year; allowed: DEFAULT (name, yearFrom, yearTo null), NAMED (name one of all-years, 2000-2014, 2015-2026; yearFrom, yearTo null), or RANGE (name null; yearFrom and yearTo years, at most one null; yearFrom <= yearTo)`. What is wrong is one or more of: `a name`, `a year`, `no stored window name`, `no year`, `yearFrom after yearTo` (`Windows.requestProblems`). A name that is not stored is never repeated;
- `rule-8 comparison: not a stored window pair; allowed: null or fromWindow -> toWindow one of 2000-2014 -> 2015-2026`.

**planner-v2 (ruling 30).** The human's live step 1 (2026-11-11) had 3 of 4 planner-v1 attempts fail: `RESEARCHER_RANKING` with scope RESEARCHER for "how many distinct coauthors does X have", and rule 7 on a question naming `2015-2026`. The validator agrees with this file on both. planner-v1 did not exclude what the validator rejects: it never said that a named researcher's own measure is not an operation, and it never said which window fields must be null (its `{{windows}}` text, `2015-2026 (2015-2026)`, puts a name next to years). `prompts/planner/planner-v2.md` changes only rules 2, 7, and 8 and the retry heading. Rule 2 lists every operation's scopes and metrics from `{{operation_rules}}`, rendered by `Allowlist.ruleText` from the same tables the validator checks, and says that a researcher's own measure goes in `metrics`. Rule 7 gives the three exact window objects, with the stored names from `{{window_names}}`. Rule 8 gives the comparison object. The output schema text is unchanged, so planner-v2 uses `planner-output-v1`. The allowed operations and every validation rule are unchanged.

**Output text (ruling 27).** Whitespace and exactly one Markdown code fence around the whole output (```` ```json ```` or ```` ``` ````, then a newline, the JSON, and the closing fence) are removed before parsing (`LlmOutput.unfence`, the same rule as modules/entity-resolution, with no dependency on it). Prose before or after the JSON, prose after a fence, two objects, and an unclosed fence stay rejected (`output-is-json`). Each rejected or failed attempt records `LlmCall.failure`: the failed rule names with schema paths (at most 5 shown, then "+n more") and the output's shape (length, first and last character class, stop reason), or the exception class of a failed call; never prompt, retrieved, or model text (ruling 23). A second failure goes to the rule planner (`FALLBACK_RULES`). When the rule planner finds no operation and no metric, the answer abstains with `INVALID_PLAN`.

### Request overrides (after validation)

`constraints.yearFrom`/`yearTo` replace the window (RANGE). `constraints.topics`/`venues`/`institutions` replace the model's lists and need not occur in the question; they are still length-checked and are only ever bound parameters. `analysis.rankingMetric` sets the RESEARCHER_RANKING metric, or adds a ranking operation when there is none. `includeTeamSize` adds MAXIMUM_TEAM_SIZE, `includeBridgeMetrics` adds BRIDGE_RANKING, `includeCommunities` adds COMMUNITY_COMPARISON, and `includePaths` adds SHORTEST_PATH when the plan has a PAIR, each only where a valid scope exists. `false` removes the operation. Request years with `yearFrom` after `yearTo` abstain with `INVALID_PLAN` ("The requested years are reversed: ..."), not `NO_DATA_IN_WINDOW` (review 1 m8).

### Rule planner (`RULES`)

The rule planner is deterministic and used in fake mode, in tests, and as the fallback. Names are maximal runs of capitalized tokens, including initials ("T."), accented letters, hyphens, apostrophes inside names, and a trailing DBLP homonym number (`0` and three digits); a possessive "'s" or trailing punctuation ends a run. Leading question and function words (`RulePlanner.QuestionWords`) are dropped, and a name needs at least two tokens. Topic terms come from "papers/works/publications on <term> [topics]"; "top <n>" sets topN. Years and stored window names come from regular expressions. Operations come from keyword rules (for example "shortest" and "path" give SHORTEST_PATH; "coauthored" with two names gives DIRECT_COAUTHORS PAIR; "participation coefficient" gives the metric). The rules are frozen in `RulePlanner` and `PlannerSpec`.

## 4. Windows (ruling 6)

Stored windows: the core `graph.windows` (`all-years` 2000-2026, `2000-2014`, `2015-2026`). `WindowSelection` maps a `WindowRequest` to one stored window:

- DEFAULT: `all-years`, exact.
- NAMED: that window, exact.
- RANGE: an open side takes the corpus bound (the `all-years` years). The range is then clipped to the corpus bound. An empty intersection abstains with `NO_DATA_IN_WINDOW`. If any range was clipped, the note says so. The selected window is the smallest stored window (by year span; ties in configuration order) that contains the clipped range. If it equals the range, `exact` is true. Otherwise `exact` is false and `note` reads "Requested <a>-<b>; the stored window <w> (<s>-<e>) is the smallest that contains it, so values cover <s>-<e>." The note becomes an uncertainty line in the answer, so the choice is never silent.
- Comparisons use only stored `CHANGE#` pairs: every ordered pair of non-overlapping stored windows that has items. With D-10 that is `2000-2014 -> 2015-2026`. The benchmark value `both` means that pair.
- Affiliations are not windowed. INSTITUTION_FILTER with an explicit year or a range other than the full corpus gives `MissingEvidence(NOT_WINDOWED)`. When that is the only operation, the answer abstains with `UNSUPPORTED_REQUEST` (B28).

## 5. Researcher resolution (ruling 5; closes Phase 7 M9)

Policy `dominant-name-v1` (`modules/spark/JOBS.md`), with share `s` = `runtime.resolver.dominant-share` (0.9) and minimum works `m` = `runtime.resolver.dominant-min-works` (5):

1. **Key (the shared normalization).** `nameKey = DynamoContract.nameKey(name)` (`name-v1`, the EntityLinkCache key). If no key is left (only punctuation or digits), the decision is UNKNOWN_RESEARCHER.
2. **Lookup.** EntityLinkCache `query(nameKey, limit = runtime.resolver.max-candidates)`. When the limit is reached, `truncated` is true and the decision cannot be RESOLVED_DOMINANT (AMBIGUOUS). A cut list with no exact display-name match is AMBIGUOUS too, with the note that the candidate list was cut at its limit; it never yields "not in the graph" (review 1 m2).
3. **Candidates.** These are the items whose `displayName` equals the given name trimmed of leading and trailing U+0020 (Spark `trim`), compared exactly, case and accents included. These are exactly the canonical researchers that dominant-name-v1 matches, because every researcher has an item under the key of its display name. Items under the same key with a different display name (alternate names, other case or accents, other homonym numbers) are `otherNameMatches`. They are listed in every outcome so the user can pick, but never counted.
4. **Works.** Each candidate's AUTHORED row count (any source), as in ruling 30. The source is the resolution port (below).
5. **Decision.** No candidate and a complete list: UNKNOWN_RESEARCHER. Rank by works descending, then researcherId. The top candidate is RESOLVED_DOMINANT when `works / totalWorks >= s` and `works >= m` (and `truncated` is false). Otherwise the decision is AMBIGUOUS, which includes a single candidate with fewer than `m` works and any tie.
6. **Output (`ResearcherResolution`, `researcher-resolution-v1`).** The output carries the name, key, policy, `s`, `m`, the decision, the researcher ID, `topShare`, `totalWorks`, and `candidateCount`. Each candidate (`NameCandidate`) has its source identifiers (researcherId, ORCID, dblpAnchored, mentionCount), `identityConfidence`, works and share, affiliations (`graph.researcher-institutions`, top `runtime.resolver.candidate-institutions`), and coauthor context (`graph.coauthors` in `all-years`, top `runtime.resolver.candidate-coauthors`). Context is fetched in ID batches of `graph-read.id-batch-size`.
7. **Use.** RESOLVED_DOMINANT gives an IDENTITY_RESOLUTION evidence item. Every answer that uses it carries the uncertainty line "<name> was linked to <id> by dominant-name-v1: <share> of <totalWorks> works among <n> researchers with this display name." AMBIGUOUS and UNKNOWN_RESEARCHER never resolve: the whole answer abstains (`AMBIGUOUS_RESEARCHER` or `UNKNOWN_RESEARCHER`), lists every candidate and other name match, and asks which researcher is meant. No synthesis call is made.

**Resolution port.** `ResearcherResolutionPort.lookup(nameKey, limit)` returns the EntityLinkCache items with each researcher's works count. Live: EntityLinkCache query plus `graph.authored-counts` (work order 3). Fake: built read-only from the Phase 7 EntityLinkCache item files and the Neptune `authored` edge files of conversion run `run-1d6a86491812ff94887270c1ab885e69` (works = AUTHORED rows per researcher). Follow-up F1 removes the graph count.

**Reproduction check (work order 4 test, against the Phase 7 files, read-only).** Counted in work order 1 with `grep` on the EntityLinkCache files of run `run-1d6a86491812ff94887270c1ab885e69`:

| Name | Items under the name-v1 key | Exact display-name candidates | Work order 6b candidates |
|---|---|---|---|
| Jack J. Dongarra | 17 | 17 | 17 |
| Yves Robert | 6 | 6 | 6 |
| Rajkumar Buyya | 11 | 11 | 11 |
| Albert Y. Zomaya | 4 | 4 | 4 |
| Rachid Guerraoui | 11 | 11 | 11 |
| Ian T. Foster | 10 | 10 | 10 |
| José Duato | 4 | 3 | 3 |
| Lei Zhang | 134 | 101 | 101 |
| Wei Wang | 420 | 307 | 307 |
| Elena Vasquez-Marlowe | 0 | 0 | 0 |

The candidate sets match. The shares and decisions still depend on the works counts, which work order 4 must reproduce (top shares 0.9576, 0.9775, 0.9645, 0.9902, 0.9615, 0.9680, 0.9871, 0.0273, 0.0128). The key alone would add "Jose Duato" and the "Lei Zhang 00nn" and "Wei Wang 00nn" items and change the counts, which is why step 3 exists. The other benchmark names, not part of work order 6b, have 6 exact candidates (Dhabaleswar K. Panda 0001), 17 (Franck Cappello), and 8 (Minyi Guo, plus 1 "MINYI GUO" other match). `max-candidates` must stay at or above 420.

## 6. Metrics, rankings, filters (ruling 6)

- **Metrics.** Get `WINDOW#<w>` for each resolved researcher. A missing item means the researcher has no DBLP publication in the window: `MissingEvidence(NO_METRIC_ROW)`, stated as such, never as zero. `metricVersion` = `<schemaVersion>@<runId>`. The community and bridge fields also carry `communityRunId`.
- **Names are distinct and stated.** Breadth is `distinctCoauthorCount` (C_r). Intensity is `weightedDegree` (W_r, with T_r). Team size is `maximumCoauthorsOnOnePaper` (M_r coauthors, so M_r + 1 authors on the paper) and `averageCoauthorsPerPaper`. Connectivity is `componentId` and `componentSize`. Bridge importance is `crossCommunityDegree`, `communitiesConnected`, and `participationCoefficient` (D-10; no betweenness). Coreness is `coreness`. Synthesis must name the measure it reports, and the verifier checks the asserted key.
- **Rankings.** Get `RANKING#<attribute>`/`TOP#<w>` only. `topN` defaults to `runtime.rankings.default-top-n`. A `topN` above the stored size (`analytics.ranking-size`, 50) returns the stored entries plus `NOT_IN_STORED_TOP_N`. A metric outside the 7 gives `NO_STORED_RANKING`. When the value at the last returned rank equals the last stored value, the evidence records `tiedAtCut = true`, and the answer says equal values may continue past the stored list (for example the 286-coauthor team, where all 50 stored entries tie).
- **Filters on a ranking.** These apply only to the stored entries: a topic or venue filter keeps the entries whose researcher has at least one AUTHORED work with a matching topic or venue in the window (`graph.researcher-facets`), and an institution filter keeps those with a matching AFFILIATED_WITH institution (not windowed). User terms become IDs through `graph.lookup` (case-insensitive substring of the stored name, bound parameter). A term that matches no ID gives `UNSUPPORTED_FILTER` and the filter is not applied silently. The answer states "filtered from the stored top <k>", never "the top N in <topic>". "Researchers with the most works on topic T" is a separate graph aggregate (TOPIC_FILTER, CORPUS, `graph.topic-researchers`), not a filtered ranking. There is no composite score.

## 7. Paths and communities

- **Path (PAIR).** First compare the two `WINDOW#` component IDs. If they differ, the status is UNREACHABLE with COMPONENT_RECORD evidence for both ends and no search. If either item is missing, the status is UNKNOWN_RESEARCHER. Otherwise run `graph.shortest-path` with `maxDepth = min(request, retrieval.max-path-depth)`. Statuses: FOUND, BEYOND_DEPTH, and SEARCH_LIMIT (the frontier or time limit was hit first; stated as "no path established within the search limits"). The tie-break must equal Spark job 5 (smallest predecessor at each step back), so a FOUND path equals the stored path evidence for the same pair and window (benchmark check, ruling 12). Every hop carries its edge ID, joint publication count, first and last year, and its complete supporting work IDs (inline, or read from the evidence object), with provenance.
- **Community.** `communityId` comes from `WINDOW#`; size, internal edges, cross-community edges, and label come from the Community vertex. Members: `graph.community-members` (bounded, `membersTruncated` set when the community is larger; the size is always the stored size). Neighbors: `graph.community-neighbors`. Topics and venues: `graph.community-facets`. Bridge explanation for a researcher: the three bridge measures from `WINDOW#` plus `graph.cross-community-edges`. The synthesis prompt requires stating that high C_r (direct degree) and high bridge measures differ.

## 8. Read operations (work order 3 provides; storage-engineer owns templates and limits)

Key-value and object reads that already exist (`KeyValueReadPort`, `ObjectReadPort`, `VectorReadPort`):

| ID | Read |
|---|---|
| `kv.metrics.window` | get ResearcherMetricCache (researcherId, `WINDOW#<w>`) |
| `kv.metrics.change` | get ResearcherMetricCache (researcherId, `CHANGE#<from>><to>`) |
| `kv.ranking` | get ResearcherMetricCache (`RANKING#<attribute>`, `TOP#<w>`) |
| `kv.entity-link` | query EntityLinkCache (nameKey, limit) |
| `kv.artifact-active` | get ArtifactRegistry (`neptune/graph` and the other artifact keys, `ACTIVE`): the serving run IDs (live); the fake takes them from the store paths |
| `obj.coauthor-evidence` | get `curated/coauthor-evidence/...` (edges with more than 20 works) |
| `obj.text` | get `semantic-context/text/...` by `textRef` |
| `vec.query` | `VectorReadPort.query(index, vector, topK, filter)` |

Graph templates required from work order 3 (ruling 7). Every parameter is bound, never concatenated. Every template has depth, result-count, and timeout limits from configuration (section 13), returns a `truncated` flag when a limit cut the result, and carries `runId`, `snapshot`, and `schemaVersion` (edges also `policyVersion`) on every element. Neptune openCypher has no `shortestPath()` ([openCypher compliance](https://docs.aws.amazon.com/neptune/latest/userguide/feature-opencypher-compliance.html), accessed 2026-11-06), so the path is a bounded breadth-first search driven by the client over a parameterized frontier template.

| ID | Parameters (bounds) | Returns |
|---|---|---|
| `graph.vertices` | ids (at most `graph-read.id-batch-size`) | id, label, properties (Researcher displayName; Work title, year, venue; Community window, size, internal and cross edges, label; Topic, Venue, Institution name), provenance |
| `graph.coauthors` (exists) | researcherId, window, limit (1 to `retrieval.graph-limit`) | COAUTHORED_WITH edges, strongest first (joint publications descending, then edge ID), with the coauthor's displayName and `truncated` |
| `graph.coauthor-edge` (exists) | researcherX, researcherY, window | the edge or none |
| `graph.supporting-works` | edgeId | complete sorted work IDs (inline list or evidence object; count equals `jointPublicationCount`) |
| `graph.frontier` | ids (at most `graph-read.id-batch-size`), window | for each id, its COAUTHORED_WITH neighbors in the window (the BFS step) |
| `graph.shortest-path` | sourceId, targetId, window, maxDepth (1 to `retrieval.max-path-depth`) | status (FOUND, BEYOND_DEPTH, UNREACHABLE, UNKNOWN_RESEARCHER, SEARCH_LIMIT); for FOUND, researcherIds and hops (from, to, edgeId, jointPublicationCount, first and last year, supportingWorkIds or the ref, provenance). Limits: `graph-read.path-max-frontier` nodes visited, `graph-read.path-timeout-ms` total. Tie-break: Spark job 5. |
| `graph.community-members` | communityId, limit (1 to `graph-read.community-members-limit`) | member researcher IDs and displayNames, ordered by within-community degree in the community's window descending, then ID (or by ID, if storage documents that the degree order exceeds the timeout), plus `truncated` |
| `graph.community-neighbors` | communityId, limit (1 to `graph-read.community-neighbors-limit`) | linked community IDs with the number of cross-community COAUTHORED_WITH edges, descending, then ID |
| `graph.cross-community-edges` | researcherId, window, limit (1 to `retrieval.graph-limit`) | the researcher's coauthor edges whose other end is in a different community in that window, with both community IDs |
| `graph.community-facets` | communityId, window, facet (TOPIC or VENUE), limit (1 to `graph-read.facet-limit`) | facet ID, name, and the count of distinct works by members in the window; aggregates at most `graph-read.community-facet-member-cap` members, else `truncated` |
| `graph.researcher-facets` | researcherIds (batch), window, facet (TOPIC or VENUE), perResearcherLimit (1 to `graph-read.facet-limit`) | per researcher: facet ID, name, and the count of AUTHORED works with year in the window (basis: AUTHORED, any source; stated) |
| `graph.researcher-institutions` | researcherIds (batch), perResearcherLimit (1 to `graph-read.facet-limit`) | per researcher: institution ID, name, and `via` (not windowed) |
| `graph.topic-researchers` | topicIds (at most `graph-read.max-lookup-ids`), window, limit (1 to `retrieval.graph-limit`) | researcher IDs, displayNames, and the count of their AUTHORED works in the window having any of the topics, descending, then ID |
| `graph.lookup` | label (Topic, Venue, or Institution), text (1 to `runtime.planner.max-term-chars` characters), limit (1 to `graph-read.lookup-limit`) | IDs and names whose name contains the text, case-insensitive, ordered by name, then ID |
| `graph.authored-counts` | researcherIds (batch) | AUTHORED edge count per researcher (resolution works; M9 interim) |
| `graph.neighbors` | nodeId, direction (in, out, both), depth (1 to `graph-read.neighbors-max-depth`), limit (1 to `graph-read.neighbors-max-limit`), edgeTypes (a subset of COAUTHORED_WITH, AUTHORED, HAS_TOPIC, AFFILIATED_WITH, PUBLISHED_IN) | the Phase 10 neighbors endpoint: nodes and edges with provenance, `truncated` |

Departures in the work order 3 implementation (STORAGE.md "Graph read templates" is authoritative): `graph.community-facets` takes no window (it uses the community's own window); `graph.neighbors` accepts all six loaded edge types, including MEMBER_OF; `graph.expand` is an added template behind `neighbors`; `graph.coauthors` returns `Bounded[CoauthorLink]`; the fake is `InMemoryGraph.reader(GraphReadLimits.from(cfg))`. `graph.vertex`, `graph.edge`, and `graph.frontier` are also used.

Other storage items for work order 3:
- A file-backed `RuntimeRecordPort` (create-only, one JSON Lines log per table, replayed on open, like `FileVectorStore`) for QuerySession and EvidenceBundle under `scratch/runtime/`.
- A public function that loads DynamoDB item files read-only into `InMemoryDynamo`, unless the `LoadDynamoCli` path is already reusable.
- `DynamoContract.itemSchemaVersion` names for the runtime tables: QuerySession `rag-query-session-v1`; EvidenceBundle `rag-evidence-bundle-item-v1` (header `BUNDLE` and `EV#` items).
- The fakes of every graph template run on `InMemoryGraph` with the same limits. The full Phase 7 graph loads in `-Xmx6g` (Phase 7 ruling 27). Tests use small fixtures.

## 9. Semantic context (stage 6)

- **Embedding.** The question (NFC, at most `runtime.question.max-chars`) is embedded once per question with the configured model and reused for every index. That is at most 1 Titan call per question, inside ruling 3's 1 per index. Fake: the storage `HashingEmbedder`, with filters on `fake:<version>`.
- **Indexes chosen by code** (at most `runtime.semantic.max-indexes`, `topK` = `min(request vectorLimit, runtime.semantic.top-k)`). Every query filters `embeddingModelVersion` = the configured version (RAG.md) and `window` = the plan window (`all-years` for paths and papers):

| Plan content | Index | Extra filter |
|---|---|---|
| resolved researcher | `researcher-profile-v1` | `entityIds $eq <researcherId>` |
| PAIR with a path | `collaboration-path-v1` | `$and` of `entityIds $eq` both IDs |
| community operation | `collaboration-community-v1` | `communityId $eq <communityId>` |
| topics, or CORPUS ranking or SEMANTIC_EXPLANATION | `topic-institution-summary-v1` | none (similarity) |
| supporting or maximum-team works | `paper-abstract-v1` | `entityIds $in <workIds>` (at most 100 constraints) |

- **Output.** `SemanticContext.evidence` (RAG.md) gives PROFILE or TEXT_CHUNK `EvidenceItem`s. A hit whose text is missing or fails its hash is dropped (`droppedCount`). No semantic item is ever used to choose or confirm a graph fact. A filtered hit for a researcher is still descriptive only.

## 10. Evidence bundle (stage 7; ruling 8)

`EvidenceBundle` (`rag-evidence-bundle-v1`): `queryId`, `windows`, `graphEvidence` (every non-semantic type), `semanticEvidence` (PROFILE, TEXT_CHUNK only), `missing`, `budget` (`BundleBudget`), `graphRunId`, and `provenance`. Items are core `EvidenceItem` (`evidence-v1`). Deterministic values go in `facts`, a string map, in canonical text: integers plain; decimals as `BigDecimal.stripTrailingZeros.toPlainString` (the DynamoDB canonical form, so `4.0` becomes `4`); booleans `true` and `false`; ID lists space-joined (IDs never contain spaces); named lists as indexed keys (`entry.1.id`, `entry.1.name`, `entry.1.count`).

### Evidence IDs (`StableIds.evidenceId(type, subjectIds, runId)`)

The same fact read from the same run always gets the same ID, and different facts never share one, because a cut or filtered view puts its parameters into `subjectIds`.

| Type | subjectIds | runId | Facts (beyond `window`, years) |
|---|---|---|---|
| IDENTITY_RESOLUTION (new, C1) | nameKey, name, policyVersion | EntityLinkCache item run | decision, researcherId, displayName, works, totalWorks, topShare, candidateCount, dominantShare, minWorks |
| METRIC_RECORD | researcherId, `WINDOW#<w>` | item `runId` | every `WINDOW#` metric attribute, componentId, componentSize, communityId, communitySize, graphDegree, maximumTeamWorkIds, metricVersion, communityRunId; derived `maximumTeamSize` = M_r + 1 |
| RANKING_RECORD (new, C1) | `RANKING#<attribute>`, `TOP#<w>`, `n=<topN>`, `filter=<sorted IDs or ->` | item `runId` | metric, storedSize, returnedSize, filteredBy, `rank.<k>.researcherId`, `rank.<k>.displayName`, `rank.<k>.value`, lastStoredValue, tiedAtCut; derived `rank.<k>.maximumTeamSize` for M_r |
| TEMPORAL_CHANGE (new, C1) | researcherId, `CHANGE#<from>><to>` | item `runId` | every `CHANGE#` attribute |
| COMPONENT_RECORD | researcherId, `COMPONENT#<w>` | `WINDOW#` item `runId` | componentId, componentSize (inLargestComponent only from `CHANGE#`, for its two windows) |
| COAUTHOR_EDGE | edgeId | edge `runId` | researcherA, researcherB, jointPublicationCount, firstCollaborationYear, lastCollaborationYear, supportingWorkIds (complete) |
| GRAPH_PATH | window, `depth=<d>`, researcherIds... (not FOUND: window, depth, source, target, status) | graphRunId | status, pathLength, maxDepth, researcherIds, `hop.<k>.from`, `.to`, `.edgeId`, `.jointPublicationCount`, `.firstYear`, `.lastYear` |
| COMMUNITY_RECORD | communityId, `members=<n>`, `neighbors=<n>` | Community vertex `runId` | communitySize, internalEdges, crossCommunityEdges, communityLabel, `member.<k>.id`/`.name`, `neighbor.<k>.id`/`.count`, membersTruncated |
| FACET_RECORD (new, C1) | subjectId, facet, window or `-`, `n=<limit>` | graphRunId | basis, truncated, `entry.<k>.id`/`.name`/`.count` |
| WORK_RECORD | workId | Work vertex `runId` | title, year, venueId |
| PROFILE, TEXT_CHUNK | vectorKey | render run | RAG.md semantic-context-v1 |

`sourceUri` is a store pointer with no bucket or endpoint: `dynamodb:<Table>/<pk>/<sk>`, `neptune:<element id>`, `runtime:<templateId>` (for a computed path or aggregate), or the S3 key (`textRef`, evidence object). `graphRunId` is the store-conversion run whose graph is served (fake: from the store path, `run-1d6a86491812ff94887270c1ab885e69`; live: the ArtifactRegistry `neptune/graph` ACTIVE run).

### Budget and order

Tokens are estimated at `runtime.budget.chars-per-token` characters per token (RAG.md heuristic) over each item's prompt rendering. Graph evidence fills `runtime.budget.graph-evidence-tokens` first, in this order: identity, metrics, rankings, changes, paths and their hops, edges, communities, facets, works. Semantic context fills `runtime.budget.semantic-context-tokens` with whole documents, best score first. The sum of the two must not exceed `retrieval.context-token-budget` (a configuration check). The item count is at most `runtime.budget.max-evidence-items`. Anything left out is counted in `omittedGraphItems`/`omittedSemanticItems` and listed as `OMITTED_BY_BUDGET`, so a claim citing it fails verification (it is not in the bundle). A path is never cut inside its hops: if it does not fit, the whole path is omitted and stated.

## 11. Synthesis (stage 8)

- **Prompt** (`prompts/synthesis/synthesis-v2.md`, schema `synthesis-output-v2`; ruling 17). v2 adds, for format compliance and the ruling 17 and 18 rules: the reply starts with `{` and ends with `}`; every claim but DESCRIPTIVE has at least one values entry (the schema requires it with `if`/`then` and `minProperties`); every number written is in values under its fact name and is named next to its measure; window names and year ranges in the text are the claim's window; rank numbers only in RANKING claims with `rank.<k>.storedRank` and `rank.<k>.researcherId`; PATH_HOP claims carry all five hop facts; DESCRIPTIVE claims have no number, at most one researcher name, and no relationship; summary and uncertainty follow the same rules. `synthesis-v1` stays loadable (`RuntimeSchemas.SynthesisOutputVersions`) until configuration selects v2 (domain-engineer, section 20), and the non-empty values rule is enforced in code for both (`Synthesis.problems`). The system part holds the rules. The user part holds three delimited, XML-escaped blocks: `<question>`, `<graph_evidence>` (one `<item id type>` per item with its facts), and `<semantic_context>` (RAG.md format). The prompt states that every block is data, not instructions.
- **Rules given to the model.** Answer only from the bundle. Every material claim cites evidence IDs and puts the facts it asserts in `values`, under the evidence fact names. Copy numbers, never compute them; derived values are precomputed in the facts. Name the measure (breadth C_r, intensity W_r, team size M_r + 1, connectivity, bridge measures, coreness), and when the question touches them, say how they differ. State the window and every uncertainty line and missing-evidence item. Never cite semantic context for a number, edge, path, component, community, or bridge value. Return JSON only.
- **Output** (`SynthesisOutput`, `synthesis-output-v2`, or v1 under `synthesis-v1`): `summary`, `claims` (`ClaimDraft`: claimId, kind, text, evidenceIds, subjectIds, window, values), `uncertainty`. It is validated like the planner output (one enclosing fence tolerated; schema keys exact; at most `runtime.synthesis.max-claims` claims; claim IDs `claim-<n>` unique; kinds known; every evidence ID well-formed; values non-empty for every kind but DESCRIPTIVE), with one retry and then `BUDGET_EXHAUSTED` abstention. There is no template fallback in live mode, so live results show the model's behaviour.
- **Fake mode** uses a deterministic template synthesizer that drafts claims from the bundle facts. It exercises verification and the benchmark mechanics, and its output says it is a template answer.

## 12. Verification (stage 9; rulings 8, 17 to 20, and 29)

Deterministic rules, no model (`Verifier.scala`), version `Verifier.Version` = `verifier-v3` (v1 work order 4, v2 repair-01, v3 repair-02: number words and asserted names, ruling 29). The version is an input of the runtime run ID (section 14). Each `ClaimDraft` gets `CheckResult`s (rule, outcome `PASS`, `FAIL`, `MISSING`, or `CONFLICT`, and detail):

| Rule | Check |
|---|---|
| `evidence-exists` | every cited ID is in the bundle; none found is FAIL |
| `graph-evidence-required` | any kind other than DESCRIPTIVE cites at least one non-semantic item; a claim resting only on semantic items is FAIL ("vector similarity is not evidence for ...") |
| `kind-matches-type` | IDENTITY: IDENTITY_RESOLUTION. METRIC_VALUE: METRIC_RECORD, RANKING_RECORD, TEMPORAL_CHANGE. RANKING: RANKING_RECORD. COAUTHORSHIP: COAUTHOR_EDGE. PATH and PATH_HOP: GRAPH_PATH. COMPONENT: COMPONENT_RECORD or METRIC_RECORD. COMMUNITY: COMMUNITY_RECORD or METRIC_RECORD. BRIDGE: METRIC_RECORD or RANKING_RECORD, and `values` names a bridge measure. TEMPORAL_CHANGE: TEMPORAL_CHANGE. PUBLICATION: WORK_RECORD, COAUTHOR_EDGE, METRIC_RECORD. FACET: FACET_RECORD |
| `values-required` | any kind other than DESCRIPTIVE asserts at least one values entry (ruling 17); otherwise FAIL |
| `asserted-values` | each `values` entry: a cited item with that fact and an equal value is PASS; a different value is CONFLICT; no cited item with the fact is MISSING. Equal means the same text or the same number by the number rule below |
| `rank` | a `rank.<k>.storedRank` entry only in a RANKING claim whose values also hold the cited `rank.<k>.researcherId`; otherwise FAIL (ruling 17) |
| `numbers-in-text` | every number in the text, digits or number words (below), is bound (below); otherwise FAIL, naming the number |
| `window` | the claim's `window` field equals the cited items' window (MISSING when absent, CONFLICT when different); and every window name and year range written in the text is a window of the field (a comparison `a>b` allows both), otherwise CONFLICT (ruling 18) |
| `identity` | every subject ID is a RESOLVED_DOMINANT researcher or appears in a cited item; every researcher name of the bundle written in the text is a researcher of the cited items whose facts the claim's values assert (below, ruling 29); no name-like span names anyone outside the bundle (below); an AMBIGUOUS or UNKNOWN name in a material claim is CONFLICT |
| `path-hops` | PATH: pathLength, researcherIds, or status equal the GRAPH_PATH facts. PATH_HOP: every hop k named in values carries `hop.<k>.from`, `.to`, `.jointPublicationCount`, `.firstYear`, and `.lastYear` (else FAIL), all equal to the GRAPH_PATH facts (else CONFLICT); a cited COAUTHOR_EDGE must be the edge of an asserted hop (else FAIL); no hop named is FAIL |
| `titles` | every quoted title equals (whitespace-normalized) the title of a cited WORK_RECORD; a cited title quoted exactly is accepted even when it holds a quote mark; otherwise FAIL |
| `context-only` | DESCRIPTIVE claims only: MISSING ("Context only: ...") when the text names at most one researcher and states no number (digits or number words), count, relationship, path, component, community, or bridge measure; otherwise FAIL (rulings 19 and 29) |

**Numbers (ruling 17).** Text is first stripped of every bundle text with a digit, space, or capital (names, titles, IDs, versions), so digits inside them are not numbers; window names are kept for the window rule. A year range (`2000-2014`, `2000 to 2014`) is a window mention unless both years are year facts the claim asserts (first and last collaboration years, hop years, work years), which are facts, not windows. Every other number must be bound: some values entry of the claim passed `asserted-values`, has a numeric fact name, and a cited item's stored value of that fact equals the written number. Integers compare exactly; a decimal passes when equal, or when at least 2 decimals are written and the stored value rounded half-up to them equals it (0.55 never verifies as 1 or 0.6). The sign of a stored change is not required in the text. Window keys (`window`, `*WindowStartYear`, `*WindowEndYear`) never bind a number: a window bound is not a free number. The bound fact's measure must be named: for scalar facts the measure name nearest to the number in its clause (`;`, `:`, a sentence end; parenthetical remarks that do not contain the number are skipped) must be one of the fact's names (`Verifier.Families`, for example "distinct coauthors" or "breadth" for `distinctCoauthorCount`, "publications", "papers", or "works" for `publicationCount`, "weighted degree" or "intensity" for `weightedDegree`); list facts (`rank.<k>.value`, `entry.<k>.count`, `CHANGE#` from, to, and delta facts) need the measure named once in the text; year facts need no name. An asserted `edgeExists` of `false` binds the number 0 as a joint publication count (the pair has no edge in that window).

**Number words (ruling 29, N1; `Verifier.wordsToDigits`).** After the bundle texts are stripped and year ranges are set aside, number words are rewritten as digits, so they are bound exactly like digits in claims, summary lines, uncertainty lines, the fixed notes (their numbers stay free numbers for uncertainty lines), and the `context-only` rule. Case is ignored. In order:
- zero expressions attached to a relation or measure become 0: "never" or "not" (an optional adverb in "-ly" between) before "coauthored", "coauthor", "collaborated", "collaborate", "co-wrote", "co-write", or "published", "worked", "written" + "together" (becomes "0 joint"); "no" before "coauthorship" or "collaboration(s)", optionally followed by "edge", "link", "tie", "connection", or "relationship" (becomes "0 joint"), but not when "path" follows; "no" before optional adjectives (direct, joint, shared, common, distinct, unique, other, further, recorded, stored, known) and then "coauthor(s)", "collaborator(s)", "publication(s)", "paper(s)", "work(s)", or "article(s)" (becomes "0 <rest>"); "none" (becomes 0). "No one" is not a zero;
- "a single" and "once" are 1, "twice" is 2;
- ordinals "first" to "tenth" become "1st" to "10th" (the rank measure), except when a year (19xx or 20xx) follows within four words, as in "first in 2001" or "their first joint publication was in 2001", which is a time marker; a year range has already been set aside, so "ranks first in 2015-2026" is still a rank;
- tens-unit compounds ("twenty-one", "ninety nine") become 21 to 99;
- cardinals "zero" to "nineteen" and the tens "twenty" to "ninety" become digits; "one" directly before "of" or "another" is not a count.

"a" and "an" are articles, never 1: "a path", "a paper", and "coauthored a paper" are existential, true for any count of at least one, so counting them as 1 would remove correct claims. A count stated only by an article (for example "have a joint publication" against a stored 0) is therefore not caught by the number rule; a claim of that kind must still assert its values, which are checked. Other "no" phrases ("no stored value", "no path", "no language model") are not numbers.

**Asserted names (ruling 29, N2).** The researcher names a claim may write are the researchers of the cited graph items whose facts its values assert with an equal value (window keys assert no researcher): for a `hop.<k>.*` key, hop k's from and to; for a `rank.<k>.*` key, rank k's researcher; for an edge, its two endpoints; for a path, its source and target, or every researcher on it for `researcherIds`; for a community, its returned members; for a facet, its subject and entries; for a metric, change, component, or identity item, its researcher. Ranking list facts (metric, sizes) and works name nobody. These researchers' resolved names and candidate display names are allowed too. A DESCRIPTIVE claim may also name its resolved subjects (it is context only). Every name in `researcherNames` (graph display names, resolved names, candidates, their top coauthors, other-name matches, and community members) written in the text, matched as whole words and longest first, must be allowed; text inside the cited works' titles is not checked (a name in a title is title text). So a name known only through resolution candidates or their top coauthors never verifies a claim that cites other evidence, and a claim with no values may name nobody. AMBIGUOUS and UNKNOWN_RESEARCHER answers never reach verification (section 13) and are unchanged.

**Names (ruling 20).** After removing every bundle text (graph and semantic facts, resolved names, candidates, their coauthors and institutions, window names), any remaining run of two or more capitalized tokens (`RulePlanner.names`) that is not part of a bundle text is a name-like span and makes `identity` FAIL. Each span is looked up once per answer through the EntityLinkCache read port (`kv.entity-link`, limit 1, never a full load) only to say which it is: a corpus researcher who is not in the evidence, or not a researcher, title, venue, topic, or institution of the evidence.

**Status.** Any CONFLICT gives CONFLICTING. Otherwise any FAIL gives UNSUPPORTED. Otherwise any MISSING with at least one PASS gives PARTIALLY_SUPPORTED, and MISSING with no PASS gives UNSUPPORTED. All PASS gives VERIFIED. A DESCRIPTIVE claim always has a `context-only` MISSING or FAIL, so it is never VERIFIED, and every VERIFIED claim cites graph evidence (ruling 19). Actions: VERIFIED is KEPT. PARTIALLY_SUPPORTED is LABELLED with the uncertainty "Partially supported: <missing facts>", or "Context only: descriptive text retrieved by vector similarity; it is not verified against the graph." UNSUPPORTED and CONFLICTING are REMOVED from the answer and stay in the `VerificationReport`. The final claims are core `AnswerClaim`s (`answer-claim-v1`) with their status.

**Summary and uncertainty lines (ruling 20).** They get the claims' numbers, windows, names, and titles rules. Their numbers must be bound to facts the kept claims asserted (an uncertainty line may also use the numbers of the fixed note lines), their windows must be the answer's window or a kept claim's window (an uncertainty line may also use year ranges of the fixed notes), every researcher name in them must be one the kept claims may write (asserted names above; ruling 29, for summary and uncertainty lines alike), and no name-like span may name anyone outside the bundle. A failing summary is replaced by the kept claims' texts in order. Failing model uncertainty lines are left out and replaced by the fixed line "<n> drafted uncertainty line(s) were left out because they failed verification." The fixed-template lines (window note, identity links, missing evidence) are always kept.

## 13. Abstention

`RuntimeAnswer.response.abstained` is true, with an `Abstention(reason, detail)`, when:

| Reason | When | Synthesis call |
|---|---|---|
| AMBIGUOUS_RESEARCHER | any plan name is AMBIGUOUS; the answer lists the candidates and other name matches and asks which is meant | no |
| UNKNOWN_RESEARCHER | any plan name is UNKNOWN_RESEARCHER (and none is AMBIGUOUS) | no |
| INVALID_PLAN | the model failed twice and the rule planner found nothing | no |
| NO_DATA_IN_WINDOW | the requested years do not meet the corpus years | no |
| UNSUPPORTED_REQUEST | every requested operation is unsupported (section 3) | no |
| INSUFFICIENT_EVIDENCE | the graph facts are empty apart from missing-evidence entries | no |
| BUDGET_EXHAUSTED | an LLM or token budget ran out, synthesis failed validation twice, or the question deadline passed (section 2) | attempted, or skipped at the deadline |
| NO_VERIFIED_CLAIM | verification kept no VERIFIED claim (every VERIFIED claim cites graph evidence; context-only claims never count, ruling 19); PARTIALLY_SUPPORTED claims stay, labelled | yes |

Abstention text is a fixed template filled from the resolution or missing-evidence records, never model text. Partial support is labelled in a non-abstaining answer when at least one claim is VERIFIED.

## 14. Audit (stage 10; ruling 10)

- **Log object.** `AuditRecord` (`rag-audit-v2`; v2 adds `LlmCall.requests`, the Bedrock requests of the attempt including an expired-token retry, and `LlmCall.failure`, the short reason of a rejected or failed attempt, section 3), written once with `ObjectLoadPort.putNew` at `logs/rag-runtime/snapshot=<graph snapshot>/run=<runtime run>/<queryId>.json`. It holds the QueryPlan, the resolution summaries, graph and semantic evidence IDs, metric versions, graph run ID, vector indexes and the embedding model version, prompt versions (with the prompt file sha256), model IDs, every LLM and embedding attempt (tokens where the response reports them, latency, throttle wait, outcome), template calls, per-stage timings, the bundle budget, verification counts, the abstention, and the answer hash (`StableIds.textHash` of the `AnswerResponse` JSON). It never holds the question text (only `questionHash` and `questionChars`), prompt text, model output text, credentials, profile names, or endpoints.
- **QuerySession item** (create-only). `sessionId`, `turnKey` = `<createdAt ISO-8601 UTC>#<queryId>`, `queryId`, `createdAt`, `expiresAt` = createdAt + `runtime.audit.ttl-days` days (epoch seconds), `schemaVersion` `rag-query-session-v1`, `questionHash`, `auditKey`, `answerHash`, `abstained`, and the verification counts.
- **EvidenceBundle items** (create-only, same TTL, `schemaVersion` `rag-evidence-bundle-item-v1`). `BUNDLE` holds the bundle header (everything except the items, plus the item ID lists). `EV#<evidenceId>` holds the core `EvidenceItem` JSON as `payload`, or, above the 32 KB item budget, `payloadRef` (`logs/rag-runtime/.../<queryId>/evidence/<evidenceId>.json`) and `payloadHash`.
- **IDs.** The runtime run ID is `StableIds.runId("rag-runtime", "rag-runtime-v2", configVersion, [graph run, metric item run, render run, vector store run, embedding model version, planner prompt version and sha256, synthesis prompt version and sha256, planner and synthesis model IDs, mode, verifier version (`verifier=verifier-v3`, ruling 29)], 1)`. A verifier rule change bumps `Verifier.Version`, so it gives new runtime and benchmark run IDs and a rerun never resumes results verified by older rules. The query ID is `qry-` + `StableIds.hash("query", runId, questionHash, request hash, n)`, where n is 1 plus the number of audits already written for that run, question, and request (so a write-once key never conflicts). Services may supply a query ID of the same shape (C2).
- **Local layout** (ruling 2, under `scratch/runtime/`):
  - `s3/logs/rag-runtime/snapshot=<d>/run=<r>/<queryId>.json` (file object store);
  - `s3/reports/rag-runtime/snapshot=<d>/run=<r>/<queryId>.answer.json` (the `RuntimeAnswer`);
  - `dynamodb/snapshot=<d>/run=<r>/{QuerySession,EvidenceBundle}.jsonl` (the file-backed runtime record port);
  - `s3/runs/rag-runtime/snapshot=<d>/run=<r>/manifest-<queryId>.json` (`store-run-manifest-v1`, written at the end of each CLI run). Work order 4: one manifest per question, because the runtime run ID repeats for identical inputs and a single write-once `manifest.json` would conflict on the second question.
  - The `EvidenceBundle` `BUNDLE` item holds the bundle provenance JSON as `payload`, the item ID lists as space-joined strings, and `missing` and `budget` as JSON strings.

  Nothing is written under `scratch/stores/`, `scratch/spark/`, or `scratch/hadoop/`.

## 15. Token, context, call, and cost budgets

| Budget | Value (configuration) | Enforced by |
|---|---|---|
| Haiku calls per question | 2 calls (planner, synthesis), each at most `runtime.llm.max-attempts-per-call` = 2 attempts, whatever the failure (malformed output or transient error). So at most 4 attempts; each attempt is at most 2 Bedrock requests (the request and one expired-token retry, `LiveAws.guardedWith`), so at most 8 requests, all counted in `LlmCall.requests` and the benchmark's `llm-budget` (review 1 m5) | the stage, before each attempt |
| Haiku rate | `runtime.llm.requests-per-minute` = 8 (quota 10, ruling 11); every attempt passes the storage `Throttle` | shared throttle |
| Titan calls per question | 1 (the query embedding, reused for every index) | stage 6 |
| Titan rate | `runtime.embedding.requests-per-minute` = 50 | storage `Throttle` |
| Planner output | `runtime.planner.max-output-tokens` | request `max_tokens` |
| Synthesis output | `runtime.synthesis.max-output-tokens` | request `max_tokens` |
| Evidence context | `retrieval.context-token-budget` (12,000) = graph share plus semantic share at most | stage 7 |
| Input tokens per question | `runtime.budget.max-input-tokens-per-question`: the estimate is checked before each call, and a call that would pass it is `SKIPPED_BUDGET` | stages 1 and 8 |
| Output tokens per question | `runtime.budget.max-output-tokens-per-question` | stages 1 and 8 |
| Concurrency | `operations.max-concurrent-llm-calls`; the CLI and the benchmark run one question at a time | throttle |
| Deadline | `runtime.question.deadline-ms` per question; Phase 10 services pass `operations.synchronous-deadline-ms` | workflow |

Cost is bounded by these token ceilings. The dollar figure comes from the Bedrock pricing page, which is not in docs.aws.amazon.com. It is an UNVERIFIED estimate to be checked in the Phase 12 cost plan, and no price appears in code or configuration.

## 16. Versioning

- **Records.** `RuntimeSchemas` lists every runtime schema version. A wire change bumps the version, the round-trip tests, and this file.
- **Prompts.** `prompts/planner/planner-v1.md`, `prompts/planner/planner-output-v1.schema.json`, `prompts/synthesis/synthesis-v1.md`, `prompts/synthesis/synthesis-output-v1.schema.json` (work order 4); `prompts/synthesis/synthesis-v2.md` and `prompts/synthesis/synthesis-output-v2.schema.json` (repair-01, ruling 17). The fence is handled in code. `prompts/planner/planner-v2.md` (repair-02, ruling 30; section 3) keeps the output schema `planner-output-v1`. Its `{{operation_rules}}` and `{{window_names}}` values are rendered by `Planner.promptValues` from the allowlist and `graph.windows`. Each prompt file starts with the lines `prompt-version: <v>` and `output-schema: <schema version>`, then `=== system ===`, `=== user ===`, and `=== retry ===` sections with `{{name}}` placeholders, filled in one pass (filled text is never rescanned). The schema file is `<schema version>.schema.json` beside the prompt and uses only the JSON Schema subset `JsonSchema` checks (type, const, enum, properties, required, additionalProperties, items, min/max items, length, value, minProperties, and if/then); any other keyword is refused at load. The runtime loads the version named in configuration (`runtime.planner.prompt-version`, `runtime.synthesis.prompt-version`) from `runtime.prompts-dir`, refuses a file whose header differs, and records `<v>` and the file's sha256 in the audit. A wording change is a new version file. Old versions stay.
- **Models.** `models.planner-model` and `models.synthesis-model` (Haiku 4.5, `us.anthropic.claude-haiku-4-5-20251001-v1:0`, ruling 3), and `models.embedding-model` (Titan V2; the RAG.md version string). `models.verification-model` is unused in this version.
- **Policies.** `dominant-name-v1` (resolution), `rendering-v1` and `selection-v1` (RAG.md, SELECTION.md), `fusion-v1` (`retrieval.fusion-policy-version`): graph evidence and semantic context are never fused into one score in this version, and the bundle keeps them apart.

## 17. Fake and live modes (ruling 11)

| Part | Fake (default; tests, benchmark, agents) | Live (`--live --profile <name> [--region]`, human only) |
|---|---|---|
| Planner | rule planner (`RULES`), or a recorded-fixture LLM client in tests | Bedrock Haiku InvokeModel (Anthropic Messages body), `storage.live.LiveAws.bedrock` with `RefreshingCredentials` and `LiveAws.guarded` (one refresh and retry on an expired token) |
| Synthesis | template synthesizer, or a recorded-fixture LLM client in tests | Bedrock Haiku as above |
| Query embedding | storage `HashingEmbedder` (`fake:` version filter) | storage `BedrockTitanEmbedder` |
| Graph | `InMemoryGraph` loaded from the Phase 7 Neptune CSV (`-Xmx6g`), or test fixtures | same local store in Phase 9; Neptune openCypher through the work order 3 adapter from Phase 10 |
| Key-value | `InMemoryDynamo` loaded read-only from the Phase 7 item files | same local store in Phase 9; DynamoDB from Phase 10 |
| Vectors | `FileVectorStore` at `scratch/stores/s3vectors` | `FileVectorStore` at the path the human passes (`scratch/stores-live/s3vectors`, never read by agents) |
| Audit | file stores under `scratch/runtime/` | same |

Key-value reads in the CLI use the storage `ItemFileReader` (STORAGE.md "Indexed item-file reader"; ruling 22, review 1 F6), not `InMemoryDynamo`: the full item fake beside the full graph fake runs out of memory at `-Xmx6g` (measured in work order 4). The rag-side `ItemFileIndex` is deleted. Tests use `InMemoryDynamo`.

The CLI (exact form; prompts are read from `runtime.prompts-dir` relative to the working directory, so run sbt at the repository root):

```text
SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -J-Xmx6g "rag/runMain scholarchain.rag.cli.AskCli \
  --question '<text>' \
  --graph scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 \
  --items scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 \
  --objects scratch/stores/s3 --vectors scratch/stores/s3vectors [--out scratch/runtime] [--session <id>] \
  [--live --profile <name> [--region <r>] [--per-minute 1..8]]"
```

In live mode `--vectors` must point at the live-embedded store (the human's path); Titan is throttled to `runtime.embedding.requests-per-minute`, Haiku to `--per-minute` (default `runtime.llm.requests-per-minute`), and the expired-token retry of either client passes the same throttle (`LiveAws.guardedWith(beforeRetry)`). Per-attempt timeouts are `runtime.llm.timeout-ms` and `runtime.embedding.timeout-ms` (SDK `apiCallTimeout`).

No test calls AWS. Live clients are unit-tested on request building and response parsing with recorded bodies only. SDK retries are off, so the runtime's bounded attempts are the only retry layer.

## 18. Untrusted text (ruling 9)

The question, names, titles, rendered documents, and dataset text are data. The planner sees only the question, inside `<question>`, XML-escaped, never retrieved text. Synthesis sees the escaped blocks of section 11. Model output is parsed as JSON and validated; text that is not JSON is never acted on. Work order 4 tests place injection strings in the question, in a rendered document, and in a researcher name and a paper title, and assert that the plan operations, the store calls (template IDs and bound IDs), and the verified claims do not change. Repair-01 (`InjectionSpec`, ruling 21): the fixture text holds `<`, `>`, `&`, `"`, and the closing tag of every delimiter (`</question>`, `</graph_evidence>`, `</semantic_context>`, `</notes>`, `</item>`, `</doc>`) in the question, W2's title, the display name of a fixture researcher, and a profile document; an injected question goes through the model planner and the synthesis prompt builders with a recording fake model; each built prompt has exactly one opening and one closing tag per block and the escaped forms; and the plan operations, store calls, and claims equal those of the clean question. Removing the escaping in `Planner.promptValues`, `Synthesis.promptValues`, or `EvidenceBuilder.render` makes a test fail. The OpenAlex injection text is not available, so that requirement is waived (ruling 21, m3); the threat model (Phase 17) records the waiver.

## 19. Core changes (work order 2) and follow-ups outside rag

- **C1 (domain-engineer, done).** Add four non-semantic `EvidenceType` values: `IDENTITY_RESOLUTION`, `RANKING_RECORD`, `TEMPORAL_CHANGE`, and `FACET_RECORD`. `isSemantic` stays true only for TEXT_CHUNK and PROFILE. Without them, the verifier's kind-to-type rule cannot tell a ranking from a metric row or an identity decision from a match decision. If declined, the fallback is MATCH_DECISION, METRIC_RECORD, METRIC_RECORD, and AUTHORSHIP with a `recordKind` fact. Whether `evidence-v1` must change is domain-engineer's call: the record shape is unchanged, but older readers reject the new names.
- **C2 (domain-engineer, done).** `StableIds.queryId(runId, questionHash, requestHash, ordinal)` = `qry-` + `hash("query", ...)` and `IdFormat.isQueryId` (`qry-<32 hex>`); the runtime uses the typed signature. Note: `ClaimKind.TEMPORAL_CHANGE` (rag) and `EvidenceType.TEMPORAL_CHANGE` (core) are different enums with the same case name; runtime code imports them by owner. Phase 10 then validates `/v1/explain/{queryId}` against the same shape.
- **No other core change.** Core `AnswerClaim`, `AnswerResponse`, `EvidenceItem`, `Provenance`, `ResearcherMetrics`, `QueryOperation`, `CollaborationMetric`, and `VerificationStatus` are reused as they are. Core `QueryPlan` and `EvidenceBundle` are unused by the runtime (ruling 1). An optional Scaladoc note saying so is enough; no shape change.

Follow-ups outside rag (reported, not done here):

- **F1 (spark-engineer, storage-engineer; M9).** Add `authoredWorkCount` (Number, AUTHORED rows of the researcher, any source) to every EntityLinkCache item (StoreConversionJob), and make it a required attribute in `DynamoContract` (with `entity-link-v2`). Then resolution needs no graph read. Until then, `graph.authored-counts`.
- **F2 (spark-engineer, storage-engineer).** Export `temporal/window-summary` and `temporal/window-comparison` (one row per window or comparison, with the largest component ID added) as store objects, for example `curated/window-summary/snapshot=<d>/run=<r>/part-00000.jsonl`, plus a read in storage. Until then, corpus-level component, community, and window questions (B13, B15, B17, B24) and "in the largest component" for `all-years` are stated as `NOT_STORED`.
- **F3 (build-engineer, Phase 10).** Package `prompts/` with the service image (or as rag resources). Phase 9 reads them from `runtime.prompts-dir` relative to the repository root.

## 20. Configuration keys (work order 2, domain-engineer)

Ruling 13: every limit, budget, rate, model ID, prompt version, and timeout is a key, not a code constant. Existing keys used unchanged: `graph.windows`, `s3-vectors.indexes`, `neptune.query-timeout-ms` (each graph template), `retrieval.graph-limit`, `retrieval.vector-limit`, `retrieval.max-path-depth` (request maxima), `retrieval.context-token-budget`, `retrieval.fusion-policy-version`, `models.embedding-model`, `operations.*`.

| Key | Type | Default | Reason |
|---|---|---|---|
| `models.planner-model` (env `SCHOLARCHAIN_PLANNER_MODEL_ID`) | String | `us.anthropic.claude-haiku-4-5-20251001-v1:0` | ruling 3 |
| `models.synthesis-model` (existing) | String | change from the placeholder to `us.anthropic.claude-haiku-4-5-20251001-v1:0` | ruling 3 |
| `runtime.prompts-dir` (env `SCHOLARCHAIN_PROMPTS_DIR`) | String | `prompts` | where versioned prompt files are read |
| `runtime.question.max-chars` | Int | 2000 | question length bound |
| `runtime.question.deadline-ms` | Int | 180000 | per-question bound (CLI); at 8 per minute, 4 attempts can wait 22.5 s in the throttle |
| `runtime.planner.prompt-version` | String | `planner-v1`; repair-02 asks domain-engineer to change it to `planner-v2` (ruling 30) | ruling 13 |
| `runtime.planner.max-output-tokens` | Int | 800 | output budget |
| `runtime.planner.max-operations` | Int | 6 | plan validation |
| `runtime.planner.max-researcher-names` | Int | 4 | plan validation |
| `runtime.planner.max-term-chars` | Int | 200 | names, topics, venues, institutions, lookup text |
| `runtime.planner.max-filter-terms` | Int | 5 | topics, venues, institutions each |
| `runtime.synthesis.prompt-version` | String | `synthesis-v1`; repair-01 asks domain-engineer to change it to `synthesis-v2` (ruling 17) | ruling 13 |
| `runtime.synthesis.max-output-tokens` | Int | 2000 | output budget |
| `runtime.synthesis.max-claims` | Int | 20 | output validation |
| `runtime.llm.requests-per-minute` | Int | 8 (valid 1 to 8) | ruling 11 (quota 10) |
| `runtime.llm.max-attempts-per-call` | Int | 2 (valid 1 to 2) | ruling 3 (one retry) |
| `runtime.llm.timeout-ms` | Int | 30000 | per-attempt timeout |
| `runtime.llm.temperature` | Double | 0.0 | repeatable plans and claims |
| `runtime.embedding.requests-per-minute` | Int | 50 (valid 1 to 50) | ruling 11 |
| `runtime.embedding.timeout-ms` | Int | 10000 | per-attempt timeout |
| `runtime.resolver.policy-version` | String | `dominant-name-v1` | ruling 5 |
| `runtime.resolver.dominant-share` | Double | 0.9 (valid above 0.5, at most 1) | ruling 5 / Phase 7 ruling 30 |
| `runtime.resolver.dominant-min-works` | Int | 5 | ruling 5 / Phase 7 ruling 30 |
| `runtime.resolver.max-candidates` | Int | 500 (valid 1 to 1000, the DynamoDB query bound) | above Wei Wang's 420 items |
| `runtime.resolver.candidate-coauthors` | Int | 3 | candidate context |
| `runtime.resolver.candidate-institutions` | Int | 3 | candidate context |
| `analytics.ranking-size` | Int | 50 | the stored top-N, one key for Spark and the runtime (work order 2 renamed `runtime.rankings.stored-size` to it) |
| `runtime.rankings.default-top-n` | Int | 10 | default N |
| `runtime.rankings.max-top-n` | Int | 1000 | plan validation bound; larger stored requests are cut and stated |
| `runtime.semantic.top-k` | Int | 5 | hits per index |
| `runtime.semantic.max-indexes` | Int | 3 | indexes per question |
| `runtime.budget.graph-evidence-tokens` | Int | 8000 | bundle share |
| `runtime.budget.semantic-context-tokens` | Int | 3000 | bundle share; graph plus semantic at most `retrieval.context-token-budget` |
| `runtime.budget.max-evidence-items` | Int | 300 | bundle bound |
| `runtime.budget.chars-per-token` | Int | 3 | token estimate (RAG.md) |
| `runtime.budget.max-input-tokens-per-question` | Int | 40000 | cost bound (2 calls x 2 attempts) |
| `runtime.budget.max-output-tokens-per-question` | Int | 5600 | 2 x 800 + 2 x 2000 |
| `runtime.audit.ttl-days` | Int | 30 | Phase 7 ruling 22 |
| `graph-read.max-results` | Int | 1000 | replaces the code constant `GraphLimits.MaxResults` |
| `graph-read.id-batch-size` | Int | 100 | batched ID templates |
| `graph-read.path-max-frontier` | Int | 50000 | path search bound |
| `graph-read.path-timeout-ms` | Int | 20000 | path search total time |
| `graph-read.community-members-limit` | Int | 100 | template bound |
| `graph-read.community-neighbors-limit` | Int | 20 | template bound |
| `graph-read.community-facet-member-cap` | Int | 2000 | aggregate bound |
| `graph-read.facet-limit` | Int | 10 | facet entries per subject |
| `graph-read.lookup-limit` | Int | 20 | name lookup bound |
| `graph-read.max-lookup-ids` | Int | 10 | topic IDs per aggregate |
| `graph-read.neighbors-max-depth` | Int | 3 | spec neighbors endpoint |
| `graph-read.neighbors-max-limit` | Int | 200 | spec neighbors endpoint |

Cross-key checks: the budget shares sum to at most `retrieval.context-token-budget`; `runtime.rankings.default-top-n` is at most `analytics.ranking-size`; `retrieval.max-path-depth` is at most 6 (ruling 7); every Int above is at least 1.

## 21. Known failure modes

- dominant-name-v1 links a real homonym with few works to the dominant researcher. The answer always states the share. A name printed differently from the display name (for example "Jack Dongarra") is not a candidate, and its other-name matches are only listed.
- The rule planner's capitalized-span name extraction misses lower-case names and can take a capitalized non-name ("Cloud Computing") as a name. Such a name then resolves UNKNOWN_RESEARCHER and abstains, which is safe but unhelpful.
- Rankings stop at 50 and break ties by ID. Filtered rankings cover only the stored entries.
- The live shortest-path search can stop at SEARCH_LIMIT around hubs (the 287-author team puts most of the largest component within 5 to 6 hops). That is reported, never shown as "no path".
- The token estimate is a character heuristic, not the Haiku tokenizer. The input ceiling is checked against the estimate, and the response's reported usage is audited.
- Verification checks what claims assert and the numbers, names, and titles in their text. A qualitative misreading without numbers or names (for example calling a low participation coefficient "high") is not caught.
- Semantic results with the fake embedder are bag-of-words matches. They are context only and never affect a graph answer.
- The benchmark's stored-path reference is the 8-hop CSV `work/neptune-check/.../run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv`, built in Phase 7 from that run's `paths/path-evidence` (rag has no Parquet reader, ruling 14). Work order 5 checked it hop by hop against the `collaboration-path` facts of the same run (4 pairs, 8 hops, all equal).
- A live answer can differ from the fake one: the model planner may choose other operations or names, so the call counts in section 22 are estimates.
- Number binding is lenient across cited items of the same fact: a number equal to the fact's stored value in any cited holder binds, so two researchers' values swapped in one claim citing both items verify. The measure-name rule catches a number stated as another measure only when the nearest measure name in the clause differs; two numbers of the same family (for example distinct coauthors and maximum coauthors on one paper) swapped in one clause are not caught, and neither are first and last years swapped.
- The measure vocabulary is fixed (`Verifier.Families`); a model that names a measure with other words (for example "collaborators count" is covered, "network size" is not) has the claim removed. That errs toward removal.
- The name-like span rule flags any run of two or more capitalized words that is no bundle text, for example "Graph Evidence" or a venue the bundle does not hold; the claim is removed, or the line replaced. Single-word names (a surname alone) are not detected. The fake benchmark (template text) shows no such span (section 22).
- A title with a quote mark is accepted only when it is quoted exactly as stored.
- Number words (ruling 29) cover the list in section 12 only. Not covered: "a"/"an" as a count (a deliberate choice, section 12), number words above 99 or with "hundred", fractions and percentages in words ("half", "a third"), ordinals beyond "tenth", and zero phrasings outside the list (for example "have yet to coauthor", "do not share any paper"). An ordinal within four words before a single year is read as a time marker, so "ranks second in 2015" is not checked as a rank. "one" before "of" or "another" is skipped, so "one of the 50" checks only the 50.
- Number words remove some correct model claims: "never collaborated" against a no-edge item binds only if the claim asserts `edgeExists` false or a joint count of 0; "no coauthors in common" becomes a 0 that no fact holds; "the first time", "the fifth paper", or "one hop away" are counted. That errs toward removal. In the fake benchmark no template text changed status (section 22).
- Asserted names (ruling 29): a model claim that names a researcher whose facts it cites but does not assert in `values` (for example a publication claim citing an edge and a work, with only the work ID in values, that names the pair) is removed. A community or facet claim may name any returned member or entry once it asserts one of the item's facts. A name inside a cited title is skipped. Names are matched as whole words against the bundle's researcher names, so a name spelled differently from every bundle name is caught only by the name-like span rule, and a single-word surname is not caught.

- Planner-v2 (ruling 30) is tested only on recorded-shape fixtures. The step-1 audits keep the failed rules but not the model's window fields, so the rule-7 fixture (NAMED `2015-2026` with its years filled in) is a reconstruction. Any other rule-7 shape now records its exact fault (`Windows.requestProblems`). A failure reason longer than `LlmCaller.MaxReason` (600 characters with the shape) is cut in the audit, but not in the retry prompt. The live run is the real test.

## 22. Benchmark harness (work order 5; ruling 12)

`cli/BenchmarkCli` loads the stores once (as `AskCli`, through `AskCli.open` and `LoadedRuntime`) and runs every row of the question CSV, in file order, through `Workflow.answer`. `cli/Benchmark` holds the pure parts: the CSV reader, the stored-path reference, the checks, and the summary.

- **Inputs.** `--questions` (`experiments/benchmark/benchmark-questions-v1.csv`, read-only; columns `question_id`, `category`, `question_text`, `researcher_a`, `researcher_b`, `window`, `answer_type`, `should_abstain`), `--path-evidence` (stored hops: `window,researcherX,researcherY,jointPublicationCount,firstCollaborationYear,lastCollaborationYear`), and the `AskCli` store, live, and `--out` flags. `--max n` answers at most n pending questions.
- **Run ID.** `StableIds.runId("rag-benchmark", "rag-benchmark-v2", configVersion, [runtime run ID, sha256 of the question file, sha256 of the path file], 1)`. The runtime run ID already covers the mode, the stores, the vectors, prompts, and models, so fake and live runs never share a directory.
- **Outputs** (file object store under `<out>/s3`, write-once): `reports/rag-benchmark/snapshot=<d>/run=<r>/questions/<question_id>.json` (`rag-benchmark-result-v1`); `.../summary-<nnn>.json` (`rag-benchmark-summary-v1`, nnn = results present); `runs/rag-benchmark/snapshot=<d>/run=<r>/manifest-<nnn>.json` (`store-run-manifest-v1`, every result and the summary). Each question's runtime audit, answer, records, and per-question manifest are written as in section 14. A result holds no question text.
- **Resume.** A question with a result object is skipped, so rerunning the same command continues. The summary is rebuilt from all stored results; when nothing new was answered, its bytes are identical and the write is `Unchanged`.
- **Failures.** A runtime failure, or a question whose planner, synthesis, or embedding call never succeeded because of an error (`TRANSIENT_ERROR` or `FAILED`; not invalid output or a budget skip), gets no result. The run stops there with exit 1, and the next run retries it. This keeps a live run that cannot reach Bedrock from storing fallback answers as results.
- **Checks** (PASS, FAIL, or NOT_APPLICABLE; mechanical only, no rating, relevance score, or label):

| Check | Rule |
|---|---|
| `plan-operations` | every operation is allowlisted for its scope and metric (`Allowlist`), and the plan holds the category's operation: coauthor_breadth DISTINCT_COAUTHOR_COUNT (metric), max_team_size MAXIMUM_TEAM_SIZE (or the max-coauthors metric), direct_collaboration DIRECT_COAUTHORS/PAIR, shortest_path SHORTEST_PATH, components COMPONENT_LOOKUP, communities COMMUNITY_COMPARISON, bridges BRIDGE_RANKING (or a bridge metric), temporal TEMPORAL_COMPARISON, topics TOPIC_FILTER, institutions INSTITUTION_FILTER, evidence DIRECT_COAUTHORS/PAIR or SHORTEST_PATH |
| `researcher-names` | the plan's names equal `researcher_a` and `researcher_b` (none extra, none missing) |
| `resolution` | B09 both AMBIGUOUS, B12 `Elena Vasquez-Marlowe` UNKNOWN_RESEARCHER (ruling 12, `Benchmark.V1Expectations`); every other named researcher RESOLVED_DOMINANT |
| `abstention` | abstained equals `should_abstain`; B09 AMBIGUOUS_RESEARCHER and B12 UNKNOWN_RESEARCHER |
| `claim-evidence` | every final claim cites IDs that are in the bundle; every material claim (the verifier ran `graph-evidence-required` on it) cites a graph item |
| `verification` | an answer keeps at least one VERIFIED claim that cites graph evidence (ruling 19); an abstention keeps none; final claims are only VERIFIED or PARTIALLY_SUPPORTED |
| `bundle-budget` | graph and semantic tokens within their shares, the sum within `retrieval.context-token-budget`, items within `runtime.budget.max-evidence-items` |
| `llm-budget` | model calls only in planning and synthesis, at most `max-attempts-per-call` attempts each, at most 2 Bedrock requests per attempt (`Benchmark.MaxRequestsPerAttempt`), reported tokens within the per-question ceilings |
| `embedding-budget` | at most one embedding call, and none without an index queried |
| `path-hops` | each FOUND `GRAPH_PATH` item has the stored researcher sequence and, hop by hop, the stored joint count and first and last years, and a final claim cites it; a non-FOUND status for a stored pair fails; a pair with no stored path is NOT_APPLICABLE |
| `pair-edges` | each bundle `COAUTHOR_EDGE` whose pair has a stored hop has the stored joint count and years |
| `deadline` | the question's wall time is at most `runtime.question.deadline-ms` |

- **Reported separately.** Graph retrieval: template and key reads (by template ID), rows, truncated reads, graph evidence items and tokens, path statuses, and stages 3 to 5 latency. Semantic retrieval: embedding calls and tokens, indexes, hits, semantic tokens, and stage 6 latency. Also LLM attempts by stage and outcome, tokens, throttle wait, verification counts, plan sources, abstention reasons, resolution decisions, and question latency (p50, p95, max).

Fake run (agents; heap at most 6g):

```text
SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -J-Xmx6g "rag/runMain scholarchain.rag.cli.BenchmarkCli \
  --questions experiments/benchmark/benchmark-questions-v1.csv \
  --path-evidence scratch/stores/work/neptune-check/snapshot=2026-10-03/run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv \
  --graph scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 \
  --items scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 \
  --objects scratch/stores/s3 --vectors scratch/stores/s3vectors --out scratch/runtime [--max <n>]"
```

Live run (the human only): the same command with `--vectors scratch/stores-live/s3vectors --live --profile scholarchain-sdk --region us-east-2 --per-minute 8`, first with `--max 2`, then with `--max 30` (the second resumes). Haiku is held to `--per-minute` (8) on every attempt and retry; Titan to `runtime.embedding.requests-per-minute` (50). The flags are unchanged by repair-01; the run IDs are new (code versions v2). The repair-01 part B report gives the exact commands, the expected time, and the call counts.

Results (fake mode, the 30 questions of benchmark-questions-v1):

| Run | Runtime run | Verified / partial / unsupported / conflicting | Checks failing |
|---|---|---|---|
| `run-d22ec2f83528a8e95067443cfb114bab` (work order 5) | `run-31d709a11373f510067dcd1657caaca8` | 109 / 0 / 0 / 0 (109 claims) | abstention: B13, B15, B17, B24 (FU2) |
| `run-3250005c792bad74709a1b3c7f0a4814` (repair-01) | `run-b8694005af9edbb2c60b5ca1b043768d` | 81 / 28 / 0 / 0 (109 claims) | abstention: B13, B15, B17, B24 (FU2) |

| `run-c5befe8f45411f497ea0db779978e564` (repair-02, verifier-v3) | `run-42b0ea60246dac5cf349a537a90419df` | 81 / 28 / 0 / 0 (109 claims) | abstention: B13, B15, B17, B24 (FU2) |

Repair-02 (number words and asserted names): no claim changed status, and no claim text, summary, uncertainty line, or abstention changed on any of the 30 questions, B09 (AMBIGUOUS) and B12 (UNKNOWN_RESEARCHER) included. The template writes digits and names only the researchers whose facts it asserts. Three template wordings changed so the new rules do not misread them; none of them appears in this run's answers: a temporal change with a missing side says "no stored value", not "none"; a path with an endpoint outside the window says "is not in the <window> graph", not "has no publication"; and a hop's publication claim also asserts the hop edge's `edgeId`.

From repair-01: the 28 changes are the descriptive claims (semantic context), now context only (ruling 19). No material claim changed status or text, and the name-like span rule made no corpus lookup (graph reads 1,017 in both runs).
