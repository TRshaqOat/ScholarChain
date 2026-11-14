"""Phase 11 repair-01 Part C disable checks: patch one fix out, run the named suites in a fresh sbt
JVM, restore the file, and verify its sha256. Usage: python3 -I disable.py <repo> <logdir> [label...]
"""
import hashlib
import os
import subprocess
import sys

REPO, LOGS = sys.argv[1], sys.argv[2]
ONLY = set(sys.argv[3:])
S = "modules/services/src/main/scala/scholarchain/services/"
HTTP, RES, GEA = S + "Http.scala", S + "Resilience.scala", S + "main/GraphEvidenceApi.scala"
P = "scholarchain.services."

CHECKS = [
    ("D5-alarm", HTTP,
     '      if unread then interruptAfter(HttpService.UnreadBodyDrainMs)(ex.close()) else ex.close()\n',
     '      ex.close()\n',
     [P + "RequestReadSpec"]),
    ("D5-conn-close", HTTP,
     '      if unread then out.set("Connection", "close")\n',
     '      ()\n',
     [P + "RequestReadSpec"]),
    ("D5-timeout", HTTP,
     '      System.setProperty("sun.net.httpserver.maxReqTime", RequestReadTimeoutSeconds.toString)\n',
     '      ()\n',
     [P + "RequestReadSpec"]),
    ("D5-400", HTTP,
     '      try ex.getRequestBody.readNBytes(max + 1)\n'
     '      catch\n'
     '        case _: java.io.IOException => // closed by the read timeout, or by the client\n'
     '          throw ApiError(ErrorCode.INVALID_FIELD, "the body was not received in full")\n',
     '      ex.getRequestBody.readNBytes(max + 1)\n',
     [P + "RequestReadSpec"]),
    ("main-answers", GEA,
     '  def main(args: Array[String]): Unit = Launcher.run(start(args.toSeq))\n',
     '  def main(args: Array[String]): Unit =\n    Launcher.main(args, Apps.graphEvidenceApi(_, _, _, _), _.services.graphEvidenceApi.port)\n',
     [P + "CorpusQuerySpec"]),
    ("F3-workflow-queue", RES,
     'executor(workflowThreads, SynchronousQueue[Runnable](), "workflow")',
     'executor(workflowThreads, LinkedBlockingQueue[Runnable](4), "workflow")',
     [P + "BuildingBlocksSpec"]),
    ("F3-timeout-from-submission", RES,
     '        if left <= 0 then throw e else f.get(left, TimeUnit.MILLISECONDS)\n',
     '        throw e\n',
     [P + "BuildingBlocksSpec"]),
    ("F3-no-shedding", HTTP,
     'LinkedBlockingQueue[Runnable](queue), factory)',
     'LinkedBlockingQueue[Runnable](), factory)',
     [P + "GraphEvidenceApiSpec"]),
]


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


summary = []
for label, rel, old, new, suites in CHECKS:
    if ONLY and label not in ONLY:
        continue
    path = os.path.join(REPO, rel)
    with open(path, "rb") as f:
        original = f.read()
    before = hashlib.sha256(original).hexdigest()
    text = original.decode("utf-8")
    if text.count(old) != 1:
        summary.append(f"{label}: PATCH NOT APPLICABLE ({text.count(old)} matches)")
        continue
    log = os.path.join(LOGS, f"disable-{label}.log")
    try:
        with open(path, "w", encoding="utf-8") as f:
            f.write(text.replace(old, new))
        env = dict(os.environ, SPARK_LOCAL_IP="127.0.0.1")
        cmd = ["sbt", "-J-Xmx4g", "-batch", "services/testOnly " + " ".join(suites)]
        with open(log, "w") as out:
            out.write(f"# {label}: {rel}\n# patch: {old!r} -> {new!r}\n# cmd: {' '.join(cmd)}\n")
            out.flush()
            code = subprocess.run(cmd, cwd=REPO, env=env, stdout=out, stderr=subprocess.STDOUT).returncode
    finally:
        with open(path, "wb") as f:
            f.write(original)
    after = sha(path)
    ok = "OK" if after == before else "MISMATCH"
    with open(log, "a") as out:
        out.write(f"# exit {code}\n# sha256 before {before}\n# sha256 after  {after} {ok}\n")
    summary.append(f"{label}: exit {code}, {rel} sha256 {after} restore {ok}")

print("\n".join(summary))
