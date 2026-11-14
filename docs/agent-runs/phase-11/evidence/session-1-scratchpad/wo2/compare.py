import json,glob,sys
def load(root,run):
    qs={}
    for f in glob.glob(f'{root}/s3/reports/rag-benchmark/snapshot=2026-10-03/run={run}/questions/*.json'):
        q=json.load(open(f)); qs[q['questionId']]=q
    return qs
a=load(sys.argv[1],sys.argv[2]); b=load(sys.argv[3],sys.argv[4])
rt_a=next(iter(a.values()))['runtimeRunId']; rt_b=next(iter(b.values()))['runtimeRunId']
print('old',sys.argv[2],'runtime',rt_a,'questions',len(a)); print('new',sys.argv[4],'runtime',rt_b,'questions',len(b))
def ans(root,rt,q):
    return json.load(open(f'{root}/s3/reports/rag-runtime/snapshot=2026-10-03/run={rt}/{q}.answer.json'))
diff=0
for qid in sorted(a):
    x,y=a[qid],b[qid]
    va=x['verification']; vb=y['verification']
    ca={k['name'] if isinstance(k,dict) and 'name' in k else str(i):k for i,k in enumerate(x['checks'])}
    xa=ans(sys.argv[1],rt_a,x['queryId']); yb=ans(sys.argv[3],rt_b,y['queryId'])
    ra=xa['response']; rb=yb['response']
    same_claims=[(c['text'],c['verificationStatus']) for c in ra['claims']]==[(c['text'],c['verificationStatus']) for c in rb['claims']]
    same_sum=ra.get('summary')==rb.get('summary'); same_unc=ra.get('uncertainty')==rb.get('uncertainty')
    same_abs=(x['abstained'],x['abstentionReason'])==(y['abstained'],y['abstentionReason'])
    chk=lambda q:[(c['name'],c['outcome']) for c in q['checks']]
    same_chk=chk(x)==chk(y)
    flag='' if (va==vb and same_claims and same_sum and same_unc and same_abs and same_chk) else 'DIFF'
    if flag: diff+=1
    print(qid, 'abst' if x['abstained'] else 'ans', x['abstentionReason'] or '', 'V/P/U/C old', [va[k] for k in ('verified','partiallySupported','unsupported','conflicting')], 'new', [vb[k] for k in ('verified','partiallySupported','unsupported','conflicting')], 'claims', same_claims, 'summary', same_sum, 'uncertainty', same_unc, 'abstention', same_abs, 'checks', same_chk, flag)
print('questions with any difference:',diff)
