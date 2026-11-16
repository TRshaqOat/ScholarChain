# Phase 14 review report

## Code review 1: repair-01 (work order 2, infra-engineer), against ruling 12 (code-reviewer, verbatim)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 14, code review 1 of repair-01 (work order 2, infra-engineer), checked only against ruling 12: P14-D1 to P14-D4, nothing else changed, scripts/cleanup.sh untouched, no secret in output, Bash 3.2 and `set -euo pipefail` behavior.

VERDICT: APPROVE

All four defects are fixed as ruling 12 scopes them. No CRITICAL or MAJOR finding survived verification. The two open findings are MINOR process findings. Neither changes the four scripts.

SCOPE REVIEWED:
- Change set file: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/d2925ebd-d0c7-47c5-a75d-6905867a3626/scratchpad/phase-14-repair-01-changeset.txt. I read every hunk.
- Changed files, read in full in the working tree:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/run-bounded-pipeline.sh
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/deploy.sh
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/package.sh
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/README.md (the changed sections)
- Read in full: /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/lib/common.sh.
- Searched with Grep: scripts/cleanup.sh, scripts/check.sh, .gitignore.
- Other inputs:
  - docs/agent-runs/phase-14/prompt.md (rulings 11 and 12, work order 2, "Code review 1")
  - docs/agent-runs/phase-14/repair-01.md
  - every log under scratch/phase-14-logs/
  - the harness and its output
  - the failure evidence directory operations/pipeline-runs/20261116T063500Z-fraction25/

Per-defect verification:
- **P14-D4: fixed.** run-bounded-pipeline.sh:221 now passes `--applications Name=Spark` only, where the pre-edit copy had `Name=Hadoop Name=Spark`.
  - The dry-run text (line 126), the usage text (line 31) and the README (lines 195-197) match.
  - The dry-run logs 02, 04 and 05 show the new line. The harness shows the CLI received `--applications Name=Spark`.
- **P14-D1: fixed.** deploy.sh:79 resolves the path right after the usage-level `-f` check (line 76) and before everything that reads the file:
  - META (line 84)
  - the sha256 gate (lines 89-91)
  - the dry-run text (line 105)
  - `tf apply ... "$PLAN_FILE"` (line 126), which runs under `terraform -chdir` (common.sh:108)

  `CDPATH=''` stops a caller's CDPATH from printing to stdout and corrupting the result, and `cd --` and `pwd -P` both work in Bash 3.2. Log 03 shows the relative path resolved to the absolute path, with the checksum gate passing and the apply line absolute. Log 04 shows the `..` form, CDPATH=/tmp, a missing file (exit 2) and a wrong sha256 (exit 4).

  cleanup.sh is untouched: the orchestrator observed `git diff --quiet` exit 0, and the sha256 is the same before and after (bd685f82...e38797). The README (lines 308-312) states that cleanup.sh needs the absolute path. Its claims match cleanup.sh: default OUT_DIR is absolute (line 49), DIR is built from OUT_DIR (line 172), the path is printed at line 201, and it is passed unchanged to `tf apply` (line 128).
- **P14-D2: fixed.** package.sh:144-148 queries `--output json`, pipes it through `jq -c`, and falls back with `|| SCAN=null` and then `${SCAN:-null}`. The record uses `--argjson scan` (line 158), so a non-JSON value cannot be written. Under pipefail:
  - A CLI failure fails the pipeline and gives `SCAN=null`.
  - Empty output makes jq print nothing, which gives `null`.
  - `imageDetails` empty gives `null`.
  - No status gives `null`.
  - IN_PROGRESS gives `{status, severityCounts: null}`.
  - COMPLETE gives the counts, or `{}` when there are none.

  The harness ran these cases under the inherited `set -euo pipefail`, and every record jq call succeeded. No reader of `.image.scan` exists in the repository (Grep).
- **P14-D3: fixed.** run-bounded-pipeline.sh:218-248:
  - `CLUSTER_ID="$(... 2>"$CC_ERR")" || CC_RC=$?`: the assignment's status is the outer substitution's status, and `||` suppresses errexit, so the code is captured correctly under `set -e`.
  - The stderr is echoed to the terminal.
  - On failure, run.json is written. It has the same key set as the success writer at lines 303-321 (schemaVersion through manifests), with unknowable values set to null or empty, plus `failure: {step, command, cliExitCode, error}`.
  - The temp file is removed, and `die ... 1` prints `==> FAIL ... (exit 1)` (common.sh:29-32).

  The harness output shows exit 1, the FAIL line, run.json with clusterId null, cliExitCode 254, the trimmed ValidationException text, and no leftover temp file. The success path writes no run.json and continues.
- **Nothing else changed.** The supplied porcelain status shows only the four scripts as modified. common.sh has no diff. No Terraform, Scala, constitutional or other script path appears. operations-before.txt and operations-after.txt are identical, and the failure directory still holds the same five files.
- **No secret in output.** The new output covers the plan path, the scan JSON, the CLI error text and run.json fields. None carries credentials. The logs and harness output I read contain the account ID and paths only, with no key material.

FINDINGS (ranked):

1. MINOR: process, out of scope of ruling 12's code points. Owner: infra-engineer (process); human architect (disposition); factory-engineer (any scratch/ ownership row).
   - **Where:** /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/orig/ (deploy.sh, package.sh, run-bounded-pipeline.sh, README.md).
   - **Defect:** the work order says "Save the logs under scratch/phase-14-logs/". The infra-engineer also wrote byte copies of the four pre-edit files there, with Bash `cp`. scratch/ matches no row in docs/agents.md, and the infra-engineer's rows are infra/**, scripts/** and .github/**.
   - **Impact:** none on the change set. scratch/ is git-ignored (.gitignore:27), so it does not dirty `require_clean_tree` or reach a commit. scripts/check.sh globs only scripts/*.sh and scripts/*/*.sh, so it never picks up these .sh copies. Grep confirms that orig/ holds the HEAD content (`Name=Hadoop Name=Spark`, `--arg scan`).
   - **Why MINOR rather than law 3's MAJOR:** the human's work order named this directory as the output location for this agent's validation, and ruling 3 already allows files in "the ignored scratch/". If the human reads "logs" narrowly, this becomes MAJOR under law 3, and the verdict would flip to REQUEST_CHANGES on process grounds alone, with no change to the four scripts. That is the human's call.
   - **Routing:** the human decides whether orig/ is removed. No agent may delete it, because the path is unowned. A standing scratch/ row would be a factory-engineer proposal for the human to ratify.

2. MINOR: process, out of scope. Owner: infra-engineer.
   - **Defect:** one `git status --porcelain --untracked-files=all | head`, against the work order's "No AWS, Terraform, git or sbt calls". The agent self-reported it.
   - **Impact:** read-only for content. At most, git may refresh the stat cache in .git/index. No tracked file or record changed.
   - **Verdict effect:** none. It is recorded for the phase evidence.

The infra-engineer's validation logs themselves (01-05, operations-before/after, cleanup sha256) were written where the human work order directed. I do not count them as an ownership defect against the author. The unowned scratch/ path is a constitutional gap for the human and the factory-engineer.

OWNERSHIP CHECK:
- scripts/README.md -> infra-engineer (scripts/**) -> author matches: yes
- scripts/deploy.sh -> infra-engineer -> yes
- scripts/package.sh -> infra-engineer -> yes
- scripts/run-bounded-pipeline.sh -> infra-engineer -> yes
- docs/agent-runs/phase-14/repair-01.md -> orchestrator report file (repair-NN.md in phase-14, which has no commit.txt; Glob shows only prompt.md and repair-01.md) -> permitted: yes
- operations/** (untracked) -> records written earlier by scripts, the deploy-engineer and the human; not part of this change; unchanged per operations-before/after -> n/a
- scratch/phase-14-logs/** (ignored) -> no owner row; logs were human-directed; orig/ is finding 1

FLOOR AND FENCE CHECK: None. No change to .claude/**, settings, hooks, CLAUDE.md, docs/agents.md, .mcp.json, build.sbt or `markTestRun`, and no reviewer fence is touched. The agent says the guard-paths.sh block on a scratch/ Write was respected (the harness moved to the session scratchpad). The deterministic floor is not weakened.

EVIDENCE GAPS:
- The D3 harness ran the extracted block under `set +e` (harness lines 55-58), so the `set -e` capture was not tested by running it. I verified it statically: `|| CC_RC=$?` suppresses errexit, and `$?` is the outer substitution's status. The D2 cases did run under the inherited `set -euo pipefail`.
- The report says the validation logs and orig/ copies were "already written ... before the guard told me scratch/ is unowned". By the report's own sequence, that does not hold for 05-recheck-after-final-edit.txt:
  1. The harness Write was blocked.
  2. The harness then ran from the scratchpad, before and after the error-trim fix.
  3. The recheck came after the last script edit.

  So log 05 was most likely written after the guard notice. I cannot see file times, so this is not verified. It does not affect the code.
- Whole-script runs against AWS have not been observed. That is expected under the work order, which allows only `--dry-run`.
- shellcheck was not run because it is not installed. scripts/check.sh skips it the same way.
- The "operations/ unchanged" claim rests on the agent's own `ls` listings (size and minute-resolution times). It is consistent with the supplied git status and my Glob of the failure directory, but not checked byte for byte.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, quoted from repair-01.md): "TEST RESULTS: no sbt tests apply; no Scala changed."

VALIDATION RESULTS (as supplied, and checked against the scratch/phase-14-logs files):
- `/bin/bash -n` under GNU bash 3.2.57: exit 0 for deploy.sh, run-bounded-pipeline.sh, package.sh and lib/common.sh (logs 01 and 05).
- run-bounded-pipeline.sh dry run: exit 0, showing `--applications Name=Spark` (logs 02, 04, 05).
- deploy.sh dry run with the relative plan path: exit 0, with `plan file /Users/.../scratch/plans/20261116T060222Z-d33ce8e8d507/plan.tfplan`, checksum approved, and the apply line absolute (log 03).
- Extra deploy.sh cases: missing file gives exit 2; wrong sha gives exit 4 (log 04).
- package.sh dry run: exit 0 (log 04).
- D2 and D3 harness results as described above (harness-d2-d3-output.txt).
- cleanup.sh sha256 is identical before and after.

ASSUMPTIONS:
- The orchestrator's `git status` and `git diff` (and `git diff --quiet` exit 0 for cleanup.sh and common.sh) reflect the tree under review.
- The jq on the human's machine supports `--rawfile` and regex. The unchanged success-path writer already needs both.
- "Status and severity counts, or null when not yet available" is met by `{status: "IN_PROGRESS", severityCounts: null}`, as the README documents.

RISKS (verified, not findings):
- deploy.sh:79: in `PLAN_FILE="$(cd ... && pwd -P)/$(basename ...)"` the assignment's status is that of the last substitution, so a failed `cd` or `pwd -P` would not stop the script there. This cannot happen once `[ -f ]` at line 76 has passed (the same directory search permission applies). If it did, PLAN_FILE would become `/plan.tfplan`, and the META check would fail closed with exit 3 before any gate or apply.
- run-bounded-pipeline.sh:231-244 has no fallback if the failure-record jq or its redirect fails. The script would then exit with jq's or the redirect's status, without the FAIL line, and the temp file would remain. Every input is validated by the script or produced by it, so I found no realistic trigger.
- There is no trap, so an interrupt between mktemp (line 218) and rm leaves one empty temp file.
- A create-cluster read timeout could still have created a cluster. The README (line 239) and the FAIL message both tell the operator to check list-clusters first.
- Ruling 12's accepted risk still stands: `hadoop jar` and MapReduce on emr-spark-8.0.0 with only Spark selected are unverified until the 25% run's classpath-probe and hadoop-pipeline steps.

MANUAL ACTION REQUIRED:
- The human decides on scratch/phase-14-logs/orig/ (finding 1) and whether scratch/ needs an ownership row.
- Then, per ruling 12: scripts/check.sh, the commit, package.sh at the new HEAD, and the 25% run.
- Until the repair is committed, deploy.sh calls still need the absolute plan path. cleanup.sh always needs it.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: finding 1 goes to the human architect (with the factory-engineer for any ownership row), and finding 2 is recorded against infra-engineer process. Neither needs a code repair or re-review. Then the human gate on this APPROVE: check.sh, commit, package.sh, then the 25% run.
