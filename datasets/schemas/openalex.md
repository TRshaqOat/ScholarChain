# OpenAlex schema note

- Source: OpenAlex REST API `GET https://api.openalex.org/works?filter=doi:<A>|<B>|...&per_page=100&select=<list>` (list request), and `GET /works/doi:<doi>` (singleton) for DOIs containing `,` or `|`.
- Schema version: `openalex-api-works/select-v1`. This is the root-field `select` list in `datasets/config/subset.v1.json` (`openalex.select`). OpenAlex does not publish a response schema version number, so changes are detected by validating the fields below on each run.
- Raw responses are stored unmodified in `scratch/datasets/openalex/<mode>/raw/`. `works.jsonl` holds one work per line. The committed sample `datasets/samples/openalex/works-sample.jsonl` is a field-minimized projection (no abstracts, no raw affiliation strings).
- Nullability: "observed" means seen as null in the 40-work sample. "Per API" means the API may return null even though the sample had none.

| Field | Type | Nullable | Linkage |
|---|---|---|---|
| `id` | URL `https://openalex.org/W...` | no | OpenAlex work ID (source record ID) |
| `doi` | URL `https://doi.org/...`, lower-case | per API (non-null for every record fetched by DOI) | DOI |
| `ids` | object (`openalex`, `doi`, `mag`, `pmid` ...) | keys optional | cross-identifiers |
| `title` | string | per API | normalized title |
| `publication_year` | integer | per API | year |
| `type` | string (sample: `conference-paper`) | per API | |
| `primary_location.source` | object `{id, display_name, issn_l, type}` | per API (0 of 40 null in sample) | venue, OpenAlex source ID |
| `primary_location.raw_source_name` | string | per API | venue string |
| `primary_topic` | object `{id, display_name, subfield, field}` | per API | topic |
| `cited_by_count` | integer | no | |
| `updated_date` | ISO timestamp | no | staleness and provenance |
| `authorships[]` | array, ordered | may be empty | author sequence |
| `authorships[].author_position` | `first`, `middle`, `last` | no | |
| `authorships[].author.id` | URL `https://openalex.org/A...` | per API | OpenAlex author ID (a candidate, not identity) |
| `authorships[].author.display_name` | string | no | author name |
| `authorships[].author.orcid` | URL `https://orcid.org/...` | yes (observed: 24 of 163 authorships null) | ORCID; drives ORCID fetch |
| `authorships[].raw_author_name` | string | per API | name as printed |
| `authorships[].institutions[]` | `{id, display_name, ror, country_code, type}` | may be empty | institution, ROR |
| `authorships[].is_corresponding` | boolean | per API | |

Linkage keys: DOI (exact, primary), OpenAlex work ID, ORCID on authorships, OpenAlex author ID, and ROR on institutions. OpenAlex author IDs are algorithmic clusters, so they are candidate evidence, not identity.
