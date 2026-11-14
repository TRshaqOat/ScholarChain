"""Named disable patches for Verifier.scala (repair-02 Part A). usage: patch.py <file> <label>"""
import sys

V7_BEFORE = (
    '      "upwards\\\\s+of|close\\\\s+to|nearly|almost|about|around|approximately|approx|roughly|circa|ca|" +\n'
    '      "some|beyond|exceeding|exceeds?|over|under|above|below)|[~≈<>≤≥]=?)" + Filler).r'
)
V8_BEFORE = (
    '      "upwards?\\\\s+of|(?:short|shy|north|south)\\\\s+of|far\\\\s+from|close\\\\s+to|nearly|near|nigh|" +\n'
    '      "almost|about|around|approximately|approx|approaching|nearing|roughly|circa|ca|some|beyond|" +\n'
    '      "exceed(?:s|ed|ing)?|surpass(?:es|ed|ing)?|outnumber(?:s|ed|ing)?|top(?:s|ped|ping)|over|" +\n'
    '      "under|above|below|beneath)|[~≈<>≤≥]=?)" + Filler).r'
)
V8_AFTER = (
    '    ("(?i)^(?:\\\\s*%)?(?:\\\\s*\\\\+|[-\\\\s]\\\\s*(?:plus|odd|ish)\\\\b|ish\\\\b|\\\\s+or\\\\s+(?:more|fewer|less|so|" +\n'
    '      "higher|lower|greater|above|below|over|under|thereabouts)\\\\b|\\\\s+and\\\\s+(?:up|above|over|more|" +\n'
    '      "counting)\\\\b|\\\\s+at\\\\s+(?:least|most|(?:a\\\\s+)?(?:minimum|maximum))\\\\b)").r'
)
V7_AFTER = (
    '    ("(?i)^(?:\\\\s*%)?(?:\\\\s*\\\\+|[-\\\\s]\\\\s*(?:plus|odd)\\\\b|\\\\s+or\\\\s+(?:more|fewer|less|so|higher|" +\n'
    '      "lower|greater|above|below|over|under|thereabouts)\\\\b|\\\\s+and\\\\s+(?:up|above|over|more)\\\\b|" +\n'
    '      "\\\\s+at\\\\s+(?:least|most)\\\\b)").r'
)
V8_NEG = (
    '      "lack(?:s|ed|ing)?|without|absen(?:t|ce)|devoid|fail(?:s|ed|ing)?\\\\s+to|hardly|scarcely|" +\n'
    '      "barely|seldom|rarely|den(?:y|ies|ied)|except|excluding)\\\\b|n[\'’]t\\\\b)(?!\\\\s+converge)").r'
)
V7_NEG = '      "zzdisabledzz)\\\\b|n[\'’]t\\\\b)(?!\\\\s+converge)").r'
V8_FILLER = 'private val Filler = "(?:\\\\W{1,3}(?:the|an?|a\\\\s+(?:total|count)\\\\s+of))?\\\\W{0,3}$"'
V7_FILLER = 'private val Filler = "\\\\W{0,3}$"'
ASIDE_ON = "Negation.findFirstIn(asidesOut(before))"
ASIDE_OFF = "Negation.findFirstIn(before)"

PATCHES = {
    # The whole D14 lexicon back to its verifier-v7 form (Before, After, Negation, Filler).
    "D14-all": [(V8_BEFORE, V7_BEFORE), (V8_AFTER, V7_AFTER), (V8_NEG, V7_NEG), (V8_FILLER, V7_FILLER)],
    "D14-comparison": [(V8_BEFORE, V7_BEFORE), (V8_AFTER, V7_AFTER)],
    "D14-negation": [(V8_NEG, V7_NEG)],
    "D14-filler": [(V8_FILLER, V7_FILLER)],
    "ctl-aside": [(ASIDE_ON, ASIDE_OFF)],
}

path, label = sys.argv[1], sys.argv[2]
s = open(path, encoding="utf-8").read()
for old, new in PATCHES[label]:
    n = s.count(old)
    if n != 1:
        sys.exit(f"{label}: expected 1 match, found {n}: {old[:70]!r}")
    s = s.replace(old, new)
open(path, "w", encoding="utf-8").write(s)
print(f"{label}: {len(PATCHES[label])} replacement(s) applied")
