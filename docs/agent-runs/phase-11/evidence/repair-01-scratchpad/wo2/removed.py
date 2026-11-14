import json,glob,os,sys
root=sys.argv[1]
qs={}
for f in glob.glob(root+'/reports/rag-benchmark/snapshot=2026-10-03/run=run-052145faed8cbaed7ae874e3fbf36eae/questions/*.json'):
    q=json.load(open(f)); qs[q['queryId']]=q['questionId']
n=0
for f in sorted(glob.glob(root+'/logs/rag-runtime/snapshot=2026-10-03/run=run-422d912ac162f340f5f31a1b63b65e19/qry-*.json')):
    a=json.load(open(f))
    for c in a.get('removedClaims',[]):
        n+=1
        print('====',n,qs.get(a['queryId']),a['queryId'],c['claimId'],c['kind'],c['status'])
        print('TEXT:',c['text'])
        for fc in c['failedChecks']: print('  FAIL',fc['rule'],':',fc['detail'])
print('total',n)
