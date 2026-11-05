#!/usr/bin/env bash
# Fetch public ORCID records (anonymous Public API, https://pub.orcid.org/v3.0) for ORCID iDs found
# in the DBLP subset (DBLP person records and <author orcid> attributes) or in fetched OpenAlex
# authorships. No other iDs are ever requested.
#
#   SAMPLE (default): at most orcid.sample_max_ids iDs, plus ORCID's fictional test record
#                     (scratch/datasets/orcid/test-record/).
#   --full:           at most orcid.full_max_ids iDs from the DBLP --full and OpenAlex --full runs.
#   --plan:           with either mode, print the selection counts, request count, estimated duration,
#                     and estimated disk, then exit 0. Fetches nothing and writes nothing.
#   --self-test:      check the selection and ranking rule on fixtures (no network, no scratch writes).
#
# Selection, in class priority order, cut at the cap:
#   b) iDs from DBLP person records; a) remaining iDs in both DBLP and OpenAlex;
#   c) remaining iDs from DBLP <author orcid> attributes; d) remaining OpenAlex-only iDs.
# Classes b, c, d are in iD string order (LC_ALL=C). Class a: --full ranks by distinct subset works per iD
# (dblp_extract.py --pass orcid-work-counts), highest first, ties by iD string order; SAMPLE keeps iD string order.
# The run directory gets ids.txt (the selected list, in order) and selection.json (counts per class, ranking).
#
# Anonymous limits (documented): 12 req/s, 40 burst, 25,000 reads/day per IP. Each request is counted
# against its own UTC day (requests-<UTC date>.count); at orcid.daily_request_cap requests in one UTC
# day the script exits 7; re-run later to resume. Output: scratch/datasets/orcid/<mode>/.
# Re-runs skip completed iDs. No credentials are used.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

ORCID_RE='^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$'
CLASSES='b_dblp_person_records a_dblp_and_openalex c_dblp_author_elements d_openalex_only'
TAB="$(printf '\t')"

# select_ids PERSONS WORKS OPENALEX MAX DIR RANK DBLP_WORKS_XML OPENALEX_WORKS_JSONL
# RANK is works (rank class a by distinct subset works) or string (class a in iD string order).
# Writes DIR/ids.txt and DIR/selection.json (scratch files in DIR).
select_ids() {
  local dir="$5" left="$4" n s k av="" se="" sa=0 basis
  { grep -E "$ORCID_RE" "$1" || true; } | LC_ALL=C sort -u >"$dir/p"
  { grep -E "$ORCID_RE" "$2" || true; } | LC_ALL=C sort -u >"$dir/w"
  { grep -E "$ORCID_RE" "$3" || true; } | LC_ALL=C sort -u >"$dir/o"
  LC_ALL=C sort -u "$dir/p" "$dir/w" >"$dir/dblp"
  LC_ALL=C comm -12 "$dir/dblp" "$dir/o" >"$dir/a"
  LC_ALL=C comm -23 "$dir/p" "$dir/a" >"$dir/b"
  LC_ALL=C comm -23 "$dir/w" "$dir/p" | LC_ALL=C comm -23 - "$dir/a" >"$dir/c"
  LC_ALL=C comm -23 "$dir/o" "$dir/dblp" >"$dir/d"
  python3 -I "$SCRIPTS_DIR/dblp_extract.py" --pass orcid-work-counts --openalex "$8" --out "$dir" <"$7" >/dev/null
  # a.ranked: iD TAB distinct subset works (0 if uncounted), in selection order
  LC_ALL=C join -t "$TAB" -a 1 -e 0 -o 0,2.2 "$dir/a" "$dir/orcid-work-counts.tsv" >"$dir/a.counts"
  case "$6" in
    works) LC_ALL=C sort -t "$TAB" -k2,2nr -k1,1 "$dir/a.counts" >"$dir/a.ranked"
      basis="distinct subset works per iD, highest first; ties by iD string order (LC_ALL=C)" ;;
    string) cp "$dir/a.counts" "$dir/a.ranked"
      basis="iD string order (LC_ALL=C); SAMPLE mode keeps the pre-repair within-class order" ;;
    *) die "select_ids: unknown rank $6" 2 ;;
  esac
  cut -f1 "$dir/a.ranked" >"$dir/a.order"
  awk -v m="$4" 'NR <= m' "$dir/b" "$dir/a.order" "$dir/c" "$dir/d" >"$dir/ids.txt" # awk, not head: head would SIGPIPE under pipefail
  for k in b a c d; do
    n="$(wc -l <"$dir/$k" | tr -d ' ')"
    s=$((n < left ? n : left))
    left=$((left - s))
    [ "$k" = a ] && sa="$s"
    av="$av${av:+,}$n"; se="$se${se:+,}$s"
  done
  jq -n --arg cv "$(cfg .config_version)" --arg csha "$(sha256_of "$CONFIG")" --argjson max "$4" \
    --argjson av "[$av]" --argjson se "[$se]" --arg names "$CLASSES" --arg rank "$6" --arg basis "$basis" \
    --argjson sel "$(awk -F "$TAB" -v n="$sa" 'NR <= n {printf "%s%s", (NR > 1 ? "," : "["), $2} END {print (n > 0 ? "]" : "[]")}' "$dir/a.ranked")" \
    --slurpfile link "$dir/stats-orcid-work-counts.json" \
    --arg dblp "$(wc -l <"$dir/dblp" | tr -d ' ')" --arg oa "$(wc -l <"$dir/o" | tr -d ' ')" '
    ($names | split(" ")) as $n
    | {config_version: $cv, config_sha256: $csha, max_ids: $max,
       rule: "priority b, a, c, d; class a ranked as class_a_ranking.basis; b, c, d in iD string order (LC_ALL=C); first max_ids iDs",
       class_order: $n,
       class_a_ranking: {rank: $rank, basis: $basis,
         work_identity: "dblp:<key> for each DBLP subset record whose <author orcid> attribute carries the iD; an OpenAlex work whose lower-cased DOI is on a DBLP subset record counts as that record (lowest key if several); other OpenAlex works count as doi:<DOI>, or openalex:<id> without a DOI. DBLP person records contribute no works.",
         selected_min_works: ($sel | min), selected_max_works: ($sel | max)},
       work_linkage: $link[0],
       input_distinct: {dblp: ($dblp | tonumber), openalex: ($oa | tonumber)},
       classes: [range(4) as $i | {class: $n[$i], available: $av[$i], selected: $se[$i]}],
       available_total: ($av | add), selected_total: ($se | add)}' >"$dir/selection.json"
}

self_test() {
  local t i1=0000-0000-0000-001X i2=0000-0000-0000-0028 i3=0000-0000-0000-0036 i4=0000-0000-0000-0044 \
    i5=0000-0000-0000-0052 i6=0000-0000-0000-0060 i7=0000-0000-0000-0079 i8=0000-0000-0000-0087 i9=0000-0000-0000-0095
  t="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-orcid-selftest.XXXXXX")"
  # classes: a = i1 i2 i3 i7; b = i4 i5; c = i6; d = i8 i9
  printf '%s\n' "$i7" "$i5" "$i4" "$i1" >"$t/in-persons"
  printf '%s\n' "$i4" "$i3" "$i6" "$i2" not-an-id >"$t/in-works"
  printf '%s\n' "$i9" "$i2" "$i1" "$i8" "$i3" "$i7" >"$t/in-openalex"
  au() { printf '<author orcid="%s">N</author>' "$@"; }
  { echo '<?xml version="1.0" encoding="ISO-8859-1"?>'; echo '<dblp>'
    echo "<inproceedings mdate=\"2020-01-01\" key=\"conf/x/K1\">$(au "$i2" "$i3" "$i4")<year>2020</year><ee>https://doi.org/10.1/One</ee></inproceedings>"
    echo "<inproceedings mdate=\"2020-01-01\" key=\"conf/x/K2\">$(au "$i2" "$i3" "$i6")<year>2020</year><ee>https://doi.org/10.1/two</ee></inproceedings>"
    echo "<article mdate=\"2020-01-01\" key=\"conf/x/K3\">$(au "$i2")<author>No Id</author><year>2020</year></article>"
    echo '</dblp>'; } >"$t/in-works.xml"
  oa() { jq -cn --arg id "$1" --arg doi "$2" '$ARGS.positional as $o | {id: $id, doi: (if $doi == "" then null else "https://doi.org/" + $doi end),
    authorships: [$o[] | {author: {orcid: ("https://orcid.org/" + .)}}]}' --args "${@:3}"; }
  # distinct works: i1 1 (W1 = K1), i2 3 (K1 K2 K3; W1 = K1 again), i3 3 (K1 K2, doi 10.1/three once for W3 and W3b),
  # i7 2 (W4 and W4b = K2, W5 has no DOI). Ranked a: i2 3, i3 3 (tie by iD), i7 2, i1 1. String order: i1 i2 i3 i7.
  { oa W1 10.1/ONE "$i2" "$i3" "$i1"; oa W3 10.1/three "$i3"; oa W3b 10.1/three "$i3"
    oa W4 10.1/two "$i7"; oa W4b 10.1/two "$i7"; oa W5 "" "$i7" "$i8" "$i9"; } >"$t/in-works.jsonl"
  st() { # st MAX RANK EXPECTED_IDS... : run the selection and compare ids.txt
    local m="$1" r="$2"; shift 2
    select_ids "$t/in-persons" "$t/in-works" "$t/in-openalex" "$m" "$t" "$r" "$t/in-works.xml" "$t/in-works.jsonl"
    printf '%s\n' "$@" | cmp -s - "$t/ids.txt" || { rm -rf "$t"; die "self-test: wrong selected list (max $m, rank $r)" 1; }
  }
  ck() { jq -e "$1" "$t/selection.json" >/dev/null || { rm -rf "$t"; die "self-test: wrong selection.json ($1)" 1; }; }
  # b first, then a ranked by works (i1 with 1 work is cut), c and d cut
  st 5 works "$i4" "$i5" "$i2" "$i3" "$i7"
  ck '[.classes[].available] == [2,4,1,2] and [.classes[].selected] == [2,3,0,0] and .selected_total == 5
    and .class_a_ranking.selected_min_works == 2 and .class_a_ranking.selected_max_works == 3 and .class_a_ranking.rank == "works"
    and .input_distinct == {dblp: 7, openalex: 6} and .class_order[0] == "b_dblp_person_records"'
  ck '.work_linkage | .dblp_records == 3 and .openalex_works == 6 and .openalex_works_matched_to_dblp_record == 3
    and .openalex_works_unmatched_with_doi == 2 and .openalex_works_without_doi == 1'
  st 8 works "$i4" "$i5" "$i2" "$i3" "$i7" "$i1" "$i6" "$i8"
  ck '[.classes[].selected] == [2,4,1,1] and .class_a_ranking.selected_min_works == 1 and .class_a_ranking.selected_max_works == 3'
  st 2 works "$i4" "$i5"
  ck '.class_a_ranking.selected_min_works == null and .class_a_ranking.selected_max_works == null'
  st 5 string "$i4" "$i5" "$i1" "$i2" "$i3"
  ck '.class_a_ranking.rank == "string" and .class_a_ranking.selected_min_works == 1 and .class_a_ranking.selected_max_works == 3'
  rm -rf "$t"
  echo "fetch_orcid selection self-test: OK"
}

PLAN=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --plan) PLAN=1 ;;
    --self-test) require_tools jq comm join python3; self_test; exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done
parse_mode ${ARGS[@]+"${ARGS[@]}"}
require_tools curl jq comm join python3

DBLP_IN="$(dblp_out_dir "$MODE")"
OA_IN="$SCRATCH/openalex/$MODE"
[ -f "$DBLP_IN/_SUCCESS" ] || die "missing DBLP $MODE subset at $DBLP_IN; run fetch_dblp.sh first" 6
[ -f "$OA_IN/_SUCCESS" ] || die "missing OpenAlex $MODE output at $OA_IN; run fetch_openalex.sh first" 6
OUT="$SCRATCH/orcid/$MODE"
API="$(cfg .orcid.api_base)"
PAUSE="$(cfg .orcid.min_seconds_between_requests)"
CAP="$(cfg .orcid.daily_request_cap)"
if [ "$MODE" = "sample" ]; then MAXN="$(cfg .orcid.sample_max_ids)"; RANK=string; else MAXN="$(cfg .orcid.full_max_ids)"; RANK=works; fi

SEL="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-orcid-select.XXXXXX")"
trap 'rm -rf "$SEL"' EXIT
log STEP "select: priority classes b, a, c, d from DBLP person records, DBLP author elements, and OpenAlex authorships (max $MAXN, class a rank $RANK)"
select_ids "$DBLP_IN/orcids-persons.txt" "$DBLP_IN/orcids-works.txt" "$OA_IN/orcids.txt" "$MAXN" "$SEL" "$RANK" "$DBLP_IN/works.xml" "$OA_IN/works.jsonl"
N="$(wc -l <"$SEL/ids.txt" | tr -d ' ')"
jq -r '.classes[] | "class \(.class): available=\(.available) selected=\(.selected)"' "$SEL/selection.json" | while read -r l; do log INFO "$l"; done
jq -r '.class_a_ranking | "class a ranking: \(.rank); selected min_works=\(.selected_min_works) max_works=\(.selected_max_works)"' "$SEL/selection.json" | while read -r l; do log INFO "$l"; done
log INFO "mode=$MODE dblp=$(jq .input_distinct.dblp "$SEL/selection.json") openalex=$(jq .input_distinct.openalex "$SEL/selection.json") union=$(jq .available_total "$SEL/selection.json") selected=$N max=$MAXN daily_cap=$CAP"

if [ "$PLAN" = 1 ]; then
  [ -f "$OUT/ids.txt" ] && ! cmp -s "$OUT/ids.txt" "$SEL/ids.txt" && log WARN "plan: $OUT/ids.txt differs from this selection; a run would exit 4 until $OUT is moved aside"
  done_n="$({ ls "$OUT/raw" 2>/dev/null || true; } | sed -E 's/\.(json\.gz|http[0-9]+)$//' | LC_ALL=C sort -u \
    | LC_ALL=C comm -12 - <(LC_ALL=C sort "$SEL/ids.txt") | wc -l | tr -d ' ')"
  req=$((N - done_n))
  used="$(cat "$OUT/requests-$(date -u +%Y-%m-%d).count" 2>/dev/null || echo 0)"
  log PLAN "requests: $req (selected $N, already fetched $done_n); used today (UTC $(date -u +%Y-%m-%d)): $used of $CAP"
  if [ $((used + req)) -le "$CAP" ]; then log PLAN "quota: fits in the current UTC day"; else log PLAN "quota: does NOT fit in the current UTC day; the run would exit 7 and need a re-run on a later UTC day"; fi
  log PLAN "duration: at least $(awk -v n="$req" -v p="$PAUSE" 'BEGIN{printf "%.1f", n*p/3600}') h from the ${PAUSE}s pause alone"
  # Observed pace: spread of download-completion times (file mtimes) across the SAMPLE run's raw records.
  pace="$(python3 -I -c 'import glob, os, sys
t = sorted(os.path.getmtime(f) for f in glob.glob(sys.argv[1]))
print("%.3f %d" % ((t[-1] - t[0]) / (len(t) - 1), len(t)) if len(t) > 2 else "")' "$SCRATCH/orcid/sample/raw/*.json.gz")"
  if [ -n "$pace" ]; then
    log PLAN "duration: about $(awk -v n="$req" -v s="${pace% *}" 'BEGIN{printf "%.1f", n*s/3600}') h at the SAMPLE run's observed ${pace% *} s per request (${pace#* } records)"
  else
    log PLAN "duration: no SAMPLE run timing available; run SAMPLE mode first for an observed estimate"
  fi
  if [ -f "$SCRATCH/orcid/sample/records.jsonl.gz" ]; then
    log PLAN "disk: about $(awk -v n="$N" -v b="$(bytes_of "$SCRATCH/orcid/sample/records.jsonl.gz")" -v s="$(gzip -dc "$SCRATCH/orcid/sample/records.jsonl.gz" | wc -l)" 'BEGIN{printf "%.0f", 2*n*b/s/1048576}') MiB gzip-compressed (raw plus combined, from the SAMPLE average)"
  else
    log PLAN "disk: no SAMPLE records available for an estimate"
  fi
  log PLAN "nothing fetched; to run: bash datasets/scripts/fetch_orcid.sh${ARGS[*]+ ${ARGS[*]}}"
  exit 0
fi

mkdir -p "$OUT/raw"
if [ -f "$OUT/ids.txt" ]; then
  cmp -s "$OUT/ids.txt" "$SEL/ids.txt" || die "selected iD list changed since this run started; move $OUT aside to start a new run" 4
else
  mv "$SEL/selection.json" "$OUT/selection.json"
  mv "$SEL/ids.txt" "$OUT/ids.txt"
fi

if [ "$MODE" = "sample" ]; then
  # Human ruling (phase-03 repair-01, Decision 1): the only real-registry ORCID record that may be committed is
  # ORCID's fictional demonstration record. Fetch it separately; it is refused if it no longer self-declares fictitious.
  TID="$(cfg .orcid.fictional_test_record_id)"
  TOUT="$SCRATCH/orcid/test-record"
  valid_orcid "$TID" || die "config orcid.fictional_test_record_id is not an ORCID iD" 4
  if [ -f "$TOUT/_SUCCESS" ]; then
    log SKIP "test record: $TOUT already complete"
  else
    log STEP "test record: fetch ORCID's fictional demonstration record $TID"
    mkdir -p "$TOUT"
    code="$(http_get "$API/$TID/record" "$TOUT/$TID.json" -H 'Accept: application/json')" || die "test record: network failure (re-run to resume)" 5
    [ "$code" = 200 ] || die "test record: HTTP $code" 5
    jq -e --arg id "$TID" --arg mk "$(cfg .orcid.fictional_test_record_marker)" \
      '."orcid-identifier".path == $id and ((.person.biography.content // "") | ascii_downcase | contains($mk))' "$TOUT/$TID.json" >/dev/null \
      || { rm -f "$TOUT/$TID.json"; die "test record: $TID did not return a record that declares itself fictitious; not used" 4; }
    gzip -n -f "$TOUT/$TID.json"
    write_run_info "$TOUT" dataset=orcid kind=fictional-test-record orcid="$TID" source_url="$API/$TID/record" \
      config_version="$(cfg .config_version)" retrieved_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    write_sha256sums "$TOUT"
    touch "$TOUT/_SUCCESS"
    log DONE "test record: $TOUT/$TID.json.gz"
  fi
fi
if [ -f "$OUT/_SUCCESS" ]; then log SKIP "fetch: $OUT already complete"; exit 0; fi

STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
log STEP "fetch: one /record request per selected iD ($N); requests already used today: $(cat "$OUT/requests-$(date -u +%Y-%m-%d).count" 2>/dev/null || echo 0)"
done_n=0
day=""
used=0
while read -r id; do
  valid_orcid "$id" || die "invalid ORCID iD in input: $id" 4
  dest="$OUT/raw/$id.json"
  if [ -f "$dest.gz" ] || ls "$OUT/raw/$id".http* >/dev/null 2>&1; then continue; fi
  today="$(date -u +%Y-%m-%d)" # each request counts against the UTC day it is made (phase-03 review risk 1)
  if [ "$today" != "$day" ]; then
    day="$today"
    COUNT_FILE="$OUT/requests-$day.count"
    used="$(cat "$COUNT_FILE" 2>/dev/null || echo 0)"
  fi
  [ "$used" -lt "$CAP" ] || die "fetch: daily cap $CAP reached for UTC $day ($done_n fetched this run); re-run after 00:00 UTC to resume" 7
  code="$(http_get "$API/$id/record" "$dest" -H 'Accept: application/json')" || die "fetch: network failure (re-run to resume)" 5
  used=$((used + 1))
  printf '%s\n' "$used" >"$COUNT_FILE"
  case "$code" in
    200) jq -e '."orcid-identifier".path' "$dest" >/dev/null || { rm -f "$dest"; die "fetch: malformed record for $id" 4; }
      gzip -n -f "$dest" ;; # full records average ~237 KB in the sample, so raw records are stored gzip-compressed
    404|409|410) : >"$OUT/raw/$id.http$code" ;; # unknown, locked, or deactivated: recorded, not retried
    *) die "fetch: HTTP $code for $id" 5 ;;
  esac
  done_n=$((done_n + 1))
  if [ $((done_n % 1000)) = 0 ]; then log INFO "fetch: $done_n requests this run (UTC $day: $used)"; fi
  sleep "$PAUSE"
done <"$OUT/ids.txt"
log DONE "fetch: requests this run=$done_n; every selected iD is fetched or recorded unavailable"

log STEP "combine: records.jsonl.gz from the $N selected iDs"
{ for f in "$OUT"/raw/*.json.gz; do [ -e "$f" ] && gzip -dc "$f"; done; } | jq -c '.' | gzip -n >"$OUT/records.jsonl.gz.part"
mv "$OUT/records.jsonl.gz.part" "$OUT/records.jsonl.gz"
ls "$OUT"/raw/*.http* 2>/dev/null | sed 's#.*/##' >"$OUT/unavailable-ids.txt" || true
RECS="$(gzip -dc "$OUT/records.jsonl.gz" | wc -l | tr -d ' ')"
write_run_info "$OUT" dataset=orcid mode="$MODE" api_base="$API" dblp_snapshot="$(cfg .dblp.snapshot_date)" \
  config_version="$(cfg .config_version)" fetch_started_utc="$STARTED" retrieved_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  selected="$N" records="$RECS" unavailable="$(wc -l <"$OUT/unavailable-ids.txt" | tr -d ' ')"
write_sha256sums "$OUT"
touch "$OUT/_SUCCESS"
log DONE "orcid: selected=$N records=$RECS unavailable=$(wc -l <"$OUT/unavailable-ids.txt" | tr -d ' ')"
