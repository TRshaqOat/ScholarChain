# Phase 12 repair-02

## Part A: infra-engineer

````text
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12, session 5, repair-02 Part A (ruling 22): R1, N1, N2 and the F9 remainder. Edits are in infra/** and scripts/** only.

Summary: all four items are done. scripts/check.sh on the final tree exited 0, with every gate passing. sbt ran 839 passed, 0 failed, 10 pending, and markTestRun wrote the marker. Two limits:
- The R1 grant cannot be shown by any local check. It is first tested on the first window 1 cluster.
- One guard block happened (a false positive). It is described under COMMANDS RUN.

STATEFUL DESTROY OR REPLACE: none. Only the content of a policy document changed. No managed resource was added or removed.

FINDINGS CLOSED:
1. R1. File: infra/terraform/modules/emr/main.tf.
   - The new local is at :60: `parent_keys = ["curated", "logs/emr"]`.
   - The explanation comment is at :54-59. It says:
     - why the keys are needed: an S3A mkdirs or getFileStatus walk HEADs each parent key. `curated/*` and `logs/emr/*` do not match those bare keys, so on an empty prefix the HEAD returns 403 instead of 404;
     - that nothing writes these keys, so they hold no data;
     - why `logs` is not granted (see ASSUMPTIONS 1).
   - The new statement is at :174-178:
       sid       = "ProbeWriteRootParents"
       actions   = ["s3:GetObject"]
       resources = [for k in local.parent_keys : "${local.b}/${k}"]
     This produces exact object ARNs `<bucket-arn>/curated` and `<bucket-arn>/logs/emr`, with no wildcard. No ListBucket was added and nothing else was widened.
   - Final policy statements, from grep (Grep is not available in this session, so I used Bash grep; log: scratch/phase-12-logs/r02a-r1-grep.log):
       48:  read_prefixes = ["raw/", "curated/", "artifacts/"]
       51:  write_prefixes = ["curated/", "logs/emr/"]
       53:  list_prefixes = distinct(concat(local.read_prefixes, local.write_prefixes))
       60:  parent_keys = ["curated", "logs/emr"]
       151:    sid       = "BucketLevel"
       152:    actions   = ["s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
       153:    resources = [local.b]
       157:    sid       = "ListOwnPrefixes"
       158:    actions   = ["s3:ListBucket"]
       159:    resources = [local.b]
       164:      values   = [for p in local.list_prefixes : "${p}*"]
       169:    sid       = "ReadInputs"
       170:    actions   = ["s3:GetObject"]
       171:    resources = [for p in local.read_prefixes : "${local.b}/${p}*"]
       175:    sid       = "ProbeWriteRootParents"
       176:    actions   = ["s3:GetObject"]
       177:    resources = [for k in local.parent_keys : "${local.b}/${k}"]
       181:    sid = "WriteOutputs"
       188:    resources = [for p in local.write_prefixes : "${local.b}/${p}*"]
     (The condition at :161-165 is StringLike on s3:prefix and is unchanged.)
   - IAM summary: infra/terraform/README.md:229 adds "and on the exact keys `curated` and `logs/emr` (the parent keys an S3A mkdirs or getFileStatus walk HEADs; they hold no data; repair-02 R1)".
2. N1. infra/terraform/README.md:211 now reads `command    -main scholarchain.storage.cli.LoadNeptuneCli --live --stage-from-bucket`. This matches scripts/load-stores.sh:171 (`run_loader_task start --live --stage-from-bucket --dir ... --out /tmp/stores`). The `--status` line (:214) is unchanged, matching load-stores.sh.
3. N2. python3 is now a documented and checked prerequisite:
   - scripts/check.sh:24-25: "Needs: jq, bash, python3 (dataset validation), terraform 1.16.6, sbt, a JDK. ..."
   - scripts/check.sh:35: `need jq python3 terraform sbt`
   - scripts/README.md:85-86: a new "Prerequisites" bullet in the check.sh section. It says the script stops with exit 2 when jq, python3, terraform or sbt is missing.
   - Disable check (scratch/phase-12-logs/r02a-disable-check-n2.log). The test PATH was a scratchpad bin directory linking every PATH tool except python3*, plus a no-op sbt shim, so sbt did not really run.
     - Fixed check.sh (sha256 bf733fd9…): `==> FAIL missing tool: python3 (exit 2)`, before any gate ran.
     - Pre-fix copy in place (sha256 f7266c73…): passed `need`, ran every gate, failed late at `dataset-validation` ("required tool not found: python3"), exit 1.
     - Restored sha256 bf733fd9…, which matches.
4. F9 remainder. scripts/run-experiment.sh:
   - :100 now writes `>"$DIR/stdout.txt" 2>"$DIR/stderr.txt"`;
   - :118 runs `tail -20 "$DIR/stdout.txt"`;
   - :126 run-record has `stdout: "stdout.txt", stderr: "stderr.txt"`;
   - :31-34 help text: "stdout.txt, stderr.txt (.txt, not .log, because .gitignore ignores *.log)".
   scripts/README.md matches:
   - :269 Evidence: `stdout.txt`, `stderr.txt`;
   - :50-52 convention: "Logs the scripts write under `operations/` and `experiments/results/` end in `.txt`".
   Grep for `stdout.log|stderr.log` over scripts/ and infra/ finds nothing (exit 1). No local check can show this rename: the only path that writes the files needs AWS and a committed manifest under experiments/ (not owned by me).

FILES CREATED (logs only):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-12-logs/r02a-terraform.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-12-logs/r02a-r1-grep.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-12-logs/r02a-disable-check-n2.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-12-logs/r02a-bash-n-help.log
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/phase-12-logs/r02a-check.log

FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/infra/terraform/modules/emr/main.tf
- /Users/achyutpatel/Developer/Cloud/ScholarChain/infra/terraform/README.md
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/check.sh
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/run-experiment.sh
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scripts/README.md

FILES DELETED: none.

PLAN SUMMARY: no plan was run (ruling 5). Counts are unchanged, because only the content of the `instance_s3` policy document data source changed:
- window 1 from empty state: 43 add, 0 change, 0 destroy;
- window 2 from window 1 state: 91 add (89 without an alarm email);
- window 2 from empty state: 134.
The human's plan should show one new statement, `ProbeWriteRootParents`, inside `aws_iam_role_policy.instance_s3`.

IAM CHANGES: on the EMR instance role only, s3:GetObject on two exact object keys (`curated` and `logs/emr`) in the data bucket. No trust policy, no ListBucket and no other role changed.

HIGH-COST RESOURCES AND BOUNDS: unchanged.

SCRIPT GATES:
- check.sh now fails fast (exit 2) when python3 is missing, instead of failing late at dataset-validation.
- run-experiment.sh gates are unchanged; only its output file names changed.

SECURITY SCAN: Checkov was not rerun (optional under ruling 5).

COMMANDS RUN:
- cp of the pre-fix files to the scratchpad; shasum.
- sed -i on check.sh and run-experiment.sh; Edit on the rest.
- terraform version, fmt -check -recursive infra/terraform, `terraform -chdir=infra/terraform/envs/course init -backend=false -input=false`, validate.
- bash -n on the changed scripts; run-experiment.sh --help and check.sh --help.
- The N2 disable check script (in the scratchpad), which ran check.sh twice under the restricted PATH with the sbt shim.
- `SPARK_LOCAL_IP=127.0.0.1 bash scripts/check.sh` once on the final tree (background). Two Bash wait loops, also backgrounded, polled its log.
- grep, pgrep, df.
- No git, aws, plan or state command. No cleanup.sh. No command through a wrapper.
- Blocked once by guard-dangerous [protected-path-write]. It was a false positive: one read-only summary command also contained `ls -l .claude/.last-test-run` and an `rm -f` of my scratchpad copy of check.sh. I did not retry the rm or the marker listing. I ran only the read-only summary, without them. The scratchpad file r02a-fixed-check.sh is left in the scratchpad.

TEST RESULTS (scripts/check.sh on the final tree; log r02a-check.log):
- exit 0, "==> OK check: every gate passed".
- Gates OK: json, constitution, bash-syntax, shellcheck (SKIPPED, not installed), dataset-validation (15 [PASS] markers, 0 [FAIL] markers, "[DONE] validate: all checks passed"), terraform-fmt, terraform-validate, sbt-check.
- sbt: 191+111+116+115+49+141+46+70 = 839 succeeded, 0 failed, 10 pending. "markTestRun: tests passed, wrote .../.claude/.last-test-run".

VALIDATION RESULTS:
- terraform fmt -check: exit 0.
- init: exit 0, using the locked aws v6.28.0.
- validate: "Success! The configuration is valid.", exit 0.
- bash -n: ok for scripts/check.sh and scripts/run-experiment.sh; check.sh's bash-syntax gate also passed for every script.
- shellcheck: not installed.
- run-experiment.sh --help: exit 0, shows the stdout.txt and stderr.txt text.
- check.sh --help: exit 0.

ASSUMPTIONS:
1. Parent keys. The walks start at the write roots curated/hadoop and curated/spark (whose parent is `curated`) and at logs/emr.
   - `curated` is the key that matters: its LIST probe on `curated/` is already allowed by the `curated/*` condition, so the 403 came only from the HEAD.
   - `logs/emr` is granted as the work order directs.
   - `logs` is not granted:
     - EMR's log pusher PUTs objects without an S3A mkdirs, so no S3A walk reaches it;
     - if one did, its LIST probe on `logs/` would still be denied by the prefix condition, and a ListBucket widening there is outside ruling 22;
     - so a GetObject on `logs` alone could not make such a walk succeed.
2. Probe order (a HEAD on the bare key, then a LIST on `key/`) follows Apache S3A 3.3 and 3.4. Whether emr-spark-8.0.0's s3a:// uses that connector is still unverified (as in R1).

RISKS:
- R1 is mitigated, not proven. The first window 1 Hadoop step's logs show whether any other probe is denied.
- If anything does mkdirs under logs/emr through S3A on an empty bucket, the LIST on `logs/` would still return 403 (see ASSUMPTIONS 1).

MANUAL ACTION REQUIRED: the human plan review per ruling 21 (expected 43/0/0), including the new `ProbeWriteRootParents` statement in the EMR instance policy.
BLOCKED-ON OWNER: none.
BLOCKED-ON EVIDENCE: none.
Housekeeping: no sbt process (pgrep exit 1); both background shells completed; 10 GiB free; the disable-check bin directory was removed by its script.
NEXT GATE: code review 3 of the repair-02 changes, then the human commit and plan (ruling 21). Apply only through the approved path.

Not done: no plan and no live check of the grant (ruling 5); `logs` was deliberately left without a grant.
````
