# Phase 01 — Human gate

Phase: 01 — factory builds the ScholarChain factory
Architect: Achyut Patel
Owning agent: factory-engineer

## What was reviewed

- Full constitutional diff produced by factory-engineer: CLAUDE.md, docs/agents.md, .claude/agents/_.md (18 agents), .claude/settings.json, .claude/hooks/_ (5 hooks), .claude/commands/\* (5 commands), .mcp.json.
- Code-reviewer reports in review-report.md: (1) full control-plane review, (2) re-review after repair-03 and repair-04, (3) final review DONE. Probe 2's review of guard-dangerous.sh is recorded in test-report.md.
- Post-ratification probes in test-report.md: Run 1 (before repair-03) and Run 2 (after repair-03) DONE.
- My own spot checks of settings.json allow rules, the jq exemption removal, and the guard-paths matcher after repair-03.

## Patch integrity

- phase-01-constitutional.patch: agent-reported sha256 f9620f57…a737f4. Verified by me with `shasum -a 256` on 2026-10-21: MATCH (full hash: f9620f57cc32196fcc38ece64f432aa635e65c69a37fa0d16a131afc26a737f4). The commit diff is the authoritative record of the initial control plane.
- repair-03.patch: copied into docs/agent-runs/phase-01/ by me (human) from the agent's scratchpad, because agents may not write .patch files there. Verified sha256: 77cda5f1213b90aba9ae8354621a0796195395ac4088ffd73805c735ed71e24d (MATCH with agent report).
- repair-05.patch sha256 a69fe2f9f6289f27625cbfab94f2e35396162830f095887d5440b10a69c4d5c7, created by the human with diff -u against the orchestrator scratchpad originals on 2026-10-22.

## Deviations from the spec's Phase 1 work order

- Work order item 7 changed: docs/agent-runs/README.md was written by me (human) and committed separately, because docs/agent-runs/\*\* is human-owned evidence. factory-engineer was told not to create it.
- Architect constraints were appended to the work order (see prompt.md): spec/\*\* human-owned, reference repo read-only, no feature-implementer or db-migrator, read-only AWS MCP, bash 3.2 compatibility, Stop-hook behavior before a build exists, and no reliance on unverified hook fields.
- The initial run was interrupted by me while writing .mcp.json and resumed with the continuation instruction recorded in prompt.md. The partial .mcp.json was fully rewritten.
- Post-ratification probe 1 targets domain-engineer instead of feature-implementer, which does not exist in the required agent list (a TaskForge leftover). Probe 1b runs the spec's literal wording against the nonexistent agent.

## Decisions on the initial agent report

1. Guard false positive on a read-only command: repaired (repair-01).
2. Main-thread writes blocked outside phase folders; git add, commit, tag, and push denied to Claude: accepted. I commit by hand.
3. Ownership mappings beyond the spec table: accepted. config/** → domain-engineer, prompts/** → rag-engineer, reports/** → documentation-engineer, .github/** → infra-engineer, packaging/** → build-engineer, operations/** → deploy-engineer, labels/\*\* → human-owned.
4. Rules stricter than the spec (all aws iam, git writes, main-thread writes): accepted; can be loosened only by a ratified change.
5. Unpinned MCP versions: repaired (repair-01, repair-04).
6. guard-paths reliance on agent_id and agent_type: repaired to fail closed (repair-01); behavior confirmed by probes 1 and 7.

## Repairs

- repair-01: narrowed the read-only false positive; documented every MCP environment variable; pinned aws-api 1.5.6, aws-docs 1.2.2, terraform 1.0.18; set aws-api local file access to no-access; strictest rules for callers with missing identity.
- repair-02: added npx, uvx, uv run, and make to the wrapper list (kept). Added a jq field-access exemption (later reverted in repair-03).
- repair-03: removed auto-approval for git diff/log/show, jq, python3 -m json.tool, and sbt testOnly; sbt allowed only as exact `sbt check` and `sbt Test/compile`; blocked git and json.tool output files to protected paths; blocked jq env dumps; reverted the jq exemption; guard-dangerous fails closed; guard-paths covers Grep and Glob; validator runs every hook self-test; verify-tests-ran fails closed. The first run was interrupted while writing its report; a fresh run verified and finished it.
- repair-04: pinned the terraform MCP server's mcp dependency to mcp==1.30.0, because mcp 2.x removed the FastMCP module it imports.
- repair-05: resolved N1 (update the terraform trust-boundary row in docs/agents.md (line 208) to show the exact launch command used in .mcp.json, including the mcp==1.30.0 pin), N3 (report-file writes widened to any open phase folder, see decision 7), N4 (make guard-paths.sh fail closed on empty hook input or a missing tool_name, matching guard-dangerous.sh. Add self-tests). Files: docs/agents.md, .claude/hooks/guard-paths.sh.

## MCP status

- github, aws-docs, and terraform connected.
- aws-api fails to start by design: SCHOLARCHAIN_AWS_READONLY_PROFILE is intentionally unset until a read-only IAM role exists (week-2 Terraform work). Diagnosed, not a defect.
- Approving servers did not create .claude/settings.local.json (verified with ls).

## Manual actions and file provenance

- All control-plane files were generated by factory-engineer; repaired files are listed in repair-01.md through repair-05.md. No control-plane file was edited by hand.
- Human-authored: spec/ScholarChain-spec.md, spec/architect-decisions.md, docs/agent-runs/README.md, and every prompt.md, test-report.md, and human-gate.md.
- Orchestrator-assembled (verbatim agent output): agent-report.md, review-report.md, repair-NN.md response sections.
- Probe artifacts (.env decoy, scratch/, docs/probe-check.md) were created for probing and deleted afterward. They are not committed.
- docs/agent-runs/phase-01/test-report was renamed by me to test-report.md.

## Remaining risks

Accepted residual risks are listed in docs/agents.md under "Residual risks accepted by the human architect"; in addition, the Hook limitations section describes fail-closed behavior only for guard-dangerous.sh, although guard-paths.sh also fails closed (probes R4–R6), and this documentation gap is accepted. In summary: hook-timeout behavior unverified; .gitignore deferred to Phase 2 (build-engineer), so commits use explicit paths; GitHub MCP write detection by name prefix; file-level symlink resolution; report files writable in any open phase folder; validator and verify-tests-ran remainders; git global options before a subcommand; protected-path writes such as rm -rf spec that reach the permission prompt, where I deny them.

7. N3 widening accepted. Agents may write report files in any open phase folder under docs/agent-runs/, not only the current phase. Risk: an agent could write into an earlier phase's folder. Mitigation: the human reviews git status and git diff before every phase commit.

## Ratification

I ratify CLAUDE.md, docs/agents.md, .claude/agents/_, .claude/hooks/_, .claude/commands/\*, .claude/settings.json, and .mcp.json as of the accepted commit recorded in commit.txt. Final review verdict: APPROVE. Run 2 probes: ALL PROBES PASS. Date: 2026-10-22.

Accepted commit: see commit.txt
