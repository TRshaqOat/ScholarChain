"""Count JUnit XML reports written by one sbt check run.

Usage: python3 -I junit_counts.py START_ISO_UTC END_ISO_UTC
Reads */target/test-reports/TEST-*.xml under modules/ and tests/ of the current directory. Only
reports whose modification time lies within [START, END] are counted; older ones are listed as
stale and not counted. Prints per-project suites, tests, failures, errors, skipped, and totals,
plus the names of every skipped (pending) test case.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone


def ts(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()


start, end = ts(sys.argv[1]), ts(sys.argv[2])
paths = sorted(
    glob.glob("modules/*/target/test-reports/TEST-*.xml")
    + glob.glob("tests/target/test-reports/TEST-*.xml")
)
per = {}
stale = []
skipped_cases = []
for p in paths:
    m = os.path.getmtime(p)
    if not (start - 1 <= m <= end + 1):
        stale.append(p)
        continue
    project = p.split("/target/")[0]
    r = ET.parse(p).getroot()
    agg = per.setdefault(project, [0, 0, 0, 0, 0])
    agg[0] += 1
    agg[1] += int(r.get("tests", 0))
    agg[2] += int(r.get("failures", 0))
    agg[3] += int(r.get("errors", 0))
    agg[4] += int(r.get("skipped", 0))
    for tc in r.iter("testcase"):
        if tc.find("skipped") is not None:
            skipped_cases.append(f"{tc.get('classname')} :: {tc.get('name')}")
tot = [0, 0, 0, 0, 0]
for k in sorted(per):
    v = per[k]
    print(f"{k}: suites {v[0]}, tests {v[1]}, failures {v[2]}, errors {v[3]}, skipped {v[4]}")
    tot = [a + b for a, b in zip(tot, v)]
print(f"TOTAL: suites {tot[0]}, tests {tot[1]}, failures {tot[2]}, errors {tot[3]}, skipped {tot[4]}")
print("SKIPPED (pending) cases:")
for s in skipped_cases:
    print("  " + s)
print("STALE (not counted):")
for s in stale:
    print("  " + s)
