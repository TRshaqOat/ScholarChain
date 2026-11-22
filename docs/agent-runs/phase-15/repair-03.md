STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 15+16, session 12, repair-03 (docs/agent-runs/phase-15/prompt.md, "Repair-03 work order (documentation-engineer): code review 5 findings in docs/evaluation.md"; rulings 16, 17, 18; review-report.md "Code review 5"). Ponytail ON.

FILES CREATED: none
FILES MODIFIED: /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/evaluation.md
FILES DELETED: none

LINE COUNT: before 570, after 600 (both from the Read tool's numbering).

CHANGES BY FINDING (line numbers are in the repaired file)

- **C5-M1 (major)**
  - Section 4.1, lines 214-234 (new). Two tables built from the three summary-010.json files:
    - Verification counts with the contradiction rate as conflicting over model claims: fused 14/0/8/0, so 0/22; graph-only 14/0/2/0, so 0/16; vector-only 0/0/4/7, so 7/11.
    - Checks as pass / fail / n/a for abstention, pair-edges, claim-evidence, path-hops and plan-operations.
  - The named failures, quoted from the summaries' `failures` lists: B08 plan-operations in every mode; B08 path-hops in fused and graph-only; nine vector-only abstention failures (B01 to B08 and B10).
  - Also added in 4.1:
    - B09 passed the abstention check in every mode.
    - The other seven checks record 0 failures in every mode.
    - No check in the summaries is named for supporting-publication validity.
    - Vector-only's abstention failures follow from the verifier design (ruling 6; manifest `expectedFinding`).
    - A comparison against exp-e-summary.csv (no count differs).
  - Sources: the summary-010.json of each mode (run IDs from ruling 18 point 2) and `raw/<mode>/modes-stdout.txt`; admissible by ruling 18 point 2.
  - Section 4.3, lines 259-261: three hedged lines, on the 7/11 contradiction rate, the repeated B08 failure, and the abstention checks.
  - Section 3.2, line 158: names B09 as the unscored question, quoting modes-stdout.txt (fused line 20; graph-only and vector-only line 22).
  - Missing data:
    - Line 586: the E contradiction-rate and validity row is corrected to "measured (section 4.1)".
    - Line 587: new row for abstention quality.
- **C5-m1:** Section 5.7, line 388: window 2 start is now "about 20:30 UTC, ruling 18 point 4". Line 389: window 3 start is now "about 17:30 UTC, ruling 17 point 2".
- **C5-m2:**
  - Section 7.1, lines 520-521: I removed "its rebuild is not in that file" and added a bullet citing `docs/agent-runs/phase-15/evidence/gate/regenerate-labelled.txt` (at cdb9578, `//raw` argument, committed key copy, exit 0, every file byte-identical; ruling 17 point 3).
  - Missing data line 598: the regeneration row now cites both regenerate files.
- **C5-m3:**
  - Section 4.2, line 251: new bullet giving the scope of the departure. It is limited to vector-only, where 9 of the 10 abstaining answers were rated above 1. The fused and graph-only abstaining answers were rated usefulness 1. Source: ruling 18 point 3.
  - The abstention-departure threat at line 552 gets the same sentence.
- **C5-m4:** The reason precision at K is not reported is corrected at section 3.3 line 173 and Missing data line 580. The summarizer computes only recall at K and MRR (work order 4 A3). Binary labels would support it, and the bundle cap would also bound it. nDCG stays not reported for lack of graded labels. Sources: prompt.md work order 4 A3; ruling 6.
- **C5-m5:**
  - Section 5.8, line 396: the bottleneck claim is reworded as "consistent with … but do not prove it". It cites the Neptune CPU ranges already in 5.3 (80.4 to 93.0) and 5.4 (97.0 to 98.7) and names the client and network limits.
  - Section 7.2, line 530: the full Spark pipeline on EMR "did not complete beyond 25 percent at this cluster size with the settings tried (16 and 40 shuffle partitions; P14-D8)". Option B (Spark executor cores 2) is untested and deferred. Source: Phase 14 rulings 21 (line 208) and 22 (lines 216, 223).
- **C5-m6:** Line 17 now reads "8 fraction records (failed ones included; one without run.json)". Source: size-scaling.csv rows 2-9; row 2 has no run.json.
- **C5-m7:** Line 30 reads "Researchers (260 rows: 259 decided, 1 UNSURE)" and line 49 reads "Works (50 rows: 45 decided, 5 UNSURE)". Source: eval-er-v1/metrics.json (researchers n 259, unsure 1, confusion rows 150 + 109 + 1; works n 45, unsure 5, confusion rows 37 + 8 + 5). eval-er-v2/metrics.json gives the same n and unsure.
- **C5-m8:** Threats, line 551: new "Rater independence" bullet. The single labeller and rater is also the system's architect and the benchmark's reviewer, which together with ruling 12(h) is a possible rater bias. Sources: `docs/agents.md` (human architect) and `phase-06/human-gate.md`.
- **C5-m9:** Line 468 now reads "Phase 15 18 (as of repair-03)". Source: prompt.md has rulings up to 18.
- **C5-m10:** Missing data line 578 now says definitively that no validator output for an EMR run is committed. It adds that the EMR Spark runs wrote integrity-violation tables whose row counts are not committed, citing `docs/agent-runs/phase-14/evidence/window-1/p14-s3-run-*.txt`. Source: review-report.md C5-m10.
- **Item 12:**
  - Section 5.7, line 390: states the list-price basis of about 0.28 USD (about 177k input and 21k output tokens; Haiku 4.5 at 1 USD per million input and 5 USD per million output; Titan negligible). Sources: ruling 16 point 5 and ruling 18 point 4, the same basis as the manifest `budgetNote`.
  - Missing data line 599: new row stating that no plots are produced; the derived tables are the only regenerated outputs.

COUNTS THAT DIFFER BETWEEN THE RAW SUMMARIES AND THE DERIVED TABLES: none.
- Checked against experiments/derived/exp-e/exp-e-summary.csv:
  - questions, answered, abstained by reason;
  - verified 14/14/0 and partially supported 0/0/0;
  - removed 8/2/11, against unsupported + conflicting 8+0, 2+0, 4+7;
  - latency p50, p95 and max; LLM attempts and requests 19; input and output tokens; embedding calls 9/0/9;
  - retrieved items 86/62/37, against graph evidenceItems + semantic hits: 62+24, 62+0, 0+37.

COMMANDS RUN: none. I used only Read and Edit. No Bash, git, aws, terraform, sbt, python, Bedrock or MCP calls.
COMMANDS REPLAYED: none. The documented commands in 7.1 are python runs, which this work order forbids. The 7.1 results come from the recorded files.
TEST RESULTS: not applicable. No Scala or sbt source changed.
VALIDATION RESULTS:
- I read back the repaired sections (lines 150-269 and 380-600) after editing.
- Every new number was compared with its source file during this session: the three summary-010.json files, the three modes-stdout.txt files, exp-e-summary.csv, size-scaling.csv, both metrics.json files, regenerate-labelled.txt, the manifest, and Phase 14 prompt.md rulings 21 and 22.
- Nothing under labels/, scratch/, secrets/ or any e-labelling path was opened.
- No new text addresses a grader or an evaluator.

EVIDENCE LINKS:
- E verification counts and checks -> experiments/results/exp-e-retrieval-modes-v1/20261120T093705Z/raw/{fused,graph-only,vector-only}/s3/reports/rag-benchmark/snapshot=2026-10-03/run=<id>/summary-010.json
- B09 unscored -> raw/<mode>/modes-stdout.txt
- Labelled rebuild -> docs/agent-runs/phase-15/evidence/gate/regenerate-labelled.txt
- Gold row counts -> docs/agent-runs/phase-05/evidence/eval-er-v1/metrics.json and eval-er-v2/metrics.json
- Fraction records -> experiments/derived/size-scaling/size-scaling.csv
- Option B -> docs/agent-runs/phase-14/prompt.md rulings 21 and 22
- Window times, abstention-rating scope and list price -> rulings 17.2, 18.3 and 18.4

LINK CHECK:
- Every newly cited repository path was opened with Read in this session except two:
  - the wildcard `docs/agent-runs/phase-14/evidence/window-1/p14-s3-run-*.txt`, which I could not list without Glob; it rests on code review 5's Glob;
  - `docs/agents.md`, which was provided in this session's context.
- No external links were added.

UNIMPLEMENTED OR OPTIONAL COMPONENTS STATED: no plots (new Missing data row). No check is named for supporting-publication validity in E. Precision at K is not computed by the summarizer.

DEVIATIONS:
- Grep and Glob were not available as tools in this session, as in sessions 9 and 10. I worked from the paths the work order and the findings name.
- The `p14-s3-run-*.txt` listings and the absence of any committed EMR validator output (C5-m10) were not checked independently. They rest on code review 5's Glob result.
- The "no plots" row rests on the work order and code review 5. I did not list `experiments/derived/` myself.

FINDINGS NOT FIXED: none. All of C5-M1, C5-m1 to C5-m10 and item 12 were applied.

ASSUMPTIONS:
- The contradiction-rate denominator is model claims (verified + partially supported + unsupported + conflicting), matching the file's existing 0/99 for Phase 11.
- The Status line, the Rules section and line 78 ("259 researcher rows") were left as written because the work order does not name them.

RISKS:
- 4.3 line 261 states vector-only's abstention failures as the designed consequence, not hedged, because the work order directs that statement (ruling 6).
- The cited wildcard path `p14-s3-run-*.txt` is not a single resolvable file name.

MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code review 6 (session 13, code-reviewer, Ponytail OFF), limited to the repaired findings and a check that nothing else changed (ruling 18 point 5); then the human gate.
