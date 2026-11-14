# Phase 11 prompt: adversarial testing and full repository review (spec Phase 11)

## Session plan
- Session 1: work orders 1 to 3, in order: 1 service-engineer, 2 rag-engineer, 3 test-engineer.
- Session 2: code review 1 (code-reviewer, full repository).
- Then repair-01 (one part per owner), a test-engineer rerun of the relevant adversarial tests, and code review 2. After that, the exit is human escalation.
- Phase 10 is accepted (docs/agent-runs/phase-10/commit.txt). Phase 11 source changes stay uncommitted until the gate.

## Human rulings before work order 1

1. Scope: the repository at the Phase 10 accepted commit, plus the Phase 11 changes. Carried Phase 10 items: SaturationSpec timing flake (Phase 10 ruling 25), review 2 minors N1 to N5 (Phase 10 ruling 24), the 15 removed live claims of the Phase 10 gate run (Phase 10 ruling 25), and the Phase 10 adversarial targets: API authentication bypass, idempotency races, job redelivery, unbounded graph requests, injection through every text field, LLM budget exhaustion, queue flooding, and window-summary object tampering.
2. No live AWS in Phase 11. Agents make no AWS or Bedrock calls (live calls are human-run only). test-engineer may write tests tagged RequiresAws; they stay excluded from check and are not run. Bounded live checks move to cloud window 2.
3. Defect-exposing tests. test-engineer never edits production code. A test that exposes a production defect must fail without the fix; it is then wrapped in ScalaTest pendingUntilFixed, so sbt check stays green and the defect shows as pending. Each one is listed in the report with owner, file and line, and failure input. In repair, the owner fixes the defect and removes the wrapper; the test must then pass.
4. test-engineer territory: tests/** (the tests sbt project) and new test files under modules/*/src/test/scala/**/adversarial/. It does not edit existing test files, production code, build files, configuration, prompts, or documentation outside its own TESTS.md (tests/TESTS.md). It adds no dependency; if one is needed, record BLOCKED-ON build-engineer and continue.
5. Test quality: every new test is deterministic (no wall-clock margins; use latches, injected clocks, and fakes), binds only 127.0.0.1:0, uses JVM temporary directories, and makes no network call. New suites are run 3 times before the report. Keep a full sbt check under about 8 minutes (now about 4.5).
6. Coverage report: for every category in the spec Phase 11 test work order and every ruling 1 target, list the test names, their result (pass, pendingUntilFixed, or not covered), and the requirement they cover. Name every production behavior found with no relevant test.
7. Constraints for every agent: no reads, writes, or listing under labels/, scratch/stores-live/, or scratch/er/adjudication-live/; no git commands; no aws commands; no network downloads except aws-docs MCP lookups; each agent edits only its owned paths; nothing existing under scratch/ modified or deleted; new outputs only under scratch/phase-11-logs/ (cited logs) and scratch/runtime/phase-11/; experiments/benchmark/ and docs/agent-runs/** read-only (except the orchestrator's report appends); SPARK_LOCAL_IP=127.0.0.1 for every sbt run; heap at most 6g; use the Edit tool when a guard blocks; stop below 5 GiB free disk; stop any background shell and local service as soon as it finishes.
8. Code review 1 reviews the complete repository and the Phase 11 change set. It also reads the "Remaining risks" sections of docs/agent-runs/phase-03 to phase-10 human-gate.md and reports which carried items are still open, verified against the files (a carried item becomes a finding only when verified).
9. One repair round, grouped by owner. Minor findings may become residual risks. After the repair, test-engineer reruns the relevant adversarial tests (and removes no wrapper itself), then code review 2.
10. code-reviewer and test-engineer run without Ponytail.

## Work order 1: service-engineer (Phase 10 carry-forward)

Read Phase 10 review-report.md (code review 2), Phase 10 repair-01.md Part A, and Phase 10 prompt.md rulings 24 and 25. Edit only modules/services/**.
1. Make SaturationSpec deterministic: replace wall-clock margins with latches or explicit signals so the result does not depend on machine load. Run the suite 10 times (all must pass), and rerun the repair-01 disable checks D0 to D4 (each must still fail a named test).
2. N1: correct SERVICES.md:22 (the graph-evidence-api workflow pool size the code creates).
3. N2: a test that fails if main/GraphEvidenceApi stops passing answers = false.
4. N3: document in SERVICES.md and in the openapi.yaml 503 description that a sync POST answered 503 OVERLOADED keeps its Idempotency-Key claimed until the TTL (release deferred by Phase 10 ruling 18); contract tests must pass.
5. N4: document in SERVICES.md section 3 that a worker without a free slot keeps the message, so repeated misses can move it to the DLQ while the job stays QUEUED until its TTL.
Run sbt check twice.

## Work order 2: rag-engineer (Phase 10 carry-forward)

Read Phase 10 prompt.md ruling 19 (19b method) and ruling 25, the Phase 10 WO9 report, and repair-01 Part B. Edit only modules/rag/**.
1. Read the 15 removed claims in the audits of runtime run-422d912ac162f340f5f31a1b63b65e19 under scratch/runtime/s3/logs/rag-runtime/ (failing rules: numbers-in-text 13, identity 2, context-only 1). Classify each as removed by design or a verifier defect, as in Phase 10 ruling 19b. Fix clear defects, each with a test built from the failure shape using fixture names (never copy model text into tests or logs). Report the table (question, claim kind, failing rule, by design or defect, fixed or not), with B17 and B24 explicitly.
2. N5: add a disable check for the word-before-year order in Verifier numbersIn alone; if no test fails, strengthen a test until one does.
3. If the verifier changes, bump its version, rerun the fake benchmark as a new run under scratch/runtime/phase-11/, and report it next to Phase 10 run-396fa84df47be7a2579b491bec64356c. No live calls (the human reruns the live benchmark at the gate).
Run sbt check twice.

## Work order 3: test-engineer (spec Phase 11 test work order)

Use the test-engineer agent on the complete ScholarChain repository produced through Phase 10, plus the work order 1 and 2 changes.

Add adversarial tests without modifying production code.

Required categories:
- Malformed and truncated DBLP XML.
- Unicode normalization and confusable characters.
- Duplicate and conflicting DOI records.
- Common author names and reordered author lists.
- Very large blocking groups.
- Coauthor edges with unresolved or conflicting researcher endpoints.
- Empty and disconnected graphs.
- Distinct-degree, component, shortest-path, bridge, and k-core known-graph cases.
- Large-team pair expansion and common-name researcher-resolution cases.
- Missing topic, institution, abstract, or researcher-profile metadata.
- Duplicate vector keys and stale model versions.
- Prompt injection in title, abstract, and full-text chunks.
- LLM malformed JSON and fabricated identifiers.
- Unsupported answer claims.
- Neptune timeout.
- DynamoDB conditional-write conflict.
- S3 Vectors throttling or transient failure.
- Replayed workflow and idempotency.
- API authentication, authorization, malformed bodies, and timeouts.
- Logging redaction.
- Configuration omissions.
Plus every ruling 1 target.

Run all tests that do not require live AWS (ruling 2: no AWS integration tests run in Phase 11).
Leave any production defect exposed by a failing test, wrapped per ruling 3. Report it to the owning agent instead of changing production code.
Write tests/TESTS.md (categories, suites, how to run). Run sbt check twice.
Report exact test counts, failures, the pendingUntilFixed list (owner, file and line, failure input), coverage by requirement (ruling 6), and residual risks.

## Code review 1: code-reviewer (spec Phase 11 review work order)

Use the code-reviewer agent to perform an adversarial read-only review of the complete repository and the full current diff (the Phase 11 change set).

Review in this order:
1. Correctness and data loss.
2. Contract and schema drift.
3. Artifact ownership violations.
4. Security, secrets, IAM assumptions, and injection risks.
5. Hadoop and Spark distribution mistakes.
6. Entity-resolution over-merging and unreported uncertainty.
7. Storage idempotency and provenance.
8. RAG grounding and unsupported claims.
9. API behavior and failure handling.
10. Tests that would pass even if the behavior were broken.
11. Documentation claims not supported by implementation.
12. Cost and cleanup risks.

Also check: test-engineer edited no production code and no existing test file; every pendingUntilFixed test fails for its stated reason; the work order 1 SaturationSpec change removes wall-clock dependence; the work order 2 classification table; and the carried items of ruling 8.

Verify every candidate finding against the actual files before reporting it.
For each finding provide severity, file and line, concrete failure input, expected impact, and owning agent.
Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings after session 1

11. test-engineer territory (amends ruling 4): tests/** only. guard-paths.sh is correct; ruling 4's grant of modules/*/src/test/scala/**/adversarial/ is withdrawn. No factory change.
12. Spark and Hadoop adversarial coverage (the WO3 BLOCKED-ON build-engineer item). In repair-01, build-engineer gives the tests project the Spark and Hadoop libraries on its Test classpath, plus the fork and JVM options the Spark and Hadoop module tests use. No production scope and no job jar may change. In the test-engineer rerun after repair-01, test-engineer adds the uncovered categories: components, bridges, k-core, and communities known-graph cases; empty and disconnected graphs on the Spark side; CoauthorGraph unresolved or conflicting endpoints; and the DBLP record reader (truncation, splits, and "<article " inside an XML comment). A full sbt check stays under about 8 minutes.
13. The pending defects D1 to D13 from WO3 are routed with code review 1's findings in repair-01. Code review 1 confirms or changes each one's severity and owner. Recorded deviation: in WO2, a read-only find walked the directory entries of scratch/stores-live/ and scratch/er/adjudication-live/ without pruning them; nothing from them was printed or read. Any later find over scratch/ must prune both paths.

## Human rulings after code review 1

14. Pending tests during repair (amends rulings 3 and 9). Owners never edit tests/** (ruling 11). Each owner fixes its D items in production code and adds its own module test built from the same failure input; it must fail without the fix (a logged disable check with sha256 restore). In an owner's sbt check, the only accepted failures are tests/** pendingUntilFixed tests of D items that this or an earlier repair-01 part reports fixed; they fail because the defect is fixed. The report lists them by name. Any other failure is not accepted. markTestRun will not write the marker while they remain; that is expected. Do not work around any hook; if a hook stops a part from finishing, record BLOCKED-ON human and continue with the next part. Work order 4 (test-engineer) removes those wrappers and restores a fully passing check.
15. Residual D items and stable IDs. D6, D7, and D9 are residual risks with no fix in Phase 11: each only loses recall (none over-merges), and their fixes would change Phase 4 to 8 outputs or stable doi: IDs. Their wrappers stay and are listed at the gate. Any other fix (D8, code review 1 finding 1) must not change any stable ID, normalized key, or stored record of data already produced in Phases 4 to 8. The owner measures this read-only over the existing local outputs (prune scratch/stores-live/ and scratch/er/adjudication-live/ in any find). If any existing record would change, the owner reports BLOCKED-ON human with the counts and does not apply that fix.
16. Repair-01 routing follows code review 1 section 7, except: D6, D7, D9 per ruling 15 (no hadoop-engineer or entity-resolution-engineer part); finding 2 is fixed by aligning the core value to 10 (no key removal, no config-version bump); the optional storage read retry for D4 is not done (rag's degrade is the fix). Code review 1 section 5 tests owned by test-engineer are strengthened in work order 4; the CorpusQuerySpec item goes to Part C.
17. Work order 4 (test-engineer rerun) runs in the same session after the repair-01 parts.
18. The files under docs/agent-runs/phase-11/evidence/ were copied there by the human from Terminal; no agent wrote them.

## Repair 1 (repair-01)

One session, in order: Part A build-engineer, Part B rag-engineer, Part C service-engineer, Part D domain-engineer, Part E storage-engineer, Part F spark-engineer, then work order 4 (test-engineer). Before each part, check docs/agent-runs/phase-11/repair-01.md for its heading and skip it if present; before work order 4, check agent-report.md for "## Work order 4". Append each part report verbatim to repair-01.md under "## Part X: <agent>", wrapped in a ````text fence. Ruling 7 constraints apply. Cited logs go under scratch/phase-11-logs/repair-01/ (work order 4: scratch/phase-11-logs/rerun/). Every fix gets the owner's own module test that fails without it (a logged disable check, sha256 restore). Each part with code changes runs sbt check twice; ruling 14 says which failures are accepted.

### Part A: build-engineer (ruling 12)

Read the WO3 report (its BLOCKED-ON build-engineer item) and code review 1. Edit only build.sbt and project/**. Put the Spark and Hadoop libraries on the tests project's Test classpath, with the fork and JVM options the sparkAnalytics and hadoop module tests use. No production scope, no job jar, and no library version may change. Report `show tests/Test/fork`, `show tests/Test/javaOptions`, and that tests/Test/fullClasspath contains the Spark SQL and Hadoop artifacts. Keep a full sbt check under about 8 minutes.

### Part B: rag-engineer (D1, D2, D3, D4; finding 5)

Read code review 1 (sections 3 and 7, finding 5) and the WO2 and WO3 reports. Edit only modules/rag/**.
1. D1, D2, D3: quantity words ("hundreds", "dozens", "scores", "a dozen"), comparison qualifiers ("more than", "fewer than", "over", "under", "at least", "at most", "nearly", "about", and similar), and negations ("does not have", "not N", "it is false that", "never", and similar) must not pass as VERIFIED unless the asserted value makes the statement exactly true; when in doubt the claim is removed. Tests from the WO3 failure inputs with fixture names, plus controls that must still verify (the template wordings and the Phase 10 and 11 live-shaped fixtures). Bump the verifier version.
2. D4: a transient error or a Left from the vector query degrades SEMANTIC_CONTEXT to a missing-evidence note, as an embedding failure does, and the question continues on graph evidence. Test.
3. Finding 5: fix RUNTIME.md:509 and any other line that contradicts the verifier.
4. Rerun the fake benchmark as a new run under scratch/runtime/phase-11/repair-01/ and report it next to run-7730f2f6f85f3bc22754e299a2136e16, explaining every changed claim. No live calls.

### Part C: service-engineer (D5; finding 3; CorpusQuerySpec main)

Read code review 1 (D5, finding 3, section 5) and the WO1 and WO3 reports. Edit only modules/services/**.
1. D5: never drain or wait on an unread request body before responding, and add a request read or idle timeout, so a slow or missing body cannot pin an HTTP thread, authenticated or not. A module test over a raw socket that fails without the fix. Document the timeout in SERVICES.md.
2. Finding 3: make BuildingBlocksSpec:113 and :118-122 and GraphEvidenceApiSpec:211 deterministic (latches or explicit signals, no sleeps for ordering), and correct the SaturationSpec scaladoc. Run each changed suite 10 times.
3. A test that fails if main/GraphEvidenceApi stops using answers = false (main, not only start).
Contract tests must pass.

### Part D: domain-engineer (D8, D11, D12, D13; findings 1 and 2)

Read code review 1 and the WO3 report. Edit only modules/core/**.
1. D8: normalizeDoi becomes idempotent, with no percent-decoding (D9 stays residual, ruling 15). Ruling 15 measurement.
2. D11: a null index entry is refused. D12: a blank or empty value (from a file or an environment variable) is refused for the keys WO3 lists when the environment is not local. D13: a fractional value for an integer key is refused, never truncated.
3. Finding 1: AuthorshipRecord position must be at least 1 (ruling 15 measurement over the existing canonical/authored outputs).
4. Finding 2: set the core analytics.community-max-iterations to 10, and say in its comment that the Spark jobs read scholarchain-spark.conf. No key removal and no config-version bump.

### Part E: storage-engineer (D10)

Read code review 1 (D10) and the WO3 report. Edit only modules/storage/**. Window-summary rows must satisfy the count identities a real summary satisfies, for example largestComponentSize <= researchers - (components - 1), largestCommunitySize <= researchers - (communities - 1), communitiesAtLeastMinSize x communityMinSize <= researchers, and communitiesAtLeastMinSize = 0 when largestCommunitySize < communityMinSize. The existing loaded object under scratch/runtime/wo8/s3 (read-only) must still open. Test.

### Part F: spark-engineer (finding 6)

Edit only modules/spark/JOBS.md. Lines 469-470 must describe matchConfidence and identityConfidence as the cluster's minimum accepted-edge confidence, as JOBS.md:42 and Canonical.scala do. Documentation only, so no sbt check.

## Work order 4: test-engineer (rerun after repair-01)

Read code review 1 (sections 3, 4, 5, and 7), repair-01.md in full, and your WO3 report. Edit only tests/**.
1. Ruling 14: remove the wrapper of every D item a repair part reports fixed; each test must now pass. Keep the wrappers of D6, D7, and D9 (ruling 15) and of any item not fixed, and report them.
2. Finding 4: make GraphBoundsTimeoutAdversarialSpec :150 and :179 deterministic. Finding 7: fix TESTS.md:86.
3. The ruling 12 categories, if Part A put Spark and Hadoop on the classpath; otherwise record BLOCKED-ON build-engineer.
4. The Phase 06 m5 MatchPath contract test.
5. Strengthen the code review 1 section 5 tests you own: RagModelOutput :151-179 and :181-207, StoreFailure :145-176, DblpXml :86-90, ApiBody :106-151, WorkerRedelivery :166-207, GraphReadKnownGraph :128-135.
6. Tests for the WO3 "untested production behaviours" that repair-01 fixed.
7. Update tests/TESTS.md. Run every adversarial suite 3 times. Run sbt check twice: no failures, and pending only for the kept wrappers.
Report exact counts, wrappers removed and kept, new suites per category, coverage by requirement (ruling 6), and residual risks.

## Code review 2: code-reviewer

Review the repair-01 changes and work order 4 against code review 1's findings, D1 to D13, and rulings 14 to 18. For each finding: resolved or not, with file and line and the test that fails without the fix (or residual by ruling 15). Check that repair-01 edited nothing outside owned paths; that no fix changed a stable ID or stored record of existing data (ruling 15); that test-engineer edited no production code; that every remaining pendingUntilFixed wrapper is justified; and that the spec Phase 11 gate items hold. Verify each candidate finding against the files. Give critical and major findings in full (severity, file and line, failure input, impact, owner) and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings after repair-01 and work order 4

19. D8 stays a residual risk, with D6, D7 and D9 (ruling 15). Part D's measurement: an idempotent normalizeDoi would change 36 stored ORCID workDoi values in 26 researcher-profile records of Hadoop run run-035e75abfa6798c938cbd534273dbd8d (j02-source-normalize/researcher-profiles); no work ID or key would change. A DOI with a leading space only fails to match, so it loses recall and never over-merges. Part D made no D8 change. Its tests/** wrapper (IdentityAdversarialSpec, "DOI normalization is idempotent") stays and is listed at the gate.
20. D14 is confirmed major (owner rag-engineer). This is a human exception to the one-repair-round rule (ruling 9): one small repair round, repair-02, fixes D14 only, and work order 5 (test-engineer) follows in the same session. Code review 2 then covers repair-01, work order 4, repair-02 and work order 5. If code review 2 returns REQUEST_CHANGES, the exit is human escalation. Ruling 14 applies to repair-02: the only accepted sbt check failure is the D14 tests/** wrapper.
21. D15 and D16 (owner service-engineer) and D17 (owner hadoop-engineer) are minor residual risks, carried to the Phase 12+ backlog. Their wrappers stay and are listed at the gate. D17 measurement, made by the human (read-only grep for "<!--" from Terminal): 0 comments in datasets/samples/dblp/dblp-sample.xml; in the full works.xml (61M) and persons.xml (27M) under scratch/datasets/dblp/snapshot=2026-10-03/full/; in the sample works.xml and persons.xml; and in the full raw dblp-2026-10-03.xml.gz (1.0G). The 32 MB dblp-2026-10-03.prefix-33554432.xml.gz is a truncated byte prefix (gzip reports "unexpected end of file", as expected) and has 0 in its readable part. D17 therefore affects no existing output.
22. Accepted deviations. First, the four guard blocks in repair-01 Parts B, C and E and in work order 4. Each was a pattern match on a read-only command or on test code, and nothing was written to a protected path; the step was redone with the Write or Edit tool, or only its read-only parts were rerun. Second, Part D's `du -sh scratch`, which walked directory entries under scratch/stores-live/ and scratch/er/adjudication-live/ for a size total without printing or reading anything from them (the same kind as ruling 13). From now on, no du or find runs over scratch/ without pruning both paths.
23. Code review 2 scope (amends "## Code review 2: code-reviewer"). It reviews repair-01, work order 4, repair-02 and work order 5 against code review 1, D1 to D17, and rulings 14 to 23. The justified pendingUntilFixed wrappers are D6, D7, D8 and D9 (rulings 15 and 19) and D15, D16 and D17 (ruling 21). It also confirms or changes the severity and owner of D14 to D17.

## Repair 2 (repair-02, human exception per ruling 20)

One session: Part A rag-engineer, then work order 5 (test-engineer). Before Part A, check docs/agent-runs/phase-11/repair-02.md for "## Part A: rag-engineer" and skip the part if it is present. Before work order 5, check agent-report.md for "## Work order 5" and skip it if present. Append the Part A report verbatim to repair-02.md under "## Part A: rag-engineer", wrapped in a ````text fence. Ruling 7 constraints apply. Cited logs go under scratch/phase-11-logs/repair-02/ (work order 5: scratch/phase-11-logs/rerun-2/).

### Part A: rag-engineer (D14)

Read the work order 4 report (D14), repair-01 Part B, and RUNTIME.md sections 12 and 21. Edit only modules/rag/**.
1. D14: "short of 10", "falls short of 10" and "lacks 10", checked against a stored 10, must not pass as VERIFIED. Close the same families, not only these words. That means comparison phrases such as "short of", "shy of", "below", "above", "beyond", "in excess of", "upwards of" and "exceeds"/"exceeding", and negation words such as "lack"/"lacks"/"lacking"/"lacked", "without" and "absent". Keep repair-01's exceptions (a bound fact's own direction, ratios, "not converge") and keep the template wordings verified. Write module tests from the work order 4 failure inputs, plus controls. The owner's own test must fail without the fix (a logged disable check with sha256 restore).
2. Bump the verifier version from verifier-v7 to verifier-v8. Update RUNTIME.md: the section 12 rules, the section 21 known failure modes, and the version lines.
3. Rerun the fake benchmark as a new run under scratch/runtime/phase-11/repair-02/. Report it next to run-6acd1b5b8b39225000e9599b827f369d and explain every changed claim. No live calls.
4. Run sbt check twice. By ruling 14, the only accepted failure is the D14 wrapper at UnsupportedClaimAdversarialSpec.scala:196.

## Work order 5: test-engineer (rerun after repair-02)

Read the work order 4 report and repair-02.md in full. Edit only tests/**.
1. Remove the D14 wrapper; the test must now pass. Keep the wrappers of D6, D7, D8, D9, D15, D16 and D17.
2. Add verifier-v8 breadth cases for the new wordings repair-02 reports (these must not verify), plus exact-value controls (these must verify).
3. Update tests/TESTS.md: counts, D14 fixed, rulings 19 to 23.
4. Run UnsupportedClaimAdversarialSpec and RagModelOutputAdversarialSpec 3 times each. Run sbt check twice: no failures, and pending only for the 7 kept wrappers.
Report exact counts, the wrappers removed and kept, the new tests, any new defect (wrapped, with proposed severity and owner), and residual risks.
