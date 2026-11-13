#!/bin/bash
# repair-01 Part B: one rule disabled at a time, named suites run, file restored and sha256-checked.
# Usage: disable.sh <patch name> [<patch name> ...]
set -u
SP=/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/7d921cd3-74fa-4486-933f-269f99093723/scratchpad/part-b
ROOT=/Users/achyutpatel/Developer/Cloud/ScholarChain
LOGS=$ROOT/scratch/phase-10-logs/repair-01/part-b
MANIFEST=$LOGS/main-sha256-before-disable.txt
PKG=scholarchain.rag.runtime
cd "$ROOT" || exit 1
export SPARK_LOCAL_IP=127.0.0.1

current=""
restore() {
  if [ -n "$current" ]; then
    cp "$SP/backup.scala" "$current"
    current=""
  fi
}
trap restore EXIT INT TERM

for name in "$@"; do
  avail=$(df -g "$ROOT" | awk 'NR==2 {print $4}')
  if [ "$avail" -lt 5 ]; then echo "STOP: ${avail} GiB free"; exit 3; fi
  case "$name" in
    wo1-*)               suites="$PKG.VerifierSpec $PKG.LlmSpec $PKG.RuntimeApiSpec" ;;
    wo9-D1-*)            suites="$PKG.LlmSpec" ;;
    wo9-D2-*|wo9-D3-*)   suites="$PKG.VerifierSpec" ;;
    wo9-D4-*)            suites="$PKG.CorpusSpec" ;;
  esac
  log="$LOGS/disable-$name.log"
  # Back up the target file, then patch it.
  path=$(python3 -I -c "
src=open('$SP/patches.py').read().split('name, root = sys.argv')[0]
g={}
exec(src,g)
print(g['P']['$name'][0])")
  cp "$ROOT/$path" "$SP/backup.scala"
  current="$ROOT/$path"
  {
    echo "== disable $name: $path"
    python3 -I "$SP/patches.py" "$name" "$ROOT" >/dev/null || { echo "PATCH FAILED"; }
    diff "$SP/backup.scala" "$ROOT/$path"
    echo "== sbt -batch -J-Xmx4g \"rag/testOnly $suites\""
    sbt -batch -J-Xmx4g "rag/testOnly $suites" 2>&1
    echo "== sbt exit $?"
  } > "$log" 2>&1
  restore
  {
    echo "== restored $path; sha256 check against the pre-disable manifest:"
    grep " $path\$" "$MANIFEST" | shasum -a 256 -c
  } >> "$log" 2>&1
  echo "$name: $(grep -c '\*\*\* FAILED \*\*\*' "$log") failed tests; $(grep 'Tests: succeeded' "$log" | tail -1); $(tail -1 "$log")"
done
echo "== full manifest check"
shasum -a 256 -c "$MANIFEST" | grep -vc ": OK$" | sed 's/^/files not OK: /'
