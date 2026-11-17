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

## Code review 2: repair-02 (work order 3, infra-engineer), against ruling 14 (code-reviewer, verbatim)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 14, code review 2 (after work order 3, repair-02, human ruling 14). I reviewed the repair-02 change set against ruling 14 only: P14-D5 fixed for an empty and a non-empty earlier list, the argument checks in step.sh, nothing else changed, scripts/cleanup.sh untouched, bash 3.2 compatible.

VERDICT: APPROVE

SCOPE REVIEWED:
- Diff: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/7acc9282-a10a-4d93-9a18-e3b0ee5f563a/scratchpad/repair-02-changeset.diff. It is identical to the diff pasted in the work order: step.sh +27/-2, run-bounded-pipeline.sh +2.
- Full current files:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/emr/step.sh
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/run-bounded-pipeline.sh
  - the relevant parts of scripts/lib/common.sh (`dry_run_exit` at line 57, `tfo` at line 128, `SC_SNAPSHOT`)
  - scripts/README.md, the run-bounded-pipeline.sh section at lines 190-218
- Failure evidence: operations/pipeline-runs/20261116T071214Z-fraction25/steps.json, run.json and hadoop-runs-after.txt, plus operations/releases/package-1232927b8ac8828dd57f30e93e06e263bd57ac2a.json.
- Harness: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/7acc9282-a10a-4d93-9a18-e3b0ee5f563a/scratchpad/repair-02-harness/harness.sh
- Logs in /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/repair-02/: syntax.log, dry-run-pipeline.log, step-sha256.log, harness.log.
- Infra-engineer report: docs/agent-runs/phase-14/repair-02.md.

VERIFIED AGAINST RULING 14 (no defects found):

1. Empty earlier list.
   - run-bounded-pipeline.sh:192-194: `EARLIER="$(paste -sd, ...)"` is followed by `[ -n "$EARLIER" ] || EARLIER=none`. `$earlier` (from `--arg earlier "$EARLIER"`, line 201) is the only value placed in a positional slot of the spark Args (line 211), so that slot can no longer be "".
   - step.sh:67 maps "none" back to "" after `shift 5`.
   - step.sh:71: `case ",$earlier," in *",$d,"*)` with an empty `earlier` tests ",," and matches no `run=...` directory, so every listed run counts as new. This is the same behavior as before the bug.
   - harness.log lines 54-71: spark-submit received `--input s3a://.../run=run-620f... --out s3a://.../curated/spark --checkpoint-dir hdfs:///... --code-version <sha> --shuffle-partitions 16`, in order, exit 0.

2. Non-empty earlier list.
   - The value passes through unchanged. harness.log lines 145-209: the earlier run `run=run-1111...` is skipped and the new run is chosen.
   - spark-submit received the same vector as the empty case, in order, exit 0.

3. The harness is faithful to the shipped code.
   - harness.sh:49-50 extracts the jq Args filter from run-bounded-pipeline.sh at run time. I checked that the awk start pattern matches line 204, the end pattern matches line 213, and the sed strips exactly the leading `'` and the trailing `' >"$REC/steps.json"`.
   - harness.sh:51 greps the two shipped EARLIER lines (192 and 194) and evals them (lines 63-65). It aborts if both are not found (line 54), and harness.log lines 3-4 print them.
   - It runs the repository's scripts/emr/step.sh directly (`$STEP`, line 13), not a copy, so it cannot drift from the shipped code.
   - Empty-string removal is `select(. != "")` over the Args (line 77). That removes exactly the "" entries and keeps the order.
   - The only deviation from production is the boot (line 60), which skips the S3 copy of step.sh and uses /bin/bash.
   - Stubs only: harness.sh:41-45 puts the stubs first on PATH="stubs:/usr/bin:/bin" and asserts that aws, hadoop, spark-submit and timeout resolve to the stubs, and harness.log line 1 confirms it.
   - Case 3 replays the real failed vector from steps.json, in which I verified the "" at line 69 before "--out". step.sh exits 2 with "positional argument 5 of 5 ... is '--out'" before any stub call (harness.log lines 282-298).

4. Argument checks in every mode (step.sh:19-37, 45, 57, 64).
   - Missing mode exits 2.
   - classpath expects 0 arguments.
   - hadoop checks its 2 positionals. The Pipeline flags that follow are not checked, which is correct because they are flags, not positionals. If the jar is dropped, "--out" lands in position 2 and is caught. If the timeout is dropped, "--out" is still in position 2 and is caught.
   - spark checks its 5 positionals. Any drop among the 5 moves "--out" into position 5 or earlier, or leaves fewer than 5 arguments, and both are caught.
   - An unknown mode, including one starting with "--", still exits 2 (lines 82-84).
   - Every check runs before `fetch` or `aws_t` in step.sh.
   - Each check is exercised in harness.log lines 300-324.

5. The "none" sentinel cannot collide with a real run name.
   - Hadoop run directories are named `run=run-<hex>` (hadoop-runs-after.txt line 1; list_runs prints the PRE names).
   - The only way to collide is a hand-made directory named exactly "none" as the only earlier entry. step.sh would then treat it as new, find n=2, and exit 1 at step.sh:74. That fails closed and never selects the wrong input.

6. Behavior under `set -u`.
   - `$mode` is assigned at line 36, before any `need` call. `local ... a` and `for a in "$@"` are safe.
   - An empty `"$@"` under set -u in bash 3.2 is exercised and works: harness case 4 "no mode" runs the boot `exec /bin/bash $STEP "$@"` with set -u and zero arguments and reaches usage_fail with exit 2 (harness.log lines 301-304).
   - Inside `need`, the count check runs before the loop, so the loop never iterates an empty list when `want` is 2 or more.

7. bash 3.2 compatibility.
   - The change uses only `local`, `$(( ))`, `case`, `[ ]` and `for ... in "$@"`. There are no arrays, `${var,,}`, `mapfile` or `[[ =~ ]]` forms.
   - syntax.log: `/bin/bash -n` exit 0 on both files under GNU bash 3.2.57. The harness ran every case under /bin/bash 3.2.57.

8. The two flagged behavior changes fit the work order.
   - The work order says "In every mode, step.sh also checks that it received its expected positional arguments ... otherwise it exits 2 with a clear message."
   - Missing mode now exits 2 instead of 1. The mode is the first expected positional, and exit 2 is the documented usage-error code.
   - classpath now rejects extra arguments. Its expected positional count is 0.
   - Neither affects the submitted steps: classpath Args are exactly `step classpath` (run-bounded-pipeline.sh:205), and that step is ActionOnFailure CONTINUE.

9. Nothing else changed.
   - The diff touches only the step.sh header comment, the two helpers, mode parsing, the three mode checks and the "none" mapping, plus the two lines in run-bounded-pipeline.sh.
   - Run discovery, aws_t/fetch, the hadoop jar command and the spark-submit command are unchanged.
   - scripts/README.md does not list step.sh positionals (lines 201-213 describe the steps and the job flags only), so leaving it unchanged is correct under work order 3.
   - The `git diff` of scripts/cleanup.sh, scripts/lib/common.sh and scripts/README.md is empty, as supplied by the orchestrator. I have no Bash, so I cannot run git myself.

10. Other steps carrying empty arguments.
   - The hadoop Args values are non-empty by construction: commit is checked by is_sha40 (line 117), reducers by in_range (line 81), the snapshots are literals or config, the input keys are built from non-empty parts, and BUCKET comes from the Terraform output.
   - The classpath Args have no variables.
   - If a hadoop positional were ever dropped, the new `need 2` catches it. A dropped Pipeline flag value is outside P14-D5 and outside "positional".

FINDINGS:

1. MINOR. File: docs/agent-runs/phase-14/repair-02.md:60, the claim "(harness exit=0)".
   - Failure input: harness.log has no line recording the harness's own exit status. It ends at the last case-4 line, "step.sh exit=2".
   - Evidence: harness.sh runs under `set -euo pipefail`, every case is logged through to the last statement, and the last statement is an `echo`. This strongly implies exit 0, but the claimed exit code itself was not captured.
   - Impact: none on correctness. Under law 4 it is a claim without directly observed output.
   - Owning agent: infra-engineer. No re-work is needed for this verdict. Future harness logs should record the exit code.

2. MINOR (coverage gap, not a defect). File: the harness script, cases 1-4.
   - Not exercised: classpath with zero arguments (the normal path), hadoop with zero positionals, and a spark earlier list with two or more comma-separated runs.
   - Evidence: I read these paths in the code and they are correct. `[ "$#" = 0 ]` holds for the submitted classpath Args. `need 2` with no arguments fails the count check at step.sh:25 before the loop. The comma-list matching at step.sh:71 did not change in this diff.
   - Impact: low.
   - Owning agent: infra-engineer.

No CRITICAL or MAJOR findings.

OWNERSHIP CHECK:
- scripts/emr/step.sh: owner infra-engineer; author infra-engineer. Matches: yes.
- scripts/run-bounded-pipeline.sh: owner infra-engineer; author infra-engineer. Matches: yes.
- docs/agent-runs/phase-14/repair-02.md: a report file the orchestrator assembles, in an open phase folder. phase-14 has no commit.txt; I checked the folder listing (prompt.md, repair-01.md, repair-02.md, review-report.md). Matches: yes.
- Harness and logs: the session scratchpad and the gitignored scratch/. These are not repository artifacts.
- Out of scope: the untracked operations/ tree (script records).

FLOOR AND FENCE CHECK: No hook, permission rule, `check`, `markTestRun` or reviewer-fence file is touched, so nothing is widened or weakened. No Terraform, IAM or bound changed. step.sh now fails faster and fails closed.

EVIDENCE GAPS:
- Finding 1: the harness exit=0 is implied by the log but not captured.
- The stderr.gz excerpt in repair-02.md (PipelineJob "flags come in --name value pairs" with "--out" missing) is quoted by the infra-engineer. I cannot decompress it with Read. It is consistent with what I verified: steps.json line 69 holds "", and run.json shows spark-pipeline FAILED after about 6 seconds. Ruling 14 also records the same cause.
- The step.sh sha256 mismatch against the 1232927 record was not reported by the dry run, because of a premise error in the work order rather than a defect in the change.
  - Work order 3 called the mismatch acceptable "if the dry run reports it".
  - The dry run cannot report it. `dry_run_exit` at run-bounded-pipeline.sh:122 exits (common.sh:57-64) before `check_sha emr-step` at line 139, and this is behavior from before the change.
  - dry-run-pipeline.log only prints the planned gate "jar and step.sh sha256 must equal the package record".
  - The infra-engineer disclosed this and recorded the mismatch separately in step-sha256.log: record ec7fb8b8...0a5, edited file 4db0784f...ad4f. I checked the record value against the package record at line 23.
  - Making the dry run hash-check would have broken "change nothing else". A real run against the 1232927 record would stop at check_sha with exit 3, and ruling 14's sequence (package.sh at the new HEAD) produces a matching record.
  - The human may want to note that the work order's expectation does not match the script's documented dry-run behavior. This does not block.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.

TEST RESULTS (as supplied, quoted from the logs):
- harness.log:
  - Case 1 (empty list): spark-submit `[--input] [s3a://.../run=run-620fccae97a621ed9437af85f2a590c4] [--out] [s3a://.../curated/spark] [--checkpoint-dir] [hdfs:///scholarchain/spark/checkpoints] [--code-version] [0123...4567] [--shuffle-partitions] [16]`, "step.sh exit=0". The hadoop step gave "step.sh exit=0".
  - Case 2 (non-empty list): the same spark-submit vector, "step.sh exit=0". The hadoop step gave "step.sh exit=0".
  - Case 3: "step.sh: spark positional argument 5 of 5 (...) is '--out'; an earlier argument is missing", "step.sh exit=2".
  - Case 4: six checks, each "step.sh exit=2".

VALIDATION RESULTS:
- syntax.log: `/bin/bash -n` exit=0 on both files. "GNU bash, version 3.2.57(1)-release". "shellcheck not installed".
- dry-run-pipeline.log: "==> OK dry-run", "exit=0".
- step-sha256.log: the record's emr-step sha256 is ec7fb8b8dbec6605c99cf377d448b9d300a9d1abd54d77006c52272ed4f270a5, and the edited step.sh is 4db0784fe306c6e9d7dba266e68edef024c0e17db4518055a27e4718eb02ad4f. The two differ, as expected.

ASSUMPTIONS:
- The orchestrator-supplied git status and the empty diffs of cleanup.sh, common.sh and README.md are accurate.
- The bash on the EMR node (invoked as `bash` by the boot) is at least as capable as 3.2.
- EMR command-runner drops only empty strings and does not otherwise alter the arguments. The failure evidence supports this, but only a live run proves it.

RISKS:
- As ruling 14 says, the next live run cannot exercise the empty-list path, because curated/hadoop already holds a run. The harness is the only evidence for that path.
- A spark-step exit 2 still happens after the roughly 17-minute Hadoop step, because the check runs on the node. That is inherent to the design, not to this change.
- step.sh defines a helper named `need` that has different semantics from common.sh's `need` (command presence). step.sh does not source common.sh, so there is no runtime conflict, but the shared name could confuse readers.

MANUAL ACTION REQUIRED: per ruling 14, the human does the evidence copy, runs check.sh, commits, runs package.sh at the new HEAD, then the 25% run.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human gate on APPROVE (ruling 14 sequence). The two MINOR findings go to infra-engineer as notes only and need no repair round.

## Code review 3

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 14, code review 3 (repair-03, work order 4; rulings 18 and 19)
VERDICT: APPROVE

The change does what work order 4 asks. I found no major or critical findings, only three minor ones.

SCOPE REVIEWED:
- `git diff` of scripts/emr/step.sh, scripts/run-bounded-pipeline.sh and scripts/README.md, as supplied in /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/20b5480c-4391-4627-b47c-b3b347ca6457/scratchpad/repair-03-changeset.txt
- Full working-tree reads of /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/emr/step.sh and /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/run-bounded-pipeline.sh
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/README.md lines 189-229
- /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/agent-runs/phase-14/prompt.md lines 130-192
- /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/agent-runs/phase-14/repair-03.md
- The infra-engineer's harness, stubs and harness.log in the session scratchpad (h/)
- modules/spark grep for the shuffle default

CHECKS REQUESTED
1. **The --conf placement is correct.** step.sh:87-90 keeps a single `exec timeout "$timeout_s" spark-submit ...` command. `--conf spark.sql.join.hybridHashJoin.enabled=false` sits between `--deploy-mode client` and `--class`, which is before the primary resource as spark-submit requires. The `--input "s3a://..."` quoting and the trailing `"$@"` are unchanged, and so are the positional checks (`need 5`). harness.log lines 54-73 and 193-212 show this exact argv order for both the empty and the non-empty earlier list.
2. **The classpath scan cannot fail the step or run unbounded.**
   - The block runs after `set +e` and still ends in `exit 0` (step.sh:46, 61). The only variable read in the outer shell is `found`, which is always assigned, so `set -u` cannot fire. `$j` is expanded in the inner `bash -c`, which does not inherit `-u`.
   - GNU `timeout`, run without `--foreground`, puts itself in its own process group and signals that whole group when the limit expires. That stops `unzip` and `grep` as well. `sort -u` runs outside `timeout`, gets EOF and finishes.
   - `unzip -p` streams to a pipe and writes nothing to disk. If the glob matches no jar, the loop gets the literal pattern, `unzip` fails silently, and the output is `none found`.
   - The harness exercised the `classpath` path under bash 3.2 and got exit 0 (harness.log:329-343).
3. **Nothing else changed behaviour.** Only the three permitted files changed. scripts/cleanup.sh and lib/common.sh are untouched. The `in_range --shuffle-partitions "$SHUFFLE" 2 200` line (run-bounded-pipeline.sh:82) is unchanged. The new default of 40 matches modules/spark/JOBS.md:11, 430 and modules/spark/src/main/resources/scholarchain-spark.conf:11 (`shuffle-partitions = 40`).
   - Expected side effect: step.sh's sha256 changes, so a new package record is needed. This is already in ruling 19's mechanics and in the engineer's MANUAL ACTION.
4. **The README matches the code**, apart from minor finding 2.
5. **bash 3.2 compatibility holds.** The only change to run-bounded-pipeline.sh is a usage line and `SHUFFLE=40 # ...`. A trailing comment after an assignment is valid in bash 3.2. step.sh adds only `command -v`, `$(...)`, `${var:-word}` and `bash -c`, all valid in 3.2. The harness runs step.sh under /bin/bash 3.2.

FINDINGS (ranked; no CRITICAL or MAJOR)

1. **MINOR: a timed-out or broken scan reads the same as "key absent".**
   - Where: /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/emr/step.sh:56-57
   - Failure input: the scan reaches 120 s, so `timeout` exits 124. Or `timeout` or `bash` is missing on the node, or the key lives in a jar that does not match `spark-*.jar` (for example an EMR-specific jar with another name).
   - What happens: the output is a partial list or `none found`, with no sign that the scan was incomplete. `found` takes only the output of `sort -u`, so the 124 exit is lost.
   - Impact: ruling 19 requires the results ruling to quote "whether the hybridHashJoin key was found". A truncated or mis-scoped scan could be read as proof the key does not exist. Only the diagnostic is affected; the step and the run are not.
   - Owner: infra-engineer. Optional: print the scan's exit status or a "scan incomplete" note. Ruling 19 allows no repair-04, so this goes to Phase 17 notes or is handled by reading the output with care.
2. **MINOR: two descriptions are more precise in the code than in the text.**
   - README (/Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/README.md:216-217) says the classpath step "prints the `spark.sql.join.hybridHashJoin*` settings found in the cluster's Spark jars". The code scans only `/usr/lib/spark/jars/spark-*.jar`, prints any matching string (which is not necessarily a registered setting), and can print `none found` or `unzip not available`.
   - The new text describes the classpath step under item 3 (spark-pipeline). Item 1 (classpath-probe, README:202-203) and step.sh's own header (step.sh:5-7) do not mention the new jar scan.
   - Failure input: an operator reads item 1 or the step.sh header to learn what the probe prints.
   - Impact: documentation drift only.
   - Owner: infra-engineer.
3. **MINOR (Law 4): an untested claim is written as fact.**
   - Where: step.sh:85-86 and README:213-216.
   - Both state that "off gives the sort-merge join Apache Spark plans locally". Nobody has seen this on EMR. Ruling 19 cites a third-party source (NVIDIA/cudf-spark PR 16253), and the engineer's ASSUMPTIONS say the key was not checked against AWS documentation or the cluster jars.
   - The wording follows work order 4 item 1, which the human wrote.
   - Failure input: EMR uses a different key name. Spark accepts unknown `spark.sql.*` keys passed through `--conf` without error, so the run would fail again at TemporalSummaryExportJob.scala:442 while the docs say the problem is fixed.
   - Impact: a misleading record until the 50% rerun confirms the fix.
   - Owner: infra-engineer, routed by the human. Either the results ruling confirms the statement from the 50% rerun and the classpath-probe output, or the README wording is corrected later.

OWNERSHIP CHECK:
- scripts/emr/step.sh -> infra-engineer -> author infra-engineer: yes
- scripts/run-bounded-pipeline.sh -> infra-engineer -> author infra-engineer: yes
- scripts/README.md -> infra-engineer (`scripts/**`) -> author infra-engineer: yes
- docs/agent-runs/phase-14/repair-03.md (untracked) -> written by the orchestrator. Allowed, because `repair-NN.md` may be written into phase-14, which has no commit.txt, and ruling 19 explicitly permits it.
- operations/** (untracked) -> deploy-engineer and human ruling records; not part of this change set and uncommitted by design.

FLOOR AND FENCE CHECK:
- No hooks, permission rules, `check`, `markTestRun`, agent files or reviewer fences were touched. No Terraform or IAM change. No widening.
- Script gates are unchanged: the positional checks still exit 2 before any AWS call, and the classpath probe still never fails the step.

EVIDENCE GAPS:
- **Jar-scan unit check.** The claim that it printed the two strings once each, and `none found` for a missing directory, has no captured log. Only the test jar (scratchpad/jars/spark-sql_test.jar) exists. It also ran with `timeout` removed (macOS has none), so the `timeout 120` wrapper with real GNU timeout was never run.
- **Harness.** "Harness exit was 0" is not recorded in harness.log. This is the same residual as review 2. The per-case exit codes are in the log and match the report.
- **Harness classpath case.** The stub `timeout` printed `timeout seconds: [120]` to stdout, so the captured `found` was that stub line rather than `none found`. This is disclosed as a stub artifact, and it means the classpath case shows only exit 0 and the header line.
- **Shuffle value in the harness.** It hardcodes `--arg shuffle 40` rather than reading the script default, so it does not prove the default. The diff of line 56 is the evidence for the default.
- **bash -n.** "ok" for both scripts is self-reported. The orchestrator did not replay it and supplied no output.
- **Process deviation.** The infra-engineer ran read-only `git status --porcelain` and `git diff --stat` against work order 4's "no git" rule. It disclosed this, and nothing changed. Noted for the human; not a code defect.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool)

TEST RESULTS (as supplied, from repair-03.md):
"Case 1, empty earlier list, spark mode: exit=0 ... Case 2 ... exit=0 ... Case 3 ... exit=2 (positional 5 is '--out') ... Case 4 ... exit=2 [x6] ... Added case, classpath with no arguments: exit=0". harness.log in the scratchpad agrees line for line.

VALIDATION RESULTS: "bash -n passed on both scripts. shellcheck was not available." (self-reported; not replayed by the orchestrator)

ASSUMPTIONS:
- The EMR primary node's `timeout` is GNU coreutils, which signals its whole process group when the limit expires. AL2023 ships it, and spark and hadoop mode already rely on `timeout`.
- JOBS.md and scholarchain-spark.conf are the authority for the PipelineJob default of 40.

RISKS:
- If `spark.sql.join.hybridHashJoin.enabled` is not EMR's key, Spark ignores it silently and the 50% run fails again, costing about 24 normalized instance hours. Ruling 19 then requires human escalation. The classpath-probe output (finding 1 caveat) is the first signal.
- A new package record is needed before any run, because check_sha compares step.sh with the record.

MANUAL ACTION REQUIRED:
- Human acceptance of this verdict, then commit, `package.sh` at the new HEAD, and the chain run per ruling 19.
- When quoting the classpath-probe output in the results ruling, read `none found` with finding 1 in mind.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Minor findings 1-3 go to the infra-engineer as Phase 17 notes (no repair-04 under ruling 19). Then the human gate on this APPROVE.
