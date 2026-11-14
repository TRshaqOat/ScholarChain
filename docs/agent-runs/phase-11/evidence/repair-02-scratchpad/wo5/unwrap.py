"""Temporarily remove the pendingUntilFixed wrapper that follows each given PENDING marker.

Usage: python3 -I unwrap.py FILE ID [ID ...]
The wrapper line `pendingUntilFixed {` directly after `// PENDING <ID> ` is removed together
with its matching closing brace (the first later line with the same indentation that is `}`).
The body keeps its extra indentation, which compiles; the file is restored from a backup after.
"""
import sys

path, ids = sys.argv[1], sys.argv[2:]
lines = open(path).read().split("\n")
for d in ids:
    marks = [i for i, l in enumerate(lines) if l.strip().startswith(f"// PENDING {d} ")]
    assert len(marks) == 1, (d, marks)
    w = marks[0] + 1
    assert lines[w].strip() == "pendingUntilFixed {", (d, lines[w])
    indent = lines[w][: len(lines[w]) - len(lines[w].lstrip())]
    close = next(i for i in range(w + 1, len(lines)) if lines[i] == indent + "}")
    del lines[close]
    del lines[w]
    print(f"unwrapped {d}: wrapper line {w + 1}, closing line {close + 1}")
open(path, "w").write("\n".join(lines))
