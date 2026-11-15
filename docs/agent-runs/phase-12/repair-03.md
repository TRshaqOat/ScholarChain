# Phase 12 repair-03

## Part A: infra-engineer

````text
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12 session 6, repair-03 Part A (human exception, ruling 23): fix N4 on the EMR instance role, correct the README, and check every other role for the same S3 404 dependency.

FILES CREATED: none (logs only, under scratch/phase-12-logs/: r03a-tf-validate.txt, r03a-check.txt)
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/infra/terraform/modules/emr/main.tf
- /Users/achyutpatel/Developer/Cloud/ScholarChain/infra/terraform/README.md
FILES DELETED: none

STATEFUL DESTROY OR REPLACE: none. Only the content of a policy changes (the inline policy aws_iam_role_policy.instance_s3, in place). No resource is added, removed or replaced.

PLAN SUMMARY: Not run (rulings 5 and 8; the human runs scripts/plan.sh).
- Expected counts are unchanged: window 1 is 43/0/0; window 2 from the window 1 state is 91 to add (89 without an alarm email); 134 from an empty state.
- I removed no resource and added none. The only removal is the now-unused local `list_prefixes`.
- These counts are inferred from the code, not observed.
- On a later plan against the applied window 1 state, the change shows as 1 in-place update of module.emr.aws_iam_role_policy.instance_s3.

IAM CHANGES:

1. Final EMR instance role policy (data "aws_iam_policy_document" "instance_s3", emr/main.tf:147-186). The Grep tool was not available, so these were read back with the Read tool:
   - :148-152 BucketLevel: s3:GetBucketLocation and s3:ListBucketMultipartUploads on the bucket ARN. Unchanged.
   - :154-157: new comment. It explains that a GetObject or HeadObject request carries no s3:prefix, so a prefix-conditioned ListBucket never applies to it. It also says the grant exposes key names only, never data, citing iam/main.tf:181-183 and ruling 11.
   - :158-162 ListDataBucket: s3:ListBucket on local.b (the data bucket ARN), with no condition. This replaces ListOwnPrefixes, which had the s3:prefix StringLike condition.
   - :164-168 ReadInputs: s3:GetObject on raw/*, curated/*, artifacts/*. Unchanged.
   - :170-174 ProbeWriteRootParents: s3:GetObject on the exact keys in local.parent_keys. Kept.
   - :176-185 WriteOutputs: Put, Delete, AbortMultipartUpload, ListMultipartUploadParts on curated/* and logs/emr/*. Unchanged.

2. Locals (emr/main.tf:52-58):
   - :52-57: the comment is rewritten. It states the ruling 23 S3 rule and cites iam/main.tf:181-183: 404 only to a principal with GetObject on the key and ListBucket on the bucket, otherwise 403, which S3A raises as AccessDeniedException. It also names the S3A parent walk and the existence checks.
   - :58: `parent_keys = ["curated", "logs", "logs/emr"]`. "logs" is added.
   - The old comment and the `list_prefixes` local are removed. The comment had the wrong claim "only turns that 403 into 404" and the "logs is not granted" rationale.

3. README (infra/terraform/README.md:229): the emr-instance row now says:
   - ListBucket is unconditioned, with the ruling 23 reason;
   - it exposes key names only, never data, as for query-api;
   - GetObject applies to the exact keys curated, logs and logs/emr (repair-02 R1, repair-03).
   No other line in infra/terraform/README.md or scripts/README.md repeats the "403 into 404" claim. README:234 (query-api) was already correct. The claim also appears in docs/agent-runs/phase-12/repair-02.md, which is human-owned and not edited.

4. Role table (every role in the stack). The README per-module resource tables (README:59-80) list IAM roles only under emr (2), build (1), neptune (1) and iam (6). The IAM summary (README:226-237) lists the same 10.

| Role | Missing-key path | Current ListBucket | Decision |
|---|---|---|---|
| emr-instance | S3A HEAD of parent keys "curated", "logs/emr", "logs"; output existence checks; first write under curated/; getFileStatus HEAD of input directory keys (raw/..., the curated/hadoop run as Spark --input) | was conditioned on s3:prefix, so it never applied to a GET or HEAD | CHANGED to unconditioned (ruling 23; same rationale as query-api, key names only). Exact-key parent grant kept, plus "logs" |
| emr-service | none: AmazonEMRServicePolicy_v2 plus iam:PassRole; reads no data-bucket object (EMR log pushing uses the instance role) | none | no change: no S3 object read |
| codebuild-services-image | artifacts/codebuild/source/<sha>.zip. package.sh:110 uploads it before start-build at :122 | none | no change: impossible in the normal path, and a missing source fails the build either way (403 or 404) |
| neptune-bulk-loader (assumed by Neptune) | none: the loader lists the source prefix and GETs only listed keys | s3:prefix graph/*, which applies to its ListObjects of graph/neptune/... | no change |
| ecs-exec-services, ecs-exec-loader | none (no S3 grant) | none | no change |
| task-query-api, task-worker | readiness probe key, unknown query IDs (SERVICES.md:72-77) | unconditioned (iam/main.tf:184-188) | no change: already correct |
| task-graph-evidence-api | only payloadRef keys named by an EvidenceBundle item | none | no change: impossible in the normal path (see a) |
| task-neptune-loader | store-conversion manifest and manifest entries (LoadNeptuneCli.scala:214-235); HEAD only on keys it has listed (:289) | conditioned on graph/neptune/*, curated/coauthor-evidence/*, runs/store-conversion/*. This applies to its ListObjectsV2 but not to its GETs | no change: impossible in the normal path, harmless otherwise (see b) |

   Why nothing changed for the last two roles:
   - (a) graph-evidence-api.
     - Audit.persist writes each payload object (Audit.scala:109-118, putOnce) before the EvidenceBundle item that names it (Audit.scala:147-150), and S3 reads are strongly consistent after a write.
     - Expired items are not returned (live evidence-by-ID reads unexpired items only; ServiceStateSpec "live evidence by ID: ... unexpired only").
     - The only remaining gap is the 30-day logs/ lifecycle compared with the item TTL. That cannot happen inside the roughly 48-hour runtime window, whose tables are destroyed at its end (D-13).
     - Even with a 404, a missing payload answers 502 (RuntimeApi.evidenceOf:89 gives Left; Services.scala:341-347 maps it to 502). A 403 would only also count against the s3 breaker.
     - SERVICES.md:85 agrees.
   - (b) neptune-loader.
     - load-stores.sh uploads the store-conversion manifest set with LoadS3Cli first (load-stores.sh:135) and stops on any loader failure (:130) before the loader task runs (:171). So in the normal path every key it GETs exists.
     - On misuse, a 404 gives "no run manifest" or "missing from the bucket", exit 1.
     - A 403 is an S3Exception that is not transient (LiveAws.scala:159-162), so it is not retried (ObjectStoreSpec "retry covers only transient failures"). Cli.exitCode prints "LoadNeptuneCli failed: S3Exception ..." and exits 1 (Cli.scala:136-141).
     - The outcome is the same either way: exit 1, nothing loaded. Only the message is less specific.

HIGH-COST RESOURCES AND BOUNDS: unchanged (no capacity change).
SCRIPT GATES: no script changed.
SECURITY SCAN: Checkov not run (optional under ruling 5; it was already run once in Phase 12). The only security-relevant change is the widened ListBucket, which ruling 23 decided.

COMMANDS RUN:
- `terraform fmt -check -recursive infra/terraform`
- `terraform -chdir=infra/terraform/envs/course init -backend=false -input=false` (reused the installed hashicorp/aws v6.28.0 from the lock file; no download)
- `terraform -chdir=infra/terraform/envs/course validate`
- `bash -n` on the 13 scripts under scripts/
- `SPARK_LOCAL_IP=127.0.0.1 scripts/check.sh`, once, in the background, on the final tree
- Read-only shell commands: `ls -R` of infra/terraform/modules and scripts; `find` of the Scala main sources under modules/{storage,services,rag}/src/main (file names only); `wc -l`; `df -h`; `pgrep` (sbt check)
- One aws-docs search_documentation call (public AWS documentation, not an AWS API). It returned links only; the cited rule comes from ruling 23.
- No git, no aws, no terraform plan or state command, no cleanup.sh, no Bash grep.

TEST RESULTS: scripts/check.sh exited 0, with every gate OK.
- json, constitution: validate-agent-files 231/231; hook self-tests guard-dangerous 304/304, guard-paths 142/142, verify-tests-ran 11/11.
- bash-syntax OK.
- shellcheck: "SKIPPED: shellcheck not installed".
- dataset-validation OK, terraform-fmt OK, terraform-validate OK, sbt-check OK.
- sbt tests: 839 passed, 0 failed, 10 pending. Eight module summaries: 191, 111, 116, 115, 49, 46, 141 (+10 pending), 70; 0 failed and 0 aborted in each.
- markTestRun line: "[info] markTestRun: tests passed, wrote /Users/achyutpatel/Developer/Cloud/ScholarChain/.claude/.last-test-run".
- Log: scratch/phase-12-logs/r03a-check.txt (1826 lines).

VALIDATION RESULTS:
- fmt: exit 0.
- init: "Terraform has been successfully initialized!", exit 0.
- validate: "Success! The configuration is valid.", exit 0.
- bash -n: ok for all 13 scripts.
- shellcheck: not installed.
- plan: not run (human gate).
- Log: scratch/phase-12-logs/r03a-tf-validate.txt.

GREP TOOL: Grep is not in this session's function list, so no Grep call could be made ("No such tool" in effect). All inspection used the Read tool, plus the file-listing commands named above. Bash grep was not used.

HOUSEKEEPING: 10 GiB free at the start and at the end. No sbt process is left (pgrep: none). The one background shell (check.sh) has finished.

ASSUMPTIONS:
- S3A (the Hadoop 3.4 line on emr-spark-8.0.0) does getFileStatus as a HEAD of the key and then a LIST, so HEADs of directory keys under raw/ and curated/ also hit the 403. This is why the N4 failure was broader than the parent walk.
- AmazonEMRServicePolicy_v2 grants no read of this bucket's objects that the service depends on. The managed policy text was not re-read here.

RISKS:
- The unconditioned ListBucket lets the EMR instance role (that is, any step code) list every key name in the data bucket, including graph/, runs/, reports/ and logs/rag-runtime/. It still cannot read object data. Ruling 23 accepted this.
- Whether S3A probes any key outside parent_keys and the read prefixes stays unverified until the first window 1 run. The likely candidate is the bucket root, which S3A does not HEAD.
- The neptune-loader gives a less specific error message ("S3Exception: Access Denied") when a manifest key is missing after a failed or skipped upload.

MANUAL ACTION REQUIRED:
- During the human plan review, read the ListDataBucket statement in the plan. It shows as an in-place change of module.emr.aws_iam_role_policy.instance_s3 once window 1 is applied; it is part of the 43 creates on an empty state.
- docs/agent-runs/phase-12/repair-02.md still contains the wrong "turns that 403 into 404" claim. It is human-owned, so it is left as is.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code review 4 (repair-03 only), human plan review and code-reviewer APPROVE; apply only through the approved path.
````
