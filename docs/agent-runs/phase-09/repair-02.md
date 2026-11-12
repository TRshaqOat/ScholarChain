# Phase 09: repair-02 report

## Repair 2: rag-engineer

````text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09, Repair 2 (repair-02), rag-engineer. This covers ruling 29, N1 and N2. Edits are only in modules/rag/**, with no prompt or schema change; synthesis-v2 stays.

All paths below are relative to /Users/achyutpatel/Developer/Cloud/ScholarChain. Every cited log is in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/repair-02-logs/.

1. WHAT CHANGED FOR N1 AND N2 (file:line in the final, formatted tree)

Verifier.scala is modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala.

- **N1, number words.**
  - New `Verifier.wordsToDigits` (Verifier.scala:131), backed by the word tables and `ZeroRules`, `OrdinalRe`, `CompoundRe` and `CardinalRe` just above it (from about line 47).
  - It is applied in three places:
    - the claim, summary and uncertainty number rule (`textProblems`, :439), after bundle text is stripped and year ranges are set aside;
    - the free numbers of the fixed notes (`numbersIn`, :472);
    - the DESCRIPTIVE `context-only` rule (:739).
  - So a number word must bind to an asserted, equal, measure-named fact, exactly like a digit.
  - Zero support: `rule` maps `edgeExists` to the joint family (:281), and `stored` (:607) turns a stored `edgeExists` of `false` into 0. A correct "never coauthored" claim that asserts the no-edge item therefore verifies.
- **N2, names tied to asserted facts.**
  - New `researchersOf` (:482) gives the researchers an asserted key is about.
  - New `allowedNames` (:514) collects the researchers of the cited items whose facts the claim's values assert with an equal value. Window keys are excluded, and the resolved and candidate names of those same researcher IDs are added.
  - `namesBad` (:530) now checks every name in `researcherNames` against that allowed set: graph names, resolved names, candidates, their top coauthors, other-name matches and members. It uses whole-word, longest-first patterns (`Context.researcherPatterns`, :331), and text inside cited work titles is skipped.
  - Claims call it at :689.
  - Summary and uncertainty lines are now limited to the names the kept claims may write (`keptNames`, :818, passed to `lineOk`). Before, uncertainty lines could use any bundle name.
  - Resolved subject names no longer allow a name in a material claim. A DESCRIPTIVE claim keeps its resolved subjects, because it is context only.
- **Template wording (Synthesis.scala, so the new rules do not misread deterministic text):**
  - "no stored value" replaces "none" for a missing temporal side (:263-265).
  - "is not in the $w graph" replaces "has no publication" (:363).
  - A hop's PUBLICATION claim now also asserts the hop edge's `edgeId`, so the pair it names is allowed (:351).
- **Run ID:** Workflow.scala:85 and :96 (item 6).
- **RUNTIME.md:** the status line; section 12 (rules table, new "Number words" and "Asserted names" paragraphs, the lines paragraph); section 14 (run ID); section 21 (three new failure-mode entries); section 22 (the new run row and its explanation).

2. NUMBER WORDS, THE "a"/"an" DECISION, AND FALSE-POSITIVE GUARDS
Case is ignored. The rules apply in this order:
- **Zero expressions.**
  - "never" or "not" (with an optional "-ly" adverb) before coauthored, coauthor, collaborated, collaborate, co-wrote, co-write, or published, worked or written followed by "together", becomes "0 joint".
  - "no" before coauthorship(s) or collaboration(s), optionally followed by edge, link, tie, connection or relationship, becomes "0 joint". This does not apply when "path" follows.
  - "no" before optional adjectives (direct, joint, shared, common, distinct, unique, other, further, recorded, stored, known) and then coauthor(s), collaborator(s), publication(s), paper(s), work(s) or article(s) becomes "0 <rest>".
  - "none" becomes 0.
  - "no one" is not a zero.
- **Other fixed words.** "a single" and "once" are 1; "twice" is 2.
- **Ordinals.** "first" to "tenth" become 1st to 10th, which binds to the rank measure.
  - Exception: when a year (19xx or 20xx) follows within four words, the ordinal is a time marker. Examples: "first in 2001", "their first joint publication was in 2001".
- **Compounds.** Tens-unit forms ("twenty-one", "ninety nine") become 21 to 99.
- **Cardinals.** "zero" to "nineteen" and the tens "twenty" to "ninety" become digits. "one" directly before "of" or "another" is not a count.

The "a"/"an" decision: they never count as 1. They are articles with an existential meaning: "a path", "coauthored a paper" and "has a coauthor" are true for any count of at least 1. Counting them would remove correct claims.

Residual (in RUNTIME.md section 21): "have a joint publication" against a stored 0 is not caught by the number rule.

How false positives are avoided:
- "path", "stored value" and "language model" are not in the "no" noun lists.
- Ordinals before a year are skipped.
- Bundle texts (names, titles, topics) are stripped first.
- The N1 word test checks that these inputs gain no digit: "a path of a paper", "first in 2001 and last in 2002", "their first joint publication was in 2001", "one of them", "no coauthorship path connects them", "no stored value", "no language model was called".

3. FAILURE INPUTS AND RESULTS
All are in VerifierSpec.scala (modules/rag/src/test/scala/scholarchain/rag/runtime/), and all pass in check1.log and check2.log.

Test "N1 (ruling 29): a wrong count written as words fails; the right word form verifies" (:356):
- **N1 claim** "Ada Lovelace and Grace Hopper have three joint publications in all-years." against a stored 2: UNSUPPORTED; the only failing rule is `numbers-in-text` (:358).
- **N1 summary line** "They have three joint publications.": replaced by the kept claim's text. An uncertainty line with "three" is dropped.
- **N1 DESCRIPTIVE** "Ada Lovelace has three students.": UNSUPPORTED, and `context-only` is FAIL, not context only (:383-392).
- **N1 "never coauthored"** against a stored 2: UNSUPPORTED with values {jointPublicationCount: 2}; CONFLICTING with values {jointPublicationCount: 0} (:378-382).
- **Correct word forms** in the same test, all VERIFIED:
  - "two joint publications" against a stored 2 (:363);
  - "two … first in 2001 and last in 2002";
  - "never coauthored" and "no joint publications" against the no-edge item asserting edgeExists false;
  - "ranks first" (Grace, rank 1);
  - "a path of two hops".
- **Wrong word forms**, all UNSUPPORTED: "one joint publication" against no edge, "ranks second", "three hops".

Test "N2 (ruling 29): every name in a claim is a researcher of the facts its values assert" (:467):
- **N2 (a)** PATH_HOP "Ada Lovelace and Grace Hopper have 1 joint publication, first in 2012 and last in 2012." with hop 2's five facts: UNSUPPORTED; only `identity` fails, and its detail names Ada Lovelace (:472). The correct pair (Grace, Edsger) is VERIFIED.
- **N2 (b)** "Ada Lovelace and Edsger Dijkstra have 2 joint publications in all-years." citing the Ada–Grace edge: UNSUPPORTED; only `identity` fails (:506).
  - The test first asserts that Edsger is in `researcherNames` and in no graph item of the bundle.
- **Variant:** a resolved subject (Ada) named against Grace's metric is UNSUPPORTED (:518).
- **Summary and uncertainty lines** naming a researcher that no kept claim may write are replaced or dropped. This was checked in both the path and the pair contexts.

Also: test "ruling 29: the runtime run ID changes with the verifier version" (:542).

One expectation changed: in "F1 (a)" (:54), a claim with values {} now also fails `identity`, because it asserts no fact and so may name nobody.

4. EACH NEW RULE DISABLED TEMPORARILY
Each was disabled alone with the Edit tool, `rag/test` was run, and the rule was restored.

| Rule disabled | Failing test | Log |
|---|---|---|
| D1. Number words in the number rule | "N1 … wrong count written as words fails" (1 failed) | disable-n1-words-in-text.log |
| D2. Number words in context-only (first attempt) | none; the test was too weak (it accepted MISSING) | disable-n1-words-in-context-only.log |
| D2. Same rule, after strengthening the test to require `context-only` FAIL | "N1 … wrong count …" | disable-n1-words-in-context-only-2.log |
| D3. Zero expressions | "N1 … written as digits before binding" and "N1 … wrong count …" (2 failed) | disable-n1-zero-expressions.log |
| D4. edgeExists-false binding as 0 | "N1 … wrong count …" | disable-n1-edgeexists-zero.log |
| D5. Asserted names (any fact of any cited item allows a name, as before) | "F1 (a) … not to any fact or to none" and "N2 …" (2 failed) | disable-n2-asserted-names.log |
| D6. Name universe narrowed to graph display names | "N2 …" | disable-n2-name-universe.log |
| D7. Summary and uncertainty names widened to all graph facts | "N2 …" | disable-n2-line-names.log |
| D8. Verifier version removed from the run ID | "ruling 29: the runtime run ID changes …" | disable-runid-verifier.log |
| Title skip (found during development: before it existed, the template PUBLICATION claim citing the injection title that contains "Ada Lovelace" failed) | WorkflowSpec "a dominant name resolves …" | rag-test-1.log |

Restoration:
- After resuming, Verifier.scala and Workflow.scala were grepped and no disable edit remains.
- rag tests passed 76/0 in rag-test-restored.log and again in rag-test-resume.log.
- Both sbt check runs then passed.

5. FAKE BENCHMARK
- Free disk before the run was 16 GiB.
- The command is the RUNTIME.md section 22 fake command, with `-J-Xmx6g` and SPARK_LOCAL_IP=127.0.0.1. Exit 0. Log: fake-benchmark.log.
- New run: run-c5befe8f45411f497ea0db779978e564 (runtime run-42b0ea60246dac5cf349a537a90419df). Summary file: scratch/runtime/s3/reports/rag-benchmark/snapshot=2026-10-03/run=run-c5befe8f45411f497ea0db779978e564/summary-030.json.

| | run-3250005c… (repair-01) | run-c5befe8f… (repair-02) |
|---|---|---|
| Questions done | 30 | 30 |
| Checks | all pass except abstention 26/4 | same |
| Failing questions | B13, B15, B17, B24 (FU2) | B13, B15, B17, B24 (FU2) |
| Plan sources | RULES 30 | RULES 30 |
| Outcomes | ANSWERED 23, UNSUPPORTED_REQUEST 5, AMBIGUOUS 1, UNKNOWN 1 | same |
| Verification (verified / partial / unsupported / conflicting) | 81 / 28 / 0 / 0, 109 claims | 81 / 28 / 0 / 0, 109 claims |
| Graph reads | 1,017 (3,730 rows, 202 items) | 1,017 (3,730 rows, 202 items) |
| Semantic | 20 embeddings, 42 hits | 20 embeddings, 42 hits |
| Question latency | p50 21 ms, p95 611 ms | p50 23 ms, p95 480 ms |

- **Claims whose status changed: 0. False positives: none.**
- No claim text, summary, uncertainty line or abstention changed on any of the 30 questions, B09 (AMBIGUOUS) and B12 (UNKNOWN_RESEARCHER) included.
- The three template wording changes do not appear in this run's answers. Values maps were not compared, so the added `edgeId` on hop publication claims is not visible there.
- Comparison output: claim-status-changes.txt.
- The comparison script is /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/73c267b4-46e6-4f59-b8fa-07aaf35a1046/scratchpad/compare.py, which is not durable. I first tried to write it into scratch/repair-02-logs with the Write tool, and guard-paths.sh blocked that (scratch/ has no owner row). I did not work around the guard. The script only reads each question's benchmark result and runtime answer, then compares every claim's status and text, the summary, the uncertainty lines and the abstention.

6. RUN IDS
- New `Verifier.Version = "verifier-v3"` (v1 was work order 4, v2 repair-01, v3 repair-02).
- `Workflow.runIdFor` takes `verifier: String = Verifier.Version` and adds `verifier=$verifier` to the runtime run ID inputs.
- The benchmark run ID includes the runtime run ID, so it changes too: runtime run-b8694005… became run-42b0ea60…, and benchmark run-3250005c… became run-c5befe8f….
- A live rerun therefore cannot resume pre-repair results. The test above fails without the input (D8).

7. LIVE BENCHMARK COMMANDS (for the human, from Terminal at the repository root; agents never run them)
Step 1:
```
SCHOLARCHAIN_EMBEDDING_MODEL_ID=amazon.titan-embed-text-v2:0 sbt -J-Xmx6g "rag/runMain scholarchain.rag.cli.BenchmarkCli --questions experiments/benchmark/benchmark-questions-v1.csv --path-evidence scratch/stores/work/neptune-check/snapshot=2026-10-03/run=run-ee4ffd21aa20dbe4c078a4be96fe8c9f/expect-benchmark.csv --graph scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --items scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --objects scratch/stores/s3 --vectors scratch/stores-live/s3vectors --out scratch/runtime --live --profile scholarchain-sdk --region us-east-2 --per-minute 8 --max 2"
```
Step 2: the same command with `--max 30`. It resumes and skips B01 and B02.
- The flags are unchanged; the run IDs are new (verifier-v3).
- Call counts and times are as in the repair 1B report, since repair-02 adds no model call.

8. SBT CHECK (both with SPARK_LOCAL_IP=127.0.0.1)
- **First attempt:** failed at scalafmtCheck. One main and one test file in rag were unformatted. Its log was overwritten by the rerun.
- I ran `rag/scalafmtAll` and `rag/Test/scalafmtAll` (scalafmt.log), which reformatted Verifier.scala and VerifierSpec.scala, then reran.

| | Run 1 (check1.log) | Run 2 (check2.log) |
|---|---|---|
| Exit | 0 | 0 |
| Per-project counts, all passed, 0 failed | 175, 76, 87, 111, 43, 70 | 175, 76, 87, 111, 43, 70 |
| markTestRun wrote the marker | yes | yes |
| Total time | 259 s | 259 s |
| Completed | 8:32:08 PM | 8:36:37 PM |

- rag is the 76 (72 before, plus 4 new tests).
- Afterwards, `find -newer .claude/.last-test-run` over modules/rag/src, RUNTIME.md and prompts lists no file.

9. FREE DISK: 16 GiB at the start, before the benchmark, and at the end.

FILES CREATED: none in the repository. Logs are under scratch/repair-02-logs/; the compare script is in the session scratchpad.
FILES MODIFIED:
- modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala
- modules/rag/src/main/scala/scholarchain/rag/runtime/Workflow.scala
- modules/rag/src/main/scala/scholarchain/rag/runtime/Synthesis.scala
- modules/rag/src/test/scala/scholarchain/rag/runtime/VerifierSpec.scala
- modules/rag/RUNTIME.md
FILES DELETED: none

RULING 16 ITEMS:
- **Prompt, schema and model versions:** unchanged (planner-v1, synthesis-v2, planner-output-v1, synthesis-output-v2, Haiku us.anthropic.claude-haiku-4-5-20251001-v1:0). The verifier is now versioned as verifier-v3.
- **Allowed plan operations and graph query templates:** unchanged.
- **Identity resolution and abstention:** unchanged. AMBIGUOUS and UNKNOWN answers never reach the verifier and are byte-identical in the benchmark comparison.
- **Verification rules:** RUNTIME.md section 12.
- **Benchmark results:** item 5.
- **Injection tests:** unchanged and passing.

COMMANDS RUN:
- **Interruption:** the session was interrupted by an API rate limit just before the fake benchmark run, and resumed on the orchestrator's message.
- **Part 1, before the interruption:**
  - df, wc, grep, sed, ls, mkdir (scratch/repair-02-logs).
  - sbt `rag/testOnly …VerifierSpec` twice (verifier-spec-1.log, which showed a compile error; verifier-spec-2.log, which showed 1 failure).
  - sbt `rag/test`: rag-test-1.log (1 failure), rag-test-2.log (pass), the nine disable logs, rag-test-restored.log.
  - All with SPARK_LOCAL_IP=127.0.0.1.
- **Part 2, after resuming:**
  - grep of Verifier.scala and Workflow.scala for leftover disable edits.
  - sbt `rag/test` (rag-test-resume.log), df.
  - The fake BenchmarkCli run (fake-benchmark.log); ls and find over the new run and the earlier runtime outputs, read only.
  - python3 -I compare.py (claim-status-changes.txt).
  - sbt `rag/scalafmtAll rag/Test/scalafmtAll` (scalafmt.log).
  - `sbt check` three times: the first failed formatting, then check1 and check2. Both of the last two ran as background jobs and had finished when read.
  - ls of the marker, find -newer.
- **Not run:** no git, no aws, no network. Nothing under labels/, scratch/stores-live/, scratch/runtime-presmoke/ or scratch/er/adjudication-live/ was read or listed. Nothing existing under scratch/runtime/, scratch/stores/, scratch/spark/ or scratch/hadoop/ was modified. No background shell is left running.

TEST RESULTS: item 8. rag: 76 passed, 0 failed.
VALIDATION RESULTS: fake benchmark exit 0; the 4 known abstention FAILs (FU2) remain, every other check passes, and 0 claims changed status.

ASSUMPTIONS:
- For community and facet items, asserting any fact of the item allows all of its returned members or entries.
- A name inside a cited work's title is title text and is not checked.
- `edgeExists` false asserts a joint publication count of 0.

RISKS AND KNOWN FAILURE MODES (RUNTIME.md section 21):
- **Not covered:** "a" or "an" used as a count; number words above 99; fractions in words; zero phrasings outside the list.
- **Ordinal before a year:** an ordinal followed within four words by a year is read as a time marker, so it is not checked as a rank.
- **Correct model claims that will be removed:** claims that name a pair without asserting one of that pair's facts, and some phrasings such as "no coauthors in common" or "one hop away". This errs toward removal.
- **Fake benchmark limit:** it cannot show the model-side effect, because the template writes digits. The live run is the real test.

MANUAL ACTION REQUIRED:
- The human runs the two live commands after review 3.
- The compare script lives only in the session scratchpad, because guard-paths.sh blocks file-tool writes to scratch/. Copy it if it is needed as durable evidence.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: retrieval and groundedness evaluation, code-reviewer APPROVE (review 3: N1, N2, the tests, no regression)
````

## Repair 2, ruling 30: rag-engineer

````text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 09, Repair 2 (repair-02), ruling 30: live planner validation. Edits are only in modules/rag/** and prompts/**. The N1/N2 work (verifier-v3, number words, asserted names, and their tests) is untouched, and all of its tests still pass.

Paths are relative to /Users/achyutpatel/Developer/Cloud/ScholarChain. Every log cited is in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/repair-02-logs/ruling-30/.

1. CAUSE OF THE RULE-2 AND RULE-7 FAILURES

Evidence (read only): docs/agent-runs/phase-09/evidence/live-benchmark-step1.txt and the two audit objects:
- scratch/runtime/s3/logs/rag-runtime/snapshot=2026-10-03/run=run-d18aa34eee7633bd7f5caaf33933ff20/qry-feee55c56170fefd7606af01fd325c67.json (B01: "How many distinct coauthors does Jack J. Dongarra have?")
- …/qry-0bf42695989d67a58812a50f6634dbf1.json (B02: "… Rajkumar Buyya have in 2015-2026?")

rule-2 (RESEARCHER_RANKING with scope RESEARCHER):
- **Validator:** Planner.scala:41, `RESEARCHER_RANKING -> Set(CORPUS)`.
- **RUNTIME.md:** section 3 table (line 59) says CORPUS; line 72 says "Metric lookups for named researchers are not an operation".
- **planner-v1:** line 15 says "RESEARCHER_RANKING: CORPUS". It never says that a named researcher's own measure is not an operation, so for "how many distinct coauthors does X have" the model chose a per-researcher ranking.
- **Wrong side:** the validator and RUNTIME.md agree, so the prompt was the side that failed to exclude it.

rule-7 (window):
- **Validator:** Planner.scala:409-418 before this change. NAMED needs a stored name and null years. RANGE needs a null name and at least one year. DEFAULT needs everything null.
- **RUNTIME.md rule 7** (section 3, old line 96): "NAMED has a stored window name; RANGE has yearFrom <= yearTo where both are set; DEFAULT has no other field". This is underspecified, not contradictory.
- **planner-v1:** line 26 never says which fields must be null. Its `{{windows}}` text, "2015-2026 (2015-2026)", puts a name beside years, which invites NAMED "2015-2026" with yearFrom 2015 and yearTo 2026.
- **The exact field values are not recorded.** The audit kept only "rule-7 window", so the shape above is a reconstruction.
- **Wrong side:** the prompt. Accepting a name together with years would loosen the rule and let the code silently choose between two fields that may conflict. So the validator was kept, and RUNTIME.md rule 7 now states exactly what it already checked.

2. WHAT CHANGED (final, formatted tree)

modules/rag/src/main/scala/scholarchain/rag/runtime/Planner.scala:
- **The validator accepts and rejects exactly the same outputs as before.** Only the messages are new, plus the helpers that build them:
  - `Allowlist.scopeText`, `metricText` and `ruleText` (:66-74);
  - `Windows.requestProblems` (:107), which is equivalent to the old rule-7 match;
  - `Windows.allowedText` (:125).
- **Messages:** `Planner.problems` (:391) now gives every rule 1 to 8 failure as "<rule> <field>: <what is wrong>; allowed: <what is allowed>". The text is fixed and built only from enum values, the allowlist tables and configuration, never from the model's text. The exact texts:
  - rule-2 (:423): `rule-2 RESEARCHER_RANKING: scope RESEARCHER not allowed; allowed: scope CORPUS`
  - rule-7 (:464): `rule-7 window: NAMED with a year; allowed: DEFAULT (name, yearFrom, yearTo null), NAMED (name one of all-years, 2000-2014, 2015-2026; yearFrom, yearTo null), or RANGE (name null; yearFrom and yearTo years, at most one null; yearFrom <= yearTo)`
    - The fault part is one or more of: "a name", "a year", "no stored window name", "no year", "yearFrom after yearTo".
    - A window name that is not stored is never repeated back.
  - rule-4 metric (:437): `…; allowed: metric null or one of <list>`
  - rule-8 (:468): `rule-8 comparison: not a stored window pair; allowed: null or fromWindow -> toWindow one of 2000-2014 -> 2015-2026`
  - rule-1, rule-2 (count, duplicate, none), rule-3, rule-5 and rule-6 each get an "allowed:" part too.
- **Unchanged:** `rejectedItems` and the INVALID_PLAN abstention still keep only the part before the first ':' ("rule-7 window"), so audit item names do not change.
- **LlmCall.failure:** carries the full strings, cut at the existing 600-character limit.
- **Retry prompt:** carries them in full. Workflow.scala already passes the failure strings as `{{failed_rules}}`, so no Workflow change was needed.
- **Prompt values:** `Planner.promptValues` (:510-513) adds `window_names` (the exact quoted names) and `operation_rules` (one `Allowlist.ruleText` line per operation). planner-v1 has neither placeholder, so its rendering is unchanged.

prompts/planner/planner-v2.md (new; output-schema planner-output-v1):
- Compared with v1, it changes only rules 2, 7 and 8 and the retry heading.
- Rule 2 lists each operation's scopes and metrics from `{{operation_rules}}`, built from the same tables the validator checks. It also says that a researcher's own measure goes in `metrics`, and that RESEARCHER_RANKING's only scope is CORPUS.
- Rule 7 gives the three exact window objects, with the other fields null and the names from `{{window_names}}`.
- Rule 8 gives the comparison object.
- The schema text does not change, so there is no planner-output-v2.
- The allowed operations and all safety rules are unchanged, and nothing is tuned for answer quality.

modules/rag/RUNTIME.md:
- the status line;
- section 3: rule 7 stated exactly; new paragraphs "Planner failure messages (ruling 30)" and "planner-v2 (ruling 30)", which give the diagnosis;
- section 16: prompt list;
- section 20: the planner prompt-version row;
- section 21: a new known-failure entry.

Test fixtures now load planner-v2: RuntimeFixtures.scala:397, PlannerSpec.scala:47 and LlmSpec.scala:20. WorkflowSpec.scala:553 now expects "planner-v2". The PlannerSpec exact rule-5 assertion (:170) was updated to the new message.

3. NEW TESTS (PlannerSpec.scala)
- **"ruling 30: the recorded rule-2 and rule-7 failures name what is allowed; the corrections validate" (:484).** Realistic fenced, pretty-printed outputs:
  - the B01 shape (RESEARCHER_RANKING/RESEARCHER) fails with exactly the rule-2 text;
  - the B02 shape (that operation plus NAMED "2015-2026" with its years) fails with exactly [rule-2, rule-7 "NAMED with a year; allowed: …"];
  - NAMED "2010-2020" (not stored) gives "NAMED with no stored window name", without echoing 2010-2020;
  - RANGE with a name gives "a name", and reversed years give "yearFrom after yearTo";
  - the corrected outputs (no operation with the metric; NAMED with null years; RANGE with a null name) all validate.
- **"ruling 30: the retry prompt and the audit carry the allowed values, never the model's text" (:525).** Through the workflow with RecordedLlm:
  - the retry prompt contains both full lines;
  - LlmCall.failure starts with both messages and ends with the fenced shape;
  - the corrected second attempt gives plan source MODEL and window 2015-2026;
  - two not-stored attempts give FALLBACK_RULES with rejectedItems == ["rule-7 window"];
  - neither the retry prompt nor the audit contains 2010-2020.
- **"ruling 30: planner-v2 states each operation's scopes and metrics from the allowlist" (:557).**
  - The rendered system prompt contains every `Allowlist.ruleText` line and the exact quoted window names, with no `{{` left.
  - `metricText` and `scopeText` agree with `metricAllowed` and `scopes` for every operation, metric and scope.
  - planner-v1 still loads.
- **"ruling 30: every planner rule failure names what is allowed" (:586).** Inputs that trigger rules 1 to 8 each produce a message with "; allowed: ", and no model text appears.

Disable checks (each change made alone with the Edit tool, PlannerSpec run, then restored):

| Change | Failing tests | Log |
|---|---|---|
| D1. rule-2 allowed scopes removed | the first, second and fourth ruling 30 tests (3 failed) | disable-d1-rule2-allowed.log |
| D2. rule-7 back to the bare "rule-7 window" | the same 3 | disable-d2-rule7-detail.log |
| D3. rule-4 allowed metrics removed | "every planner rule failure names what is allowed" (1 failed) | disable-d3-rule4-allowed.log |
| D4. `operation_rules` emptied | "planner-v2 states …" (1 failed) | disable-d4-prompt-operation-rules.log |
| D5. `window_names` emptied | "planner-v2 states …" (1 failed) | disable-d5-prompt-window-names.log |

- A grep for the DISABLE markers found none left.
- After restoring, rag passed 80/0 (rag-test-restored.log). Before the disable checks, the 4 affected specs passed 32/0 (rag-specs-1.log).

4. PLANNER-V2 WAS CREATED. Config change for domain-engineer (edit only modules/core/**):
- **Config:** in modules/core/src/main/resources/scholarchain.conf, line 146 (`runtime.planner.prompt-version`), change `prompt-version = "planner-v1"` to `prompt-version = "planner-v2"`.
- **Test that pins it:** modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSpec.scala:200, `PlannerConfig("planner-v1", 800, 6, 4, 200, 5)`, must become "planner-v2".
  - A read-only grep found no other pin in modules/core, services or storage.
  - The rag tests load the version explicitly, so they do not depend on this key.
- **Run IDs:** the runtime run ID includes the planner prompt version and its sha256, so the change gives new runtime and benchmark run IDs. Until it is made, a live rerun still uses planner-v1 and resumes run-d18aa34e… (B01 and B02 would be skipped), though it would get the new messages.

5. SBT CHECK (both with SPARK_LOCAL_IP=127.0.0.1)

| | Run 1 (check1.log) | Run 2 (check2.log) |
|---|---|---|
| Exit | 0 | 0 |
| Per-project tests, all passed, 0 failed | 175, 80, 87, 111, 43, 70 | 175, 80, 87, 111, 43, 70 |
| markTestRun wrote the marker | yes | yes |
| Total time | 326 s | 262 s |
| Completed | 8:57:28 PM | 9:02:02 PM |

- rag is the 80 (76 before, plus 4 new tests).
- Before the checks, `rag/scalafmtAll rag/Test/scalafmtAll` reformatted one file (scalafmt.log).
- Afterwards, `find -newer .claude/.last-test-run` over modules/rag/src, RUNTIME.md and prompts listed no file.

6. FREE DISK: 16 GiB at the start, 14 GiB at the end.

FILES CREATED: prompts/planner/planner-v2.md
FILES MODIFIED:
- modules/rag/src/main/scala/scholarchain/rag/runtime/Planner.scala
- modules/rag/src/test/scala/scholarchain/rag/runtime/PlannerSpec.scala
- modules/rag/src/test/scala/scholarchain/rag/runtime/LlmSpec.scala
- modules/rag/src/test/scala/scholarchain/rag/runtime/RuntimeFixtures.scala
- modules/rag/src/test/scala/scholarchain/rag/runtime/WorkflowSpec.scala
- modules/rag/RUNTIME.md
FILES DELETED: none

ALLOWED PLAN OPERATIONS: unchanged (the 12 operations, the scopes table and the metric rules). The validator accepts and rejects exactly the same outputs as before.
PROMPT, SCHEMA, AND MODEL VERSIONS: planner-v2 is new (schema planner-output-v1, unchanged). planner-v1 stays loadable. synthesis-v2, synthesis-output-v2, verifier-v3 and Haiku us.anthropic.claude-haiku-4-5-20251001-v1:0 are unchanged.
IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR: unchanged. rejectedItems and the INVALID_PLAN text keep the same rule and field names.
VERIFICATION RULES: unchanged (verifier-v3).
BENCHMARK RESULTS: no rerun. Ruling 30 does not require one, and fake mode uses the rule planner, which this change does not touch. Injection tests (InjectionSpec) pass with planner-v2 inside the 80.
KNOWN FAILURE MODES (RUNTIME.md section 21):
- planner-v2 is tested only on fixtures that copy the recorded reply shape, and the rule-7 fixture's field values are reconstructed.
- An audit failure reason over 600 characters is cut; the retry prompt is not.
- Whether Haiku follows planner-v2 is shown only by a live run.

COMMANDS RUN:
- df, wc, sed, grep, find and ls (reads only, with no forbidden path).
- python3 -I to print the plan and llmCalls fields of the two audit objects (read only).
- mkdir scratch/repair-02-logs/ruling-30.
- sbt `rag/testOnly` of 4 specs once, `rag/testOnly PlannerSpec` five times (D1 to D5), `rag/scalafmtAll rag/Test/scalafmtAll`, `rag/test`, and `sbt check` twice (both as background jobs, read after they finished). All runs used SPARK_LOCAL_IP=127.0.0.1.
- No git, no aws, no network, and no live command.
- Nothing under labels/, scratch/stores-live/, scratch/runtime-presmoke/ or scratch/er/adjudication-live/ was read or listed.
- No existing scratch/runtime, scratch/stores, scratch/spark or scratch/hadoop output was modified.
- No background shell is left running.

TEST RESULTS: section 5. rag: 80 passed, 0 failed.
VALIDATION RESULTS: all 5 disable checks failed as expected and were restored.
ASSUMPTIONS:
- The rule-7 reply was NAMED with its years filled in. The other plausible shapes (RANGE with a name, a name that is not stored) are covered too.
- Enum values (for example "scope RESEARCHER") are not model free text, as in the quoted live message.
RISKS: the live effect of planner-v2 is unverified until the human's live run, and the config default stays planner-v1 until domain-engineer acts.
MANUAL ACTION REQUIRED:
- domain-engineer makes the config change in item 4.
- After review 3, the human reruns the live steps. The commands are unchanged from the repair-02 report; the run IDs are new once planner-v2 is the default.
BLOCKED-ON OWNER: none (the config default is a planned domain-engineer step, not a block)
BLOCKED-ON EVIDENCE: none
NEXT GATE: retrieval and groundedness evaluation, code-reviewer APPROVE (review 3: rulings 29 and 30)
````

## Repair 2, ruling 30: domain-engineer

````text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 09, Repair 2, ruling 30, domain-engineer part. Change the default `runtime.planner.prompt-version` to "planner-v2" (the ruling 28 pattern), update every test that pins it, and edit only modules/core/**.

Summary: the default planner prompt is now "planner-v2". The one core test that pins it was updated, and it fails with the old value (observed). config-version stays "config-v1". Both `sbt check` runs passed.

FILES CREATED: none

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/resources/scholarchain.conf, line 146:
  - before: `prompt-version = "planner-v1"`
  - after: `prompt-version = "planner-v2"  # Phase 9 ruling 30; output schema planner-output-v1 unchanged`
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSpec.scala, line 200:
  - before: `PlannerConfig("planner-v1", 800, 6, 4, 200, 5)`
  - after: `PlannerConfig("planner-v2", 800, 6, 4, 200, 5)`

FILES DELETED: none

PIN SEARCH IN modules/core:
- Command: `grep -rn -e "planner-v" -e "PlannerConfig(" -e "prompt-version" -e "promptVersion" modules/core/src`.
- The only pins are conf:146 and ConfigLoaderSpec:200. Both match the rag-engineer report.
- ConfigLoaderSpec.scala:233 (`"planner v1"`, with a space) is a test that a malformed value is rejected. It does not pin the default, so I left it alone.
- examples-v1.json:318 (`"promptVersion": null`) is a wire example field, not this key.
- There is no planner output schema-version key in core. prompts/planner/planner-v2.md:2 declares `output-schema: planner-output-v1` itself, so I added no key.

CONFIG-VERSION DECISION: unchanged, "config-v1" (scholarchain.conf line 16). Under the work order 2 rule, config-version changes only when a key is renamed or removed or its meaning changes. Changing a default value does not trigger it. Here:
- The key keeps its name, type and meaning.
- Override files that set "planner-v1" still validate, and rag still loads planner-v1 (PlannerSpec.scala:583).
- A bump would change the Phase 7 render and embed run IDs for no contract reason.
- The rag-runtime run ID already changes, because Workflow.scala:91 hashes `prompts.versions` into `runIdFor`.

SCHEMA VERSIONS: no record schema changed. No wire shape changed, and the frozen examples were not touched.
STABLE ID FORMULAS: unchanged.
PAIR CANONICALIZATION RULE: unchanged. A is the smaller ID by String.compareTo, and a self-pair is not a pair.
CONFIGURATION PRECEDENCE: unchanged, highest first:
1. the documented environment variables in ConfigLoader.EnvOverrides;
2. the file named by SCHOLARCHAIN_CONFIG_FILE;
3. the reference scholarchain.conf.
No environment variable exists for this key, and none was added.
SAMPLE SERIALIZED RECORDS: none changed.

DOWNSTREAM REFERENCES (read-only grep; nothing edited):
- Search: `grep -rn -e planner-v1 -e planner-v2 -e "planner\.prompt-version" -e "planner\.promptVersion"` over the repository, excluding target, .git, labels, scratch and docs. A separate grep of docs/, excluding agent-runs, found nothing.
- modules/services, modules/storage, tests/, scripts/ and config/ have no references. config/ does not exist.
- rag-engineer, code that reads the key (works as is):
  - modules/rag/src/main/scala/scholarchain/rag/cli/AskCli.scala:135 reads `cfg.runtime.planner.promptVersion`.
  - BenchmarkCli goes through AskCli.open, so both CLIs now load prompts/planner/planner-v2.md by default.
  - Workflow.scala:91 puts the prompt versions into the run ID, so default-config runs get new rag-runtime and benchmark run IDs and will not resume run-d18aa34e….
- rag-engineer, stale doc text:
  - modules/rag/RUNTIME.md:418 (section 20 row) still reads "`planner-v1`; repair-02 asks domain-engineer to change it to `planner-v2` (ruling 30)". It should now say `planner-v2`.
  - RUNTIME.md:5, :108 and :358 are still accurate.
- rag-engineer, tests (explicit versions, not the config default; they pass as they are):
  - PlannerSpec.scala:47, :560 and :583;
  - LlmSpec.scala:20;
  - RuntimeFixtures.scala:397;
  - WorkflowSpec.scala:553;
  - RuntimeRecordsSpec.scala:67, :499 and :505 (planner-v1 audit fixtures).
- Prompt files (both kept): prompts/planner/planner-v1.md:1 and prompts/planner/planner-v2.md:1-2.
- Human-owned records under docs/agent-runs/ were not listed in detail. They are read-only history.

AFFECTED CONSUMERS:
- rag-engineer: the RUNTIME.md:418 row (doc only).
- Human: the live benchmark commands are unchanged, but default-config runs now use planner-v2 and get new run IDs.

COMMANDS RUN (observed):
- Reads only, with no forbidden path: sed, grep, head, ls, find, df.
- mkdir -p scratch/repair-02-logs/ruling-30-domain.
- Edit tool: 2 edits (the spec first, then the conf).
- `SPARK_LOCAL_IP=127.0.0.1 sbt -batch "core/testOnly scholarchain.core.config.ConfigLoaderSpec"`, with only the spec change.
- `SPARK_LOCAL_IP=127.0.0.1 sbt check` twice, each run in the background with output redirected to a log.
- How I waited:
  - Run 1: a foreground poll loop on the log was moved to the background after the 180 s limit, then a second bounded poll loop read the exit line.
  - Run 2: two bounded foreground poll loops.
  - All of these background tasks finished; no shell is left running.
- No git, no aws, no network. I did not create or touch .claude/.last-test-run; only markTestRun wrote it.
- Nothing under labels/, scratch/stores-live/, scratch/runtime-presmoke/, scratch/er/adjudication-live/, scratch/runtime/, scratch/stores/, scratch/spark/ or scratch/hadoop/ was read, listed or modified.

TEST RESULTS:
- Without the change (spec changed, conf still planner-v1):
  - exit 1, "Tests: succeeded 22, failed 1";
  - the failure: `PlannerConfig("planner-v1", 800, 6, 4, 200, 5) did not equal PlannerConfig("planner-v2", 800, 6, 4, 200, 5) (ConfigLoaderSpec.scala:200)`;
  - log: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/repair-02-logs/ruling-30-domain/without-conf-change.log
- `sbt check` run 1:
  - exit 0, "[success] Total time: 261 s", completed 9:08:59 PM, markTestRun wrote the marker;
  - log: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/repair-02-logs/ruling-30-domain/check1.log
- `sbt check` run 2:
  - exit 0, "[success] Total time: 260 s", completed 9:13:32 PM, markTestRun wrote the marker;
  - log: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/repair-02-logs/ruling-30-domain/check2.log
- Per-project tests, the same in both runs, with 0 failed, canceled or ignored and no "FAILED" lines:

| Project | Tests passed | Suites |
|---|---|---|
| core | 175 | 4 |
| rag | 80 | 12 |
| storage | 87 | 8 |
| entityResolution | 111 | 10 |
| sparkAnalytics | 43 | 7 |
| hadoop | 70 | 6 |

  - The log summaries do not name projects. I matched them by suite count, as in repair 1D.
- After run 2, `find modules/core prompts modules/rag/src -newer .claude/.last-test-run -type f` listed only three sbt stream files under modules/core/target and no source file.

VALIDATION RESULTS: the existing `IdFormat.Version` check accepts "planner-v2", and the config loads in every project's tests. No validation rule changed.

FREE DISK: 14 GiB available at the start and 15 GiB at the end (`df -h .`, /dev/disk3s5).

ASSUMPTIONS:
- The work order 2 config-version rule (bump only on a rename, a removal or a change of meaning) applies as it did in ruling 28 / part D.
- "Run only with the spec change" was used as the without-change demonstration, so no temporary revert was needed.

RISKS:
- Whether live Haiku follows planner-v2 is unverified until the human's live run.
- RUNTIME.md:418 now disagrees with the default until rag-engineer edits it.

MANUAL ACTION REQUIRED: the orchestrator routes the RUNTIME.md:418 text fix to rag-engineer. Review 3 covers this part.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, and code-reviewer APPROVE (review 3, rulings 29 and 30)

Not checked: I did not run the benchmark or check how BenchmarkCli resume behaves with the new run ID.
````
