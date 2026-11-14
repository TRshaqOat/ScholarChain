# Phase 11 repair-02 (human exception per ruling 20)

## Part A: rag-engineer

````text
## Phase 11 repair-02 Part A (rag-engineer, D14): report

D14 is fixed and the verifier goes from verifier-v7 to verifier-v8. Both `sbt check` runs ended exactly as ruling 14 allows: exit 1, 832 tests, and the only failure is the D14 tests/** wrapper at UnsupportedClaimAdversarialSpec.scala:197 ("did not throw an exception"), because the defect is now fixed. markTestRun did not write the marker, which is expected.

The fake benchmark rerun matches run-6acd1b5b8b39225000e9599b827f369d on all 30 questions; no claim changed. All five disable checks failed named module tests, and every restore matched its sha256.

```text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 11 repair-02 Part A (D14), rulings 7, 11, 14, 20, 22
```

### 1. The D14 fix (`Verifier.scala`, `hedge` and its regexes)

**Comparison words before a number (`Before`).** Each new word fails `numbers-in-text` when it comes directly before the number. New in v8:
- "short of", "shy of"
- "upward of" ("upwards of" was already there), "north of", "south of"
- "far from", "near", "nigh", "approaching", "nearing"
- "exceed" and "exceeded" ("exceeds" and "exceeding" were already there)
- "surpass", "surpasses", "surpassed", "surpassing"
- "outnumber" in all its forms
- "tops", "topped", "topping" (plain "top" is left out because "top 10" is a list size)
- "beneath"

These were already in v7 and are retested: "below", "above", "beyond", "in excess of", "upwards of", "exceeds", "exceeding".

**Words in between (`Filler`).** The comparison word may now be followed by "the", "a", "an", "a total of" or "a count of" before the number, so "exceeds a total of 10" and "has over the 10" fail.

**Words after a number (`After`).** New: "-ish" or "ish", "and counting", "at (a) minimum", "at (a) maximum".

**Negation words, anywhere before the number in its clause (`Negation`).** New in v8:
- "lack", "lacks", "lacked", "lacking"
- "without", "absent", "absence", "devoid"
- "fail", "fails", "failed" or "failing" + "to"
- "hardly", "scarcely", "barely", "seldom", "rarely"
- "deny", "denies", "denied"
- "except", "excluding"

**How the exceptions and the template wordings are kept:**
- **Bound direction.** The `MinKeys`/`MaxKeys` check with `AtLeast`/`OrMore`/`AtMost`/`OrFewer` is unchanged and still runs first. None of the new words is a bound phrase, so "short of 4 hops" with a maximum still fails.
- **Ratio.** The `Ratio` regex still covers only over, under, above and below after a number or a count noun. It now takes the same filler words, so "1 distinct coauthor over the 10 publications" still verifies.
- **"not converge".** The `(?!\s+converge)` lookahead now applies to every negation word, so "failed to converge within its 10 rounds" verifies. A CorpusSpec test checks this.
- **Template wordings.** Only one new word appears in template text before a number: "without", in the singleton claim "N singleton researchers (researchers without any coauthor edge in that window) are among the M researchers". To keep that claim verified, a new rule (`asidesOut`) skips a closed parenthetical before the number when it holds no digit and names a measure (`Families`), which mirrors how the measure-name rule already skips such remarks. A parenthetical like "(not)" holds no measure, so "has (not) 10 publications" still fails, and a test checks that. The real benchmark data shows the singleton claim VERIFIED in all three windows.

**Wordings I knowingly left uncovered** (also listed in RUNTIME.md section 21):
- "past 10": "over the past 10 years" is a time phrase.
- "is missing 10": ambiguous with the missing-evidence wording.
- "a minimum of 10" and "a maximum of 10" before the number: these would need bound-direction handling.
- "within 10", "toward 10", "pushing 10", "the better part of 10".
- "besides", "apart from", "other than", "none of", "nobody", "nothing".
- A comparison word separated from the number by words other than the filler list ("exceeds the stored total of 10").
- An after-number word written after the measure ("10 publications and counting").
- "a mere 10" states the value exactly and verifies by design.

### 2. Module tests and disable checks

New tests:
- `modules/rag/src/test/scala/scholarchain/rag/runtime/VerifierSpec.scala`
  - "repair-02 D14: 'short of 10', 'falls short of 10', and 'lacks 10' are not verified" (the three work order 4 inputs, unchanged)
  - "repair-02 D14: the comparison families are not verified" (25 wordings)
  - "repair-02 D14: the negation families (lack, without, absent, fail to) are not verified" (11 wordings, including "(not) 10")
  - "repair-02 controls: exact statements, ratios, and an aside defining a measure still verify"
- `modules/rag/src/test/scala/scholarchain/rag/runtime/CorpusSpec.scala`
  - "repair-02 D14 controls: the template's 'without' aside and 'failed to converge' verify". It also checks that "without" outside the parenthetical, and "lacked its 10 rounds", are removed.

Disable checks: patch, run `rag/test`, restore, then `shasum -c`. The sha256 before every patch was 59876ae6… and every restore was OK. Logs are `scratch/phase-11-logs/repair-02/disable-<label>.log` (with a diff of the patch) and `.sbt.log`.

| Label | Patch | rag/test passed/failed | Failing tests |
|---|---|---|---|
| D14-all | Before, After, Negation and Filler set back to their v7 forms | 112/4 | all 3 D14 tests and the CorpusSpec controls |
| D14-comparison | Before and After set back to v7 | 114/2 | "short of 10…" and "comparison families" |
| D14-negation | new negation words removed | 113/3 | "short of 10…" ("lacks"), "negation families", CorpusSpec controls |
| D14-filler | Filler = `\W{0,3}$` | 115/1 | "comparison families" |
| ctl-aside (control) | parenthetical skip removed | 112/4 | CorpusSpec "B13 and B15 shapes", "B24 shape", the CorpusSpec repair-02 controls, and the VerifierSpec repair-02 controls (the template's "without" would be negated) |

After all five runs, the manifest check over the 43 rag Scala files was 43 of 43 OK (`rag-sha256-after-disable-check.txt`).

### 3. Version and RUNTIME.md
- Verifier version: `verifier-v7` before, `verifier-v8` after (`Verifier.Version` and its scaladoc).
- RUNTIME.md changes:
  - **Status line:** a repair-02 sentence.
  - **Section 12:** the version line, with a v8 entry in its history; the `numbers-in-text` row; the "Quantity words, qualifiers, and negations" heading, its qualifier bullet (v8 words, the filler, and the ratio filler), its negation bullet (v8 words, "failed to converge", and the parenthetical rule); and the not-covered sentence.
  - **Section 14:** the run-ID input `verifier=verifier-v8`.
  - **Section 21:** the qualitative-misreading bullet, plus a new verifier-v8 bullet covering the recall cost, the parenthetical rule's limit, and the uncovered wordings.
  - **Section 22:** a new results row and a repair-02 paragraph.
- Historical mentions of v7 were left as they are.

### 4. Fake benchmark (no live calls)

| | run-6acd1b5b8b39225000e9599b827f369d (v7) | run-472a394363c6a23e79d4d33c2fa58416 (v8) |
|---|---|---|
| Runtime run | run-bcc6454a98b785018d0df03546bf06bc | run-3e844909c13a9555428215a182e3d6b6 |
| Answered / abstained | 27 / B09 AMBIGUOUS, B12 UNKNOWN, B28 UNSUPPORTED | the same |
| Verified / partial / unsupported / conflicting | 91 / 28 / 0 / 0 (119 claims) | 91 / 28 / 0 / 0 (119 claims) |
| Checks passing | 30 of 30 | 30 of 30 |
| LLM calls | 0 | 0 |
| Latency p50 / p95 / max | 22 / 365 / 714 ms | 27 / 401 / 820 ms |

- **Changed claims: none.** `compare-vs-run-6acd1b5b.txt` reports "questions with any difference: 0", with no "only old" or "only new" lines. It also reports "removed claims 0, left-out lines 0" across all 30 audits of both runs.
- **Why nothing changed:** the template uses none of the new comparison words, and its only "without" is inside the singleton parenthetical that the new rule skips.
- The run used the RUNTIME.md section 22 command with both window-summary flags and `--out scratch/runtime/phase-11/repair-02` (2.2M). Exit 0, 07:05:15Z to 07:06:16Z.

### 5. sbt check (SPARK_LOCAL_IP=127.0.0.1, -J-Xmx6g)

| | Check 1 | Check 2 |
|---|---|---|
| Time | 07:08:10Z to 07:12:34Z | 07:12:43Z to 07:17:09Z |
| Exit / sbt time | 1 / 258 s | 1 / 262 s |

Console and JUnit counts were identical in both runs:
- **Per project:** core 190, entity-resolution 111, storage 109, rag 116 (was 111), services 47, spark 46, hadoop 70, all with 0 failed and 0 pending. tests: 135 succeeded, 1 failed, 7 pending.
- **JUnit:** 82 suites, 832 tests, 1 failure, 0 errors, 7 skipped. The WO4 stale `Wo4ProbeSpec.xml` was not counted.
- **The only failing test:** `scholarchain.tests.adversarial.UnsupportedClaimAdversarialSpec` :: "a comparison or negation outside the verifier-v7 word lists (short of 10, lacks 10) is not verified". It fails with "pendingUntilFixed did not throw an exception (UnsupportedClaimAdversarialSpec.scala:197)", which is the D14 wrapper.
- **The 7 pending:** D6, D7, D8, D9, D15, D16, D17.
- **markTestRun:** did not write the marker. There is no markTestRun line in either log, because `tests/test` failed first.

### 6. End state
- Free disk at the end: 13 GiB (`df-end.txt`).
- No sbt, java or background process is running (`pgrep` found none). Both background check tasks have completed.

```text
FILES CREATED: none in modules/.
- Logs in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-11-logs/repair-02/:
  - df-start, df-before-benchmark, df-before-check-1/2, df-end
  - rag-testonly-1.log, rag-test-1.log, unsupported-claim-adversarial.log
  - rag-sha256-before-disable.txt, rag-sha256-after-disable-check.txt, rag-sha256-end.txt
  - disable-{D14-all,D14-comparison,D14-negation,D14-filler,ctl-aside}.log and .sbt.log
  - fake-benchmark.log/.exit, benchmark.start/.end, compare-vs-run-6acd1b5b.txt
  - sbt-check-1/2.{log,start,end,exit}, junit-counts-1/2.txt
- Benchmark output: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/runtime/phase-11/repair-02/
- Scratchpad: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c8242cd7-266e-4b64-b940-735f198f56c6/scratchpad/repair-02-partA/
  - patch.py, disable.sh, check.sh, compare.py, junit_counts.py, Verifier.scala.orig, Verifier.sha256
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala (sha256 59876ae6…)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/VerifierSpec.scala (+4 tests)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/CorpusSpec.scala (+1 test)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md
FILES DELETED: none
ALLOWED PLAN OPERATIONS: unchanged
PROMPT, SCHEMA, AND MODEL VERSIONS:
- verifier-v7 -> verifier-v8.
- All others unchanged: planner-v2, planner-output-v1, synthesis-v3, synthesis-output-v3, rag-runtime-v3, rag-audit-v3, the Haiku model ID.
- No prompts/** change.
IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR: unchanged (B09 AMBIGUOUS, B12 UNKNOWN, B28 UNSUPPORTED).
VERIFICATION RULES: RUNTIME.md section 12, "Quantity words, qualifiers, and negations (verifier-v7 …; verifier-v8 …)".
BENCHMARK RESULTS:
- Fake (graph-plus-fake-embedder) run-472a3943…: 91/28/0/0, checks 30/30, identical to run-6acd1b5b.
- Groundedness: 0 unsupported, 0 conflicting.
- InjectionSpec passes within rag 116.
- No vector-only or live run (ruling 2).
KNOWN FAILURE MODES: RUNTIME.md section 21, including the new verifier-v8 bullet and the uncovered wordings in item 1.
COMMANDS RUN:
- sbt rag/scalafmtAll, rag/Test/scalafmtAll, rag/testOnly VerifierSpec CorpusSpec: 42/0, first run.
- sbt rag/scalafmtCheckAll, rag/Test/scalafmtCheckAll, rag/test: 116/0.
- sbt "tests/testOnly …UnsupportedClaimAdversarialSpec": 7/1/0; the 1 is the D14 wrapper "did not throw".
- disable.sh five times.
- sbt rag/runMain BenchmarkCli (fake): exit 0.
- python3 -I compare.py and junit_counts.py.
- sbt -batch check, twice.
- Read-only: shasum, df, pgrep, grep, sed, ls, du (on scratch/runtime/phase-11/repair-02 only).
- Not run: git, aws, Bedrock, network, or find/du over scratch/. Nothing was read or listed under labels/, scratch/stores-live/ or scratch/er/adjudication-live/.
TEST RESULTS: see item 5.
VALIDATION RESULTS:
- The format checks passed (in rag/test's precheck, and in both checks, which reached the test stage).
- Every disable check failed a named test, and every restore was OK. The manifest check was 43/43 OK.
- The end sha256 of Verifier.scala equals the pre-disable sha256.
ASSUMPTIONS:
1. Most conservative reading: the extra words beyond the named ones (hardly, barely, except, excluding, near, and the filler) are added, and they remove some true claims. This is disclosed in section 21.
2. The parenthetical rule is the narrowest change that keeps the singleton template verified without editing template text. I chose not to change the template wording, so that no benchmark claim changes.
3. The disable checks ran `rag/test`, as in repair-01 Part B. Before I edited Verifier.scala there was no separate pre-change manifest, only the v7 text that the D14-all patch restores.
RISKS:
- The rules are still lexical. Wordings outside the lists still pass (item 1).
- The live recall cost of v8 is not measured.
- A negation inside a parenthetical that names a measure is not checked.
- Guard events: none.
MANUAL ACTION REQUIRED:
- Work order 5 (test-engineer): remove the D14 wrapper at UnsupportedClaimAdversarialSpec.scala:196-197. Until then markTestRun will not write the marker.
- Human: rerun the live benchmark with verifier-v8 when cloud window 2 allows.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order 5 wrapper removal, then retrieval and groundedness evaluation and code review 2 APPROVE.
```

Not checked: real model output against the v8 rules (no live calls). RUNTIME.md was edited before both checks, so no source is newer than them.
````
