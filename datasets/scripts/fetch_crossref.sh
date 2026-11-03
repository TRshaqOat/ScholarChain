#!/usr/bin/env bash
# Fetch Crossref works for the DBLP subset's DOIs (official REST API, https://api.crossref.org).
#
#   SAMPLE (default): DOIs from the DBLP SAMPLE run (a few dozen, one or two requests).
#   --full:           DOIs from the DBLP --full run. Prints the request count first.
#
# Requires SCHOLARCHAIN_CONTACT_EMAIL (polite pool: mailto parameter and User-Agent; never written).
# Batched: crossref.dois_per_request DOIs per list request (filter doi:A,doi:B,...). Repeated doi
# filters were observed to combine with OR (2026-11-02); this is not documented, so every DOI a
# batch does not return is retried with the documented singleton route /works/{doi}.
# Rate-limited and resumable (one raw file per batch or singleton). Abstracts are never requested
# by the list route (select excludes them).
# Output: scratch/datasets/crossref/<mode>/. Re-runs skip completed work.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
parse_mode "$@"
require_tools curl jq split comm
EMAIL="$(contact_email)" || die "SCHOLARCHAIN_CONTACT_EMAIL is not set to an email address (needed for the Crossref polite pool)" 3

IN="$(dblp_out_dir "$MODE")"
[ -f "$IN/_SUCCESS" ] || die "missing DBLP $MODE subset at $IN; run fetch_dblp.sh $([ "$MODE" = full ] && echo --full) first" 6
OUT="$SCRATCH/crossref/$MODE"
API="$(cfg .crossref.api_base)"
PER="$(cfg .crossref.dois_per_request)"
PAUSE="$(cfg .crossref.min_seconds_between_requests)"
SELECT="$(cfg .crossref.select)"
MAILTO="$(uri_encode "$EMAIL")"
mkdir -p "$OUT/raw"

log STEP "input: check DOI list $IN/dois.txt"
IN_SHA="$(sha256_of "$IN/dois.txt")"
if [ -f "$OUT/input.sha256" ] && [ "$(cat "$OUT/input.sha256")" != "$IN_SHA" ]; then
  die "input DOI list changed since this run started; move $OUT aside to start a new run" 4
fi
printf '%s\n' "$IN_SHA" >"$OUT/input.sha256"
N="$(wc -l <"$IN/dois.txt" | tr -d ' ')"
REQ=$(((N + PER - 1) / PER))
log INFO "mode=$MODE dois=$N batch=$PER list_requests<=$REQ pause=${PAUSE}s"
if [ "$MODE" = "full" ]; then
  log PLAN "full: about $REQ list requests plus one singleton request per DOI a batch does not return (observed list-pool limit 3 req/s)"
  if [ -f "$SCRATCH/crossref/sample/works.jsonl" ]; then
    log PLAN "full: estimated download about $(awk -v n="$N" -v b="$(bytes_of "$SCRATCH/crossref/sample/works.jsonl")" -v s="$(wc -l <"$SCRATCH/crossref/sample/works.jsonl")" 'BEGIN{printf "%.0f", n*b/s/1048576}') MiB (from sample average)"
  fi
fi
if [ -f "$OUT/_SUCCESS" ]; then log SKIP "fetch: $OUT already complete"; exit 0; fi

rm -rf "$OUT/batches"
mkdir -p "$OUT/batches"
{ grep -v ',' "$IN/dois.txt" || true; } >"$OUT/batches/safe.txt"
split -l "$PER" -a 5 "$OUT/batches/safe.txt" "$OUT/batches/b."

fetch_or_die() { # url dest -> status code; exits on 429 or unexpected codes
  local code
  code="$(http_get "$1" "$2")" || die "fetch: network failure (re-run to resume)" 5
  case "$code" in 200|404) printf '%s' "$code" ;; 429) die "fetch: HTTP 429 (rate limited); re-run later to resume" 7 ;; *) die "fetch: HTTP $code for $1" 5 ;; esac
}

log STEP "fetch: batched list requests"
done_n=0
skip_n=0
for f in "$OUT"/batches/b.*; do
  [ -e "$f" ] || continue
  dest="$OUT/raw/${f##*/}.json"
  if [ -f "$dest" ]; then skip_n=$((skip_n + 1)); continue; fi
  filter="$(jq -sRr 'split("\n") | map(select(length > 0) | "doi:" + .) | join(",") | @uri' "$f")"
  code="$(fetch_or_die "$API/works?filter=$filter&rows=$((PER * 2))&select=$SELECT&mailto=$MAILTO" "$dest")"
  [ "$code" = 200 ] || die "fetch: HTTP $code for list request ${f##*/}" 5
  jq -e '.message.items | type == "array"' "$dest" >/dev/null || { rm -f "$dest"; die "fetch: malformed response for ${f##*/}" 4; }
  done_n=$((done_n + 1))
  sleep "$PAUSE"
done
log DONE "fetch: batches fetched=$done_n skipped=$skip_n"

log STEP "fetch: singleton lookups for DOIs not returned by a batch"
{ for f in "$OUT"/raw/b.*.json; do [ -e "$f" ] && jq -r '.message.items[].DOI | ascii_downcase' "$f"; done; } | LC_ALL=C sort -u >"$OUT/batch-dois.txt"
LC_ALL=C sort -u "$IN/dois.txt" | LC_ALL=C comm -23 - "$OUT/batch-dois.txt" >"$OUT/batches/singletons.txt"
single_n=0
while read -r doi; do
  [ -n "$doi" ] || continue
  enc="$(uri_encode "$doi")"
  dest="$OUT/raw/single.$enc.json"
  if [ -f "$dest" ] || [ -f "$dest.404" ]; then continue; fi
  code="$(fetch_or_die "$API/works/$enc?mailto=$MAILTO" "$dest")"
  [ "$code" = 404 ] && : >"$dest.404"
  single_n=$((single_n + 1))
  sleep "$PAUSE"
done <"$OUT/batches/singletons.txt"
log DONE "fetch: singleton requests=$single_n"

log STEP "combine: works.jsonl, found and missing DOIs"
{
  for f in "$OUT"/raw/b.*.json; do [ -e "$f" ] && jq -c '.message.items[]' "$f"; done
  for f in "$OUT"/raw/single.*.json; do [ -e "$f" ] && jq -c '.message' "$f"; done
} >"$OUT/works.jsonl.part"
mv "$OUT/works.jsonl.part" "$OUT/works.jsonl"
jq -r '.DOI | ascii_downcase' "$OUT/works.jsonl" | LC_ALL=C sort -u >"$OUT/found-dois.txt"
LC_ALL=C sort -u "$IN/dois.txt" | LC_ALL=C comm -23 - "$OUT/found-dois.txt" >"$OUT/missing-dois.txt"
jq -r '.author[]?.ORCID // empty | sub("^https?://orcid.org/"; "")' "$OUT/works.jsonl" \
  | grep -E '^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$' | LC_ALL=C sort -u >"$OUT/orcids.txt" || true
write_run_info "$OUT" dataset=crossref mode="$MODE" api_base="$API" dblp_snapshot="$(cfg .dblp.snapshot_date)" \
  config_version="$(cfg .config_version)" input_dois_sha256="$IN_SHA" retrieved_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  works="$(wc -l <"$OUT/works.jsonl" | tr -d ' ')" missing_dois="$(wc -l <"$OUT/missing-dois.txt" | tr -d ' ')"
write_sha256sums "$OUT"
touch "$OUT/_SUCCESS"
log DONE "crossref: works=$(wc -l <"$OUT/works.jsonl" | tr -d ' ') found_dois=$(wc -l <"$OUT/found-dois.txt" | tr -d ' ') missing=$(wc -l <"$OUT/missing-dois.txt" | tr -d ' ') orcids=$(wc -l <"$OUT/orcids.txt" | tr -d ' ')"
