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
