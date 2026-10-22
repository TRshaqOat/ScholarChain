Use the factory-engineer agent to create the complete ScholarChain agent system from the authority matrix in the course project specification.

Create:

1. CLAUDE.md

- Identify ScholarChain as a Scala 3, Hadoop, Spark, AWS, Graph-RAG project.
- Define module dependency boundaries.
- Define sbt check as the repository definition of done.
- State the one-owner-per-artifact rule.
- Require feature tests for every behavior change.
- Require provenance for every data transformation and runtime claim.
- Require secrets only through Secrets Manager or approved local placeholders.
- Require all AWS infrastructure through Terraform.
- Forbid direct cloud-console mutation as an implementation path.
- Import docs/agents.md.

2. docs/agents.md

- Transcribe the complete artifact ownership table for all required agents.
- Add a lifecycle-to-agent routing table.
- Add escalation rules for constitutional changes, data destruction, security changes, Terraform apply, deployment, expensive scale changes, and ambiguous ownership.
- State that subagents do not invoke one another and that all routing passes through the orchestrator.

3. All remaining .claude/agents/\*.md files

- Use precise routing descriptions in the vocabulary of this project.
- Use the narrowest practical tools field.
- Give code-reviewer no Edit or Write tool.
- Give infra-engineer authoring and read-only cloud inspection tools but no apply or live deployment authority.
- Give deploy-engineer execution authority only through approved scripts and approved environments.
- Give test-engineer write access only to test territories.
- Require each agent to decline and route out-of-scope work.
- Include a structured report contract.

4. .claude/settings.json and hooks

- PostToolUse formatter for Scala writes.
- PreToolUse dangerous-command guard.
- Stop hook requiring current tests after source changes.
- Agent-file validation hook or script.
- Explicit allow and deny lists.
- Deny reads of secrets and .env files.
- Deny terraform apply and destroy.
- Deny force push.
- Deny destructive S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, and EMR commands.

5. .mcp.json

- GitHub access.
- AWS API inspection with no unrestricted write authority.
- Terraform documentation or provider inspection.
- AWS documentation access where available.
- Document every server's trust boundary and environment variables.

6. .claude/commands

- deploy, rollback, incident, run-pipeline, and experiment commands.
- Every command must name the responsible agent and gates.

7. docs/agent-runs/

- docs/agent-runs/README.md already exists and is human-authored. Do not create or modify it.
- In docs/agents.md, declare docs/agent-runs/\*\* as human-owned evidence that the orchestrator may assemble agent reports into, and that no agent may rewrite.

Do not create build files or application source.

Validate every JSON file with a parser, every shell script with bash -n, all hook executable bits, all agent frontmatter, unique agent names, ownership completeness, routing collisions, routing orphans, and reviewer tool fences.

Present the complete diff, per-file authority justification, audit results, validation output, and post-ratification probe instructions. Stop for human ratification.

Additional architect constraints for this phase:

- The authority matrix is in spec/ScholarChain-spec.md, section "Agent authority and ownership matrix".
- Declare spec/\*\* human-owned and read-only for all agents in docs/agents.md.
- Apply spec/architect-decisions.md section 4.
- The reference repository is available read-only at /Users/achyutpatel/Developer/Cloud/AgenticScalaAppTutorial. Adapt its control patterns; do not copy TaskForge-specific content.
- Do not create feature-implementer or db-migrator; they are TaskForge roles with no ScholarChain equivalent. Their responsibilities are covered by the domain, hadoop, entity-resolution, spark, storage, rag, and service engineers.
- The AWS MCP server must be read-only and select credentials by profile name through an environment variable. Commit no secret values.
- Deny reads of ~/.aws/ credential and cache files in addition to .env and secret paths.
- Keep all hook scripts compatible with bash 3.2 (the macOS default) and use jq for JSON parsing.
- verify-tests-ran.sh must exit cleanly when no Scala source has changed. When a Scala source has changed and the test marker is missing or older than the change, it must block with an actionable message, even before the sbt build exists.
- Do not rely on PreToolUse hook input identifying the calling subagent unless you verify that field exists; enforce path ownership through agent instructions plus path-pattern deny rules.
- Post-ratification probe 1 targets domain-engineer instead of feature-implementer, which is not in the required agent list.

## Continuation instruction

Resume the phase-01 work order with the factory-engineer agent.

- Rewrite .mcp.json completely; do not append to the partial file.
- Create the five .claude/commands files: deploy, rollback, incident, run-pipeline, experiment.
- Make .claude/hooks/validate-agent-files.sh executable.
- Confirm .claude/agents/ contains exactly 18 files including factory-engineer, with no feature-implementer or db-migrator.
- Run every validation listed in the work order and show the actual output.
- Present the complete final report: full diff, per-file authority justification, audit results, validation output, and post-ratification probe instructions.
  Stop for human ratification.
