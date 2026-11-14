"""D8: exact count of stored ORCID workDoi.N values that an idempotent normalizeDoi would change.

A stored value x is old(raw); the new rule gives new(raw) = new(x), so x changes iff new(x) != x.
Reads j02-source-normalize/researcher-profiles of every Hadoop run. Prints counts only.

Usage: python3 -I measure_doi_stored_workdoi.py <repo root>
"""
import glob
import gzip
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from measure_doi_raw import new  # noqa: E402

FIELD = re.compile(r'"(workDoi\.\d+)":("(?:[^"\\]|\\.)*")')


def main(root):
    os.chdir(root)
    for run in sorted(glob.glob("scratch/hadoop/*/snapshot=*/run=*/j02-source-normalize/researcher-profiles")):
        lines = values = changed = records_changed = 0
        for p in sorted(glob.glob(run + "/part-*")):
            with (gzip.open(p, "rt", encoding="utf-8") if p.endswith(".gz") else open(p, encoding="utf-8")) as f:
                for line in f:
                    lines += 1
                    c = 0
                    for _, s in FIELD.findall(line):
                        x = json.loads(s)
                        values += 1
                        c += new(x) != x
                    changed += c
                    records_changed += c > 0
        print(f"{run}\tprofile_lines={lines}\tworkDoi_values={values}\tvalues_changed={changed}\tprofiles_changed={records_changed}")


if __name__ == "__main__":
    main(sys.argv[1])
