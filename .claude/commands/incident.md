---
description: Diagnose a ScholarChain production or pipeline incident through the incident-responder and escalate data, security, or destructive decisions
argument-hint: <symptom or alarm> [environment] [trace or run ID]
---

Incident reported: $ARGUMENTS

Responsible agent: **incident-responder**. The orchestrator delegates. It does not diagnose inline.

## Gates

1. Diagnosis is read-only first: ECS state and stopped-task reasons, CloudWatch logs, metrics, and alarms, SQS and dead-letter queues, Step Functions, EMR steps, Neptune, DynamoDB, S3 Vectors, and Bedrock client metrics, plus `operations/` records.
2. Remediation is limited to the bounded actions in `docs/agents.md` (rollback to last known-good, task restart, ECS desired count within 1 to 4). Every action cites its evidence, and the human confirms it through the permission prompt.
3. Data repair or deletion, schema rollback, Terraform, IAM, KMS, network, EMR resizing or termination, scaling beyond bounds, and any security finding stop and escalate to the human architect. Suspected secret exposure means stop immediately, without echoing the value.
4. If no credible hypothesis emerges within the timebox, the agent escalates with an evidence dossier.

## Procedure

1. Use the **incident-responder** agent with a work order containing the symptom, environment, IDs, and timebox.
2. It triages, forms an evidence-backed hypothesis, acts only within the gates, and verifies recovery with `scripts/smoke-test.sh` when it acted.
3. It writes `docs/incidents/<UTC-date>-<slug>.md` with every required incident field.
4. The orchestrator routes code, script, Terraform, or configuration fixes to their owners in `docs/agents.md`, and presents any required human decision.

Report: incident ID, impact, timeline, root cause or best hypothesis, actions taken with evidence, data-integrity assessment, follow-up owner, preventive test or control, and escalations.
