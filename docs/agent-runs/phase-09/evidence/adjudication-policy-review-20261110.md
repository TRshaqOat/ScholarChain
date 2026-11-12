# Phase 09 adjudication policy review preparation

Date: 2026-11-10 (checked by the human on 2026-11-11)
Run: run-70a26cef3256da28ef1257cb4498d0bb
Session: session-0001 (the only session; the COMPLETE rerun after repair 1C)

The saved decisions exactly reproduce the COMPLETE session summary:
RESEARCHER 33 ACCEPTED, 81 REJECTED, 66 UNRESOLVED;
WORK 2 ACCEPTED, 11 REJECTED, 7 UNRESOLVED.

The 66 RESEARCHER UNRESOLVED decisions contain:
- 25 with LLM_ABSTAIN.
- 41 with LLM_LOW_CONFIDENCE.
- 0 with neither reason.

The 7 WORK UNRESOLVED decisions contain 0 with LLM_ABSTAIN and 7 with LLM_LOW_CONFIDENCE.

MatchPolicy.withAdjudication (unchanged since accepted commit 77cafc7) keeps a pair
UNRESOLVED when the model answers ACCEPT with confidence below the accept threshold,
answers REJECT with 1 - confidence not below the reject threshold, or answers ABSTAIN.

So UNRESOLVED exceeds abstentions by design. The policy did not override the model's
answers; it kept 41 RESEARCHER and 7 WORK answers that fell below its confidence
thresholds UNRESOLVED. The earlier progress-log tally (24 RESEARCHER abstentions and
about 42 inferred low-confidence answers) is superseded by the persisted counts, 25 and 41.
The cause of the one-count difference in the log tally has not been established.

Recording gap: for LLM_LOW_CONFIDENCE decisions, the decision record keeps neither the
model's answer (ACCEPT or REJECT) nor its confidence, so the 41 cannot be split by answer
from the decision records. Residual risk; no change in Phase 9 (a change would require
rerunning the live adjudication).

Provenance:
- live-adjudication-summary.json and live-adjudication-decisions.jsonl are exact copies of
  session-0001/summary.json and session-0001/decisions.jsonl (checked with cmp).
- live-adjudication-policy-breakdown.json was derived from the decisions file.
- The tally and this note were prepared with Codex (OpenAI) under the human's direction on
  2026-11-10, reading the human-run output under scratch/er/adjudication-live/, and were
  checked by the human on 2026-11-11 against MatchPolicy.scala and the session files.

This note proposes no threshold or match-policy change.
The demonstration has not been evaluated against human gold labels.
Final human gate acceptance remains pending.
