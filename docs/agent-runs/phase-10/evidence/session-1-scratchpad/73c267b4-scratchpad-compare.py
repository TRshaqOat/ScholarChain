# Read-only comparison of two fake benchmark runs: per question, every claim's status and text,
# the summary, the uncertainty lines, and the abstention. Usage: python3 -I compare.py OLD NEW
import json, os, sys

base = "scratch/runtime/s3/reports"
snap = "snapshot=2026-10-03"


def load(bench):
    qdir = f"{base}/rag-benchmark/{snap}/run={bench}/questions"
    out = {}
    for f in sorted(os.listdir(qdir)):
        r = json.load(open(f"{qdir}/{f}"))
        a = json.load(open(f"{base}/rag-runtime/{snap}/run={r['runtimeRunId']}/{r['queryId']}.answer.json"))
        status = {c["claimId"]: c["status"] for c in a["verification"]["checks"]}
        text = {c["claimId"]: c["text"] for c in a["response"]["claims"]}
        out[r["questionId"]] = (status, text, a["response"]["summary"],
                                a["response"].get("uncertainty"), a.get("abstention"))
    return out


old, new = load(sys.argv[1]), load(sys.argv[2])
changes = 0
for q in sorted(set(old) | set(new)):
    (os_, ot, osum, ou, oa), (ns, nt, nsum, nu, na) = old[q], new[q]
    for c in sorted(set(os_) | set(ns), key=lambda x: int(x.split("-")[1])):
        if os_.get(c) != ns.get(c):
            changes += 1
            print(f"{q} {c}: status {os_.get(c)} -> {ns.get(c)}")
        if ot.get(c) != nt.get(c):
            print(f"{q} {c}: text\n  old: {ot.get(c)}\n  new: {nt.get(c)}")
    if osum != nsum:
        print(f"{q}: summary\n  old: {osum}\n  new: {nsum}")
    if ou != nu:
        print(f"{q}: uncertainty\n  old: {ou}\n  new: {nu}")
    if oa != na:
        print(f"{q}: abstention {oa} -> {na}")
print(f"questions compared: {len(set(old) | set(new))}")
print(f"claims whose status changed: {changes}")
