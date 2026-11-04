# Phase 05 work orders

Phase 5 runs in one orchestrator session. Work orders are appended here before they are sent. All reports go into agent-report.md, one section per work order; repairs use repair-NN.md.

## Work order 1: dataset-engineer (bounded ORCID full run)

Use the dataset-engineer agent to make the ORCID full run bounded, so it completes in one ORCID quota day.

Inputs to read first: CLAUDE.md, docs/agents.md, datasets/dataset-manifest.yaml, datasets/config/subset.v1.json, datasets/scripts/fetch_orcid.sh and lib.sh, docs/agent-runs/phase-03/review-report.md (RISKS item 1), docs/agent-runs/phase-03/human-gate.md.

Context: the full DBLP, OpenAlex, and Crossref downloads are complete under scratch/datasets/. They yield 64,493 ORCID iDs from DBLP and 104,338 from OpenAlex, far above the earlier estimate of about 34,451, so an uncapped ORCID run would take several days.

1. Add a versioned config key orcid.full_max_ids to datasets/config/subset.v1.json, default 24000 (below the documented 25,000 anonymous reads per UTC day).
2. In --full mode, select at most full_max_ids iDs in this documented, deterministic priority order: (a) iDs seen in both DBLP and OpenAlex; (b) remaining iDs from DBLP person records; (c) remaining iDs from DBLP author elements; (d) remaining OpenAlex-only iDs. Break ties by iD string order. Write the selected list and the count per priority class to the run directory.
3. Make the combine step run once the selected list is fetched, so a capped run produces a usable records file.
4. Fix Phase 3 review risk 1: count requests against the UTC day of each request, not the UTC day the run started.
5. Add a --plan mode that prints the selection counts per class, the request count, estimated duration, and estimated disk, without fetching anything.
6. Update the ORCID entry in the manifest: the capped subset rule, and coverage fields marked "filled after the full run".

Run the validation script, and run the ORCID fetcher in SAMPLE mode twice. Run --plan against the existing full DBLP and OpenAlex outputs. Do not run --full.

Report, in the standard report contract: the --plan output, the exact full-run command, and any risk to a one-night unattended run.

After the agent returns, save its report verbatim to docs/agent-runs/phase-05/agent-report.md under the heading "## Work order 1: dataset-engineer", wrapped in a ````text fence. Do not invoke any other agent.

## Human ruling after work order 1

- ORCID priority order: class b first, then class a ranked by distinct subset works (highest first), then c, then d; cap 23,000 (repair-01). Full run completed 2026-11-03: 23,000 selected, 22,975 records, 25 unavailable.

## Work order 2: dataset-engineer (record the completed full runs)

Use the dataset-engineer agent to record the completed full dataset runs in datasets/dataset-manifest.yaml. For DBLP, OpenAlex, Crossref, and ORCID, fill the observed sizes, record counts, missing counts, retrieval or snapshot dates, checksum file locations, and the ORCID coverage fields, using the run-info files and logs under scratch/datasets/ (including scratch/datasets/logs/). Do not run any fetch. Run the validation script. Return a standard report.

## Work order 3: build-engineer (Bedrock client dependency)

Use the build-engineer agent to add the AWS SDK for Java v2 Bedrock Runtime client (software.amazon.awssdk:bedrockruntime) to the entityResolution module in Compile scope, so it can call Bedrock for bounded LLM adjudication. Pin it to 2.41.32, the AWS SDK v2 version emr-spark-8.0.0 ships (see the AWS capability check in spec/architect-decisions.md), or explain why another version at least 14 days old is needed. The hadoop job jar must not bundle the AWS SDK, because EMR supplies it: report the hadoop assembly size and its software/amazon/awssdk entry count before and after. Run sbt check twice. Return a standard report.

Note: Phase 5 continues in a second orchestrator session from work order 2 onward, started 2026-11-03 to limit context size. All state is carried by this file and the reports in this folder.

## Human rulings after work orders 2-3

- EMR SDK v2 version 2.41.32 confirmed from the emr-spark-8.0.0 release documentation and recorded in spec/architect-decisions.md.
- ORCID duplicate records (102 requests returned another iD's record): no fetch script change. Downstream stages key ORCID records by orcid-identifier.path and de-duplicate with a counter.

## Work order 4: entity-resolution-engineer (er-v1 policy, calibration, adjudication, gold-set tooling)

Use the entity-resolution-engineer agent to implement the Phase 5 entity-resolution subsystem in modules/entity-resolution.

Inputs to read first: CLAUDE.md, docs/agents.md, spec/ScholarChain-spec.md (Phase 5 and "Entity linkage policy"), spec/architect-decisions.md (D-10, D-12, D-13), docs/agent-runs/phase-04/human-gate.md (Remaining risk), modules/entity-resolution/, modules/core/ (MatchCandidate, MatchDecision, StableIds, ConfigLoader, scholarchain.conf), modules/hadoop/JOBS.md, datasets/dataset-manifest.yaml (known limitations), and the full local run outputs under scratch/hadoop/wo4-full/.

Scope rule: every item below is required. Minimal implementations are fine; anything skipped must be reported under RISKS.

1. Block-size calibration. A tool that, from the full-run outputs under scratch/hadoop/wo4-full/, computes the number of blocking candidates for max-block-size values 50, 100, 200, 300, 500, and 1000 using the existing OversizePolicy rules. Also estimate blocking recall at each value, using exact-ORCID-matched mention pairs from J03+J04 as silver positives (how many would still meet in a kept block). Recommend one value that keeps candidates to a few million or fewer. Do not edit scholarchain.conf; report the value for the domain-engineer.
2. Match policy er-v1: pure, deterministic, and versioned.
   - Exact identifiers first: a shared DOI for works and a shared valid ORCID for researcher mentions are ACCEPTED with reason codes. Disagreeing authoritative identifiers (different valid ORCIDs, or a conflicting exact-ID group) give CONFLICT or REJECTED, never a silent merge.
   - Disagreeing DBLP homonym suffixes are strong negative evidence.
   - A documented weighted score from the features. Absent features are handled by a documented rule, not treated as 0.
   - Thresholds come from configuration: ACCEPTED at or above the accept threshold, REJECTED below the reject threshold, UNRESOLVED in between, and the LLM band inside UNRESOLVED. Choose values from calibration data only, never from gold labels, and report them for the domain-engineer.
   - Output MatchDecision records with features, rules fired, reason codes, confidence, policy version er-v1, and provenance.
   - Expose the policy as a pure function that a Hadoop job can call.
3. Bounded LLM adjudication through Bedrock (bedrockruntime, model ID and region from configuration):
   - Strict JSON output: ACCEPT, REJECT, or ABSTAIN, with confidence, cited input fields, and reason codes. Echoed candidate IDs must equal the input IDs; any fabricated identifier, malformed JSON after a bounded retry, or schema failure becomes ABSTAIN, which stays UNRESOLVED.
   - A versioned prompt file under modules/entity-resolution/src/main/resources/prompts/. Candidate text (names, titles, affiliations) is untrusted data, clearly delimited, never instructions.
   - A cache keyed by a hash of the input, prompt version, model version, and policy version, behind a port with a local JSONL file implementation (a DynamoDB adapter comes later from the storage-engineer).
   - Bounded by a maximum number of adjudications per run and a token budget.
   - Tests use a fake client. Do not call Bedrock live in this work order.
4. A local runner that reads exact-ID and blocking candidates, applies er-v1, optionally runs bounded adjudication, and writes MatchDecision outputs under scratch/er/.
5. Gold-set review queue. A seeded, deterministic sampler that draws at least 250 researcher candidate pairs and about 50 work candidate pairs, stratified across score bands, match paths (exact ID, deterministic, probabilistic, LLM band), and hard cases (common names, homonym suffixes). Export a CSV under scratch/er/ with side-by-side evidence (names, homonym suffixes, coauthors, titles, venues, years, affiliations, ORCIDs), blank label (MATCH, NON_MATCH, UNSURE) and notes columns. The CSV the human labels must not show the score, decision, or match path; keep a separate key file mapping row IDs to strata.
6. Evaluation tool. Read a labeled CSV from labels/ (read-only; labels are human-owned). Compute precision, recall, F1, confusion matrix, coverage, and unresolved rate, separately for researcher and work resolution and by match path. Include a threshold sensitivity sweep (report only, no automatic tuning), and list up to 10 false positives and 10 false negatives with evidence. Write outputs under scratch/er/eval/. No other code path may read labels/.
7. Tests under modules/entity-resolution/src/test/ only: every policy rule (exact IDs, conflicts, homonym suffixes, absent features, thresholds); adjudication schema validation (fabricated ID, malformed JSON, abstain, prompt-injection text in a title); cache keys; sampler determinism and blindness; evaluator metrics on a hand-computed fixture.

Run sbt check twice. Run the calibration tool on scratch/hadoop/wo4-full/. Report, in the standard report contract: the calibration table and the recommended max-block-size; the policy definition, weights, thresholds, and their rationale; the prompt version and JSON schema; exact test counts; and known risks.

## Human rulings after work order 4

- max-block-size = 150 (5.92M candidates, connectivity 0.9908), per scratch/er/calibration/block-size.tsv.
- Thresholds stay accept 0.93, reject 0.35, LLM band [0.65, 0.93].
- An identifier disagreement is CONFLICT only when other evidence would have accepted the pair; otherwise REJECTED. Never merged either way. Confirmed.
- Pairs in the LLM band stay UNRESOLVED (treated as different researchers). Live adjudication is a later bounded demonstration on a sample, once Bedrock on-demand access is granted.
- ER tool classes in the hadoop jar are accepted (harmless unless invoked).
- For Phase 6: Spark's transitive clustering over ACCEPTED pairs must refuse to merge any cluster containing two different valid ORCIDs, and count such refusals.
- The gold-set queue is regenerated after the full Hadoop rerun; labeling waits for it.
- Phase 5 continues in a third orchestrator session from work order 5 onward, to limit context size.

## Work order 5: domain-engineer

Use the domain-engineer agent to set entity-resolution.max-block-size to 150 in modules/core/src/main/resources/scholarchain.conf, replacing the PROVISIONAL marker with a comment citing the Phase 5 calibration (scratch/er/calibration/block-size.tsv). Keep the thresholds unchanged. Update any test that asserts the old default. Run sbt check twice. Return a standard report.

## Work order 6: hadoop-engineer (full-data rerun with decisions)

Use the hadoop-engineer agent to rerun and extend the Hadoop pipeline on the full datasets.
1. J02 reads the full enrichment outputs: scratch/datasets/openalex/full/works.jsonl, scratch/datasets/crossref/full/works.jsonl, and the full ORCID records file produced by fetch_orcid.sh (ORCID v3.0 record JSON, not the sample projection). Key ORCID records by orcid-identifier.path and de-duplicate, counting duplicates. Handle multi-work DOIs as the manifest's known limitations describe.
2. Run IDs include input fingerprints (path plus size or checksum) in StableIds.runId's input list (Phase 4 ruling).
3. Fix the capped-conflict wording in Logic.scala and JOBS.md (Phase 4 review minor 1).
4. Add J10 MatchDecisionJob, map-only, after J06 and before J09: apply WorkPairFeatures and MatchPolicy.decide / decideBlocked from modules/entity-resolution to the J03+J04 matches and conflicts and the J06 blocking candidates, writing MatchDecision records with counters per outcome and match path. J10 must never call Bedrock or any adjudication code, and the hadoop jar must still bundle no AWS SDK. J09 exports the decisions, partitioned by entity type and decision.
5. Run the full chain locally on the full inputs, with max-block-size 150, under scratch/hadoop/wo5-full/. Check free disk first and stop with BLOCKED-ON if less than 5 GiB would remain.
Update JOBS.md. Run sbt check twice. Report all counters per job, decisions by outcome and path, disk used, and run time.

## Work order 7: entity-resolution-engineer (recalibrate and regenerate the review queue)

Use the entity-resolution-engineer agent to, using the work order 6 outputs under scratch/hadoop/wo5-full/: (1) rerun score calibration, including work pairs now that full enrichment data exists, and report whether the work weights or thresholds should change (report only; do not change them without a human ruling); (2) regenerate the gold-set review queue with at least 250 researcher and about 50 work pairs, where the work pairs now include non-exact paths. Name the strata file without the word "key" in its file name. Keep the labeling CSV blind. Run sbt check twice if any source changes. Return a standard report.

## Human rulings after work order 7

- Blocking volume at max-block-size 150 is accepted: 26.67M blocked candidates on full enrichment, against 5.92M predicted by calibration on sample-sized enrichment. No block-size recalibration in Phase 5. Recorded as a residual risk.
- The J06 work-pairs/ output that feeds J10 is accepted as a design deviation from work order 6.
- Policy er-v2 adopts two work rules: (a) SAME_SOURCE_SHARED_DOI: a blocked WORK pair whose two records come from the same source and share a DOI is CONFLICT, consistent with J03+J04; (b) a deterministic work rule: identical normalized title, identical author sequence, and year distance at most 1 is ACCEPTED on the DETERMINISTIC path, regardless of venue. The identifier step still runs first, so an identifier disagreement still gives CONFLICT or REJECTED as in er-v1.
- er-v2 does not change researcher rules or weights. The affiliation-weight proposal is declined for Phase 5: it would accept more researcher pairs before any gold evaluation, and over-merging corrupts downstream metrics, while splitting is the documented conservative failure.
- Thresholds are unchanged: accept 0.93, reject 0.35, LLM band [0.65, 0.93].
- er-v2 is decided from silver calibration only, before any gold label is evaluated. Gold labels must not be used to tune er-v2.
- The gold set is the work order 7 queue in scratch/er/review-queue-wo5/. It is not resampled. After the er-v2 rerun, its rows are re-keyed to the er-v2 decisions, and both er-v1 and er-v2 are evaluated on the same labels.
- Calibration evidence is archived in docs/agent-runs/phase-05/evidence/ (human-owned).
- Work orders 8 to 11 run in a fourth orchestrator session, to limit context size.

## Work order 8: entity-resolution-engineer (er-v2 policy)

Use the entity-resolution-engineer agent to implement policy er-v2 in modules/entity-resolution, as ruled in "Human rulings after work order 7":
1. Add the SAME_SOURCE_SHARED_DOI rule for blocked WORK pairs.
2. Add the deterministic work rule (identical normalized title, identical author sequence, year distance at most 1, venue ignored). It runs after the identifier step.
3. Keep er-v1 selectable by policy version with unchanged behavior, so the er-v1 outputs on disk stay reproducible. Researcher rules, weights, and thresholds are identical in both versions.
4. Add hand-checkable tests: two OpenAlex works sharing a DOI (CONFLICT), an ACM dual-DOI pair with the same title and authors but different DOIs (not ACCEPTED), a DOI-less pair that the new deterministic rule accepts, a one-year-gap pair, and a test that er-v1 decisions are unchanged.
5. Update Calibrate and ReviewQueue so they accept a run whose matchPolicyVersion is er-v1 or er-v2.
Do not edit scholarchain.conf; domain-engineer owns it. Run sbt check twice. Report the full rule order for both versions, the new rule IDs, and the test counts.

## Work order 9: domain-engineer (policy version)

Use the domain-engineer agent to set the entity-resolution policy version in modules/core/src/main/resources/scholarchain.conf to er-v2, and update any test that asserts the old value. Change the max-block-size comment to cite docs/agent-runs/phase-05/evidence/block-size.tsv instead of the scratch path. Keep max-block-size and all thresholds unchanged. If no policy-version key exists, report BLOCKED-ON instead of inventing one. Run sbt check twice. Return a standard report.

## Work order 10: hadoop-engineer (er-v2 rerun)

Use the hadoop-engineer agent to rerun the full Hadoop chain with policy er-v2 on the same full inputs as work order 6, under scratch/hadoop/wo5-v2/. Do not change J01 to J09 logic; change code only if the policy-version check or tests require it. Do not delete scratch/hadoop/wo5-full/. Check free disk first and stop with BLOCKED-ON if less than 5 GiB would remain. Run sbt check twice if any source changes. Report counters per job, decisions by entity type, path, and outcome, and the difference from the work order 6 run for each path and outcome. Confirm that every RESEARCHER decision count is identical to work order 6, and that no WORK pair is ACCEPTED where J03+J04 reported CONFLICT for the same DOI group.

## Work order 11: entity-resolution-engineer (re-key the gold queue)

Use the entity-resolution-engineer agent to create an er-v2 strata file for the existing review queue in scratch/er/review-queue-wo5/, using the work order 10 run in scratch/hadoop/wo5-v2/. Do not resample: keep the same row IDs and pairs, and take decision, path, and stratum fields from the er-v2 decisions. Write it as scratch/er/review-queue-wo5/review-queue-20261103-strata-er-v2.csv. Leave the blind CSV and the er-v1 strata file unchanged. Report how many queued pairs changed decision, by entity type. If Evaluate needs a change to score rows whose current decision differs from their sampling stratum, make it and test it. Do not read labels/. Run sbt check twice if any source changes. Return a standard report.

## Human rulings after work order 11

- Work order 9 returned BLOCKED-ON (hadoop-engineer). It was resolved under option (a): the er-v2 config was kept and work order 10 made J10 select the configured policy version. sbt check is green after work orders 10 and 11.
- Transitive work conflict (work order 10, risk 1) is a Phase 6 carry-forward. Spark clustering over ACCEPTED pairs, for researchers and works, must refuse any cluster that would contain two records with a CONFLICT decision between them (including J03+J04 conflict DOI groups) or two different valid ORCIDs, and must count and report refusals. No Phase 5 change.
- The run ID does not include the policy version or threshold overrides (work order 10, risk 2). Accepted as a residual risk; the manifest records both. Fix it in the next hadoop work order that touches Pipeline, or in the Phase 5 repair round if the reviewer rates it major or above.
- Uneven er-v2 gold cells (work order 11 risks) are accepted. er-v1 is the stratified evaluation. er-v2 is reported with per-cell row counts and no stratified claim for paths whose rows were sampled from other strata.
- er-v2 (run-4db46b8bcc1d3473446298ac6705f149 in scratch/hadoop/wo5-v2/) is the decision set Phase 6 consumes. scratch/hadoop/wo5-full/ (er-v1) is deleted after the Phase 5 gate.
- Per D-10, test-engineer runs once in Phase 11, not in Phase 5.
- The Phase 5 code review runs in a fifth orchestrator session.

## Code review: code-reviewer

Use the code-reviewer agent to perform a read-only review of the complete Phase 5 change set against work orders 1 to 11 and all human rulings in docs/agent-runs/phase-05/prompt.md, the Phase 5 section of spec/ScholarChain-spec.md, and decisions D-05, D-09, D-10, and D-13 in spec/architect-decisions.md.

Scope: every uncommitted change shown by git status in datasets/, modules/core/, modules/entity-resolution/, modules/hadoop/, build.sbt, and project/Dependencies.scala. Many modules/entity-resolution files are untracked, so read untracked files in full rather than relying on git diff. Do not read anything under labels/ or scratch/er/review-queue-wo5/.

Check in this order:
1. Exact-identifier conflicts never silently merge, under er-v1 and er-v2.
2. The LLM adjudication path can abstain, validates strict JSON, cannot create identifiers, and is never called from Hadoop.
3. No leakage from gold labels into matching, calibration, or sampling.
4. Policy versioning: er-v1 is reproducible, er-v2 is selected only from configuration, and decisions record their version.
5. J10 correctness, determinism, counters, and provenance on every decision.
6. The hadoop job jar contains no AWS SDK. Dependency scopes and pins follow D-09.
7. Dataset manifest and scripts: bounded by default, no invented licenses or sources.
8. Tests that would pass even if the behavior were broken.

Verify every finding against the actual files. Report only findings rated critical or major, each with severity, file and line, a concrete failure input, expected impact, and owning agent. List minor findings in one short section without detail. Return APPROVE or REQUEST_CHANGES. Do not edit any file.
