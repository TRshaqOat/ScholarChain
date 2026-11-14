"""Remove pendingUntilFixed wrappers for the named D items.

Usage: python3 -I unwrap.py FILE D1 D2 ...
For each `// PENDING Dn (...)` marker line followed by `pendingUntilFixed {`, the marker and
the wrapper line are removed, the block body is dedented by 2 spaces, and the matching close
brace (the first later line equal to the wrapper's indentation + "}") is removed.
"""
import re
import sys

path = sys.argv[1]
ids = set(sys.argv[2:])
lines = open(path, encoding="utf-8").read().split("\n")
out = []
i = 0
removed = []
while i < len(lines):
    m = re.match(r"^(\s*)// PENDING (D\d+) ", lines[i])
    if m and m.group(2) in ids and i + 1 < len(lines) and lines[i + 1].strip() == "pendingUntilFixed {":
        indent = m.group(1)
        assert lines[i + 1].startswith(indent + "pendingUntilFixed {"), (path, i)
        j = i + 2
        body = []
        while lines[j] != indent + "}":
            l = lines[j]
            if l.strip():
                assert l.startswith(indent + "  "), (path, j, l)
                body.append(l[2:])
            else:
                body.append(l)
            j += 1
        out.extend(body)
        removed.append((m.group(2), i + 1))
        i = j + 1
        continue
    out.append(lines[i])
    i += 1
open(path, "w", encoding="utf-8").write("\n".join(out))
print(path, "removed", removed)
missing = ids - {r[0] for r in removed}
if missing:
    print("NOT FOUND", sorted(missing))
    sys.exit(1)
