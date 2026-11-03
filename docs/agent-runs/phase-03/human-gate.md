# Phase 03 human gate

Phase: 03, contracts, configuration, and dataset manifests (date 2026-11-02)

Reviewed:
- prompt.md (work orders 1-3 and recorded decisions); agent-report.md (work orders 1-3); repair-01.md and repair-02.md (dataset-engineer); review-report.md (code-reviewer: APPROVE).
- I ran `sbt check` twice (148 tests passed both times) and `datasets/scripts/validate.sh` (all checks passed).

Accepted / rejected: Accepted. Human decisions recorded for this phase:
- All Phase 3 reports in one agent-report.md, because guard-paths.sh allows one per phase.
- ORCID: no real ORCID records committed; samples are ORCID's fictional test record 0000-0002-1825-0097 plus labeled "synthetic:" records. Real ORCID iDs from CC0 DBLP and OpenAlex data stay in samples as identity evidence.
- OPENALEX_API_KEY: environment variable only, set by me in my own terminal for full runs.
- Subset: 46 venues, about 111,417 works (106,246 with a DOI), 2000-2026.
- Domain record deviations listed under the domain-engineer's ASSUMPTIONS are accepted.
- Wire examples were frozen from the code's own output; I reviewed the sample records in the report.
- Researcher and graph-edge IDs may change between runs; downstream loaders must upsert by ID.
- PROVISIONAL configuration values are for their owners to calibrate.

Manual changes (files and reason): none.

File provenance:
- build.sbt, project/Dependencies.scala: agent-generated (build-engineer, work order 2), accepted as-is.
- datasets/**: agent-generated (dataset-engineer, work order 1), repaired by the same owner (repair-01: ORCID, key handling, subset; repair-02: raw-bucket reference).
- modules/core/**: agent-generated (domain-engineer, work order 3), accepted as-is.
- prompt.md and human-gate.md: human-authored. Agent reports saved verbatim by the orchestrator.

Remaining risk:
- ORCID daily-cap counting across midnight UTC (review risk 1). Procedure: start the full ORCID run just after 00:00 UTC and do not rerun it the same UTC day.
- AuthorshipRecord position and a few non-negative bounds are not validated (review risk 2). To fix in the Phase 11 repair loop.
- Config defaults to environment "local", which disables the placeholder guard (review risk 3). Phase 12 must set SCHOLARCHAIN_ENVIRONMENT on every deployed task.
- Run ID uniqueness depends on callers incrementing attempt (review risk 4). Phase 4 and 6 work orders must require it.
- Typesafe Config and scala-library on the EMR classpath are unverified until the first EMR run.
- About 5,171 subset works (mostly USENIX) have no DOI and get no OpenAlex or Crossref enrichment.
- An OpenAlex API response contained instructions addressed to AI agents; the agent treated it as data. Input for the Phase 17 threat model.
- The contact email is visible to ps while fetches run (single-user machine; accepted).
- Full dataset runs have not been executed yet.

Accepted commit: see commit.txt
