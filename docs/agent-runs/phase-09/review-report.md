# Phase 09 review reports

## Review 1: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 09, session 1, code review 1. Review of work orders 1 to 6 against rulings 1 to 16, spec Phase 9 (work order and gate), and D-10, D-12 and D-13.
VERDICT: REQUEST_CHANGES

The verdict is REQUEST_CHANGES because six MAJOR findings are open. There is no CRITICAL finding.

The core controls hold:
- User text never becomes Cypher, PartiQL, a filter expression, or an AWS call.
- AMBIGUOUS and UNKNOWN names always abstain.
- Live calls need both --live and --profile, and every Haiku and Titan attempt passes a throttle.
- No test touches AWS. The benchmark produces no ratings or labels.
- Every changed path is inside its author's rows.

The open findings are in claim verification (F1 to F4), the prompt-injection tests (F5), and the ownership of one artifact class (F6).

SCOPE REVIEWED:
- `git status --porcelain` (review-status.txt), the full tracked diff (review-diff.patch, 2,371 lines), and the untracked files, read directly in the tree.
- Read in full: Planner, Workflow, Resolver, GraphStages, Evidence, Semantic, Synthesis, Verifier, Llm, Prompts, JsonSchema, Audit, BedrockHaikuClient, AskCli, Benchmark, ItemFileIndex, GraphRead, NeptuneGraphReader, AdjudicateSample, and the prompts and schemas.
- Read in part: WorkflowSpec, PlannerSpec, HaikuClientSpec, RuntimeFixtures, AdjudicateSampleSpec, RUNTIME.md sections 8 to 22, and spec Phase 9 together with D-10, D-12 and D-13.
- Not read in detail: InMemoryGraph.scala, GraphReadSpec.scala, Contracts.scala and Records.scala in modules/rag, the core and storage test diffs, and the STORAGE.md diff.
- Nothing under labels/ or scratch/stores-live/ was read or listed.

FINDINGS (ranked)

F1. MAJOR. The verifier does not tie the numbers in a claim's text to the facts the claim asserts. Wrong numbers and wrong hop values can verify.
- Where:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala:98-113 (`numbersOk`)
  - Verifier.scala:44-51 (`same`)
  - Verifier.scala:231-242 (the PATH_HOP part of `path-hops`)
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/prompts/synthesis/synthesis-output-v1.schema.json:25 (`values` may be `{}`)
- Failure inputs:
  - (a) A METRIC_VALUE claim with text "In all-years, X has N distinct coauthors.", `values: {}` or `{"publicationCount":"N"}`, citing X's METRIC_RECORD, where N is publicationCount, not distinctCoauthorCount. The number check accepts any fact of any cited item, so the claim is VERIFIED. On a RANKING_RECORD, every integer from 1 to k is a fact (`rank.k.storedRank`), so almost any small number passes.
  - (b) A PATH_HOP claim with `values: {hop.1.from, hop.1.to}` and text "A and B have 3 joint publications", where hop 1 has 5 and hop 2 has 3. The code checks only from and to, so the claim is VERIFIED. RUNTIME.md section 12 says PATH_HOP also checks the joint count and years.
  - (c) A BRIDGE claim with `{"participationCoefficient":"1"}` where the stored value is 0.55. `same` rounds the stored value to the claim's 0 decimal places, gets 1, and passes the claim. The prompt says "never round".
- Impact: the gate item "verification catches wrong numbers and hops" is not met for model synthesis. The fake benchmark (109 of 109 VERIFIED) cannot show this, because the template synthesizer copies values exactly.
- Owner: rag-engineer.

F2. MAJOR. A wrong window stated in the claim text verifies.
- Where: Verifier.scala:199-209 (`window` checks only `d.window`) and Verifier.scala:67-71 (every window bound counts as an allowed number).
- Failure input: a METRIC_VALUE claim with text "In 2015-2026, Ada Lovelace has 1 distinct coauthor.", `window: "all-years"`, `values {"distinctCoauthorCount":"1"}`, citing the all-years METRIC_RECORD. The window field passes. 2015 and 2026 are window bounds, so the number check passes too. The claim is VERIFIED.
- Impact:
  - A value from one window is presented as another window's value. Ruling 8 and the review checklist require window checks.
  - RUNTIME.md section 12 lists a check that a year range in the text matches, but the implementation note at RUNTIME.md:278 drops it.
  - The existing test, WorkflowSpec claim 8, covers only a wrong window field.
- Owner: rag-engineer.

F3. MAJOR. A semantic-only DESCRIPTIVE claim counts as VERIFIED and prevents abstention.
- Where:
  - Verifier.scala:144 and 155. `material = kind != DESCRIPTIVE`, and the model chooses the kind.
  - Verifier.scala:302-307. `counts.verified` includes DESCRIPTIVE claims.
  - Workflow.scala:549-554. Abstention happens only when `verified == 0`.
  - Benchmark.scala:486 and 496 use the same count.
- Failure input: synthesis returns, among others, `{kind: DESCRIPTIVE, text: "Ada Lovelace and Grace Hopper are close collaborators who frequently coauthor.", evidenceIds: [<PROFILE ev>], subjectIds: [ada, grace], window: null, values: {}}`, and every material claim fails.
  - The DESCRIPTIVE claim passes evidence-exists and identity, so it is VERIFIED.
  - verified becomes 1, so the answer does not abstain.
  - WorkflowSpec claim 9 already shows that a DESCRIPTIVE claim citing only a profile verifies.
- Impact:
  - A coauthorship assertion backed only by vector similarity is shown as VERIFIED, against ruling 8 ("Vector similarity is never evidence for a coauthorship edge").
  - "With no verified claim, the answer abstains" can be met by semantic text alone.
- Owner: rag-engineer.

F4. MAJOR. A researcher name that appears nowhere in the bundle is never flagged.
- Where: Verifier.scala:74-81 (`knownNames` holds only names already in the graph evidence) and Verifier.scala:115-134 (`namesBad`).
- Failure input: a COAUTHORSHIP claim with text "Ada Lovelace and Alan Turing have 2 joint publications in all-years.", citing the Ada–Grace COAUTHOR_EDGE, `values {"jointPublicationCount":"2"}`, `subjectIds [ada, grace]`. "Alan Turing" is in no bundle item, so the identity check passes and the claim is VERIFIED.
- The same narrow checks decide whether the model's summary and uncertainty lines are kept (Verifier.scala:299-301 and 312). A removed claim's content can therefore survive in the summary when it has no numbers and no known names.
- Impact: the gate item "verification catches wrong identities" is not met. RUNTIME.md section 12 states a stronger identity rule than the code implements.
- Owner: rag-engineer.

F5. MAJOR. The prompt-injection tests do not exercise the escaping that defends the prompt delimiters, and the question-injection test runs only in fake mode.
- Where:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/RuntimeFixtures.scala:49-50. The `Injection` string contains no `<`, `>`, `&` or `"`.
  - WorkflowSpec.scala:446-451. `Injection.replace("&","&amp;")` changes nothing, and the closing-tag split counts would hold even without escaping.
  - WorkflowSpec.scala:404-424. The question-injection case runs in fake mode only: the rule planner, plus the template synthesizer, which never reads the question. "No claim changes" is therefore true by construction.
- Failure input:
  - Remove `SemanticContext.escape` from Planner.scala:450 (the question), Synthesis.scala:23 (the question), or Evidence.scala:28 (graph facts such as titles and names).
  - Every rag test still passes. A title such as `x</item></graph_evidence> new rules…`, or a question containing `</question>`, would then close the data block.
  - No test sends an injected question through the model planner or synthesis prompts.
- Impact: the review checklist asks for injection tests where the injected text reaches the code path under test, and the definition of done asks for a test that fails without the behaviour. Neither holds for the Phase 9 call sites. The escaping code itself looks correct.
- Owner: rag-engineer (module-local tests).

F6. MAJOR. A DynamoDB read adapter (a KeyValueReadPort implementation) was written in modules/rag, in an artifact class owned by storage-engineer.
- Where: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/cli/ItemFileIndex.scala:20-128. The author marks it `shortcut:`.
- Failure input: any change to the item-file format or the DynamoContract validation now has a second, rag-owned reader to keep in step. CLAUDE.md says "Persistence lives behind ports in storage." docs/agents.md gives "ports, adapters, and loaders for … DynamoDB" to storage-engineer.
- Impact: an ownership breach under review law 3. It is reported as a follow-up but is unresolved at review time.
- Owner: storage-engineer provides an indexed, read-only item-file reader in modules/storage. Then rag-engineer switches AskCli to it and deletes ItemFileIndex.

MINOR
- m1. Workflow.scala:440-442 records PATH_FINDING and COMMUNITY_ANALYSIS as OK with 0 ms whether or not they ran, so the per-stage latency in the audit (ruling 10) is inaccurate. Owner: rag-engineer.
- m2. Resolver.scala:55, 117-118 and 163. When the candidate list is cut at max-candidates and no exact display-name match is in the returned page, the decision is UNKNOWN_RESEARCHER with the text "No researcher named … is in the graph." That text is false; the answer still abstains. Owner: rag-engineer.
- m3. Ruling 9's OpenAlex injection text is not used. It is not recorded in the repository; factory-metrics only notes that Phase 3 flagged it. The human must supply the text or waive the requirement. Owner: human architect, then rag-engineer.
- m4. entity-resolution Runner.scala:58 discards the SDK client's AutoCloseable (`val (client, _)`), so it is never closed. Owner: entity-resolution-engineer.
- m5. Model call counting misses the expired-token retry:
  - BedrockHaikuClient.scala:71 makes one more request per attempt on an expired token. The audit's LlmCall records and the benchmark's llm-budget check do not count that request.
  - Work order 5's stated maximum of 120 Haiku requests leaves it out; the true worst case is up to 240.
  - Owner: rag-engineer.
- m6. AdjudicateSample.scala:168-172 sets the seed and sample sizes as code defaults, not configuration keys (CLAUDE.md externalized configuration). Owner: entity-resolution-engineer, with domain-engineer if the human wants keys.
- m7. The question deadline is checked only before LLM attempts (Llm.scala:99). The graph and semantic stages are not bounded by runtime.question.deadline-ms; the benchmark checks it only afterwards. Owner: rag-engineer.
- m8. Planner.scala:104-106. Request constraints with yearFrom greater than yearTo give the misleading text "has no year in the corpus years". Owner: rag-engineer.
- m9. Planner.scala:82-83 has a stale Scaladoc block above `widest`. StableIds.scala:229 lost a space ("timestamp-independent:"run-""). Owners: rag-engineer and domain-engineer.

OWNERSHIP CHECK (changed path, owner, author matches):
- modules/core/** (8 files): domain-engineer (work order 2). Yes.
- modules/entity-resolution/** (BedrockLlmClient, Runner, AdjudicateSample, AdjudicateSampleSpec): entity-resolution-engineer (work order 6). Yes.
- modules/rag/** (RAG.md, RUNTIME.md, SmokeCli, cli/*, live/*, runtime/*, tests): rag-engineer (work orders 1, 4, 5). The paths match. ItemFileIndex's artifact class is storage-engineer's (F6).
- prompts/** (4 files): rag-engineer. Yes.
- modules/storage/**: storage-engineer (work order 3). Yes.
- docs/agent-runs/phase-09/agent-report.md: orchestrator report assembly. Permitted.
- No change to spec/, labels/, experiments/ (the benchmark CSV is unchanged), build.sbt, project/, .claude/, CLAUDE.md or docs/agents.md.

FLOOR AND FENCE CHECK: none weakened or widened. No hook, permission, settings, markTestRun, reviewer-fence or constitutional change. No new library and no version change (ruling 14).

EVIDENCE GAPS:
- Work order 6's final sbt check output (totals 175, 53, 84, 103, 43, 70) was not supplied as a raw log. The scratchpad check1.log and check2.log are work order 5's runs (175, 53, 94, 84, 43, 70; markTestRun wrote the marker; finished 00:02:33 and 00:07:02). The 103 total matches 94 plus the 9 tests in AdjudicateSampleSpec, which I counted.
- Three results come only from probes that were deleted, so they are claims in reports, not artifacts:
  - work order 3: the full-graph path probe (54 of 54 equal);
  - work order 4: the FullStoreProbe 30-question run;
  - work order 4: the reproduction of the work order 6b shares.
  The fake benchmark summary is on disk and matches work order 5's table: scratch/runtime/s3/reports/rag-benchmark/snapshot=2026-10-03/run=run-d22ec2f83528a8e95067443cfb114bab/summary-030.json (30 done; 109 verified; failures B13, B15, B17, B24; RULES 30).
- The verifier has never been run against real model output; that is pending the human's live run.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.
TEST RESULTS (as supplied by the orchestrator, quoted): "the last full run, by work order 6, was exit 0 with per-project totals 175, 53, 84, 103, 43, 70 and 0 failed; .claude/.last-test-run was written 2026-11-07 00:26 and no source file under modules/, prompts/, build.sbt or project/ is newer than it".
VALIDATION RESULTS: the orchestrator's status and diff matched the tree. The benchmark summary file matches the work order 5 report.
ASSUMPTIONS:
- Agent statements that they did not read or list labels/ or scratch/stores-live/ cannot be verified beyond the code; no code path reads them.
- InMemoryGraph.scala, GraphReadSpec.scala and the rag Contracts.scala and Records.scala were not reviewed line by line.
RISKS:
- Until F1 to F4 are fixed, the human's live benchmark verification counts will overstate groundedness.
- The category-to-operation map in Benchmark.categoryOk is the agent's own reading of the CSV's category column. Work order 5 asks the human to review it.
- F2 for corpus-level questions (B13, B15, B17, B24) remains open.
MANUAL ACTION REQUIRED:
- Route F1 to F5 and m1, m2, m5, m7, m8 and m9 to rag-engineer.
- Route F6 to storage-engineer first, then rag-engineer.
- Route m4 and m6 to entity-resolution-engineer.
- The human decides on m3 (supply the OpenAlex injection text or waive).
- Consider holding the live benchmark run until F1 to F4 are repaired, so its verification counts are meaningful.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to owners (repair-01), then re-review; human gate on APPROVE
````

## Review 2: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 09, Review 2. Repair-01 parts A to D checked against rulings 17 to 28 and every "Human rulings" section of prompt.md (rulings 1 to 16 still apply).
VERDICT: REQUEST_CHANGES

Two MAJOR findings are open, N1 and N2, and there is no CRITICAL finding. Both majors are in the rag verifier and belong to rag-engineer.

What the repair fixed:
- Every review 1 failure input (F1 a, b, c; F2; F3; F4 with a corpus name and with a non-corpus name; F5) now fails verification or fails a test.
- F6, part D, ruling 26, ruling 27, and the ruling 23 minors are resolved. Each fix has a test that needs no network, and each of those tests passes in the supplied logs.

What is still open:
- Two variants of the same failure classes still verify a wrong claim.
  - N1: a number written as a word, for example "three joint publications", is not checked at all.
  - N2: the names written in a claim are not tied to the researchers its values assert.

SCOPE REVIEWED
- Change set: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/4291d6d5-5427-4cb6-8358-4b98c60b3753/scratchpad/changeset-review2.txt. I read the git status, the diff stat, and the diffs of modules/core and modules/entity-resolution. For untracked files I read the repository copies.
- Read in full:
  - rag runtime: Verifier.scala, Workflow.scala, Llm.scala, JsonSchema.scala, Synthesis.scala, Semantic.scala, Evidence.scala, Planner.scala, Resolver.scala
  - rag live: BedrockHaikuClient.scala
  - rag: SemanticContext.scala
  - storage: ItemFileReader.scala
  - prompts: synthesis-v2.md and synthesis-output-v2.schema.json
  - rag tests: VerifierSpec.scala, InjectionSpec.scala, LlmSpec.scala
- Read in part:
  - GraphStages.scala (stageOf, run) and Benchmark.scala (verification and llm-budget checks)
  - AskCli.scala (live wiring) and RuntimeFixtures.scala (injection constants)
  - RUNTIME.md sections 11 to 21
  - Entity-resolution: AdjudicateSample.scala (session, live wiring) and the test names in AdjudicateSampleSpec
- Evidence read:
  - All logs in repair-1a/, repair-1b/ and repair-1d/
  - claim-status-changes.txt and runtime_md.py
  - fake-benchmark-summary.json
  - live-adjudication.txt
  - one live-presmoke audit object (qry-7120d33d…json)
- Not read: anything under labels/, scratch/stores-live/, scratch/runtime-presmoke/ or scratch/er/adjudication-live/, the live-benchmark-* and human-sbt-check-* files, and the thread dump.

FINDINGS (ranked)

N1. MAJOR. A number written as a word is never checked, so a wrong count still verifies. This is a residual of ruling 17 / F1.
- Where:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala:41: `NumberRe = "\\d+(?:\\.\\d+)?"` finds digits only.
  - textProblems at :354-369 checks only those digit matches.
  - The context-only test at :579-580 also uses only NumberRe and Relation.
  - lineOk at :608-620 relies on the same rules.
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/prompts/synthesis/synthesis-v2.md:15 never asks for digits.
- Failure input (fixture world, question "Have Ada Lovelace and Grace Hopper coauthored a paper?"):
  - A COAUTHORSHIP claim with text "Ada Lovelace and Grace Hopper have three joint publications in all-years.", citing the Ada–Grace COAUTHOR_EDGE (stored count 2), window "all-years", values {"jointPublicationCount":"2"}, subjects [ada, grace].
  - The values pass, numbers-in-text finds no number and passes, and the window and identity checks pass. The claim is VERIFIED with a false count.
  - A summary line "They have three joint publications." is kept the same way.
  - A DESCRIPTIVE claim "Ada Lovelace has three students." stays PARTIALLY_SUPPORTED as context, although VerifierSpec.scala:216 shows the digit form "has 3 students" is UNSUPPORTED and ruling 19 says a semantic claim "states no count".
  - "Never coauthored" against a stored count of 2 verifies too (a zero written as words).
- Impact:
  - Ruling 17 says every number written in a claim must be bound to a fact. The gate requires that verification catch wrong numbers.
  - Claude models commonly spell out small counts ("two papers", "three hops", "first").
  - The fake benchmark cannot show this, because the template writes digits.
  - No test covers it.
- Owner: rag-engineer.

N2. MAJOR. The names written in a claim are not tied to the researchers its values assert. A wrong pair verifies, including the shape of review 1 F1(b).
- Where:
  - Verifier.scala:378-397 (`namesBad`): any name held by a cited item counts as cited.
  - :399-407 (`unknownNames`): any name in the resolution context counts as a bundle name.
  - :231-237 (`researcherNames`) includes every candidate's and other-name match's `topCoauthors`.
  - :549-563 (PATH_HOP) checks the hop facts in values, never the names in the text.
- Failure input (a), path question "What is the shortest coauthorship path between Ada Lovelace and Edsger Dijkstra?":
  - A PATH_HOP claim with text "Ada Lovelace and Grace Hopper have 1 joint publication, first in 2012 and last in 2012.", citing only the GRAPH_PATH item, window "all-years", subjects [].
  - Its values hold all five facts of hop 2 (grace to edsger, count 1, 2012, 2012). Hop 1 is Ada–Grace with 2 joint publications, 2001 to 2002.
  - Each hop.2 value matches the stored hop 2, so path-hops passes. "1" binds to hop.2.jointPublicationCount next to "joint", and 2012 binds as a year fact.
  - Both names are hop.1 names of the cited item, so identity passes.
  - The claim is VERIFIED with Ada–Grace given hop 2's count and years. This is the review 1 F1(b) failure with the hop index moved into values. VerifierSpec.scala:110-138 covers only hop 1.
- Failure input (b), the pair question above:
  - A COAUTHORSHIP claim with text "Ada Lovelace and Edsger Dijkstra have 2 joint publications in all-years.", citing the Ada–Grace edge, values {"jointPublicationCount":"2"}, subjects [ada, grace].
  - "Edsger Dijkstra" is in no graph item of this bundle, so `foreign` does not fire.
  - But Grace's resolution candidate lists him in `topCoauthors`: Resolver.scala:88-116 calls graph.coauthors for every candidate, and InMemoryGraph.scala:76 fills coauthorName. So `unknownNames` strips his name, and the claim is VERIFIED.
  - The same name placed in graph evidence would FAIL, so the outcome depends on where the name happened to be loaded.
  - In live data the resolved researcher's top coauthors are exactly the names a profile document mentions.
- I traced both inputs through the code by reading it. I could not execute them (no Bash).
- Impact:
  - The gate requires that verification catch wrong identities and hops (rulings 8, 17 and 20).
  - Ruling 20's list of bundle names includes candidates and coauthor lists, but nothing ties those names to the claim's cited evidence. RUNTIME.md section 21 (line 459) discloses only the numeric leniency across cited items, not this.
  - No test covers either variant.
- Owner: rag-engineer.

MINOR
- n1. Stale synthesis-v1 text after part D (ruling 28). Owner: rag-engineer.
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md:406 still gives the default as `synthesis-v1`.
  - RUNTIME.md:255 says v1 stays loadable "until configuration selects v2". Configuration now selects v2.
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Records.scala:398 says "schema synthesis-output-v1".
  - The work order places this Scaladoc in modules/core/.../Records.scala. It is in modules/rag; core Records.scala contains no synthesis text.
  - These are documentation and comment only, with no behaviour effect. RUNTIME.md is the place ruling 17 says the versions are recorded, so it should be fixed in the same round.
- n2. A number written as a rank can still pass when it equals any listed value. Owner: rag-engineer.
  - Where: Verifier.scala:185 (`rank.<k>.value` gets the whole-text scope) and :362-363 (whole-scope bindings skip the nearness check).
  - Example: a ranking by communitiesConnected with values {rank.1.value: "5", rank.1.researcherId: X} and text "X ranks 5th by communities connected" verifies, though X is ranked 1.
  - This is narrow (the wrong rank must equal a listed value), but it is a residual of ruling 17's rank rule.
- n3. A name that sits inside a longer bundle text is never flagged. Owner: rag-engineer.
  - `unknownNames` (Verifier.scala:406) drops a span contained in any bundle text, so a corpus name inside a cited title or inside a longer display name passes.
  - The strip at :403 removes any single capitalized bundle value, which can cut a two-word span down to one word.

REVIEW 1 FINDINGS AND RULING ITEMS (status, fix, test)
Paths below are relative to /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/{main,test}/scala/scholarchain/rag/runtime unless shown in full. "log 1d2" means repair-1d/check2.log, the last full run; the same tests also pass in repair-1b check1.log and check2.log.

- F1, ruling 17: resolved for the review inputs; not fully resolved (N1, N2).
  - Fixes:
    - values-required: Verifier.scala:480; synthesis-output-v2.schema.json:27-28; Synthesis.scala:51-53 (v1 too)
    - binding: Verifier.scala:169-194, 325-370, 439-446
    - number rule: Verifier.scala:65-71
    - rank: Verifier.scala:432-437, 494-496
    - PATH_HOP: Verifier.scala:549-569
  - Tests: VerifierSpec:45, 54, 92, 110, 141.
- F2, ruling 18: resolved.
  - Fixes: Verifier.scala:319, 336-353, 520.
  - Test: VerifierSpec:159.
- F3, ruling 19: resolved.
  - Fixes: Verifier.scala:575-596; Workflow.scala:606-611; Benchmark.scala:494-506.
  - Test: VerifierSpec:205 (claim level and through the workflow).
- F4, ruling 20: resolved for the review inputs; residual N2(b).
  - Fixes:
    - Verifier.scala:399-407, 527-539, 608-620, 655-689
    - the bounded corpus lookup at Workflow.scala:461-475
  - Tests: VerifierSpec:257 and 308.
- F5, ruling 21: resolved.
  - Fixtures: RuntimeFixtures.scala:64-66, 101, 150, 277.
  - Tests: InjectionSpec:84, 93, 118.
  - Each escape removed alone fails a test:
    - f5-no-planner-escape.log:62
    - f5-no-synthesis-escape.log:56
    - f5-no-evidence-escape.log:53 and :87
  - m3 waiver: RUNTIME.md:375.
- F6, ruling 22: resolved.
  - storage /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/ItemFileReader.scala, tested by DynamoSpec.scala:415, 458, 495.
  - AskCli.scala:169 and :208 use the reader; ItemFileIndex is gone (no reference in modules/rag).
  - Peak heap 5,448 MiB at -Xmx6g (repair-1a/probe-6g.log:12 and :14).
- m1: resolved. Fixes: GraphStages.scala:46-55, 688-724; Workflow.scala:486-493. Test: WorkflowSpec:585.
- m2: resolved. Fixes: Resolver.scala:128-132, 176-179. Test: WorkflowSpec:602.
- m4: resolved. Fixes: entity-resolution tools/Runner.scala:56-67, 97-99. Test: AdjudicateSampleSpec:444.
- m5: resolved.
  - Fixes: BedrockHaikuClient.scala:71-78; Llm.scala:120-121; Benchmark.scala:20-23, 527-532. The stated maximum is 8 requests per question (RUNTIME.md:323) and 240 for 30 questions.
  - Tests: LlmSpec:158 and HaikuClientSpec.
- m7: resolved.
  - Fixes: Workflow.scala:171-184, 414-432; Resolver.scala:24-35; Semantic.scala:90, 126; Llm.scala:112.
  - Test: WorkflowSpec:619.
- m8: resolved. Fix: Planner.scala:602-650. Test: WorkflowSpec:643.
- m9: resolved in rag (Planner.scala:82). The core space at StableIds.scala:352 is unchanged and accepted under ruling 24.
- Haiku output validation, ruling 23 with ruling 27 (rag): resolved in code.
  - Fixes:
    - fence: Llm.scala:171-177; JsonSchema.scala:136-146
    - recorded reason and shape: Llm.scala:161-166, 180-189
    - retry with schema paths: Workflow.scala:207-217
  - Tests: LlmSpec:46, 61, 110. Removing the fence step makes two tests fail (fence-disabled.log:56 and :58).
  - The diagnosis matches the evidence: the audit at qry-7120d33d…json:52-53 shows rejectedItems output-is-json, FALLBACK_RULES, and equal output lengths on both attempts.
  - The cause is still an inference, because the evidence holds no output text.
- Live results, ruling 23:
  - Part B quotes live-presmoke.txt and fixes the parse.
  - Part C fixed the ER reply format, and the human's rerun is COMPLETE (live-adjudication.txt:295 and :310; 13 schema failures, each reply wrapped in backticks).
  - Live confirmation for rag is pending the human's live benchmark, which this review was told not to read.
- Ruling 26: resolved.
  - The cause was replies failing validation, not the throttle (ruling 27 accepts this).
  - Fix for the late wake-up: entity-resolution BedrockLlmClient.scala:158-162. Wiring: Throttle.live at :167-171.
  - Tests: AdjudicateSampleSpec:284 (real clock), 298 (milliseconds), 313 (cache append), 327 (resume from a partial cache), 412 (halt after 3 failed pairs).
- Ruling 27 (ER):
  - Fence: Adjudication.scala:119-124. Shape: Adjudication.scala:170.
  - Tests: AdjudicationSpec (the reversed Phase 5 expectation, accepted) and AdjudicateSampleSpec:361, 395.
  - The gate item (66 vs 24 RESEARCHER UNRESOLVED) was not reviewed, as instructed.
- Ruling 28, part D: resolved.
  - modules/core/src/main/resources/scholarchain.conf:154 now reads "synthesis-v2".
  - ConfigLoaderSpec.scala:201 pins it, and it fails with the old value (repair-1d/without-change.log:25-26).
  - Leaving config-version unchanged is justified by the work order 2 rule.
  - The stale text is n1.

FAILURE-INPUT TABLE (review 1 input, its result now, the test, and the log that shows the test passing)

| Input | Result now | Test | Passing in |
|---|---|---|---|
| F1 a: values {} or {publicationCount:N} with text "N distinct coauthors" | UNSUPPORTED (values-required and numbers-in-text, or numbers-in-text naming publicationCount); a rank used outside a RANKING claim is UNSUPPORTED | VerifierSpec:54, 92 | 1d2:737-738 |
| F1 b: PATH_HOP with only hop.1 from and to, and hop 2's count in the text | UNSUPPORTED (path-hops and numbers-in-text); a wrong count in values is CONFLICTING | VerifierSpec:110 | 1d2:739 (variant N2(a) open) |
| F1 c: participationCoefficient "1" against a stored decimal | CONFLICTING; numberMatches("1","0.55") is false | VerifierSpec:141, 45 | 1d2:740, 736 |
| F2: "In 2015-2026 …" with window all-years | CONFLICTING (window) | VerifierSpec:159 | 1d2:741 |
| F3: DESCRIPTIVE coauthorship claim on a profile only | UNSUPPORTED (context-only); the answer abstains with NO_VERIFIED_CLAIM | VerifierSpec:205 | 1d2:742 |
| F4 with a corpus name (Barbara Liskov) | UNSUPPORTED (identity, detail "a corpus researcher"); summary and uncertainty line replaced | VerifierSpec:257 | 1d2:743 |
| F4 with a non-corpus name (Alan Turing) | UNSUPPORTED (identity); same replacements | VerifierSpec:257 (same loop) | 1d2:743 (variant N2(b) open) |
| F5: escapes removed | each removal fails a test | InjectionSpec:84, 93, 118 | passing in 1d2:701-703; failing without the escapes in f5-no-planner-escape.log:62, f5-no-synthesis-escape.log:56, f5-no-evidence-escape.log:53 and :87 |

ENTITY-RESOLUTION CHANGES AFTER REPAIR 1C
- I found none.
  - The ER diff and the untracked ER files contain only work order 6 and the changes repair 1C reports.
  - Parts 1A, 1B and 1D list no ER file.
  - The Nov 11 logs (1A, 1B, 1D) all show entityResolution at 111 tests passing, with 1C's test names.
- I cannot see file modification times with Read, Grep and Glob. So the orchestrator's timestamp observation (latest ER edit 2026-11-07 15:39, before the 16:34 rerun) is unverified by me, though nothing I read contradicts it.

OWNERSHIP CHECK (changed path, owner, author matches)
- modules/storage/** (ItemFileReader, ItemFileReaderProbe, DynamoSpec, STORAGE.md): storage-engineer, part A. Yes.
- modules/rag/** and prompts/** (synthesis-v2.md and the v2 schema; ItemFileIndex deleted): rag-engineer, part B. Yes.
- modules/entity-resolution/**: entity-resolution-engineer, part C. Yes.
- modules/core/** (scholarchain.conf:154, ConfigLoaderSpec:201): domain-engineer, part D. Yes.
- docs/agent-runs/phase-09/{agent-report.md, review-report.md, repair-01.md}: orchestrator report assembly. Permitted.
- docs/agent-runs/phase-09/evidence/** is human-owned. Who copied the repair logs there cannot be told from the change set. If it was not the human, it is outside the orchestrator's permitted writes. The human should confirm.
- Not touched: spec/, labels/, experiments/ (benchmark unchanged), build.sbt, project/, .claude/, CLAUDE.md, docs/agents.md.

FLOOR AND FENCE CHECK: nothing weakened or widened. There is no change to hooks, permissions, settings, markTestRun or the reviewer fence, no new library and no version change. The marker was written only by markTestRun (the log lines are cited below).

EVIDENCE GAPS
- Repair 1C: no sbt logs were supplied.
  - Its two Nov 7 check runs and its fence-disabled check (2 failures) are claims in its report only.
  - Later logs support them indirectly: 111 ER tests pass, including 1C's tests.
- Repair 1A: the mutation run (key check removed, 1 failure) has no log.
- Repair 1B: its statement that no file under modules/rag or prompts is newer than the marker cannot be checked with my tools.
- Live rag confirmation of the fence cause is outstanding (live-benchmark-* was excluded from this review).
- There was no sbt run in this session. Nothing observed covers the tree after 2026-11-11 17:39:28.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.
TEST RESULTS (as supplied, quoted from logs):
- repair-1d/check2.log:903-1107: "Tests: succeeded 72 … 175 … 87 … 111 … 43 … 70, failed 0"; "markTestRun: tests passed, wrote …/.claude/.last-test-run"; "[success] Total time: 259 s … completed Nov 11, 2026, 5:39:28 PM".
- repair-1d/check1.log:1107-1108: the same totals, completed 5:34:37 PM.
- repair-1b check1.log and check2.log: the same totals, completed 4:57:35 PM and 5:02:10 PM.
- repair-1a check1b.log and check2.log: 53 rag tests (the count before part B), the other totals the same, completed 3:56:31 PM and 4:01:10 PM.
VALIDATION RESULTS:
- The fake benchmark summary (fake-benchmark-summary.json:92-93) shows verified 81 and partially supported 28, matching part B's table.
- claim-status-changes.txt lists exactly 28 changes, all VERIFIED to PARTIALLY_SUPPORTED on template DESCRIPTIVE claims.
ASSUMPTIONS:
- N1 and N2 are established by reading the code paths cited above against the fixture facts. They were not executed.
- Ruling 20's list of bundle names (candidates, coauthor lists) governs which names may appear at all. It does not make a name verified for a claim that cites other evidence.
RISKS:
- Until N1 and N2 are fixed, the human's live benchmark verification counts can include claims with wrong spelled-out counts or a wrong pair of researchers.
- Process notes, no finding:
  - Part B edited RUNTIME.md through a python script (runtime_md.py). That is inside its own paths, but it bypasses the file-tool hooks.
  - Part D split a guard-blocked `tee` command. The blocked write target was the scratchpad, not the marker.
MANUAL ACTION REQUIRED:
- Route N1, N2 and n1 to n3 to rag-engineer, then re-review.
- The human confirms who wrote docs/agent-runs/phase-09/evidence/repair-1a, repair-1b and repair-1d.
- The gate item on 66 vs 24 RESEARCHER UNRESOLVED stays with the human.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to owners, then re-review; human gate on APPROVE

VERDICT: REQUEST_CHANGES
````

## Review 3: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 09, Review 3. Repair-02 checked against rulings 29 and 30 and every human rulings section of prompt.md (rulings 1 to 28 still apply). Covers N1, N2, ruling 30, regressions since review 2, question (a), and question (b), the live run run-2f7b6b9ce82a37c0a80d892dc09422f8.
VERDICT: REQUEST_CHANGES

Summary
- N1 and N2 are fixed. Every review 2 failure input now ends UNSUPPORTED or CONFLICTING, and disabling each new rule makes a test fail.
- Ruling 30 is met. The planner messages use fixed text and never repeat model text. planner-v2 is built from the validator's own tables. The config default and its test are correct. No safety rule was loosened.
- The live run shows that the N1 number-word change removes correct claims. Two MAJOR findings follow from it (R3-1, R3-2). They cause 13 of the 43 removals, including every path-hop explanation, and two questions abstain because of them.
- Under ruling 29, a REQUEST_CHANGES at review 3 goes to human escalation.

SCOPE REVIEWED
- Change set: the orchestrator's index at /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b8fa3839-5ba3-44e1-a883-a802cddc0ef3/scratchpad/change-set-index.txt (git-status.txt, git-diff.txt, untracked-modules-prompts.txt, runtime-answers-since-start.txt).
- Code read in full:
  - modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala
  - prompts/synthesis/synthesis-v2.md, prompts/planner/planner-v1.md, prompts/planner/planner-v2.md
- Code read in part:
  - Planner.scala lines 1-160 and 370-700
  - Synthesis.scala lines 250-390
  - Workflow.scala (runIdFor)
  - VerifierSpec.scala lines 111-138 and 325-584
  - PlannerSpec.scala lines 120-229 and the ruling 30 test names
  - RUNTIME.md lines 99-114, 291 and 482
  - The core diff for scholarchain.conf and ConfigLoaderSpec
- Reports: review-report.md (Review 2), repair-02.md (all three sections), prompt.md (all rulings).
- Logs:
  - docs/agent-runs/phase-09/evidence/repair-02/disable-*.log
  - repair-02/ruling-30/disable-d1 to d5 logs
  - human-sbt-check-1.txt and human-sbt-check-2.txt
  - live-benchmark-summary.json
- Live run:
  - all 30 runtime answers in run-80e436dea5587ed42c03f98d66a05bb2, with the per-claim checks of all 43 removed claims
  - the matching EvidenceBundle.jsonl facts (hop, rank and edge facts)
  - the audit LlmCall failures
- Not read: labels/, scratch/stores-live/, scratch/runtime-presmoke/, scratch/er/adjudication-live/.

FINDINGS (ranked)

R3-1. MAJOR. Ordinals that state a position in a list can never be bound, so correct path-hop, facet and similar claims are removed.
- Where (paths under modules/rag/src/main/scala/scholarchain/rag/runtime/):
  - Verifier.scala:120-121 (OrdinalRe) and :131-143 (wordsToDigits) turn first to tenth into 1st to 10th.
  - :193: the "rank" measure family matches the "st/nd/rd/th" suffix, so the converted number can only bind to a rank fact.
  - Only rank.k.storedRank can satisfy it. That happens through RankKey at :274, and only when rankOk at :585-590 holds.
  - The index k inside an asserted hop.k.* or entry.k.* key is never a binding: HopKey at :272-273 uses only the field name, and ListKey at :279 gives None.
  - Also, YearRange is blanked before the conversion (:439-452), so in "first collaborated in 2009-2012" the ordinal no longer sees a following year and is counted as 1.
- Failure input (code-verified against the live fact values of B10, run-80e436…, EvidenceBundle.jsonl line 83):
  - A PATH_HOP claim: "The second hop links Ivona Brandic and Paolo Romano 0002 with 1 joint publication in 2012."
  - values: hop.2.from, hop.2.to, hop.2.jointPublicationCount "1", hop.2.firstYear "2012", hop.2.lastYear "2012"
  - Every asserted value, path-hops and identity pass. "second" becomes "2nd", and 2 binds nothing.
  - Result: UNSUPPORTED, "2 is not an asserted fact of a cited item".
  - The same happens for a FACET claim "Fault tolerance is Franck Cappello's second most common topic with 41 papers" that asserts entry.2.name and entry.2.count.
- Live evidence: the claim text of removed claims is not stored, so the claim shapes are inferred. In every case below, every asserted value passed and the only unbound number equals the index k of the asserted keys.
  - B10 qry-4a976029…answer.json:673, 740, 807 and B11 qry-48d32d34…answer.json:579, 646, 713: the hop 1, 2 and 3 claims fail on exactly 1, 2 and 3.
  - B30 qry-6d860103…answer.json:579: hop 2 fails on "2" alone.
  - B25 qry-7a4c9daa…answer.json:554, 601, 648, 695: entry.2 to entry.5 claims fail on 2, 3, 4 and 5. The entry.1 claim ("most common topic with 80 papers") is VERIFIED.
  - Total: 11 correct claims removed.
- Impact:
  - All 9 PATH_HOP claims in the live run were removed, 7 of them only because of this. So no live answer explains the hops of a path (B10, B11, B30), which is a spec Phase 9 path-evidence item.
  - B30 abstains NO_VERIFIED_CLAIM, although its hop 2 claim is otherwise fully verified.
  - B25 keeps 1 of its 5 topics.
- Why this is a defect:
  - Ruling 29 asks the repair to "avoid false positives".
  - No prompt change was allowed, and synthesis-v2 gives the model no rule against ordinals.
  - RUNTIME.md:482 discloses only "the first time", "the fifth paper" and "one hop away". It does not disclose hop or list positions.
  - No test covers this, and the fake benchmark cannot show it, because the template never writes ordinals.
- Owner: rag-engineer.

R3-2. MAJOR. Cardinal conversion turns the measure's own name "on one paper" (or "a single paper") into an unbound 1.
- Where:
  - Verifier.scala:124 and :143 (CardinalRe) and :116 ("a single" becomes 1). The only exceptions are "one of" and "one another" (:115).
  - The prompt itself uses the phrase: synthesis-v2.md:18 says "team size is the largest number of authors on one paper".
  - The metric is MAXIMUM_COAUTHORS_ON_ONE_PAPER. Neither the "team" family nor the "coauthors" family (:171-176) contains "one paper".
- Failure inputs (code-verified):
  - METRIC_VALUE "Ian T. Foster's largest team had 98 authors on one paper in all-years." with values {maximumTeamSize: "98"}: UNSUPPORTED, "1 is not an asserted fact of a cited item".
  - A correct RANKING claim that asserts rank.1.researcherId, rank.1.storedRank "1" and rank.1.value "286", with text like "… ranks first with 286 coauthors on one paper": UNSUPPORTED, "1 is not written next to the name of rank.1.storedRank".
- Live evidence:
  - B05 qry-2678edc0…answer.json:374: maximumTeamSize passes; the only failure is "1".
  - B04 qry-bef500c5…answer.json:141-159: every rank value passes, including researcherId, and the failure is "1 is not written next to the name of rank.1.storedRank". A rank-position "1" (first, #1, rank 1) would sit next to the rank family, so a second, non-rank "1" must be in the text.
  - 2 correct claims removed.
- Impact:
  - B04 abstains NO_VERIFIED_CLAIM only because of this; its claim-1 fails for its own reasons.
  - Any team-size or maximum-coauthors claim worded as the prompt words it is removed.
- Owner: rag-engineer.

MINOR (brief; owner rag-engineer unless stated)
- n4. Wrong-rank escape through the year exception (narrow).
  - Where: OrdinalRe :121 skips an ordinal followed within 4 words by a single year, or by "2015 and 2026", which YearRange does not match.
  - The human's example "ranks third in 2015-2026" does not escape (see (a)).
  - Input that does escape: the summary "Grace Hopper ranks second since 2001.", when another kept claim asserted firstCollaborationYear 2001. "second" is not converted, 2001 binds as a year from keptBindings (:817, :825-836), and the false rank is kept, even when no ranking is in the bundle.
  - In a single claim, the year must itself be an asserted year fact, so this needs a RANKING claim that also cites a dated item.
- n5. Ordinals above "tenth" are never checked. Rankings hold the top 50, so "ranks twelfth" against a stored 11 passes. This is within ruling 29's "at least", but it is not disclosed in RUNTIME.md section 21.
- n6. The nearest-measure vocabulary (since repair-01, not repair-02) conflicts with the prompt's own wording.
  - Where: Verifier.scala:378-414, and Families at :170-178, where "coauthors" sits in the coauthors and degree families.
  - "an average of 2.72 coauthors per paper": "coauthors" (distance 1) is nearer than "average" (distance 4), so it fails.
  - "intensity of 798 joint publications" fails the same way; synthesis-v2.md:18 itself defines intensity as "joint publications summed over coauthors".
  - Likely cause of B18 qry-460555e8…answer.json:517 (1 removal; inferred, text not stored).
- n7. The text of removed claims and dropped uncertainty lines, and the reasons the lines were dropped, are recorded nowhere: not in the answer, the audit, or the bundle.
  - 14 lines were dropped across the run.
  - Removals cannot be audited from the record. Every (b) judgment below is an inference from check details and bundle facts.

Known items, not re-raised: ruling 24; review 2 n1 to n3; RUNTIME.md:418; max_tokens and empty evidenceIds (B23, B26, B29 BUDGET_EXHAUSTED); FU2 (B13, B15, B17, B24).

(1) N1 AND N2: RESOLVED
Line numbers are in VerifierSpec.scala unless stated.
- N1 inputs:
  - "three joint publications" vs a stored 2: UNSUPPORTED, numbers-in-text (:359-362).
  - The summary line is replaced (:400). The uncertainty line is dropped (:404-407).
  - DESCRIPTIVE "three students": UNSUPPORTED, context-only FAIL (:384-397).
  - "never coauthored" vs 2: UNSUPPORTED; with values {jointPublicationCount: 0} it is CONFLICTING (:379-382).
  - "two joint publications" vs 2: VERIFIED (:363-367).
- N2 inputs:
  - N2(a): UNSUPPORTED, identity (:473-486). The correct pair is VERIFIED (:487-490).
  - N2(b): UNSUPPORTED, identity (:507-516).
  - Lines naming a disallowed researcher are replaced or dropped (:493-499, :532-538).
- Code:
  - N1: Verifier.scala:103-143, used at :439, :472 and :739. edgeExists false is read as 0 at :281 and :607-608.
  - N2: researchersOf at :482-506, allowedNames at :514-524, namesBad at :530-550, keptNames at :818-819.
- Disable logs (docs/agent-runs/phase-09/evidence/repair-02/):
  - disable-n1-words-in-text.log:58-59
  - disable-n1-words-in-context-only-2.log:79 (the first attempt passed 76/0 at disable-n1-words-in-context-only.log:100, as the report disclosed)
  - disable-n1-zero-expressions.log:46, :50
  - disable-n1-edgeexists-zero.log:79
  - disable-n2-asserted-names.log:50, :62
  - disable-n2-name-universe.log:80
  - disable-n2-line-names.log:48
  - disable-runid-verifier.log:54
  - Each disabled rule fails a test.
- Run ID: Verifier.Version is "verifier-v3" and is hashed into the runtime run ID (Workflow.scala:85, :96).
- AMBIGUOUS and UNKNOWN answers are unchanged: B09 and B12 are deterministic text.

(2) RULING 30
- Messages:
  - Planner.scala:391-474 builds every rule 1-8 failure from enum values, the allowlist tables, configuration and counts. There is no model text.
  - rule-5 and rule-6 never repeat the rejected term (PlannerSpec:176). A not-stored window name is never repeated (repair-02 test, PlannerSpec:525).
  - Live: the only planner retry, qry-f2837b5f….json:125, carries fixed text plus the output's shape only.
- Validator decisions:
  - Windows.requestProblems (:107-122) reproduces the old rule-7 conditions stated in the report and the old RUNTIME.md rule 7 text, including yearFrom <= yearTo.
  - Allowlist.scopes and metricAllowed (:40-60) are identical to planner-v1 lines 15-21.
  - The old allowlist and term tests (PlannerSpec:122-189) still pass.
  - Evidence gap: Planner.scala is untracked and no copy from before ruling 30 exists, so "exactly the same" could not be checked against a diff.
- planner-v2:
  - Its operation rules come from Allowlist.ruleText, and window names and comparison pairs come from configuration (Planner.scala:505-517).
  - The fixed sentence "RESEARCHER_RANKING … only scope is CORPUS" matches :41.
  - Rule 7's three forms match requestProblems.
  - No safety rule was loosened, and the operation set and output schema are unchanged.
- Default: scholarchain.conf now selects "planner-v2" (git-diff.txt:68). ConfigLoaderSpec pins it (git-diff.txt:766). The failure without the change is in ruling-30-domain/without-conf-change.log.
- Live: every live answer reports planner-v2; plan sources are MODEL 30, with 31 planning attempts.

(3) REGRESSIONS SINCE REVIEW 2
- No regression found outside the verifier, apart from R3-1 and R3-2.
- The Synthesis template wording changes (Synthesis.scala:263-265, 351, 363) are consistent with the new rules.
- The fake benchmark shows 0 claim changes (claim-status-changes.txt).
- The human's final-tree checks pass.

ANSWER TO (a)
- Ordinary phrases outside a ranking claim do fail, and in numbers-in-text, not in the rank rule.
- Any first to tenth word not followed within 4 words by a year becomes an unbound "Nth". This gives R3-1:
  - "the second hop" and "second most common topic"
  - a sentence-initial "First, …" in a summary or uncertainty line, which then gets replaced or dropped
  - "second-largest"
  - "first … in 2009-2012", because the year range is blanked first
- "one"/"a single"/"once"/"twice" do the same (R3-2): "on one paper", "once again", "twice as many".
- In DESCRIPTIVE claims these words make context-only FAIL, so the claim is removed instead of labelled.
- The year exception does not let "ranks third in 2015-2026" escape. In textProblems, YearRange blanks the range before wordsToDigits (Verifier.scala:439-452), so "third" becomes "3rd" and needs rank.k.storedRank with a matching researcherId. RUNTIME.md:291 says the same.
- It escapes only when a single year follows (or "between 2015 and 2026", or "2015/2026") and that year is itself an asserted year fact or a free note number. Otherwise the year fails numbers-in-text. That residual is n4.

ANSWER TO (b): live run run-2f7b6b9ce82a37c0a80d892dc09422f8, 43 removed (40 UNSUPPORTED, 3 CONFLICTING)
- My grouping reproduces the human's rule counts exactly: numbers-in-text 38, rank 21, context-only 3, identity 2, window 2, asserted-values 2.

By design: 29 of 43 (67.4%). The model wrote unbound numbers or ranks, or broke a prompt rule.
- 21 RANKING claims omit rank.k.researcherId, which synthesis-v2.md:17 requires: B19 ×10, B21 ×10, B03 ×1 (B03 used displayName instead).
  - 19 of them also fail numbers-in-text, because the rank number loses its binding.
  - Design note for the human, not a defect: since N2, the name rule already ties the written name to rank k, so this rule now removes claims that name the right person. B21 claim-1 writes no rank at all and is still removed.
- 2 B30 hop claims cite work years 2013/2019 and 2011/2019 without asserting a year fact. They also fail on the hop index.
- B04 claim-1: it asserts maximumCoauthorsOnOnePaper, which is absent from the cited item (MISSING), and 286 is unbound.
- B18 claim-1: the topShare value is put under dominantShare (CONFLICT), and a computed "96.5" appears.
- 4 DESCRIPTIVE claims with counts, relations, windows or author lists: B15, B16, B18 claim-6, and B22 claim-3, whose window is "2015-2026" against the item's "2000-2014>2015-2026" (prompt rule 4; a refinement candidate, not a defect).

Verifier defect: 14 of 43 (32.6%).
- 13 (30.2%) come from the repair-02 number-word conversion:
  - R3-1, 11 claims: B10 ×3, B11 ×3, B30 ×1, B25 ×4
  - R3-2, 2 claims: B04 claim-2, B05 claim-1
- 1 (2.3%) comes from measure vocabulary: n6, B18 claim-3, which predates repair-02.
- Consequence: B04 and B30 would not abstain without these defects.
- Caveat: removed claim text is not stored (n7), so the word versus digit form is inferred. The position index cannot be bound in either form.

ENTITY-RESOLUTION
- Nothing in modules/entity-resolution changed after repair 1C.
  - The orchestrator's index gives the latest ER modification time as 2026-11-07 15:39:30. I cannot see mtimes myself.
  - No repair-02 section lists an ER file.
  - The human's final checks show entityResolution at 111 tests passing, the same as at review 2 (human-sbt-check-1.txt:958, -2.txt:987).
- The live adjudication result stands.

OWNERSHIP CHECK (changed path, owner, author matches)
- modules/rag/** (Verifier, Workflow, Synthesis, Planner, the specs, RUNTIME.md) and prompts/planner/planner-v2.md: rag-engineer. Yes.
- modules/core/src/main/resources/scholarchain.conf and ConfigLoaderSpec.scala: domain-engineer. Yes.
- docs/agent-runs/phase-09/*: orchestrator report assembly and human evidence. Permitted.
- No change to spec/, labels/, experiments/, build files, .claude/, CLAUDE.md or docs/agents.md.

FLOOR AND FENCE CHECK: nothing weakened or widened. There is no hook, permission, markTestRun or reviewer-fence change, no dependency change, and the marker was written only by markTestRun.

EVIDENCE GAPS
- n7: removed claim text and the reasons for dropped lines are not recorded.
- Ruling 30 validator equivalence: there is no file from before the change to diff against.
- ER modification times come from the orchestrator.
- live-benchmark-step1.txt and live-benchmark-step2.txt were not read; the summary JSON and the per-question files were.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (quoted from the human's final-tree checks)
- human-sbt-check-1.txt:869-1115: "Tests: succeeded 175 / 80 / 111 / 87 / 43 / 70, failed 0"; "markTestRun: tests passed"; "[success] Total time: 263 s … 9:52:00 PM".
- human-sbt-check-2.txt:821-1115: the same counts; "[success] Total time: 264 s … 9:56:28 PM".

VALIDATION RESULTS
- Live summary: verification 30/0/40/3; plan sources MODEL 30; abstentions ANSWERED 15, NO_VERIFIED_CLAIM 6, BUDGET_EXHAUSTED 3, UNSUPPORTED_REQUEST 4, AMBIGUOUS 1, UNKNOWN 1.

ASSUMPTIONS
- Claim shapes are inferred from check details and bundle facts (n7).
- "By design" means the claim also fails a rule that the prompt states and the model broke.

RISKS
- Until R3-1 and R3-2 are fixed, live answers will not show hop explanations, and some correct team-size and topic claims will be removed. This fails closed: no false claim is kept.
- Process note for the human: .gitignore:28 ignores *.log and :27 ignores scratch/. So the evidence logs under docs/agent-runs/phase-09/evidence/**/*.log and the live per-claim checks are not committed by a plain git add.

MANUAL ACTION REQUIRED
- Human escalation (ruling 29): decide on R3-1 and R3-2, the minors n4 to n7, and the design note on the rank researcherId rule.
- Decide whether to force-add or copy the ignored evidence logs.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to rag-engineer by human decision (review 3 exit is human escalation), then re-review; human gate on APPROVE

VERDICT: REQUEST_CHANGES
````
