"""Compare two fake benchmark runs question by question (template text only; fake mode has no model text).
usage: compare.py <old root> <old run> <new root> <new run>"""
import glob, json, sys


def load(root, run):
    qs = {}
    for f in glob.glob(f"{root}/s3/reports/rag-benchmark/snapshot=2026-10-03/run={run}/questions/*.json"):
        q = json.load(open(f))
        qs[q["questionId"]] = q
    return qs


def ans(root, rt, q):
    return json.load(open(f"{root}/s3/reports/rag-runtime/snapshot=2026-10-03/run={rt}/{q}.answer.json"))


a, b = load(sys.argv[1], sys.argv[2]), load(sys.argv[3], sys.argv[4])
rt_a, rt_b = next(iter(a.values()))["runtimeRunId"], next(iter(b.values()))["runtimeRunId"]
print("old", sys.argv[2], "runtime", rt_a, "questions", len(a))
print("new", sys.argv[4], "runtime", rt_b, "questions", len(b))
keys = ("verified", "partiallySupported", "unsupported", "conflicting")
tot_a, tot_b, diff = [0] * 4, [0] * 4, 0
for qid in sorted(a):
    x, y = a[qid], b[qid]
    va, vb = x["verification"], y["verification"]
    tot_a = [t + va[k] for t, k in zip(tot_a, keys)]
    tot_b = [t + vb[k] for t, k in zip(tot_b, keys)]
    ra, rb = ans(sys.argv[1], rt_a, x["queryId"])["response"], ans(sys.argv[3], rt_b, y["queryId"])["response"]
    ca = [(c["text"], c["verificationStatus"]) for c in ra["claims"]]
    cb = [(c["text"], c["verificationStatus"]) for c in rb["claims"]]
    same = dict(
        claims=ca == cb,
        summary=ra.get("summary") == rb.get("summary"),
        uncertainty=ra.get("uncertainty") == rb.get("uncertainty"),
        abstention=(x["abstained"], x["abstentionReason"]) == (y["abstained"], y["abstentionReason"]),
        checks=[(c["name"], c["outcome"]) for c in x["checks"]] == [(c["name"], c["outcome"]) for c in y["checks"]],
        counts=va == vb,
    )
    flag = "" if all(same.values()) else "DIFF"
    diff += bool(flag)
    print(qid, "abst" if x["abstained"] else "ans", x["abstentionReason"] or "",
          "V/P/U/C old", [va[k] for k in keys], "new", [vb[k] for k in keys],
          " ".join(f"{k}={v}" for k, v in same.items()), flag)
    for c in ca:
        if c not in cb:
            print("   only old:", c)
    for c in cb:
        if c not in ca:
            print("   only new:", c)
print("totals V/P/U/C old", tot_a, "new", tot_b)
print("questions with any difference:", diff)


def audits(root, rt):
    removed = dropped = n = 0
    for f in glob.glob(f"{root}/s3/logs/rag-runtime/snapshot=2026-10-03/run={rt}/*.json"):
        d = json.load(open(f))
        n += 1
        removed += len(d.get("removedClaims", []))
        dropped += len(d.get("droppedLines", []))
    return n, removed, dropped


for label, root, rt in (("old", sys.argv[1], rt_a), ("new", sys.argv[3], rt_b)):
    n, r, d = audits(root, rt)
    print(f"{label} audits {n}: removed claims {r}, left-out lines {d}")
