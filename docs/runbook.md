# ScholarChain runbook

Owner: documentation-engineer. Operating procedures for the course environment (us-east-2). The script contracts are authoritative in [`scripts/README.md`](../scripts/README.md) and in each script's `--help`; who may run what is set by [`docs/agents.md`](agents.md) ("Exceptions and clarifications", item 8, and "Escalation policy and human gates"). None of the cloud commands below was replayed for this document: the runtime was destroyed on Nov 20, 2026 and only the 43 base resources remain ([`operations/cleanup/cleanup-20261120T041658Z-runtime/`](../operations/cleanup/cleanup-20261120T041658Z-runtime/record.json)). Each command names its source.

## 1. Before any cloud step

- Clean tree. `plan.sh`, `deploy.sh`, `package.sh`, `run-bounded-pipeline.sh`, `load-stores.sh`, `run-experiment.sh` and `cleanup.sh` stop with exit 3 on any change outside `operations/` and `experiments/results/`, untracked files included; `deploy.sh` also needs HEAD equal to the plan's commit ([`scripts/README.md`](../scripts/README.md) "Conventions", `deploy.sh`).
- `aws login` immediately before each long script. Every script prints `==> TARGET account=... region=... profile=...` first; check it. `--region` accepts only `us-east-2`.
- Exit codes: 0 success, 1 a gate failed, 2 usage or missing tool, 3 precondition, 4 approval mismatch, 5 waiting for a human action, 6 cloud timeout.
- In window 3 every script was human-run (Phase 15 ruling 3). The window time and cost caps are set by ruling before each window (Phase 14 ruling 26; Phase 15 ruling 3).

## 2. Deployment, in the corrected window order

From [`scripts/README.md`](../scripts/README.md) "The flow" (window 3, the last deployed release). The order is apply, secret, loads, then `deploy.sh --resume`, because query-api and the worker exit at startup until `load-stores.sh` has uploaded the window-summary object (Phase 14 ruling 39).

```text
  human:  scripts/plan.sh --runtime-enabled true                                         -> sign the sha256
  human:  scripts/deploy.sh --plan-file <plan> --approved-plan-sha256 <sha> --package-record <rec>
  human:  sets the API key secret value; backs up the state
  human:  Neptune scholarchain-course-graph-1 to db.r6g.large (--apply-immediately; temporary drift from
          Terraform, Phase 14 rulings 42 and 43: the bulk load needs more than db.t4g.medium's memory)
  human:  scripts/load-stores.sh --store-run <run> --vectors-run <run>
            --skip s3-store-conversion,s3-embed,s3-window-summary --neptune-timeout-minutes 180
  human:  Neptune back to db.t4g.medium, waits until available (Terraform and the instance agree again)
  human:  the same deploy.sh command + --resume
  human:  Neptune warm-up (Phase 14 rulings 46 and 47: three sequential neighbors GETs for each of the two
          researchers, API key on standard input), then scripts/smoke-test.sh
  human:  demonstration recording; scripts/run-experiment.sh --manifest experiments/manifests/exp-d-scale-v1.json
  human:  scripts/archive-evidence.sh; scripts/cleanup.sh --stage runtime; backs up the state
```

Notes from the window records:

- `plan.sh` needs `TF_VAR_alarm_email` (empty was used in windows 2 and 3) and `AWS_PROFILE`; it writes the plan, its sha256 and `summary.md` under `scratch/plans/<stamp>-<commit12>/` and copies the summary to [`operations/plans/`](../operations/plans/). The human writes an approval record beside it (example: [`operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt`](../operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt)).
- Pass `--plan-file` as printed by `plan.sh`. `deploy.sh` exits 4 on a checksum mismatch and stops with exit 5 until the API key secret has a value; the human sets it by the stdin pattern in [`infra/terraform/README.md`](../infra/terraform/README.md) (Phase 14 ruling 36).
- Start `load-stores.sh` only after `describe-db-instances` shows `scholarchain-course-graph-1` as `db.r6g.large`, available, with no pending modified values. Once `==> NEPTUNE load=<id>` has printed, never rerun `load-stores.sh` without `neptune` in `--skip`; record the load ID and check it with manual status tasks first (Phase 15 ruling 7(c)).
- Recorded window 3 outcome: plan `978ce305...` 89 to add, 0 to change, 0 to destroy; Neptune load 6113dd40 LOAD_COMPLETED with 34,052,689 records; DynamoDB 876,228 items; 13,023 vectors; `deploy.sh --resume` exit 0 with digest `sha256:5c751b1e...` on all three services (Phase 15 ruling 10; [`operations/releases/release-20261119T233215Z-c5428063732a.json`](../operations/releases/release-20261119T233215Z-c5428063732a.json)).

## 3. Neptune warm-up and smoke test

- **Warm-up.** Three sequential `GET /v1/graph/nodes/<researcherId>/neighbors` requests (query string `direction=both&depth=1&limit=50&edgeTypes=COAUTHORED_WITH`, as in `load-tests/exp-d-query-load.py` line 73) for each of the two B07 researchers, `scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763` and `scr:canon-v1.er-v2:da6e3bdff14745638f3f5425f0133388`, with the API key on standard input as in the README API examples (`curl --config -`). Parameters: [`docs/api-reference.md`](api-reference.md). Expected: every response 200. In window 2 the high-degree node took 3.40 s cold, then 0.55 to 0.60 s (Phase 14 ruling 47); in window 3 the warm-up returned two 504s, then 200 (Phase 15 ruling 10(d)). The exact curl lines of the warm-ups are not recorded in the repository.
- **Smoke test** (source: [`scripts/README.md`](../scripts/README.md) `smoke-test.sh`):

  ```bash
  scripts/smoke-test.sh --profile <profile> --dry-run
  scripts/smoke-test.sh --profile <profile>
  ```

  Six checks: `/healthz` 200, `/readyz` 200, a sync B07 query with a fresh Idempotency-Key returning a `qry-` ID and a claim with an evidence ID, that evidence fetched, the same POST replaying the same queryId and answerHash, and a POST without `X-Api-Key` answering 401. Record: `operations/releases/smoke-<stamp>.json` and the bodies in `smoke-<stamp>/`. The last PASS: [`operations/releases/smoke-20261119T235713Z.json`](../operations/releases/smoke-20261119T235713Z.json). Never rerun after a failure without a ruling; a failure goes to the rollback gate (section 5).

## 4. Common failures

| Symptom | Cause | Action | Record |
|---|---|---|---|
| A long script fails mid-run with a credential error (for example `credential_process` exit 255) | The `aws login` session expired during the run. In window 2 cleanup apply 1 destroyed 77 resources, then failed while waiting on the Neptune delete. Earlier, a live embedding run stopped on "The security token included in the request is expired". | Run `aws login` immediately before each long script. After a partial apply: confirm no terraform process and no lock file, back up the state, run a fresh plan, check it, apply it. The embedding client now refreshes credentials and retries once on an expired token. | Phase 14 ruling 48; [`docs/agent-runs/phase-07/human-gate.md`](agent-runs/phase-07/human-gate.md) |
| Smoke query 502 `DOWNSTREAM_FAILURE`, or neighbors 504 `DOWNSTREAM_TIMEOUT`, right after a resize | Cold Neptune buffer cache on `db.t4g.medium`: graph queries hit `TimeLimitExceededException` at the query timeout. | Warm up (section 3) before the smoke test and any load test. If the smoke test fails again with a Neptune timeout, the window 2 ruling allowed a resize to `db.r6g.large`, reported as a deviation. | Phase 14 rulings 44 to 47 |
| Neptune bulk load ends `LOAD_CANCELLED_DUE_TO_ERRORS` with `OUT_OF_MEMORY_ERROR` | The bulk load of this graph needs more memory than `db.t4g.medium` (4 GiB). | Resize to `db.r6g.large` with `--apply-immediately`, start one new load of the same run (already-loaded elements are duplicates and are not changed), poll status until LOAD_COMPLETED, resize back to `db.t4g.medium` before measurements. | Phase 14 rulings 42 and 43 |
| `plan.sh` or `cleanup.sh` cannot acquire the state lock | An interrupted run (Ctrl+C during `plan.sh`) left a stale lock and an empty plan directory. | Confirm that no process holds the state, then clear the lock. The exact command used is not recorded. | Phase 14 ruling 38 |
| `load-stores.sh` step `s3-store-conversion` exits 1 after every object was uploaded | `LoadS3Cli` verified the run by downloading every object under a fixed 120 s per-call timeout; laptop downloads of the 200 MB DynamoDB files were too slow. | Check the objects against the manifest with S3's stored SHA-256 (HEAD, no download), then continue without the S3 steps (`--skip s3-store-conversion,s3-embed,s3-window-summary`). A HEAD-checksum verify in `LoadS3Cli` is a storage-engineer follow-up. | Phase 14 ruling 40 |
| `load-stores.sh` cannot find the Neptune load ID | The loader logs the AWS SDK at DEBUG, and the first page of `get-log-events` missed the `started load` line. | Fixed: the script reads with `filter-log-events` across every page and re-reads up to 3 times. Session-token lines are dropped from records (see [`docs/threat-model.md`](threat-model.md)). | Phase 14 ruling 41; Phase 15 repair-01 part A |
| `deploy.sh --resume` exits 1; query-api and the worker exit at startup with `OBJECT_MISSING` | The window-summary object was not yet in the data bucket (resume before loads). | Run `load-stores.sh` first, then `--resume` (section 2). | Phase 14 ruling 39 |
| Apply fails because the secret name `scholarchain-course/api-key` is taken | The previous cleanup left the secret in its 7-day recovery window; a new secret cannot take a name pending deletion. | Before the plan, restore and then force-delete the secret (human). The current secret is pending deletion until about Nov 26 to 27, 2026. | Phase 14 gate; Phase 15 ruling 3; [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md) |
| A script stops with exit 3, dirty tree | A change or untracked file outside `operations/` and `experiments/results/` (for example an agent report under `docs/agent-runs/` during a window). | Commit or move the file before the plan; nothing is committed between the plan and cleanup. Hiding a file with `.git/info/exclude` was used only under rulings 40 and 41 and removed afterwards. | [`scripts/README.md`](../scripts/README.md) "Conventions"; Phase 14 ruling 5 |
| `deploy.sh` or `cleanup.sh` passes its checksum gate but terraform cannot load the plan | A relative `--plan-file` (terraform runs with `-chdir`). | `deploy.sh` now resolves the path (P14-D1); `cleanup.sh` still needs the absolute path. | Phase 14 rulings 11 and 12 |

## 5. Rollback

From [`scripts/README.md`](../scripts/README.md) `rollback.sh`:

```bash
scripts/rollback.sh --list
scripts/rollback.sh --to-release operations/releases/release-<stamp>-<sha12>.json --reason "<text>" --dry-run
scripts/rollback.sh --to-release operations/releases/release-<stamp>-<sha12>.json --reason "<text>"
```

- It moves query-api, graph-evidence-api and the worker together to the image of a known-good release record (new task definition revisions, no Terraform apply), waits until stable, and checks digests and runtime run IDs. Record: `operations/releases/rollback-<stamp>.json`.
- Who: the deploy-engineer for its own failing in-flight deployment (inside `/deploy`); the incident-responder for an already-live revision through [`/rollback`](../.claude/commands/rollback.md). Each AWS mutation is approved at the permission prompt. A failing ECS deployment is first rolled back by the ECS deployment circuit breaker.
- Terraform state still names the newer image until `image_tag` in `course.tfvars` is changed and applied; the next plan shows that drift. Afterwards run the smoke test (section 3).
- The window rulings and gates of Phases 14 and 15 record no rollback run.

## 6. Incident escalation

- Open [`/incident`](../.claude/commands/incident.md) with the symptom, environment and IDs. The orchestrator delegates to the incident-responder; diagnosis is read-only first.
- Autonomous recovery is limited to `scripts/rollback.sh` to the last known-good release, an ECS task restart, and an ECS desired count within 1 to 4, each approved at the permission prompt ([`docs/agents.md`](agents.md) "Escalation policy and human gates").
- Data repair or deletion, schema rollback, Terraform, IAM, KMS, network, EMR resizing or termination, scaling beyond bounds and any security finding stop and escalate to the human architect. Suspected secret exposure: stop immediately without echoing the value.
- The report goes to `docs/incidents/<UTC-date>-<slug>.md` (incident-responder). During the windows, mid-window decisions were written by the human to [`operations/rulings/`](../operations/rulings/) because HEAD was pinned.

## 7. Cleanup (human only)

`scripts/cleanup.sh` is run by the human only; agents are denied by permission rules and blocked by `guard-dangerous.sh`. Source: [`scripts/README.md`](../scripts/README.md) `cleanup.sh`.

```bash
aws login   # immediately before
TF_VAR_alarm_email=<address or empty> scripts/cleanup.sh --stage infra
scripts/cleanup.sh --plan-file "$PWD/scratch/plans/cleanup-<stamp>-infra/plan.tfplan" --confirm <sha256>
# after the instructor approves the evidence archive location
TF_VAR_alarm_email=<address or empty> scripts/cleanup.sh --stage final --evidence-archive <location>
scripts/cleanup.sh --plan-file "$PWD/scratch/plans/cleanup-<stamp>-final/plan.tfplan" --confirm <sha256>
```

- The first invocation writes the inventory, the full plan, every resource destroyed and kept with its reason, and the plan sha256; nothing changes. The second applies only that plan, takes the second inventory, and writes `operations/cleanup/cleanup-<stamp>-<stage>/record.json`.
- `--plan-file` must be the absolute path: `cleanup.sh` passes it unchanged to terraform, which runs with `-chdir` (Phase 14 ruling 11).
- Stages: `runtime` destroys the stores, services, ALB, secret, alarms and runtime roles; `infra` the network, EMR roles and groups and CodeBuild; `final` everything in state except what survives because the data bucket and ECR repository are not empty. Ephemeral EMR clusters are listed, never terminated.
- Back up the Terraform state before and after.
- State now: the `runtime` stage ran on Nov 20, 2026 (89 destroyed, 43 kept). The final cleanup (`--stage infra`, then `--stage final`) is planned for December 15, 2026 (Phase 17 ruling 8).
