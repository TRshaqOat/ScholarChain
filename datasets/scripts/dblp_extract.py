#!/usr/bin/env python3
"""Stream a decompressed dblp.xml on stdin and extract the ScholarChain subset.

Python 3 standard library only. Records are copied byte-for-byte (ISO-8859-1 is a
lossless byte mapping), so character entities such as &uuml; stay intact and the
output remains valid against dblp.dtd.

Pass "works": keep article/inproceedings records whose key starts with a configured
venue prefix and whose <year> is inside the configured range. Writes works.xml,
dois.txt, orcids-works.txt, author-names.txt, stats-works.json.

Pass "persons": keep <www key="homepages/..."> person records whose <author> names
appear in author-names.txt. Writes persons.xml, orcids-persons.txt, stats-persons.json.

Truncated input (sample mode reads a byte-range prefix) is tolerated: an incomplete
final record is simply not matched.
"""
import argparse
import html
import json
import os
import re
import sys

REC = re.compile(
    r"<(article|inproceedings|proceedings|book|incollection|phdthesis|mastersthesis|www|data)\s[^>]*>.*?</\1>",
    re.S,
)
KEY = re.compile(r'\skey="([^"]+)"')
YEAR = re.compile(r"<year>(\d{4})</year>")
AUTHOR = re.compile(r"<author(\s[^>]*)?>(.*?)</author>", re.S)
ORCID_ATTR = re.compile(r'orcid="([0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X])"')
ORCID_URL = re.compile(r"<url(?:\s[^>]*)?>https?://orcid\.org/([0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X])</url>")
DOI_EE = re.compile(r"<ee(?:\s[^>]*)?>https?://(?:dx\.)?doi\.org/([^<\s]+)</ee>")
CHUNK = 1 << 22


def orcid_checksum_ok(oid):
    digits = oid.replace("-", "")
    total = 0
    for ch in digits[:-1]:
        total = (total + int(ch)) * 2
    check = (12 - total % 11) % 11
    return digits[-1] == ("X" if check == 10 else str(check))


def records(stream):
    """Yield (prologue_or_None, record_type, record_text). Prologue is yielded once with the first record."""
    buf = ""
    prologue = None
    while True:
        chunk = stream.read(CHUNK)
        if chunk:
            buf += chunk.decode("latin-1")
        pos = 0
        for m in REC.finditer(buf):
            if prologue is None:
                prologue = buf[: m.start()]
                yield prologue, m.group(1), m.group(0)
            else:
                yield None, m.group(1), m.group(0)
            pos = m.end()
        buf = buf[pos:]
        if not chunk:
            return


def write_latin1(path, text):
    with open(path + ".part", "w", encoding="latin-1", newline="") as f:
        f.write(text)
    os.replace(path + ".part", path)


def write_lines(path, items):
    write_latin1(path, "".join(x + "\n" for x in sorted(items)))


class XmlOut:
    def __init__(self, path):
        self.path = path
        self.f = open(path + ".part", "w", encoding="latin-1", newline="")
        self.header_done = False

    def header(self, prologue):
        if not self.header_done:
            self.f.write(prologue.rstrip() + "\n")
            self.header_done = True

    def close(self):
        if not self.header_done:
            self.f.write('<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n')
        self.f.write("</dblp>\n")
        self.f.close()
        os.replace(self.path + ".part", self.path)


def pass_works(cfg, stream, out, max_records):
    d = cfg["dblp"]
    prefixes = tuple(v["key_prefix"] for v in d["venues"])
    types = set(d["record_types"])
    ymin, ymax = int(d["year_min"]), int(d["year_max"])
    xml = XmlOut(os.path.join(out, "works.xml"))
    dois, orcids, names = set(), set(), set()
    stats = {"records_scanned": 0, "subset_matched": 0, "written": 0, "written_with_doi": 0,
             "bad_orcid_checksum": 0, "by_venue": {}, "by_year": {}, "by_type": {}}
    prologue_seen = None
    for prologue, rtype, text in records(stream):
        if prologue is not None:
            prologue_seen = prologue
        stats["records_scanned"] += 1
        if rtype not in types:
            continue
        km = KEY.search(text)
        if not km or not km.group(1).startswith(prefixes):
            continue
        ym = YEAR.search(text)
        if not ym or not ymin <= int(ym.group(1)) <= ymax:
            continue
        stats["subset_matched"] += 1
        if max_records and stats["written"] >= max_records:
            continue
        xml.header(prologue_seen)
        xml.f.write(text + "\n")
        stats["written"] += 1
        venue = next(p for p in prefixes if km.group(1).startswith(p)).rstrip("/")
        stats["by_venue"][venue] = stats["by_venue"].get(venue, 0) + 1
        stats["by_year"][ym.group(1)] = stats["by_year"].get(ym.group(1), 0) + 1
        stats["by_type"][rtype] = stats["by_type"].get(rtype, 0) + 1
        rec_dois = {html.unescape(x).strip().lower() for x in DOI_EE.findall(text)}
        if rec_dois:
            stats["written_with_doi"] += 1
        dois |= rec_dois
        for attrs, name in AUTHOR.findall(text):
            names.add(name.strip())
            om = ORCID_ATTR.search(attrs or "")
            if om:
                if orcid_checksum_ok(om.group(1)):
                    orcids.add(om.group(1))
                else:
                    stats["bad_orcid_checksum"] += 1
    xml.close()
    stats["distinct_dois"] = len(dois)
    stats["distinct_orcids_on_author_elements"] = len(orcids)
    stats["distinct_author_names"] = len(names)
    write_lines(os.path.join(out, "dois.txt"), dois)
    write_lines(os.path.join(out, "orcids-works.txt"), orcids)
    write_lines(os.path.join(out, "author-names.txt"), names)
    write_latin1(os.path.join(out, "stats-works.json"), json.dumps(stats, indent=2, sort_keys=True) + "\n")
    return stats


def pass_persons(stream, out):
    with open(os.path.join(out, "author-names.txt"), encoding="latin-1") as f:
        names = {line.rstrip("\n") for line in f if line.strip()}
    xml = XmlOut(os.path.join(out, "persons.xml"))
    orcids = set()
    stats = {"records_scanned": 0, "person_records_written": 0, "bad_orcid_checksum": 0}
    prologue_seen = None
    for prologue, rtype, text in records(stream):
        if prologue is not None:
            prologue_seen = prologue
        stats["records_scanned"] += 1
        if rtype != "www":
            continue
        km = KEY.search(text)
        if not km or not km.group(1).startswith("homepages/"):
            continue
        if not any(n.strip() in names for _, n in AUTHOR.findall(text)):
            continue
        xml.header(prologue_seen)
        xml.f.write(text + "\n")
        stats["person_records_written"] += 1
        for oid in ORCID_URL.findall(text):
            if orcid_checksum_ok(oid):
                orcids.add(oid)
            else:
                stats["bad_orcid_checksum"] += 1
    xml.close()
    stats["distinct_orcids_on_person_records"] = len(orcids)
    write_lines(os.path.join(out, "orcids-persons.txt"), orcids)
    write_latin1(os.path.join(out, "stats-persons.json"), json.dumps(stats, indent=2, sort_keys=True) + "\n")
    return stats


def self_test():
    import io
    import tempfile
    cfg = {"dblp": {"venues": [{"key_prefix": "conf/ics/"}], "record_types": ["article", "inproceedings"],
                    "year_min": 2000, "year_max": 2026}}
    body = (
        '<?xml version="1.0" encoding="ISO-8859-1"?>\n<!DOCTYPE dblp SYSTEM "dblp.dtd">\n<dblp>\n'
        '<inproceedings mdate="2020-01-01" key="conf/ics/A20"><author orcid="0000-0002-1825-0097">J&uuml;rgen A</author>'
        "<title>Multi\nline.</title><year>2020</year><booktitle>ICS</booktitle>"
        "<ee>https://doi.org/10.1145/ABC.1</ee></inproceedings>"
        '<inproceedings mdate="2020-01-01" key="conf/ics/Old99"><author>B</author><year>1999</year></inproceedings>\n'
        '<article mdate="2020-01-01" key="journals/other/X"><author>C</author><year>2010</year></article>\n'
        '<www mdate="2020-01-01" key="homepages/1/2"><author>J&uuml;rgen A</author><title>Home Page</title>'
        "<url>https://orcid.org/0000-0002-1825-0097</url></www>\n"
        '<inproceedings mdate="2020-01-01" key="conf/ics/Trunc"><author>T</author>'
    ).encode("latin-1")
    global CHUNK
    saved, CHUNK = CHUNK, 7  # force records to straddle chunk boundaries
    try:
        with tempfile.TemporaryDirectory() as tmp:
            s = pass_works(cfg, io.BytesIO(body), tmp, 0)
            assert s["subset_matched"] == 1 and s["written"] == 1, s
            assert s["records_scanned"] == 4, s  # the truncated final record is not matched
            assert open(os.path.join(tmp, "dois.txt")).read() == "10.1145/abc.1\n"
            assert open(os.path.join(tmp, "orcids-works.txt")).read() == "0000-0002-1825-0097\n"
            works = open(os.path.join(tmp, "works.xml"), encoding="latin-1").read()
            assert works.startswith('<?xml') and "J&uuml;rgen" in works and works.endswith("</dblp>\n")
            p = pass_persons(io.BytesIO(body), tmp)
            assert p["person_records_written"] == 1 and p["distinct_orcids_on_person_records"] == 1, p
    finally:
        CHUNK = saved
    assert orcid_checksum_ok("0000-0002-1825-0097") and not orcid_checksum_ok("0000-0002-1825-0098")
    print("dblp_extract self-test: OK")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pass", dest="which", choices=["works", "persons"])
    ap.add_argument("--config")
    ap.add_argument("--out")
    ap.add_argument("--max-records", type=int, default=0, help="0 means no cap")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        self_test()
        return 0
    if not a.which or not a.out:
        ap.error("--pass and --out are required")
    if a.which == "works":
        with open(a.config, encoding="utf-8") as f:
            cfg = json.load(f)
        s = pass_works(cfg, sys.stdin.buffer, a.out, a.max_records)
    else:
        s = pass_persons(sys.stdin.buffer, a.out)
    print(json.dumps({k: v for k, v in s.items() if not isinstance(v, dict)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
