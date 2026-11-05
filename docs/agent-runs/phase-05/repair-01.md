# Phase 05 repair-01: dataset-engineer

````text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 05, repair-01. The human architect's ruling on the ORCID priority order: cap 23,000; --full order b, then a ranked by work count, then c, then d; record the ranking in selection.json; update the self-test and the manifest. Repairs Work order 1.

SUMMARY: The --full selection is now 4,146 class (b) iDs plus 18,854 class (a) iDs, which is 23,000 iDs. The class (a) iDs are the ones with the most distinct subset works. Among the selected class (a) iDs the work count runs from 2 to 352. SAMPLE uses the same class order but keeps class (a) in iD string order. Its selected list is byte-identical to the completed sample's.

FILES CREATED: none
FILES MODIFIED (all on top of the uncommitted Work order 1 changes):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/config/subset.v1.json: orcid.full_max_ids changed from 24000 to 23000. daily_request_cap stays at 24000.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/dblp_extract.py: new pass `--pass orcid-work-counts --openalex <works.jsonl> --out DIR`, which reads works.xml on stdin. It writes orcid-work-counts.tsv and stats-orcid-work-counts.json. It is in this file so that it reuses the existing KEY, AUTHOR, ORCID_ATTR, DOI_EE and checksum code. This file is under datasets/ but was not on the orchestrator's list.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/fetch_orcid.sh: select_ids now takes a rank mode (works or string) plus the DBLP works.xml and the OpenAlex works.jsonl. Class order is b, a, c, d. selection.json is extended. The self-test is rewritten. The header and usage are updated (usage now prints lines 2 to 23, so it no longer prints the `source` line). `join` was added to require_tools.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/validate.sh: the ORCID caps check also requires full_max_ids <= documented_anonymous_daily_reads - 2000, citing the ruling. The self-test check is renamed.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/dataset-manifest.yaml, ORCID entry only:
  - access_rules: 23,000 cap and 2,000-read retry margin.
  - expected_size: updated with the new plan figures, plus new full_class_a_work_counts and full_work_linkage entries.
  - coverage note: now 23,000 of 111,166 (20.7%).
  - subset_rule: rewritten.
  - known_limitations: the cap limitation is rewritten and a DOI-linkage limitation is added.
  - selection.json validation command: now `.selected_total == 23000 and .class_a_ranking.rank == "works"`.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/schemas/orcid.md: the selection.json field list is updated.
FILES DELETED: none

WORK IDENTITY AND DE-DUPLICATION (what I used):
- **DBLP side.** Each record in the full works.xml counts as `dblp:<key>` for every checksum-valid `<author orcid>` iD on it. This is the same filter that builds orcids-works.txt.
- **OpenAlex side.** Each work in the full works.jsonl counts for every `authorships[].author.orcid` after removing the https://orcid.org/ prefix and checking the format. This is the same filter that builds orcids.txt.
- **Matching the two.** The OpenAlex work's DOI is lower-cased with the https://doi.org/ prefix removed. If it equals a DOI on a DBLP subset record, the work counts as that record's `dblp:<key>`. DBLP DOIs are the <ee> doi.org links, html-unescaped and lower-cased, exactly as in dois.txt. If several records share a DOI, the lowest key is used; observed: 0 such DOIs.
- **Unmatched works.** An OpenAlex work that matches no record counts as `doi:<DOI>`, or as `openalex:<id>` if it has no DOI. Two OpenAlex works with the same DOI therefore count once.
- **What is never matched.** A DBLP record without a DOI is never matched to an OpenAlex work, and there is no title matching. DBLP person records contribute no works.
- **Observed on the full inputs:**
  - DBLP: 110,867 records, 105,816 of them with a DOI.
  - OpenAlex: 107,506 works. 107,412 matched a DBLP record, 94 have a DOI that matches no record, and 0 have no DOI.
  - 107,778 iDs counted, equal to the union of orcids-works.txt and OpenAlex orcids.txt (comm check: 0 differences).
  - Every class (a) iD has a count of at least 1 (0 iDs at 0).

--full --plan OUTPUT (observed, exit 0, 3.0 s):
- class b_dblp_person_records: available 4146, selected 4146
- class a_dblp_and_openalex: available 57665, selected 18854
- class c_dblp_author_elements: available 2682, selected 0
- class d_openalex_only: available 46673, selected 0
- class a ranking: works; selected min_works 2, max_works 352
- totals: mode full, dblp 64493, openalex 104338, union 111166, selected 23000, max 23000, daily_cap 24000
- requests: 23000 (0 already fetched); used today (UTC 2026-11-03): 0 of 24000; fits in the current UTC day
- duration: at least 1.3 h from the pause alone, or about 4.1 h at the sample's 0.638 s per request
- disk: about 680 MiB
- Afterwards scratch/datasets/orcid/ held only sample, sample.superseded-uncompressed, and test-record. No full directory was created.
- How the class (a) cut falls (I computed this separately from the same counts file): 18,754 iDs with 3 or more works, all selected. 9,994 with exactly 2 works; 100 selected, by iD string order. 28,917 with 1 work, none selected.

SAMPLE: It keeps class (a) in iD string order (rank "string") under the same b, a, c, d order. Its class (b) is empty (available 0), so ids.txt is unchanged:
- Both SAMPLE runs passed the script's cmp guard. A changed list would have exited 4.
- ids.txt sha256 is 8d22c41a38456f8a33af4f24631682ff89f18f9f5ad92b4cf2e6e6a533552248 both before and after.
- The completed sample directory still has no selection.json. It predates the file, and a resumed run does not write one.

DATASETS: Only the ORCID entry changed.
- Source: https://pub.orcid.org/v3.0/{orcid}/record
- License: UNVERIFIED for redistribution (decision-1, unchanged).
- Snapshot: the UTC start date of the full run, which has not been made.
- Format: JSON per iD, gzip.
- Size: 111,166 candidates, 23,000 selected. Full-run bytes not yet observed.
- Checksum: not yet; SHA256SUMS is written at completion.
- Subset rule: as above.
- Evidence: the --plan output at 2026-11-03T05:40Z, from DBLP 2026-10-03 full and OpenAlex full (retrieved_utc 2026-11-03T03:26:48Z).

SAMPLES: unchanged.
- datasets/samples/orcid/orcid-fictional-test-record.jsonl: 1 record, 935 bytes.
- datasets/samples/orcid/synthetic-orcid-records.jsonl: 3 records, 1801 bytes.
- License basis: decision-1.

UNKNOWNS AND ESCALATIONS:
- Whether ORCID's quota resets at 00:00 UTC or runs over a rolling 24 hours is still UNKNOWN.
- No new license questions.

COMMANDS RUN (all observed):
- python3 -I datasets/scripts/dblp_extract.py --pass orcid-work-counts on the full inputs, writing to the scratchpad: the stats above, 2.7 s.
- comm of the counted iDs against the union of orcids-works.txt and OpenAlex orcids.txt: 0 differences each way.
- bash datasets/scripts/fetch_orcid.sh --self-test: "fetch_orcid selection self-test: OK", exit 0.
- Mutation checks, each on a scratchpad copy:
  - unmutated copy: OK, exit 0
  - ranking ascending: FAIL "wrong selected list (max 5, rank works)", exit 1
  - tie-break reversed: same FAIL, exit 1
  - string order used in --full: same FAIL, exit 1
  - class order a, b, c, d: same FAIL, exit 1
  - no de-duplication by DBLP key (each OpenAlex work counted separately): FAIL "wrong selection.json", exit 1
  - no DOI lower-casing: FAIL "wrong selection.json", exit 1
  - min/max computed over all class (a) iDs instead of selected ones: FAIL "wrong selection.json", exit 1
- bash datasets/scripts/fetch_orcid.sh --plan (SAMPLE): requests 0 (30 selected, 30 already fetched), exit 0.
- bash datasets/scripts/fetch_orcid.sh (SAMPLE), run 1: b 0/0, a 30/84, c 0/6, d 0/53. The test record and the fetch were both SKIP (already complete). Exit 0.
- bash datasets/scripts/fetch_orcid.sh (SAMPLE), run 2: identical output, exit 0.
- shasum -a 256 -c SHA256SUMS in scratch/datasets/orcid/sample: 0 lines that were not OK.
- bash datasets/scripts/fetch_orcid.sh --full --plan: output above, exit 0.
- bash datasets/scripts/validate.sh: exit 0.
- --full was not run.

TEST RESULTS:
- validate.sh: 58 PASS lines, 0 FAIL lines, "[DONE] validate: all checks passed".
- Inside that run, these passed: dblp_extract.py --self-test, the extended ORCID caps check, and the fetch_orcid.sh --self-test.

VALIDATION RESULTS:
- validate.sh's manifest checks passed: required fields, sample sizes, SHA256SUMS.
- Markers: UNVERIFIED=10, UNKNOWN=4. UNVERIFIED was 11 in Work order 1. I removed the "string order may correlate with seniority" limitation because class (a) is no longer cut by string order.
- PyYAML is not installed, so the manifest was not parsed by a YAML library. Only validate.sh's own format checks ran.

CAP CONSISTENCY CHANGES:
- full_max_ids is now 23,000. 23,000 <= daily_request_cap 24,000 < 25,000 still holds, so the existing check needed no change.
- I added the human's margin as a check: full_max_ids <= documented - 2000.
- The manifest has no "1,000-read margin" text; that wording was only in the Work order 1 report. Manifest text that said 24,000 now says 23,000: access_rules, full_record_requests, full_days_at_cap, the coverage note, known_limitations, and the validation command.
- daily_request_cap was left at 24,000. It is a per-run-directory stop for exit 7, and the full run never reaches it on its own.

ASSUMPTIONS:
- "Distinct subset works" means DBLP subset records plus OpenAlex works fetched for subset DOIs, joined by DOI only (see above). Ties at the cut (100 of 9,994 iDs with 2 works) are broken by iD string order, as the ruling says.
- config_version is not bumped, as in Work order 1. selection.json carries config_sha256.
- SAMPLE uses the same class order with string-ordered class (a). I chose this over the old a, b, c, d order. The result is the same here because SAMPLE class (b) is empty.

RISKS:
1. Matching by DOI only. 5,051 DBLP records have no DOI, and 94 OpenAlex works have a DOI that matches no DBLP record. One work can be counted twice for an iD that appears on both sides. Such a work can only add to an iD's count, so the effect on ranking is small but not zero.
2. The ranking favors prolific authors and is not a random sample of class (a). 28,917 single-work class (a) iDs are excluded, along with all of classes (c) and (d).
3. The fetch loop is still not exercised live; both SAMPLE runs skipped the fetch. The other Work order 1 risks still apply: an unattended overnight run, a sleeping Mac, and inputs changing before the run.
4. shellcheck is not installed, so it was not run. bash -n passed through validate.sh.

MANUAL ACTION REQUIRED:
- Human: review the new selection, then start --full near 00:00 UTC with `caffeinate -i bash datasets/scripts/fetch_orcid.sh --full 2>&1 | tee -a scratch/datasets/logs/orcid-full-$(date -u +%Y%m%d-%H%M).log`.
- After the full run: route back to dataset-engineer to fill the manifest coverage fields.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review

Not checked: YAML parsing with a real parser (PyYAML is absent), and shellcheck. Risk: work counts link DBLP and OpenAlex by DOI only (see RISKS item 1).
````
