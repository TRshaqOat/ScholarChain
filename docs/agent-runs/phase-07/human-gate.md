# Phase 07 human gate: storage adapters, loading, embeddings, and S3 Vectors (spec Phases 7+8, merged under D-10)

Date: 2026-11-06
Human architect: Achyut Patel

## What was reviewed
- Agent reports for work orders 1, 2, 3, 4, 5, 6, 6b, 7 (agent-report.md) and repair-01 (repair-01.md).
- Code reviews in review-report.md: review 1 REQUEST_CHANGES (F1 DynamoDB number normalization, F2 evidence tracing, minors M1 to M13); review 2 APPROVE (no critical or major findings; minors N1 to N6).
- Human sbt check, run twice (19:25 and 19:31): core 166, storage 60, rag 15, entityResolution 94, sparkAnalytics 43, hadoop 70 = 448, 0 failed (evidence/human-sbt-check-1.txt and -2.txt).
- Store conversion run-1d6a86491812ff94887270c1ab885e69 (benchmark path run run-ee4ffd21aa20dbe4c078a4be96fe8c9f), render run run-b6343d09bb981330d78f56fb8c3b94d0, fake embed run run-13e2b27dca90db05937df33c4accb537, live embed run run-b28edbd40f932f0342c05d5b6f57b46c. Manifests in evidence/.
- Live Titan embedding (human-run): 13,023 vectors, 0 rejected; ValidateCli printed "count total 13023", "violations 0", "OK" (evidence/live-vectors-validate.txt). Session 1 was a 5-document test. Session 2 reached 2,772 documents, then failed after about 55 minutes with "The security token included in the request is expired". The auto-resume loop then embedded the remaining 10,251 in one pass (about 23:00 to 02:42, 10,254 requests). Evidence: live-embed-loop.log, live-embed-ledger.jsonl, embed-manifest.json.
- Token failure diagnosis (repair-01): the SDK credential provider honours Expiration, but the live retry policy did not retry the expired-token 403, so one bad request stopped the run. Fixed: one shared refreshing provider per profile for all five live clients, with refresh and one retry on an expired token. Why one request carried an expired token is unproven (credential_process timing or clock skew; clock offset at the gate in evidence/clock-offset.txt).
- Live retrieval smoke test on real Titan vectors (human-run, 2026-11-06, 300 query embeddings, 6 min 20 s). The live backup was first loaded into a separate file store, scratch/stores-live (evidence/live-vectors-load-local.txt: 13,023 written, 0 rejected). Results: profile-self@1 49/50 (0.98), community-self@5 50/50, path-self@1 50/50, topic-self@1 50/50, paper-self@1 49/50 (0.98), profile-community-filter 50/50; 6/6 passed (evidence/live-smoke.txt). Fake-embedding smoke: 6/6 passed.
- Repair-01 evidence runs (agent-run, quoted from repair-01.md): ValidateCli neptune on run-1d6a86491812ff94887270c1ab885e69 with --evidence-root: 3,635,231 elements, 0 violations; 1,590,180 inline plus 574 object-evidence edges (all 1,590,754 COAUTHORED_WITH edges), 2,038,078 work IDs traced; EVIDENCE_WORK_MISSING, EVIDENCE_NOT_AUTHORED, EVIDENCE_OBJECT_ORDER and EVIDENCE_OBJECT_PROVENANCE all 0. LoadDynamoCli dry run pass 2: all Unchanged (ArtifactRegistry 9, EntityLinkCache 287,780, ResearcherMetricCache 588,439), 0 conflicts, 0 invalid.

## Phase 7 gate (spec)
- [x] Long text is not stuffed into vector metadata (validator rejects any text key; text lives in S3 at textRef).
- [x] Neptune graph identifiers match Spark output identifiers (Phase 6 IDs reused; COAUTHORED_WITH reuses cae- IDs; 202 path hops match Spark evidence).
- [x] COAUTHORED_WITH edges can be traced to supporting work evidence (repair-01 F2: all 1,590,754 edges traced, 2,038,078 work IDs; EVIDENCE_WORK_MISSING 0, EVIDENCE_NOT_AUTHORED 0).
- [x] DynamoDB keys support stated access patterns (STORAGE.md lists them per table).
- [x] Loaders are idempotent (dry-run second passes all Unchanged; repair-01 F1 normalizes numbers in the fake and the live adapter, tested with a simulated 4.0 written / 4 read back; live reload verified in cloud window 2).
- [x] AWS integration tests are bounded and separately enabled (--live plus --profile; the bounded live load test stays open until cloud window 2, ruling 1).
- [x] The reviewer approves schema and failure-handling behavior (review 2 APPROVE).

## Phase 8 gate (spec)
- [x] The same vector key cannot silently refer to different text (key is chunkId over the text; CreateOnly refuses a different textHash).
- [x] The embedding model version is stored (amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1).
- [x] Source text can be fetched from the vector result through its S3 pointer (textRef).
- [x] Retrieval smoke tests pass (fake 6/6; live 6/6 on real Titan vectors).
- [x] Exact counts, shortest paths, components, and bridge metrics come from graph or metric stores, not vector similarity.
- [x] Cost and object counts are reported (13,023 vectors; about 13,000 Titan embedding requests plus 300 smoke queries, roughly 3 to 4 million input tokens, about 0.05 to 0.10 USD estimated, to confirm in Billing; DynamoDB about 0.56 USD per full load at an unverified price; S3 Vectors estimate in the Phase 12 cost plan).

## Results
- Neptune: 511,920 vertices (Researcher 259,057; Work 113,205; Community 79,740; Institution 58,404; Topic 1,226; Venue 288) and 3,123,311 edges (COAUTHORED_WITH 1,590,754; AUTHORED 535,898; MEMBER_OF 397,100; AFFILIATED_WITH 379,708; PUBLISHED_IN 113,169; HAS_TOPIC 106,682); 574 evidence objects.
- DynamoDB: ResearcherMetricCache 588,439 (397,100 WINDOW#, 191,318 CHANGE#, 21 RANKING#), EntityLinkCache 287,780, ArtifactRegistry 9.
- Vectors: 13,023 (researcher-profile-v1 5,000; collaboration-community-v1 4,000; topic-institution-summary-v1 2,969; paper-abstract-v1 1,000 metadata summaries, no abstracts; collaboration-path-v1 54). Live run throughput: 50 per minute (throttle), 0 rejected.
- Benchmark pairs (dominant-name-v1): B07/B29, B08 found at length 1; B10, B11/B30 at length 3; B09 AMBIGUOUS; B12 UNKNOWN_RESEARCHER.
- Split identities: 18,055 display names shared by more than one researcher; 1,080 resolve to a dominant candidate; 16,975 stay AMBIGUOUS.

## Human rulings recorded at the gate
- Rulings 17 to 37 in prompt.md (git, region, selection, core alignment, rankings, file handoff, dependencies, benchmark paths, live calls, memory, code version, throttle live-only, dominant-name-v1, config-v1 kept, documentation, review 1 rulings).
- Ruling 36 acknowledgements: M2 vector key policy; M7 embedding client location; M9 Phase 9 name normalization; M12 openCypher templates and live GraphReadPort deferred to Phase 9, read-only graph credentials to Phase 12.
- config-version stays config-v1 (ruling 31): the change only consolidated bucket names that no Phase 4 to 6 output depends on.
- Review 2 manual actions: every file in docs/agent-runs/phase-07/evidence/ was written by the human from Terminal (live run, smoke test, gate). The SDK's 15 s credential refresh margin is kept; a wider margin goes with N1 to N6 into a storage work order before cloud window 2.

## Decision
ACCEPTED (review 2 APPROVE after one repair round).

## Manual changes
- No manual edits to agent-owned files.
- Per-file provenance: modules/core (domain-engineer, WO2), build.sbt and project/Dependencies.scala (build-engineer, WO3), modules/storage (storage-engineer, WO1, WO4, WO7, repair-01), modules/rag (rag-engineer, WO5), modules/spark additions (spark-engineer, WO6, WO6b). All agent-generated, accepted after review.
- Human-authored: prompt.md rulings, this gate, evidence copies, the live embedding run, the local load into scratch/stores-live and the live smoke test (human-run commands), the scholarchain-sdk credential profile (outside the repo).
- Process deviations: WO1 ran git status once (read-only); WO4 worked around a guard block with a scratchpad script (M11). In repair-01 the guard blocked two heredoc edits as false positives and the agent switched to the Edit tool (correct handling). The orchestrator ran read-only git status and git diff to build review change sets, as allowed.

## Remaining risks
- Live loads (S3, Neptune, DynamoDB, S3 Vectors) are untested against AWS until cloud window 2; the bounded live load test stays open.
- Review 1 minors not repaired: M2, M5, M6, M7, M9, M10, M12, M13 (ruling 36).
- Review 2 minors N1 to N6 (storage-engineer, before cloud window 2): N1 expired-token retry bypasses the Bedrock throttle (at most one extra call per window; stale comment); N2 expired token on S3 HeadObject probably not detected (fails safe with exit 1); N3 error-code detection not independently tested; N4 no DynamoDB number range check; N5 refresh storm under loader concurrency; N6 ValidateCli without --evidence-root exits 0 with object-evidence edges untraced (gate runs must pass it).
- The root cause of the expired token is not settled; a second expiry in a row still stops a run as FAILED (resumable).
- The repair's ValidateCli and DynamoDB evidence runs used a build from before its last credentials change (code paths not affected); both sbt check runs used the final code. Review 2 confirmed no change outside storage by section line counts, not byte for byte.
- Split identities (18,055 shared names; dominant-name-v1 can attach a real homonym's few works to a famous researcher).
- Real Titan retrieval quality beyond the smoke tests is measured in Phase 16 (one profile and one paper missed rank 1 in the live smoke test).
- Paper index holds metadata summaries, not abstracts.
- DynamoDB write price unverified (Phase 12 cost plan).
- The Spark conversion copies and hashes every output byte through the driver; fine locally, serial on EMR.

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
