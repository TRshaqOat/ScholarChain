# ScholarChain Hadoop jobs

Owner: hadoop-engineer. Code: `modules/hadoop/src/main/scala/scholarchain/hadoop/`. Tests: `modules/hadoop/src/test/scala/scholarchain/hadoop/`.

All jobs use the Java MapReduce API (`org.apache.hadoop.mapreduce`) from Scala 3 (D-03). The driver `scholarchain.hadoop.Pipeline` runs the chain in sequence. J03 and J04 are merged into one job, and so are J07 and J08. J05 is one MapReduce job per blocking level (passes p0, p1, p2). J10 is map-only. Normalization, blocking keys, the oversize-block rule, match features, the work-pair features, and the match policy are the entity-resolution-engineer's pure functions in `modules/entity-resolution` (`Normalize`, `BlockingKeys`, `OversizePolicy`, `Features`, `WorkPairFeatures`, `MatchPolicy`, rule versions `norm-v1`, `block-v1`, `feat-v1`, policy `er-v1` or `er-v2`, selected by `entity-resolution.policy-version`); the jobs apply them and never redefine them. No job calls adjudication, Bedrock, or any LLM client, and the job jar bundles no AWS SDK.

```text
DBLP works.xml + DTD ──> J01 dblp-extract ──────┬──> J03+J04 exact-identifier ─────────────────┐
OpenAlex / Crossref / ORCID JSONL ──> J02 ──────┤                                              │
                                                ├──> J05 p0 ─rekey/─> J05 p1 ─rekey/─> J05 p2  │
                                                │      └──────blocks/──────┴──────blocks/──┴─> J06 candidate-join ─┤
J03+J04 matches/ conflicts/, J06 candidates/ work-pairs/ ──> J10 match-decision (map-only) ───┤
J01 works/ ─────────────────────────────────────┴──> J07+J08 team-pair-expansion ─────────────┤
J01, J02, J03+J04, J06, J10, J07+J08 outputs ─────────────────────────────────> J09 graph-export (Spark-ready)
```

Ordering: J01, J02, J03+J04, J05 p0, J05 p1, J05 p2, J06, J10, J07+J08, J09. J05 pass k (k > 0) runs only if pass k-1 wrote `rekey/` (some block was re-keyed); J06 runs only if some pass wrote `blocks/`; J10 runs if J03+J04 or J06 wrote any candidate directory. A skipped job has no directory and no `_counters` file.

## Conventions shared by every job

| Item | Rule |
|---|---|
| Record format | JSON Lines, one core record per line, encoded with the core uPickle codecs (`WireCodec.encode`). Spark reads them with `spark.read.json`. |
| Shuffle types | Map output key `Text`, value `Text` (a JSON record). Job output key `NullWritable`, value `Text`. |
| Partitioner | J10 is map-only (no shuffle, no partitioner; one output file per input file). Every other job: `HashPartitioner` on the map output key, set explicitly. The key is `<kind>\t<stable ID>`, the identifier itself in J03+J04, the blocking key in J05, `<entityType>\t<sourceId>\t<targetId>` in J06, or the work ID in J07+J08. All records with one key meet in one reduce call. |
| Reducers | `--reducers` (default 2), the same for every job except J10 (0). Output is deterministic for a fixed reducer count. |
| Combiner | None. Every reducer needs the whole group (dedupe, conflict detection, or pair expansion), so a combiner cannot shrink it. |
| Output writer | `MultipleOutputs` under `LazyOutputFormat` (no empty part files), so every output lands in `<job dir>/<dir>/part-r-NNNNN.gz` (`part-m-NNNNN.gz` for J10). |
| Compression | Map output uses `DefaultCodec` (zlib, `mapreduce.map.output.compress=true`). Final output uses `GzipCodec`. Both have pure-Java fallbacks, so no native library is needed. |
| Determinism | Tasks hold only per-task state built in `setup` (parser, run context, output writer). Reducers sort their values before choosing, so output does not depend on shuffle order. Chosen records are the smallest (works) or the most frequent, ties broken by the smallest (entities). A rerun differs only in `provenance.runId` and the J10 `decidedAt` (the run's `startedAt`), and `PipelineSpec` checks this. |
| Provenance | Every record carries `Provenance`. Source records keep their dataset, source record ID, and dataset snapshot. Teams and pairs keep the DBLP work's source provenance. Match candidates are `DERIVED`, with `sourceRecordId = <key>|<sourceId>|<targetId>` and the DBLP snapshot; blocked candidates (J06) also carry `policyVersion = norm-v1.block-v1.feat-v1`. Graph edges carry `graph.policy-version`. Match decisions (J10) are `DERIVED`, keep the candidate's `sourceRecordId` and snapshot, and carry `policyVersion` = the configured match policy version (`er-v1` or `er-v2`), no prompt version, and no model version. |
| Run ID | `StableIds.runId("hadoop-pipeline", --code-version, config-version, [dblp=<d>, openalex=<d>, crossref=<d>, orcid=<d>] ++ input fingerprints, attempt)`. A fingerprint is `input:<name>=<path as given>:<bytes>` for every `--dblp`, `--dblp-dtd`, `--openalex`, `--crossref`, and `--orcid` path (bytes: the file length, or the total below a directory, from `FileSystem.getContentSummary`), so changed input data gives a new run ID; a missing input path fails the run before any job. Size, not a checksum: an in-place edit that keeps the byte count keeps the run ID. The attempt is `--attempt <n>` when given (run scripts pass it; the run fails before any job if that attempt's run directory exists). Otherwise the driver uses the smallest attempt (>= 1) whose run directory does not exist, so every local rerun of the same inputs gets a new attempt. `_manifest.json` (`hadoop-run-manifest-v3`) is created with overwrite disabled, which claims the attempt; it records the attempt, inputs, input fingerprints, reducers, `graph.oversize-team-threshold`, `entity-resolution.max-block-size`, `entity-resolution.max-conflict-group-size`, the entity-resolution rule versions, the configured match policy version (`matchPolicyVersion`, `er-v1` or `er-v2`), the four thresholds, and `startedAt` (the ISO-8601 instant every J10 decision of the run carries as `decidedAt`). |
| Paths | `<--out>/snapshot=<dblp snapshot>/run=<runId>/<job>/<dir>/`, plus `_manifest.json` and `_counters/<job>.json` in the run directory. Inputs and output are arguments, so local paths and `s3a://` URIs both work (EMRFS is removed in emr-spark-8.0.0). On EMR: inputs `s3a://<raw-bucket>/<dataset>/snapshot=<date>/...`, output root `s3a://<curated-bucket>/hadoop`. |
| Idempotency | Output is never overwritten. A new attempt writes a new run directory, and `FileOutputFormat` refuses an existing job directory. Consumers read one run directory, named in its manifest. |
| Config | `ConfigLoader.load()` supplies `config-version`, `graph.oversize-team-threshold`, `graph.policy-version`, `entity-resolution.max-block-size` (env `SCHOLARCHAIN_ER_MAX_BLOCK_SIZE`), `entity-resolution.max-conflict-group-size` (env `SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE`), `entity-resolution.policy-version` (env `SCHOLARCHAIN_ER_POLICY_VERSION`; must be one of the implemented `MatchPolicy.Versions`, `er-v1` or `er-v2`, else the run fails before any job and before any run directory is created; J10 tasks read it from `scholarchain.entity-resolution.policy-version` and also refuse an unknown version in setup), and the thresholds `entity-resolution.accept-threshold`, `reject-threshold`, `llm-band-min`, and `llm-band-max`. The driver copies them into the job configuration (`scholarchain.*` keys) for tasks to read. |
| Rejects | Each job writes `rejects-v1/` lines with the `RejectRecord` format, `schemaVersion = "hadoop-reject-v1"`: `job`, `reason`, `sourceDataset`, `sourceLocation` (`<file>@<byte offset>`), `sourceRecordId`, `detail`, `rawExcerpt` (first 2,000 characters), `snapshot`, and `runId`. |

### Counters

Group `scholarchain` is registered at zero in every map task, so every counter is always visible: `INPUT`, `ACCEPTED`, `REJECTED`, `DUPLICATE`, `CONFLICT`, `OVERSIZE_BLOCK`, `OVERSIZE_CONFLICT`, `OVERSIZE_TEAM`, `EMITTED_PAIR`, `MISSING_KEY`, and `SCHEMA_FAILURE`. Extra counters: `INVALID_ORCID` and `REPEATED_AUTHOR_NAME` (J01); `ORCID_DUPLICATE` (J02); `EXACT_MATCH`, `EXACT_UNMATCHED`, and `ORCID_SUFFIX_CONFLICT` (J03+J04; the last registered at zero in its reducers); `BLOCK_MEMBER`, `BLOCK_KEPT`, and `BLOCK_REKEYED` (J05); `SAME_WORK_PAIR_SKIPPED`, `MATCH_CANDIDATE`, and `WORK_PAIR` (J06); `DEFERRED_WORK_CANDIDATE` and `LLM_ELIGIBLE` (J10). Group `scholarchain.outputs` counts lines written per output directory. Group `scholarchain.decisions` (J10) counts decisions per `<entityType>.<matchPath>.<outcome>`. In mappers, `INPUT = ACCEPTED + REJECTED` (J10: `+ DEFERRED_WORK_CANDIDATE`). `DUPLICATE` and `CONFLICT` are counted in reducers, among accepted records.

| Reason code | Counter besides `REJECTED` | Raised by |
|---|---|---|
| `missing-key` | `MISSING_KEY` | J01 record without `key`; J02 OpenAlex without `id`, Crossref without `DOI`, ORCID without `orcid` (projection) or `orcid-identifier.path` (v3.0 record) |
| `schema-validation` | `SCHEMA_FAILURE` | Any record failing core `Validation` (for example a DOI work ID with spaces), a malformed DBLP year, an author without a name, or an invalid upstream record read by J03+J04, J07+J08, or J09 |
| `malformed-record` | `SCHEMA_FAILURE` | Unparseable XML or JSON, a truncated DBLP record, a JSON value of the wrong type |
| `unknown-entity` | `SCHEMA_FAILURE` | A DBLP entity reference not declared in the DTD |
| `invalid-affiliation` | `SCHEMA_FAILURE` | J02: a Crossref affiliation or ORCID employment name with no letters or digits (`StableIds.affiliationNameId` returns Left) |
| `unsupported-record-type` | none | DBLP record types other than `article` and `inproceedings` |
| `unsupported-identifier` | none | An ORCID record whose iD fails the core check (including `synthetic:` fixtures) |
| `conflicting-duplicate` | `CONFLICT` (not `REJECTED`) | Reducer: two different records with one stable ID (works, researcher profiles, J09 exports); the smallest is kept and the rest go to rejects |

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
  - **ORCID** (`--orcid` takes the full ORCID API v3.0 records that `fetch_orcid.sh` writes, `records.jsonl.gz`, or the projection in datasets/schemas/orcid.md, as in the committed samples). A line with `orcid-identifier` is a v3.0 record and is first projected by `SourceNormalizers.projectV3`, with the same paths as the `build_samples.sh` projection: `orcid-identifier.path`, `person.name`, `person.other-names`, `person.external-identifiers`, the employment summaries, the `doi` external IDs of `activities-summary.works.group[]` (lower-cased, distinct), and `history.last-modified-date`; biography, emails, addresses, keywords, and URLs are dropped. Records are keyed by `orcid-identifier.path` (the returned iD, not the requested one). A valid iD becomes a `GraphNode` (label `Researcher`, `nodeId = orcid:<iD>`). Its properties hold the given, family, and credit names, `otherName.N`, `externalId.N.type` and `externalId.N.value`, `workDoi.N`, and `lastModifiedMs`. Each employment becomes an `Institution` (`<org_id source lower-case>:<id>`, else `StableIds.affiliationNameId(org)`; a Left result rejects the record) and an `AFFILIATED_WITH` edge carrying department, role, years, and country.
- **Reducer:** `NormalizedRecordReducer` (as in J01). Researcher profiles are de-duplicated like works: one profile per `orcid:<iD>`; every extra record of one iD counts `ORCID_DUPLICATE` (and `DUPLICATE` when identical); differing records count `CONFLICT`, keep the smallest, and reject the rest.
- **Multi-work DOIs** (dataset manifest known limitations: OpenAlex can return several works for one DOI, and Crossref `works.jsonl` repeats some DOIs): OpenAlex works are keyed by their `W` ID, so works sharing a DOI stay separate records; Crossref works are keyed `doi:<DOI>`, so a repeated DOI is one work (identical repeats count `DUPLICATE`, differing ones `CONFLICT`). No job assumes one work per DOI: J03+J04 treats two works of one source under one DOI as a conflict (below).
- **Outputs:** `works/`, `mentions/`, `venues/`, `institutions/`, `topics/`, `edges/`, `researcher-profiles/`, `rejects-v1/`.
- **Test fixtures:** `SourceNormalizersSpec` (the committed samples plus malformed, missing-ID, wrong-type, nameless-author, and synthetic-iD cases; a v3.0-shaped copy of the committed fictional record normalizes exactly like its projection; a v3.0 record without `orcid-identifier.path` and one with a wrong field type) and `PipelineSpec` (the fictional record as projection plus two v3.0 copies: one profile, `ORCID_DUPLICATE` 2).

## J03+J04 ExactIdentifierIndexJob + ExactIdentifierJoinJob (`j03-j04-exact-identifier`)

- **Input format:** `TextInputFormat` over the `works/` and `mentions/` directories of J01 and J02. The mapper takes the record type from the parent directory name.
- **Mapper `ExactKeyMapper`:** works emit (`doi:<doi>` or `arxiv:<id>`, `IndexEntry`), and a work with neither counts `MISSING_KEY`. Mentions emit (`orcid:<iD>`, `IndexEntry`) and (`openalex:A...`, `IndexEntry`). `IndexEntry {kind, id, source, workId, rawName}` exists only inside this job's shuffle (`rawName` is the mention's printed name, absent for works). The shuffle is the keyed index.
- **Reducer `ExactJoinReducer`:** input (identifier, [IndexEntry]). The rules are in `ExactIdentifiers.resolve`:
  - Repeated members count `DUPLICATE`.
  - Work keys: if one source has two or more works under the key, the reducer counts `CONFLICT`, writes every within-source pair to `conflicts/`, and matches nothing. Otherwise each non-DBLP work becomes a `MatchCandidate(WORK, sourceId = that work, targetId = the DBLP work, blockingKey = key)`. With no DBLP work the key counts `EXACT_UNMATCHED`.
  - Researcher keys, ORCID homonym-suffix rule (er-v2 only; human ruling after phase-05 review 1): the reducer reads the configured `scholarchain.entity-resolution.policy-version` in setup (an unknown version fails the task) and calls the entity-resolution function `MatchPolicy.orcidGroupSuffixConflict(key, member rawNames, version)`. When it holds (an `orcid:` group whose mentions carry two or more distinct DBLP homonym numbers), the whole group is one conflict group: every member pair goes to `conflicts/`, nothing matches, the key counts `CONFLICT` and `ORCID_SUFFIX_CONFLICT`, and one `suffix-conflicts/` line `{job, identifier, groupSize, rule, policyVersion}` is written (rule `er-v2:orcid-group-homonym-suffix-conflict`). J10 decides those pairs as `EXACT_CONFLICT`. Under er-v1 the function is always false and the rule below applies unchanged.
  - Researcher keys otherwise: two or more mentions of one work under one key are a conflict. Every other mention becomes a `MatchCandidate(RESEARCHER, mention, key)`.
  - Conflict cap: a conflicting group (one source's works under a work key, or one work's mentions under a researcher key) with more than `entity-resolution.max-conflict-group-size` members writes no conflict pairs. It counts `OVERSIZE_CONFLICT` (the key still counts `CONFLICT`) and writes one `oversize-conflicts/` line `{job, identifier, entityType, groupSize, maxConflictGroupSize}`. The cap changes only the conflict output, not the matches: a capped work key matches nothing (as any conflicting work key), and under a capped researcher key the mentions of other works still match the key; only the clashing work's mentions are withheld. A capped ORCID suffix-conflict group (the whole group is the conflicting group) writes no pairs and matches nothing.
  - Feature maps are empty: exact-identifier matches are not blocked candidates.
  - Each reducer writes one `stats/` line with its largest identifier group.
- **Outputs:** `matches/`, `conflicts/`, `oversize-conflicts/`, `suffix-conflicts/`, `stats/`, `rejects-v1/`.
- **Test fixtures:** `LogicSpec` (key functions; match, duplicate, conflict, two OpenAlex works under one DOI, unmatched, and conflict-cap cases, including a capped researcher key whose other-work mention still matches; the ORCID suffix rule on the reviewer's failure input, one ORCID on "Wei Wang 0001" and "Wei Wang 0002", under er-v2 and er-v1, plus whole-group, capped, unsuffixed, and non-`orcid:` cases) and `PipelineSpec` (80 DOI work matches on the samples; the failure input as an extra `--dblp` file, end to end through J10, under er-v2 and er-v1).

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
- **Mapper `CandidatePairMapper`:** (offset, BlockRecord JSON) -> (`<entityType>\t<sourceId>\t<targetId>`, JSON `[blockingKey, features, works]`) for every member pair of the block, with sourceId < targetId (members are sorted, which is the `CanonicalPair` order). Features: `Features.researcher(mentionA, workA, mentionB, workB)` or `Features.work(workA, venueNameA, workB, venueNameB)` (feat-v1; absent features are left out). `works` is `[[workA, workB]]` (both WorkRecords) for WORK pairs and `[]` for researcher pairs. Two mentions of the same work are never paired (one person holds one author slot) and count `SAME_WORK_PAIR_SKIPPED`. A block of n members (n <= max-block-size) yields at most n(n-1)/2 pairs.
- **Reducer `CandidateJoinReducer`:** (pair key, [[blockingKey, features, works]]) -> one `MatchCandidate` in `candidates/`, and for a WORK pair also one `WorkPairInput {schemaVersion = hadoop-work-pair-v1, candidate, workA, workB}` (hadoop-internal) in `work-pairs/`, counting `WORK_PAIR`. `work-pairs/` exists because the work-pair features need both work records, which only J06 holds; it lets J10 stay map-only without loading a works table. `candidates/` is unchanged (feat-v1 features only), so the entity-resolution tools read it as before. The candidate: `blockingKey` = the smallest key among the blocks where the pair met (r2 is multi-key, so a pair can meet more than once), the features, `DERIVED` provenance with `policyVersion = norm-v1.block-v1.feat-v1`. Extra meetings count `DUPLICATE`; each written candidate counts `MATCH_CANDIDATE`; a candidate failing core validation is rejected (`SCHEMA_FAILURE`). No thresholds, scores, or decisions (Phase 5).
- **Combiner:** none. **Partitioner:** `HashPartitioner` on the pair key.
- **Outputs:** `candidates/`, `work-pairs/`, `rejects-v1/`.
- **Test fixtures:** `BlockingSpec` (pairs, same-work skip, features equal to the ER functions, venue side input, candidate validation) and `PipelineSpec` (one candidate per distinct pair of the kept blocks, smallest blocking key, `DUPLICATE` equals extra meetings, export count).

## J10 MatchDecisionJob (`j10-match-decision`, map-only)

- **Input format:** `TextInputFormat` over J03+J04 `matches/` and `conflicts/` and J06 `candidates/` and `work-pairs/`. The origin comes from the parent directory: `matches/` is `EXACT_MATCH`, `conflicts/` is `EXACT_CONFLICT`, and `candidates/` and `work-pairs/` are `BLOCKED`.
- **Mapper `MatchDecisionMapper`** (0 reducers, so no shuffle and no partitioner): (offset, MatchCandidate JSON, or WorkPairInput JSON from `work-pairs/`) -> one `MatchDecision` JSON line in `decisions/`, or a `RejectRecord` in `rejects-v1/`. Rules (`Decisions.decide`, the same steps as the entity-resolution `Runner` without adjudication):
  - Every call passes the configured version `v` (no default, so J10 never falls back to `er-v1` silently).
  - Exact matches and conflicts: `MatchPolicy.decide(candidate, origin, thresholds, v)` on the candidate's own (empty) features.
  - Blocked researcher pairs (`candidates/`): `MatchPolicy.decideBlocked(RESEARCHER, features, thresholds, v)`.
  - Blocked work pairs (`work-pairs/`): features = feat-v1 features ++ `WorkPairFeatures.work(workA, workB, v)`, then `decideBlocked(WORK, ..., v)`. WORK lines of `candidates/` are the same candidates; they count `DEFERRED_WORK_CANDIDATE` and write nothing.
  - `MatchPolicy.toDecision(candidate, features, verdict, runId, decidedAt = manifest startedAt, promptVersion = None, modelVersion = None)`. A decision failing core validation is rejected (`SCHEMA_FAILURE`).
  - LLM-band verdicts stay `UNRESOLVED` and count `LLM_ELIGIBLE`. J10 never adjudicates, never calls Bedrock or any LLM client, and has no adjudication code path (`BlockingSpec` scans the module sources for it).
- **Thresholds:** `entity-resolution.accept-threshold`, `reject-threshold`, `llm-band-min`, `llm-band-max` from configuration, recorded in the manifest.
- **Counters:** `INPUT = ACCEPTED + REJECTED + DEFERRED_WORK_CANDIDATE`; `LLM_ELIGIBLE`; group `scholarchain.decisions`, one counter per `<entityType>.<matchPath>.<outcome>` (match paths `EXACT_ID`, `DETERMINISTIC`, `PROBABILISTIC`, `LLM_BAND`; `LLM` never occurs here).
- **Outputs:** `decisions/` (`part-m-NNNNN.gz`), `rejects-v1/`.
- **Test fixtures:** `BlockingSpec` (verdicts equal MatchPolicy on hand-built pairs: two DBLP records rejected with `DBLP_RECORD_MISMATCH`, a shared DOI accepted, researcher and exact-ID origins; the version reaching both WorkPairFeatures and MatchPolicy, with two OpenAlex works sharing a DOI ACCEPTED under er-v1 and CONFLICT `SAME_SOURCE_SHARED_DOI` under er-v2; the no-adjudication source scan) and `PipelineSpec` (one decision per candidate, each equal to an independent recomputation from the J01 and J02 works, counters per path and outcome, `decidedAt` equals the manifest `startedAt`, J09 export; run once under the configured er-v2 and once under an env er-v1 override, with identical researcher decisions; an unknown version fails before any run directory).

## J07+J08 PublicationTeamJob + CoauthorPairExpansionJob (`j07-j08-team-pair-expansion`)

- **Input format:** `TextInputFormat` over J01 `works/` (every accepted DBLP work is retained).
- **Mapper `TeamMapper`:** emits (`workId`, WorkRecord JSON) after validation.
- **Reducer `TeamPairReducer`:** input (workId, [WorkRecord]). It writes one `PublicationTeam` (mention IDs in author order) to `teams/`. It writes all n(n-1)/2 `CoauthorPairCandidate`s to `pairs/`, each in `CanonicalPair` order, with the work ID, year, author positions (the `#n` suffix of each mention ID), and the work's source provenance. It counts `EMITTED_PAIR` and, when n exceeds `graph.oversize-team-threshold`, `OVERSIZE_TEAM`; oversize teams still emit all their pairs. Each reducer writes one `stats/` line: its largest team, that team's pair count, and a team-size histogram. Author-paper records are J01's `mentions/`.
- **Outputs:** `teams/`, `pairs/`, `stats/`, `rejects-v1/`.
- **Test fixtures:** `LogicSpec` (hand-checked pairs for 0, 1, 2, 3, 4, and 12 authors; 1,000 authors gives 499,500) and `PipelineSpec` (pair total equals the sum of n(n-1)/2 over the sample XML; `OVERSIZE_TEAM` at threshold 5).

## J09 GraphCandidateExportJob (`j09-graph-export`)

- **Input format:** `TextInputFormat` over every data directory of J01, J02, J03+J04, J06, J10, and J07+J08 (not `rejects-v1/`, `stats/`, `oversize-conflicts/`, `suffix-conflicts/`, J06 `work-pairs/`, or J05's intermediate directories).
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
  - `match-decisions/entity_type=<WORK|RESEARCHER>/outcome=<ACCEPTED|REJECTED|UNRESOLVED|CONFLICT>/` (J10 decisions, keyed by the candidate's `provenance.sourceRecordId`; the partition column is `outcome` because `decision` is a record field)
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

Full local inputs (work order 6 of phase 5): `--openalex scratch/datasets/openalex/full/works.jsonl`, `--crossref scratch/datasets/crossref/full/works.jsonl`, `--orcid scratch/datasets/orcid/full/records.jsonl.gz` (v3.0 records; gzip is one split), DBLP `scratch/datasets/dblp/snapshot=2026-10-03/full/works.xml` with the DTD under `raw/`.

The run uses `Test/runMain` because Hadoop is a `Provided` dependency, so it is on the test classpath but not on `Compile/run`. Hadoop `-D` options come before the `--` arguments. On EMR, the job jar from `sbt packageJobs` runs the same main class through the infra-engineer's scripts.

## Not implemented

- **DBLP person records (`persons.xml`)** are not read.
- **OpenAlex API list pages** (`scratch/datasets/openalex/full/raw/*.json`) are not an input format; J02 reads the JSON Lines the dataset scripts produce.
