# Phase 04 human gate

Phase: 04, raw ingestion and Hadoop MapReduce (date 2026-11-03)

Reviewed:
- prompt.md (work orders 1-4 and rulings); agent-report.md (work orders 1-4); review-report.md (code-reviewer: APPROVE, 3 minor).
- I ran `sbt check` twice (core 150, entity-resolution 30, hadoop 54; all passed) and inspected the hadoop job jar: 0 top-level scala/ entries and 0 org/apache/hadoop/ entries.

Accepted / rejected: Accepted. Human decisions recorded for this phase:
- Work order 1 returned BLOCKED-ON (blocking keys and features belong to the entity-resolution-engineer; config key and affiliation IDs to the domain-engineer). Routed as work orders 2-4 in the same session.
- ORCID profiles are emitted as GraphNode plus Institution records with AFFILIATED_WITH edges; no new core record type.
- Affiliation IDs without an external identifier come from core StableIds.affiliationNameId.
- Oversize teams keep all their pairs (required for correct team-size metrics); OVERSIZE_TEAM counts them.
- J03+J04 conflict output is capped by entity-resolution.max-conflict-group-size.
- Same-work pairs are never candidates in J06.
- Features return Option; absent features are left out of the map.
- Work blocking keys use title and year only; venue and affiliation stay features (review minor 2).
- Adversarial testing is deferred to Phase 11 (D-10); tests use the local job runner, not a mini-cluster.

Manual changes (files and reason): none.

File provenance:
- modules/hadoop/**: agent-generated (hadoop-engineer, work orders 1 and 4), accepted as-is.
- modules/entity-resolution/**: agent-generated (entity-resolution-engineer, work order 3), accepted as-is.
- modules/core/** changes: agent-generated (domain-engineer, work order 2), accepted as-is.
- prompt.md and human-gate.md: human-authored. Agent reports saved verbatim by the orchestrator.

Full local run evidence (110,867 DBLP works, sample-size enrichment): max team 287 authors (41,041 pairs); 1,019,535 pair candidates; 4 oversize teams; largest blocking group 1,632 (re-keyed); 20,258,400 blocking candidates at max-block-size 1000; 0 rejects.

Remaining risk:
- max-block-size 1000 yields about 20.26M candidates; calibration assigned to Phase 5, followed by a J05/J06 rerun.
- Run IDs can collide across different inputs with the same labels; in Phase 5 the hadoop driver passes input fingerprints into the run ID inputs.
- Capped-conflict wording in Logic.scala and JOBS.md disagrees with behavior (review minor 1); fix in the Phase 5 hadoop rerun.
- J01/J02 reducers hold whole venue groups in memory (review minor 3); fine at subset scale.
- The full local run used sample-size enrichment; the Phase 5 rerun uses the full OpenAlex and Crossref downloads.
- Full ORCID records not yet fetched; capped ORCID run is Phase 5 work order 1.
- Nothing has run on EMR or s3a yet; Typesafe Config on the EMR classpath unverified.

Accepted commit: see commit.txt
