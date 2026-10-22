#!/usr/bin/env bash
# verify-tests-ran.sh - ScholarChain deterministic floor (see docs/agents.md).
#
# Stop and SubagentStop hook. Refuses completion while any changed Scala or sbt source
# (*.scala, *.sc, *.sbt; tracked, staged, untracked, or deleted, excluding build outputs) is
# newer than the repository test marker .claude/.last-test-run, or the marker is missing.
# The marker is written only by the sbt markTestRun task after tests pass (sbt check).
#
# Exit 0  no Scala or sbt source changed, or tests ran after the latest change.
# Exit 2  block with an actionable message (stderr is shown to the agent). This includes failing
#         closed when the hook cannot change to the project root, because then it cannot tell
#         whether tests are current.
# When Claude Code sets stop_hook_active (the agent was already blocked once in this stop
# sequence), the hook allows the stop to avoid an infinite loop; the agent's report must then
# say that tests were not run (or, for an unreachable project root, not verified). This works
# before the sbt build exists.
#
# Compatible with bash 3.2. Uses jq when available (falls back to a text match).
#
# Usage:
#   verify-tests-ran.sh              hook mode (payload JSON on stdin)
#   verify-tests-ran.sh --self-test  exercise the hook against a throwaway Git fixture created
#                                    with mktemp -d under $TMPDIR and removed afterwards

set -u

MARKER_REL=".claude/.last-test-run"

hook_main() {
  local payload active root marker stale count path st entry ref orig shown
  payload=$(cat 2>/dev/null || true)

  active=false
  if command -v jq >/dev/null 2>&1; then
    active=$(printf '%s' "$payload" | jq -r '.stop_hook_active // false' 2>/dev/null) || active=false
  else
    case "$payload" in
      *'"stop_hook_active":true'*|*'"stop_hook_active": true'*) active=true ;;
    esac
  fi

  root="${CLAUDE_PROJECT_DIR:-}"
  if [ -z "$root" ]; then
    root=$(git rev-parse --show-toplevel 2>/dev/null) || root=$(pwd)
  fi
  if ! cd "$root" 2>/dev/null; then
    # Fail closed: without the project root the hook cannot tell whether tests are current.
    if [ "$active" = "true" ]; then
      # Already blocked once in this stop sequence: allow the stop to avoid an infinite loop.
      exit 0
    fi
    {
      printf 'Stop blocked by .claude/hooks/verify-tests-ran.sh: cannot reach the project root (%s), so it cannot verify that tests ran after the latest Scala or sbt change (fail closed).\n' "$root"
      printf '  What to do: start Claude Code from the repository root (or fix CLAUDE_PROJECT_DIR), run `sbt check`, and report the exact test counts you observed. If you cannot, finish with STATUS: BLOCKED-ON human architect and state in TEST RESULTS that test currency was not verified, quoting this message.\n'
    } >&2
    exit 2
  fi
  marker="$MARKER_REL"

  stale=""
  count=0
  if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    while IFS= read -r -d '' entry; do
      st="${entry:0:2}"
      path="${entry:3}"
      case "$st" in
        R*|C*) IFS= read -r -d '' orig || true ;;
      esac
      case "$path" in
        *.scala|*.sc|*.sbt) ;;
        *) continue ;;
      esac
      case "$path" in
        target/*|*/target/*|.bsp/*|.metals/*|.bloop/*|*/.bloop/*|.claude/*) continue ;;
      esac
      if [ -e "$path" ]; then
        ref="$path"
      else
        # Deleted source: the parent directory's mtime records the deletion.
        ref=$(dirname "$path")
        while [ ! -d "$ref" ]; do ref=$(dirname "$ref"); done
      fi
      if [ ! -f "$marker" ] || [ "$ref" -nt "$marker" ]; then
        stale="$stale  $st $path
"
        count=$((count + 1))
      fi
    done < <(git status --porcelain=v1 -z --untracked-files=all 2>/dev/null)
  else
    while IFS= read -r path; do
      [ -n "$path" ] || continue
      stale="$stale  ?? $path
"
      count=$((count + 1))
    done < <(
      if [ -f "$marker" ]; then
        find . \( -name target -o -name .git -o -name .bsp -o -name .metals -o -name .bloop -o -name .claude \) -prune -o -type f \( -name '*.scala' -o -name '*.sc' -o -name '*.sbt' \) -newer "$marker" -print 2>/dev/null
      else
        find . \( -name target -o -name .git -o -name .bsp -o -name .metals -o -name .bloop -o -name .claude \) -prune -o -type f \( -name '*.scala' -o -name '*.sc' -o -name '*.sbt' \) -print 2>/dev/null
      fi
    )
  fi

  if [ "$count" -eq 0 ]; then
    exit 0
  fi
  if [ "$active" = "true" ]; then
    # Already blocked once in this stop sequence: allow the stop to avoid an infinite loop.
    exit 0
  fi

  shown=$(printf '%s' "$stale" | head -n 10)
  {
    printf 'Stop blocked by .claude/hooks/verify-tests-ran.sh: %s Scala/sbt source file(s) changed after the last recorded test run.\n' "$count"
    if [ -f "$marker" ]; then
      printf '  Test marker: %s is older than these changes.\n' "$MARKER_REL"
    else
      printf '  Test marker: %s does not exist (no recorded passing test run).\n' "$MARKER_REL"
    fi
    printf '  Changed sources (up to 10):\n%s\n' "$shown"
    printf '  What to do: run `sbt check` (format check, compile, tests, AWS-free contract tests; its markTestRun task refreshes the marker only when tests pass), fix every failure, then finish and report the exact test counts you observed.\n'
    if [ ! -f build.sbt ]; then
      printf '  No build.sbt exists yet, so sbt check cannot run. Do not create or touch the marker by hand (blocked by the floor). Finish with STATUS: BLOCKED-ON build-engineer (or FAILED) and state in TEST RESULTS that tests were not run.\n'
    fi
    printf '  If you cannot run the tests (for example, no Bash tool), say so explicitly in TEST RESULTS and quote this message as evidence.\n'
  } >&2
  exit 2
}

self_test() {
  local self="$0" tmp fails=0 total=0 out code
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-verify-selftest.XXXXXX") || { echo "SELF-TEST FAIL: mktemp" >&2; return 1; }
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  check() {
    # $1 expected exit, $2 description, $3 stop_hook_active (true/false), $4 optional stderr
    # substring, $5 optional project root (default: the fixture)
    out=$(printf '{"hook_event_name":"Stop","stop_hook_active":%s}' "$3" | CLAUDE_PROJECT_DIR="${5:-$tmp}" bash "$self" 2>&1 >/dev/null)
    code=$?
    total=$((total + 1))
    if [ "$code" = "$1" ] && { [ -z "${4:-}" ] || printf '%s' "$out" | grep -q -- "$4"; }; then
      printf 'PASS expect=%s got=%s  %s\n' "$1" "$code" "$2"
    else
      fails=$((fails + 1))
      printf 'FAIL expect=%s got=%s  %s\n' "$1" "$code" "$2"
      printf '     stderr: %s\n' "$(printf '%s' "$out" | head -3 | tr '\n' ' ')"
    fi
  }

  ( cd "$tmp" && git init -q . ) || { echo "SELF-TEST FAIL: git init" >&2; return 1; }
  mkdir -p "$tmp/.claude" "$tmp/modules/core/src/main/scala"
  printf 'readme\n' > "$tmp/README.md"
  check 2 "project root unreachable (fail closed)" false "cannot reach the project root" "$tmp/no-such-project-root"
  check 0 "project root unreachable with stop_hook_active=true (loop breaker)" true "" "$tmp/no-such-project-root"
  check 0 "no Scala source changed (README only)" false

  printf 'class Notes\n' > "$tmp/Notes.java"
  check 0 "only a non-Scala source changed" false

  printf 'object A\n' > "$tmp/modules/core/src/main/scala/A.scala"
  touch -t 202601010000 "$tmp/modules/core/src/main/scala/A.scala"
  check 2 "Scala change, marker missing, no build.sbt" false "BLOCKED-ON build-engineer"
  check 0 "same state with stop_hook_active=true (loop breaker)" true

  touch -t 202601010100 "$tmp/.claude/.last-test-run"
  check 0 "marker newer than the Scala change" false

  touch -t 202601010200 "$tmp/modules/core/src/main/scala/A.scala"
  check 2 "Scala change newer than marker" false "sbt check"

  touch -t 202601010300 "$tmp/.claude/.last-test-run"
  printf 'ThisBuild / scalaVersion := "3"\n' > "$tmp/build.sbt"
  touch -t 202601010400 "$tmp/build.sbt"
  check 2 "build.sbt newer than marker" false "older than these changes"

  touch -t 202601010500 "$tmp/.claude/.last-test-run"
  mkdir -p "$tmp/target/scala-3"
  printf 'object Gen\n' > "$tmp/target/scala-3/Gen.scala"
  check 0 "generated Scala under target/ ignored" false

  printf 'object B\n' > "$tmp/modules/core/src/main/scala/B.scala"
  ( cd "$tmp" && git add modules/core/src/main/scala/B.scala ) || true
  rm -f "$tmp/modules/core/src/main/scala/B.scala"
  touch -t 202601010600 "$tmp/modules/core/src/main/scala"
  check 2 "staged Scala file deleted after marker" false "Changed sources"

  echo "verify-tests-ran self-test: $((total - fails))/$total passed"
  [ "$fails" -eq 0 ]
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
hook_main
