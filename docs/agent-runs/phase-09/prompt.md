# Phase 09: runtime collaboration-analysis agents (spec Phase 9)

Session plan: session 1 (2026-11-06, overnight) runs work orders 1 to 6 and code review 1, in order. Phase 8 has no folder of its own: it was merged with Phase 7 under D-10 (docs/agent-runs/phase-07/, accepted commit 77cafc7). The human runs the live benchmark after session 1; repair-01 and review 2 follow in session 2. Each work order report is appended verbatim to agent-report.md under "## Work order N: <agent>", and each review to review-report.md, wrapped in a ````text fence.

## Human rulings before work order 1 (binding)

1. Scope and location. rag-engineer implements the spec Phase 9 workflow (stages 1 to 10) in modules/rag, with prompts and JSON schemas versioned wherever docs/agents.md assigns them to rag-engineer. No HTTP routes or services (Phase 10), no cloud resources, no Terraform. Runtime types (QueryPlan, EvidenceBundle, stage results) live in modules/rag; reuse core records (for example EvidenceItem and AnswerClaim) where they already exist. Core changes go through domain-engineer (work order 2).

2. Inputs and outputs.
   - Read-only inputs: the Phase 7 store outputs under scratch/stores/ (conversion run run-1d6a86491812ff94887270c1ab885e69: Neptune CSV, DynamoDB item files, coauthor evidence, candidates; benchmark path run run-ee4ffd21aa20dbe4c078a4be96fe8c9f; render run run-b6343d09bb981330d78f56fb8c3b94d0; fake vectors in scratch/stores/s3vectors from fake embed run run-13e2b27dca90db05937df33c4accb537).
   - Contracts: modules/storage/STORAGE.md, modules/rag/RAG.md and SELECTION.md, modules/spark/JOBS.md.
   - Nothing under scratch/stores/, scratch/spark/ or scratch/hadoop/ may be modified or deleted. scratch/stores-live/ (the human's live vectors) is never read, listed, or modified by agents; the live CLIs take its path as an argument.
   - Local runtime outputs (audit objects, file-backed QuerySession and EvidenceBundle tables, benchmark results) go under scratch/runtime/, with the snapshot and run ID in the path, deterministic run IDs, and a run manifest per run.

3. LLM use. Only QueryPlannerAgent and AnswerSynthesisAgent call an LLM: Claude Haiku 4.5 (us.anthropic.claude-haiku-4-5-20251001-v1:0) on Bedrock. Stages 2 to 7, 9 and 10 are deterministic code. ClaimVerificationAgent is deterministic in this version (no verification model): it checks claims against the EvidenceBundle by rule. SemanticContextAgent embeds the query with Titan V2 through the existing storage embedder. Budget per question: at most 2 Haiku calls, plus at most 1 bounded retry per call for malformed output, and at most 1 Titan call per index queried.

4. Planner controls.
   - QueryPlan is a versioned typed schema with an allowlisted operation enum (the spec Phase 9 list: researcher ranking, maximum team size, direct coauthors, shortest path, component lookup, community comparison, bridge ranking, temporal comparison, topic filter, institution filter, semantic explanation) and typed parameters.
   - Model output is parsed and validated against the schema. Invalid output gets one bounded retry, then the deterministic fallback planner or abstention.
   - Every extracted researcher name must occur in the question text. Window values are one of the three stored windows or an explicit year range.
   - Structured request fields (constraints, analysis, retrieval limits, as in the spec POST /v1/query body) override the model.
   - A deterministic rule-based planner exists for tests, fake mode, and fallback.
   - No stage builds Cypher, PartiQL, or AWS calls from user text. Graph access uses only the templates of ruling 7.

5. Researcher resolution (closes Phase 7 M9).
   - Runtime resolution applies dominant-name-v1 (modules/spark/JOBS.md, Phase 7 ruling 30): RESOLVED_DOMINANT (share at least 0.9 and at least 5 works, with the share stated), AMBIGUOUS (return every candidate with source identifiers, affiliations, coauthor context, and confidence), or UNKNOWN_RESEARCHER. One name normalization is shared with the EntityLinkCache key.
   - The resolver must reproduce the Phase 7 work order 6b decisions for every benchmark name. If the stored items lack something it needs (for example a works count per candidate), do not stop: implement it against a resolution port with a fake built read-only from the Phase 7 outputs, and report the exact item-file or schema change needed (spark-engineer, storage-engineer) as a follow-up.
   - AMBIGUOUS and UNKNOWN never resolve silently: the answer abstains or asks which researcher is meant.

6. Metrics, windows, rankings.
   - Metrics come only from ResearcherMetricCache (WINDOW#, CHANGE#), returned with metric version and run ID. Rankings come only from the RANKING# items (7 metrics x 3 windows, top 50). A request beyond the top 50, or for a metric with no stored ranking, says so.
   - Stored windows are all, 2000-2014, and 2015-2026. A requested range that is not a stored window uses the smallest stored window containing it, and the answer says so in an uncertainty line, never silently.
   - Topic, institution, or venue filters on a ranking apply only where the stored data supports them (for example filtering the stored top 50 by each researcher's topics); anything else is stated as unsupported. No composite score in this version.
   - Breadth (distinct coauthors), intensity (weighted degree), team size, connectivity (component), and bridge importance (cross-community degree, communities connected, participation coefficient; D-10) are distinct and named as such.

7. Graph reads (closes Phase 7 M12; storage-engineer, docs/agents.md exception 6, work order 3).
   - A GraphReadPort with fixed parameterized openCypher templates: direct coauthors of a researcher in a window; shortest coauthorship path between two researchers in a window (depth at most 6); component and community lookup; community neighbors and cross-community edges; node neighbors (for the Phase 10 neighbors endpoint). Every template has depth, result-count, and timeout limits from configuration. Parameters are bound, never concatenated.
   - Each hop returns researcher IDs, joint publication count, years, supporting work IDs (or the evidence pointer), and provenance (runId, snapshot, schemaVersion).
   - Fake: on the Phase 7 in-memory graph with the same limits; memory per Phase 7 ruling 27 (6g); tests use small fixtures.
   - Live: neptunedata openCypher queries, unit-tested on request building and response parsing only.

8. Evidence, synthesis, verification.
   - EvidenceBundle: bounded by a token budget from configuration, versioned, with stable evidence IDs. Deterministic graph evidence (metrics, paths, communities, publication support, identity confidence) is kept separate from descriptive semantic context (vector score, index, vector key, textRef, model version).
   - Vector similarity is never evidence for a coauthorship edge, a count, a path, a component, a community, or a bridge metric.
   - Synthesis returns typed AnswerClaim records; every material claim carries evidence IDs. It explains breadth versus intensity versus team size versus connectivity versus bridge importance when relevant, and states uncertainty and missing evidence. No output contains hidden chain-of-thought.
   - Verification returns VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, or CONFLICTING per the spec checks (identities, titles, counts, team sizes, path hops, communities, bridge scores, windows, every number). UNSUPPORTED and CONFLICTING claims are removed or explicitly labelled. With no verified claim, the answer abstains.

9. Untrusted text. The question and all retrieved text (rendered documents, titles, names, any dataset text) are data, never instructions, and prompts delimit them. Tests put injection strings in the question, in a retrieved document (including the injection text seen in an OpenAlex response, noted for the threat model), and in a researcher or paper name, and show that no plan operation, store call, or claim changes because of them.

10. Audit (stage 10). Write-once objects under the logs/ prefix (Phase 7 ruling 22), plus QuerySession and EvidenceBundle items (30-day TTL), holding the QueryPlan, resolved IDs, evidence IDs, metric versions, graph run ID, prompt and model versions, token counts where available, per-stage latency, verification result, and answer hash. Locally they go to file-backed fakes under scratch/runtime/. No secrets in any log.

11. Live calls and throttles.
   - Agents never call AWS. Every LLM and embedding client has a fake.
   - rag-engineer writes the live Haiku client in modules/rag, behind --live plus --profile (off by default), using the storage refreshing credentials provider and expired-token retry (Phase 7 ruling 35).
   - Haiku is throttled to at most 8 requests per minute (quota 10), Titan to at most 50. Every attempt, including retries, passes the throttle (Phase 7 review 2 N1).
   - The human runs the live benchmark from Terminal with the exact command work order 5 produces.

12. Benchmark suite.
   - Input: experiments/benchmark/benchmark-questions-v1.csv (Claude-drafted, human-reviewed), read-only.
   - Mechanical checks only: plan operations, resolution decisions (B09 AMBIGUOUS, B12 UNKNOWN_RESEARCHER, the resolved pairs resolve), abstention, verification status counts, evidence-bundle size, LLM call and token counts, and latency. Graph checks use stored data (for example a path claim's hops equal the path evidence in run-ee4ffd21aa20dbe4c078a4be96fe8c9f).
   - No answer ratings, relevance labels, or quality scores, by code or by any model. Those are the human's (Phase 16).
   - Deterministic graph retrieval and semantic retrieval are reported separately (spec Phase 9 gate).

13. Configuration. Every limit, budget, throttle rate, model ID, index name, prompt version, and timeout is a configuration key (domain-engineer, work order 2), not a code constant. Work order 2 also closes Phase 7 M10 (table prefix and Neptune loader role ARN as configuration keys).

14. Dependencies. No new libraries and no version changes; rag reaches the AWS SDK through storage. If something is missing, report BLOCKED-ON build-engineer with the exact artifact.

15. Constraints carried from Phase 7: no agent reads, writes, or lists anything under labels/. No git (only the orchestrator's read-only git status and git diff for the review change set). No aws commands. No network downloads except aws-docs MCP lookups. experiments/benchmark/ is read-only. Check free disk before long runs and stop below 5 GiB. When a guard blocks an edit, switch to the Edit tool; never work around it with a script.

16. Every report covers files created and changed, tests added, sbt check results for two runs, any BLOCKED-ON, and the spec Phase 9 report items that apply to that work order (prompt versions, schemas, allowed plan operations, identity-resolution behavior, graph query templates, verification rules, abstention behavior, benchmark results, known failure modes).

## Work order 1: rag-engineer (runtime contract)

Read rulings 1 to 16; spec Phase 9 and the sections Runtime microservice graph, REST API contract, Configuration, and Observability; modules/rag/RAG.md and SELECTION.md; modules/storage/STORAGE.md; modules/spark/JOBS.md (dominant-name-v1, rankings); and the core records. Report what already exists.
1. Write modules/rag/RUNTIME.md: the stage graph; each stage's input and output types; the QueryPlan schema and operation allowlist; the resolution policy (ruling 5); the EvidenceBundle schema and evidence-ID scheme; the AnswerClaim and verification rules; the abstention rules; the audit record; token and context budgets; prompt and schema versioning; fake and live modes.
2. List exactly:
   - the configuration keys work order 2 must add (name, type, default, reason);
   - any core record change needed;
   - the read operations work order 3 must provide (GraphReadPort templates with parameters, limits, and returned fields, and any other read port).
3. Add the runtime types with serialization round-trip tests in modules/rag. No stage logic yet.
Run sbt check twice.

## Work order 2: domain-engineer (runtime configuration and M10)

Read the work order 1 report. Add the configuration keys it lists, plus the table prefix and Neptune loader role ARN (Phase 7 M10), and any core record change it lists. Keep keys stable where possible. Report every new or renamed key and every downstream reference, without editing it. Update contract tests. Report whether config-version must change and why; do not change it without that reason.
Run sbt check twice.

## Work order 3: storage-engineer (graph read port, other read ports, review 2 minors)

Read the work order 1 and 2 reports and the Review 2 section of docs/agent-runs/phase-07/review-report.md (N1 to N6). Edit only modules/storage/**.
1. GraphReadPort and its openCypher templates (ruling 7), with the fake and the live adapter. Document in STORAGE.md each template, its parameters, limits, and returned fields.
2. Any other read port work order 1 lists.
3. Align with the work order 2 configuration keys, including M10.
4. Phase 7 review 2 minors: N1 (retries pass the throttle; fix the comment); N2 (a 403 on HeadObject gets one refresh-and-retry, or document why not); N3 (a test that detects the expired token by code alone); N4 (DynamoDB number range check; invalid number text returns Left); N5 (one refresh per expiry under concurrency); N6 (ValidateCli exits non-zero, or warns, when object-evidence edges are left untraced).
5. Tests per ruling 7, with no network.
Run sbt check twice. Report per Phase 7 ruling 16 for the new ports.

## Work order 4: rag-engineer (runtime stages 1 to 10)

Read the reports of work orders 1 to 3.
1. Implement stages 1 to 10 per RUNTIME.md and rulings 3 to 10, on the ports, with fakes for the LLM and the embedder.
2. Versioned prompts and JSON schemas for the planner and synthesis; validation of every model output; bounded retry.
3. The live Haiku client (ruling 11), unit-tested on request building and response parsing only.
4. A runtime CLI (runMain) that answers one question locally: fake mode by default; for the human, --live --profile with flags for the vector-store path, the store-file paths, and --per-minute.
5. Tests: plan validation and the allowlist; names absent from the question rejected; ambiguous and unknown names abstain; every numeric and path claim carries evidence IDs; each verification status; unsupported claims removed or labelled; semantic context never used as graph evidence; prompt injection (ruling 9); token budget enforcement; audit record contents.
Run sbt check twice. Report the ruling 16 items.

## Work order 5: rag-engineer (benchmark suite and live command)

Read the work order 4 report.
1. A benchmark CLI (runMain) that runs every question in experiments/benchmark/benchmark-questions-v1.csv through the runtime and writes per-question results and a summary under scratch/runtime/ (ruling 2), with the mechanical checks of ruling 12. It supports --max, resumes by question ID, and reports graph retrieval and semantic retrieval separately.
2. Run it in fake mode on all questions (fake LLM, fake embedder, the Phase 7 fake vectors and store files; heap at most 6g). Report the summary.
3. Produce, without running it, the exact Terminal command for the human's live run: profile scholarchain-sdk, us-east-2, --live, Haiku at most 8 per minute, Titan at most 50, the vector store scratch/stores-live/s3vectors, --max, resume, the expected time, and the expected Haiku and Titan call counts.
Run sbt check twice.

## Work order 6: entity-resolution-engineer (bounded live adjudication command)

Read docs/agent-runs/phase-05/ (prompt.md and the reports) for the adjudication design and the LLM band. Do not change match policy, thresholds, or decisions.
1. Check that the existing adjudication path can run live on a bounded, deterministic, seeded sample of about 200 LLM-band pairs: --max; a throttle of at most 8 per minute on every attempt; resume; output under scratch/ with a run ID; and refreshing credentials with one retry on an expired token (the Phase 7 ruling 35 pattern, implemented inside modules/entity-resolution; do not add a module dependency).
2. Fix only what item 1 needs, in modules/entity-resolution/**, with tests and no network.
3. Produce, without running it, the exact Terminal command, the expected time, and the call count.
Run sbt check twice. If this work order is blocked, the others continue.

## Code review 1: code-reviewer

Review the Phase 09 change set (modules/rag, any prompt or schema files, modules/core, modules/storage, and modules/entity-resolution changes) against work orders 1 to 6, rulings 1 to 16, spec Phase 9 (work order and gate), and D-10, D-12 and D-13. The orchestrator supplies the change set as in Phase 7.
Check especially:
- user text can never become database code or an AWS call; only allowlisted operations and bound template parameters;
- ambiguous and unknown researchers never resolve silently;
- every numeric and path claim has evidence IDs, and verification catches wrong numbers, hops, windows, and identities;
- unsupported claims are removed or labelled, and the answer abstains when nothing is verified;
- prompt injection tests are real (the injected text reaches the code path under test);
- graph and semantic evidence stay separate, and vector similarity is never graph evidence;
- live calls only behind --live and --profile, throttles on every attempt, and no test touches AWS;
- the benchmark suite produces no ratings or labels;
- no edits outside owned paths, no reads of labels/ or scratch/stores-live/, and the benchmark file unchanged.
Verify each candidate finding against the files. Give critical and major findings in full (severity, file and line, failure input, impact, owner) and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings after review 1 (binding)

Review 1 returned REQUEST_CHANGES with six major findings (F1 to F6) and minors m1 to m9. There is one repair round (repair-01, in parts A to C), then review 2. Rulings 1 to 16 still apply. Before this repair the human ran a 2-question live benchmark check (output in scratch/runtime-presmoke) and the live adjudication demonstration (run-70a26cef3256da28ef1257cb4498d0bb); their logs are in docs/agent-runs/phase-09/evidence/. Agents never rerun them and never read scratch/runtime-presmoke or scratch/er/adjudication-live.

17. F1 (claim numbers): accepted; fix in repair-01 part B.
    - Claims of every numeric kind must carry a non-empty values map; the synthesis output schema requires it. Bump the prompt and schema versions (synthesis-v2, synthesis-output-v2) if their text changes, and record the versions in RUNTIME.md.
    - Every number written in a claim must be bound to a values key that names a fact of a cited evidence item, and must equal that fact's stored value. Integers must be exact. A decimal passes if it equals the stored value, or the stored value rounded half-up to the number of decimals written, and only when at least 2 decimals are written (0.55 never verifies as 1).
    - Rank numbers verify only for a claim that asserts a rank, against that researcher's stored rank. Window bounds are not free numbers (ruling 18).
    - PATH_HOP checks every hop's from, to, joint publication count, and first and last collaboration years, as RUNTIME.md section 12 states.
    - Tests: review 1 failure inputs (a), (b), and (c) each end UNSUPPORTED or CONFLICTING.
18. F2 (windows): accepted. A window name or year range written in a claim, summary, or uncertainty line must match the claim's window field (or the answer's window for summary lines). First and last collaboration years asserted through values keys are facts, not windows. Test with the review 1 input.
19. F3 (semantic-only claims): accepted.
    - A claim citing only semantic evidence is never VERIFIED. If it names at most one researcher and states no count, relationship, path, component, community, or bridge measure, it is kept as PARTIALLY_SUPPORTED with a "context only" label; otherwise it is UNSUPPORTED and removed.
    - Abstention (NO_VERIFIED_CLAIM) and the benchmark's verification counts count only VERIFIED claims that cite graph evidence.
    - Test with the review 1 input.
20. F4 (identities): accepted.
    - Every researcher name written in a claim, the summary, or an uncertainty line must belong to a researcher in the bundle (resolved researchers, candidates, path hops, coauthor lists). A name of any other corpus researcher (looked up through the EntityLinkCache read port, never loaded whole) makes the claim UNSUPPORTED, or the line replaced.
    - Also add a conservative rule for name-like spans that are neither bundle names nor bundle titles, venues, topics, or institutions. Report its effect on the fake benchmark (claims that change status) and keep false positives explained.
    - Summary and uncertainty lines get the same numbers, windows, names, and titles checks as claims.
    - Tests: the review 1 input with a corpus name, and with a name absent from the corpus.
21. F5 (injection tests): accepted.
    - Fixtures contain <, >, &, ", and the closing tag of every prompt delimiter, in the question, a title, a researcher name, and a retrieved document. Assert that each built prompt has exactly one opening and one closing tag per block and contains the escaped forms.
    - Send an injected question through the model planner and the synthesis prompt builders with a recording fake model; check the captured prompt, and that no plan operation, store call, or claim changes.
    - Show that removing each escape call (Planner, Synthesis, Evidence) makes at least one test fail: remove each temporarily, report the failing test names, then restore.
    - m3: the OpenAlex injection text is not available, so the requirement is waived; tests use representative strings, and the threat model (Phase 17) records the waiver.
22. F6 (item-file reader): accepted.
    - Part A, storage-engineer: an indexed, read-only item-file reader that implements the existing key-value read port, in modules/storage, with tests and STORAGE.md. Peak heap beside the full graph fake stays under 6g; report the measured peak.
    - Part B, rag-engineer: switch AskCli and BenchmarkCli to it and delete ItemFileIndex.
23. Minors fixed in the same repair:
    - rag-engineer: m1 (stage timing records only stages that ran; others SKIPPED), m2 (a cut candidate list never yields "not in the graph"; it is AMBIGUOUS with a truncation note), m5 (every Bedrock request, including expired-token retries, is counted in LlmCall, llm-budget, and the stated maximum), m7 (the question deadline is checked before every stage and bounds graph and semantic calls), m8 (yearFrom greater than yearTo is INVALID_PLAN with a clear message), m9 (the stale Scaladoc in Planner).
    - entity-resolution-engineer: m4 (close the SDK client in Runner).
    - Haiku output validation (rag-engineer, part B; found by the human's 2-question live check on 2026-11-07). Bedrock calls succeeded (8 attempts returned, 19,870 input and 5,574 output tokens), but every planner and synthesis output failed validation, so both questions fell back to FALLBACK_RULES and abstained with BUDGET_EXHAUSTED. Read docs/agent-runs/phase-09/evidence/live-presmoke.txt and the per-question results and audit records under docs/agent-runs/phase-09/evidence/live-presmoke-runtime/ (read-only). The human's copy of the evidence records no validation failure reason anywhere (a search of live-presmoke-runtime for error, reason, message, and violation fields found only abstentionReason and reason BUDGET_EXHAUSTED), so the cause must come from the stored outputs, the log, and the code. Find the cause (for example response text extraction, code fences or prose around the JSON, a schema detail the model does not follow, or output cut off by max-output-tokens), and fix it. Record the validation failure reason of every rejected model output in the audit LlmCall (a short reason, never prompt text or retrieved text), so a later failure can be diagnosed. Add a test that feeds a realistic Haiku response, in the shape seen in the evidence where available, through parsing and validation. Prompt or schema text may change to fix format compliance (bump the version), but not to tune answer quality. If the evidence does not show the cause, add the diagnostic recording, fix every plausible cause the code shows, and say which.
    - Live results (both parts read their log first, read-only): part B reads docs/agent-runs/phase-09/evidence/live-presmoke.txt (the human's 2-question live check) and part C reads docs/agent-runs/phase-09/evidence/live-adjudication.txt (the human's live adjudication run). If a log shows an integration failure (Bedrock request or response format, credentials or expired tokens, schema validation, plan source FALLBACK_RULES, BUDGET_EXHAUSTED, a run that stopped FAILED), fix it within the part's own paths, with tests and no network, and quote the log lines it addresses. If the log shows a clean run, say so. A credential problem in shared storage code is reported as BLOCKED-ON storage-engineer. Do not tune prompts for answer quality; fix only integration and validation bugs. Agents never rerun either live command.
24. Decisions and residual risks (no repair):
    - FU1 (entity-link-v2 works count): deferred. The runtime counts works with graph.authored-counts, which reproduces the Phase 7 decisions. Revisit before cloud window 2 (live Neptune cost per candidate).
    - FU2 (window summaries for B13, B15, B17, B24): a Phase 10 work order (spark-engineer writes window-summary objects as a separate run; storage read port; rag answers from it). Until then those four abstention FAILs are known results.
    - FU3 (prompts packaging): Phase 10, build-engineer.
    - M10 remainder stays open until cloud window 2 (domain-engineer). m6 (adjudication sample defaults in code) and the m9 core space are accepted minors.
    - Accepted: the Haiku default for models.synthesis-model; analytics.ranking-size; C1 and C2; the work order 3 and 4 departures recorded in RUNTIME.md; the deleted probes stand as report claims (the work order 5 benchmark covers them).
    - The benchmark category-to-operation map (RUNTIME.md section 22) is a check rule, not a label; the human reviews it at the gate.
25. Review 2 covers repair-01 against rulings 17 to 24. Items in ruling 24 are not re-raised unless the repair made them worse.

## Repair 1 (repair-01)

Each part reads the Review 1 section of review-report.md and rulings 17 to 25, runs sbt check twice, and reports per ruling 16 plus what changed for each finding with file paths. If a change outside the part's own paths seems unavoidable, report BLOCKED-ON with the owner.

### Part A: storage-engineer
Edit only modules/storage/**. Ruling 22, part A.

### Part B: rag-engineer
Edit only modules/rag/** and prompts/**. Read the part A report first.
1. Rulings 17 to 21.
2. Ruling 22, part B.
3. Ruling 23, rag items.
4. Rerun the fake benchmark on all 30 questions as a new run under scratch/runtime/ (heap at most 6g). Report the summary next to run-d22ec2f83528a8e95067443cfb114bab, and list every claim whose status changed and why.
5. Update RUNTIME.md and, if the CLI flags changed, give the exact live benchmark commands again (two steps: --max 2, then --max 30).

### Part C: entity-resolution-engineer
Edit only modules/entity-resolution/**. Ruling 23, m4.

## Review 2: code-reviewer

Review the Phase 09 change set again; the orchestrator supplies it as for review 1. Focus on repair-01 against rulings 17 to 25. Verify, with file and line evidence, that F1 to F6 and the ruling 23 minors are resolved and tested without network access, and that each review 1 failure input now fails verification. Ruling 24 items are accepted: do not re-raise them unless the repair made them worse. Report any new critical or major finding in full and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human rulings before session 2 (binding)

26. Live adjudication hang (entity-resolution-engineer, repair-01 part C; found by the human on 2026-11-07).
    - The human's resumed live adjudication run (run-70a26cef3256da28ef1257cb4498d0bb) never completed a request. It sampled 200 pairs and logged its plan, then the main thread waited in Throttle.acquire (BedrockLlmClient.scala:148), through the sleep function at AdjudicateSample.scala:217, for over 30 minutes at about 0% CPU, before the first Bedrock call. No cache.jsonl was written. Credentials were valid (sts get-caller-identity through scholarchain-sdk succeeded; no export-credentials process was running). The first attempt on Nov 7 at 02:01 very likely stopped the same way, since it also left no cache. The human stopped the run. Evidence (read-only): docs/agent-runs/phase-09/evidence/live-adjudication.txt and the thread dump docs/agent-runs/phase-09/evidence/live-adjudication-threads-9341.txt.
    - Find and fix the cause (for example a wait computed from mixed clocks or units, nanoseconds passed where milliseconds are expected, or an uninitialised last-call time). Add a test that runs the throttle with the real clock and real sleep, no network, and shows the first acquire returns at once and later acquires are spaced as configured (a short test at a high per-minute rate is fine), and a test that the sleep function receives the unit it expects.
    - Check that each answer is appended to cache.jsonl as soon as it returns, and add a test that a run stopped partway resumes from the partial cache without repeating answered pairs.
    - Report the cause with file and line, and give the Terminal command for the human's rerun (unchanged, or corrected if flags changed). The human reruns the live adjudication after review 2; agents never run it.
    - Review 2 checks this ruling together with rulings 17 to 25.
