import json, sys, os, collections
base = 'scratch/runtime/s3/reports'
def load(bench):
    d = f'{base}/rag-benchmark/snapshot=2026-10-03/run={bench}'
    out = {}
    for f in sorted(os.listdir(d + '/questions')):
        r = json.load(open(f'{d}/questions/{f}'))
        a = json.load(open(f"{base}/rag-runtime/snapshot=2026-10-03/run={r['runtimeRunId']}/{r['queryId']}.answer.json"))
        checks = {c['claimId']: c for c in a['verification']['checks']}
        claims = {c['claimId']: c for c in a['response']['claims']}
        out[r['questionId']] = (checks, claims, a)
    return out
old = load(sys.argv[1]); new = load(sys.argv[2])
changes = collections.Counter(); kinds = collections.Counter()
for q in sorted(old):
    oc, ocl, oa = old[q]; nc, ncl, na = new[q]
    if set(oc) != set(nc): print(q, 'claim ids differ', sorted(oc), sorted(nc))
    for cid in sorted(oc, key=lambda x: int(x.split('-')[1])):
        o = oc[cid]; n = nc.get(cid)
        if n is None: continue
        ot = ocl.get(cid, {}).get('text', ''); nt = ncl.get(cid, {}).get('text', '')
        if o['status'] != n['status']:
            failing = [f"{c['rule']}={c['outcome']}" for c in n['checks'] if c['outcome'] != 'PASS']
            print(f"{q} {cid}: {o['status']} -> {n['status']} [{'; '.join(failing)}] old: {ot[:90]!r} new: {nt[:90]!r}")
            changes[(o['status'], n['status'])] += 1
        elif ot != nt:
            print(f"{q} {cid}: text changed, status {n['status']}: {ot[:70]!r} -> {nt[:70]!r}")
    if oa['response']['summary'] != na['response']['summary']:
        print(q, 'summary changed')
print(dict(changes))
