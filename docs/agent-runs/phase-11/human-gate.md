# Phase 11 human gate: adversarial testing and full repository review (spec Phase 11)

Date: 2026-11-14
Human architect: Achyut Patel

## What was reviewed
- Agent reports for work orders 1 to 5 (agent-report.md): WO1 service-engineer, WO2 rag-engineer, WO3 test-engineer, and WO4 and WO5 test-engineer reruns.
- Repair-01 Parts A to F (repair-01.md): build-engineer, rag-engineer, service-engineer, domain-engineer, storage-engineer, spark-engineer.
- Repair-02 Part A (repair-02.md): rag-engineer, by human exception (ruling 20).
- Code review 1: REQUEST_CHANGES (D1 to D5 major; D6 to D13 and findings 1 to 7 minor).
- Code review 2: REQUEST_CHANGES (review-report.md; change set in evidence/code-review-2/). No new critical or major finding. Every repaired item is resolved, with an owner test and a logged disable check. D18 is confirmed major and open (residual, ruling 24). The gate item "every production behavior has a relevant test" does not hold. New minors N1 to N3. Severities and owners of D14 to D20 confirmed as proposed.
- Human sbt check, run twice: core 190, entityResolution 111, storage 109, rag 116, services 47, sparkAnalytics 46, hadoop 70, tests 141 (pending 10); 840 tests, 0 failed; about 260 s each; markTestRun wrote the marker (evidence/human-sbt-check-1.txt and -2.txt).
- Phase 10 carry-forward:
  - SaturationSpec is deterministic (10/10).
  - N1 to N5 are closed.
  - The 15 removed live claims are classified: 12 by design, and 3 defects fixed (verifier-v6).
- Adversarial tests:
  - 151 tests in 20 suites under tests/** (WO3: 108; WO4: 143; WO5: 151). 141 pass and 10 are pending.
  - Fixed: D1 to D5 and D10 to D14.
  - Residual: D6 to D9 and D15 to D20 (rulings 15, 19, 21, 24).
- Fake benchmarks: run-7730f2f6f85f3bc22754e299a2136e16 (verifier-v6), run-6acd1b5b8b39225000e9599b827f369d (v7) and run-472a394363c6a23e79d4d33c2fa58416 (v8) are identical: 27 answered, 91 verified / 28 partial / 0 unsupported / 0 conflicting, 30/30 checks.
- Live benchmark on the final tree (verifier-v8): run-aad7216cb2b1500996f3403a174cc11d, runtime run-da948201a77e0fe136727d99a55c2b89 (evidence/gate-live-benchmark-*, gate-live-benchmark-run/, gate-live-llm-failures.txt, gate-live-claim-rules.txt, gate-live-removed-claims.txt).
  - Outcomes: ANSWERED 26, NO_VERIFIED_CLAIM 1 (B15 misplan), AMBIGUOUS_RESEARCHER 1, UNKNOWN_RESEARCHER 1, UNSUPPORTED_REQUEST 1. Phase 10 final: ANSWERED 24, NO_VERIFIED_CLAIM 3.
  - Claims: 87 verified, 0 partial, 12 unsupported, 0 conflicting.
  - Haiku: 60 requests (31 planning, 29 synthesis), 219,940 tokens in, 39,429 out. 3 INVALID_OUTPUT, all recovered on retry: B27 planning with no operations; B29 output cut off at max_tokens (ruling 19a cut-off retry); B23 a claim with no evidence IDs. Titan: 24 embedding calls.
  - Latency: p50 14,942 ms, p95 29,927 ms, max 39,804 ms.
  - Checks: plan-operations fails B15, B18, B23, B24 (carried Phase 9 misplans); abstention fails B15; path-hops fails B29 (the runtime path matches the stored 1-hop path but no final claim cites it; no B29 claim was removed, so synthesis left the path out after the cut-off retry).
  - Removed claims, classified by the human from verifier rule names (code review, not labelling): all 12 by design, 0 defects. B05 1 (identity: owner named from an uncited record); B16 1 (asserted-values: memberCount on no cited item; identity: member names not resolved or cited); B18 1 (identity and context-only: topic-like names not in the evidence); B26 9 (numbers-in-text: positions 2 to 10 of a filtered list not bound to stored ranks). B05, B18 and B26 match the WO2 by-design classes. No claim was removed by the verifier-v7 or v8 quantity, qualifier, negation or comparison rules. The three Phase 10 defects (B13 and B24 percentages, B17 measure word) no longer cause removals.

## Phase 11 gate (spec)
- [ ] No critical or major verified finding remains. (Does not hold: D18, major, confirmed by code review 2, is open as a residual risk under ruling 24. Accepted by human escalation, ruling 27.)
- [ ] Every production behavior has a relevant test. (Does not hold: the 10 untested behaviours in the WO4 and WO5 reports plus N2, listed under remaining risks. Accepted by human escalation, ruling 27.)
- [x] All tests pass under their declared profiles. (840 tests, 0 failed, 10 pending known defects, in both human checks. RequiresAws tests were not run: agents had no live AWS in Phase 11, ruling 2.)
- [x] No reviewer wrote production code.
- [x] Repair records identify owners and rerun evidence.

## Results
- Test counts: core 190, entityResolution 111, storage 109, rag 116, services 47, sparkAnalytics 46, hadoop 70, tests 151 (141 passed; 10 pending: D6 to D9, D15 to D20); 840 in total.
- Coverage by requirement: tests/TESTS.md and the WO3 to WO5 reports.
- Verifier: verifier-v8. Live removal rate: 12 of 99, all by design, compared with Phase 10's final 15 of 100 (12 by design, 3 defects). Live recall cost of the verifier-v7 and v8 rules in this run: 0 claims.
- Services: a 30 s request read timeout and a 1 s unread-body drain bound (SERVICES.md section 6).

## Human rulings recorded at the gate
- Rulings 1 to 27 are in prompt.md.
- BLOCKED-ON items:
  - WO3, guard path and build-engineer classpath: rulings 11 and 12.
  - Repair-01 Part D, D8: ruling 19.
- Repair rounds: repair-01, and repair-02 by human exception for D14 (ruling 20).
- Code review 2 exit: human escalation (rulings 26 and 27).
- Residual:
  - D6, D7 and D9 (ruling 15).
  - D8 (ruling 19).
  - D15 to D17 (ruling 21). The human measured 0 XML comments in the local DBLP inputs.
  - D18 to D20 (ruling 24).
  - N1 to N3 (ruling 27).

## Decision
ACCEPTED by human escalation (rulings 20, 26 and 27). Code review 2 returned REQUEST_CHANGES with no new critical or major finding. The open items are D18 (major, residual under ruling 24), the gate item on untested behaviours (WO4 and WO5 list plus N2), and the minors N1 to N3. All are recorded as residual risks and Phase 12+ backlog items. No further repair round.

## Manual changes
- No manual edits to agent-owned files.
- Per-file provenance:
  - tests/**: test-engineer (WO3 to WO5).
  - build.sbt: build-engineer (repair-01 A).
  - modules/rag: rag-engineer (WO2, repair-01 B, repair-02 A).
  - modules/services: service-engineer (WO1, repair-01 C).
  - modules/core: domain-engineer (repair-01 D).
  - modules/storage: storage-engineer (repair-01 E).
  - modules/spark/JOBS.md: spark-engineer (repair-01 F).
  - All of these are agent-generated and accepted after review.
- Human-authored: the prompt.md rulings, this gate, evidence copies, live runs and their analysis scripts, the removed-claim classification, and the D17 comment measurement (ruling 21).
- External assistance: none.
- Process notes:
  - guard-paths blocked test-engineer's module adversarial path (ruling 11).
  - WO2 ran an unpruned find (ruling 13).
  - Four guard false positives in repair-01 B, C, E and WO4, and Part D's unpruned du (ruling 22).
  - WO5 ran a read-only git status (ruling 25).
  - Repair-01 Part A resumed an attempt that had lost auth.
  - Logs over 10 MB were gzipped in evidence; WO4 added logback-test.xml.

## Remaining risks
- Live Neptune, DynamoDB, S3 Vectors, SQS, EMR, and the services' live mode stay untested until the cloud windows.
- The verifier is lexical, so wordings outside its lists still verify. D18 (major) is open: "it is doubtful that X has 10" or "10 more ... than" keeps a claim VERIFIED. D19 and D20 are minor. RUNTIME.md section 21 lists the gaps.
- Review 2 minors (ruling 27):
  - N1: GraphBoundsTimeoutAdversarialSpec:188-191 depends on the 300 ms workflow deadline and can fail on a loaded JVM (it passed in both gate checks); TESTS.md:42-43 overclaims.
  - N2: Semantic.scala:144 fetches semantic text outside the D4 try, so a throttled S3 text fetch still fails the question with 502; RUNTIME.md:231 overclaims.
  - N3: ApiBodyAdversarialSpec:331-343 asserts only a system property.
- Residual D items:
  - D6 to D9: name and DOI normalization; they lose recall only.
  - D15 and D16: API input validation, behind the API key.
  - D17: DBLP comments; none in the current data.
- Live answer quality:
  - B26 loses 9 list-position claims every run (by design).
  - B29's path is not cited when the cut-off retry trims claims.
  - Planner misplans B15, B18, B23 and B24 (carried from Phase 9).
  - Max live question latency (39,804 ms) is just under the 40,000 ms sync deadline. Under Haiku throttling, sync requests can time out; the async path is the fallback.
- Services: a slow body within the 30 s timeout still holds a thread. The ALB in front mitigates this.
- Untested behaviours (WO4 list, plus N2):
  - breaker half-open concurrency;
  - Supersede vector write ordering;
  - S3VectorStore.reader has no retry (degraded by D4);
  - fullwidth homonym digits;
  - any-integer DBLP year;
  - duplicate X-Api-Key headers;
  - Hadoop drivers without a mini-cluster;
  - the semantic text fetch failure path (N2).
- The Haiku quota (10 requests per minute) caps live concurrency.
- Phase 12 carry-forward:
  - Phase 10 rulings 18, 20 and 23.
  - SERVICES.md sections 4 to 6.
  - No empty SCHOLARCHAIN_* variables outside local (D12).

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
