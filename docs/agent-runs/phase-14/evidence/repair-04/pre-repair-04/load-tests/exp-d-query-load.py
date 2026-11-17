#!/usr/bin/env python3
"""Experiment D: bounded closed-loop load on graph-evidence-api (no model calls).

Run only through scripts/run-experiment.sh with experiments/manifests/exp-d-query-load-v1.json.
Python 3 standard library only (D-09).

Inputs come from the same sources as scripts/smoke-test.sh: terraform output (local state, no
cloud call) for alb_dns_name, api_key_secret_arn and the ECS names, and Secrets Manager for the
API key, which is held in memory only and never printed, logged or written. Evidence and node IDs
come from the latest PASS smoke record (operations/releases/smoke-<stamp>.json and its query.json).

Requests (GET only, graph-evidence-api; no /v1/query, so no Haiku call: the deployed tasks set
SCHOLARCHAIN_STUB_LLM=false and stubbing needs a task-definition change):
  /v1/evidence/<evidenceId>
  /v1/graph/nodes/<researcherId>
  /v1/graph/nodes/<researcherId>/neighbors?direction=both&depth=1&limit=50&edgeTypes=COAUTHORED_WITH

Per level: <c> workers, each a closed loop over the request list for --level-seconds, one
keep-alive connection per worker. graph-evidence-api desired/running/pending counts are read
(aws ecs describe-services, read-only) before and after each level and once more after
--post-observe-seconds. Raw results go only under $SCHOLARCHAIN_EXPERIMENT_DIR.

Exit: 0 every level ran; 1 a level was aborted (error rate above --abort-error-rate); 2 usage;
3 precondition (missing input, runtime disabled, no PASS smoke record, account mismatch).
"""

import argparse
import csv
import glob
import http.client
import json
import math
import os
import subprocess
import sys
import threading
import time
import urllib.parse
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TF_DIR = os.path.join(ROOT, "infra", "terraform", "envs", "course")
SMOKE_DIR = os.path.join(ROOT, "operations", "releases")
SERVICE = "graph-evidence-api"
NEIGHBORS_QS = "direction=both&depth=1&limit=50&edgeTypes=COAUTHORED_WITH"
AWS_TIMEOUTS = ["--cli-connect-timeout", "10", "--cli-read-timeout", "120"]

# Hard ceilings; the manifest's flags must stay inside them.
MAX_LEVEL = 8
MAX_LEVELS = 4
MAX_LEVEL_SECONDS = 300
MAX_REQUESTS_PER_LEVEL = 20000
REQUEST_TIMEOUT_S = 10


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def die(msg, code):
    print("==> FAIL %s (exit %d)" % (msg, code), file=sys.stderr)
    sys.exit(code)


def percentile(sorted_ms, p):
    """Nearest-rank percentile of an ascending list; None when empty."""
    if not sorted_ms:
        return None
    return sorted_ms[max(1, math.ceil(p / 100.0 * len(sorted_ms))) - 1]


def self_test():
    xs = list(range(1, 101))
    assert percentile(xs, 50) == 50 and percentile(xs, 95) == 95 and percentile(xs, 99) == 99
    assert percentile([7], 99) == 7 and percentile([], 50) is None
    assert percentile([1, 2, 3, 4], 50) == 2
    print("==> OK self-test")


def latest_pass_smoke():
    """(record path, evidence IDs, researcher IDs) of the newest PASS smoke record, or None."""
    for rec in sorted(glob.glob(os.path.join(SMOKE_DIR, "smoke-*.json")), reverse=True):
        try:
            with open(rec) as f:
                r = json.load(f)
            if r.get("result") != "PASS":
                continue
            with open(os.path.join(SMOKE_DIR, r["bodies"], "query.json")) as f:
                q = json.load(f)
        except (OSError, ValueError, KeyError):
            continue
        ev = sorted({e for c in q.get("claims") or [] for e in c.get("evidenceIds") or []})
        nodes = sorted({x["researcherId"] for x in (q.get("answer") or {}).get("resolutions") or []
                        if x.get("researcherId")})
        if ev and nodes:
            return rec, ev[:10], nodes[:5]
    return None


def request_list(ev, nodes):
    paths = [("evidence", "/v1/evidence/" + e) for e in ev]
    for n in nodes:
        q = urllib.parse.quote(n, safe="")
        paths.append(("node", "/v1/graph/nodes/" + q))
        paths.append(("neighbors", "/v1/graph/nodes/%s/neighbors?%s" % (q, NEIGHBORS_QS)))
    return paths


def run_cmd(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        # stderr of terraform output / aws ecs / sts never holds the key; the secret call is separate.
        die("%s failed: %s" % (" ".join(args[:3]), r.stderr.strip()[:300]), 3)
    return r.stdout


def aws(*args):
    return run_cmd(["aws"] + list(args) + AWS_TIMEOUTS)


def read_api_key(secret_arn):
    r = subprocess.run(["aws", "secretsmanager", "get-secret-value", "--secret-id", secret_arn,
                        "--query", "SecretString", "--output", "text"] + AWS_TIMEOUTS,
                       capture_output=True, text=True)
    key = r.stdout.strip()
    if r.returncode != 0 or not key or key == "None":
        die("the API key secret could not be read (stderr not shown)", 3)
    return key


def ecs_counts(cluster, label, out_dir):
    raw = aws("ecs", "describe-services", "--cluster", cluster, "--services", SERVICE, "--query",
              "services[0].{desired:desiredCount,running:runningCount,pending:pendingCount,"
              "events:events[0:5].[createdAt,message]}", "--output", "json")
    rec = {"label": label, "at": utc_now(), "service": SERVICE, "describe": json.loads(raw)}
    with open(os.path.join(out_dir, "ecs-%s.json" % label), "w") as f:
        json.dump(rec, f, indent=2)
    d = rec["describe"]
    print("    ecs %-22s desired=%s running=%s pending=%s" % (label, d["desired"], d["running"], d["pending"]))
    return {k: d[k] for k in ("desired", "running", "pending")}


def worker(host, port, key, paths, offset, deadline, cap, level, wid, rows, lock):
    headers = {"X-Api-Key": key, "X-Request-Id": "exp-d-c%d-w%d" % (level, wid)}
    conn = None
    i = offset
    while time.monotonic() < deadline:
        with lock:
            if len(rows) >= cap:
                return
        kind, path = paths[i % len(paths)]
        i += 1
        start = utc_now()
        t0 = time.perf_counter()
        status, err = 0, ""
        try:
            if conn is None:
                conn = http.client.HTTPConnection(host, port, timeout=REQUEST_TIMEOUT_S)
            conn.request("GET", path, headers=headers)
            resp = conn.getresponse()
            resp.read()
            status = resp.status
            if (resp.getheader("Connection") or "").lower() == "close":
                conn.close()
                conn = None
        except Exception as e:  # recorded as its type only, never with headers
            err = type(e).__name__
            if conn is not None:
                conn.close()
            conn = None
        ms = (time.perf_counter() - t0) * 1000.0
        with lock:
            rows.append([level, wid, start, kind, path, status, "%.1f" % ms, err])
    if conn is not None:
        conn.close()


def run_level(base, key, paths, level, seconds, out_dir):
    u = urllib.parse.urlsplit(base)
    rows, lock = [], threading.Lock()
    deadline = time.monotonic() + seconds
    started = utc_now()
    ts = [threading.Thread(target=worker, args=(u.hostname, u.port or 80, key, paths,
                                                w * (len(paths) // level or 1), deadline,
                                                MAX_REQUESTS_PER_LEVEL, level, w, rows, lock))
          for w in range(level)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    ended = utc_now()
    with open(os.path.join(out_dir, "requests-c%d.csv" % level), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["concurrency", "worker", "startUtc", "kind", "path", "status", "latencyMs", "error"])
        w.writerows(rows)
    lat = sorted(float(r[6]) for r in rows)
    ok = sorted(float(r[6]) for r in rows if 200 <= r[5] < 300)
    errors = sum(1 for r in rows if not 200 <= r[5] < 300)
    by_status = {}
    for r in rows:
        by_status[str(r[5])] = by_status.get(str(r[5]), 0) + 1
    s = {"concurrency": level, "startedAt": started, "endedAt": ended, "seconds": seconds,
         "requests": len(rows), "throughputPerSecond": round(len(rows) / float(seconds), 2),
         "errors": errors, "errorRate": round(errors / float(len(rows)), 4) if rows else None,
         "statusCounts": by_status,
         "latencyMsAll": {"p50": percentile(lat, 50), "p95": percentile(lat, 95), "p99": percentile(lat, 99)},
         "latencyMs2xx": {"p50": percentile(ok, 50), "p95": percentile(ok, 95), "p99": percentile(ok, 99)},
         "requestCapReached": len(rows) >= MAX_REQUESTS_PER_LEVEL}
    print("    level c=%d requests=%d rps=%s errors=%d p50=%s p95=%s p99=%s" % (
        level, s["requests"], s["throughputPerSecond"], errors, s["latencyMsAll"]["p50"],
        s["latencyMsAll"]["p95"], s["latencyMsAll"]["p99"]))
    return s


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--levels", default="1,2,4,8")
    ap.add_argument("--level-seconds", type=int, default=180)
    ap.add_argument("--pause-seconds", type=int, default=30)
    ap.add_argument("--post-observe-seconds", type=int, default=120)
    ap.add_argument("--abort-error-rate", type=float, default=0.5)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        self_test()
        return 0
    try:
        levels = [int(x) for x in a.levels.split(",")]
    except ValueError:
        die("--levels must be comma-separated integers", 2)
    if not (1 <= len(levels) <= MAX_LEVELS and all(1 <= c <= MAX_LEVEL for c in levels)):
        die("--levels: 1 to %d levels, each 1 to %d" % (MAX_LEVELS, MAX_LEVEL), 2)
    if not (10 <= a.level_seconds <= MAX_LEVEL_SECONDS and 0 <= a.pause_seconds <= 120
            and 0 <= a.post_observe_seconds <= 300 and 0 < a.abort_error_rate <= 1):
        die("a duration or the abort rate is out of bounds (see --help)", 2)
    planned_s = len(levels) * a.level_seconds + (len(levels) - 1) * a.pause_seconds + a.post_observe_seconds
    bounds = {"levels": levels, "levelSeconds": a.level_seconds, "pauseSeconds": a.pause_seconds,
              "postObserveSeconds": a.post_observe_seconds, "abortErrorRate": a.abort_error_rate,
              "maxRequestsPerLevel": MAX_REQUESTS_PER_LEVEL, "requestTimeoutSeconds": REQUEST_TIMEOUT_S,
              "plannedSeconds": planned_s, "haikuCalls": 0, "service": SERVICE, "serviceMaxTasks": 2}

    if a.dry_run:
        smoke = latest_pass_smoke()
        ev, nodes = (smoke[1], smoke[2]) if smoke else (["<evidenceId>"], ["<researcherId>"])
        print("==> STEP dry-run (no terraform, AWS or HTTP call)")
        print("    inputs: terraform output alb_dns_name, api_key_secret_arn, ecs_cluster_name, "
              "account_id, runtime_enabled; Secrets Manager API key (memory only)")
        print("    ids from: %s" % (smoke[0] if smoke else "the latest PASS operations/releases/smoke-*.json (none yet)"))
        print("    bounds: %s" % json.dumps(bounds))
        print("    planned requests (round-robin per worker, GET, X-Api-Key header):")
        for kind, path in request_list(ev, nodes):
            print("      %-9s GET http://<alb_dns_name>%s" % (kind, path))
        for c in levels:
            print("    level c=%d: ecs describe-services %s; %d s closed loop; ecs describe-services" % (c, SERVICE, a.level_seconds))
        print("    then %d s idle and a final ecs describe-services; write summary.json" % a.post_observe_seconds)
        print("==> OK dry-run")
        return 0

    out_dir = os.environ.get("SCHOLARCHAIN_EXPERIMENT_DIR")
    if not out_dir or not os.path.isdir(out_dir):
        die("SCHOLARCHAIN_EXPERIMENT_DIR is not a directory (run through scripts/run-experiment.sh)", 3)
    smoke = latest_pass_smoke()
    if not smoke:
        die("no PASS smoke record with evidence and researcher IDs under operations/releases", 3)
    tf = json.loads(run_cmd(["terraform", "-chdir=" + TF_DIR, "output", "-json"]))
    if not tf.get("runtime_enabled", {}).get("value"):
        die("runtime_enabled is false", 3)
    acct = aws("sts", "get-caller-identity", "--query", "Account", "--output", "text").strip()
    if acct != tf["account_id"]["value"]:
        die("profile account %s differs from the state account" % acct, 3)
    base = "http://" + tf["alb_dns_name"]["value"]
    cluster = tf["ecs_cluster_name"]["value"]
    paths = request_list(smoke[1], smoke[2])
    with open(os.path.join(out_dir, "inputs.json"), "w") as f:
        json.dump({"baseUrl": base, "account": acct, "region": os.environ.get("AWS_REGION"),
                   "cluster": cluster, "albArnSuffix": tf.get("alb_arn_suffix", {}).get("value"),
                   "smokeRecord": os.path.relpath(smoke[0], ROOT), "evidenceIds": smoke[1],
                   "researcherIds": smoke[2], "requests": paths, "bounds": bounds,
                   "startedAt": utc_now()}, f, indent=2)
    key = read_api_key(tf["api_key_secret_arn"]["value"])
    print("==> OK API key read (not printed)")

    summary = {"schemaVersion": "exp-d-summary-v1", "levels": [], "aborted": None}
    for n, c in enumerate(levels):
        if n:
            time.sleep(a.pause_seconds)
        before = ecs_counts(cluster, "before-c%d" % c, out_dir)
        s = run_level(base, key, paths, c, a.level_seconds, out_dir)
        s["ecsBefore"], s["ecsAfter"] = before, ecs_counts(cluster, "after-c%d" % c, out_dir)
        summary["levels"].append(s)
        if s["errorRate"] is None or (s["requests"] >= 20 and s["errorRate"] > a.abort_error_rate):
            summary["aborted"] = "level c=%d error rate %s above %s" % (c, s["errorRate"], a.abort_error_rate)
            break
    del key
    if summary["aborted"] is None:
        time.sleep(a.post_observe_seconds)
        summary["ecsPostObserve"] = ecs_counts(cluster, "post-observe", out_dir)
    summary["endedAt"] = utc_now()
    with open(os.path.join(out_dir, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print("==> EXP-D levels=%d aborted=%s" % (len(summary["levels"]), summary["aborted"]))
    return 1 if summary["aborted"] else 0


if __name__ == "__main__":
    sys.exit(main())
