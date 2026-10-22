#!/usr/bin/env bash
# format-scala.sh - ScholarChain deterministic floor (see docs/agents.md).
#
# PostToolUse hook, matcher "Write|Edit|MultiEdit". After a write to a Scala or sbt file
# (*.scala, *.sc, *.sbt) inside the project, formats that one file with the scalafmt CLI and
# the repository's .scalafmt.conf. Formatting is a convenience, never a blocker: the hook
# always exits 0 and does nothing when no Scala file changed, when the file is outside the
# project, when .scalafmt.conf does not exist yet, or when the scalafmt CLI is not installed.
# The authoritative format gate is `sbt check` (format check), which runs regardless.
#
# Compatible with bash 3.2. Uses jq when available.

set -u

payload=$(cat 2>/dev/null || true)
command -v jq >/dev/null 2>&1 || exit 0

file=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null) || exit 0
[ -n "$file" ] || exit 0

case "$file" in
  *.scala|*.sc|*.sbt) ;;
  *) exit 0 ;;
esac

[ -f "$file" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$root" ]; then
  root=$(git -C "$(dirname "$file")" rev-parse --show-toplevel 2>/dev/null) || exit 0
fi
root=$(cd "$root" 2>/dev/null && pwd -P) || exit 0
dir=$(cd "$(dirname "$file")" 2>/dev/null && pwd -P) || exit 0
case "$dir/" in
  "$root"/*) ;;
  *) exit 0 ;;
esac

conf="$root/.scalafmt.conf"
[ -f "$conf" ] || exit 0

if command -v scalafmt >/dev/null 2>&1; then
  scalafmt --non-interactive --quiet --config "$conf" "$file" >/dev/null 2>&1 || true
fi
exit 0
