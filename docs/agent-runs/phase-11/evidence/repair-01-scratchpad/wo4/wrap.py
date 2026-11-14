"""Wrap one test body in pendingUntilFixed with a PENDING marker.

Usage: python3 -I wrap.py FILE "test title fragment" D14 owner
The test header is the first line containing the fragment; its body runs from the line after the
first later line ending in ") {" (or the header itself when it ends so) to the first later line
equal to "  }". Body lines are indented by 2 more spaces.
"""
import sys

path, frag, did, owner = sys.argv[1:5]
lines = open(path, encoding="utf-8").read().split("\n")
h = next(i for i, l in enumerate(lines) if frag in l)
o = h
while not lines[o].rstrip().endswith(") {"):
    o += 1
c = next(i for i in range(o + 1, len(lines)) if lines[i] == "  }")
body = [("  " + l) if l.strip() else l for l in lines[o + 1 : c]]
wrapped = (
    [f"    // PENDING {did} (owner {owner}): fails without a production fix; see tests/TESTS.md.",
     "    pendingUntilFixed {"]
    + body
    + ["    }"]
)
lines = lines[: o + 1] + wrapped + lines[c:]
open(path, "w", encoding="utf-8").write("\n".join(lines))
print(path, did, "marker at line", o + 2)
