#!/usr/bin/env bash
# Fetch OpenAlex works for the DBLP subset's DOIs (official REST API, https://api.openalex.org).
#
#   SAMPLE (default): DOIs from the DBLP SAMPLE run (a few dozen, one or two requests).
#   --full:           DOIs from the DBLP --full run. Prints the request count and cost estimate first.
#
# Batched: up to openalex.dois_per_request DOIs per list request (filter doi:A|B|..., documented
# maximum 100 values). DOIs containing ',' or '|' use the free singleton lookup instead.
# Rate-limited (openalex.min_seconds_between_requests), resumable (one raw file per batch).
# Optional OPENALEX_API_KEY raises the daily budget; it is sent as a header and never written.
# Output: scratch/datasets/openalex/<mode>/. Re-runs skip completed batches.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
parse_mode "$@"
require_tools curl jq split comm

IN="$(dblp_out_dir "$MODE")"
[ -f "$IN/_SUCCESS" ] || die "missing DBLP $MODE subset at $IN; run fetch_dblp.sh $([ "$MODE" = full ] && echo --full) first" 6
OUT="$SCRATCH/openalex/$MODE"
API="$(cfg .openalex.api_base)"
PER="$(cfg .openalex.dois_per_request)"
PAUSE="$(cfg .openalex.min_seconds_between_requests)"
SELECT="$(cfg .openalex.select)"
mkdir -p "$OUT/raw"

# OPENALEX_API_KEY (human ruling, phase-03 repair-01 Decision 2): environment only, never written, logged,
# echoed, or put in error output. Keep a non-exported copy so child processes (curl, jq, awk) never inherit it.
OA_KEY="${OPENALEX_API_KEY:-}"
unset OPENALEX_API_KEY
# The header reaches curl through a process substitution (-H @/dev/fd/N) written by the printf builtin, so the
# key never appears in any process's argv, in a URL, or in a file.
auth() { if [ -n "$OA_KEY" ]; then printf 'Authorization: Bearer %s\n' "$OA_KEY"; fi; }
# Never store a response that echoes the key back; the pattern also goes through a process substitution.
key_in() { [ -n "$OA_KEY" ] && grep -qF -f <(printf '%s\n' "$OA_KEY") "$1"; }

log STEP "input: check DOI list $IN/dois.txt"
IN_SHA="$(sha256_of "$IN/dois.txt")"
if [ -f "$OUT/input.sha256" ] && [ "$(cat "$OUT/input.sha256")" != "$IN_SHA" ]; then
  die "input DOI list changed since this run started; move $OUT aside to start a new run" 4
fi
printf '%s\n' "$IN_SHA" >"$OUT/input.sha256"
N="$(wc -l <"$IN/dois.txt" | tr -d ' ')"
REQ=$(((N + PER - 1) / PER))
log INFO "mode=$MODE dois=$N batch=$PER list_requests<=$REQ api_key=$([ -n "$OA_KEY" ] && echo set || echo unset)"
if [ "$MODE" = "full" ]; then
  log PLAN "full: about $REQ list requests (cost about \$$(awk -v r="$REQ" 'BEGIN{printf "%.2f", r*0.0001}') at \$0.10 per 1000 list calls)."
  log PLAN "full: keyless daily budget is \$0.10 (about 1000 list calls); a free key gives \$1/day. On budget exhaustion the run exits 7; re-run to resume."
  if [ -f "$SCRATCH/openalex/sample/works.jsonl" ]; then
    log PLAN "full: estimated download about $(awk -v n="$N" -v b="$(bytes_of "$SCRATCH/openalex/sample/works.jsonl")" -v s="$(wc -l <"$SCRATCH/openalex/sample/works.jsonl")" 'BEGIN{printf "%.0f", n*b/s/1048576}') MiB (from sample average)"
  fi
fi
if [ -f "$OUT/_SUCCESS" ]; then log SKIP "fetch: $OUT already complete"; exit 0; fi

rm -rf "$OUT/batches"
mkdir -p "$OUT/batches"
{ grep -v '[,|]' "$IN/dois.txt" || true; } >"$OUT/batches/safe.txt"
{ grep '[,|]' "$IN/dois.txt" || true; } >"$OUT/batches/unsafe.txt"
split -l "$PER" -a 5 "$OUT/batches/safe.txt" "$OUT/batches/b."

log STEP "fetch: batched list requests"
done_n=0
skip_n=0
for f in "$OUT"/batches/b.*; do
  [ -e "$f" ] || continue
  name="${f##*/}"
  dest="$OUT/raw/$name.json"
  if [ -f "$dest" ]; then skip_n=$((skip_n + 1)); continue; fi
  filter="$(jq -sRr 'split("\n") | map(select(length > 0) | "https://doi.org/" + .) | "doi:" + join("|") | @uri' "$f")"
  code="$(http_get "$API/works?filter=$filter&per_page=100&select=$SELECT" "$dest" -H @<(auth))" || die "fetch: network failure at $name (re-run to resume)" 5
  case "$code" in
    200) if key_in "$dest"; then rm -f "$dest"; die "fetch: response for $name contains the API key; discarded" 4; fi
      jq -e '.results | type == "array"' "$dest" >/dev/null || { rm -f "$dest"; die "fetch: malformed response for $name" 4; }
      # per_page=100 leaves headroom for DOIs that map to several works; never truncate silently.
      jq -e '.meta.count <= 100' "$dest" >/dev/null || { rm -f "$dest"; die "fetch: $name matched more than one page of works; lower openalex.dois_per_request" 4; } ;;
    429) die "fetch: HTTP 429 at $name (daily budget or rate limit); re-run later to resume" 7 ;;
    *) die "fetch: HTTP $code at $name" 5 ;;
  esac
  done_n=$((done_n + 1))
  sleep "$PAUSE"
done
log DONE "fetch: batches fetched=$done_n skipped=$skip_n"

log STEP "fetch: singleton lookups for DOIs unsafe in filter syntax ($(wc -l <"$OUT/batches/unsafe.txt" | tr -d ' '))"
while read -r doi; do
  [ -n "$doi" ] || continue
  enc="$(uri_encode "$doi")"
  dest="$OUT/raw/single.$enc.json"
  [ -f "$dest" ] || [ -f "$dest.404" ] && continue
  code="$(http_get "$API/works/doi:$enc?select=$SELECT" "$dest" -H @<(auth))" || die "fetch: network failure (re-run to resume)" 5
  case "$code" in 200) if key_in "$dest"; then rm -f "$dest"; die "fetch: singleton response contains the API key; discarded" 4; fi ;; 404) : >"$dest.404" ;; 429) die "fetch: HTTP 429; re-run later" 7 ;; *) die "fetch: HTTP $code for singleton" 5 ;; esac
  sleep "$PAUSE"
done <"$OUT/batches/unsafe.txt"

log STEP "combine: works.jsonl, found and missing DOIs, ORCIDs from authorships"
{
  for f in "$OUT"/raw/b.*.json; do [ -e "$f" ] && jq -c '.results[]' "$f"; done
  for f in "$OUT"/raw/single.*.json; do [ -e "$f" ] && jq -c '.' "$f"; done
} >"$OUT/works.jsonl.part"
mv "$OUT/works.jsonl.part" "$OUT/works.jsonl"
jq -r '.doi // empty | ascii_downcase | sub("^https://doi.org/"; "")' "$OUT/works.jsonl" | LC_ALL=C sort -u >"$OUT/found-dois.txt"
LC_ALL=C sort -u "$IN/dois.txt" | LC_ALL=C comm -23 - "$OUT/found-dois.txt" >"$OUT/missing-dois.txt"
jq -r '.authorships[]?.author.orcid // empty | sub("^https://orcid.org/"; "")' "$OUT/works.jsonl" \
  | grep -E '^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$' | LC_ALL=C sort -u >"$OUT/orcids.txt" || true
write_run_info "$OUT" dataset=openalex mode="$MODE" api_base="$API" dblp_snapshot="$(cfg .dblp.snapshot_date)" \
  config_version="$(cfg .config_version)" input_dois_sha256="$IN_SHA" retrieved_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  works="$(wc -l <"$OUT/works.jsonl" | tr -d ' ')" missing_dois="$(wc -l <"$OUT/missing-dois.txt" | tr -d ' ')"
write_sha256sums "$OUT"
touch "$OUT/_SUCCESS"
log DONE "openalex: works=$(wc -l <"$OUT/works.jsonl" | tr -d ' ') found_dois=$(wc -l <"$OUT/found-dois.txt" | tr -d ' ') missing=$(wc -l <"$OUT/missing-dois.txt" | tr -d ' ') orcids=$(wc -l <"$OUT/orcids.txt" | tr -d ' ')"
