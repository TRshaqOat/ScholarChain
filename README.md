# ScholarChain

Scholarly collaboration intelligence on AWS: Scala 3 Hadoop MapReduce and Spark on Amazon EMR, a temporal weighted coauthorship graph in Amazon Neptune, Amazon DynamoDB, Amazon S3 and Amazon S3 Vectors, a Graph-RAG runtime served by Scala 3 REST services on ECS Fargate, and every AWS resource in Terraform. The repository was produced through a controlled Claude Code agentic workflow (orchestrator plus 18 subagents, hooks, and human gates).

- **Student:** Achyut Patel, apate673@uic.edu (individual project)
- **Course:** CS441 course project, specification in [`spec/ScholarChain-spec.md`](spec/ScholarChain-spec.md); architect decisions in [`spec/architect-decisions.md`](spec/architect-decisions.md)
- **Repository:** https://github.com/TRshaqOat/ScholarChain (private until Nov 25, 2026, then public). Submitted tag: `v1.0-submission`. The final commit and tag are recorded in the Phase 17 gate.
- **Deployed application:** not available. The runtime was destroyed by the cleanup after cloud window 3 on Nov 20, 2026 (see [Known limitations](#known-limitations)).

Contents: [Problem](#problem-statement) · [Architecture](#architecture-summary) · [Agentic workflow](#agentic-workflow-summary) · [Software](#required-local-software) · [AWS](#aws-prerequisites) · [Environment variables](#environment-variables-names-only) · [Build and test](#format-compile-test-and-package) · [Bounded sample](#bounded-local-sample-run) · [Terraform](#terraform-format-validate-and-plan) · [Automation](#automation-scripts-and-who-runs-them) · [Deployment](#deployment-and-smoke-test) · [API examples](#api-query-examples) · [Experiments](#experiment-reproduction) · [Final report and video](#final-report-and-video) · [Cleanup](#cleanup) · [Known limitations](#known-limitations) · [Deliverables](#deliverable-index) · [Agent catalog](#agent-catalog) · [Reproducing the agentic workflow](#reproducing-the-agentic-workflow) · [Records](#what-claude-and-docsagent-runs-contain)

## Problem statement

A literal author-string query can count papers, but it cannot say who really has the broadest collaboration network, how two researchers connect through intermediate coauthors, or who bridges otherwise separate communities, because those answers depend on researcher identity resolution, coauthor-pair expansion, graph construction, and auditable path evidence. ScholarChain parses the DBLP XML dump and links it to OpenAlex, Crossref and ORCID with Hadoop MapReduce, resolves researcher and work identity with an evaluated, abstaining policy, builds a temporal weighted coauthorship graph with Spark, and computes for every researcher and time window the publication count, distinct coauthors, coauthor occurrences, average and maximum team size, weighted degree, connected components, bounded shortest paths, communities, three bridge measures, and k-core. A runtime of narrow agents answers natural-language collaboration questions from those deterministic results and returns only claims that cite evidence IDs and pass rule-based verification; it abstains when identity or evidence is insufficient.

## Architecture summary

Diagrams and per-component text: [`docs/architecture.md`](docs/architecture.md).

- **Data.** DBLP snapshot 2026-10-03 (a 46-venue subset, 110,867 works in the full local and EMR 100 percent runs) plus DOI-driven subsets of OpenAlex, Crossref and ORCID (snapshots 2026-11-03), as recorded in [`datasets/dataset-manifest.yaml`](datasets/dataset-manifest.yaml) and [`docs/evaluation.md`](docs/evaluation.md) section 7.
- **Hadoop MapReduce** ([`modules/hadoop/JOBS.md`](modules/hadoop/JOBS.md)): J01 DBLP extraction, J02 source normalization, J03+J04 exact identifiers, J05 blocking, J06 candidate join, J10 match decisions, J07+J08 team and pair expansion, J09 Spark-ready export.
- **Entity resolution** ([`modules/entity-resolution/`](modules/entity-resolution/)): pure versioned normalization, blocking and feature functions, policy `er-v2`, ACCEPTED, REJECTED, UNRESOLVED and CONFLICT outcomes.
- **Spark** ([`modules/spark/JOBS.md`](modules/spark/JOBS.md)): jobs 1 to 11 in four stages. The local Phase 6 graph has 191,318 researchers and 787,726 coauthor edges in the all-years window ([`docs/evaluation.md`](docs/evaluation.md) section 2).
- **Stores** ([`modules/storage/STORAGE.md`](modules/storage/STORAGE.md)): S3 (one data bucket, `scholarchain-course-data-<account-id>-us-east-2`), Neptune 1.4.8.1, seven DynamoDB tables, five S3 Vectors indexes. No OpenSearch tier (D-06).
- **Runtime** ([`modules/rag/RUNTIME.md`](modules/rag/RUNTIME.md)): ten stages, two of which call Claude Haiku 4.5 (planning and synthesis); Titan Text Embeddings V2 for the query embedding; rule-based claim verification; write-once audit.
- **Services** ([`docs/api-reference.md`](docs/api-reference.md)): query-api, graph-evidence-api and worker on ECS Fargate (ARM64) behind an HTTP ALB, with an SQS job queue and DLQ.
- **Infrastructure** ([`infra/terraform/README.md`](infra/terraform/README.md)): Terraform 1.16.6, provider `hashicorp/aws` 6.28.0, Region us-east-2 (D-04), two stages selected by `runtime_enabled`.

## Agentic workflow summary

The main Claude Code session is the **orchestrator**; it has no file under `.claude/agents/`. It reads a human work order, delegates to the one subagent that owns the artifact class, routes `BLOCKED-ON` results and review findings, and assembles the agents' final reports into `docs/agent-runs/phase-NN/`. Eighteen subagents under [`.claude/agents/`](.claude/agents/) own disjoint paths and never call one another. Hooks and permission rules in [`.claude/settings.json`](.claude/settings.json) form the deterministic floor (dangerous-command guard, path-ownership guard, Scala formatter, test-stop hook, constitution validator). The code-reviewer has no write tool, the test-engineer writes only under `tests/`, and the human ratifies constitutional changes, approves Terraform plans and cloud operations, and accepts each phase. The authoritative rules are [`CLAUDE.md`](CLAUDE.md) and [`docs/agents.md`](docs/agents.md); the per-agent table is the [agent catalog](#agent-catalog), and the replay procedure is [Reproducing the agentic workflow](#reproducing-the-agentic-workflow).

## Required local software

Versions are the human's record in [`docs/agent-runs/tool-versions.md`](docs/agent-runs/tool-versions.md) (section "Update 2026-11-21").

| Tool | Version used | Needed for |
|---|---|---|
| macOS on Apple Silicon (arm64) | 27.x | development machine; images target linux/arm64 |
| Claude Code CLI | 2.1.329 | the agentic workflow (`claude`) |
| JDK (Temurin, aarch64) | 17.0.21 | sbt, Scala, Hadoop and Spark local runs |
| sbt launcher | 1.13.0 (`project/build.properties` pins `sbt.version=1.13.0`) | build, tests, local jobs |
| Scala | 3.3.7 (fetched by sbt; scala-library 2.13.16) | all modules |
| Terraform | 1.16.6, provider `hashicorp/aws` 6.28.0 (lock file committed) | `scripts/check.sh`, `scripts/plan.sh` and the apply path |
| AWS CLI | 2.38.3 | human-run cloud scripts |
| jq | 1.7.1 | hooks and scripts |
| Python | 3.13.5 | dataset validation gate, experiment analysis |
| git | 2.54.0 | everything |
| uv | 0.13.4 | `uvx` launches the pinned MCP servers in [`.mcp.json`](.mcp.json) |
| curl | any | `scripts/smoke-test.sh`, dataset scripts |
| Ponytail plugin | v5.3.2 (user scope) | optional for replay ([`spec/architect-decisions.md`](spec/architect-decisions.md) D-11) |
| shellcheck | not installed in the recorded runs | optional `scripts/check.sh` gate (reported SKIPPED) |

## AWS prerequisites

Only the cloud steps need AWS; the build, tests, bounded local sample and the agent-replay exercise do not.

- An AWS account on the Paid plan in **us-east-2** (D-04, D-12), with budget alerts (D-12, D-13: target below 25 USD; 16.84 USD month to date before credits was read on Nov 21, 2026, [`docs/agent-runs/phase-15/human-gate.md`](docs/agent-runs/phase-15/human-gate.md)).
- Amazon Bedrock access to `us.anthropic.claude-haiku-4-5-20251001-v1:0` (cross-Region inference profile) and `amazon.titan-embed-text-v2:0` ([`infra/terraform/envs/course/course.tfvars`](infra/terraform/envs/course/course.tfvars)).
- A deployment profile for the human-run scripts, passed as `AWS_PROFILE` (the record names the human's `scholarchain-sdk` profile, read by Terraform through its `credential_process`; [`scripts/README.md`](scripts/README.md) `plan.sh`).
- Optionally a separate read-only profile for the `aws-api` MCP server, named by `SCHOLARCHAIN_AWS_READONLY_PROFILE` ([`docs/agents.md`](docs/agents.md) "MCP servers and trust boundaries"). The full-access deployment profile must never be given to an MCP server.
- Service quotas, Region availability and limits: [`docs/service-limits.md`](docs/service-limits.md).

## Environment variables (names only)

No value is shown here, and no secret is ever stored in the repository or in a `.env` file.

| Scope | Names | Source |
|---|---|---|
| Core configuration overrides | `SCHOLARCHAIN_CONFIG_FILE`, `SCHOLARCHAIN_ENVIRONMENT`, `SCHOLARCHAIN_AWS_REGION`, `SCHOLARCHAIN_AWS_ACCOUNT_ID`, `SCHOLARCHAIN_BUCKET`, `SCHOLARCHAIN_NEPTUNE_ENDPOINT`, `SCHOLARCHAIN_NEPTUNE_PORT`, `SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS`, `SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN`, `SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX`, `SCHOLARCHAIN_VECTOR_BUCKET`, `SCHOLARCHAIN_ER_POLICY_VERSION`, `SCHOLARCHAIN_ER_MAX_BLOCK_SIZE`, `SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE`, `SCHOLARCHAIN_GRAPH_POLICY_VERSION`, `SCHOLARCHAIN_EMBEDDING_MODEL_ID`, `SCHOLARCHAIN_PLANNER_MODEL_ID`, `SCHOLARCHAIN_SYNTHESIS_MODEL_ID`, `SCHOLARCHAIN_VERIFICATION_MODEL_ID`, `SCHOLARCHAIN_ADJUDICATION_MODEL_ID`, `SCHOLARCHAIN_PROMPTS_DIR`, `SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE`, `SCHOLARCHAIN_WINDOW_SUMMARY_RUN`, `SCHOLARCHAIN_STUB_LLM`, `SCHOLARCHAIN_JOB_QUEUE_URL`, `SCHOLARCHAIN_JOB_DLQ_URL`, `SCHOLARCHAIN_JOB_MAX_RECEIVE_COUNT` | [`scholarchain.conf`](modules/core/src/main/resources/scholarchain.conf) comments |
| Service API key | `SCHOLARCHAIN_API_KEY` (the name in `services.api-key-env`; ECS injects the value from Secrets Manager) | same |
| Build and local jobs | `SCHOLARCHAIN_SPARK_HEAP`, `SPARK_LOCAL_IP`, `SCHOLARCHAIN_IMAGE_TAG` | [`modules/spark/JOBS.md`](modules/spark/JOBS.md), [`scripts/README.md`](scripts/README.md) |
| Operational scripts | `AWS_PROFILE`, `TF_VAR_alarm_email`; `run-experiment.sh` passes `SCHOLARCHAIN_EXPERIMENT_ID`, `SCHOLARCHAIN_EXPERIMENT_DIR`, `AWS_PROFILE`, `AWS_REGION` to the experiment command | [`scripts/README.md`](scripts/README.md) |
| Dataset scripts | `OPENALEX_API_KEY` (full runs only, never written anywhere), `SCHOLARCHAIN_CONTACT_EMAIL` (required by `datasets/scripts/fetch_crossref.sh`, optional for the other fetch scripts; never printed), `SCHOLARCHAIN_DATASET_CONFIG` | [`datasets/dataset-manifest.yaml`](datasets/dataset-manifest.yaml), `datasets/scripts/lib.sh` |
| MCP servers | `GITHUB_PERSONAL_ACCESS_TOKEN`, `SCHOLARCHAIN_AWS_READONLY_PROFILE`, `AWS_REGION` (optional), `TMPDIR` (optional) | [`.mcp.json`](.mcp.json), [`docs/agents.md`](docs/agents.md) |
| User scope (`~/.claude/settings.json` env block or shell) | `PONYTAIL_DEFAULT_MODE`, `PONYTAIL_SUBAGENT_MATCHER`, `SCHOLARCHAIN_CONTACT_EMAIL` | [`docs/agent-runs/tool-versions.md`](docs/agent-runs/tool-versions.md) |

## Format, compile, test, and package

Run from the repository root (source: [`CLAUDE.md`](CLAUDE.md) "Commands", [`scripts/README.md`](scripts/README.md)).

```bash
sbt fmt            # scalafmt on every module
sbt check          # format check, compile, every test, AWS-free contract tests, then markTestRun
sbt packageJobs    # Hadoop and Spark job jars
scripts/check.sh   # every non-cloud gate: JSON, constitution validator and hook self-tests, bash -n,
                   # shellcheck (if installed), bash datasets/scripts/validate.sh,
                   # terraform fmt -check and validate, sbt check
```

The scripts run sbt as `SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g -batch ...` ([`scripts/README.md`](scripts/README.md) "Conventions"). `scripts/check.sh` needs jq, python3, terraform 1.16.6 and sbt; its first `terraform init` downloads the pinned provider. Recorded result on the accepted Phase 15 tree `d406c66`: exit 0, 845 tests passed, 0 failed, 10 pending ([`docs/agent-runs/phase-15/human-gate.md`](docs/agent-runs/phase-15/human-gate.md), evidence `docs/agent-runs/phase-15/evidence/gate/check-sh-gate.txt`). The fresh-clone `sbt clean check` and `scripts/check.sh` replays of Phase 17 are stored in `docs/agent-runs/phase-17/evidence/fresh-clone/` (Phase 17 ruling 2).

## Bounded local sample run

The committed samples under [`datasets/samples/`](datasets/samples/) run through the Hadoop pipeline and then the Spark pipeline on a laptop, with no AWS. Commands from [`modules/hadoop/JOBS.md`](modules/hadoop/JOBS.md) "Running locally" and [`modules/spark/JOBS.md`](modules/spark/JOBS.md) "Running locally":

```bash
# 1. Hadoop pipeline J01 to J10 on the committed samples (Test/runMain: Hadoop is a Provided dependency)
sbt "hadoop/Test/runMain scholarchain.hadoop.Pipeline --out scratch/hadoop/samples --code-version "$(git rev-parse HEAD)" \
  --dblp datasets/samples/dblp/dblp-sample.xml --dblp-dtd datasets/samples/dblp/dblp-2023-06-28.dtd --dblp-snapshot 2026-10-03 \
  --openalex datasets/samples/openalex/works-sample.jsonl --openalex-snapshot 2026-11-03 \
  --crossref datasets/samples/crossref/works-sample.jsonl --crossref-snapshot 2026-11-03 \
  --orcid datasets/samples/orcid --orcid-snapshot 2026-11-03 --reducers 2"

# 2. Spark jobs 1 to 11 on that output; <hadoop run id> is the run= directory step 1 created
#    (its _manifest.json records the run ID)
sbt "sparkAnalytics/runMain scholarchain.spark.PipelineJob --input scratch/hadoop/samples/snapshot=2026-10-03/run=<hadoop run id> \
  --code-version "$(git rev-parse HEAD)" --shuffle-partitions 40"
```

Outputs land under `scratch/hadoop/samples/` and `scratch/spark/` (`scratch/` is gitignored). Keep the Spark driver heap (`SCHOLARCHAIN_SPARK_HEAP`, default 6g) at most half of the machine's RAM. The cloud variant of the same run is `scripts/run-bounded-pipeline.sh` (below).

## Terraform format, validate, and plan

```bash
terraform fmt -check -recursive infra/terraform                                      # scripts/check.sh gate
terraform -chdir=infra/terraform/envs/course init -backend=false -input=false        # infra/terraform/README.md "Human actions"
terraform -chdir=infra/terraform/envs/course validate                                # scripts/check.sh gate
TF_VAR_alarm_email=<address or empty> AWS_PROFILE=<deployment profile> \
  scripts/plan.sh --runtime-enabled false                                            # scripts/README.md plan.sh; human only
```

`scripts/check.sh` runs the first three as gates (it uses `-lockfile=readonly` on init). `plan.sh` never applies: it writes the plan, its sha256, `plan-meta.json` and a secret-free `summary.md` under `scratch/plans/<stamp>-<commit12>/` and copies the summary to [`operations/plans/`](operations/plans/). The human reviews the plan and signs its sha256; `deploy.sh` accepts nothing else. Agents never run `terraform plan`, `apply`, `destroy` or any state command ([`infra/terraform/README.md`](infra/terraform/README.md)).

## Automation scripts and who runs them

Full contracts: [`scripts/README.md`](scripts/README.md) and each script's `--help`. Who may run a script is set by [`docs/agents.md`](docs/agents.md) "Exceptions and clarifications", item 8. Every AWS-calling script takes `--profile` (default `$AWS_PROFILE`) and `--region` (only `us-east-2`), prints `==> TARGET account=... region=...` first, and has a `--dry-run` that makes no AWS, Terraform, git or sbt call.

| Script | Purpose | Run by | Environment |
|---|---|---|---|
| `scripts/check.sh` | every non-cloud gate | anyone, including agents | none |
| `scripts/package.sh` | job jars, services image through CodeBuild, `operations/releases/package-<commit>.json` | human or deploy-engineer | `AWS_PROFILE` |
| `scripts/plan.sh` | Terraform plan and checksum; never applies | human architect | `AWS_PROFILE`, `TF_VAR_alarm_email` |
| `scripts/deploy.sh` | apply one approved saved plan, verify digests and revisions | deploy-engineer (`/deploy`); windows 2 and 3 were human-run | `AWS_PROFILE` |
| `scripts/smoke-test.sh` | health, readiness, one bounded query, evidence, idempotent replay, 401 | deploy-engineer; incident-responder after a rollback; human in window 3 | `AWS_PROFILE` |
| `scripts/rollback.sh` | services back to a known-good release record, no apply | deploy-engineer (own in-flight deploy), incident-responder (`/rollback`) | `AWS_PROFILE` |
| `scripts/run-bounded-pipeline.sh` | one ephemeral EMR cluster: Hadoop then Spark at 25, 50 or 100 percent | deploy-engineer (`/run-pipeline`) or human | `AWS_PROFILE` |
| `scripts/load-stores.sh` | S3, Neptune, DynamoDB and S3 Vectors loads | human | `AWS_PROFILE` |
| `scripts/run-experiment.sh` | one approved, committed experiment manifest | experiment-engineer (`/experiment`); the recorded D, D-scale, E and F runs were human-run | `AWS_PROFILE` |
| `scripts/archive-evidence.sh` | one versioned evidence directory and optional S3 prefix | human or deploy-engineer | `AWS_PROFILE` (metrics or S3 only) |
| `scripts/cleanup.sh` | inventory, plan, then apply the cleanup plan | **human only** (agents are denied and blocked) | `AWS_PROFILE`, `TF_VAR_alarm_email` |

Exit codes for all scripts: 0 success, 1 gate failed, 2 usage or missing tool, 3 precondition, 4 approval mismatch, 5 waiting for a human action, 6 cloud timeout ([`scripts/README.md`](scripts/README.md) "Conventions").

## Deployment and smoke test

The corrected window order, from [`scripts/README.md`](scripts/README.md) "The flow" (window 3 is the last deployed release; every window 3 script was human-run, with `aws login` immediately before each long script, Phase 15 ruling 3):

```text
Cloud window 1 (runtime_enabled = false)
  TF_VAR_alarm_email=... scripts/plan.sh --runtime-enabled false        -> human signs the sha256
  scripts/deploy.sh --plan-file <plan> --approved-plan-sha256 <sha>
  scripts/package.sh                                                     -> package record
  scripts/run-bounded-pipeline.sh --fraction 100 --package-record <rec>  (then 50, 25)
Cloud window 3 (runtime_enabled = true; image_tag and the served runs in course.tfvars)
  scripts/plan.sh --runtime-enabled true                                 -> human signs the sha256
  scripts/deploy.sh --plan-file <plan> --approved-plan-sha256 <sha> --package-record <rec>
  human sets the API key secret value; backs up the Terraform state
  Neptune scholarchain-course-graph-1 to db.r6g.large; wait until available with no pending changes
  scripts/load-stores.sh --store-run <run> --vectors-run <run> \
    --skip s3-store-conversion,s3-embed,s3-window-summary --neptune-timeout-minutes 180
  Neptune back to db.t4g.medium; wait until available
  the same deploy.sh command + --resume
  Neptune warm-up (three sequential neighbors GETs for each of the two researchers), then scripts/smoke-test.sh
  scripts/run-experiment.sh --manifest experiments/manifests/exp-d-scale-v1.json
  scripts/archive-evidence.sh; scripts/cleanup.sh --stage runtime; back up the state
```

The `--plan-file` of `deploy.sh` is resolved to an absolute path before its first gate. `deploy.sh` exits 4 on a checksum mismatch and never uses `-auto-approve`. The window 2 order in `scripts/README.md` is the same apply, secret, loads, then `deploy.sh --resume` sequence, with every load step. The `--skip` list must include `neptune` on any rerun after `==> NEPTUNE load=<id>` has printed (Phase 15 ruling 7(c)).

Recorded window 3 outcome: plan sha256 `978ce3059dc6b039e7c1bd9361c896838ea568dea9b68164ae0ff2a02e4b0bb6` (89 to add, 0 to change, 0 to destroy; [`operations/plans/`](operations/plans/)), release [`operations/releases/release-20261119T233215Z-c5428063732a.json`](operations/releases/release-20261119T233215Z-c5428063732a.json) (commit `c542806`, image digest `sha256:5c751b1e...047ea` on all three services, task definitions `:2`, runtime run `run-c9f26a9ecfe83c96526d50d79d99fd67`), smoke [`operations/releases/smoke-20261119T235713Z.json`](operations/releases/smoke-20261119T235713Z.json): PASS on all six checks.

## API query examples

The runtime is destroyed, so these commands cannot be replayed now. The responses shown are excerpts of the archived window 3 smoke bodies in [`operations/releases/smoke-20261119T235713Z/`](operations/releases/smoke-20261119T235713Z/), not new results. Every route, field and error code: [`docs/api-reference.md`](docs/api-reference.md).

The API key is passed on standard input as a curl config line, the way `scripts/smoke-test.sh` does it (`--config -`), so it never appears in argv, a file, or shell history. `smoke-test.sh` reads it from Secrets Manager into a shell variable; `BASE` is `http://` plus the Terraform output `alb_dns_name`.

```bash
# Synchronous query with an Idempotency-Key (benchmark B07, the smoke-test question)
printf 'header = "X-Api-Key: %s"\n' "$API_KEY" | curl --config - -sS -X POST "$BASE/v1/query" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: readme-example-1' \
  --data-binary '{"question": "Have Jack J. Dongarra and Yves Robert coauthored a paper?", "execution": "sync"}'

# The evidence the first claim cites
printf 'header = "X-Api-Key: %s"\n' "$API_KEY" | curl --config - -sS "$BASE/v1/evidence/ev-927665f3bbac87eb60685e50cfeb8f9b"

# The observable derivation of the answer
printf 'header = "X-Api-Key: %s"\n' "$API_KEY" | curl --config - -sS "$BASE/v1/explain/qry-c08ceb31eb51c6547cda9e522e3d1427"
```

Archived answer (excerpt of [`query.json`](operations/releases/smoke-20261119T235713Z/query.json)):

```json
{"queryId": "qry-c08ceb31eb51c6547cda9e522e3d1427", "mode": "synchronous",
 "answer": {"summary": "Jack J. Dongarra and Yves Robert coauthored 23 joint publications from 2007 to 2021. They published \"Mixing LU and QR factorization algorithms to design high-performance dense linear algebra solvers\" (2015).",
            "abstained": false,
            "verification": {"verified": 2, "partiallySupported": 0, "unsupported": 3, "conflicting": 0}},
 "claims": [{"claimId": "claim-1",
             "text": "Jack J. Dongarra and Yves Robert coauthored 23 joint publications from 2007 to 2021.",
             "evidenceIds": ["ev-927665f3bbac87eb60685e50cfeb8f9b"], "verificationStatus": "VERIFIED"}],
 "links": {"explain": "/v1/explain/qry-c08ceb31eb51c6547cda9e522e3d1427",
           "evidence": ["/v1/evidence/ev-927665f3bbac87eb60685e50cfeb8f9b", "/v1/evidence/ev-9edfbd8136ecefa015ada3457f655f52"]}}
```

Archived evidence (excerpt of [`evidence.json`](operations/releases/smoke-20261119T235713Z/evidence.json)): `evidenceType` `COAUTHOR_EDGE`, `sourceUri` `neptune:cae-94049b45586a9d41217209d71135c559`, facts `jointPublicationCount` 23, `firstCollaborationYear` 2007, `lastCollaborationYear` 2021, window `all-years`, and 23 supporting DBLP work IDs. The smoke record holds no explain response; its shape is the example [`explain-response.json`](modules/services/openapi/examples/explain-response.json). A request without `X-Api-Key` answered 401 `UNAUTHORIZED` (smoke check `auth-error`).

## Experiment reproduction

Experiments run only from committed manifests, through `scripts/run-experiment.sh` ([`scripts/README.md`](scripts/README.md) `run-experiment.sh`):

```bash
scripts/run-experiment.sh --manifest experiments/manifests/<manifest>.json
```

| Manifest | Experiment | Needs |
|---|---|---|
| [`exp-d-query-load-v1.json`](experiments/manifests/exp-d-query-load-v1.json) | D: query load on graph-evidence-api at concurrency 1, 2, 4, 8 | deployed runtime, PASS smoke |
| [`exp-d-scale-v1.json`](experiments/manifests/exp-d-scale-v1.json) | D-scale: one level at concurrency 8, scale-out and scale-in (CPU target 15 percent as an experiment setting) | deployed runtime, PASS smoke |
| [`exp-e-retrieval-modes-v1.json`](experiments/manifests/exp-e-retrieval-modes-v1.json) | E: fused, graph-only and vector-only retrieval on 10 benchmark questions | local runtime, live Bedrock only |
| [`exp-f-failure-recovery-v1.json`](experiments/manifests/exp-f-failure-recovery-v1.json) | F: poison message to the DLQ, idempotent async replay | deployed runtime, PASS smoke |

Experiment A and the size scaling (which replaced B, D-13) are the `run-bounded-pipeline.sh` runs recorded under [`operations/pipeline-runs/`](operations/pipeline-runs/); C is deferred (D-10). Raw results stay under [`experiments/results/`](experiments/results/) and are read-only after a run.

Regenerating the derived tables, from [`experiments/derived/README.md`](experiments/derived/README.md) and [`docs/evaluation.md`](docs/evaluation.md) section 7.1 (no AWS):

```bash
python3 -I experiments/analysis/derive-tables.py
python3 -I experiments/analysis/derive-tables.py --self-test
S=$(ls -td experiments/results/exp-e-retrieval-modes-v1/*/ | head -1)
python3 -I experiments/analysis/summarize-exp-e.py --raw "$S/raw" --out experiments/derived/exp-e
python3 -I experiments/analysis/summarize-exp-e.py --raw "$S/raw" --relevance labels/relevance-e-v1.csv --ratings labels/ratings-e-v1.csv --keys docs/agent-runs/phase-15/evidence/exp-e-keys --out experiments/derived/exp-e-labelled
python3 -I experiments/analysis/summarize-exp-e.py --self-test
```

Recorded regeneration: byte-identical rebuilds (`docs/agent-runs/phase-15/evidence/gate/regenerate-derived.txt`, `regenerate-labelled.txt`). Results and their sources: [`docs/evaluation.md`](docs/evaluation.md).

## Final report and video

- Final report: [`reports/final-report.md`](reports/final-report.md)
- Demonstration video: link added at submission

## Cleanup

`scripts/cleanup.sh` is run by the human only; agents are denied by permission rules and blocked by `guard-dangerous.sh`. Source: [`scripts/README.md`](scripts/README.md) `cleanup.sh`.

```bash
# First invocation: inventory, the plan printed in full, every resource destroyed and kept, the plan sha256. Nothing changes.
aws login   # immediately before (scripts/README.md "The flow")
TF_VAR_alarm_email=<address or empty> scripts/cleanup.sh --stage infra
# Second invocation: --plan-file must be the ABSOLUTE path
scripts/cleanup.sh --plan-file "$PWD/scratch/plans/cleanup-<stamp>-infra/plan.tfplan" --confirm <sha256>
# Final stage, after the instructor approves the evidence archive
TF_VAR_alarm_email=<address or empty> scripts/cleanup.sh --stage final --evidence-archive <location>
scripts/cleanup.sh --plan-file "$PWD/scratch/plans/cleanup-<stamp>-final/plan.tfplan" --confirm <sha256>
```

State now: the `runtime` stage ran on Nov 20, 2026 (plan `3352beee`, 89 destroyed, 43 base resources kept with reasons: network, data bucket, ECR, CodeBuild, EMR roles and security groups, log groups; [`operations/cleanup/cleanup-20261120T041658Z-runtime/`](operations/cleanup/cleanup-20261120T041658Z-runtime/record.json), `retained.tsv`). They match the window 1 plan ([`operations/plans/20261116T060222Z-d33ce8e8d507.md`](operations/plans/20261116T060222Z-d33ce8e8d507.md), `runtime_enabled` false). Planned final cleanup (`--stage infra`, then `--stage final`): **December 15, 2026**, after grading.

## Known limitations

From [`docs/evaluation.md`](docs/evaluation.md) and the phase gates; negative results are kept.

- **Deployed application not available.** The runtime was destroyed on Nov 20, 2026; only the 43 base resources remain. Query and evidence examples come from the archived window 3 smoke records (Phase 17 ruling 8).
- **P14-D8: Spark job 10 on EMR at 50 and 100 percent.** The Spark pipeline on EMR completed at 25 percent and ran out of memory in TemporalSummaryExportJob at 50 and 100 percent; option B (executor cores 2) is untested and deferred. The served graph is the local full-run store conversion `run-1d6a86491812ff94887270c1ab885e69` ([`docs/evaluation.md`](docs/evaluation.md) sections 5.2 and 7).
- **Verifier limits D18 to D20.** The claim verifier is lexical. D18 (major, open residual) and D19, D20 (minor) remain, and pending tests in the suite describe them (10 pending in all) ([`docs/evaluation.md`](docs/evaluation.md) section 4.1; [`docs/agent-runs/phase-11/human-gate.md`](docs/agent-runs/phase-11/human-gate.md)). "Verified" means verified by these rules.
- **graph-evidence-api is Neptune-bound.** At concurrency 8 the service used 20 to 27 percent CPU while Neptune (`db.t4g.medium`) used 80 to 93 percent, so the production 60 percent CPU target cannot be reached and window 2 showed no scale-out. Window 3 showed one scale-out and one scale-in only with a 15 percent target set as an experiment setting (reverted to 60 in `course.tfvars`); the second task did not demonstrably add throughput (sections 5.3, 5.4, 5.8).
- **File-backed vectors in experiment E.** E searched a local exact index over the exported vectors, not the S3 Vectors service, and ran the local runtime; recall at K is capped at the evidence-bundle size (section 3.3).
- **Open Neptune record count.** Window 3 loaded 34,052,689 records and window 2 reported 29,054,998 for the same input; the difference is not fully explained (section "Neptune record count").
- **Identity recall.** Researcher recall is 0.600 at precision 0.989 on the gold set: the LLM band stays UNRESOLVED because no live adjudication ran in the evaluated decisions (section 1).
- **Communities** come from label propagation that stopped at its 10-round cap without converging in every window; bridge measures inherit this (section 2; [`modules/spark/JOBS.md`](modules/spark/JOBS.md) job 6).
- **Scope cuts (D-10).** Approximate betweenness, experiment C, Step Functions, GitHub Actions, and a security scanner beyond `terraform validate` are not implemented; experiment B is replaced by size scaling; there is no OpenSearch tier (D-06).
- **HTTP-only ALB.** No certificate in the course window, so the API key travelled in clear text (Phase 12 ruling 11). The services image had 10 CRITICAL and 48 HIGH scan findings ([`docs/agent-runs/phase-14/human-gate.md`](docs/agent-runs/phase-14/human-gate.md)).
- **Single runs and one labeller.** Every experiment except D is one run, and the gold labels, relevance labels and ratings each come from one human who is also the architect ("Threats to validity").
- **Experiment A** has no raw results under `experiments/results/`; its record is the 25 percent pipeline run ([`experiments/derived/README.md`](experiments/derived/README.md)).

## Deliverable index

| ID | Deliverable | Where |
|---|---|---|
| D01 | Private Git repository | https://github.com/TRshaqOat/ScholarChain, tag `v1.0-submission` |
| D02 | Agentic constitution | [`CLAUDE.md`](CLAUDE.md), [`docs/agents.md`](docs/agents.md), [`.claude/agents/`](.claude/agents/), [`.claude/settings.json`](.claude/settings.json), [`.claude/hooks/`](.claude/hooks/), [`.claude/commands/`](.claude/commands/), [`.mcp.json`](.mcp.json), [`docs/agent-runs/tool-versions.md`](docs/agent-runs/tool-versions.md), [agent catalog](#agent-catalog) |
| D03 | Agent run evidence | [`docs/agent-runs/`](docs/agent-runs/) |
| D04 | Deterministic build | [`build.sbt`](build.sbt), [`project/`](project/), [`.scalafmt.conf`](.scalafmt.conf) |
| D05 | Dataset package | [`datasets/dataset-manifest.yaml`](datasets/dataset-manifest.yaml) |
| D06 | Hadoop package | [`modules/hadoop/JOBS.md`](modules/hadoop/JOBS.md) |
| D07 | Entity-resolution package | [`modules/entity-resolution/`](modules/entity-resolution/), gold set [`labels/README.md`](labels/README.md), evaluation [`docs/evaluation.md`](docs/evaluation.md) section 1 |
| D08 | Spark package | [`modules/spark/JOBS.md`](modules/spark/JOBS.md) |
| D09 | Storage package | [`modules/storage/STORAGE.md`](modules/storage/STORAGE.md) |
| D10 | Runtime collaboration-analysis package | [`modules/rag/RUNTIME.md`](modules/rag/RUNTIME.md), [`prompts/`](prompts/) |
| D11 | Microservices package | [`docs/api-reference.md`](docs/api-reference.md), [`modules/services/openapi.yaml`](modules/services/openapi.yaml) |
| D12 | Terraform package | [`infra/terraform/README.md`](infra/terraform/README.md), plans [`operations/plans/`](operations/plans/) |
| D13 | Reproducibility automation package | [`scripts/README.md`](scripts/README.md) |
| D14 | Deployment package | [`operations/releases/`](operations/releases/) (release manifest `operations/releases/release-manifest-final.md`, Phase 17 work order 5) |
| D15 | Experiment package | [`experiments/`](experiments/), [`load-tests/`](load-tests/) |
| D16 | Evaluation package | [`docs/evaluation.md`](docs/evaluation.md) |
| D17 | Documentation package | this README, [`docs/architecture.md`](docs/architecture.md), [`docs/data-lineage.md`](docs/data-lineage.md), [`docs/threat-model.md`](docs/threat-model.md), [`docs/service-limits.md`](docs/service-limits.md), [`docs/runbook.md`](docs/runbook.md), [`docs/evaluation.md`](docs/evaluation.md), [`docs/api-reference.md`](docs/api-reference.md), [`docs/demo-script.md`](docs/demo-script.md) |
| D18 | Final report | [`reports/final-report.md`](reports/final-report.md) |
| D19 | Demonstration video | Demonstration video: link added at submission |
| D20 | Cleanup evidence | [`operations/cleanup/`](operations/cleanup/) |

## Agent system

### Agent catalog

One row per file under [`.claude/agents/`](.claude/agents/). Owned paths are the rows of [`docs/agents.md`](docs/agents.md) (authoritative); tool fences are each file's `tools:` frontmatter. Every agent is invoked by the orchestrator (the main session) with a work order; agents never invoke each other. Each agent ends with a structured report that the orchestrator appends verbatim to `docs/agent-runs/phase-NN/agent-report.md` (or `review-report.md`, `test-report.md`, `repair-NN.md`).

**Standard report fields** (every agent): `STATUS` (COMPLETE, BLOCKED-ON or FAILED), `OWNER`, `WORK ORDER`, `FILES CREATED`, `FILES MODIFIED`, `COMMANDS RUN`, `TEST RESULTS`, `VALIDATION RESULTS`, `ASSUMPTIONS`, `RISKS`, `MANUAL ACTION REQUIRED`, `BLOCKED-ON OWNER`, `BLOCKED-ON EVIDENCE`, `NEXT GATE`. The last column lists the extra fields of each agent's report contract.

| Agent | Purpose | Owned paths | Tool fence | Prerequisites | Example orchestrator instruction | Verification command (gate) | Report fields beyond the standard |
|---|---|---|---|---|---|---|---|
| `factory-engineer` | Creates and maintains the agent constitution; prepares diffs for ratification, never self-ratifies | `CLAUDE.md`, `docs/agents.md`, `.claude/agents/**`, `.claude/hooks/**`, `.claude/commands/**`, `.claude/settings.json`, `.mcp.json` | Read, Grep, Glob, Write, Edit, Bash | jq, bash; no MCP | `Use the factory-engineer agent to add the residual-risk bullets of the work order to docs/agents.md and run the validator.` | `.claude/hooks/validate-agent-files.sh`; human ratification, commit, fresh session | FILES DELETED, CONSTITUTIONAL DIFF, PER-FILE AUTHORITY JUSTIFICATION, WIDENING CHANGES, FLOOR-TOUCHING CHANGES, AUDIT RESULTS, POST-RATIFICATION PROBES |
| `build-engineer` | sbt multi-project build, module graph, scopes, aliases, `markTestRun`, packaging | `build.sbt`, `project/**` (structure), `.scalafmt.conf`, `.gitignore`, `.sbtopts`, `.jvmopts`, `.dockerignore`, compose files, `packaging/**` | Read, Grep, Glob, Write, Edit, Bash | JDK 17, sbt 1.13.0 | `Use the build-engineer agent to add a .gitignore rule for load-tests/__pycache__/ and run sbt check.` | `sbt check` | FILES DELETED, MODULE GRAPH, VERSION LEDGER, SCOPES AND PACKAGING, ENVIRONMENT INPUTS, COMPATIBILITY RISKS |
| `domain-engineer` | Shared records, stable IDs, enums, wire contracts, configuration loader and files | `modules/core/**`, `config/**` | Read, Grep, Glob, Write, Edit, Bash | JDK 17, sbt | `Use the domain-engineer agent to add an environment override for a new configuration key with its contract test.` | `sbt check` (contract tests) | FILES DELETED, SCHEMA VERSIONS, STABLE ID FORMULAS, PAIR CANONICALIZATION RULE, CONFIGURATION PRECEDENCE, SAMPLE SERIALIZED RECORDS, AFFECTED CONSUMERS |
| `dataset-engineer` | Dataset manifest, bounded fetch and sampling scripts, samples, schemas, licenses, checksums | `datasets/**` | Read, Grep, Glob, Write, Edit, Bash, WebFetch | jq, python3, curl; `OPENALEX_API_KEY` for full runs only; `SCHOLARCHAIN_CONTACT_EMAIL` (required by `fetch_crossref.sh`) | `Use the dataset-engineer agent to update the Crossref and ORCID license fields of datasets/dataset-manifest.yaml and run the validator.` | `bash datasets/scripts/validate.sh` | FILES DELETED, DATASETS, SAMPLES, UNKNOWNS AND ESCALATIONS |
| `hadoop-engineer` | MapReduce jobs J01 to J10, input formats, counters, rejects, Hadoop tests | `modules/hadoop/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-docs (search, read) | JDK 17, sbt; `uvx` for aws-docs | `Use the hadoop-engineer agent to add a counter for empty author lists in J01 with its test.` | `sbt check` plus the [bounded local sample](#bounded-local-sample-run) Hadoop run | FILES DELETED, JOB GRAPH, KEY-VALUE CONTRACTS, PARTITIONING AND COMPRESSION, COUNTERS, REJECTS, TEAM AND BLOCK EXTREMES, OUTPUT PATHS |
| `entity-resolution-engineer` | Normalization, blocking keys, match features, policy and thresholds, adjudication, gold-set evaluation | `modules/entity-resolution/**` | Read, Grep, Glob, Write, Edit, Bash | JDK 17, sbt; read-only `labels/` in evaluation code only | `Use the entity-resolution-engineer agent to add a feature for affiliation overlap under a new policy version and report the gold-set evaluation.` | `sbt check`; gold-set evaluation (Phase 5 evidence `docs/agent-runs/phase-05/evidence/eval-er-v2/`) | FILES DELETED, POLICY VERSION, FEATURES AND RULES, THRESHOLDS, MODEL AND PROMPT VERSIONS, EVALUATION, REPRESENTATIVE ERRORS, LEAKAGE CHECK |
| `spark-engineer` | Canonical tables, coauthorship graph, metrics, components, paths, communities, bridges, k-core, exports | `modules/spark/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-docs (search, read) | JDK 17, sbt, at least 6 GB free heap; `uvx` for aws-docs | `Use the spark-engineer agent to add executor-cores settings for job 10 (P14-D8 option B) and run the Spark tests.` | `sbt check` plus the [bounded local sample](#bounded-local-sample-run) PipelineJob run | FILES DELETED, STAGE DAGS AND SHUFFLE BOUNDARIES, PARTITION COUNTS AND SKEW, GRAPH COUNTS, ALGORITHMS, METRIC DISTRIBUTIONS, TEMPORAL WINDOWS, INTEGRITY VIOLATIONS, OUTPUT PATHS AND LOAD MANIFESTS |
| `storage-engineer` | Ports, adapters and loaders for S3, Neptune, DynamoDB, S3 Vectors; query templates; embedding queue | `modules/storage/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-docs (search, read) | JDK 17, sbt; fakes by default, `--live` runs are human-run | `Use the storage-engineer agent to add a --neptune-load-id resume path to LoadNeptuneCli with its contract test.` | `sbt check` (contract tests); bounded live load by the human | FILES DELETED, SCHEMAS AND KEYS, METADATA FIELDS, BATCH SIZES AND RETRY POLICY, IDEMPOTENCY STRATEGY, SERVICE LIMITS, CLEANUP PROCEDURE |
| `rag-engineer` | Runtime Graph-RAG stages, allowlist, prompts and schemas, verification, audit, model clients | `modules/rag/**`, `prompts/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-docs (search, read) | JDK 17, sbt; recorded fixtures, no live Bedrock in tests | `Use the rag-engineer agent to add the retrieval modes FUSED, GRAPH_ONLY and VECTOR_ONLY as in Phase 15 work order 1.` | `sbt check` (benchmark suite with fixtures) | FILES DELETED, ALLOWED PLAN OPERATIONS, PROMPT, SCHEMA, AND MODEL VERSIONS, IDENTITY-RESOLUTION AND ABSTENTION BEHAVIOR, VERIFICATION RULES, BENCHMARK RESULTS, KNOWN FAILURE MODES |
| `service-engineer` | REST routes, models, typed errors, idempotency, auth, health and readiness, composition roots | `modules/services/**` | Read, Grep, Glob, Write, Edit, Bash | JDK 17, sbt | `Use the service-engineer agent to add an example file for NODE_NOT_FOUND to openapi/examples/ with its contract test.` | `sbt check` (API tests) | FILES DELETED, ROUTE TABLE, STATUS-CODE MAPPING, TIMEOUT, RETRY, AND CIRCUIT-BREAKER POLICY, AUTHENTICATION METHOD, SERVICE DEPENDENCY GRAPH |
| `test-engineer` | Adversarial, cross-module, integration and end-to-end tests; never edits production code | `tests/**` | Read, Grep, Glob, Write, Edit, Bash (writes `tests/**` only) | JDK 17, sbt | `Use the test-engineer agent to add adversarial tests for the neighbors cursor and report exact counts.` | `SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g tests/test` ([`tests/TESTS.md`](tests/TESTS.md)); `sbt check` | FILES DELETED, TESTS ADDED, DEFECTS EXPOSED, COVERAGE BY REQUIREMENT, RESIDUAL RISK |
| `code-reviewer` | Read-only adversarial review; APPROVE or REQUEST_CHANGES with owned findings | none | Read, Grep, Glob (no Write, Edit or Bash) | the orchestrator pastes `git status --porcelain --untracked-files=all`, `git diff` and test output into the work order | `Use the code-reviewer agent to review the Phase 17 documentation diff pasted below and return a verdict.` | none (no Bash); the gate is the verdict | VERDICT, SCOPE REVIEWED, FINDINGS, OWNERSHIP CHECK, FLOOR AND FENCE CHECK, EVIDENCE GAPS |
| `infra-engineer` | Terraform, operational scripts, optional workflows; plans, never applies | `infra/**`, `scripts/**`, `.github/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-api (`call_aws`, `suggest_aws_commands`), terraform (four Search tools, `RunCheckovScan`), aws-docs (search, read, recommend) | terraform 1.16.6, bash, jq; `SCHOLARCHAIN_AWS_READONLY_PROFILE` for aws-api; `uvx` | `Use the infra-engineer agent to add ?"stage:" to the load-stores.sh error pattern and update scripts/README.md.` | `terraform fmt -check -recursive infra/terraform`, `terraform -chdir=infra/terraform/envs/course validate`, `bash -n scripts/*.sh` (all in `scripts/check.sh`) | FILES DELETED, STATEFUL DESTROY OR REPLACE, PLAN SUMMARY, IAM CHANGES, HIGH-COST RESOURCES AND BOUNDS, SCRIPT GATES, SECURITY SCAN |
| `deploy-engineer` | Runs approved deploy, smoke, pipeline and in-flight rollback scripts; writes `operations/` records | `operations/**` (new records only) | Read, Grep, Glob, Write, Bash; MCP aws-api `call_aws` (no Edit) | `AWS_PROFILE`, terraform, aws CLI, jq, curl; approved plan checksum, clean tree, code-reviewer APPROVE (`/deploy`, `/run-pipeline`) | `Use the deploy-engineer agent to write operations/releases/release-manifest-final.md from the listed release records.` | `scripts/smoke-test.sh` after `scripts/deploy.sh` | APPROVAL REFERENCE, PRECONDITIONS, SCRIPTS EXECUTED, DEPLOYED STATE, PIPELINE RUN, SMOKE RESULTS, ROLLBACK ACTION |
| `experiment-engineer` | Experiment manifests, load generators, collectors, raw results, derived tables | `experiments/**`, `load-tests/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-api `call_aws` | python3, `AWS_PROFILE` for cloud runs; a committed manifest (`/experiment`) | `Use the experiment-engineer agent to add a manifest for experiment D-scale at concurrency 8 for 600 s with --dry-run checks.` | `scripts/run-experiment.sh --manifest <m> --dry-run`; `python3 -I experiments/analysis/derive-tables.py --self-test` | FILES DELETED, MANIFEST, RUN IDENTIFIERS, RAW RESULT LOCATIONS AND CHECKSUMS, OBSERVATIONS, ESTIMATED COST, FAILED OR ANOMALOUS RUNS, LIMITATIONS AND THREATS TO VALIDITY |
| `documentation-engineer` | README, docs and final report from committed evidence | `README.md`, `reports/**`, `docs/**` except `docs/agents.md`, `docs/agent-runs/**`, `docs/incidents/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-docs (search, read) | `uvx` for aws-docs | `Use the documentation-engineer agent to write docs/runbook.md from scripts/README.md and the window records.` | `.claude/hooks/validate-agent-files.sh` and `.claude/hooks/guard-dangerous.sh --self-test` (replayed); link check, evidence review | FILES DELETED, COMMANDS REPLAYED, EVIDENCE LINKS, LINK CHECK, UNIMPLEMENTED OR OPTIONAL COMPONENTS STATED |
| `incident-responder` | Read-only diagnosis; bounded recovery (rollback, task restart, desired count 1 to 4); incident reports | `docs/incidents/**` | Read, Grep, Glob, Write, Edit, Bash; MCP aws-api `call_aws`, aws-docs (search, read) | `AWS_PROFILE`, `SCHOLARCHAIN_AWS_READONLY_PROFILE`, a release record in `operations/releases/` (`/incident`, `/rollback`) | `Use the incident-responder agent to diagnose the failed smoke test of the current window and write the incident report.` | `scripts/smoke-test.sh` after recovery | INCIDENT ID, START AND END TIME (UTC), DETECTION SOURCE, USER-VISIBLE IMPACT, TRACE AND RUN IDS, TIMELINE, ROOT CAUSE, RECOVERY ACTION, DATA-INTEGRITY ASSESSMENT, FOLLOW-UP OWNER, PREVENTIVE TEST OR CONTROL, ESCALATIONS |
| `dependency-updater` | Version values only in the ledger, `sbt.version` and plugin versions; vulnerability reports | version-string values in `project/` (value-level co-owner with build-engineer) | Read, Grep, Glob, Edit, Bash, WebFetch, WebSearch (no Write) | JDK 17, sbt; network for release pages | `Use the dependency-updater agent to bump the uPickle patch version and run sbt check.` | `sbt check` | VERSION CHANGES, VULNERABILITIES, REVERTED BUMPS |

The orchestrator also routes the five project commands under [`.claude/commands/`](.claude/commands/): `/deploy` and `/run-pipeline` (deploy-engineer), `/experiment` (experiment-engineer), `/incident` and `/rollback` (incident-responder). Each states its gates.

### Reproducing the agentic workflow

The spec's eleven steps, for this repository. Exact natural-language wording is not expected to match the archive; artifacts, contracts, tests, gates and observable results are.

1. **Clone and check out the submitted tag.**
   ```bash
   git clone https://github.com/TRshaqOat/ScholarChain.git
   cd ScholarChain
   git checkout v1.0-submission
   ```
2. **Install Claude Code 2.1.329 and the toolchain** in [Required local software](#required-local-software). The model is not pinned in `.claude/settings.json` or in any agent file; the recorded sessions used the model selected in the client ([`docs/agent-runs/tool-versions.md`](docs/agent-runs/tool-versions.md)).
3. **Configure only the environment variables** that the replayed phase needs ([Environment variables](#environment-variables-names-only)). For MCP: `GITHUB_PERSONAL_ACCESS_TOKEN` (fine-grained, this repository only, read-only scopes) and, only for agents that use `aws-api`, `SCHOLARCHAIN_AWS_READONLY_PROFILE` naming a read-only role. Never put a secret in the repository or in a `.env` file; reads of `.env*`, `~/.aws/` and key files are denied.
4. **Start Claude Code from the repository root:** `claude`. Approve the project MCP servers when asked on first launch. At permission prompts choose the one-time allow, never "always allow" (it writes `.claude/settings.local.json`).
5. **Confirm what is loaded.** In the session: `/agents` lists the 18 project agents of the [catalog](#agent-catalog); `/memory` shows `CLAUDE.md` (which imports `docs/agents.md`); `/hooks` shows the PreToolUse, PostToolUse, Stop and SubagentStop hooks of `.claude/settings.json`; `/mcp` shows `github`, `aws-api`, `terraform` and `aws-docs`.
6. **Run the agent validation and the dangerous-command probe** (both read-only, both auto-allowed):
   ```bash
   .claude/hooks/validate-agent-files.sh          # expected last line: validate-agent-files: 231/231 checks passed
   .claude/hooks/guard-dangerous.sh --self-test   # expected last line: guard-dangerous self-test: 304/304 passed
   ```
   Live probe: ask the orchestrator to run `echo 'terraform destroy'`. The expected result is a hook block, not execution: `guard-dangerous.sh` exits 2 with the rule `[terraform-apply-destroy]` (recorded in [`spec/architect-decisions.md`](spec/architect-decisions.md) D-11). A model refusal is inconclusive; only a hook or permission block counts.
7. **Replay one phase in a fresh session.** Run `/clear` or start a new `claude`, copy the exact work order from `docs/agent-runs/phase-NN/prompt.md`, and tell the orchestrator `Use the <owner> agent to ...` with that work order.
8. **Invoke review and test agents exactly where the phase record has them:** the code-reviewer when the folder holds `review-report.md`, the test-engineer when it holds `test-report.md` (Phase 11).
9. **Route every `BLOCKED-ON`** through the orchestrator to the owner that `docs/agents.md` names; never edit another owner's files directly (`guard-paths.sh` blocks such file-tool writes).
10. **Compare with the archive:** the reproduced report against `agent-report.md` (and `review-report.md`, `repair-NN.md`), test counts against the recorded counts, and the generated diff against the accepted commit in `commit.txt` and `human-gate.md`.
11. **Use a fresh session before the next phase**, so that the committed repository state, not conversation context, is the next starting point.

**Verifying external prerequisites.** Expected values are those recorded in [`docs/agent-runs/tool-versions.md`](docs/agent-runs/tool-versions.md):

| Prerequisite | How to check |
|---|---|
| Claude Code | `claude --version` (2.1.329) |
| JDK, sbt | `java -version` (17.0.21); `sbt --version` in the repository root (1.13.0) |
| Terraform, AWS CLI | `terraform version` (1.16.6); `aws --version` (2.38.3) |
| jq, Python, git, uv | `jq --version`, `python3 --version`, `git --version`, `uv --version` |
| MCP servers | `/mcp` in the session shows each server connected. `aws-api` connects only when `SCHOLARCHAIN_AWS_READONLY_PROFILE` names a working read-only profile; Phase 17 work order 3 records that it can fail to connect, and live AWS reads are then human-run. |
| AWS read access (cloud steps only) | `aws sts get-caller-identity --profile <profile>` shows the expected account and the profile works; Region `us-east-2` |
| Bedrock model access (experiment E only) | the Bedrock console's model access page for the two model IDs in [AWS prerequisites](#aws-prerequisites) |

**Bounded reproduction exercise (no AWS).** Replay Phase 15 work order 1 (rag-engineer: the retrieval modes FUSED, GRAPH_ONLY and VECTOR_ONLY for experiment E). Its work order runs no git, aws, terraform or Bedrock call (Phase 15 ruling 4) and is tested with fakes only.

```bash
git checkout -b replay-phase-15-wo1 aa30f5a    # the commit that added the work order
claude                                         # a fresh session at the repository root
```

1. In the session, paste Phase 15 rulings 1 and 4 and the section "Work order 1 (rag-engineer): retrieval modes for experiment E (Phase 14 ruling 31)" from [`docs/agent-runs/phase-15/prompt.md`](docs/agent-runs/phase-15/prompt.md), and tell the orchestrator: `Use the rag-engineer agent to carry out this work order.`
2. When the agent reports, run `sbt check` yourself (the Stop hook also requires a current test marker).
3. Compare the report with the "Work order 1 (rag-engineer)" section of [`docs/agent-runs/phase-15/agent-report.md`](docs/agent-runs/phase-15/agent-report.md), and the generated diff with the archived one:
   ```bash
   git diff aa30f5a c542806 -- modules/rag
   ```
   Commit `c542806` also contains repair-01 part C (code review 1 finding m7: a `RUNTIME.md` section 14 note on the retrieval-mode run-ID input), so that one documentation change is expected in the archived diff and not in the replay.
4. Optionally invoke the code-reviewer on the replayed diff, as the phase record did (`docs/agent-runs/phase-15/review-report.md`).

### What `.claude/` and `docs/agent-runs/` contain

- [`.claude/agents/`](.claude/agents/): the 18 subagent role files (frontmatter `name`, `description`, `tools`; laws, procedure, refusal boundary, report contract).
- [`.claude/hooks/`](.claude/hooks/): `guard-dangerous.sh`, `guard-paths.sh`, `format-scala.sh`, `verify-tests-ran.sh`, `validate-agent-files.sh`. Each header comment states what it checks; `docs/agents.md` "Hook limitations" states what it does not.
- [`.claude/commands/`](.claude/commands/): `deploy.md`, `rollback.md`, `incident.md`, `run-pipeline.md`, `experiment.md`.
- [`.claude/settings.json`](.claude/settings.json): allow, ask and deny rules and the hook registrations; bypass mode disabled. `.claude/.last-test-run` is written only by `sbt check` (`markTestRun`).
- [`docs/agent-runs/`](docs/agent-runs/): one folder per phase with `prompt.md` (the exact work order and human rulings), `agent-report.md`, `review-report.md`, `test-report.md`, `repair-NN.md`, `human-gate.md`, `commit.txt`, and `evidence/`; plus [`tool-versions.md`](docs/agent-runs/tool-versions.md) and [`factory-metrics.md`](docs/agent-runs/factory-metrics.md). Format: [`docs/agent-runs/README.md`](docs/agent-runs/README.md). Hidden model reasoning is never stored.

**The orchestrator** is the main Claude Code session, not a subagent file. That is why `.claude/agents/` holds 18 files and none is named orchestrator ([`docs/agents.md`](docs/agents.md) "Topology"; spec "The reference agentic control model").

**Merged phases** (D-10): 7+8 (storage and embeddings) in `phase-07/`, 12+13 (Terraform and scripts) in `phase-12/`, 15+16 (experiments and evaluation) in `phase-15/`. `phase-08/`, `phase-13/` and `phase-16/` each hold a README stub pointing to the merged folder. The test-engineer ran once, in Phase 11.

### Records and autograder notes

These explain findings of the course autograder, as decided in Phase 17 rulings 7 and 14 ([`docs/agent-runs/phase-17/prompt.md`](docs/agent-runs/phase-17/prompt.md)).

- **P01 (phase folders).** The merged-phase stubs above.
- **E01, E03, E06 (phase records).** The historical records stay as committed. Phases 00 to 14 recorded `commit.txt` in a follow-up commit through the `phase_commit` helper, which wrote the hash of the gate commit itself; a gate file cannot state its own commit's hash, which caused the E06 mismatches. From the Phase 15 gate on, `commit.txt` is written by hand and names the last reviewed and checked commit ([`docs/agent-runs/README.md`](docs/agent-runs/README.md); [`docs/agent-runs/phase-15/human-gate.md`](docs/agent-runs/phase-15/human-gate.md) "Deviations").
- **C03 (tutorial role names).** `validate-agent-files.sh` rejects the tutorial-only roles `feature-implementer` and `db-migrator`. That check comes from the Phase 1 work order, and its comment and message name the reference tutorial's TaskForge example. Neither agent exists in ScholarChain ([`docs/agents.md`](docs/agents.md) "Agents not in this system").
- **F07 (`.mcp.json` token).** `.mcp.json` holds `Bearer ${GITHUB_PERSONAL_ACCESS_TOKEN}`, an environment-variable reference with no secret value; the validator checks that credential fields reference environment variables only.
- **O01 (orchestrator).** The orchestrator is the main session, with no agent file (above).
- **O02 and E05 (ownership).** `docs/agent-runs/**`, `spec/` and `labels/` are human-owned. The dependency-updater is a value-level co-owner of `project/` with the build-engineer (it changes version strings only). The infra-engineer authors every script; the deploy-engineer, the experiment-engineer, the incident-responder or the human runs them, as the [automation table](#automation-scripts-and-who-runs-them) shows.
- **K03 (`course.tfvars`).** [`infra/terraform/envs/course/course.tfvars`](infra/terraform/envs/course/course.tfvars) holds no secrets and no personal data: the API key is set in Secrets Manager after apply and the alarm email is passed as `TF_VAR_alarm_email` (its header comment).
- **V01 (hardcoded values).** Model IDs and storage constants come from versioned configuration (`scholarchain.conf`, `course.tfvars`). The ARN in `load-tests/exp-d-query-load.py` is a self-test vector that uses the documentation example account `111122223333`.
- **A01 (driver collects).** Every Spark `.collect()` is bounded and carries a comment saying by what, in `JobRun.scala`, `Communities.scala`, `CommunityPathJob.scala` and `BenchmarkPathJob.scala` (under `modules/spark/src/main/scala/scholarchain/spark/`).
- **DATA-08 (dataset validation).** `scripts/check.sh` runs `bash datasets/scripts/validate.sh` as a gate.
- **DATA-03 (licenses).** Assigned to Phase 17 work order 4 (dataset-engineer: Crossref and ORCID license fields of the dataset manifest).
- **FACTORY-09 and FACTORY-10.** The [agent catalog](#agent-catalog) and [Reproducing the agentic workflow](#reproducing-the-agentic-workflow) above.
- **K01 (flagged lines).** The two lines flagged in `docs/agent-runs/phase-10/evidence/code-review-1/change-set.txt` (change-set lines 9549 and 9695) are test fixtures, not credentials: line 315 of `modules/services/src/test/scala/scholarchain/services/QueryApiSpec.scala` and line 22 of `modules/services/src/test/scala/scholarchain/services/ServiceFixtures.scala`, the API-key value the service tests use. Both files appear in that change set under "UNTRACKED FILE" headers and are committed under `src/test/`. Neither value has the shape of an AWS access key or session token. No change (Phase 17 ruling 14).

### Provenance and AI assistance

The repository was generated by the agentic workflow above; every phase gate records per-file provenance and manual changes. Outside the repository, the human used Claude in a web chat for work orders, rulings, gate text and planning (all reviewed by the human), Codex as a terminal assistant on Nov 10, 2026, the course autograder runs of Nov 19, a display-only labelling page kept outside the repository, and a Claude-drafted, human-reviewed demonstration script; the benchmark questions were drafted with Claude and reviewed by the human. Gold labels, relevance labels and answer ratings are the human's only ([`docs/agent-runs/phase-15/human-gate.md`](docs/agent-runs/phase-15/human-gate.md) "External assistance" and "Human-only work"; [`docs/evaluation.md`](docs/evaluation.md) section 6.1). The full disclosure is in the final report.
