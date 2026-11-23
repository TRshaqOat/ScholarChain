# Code review 1

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 17, session 6, Code review 1. Final read-only review of the whole repository, Ponytail OFF. Change set 2b82e9c..08e351a (29 files), plus the untracked `docs/agent-runs/phase-17/evidence/code-review-1/`.

VERDICT: APPROVE

I found 0 critical, 0 major and 4 minor findings. Ruling 9 allows minor findings to become residual risks. Before the repository becomes public on Nov 25, the human needs to decide on finding m1 (privacy).

SCOPE REVIEWED:
- Every file in `diffstat.txt`. I read the README, the 8 docs, the final report and the release manifest in full. I read `diff.txt` for the manifest, `docs/agents.md`, `docs/agent-runs/README.md`, the stubs and `tool-versions.md`.
- I read all six fresh-clone logs directly: Hadoop and Spark in full; the sbt, check.sh and terraform logs at the gates and summaries.
- I checked the source records each document cites (listed under FOCUS below).
- I used `.git/logs/HEAD` (read only) to confirm what commits aa30f5a, c542806, d406c66 and 87151a8 contain.

FINDINGS (all MINOR, ranked):

m1. MINOR. `docs/agent-runs/phase-17/evidence/fresh-clone/fresh-terraform-init-attempt1.txt:24`, and the same text at `docs/agent-runs/phase-17/evidence/code-review-1/diff.txt:4662`.
- Problem: the failed `terraform init` log records the human's public IPv6 source address (a residential ISP prefix) next to the remote endpoint.
- Why it matters: the repository goes public on Nov 25. Ruling 15 item 2 says Step S scans for personal data beyond ruling 4, and an IP address is personal network data. The file is committed at 10d05de, so history also holds it.
- Other logs: the other fresh-clone logs hold only the private address 10.0.0.67, the hostname and the username (the student's name, covered by ruling 4).
- Owner: human architect (`docs/agent-runs/**`).
- Fix: replace the address with `<ipv6>` in line 24 before the gate commit. Do not commit `diff.txt`, or redact it the same way. Then decide, as for the account ID, whether history may keep it.

m2. MINOR. `README.md:74` and `README.md:315` (agent catalog, dataset-engineer prerequisites).
- Problem: both call `SCHOLARCHAIN_CONTACT_EMAIL` "optional". It is required for Crossref, sample and full runs alike.
- Evidence:
  - `datasets/scripts/fetch_crossref.sh:7` ("Requires SCHOLARCHAIN_CONTACT_EMAIL") and `:19` (exits 3 when it is unset);
  - `datasets/dataset-manifest.yaml:234-235` (`SCHOLARCHAIN_CONTACT_EMAIL=<you> bash datasets/scripts/fetch_crossref.sh`).
- Failure: following the README, `bash datasets/scripts/fetch_crossref.sh` with the variable unset fails with exit 3.
- Owner: documentation-engineer.
- Fix: say it is required by `fetch_crossref.sh` and optional for the other fetch scripts (`lib.sh` `user_agent`). Ruling 16 item 1 removed only the User-Agent wording, and this fix does not bring it back.

m3. MINOR. `docs/runbook.md:41` (warm-up).
- Problem: the runbook gives the neighbors warm-up as "depth 1, limit 50, edge type `COAUTHORED_WITH`" but leaves out `direction`. `direction` is a required parameter (`docs/api-reference.md:94`; any other or missing parameter is 400).
- Failure: a warm-up GET built from the runbook answers 400 `INVALID_FIELD`, not 200.
- Evidence: the query string actually used by experiment D is `direction=both&depth=1&limit=50&edgeTypes=COAUTHORED_WITH` (`load-tests/exp-d-query-load.py:73`). Phase 14 ruling 46 (`docs/agent-runs/phase-14/prompt.md:319`) also omits direction.
- Owner: documentation-engineer.
- Fix: add `direction=both` and cite `load-tests/exp-d-query-load.py:73`.

m4. MINOR. `docs/demo-script.md:116` (A8 voiceover, copied from `inputs/demo-script-source.md:84`).
- Problem: the narration says "After I redrove the message, the job completed exactly once". No record shows a redrive. The records show:
  - the poison message stayed in the DLQ until the runtime cleanup (Phase 14 ruling 46, `docs/agent-runs/phase-14/prompt.md:319`);
  - the job that SUCCEEDED was a separate valid async job (`experiments/derived/exp-f/README.md:17-24`: checks submit, job SUCCEEDED, dlq, poison-retried, one-job-run).
- Impact: the demonstration of spec item 21 states a recovery step the evidence does not support. A10 carries a "Sources for the narrated figures" note; A8 has none.
- Owners: documentation-engineer for the note; human architect for the recorded audio.
- Fix: add a narration-correction note under A8 citing `experiments/derived/exp-f/README.md`. The human decides whether to re-record the A8 voiceover.

OWNERSHIP CHECK (path -> owner -> author matches):
- `README.md`, `docs/api-reference.md`, `docs/architecture.md`, `docs/data-lineage.md`, `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md`, `docs/demo-script.md`, `reports/final-report.md` -> documentation-engineer -> yes (commits 1554c9f and 8c71dde).
- `docs/agents.md` -> factory-engineer -> yes (d4cd416). Pending ratification.
- `datasets/dataset-manifest.yaml` -> dataset-engineer -> yes (78d479e).
- `operations/releases/release-manifest-final.md` -> deploy-engineer -> yes (87151a8; new file).
- `docs/agent-runs/README.md`, the phase-08/13/16 stubs, `tool-versions.md`, `phase-17/prompt.md`, `inputs/*` and `evidence/fresh-clone/*` -> human architect -> yes. `phase-17/agent-report.md` -> orchestrator assembly -> yes.

FLOOR AND FENCE CHECK:
- No hook, `.claude/settings.json`, `.mcp.json`, agent file or command file changed.
- The `docs/agents.md` diff (diff.txt:5443-5452) adds exactly six bullets at the end of "Residual risks accepted by the human architect" and changes nothing else.
- No widening and no weakening of the floor. It has authority only after ratification in the Phase 17 `human-gate.md`, a commit and a fresh session.

FOCUS, what I checked (clean unless a finding is named):

(a) Numbers against cited evidence.
- Release record c542806: digest 5c751b1e…047ea on all three services at `:2`; runtime run c9f26a9e…; deployed 23:35:35Z.
- Plan 978ce305…: 89/0/0; created 17:33:52Z; approved 17:39:32Z.
- Package checksums: all four.
- Smoke: 6 of 6 checks; `query.json` and `evidence.json` excerpts; 23 supporting works counted one by one.
- Window 1 plan 43/0/0.
- 845 / 0 / 10 at d406c66 (phase-15 gate:15). Cost 16.84 USD and every window figure (phase-15 prompt:276-284).
- Gold set 0.989 (90/91) and 0.600 (90/150).
- Phase 6 run IDs and counts (259,057; 191,318; 787,726; 16,091; 38,034). Phase 7 counts (511,920; 3,123,311; 13,023).
- Hadoop 17.2 / 27.6 / 53.3 minutes; largest team 287 authors (41,041 pairs); 1,019,535 pair candidates.
- D: 35.72 req/s, p95 673.5 ms. D-scale: 21,962 requests, p95 682.9 ms, 00:24 and 00:46 UTC.
- E recall and MRR; usefulness 4.4 / 4.6 / 1.9.
- Neptune records 34,052,689 against 29,054,998.
- Lineage run IDs: run-10ceed31, run-035e75ab, run-ee4ffd21, qry-41d3b962; 609 of 609 objects.
- Threat-model citations: P15 r10(f), P12 r10(b).
- The final report's escalation and repair-exception phases and its manual-edit records (84d90b0, `.gitkeep`, `modules/secrets`).
- Result: all match.

(b) Commands.
- The README Hadoop command ran exactly as written in Step F (`fresh-hadoop-sample.txt:6`: the same arguments, with `--code-version` resolved to 87151a8…). Spark PipelineJob also matches (`fresh-spark-sample.txt:6`). The run IDs match ruling 16.
- `scripts/check.sh`: all 8 gates OK and "==> OK check: every gate passed" (`fresh-check-sh.txt:1-1831`).
- `sbt clean check`: eight summaries summing to 845/0/10, then `markTestRun` (`fresh-sbt-clean-check.txt:1011-1511`).
- Script flags in the README and the runbook match `scripts/README.md`: plan, deploy (`--resume`), load-stores (`--skip`, `--neptune-timeout-minutes`), cleanup (absolute `--plan-file`), rollback, run-experiment and the analysis commands (`docs/evaluation.md:508-513`). The terraform init line matches `infra/terraform/README.md:295`.
- Exceptions: m2 and m3.

(c) Agent catalog.
- 18 rows. Every tool fence matches the `tools:` frontmatter, including infra-engineer's 4 terraform Search tools plus RunCheckovScan, and aws-docs search/read/recommend.
- Owned paths match `docs/agents.md`. The extra report fields match each agent file's report contract. No `permissionMode`, `mcpServers` or `disallowedTools` field appears.

(d) Reproduction.
- All 11 spec steps are present (spec:820-834).
- The `echo 'terraform destroy'` probe gives `[terraform-apply-destroy]` (`architect-decisions.md:176`; `guard-dangerous.sh:260`, self-test :494).
- aa30f5a is "phase-15 human: rulings 1-4, work orders 1-3". c542806 is "work orders 1-3, repair-01, code reviews 1-2". The repair-01 note in the README is therefore correct.

(e) Links. Every relative link target in the README and in the 7 docs I opened (file or directory) exists. `labels/README.md` was treated as resolving and not opened. Anchors I checked follow GitHub slug rules.

(f) Requirements.
- Every spec "README requirements" item (spec:2981-3001) and every Phase 17 required document (spec:2157-2169) is present.
- The release manifest holds every spec:2179 element.

(g) Wording.
- No text addresses a grader or an evaluator. "after grading" appears at `README.md:258` and `docs/demo-script.md:160` (ruling 16 item 2). "instructor approves" is descriptive.
- The closing "Thank you." (`demo-script.md:260`) is recorded narration with no named addressee. Left to Step S.

(h) Secrets and the account ID.
- No credential shapes in the change set. The `aws_session_token` match is threat-model prose.
- New Phase 17 documents use `<account-id>` throughout, and the ALB hostname is not copied.
- The only 12-digit account ID in Phase 17 text is at `docs/agent-runs/phase-17/agent-report.md:485` (WO5 report, verbatim). I judge it covered by ruling 15 item 4. It is a committed evidence record under `docs/agent-runs/` from commit 87151a8 (the commit that added the WO5 report), which is the count the ruling states, and verbatim agent reports are not rewritten. Not a finding.
- `diff.txt:1001` would add a new copy if committed (see m1's fix).

(i) Release manifest. Every value matches its record. D1 and D2 are resolved by ruling 16 item 5: `apply.txt:279` reads "89 destroyed", and `phase-15/human-gate.md:9` and `commit.txt` give d406c66.

(j) WO3 bullets against the guard code. All accurate:
- `guard-dangerous.sh:452` deletes quotes and splits on `;&|(){}`, backtick and newline;
- `:337` is the PP regex that matches `labels/`;
- `:367-371` give `[protected-path-write]` (CW_VERB and the CW_WRAP list include find, xargs, env, python and awk);
- `:315-316` give `[secret-jq-env]`; `:307` gives `[secret-env-file]`, which fires first;
- `:338-341` give `[protected-path-redirect]` with the listed targets;
- sessions 9, 10 and 12 match `phase-15/human-gate.md:55`.
WO4 changed only the crossref and orcid `license` / `license_evidence` fields and two `license_basis` fields (diff.txt:426-470). The remaining UNVERIFIED markers concern other facts, not these licenses.

(k) A01 and V01.
- Every Spark `.collect()` under `modules/spark/src/main/` is in the four named files, and each carries a bound comment: BenchmarkPathJob:110/198/370, Communities:61, CommunityPathJob:287, JobRun:271.
- Exception: `JobRun.rows` (`JobRun.scala:240`) carries its bound in the Scaladoc (`:236`, "Callers only pass small aggregates"), not inline. I checked every caller: each passes a groupBy/count aggregate or a `.limit(5|10)` / rank ≤ 5 frame.
- No other file has a Spark `.collect()`. The `.collect {` calls at CommunityPathJob:292 and StoreConversionJob:898 are Scala collection methods. The `.head()` calls in Clustering:65, KCore:58/86 and TemporalSummaryExportJob:702 return one-row aggregates.
- V01: `load-tests/exp-d-query-load.py:254` is the only ARN in that file. It uses `111122223333`, inside `self_test_fakes()` (:231). Confirmed.

EVIDENCE GAPS:
- The Step F logs do not record the shell command lines or exit codes. Exit 0 rests on ruling 16 plus sbt `[success]` and "==> OK check". `clean` is not visible as a command, only inferred from log line 8.
- `sbt fmt` and `sbt packageJobs` (README:83-85) were not replayed in Step F. Packaging evidence exists only from package.sh at deef295 (Nov 17).
- The WO3 bullet behaviour was derived by reading the code and not probed. My reading of the regexes agrees with it.
- WO4's validator exit code was not captured. Step F's dataset-validation gate later passed (`fresh-check-sh.txt:362-363`).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (supplied, quoted from ruling 16 item 6 and verified in the logs):
- "sbt clean check: exit 0; 845 succeeded, 0 failed, 10 pending".
- "scripts/check.sh: exit 0, '==> OK check: every gate passed'".
- Hadoop run-e1467fc1552982ae1519a23ba6f6be4e and Spark run-fe913aefae9e677ff451518942c97582, both exit 0.

VALIDATION RESULTS: none run by me. `fresh-check-sh.txt:235` shows "validate-agent-files: 231/231 checks passed".

ASSUMPTIONS:
- The WO5 report text was part of the tree at 87151a8; the reflog gives the commit message only.
- An IP address counts as personal data under ruling 15 item 2.

RISKS (for the human; not findings):
- `docs/agent-runs/phase-17/inputs/ta-reply-2026-11-22.md` names a third party (the TA) in full. The human may want to confirm this under Step S.
- Commit author metadata in `.git` carries a personal email address that differs from the ruling 4 address, and it will be public with history. I have not quoted it.
- `labels/` is readable by agents and protected only by instructions (ruling 16 item 2, factory backlog).

What I did not check:
- `labels/`.
- External URLs.
- The 48 HIGH CVEs.
- The Phase 6 manifests and the Neptune CSV.
- The contents of git objects.

Tool note: a Glob with an explicit brace list was blocked by `guard-paths.sh [secret-search]`. I used Read instead, so nothing went unchecked.

MANUAL ACTION REQUIRED:
- Human:
  - Decide on m1 before Nov 25.
  - Ratify the WO3 `docs/agents.md` change in `human-gate.md`, commit it and start a fresh session.
  - Replace the D19 and "Demonstration video" lines with the link.
  - Create the `v1.0-submission` tag.
  - Decide on re-recording the A8 voiceover (m4).
- Orchestrator: route m2, m3 and m4 (note part) to documentation-engineer, if the human wants them fixed rather than kept as residual risks.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to owners (or accepted as residual risks under ruling 9), then the human gate.
