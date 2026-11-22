# ScholarChain final report

Achyut Patel (individual project), CS441 course project. Repository and identity: [`README.md`](../README.md). Every number below names its source; most figures are in [`docs/evaluation.md`](../docs/evaluation.md), which this report links instead of repeating. Negative results are kept. "Phase N ruling M" means `docs/agent-runs/phase-NN/prompt.md`, ruling M.

## 1. Problem and motivation

Counting papers by a literal author string cannot say who has the broadest collaboration network, how two researchers connect through intermediate coauthors, or who bridges otherwise separate communities. Those answers need researcher identity resolution, coauthor-pair expansion (a paper with n authors gives n(n-1)/2 pairs), graph construction and auditable path evidence ([`spec/ScholarChain-spec.md`](../spec/ScholarChain-spec.md) "Overview and motivation"). ScholarChain turns the DBLP XML dump and three enrichment datasets into a temporal weighted coauthorship graph and answers natural-language questions from that graph with claims that cite evidence IDs and pass rule-based verification. The second problem is the build process itself: the whole repository was produced by a controlled Claude Code agentic workflow with one writer per artifact, independent review, executable safety hooks and human gates.

## 2. Research questions and metrics

The three questions are collaboration breadth and team size, connectivity, and bridges between communities. For every researcher and time window the Spark pipeline stores P_r (publications), C_r (distinct coauthors, breadth), T_r (coauthor occurrences), A_r (average coauthors per paper), M_r (maximum coauthors on one paper, with the responsible work IDs) and W_r (weighted degree, intensity) as separate columns, never one score; plus component ID and size, label-propagation community, three bridge measures (cross-community degree, communities connected, participation coefficient) and k-core coreness ([`modules/spark/JOBS.md`](../modules/spark/JOBS.md) jobs 3 to 8). Edges record joint publication count, first and last year and the complete supporting work IDs. Windows: `all-years` (2000 to 2026), `2000-2014`, `2015-2026` (D-10). Approximate betweenness was not implemented (D-10); the bridge measures are local, one-hop measures, and their limits are stated in JOBS.md job 7.

## 3. Datasets and licenses

DBLP (snapshot 2026-10-03) is the seed; OpenAlex, Crossref and ORCID (snapshots 2026-11-03) are DOI-driven subsets of a 46-venue DBLP subset of about 111,417 works ([`docs/agent-runs/phase-03/human-gate.md`](../docs/agent-runs/phase-03/human-gate.md); D-10, D-13). The full local run and the EMR 100 percent run read 110,867 DBLP works (Phase 4 gate; Phase 14 gate). The instructor approved bounded DOI-driven subsets and fewer than 1 million coauthor edges, with scaling shown at different sizes (D-13). Real ORCID records are not committed; the samples are ORCID's fictional test record and labelled synthetic records (Phase 3 gate). Licenses, checksums, selection rules and known limitations are in [`datasets/dataset-manifest.yaml`](../datasets/dataset-manifest.yaml); Phase 17 work order 4 replaces the "UNVERIFIED" Crossref and ORCID license statements there. Known data limits: about 5,171 subset works without a DOI get no OpenAlex or Crossref enrichment (Phase 3 gate), and ORCID does not scale with the size fractions (64.9 and 80.0 percent at 25 and 50 percent; Phase 12 ruling 17).

## 4. Agentic software factory

The main Claude Code session is the orchestrator; it has no agent file. It delegates each work order to one of 18 subagents under [`.claude/agents/`](../.claude/agents/), routes `BLOCKED-ON` results and review findings, and assembles reports into `docs/agent-runs/phase-NN/`. Subagents never call one another. The rules are [`CLAUDE.md`](../CLAUDE.md) and [`docs/agents.md`](../docs/agents.md); the build-time diagram is [`docs/architecture.md`](../docs/architecture.md) section 1, and the agent catalog and replay steps are in the README.

## 5. Authority and ownership

Every path has exactly one writing agent ([`docs/agents.md`](../docs/agents.md) "Artifact ownership map"). The code-reviewer has no write tool; the test-engineer writes only under `tests/`; the infra-engineer authors scripts and never runs them against AWS; the deploy-engineer, experiment-engineer, incident-responder or human runs them; `cleanup.sh` is human-only. `spec/`, `labels/` and `docs/agent-runs/` are human-owned. Enforcement has three layers: agent instructions, permission rules in `.claude/settings.json`, and the hooks (`guard-dangerous.sh`, `guard-paths.sh`, `format-scala.sh`, `verify-tests-ran.sh`, `validate-agent-files.sh`). What the hooks do not enforce is stated in `docs/agents.md` "Hook limitations".

## 6. Human gates and repair loops

Each phase ran as work order, agent report, code review, at most one repair round, re-review and a human gate with the accepted commit. The one-repair-round rule had human exceptions: Phase 9 ruling 29, Phase 11 ruling 20, Phase 12 rulings 22 and 23, Phase 14 rulings 14, 19 and 33, Phase 15 rulings 12 and 18. Phases 5, 9 and 11 were accepted by human escalation without a final `APPROVE` (their gates record why). Constitutional changes needed human ratification, a commit and a fresh session (Phase 1 gate).

## 7. Hadoop pipeline

Hadoop MapReduce does the record-independent work: J01 DBLP extraction with an input format that never splits a record, J02 normalization of OpenAlex, Crossref and ORCID, J03+J04 exact identifiers and conflicts, J05 blocking in up to three passes with a maximum block size, J06 candidate join, J10 map-only match decisions, J07+J08 publication teams and pair expansion, J09 Spark-ready export. Every job has counters and a `rejects-v1/` output, and every record carries provenance ([`modules/hadoop/JOBS.md`](../modules/hadoop/JOBS.md)). The full local run found a largest team of 287 authors (41,041 pairs) and 1,019,535 pair candidates (Phase 4 gate). On EMR, Hadoop completed at 25, 50 and 100 percent in 17.2, 27.6 and 53.3 minutes (Phase 14 gate).

## 8. Entity resolution

Normalization, blocking keys, features and the versioned match policy (`er-v2`) are pure functions in [`modules/entity-resolution/`](../modules/entity-resolution/) that the Hadoop jobs apply. Outcomes are ACCEPTED, REJECTED, UNRESOLVED and CONFLICT; exact-identifier conflicts never merge. On 310 human gold labels, researcher precision is 0.989 (90/91) and recall 0.600 (90/150); 54 of the 60 false negatives are deferrals to UNRESOLVED, mostly the LLM band, because no live adjudication is in the evaluated decisions ([`docs/agent-runs/phase-05/human-gate.md`](../docs/agent-runs/phase-05/human-gate.md); [`docs/evaluation.md`](../docs/evaluation.md) section 1). Thresholds were not tuned on the gold set (Phase 5 gate). A bounded live adjudication demonstration ran in Phase 9 and is not evaluated against gold labels (Phase 9 gate).

## 9. Spark graph and analytics

Spark consumes only Hadoop outputs. `PipelineJob` runs four stages: canonical researchers and the coauthorship graph (jobs 1, 2), breadth, team size, components and k-core (3, 4, 8), paths, communities and bridges (5, 6, 7), and temporal comparison, summaries and the load manifest (9, 10, 11). Algorithms are DataFrame iterations with checkpoints; nothing collects the graph to the driver ([`modules/spark/JOBS.md`](../modules/spark/JOBS.md)). The local full run produced 259,057 canonical researchers and, in the `all-years` window, 191,318 graph researchers, 787,726 coauthor edges, 16,091 components and 38,034 communities; label propagation did not converge within its 10-round cap in any window (Phase 6 gate). On EMR, Spark completed at 25 percent and ran out of memory in job 10 at 50 and 100 percent (P14-D8, open), so the served graph is the local full run.

## 10. Storage architecture

Each store has one role ([`modules/storage/STORAGE.md`](../modules/storage/STORAGE.md)): S3 holds the authoritative text, evidence objects and write-once run manifests; Neptune holds the graph for bounded path and neighbor queries through fixed parameterized openCypher templates; DynamoDB holds per-window metrics, rankings, the entity-link cache, query sessions, evidence bundles and service state; S3 Vectors holds embeddings with compact metadata that points to S3 text. The Phase 7 store files hold 511,920 Neptune vertices and 3,123,311 edges, and the validator traced every coauthor edge to authored works with 0 violations ([`docs/agent-runs/phase-07/human-gate.md`](../docs/agent-runs/phase-07/human-gate.md)). The full chain from source record to answer claim, with one traced example, is [`docs/data-lineage.md`](../docs/data-lineage.md).

## 11. S3 Vectors and the OpenSearch option

Five indexes (researcher profiles, communities, paper metadata summaries, paths, topic and institution summaries) hold 13,023 Titan V2 vectors, 1024 dimensions, normalized (Phase 7 gate). Vector similarity only selects descriptive text: a claim that rests only on semantic items cannot be verified. No OpenSearch tier was built (D-06). The paper index holds metadata summaries, not abstracts (Phase 7 gate).

## 12. Runtime agents

Ten stages: query planning (Claude Haiku 4.5, validated against an operation allowlist, rule-planner fallback), researcher resolution (`dominant-name-v1`, abstains when ambiguous), collaboration metrics, path finding and community analysis (deterministic store reads), semantic context (one Titan embedding), evidence building, synthesis (Haiku), rule-based claim verification (`verifier-v8`) and a write-once audit ([`modules/rag/RUNTIME.md`](../modules/rag/RUNTIME.md)). Prompts are versioned (`planner-v2`, `synthesis-v3`).

## 13. Microservices and API

query-api, graph-evidence-api and worker are three Scala 3 services on ECS Fargate (ARM64) behind an ALB, with an SQS job queue and dead-letter queue for asynchronous queries. Routes are thin; the runtime is in `modules/rag`. Every route, error code and header is in [`docs/api-reference.md`](../docs/api-reference.md). The window 3 smoke test passed all six checks ([`operations/releases/smoke-20261119T235713Z.json`](../operations/releases/smoke-20261119T235713Z.json)).

## 14. Terraform architecture

All resources come from Terraform 1.16.6 with provider `hashicorp/aws` 6.28.0 in two stages: `runtime_enabled` false (network, data bucket, ECR, CodeBuild, EMR roles and groups, log groups; 43 resources) and true (Neptune, DynamoDB, S3 Vectors, SQS, the secret, ECS, ALB, alarms; 89 more) ([`infra/terraform/README.md`](../infra/terraform/README.md); Phase 12 gate; Phase 15 ruling 10). EMR clusters are ephemeral, launched per run by a script. The runtime diagram is [`docs/architecture.md`](../docs/architecture.md) section 2.

## 15. Build, deployment, experiment and reproduction automation

`sbt check` is the definition of done; `scripts/check.sh` runs every non-cloud gate and passed on the accepted Phase 15 tree with 845 tests passed, 0 failed, 10 pending ([`docs/agent-runs/phase-15/human-gate.md`](../docs/agent-runs/phase-15/human-gate.md)). `plan.sh`, `deploy.sh` (only a plan whose sha256 the human signed), `package.sh`, `run-bounded-pipeline.sh`, `load-stores.sh`, `smoke-test.sh`, `rollback.sh`, `run-experiment.sh` (only committed manifests), `archive-evidence.sh` and `cleanup.sh` are documented in [`scripts/README.md`](../scripts/README.md); operating procedures are in [`docs/runbook.md`](../docs/runbook.md).

## 16. Security and threat model

[`docs/threat-model.md`](../docs/threat-model.md) covers prompt injection, data poisoning, credential exposure, over-permission, denial of service, destructive commands and provenance tampering, plus the HTTP-only ALB, the unconditioned ListBucket grants, the ECR scan findings (10 CRITICAL, 48 HIGH; Phase 14 ruling 25), the SSE-C drift, session tokens in DEBUG logs and the API key handling.

## 17. Elasticity experiments

- **A** (baseline) is the 25 percent EMR run at `deef295`: cluster 36.3 minutes, Hadoop 17.2, Spark 13.3 (Phase 14 ruling 22; [`experiments/derived/exp-a/README.md`](../experiments/derived/exp-a/README.md)). It has no raw results under `experiments/results/`.
- **Size scaling** (replaces B, D-13): Hadoop 17.2, 27.6 and 53.3 minutes for 27,993, 55,598 and 110,867 DBLP works; Spark completes only at 25 percent (Phase 14 ruling 22; [`experiments/derived/size-scaling/README.md`](../experiments/derived/size-scaling/README.md)).
- **D** (window 2): attempt 1 stopped at its preflight with a 504 (cold Neptune); attempt 2 ran concurrency 1, 2, 4 and 8 with 0 errors and no scale-out; at concurrency 8, 35.7 requests per second and p95 673.5 ms (Phase 14 rulings 46 and 47). graph-evidence-api used 20 to 27 percent CPU while Neptune reached 80 to 93 percent, so the 60 percent target could not be reached (Phase 15 ruling 2; [`operations/deployments/window-2/post-exp-d-cloudwatch.txt`](../operations/deployments/window-2/post-exp-d-cloudwatch.txt)).
- **D-scale** (window 3, CPU target 15 percent as a declared experiment setting): 21,962 requests in 600 s, 0 errors, p95 683 ms; desired count 1 to 2 at 18:24:01 CST and back to 1 at 18:46:39 CST (Phase 15 ruling 10(e)). Neptune stayed at about 97 percent CPU, so the throughput difference is not attributed to the second task (Phase 15 rulings 12(e) and 13(c)).
- **E** (retrieval modes, local runtime with live Bedrock, file-backed vectors): see section 18.
- **F** (failure and recovery, window 2): a poison message was received 3 times and redriven to the DLQ; replays returned the same job (Phase 14 ruling 46).
- **C** was not run (D-10).

Every experiment except D is a single run, so variability was not measured ([`docs/evaluation.md`](../docs/evaluation.md) "Rules used in this document").

## 18. Retrieval and answer evaluation

Experiment E compared fused, graph-only and vector-only retrieval on 10 benchmark questions. Recall at 5, recall at 10 and MRR: fused 0.787, 0.8241, 0.4926; graph-only 0.5648, 0.6019, 0.4556; vector-only 0.3704, 0.3704, 0.6111. Mean usefulness and clarity: fused 4.4 and 3.6, graph-only 4.6 and 3.6, vector-only 1.9 and 1.9 (Phase 15 gate; [`experiments/derived/exp-e-labelled/exp-e-summary.md`](../experiments/derived/exp-e-labelled/exp-e-summary.md)). Recall at K is capped at the bundle size, and vector-only abstains by design because the verifier requires graph evidence (Phase 15 rulings 6 and 16). On the 30-question live benchmark at the end of Phase 11, 26 questions were answered, with 87 claims verified and 12 removed, all 12 by design ([`docs/agent-runs/phase-11/human-gate.md`](../docs/agent-runs/phase-11/human-gate.md)). Details: [`docs/evaluation.md`](../docs/evaluation.md) sections 3 and 4.

## 19. Agentic factory evaluation

First-pass acceptance by agent, `BLOCKED-ON` events, review findings by severity, repair rounds and manual interventions are evaluated in [`docs/evaluation.md`](../docs/evaluation.md) section 6 from [`docs/agent-runs/factory-metrics.md`](../docs/agent-runs/factory-metrics.md). Cells marked `?` in that log are missing data.

### 19.1 Provenance of artifacts (the four-way distinction)

Built from the human gates (`docs/agent-runs/phase-NN/human-gate.md`) and the factory-metrics log.

| Class | Artifacts | Record |
|---|---|---|
| Agent-generated and accepted without change | `.claude/agents/factory-engineer.md` (Phase 0); `build.sbt`, `project/build.properties`, `project/plugins.sbt`, `.scalafmt.conf`, `.gitignore` (Phase 2); `modules/core/**` and the build changes of Phase 3; `modules/hadoop/**`, `modules/entity-resolution/**` and the `modules/core/**` changes of Phase 4; the Phase 6 `build.sbt` change. From Phase 7 on, the gates record each module as "agent-generated, accepted after review"; per invocation, the "first-pass accepted" column of the factory-metrics log says which reports were accepted without a repair. | gates of Phases 0, 2, 3, 4, 6 and 7 to 15 |
| Agent-generated and repaired after another agent's finding | Under the one-writer rule the owner makes the repair; the finding comes from the code-reviewer or the test-engineer. Repair records: Phase 1 `repair-01` to `repair-05` (control plane); Phase 5 `repair-01`, `repair-02`; Phase 6 `repair-01`; Phase 7 `repair-01`; Phase 9 `repair-01`, `repair-02`; Phase 10 `repair-01`; Phase 11 `repair-01`, `repair-02`; Phase 12 `repair-01` to `repair-03`; Phase 14 `repair-01` to `repair-04`; Phase 15 `repair-01` to `repair-03`. One file was changed by a second agent: `project/Dependencies.scala` (build-engineer) had its version values changed by the dependency-updater (Phase 2, value-level ownership). | `docs/agent-runs/phase-NN/repair-NN.md` and the gates |
| Manually edited (each disclosed edit) | Phase 1: no control-plane file edited by hand; the human built `repair-05.patch` with `diff -u`, renamed `test-report` to `test-report.md`, and deleted the probe artifacts. Phase 2: the human created `labels/.gitkeep`. Phase 6: an indentation edit to `prompt.md` (commit `84d90b0`). Phase 12: the human deleted the empty `modules/secrets/` and `datasets/scripts/__pycache__/`. Phase 14: the human set the window 2 inputs in `infra/terraform/envs/course/course.tfvars` (ruling 24, infra-engineer not invoked); redacted a session token in three `operations/` files (ruling 41); ran local, uncommitted copies of `load-stores.sh`, with diffs saved and the copies removed (rulings 40, 41); changed the Neptune instance class outside Terraform and restored it (rulings 42, 43); appended the work order 8 report. Phase 15: the human removed `load-tests/__pycache__` (ruling 5); the orchestrator removed a two-space indent from the code review 6 report (ruling 19). Phase 17: the human updated `docs/agent-runs/README.md` (ruling 7) and wrote the merged-phase stubs (ruling 1). Open discrepancy: the "manual edits" column of the factory-metrics log holds non-zero values in six rows (Phase 9 rag-engineer repair-01 B; Phase 11 test-engineer work order 3 and domain-engineer repair-01 D; Phase 12 storage-engineer work order 1 and infra-engineer work orders 2 and 3), while those phases' gates record no manual edits to agent-owned files; the log does not say what the values count. | gates; `factory-metrics.md` |
| Human-authored | `spec/ScholarChain-spec.md` (course specification) and `spec/architect-decisions.md`; every `prompt.md` (work orders and rulings), every `human-gate.md` and `commit.txt`, the Phase 1 `test-report.md`, `docs/agent-runs/README.md`, `tool-versions.md`, `factory-metrics.md`, the evidence copies; the gold labels (310 rows, Phase 5), the experiment E relevance labels (101 items) and answer ratings (30 answers) with their rating anchors (Phase 15 gate; files named in [`labels/README.md`](../labels/README.md), `labels/relevance-e-v1.csv`, `labels/ratings-e-v1.csv`); the decision to accept each phase; every cloud-window script run, all cost reads and all commits; the Phase 17 demo recording notes. | gates; Phase 15 gate "Human-only work" |

### 19.2 AI assistance outside the repository workflow

- Claude in a web chat: architect notes (`spec/architect-decisions.md` states it was drafted with Claude and reviewed by the architect), work orders, rulings, gate text and planning, all reviewed by the human (Phase 0 and Phase 15 gates).
- Codex (OpenAI) as a Terminal assistant on Nov 10, 2026, for a baseline `sbt check`, the AWS cost export and the adjudication tally and policy note; the human checked the note on Nov 11 (Phase 9 gate).
- The course autograder runs (V1 to V3) of Nov 19, 2026; the Claude Code CLI was updated to 2.1.329 to run it (Phase 15 gate, ruling 10).
- Display-only labelling tools kept outside the repository, which never suggest a label or score: a local display tool for the Phase 5 gold labels (Phase 5 gate) and the experiment E labeller built by Claude in the web chat on Nov 20 (Phase 15 gate and ruling 16, with its sha256).
- Claude-drafted, human-reviewed content: the 30 benchmark questions (Phase 6 gate; Phase 15 gate) and the demonstration script ([`docs/demo-script.md`](../docs/demo-script.md); Phase 17 ruling 5).
- The Ponytail plugin (v5.3.2, user scope) was active for implementation agents; the code-reviewer, test-engineer, factory-engineer, deploy-engineer and incident-responder ran without it (D-11).

## 20. Cost analysis

Month to date before credits: 16.84 USD, read on Nov 21, 2026 at 5:00 PM CST, under the 25 USD target ([`docs/agent-runs/phase-15/evidence/gate/cost-explorer-20261121.txt`](../docs/agent-runs/phase-15/evidence/gate/cost-explorer-20261121.txt); Phase 15 ruling 16 point 5). By window (UTC billing days, same ruling): window 1 (EMR) about 3.58 USD against the cluster-timeline estimate of 3.32 USD; window 2 about 4.63 USD plus part of 0.33 USD of shared lines; window 3 2.98 USD on Nov 19 plus part of 1.16 USD on Nov 20, which experiment E shares (E's Haiku cost about 0.28 USD by token counts at an assumed price); Haiku checks on Nov 14 0.46 USD; Nov 1 to 13 and lines under 0.01 USD about 3.70 USD. The largest drivers were EC2 compute for EMR in window 1, ECS in window 2 and Neptune in window 3 (the daily lines in the Cost Explorer file). Nov 20 and 21 may be incomplete. Only the 43 base resources remain.

## 21. Failures and incident analysis

Window 1 defects (Phase 14 rulings 11 to 22):

| ID | Failure | Outcome |
|---|---|---|
| P14-D1 | `deploy.sh` passed a relative `--plan-file` to terraform running with `-chdir` | fixed in repair-01; `cleanup.sh` still needs the absolute path |
| P14-D2 | `package.sh` recorded the ECR scan as raw CLI text before it finished | fixed in repair-01 (JSON) |
| P14-D3 | a failed `create-cluster` exited with the CLI's code 254 and wrote no `run.json` | fixed in repair-01 |
| P14-D4 | `emr-spark-8.0.0` has no Hadoop application ("Specified application: Hadoop is invalid") | fixed: Spark only; MapReduce runs inside it |
| P14-D5 | EMR command-runner drops empty arguments, shifting the Spark step's arguments | fixed in repair-02 |
| P14-D6 | the pipeline script defaulted to 16 shuffle partitions | fixed in repair-03 (40) |
| P14-D7 | EMR planned HybridHashJoin, which ran out of memory in job 10 at 50 percent | fixed in repair-03 (`spark.sql.join.hybridHashJoin.enabled=false`) |
| P14-D8 | EMR aggregation operators in job 10 still ran out of memory at 50 and 100 percent | open; option B (executor cores 2) deferred |

Window 2 and 3 lessons: the window 2 order put `deploy.sh --resume` before the loads (ruling 39); `LoadS3Cli` verify timed out on laptop downloads (ruling 40); DEBUG logs exposed a task session token and hid the load ID (ruling 41); the Neptune bulk load ran out of memory on `db.t4g.medium` (ruling 42); a cold Neptune cache failed smoke 1 and the first D preflight (rulings 44, 46); an interrupted `plan.sh` left a stale lock (ruling 38); the `aws login` session expired during cleanup apply 1 (ruling 48); the window 2 demo was not recorded before cleanup, so a window 3 was run (rulings 48, 49); window 3 kept Neptune on `db.r6g.large` about 1.5 h longer than planned (Phase 15 ruling 10(c)). The window 3 load reported 34,052,689 Neptune records against 29,054,998 in window 2 for the same input; the difference is open ([`docs/evaluation.md`](../docs/evaluation.md) "Neptune record count"). Each of these failures was handled by a human ruling, recorded in the phase prompts and, while HEAD was pinned, in [`operations/rulings/`](../operations/rulings/). Procedures: [`docs/runbook.md`](../docs/runbook.md).

## 22. Threats to validity

Small question sets, single runs, one labeller who is also the architect, file-backed vectors and a local runtime for E, a laptop client for D, cold caches after resizes, and Cost Explorer lag: [`docs/evaluation.md`](../docs/evaluation.md) "Threats to validity" and "Missing data".

## 23. Limitations

The README "Known limitations" lists them with sources: the runtime is destroyed (Phase 17 ruling 8); P14-D8; verifier limits D18 to D20; graph-evidence-api is Neptune-bound; file-backed vectors in E; the open Neptune record count; identity recall 0.600; unconverged communities; the D-10 cuts (approximate betweenness, experiment C, Step Functions, GitHub Actions, a security scanner beyond `terraform validate`, OpenSearch); the HTTP-only ALB and the image scan findings; single runs and one labeller; experiment A without raw results.

## 24. Lessons learned

- Narrow, explicit work orders that name every input by path kept sessions reproducible when Grep and Glob were unavailable (Phase 15 gate "Deviations"; Phase 15 ruling 16 point 7).
- Broad auto-approve rules caused the critical bypasses of Phase 1; risky commands should fall back to the permission prompt ([`spec/architect-decisions.md`](../spec/architect-decisions.md) section 4).
- A probe passes only on a hook or permission block; a model refusal is inconclusive (same section).
- Expectations written without reading the script were wrong more than once (Phase 14 rulings 12, 15, 17).
- The one-repair-round rule needed human exceptions in five phases (section 6).
- Cloud operations need their own preconditions: `aws login` immediately before long scripts, load on a larger Neptune instance and scale down, warm the cache before measuring, and write mid-window decisions to `operations/rulings/` while HEAD is pinned (Phase 14 rulings 42 to 48; Phase 15 ruling 3).
- EMR runtime operators behave differently from Apache Spark locally; a configuration that worked locally at 100 percent failed on EMR at 50 percent (P14-D7, P14-D8).

## 25. Reproduction and cleanup

Build, test, the bounded local sample, Terraform planning, deployment, experiments and the agent-workflow replay are in the README ("Format, compile, test, and package", "Bounded local sample run", "Deployment and smoke test", "Experiment reproduction", "Reproducing the agentic workflow"). The human's fresh-clone `sbt clean check` and `scripts/check.sh` logs of Phase 17 go to `docs/agent-runs/phase-17/evidence/fresh-clone/` (Phase 17 ruling 2). Cleanup is human-only ([`docs/runbook.md`](../docs/runbook.md) section 7): the runtime stage ran on Nov 20, 2026 with 89 destroyed and 43 base resources kept ([`operations/cleanup/cleanup-20261120T041658Z-runtime/`](../operations/cleanup/cleanup-20261120T041658Z-runtime/record.json)); the final cleanup is planned for December 15, 2026 (Phase 17 ruling 8). The release manifest is `operations/releases/release-manifest-final.md` (Phase 17 work order 5).
