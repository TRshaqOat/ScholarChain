# Phase 11 repair-01

## Part A: build-engineer

````text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 11, repair-01, Part A (ruling 12). This continues an attempt that lost auth before writing its report. The tests project gets Spark and Hadoop on its Test classpath, plus the fork and JVM options the Spark module tests use. No production scope, job jar, or version change.

Summary: the interrupted attempt's build.sbt change was already complete and correct, so I did not edit it and did not rerun the full check. check-A and check-B, both from the interrupted attempt in this part, are the two sbt check runs. I added only new read-only evidence logs.

FILES CREATED (evidence only, all under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-11-logs/repair-01/partA/):
- show.log, show.exit, show.start, show.end
- tests-test-fullClasspath-now.txt
- tests-classpath-grep.txt
- dual-suffix-check.txt
FILES MODIFIED: none in this session. /Users/achyutpatel/Developer/Cloud/ScholarChain/build.sbt was changed by the interrupted attempt (mtime 2026-11-13 03:40:11 local); I verified it and left it as is. project/** is unchanged: git status shows nothing under project/, and Dependencies.scala mtime is 2026-11-12 01:25:39.
FILES DELETED: none

BUILD.SBT CHANGE (verified with git diff):
- It extracts `sparkTestSettings` (Test/fork true, Test/parallelExecution false, Test/javaOptions = SparkJvmOptions plus -Xmx2g) and `excludeScalaXml3` out of sparkAnalytics. sparkAnalytics now uses both helpers, and its resolved values are the same as before (show output below).
- tests project:
  - Spark and Hadoop in Test scope: `libraryDependencies ++= (Dependencies.sparkSql +: Dependencies.hadoop).map(_.withConfigurations(Some(Test.name)))`. These use the same ledger entries, re-scoped from Provided to Test for this project only.
  - `excludeScalaXml3` and `sparkTestSettings`.
  - `Test / forkOptions := (Test / forkOptions).value.withWorkingDirectory((ThisBuild / baseDirectory).value)`.
- Production scope is unchanged: the show output gives `hadoop/libraryDependencies` hadoop-client-api and hadoop-client-runtime 3.4.2 `:provided`, and `sparkAnalytics/libraryDependencies` spark-sql 4.0.2 `:provided`.
- Job jars are unchanged. Assembly settings (hadoop shade rules, sparkAnalytics assemblyExcludedJars) and the packageJobs alias are not touched by the diff. The tests project has no assembly and `publish / skip := true`.
- No version changed.
- Hadoop module tests had no fork or JVM options before and still have none (`hadoop/Test/fork` = false, `hadoop/Test/javaOptions` empty). So "the options the hadoop tests use" means none extra. Spark's options are a superset and cover Hadoop suites in the same forked JVM.

WHY SBT-CHECK-1 FAILED AND HOW THE CHANGE FIXED IT:
- sbt-check-1.log: exit 1. tests/Test/test had about 37 failures, all `java.lang.RuntimeException: missing prompts/planner/planner-v2.md` at scholarchain.tests.support.StoreWorld$World.<init>(StoreWorld.scala:422). Examples: StoreFailureAdversarialSpec and ApiAuthAdversarialSpec.
- Cause: once `tests` forked, the child JVM's working directory defaulted to the project base `tests/`. The relative `prompts/` path no longer resolved. Unforked, the working directory had been the repository root.
- Fix: the `Test / forkOptions ... withWorkingDirectory(repo root)` line. fix-verify.log (exit 0) shows the tests project at succeeded 94, failed 0, pending 14. show.log confirms ForkOptions working directory Some(/Users/achyutpatel/Developer/Cloud/ScholarChain).
- sbt-check-2 (exit 143, killed after 10 s) and sbt-check-3 (no exit file, incomplete) were aborted runs of the interrupted attempt. They are not cited. No sbt or forked JVM process is still running (ps checked).

THE TWO SBT CHECK RUNS (both on the current build.sbt):
- build.sbt mtime is 03:40:11 local (09:40:11Z). check-A started 09:41:27Z, after it.
- check-A: 09:41:27Z to 09:45:51Z. Exit 0, wall 264 s (sbt total 259 s). markTestRun wrote the marker.
- check-B: 09:45:51Z to 09:50:14Z. Exit 0, wall 263 s.
- Both are about 4.4 minutes, under the 8-minute budget.
- The marker .claude/.last-test-run (03:50:14 local, written by check-B's markTestRun) is newer than every changed Scala or sbt source. The newest is build.sbt at 03:40:11.

SHOW OUTPUTS (show.log, exit 0):
- `show tests/Test/fork`: true
- `show tests/Test/javaOptions`:
  -XX:+IgnoreUnrecognizedVMOptions, --add-modules=jdk.incubator.vector, -Djdk.reflect.useDirectMethodHandle=false, -Dio.netty.tryReflectionSetAccessible=true, --add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED, --add-opens=java.base/{java.lang, java.lang.invoke, java.lang.reflect, java.io, java.net, java.nio, java.util, java.util.concurrent, java.util.concurrent.atomic, jdk.internal.ref, sun.nio.ch, sun.nio.cs, sun.security.action, sun.util.calendar}=ALL-UNNAMED, -Xmx2g
- `show sparkAnalytics/Test/fork`: true. `show sparkAnalytics/Test/javaOptions`: the same list as tests.
- `show tests/Test/parallelExecution`: false
- `show tests/Test/forkOptions`: workingDirectory Some(/Users/achyutpatel/Developer/Cloud/ScholarChain)
- `show tests/libraryDependencies`: scala3-library 3.3.7, scalatest 3.2.20:test, spark-sql 4.0.2:test, hadoop-client-api 3.4.2:test, hadoop-client-runtime 3.4.2:test

CLASSPATH EVIDENCE (`export tests/Test/fullClasspath`, 245 entries, in tests-test-fullClasspath-now.txt):
- spark-sql_2.13-4.0.2.jar, spark-core_2.13-4.0.2.jar, spark-catalyst_2.13-4.0.2.jar, spark-sql-api_2.13-4.0.2.jar
- hadoop-client-api-3.4.2.jar, hadoop-client-runtime-3.4.2.jar
- scala-library-2.13.16.jar, scala3-library_3-3.3.7.jar, scala-xml_2.13-2.3.0.jar (no scala-xml_3)
- Dual-suffix check (one library with both _2.13 and _3): 0 libraries (dual-suffix-check.txt).

MODULE GRAPH: unchanged. The tests project still dependsOn core, entityResolution, hadoop, sparkAnalytics, storage, rag, and services, all `% Test`, and nothing depends on tests. No production edge was added or removed, which matches the CLAUDE.md table.
VERSION LEDGER: /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala, unchanged.
- scala 3.3.7, scala-library 2.13.16, Java release 17, spark 4.0.2, hadoop 3.4.2, upickle 4.4.3, scalatest 3.2.20, logback 1.5.38, typesafe config 1.4.9, awsSdk 2.41.32
- javaRuntimeImage eclipse-temurin:17.0.20_8-jre-noble@sha256:39d618b8...5f45
SCOPES AND PACKAGING:
- Spark and Hadoop stay Provided in sparkAnalytics and hadoop. They are Test-only in the non-published tests project.
- Logback stays Runtime. Job jars and the image are unchanged.
ENVIRONMENT INPUTS: unchanged (SCHOLARCHAIN_IMAGE_TAG, SCHOLARCHAIN_SPARK_HEAP). SPARK_LOCAL_IP is set by the caller and is not read by the build.
COMPATIBILITY RISKS: none new. The tests classpath uses the same Spark 2.13 build, Scala 2.13.16 stdlib pin, and scala-xml_3 exclusion as sparkAnalytics. The cross-version conflictWarning (failOnConflict) still applies.

COMMANDS RUN (this session):
- git diff build.sbt; git status --porcelain (read-only)
- inspection of the partA logs; stat of mtimes; df
- `SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx3g -batch "show tests/Test/fork" "show tests/Test/javaOptions" "show tests/Test/parallelExecution" "show tests/Test/forkOptions" "show sparkAnalytics/Test/fork" "show sparkAnalytics/Test/javaOptions" "show hadoop/Test/fork" "show hadoop/Test/javaOptions" "show tests/libraryDependencies" "show hadoop/libraryDependencies" "show sparkAnalytics/libraryDependencies" "export tests/Test/fullClasspath"`: exit 0
- grep of the exported classpath; ps check for lingering sbt JVMs (none)

TEST RESULTS (from check-A and check-B, identical; junit-counts-A.txt and junit-counts-B.txt): 77 suites, 777 tests, 0 failures, 0 errors, 14 skipped.

| Project | Tests |
|---|---|
| core | 185 |
| entity-resolution | 111 |
| hadoop | 70 |
| rag | 106 |
| services | 43 |
| spark | 46 |
| storage | 108 |
| tests | 108, of which 94 succeeded and 14 are pending |

The 14 pending are the WO3 pendingUntilFixed D-item wrappers. Part A fixes no D item, so ruling 14 adds no accepted failures here.

VALIDATION RESULTS:
- scalafmtSbtCheck, scalafmtCheckAll, and Test/compile passed inside check-A and check-B.
- The show and export commands passed.
- Disk: 14 GiB free.

ASSUMPTIONS:
- "The fork and JVM options the hadoop module tests use" is the empty set, because the hadoop project defines none.
- The interrupted attempt's check-A and check-B logs are genuine sbt output. Their content (sbt headers, per-suite lines, markTestRun line, timestamps) is consistent with that.

RISKS:
- The whole tests project now runs in one forked JVM, serially, with -Xmx2g. Suites that relied on in-process parallelism now run one after another. tests/Test took about 26 s in fix-verify, so the budget impact is small.
- -Xmx2g is shared by every tests suite plus any SparkSession. Large Spark fixtures in tests/ could hit OOM; keep fixtures small.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NOTE FOR TEST-ENGINEER (work order 4, Spark and Hadoop suites under tests/):
- Shared SparkSession: all tests suites run in one forked JVM. Use a single lazily created local SparkSession per JVM, for example an object with `SparkSession.builder().master("local[2]").config("spark.ui.enabled","false").config("spark.sql.shuffle.partitions","2")` and getOrCreate. Do not stop it in afterAll; stopping it breaks later suites. If you need a shared helper, put it under tests/adversarial/.../support like StoreWorld. Before writing your own, check whether modules/spark/src/test already has one you can follow; you cannot import it, because module test classes are not on the tests classpath.
- Serial execution: Test/parallelExecution is false, so suites run one at a time. Do not add ParallelTestExecution.
- Working directory: the repository root (forkOptions), so `prompts/` and other relative paths resolve as before. Write every output to JVM temporary directories (`Files.createTempDirectory`), never to the working directory.
- Heap: -Xmx2g for the whole tests JVM, so keep known-graph fixtures tiny.
- SPARK_LOCAL_IP=127.0.0.1 is still required on the sbt command line.
- Hadoop: hadoop-client-api and hadoop-client-runtime are on the classpath. Test record readers through local `file:` paths with a `Configuration`, `FileSplit`, and `TaskAttemptContextImpl`. No mini-cluster or network is needed.
- Spark and Hadoop are Test-scoped in tests only. Production modules are unchanged. Do not try to add libraries yourself; that is a build-engineer change.

NEXT GATE: sbt check and code-reviewer APPROVE

Not checked: I did not rerun the full sbt check (as instructed), and I relied on check-A and check-B from the interrupted attempt. Risk: those runs predate any later repair-01 part, so each later part's own sbt check governs the marker after this.
````

## Part B: rag-engineer

````text
## Phase 11 repair-01 Part B (rag-engineer): report

D1 to D4 and finding 5 are fixed, and the verifier goes from verifier-v6 to verifier-v7. Both `sbt check` runs end exactly as ruling 14 expects: 782 tests, and the only 4 failures are the tests/** pendingUntilFixed wrappers for D1 to D4. Each fails with "did not throw an exception" because its defect is now fixed. Because of those 4 failures, markTestRun did not write the marker, as ruling 14 says.

The fake benchmark rerun is identical to run-7730f2f6 on all 30 questions; no claim changed. Every fix has a disable check that fails a named module test, and every restore matched its sha256.

```text
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 11 repair-01 Part B (D1, D2, D3, D4; code review 1 finding 5), rulings 7, 14, 15, 16
```

### Changes
- **D1 to D3** (`Verifier.scala`, verifier-v7). Three new parts of the `numbers-in-text` rule. Each one fails the text, so the claim is removed.
  - **Quantity words** fail when their clause also holds a measure name: "hundreds", "dozens", "a dozen", "scores of", "thousands", "several", "many", "multiple", "a number of", "half of", and similar.
  - **Qualifiers** directly before or after a number fail: "more/fewer/less than", "over", "under", "at least", "at most", "nearly", "about", "approximately", "or more", "+", "or so", and similar.
  - **Negations** before the number in its clause fail: "not", "n't", "never", "false", "neither", "nor", "no longer", and similar.
  - Fixed-note numbers and year facts are exempt.
  - **Exception 1, a bound fact's own direction.** "at least" or "or more" verifies with a minimum (`communityMinSize`, `minWorks`). "at most", "up to" or "or fewer" verifies with a maximum (`maxDepth`).
  - **Exception 2, ratios.** "over" or "under" after a number or a count noun is a ratio, as in the template's "is 25 over 10 publications".
  - **Exception 3, "not converge".** "did not converge" states the stored `communityConverged`.
  - A negation after the number ("which is not the largest") or in another clause ("Breadth is not intensity:") does not apply to it.
- **D4** (`Semantic.scala`). A vector query that throws or returns a Left now degrades only that index. The index gets a `NO_SEMANTIC_MATCH` note: "The <index> query failed; no semantic context from it." The note never carries the error text.
  - The other indexes are still queried, and the question continues on its graph evidence.
  - `DeadlineExceeded` still propagates.
  - No enum, schema or storage change; per ruling 16 there is no read retry.
- **Finding 5** (`RUNTIME.md`).
  - The section 21 percentage sentence (old line 509) now states the verifier-v6 behaviour.
  - Other lines that would now contradict the code were updated: the status line, section 2 (a semantic-context read no longer fails the question), section 9 (a failures bullet), the section 12 version line, the `numbers-in-text` row, a new verifier-v7 paragraph, and the section 14 run-ID verifier input.
  - In section 21: the qualitative-misreading bullet, the number-words bullet ("hundred"), and a new bullet on the v7 recall cost.
  - Section 22 has the new run row and a paragraph.

### Disable checks
Each fix was patched out, then `rag/test` was run and the source restored. Logs are `scratch/phase-11-logs/repair-01/partB/disable-<label>.log` and `.sbt.log`.

| Fix | Patch | Failing test (rag/test) | sha256 restored |
|---|---|---|---|
| D1 quantity words | drop the `vague` problems | 110/1: VerifierSpec "repair-01 D1: a quantity in words …" | a4aebe1e… OK |
| D2 qualifiers | qualifier → `Option.empty` | 109/2: VerifierSpec "repair-01 D2 …" and "repair-01 controls …" | a4aebe1e… OK |
| D3 negations | negation → `Option.empty` | 110/1: VerifierSpec "repair-01 D3: a negated number …" | a4aebe1e… OK |
| Bound-direction exception (control) | `if false` | 107/4: CorpusSpec "B17 shape …", "a model claim on corpus evidence is still checked …", "Phase 11 work order 2, live B17 shape …"; VerifierSpec "repair-01 controls …" | a4aebe1e… OK |
| Ratio exception (control) | drop the Ratio filter | 108/3: VerifierSpec "F1 (a): a number must be bound …", "repair-01 D2 …", "repair-01 controls …" | a4aebe1e… OK |
| "not converge" exception (control) | drop the lookahead | 110/1: CorpusSpec "B17 shape …" | a4aebe1e… OK |
| D4, thrown error | catch only `DeadlineExceeded` | 110/1: WorkflowSpec "repair-01 D4 …", which reports the question FAILED at SEMANTIC_CONTEXT with the TransientStoreException | 2a94eb93… OK |
| D4, Left | rethrow only a store-returned Left (`disable-D4-left-2`) | 110/1: WorkflowSpec "repair-01 D4 …", which reports the question FAILED at SEMANTIC_CONTEXT with the IllegalStateException (refused shape) | 2a94eb93… OK |

- After all the disable runs, the main-source manifest check gave 15 of 15 OK.
- An earlier D4-left patch (`disable-D4-left.log`) also failed, but on the thrown shape. I replaced it with `-left-2`, which isolates the Left path.

### Benchmark comparison
Fake mode, no live calls. The new output is under `scratch/runtime/phase-11/repair-01` (2.2M).

| | run-7730f2f6f85f3bc22754e299a2136e16 (verifier-v6) | run-6acd1b5b8b39225000e9599b827f369d (verifier-v7) |
|---|---|---|
| Runtime run | run-c2198066e1f52dcdbfbb6eafe6b2b4c3 | run-bcc6454a98b785018d0df03546bf06bc |
| Answered / abstained | 27 / B09 AMBIGUOUS, B12 UNKNOWN, B28 UNSUPPORTED | the same |
| Verified / partial / unsupported / conflicting | 91 / 28 / 0 / 0 (119 claims) | 91 / 28 / 0 / 0 (119 claims) |
| Checks passing | 30 of 30 | 30 of 30 |
| LLM calls | 0 | 0 |
| Latency p50 / p95 / max | 23 / 419 / 750 ms | 22 / 365 / 714 ms |

- **Changed claims: none.** `compare-vs-run-7730f2f6.txt` reports "questions with any difference: 0", with no "only old" or "only new" claim lines. Claim texts and statuses, summaries, uncertainty lines, abstentions, checks and counts are all identical.
- **Why nothing changed:** the template writes no quantity word. Its only qualifiers are a bound fact's own direction ("size 3 or more", "at most N hops") or a ratio. Its negations come after the number, sit in another clause, or come before "converge". No fake question has a failing vector query.
- Benchmark exit 0, 62 s.

### Wrappers that now fail (ruling 14)
D items reported fixed: **D1, D2, D3, D4.** Each wrapper reports "A block of code that was marked pendingUntilFixed did not throw an exception":
1. `tests/adversarial/scholarchain/tests/adversarial/UnsupportedClaimAdversarialSpec.scala:100` — "a quantity stated only in words (hundreds of, dozens of, a dozen) is not verified" (D1)
2. `UnsupportedClaimAdversarialSpec.scala:118` — "a comparison the stored value contradicts (more than 10, fewer than 10) is not verified" (D2)
3. `UnsupportedClaimAdversarialSpec.scala:136` — "a negated statement of the stored value (does not have 10) is not verified" (D3)
4. `tests/adversarial/scholarchain/tests/adversarial/RagModelOutputAdversarialSpec.scala:352` — "a throttled vector query (once, or every time) does not fail an answer the graph supports" (D4)

I did not edit tests/**.

```text
FILES CREATED: none in the repository.
- Logs in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-11-logs/repair-01/partB/: df-start.txt, df-before-benchmark.txt, df-before-check.txt, rag-sha256-start.txt, main-sha256-before-disable.txt, rag-sha256-end.txt, rag-testonly-1..3.log, rag-test-1.log, disable-{D1-vague,D2-qualifier,D3-negation,ctl-bounded,ctl-ratio,ctl-converge,D4-thrown,D4-left,D4-left-2}.log and .sbt.log, fake-benchmark.log/.exit, benchmark.start, compare-vs-run-7730f2f6.txt, sbt-check-1.log/.start/.end/.exit, sbt-check-2.log/.start/.end/.exit, junit-counts-1.txt, junit-counts-2.txt.
- Benchmark output: /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/runtime/phase-11/repair-01/.
- Scratchpad: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/d0c5ca25-a016-4894-ab6a-845196da978b/scratchpad/partB/{patch.py, disable.sh, compare.py, junit_counts.py, RUNTIME.md.orig}.
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala (verifier-v7: Vague, Before, After, Ratio, bound-direction and Negation rules, hedge(), wired into textProblems)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/runtime/Semantic.scala (D4 degrade)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/VerifierSpec.scala (4 tests: repair-01 D1, D2, D3, controls)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/test/scala/scholarchain/rag/runtime/WorkflowSpec.scala (1 test: repair-01 D4, with thrown and refused shapes)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/RUNTIME.md (finding 5 and the sections listed above)
FILES DELETED: none
ALLOWED PLAN OPERATIONS: unchanged
PROMPT, SCHEMA, AND MODEL VERSIONS: verifier-v6 -> verifier-v7. Unchanged: planner-v2, planner-output-v1, synthesis-v3, synthesis-output-v3, rag-runtime-v3, rag-audit-v3, the Haiku model ID. No prompts/** change and no enum or schema change.
IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR: unchanged (fake run: B09 AMBIGUOUS, B12 UNKNOWN, B28 UNSUPPORTED). A failed vector query no longer fails the question; it adds a NO_SEMANTIC_MATCH note.
VERIFICATION RULES: RUNTIME.md section 12, "Quantity words, qualifiers, and negations (verifier-v7)".
BENCHMARK RESULTS: fake (graph plus fake embedder, no model) run-6acd1b5b…: 91/28/0/0, checks 30/30, identical to run-7730f2f6. Groundedness: 0 unsupported, 0 conflicting. InjectionSpec passes within rag 111. No vector-only or live run (ruling 2).
KNOWN FAILURE MODES: RUNTIME.md section 21, including these new items:
- true but inexact claims are removed ("at least 10" against a stored 10, "about 10");
- a negation anywhere before a number in its clause removes the claim;
- a negation with no number ("do not share any paper") is still not caught;
- "over" after a count noun is read as a ratio.
COMMANDS RUN (every sbt run with SPARK_LOCAL_IP=127.0.0.1, heap 4g or 6g):
- sbt rag/scalafmtAll, rag/Test/scalafmtAll, and rag/testOnly VerifierSpec WorkflowSpec CorpusSpec, three times while writing the tests: run 1 50/1 (a control question's fixture had no bridge facts), run 2 3/1 (the fixture component record has no inLargestComponent fact), run 3 4/0.
- sbt rag/scalafmtCheckAll, rag/Test/scalafmtCheckAll, rag/test: exit 0, 111/0.
- disable.sh nine times, as in the table above.
- sbt rag/runMain BenchmarkCli (fake, window-summary flags, --out scratch/runtime/phase-11/repair-01): exit 0.
- python3 -I compare.py and junit_counts.py; shasum -a 256 -c; df; ps.
- git diff and git status, read-only.
- sbt -J-Xmx6g check, twice.
- No aws, Bedrock, network, or other git commands. No find over scratch/. Nothing read under labels/, scratch/stores-live/, or scratch/er/adjudication-live/.
TEST RESULTS:
- sbt check 1 (03:59:24Z to 04:03:48Z): exit 1, sbt 259 s.
- sbt check 2 (04:04:13Z to 04:08:38Z): exit 1, sbt 260 s.
- Both runs, JUnit: 782 tests, 4 failures, 0 errors, 10 skipped (pending), 77 suites.
- Per project, both runs: core 185, entity-resolution 111, hadoop 70, rag 111 (was 106), services 43, spark 46, storage 108, all 0 failures; tests 108 with 94 succeeded, 4 failed, 10 pending.
- The 4 failures are exactly the D1 to D4 wrappers listed above (the "did not throw an exception" line appears 4 times in each log). There is no other failure. The 10 pending are D5 to D13 (D12 has two tests).
VALIDATION RESULTS:
- The format checks passed inside both checks.
- Every disable check fails a named test, and every restore matched its sha256.
- The final main-source manifest gave 15 of 15 OK.
- No sbt or java process is left. 13 GiB free at the end.
ASSUMPTIONS:
1. "Exactly true" is read strictly: only a bound fact's own direction counts as exact. "at least 10" against a stored 10 is removed (ruling 14 says: when in doubt, remove).
2. A quantity word fails only in a clause that also holds a measure name, so descriptive prose ("several methods") is not affected unless it states a measure.
3. For D4, a degraded index gets no SemanticResult in the audit, only the missing-evidence note, which is the same shape as an embedding failure. The benchmark's "no result on a failed call" rule (section 22) covers only model and embedding calls, so a benchmark run would store an answer degraded by a failed vector query.
RISKS:
- Guard event: guard-dangerous.sh blocked one Bash command [protected-path-write]. The command copied a script into my scratchpad and, in the same command, ran a read-only `ls .claude/.last-test-run`. Nothing was written to a protected path. Following ruling 7 ("use the Edit tool when a guard blocks"), I recreated the script with the Write tool and ran read-only commands without the marker path. The human should confirm this was acceptable.
- Recall cost in live mode: correct live phrasings with qualifiers or vague words will now be removed. This is unmeasured until the live rerun.
- The negation and qualifier rules are lexical. Wordings outside the lists still pass, for example "a mere 10", or "short of 10" against a stored 10.
MANUAL ACTION REQUIRED:
- The human reruns the live benchmark with verifier-v7; the new run IDs follow from the version.
- Work order 4 (test-engineer) removes the 4 wrappers. Until then, markTestRun will not write the marker.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: retrieval and groundedness evaluation, work order 4 wrapper removal, code-reviewer APPROVE (code review 2)
```

Not checked: real model output against the new rules (no live calls). RUNTIME.md was edited after both checks; it is documentation only, so no Scala or sbt source is newer than the check runs.
````

## Part C: service-engineer

````text
## Phase 11 repair-01 Part C (service-engineer): report

D5 is fixed. Finding 3 is addressed: the three timing-dependent tests are deterministic and the SaturationSpec scaladoc is corrected. `GraphEvidenceApi.main` itself is now tested. Every fix has a disable check that fails a named module test, and every restore matched its sha256. Each changed suite passed 10 of 10 runs. Both final `sbt check` runs end as ruling 14 expects: 786 tests, and the only 5 failures are the D1 to D5 wrappers.

My first D5 design had to be replaced. It stopped the JDK draining unread bodies (`drainAmount=0`). In the first pair of checks, check 2 then failed QueryApiSpec "missing or wrong API key is 401 …" with `Connection reset`: a 401 POST's body was still in the kernel buffer when the server closed. The same test failed 1 of 6 times in a stress run. The final design keeps the JDK drain and bounds it in time instead (details under D5 below). QueryApiSpec then passed 20/20 in one session plus 10/10 in the final runs. The first-design logs are under `scratch/phase-11-logs/repair-01/partC/` and are superseded. Every cited final log is under `scratch/phase-11-logs/repair-01/partC/final/`.

```text
STATUS: COMPLETE
OWNER: service-engineer
WORK ORDER: Phase 11 repair-01 Part C (D5; code review 1 finding 3; the CorpusQuerySpec main item from section 5), rulings 7 and 14
```

### D5: what was wrong and the fix
- **What was actually wrong.** In JDK 17 the response bytes go straight to the socket (`Request.WriteStream` is unbuffered), so the client did get its response. Code review 1 assumed the drain happened before the flush, but it happens after. The hang came next: `ExchangeImpl.close()` drained the unread body (up to 64 KiB) with no time limit. That pinned the HTTP thread and the connection forever.
- **Fix, in `Http.scala`:**
  1. A request read timeout: the JDK property `sun.net.httpserver.maxReqTime` = 30 s, set by `HttpService.start` before the JVM's first server. It cuts slow or missing bodies a route is reading, and headers that never end.
  2. When a request declared a body the route did not read, its response carries `Connection: close`.
  3. For those requests, `ex.close()` runs under a 1000 ms interrupt alarm. The JDK still drains a body that is arriving, so a client still sending gets its response instead of a reset. A body that never comes is cut: interrupting the HTTP thread closes the blocked socket channel. The interrupt never outlives the call.
  4. A body read cut by the timeout is logged 400 INVALID_FIELD, not 500.

### Timeout values and where they are configured
| Value | Where |
|---|---|
| Request read timeout: 30 s | The services-local constant `HttpService.RequestReadTimeoutSeconds`, applied as the JDK property `sun.net.httpserver.maxReqTime`. A JVM option `-Dsun.net.httpserver.maxReqTime=<seconds>` set at start wins. |
| Unread-body drain bound: 1000 ms | The services-local constant `HttpService.UnreadBodyDrainMs` |

Neither is a `services.*` key. Adding one is a modules/core change; I made none, and I am not blocked on it. Both are documented in SERVICES.md section 6.

### Disable checks (final code)
Logs are in `partC/final/disable-<label>.log`. Each check ran `services/testOnly` in a fresh sbt JVM. The manifest check afterwards had 0 mismatches.

| Fix | Patched out | Failing test (result) | sha256 restored |
|---|---|---|---|
| D5 drain bound | `ex.close()` without the alarm | RequestReadSpec "D5: a refused or unread body that never arrives does not hold the exchange or the thread": "over the cap, with the key: None" (:74), 2/1 | Http.scala b2bae7d1… OK |
| D5 `Connection: close` | header not set | RequestReadSpec "D5: a response to a body left unread says Connection: close, so no client reuses it" (:124), 2/1 | b2bae7d1… OK |
| D5 read timeout | maxReqTime not set | RequestReadSpec "D5: a body or header slower than the read timeout loses its connection; threads freed": "the slow body kept its connection" (:95), 2/1 | b2bae7d1… OK |
| D5 400 instead of 500 | IOException catch removed | the same test: logged `"status":500,"error":"INTERNAL_ERROR"` (:103), 2/1 | b2bae7d1… OK |
| Main uses answers = false | `main` back to `Launcher.main(...)` (default answers = true) | CorpusQuerySpec "the graph-evidence-api main itself passes answers = false (repair-01 Part C)": "failed: … OBJECT_MISSING" (:194), 4/1. The start-based m3 test still passed. | GraphEvidenceApi.scala c1c1aea9… OK |
| Finding 3, :113 (the test still catches a queued workflow) | workflow pool given a queue of 4 | BuildingBlocksSpec "waiting to start…": "Expected exception ApiError … no exception was thrown", 6/1 | Resilience.scala 7607aafd… OK |
| Finding 3, :118-122 (the test still catches a timeout counted from submission) | `await` rethrows at the submission deadline | BuildingBlocksSpec "waiting to start…": "s3 did not answer within 4000 ms", 6/1 | 7607aafd… OK |
| Finding 3, GraphEvidenceApiSpec:211 (the test still catches lost shedding) | unbounded HTTP queue | GraphEvidenceApiSpec "beyond its threads and queue … 503 OVERLOADED": "no probe was shed" (:216), 6/1 | b2bae7d1… OK |

### Finding 3 changes
- **BuildingBlocksSpec :113.** The `< 1000` ms assertion is replaced by a workflow with a one-hour timeout. OVERLOADED can then only mean an immediate refusal; a queued call would run and answer.
- **BuildingBlocksSpec :118-122.** The two orderings (the release at half the timeout, and the end after the submission deadline) are now explicit clock checks instead of sleeps, with timeout T = 4000 ms. One real-time condition remains, stated in the test: a pool thread must start within T/2 = 2 s of being freed. The old margins were 200 ms.
- **GraphEvidenceApiSpec :211.** The 300 ms sleep is gone. Two probes race for the one queue place, and the first to finish must be the shed 503, whatever the order of arrival.
- **SaturationSpec scaladoc.** It now lists the three remaining real-clock conditions from code review 1, section 4c.

### 10-run results
Each changed suite, plus QueryApiSpec (the one that flaked), in 10 separate sbt runs on the final code (`partC/final/ten-runs-summary.txt`). All 10 exited 0, each with 37 passed and 0 failed:

| Suite | Passed / failed, every run |
|---|---|
| BuildingBlocksSpec | 7/0 |
| GraphEvidenceApiSpec | 7/0 |
| SaturationSpec | 2/0 |
| CorpusQuerySpec | 5/0 |
| RequestReadSpec | 3/0 |
| QueryApiSpec | 13/0 |

An earlier 10-run pass on the first design was also 10/10 (`partC/ten-runs-summary.txt`).

### tests/** wrappers that now fail (ruling 14)
D items reported fixed: **D1, D2, D3, D4 (Part B) and D5 (this part).** Each fails with "A block of code that was marked pendingUntilFixed did not throw an exception":
1. tests/adversarial/scholarchain/tests/adversarial/UnsupportedClaimAdversarialSpec.scala:100, "a quantity stated only in words (hundreds of, dozens of, a dozen) is not verified" (D1)
2. UnsupportedClaimAdversarialSpec.scala:118, "a comparison the stored value contradicts (more than 10, fewer than 10) is not verified" (D2)
3. UnsupportedClaimAdversarialSpec.scala:136, "a negated statement of the stored value (does not have 10) is not verified" (D3)
4. tests/adversarial/scholarchain/tests/adversarial/RagModelOutputAdversarialSpec.scala:352, "a throttled vector query (once, or every time) does not fail an answer the graph supports" (D4)
5. tests/adversarial/scholarchain/tests/adversarial/ApiBodyAdversarialSpec.scala:205, "a declared body that never arrives does not hold the exchange open (refused, 401, or unread)" (D5). sbt cites :205; the WO3 report said :204.

I did not edit tests/**.

```text
FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/RequestReadSpec.scala (3 raw-socket tests on 127.0.0.1:0)
- Logs, all under /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-11-logs/repair-01/partC/:
  - first design (superseded): df-start.txt, services-sha256-start.txt, dev-run-1..4*.log, disable-*.log (7), disable-summary.txt, services-sha256-{before,after}-disable*.txt, ten-run-1..10.log, ten-runs-summary.txt, df-before-check.txt, sbt-check-1..2.{log,start,end,exit}, junit-counts-1..2.txt
  - final/: disable-*.log (8), disable-summary.txt, services-sha256-{before-disable,after-disable-check}.txt, ten-run-1..10.log, ten-runs-summary.txt, df-before-check.txt, sbt-check-1..2.{log,start,end,exit}, junit-counts-1..2.txt
- Scratchpad: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/d0c5ca25-a016-4894-ab6a-845196da978b/scratchpad/partC/{disable.py, ten-runs.sh, junit_counts.py, jdk/ (JDK 17 jdk.httpserver sources unzipped from the local JDK's src.zip, read only)}
FILES MODIFIED (Part C only; the start and final manifests differ in exactly these files):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/main/scala/scholarchain/services/Http.scala (read timeout, Connection: close, bounded drain alarm, 400 on a cut body read, companion HttpService)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/BuildingBlocksSpec.scala (:113 and :118-122)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/GraphEvidenceApiSpec.scala (:211)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/SaturationSpec.scala (scaladoc only)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/test/scala/scholarchain/services/CorpusQuerySpec.scala (the child-JVM main test)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/SERVICES.md (new section 6; one line in section 3)
FILES DELETED: none
ROUTE TABLE: unchanged (ServicesContractSpec passes)
STATUS-CODE MAPPING:
- unchanged for delivered responses;
- a body read cut by the read timeout or by the client is logged 400 INVALID_FIELD ("the body was not received in full") instead of 500 (the client cannot receive it);
- a response to a request whose declared body was left unread carries Connection: close.
TIMEOUT, RETRY, AND CIRCUIT-BREAKER POLICY: new request read timeout of 30 s (maxReqTime; first byte to complete body, including time queued for an HTTP thread); unread-body drain bound of 1000 ms after the response; downstream policy unchanged
AUTHENTICATION METHOD: unchanged (X-Api-Key, constant-time comparison)
SERVICE DEPENDENCY GRAPH: unchanged (services -> core, rag, storage)
COMMANDS RUN (every sbt run with SPARK_LOCAL_IP=127.0.0.1, -J-Xmx4g, or -J-Xmx6g for check):
- read-only: cat, sed, grep, find (only under modules/ and tests/; never over scratch/), wc, df, shasum, pgrep, git diff and git status (read-only), unzip of the local JDK's src.zip into the scratchpad
- sbt "services/scalafmtAll" "services/Test/scalafmtAll" "services/testOnly <changed suites>" (dev runs 1-2; run 1 hit a compile error, fixed)
- sbt services/testOnly RequestReadSpec plus QueryApiSpec x20 in one session (dev runs 3-4: the first design failed 1 of 6, the final design passed 20/20)
- python3 -I disable.py twice: 7 checks on the first design, 8 on the final; restore and shasum -c
- ten-runs.sh twice (first design; final code)
- sbt -J-Xmx6g check: twice on the first design, twice on the final code
- python3 -I junit_counts.py after each check
- no git write commands, no aws, no network; nothing read, listed, or found under labels/, scratch/stores-live/, or scratch/er/adjudication-live/; nothing existing under scratch/ modified
TEST RESULTS:
- Final check 1 (05:06:30Z to 05:10:54Z) and final check 2 (05:10:54Z to 05:15:17Z): exit 1 each, sbt 259 s each.
- JUnit, both runs: 786 tests, 5 failures, 0 errors, 9 skipped (pending), 78 suites.
- Per project, both runs: core 185, entity-resolution 111, hadoop 70, rag 111, services 47 (was 43; 9 suites), spark 46, storage 108, all with 0 failures; tests 108, with 94 succeeded, 5 failed, 9 pending.
- The 5 failures are exactly the ruling 14 wrappers listed above ("did not throw" appears 5 times in each log). The 9 pending are D6 to D13, with D12 twice.
- First-design checks, for the record: check 1 had 785 tests and only the 5 accepted failures; check 2 had 6 failures, including the unaccepted QueryApiSpec reset, which caused the redesign.
VALIDATION RESULTS:
- The format checks passed inside every check.
- ServicesContractSpec passes (3 tests, including "an unauthenticated oversized body is refused as 401").
- 8 of 8 disable checks fail their named tests, and every restore matched its sha256.
- markTestRun did not write the marker, as ruling 14 expects; I did not touch it.
- No sbt or child JVM is left. Disk: 13 GiB free at the start, 12 GiB at the end.
ASSUMPTIONS:
1. Interrupting a thread blocked in a blocking SocketChannel read closes the channel (InterruptibleChannel). The JDK HTTP server reads its body through such a channel. This was verified in the JDK 17 source and by the D5-alarm disable check.
2. The 30 s and 1000 ms constants are acceptable services-local values, as the work order allows. A core key would be a domain-engineer change.
3. The main test runs the real `GraphEvidenceApi.main` in a child JVM (-Xmx256m). Its classpath comes from the test class loaders, and its environment is cleared down to the world's variables. The test holds the child's port, so the child stops at its bind and never listens; the test itself binds only 127.0.0.1:0.
RISKS:
- The 30 s also counts time a request waits in the HTTP queue. Under deep overload, a request queued longer than that loses its connection instead of being answered late. Documented in SERVICES.md sections 3 and 6.
- Slow headers and slow bodies within the cap still hold a thread for up to 30 s, and a never-sent refused body for up to 1 s. An attacker can still occupy threads by opening new connections at that rate. This is bounded, not eliminated, which is inherent to the JDK server; ALB in front mitigates it.
- A refused body larger than 64 KiB that is still arriving can still be reset (pre-existing). A request shed on the dispatcher thread can hold the dispatcher for up to 1 s (before, there was no limit).
- When the alarm cuts a drain, the JDK ends the exchange without its WriteFinished event. The request-timeout timer removes the connection within 30 s. This only affects `stop(delay > 0)`; the services use `stop(0)`.
- maxReqTime is JVM-wide and read once. It holds because every JDK server in this repository is an HttpService.
- BuildingBlocksSpec :118-122 still needs a pool thread to start within 2 s of being freed.
- Guard event: guard-dangerous.sh [protected-path-write] blocked one Bash command. It was a `tee` of df output to scratch/ in a command whose `grep -v` filter text contained "docs/agent-runs/". Nothing was written to a protected path. I did not recreate the write: I reran only the read-only parts and record the end disk space (12 GiB) and the final hashes in this report instead of files. The human should confirm this was acceptable.
MANUAL ACTION REQUIRED:
- Work order 4 (test-engineer): remove the 5 wrappers listed above.
- Optional follow-up for domain-engineer: a `services.*` key for the request read timeout.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: API tests, sbt check, code-reviewer APPROVE
```

Final sha256 prefixes: Http.scala b2bae7d1, Resilience.scala 7607aafd (unchanged), main/GraphEvidenceApi.scala c1c1aea9 (unchanged), RequestReadSpec ea… see below, RequestReadSpec 1a62b550, BuildingBlocksSpec 03045ac8, GraphEvidenceApiSpec b918a412, SaturationSpec acdbacda, CorpusQuerySpec ea21cf58, SERVICES.md 2ad4f86d.

Not checked: the timeouts behind a real ALB or under deliberate CPU load. openapi.yaml was not changed, since the new 400 is never delivered to a client. Risk: the JVM-wide setting depends on every JDK HTTP server in a JVM being an HttpService.
````

## Part D: domain-engineer

````text
## Phase 11 repair-01 Part D (domain-engineer): report

D11, D12, D13 and findings 1 and 2 are fixed. **D8 is not fixed: ruling 15 blocks it.** An idempotent `normalizeDoi` would change 36 stored DOI values in 26 existing ORCID researcher-profile records, so this part returns BLOCKED-ON human for D8 only.

Both `sbt check` runs end as ruling 14 expects:
- 791 tests in each run.
- The only 9 failures are the wrappers of D1 to D5 plus D11, D12 (two tests) and D13. Each fails with "did not throw an exception".
- 5 tests stay pending: D6, D7, D8, D9, D10.
- markTestRun therefore did not write the marker. The Stop hook (`verify-tests-ran.sh`) fired once while check 1 was still running; I did not work around it.

```text
STATUS: BLOCKED-ON (D8 only; every other Part D item is COMPLETE)
OWNER: domain-engineer
WORK ORDER: Phase 11 repair-01 Part D (D8, D11, D12, D13; code review 1 findings 1 and 2), rulings 7, 14, 15, 16
```

### D items and findings
| Item | Result |
|---|---|
| D8 | **Not fixed** (ruling 15). The `tests/**` wrapper stays pending. |
| D9 | Residual by ruling 15; wrapper stays pending. |
| D11 | Fixed. The index map is built from `getObject(...).keySet` plus `getString`, so an entry set to null is now a load error. Before, `entrySet` dropped it silently. |
| D12 | Fixed. Outside `local`, a blank or empty value is refused for all 16 keys WO3 lists and for every `s3-vectors.indexes.*` entry. This covers both a file value and an empty environment variable. Error text: `environment '<env>' has blank values for: …` |
| D13 | Fixed. `wholeInt` refuses a value whose double differs from its int (8.5, "150.5"), with "must be a whole number". It covers every integer key, including window years. 8182.0 still loads. |
| Finding 1 | Fixed. The AuthorshipRecord validator now adds `author position must be >= 1, got <p>`. |
| Finding 2 | Fixed. Core `analytics.community-max-iterations` is now 10. Its comment says the Spark jobs read `scholarchain-spark.community-max-iterations` from scholarchain-spark.conf. No key removed, no config-version bump (ruling 16). |

### Ruling 15 measurements (read-only; both forbidden paths pruned in every find and walk)

**D8, raw inputs of every existing Hadoop run** (from the run manifests). Script: `measure_doi_raw.py`. The old rule and the new idempotent rule are Java transcriptions. Since `new(raw) = new(old(raw))`, any difference means a stored value changes.

| Input | Records | DOI values | Would change |
|---|---|---|---|
| openalex full works.jsonl | 107,506 | 107,506 | 0 |
| crossref full works.jsonl | 106,726 | 106,726 | 0 |
| orcid full records.jsonl.gz (v3 external-ids, type doi) | 22,975 | 997,529 | **36** |
| dblp full works.xml, `<ee>` DOI URLs (all `<ee>`) | – | 107,639 (155,121) | 0 (0) |
| datasets/samples: openalex, crossref, orcid ×2, dblp | 40, 40, 3+1, – | 40, 40, 5+6, 40 | 0 |

- **ORCID detail** (measure-doi-orcid-detail.log): 17,632 records have DOIs; 26 records hold the 36 values; no list length or numbering change.
- **Example:** `doi: 10.1016/...`. The old rule stores `" 10.1016/..."` with a leading space; the new rule gives `"10.1016/..."`.

**D8, stored outputs.**

| Output | Records | Values | Would change |
|---|---|---|---|
| wo5-v2b run-035e75ab… j02 researcher-profiles | 22,913 | 987,123 workDoi | **36 values in 26 profiles** |
| wo4 runs (3) j02 researcher-profiles | 1 each | 6 each | 0 |
| Text outputs of scratch/hadoop (j01, j02, j03-j04, j07-j08), spark, stores, er, runtime (rules R1, R2, R3) | 4,065,887 / 179,710 / 4,785,356 / 2,301 / 487,825 lines | – | R1 = 0, R3 = 0 everywhere. R2 = 97 in hadoop: the stored leading-space values plus false positives such as `http://doi.acm.org/…` and NBSP-prefixed values, which neither rule strips. |

- No doi: work ID, mention ID, or exact-identifier key changes.
- **Counts for the human (D8):** 36 stored ORCID `workDoi.N` values in 26 stored researcher-profile records, in j02-source-normalize/researcher-profiles of the run above.

**Finding 1** (canonical/authored is the store ruling 15 names, so it is decisive):

| Dataset | Rows | Position null | Position < 1 | Min / max |
|---|---|---|---|---|
| spark run-10ceed31… canonical/authored (parquet, Spark read) | 535,898 | 0 | 0 | 1 / 287 |
| stores neptune run-1d6a8649… edges/authored (CSV) | 535,898 | – | 0 | 1 / 287 |

Result: 0 records would change, so finding 1 was applied.

### Disable checks
Each fix was patched out and `core/testOnly` was run. The source was then restored, and every restore matched its sha256 (disable-summary.txt). The final manifest is unchanged since the fix.

| Fix | Failing test (suite result) | sha256 restored |
|---|---|---|
| F1: position check removed | ValidationSpec "phase 11 finding 1: …" (16/1): "expected rejection containing 'author position must be >= 1, got 0'" | c5619f6e… OK |
| F2: conf back to 20 | ConfigLoaderSpec "phase 11 finding 2: …" (32/1): "20 did not equal 10" | f98a0563… OK |
| D11: null entries filtered out | ConfigLoaderSpec "phase 11 D11: …" (32/1): config loaded | b4532a21… OK |
| D12: `|| true` | ConfigLoaderSpec "phase 11 D12: …" (32/1): loaded with a blank region | b4532a21… OK |
| D13: `if false &&` | ConfigLoaderSpec "phase 11 D13: …" (32/1): loaded, port truncated to 8182 | b4532a21… OK |

### tests/** wrappers that now fail (ruling 14)
Each fails with "A block of code that was marked pendingUntilFixed did not throw an exception":
1. UnsupportedClaimAdversarialSpec:100 (D1)
2. UnsupportedClaimAdversarialSpec:118 (D2)
3. UnsupportedClaimAdversarialSpec:136 (D3)
4. RagModelOutputAdversarialSpec:352 (D4)
5. ApiBodyAdversarialSpec:205 (D5)
6. ConfigOmissionContractSpec:60, "every configuration key omitted (set to null) stops the load with an error" (D11)
7. ConfigOmissionContractSpec:74, "a blank value for a required production setting is refused outside the local environment" (D12)
8. ConfigOmissionContractSpec:107, "an empty environment variable overriding a required setting is refused outside local" (D12)
9. ConfigOmissionContractSpec:149, "a fractional value for an integer setting is refused, not truncated" (D13)

Still pending: D6, D7, D8 (IdentityAdversarialSpec, "DOI normalization is idempotent"), D9, D10. I did not edit tests/**.

```text
FILES CREATED: none in the repository. Logs in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-11-logs/repair-01/partD/:
- df-start.txt, df-end.txt
- core-sha256-start.txt, core-sha256-after-fix.txt
- core-test-1.log/.exit
- measure-doi-raw.* (first run; its ORCID v3 extraction was wrong, superseded), measure-doi-raw-2.*
- measure-doi-orcid-detail.log, measure-doi-stored-workdoi.log
- measure-doi-outputs-targeted.*; measure-doi-outputs-broad-stopped.* and .note (I stopped this run inside the multi-GB j06 candidates; not cited)
- measure-authored.*, measure-authored-neptune.log
- disable-{F1-position,F2-community-iterations,D11-null-index,D12-blank,D13-fraction}.log, disable-summary.txt
- sbt-check-1.*, sbt-check-2.*
Scratchpad: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/d0c5ca25-a016-4894-ab6a-845196da978b/scratchpad/partD/ (measure_doi_raw.py, measure_doi_outputs.py, measure_doi_orcid_detail.py, measure_doi_stored_workdoi.py, MeasureAuthored.java, disable.py)
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/Validation.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/resources/scholarchain.conf (value 10, its comment, header lines on the new rules)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/ValidationSpec.scala (+1 test)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSpec.scala (+4 tests; the existing dev environment map is now a shared devEnv)
FILES DELETED: none
SCHEMA VERSIONS: unchanged (no serialized shape change; config-version stays config-v1)
STABLE ID FORMULAS: unchanged (normalizeDoi not changed)
PAIR CANONICALIZATION RULE: unchanged
CONFIGURATION PRECEDENCE: unchanged (variables > SCHOLARCHAIN_CONFIG_FILE > reference). New rules: outside local, no blank required values; no fractional integers; no null index entries.
SAMPLE SERIALIZED RECORDS: n/a (no wire change)
AFFECTED CONSUMERS:
- test-engineer (work order 4): remove the 4 new wrappers listed above.
- infra-engineer (Phase 12): ECS must not pass empty SCHOLARCHAIN_* variables outside local; the load now fails fast.
- No production module change is needed. No AuthorshipRecord validator caller exists outside core.
COMMANDS RUN:
- Read-only: cat, sed, grep, find (pruned), ls, wc, shasum, lsof, ps, df; git status and git diff (read-only)
- python3 -I measurement scripts (above)
- java single-file MeasureAuthored.java on the Part A tests classpath (Spark local, -Xmx2g, SPARK_LOCAL_IP=127.0.0.1)
- SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g "core/scalafmtAll" "core/Test/scalafmtAll" "core/test": exit 0, 190/0
- disable.py: 5 × sbt -J-Xmx4g core/testOnly
- SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g check, twice
- caffeinate -w <pid> to wait for the running check
- kill of my own stopped scan process
- No aws, no network, no git writes.
TEST RESULTS:
- check 1: 05:28:54Z to 05:33:21Z, exit 1, sbt 258 s.
- check 2: 05:33:25Z to 05:37:49Z, exit 1, sbt 259 s.
- Both runs: core 190 (was 185), entity-resolution 111, rag 111, storage 108, services 47, spark 46, hadoop 70, all 0 failed; tests 94 succeeded, 9 failed, 5 pending.
- Total 791. The 9 failures are exactly the ruling 14 wrappers above ("did not throw" counted 9 times in check 2). No other failure.
VALIDATION RESULTS:
- Format checks passed inside both checks.
- 5 of 5 disable checks fail their named test; every restore matched its sha256.
- Final core manifest equals the post-fix manifest.
- No sbt, Java or scan process left. 12 GiB free at start and end.
ASSUMPTIONS:
- Python's lower() matches Java's toLowerCase for deciding whether a value starts with an ASCII prefix or with white space at or below U+0020.
- The DBLP `<ee>` extraction is regex plus html.unescape (a superset that includes non-DOI links).
- The parquet D8 scan was skipped: D8 was already blocked by the raw-input and stored-j02 measurements.
RISKS:
- Ruling 7 deviation: an early `du -sh scratch` walked directory entries under scratch/stores-live/ and scratch/er/adjudication-live/ for a size total. Nothing from them was printed or read. Every later walk pruned both.
- D12 applies only outside local (as ordered); local still accepts blank values.
- Values from external DOI sources may contain other strippable shapes not present in today's data.
MANUAL ACTION REQUIRED:
- Human: rule on D8. Accept a change to 36 stored ORCID workDoi values in 26 researcher-profile records (Hadoop run run-035e75abfa6798c938cbd534273dbd8d, j02-source-normalize/researcher-profiles; no work ID or key changes), or keep D8 residual with D9.
- Human: confirm the du deviation is acceptable.
BLOCKED-ON OWNER: human architect (D8 only)
BLOCKED-ON EVIDENCE: measure-doi-raw-2.log (TOTAL_CHANGED 36, ORCID full), measure-doi-orcid-detail.log (26 records), measure-doi-stored-workdoi.log (values_changed=36, profiles_changed=26)
NEXT GATE: contract tests, sbt check, and code-reviewer APPROVE (after work order 4 removes the D11/D12/D13 wrappers)
```

Not checked: the parquet outputs for D8 (not needed for the decision), and j05, j06, j09 and j10 in the targeted text scan. Those jobs only carry IDs derived from j01 and j02, and no doi: work ID changes.
````

## Part E: storage-engineer

````text
## Phase 11 repair-01 Part E (storage-engineer): report

D10 is fixed. `WindowSummaries.problems` now refuses window-summary rows whose counts no real coauthorship graph can have. The existing loaded object under scratch/runtime/wo8/s3 still opens, and every existing window-summary row on disk passes the new rules; both were checked read-only. Each part of the fix fails the new module test when patched out (4 of 4 disable checks), and every restore matched its sha256.

Both `sbt check` runs end as ruling 14 expects:
- 792 tests in each run.
- The only 10 failures are the ruling-14 wrappers: the 9 from earlier parts plus the D10 wrapper. Each fails with "A block of code that was marked pendingUntilFixed did not throw an exception".
- 4 tests stay pending: D6, D7, D8 and D9.
- markTestRun therefore did not write the marker, as expected.

```text
STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 11 repair-01 Part E (D10), rulings 7, 14, 15, 16
```

### Identities enforced
These run in `WindowSummaries.identityProblems`, called from `problems`. All arithmetic is in BigInt, so a tampered value cannot overflow into a pass. Each violation is a `BAD_ROW`, and any violation refuses the whole object.

**1. Components partition the researchers.** Write r for researchers, c for components, l for the largest component and s for singleton components:
- r = 0 means c, l and s are all 0.
- l = 1 means s = c = r.
- Otherwise there is at least one group of two or more, and s + l + 2(c − s − 1) ≤ r ≤ s + l·(c − s).
- This implies largestComponentSize ≤ researchers − (components − 1).

**2. Communities partition the researchers.** The same rule applies to communities, largestCommunitySize and singletonCommunities. It implies largestCommunitySize ≤ researchers − (communities − 1).

**3. The edges fit the components.**
- researchers − components ≤ coauthorEdges, because a spanning forest needs that many edges.
- 2·coauthorEdges ≤ (researchers − singletonResearchers)·(largestComponentSize − 1), because a researcher has at most largest − 1 coauthors.

**4. communitiesAtLeastMinSize fits the community sizes.** Write a for communitiesAtLeastMinSize and m for communityMinSize:
- If largestCommunitySize < m, then a = 0.
- Otherwise a ≥ 1, and a is at most the number of communities with two or more members. When m = 1 it equals the number of communities, and when m = 2 it equals the number with two or more.
- Also (a − 1)·m + largest + (communities − a) ≤ researchers, which implies a × m ≤ researchers.

### Existing-object and existing-row checks (read-only)
- **Store object** `scratch/runtime/wo8/s3/curated/window-summary/snapshot=2026-10-03/run=run-6fbad498beb0d8f570df67665fb97544/part-00000.jsonl` (4,320 bytes, sha256 d713f227…).
  - `WindowSummaries.open` with the fixed code returns OPEN_STORE_OBJECT OK.
  - 3 rows, 0 with problems.
- **Spark WO7 manifest rows** (`run-1de2293597c338227290eb382de16957/_manifest.json`, through `fromSparkManifest`): 3 rows, 0 with problems. They equal the store object's rows.
- **Phase 6 temporal window-summary** (`run-0375535e0174b7330b18a78988c07211`, a different schema with no singletonCommunities and no communityMinSize column; checked with m = 3): 3 rows, 0 failing the identities its columns allow. The shared fields equal the store object's rows.
- The input sha256 values were the same before and after the check (existing-inputs-sha256-before.txt and existing-inputs-sha256-after.txt).
- Every find over scratch/ pruned scratch/stores-live and scratch/er/adjudication-live. Nothing under them was read or listed.
- No stored record becomes unreadable.

### Disable checks
Each check patched one part of the fix out of WindowSummaries.scala, ran `storage/testOnly WindowSummarySpec`, restored the file, and verified its sha256 (d4888851… each time OK).

| Patch | Result | First failing case |
|---|---|---|
| all identities (`.take(0)`) | 6 passed, 1 failed: "phase 11 D10: counts no graph can have are refused; real-shaped counts still open" | "largest component 900 with 1,000 components" |
| partition always true | same test fails | same case |
| at-least-min-size always true | same test fails | "communitiesAtLeastMinSize x communityMinSize above researchers" |
| edges always true | same test fails | "fewer edges than a spanning forest" |

- In round 1 (logs in disable-round-1/), the at-least-min-size patch did **not** fail the test. Its tamper rows were also caught by the old pairwise bound or by the partition check.
- I added three cases that only that identity catches, and round 2 failed all 4 patches.

### tests/** wrappers that now fail (ruling 14)
Each fails with "A block of code that was marked pendingUntilFixed did not throw an exception":
1. UnsupportedClaimAdversarialSpec:100 (D1)
2. UnsupportedClaimAdversarialSpec:118 (D2)
3. UnsupportedClaimAdversarialSpec:136 (D3)
4. RagModelOutputAdversarialSpec:352 (D4)
5. ApiBodyAdversarialSpec:205 (D5)
6. ConfigOmissionContractSpec:60 (D11)
7. ConfigOmissionContractSpec:74 (D12)
8. ConfigOmissionContractSpec:107 (D12)
9. ConfigOmissionContractSpec:149 (D13)
10. **WindowSummaryTamperAdversarialSpec:136 (D10, new)**: "counts no coauthorship graph can have are refused (largest component plus the other components exceed the researchers)"

Still pending: D6, D7, D8 ("DOI normalization is idempotent") and D9. I did not edit tests/**.

**D10: fixed.** Work order 4 can remove its wrapper.

```text
FILES CREATED: none in the repository. Logs in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-11-logs/repair-01/partE/:
- df-start.txt, df-end.txt
- storage-sha256-start.txt, storage-sha256-end.txt, fix-sha256.txt
- storage-windowsummary-1.log/.exit, storage-windowsummary-2.log/.exit
- existing-check-after-fix.log, existing-check-phase6-temporal.log, existing-inputs-sha256-before.txt, existing-inputs-sha256-after.txt
- disable-D10-{all-identities,partition,at-least-min-size,edges}.log, disable-summary.txt (round 2, cited); disable-round-1/ (round 1)
- sbt-check-1.{start,end,exit,log}, sbt-check-2.{start,end,exit,log}
Scratchpad: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/d0c5ca25-a016-4894-ab6a-845196da978b/scratchpad/partE/ (CheckExisting.java, disable.py, cp.txt)
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/WindowSummaries.scala (partitionOk, identityProblems, one call in problems)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/test/scala/scholarchain/storage/WindowSummarySpec.scala (+1 test: the three WO3 inputs, isolated cases for each identity, an overflow case, and real-shaped controls including a tree and a clique at the degree bound)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/STORAGE.md ("Window summaries" validation line lists the identities)
FILES DELETED: none
SCHEMAS AND KEYS: unchanged (spark-corpus-window-summary-v1, curated/window-summary/ key, load run ID formula)
METADATA FIELDS: unchanged
BATCH SIZES AND RETRY POLICY: unchanged. The optional S3VectorStore read retry is not done (ruling 16).
IDEMPOTENCY STRATEGY: unchanged (write-once object, reload is Unchanged)
SERVICE LIMITS: none consulted (no AWS-facing change)
CLEANUP PROCEDURE: n/a (no AWS; new files are logs under partE/ only)
COMMANDS RUN:
- Read-only: cat, sed, grep, find (both forbidden paths pruned), ls, wc, shasum, df, pgrep; git status and git diff (read-only)
- SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g "storage/scalafmtAll" "storage/Test/scalafmtAll" "storage/testOnly ...WindowSummarySpec": exit 0, 7/0 (run twice)
- SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g "export storage/Runtime/fullClasspath"
- java -Xmx1g single-file CheckExisting.java (read-only open and validation of existing data)
- python3 -I (Phase 6 identity check; disable.py with 4 × sbt -J-Xmx4g storage/testOnly, run in two rounds)
- SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g check, twice
- caffeinate -w to wait for the running checks
- No aws, no network, no git writes.
TEST RESULTS (unit only; no AWS-profile tests):
- check 1: 05:48:18Z to 05:52:44Z, exit 1, sbt 258 s.
- check 2: 05:52:55Z to 05:57:19Z, exit 1, sbt 259 s.
- Both runs: core 190, entity-resolution 111, rag 111, storage 109 (was 108), services 47, spark 46, hadoop 70, all 0 failed; tests 94 succeeded, 10 failed, 4 pending.
- Total 792. The 10 failures are exactly the ruling-14 wrappers above ("did not throw" counted 10 times in each log, each file:line once). No other failure.
VALIDATION RESULTS:
- Format checks passed inside both checks.
- 4 of 4 disable checks fail the named test (round 2); every restore matched its sha256.
- WindowSummaries.scala and WindowSummarySpec.scala match fix-sha256.txt after both checks.
- No sbt or java process left. 12 GiB free at start and end.
ASSUMPTIONS:
- Components and communities each partition the window's researchers, singletons included (spark JOBS.md, CorpusWindowSummaryJob table). All fixtures in rag, services, storage and tests/** satisfy this.
- The parquet copy of corpus/window-summary (WO7) holds the same rows as its manifest's counts.windowSummary. The loader reads the manifest copy and checks the row count; I did not open the parquet file itself.
RISKS:
- The identities are stricter than the four examples in the work order (full partition bounds and edge bounds). A future Spark change that breaks the partition definition, such as a community not covering every node, would make the loader refuse its output. That refusal is intended.
- Hook event: a read-only summary command that ran `ls` on .claude/.last-test-run in the same line as `tee` into my partE log was blocked by guard-dangerous.sh (protected-path-write). The marker was never written or touched. I removed the marker reference instead of working around the guard, and read the result from the sbt log.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work order 4 removes the D10 wrapper (WindowSummaryTamperAdversarialSpec:136) with the other nine; then contract tests, sbt check, and code-reviewer APPROVE
```

Not checked: the WO7 parquet file directly (only its manifest copy and the loaded object).
````

## Part F: spark-engineer

````text
STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 11, repair-01, Part F (code review 1 finding 6). Reword modules/spark/JOBS.md:469-470 so that matchConfidence and identityConfidence are described as the cluster's minimum accepted-edge confidence. Documentation only.

FILES CREATED: none
FILES MODIFIED: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/JOBS.md (lines 469 and 470 only)
FILES DELETED: none

CHANGED LINES (before and after)

Line 469, last cell ("Missing" column of the canonical/authored row):
- Before: `matchConfidence` (per mention: the mention map's `identityConfidence`)
- After: `matchConfidence` (not stored per mention; the nearest value is the mention map's `identityConfidence`, the researcher cluster's minimum accepted-edge confidence)
- Full line after: | `canonical/authored` (`edges/type=authored`) | researcherId\|workId | `AuthorshipRecord` | mentionId -> `mentionIds` (one row per researcher and work, not per mention) | the per-mention link is the mention map (RESOLVES_TO) | sourceDatasets | `matchConfidence` (not stored per mention; the nearest value is the mention map's `identityConfidence`, the researcher cluster's minimum accepted-edge confidence) |

Line 470, "Added" column of the canonical/mention-map row:
- Before: identityConfidence (uncertainty of each resolution)
- After: identityConfidence (the mention's researcher cluster's minimum accepted-edge confidence, the same value for every mention in the cluster; 1.0 when the cluster has no accepted edge inside it)
- Full line after: | `canonical/mention-map` | mentionId | `AuthorMention` (link part) and the RESOLVES_TO relation | workId -> sourceWorkId | none | researcherId, assignedTier, separationReason, refusal rules, identityConfidence (the mention's researcher cluster's minimum accepted-edge confidence, the same value for every mention in the cluster; 1.0 when the cluster has no accepted edge inside it) | name, ORCID, institutions (in the J09 mention) |

VERIFICATION AGAINST CODE (read only, no Scala edited)
- modules/spark/src/main/scala/scholarchain/spark/Clustering.scala:117-123 joins accepted edges to final cluster keys, keeps edges whose two endpoints are in the same cluster (ks === kd), and computes min("confidence") per clusterKey. :155 sets identityConfidence = coalesce(minConfidence, 1.0). So every member of a cluster gets the same value, and a cluster with no accepted edge inside it gets 1.0. I added the 1.0 default to line 470 because the code sets it and a reader of that cell would otherwise not know it.
- Canonical.scala:231 copies that mapping value into the mention map. :294 (researchers) and :332 (works) aggregate it with min(...). These match JOBS.md:42.
- I checked the rest of JOBS.md. grep for "confidence", "per mention", "per-mention", "each resolution", and "matchConfidence" finds no other per-mention or per-resolution wording. Line 41 lists the mention-map column with no qualifier. Line 42 already says "min confidence of accepted edges inside the cluster". Lines 468 and 471 list identityConfidence for works and the work map with no qualifier. I left those four lines unchanged.

STAGE DAGS AND SHUFFLE BOUNDARIES: not applicable (documentation only)
PARTITION COUNTS AND SKEW: not applicable
GRAPH COUNTS: not applicable
ALGORITHMS: not applicable (no change)
METRIC DISTRIBUTIONS: not applicable
TEMPORAL WINDOWS: not applicable
INTEGRITY VIOLATIONS: none introduced
OUTPUT PATHS AND LOAD MANIFESTS: unchanged

COMMANDS RUN (all read-only except the two Edit tool calls):
- grep and sed over docs/agent-runs/phase-11/prompt.md, docs/agent-runs/phase-11/review-report.md, and modules/spark/JOBS.md
- find modules/spark -name Canonical.scala, plus grep and sed over Canonical.scala and Clustering.scala
- Edit tool, twice, on modules/spark/JOBS.md
- git diff --stat -- modules/spark; git diff -- modules/spark/JOBS.md; git status --porcelain -- modules/spark. Result: 1 file changed, 2 insertions(+), 2 deletions(-). Only " M modules/spark/JOBS.md" under modules/spark.
- No aws commands, no network, no git writes. Nothing read or listed under labels/ or scratch/, and nothing modified there.

TEST RESULTS: sbt check was not run, because prompt.md Part F says this documentation-only part needs none. This part changed no Scala or sbt source. No Stop or SubagentStop hook message appeared during this part. If verify-tests-ran.sh fires later, it will be about other parts' Scala changes being newer than .claude/.last-test-run, which ruling 14 expects until work order 4. I did not touch .last-test-run and did not work around any hook.

VALIDATION RESULTS: The new wording was checked against Clustering.scala:117-123 and :155 and Canonical.scala:231, :294, and :332, as described above. The git diff shows only lines 469 and 470 of JOBS.md changed.

ASSUMPTIONS: The "Missing" cell on line 469 should still say that AuthorshipRecord.matchConfidence is not carried on the authored table. I only corrected what the nearest value means, and did not claim the field exists.
RISKS: None for code. The work-map identityConfidence (line 471) comes from the work clustering. I did not trace it separately, and its cell has no qualifier, so there is no wrong wording to fix.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code review 2 (code-reviewer) confirms finding 6 is resolved at modules/spark/JOBS.md:469-470.
````
