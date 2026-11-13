import json,sys,os
root=sys.argv[1]
runs={'wo1':('scratch/runtime','run-25b92d9b0ae8963cf3119162289523ac','run-c79c55ed6110feead79661ff2f0277e9'),
      'wo9':('scratch/runtime/wo9','run-396fa84df47be7a2579b491bec64356c','run-824b0a2eaf72183a533e55c9e07b9297')}
snap='snapshot=2026-10-03'
def load(tag):
    base,bench,rt=runs[tag]
    out={}
    qdir=f'{root}/{base}/s3/reports/rag-benchmark/{snap}/run={bench}/questions'
    for f in sorted(os.listdir(qdir)):
        r=json.load(open(f'{qdir}/{f}'))
        ans=json.load(open(f'{root}/{base}/s3/reports/rag-runtime/{snap}/run={rt}/{r["queryId"]}.answer.json'))
        aud=json.load(open(f'{root}/{base}/s3/logs/rag-runtime/{snap}/run={rt}/{r["queryId"]}.json'))
        out[r['questionId']]=(r,ans,aud)
    return out
a,b=load('wo1'),load('wo9')
def outcome(r): return r['abstentionReason'] or 'ANSWERED'
def v(r): x=r['verification']; return f"{x['verified']}/{x['partiallySupported']}/{x['unsupported']}/{x['conflicting']}"
print('| Q | WO1 outcome | WO1 V/P/U/C | WO9 outcome | WO9 V/P/U/C | checks failing WO1 -> WO9 |')
print('|---|---|---|---|---|---|')
changed=[]
for q in sorted(a):
    ra,rb=a[q][0],b[q][0]
    fa=[c['name'] for c in ra['checks'] if c['outcome']=='FAIL']; fb=[c['name'] for c in rb['checks'] if c['outcome']=='FAIL']
    print(f"| {q} | {outcome(ra)} | {v(ra)} | {outcome(rb)} | {v(rb)} | {','.join(fa) or '-'} -> {','.join(fb) or '-'} |")
    ca={c['claimId']:(c['text'],c['verificationStatus']) for c in a[q][1]['response']['claims']}
    cb={c['claimId']:(c['text'],c['verificationStatus']) for c in b[q][1]['response']['claims']}
    sa=a[q][1]['response']; sb=b[q][1]['response']
    diff=[]
    if outcome(ra)!=outcome(rb): diff.append(f'outcome {outcome(ra)} -> {outcome(rb)}')
    if ca!=cb: diff.append(f'claims {sorted(ca.items())} -> {sorted(cb.items())}')
    if sa['summary']!=sb['summary']: diff.append('summary changed')
    if sa.get('uncertainty')!=sb.get('uncertainty'): diff.append(f"uncertainty {sa.get('uncertainty')!r} -> {sb.get('uncertainty')!r}")
    cka={c['name']:c['outcome'] for c in ra['checks']}; ckb={c['name']:c['outcome'] for c in rb['checks']}
    if cka!=ckb: diff.append(f'check outcomes {cka} -> {ckb}')
    if diff: changed.append((q,diff))
def tot(x):
    t={'answered':0,'abstained':0,'V':0,'P':0,'U':0,'C':0}
    for r,_,_ in x.values():
        t['abstained' if r['abstained'] else 'answered']+=1
        vv=r['verification']; t['V']+=vv['verified']; t['P']+=vv['partiallySupported']; t['U']+=vv['unsupported']; t['C']+=vv['conflicting']
    return t
print('\nTOTALS wo1',tot(a)); print('TOTALS wo9',tot(b))
print('\nCHANGED QUESTIONS',len(changed))
for q,d in changed:
    print('##',q); [print('  ',x) for x in d]
print('\nDETAIL B13 B15 B17 B24')
for q in ['B13','B15','B17','B24']:
    for tag,x in (('wo1',a),('wo9',b)):
        r,ans,aud=x[q]
        print(f'### {q} {tag} query {r["queryId"]}')
        print('  plan operations',r['operations'],'window',r['window'],'comparison',aud['plan'].get('comparison'))
        print('  outcome',outcome(r),'abstention detail', (ans.get('abstention') or {}).get('detail'))
        print('  graph evidence',aud['graphEvidenceIds'],'metricVersions',aud['metricVersions'])
        print('  template calls',[t['templateId'] for t in aud['templateCalls']])
        for c in ans['response']['claims']: print('  claim',c['claimId'],c['verificationStatus'],c['evidenceIds'],c['text'])
        for ck in ans['verification']['checks']: print('   verify',ck['claimId'],ck['status'],[ (k['rule'],k['outcome']) for k in ck['checks']])
        print('  uncertainty',ans['response'].get('uncertainty'))
