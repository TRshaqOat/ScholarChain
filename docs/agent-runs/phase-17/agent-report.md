# Work order 1

STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 17 session 1, work order 1 (docs/agent-runs/phase-17/prompt.md): README.md, docs/api-reference.md, docs/architecture.md. Ponytail ON.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/architecture.md (83 content lines)
- /Users/achyutpatel/Developer/Cloud/ScholarChain/docs/api-reference.md (141 content lines; the Read tool reports 142 with the trailing newline)
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/README.md (419 content lines). The Write tool reported "updated", not "created". If a README was committed before, it has been replaced in full. Check this with `git diff README.md`.
FILES DELETED: none

REQUIRED ITEMS (all done):
- README, spec "README requirements" in spec order:
  1. title and identity (ruling 4)
  2. problem paragraph
  3. architecture summary, linking docs/architecture.md
  4. agentic workflow summary
  5. local software with tool-versions.md 2026-11-21 versions
  6. AWS prerequisites
  7. environment variable names only, covering: scholarchain.conf SCHOLARCHAIN_*, the API key name, build, scripts, dataset scripts, .mcp.json, and user scope PONYTAIL_DEFAULT_MODE, PONYTAIL_SUBAGENT_MATCHER, SCHOLARCHAIN_CONTACT_EMAIL
  8. sbt fmt, sbt check, sbt packageJobs, scripts/check.sh
  9. the bounded Hadoop sample, exactly as in JOBS.md with `--code-version "$(git rev-parse HEAD)"`, then Spark PipelineJob
  10. terraform fmt, init, validate, then scripts/plan.sh
  11. script table with who runs each (docs/agents.md item 8, scripts/README.md)
  12. deployment and smoke test in the corrected window order from scripts/README.md "The flow"
  13. API examples with the key on stdin (`curl --config -`, as smoke-test.sh does), with excerpts from the archived smoke bodies, labelled as archived
  14. run-experiment.sh with the 4 manifests, plus the derive and summarize commands
  15. "Final report and video" with a link to reports/final-report.md and the exact line "Demonstration video: link added at submission"
  16. cleanup (human only, absolute --plan-file, Dec 15, 2026)
  17. known limitations: P14-D8, verifier D18 to D20 and pending tests, Neptune-bound graph-evidence-api, file-backed vectors in E, open Neptune record count, runtime not available (ruling 8), plus identity recall, unconverged communities, D-10 cuts, HTTP-only ALB and image scan, single runs and one labeller, experiment A gap
  18. deliverable index D01 to D20 (D14 is operations/releases/, D19 is the video line, D20 is operations/cleanup/)
- Agent catalog: 18 rows, 8 columns (name, purpose, owned paths, tool fence, prerequisites, a "Use the <agent> agent to ..." instruction, verification command or gate, report fields). The standard report fields are listed once above the table; each row's last column lists that agent's extra fields.
- Subsection titled exactly "Reproducing the agentic workflow":
  - all 11 steps: clone and tag v1.0-submission; Claude Code 2.1.329; `claude`; /agents, /memory, /hooks, /mcp; the validator and the guard self-test with their observed expected lines; the `echo 'terraform destroy'` probe described as an expected block; a fresh session per phase; comparison with the archive
  - a prerequisite verification table
  - the no-AWS replay of Phase 15 WO1: branch at aa30f5a, paste the work order, sbt check, compare with the "Work order 1" report and `git diff aa30f5a c542806 -- modules/rag` (with a note on repair-01 part C)
- Supporting sections:
  - what .claude/ and docs/agent-runs/ contain
  - the orchestrator is the main session, not an agent file
  - merged phases with their stubs
  - ruling 7 explanations (P01, E01/E03/E06, C03, F07, O01, O02/E05, K03, V01, A01, DATA-08, DATA-03, FACTORY-09/10) and K01 per ruling 14, together
  - AI-assistance provenance from the phase-15 gate
- docs/api-reference.md covers:
  - all 10 routes with method, path, parameters, request and response fields
  - X-Api-Key, Idempotency-Key, X-Request-Id and X-Trace-Id
  - the public /healthz and /readyz
  - a 22-code error table with HTTP status and retryable flag from `ErrorCode` in Enums.scala; 14 codes have example files and 8 are marked "no example file"
  - an archived window 3 example
- docs/architecture.md: two fenced mermaid diagrams (build-time and runtime), each followed by a per-component table.

COMMANDS REPLAYED (the two ruling 3 commands only):
- `.claude/hooks/validate-agent-files.sh`: every line PASS; last line "validate-agent-files: 231/231 checks passed". This run includes guard-dangerous 304/304, guard-paths 142/142 and verify-tests-ran 11/11 self-tests.
- `.claude/hooks/guard-dangerous.sh --self-test`: every case PASS; last line "guard-dangerous self-test: 304/304 passed".

COMMANDS DOCUMENTED BUT NOT REPLAYED (each with its source):
- From CLAUDE.md "Commands" and scripts/README.md (to be replayed by the human in Step F): `sbt fmt`, `sbt check`, `sbt packageJobs`, `scripts/check.sh`.
- Bounded sample:
  - the Hadoop `sbt "hadoop/Test/runMain scholarchain.hadoop.Pipeline ..."`, from modules/hadoop/JOBS.md "Running locally"
  - the Spark `sbt "sparkAnalytics/runMain scholarchain.spark.PipelineJob ..."`, from modules/spark/JOBS.md "Running locally"
- Terraform:
  - `terraform fmt -check -recursive infra/terraform` and `terraform validate`, from the scripts/README.md check.sh gates
  - `terraform -chdir=infra/terraform/envs/course init -backend=false -input=false`, from infra/terraform/README.md "Human actions"
- From scripts/README.md (contracts and "The flow"): plan.sh, deploy.sh (with --resume), package.sh, run-bounded-pipeline.sh, load-stores.sh, smoke-test.sh, rollback.sh, archive-evidence.sh, run-experiment.sh, cleanup.sh. Also `aws login`, which is the flow's wording.
- `curl --config -` API examples: the req() function and usage text in scripts/smoke-test.sh. The responses are excerpts of operations/releases/smoke-20261119T235713Z/{query,evidence}.json.
- `python3 -I experiments/analysis/derive-tables.py` (and `--self-test`), and the summarize-exp-e.py commands: experiments/derived/README.md and docs/evaluation.md section 7.1.
- `SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g tests/test`: tests/TESTS.md.
- `scripts/run-experiment.sh --manifest <m> --dry-run`: scripts/README.md.
- `git clone`, `git checkout v1.0-submission`, `git checkout -b ... aa30f5a`, `git diff aa30f5a c542806 -- modules/rag`: Phase 17 work order 1.
- `echo 'terraform destroy'` probe: spec/architect-decisions.md D-11. Described as an expected block, not run.
- `aws sts get-caller-identity`: deploy-engineer law 2.
- Version checks (`claude --version`, `java -version`, `sbt --version`, `terraform version`, `aws --version`, jq, python3, git, uv): standard client commands. The expected values come from docs/agent-runs/tool-versions.md, which does not record the commands themselves.
- Claude Code slash commands /agents, /memory, /hooks, /mcp, /clear: standard client commands with no repository source.

EVIDENCE LINKS (claim -> evidence):
- 845 passed, 0 failed, 10 pending at d406c66 -> docs/agent-runs/phase-15/human-gate.md (check-sh-gate.txt)
- window 3 plan, release, digest and run -> operations/releases/release-20261119T233215Z-c5428063732a.json and Phase 15 ruling 10
- smoke PASS and the query and evidence excerpts -> operations/releases/smoke-20261119T235713Z.json and that run's folder
- 89 destroyed, 43 kept -> operations/cleanup/cleanup-20261120T041658Z-runtime/record.json and retained.tsv
- limitations and metrics -> docs/evaluation.md sections 1, 2, 3.3, 4.1, 5.2 to 5.8, 7, and "Neptune record count"
- costs -> phase-15 human-gate
- image scan and HTTP ALB -> phase-14 and phase-12 gates
- routes, errors and fields -> Services.scala, Models.scala, Http.scala, Enums.scala, openapi.yaml, openapi/examples/*

LINK CHECK:
- Internal links: I opened every target I could and checked the example JSON files one by one. All exist except the following.
  - Not yet existing (later Phase 17 work orders create them): docs/data-lineage.md, docs/threat-model.md, docs/service-limits.md, docs/runbook.md, docs/demo-script.md, reports/final-report.md (WO2). The release manifest operations/releases/release-manifest-final.md (WO5) is written as code text, not a link.
  - Not verified: labels/README.md (agents may not open labels/), plus the directory links to prompts/, load-tests/ and experiments/results/.
- In-page anchors: checked by hand against GitHub's slug rules.
- External links: not checked, because no fetch tool is allowed in this work order. The only one is https://github.com/TRshaqOat/ScholarChain, which is private until Nov 25.

UNIMPLEMENTED OR OPTIONAL COMPONENTS STATED: no OpenSearch (D-06), approximate betweenness, experiment C, Step Functions, GitHub Actions and a security scanner beyond terraform validate (D-10). Experiment B is replaced by size scaling, and live LLM adjudication is not in the evaluated decisions.

COMMANDS RUN: the two replays above only. No other Bash, git, sbt, terraform, aws, python or MCP call.
TEST RESULTS: none run (documentation only; there are no Scala changes).
VALIDATION RESULTS: validator 231/231, guard self-test 304/304.

ASSUMPTIONS:
- Ruling 7 gives only the cause of E06. For E01 and E03 the README says the records stay as committed and explains the commit.txt convention from docs/agent-runs/README.md and the phase-15 gate. I did not invent the finding texts.
- A01 and V01 are stated as ruling 7 records them. I did not open the Spark sources or load-tests/exp-d-query-load.py to verify them.

RISKS:
- The first README Write was refused by the cc-plugin-privacy and cc-plugin-sec-default plugins. The refusal said the file held the account's email address next to what looked like a client identity. The README needs the student email (ruling 4). I removed the "polite-pool User-Agent" wording next to SCHOLARCHAIN_CONTACT_EMAIL, and the second Write passed. The human should check that keeping the email in the README is intended.
- The 6 forward links fail any link check until work order 2 lands.
- Nothing in the new text contains the account ID, an account ARN or the ALB hostname. Bucket names use `<account-id>`.

MANUAL ACTION REQUIRED:
- Human: replace the D19 and "Final report and video" video line at submission.
- Human: confirm the tag name v1.0-submission.
- Human: if a README existed before, check its replacement with `git diff README.md`.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: link check after work order 2, command replay (Step F fresh-clone logs), evidence review, code-reviewer APPROVE.

Deviations: no slash-command source in the repository (see above). The api-reference covers 8 error codes that have no example file; their status and retryable flag come from ErrorCode. Skipped: nothing that was required.
