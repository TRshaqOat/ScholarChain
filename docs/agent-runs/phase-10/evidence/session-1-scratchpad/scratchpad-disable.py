# Applies one named disable patch to a rag runtime source file (repository copy). Usage: disable.py <dir> <id>
import sys
d, pid = sys.argv[1], sys.argv[2]
P = {
 "D1-positions": ("Verifier.scala", "else if ordinal && n.toIntOption.exists(positions) then None", "else if false then None"),
 "D2-order": ("Verifier.scala", None, None),
 "D3-discourse": ("Verifier.scala", "ZeroRules.foldLeft(Discourse.replaceAllIn(text, \"\"))", "ZeroRules.foldLeft(text)"),
 "D4-measure-name": ("Verifier.scala", ') -> "$1 $2 a $3",', ') -> "$0",'),
 "D5-fixed-phrases": ("Verifier.scala", 'ci("once\\\\s+again") -> "again",\n    ci("twice\\\\s+as") -> "as",\n', ''),
 "D6-year-exception": ("Verifier.scala", 'val time = m.group(1) == null && word == "first" &&\n          YearAhead.findPrefixOf(zero.substring(m.end)).isDefined && !ranked(zero, m.start, m.end)',
                        'val time = YearAhead.findPrefixOf(zero.substring(m.end)).isDefined'),
 "D7-ordinals-to-tenth": ("Verifier.scala", 'Ordinals.zipWithIndex.map((w, i) => w -> (i + 1)).toMap ++\n      TensOrdinals.zipWithIndex.map((w, i) => w -> (20 + 10 * i))',
                          'Ordinals.take(10).zipWithIndex.map((w, i) => w -> (i + 1)).toMap'),
 "D8-average-vocabulary": ("Verifier.scala", 'words("average|mean|co-?authors? per paper|per paper")', 'words("average|mean|per paper")'),
 "D9-intensity-vocabulary": ("Verifier.scala", 'words("weighted degree|weighted|intensity|joint publications?")', 'words("weighted degree|weighted|intensity")'),
 "D10-rank-numbers": ("Verifier.scala", 'if ordinal || (near("rank") && !near("list")) then', 'if false then'),
 "D11-unknown-names": ("Verifier.scala", 'c.knownAll.filter(_.exists(_.isWhitespace)).foldLeft(text)((t, v) => t.replace(v, " "))\n    RulePlanner\n      .names(stripped)\n      .filterNot(c.knownAll.contains)',
                       'c.knownAll.foldLeft(text)((t, v) => t.replace(v, " "))\n    RulePlanner\n      .names(stripped)\n      .filterNot(s => c.knownAll.exists(_.contains(s)))'),
 "D12-audit-removals": ("Workflow.scala", "        removed,\n        dropped,\n        abstention.isDefined,", "        Vector.empty,\n        Vector.empty,\n        abstention.isDefined,"),
 "D13-service-view": ("RuntimeApi.scala", "    a.copy(\n      removedClaims = a.removedClaims.map(_.copy(text = \"\")),\n      droppedLines = a.droppedLines.map(_.copy(text = \"\"))\n    )", "    a"),
 "D14-v3-rank-ids": ("Synthesis.scala", 'then missingRankIds(o)', 'then Vector.empty[String]'),
 "D15-deadline-param": ("Workflow.scala", "val budgetMs = deadlineMs.getOrElse(cfg.runtime.question.deadlineMs.toLong)", "val budgetMs = cfg.runtime.question.deadlineMs.toLong"),
}
f, old, new = P[pid]
p = f"{d}/{f}"
s = open(p).read()
if pid == "D2-order":
    a = "val t2 = YearRange.replaceAllIn(\n      wordsToDigits(t),"
    b = "        \" \" * m.matched.length\n    )\n    val numberProblems"
    assert s.count(a) == 1 and s.count(b) == 1
    s = s.replace(a, "val t2 = wordsToDigits(YearRange.replaceAllIn(\n      t,").replace(b, "        \" \" * m.matched.length\n    ))\n    val numberProblems")
else:
    assert s.count(old) == 1, (pid, s.count(old))
    s = s.replace(old, new)
open(p, "w").write(s)
print("applied", pid, "to", f)
