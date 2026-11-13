"""Local smoke of LocalRun (fake mode, 127.0.0.1). Prints method, URL, status, and a short body.
The API key is read from the variable named in argv[1] and never printed."""
import json, os, sys, time, urllib.request, urllib.error

KEY = os.environ[sys.argv[1]]
Q, G, W = "http://127.0.0.1:8080", "http://127.0.0.1:8081", "http://127.0.0.1:8082"
QUESTION = "Have Ada Lovelace and Grace Hopper coauthored a paper?"
failures = []


def call(method, url, body=None, key=True, extra=None, expect=200, show=300):
    headers = {"Content-Type": "application/json", "X-Request-Id": "smoke-wo11", "X-Trace-Id": "smoke-trace-wo11"}
    if key:
        headers["X-Api-Key"] = KEY
    headers.update(extra or {})
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, text, hdrs = r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        status, text, hdrs = e.code, e.read().decode(), e.headers
    ok = status == expect
    if not ok:
        failures.append(f"{method} {url}: {status} != {expect}")
    print(f"{'OK  ' if ok else 'FAIL'} {method} {url.replace('http://127.0.0.1', '')} -> {status}"
          f" (X-Request-Id {hdrs.get('X-Request-Id')}{', Idempotent-Replay' if hdrs.get('Idempotent-Replay') else ''})")
    print("     " + text[:show].replace("\n", " ") + ("..." if len(text) > show else ""))
    return json.loads(text) if text else None


for base in (Q, G, W):
    call("GET", base + "/healthz", key=False)
    call("GET", base + "/readyz", key=False)

sync = call("POST", Q + "/v1/query", {"question": QUESTION})
verified = [c for c in sync["claims"] if c["verificationStatus"] == "VERIFIED"]
print(f"     sync: {len(verified)} VERIFIED of {len(sync['claims'])} claims, abstained={sync['answer']['abstained']}")
if not verified:
    failures.append("sync answer has no VERIFIED claim")

B13 = ("How many connected components does the graph have in 2000-2014, "
       "and what share of researchers is in the largest one?")
corpus = call("POST", Q + "/v1/query", {"question": B13}, show=0)
print(f"     B13-shaped sync POST: queryId={corpus['queryId']}, abstained={corpus['answer']['abstained']}, "
      f"abstention={corpus['answer']['abstention']}, window={corpus['answer']['window']['window']}")
for c in corpus["claims"]:
    print(f"     {c['claimId']} {c['verificationStatus']}: {c['text']}")
if corpus["answer"]["abstained"] or not corpus["claims"] or any(
        c["verificationStatus"] != "VERIFIED" for c in corpus["claims"]):
    failures.append("B13-shaped answer is not fully VERIFIED")
cev = corpus["claims"][0]["evidenceIds"][0] if corpus["claims"] else None
if cev:
    e = call("GET", G + f"/v1/evidence/{cev}", show=0)
    print(f"     corpus evidence {cev}: type={e['evidenceType']}, sourceUri={e['sourceUri']}, "
          f"provenance.runId={e['provenance']['runId']}, sourceRecordId={e['provenance']['sourceRecordId']}")

idem = {"Idempotency-Key": "smoke-wo11-async-1"}
job = call("POST", Q + "/v1/query", {"question": QUESTION, "execution": "async"}, extra=idem, expect=202)
call("POST", Q + "/v1/query", {"question": QUESTION, "execution": "async"}, extra=idem, expect=202)
state, deadline = job["state"], time.time() + 90
while state not in ("SUCCEEDED", "FAILED") and time.time() < deadline:
    time.sleep(0.5)
    state = call("GET", Q + job["statusUrl"], show=0)["state"]
status = call("GET", Q + job["statusUrl"], show=600)
if status["state"] != "SUCCEEDED":
    failures.append(f"job ended {status['state']}")
result = call("GET", Q + job["statusUrl"] + "/result")
averified = [c for c in result["claims"] if c["verificationStatus"] == "VERIFIED"]
print(f"     async result: mode={result['mode']}, {len(averified)} VERIFIED of {len(result['claims'])} claims")

ev = averified[0]["evidenceIds"][0] if averified else verified[0]["evidenceIds"][0]
call("GET", G + f"/v1/evidence/{ev}", show=500)
for qid in (sync["queryId"], job["queryId"]):
    x = call("GET", Q + f"/v1/explain/{qid}", show=200)
    print(f"     explain {qid}: llm={x['llm']}, stages={len(x['stages'])}, verified={x['verification']['verified']}, "
          f"cited evidence in explain={ev in x['graphEvidenceIds'] + x['semanticEvidenceIds'] if qid == job['queryId'] else 'n/a'}")

call("POST", Q + "/v1/query", {"question": QUESTION}, key=False, expect=401)
call("GET", W + "/metrics", key=False, expect=401)
wm = call("GET", W + "/metrics", show=0)
print("     worker counters: " + json.dumps({k: wm["counters"][k] for k in
      ("queueReceives", "jobsSucceeded", "jobsFailed", "duplicateDeliveries", "poisonMessages", "answers", "verifiedClaims")}))
qm = call("GET", Q + "/metrics", show=0)
print("     query-api requests: " + json.dumps(qm["requests"]))

print("SMOKE " + ("PASSED" if not failures else "FAILED: " + "; ".join(failures)))
sys.exit(1 if failures else 0)
