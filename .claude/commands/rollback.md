---
description: Roll a live ScholarChain service back to its last known-good revision through the incident-responder, with bounded autonomy
argument-hint: <environment> <service> [reason]
---

Roll back ScholarChain with these arguments: $ARGUMENTS

Responsible agent: **incident-responder**. The orchestrator delegates. It does not run rollback steps inline. (A failing in-flight deployment is rolled back by the **deploy-engineer** inside `/deploy`. This command is for an already-live revision.)

## Gates

1. The environment and service are named, and the last known-good revision is identified from a record under `operations/releases/` or `operations/deployments/`. If no such record exists, stop and report BLOCKED-ON human architect.
2. Autonomous actions are limited to `scripts/rollback.sh` to that revision, an ECS task restart, and ECS desired count within 1 to 4 (`docs/agents.md`, Escalation policy). The human confirms each action through the permission prompt.
3. Anything touching data, schema, contracts, Terraform, IAM, KMS, network, EMR capacity, or security stops for human approval first. No destructive data repair.

## Procedure

1. Use the **incident-responder** agent with a work order containing the arguments, the target revision record, and the timebox.
2. It gathers read-only evidence, then runs `scripts/rollback.sh`, polls service state with bounded timeouts, and verifies recovery with `scripts/smoke-test.sh`.
3. It writes `docs/incidents/<UTC-date>-<slug>.md` and returns its report.
4. Before any redeploy, the orchestrator routes the root cause to the owning agent named in that report.

Report: revision now live, evidence that justified the rollback, smoke-test results, incident report path, and the follow-up owner.
