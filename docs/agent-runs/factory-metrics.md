# Factory metrics (human-owned)

Running log for the Phase 16 agentic-factory evaluation. One row per agent invocation (format from spec/architect-decisions.md section 5). Cells marked ? are filled in from the phase reports before Phase 17.

| date | phase | agent | status | first-pass accepted (y/n) | findings critical/major/minor | repair iteration | manual edits | hooks triggered | notes |
|---|---|---|---|---|---|---|---|---|---|
| ? | 00 | factory-engineer (seed, plain orchestrator) | COMPLETE | y | n/a | 0 | ? | ? | seed agent file only |
| ? | 01 | factory-engineer | COMPLETE | n | - | 0 | ? | ? | initial control plane |
| ? | 01 | code-reviewer | REQUEST_CHANGES | - | 2/6/? | 0 | none | ? | full review 1 |
| ? | 01 | factory-engineer | COMPLETE | - | - | 1-4 | ? | ? | repairs 01-04; see phase-01 repair files |
| ? | 01 | code-reviewer | REQUEST_CHANGES | - | 0/?/? | - | none | ? | re-review 2 (evidence and .gitignore majors) |
| ? | 01 | factory-engineer | COMPLETE | - | - | 5 | human-built repair-05.patch | ? | repair-05 |
| ? | 01 | code-reviewer | ? | - | ?/?/? | - | none | ? | final review 3 |
| 2026-11-02 | 02 | build-engineer | COMPLETE | n | - | 0 | none | none | build files done; stopped and asked before writing into 13 other agents' paths (ownership respected); human deferred those items |
| 2026-11-02 | 02 | build-engineer | BLOCKED-ON | - | - | 1 | none | none | repair-01: comments done; version values BLOCKED-ON dependency-updater (exception 1) |
| 2026-11-02 | 02 | dependency-updater | COMPLETE | y | - | 2 | none | none | repair-02: Scala 3.3.7, Spark 4.0.2, Hadoop 3.4.2 to match EMR; ruling B |
| 2026-11-02 | 02 | code-reviewer | APPROVE | y | 0/0/0 | - | none | none | non-blocking risks only; flagged Ponytail injected despite matcher |
| 2026-11-02 | 03 | dataset-engineer | COMPLETE | n | - | 0 | none | guard-dangerous [secret-jq-env] x1 (false positive); orchestrator hit guard-paths [agent-runs-not-a-report] x1 | WO1; 3 human escalations (ORCID license, API key, subset size); flagged prompt injection in an OpenAlex response |
| 2026-11-02 | 03 | dataset-engineer | COMPLETE | y | - | 1 | none | none | repair-01: applied ORCID, key, and subset decisions; key no longer printable |
| 2026-11-02 | 03 | build-engineer | COMPLETE | y | - | 0 | none | none | WO2: Typesafe Config 1.4.9; scala-library override 2.13.16 |
| 2026-11-02 | 03 | domain-engineer | COMPLETE | y | - | 0 | none | none | WO3: 21 records, IDs, validation, config loader; 148 tests |
| 2026-11-02 | 03 | dataset-engineer | COMPLETE | y | - | 2 | none | none | repair-02: raw-bucket config reference in manifest |
| 2026-11-02 | 03 | code-reviewer | APPROVE | y | 0/0/5 | - | none | none | 5 minor risks accepted as residual |
| 2026-11-03 | 04 | hadoop-engineer | BLOCKED-ON | n | - | 0 | none | none | WO1: J01-J04, J07-J09 done; J05/J06 blocked on entity-resolution-engineer and domain-engineer |
| 2026-11-03 | 04 | domain-engineer | COMPLETE | y | - | 0 | none | none | WO2: max-block and conflict-cap keys; affiliationNameId |
| 2026-11-03 | 04 | entity-resolution-engineer | COMPLETE | y | - | 0 | none | none | WO3: normalization, blocking keys, oversize policy, 10 features; 30 tests |
| 2026-11-03 | 04 | hadoop-engineer | COMPLETE | y | - | 1 | none | guard-dangerous false positive on Scala text "b.key" (used file tools instead) | WO4: J05/J06, jar shaded; full 110,867-work local run |
| 2026-11-03 | 04 | code-reviewer | APPROVE | y | 0/0/3 | - | none | none | 3 minor accepted as residual |
| ? | 05 | dataset-engineer (WO1) | COMPLETE | n | - | 0 | 0 | ? | follow-up in repair-01 |
| ? | 05 | dataset-engineer (repair-01) | ? | ? | - | ? | 0 | ? | see repair-01.md |
| ? | 05 | dataset-engineer (WO2) | COMPLETE | y | - | 0 | 0 | ? | manifest updated with observed figures |
| ? | 05 | build-engineer (WO3) | COMPLETE | y | - | 0 | 0 | ? | bedrockruntime pin, awssdk excluded from hadoop |
| ? | 05 | entity-resolution-engineer (WO4) | COMPLETE | y | - | 0 | 0 | ? | match policy er-v1 |
| ? | 05 | domain-engineer (WO5) | COMPLETE | y | - | 0 | 0 | ? | ER configuration |
| ? | 05 | hadoop-engineer (WO6) | COMPLETE | y | - | 0 | 0 | ? | J10 decisions, er-v1 full run |
| ? | 05 | entity-resolution-engineer (WO7) | COMPLETE | y | - | 0 | 0 | ? | gold review queue, 310 rows |
| ? | 05 | entity-resolution-engineer (WO8) | COMPLETE | y | - | 0 | 0 | ? | er-v2 from silver data |
| ? | 05 | domain-engineer (WO9) | BLOCKED-ON hadoop-engineer | n | - | 0 | 0 | ? | cross-owner dependency, routed via WO10 |
| ? | 05 | hadoop-engineer (WO10) | COMPLETE | y | - | 0 | 0 | ? | er-v2 full run |
| ? | 05 | entity-resolution-engineer (WO11) | COMPLETE | y | - | 0 | 0 | ? | er-v2 re-key |
| 2026-11-04 | 05 | code-reviewer (review 1) | REQUEST_CHANGES | n | 0/1/11 | 0 | 0 | ? | major: ORCID group across DBLP homonym numbers |
| 2026-11-04 | 05 | entity-resolution-engineer (R1) | COMPLETE | y | - | 1 | 0 | ? | orcidGroupSuffixConflict rule |
| 2026-11-04 | 05 | hadoop-engineer (R2) | COMPLETE | y | - | 1 | 0 | ? | rule applied in J03+J04; 16 groups; rerun |
| 2026-11-04 | 05 | entity-resolution-engineer (R3) | COMPLETE | y | - | 1 | 0 | ? | re-key, 0 rows changed |
| 2026-11-04 | 05 | code-reviewer (review 2) | REQUEST_CHANGES | n | 0/1/5 | 1 | 0 | ? | major (capped group) ruled residual risk; Phase 5: 1 BLOCKED-ON, 1 repair round |
| 2026-11-04/05 | 06 | build-engineer (WO1) | COMPLETE | y | - | 0 | 0 | ? | build.sbt only: forked serial tests, Spark JVM options, forked runMain; jar 0/0 |
| 2026-11-04/05 | 06 | spark-engineer (WO2) | COMPLETE | n | - | 0 | 0 | ? | jobs 1-2; review 1 MAJOR-1 and MAJOR-2 touch its tables |
| 2026-11-05 | 06 | spark-engineer (WO3) | COMPLETE | n | - | 0 | 0 | ? | jobs 3, 4, 8; MAJOR-1 (components) |
| 2026-11-05 | 06 | spark-engineer (WO4) | COMPLETE | n | - | 0 | 0 | ? | jobs 5-7; MAJOR-1 (cross-community edges) |
| 2026-11-05 | 06 | spark-engineer (WO5) | COMPLETE | n | - | 0 | 0 | ? | jobs 9-11, end-to-end; first attempt OOM, second succeeded; MAJOR-1 (temporal) |
| 2026-11-05 | 06 | code-reviewer (review 1) | REQUEST_CHANGES | n | 0/3/8 | 0 | 0 | ? | MAJOR-3 closed by human ruling 23 |
| 2026-11-05 | 06 | spark-engineer (repair-01) | COMPLETE | y | - | 1 | 0 | ? | fixes MAJOR-1, MAJOR-2, m3, code version; content hashes equal to WO5 run |
| 2026-11-05 | 06 | code-reviewer (review 2) | APPROVE | y | 0/0/3 | 1 | 0 | ? | n2 ruled at gate; n1, n3 residual; Phase 6: 0 BLOCKED-ON, 1 repair round |
| 2026-11-05 | 07 | storage-engineer (WO1) | COMPLETE | n | - | 0 | 0 | ? | contracts, ports, fakes, validators; F2 validator gap and F1 fake model originate here; ran git status once |
| 2026-11-05 | 07 | domain-engineer (WO2) | COMPLETE | y | - | 0 | 0 | ? | one bucket + prefixes; Community, MEMBER_OF; config-v1 kept |
| 2026-11-05 | 07 | build-engineer (WO3) | COMPLETE | y | - | 0 | 0 | ? | 4 SDK modules + bedrockruntime to storage at 2.41.32; no 3b needed |
| 2026-11-05 | 07 | storage-engineer (WO4) | COMPLETE | n | - | 0 | 0 | ? | CLIs, embedding runner, live adapters; F1 (live), M11 guard workaround; throttle applied to fake (fixed in WO7 step 0) |
| 2026-11-05 | 07 | rag-engineer (WO5) | COMPLETE | y | - | 0 | 0 | ? | selection-v1, rendering-v1, render and smoke CLIs; flagged the fake-throttle issue |
| 2026-11-05 | 07 | spark-engineer (WO6) | COMPLETE | y | - | 0 | 0 | ? | rankings, store conversion; exact-name rule left all benchmark pairs AMBIGUOUS (human rule change in ruling 30) |
| 2026-11-05 | 07 | spark-engineer (WO6b) | COMPLETE | y | - | 0 | 0 | ? | dominant-name-v1; 4 benchmark pairs found; split-identity counts |
| 2026-11-05 | 07 | storage-engineer (WO7) | COMPLETE | y | - | 0 | 0 | ? | step 0 (rulings 29, 32), full dry run, streaming Neptune reload fix, live command |
| 2026-11-05 | 07 | code-reviewer (review 1) | REQUEST_CHANGES | n | 0/2/13 | 0 | 0 | ? | F1, F2 to storage-engineer; ruling 36 acknowledgements |
| 2026-11-06 | 07 | storage-engineer (repair-01) | COMPLETE | y | - | 1 | 0 | ? | F1, F2, M1, M3, M4, M8, credential refresh (expired-token 403 not retried; shared refreshing provider, retry once); 448 tests; guard false positives handled with Edit |
| 2026-11-06 | 07 | code-reviewer (review 2) | APPROVE | y | 0/0/6 | 1 | 0 | ? | N1-N6 minors to storage-engineer; Phase 7: 0 BLOCKED-ON, 1 repair round, 3 mid-session human rulings (29, 30-32) |
| 2026-11-06 | 09 | rag-engineer (WO1) | COMPLETE | y | - | 0 | 0 | ? | RUNTIME.md contract, runtime records; resumed after cut-off |
| 2026-11-06 | 09 | domain-engineer (WO2) | COMPLETE | y | - | 0 | 0 | ? | runtime and graph-read config keys, M10 two keys, C1, C2; analytics.ranking-size |
| 2026-11-06 | 09 | storage-engineer (WO3) | COMPLETE | y | - | 0 | 0 | ? | 17 openCypher templates, bidirectional path search (54/54), runtime records, N1-N6 |
| 2026-11-06 | 09 | rag-engineer (WO4) | COMPLETE | n | - | 0 | 0 | ? | stages 1-10, Haiku client, prompts; F1-F5 and F6 (ItemFileIndex) originate here |
| 2026-11-07 | 09 | rag-engineer (WO5) | COMPLETE | y | - | 0 | 0 | ? | benchmark CLI, fake run 26/30 (FU2), live command |
| 2026-11-07 | 09 | entity-resolution-engineer (WO6) | COMPLETE | n | - | 0 | 0 | ? | AdjudicateSample; silent fenced-reply rejection found live (rulings 26, 27) |
| 2026-11-07 | 09 | code-reviewer (review 1) | REQUEST_CHANGES | n | 0/6/9 | 0 | 0 | ? | F1-F5 rag-engineer, F6 storage then rag |
| 2026-11-07 | 09 | entity-resolution-engineer (repair-01 C) | COMPLETE | y | - | 1 | 0 | ? | fence strip, reply shape, progress lines, stop after 3 failures; m4 |
| 2026-11-11 | 09 | storage-engineer (repair-01 A) | COMPLETE | y | - | 1 | 0 | ? | ItemFileReader (F6); one read-only git status (deviation) |
| 2026-11-11 | 09 | rag-engineer (repair-01 B) | BLOCKED-ON | n | - | 1 | 1 | ? | F1-F6 and minors; synthesis-v2 default routed to domain-engineer; N1 N2 found later |
| 2026-11-11 | 09 | domain-engineer (repair-01 D) | COMPLETE | y | - | 1 | 0 | ? | synthesis-v2 default (ruling 28) |
| 2026-11-11 | 09 | code-reviewer (review 2) | REQUEST_CHANGES | n | 0/2/3 | 1 | 0 | ? | N1 number words, N2 names |
| 2026-11-11 | 09 | rag-engineer (repair-02) | COMPLETE | n | - | 2 | 0 | ? | N1 N2 fixed; R3-1 R3-2 originate here; rate-limit interruption |
| 2026-11-11 | 09 | rag-engineer (repair-02, ruling 30) | COMPLETE | y | - | 2 | 0 | ? | planner messages name what is allowed; planner-v2 |
| 2026-11-11 | 09 | domain-engineer (repair-02, ruling 30) | COMPLETE | y | - | 2 | 0 | ? | planner-v2 default |
| 2026-11-12 | 09 | code-reviewer (review 3) | REQUEST_CHANGES | n | 0/2/4 | 2 | 0 | ? | Phase 9: 1 BLOCKED-ON, 2 repair rounds (2nd by exception), human escalation (ruling 31) |
| 2026-11-12 | 10 | rag-engineer (WO1) | COMPLETE | n | - | 0 | 0 | ? | Phase 9 ruling 31 verifier fixes (verifier-v4), synthesis-v3, RuntimeApi; review 2 N5 |
| 2026-11-12 | 10 | domain-engineer (WO2) | COMPLETE | y | - | 0 | 0 | ? | synthesis limits, sync deadline 40 s, services keys, JobState, ErrorCode |
| 2026-11-12 | 10 | build-engineer (WO3) | COMPLETE | y | - | 0 | 0 | ? | services entry points, sqs artifact, prompts packaging (FU3) |
| 2026-11-12 | 10 | storage-engineer (WO4) | COMPLETE | y | - | 0 | 0 | ? | ServiceState table, evidenceById GSI, job queue port |
| 2026-11-12 | 10 | service-engineer (WO5) | COMPLETE | n | - | 0 | 0 | ? | APIs, OpenAPI, contract tests; review 1 F1, m1, m5 |
| 2026-11-12 | 10 | service-engineer (WO6) | COMPLETE | n | - | 0 | 0 | ? | worker, async path, lifecycle test, local smoke; review 1 m2 |
| 2026-11-12 | 10 | spark-engineer (WO7) | COMPLETE | y | - | 0 | 0 | ? | FU2 window summaries |
| 2026-11-12 | 10 | storage-engineer (WO8) | COMPLETE | y | - | 0 | 0 | ? | FU2 read port (manifest-based loader, ruling 20) |
| 2026-11-12 | 10 | rag-engineer (WO9) | COMPLETE | n | - | 0 | 0 | ? | FU2 answers, rulings 19a and 19b, verifier-v5; review 1 m4 |
| 2026-11-12 | 10 | domain-engineer (WO10) | COMPLETE | y | - | 0 | 0 | ? | runtime.window-summary.load-run |
| 2026-11-12 | 10 | service-engineer (WO11) | COMPLETE | n | - | 0 | 0 | ? | window-summary wiring, stale JUnit file; review 1 m3 |
| 2026-11-12 | 10 | code-reviewer (review 1) | REQUEST_CHANGES | y | 0/1/5 | 0 | 0 | ? | F1 pool saturation; m1 to m5 |
| 2026-11-12 | 10 | service-engineer (repair-01 A) | COMPLETE | n | - | 1 | 0 | ? | F1 two pools, m1 m2 m3 m5, SERVICES.md; review 2 N1 to N4; SaturationSpec flake (ruling 25) |
| 2026-11-12 | 10 | rag-engineer (repair-01 B) | COMPLETE | y | - | 1 | 0 | ? | m4; 19 disable checks on verifier-v5 |
| 2026-11-12 | 10 | code-reviewer (review 2) | APPROVE | y | 0/0/5 | 1 | 0 | ? | N1 to N5 residual (ruling 24) |
| 2026-11-13 | 11 | service-engineer (WO1) | COMPLETE | n | - | 0 | 0 | ? | SaturationSpec deterministic, N1-N4; review 1 F3 |
| 2026-11-13 | 11 | rag-engineer (WO2) | COMPLETE | n | - | 0 | 0 | ? | 15 live claims classified, verifier-v6, N5; review 1 F5 |
| 2026-11-13 | 11 | test-engineer (WO3) | COMPLETE | n | - | 0 | 2 | ? | 108 adversarial tests, D1-D13 pending; review 1 F4, F7, section 5 |
| 2026-11-13 | 11 | code-reviewer (review 1) | REQUEST_CHANGES | y | 0/5/15 | 0 | 0 | ? | D1-D5 major confirmed; D6-D13 and F1-F7 minors |
| 2026-11-14 | 11 | build-engineer (repair-01 A) | COMPLETE | y | - | 1 | 0 | ? | tests classpath for Spark/Hadoop, fork at repo root (ruling 12) |
| 2026-11-14 | 11 | rag-engineer (repair-01 B) | COMPLETE | n | - | 1 | 0 | ? | D1-D4, F5, verifier-v7; review 2 N2 |
| 2026-11-14 | 11 | service-engineer (repair-01 C) | COMPLETE | y | - | 1 | 0 | ? | D5 read timeout and drain bound, F3, main test |
| 2026-11-14 | 11 | domain-engineer (repair-01 D) | BLOCKED-ON | y | - | 1 | 1 | ? | D11-D13, F1, F2; D8 blocked (ruling 19) |
| 2026-11-14 | 11 | storage-engineer (repair-01 E) | COMPLETE | y | - | 1 | 0 | ? | D10 count identities |
| 2026-11-14 | 11 | spark-engineer (repair-01 F) | COMPLETE | y | - | 1 | 0 | ? | F6 JOBS.md |
| 2026-11-14 | 11 | test-engineer (WO4 rerun) | COMPLETE | n | - | 1 | 0 | ? | 10 wrappers removed, Spark/Hadoop suites, D14-D17 found; review 2 N1, N3 |
| 2026-11-14 | 11 | rag-engineer (repair-02 A) | COMPLETE | y | - | 2 | 0 | ? | D14, verifier-v8 (human exception, ruling 20) |
| 2026-11-14 | 11 | test-engineer (WO5 rerun) | COMPLETE | y | - | 2 | 0 | ? | D14 wrapper removed, D18-D20 found |
| 2026-11-14 | 11 | code-reviewer (review 2) | REQUEST_CHANGES | y | 0/1/3 | 2 | 0 | ? | D18 major open (residual, ruling 24); N1-N3 new minors; human escalation (ruling 27) |
| 2026-11-14 | 12 | storage-engineer (WO1) | BLOCKED-ON | y | - | 0 | 1 | ? | LiveAws default chain for ECS; blocked on service-engineer launcher |
| 2026-11-14 | 12 | infra-engineer (WO2) | BLOCKED-ON | n | - | 0 | 3 | ? | Terraform 13 modules, aws 6.28.0; review 1 F1, F5, F6, F7a, F7c |
| 2026-11-14 | 12 | infra-engineer (WO3) | BLOCKED-ON | n | - | 0 | 2 | ? | 11 scripts; ran cleanup.sh via wrapper (ruling 10); review 1 F3, F4, F7b, F9 |
| 2026-11-14 | 12 | domain-engineer (WO4) | COMPLETE | y | - | 0 | 0 | ? | SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE override |
| 2026-11-14 | 12 | storage-engineer (WO5) | COMPLETE | n | - | 0 | 0 | ? | loaders without --profile, --stage-from-bucket; review 1 F8 (LiveAws comment) |
| 2026-11-14 | 12 | service-engineer (WO6) | COMPLETE | y | - | 0 | 0 | ? | Launcher optional --profile, SERVICES.md 5a |
| 2026-11-14 | 12 | dataset-engineer (WO7) | COMPLETE | n | - | 0 | 0 | ? | fraction-v1 25/50 subsets; review 1 F2 (pycache) |
| 2026-11-14 | 12 | hadoop-engineer (WO8) | COMPLETE | y | - | 0 | 0 | ? | JOBS.md single bucket |
| 2026-11-14 | 12 | code-reviewer (review 1) | REQUEST_CHANGES | y | 0/1/8 | 0 | 0 | ? | F1 EMR role IAM major |
| 2026-11-14 | 12 | dataset-engineer (repair-01 A) | COMPLETE | y | - | 1 | 0 | ? | F2 |
| 2026-11-14 | 12 | storage-engineer (repair-01 B) | COMPLETE | y | - | 1 | 0 | ? | F8 docs |
| 2026-11-14 | 12 | infra-engineer (repair-01 C) | COMPLETE | n | - | 1 | 0 | ? | F1, F3-F9; review 2 N1-N3; ListBucket prefix condition per human wording led to N4 |
| 2026-11-14 | 12 | code-reviewer (review 2) | APPROVE | n | 0/0/3 | 1 | 0 | ? | R1 raised; its proposed exact-key fix was incomplete (corrected in review 3) |
| 2026-11-15 | 12 | infra-engineer (repair-02 A) | COMPLETE | n | - | 2 | 0 | ? | R1, N1, N2, F9 remainder (ruling 22); review 3 N4, N5 |
| 2026-11-15 | 12 | code-reviewer (review 3) | REQUEST_CHANGES | y | 0/1/1 | 2 | 0 | ? | N4 major (S3 404 needs ListBucket) |
| 2026-11-15 | 12 | infra-engineer (repair-03 A) | COMPLETE | n | - | 3 | 0 | ? | N4, role table (ruling 23); review 4 M1, M2 |
| 2026-11-15 | 12 | code-reviewer (review 4) | APPROVE | y | 0/0/3 | 3 | 0 | ? | M1-M3 residual (ruling 24) |
