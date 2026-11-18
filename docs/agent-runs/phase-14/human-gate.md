# Phase 14 human gate: apply and deploy through human approval (spec Phase 14; cloud windows 1 and 2)

Date: 2026-11-18
Human architect: Achyut Patel

## What was reviewed
- Work orders (agent-report.md):
  - work order 1, deploy-engineer, window 1 deploy: attempt 1 FAILED (P14-D1, ruling 11), attempt 2 COMPLETE (43 added);
  - work order 5, experiment-engineer: the experiment D and F harnesses; E BLOCKED-ON (no retrieval-mode switch, ruling 31);
  - work order 8, deploy-engineer: smoke test 1 FAIL (ruling 44).
- Repairs:
  - repair-01: P14-D1 to D4 (ruling 12);
  - repair-02 (human exception, ruling 14): P14-D5;
  - repair-03 (human exception, ruling 19): P14-D6 and D7;
  - repair-04 (human exception, ruling 33): code review 4 findings 1 to 9.
- Code reviews (review-report.md):
  - 1: APPROVE;
  - 2: APPROVE;
  - 3: APPROVE (0/0/3);
  - 4: REQUEST_CHANGES (0/1/9; the major: D's abort did not stop the load);
  - 5: APPROVE (0/0/2).
  - Change sets and logs are in evidence/repair-01 to repair-04/, evidence/code-review-4/ and evidence/wo5/.
- Final tree, scripts/check.sh at 3ae999c: exit 0, every gate OK (shellcheck not installed).
  - sbt: 839 passed, 0 failed, 10 pending (core 191, entityResolution 111, rag 116, storage 115, services 49, sparkAnalytics 46, tests 141 + 10 pending, hadoop 70).
  - Hook self-tests: guard-dangerous 304/304, guard-paths 142/142, verify-tests-ran 11/11; validate-agent-files 231/231.
  - Dataset validation, terraform fmt and validate, bash -n: OK.
  - Log: evidence/gate/check-sh-3ae999c.txt.
- Window 1 (Nov 16 to 17; rulings 1 to 22; evidence/window-1/):
  - Fresh plan before apply (ruling 3); apply 43 added; four images packaged.
  - Size scaling on EMR at deef295 (x = DBLP works 27,993 / 55,598 / 110,867): Hadoop 17.2 / 27.6 / 53.3 min; Spark complete at 25 percent (13.3 min), out of memory in job 10 at 50 and 100 percent (P14-D8, open).
  - 5.74 cluster hours, about $3.32.
- Window 2 (Nov 17 to 18; rulings 23 to 49; operations/):
  - Plan 20261117T201324Z-506eddf46f23 at 506eddf, sha256 ba816190fb85a236d10641c531980af10456bd1ea8b9a0400a6a81428f0c2ea0: 89 to add, 1 to change (the S3 SSE-C default, ruling 38), 0 to destroy. Approval record operations/plans/20261117T201324Z-506eddf46f23-APPROVED.txt.
  - Apply human-run (ruling 36); API key secret set (value never displayed); state backed up.
  - Loads (rulings 39 to 43): S3 objects verified by stored SHA-256 (609 of 609); DynamoDB and S3 Vectors (13,023 vectors) loaded; the Neptune bulk load ran out of memory on db.t4g.medium, was rerun on db.r6g.large (LOAD_COMPLETED, 29,054,998 records), and the instance went back to db.t4g.medium.
  - deploy.sh --resume exit 0 with digests verified: release-20261117T232233Z-506eddf46f23.json.
  - Smoke 1 FAIL (Neptune timeout, cold cache after the resize, ruling 44); smoke 2 PASS, all six checks (ruling 45).
  - Experiment F PASS (ruling 46). Experiment D: attempt 1 stopped at its preflight (504, cold); attempt 2 PASS, all four levels, 0 errors, no scale-out (ruling 47).
  - Cleanup runtime (ruling 48): two applies (the aws login session expired during the first), 89 runtime resources removed; record operations/cleanup/cleanup-20261118T181724Z-runtime/record.json.
  - Window 2 ran 20:30:59Z Nov 17 to 18:18:25Z Nov 18, about 21.8 h (ruling 26 limit 24 h).

## Before starting (spec)
- [x] Plan checksum verified by the human before each apply (window 1: ruling 3; window 2: the approval record above).
- [x] Target account 834519512655 and Region us-east-2 confirmed; every script printed its TARGET line.
- [x] Cost bounds confirmed: ruling 8 (window 1 cap $5) and ruling 26 (window 2 at most about 24 h; stop at $20 month-to-date).
- [x] No unexplained destroy or replacement: window 1 43/0/0; window 2 89/1/0, the change explained (ruling 38).
- [x] Documented deployment command approved: deploy.sh with the plan file and the approved plan sha256 (rulings 4 and 36).

## Phase 14 gate (spec)
- [x] Services are healthy and ready: smoke 2 healthz and readyz passed (operations/releases/smoke-20261118T061612Z.json); query-api, graph-evidence-api and worker each at desired = running = 1 during experiment D and at the Nov 18 morning check.
- [x] Deployed image digests match the commit: all three services ran sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea, the package digest of the window 2 image deef295 (ruling 23), verified by deploy.sh --resume (release-20261117T232233Z-506eddf46f23.json).
- [x] Smoke query returns an evidence-backed response: smoke 2 query qry-41d3b96234f65188482930ad99486165, 2 claims, evidence ev-927665f3bbac87eb60685e50cfeb8f9b (COAUTHOR_EDGE), evidence fetch, idempotent replay, 401 without a key (ruling 45).
- [x] No critical alarm remains active (CloudWatch alarm history, evidence/gate/alarm-history-window2.txt): every alarm started OK at 14:33-14:44 CST Nov 17. No ALB 5xx, unhealthy-target or ECS CPU alarm ever left OK, including during smoke 1 and the first D preflight. neptune-cpu went to ALARM twice, both during the Neptune bulk loads (17:28-17:32 and 21:25-22:15 CST), and cleared both times. At cleanup only two alarms were in ALARM, neither a fault: the job DLQ alarm, set at 00:38 CST Nov 18 by experiment F's deliberate poison message (ruling 35), and the graph-evidence-api target-tracking AlarmLow, the scale-in signal of an idle service. Cleanup runtime removed every alarm (ruling 48).
- [x] Deployment report archived: agent-report.md (work orders 1 and 8), operations/deployments/ and operations/releases/ (commits 35fccb5 and 3ae999c), the human-run deploy logs p14-w2-deploy-1 to 3.

## Human rulings recorded at the gate
- Rulings 1 to 49 are in prompt.md. Rulings 38 to 49 were written to operations/rulings/ while HEAD was pinned and appended after cleanup (rulings 11 and 37).
- Human exceptions to the one-repair-round rule: rulings 14, 19 and 33.
- Accepted deviations:
  - read-only git by the orchestrator or an agent (rulings 20 and 32);
  - window 2 deploy and the smoke rerun human-run (rulings 36 and 44);
  - local copies of load-stores.sh hidden by .git/info/exclude, diffs saved (rulings 40 and 41), removed afterwards;
  - the Neptune class changed outside Terraform for the bulk load and restored (rulings 42 and 43);
  - experiment E deferred to Phase 15+16 (ruling 31);
  - the demo video not recorded in window 2; a possible window 3 is decided in Phase 15+16 planning (ruling 49).

## Decision
ACCEPTED with documented deviations. Code review 5 APPROVE; smoke 2 PASS; experiments F and D PASS; cleanup runtime complete.

## Manual changes
- No manual edits to agent-owned source.
- Human-authored: the prompt rulings, this gate, the evidence copies, the factory-metrics rows, the work order 8 report appended verbatim to agent-report.md (ruling 5), the session-token redaction in three operations/ files (ruling 41), and the removal of the load-stores.sh copies.
- Human-run: plans, applies, the secret, the loads, the Neptune resizes and status tasks, the smoke rerun, experiments D and F, cleanup runtime.
- External assistance: none in window 1 or window 2.
- Process notes: a Ctrl+C during plan.sh left a stale lock (ruling 38); the aws login session expired during cleanup apply 1 (ruling 48). Guard false positives as listed in the handoff; no hook was worked around.

## Remaining risks
- P14-D8 open: EMR aggregation classes out of memory in Spark job 10 at 50 and 100 percent; option B (executor cores 2) deferred (ruling 21).
- Neptune on db.t4g.medium: the bulk load of this graph needs a larger class; high-degree neighbor queries sit near the query deadline when the cache is cold (rulings 42 to 47).
- Demonstration: the live items and scale-out evidence need a window 3 (ruling 49). The API key secret is in its 7-day recovery window until about Nov 25; an apply before then must restore or force-delete scholarchain-course/api-key, because a new secret cannot take a name pending deletion.
- The S3 SSE-C setting drifts back on every plan; follow-up: declare blocked_encryption_types = ["SSE-C"] (rulings 38 and 48).
- Follow-ups from window 2: LoadS3Cli verifies by HEAD checksums (ruling 40); the AWS SDK logs at INFO and load-stores.sh reads the load ID with filter-log-events (ruling 41).
- Experiment E not run yet (ruling 31).
- Residual minors: repair-02 and repair-03 minors (Phase 17), code review 4 finding 10 and the smoke-test.sh xtrace gap (ruling 34), code review 5 minors 1 and 2 (ruling 35).
- Phase 12 carry-over: M1 answered (the bulk load succeeded with the prefix-conditioned ListBucket); M2, M3, R2 to R4 and N3 remain.
- The services image has 10 CRITICAL and 48 HIGH scan findings (ruling 25).
- Log retention: CloudWatch log groups keep 14 days (window 2 logs until about Dec 1); the S3 logs/ prefix keeps 30 days. Archive what grading needs before then.
- Cost: month-to-date before credits $10.09 at 12:18 CST Nov 18; billing lags about a day.
- shellcheck was not run.

## Accepted commit
Recorded automatically in commit.txt by phase_commit.
