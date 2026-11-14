import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
n = s.count(old)
if n != 1:
    sys.exit(f"expected 1 match, found {n}")
open(path, "w", encoding="utf-8").write(s.replace(old, new))
