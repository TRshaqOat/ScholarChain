# ScholarChain Hadoop jobs

Owner: hadoop-engineer. Code: `modules/hadoop/src/main/scala/scholarchain/hadoop/`. Tests: `modules/hadoop/src/test/scala/scholarchain/hadoop/`.

All jobs use the Java MapReduce API (`org.apache.hadoop.mapreduce`) from Scala 3 (D-03). The driver `scholarchain.hadoop.Pipeline` runs the chain in sequence. J03 and J04 are merged into one job, and so are J07 and J08. J05 is one MapReduce job per blocking level (passes p0, p1, p2). Normalization, blocking keys, the oversize-block rule, and match features are the entity-resolution-engineer's pure functions in `modules/entity-resolution` (`Normalize`, `BlockingKeys`, `OversizePolicy`, `Features`, rule versions `norm-v1`, `block-v1`, `feat-v1`); the jobs apply them and never redefine them.

```text
DBLP works.xml + DTD ──> J01 dblp-extract ──────┬──> J03+J04 exact-identifier ─────────────────┐
OpenAlex / Crossref / ORCID JSONL ──> J02 ──────┤                                              │
                                                ├──> J05 p0 ─rekey/─> J05 p1 ─rekey/─> J05 p2  │
                                                │      └──────blocks/──────┴──────blocks/──┴─> J06 candidate-join ─┤
J01 works/ ─────────────────────────────────────┴──> J07+J08 team-pair-expansion ─────────────┤
J01, J02, J03+J04, J06, J07+J08 outputs ──────────────────────────────────────> J09 graph-export (Spark-ready)
```

Ordering: J01, J02, J03+J04, J05 p0, J05 p1, J05 p2, J06, J07+J08, J09. J05 pass k (k > 0) runs only if pass k-1 wrote `rekey/` (some block was re-keyed); J06 runs only if some pass wrote `blocks/`. A skipped job has no directory and no `_counters` file.

## Conventions shared by every job

| Item | Rule |
|---|---|
| Record format | JSON Lines, one core record per line, encoded with the core uPickle codecs (`WireCodec.encode`). Spark reads them with `spark.read.json`. |
| Shuffle types | Map output key `Text`, value `Text` (a JSON record). Job output key `NullWritable`, value `Text`. |
| Partitioner | `HashPartitioner` on the map output key, set explicitly. The key is `<kind>\t<stable ID>`, the identifier itself in J03+J04, the blocking key in J05, `<entityType>\t<sourceId>\t<targetId>` in J06, or the work ID in J07+J08. All records with one key meet in one reduce call. |
| Reducers | `--reducers` (default 2), the same for every job. Output is deterministic for a fixed reducer count. |
| Combiner | None. Every reducer needs the whole group (dedupe, conflict detection, or pair expansion), so a combiner cannot shrink it. |
| Output writer | `MultipleOutputs` under `LazyOutputFormat` (no empty part files), so every output lands in `<job dir>/<dir>/part-r-NNNNN.gz`. |
| Compression | Map output uses `DefaultCodec` (zlib, `mapreduce.map.output.compress=true`). Final output uses `GzipCodec`. Both have pure-Java fallbacks, so no native library is needed. |
| Determinism | Tasks hold only per-task state built in `setup` (parser, run context, output writer). Reducers sort their values before choosing, so output does not depend on shuffle order. Chosen records are the smallest (works) or the most frequent, ties broken by the smallest (entities). A rerun differs only in `provenance.runId`, and `PipelineSpec` checks this. |
| Provenance | Every record carries `Provenance`. Source records keep their dataset, source record ID, and dataset snapshot. Teams and pairs keep the DBLP work's source provenance. Match candidates are `DERIVED`, with `sourceRecordId = <key>|<sourceId>|<targetId>` and the DBLP snapshot; blocked candidates (J06) also carry `policyVersion = norm-v1.block-v1.feat-v1`. Graph edges carry `graph.policy-version`. |
| Run ID | `StableIds.runId("hadoop-pipeline", --code-version, config-version, [dblp=<d>, openalex=<d>, crossref=<d>, orcid=<d>], attempt)`. The attempt is `--attempt <n>` when given (run scripts pass it; the run fails before any job if that attempt's run directory exists). Otherwise the driver uses the smallest attempt (>= 1) whose run directory does not exist, so every local rerun of the same inputs gets a new attempt. `_manifest.json` (`hadoop-run-manifest-v2`) is created with overwrite disabled, which claims the attempt; it records the attempt, inputs, reducers, `graph.oversize-team-threshold`, `entity-resolution.max-block-size`, `entity-resolution.max-conflict-group-size`, and the entity-resolution rule versions. |
| Paths | `<--out>/snapshot=<dblp snapshot>/run=<runId>/<job>/<dir>/`, plus `_manifest.json` and `_counters/<job>.json` in the run directory. Inputs and output are arguments, so local paths and `s3a://` URIs both work (EMRFS is removed in emr-spark-8.0.0). On EMR: inputs `s3a://<raw-bucket>/<dataset>/snapshot=<date>/...`, output root `s3a://<curated-bucket>/hadoop`. |
| Idempotency | Output is never overwritten. A new attempt writes a new run directory, and `FileOutputFormat` refuses an existing job directory. Consumers read one run directory, named in its manifest. |
| Config | `ConfigLoader.load()` supplies `config-version`, `graph.oversize-team-threshold`, `graph.policy-version`, `entity-resolution.max-block-size` (env `SCHOLARCHAIN_ER_MAX_BLOCK_SIZE`), and `entity-resolution.max-conflict-group-size` (env `SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE`). The driver copies them into the job configuration (`scholarchain.*` keys) for tasks to read. |
| Rejects | Each job writes `rejects-v1/` lines with the `RejectRecord` format, `schemaVersion = "hadoop-reject-v1"`: `job`, `reason`, `sourceDataset`, `sourceLocation` (`<file>@<byte offset>`), `sourceRecordId`, `detail`, `rawExcerpt` (first 2,000 characters), `snapshot`, and `runId`. |

### Counters

Group `scholarchain` is registered at zero in every map task, so every counter is always visible: `INPUT`, `ACCEPTED`, `REJECTED`, `DUPLICATE`, `CONFLICT`, `OVERSIZE_BLOCK`, `OVERSIZE_CONFLICT`, `OVERSIZE_TEAM`, `EMITTED_PAIR`, `MISSING_KEY`, and `SCHEMA_FAILURE`. Extra counters: `INVALID_ORCID` and `REPEATED_AUTHOR_NAME` (J01); `EXACT_MATCH` and `EXACT_UNMATCHED` (J03+J04); `BLOCK_MEMBER`, `BLOCK_KEPT`, and `BLOCK_REKEYED` (J05); `SAME_WORK_PAIR_SKIPPED` and `MATCH_CANDIDATE` (J06). Group `scholarchain.outputs` counts lines written per output directory. In mappers, `INPUT = ACCEPTED + REJECTED`. `DUPLICATE` and `CONFLICT` are counted in reducers, among accepted records.

| Reason code | Counter besides `REJECTED` | Raised by |
|---|---|---|
| `missing-key` | `MISSING_KEY` | J01 record without `key`; J02 OpenAlex without `id`, Crossref without `DOI`, ORCID without `orcid` |
| `schema-validation` | `SCHEMA_FAILURE` | Any record failing core `Validation` (for example a DOI work ID with spaces), a malformed DBLP year, an author without a name, or an invalid upstream record read by J03+J04, J07+J08, or J09 |
| `malformed-record` | `SCHEMA_FAILURE` | Unparseable XML or JSON, a truncated DBLP record, a JSON value of the wrong type |
| `unknown-entity` | `SCHEMA_FAILURE` | A DBLP entity reference not declared in the DTD |
| `invalid-affiliation` | `SCHEMA_FAILURE` | J02: a Crossref affiliation or ORCID employment name with no letters or digits (`StableIds.affiliationNameId` returns Left) |
| `unsupported-record-type` | none | DBLP record types other than `article` and `inproceedings` |
| `unsupported-identifier` | none | An ORCID record whose iD fails the core check (including `synthetic:` fixtures) |
| `conflicting-duplicate` | `CONFLICT` (not `REJECTED`) | Reducer: two different records with one stable ID; the smallest is kept and the rest go to rejects |

## J01 DblpExtractJob (`j01-dblp-extract`)

- **Input format:** `DblpInputFormat`. Key `LongWritable` is the byte offset of the record's `<`. Value `Text` is the complete record element, decoded from ISO-8859-1 to Unicode. A record belongs to the split that holds its first byte. The reader reads past the split end to finish it, and the next split skips to the first record start inside itself. Records never nest, so the first matching end tag closes one. Recognized start tags are every DTD record element followed by whitespace or `>` (`<booktitle>` is not `<book>`). Compressed input is one split.
- **Mapper `DblpExtractMapper`:** reads the DTD (`--dblp-dtd`) in `setup` and builds the entity map from `<!ENTITY name "&#N;">` declarations. Per record, it replaces entity references with their declared numeric references (an undeclared entity is a reject), parses with StAX (no DTD, no external entities, coalescing), and maps the record to `WorkRecord` with embedded `AuthorMention`s and a DBLP `Venue`. Output: (`works\t<workId>`, WorkRecord), (`venues\t<venueId>`, Venue), or (`rejects-v1\t<file>@<offset>`, RejectRecord).
- **Field mapping:** `workId = dblp:<key>`, `sourceRecordId = key`, raw `title` (inline markup text kept, tags dropped, line breaks kept), `year`, `workType` (element name), `doi` (first `ee` on a doi.org resolver, through `StableIds.normalizeDoi`), `arxivId` (`ee` on `https://arxiv.org/abs/`), `venueId = dblp-venue:<key prefix>`. `externalIds` holds `venue` (booktitle or journal), `volume`, `number`, `pages`, `url`, `crossref`, `mdate`, `publtype`, `ee.N`, and `ee.N.type`. `normalizedTitle = Normalize.title(title)` (None when empty). Each author becomes mention `workId#position`, with `rawName` (keeping homonym suffixes such as `0001`), `normalizedName = Normalize.name(rawName).comparable` (homonym suffix dropped, "Family, Given" reordered, Unicode folded), and `orcid` (through `StableIds.normalizeOrcid`; an invalid iD becomes `None` and counts `INVALID_ORCID`). Repeated author names keep separate positions and count `REPEATED_AUTHOR_NAME`.
- **Reducer `NormalizedRecordReducer`:** input (`<kind>\t<id>`, [JSON]). For works, identical repeats count `DUPLICATE`, and differing repeats count `CONFLICT`, keep the smallest record, and reject the others. The reducer writes `works/` and the work's authors to `mentions/` (these mentions are the author-paper records keyed by mention ID and work ID). Entities: the most frequent value is written to `venues/`. Rejects pass through to `rejects-v1/`.
- **Outputs:** `works/`, `mentions/`, `venues/`, `rejects-v1/`.
- **Test fixtures:** `DblpRecordReaderSpec` (hand-built ISO-8859-1 file split at every byte offset and in 1-, 2-, 3-, 17-, 64-, and 257-byte splits, the committed sample in 4 KB splits, a truncated record, gzip input), `DblpParserSpec` (DTD entities, numeric references, builtins, undeclared entity, multiline titles with markup, Unicode, missing optional fields, repeated authors, ORCID checksum, DOI and arXiv, rejects), and `PipelineSpec`.

## J02 SourceNormalizeJob (`j02-source-normalize`)

- **Input format:** `TextInputFormat` (gzip read transparently) over `--openalex`, `--crossref`, and `--orcid`. Key is the byte offset, value is one JSON line.
- **Mapper `SourceNormalizeMapper`:** finds the source dataset whose configured input path (`scholarchain.j02.inputs.<SOURCE>`) contains the split's file, then applies that dataset's normalizer in `SourceNormalizers`. Output: (`<kind>\t<id>`, JSON) for kinds `works`, `venues`, `institutions`, `topics`, `edges`, and `researcher-profiles`, or a reject.
  - **OpenAlex:** `workId = openalex:W...`, `doi`, `title`, `publication_year`, `type`, and `venueId = openalex:S...` (a `Venue` with `issn_l`). `externalIds` holds the `ids` entries other than doi, plus `raw_source_name`. Authorships in array order become mentions with `rawName = raw_author_name` (falling back to `display_name`), `orcid`, `sourceAuthorId = openalex:A...`, and `institutionIds` (`ror:<id>`, else `openalex:I...`), each with an `Institution`. `primary_topic` becomes a `Topic` plus a `HAS_TOPIC` `GraphEdge` (work to topic).
  - **Crossref:** `workId = doi:<normalized DOI>`, `title[0]`, the year from `issued.date-parts[0][0]`, `type`, and `venueId = issn:<ISSN[0]>` (a `Venue` named by `container-title`). `externalIds` holds `publisher`, `member`, `URL`, `page`, `volume`, `issue`, `container-title`, and `issn.N`. Authors become mentions named `name`, or else `given family`, with `ORCID`. Each named affiliation becomes `StableIds.affiliationNameId(name)` (`sci:affn-v1:<hash>`, core) plus an `Institution`; a Left result rejects the record (`invalid-affiliation`).
  - **ORCID** (the projection in datasets/schemas/orcid.md): a valid iD becomes a `GraphNode` (label `Researcher`, `nodeId = orcid:<iD>`). Its properties hold the given, family, and credit names, `otherName.N`, `externalId.N.type` and `externalId.N.value`, `workDoi.N`, and `lastModifiedMs`. Each employment becomes an `Institution` (`<org_id source lower-case>:<id>`, else `StableIds.affiliationNameId(org)`; a Left result rejects the record) and an `AFFILIATED_WITH` edge carrying department, role, years, and country.
- **Reducer:** `NormalizedRecordReducer` (as in J01).
- **Outputs:** `works/`, `mentions/`, `venues/`, `institutions/`, `topics/`, `edges/`, `researcher-profiles/`, `rejects-v1/`.
- **Test fixtures:** `SourceNormalizersSpec` (the committed samples plus malformed, missing-ID, wrong-type, nameless-author, and synthetic-iD cases) and `PipelineSpec`.

## J03+J04 ExactIdentifierIndexJob + ExactIdentifierJoinJob (`j03-j04-exact-identifier`)

- **Input format:** `TextInputFormat` over the `works/` and `mentions/` directories of J01 and J02. The mapper takes the record type from the parent directory name.
- **Mapper `ExactKeyMapper`:** works emit (`doi:<doi>` or `arxiv:<id>`, `IndexEntry`), and a work with neither counts `MISSING_KEY`. Mentions emit (`orcid:<iD>`, `IndexEntry`) and (`openalex:A...`, `IndexEntry`). `IndexEntry {kind, id, source, workId}` exists only inside this job's shuffle. The shuffle is the keyed index.
- **Reducer `ExactJoinReducer`:** input (identifier, [IndexEntry]). The rules are in `ExactIdentifiers.resolve`:
  - Repeated members count `DUPLICATE`.
  - Work keys: if one source has two or more works under the key, the reducer counts `CONFLICT`, writes every within-source pair to `conflicts/`, and matches nothing. Otherwise each non-DBLP work becomes a `MatchCandidate(WORK, sourceId = that work, targetId = the DBLP work, blockingKey = key)`. With no DBLP work the key counts `EXACT_UNMATCHED`.
  - Researcher keys: two or more mentions of one work under one key are a conflict. Every other mention becomes a `MatchCandidate(RESEARCHER, mention, key)`.
  - Conflict cap: a conflicting group (one source's works under a work key, or one work's mentions under a researcher key) with more than `entity-resolution.max-conflict-group-size` members writes no pairs. It counts `OVERSIZE_CONFLICT` (still `CONFLICT`, still no match) and writes one `oversize-conflicts/` line `{job, identifier, entityType, groupSize, maxConflictGroupSize}`.
  - Feature maps are empty: exact-identifier matches are not blocked candidates.
  - Each reducer writes one `stats/` line with its largest identifier group.
- **Outputs:** `matches/`, `conflicts/`, `oversize-conflicts/`, `stats/`, `rejects-v1/`.
- **Test fixtures:** `LogicSpec` (key functions; match, duplicate, conflict, unmatched, and conflict-cap cases) and `PipelineSpec` (80 DOI work matches on the samples).

## J05 BlockingKeyJob (`j05-blocking-key-p0`, `-p1`, `-p2`)

One MapReduce job per blocking level; pass k groups by the level-k keys. Researcher and work members share each pass (their keys never collide: `r<k>:` and `w<k>:` prefixes from `BlockingKeys`).

- **Input format:** `TextInputFormat`. Pass 0 reads J01 and J02 `works/`; pass k > 0 reads pass k-1 `rekey/`.
- **Mapper `BlockingKeyMapper`:**
  - Pass 0: (offset, WorkRecord JSON) -> (level-0 key, `BlockMember` JSON) for the work (`BlockingKeys.work(title, year)`) and for each author mention (`BlockingKeys.researcher(rawName, coauthor raw names)`). A member with no key counts `MISSING_KEY` and is not blocked. Counts `BLOCK_MEMBER` per keyed member.
  - Pass k > 0: (offset, `BlockMember` JSON) -> (level-k key, `BlockMember` JSON), once per level-k key (`r2` has one key per coauthor family name).
  - `BlockMember {entityType, id, keys, work, position}` (hadoop-internal) carries the keys of every level, computed once in pass 0, and the whole WorkRecord, because the features need the mention's coauthors and team size.
- **Reducer `BlockingKeyReducer`:** (key, [BlockMember]) -> by `OversizePolicy.action(level, size, max-block-size, levels)`:
  - Keep (size <= `entity-resolution.max-block-size`): one `BlockRecord {schemaVersion = hadoop-block-v1, entityType, level, key, members sorted by ID}` in `blocks/`; counts `BLOCK_KEPT`.
  - Rekey (a further level exists): every member in `rekey/` for the next pass; counts `BLOCK_REKEYED`.
  - ReportOversize (still too large at the entity's last level): one `OversizeBlockRecord {schemaVersion, entityType, level, key, size}` in `oversize/`; counts `OVERSIZE_BLOCK`. These members get no candidates (split, never merged).
  - Bounded memory (`Blocking.group`): values are read once. Members are buffered, distinct by ID, until the group exceeds the maximum; from then on the buffer and every later value stream to `rekey/` (or are only counted, at the last level). At most max-block-size + 1 members are held. Member IDs are unique by construction, so values streamed after the overflow are not de-duplicated.
  - Each reducer writes one `stats/` line: `level`, `maxBlockSize`, the largest group (size and key, before any re-keying) and the largest kept block.
- **Combiner:** none (the size decision needs the whole group). **Partitioner:** `HashPartitioner` on the blocking key.
- **Outputs per pass:** `blocks/`, `rekey/` (intermediate), `oversize/`, `stats/`, `rejects-v1/`.
- **Equivalence:** the passes together produce exactly the blocks and oversize reports of the entity-resolution oracle `OversizePolicy.plan`. `BlockingSpec` checks this in memory for maximum sizes 1, 2, 3, and 100; `PipelineSpec` checks it on the job's real output for the samples.
- **Test fixtures:** `BlockingSpec` (members and keys, a hand-checked max-2 run, buffering and streaming, oracle equivalence) and `PipelineSpec` (max-block-size 2 through `SCHOLARCHAIN_CONFIG_FILE`, so every pass runs and oversize blocks occur).

## J06 CandidateJoinJob (`j06-candidate-join`)

- **Input format:** `TextInputFormat` over the `blocks/` directories of every J05 pass.
- **Side input:** the J01 and J02 `venues/` directories (key `scholarchain.j06.venue-dirs`), read once in mapper `setup` into a venue-ID-to-name map for the venue feature. The table holds one entry per venue, not per work.
- **Mapper `CandidatePairMapper`:** (offset, BlockRecord JSON) -> (`<entityType>\t<sourceId>\t<targetId>`, JSON `[blockingKey, features]`) for every member pair of the block, with sourceId < targetId (members are sorted, which is the `CanonicalPair` order). Features: `Features.researcher(mentionA, workA, mentionB, workB)` or `Features.work(workA, venueNameA, workB, venueNameB)` (feat-v1; absent features are left out). Two mentions of the same work are never paired (one person holds one author slot) and count `SAME_WORK_PAIR_SKIPPED`. A block of n members (n <= max-block-size) yields at most n(n-1)/2 pairs.
- **Reducer `CandidateJoinReducer`:** (pair key, [[blockingKey, features]]) -> one `MatchCandidate` in `candidates/`: `blockingKey` = the smallest key among the blocks where the pair met (r2 is multi-key, so a pair can meet more than once), the features, `DERIVED` provenance with `policyVersion = norm-v1.block-v1.feat-v1`. Extra meetings count `DUPLICATE`; each written candidate counts `MATCH_CANDIDATE`; a candidate failing core validation is rejected (`SCHEMA_FAILURE`). No thresholds, scores, or decisions (Phase 5).
- **Combiner:** none. **Partitioner:** `HashPartitioner` on the pair key.
- **Outputs:** `candidates/`, `rejects-v1/`.
- **Test fixtures:** `BlockingSpec` (pairs, same-work skip, features equal to the ER functions, venue side input, candidate validation) and `PipelineSpec` (one candidate per distinct pair of the kept blocks, smallest blocking key, `DUPLICATE` equals extra meetings, export count).

## J07+J08 PublicationTeamJob + CoauthorPairExpansionJob (`j07-j08-team-pair-expansion`)

- **Input format:** `TextInputFormat` over J01 `works/` (every accepted DBLP work is retained).
- **Mapper `TeamMapper`:** emits (`workId`, WorkRecord JSON) after validation.
- **Reducer `TeamPairReducer`:** input (workId, [WorkRecord]). It writes one `PublicationTeam` (mention IDs in author order) to `teams/`. It writes all n(n-1)/2 `CoauthorPairCandidate`s to `pairs/`, each in `CanonicalPair` order, with the work ID, year, author positions (the `#n` suffix of each mention ID), and the work's source provenance. It counts `EMITTED_PAIR` and, when n exceeds `graph.oversize-team-threshold`, `OVERSIZE_TEAM`; oversize teams still emit all their pairs. Each reducer writes one `stats/` line: its largest team, that team's pair count, and a team-size histogram. Author-paper records are J01's `mentions/`.
- **Outputs:** `teams/`, `pairs/`, `stats/`, `rejects-v1/`.
- **Test fixtures:** `LogicSpec` (hand-checked pairs for 0, 1, 2, 3, 4, and 12 authors; 1,000 authors gives 499,500) and `PipelineSpec` (pair total equals the sum of n(n-1)/2 over the sample XML; `OVERSIZE_TEAM` at threshold 5).

## J09 GraphCandidateExportJob (`j09-graph-export`)

- **Input format:** `TextInputFormat` over every data directory of J01, J02, J03+J04, J06, and J07+J08 (not `rejects-v1/`, `stats/`, `oversize-conflicts/`, or J05's intermediate directories).
- **Mapper `ExportMapper`:** decodes and validates each record according to its directory (`Export.route`) and emits (`<export dir>\t<stable ID>`, JSON). A record failing validation counts `SCHEMA_FAILURE` and is rejected.
- **Reducer `ExportReducer`:** writes one record per ID. Identical repeats count `DUPLICATE`, and differing repeats count `CONFLICT` and are rejected.
- **Outputs** (Hive-style partitions; `__HIVE_DEFAULT_PARTITION__` reads as null in Spark; partition column names differ from record field names, so they do not clash):
  - `works/source_dataset=<DBLP|OPENALEX|CROSSREF>/publication_year=<y>/`
  - `author-mentions/source_dataset=<ds>/`
  - `publication-teams/publication_year=<y>/`
  - `coauthor-pair-candidates/publication_year=<y>/`
  - `match-candidates/entity_type=<WORK|RESEARCHER>/`
  - `exact-id-conflicts/entity_type=<...>/`
  - `blocking-candidates/entity_type=<WORK|RESEARCHER>/` (J06 candidates with features)
  - `graph-edges/edge_type=<HAS_TOPIC|AFFILIATED_WITH>/`
  - `venues/`, `institutions/`, `topics/`, `researcher-profiles/`, `rejects-v1/`
- **Test fixture:** `PipelineSpec` (partition layout, validation of every pair, a rerun equal apart from the run ID).

## Running locally

```text
sbt "hadoop/Test/runMain scholarchain.hadoop.Pipeline --out scratch/hadoop/samples --code-version <git sha> \
  --dblp datasets/samples/dblp/dblp-sample.xml --dblp-dtd datasets/samples/dblp/dblp-2023-06-28.dtd --dblp-snapshot 2026-10-03 \
  --openalex datasets/samples/openalex/works-sample.jsonl --openalex-snapshot 2026-11-03 \
  --crossref datasets/samples/crossref/works-sample.jsonl --crossref-snapshot 2026-11-03 \
  --orcid datasets/samples/orcid --orcid-snapshot 2026-11-03 --reducers 2 [--attempt <n>]"
```

The run uses `Test/runMain` because Hadoop is a `Provided` dependency, so it is on the test classpath but not on `Compile/run`. Hadoop `-D` options come before the `--` arguments. On EMR, the job jar from `sbt packageJobs` runs the same main class through the infra-engineer's scripts.

## Not implemented

- **DBLP person records (`persons.xml`) and non-projection ORCID records** (raw `records.jsonl.gz`) are not read.
- **OpenAlex API list pages** (`scratch/datasets/openalex/full/raw/*.json`) are not an input format; J02 reads the JSON Lines the dataset scripts produce.
