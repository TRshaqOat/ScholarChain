# DBLP schema note

- Schema version: `dblp-xml/dtd-2023-06-28` (the release's DOCTYPE names `dblp-2023-06-28.dtd`; `https://dblp.org/xml/dblp.dtd`, sha256 `2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6`). Subset rule version: `dblp-subset.v1` (`datasets/config/subset.v1.json`).
- Format: XML, ISO-8859-1. Character entities (for example `&uuml;`) are defined in the DTD, so parsers must load it. Subset files keep the original record bytes and the original prologue.
- Nullability below comes from the DTD: every child element of a record is optional (`(%field;)*`), and only `key` is `#REQUIRED`. "Subset guarantee" lists what the subset rule additionally guarantees.

## Work records (`works.xml`; record elements `article` and `inproceedings`)

| Field | XML location | Type | Nullable | Subset guarantee | Linkage |
|---|---|---|---|---|---|
| record type | element name | enum `article`, `inproceedings` | no | yes | |
| key | `@key` | string, `<stream-prefix>/<id>` (for example `conf/ics/LiaoHNZCT09`) | no (`#REQUIRED`) | prefix is in the venue list | DBLP key (source record ID) |
| mdate | `@mdate` | date `YYYY-MM-DD` | yes (`#IMPLIED`) | no | snapshot provenance |
| publtype | `@publtype` | NMTOKENS (for example `informal`, `withdrawn`) | yes | no | filter signal |
| authors | `author` (repeated, ordered) | string; DBLP disambiguates homonyms with a numeric suffix (`Wei Wang 0001`) | yes (zero or more) | no | normalized author sequence, DBLP person name |
| author ORCID | `author/@orcid` | ORCID iD `NNNN-NNNN-NNNN-NNNX` | yes | checksum-validated before use | ORCID |
| title | `title` | string with inline markup possible (`<i>`, `<sub>`, `<sup>`) | yes | no | normalized title |
| year | `year` | integer, 4 digits | yes | present and in 2000..2026 | year |
| venue | `booktitle` (inproceedings) or `journal` (article) | string (abbreviation) | yes | no | venue |
| volume, number, pages | `volume`, `number`, `pages` | string | yes | no | |
| electronic editions | `ee` (repeated), `@type` (for example `oa`) | URL | yes | no | DOI when the URL starts with `https://doi.org/` |
| DOI (derived) | from `ee` | string, lower-cased, without the resolver prefix | yes | no (USENIX venues have none) | DOI |
| crossref | `crossref` | key of the proceedings record | yes | no | |
| url | `url` | dblp-relative table-of-contents path | yes | no | |
| stream | `stream` | stream reference (DTD since 2023-05-10) | yes | absent in all observed sample records | |

## Person records (`persons.xml`; element `www`, key prefix `homepages/`)

| Field | XML location | Type | Nullable | Linkage |
|---|---|---|---|---|
| key | `@key` | string `homepages/...` | no | DBLP person ID |
| names | `author` (repeated; whether the first one is the primary name is UNVERIFIED this phase, so check against dblp's XML documentation before relying on order) | string | DTD formally optional | DBLP person name |
| ORCID | `url` with prefix `https://orcid.org/` | ORCID iD | yes | ORCID |
| other identifiers and homepages | `url` (repeated) | URL | yes | |
| affiliation | `note` with a `type` attribute (the DTD declares `type` as NMTOKENS; the value `affiliation` is UNVERIFIED because no person record was observed this phase) | string | yes | affiliation evidence |

Person records are selected when any `author` name equals a name on a subset work record. The bounded SAMPLE prefix contains no person records, so the committed sample has none. They appear only in `--full` output.

## Derived lists

- `dois.txt`: distinct DOIs from subset `ee` elements. Drives OpenAlex and Crossref.
- `orcids.txt`: union of `author/@orcid` on subset works and ORCID URLs on selected person records. Combined with OpenAlex authorship ORCIDs, it drives ORCID.
- `author-names.txt`: raw (entity-escaped) author strings used to select person records.
