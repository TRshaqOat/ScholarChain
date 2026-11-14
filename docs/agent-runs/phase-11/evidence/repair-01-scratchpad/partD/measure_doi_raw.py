"""Ruling 15 measurement for D8, read-only over the raw inputs of every existing Hadoop run.

For each DOI string that reaches StableIds.normalizeDoi in the Hadoop jobs (OpenAlex "doi",
Crossref "DOI", ORCID "work_dois"[], DBLP <ee> text), apply the old rule and the new rule
(Java transcriptions) and count the values whose result differs. Every stored DOI, doi: work ID,
mention ID, and exact-identifier key is derived from these results, so zero differences means no
existing stable ID, normalized key, or stored record changes.

Usage: python3 -I measure_doi_raw.py <repo root>
"""
import gzip
import html
import json
import os
import re
import sys

PREFIXES = ["https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/", "doi:"]
DBLP_DOI_URL = PREFIXES[:4]


def jtrim(s):  # java.lang.String.trim: strip chars <= U+0020 at both ends
    i, j = 0, len(s)
    while i < j and ord(s[i]) <= 0x20:
        i += 1
    while j > i and ord(s[j - 1]) <= 0x20:
        j -= 1
    return s[i:j]


def old(doi):  # StableIds.normalizeDoi before repair-01 Part D
    d = jtrim(doi).lower()
    for p in PREFIXES:
        if d.startswith(p):
            return d[len(p):]
    return d


def new(doi):  # StableIds.normalizeDoi after repair-01 Part D (strip and trim until stable)
    d = doi.lower()
    while True:
        d = jtrim(d)
        p = next((p for p in PREFIXES if d.startswith(p)), None)
        if p is None:
            return d
        d = d[len(p):]


def opener(path):
    return gzip.open(path, "rt", encoding="utf-8") if path.endswith(".gz") else open(path, encoding="utf-8")


def jsonl_values(path, extract):
    records = 0
    with opener(path) as f:
        for line in f:
            if not line.strip():
                continue
            records += 1
            for v in extract(json.loads(line)):
                yield records, v


def top(field):
    def ex(j):
        v = j.get(field) if isinstance(j, dict) else None
        if isinstance(v, str) and v.strip():
            yield v.strip()
    return ex


def at(v, *path):
    for k in path:
        v = v.get(k) if isinstance(v, dict) else None
    return v


def orcid_dois(j):
    if not isinstance(j, dict):
        return
    if "orcid-identifier" in j:  # full v3 record, as SourceNormalizers.projectV3 reads it
        for g in at(j, "activities-summary", "works", "group") or []:
            for x in at(g, "external-ids", "external-id") or []:
                t, v = at(x, "external-id-type"), at(x, "external-id-value")
                if isinstance(t, str) and t.strip() == "doi" and isinstance(v, str) and v.strip():
                    yield v.strip().lower()
    else:
        for v in j.get("work_dois") or []:
            if isinstance(v, str):
                yield v


EE = re.compile(r"<ee\b[^>]*>(.*?)</ee>", re.S)
TAG = re.compile(r"<[^>]+>")


def dblp_values(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for m in EE.finditer(text):
        e = jtrim(html.unescape(TAG.sub("", m.group(1))))
        yield e


def measure(label, path, values, doi_filter=None):
    total = changed = 0
    records = 0
    examples = []
    for item in values:
        if isinstance(item, tuple):
            records, v = item
        else:
            v = item
        if doi_filter and not doi_filter(v):
            continue
        total += 1
        if old(v) != new(v):
            changed += 1
            if len(examples) < 5:
                examples.append(repr(v[:80]))
    print(f"{label}\t{path}\trecords={records if records else '-'}\tdoi_values={total}\tchanged={changed}\t{'; '.join(examples)}")
    sys.stdout.flush()
    return changed


def main(root):
    os.chdir(root)
    is_doi_url = lambda e: any(e.lower().startswith(p) for p in DBLP_DOI_URL)
    jobs = [
        ("openalex", "scratch/datasets/openalex/full/works.jsonl", top("doi")),
        ("crossref", "scratch/datasets/crossref/full/works.jsonl", top("DOI")),
        ("orcid", "scratch/datasets/orcid/full/records.jsonl.gz", orcid_dois),
        ("openalex", "datasets/samples/openalex/works-sample.jsonl", top("doi")),
        ("crossref", "datasets/samples/crossref/works-sample.jsonl", top("DOI")),
        ("orcid", "datasets/samples/orcid/synthetic-orcid-records.jsonl", orcid_dois),
        ("orcid", "datasets/samples/orcid/orcid-fictional-test-record.jsonl", orcid_dois),
    ]
    changed = 0
    for label, path, ex in jobs:
        changed += measure(label, path, jsonl_values(path, ex))
    for path in ["scratch/datasets/dblp/snapshot=2026-10-03/full/works.xml", "datasets/samples/dblp/dblp-sample.xml"]:
        changed += measure("dblp-ee-doi-url", path, dblp_values(path), is_doi_url)
        changed += measure("dblp-ee-any", path, dblp_values(path))
    print(f"TOTAL_CHANGED\t{changed}")


if __name__ == "__main__":
    main(sys.argv[1])
