"""Wraps named ScalaTest tests in pendingUntilFixed (Phase 11 WO3, ruling 3). Idempotent."""
import sys

ROOT = sys.argv[1]
A = ROOT + "/tests/adversarial/scholarchain/tests/adversarial/"
C = ROOT + "/tests/contracts/scholarchain/tests/contracts/"

WRAPS = [
    (A + "UnsupportedClaimAdversarialSpec.scala", "a quantity stated only in words", "D1", "rag-engineer"),
    (A + "UnsupportedClaimAdversarialSpec.scala", "a comparison the stored value contradicts", "D2", "rag-engineer"),
    (A + "UnsupportedClaimAdversarialSpec.scala", "a negated statement of the stored value", "D3", "rag-engineer"),
    (A + "RagModelOutputAdversarialSpec.scala", "a throttled vector query (once, or every time)", "D4", "rag-engineer"),
    (A + "ApiBodyAdversarialSpec.scala", "a declared body that never arrives", "D5", "service-engineer"),
    (A + "DblpXmlAdversarialSpec.scala", "an author with no name", "D6", "hadoop-engineer"),
    (A + "IdentityAdversarialSpec.scala", "invisible format characters inside a name", "D7", "entity-resolution-engineer"),
    (A + "IdentityAdversarialSpec.scala", "DOI normalization is idempotent", "D8", "domain-engineer"),
    (A + "IdentityAdversarialSpec.scala", "a percent-encoded DOI URL", "D9", "domain-engineer"),
    (A + "WindowSummaryTamperAdversarialSpec.scala", "counts no coauthorship graph can have", "D10", "storage-engineer"),
    (C + "ConfigOmissionContractSpec.scala", "every configuration key omitted", "D11", "domain-engineer"),
    (C + "ConfigOmissionContractSpec.scala", "a blank value for a required production setting", "D12", "domain-engineer"),
    (C + "ConfigOmissionContractSpec.scala", "an empty environment variable overriding", "D12", "domain-engineer"),
    (C + "ConfigOmissionContractSpec.scala", "a fractional value for an integer setting", "D13", "domain-engineer"),
]

for path, title, defect, owner in WRAPS:
    lines = open(path, encoding="utf-8").read().split("\n")
    starts = [i for i, l in enumerate(lines) if l.startswith('  test("' + title)]
    assert len(starts) == 1, (path, title, starts)
    s = starts[0]
    # The test header may span lines until the line ending with ") {".
    h = s
    while not lines[h].rstrip().endswith("{"):
        h += 1
    if "pendingUntilFixed" in lines[h + 1] or "pendingUntilFixed" in lines[h + 2]:
        continue
    e = h + 1
    while lines[e] != "  }":
        e += 1
    body = ["  " + l if l.strip() else l for l in lines[h + 1 : e]]
    wrapped = (
        ["    // PENDING " + defect + " (owner " + owner + "): fails without a production fix; see tests/TESTS.md.",
         "    pendingUntilFixed {"]
        + body
        + ["    }"]
    )
    lines = lines[: h + 1] + wrapped + lines[e:]
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    print("wrapped", defect, path.split("/")[-1], title)
