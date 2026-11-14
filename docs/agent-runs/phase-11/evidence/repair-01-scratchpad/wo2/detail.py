import json,glob,sys
root=sys.argv[1]; run='run-422d912ac162f340f5f31a1b63b65e19'
items={}
for l in open(f'{root}/dynamodb/snapshot=2026-10-03/run={run}/EvidenceBundle.jsonl'):
    r=json.loads(l)['Item']
    if r['itemKey']['S']!='BUNDLE':
        p=json.loads(r['payload']['S'])
        items[(r['queryId']['S'],p.get('evidenceId'))]=p
want=set(sys.argv[2:])
for f in sorted(glob.glob(f'{root}/s3/logs/rag-runtime/snapshot=2026-10-03/run={run}/qry-*.json')):
    a=json.load(open(f)); q=a['queryId']
    if q not in want: continue
    ans=json.load(open(f.replace('/logs/','/reports/').replace('.json','.answer.json')))
    rem={c['claimId'] for c in a['removedClaims']}
    print('#####',q,'plan ops',[ (o.get('operation'),o.get('scope')) if isinstance(o,dict) else o for o in a['plan']['operations']],'window',a['plan']['window'].get('name') if a['plan']['window'] else None)
    for ck in ans['verification']['checks']:
        if ck['claimId'] in rem:
            print(' CHECKS',ck['claimId'],[(x['rule'],x['outcome'],x['detail'][:120]) for x in ck['checks']])
    for c in ans['response'].get('claims',[]) if isinstance(ans['response'],dict) else []:
        pass
    for (qq,eid),p in items.items():
        if qq==q:
            print(' EV',eid,p.get('evidenceType'),{k:v for k,v in p.get('facts',{}).items() if len(str(v))<60})
