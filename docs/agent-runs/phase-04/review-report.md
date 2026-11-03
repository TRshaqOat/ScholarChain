````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04 review. Review every uncommitted Phase 4 change (modules/hadoop/**, modules/entity-resolution/**, and the work order 2 changes in modules/core/**) against docs/agent-runs/phase-04/prompt.md, the spec's Phase 4 gate and "Detailed Hadoop requirements", and D-01 to D-03 and D-10. The four human rulings and the listed human-approved decisions are not findings. Report only findings that block ratification.

VERDICT: APPROVE

None of my findings is CRITICAL or MAJOR. Three MINOR notes are listed below; none of them blocks ratification.

SCOPE REVIEWED:
I read every one of these files in full:
- Core (tracked diff): modules/core/src/main/resources/scholarchain.conf, StableIds.scala, config/ConfigLoader.scala, plus the new tests in StableIdsSpec.scala (line 97) and ConfigLoaderSpec.scala (line 110).
- Entity resolution (untracked): Normalize.scala, Blocking.scala, Features.scala, and BlockingSpec.scala.
- Hadoop (untracked): JOBS.md, Common.scala, DblpInputFormat.scala, DblpParser.scala, SourceNormalizers.scala, Logic.scala, Jobs.scala, Pipeline.scala, and the specs TestSupport, PipelineSpec, DblpRecordReaderSpec, DblpParserSpec, LogicSpec, and BlockingSpec.

For context I also read build.sbt (module edges and shade rule), .gitignore, the sample DTD, datasets/config/subset.v1.json, the core record definitions, hcheck1.log and hcheck2.log, and the scratch _counters for wo4-samples (both attempts) and wo4-full. I did not open NormalizeSpec, FeaturesSpec, or SourceNormalizersSpec. Their test names and pass results are in hcheck2.log.

GATE CHECKS (each verified against the code):
- At least two real MapReduce jobs: yes. Jobs.scala defines nine Mapper/Reducer job definitions on the Java mapreduce API (D-03): J01, J02, J03+J04, J05 p0 to p2, J06, J07+J08, and J09. Pipeline.scala runs each with Job.waitForCompletion. There are no local loops.
- DBLP reader never splits a record: yes. A record belongs to the split holding its `<` (DblpInputFormat.scala:91-93), and the reader reads past the split end to finish it. Tag matching requires a delimiter after the name, so `<booktitle>` is not taken for `<book>`. No child element in the DTD shares a record name. The test checks a two-way split at every byte offset plus 1-, 2-, 3-, 17-, 64-, and 257-byte splits against an independent regex (DblpRecordReaderSpec:78-94).
- Entities and Unicode: entities come from the DTD (DblpParser.scala:13-35). The parser runs with SUPPORT_DTD off and external entities off, so XXE is closed. Input is decoded as ISO-8859-1, and tests cover `&eacute;`, `&#8364;`, builtins, and undeclared entities.
- Pair generation: correct, in Logic.scala:121-136. Tests cover 0, 1, 2, 3, 4, 12, and 1,000 authors (LogicSpec:46-80). Order is CanonicalPair, and #10 sorts before #2. Each pair carries work ID, year, the DBLP provenance, and positions in the mention ID, as core's CoauthorPairCandidate doc requires.
- Counters: every required counter exists (Common.scala:16-26). They are registered at zero in every map task, and PipelineSpec:104-106 checks that they are visible.
- Reject outputs: the `rejects-v1\t` key prefix is handled in every reducer. The 3 J02 rejects are checked in PipelineSpec:111-124.
- Determinism: reducers sort their values, and kept blocks use a TreeMap. PipelineSpec:164-175 compares J09, J02 rejects, J04 conflicts, J05 blocks and oversize, and J06 candidates with the run ID masked, and also compares counters. The scratch counters for run-65e25f48... and run-1498975f... are identical (I checked j06 and j07-j08).
- No mutable shared state: only per-task vars built in setup. The objects hold immutable vals only.
- Paths: all paths come from CLI arguments or configuration. All file access goes through the Hadoop FileSystem API (Path.getFileSystem), so s3a:// URIs work. The output layout is snapshot=/run=.
- JOBS.md: covers every item in the spec's per-job list (input format, key-value contracts, combiner, partitioner, counters, rejects, reducers, S3 prefixes, codec, idempotency, fixture).
- Entity-resolution functions: pure and deterministic. They import only scholarchain.core and the JDK. There are no thresholds, scores, or decisions; OversizePolicy.action takes maxBlockSize as a parameter. Feature values stay in [0, 1], and the grid test covers this.
- Core: both config keys exist, have env overrides, and are validated as at least 1 (ConfigLoader.scala:240-243). The test rejects 0 and -1 and would fail if the check were removed. affiliationNameId returns Left for an empty canonical name and is tested against frozen values. Its IDs pass both isAffiliationNameId and isNamespacedId.
- Nothing writes into the repository: tests use Files.createTempDirectory and createTempFile. Job output went to scratch/, which is gitignored (.gitignore:27) and was named by the work order.

FINDINGS (ranked; all MINOR, non-blocking):
1. MINOR, docs and code comments disagree with behavior. Logic.scala:50-51 and JOBS.md:80 say an oversize conflicting group "still matches nothing". For researcher keys, Logic.scala:97-100 still emits matches for mentions on other works while the clashing group is capped.
   - Failure input: orcid:X with slots B#1, B#2, B#3 (cap 2) plus A#1. Output is a match for A#1 and an OversizeConflict for B.
   - Impact: a misleading contract for Phase 5 readers. The behavior itself matches the non-oversize rule at Logic.scala:48-49.
   - Owner: hadoop-engineer.
2. MINOR, scope wording. Work blocking keys use title and year only (entity-resolution Blocking.scala:67-74). Work order 1's J05 line lists venue among block attributes, and the spec's item 4 also lists affiliation and source. Venue and affiliation are used only as features (Features.scala:92-96, 122-134).
   - Impact: possibly none. Venue IDs are not comparable across sources (dblp-venue: vs openalex:S vs issn:), so a venue key would split cross-source pairs. The human should record whether this is accepted.
   - Owner: entity-resolution-engineer.
3. MINOR, reducer memory in J01/J02. NormalizedRecordReducer (Jobs.scala:132, 153-157) holds and sorts every repeat of an enrichment entity. J01 emits the `venues\t<id>` key once per work, so one group holds as many values as the venue has works. The cap covers only conflict output; ExactJoinReducer (Jobs.scala:260) also holds a whole identifier group, which the agent already disclosed.
   - Impact: fine at the D-10 subset (110,867 works across 46 venues). A full-DBLP venue such as CoRR would make one large reducer group, which a combiner or a per-venue count would avoid.
   - Owner: hadoop-engineer.

OWNERSHIP CHECK:
- modules/core/src/main/resources/scholarchain.conf -> domain-engineer -> author domain-engineer (work order 2) -> yes
- modules/core/src/main/scala/scholarchain/core/StableIds.scala -> domain-engineer -> yes
- modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala -> domain-engineer -> yes
- modules/core/src/test/scala/scholarchain/core/StableIdsSpec.scala and config/ConfigLoaderSpec.scala -> domain-engineer (module-local tests) -> yes
- modules/entity-resolution/src/** (3 main, 3 test) -> entity-resolution-engineer -> yes
- modules/hadoop/** (JOBS.md, 7 main, 7 test) -> hadoop-engineer -> yes
- docs/agent-runs/phase-04/agent-report.md -> human-owned, assembled by the orchestrator -> not under review
- Module edges: hadoop -> core and entityResolution (build.sbt:45-46), as permitted. entityResolution -> core only. Hadoop uses only the ER pure functions (Normalize, BlockingKeys, OversizePolicy.action, Features, Versions), as work orders 3 and 4 directed. No tests/** changes.

FLOOR AND FENCE CHECK: no file under .claude/, CLAUDE.md, docs/agents.md, .mcp.json, build.sbt, or project/ was changed (per the git status as pasted). Nothing touches hooks, permissions, `check`, `markTestRun`, or reviewer fences. No widening and no weakening.

EVIDENCE GAPS (non-blocking):
- I could not observe the jar inspection claims (0 top-level scala/ entries, 0 org/apache/hadoop/ entries, the standalone `java -cp` run) because no listing output was supplied. The jar exists at modules/hadoop/target/scala-3.3.7/hadoop-assembly-0.1.0.jar, and the shade rule at build.sbt:50-52 is consistent with the claim.
- The logical-hash values (7bbc22e2..., 80587c45...), the full-run wall time (437.6 s), and peak RSS (2.78 GB) are unobserved. The full-run counters for J06 (MATCH_CANDIDATE 20,258,400; SAME_WORK_PAIR_SKIPPED 1,008) and J07+J08 (EMITTED_PAIR 1,019,535; OVERSIZE_TEAM 4) match the scratch _counters files.
- The work order says HEAD is 96a52df, but the session's git snapshot shows 785732c as the latest commit, and work order 4 ran with code version 785732c-dirty. Please confirm which HEAD the human gate records.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (from the orchestrator's paste; I confirmed the same lines in hcheck2.log):
"[info] Total number of tests run: 150 / Tests: succeeded 150, failed 0 ... [info] Total number of tests run: 30 / Tests: succeeded 30, failed 0 ... [info] Total number of tests run: 54 / Tests: succeeded 54, failed 0, canceled 0, ignored 0, pending 0 / All tests passed. ... markTestRun: tests passed, wrote .../.claude/.last-test-run / [success] Total time: 101 s". hcheck1.log shows the same 150/30/54 and the markTestRun line.

VALIDATION RESULTS:
- PipelineSpec checks J05 output against the OversizePolicy.plan oracle at max-block-size 2, with all three passes run and oversize blocks present.
- PipelineSpec checks J06 candidate de-duplication and the smallest blocking key per pair.
- BlockingSpec checks group buffering for max sizes 1, 2, 3, and 100.
- All of these tests are independent of the code under test, so they would fail if the behavior broke.

ASSUMPTIONS:
- The four human rulings and the listed human-approved decisions are excluded from findings.
- Authorship follows the agent reports and the path rows. I cannot verify which agent wrote which file without Git history.

RISKS (already accepted or disclosed, restated for the gate):
- 20.26M researcher candidates at max-block-size 1000 (calibration is ruled to Phase 5).
- Run ID collision across input sets (Phase 5 ruling).
- No s3a or EMR execution yet. Local job runner only.
- The adversarial pass is deferred to Phase 11.

MANUAL ACTION REQUIRED: the human decides whether MINOR findings 1 to 3 are routed now or recorded as residual risks. Confirm the HEAD commit for the gate record.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate on APPROVE. If the MINOR findings are routed, finding 1 and finding 3 go to hadoop-engineer and finding 2 goes to entity-resolution-engineer, followed by re-review.
````
