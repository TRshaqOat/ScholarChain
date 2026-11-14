import hashlib, os, subprocess, sys

# Patches one part of the D10 fix out, runs WindowSummarySpec, restores the file, verifies sha256.
repo, logdir = sys.argv[1], sys.argv[2]
src = os.path.join(repo, "modules/storage/src/main/scala/scholarchain/storage/WindowSummaries.scala")
patches = [
    ("D10-all-identities", ").flatten\n\n  /** Rule violations", ").flatten.take(0)\n\n  /** Rule violations"),
    ("D10-partition", "    if r == 0 then c == 0", "    if true then true\n    else if r == 0 then c == 0"),
    ("D10-at-least-min-size", "Option.unless(atLeastOk)", "Option.unless(true || atLeastOk)"),
    ("D10-edges", "Option.unless(edgesOk)", "Option.unless(true || edgesOk)"),
]
orig = open(src, "rb").read()
sha = hashlib.sha256(orig).hexdigest()
env = dict(os.environ, SPARK_LOCAL_IP="127.0.0.1")
summary = []
for name, old, new in patches:
    text = orig.decode()
    assert text.count(old) == 1, name
    open(src, "w").write(text.replace(old, new))
    log = os.path.join(logdir, f"disable-{name}.log")
    try:
        with open(log, "w") as f:
            code = subprocess.run(
                ["sbt", "-J-Xmx4g", "storage/testOnly scholarchain.storage.WindowSummarySpec"],
                cwd=repo, env=env, stdout=f, stderr=subprocess.STDOUT).returncode
    finally:
        open(src, "wb").write(orig)
    restored = hashlib.sha256(open(src, "rb").read()).hexdigest()
    out = open(log).read()
    failed = [l.strip() for l in out.splitlines() if "*** FAILED ***" in l]
    tests = [l.strip() for l in out.splitlines() if "Tests: succeeded" in l]
    line = f"{name}: sbt exit {code}; {tests}; failed {failed}; restore sha256 {restored} {'OK' if restored == sha else 'MISMATCH'}"
    summary.append(line)
    print(line, flush=True)
open(os.path.join(logdir, "disable-summary.txt"), "w").write(f"original sha256 {sha}\n" + "\n".join(summary) + "\n")
