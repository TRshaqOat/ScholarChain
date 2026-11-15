#!/usr/bin/env python3
"""Deterministic, nested size-scaling fractions of the ScholarChain inputs (rule fraction-v1).

Python 3 standard library only. Called by make_fraction.sh; run with python3 -I.

make:   keep a DBLP record when int(sha256("fraction-v1|" + key)[:8], 16) % 100 < p. Records are copied
        byte-for-byte (same reader and writer as dblp_extract.py). OpenAlex and Crossref lines are kept when
        their DOI is on a kept DBLP record. ORCID records are kept when their iD (orcid-identifier.path)
        appears as an author iD on a kept DBLP, OpenAlex, or Crossref record, the exact-key linkage the
        Hadoop pipeline uses (J03). Every rule is monotone in p, so fraction 25 is inside 50 inside 100.
verify: parse every output (DBLP with expat and the DTD, JSONL line by line, ORCID through gzip), check the
        DBLP rule exactly against the full input, check linkage, and check record-level nesting
        (every output record is byte-identical to a record of the next larger fraction, up to the full input).
"""
import argparse
import gzip
import hashlib
import html
import importlib.util
import json
import os
import platform
import re
import sys
import zlib
from datetime import datetime, timezone

RULE_VERSION = "fraction-v1"
RULE = ('keep a DBLP record when int(first 8 hex digits of sha256("fraction-v1|" + key), 16) mod 100 < p; '
        "OpenAlex and Crossref lines whose lower-cased DOI is on a kept DBLP record; ORCID records whose "
        "orcid-identifier.path appears as an author iD on a kept DBLP, OpenAlex, or Crossref record")
ID = re.compile(r"[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]")
NAMES = {"dblp": "dblp/works.xml", "openalex": "openalex/works.jsonl", "crossref": "crossref/works.jsonl",
         "orcid": "orcid/records.jsonl.gz"}

sys.dont_write_bytecode = True  # python3 -I does not imply -B: no __pycache__ under datasets/scripts (code review 1 F2)
_spec = importlib.util.spec_from_file_location("dblp_extract", os.path.join(os.path.dirname(os.path.abspath(__file__)), "dblp_extract.py"))
dx = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dx)


def bucket(key):
    return int(hashlib.sha256(f"{RULE_VERSION}|{key}".encode("utf-8")).hexdigest()[:8], 16) % 100


def dblp_records(path):
    """Yield (prologue_or_None, key, text) for every record of a works.xml."""
    with open(path, "rb") as f:
        for prologue, _, text in dx.records(f):
            km = dx.KEY.search(text)
            if not km:
                raise ValueError(f"{path}: record without key: {text[:80]!r}")
            yield prologue, km.group(1), text


def dblp_dois(text):
    return {html.unescape(x).strip().lower() for x in dx.DOI_EE.findall(text)}


def oa_doi(w):
    return (w.get("doi") or "").lower().removeprefix("https://doi.org/")


def oa_ids(w):
    return {m.group(0) for a in w.get("authorships") or [] for m in [ID.search(((a or {}).get("author") or {}).get("orcid") or "")] if m}


def cr_ids(w):
    return {m.group(0) for a in w.get("author") or [] for m in [ID.search((a or {}).get("ORCID") or "")] if m}


def orcid_path(r):
    return (r.get("orcid-identifier") or {}).get("path")


def jsonl(path, opener=open):
    with opener(path, "rb") as f:
        for line in f:
            if line.strip():
                yield line, json.loads(line)


def make(p, src, out, source_meta):
    os.makedirs(os.path.join(out, "dblp"), exist_ok=True)
    counts = {k: {"source_records": 0, "kept_records": 0} for k in NAMES}
    xml = dx.XmlOut(os.path.join(out, NAMES["dblp"]))
    dois, ids = set(), set()
    for prologue, key, text in dblp_records(src["dblp"]):
        if prologue is not None:
            xml.header(prologue)
        counts["dblp"]["source_records"] += 1
        if bucket(key) < p:
            xml.f.write(text + "\n")
            counts["dblp"]["kept_records"] += 1
            dois |= dblp_dois(text)
            ids |= {m.group(1) for attrs, _ in dx.AUTHOR.findall(text) for m in [dx.ORCID_ATTR.search(attrs or "")] if m}
    xml.close()
    counts["dblp"]["kept_distinct_dois"] = len(dois)

    for name, doi_of, ids_of in (("openalex", oa_doi, oa_ids), ("crossref", lambda w: (w.get("DOI") or "").lower(), cr_ids)):
        dest = os.path.join(out, NAMES[name])
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest + ".part", "wb") as o:
            for line, w in jsonl(src[name]):
                counts[name]["source_records"] += 1
                if doi_of(w) in dois:
                    o.write(line if line.endswith(b"\n") else line + b"\n")
                    counts[name]["kept_records"] += 1
                    ids |= ids_of(w)
        os.replace(dest + ".part", dest)
    counts["orcid"]["linked_ids_on_kept_records"] = len(ids)

    dest = os.path.join(out, NAMES["orcid"])
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest + ".part", "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6) as o:
        for line, r in jsonl(src["orcid"], gzip.open):
            counts["orcid"]["source_records"] += 1
            if orcid_path(r) in ids:
                o.write(line if line.endswith(b"\n") else line + b"\n")
                counts["orcid"]["kept_records"] += 1
    os.replace(dest + ".part", dest)

    for k, c in counts.items():
        c["kept_percent"] = round(100.0 * c["kept_records"] / c["source_records"], 3) if c["source_records"] else None
        c.update(source_meta.get(k, {}))
        c["output"] = NAMES[k]
        c["output_bytes"] = os.path.getsize(os.path.join(out, NAMES[k]))
    info = {"dataset": "scholarchain-size-fraction", "fraction": p, "rule_version": RULE_VERSION, "rule": RULE,
            "dblp_persons": "not produced: scripts/run-bounded-pipeline.sh uploads no persons file and modules/hadoop/JOBS.md says persons.xml is not read",
            "dtd": source_meta.get("dtd", {}), "inputs": counts, "tool": "datasets/scripts/make_fraction.sh",
            "python": platform.python_version(), "zlib": zlib.ZLIB_VERSION, "gzip": {"mtime": 0, "compresslevel": 6},
            "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    with open(os.path.join(out, "run-info.json.part"), "w", encoding="utf-8") as f:
        json.dump(info, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(os.path.join(out, "run-info.json.part"), os.path.join(out, "run-info.json"))
    print(json.dumps({k: {"source": c["source_records"], "kept": c["kept_records"], "percent": c["kept_percent"]} for k, c in counts.items()}, sort_keys=True))
    return counts


def parse_xml(path, dtd):
    """expat with the external DTD loaded only from the given path (as validate.sh checks the sample)."""
    import xml.parsers.expat as expat
    parser = expat.ParserCreate()
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_ALWAYS)
    n = {"n": 0}

    def ext(context, base, system_id, public_id):
        sub = parser.ExternalEntityParserCreate(context)
        with open(dtd, "rb") as d:
            sub.ParseFile(d)
        return 1

    def start(name, attrs):
        if name in ("article", "inproceedings"):
            n["n"] += 1
    parser.ExternalEntityRefHandler = ext
    parser.StartElementHandler = start
    with open(path, "rb") as f:
        parser.ParseFile(f)
    return n["n"]


def digest(b):
    return hashlib.sha256(b).digest()


def load_level(paths):
    """Record digests, keys, and linkage sets of one level (a fraction directory or the full inputs)."""
    lv = {"dblp": {}, "dois": set(), "ids": set(), "rows": {}, "unlinked": {}}
    for _, key, text in dblp_records(paths["dblp"]):
        lv["dblp"][key] = digest(text.encode("latin-1"))
        lv["dois"] |= dblp_dois(text)
        lv["ids"] |= {m.group(1) for attrs, _ in dx.AUTHOR.findall(text) for m in [dx.ORCID_ATTR.search(attrs or "")] if m}
    checks = (("openalex", open, lambda w: oa_doi(w) in lv["dois"], oa_ids),
              ("crossref", open, lambda w: (w.get("DOI") or "").lower() in lv["dois"], cr_ids),
              ("orcid", gzip.open, lambda r: orcid_path(r) in lv["ids"], lambda r: set()))
    for name, opener, linked, ids_of in checks:  # orcid last: lv["ids"] then holds every linked iD
        lv[name], lv["rows"][name], lv["unlinked"][name] = set(), 0, 0
        for line, w in jsonl(paths[name], opener):
            lv[name].add(digest(line.rstrip(b"\n")))
            lv["rows"][name] += 1
            lv["unlinked"][name] += not linked(w)
            if name != "orcid":
                lv["ids"] |= ids_of(w)
    return lv


def verify(full, dirs, dtd):
    fails = 0

    def report(ok, msg):
        nonlocal fails
        print(("PASS " if ok else "FAIL ") + msg)
        fails += 0 if ok else 1
    big = load_level(full)
    print(f"INFO full: dblp {len(big['dblp'])} records; rows {big['rows']}; not linked by the fraction rule at 100 percent {big['unlinked']}")
    levels = []
    for d in dirs:
        p = int(re.search(r"fraction=(\d+)", d).group(1))
        paths = {k: os.path.join(d, v) for k, v in NAMES.items()}
        n_xml = parse_xml(paths["dblp"], dtd)
        lv = load_level(paths)
        report(n_xml == len(lv["dblp"]) > 0, f"fraction={p} dblp/works.xml parses with expat and the DTD: {n_xml} records")
        for k in ("openalex", "crossref", "orcid"):
            report(lv["rows"][k] > 0, f"fraction={p} {NAMES[k]} parses as JSON per line: {lv['rows'][k]} records")
        expect = {k for k in big["dblp"] if bucket(k) < p}
        report(set(lv["dblp"]) == expect, f"fraction={p} dblp keys equal the rule applied to the full input: {len(lv['dblp'])} kept, {len(expect)} expected")
        u = lv["unlinked"]
        report(u["openalex"] == 0 and u["crossref"] == 0, f"fraction={p} every OpenAlex and Crossref DOI is on a kept DBLP record (unlinked: openalex {u['openalex']}, crossref {u['crossref']})")
        report(u["orcid"] == 0, f"fraction={p} every ORCID iD appears on a kept record (unlinked: {u['orcid']})")
        levels.append((f"fraction={p}", lv))
    levels.append(("full", big))
    for (a, la), (b, lb) in zip(levels, levels[1:]):
        report(set(la["dblp"]) <= set(lb["dblp"]) and all(lb["dblp"][k] == v for k, v in la["dblp"].items()),
               f"nesting dblp {a} within {b}: {len(la['dblp'])} of {len(lb['dblp'])} keys, byte-identical records")
        for k in ("openalex", "crossref", "orcid"):
            report(la[k] <= lb[k], f"nesting {k} {a} within {b}: {len(la[k])} of {len(lb[k])} distinct records, byte-identical lines")
    return fails


def self_test():
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        keys = [f"conf/x/K{i}" for i in range(400)]
        recs = "".join(f'<inproceedings mdate="2020-01-01" key="{k}"><author orcid="0000-0000-0001-{i:04d}">A&uuml;</author>'
                       f"<year>2020</year><ee>https://doi.org/10.1/X{i}</ee></inproceedings>\n" for i, k in enumerate(keys))
        src = {k: os.path.join(tmp, "src-" + k) for k in NAMES}
        with open(src["dblp"], "w", encoding="latin-1") as f:
            f.write('<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n' + recs + "</dblp>\n")
        with open(os.path.join(tmp, "dblp.dtd"), "w") as f:
            f.write('<!ELEMENT dblp ANY><!ENTITY uuml "&#252;">\n')
        with open(src["openalex"], "w") as f:
            f.writelines(json.dumps({"id": f"W{i}", "doi": f"https://doi.org/10.1/x{i}",
                                     "authorships": [{"author": {"orcid": f"https://orcid.org/0000-0000-0002-{i:04d}"}}]}) + "\n" for i in range(400))
            f.write(json.dumps({"id": "Wnone", "doi": "https://doi.org/10.9/unlinked"}) + "\n")
        with open(src["crossref"], "w") as f:
            f.writelines(json.dumps({"DOI": f"10.1/X{i}", "author": [{"ORCID": f"http://orcid.org/0000-0000-0003-{i:04d}"}]}) + "\n" for i in range(400))
        with gzip.open(src["orcid"], "wt") as f:
            for i in range(400):
                for s in (1, 2, 3):
                    f.write(json.dumps({"orcid-identifier": {"path": f"0000-0000-000{s}-{i:04d}"}}) + "\n")
            f.write(json.dumps({"orcid-identifier": {"path": "0000-0000-0009-0000"}}) + "\n")
        outs = []
        for p in (25, 50):
            out = os.path.join(tmp, f"fraction={p}")
            c = make(p, src, out, {})
            kept = c["dblp"]["kept_records"]
            assert kept == sum(1 for k in keys if bucket(k) < p) and 0 < kept < 400, c
            assert c["openalex"]["kept_records"] == kept and c["crossref"]["kept_records"] == kept, c
            assert c["orcid"]["kept_records"] == 3 * kept, c
            outs.append(out)
        assert verify(src, outs, os.path.join(tmp, "dblp.dtd")) == 0
        a = set(re.findall(r'key="([^"]+)"', open(os.path.join(outs[0], NAMES["dblp"]), encoding="latin-1").read()))
        b = set(re.findall(r'key="([^"]+)"', open(os.path.join(outs[1], NAMES["dblp"]), encoding="latin-1").read()))
        assert a < b, "25 must be a strict subset of 50 on 400 keys"
        o1 = open(os.path.join(outs[0], NAMES["orcid"]), "rb").read()
        make(25, src, outs[0], {})
        assert open(os.path.join(outs[0], NAMES["orcid"]), "rb").read() == o1, "gzip output must be deterministic"
    assert bucket("conf/ics/A20") == int(hashlib.sha256(b"fraction-v1|conf/ics/A20").hexdigest()[:8], 16) % 100
    print("make_fraction self-test: OK")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--verify", action="store_true", help="verify --dirs against the full inputs")
    ap.add_argument("--fraction", type=int)
    for k in NAMES:
        ap.add_argument(f"--{k}", help=f"full {k} input")
    ap.add_argument("--dtd")
    ap.add_argument("--out")
    ap.add_argument("--meta", help="JSON object of source metadata (sha256, bytes, path) per input")
    ap.add_argument("--dirs", nargs="*", default=[], help="fraction directories, smallest first")
    a = ap.parse_args()
    if a.self_test:
        self_test()
        return 0
    src = {k: getattr(a, k) for k in NAMES}
    if not all(src.values()):
        ap.error("--dblp, --openalex, --crossref and --orcid are required")
    if a.verify:
        return 1 if verify(src, a.dirs, a.dtd) else 0
    if a.fraction not in range(1, 100) or not a.out:
        ap.error("--fraction 1..99 and --out are required")
    make(a.fraction, src, a.out, json.loads(a.meta or "{}"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
