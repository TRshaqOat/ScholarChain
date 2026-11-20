#!/usr/bin/env python3
"""Summary of experiment E per retrieval mode (Phase 15 work order 4 A3). Read-only on the results.

From the raw outputs (<raw>/fused, <raw>/graph-only, <raw>/vector-only, as written by
experiments/harness/exp-e-retrieval-modes.sh) it writes into --out:
  exp-e-summary.csv / .md   per mode: questions, answered, abstained (total and by reason), VERIFIED,
                            PARTIALLY_SUPPORTED and removed claims, question latency p50, p95 and
                            max (nearest rank, as Benchmark.latency), LLM attempts, Bedrock requests,
                            input and output tokens, embedding calls, retrieved items
  exp-e-quality.csv         only with the human's files: recall at 5 and 10 and MRR per mode
                            (--relevance and --keys), mean usefulness and clarity per mode (--ratings
                            and --keys); otherwise the Markdown says "not labelled yet"
Relevance is never inferred: an item counts as relevant only when the human's file says 1. A
question is scored only when every pooled item of it is labelled 1 or 0; a question with no
relevant item has no recall or MRR, and a question with no pooled item at all (every mode
abstained before the bundle) is counted as questions_without_items, not as unlabelled. Quality
metrics (relevance and ratings) use only the question IDs present in all three modes; the excluded
IDs are listed per mode, and the Markdown prints a "Question sets differ" line when the modes' question
sets differ (Phase 15 ruling 12(d)). Recall at K uses the first min(K, list length) items of a mode's
list, and the list is the post-budget bundle, so recall at K is capped at the bundle size (items the
budget left out are not listed; Phase 15 ruling 6, m5).

Usage:
  python3 -I experiments/analysis/summarize-exp-e.py --raw <stamp>/raw --out <dir>
      [--keys <dir with item-keys.csv and answer-keys.csv>] [--relevance <csv>] [--ratings <csv>]
  python3 -I experiments/analysis/summarize-exp-e.py --self-test
Exit: 0 written; 1 inconsistent input (missing record, bad label value, unknown key); 2 usage.
"""
import argparse
import csv
import glob
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

MODES = ["fused", "graph-only", "vector-only"]
HERE = os.path.dirname(os.path.abspath(__file__))
CAP_NOTE = ("Recall at K uses the first min(K, list length) items of each mode's ranked list. The list is the "
            "post-budget evidence bundle (items the token and item budget left out are not listed), so recall at K "
            "is capped at the bundle size; the mean list length per mode is in exp-e-summary.csv (Phase 15 ruling 6, m5).")


class Fail(Exception):
    pass


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise Fail(f"cannot read {path}: {e}")


def load_mode(raw, mode):
    """[(result, audit)] of one mode, in question-ID order."""
    mdir = os.path.join(raw, mode)
    runs = sorted(glob.glob(os.path.join(mdir, "s3/reports/rag-benchmark/snapshot=*/run=*")))
    if len(runs) > 1:
        raise Fail(f"{mdir}: more than one benchmark run: {runs}")
    out = []
    for run in runs:
        snap = os.path.basename(os.path.dirname(run))
        for rpath in sorted(glob.glob(os.path.join(run, "questions", "*.json"))):
            res = read_json(rpath)
            audit = read_json(os.path.join(mdir, "s3/logs/rag-runtime", snap, f"run={res['runtimeRunId']}", f"{res['queryId']}.json"))
            out.append((res, audit))
    return out


def pct(xs, p):
    s = sorted(xs)
    return s[max(0, math.ceil(p * len(s)) - 1)] if s else ""


def mode_row(mode, rows):
    rs = [r for r, _ in rows]
    reasons = {}
    for r in rs:
        if r["abstained"]:
            reasons[r.get("abstentionReason") or "UNKNOWN"] = reasons.get(r.get("abstentionReason") or "UNKNOWN", 0) + 1
    lat = [r["latencyMs"] for r in rs]
    n_items = [len(r.get("retrieved", [])) for r in rs]
    return {
        "mode": mode, "questions": len(rs), "answered": sum(not r["abstained"] for r in rs),
        "abstained": sum(r["abstained"] for r in rs),
        "abstained_by_reason": "; ".join(f"{k} {v}" for k, v in sorted(reasons.items())),
        "verified": sum(r["verification"]["verified"] for r in rs),
        "partially_supported": sum(r["verification"]["partiallySupported"] for r in rs),
        "removed_claims": sum(len(a.get("removedClaims", [])) for _, a in rows),
        "latency_p50_ms": pct(lat, 0.5), "latency_p95_ms": pct(lat, 0.95), "latency_max_ms": max(lat) if lat else "",
        "llm_attempts": sum(r["llm"]["attempts"] for r in rs), "bedrock_requests": sum(r["llm"]["requests"] for r in rs),
        "llm_input_tokens": sum(r["llm"]["inputTokens"] for r in rs), "llm_output_tokens": sum(r["llm"]["outputTokens"] for r in rs),
        "embedding_calls": sum(r["semantic"]["embeddingCalls"] for r in rs),
        "retrieved_items": sum(n_items), "retrieved_items_mean": round(sum(n_items) / len(rs), 2) if rs else "",
        "questions_ids": " ".join(r["questionId"] for r in rs),
    }


def read_rows(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def question_sets(data):
    """(IDs present in every mode, {mode: IDs of that mode not present in every mode})."""
    ids = {m: {r["questionId"] for r, _ in data[m]} for m in MODES}
    common = set.intersection(*ids.values())
    return common, {m: sorted(ids[m] - common) for m in MODES}


def relevance_quality(data, keys_dir, rel_path, common):
    keys = read_rows(os.path.join(keys_dir, "item-keys.csv"))
    item = {k["item_key"]: (k["question_id"], k["evidence_id"]) for k in keys}
    pool = {}
    for key, (q, _) in item.items():
        pool.setdefault(q, set()).add(key)
    label = {}
    for r in read_rows(rel_path):
        v = (r.get("relevant") or "").strip()
        if r["item_key"] not in item or item[r["item_key"]][0] != r["question_id"]:
            raise Fail(f"{rel_path}: unknown item key {r['question_id']} {r['item_key']}")
        if v not in ("", "0", "1"):
            raise Fail(f"{rel_path}: relevant must be 1, 0 or empty, got {v!r} for {r['item_key']}")
        if v:
            label[r["item_key"]] = v == "1"
    complete = {q for q, ks in pool.items() if all(k in label for k in ks)}
    relevant = {q: {item[k][1] for k in pool[q] if label[k]} for q in complete}
    out = {}
    for m in MODES:
        rec, mrr, skipped = {5: [], 10: []}, [], {"no_items": 0, "incomplete": 0, "no_relevant": 0}
        for res, _ in data[m]:
            q = res["questionId"]
            if q not in common:
                continue
            if q not in pool:
                skipped["no_items"] += 1
                continue
            if q not in complete:
                skipped["incomplete"] += 1
                continue
            rel = relevant[q]
            if not rel:
                skipped["no_relevant"] += 1
                continue
            lst = [x["evidenceId"] for x in sorted(res.get("retrieved", []), key=lambda x: x["rank"])]
            for k in rec:
                rec[k].append(len(rel & set(lst[:k])) / len(rel))
            mrr.append(next((1 / i for i, e in enumerate(lst, 1) if e in rel), 0.0))
        mean = lambda xs: round(sum(xs) / len(xs), 4) if xs else ""
        out[m] = {"recall_at_5": mean(rec[5]), "recall_at_10": mean(rec[10]), "mrr": mean(mrr),
                  "questions_scored": len(mrr), "questions_without_items": skipped["no_items"],
                  "questions_incompletely_labelled": skipped["incomplete"],
                  "questions_without_relevant_item": skipped["no_relevant"]}
    return out


def rating_quality(keys_dir, rat_path, common):
    ans = {r["answer_key"]: (r["question_id"], r["mode"]) for r in read_rows(os.path.join(keys_dir, "answer-keys.csv"))}
    vals = {m: {"usefulness": [], "clarity": []} for m in MODES}
    for r in read_rows(rat_path):
        if r["answer_key"] not in ans or ans[r["answer_key"]][0] != r["question_id"]:
            raise Fail(f"{rat_path}: unknown answer key {r['question_id']} {r['answer_key']}")
        for col in ("usefulness", "clarity"):
            v = (r.get(col) or "").strip()
            if v and v not in ("1", "2", "3", "4", "5"):
                raise Fail(f"{rat_path}: {col} must be 1 to 5 or empty, got {v!r} for {r['answer_key']}")
            if v and r["question_id"] in common:
                vals[ans[r["answer_key"]][1]][col].append(int(v))
    return {m: {"mean_usefulness": round(sum(v["usefulness"]) / len(v["usefulness"]), 3) if v["usefulness"] else "",
                "answers_rated_usefulness": len(v["usefulness"]),
                "mean_clarity": round(sum(v["clarity"]) / len(v["clarity"]), 3) if v["clarity"] else "",
                "answers_rated_clarity": len(v["clarity"])} for m, v in vals.items()}


def md_table(rows, cols):
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    lines += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for r in rows]
    return lines


def summarize(raw, out, keys=None, relevance=None, ratings=None):
    if (relevance or ratings) and not keys:
        raise Fail("--relevance and --ratings need --keys")
    ro = os.path.realpath(raw)
    if os.path.realpath(out) == ro or os.path.realpath(out).startswith(ro + os.sep):
        raise Fail("--out must not be under the raw results (they are read-only)")
    data = {m: load_mode(raw, m) for m in MODES}
    if not any(data.values()):
        raise Fail(f"no benchmark results under {raw}/<mode>/")
    rows = [mode_row(m, data[m]) for m in MODES]
    os.makedirs(out, exist_ok=True)
    cols = list(rows[0])
    with open(os.path.join(out, "exp-e-summary.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, cols, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    md = ["# Experiment E summary per retrieval mode", "", f"Source: {raw}", ""]
    hj = os.path.join(raw, "harness.json")
    if os.path.isfile(hj):
        h = read_json(hj)
        md += [f"Harness: started {h.get('startedAt')}, ended {h.get('endedAt')}, exit {h.get('exitCode')}, N {h.get('n')}; "
               f"check {json.dumps(h.get('check'))}; invocations "
               + ", ".join(f"{i['step']} {i['mode']} exit {i['exitCode']}" for i in h.get("invocations", [])), ""]
    else:
        md += ["Harness: harness.json not found (gap).", ""]
    common, excluded = question_sets(data)
    if any(excluded.values()):
        md += [f"Question sets differ across modes: quality metrics use only the {len(common)} question(s) present in all "
               f"three modes ({' '.join(sorted(common)) or 'none'}); excluded: "
               + "; ".join(f"{m} {' '.join(excluded[m]) or 'none'}" for m in MODES) + ".", ""]
    else:
        md += [f"Question sets: the same {len(common)} question(s) in every mode.", ""]
    md += md_table(rows, [c for c in cols if c != "questions_ids"]) + [""]
    q_rows = [{"mode": m, "questions_excluded": " ".join(excluded[m])} for m in MODES]
    if relevance:
        rq = relevance_quality(data, keys, relevance, common)
        for r in q_rows:
            r.update(rq[r["mode"]])
        md += ["## Retrieval quality (human relevance labels)", "", f"Labels: {relevance}", "", CAP_NOTE, ""]
        md += md_table(q_rows, ["mode", "recall_at_5", "recall_at_10", "mrr", "questions_scored", "questions_without_items",
                                "questions_incompletely_labelled", "questions_without_relevant_item"]) + [""]
    else:
        md += ["## Retrieval quality", "", "Relevance: not labelled yet.", "", CAP_NOTE, ""]
    if ratings:
        aq = rating_quality(keys, ratings, common)
        for r in q_rows:
            r.update(aq[r["mode"]])
        md += ["## Answer ratings (human)", "", f"Ratings: {ratings}", ""]
        md += md_table(q_rows, ["mode", "mean_usefulness", "answers_rated_usefulness", "mean_clarity", "answers_rated_clarity"]) + [""]
    else:
        md += ["## Answer ratings", "", "Usefulness and clarity: not labelled yet.", ""]
    if relevance or ratings:
        with open(os.path.join(out, "exp-e-quality.csv"), "w", newline="") as f:
            w = csv.DictWriter(f, list(q_rows[0]), lineterminator="\n")
            w.writeheader()
            w.writerows(q_rows)
    with open(os.path.join(out, "exp-e-summary.md"), "w") as f:
        f.write("\n".join(md))
    print(f"wrote {out}/exp-e-summary.csv and .md" + (" and exp-e-quality.csv" if relevance or ratings else ""))
    return rows, q_rows


def self_test():
    fails = []

    def check(name, cond):
        print(f"    {'PASS' if cond else 'FAIL'} {name}")
        if not cond:
            fails.append(name)

    sheets = [sys.executable, "-I", os.path.join(HERE, "e-label-sheets.py")]
    with tempfile.TemporaryDirectory() as t:
        raw = os.path.join(t, "raw")
        subprocess.run(sheets + ["--write-fixture", raw], check=True)
        subprocess.run(sheets + ["--raw", raw, "--questions", os.path.join(raw, "questions.csv"),
                                 "--out", os.path.join(t, "sheets"), "--keys-out", os.path.join(t, "keys")],
                       check=True, stdout=subprocess.DEVNULL)
        rows, _ = summarize(raw, os.path.join(t, "s1"))
        by = {r["mode"]: r for r in rows}
        f = by["fused"]
        check("fused counts", (f["questions"], f["answered"], f["abstained"], f["verified"], f["partially_supported"],
                               f["removed_claims"], f["llm_attempts"], f["llm_input_tokens"], f["embedding_calls"],
                               f["retrieved_items"]) == (2, 2, 0, 4, 1, 1, 4, 2001, 2, 7))
        check("vector-only abstentions by reason", by["vector-only"]["abstained_by_reason"] == "NO_VERIFIED_CLAIM 2")
        check("latency nearest rank p50, p95, max", (f["latency_p50_ms"], f["latency_p95_ms"], f["latency_max_ms"]) == (1000, 3000, 3000))
        check("graph-only makes no embedding call", by["graph-only"]["embedding_calls"] == 0)
        md = open(os.path.join(t, "s1", "exp-e-summary.md")).read()
        check("unlabelled: says not labelled yet and writes no quality file",
              md.count("not labelled yet") == 2 and not os.path.exists(os.path.join(t, "s1", "exp-e-quality.csv")))
        check("cap stated", "capped at the bundle size" in md)
        # Fixture labels and ratings (test data in a temporary directory, never a real label).
        keys = list(csv.DictReader(open(os.path.join(t, "keys", "item-keys.csv"))))
        ev_key = {(k["question_id"], k["evidence_id"]): k["item_key"] for k in keys}
        rel = {("B01", "ev-g1"): "1", ("B01", "ev-v1"): "1", ("B02", "ev-g3"): "1"}
        with open(os.path.join(t, "rel.csv"), "w") as fh:
            fh.write("question_id,item_key,relevant\n")
            for (q, e), k in sorted(ev_key.items()):
                fh.write(f"{q},{k},{rel.get((q, e), '0')}\n")
        ak = {(a["question_id"], a["mode"]): a["answer_key"] for a in csv.DictReader(open(os.path.join(t, "keys", "answer-keys.csv")))}
        rat = {("B01", "fused"): "4,5", ("B01", "graph-only"): "3,4", ("B01", "vector-only"): "1,2",
               ("B02", "fused"): "5,5", ("B02", "graph-only"): "4,4", ("B02", "vector-only"): ","}
        with open(os.path.join(t, "rat.csv"), "w") as fh:
            fh.write("question_id,answer_key,usefulness,clarity\n")
            fh.writelines(f"{q},{ak[(q, m)]},{v}\n" for (q, m), v in sorted(rat.items()))
        _, qr = summarize(raw, os.path.join(t, "s2"), os.path.join(t, "keys"), os.path.join(t, "rel.csv"), os.path.join(t, "rat.csv"))
        q = {r["mode"]: r for r in qr}
        # fused B01 list g1,g2,g4,g5,g6,v1 (relevant g1, v1): R@5 0.5, R@10 1, RR 1; B02 g3: 1, 1, 1
        check("fused recall@5, recall@10, MRR", (q["fused"]["recall_at_5"], q["fused"]["recall_at_10"], q["fused"]["mrr"]) == (0.75, 1.0, 1.0))
        check("graph-only recall@5, MRR", (q["graph-only"]["recall_at_5"], q["graph-only"]["mrr"]) == (0.75, 1.0))
        # vector-only B01 list v2,v1: R 0.5, RR 0.5; B02 empty list: 0, 0
        check("vector-only recall@5, MRR (empty list counts 0)", (q["vector-only"]["recall_at_5"], q["vector-only"]["mrr"]) == (0.25, 0.25))
        check("ratings means; an empty rating is not counted",
              (q["fused"]["mean_usefulness"], q["graph-only"]["mean_clarity"], q["vector-only"]["mean_usefulness"],
               q["vector-only"]["answers_rated_clarity"]) == (4.5, 4.0, 1.0, 1))
        # An unlabelled item makes its question unscored (no relevance is inferred).
        lines = open(os.path.join(t, "rel.csv")).read().splitlines()
        with open(os.path.join(t, "rel2.csv"), "w") as fh:
            fh.write("\n".join(lines[:1] + [lines[1].rsplit(",", 1)[0] + ","] + lines[2:]) + "\n")
        _, qr2 = summarize(raw, os.path.join(t, "s3"), os.path.join(t, "keys"), os.path.join(t, "rel2.csv"))
        check("an unlabelled item leaves its question unscored", all(r["questions_incompletely_labelled"] == 1 and r["questions_scored"] == 1 for r in qr2))
        with open(os.path.join(t, "rel3.csv"), "w") as fh:
            fh.write("\n".join(lines[:1] + [lines[1].rsplit(",", 1)[0] + ",yes"] + lines[2:]) + "\n")
        try:
            summarize(raw, os.path.join(t, "s4"), os.path.join(t, "keys"), os.path.join(t, "rel3.csv"))
            check("a label other than 1, 0 or empty is refused", False)
        except Fail:
            check("a label other than 1, 0 or empty is refused", True)
        try:
            summarize(raw, os.path.join(raw, "x"))
            check("refuses --out under the raw results", False)
        except Fail:
            check("refuses --out under the raw results", True)
        check("same question sets: no 'differ' line, nothing excluded",
              "Question sets: the same 2 question(s) in every mode." in md and all(r["questions_excluded"] == "" for r in qr))
        # Ruling 12(d): graph-only lacks B02, so every quality metric uses B01 only.
        raw3 = os.path.join(t, "raw3")
        shutil.copytree(raw, raw3)
        for p in glob.glob(os.path.join(raw3, "graph-only", "s3/reports/rag-benchmark/*/*/questions/B02.json")):
            os.remove(p)
        _, qr3 = summarize(raw3, os.path.join(t, "s5"), os.path.join(t, "keys"), os.path.join(t, "rel.csv"), os.path.join(t, "rat.csv"))
        q3 = {r["mode"]: r for r in qr3}
        md3 = open(os.path.join(t, "s5", "exp-e-summary.md")).read()
        check("question sets differ: the Markdown says so and names the excluded IDs",
              "Question sets differ across modes: quality metrics use only the 1 question(s) present in all three modes (B01); "
              "excluded: fused B02; graph-only none; vector-only B02." in md3)
        check("question sets differ: excluded IDs per mode in the quality rows",
              [q3[m]["questions_excluded"] for m in MODES] == ["B02", "", "B02"])
        # fused B01 only: R@5 0.5, R@10 1, RR 1; vector-only B01 list v2,v1: R 0.5, RR 0.5
        check("question sets differ: recall and MRR over the shared question only",
              (q3["fused"]["recall_at_5"], q3["fused"]["recall_at_10"], q3["fused"]["mrr"], q3["vector-only"]["mrr"],
               [q3[m]["questions_scored"] for m in MODES]) == (0.5, 1.0, 1.0, 0.5, [1, 1, 1]))
        check("question sets differ: ratings over the shared question only",
              (q3["fused"]["mean_usefulness"], q3["fused"]["answers_rated_usefulness"], q3["graph-only"]["mean_clarity"])
              == (4.0, 1, 4.0))
        # Ruling 12(d): B02 has no retrieved item in any mode, so it has nothing to label.
        raw4 = os.path.join(t, "raw4")
        shutil.copytree(raw, raw4)
        for p in glob.glob(os.path.join(raw4, "*", "s3/reports/rag-benchmark/*/*/questions/B02.json")):
            res = read_json(p)
            res["retrieved"] = []
            with open(p, "w") as fh:
                json.dump(res, fh)
        subprocess.run(sheets + ["--raw", raw4, "--questions", os.path.join(raw, "questions.csv"),
                                 "--out", os.path.join(t, "sheets4"), "--keys-out", os.path.join(t, "keys4")],
                       check=True, stdout=subprocess.DEVNULL)
        keys4 = list(csv.DictReader(open(os.path.join(t, "keys4", "item-keys.csv"))))
        with open(os.path.join(t, "rel4.csv"), "w") as fh:
            fh.write("question_id,item_key,relevant\n")
            for k in sorted({(k["question_id"], k["item_key"], k["evidence_id"]) for k in keys4}):
                fh.write(f"{k[0]},{k[1]},{rel.get((k[0], k[2]), '0')}\n")
        _, qr4 = summarize(raw4, os.path.join(t, "s6"), os.path.join(t, "keys4"), os.path.join(t, "rel4.csv"))
        md4 = open(os.path.join(t, "s6", "exp-e-summary.md")).read()
        check("a question with no pooled item is questions_without_items, not incompletely labelled",
              all((r["questions_without_items"], r["questions_incompletely_labelled"], r["questions_scored"]) == (1, 0, 1) for r in qr4)
              and "questions_without_items" in md4)
    if fails:
        print(f"==> FAIL self-test: {len(fails)} failure(s)")
        return 1
    print("==> OK self-test")
    return 0


def main(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--raw")
    p.add_argument("--out")
    p.add_argument("--keys")
    p.add_argument("--relevance")
    p.add_argument("--ratings")
    p.add_argument("--self-test", action="store_true")
    a = p.parse_args(argv)
    try:
        if a.self_test:
            return self_test()
        if not (a.raw and a.out):
            p.error("--raw and --out are required")
        summarize(a.raw, a.out, a.keys, a.relevance, a.ratings)
        return 0
    except Fail as e:
        print(f"==> FAIL {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
