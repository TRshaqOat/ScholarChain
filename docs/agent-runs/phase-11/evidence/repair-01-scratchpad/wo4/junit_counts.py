"""Per-project JUnit XML counts for report files modified at or after a start time.

Usage: python3 -I junit_counts.py REPO_ROOT START_EPOCH_SECONDS
Projects: modules/*/target/test-reports and tests/target/test-reports. Files older than the start
(for example a deleted suite's report from an earlier run) are listed as stale and not counted.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root, start = sys.argv[1], float(sys.argv[2])
dirs = sorted(glob.glob(os.path.join(root, "modules", "*", "target", "test-reports"))) + [
    os.path.join(root, "tests", "target", "test-reports")
]
tot = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
for d in dirs:
    proj = os.path.relpath(d, root).split(os.sep)
    name = proj[1] if proj[0] == "modules" else "tests"
    c = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    stale = []
    for f in sorted(glob.glob(os.path.join(d, "*.xml"))):
        if os.path.getmtime(f) < start:
            stale.append(os.path.basename(f))
            continue
        s = ET.parse(f).getroot()
        c["suites"] += 1
        for k in ("tests", "failures", "errors", "skipped"):
            c[k] += int(s.get(k, "0"))
    for k in tot:
        tot[k] += c[k]
    print(f"{name}: suites {c['suites']}, tests {c['tests']}, failures {c['failures']}, "
          f"errors {c['errors']}, skipped {c['skipped']}" + (f" (stale, not counted: {stale})" if stale else ""))
print(f"TOTAL: suites {tot['suites']}, tests {tot['tests']}, failures {tot['failures']}, "
      f"errors {tot['errors']}, skipped {tot['skipped']}")
