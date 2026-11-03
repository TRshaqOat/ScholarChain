#!/usr/bin/env bash
# Download the pinned DBLP monthly release (dblp.xml.gz) and dblp.dtd with integrity checks,
# then extract the ScholarChain subset defined in datasets/config/subset.v1.json.
#
#   SAMPLE (default): fetches the DTD, the published .md5, and only a byte-range prefix of the
#                     release (dblp.sample_prefix_bytes, 32 MiB), then extracts at most
#                     dblp.sample_max_records subset records. No full download.
#   --full:           downloads the whole release (about 1.1 GB, resumable), verifies the
#                     published MD5, records SHA-256, runs gzip -t, and extracts the full subset.
#
# Output: scratch/datasets/dblp/snapshot=<date>/{raw,sample,full}/. Re-runs skip completed work.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
parse_mode "$@"
require_tools curl jq python3 gzip

SNAP="$(cfg .dblp.snapshot_date)"
GZ_URL="$(cfg .dblp.xml_gz_url)"
MD5_URL="$(cfg .dblp.xml_gz_md5_url)"
WANT_MD5="$(cfg .dblp.xml_gz_md5)"
WANT_BYTES="$(cfg .dblp.xml_gz_bytes)"
DTD_URL="$(cfg .dblp.dtd_url)"
WANT_DTD_SHA="$(cfg .dblp.dtd_sha256)"
BASE="$SCRATCH/dblp/snapshot=$SNAP"
RAW="$BASE/raw"
OUT="$(dblp_out_dir "$MODE")"
mkdir -p "$RAW"
log INFO "mode=$MODE snapshot=$SNAP config=$(cfg .config_version) out=$OUT"

log STEP "dtd: fetch and verify dblp.dtd"
if [ -f "$RAW/dblp.dtd" ] && [ "$(sha256_of "$RAW/dblp.dtd")" = "$WANT_DTD_SHA" ]; then
  log SKIP "dtd: present and verified"
else
  code="$(http_get "$DTD_URL" "$RAW/dblp.dtd")" || die "dtd: network failure" 5
  [ "$code" = "200" ] || die "dtd: HTTP $code from $DTD_URL" 5
  got="$(sha256_of "$RAW/dblp.dtd")"
  [ "$got" = "$WANT_DTD_SHA" ] || die "dtd: sha256 $got does not match config $WANT_DTD_SHA (upstream DTD changed; review before updating the config)" 4
  log DONE "dtd: $(bytes_of "$RAW/dblp.dtd") bytes sha256=$got"
fi
# The release's DOCTYPE names the dated DTD file; provide it under both configured names.
for n in $(cfg '.dblp.dtd_local_names[]'); do [ -f "$RAW/$n" ] || cp "$RAW/dblp.dtd" "$RAW/$n"; done

log STEP "md5: fetch the published release checksum"
MD5_FILE="$RAW/dblp-$SNAP.xml.gz.md5"
if [ ! -f "$MD5_FILE" ]; then
  code="$(http_get "$MD5_URL" "$MD5_FILE")" || die "md5: network failure" 5
  [ "$code" = "200" ] || die "md5: HTTP $code from $MD5_URL" 5
fi
PUB_MD5="$(cut -d' ' -f1 "$MD5_FILE")"
[ "$PUB_MD5" = "$WANT_MD5" ] || die "md5: published $PUB_MD5 differs from config $WANT_MD5" 4
log DONE "md5: published checksum matches config ($PUB_MD5)"

if [ -f "$OUT/_SUCCESS" ] && (cd "$OUT" && grep -v '_SUCCESS$' SHA256SUMS | while read -r h f; do [ "$(sha256_of "$f")" = "$h" ] || exit 1; done); then
  log SKIP "extract: $OUT already complete and checksums verify"
  cat "$OUT/stats-works.json" >&2
  exit 0
fi

if [ "$MODE" = "sample" ]; then
  NBYTES="$(cfg .dblp.sample_prefix_bytes)"
  SRC="$RAW/dblp-$SNAP.prefix-$NBYTES.xml.gz"
  log STEP "prefix: byte-range fetch of the first $NBYTES bytes of the release"
  if [ -f "$SRC" ] && [ "$(bytes_of "$SRC")" = "$NBYTES" ]; then
    log SKIP "prefix: present ($NBYTES bytes)"
  else
    # --max-filesize aborts if the server ignores Range and starts sending the full 1.1 GB body.
    code="$(http_get "$GZ_URL" "$SRC.tmp" -r "0-$((NBYTES - 1))" --max-filesize "$NBYTES")" || die "prefix: network failure or server ignored Range" 5
    [ "$code" = "206" ] || { rm -f "$SRC.tmp"; die "prefix: expected HTTP 206, got $code" 5; }
    [ "$(bytes_of "$SRC.tmp" 2>/dev/null || echo 0)" = "$NBYTES" ] || { rm -f "$SRC.tmp"; die "prefix: unexpected size" 4; }
    mv "$SRC.tmp" "$SRC"
    log DONE "prefix: $NBYTES bytes sha256=$(sha256_of "$SRC")"
  fi
  MAXREC="$(cfg .dblp.sample_max_records)"
  # Truncated gzip prefix: gzip reports an unexpected end of file, which is expected here.
  decompress() { gzip -dc "$SRC" 2>/dev/null || true; }
else
  SRC="$RAW/dblp-$SNAP.xml.gz"
  log PLAN "full: requests=3 (dtd, md5, release); download=$WANT_BYTES bytes (~1.1 GB) to $RAW; decompressed ~5 GB is streamed, never stored"
  if [ -f "$SRC" ] && [ -f "$SRC.sha256" ]; then
    log SKIP "download: $SRC present (verified on a previous run)"
  else
    log STEP "download: resumable fetch of $GZ_URL"
    curl -q -sS -L --retry 6 --retry-connrefused --connect-timeout 20 -C - -A "$(user_agent)" -o "$SRC.part" "$GZ_URL" \
      || die "download: network failure (re-run to resume)" 5
    [ "$(bytes_of "$SRC.part")" = "$WANT_BYTES" ] || die "download: size $(bytes_of "$SRC.part") != $WANT_BYTES (re-run to resume)" 4
    got="$(md5_of "$SRC.part")"
    [ "$got" = "$WANT_MD5" ] || { mv "$SRC.part" "$SRC.corrupt"; die "download: md5 $got != $WANT_MD5; moved to $SRC.corrupt" 4; }
    log STEP "download: gzip -t integrity test"
    gzip -t "$SRC.part" || { mv "$SRC.part" "$SRC.corrupt"; die "download: gzip -t failed" 4; }
    mv "$SRC.part" "$SRC"
    printf '%s  %s\n' "$(sha256_of "$SRC")" "$(basename "$SRC")" >"$SRC.sha256"
    log DONE "download: md5 verified, sha256 recorded in $SRC.sha256"
  fi
  MAXREC=0
  decompress() { gzip -dc "$SRC"; }
fi

TMP="$OUT.tmp"
rm -rf "$TMP"
mkdir -p "$TMP"
log STEP "extract: works pass (types=$(cfg '.dblp.record_types|join(",")') years=$(cfg .dblp.year_min)-$(cfg .dblp.year_max) venues=$(cfg '.dblp.venues|length'))"
decompress | python3 -I "$SCRIPTS_DIR/dblp_extract.py" --pass works --config "$CONFIG" --out "$TMP" --max-records "$MAXREC" >&2
log STEP "extract: persons pass (DBLP person records of subset authors)"
decompress | python3 -I "$SCRIPTS_DIR/dblp_extract.py" --pass persons --out "$TMP" >&2
sort -u "$TMP/orcids-works.txt" "$TMP/orcids-persons.txt" >"$TMP/orcids.txt"
write_run_info "$TMP" dataset=dblp mode="$MODE" snapshot_date="$SNAP" config_version="$(cfg .config_version)" \
  source_url="$GZ_URL" source_md5="$WANT_MD5" source_file_sha256="$(sha256_of "$SRC")" \
  extracted_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
write_sha256sums "$TMP"
touch "$TMP/_SUCCESS"
rm -rf "$OUT"
mv "$TMP" "$OUT"
log DONE "extract: works=$(jq .written "$OUT/stats-works.json") dois=$(wc -l <"$OUT/dois.txt" | tr -d ' ') orcids=$(wc -l <"$OUT/orcids.txt" | tr -d ' ') persons=$(jq .person_records_written "$OUT/stats-persons.json")"
