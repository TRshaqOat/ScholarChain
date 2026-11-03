````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 03 code review (human architect, verbatim in the orchestrator's message). Review every uncommitted Phase 3 change (datasets/**, modules/core/**, build.sbt, project/Dependencies.scala) against docs/agent-runs/phase-03/prompt.md, the spec's Phase 3 gate, and D-02, D-05, D-09, and D-10. Report only findings that would block ratification. Do not edit any file.

VERDICT: APPROVE

SCOPE REVIEWED:
- Tracked diff: /Users/achyutpatel/Developer/Cloud/ScholarChain/build.sbt and /Users/achyutpatel/Developer/Cloud/ScholarChain/project/Dependencies.scala. I read both files in full and checked them against the supplied `git diff`.
- Untracked files, each read in full:
  - /Users/achyutpatel/Developer/Cloud/ScholarChain/datasets/dataset-manifest.yaml
  - datasets/config/subset.v1.json
  - every script under datasets/scripts/ (lib.sh, fetch_dblp.sh, dblp_extract.py, fetch_openalex.sh, fetch_crossref.sh, fetch_orcid.sh, build_samples.sh, validate.sh)
  - datasets/schemas/dblp.md and openalex.md
  - the ORCID-relevant lines of datasets/schemas/orcid.md
  - both ORCID sample files, dblp/dois.txt, samples/SHA256SUMS, and the head of dblp-sample.xml
  - every file under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/core/ (5 main sources, scholarchain.conf, 4 specs, Fixtures.scala, wire/examples-v1.json)
- Context read: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-03/prompt.md, agent-report.md, repair-01.md, repair-02.md, spec/architect-decisions.md (all of it), spec/ScholarChain-spec.md ("Dataset manifest" section and Phase 3, including the gate and the stable-ID principles), and .gitignore.
- Grep sweeps: emails, secrets, keys, real ORCID records, abstracts, and Spark or Hadoop references in core.
- Not read line by line: crossref/works-sample.jsonl, openalex/works-sample.jsonl, orcids.txt, the DTD, crossref.md, and lines 21 onward of dblp-sample.xml. I covered those by pattern sweeps only.

FINDINGS: none blocking (no CRITICAL or MAJOR).

Results of each check the work order names (verified against the files):
1. Provenance and schema version on every record: PASS.
   - All 21 records in Records.scala have a required `schemaVersion: String` and a required `provenance: Provenance`, with no defaults. Provenance has its own `schemaVersion`.
   - Validation.scala rejects a null or missing provenance through `Validator.provenance`.
   - WireContractSpec.scala:61-68 tests both the missing-key case and the JSON-null case for all 20 non-Provenance records.
2. Contract tests pass and would fail on a format change: PASS.
   - WireContractSpec.scala:36-41 compares each fixture's ujson encoding with the frozen value in wire/examples-v1.json.
   - Adding, removing, renaming, or retyping a field, changing an enum's wire form, or changing a stable-ID formula all change the encoding, so the test fails. Field order is ignored, which is correct for JSON objects.
   - Line 31 checks the key set, so a missing or orphaned example also fails.
   - Line 43 checks decoding from the frozen file.
3. Each validation rule is tested: PASS. ValidationSpec.scala covers:
   - missing provenance (:20)
   - confidence outside [0, 1], including NaN and the 0.0 and 1.0 bounds (:44)
   - unsupported schema versions, including nested ones (:56)
   - malformed stable IDs (:69, :82)
   - non-canonical pairs and self-pairs (:96)
   - start year after end year (:121)
   - ConfigLoaderSpec.scala:108 covers the same window rule for configuration.
   - My count of the test cases in the source is 109 + 12 + 15 + 12 = 148. That matches the supplied `sbt check` total of 148.
4. Pair rule and time windows are correct: PASS.
   - CanonicalPair (StableIds.scala:13-20) puts the smaller ID first by `String.compareTo` and refuses self-pairs.
   - IdFormat limits stable IDs to visible ASCII, so this order equals Spark's binary string order.
   - CoauthorEdge (Validation.scala:134-160) checks:
     - pair order;
     - the window;
     - that the collaboration years are ordered and lie inside the window;
     - that edgeId matches its formula.
   - `Validation.window` (`start <= end`) allows single-year windows, which is correct for inclusive windows.
   - Config windows are checked at ConfigLoader.scala:235-240.
5. No hardcoded configuration: PASS.
   - Buckets, the Neptune endpoint, the account ID, model IDs, thresholds, windows, and bridge parameters live only in scholarchain.conf, as fake `example-*`, `.invalid`, and `000000000000` placeholders, plus 17 SCHOLARCHAIN_* overrides.
   - The scripts take every endpoint from subset.v1.json.
   - The loader rejects placeholders outside "local".
6. core has no Spark or Hadoop dependency: PASS. build.sbt:39-40 adds only upickle and typesafeConfig, and nothing in core imports Spark or Hadoop. The only hits for those words are comments.
7. Manifest: PASS.
   - All 17 fields the spec requires are present for each of the four datasets, and validate.sh checks 27 keys.
   - Every license and access-rule claim cites a URL and an access date (2026-11-02 or 2026-11-03), or is marked UNVERIFIED (DBLP rate limit, OpenAlex polite pool, Crossref license name, ORCID redistribution).
   - I found no invented license or identifier. CC0 for dblp and OpenAlex, the Crossref reuse statement, the ORCID ToS and CC0 Public Data File, and the fictional iD 0000-0002-1825-0097 all agree with my independent knowledge.
   - I cannot re-fetch provider pages to confirm the current numeric limits.
8. No full download without an explicit flag: PASS.
   - `parse_mode` (lib.sh:53-64) defaults to sample.
   - SAMPLE mode in fetch_dblp.sh uses a ranged fetch with `--max-filesize` (line 68) and requires HTTP 206.
   - Each `--full` run logs a PLAN line (request count and size estimate) before it fetches.
9. Scripts write only under scratch/datasets/: PASS, with one by-design exception that is not a finding. build_samples.sh writes datasets/samples/**, which the dataset-engineer owns. That is the sample builder required by work order 1, item 3. Every fetcher writes only under `$SCRATCH` (= scratch/datasets, git-ignored by .gitignore:27), and validate.sh writes nothing.
10. No secret, API key, or personal email: PASS.
    - The regex `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` finds 0 matches in datasets/ and modules/core/.
    - Neither build.sbt nor Dependencies.scala contains an email address, mailto, or the user's name.
    - The only key-related strings are the documentation text "api_key parameter or Authorization: Bearer header" and the log line `api_key=set|unset`.
11. No real ORCID record: PASS.
    - datasets/samples/orcid/ holds only orcid-fictional-test-record.jsonl (Josiah Carberry, 0000-0002-1825-0097) and synthetic-orcid-records.jsonl (3 records with `synthetic:` IDs whose DOIs all appear in dblp/dois.txt).
    - validate.sh:129-168 enforces this rule.
12. Every sample file is under 200 KB: PASS. The largest supplied size is 99,797 B, and validate.sh enforces a stricter limit of 200,000 B.
13. Typesafe Config pin and scala-library override: PASS.
    - Pin: `com.typesafe % config % 1.4.9`, a plain Java artifact with no Scala suffix, in Compile scope on core only, with the date "2026-06-03" in a comment, as D-09 requires.
    - Override: `dependencyOverrides += org.scala-lang % scala-library % 2.13.16` in commonSettings, which every module and `tests` use. This matches D-01 and D-02 (EMR Scala 2.13.16).
    - JSON stays uPickle only and tests stay ScalaTest only (D-05). D-10 is honored: four datasets, three windows, and three bridge measures.

OWNERSHIP CHECK:
- build.sbt -> build-engineer -> author build-engineer: yes.
- project/Dependencies.scala -> build-engineer (structure; new entries) -> build-engineer: yes. These are new pins the human ordered in work order 2, not bumps of existing values, so exception 1 does not apply.
- datasets/** (manifest, config, schemas, scripts, samples) -> dataset-engineer -> dataset-engineer: yes.
- modules/core/** -> domain-engineer -> domain-engineer: yes. No file was created under root config/**.
- docs/agent-runs/phase-03/agent-report.md, repair-01.md, repair-02.md -> orchestrator assembly (permitted report names in an open phase folder): yes. Not under review.

FLOOR AND FENCE CHECK:
- No change to .claude/**, settings, hooks, CLAUDE.md, docs/agents.md, or .mcp.json.
- The diff leaves `markTestRun` and the `check` alias in build.sbt untouched.
- No widening and no weakening.

EVIDENCE GAPS (claims I could not observe; none blocks ratification):
- The 2026-06-03 publication date for Typesafe Config 1.4.9 is a build-engineer claim from Maven Central headers. The artifact's existence is supported by the supplied `sbt check` success, which had to resolve it. I cannot verify the date.
- validate.sh results: repair-01 reports PASS=56 and repair-02 reports "46 PASS lines". The two counts agree. By my count from the source, the embedded Python block prints 46 PASS lines on stdout, and bash's `log PASS` prints 10 more on stderr, for 56 in total. The report wording is ambiguous, but the numbers are consistent. I cannot run the script.
- Provider rate limits, prices, and terms (OpenAlex budget and key rules, Crossref pools, ORCID 12/40/25,000) are cited with URLs and dates. I cannot re-fetch them.
- The build-engineer's per-project classpath exports for the scala-library override are claims. The build definition matches them.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Only Read, Grep, and Glob.

TEST RESULTS (as supplied by the orchestrator, quoted):
"[info] Total number of tests run: 148 / [info] Suites: completed 4, aborted 0 / [info] Tests: succeeded 148, failed 0, canceled 0, ignored 0, pending 0 / [info] All tests passed. / [info] markTestRun: tests passed, wrote .../.claude/.last-test-run / [success]"

VALIDATION RESULTS:
- Orchestrator-supplied `wc -c`: every sample is under 204,800 B. The largest is openalex/works-sample.jsonl at 99,797 B.
- Orchestrator-supplied university-email grep: 0 matches. My own generic email regex sweep: 0 matches.
- validate.sh exit 0 is a claim (see EVIDENCE GAPS).

ASSUMPTIONS:
- Per the work order, the human-approved items are excluded from findings. That covers the single agent-report.md, the ORCID handling, OPENALEX_API_KEY in the environment, the 111,417-work subset, the record deviations, the self-frozen wire examples, IDs that change between runs, PROVISIONAL values, and the contact email visible to `ps`.
- build_samples.sh writing datasets/samples/** is the intended way to produce work order 1's samples, not a breach of "scripts write only under scratch/datasets/".

RISKS (non-blocking MINOR observations, listed per the phase stopping rule as candidate residual risks; none is a ratification blocker):
1. ORCID daily-cap accounting can pass the documented quota when a run crosses midnight UTC (owner: dataset-engineer).
   - Where: datasets/scripts/fetch_orcid.sh:77 and :85.
   - Cause: `COUNT_FILE` is named once, from the start date. The die message tells the human to "re-run after 00:00 UTC".
   - Example: a full run starts at 23:30Z and makes 24,000 requests by about 01:10Z the next day. An immediate rerun starts a fresh counter for that day, so that UTC day can reach about 42,000 anonymous reads against the documented 25,000. The likely result is a temporary ORCID block, and the run ends with exit 5. No data is corrupted, because the run resumes.
2. AuthorshipRecord accepts a mention ID with position 0 or a negative position (owner: domain-engineer).
   - Where: modules/core/src/main/scala/scholarchain/core/Validation.scala:103-112. Unlike AuthorMention (:72), it neither checks `position >= 1` nor calls `IdFormat.isMentionId`.
   - Example: `authorship.copy(position = 0, mentionId = workId + "#0")` validates as Right.
   - Related gaps: negative `crossCommunityDegree`, `communitiesConnected`, or `coreness` is accepted (:162-180), and directed GraphEdge endpoints and EvidenceItem.graphPath are checked only as visible ASCII.
3. The configuration defaults to `application.environment = "local"`, which disables the placeholder guard (owner: domain-engineer).
   - Where: modules/core/src/main/resources/scholarchain.conf:19 and ConfigLoader.scala:266.
   - Effect: a deployed job that forgets SCHOLARCHAIN_ENVIRONMENT loads quietly with the `example-scholarchain-*` bucket names. S3 bucket names are global, so those names could belong to a third party.
   - Mitigation: Phase 12 Terraform and task definitions must always set SCHOLARCHAIN_ENVIRONMENT.
4. Run ID uniqueness depends on the caller incrementing `attempt` (owner: domain-engineer for the contract; downstream stage owners for correct use).
   - Where: StableIds.scala:167-183.
   - Effect: two runs with the same stage, code, config, inputs, and attempt get the same `run-` ID, so their run_id= outputs collide.
5. Typesafe Config ships unrelocated in the hadoop job jar, and its presence on the EMR classpath is unverified. This is already in the build-engineer's report (owner: build-engineer, checked at the first EMR run).

MANUAL ACTION REQUIRED:
- Human gate: accept or route RISKS 1 to 4 as residual risks or as Phase 4+ work orders.
- Confirm that the human-gate record covers the frozen wire examples and the PROVISIONAL values.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: findings routed to owners, then re-review; human gate on APPROVE

APPROVE
````
