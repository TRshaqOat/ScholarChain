#!/usr/bin/env bash
# Validate the dataset manifest, config, scripts, schema notes, and committed samples.
# No network. Exits 0 when every check passes and 1 when any check fails.
#
# Checks: manifest required fields for each dataset; S3 raw prefixes use the configured-bucket
# placeholder; subset config sanity and ORCID caps; bash -n on every script; the ORCID selection
# self-test; Python syntax and the extractor self-test; schema notes exist; every sample file is under 200,000 bytes; JSONL samples parse;
# the DBLP sample is well-formed XML with the DTD's entities resolved; OpenAlex and Crossref sample
# DOIs are a non-empty subset of the DBLP sample DOIs; manifest sample sizes match the files;
# ORCID samples hold only ORCID's fictional test record or labeled synthetic records (human ruling,
# phase-03 repair-01, Decision 1); datasets/samples/SHA256SUMS verifies.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
set +e # collect every failure instead of stopping at the first

require_tools jq python3
FAILS=0
pass() { log PASS "$1"; }
fail() { log FAIL "$1"; FAILS=$((FAILS + 1)); }
check() { local name="$1"; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }

for f in "$SCRIPTS_DIR"/*.sh; do check "bash -n ${f#"$REPO_ROOT"/}" bash -n "$f"; done
for f in "$SCRIPTS_DIR"/*.py; do
  check "python syntax ${f#"$REPO_ROOT"/}" python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$f"
done
check "dblp_extract.py --self-test" python3 -I "$SCRIPTS_DIR/dblp_extract.py" --self-test

check "config is valid JSON with venues, years, and pinned checksums" jq -e '
  (.config_version | type == "string") and (.dblp.venues | length > 0)
  and all(.dblp.venues[]; (.key_prefix | test("^(conf|journals)/[A-Za-z0-9-]+/$")))
  and (.dblp.year_min <= .dblp.year_max)
  and (.dblp.xml_gz_md5 | test("^[0-9a-f]{32}$")) and (.dblp.dtd_sha256 | test("^[0-9a-f]{64}$"))
  and (.dblp.sample_prefix_bytes < .dblp.xml_gz_bytes)' "$CONFIG" >/dev/null
# 2,000-read retry margin under the documented quota: human ruling, phase-05 repair-01.
check "config orcid caps: sample_max_ids <= full_max_ids <= daily_request_cap < documented_anonymous_daily_reads, full_max_ids <= documented - 2000" jq -e '
  .orcid | (.full_max_ids | type == "number") and .sample_max_ids <= .full_max_ids
  and .full_max_ids <= .daily_request_cap and .daily_request_cap < .documented_anonymous_daily_reads
  and .full_max_ids <= .documented_anonymous_daily_reads - 2000' "$CONFIG" >/dev/null
check "fetch_orcid.sh --self-test (selection priority, class a work ranking, and counts)" bash "$SCRIPTS_DIR/fetch_orcid.sh" --self-test

python3 -I - "$REPO_ROOT" "$CONFIG" <<'PY'
import hashlib, json, os, re, sys
import xml.parsers.expat as expat

root = sys.argv[1]
fails = 0
def report(ok, msg):
    global fails
    print(("PASS " if ok else "FAIL ") + msg)
    fails += 0 if ok else 1

REQUIRED = ["name", "purpose", "required", "owning_agent", "official_source", "documentation_links", "license",
            "license_evidence", "access_rules", "snapshot_date", "retrieval_date", "retrieval_command", "format",
            "compression", "expected_size", "observed_size", "s3_raw_prefix", "integrity_policy", "schema_version",
            "schema_note", "subset_rule", "incremental_update_strategy", "linkage_fields", "privacy_notes",
            "known_limitations", "validation_commands", "samples"]
EXPECTED = ["dblp", "openalex", "crossref", "orcid"]

# shortcut: indentation-based parse of the manifest's fixed layout (no YAML library in the stdlib);
# upgrade to a real YAML parser if the manifest layout grows beyond two levels of keys.
lines = open(os.path.join(root, "datasets/dataset-manifest.yaml"), encoding="utf-8").read().splitlines()
fields, current, in_ds = {}, None, False
for ln in lines:
    if ln.startswith("datasets:"):
        in_ds = True; continue
    if not in_ds or not ln.strip() or ln.lstrip().startswith("#"):
        continue
    m2 = re.match(r"^  ([a-z0-9_]+):\s*(.*)$", ln)
    m4 = re.match(r"^    ([a-z0-9_]+):\s*(.*)$", ln)
    if m2:
        current = m2.group(1); fields[current] = {}
    elif m4 and current:
        fields[current][m4.group(1)] = m4.group(2)
report(sorted(fields) == sorted(EXPECTED), f"manifest datasets are exactly {EXPECTED} (found {sorted(fields)})")
for ds, f in fields.items():
    missing = [k for k in REQUIRED if k not in f]
    report(not missing, f"manifest {ds}: all {len(REQUIRED)} required fields present" + (f" (missing {missing})" if missing else ""))
    prefix = f.get("s3_raw_prefix", "").strip('"')
    report(prefix.startswith(f"s3://<raw-bucket>/{ds}/snapshot="), f"manifest {ds}: s3_raw_prefix uses the configured-bucket placeholder")
    report(f.get("owning_agent") == "dataset-engineer", f"manifest {ds}: owning_agent is dataset-engineer")
    note = f.get("schema_note", "")
    report(bool(note) and os.path.isfile(os.path.join(root, note)), f"manifest {ds}: schema note {note} exists")
text = "\n".join(lines)
print(f"INFO manifest markers: UNVERIFIED={text.count('UNVERIFIED')} UNKNOWN={text.count('UNKNOWN')}")

# Manifest sample entries must match the files.
for m in re.finditer(r"\{path: ([^,]+), records: (\d+), bytes: (\d+)", text):
    p, n = m.group(1), int(m.group(3))
    full = os.path.join(root, p)
    actual = os.path.getsize(full) if os.path.isfile(full) else -1
    report(actual == n, f"manifest sample size {p}: manifest {n} bytes, file {actual} bytes")

samples = os.path.join(root, "datasets/samples")
for dirpath, _, files in os.walk(samples):
    for fn in files:
        p = os.path.join(dirpath, fn)
        size = os.path.getsize(p)
        report(size < 200_000, f"sample size {os.path.relpath(p, root)}: {size} bytes < 200000")
        if fn.endswith(".jsonl"):
            try:
                rows = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
                report(len(rows) > 0, f"jsonl parses {os.path.relpath(p, root)}: {len(rows)} records")
            except ValueError as e:
                report(False, f"jsonl parses {os.path.relpath(p, root)}: {e}")

# DBLP sample: well-formed XML; the external DTD is loaded only from the sample directory.
xml_path = os.path.join(samples, "dblp/dblp-sample.xml")
try:
    parser = expat.ParserCreate()
    parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_ALWAYS)
    count = {"n": 0}
    def ext(context, base, system_id, public_id):
        sub = parser.ExternalEntityParserCreate(context)
        with open(os.path.join(os.path.dirname(xml_path), os.path.basename(system_id)), "rb") as d:
            sub.ParseFile(d)
        return 1
    def start(name, attrs):
        if name in ("article", "inproceedings"):
            count["n"] += 1
    parser.ExternalEntityRefHandler = ext
    parser.StartElementHandler = start
    with open(xml_path, "rb") as f:
        parser.ParseFile(f)
    report(count["n"] > 0, f"dblp sample is well-formed XML with DTD entities resolved: {count['n']} records")
except Exception as e:
    report(False, f"dblp sample XML: {e}")

def lines_of(p):
    return {l.strip() for l in open(p, encoding="utf-8") if l.strip()}
dblp_dois = lines_of(os.path.join(samples, "dblp/dois.txt"))
oa = {json.loads(l)["doi"].lower().removeprefix("https://doi.org/") for l in open(os.path.join(samples, "openalex/works-sample.jsonl"))}
cr = {json.loads(l)["DOI"].lower() for l in open(os.path.join(samples, "crossref/works-sample.jsonl"))}
report(oa and oa <= dblp_dois, f"openalex sample DOIs subset of dblp sample DOIs: {len(oa & dblp_dois)}/{len(oa)} linked")
report(cr and cr <= dblp_dois, f"crossref sample DOIs subset of dblp sample DOIs: {len(cr & dblp_dois)}/{len(cr)} linked")

# Decision 1: no real ORCID record is committed. Every file under samples/orcid/ is JSONL, and every record is either
# ORCID's fictional test record (file name says so) or synthetic (top-level label, file name, "synthetic:" iD,
# no ORCID-iD-shaped string anywhere, no name shared with a committed sample author, DOIs only from the DBLP sample).
test_id = json.load(open(sys.argv[2], encoding="utf-8"))["orcid"]["fictional_test_record_id"]
id_shape = re.compile(r"\d{4}-\d{4}-\d{4}-\d{3}[\dX]")
real_names = set(re.findall(r"<author[^>]*>([^<]+)</author>", open(xml_path, encoding="latin-1").read()))
for l in open(os.path.join(samples, "openalex/works-sample.jsonl"), encoding="utf-8"):
    for a in json.loads(l).get("authorships") or []:
        real_names |= {a.get("raw_author_name"), (a.get("author") or {}).get("display_name")}
for l in open(os.path.join(samples, "crossref/works-sample.jsonl"), encoding="utf-8"):
    for a in json.loads(l).get("author") or []:
        real_names |= {a.get("name"), " ".join(x for x in (a.get("given"), a.get("family")) if x)}
real_names = {n.strip().lower() for n in real_names if n}
orcid_dir = os.path.join(samples, "orcid")
kinds = {"test": 0, "synthetic": 0}
for fn in sorted(os.listdir(orcid_dir)) if os.path.isdir(orcid_dir) else []:
    rel = f"datasets/samples/orcid/{fn}"
    if not fn.endswith(".jsonl"):
        report(False, f"orcid sample {rel}: only .jsonl files are allowed"); continue
    for i, l in enumerate(l for l in open(os.path.join(orcid_dir, fn), encoding="utf-8") if l.strip()):
        r, where = json.loads(l), f"{rel} record {i + 1}"
        oid = r.get("orcid")
        if oid == test_id:
            ok = r.get("sample_kind") == "orcid-fictional-test-record" and r.get("synthetic") is False and "fictional-test-record" in fn
            report(ok, f"orcid decision 1 {where}: fictional test record {test_id}, labeled, in a fictional-test-record file")
            kinds["test"] += ok
            continue
        names = {n.lower() for n in [*(r.get("name") or {}).values(), *(r.get("other_names") or [])] if isinstance(n, str)}
        names |= {" ".join(x for x in ((r.get("name") or {}).get("given"), (r.get("name") or {}).get("family")) if x).lower()}
        dois = set(r.get("work_dois") or [])
        problems = [msg for bad, msg in [
            (r.get("synthetic") is not True or r.get("sample_kind") != "synthetic", "top-level synthetic label missing"),
            ("synthetic" not in fn, "file name lacks 'synthetic'"),
            (not (isinstance(oid, str) and oid.startswith("synthetic:")), f"iD {oid!r} lacks the 'synthetic:' prefix"),
            (bool(id_shape.search(json.dumps(r))), "contains an ORCID-iD-shaped string"),
            (bool(names & real_names), f"name shared with a committed sample author: {sorted(names & real_names)}"),
            (not dois or not dois <= dblp_dois, f"work_dois must be non-empty and in dblp/dois.txt ({len(dois & dblp_dois)}/{len(dois)})"),
        ] if bad]
        report(not problems, f"orcid decision 1 {where}: synthetic record" + (f" ({'; '.join(problems)})" if problems else ""))
        kinds["synthetic"] += not problems
print(f"INFO orcid samples: fictional_test_records={kinds['test']} synthetic_records={kinds['synthetic']}")
print(f"INFO doi overlap: dblp={len(dblp_dois)} dblp&openalex={len(dblp_dois & oa)} dblp&crossref={len(dblp_dois & cr)} all_three={len(dblp_dois & oa & cr)}")

sums = os.path.join(samples, "SHA256SUMS")
ok = os.path.isfile(sums)
if ok:
    for l in open(sums, encoding="utf-8"):
        h, name = l.rstrip("\n").split("  ", 1)
        with open(os.path.join(samples, name), "rb") as f:
            if hashlib.sha256(f.read()).hexdigest() != h:
                ok = False; print(f"INFO checksum mismatch {name}")
report(ok, "datasets/samples/SHA256SUMS verifies")
sys.exit(1 if fails else 0)
PY
py_status=$?
[ "$py_status" = 0 ] || FAILS=$((FAILS + 1))

if [ "$FAILS" -eq 0 ]; then log DONE "validate: all checks passed"; exit 0; fi
log FAIL "validate: $FAILS check group(s) failed"
exit 1
