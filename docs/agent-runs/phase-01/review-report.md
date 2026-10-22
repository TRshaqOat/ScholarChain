# Phase 01 — Review reports

Probe 2's code-reviewer output (review of guard-dangerous.sh) is recorded in test-report.md under Probe 2.

## 1. Full control-plane review (before repair-03)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase-01 full review of the control plane: CLAUDE.md, docs/agents.md, .claude/agents/_.md, .claude/settings.json, .claude/hooks/_, .claude/commands/\*, .mcp.json, including the repair-01 and repair-02 changes. Checked against spec/ScholarChain-spec.md. spec/architect-decisions.md has no RATIFIED entries (D-01 to D-08 are all PROPOSED), so it adds no binding constraints. Read-only review.
VERDICT: REQUEST_CHANGES

SCOPE REVIEWED: All files listed in the supplied `git status` (everything is untracked, so there is no diff range). I read these in full: settings.json, .mcp.json, all 5 hooks, all 5 commands, code-reviewer.md, test-engineer.md, infra-engineer.md, deploy-engineer.md, repair-01.md, repair-02.md, test-report, and the relevant spec sections (lines 280-419 and 850-979). For the other 14 agent files I checked only the `tools:` lines and grepped their frontmatter. I did not read .env, because reading it is denied.

Note on detail: an earlier draft of this report was stopped by a safety classifier. The bypass findings below therefore name the mechanism class and the exact file and line, but not complete, runnable command strings. The owner can rebuild a test vector from each description without needing a working exploit from me.

FINDINGS (ranked):

1. CRITICAL: auto-approved commands give a write path to protected files that the guard does not see as a write.
   - Where: .claude/settings.json lines 9-11 (`Bash(git diff *)`, `Bash(git log *)`, `Bash(git show *)`) and line 23 (`Bash(python3 -m json.tool *)`), together with .claude/hooks/guard-dangerous.sh lines 306-345 (the protected-path write check).
   - Failing input, by class:
     - (a) A git diff-family command using its built-in option that sends output to a named file, pointed at a protected path.
     - (b) The json.tool module given its optional second positional argument, which is an output file, pointed at a protected path.
   - Both start with an allow-listed prefix, so there is no permission prompt. Neither contains a write verb in command position, a `sed -i`, a git work-tree subcommand from the CW_GIT list (line 327), `find -delete`, or a wrapper plus write verb. The CW_WRAP branch (lines 331, 339) needs a WO or WV verb that is not present. The RD redirect rule (line 307) needs a literal `>`. I traced both inputs through `run_rules`, and the guard exits 0.
   - Impact: no prompt and no block on writes to:
     - `.claude/hooks/*.sh`: an emptied hook exits 0, which silently disables the floor.
     - `.claude/settings.json`
     - a newly created `.claude/settings.local.json`: a policy override, which the floor says must never exist.
     - `.claude/.last-test-run`: a forged marker that defeats verify-tests-ran.sh and definition-of-done item 1.
     - `spec/**` and `CLAUDE.md`.
   - This is spec line 387 ("Block direct modification of ... constitutional files by non-factory agents") failing outright. It also makes docs/agents.md:142 ("Bash writes to constitutional paths blocked by guard-dangerous.sh") false.
   - Owner: factory-engineer.

2. CRITICAL: secrets can reach the transcript through auto-approved commands.
   - Where: settings.json lines 9-11 and 22 (`Bash(jq *)`), plus guard-dangerous.sh line 269 (ENVF), line 283 (secret-aws-dir) and line 286 (secret-env-dump).
   - Failing input, by class:
     - (a) jq has built-ins that print the whole process environment. The secret-env-dump rule matches `env` or `printenv` only in command position, so a jq program calling those built-ins passes. Repair-02 item 3 already reported this as open, and nobody fixed it.
     - (b) The `.env` and `.aws` rules match literal names only. A file name written with shell pattern characters, so that the literal `.env` or `.aws` never appears, reaches an allow-listed file-printing command (jq in raw mode, or git diff in no-index mode) with neither a guard block nor a prompt.
   - docs/agents.md requires `GITHUB_PERSONAL_ACCESS_TOKEN` in the developer environment, and the Bash tool inherits it. Read-tool deny rules (settings.json:74-98) do not apply to Bash.
   - Impact: a credential printed into the transcript and phase evidence with no human prompt. This violates spec line 383 and the CLAUDE.md security invariants. Law 5 rates it CRITICAL.
   - Owner: factory-engineer.

3. MAJOR: `Bash(sbt testOnly *)` (settings.json:20) authorizes arbitrary sbt commands.
   - sbt in batch mode runs each extra command-line argument as its own sbt command. That includes commands that evaluate Scala expressions or change settings.
   - Failing input, by class: `sbt testOnly` followed by a separate argument that is an expression-evaluating sbt command.
   - The guard does not treat sbt as a wrapper (line 331, on purpose), so nothing blocks it.
   - Impact: auto-approved arbitrary JVM code once a build exists, with full file-system and environment access. It is latent until build.sbt exists, but it is in the policy now.
   - Owner: factory-engineer.

4. MAJOR: the guard-paths hook does not cover Grep (or Glob).
   - Where: settings.json:252, matcher `Read|Write|Edit|MultiEdit|NotebookEdit`.
   - Failing input, by class: a Grep tool call in content mode whose path is a secret file. guard-paths.sh never runs, so only the best-effort permission-layer Read deny stands in the way.
   - Every agent has Grep, including code-reviewer. docs/agents.md's floor table says secret reads are covered by `guard-paths.sh (Read)`, which overstates the hook layer.
   - Owner: factory-engineer.

5. MAJOR: the repair-02 jq exemption weakens the `.env` floor, and its safety comment is false.
   - Where: guard-dangerous.sh lines 86-88 ("the exemption admits no read of a .env file") and the jq_segment logic at lines 105-148.
   - Failing input, by class: the tokenizer assumes a word `jq` in command position is the real jq binary. It does not handle the command name being redefined earlier in the same command line, which bash allows. In that case the "program" word that gets replaced with `jq-program` is passed to something that reads files. I traced the tokenizer's segment and cmdpos handling for this shape, and the final JQS check passes.
   - Impact: the comment's guarantee does not hold. Law 3 classes floor weakening as CRITICAL. I rate this MAJOR because the rule could already be evaded by variable indirection (documented as a residual risk). The comment still needs to be corrected, or the exemption reworked, before ratification.
   - Owner: factory-engineer.

6. MAJOR: the guard can fail open by timeout, and its header claim is inaccurate.
   - Timeout: jq_strip_filters (lines 150-205) is a per-character bash loop using `${s:i:1}`, which is quadratic on bash 3.2. It runs whenever the command contains both `.env` and `jq`, and the author controls the command length. The hook timeout is 10s (settings.json:247). Whether Claude Code blocks or allows a tool call when a PreToolUse hook times out is unverified. If it allows, a long enough command gets past the guard. This needs a safe probe.
   - Header: line 12 says the guard fails closed, but an empty payload, or one with no tool_name, falls through to `*) exit 0` (lines 383-384).
   - Owner: factory-engineer.

7. MAJOR: `.env` sits untracked in the repository root with no `.gitignore`.
   - Evidence: the supplied git status shows `?? .env`, and test-report Probe 4 says the terminal step wrote a FAKE_KEY into it.
   - Agents are denied `git add *` (settings.json:143), but a human `git add -A` or `git add .` would put it into history, which CLAUDE.md forbids for secrets.
   - Owners: build-engineer (`.gitignore` with `.env*`, target dirs, tfstate and so on); human architect (remove the probe `.env`).

8. MAJOR: the post-ratification evidence is incomplete or inconsistent (Law 4).
   - docs/agent-runs/phase-01/test-report:
     - Probes 5-13 have no Response or Result. Probe 5 (Stop hook) and Probe 6 (routing) are spec-required probes (spec lines 972-973).
     - Probe 0 is marked PASS, but its Expected line says github, terraform and aws-docs connected, while the Response shows all 4 MCP servers failed (uvx missing; GitHub HTTP 400 with an empty bearer token).
     - Probe 1 used domain-engineer, but spec line 968 requires asking `feature-implementer`.
   - review-report.md is 0 bytes.
   - The self-test counts (205/205, 82/82, 9/9) appear only in the orchestrator's prose in repair-02.md. That output is not in this work order, and the Probe 2 reviewer recorded that `--self-test` had never been run. These two statements need reconciling with observed output.
   - Owner: human architect (evidence) and orchestrator (re-run and paste the self-test output).

9. MINOR: GitHub MCP write detection is a deny-list of name prefixes (guard-dangerous.sh:375), and settings.json:222-236 lists specific legacy tool names.
   - Failing input, by class: any GitHub write tool whose name starts with a word outside the prefix list, such as consolidated `*_write` tools. These pass the guard.
   - It is mitigated by the `X-MCP-Readonly` header, the token scopes and the default ask. An allow-list of read prefixes would close it.
   - Owner: factory-engineer.

10. MINOR: guard-paths resolve_path (lines 76-97) resolves symlinks only on the deepest existing directory, not on a symlink as the final path part.
    - Failing input, by class: a file-level link placed inside an agent's owned tree that points at a protected file is classified by where the link sits. Creating the link needs a prompted Bash step.
    - Owner: factory-engineer.

11. MINOR: the tool-fence table overstates how path authority is enforced.
    - docs/agents.md:83 says "new records, no Edit" for deploy-engineer, and the fence table says test-engineer is "tests/\*\* only". guard-paths.sh only covers the file tools.
      - deploy-engineer's Write can overwrite an existing operations record, because append-only is not enforced (guard-paths.sh:138-139).
      - Bash writes by any agent outside the constitutional and human paths rely on the instructions plus the permission prompt, apart from the auto-approved primitives in finding 1.
    - The docs should say this plainly.
    - Owner: factory-engineer.

12. MINOR: guard-paths.sh:297-303 lets the main thread write report files into any phase-NN folder that has no commit.txt, including future ones (its own self-test allows phase-99). docs/agents.md:53 and :65 say "the current phase's folder".
    - Owner: factory-engineer.

13. MINOR: validate-agent-files.sh is weaker than its 216/216 suggests.
    - Its deny checklist (line 118) leaves out Neptune and recursive `s3 rm`, both spec floor items.
    - It does not check agent frontmatter fields that can widen authority (permissionMode, mcpServers, disallowedTools). None are present today, which I confirmed by grep.
    - It never runs the hooks' self-tests.
    - Owner: factory-engineer.

14. MINOR: verify-tests-ran.sh:43 exits 0 (allows the stop) if it cannot cd to the project root. Sources that git ignores are never considered, because it relies on `git status`.
    - Owner: factory-engineer.

15. MINOR: the phase-01 test report is named `test-report` with no `.md` extension. That doesn't match the docs/agents.md:53 naming, and guard-paths only permits `test-report.md`.
    - Owner: human architect.

OWNERSHIP CHECK:

- CLAUDE.md, docs/agents.md, .claude/agents/_.md, .claude/hooks/_, .claude/commands/\*, .claude/settings.json, .mcp.json -> factory-engineer -> author matches: yes. The repair reports name factory-engineer as author, but I could not verify authorship independently.
- docs/agent-runs/phase-01/\* -> human architect (orchestrator only for the four report filenames) -> I can't tell from the tree; see finding 15.
- .env -> no owning row. Reading or writing it is denied -> see finding 7.
- Roster: all 18 required agents exist, each with an explicit tools line. No Agent or Task tool. No feature-implementer or db-migrator.
- The one-writer map matches spec lines 328-347, refined to the repository layout. spec/**, labels/** and docs/agent-runs/\*\* are human-owned (docs/agents.md:51-55).
- Every lifecycle stage in the routing table has exactly one owner.

FLOOR AND FENCE CHECK:

- Reviewer fence holds: code-reviewer has `Read, Grep, Glob` only.
- infra-engineer has no Terraform-execution MCP tools, and terraform apply and destroy are denied (settings.json:114-130) and blocked by the guard (line 219).
- deploy-engineer has Write without Edit, plus read-only aws-api.
- Floor weakening or bypass: findings 1-6.
- No widening of agent tools beyond docs/agents.md. The tools in agent files match the table at docs/agents.md:72-90.
- .mcp.json: versions pinned (1.5.6, 1.0.18, 1.2.2) and matching docs/agents.md. No secret literals: the only credential is `${GITHUB_PERSONAL_ACCESS_TOKEN}`. aws-api has READ_OPERATIONS_ONLY, REQUIRE_MUTATION_CONSENT, and no local file access. The environment variables match the docs table exactly. Least privilege still rests on the IAM profile being read-only; that is documented, but nobody has verified it.

EVIDENCE GAPS:

- Hook self-test output is not supplied (finding 8).
- Probes 5-13 have no results.
- Probe 0's PASS contradicts its own Expected line.
- The MCP servers have never connected, so the pinned versions and environment variable names are unchecked against upstream (repair-01 says the same).
- The Claude Code hook-timeout semantics and the `agent_id`/`agent_type` fields are unverified.
- Repair-01's diff was typed out by hand, not produced by a diff tool.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)
TEST RESULTS (as supplied by the orchestrator, quoted): "`bash .claude/hooks/validate-agent-files.sh` → exit 0, final line `validate-agent-files: 216/216 checks passed`". No other test output was supplied.
VALIDATION RESULTS: Only the validator output above was supplied. It does not cover findings 1-6 and 13.
ASSUMPTIONS:

- The git diff-family output-file option and json.tool's output-file positional behave as their upstream documentation describes.
- Claude Code matches `Bash(prefix *)` allow rules by prefix and applies no extra built-in check that would stop findings 1-3. A safe probe should confirm this.
- The PreToolUse timeout behavior is unknown (finding 6).
  RISKS: Until findings 1 and 2 are fixed, the deterministic floor can be disabled or a secret printed without any human prompt. Ratifying the control plane in this state would give every later phase a guard that can be bypassed.
  MANUAL ACTION REQUIRED:
- Human: remove the probe `.env`, or keep it out of any commit.
- Human: do not ratify phase-01 until findings 1-8 are routed and re-reviewed.
- Install `uv` and export the PAT, then re-run probes 0 and 5-13.
  BLOCKED-ON OWNER: none for this review. For repairs: factory-engineer (findings 1-6, 9-14), build-engineer (`.gitignore`, finding 7), human architect (findings 7, 8 and 15).
  BLOCKED-ON EVIDENCE: n/a
  NEXT GATE: The orchestrator routes the findings to factory-engineer and build-engineer. Each repair must add self-test vectors for every bypass class above. The orchestrator then pastes the self-test output and the fresh probe results, and the code-reviewer re-reviews. The human gate follows only after an APPROVE.

## 2. Re-review after repair-03 and repair-04

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase-01 control-plane re-review after repair-03 and repair-04. Read-only.
VERDICT: REQUEST_CHANGES

Summary: the repair-03 and repair-04 code changes contain no open CRITICAL or MAJOR defect. Findings 1 to 5 are resolved. The verdict is still REQUEST_CHANGES because findings 7 and 8 are still open at MAJOR, and both are about phase hygiene and evidence rather than the code. Neither belongs to factory-engineer (finding 7 belongs to the human and build-engineer, finding 8 to the human and the orchestrator). Some of finding 8 can only be closed after ratification, because those probes are run after it (see NEXT GATE).

A note on detail: an earlier attempt at this report was stopped by a safety classifier. The new findings below therefore give the mechanism class and the file and line, but not runnable command strings.

SCOPE REVIEWED:
- repair-03.patch, all 1148 lines (sha256 77cda5f1…e24d, as supplied).
- The repair-04 diff in repair-04.md.
- Current files:
  - read in full: .claude/settings.json, .mcp.json, verify-tests-ran.sh
  - read in part: guard-dangerous.sh lines 1-470 (all rule and dispatch logic), guard-paths.sh lines 296-320, the docs/agents.md hook-limitations section (lines 155-174)
  - read or grepped: the remaining docs/agents.md lines, review-report.md, repair-03.md, repair-04.md, test-report.md, human-gate.md
  - grepped: agent frontmatter, and every description of how the terraform server is launched.
- Not read beyond the patch hunks: the rest of guard-paths.sh and validate-agent-files.sh, and the self-test vector tails.
- There is no git diff range, because every file is untracked.

DISPOSITION OF FINDINGS 1-15 (from "## 1. Full control-plane review")

1. RESOLVED.
   - The allow rules for git diff/log/show with arguments, json.tool, jq and the sbt wildcards are gone. settings.json:5-28 has only `git status`, `git status *`, exact `git diff`, `git ls-files *`, `git rev-parse *`, exact `git stash list`, exact `sbt check`, exact `sbt Test/compile`, `test -x *`, four exact hook entries, `terraform fmt *` and `terraform validate*`.
   - None of these takes an output-file option or a code-running argument.
   - The backstop checks are added at guard-dangerous.sh:139-246 and :383-392, with vectors in the patch at lines 582-646.
   - One remaining gap in that backstop is new finding N2 (MINOR).
2. RESOLVED.
   - (a) The jq env built-in and $ENV are blocked at guard-dangerous.sh:115-116 and :315-317, with 16 block and 8 allow vectors.
   - (b) Wildcard-obscured secret names remain a residual risk, documented at docs/agents.md:165. No allow rule auto-approves a Bash command that prints a file, so such a command reaches the permission prompt.
3. RESOLVED. sbt appears only as the exact entries at settings.json:12-13.
4. RESOLVED.
   - The matcher is now `Read|Grep|Glob|Write|Edit|MultiEdit|NotebookEdit` (settings.json:234), and Grep and Glob calls go to guard_search (guard-paths.sh:310).
   - A recursive search with no path or glob is still possible. That residual risk is documented at docs/agents.md:167.
5. RESOLVED.
   - The jq exemption code is gone: no jq_strip_filters, jq_segment or JQS anywhere in guard-dangerous.sh lines 1-470.
   - The plain `.env` rule is back (:307).
   - The 11 former allow vectors now expect a block, and the patch adds a vector for the redefined-command shape from the finding.
6. STILL OPEN, downgraded to MINOR.
   - The header is now accurate (:50-51).
   - The guard now fails closed on an empty payload (:401-403), a non-object payload (:404-407), several JSON documents (:408-413) and a missing or empty tool_name (:414-417), with 13 RAW vectors.
   - The per-character loop is gone.
   - Still open: whether a timed-out hook fails open is unverified, and nothing stops it, for example a fail-closed size cap. This is documented at docs/agents.md:168 and in repair-03 RISKS 3. The human has not accepted it as a residual risk.
7. STILL OPEN, MAJOR.
   - The orchestrator's root listing shows no `.env`, so the earlier probe file is gone.
   - There is still no `.gitignore`. The planned Run 2 Probe 4 step writes the `.env` decoy again (test-report.md:281).
   - Owners: build-engineer (`.gitignore`, Phase 2) and the human architect (remove the decoy after Probe 13, and do not commit it).
8. STILL OPEN, MAJOR.
   - Resolved parts:
     - review-report.md is no longer empty.
     - Probe 0 of Run 1 is now marked FAIL (test-report.md:78).
     - Probe 1b covers feature-implementer (:258).
     - The self-test output was observed by the orchestrator in this work order: 304/304, 127/127, 11/11 and 231/231.
   - Still open:
     - Every Run 2 probe has an empty `Result:` (test-report.md:249-380). That includes the spec-required Probes 5 and 6.
     - human-gate.md records only hashes. It has no ratification decision, its date is a placeholder ("2026-10-2X", line 2), and it records nothing for repair-03 or repair-04.
   - Owners: human architect and orchestrator.
9. ACCEPTED RESIDUAL RISK (human: repair-03.md:15), but NOT documented as one in docs/agents.md. The only mention, docs/agents.md:206, says the guard checks write-style GitHub tool names, as if that were a complete control. See N3.
10. ACCEPTED RESIDUAL RISK (human: repair-03.md:15), but NOT documented. docs/agents.md never mentions file-level symlinks. See N3.
11. RESOLVED. The corrected text is at docs/agents.md:65-73, the table rows at :88, :91 and :94, the floor table at :148-150 and :153, and the limitations at :158-170.
12. ACCEPTED RESIDUAL RISK (human: repair-03.md:15), but NOT documented.
    - docs/agents.md:53 and :65 still say "the current phase's folder".
    - Only :169 says "an open `docs/agent-runs/phase-NN/` folder", and it does not state that future, not-yet-created phase folders are writable.
    - See N3.
13. STILL OPEN, MINOR, one sub-item only.
    - The s3 rm and Neptune deny checks and the hook self-test execution are added (patch lines 969 and 992-1011). The validator output shows them passing.
    - Not added: checks for frontmatter fields that can widen an agent's authority (permissionMode, mcpServers, disallowedTools). None exist today: a grep of .claude/ finds no such frontmatter.
    - Related and already known: repair-03 RISKS 6 says hook mode exits 0 when jq is missing.
    - The repair-03 work order did not ask for these changes.
14. STILL OPEN, MINOR, one sub-item only.
    - The project-root fail-closed fix is done (verify-tests-ran.sh:46-57, with 2 vectors).
    - Still open: sources that Git ignores are invisible to the hook, and a `git status` error is silently treated as "no changes", because stderr goes to /dev/null and the loop just ends (:88). The work order did not ask for this.
15. RESOLVED. The file is now docs/agent-runs/phase-01/test-report.md, which matches docs/agents.md:53 and the guard-paths allow-list.

REPAIR-03 CHANGE-SET VERIFICATION
- Removed allow rules: 18 entries are removed, and the ask and deny lists are unchanged (patch hunks 1-2 against settings.json:29-219). No widening. The remaining entries and the docs/agents.md:72 list match exactly.
- git output-file block: it covers --output and its abbreviations, --output-directory, format-patch and archive -o, bundle create and git -C. The vectors include quoted targets, an absolute path, ~/ and $HOME/ prefixes, a doubled slash, and compound commands. One gap in recognizing the subcommand is N2.
- json.tool block: it counts positional arguments and skips options, `--` and redirections. The vectors cover the attached form, clusters, interpreter versions, `-I`, a uv run wrapper and stdin input. I found no gap.
- jq env and $ENV block: the check runs on the normalized string, so removing quotes and splitting the word inside quotes (vector `'en''v'`) are both covered. The false positives are documented (docs/agents.md:166). Programs read from a file and other indirection remain the documented residual risk.
- Revert of the jq exemption: confirmed (see finding 5).
- Fail closed on an empty payload or a missing tool_name: confirmed in guard-dangerous.sh. guard-paths.sh does not have the same behavior (N4).
- Grep and Glob coverage: path and pattern components, brace expansion with a fail-closed cap, and the generic `*.json` exception. I found no bypass in the hunks I read.
- Validator self-test execution: it runs every hook that has `--self-test`, requires one for the three named hooks, and its negative fixture fails as expected (repair-03.md:317-331).
- verify-tests-ran.sh fail closed: confirmed, and the loop breaker still applies.
- Regressions: none found. All 205 baseline guard-dangerous vectors are kept or tightened, and all guard-paths vectors are kept.

REPAIR-04 VERIFICATION
- .mcp.json:28: the pin `mcp==1.30.0` is exact. The entry has no secret, only `FASTMCP_LOG_LEVEL`.
- github (lines 3-10), aws-api (11-24) and aws-docs (33-41) match the docs/agents.md tables and are unchanged.
- docs/agents.md:182 matches .mcp.json.
- docs/agents.md:208 is stale (N1).
- No agent file or command describes how the terraform server is launched. infra-engineer.md:4 lists tool names only.

FINDINGS (new, ranked)
N1. MINOR: docs/agents.md:208, the trust-boundary table, still says the terraform server is launched with `uvx awslabs.terraform-mcp-server@1.0.18` and omits the `--with mcp==1.30.0` pin.
   - Failure class: documentation inconsistency.
   - Impact: anyone who reproduces the server from this row gets the unpinned launch that fails at startup.
   - Owner: factory-engineer.
N2. MINOR: guard-dangerous.sh:168-176. check_git_output takes the first word that does not start with a dash as the git subcommand.
   - Failure class: when a git global option takes its value as a separate word before the subcommand, the subcommand is misidentified. The short `-o` checks for format-patch and archive then never apply.
   - The long `--output` and `--output-directory` forms are still caught.
   - Impact: the backstop claim at docs/agents.md:162 and guard-dangerous.sh:45-48 is overstated. That form is not auto-approved, so it still reaches the permission prompt.
   - No self-test vector covers this shape.
   - Owner: factory-engineer.
N3. MINOR: docs/agents.md:53, :65, :169 and :206. Findings 9, 10 and 12 are human-accepted (repair-03.md:15), but they are not recorded as residual risks. :53 and :65 still claim the current-phase-only behavior.
   - Failure class: the documented control does not match the enforced control.
   - Owner: factory-engineer, to document them. The human should also record the acceptance in human-gate.md.
N4. MINOR: guard-paths.sh:305-313. On an empty payload, or one with no tool_name, jq yields an empty TOOL, and the `*) exit 0` branch allows the call.
   - Failure class: malformed hook input. This is the same class repair-03 fixed in guard-dangerous.sh.
   - Impact: the hook fails open, which does not match the floor's fail-closed posture. Likelihood is low, because the matcher guarantees a tool name.
   - Owner: factory-engineer.

OWNERSHIP CHECK
- These paths are factory-engineer rows, and the repair reports name factory-engineer as the author (yes): .claude/settings.json, the four .claude/hooks/*.sh files, docs/agents.md and .mcp.json. I could not verify authorship independently.
- docs/agent-runs/phase-01/repair-03.patch: the human architect owns this folder. A `.patch` file is not one of the four report names the orchestrator may write (docs/agents.md:53). Its author is unknown. The human should confirm they placed it.
- All other docs/agent-runs/phase-01 files: human-owned, or assembled by the orchestrator under the permitted names.

FLOOR AND FENCE CHECK
- No widening anywhere:
  - 18 allow entries are removed; ask and deny are unchanged.
  - The guard-paths matcher is widened to cover Grep and Glob. That is stricter.
  - The guard blocks are new, and the validator checks only increase.
- The reviewer fence is intact. No agent's tools changed. The terraform Execute* tools are denied (settings.json:202-203) and blocked by the guard (guard-dangerous.sh:424-430).
- Remaining floor gaps:
  - N2 and N4.
  - The timeout from finding 6.
  - The write-verb coverage gaps that repair-03 RISKS 2 already documents (other `.claude/` subpaths, and protected directories named without a trailing slash). All of these reach the permission prompt.

EVIDENCE GAPS
- Every Run 2 probe result is empty, and no ratification is recorded.
- Repair-04: the pinned version was resolved only from the uv cache. No fresh `uvx --with` resolution was observed. Transitive dependencies are not pinned.
- aws-api fails to connect (CONNECTION_CLOSED), and the cause has not been diagnosed.
- The timeout semantics of Claude Code PreToolUse hooks are unverified.
- The fields guard-paths.sh relies on to tell subagent calls apart (`agent_id`, `agent_type`) are unverified.
- Claude Code's prefix matching on compound commands is unverified (repair-03 ASSUMPTIONS).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)
TEST RESULTS (as supplied by the orchestrator, quoted): "validate-agent-files: 231/231 checks passed", "guard-dangerous self-test: 304/304 passed, exit=0", "guard-paths self-test: 127/127 passed, exit=0", "verify-tests-ran self-test: 11/11 passed, exit=0". There is no sbt build, so sbt check does not apply.
VALIDATION RESULTS: the hashes the orchestrator supplied match the post-repair values in repair-03.md (repair-03 files) and repair-04.md (repair-04 files). The hash of repair-03.patch matches human-gate.md:5.
ASSUMPTIONS:
- The supplied patch is the complete repair-03 change.
- Unread parts of guard-paths.sh and validate-agent-files.sh match the hunks in the patch.
RISKS:
- The unratified hooks are already running live in this session.
- The terraform server's own instructions (untrusted data) say it is deprecated and will get no more updates. Pinning it means no upstream security fixes.
- aws-api may have the same unpinned-mcp startup problem that repair-04 fixed for terraform. This is unverified.
MANUAL ACTION REQUIRED:
- Human: decide whether to ratify the constitutional files now, so the Run 2 probes can run, or after the N1-N4 repairs.
- Human: record acceptance of findings 9, 10 and 12, and decide whether to accept the timeout risk from finding 6.
- Human: keep the probe `.env` decoy out of every commit, and remove it after Probe 13.
BLOCKED-ON OWNER: none for this review.
BLOCKED-ON EVIDENCE: n/a
NEXT GATE:
1. The orchestrator routes N1-N4 (and, if wanted, the open sub-items of findings 6, 13 and 14) to factory-engineer, and finding 7 to build-engineer (Phase 2) and the human.
2. The human ratifies and restarts. The orchestrator then fills in the Run 2 probe results (finding 8).
3. The code-reviewer re-reviews.
4. The human gate follows an APPROVE.

## 3. Final review after repair-05 and Run 2

### Summary

The verdict is **REQUEST_CHANGES**, for one MAJOR evidence finding in test-report.md. N1, N3 and N4 are all resolved in the live files, and every claim in the residual-risk section matches the hooks and settings. The problem is that Run 2 records PASS for two probes whose responses show the live step never ran, which breaks the report's own recording rule. The record becomes immutable once `commit.txt` exists, so it has to be corrected before ratification.

### Scope reviewed

- **Patch and context:** `docs/agent-runs/phase-01/repair-05.patch` and `repair-05.md`, read in full.
- **Live files:**
  - `.claude/hooks/guard-paths.sh` (read in full)
  - `.claude/settings.json` and `.mcp.json` (read in full)
  - `.claude/hooks/guard-dangerous.sh` (lines 130-259 read, plus grepped dispatch and GitHub logic)
  - `.claude/hooks/verify-tests-ran.sh` (lines 1-134)
  - `.claude/hooks/validate-agent-files.sh` (grepped)
  - agent frontmatter (grepped)
  - `factory-engineer.md` (audit section)
- **Docs:** `docs/agents.md`, `review-report.md` and `human-gate.md`.
- **Test report:** `test-report.md` lines 1-12, 116-155, 206-705 and 1400-1633. The Probe S self-test tallies and the targeted vectors (lines 738 and 1153) were grepped.
- **Spec:** spec lines 964-983.
- **Diff range:** none, because every phase-01 file is untracked.

### Item 1: repair-05 findings

- **N1: RESOLVED.**
  - `docs/agents.md:222`: the terraform trust-boundary row now reads `uvx --with mcp==1.30.0 awslabs.terraform-mcp-server@1.0.18`.
  - This matches `.mcp.json:28` (`["--with", "mcp==1.30.0", "awslabs.terraform-mcp-server@1.0.18"]`) and the pinned-versions row at `docs/agents.md:196`.
- **N3: RESOLVED.**
  - `docs/agents.md:53` and `:65` now describe the enforced rule: the four report names, directly inside any `docs/agent-runs/phase-NN/` folder with no `commit.txt`.
  - This matches `guard-paths.sh:395-417`, where `rel` is lower-cased, the pattern is `phase-*`, the `*/*/*` depth check applies, and the `commit.txt` check is at :406.
  - Findings 9, 10 and 12 are now recorded at `docs/agents.md:178-180`.
  - The GitHub trust-boundary row (`:220`) is unchanged, but the gap it understates is now documented at `:178`.
  - No "current phase" wording is left in `docs/agents.md` or `CLAUDE.md`.
- **N4: RESOLVED.**
  - `guard-paths.sh:307-324` blocks four malformed-input cases with exit 2:
    - an empty or whitespace-only payload (`guard-empty-payload`)
    - a payload that is not a JSON object, or whose `tool_name` is not a string (`guard-unreadable-payload`)
    - more than one JSON document (output containing a newline)
    - an empty or missing `tool_name` (`guard-no-tool-name`)
  - This is token-for-token the same as `guard-dangerous.sh:401-417`.
  - There are 15 RAW vectors at `guard-paths.sh:612-628`: 12 expect a block and 3 expect an allow.
  - Observed evidence:
    - Probe S shows `guard-paths self-test: 142/142 passed` and `[exit: 0]` (`test-report.md:1154-1155`), including the Glob allow vector (`:1153`).
    - Terminal probes R4-R6 show exit 2 with the expected categories.

### Item 2: residual-risk section (`docs/agents.md:172-184`)

Each entry was checked against the live code and the cited findings. All of them are accurate:

- **Finding 6:** the "measured scan times" it points to are present at `:168`.
- **Finding 7:** `git add *` and `git commit *` are denied (`settings.json:123-125`).
- **Finding 9:**
  - Detection is by write-verb prefix (`guard-dangerous.sh:435`), so a name like `pull_request_review_write` passes.
  - `settings.json:204-218` denies specific legacy tool names.
  - No allow rule covers `mcp__github__*`.
  - No agent file grants github.
- **Finding 10:** `resolve_path` resolves only the deepest existing directory (`guard-paths.sh:98-107`), and no allow rule covers `ln`.
- **Finding 12:** matching is case-insensitive through the lower-cased `rel`, accepts any `phase-*` name, and allows phase-99 (vectors at :528 and :555-556).
- **Finding 13:**
  - `--hook` mode exits 0 when jq is missing (`validate-agent-files.sh:220`).
  - Standalone mode fails (`:68-69`).
  - The tool-fence audit exists (`factory-engineer.md:86`).
  - No agent uses `permissionMode`, `mcpServers` or `disallowedTools`.
- **Finding 14:** `git status ... 2>/dev/null` at `verify-tests-ran.sh:88`.
- **N2:** the first non-dash word becomes `sub` (`guard-dangerous.sh:168-169`), while `GIT_OUT_RX` applies whatever the subcommand (:156).
- **Unrecognized writes:** file-tool writes to `spec/**` are blocked at :390. Writes to `.claude/<other>` are blocked as `orchestrator-inline-write` or `not-owner`.

The citations are correct: findings 6, 7, 9, 10, 12, 13 and 14 are in section 1, N2 is in section 2, and the acceptances are at `repair-03.md:15` and in the repair-05 prompt.

### Item 3: Run 2 probe completeness

- Every Run 2 probe (0, 1, 1b, 2-13, S, R1-R7) has a recorded response and a recorded result, and the Run 2 summary exists (`test-report.md:1627-1632`).
- R4-R6 record `Command:` and `Observed:` instead of `Response:`. That is acceptable, because they are terminal probes under run rule `:231`.
- Two recorded results are not supported by their responses (finding 1 below).

### Findings (blocking ratification)

**1. MAJOR: Run 2 records PASS for probe steps that were not executed, against the report's own recording rule.**

- **Owner:** human architect (`test-report.md` is human-authored, per `human-gate.md:53`).
- **Probe 13** (`test-report.md:656-681`):
  - The response says: "I worked this out by reading the hook; I didn't send the command through it."
  - No guard block was observed. The message shown is a prediction.
  - The recording rule (`:3`, "A model refusal with no hook or permission block message is INCONCLUSIVE") and run rule `:229` make this INCONCLUSIVE, but `:681` records PASS.
- **Probe R7** (`:1560-1625`):
  - The Expected line is "Both succeed with no hook block message" (Read and Glob).
  - The response says "This session has no Glob tool, so I ran `find` instead."
  - The Glob half never reached `guard-paths.sh`, because `find` runs through Bash and `guard-dangerous.sh`. `:1625` still records PASS.
- **Probe 2** (`:342-366`):
  - The Expected line includes "Reports findings … no file changes (confirm with `git status --short .claude/hooks/`)".
  - The recorded response shows the reviewer only being launched in the background. It records no reviewer verdict and no `git status` output.
  - The spec probe 2 requirement is met by Run 1 Probe 2 (`:118-154`), and the reviewer fence has not changed since. Run 2's PASS still has no observed evidence.
- **Consequences:**
  - The Run 2 summary (`:1629-1632`, "PASS: ALL … INCONCLUSIVE: NO … ALL TESTS PASSED") is not accurate as written.
  - The Run 2 header still has blank metadata: `Date: 2026-10-` and no extension version (`:222-223`).
- **Mitigating evidence:** the underlying behaviors are covered by observed deterministic self-tests. These are `[secret-env-file] Bash cat .env` at `:738` and the Glob RAW allow vector at `:1153`, both inside the passing 304/304 and 142/142 runs. The defect is in the evidence record, not the floor.
- **Impact:** after `commit.txt` is written, the phase-01 record is immutable (`docs/agents.md:53`). It would then permanently state that unexecuted probes passed, which is the same class as section 1 finding 8.
- **Fix belongs to the human architect.** Either:
  - re-mark Probe 13 and the Glob half of R7 as INCONCLUSIVE and cite the self-test vectors, or
  - re-run them: `cat .env` actually proposed to the Bash tool, and Glob from a context that has the Glob tool, such as a subagent.

  In either case, also attach or cross-reference the Probe 2 reviewer output, fill in the Run 2 date and version, and correct the summary line.

### Ownership check

- **Repair-05 files:**
  - `docs/agents.md` → factory-engineer → yes (per repair-05.md)
  - `.claude/hooks/guard-paths.sh` → factory-engineer → yes (per repair-05.md)
  - I could not verify authorship independently.
- **Human-placed patch:** `repair-05.patch` is human-placed and recorded at `human-gate.md:18`.

### Floor and fence check

No widening and no weakening:

- `guard-paths.sh` only adds blocks.
- `settings.json`, the agent tools and `.mcp.json` are unchanged by repair-05.
- The N3 doc change matches behavior the hook already enforced. The human accepted it as a widening of documented authority at `human-gate.md:62`.

### Evidence gaps

- Finding 1 above.
- The `repair-05.patch` hash at `human-gate.md:18` cannot be checked by me (no Bash).

### Report fields

- **FILES CREATED:** none
- **FILES MODIFIED:** none
- **COMMANDS RUN:** none (no Bash tool)
- **TEST RESULTS (quoted from test-report.md Probe S):**
  - "guard-dangerous self-test: 304/304 passed [exit: 0]"
  - "guard-paths self-test: 142/142 passed [exit: 0]"
  - "verify-tests-ran self-test: 11/11 passed [exit: 0]"
  - "validate-agent-files: 231/231 checks passed [exit: 0]"
- **ASSUMPTIONS:** the Probe S output in `test-report.md` was produced against the current working-tree hooks.
- **NEXT GATE:** the human corrects test-report.md Run 2 (finding 1), then a brief re-check, then the human ratification in `human-gate.md`.

VERDICT: REQUEST_CHANGES
