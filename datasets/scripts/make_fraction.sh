#!/usr/bin/env bash
# Build a deterministic, nested size-scaling fraction (rule fraction-v1, D-13) of the four full local inputs,
# in exactly the layout scripts/run-bounded-pipeline.sh --fraction 25|50 reads:
#   scratch/datasets/fraction=<p>/{dblp/works.xml, openalex/works.jsonl, crossref/works.jsonl,
#                                  orcid/records.jsonl.gz, run-info.json, SHA256SUMS}
# The DTD is not copied: the pipeline reads scratch/datasets/dblp/snapshot=<d>/raw/dblp.dtd for every fraction.
# DBLP persons are not produced: the pipeline uploads no persons file (modules/hadoop/JOBS.md: not read).
#
# Usage: datasets/scripts/make_fraction.sh --fraction 25|50
#        datasets/scripts/make_fraction.sh --verify   (parse, rule, linkage, and nesting checks of every
#                                                     existing fraction directory against the full inputs)
# Local only: no network. Reads the full inputs, verifies them against their SHA256SUMS, refuses to run when
# the projected output would leave less than 5 GiB free, and skips a fraction whose SHA256SUMS already verifies
# for the same source checksums. SHA256SUMS is written last and marks a complete run.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

FRACTION="" VERIFY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --fraction) FRACTION="${2-}"; shift 2 || { usage; die "--fraction needs a value" 2; } ;;
    --verify) VERIFY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown argument: $1" 2 ;;
  esac
done
require_tools python3 jq df awk

SNAP="$(cfg .dblp.snapshot_date)"
DBLP_DIR="$(dblp_out_dir full)"
DTD="$SCRATCH/dblp/snapshot=$SNAP/raw/dblp.dtd"
SRC_DBLP="$DBLP_DIR/works.xml"
SRC_OA="$SCRATCH/openalex/full/works.jsonl"
SRC_CR="$SCRATCH/crossref/full/works.jsonl"
SRC_OR="$SCRATCH/orcid/full/records.jsonl.gz"
for f in "$SRC_DBLP" "$SRC_OA" "$SRC_CR" "$SRC_OR" "$DTD"; do
  [ -f "$f" ] || die "missing full input ${f#"$REPO_ROOT"/}: run the fetch scripts with --full first" 6
done
PY=(python3 -I "$SCRIPTS_DIR/make_fraction.py" --dblp "$SRC_DBLP" --openalex "$SRC_OA" --crossref "$SRC_CR" --orcid "$SRC_OR")

if [ "$VERIFY" = 1 ]; then
  dirs=()
  for p in 25 50; do [ -f "$SCRATCH/fraction=$p/SHA256SUMS" ] && dirs+=("$SCRATCH/fraction=$p"); done
  [ "${#dirs[@]}" -gt 0 ] || die "no complete fraction directory to verify" 6
  for d in "${dirs[@]}"; do
    (cd "$d" && shasum -a 256 -c --quiet SHA256SUMS) || die "${d#"$REPO_ROOT"/}: SHA256SUMS does not verify" 4
    log PASS "${d#"$REPO_ROOT"/}/SHA256SUMS verifies"
  done
  log STEP "verify ${#dirs[@]} fraction(s) against the full inputs"
  "${PY[@]}" --verify --dtd "$DTD" --dirs "${dirs[@]}" || die "fraction verification failed" 4
  log DONE "verify: all checks passed"
  exit 0
fi

case "$FRACTION" in 25|50) ;; *) usage; die "--fraction 25|50 is required (100 is the full input itself)" 2 ;; esac
OUT="$SCRATCH/fraction=$FRACTION"

# Source integrity: each full input must match the SHA256SUMS its fetch run recorded.
META="{}"
for pair in "dblp:$SRC_DBLP" "openalex:$SRC_OA" "crossref:$SRC_CR" "orcid:$SRC_OR" "dtd:$DTD"; do
  name="${pair%%:*}" f="${pair#*:}"
  sum="$(sha256_of "$f")"
  if [ "$name" != dtd ]; then
    want="$(awk -v n="$(basename "$f")" '$2 == n {print $1}' "$(dirname "$f")/SHA256SUMS")"
    [ "$sum" = "$want" ] || die "${f#"$REPO_ROOT"/}: sha256 $sum does not match its SHA256SUMS ($want)" 4
  fi
  log INFO "source $name ${f#"$REPO_ROOT"/} sha256=$sum bytes=$(bytes_of "$f")"
  META="$(jq -c --arg n "$name" --arg p "${f#"$REPO_ROOT"/}" --arg s "$sum" --argjson b "$(bytes_of "$f")" \
    '. + {($n): {source_path: $p, source_sha256: $s, source_bytes: $b}}' <<<"$META")"
done

if [ -f "$OUT/SHA256SUMS" ] && [ -f "$OUT/run-info.json" ] && (cd "$OUT" && shasum -a 256 -c --quiet SHA256SUMS) &&
  [ "$(jq -c '[.inputs[] | .source_sha256] | sort' "$OUT/run-info.json")" = "$(jq -c '[del(.dtd)[] | .source_sha256] | sort' <<<"$META")" ]; then
  log SKIP "${OUT#"$REPO_ROOT"/} is complete for these sources (SHA256SUMS verifies)"
  exit 0
fi

# Disk bound: the output is about p percent of the inputs; keep at least 5 GiB free after writing.
src_kb=0
for f in "$SRC_DBLP" "$SRC_OA" "$SRC_CR" "$SRC_OR"; do src_kb=$((src_kb + $(bytes_of "$f") / 1024)); done
proj_kb=$((src_kb * (FRACTION + 10) / 100))
free_kb="$(df -Pk "$SCRATCH" | awk 'NR == 2 {print $4}')"
log PLAN "fraction $FRACTION -> ${OUT#"$REPO_ROOT"/}: projected $((proj_kb / 1024)) MiB, free $((free_kb / 1024)) MiB"
[ $((free_kb - proj_kb)) -ge $((5 * 1024 * 1024)) ] || die "projected output would leave less than 5 GiB free" 3

log STEP "select fraction $FRACTION (rule fraction-v1)"
mkdir -p "$OUT"
rm -f "$OUT/SHA256SUMS"
"${PY[@]}" --fraction "$FRACTION" --out "$OUT" --meta "$META"
write_sha256sums "$OUT"
while read -r h f; do log INFO "wrote ${OUT#"$REPO_ROOT"/}/$f bytes=$(bytes_of "$OUT/$f") sha256=$h"; done <"$OUT/SHA256SUMS"
log DONE "fraction $FRACTION complete"
