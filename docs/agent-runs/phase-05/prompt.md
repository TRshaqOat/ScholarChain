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
