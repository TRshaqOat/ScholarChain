#!/usr/bin/env bash
# Non-cloud validation of a checkout (spec Phase 13 item 1). No AWS call. Runs every gate, then
# exits 1 if any failed.
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/check.sh [--help]

Gates, in order (all run; the exit code is 1 if any failed):
  json               jq parses .claude/settings.json and .mcp.json
  constitution       .claude/hooks/validate-agent-files.sh (agent files and hook self-tests)
  bash-syntax        bash -n on .claude/hooks/*.sh, scripts/**/*.sh and datasets/scripts/*.sh
  shellcheck         shellcheck -S error on scripts/**/*.sh (SKIPPED when not installed)
  dataset-validation bash datasets/scripts/validate.sh (manifest, samples, checksums, self-tests;
                     no network)
  terraform-fmt      terraform fmt -check -recursive infra/terraform
  terraform-validate terraform init -backend=false -lockfile=readonly, then validate
  sbt-check          SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g -batch check
                     (scalafmt check, Test/compile, every test, markTestRun)

Needs: jq, bash, python3 (dataset validation), terraform 1.16.6, sbt, a JDK. Network: only the first
terraform init (provider download) and sbt dependency resolution. No AWS credentials are read.
EOF
}

case "${1-}" in
  --help | -h) usage; exit 0 ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac

need jq python3 terraform sbt
FAILED=""

gate() {
  local name="$1"
  shift
  step "$name"
  if "$@"; then ok "$name"; else
    printf '==> FAIL %s\n' "$name" >&2
    FAILED="$FAILED $name"
  fi
}

json_ok() { jq -e . "$SC_ROOT/.claude/settings.json" "$SC_ROOT/.mcp.json" >/dev/null; }

constitution_ok() { "$SC_ROOT/.claude/hooks/validate-agent-files.sh"; }

bash_syntax_ok() {
  local f rc=0
  for f in "$SC_ROOT"/.claude/hooks/*.sh "$SC_ROOT"/scripts/*.sh "$SC_ROOT"/scripts/*/*.sh "$SC_ROOT"/datasets/scripts/*.sh; do
    if bash -n "$f"; then info "ok   ${f#"$SC_ROOT"/}"; else info "FAIL ${f#"$SC_ROOT"/}"; rc=1; fi
  done
  return "$rc"
}

shellcheck_ok() {
  if ! command -v shellcheck >/dev/null 2>&1; then
    info "SKIPPED: shellcheck not installed"
    return 0
  fi
  shellcheck -x -S error "$SC_ROOT"/scripts/*.sh "$SC_ROOT"/scripts/*/*.sh
}

# Through bash, as the manifest's validation_commands do: the datasets/scripts files are not executable.
datasets_ok() { bash "$SC_ROOT/datasets/scripts/validate.sh"; }

tf_fmt_ok() { terraform fmt -check -recursive "$SC_ROOT/infra/terraform"; }

tf_validate_ok() {
  tf_require_version
  tf init -backend=false -input=false -lockfile=readonly >/dev/null && tf validate
}

sbt_check_ok() { sbt_run check; }

gate json json_ok
gate constitution constitution_ok
gate bash-syntax bash_syntax_ok
gate shellcheck shellcheck_ok
gate dataset-validation datasets_ok
gate terraform-fmt tf_fmt_ok
gate terraform-validate tf_validate_ok
gate sbt-check sbt_check_ok

if [ -n "$FAILED" ]; then
  die "check failed:$FAILED" 1
fi
ok "check: every gate passed"
