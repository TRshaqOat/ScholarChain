# Compares two fake benchmark runs question by question: every claim's status and text, the summary,
# the uncertainty text, and the abstention. Reads only. Usage: compare.py <s3 root> <old run> <new run>
import json, sys, glob, os
root, old, new = sys.argv[1:4]
snap = "snapshot=2026-10-03"
def bench(run):
    out = {}
    for f in sorted(glob.glob(f"{root}/reports/rag-benchmark/{snap}/run={run}/questions/*.json")):
        r = json.load(open(f))
        a = json.load(open(f"{root}/reports/rag-runtime/{snap}/run={r['runtimeRunId']}/{r['queryId']}.answer.json"))
        out[r["questionId"]] = (r, a)
    return out
A, B = bench(old), bench(new)
changes = 0
print(f"questions: old {len(A)}, new {len(B)}")
for q in sorted(set(A) | set(B)):
    if q not in A or q not in B:
        print(q, "missing in one run"); changes += 1; continue
    (ra, aa), (rb, ab) = A[q], B[q]
    ca = {c["claimId"]: c for c in aa["verification"]["checks"]}
    cb = {c["claimId"]: c for c in ab["verification"]["checks"]}
    ta = {c["claimId"]: c["text"] for c in aa["response"]["claims"]}
    tb = {c["claimId"]: c["text"] for c in ab["response"]["claims"]}
    for k in sorted(set(ca) | set(cb)):
        sa, sb = ca.get(k, {}).get("status"), cb.get(k, {}).get("status")
        if sa != sb:
            changes += 1
            fails = [f"{x['rule']}={x['outcome']}: {x['detail']}" for x in cb.get(k, {}).get("checks", []) if x["outcome"] != "PASS"]
            print(f"{q} {k}: {sa} -> {sb}; new non-PASS checks: {fails}")
        if ta.get(k) != tb.get(k):
            changes += 1; print(f"{q} {k}: kept text differs")
    for field in ("summary", "uncertainty", "abstained"):
        if aa["response"].get(field) != ab["response"].get(field):
            changes += 1; print(f"{q}: response.{field} differs")
    if (aa.get("abstention") or {}).get("reason") != (ab.get("abstention") or {}).get("reason"):
        changes += 1; print(f"{q}: abstention reason differs")
    if ra["checks"] != rb["checks"]:
        print(f"{q}: benchmark checks differ: {[(c['name'], c['outcome']) for c in rb['checks'] if c not in ra['checks']]}")
print("total differences:", changes)
