# Size-scaling fractions schema note (rule `fraction-v1`)

- Purpose: the 25 and 50 percent inputs of the D-13 size-scaling runs (`scripts/run-bounded-pipeline.sh --fraction 25|50`). The 100 percent input is the `--full` subset itself.
- Tool: `datasets/scripts/make_fraction.sh --fraction 25|50` builds a fraction, and `--verify` checks every existing fraction. Local only, with no network. The tool verifies each source against its `SHA256SUMS`, stops when the projected output would leave less than 5 GiB free, and skips a fraction whose `SHA256SUMS` already verifies for the same sources.
- Record formats are unchanged. Every output record is a byte-identical copy of a source record, so the `dblp.md`, `openalex.md`, `crossref.md`, and `orcid.md` notes apply. The only exception is the ORCID file: it is decompressed and re-compressed (gzip level 6, mtime 0, no file name), so its lines are identical but the `.gz` bytes are new.

## Layout (`scratch/datasets/fraction=<p>/`)

| File | Content |
|---|---|
| `dblp/works.xml` | Original prologue (`<!DOCTYPE dblp SYSTEM "dblp.dtd">`), then the kept records in source order, then `</dblp>`. The DTD is not copied: the pipeline reads `scratch/datasets/dblp/snapshot=2026-10-03/raw/dblp.dtd`. |
| `openalex/works.jsonl`, `crossref/works.jsonl` | Kept lines, in source order |
| `orcid/records.jsonl.gz` | Kept ORCID API v3.0 records, in source order |
| `run-info.json` | `fraction`, `rule_version`, `rule`, the DTD source, and per input `source_path`, `source_sha256`, `source_bytes`, `source_records`, `kept_records`, `kept_percent`, `output`, and `output_bytes`. DBLP adds `kept_distinct_dois`, and ORCID adds `linked_ids_on_kept_records`. Also `python`, `zlib`, `gzip`, and `generated_utc`. |
| `SHA256SUMS` | sha256 of every other file. It is written last and marks a complete run. |

DBLP person records are not produced. The pipeline uploads no persons file, and `modules/hadoop/JOBS.md` states that `persons.xml` is not read.

## Rule

1. DBLP: keep a record when `int(sha256("fraction-v1|" + key).hexdigest()[:8], 16) % 100 < p`.
2. OpenAlex and Crossref: keep a line when its lower-cased DOI is on a kept DBLP record. OpenAlex DOIs have `https://doi.org/` removed. DBLP DOIs are html-unescaped and lower-cased from the `<ee>` doi.org links.
3. ORCID: keep a record when `orcid-identifier.path` appears as an author iD on a kept DBLP record (`<author orcid>`), OpenAlex record (`authorships[].author.orcid`), or Crossref record (`author[].ORCID`). This is the exact `orcid:<iD>` linkage of Hadoop J03.

Every rule is monotone in `p`, so each fraction nests in the next: 25 is within 50, and 50 is within 100.

## Observed counts (2026-11-14)

| Input | 100 | 25 | 50 |
|---|---|---|---|
| DBLP records | 110,867 | 27,993 (25.249%) | 55,598 (50.148%) |
| OpenAlex lines | 107,506 | 27,129 (25.235%) | 53,949 (50.182%) |
| Crossref lines | 106,726 | 26,964 (25.265%) | 53,607 (50.229%) |
| ORCID records | 22,975 | 14,905 (64.875%) | 18,388 (80.035%) |

## Known limitations

- ORCID does not scale with `p`, because a researcher is kept when any one of their works is kept.
- 3,301 full ORCID records carry an iD that appears on no subset record, so no fraction can keep them.
- 94 full OpenAlex works have a DOI that is on no DBLP record, so no fraction keeps them.
- The source files contain duplicate identical lines (OpenAlex: 107,506 lines, 107,439 distinct; ORCID: 22,975 lines, 22,914 distinct). The tool keeps them as they are.
