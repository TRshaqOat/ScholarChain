# ScholarChain final release manifest

- Owner: deploy-engineer (`operations/releases/`).
- Work order: Phase 17 work order 5 (`docs/agent-runs/phase-17/prompt.md`, human rulings 6 and 11).
- Written 2026-11-22 from committed records only. No script, cloud command, MCP call, or Git command was run to produce it.
- Scope: the last deployed release, cloud window 3, environment `course`, Region `us-east-2`.
- Account ID: written as `<account-id>` (ruling 11). No ARN containing the account ID is copied. The data bucket is `scholarchain-course-data-<account-id>-us-east-2`.
- Runtime status: not available. The runtime cleanup finished at 2026-11-20T04:27:03Z (section 5). Only the 43 base resources remain.
- The final submission commit and tag are not recorded here. This manifest is committed before them, so they are recorded in the Phase 17 gate and in the submission (ruling 6).

## 1. Release facts (ruling 6)

| Item | Value | Source path |
| --- | --- | --- |
| Release commit | `c5428063732af5f7a5279c215dc2a52b89f64f16` | `operations/releases/release-20261119T233215Z-c5428063732a.json` (`commit`) |
| Deployed at | 2026-11-19T23:35:35Z; environment `course`; Region `us-east-2`; `runtimeEnabled` true; `resumed` true; `verified` true | `operations/releases/release-20261119T233215Z-c5428063732a.json` |
| Image tag | `deef29516bf395ac2b551beb93710b6a48a937a8` (the window 2 image, reused by window 3) | `operations/releases/release-20261119T233215Z-c5428063732a.json` (`image.tag`); `operations/releases/release-20261117T232233Z-506eddf46f23.json` (`image.tag`) |
| Image digest | `sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea`, the same on query-api, graph-evidence-api, and worker (task definition revision `:2`, section 2) | `operations/releases/release-20261119T233215Z-c5428063732a.json` (`image.digest`, `services[].imageDigest`) |
| Image repository and build | `<account-id>.dkr.ecr.us-east-2.amazonaws.com/scholarchain-course-services`; CodeBuild build `scholarchain-course-services-image:6d6dec43-47d5-4fcf-91e5-33e4492ebc9f`; image scan not recorded (`scan` is null) | `operations/releases/package-deef29516bf395ac2b551beb93710b6a48a937a8.json` (`image`) |
| Plan | SHA-256 `978ce3059dc6b039e7c1bd9361c896838ea568dea9b68164ae0ff2a02e4b0bb6`; counts 89 add, 0 change, 0 destroy; `runtime_enabled` true; no stateful destroy or replace; created 2026-11-19T17:33:52Z at the release commit; approved by the human architect at 2026-11-19T17:39:32Z. The release record's `planSha256` is the same value. | `operations/plans/20261119T173346Z-c5428063732a.md`; `operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt` |
| Plan settings | graph-evidence-api CPU `target_value` 15 (Phase 15 ruling 2, window 3 experiment setting); `TF_VAR_alarm_email` empty (Phase 14 ruling 27); SSE-C pinned, no drift change | `operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt` |
| Dataset snapshots | DBLP `2026-10-03`; OpenAlex `2026-11-03`; Crossref `2026-11-03`; ORCID `2026-11-03` (each enrichment snapshot is the UTC start date of its API run) | `datasets/dataset-manifest.yaml` (`datasets.<name>.snapshot_date`) |
| Served runs | graph `run-1d6a86491812ff94887270c1ab885e69`; items `run-1d6a86491812ff94887270c1ab885e69`; vectors `run-b28edbd40f932f0342c05d5b6f57b46c`; window summary `run-6fbad498beb0d8f570df67665fb97544`; runtime snapshot `2026-10-03` | `infra/terraform/envs/course/course.tfvars` (`runtime_graph_run`, `runtime_items_run`, `runtime_vectors_run`, `window_summary_run`, `runtime_snapshot`) |
| Runtime run | `run-c9f26a9ecfe83c96526d50d79d99fd67`, recorded for query-api and worker (no entry for graph-evidence-api) | `operations/releases/release-20261119T233215Z-c5428063732a.json` (`runtimeRunIds`) |
| Prompt versions | planner `planner-v2`; synthesis `synthesis-v3` | `modules/core/src/main/resources/scholarchain.conf` (`runtime.planner.prompt-version`, `runtime.synthesis.prompt-version`); `modules/rag/RUNTIME.md` section 20 gives the same defaults |
| Verifier version | `verifier-v8` (Phase 11 repair-02) | `modules/rag/RUNTIME.md` section 12 (`Verifier.Version`) |
| Model IDs | planner and synthesis `us.anthropic.claude-haiku-4-5-20251001-v1:0`; embedding `amazon.titan-embed-text-v2:0` (see the note below the table) | `modules/core/src/main/resources/scholarchain.conf` (`models.planner-model`, `models.synthesis-model`); `modules/rag/RUNTIME.md` sections 16, 17, and 20; `infra/terraform/envs/course/course.tfvars` (`embedding_model_id`, `haiku_inference_profile_id`) |
| Tests | 845 passed, 0 failed, 10 pending: the sum of the eight per-project ScalaTest summaries (191, 111, 115, 122, 49, 46, 141 with 10 pending, 70), followed by `markTestRun` and `==> OK check: every gate passed`. The log does not record the commit; ruling 6 attributes this run to `d406c66`. | `docs/agent-runs/phase-15/evidence/gate/check-sh-gate.txt` |
| Artifact checksums | two job jars, the EMR step script, and the Docker build context (section 3) | `operations/releases/package-deef29516bf395ac2b551beb93710b6a48a937a8.json` |
| Smoke test | PASS, 6 of 6 checks, finished 2026-11-19T23:57:37Z (section 4) | `operations/releases/smoke-20261119T235713Z.json` |
| Cleanup | stage `runtime`, finished 2026-11-20T04:27:03Z, 43 base resources kept; destroyed count not recorded (section 5) | `operations/cleanup/cleanup-20261120T041658Z-runtime/record.json`; `operations/cleanup/cleanup-20261120T041658Z-runtime/retained.tsv` |

Model ID note: `scholarchain.conf` is the reference configuration. It holds the Haiku 4.5 ID as a real default for the planner and synthesis models, but only the placeholder `example-embedding-model` for `models.embedding-model` (environment variable `SCHOLARCHAIN_EMBEDDING_MODEL_ID`). The embedding ID above comes from `course.tfvars` (`embedding_model_id`) and matches the Titan V2 ID in `RUNTIME.md` section 17. `models.verification-model` and `models.adjudication-model` are placeholders and unused (verification is deterministic, `RUNTIME.md` section 16). The values the deployed tasks received through environment variables are not recorded in the inputs of this manifest.

Prompt version correction (ruling 6): the Nov 21 handoff named `synthesis-v2`. The configuration says `synthesis-v3`, and the configuration rules.

Other versions in the reference configuration: `config-v1` (`config-version`), entity resolution `er-v2`, graph `graph-v1`, fusion `fusion-v1`, resolver `dominant-name-v1` (`modules/core/src/main/resources/scholarchain.conf`).

## 2. Deployed revisions (window 3)

| Service | Task definition ARN (account ID replaced) | Revision | Image digest |
| --- | --- | --- | --- |
| query-api | `arn:aws:ecs:us-east-2:<account-id>:task-definition/scholarchain-course-query-api:2` | `:2` | `sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea` |
| graph-evidence-api | `arn:aws:ecs:us-east-2:<account-id>:task-definition/scholarchain-course-graph-evidence-api:2` | `:2` | `sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea` |
| worker | `arn:aws:ecs:us-east-2:<account-id>:task-definition/scholarchain-course-worker:2` | `:2` | `sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea` |

Source: `operations/releases/release-20261119T233215Z-c5428063732a.json` (`services`). The record names its outputs file `release-20261119T233215Z-c5428063732a.outputs.json` (`outputsFile`); that file is not an input of this manifest.

## 3. Artifact checksums

Package record `operations/releases/package-deef29516bf395ac2b551beb93710b6a48a937a8.json`: commit `deef29516bf395ac2b551beb93710b6a48a937a8`, created 2026-11-17T01:09:25Z, Region `us-east-2`, bucket `scholarchain-course-data-<account-id>-us-east-2`.

| Artifact | Key in the data bucket | SHA-256 |
| --- | --- | --- |
| Hadoop job jar (main class `scholarchain.hadoop.Pipeline`) | `artifacts/jobs/deef29516bf395ac2b551beb93710b6a48a937a8/hadoop-assembly.jar` | `5c320d45270921ad68cc50f7c486920817b78c7a6f5f8146bf3fe3ea7696f120` |
| Spark job jar (main class `scholarchain.spark.PipelineJob`) | `artifacts/jobs/deef29516bf395ac2b551beb93710b6a48a937a8/sparkAnalytics-assembly.jar` | `0045d22310cd559fd5502829aed974d8394c2df71ae887375e44e86fe633d1fc` |
| EMR step script | `artifacts/jobs/deef29516bf395ac2b551beb93710b6a48a937a8/step.sh` | `930f28615c516998d3e7fa663f89c3b03c63144c79eb25a7cbc151da4825e1c5` |
| Docker build context | `artifacts/codebuild/source/deef29516bf395ac2b551beb93710b6a48a937a8.zip` | `6968a921ec1caac20385a317f60cf54931d087893087770aca08e7c09236938d` |

## 4. Smoke result

Record `operations/releases/smoke-20261119T235713Z.json`: overall `PASS`, finished 2026-11-19T23:57:37Z, Region `us-east-2`. Question: "Have Jack J. Dongarra and Yves Robert coauthored a paper?". The load balancer address is in the record's `baseUrl` field and is not copied here; the load balancer was destroyed by the runtime cleanup. Response bodies are recorded under `bodies`: `smoke-20261119T235713Z/`.

| Check | Result | HTTP | Detail |
| --- | --- | --- | --- |
| healthz | PASS | 200 | ok |
| readyz | PASS | 200 | ready |
| query | PASS | 200 | `qry-c08ceb31eb51c6547cda9e522e3d1427`, claims=2, evidence=`ev-927665f3bbac87eb60685e50cfeb8f9b` |
| evidence | PASS | 200 | `ev-927665f3bbac87eb60685e50cfeb8f9b` COAUTHOR_EDGE |
| idempotent | PASS | 200 | same queryId and answerHash |
| auth-error | PASS | 401 | UNAUTHORIZED |

## 5. Cleanup record and retained base resources

Record `operations/cleanup/cleanup-20261120T041658Z-runtime/record.json`:

- Stage `runtime`, finished 2026-11-20T04:27:03Z, Region `us-east-2`, commit `c5428063732af5f7a5279c215dc2a52b89f64f16`.
- Cleanup plan SHA-256 `3352beee7038b464d1a203f6a6c6e1633d56e25bd1c77b3607ca8f8f083de5a6`.
- Retained: 43 resources, listed below. `record.json` (`retained`) and `retained.tsv` hold the same 43 addresses and reasons.
- Terraform state kept (`stateKept` true). Inventories named `before.*.json` and `after.*.json` (not inputs of this manifest).
- Evidence archive: not recorded (`evidenceArchive` is null).
- Destroyed count: not recorded. `record.json` and `retained.tsv` hold no count of destroyed resources. Ruling 6 states 89 destroyed; the window 3 plan created 89 resources (section 1).

| # | Terraform address | Reason recorded |
| --- | --- | --- |
| 1 | `module.build.aws_cloudwatch_log_group.codebuild` | log groups: logs kept as evidence (14-day retention) |
| 2 | `module.build.aws_codebuild_project.services_image` | kept by this stage |
| 3 | `module.build.aws_ecr_lifecycle_policy.services` | ECR: image history for reproduction (force_delete=false) |
| 4 | `module.build.aws_ecr_repository.services` | ECR: image history for reproduction (force_delete=false) |
| 5 | `module.build.aws_iam_role.codebuild` | kept by this stage |
| 6 | `module.build.aws_iam_role_policy.codebuild` | kept by this stage |
| 7 | `module.data.aws_s3_bucket.data` | data bucket: grading evidence until the instructor approves the archive (force_destroy=false) |
| 8 | `module.data.aws_s3_bucket_lifecycle_configuration.data` | data bucket (as row 7) |
| 9 | `module.data.aws_s3_bucket_ownership_controls.data` | data bucket (as row 7) |
| 10 | `module.data.aws_s3_bucket_policy.data` | data bucket (as row 7) |
| 11 | `module.data.aws_s3_bucket_public_access_block.data` | data bucket (as row 7) |
| 12 | `module.data.aws_s3_bucket_server_side_encryption_configuration.data` | data bucket (as row 7) |
| 13 | `module.data.aws_s3_bucket_versioning.data` | data bucket (as row 7) |
| 14 | `module.emr.aws_iam_instance_profile.instance` | kept by this stage |
| 15 | `module.emr.aws_iam_role.instance` | kept by this stage |
| 16 | `module.emr.aws_iam_role.service` | kept by this stage |
| 17 | `module.emr.aws_iam_role_policy.instance_s3` | kept by this stage |
| 18 | `module.emr.aws_iam_role_policy.service_pass_role` | kept by this stage |
| 19 | `module.emr.aws_iam_role_policy_attachment.service_v2` | kept by this stage |
| 20 | `module.emr.aws_security_group.core` | kept by this stage |
| 21 | `module.emr.aws_security_group.primary` | kept by this stage |
| 22 | `module.emr.aws_vpc_security_group_egress_rule.emr_all["core"]` | kept by this stage |
| 23 | `module.emr.aws_vpc_security_group_egress_rule.emr_all["primary"]` | kept by this stage |
| 24 | `module.network.aws_default_security_group.this` | kept by this stage |
| 25 | `module.network.aws_internet_gateway.this` | kept by this stage |
| 26 | `module.network.aws_route.public_internet` | kept by this stage |
| 27 | `module.network.aws_route_table.private` | kept by this stage |
| 28 | `module.network.aws_route_table.public` | kept by this stage |
| 29 | `module.network.aws_route_table_association.private[0]` | kept by this stage |
| 30 | `module.network.aws_route_table_association.private[1]` | kept by this stage |
| 31 | `module.network.aws_route_table_association.public[0]` | kept by this stage |
| 32 | `module.network.aws_route_table_association.public[1]` | kept by this stage |
| 33 | `module.network.aws_subnet.private[0]` | kept by this stage |
| 34 | `module.network.aws_subnet.private[1]` | kept by this stage |
| 35 | `module.network.aws_subnet.public[0]` | kept by this stage |
| 36 | `module.network.aws_subnet.public[1]` | kept by this stage |
| 37 | `module.network.aws_vpc.this` | kept by this stage |
| 38 | `module.network.aws_vpc_endpoint.dynamodb` | kept by this stage |
| 39 | `module.network.aws_vpc_endpoint.s3` | kept by this stage |
| 40 | `module.observability.aws_cloudwatch_log_group.ecs["graph-evidence-api"]` | log groups: logs kept as evidence (14-day retention) |
| 41 | `module.observability.aws_cloudwatch_log_group.ecs["neptune-loader"]` | log groups: logs kept as evidence (14-day retention) |
| 42 | `module.observability.aws_cloudwatch_log_group.ecs["query-api"]` | log groups: logs kept as evidence (14-day retention) |
| 43 | `module.observability.aws_cloudwatch_log_group.ecs["worker"]` | log groups: logs kept as evidence (14-day retention) |

By module: build 6, data 7, emr 10, network 16, observability 4 (total 43).

## 6. Earlier releases

| Window | Release record | Commit | Plan SHA-256 | Deployed at | Runtime | Image | Task definitions |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `operations/releases/release-20261116T062349Z-d33ce8e8d507.json` | `d33ce8e8d507145cd46fb39bf319e985bc9a428a` | `9930298cd1a9ecafd5f616471681145e80918234ee6dd7805b3a6e973acedc63` | 2026-11-16T06:24:50Z | `runtimeEnabled` false, `resumed` false, `verified` true | none (`image` null) | none (`services` empty); no runtime run |
| 2 | `operations/releases/release-20261117T232233Z-506eddf46f23.json` | `506eddf46f23c7004150b2373403adf49518953a` | `ba816190fb85a236d10641c531980af10456bd1ea8b9a0400a6a81428f0c2ea0` | 2026-11-17T23:26:12Z | `runtimeEnabled` true, `resumed` true, `verified` true | tag `deef29516bf395ac2b551beb93710b6a48a937a8`, digest `sha256:5c751b1e1341fd9dd86101cdc8fffc9b8b9c91284b83ec1757758218588047ea` (the same as window 3) | query-api, graph-evidence-api, and worker at revision `:1`, all with the same digest; runtime run `run-c9f26a9ecfe83c96526d50d79d99fd67` (query-api and worker) |
| 3 | `operations/releases/release-20261119T233215Z-c5428063732a.json` | `c5428063732af5f7a5279c215dc2a52b89f64f16` | `978ce3059dc6b039e7c1bd9361c896838ea568dea9b68164ae0ff2a02e4b0bb6` | 2026-11-19T23:35:35Z | `runtimeEnabled` true, `resumed` true, `verified` true | the same tag and digest as window 2 | sections 1 and 2 |

The window 1 and window 2 records name their outputs files `release-20261116T062349Z-d33ce8e8d507.outputs.json` and `release-20261117T232233Z-506eddf46f23.outputs.json`; those files are not inputs of this manifest.

## 7. Notes on the records

- `infra/terraform/envs/course/course.tfvars` as committed sets `runtime_enabled = false` and `service_cpu_target_percent = 60`. Window 3 was planned with `runtime_enabled` true and a graph-evidence-api CPU target of 15 (`-APPROVED.txt`). The tfvars comment states that 60 is the variable default restored by Phase 15 ruling 6, and that 15 was a declared experiment setting. The served run IDs in section 1 are the values present in the committed file.
- Windows 2 and 3 record the same runtime run ID, `run-c9f26a9ecfe83c96526d50d79d99fd67`, and the same image digest.
- The package record holds no image scan result (`image.scan` is null).
