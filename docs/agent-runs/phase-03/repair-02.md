````text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 03, repair-02 (human architect, verbatim): "In datasets/dataset-manifest.yaml, replace the raw-bucket placeholder note with a reference to configuration key scholarchain.storage.raw-bucket and environment variable SCHOLARCHAIN_RAW_BUCKET, keeping the s3://<raw-bucket>/<dataset>/snapshot=<date>/ layout. Change nothing else. Run the validation script. Return a standard report."

FILES CREATED: none
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/dataset-manifest.yaml (line 12, the trailing comment only)
FILES DELETED: none

CHANGE (line 12, exact)
Before:
raw_bucket: "<raw-bucket>"   # the bucket name comes from configuration (config/**, owned by domain-engineer); UNKNOWN until defined there
After:
raw_bucket: "<raw-bucket>"   # resolved at run time from configuration key scholarchain.storage.raw-bucket (env override SCHOLARCHAIN_RAW_BUCKET); layout s3://<raw-bucket>/<dataset>/snapshot=<date>/

The value "<raw-bucket>" stays as it is. Only the comment changed. The four s3_raw_prefix values (lines 73, 133, 186, 246) and every other line are unchanged.

DIFF
`git diff -- datasets/dataset-manifest.yaml` printed 0 lines because the file is untracked (`git status --porcelain` shows `?? datasets/dataset-manifest.yaml`), so git has no baseline to compare against. To show the change, I rebuilt the pre-edit file by restoring line 12 in a scratch copy (/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b7dc6dfb-710c-4b42-91d4-a702cb625b00/scratchpad/manifest.before.yaml) and ran `diff -u`:

--- a/datasets/dataset-manifest.yaml
+++ b/datasets/dataset-manifest.yaml
@@ -9,7 +9,7 @@
 manifest_version: "1"
 datasets_in_scope: [dblp, openalex, crossref, orcid]   # D-10: exactly four datasets
 subset_config: datasets/config/subset.v1.json            # config_version dblp-subset.v1 (venue list, years, fetch bounds)
-raw_bucket: "<raw-bucket>"   # the bucket name comes from configuration (config/**, owned by domain-engineer); UNKNOWN until defined there
+raw_bucket: "<raw-bucket>"   # resolved at run time from configuration key scholarchain.storage.raw-bucket (env override SCHOLARCHAIN_RAW_BUCKET); layout s3://<raw-bucket>/<dataset>/snapshot=<date>/
 # Human architect rulings on the phase-03 work order 1 escalations (phase-03 repair-01 work order, 2026-11-02).
 # A ruling decides what ScholarChain does. It does not turn an UNVERIFIED provider fact into a verified one.
 human_rulings:

Evidence for the reference (I only read these files, which belong to domain-engineer):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/resources/scholarchain.conf, lines 15-26: the key `raw-bucket` sits under `scholarchain { storage { ... } }`, which makes the full key scholarchain.storage.raw-bucket. Its value is the placeholder "example-scholarchain-raw" with the comment `# SCHOLARCHAIN_RAW_BUCKET`, and line 25 documents the layout s3://<raw-bucket>/<dataset>/snapshot=<date>/.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala, line 93: `"SCHOLARCHAIN_RAW_BUCKET" -> "storage.raw-bucket"`.

DATASETS: no changes. No source, license, snapshot, size, checksum, or subset rule was touched.
SAMPLES: no changes. datasets/samples/SHA256SUMS still verifies (see validation below).

UNKNOWNS AND ESCALATIONS: none new. Removing the words "UNKNOWN until defined there" took the manifest's UNKNOWN count from 6 to 5. That is intended: the configuration key now exists.

COMMANDS RUN:
1. grep for raw-bucket, raw_bucket, and RAW_BUCKET in datasets/ and the two core config files (read-only)
2. sed -n 1,30p scholarchain.conf; grep of validate.sh for raw_bucket, UNKNOWN, and checksum checks
3. bash datasets/scripts/validate.sh (baseline, before the edit)
4. Edit of datasets/dataset-manifest.yaml, line 12
5. bash datasets/scripts/validate.sh (after the edit), plus a line diff of the before and after output
6. git status --porcelain and git diff on the manifest, shasum -a 256, wc -c
7. diff -u against the rebuilt pre-edit copy in the scratchpad

TEST RESULTS / VALIDATION RESULTS (observed):
- Before the edit: validate.sh exit 0; 46 PASS lines; "INFO manifest markers: UNVERIFIED=10 UNKNOWN=6"; "[DONE] validate: all checks passed"
- After the edit: validate.sh exit 0; 46 PASS lines; 0 FAIL lines; "INFO manifest markers: UNVERIFIED=10 UNKNOWN=5"; "PASS datasets/samples/SHA256SUMS verifies"; "2026-11-03T01:42:54Z [DONE] validate: all checks passed"
- Comparing the two outputs (timestamp lines excluded), the only difference is the INFO markers line (UNKNOWN 6 to 5).
- The validate.sh check at line 72 (each s3_raw_prefix starts with "s3://<raw-bucket>/{ds}/snapshot=") still passes, because the prefixes are unchanged.
- The manifest after the edit: sha256 5632e8b4c8228f1cccb36a899d656e78e4fac4fdd243ee58e67c72b16c516252, 29398 bytes.
- sbt check: not run. No Scala or sbt source changed, and this is a YAML comment edit inside datasets/**.

ASSUMPTIONS:
- "Placeholder note" means the trailing comment on line 12. The value "<raw-bucket>" is kept because the four s3_raw_prefix entries and validate.sh line 72 depend on that placeholder, and the order says "change nothing else".

RISKS:
- validate.sh needed no matching change. It reports the UNKNOWN count as INFO only and does not assert it. It does not record a size or checksum for the manifest, and datasets/samples/SHA256SUMS does not list the manifest. If an earlier report or phase evidence cites "UNKNOWN=6", that number is now 5.
- The manifest is untracked, so git cannot show this diff. Reviewers should use the reconstructed diff above.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review
````
