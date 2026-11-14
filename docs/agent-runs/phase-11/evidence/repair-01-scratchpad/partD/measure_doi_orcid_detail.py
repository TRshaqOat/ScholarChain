"""D8 detail for the full ORCID input: per record, would the stored workDoi.N list change?

Reproduces SourceNormalizers.projectV3 + orcidRecord: DOI external-id values, trimmed, lower-cased,
distinct, sorted, then normalizeDoi per value, distinct again, numbered workDoi.1..n.
Prints counts only (no ORCID iDs).

Usage: python3 -I measure_doi_orcid_detail.py <repo root>
"""
import gzip
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from measure_doi_raw import at, new, old  # noqa: E402


def dedup(xs):
    seen, out = set(), []
    for x in xs:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


def main(root):
    os.chdir(root)
    records = with_dois = changed_records = renumbered = values_changed = 0
    with gzip.open("scratch/datasets/orcid/full/records.jsonl.gz", "rt", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            records += 1
            j = json.loads(line)
            raw = []
            for g in at(j, "activities-summary", "works", "group") or []:
                for x in at(g, "external-ids", "external-id") or []:
                    t, v = at(x, "external-id-type"), at(x, "external-id-value")
                    if isinstance(t, str) and t.strip() == "doi" and isinstance(v, str) and v.strip():
                        raw.append(v.strip().lower())
            raw = sorted(set(raw))
            if not raw:
                continue
            with_dois += 1
            o, n = dedup(map(old, raw)), dedup(map(new, raw))
            if o != n:
                changed_records += 1
                values_changed += sum(1 for r in raw if old(r) != new(r))
                if len(o) != len(n) or any(a != b for a, b in zip(o, n) if old(a) == a):
                    renumbered += 1
    print(f"orcid_records={records}\trecords_with_doi={with_dois}\trecords_whose_workDoi_list_changes={changed_records}"
          f"\tvalues_changed={values_changed}\trecords_whose_list_length_or_numbering_changes={renumbered}")


if __name__ == "__main__":
    main(sys.argv[1])
