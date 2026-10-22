---
description: Run an approved ScholarChain elasticity, performance, or evaluation experiment through the experiment-engineer
argument-hint: <experiment-manifest-path> <environment> <approval-reference>
---

Run the ScholarChain experiment with these arguments: $ARGUMENTS

Responsible agent: **experiment-engineer**. The orchestrator delegates. It does not run experiments inline.

## Gates (all must hold before delegation; otherwise stop and report BLOCKED-ON human architect)

1. The manifest is committed and reviewed before the run. It records the commit SHA, dataset snapshot, configuration, resource limits, Region, budget bound, and planned duration (long enough to capture scale-in).
2. The environment is deployed from an approved plan, and its latest deployment record exists under `operations/deployments/`.
3. Scale stays within the manifest's approved bounds. Failure injection is non-destructive and named in the manifest.
4. No production algorithm, threshold, prompt, or capacity change during or for the experiment.

## Procedure

1. Use the **experiment-engineer** agent with a work order containing the manifest path, environment, and approval reference.
2. It runs the approved runner (`scripts/run-experiment.sh` or the manifest-driven equivalent; each run is confirmed through the permission prompt) and collects raw metrics with synchronized UTC timestamps.
3. Raw results are immutable. Large raw data goes to the approved S3 prefix with checksums recorded under `experiments/`. Failed runs are kept.
4. Defects found during a run are routed by the orchestrator to their owners, and any fix is measured as a new experiment. The **documentation-engineer** writes up results only after raw outputs are frozen.

Report: manifest and commit, run IDs, raw result locations and checksums, scale-out and scale-in events, latency percentiles, throughput, errors, estimated cost, failed runs, and limitations.
