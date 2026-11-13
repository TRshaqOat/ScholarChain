"""Per-project JUnit XML counts. Usage: python3 -I junit.py <repo root>."""
import glob, os, sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
names = {"core": "core", "entity-resolution": "entityResolution", "hadoop": "hadoop", "rag": "rag",
         "services": "services", "spark": "sparkAnalytics", "storage": "storage"}
tot = [0, 0, 0, 0]
for d, p in sorted(names.items(), key=lambda x: x[1]):
    files = glob.glob(os.path.join(root, "modules", d, "target", "test-reports", "TEST-*.xml"))
    t = f = e = 0
    newest = 0.0
    for x in files:
        s = ET.parse(x).getroot()
        t += int(s.get("tests", 0)); f += int(s.get("failures", 0)); e += int(s.get("errors", 0))
        newest = max(newest, os.path.getmtime(x))
    oldest = min((os.path.getmtime(x) for x in files), default=0.0)
    import time
    fmt = lambda v: time.strftime("%H:%M:%S", time.localtime(v))
    print(f"{p}: tests {t}, failures {f}, errors {e}, suites {len(files)}, report times {fmt(oldest)}..{fmt(newest)}")
    tot = [tot[0] + t, tot[1] + f, tot[2] + e, tot[3] + len(files)]
print(f"total: tests {tot[0]}, failures {tot[1]}, errors {tot[2]}, suites {tot[3]}")
