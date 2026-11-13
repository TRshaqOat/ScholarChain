"""Repair-01 disable checks: patch one rule out, run the named suites, restore, verify sha256.

usage: python3 -I disable.py <id>   (run from the repository root)
"""
import hashlib, os, shutil, subprocess, sys

ROOT = os.getcwd()
SRC = "modules/services/src/main/scala/scholarchain/services/"
LOGS = "scratch/phase-10-logs/repair-01/"
SCRATCH = os.path.dirname(os.path.abspath(__file__))

AWAIT = ("val a = await(f, start, p.timeoutMs)", "val a = f.get(p.timeoutMs, TimeUnit.MILLISECONDS)")
SHARED = ("(if p.workflow then workflows else pool).submit(task)", "pool.submit(task)")
SUITES_F1 = "scholarchain.services.BuildingBlocksSpec scholarchain.services.SaturationSpec"

PATCHES = {
    "D0-original-behaviour": ("Resilience.scala", [AWAIT, SHARED], SUITES_F1),
    "D1-queue-wait-counts": ("Resilience.scala", [AWAIT], SUITES_F1),
    "D2-shared-pool": ("Resilience.scala", [SHARED], SUITES_F1),
    "D3-worker-claims-without-slot": (
        "Worker.scala",
        [("case Some(_) if !ds.workflowSlotFree =>", "case Some(_) if false =>")],
        "scholarchain.services.SaturationSpec",
    ),
    "D4-worker-loop-receives-without-slot": (
        "Worker.scala",
        [("if ds.openBreakers.nonEmpty || !ds.workflowSlotFree then", "if ds.openBreakers.nonEmpty then")],
        "scholarchain.services.SaturationSpec",
    ),
    "D5-probe-key-at-bucket-root": (
        "Services.scala",
        [('s"${Audit.base("logs", setup.snapshot, setup.runId)}/readiness-probe"', '"readiness-probe"')],
        "scholarchain.services.QueryApiSpec",
    ),
    "D6-graph-evidence-opens-summaries": (
        "Composition.scala",
        [("case Some(key) if !answers =>", "case Some(key) if false =>")],
        "scholarchain.services.CorpusQuerySpec",
    ),
    "D7-iae-is-400": (
        "Resilience.scala",
        [('throw ApiError(ErrorCode.INTERNAL_ERROR, "unexpected internal failure")',
          'throw ApiError(ErrorCode.INVALID_FIELD, "a parameter is out of range")')],
        "scholarchain.services.BuildingBlocksSpec scholarchain.services.GraphEvidenceApiSpec",
    ),
}


def sha(p):
    with open(p, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def main(pid):
    name, reps, suites = PATCHES[pid]
    path = SRC + name
    backup = os.path.join(SCRATCH, "disable-backup-" + name)
    shutil.copyfile(path, backup)
    before = sha(path)
    with open(path) as f:
        s = f.read()
    for old, new in reps:
        assert s.count(old) == 1, (pid, old)
        s = s.replace(old, new)
    log = LOGS + "disable-" + pid + ".log"
    try:
        with open(path, "w") as f:
            f.write(s)
        env = dict(os.environ, SPARK_LOCAL_IP="127.0.0.1")
        with open(log, "w") as out:
            out.write(f"disable {pid}: {path}\n")
            for old, new in reps:
                out.write(f"  replaced: {old}\n  with:     {new}\n")
            out.write(f"sha256 before: {before}\n")
            out.flush()
            r = subprocess.run(
                ["sbt", "-J-Xmx6g", "-batch", f"services/testOnly {suites}"],
                stdout=out, stderr=subprocess.STDOUT, env=env)
            out.write(f"sbt exit {r.returncode}\n")
    finally:
        shutil.copyfile(backup, path)
    after = sha(path)
    with open(log, "a") as out:
        out.write(f"restored sha256: {after} {'OK' if after == before else 'MISMATCH'}\n")
    print(pid, "restore", "OK" if after == before else "MISMATCH")


if __name__ == "__main__":
    for pid in sys.argv[1:]:
        main(pid)
