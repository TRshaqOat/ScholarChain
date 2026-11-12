# Phase 09 human gate: runtime collaboration-analysis agents (spec Phase 9)

Date: 2026-11-12
Human architect: Achyut Patel

## What was reviewed
- Agent reports for work orders 1 to 6 (agent-report.md), repair-01 parts A to D (repair-01.md), and repair-02 with ruling 30 (repair-02.md).
- Code reviews in review-report.md: review 1 REQUEST_CHANGES (F1 to F6, m1 to m9); review 2 REQUEST_CHANGES (N1 number words, N2 names not tied to cited evidence; n1 to n3); review 3 REQUEST_CHANGES (R3-1, R3-2; n4 to n7).
- Human sbt check on the final tree, run twice on 2026-11-11 (21:52:00 and 21:56:28): core 175, rag 80, storage 87, entityResolution 111, sparkAnalytics 43, hadoop 70, 0 failed (evidence/human-sbt-check-1.txt and -2.txt). Earlier human checks on the pre-repair-02 tree are kept as evidence/pre-repair02-human-sbt-check-*.txt.
- Fake benchmark, all 30 questions: repair-01 run-3250005c792bad74709a1b3c7f0a4814 and repair-02 run-c5befe8f45411f497ea0db779978e564; both 26 of 30 pass every check (B13, B15, B17, B24 abstain: FU2); verification 81 verified, 28 partially supported (context only), 0 unsupported, 0 conflicting; 0 claim changes between the two runs (evidence/fake-benchmark-summary.json, fake-benchmark-summary-repair02.json).
- Live benchmark after repair-02 (human-run, Haiku 4.5 and Titan V2 on the live vector file store; run-2f7b6b9ce82a37c0a80d892dc09422f8, runtime run-80e436dea5587ed42c03f98d66a05bb2): plan source MODEL 30; outcomes ANSWERED 15, NO_VERIFIED_CLAIM 6, BUDGET_EXHAUSTED 3, UNSUPPORTED_REQUEST 4, AMBIGUOUS_RESEARCHER 1, UNKNOWN_RESEARCHER 1; of 73 model claims, 30 VERIFIED, 40 UNSUPPORTED, 3 CONFLICTING; 61 Haiku requests (31 planning, 30 synthesis; 10 invalid outputs), 233,241 input and 40,466 output tokens; 24 Titan embedding calls; question latency p50 15.0 s, p95 28.7 s; graph retrieval p95 252 ms; semantic retrieval p95 1.7 s (evidence/live-benchmark-step1.txt, -step2.txt, live-benchmark-summary.json, live-benchmark-run/).
- Earlier live checks: the 2-question check before repair-01 (2026-11-07): every Haiku output failed validation (code fence), FALLBACK_RULES and BUDGET_EXHAUSTED (evidence/live-presmoke.txt). The 2-question check after repair-01 (2026-11-11 18:04): synthesis OK, planner rule-2 and rule-7 failures, which led to ruling 30 (evidence/pre-repair02-live-benchmark-step1.txt).
- Live adjudication demonstration (human-run, run-70a26cef3256da28ef1257cb4498d0bb, 200 seeded LLM-band pairs, after repair 1C): COMPLETE; RESEARCHER 33 ACCEPTED, 81 REJECTED, 66 UNRESOLVED; WORK 2 ACCEPTED, 11 REJECTED, 7 UNRESOLVED; 213 requests; 259,255 tokens; 13 schema failures (evidence/live-adjudication*.{txt,json,jsonl}, adjudication-policy-review-20261110.md). Two earlier attempts on 2026-11-07 sent roughly 300 uncached requests each, because every fenced reply was rejected silently (rulings 26, 27). Not evaluated against gold labels.

## Phase 9 gate (spec)
- [x] User text cannot become arbitrary database code (allowlisted plan operations; 17 fixed openCypher templates with bound parameters; no PartiQL or AWS call built from user text).
- [x] Ambiguous researcher names do not silently resolve to one identity (dominant-name-v1; B09 AMBIGUOUS and B12 UNKNOWN_RESEARCHER abstain in fake and live runs).
- [x] Every numerical and path claim has evidence IDs (the synthesis schema rejects a claim with no evidence IDs; each number must bind to an asserted fact of a cited item).
- [x] Unsupported claims are removed, weakened, or explicitly labelled. The verifier fails closed: in the live run it also removed 14 correct claims (review 3: R3-1, R3-2, n6), deferred to Phase 10 (ruling 31).
- [x] Prompt injection tests pass (removing any one escape fails a test; the OpenAlex text is waived, m3).
- [x] Deterministic graph retrieval and semantic retrieval are evaluated separately (the benchmark reports both).
- [ ] Code review returns APPROVE. Not met: review 3 REQUEST_CHANGES; accepted by human escalation (rulings 29, 31).

## Results
- Runtime: stages 1 to 10; LLM only in planner and synthesis (Haiku 4.5, at most 8 requests per minute, every request counted); verification is deterministic (verifier-v3); audit to file-backed QuerySession and EvidenceBundle under scratch/runtime/. Prompts planner-v2 and synthesis-v2 are the configured defaults.
- Resolution reproduces every Phase 7 decision (Dongarra 0.9576, Yves Robert 0.9775, Buyya 0.9645, Zomaya 0.9902, Guerraoui 0.9615, Foster 0.9680, Duato 0.9871; Lei Zhang and Wei Wang AMBIGUOUS; Elena Vasquez-Marlowe UNKNOWN).
- Paths: the bidirectional search reproduced all 54 stored path results; live B29 and B30 found the stored paths hop for hop, but no surviving claim cited them (R3-1).
- Live removals (review 3): 29 of 43 by design (mostly 21 ranking claims without rank.k.researcherId), 14 verifier defects (R3-1 11, R3-2 2, n6 1). The model misplanned B15, B18, B23, B24 (plan-operations FAIL); B13, B15, B17, B24 need FU2.
- Item-file reader (F6): 876,228 items indexed; peak heap 5,448 MiB at -Xmx6g beside the full graph fake.

## Human rulings recorded at the gate
- Rulings 1 to 31 in prompt.md.
- Ruling 27: one enclosing Markdown code fence is accepted in model replies (reverses a Phase 5 test expectation); prose stays rejected.
- Ruling 27 gate item: the 66 RESEARCHER UNRESOLVED decisions are 25 LLM_ABSTAIN and 41 LLM_LOW_CONFIDENCE. MatchPolicy.withAdjudication (unchanged since 77cafc7) keeps an ACCEPT below the accept threshold, a REJECT with 1 - confidence not below the reject threshold, and every ABSTAIN UNRESOLVED, by design. The progress-log tally of 24 abstentions is superseded by the persisted counts. Recording gap: low-confidence decisions keep neither the model's answer nor its confidence (residual risk; evidence/adjudication-policy-review-20261110.md).
- Ruling 28: synthesis-v2 default (repair-01 part D); SPARK_LOCAL_IP=127.0.0.1 allowed for agent sbt runs.
- Ruling 29: a narrow second repair round (repair-02) for N1 and N2, by human exception to the one-round rule.
- Ruling 30: live planner validation (messages name what is allowed; planner-v2 and its default), added to repair-02.
- Ruling 31: review 3 escalation; R3-1, R3-2, n1 to n7, the RUNTIME.md:418 line, the rank researcherId schema check, and the synthesis output limits go to the first Phase 10 rag-engineer and domain-engineer work orders.
- Ruling 24 decisions stand: FU1 deferred; FU2 and FU3 to Phase 10; M10 remainder open until cloud window 2; m6 and the core m9 space accepted; deleted probes stand as report claims.
- Benchmark category-to-operation map (RUNTIME.md section 22), reviewed by the human: accepted as a mechanical check rule, not a label.
- m3 waived: the OpenAlex injection text was not recorded; representative strings are used.

## Decision
ACCEPTED by human escalation (rulings 29 and 31). Review 2 REQUEST_CHANGES (N1, N2) led to a narrow second repair round by human exception; review 3 REQUEST_CHANGES (R3-1, R3-2, both fail closed) is deferred to the first Phase 10 rag-engineer work order.

## Manual changes
- No manual edits to agent-owned files.
- Per-file provenance: modules/rag and prompts/ (rag-engineer: WO1, WO4, WO5, repair 1B, repair-02, ruling 30); modules/core (domain-engineer: WO2, repair 1D, ruling 30); modules/storage (storage-engineer: WO3, repair 1A); modules/entity-resolution (entity-resolution-engineer: WO6, repair 1C). All agent-generated, accepted as above.
- Human-authored: prompt.md rulings, this gate, evidence copies (the human copied the agents' scratchpad logs into evidence/repair-1a, repair-1b, repair-1d, repair-02), and the live benchmark and adjudication runs.
- External assistance: on 2026-11-10 the human used Codex (OpenAI) as a Terminal assistant for the baseline sbt check, the AWS cost export, and the adjudication tally and policy note; Codex read the human-run output under scratch/er/adjudication-live/. The human checked the note on 2026-11-11.
- Process notes: guard false positives were handled with the Edit tool; repair 1A ran one read-only git status (disclosed, deviation from ruling 15); repair 1B edited RUNTIME.md with a python script inside its own paths; repair 1D split a guard-blocked tee command; repair-02 wrote logs under scratch/repair-02-logs/ and resumed after an API rate-limit interruption; agent shells needed SPARK_LOCAL_IP (the human Terminal did not).

## Remaining risks
- Live Neptune, DynamoDB, and S3 Vectors reads are untested until cloud window 2 (the runtime used local stores; only Bedrock calls were live).
- R3-1 and R3-2 remove correct claims: no live answer keeps a hop explanation; B04 and B30 abstain because of them. n4 to n7 open. Phase 10.
- Synthesis output cut off at 2000 tokens and claims without evidence IDs caused 3 BUDGET_EXHAUSTED questions. Phase 10.
- Corpus-level questions are unanswered until FU2. The model misplans some questions (B15, B18, B23, B24).
- Verification catches numbers, windows, names, titles, and hops, not qualitative misreadings.
- Dominant-name homonyms can attach a few works to a famous researcher (split identities).
- The live adjudication decisions are a demonstration set, not evaluated against gold labels; low-confidence records omit the model's answer.
- Haiku wraps JSON in a code fence; validation strips one fence, so other format drift still fails validation.
- Evidence gaps: repair 1C's sbt logs and repair 1A's mutation log were not kept; ruling 30's validator equivalence could not be diffed (Planner.scala untracked); session 1's deleted probes stand as report claims.

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
