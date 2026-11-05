# Phase 05 human gate: entity resolution

Date: 2026-11-04
Human architect: Achyut Patel

## What was reviewed
- Agent reports for work orders 1 to 11 (agent-report.md), repair-01.md, and repair-02.md (R1 to R3).
- Code reviews in review-report.md:
  - Review 1: REQUEST_CHANGES, one major (an ORCID group spanning DBLP homonym numbers merged silently). Fixed in repair-02.
  - Review 2: REQUEST_CHANGES, one major (the capped 152-mention suffix-conflict group reaches no J10 decision). Ruled a residual risk with an extended Phase 6 refusal rule; see "Human rulings after review 2" in prompt.md.
- Human sbt check, run twice: core 151, entityResolution 94, hadoop 70, 0 failed, success both times (evidence/human-sbt-check-1.txt and -2.txt).
- Hadoop assembly: 0 software/amazon/awssdk entries (R2 report, corroborated by the orchestrator's r4-jar-check.txt).
- Gold-set evaluation on 310 human labels (304 decided, 6 UNSURE): evidence/eval-er-v1/ and evidence/eval-er-v2/ (metrics.json, errors.tsv, sensitivity.tsv), plus both strata key files in evidence/.

## Phase 5 gate (spec)
- [x] Exact ID conflicts do not silently merge: J03+J04 conflict groups, same-source shared DOI (er-v2), and ORCID groups spanning DBLP homonym numbers (repair-02). Transitive cases and the capped suffix-conflict group are handled by the Phase 6 refusal rule (ruled).
- [x] The LLM can abstain (AdjudicationSpec; review 1 check 2). No live Bedrock call yet (on-demand quota 0, support case open); a bounded live demonstration comes later.
- [x] Evaluation examples are traceable to source records (row_id maps to source IDs in the strata files).
- [x] Gold labels are human-reviewed: 310 rows labelled by Achyut Patel under labels/README.md. A local display tool was used; it never suggests labels.
- [x] Precision and recall are reported separately for researchers and works, and by match path. Threshold sensitivity analysis: sensitivity.tsv.
- [x] Error analysis lists 1 false positive (all available) and 20 false negatives per policy version.
- [x] Reviewer verified no leakage from labels into matching (review 1 check 3). er-v2 was decided from silver data before any gold evaluation.

## Results (from metrics.json)
- er-v1 researcher: precision 0.989 (90/91), recall 0.600 (90/150), F1 0.747, unresolved rate 0.297, UNSURE 1.
  - EXACT_ID: precision 1.000, recall 0.929.
  - DETERMINISTIC: 13/13 correct.
  - PROBABILISTIC: precision 0.974, recall 0.594.
  - LLM_BAND: 39 rows, all UNRESOLVED (31 MATCH, 8 NON_MATCH); no live LLM.
- er-v2 researcher: identical to er-v1. No queued row touches the repair-02 ORCID groups.
- er-v1 work: precision 1.000 (19/19), recall 0.514 (19/37), F1 0.679, UNSURE 5.
- er-v2 work: precision 1.000 (22/22), recall 0.595 (22/37), F1 0.746. DETERMINISTIC 14/14 correct. These are per-cell row counts, not a stratified estimate for paths sampled from other strata.
- Overall figures are sample-weighted (about 39 rows per stratum); the per-path figures are the primary view.
- Researcher misses are mostly deferrals: 54 of 60 false negatives are UNRESOLVED (LLM band or below the accept threshold), 5 REJECTED, 1 CONFLICT.
- Threshold sensitivity (er-v1):
  - Researcher accept 0.93: precision 0.989, recall 0.600.
  - Researcher accept 0.70: precision 0.945, recall 0.800.
  - Researcher accept 0.50: precision 0.853, recall 0.853.
  - Work precision is 1.000 at every accept threshold; recall goes from 0.514 to 0.757.
  - The reject threshold changes coverage only.
  - Thresholds stay as ruled (accept 0.93, reject 0.35). Gold results are reported, not used for tuning.

## Findings recorded, not acted on (acting on gold results would be tuning on the gold set)
- All 6 sampled WORK exact-ID CONFLICT rows were labelled MATCH (duplicate records of one paper: ACM dual-DOI pattern, duplicate OpenAlex records). Any rule change must be justified from silver data and evaluated on a fresh sample.
- The gold set does not measure the repair-02 ORCID suffix-conflict rule (ruled; Phase 17 limitation).

## Decision
ACCEPTED

## Manual changes
- No manual edits to agent-owned files.
- Human-authored: prompt.md rulings (after review 1 and after review 2), evidence copies, labels/README.md, gold labels.

## Remaining risks
- 8.32M researcher pairs in the LLM band stay UNRESOLVED (split identities) until bounded live adjudication; 31 of 39 sampled band pairs are true matches.
- Silver calibration is biased toward ORCID holders. Work weights are partly calibrated (er-v2 rule from silver).
- The run ID omits the policy version and threshold overrides; the manifest records both (fix later in hadoop).
- Transitive merges across CONFLICT pairs or homonym suffixes, and the capped suffix-conflict group orcid:0000-0001-5832-0347 (152 mentions), depend on the extended Phase 6 refusal rule.
- er-v1 stays selectable through SCHOLARCHAIN_ER_POLICY_VERSION and reintroduces same-source DOI merges if used.
- Review 1 and review 2 minor findings are deferred to Phase 11 (listed in review-report.md and prompt.md).
- 26.67M blocked candidates versus 5.92M predicted by calibration on sample-sized enrichment.
- Small gold strata (about 39 rows each) give wide confidence intervals; only 1 false positive was observed.

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
