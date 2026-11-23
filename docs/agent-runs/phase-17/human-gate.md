# Phase 17 human gate

Phase 17 (final documentation and submission). Folder docs/agent-runs/phase-17/. Drafted with Claude in the web chat on Nov 22, 2026; completed, checked and committed by the human.

## Decision

ACCEPTED. No human escalation: the final code review (1) is APPROVE with 0 critical, 0 major and 4 minor findings, and the human fixed the four minor findings directly (ruling 17).

Accepted commit: 4e37eb74a5d9f0c27a4726477e147df88a2ed3a7

This is HEAD before the gate commit: the README video-link commit. Its tree is the tree code review 1 reviewed (change set 2b82e9c..08e351a, now 342b4ed after the m1 rewrite), plus the review records (723e5dd), the ruling 17 manual edits (3953aa2) and the README video line. check.sh passed on it at this gate. commit.txt records the same hash. The gate commit adds only records: this file, commit.txt, evidence/gate/ and the factory-metrics rows.

## What was checked

- check.sh on the committed tree at the accepted commit (clean tree, VS Code quit, no other sbt process): exit 0, "==> OK check: every gate passed"; json, constitution, bash-syntax, shellcheck, dataset-validation, terraform-fmt, terraform-validate and sbt-check all OK; tests 845 passed, 0 failed, 10 pending (evidence/gate/check-sh-gate.txt).
- Step F (ruling 16 item 6): a fresh clone of 87151a8 on Nov 22. sbt clean check exit 0 (845/0/10); check.sh exit 0; the README's bounded Hadoop and Spark sample commands ran as written (Hadoop run-e1467fc1552982ae1519a23ba6f6be4e, Spark run-fe913aefae9e677ff451518942c97582). Evidence: evidence/fresh-clone/.
- Code review 1 (session 6): APPROVE, 0/0/4. It checked every Phase 17 document against the evidence it cites, the README commands against the scripts and the Step F logs, the 18-row agent catalog against the agent files, the reproduction section against the spec's 11 steps, every deliverable link, the README requirements and the required documents, the wording rule, secrets and the account ID, the release manifest, the work order 3 and 4 changes, and the A01 and V01 explanations. Findings m1 to m4 were fixed by the human (ruling 17).
- Step S (ruling 17 item 6): grader wording, credential shapes in the tree and in history, and the account ID. Clean.
- Spec Phase 17 gate: every README requirement and every required document is present (code review 1 items c, d, f); the release manifest is complete (operations/releases/release-manifest-final.md, with ruling 16 item 5); a fresh clone builds, tests and runs the bounded sample (Step F).
- Demonstration video: https://www.youtube.com/watch?v=2SpvHCDikVI, opened and played in a private browser window before this gate.

## Rulings (full text in prompt.md)

1. Scope: the spec's Phase 17 work order, README requirements and deliverables D01 to D20.
2. Final test run: the human's fresh-clone sbt clean check and check.sh (Step F) replace the test-engineer run.
3. Command replay: documentation-engineer runs only the constitution validator and the guard self-test.
4. Identity: Achyut Patel, apate673@uic.edu; repository private until Nov 25, 2026, then public.
5. Demo script: built from the exported Claude Doc source.
6. Release manifest facts: the last deployed release (window 3), from committed records only.
7. Autograder findings: dispositions.
8. Deployed application: not available; runtime destroyed on Nov 20; 43 base resources remain until cleanup.
9. Final review: whole repository, read-only; APPROVE needed; one repair round allowed; minor findings may become residual risks.
10. Wording: no text addresses a grader or an evaluator.
11. Account ID and labels: new text writes `<account-id>` (completed by ruling 15).
12. Sessions: a new Claude Code conversation per session; one commit after each.
13. Demo-script inputs and the version record.
14. K01: the two flagged lines are test fixtures.
15. The TA's answers: credentials removed; signatures and temporary IDs kept; account ID kept in evidence records; labels/ stays public; the endpoint need not run.
16. Outcomes of sessions 1 to 5 and Step F, with the accepted deviations and the D1 and D2 sources.
17. Code review 1 findings m1 to m4 fixed by the human; Step S results.

## Ratification

I ratify the work order 3 change to docs/agents.md (commit d4cd416): six bullets appended to "Residual risks accepted by the human architect", with the four deviations accepted in ruling 16 item 3. It takes effect in the next fresh Claude Code session.

## Deviations

- The final test run is the human's fresh-clone run, not a test-engineer run (ruling 2).
- documentation-engineer opened labels/README.md for one heading line (ruling 16 item 2).
- The work order 3 bullets name the hook and categories that actually fire, not the work order's wording (ruling 16 item 3).
- Release manifest D1 and D2: the sources are recorded in ruling 16 item 5; the manifest stays as written because deploy-engineer records are append-only.
- Step F: the standalone terraform init failed on a network error and was rerun; the sample commands ran once in the working repository by mistake before the clone existed (ruling 16 item 6).
- No repair round and no re-review: the human fixed m1 to m4 by hand (ruling 17).
- The three commits after 87151a8 were rewritten to remove the IPv6 address (m1): 10d05de became 5de8c1c, 08e351a became 342b4ed, and 976d625 became 723e5dd. Hashes cited in code review 1 name the commits before the rewrite.
- The orchestrator removed the hand-back's indentation and a tool-system note from the code review 1 report.
- Grep and Glob were unavailable to subagents in several sessions; work orders named every input path.
- Clip B11 was recorded after Step F, from the README and the committed Step F logs. In the video it sits between B10 and B12, not at clip 17 of docs/demo-script.md section 3; only the order differs.
- commit.txt was written by hand, not with phase_commit; commit.txt and the Accepted commit line name the same hash.

## External assistance

- Claude in a web chat: rulings, work orders, session prompts, Step F and Step S commands, reviews of agent output, and this gate text, all reviewed by the human.
- The demo recording script: a Claude Doc drafted with Claude and reviewed by the human (ruling 13).
- The course autograder was not rerun in Phase 17. The TA's reply of Nov 22 is recorded in inputs/ta-reply-2026-11-22.md (ruling 15).

## Human-only work

- The email to the TA and the record of the reply.
- Step F, Step S, the m1 history rewrite and the m2 to m4 edits.
- The demonstration video: recording, voiceovers, assembly, export and upload. A8 voiceover: kept as recorded; the narration-correction note in docs/demo-script.md applies.
- The README video line and every commit.

## Manual edits (agent-generated files edited by the human)

- README.md: SCHOLARCHAIN_CONTACT_EMAIL wording in two lines (m2); the demonstration video line in two places (the "Final report and video" section and D19).
- docs/runbook.md: the warm-up query string (m3).
- docs/demo-script.md: the A8 narration-correction note (m4).
- The m1 redaction touched only human-owned evidence files.

Edits from earlier phases are disclosed in reports/final-report.md section 19.1 and the phase gates.

## Cost

No cloud operation ran in Phase 17. Month to date before credits was 16.84 USD on Nov 21 (Phase 15 gate), under the 25 USD limit (D-13). The 43 base resources accrue storage until the final cleanup.

## Release manifest

operations/releases/release-manifest-final.md (work order 5), with the D1 and D2 sources in ruling 16 item 5: release commit c5428063732a, image digest sha256:5c751b1e…047ea, plan 978ce305… (89/0/0), runtime run run-c9f26a9ecfe83c96526d50d79d99fd67, smoke PASS 6 of 6, cleanup of Nov 20 (89 destroyed, 43 kept).

## Residual risks

- Agents can read labels/; only the instruction prevents it (ruling 16 item 2; factory-engineer backlog).
- The guard false positives documented by work order 3; the bullets were derived from the hook code, not probed.
- Step F logs do not record the command lines; sbt fmt and sbt packageJobs were not replayed in Step F.
- GitHub may keep the commits from before the m1 rewrite reachable by hash for some time.
- Commit author metadata in the history becomes public with the repository (ruling 17 item 5).
- The known limitations in the README: verifier limits D18 to D20, P14-D8, the open Neptune record count, single runs and one labeller.
- The API key secret is pending deletion until about Nov 26 to 27.
- Factory-metrics cells marked ? from early phases were not recorded at the time and stay unknown.
- The 43 base resources and the data bucket remain until the final cleanup.

## After this gate

- Tag v1.0-submission on the gate commit, make the repository public, check an anonymous clone and the video link, and submit on Teams by Nov 25, 2026, 11 PM CST.
- Final cleanup: human only, after the instructor approves the evidence archive, planned for Dec 15, 2026 (`scripts/cleanup.sh` with an absolute --plan-file).
