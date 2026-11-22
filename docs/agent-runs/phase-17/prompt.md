# Phase 17 prompt: documentation, final review and release (spec Phase 17)

## Human rulings 1 to 12 (Nov 21, 2026)

1. **Scope.** The spec's Phase 17 work order (`spec/ScholarChain-spec.md`, section "Phase 17"), together with "README requirements", "Required deliverables" (D01 to D20), "Agent submission and reproducibility requirements" and "Submission logistics". Documentation is written from committed evidence. No new features. Files other than documentation change only where a work order names them: the dataset manifest's license fields (work order 4), a residual-risk note in `docs/agents.md` (work order 3), and the release manifest (work order 5). Merged phases under D-10: 7+8, 12+13, 15+16. `docs/agent-runs/phase-08/`, `phase-13/` and `phase-16/` each hold a README stub that points to the merged folder (human, committed with this file).

2. **Final test run.** The spec's final test-engineer run is replaced by the human's fresh-clone `sbt clean check` and `scripts/check.sh` on the committed tree (Step F), because D-10 runs test-engineer once (Phase 11). This is a scope deviation, recorded in the Phase 17 gate.

3. **Command replay.** documentation-engineer's tool fence includes Bash. In Phase 17 it runs exactly two commands, both auto-allowed in `.claude/settings.json` and both free of side effects: `.claude/hooks/validate-agent-files.sh` and `.claude/hooks/guard-dangerous.sh --self-test`. It runs nothing else:
   - no script under `scripts/`, not even `--help`;
   - no sbt, terraform, aws, git or python;
   - no Bedrock call and no MCP call, except as work order 2 allows.

   Every other documented command comes from `scripts/README.md`, the usage text inside each script, the module documents, or committed evidence, and the document names that source. The human replays the build, test, check and bounded local sample commands in Step F and stores the logs in `docs/agent-runs/phase-17/evidence/fresh-clone/`. The agent's report lists, as "not replayed", every documented command that it did not replay itself. No result may be invented (spec rule).

4. **Identity.** Student: Achyut Patel, apate673@uic.edu. Repository: https://github.com/TRshaqOat/ScholarChain (private until Nov 25, 2026, then public).

5. **Demo script.** Before session 2, the human saves the text of the Claude Doc "ScholarChain Demo Recording Script" to `docs/agent-runs/phase-17/inputs/demo-script-source.md` and commits it. documentation-engineer adapts it into `docs/demo-script.md` and states there that it was drafted by Claude (web chat) and reviewed by the human. Commands keep their exact text.

6. **Release manifest facts (work order 5).** The manifest describes the last deployed release (cloud window 3), using committed records only.

   | Item | Value | Source |
   | --- | --- | --- |
   | Release commit | `c5428063732af5f7a5279c215dc2a52b89f64f16` | `operations/releases/release-20261119T233215Z-c5428063732a.json` |
   | Image tag | `deef29516bf395ac2b551beb93710b6a48a937a8` | release record (window 3 reused the window 2 image) |
   | Image digest | `sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea` | release record; same digest on query-api, graph-evidence-api and worker (task definitions `:2`) |
   | Plan | sha256 `978ce3059dc6b039e7c1bd9361c896838ea568dea9b68164ae0ff2a02e4b0bb6`, counts 89/0/0 | `operations/plans/20261119T173346Z-c5428063732a.md` and `-APPROVED.txt` |
   | Dataset snapshots | DBLP 2026-10-03; enrichment snapshots as listed | `datasets/dataset-manifest.yaml` |
   | Served runs | graph and items `run-1d6a86491812ff94887270c1ab885e69`, vectors `run-b28edbd40f932f0342c05d5b6f57b46c`, window summary `run-6fbad498beb0d8f570df67665fb97544` | `infra/terraform/envs/course/course.tfvars` |
   | Runtime run | `run-c9f26a9ecfe83c96526d50d79d99fd67` | release record |
   | Prompt versions | `planner-v2`, `synthesis-v3` | `modules/core/src/main/resources/scholarchain.conf` |
   | Verifier version | the latest in section 12 | `modules/rag/RUNTIME.md` |
   | Model IDs | from configuration | `scholarchain.conf` and `modules/rag/RUNTIME.md` |
   | Tests at `d406c66` | 845 passed, 0 failed, 10 pending | `docs/agent-runs/phase-15/evidence/gate/check-sh-gate.txt` |
   | Artifact checksums | jars, EMR step script, Docker context | `operations/releases/package-deef29516bf395ac2b551beb93710b6a48a937a8.json` |
   | Smoke test | PASS | `operations/releases/smoke-20261119T235713Z.json` |
   | Cleanup | 89 destroyed, 43 base resources kept | `operations/cleanup/cleanup-20261120T041658Z-runtime/` |

   Correction: the Nov 21 handoff named `synthesis-v2`, but the configuration says `synthesis-v3`, and the configuration rules. The final submission commit and tag are recorded in the Phase 17 gate and in the submission, not in the manifest, because the manifest is committed before them.

7. **Autograder findings: dispositions.**
   - **P01:** fixed by the stubs (ruling 1).
   - **E01, E03, E06:** the historical records stay as committed, and the README explains them. E06 was caused by `phase_commit`, which wrote the gate commit's own hash into `commit.txt`. From the Phase 15 gate on, `commit.txt` is written by hand and names the last reviewed and checked commit. `docs/agent-runs/README.md` is updated by the human in this commit.
   - **C03:** no change. The validator check that rejects tutorial-only roles (`feature-implementer`, `db-migrator`) comes from the Phase 1 work order, and its comment and message accurately name the reference tutorial's TaskForge example. The README explains this.
   - **F07:** no change. `.mcp.json` holds `Bearer ${GITHUB_PERSONAL_ACCESS_TOKEN}`, an environment-variable reference with no secret value, which is what the spec asks for. The README explains this.
   - **O01:** the orchestrator is the main Claude Code session, with no agent file. README.
   - **O02 and E05 (ownership):** `docs/agent-runs/**`, `spec/` and `labels/` are human-owned. dependency-updater is a value-level co-owner of `project/` with build-engineer. infra-engineer authors scripts, which deploy-engineer or the human runs. README.
   - **K03:** `course.tfvars` holds no secrets. README.
   - **V01:** model IDs and storage constants come from versioned configuration. The ARN in `load-tests/exp-d-query-load.py` is a self-test vector using the documentation example account `111122223333`. README.
   - **A01:** every Spark `.collect()` is bounded and carries a comment saying by what. The README names the files: `JobRun.scala`, `Communities.scala`, `CommunityPathJob.scala`, `BenchmarkPathJob.scala`.
   - **DATA-08:** `scripts/check.sh` runs `bash datasets/scripts/validate.sh`. README.
   - **DATA-03:** work order 4.
   - **FACTORY-09 and FACTORY-10:** the README agent catalog and the "Reproducing the agentic workflow" section (work order 1).
   - **K01:** decided by a later ruling after the human's counts-only check.

8. **Deployed application.** The runtime is not available. It was destroyed by the cleanup after window 3 on Nov 20, 2026. 43 base resources remain: network, data bucket, ECR, CodeBuild, EMR roles and security groups, log groups. They match the window 1 plan (`operations/plans/20261116T060222Z-d33ce8e8d507.md`, runtime_enabled false). Query and evidence examples are shown from the archived window 3 smoke records, and the documents say so. Planned final cleanup (`cleanup.sh --stage infra`, then `--stage final`): December 15, 2026, after grading.

9. **Final review.** code-reviewer reviews the whole repository read-only at the end (Ponytail OFF). The spec's final gate needs APPROVE. One repair round is allowed. More need a human exception. Minor findings may become residual risks.

10. **Wording.** No text may address a grader or an evaluator. Describing how to run, test or verify something is fine. Step S scans for this.

11. **Account ID and labels (TA reply pending).** New documents write `<account-id>` in place of the 12-digit account ID. Bucket and ECR names are written as `scholarchain-course-data-<account-id>-us-east-2`, and no ARN containing the account ID is copied. Existing committed files are left alone until the TA replies. Agents never open `labels/`. Documents may link to `labels/README.md` and name the label files by path.

12. **Sessions.** A NEW Claude Code conversation for each session; one commit by the human after each session.

    | Session | Agent | Work order | Ponytail |
    | --- | --- | --- | --- |
    | 1 | documentation-engineer | 1 | ON |
    | 2 | documentation-engineer | 2 | ON |
    | 3 | factory-engineer | 3 | OFF |
    | 4 | dataset-engineer | 4 | ON |
    | 5 | deploy-engineer | 5 | OFF |

    Grep and Glob may be unavailable to agents, so every work order names its inputs by path. The orchestrator appends each report verbatim to `docs/agent-runs/phase-17/agent-report.md` under "Work order <k>".

## Work order 1 (documentation-engineer): README.md, docs/api-reference.md, docs/architecture.md

**Write only:** `README.md`, `docs/api-reference.md`, `docs/architecture.md`.

**Read (by path):**
- **Spec:** `spec/ScholarChain-spec.md`, the sections named in ruling 1 plus "Agent authority and ownership matrix" and "Required factory artifacts". `spec/architect-decisions.md`.
- **Agent system:** `CLAUDE.md`, `docs/agents.md`, `.claude/settings.json`, `.mcp.json`, `.claude/commands/{deploy,experiment,incident,rollback,run-pipeline}.md`, and the header comment of each `.claude/hooks/*.sh`.
- **The 18 agent files:** `.claude/agents/{build-engineer,code-reviewer,dataset-engineer,dependency-updater,deploy-engineer,documentation-engineer,domain-engineer,entity-resolution-engineer,experiment-engineer,factory-engineer,hadoop-engineer,incident-responder,infra-engineer,rag-engineer,service-engineer,spark-engineer,storage-engineer,test-engineer}.md`.
- **Scripts:** `scripts/README.md`, and the usage blocks of `scripts/*.sh` and `datasets/scripts/*.sh`.
- **Modules:** `modules/hadoop/JOBS.md`, `modules/spark/JOBS.md`, `modules/storage/STORAGE.md`, `modules/rag/RUNTIME.md`, `modules/rag/RAG.md`, `modules/services/SERVICES.md`, `modules/core/src/main/resources/scholarchain.conf`, `tests/TESTS.md`.
- **Infrastructure and datasets:** `infra/terraform/README.md`, `infra/terraform/envs/course/course.tfvars`, `datasets/dataset-manifest.yaml`, `datasets/schemas/*.md`.
- **Evaluation and records:** `docs/evaluation.md`, `experiments/derived/README.md`, `experiments/manifests/*.json`, `docs/agent-runs/README.md`, `docs/agent-runs/tool-versions.md`, `docs/agent-runs/phase-15/prompt.md` (rulings 1 to 4 and work order 1), and `docs/agent-runs/phase-{12,14,15}/human-gate.md`.
- **Operations:** `operations/releases/release-20261119T233215Z-c5428063732a.json`, `operations/releases/smoke-20261119T235713Z.json` and its folder, `operations/cleanup/cleanup-20261120T041658Z-runtime/record.json` and `retained.tsv`.
- **API reference only:** `modules/services/src/main/scala/scholarchain/services/{Services,Models,Http}.scala` and `modules/services/openapi/examples/*.json`.

**README.md must contain:**
1. **Every item of the spec's "README requirements" list**, in that order:
   - title and identity (ruling 4);
   - one-paragraph problem statement;
   - architecture summary;
   - agentic workflow summary;
   - required local software, with versions from `tool-versions.md`;
   - AWS prerequisites;
   - environment-variable names without values: `SCHOLARCHAIN_*` from `scholarchain.conf`, the scripts' variables, the `.mcp.json` variables, and the user-scope `PONYTAIL_DEFAULT_MODE`, `PONYTAIL_SUBAGENT_MATCHER` and `SCHOLARCHAIN_CONTACT_EMAIL`;
   - exact format, compile, test and package commands (`sbt fmt`, `sbt check`, `sbt packageJobs`, `scripts/check.sh`);
   - the bounded local sample command: the Hadoop pipeline on `datasets/samples/` exactly as in `modules/hadoop/JOBS.md` "Running locally", with `--code-version "$(git rev-parse HEAD)"`, then the Spark `PipelineJob` on its output as in `modules/spark/JOBS.md` "Running locally";
   - Terraform fmt, validate and plan commands (`scripts/check.sh` gates and `scripts/plan.sh`);
   - the automation scripts and who runs each (`docs/agents.md` section 8 and `scripts/README.md`);
   - deployment and smoke-test commands, in the corrected window order from `scripts/README.md` "The flow";
   - API query examples, from the smoke records, with the API key passed on standard input;
   - experiment reproduction commands (`scripts/run-experiment.sh --manifest experiments/manifests/<m>.json`, and the analysis commands in `experiments/derived/README.md`);
   - a "Final report and video" section linking `reports/final-report.md`, plus the line `Demonstration video: link added at submission` (the human replaces it);
   - cleanup commands (`scripts/cleanup.sh`, human only, absolute plan path);
   - known limitations;
   - the deliverable index.
2. **Agent catalog:** one table row per agent file (18 rows), with these columns:
   - name;
   - purpose;
   - owned paths (from `docs/agents.md`);
   - tool fence (from the agent's frontmatter);
   - prerequisites (MCP servers, environment variables, tools);
   - an exact orchestrator instruction `Use the <agent> agent to ...`;
   - the deterministic verification command (the gate in `docs/agents.md`);
   - the report fields (the report contract in the agent file).
3. **A subsection titled exactly `Reproducing the agentic workflow`:**
   - the spec's 11 steps in project form: the clone and tag (`v1.0-submission`); the Claude Code version; `claude` from the repository root; how to confirm that the agents are loaded (`/agents`); the validation command `.claude/hooks/validate-agent-files.sh`; the dangerous-command probe `.claude/hooks/guard-dangerous.sh --self-test`, plus one live probe described as an expected block; the fresh session per phase; and the comparison with the archived evidence;
   - how to verify each external prerequisite;
   - a bounded reproduction exercise that needs no AWS: replay Phase 15 work order 1 (rag-engineer, retrieval modes; `docs/agent-runs/phase-15/prompt.md`). Check out a branch at commit `aa30f5a` (the commit that added that work order), paste the work order into a fresh session, run `sbt check`, then compare with the "Work order 1" report in `docs/agent-runs/phase-15/agent-report.md` and with `git diff aa30f5a c542806 -- modules/rag` (that commit also contains repair-01).
4. **Supporting sections:**
   - what `.claude/` and `docs/agent-runs/` contain;
   - that the orchestrator is the main session and not a subagent file;
   - the merged phases;
   - the E01/E03/E06 record note and the other ruling 7 explanations, as plain facts.
5. **Deliverable index:** D01 to D20, one link per item. Link the existing evidence, for example D14 to `operations/releases/` and D20 to `operations/cleanup/`. D19 is the video line.
6. **Known limitations,** from `docs/evaluation.md` and the gates, including:
   - P14-D8: Spark job 10 on EMR at 50% and 100%;
   - the verifier limits D18 to D20 (pending tests);
   - the Neptune-bound graph-evidence-api (the CPU target cannot be reached);
   - file-backed vectors in experiment E;
   - the open Neptune record count;
   - the deployed application not being available (ruling 8).

**docs/api-reference.md:** every route with method, path, path and query parameters, request and response fields, error codes with an example file each, the auth header name, the idempotency key, and the public routes (`/healthz`, `/readyz`). Take these from the route code and the example JSON. Link the file from the README.

**docs/architecture.md:** two Mermaid diagrams in fenced `mermaid` blocks, each followed by a short text per component:
- **Build time:** human architect, orchestrator, subagents, hooks, gates, `docs/agent-runs/`.
- **Runtime:** dataset scripts → S3 raw → Hadoop on EMR (J01–J10) → Spark on EMR (jobs 1–11) → loaders → S3, Neptune, DynamoDB and S3 Vectors → query-api, graph-evidence-api and worker on ECS Fargate behind the ALB, with SQS → RAG runtime with Bedrock Haiku 4.5 and Titan V2.

**Limits:**
- Rulings 3, 10 and 11 apply.
- Use Read, Grep and Glob. Bash only for the two commands in ruling 3.
- Never open `labels/`, `scratch/`, `secrets/`, or any file under `docs/agent-runs/*/evidence/` larger than 1 MB.
- Do not edit `docs/agents.md` or any constitutional file. Route a needed correction as BLOCKED-ON factory-engineer.

**Report:**
- files and line counts;
- each required item, done or missing;
- commands replayed, with what was observed;
- commands documented but not replayed, with their sources;
- deviations.

## Work order 2 (documentation-engineer): lineage, threat model, limits, runbook, demo script, final report

**Write only:** `docs/data-lineage.md`, `docs/threat-model.md`, `docs/service-limits.md`, `docs/runbook.md`, `docs/demo-script.md`, `reports/final-report.md`.

**Read (by path):** the work order 1 inputs, plus:
- `README.md`, `docs/architecture.md` and `docs/api-reference.md` as committed by session 1;
- `docs/agent-runs/phase-17/inputs/demo-script-source.md`;
- the rulings in `docs/agent-runs/phase-{10,11,12,14,15}/prompt.md` named below;
- `docs/agent-runs/factory-metrics.md`;
- `docs/agent-runs/phase-*/human-gate.md`;
- `docs/agent-runs/phase-15/evidence/gate/cost-explorer-20261121.txt`;
- `operations/deployments/window-2/post-exp-d-cloudwatch.txt`;
- `operations/rulings/p15-w3-*.txt`, headers and result lines only;
- the `docs/agents.md` sections "Deterministic floor and enforcement layers" and "Residual risks accepted by the human architect".

**Documents:**

1. **`docs/data-lineage.md`.** Source record → dataset script → S3 raw → Hadoop job outputs (`modules/hadoop/JOBS.md`) → entity resolution decisions → Spark tables (`modules/spark/JOBS.md`) → store conversion → S3, Neptune, DynamoDB and S3 Vectors (`modules/storage/STORAGE.md`) → evidence IDs → verified answer claim (`modules/rag/RUNTIME.md`). Include one traced example: the smoke query's evidence `ev-927665f3bbac87eb60685e50cfeb8f9b` (COAUTHOR_EDGE; `operations/releases/smoke-20261119T235713Z/evidence.json`), followed back through its edge ID, supporting DBLP work IDs and run IDs to the Hadoop and Spark run records.

2. **`docs/threat-model.md`.** Cover:
   - the spec's seven categories: prompt injection (retrieved text is data; semantic items never verify facts), data poisoning, credential exposure, over-permission, denial of service, destructive commands (the guard and its self-test count, replayed), and provenance tampering;
   - the HTTP-only ALB (Phase 12 ruling 11);
   - the ListBucket exposure and its 404 reason (Phase 12 ruling 23);
   - ECR scan findings, 10 CRITICAL and 48 HIGH (Phase 14 ruling 25);
   - the SSE-C drift (Phase 14 ruling 38);
   - session tokens in DEBUG logs and their redaction (Phase 14 ruling 41);
   - the API key in Secrets Manager;
   - the residual risks from `docs/agents.md`.

   Give each threat a mitigation and a residual risk.

3. **`docs/service-limits.md`.** Use these human-verified facts, each with its date:
   - Region us-east-2 (D-04);
   - Bedrock on-demand quotas: Haiku 4.5 at 10 requests per minute account-wide and 5,000,000 tokens per minute, Titan V2 at 60 requests per minute and 300,000 tokens per minute (read Nov 14, 2026). The code throttles to 8 and 50 per minute. On ECS each process gets 4 per minute (Phase 10 ruling 10; Phase 12 work order 4);
   - model IDs `us.anthropic.claude-haiku-4-5-20251001-v1:0` (cross-Region profile: us-east-1, us-east-2, us-west-2; checked Nov 14) and `amazon.titan-embed-text-v2:0` (1024 dimensions, normalized);
   - EC2 On-Demand Standard vCPU quota 16, so one 12-vCPU EMR cluster at a time (Phase 14 prompt);
   - `emr-spark-8.0.0`: Spark 4.0.2, Hadoop 3.4.2-amzn-1 inside the Spark application, m6g.xlarge Graviton, YARN maximum container 11,712 MB (Nov 16);
   - Neptune 1.4.8.1 on db.t4g.medium, orderable in 2a/2b/2c (Nov 14), with db.r6g.large for bulk loads (Phase 14 rulings 42 and 43);
   - Fargate ARM64;
   - the 25 USD budget and the 60 USD spend limit (D-12, D-13).

   The aws-docs MCP tools may be used for this document only, to cite public documentation pages. Record each page's URL and the access date. Never present a value read from documentation as the account's own quota.

4. **`docs/runbook.md`.** Cover:
   - deployment in the corrected window order (`scripts/README.md` "The flow");
   - the smoke test and the Neptune warm-up before it;
   - common failures, from the window lessons: credential refresh mid-script, cold Neptune cache, Neptune bulk-load out of memory on t4g, a stale Terraform lock after an interrupt, LoadS3Cli verify timeouts, the secret pending deletion, a dirty tree blocking scripts;
   - rollback (`scripts/rollback.sh`);
   - incident escalation (`.claude/commands/incident.md`, `docs/agents.md`);
   - cleanup: human only, the absolute plan path, `aws login` right before.

5. **`docs/demo-script.md`.** Built from the inputs file (ruling 5), with each spec demonstration requirement mapped to its clip.

6. **`reports/final-report.md`.** Cover problem, motivation, architecture, implementation, agentic workflow, experiments A, D, D-scale, E and F with size scaling, results (link `docs/evaluation.md` and the derived tables; restate no number without its source), costs (Phase 15 ruling 16; 16.84 USD month to date before credits on Nov 21), failures (P14-D1 to D8, window lessons), lessons, and limitations.

   Include the spec's four-way distinction, built from the human gates and `factory-metrics.md`:
   - agent-generated and accepted without change;
   - repaired by another agent;
   - manually edited (each disclosed manual edit);
   - human-authored prompts, labels, decisions and explanations.

   Disclose all AI assistance: Claude web chats for rulings, work orders and gates; Codex on Nov 10; the course autograder runs; the display-only labellers kept outside the repo; the Claude-drafted, human-reviewed benchmark questions and demo script.

**Limits:** as in work order 1. Bash only for the two ruling 3 commands; aws-docs MCP only for `docs/service-limits.md`.

**Report:** as in work order 1.

## Work order 3 (factory-engineer): known guard false positives (Ponytail OFF)

**Write only:** `docs/agents.md`.

Append bullets to the section "Residual risks accepted by the human architect", each stating the risk and its mitigation:
- **`guard-paths.sh` `[protected-path-write]` false positives:**
  - text under `labels/` that contains a write verb;
  - jq reads combined with `tee`;
  - `jq` combined with `env`;
  - `==>` in a grep pattern read as a redirect into `docs/agent-runs/`.
- **Client and connection limits:**
  - Grep and Glob unavailable to subagents in some sessions (Phase 15 sessions 9, 10 and 12); mitigation: work orders name every input by path;
  - the aws-api MCP server failing to connect; mitigation: live AWS reads are human-run.

**Limits:**
- Change no hook, no settings, no `.mcp.json` and no agent file (ruling 7 keeps C03 and F07 as they are).
- Afterwards run `.claude/hooks/validate-agent-files.sh` and report its result.
- This change takes effect only after human ratification at the Phase 17 gate.

**Report:** the full diff and the validator result.

## Work order 4 (dataset-engineer): Crossref and ORCID license facts (V2 DATA-03)

**Write only:** `datasets/dataset-manifest.yaml`. Change only these fields:
- the `license` and `license_evidence` fields of the Crossref and ORCID entries;
- the `license_basis` text of their committed samples.

Replace every "UNVERIFIED" statement about these licenses with the facts below, read by the human's assistant on the providers' own pages on 2026-11-21. Quote at most a short phrase from each page.

**Crossref.** Page: https://crossref.org/documentation/retrieve-metadata/rest-api/rest-api-metadata-license-information (the page states it was last updated 2022-02-01).
- Metadata retrieved with the Crossref REST API, or included in Crossref snapshots and public data files, may be used and redistributed.
- Crossref treats basic bibliographic metadata, references included, as facts that are not subject to copyright, so no license applies and CC0 cannot apply (there is nothing to waive).
- Abstracts remain the copyright of the publisher or author but are redistributable under the Crossref membership terms.
- The committed samples exclude abstracts, as before.
- License field: "No license applies: Crossref states that bibliographic metadata are facts not subject to copyright and may be used and redistributed without restriction; abstracts are publisher or author copyright (excluded from the committed samples)."

**ORCID.** Pages: https://info.orcid.org/public-client-terms-of-service/ (Public APIs Terms of Service, page states last updated 23 October 2024) and https://info.orcid.org/public-data-file-use-policy (Public Data File).
- Data obtained through the Public APIs (anonymous and registered) is under a limited license to use Registry data marked public, subject to those terms:
  - non-commercial use;
  - posted rate limits and usage quotas;
  - no continuous polling;
  - export-control limits.
- The terms grant no redistribution right.
- Only the annual Public Data File is CC0 1.0, and this project does not use it.
- Real ORCID records are therefore never committed (human ruling decision-1, unchanged). The committed samples are the fictional test record and synthetic records.
- License field: "Public APIs Terms of Service: limited non-commercial license to use public Registry data, no redistribution grant; real records are not redistributed (decision-1). The ORCID Public Data File is CC0 1.0 but is not used."

**Limits:**
- Keep every other field byte-identical.
- Run `bash datasets/scripts/validate.sh` once afterwards and report its summary.
- No network fetch, no `--full` run, no other script.

**Report:** the diff and the validator result.

## Work order 5 (deploy-engineer): final release manifest (Ponytail OFF)

**Write only:** `operations/releases/release-manifest-final.md`, as a new file.

**Content:** the ruling 6 facts in a table with columns item, value and source path, plus:
- the deployed revisions: the three services with task definition revision `:2`, and the ARNs written with `<account-id>` (ruling 11);
- the smoke result;
- the cleanup record and the 43 kept base resources (`retained.tsv`);
- the earlier releases (window 1 at `d33ce8e`, window 2 `release-20261117T232233Z-506eddf46f23.json` with the same image digest), each listed with its record path.

**Read only these paths:**
- `operations/releases/release-20261119T233215Z-c5428063732a.json`
- `operations/releases/package-deef29516bf395ac2b551beb93710b6a48a937a8.json`
- `operations/plans/20261119T173346Z-c5428063732a.md`
- `operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt`
- `operations/releases/smoke-20261119T235713Z.json`
- `operations/cleanup/cleanup-20261120T041658Z-runtime/record.json`
- `operations/cleanup/cleanup-20261120T041658Z-runtime/retained.tsv`
- `operations/releases/release-20261117T232233Z-506eddf46f23.json`
- `operations/releases/release-20261116T062349Z-d33ce8e8d507.json`
- `infra/terraform/envs/course/course.tfvars`
- `modules/core/src/main/resources/scholarchain.conf`
- `modules/rag/RUNTIME.md`, section 12 and the configuration table
- `datasets/dataset-manifest.yaml`, snapshot fields only
- `docs/agent-runs/phase-15/evidence/gate/check-sh-gate.txt`

**Limits:**
- No Bash at all: no script, no aws, no git, no MCP call.
- If a value is missing from these records, write "not recorded" and name what was looked for.

**Report:**
- the file and its line count;
- each ruling 6 item, found or not recorded;
- deviations.

## Human ruling 13 (Nov 21, 2026): demo-script inputs and the version record

1. The demo-script source was exported from the Claude Doc as Markdown to `docs/agent-runs/phase-17/inputs/demo-script-source.md`. It was written before Part B was recorded and is partly out of date. `docs/agent-runs/phase-17/inputs/demo-recording-notes.md` (human) records what was actually recorded. Work order 2 reads both; where they differ, the notes win.
2. `docs/agent-runs/tool-versions.md` has a 2026-11-21 section (human). It is the version record the README cites (work order 1).

## Human ruling 14 (Nov 22, 2026): K01 disposition

The two lines flagged by the course autograder in `docs/agent-runs/phase-10/evidence/code-review-1/change-set.txt` (change-set lines 9549 and 9695) are test fixtures, not credentials. They are line 315 of `modules/services/src/test/scala/scholarchain/services/QueryApiSpec.scala` and line 22 of `modules/services/src/test/scala/scholarchain/services/ServiceFixtures.scala` (the API-key value the service tests use). Both files are listed in the change set under "UNTRACKED FILE" headers and are committed under `src/test/`. Neither value has the shape of an AWS access key or session token. No change. Work order 1 states this in the README together with the other ruling 7 explanations.

## Human ruling 15 (Nov 22, 2026): the TA's answers on redaction and the endpoint

The TA's reply is recorded verbatim in `docs/agent-runs/phase-17/inputs/ta-reply-2026-11-22.md`. This ruling replaces the "TA reply pending" part of ruling 11.

1. **Endpoint.** The deployed application need not keep running. Ruling 8 stands unchanged.
2. **Removed: private and sensitive information.** Credentials and secrets stay out of the repository. The DEBUG session tokens were redacted (Phase 14 ruling 41); the API key value lived only in Secrets Manager; the two K01 lines are test fixtures (ruling 14). Step S scans the tree for credential shapes (access keys, session tokens, secret-key and password assignments, private keys) and for personal data beyond ruling 4.
3. **Kept: non-sensitive signatures and temporary IDs,** as the TA asks, so that the work can be traced to its own runs: commit hashes; run, query, trace and evidence IDs; image digests; plan and artifact checksums; and the release, smoke, pipeline and cleanup records with their timestamps.
4. **Account ID.** The 12-digit AWS account ID stays in the committed evidence records where it already appears (196 files and 3,689 occurrences at `87151a8`, all under `operations/`, `experiments/results/` and `docs/agent-runs/`). AWS's account-management documentation states that account IDs, though to be shared carefully, are not secret, sensitive or confidential information. They are part of the run records of item 3; editing them would change checksummed evidence; and git history keeps them anyway. No history rewrite. New text keeps writing `<account-id>` (ruling 11).
5. **Identity.** The README keeps the student name and UIC email (ruling 4).
6. **Labels.** `labels/` stays in the public repository. It holds the human-authored labels: the gold set (names, ORCID iDs, affiliations and DOIs taken from the public bibliographic records the pipeline used) and the experiment E relevance and rating files (IDs and scores only). It contains no private data and is the evidence of the labelling work. Agents still never open `labels/` (ruling 11).

## Human ruling 16 (Nov 22, 2026): outcomes of sessions 1 to 5 and Step F

1. **Work order 1** is accepted. The student email stays in the README (ruling 4). The removal of the "polite-pool User-Agent" wording after the privacy plugin's refusal is accepted.
2. **Work order 2** is accepted.
   - Deviation 1 (`labels/README.md` opened for its heading line during the link check; no label file or value read) breaches ruling 11. No rework. Recorded as a residual risk: agents can read `labels/`, and only the instruction prevents it. This goes to the factory-engineer backlog; no change in Phase 17.
   - The A11 voiceover phrase "happens after grading" stays. It states when the final cleanup happens and addresses no one (ruling 10).
   - The Phase 14 ruling 24 edit to `course.tfvars` and the ruling 41 session-token redaction are disclosed as manual edits, as section 19.1 of the final report states.
   - The factory-metrics discrepancy stays stated as open in the final report. The Phase 17 gate adds a note on that column's unit.
   - The four-way classification of section 19.1 is accepted. It uses the spec's four classes. "Repaired by another agent" means repaired by the owner after another agent's finding (one-writer rule).
3. **Work order 3** is accepted, pending ratification at the Phase 17 gate. Its four deviations are accepted: the hook that blocks is `guard-dangerous.sh`, not `guard-paths.sh`; each bullet names the category that actually fires; the reading of the `labels/` case; and the "(Phase 17 work order 3)" tags.
4. **Work order 4** is accepted.
5. **Work order 5** is accepted, with D3 and D4 as written. D1 and D2 have committed sources outside the work order's input list:
   - D1: `docs/agent-runs/phase-15/human-gate.md` ("Accepted commit: d406c66…", and check.sh on the committed tree d406c66, exit 0) and `docs/agent-runs/phase-15/commit.txt`.
   - D2: `operations/cleanup/cleanup-20261120T041658Z-runtime/apply.txt`, line 279: "Apply complete! Resources: 0 added, 0 changed, 89 destroyed."

   deploy-engineer records are append-only, and the manifest's statements are true of the inputs it was given, so the manifest stays as written. This ruling is the correction.
6. **Step F** (ruling 2) ran on a fresh clone of `87151a8` on Nov 22, 2026. Evidence: `docs/agent-runs/phase-17/evidence/fresh-clone/`.
   - `sbt clean check`: exit 0; 845 succeeded, 0 failed, 10 pending.
   - `scripts/check.sh`: exit 0, "==> OK check: every gate passed" (json, constitution, bash-syntax, shellcheck, dataset-validation, terraform-fmt, terraform-validate, sbt-check).
   - The standalone `terraform init` between them failed on a network error ("no route to host"); its log is `fresh-terraform-init-attempt1.txt`. check.sh's own init succeeded, and the rerun is `fresh-terraform-init.txt`.
   - Bounded sample, with the README commands as written. Hadoop: exit 0, run `run-e1467fc1552982ae1519a23ba6f6be4e`. Spark PipelineJob: exit 0, run `run-fe913aefae9e677ff451518942c97582`.
   - After the Hadoop run, a shutdown thread (`Thread-2`) logs a `NoSuchFileException` for a jar under sbt's `target/bg-jobs/`, which sbt deletes when a run ends. The job had already finished, and Spark read its output. Harmless.
   - Before the clone existed, the sample commands ran once in the working repository by mistake (outputs only under the gitignored `scratch/`). Those logs were overwritten and are not evidence.
   - Clip B11 is recorded later from the README and these logs.
