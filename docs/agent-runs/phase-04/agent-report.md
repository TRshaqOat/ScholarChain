````text
STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: Phase 04 work order 1 of 1. Implement the ScholarChain Hadoop MapReduce pipeline in modules/hadoop: J01-J09, counters, rejects, JOBS.md, and tests; run `sbt check` twice and the bounded local chain on datasets/samples into scratch/hadoop/; run on full data under scratch/datasets/ if it exists.

Summary: J01, J02, J03+J04 (merged), J07+J08 (merged), and J09 are implemented and tested, and they ran locally. J05 and J06 are not implemented: the entity-resolution-engineer has not yet written the blocking-key and match-feature definitions they need (`modules/entity-resolution` holds only `target/`). A few contract choices also need domain-engineer confirmation (see ASSUMPTIONS 2 and 3). No full-data run was possible, because no full dataset outputs exist yet.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/JOBS.md
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Common.scala (counters, config keys, RunContext, RejectRecord `hadoop-reject-v1`, Keyed, Outputs)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/DblpInputFormat.scala (input format and record reader)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/DblpParser.scala (DTD entities, StAX parser, mapping to WorkRecord and AuthorMention)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/SourceNormalizers.scala (OpenAlex, Crossref, ORCID)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Logic.scala (exact-identifier keys and resolution, teams, pair expansion)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala (all mappers, reducers, and job definitions)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Pipeline.scala (driver, run ID and attempt, manifest, counters)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/{TestSupport,DblpRecordReaderSpec,DblpParserSpec,SourceNormalizersSpec,LogicSpec,PipelineSpec}.scala
FILES MODIFIED: none outside modules/hadoop (`git status --porcelain` shows only the 14 new files above). `.claude/.last-test-run` was written by `markTestRun` only.
FILES DELETED: none

JOB GRAPH:
- J01 `j01-dblp-extract`: DBLP works.xml + DTD -> works/, mentions/, venues/, rejects-v1/
- J02 `j02-source-normalize`: OpenAlex, Crossref, and ORCID JSONL -> works/, mentions/, venues/, institutions/, topics/, edges/, researcher-profiles/, rejects-v1/
- J03+J04 `j03-j04-exact-identifier` (merged): J01 and J02 works/ and mentions/ -> matches/, conflicts/, stats/, rejects-v1/
- J07+J08 `j07-j08-team-pair-expansion` (merged): J01 works/ -> teams/, pairs/, stats/, rejects-v1/
- J09 `j09-graph-export`: every data directory of the jobs above -> Spark-ready Hive-style partitions (`works/source_dataset=/publication_year=`, `author-mentions/source_dataset=`, `publication-teams/publication_year=`, `coauthor-pair-candidates/publication_year=`, `match-candidates/entity_type=`, `exact-id-conflicts/entity_type=`, `graph-edges/edge_type=`, `venues`, `institutions`, `topics`, `researcher-profiles`)
- Ordering: J01, J02, J03+J04, J07+J08, J09, run in sequence by `scholarchain.hadoop.Pipeline`.
- J05 and J06: not implemented (BLOCKED-ON, below).

KEY-VALUE CONTRACTS (full detail in JOBS.md):
- J01 map: (LongWritable byte offset of the record's `<`, Text complete record decoded from ISO-8859-1) -> (Text `works\t<workId>` | `venues\t<venueId>` | `rejects-v1\t<file>@<offset>`, Text JSON).
- J01 and J02 reduce (`NormalizedRecordReducer`): (Text `<kind>\t<id>`, [Text JSON]) -> JSON lines in `<kind>/`. For works, identical repeats count DUPLICATE; differing repeats count CONFLICT, the smallest record is kept, and the others go to rejects; each kept work's authors go to mentions/. Entities keep the most frequent value (ties: the smallest).
- J02 map (`SourceNormalizeMapper`): (LongWritable, Text JSON line) -> (Text `<kind>\t<id>`, Text JSON). The source is chosen from the split's file path against `scholarchain.j02.inputs.<SOURCE>`.
- J03+J04 map: (LongWritable, Text WorkRecord or AuthorMention) -> (Text `doi:`/`arxiv:`/`orcid:`/`openalex:A...` key, Text IndexEntry).
- J03+J04 reduce: (identifier, [IndexEntry]) -> MatchCandidate records with empty feature maps. A work key matches each non-DBLP work to its single DBLP work. If two or more works from one source share the key, it is a conflict and nothing matches. A researcher key matches each mention to the key; two mentions of one work under one key are a conflict.
- J07+J08 map: (LongWritable, Text WorkRecord) -> (Text workId, Text WorkRecord).
- J07+J08 reduce: one PublicationTeam, plus n(n-1)/2 CoauthorPairCandidate records in CanonicalPair order, each with work ID, year, author positions (the `#n` suffix of each mention ID), and the DBLP source provenance.
- J09 map: (LongWritable, Text) -> (Text `<export dir>\t<stable id>`, Text) after WireCodec decode-and-validate.
- J09 reduce: one record per ID; DUPLICATE and CONFLICT are counted.

PARTITIONING AND COMPRESSION:
- Explicit HashPartitioner on the shuffle key in every job. Reducers = `--reducers`, 2 in every run here, so each job produced 2 reduce partitions.
- No combiner: every reduce needs the whole group.
- Map output uses DefaultCodec (zlib). Final output is gzip JSON Lines written through MultipleOutputs and LazyOutputFormat.
- J09 leaf partitions in the bounded run: works 60, publication-teams 20, coauthor-pair-candidates 20, author-mentions 3, match-candidates 2, graph-edges 2. That is 184 part files in total for the run.

COUNTERS (observed, bounded run on datasets/samples, attempt 1 run-d45b1ab121612f6caa217300870e70d1; attempt 2 run-b3e806e4a9d5419ed61e9fd4bfdceeae has identical counters):
- J01: INPUT=40, ACCEPTED=40, REJECTED=0, DUPLICATE=0, CONFLICT=0, MISSING_KEY=0, SCHEMA_FAILURE=0, INVALID_ORCID=0, REPEATED_AUTHOR_NAME=0. Outputs: works=40, mentions=163, venues=1.
- J02: INPUT=84, ACCEPTED=81, REJECTED=3, DUPLICATE=0, CONFLICT=0, MISSING_KEY=0, SCHEMA_FAILURE=0. Outputs: works=80, mentions=326, institutions=137, edges=42, topics=10, venues=2, researcher-profiles=1, rejects-v1=3.
- J03+J04: INPUT=609, ACCEPTED=609, REJECTED=0, DUPLICATE=0, CONFLICT=0, MISSING_KEY=0, EXACT_MATCH=536, EXACT_UNMATCHED absent (never incremented, so 0). Matches by key type: WORK doi 80, RESEARCHER orcid 308, RESEARCHER openalex 148. conflicts=0.
- J07+J08: INPUT=40, ACCEPTED=40, EMITTED_PAIR=346, OVERSIZE_TEAM=0 (threshold 100 from `graph.oversize-team-threshold`), DUPLICATE=0, CONFLICT=0. Outputs: teams=40, pairs=346.
- J09: INPUT=1724, ACCEPTED=1724, REJECTED=0, SCHEMA_FAILURE=0, DUPLICATE=0, CONFLICT=0. Outputs: works=120, author-mentions=489, publication-teams=40, coauthor-pair-candidates=346, match-candidates=536, graph-edges=42, institutions=137, topics=10, venues=3, researcher-profiles=1.
- OVERSIZE_BLOCK: not emitted, because J05 is blocked.

REJECTS:
- 3 records, all in J02, reason `unsupported-identifier`: the synthetic ORCID fixtures synthetic:orcid-a, -b, and -c, located at `.../synthetic-orcid-records.jsonl@<offset>`.
- Path: scratch/hadoop/bounded/snapshot=2026-10-03/run=run-d45b1ab121612f6caa217300870e70d1/j02-source-normalize/rejects-v1/part-r-0000{0,1}.gz
- Every other job: 0 rejects.

TEAM AND BLOCK EXTREMES:
- Maximum team size: 12 (dblp:conf/ics/WangYWDXSLWLLLQ25).
- Largest pair expansion: 66 pairs, from that work.
- Team-size histogram across both reducers: 1:2, 2:7, 3:12, 4:5, 5:5, 6:3, 7:4, 8:1, 11-20:1.
- Largest blocking group: not available (J05 blocked). The largest exact-identifier group is 4 members (orcid:0000-0002-5949-0046 and orcid:0000-0002-0258-0294).

OUTPUT PATHS:
- Final bounded run: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/hadoop/bounded/snapshot=2026-10-03/run=run-d45b1ab121612f6caa217300870e70d1/ (attempt 1) and .../run=run-b3e806e4a9d5419ed61e9fd4bfdceeae/ (attempt 2). Each holds `_manifest.json`, `_counters/<job>.json`, and one directory per job.
- Superseded earlier run with pre-final code (J02 reject locations were offsets only): /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/hadoop/samples/. Left in place; delete it if you wish.
- Nothing was written into the repository.

COMMANDS RUN:
- `sbt -batch hadoop/compile`
- `sbt -batch hadoop/test`, several times while iterating
- `sbt -batch hadoop/scalafmtAll hadoop/test`
- `sbt check`, twice
- `sbt -batch "hadoop/Test/runMain scholarchain.hadoop.Pipeline --out scratch/hadoop/samples ..."` (superseded run)
- The same command with `--out scratch/hadoop/bounded`, twice in one sbt session (final runs). Arguments: `--code-version b52ff1cdb4110b2b80c768a93ab9436d14ab27b7-dirty`, the four sample inputs, snapshots dblp=2026-10-03 and openalex/crossref/orcid=2026-11-03, `--reducers 2`.
- Read-only inspection: git status, rev-parse, ls/find/du, jq, gzcat, and shasum on scratch output.

TEST RESULTS:
- `sbt check` run 1: core 148 succeeded and 0 failed; hadoop 42 succeeded and 0 failed; markTestRun wrote `.claude/.last-test-run`.
- `sbt check` run 2: the same, 148 + 42, 0 failed.
- Hadoop suite breakdown (42): DblpRecordReaderSpec 6, DblpParserSpec 11, SourceNormalizersSpec 6, LogicSpec 12, PipelineSpec 7.
- PipelineSpec runs the whole chain twice with Hadoop's local job runner, using 4 KB DBLP splits and oversize threshold 5 through `SCHOLARCHAIN_CONFIG_FILE`.

VALIDATION RESULTS:
- Split boundaries: a two-way split at every byte offset of the hand-built ISO-8859-1 fixture, and 1-, 2-, 3-, 17-, 64-, and 257-byte splits, each yield every record exactly once and intact. The 40-record sample reads identically as one split and as 4 KB splits.
- Hand-checked pair generation: 0 and 1 authors give no pairs; 2 give (#1,#2); 3 give (#1,#2), (#1,#3), (#2,#3); 12 give 66 canonical pairs, with #10 sorting before #2.
- The pair total equals the sum of n(n-1)/2 computed independently from the sample XML.
- Every J09 record decodes and validates with core WireCodec.
- Determinism: across attempts 1 and 2, counters are identical, and the sha256 of all sorted output lines with the run ID masked is identical (80587c457d757a55eb67cf6be823ec30608e7e0cf2ace7b7b1c0f0a7e85c9843).
- Run IDs: attempt 1, then attempt 2 on a rerun of the same inputs.
- Full-data run: not executed. scratch/datasets has no full outputs: DBLP has only `raw/dblp-2026-10-03.xml.gz.part` (247,476,224 of 1,107,081,001 bytes) and no `full/` directory, and OpenAlex, Crossref, and ORCID have only `sample/`.

ASSUMPTIONS:
1. `normalizedName = rawName` and `normalizedTitle = None` are placeholders. The normalization rules belong to the entity-resolution-engineer, and the code is marked with a `shortcut:` comment.
2. ORCID projection records have no core contract of their own. They are emitted as a GraphNode (label Researcher, `nodeId orcid:<iD>`, with names, other names, external IDs, and work DOIs as properties), Institution records, and AFFILIATED_WITH GraphEdge records. The domain-engineer should confirm this.
3. Affiliations with no identifier (Crossref affiliation names, ORCID employments without org_id) get the ID `affiliation-name:<H("affiliation-name", name)>`, a scheme defined in hadoop. Stable IDs belong to the domain-engineer.
4. Work-topic links use GraphEdge HAS_TOPIC; venues use WorkRecord.venueId plus Venue records.
5. Author-paper records are J01's and J02's mentions/ (AuthorMention keyed by mention ID, carrying work ID and position).
6. Exact-ID matches and conflicts are MatchCandidate records with empty feature maps. The conflict rules are structural only; policy-level conflict detection belongs to the entity-resolution-engineer. A DERIVED candidate's `sourceRecordId` is `<key>|<sourceId>|<targetId>`.
7. The output path uses the DBLP snapshot; all four snapshots are in the run ID and the manifest. The DBLP file encoding is taken as ISO-8859-1 from datasets/schemas/dblp.md, not read from the XML prologue.
8. The local run uses `hadoop/Test/runMain`, because Hadoop is Provided and so is absent from the Compile/run classpath.

RISKS:
- J05 and J06 are not implemented, so there are no OVERSIZE_BLOCK counter, no largest-blocking-group figure, no MatchCandidate features, and no researcher or work blocks.
- The J03+J04 conflict-pair output is quadratic within one source and has no cap. No max-group configuration exists.
- Oversize teams are counted, but their pairs are still emitted in full in one reduce call.
- Run ID: a `-dirty` code version does not distinguish uncommitted code changes, so EMR runs must pass a clean commit SHA. The attempt claim (create with overwrite disabled) is not atomic on S3A.
- Not read: DBLP persons.xml and raw ORCID `records.jsonl.gz` (only the projection format is read).
- Dropped fields: OpenAlex cited_by_count, updated_date, is_corresponding, author_position, and author display_name when a raw name exists; Crossref authenticated-orcid, funder, license, and reference counts. Abstracts are not handled (abstractTextRef is None).
- Tests use Hadoop's local job runner, not a mini-cluster. `sbt packageJobs` (the shaded assembly) was not run, and nothing has run on EMR.
- J09 rewrites all upstream data, doubling I/O.
- The first `Test/runMain` exit printed a NoSuchFileException from a Hadoop shutdown hook after sbt removed its temporary classpath copy. It appeared after the jobs had finished, and the outputs and counters were complete.

MANUAL ACTION REQUIRED:
- Route the BLOCKED-ON items below.
- Decide on ASSUMPTIONS 2 and 3 (domain-engineer).
- Optionally delete the superseded scratch/hadoop/samples/ run.

BLOCKED-ON OWNER:
- entity-resolution-engineer (primary)
- domain-engineer (secondary)

BLOCKED-ON EVIDENCE:
- `ls -R modules/entity-resolution` shows only `target/`, with no source.
- `grep -rn -i normaliz modules/core/src/main` finds only StableIds.normalizeDoi and normalizeOrcid; no name or title normalization exists.
- `modules/core/src/main/resources/scholarchain.conf` `entity-resolution {}` holds only policy-version and four thresholds; there is no max-block-size key.

Needed from the entity-resolution-engineer, as pure versioned functions:
- name normalization and title normalization
- researcher blocking keys (normalized name, initials, coauthor context) and work blocking keys (title, year, venue), each in primary-then-fallback order
- researcher and work match-feature functions returning `Map[String, Double]`
- the maximum block size value and the oversize-block policy

Needed from the domain-engineer:
- a config key for max block size, for example `entity-resolution.max-block-size`
- a ruling on ASSUMPTIONS 2 and 3, or a StableIds function for affiliations that have no identifier

After that, the hadoop-engineer reruns to add J05 and J06 and to swap in the normalization functions.

NEXT GATE: rerun hadoop-engineer after the dependencies land, then local or EMR sample run, deterministic output checks, test-engineer, code-reviewer APPROVE.
````

## Work order 2: domain-engineer

````text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 04, work order 2 of 4. (1) Add the config keys entity-resolution.max-block-size and entity-resolution.max-conflict-group-size, with SCHOLARCHAIN_* environment overrides, PROVISIONAL defaults, and a check that both are positive. (2) Add a documented, tested stable ID function for affiliations that have no external identifier, to replace the hadoop scheme affiliation-name:<hash>.

FILES CREATED: none

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/resources/scholarchain.conf
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/StableIds.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/StableIdsSpec.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSpec.scala

FILES DELETED: none

SCHEMA VERSIONS: no change. No record and no serialized shape changed, so the frozen wire examples (modules/core/src/test/resources/wire/examples-v1.json) were not touched. config-version stays config-v1, because the change only adds keys and existing files still load.

STABLE ID FORMULAS:
- New: `StableIds.affiliationNameId(name: String): Either[String, String]`
  = "sci:" + AffiliationNameRuleVersion + ":" + H("affiliation-name", AffiliationNameRuleVersion, canonicalAffiliationName(name))
  - AffiliationNameRuleVersion = "affn-v1".
  - H is the existing hash: SHA-256 over the parts joined by U+001F, keeping the first 32 hex digits.
  - It returns Left when the canonical name is empty (no letters or digits). It returns Left instead of throwing, so a mapper that meets a bad name can reject the record without crashing.
- New: `StableIds.canonicalAffiliationName(name)` (rule affn-v1). Steps, in order:
  1. Unicode NFKD.
  2. Remove combining marks (\p{M}).
  3. Lower-case with Locale.ROOT.
  4. Replace each run of characters that are not letters or digits with one space.
  5. Trim.
  - Non-Latin letters are kept.
  - This function is only an ID key. It does not decide that two different spellings are the same institution; that remains entity resolution's job.
- New: `IdFormat.isAffiliationNameId(s)`, which matches `sci:<version>:<32 hex>`. These IDs also pass `IdFormat.isNamespacedId`, so `Validator[Institution]` accepts them with no change.
- Frozen test values, computed separately with Python hashlib and unicodedata:
  - "Universitat Politècnica de Catalunya, Dept. EE" -> canonical "universitat politecnica de catalunya dept ee" -> sci:affn-v1:981e0a59669da94037cd74352e4b6112
  - "清华大学" -> sci:affn-v1:ed625fa53e12cc4c68183d214dca8dcc
- Existing formulas are unchanged.

PAIR CANONICALIZATION RULE: unchanged (CanonicalPair: smaller ID by String.compareTo first; self-pairs refused).

CONFIGURATION PRECEDENCE: unchanged. Highest first: environment variables in ConfigLoader.EnvOverrides, then the file named by SCHOLARCHAIN_CONFIG_FILE, then the scholarchain.conf reference file.

New keys (under scholarchain.entity-resolution):

| Key | Environment variable | Default |
|---|---|---|
| max-block-size | SCHOLARCHAIN_ER_MAX_BLOCK_SIZE | 1000 (PROVISIONAL) |
| max-conflict-group-size | SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE | 100 (PROVISIONAL) |

- In code they are EntityResolutionConfig.maxBlockSize and EntityResolutionConfig.maxConflictGroupSize (both Int).
- Validation: if either is below 1, loading fails with "entity-resolution.max-block-size and entity-resolution.max-conflict-group-size must be positive".

SAMPLE SERIALIZED RECORDS: none changed. An Institution built from a name only would carry institutionId "sci:affn-v1:981e0a59669da94037cd74352e4b6112".

AFFECTED CONSUMERS:
- hadoop-engineer (modules/hadoop), work order 4:
  - Replace `SourceNormalizers.unidentifiedInstitutionId` (SourceNormalizers.scala:33-35, called at lines 244 and 353) with `StableIds.affiliationNameId`, and handle its Left result as a reject.
  - Read `entityResolution.maxBlockSize` and `entityResolution.maxConflictGroupSize`.
  - Institution IDs change from affiliation-name:<hash> to sci:affn-v1:<hash>, so earlier scratch outputs are not comparable.
- entity-resolution-engineer (work order 3): may read entityResolution.maxBlockSize. It owns the values.
- No other module constructs EntityResolutionConfig; I checked with grep across modules/ and tests/.

COMMANDS RUN:
1. `sbt check`: failed at scalafmtCheck, because StableIds.scala and StableIdsSpec.scala were not formatted.
2. `sbt core/scalafmt core/Test/scalafmt`: scoped to core so no other owner's files were changed; it reformatted 1 main and 1 test source.
3. `sbt check`: exit 0, success.
4. `sbt check`: exit 0, success (second required run; log at /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/00e1d7f4-8996-4366-b4b5-a454e6186778/scratchpad/check2.log).

TEST RESULTS (observed the same on runs 3 and 4):
- First test group (StableIdsSpec, ConfigLoaderSpec, ValidationSpec, WireContractSpec, DblpParserSpec): "Total number of tests run: 150", succeeded 150, failed 0, canceled 0, ignored 0, pending 0.
- Second test group (LogicSpec, SourceNormalizersSpec, DblpRecordReaderSpec, PipelineSpec): 42 run, succeeded 42, failed 0.
- markTestRun wrote .claude/.last-test-run.
- These new tests appeared as passing: "affiliation-name ID: frozen value, canonical form, version, and empty names" and "entity-resolution block and conflict-group limits: defaults, env overrides, positive".
- The existing test "a non-local environment with every value supplied loads" was given numeric values for the two new environment variables.

VALIDATION RESULTS:
- Config: values 0 and -1 are rejected (tested). The defaults load. A config-file value and an environment override each take effect (tested). The test that checks every documented environment variable maps to a reference path covers the two new variables.
- Affiliation IDs: names with different case, spacing, punctuation, or diacritics give the same ID. A different name gives a different ID. A name with only punctuation returns Left. The old affiliation-name:<hash> form fails isAffiliationNameId.

ASSUMPTIONS:
1. The namespace "sci:" ("ScholarChain institution") mirrors the versioned "scr:" researcher form. The rule version is part of both the ID text and the hash input, so changing the rule can never silently produce the same IDs.
2. The default values 1000 and 100 are uncalibrated starting points. The entity-resolution-engineer and hadoop-engineer own their evaluation.
3. The repository has no top-level config/ directory. The versioned configuration is modules/core/src/main/resources/scholarchain.conf, as in Phase 3.

RISKS:
- Canonical-name collisions are intended to be coarse. For example, "Dept. of EE & CS" and "Dept of EE CS" get the same ID, and the rule does not expand "&" to "and". Two genuinely different institutions with the same canonical name share one ID. That is acceptable only as an ID key, not as a match decision.
- NFKD does not fold some letters, such as ß and ø, to ASCII. Those stay distinct from their transliterations.
- The test counts are reported per test group because the sbt output interleaves projects. I did not break them down by module.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, and code-reviewer APPROVE
````

## Work order 3: entity-resolution-engineer

````text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 04, work order 3 of 4. Create the modules/entity-resolution source tree with the pure functions J05 and J06 need: normalization, researcher and work blocking keys (a primary and fallbacks for each), an oversize-block policy driven by entity-resolution.max-block-size, and nine match features with values in [0, 1]. No thresholds, scores, or decisions. Tests go in modules/entity-resolution/src/test only. Run sbt check twice.

Summary: All four items are implemented and tested, 30 tests in total. I wrote only under modules/entity-resolution. I added no dependency, build edge, or core change. The only core functions used are StableIds.normalizeOrcid and the AuthorMention and WorkRecord contracts. One deliberate change from the work order's wording: each feature returns Option[Double] rather than Double (see ASSUMPTIONS 1).

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Normalize.scala (Versions, ParsedName, Normalize)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Blocking.scala (BlockingKeys, OversizePolicy)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Features.scala (VenueRef, Features)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/NormalizeSpec.scala (7 tests)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingSpec.scala (9 tests)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/FeaturesSpec.scala (14 tests)
FILES MODIFIED: none
FILES DELETED: none

POLICY VERSION: There is no match policy yet; that is Phase 5, and er-v1 in config is unchanged. Rule versions are in `scholarchain.entityresolution.Versions`: Normalization = "norm-v1", Blocking = "block-v1", Features = "feat-v1". Hadoop should record these in provenance and the run manifest.

FEATURES AND RULES:
Normalization (norm-v1, object Normalize):
- `foldUnicode`: lower-case with Locale.ROOT, then NFKD, then remove \p{M}, then fold letters NFKD keeps intact: ß->ss, æ->ae, œ->oe, ø->o, đ/ð->d, ł->l, þ->th, ı->i. Non-Latin letters are kept.
- `text`: fold, delete apostrophes (' ’ `) so "O'Brien" becomes "obrien", turn every other run of non-letter, non-digit characters into one space, then trim.
- `stripMarkup` removes `<...>` tags without inserting a space, so "H<sub>2</sub>O" becomes "H2O". `title` = text(stripMarkup(s)). `titleTokens(Option[String])` returns the title's tokens.
- `splitHomonym`: "Wei Wang 0001" becomes ("Wei Wang", Some("0001")). The match is a trailing whitespace plus a 4-digit number.
- `name(raw): ParsedName(givenNames, family, homonymSuffix)`:
  - "Family, Given" splits at the first comma.
  - Otherwise the last token is the family name, and a run of particles before it (van, von, de, der, den, del, della, di, da, du, le, la, dos, das, ten, ter) joins the family, provided at least one given token remains.
  - `comparable` = given tokens then family, so "Wang, Wei" and "Wei Wang 0001" both give "wei wang". The homonym suffix stays in the separate `homonymSuffix` field.
  - `initials`: "J.-P." gives "jp". `familyKey` = family with spaces removed.
- Hadoop should set AuthorMention.normalizedName = Normalize.name(raw).comparable and WorkRecord.normalizedTitle = Normalize.title(raw).

Blocking keys (block-v1, object BlockingKeys):
- Each function returns one key list per level. Index 0 is primary and later indexes are fallbacks. An empty result means MISSING_KEY. "-" stands for an absent part.
- Each level's key text starts with the previous level's key text (tested), so re-keying a block can only split it.
- `researcher(rawName, coauthorRawNames)`, `ResearcherLevels = 3`:
  - r0 (primary): `r0:<familyKey>|<first initial>`
  - r1: `r1:<familyKey>|<first given token>`
  - r2: one key per distinct coauthor familyKey, sorted: `r2:<familyKey>|<first given>|<coauthor familyKey>`, or `...|-` when there are no coauthors.
  - The homonym suffix and the name order do not affect the keys.
- `work(title, year)`, `WorkLevels = 3`:
  - w0 (primary): `w0:<first 3 non-stopword title tokens joined by _>`
  - w1: `w1:<w0>|<year>`
  - w2: `w2:<w0>|<year>|<all title tokens>`

Oversize policy (block-v1, object OversizePolicy):
- `action(level, size, maxBlockSize, levels)` returns:
  - Keep if size <= maxBlockSize
  - Rekey(level+1) if a further level exists
  - ReportOversize otherwise
- maxBlockSize < 1 throws.
- A block reported as oversize yields no candidates, so its members stay unresolved: split, never merged.
- `plan(members, id, keys, levels, maxBlockSize)` is the in-memory reference. It returns blocks and oversize reports sorted by (level, key), plus the missing-key member IDs, and it does not depend on input order. J05's multi-pass job should give the same blocks.

Features (feat-v1, object Features):
- Each feature returns Some(value in [0, 1]) when both sides have evidence and None when either side lacks it.
- `Features.researcher(mentionA, workA, mentionB, workB)` and `Features.work(workA, venueNameA, workB, venueNameB)` build the `Map[String, Double]` for MatchCandidate.features and leave out absent features.
- Feature-map key names:
  - name_similarity: Jaro-Winkler (p = 0.1, prefix up to 4) of the comparable names.
  - initials_compatibility: given tokens aligned from the start. A pair is compatible when equal or when one is a single letter that starts the other. Value = compatible leading pairs / the shorter token count.
  - author_order_agreement: 1 - |posA - posB| / max(teamA, teamB). None when a position is outside its team.
  - coauthor_overlap: Jaccard of coauthor keys of the form familyKey|first initial.
  - affiliation_compatibility: overlap coefficient of institution IDs, counting only namespaces both sides use. None when no namespace is shared.
  - orcid_agreement: 1.0 when the normalizeOrcid values are equal, 0.0 when they differ (the conflict signal), None when either is missing or invalid.
  - homonym_suffix_agreement: 1.0 or 0.0 for DBLP homonym numbers. This is the "kept as separate evidence" carrier.
  - title_similarity: Jaccard of normalized title tokens.
  - year_distance: 1 / (1 + |Δ|). It is a proximity: 1.0 for the same year.
  - venue_compatibility: venue IDs in the same namespace give 1 or 0. Otherwise, with both names: 1.0 for an equal name or an acronym match ("ICS" and "International Conference on Supercomputing"), else the Jaccard of significant name tokens.

THRESHOLDS: none (Phase 5). The only parameter consumed is entity-resolution.max-block-size, passed by the caller. Its default of 1000 is PROVISIONAL and unchanged.
MODEL AND PROMPT VERSIONS: none. No LLM is used in this work order.
EVALUATION: not run. There are no match decisions to evaluate yet (Phase 5), and labels/ was not read.
REPRESENTATIVE ERRORS: none observed, since no evaluation ran. Known recall costs are encoded in the tests: in an oversize r0 block, "W. Wang" (r1 `wang|w`) is split from "Wei Wang" (r1 `wang|wei`) even when they share a coauthor.
LEAKAGE CHECK: No code path reads labels/**. Nothing is fitted; all rules are fixed literals in source.

COMMANDS RUN:
- `sbt -batch entityResolution/scalafmtAll entityResolution/test`, 5 times while iterating:
  - Run 1: scalafmt rejected the field name `given`, so I renamed it to `givenNames`.
  - Runs 2 and 3: test compile errors, because the test helper `work` shadowed `Features.work`; I renamed it to `mkWork`.
  - Run 4: 30/30 passed, with 1 exhaustivity warning in a test helper, which I then fixed.
  - Run 5: 30/30 passed, no warnings.
- `sbt check`, run 1: exit 0. Log at /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/00e1d7f4-8996-4366-b4b5-a454e6186778/scratchpad/er-check1.log
- `sbt check`, run 2: exit 0. Log at /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/00e1d7f4-8996-4366-b4b5-a454e6186778/scratchpad/er-check2.log
- `git status --porcelain --untracked-files=all`

TEST RESULTS (identical on both sbt check runs):
- Group with 150 tests (core suites, 4 suites): succeeded 150, failed 0.
- entityResolution (NormalizeSpec 7, BlockingSpec 9, FeaturesSpec 14): 30 run, succeeded 30, failed 0, canceled 0, ignored 0, pending 0.
- hadoop (5 suites): 42 run, succeeded 42, failed 0.
- markTestRun wrote .claude/.last-test-run on both runs.

VALIDATION RESULTS:
- Jaro-Winkler matches textbook values: martha/marhta 0.9611, dwayne/duane 0.84, dixon/dicksonx 0.8133 (tolerance 1e-4).
- Every normalizer, all six blocking levels, the oversize action and plan, and all nine features (plus homonym agreement and both map builders) are checked against hand-computed literals.
- A grid test over 7 names and 4 titles asserts every feature value lies in [0, 1] and that two evaluations are equal.
- The refinement property is tested on the researcher and work keys.
- The plan gives the same result for reversed input.
- git status shows new files only under modules/entity-resolution/src. The pre-existing uncommitted core and hadoop changes and docs/agent-runs/phase-04/agent-report.md are untouched.

ASSUMPTIONS:
1. Features return Option[Double] rather than Double. The work order says "each returning a value in [0, 1]". I read that as "every value present is in [0, 1]", and I kept "no evidence" (None, left out of the map) distinct from "evidence disagrees" (0.0). Collapsing the two would turn a missing ORCID into an apparent ORCID conflict, and Phase 5 policy needs to tell them apart. If the human wants Double with a sentinel, that is a one-line change in the two map builders.
2. homonym_suffix_agreement is a tenth feature beyond the nine listed. It is the only way the kept homonym evidence can travel in MatchCandidate.features.
3. Venue names are not in WorkRecord. `Features.work` takes optional venue names, which J06 joins from Venue records. Without names, cross-namespace venues give None.
4. author_order_agreement is meaningful mainly for mentions on two records of the same matched work. It is computed whenever positions are valid.

RISKS:
- r2 is multi-key: a mention can be emitted once per distinct coauthor family name, up to team size - 1, but only for mentions in oversize r1 blocks. The same pair can meet in several r2 blocks, so J06 must de-duplicate (sourceId, targetId).
- Fallback levels trade recall for bounded blocks:
  - initial-only names split from full names at r1;
  - works more than 0 years apart split at w1;
  - only exact titles meet at w2.
  - The year is part of the w1 key, so ±1-year preprint and proceedings pairs split only inside oversize w0 blocks.
- Name parsing has known gaps:
  - no handling of Jr./Sr./III;
  - an initial-cluster token such as "JP" counts as one initial;
  - CJK names without spaces become a family-only name;
  - comma forms with trailing particles ("Aalst, W. van der") are not normalized.
- affiliation_compatibility cannot equate the same institution across namespaces (ror: against openalex: against sci:affn-v1:); it returns None.
- `OversizePolicy.plan` holds a block's members in memory. It is a reference and test oracle. Hadoop must apply `action` with a multi-pass count, not call `plan` on unbounded reducer groups.
- None of the blocking keys or features has been run on the bounded samples yet; that happens in work order 4.

MANUAL ACTION REQUIRED: Rule on ASSUMPTIONS 1 (Option versus Double) before work order 4 starts, if you disagree with it.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: hadoop-engineer work order 4 (J05 and J06 using these functions), then the Phase 5 gold-set evaluation, match-policy review, and code-reviewer APPROVE.
````

## Work order 4: hadoop-engineer

````text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04, work order 4 of 4. Finish Phase 4 in modules/hadoop:
(1) J05 BlockingKeyJob and J06 CandidateJoinJob, using the entity-resolution functions and entity-resolution.max-block-size.
(2) Entity-resolution normalization replaces the name and title placeholders.
(3) Core StableIds.affiliationNameId replaces the hadoop-defined affiliation scheme.
(4) J03+J04 conflict cap from max-conflict-group-size, plus an oversize-conflict counter.
(5) Explicit run attempt as an argument; auto-claim stays the default.
(6) Oversize teams keep all their pairs and keep the counter.
(7) sbt packageJobs, then jar inspection.
Also: JOBS.md updated, sbt check twice, the chain twice on samples, and the chain once on the full DBLP subset.

Summary: All seven items are done and tested. `sbt check` passed twice. Two sample runs give identical counters and an identical logical output hash. The packaged jar runs on its own with only the Hadoop client jars beside it. The full DBLP subset (110,867 works) ran locally in 437.6 s. The headline finding: the PROVISIONAL default max-block-size of 1000 yields 20,258,400 blocked candidates on 110k works, almost all of them researcher candidates. I wrote only under modules/hadoop. Job output went only under scratch/hadoop/.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/BlockingSpec.scala

FILES MODIFIED (all were already untracked from work order 1):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/JOBS.md
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Common.scala (new counters and config keys)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/DblpParser.scala (normalizedName, normalizedTitle)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/SourceNormalizers.scala (Normalize; affiliationNameId with a Left result as a reject)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Logic.scala (conflict cap; BlockMember, BlockRecord, OversizeBlockRecord, and the Blocking object)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Jobs.scala (J05 and J06 mappers, reducers, and job definitions; cap in ExactJoinReducer; J09 route for candidates)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/Pipeline.scala (--attempt, config wiring, J05 passes and J06 in the chain, manifest v2)
- Tests: DblpParserSpec.scala, SourceNormalizersSpec.scala, LogicSpec.scala, PipelineSpec.scala, all in /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/

FILES DELETED: none. No file outside modules/hadoop was touched. The modified core files in git status are the domain-engineer's work order 2 changes.

JOB GRAPH (sequential, run by scholarchain.hadoop.Pipeline):
- J01 j01-dblp-extract: DBLP XML + DTD -> works/, mentions/, venues/, rejects-v1/
- J02 j02-source-normalize: OpenAlex, Crossref, ORCID JSONL -> works/, mentions/, venues/, institutions/, topics/, edges/, researcher-profiles/, rejects-v1/
- J03+J04 j03-j04-exact-identifier: J01 and J02 works/ and mentions/ -> matches/, conflicts/, oversize-conflicts/, stats/, rejects-v1/
- J05 j05-blocking-key-p0, -p1, -p2: one MapReduce job per blocking level.
  - p0 reads J01 and J02 works/.
  - pk (k > 0) reads p(k-1) rekey/, and runs only if that directory exists.
  - Each pass writes blocks/, rekey/, oversize/, stats/, rejects-v1/.
- J06 j06-candidate-join: blocks/ from every pass, plus J01 and J02 venues/ as a side input -> candidates/, rejects-v1/
- J07+J08 j07-j08-team-pair-expansion: J01 works/ -> teams/, pairs/, stats/
- J09 j09-graph-export: J01, J02, J03+J04, J06, and J07+J08 data directories -> Spark-ready partitions. New partition: blocking-candidates/entity_type=<WORK|RESEARCHER>/
- Order: J01, J02, J03+J04, J05 p0, p1, p2, J06, J07+J08, J09. A job with no input is skipped and gets no _counters file.

KEY-VALUE CONTRACTS (new or changed; the rest are unchanged from work order 1 and documented in JOBS.md):

J05 BlockingKeyMapper:
- Pass 0: (LongWritable, Text WorkRecord) -> (Text level-0 key, Text BlockMember) for the work (`BlockingKeys.work(title, year)`) and for each mention (`BlockingKeys.researcher(rawName, coauthor raw names)`).
- `BlockMember {entityType, id, keys (all levels), work (whole WorkRecord), position}`.
- A member with no key counts MISSING_KEY and is not blocked.
- Pass k: (LongWritable, Text BlockMember) -> (Text level-k key, Text BlockMember), once per level-k key (r2 is multi-key).

J05 BlockingKeyReducer: (key, [BlockMember]) -> `OversizePolicy.action(level, size, maxBlockSize, levels)`:
- Keep: a BlockRecord in blocks/ (`hadoop-block-v1`, members sorted by ID). Counts BLOCK_KEPT.
- Rekey: every member written to rekey/. Counts BLOCK_REKEYED.
- ReportOversize: an OversizeBlockRecord in oversize/. Counts OVERSIZE_BLOCK.
- `Blocking.group` reads the values once and holds at most max+1 members. After the group overflows, members stream to rekey/; `plan` is never called on reducer groups.

J06 CandidatePairMapper: (LongWritable, Text BlockRecord) -> (Text `<entityType>\t<sourceId>\t<targetId>`, Text `[blockingKey, features]`) for each member pair, with sourceId < targetId.
- Features come from `Features.researcher` or `Features.work`. Venue names come from the venues/ side input.
- Two mentions of the same work are skipped and count SAME_WORK_PAIR_SKIPPED.

J06 CandidateJoinReducer: (pair key, [[blockingKey, features]]) -> one MatchCandidate in candidates/.
- blockingKey is the smallest key among the pair's meetings. This de-duplicates (sourceId, targetId) across r2 blocks, as the entity-resolution report required.
- Extra meetings count DUPLICATE. Each written candidate counts MATCH_CANDIDATE.
- Provenance is DERIVED, with `sourceRecordId = key|a|b` and `policyVersion = norm-v1.block-v1.feat-v1`.

J03+J04 change: a conflicting group larger than max-conflict-group-size emits no pairs. It counts OVERSIZE_CONFLICT (and still counts CONFLICT), and writes one oversize-conflicts/ line `{identifier, entityType, groupSize, maxConflictGroupSize}`.

Normalization (item 2):
- `normalizedName = Normalize.name(raw).comparable`.
- `normalizedTitle = Normalize.title(title)`, or None when the result is empty.

Affiliation IDs (item 3): `StableIds.affiliationNameId`. A Left result rejects the record with `invalid-affiliation` and counts SCHEMA_FAILURE.

PARTITIONING AND COMPRESSION:
- Explicit HashPartitioner in every job. J05 partitions by block key; J06 by pair key.
- No combiners.
- Map output uses zlib (DefaultCodec). Final output is gzip JSON Lines through MultipleOutputs and LazyOutputFormat.
- Reducers: 2 for the sample runs, 8 for the full run. The full run also used local runner options: map parallelism 6, reduce parallelism 4, `shuffle.input.buffer.percent=0.1`.

COUNTERS, sample runs (datasets/samples, default config: max-block 1000, max-conflict 100). Attempt 1 is run-65e25f48aad047fb92a546a464394704 and attempt 2 is run-1498975ff961a909c06c2d6b52192129. Every counter is identical between the two:
- J01: INPUT 40, ACCEPTED 40, every other counter 0. Outputs: works 40, mentions 163, venues 1.
- J02: INPUT 84, ACCEPTED 81, REJECTED 3. Outputs: works 80, mentions 326, institutions 137, edges 42, topics 10, venues 2, researcher-profiles 1, rejects-v1 3.
- J03+J04: INPUT 609, ACCEPTED 609, EXACT_MATCH 536, CONFLICT 0, OVERSIZE_CONFLICT 0.
- J05 p0: INPUT 120, ACCEPTED 120, BLOCK_MEMBER 609, BLOCK_KEPT 202, BLOCK_REKEYED 0, OVERSIZE_BLOCK 0, MISSING_KEY 0. p1 and p2 skipped (nothing re-keyed).
- J06: INPUT 202, MATCH_CANDIDATE 667, DUPLICATE 0, SAME_WORK_PAIR_SKIPPED 0. The 0 agrees with an independent scan of the sample XML, which finds no same-work family-name and initial collisions.
- J07+J08: INPUT 40, EMITTED_PAIR 346, OVERSIZE_TEAM 0 (threshold 100).
- J09: INPUT 2391, ACCEPTED 2391, REJECTED 0. Outputs: works 120, author-mentions 489, blocking-candidates 667, match-candidates 536, coauthor-pair-candidates 346, publication-teams 40, institutions 137, graph-edges 42, topics 10, venues 3, researcher-profiles 1.

COUNTERS, full DBLP subset run (run-65e25f48aad047fb92a546a464394704 under scratch/hadoop/wo4-full; default config; 437.56 s real; peak RSS 2.78 GB):
- Inputs: scratch/datasets/dblp/snapshot=2026-10-03/full/works.xml with the dblp-2023-06-28 DTD. Enrichment is the only JSONL that exists: scratch/datasets/{openalex,crossref}/sample/works.jsonl (40 lines each) and orcid/sample/orcid-sample.minimized.jsonl (30 lines).
- J01: INPUT 110867, ACCEPTED 110867, REJECTED 0, REPEATED_AUTHOR_NAME 6. Outputs: works 110867, mentions 436445, venues 46.
- J02: INPUT 110, ACCEPTED 110, REJECTED 0. Outputs: works 80, mentions 326, institutions 172, edges 89, topics 10, venues 2, researcher-profiles 30.
- J03+J04: INPUT 547718, ACCEPTED 547718, EXACT_MATCH 130158, EXACT_UNMATCHED 105776, MISSING_KEY 5051, CONFLICT 0, OVERSIZE_CONFLICT 0.
- J05 p0: INPUT 110947, BLOCK_MEMBER 547718, BLOCK_KEPT 184468, BLOCK_REKEYED 9 (11203 members to rekey/), OVERSIZE_BLOCK 0, MISSING_KEY 0.
- J05 p1: INPUT 11203, BLOCK_MEMBER 11203, BLOCK_KEPT 2459, BLOCK_REKEYED 0, OVERSIZE_BLOCK 0. p2 skipped.
- J06: INPUT 186927, MATCH_CANDIDATE 20258400 (RESEARCHER 20221514, WORK 36886), SAME_WORK_PAIR_SKIPPED 1008, DUPLICATE 0, REJECTED 0.
- J07+J08: INPUT 110867, EMITTED_PAIR 1019535, OVERSIZE_TEAM 4.
- J09: INPUT 22067027, ACCEPTED 22067027, REJECTED 0, SCHEMA_FAILURE 0, DUPLICATE 0, CONFLICT 0.
- Disk: 1.6 GB in total. J06 candidates take 649 MB and the J09 copy 653 MB. Free space stayed at or above 13 GiB, and the watchdog never fired.

REJECTS:
- Samples: 3, all in J02, reason `unsupported-identifier` (the synthetic ORCID fixtures), at scratch/hadoop/wo4-samples/snapshot=2026-10-03/run=<runId>/j02-source-normalize/rejects-v1/.
- Full run: 0 in every job.

TEAM AND BLOCK EXTREMES (full run):
- Maximum team size: 287 (dblp:journals/jpdc/Aartsen15).
- Largest pair expansion: 41,041 pairs, from that work.
- Largest blocking group: 1,632 members (r0:zhang|y), re-keyed.
- Largest kept block: 981 (r0:zhang|j). Largest p1 block: 144 (r1:chen|yong).
- Largest exact-identifier group: 280 (orcid:0000-0002-3090-1059).
- Samples: max team 12, 66 pairs; largest blocking group 6 (r0:liu|w and r0:mueller|f).

JAR INSPECTION (modules/hadoop/target/scala-3.3.7/hadoop-assembly-0.1.0.jar, 10,490,527 bytes, 4,791 entries, Main-Class scholarchain.hadoop.Pipeline):
- 0 top-level scala/ entries; 3,593 entries under scholarchain/shaded/scala/.
- 0 org/apache/hadoop/ entries. All 151 paths containing "hadoop" are under scholarchain/hadoop/ (this module's own classes).
- Remaining top-level namespaces: scholarchain/ (core, entityresolution, hadoop, shaded), upickle/, ujson/, upack/, geny/, com/typesafe/config, META-INF/, scholarchain.conf, and the scala-library license files.
- Runtime check: `java -cp <assembly jar>:hadoop-client-api:hadoop-client-runtime:snappy:slf4j-api:commons-logging:jsr305` (no unshaded Scala or upickle on the classpath) ran the chain on the samples and gave the same logical hash as the sbt runs. It also ran the full-subset run.

OUTPUT PATHS (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/hadoop/):
- wo4-samples/snapshot=2026-10-03/run=run-65e25f48aad047fb92a546a464394704/ (attempt 1)
- wo4-samples/snapshot=2026-10-03/run=run-1498975ff961a909c06c2d6b52192129/ (attempt 2)
- wo4-jar-samples/snapshot=2026-10-03/run=run-65e25f48aad047fb92a546a464394704/ (jar smoke run)
- wo4-full/snapshot=2026-10-03/run=run-65e25f48aad047fb92a546a464394704/ (full run)
- tmp-full/ (empty Hadoop temp directory)

COMMANDS RUN:
- `sbt -batch hadoop/scalafmtAll hadoop/compile`, `hadoop/Test/compile`, `hadoop/test`, and `hadoop/testOnly` while iterating. One run had 2 failures from test-side mistakes, both fixed: a fixture DOI that fails core validation, and a test max-block-size of 3 that produced no oversize block, lowered to 2.
- `sbt check`, twice. Logs: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/00e1d7f4-8996-4366-b4b5-a454e6186778/scratchpad/hcheck1.log and hcheck2.log
- `sbt -batch packageJobs`; `unzip -Z1` and `unzip -p` on the jar.
- `sbt -batch "hadoop/Test/runMain scholarchain.hadoop.Pipeline <args>"`, twice in one session, with `--out scratch/hadoop/wo4-samples --code-version 785732c...-dirty --reducers 2`.
- The plain `java -cp` jar run on the samples.
- /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/00e1d7f4-8996-4366-b4b5-a454e6186778/scratchpad/tools/full-run.sh: the full run, with a free-disk watchdog at 3 GiB.
- `sbt "export hadoop/Compile/dependencyClasspath"`.
- Read-only Python and shell inspection of the outputs, plus a Python pre-estimate of block sizes, which predicted about 20.3M researcher pairs.

TEST RESULTS (identical on both sbt check runs):
- core: 150 run, 150 succeeded, 0 failed.
- entityResolution: 30 run, 30 succeeded, 0 failed.
- hadoop: 54 run, 54 succeeded, 0 failed, 0 canceled, 0 ignored. By suite: BlockingSpec 6 (new), DblpParserSpec 12 (+1), LogicSpec 13 (+1), SourceNormalizersSpec 7 (+1), DblpRecordReaderSpec 6, PipelineSpec 10 (+3).
- markTestRun wrote .claude/.last-test-run both times. No source changed after the second run.

VALIDATION RESULTS:
- J05 equals the entity-resolution oracle `OversizePolicy.plan`:
  - in memory for max-block-size 1, 2, 3, and 100 (BlockingSpec);
  - on real job output for the samples at max-block-size 2, where all three passes run and oversize blocks occur (PipelineSpec).
- J06 writes exactly one candidate per distinct pair of the kept blocks. Each carries the smallest blocking key, and DUPLICATE equals the number of extra meetings (PipelineSpec).
- Features equal the entity-resolution functions applied directly.
- Conflict cap: a group above the cap writes no pairs and is reported as OversizeConflict (LogicSpec).
- `--attempt 0` is refused. An explicit attempt whose run directory exists is refused before any job runs.
- Affiliation IDs match the domain-engineer's frozen value (sci:affn-v1:981e0a59669da94037cd74352e4b6112). A name with no letters or digits gives the `invalid-affiliation` reject.
- Determinism: sample attempts 1 and 2 have identical counters and an identical sha256 over all job directories (sorted lines, run ID masked): 7bbc22e2fe3567b26fa1218bac0f0779953ec5a562204283567bba1747c706ba. The jar run gives the same hash.

ASSUMPTIONS:
1. Two mentions of the same work are never paired in J06. This mirrors the J03+J04 rule that one person holds one author slot; it skipped 1,008 pairs in the full run. The entity-resolution-engineer should confirm it.
2. Blocked candidates set `provenance.policyVersion` to "norm-v1.block-v1.feat-v1". There is no match-policy version yet.
3. Venue names come from a side input of the J01 and J02 venues/ tables (48 venues in the full run), not from a separate join job.
4. Absent features are left out of the map: the entity-resolution functions return Option, and I applied them unchanged.
5. Blocked candidates are exported to their own partition, blocking-candidates/, separate from the exact-ID match-candidates/.
6. A member with no key counts MISSING_KEY only and writes no output line, because it is not a malformed record.

RISKS:
- Volume: the PROVISIONAL max-block-size of 1000 yields 20.26M researcher candidates from 110,867 works. The largest kept blocks hold 900 to 981 members, about 470k pairs each. Phase 5 cost and calibration need a decision on this value (entity-resolution-engineer and the human).
- Run ID collision: the full run and the sample run share run ID run-65e25f48aad047fb92a546a464394704. Their inputs differ, but the code version, config version, and snapshot labels are the same, and `StableIds.runId` does not include input paths or checksums. Only the different output roots kept them apart. Fixing this needs the domain-engineer (stable-ID rules) or a hadoop change that adds input checksums to the run ID inputs. I made neither change.
- Each J05 researcher member carries its whole WorkRecord, so shuffle size per work grows with the square of its team size. The 287-author work ran fine here.
- Values that arrive after a block overflows are not de-duplicated. Member IDs are unique by construction, so this is safe today.
- J03+J04 still holds a whole identifier group in memory; the largest was 280 entries.
- J09 re-writes all blocked candidates, which doubled their storage (653 MB).
- The full run used sample-size enrichment: 40 OpenAlex, 40 Crossref, and 30 ORCID records. The full OpenAlex fetch was still downloading as API list pages, which J02 does not read.
- Nothing has run on EMR, and no mini-cluster test exists (tests use the local job runner).
- Run under sbt `Test/runMain`, each run still printed the known Hadoop shutdown-hook NoSuchFileException after its jobs had finished. The plain java jar runs printed none.
- The Bash guard flags Scala text such as `b.key` as a secret key file. I used the file tools for those edits.
- The disk was 94% full during the work (about 13 GiB free).

MANUAL ACTION REQUIRED:
- Decide on the max-block-size value, given the 20.26M-candidate result.
- Route the run-ID-uniqueness risk to the domain-engineer.
- Have the entity-resolution-engineer confirm ASSUMPTION 1.
- Optionally delete the scratch/hadoop/wo4-* runs (about 1.6 GB for the full run).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: local or EMR sample run, deterministic output checks, test-engineer, code-reviewer APPROVE
````
