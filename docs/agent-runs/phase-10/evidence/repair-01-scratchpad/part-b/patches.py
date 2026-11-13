"""Disable patches for repair-01 Part B. Usage: python3 -I patches.py <name> <repo root>.
Each patch is a list of (old, new) exact replacements; every old text must occur exactly once.
Prints the file it patched."""
import sys

R = "modules/rag/src/main/scala/scholarchain/rag/runtime/"
V, W, S, A = R + "Verifier.scala", R + "Workflow.scala", R + "Synthesis.scala", R + "RuntimeApi.scala"

P = {
    # WO1 D1: an ordinal never binds to a list position k.
    "wo1-D1-positions": (V, [(
        "else if ordinal && n.toIntOption.exists(positions) then None",
        "else if false then None")]),
    # WO1 D2: year ranges set aside before number words (the pre-WO1 order, textProblems).
    "wo1-D2-order": (V, [(
        "    val t2 = YearRange.replaceAllIn(\n      wordsToDigits(t),\n",
        "    val t2 = wordsToDigits(YearRange.replaceAllIn(\n      t,\n"), (
        "        \" \" * m.matched.length\n    )\n    val numberProblems",
        "        \" \" * m.matched.length\n    ))\n    val numberProblems")]),
    # WO1 D3: no sentence-initial ordering word rule.
    "wo1-D3-discourse": (V, [(
        "ZeroRules.foldLeft(Discourse.replaceAllIn(ungroup(text), \"\"))",
        "ZeroRules.foldLeft(ungroup(text))")]),
    # WO1 D4: the measure name "on one paper" rule becomes a no-op.
    "wo1-D4-measure-name": (V, [(') -> "$1 $2 a $3",', ') -> "$0",')]),
    # WO1 D5: "once again" and "twice as" rules become no-ops.
    "wo1-D5-fixed-phrases": (V, [
        ('ci("once\\\\s+again") -> "again",', 'ci("once\\\\s+again") -> "$0",'),
        ('ci("twice\\\\s+as") -> "as",', 'ci("twice\\\\s+as") -> "$0",')]),
    # WO1 D6: any ordinal followed by a year is a time marker, rank word or not (pre-WO1 rule).
    "wo1-D6-year-exception": (V, [(
        "val time = m.group(1) == null && word == \"first\" &&\n"
        "          YearAhead.findPrefixOf(zero.substring(m.end)).isDefined && !ranked(zero, m.start, m.end)",
        "val time = m.group(1) == null &&\n"
        "          YearAhead.findPrefixOf(zero.substring(m.end)).isDefined")]),
    # WO1 D7: ordinals first to tenth only.
    "wo1-D7-ordinals-to-tenth": (V, [(
        "    Ordinals.zipWithIndex.map((w, i) => w -> (i + 1)).toMap ++\n"
        "      TensOrdinals.zipWithIndex.map((w, i) => w -> (20 + 10 * i))",
        "    Ordinals.take(10).zipWithIndex.map((w, i) => w -> (i + 1)).toMap")]),
    # WO1 D8: "coauthors per paper" out of the average family.
    "wo1-D8-average-vocabulary": (V, [(
        '"average" -> words("average|mean|co-?authors? per paper|per paper"),',
        '"average" -> words("average|mean|per paper"),')]),
    # WO1 D9: "joint publications" out of the weighted family.
    "wo1-D9-intensity-vocabulary": (V, [(
        '"weighted" -> words("weighted degree|weighted|intensity|joint publications?"),',
        '"weighted" -> words("weighted degree|weighted|intensity"),')]),
    # WO1 D10: no rank or list-position branch for ordinals and rank-word numbers.
    "wo1-D10-rank-numbers": (V, [(
        'if ordinal || (near("rank") && !near("list")) then',
        "if false then")]),
    # WO1 D11: the pre-WO1 unknownNames (every bundle text stripped; part of a text passes).
    "wo1-D11-unknown-names": (V, [(
        "c.knownAll.filter(_.exists(_.isWhitespace)).foldLeft(text)((t, v) => t.replace(v, \" \"))",
        "c.knownAll.foldLeft(text)((t, v) => t.replace(v, \" \"))"), (
        ".filterNot(c.knownAll.contains)",
        ".filterNot(s => c.knownAll.exists(_.contains(s)))")]),
    # WO1 D12: the audit records no removed claims or dropped lines.
    "wo1-D12-audit-removals": (W, [(
        "              v.removed,\n              v.dropped\n",
        "              Vector.empty,\n              Vector.empty\n")]),
    # WO1 D13: the service view keeps model text.
    "wo1-D13-service-view": (A, [(
        "    a.copy(\n      removedClaims = a.removedClaims.map(_.copy(text = \"\")),\n"
        "      droppedLines = a.droppedLines.map(_.copy(text = \"\"))\n    )",
        "    a")]),
    # WO1 D14: synthesis-output-v3 rank researcherId rule off.
    "wo1-D14-v3-rank-ids": (S, [(
        'then missingRankIds(o)', 'then Vector.empty[String]')]),
    # WO1 D15: the deadline parameter is ignored (configuration deadline only).
    "wo1-D15-deadline-param": (W, [(
        "val budgetMs = deadlineMs.getOrElse(cfg.runtime.question.deadlineMs.toLong)",
        "val budgetMs = deadlineMs.fold(cfg.runtime.question.deadlineMs.toLong)(_ =>"
        " cfg.runtime.question.deadlineMs.toLong)")]),
    # WO9 D1: no cut-off rule passed to synthesis.
    "wo9-D1-cutoff-retry": (W, [("Some(Synthesis.cutOffRule(cfg))", "None")]),
    # WO9 D2: digit groups not removed.
    "wo9-D2-digit-groups": (V, [(
        "Discourse.replaceAllIn(ungroup(text), \"\")", "Discourse.replaceAllIn(text, \"\")")]),
    # WO9 D3: a comparison item covers only its a>b window.
    "wo9-D3-comparison-side": (V, [("      w +: side.toVector\n", "      w +: Vector.empty[String]\n")]),
    # WO9 D4: the window-summary port is not passed to GraphStages.
    "wo9-D4-corpus-port": (W, [("          ports.windowSummaries\n        ).run", "          None\n        ).run")]),
}

name, root = sys.argv[1], sys.argv[2]
path, reps = P[name]
full = root + "/" + path
src = open(full, encoding="utf-8").read()
for old, new in reps:
    n = src.count(old)
    if n != 1:
        sys.exit(f"patch {name}: expected 1 occurrence, found {n}: {old[:60]!r}")
    src = src.replace(old, new)
open(full, "w", encoding="utf-8").write(src)
print(path)
