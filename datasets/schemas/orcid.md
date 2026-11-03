# ORCID schema note

- Source: ORCID anonymous Public API `GET https://pub.orcid.org/v3.0/{orcid}/record` with `Accept: application/json`.
- Schema version: `orcid-api-v3.0/record`.
- Raw responses are stored gzip-compressed and unmodified in `scratch/datasets/orcid/<mode>/raw/{orcid}.json.gz`, then combined into `records.jsonl.gz`. The minimized projection of real records is written to `scratch/datasets/orcid/sample/orcid-sample.minimized.jsonl` for review only.
- The Public API returns only data that the researcher marked public. Nullability: every section except the iD may be empty or null.

## Committed samples (human ruling, phase-03 repair-01, Decision 1)

Real ORCID records are never committed. `datasets/samples/orcid/` holds only these two files, and `datasets/scripts/validate.sh` fails on anything else.

| File | Records | Content |
|---|---|---|
| `orcid-fictional-test-record.jsonl` | 1 | The projection below of ORCID's fictional demonstration record `0000-0002-1825-0097` (Josiah Carberry), fetched by `fetch_orcid.sh` in SAMPLE mode into `scratch/datasets/orcid/test-record/` and built by `build_samples.sh`. Extra top-level fields: `sample_kind` = `orcid-fictional-test-record`, `synthetic` = `false`, `source_url`, and `retrieved_utc`. |
| `synthetic-orcid-records.jsonl` | 3 | Hand-authored records with the same projection fields. Extra top-level fields: `sample_kind` = `synthetic`, `synthetic` = `true`, and `synthetic_note`. `orcid` is `synthetic:<name>`, which never matches the iD pattern. `work_dois` come only from `datasets/samples/dblp/dois.txt`. `synthetic:orcid-a` and `synthetic:orcid-b` share one DOI, so they look like coauthors. `last_modified_ms` is `null`. |

The validation rules for every committed record are as follows. If `orcid` equals `orcid.fictional_test_record_id` in the config, the record must carry the test-record label and sit in a `fictional-test-record` file. Otherwise it must carry `synthetic: true` and `sample_kind: synthetic`, sit in a file whose name contains `synthetic`, use a `synthetic:` iD, contain no ORCID-iD-shaped string anywhere, share no name with any author in the committed DBLP, OpenAlex, or Crossref samples, and list only non-empty DBLP-sample DOIs.

The synthetic records attach fictitious names to real DBLP works. This name mismatch is deliberate, and the records are not evidence about any real author.

ScholarChain uses only the fields below. Biography, emails, addresses, keywords, and researcher URLs are never used and are dropped by the projection.

| Projection field | Record path | Type | Nullable | Linkage |
|---|---|---|---|---|
| `orcid` | `orcid-identifier.path` | ORCID iD `NNNN-NNNN-NNNN-NNNX` | no | ORCID (source record ID) |
| `name.given` | `person.name.given-names.value` | string | yes | name evidence |
| `name.family` | `person.name.family-name.value` | string | yes | name evidence |
| `name.credit` | `person.name.credit-name.value` | string | yes | name evidence |
| `other_names[]` | `person.other-names.other-name[].content` | string | may be empty | alias evidence |
| `external_ids[]` | `person.external-identifiers.external-identifier[]` (`external-id-type`, `external-id-value`) | object (for example Scopus Author ID) | may be empty | cross-identifiers |
| `employments[]` | `activities-summary.employments.affiliation-group[].summaries[].employment-summary` | `{org, country, org_id{source,id}, department, role, start_year, end_year}` | may be empty | affiliation evidence (ROR, GRID, RINGGOLD, FUNDREF via `org_id`) |
| `work_dois[]` | `activities-summary.works.group[].external-ids.external-id[]` where `external-id-type` = `doi` | lower-case DOI strings, distinct | may be empty | DOI overlap with DBLP works (strong identity evidence) |
| `last_modified_ms` | `history.last-modified-date.value` | epoch milliseconds | yes | staleness and provenance |

Linkage keys: ORCID (exact), plus DOI overlap between `work_dois` and the DBLP, OpenAlex, and Crossref works of the candidate.
