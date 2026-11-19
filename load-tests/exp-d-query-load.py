#!/usr/bin/env python3
"""Experiment D: bounded closed-loop load on graph-evidence-api (no model calls).

Run only through scripts/run-experiment.sh with experiments/manifests/exp-d-query-load-v1.json or
experiments/manifests/exp-d-scale-v1.json. Python 3 standard library only (D-09).

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

D-scale (Phase 15 ruling 2), only when --poll-seconds is above 0 (default 0: off, so
exp-d-query-load-v1 runs exactly as before): a background thread samples graph-evidence-api's
desired, running and pending counts every --poll-seconds from just before the preflight until the
post-observation ends, into ecs-timeline.csv (UTC). After summary.json is written, read-only calls
collect into collect-<name>.json: Application Auto Scaling scaling activities and policies, the ECS
service events and load balancers, the target-tracking alarms and their history, and CloudWatch
1-minute metrics (get-metric-data) from 5 minutes before the start to the collection time; index in
collect.json, rewritten after every call. A failed or timed-out call is recorded in its file and never
changes the exit code or summary.json. Collection stops after COLLECT_BUDGET_S seconds, and the three
describe-services task-count reads around the level and after the post-observation time out after
POLL_CALL_TIMEOUT_S, so the manifest's maxMinutes keeps at least 5 minutes of margin (code review 1 m3).

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
from datetime import datetime, timedelta, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TF_DIR = os.path.join(ROOT, "infra", "terraform", "envs", "course")
SMOKE_DIR = os.path.join(ROOT, "operations", "releases")
SERVICE = "graph-evidence-api"
NEIGHBORS_QS = "direction=both&depth=1&limit=50&edgeTypes=COAUTHORED_WITH"
AWS_TIMEOUTS = ["--cli-connect-timeout", "10", "--cli-read-timeout", "120"]

# Hard ceilings; the manifest's flags must stay inside them.
MAX_LEVEL = 8
MAX_LEVELS = 4
MAX_LEVEL_SECONDS = 600  # D-scale holds one level for 600 s; exp-d-query-load-v1 uses 180
MAX_POST_OBSERVE_SECONDS = 1800  # D-scale observes scale-in (about 15 min below target) for 1,500 s
MAX_REQUESTS_PER_LEVEL = 20000  # the default cap (--max-requests-per-level)
MAX_REQUESTS_CEILING = 40000  # c=8 ran at about 36 requests/s in window 2: 600 s is about 21,500
REQUEST_TIMEOUT_S = 10
COLLECT_BUDGET_S = 90  # all post-run collection calls; each call at most 60 s (m3: was 150)
POLL_CALL_TIMEOUT_S = 20
READ_ONLY_CALLS = {("ecs", "describe-services"), ("application-autoscaling", "describe-scaling-activities"),
                   ("application-autoscaling", "describe-scaling-policies"), ("cloudwatch", "describe-alarms"),
                   ("cloudwatch", "describe-alarm-history"), ("cloudwatch", "get-metric-data")}
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
    self_test_fakes()
    print("==> OK self-test")


def self_test_fakes():
    """execute() against a local HTTP server and a fake `aws` (subprocess.run replaced): the v1 path
    is unchanged; the D-scale poller and collection are read-only, UTC, and failures stay recorded."""
    import contextlib
    import http.server
    import io
    import shutil
    import tempfile

    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

        def log_message(self, *args):
            pass
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % srv.server_address[1]
    tg = "arn:aws:elasticloadbalancing:us-east-2:111122223333:targetgroup/tg-x/abc"
    canned = {"describe-services": {"desired": 1, "running": 1, "pending": 0, "events": [],
                                    "loadBalancers": [{"targetGroupArn": tg}]},
              "describe-scaling-activities": {"ScalingActivities": []},
              "describe-scaling-policies": {"ScalingPolicies": [{"Alarms": [
                  {"AlarmName": "TargetTracking-x-AlarmHigh-1"}, {"AlarmName": "TargetTracking-x-AlarmLow-2"}]}]},
              "describe-alarms": {"MetricAlarms": []}, "describe-alarm-history": {"AlarmHistoryItems": []},
              "get-metric-data": {"MetricDataResults": []}}
    calls, fail, cur = [], {}, {}

    def fake(args, **kw):
        op = args[2]
        idx_path = os.path.join(cur["dir"], "collect.json")  # calls indexed so far (m3), None before the first
        n_idx = len(json.load(open(idx_path))["calls"]) if os.path.exists(idx_path) else None
        calls.append((list(args), kw.get("env"), os.path.exists(os.path.join(cur["dir"], "summary.json")),
                      kw.get("timeout"), n_idx))
        if fail.get(op) == "timeout":
            raise subprocess.TimeoutExpired(args, 1)
        if fail.get(op):
            return subprocess.CompletedProcess(args, 255, "", "boom")
        return subprocess.CompletedProcess(args, 0, json.dumps(canned[op]), "")
    tf = {"alb_arn_suffix": {"value": "app/alb/1"}, "neptune_cluster_id": {"value": "graph"}}

    def go(poll, post=1):
        cur["dir"] = tempfile.mkdtemp(prefix="exp-d-selftest-")
        del calls[:]
        a = argparse.Namespace(pause_seconds=0, level_seconds=1, post_observe_seconds=post,
                               abort_error_rate=0.05, abort_window_seconds=10, abort_min_requests=20,
                               abort_consecutive=10, max_requests_per_level=MAX_REQUESTS_PER_LEVEL,
                               poll_seconds=poll)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = execute(base, "k", request_list(["ev-1"], ["r-1"]), [1], a, "cl", tf, cur["dir"], {})
        files = sorted(os.listdir(cur["dir"]))
        data = {}
        for fn in files:
            with open(os.path.join(cur["dir"], fn)) as f:
                data[fn] = f.read()
        shutil.rmtree(cur["dir"])
        return code, files, data, list(calls)

    real = subprocess.run
    subprocess.run = fake
    try:
        # v1 (--poll-seconds 0): the same files and the same three describe-services calls as before.
        code, files, data, cs = go(0)
        assert code == 0, code
        assert files == ["ecs-after-c1.json", "ecs-before-c1.json", "ecs-post-observe.json", "preflight.json",
                         "requests-c1.csv", "summary.json"], files
        assert [c[0][1:3] for c in cs] == [["ecs", "describe-services"]] * 3 and all(c[1] is None for c in cs)
        assert all(c[3] is None for c in cs)  # v1: no subprocess timeout, as before (m3)
        s = json.loads(data["summary.json"])
        assert s["levels"][0]["requests"] > 0 and s["levels"][0]["errors"] == 0 and s["aborted"] is None

        # D-scale: timeline plus collection, every call allowlisted, TZ=UTC, after summary.json.
        code, files, data, cs = go(1, post=2)
        assert code == 0, code
        for fn in ["ecs-timeline.csv", "collect.json", "collect-scaling-activities.json",
                   "collect-scaling-policies.json", "collect-ecs-service.json", "collect-alarms.json",
                   "collect-alarm-history-0.json", "collect-alarm-history-1.json", "collect-metrics.json"]:
            assert fn in files, fn
        tl = list(csv.reader(data["ecs-timeline.csv"].splitlines()))
        assert tl[0] == ["utc", "desired", "running", "pending", "error"] and len(tl) >= 3, tl
        assert tl[1][0].endswith("Z") and tl[1][1:] == ["1", "1", "0", ""], tl[1]
        assert all(tuple(c[0][1:3]) in READ_ONLY_CALLS for c in cs)
        new = [c for c in cs if c[1] is not None]
        assert len(cs) - len(new) == 3 and all(c[1]["TZ"] == "UTC" for c in new)
        assert all(c[3] == POLL_CALL_TIMEOUT_S for c in cs if c[1] is None)  # m3: the 3 ecs_counts calls
        coll = [c for c in new if c[0][2] != "describe-services" or "events:events" in " ".join(c[0])]
        assert len(coll) == 7 and all(c[2] for c in coll), [c[0][1:3] for c in coll]
        assert [c[4] for c in coll] == [None, 1, 2, 3, 4, 5, 6], [c[4] for c in coll]  # m3: index per call
        m = json.loads(data["collect-metrics.json"])
        q = json.loads(m["command"][m["command"].index("--metric-data-queries") + 1])
        assert [x["Id"] for x in q] == ["ecs_cpu", "ecs_memory", "alb_healthy_min", "alb_healthy_max",
                                        "alb_requests", "alb_trt_p50", "alb_trt_p95", "alb_target_5xx",
                                        "neptune_cpu"]
        assert {"Name": "TargetGroup", "Value": "targetgroup/tg-x/abc"} in q[2]["MetricStat"]["Metric"]["Dimensions"]
        assert all(x["MetricStat"]["Period"] == 60 for x in q)
        idx = json.loads(data["collect.json"])
        assert all(c["error"] is None for c in idx["calls"]) and idx["skippedMetricQueries"] == []
        assert idx["metricsStartUtc"].endswith(":00Z") and idx["metricsStartUtc"] < idx["metricsEndUtc"]

        # Failed collection and polling: recorded; the load result and exit code stand.
        fail.update({"describe-services": "exit", "describe-scaling-activities": "timeout",
                     "describe-scaling-policies": "exit", "get-metric-data": "exit"})
        code, files, data, cs = go(1)
        assert code == 0 and json.loads(data["summary.json"])["levels"][0]["requests"] > 0
        assert all(r[4].startswith("aws exit 255") for r in list(csv.reader(data["ecs-timeline.csv"].splitlines()))[1:])
        idx = json.loads(data["collect.json"])
        errs = {c["name"]: c["error"] for c in idx["calls"]}
        assert errs["scaling-activities"] == "TimeoutExpired" and errs["metrics"].startswith("aws exit 255")
        assert "no target-tracking alarm" in errs["alarms"] and idx["targetGroupArn"] is None
        assert idx["skippedMetricQueries"] == ["alb_healthy_min", "alb_healthy_max", "alb_requests",
                                               "alb_trt_p50", "alb_trt_p95", "alb_target_5xx"]
        assert json.loads(data["collect-scaling-activities.json"])["data"] is None
        assert cs[-1][0][2] == "get-metric-data" and cs[-1][4] == 4  # m3: includes the no-alarm entry
        assert "collect.json.tmp" not in files

        # m3: an ecs_counts timeout (D-scale only) is recorded; the run goes on and exits 0.
        fail.clear()
        fail["describe-services"] = "timeout"
        code, files, data, cs = go(1)
        assert code == 0, code
        s = json.loads(data["summary.json"])
        assert s["levels"][0]["ecsBefore"] == {"error": "TimeoutExpired"} == s["ecsPostObserve"]
        assert json.loads(data["ecs-after-c1.json"])["error"] == "TimeoutExpired" and "collect.json" in files
        fail.clear()

        # Spent budget: every call is skipped without running aws.
        cur["dir"] = tempfile.mkdtemp(prefix="exp-d-selftest-")
        del calls[:]
        with contextlib.redirect_stdout(io.StringIO()):
            collect("cl", tf, datetime.now(timezone.utc), cur["dir"], budget_s=0)
        with open(os.path.join(cur["dir"], "collect.json")) as f:
            idx = json.load(f)
        shutil.rmtree(cur["dir"])
        assert calls == [] and all(c["error"].startswith(("skipped", "no target-tracking")) for c in idx["calls"])

        # A mutating call is refused before any subprocess.
        assert aws_read(["ecs", "update-service", "--desired-count", "2"], 5)[1].startswith("refused")
        assert calls == []
    finally:
        subprocess.run = real
        srv.shutdown()


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


def ecs_counts(cluster, label, out_dir, timeout=None):
    """Task counts. Called only after load has started, so a failure is recorded, never fatal.
    timeout: None (v1, unchanged) or POLL_CALL_TIMEOUT_S when --poll-seconds is set (m3)."""
    d, err = None, None
    try:
        r = subprocess.run(["aws", "ecs", "describe-services", "--cluster", cluster, "--services", SERVICE,
                            "--query", "services[0].{desired:desiredCount,running:runningCount,"
                            "pending:pendingCount,events:events[0:5].[createdAt,message]}",
                            "--output", "json"] + AWS_TIMEOUTS, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:  # only possible when timeout is set
        r, err = None, "TimeoutExpired"
    if r is not None and r.returncode != 0:
        err = "aws exit %d" % r.returncode
        print("    ecs %s: describe-services failed: %s" % (label, r.stderr.strip()[:300]), file=sys.stderr)
    elif r is not None:
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


def aws_read(args, timeout):
    """One allowlisted read-only aws call with TZ=UTC (so the CLI prints UTC timestamps):
    (data, None) or (None, error). Never raises."""
    if tuple(args[:2]) not in READ_ONLY_CALLS:
        return None, "refused: %s is not an allowlisted read-only call" % " ".join(args[:2])
    try:
        r = subprocess.run(["aws"] + list(args) + ["--output", "json"] + AWS_TIMEOUTS, capture_output=True,
                           text=True, timeout=timeout, env=dict(os.environ, TZ="UTC"))
    except Exception as e:  # TimeoutExpired or OSError
        return None, type(e).__name__
    if r.returncode != 0:
        return None, "aws exit %d: %s" % (r.returncode, r.stderr.strip()[:300])
    try:
        return json.loads(r.stdout or "null"), None
    except ValueError:
        return None, "unparsable output"


def start_poller(cluster, seconds, out_dir):
    """Samples task counts every `seconds` into ecs-timeline.csv until the returned event is set."""
    stop = threading.Event()

    def loop():
        with open(os.path.join(out_dir, "ecs-timeline.csv"), "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["utc", "desired", "running", "pending", "error"])
            while True:
                at = utc_now()
                d, err = aws_read(["ecs", "describe-services", "--cluster", cluster, "--services", SERVICE,
                                   "--query", "services[0].{desired:desiredCount,running:runningCount,"
                                   "pending:pendingCount}"], POLL_CALL_TIMEOUT_S)
                if err is None and not isinstance(d, dict):
                    err, d = "service %s not found" % SERVICE, None
                d = d or {}
                w.writerow([at, d.get("desired"), d.get("running"), d.get("pending"), err or ""])
                f.flush()
                if stop.wait(seconds):
                    return
    t = threading.Thread(target=loop, daemon=True)
    t.start()
    return stop, t


def metric_queries(cluster, alb, tg, neptune):
    """get-metric-data queries (60 s period); queries whose dimension value is unknown are skipped."""
    ecs = [{"Name": "ClusterName", "Value": cluster}, {"Name": "ServiceName", "Value": SERVICE}]
    elb = [{"Name": "TargetGroup", "Value": tg}, {"Name": "LoadBalancer", "Value": alb}]
    nep = [{"Name": "DBClusterIdentifier", "Value": neptune}]
    spec = [("ecs_cpu", "AWS/ECS", "CPUUtilization", ecs, "Average"),
            ("ecs_memory", "AWS/ECS", "MemoryUtilization", ecs, "Average"),
            ("alb_healthy_min", "AWS/ApplicationELB", "HealthyHostCount", elb, "Minimum"),
            ("alb_healthy_max", "AWS/ApplicationELB", "HealthyHostCount", elb, "Maximum"),
            ("alb_requests", "AWS/ApplicationELB", "RequestCount", elb, "Sum"),
            ("alb_trt_p50", "AWS/ApplicationELB", "TargetResponseTime", elb, "p50"),
            ("alb_trt_p95", "AWS/ApplicationELB", "TargetResponseTime", elb, "p95"),
            ("alb_target_5xx", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", elb, "Sum"),
            ("neptune_cpu", "AWS/Neptune", "CPUUtilization", nep, "Average")]
    queries, skipped = [], []
    for qid, ns, name, dims, stat in spec:
        if any(not d["Value"] for d in dims):
            skipped.append(qid)
            continue
        queries.append({"Id": qid, "ReturnData": True, "MetricStat": {
            "Metric": {"Namespace": ns, "MetricName": name, "Dimensions": dims}, "Period": 60, "Stat": stat}})
    return queries, skipped


def collect(cluster, tf, started, out_dir, budget_s=COLLECT_BUDGET_S):
    """Post-run read-only collection (D-scale). Every call's result or error goes to its own file, and
    collect.json is rewritten after every call (m3), so a TERM at maxMinutes keeps the calls so far."""
    deadline = time.monotonic() + budget_s
    index = []
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    t0 = started.replace(second=0, microsecond=0) - timedelta(minutes=5)
    head = {"schemaVersion": "exp-d-collect-v1", "metricsStartUtc": t0.strftime(fmt), "metricsEndUtc": None,
            "targetGroupArn": None, "skippedMetricQueries": None, "budgetSeconds": budget_s, "calls": index}

    def save():  # temp file and rename: a kill mid-write never leaves a truncated collect.json
        tmp = os.path.join(out_dir, "collect.json.tmp")
        with open(tmp, "w") as f:
            json.dump(head, f, indent=2)
        os.replace(tmp, os.path.join(out_dir, "collect.json"))

    def get(name, args):
        left = deadline - time.monotonic()
        at = utc_now()
        d, err = aws_read(args, min(60, left)) if left > 1 else (None, "skipped: collection budget used")
        fn = "collect-%s.json" % name
        with open(os.path.join(out_dir, fn), "w") as f:
            json.dump({"at": at, "command": ["aws"] + args, "data": d, "error": err}, f, indent=2)
        index.append({"name": name, "file": fn, "at": at, "error": err})
        save()
        print("    collect %-20s %s" % (name, "error=" + err if err else "ok"))
        return d

    scal = ["--service-namespace", "ecs", "--resource-id", "service/%s/%s" % (cluster, SERVICE),
            "--scalable-dimension", "ecs:service:DesiredCount"]
    get("scaling-activities", ["application-autoscaling", "describe-scaling-activities"] + scal)
    pol = get("scaling-policies", ["application-autoscaling", "describe-scaling-policies"] + scal)
    svc = get("ecs-service", ["ecs", "describe-services", "--cluster", cluster, "--services", SERVICE,
                              "--query", "services[0].{events:events,loadBalancers:loadBalancers}"])
    alarms = sorted({x["AlarmName"] for p in (pol or {}).get("ScalingPolicies") or []
                     for x in p.get("Alarms") or [] if x.get("AlarmName")})
    if alarms:
        get("alarms", ["cloudwatch", "describe-alarms", "--alarm-names"] + alarms)
    else:
        index.append({"name": "alarms", "file": None, "at": utc_now(),
                      "error": "no target-tracking alarm names in collect-scaling-policies.json"})
        save()
    for i, n in enumerate(alarms):
        get("alarm-history-%d" % i, ["cloudwatch", "describe-alarm-history", "--alarm-name", n])
    tg = next((lb["targetGroupArn"] for lb in (svc if isinstance(svc, dict) else {}).get("loadBalancers") or []
               if lb.get("targetGroupArn")), None)
    queries, skipped = metric_queries(cluster, (tf.get("alb_arn_suffix") or {}).get("value"),
                                      tg.split(":")[-1] if tg else None,
                                      (tf.get("neptune_cluster_id") or {}).get("value"))
    t1 = datetime.now(timezone.utc).replace(second=0, microsecond=0) + timedelta(minutes=1)
    head.update(metricsEndUtc=t1.strftime(fmt), targetGroupArn=tg, skippedMetricQueries=skipped)
    if queries:
        get("metrics", ["cloudwatch", "get-metric-data", "--start-time", t0.strftime(fmt), "--end-time",
                        t1.strftime(fmt), "--scan-by", "TimestampAscending",
                        "--metric-data-queries", json.dumps(queries)])
    save()


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


def worker(host, port, key, paths, offset, deadline, level, wid, rows, lock, stop, mon, cap):
    headers = {"X-Api-Key": key, "X-Request-Id": "exp-d-c%d-w%d" % (level, wid)}
    conn = None
    i = offset
    while not stop.is_set() and time.monotonic() < deadline:
        with lock:
            if len(rows) >= cap:
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


def run_level(base, key, paths, level, seconds, out_dir, mon, cap=MAX_REQUESTS_PER_LEVEL):
    u = urllib.parse.urlsplit(base)
    rows, lock, stop = [], threading.Lock(), threading.Event()
    started = utc_now()
    t_start = time.monotonic()
    deadline = t_start + seconds
    ts = [threading.Thread(target=worker, args=(u.hostname, u.port or 80, key, paths,
                                                w * (len(paths) // level or 1), deadline,
                                                level, w, rows, lock, stop, mon, cap))
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
         "requestCapReached": len(rows) >= cap,
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
    ecs_timeout = POLL_CALL_TIMEOUT_S if a.poll_seconds else None  # v1: no timeout, as before
    for n, c in enumerate(levels):
        if n:
            time.sleep(a.pause_seconds)
        before = ecs_counts(cluster, "before-c%d" % c, out_dir, ecs_timeout)
        mon = AbortMonitor(a.abort_error_rate, a.abort_window_seconds, a.abort_min_requests,
                           a.abort_consecutive)
        s = run_level(base, key, paths, c, a.level_seconds, out_dir, mon, a.max_requests_per_level)
        s["ecsBefore"], s["ecsAfter"] = before, ecs_counts(cluster, "after-c%d" % c, out_dir, ecs_timeout)
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
    summary["ecsPostObserve"] = ecs_counts(cluster, "post-observe", out_dir, ecs_timeout)
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
    ap.add_argument("--max-requests-per-level", type=int, default=MAX_REQUESTS_PER_LEVEL)
    ap.add_argument("--poll-seconds", type=int, default=0,
                    help="0 (default) off; 10 to 60: ECS timeline and post-run collection (D-scale)")
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
            and 0 <= a.post_observe_seconds <= MAX_POST_OBSERVE_SECONDS
            and (a.poll_seconds == 0 or 10 <= a.poll_seconds <= 60)
            and 1 <= a.max_requests_per_level <= MAX_REQUESTS_CEILING):
        die("a duration or the request cap is out of bounds (see --help)", 2)
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
              "maxRequestsPerLevel": a.max_requests_per_level, "requestTimeoutSeconds": REQUEST_TIMEOUT_S,
              "plannedSeconds": planned_s, "haikuCalls": 0, "service": SERVICE, "serviceMaxTasks": 2}
    if a.poll_seconds:  # absent when off, so exp-d-query-load-v1's records are unchanged
        bounds.update(pollSeconds=a.poll_seconds, collectBudgetSeconds=COLLECT_BUDGET_S)

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
        if a.poll_seconds:
            print("    poll: every %d s from before the preflight to the end of the post-observation, "
                  "ecs describe-services %s -> ecs-timeline.csv (UTC)" % (a.poll_seconds, SERVICE))
            print("    collect (read-only, TZ=UTC, at most %d s, failures recorded, exit code unchanged):" % COLLECT_BUDGET_S)
            for svc, op in sorted(READ_ONLY_CALLS):
                print("      aws %s %s" % (svc, op))
            print("    metrics (60 s, from 5 min before the start to the collection time):")
            for q in metric_queries("<ecs_cluster_name>", "<alb_arn_suffix>", "<targetgroup from describe-services>",
                                    "<neptune_cluster_id>")[0]:
                m = q["MetricStat"]
                print("      %-16s %s %s %s" % (q["Id"], m["Metric"]["Namespace"], m["Metric"]["MetricName"], m["Stat"]))
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

    code = execute(base, key, paths, levels, a, cluster, tf, out_dir, abort_rule)
    key = None
    return code


def execute(base, key, paths, levels, a, cluster, tf, out_dir, abort_rule):
    """The load (with the poller when --poll-seconds is set), summary.json, then the D-scale collection."""
    started = datetime.now(timezone.utc)
    summary = {"schemaVersion": "exp-d-summary-v1", "startedAt": utc_now(), "abortRule": abort_rule,
               "preflight": None, "levels": [], "aborted": None, "abortedAt": None,
               "abortWindowCounts": None, "error": None}
    poll = start_poller(cluster, a.poll_seconds, out_dir) if a.poll_seconds else None
    try:
        code = run_load(base, key, paths, levels, a, cluster, out_dir, summary)
    except Exception as e:  # type only: a message could quote a request
        summary["error"] = type(e).__name__
        print("==> FAIL unexpected %s during the load (exit 4)" % summary["error"], file=sys.stderr)
        code = 4
    finally:
        key = None
        if poll:
            poll[0].set()
            poll[1].join()
        summary["endedAt"] = utc_now()
        with open(os.path.join(out_dir, "summary.json"), "w") as f:
            json.dump(summary, f, indent=2)
    print("==> EXP-D levels=%d aborted=%s error=%s exit=%d" % (
        len(summary["levels"]), summary["aborted"], summary["error"], code))
    if a.poll_seconds:
        try:
            collect(cluster, tf, started, out_dir)
        except Exception as e:  # recorded; the load result and exit code stand
            print("    collect failed: %s (exit code unchanged)" % type(e).__name__, file=sys.stderr)
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # type only: a message could quote an input (finding 4)
        print("==> FAIL unexpected %s (exit 4)" % type(e).__name__, file=sys.stderr)
        sys.exit(4)
