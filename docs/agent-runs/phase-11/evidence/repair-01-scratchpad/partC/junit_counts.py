"""Sums the JUnit XML reports per project (run from the repo root) and lists every failed or errored
testcase by suite and name."""
import glob, os, xml.etree.ElementTree as ET

total = [0, 0, 0, 0, 0]
failed = []
dirs = sorted(glob.glob("modules/*/target/test-reports")) + sorted(glob.glob("tests/target/test-reports"))
for d in dirs:
    name = d.split("/")[1] if d.startswith("modules/") else "tests"
    t = [0, 0, 0, 0, 0]
    for f in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
        r = ET.parse(f).getroot()
        t[0] += int(r.get("tests")); t[1] += int(r.get("failures")); t[2] += int(r.get("errors"))
        t[3] += int(r.get("skipped") or 0); t[4] += 1
        for c in r.iter("testcase"):
            for k in ("failure", "error"):
                e = c.find(k)
                if e is not None:
                    msg = (e.get("message") or "").replace("\n", " ")[:160]
                    failed.append(f"{k}: {c.get('classname')} :: {c.get('name')} :: {msg}")
    total = [a + b for a, b in zip(total, t)]
    print(f"{name}: tests {t[0]} failures {t[1]} errors {t[2]} skipped {t[3]} suites {t[4]}")
print(f"TOTAL: tests {total[0]} failures {total[1]} errors {total[2]} skipped {total[3]} suites {total[4]}")
for line in failed:
    print(line)
