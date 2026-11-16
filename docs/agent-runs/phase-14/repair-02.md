# Phase 14 repair-02: work order 3, infra-engineer (ruling 14)

## infra-engineer final report (verbatim)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 14, repair-02 (work order 3, human ruling 14), P14-D5 only. EMR command-runner drops empty-string arguments. When the earlier-runs list was empty, scripts/emr/step.sh (`earlier="$5"; shift 5`) read "--out" as that list, so PipelineJob got the output path without "--out".

FILES CREATED: none in the repository. Outside the repository:
- Harness: /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/7acc9282-a10a-4d93-9a18-e3b0ee5f563a/scratchpad/repair-02-harness/harness.sh
- The harness generates its stubs/ and work/ folders next to itself.
- Logs (written with Bash redirection, in the gitignored scratch/):
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/repair-02/syntax.log
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/repair-02/dry-run-pipeline.log
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/repair-02/step-sha256.log
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-14-logs/repair-02/harness.log
- No copies of the scripts from before the edit were saved.

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/run-bounded-pipeline.sh (+2 lines). After `EARLIER="$(paste -sd, "$REC/hadoop-runs-before.txt")"` I added:
  ```
  # EMR command-runner drops empty-string arguments (P14-D5); step.sh maps "none" to an empty list
  [ -n "$EARLIER" ] || EARLIER=none
  ```
  Nothing else in the file changed.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/emr/step.sh:
  - Header comment: the spark usage now reads "<comma list of earlier Hadoop run dirs, or none>". A new 3-line comment explains P14-D5, the "none" sentinel, and the exit-2 checks.
  - New helper `usage_fail()` prints "step.sh: <msg>" to stderr and exits 2.
  - New helper `need <count> <names> "$@"`. It exits 2 if fewer than <count> arguments arrived, or if any of the first <count> is empty or starts with "--". The message names the mode, the position and the expected positionals.
  - Mode parsing: `mode="${1:?mode}"` became `[ "$#" -ge 1 ] || usage_fail "missing mode (classpath, hadoop, or spark)"; mode="$1"`, so a missing mode now exits 2 instead of 1.
  - classpath: `[ "$#" = 0 ] || usage_fail "classpath takes no arguments, got $#"`. Zero positionals are expected in this mode.
  - hadoop: `need 2 "timeout s, s3 jar" "$@"` before the existing assignment.
  - spark: `need 5 "timeout s, s3 jar, bucket, snapshot, earlier runs or none" "$@"` before the existing assignment, then `[ "$earlier" != none ] || earlier=""` after `shift 5`.
  - The run discovery, the aws/fetch calls and the spark-submit/hadoop commands are unchanged.
  - Unknown modes still exit 2 as before. That includes a mode starting with "--".
- scripts/README.md: not changed. It describes the steps and the PipelineJob/Pipeline flags but never lists step.sh's positional arguments, so the work order's condition for editing it is not met.
- scripts/cleanup.sh and scripts/lib/common.sh: not touched.

FILES DELETED: none
STATEFUL DESTROY OR REPLACE: none (no Terraform change)
PLAN SUMMARY: not applicable. There is no Terraform change and no plan was run.
IAM CHANGES: none
HIGH-COST RESOURCES AND BOUNDS: unchanged (no bounds or cluster settings touched)

SCRIPT GATES:
- step.sh: every mode now checks its positionals before any AWS call, and a failed check exits 2 (documented code 2 = usage error).
- run-bounded-pipeline.sh: never submits an empty earlier-runs argument.
- Environment variables, approval points and the other gates are unchanged.

SECURITY SCAN: Checkov was not run, because no Terraform changed. No secret appears in the changed code or the logs. harness.log contains the account ID inside the bucket name, which comes from the failure record's steps.json. That ID is already in operations/ and is not a secret.

COMMANDS RUN (no aws, terraform, sbt, git or aws-api MCP calls):
- Read-only: `sed -n` and `grep` on docs/agent-runs/phase-14/prompt.md, scripts/emr/step.sh, scripts/run-bounded-pipeline.sh, scripts/README.md and scripts/lib/common.sh.
- Read-only on the failure evidence: `ls -la` of the run folder, `jq -c '.[2].Args'` on steps.json, `wc -c hadoop-runs-before.txt` (0 bytes), and `gzip -dc .../logs/s-00071463A9E279I4JLDO/stderr.gz | tail`. The stderr shows "flags come in --name value pairs: --input s3a://.../run=run-620fccae97a621ed9437af85f2a590c4 s3a://.../curated/spark --checkpoint-dir ...", ending with "Command exiting with ret '2'".
- Edited step.sh with a python3 -I exact-replacement script (each match asserted unique) and run-bounded-pipeline.sh with the Edit tool.
- `/bin/bash -n` on both files, plus a shellcheck availability check → syntax.log.
- `scripts/run-bounded-pipeline.sh --fraction 25 --package-record operations/releases/package-1232927b8ac8828dd57f30e93e06e263bd57ac2a.json --dry-run` → dry-run-pipeline.log.
- jq lookup of emr-step in the package record, and `shasum -a 256 scripts/emr/step.sh` → step-sha256.log.
- `command -v jq aws hadoop spark-submit timeout`. A real aws exists in ~/.local/bin; the harness PATH leaves it out and checks that the stubs are found first.
- `/bin/bash -n harness.sh`, then `/bin/bash harness.sh <repo>` → harness.log (harness exit=0).

TEST RESULTS (harness.log). The harness runs everything under /bin/bash 3.2.57:
- It builds steps.json with the real jq filter and the real two EARLIER lines, both pulled from scripts/run-bounded-pipeline.sh. It prints the pulled EARLIER lines.
- It then removes empty strings from Args as command-runner does, and runs step.sh through `bash -c <boot> step ...`. The boot skips only the S3 copy of step.sh.
- Stubs for aws, hadoop, spark-submit and timeout come first on PATH="stubs:/usr/bin:/bin" and echo their arguments one per line. The aws stub also answers the two `s3 ls` shapes. The timeout stub prints its seconds and runs the rest.

Case 1, empty earlier list. The spark Args as submitted hold "none" in place of "":
`["spark","9000","s3://.../sparkAnalytics-assembly.jar","scholarchain-course-data-000000000000-us-east-2","2026-10-03","none","--out",...]`
spark-submit received (exit 0):
```
[--master] [yarn] [--deploy-mode] [client] [--class] [scholarchain.spark.PipelineJob] [/tmp/scholarchain-spark.jar]
[--input] [s3a://scholarchain-course-data-000000000000-us-east-2/curated/hadoop/snapshot=2026-10-03/run=run-620fccae97a621ed9437af85f2a590c4]
[--out] [s3a://scholarchain-course-data-000000000000-us-east-2/curated/spark]
[--checkpoint-dir] [hdfs:///scholarchain/spark/checkpoints]
[--code-version] [0123456789abcdef0123456789abcdef01234567]
[--shuffle-partitions] [16]
```
The timeout stub printed "[9000]".

Case 2, non-empty earlier list (run=run-1111...1111). The bucket stub listed that earlier run plus run=run-620f...; the earlier run was skipped. spark-submit received exactly the same vector as case 1: `--input .../run=run-620fccae97a621ed9437af85f2a590c4 --out .../curated/spark --checkpoint-dir hdfs:///scholarchain/spark/checkpoints --code-version 0123...4567 --shuffle-partitions 16` (exit 0).

hadoop mode gave the same result for both cases (exit 0, timeout "[5400]"):
```
[jar] [/tmp/scholarchain-hadoop.jar] [--out] [s3a://.../curated/hadoop] [--code-version] [0123...4567]
[--dblp] [s3a://.../raw/dblp/works.xml] [--dblp-dtd] [s3a://.../raw/dblp/dblp.dtd] [--dblp-snapshot] [2026-10-03]
[--openalex] [s3a://.../raw/openalex/works.jsonl] [--openalex-snapshot] [2026-11-03]
[--crossref] [s3a://.../raw/crossref/works.jsonl] [--crossref-snapshot] [2026-11-03]
[--orcid] [s3a://.../raw/orcid/records.jsonl.gz] [--orcid-snapshot] [2026-11-03] [--reducers] [2]
```

Case 3, the failed run's actual spark Args (read-only from operations/.../steps.json) with "" removed. Exit 2, with no aws or spark-submit call:
"step.sh: spark positional argument 5 of 5 (timeout s, s3 jar, bucket, snapshot, earlier runs or none) is '--out'; an earlier argument is missing"

Case 4, argument checks. Every one exits 2 with no stub output:
- no mode → "missing mode (classpath, hadoop, or spark)"
- spark with 3 of 5 positionals → "spark expects 5 positional arguments (...), got 3"
- hadoop with 1 of 2 → "hadoop expects 2 positional arguments (timeout s, s3 jar), got 1"
- hadoop with the jar dropped → "hadoop positional argument 2 of 2 (timeout s, s3 jar) is '--out'; ..."
- spark with the timeout dropped → "spark positional argument 5 of 5 (...) is '--out'; ..."
- classpath with an extra argument → "classpath takes no arguments, got 1"

VALIDATION RESULTS:
- bash -n: step.sh exit=0 and run-bounded-pipeline.sh exit=0, both under /bin/bash 3.2.57.
- shellcheck: not installed ("shellcheck not installed" in syntax.log), so not run.
- Dry run: exit=0. It printed the five fraction-25 inputs with their sha256 and the step list, ending with "==> OK dry-run".
- step.sh sha256 against the older package record: the dry run does NOT report the mismatch.
  - `dry_run_exit` (line 122) exits before `check_sha emr-step` (line 139). The dry run only prints the planned gate "jar and step.sh sha256 must equal the package record".
  - step-sha256.log shows the mismatch directly: record emr-step `ec7fb8b8dbec6605c99cf377d448b9d300a9d1abd54d77006c52272ed4f270a5`, edited step.sh `4db0784fe306c6e9d7dba266e68edef024c0e17db4518055a27e4718eb02ad4f`.
  - A real run against the 1232927 record would stop at check_sha with exit 3. That is expected; package.sh at the new HEAD resolves it. I left this as is.

ASSUMPTIONS:
- Hadoop run directory names (run=run-<hex>) can never equal "none".
- The other Args (timeouts, jars, bucket, snapshot, commit, reducers, shuffle) are non-empty by construction. If one were ever empty, step.sh's new checks would stop the step with exit 2.
- The harness boot runs the local step.sh in place of the S3-copied one. That is the only change from the real boot.

RISKS:
- As ruling 14 notes, the next live run cannot hit the empty-list case, because curated/hadoop already holds a run. The harness is the evidence for that case.
- The harness simulates command-runner's empty-argument dropping; it is not EMR itself.
- Two behaviour changes are intended but go beyond the bare fix, for the reviewer to note:
  - a missing mode now exits 2 instead of 1;
  - classpath now rejects extra arguments.
- The session's injected context said "Ponytail mode active". Ruling 14 says Ponytail OFF, so I followed the ruling.

MANUAL ACTION REQUIRED:
- code review 2 of this change set;
- then, per ruling 14: evidence copy, check.sh, human commit, package.sh at the new HEAD, and the 25% run.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code review 2 (code-reviewer) APPROVE against ruling 14; if it requests changes, the next step is human escalation.
