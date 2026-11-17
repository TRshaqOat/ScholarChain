#!/bin/bash
# P14-D5 harness (repair-02). Builds the hadoop and spark step Args with the real jq filter and the
# real EARLIER lines from scripts/run-bounded-pipeline.sh, drops empty strings as EMR command-runner
# does, and runs scripts/emr/step.sh under /bin/bash 3.2 with stub aws, hadoop, spark-submit, and
# timeout first on a PATH that holds no real aws, hadoop, or spark-submit. No AWS call is possible.
# Usage: harness.sh <repo root>
set -euo pipefail
ROOT="$1"
H="$(cd "$(dirname "$0")" && pwd)"
STUBS="$H/stubs"
WORK="$H/work"
PIPE="$ROOT/scripts/run-bounded-pipeline.sh"
STEP="$ROOT/scripts/emr/step.sh"
EVIDENCE_STEPS="$ROOT/operations/pipeline-runs/20261116T071214Z-fraction25/steps.json"
JQ="$(command -v jq)"
rm -rf "$STUBS" "$WORK"
mkdir -p "$STUBS" "$WORK"

# Stubs: print their argument vector one per line; aws also answers the two s3 ls shapes step.sh uses.
cat >"$STUBS/aws" <<'EOF'
#!/bin/bash
{ echo "  aws argv:"; printf '    [%s]\n' "$@"; } >&2
if [ "$3 $4 $5" = "--cli-read-timeout 120 s3" ] && [ "$6" = ls ]; then
  case "$7" in
    */_manifest.json) echo "2026-11-16 07:30:00        512 _manifest.json" ;;
    *) for r in $STUB_RUNS; do echo "                           PRE $r/"; done ;;
  esac
fi
exit 0
EOF
for t in hadoop spark-submit; do
  printf '#!/bin/bash\necho "  %s argv:"\nprintf '"'"'    [%%s]\\n'"'"' "$@"\n' "$t" >"$STUBS/$t"
done
cat >"$STUBS/timeout" <<'EOF'
#!/bin/bash
echo "  timeout seconds: [$1]"
shift
exec "$@"
EOF
chmod +x "$STUBS"/*
SPATH="$STUBS:/usr/bin:/bin"
for t in aws hadoop spark-submit timeout; do
  got="$(PATH="$SPATH" command -v "$t")"
  [ "$got" = "$STUBS/$t" ] || { echo "stub check failed: $t resolves to $got" >&2; exit 1; }
done
echo "stubs first on PATH: aws hadoop spark-submit timeout -> $STUBS"

# The real Args filter and EARLIER lines, extracted from run-bounded-pipeline.sh.
FILTER="$(awk '/^  .\[\{Type: "CUSTOM_JAR", Name: "classpath-probe"/ {on = 1} on {print} /steps\.json"$/ && on {exit}' "$PIPE" |
  sed -e '1s/^  .//' -e '$s/. >"\$REC\/steps\.json"$//')"
EARLIER_LINES="$(grep -E '^EARLIER=|^\[ -n "\$EARLIER" \] \|\| EARLIER=none$' "$PIPE")"
echo "EARLIER lines from run-bounded-pipeline.sh:"
printf '%s\n' "$EARLIER_LINES" | sed 's/^/  /'
[ "$(printf '%s\n' "$EARLIER_LINES" | wc -l | tr -d ' ')" = 2 ] || { echo "EARLIER lines not found" >&2; exit 1; }

BUCKET=scholarchain-course-data-000000000000-us-east-2
COMMIT=0123456789abcdef0123456789abcdef01234567
S3A="s3a://$BUCKET"
JP="artifacts/jobs/$COMMIT"
BOOT="set -euo pipefail; exec /bin/bash $STEP \"\$@\"" # the real boot first copies step.sh from S3

# build_steps <case dir>: steps.json from the real filter, with EARLIER from <case dir>/hadoop-runs-before.txt
build_steps() {
  local REC="$1" EARLIER
  eval "$EARLIER_LINES"
  "$JQ" -n --arg boot "$BOOT" --arg hjar "s3://$BUCKET/$JP/hadoop-assembly.jar" --arg sjar "s3://$BUCKET/$JP/sparkAnalytics-assembly.jar" \
    --arg ht 5400 --arg st 9000 --arg bucket "$BUCKET" --arg snap 2026-10-03 \
    --arg earlier "$EARLIER" --arg commit "$COMMIT" --arg reducers 2 --arg shuffle 40 \
    --arg dblp "$S3A/raw/dblp/works.xml" --arg dtd "$S3A/raw/dblp/dblp.dtd" --arg oa "$S3A/raw/openalex/works.jsonl" \
    --arg cr "$S3A/raw/crossref/works.jsonl" --arg or "$S3A/raw/orcid/records.jsonl.gz" \
    --arg es 2026-11-03 --arg hout "$S3A/curated/hadoop" --arg sout "$S3A/curated/spark" "$FILTER" >"$REC/steps.json"
}

# run_step <steps.json> <index>: command-runner emulation: drop "" args, then bash -c <boot> step <args...>
run_step() {
  local argv=() a rc
  while IFS= read -r -d '' a; do argv+=("$a"); done < <("$JQ" -j --argjson i "$2" '.[$i].Args[] | select(. != "") | ., "\u0000"' "$1")
  echo "  Args after dropping empty strings, from \"step\" on:"
  printf '    [%s]\n' "${argv[@]:3}"
  set +e
  PATH="$SPATH" /bin/bash -c "$BOOT" "${argv[@]:3}"
  rc=$?
  set -e
  echo "  step.sh exit=$rc"
}

# run_raw <args...>: step.sh called directly with the given vector (for the argument-check cases)
run_raw() {
  local rc
  echo "  argv: $*"
  set +e
  PATH="$SPATH" /bin/bash -c "$BOOT" step "$@"
  rc=$?
  set -e
  echo "  step.sh exit=$rc"
}

NEW=run=run-620fccae97a621ed9437af85f2a590c4
OLD=run=run-11111111111111111111111111111111

echo
echo "=== case 1: empty earlier list (hadoop-runs-before.txt empty) ==="
mkdir -p "$WORK/empty"
: >"$WORK/empty/hadoop-runs-before.txt"
build_steps "$WORK/empty"
echo "spark Args as submitted (steps.json, index 2):"
"$JQ" -c '.[2].Args[4:]' "$WORK/empty/steps.json" | sed 's/^/  /'
echo "--- spark mode (bucket lists only the new run $NEW)"
STUB_RUNS="$NEW" run_step "$WORK/empty/steps.json" 2
echo "--- hadoop mode"
STUB_RUNS="" run_step "$WORK/empty/steps.json" 1

echo
echo "=== case 2: non-empty earlier list (one earlier run $OLD) ==="
mkdir -p "$WORK/nonempty"
echo "$OLD" >"$WORK/nonempty/hadoop-runs-before.txt"
build_steps "$WORK/nonempty"
echo "spark Args as submitted (steps.json, index 2):"
"$JQ" -c '.[2].Args[4:]' "$WORK/nonempty/steps.json" | sed 's/^/  /'
echo "--- spark mode (bucket lists $OLD and the new run $NEW)"
STUB_RUNS="$OLD $NEW" run_step "$WORK/nonempty/steps.json" 2
echo "--- hadoop mode"
STUB_RUNS="" run_step "$WORK/nonempty/steps.json" 1

echo
echo "=== case 3: the P14-D5 vector from the failed run's steps.json (read-only), empty strings dropped ==="
echo "--- spark mode: \"--out\" lands in position 5 (expect exit 2, no aws or spark-submit call)"
STUB_RUNS="$NEW" run_step "$EVIDENCE_STEPS" 2

echo
echo "=== case 4: argument checks (expect exit 2 each, no stub output) ==="
echo "--- no mode"
run_raw
echo "--- spark with 3 of 5 positionals"
run_raw spark 9000 "s3://$BUCKET/$JP/sparkAnalytics-assembly.jar" "$BUCKET"
echo "--- hadoop with 1 of 2 positionals"
run_raw hadoop 5400
echo "--- hadoop with the jar dropped (\"--out\" in position 2)"
run_raw hadoop 5400 --out "$S3A/curated/hadoop" --code-version "$COMMIT"
echo "--- spark with the timeout dropped (\"--out\" in position 5)"
run_raw spark "s3://$BUCKET/$JP/sparkAnalytics-assembly.jar" "$BUCKET" 2026-10-03 none --out "$S3A/curated/spark"
echo "--- classpath with an extra argument"
run_raw classpath extra
echo "--- classpath, no arguments (expect exit 0 and the hybrid hash join section)"
run_raw classpath
