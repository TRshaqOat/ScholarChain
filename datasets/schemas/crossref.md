# Crossref schema note

- Source: Crossref REST API `GET https://api.crossref.org/works?filter=doi:<A>,doi:<B>,...&rows=100&select=<list>&mailto=<contact>` (list request; `message-type` `work-list`), with fallback `GET /works/{doi}` (singleton; `message-type` `work`).
- Schema version: `crossref-rest-api/message-version-1.0.0` (observed `message-version` on 2026-11-02) plus the `select` list `crossref.select` in `datasets/config/subset.v1.json`.
- Raw responses are stored unmodified in `scratch/datasets/crossref/<mode>/raw/`. The committed sample `datasets/samples/crossref/works-sample.jsonl` is a projection. The list route never requests `abstract` or `reference`. Singleton fallback responses can contain an abstract, and the sample projection drops it.
- Nullability: Crossref fields are deposit-dependent, so every field except `DOI` and `type` may be absent. Absent keys appear as `null` in the projection.

| Field | Type | Nullable | Linkage |
|---|---|---|---|
| `DOI` | string (case-insensitive; compare lower-cased) | no | DOI (source record ID) |
| `type` | string (sample: `proceedings-article`) | no | |
| `title` | array of string (use element 0) | yes | normalized title |
| `container-title` | array of string | yes | venue |
| `ISSN` | array of string | yes | venue |
| `issued`, `published-print`, `published-online` | `{date-parts: [[Y, M, D]]}` with partial dates allowed | yes | year (from `issued`) |
| `publisher`, `member` | string | yes | |
| `page`, `volume`, `issue` | string | yes | |
| `references-count`, `is-referenced-by-count` | integer | yes | |
| `URL` | string | yes | |
| `indexed` | ISO timestamp (projection keeps `date-time`) | no | staleness and provenance |
| `license[]` | `{URL, content-version, start}` | yes | license of the content, not of the metadata |
| `funder[]` | `{name, DOI, award[]}` | yes | |
| `author[]` | array, ordered | yes | author sequence |
| `author[].given`, `author[].family` | string | yes (organizational authors use `name`) | author name |
| `author[].sequence` | `first` or `additional` | yes | |
| `author[].ORCID` | URL `https://orcid.org/...` | yes (observed: 85 of 163 authors null) | ORCID (supporting evidence only; not used to drive ORCID fetches) |
| `author[].authenticated-orcid` | boolean | yes | ORCID evidence strength |
| `author[].affiliation[].name` | string | yes | affiliation evidence |

Linkage keys: DOI (exact), title, author sequence, year, venue, and ORCID where it is supplied.
