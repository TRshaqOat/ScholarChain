import sys
P={
 'D1-percent': [('''          if Percent.findPrefixOf(t2.substring(m.end)).isEmpty then bindings.filter(_.binds(n))''',
                 '''          if true then bindings.filter(_.binds(n))''')],
 'D2-collaboration-communities': [('collaborations?(?!\\\\s+communit)|connections?','collaborations?|connections?'),
                                  ('works?|collaborations?(?!\\\\s+communit)"','works?|collaborations?"')],
 'D3-name-works': [('"totalWorks" -> "nameWorks",','"totalWorks" -> "publications",')],
 'N5-numbersIn-order': [('NumberRe.findAllIn(YearRange.replaceAllIn(wordsToDigits(c.strip(text)), " ")).toSet',
                         'NumberRe.findAllIn(wordsToDigits(YearRange.replaceAllIn(c.strip(text), " "))).toSet')],
}
name,path=sys.argv[1],sys.argv[2]
s=open(path).read()
for a,b in P[name]:
    assert s.count(a)==1,(name,a,s.count(a))
    s=s.replace(a,b)
open(path,'w').write(s)
