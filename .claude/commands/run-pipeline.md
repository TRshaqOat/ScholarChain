---
description: Run the bounded ScholarChain Hadoop and Spark data pipeline through the approved script via the deploy-engineer
argument-hint: <environment> <approved-commit-sha> <pipeline-manifest-path> <approval-reference>
---

Run the bounded ScholarChain pipeline with these arguments: $ARGUMENTS

Responsible agent: **deploy-engineer**. The orchestrator delegates. It does not submit EMR steps inline.

## Gates (all must hold before delegation; otherwise stop and report BLOCKED-ON human architect)

1. The environment is deployed from an approved plan, and the commit SHA matches an accepted phase with a clean working tree.
2. The pipeline manifest is committed and names the dataset snapshot, subset rule, run ID policy, EMR capacity bounds, and output prefixes. A full-corpus run or capacity beyond the approved bounds needs explicit human approval (`docs/agents.md`, Escalation 6).
3. Job jars come from `sbt packageJobs` at the approved commit, with recorded checksums.
4. `scripts/run-bounded-pipeline.sh` exists and was authored by the **infra-engineer**. If it is missing, stop with BLOCKED-ON infra-engineer.

## Procedure

1. Use the **deploy-engineer** agent with a work order containing the arguments and the approval reference.
2. It verifies the preconditions, runs `scripts/run-bounded-pipeline.sh` (confirmed through the permission prompt), and waits with bounded timeouts.
3. It records run IDs, dataset snapshot, Hadoop counters, Spark output counts, output prefixes, duration, and estimated cost in a new record under `operations/pipeline-runs/`.
4. Job failures are reported with evidence and routed by the orchestrator: parser and MapReduce defects to the **hadoop-engineer**, identity-resolution defects to the **entity-resolution-engineer**, graph and analytics defects to the **spark-engineer**, and load defects to the **storage-engineer**. Nobody terminates or resizes EMR clusters outside the script.

Report: run IDs, snapshot, counters, rejects, output locations, duration, and the record path.
