# Gold-set labelling rules (Phase 5)

Labeller: Achyut Patel (human). Queue: scratch/er/review-queue-wo5/review-queue-20261103.csv (er-v1 sample, seed 20261103), copied to labels/gold-20261103.csv. Labels are given blind; no decision, score, or path is shown.

Label values: MATCH, NON_MATCH, UNSURE.

Researcher rows: MATCH when the evidence shows the same person: same ORCID, same DBLP homonym suffix, overlapping coauthors, a plausible affiliation history, or a consistent topic and timeline. NON_MATCH when the evidence shows different people. UNSURE when the evidence can't decide; write why in notes. A shared name alone is never enough for MATCH. A missing DBLP suffix is not evidence of a different person.

Work rows: MATCH when both records are the same paper (same title, same authors, year within about one year), even if the DOIs differ. Republications under two DOIs, such as ACM proceedings plus SIGPLAN Notices or SIGARCH CAN, are MATCH with "dual-doi" in notes. Different papers that share a title, such as front matter ("Session Introduction."), journal extensions, and errata, are NON_MATCH.

The order of a_id and b_id carries no information about the answer and is ignored.

Rules are fixed before labelling and are not changed after seeing any evaluation result.

Labels were entered with a local display tool (kept outside the repo at ~/scholarchain-evidence/tools/scholarchain-labeller.html) that shows A and B side by side and marks equal values. It never suggests a label; every label is the human labeller's decision.

Given names (added before the first label): an initial that fits the full name, or a spelling, hyphenation or spacing variant, is compatible; continue to the next check. Clearly different full given names are NON_MATCH (note: diff-name).
