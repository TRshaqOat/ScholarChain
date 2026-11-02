# Tool versions (human-recorded 2026-11-02)

```text
ProductName:		macOS
ProductVersion:		27.0.1
BuildVersion:		26A434
arm64
openjdk version "17.0.21" 2026-10-20
OpenJDK Runtime Environment Temurin-17.0.21+9 (build 17.0.21+9)
OpenJDK 64-Bit Server VM Temurin-17.0.21+9 (build 17.0.21+9, mixed mode, sharing)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
Note: local JDK 17.0.21 (released 2026-10-20) is a developer tool, not a pinned project dependency; D-09 applies to build and container pins.
java install: Temurin .pkg from api.adoptium.net
sbt launcher: 1.13.0
sbt install: GitHub release sbt-1.13.0.tgz
git version 2.54.0 (Apple Git-157)
jq jq-1.7.1-apple
uv 0.13.4 (7456b60bc 2026-10-20 aarch64-apple-darwin)
VS Code Claude extension: anthropic.claude-code@2.1.314
terraform: not yet installed
aws-cli/2.38.3 Python/3.14.6 Darwin/27.0.0 script-exe/arm64
```

## Update 2026-11-02: Apple Silicon correction and Ponytail

```text
uname -m: arm64  (Apple Silicon; earlier handoff note saying Intel was wrong)
    java.version = 17.0.21
    os.arch = aarch64
java install: Temurin jdk-17.0.21+9 aarch64 .pkg from api.adoptium.net
node: v26.3.0 (arm64), released 2026-06-01
claude CLI: 2.1.178 (Claude Code)
VS Code Claude extension: 2.1.314 (darwin-arm64)
ponytail: v5.3.2, commit 16352ac229eb08a9598dcae48fe9f2b342567d86, released 2026-10-16, user scope, local marketplace ~/Developer/tools/ponytail-v5.3.2
PONYTAIL_DEFAULT_MODE=full
PONYTAIL_SUBAGENT_MATCHER=^(build-engineer|domain-engineer|dataset-engineer|hadoop-engineer|entity-resolution-engineer|spark-engineer|storage-engineer|rag-engineer|service-engineer|infra-engineer|experiment-engineer|documentation-engineer|dependency-updater)$
guard probe after ponytail install: echo 'terraform destroy' -> BLOCKED by guard-dangerous.sh [terraform-apply-destroy]
```

## Update 2026-11-02: Ponytail matcher fix

```text
PONYTAIL_DEFAULT_MODE and PONYTAIL_SUBAGENT_MATCHER moved into the env block of ~/.claude/settings.json (user scope), because ~/.zshrc exports did not reach the running VS Code session.
Verified: Bash tool sees both variables; code-reviewer reports no Ponytail ruleset; build-engineer reports 'PONYTAIL MODE ACTIVE — level: full'.
```
