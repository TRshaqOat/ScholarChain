#!/usr/bin/env bash
# guard-paths.sh - ScholarChain deterministic floor (see docs/agents.md).
#
# PreToolUse hook, matcher "Read|Grep|Glob|Write|Edit|MultiEdit|NotebookEdit".
#
# Read:  blocks secret and credential paths (.env*, secrets/, private keys, ~/.aws/,
#        ~/.ssh/, credential files, Terraform state and plan files).
# Grep/Glob: blocks a search whose path input (Grep and Glob), glob input (Grep), or pattern
#        input (Glob) targets a secret: a secret file or directory named literally, or a path
#        component that can match a secret name as a shell pattern (.e*v, .en?, .[e]nv, *.pem,
#        secret*, **/id_*, {x,.env}). A component made only of * and ? (*, **) targets nothing
#        in particular and passes, and a generic *.json does not count as targeting
#        credentials.json. Grep's content regex (its pattern input) is not a path and is not
#        checked. A recursive search of an ordinary directory with no glob is not blocked;
#        whether it opens hidden or secret files depends on the search tool and on the Read
#        deny rules in .claude/settings.json.
# Write/Edit/MultiEdit/NotebookEdit:
#   1. Always blocked for everyone: secret paths, sensitive home files (~/.claude/, ~/.aws/,
#      shell init files, Git credentials), spec/**, labels/**, .git/**,
#      .claude/settings.local.json, .claude/.last-test-run, and the human records in
#      docs/agent-runs/ (README.md, */prompt.md, */human-gate.md, */commit.txt).
#   2. Subagent calls (hook input carries agent_id/agent_type, documented by the Claude Code
#      client for hooks that fire inside a subagent): the path must be inside that agent's
#      rows of the ownership map in docs/agents.md. docs/agent-runs/** is never writable.
#      Outside the repository, only temporary directories are writable.
#   3. Calls with no agent identity (no agent_id and no agent_type: the main thread, or a
#      subagent whose identity is missing; the hook cannot tell them apart) get the most
#      restrictive posture: only agent-report.md, review-report.md, test-report.md, or
#      repair-NN.md directly inside a not-yet-accepted docs/agent-runs/phase-NN/ folder (no
#      commit.txt), plus temporary directories outside the repository. Every other write is
#      denied and delegated to its owning agent.
#   4. agent_id without agent_type: denied everywhere in the repository; temporary
#      directories only outside it. agent_type without agent_id: checked against that
#      agent's rows like a normal subagent call.
#
# docs/agents.md is authoritative. OWNERSHIP below mirrors it; any divergence is a
# constitutional defect for factory-engineer.
#
# Exit 2 blocks with an actionable message; exit 0 allows. Fails closed on writes when jq is
# missing or the project root cannot be determined. Fails closed (exit 2) for every tool when
# the payload is empty, is not valid JSON, is not a single JSON object, or has a missing, empty,
# or non-string tool_name. Compatible with bash 3.2. Requires jq.
#
# Usage:
#   guard-paths.sh              hook mode (payload JSON on stdin)
#   guard-paths.sh --self-test  run the built-in vectors (no file is read or written)

set -u

AGENTS="factory-engineer build-engineer dependency-updater domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer documentation-engineer incident-responder"

TOOL="unknown"
SHOWN_PATH=""

block() {
  # $1 category, $2 reason, $3 remedy
  {
    printf 'BLOCKED by .claude/hooks/guard-paths.sh [%s]\n' "$1"
    printf '  Tool: %s\n' "$TOOL"
    printf '  Path: %s\n' "$SHOWN_PATH"
    printf '  Reason: %s\n' "$2"
    printf '  What to do: %s\n' "$3"
    printf '  Ownership is defined in docs/agents.md. Do not write through another tool or path to evade this; report STATUS: BLOCKED-ON the owner named above.\n'
  } >&2
  exit 2
}

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

normpath() {
  # Collapse ".", "..", and repeated slashes in an absolute path.
  local p="$1" out="" seg
  local IFS='/'
  set -f
  for seg in $p; do
    case "$seg" in
      ''|.) ;;
      ..) out="${out%/*}" ;;
      *) out="$out/$seg" ;;
    esac
  done
  set +f
  if [ -z "$out" ]; then out="/"; fi
  printf '%s\n' "$out"
}

resolve_path() {
  # $1 path (absolute, relative, or ~), $2 base directory. Prints an absolute, normalized path
  # whose deepest existing ancestor has symlinks resolved.
  local p="$1" base="$2" head tail real
  case "$p" in
    "~") p="$HOME" ;;
    "~/"*) p="$HOME/${p#\~/}" ;;
    /*) ;;
    *) p="$base/$p" ;;
  esac
  p=$(normpath "$p")
  head="$p"
  tail=""
  while [ -n "$head" ] && [ ! -d "$head" ]; do
    tail="/${head##*/}$tail"
    head="${head%/*}"
  done
  if [ -z "$head" ]; then head="/"; fi
  real=$(cd "$head" 2>/dev/null && pwd -P) || real="$head"
  if [ "$real" = "/" ]; then real=""; fi
  printf '%s%s\n' "$real" "$tail"
}

# owned_by AGENT REL  -> 0 when REL (lower-case, repository-relative) is in AGENT's rows.
owned_by() {
  local a="$1" r="$2"
  case "$a" in
    factory-engineer)
      case "$r" in
        claude.md|docs/agents.md|.claude/agents/*|.claude/hooks/*|.claude/commands/*|.claude/settings.json|.mcp.json) return 0 ;;
      esac ;;
    build-engineer)
      case "$r" in
        build.sbt|project/*|.scalafmt.conf|.gitignore|.sbtopts|.jvmopts|.dockerignore|docker-compose*.yml|docker-compose*.yaml|compose*.yml|compose*.yaml|packaging/*) return 0 ;;
      esac ;;
    dependency-updater)
      # Value-level edits inside the version ledger and project/build.properties, project/plugins.sbt.
      case "$r" in
        project/*) return 0 ;;
      esac ;;
    domain-engineer)
      case "$r" in modules/core/*|config/*) return 0 ;; esac ;;
    dataset-engineer)
      case "$r" in datasets/*) return 0 ;; esac ;;
    hadoop-engineer)
      case "$r" in modules/hadoop/*) return 0 ;; esac ;;
    entity-resolution-engineer)
      case "$r" in modules/entity-resolution/*) return 0 ;; esac ;;
    spark-engineer)
      case "$r" in modules/spark/*) return 0 ;; esac ;;
    storage-engineer)
      case "$r" in modules/storage/*) return 0 ;; esac ;;
    rag-engineer)
      case "$r" in modules/rag/*|prompts/*) return 0 ;; esac ;;
    service-engineer)
      case "$r" in modules/services/*) return 0 ;; esac ;;
    test-engineer)
      case "$r" in tests/*) return 0 ;; esac ;;
    code-reviewer)
      return 1 ;;
    infra-engineer)
      case "$r" in infra/*|scripts/*|.github/*) return 0 ;; esac ;;
    deploy-engineer)
      case "$r" in operations/*) return 0 ;; esac ;;
    experiment-engineer)
      case "$r" in experiments/*|load-tests/*) return 0 ;; esac ;;
    documentation-engineer)
      case "$r" in
        docs/agents.md|docs/agent-runs|docs/agent-runs/*|docs/incidents|docs/incidents/*) return 1 ;;
        readme.md|reports/*|docs/*) return 0 ;;
      esac ;;
    incident-responder)
      case "$r" in docs/incidents/*) return 0 ;; esac ;;
  esac
  return 1
}

owner_of() {
  local r="$1" a
  case "$r" in
    spec|spec/*|labels|labels/*|docs/agent-runs|docs/agent-runs/*) printf 'human architect'; return ;;
  esac
  for a in $AGENTS; do
    if [ "$a" = "dependency-updater" ]; then continue; fi   # value-level co-owner of project/; build-engineer owns the files
    if owned_by "$a" "$r"; then printf '%s' "$a"; return; fi
  done
  printf 'no owner (ambiguous ownership: escalate to the human architect; factory-engineer proposes a ratifiable row)'
}

is_secret_name() {
  # $1 lower-case file or directory name (one path component).
  case "$1" in
    .env|.env.*|.envrc|*.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|id_rsa*|id_dsa*|id_ecdsa*|id_ed25519*|.netrc|.git-credentials|credentials|credentials.json|*.tfstate|*.tfstate.backup|*.tfplan|.npmrc|.pypirc)
      return 0 ;;
  esac
  return 1
}

is_secret() {
  # $1 lower-case absolute path, $2 lower-case home
  local p="$1" h="$2"
  if is_secret_name "${p##*/}"; then return 0; fi
  case "$p" in
    */secrets/*|*/secret/*|*/.aws/*|*/.ssh/*|*/terraform.tfstate.d/*|"$h"/.config/gh/hosts.yml|"$h"/.docker/config.json|"$h"/.claude/.credentials.json)
      return 0 ;;
  esac
  return 1
}

is_sensitive_home_write() {
  local p="$1" h="$2"
  case "$p" in
    "$h"/.claude|"$h"/.claude/*|"$h"/.aws|"$h"/.aws/*|"$h"/.ssh|"$h"/.ssh/*|"$h"/.config/gh/*|"$h"/.docker/*|"$h"/.gitconfig|"$h"/.git-credentials|"$h"/.netrc|"$h"/.zshrc|"$h"/.zprofile|"$h"/.zshenv|"$h"/.zlogin|"$h"/.bashrc|"$h"/.bash_profile|"$h"/.bash_login|"$h"/.profile)
      return 0 ;;
  esac
  return 1
}

is_temp() {
  local p="$1" t
  case "$p" in
    /tmp/*|/private/tmp/*|/var/folders/*|/private/var/folders/*) return 0 ;;
  esac
  if [ -n "${TMPDIR:-}" ]; then
    t=$(lower "$(cd "$TMPDIR" 2>/dev/null && pwd -P)")
    if [ -n "$t" ]; then
      case "$p" in "$t"/*) return 0 ;; esac
    fi
  fi
  return 1
}

# Grep and Glob (repair-03). Sample secret file and directory names that a search path component
# must not be able to match. A component targets a secret when it is a secret name itself or
# when one of these samples matches it as a shell pattern.
SECRET_SAMPLES=".env .env.local .env.production .envrc x.pem x.key x.p12 x.pfx x.jks x.keystore id_rsa id_dsa id_ecdsa id_ed25519 .netrc .git-credentials credentials credentials.json .credentials.json terraform.tfstate terraform.tfstate.backup x.tfplan .npmrc .pypirc secrets secret .aws .ssh .docker terraform.tfstate.d"
BRACE_RX='^(.*)[{]([^{}]*)[}](.*)$'

comp_targets_secret() {
  # $1 one lower-case path component, possibly with glob characters.
  local c="$1" s
  [ -n "$c" ] || return 1
  if is_secret_name "$c"; then return 0; fi
  case "$c" in
    *[!*?]*) ;;
    *) return 1 ;; # only * and ?: matches everything and targets no secret in particular
  esac
  for s in $SECRET_SAMPLES; do
    # shellcheck disable=SC2053
    if [[ $s == $c ]]; then
      # A generic *.json pattern does not count as targeting credentials.json.
      case "$s" in *.json) if [[ zz9q.json == $c ]]; then continue; fi ;; esac
      return 0
    fi
  done
  return 1
}

path_targets_secret() {
  # $1 lower-case path or glob. 0 when any component can name a secret. Brace alternatives are
  # expanded first; more than 4 brace groups or 16 commas fail closed (treated as a secret target).
  local p="$1" c pre alts post rest alt nb nc
  nb=${p//[!\{]/}
  nc=${p//[!,]/}
  if [ "${#nb}" -gt 4 ] || [ "${#nc}" -gt 16 ]; then return 0; fi
  if [[ $p =~ $BRACE_RX ]]; then
    pre=${BASH_REMATCH[1]}
    alts=${BASH_REMATCH[2]}
    post=${BASH_REMATCH[3]}
    rest="$alts,"
    while [ -n "$rest" ]; do
      alt=${rest%%,*}
      rest=${rest#*,}
      if path_targets_secret "$pre$alt$post"; then return 0; fi
    done
    return 1
  fi
  while [ -n "$p" ]; do
    c=${p%%/*}
    if [ "$c" = "$p" ]; then p=""; else p=${p#*/}; fi
    if comp_targets_secret "$c"; then return 0; fi
  done
  return 1
}

guard_search() {
  # $1 payload. Grep and Glob are read-only: block when the path input, or the Grep glob or Glob
  # pattern input, targets a secret. Exits.
  local payload="$1" path glob cwd home lhome abs item
  path=$(printf '%s' "$payload" | jq -r '.tool_input.path // ""')
  if [ "$TOOL" = "Grep" ]; then
    glob=$(printf '%s' "$payload" | jq -r '.tool_input.glob // ""')
  else
    glob=$(printf '%s' "$payload" | jq -r '.tool_input.pattern // ""')
  fi
  cwd=$(printf '%s' "$payload" | jq -r '.cwd // ""')
  SHOWN_PATH="path=${path:-<default>} glob/pattern=${glob:-<none>}"
  home=$(cd "$HOME" 2>/dev/null && pwd -P) || home="$HOME"
  lhome=$(lower "$home")
  for item in "$path" "$glob"; do
    [ -n "$item" ] || continue
    if path_targets_secret "$(lower "$item")"; then
      block "secret-search" "the search path, glob, or pattern can reach a secret or credential file" "Secrets are never read by agents (CLAUDE.md, Security invariants). Narrow the search to non-secret paths and globs. Owner: human architect."
    fi
  done
  if [ -n "$path" ]; then
    abs=$(resolve_path "$path" "${cwd:-$(pwd -P)}")
    if is_secret "$(lower "$abs")" "$lhome" || path_targets_secret "$(lower "$abs")"; then
      block "secret-search" "the search path resolves to a secret or credential location" "Secrets are never read by agents (CLAUDE.md, Security invariants). Narrow the search to non-secret paths. Owner: human architect."
    fi
  fi
  exit 0
}

guard_main() {
  local payload
  payload=$(cat)
  if ! command -v jq >/dev/null 2>&1; then
    block "guard-unavailable" "jq is not installed" "Install jq (required by .claude/hooks). File tool calls are blocked until then (fail closed)."
  fi
  # Fail closed on malformed hook input (repair-05 N4; mirrors guard-dangerous.sh).
  if ! [[ $payload =~ [^[:space:]] ]]; then
    block "guard-empty-payload" "hook payload is empty" "Retry the tool call; if this persists, report it to factory-engineer. The guard fails closed on an empty payload."
  fi
  if ! TOOL=$(printf '%s' "$payload" | jq -r 'if type == "object" and ((.tool_name // "") | type) == "string" then (.tool_name // "") else error("not a hook payload object") end' 2>/dev/null); then
    TOOL="unknown"
    block "guard-unreadable-payload" "hook payload is not a valid JSON object" "Retry the tool call; if this persists, report it to factory-engineer."
  fi
  case "$TOOL" in
    *$'\n'*)
      TOOL="unknown"
      block "guard-unreadable-payload" "hook payload holds more than one JSON document" "Retry the tool call; if this persists, report it to factory-engineer."
      ;;
  esac
  if [ -z "$TOOL" ]; then
    TOOL="unknown"
    block "guard-no-tool-name" "hook payload has no tool_name" "Retry the tool call; if this persists, report it to factory-engineer. The guard fails closed when it cannot tell which tool is being called."
  fi
  case "$TOOL" in
    Grep|Glob) guard_search "$payload" ;;
    Read|Write|Edit|MultiEdit|NotebookEdit) ;;
    *) exit 0 ;;
  esac

  local raw agent_id agent_type cwd root home abs lp lroot lhome rel orel caller is_write
  raw=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // .tool_input.notebook_path // .tool_input.path // ""')
  agent_id=$(printf '%s' "$payload" | jq -r '.agent_id // ""')
  agent_type=$(printf '%s' "$payload" | jq -r '.agent_type // ""')
  cwd=$(printf '%s' "$payload" | jq -r '.cwd // ""')
  SHOWN_PATH="$raw"
  if [ "$TOOL" = "Read" ]; then is_write=0; else is_write=1; fi
  if [ -z "$raw" ]; then
    if [ "$is_write" = 1 ]; then block "no-path" "the tool input names no file path" "Retry with an explicit file path."; fi
    exit 0
  fi

  root="${CLAUDE_PROJECT_DIR:-}"
  if [ -z "$root" ] && [ -n "$cwd" ]; then
    root=$(git -C "$cwd" rev-parse --show-toplevel 2>/dev/null) || root="$cwd"
  fi
  root=$(cd "$root" 2>/dev/null && pwd -P) || root=""
  if [ -z "$root" ]; then
    if [ "$is_write" = 1 ]; then block "no-project-root" "cannot determine the project root" "Start Claude Code from the repository root."; fi
    root="/nonexistent-project-root"
  fi
  home=$(cd "$HOME" 2>/dev/null && pwd -P) || home="$HOME"

  abs=$(resolve_path "$raw" "${cwd:-$root}")
  lp=$(lower "$abs")
  lroot=$(lower "$root")
  lhome=$(lower "$home")

  # Secrets: never read, never written.
  if is_secret "$lp" "$lhome"; then
    block "secret-path" "secret or credential file" "Secrets are never read or written by agents (CLAUDE.md, Security invariants). Use documented environment-variable names and Secrets Manager references. Owner: human architect."
  fi
  if [ "$is_write" = 0 ]; then exit 0; fi

  if is_sensitive_home_write "$lp" "$lhome"; then
    block "sensitive-home-file" "user-level configuration, credentials, or shell init file" "Agents never modify user-level Claude settings, credentials, or shell init files. Owner: human architect."
  fi

  if [ -n "$agent_id" ] || [ -n "$agent_type" ]; then caller="$agent_type"; else caller=""; fi

  rel=""
  orel=""
  if [ "$lp" = "$lroot" ]; then
    rel="."
  else
    case "$lp" in
      "$lroot"/*) rel="${lp#"$lroot"/}"; orel="${abs:$((${#root} + 1))}" ;;
    esac
  fi

  if [ -z "$rel" ]; then
    # Outside the repository: temporary directories only, for every caller. A call without
    # agent identity cannot be told apart from a subagent, so it gets the same limit.
    if is_temp "$lp"; then exit 0; fi
    block "outside-repository" "write outside the repository and outside temporary directories" "Write only to your owned paths in docs/agents.md (or a temporary directory for scratch files)."
  fi

  # Always-protected repository paths.
  case "$rel" in
    .|spec|spec/*|labels|labels/*|.git|.git/*|.claude/settings.local.json|.claude/.last-test-run|docs/agent-runs/readme.md|docs/agent-runs/*/prompt.md|docs/agent-runs/*/human-gate.md|docs/agent-runs/*/commit.txt)
      block "protected-path" "human-owned, generated, or policy-override path (owner: $(owner_of "$rel"))" "spec/**, labels/**, and human records in docs/agent-runs/ are written by the human architect only; .claude/.last-test-run is written only by sbt markTestRun; .claude/settings.local.json must not exist." ;;
  esac

  # Phase evidence: only the orchestrator, only an open phase folder.
  case "$rel" in
    docs/agent-runs|docs/agent-runs/*)
      if [ -n "$agent_id" ] || [ -n "$agent_type" ]; then
        block "agent-runs-subagent" "subagents never write phase evidence" "Return your final report; the orchestrator assembles it into docs/agent-runs/."
      fi
      local phase
      phase="${orel#docs/agent-runs/}"
      case "$phase" in
        */*) phase="${phase%%/*}" ;;
        *) block "agent-runs-root" "files directly under docs/agent-runs/ are human-owned" "Write agent reports into the current phase folder docs/agent-runs/phase-NN/." ;;
      esac
      if [ -e "$root/docs/agent-runs/$phase/commit.txt" ]; then
        block "agent-runs-accepted-phase" "phase $phase has an accepted commit (commit.txt); its evidence is immutable" "Record new evidence in the current phase folder."
      fi
      # Only assembled agent reports, directly inside a phase-NN folder (docs/agents.md).
      case "$rel" in
        docs/agent-runs/phase-*/agent-report.md|docs/agent-runs/phase-*/review-report.md|docs/agent-runs/phase-*/test-report.md|docs/agent-runs/phase-*/repair-[0-9][0-9].md)
          case "${rel#docs/agent-runs/}" in
            */*/*) ;;
            *) exit 0 ;;
          esac ;;
      esac
      block "agent-runs-not-a-report" "only agent-report.md, review-report.md, test-report.md, and repair-NN.md directly inside an open phase-NN folder may be written" "Assemble the agent's final report into one of those files; everything else under docs/agent-runs/ is human-owned." ;;
  esac

  if [ -n "$caller" ]; then
    if owned_by "$caller" "$rel"; then exit 0; fi
    block "not-owner" "$caller does not own this path; owner: $(owner_of "$rel")" "Decline the edit and return STATUS: BLOCKED-ON $(owner_of "$rel")."
  fi
  if [ -n "$agent_id" ]; then
    block "unknown-subagent" "subagent call without an agent type" "Only agents listed in docs/agents.md may write repository files."
  fi

  # Main thread (orchestrator): delegate.
  block "orchestrator-inline-write" "the orchestrator does not perform specialist work inline; owner: $(owner_of "$rel")" "Delegate to the owning agent (for example: Use the $(owner_of "$rel") agent to ...)."
}

self_test() {
  local self="$0" root fails=0 total=0 line expect tool agent path payload got err ti nl=$'\n'
  if ! command -v jq >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: jq is not installed" >&2
    return 1
  fi
  root=$(cd "$(dirname "$self")/../.." && pwd -P)
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    expect=${line%%@@*}; line=${line#*@@}
    tool=${line%%@@*}; line=${line#*@@}
    agent=${line%%@@*}; path=${line#*@@}
    if [ "$tool" = "RAW" ]; then
      # repair-05 N4: the last column is the raw hook payload (<EMPTY> = an empty payload,
      # <NL> = a newline).
      if [ "$path" = "<EMPTY>" ]; then payload=""; else payload=${path//<NL>/$nl}; fi
    elif [ "$tool" = "Grep" ] || [ "$tool" = "Glob" ]; then
      # Search tools: the last column is the tool_input JSON object itself.
      ti=$path
    else
      ti=$(jq -cn --arg p "$path" '{file_path:$p}')
    fi
    if [ "$tool" = "RAW" ]; then
      : # payload already set above
    elif [ "$agent" = "-" ]; then
      # No agent_id and no agent_type (main thread, or a subagent whose identity is missing).
      payload=$(jq -cn --arg t "$tool" --argjson ti "$ti" --arg c "$root" '{hook_event_name:"PreToolUse",tool_name:$t,cwd:$c,tool_input:$ti}')
    elif [ "$agent" = "?" ]; then
      # agent_id present, agent_type missing.
      payload=$(jq -cn --arg t "$tool" --argjson ti "$ti" --arg c "$root" '{hook_event_name:"PreToolUse",tool_name:$t,cwd:$c,agent_id:"self-test-agent",tool_input:$ti}')
    elif [ "${agent#+}" != "$agent" ]; then
      # agent_type present, agent_id missing.
      payload=$(jq -cn --arg t "$tool" --argjson ti "$ti" --arg c "$root" --arg a "${agent#+}" '{hook_event_name:"PreToolUse",tool_name:$t,cwd:$c,agent_type:$a,tool_input:$ti}')
    else
      payload=$(jq -cn --arg t "$tool" --argjson ti "$ti" --arg c "$root" --arg a "$agent" '{hook_event_name:"PreToolUse",tool_name:$t,cwd:$c,agent_id:"self-test-agent",agent_type:$a,tool_input:$ti}')
    fi
    err=$(printf '%s' "$payload" | CLAUDE_PROJECT_DIR="$root" bash "$self" 2>&1 >/dev/null)
    got=$?
    total=$((total + 1))
    if [ "$got" = "$expect" ]; then
      printf 'PASS expect=%s got=%s  %-12s %-28s %s\n' "$expect" "$got" "$tool" "$agent" "$path"
    else
      fails=$((fails + 1))
      printf 'FAIL expect=%s got=%s  %-12s %-28s %s\n' "$expect" "$got" "$tool" "$agent" "$path"
      if [ -n "$err" ]; then printf '     stderr: %s\n' "$(printf '%s' "$err" | head -3 | tr '\n' ' ')"; fi
    fi
  done <<'VECTORS'
# expected-exit@@tool@@agent-type ("-" = main thread)@@path
2@@Write@@-@@spec/ScholarChain-spec.md
2@@Edit@@domain-engineer@@spec/architect-decisions.md
2@@Edit@@domain-engineer@@modules/core/../../spec/x.md
0@@Edit@@domain-engineer@@modules/core/src/main/scala/scholarchain/core/WorkRecord.scala
0@@Write@@domain-engineer@@config/application.conf
2@@Write@@test-engineer@@modules/core/src/main/scala/scholarchain/core/WorkRecord.scala
2@@Write@@test-engineer@@modules/core/src/test/scala/scholarchain/core/WorkRecordSuite.scala
0@@Write@@test-engineer@@tests/adversarial/src/test/scala/DblpXmlAdversarialSuite.scala
2@@Edit@@code-reviewer@@modules/rag/src/main/scala/Verifier.scala
2@@Write@@code-reviewer@@docs/review.md
2@@Edit@@domain-engineer@@CLAUDE.md
2@@Edit@@build-engineer@@.claude/settings.json
0@@Edit@@factory-engineer@@.claude/agents/build-engineer.md
0@@Edit@@factory-engineer@@CLAUDE.md
0@@Edit@@factory-engineer@@docs/agents.md
2@@Write@@factory-engineer@@build.sbt
2@@Write@@factory-engineer@@.claude/settings.local.json
2@@Write@@build-engineer@@.claude/.last-test-run
0@@Write@@build-engineer@@build.sbt
2@@Write@@dependency-updater@@build.sbt
0@@Edit@@dependency-updater@@project/Versions.scala
0@@Write@@hadoop-engineer@@modules/hadoop/src/main/scala/DblpRecordMapper.scala
2@@Write@@hadoop-engineer@@modules/entity-resolution/src/main/scala/BlockingKeys.scala
0@@Write@@entity-resolution-engineer@@modules/entity-resolution/src/main/scala/BlockingKeys.scala
0@@Write@@spark-engineer@@modules/spark/src/main/scala/CoauthorGraph.scala
0@@Write@@storage-engineer@@modules/storage/src/main/scala/NeptuneLoader.scala
0@@Write@@rag-engineer@@prompts/runtime/answer-synthesis-v1.md
0@@Write@@service-engineer@@modules/services/src/main/scala/QueryRoutes.scala
0@@Write@@infra-engineer@@infra/terraform/modules/foundation/main.tf
0@@Write@@infra-engineer@@scripts/deploy.sh
2@@Write@@infra-engineer@@modules/services/src/main/scala/QueryRoutes.scala
0@@Write@@deploy-engineer@@operations/deployments/dev-abc123.md
2@@Write@@deploy-engineer@@scripts/deploy.sh
0@@Write@@experiment-engineer@@experiments/manifests/query-load-v1.yaml
0@@Write@@documentation-engineer@@docs/architecture.md
0@@Write@@documentation-engineer@@README.md
2@@Write@@documentation-engineer@@docs/agents.md
2@@Write@@documentation-engineer@@docs/incidents/2026-01-01-x.md
2@@Write@@documentation-engineer@@docs/agent-runs/phase-99/agent-report.md
0@@Write@@incident-responder@@docs/incidents/2026-01-01-smoke-failure.md
0@@Write@@dataset-engineer@@datasets/dataset-manifest.yaml
2@@Write@@general-purpose@@README.md
2@@Write@@factory-engineer@@labels/gold-entity-matches.csv
2@@Write@@factory-engineer@@docs/agent-runs/phase-01/prompt.md
2@@Write@@-@@docs/agent-runs/phase-01/human-gate.md
2@@Write@@-@@docs/agent-runs/phase-00/commit.txt
2@@Write@@-@@docs/agent-runs/README.md
2@@Write@@-@@docs/agent-runs/phase-00/agent-report.md
0@@Write@@-@@docs/agent-runs/phase-99/agent-report.md
2@@Write@@-@@modules/core/src/main/scala/WorkRecord.scala
2@@Write@@-@@CLAUDE.md
2@@Write@@-@@.git/hooks/pre-commit
0@@Write@@-@@/tmp/scholarchain-guard-paths-probe.txt
0@@Write@@domain-engineer@@/tmp/scholarchain-guard-paths-probe.txt
2@@Write@@domain-engineer@@/usr/local/scholarchain-probe.txt
2@@Write@@domain-engineer@@~/.zshrc
2@@Write@@-@@~/.claude/settings.json
2@@Read@@-@@.env
2@@Read@@code-reviewer@@config/.env.local
2@@Read@@-@@~/.aws/credentials
2@@Read@@-@@~/.aws/sso/cache/token.json
2@@Read@@-@@secrets/neptune.json
2@@Read@@-@@infra/terraform/environments/dev/terraform.tfstate
2@@Read@@-@@~/.ssh/id_ed25519
0@@Read@@code-reviewer@@modules/core/src/main/scala/WorkRecord.scala
0@@Read@@-@@spec/ScholarChain-spec.md
0@@Read@@-@@docs/agent-runs/phase-00/human-gate.md
# repair-01: missing agent identity. "-" = no agent_id and no agent_type; "?" = agent_id only;
# "+name" = agent_type only. Writes outside owned paths must be denied in every case.
2@@Write@@-@@build.sbt
2@@Write@@-@@tests/adversarial/X.scala
2@@Write@@-@@/usr/local/scholarchain-probe.txt
2@@Write@@-@@~/scholarchain-probe.txt
2@@Write@@-@@docs/agent-runs/phase-99/notes.md
2@@Write@@-@@docs/agent-runs/phase-99/sub/agent-report.md
0@@Write@@-@@docs/agent-runs/phase-99/review-report.md
0@@Write@@-@@docs/agent-runs/phase-99/repair-01.md
2@@Write@@?@@modules/core/src/main/scala/WorkRecord.scala
2@@Write@@?@@docs/agent-runs/phase-99/agent-report.md
2@@Write@@?@@/usr/local/scholarchain-probe.txt
0@@Write@@?@@/tmp/scholarchain-guard-paths-probe.txt
2@@Write@@+domain-engineer@@build.sbt
0@@Write@@+domain-engineer@@modules/core/src/main/scala/WorkRecord.scala
2@@Write@@+domain-engineer@@docs/agent-runs/phase-99/agent-report.md
# repair-03 finding 4: Grep and Glob. The last column is the tool_input JSON object.
# Searches whose path, glob, or pattern targets a secret are blocked.
2@@Grep@@-@@{"pattern":"KEY","path":".env"}
2@@Grep@@code-reviewer@@{"pattern":"KEY","path":"config/.env.local","output_mode":"content"}
2@@Grep@@-@@{"pattern":"token","path":"~/.aws"}
2@@Grep@@-@@{"pattern":"aws_secret","path":"~/.aws/credentials"}
2@@Grep@@-@@{"pattern":"x","path":"secrets"}
2@@Grep@@-@@{"pattern":"x","path":"secrets/neptune.json"}
2@@Grep@@-@@{"pattern":"x","path":"~/.ssh"}
2@@Grep@@-@@{"pattern":"x","path":"infra/terraform/environments/dev/terraform.tfstate"}
2@@Grep@@-@@{"pattern":"KEY","path":".","glob":".env*"}
2@@Grep@@-@@{"pattern":"KEY","glob":"**/.env"}
2@@Grep@@-@@{"pattern":"KEY","glob":".e*v"}
2@@Grep@@-@@{"pattern":"KEY","glob":".en?"}
2@@Grep@@-@@{"pattern":"KEY","glob":".[e]nv"}
2@@Grep@@-@@{"pattern":"KEY","glob":"{.env,x.json}"}
2@@Grep@@-@@{"pattern":"KEY","glob":"*.{pem,txt}"}
2@@Grep@@-@@{"pattern":"BEGIN","glob":"*.pem"}
2@@Grep@@-@@{"pattern":"x","glob":"**/*.tfstate"}
2@@Grep@@-@@{"pattern":"x","glob":"**/secret*/**"}
2@@Grep@@-@@{"pattern":"x","glob":"*credentials*"}
2@@Grep@@-@@{"pattern":"x","glob":"{a,b}{c,d}{e,f}{g,h}{i,j}"}
2@@Glob@@-@@{"pattern":"**/.env*"}
2@@Glob@@-@@{"pattern":".e*"}
2@@Glob@@-@@{"pattern":"~/.aws/**"}
2@@Glob@@-@@{"pattern":"**/secrets/**"}
2@@Glob@@-@@{"pattern":"**/id_*"}
2@@Glob@@-@@{"pattern":"*","path":"~/.aws"}
2@@Glob@@-@@{"pattern":"**/*.key"}
2@@Glob@@domain-engineer@@{"pattern":"**/.ENV.local"}
2@@Glob@@-@@{"pattern":"**/{x,.e*}"}
2@@Glob@@-@@{"pattern":"**/.docker/*"}
# repair-03 finding 4: ordinary Grep and Glob searches are allowed.
0@@Grep@@-@@{"pattern":"sys.env","path":"modules"}
0@@Grep@@-@@{"pattern":"TODO","path":".","glob":"*.scala"}
0@@Grep@@code-reviewer@@{"pattern":"tools:","path":".claude/agents","glob":"*.md"}
0@@Grep@@-@@{"pattern":"guard","path":".claude/hooks","glob":"*.sh"}
0@@Grep@@-@@{"pattern":"RATIFIED","path":"spec/ScholarChain-spec.md"}
0@@Grep@@-@@{"pattern":"version","glob":"**/*.json"}
0@@Grep@@-@@{"pattern":"x","glob":"*.{json,yaml}"}
0@@Grep@@-@@{"pattern":".env"}
0@@Glob@@-@@{"pattern":"**/*.scala"}
0@@Glob@@-@@{"pattern":".claude/agents/*.md"}
0@@Glob@@-@@{"pattern":"**/*"}
0@@Glob@@-@@{"pattern":"*"}
0@@Glob@@-@@{"pattern":"**/*.tf","path":"infra"}
0@@Glob@@-@@{"pattern":"docs/**/*.md"}
0@@Glob@@-@@{"pattern":"**/*.{scala,sbt}"}
# repair-05 N4: malformed hook input fails closed. Tool RAW: the last column is the raw payload.
2@@RAW@@-@@<EMPTY>
2@@RAW@@-@@<NL>
2@@RAW@@-@@{}
2@@RAW@@-@@{"tool_input":{"file_path":"CLAUDE.md"}}
2@@RAW@@-@@{"tool_name":"","tool_input":{"file_path":"CLAUDE.md"}}
2@@RAW@@-@@{"tool_name":null,"tool_input":{"file_path":"CLAUDE.md"}}
2@@RAW@@-@@{"tool_name":7,"tool_input":{"file_path":"CLAUDE.md"}}
2@@RAW@@-@@null
2@@RAW@@-@@[]
2@@RAW@@-@@not json
2@@RAW@@-@@{"tool_name":"Read","tool_input":{"file_path":"CLAUDE.md"}}<NL>{"tool_name":"Write"}
2@@RAW@@-@@{"tool_name":"Write","tool_input":{"file_path":"spec/x.md"}}
# repair-05 N4: well-formed payloads are unaffected.
0@@RAW@@-@@{"tool_name":"Read","tool_input":{"file_path":"CLAUDE.md"}}
0@@RAW@@-@@{"tool_name":"Read","tool_input":{"file_path":"CLAUDE.md"}}<NL>
0@@RAW@@-@@{"tool_name":"Glob","tool_input":{"pattern":"**/*.scala"}}
VECTORS
  echo "guard-paths self-test: $((total - fails))/$total passed"
  [ "$fails" -eq 0 ]
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
guard_main
