#!/usr/bin/env bash
# validate-agent-files.sh - ScholarChain agent-constitution validator (see docs/agents.md).
#
# Checks:
#   agents    frontmatter (name, description, tools), filename == name, unique names, the
#             required agent set, forbidden TaskForge roles absent, no Agent/Task tool (hub and
#             spoke), reviewer fence (code-reviewer: Read, Grep, Glob only), refusal boundary
#             and report contract sections, every agent listed in docs/agents.md
#   hooks     every hook executable and passing bash -n; every hook referenced by
#             .claude/settings.json exists; every hook file is referenced; every hook that has a
#             --self-test mode passes it (guard-dangerous.sh, guard-paths.sh, and
#             verify-tests-ran.sh must have one)
#   json      .claude/settings.json and .mcp.json parse; floor rules present in settings;
#             no auto-approved Bash rule of the write-or-execute class (bare Bash, Bash(*), git
#             wildcards, jq, python, bash, shellcheck, git diff/log/show with arguments, sbt
#             other than check and Test/compile; "cmd *" and legacy "cmd:*" spellings);
#             guard-paths.sh registered for Read, Grep, Glob, and the write tools;
#             no secret-looking literals in .mcp.json; no .claude/settings.local.json
#   commands  every command names at least one existing agent (**agent-name**) and has a
#             "## Gates" section
#
# Usage:
#   validate-agent-files.sh          standalone; prints every check, exits 1 on any failure
#   validate-agent-files.sh --hook   PostToolUse hook mode: validates only after a write to a
#                                    constitutional path; exit 2 (stderr to the agent) on failure
#
# Compatible with bash 3.2. Requires jq. Never writes inside the repository. The hook self-tests
# it runs execute nothing they describe; verify-tests-ran.sh --self-test creates and removes a
# throwaway Git fixture under $TMPDIR.

set -u

REQUIRED_AGENTS="factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer documentation-engineer incident-responder dependency-updater"
FORBIDDEN_AGENTS="feature-implementer db-migrator"
REVIEWER_ALLOWED_TOOLS="Read Grep Glob"
SELFTEST_REQUIRED="guard-dangerous.sh guard-paths.sh verify-tests-ran.sh"
GUARD_PATHS_TOOLS="Read Grep Glob Write Edit MultiEdit NotebookEdit"

FAILS=0
CHECKS=0
OUT=""

pass() { CHECKS=$((CHECKS + 1)); OUT="${OUT}PASS  $1
"; }
fail() { CHECKS=$((CHECKS + 1)); FAILS=$((FAILS + 1)); OUT="${OUT}FAIL  $1
"; }

frontmatter() {
  # Print the YAML frontmatter block (between the first two --- lines) of $1.
  awk 'NR==1 && $0 != "---" { exit } NR==1 { next } $0 == "---" { exit } { print }' "$1"
}

fm_field() {
  # $1 file, $2 key: print the single-line value of key.
  frontmatter "$1" | awk -v k="$2" 'index($0, k ":") == 1 { sub("^" k ":[[:space:]]*", ""); print; exit }'
}

contains_word() {
  # $1 space-separated list, $2 word
  case " $1 " in *" $2 "*) return 0 ;; esac
  return 1
}

validate() {
  local root="$1" f name desc tools base names="" t tl
  cd "$root" || { fail "cannot cd to project root $root"; return; }

  if ! command -v jq >/dev/null 2>&1; then
    fail "jq is installed (required)"
    return
  fi

  # ---------- agents ----------
  for f in .claude/agents/*.md; do
    [ -f "$f" ] || { fail "agent files exist under .claude/agents/"; break; }
    base=$(basename "$f" .md)
    if [ "$(head -n 1 "$f")" != "---" ]; then fail "$f starts with YAML frontmatter"; continue; fi
    name=$(fm_field "$f" name)
    desc=$(fm_field "$f" description)
    tools=$(fm_field "$f" tools)
    if [ -n "$name" ]; then pass "$f has name ($name)"; else fail "$f has name"; fi
    if [ -n "$desc" ]; then pass "$f has description (${#desc} chars)"; else fail "$f has description"; fi
    if [ -n "$tools" ]; then pass "$f has explicit tools ($tools)"; else fail "$f has explicit tools (omitted tools inherit everything)"; fi
    if [ "$name" = "$base" ]; then pass "$f filename matches name"; else fail "$f filename matches name ($name)"; fi
    if contains_word "$names" "$name"; then fail "agent name $name is unique"; else names="$names $name"; fi
    tl=$(printf '%s' "$tools" | tr ',' ' ')
    for t in $tl; do
      case "$t" in
        Agent|Task) fail "$f grants no Agent/Task tool (hub and spoke): found $t" ;;
      esac
    done
    if grep -q '^## Refusal boundary' "$f"; then pass "$f has a refusal boundary"; else fail "$f has a refusal boundary"; fi
    if grep -q '^## Report contract' "$f" && grep -q 'STATUS: COMPLETE | BLOCKED-ON | FAILED' "$f"; then
      pass "$f has a structured report contract"
    else
      fail "$f has a structured report contract"
    fi
    if [ -f docs/agents.md ] && grep -q "\`$name\`" docs/agents.md; then
      pass "$name is listed in docs/agents.md"
    else
      fail "$name is listed in docs/agents.md"
    fi
    if contains_word "$FORBIDDEN_AGENTS" "$name"; then fail "$name is not a forbidden TaskForge role"; fi
  done
  for t in $REQUIRED_AGENTS; do
    if contains_word "$names" "$t"; then pass "required agent $t exists"; else fail "required agent $t exists"; fi
  done
  for t in $names; do
    if ! contains_word "$REQUIRED_AGENTS" "$t"; then fail "agent $t is in the required agent set (unlisted agent)"; fi
  done
  if [ -f .claude/agents/code-reviewer.md ]; then
    tl=$(fm_field .claude/agents/code-reviewer.md tools | tr ',' ' ')
    local bad=""
    for t in $tl; do
      contains_word "$REVIEWER_ALLOWED_TOOLS" "$t" || bad="$bad $t"
    done
    if [ -z "$bad" ]; then pass "reviewer fence: code-reviewer tools are read-only ($tl)"; else fail "reviewer fence: code-reviewer holds non-read tools:$bad"; fi
  fi

  # ---------- json ----------
  local s=.claude/settings.json m=.mcp.json
  if [ -f "$s" ] && jq empty "$s" >/dev/null 2>&1; then pass "$s parses as JSON"; else fail "$s parses as JSON"; fi
  if [ -f "$m" ] && jq empty "$m" >/dev/null 2>&1; then pass "$m parses as JSON"; else fail "$m parses as JSON"; fi
  if [ -e .claude/settings.local.json ]; then fail ".claude/settings.local.json does not exist (policy override)"; else pass ".claude/settings.local.json does not exist"; fi
  if [ -f "$s" ] && jq empty "$s" >/dev/null 2>&1; then
    if [ "$(jq -r '.permissions.disableBypassPermissionsMode // ""' "$s")" = "disable" ]; then pass "bypass permissions mode is disabled"; else fail "bypass permissions mode is disabled"; fi
    local rule
    for rule in 'Bash(terraform apply *)' 'Bash(terraform destroy *)' 'Bash(git push --force *)' 'Bash(git push -f *)' 'Bash(git push --force-with-lease *)' 'Read(**/.env)' 'Read(**/.env.*)' 'Read(~/.aws/**)' 'Edit(spec/**)' 'Bash(aws s3 rb *)' 'Bash(aws s3 rm *)' 'Bash(aws dynamodb delete-table *)' 'Bash(aws neptune delete-*)' 'Bash(aws neptune-graph delete-*)' 'Bash(aws s3vectors delete-index *)' 'Bash(aws kms schedule-key-deletion *)' 'Bash(aws emr terminate-clusters *)' 'Bash(aws ecr delete-repository *)'; do
      if jq -e --arg r "$rule" '.permissions.deny | index($r)' "$s" >/dev/null 2>&1; then pass "deny rule present: $rule"; else fail "deny rule present: $rule"; fi
    done
    # Auto-approved Bash rules must not be able to write files or run code through their
    # arguments (repair-03): no bare Bash, Bash(*), or git wildcard rule, no jq, python, bash,
    # sh, or shellcheck rule, no git diff/log/show rule that takes arguments, and no sbt rule
    # other than exactly sbt check and sbt Test/compile. Both the "cmd *" and the legacy
    # "cmd:*" rule spellings are checked.
    local broad
    broad=$(jq -r '.permissions.allow[]? | select((. == "Bash") or test("^Bash\\((\\*|git( |:)?\\*)") or (test("^Bash\\(((jq|python[0-9.]*|bash|sh|zsh|shellcheck)( |:|\\*|\\))|git (diff|log|show)( |:)|sbt( |:))") and . != "Bash(sbt check)" and . != "Bash(sbt Test/compile)"))' "$s" 2>/dev/null | tr '\n' ' ')
    if [ -z "$broad" ]; then pass "no auto-approved Bash rule of the write-or-execute class"; else fail "no auto-approved Bash rule of the write-or-execute class: $broad"; fi
    local gpm gt
    gpm=$(jq -r '[.hooks.PreToolUse[]? | select([.hooks[]?.command] | any(test("guard-paths[.]sh"))) | .matcher] | join("|")' "$s" 2>/dev/null)
    for gt in $GUARD_PATHS_TOOLS; do
      if printf '%s\n' "$gpm" | tr '|' '\n' | grep -qx "$gt"; then pass "guard-paths.sh is registered for $gt"; else fail "guard-paths.sh is registered for $gt (matcher: $gpm)"; fi
    done
    local refs ref
    refs=$(jq -r '[.hooks[]?[]?.hooks[]?.command] | .[]' "$s" 2>/dev/null | sed -n 's#.*\.claude/hooks/\([A-Za-z0-9_.-]*\.sh\).*#\1#p' | sort -u)
    for ref in $refs; do
      if [ -f ".claude/hooks/$ref" ]; then pass "referenced hook exists: $ref"; else fail "referenced hook exists: $ref"; fi
    done
    for f in .claude/hooks/*.sh; do
      [ -f "$f" ] || continue
      ref=$(basename "$f")
      if printf '%s\n' "$refs" | grep -qx "$ref"; then pass "hook is referenced by settings: $ref"; else fail "hook is referenced by settings: $ref (orphan)"; fi
    done
  fi
  if [ -f "$m" ] && jq empty "$m" >/dev/null 2>&1; then
    if grep -E -q '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|sk-ant-[A-Za-z0-9-]{10,}|aws_secret_access_key)' "$m"; then
      fail "$m contains no secret-looking literal"
    else
      pass "$m contains no secret-looking literal"
    fi
    local lit
    lit=$(jq -r '.mcpServers[]? | ((.env // {}) + (.headers // {})) | to_entries[] | select((.key|test("TOKEN|SECRET|KEY|PASSWORD|AUTHORIZATION";"i")) and ((.value|tostring)|test("\\$\\{")|not)) | .key' "$m" 2>/dev/null)
    if [ -z "$lit" ]; then pass "$m credential fields reference environment variables only"; else fail "$m credential fields reference environment variables only: $lit"; fi
  fi

  # ---------- hooks ----------
  for f in .claude/hooks/*.sh; do
    [ -f "$f" ] || { fail "hook scripts exist"; break; }
    if [ -x "$f" ]; then pass "$f is executable"; else fail "$f is executable"; fi
    if bash -n "$f" 2>/dev/null; then pass "$f passes bash -n"; else fail "$f passes bash -n"; fi
  done

  # ---------- hook self-tests ----------
  local hb st_out st_code
  for f in .claude/hooks/*.sh; do
    [ -f "$f" ] || continue
    hb=$(basename "$f")
    [ "$hb" = "validate-agent-files.sh" ] && continue
    if grep -q -- '"--self-test"' "$f"; then
      st_out=$(bash "$f" --self-test </dev/null 2>&1)
      st_code=$?
      if [ "$st_code" = 0 ]; then
        pass "$f --self-test passes ($(printf '%s\n' "$st_out" | tail -n 1))"
      else
        fail "$f --self-test passes (exit $st_code; $(printf '%s\n' "$st_out" | grep -c '^FAIL') failing vectors; $(printf '%s\n' "$st_out" | tail -n 1))"
      fi
    elif contains_word "$SELFTEST_REQUIRED" "$hb"; then
      fail "$f has a --self-test mode (required)"
    else
      pass "$f has no --self-test mode (not required)"
    fi
  done

  # ---------- commands ----------
  local refs_a a any
  for f in .claude/commands/*.md; do
    [ -f "$f" ] || { fail "command files exist"; break; }
    refs_a=$(grep -o '\*\*[a-z][a-z-]*\*\*' "$f" | tr -d '*' | sort -u)
    any=0
    for a in $refs_a; do
      if contains_word "$REQUIRED_AGENTS" "$a"; then
        any=1
        if [ -f ".claude/agents/$a.md" ]; then pass "$f names existing agent $a"; else fail "$f names existing agent $a"; fi
      fi
    done
    if [ "$any" = 1 ]; then pass "$f names a responsible agent"; else fail "$f names a responsible agent (**agent-name**)"; fi
    if grep -q '^## Gates' "$f"; then pass "$f states its gates"; else fail "$f states its gates (## Gates)"; fi
    if frontmatter "$f" | grep -q '^allowed-tools:'; then fail "$f grants no allowed-tools (would widen permissions)"; else pass "$f grants no allowed-tools"; fi
  done
}

project_root() {
  local r="${CLAUDE_PROJECT_DIR:-}"
  if [ -z "$r" ]; then r=$(cd "$(dirname "$0")/../.." && pwd -P); fi
  printf '%s' "$r"
}

if [ "${1:-}" = "--hook" ]; then
  payload=$(cat 2>/dev/null || true)
  command -v jq >/dev/null 2>&1 || exit 0
  file=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null) || exit 0
  case "$file" in
    *.claude/agents/*|*.claude/hooks/*|*.claude/commands/*|*.claude/settings.json|*.mcp.json|*CLAUDE.md|*docs/agents.md) ;;
    *) exit 0 ;;
  esac
  validate "$(project_root)"
  if [ "$FAILS" -gt 0 ]; then
    {
      printf 'validate-agent-files.sh: %s of %s constitution checks FAILED after writing %s\n' "$FAILS" "$CHECKS" "$file"
      printf '%s' "$OUT" | grep '^FAIL'
      printf 'What to do: fix the failing checks in the constitutional files (factory-engineer), then rerun .claude/hooks/validate-agent-files.sh.\n'
    } >&2
    exit 2
  fi
  exit 0
fi

validate "$(project_root)"
printf '%s' "$OUT"
printf 'validate-agent-files: %s/%s checks passed\n' "$((CHECKS - FAILS))" "$CHECKS"
[ "$FAILS" -eq 0 ] || exit 1
exit 0
