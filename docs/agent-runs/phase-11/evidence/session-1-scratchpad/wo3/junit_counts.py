"""Phase 11 WO3: sums the JUnit XML reports of every project (modules/* and tests), run from the
repository root. Per project: suites, testcases, failures, errors, skipped (the <skipped/> children,
which is how sbt records ScalaTest pending and ignored tests), and the suite-level attributes."""
import glob, os, xml.etree.ElementTree as ET

dirs = sorted(glob.glob("modules/*/target/test-reports")) + sorted(glob.glob("tests/target/test-reports"))
total = dict(suites=0, cases=0, failures=0, errors=0, skipped=0)
for d in dirs:
    t = dict(suites=0, cases=0, failures=0, errors=0, skipped=0)
    for f in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
        r = ET.parse(f).getroot()
        t["suites"] += 1
        for case in r.iter("testcase"):
            t["cases"] += 1
            t["failures"] += len(case.findall("failure"))
            t["errors"] += len(case.findall("error"))
            t["skipped"] += len(case.findall("skipped"))
    for k in total:
        total[k] += t[k]
    name = d.split("/")[1] if d.startswith("modules/") else "tests"
    print(f"{name}: suites {t['suites']} tests {t['cases']} failures {t['failures']} errors {t['errors']} skipped/pending {t['skipped']}")
print(f"TOTAL: suites {total['suites']} tests {total['cases']} failures {total['failures']} errors {total['errors']} skipped/pending {total['skipped']}")
