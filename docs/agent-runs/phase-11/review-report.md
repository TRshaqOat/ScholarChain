## Code review 1: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11, session 2, code review 1. Adversarial, read-only review of the repository at HEAD 675dc01 plus the Phase 11 change set (WO1 service-engineer, WO2 rag-engineer, WO3 test-engineer), the ALSO CHECK items, and the ruling 8 carried items.

VERDICT: REQUEST_CHANGES

The rules do not allow APPROVE here. Five MAJOR defects (D1 to D5) are confirmed and still open; they are only wrapped as pending. The review also adds seven new MINOR findings. Five come from carried or stale items verified against the files. Two are timing assumptions left in tests. No change was CRITICAL. Nothing weakened the deterministic floor or widened a fence. No secrets were found.

SCOPE REVIEWED:
- The whole change set in /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/39fb2f3b-8d01-4793-9c74-67cbae6acfda/scratchpad/phase-11-change-set.txt (5070 lines): 11 modified tracked files, plus tests/TESTS.md and the 18 Scala files under tests/.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/agent-runs/phase-11/agent-report.md, all 624 lines.
- The production code behind D1 to D13:
  - Verifier.scala, Semantic.scala and Workflow.scala in rag;
  - Http.scala, Composition.scala, Resilience.scala and Worker.scala in services;
  - DblpParser.scala, Common.scala, Jobs.scala and JOBS.md in hadoop;
  - Normalize.scala in entity-resolution;
  - StableIds.scala, Validation.scala and ConfigLoader.scala in core;
  - WindowSummaries.scala in storage.
- The services timing tests: SaturationSpec, BuildingBlocksSpec, GraphEvidenceApiSpec, QueryApiSpec, WorkerSpec.
- Human gates for phases 03 to 10, and the review reports for phases 05, 06 and 10 for the deferred minor findings.
- WO1, WO2 and WO3 logs: pending-evidence.log, junit-counts, saturation-10-summary, sbt-check-2.log.
- The WO2 source audits and answers for runtime run-422d912ac162f340f5f31a1b63b65e19.
- Not read line by line: every module of the repository. The whole-repository axes were checked by targeted searches:
  - secret patterns in modules, tests, config and prompts (none found);
  - Spark collect() calls (all bounded);
  - XML parser hardening (secure);
  - Neptune query parameterisation (parameterised).
  - infra/, scripts/ and config/ do not exist yet, so axis 12 (cost and cleanup) has no artifacts to review.

====================================================================
FINDINGS (new; D1 to D13 are in section 3)
====================================================================

1. MINOR. Category 2, contract and schema drift. Carried from Phase 03 risk 2, which the gate said to "fix in the Phase 11 repair loop".
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/Validation.scala:103-112, the AuthorshipRecord validator.
- Defect: `position` is checked only through `mentionId == workId#position`. The AuthorMention validator checks `position >= 1` (line 72); this one does not.
- Failure input: an AuthorshipRecord with position = 0 and mentionId = "dblp:conf/x/W1#0", or position = -1 with mentionId "...#-1". Both validate.
- Impact: an authorship with an impossible author position is accepted into canonical/authored. WO3 did not test this.
- Owner: domain-engineer.
- How verified: read the validator and compared it with lines 68-79.

2. MINOR. Category 2, configuration. Carried from Phase 06 review 1 m8, assigned to domain-engineer in Phase 11.
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/resources/scholarchain.conf:103 sets `community-max-iterations = 20`. /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/resources/scholarchain-spark.conf:22-25 sets 10, and its comment says "The core analytics.community-max-iterations is not used".
- Failure input: an operator raises the core key to 50. Label propagation still stops at 10. The stored windows and the runtime's uncertainty line ("10-round cap") disagree with the configuration that was read.
- Impact: one algorithm parameter has two sources, and the core one is dead. The only reference to it is a positivity check at ConfigLoader.scala:671. This conflicts with the externalized-configuration invariant.
- Owner: domain-engineer (remove the core key or align it). spark-engineer only if the Spark side changes.
- How verified: searched for every use of communityMaxIterations.

3. MINOR. Category 10, tests that depend on timing. Includes the remainder of the Phase 10 SaturationSpec flake.
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/BuildingBlocksSpec.scala:118-122. It runs 300 ms of waiting and then 300 ms of running against a 500 ms timeout, which leaves about 200 ms of margin on each side. Line :113 also asserts `< 1000` ms.
- Location: GraphEvidenceApiSpec.scala:211, `Thread.sleep(300) // let the second request reach the queue`. This orders requests with a sleep.
- Location: SaturationSpec.scala:15-17. The scaladoc says "no assertion depends on how long anything takes", which overstates it (see section 4c).
- Failure input: a loaded build machine, the same conditions as human check 1 in Phase 10.
  - BuildingBlocksSpec: one sleep that overruns by more than about 200 ms turns "ok" into 504.
  - GraphEvidenceApiSpec: a request that reaches the queue after more than 300 ms makes the probe queue instead of answering 503.
- Impact: intermittent `sbt check` failures of the same kind as Phase 10 ruling 25.
- Owner: service-engineer. These are module-local tests.
- How verified: read each test against Resilience.scala:61-111. WO1's RISKS already flags the BuildingBlocksSpec margin.

4. MINOR. Category 10, wall-clock dependence in a new test, against ruling 5.
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/tests/adversarial/scholarchain/tests/adversarial/GraphBoundsTimeoutAdversarialSpec.scala:179. `assert(entered.getCount == 0)` runs right after a sync POST under `operations.synchronous-deadline-ms = 300` (line 162).
- Failure input: a cold or loaded JVM where the workflow has not yet reached its first key-value read (the resolution stage) when the 300 ms hard timeout answers 504. The 504 is still correct, but the latch has not counted down yet, so the assertion fails. This is the Phase 10 flake pattern.
- Line :150 has the same pattern with less risk: the store call starts at once.
- Owner: test-engineer.
- How verified: compared the test with the hard-timeout path (CallPolicy with interruptOnTimeout false for workflows).

5. MINOR. Category 11, documentation not supported by the implementation.
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md:509 (section 21) still says a percentage is not converted and a share written as a percentage is removed.
- This contradicts verifier-v6 in RUNTIME.md:295-296 and Verifier.scala:170-173 and :556-560.
- Failure input: a reader relies on section 21 for a live share claim written as a one-decimal percentage. The code now verifies that claim.
- Owner: rag-engineer.
- How verified: searched RUNTIME.md and read the code.

6. MINOR. Category 11. Carried from Phase 06 review 2 n1 ("JOBS.md needs rewording").
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/JOBS.md:469-470 still calls `matchConfidence` "per mention" and `identityConfidence` "uncertainty of each resolution".
- JOBS.md:42 and the Phase 06 gate both say it is the cluster's minimum accepted-edge confidence. Canonical.scala:231 copies the mapping value, and :294 and :332 compute it with `min(...)`.
- Failure input: a Phase 12 to 17 consumer reads it as a per-mention confidence.
- Owner: spark-engineer.
- How verified: compared the JOBS.md lines with the Canonical.scala aggregation.

7. MINOR. Category 11.
- Location: /Users/achyutpatel/Developer/Cloud/ScholarChain/tests/TESTS.md:86 still says "Phase 11 ruling 4 also allows new files under modules/*/src/test/scala/**/adversarial/". Ruling 11 withdrew that grant.
- Owner: test-engineer, in the rerun.
- How verified: read the file and ruling 11.

====================================================================
3. D1 TO D13 (WO3 severity and owner, then my decision)
====================================================================

Any exception inside ScalaTest's `pendingUntilFixed` makes a test pending, so pending status alone proves nothing. Each line below was confirmed by reading the code and the matching line in pending-evidence.log.

- D1 (MAJOR, rag-engineer): CONFIRMED MAJOR, rag-engineer.
  - Verifier.scala:550-584 checks only NumberRe digit matches, plus number words up to 99. A claim with no digit only has to pass asserted-values. A VERIFIED status on a false quantity breaks groundedness.
  - The wrapped test fails for the stated reason: evidence line 6 lists all four texts.
- D2 (MAJOR, rag-engineer): CONFIRMED MAJOR, rag-engineer.
  - The "10" in "more than / fewer than / over / under 10" binds the stored 10, and no qualifier rule exists.
  - Fails for the stated reason (evidence line 8).
- D3 (MAJOR, rag-engineer): CONFIRMED MAJOR, rag-engineer.
  - The ZeroRules (RUNTIME.md section 12) fire only when "not" directly precedes a relation word. "not 1 distinct coauthor", "does not have 10" and "it is false that" are untouched.
  - Fails for the stated reason (evidence line 10).
  - D1 to D3 are concrete cases of the Phase 09 carried risk "verification does not catch qualitative misreadings".
- D4 (MAJOR, rag-engineer): CONFIRMED MAJOR, rag-engineer.
  - Semantic.scala:128-133 lets a thrown TransientStoreException escape, and turns a Left into an IllegalStateException. Workflow.scala:565 has no handler, so the question FAILS (502). By contrast, an embedding failure degrades at Semantic.scala:106-118.
  - storage-engineer adding read retries in S3VectorStore.reader would only be defence in depth, not the fix.
  - Fails for the stated reason (evidence line 36).
- D5 (MAJOR, service-engineer): CONFIRMED MAJOR, service-engineer.
  - Http.scala:231 `finally ex.close()`: the JDK exchange closes, and so drains, the unread request stream before it flushes the response. There is no request read timeout, and readNBytes at :173 also blocks on a slow body within the cap.
  - Unauthenticated: a few connections pin all T HTTP threads.
  - The expected statuses match the error codes: REQUEST_TOO_LARGE and INVALID_FIELD are 400 (Enums.scala:126-127), UNAUTHORIZED is 401.
  - Fails for the stated reason (evidence line 38, all three cases).
- D6 (MINOR, hadoop-engineer): CONFIRMED MINOR, hadoop-engineer.
  - DblpParser.scala:150 accepts a name of "". The core AuthorMention validator (Validation.scala:68-79) has no rawName check, and JOBS.md:45 lists "an author without a name" as a reject.
  - The evidence log shows only case N1, because the foreach stops at the first failure. By code reading all four cases reach `f.text.trim == ""`.
  - Fails for the stated reason.
- D7 (MINOR, entity-resolution-engineer): CONFIRMED MINOR, entity-resolution-engineer.
  - Normalize.scala:87-88 turns \p{Cf} characters into spaces, which splits the family. The effect is lost recall, never over-merging.
  - Fails for the stated reason (evidence line 12, all eight code points).
- D8 (MINOR, domain-engineer): CONFIRMED MINOR, domain-engineer.
  - StableIds.scala:59-63 strips one prefix and does not trim afterwards. Validation.scala:87 then rejects the record. The reject is counted, not silent.
  - Fails for the stated reason (evidence line 14, all four inputs).
- D9 (MINOR, domain-engineer): CONFIRMED MINOR, domain-engineer.
  - No percent-decoding, so exact-ID matching loses recall on SICI DOIs.
  - Fails for the stated reason (evidence line 16).
  - Repair risk: decoding changes existing doi: work IDs, which is a stable-ID change. Decoding a literal "%25" twice also breaks D8's idempotence, so D8 and D9 must be fixed together.
- D10 (MINOR, storage-engineer): CONFIRMED MINOR, storage-engineer.
  - WindowSummaries.scala:129-135 checks only pairwise bounds.
  - I checked that all three tampered rows are impossible, and that the three control rows (lines 70-74) satisfy the identity a fix would add.
  - Fails for the stated reason (evidence line 18).
- D11 (MINOR, domain-engineer): CONFIRMED MINOR, domain-engineer.
  - ConfigLoader.scala:373-377 builds the index map from `getConfig(...).entrySet`, which skips null-valued entries.
  - Fails for the stated reason (evidence line 22). The `leaves.size > 100` precondition passed.
- D12, two tests (MINOR, domain-engineer): CONFIRMED MINOR, domain-engineer.
  - validate() (ConfigLoader.scala:603-) has no blank-value check for region, endpoint, vector bucket, index names, model IDs or policy versions.
  - All eight environment variables are mapped in EnvOverrides (lines 273-298), so both tests can pass once fixed.
  - Both fail for the stated reason (evidence lines 24 and 26).
- D13 (MINOR, domain-engineer): CONFIRMED MINOR, domain-engineer.
  - `c.getInt` at ConfigLoader.scala:344 silently truncates a decimal value.
  - Fails for the stated reason (evidence line 28).

No severity or owner changed.

====================================================================
4. ALSO CHECK
====================================================================

(a) test-engineer edited no production code and no existing test file: CONFIRMED, as far as the evidence allows.
- WO3's FILES MODIFIED is "none".
- WO3 left only untracked files under tests/, and there are no untracked files under modules/, prompts/ or project/.
- The 11 modified tracked files are exactly the union of:
  - WO1's list (7 files, all modules/services/**);
  - WO2's list (4 files, all modules/rag/**).
- Each diff hunk matches the change its author described:
  - VerifierSpec: the B23 and N5 tests;
  - CorpusSpec: the B13/B24 and B17 tests;
  - CorpusQuerySpec: the m3 change;
  - SaturationSpec: the rewrite.
- The guard blocked WO3's one attempted module write, and that file does not exist.
- Git history was not available to me, so authorship is not proven byte for byte.

(b) Every pendingUntilFixed test fails for its stated reason: CONFIRMED for all 14 (D1 to D11, D12 twice, D13), by code reading plus pending-evidence.log.
- Each wrapped test should start passing, and so break its wrapper ("expected to fail"), once the stated defect is fixed. The detail is in section 3.
- I found no unrelated failure path inside a wrapper. Fixture setup inside the wrapper would also count as pending (for example withWorld in D4 and D5, the precondition in D11), but each of those fixtures is exercised by a passing sibling test.
- Note for repair: the D6 evidence covers only its first case. D4 and D5 were taken from the "merged form" run (run-subset-4), whose test names match the final files.

(c) WO1's SaturationSpec change and wall-clock dependence: PARTIALLY.
- Removed: the cold-workflow 300 ms dependence, which caused the flake; every latency assertion; the drain sleeps; the 1500 ms poll sleep. In their place: a frozen question clock, `entered` and `paused` semaphores, and 20 s bounds that only catch failures.
- Remaining:
  - :51 and :87-89: the 504 relies on the 1000 ms hard timeout firing. This is the timer under test, so it is acceptable.
  - :52-53 with :93-98, :102, :104 and :140-142: each 200 or successful probe implicitly requires the store call or probe to finish within 1000 ms (dynamodb-ms and s3-ms). This is the same bound as the removed `jMs < 1000` assertions, now enforced by the timeout rather than an assert.
  - :115-116 and :129-136: DEADLINE_EXCEEDED requires the held workflow to start within the 1000 ms backstop (deadline 200 + model timeout 800). WO1 disclosed this.
  - :124: a real 1 s `Thread.sleep` inside the injected pause. Nothing asserts on it.
- Outside SaturationSpec: see finding 3 (BuildingBlocksSpec:113 and :118-122, GraphEvidenceApiSpec:211). Also QueryApiSpec.scala:213 (`< 3000` ms against a 300 ms deadline, a wide margin) and WorkerSpec.scala:242-245 (50 ms polling up to 20 s).
- The 10 of 10 run (saturation-10-summary.txt) was on an idle machine, as WO1 says.

(d) WO2 classification table: AGREE with all 15 rows.
- The audits under scratch/runtime/s3/logs/rag-runtime/.../run-422d912ac162f340f5f31a1b63b65e19 hold 15 UNSUPPORTED claims across 7 audits (counted).
- Rows 4-12, B26, 9 RANKING claims: by design, not fixed. All fail with "n is a rank, and no asserted rank.<k>.storedRank binds it". These are filtered-list positions (stored ranks only, review 2 n2).
- Row 1, B18 DESCRIPTIVE: by design, not fixed. It fails identity on profile topic names, and context-only.
- Row 3, B05 PUBLICATION: by design, not fixed. It fails identity on an uncited researcher name (ruling 29 N2).
- Row 14, B23 IDENTITY: by design; correctly removed. The claim gave the display name's total works (252) as the resolved researcher's own count (245). WO2's extra tightening (the totalWorks measure names) is a conservative behaviour change beyond the 15 claims and is disclosed. prompts/** gives the model no wording for it, so some correct live phrasings will now be removed (a recall cost).
- B13 and row 15, B24 COMPONENT: defect, fixed, AGREE.
  - The answer report (qry-e15d…answer.json:100-118) shows asserted-values PASS for largestComponentFraction.
  - Under v6, "69.4" followed by "%" becomes 0.694. The stored 0.6942… rounded half-up to 3 decimals equals it. The nearest measure name is "%" in the share family.
  - B24 stays misplanned (COMPONENT_LOOKUP only), as WO2 states.
- Row 13, B17 COMMUNITY: defect, fixed, AGREE.
  - The lookahead at Verifier.scala:262 and :265 removes "collaboration" from the joint and edges families when followed by "communit".
  - The nearest measure for the count becomes "communities", so it binds communitiesAtLeastMinSize. The "3" binds communityMinSize through "size" or "or more".
- Everything here is verified by code reading and fixtures. Live confirmation waits for the human's cloud rerun (ruling 2).

(e) Ruling 8 carried items, phase 03 to phase 10. "Closed" and "open" were checked against the file named. "NRV" means not re-verified this session; the status is taken from the later gate or report named.

| Gate | Item | Status | File evidence |
|---|---|---|---|
| 03 | ORCID daily-cap procedure | open (procedural) | n/a |
| 03 | AuthorshipRecord position and bounds; fix in the Phase 11 repair loop | STILL OPEN, finding 1 | Validation.scala:103-112 |
| 03 | Environment defaults to "local", which disables the placeholder guard | open, Phase 12 | ConfigLoader.scala:607-624 (guard exists; the environment is set at deploy) |
| 03 | Run-ID uniqueness depends on callers incrementing the attempt | NRV | — |
| 03 | Typesafe Config and scala-library on EMR; full runs | open (cloud) | — |
| 03 | 5,171 works without a DOI; agent-addressed text in an OpenAlex response; contact email visible to ps | open, accepted or input to Phase 17 | — |
| 04 | max-block-size calibration | superseded by Phase 05's residual (26.67M candidates) | phase-05 gate |
| 04 | Run-ID collisions; fingerprints | partially open (Phase 05: policy version omitted) | NRV |
| 04 | Capped-conflict wording (minor 1) | NRV | — |
| 04 | J01/J02 reducers hold whole venue groups in memory | still open, accepted at subset scale | Jobs.scala:38-39 `sortedValues` materialises the group |
| 04 | Nothing run on EMR or s3a | open (cloud) | — |
| 05 | 8.32M LLM-band pairs unresolved; silver bias; small gold strata; 26.67M candidates | open, accepted | — |
| 05 | er-v1 still selectable | open, by design | MatchPolicy.scala:106-111 |
| 05 | Review 1 minor: decide, decideBlocked and the tools default to er-v1 | still open, latent. No production caller omits the version (Logic.scala:389-405 has no default) | MatchPolicy.scala:52, :111, :282, :317; ReviewQueue.scala:55 |
| 05 | Other review 1 and 2 minors | NRV | — |
| 06 | EMR Spark run; label propagation does not converge; tier-2 recall cost | open | — |
| 06 | Path evidence from the seeded sample | closed in Phase 07 (benchmark path run) | phase-07 gate |
| 06 | m4: a missing input reads as empty | still open | Inputs.scala:48-62 |
| 06 | m5: MatchPath copy with no contract test | still open; blocked until build-engineer completes ruling 12 | Inputs.scala:80-100; no tests/contracts test. The rule IDs currently match MatchPolicy.scala:391-402 |
| 06 | m7: density NaN or null at minimum size 1 | still open | Summaries.scala:362-365; SparkSettings.scala:105-110 allows >= 1 |
| 06 | m8: two sources for community-max-iterations | STILL OPEN, finding 2 | scholarchain.conf:103 vs scholarchain-spark.conf:25 |
| 06 | m1, m2, m6, n3 | NRV (m6 is a human policy question) | — |
| 06 | n1: JOBS.md wording | STILL OPEN, finding 6 | spark/JOBS.md:469-470 |
| 06 | Embedding quota | closed in Phase 07 (bounded set of 13,023) | phase-07 gate |
| 07 | Live loads untested; expired-token root cause; Titan quality; paper index holds metadata only; DynamoDB price; Spark conversion runs through the driver | open (cloud or later phases) | — |
| 07 | N1 to N6 | closed in Phase 09 WO3 (per its report) | NRV line by line |
| 07 | Review 1 minors M2, M5 to M7, M9, M10, M12, M13 | acknowledged by ruling 36; M10 and M12 done in Phase 09 | NRV |
| 09 | R3-1, R3-2, n4 to n7; synthesis cut-off | closed in Phase 10 | phase-10 gate |
| 09 | Corpus questions | closed in Phase 10 WO9; the live B17 and B24 removals are fixed in v6 (section 4d) | Verifier.scala |
| 09 | Misplans of B15, B18, B23, B24 | still open | no Phase 11 change |
| 09 | Verification misses qualitative misreadings | still open; D1 to D3 are instances | Verifier.scala:550-584 |
| 09 | Dominant-name homonyms; unevaluated adjudication; Haiku code fence; adjudication omits low-confidence answers | open | NRV |
| 10 | Live mode untested; Haiku quota; 40 s deadline margin; loader reads the manifest; GSI consistency; ECS credentials; Phase 12 carry-forward | open (Phase 12 or cloud) | — |
| 10 | SaturationSpec flake, N1 to N5, 15 removed claims | see section 6 | — |
| 10 | Idempotency release deferred (ruling 18) | open, documented (N3) | SERVICES.md section 3; openapi.yaml:589 |

====================================================================
5. TESTS THAT WOULD PASS EVEN IF THE BEHAVIOUR WERE BROKEN
====================================================================

- tests/adversarial/.../RagModelOutputAdversarialSpec.scala:181-207 (injection through constraint fields).
  - It checks only that template IDs are fixed names, which is true by construction in fake mode, where no query text exists.
  - It would pass if the live NeptuneGraphReader interpolated a topic term into openCypher. Production is parameterised (NeptuneGraphReader.scala:94, `$$text`), and nothing in tests/ checks it.
  - Owner: test-engineer, or storage-engineer for a module test.
- tests/adversarial/.../StoreFailureAdversarialSpec.scala:145-176 (DynamoDB proxy).
  - The proxy ignores `ConditionExpression` and keys only on the `:now` and `:from` values.
  - A wrong condition expression in DynamoServiceStateTable would still pass. WO3 discloses this.
  - Owner: test-engineer.
- tests/adversarial/.../DblpXmlAdversarialSpec.scala:86-90 (parameter entity).
  - The name says "refused without any I/O", but the `Left(_) => ()` branch also passes when the parser read the secret file and then failed on its content. No I/O is detected.
  - Production is safe (DblpParser.scala:60-61).
  - Owner: test-engineer.
- tests/adversarial/.../RagModelOutputAdversarialSpec.scala:151-179 (injected question).
  - When the injected question abstains, its kept-claims check is vacuously true. WO3 discloses this.
  - Owner: test-engineer.
- tests/adversarial/.../ApiBodyAdversarialSpec.scala:106-121 (lone surrogate, invalid UTF-8) and :126-151.
  - Any 200 or 4xx passes, so silently mangled input is not detected.
  - Owner: test-engineer.
- tests/adversarial/.../WorkerRedeliveryAdversarialSpec.scala:166-207.
  - It builds its own QueryService and limiter, so it would pass if Apps.queryApi stopped wiring the limiter for async requests. The sync path is covered by ApiAuth:97-111.
  - Owner: test-engineer.
- tests/integration/.../GraphReadKnownGraphSpec.scala:128-135.
  - `Left(_) => ()` passes on any load failure, not only the count mismatch.
  - Owner: test-engineer.
- modules/services/src/test/.../CorpusQuerySpec.scala (m3).
  - It covers `GraphEvidenceApi.start`, not `main` (GraphEvidenceApi.scala:29). Reverting `main` to `Launcher.main(...)` with the default `answers = true` would pass. Low risk, because main is one line.
  - Owner: service-engineer.
- modules/services/src/test/.../SaturationSpec.scala:93-98 and :140-142.
  - Latency is no longer asserted, so a store call slowed to just under 1000 ms is not detected. This is the intended design. It is noted only because the old assertions documented the bound.
  - Owner: service-engineer.

====================================================================
6. CARRIED PHASE 10 ITEMS
====================================================================

- N1: CLOSED.
  - SERVICES.md:22 now gives max(1, G / 2) = 8 slots, which matches Composition.scala:320-327.
  - Threads start lazily (Resilience.scala:104-105).
- N2: CLOSED.
  - GraphEvidenceApi.scala:11-27 passes answers = false.
  - CorpusQuerySpec checks the "not opened" line, which matches Composition.scala:268.
  - The WO1 disable check fails without the change.
  - Residual: main itself is not tested (section 5).
- N3: PARTIALLY OPEN.
  - Documented in SERVICES.md section 3 and openapi.yaml:589. The behaviour is deferred by ruling 18.
  - IdempotencyRaceAdversarialSpec:164-205 pins the documented behaviour.
- N4: PARTIALLY OPEN.
  - Documented in SERVICES.md section 3.
  - Behaviour unchanged: a QUEUED job is orphaned after its message goes to the DLQ, until its TTL.
- N5: CLOSED.
  - VerifierSpec N5 test; the WO2 disable log shows 105 passed and 1 failed with the order reversed.
- SaturationSpec flake: CLOSED for its root cause (the cold workflow against 300 ms). The remaining timing conditions are in section 4c and finding 3.
- 15 removed live claims: classified, and I agree with all 15 (section 4d). The 3 defects are fixed in verifier-v6. The live gain is unproven until the human's cloud rerun.
- Phase 10 adversarial targets:
  - API authentication bypass: covered, pass.
  - Idempotency races: covered, pass.
  - Job redelivery: covered, pass.
  - Unbounded graph requests: covered, pass.
  - Injection through every text field: covered, pass, with the weak spots in section 5.
  - LLM budget exhaustion: covered, pass.
  - Queue flooding: covered, pass.
  - Window-summary tampering: covered, pass, plus D10 pending.
- Misplans of B15, B18, B23, B24: still open.

====================================================================
7. ROUTING FOR REPAIR-01 (by owner)
====================================================================

- rag-engineer: D1, D2, D3 (a qualifier, quantity-word and negation rule in numbers-in-text); D4 (degrade SEMANTIC_CONTEXT on a transient error or Left, as embedding failures already do); finding 5 (RUNTIME.md:509).
- service-engineer: D5 (do not drain unread bodies; add a request read or idle timeout); finding 3 (BuildingBlocksSpec:118-122 and :113, GraphEvidenceApiSpec:211, SaturationSpec scaladoc).
- domain-engineer: D8 and D9 together (idempotence and decoding; handle the doi: work-ID change); D11, D12, D13; finding 1 (AuthorshipRecord position); finding 2 (core community-max-iterations).
- hadoop-engineer: D6.
- entity-resolution-engineer: D7.
- storage-engineer: D10 (optionally, read retries on S3VectorStore.reader as defence in depth for D4).
- spark-engineer: finding 6 (JOBS.md:469-470).
- build-engineer: ruling 12 (Spark and Hadoop on the tests Test classpath, with the fork and JVM options).
- test-engineer, in the rerun after repair-01: finding 4 (GraphBoundsTimeoutAdversarialSpec:179); finding 7 (TESTS.md:86); the ruling 12 categories; the Phase 06 m5 MatchPath contract test once the classpath allows it; strengthen the tests in section 5. The owners, not test-engineer, remove each wrapper.

====================================================================
REPORT CONTRACT FIELDS
====================================================================

OWNERSHIP CHECK:
- modules/rag/RUNTIME.md, Verifier.scala, CorpusSpec.scala, VerifierSpec.scala -> rag-engineer -> WO2 author rag-engineer -> yes.
- modules/services/SERVICES.md, openapi.yaml, Composition.scala, Worker.scala, main/GraphEvidenceApi.scala, CorpusQuerySpec.scala, SaturationSpec.scala -> service-engineer -> WO1 -> yes.
- tests/** (TESTS.md and 18 Scala files) -> test-engineer -> WO3 -> yes. Within ruling 11 (tests/** only).
- docs/agent-runs/phase-11/agent-report.md -> orchestrator report assembly -> allowed.
- docs/agent-runs/phase-11/evidence/** (logs and scratchpad scripts) -> human-owned path, author not recorded. These are not one of the four report files the orchestrator may write. If the human copied them, record that in human-gate.md, as the Phase 07 gate did. Unverified, so this is not a finding.
- No new module dependency edges. SaturationSpec's import of rag RuntimeApi uses the existing services -> rag edge.

FLOOR AND FENCE CHECK: None found. No change to .claude/**, CLAUDE.md, docs/agents.md, .mcp.json, build.sbt, project/**, markTestRun, the hooks, or the permission rules. Worker's new `pause` parameter defaults to Thread.sleep, so production behaviour is unchanged.

EVIDENCE GAPS:
- D6 claims four inputs are accepted; the log shows only the first. The other three are confirmed by code reading.
- B13, B17 and B24 "would verify under v6" is shown only by code reading and fixtures. No live rerun (ruling 2).
- WO1's 10-run determinism was measured on an idle machine only, as WO1 states.
- Authorship of docs/agent-runs/phase-11/evidence/** is not recorded.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only. Nothing was read under labels/, scratch/stores-live/ or scratch/er/adjudication-live/. No search was rooted at scratch/ or at the repository root in a way that could descend into them.

TEST RESULTS (as supplied, and confirmed in the logs):
- WO1: sbt check twice, 665 passed, 0 failed. SaturationSpec 10 of 10 runs passed.
- WO2: sbt check twice, 669 passed, 0 failed (rag 106).
- WO3: sbt check twice, 777 tests, 763 succeeded, 0 failed, 14 pending; JUnit 77 suites, 0 failures, 0 errors, 14 skipped or pending (junit-counts-2.txt; sbt-check-2.log:1275 "succeeded 94 … pending 14", :1368 "Total time: 259 s").

VALIDATION RESULTS: Format checks passed inside every check run. ServicesContractSpec passes. The disable checks are in the WO1 and WO2 logs.

ASSUMPTIONS:
- ScalaTest's pendingUntilFixed treats any Exception or AssertionError as pending.
- The JDK HttpServer drains unread request input when the exchange closes, before it flushes the response. This is consistent with the observed timeouts.
- Typesafe Config's entrySet omits null values, and getInt truncates decimals. Both are consistent with the evidence.

RISKS:
- The D8 and D9 repair can change stable doi: IDs.
- Verifier-v6's totalWorks rule will remove some correct live phrasings.
- A percentage is ambiguous between percentage points and relative change for a share delta. Both bind the stored delta.
- All live adapters are still unproven until cloud window 2.

MANUAL ACTION REQUIRED:
- Human: confirm who wrote docs/agent-runs/phase-11/evidence/**.
- Human: run the live benchmark again with verifier-v6.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: route the findings to owners in repair-01; test-engineer reruns; code review 2. Human gate on APPROVE.
````

## Code review 2: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 code review 2, as amended by rulings 23 and 26. Scope: repair-01 (Parts A to F), work order 4, repair-02 Part A and work order 5, checked against code review 1 (findings 1 to 7), D1 to D20 and rulings 14 to 26. The review also confirms or changes the severity and owner of D14 to D20.

## 1. VERDICT: REQUEST_CHANGES

The repairs themselves are correct, and I found no new CRITICAL or MAJOR defect in the repair code. Every item reported fixed (D1 to D5, D10 to D14, findings 1, 2, 3, 5, 6, 7) is fixed in production code. Each fix has an owner module test whose disable check is logged with a sha256 restore. Every removed tests/** wrapper now passes. The final `sbt check` (rerun-2/sbt-check-2.log) exits 0: 840 tests, 0 failures, 10 pending, and markTestRun wrote the marker (:1442).

I still cannot return APPROVE, for two reasons:
- **D18 stays MAJOR.** I confirm it at that severity (reasons in section 4). Ruling 24 carries it as a residual risk, but it is an open MAJOR finding, and a verdict of APPROVE with an open MAJOR is not allowed. For the same reason the spec gate item "No critical or major verified finding remains" does not hold.
- **The spec gate item "Every production behavior has a relevant test" does not hold.** WO4 and WO5 list 10 behaviours with no relevant test, and N2 below adds one more.

The exit is human escalation (rulings 20 and 26). If the human accepts D18 as a recorded residual under ruling 24, no other blocker remains. All remaining findings are MINOR and can be carried as residuals.

## 2. Code review 1 findings

| # | State | Fix (file:line) | Test that fails without the fix; disable-check log |
|---|---|---|---|
| 1 | Resolved | Validation.scala:107 (`position >= 1`) | ValidationSpec.scala:171; scratch/phase-11-logs/repair-01/partD/disable-F1-position.log (RESTORE-OK) |
| 2 | Resolved | scholarchain.conf:106-108 (value 10, comment says Spark reads scholarchain-spark.conf) | ConfigLoaderSpec.scala:223; partD/disable-F2-community-iterations.log. Also checked across modules by tests/** ConfigOmission "boundaries" test |
| 3 | Resolved | BuildingBlocksSpec:85 test (the `< 1000` assertion replaced by a 1-hour workflow timeout; the sleeps replaced by explicit clock checks); GraphEvidenceApiSpec:195 test (probes race through an ExecutorCompletionService); SaturationSpec scaladoc | partC/final/disable-F3-{workflow-queue,timeout-from-submission,no-shedding}.log; 10 of 10 runs in partC/final/ten-runs-summary.txt. One real-clock condition remains, documented: a pool thread must start within 2 s (the old margin was 200 ms) |
| 4 | Partly open | GraphBoundsTimeoutAdversarialSpec :156 is resolved. :191 (old :179) is NOT resolved; see N1 | n/a (test-engineer item) |
| 5 | Resolved | RUNTIME.md:518 (section 21 now states verifier-v6 percentage behaviour) | Documentation only. See new N2 for another section 9 overclaim |
| 6 | Resolved | spark/JOBS.md:469-470 | Documentation only. Checked against Clustering.scala:123 (min confidence) and :155 (coalesce to 1.0) |
| 7 | Resolved | tests/TESTS.md:5 (ruling 11 wording; the old ":86" text is gone, checked by grep) | n/a |

## 3. D1 to D20

"Wrapper removed, passes" means the test passes in the final check (rerun-2/sbt-check-2.log, junit-counts-2.txt).

| D | State | Fix (file:line) | Owner module test; disable-check log | tests/** wrapper |
|---|---|---|---|---|
| D1 | Resolved | Verifier.scala:179-183 (Vague), :665-671 | VerifierSpec:1124; partB/disable-D1-vague.log | Removed; UnsupportedClaim:121 passes |
| D2 | Resolved | Verifier.scala:185-205 (Filler, Before, After, Ratio, bound direction), :231 hedge, :638-645 | VerifierSpec:1143; partB/disable-D2-qualifier.log; control logs disable-ctl-{bounded,ratio}.log | Removed; :136 passes |
| D3 | Resolved | Verifier.scala:211-214 (Negation), hedge | VerifierSpec:1176; partB/disable-D3-negation.log, disable-ctl-converge.log | Removed; :151 passes |
| D4 | Resolved | Semantic.scala:132-153 (catch at :134, NO_SEMANTIC_MATCH note) | WorkflowSpec:658; partB/disable-D4-thrown.log and disable-D4-left-2.log (the Left path, isolated) | Removed; RagModelOutput:570 passes. Residual of the same shape: N2 |
| D5 | Resolved | Http.scala:142 (interruptAfter), :165 and :304 (maxReqTime 30 s), :207 (cut body read is 400 INVALID_FIELD), :269 (Connection: close), :277 (drain bounded to 1000 ms) | RequestReadSpec:50, :80, :112; partC/final/disable-D5-{alarm,conn-close,timeout,400}.log | Removed; ApiBody:253 passes (plus WO4 :300) |
| D6 | Residual (ruling 15) | none (DblpParser unchanged) | n/a | Kept: DblpXml:170 |
| D7 | Residual (ruling 15) | none (Normalize unchanged) | n/a | Kept: Identity:101 |
| D8 | Residual (ruling 19) | none (StableIds unchanged) | n/a | Kept: Identity:270 |
| D9 | Residual (ruling 15) | none | n/a | Kept: Identity:288 |
| D10 | Resolved | WindowSummaries.scala:101-137 (partitionOk, identityProblems, in BigInt), :179 | WindowSummarySpec:166; partE/disable-D10-{all-identities,partition,at-least-min-size,edges}.log (round 2) | Removed; WindowSummaryTamper:132 passes |
| D11 | Resolved | ConfigLoader.scala:389-394 (`getObject.keySet` plus `getString`) | ConfigLoaderSpec:158; partD/disable-D11-null-index.log | Removed; ConfigOmission:58 passes |
| D12 | Resolved | ConfigLoader.scala:646-663 plus the final `blanks` check | ConfigLoaderSpec:164; partD/disable-D12-blank.log | Removed; ConfigOmission:69 and :99 pass |
| D13 | Resolved | ConfigLoader.scala:352-356 (wholeInt), also used for window years | ConfigLoaderSpec:202; partD/disable-D13-fraction.log | Removed; ConfigOmission:138 passes |
| D14 | Resolved | Verifier.scala:185-196 (Filler, Before, After), :211-214 (Negation), :218-229 (asidesOut) | VerifierSpec:1250, :1263, :1299; CorpusSpec:260; repair-02/disable-D14-{all,comparison,negation,filler}.log, disable-ctl-aside.log | Removed; UnsupportedClaim:216 passes |
| D15 | Residual (ruling 21) | none (Http.scala:66 `ujson.read`) | n/a | Kept: ApiBody:112 |
| D16 | Residual (ruling 21) | none | n/a | Kept: ApiBody:143 |
| D17 | Residual (ruling 21) | none (DblpInputFormat.scala:85-104) | n/a | Kept: DblpRecordReader:176 |
| D18 | Residual (ruling 24), MAJOR | none | n/a | Kept: UnsupportedClaim:658 |
| D19 | Residual (ruling 24) | none | n/a | Kept: UnsupportedClaim:683 |
| D20 | Residual (ruling 24) | none | n/a | Kept: RagModelOutput:489 |

## 4. D14 to D20: severity and owner

- **D14: MAJOR, rag-engineer. Confirmed and resolved.**
  - "short of 10" and "lacks 10" kept a false statement VERIFIED, which is the same impact as D2 and D3.
  - Checked in the code: "short of" and "shy of" are in Before (:188), and "lack(s|ed|ing)" is in Negation (:212).
- **D15: MINOR, service-engineer. Confirmed.**
  - Invalid UTF-8 is decoded leniently and answered as a mangled question.
  - No security effect and no data loss.
- **D16: MINOR, service-engineer. Confirmed.**
  - A lone high surrogate is accepted. HTTP request validation (Models.scala:455-457) is the service's contract.
  - rag-engineer (Question.normalize) is an acceptable alternative place for the fix.
- **D17: MINOR, hadoop-engineer. Confirmed.**
  - Checked: findStart at DblpInputFormat.scala:91-104 takes any `<name` delimiter as a record start, including inside comments.
  - The fabricated work and the swallowed records are real. However, the human's measurement (ruling 21) found 0 comments in every DBLP input, so no existing output is affected.
- **D18: MAJOR, rag-engineer. Confirmed, not downgraded.** My reasons:
  - The failure is the same one D2, D3 and D14 were rated MAJOR for: a claim whose text says something false or doubtful about the stored value keeps the VERIFIED label.
  - Checked in the code:
    - After (Verifier.scala:193-196) matches "more" or "fewer" only after "or".
    - Negation (:211-214) has no "dispute", "unlikely" or "doubtful".
    - The five inputs therefore pass (rerun-2/pending-evidence.log:3).
  - The difference wording needs the difference to equal the stored value, so it is less likely. But a doubt wording ("it is doubtful that X has 10") is exactly what injected text could steer a model toward, and the verifier is the last control against that.
  - Being likely to occur less often does not change the class of the impact.
- **D19: MINOR, rag-engineer. Confirmed.** The stored value is written, only hedged ("more or less 10", "perhaps 10"). It is inexact, not false.
- **D20: MINOR, rag-engineer. Confirmed.**
  - This is the documented rule in RUNTIME.md section 12: numbers of the fixed notes are not checked, applied at Verifier.scala:638-640 and :1090-1100.
  - It affects uncertainty lines only, which carry no verification status.
  - It needs the claim value to equal a fixed-note number.

## 5. New CRITICAL and MAJOR findings

None in the repair-01, WO4, repair-02 or WO5 changes.

The only open MAJOR is D18 (section 4):
- **Severity:** MAJOR.
- **Location:** Verifier.scala:193-196 and :211-214.
- **Failure input:** "In all-years, Ada Lovelace has 10 more publications than her coauthor." or "In all-years, it is doubtful that Ada Lovelace has 10 publications.", each with publicationCount 10 asserted and cited.
- **Impact:** the claim is kept VERIFIED (a groundedness failure).
- **Owner:** rag-engineer.
- **Status:** residual by ruling 24.

## 6. New MINOR findings, by owner

**test-engineer**

- **N1. Finding 4 is only half resolved.**
  - Location: tests/adversarial/.../GraphBoundsTimeoutAdversarialSpec.scala:188-191.
  - What the test assumes: it awaits `entered` after the 504, on the grounds that the workflow continues past the 504.
  - Why that does not remove the timing dependence:
    - The workflow's own deadline is the same 300 ms (Services.scala:167 passes syncDeadlineMs).
    - It is measured from workflow start (Workflow.scala:140-142).
    - CallLog checks it before every store read (Resolver.scala:28-31).
  - Failure input: a cold or loaded JVM where planning takes more than 300 ms. The workflow throws DeadlineExceeded before the first `kv.get`, `entered` never counts down, and the test fails after its 30 s guard. This is the same condition code review 1 flagged.
  - TESTS.md:42-43 states the "hang guards only" claim, which is therefore not true for this case.
  - A frozen or tick clock on the backend setup would remove the dependence.
- **N3. A test that would pass if the behaviour were broken.**
  - Location: ApiBodyAdversarialSpec.scala:331-343 asserts only the system property `sun.net.httpserver.maxReqTime`.
  - The JDK reads that property once, when the JVM's first JDK HTTP server is created (SERVICES.md section 6 says so).
  - DblpXmlAdversarialSpec.scala:102 creates a plain JDK HttpServer in the same forked tests JVM. If it runs first, no HttpService in that JVM has a read timeout, and this test still passes.
  - Production is unaffected; the only JDK server in modules/ is HttpService (checked by grep).

**rag-engineer**

- **N2. The D4 fix covers the vector query only; the text fetch can still fail the question.**
  - RUNTIME.md:231 (section 9) now says "The question is never failed by semantic context". That is not true.
  - Semantic.scala:144 calls `SemanticContext.fetch` outside the D4 try. fetch calls `objects.get` (SemanticContext.scala:97), and the live S3 reader rethrows transient errors through `LiveAws.guarded` (S3Live.scala:89-94, LiveAws.scala:191-192).
  - Failure input: a vector query that returns hits, then a GetObject on a `textRef` that is throttled (503 SlowDown after the SDK retries).
  - Result: SEMANTIC_CONTEXT FAILED and a 502, for context that is descriptive only. This is the same shape as D4, and no test covers it.

**Note, no finding.** The Ratio exception (Verifier.scala:198-200) also fires after any digit, so "In 2009, over 10 publications" verifies. RUNTIME.md section 12 ("after a number") documents this, so it falls inside the lexical class of ruling 24.

## 7. Ruling 15 check

No fix changed a stable ID, a normalized key, or a stored record of Phase 4 to 8 data.

- **Code.**
  - No diff touches StableIds.scala, normalizeDoi, Normalize.scala, DblpParser, DblpInputFormat or any Spark source.
  - D8 was not applied (ruling 19). The measurement for it: values_changed=36 and profiles_changed=26 in run-035e75ab…, j02 researcher-profiles (partD/measure-doi-stored-workdoi.log:4).
- **Finding 1 is validation only.**
  - Spark canonical/authored: rows=535898, position_null=0, position_lt_1=0, min 1 (partD/measure-authored.log:16341).
  - Neptune edges/authored: rows=535898, position_lt_1=0 (partD/measure-authored-neptune.log:1).
- **D10 is validation only.**
  - The existing store object (sha256 d713f227…) still opens: OPEN_STORE_OBJECT OK, 3 rows, 0 with problems.
  - The Spark manifest rows equal the store rows, and the input sha256 values are unchanged (partE/existing-check-after-fix.log:5-14).
- **Configuration and verifier.**
  - D11 to D13 and finding 2 change load behaviour only. The core key is unread by Spark (grep: only ConfigLoader.scala:710 reads it). config-version is unchanged.
  - The verifier-v8 bump changes future runtime run IDs, by design. It rewrites no stored record.

## 8. Ownership check

- **Parts and paths**, from the change set (26 tracked files plus 1 untracked module file):
  - build.sbt -> build-engineer (Part A). project/** is unchanged.
  - modules/core/** -> domain-engineer (Part D).
  - modules/rag/** -> rag-engineer: Part B, repair-02 A, and WO2 from before code review 1.
  - modules/services/**, including the new RequestReadSpec -> service-engineer. Composition.scala, Worker.scala, openapi.yaml and main/GraphEvidenceApi.scala are already in code review 1's change set (WO1); Part C's report lists Http.scala and 4 tests.
  - modules/storage/** -> storage-engineer (Part E).
  - modules/spark/JOBS.md -> spark-engineer (Part F).
  - Every path matches its author: yes.
- **test-engineer (WO3, WO4, WO5).**
  - Only tests/**: TESTS.md, fixtures/logback-test.xml (tests/fixtures is the tests project's resource directory, build.sbt:186), and Scala files under tests/.
  - No file under modules/ other than Part C's own spec, and nothing under prompts/ or project/.
  - No production edit: yes.
- **Other.**
  - docs/agent-runs/phase-11/** was assembled by the orchestrator plus human-copied evidence (ruling 18).
  - The WO5 `git status` deviation is accepted by ruling 25.
- **Floor and fence check.** No change to .claude/**, CLAUDE.md, docs/agents.md, .mcp.json, markTestRun, hooks or permission rules. The build.sbt diff touches only sparkTestSettings, excludeScalaXml3 and the tests project's Test scope. No production scope changed, and no job jar changed.

## 9. pendingUntilFixed audit (tests/**, grep)

All 10 wrappers in tests/** are justified, and there is no wrapper outside tests/**.

| D | Wrapper line (PENDING marker line) | Ruling |
|---|---|---|
| D6 | DblpXmlAdversarialSpec:170 (169) | 15 |
| D7 | IdentityAdversarialSpec:101 (100) | 15 |
| D8 | IdentityAdversarialSpec:270 (269) | 19 |
| D9 | IdentityAdversarialSpec:288 (287) | 15 |
| D15 | ApiBodyAdversarialSpec:112 (111) | 21 |
| D16 | ApiBodyAdversarialSpec:143 (142) | 21 |
| D17 | DblpRecordReaderAdversarialSpec:176 (175) | 21 |
| D18 | UnsupportedClaimAdversarialSpec:658 (657) | 24 |
| D19 | UnsupportedClaimAdversarialSpec:683 (682) | 24 |
| D20 | RagModelOutputAdversarialSpec:489 (488) | 24 |

- None would pass:
  - All 10 report "(pending)" in rerun-2/sbt-check-2.log:1270-1385. A fixed defect would make its wrapper fail with "did not throw".
  - For each, the production code is unchanged or, for D18 to D20, its gap is confirmed by reading the code.
- Unwrapped failure evidence: rerun/pending-evidence.log (D14 to D17) and rerun-2/pending-evidence.log (D18 to D20).
- No D item lacks a wrapper. N1 to N3 are new review findings and have no tests.

## 10. Spec Phase 11 gate items

| Gate item | Holds? | Evidence |
|---|---|---|
| No critical or major verified finding remains | Does not hold | D18 MAJOR (residual by ruling 24) |
| Every production behavior has a relevant test | Does not hold | WO4 "still without a relevant test" items 1-10 (for example the breaker's half-open concurrency, VectorWriteRule Supersede, the J01-J10 drivers with no mini-cluster), plus N2 |
| All tests pass under their declared profiles | Holds, with 10 known defects pending | sbt check exit 0, 840 tests, 0 failures, 10 pending (junit-counts-2.txt). RequiresAws tests are excluded and not run (ruling 2) |
| No reviewer wrote production code | Holds | code-reviewer has no write tools; FILES MODIFIED none in both reviews |
| Repair records identify owners and rerun evidence | Holds | repair-01.md Parts A-F, repair-02.md Part A, agent-report WO4 and WO5, logs under scratch/phase-11-logs/{repair-01,repair-02,rerun,rerun-2} |

## 11. Carried items and test-quality notes

- **Repairs worth noting.**
  - D5: the drain is bounded by an interrupt, and the interrupt flag is cleared only when the alarm fired (Http.scala:142-157). Shed requests run on the dispatcher with the same 1 s bound (SERVICES.md section 6).
  - D10 BigInt identities: I checked partitionOk and atLeastOk algebraically.
  - The D12 blank rule cannot break an existing non-local deployment: every listed key is already a placeholder that is refused outside local, or has a non-blank default.
- **Real-clock conditions still in tests**, all documented:
  - BuildingBlocksSpec: a pool thread must start within 2 s.
  - SaturationSpec: 3 conditions.
  - WorkerRedelivery: the composed limiter, 3 requests within 60 s.
  - RequestReadSpec:80 waits out the real 30 s, with a 90 s guard.
  - GraphBoundsTimeout:152: the 504 needs the store call to start within 300 ms, otherwise the answer is 503. Low risk.
  - Plus N1.
- **Verifier.**
  - The v7 and v8 lists remain lexical. The WO5 probe (rerun-2/probe-results.log) shows further uncovered wordings, and RUNTIME.md section 21 discloses them.
  - Live recall cost and live gain are unmeasured until the human's verifier-v8 live benchmark.
- **Carried and not re-verified here:** Phase 06 m4 and m7; the J01/J02 reducer memory; the B15, B18, B23 and B24 misplans; live adapters; N3 and N4 behaviour (documented and deferred).

## Standard report fields

SCOPE REVIEWED:
- /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c5def804-6c92-4c63-8a4a-afa299baa28a/scratchpad/phase-11-change-set-cr2.txt (HEAD 9afe89e)
- docs/agent-runs/phase-11/{prompt.md, review-report.md, repair-01.md, repair-02.md, agent-report.md (WO4, WO5)}
- The working-tree production files behind each finding
- The cited logs under scratch/phase-11-logs/
- spec Phase 11

OWNERSHIP CHECK: section 8.
FLOOR AND FENCE CHECK: none weakened or widened (section 8).

EVIDENCE GAPS:
- The two Part A checks come from an interrupted attempt (partA logs).
- D17's measurement was made by the human and is not reproducible here.
- I did not open the D8 raw-input log totals (measure-doi-raw-2.log); the stored-output count was verified.
- The live effect of v7 and v8 is unmeasured.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only. Nothing was read or listed under labels/, scratch/stores-live/ or scratch/er/adjudication-live/; every Glob was rooted under scratch/phase-11-logs/.

TEST RESULTS (as supplied, confirmed in the logs):
- WO5 check 2: "Tests: succeeded 141, failed 0, canceled 0, ignored 0, pending 10" (tests project).
- All modules: 0 failed.
- "markTestRun: tests passed, wrote …/.claude/.last-test-run".
- "Total time: 259 s".
- JUnit: "TOTAL: suites 82, tests 840, failures 0, errors 0, skipped 10".

VALIDATION RESULTS:
- Every disable check fails a named test, with RESTORE-OK: partC/final/disable-summary.txt, partD/disable-summary.txt, repair-02/disable-D14-all.log:32-38, partB/disable-D4-left-2.sbt.log:89.

ASSUMPTIONS:
- The JDK 17 ServerConfig reads maxReqTime once (consistent with SERVICES.md section 6).
- Ruling 24's "residual" does not make a MAJOR finding closed for the purpose of this verdict.

RISKS:
- D18 class: lexical verifier gaps.
- N2: a semantic text-fetch failure still fails the question.
- N1: a timing-dependent test in check.

MANUAL ACTION REQUIRED:
- Human: decide whether to accept D18 (MAJOR) as a ruling 24 residual and the two gate items that do not hold.
- Human: route or record N1 (test-engineer), N2 (rag-engineer) and N3 (test-engineer) as residuals.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human escalation (rulings 20 and 26): no further repair round; the human gate decides acceptance with D18 and the minors recorded as residual risks.
````
