"""Disable checks for repair-01 Part D: patch one fix out, run its core suite, restore, verify sha256.

Usage: python3 -I disable.py <repo root> <log dir>
Each patch must match exactly once. The original bytes are restored in a finally block and the
file's sha256 is compared with the value before patching.
"""
import hashlib
import os
import subprocess
import sys

CL = "modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala"
VAL = "modules/core/src/main/scala/scholarchain/core/Validation.scala"
CONF = "modules/core/src/main/resources/scholarchain.conf"
JOIN = 'ConfigUtil.joinPath("s3-vectors", "indexes", k)'

CHECKS = [
    ("F1-position", VAL,
     '      check(a.position >= 1, s"author position must be >= 1, got ${a.position}") ++\n', "",
     "scholarchain.core.ValidationSpec"),
    ("F2-community-iterations", CONF,
     "community-max-iterations = 10   # PROVISIONAL", "community-max-iterations = 20   # PROVISIONAL",
     "scholarchain.core.config.ConfigLoaderSpec"),
    ("D11-null-index", CL,
     f".map(k => k -> str({JOIN}))",
     f".filterNot(k => c.getIsNull({JOIN})).map(k => k -> str({JOIN}))",
     "scholarchain.core.config.ConfigLoaderSpec"),
    ("D12-blank", CL,
     'c.environment == "local" || blanks.isEmpty,', 'c.environment == "local" || blanks.isEmpty || true,',
     "scholarchain.core.config.ConfigLoaderSpec"),
    ("D13-fraction", CL,
     "    if c.getDouble(p) != i then", "    if false && c.getDouble(p) != i then",
     "scholarchain.core.config.ConfigLoaderSpec"),
]


def sha(b):
    return hashlib.sha256(b).hexdigest()


def main(root, logs):
    os.chdir(root)
    env = dict(os.environ, SPARK_LOCAL_IP="127.0.0.1")
    for label, path, old, new, suite in CHECKS:
        with open(path, "rb") as f:
            original = f.read()
        before = sha(original)
        text = original.decode("utf-8")
        assert text.count(old) == 1, f"{label}: patch target found {text.count(old)} times"
        log = os.path.join(logs, f"disable-{label}.log")
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(text.replace(old, new))
            with open(log, "w") as out:
                rc = subprocess.call(
                    ["sbt", "-J-Xmx4g", "-batch", f"core/testOnly {suite}"],
                    stdout=out, stderr=subprocess.STDOUT, env=env)
        finally:
            with open(path, "wb") as f:
                f.write(original)
        with open(path, "rb") as f:
            after = sha(f.read())
        print(f"{label}\t{path}\tsbt_exit={rc}\tsha256_before={before}\tsha256_restored={after}\t"
              f"{'RESTORE-OK' if after == before else 'RESTORE-MISMATCH'}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
