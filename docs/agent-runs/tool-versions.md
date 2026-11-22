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

## Terraform (installed 2026-11-05)

```text
Terraform 1.16.6, darwin_arm64, released 2026-10-14 (22 days old at install; D-09 two-week rule met).
Source: https://releases.hashicorp.com/terraform/1.16.6/terraform_1.16.6_darwin_arm64.zip
sha256 verified against terraform_1.16.6_SHA256SUMS: 85e1bc78ab45bdc5f82a7bac3929e37d072834a584d22583a2498dc67bb624cd (OK)
Installed to ~/.local/bin/terraform. `terraform version` reports v1.16.6 on darwin_arm64.
Newer 1.17.1 deliberately not used (too recent under D-09).
```

## Update 2026-11-21 (Phase 17 version record)

```text
claude CLI: 2.1.329 (Claude Code) at ~/.local/bin/claude (updated from 2.1.178 on 2026-11-19 to run the course autograder)
VS Code Claude extension: 2.1.314 (darwin-arm64)
Claude Code model: not pinned in .claude/settings.json or in any agent frontmatter; sessions used the model selected in the client
Ponytail: v5.3.2, user-scope plugin (optional for replay; code-reviewer, test-engineer, factory-engineer, deploy-engineer and incident-responder are excluded)
openjdk version "17.0.21" 2026-10-20 (Temurin, aarch64)
sbt launcher: 1.13.0 (project/build.properties pins sbt.version=1.13.0)
Scala: 3.3.7 (project/Dependencies.scala; scala-library 2.13.16)
Terraform v1.16.6 darwin_arm64; provider hashicorp/aws = 6.28.0 (lock file committed)
aws-cli/2.38.3 Python/3.14.6 Darwin/27.2.0 script-exe/arm64
node v26.3.0
Python 3.13.5
git version 2.54.0 (Apple Git-157)
jq-1.7.1-apple
uv 0.13.4 (recorded 2026-11-02, not rechecked)
Runtime models (Amazon Bedrock, us-east-2): us.anthropic.claude-haiku-4-5-20251001-v1:0, amazon.titan-embed-text-v2:0
MCP servers (pinned in .mcp.json): aws-api 1.5.6, aws-docs 1.2.2, terraform 1.0.18 (with mcp 1.30.0), github remote endpoint
```
