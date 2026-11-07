# Embedding selection policy `selection-v1`

Owner: rag-engineer. Implemented by: the spark-engineer store-conversion job (Phase 7 work order 6), which writes the embedding candidates. Checked by: `scholarchain.storage.cli.ValidateCli --kind candidates --bounds 10000:15000` (storage contract) and `scholarchain.rag.cli.RenderCli` (policy version, rank sequence, facts). Code constants: `modules/rag/src/main/scala/scholarchain/rag/Selection.scala`.

This file is the exact rule for human ruling 19: 13,019 vectors plus the benchmark path pairs of ruling 25, inside D-13 (10,000 to 15,000). Nothing in it needs interpretation. Where it and the code constants disagree, that is a defect for rag-engineer.

## Inputs

| Name | Source |
|---|---|
| Summaries | Phase 6 `summaries/semantic-summaries` of run `run-0375535e0174b7330b18a78988c07211` (snapshot 2026-10-03): `researcher-profile`, `collaboration-community`, `topic-summary` rows |
| Path summaries | The `collaboration-path` rows of the new bounded path run of work order 6 (ruling 25: the seeded 50 pairs plus the resolved benchmark pairs). The 50 Phase 6 path summaries are not used. |
| Rankings | The work order 6 ranking items `RANKING#<metric>` / `TOP#all-years` (ruling 21), seven metrics |
| Works | Phase 6 `canonical/works`, `canonical/authored`, `canonical/researchers`, `canonical/venues`, `canonical/work-topics`, `canonical/topics` |

Windows are the core `graph.windows`, in configuration order: `all-years`, `2000-2014`, `2015-2026`.

## The rank hash

```text
rankKey(index, subjectId, window) = StableIds.hash("embed-select", "selection-v1", "20261003", index, subjectId, window)
```

- `StableIds.hash` joins the parts with U+001F, takes SHA-256 of the UTF-8 bytes, and keeps the first 16 bytes as 32 lower-case hex characters. Spark: `substring(sha2(concat_ws('\u001f', 'embed-select', 'selection-v1', '20261003', <index>, <subjectId>, <window>), 256), 1, 32)`.
- `index` is the vector index name (for example `researcher-profile-v1`); `subjectId` is the summary's `subjectId` (researcher, community, pair, topic, or work ID); `window` is the window name.
- The seed `20261003` is fixed (the DBLP snapshot date, also the Phase 6 path sample seed).
- Order: `rankKey` ascending as a string. Equal keys (not expected) are ordered by `subjectId`, then `window`, ascending by code point.
- Frozen check values (independently computed with Python `hashlib`, and asserted in `SelectionSpec`):
  - `rankKey("researcher-profile-v1", "scr:canon-v1.er-v2:001fe522e8222b9065d8747681782ee5", "all-years")` = `c0bd6ae2faf1ff52b9dc44304245b5fc`
  - `rankKey("paper-abstract-v1", "dblp:conf/ispa/TangY0DHH24", "all-years")` = `0a3416a1573f16e3b3fe090dbbd145df`

"Hash order" below means this order.

## Per index

`selectionRank` is the 1-based position of the candidate in its index's final order, so each index holds exactly the ranks 1..n with no gap and no repeat.

### `collaboration-path-v1`: every path summary

Every `collaboration-path` summary of the work order 6 path run, every status (`FOUND`, `BEYOND_DEPTH`, `UNREACHABLE`, `UNKNOWN_RESEARCHER`). Benchmark names that resolved to `UNKNOWN_RESEARCHER` or `AMBIGUOUS` at name resolution have no path-evidence row and therefore no candidate. Window: the row's window (`all-years`). Order: hash order. Count: 50 plus the resolved benchmark pairs (b). This index is selected first because the profile and paper tiers below refer to it.

### `researcher-profile-v1`: 5,000

Eligible: every `researcher-profile` summary (window `all-years`; 54,935 rows). Each eligible researcher gets the lowest tier that applies:

1. **Tier 1, path researchers.** The researcher is `sourceResearcherId`, `targetResearcherId`, or a `hops[].id` of any selected path summary.
2. **Tier 2, ranked researchers.** The researcher appears in `entries[].researcherId` of any of the seven `all-years` ranking items (`publicationCount`, `distinctCoauthorCount`, `maximumCoauthorsOnOnePaper`, `crossCommunityDegree`, `communitiesConnected`, `participationCoefficient`, `coreness`).
3. **Tier 3.** Every other eligible researcher.

Order: tier ascending, then hash order. Take the first 5,000. A researcher without an eligible profile (fewer than 2 publications) is skipped, never substituted.

### `collaboration-community-v1`: 4,000

Eligible: every `collaboration-community` summary (communities of at least 3 members, all windows; 50,025 rows). Quotas per window: `all-years` 2,000; `2000-2014` 1,000; `2015-2026` 1,000.

1. **Tier 1.** Window `all-years` only: the community is the `communityId` in the facts of a selected profile of tier 1 or 2.
2. **Tier 2.** Every other eligible community.

Within each window: tier ascending, then hash order; take the window's quota. A window with fewer eligible rows than its quota gives all of them, and the remainder is not moved to another window (all three windows have more than enough: 23,635, 8,075, and 18,315). The index order is window order (`all-years`, `2000-2014`, `2015-2026`), then the order within the window.

### `topic-institution-summary-v1`: every topic summary (2,969)

Every `topic-summary` summary, all windows (1,207 + 685 + 1,077). Order: window order, then hash order. No institution summaries exist (Phase 6 produces none), so `institution-summary` documents are not selected in this version.

### `paper-abstract-v1`: 1,000 paper summaries

Ruling 10: the inputs carry no abstracts, so this index holds `paper-summary` documents rendered from work metadata. Window: `all-years` for every paper.

Eligible work: a `canonical/works` row whose `title` is non-null and not blank after trimming, whose `year` is in 2000..2026, and which has at least one `canonical/authored` row whose `sourceDatasets` contains `DBLP`.

1. **Tier 1.** `hops[].workId` of the selected `FOUND` path summaries, in path `selectionRank` order, then hop order.
2. **Tier 2.** `recentWorks[].id` of the selected profiles, in profile `selectionRank` order, then list order.
3. **Tier 3.** Every other eligible work, in hash order (`subjectId` = work ID, window `all-years`).

Walk the tiers in that order, skip ineligible works and works already taken, and take the first 1,000.

### Totals

| Index | Count |
|---|---|
| researcher-profile-v1 | 5,000 |
| collaboration-community-v1 | 4,000 (2,000 + 1,000 + 1,000) |
| collaboration-path-v1 | 50 + b |
| topic-institution-summary-v1 | 2,969 |
| paper-abstract-v1 | 1,000 |
| Total | 13,019 + b (b at most 1,981 keeps the total inside 15,000) |

## Candidate content (embedding-candidate-v1 plus `facts`)

Every candidate is one line of the `embedding-candidate-v1` JSONL of modules/storage/STORAGE.md, with one added field, `facts`. The storage validator ignores unknown fields. The rag render CLI requires `facts` and renders the text from it (rendering-v1, `modules/rag/RAG.md`).

| Field | Value |
|---|---|
| `schemaVersion` | `embedding-candidate-v1` |
| `vectorIndex`, `documentType` | per index: `researcher-profile`, `collaboration-community`, `collaboration-path`, `topic-summary`, `paper-summary` |
| `vectorKey` | the summary's `summaryId`. Papers: `StableIds.chunkId("<workId>\|all-years", "source-title-v1", "paper-summary", None, textHash)` |
| `window` | the summary's window; `all-years` for papers |
| `text`, `textHash` | the summary's `text` and `textHash` (`summary-template-v1`). Papers: the trimmed title, and its `StableIds.textHash` |
| `templateVersion` | `summary-template-v1`; papers `source-title-v1` |
| `textRef` | `semantic-context/text/snapshot=<d>/run=<conversion run>/<vectorIndex>/<vectorKey>.txt` |
| `selectionPolicyVersion` | `selection-v1` |
| `selectionRank` | as above |
| `entityIds`, `topicIds`, `institutionIds`, `communityId` | derived from `facts` by the table below. The render CLI recomputes them and refuses a candidate whose fields differ. |
| `provenance` | the summary row's `provenance` (papers: `DERIVED`, `sourceRecordId` = workId, the conversion run and snapshot, `policyVersion` `source-title-v1`) |
| `facts` | a JSON object (not a string): the summary's `facts` JSON parsed, with the additions below |

Facts additions and the paper facts:
- **Paths.** The Phase 6 path facts lack the end names. Add `sourceName` and `targetName`: the `canonical/researchers.displayName` of the two ends, or the researcher ID when the name is null. Both are required by rendering-v1.
- **Papers (`paper-facts-v1`).** `workId`; `title` (trimmed); `year`; `workType` (when not null); `venueId` and `venueName` (from `canonical/venues.name`, when the work has a venue); `authorCount` (distinct researchers in `canonical/authored` for the work, any source); `authors`: at most 10 of them as `{"id", "name", "position"}`, ordered by `position` ascending, then `id` (name = `displayName`, or the ID when null); `topics`: at most 5 `{"id", "name"}` from `canonical/work-topics` joined to `canonical/topics`, ordered by `name`, then `id`.
- Null values are omitted, as Spark `to_json` does.

Metadata lists derived from facts:

| documentType | entityIds | topicIds | institutionIds | communityId |
|---|---|---|---|---|
| researcher-profile | `[researcherId]` | `topics[].id` | `institutions[].id` | `communityId` |
| collaboration-community | `[communityId]` | `topics[].id` | empty | `communityId` |
| collaboration-path | `FOUND` with `pathLength` > 0: `[pairId, sourceResearcherId, hops[].id...]`; otherwise `[pairId, sourceResearcherId, targetResearcherId]` | empty | empty | null |
| topic-summary | `[topicId]` | `[topicId]` | empty | null |
| paper-summary | `[workId]` | `topics[].id` | empty | null |

Lists keep the facts' order. An empty list is written as `[]` and is omitted from the vector metadata.

## Output layout

`semantic-context/candidates/snapshot=2026-10-03/run=<conversion run>/<vectorIndex>/part-*.jsonl` under `scratch/stores/s3/` (STORAGE.md). Lines in `selectionRank` order.

## Known limits

- Tier 3 is a seeded hash sample, not a ranking. Most low-activity researchers have no profile vector; graph and metric questions do not depend on vectors, which only add descriptive context.
- Tier 1 and 2 make the profile, community, and paper indexes coherent with the path and ranking evidence, so a ranked or path researcher has a profile vector.
- Changing the seed, quotas, tiers, or eligibility is a new policy version (`selection-v2`).
