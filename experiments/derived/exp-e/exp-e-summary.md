# Experiment E summary per retrieval mode

Source: experiments/results/exp-e-retrieval-modes-v1/20261120T093705Z//raw

Harness: started 2026-11-20T09:37:05Z, ended 2026-11-20T09:47:41Z, exit 0, N 10; check {"planSourcesLine": "plan sources: MODEL 2; outcomes: ANSWERED 2", "continue": true, "stopReason": null}; invocations check fused exit 0, modes fused exit 0, modes graph-only exit 0, modes vector-only exit 0

Question sets: the same 10 question(s) in every mode.

| mode | questions | answered | abstained | abstained_by_reason | verified | partially_supported | removed_claims | latency_p50_ms | latency_p95_ms | latency_max_ms | llm_attempts | bedrock_requests | llm_input_tokens | llm_output_tokens | embedding_calls | retrieved_items | retrieved_items_mean |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| fused | 10 | 9 | 1 | AMBIGUOUS_RESEARCHER 1 | 14 | 0 | 8 | 14663 | 19644 | 19644 | 19 | 19 | 65072 | 8255 | 9 | 86 | 8.6 |
| graph-only | 10 | 9 | 1 | AMBIGUOUS_RESEARCHER 1 | 14 | 0 | 2 | 14945 | 20300 | 20300 | 19 | 19 | 57030 | 6705 | 0 | 62 | 6.2 |
| vector-only | 10 | 0 | 10 | AMBIGUOUS_RESEARCHER 1; NO_VERIFIED_CLAIM 9 | 0 | 0 | 11 | 14719 | 19435 | 19435 | 19 | 19 | 54989 | 5889 | 9 | 37 | 3.7 |

## Retrieval quality

Relevance: not labelled yet.

Recall at K uses the first min(K, list length) items of each mode's ranked list. The list is the post-budget evidence bundle (items the token and item budget left out are not listed), so recall at K is capped at the bundle size; the mean list length per mode is in exp-e-summary.csv (Phase 15 ruling 6, m5).

## Answer ratings

Usefulness and clarity: not labelled yet.
