"""Ruling 15 measurement for D8 (corroboration), read-only over existing text outputs.

A stored value derived from the old normalizeDoi changes under the new rule only if, after the
old single strip, it still starts with white space or a resolver/"doi:" prefix. Such a value
shows up as:
  R1  a "doi:" ID or key whose remainder starts with white space, "http", or "doi:";
  R2  a doi / workDoi.N field whose value starts with white space, "http", or "doi:";
  R3  a doi.org URL followed by white space, "doi:", or another URL.
Counts lines (records) per area and matches per rule. Parquet is scanned by Measure.java.
The pruned paths are never entered.

Usage: python3 -I measure_doi_outputs.py <repo root>
"""
import gzip
import os
import re
import sys

ROOTS = ["scratch/hadoop", "scratch/spark", "scratch/stores", "scratch/er", "scratch/runtime"]
PRUNE = {"scratch/stores-live", "scratch/er/adjudication-live"}
# Targeted run: Hadoop jobs whose IDs all come from j01/j02 work IDs (blocks, candidates, graph
# export, decisions) and Hadoop temp files are skipped; they cannot hold a DOI j01/j02 do not.
SKIP = {"j05-blocking-key-p0", "j05-blocking-key-p1", "j05-blocking-key-p2", "j06-candidate-join",
        "j09-graph-export", "j10-match-decision", "tmp-wo5"}
WS = r"(?:\s|\\[tnrbfu])"
RULES = {
    "R1": re.compile(r"(?i)(?:^|[\"',\t\[|;])doi:(?:" + WS + r"|https?:|doi:)"),
    "R2": re.compile(r"(?i)\"(?:doi|workdoi\.\d+)\"\s*:\s*\"(?:" + WS + r"|https?:|doi:)"),
    "R3": re.compile(r"(?i)https?://(?:dx\.)?doi\.org/(?:" + WS + r"|doi:|https?:)"),
}


def files():
    for root in ROOTS:
        for d, dirs, fs in os.walk(root):
            dirs[:] = [x for x in dirs if os.path.join(d, x) not in PRUNE and x not in SKIP]
            assert d not in PRUNE
            for f in fs:
                if f.endswith(".crc") or f.endswith(".parquet") or f.startswith("."):
                    continue
                yield os.path.join(d, f)


def lines(path):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
            yield from f
    else:
        with open(path, encoding="utf-8", errors="replace") as f:
            yield from f


def main(root):
    os.chdir(root)
    per_root = {r: {"files": 0, "lines": 0, "R1": 0, "R2": 0, "R3": 0} for r in ROOTS}
    examples = []
    for path in files():
        r = next(x for x in ROOTS if path.startswith(x + "/"))
        s = per_root[r]
        s["files"] += 1
        try:
            for line in lines(path):
                s["lines"] += 1
                for k, rx in RULES.items():
                    m = rx.search(line)
                    if m:
                        s[k] += 1
                        if len(examples) < 20:
                            examples.append(f"{k}\t{path}\t{line[max(0, m.start() - 40):m.end() + 40]!r}")
        except (OSError, EOFError) as e:
            print(f"UNREADABLE\t{path}\t{e}")
    for r, s in per_root.items():
        print(f"{r}\tfiles={s['files']}\tlines={s['lines']}\tR1={s['R1']}\tR2={s['R2']}\tR3={s['R3']}")
    print("EXAMPLES" if examples else "EXAMPLES none")
    for e in examples:
        print(e)


if __name__ == "__main__":
    main(sys.argv[1])
