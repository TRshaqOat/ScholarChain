# Agent Run Evidence

Each phase has a folder `docs/agent-runs/phase-NN/` containing:

- `prompt.md` — the exact work order given to the orchestrator
- `agent-report.md` — the owning agent's final report (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE)
- `review-report.md` — code-reviewer verdict, when required
- `test-report.md` — test-engineer results or probe results, when required
- `repair-NN.md` — each repair loop: findings, owner, fix, rerun evidence
- `human-gate.md` — what was reviewed, accepted or rejected, manual changes, file provenance, remaining risk, accepted commit
- `commit.txt` — the accepted commit SHA (phases 00 to 14 recorded it in a follow-up commit; from Phase 15 on it is written by hand in the gate commit and names the last reviewed and checked commit)

This directory is human-owned. The orchestrator may assemble agent reports here; no agent may rewrite a human decision.

Hidden model reasoning is never stored. Only work orders, final reports, deterministic command output, review findings, and human decisions are recorded.

Commit messages use the form `phase-NN owner: description`.
