import sys
p=sys.argv[1]; s=open(p).read()
old='NumberRe.findAllIn(YearRange.replaceAllIn(wordsToDigits(c.strip(text)), " ")).toSet'
new='NumberRe.findAllIn(wordsToDigits(YearRange.replaceAllIn(c.strip(text), " "))).toSet'
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new))
