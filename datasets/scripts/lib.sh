# shellcheck shell=bash
# Shared helpers for ScholarChain dataset scripts. Source this file; do not execute it.
# Compatible with bash 3.2 (macOS /bin/bash): no associative arrays, no mapfile.
#
# Exit codes used by every dataset script:
#   0 success (or completed work skipped)   2 usage error
#   3 missing tool or environment variable  4 integrity or input-consistency failure
#   5 network or HTTP failure               6 missing upstream input (run the upstream script first)
#   7 partial run: provider quota reached, re-run later to resume

set -Eeuo pipefail
set +x # never trace, even under `bash -x`: traced expansions would print OPENALEX_API_KEY or the contact email

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DATASETS_DIR="$REPO_ROOT/datasets"
SCRIPTS_DIR="$DATASETS_DIR/scripts"
CONFIG="${SCHOLARCHAIN_DATASET_CONFIG:-$DATASETS_DIR/config/subset.v1.json}"
SCRATCH="$REPO_ROOT/scratch/datasets"

# Greppable step markers: [STEP] [DONE] [SKIP] [WARN] [FAIL] [PLAN] [INFO]
log() { printf '%s [%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" >&2; }
die() { log FAIL "$1"; exit "${2:-1}"; }

require_tools() {
  local t
  for t in "$@"; do command -v "$t" >/dev/null 2>&1 || die "required tool not found: $t" 3; done
}

cfg() { jq -er "$1" "$CONFIG" || die "config value missing: $1 in $CONFIG" 4; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
md5_of() {
  if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi
}
bytes_of() { wc -c <"$1" | tr -d ' '; }

# Contact email for polite pools. Never printed or written to disk.
contact_email() {
  local e="${SCHOLARCHAIN_CONTACT_EMAIL:-}"
  case "$e" in
    *@*.*) printf '%s' "$e" ;;
    *) return 1 ;;
  esac
}
user_agent() {
  local e
  if e="$(contact_email)"; then printf 'ScholarChain-dataset-fetch/1 (mailto:%s)' "$e"; else printf 'ScholarChain-dataset-fetch/1'; fi
}

# parse_mode "$@" sets MODE to sample (default) or full.
parse_mode() {
  MODE=sample
  while [ $# -gt 0 ]; do
    case "$1" in
      --full) MODE=full ;;
      --sample) MODE=sample ;;
      -h|--help) usage; exit 0 ;;
      *) usage; die "unknown argument: $1" 2 ;;
    esac
    shift
  done
}

# http_get URL OUTFILE [curl args...]: prints the HTTP status code.
# Writes OUTFILE only on 200 or 206 (atomic rename). curl --retry covers 408/429/5xx and honours Retry-After.
# -q (must be first) ignores ~/.curlrc, so a user's "verbose" or "trace" setting cannot print request headers.
http_get() {
  local url="$1" out="$2" code
  shift 2
  if ! code="$(curl -q -sS -L --retry 6 --retry-connrefused --retry-max-time 900 --connect-timeout 20 --max-time 600 \
      -A "$(user_agent)" "$@" -o "$out.part" -w '%{http_code}' "$url")"; then
    rm -f "$out.part"
    return 5
  fi
  case "$code" in 200|206) mv "$out.part" "$out" ;; *) rm -f "$out.part" ;; esac
  printf '%s' "$code"
}

uri_encode() { jq -rn --arg v "$1" '$v|@uri'; }

valid_orcid() { printf '%s' "$1" | grep -Eq '^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$'; }

dblp_out_dir() { printf '%s/dblp/snapshot=%s/%s' "$SCRATCH" "$(cfg .dblp.snapshot_date)" "$1"; }

# write_run_info DIR KEY=VALUE...: small JSON provenance file for a fetch run.
write_run_info() {
  local dir="$1" a=()
  shift
  while [ $# -gt 0 ]; do a+=(--arg "${1%%=*}" "${1#*=}"); shift; done
  jq -n "${a[@]}" '$ARGS.named' >"$dir/run-info.json.part"
  mv "$dir/run-info.json.part" "$dir/run-info.json"
}

# write_sha256sums DIR: integrity record of every file in DIR (relative paths, sorted).
write_sha256sums() {
  local dir="$1" f
  (cd "$dir" && find . -type f ! -name SHA256SUMS ! -name '*.part' | LC_ALL=C sort | while read -r f; do
    printf '%s  %s\n' "$(sha256_of "$f")" "${f#./}"
  done) >"$dir/SHA256SUMS.part"
  mv "$dir/SHA256SUMS.part" "$dir/SHA256SUMS"
}
