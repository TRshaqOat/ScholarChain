#!/usr/bin/env bash
# Fetch public ORCID records (anonymous Public API, https://pub.orcid.org/v3.0) for ORCID iDs found
# in the DBLP subset (DBLP person records and <author orcid> attributes) or in fetched OpenAlex
# authorships. No other iDs are ever requested.
#
#   SAMPLE (default): at most orcid.sample_max_ids iDs, preferring iDs seen in both DBLP and OpenAlex,
#                     plus ORCID's fictional test record (scratch/datasets/orcid/test-record/).
#   --full:           every iD from the DBLP --full and OpenAlex --full runs. Prints the request count.
#
# Anonymous limits (documented): 12 req/s, 40 burst, 25,000 reads/day per IP. The script stops at
# orcid.daily_request_cap requests per UTC day and exits 7; re-run the next day to resume.
# Output: scratch/datasets/orcid/<mode>/. Re-runs skip completed iDs. No credentials are used.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
parse_mode "$@"
require_tools curl jq comm

DBLP_IN="$(dblp_out_dir "$MODE")"
OA_IN="$SCRATCH/openalex/$MODE"
[ -f "$DBLP_IN/_SUCCESS" ] || die "missing DBLP $MODE subset at $DBLP_IN; run fetch_dblp.sh first" 6
[ -f "$OA_IN/_SUCCESS" ] || die "missing OpenAlex $MODE output at $OA_IN; run fetch_openalex.sh first" 6
OUT="$SCRATCH/orcid/$MODE"
API="$(cfg .orcid.api_base)"
PAUSE="$(cfg .orcid.min_seconds_between_requests)"
CAP="$(cfg .orcid.daily_request_cap)"
mkdir -p "$OUT/raw"

log STEP "input: union of DBLP and OpenAlex ORCID iDs"
LC_ALL=C sort -u "$DBLP_IN/orcids.txt" "$OA_IN/orcids.txt" | grep -E '^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$' >"$OUT/all-ids.txt" || true
LC_ALL=C comm -12 "$DBLP_IN/orcids.txt" "$OA_IN/orcids.txt" >"$OUT/both-ids.txt"
if [ "$MODE" = "sample" ]; then
  MAXN="$(cfg .orcid.sample_max_ids)"
  { cat "$OUT/both-ids.txt"; LC_ALL=C comm -23 "$OUT/all-ids.txt" "$OUT/both-ids.txt"; } | awk -v m="$MAXN" 'NR <= m' >"$OUT/ids.txt.new" # awk, not head: head would SIGPIPE under pipefail
else
  cp "$OUT/all-ids.txt" "$OUT/ids.txt.new"
fi
if [ -f "$OUT/ids.txt" ] && ! cmp -s "$OUT/ids.txt" "$OUT/ids.txt.new"; then
  rm -f "$OUT/ids.txt.new"
  die "input iD list changed since this run started; move $OUT aside to start a new run" 4
fi
mv "$OUT/ids.txt.new" "$OUT/ids.txt"
N="$(wc -l <"$OUT/ids.txt" | tr -d ' ')"
log INFO "mode=$MODE ids=$N (dblp=$(wc -l <"$DBLP_IN/orcids.txt" | tr -d ' ') openalex=$(wc -l <"$OA_IN/orcids.txt" | tr -d ' ') both=$(wc -l <"$OUT/both-ids.txt" | tr -d ' ')) daily_cap=$CAP"
if [ "$MODE" = "full" ]; then
  log PLAN "full: $N record requests; at most $CAP per UTC day, so about $(((N + CAP - 1) / CAP)) day(s)"
  if [ -f "$SCRATCH/orcid/sample/records.jsonl.gz" ]; then
    log PLAN "full: estimated disk about $(awk -v n="$N" -v b="$(bytes_of "$SCRATCH/orcid/sample/records.jsonl.gz")" -v s="$(gzip -dc "$SCRATCH/orcid/sample/records.jsonl.gz" | wc -l)" 'BEGIN{printf "%.0f", 2*n*b/s/1048576}') MiB gzip-compressed (raw plus combined, from sample average)"
  fi
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

COUNT_FILE="$OUT/requests-$(date -u +%Y-%m-%d).count"
used="$(cat "$COUNT_FILE" 2>/dev/null || echo 0)"
log STEP "fetch: one /record request per iD (requests already used today: $used)"
done_n=0
while read -r id; do
  valid_orcid "$id" || die "invalid ORCID iD in input: $id" 4
  dest="$OUT/raw/$id.json"
  if [ -f "$dest.gz" ] || ls "$OUT/raw/$id".http* >/dev/null 2>&1; then continue; fi
  [ "$used" -lt "$CAP" ] || die "fetch: daily cap $CAP reached ($done_n fetched this run); re-run after 00:00 UTC to resume" 7
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
  sleep "$PAUSE"
done <"$OUT/ids.txt"
log DONE "fetch: requests this run=$done_n"

log STEP "combine: records.jsonl.gz"
{ for f in "$OUT"/raw/*.json.gz; do [ -e "$f" ] && gzip -dc "$f" | jq -c '.'; done; } | gzip -n >"$OUT/records.jsonl.gz.part"
mv "$OUT/records.jsonl.gz.part" "$OUT/records.jsonl.gz"
ls "$OUT"/raw/*.http* 2>/dev/null | sed 's#.*/##' >"$OUT/unavailable-ids.txt" || true
RECS="$(gzip -dc "$OUT/records.jsonl.gz" | wc -l | tr -d ' ')"
write_run_info "$OUT" dataset=orcid mode="$MODE" api_base="$API" dblp_snapshot="$(cfg .dblp.snapshot_date)" \
  config_version="$(cfg .config_version)" retrieved_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  records="$RECS" unavailable="$(wc -l <"$OUT/unavailable-ids.txt" | tr -d ' ')"
write_sha256sums "$OUT"
touch "$OUT/_SUCCESS"
log DONE "orcid: records=$RECS unavailable=$(wc -l <"$OUT/unavailable-ids.txt" | tr -d ' ')"
