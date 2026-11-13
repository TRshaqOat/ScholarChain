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
