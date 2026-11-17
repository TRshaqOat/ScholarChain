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

Preflight: one GET of each path, sequential, before the first level; any non-2xx stops the run
(exit 3), so later errors can be attributed to load.

Abort rule, per request kind (evidence, node, neighbors): a monitor keeps a sliding window of
--abort-window-seconds per kind, updated at every completed request. It sets a stop event, which
every worker checks before each request, when a kind has at least --abort-min-requests requests in
the window and a non-2xx rate above --abort-error-rate, or --abort-consecutive consecutive 5xx or
transport errors (timeout, reset, refused). A worker already waiting on a response stops within
the 10 s request timeout. The same per-kind rule is applied to each whole level before the next
one starts. Flag bounds: rate at most 0.05, window 5 to 10 s, at most 20 requests, at most 10
consecutive (the reviewed rule; a longer window would dilute a burst, a shorter one would rarely
reach the request minimum).

Exit: 0 every level ran; 1 the abort rule stopped the run (in a level or between levels);
2 usage; 3 precondition (missing input or terraform output, runtime disabled, no PASS smoke
record, account mismatch, a non-2xx preflight GET); 4 unexpected error (only its type is printed;
once the preflight has started, summary.json is still written with it).
"""

import argparse
import collections
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
# Abort-rule bounds: the flags cannot loosen the reviewed rule (code review 4 finding 1).
MAX_ABORT_ERROR_RATE = 0.05
MIN_ABORT_WINDOW_SECONDS = 5
MAX_ABORT_WINDOW_SECONDS = 10
MAX_ABORT_MIN_REQUESTS = 20
MAX_ABORT_CONSECUTIVE = 10


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


def hard_failure(status):
    """A 5xx response or a transport error (status 0: timeout, reset, refused)."""
    return status == 0 or status >= 500


def rule_reason(kind, n, bad, run, rate, min_requests, consecutive, scope):
    """The shared abort predicate for one request kind: a reason, or None."""
    if n >= min_requests and bad / float(n) > rate:
        return "%s: %d of %d non-2xx in %s, rate above %g" % (kind, bad, n, scope, rate)
    if run >= consecutive:
        return "%s: %d consecutive 5xx or transport errors" % (kind, run)
    return None


class AbortMonitor:
    """Sliding window per request kind (code review 4 finding 1). The caller holds the level lock."""

    def __init__(self, rate, window_s, min_requests, consecutive):
        self.rate, self.window_s = rate, window_s
        self.min_requests, self.consecutive = min_requests, consecutive
        self.win, self.bad, self.run = {}, {}, {}
        self.tripped = None

    def counts(self):
        return {k: {"windowRequests": len(q), "windowNon2xx": self.bad[k],
                    "consecutive5xxOrTransport": self.run[k]} for k, q in sorted(self.win.items())}

    def record(self, kind, status, now):
        """Adds one completed request (now: time.monotonic()); True once the rule has tripped."""
        q = self.win.setdefault(kind, collections.deque())
        ok = 200 <= status < 300
        q.append((now, ok))
        self.bad[kind] = self.bad.get(kind, 0) + (0 if ok else 1)
        while q[0][0] <= now - self.window_s:
            if not q.popleft()[1]:
                self.bad[kind] -= 1
        self.run[kind] = self.run.get(kind, 0) + 1 if hard_failure(status) else 0
        if self.tripped is None:
            reason = rule_reason(kind, len(q), self.bad[kind], self.run[kind], self.rate,
                                 self.min_requests, self.consecutive, "a %g s window" % self.window_s)
            if reason:
                self.tripped = {"at": utc_now(), "reason": reason, "windowCounts": self.counts()}
        return self.tripped is not None


def stats(rows):
    """Counts, status counts, longest 5xx/transport run and percentiles of request rows."""
    lat = sorted(float(r[6]) for r in rows)
    ok = sorted(float(r[6]) for r in rows if 200 <= r[5] < 300)
    by_status, run, max_run = {}, 0, 0
    for r in rows:
        by_status[str(r[5])] = by_status.get(str(r[5]), 0) + 1
        run = run + 1 if hard_failure(r[5]) else 0
        max_run = max(max_run, run)
    errors = len(rows) - len(ok)
    return {"requests": len(rows), "errors": errors,
            "errorRate": round(errors / float(len(rows)), 4) if rows else None,
            "statusCounts": by_status, "maxConsecutive5xxOrTransport": max_run,
            "latencyMsAll": {"p50": percentile(lat, 50), "p95": percentile(lat, 95), "p99": percentile(lat, 99)},
            "latencyMs2xx": {"p50": percentile(ok, 50), "p95": percentile(ok, 95), "p99": percentile(ok, 99)}}


def level_trip(by_kind, rate, min_requests, consecutive):
    """The same per-kind rule over a whole level, applied before the next level starts."""
    for kind, k in sorted(by_kind.items()):
        reason = rule_reason(kind, k["requests"], k["errors"], k["maxConsecutive5xxOrTransport"],
                             rate, min_requests, consecutive, "the whole level")
        if reason:
            return {"at": utc_now(), "reason": reason, "windowCounts": {
                kk: {"levelRequests": v["requests"], "levelNon2xx": v["errors"],
                     "maxConsecutive5xxOrTransport": v["maxConsecutive5xxOrTransport"]}
                for kk, v in sorted(by_kind.items())}}
    return None


def self_test():
    xs = list(range(1, 101))
    assert percentile(xs, 50) == 50 and percentile(xs, 95) == 95 and percentile(xs, 99) == 99
    assert percentile([7], 99) == 7 and percentile([], 50) is None
    assert percentile([1, 2, 3, 4], 50) == 2

    # Abort rule (finding 1) with the manifest values: trips above each threshold, not at or below.
    def feed(statuses, kind="node", step=0.01, m=None):
        m = m or AbortMonitor(0.05, 10, 20, 10)
        for i, s in enumerate(statuses):
            m.record(kind, s, 1000.0 + i * step)
        return m
    assert feed([200] * 19 + [503]).tripped is None             # 1/20 = 0.05, not above
    assert "non-2xx" in feed([200] * 18 + [503] * 2).tripped["reason"]  # 2/20 = 0.10
    assert "non-2xx" in feed([200] * 17 + [404] * 3).tripped["reason"]  # 4xx counts as non-2xx
    assert feed([503] * 2 + [200] * 16).tripped is None          # 18 requests, below the minimum
    assert feed([200] * 95 + [503] * 5).tripped is None          # 5/100 = 0.05, not above
    w = feed([503, 503])
    for i in range(20):  # 11 s later: the two errors have left the 10 s window (22 in all would trip)
        w.record("node", 200, 1011.0 + i * 0.01)
    assert w.tripped is None and w.counts()["node"]["windowNon2xx"] == 0
    assert feed([503] * 9).tripped is None                      # 9 consecutive, below 10
    assert "consecutive" in feed([503] * 10).tripped["reason"]
    assert "consecutive" in feed([0, 504] * 5).tripped["reason"]  # transport errors count
    assert feed([503] * 9 + [404] + [503] * 9).tripped is None   # a non-5xx resets the run
    m = AbortMonitor(0.05, 10, 20, 10)
    for i in range(100):
        m.record("evidence", 200, 1000.0 + i * 0.001)
    assert feed([200] * 18 + [503] * 2, m=m).tripped["reason"].startswith("node:")  # per kind
    assert m.tripped["windowCounts"]["node"] == {"windowRequests": 20, "windowNon2xx": 2,
                                                 "consecutive5xxOrTransport": 2}

    def rows(kind, statuses):
        return [[1, 0, "", kind, "/", s, "1.0", ""] for s in statuses]
    ev = {"evidence": stats(rows("evidence", [200] * 100))}
    assert level_trip(dict(ev, node=stats(rows("node", [200] * 95 + [503] * 5))), 0.05, 20, 10) is None
    assert level_trip(dict(ev, node=stats(rows("node", [200] * 94 + [503] * 6))), 0.05, 20, 10)
    assert level_trip(dict(ev, node=stats(rows("node", [503] * 9))), 0.05, 20, 10) is None
    assert level_trip(dict(ev, node=stats(rows("node", [0] * 10))), 0.05, 20, 10)
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
    """Task counts. Called only after load has started, so a failure is recorded, never fatal."""
    r = subprocess.run(["aws", "ecs", "describe-services", "--cluster", cluster, "--services", SERVICE,
                        "--query", "services[0].{desired:desiredCount,running:runningCount,"
                        "pending:pendingCount,events:events[0:5].[createdAt,message]}",
                        "--output", "json"] + AWS_TIMEOUTS, capture_output=True, text=True)
    d, err = None, None
    if r.returncode != 0:
        err = "aws exit %d" % r.returncode
        print("    ecs %s: describe-services failed: %s" % (label, r.stderr.strip()[:300]), file=sys.stderr)
    else:
        try:
            d = json.loads(r.stdout)
        except ValueError:
            err = "unparsable describe-services output"
        if err is None and not isinstance(d, dict):
            err = "service %s not found" % SERVICE
    rec = {"label": label, "at": utc_now(), "service": SERVICE, "describe": d, "error": err}
    with open(os.path.join(out_dir, "ecs-%s.json" % label), "w") as f:
        json.dump(rec, f, indent=2)
    if err:
        print("    ecs %-22s error=%s" % (label, err))
        return {"error": err}
    print("    ecs %-22s desired=%s running=%s pending=%s" % (label, d.get("desired"), d.get("running"), d.get("pending")))
    return {k: d.get(k) for k in ("desired", "running", "pending")}


def timed_get(conn, host, port, path, headers):
    """One GET on a keep-alive connection: (connection or None, status (0 on a transport error),
    error type, latency ms). Errors are kept as their type only, never with headers."""
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
    except Exception as e:
        err = type(e).__name__
        if conn is not None:
            conn.close()
        conn = None
    return conn, status, err, (time.perf_counter() - t0) * 1000.0


def worker(host, port, key, paths, offset, deadline, level, wid, rows, lock, stop, mon):
    headers = {"X-Api-Key": key, "X-Request-Id": "exp-d-c%d-w%d" % (level, wid)}
    conn = None
    i = offset
    while not stop.is_set() and time.monotonic() < deadline:
        with lock:
            if len(rows) >= MAX_REQUESTS_PER_LEVEL:
                break
        kind, path = paths[i % len(paths)]
        i += 1
        start = utc_now()
        conn, status, err, ms = timed_get(conn, host, port, path, headers)
        with lock:
            rows.append([level, wid, start, kind, path, status, "%.1f" % ms, err])
            if mon.record(kind, status, time.monotonic()):
                stop.set()
    if conn is not None:
        conn.close()


def preflight(base, key, paths, out_dir):
    """One sequential GET of each path before the first level; returns the non-2xx count."""
    u = urllib.parse.urlsplit(base)
    headers = {"X-Api-Key": key, "X-Request-Id": "exp-d-preflight"}
    conn, out = None, []
    for kind, path in paths:
        start = utc_now()
        conn, status, err, ms = timed_get(conn, u.hostname, u.port or 80, path, headers)
        out.append({"startUtc": start, "kind": kind, "path": path, "status": status,
                    "latencyMs": round(ms, 1), "error": err})
    if conn is not None:
        conn.close()
    with open(os.path.join(out_dir, "preflight.json"), "w") as f:
        json.dump(out, f, indent=2)
    bad = sum(1 for p in out if not 200 <= p["status"] < 300)
    print("    preflight requests=%d non2xx=%d" % (len(out), bad))
    return bad


def run_level(base, key, paths, level, seconds, out_dir, mon):
    u = urllib.parse.urlsplit(base)
    rows, lock, stop = [], threading.Lock(), threading.Event()
    started = utc_now()
    t_start = time.monotonic()
    deadline = t_start + seconds
    ts = [threading.Thread(target=worker, args=(u.hostname, u.port or 80, key, paths,
                                                w * (len(paths) // level or 1), deadline,
                                                level, w, rows, lock, stop, mon))
          for w in range(level)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.monotonic() - t_start
    ended = utc_now()
    with open(os.path.join(out_dir, "requests-c%d.csv" % level), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["concurrency", "worker", "startUtc", "kind", "path", "status", "latencyMs", "error"])
        w.writerows(rows)
    s = {"concurrency": level, "startedAt": started, "endedAt": ended, "seconds": seconds,
         "elapsedSeconds": round(elapsed, 3),
         "throughputPerSecond": round(len(rows) / elapsed, 2) if elapsed > 0 else None,
         "requestCapReached": len(rows) >= MAX_REQUESTS_PER_LEVEL,
         "stoppedByAbortRule": mon.tripped is not None}
    s.update(stats(rows))
    s["byKind"] = {k: stats([r for r in rows if r[3] == k]) for k in sorted({r[3] for r in rows})}
    print("    level c=%d requests=%d elapsed=%.1fs rps=%s errors=%d p50=%s p95=%s p99=%s" % (
        level, s["requests"], elapsed, s["throughputPerSecond"], s["errors"], s["latencyMsAll"]["p50"],
        s["latencyMsAll"]["p95"], s["latencyMsAll"]["p99"]))
    for k, v in s["byKind"].items():
        print("      %-9s requests=%d errors=%d p50=%s p95=%s p99=%s" % (
            k, v["requests"], v["errors"], v["latencyMsAll"]["p50"], v["latencyMsAll"]["p95"],
            v["latencyMsAll"]["p99"]))
    return s


def run_load(base, key, paths, levels, a, cluster, out_dir, summary):
    """Preflight, the levels and the post-observation. Fills summary; returns the exit code."""
    bad = preflight(base, key, paths, out_dir)
    summary["preflight"] = {"requests": len(paths), "non2xx": bad, "raw": "preflight.json"}
    if bad:
        summary["aborted"] = "preflight: %d of %d paths returned non-2xx" % (bad, len(paths))
        summary["abortedAt"] = {"utc": utc_now(), "phase": "preflight", "concurrency": None}
        print("==> FAIL %s (exit 3)" % summary["aborted"], file=sys.stderr)
        return 3
    for n, c in enumerate(levels):
        if n:
            time.sleep(a.pause_seconds)
        before = ecs_counts(cluster, "before-c%d" % c, out_dir)
        mon = AbortMonitor(a.abort_error_rate, a.abort_window_seconds, a.abort_min_requests,
                           a.abort_consecutive)
        s = run_level(base, key, paths, c, a.level_seconds, out_dir, mon)
        s["ecsBefore"], s["ecsAfter"] = before, ecs_counts(cluster, "after-c%d" % c, out_dir)
        summary["levels"].append(s)
        trip, phase = mon.tripped, "level"
        if trip is None:
            trip, phase = level_trip(s["byKind"], a.abort_error_rate, a.abort_min_requests,
                                     a.abort_consecutive), "between-levels"
        if trip is None and not s["requests"]:
            trip = {"at": utc_now(), "reason": "no request completed", "windowCounts": None}
        if trip:
            summary["aborted"] = "c=%d %s: %s" % (c, phase, trip["reason"])
            summary["abortedAt"] = {"utc": trip["at"], "phase": phase, "concurrency": c}
            summary["abortWindowCounts"] = trip["windowCounts"]
            return 1
    time.sleep(a.post_observe_seconds)
    summary["ecsPostObserve"] = ecs_counts(cluster, "post-observe", out_dir)
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--levels", default="1,2,4,8")
    ap.add_argument("--level-seconds", type=int, default=180)
    ap.add_argument("--pause-seconds", type=int, default=30)
    ap.add_argument("--post-observe-seconds", type=int, default=120)
    ap.add_argument("--abort-error-rate", type=float, default=MAX_ABORT_ERROR_RATE)
    ap.add_argument("--abort-window-seconds", type=float, default=MAX_ABORT_WINDOW_SECONDS)
    ap.add_argument("--abort-min-requests", type=int, default=MAX_ABORT_MIN_REQUESTS)
    ap.add_argument("--abort-consecutive", type=int, default=MAX_ABORT_CONSECUTIVE)
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
            and 0 <= a.post_observe_seconds <= 300):
        die("a duration is out of bounds (see --help)", 2)
    if not (0 < a.abort_error_rate <= MAX_ABORT_ERROR_RATE
            and MIN_ABORT_WINDOW_SECONDS <= a.abort_window_seconds <= MAX_ABORT_WINDOW_SECONDS
            and 1 <= a.abort_min_requests <= MAX_ABORT_MIN_REQUESTS and 1 <= a.abort_consecutive <= MAX_ABORT_CONSECUTIVE):
        die("abort rule bounds: rate 0 to %g, window %d to %d s, 1 to %d requests, 1 to %d consecutive"
            % (MAX_ABORT_ERROR_RATE, MIN_ABORT_WINDOW_SECONDS, MAX_ABORT_WINDOW_SECONDS,
               MAX_ABORT_MIN_REQUESTS, MAX_ABORT_CONSECUTIVE), 2)
    planned_s = len(levels) * a.level_seconds + (len(levels) - 1) * a.pause_seconds + a.post_observe_seconds
    abort_rule = {"scope": "per request kind (evidence, node, neighbors); sliding window within a level, "
                           "whole level between levels; preflight GET of each path before the first level",
                  "errorRateAbove": a.abort_error_rate, "windowSeconds": a.abort_window_seconds,
                  "minRequestsPerKind": a.abort_min_requests, "consecutive5xxOrTransport": a.abort_consecutive}
    bounds = {"levels": levels, "levelSeconds": a.level_seconds, "pauseSeconds": a.pause_seconds,
              "postObserveSeconds": a.post_observe_seconds, "abortRule": abort_rule,
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
        print("    preflight: one GET of each path above, sequential; any non-2xx stops the run (exit 3)")
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

    def tfo(name):
        v = (tf.get(name) or {}).get("value")
        if v is None:
            die("terraform output missing: %s" % name, 3)
        return v
    if tfo("runtime_enabled") is not True:
        die("runtime_enabled is false", 3)
    acct = aws("sts", "get-caller-identity", "--query", "Account", "--output", "text").strip()
    if acct != tfo("account_id"):
        die("profile account %s differs from the state account" % acct, 3)
    base = "http://" + tfo("alb_dns_name")
    cluster = tfo("ecs_cluster_name")
    secret_arn = tfo("api_key_secret_arn")
    paths = request_list(smoke[1], smoke[2])
    with open(os.path.join(out_dir, "inputs.json"), "w") as f:
        json.dump({"baseUrl": base, "account": acct, "region": os.environ.get("AWS_REGION"),
                   "cluster": cluster, "albArnSuffix": (tf.get("alb_arn_suffix") or {}).get("value"),
                   "smokeRecord": os.path.relpath(smoke[0], ROOT), "evidenceIds": smoke[1],
                   "researcherIds": smoke[2], "requests": paths, "bounds": bounds,
                   "startedAt": utc_now()}, f, indent=2)
    key = read_api_key(secret_arn)
    print("==> OK API key read (not printed)")

    summary = {"schemaVersion": "exp-d-summary-v1", "startedAt": utc_now(), "abortRule": abort_rule,
               "preflight": None, "levels": [], "aborted": None, "abortedAt": None,
               "abortWindowCounts": None, "error": None}
    try:
        code = run_load(base, key, paths, levels, a, cluster, out_dir, summary)
    except Exception as e:  # type only: a message could quote a request
        summary["error"] = type(e).__name__
        print("==> FAIL unexpected %s during the load (exit 4)" % summary["error"], file=sys.stderr)
        code = 4
    finally:
        key = None
        summary["endedAt"] = utc_now()
        with open(os.path.join(out_dir, "summary.json"), "w") as f:
            json.dump(summary, f, indent=2)
    print("==> EXP-D levels=%d aborted=%s error=%s exit=%d" % (
        len(summary["levels"]), summary["aborted"], summary["error"], code))
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # type only: a message could quote an input (finding 4)
        print("==> FAIL unexpected %s (exit 4)" % type(e).__name__, file=sys.stderr)
        sys.exit(4)
