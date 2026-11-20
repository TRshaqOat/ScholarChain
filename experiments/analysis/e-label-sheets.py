#!/usr/bin/env python3
"""Blind labelling sheets for experiment E (Phase 15 work order 4 A4). Read-only on the raw results.

From the E raw outputs (one directory per retrieval mode, as written by
experiments/harness/exp-e-retrieval-modes.sh: <raw>/fused, <raw>/graph-only, <raw>/vector-only) it
writes, into --out (never under labels/):
  relevance-sheet.md       per question: the question and the pooled retrieved items of all three
                           modes, deduplicated by evidence ID and shuffled with a fixed seed, each
                           shown by its content only (evidence type, facts, text); never mode, rank,
                           score, verification status, or which modes retrieved it
  relevance-template.csv   question_id,item_key,relevant (relevant left empty; the human writes 1
                           or 0 and saves it as labels/relevance-e-v1.csv)
  answers-sheet.md         per question: the answers of the three modes under blind keys, shuffled
  ratings-template.csv     question_id,answer_key,usefulness,clarity (left empty; 1 to 5)
and, into --keys-out (a separate directory, for summarize-exp-e.py only; not for the labeller):
  item-keys.csv            question_id,item_key,evidence_id,mode,rank (one row per mode that
                           retrieved the item)
  answer-keys.csv          question_id,answer_key,mode,query_id
No label, rating, relevance guess, or score is produced, suggested, or pre-filled.

Usage:
  python3 -I experiments/analysis/e-label-sheets.py --raw <stamp>/raw \
      --questions experiments/benchmark/benchmark-questions-v1.csv \
      --out scratch/e-labelling --keys-out scratch/e-labelling-keys [--seed exp-e-v1]
  python3 -I experiments/analysis/e-label-sheets.py --self-test
  python3 -I experiments/analysis/e-label-sheets.py --write-fixture <dir>   (self-test fixture only)
Exit: 0 written; 1 the raw outputs are incomplete or inconsistent; 2 usage or a refused path.
"""
import argparse
import csv
import glob
import json
import os
import random
import re
import sys
import tempfile

MODES = ["fused", "graph-only", "vector-only"]
REPO = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT_FILES = ["relevance-sheet.md", "relevance-template.csv", "answers-sheet.md", "ratings-template.csv"]
KEY_FILES = ["item-keys.csv", "answer-keys.csv"]


class Fail(Exception):
    def __init__(self, msg, code=1):
        super().__init__(msg)
        self.code = code


def inside(path, root):
    # abspath first, so a path named under labels/ is refused without touching labels/ at all
    for f in (os.path.abspath, os.path.realpath):
        p, r = f(path), f(root)
        if p == r or p.startswith(r + os.sep):
            return True
    return False


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise Fail(f"cannot read {path}: {e}")


def load_mode(raw, mode, evidence=True):
    """{question_id: {result, answer, audit, items}} of one mode (items: evidence ID -> item)."""
    mdir = os.path.join(raw, mode)
    runs = sorted(glob.glob(os.path.join(mdir, "s3/reports/rag-benchmark/snapshot=*/run=*")))
    if not runs:
        return {}
    if len(runs) > 1:
        raise Fail(f"{mdir}: more than one benchmark run: {runs}")
    snap = os.path.basename(os.path.dirname(runs[0]))
    out = {}
    bundles = {}
    for rpath in sorted(glob.glob(os.path.join(runs[0], "questions", "*.json"))):
        res = read_json(rpath)
        rr, qid = res["runtimeRunId"], res["queryId"]
        audit = read_json(os.path.join(mdir, "s3/logs/rag-runtime", snap, f"run={rr}", f"{qid}.json"))
        answer = read_json(os.path.join(mdir, "s3/reports/rag-runtime", snap, f"run={rr}", f"{qid}.answer.json"))
        items = {}
        if evidence:
            if rr not in bundles:
                bundles[rr] = load_bundle(mdir, os.path.join(mdir, "dynamodb", snap, f"run={rr}", "EvidenceBundle.jsonl"))
            items = bundles[rr].get(qid, {})
        out[res["questionId"]] = {"result": res, "answer": answer, "audit": audit, "items": items}
    return out


def load_bundle(mdir, path):
    """{query ID: {evidence ID: evidence item}} from the file-backed EvidenceBundle table."""
    out = {}
    if not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            it = json.loads(line)["Item"]
            key = it["itemKey"]["S"]
            if not key.startswith("EV#"):
                continue
            if "payload" in it:
                ev = json.loads(it["payload"]["S"])
            else:  # above the 32 KB item budget: payloadRef names the object (RUNTIME.md section 14)
                ev = read_json(os.path.join(mdir, "s3", it["payloadRef"]["S"]))
            out.setdefault(it["queryId"]["S"], {})[key[3:]] = ev
    return out


def natural(key):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", key)]


def render_item(ev):
    """Content only: evidence type, facts, text. No ID, source, score, or status."""
    lines = [f"Evidence type: {ev.get('evidenceType')}"]
    facts = ev.get("facts") or {}
    if facts:
        lines.append("Facts:")
        lines += [f"- {k}: {facts[k]}" for k in sorted(facts, key=natural)]
    if ev.get("text"):
        lines += ["Text:", "", "> " + ev["text"].replace("\n", "\n> ")]
    return lines


def render_answer(ans):
    r = ans.get("response") or {}
    lines = [f"Summary: {r.get('summary', '')}"]
    claims = r.get("claims") or []
    if claims:
        lines.append("Claims:")
        lines += [f"{i}. {c.get('text', '')} ({c.get('verificationStatus')})" for i, c in enumerate(claims, 1)]
    if r.get("uncertainty"):
        lines.append(f"Uncertainty: {r['uncertainty']}")
    ab = ans.get("abstention")
    if r.get("abstained"):
        lines.append(f"Abstained: {ab.get('reason')}: {ab.get('detail')}" if ab else "Abstained.")
    return lines


def read_questions(path):
    with open(path, newline="", encoding="utf-8") as f:
        return {r["question_id"]: r["question_text"] for r in csv.DictReader(f)}


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def build(raw, questions, out, keys_out, seed):
    for d, name in ((out, "--out"), (keys_out, "--keys-out")):
        if inside(d, os.path.join(REPO, "labels")):
            raise Fail(f"{name} must not be under labels/: {d}", 2)
        if inside(d, raw):
            raise Fail(f"{name} must not be under the raw results: {d}", 2)
    if inside(keys_out, out) or inside(out, keys_out):
        raise Fail("--keys-out must be separate from --out (the labeller must not see the keys)", 2)
    existing = [p for p in [os.path.join(out, f) for f in OUT_FILES] + [os.path.join(keys_out, f) for f in KEY_FILES] if os.path.exists(p)]
    if existing:
        raise Fail(f"refusing to overwrite (a filled sheet would be lost): {existing}", 2)
    data = {m: load_mode(raw, m) for m in MODES}
    texts = read_questions(questions)
    qids = sorted({q for m in MODES for q in data[m]}, key=natural)
    if not qids:
        raise Fail(f"no benchmark results under {raw}/<mode>/")
    rel_md = ["# Experiment E relevance sheet", "",
              "For each question, judge each item on its own: is it relevant evidence for answering the question?",
              "Write 1 (relevant) or 0 (not relevant) in the `relevant` column of relevance-template.csv for its item key,",
              "then save the file as labels/relevance-e-v1.csv. Items are in a fixed random order.", ""]
    ans_md = ["# Experiment E answers sheet", "",
              "For each question, rate each answer from 1 (worst) to 5 (best) for usefulness and for clarity in",
              "ratings-template.csv, by its answer key. Answers are in a fixed random order.", ""]
    rel_rows, rat_rows, item_keys, answer_keys, gaps = [], [], [], [], []
    for q in qids:
        if q not in texts:
            raise Fail(f"question {q} is not in {questions}")
        pool, where = {}, {}
        for m in MODES:
            rec = data[m].get(q)
            if rec is None:
                gaps.append(f"{q}: no {m} result")
                continue
            for r in rec["result"].get("retrieved", []):
                e = r["evidenceId"]
                where.setdefault(e, []).append((m, r["rank"]))
                if e not in pool:
                    pool[e] = rec["items"].get(e)
        order = sorted(pool)  # by evidence ID, not by any rank, before the shuffle
        random.Random(f"{seed}|{q}|items").shuffle(order)
        rel_md += [f"## {q}", "", f"Question: {texts[q]}", ""]
        if not order:
            rel_md += ["(no item for this question)", ""]
        for i, e in enumerate(order, 1):
            key = f"{q}-i{i:02d}"
            rel_md.append(f"### Item {key}")
            rel_md.append("")
            if pool[e] is None:
                gaps.append(f"{q}: content of {e} not found in any EvidenceBundle record")
                rel_md.append("(content not found in the raw records)")
            else:
                rel_md += render_item(pool[e])
            rel_md.append("")
            rel_rows.append([q, key, ""])
            item_keys += [[q, key, e, m, rank] for m, rank in where[e]]
        present = [m for m in MODES if q in data[m]]
        random.Random(f"{seed}|{q}|answers").shuffle(present)
        ans_md += [f"## {q}", "", f"Question: {texts[q]}", ""]
        for i, m in enumerate(present):
            key = f"{q}-{'ABC'[i]}"
            ans_md += [f"### Answer {key}", ""] + render_answer(data[m][q]["answer"]) + [""]
            rat_rows.append([q, key, "", ""])
            answer_keys.append([q, key, m, data[m][q]["result"]["queryId"]])
    os.makedirs(out, exist_ok=True)
    os.makedirs(keys_out, exist_ok=True)
    with open(os.path.join(out, "relevance-sheet.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(rel_md))
    with open(os.path.join(out, "answers-sheet.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(ans_md))
    write_csv(os.path.join(out, "relevance-template.csv"), ["question_id", "item_key", "relevant"], rel_rows)
    write_csv(os.path.join(out, "ratings-template.csv"), ["question_id", "answer_key", "usefulness", "clarity"], rat_rows)
    write_csv(os.path.join(keys_out, "item-keys.csv"), ["question_id", "item_key", "evidence_id", "mode", "rank"], item_keys)
    write_csv(os.path.join(keys_out, "answer-keys.csv"), ["question_id", "answer_key", "mode", "query_id"], answer_keys)
    print(f"questions {len(qids)}, pooled items {len(rel_rows)}, answers {len(rat_rows)}; sheets in {out}; keys in {keys_out}")
    for g in gaps:
        print(f"GAP {g}")
    return gaps


# ---- Self-test fixture: fake outputs in the BenchmarkCli layout (no label, no rating) ----

SNAP = "snapshot=2026-10-03"


def ev(eid, etype, facts=None, text=None, score=None):
    return {"schemaVersion": "evidence-v1", "evidenceId": eid, "evidenceType": etype, "subjectIds": [],
            "sourceUri": f"fixture:{eid}", "chunkId": None, "score": score, "text": text, "facts": facts or {}}


FIXTURE_ITEMS = {
    "ev-g1": ev("ev-g1", "METRIC_RECORD", {"displayName": "Ada Fixture", "distinctCoauthorCount": "12", "window": "all-years"}),
    "ev-g2": ev("ev-g2", "IDENTITY_RESOLUTION", {"decision": "RESOLVED_DOMINANT", "displayName": "Ada Fixture"}),
    "ev-g3": ev("ev-g3", "COAUTHOR_EDGE", {"displayNameA": "Ada Fixture", "displayNameB": "Bo Example", "jointPublicationCount": "3"}),
    "ev-g4": ev("ev-g4", "WORK_RECORD", {"title": "Fixture work four", "year": "2004"}),
    "ev-g5": ev("ev-g5", "WORK_RECORD", {"title": "Fixture work five", "year": "2005"}),
    "ev-g6": ev("ev-g6", "RANKING_RECORD", {"metric": "distinctCoauthorCount", "rank.1.displayName": "Ada Fixture", "rank.1.value": "12"}),
    "ev-v1": ev("ev-v1", "PROFILE", {"index": "researcher-profile-v1", "window": "all-years"}, "Researcher profile: Ada Fixture.", 0.4321987),
    "ev-v2": ev("ev-v2", "TEXT_CHUNK", {"index": "topic-institution-summary-v1"}, "Topic: fixture topic.", 0.1234567),
}
# question -> mode -> (retrieved evidence IDs in rank order, abstention reason or None, verified, partial)
FIXTURE_LISTS = {
    "B01": {"fused": (["ev-g1", "ev-g2", "ev-g4", "ev-g5", "ev-g6", "ev-v1"], None, 3, 1),
            "graph-only": (["ev-g1", "ev-g2"], None, 2, 0),
            "vector-only": (["ev-v2", "ev-v1"], "NO_VERIFIED_CLAIM", 0, 1)},
    "B02": {"fused": (["ev-g3"], None, 1, 0),
            "graph-only": (["ev-g3"], None, 1, 0),
            "vector-only": ([], "NO_VERIFIED_CLAIM", 0, 0)},
}
FIXTURE_LATENCY = {"fused": [1000, 3000], "graph-only": [500, 700], "vector-only": [2000, 4000]}


def write_fixture(root):
    for m in MODES:
        mdir = os.path.join(root, m)
        rr = f"run-rt-{m}"
        bdir = os.path.join(mdir, "s3/reports/rag-benchmark", SNAP, f"run=run-bench-{m}", "questions")
        adir = os.path.join(mdir, "s3/logs/rag-runtime", SNAP, f"run={rr}")
        rdir = os.path.join(mdir, "s3/reports/rag-runtime", SNAP, f"run={rr}")
        ddir = os.path.join(mdir, "dynamodb", SNAP, f"run={rr}")
        for d in (bdir, adir, rdir, ddir):
            os.makedirs(d, exist_ok=True)
        lines = []
        for n, (q, modes) in enumerate(sorted(FIXTURE_LISTS.items())):
            ids, reason, ver, part = modes[m]
            qy = f"qry-{m}-{q}"
            retrieved = [{"rank": i, "source": m.split("-")[0], "origin": "vector" if e.startswith("ev-v") else "graph",
                          "evidenceId": e, "elementId": f"fixture:{e}"} for i, e in enumerate(ids, 1)]
            res = {"schemaVersion": "rag-benchmark-result-v3", "questionId": q, "retrievalMode": m.upper().replace("-", "_"),
                   "runtimeRunId": rr, "queryId": qy, "abstained": reason is not None, "abstentionReason": reason,
                   "verification": {"verified": ver, "partiallySupported": part, "unsupported": 0, "conflicting": 0},
                   "claims": ver + part, "semantic": {"embeddingCalls": 0 if m == "graph-only" else 1},
                   "llm": {"attempts": 2, "requests": 2, "inputTokens": 1000 + n, "outputTokens": 100 + n},
                   "latencyMs": FIXTURE_LATENCY[m][n], "retrieved": retrieved}
            with open(os.path.join(bdir, f"{q}.json"), "w") as f:
                json.dump(res, f, indent=2)
            with open(os.path.join(adir, f"{qy}.json"), "w") as f:
                json.dump({"schemaVersion": "rag-audit-v3", "queryId": qy,
                           "removedClaims": [{"text": "removed"}] if (m, q) == ("fused", "B01") else []}, f)
            claims = [{"text": f"Fixture claim {i} for {q}.", "verificationStatus": "VERIFIED"} for i in range(ver)]
            claims += [{"text": f"Fixture context claim for {q}.", "verificationStatus": "PARTIALLY_SUPPORTED"}] * part
            ans = {"schemaVersion": "rag-answer-v1",
                   "response": {"queryId": qy, "summary": f"Fixture summary for {q}.", "claims": claims,
                                "abstained": reason is not None, "uncertainty": None},
                   "abstention": {"reason": reason, "detail": "No claim could be verified."} if reason else None}
            with open(os.path.join(rdir, f"{qy}.answer.json"), "w") as f:
                json.dump(ans, f)
            for e in ids:
                item = {"itemKey": {"S": f"EV#{e}"}, "queryId": {"S": qy}}
                if e == "ev-g6":  # one item stored by reference, as above the 32 KB item budget
                    ref = f"logs/rag-runtime/{SNAP}/run={rr}/{qy}/evidence/{e}.json"
                    os.makedirs(os.path.dirname(os.path.join(mdir, "s3", ref)), exist_ok=True)
                    with open(os.path.join(mdir, "s3", ref), "w") as f:
                        json.dump(FIXTURE_ITEMS[e], f)
                    item["payloadRef"] = {"S": ref}
                else:
                    item["payload"] = {"S": json.dumps(FIXTURE_ITEMS[e])}
                lines.append(json.dumps({"Item": item}))
            lines.append(json.dumps({"Item": {"itemKey": {"S": "BUNDLE"}, "queryId": {"S": qy}}}))
        with open(os.path.join(ddir, "EvidenceBundle.jsonl"), "w") as f:
            f.write("\n".join(lines) + "\n")
    with open(os.path.join(root, "questions.csv"), "w") as f:
        f.write("question_id,category,question_text\nB01,coauthor_breadth,How many coauthors has Ada Fixture had?\n"
                "B02,direct_collaboration,\"Have Ada Fixture and Bo Example coauthored, ever?\"\n")


def self_test():
    fails = []

    def check(name, cond):
        print(f"    {'PASS' if cond else 'FAIL'} {name}")
        if not cond:
            fails.append(name)

    with tempfile.TemporaryDirectory() as t:
        raw = os.path.join(t, "raw")
        write_fixture(raw)
        out, keys = os.path.join(t, "sheets"), os.path.join(t, "keys")
        gaps = build(raw, os.path.join(raw, "questions.csv"), out, keys, "exp-e-v1")
        check("no gap in the complete fixture", gaps == [])
        rel = open(os.path.join(out, "relevance-sheet.md")).read()
        ans = open(os.path.join(out, "answers-sheet.md")).read()
        for name in ("relevance-template.csv", "ratings-template.csv"):
            rows = list(csv.reader(open(os.path.join(out, name))))
            check(f"{name}: header and no filled label or rating",
                  rows[0][:2] in (["question_id", "item_key"], ["question_id", "answer_key"])
                  and len(rows) > 1 and all(c == "" for r in rows[1:] for c in r[2:]))
        forbidden = MODES + ["FUSED", "GRAPH_ONLY", "VECTOR_ONLY", "graph only", "vector only", "score", "origin",
                             "0.4321987", "0.1234567", "verificationStatus", "fixture:ev-", "ev-g", "ev-v"]
        for name, text in (("relevance sheet", rel), ("answers sheet", ans)):
            hits = [w for w in forbidden if w.lower() in text.lower()]
            check(f"{name} shows no mode, score, evidence ID or source: {hits}", hits == [])
            check(f"{name} shows no rank label", not re.search(r"(?i)\brank\s*[:=#]|\bretriev", text))
        check("relevance sheet shows no verification status", "VERIFIED" not in rel and "PARTIALLY" not in rel)
        check("relevance sheet shows content (facts and text)", "distinctCoauthorCount: 12" in rel and "Researcher profile: Ada Fixture." in rel)
        check("item stored by payloadRef is rendered", "rank.1.displayName: Ada Fixture" in rel)
        ik = list(csv.DictReader(open(os.path.join(keys, "item-keys.csv"))))
        tmpl = list(csv.DictReader(open(os.path.join(out, "relevance-template.csv"))))
        check("pooled and deduplicated: B01 has 10 list positions in 3 lists, 7 unique items",
              sum(r["question_id"] == "B01" for r in tmpl) == 7 and sum(r["question_id"] == "B01" for r in ik) == 10)
        check("keys map every list position", {(r["mode"], r["question_id"], r["evidence_id"], int(r["rank"])) for r in ik}
              == {(m, q, e, i) for q, ms in FIXTURE_LISTS.items() for m, v in ms.items() for i, e in enumerate(v[0], 1)})
        order = FIXTURE_LISTS["B01"]["fused"][0]
        b01 = [next(r["evidence_id"] for r in ik if r["item_key"] == k["item_key"]) for k in tmpl if k["question_id"] == "B01"]
        check("sheet order is not the fused rank order", [e for e in b01 if e in order] != order)
        ak = list(csv.DictReader(open(os.path.join(keys, "answer-keys.csv"))))
        check("three answers per question under blind keys",
              sorted((r["question_id"], r["mode"]) for r in ak) == sorted((q, m) for q in FIXTURE_LISTS for m in MODES)
              and {r["answer_key"][-1] for r in ak} == {"A", "B", "C"})
        out2, keys2 = os.path.join(t, "sheets2"), os.path.join(t, "keys2")
        build(raw, os.path.join(raw, "questions.csv"), out2, keys2, "exp-e-v1")
        same = all(open(os.path.join(out, f)).read() == open(os.path.join(out2, f)).read() for f in OUT_FILES)
        check("fixed seed: a second build is byte-identical", same)
        for name, args in (("refuses --out under labels/", (os.path.join(REPO, "labels", "x"), os.path.join(t, "k3"))),
                           ("refuses keys inside --out", (os.path.join(t, "s4"), os.path.join(t, "s4", "keys"))),
                           ("refuses to overwrite filled sheets", (out, os.path.join(t, "k5")))):
            try:
                build(raw, os.path.join(raw, "questions.csv"), args[0], args[1], "exp-e-v1")
                check(name, False)
            except Fail as e:
                check(name, e.code == 2)
    if fails:
        print(f"==> FAIL self-test: {len(fails)} failure(s)")
        return 1
    print("==> OK self-test")
    return 0


def main(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--raw")
    p.add_argument("--questions", default=os.path.join(REPO, "experiments/benchmark/benchmark-questions-v1.csv"))
    p.add_argument("--out")
    p.add_argument("--keys-out")
    p.add_argument("--seed", default="exp-e-v1")
    p.add_argument("--self-test", action="store_true")
    p.add_argument("--write-fixture")
    a = p.parse_args(argv)
    try:
        if a.self_test:
            return self_test()
        if a.write_fixture:
            write_fixture(a.write_fixture)
            return 0
        if not (a.raw and a.out and a.keys_out):
            p.error("--raw, --out and --keys-out are required")
        build(a.raw, a.questions, a.out, a.keys_out, a.seed)
        return 0
    except Fail as e:
        print(f"==> FAIL {e}", file=sys.stderr)
        return e.code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
