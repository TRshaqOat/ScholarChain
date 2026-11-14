"""Sums the JUnit XML reports per project: tests, failures, errors, skipped, suites (run from the repo root)."""
import glob, os, xml.etree.ElementTree as ET

total = [0, 0, 0, 0, 0]
for d in sorted(glob.glob("modules/*/target/test-reports") + glob.glob("tests/target/test-reports")):
    t = [0, 0, 0, 0, 0]
    for f in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
        r = ET.parse(f).getroot()
        t[0] += int(r.get("tests")); t[1] += int(r.get("failures")); t[2] += int(r.get("errors"))
        t[3] += int(r.get("skipped") or 0); t[4] += 1
    total = [a + b for a, b in zip(total, t)]
    print(f"{d.split('/')[1]}: tests {t[0]} failures {t[1]} errors {t[2]} skipped {t[3]} suites {t[4]}")
print(f"TOTAL: tests {total[0]} failures {total[1]} errors {total[2]} skipped {total[3]} suites {total[4]}")
