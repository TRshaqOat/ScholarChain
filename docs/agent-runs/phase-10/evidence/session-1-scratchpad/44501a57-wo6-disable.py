import sys
root='/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/services/src/main/scala/scholarchain/services/'
patches={
 'D1-claim': ('Worker.scala','        if mine then run(m, j, body)','        if mine || now.exists(_.state == JobState.RUNNING) then run(m, j, body)'),
 'D2-delete-before-final': ('Worker.scala','    if now.exists(_.state.isFinal) then delete(m)','    if true then delete(m)'),
 'D3-poison-deleted': ('Worker.scala','    metrics.add("poisonMessages")','    metrics.add("poisonMessages"); delete(m)'),
 'D4-no-stuck-policy': ('Worker.scala','              case JobState.RUNNING if m.receiveCount >= maxReceives =>','              case JobState.RUNNING if false =>'),
 'D5-no-async-replay': ('Services.scala','    ds.call(p.dynamodb)(backend.jobs.get(StableIds.jobId(queryId))) match','    (Right(None): Either[String, Option[JobRecord]]) match'),
}
f,a,b=patches[sys.argv[1]]
s=open(root+f).read(); assert s.count(a)==1, a
open(root+f,'w').write(s.replace(a,b))
print('patched',sys.argv[1],f)
