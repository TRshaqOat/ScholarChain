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
