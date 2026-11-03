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
