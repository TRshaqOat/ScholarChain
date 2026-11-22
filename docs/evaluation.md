# ScholarChain evaluation (spec Phase 16, groups 1 to 7)

Status: complete (Phase 15+16 work order 5, parts A and B). Part B added the human-labelled retrieval quality and answer ratings of experiment E (sections 3.2 and 4.2, ruling 16) and the settled costs (section 5.7, ruling 16 point 5).

## Rules used in this document

- Every number names the committed file it comes from, plus the ruling where a ruling records it. "Phase N ruling M" means `docs/agent-runs/phase-NN/prompt.md`, ruling M. "Ruling M" alone means Phase 15 (`docs/agent-runs/phase-15/prompt.md`).
- Each group separates **Measured** (facts with sources) from **Interpretation**.
- The derived tables under `experiments/derived/` are the inputs and are not recomputed here. A ratio written here always shows both numbers (for example 12/99).
- Raw results under `experiments/results/` are frozen (ruling 1; E committed at 544a310, ruling 15 point 2).
- Repetition: every experiment is a single run except D, which had two attempts (only the second completed its levels). Variability was therefore not measured for any experiment, and no confidence interval is reported.
- Failed runs, out-of-memory runs, abstentions and removed claims are kept.

| Experiment | What ran | Runs | Source |
|---|---|---|---|
| A | 25 percent EMR pipeline run at deef295 | 1 | `experiments/derived/exp-a/README.md`, Phase 14 rulings 21, 22 |
| B | replaced by size scaling (D-13) | 8 fraction records (failed ones included; one without run.json) | `experiments/derived/size-scaling/README.md` |
| C | deferred (D-10) | 0 | ruling 1 |
| D | query load, window 2 | 2 attempts (1 preflight stop, 1 complete) | `experiments/derived/exp-d/README.md`, Phase 14 rulings 46, 47 |
| D-scale | one c=8 level plus scaling, window 3 | 1 | `experiments/derived/exp-d-scale/README.md`, ruling 10(e) |
| E | retrieval modes, local runtime, live Bedrock | 1 | `experiments/derived/exp-e/exp-e-summary.md`, ruling 14 |
| F | poison message and idempotent replay, window 2 | 1 | `experiments/derived/exp-f/README.md`, Phase 14 ruling 46 |

## 1. Entity resolution

Source: the Phase 5 gold evaluation on 310 human labels (304 decided, 6 UNSURE), `docs/agent-runs/phase-05/evidence/eval-er-v1/metrics.json` and `eval-er-v2/metrics.json`, summarised in `docs/agent-runs/phase-05/human-gate.md`. Thresholds: accept 0.93, reject 0.35 (same gate).

### Measured

Researchers (260 rows: 259 decided, 1 UNSURE). er-v1 and er-v2 give identical figures (both metrics.json files; gate: "No queued row touches the repair-02 ORCID groups").

| Path | n | TP | FP | FN | TN | Precision | Recall | F1 | Coverage | Unresolved rate |
|---|---|---|---|---|---|---|---|---|---|---|
| overall | 259 | 90 | 1 | 60 | 108 | 0.989 | 0.600 | 0.747 | 0.703 | 0.297 |
| EXACT_ID | 91 | 39 | 0 | 3 | 49 | 1.000 | 0.929 | 0.963 | 1.000 | 0.000 |
| DETERMINISTIC | 13 | 13 | 0 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 | 0.000 |
| PROBABILISTIC | 116 | 38 | 1 | 26 | 51 | 0.974 | 0.594 | 0.738 | 0.672 | 0.328 |
| LLM_BAND | 39 | 0 | 0 | 31 | 8 | undefined | 0.000 | undefined | 0.000 | 1.000 |
| LLM | 0 | - | - | - | - | - | - | - | - | - |

Researcher confusion matrix (label by decision, overall):

| Label | ACCEPTED | CONFLICT | REJECTED | UNRESOLVED |
|---|---|---|---|---|
| MATCH | 90 | 1 | 5 | 54 |
| NON_MATCH | 1 | 12 | 73 | 23 |
| UNSURE | 0 | 0 | 0 | 1 |

Works (50 rows: 45 decided, 5 UNSURE):

| Version | Path | n | TP | FP | FN | TN | Precision | Recall | F1 | Coverage | Unresolved rate |
|---|---|---|---|---|---|---|---|---|---|---|---|
| er-v1 | overall | 45 | 19 | 0 | 18 | 8 | 1.000 | 0.514 | 0.679 | 0.756 | 0.244 |
| er-v1 | EXACT_ID | 18 | 6 | 0 | 7 | 5 | 1.000 | 0.462 | 0.632 | 1.000 | 0.000 |
| er-v1 | DETERMINISTIC | 7 | 7 | 0 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 | 0.000 |
| er-v1 | PROBABILISTIC | 14 | 6 | 0 | 5 | 3 | 1.000 | 0.545 | 0.706 | 0.643 | 0.357 |
| er-v1 | LLM_BAND | 6 | 0 | 0 | 6 | 0 | undefined | 0.000 | undefined | 0.000 | 1.000 |
| er-v2 | overall | 45 | 22 | 0 | 15 | 8 | 1.000 | 0.595 | 0.746 | 0.822 | 0.178 |
| er-v2 | EXACT_ID | 18 | 6 | 0 | 7 | 5 | 1.000 | 0.462 | 0.632 | 1.000 | 0.000 |
| er-v2 | DETERMINISTIC | 14 | 14 | 0 | 0 | 0 | 1.000 | 1.000 | 1.000 | 1.000 | 0.000 |
| er-v2 | PROBABILISTIC | 10 | 2 | 0 | 5 | 3 | 1.000 | 0.286 | 0.444 | 0.500 | 0.500 |
| er-v2 | LLM_BAND | 3 | 0 | 0 | 3 | 0 | undefined | 0.000 | undefined | 0.000 | 1.000 |

Work confusion matrix, MATCH row: er-v1 ACCEPTED 19, CONFLICT 6, REJECTED 1, UNRESOLVED 11; er-v2 ACCEPTED 22, CONFLICT 6, REJECTED 1, UNRESOLVED 8. NON_MATCH: REJECTED 8 in both. UNSURE: REJECTED 4, UNRESOLVED 1 in both.

Further facts from `docs/agent-runs/phase-05/human-gate.md`:

- 54 of the 60 researcher false negatives are UNRESOLVED (LLM band or below the accept threshold), 5 REJECTED, 1 CONFLICT.
- All 6 sampled work EXACT_ID CONFLICT rows were labelled MATCH (duplicate records of one paper).
- Overall figures are sample-weighted (about 39 rows per stratum); the per-path figures are the primary view.
- Threshold sensitivity (er-v1, researchers): accept 0.93 gives precision 0.989 and recall 0.600; 0.70 gives 0.945 and 0.800; 0.50 gives 0.853 and 0.853. Work precision is 1.000 at every accept threshold, with recall from 0.514 to 0.757. Thresholds were not changed from gold results.
- No live LLM adjudication was part of the gold evaluation; the LLM path has 0 rows.

Downstream of the evaluated decisions (not gold-evaluated): Phase 6 refused 5,110 researcher clusters, and tier-2 refusals removed the probabilistic links of 149,056 mentions (`docs/agent-runs/phase-06/human-gate.md`). The Phase 9 live adjudication demonstration on 200 LLM-band pairs (RESEARCHER 33 ACCEPTED, 81 REJECTED, 66 UNRESOLVED) was not evaluated against gold labels (`docs/agent-runs/phase-09/human-gate.md`).

### Interpretation

- Precision is high on every decided path; only one false positive was observed in 259 researcher rows. With so few errors and about 39 rows per stratum, the precision estimates have wide uncertainty (the Phase 5 gate states the same).
- Recall is limited by deferral, not by wrong merges: most missed researcher matches sit in the LLM band, which stayed unresolved because no adjudication ran in the evaluated decisions. This is the designed abstention behaviour, with a recall cost.
- er-v2 raised work recall from 19/37 to 22/37 by moving pairs into the deterministic path, without a false positive. Researcher results did not change.
- The gold set measures the J10 decisions, not the canonical graph after the Phase 6 refusal rules. Those refusals trade more recall for precision, so the served graph's identity recall is likely lower than measured here. This is not measured.

## 2. Graph correctness

### Measured

Test suite. `docs/agent-runs/phase-15/evidence/gate/check-sh-wo4.txt` (check.sh on 48e1864, ruling 14 point 8): "==> OK check: every gate passed"; the eight per-project totals (191, 141, 122, 115, 111, 70, 49, 46 succeeded) sum to 845 passed, 0 failed, and 10 pending (all in the 141-test, 20-suite project). Known-graph and integrity tests named in that file:

| Suite (from the test output) | What it checks |
|---|---|
| SparkKnownGraphSpec | components equal union-find on known and seeded graphs; k-core coreness on clique, cycle, star, tree, Petersen and clique-with-tail graphs; label propagation equals hand-worked labels; hand-computed bridge measures; window summaries of known graphs |
| ConnectedComponentsSpec, ResearcherAnalyticsSpec | min-label components; job 3 metrics on a five-node graph; job 8 k-core on a 4-clique attached to a path; a level over the round cap fails the run |
| GraphReadKnownGraphSpec | shortest-path lengths and hop order; UNREACHABLE and UNKNOWN_RESEARCHER; distinct versus weighted degree; an edge whose supporting-work count disagrees with its joint count is refused |
| CoauthorGraphEndpointAdversarialSpec | unresolved endpoints and missing works are violations, never edges; self-pairs never become self-edges; duplicated rows never inflate a joint count |
| NeptuneSpec | coauthor edges need existing endpoints of the right label; inline supporting works must be Work vertices both endpoints AUTHORED; evidence must match the joint count; duplicates are reported |
| CanonicalGraphJobSpec | refusal tiers, self-pair drop and count, per-window aggregation, provenance on every table, identical tables on rerun |

Spark run (local, Phase 6, `docs/agent-runs/phase-06/human-gate.md`):

| Window | Publications | Researchers in graph | Coauthor edges | Components | Communities | Largest component share |
|---|---|---|---|---|---|---|
| all | 110,257 | 191,318 | 787,726 | 16,091 | 38,034 | 74% |
| 2000-2014 | 45,522 | 66,350 | 217,957 | 7,709 | 15,312 | 69% |
| 2015-2026 | 64,735 | 139,432 | 585,071 | 10,746 | 26,394 | 71% |

- Integrity violations: canonical 0, community-path 0, summary-export 0, analytics 21 (m6: transitive work clustering merges two DBLP records in 5 works; residual).
- Self-pairs dropped: 95.
- Label propagation did not converge within 10 rounds in any window (387 nodes still changing in round 10 in window all).
- Path sample (50 seeded pairs): 37 found, 13 beyond depth 6, 0 unreachable.
- Determinism: the repair run matched the work order 5 run on the row counts of all 30 tables, key checksums per window, and full-row hashes.

Store validation (Phase 7, `docs/agent-runs/phase-07/human-gate.md`):

- ValidateCli on the store conversion run-1d6a86491812ff94887270c1ab885e69: 3,635,231 elements, 0 violations.
- Coauthor-edge support: 1,590,754/1,590,754 COAUTHORED_WITH edges traced to supporting works (1,590,180 inline plus 574 through evidence objects), 2,038,078 work IDs; EVIDENCE_WORK_MISSING, EVIDENCE_NOT_AUTHORED, EVIDENCE_OBJECT_ORDER and EVIDENCE_OBJECT_PROVENANCE all 0.
- Neptune identifiers equal Spark identifiers; 202 path hops match Spark evidence. The Phase 9 bidirectional search reproduced all 54 stored path results (`docs/agent-runs/phase-09/human-gate.md`).

Cloud load (window 3): Neptune load 6113dd40 LOAD_COMPLETED, 34,052,689 records, duplicates 0, parsing, datatype and insert errors 0 (`operations/pipeline-runs/store-load-20261119T190616Z/neptune-status-ac8b0c3cb5fc495696b9ebac8793b0c3.txt`; ruling 10(b)). DynamoDB 876,228 items, 0 conflicts; S3 Vectors 13,023 written, 0 rejected (ruling 10(b)). The window 2 and window 3 smoke queries cite the same evidence ID ev-927665f3 (ruling 10(d)).

Window summaries: the Phase 10 window-summary run run-1de2293597c338227290eb382de16957 (load run run-6fbad498beb0d8f570df67665fb97544) is served for corpus questions (`docs/agent-runs/phase-10/human-gate.md`); CorpusWindowSummarySpec checks member sums and every shared value against the Phase 6 window summary (test output in check-sh-wo4.txt).

### Interpretation

- Referential integrity and edge support hold by validator rule on the full store conversion: 0 violations and every coauthor edge traced to works both endpoints authored.
- Duplicate and orphan figures are counts of validator-rule violations (0) and loader duplicates (0), not sampled rates.
- The algorithms are tested on hand-checkable graphs; on the real graph, label propagation stops at its round cap without converging, so community assignments are a capped result, as `modules/spark/JOBS.md` states (cited by the Phase 6 gate).
- The 21 analytics integrity violations (m6) are a known identity-policy residual.

## 3. Retrieval (experiment E)

Sources: `experiments/derived/exp-e/exp-e-summary.md` and `.csv` (tool-generated, ruling 14 point 7), manifest `experiments/manifests/exp-e-retrieval-modes-v1.json`, ruling 14.

### 3.1 Measured

Run exp-e-retrieval-modes-v1, stamp 20261120T093705Z, commit 48e1864; run-experiment.sh exit 0, harness exit codes [0,0,0,0]; total 10 min 36 s against plannedMinutes 20 and maxMinutes 135 (ruling 14 points 1, 2, 5). The same 10 question IDs (B01 to B10) in every mode (exp-e-summary.csv; ruling 14 point 4). Model: Haiku 4.5 for planning and synthesis, Titan V2 for embeddings (manifest).

| Mode | Questions | Answered | Abstained (by reason) | Retrieved items (mean per question) | Latency p50 / p95 / max (ms) | LLM attempts | Bedrock requests | Input / output tokens | Embedding calls |
|---|---|---|---|---|---|---|---|---|---|
| fused | 10 | 9 | 1 (AMBIGUOUS_RESEARCHER 1) | 86 (8.6) | 14,663 / 19,644 / 19,644 | 19 | 19 | 65,072 / 8,255 | 9 |
| graph-only | 10 | 9 | 1 (AMBIGUOUS_RESEARCHER 1) | 62 (6.2) | 14,945 / 20,300 / 20,300 | 19 | 19 | 57,030 / 6,705 | 0 |
| vector-only | 10 | 0 | 10 (AMBIGUOUS_RESEARCHER 1; NO_VERIFIED_CLAIM 9) | 37 (3.7) | 14,719 / 19,435 / 19,435 | 19 | 19 | 54,989 / 5,889 | 9 |

Earlier retrieval evidence (not relevance-labelled, context only):

- Phase 7 live retrieval smoke on real Titan vectors (self-retrieval): profile-self@1 49/50, community-self@5 50/50, path-self@1 50/50, topic-self@1 50/50, paper-self@1 49/50, profile-community-filter 50/50 (`docs/agent-runs/phase-07/human-gate.md`).
- Phase 9 live benchmark: graph retrieval p95 252 ms, semantic retrieval p95 1.7 s (`docs/agent-runs/phase-09/human-gate.md`).

### 3.2 Retrieval quality: recall at 5 and 10, MRR

Source: `experiments/derived/exp-e-labelled/exp-e-summary.md` and `exp-e-quality.csv` (tool-generated from the human's labels, ruling 14 point 7, ruling 16 point 2).

| Mode | Recall at 5 | Recall at 10 | MRR | Questions scored | Without retrieved items | Incompletely labelled | Without a relevant item | Mean list length |
|---|---|---|---|---|---|---|---|---|
| fused | 0.787 | 0.8241 | 0.4926 | 9 | 1 | 0 | 0 | 8.6 |
| graph-only | 0.5648 | 0.6019 | 0.4556 | 9 | 1 | 0 | 0 | 6.2 |
| vector-only | 0.3704 | 0.3704 | 0.6111 | 9 | 1 | 0 | 0 | 3.7 |

- Unscored: in each mode, 1 of the 10 questions has no retrieved items and is not scored. The labelled summary does not name it; the benchmark output does: B09, "abstained AMBIGUOUS_RESEARCHER; 0 verified; graph 572 reads, 0 items; semantic 0 hits" in every mode (`experiments/results/exp-e-retrieval-modes-v1/20261120T093705Z/raw/<mode>/modes-stdout.txt`, fused line 20, graph-only and vector-only line 22; ruling 18 point 2). No question was excluded for differing question sets (`exp-e-quality.csv`, questions_excluded empty).
- Mean list length is `retrieved_items_mean` in `experiments/derived/exp-e-labelled/exp-e-summary.csv`.
- Labels: one human labelled all 101 pooled retrieved items of the three modes, binary (1 or 0), in `labels/relevance-e-v1.csv`; counts-only check 101 rows, 0 blank, 0 invalid (ruling 16 point 1). Relevance was judged only on these pooled items, so recall is relative to what the three modes retrieved, not to all relevant evidence in the stores.
- Blinding: the relevance sheet showed items by content, without mode, rank or score (work order 4 A4). Only the summarizer read the keys, after both label files were complete (ruling 16 point 4). The one known limit is on the answers sheet (ruling 12(h)).
- The values were entered with a display-only labeller kept outside the repository, built with Claude in a web chat on Nov 20; it shows the sheets as written and never suggests a label or score (ruling 16 point 1).
- Recall at K uses the first min(K, list length) items of each mode's ranked list, the post-budget evidence bundle, so recall at K is capped at the bundle size (ruling 6, m5; `exp-e-summary.md`).

### 3.3 Limits

- 10 questions and one run; variability not measured.
- The ranked list is the post-budget evidence bundle, so recall at K is capped at the bundle size (ruling 6, m5; `exp-e-summary.md`).
- The vector side is the file-backed export of the live vectors, searched by local exact search, not the S3 Vectors service, whose approximate ranking may differ (Phase 14 ruling 31; manifest `limits.vectors`).
- E ran the local runtime against live Bedrock; no deployed service, Neptune, DynamoDB or S3 Vectors call (manifest `limits.runtime`).
- VECTOR_ONLY abstains with NO_VERIFIED_CLAIM by design: the verifier requires graph evidence for every material claim (ruling 6; manifest `expectedFinding`).
- Blinding limit of the answers sheet: vector-only abstentions are recognisable (ruling 12(h)).
- Precision at K is not reported: the summarizer computes only recall at K and MRR (work order 4 A3). The binary labels would support it, and the bundle cap would also bound it. nDCG is not reported: there are no graded relevance labels.
- No OpenSearch comparison (D-06).
- Live model output is not guaranteed identical across runs (manifest `context.limitations`).

### 3.4 Interpretation

- Graph-only answered the same 9/10 questions as fused, with 24 fewer retrieved items in total (62 against 86), no embedding call, and about 8,000 fewer input tokens (57,030 against 65,072).
- Vector-only answered none. This was stated before the run as the expected finding, and it follows from the verifier's design, not from a retrieval failure that the run measured.
- Mode does not change latency much: p50 values sit within 300 ms of each other, so model calls dominate the question time.
- Fused had the highest recall at 5 and 10 (0.787 and 0.8241, against 0.5648 and 0.6019 for graph-only), so on these 9 questions fused's lists held relevant items that graph-only's lists did not. The summary does not show which source supplied them.
- Vector-only had the highest MRR (0.6111) and the lowest recall (0.3704 at both 5 and 10). Its first relevant item ranked higher on average, but its short lists (mean 3.7) cover few of the relevant items, and the bundle cap bounds its recall.
- With one run of 10 questions (9 scored per mode) and one labeller, these differences are indicative, not significant.

## 4. Answer quality

### 4.1 Measured

Verifier removal rates in the live benchmarks (30 questions each):

| Run (gate file) | Verifier | Model claims | Verified | Unsupported | Conflicting | Removed | Classification of removals |
|---|---|---|---|---|---|---|---|
| Phase 9 live, run-2f7b6b9ce82a37c0a80d892dc09422f8 (`phase-09/human-gate.md`) | verifier-v3 | 73 | 30 | 40 | 3 | 43/73 | 29 by design (mostly 21 ranking claims without rank.k.researcherId), 14 verifier defects (R3-1 11, R3-2 2, n6 1; review 3) |
| Phase 10 final, run-052145faed8cbaed7ae874e3fbf36eae (`phase-10/human-gate.md`) | verifier-v5 | 100 | 85 | 15 | 0 | 15/100 | 12 by design, 3 defects fixed in verifier-v6 (`phase-11/human-gate.md`) |
| Phase 11 gate, run-aad7216cb2b1500996f3403a174cc11d (`phase-11/human-gate.md`) | verifier-v8 | 99 | 87 (0 partial) | 12 | 0 | 12/99 | 12 by design, 0 defects (classified by the human from verifier rule names) |

Outcomes in the same runs:

- Phase 9: ANSWERED 15, NO_VERIFIED_CLAIM 6, BUDGET_EXHAUSTED 3, UNSUPPORTED_REQUEST 4, AMBIGUOUS_RESEARCHER 1, UNKNOWN_RESEARCHER 1.
- Phase 10 final: ANSWERED 24, NO_VERIFIED_CLAIM 3 (`phase-11/human-gate.md`).
- Phase 11: ANSWERED 26, NO_VERIFIED_CLAIM 1 (B15 misplan), AMBIGUOUS_RESEARCHER 1, UNKNOWN_RESEARCHER 1, UNSUPPORTED_REQUEST 1. Benchmark checks: plan-operations fails B15, B18, B23, B24; abstention fails B15; path-hops fails B29.

Fake benchmarks on the same tree: 27 answered, 91 verified, 28 partially supported (context only), 0 unsupported, 0 conflicting, 30/30 checks (`phase-11/human-gate.md`).

Experiment E claims per mode (`experiments/derived/exp-e/exp-e-summary.csv`). "Removed share" is removed divided by (verified + partially supported + removed); verified and partially supported come from the result records and removed from the audit records (`experiments/analysis/summarize-exp-e.py`).

| Mode | Verified | Partially supported | Removed | Removed share | Abstentions |
|---|---|---|---|---|---|
| fused | 14 | 0 | 8 | 8/22 | AMBIGUOUS_RESEARCHER 1 |
| graph-only | 14 | 0 | 2 | 2/16 | AMBIGUOUS_RESEARCHER 1 |
| vector-only | 0 | 0 | 11 | 11/11 | AMBIGUOUS_RESEARCHER 1, NO_VERIFIED_CLAIM 9 |

Experiment E verification counts and benchmark checks, as BenchmarkCli's own summaries record them (`experiments/results/exp-e-retrieval-modes-v1/20261120T093705Z/raw/<mode>/s3/reports/rag-benchmark/snapshot=2026-10-03/run=<id>/summary-010.json`: fused run-25a1816ee6285626bd22bdbcaa862fce, graph-only run-8823c6752422c014191a1a1864ebeb11, vector-only run-9dd2efc0312d094217e0fec704c189db; the same counts are printed in `raw/<mode>/modes-stdout.txt`; admissible by ruling 18 point 2). The contradiction rate is conflicting divided by model claims (verified + partially supported + unsupported + conflicting), as for the live runs above.

| Mode | Verified | Partially supported | Unsupported | Conflicting | Contradiction rate |
|---|---|---|---|---|---|
| fused | 14 | 0 | 8 | 0 | 0/22 |
| graph-only | 14 | 0 | 2 | 0 | 0/16 |
| vector-only | 0 | 0 | 4 | 7 | 7/11 |

| Mode | Abstention (pass / fail / n/a) | Pair-edges | Claim-evidence | Path-hops | Plan-operations |
|---|---|---|---|---|---|
| fused | 10 / 0 / 0 | 3 / 0 / 7 | 9 / 0 / 1 | 1 / 1 / 8 | 9 / 1 / 0 |
| graph-only | 10 / 0 / 0 | 3 / 0 / 7 | 9 / 0 / 1 | 1 / 1 / 8 | 9 / 1 / 0 |
| vector-only | 1 / 9 / 0 | 0 / 0 / 10 | 0 / 0 / 10 | 0 / 0 / 10 | 9 / 1 / 0 |

- Named failures (the `failures` list of each summary):
  - B08 plan-operations in every mode: "needs DIRECT_COAUTHORS/PAIR; plan [SHORTEST_PATH/PAIR]".
  - B08 path-hops in fused and graph-only: "runtime FOUND 1 hops, stored 1 hops, same researchers; no claim cites the path".
  - Vector-only abstention, nine questions (B01 to B08 and B10): "expected answer, got abstain (NO_VERIFIED_CLAIM)". B09 abstained AMBIGUOUS_RESEARCHER and passed the abstention check in every mode (modes-stdout.txt).
- The other checks in the summaries (researcher-names, verification, resolution, embedding-budget, deadline, bundle-budget, llm-budget) record 0 failures in every mode. No check in the summaries is named for supporting-publication validity.
- Vector-only's abstention failures follow from the verifier design: the verifier requires graph evidence for every material claim, and vector-only bundles hold semantic items only (ruling 6; manifest `expectedFinding`).
- Against `experiments/derived/exp-e/exp-e-summary.csv`: verified and partially supported are equal in every mode, and unsupported + conflicting (8 + 0, 2 + 0, 4 + 7) equal its removed claims (8, 2, 11). No count differs.

Residual verifier gaps (`docs/agent-runs/phase-11/human-gate.md`): the verifier is lexical. D18 (major, open, residual under Phase 11 ruling 24): "it is doubtful that X has 10" or "10 more ... than" keeps a claim VERIFIED. D19 and D20 are minor. The pending tests in check-sh-wo4.txt include "a difference from another count, or a disputed or doubted statement of the stored value, is not verified (pending)" and "an approximation idiom before the stored value ... is not verified (pending)".

### 4.2 Human ratings: usefulness and clarity

Source: `experiments/derived/exp-e-labelled/exp-e-summary.md` and `exp-e-quality.csv` (ruling 16 point 2). Scale 1 to 5; one human rated all 30 answers, 10 per mode, in `labels/ratings-e-v1.csv`; counts-only check 30 rows, 0 blank, 0 invalid (ruling 16 point 1).

| Mode | Mean usefulness | Answers rated (usefulness) | Mean clarity | Answers rated (clarity) |
|---|---|---|---|---|
| fused | 4.4 | 10 | 3.6 | 10 |
| graph-only | 4.6 | 10 | 3.6 | 10 |
| vector-only | 1.9 | 10 | 1.9 | 10 |

- Anchors: `docs/agent-runs/phase-15/evidence/gate/e-rating-anchors.txt`, written before rating (ruling 16 point 1). Its abstention rule: an answer that says it cannot answer gets usefulness 1, and its clarity is scored separately.
- All 10 vector-only answers abstained (AMBIGUOUS_RESEARCHER 1, NO_VERIFIED_CLAIM 9; section 3.1).
- Departure (ruling 16 point 3): the rule alone would give vector-only a mean usefulness of 1.0; the measured mean is 1.9, so some abstaining answers were rated above 1. The ratings are kept as entered, because a re-rating after the keys were opened would not be blind.
- Scope of the departure (ruling 18 point 3, a counts-only check by the human): it is limited to vector-only, where 9 of the 10 abstaining answers were rated above 1 and 1 was rated 1. The one abstaining answer in fused and the one in graph-only were each rated usefulness 1, so the fused and graph-only means are not affected by it.
- Blinding limit: the answers sheet may show claim statuses and abstention reasons, which can hint at the mode (ruling 12(h), ruling 16 point 4).

### 4.3 Interpretation

- The removal rate fell from 43/73 (Phase 9) to 12/99 (Phase 11) while verified claims rose from 30 to 87. In Phase 11, every removal was classified as by design. B26 alone loses 9 list-position claims every run.
- Conflicting claims went from 3/73 to 0 in both later runs, so the measured contradiction rate in the final live run is 0/99. That rate counts only contradictions the verifier detects.
- In E, fused removed more claims than graph-only (8 against 2) for the same 14 verified claims. One reading is that semantic context invites claims the verifier cannot bind to graph facts. The run does not test this.
- In E, the verifier marked 7 of vector-only's 11 model claims CONFLICTING (7/11), against 0/22 for fused and 0/16 for graph-only. The summaries do not show which claims conflicted or with what. One possible reading is that, without graph facts in the bundle, the model stated facts from semantic text that disagree with the stored graph; one run of 10 questions cannot show more. As above, the rate counts only contradictions the verifier detects.
- The B08 failures may be one planner error repeated: in every mode the model planned SHORTEST_PATH where the benchmark expects DIRECT_COAUTHORS, and in fused and graph-only the runtime found the stored 1-hop path but no claim cited it. One question in one run cannot show whether this is systematic.
- Fused and graph-only passed every abstention check (10/10), so on these 10 questions they abstained only where the benchmark expects it (B09). Vector-only's 9 abstention failures are the designed consequence of the verifier (ruling 6), not a retrieval failure the run measured.
- The verifier catches numbers, windows, names, titles and hops, not qualitative misreadings (`phase-09/human-gate.md`), and D18 to D20 remain. "Verified" therefore means verified by these rules, not true.
- In E, the two answering modes were rated alike (usefulness 4.4 and 4.6, clarity 3.6 in both). Graph-only's lower recall did not show as lower usefulness on these questions. One run, 10 answers per mode and one rater cannot show more.
- Vector-only's low usefulness (1.9) follows from its abstentions under the anchor rule, and its mean is above what that rule alone gives (1.0; ruling 16 point 3). Its clarity (1.9) was scored separately.

## 5. Performance and elasticity

### 5.1 Experiment A (25 percent EMR run at deef295)

Measured: record `operations/pipeline-runs/20261117T045335Z-fraction25` (Phase 14 rulings 21, 22): 1 primary plus 2 core m6g.xlarge, 40 shuffle partitions. All steps COMPLETED: Hadoop 17.2 min, Spark 13.3 min, cluster 36.3 min, 24 normalized instance hours, J01 accepted 27,993 works (`experiments/derived/size-scaling/README.md`). No raw results exist under `experiments/results/exp-a*`; the derived table reports this as a gap (`experiments/derived/exp-a/README.md`).

### 5.2 Size scaling (Hadoop 25, 50, 100 percent)

Measured (`experiments/derived/size-scaling/README.md`, from run.json and `docs/agent-runs/phase-14/evidence/window-1/p14-window1-results.tsv`). Runs at deef295 with 40 shuffle partitions:

| Fraction | DBLP works (J01) | Hadoop min | Works per Hadoop minute | Spark | Spark min | Cluster min | Normalized instance hours |
|---|---|---|---|---|---|---|---|
| 25 | 27,993 | 17.2 | 1,628 | COMPLETED | 13.3 | 36.3 | 24 |
| 50 | 55,598 | 27.6 | 2,014 | FAILED (OOM, TemporalSummaryExportJob:442) | 18.9 | 52.2 | 24 |
| 100 | 110,867 | 53.3 | 2,080 | FAILED (OOM, TemporalSummaryExportJob:442) | 36.3 | 95.6 | 48 |

Every fraction record, failed ones included (same table):

- 25 percent attempt 1: no run.json (submit failed, P14-D3).
- 25 percent attempt 2 (1232927): Hadoop 16.7 min; Spark FAILED after 0.1 min (P14-D5).
- 25 percent attempt 3 (fa93806, 16 partitions): complete, Hadoop 16.6, Spark 13.0 min.
- 50 percent attempts 1 and 2 (fa93806, 16 and 40 partitions): Hadoop 27.5 and 27.2 min; Spark OOM after 18.6 and 19.4 min.

Phase 14 ruling 22: Hadoop time grows 1.60x and then 1.93x per doubling. P14-D8 (EMR aggregation memory in Spark job 10) is open. The local 100 percent run completed with a 6 GB heap (Phase 14 ruling 21).

### 5.3 Experiment D (window 2, query load on graph-evidence-api)

Measured (`experiments/derived/exp-d/README.md`; Phase 14 rulings 46, 47):

- Attempt 1 (20261118T061933Z): exit 3 at the preflight. Neighbors of a high-degree researcher returned 504 DOWNSTREAM_TIMEOUT after 5,434 ms (Phase 14 ruling 46).
- Attempt 2 (20261118T063904Z) ran after a warm-up: 4 levels of 180 s, 0 errors at every level.

| Concurrency | Requests | Throughput (req/s) | p50 / p95 / p99 (ms) | Desired tasks before / after |
|---|---|---|---|---|
| 1 | 1,475 | 8.19 | 87.5 / 281.0 / 427.9 | 1 / 1 |
| 2 | 2,972 | 16.5 | 84.9 / 295.5 / 462.9 | 1 / 1 |
| 4 | 4,610 | 25.6 | 92.7 / 446.5 / 590.6 | 1 / 1 |
| 8 | 6,439 | 35.72 | 119.8 / 673.5 / 914.3 | 1 / 1 |

- Neighbors is the slowest kind: at c=8, p95 849.2 ms and p99 1,072.7 ms. Evidence and node stay under 400 ms p99 (levels-by-kind table).
- No scale-out. In the c=8 minutes (00:50 to 00:52 at -06:00), the service's ECS CPU averaged 19.8 to 26.6 percent while Neptune CPU was 80.4 to 93.0 percent (`operations/deployments/window-2/post-exp-d-cloudwatch.txt`; ruling 2 records 20 to 27 and 80 to 93 percent). The target-tracking target was 60 percent.
- Neptune class: db.t4g.medium.

### 5.4 Experiment D-scale (window 3, CPU target 15 percent)

Measured (`experiments/derived/exp-d-scale/README.md`; rulings 2, 10(e), 13(c)):

- One c=8 level for 600 s: 21,962 requests, 36.59 req/s, 0 errors, p50 112.3, p95 682.9, p99 922.3 ms. Desired count 1 before and 2 after.
- The 15 percent target is an experiment setting (ruling 2), reverted to 60 after the window (ruling 13(e)).

| Event (UTC, 2026-11-20) | Time |
|---|---|
| level start | 00:19:00.723 |
| AlarmHigh OK to ALARM | 00:24:01.103 |
| scale-out activity start ("desired count to 2") | 00:24:01.287 |
| second target registered | 00:24:32.269 |
| desired 2, running 2 (30 s poll) | 00:24:38.011 |
| level end | 00:29:00.975 |
| AlarmLow OK to ALARM | 00:46:38.891 |
| scale-in activity start ("desired count to 1") | 00:46:39.066 |

- Intervals (intervals table): level start to scale-out activity 300.6 s; level start to second target registered 331.5 s; level end to scale-in activity 1,058.1 s.
- Registration delay after the scale-out activity: 00:24:01.287 to 00:24:32.269, about 31 s.

| Phase (throughput tables) | Seconds | Requests | req/s | p50 / p95 / p99 (ms) | Neptune CPU (1-min range) |
|---|---|---|---|---|---|
| one task (level start to registration) | 331.5 | 11,229 | 33.87 | 121.5 / 739.1 / 1,006.0 | - |
| two tasks registered (to level end) | 268.7 | 10,733 | 39.94 | 103.0 / 622.9 / 801.3 | - |
| steady state: last full one-task minute | 60 | 2,239 | 37.32 | 115.0 / 645.2 / 837.3 | 96.971 |
| steady state: full two-task minutes | 240 | 9,655 | 40.23 | 101.7 / 620.7 / 793.2 | 96.988 to 97.649 |
| reference: window 2 c=8, one warmed task (different run) | 180 | 6,439 | 35.72 | 119.8 / 673.5 / 914.3 | - |

- ALB requests per minute during the level: 1,546, 1,856, 2,106, 2,217, 2,241, 2,324, 2,364, 2,398, 2,422, 2,472. The rise began before the second task and continued through the two-task minutes.
- Neptune CPU was 97.0 to 98.7 percent in every full level minute after the first (caveats in the derived README).
- Registration is not health; health-check timing was not captured.
- Gap: alb_target_5xx had no datapoints (derived README).

### 5.5 Experiment F (window 2, failure and recovery)

Measured (`experiments/derived/exp-f/README.md`; Phase 14 ruling 46): result PASS; all 8 checks PASS.

- Retry: the poison message was received 3 times ([1, 2, 3]) and redriven to the DLQ.
- Recovery time to the DLQ: 928 s (sent 06:20:03Z, observed 06:35:31Z). The DLQ was polled about every 30 s, so this bounds the time from above by up to one poll interval.
- Idempotent replay: a replay while QUEUED and after SUCCEEDED returned the same job with Idempotent-Replay true.
- One job ran: job-queued 1, job-finished 1.

### 5.6 Smoke tests and cold cache

- Window 2 smoke 1 FAIL: sync B07 returned 502 after 10,779 ms, with two Neptune TimeLimitExceededException, 8 minutes after the instance went back to db.t4g.medium. Smoke 2 PASS (`operations/releases/smoke-20261118T061612Z.json`; Phase 14 rulings 44, 45).
- Window 3: warm-up after the downsize (two 504s, then 200); smoke PASS on the first attempt (ruling 10(d)).
- The Neptune bulk load ran out of memory on db.t4g.medium in window 2 and completed on db.r6g.large (Phase 14 rulings 42, 43). In window 3 it ran on db.r6g.large in about 70 min (ruling 10(b)).

### 5.7 Cost

Measured:

- Window 1 EMR: 344.7 cluster minutes (5.74 h), about 3.32 USD at on-demand rates (Phase 14 ruling 22).
- Recorded estimates and readings (not settled costs):
  - window 2 Neptune resize extra about 0.60 USD (Phase 14 ruling 43);
  - month-to-date before credits 7.09 USD at the window 2 plan and 10.09 USD after window 2 cleanup (Phase 14 rulings 38, 48);
  - E Bedrock worst case 2.64 USD by the manifest (ruling 14 point 9).

Settled costs (`docs/agent-runs/phase-15/evidence/gate/cost-explorer-20261121.txt`; ruling 16 point 5): read Nov 21, 2026, 17:00 CST; UTC billing days; credits and refunds excluded; lines under 0.01 USD omitted; Nov 21 not yet posted and Nov 20 possibly incomplete (ruling 16 point 5).

- Month to date before credits: 16.84 USD (file: 16.8378173179).

| UTC day and attribution (ruling 16 point 5) | Day amount (USD) | Lines (USD) |
|---|---|---|
| Nov 14, local live checks before window 1 | 0.46 | Haiku 0.46 |
| Nov 16, window 1 | 1.53 | EC2 compute 1.10, EMR 0.18, S3 0.13, EC2-Other 0.08, VPC 0.04 |
| Nov 17, window 1 | 2.05 | EC2 compute 1.53, EMR 0.42, EC2-Other 0.10 |
| Nov 17, window 2 | 0.43 | ECS 0.19, DynamoDB 0.15, ELB 0.09 |
| Nov 17, shared by windows 1 and 2 | 0.33 | S3 0.20, VPC 0.11, Cost Explorer 0.02 |
| Nov 18, window 2 | 4.20 | ECS 2.14, Neptune 0.67, VPC 0.45, DynamoDB 0.41, ELB 0.41, Haiku 0.04, S3 0.04, Cost Explorer 0.03, Secrets Manager 0.01 |
| Nov 19, window 3 | 2.98 | Neptune 2.01, DynamoDB 0.56, ELB 0.16, ECS 0.10, VPC 0.07, S3 0.04, Haiku 0.02, Cost Explorer 0.02 |
| Nov 20, window 3 tail and E | 1.16 | ECS 0.53, Haiku 0.36, VPC 0.11, ELB 0.10, Neptune 0.02, S3 0.02, Cost Explorer 0.02 |
| Nov 1 to 13 plus lines under 0.01 | about 3.70 | the month total minus the listed lines (13.14) |
| Total | 16.84 | |

Per window (ruling 16 point 5):

- Window 1 (EMR, Nov 16 to 17 UTC): about 3.58 USD (1.53 + 2.05), against the cluster-timeline estimate of 3.32 USD above.
- Window 2 (Nov 17 about 20:30 UTC, ruling 18 point 4, to Nov 18 about 18:20 UTC): about 4.63 USD (0.43 + 4.20), plus part of the shared Nov 17 lines (0.33). Neptune has no line on Nov 17 UTC.
- Window 3 (Nov 19 about 17:30 UTC, ruling 17 point 2, to Nov 20 about 04:20 UTC): 2.98 USD on Nov 19, plus part of Nov 20's 1.16 USD.
- E (Nov 20, 09:37 to 09:47 UTC, ruling 14 point 2): the Haiku line on Nov 20 UTC is 0.36 USD and also covers the window 3 tail, so E's share cannot be separated by day. E's token counts (about 177k input and 21k output tokens) give about 0.28 USD at the Haiku 4.5 list price of 1 USD per million input and 5 USD per million output tokens, with the Titan embedding cost negligible (ruling 16 point 5; ruling 18 point 4), against the manifest's worst case of 2.64 USD.
- The total of 16.84 USD is under the 25 USD limit (D-13). Only the 43 base resources remain (ruling 16 point 5).

### 5.8 Interpretation

- **Hadoop scaling.** Hadoop scales slightly better than linearly in works per minute (1,628, then 2,014, then 2,080) because fixed start-up time weighs less on larger inputs. Spark on EMR does not complete past 25 percent with this cluster size (P14-D8). This is a negative result on capacity, not on correctness: the local runs complete.
- **Bottleneck.** The data are consistent with Neptune on db.t4g.medium being the bottleneck in both D runs (Neptune CPU 80.4 to 93.0 percent at c=8 in window 2, 97.0 to 98.7 percent in window 3; sections 5.3 and 5.4), but do not prove it: the laptop client and the internet path are part of the measurement, and their limits were not separated (Threats to validity). In window 2 the service never reached its 60 percent target, which is why there was no scale-out.
- **Scale-out and scale-in.** In window 3 the 15 percent setting produced exactly one scale-out and one scale-in. The scale-out came about 5 min after the level start, not the 3 min ruling 2 expected. The scale-in came about 17.6 min after the level end, against about 15 min expected.
- **Throughput.** Neither the phase averages (33.87 against 39.94 req/s) nor the steady-state rows (37.32 against 40.23 req/s) attribute the throughput difference to the second task (ruling 13(c)). The request rate was already rising before the second task, it kept rising through the two-task minutes, and Neptune stayed near 97 percent CPU throughout. The expected finding of ruling 2, that a second task adds no throughput while Neptune is the bottleneck, is consistent with these data but not proven by one run.
- **Cache dependence.** Latency depends on a warm Neptune buffer cache (smoke 1, D attempt 1, the window 3 warm-up).
- **Cost.** The month to date before credits (16.84 USD) stayed under the 25 USD limit. The largest lines in the runtime windows are ECS (2.14 on Nov 18) and Neptune (2.01 on Nov 19, a day that includes the db.r6g.large hours, ruling 10(c)). Window 1's settled cost (about 3.58) is above its cluster-timeline estimate (3.32). E's Bedrock cost (about 0.28 by token counts, inside the 0.36 Haiku line of Nov 20) was far below the manifest's worst case (2.64).
- **Async recovery.** F shows that retry, DLQ redrive and idempotent replay work as designed. The 928 s recovery time is consistent with three receives at the queue's 300 s visibility timeout (Phase 14 work order 5 states maxReceiveCount 3 and visibility 300 s).

## 6. Agentic software factory

Sources: `docs/agent-runs/factory-metrics.md` (Phases 00 to 14; cells marked ? are missing data), each phase's `human-gate.md`, and ruling 15 point 4 for Phase 15. Phase 8 is merged into 7, 13 into 12 and 16 into 15 (D-10).

### 6.1 Measured

First-pass acceptance by agent: the factory-metrics "first-pass accepted" column, implementation and operations rows with y or n (code-reviewer rows and rows marked - or ? excluded; Phase 15 rows are added at the gate, ruling 15 point 4).

| Agent | y/(y+n) |
|---|---|
| factory-engineer | 1/2 |
| build-engineer | 6/7 |
| dependency-updater | 1/1 |
| dataset-engineer | 4/7 |
| domain-engineer | 11/12 |
| entity-resolution-engineer | 8/9 |
| hadoop-engineer | 5/6 |
| spark-engineer | 5/9 |
| storage-engineer | 9/12 |
| rag-engineer | 6/13 |
| service-engineer | 2/7 |
| test-engineer | 1/3 |
| infra-engineer | 3/8 |
| deploy-engineer | 1/3 |
| experiment-engineer | 1/2 |
| all of the above | 64/101 |

Review findings (critical/major/minor), repair rounds and BLOCKED-ON events per phase:

| Phase | Reviews (c/m/m) | Repair rounds (human exceptions) | BLOCKED-ON events | Source |
|---|---|---|---|---|
| 01 | 2/6/?, 0/?/?, ?/?/? (final APPROVE) | 5 (repair-01 to 05) | 0 recorded | factory-metrics; `phase-01/human-gate.md` |
| 02 | 0/0/0 | 2 | 1 (build-engineer, version values) | factory-metrics |
| 03 | 0/0/5 | 2 | 0 | factory-metrics |
| 04 | 0/0/3 | 0 (WO1 BLOCKED-ON routed as WO2 to WO4) | 1 (hadoop-engineer WO1) | `phase-04/human-gate.md` |
| 05 | 0/1/11, 0/1/5 | 1 | 1 | factory-metrics; `phase-05/human-gate.md` |
| 06 | 0/3/8, 0/0/3 | 1 | 0 | factory-metrics |
| 07 | 0/2/13, 0/0/6 | 1 | 0 | factory-metrics |
| 09 | 0/6/9, 0/2/3, 0/2/4 | 2 (ruling 29) | 1 | factory-metrics; `phase-09/human-gate.md` |
| 10 | 0/1/5, 0/0/5 | 1 | 0 | `phase-10/human-gate.md` |
| 11 | 0/5/15, 0/1/3 | 2 (ruling 20) | 2 (WO3; repair-01 D, D8) | `phase-11/human-gate.md` |
| 12 | 0/1/8, 0/0/3, 0/1/1, 0/0/3 | 3 (rulings 22, 23) | 3 (storage WO1, infra WO2, infra WO3) | factory-metrics; `phase-12/human-gate.md` |
| 14 | ?, ?, 0/0/3, 0/1/9, 0/0/2 | 4 (rulings 14, 19, 33) | 1 (E, ruling 31) | factory-metrics; `phase-14/human-gate.md` |
| 15 | 0/0/7, 0/0/5, 0/1/6, 0/0/3 | 2 (ruling 12) | 0 (all work orders COMPLETE) | rulings 6, 7, 12, 13; ruling 15 point 4 |

- Sums of the known cells: 2 critical (all in Phase 01); 34 major; 148 minor. These exclude every ? cell (Phase 01 reviews 2 and 3, Phase 01 review 1 minors, Phase 14 reviews 1 and 2).
- BLOCKED-ON events: 10 in total, counting Phase 11 as 2 from its gate.
- Phases closed by human escalation without a final APPROVE: 05, 09 and 11 (their gates).

Hooks, ownership and unsafe actions:

| Event | Source |
|---|---|
| guard-dangerous.sh blocked all three window 3 probes (recursive S3 delete, EMR terminate, ECS desired count 100) before AWS | ruling 10(f) |
| guard-paths blocked the test-engineer's write to a module path (ownership) | `phase-11/human-gate.md` (ruling 11) |
| guard-paths blocked an orchestrator write under docs/agent-runs/ that was not a report file | factory-metrics, Phase 03 |
| guard-paths blocked writes to unowned scratch/phase-NN-logs/ | Phase 14 ruling 13 |
| build-engineer stopped before writing into 13 other agents' paths (instruction, not hook) | factory-metrics, Phase 02 |
| a guard block worked around with a scratchpad script (M11) | `phase-07/human-gate.md` |
| infra-engineer ran cleanup.sh through a wrapper; accepted deviation | factory-metrics, Phase 12; `phase-12/human-gate.md` (ruling 10) |
| guard false positives, handled without a workaround | Phases 03, 04, 07, 09, 10, 11, 12, 14 gates or factory-metrics; ruling 5 (WO2); ruling 12(i) |
| hook self-tests: guard-dangerous 304/304, guard-paths 142/142, verify-tests-ran 11/11; validate-agent-files 231/231 | check-sh-wo4.txt |

Manual interventions recorded in the gates:

- Rulings: Phase 07 up to 37, Phase 09 31, Phase 10 25, Phase 11 27, Phase 12 24, Phase 14 49, Phase 15 18 (as of repair-03).
- Human-run cloud work: plans, applies, secrets, loads, Neptune resizes, the smoke rerun, experiments D, E and F, cleanups (`phase-14/human-gate.md`; rulings 3, 10, 13(f)). Window 2 deploy was human-run (Phase 14 ruling 36), and every window 3 script was human-run (ruling 3).
- Local copies of load-stores.sh hidden by .git/info/exclude for window 2 (Phase 14 rulings 40, 41).
- Session-token redaction in three operations/ files (Phase 14 ruling 41).
- External assistance: Codex used by the human as a Terminal assistant on 2026-11-10 (`phase-09/human-gate.md`).

Agent output that needed substantial human rewrite:

- Phase 01: repair-05.patch was built by the human (factory-metrics; `phase-01/human-gate.md`).
- The gates of Phases 04, 05, 06, 07, 09, 10, 11, 12 and 14 state no manual edits to agent-owned files (Phase 14: "agent-owned source").
- The factory-metrics "manual edits" column holds non-zero values for some rows in Phases 09, 11 and 12. The file does not define the unit, and the values do not match the gates' statements.

### 6.2 Interpretation

- **First pass.** About two thirds of agent invocations (64/101) were accepted on the first pass. Agents that write against live services or long integration surfaces were accepted less often on the first pass: rag 6/13, service 2/7, infra 3/8, deploy 1/3.
- **Repairs.** Five of the 13 listed phases used at most one repair round (04, 05, 06, 07, 10). The others used two to five, with human exceptions where the gates record them.
- **Hooks.** Hooks caught ownership violations and blocked the probed destructive commands. Two events show that the instruction layer, not the hooks, carried the control: a scratchpad workaround and a cleanup.sh wrapper. Both were caught in review and recorded.
- **Gaps in the record.** The ? cells and the undefined manual-edits column limit any per-agent hook or rewrite statistic.

## 7. Reproducibility

### 7.1 Measured

- **Validation.** `scripts/check.sh` on 48e1864 passed every gate: JSON, constitution, bash syntax, dataset validation, terraform fmt and validate, sbt check (`docs/agent-runs/phase-15/evidence/gate/check-sh-wo4.txt`; ruling 14 point 8). shellcheck was skipped because it is not installed (same file).
- **Plans by checksum:**
  - window 1: 43/0/0 (Phase 14 rulings 3, 11);
  - window 2: plan 20261117T201324Z-506eddf46f23, sha256 ba816190fb85a236d10641c531980af10456bd1ea8b9a0400a6a81428f0c2ea0, 89/1/0 with the change explained, approval record `operations/plans/20261117T201324Z-506eddf46f23-APPROVED.txt` (`phase-14/human-gate.md`);
  - window 3: plan 978ce305, 89/0/0 (ruling 10(a)).
- **Deploy from an approved plan.** deploy.sh applies only a saved plan whose sha256 equals the approved value, exits 4 on a mismatch and never uses -auto-approve (`phase-12/human-gate.md`). Recorded outcomes:
  - window 1 attempt 1 exit 1 with nothing applied (relative plan path, P14-D1); attempt 2 applied 43 (`operations/releases/release-20261116T062349Z-d33ce8e8d507.json`; Phase 14 ruling 11);
  - window 2: deploy --resume failed once on the window order, then exited 0 with digests verified (`operations/releases/release-20261117T232233Z-506eddf46f23.json`; Phase 14 ruling 39);
  - window 3: deploy.sh --resume exit 0 (ruling 10(d)).
- **Bounded pipeline runs.** Every fraction run has a record under `operations/pipeline-runs/*-fraction*` (one without run.json, by P14-D3).
  - J01 accepted works were the same in every attempt of a fraction (27,993 / 55,598 / 110,867; size-scaling table).
  - The EMR 100 percent count equals the Phase 4 local full run (110,867 works; `phase-04/human-gate.md`).
  - Hadoop minutes across repeated attempts, at different commits or partition settings: 25 percent 16.7, 16.6, 17.2; 50 percent 27.5, 27.2, 27.6.
  - Local Spark reruns are deterministic (Phase 6 gate).
- **Regenerating the derived tables.** The derived README states that the output holds no generation time, so a rerun on the same inputs writes the same bytes. The commands are below. `S` is the newest E stamp, `20261120T093705Z/`, so `"$S/raw"` gives `.../20261120T093705Z//raw`, the argument the committed summaries record. The labelled command reads the human's label files and the committed copy of the keys (ruling 16 point 4); the recorded labelled run read the original key files before they were copied there.

```text
python3 -I experiments/analysis/derive-tables.py
python3 -I experiments/analysis/derive-tables.py --self-test
S=$(ls -td experiments/results/exp-e-retrieval-modes-v1/*/ | head -1)
python3 -I experiments/analysis/summarize-exp-e.py --raw "$S/raw" --out experiments/derived/exp-e
python3 -I experiments/analysis/summarize-exp-e.py --raw "$S/raw" --relevance labels/relevance-e-v1.csv --ratings labels/ratings-e-v1.csv --keys docs/agent-runs/phase-15/evidence/exp-e-keys --out experiments/derived/exp-e-labelled
python3 -I experiments/analysis/summarize-exp-e.py --self-test
```

- **Recorded regeneration result** (`docs/agent-runs/phase-15/evidence/gate/regenerate-derived.txt`, at 43201d8; ruling 16 point 6):
  - derive-tables self-test exit 0, every check PASS, including "two builds are byte-identical"; the rebuild into `experiments/derived` exited 0 and changed no committed derived-tables output.
  - summarize-exp-e self-test exit 0, every check PASS.
  - The unlabelled E summary rebuilt byte-identically with the `//raw` argument (git status of `experiments/derived` empty). With `/raw`, only the Source line of `exp-e-summary.md` differed.
  - The labelled summary was generated with the same argument (ruling 16 point 6).
- **Recorded labelled rebuild** (`docs/agent-runs/phase-15/evidence/gate/regenerate-labelled.txt`, at cdb9578; ruling 17 point 3): the labelled command above, with the `//raw` argument and the committed key copy, written to a temporary folder outside the repository: exit 0, every file byte-identical to the committed `experiments/derived/exp-e-labelled`.

- **Clean-checkout build.** Pending the Phase 17 fresh-clone run; not measured here.

### 7.2 Interpretation

- Infrastructure changes are reproducible from approved, checksummed plans, and every apply in the three windows used one.
- Pipeline counts are stable across reruns and between local and EMR runs at 100 percent Hadoop.
- Every operator error found in the windows was recorded and ruled, never silently repaired: the relative plan path, the window 2 order, and the Neptune class drift outside Terraform for the bulk load.
- The full Spark pipeline on EMR did not complete beyond 25 percent at this cluster size with the settings tried (16 and 40 shuffle partitions; P14-D8). Option B (Spark executor cores 2) is untested and deferred (Phase 14 rulings 21 and 22), so whether it completes with other settings is not known.

## Neptune record count: 34,052,689 (window 3) against 29,054,998 (window 2)

Measured:

- **Window 3.** One bulk load (6113dd40) into a newly applied cluster: LOAD_COMPLETED, 34,052,689 records, duplicates 0, errors 0 (`operations/pipeline-runs/store-load-20261119T190616Z/neptune-status-ac8b0c3cb5fc495696b9ebac8793b0c3.txt`). The input was store run run-1d6a86491812ff94887270c1ab885e69 (`run.json` in the same directory).
- **Window 2, first load (153fcdc9).** LOAD_CANCELLED_DUE_TO_ERRORS after 5,059,443 records, duplicates 0, insert errors 2, OUT_OF_MEMORY_ERROR at `edges/affiliated-with/part-00000.csv` record 0. The input was the same store run (`operations/deployments/window-2/neptune-load-error-details.txt`). The vertex files were loaded and the edge files were not (Phase 14 ruling 42).
- **Window 2, second load (08a704ef).** Into the same partly loaded cluster: LOAD_COMPLETED, 29,054,998 records, duplicates 0 (`operations/deployments/window-2/neptune-status.txt`). Phase 14 ruling 43: "the earlier partial load's vertices were not reported as duplicates".
- **Arithmetic.**
  - 34,052,689 - 29,054,998 = 4,997,691. This is close to, but not equal to, the first load's 5,059,443; the difference is 61,752.
  - 29,054,998 + 5,059,443 = 34,114,441, which is not 34,052,689.
- **Units.** The Phase 7 validator counted 3,635,231 graph elements in the same store run (`phase-07/human-gate.md`). The loader's "records" therefore do not count one per vertex or edge row.

Finding: open. The evidence is consistent with this hypothesis: in window 2 the second load did not count the elements the cancelled first load had already written, and it did not report them as duplicates. The two windows then loaded the same input, and the window 3 figure is the count for the complete graph in an empty cluster. Two things are not explained by the committed records. The first is the 61,752-record residual, which may come from records the first load counted before its cancellation but did not keep. The second is the loader's counting unit, which is not documented in the committed evidence. Both smoke tests returned the same evidence ID (ruling 10(d)), so the served graph content agrees on that query. A per-label vertex and edge count from both clusters would settle it, but both clusters have been destroyed.

## Threats to validity

- **Small question sets.** E has 10 questions per mode. The live benchmarks have 30 questions. The gold strata have about 39 rows each, with 1 observed false positive.
- **Single runs.** Every experiment except D is one run, and D completed its levels only once, so variability is not measured. The repeated Hadoop attempts in size scaling ran at different commits or settings.
- **Single labeller.** The Phase 5 gold labels, the E relevance labels (101 pooled items) and the E answer ratings (30 answers) each come from one human (ruling 16 point 1). No inter-rater agreement exists.
- **Rater independence.** That one human is also the system's architect (`docs/agents.md`, human architect) and the benchmark's reviewer (`phase-06/human-gate.md`), so the gold labels, relevance labels and ratings are not independent of the system's design. Together with the blinding limit of the answers sheet (ruling 12(h)), this is a possible rater bias, separate from having a single rater.
- **Abstention-rule departure.** The rating anchors give usefulness 1 to an answer that says it cannot answer. Vector-only's mean usefulness is 1.9, not the 1.0 that rule alone gives, so some abstaining answers were rated above 1. The ratings are kept as entered, because a re-rating after the keys were opened would not be blind (ruling 16 point 3). The departure is limited to vector-only (9 of its 10 abstaining answers rated above 1); the fused and graph-only abstaining answers were rated usefulness 1 (ruling 18 point 3).
- **Benchmark provenance.** `experiments/benchmark/benchmark-questions-v1.csv` was drafted with Claude in a web chat and reviewed by the human. It was not generated by the runtime prompt under evaluation (`phase-06/human-gate.md`). The same model family (Haiku 4.5) answers the questions.
- **File-backed vectors.** E searched a local exact index over the exported vectors, not the S3 Vectors service (Phase 14 ruling 31).
- **Local runtime for E.** No deployed service, network path or store latency is included. Bedrock was live in us-east-2.
- **Laptop client for D and D-scale.** The load generator ran from the laptop over the internet, so client and network limits are part of the measurement.
- **Cold caches after resizes.** Smoke 1, D attempt 1 and the window 3 warm-up show cache-dependent Neptune latency. D attempt 2 ran after a warm-up of two paths only.
- **Experiment settings.** The CPU target of 15 percent in D-scale is an experiment setting, not production (ruling 2). Neptune ran on db.r6g.large outside Terraform during the loads (Phase 14 rulings 42, 43; ruling 10(c)).
- **Blinding.** Vector-only abstentions are recognisable in the answers sheet (ruling 12(h)).
- **Verifier.** It is lexical; D18 to D20 are open. "Verified" counts depend on its word lists.
- **Cost Explorer lag.** Billing lags about a day (Phase 14 rulings 22, 48), so settled costs are read on Nov 21 (ruling 14 point 9).
- **Record-keeping gaps.**
  - Factory-metrics ? cells; Phase 15 rows not yet added.
  - The undefined manual-edits column.
  - A BLOCKED-ON count that differs between factory-metrics and the Phase 11 gate.
  - `post-exp-d-cloudwatch.txt` headers say UTC while its timestamps carry a -06:00 offset.

## Missing data

| Group | Spec metric | Status and reason |
|---|---|---|
| 1 | metrics for the LLM path | 0 rows: no live adjudication in the evaluated decisions |
| 1 | confidence intervals | not computed; small strata |
| 1 | the repair-02 ORCID suffix-conflict rule; identity after the Phase 6 refusal tiers | not measured by the gold set (`phase-05/human-gate.md`) |
| 1 | live adjudication accuracy | the Phase 9 demonstration was not evaluated against gold labels |
| 2 | duplicate rate and orphan rate as rates | only rule-violation and loader duplicate counts (0) exist |
| 2 | sampled provenance audit | no sampled audit recorded; the coauthor-edge trace is a full tool check |
| 2 | validator run on the EMR outputs | none: no validator output for an EMR run is committed. The EMR Spark runs wrote integrity-violation tables whose row counts are not committed (S3 listings `docs/agent-runs/phase-14/evidence/window-1/p14-s3-run-*.txt`). The served graph is the local store conversion |
| 3 | recall at K, MRR | measured (section 3.2) over 9 scored questions per mode; capped at the bundle size; one run, one labeller |
| 3 | precision at K | not reported: the summarizer computes only recall at K and MRR (work order 4 A3); the binary labels would support it, and the bundle cap would also bound it |
| 3 | nDCG | not reported: no graded relevance labels |
| 3 | OpenSearch comparison | optional, not built (D-06) |
| 3 | retrieval through the S3 Vectors service | not measured; E used the file-backed export |
| 4 | human usefulness and clarity ratings | measured (section 4.2) over 10 answers per mode; one rater, no inter-rater agreement |
| 4 | identifier and numerical accuracy against independent ground truth | only the verifier's rule checks exist |
| 4 | contradiction rate, edge, path and supporting-publication validity for E | measured (section 4.1) from the BenchmarkCli summaries: contradiction rate 0/22, 0/16 and 7/11; pair-edges, claim-evidence and path-hops checks per mode. No check is named for supporting-publication validity. The E summarizer does not report CONFLICTING or the per-check results separately |
| 4 | abstention quality | measured as the benchmark's abstention check (section 4.1): E fused 10/10 and graph-only 10/10 pass, vector-only 1/10 (9 failures by design, ruling 6); Phase 11 gate abstention fails B15. 10 questions per mode, one run |
| 5 | p99 for E | the summarizer reports p50, p95 and max |
| 5 | settled cost of windows 2 and 3 and of E | measured per UTC day (section 5.7); E's Bedrock share cannot be separated from the window 3 tail on Nov 20, and Nov 20 may be incomplete |
| 5 | Spark on EMR at 50 and 100 percent | failed (P14-D8); option B deferred |
| 5 | scale-out of query-api or the worker; recovery from a task failure | not run; F covered only the poison-message path |
| 5 | experiment C | deferred (D-10) |
| 5, all | variability | single runs |
| 6 | dates, hooks triggered, and findings marked ? | missing in `docs/agent-runs/factory-metrics.md` |
| 6 | Phase 15 rows | added at the gate (ruling 15 point 4) |
| 6 | systematic count of ownership violations caught | not recorded per invocation |
| 7 | clean-checkout build success | pending the Phase 17 fresh-clone run |
| 7 | the result of regenerating the derived tables | recorded (section 7.1): `regenerate-derived.txt` and `regenerate-labelled.txt` under `docs/agent-runs/phase-15/evidence/gate/`; the derive-tables rebuild changed no committed output, and both E summaries rebuilt byte-identically with the `//raw` argument |
| 7 | plots | none produced; the derived tables (CSV and Markdown under `experiments/derived/`) are the only regenerated outputs |
| 7 | a second identical cloud pipeline replay | not run; reruns used different settings |
