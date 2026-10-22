---
description: Deploy an approved ScholarChain commit and Terraform plan to an approved environment through the deploy-engineer
argument-hint: <environment> <approved-commit-sha> <approved-plan-sha256> <approval-reference>
---

Deploy ScholarChain with these arguments: $ARGUMENTS

Responsible agent: **deploy-engineer**. The orchestrator delegates. It does not run deployment steps inline.

## Gates (all must hold before delegation; otherwise stop and report BLOCKED-ON human architect)

1. All four arguments are present: environment, approved commit SHA, approved Terraform plan SHA-256 checksum, and the human approval reference (the human-gate record or written approval).
2. The human architect has verified the plan checksum, the target account and Region, the cost bounds, and the absence of unexplained destroy or stateful replacement (`docs/agents.md`, Escalation 4 and 5).
3. A **code-reviewer** `APPROVE` exists for the commit being deployed.
4. The working tree is clean and `HEAD` equals the approved commit SHA.

## Procedure

1. Use the **deploy-engineer** agent with a work order containing the arguments above, the approval reference, and the rollback policy: in-flight rollback of this deployment only, through `scripts/rollback.sh`.
2. The deploy-engineer verifies the preconditions, runs `scripts/deploy.sh` and `scripts/smoke-test.sh` exactly as documented (each confirmed through the permission prompt), verifies image digests and task revisions, and writes a new record under `operations/deployments/`.
3. If any gate fails, the deploy-engineer collects evidence, rolls back only its own in-flight deployment, and returns. The orchestrator then routes diagnosis through `/incident` to the **incident-responder**.
4. Script or Terraform defects are routed to the **infra-engineer**. Nobody edits scripts during a deployment.

Never run `terraform apply` or `terraform destroy` directly, and never mutate cloud resources outside the approved scripts.

Report: environment, commit SHA, plan checksum, image digests, task revisions, smoke-test results, alarms, rollback action (if any), and the record path.
