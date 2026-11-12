# ScholarChain store contracts

Owner: storage-engineer. Code: `modules/storage/src/main/scala/scholarchain/storage/`. Tests: `modules/storage/src/test/scala/scholarchain/storage/`.

This file is the contract between the Phase 6 export (`scratch/spark/snapshot=2026-10-03/run=run-0375535e0174b7330b18a78988c07211/export/load-manifest.json`, described in `modules/spark/JOBS.md`) and the four stores. A later spark-engineer work order converts the Parquet tables into the store-ready files below. The validators in this module are the acceptance test for those files. The code constants are authoritative where this text and the code disagree. A disagreement is a defect: report it to storage-engineer.

Phase 7+8 scope (human rulings 1 to 28): every store has a local fake, and the fakes are the default everywhere. The live SDK adapters (package `scholarchain.storage.live`) run only behind `--live --profile <name>`, both off by default; agents never pass them (rulings 1 and 26). Every resource (bucket, tables, cluster, vector bucket, indexes, IAM) comes from Terraform. No code here creates one.

## General rules

- **Identifiers (ruling 4).** Every store reuses the Phase 6 IDs unchanged: researcher `scr:` IDs, work IDs, venue, topic, and institution IDs, `comm-` community IDs, `comp-` component IDs, `pair-` path IDs, `cae-` coauthor edge IDs, and `chunk-` summary IDs. The only new IDs are the IDs of the Neptune edges that Phase 6 does not name (see Neptune). They are deterministic in (edge type, endpoints, policy), and a per-window edge gets its window through its endpoint (a `comm-` ID already encodes the window).
- **Provenance.** Every Neptune element, every DynamoDB item loaded from a run, and every vector carries `runId`, `snapshot`, and `schemaVersion` (vectors carry `runId`; the snapshot is in their `textRef` key). `runId` is the Phase 6 stage run that computed the row (its `provenance.runId`). The run that wrote the store file is in the object key and the run manifest.
- **Windows.** The window names and years are the core `graph.windows` (`all-years` 2000-2026, `2000-2014`, `2015-2026`). The validators take them from the configuration.
- **Run IDs and outputs.** Phase 7+8 runs write under `scratch/stores/` with the same key layout as S3 (below). Run IDs come from `StableIds.runId(stage, codeVersion, configVersion, inputs, attempt)`, and each run writes one run manifest (Phase 6 ruling 14 conventions).
- **Idempotency.** A reload of the same data changes nothing. Different data under an existing key is a conflict. It is reported and never written silently. Replacing stored data is an explicit, human-gated supersede and never a default.

## S3 (ruling 7)

One bucket per environment, created by Terraform. Keys are relative to the bucket. The bucket name comes from configuration and never appears in a stored pointer.

Key rules (`S3Layout.keyProblems`): at most 1,024 UTF-8 bytes; characters only from `A-Z a-z 0-9 ! _ . * ' ( ) = / + -`; no empty, `.`, or `..` segment; the first segment is one of the eight prefixes; and the key contains the segment pair `snapshot=<YYYY-MM-DD>/run=<runId>`.

| Prefix | Key pattern | Content |
|---|---|---|
| `raw/` | `raw/<dataset>/snapshot=<d>/run=<r>/...` | Raw dataset snapshots |
| `curated/` | `curated/spark/snapshot=<d>/run=<r>/<table>/...` | Phase 6 Parquet tables (copied as is) |
| | `curated/dynamodb/snapshot=<d>/run=<r>/<Table>/part-*.jsonl` | DynamoDB item files |
| | `curated/coauthor-evidence/snapshot=<d>/run=<r>/window=<w>/<edgeId>.json` | Long COAUTHORED_WITH evidence lists |
| | `curated/mention-map/snapshot=<d>/run=<r>/...` | Mention-level identity data (RESOLVES_TO), S3 only |
| `graph/` | `graph/neptune/snapshot=<d>/run=<r>/{vertices,edges}/<name>/part-*.csv` | Neptune bulk-load CSV. This prefix is the loader `source`, so it holds nothing else |
| `semantic-context/` | `semantic-context/text/snapshot=<d>/run=<r>/<index>/<vectorKey>.txt` | Rendered text, one object per vector |
| | `semantic-context/candidates/snapshot=<d>/run=<r>/<index>/part-*.jsonl` | Embedding-candidate files (the work queue) |
| `vectors/` | `vectors/backup/snapshot=<d>/run=<r>/<index>/part-*.jsonl` | Backup of written vectors and metadata |
| | `vectors/rejected/snapshot=<d>/run=<r>/<index>.jsonl` | Rejected vectors (key, reason, textHash, runId; never data) |
| `reports/` | `reports/<report>/snapshot=<d>/run=<r>/...` | Load and validation reports |
| `logs/` | `logs/<component>/snapshot=<d>/run=<r>/...` | Logs, write-once |
| `runs/` | `runs/<stage>/snapshot=<d>/run=<r>/manifest.json` | Run manifests |

The bucket is the core `storage.bucket` (`SCHOLARCHAIN_BUCKET`), and the eight prefixes are the core `storage.prefixes` (work order 2). A test checks that `S3Layout.Prefixes` equals `storage.prefixes.all`. `raw/` keys carry `run=` (ruling 22).

Objects are write-once (`ObjectLoadPort.putNew`). An existing key with the same bytes is `Unchanged`. An existing key with different bytes is `Conflict` and is not written.

**Run manifest** (`RunManifest`, `store-run-manifest-v1`): `stage`, `runId`, `snapshot`, and one entry per object written: `key`, `sha256` (`sha256:<64 hex>` of the bytes), `bytes`, and `sourceRef` (the Phase 6 table and run, or the input file it came from). `RunManifest.verify` checks that every entry exists with its hash and size, that the keys are valid and unique, and that every entry has a source reference.

**Local fake:** `FileObjectStore(root)` maps key `k` to the file `root/k`. Local runs use `root = scratch/stores/s3`.

## Neptune (ruling 5)

Format: openCypher bulk-load CSV ([Load format for openCypher data](https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html), accessed 2026-11-05). The files are RFC 4180 CSV in UTF-8 with one header row. System columns: `:ID` and `:LABEL` in node files; `:ID`, `:START_ID`, `:END_ID`, and `:TYPE` in relationship files. Property headers are `name:Type`, and an empty field means no value. Load with `format=opencypher` and `userProvidedEdgeIds=TRUE` ([Neptune Loader Command](https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html), accessed 2026-11-05).

The loader merges duplicate node IDs non-deterministically and keeps one arbitrary row for a duplicate relationship ID. By default it does not update the properties of an existing ID. The validator therefore rejects every duplicate ID, and the fake refuses a reload that would change an existing element.

Layout under `graph/neptune/snapshot=<d>/run=<r>/`: `vertices/<name>/part-*.csv` and `edges/<name>/part-*.csv`. Every part file repeats the header. Nothing else may be under the prefix, because the loader loads every object under its `source` prefix. Spark `_SUCCESS` and `.crc` files must be removed.

Types used: `String`, `Int`, `Long`, `Double`, `Bool`. `Bool` must be exactly `true` or `false`, because the loader reads any other value as false. `Double` must be finite. Lists are space-separated `String` values, because every stable ID is visible ASCII and so never contains a space.

Every vertex file ends with `runId:String,snapshot:String,schemaVersion:String`. Every edge file ends with those three plus `policyVersion:String`. All four are required. `schemaVersion` is `neptune-<name>-v1`.

### Vertex files

| File (`vertices/<name>/`) | `:LABEL` | Property columns (before the provenance columns) | Source: Phase 6 table and columns |
|---|---|---|---|
| `researcher` | `Researcher` | `displayName:String` (required), `orcid:String`, `mentionCount:Long`, `dblpAnchored:Bool`, `assignedTier:String`, `identityConfidence:Double` (all required except `orcid`) | `canonical/researchers` (`nodes/type=researcher`): researcherId -> `:ID`, same-named columns |
| `work` | `Work` | `title:String`, `year:Int`, `workType:String`, `doi:String`, `dblpAnchored:Bool` (required) | `canonical/works` (`nodes/type=work`): workId -> `:ID` |
| `venue` | `Venue` | `name:String` (required), `venueType:String` | `canonical/venues`: venueId -> `:ID` |
| `institution` | `Institution` | `name:String` (required), `countryCode:String` | `canonical/institutions`: institutionId -> `:ID` |
| `topic` | `Topic` | `name:String` (required), `subfield:String`, `field:String` | `canonical/topics`: topicId -> `:ID` |
| `community` | `Community` | `window:String`, `windowStartYear:Int`, `windowEndYear:Int`, `communitySize:Long`, `internalEdges:Long`, `crossCommunityEdges:Long`, `communityLabel:String` (all required) | `analytics/communities` (`metrics/type=communities`), all windows, every community (79,740 rows) so every MEMBER_OF end exists |

`:ID` formats: Researcher `IdFormat.isResearcherId`, Work `isWorkId`, Venue, Institution, and Topic `isNamespacedId`, Community `comm-<32 hex>`. Vertex IDs are unique across all vertex files. The namespaces (`scr:`, `dblp:`, `doi:`, `openalex:W`, `openalex:S`, `openalex:I`, `openalex:T`, `dblp-venue:`, `ror:`, `sci:`, `comm-`) keep the labels apart.

### Edge files

| File (`edges/<name>/`) | `:TYPE` | Start -> end | Property columns | Source |
|---|---|---|---|---|
| `authored` | `AUTHORED` | Researcher -> Work | `position:Int` (required), `year:Int` | `canonical/authored` (`edges/type=authored`) |
| `coauthored-with` | `COAUTHORED_WITH` | Researcher -> Researcher | `window:String`, `windowStartYear:Int`, `windowEndYear:Int`, `jointPublicationCount:Long`, `firstCollaborationYear:Int`, `lastCollaborationYear:Int` (all required), `supportingWorkIds:String`, `supportingWorkIdsRef:String` (exactly one) | `graph/coauthor-edges` (`edges/type=coauthored_with`): edgeId -> `:ID`, researcherA -> `:START_ID`, researcherB -> `:END_ID`, representativeWorkIds -> evidence |
| `published-in` | `PUBLISHED_IN` | Work -> Venue | none | `canonical/works`: workId, venueId (rows with a venue) |
| `affiliated-with` | `AFFILIATED_WITH` | Researcher -> Institution | `via:String` (required; space-separated `MENTION`, `ORCID_EMPLOYMENT`) | `canonical/researcher-institutions` (`edges/type=affiliated_with`) |
| `has-topic` | `HAS_TOPIC` | Work -> Topic | none | `canonical/work-topics` (`edges/type=has_topic`) |
| `member-of` | `MEMBER_OF` | Researcher -> Community | `window:String`, `windowStartYear:Int`, `windowEndYear:Int` (required) | `analytics/researcher-communities` (`metrics/type=bridges`): researcherId, communityId, window |

Edge IDs:
- `COAUTHORED_WITH`: the Phase 6 `edgeId`, which is `StableIds.coauthorEdgeId(start, end, windowStartYear, windowEndYear, policyVersion)`, with `policyVersion` equal to the graph policy (`graph-v1`). One edge per researcher pair per window. `:START_ID` is less than `:END_ID` (CanonicalPair).
- Every other type: `NeptuneContract.edgeId(type, start, end, policyVersion)` = `"ge-" + StableIds.hash("graph-edge", TYPE, start, end, policyVersion)`. For the core edge types this equals `StableIds.graphEdgeId(type, start, end, policyVersion, Nil)`. `policyVersion` is the row's provenance policy version.

COAUTHORED_WITH evidence: when `jointPublicationCount <= 20` (`InlineEvidenceMax`), `supportingWorkIds` lists the complete sorted, distinct `representativeWorkIds`, and its length equals `jointPublicationCount`. Above 20, `supportingWorkIdsRef` holds the S3 key `curated/coauthor-evidence/snapshot=<d>/run=<r>/window=<w>/<edgeId>.json`. That object is JSON `CoauthorEvidence`: `schemaVersion` = `coauthor-evidence-v1`, `edgeId`, `window`, `workIds` (complete and sorted), `runId`, `snapshot`. It is kept out of the `graph/` prefix so the Neptune loader never reads it.

Validator (`NeptuneCsv.scan`) checks:
- exact headers;
- field counts;
- types and required values;
- ID formats and recomputed edge IDs;
- labels and types;
- provenance (`runId` format, snapshot date, `schemaVersion`, `policyVersion`);
- duplicate vertex and edge IDs;
- that every edge endpoint exists with the right label;
- known windows with matching years, and a MEMBER_OF window that equals its community's window;
- canonical COAUTHORED_WITH pairs, a count of at least 1, and collaboration years inside the window;
- COAUTHORED_WITH evidence (inline count and order, or a well-formed reference; when an object reader is given, the evidence object exists and matches the edge);
- evidence objects (M4): `workIds` sorted and distinct (`EVIDENCE_OBJECT_ORDER`), and `runId` and `snapshot` equal to the edge's (`EVIDENCE_OBJECT_PROVENANCE`); schemaVersion, edgeId, window, count, and ID format mismatches stay `BAD_EVIDENCE_REF`;
- evidence tracing (F2, ruling 34), for inline lists and for evidence objects alike: every supporting work ID is a Work vertex (`EVIDENCE_WORK_MISSING`), and both endpoints have an AUTHORED edge to every cited work (`EVIDENCE_NOT_AUTHORED`). One violation per edge and rule, naming the first offender;
- unknown files.

The violation list is capped at 10,000 entries, plus a total; the count per rule (`GraphScan.ruleCounts`) is not capped. Without an object reader (`ValidateCli` without `--evidence-root`), evidence objects are not opened, so their work IDs are not traced; the scan counts those edges as `edges-object-not-opened`, and `ValidateCli` then prints `INCOMPLETE: <n> edges cite evidence objects that were not opened` and exits 1 even with no violation (Phase 7 review 2 N6), so an untraced run can never pass as gate evidence. `LoadNeptuneCli` always opens them from `<out>/s3`.

`NeptuneCsv.scan` streams the files: vertex files first, then AUTHORED edge files, then the other edge files, so each edge row is checked against the vertices already read and each COAUTHORED_WITH work ID against the Work vertices and the AUTHORED pairs. The scan itself keeps only the vertex labels and Community windows (about 512,000 entries), the edge IDs (about 3.1 million strings), and the (researcher, work) AUTHORED pairs (535,898) in memory (ruling 27); each parsed element goes to a caller-supplied sink (none for the validator CLI and the live loader). `InMemoryGraph` keeps every element. The full Phase 7 graph (3,635,231 elements) loads twice in one JVM with `-Xmx6g` in about 90 s (work order 7), and the validator with evidence tracing scans it in about 30 s at `-Xmx6g` (repair-01), so no streaming redesign is needed for the AUTHORED set.

`GraphScan.evidenceChecks` reports how much evidence was traced: `edges-inline`, `edges-object`, `edges-object-not-opened`, and `work-ids-traced`. `ValidateCli --kind neptune` prints them as `evidence <name> <n>`, and prints `rule <code> <n>` for the four rules above, zero included.

`:LABEL` `Community` and `:TYPE` `MEMBER_OF` are the core `NodeLabel.Community` and `EdgeType.MEMBER_OF`, and Community IDs use `IdFormat.isCommunityId` (`StableIds.communityId`). The `MEMBER_OF` edge ID equals `StableIds.graphEdgeId(EdgeType.MEMBER_OF, researcherId, communityId, policyVersion, Nil)`.

Scope cuts (ruling 5):
- RESOLVES_TO, DERIVED_FROM, and SUPPORTED_BY are not loaded into Neptune in this version. The mention map (mention -> researcher, with tier, refusal rules, and confidence) stays in S3 (`curated/`), and runtime name lookup uses EntityLinkCache.
- `analytics/cross-community-edges` is not loaded as its own edge type. A cross-community edge is a COAUTHORED_WITH edge whose two MEMBER_OF communities differ.
- Per-window researcher metrics live in ResearcherMetricCache, not as vertex properties.
- Path evidence (50 rows) and component rows stay in S3.

Read side: `GraphReadPort`, with fixed parameterized templates; see "Graph read templates" below. User text is never query text.

Expected counts (Phase 6 manifest row counts):

| Vertex | Rows | Edge | Rows |
|---|---|---|---|
| Researcher | 259,057 | AUTHORED | 535,898 |
| Work | 113,205 | COAUTHORED_WITH | 1,590,754 (787,726 + 217,957 + 585,071) |
| Venue | 288 | PUBLISHED_IN | at most 113,205 (works with a venue) |
| Institution | 58,404 | AFFILIATED_WITH | 379,708 |
| Topic | 1,226 | HAS_TOPIC | 106,682 |
| Community | 79,740 | MEMBER_OF | 397,100 |
| Total | 511,920 | Total | at most 3,123,347 |

**Local fake:** `InMemoryGraph` validates a whole directory and applies it all or nothing. It compares each element with the stored one while streaming and buffers only new elements, so a reload holds no second copy of the graph. An element reloaded with the same properties is unchanged. A reload with different properties is refused (`PROPERTY_CONFLICT`). For reads it indexes the edge IDs leaving and entering every node (all edge types) and the COAUTHORED_WITH pair; `reader(limits)` gives its `GraphReadPort`.

### Graph read templates (Phase 9 ruling 7; closes Phase 7 M12)

Code: `GraphRead.scala` (port, limits, result types, path search, neighbors), `InMemoryGraph.scala` (fake), `live/NeptuneGraphReader.scala` (`OpenCypherTemplates`, `NeptuneGraphReader`). Tests: `GraphReadSpec` (fixture graph; live adapter with a stub client, no network).

Rules for every template:
- **Fixed text, bound values.** Each template is one constant openCypher string, selected by template ID. Where openCypher cannot bind a part (a node label, a relationship direction, a facet path), there is one constant per enum value (`graph.lookup.Topic`, `graph.expand.OUT`, `graph.researcher-facets.VENUE`, ...). Every caller value (IDs, window, years, text, limits) is a `$parameter` in the request's `parameters` JSON. A test checks that no template contains a write clause (CREATE, MERGE, DELETE, DETACH, SET, REMOVE, LOAD, CALL) and that an injection string in a lookup reaches only `parameters`.
- **Limits from configuration** (`GraphReadLimits.from(cfg)`): `graph-read.*`, `retrieval.graph-limit`, `retrieval.max-path-depth`, `runtime.planner.max-term-chars`, `neptune.query-timeout-ms`, and the windows of `graph.windows`. The public port methods check every parameter first (an out-of-range value, an unknown window, too many IDs, or an empty ID throws `IllegalArgumentException`), so the fake and the live adapter enforce the same bounds. `GraphLimits.MaxResults` is gone; `graph-read.max-results` replaces it.
- **Truncation.** A limited template fetches `limit + 1` rows (`$fetch`) and returns `Bounded(items, truncated)`; `truncated` is true when the store held more.
- **Timeout.** Live queries run behind `USING QUERY:TIMEOUTMILLISECONDS <neptune.query-timeout-ms>` ([openCypher query timeout hint](https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-query-hints-timeout-hint.html), accessed 2026-11-06; the effective timeout is the smaller of this and the cluster `neptune_query_timeout`). The number is the validated configuration integer, the only non-constant text in a query.
- **Provenance.** Every returned vertex and edge is a `GraphElement` with its `runId`, `snapshot`, and `schemaVersion` properties (edges also `policyVersion`); aggregates return the facet, community, or researcher vertex with the count.

| Template ID | Port method (parameters and bounds) | Returns |
|---|---|---|
| `graph.vertex`, `graph.edge` | `vertex(id)`, `edge(id)` | the element or none |
| `graph.vertices` | `vertices(ids)`: 1 to `graph-read.id-batch-size` IDs | the found vertices, in request order |
| `graph.coauthor-edge` | `coauthorEdge(x, y, window)`: any order; canonical pair bound as `$a < $b` | the COAUTHORED_WITH edge or none |
| `graph.coauthors` | `coauthors(researcherId, window, limit)`: limit 1 to `retrieval.graph-limit` | `CoauthorLink(edge, coauthorId, coauthorName)`, joint publications descending, then edge ID; truncated |
| (composite) | `supportingWorks(edgeId, evidence: ObjectReadPort)` | `SupportingWorks(edgeId, workIds, ref)`: the complete sorted list, inline or from the evidence object (schema, edge ID, and count checked against `jointPublicationCount`); Left when missing or inconsistent |
| `graph.frontier` | `frontier(ids, window)`: 1 to `id-batch-size` IDs; fetch `graph-read.path-max-frontier` + 1 | `(id, neighbor)` for every COAUTHORED_WITH edge in the window; truncated |
| (composite over `graph.frontier`, `graph.vertices`, `graph.coauthor-edge`) | `shortestPath(source, target, window, maxDepth)`: maxDepth 1 to `retrieval.max-path-depth` (at most 6), source differs from target | `PathResult(status, maxDepth, researcherIds, hops, visited, detail)`; hops are `PathHop(from, to, edge)` with the canonical edge (joint count, years, supporting works or ref, provenance) |
| `graph.community-members` | `communityMembers(communityId, limit)`: 1 to `graph-read.community-members-limit` | `CommunityMember(researcher, degree)`: degree is the member's COAUTHORED_WITH edges to other members in the community's window; degree descending, then ID; truncated |
| `graph.community-neighbors` | `communityNeighbors(communityId, limit)`: 1 to `graph-read.community-neighbors-limit` | `CommunityLink(community, crossEdges)`: edges in the window between the two communities, descending, then ID |
| `graph.cross-community-edges` | `crossCommunityEdges(researcherId, window, limit)`: 1 to `retrieval.graph-limit` | `CrossCommunityEdge(edge, otherId, otherName, communityId, otherCommunityId)`, joint publications descending, then edge ID |
| `graph.community-facets.TOPIC`/`.VENUE` | `communityFacets(communityId, facet, limit)`: limit 1 to `graph-read.facet-limit` | `FacetCount(facet vertex, works)`: distinct works with Work.year in the community's own window, of the first `graph-read.community-facet-member-cap` members by ID; truncated also when the community is larger than the cap |
| `graph.researcher-facets.TOPIC`/`.VENUE` | `researcherFacets(ids, window, facet, perLimit)`: 1 to `id-batch-size` IDs, perLimit 1 to `facet-limit`; at most `graph-read.max-results` rows | one `FacetList(subjectId, entries, truncated)` per requested ID: facets of its AUTHORED works with Work.year in the window, works descending, then facet ID |
| `graph.researcher-institutions` | `researcherInstitutions(ids, perLimit)`: as above | one `InstitutionList` per requested ID: `InstitutionLink(AFFILIATED_WITH edge with via, institution)`, by institution ID; not windowed |
| `graph.topic-researchers` | `topicResearchers(topicIds, window, limit)`: 1 to `graph-read.max-lookup-ids` topics, limit 1 to `retrieval.graph-limit` | `ResearcherCount(researcher, works)`: AUTHORED works with Work.year in the window having any of the topics, descending, then ID |
| `graph.lookup.Topic`/`.Venue`/`.Institution` | `lookup(label, text, limit)`: text 1 to `runtime.planner.max-term-chars` characters (trimmed), limit 1 to `graph-read.lookup-limit` | vertices whose `name` contains the text, case-insensitive (`toLower`), by name, then ID |
| `graph.authored-counts` | `authoredCounts(ids)`: 1 to `id-batch-size` IDs | AUTHORED edge count per requested ID (0 when none or unknown); the M9 interim works source until follow-up F1 |
| `graph.expand.OUT`/`.IN`/`.BOTH` | (used by `neighbors`) ids, edge types, fetch `max-results` + 1 | `(edge, other node)`, by edge ID, then node ID |
| (composite over `graph.expand`) | `neighbors(nodeId, direction, depth, limit, edgeTypes)`: depth 1 to `graph-read.neighbors-max-depth`, limit 1 to `graph-read.neighbors-max-limit`, edgeTypes a non-empty subset of the six loaded edge types | `Neighborhood(start, nodes, edges, truncated)`: nodes within depth (start excluded), at most limit, expanded level by level; only reached nodes are expanded, so at most limit nodes are ever read; edges are those traversed between kept nodes |

**Shortest path.** Neptune openCypher does not support `shortestPath()` ([openCypher compliance](https://docs.aws.amazon.com/neptune/latest/userguide/feature-opencypher-compliance.html), accessed 2026-11-06), so the port runs a bounded bidirectional breadth-first search over `graph.frontier`, in ID batches. Full levels are expanded from the side with the smaller frontier until the two balls meet; that gives the length L. The path is then read back from the target with the Spark job 5 rule: each step takes the smallest neighbor whose distance from the source is one less (`modules/spark/JOBS.md`). Inside the source ball that distance is known. Beyond it, the shortest-path layers are built forward from the meeting layer over the neighbor lists read on the target side (a neighbor of a shortest-path node with the right target distance is itself on a shortest path). So a FOUND path equals the Spark path for the same pair and window. A single-source search was tried first: with the default `path-max-frontier` of 50,000 it stopped at SEARCH_LIMIT on all 54 benchmark pairs, even a 3-hop one, because hubs reach most of the largest component within a few hops. Statuses:
- `UNKNOWN_RESEARCHER`: either end is not a Researcher vertex;
- `FOUND`: the target was reached within maxDepth;
- `UNREACHABLE`: the search ran out of researchers first;
- `BEYOND_DEPTH`: maxDepth levels were searched without reaching the target;
- `SEARCH_LIMIT`: more than `graph-read.path-max-frontier` researchers were reached, a frontier read was cut at that many rows or timed out, or `graph-read.path-timeout-ms` passed. It never means "no path".

**Live adapter** (`NeptuneGraphReader(client, limits)`, ExecuteOpenCypherQuery, [API](https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html), accessed 2026-11-06). Results are parsed from the default JSON format (`~id`, `~labels`, `~start`, `~end`, `~type`, `~properties`; [results format](https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-opencypher-queries.html), accessed 2026-11-06), each property typed by its bulk-load column. Transient failures (throttling, 5xx, network) are retried by `RetryPolicy.Default` with jitter, after the one expired-token refresh of `LiveAws.guarded`. A `TimeLimitExceededException` becomes `GraphReadTimeoutException` and is not retried. The client must use runtime credentials allowed only `neptune-db:ReadDataViaQuery`; every template is a read. Phase 9 uses the fake; the live adapter is for the Phase 10 services and is tested only on request building and response parsing (a stub client answers from the fake).

**Measured on the full Phase 7 graph** (conversion run `run-1d6a86491812ff94887270c1ab885e69`, read only, `-Xmx6g`, Phase 9 work order 3, with a temporary probe that was then deleted):
- the load (511,920 vertices, 3,123,311 edges, with evidence tracing) took 32 s;
- 3,769 MiB of heap were in use after a GC, with both adjacency indexes, so one graph fits within ruling 27's 6 GB, but two copies would not;
- all 54 benchmark pairs of the path candidates (`collaboration-path-v1`, all-years, depth 6) gave the stored status, and every FOUND path gave the stored researcher sequence (41 FOUND, 13 BEYOND_DEPTH, 0 differences);
- the slowest search took 93 ms.

## DynamoDB (ruling 6)

All six tables use on-demand capacity and string keys. Writes are single conditional `PutItem` calls (`attribute_not_exists(<partition key>)`), because `BatchWriteItem` cannot carry conditions ([BatchWriteItem](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html), accessed 2026-11-05). The loader runs them concurrently, bounded by the retry policy below.

A failed condition still consumes write units ([read and write operations](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/read-write-operations.html), accessed 2026-11-05). A reload therefore first checks ArtifactRegistry: when `RUN#<runId>` for that table already records the same `contentHash`, the whole file is skipped.

Numbers (F1, ruling 33): DynamoDB trims leading and trailing zeros of a Number ([Number](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html), "In DynamoDB, numbers are represented as variable length. Leading and trailing zeroes are trimmed.", accessed 2026-11-06), so a stored `"4.0"` reads back as `"4"`. Every put therefore sends `AttributeValue.canonicalItem(item)`: each `N`, at any depth in `L` and `M`, becomes `java.math.BigDecimal(n).stripTrailingZeros.toPlainString` (`4.0` -> `4`, `0.0` -> `0`, `1.50` -> `1.5`, `1E+3` -> `1000`; exact, no rounding below 38 digits). `Unchanged` versus `Conflict` compares canonical forms on both sides (`AttributeValue.sameItem`), so numbers compare by value. `InMemoryDynamo` stores the canonical form too, so the dry run models the live readback. The Spark item files keep their `Double.toString` text (`4.0`); the validator accepts both, and canonical text from Spark would be a later spark-engineer item.

Number range (N4, Phase 7 review 2): `AttributeValue.numberOk` accepts only valid decimal text with at most 38 significant digits and a value of 0 or a magnitude from 1E-130 to below 1E+126, the DynamoDB Number range. So a value such as `1E+999999999` is refused before `canonicalNumber` could expand it. `DynamoContract.problems` checks every Number at any depth in `L` and `M`, so an item built in code with invalid number text gets a `Left` from every put (fake and live), never a `NumberFormatException`.

On a failed condition, an identical stored item (numbers compared by value) counts as `Unchanged`. A different one is a `Conflict`: it is recorded in RunLedger and not written. Supersede is explicit: `replace(table, item, expectedRunId)` puts with the condition `runId = :expected`. It is refused for RunLedger and for ArtifactRegistry `RUN#` items, which are append-only, and running it against live tables is a human-gated data overwrite.

Item files are JSON Lines, one `{"Item": {...}}` per line in DynamoDB JSON (`{"S": ...}`, `{"N": "..."}`, `{"BOOL": ...}`, `{"NULL": true}`, `{"L": [...]}`, `{"M": {...}}`), under `curated/dynamodb/snapshot=<d>/run=<r>/<Table>/`. `DynamoItemFile.validate` checks:
- the JSON shape;
- key presence and key byte limits;
- required attributes and their types;
- the TTL attribute (allowed only on TTL tables);
- provenance on loaded tables;
- the per-table rules below;
- the item size budget;
- duplicate keys across the files.

Item size follows the DynamoDB rules ([item sizes](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/CapacityUnitCalculations.html), accessed 2026-11-05). The ScholarChain budget is 32 KB per item, against the 400 KB limit. An ID list longer than 50 entries becomes an S3 pointer attribute `<name>Ref`.

| Table | Partition key | Sort key | TTL | Access patterns | Writer |
|---|---|---|---|---|---|
| RunLedger | `runId` (`run-<hex32>`) | `entryKey` | none | Query(runId): every step, batch, retry count, rejection, and embedding progress entry of a run; Get(runId, entryKey) for resume | loaders, embedding runner (append-only) |
| EntityLinkCache | `nameKey` (`name-v1:<canonical name>`) | `researcherId` | none | Query(nameKey, limit): candidate researchers for a name in a question (several candidates means ambiguity, so the system abstains) | loader |
| QuerySession | `sessionId` | `turnKey` = `<createdAt ISO-8601 UTC>#<queryId>` | `expiresAt` | Query(sessionId): the turns of a session in time order | runtime (create-only) |
| EvidenceBundle | `queryId` | `itemKey` = `BUNDLE` or `EV#<evidenceId>` | `expiresAt` | Query(queryId): the bundle header and every evidence item; Get(queryId, `EV#<id>`) to resolve a cited evidence ID | runtime (create-only) |
| ArtifactRegistry | `artifactKey` (for example `neptune/graph`, `dynamodb/ResearcherMetricCache`, `s3vectors/researcher-profile-v1`) | `versionKey` = `RUN#<runId>` or `ACTIVE` | none | Get(artifactKey, `ACTIVE`): the serving run; Query(artifactKey, `RUN#`): load history; reload skip by contentHash | loader (`RUN#` append-only; `ACTIVE` set by compare-and-set on runId) |
| ResearcherMetricCache | `researcherId` | `metricKey` = `WINDOW#<window>` or `CHANGE#<from>><to>` | none | Get(researcher, `WINDOW#<w>`): metrics in one window; Query(researcher, `WINDOW#`): all windows; Get(researcher, `CHANGE#2000-2014>2015-2026`): temporal comparison | loader |

Required attributes beyond the keys (`DynamoContract`):
- RunLedger: `entryType`, `recordedAt`, `schemaVersion`.
- EntityLinkCache: `displayName`, `matchedName`, `identityConfidence` (0 to 1), `mentionCount`, `dblpAnchored`, `runId`, `snapshot`, `schemaVersion`; optional `orcid`. `nameKey` must equal `DynamoContract.nameKey(matchedName)`: drop a trailing DBLP homonym number (` 0001`), then apply the canonical form of `StableIds.canonicalAffiliationName`. There is one item per distinct (name key, researcher) over `displayName` and `alternateNames`. Mention-level data stays in S3.
- QuerySession: `queryId`, `createdAt`, `expiresAt` (Number, epoch seconds), `schemaVersion`.
- EvidenceBundle: `expiresAt`, `schemaVersion`, and exactly one of `payload` (the JSON of a core `EvidenceItem` or bundle header) or `payloadRef` (S3 key) plus `payloadHash`.
- ArtifactRegistry: `runId`, `snapshot`, `schemaVersion`; `RUN#` items also need `manifestKey`, `contentHash`, and `rowCount`.
- ResearcherMetricCache: `runId`, `snapshot`, `schemaVersion`, `policyVersion`.

Item schema versions (`DynamoContract.itemSchemaVersion`; the validator and the loader refuse any other value; the Spark names were reported in Phase 7 work order 6):

| Table | Items | `schemaVersion` |
|---|---|---|
| RunLedger | every entry | `run-ledger-v1` |
| EntityLinkCache | every item | `entity-link-v1` |
| ArtifactRegistry | every item (`RUN#`, `ACTIVE`, and the loader's `loaded/` items) | `artifact-registry-v1` |
| ResearcherMetricCache | `WINDOW#<window>` | `researcher-metrics-window-v1` |
| ResearcherMetricCache | `CHANGE#<from>><to>` | `researcher-change-v1` |
| ResearcherMetricCache | `RANKING#<metric>` / `TOP#<window>` | `researcher-ranking-v1` |
| QuerySession | every item | `rag-query-session-v1` |
| EvidenceBundle | `BUNDLE` and `EV#<evidenceId>` | `rag-evidence-bundle-item-v1` |

Extra attributes the Spark items carry beyond the required ones (allowed, not checked): `window` and `communityRunId` on `WINDOW#` items, `metricRunIds` on ranking items, and `stage`, `keyPrefix`, `objectCount` on ArtifactRegistry `RUN#` items.
  - `WINDOW#` items also need `windowStartYear`, `windowEndYear`, `publicationCount`, `distinctCoauthorCount`, `totalCoauthorOccurrences`, `averageCoauthorsPerPaper`, `maximumCoauthorsOnOnePaper`, `weightedDegree`, `componentId`, `componentSize`, `coreness`, `graphDegree`, and `maximumTeamWorkIds` (or `maximumTeamWorkIdsRef`). They take `communityId`, `communitySize`, `crossCommunityDegree`, `communitiesConnected`, and `participationCoefficient` from `analytics/researcher-communities`, joined on researcherId and window. The window years must match the configured window.
  - `CHANGE#` items need `presence`, plus the `temporal/researcher-changes` columns.
  - Ranking items (below) need `metric`, `window`, `windowStartYear`, `windowEndYear`, and `entries`.

### Top-N rankings (ruling 21)

Spark precomputes the top 50 per metric and window. They live in ResearcherMetricCache under a dedicated partition prefix, one item per (metric, window), with no secondary index. One `GetItem` answers "top N researchers by metric M in window W" (N at most 50; the reader takes the first N entries).

| Attribute | Value |
|---|---|
| `researcherId` (partition key) | `RANKING#<metric>` |
| `metricKey` (sort key) | `TOP#<window>` |
| `metric` | the metric name, equal to the partition key suffix |
| `window`, `windowStartYear`, `windowEndYear` | the configured window, equal to the sort key suffix |
| `entries` | List of at most 50 Maps, each exactly `{rank: N, researcherId: S, value: N}` |
| `runId`, `snapshot`, `schemaVersion` (`researcher-ranking-v1`), `policyVersion` | provenance |

Metrics (`DynamoContract.RankingMetrics`; the names are the `WINDOW#` attribute names): `publicationCount`, `distinctCoauthorCount`, `maximumCoauthorsOnOnePaper` (maximum team size), `crossCommunityDegree`, `communitiesConnected`, `participationCoefficient`, `coreness`.

Order: `value` descending, ties by `researcherId` ascending; `rank` is 1, 2, 3, ... in list order (no shared ranks). A researcher appears at most once. The validator checks all of this. Researchers with no value for the metric in the window are not ranked. Size: about 4 KB per item, 7 metrics x 3 windows = 21 items, 21 WRU per load.

Access pattern: Get(`RANKING#<metric>`, `TOP#<window>`). A `RANKING#` partition never collides with a researcher, because researcher IDs start with `scr:`.

### Loader bookkeeping

The DynamoDB loader records each loaded table in ArtifactRegistry as `artifactKey = loaded/dynamodb/<Table>`, `versionKey = RUN#<source run>` (the run in the item-file path), with `contentHash` (sha256 over every file's relative name and sha256), `rowCount`, and `manifestKey = runs/store-conversion/snapshot=<d>/run=<source run>/manifest.json`, the run manifest the Spark store-conversion run writes. A reload of the same content skips the table without writes; different content under the same source run is a conflict and nothing is written. The `loaded/` artifact keys never collide with ArtifactRegistry items that Spark writes. The loader never sets `ACTIVE`; switching the serving run is a separate, deliberate step.

TTL applies only to QuerySession and EvidenceBundle (`expiresAt`). Every other table rejects the attribute. DynamoDB deletes expired items within a few days ([TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html), accessed 2026-11-05), so reads filter expired items (`DynamoContract.unexpired`), in the fake and the live adapter alike. Retention is 30 days (ruling 22); the runtime writer sets `expiresAt = createdAt + 30 days`. Runtime audit records are write-once objects under `logs/`, not a table (ruling 22).

Kept in S3, not DynamoDB, because no runtime access pattern needs a key lookup: works, authored, coauthor edges (Neptune serves them), components, path evidence and path-length estimates, communities (the Community vertex carries the size), window summaries and comparisons (4 rows, read as small S3 objects), community transitions, the mention and work maps, refusals, self-pairs, and violations.

Expected item counts and write units for one full load:

| Table | Items | Approx. item size | WRU per item | WRU |
|---|---|---|---|---|
| ResearcherMetricCache `WINDOW#` | 397,100 | about 0.8 KB | 1 | 397,100 |
| ResearcherMetricCache `CHANGE#` | 191,318 | about 1.3 to 1.6 KB | 2 | 382,636 |
| ResearcherMetricCache `RANKING#` | 21 | about 4 KB | 4 | 84 |
| EntityLinkCache | at least 259,057 (one per researcher display name plus distinct alternate names; the exact count comes from the conversion job) | about 0.35 KB | 1 | 259,057 to about 400,000 |
| RunLedger | about 20,000 (load steps plus about 15,000 embedding progress entries) | under 1 KB | 1 | about 20,000 |
| ArtifactRegistry | about 30 | under 1 KB | 1 | about 30 |
| QuerySession, EvidenceBundle | 0 at load (runtime only) | | | 0 |
| **Total** | **about 0.87 to 1.0 million items** | | | **about 1.06 to 1.2 million WRU** |

One WRU is one standard write of up to 1 KB, rounded up ([on-demand capacity mode](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/on-demand-capacity-mode.html), accessed 2026-11-05).

The dollar price per million WRU is published on the AWS pricing page (https://aws.amazon.com/dynamodb/pricing/on-demand/), which is not on docs.aws.amazon.com and could not be read here. At a price P per million WRU, one full load costs about 1.2 x P dollars. An UNVERIFIED value of 0.625 USD per million WRU (standard table class) gives about 0.75 USD. Ruling 18: the Region is us-east-2, the cost stays an estimate (under 1 USD per full load), and it is verified in the Phase 12 cost plan. A reload skipped through ArtifactRegistry costs about 0.

**Local fake:** `InMemoryDynamo(windows, clock)` exposes three separate ports:
- `reader` (`KeyValueReadPort`): get and bounded query, with expired items hidden.
- `loader` (`KeyValueLoadPort`): `putNew` and `replace`, on RunLedger, EntityLinkCache, ArtifactRegistry, and ResearcherMetricCache only.
- `runtimeWriter` (`RuntimeRecordPort`): create-only, on QuerySession and EvidenceBundle only.
- `loadItemFiles(dir)`: loads a `curated/dynamodb/snapshot=<d>/run=<r>` directory of table directories (loader tables only), reading the files only. Every file is validated first, so a violation loads nothing. An item that differs from one already loaded is a `CONFLICT` violation. It returns the item count per table. This is the runtime fake's read-only input (Phase 9 work order 1, list 3).

**File-backed runtime records:** `FileRuntimeRecords(root, windows, clock)` (`writer`: RuntimeRecordPort; `reader`: KeyValueReadPort) keeps one append-only log per runtime table (`root/QuerySession.jsonl`, `root/EvidenceBundle.jsonl`, item-file lines). Creates are create-only (`Inserted`, `Unchanged`, `Conflict`), and only an `Inserted` item is appended. On open the logs are replayed. A partial last line left by a crash was never acknowledged, so it is cut; any other unreadable line stops the open. Local runtime runs put `root` under `scratch/runtime/dynamodb/snapshot=<d>/run=<r>/` (modules/rag/RUNTIME.md, section 14).

### Indexed item-file reader (Phase 9 ruling 22, review 1 F6)

`ItemFileReader.open(dir, windows): Either[Vector[Violation], ItemFileReader]` (`ItemFileReader.scala`) is the read-only `KeyValueReadPort` that local runtime CLIs use over a `curated/dynamodb/snapshot=<d>/run=<r>` directory. It replaces the rag-local `ItemFileIndex`. Use it instead of `InMemoryDynamo.loadItemFiles` whenever the full graph fake shares the JVM: the full Phase 7 items in an `InMemoryDynamo` beside the graph ran out of memory at `-Xmx6g` (Phase 9 work order 4).

- **Open:** it streams every `.jsonl` file of every table directory once (`DynamoItemFile.tableDirs`, so only loader tables, at least one, and no runtime table). Every non-blank line is parsed (`DynamoItemFile.parseLine`) and checked with `DynamoContract.problems` and the duplicate-key rule, the same rules as `DynamoItemFile.validate`. Any `BAD_ITEM` or `DUPLICATE_KEY` (with file and 1-based line), a `BAD_INPUT` directory, or a file that changed while it was being indexed fails the whole open with `Left`. Nothing is half-opened. At most 100 violations are returned. When there are more, a last `TRUNCATED` violation gives the total.
- **Memory:** only the key index is kept: per table and partition key, the sort keys in sort-key order, each with a file number, a byte offset, and a line length. No item is kept.
- **Reads:** `get` and `query` (`skPrefix`, `limit` from 1 to `QueryMaxLimit`, sort-key order, as in `InMemoryDynamo`) read each matched line from disk. The reader first checks that the file still has the size and modification time it had at open. It then parses the line again, and checks that the line still holds a valid item with the indexed key. Items come back in the canonical number form (`AttributeValue.canonicalItem`), as DynamoDB and `InMemoryDynamo` return them. If a file changed after open, the read throws `IllegalStateException` and never returns another item. Files are never written. TTL filtering is not needed, because loader tables refuse `expiresAt`.
- **Memory and time** (full Phase 7 items, run-1d6a86491812ff94887270c1ab885e69: 876,228 items, EntityLinkCache 287,780, ResearcherMetricCache 588,439, ArtifactRegistry 9; measured 2026-11-11 with `ItemFileReaderProbe` in the test tree):

| `-Xmx` | Graph fake in use after GC | Graph plus reader in use after GC | Index build | Sampled peak of heap in use (2 ms) |
|---|---|---|---|---|
| 6g | 3,797 MiB | 3,942 MiB | 18.5 s | 5,448 MiB |
| 4608m | 3,769 MiB | 3,913 MiB | 13.2 s | 4,295 MiB |

  The index costs about 145 MiB. The peak includes garbage that G1 had not yet collected, so it grows with `-Xmx`. The 4608m run shows that graph plus reader complete with 1.5 GiB less heap. The probe also prints the sum of each heap pool's peak, but that sum overstates the peak: it was 5,588 MiB in the 4608m run. Replay (read-only, prints only): `sbt -batch -J-Xmx6g "storage/Test/runMain scholarchain.storage.ItemFileReaderProbe <graph dir> <item dir> <objects root>"`.

## S3 Vectors (ruling 8)

One vector bucket (`s3-vectors.vector-bucket`) holds five indexes. Their names equal the core `s3-vectors.indexes` values, and a test checks this. All five use dimension 1024, cosine distance, and float32 data. Index mode is ENHANCED, the default for vector buckets created on or after 2026-09-30 ([Vector indexes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-indexes.html), accessed 2026-11-05). The non-filterable keys `vectorKey`, `textHash`, and `textRef` must be set in Terraform when each index is created, because they cannot be changed later.

| Index | documentType | entityIds |
|---|---|---|
| `researcher-profile-v1` | `researcher-profile` | `[researcherId]` |
| `collaboration-community-v1` | `collaboration-community` | `[communityId]` |
| `paper-abstract-v1` | `paper-summary` | `[workId]` |
| `collaboration-path-v1` | `collaboration-path` | `[pairId, researcherId...]` (source to target) |
| `topic-institution-summary-v1` | `topic-summary`, `institution-summary` | `[topicId]` or `[institutionId]` |

`paper-abstract-v1` (ruling 10): the inputs carry no abstracts. `abstractTextRef` is null in all 110,867 J01, 214,162 J02, and 325,029 J09 Hadoop work records, and Crossref abstracts may be copyrighted (`datasets/dataset-manifest.yaml`). This index therefore holds representative-paper summaries rendered from metadata (title, year, venue, authors, topics) as `documentType = paper-summary`. The rag-engineer owns that template.

Vector key: the stable document ID, which is the Phase 6 `summaryId` where one exists (`StableIds.chunkId(<subjectId>|<window>, templateVersion, documentType, None, textHash)`), and otherwise `StableIds.chunkId` over the document.

Metadata schema (`VectorContract`): only these keys. Any other key, the text in particular, is rejected.

| Key | Type | Filterable | Required | Content |
|---|---|---|---|---|
| `vectorKey` | string | no | yes | equals the vector key |
| `entityIds` | list of strings (at most 10) | yes | yes | see the table above |
| `window` | string | yes | yes | window name |
| `documentType` | string | yes | yes | must belong to the index |
| `sourceDataset` | string | yes | yes | core SourceType (`DERIVED` for summaries) |
| `embeddingModelVersion` | string | yes | yes | owned by rag-engineer (for example `amazon.titan-embed-text-v2:0`, 1024, normalized) |
| `textHash` | string | no | yes | `sha256:<64 hex>` of the text |
| `runId` | string | yes | yes | run that produced the text (the rendered document's `provenance.runId`) |
| `textRef` | string | no | yes | S3 key `semantic-context/text/.../<index>/<vectorKey>.txt` |
| `topicIds` | list of strings (at most 10) | yes | no | where it applies |
| `institutionIds` | list of strings (at most 10) | yes | no | where it applies |
| `communityId` | string | yes | no | where it applies |

Budgets, measured as the UTF-8 bytes of each key plus its JSON value (an estimate, since the service's exact accounting is not documented): total metadata at most 4 KB, and filterable metadata at most 1 KB.

Vector data: exactly 1024 finite components, not all zero, and unit length within 0.001.

Write rule (`VectorWriteRule`). PutVectors overwrites an existing key, so the rule is applied on the client before every write. The live adapter reads existing keys with GetVectors (at most 100 keys per call) first.

| Stored vector | Incoming vector | Mode | Result |
|---|---|---|---|
| none | any | any | write |
| same textHash, same model version | | any | skip (idempotent; also the resume rule) |
| different textHash or model | | CreateOnly (default) | reject `KEY_CONFLICT` |
| different textHash or model | model version or runId differs | Supersede | write |
| different textHash | same model and same runId | Supersede | reject `SUPERSEDE_WITHOUT_CHANGE` |

Because the summary ID contains the text hash, a changed text normally gets a new key, which makes it a new versioned key.

Batches and retries (`VectorLoader`): at most 500 vectors per PutVectors call (default 100). A key repeated in the input is written once: an identical repeat is skipped, and a different one is rejected (`DUPLICATE_KEY`). A key repeated within one call is rejected (`DUPLICATE_KEY_IN_BATCH`).

Only `TransientStoreException` (throttling, timeouts, 5xx) is retried. `RetryPolicy.Default` allows 5 attempts with delays of 200 ms doubling to at most 5 s. The live adapter adds jitter. Rejected vectors go to the rejected-vectors sink (key, reason, textHash, runId; never the data). Every batch writes a RunLedger entry `vectors#<index>#attempt-<a>#batch-<nnnnn>` with the written, skipped, and rejected counts and the attempts used, or `FAILED` when retries run out.

Read side (`VectorReadPort`): `get` (at most 100 keys) and `query(index, vector, topK, filter)`. A query fails when it filters on a non-filterable key or uses more than 100 filter constraints. The fake implements the documented filter operators: `$eq` (on a list, it matches any element), `$ne`, `$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$nin`, `$exists`, `$startsWith`, `$and`, and `$or`. A missing key fails every operator except `$ne`, `$nin`, and `$exists: false`, which is an assumption.

**Local fake:** `FileVectorStore(root)` keeps one append-only JSON Lines log per index (`root/<index>.jsonl`), replayed on open, and searches by brute-force cosine distance.

## Embedding-candidate JSONL (rulings 3, 9, 11)

The later Spark job writes one JSON object per line under `semantic-context/candidates/snapshot=<d>/run=<r>/<index>/part-*.jsonl`. Schema `embedding-candidate-v1` (`EmbeddingCandidate`):

| Field | Type | Rule |
|---|---|---|
| `schemaVersion` | string | `embedding-candidate-v1` |
| `vectorIndex` | string | one of the five indexes |
| `vectorKey` | string | `chunk-<32 hex>`, unique across all files |
| `documentType` | string | belongs to the index |
| `entityIds` | list | per document type (see S3 Vectors) |
| `window` | string | a configured window |
| `topicIds`, `institutionIds` | list | namespaced IDs, at most 10, may be empty |
| `communityId` | string or null | `comm-<32 hex>` |
| `text` | string | non-empty, at most 8,000 characters (the longest Phase 6 summary has 2,029) |
| `textHash` | string | `StableIds.textHash(text)` |
| `textRef` | string | `semantic-context/text/snapshot=<d>/run=<r>/<vectorIndex>/<vectorKey>.txt` |
| `templateVersion` | string | the rag-engineer template version that rendered the text |
| `selectionPolicyVersion` | string | rag-engineer selection policy version |
| `selectionRank` | int | at least 1, the rank under the seeded selection |
| `provenance` | core Provenance | `provenance-v1`, run ID, snapshot, and sourceRecordId (the summary or subject ID) |
| `facts` | object | required, a non-empty JSON object: the structured facts the rag renderer reads (`modules/rag/SELECTION.md`, "Candidate content"; for example path facts carry `sourceName` and `targetName`, paper facts follow `paper-facts-v1`). Added in Phase 7 work order 5. The storage validator checks only presence and shape; the rag render CLI checks the content per document type and refuses a candidate whose `entityIds`, `topicIds`, `institutionIds`, or `communityId` differ from the lists derived from its facts. `facts` never enters vector metadata. |

The candidate file carries the text. The loader writes it once to `textRef`, and vector metadata keeps only the pointer and hash. The metadata derived from every candidate must pass the vector metadata rules. When given bounds, `EmbeddingCandidates.validate` also checks that the total falls inside them (D-13: 10,000 to 15,000).

Selection (ruling 9) belongs to rag-engineer (`modules/rag/SELECTION.md`). Ruling 19 accepted the allocation below (13,019, with the collaboration-path count growing by the benchmark pairs of ruling 25). Available counts come from the Phase 6 manifest.

| Index | Available | Proposed |
|---|---|---|
| researcher-profile-v1 | 54,935 (`all-years`) | 5,000 |
| collaboration-community-v1 | 50,025 (all windows) | 4,000 |
| collaboration-path-v1 | 50 | 50 |
| topic-institution-summary-v1 | 2,969 topic summaries | 2,969 |
| paper-abstract-v1 | 113,205 works | 1,000 |
| Total | | 13,019 |

A seeded rule that fits the store: rank by `StableIds.hash("embed-select", seed, index, vectorKey)` and take the first N per index.

## Rendered-document JSONL (rendered-document-v1)

The rag render CLI reads embedding candidates and writes one JSON object per line, under `<dir>/<index>/part-*.jsonl` (locally `scratch/stores/s3/semantic-context/rendered/snapshot=<d>/run=<r>/<index>/`). This is the embedding runner's input. `RenderedDocuments.validate` is the acceptance test.

| Field | Type | Rule |
|---|---|---|
| `schemaVersion` | string | `rendered-document-v1` |
| `vectorKey` | string | `chunk-<32 hex>`, unique across all files, and equal to `StableIds.chunkId(entityIds[0] + "|" + window, templateVersion, documentType, None, textHash)`: the Phase 6 summaryId formula applied to the rendered text. A changed text therefore always gets a new key (law 2). |
| `index` | string | one of the five indexes |
| `documentType` | string | belongs to the index; equals `metadata.documentType` |
| `text` | string | non-empty, at most 8,000 characters |
| `textHash` | string | `StableIds.textHash(text)` (`sha256:` + 64 hex of the UTF-8 text); equals `metadata.textHash` |
| `templateVersion` | string | rag rendering template version (for example `rendering-v1`); equals `provenance.policyVersion` |
| `embeddingModelId` | string | the Bedrock model the text is for (`amazon.titan-embed-text-v2:0`); the runner refuses a document whose model differs from its embedder |
| `metadata` | object | exactly the S3 Vectors metadata of the vector (the table in "S3 Vectors"): it must pass `VectorContract.metadataProblems`, never holds the text, `window` is a configured window, `runId` equals `provenance.runId`, `embeddingModelVersion` is the rag model-version string, and `textRef` equals `semantic-context/text/snapshot=<provenance.snapshot>/run=<provenance.runId>/<index>/<vectorKey>.txt` |
| `provenance` | core Provenance | `provenance-v1`; `runId` and `snapshot` of the render run; `sourceRecordId` the subject or candidate ID; `policyVersion` = `templateVersion` |

## Embedding runner (rulings 9 and 11)

`EmbeddingRunner` (CLI `EmbedCli`) is a manifest-driven batch with no SQS:
- **Input.** A directory of rendered-document files, sorted by path. Every document is validated before any call; one violation, documents from two snapshots, or (live) a document for another model stops the run with nothing done.
- **Run ID.** `StableIds.runId("embed", "embed-runner-v1", configVersion, [sha256 of each input file..., "model=<modelId>"], 1)`. The same inputs and model give the same run, so rerunning the command resumes it.
- **Embedder port.** `Embedder.embed(text)` throws `TransientStoreException` (retry), `RejectedInputException` (this document is bad), or anything else (stop the run). `HashingEmbedder` is the fake: signed feature hashing of word tokens into 1,024 dimensions, normalized, so texts sharing words are close. A fake never claims a real model: its vectors carry `embeddingModelVersion = "fake:" + <document model version>`. `BedrockTitanEmbedder` is the live Titan Text Embeddings V2 adapter: InvokeModel with `{"inputText", "dimensions": 1024, "normalize": true}`, one text per call, reads `embedding`.
- **Throttle (live only, ruling 29).** The throttle exists for the Bedrock on-demand quota, so the runner applies it only to a real embedder (`isFake = false`, that is `--live`). The fake embedder runs unthrottled, and its ledger `start` entry records `intervalMs = 0`. Live: single-threaded; call starts are at least `ceil(60000 / perMinute)` ms apart, so no 60-second window holds more than `perMinute` starts. `--per-minute` is 1 to 50 (default 50, a 1,200 ms interval). Every live attempt, retries included, passes the throttle. SDK retries are off. Tests use an injected clock and never sleep.
- **Retries.** Transient failures: 3 attempts, 2 s doubling to at most 30 s, with jitter (live). When they run out, the run stops with status `FAILED`; the next run resumes at that document.
- **Cap.** `--max` is required and caps the documents embedded in one session. Requests are at most `--max` x 3.
- **Resume.** State is in `scratch/stores/work/embed/snapshot=<d>/run=<r>/`: `vectors/<index>/part-00000.jsonl` (validated vector records, one appended line each; a partial last line left by a crash is cut on the next start) and `rejected.jsonl`. A document is skipped when its key is already there with the same `textHash` and model version, or when it was rejected earlier in this run.
- **Outputs.** Every vector is checked (`dataProblems`, `metadataProblems`) before it is appended; a bad vector goes to `rejected.jsonl` (index, key, reason, textHash, runId; never data).
- **RunLedger** (file-backed, `scratch/stores/ledger/<runId>.jsonl`). Entry keys `embed#session-<nnnn>#start`, `#progress-<nnnnnn>` every 100 vectors, `#reject#<vectorKey>`, `#failure`, `#end`, and `#status` (`COMPLETE`, `MAX_REACHED`, or `FAILED`).
- **Publish.** Only when nothing is pending, the runner writes to the file object store `scratch/stores/s3/` (write-once): every text at its `textRef`, the vector backup `vectors/backup/snapshot=<d>/run=<r>/<index>/part-00000.jsonl` (sorted by key), the rejected records `vectors/rejected/snapshot=<d>/run=<r>/<index>.jsonl`, and the run manifest `runs/embed/snapshot=<d>/run=<r>/manifest.json`. A republish of the same content is Unchanged.

### Live embedding run (human only, rulings 9 and 26)

Work order 7 rendered 13,023 documents (render run `run-b6343d09bb981330d78f56fb8c3b94d0`). From the repository root, in Terminal:

```
caffeinate -i env SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -batch "storage/runMain scholarchain.storage.cli.EmbedCli --input scratch/stores/s3/semantic-context/rendered/snapshot=2026-10-03/run=run-b6343d09bb981330d78f56fb8c3b94d0 --max 13023 --per-minute 50 --out scratch/stores --live --profile scholarchain --region us-east-2"
```

- Run ID (computed offline from the input hashes and the model): `run-b28edbd40f932f0342c05d5b6f57b46c`; the fake run of the same inputs is `run-13e2b27dca90db05937df33c4accb537`.
- Time: at least 13,023 x 1.2 s = 15,628 s, about 4 h 21 min, plus retries.
- Progress: `cat scratch/stores/work/embed/snapshot=2026-10-03/run=run-b28edbd40f932f0342c05d5b6f57b46c/vectors/*/part-00000.jsonl | wc -l` (vectors done), the same directory's `rejected.jsonl`, and `tail -n 3 scratch/stores/ledger/run-b28edbd40f932f0342c05d5b6f57b46c.jsonl` (a progress entry every 100 vectors).
- Resume: after Ctrl-C, a crash, a sleep, or status `FAILED`, run the same command again. Finished documents are skipped by key, textHash, and model version, and a partial last line is cut. The run publishes only when nothing is pending (status `COMPLETE`).
- Credentials: the shared `RefreshingCredentials` of the named profile (see "Credentials" under Live adapters), refreshed and retried once on an expired-token error. The storage classpath has no `sso`, `ssooidc`, or `sts` SDK module, so a profile that needs SSO (IAM Identity Center needs `sso` and `ssooidc`: [temporary credentials](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-temporary.html), accessed 2026-11-05) or role assumption (`sts`) cannot sign; static keys or `credential_process` work. Adding those artifacts at 2.41.32 is a build-engineer change.

## Live adapters (package `scholarchain.storage.live`)

Live adapters are built only from a `LiveTarget(profile, region)`, which a CLI creates only from `--live --profile <name>` (region default: the core `application.region`, us-east-2). They use the named profile, and SDK retries are off, so the storage `RetryPolicy` is the only retry layer. Throttling, 5xx, and client-side (network or timeout) errors become `TransientStoreException`. The live store loaders also require a non-local configuration (`SCHOLARCHAIN_ENVIRONMENT`), so placeholder names are rejected by the core loader. Every CLI `run` takes the environment as a parameter (default `sys.env`), and the tests pass an explicit one (M8). Unit tests cover request building, response parsing, and credentials wiring (ruling 26). One test builds the five clients, with an empty in-memory profile file and an injected credentials factory, and checks their credentials provider; nothing calls AWS.

### Credentials (ruling 35)

Diagnosis of the 2026-11-05 "security token included in the request is expired" failure (from the code and from `javap` on `auth-2.41.32.jar`; no AWS call):
- Before repair-01, each client got its own `ProfileCredentialsProvider.create(profile)`. For a `credential_process` profile that delegates to `ProcessCredentialsProvider`, which caches the credentials and refreshes them from `Expiration`: prefetch 15 s before (`credentialRefreshThreshold` default 15 s, one caller blocks), stale exactly at `Expiration`. So the credentials were not resolved once at startup, and the provider did not ignore `Expiration`. The case is "something else".
- The amplifier was ours: the expired-token error is a 403, which the storage `RetryPolicy` does not retry, so a single expired request stopped the whole run as `FAILED`.
- Not settled: why one request carried an expired token. Candidates: the process returned credentials at the 15 s prefetch point that expire at the same moment (for example the AWS CLI handing back its cached credentials), so the stale-time refresh got them again; or a client clock behind AWS by more than the 15 s margin. A 15-minute `Expiration` means about four process runs per hour, so a rare boundary race fits one failure in 55 minutes and none in 3.5 hours.

Since repair-01:
- `LiveAws.credentials(target)` holds one `RefreshingCredentials` per profile, shared by all five clients (Bedrock, S3, DynamoDB, Neptune data, S3 Vectors). The SDK calls `resolveCredentials` per request. The delegate (`ProfileCredentialsProvider`) is built on first use, and `refresh()` drops it, so the next request re-reads the profile and reruns `credential_process`. Nothing resolves credentials once at startup.
- `LiveAws.guarded` (every live call): on `ExpiredToken` or `ExpiredTokenException` (or that message), it refreshes the profile's credentials and retries the call once. AWS rejects such a request before acting on it, so the retry cannot repeat an effect. A second expiry propagates as before (not transient). Detection by the code alone is tested with the S3 message text, which lacks the JSON-service phrase (N3).
- **One refresh per expiry (N5).** `RefreshingCredentials` counts refreshes (`generation`). `guarded` notes each profile's generation before the call and, on expiry, calls `refreshIfCurrent(noted)`. So requests that fail together on one expiry (for example `LoadDynamoCli --concurrency 32`) rerun `credential_process` once, not once per thread.
- **The retry passes the throttle (N1).** The retry is a second request. `LiveAws.guardedWith(beforeRetry)` runs the caller's hook before it, and `BedrockTitanEmbedder(client, model, beforeRetry)` passes it through. `EmbedCli` gives the runner's own `Throttle.acquire`, so every Titan attempt, retries included, passes the throttle (rulings 9, 29; Phase 9 ruling 11). Other callers (the rag Haiku client and `SmokeCli`) must pass their throttle the same way.
- **HeadObject (N2).** S3 HEAD error responses have no body, so an expired token there carries no error code. `S3ObjectStore.sha256` therefore treats any 403 on HEAD as a possible expiry (`LiveAws.isHeadExpiredToken`): one refresh and one retry. A real denial fails again and propagates, at the cost of one extra request. Every other call keeps the strict code-or-message rule.
- No change to the embedding output format, vector keys, metadata, or the EmbedCli run-ID inputs.

| Adapter | Ports | Requests |
|---|---|---|
| `S3ObjectStore(client, bucket)` | ObjectReadPort, ObjectLoadPort | PutObject with `If-None-Match: *` and a SHA-256 checksum. On 412 the stored object is read and compared: same bytes `Unchanged`, else `Conflict`. A 409 is retried. GetObject; ListObjectsV2 (paginated). `sha256(key)`: HeadObject with `ChecksumMode = ENABLED`, returning the stored full-object SHA-256 as `sha256:<hex>` (None for a missing object or a composite multipart checksum). |
| `DynamoStore(client, tablePrefix, windows, clock)` | KeyValueReadPort, KeyValueLoadPort, RuntimeRecordPort | PutItem of the canonical item (numbers trimmed, F1) with `attribute_not_exists(#pk)` and `ReturnValuesOnConditionCheckFailure = ALL_OLD`, so a failed condition returns the stored item for the `Unchanged` or `Conflict` decision (numbers compared by value) without a second read. Supersede: `#r = :expected` on `runId`. GetItem (consistent read); Query with `begins_with` on the sort key. The physical table name is `<tablePrefix><Table>`. |
| `KeyValueRunLedger(reader, loader)` | RunLedgerPort | RunLedger items `{runId, entryKey, entryType, recordedAt, schemaVersion run-ledger-v1, details: M}`. |
| `NeptuneBulkLoader(client)` | load request and status only | StartLoaderJob: `source = s3://<bucket>/graph/neptune/snapshot=<d>/run=<r>/`, `format = opencypher`, `userProvidedEdgeIds = true`, `updateSingleCardinalityProperties = false`, `failOnError = true`, `parallelism = MEDIUM`, `queueRequest = true`, `iamRoleArn`, `s3BucketRegion`. GetLoaderJobStatus with details and the first 10 errors, parsed from `overallStatus`. |
| `S3VectorStore(client, vectorBucket)` | VectorReadPort, VectorLoadPort | GetVectors (metadata only, at most 100 keys per call) of the batch keys, then the shared `VectorBatchPlan` applies the contract checks and `VectorWriteRule`, then one PutVectors (at most 500). QueryVectors with the filter rendered in the service syntax (`$eq`, `$and`, ...). |
| `BedrockTitanEmbedder(client, modelId)` | Embedder | InvokeModel, one text per call. A `ValidationException` rejects the document. |

| `NeptuneGraphReader(client, limits)` | GraphReadPort | ExecuteOpenCypherQuery with the fixed templates of "Graph read templates", parameters as JSON, behind the query timeout hint. Read-only credentials. |

Configuration (Phase 7 M10, closed for these two keys by Phase 9 work order 2): DynamoDB table names are the table prefix plus the logical name, and the prefix is `--table-prefix` or else `dynamodb.table-prefix` (`SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX`). The Neptune loader IAM role is `--loader-role-arn` or else `neptune.loader-role-arn` (`SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN`). Live runs need a non-local configuration, which refuses the placeholder values.

## CLIs (`sbt "storage/runMain <class> ..."`)

All CLIs run from the repository root, default to local fakes under `scratch/stores/`, and exit 0 on success, 1 on violations or failed work, 2 on a usage error, and 3 when free disk under the output directory is below 5 GiB (ruling 27; report BLOCKED-ON). Any uncaught Throwable, including a JVM error such as `OutOfMemoryError`, prints `<Cli> failed: ...` and exits 1 (`Cli.exitCode`, M1), so a failure never ends as sbt `[success]`. Store files are validated in full before anything is written.

| Class | Purpose |
|---|---|
| `scholarchain.storage.cli.ValidateCli --kind neptune\|dynamodb\|candidates\|rendered\|vectors --dir <path> [--bounds lo:hi] [--evidence-root <s3 fake root>] [--show n]` | Streams and validates a directory. Prints counts, then per rule the count and the first `--show` (default 5) violations. `neptune` also prints the evidence-tracing counts (`evidence ...`) and the four evidence rules (`rule ...`, zero included); without `--evidence-root`, edges that cite evidence objects are untraced and the run exits 1 with `INCOMPLETE` (N6). `dynamodb` takes one table directory or a directory of table directories. |
| `scholarchain.storage.cli.EmbedCli --input <rendered dir> --max <n> [--out scratch/stores] [--per-minute 1..50] [--live --profile <name> [--region r]]` | Embedding runner. The live model is `models.embedding-model` (`SCHOLARCHAIN_EMBEDDING_MODEL_ID`). |
| `scholarchain.storage.cli.LoadNeptuneCli --dir <out>/s3/graph/neptune/snapshot=<d>/run=<r> [--out scratch/stores] [--passes n] [--expect-coauthor <csv>]` | Dry run: validate, then load into the in-memory graph `--passes` times. `--expect-coauthor` (dry run only) then looks up every row of a CSV `window,researcherX,researcherY,jointPublicationCount,firstCollaborationYear,lastCollaborationYear` with `coauthorEdge` and prints `edge-check OK|MISSING|MISMATCH` with the found edge ID; any miss exits 1. Live: `--live --profile <name> [--loader-role-arn <arn>]` (default `neptune.loader-role-arn`) validates the local copy, then checks content hashes against the store-conversion run manifest (`runs/store-conversion/snapshot=<d>/run=<r>/manifest.json`, M3): the local copy and the live bucket must both hold exactly the manifest's objects under the load prefix, the local files must hash to the manifest's SHA-256, and each live object's stored SHA-256 checksum (HeadObject, written by `LoadS3Cli`) must equal it. Any difference prints `pre-load:` lines and exits 1 before a load starts. Then it starts one load; `--live --profile <name> --status <loadId>` reads its status. |
| `scholarchain.storage.cli.LoadDynamoCli --dir <out>/s3/curated/dynamodb/snapshot=<d>/run=<r> [--out scratch/stores] [--passes n] [--recheck]` | Dry run: in-memory fake. Live: `--live --profile <name> [--table-prefix <p>] [--concurrency 1..32, default 8]` (prefix default `dynamodb.table-prefix`). Runtime tables (QuerySession, EvidenceBundle) are refused. |
| `scholarchain.storage.cli.LoadVectorsCli --input <out>/s3/vectors/backup/snapshot=<d>/run=<r> [--out scratch/stores] [--batch-size 1..500] [--supersede]` | Dry run: file vector store `<out>/s3vectors/`. Live: `--live --profile <name>` into `s3-vectors.vector-bucket`. |
| `scholarchain.storage.cli.LoadS3Cli --manifest runs/<stage>/snapshot=<d>/run=<r>/manifest.json [--root scratch/stores/s3]` | Dry run: verifies the manifest against the file store. Live: `--live --profile <name>` writes every listed object write-once, then the manifest, then verifies the manifest against the bucket. |

`sbt` runs `storage/runMain` in its own JVM (the storage project does not fork). For the in-memory graph or DynamoDB dry runs on full data, start sbt with a larger heap, at most 6 GB (ruling 27): `sbt -J-Xmx6g "storage/runMain ..."`.

Loader run IDs: `StableIds.runId("load-<store>", "load-<store>-v1", configVersion, ["source=<source run>", <content hash>, ...], 1)`. Each invocation (and each dry-run pass) appends a `session-<nnnn>` entry to the RunLedger.

## Service limits (current AWS documentation, accessed 2026-11-05)

| Limit | Value | Source |
|---|---|---|
| S3 object key length | 1,024 UTF-8 bytes | https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html |
| Neptune openCypher CSV | UTF-8, RFC 4180, system headers `:ID`, `:LABEL`, `:START_ID`, `:END_ID`, `:TYPE`; types Bool, Byte, Short, Int, Long, Float, Double, String, DateTime | https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html |
| Neptune duplicate IDs, `userProvidedEdgeIds`, `updateSingleCardinalityProperties` | duplicates are merged non-deterministically; existing properties are not updated by default | https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html |
| Neptune property or label size | 55 MB | https://docs.aws.amazon.com/neptune/latest/userguide/limits.html |
| Neptune load jobs | at most 64 queued; 1,024 tracked; 10,000 error details per job | https://docs.aws.amazon.com/neptune/latest/userguide/limits.html |
| DynamoDB item size | 400 KB | https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html |
| DynamoDB key length | partition key 2,048 bytes, sort key 1,024 bytes | https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html |
| DynamoDB BatchWriteItem | 25 requests, 16 MB, no conditions | https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html |
| DynamoDB write unit | 1 KB per WRU, rounded up; failed conditional writes consume WRU; transactional writes cost 2x | https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/read-write-operations.html |
| DynamoDB on-demand initial throughput | 4,000 writes/s, 12,000 reads/s per new table | https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/on-demand-capacity-mode.html |
| DynamoDB TTL | Number attribute, epoch seconds; deletion within a few days, without write units | https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html |
| S3 Vectors dimension | 1 to 4,096 | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html |
| S3 Vectors metadata | 40 KB total, 2 KB filterable, 50 keys per vector, 10 non-filterable keys per index | same |
| S3 Vectors calls | PutVectors and DeleteVectors 500 vectors per call; GetVectors 100; QueryVectors top-K 10,000; 100 filter constraints (ENHANCED); 20 MiB payload | same |
| S3 Vectors throughput | 1,000 PutVectors plus DeleteVectors requests/s, and 2,500 vectors inserted or deleted per second, per index | same |
| S3 Vectors key | 1 to 1,024 characters; for cosine, no zero vectors; no NaN or Infinity | https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_PutInputVector.html |
| S3 Vectors index settings | name, dimension, distance, and non-filterable keys are fixed at creation | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-indexes.html |
| Titan Text Embeddings V2 | `dimensions` 1024 (default), 512, or 256; `normalize` defaults to true | https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-titan-embed-text.html |
| Bedrock embedding quota | 60 requests per minute (account quota per ruling 9; the runner throttles to 50) | human ruling 9 |
| Titan V2 response | `embedding` (float array), `inputTextTokenCount`, `embeddingsByType` | https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-titan-embed-text.html |
| S3 conditional writes | `If-None-Match: *` on PutObject fails with 412 when the key exists | https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html |
| DynamoDB Number range | positive 1E-130 to 9.9999999999999999999999999999999999999E+125, negative likewise, and 0; 38 digits (accessed 2026-11-06) | https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html |
| Neptune openCypher clauses | `shortestPath()` and `allShortestPaths()` not supported; `SKIP`/`LIMIT` need static values (a bound parameter is used); `CALL` not supported (accessed 2026-11-06) | https://docs.aws.amazon.com/neptune/latest/userguide/feature-opencypher-compliance.html |
| Neptune openCypher query timeout | `USING QUERY:TIMEOUTMILLISECONDS <n>`; the effective timeout is the smaller of it and `neptune_query_timeout`; engine 1.4.6.0 or later (accessed 2026-11-06) | https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-query-hints-timeout-hint.html |
| Neptune ExecuteOpenCypherQuery | body `query` and `parameters` (JSON string); response `results`; IAM action `neptune-db:ReadDataViaQuery` for reads (accessed 2026-11-06) | https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html |
| DynamoDB Number | up to 38 digits of precision; "Leading and trailing zeroes are trimmed" (accessed 2026-11-06) | https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html |
| DynamoDB failed-condition item | `ReturnValuesOnConditionCheckFailure = ALL_OLD` returns the stored item | https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/WorkingWithItems.html |
| Neptune loader status | `overallStatus.status` (`LOAD_COMPLETED` on success), `totalRecords`, `totalDuplicates`, `parsingErrors`, `datatypeMismatchErrors`, `insertErrors` | https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-status-response.html |
| S3 Vectors filter syntax | `$eq` (matches any element of a list), `$ne`, `$gt`, `$gte`, `$lt`, `$lte`, `$startsWith` (ENHANCED), `$in`, `$nin`, `$exists`, `$and`, `$or`; 100 constraints per query (ENHANCED) | https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-metadata-filtering.html |

## Cleanup (run-scoped test data only)

- Unit tests create everything in fresh JVM temporary directories (`Files.createTempDirectory("storage-...")`) and delete only those directories. They never read or write `scratch/`.
- Local dry runs write only under `scratch/stores/` with a snapshot and run ID in every path: `s3/` (file object store), `s3vectors/` (file vector store, one log per index), `work/<stage>/snapshot=<d>/run=<r>/` (runner and loader working files), and `ledger/<runId>.jsonl`. Removing a dry run means deleting exactly its `run=<runId>` directories and its ledger files, and only by the human (`scripts/cleanup.sh`). The `s3vectors/` logs are shared across runs; resetting the fake vector store means deleting that directory, again only by the human.
- The bounded live integration tests (cloud window 2, opt-in, still an open gate item) will write only under a run-scoped prefix and run-scoped keys, and delete only those. They never delete shared data, tables, buckets, or indexes.
