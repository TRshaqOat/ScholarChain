````text
```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 03, repair-01. Apply the human architect's decisions 1 to 3 to the manifest, the ORCID samples, the ORCID schema note, and every script that reads OPENALEX_API_KEY. Confirm no script can print the key, then run validate.sh and fetch_orcid.sh in SAMPLE mode.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/samples/orcid/orcid-fictional-test-record.jsonl   (built by build_samples.sh from the fetched test record)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/samples/orcid/synthetic-orcid-records.jsonl        (written by hand)
FILES MODIFIED (all still untracked from work order 1; nothing outside datasets/ was changed):
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/dataset-manifest.yaml
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/config/subset.v1.json   (new keys orcid.fictional_test_record_id = "0000-0002-1825-0097" and orcid.fictional_test_record_marker = "fictitious person". config_version stays dblp-subset.v1 because the subset rule did not change. sha256 changed from f3023986...d794 to 91c02783f61164c15c243f34659510347ef74d20c86d2dac23fd6db877ebfc8d.)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/schemas/orcid.md
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/lib.sh            (set +x forced; curl -q)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/fetch_openalex.sh  (key handling)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/fetch_dblp.sh      (curl -q only)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/fetch_orcid.sh     (SAMPLE mode also fetches the fictional test record)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/build_samples.sh   (--include-orcid removed; real ORCID records are never copied into the repository)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/scripts/validate.sh        (enforces decision 1)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/samples/SHA256SUMS         (now 8 entries, 736 B, sha256 9c8709a63148155c9bc6e8d5288921be812d94f504aa503500af5b46c4496f0b)
FILES DELETED: none in the repository or under scratch/. In the session scratchpad I deleted my own test log keytest/before.log; see RISKS.

DECISIONS APPLIED:
- Decision 1 (ORCID):
  - The manifest has a new top-level human_rulings block that records all three rulings, with a line stating that a ruling does not verify a provider fact.
  - orcid.license still reads "UNVERIFIED for redistribution (provider fact unchanged)" and states that the escalation is resolved by the ruling.
  - orcid.samples lists the 2 committed files.
  - privacy_notes, subset_rule, integrity_policy, known_limitations, license_evidence, and documentation_links are updated.
  - build_samples.sh writes the real-record projection only to scratch/datasets/orcid/sample/orcid-sample.minimized.jsonl.
- Decision 2 (OPENALEX_API_KEY): recorded in openalex.access_rules and human_rulings. Script hardening is described under VALIDATION RESULTS.
- Decision 3 (subset size): recorded in human_rulings, in the dblp.subset_rule text, and as a comment on expected_size.subset_works_estimate. It is still labeled an estimate, and the observed count stays UNKNOWN until --full runs. The venue list is unchanged.

DATASETS (changes only; every other field is as reported in work order 1):
- orcid | fictional test record from https://pub.orcid.org/v3.0/0000-0002-1825-0097/record | committed under decision 1(a). Redistribution of ORCID API data in general stays UNVERIFIED | retrieved 2026-11-03T00:56:02Z UTC (probe at 00:52:47Z), HTTP 200 | JSON, stored gzip | raw 2,982 B .json.gz | per-run SHA256SUMS in scratch/datasets/orcid/test-record/ | iD taken from config orcid.fictional_test_record_id.
  Evidence that the record is official and fictional:
  (1) It was fetched from ORCID's official Public API. Its public biography reads: "Josiah Carberry is a fictitious person. This account is used as a demonstration account by ORCID, CrossRef and others who wish to demonstrate the interaction of ORCID with other scholarly communication systems without having to use a real-person's account."
  (2) https://github.com/ORCID/orcid-api-tutorial/blob/master/docs/display.md, in the ORCID GitHub organization and accessed 2026-11-03T00:53Z, uses https://orcid.org/0000-0002-1825-0097 as its example iD. `gh search code` also finds it in ORCID/orcid-php-demo-app and in ORCID/orcid-angular test specs.
  (3) https://en.wikipedia.org/wiki/Josiah_Carberry, accessed 2026-11-02, corroborates as a third party: "He is assigned ORCID 0000-0002-1825-0097".
  Verdict: VERIFIED as official and fictional, with the caveats under RISKS.

SAMPLES:
- datasets/samples/orcid/orcid-fictional-test-record.jsonl | 1 record | 935 B | ORCID's fictional demonstration record (decision 1(a)). Same projection as before, plus top-level fields sample_kind = "orcid-fictional-test-record", synthetic = false, source_url, and retrieved_utc.
- datasets/samples/orcid/synthetic-orcid-records.jsonl | 3 records | 1,801 B | synthetic and written by hand. Each has sample_kind = "synthetic", synthetic = true, and synthetic_note. The iDs are synthetic:orcid-a, synthetic:orcid-b, and synthetic:orcid-c. Names are "Sample Synthetic-A/B/C"; their tokens appear nowhere among the 299 name tokens in the fetched ORCID, OpenAlex, Crossref, and DBLP sample data. The work_dois are 5 DOIs from datasets/samples/dblp/dois.txt; orcid-a and orcid-b share 10.1145/1183401.1183426. There are no external IDs and no real organizations.
- All other samples are byte-identical to work order 1 (build_samples rerun: 7 SKIP "unchanged").

UNKNOWNS AND ESCALATIONS:
- Escalations 1 to 3 from work order 1 are resolved by the rulings. No new escalation.
- Manifest markers went from UNVERIFIED=8 to 10 and from UNKNOWN=5 to 6. All three additions are new statements:
  - the human_rulings header comment, which says a ruling does not verify a provider fact;
  - the test-record license_basis, which says ORCID API redistribution in general is UNVERIFIED;
  - the decision-3 text, which says the observed subset count is UNKNOWN until --full runs.
  No marker was removed or promoted to verified because of a ruling.
- Open item still for domain-engineer: the configuration key for the raw bucket name, still `<raw-bucket>`. Not blocking.

COMMANDS RUN (all observed):
- Evidence:
  - curl -q of https://pub.orcid.org/v3.0/0000-0002-1825-0097/record into the scratchpad: HTTP 200, 22,690 B.
  - WebFetch of support.orcid.org (HTTP 403), info.orcid.org/ufaqs/what-is-josiah-carberry/ (HTTP 404), the info.orcid.org API tutorial (no mention of the iD), and Wikipedia.
  - gh search code '0000-0002-1825-0097' --owner ORCID.
  - curl of raw.githubusercontent.com ORCID/orcid-api-tutorial docs/display.md (HTTP 200).
- bash datasets/scripts/fetch_orcid.sh (SAMPLE), run twice:
  - Run 1, exit 0: "[STEP] test record: fetch ... 0000-0002-1825-0097", then "[DONE] test record", then "[SKIP] fetch: .../orcid/sample already complete". That was 1 network request.
  - Run 2, exit 0: "[SKIP] test record ... already complete" and "[SKIP] fetch ... already complete".
- bash datasets/scripts/build_samples.sh, run 3 times, all exit 0. The final run gave 7 SKIP lines and changed no file.
- bash datasets/scripts/validate.sh, run 3 times (passing on the 2 runs after the synthetic file existed), plus 2 negative copies.
- Key-leak harness and test-record branch tests in the session scratchpad, against local python http.server instances on 127.0.0.1 only.
- No --full run. sbt check was not run because no Scala or sbt source changed.

TEST RESULTS:
- Test-record branch, run against a local server:
  - With the real record: exit 0 and 4 files written (json.gz, SHA256SUMS, _SUCCESS, run-info.json).
  - With the biography changed to remove "fictitious person": exit 4, "[FAIL] test record: 0000-0002-1825-0097 did not return a record that declares itself fictitious; not used", and 0 files left.
  - Rerun of the good case: "[SKIP] test record ... already complete".
- Negative test 1 (on a copy): datasets/samples/orcid/records-sample.jsonl added with a non-synthetic, non-test iD 0000-0001-2345-6789 (ORCID's documentation example iD). SHA256SUMS was regenerated in the copy so that only decision 1 can fail.
  Result: exit 1, PASS=58, FAIL=2 (one check plus the group summary):
  "FAIL orcid decision 1 datasets/samples/orcid/records-sample.jsonl record 1: synthetic record (top-level synthetic label missing; file name lacks 'synthetic'; iD '0000-0001-2345-6789' lacks the 'synthetic:' prefix; contains an ORCID-iD-shaped string)"
- Negative test 2 (on a copy): a record labeled synthetic was appended with iD "synthetic:0000-0001-2345-6789", a committed DBLP author's name, and DOI 10.5555/12345678. The manifest bytes and SHA256SUMS were updated in the copy.
  Result: exit 1, PASS=56, FAIL=2:
  "FAIL ... synthetic-orcid-records.jsonl record 4: synthetic record (contains an ORCID-iD-shaped string; name shared with a committed sample author: [<name redacted here>]; work_dois must be non-empty and in dblp/dois.txt (0/1))"

VALIDATION RESULTS:
- bash datasets/scripts/validate.sh: exit 0, PASS=56, FAIL=0, "[DONE] validate: all checks passed".
  - "PASS orcid decision 1 ... orcid-fictional-test-record.jsonl record 1: fictional test record 0000-0002-1825-0097, labeled, in a fictional-test-record file"
  - "PASS orcid decision 1 ... synthetic-orcid-records.jsonl record 1/2/3: synthetic record"
  - "INFO orcid samples: fictional_test_records=1 synthetic_records=3"
  - "INFO manifest markers: UNVERIFIED=10 UNKNOWN=6"
  - "INFO doi overlap: dblp=40 dblp&openalex=40 dblp&crossref=40 all_three=40"
  - The manifest sample sizes match the files (935 and 1,801 B). `shasum -a 256 -c datasets/samples/SHA256SUMS` gave 0 non-OK lines.
- Key-leak audit (decision 2). The sentinel key was "SENTINEL-not-a-real-key-7f3a9c", not the real key. Each harness ran fetch_openalex.sh with `bash -x` and HOME pointing at a directory whose .curlrc contains "verbose". Counts are occurrences of the sentinel:
  | run | what it tests | exit | console | files written | server log |
  |---|---|---|---|---|---|
  | before | work-order-1 scripts | 0 | 4 | 0 | 0 |
  | after | fixed scripts, 200 response | 0 | 0 | 0 | 0 |
  | echo | response that contains the key | 4 | 0 | 0 | 0 |
  | err | HTTP 404 error path | 5 | 0 | 0 | 0 |
  The "files written" column counts files under the run's scratch tree, including run-info.json and SHA256SUMS. The two server logs had 0 occurrences in 5 lines.
  - The 4 "before" leaks, shown as <KEY>: three xtrace lines ("'[' -n <KEY> ']'" twice and "printf 'Authorization: Bearer %s\n' <KEY>") and one curl verbose line from ~/.curlrc ("> Authorization: Bearer <KEY>").
  - The "after" trace stops at "++ set +x" inside lib.sh. Its output only says "api_key=set".
  - The echo run gave "[FAIL] fetch: response for b.aaaaa contains the API key; discarded" and left the raw/ directory empty. The err run gave "[FAIL] fetch: HTTP 404 at b.aaaaa".
  - The after run-info.json fields are dataset, mode, api_base, dblp_snapshot, config_version, input_dois_sha256, retrieved_utc, works, and missing_dois. None is the key.
- Every path the work order names, as handled now:
  - Step markers: only "api_key=set|unset" is printed.
  - curl verbose and trace: no script passes -v or --trace, and `curl -q` (first argument, in lib.sh http_get and fetch_dblp.sh) ignores ~/.curlrc.
  - Error messages: die() texts contain batch names and HTTP codes only. `curl -sS` errors do not include request headers.
  - run-info.json: built from explicit arguments that never include the key.
  - set -x: lib.sh forces `set +x` right after strict mode. No script calls set -x.
  - URL logging: the key is never in a URL; it is sent only as an Authorization header.
  - Request echo: responses containing the key are deleted and the run exits 4.
  - argv: the header goes in through `-H @/dev/fd/N`, written by the printf builtin. The traced "before" curl argv showed "-H @/dev/fd/63" and no key.
  - Environment: the key is copied into the non-exported variable OA_KEY, and OPENALEX_API_KEY is unset, so curl, jq, and awk do not inherit it.
  - Only fetch_openalex.sh reads the key (grep of datasets/).

ASSUMPTIONS:
- Decision 1 covers ORCID records, meaning the ORCID dataset. It does not cover ORCID iDs that appear inside the CC0 DBLP and OpenAlex samples: datasets/samples/dblp/orcids.txt has 90 iDs, and dblp-sample.xml and openalex/works-sample.jsonl contain iD attributes and fields. Those files are unchanged. If the human means the ruling to cover them too, a follow-up is needed.
- The committed test record uses the same minimized projection as real records. Biography, emails, addresses, keywords, and researcher URLs are dropped.
- The fictional test record is fetched only in SAMPLE mode. Its one request is not added to the daily request counter.

RISKS:
- My own commands exposed the contact email. The "before" harness ran under `bash -x` with SCHOLARCHAIN_CONTACT_EMAIL set in my environment, so the trace captured the email in curl's -A user-agent argument (6 occurrences). I then printed one of those trace lines to my tool output, which broke the instruction never to print that value. It went only to a local 127.0.0.1 test server (whose log records no user-agent) and to the scratchpad log. I deleted that log (scratchpad/keytest/before.log). A recheck found 0 occurrences in the other scratchpad test files. The value is now in this session's transcript.
- The email can still be seen by other local users through process arguments. user_agent() passes it on curl's -A argument, and fetch_crossref.sh sends it as a mailto URL parameter. Both are visible in `ps` while curl runs. The fixed scripts no longer trace it, and curl -q keeps a .curlrc from printing it. This is outside decision 2, which covers the key; I suggest a follow-up work order to move both into a header passed through a process substitution.
- A response that echoes the key exists on disk as `<file>.part` until the check deletes it, which takes milliseconds.
- Not tested: whether curl 8.7.1 with -L forwards a custom Authorization header to a different host on redirect. curl's documented behavior since 7.58 is not to.
- The test record's biography has source null, so the fictitious statement is asserted by the record holder. ORCID's support article on iD structure answered HTTP 403 and was not read. The ORCID-run evidence is the official API itself and ORCID's GitHub tutorial and demo code.
- The test record's work DOIs (10.5555/... test DOIs and 10.1109/tps.1987.4316723) are not in the DBLP sample, so it does not link to DBLP. Re-fetching it changes retrieved_utc and possibly last_modified_ms, which changes the committed file and SHA256SUMS.
- The synthetic records attach fictitious names to real DBLP works. This is documented as a deliberate mismatch, and they are not evidence about any real author.
- The validator's name-collision check compares only against authors in the committed samples, not against scratch data. I ran a one-time token check against the fetched scratch data: 0 collisions.

MANUAL ACTION REQUIRED:
- When ready, run the full subset. This is unchanged from work order 1, except that OPENALEX_API_KEY is now accepted (decision 2) and is set only in the human's own terminal.
- Leftovers, not deleted:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/datasets/probe (about 326 MB, from work order 1)
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/datasets/orcid/sample.superseded-uncompressed (from work order 1)
  - Session scratchpad test trees under /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b7dc6dfb-710c-4b42-91d4-a702cb625b00/scratchpad/ (orig, probe-carberry, keytest, orcidtest, neg1, neg2). They contain the sentinel only, not the real key or email.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/datasets/orcid/test-record/ is the intended new input for build_samples.sh. It is git-ignored.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review
```

Skipped: I did not move the contact email out of process arguments (it is outside decision 2) and did not test curl's redirect header behavior.
Risk to know: I printed the contact email once in my own tool output while running the before-fix harness. Details are under RISKS.
````
