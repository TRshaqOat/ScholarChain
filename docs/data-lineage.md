# ScholarChain data lineage

Owner: documentation-engineer. This file traces a value from a source record to a verified answer claim. The contracts it summarises are authoritative in the module documents: [`modules/hadoop/JOBS.md`](../modules/hadoop/JOBS.md), [`modules/spark/JOBS.md`](../modules/spark/JOBS.md), [`modules/storage/STORAGE.md`](../modules/storage/STORAGE.md) and [`modules/rag/RUNTIME.md`](../modules/rag/RUNTIME.md). Where this file and those documents disagree, they are right. Bucket names are written as `scholarchain-course-data-<account-id>-us-east-2`.

## 1. The chain

```text
source record (DBLP key, OpenAlex W ID, Crossref DOI, ORCID iD)
  -> dataset scripts (datasets/scripts/)                 local snapshot files, manifest checksums
  -> S3 raw/<dataset>/snapshot=<d>/run=<input run>/      uploaded write-once by run-bounded-pipeline.sh
  -> Hadoop J01..J10 (curated/hadoop/...)                normalized records, rejects, candidates, decisions, pairs
  -> entity-resolution decisions (J10, policy er-v2)    ACCEPTED, REJECTED, UNRESOLVED, CONFLICT
  -> Spark jobs 1..11 (curated/spark/...)               canonical tables, coauthor edges, metrics, load manifest
  -> store conversion (StoreConversionJob and friends)  Neptune CSV, DynamoDB items, evidence objects, vector candidates
  -> loaders                                            S3, Neptune, DynamoDB, S3 Vectors
  -> runtime evidence items (ev-...)                    store reads with provenance, stable evidence IDs
  -> answer claims                                      cite evidence IDs; rule-based verification
```

## 2. Stage by stage

| Stage | Code and contract | Output location | Provenance carried |
|---|---|---|---|
| Source record | [`datasets/dataset-manifest.yaml`](../datasets/dataset-manifest.yaml) | DBLP snapshot 2026-10-03; OpenAlex, Crossref and ORCID snapshots 2026-11-03 (Phase 14 ruling 6) | dataset, snapshot date, checksums in the manifest |
| Dataset scripts | `datasets/scripts/` (bounded, DOI-driven from the DBLP subset; `make_fraction.sh` for the 25 and 50 percent subsets, rule fraction-v1) | local files under the gitignored `scratch/datasets/`; uploaded by `scripts/run-bounded-pipeline.sh` to `raw/<dataset>/snapshot=<d>/run=<input run>/`, where the input run is the hash of the fraction and the file hashes ([`scripts/README.md`](../scripts/README.md) `run-bounded-pipeline.sh`) | input keys with sha256 and bytes in the pipeline `run.json` |
| Hadoop MapReduce | J01 DBLP extraction, J02 source normalization, J03+J04 exact identifiers, J05 blocking, J06 candidate join, J10 match decisions, J07+J08 publication teams and pair expansion, J09 Spark-ready export ([`modules/hadoop/JOBS.md`](../modules/hadoop/JOBS.md)) | `<out>/snapshot=<dblp snapshot>/run=<runId>/<job>/<dir>/`, plus `_manifest.json` (`hadoop-run-manifest-v3`) and `_counters/<job>.json`; on EMR `<out>` is `curated/hadoop` | every record carries `Provenance`: source records keep dataset, source record ID and snapshot; teams and pairs keep the DBLP work's source provenance; candidates and decisions are `DERIVED` with the policy version; malformed input goes to `rejects-v1/` with `sourceLocation` and `runId` |
| Entity resolution | pure functions in [`modules/entity-resolution/`](../modules/entity-resolution/) applied by J05, J06 and J10; policy `er-v2` | Spark reads the J09 `match-decisions/entity_type={RESEARCHER,WORK}/outcome={ACCEPTED,CONFLICT,UNRESOLVED}/`; REJECTED decisions are not read, and only ACCEPTED decisions link records ([`modules/spark/JOBS.md`](../modules/spark/JOBS.md) "Inputs") | decision, features, rules fired, confidence, policy version, run ID |
| Spark | `PipelineJob`: CanonicalGraphJob (jobs 1, 2), ResearcherAnalyticsJob (3, 4, 8), CommunityPathJob (5, 6, 7), TemporalSummaryExportJob (9, 10, 11) ([`modules/spark/JOBS.md`](../modules/spark/JOBS.md)) | `<out>/snapshot=<snapshot>/run=<stage run>/<table>/`; the export stage writes `export/load-manifest.json` | every row has `schemaVersion` and a `provenance` struct (`DERIVED`, `sourceRecordId` = the row key, snapshot, `runId` = the stage run that wrote the row, policy version); canonical IDs resolve to source records through `canonical/mention-map` and `canonical/work-map` |
| Store conversion | `StoreConversionJob`, `BenchmarkPathJob`, `CorpusWindowSummaryJob`, the embedding runs ([`modules/spark/JOBS.md`](../modules/spark/JOBS.md), [`modules/storage/STORAGE.md`](../modules/storage/STORAGE.md)) | `graph/neptune/snapshot=<d>/run=<r>/{vertices,edges}/`, `curated/dynamodb/...`, `curated/coauthor-evidence/...` (edges with more than 20 works), `semantic-context/...`, `vectors/backup/...`; `runs/store-conversion/.../manifest.json` (`store-run-manifest-v1`) | every Neptune element keeps `runId` and `snapshot` of the Phase 6 stage run that computed it, `schemaVersion`, and (edges) `policyVersion`; every manifest entry has sha256, bytes and `sourceRef` (`<table>@<run>`) |
| Loaders | `LoadS3Cli`, `LoadNeptuneCli` (ECS task in the VPC), `LoadDynamoCli`, `LoadVectorsCli`, run by `scripts/load-stores.sh` | the data bucket, Neptune, seven DynamoDB tables, five S3 Vectors indexes | write-once objects (a different body under an existing key is a conflict); S3 objects checked against the manifest sha256 (Phase 14 ruling 40: 609 of 609) |
| Runtime evidence | stages 3 to 7 of [`modules/rag/RUNTIME.md`](../modules/rag/RUNTIME.md) | evidence items in the answer's bundle; `EvidenceBundle` items in DynamoDB; audit object `logs/rag-runtime/snapshot=<d>/run=<runtime run>/<queryId>.json` (section 14) | `StableIds.evidenceId(type, subjectIds, runId)`; `sourceUri` is a store pointer (`neptune:<element id>`, `dynamodb:<Table>/<pk>/<sk>`, an S3 key); `facts` hold the stored values; `provenance` is the stored element's |
| Answer claim | stages 8 and 9 (synthesis, verification) | `claims[]` of the answer | each claim lists `evidenceIds`; the verifier checks that they exist and that every asserted value equals a cited fact; claim provenance names the runtime run and the model |

The served graph is the local full Spark run, not an EMR run: Spark on EMR completed at 25 percent and ran out of memory in job 10 at 50 and 100 percent (P14-D8; [`docs/agent-runs/phase-14/human-gate.md`](agent-runs/phase-14/human-gate.md)). The served runs are named in [`infra/terraform/envs/course/course.tfvars`](../infra/terraform/envs/course/course.tfvars): graph and items `run-1d6a86491812ff94887270c1ab885e69` (store conversion), vectors `run-b28edbd40f932f0342c05d5b6f57b46c`, window summary `run-6fbad498beb0d8f570df67665fb97544`.

## 3. Traced example: evidence `ev-927665f3bbac87eb60685e50cfeb8f9b`

The window 3 smoke test asked benchmark question B07, "Have Jack J. Dongarra and Yves Robert coauthored a paper?" ([`operations/releases/smoke-20261119T235713Z.json`](../operations/releases/smoke-20261119T235713Z.json), result PASS). Each step below names the record it was read from.

### 3.1 Answer claim

From [`operations/releases/smoke-20261119T235713Z/query.json`](../operations/releases/smoke-20261119T235713Z/query.json):

| Field | Value |
|---|---|
| `queryId` | `qry-c08ceb31eb51c6547cda9e522e3d1427` |
| claim | `claim-1`: "Jack J. Dongarra and Yves Robert coauthored 23 joint publications from 2007 to 2021." |
| `evidenceIds` | `ev-927665f3bbac87eb60685e50cfeb8f9b` |
| `verificationStatus` | `VERIFIED` |
| claim provenance | `sourceDataset` RUNTIME, `runId` `run-c9f26a9ecfe83c96526d50d79d99fd67` (the window 3 runtime run, also in the release record), `modelVersion` `us.anthropic.claude-haiku-4-5-20251001-v1:0` |
| identity lines | Jack J. Dongarra linked to `scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763` by `dominant-name-v1` (0.9576 of 377 works among 17 researchers with this display name); Yves Robert linked to `scr:canon-v1.er-v2:da6e3bdff14745638f3f5425f0133388` (0.9775 of 222 works among 6) |
| verification counts | verified 2, unsupported 3 (removed from the answer, kept in the verification report) |

### 3.2 Evidence item

From [`operations/releases/smoke-20261119T235713Z/evidence.json`](../operations/releases/smoke-20261119T235713Z/evidence.json):

| Field | Value |
|---|---|
| `evidenceType` | `COAUTHOR_EDGE` |
| `subjectIds` | the two researcher IDs above |
| `sourceUri` | `neptune:cae-94049b45586a9d41217209d71135c559` |
| facts | `edgeId` `cae-94049b45586a9d41217209d71135c559`; window `all-years` (2000 to 2026); `jointPublicationCount` 23; `firstCollaborationYear` 2007; `lastCollaborationYear` 2021; 23 `supportingWorkIds`; `supportingWorkIdsRef` `curated/coauthor-evidence/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69/window=all-years/cae-94049b45586a9d41217209d71135c559.json` |
| provenance | `sourceDataset` DERIVED, `sourceRecordId` the edge ID, `snapshot` 2026-10-03, `runId` `run-10ceed31b3f602bb998eae7c00929ae9`, `policyVersion` `graph-v1` |

The claim's three numbers (23, 2007, 2021) are the stored facts `jointPublicationCount`, `firstCollaborationYear` and `lastCollaborationYear`; the verifier binds each written number to one of them (RUNTIME.md section 12, `asserted-values` and `numbers-in-text`). The window 2 smoke query `qry-41d3b96234f65188482930ad99486165` cited the same evidence ID (Phase 14 ruling 45), because an evidence ID depends only on the type, the subjects and the source run (RUNTIME.md section 10).

### 3.3 Graph edge and store files

- **Neptune edge.** `cae-94049b45586a9d41217209d71135c559` is a `COAUTHORED_WITH` edge. Its ID is the Phase 6 edge ID, `StableIds.coauthorEdgeId(start, end, windowStartYear, windowEndYear, policyVersion)` with policy `graph-v1`, and `:START_ID` is the smaller researcher ID ([`modules/storage/STORAGE.md`](../modules/storage/STORAGE.md) "Edge files").
- **Bulk-load file.** The edge came from the store-conversion run `run-1d6a86491812ff94887270c1ab885e69` (`graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69/edges/coauthored-with/`), the `runtime_graph_run` of `course.tfvars`. Window 3 loaded it with Neptune load `6113dd40` (LOAD_COMPLETED, Phase 15 ruling 10(b)).
- **Evidence object.** With 23 works the edge is above the 20-work inline limit, so the edge holds `supportingWorkIdsRef`, the S3 key above, outside the `graph/` prefix (STORAGE.md "COAUTHORED_WITH evidence"). The Phase 7 validator traced every `COAUTHORED_WITH` edge of this run to Work vertices authored by both endpoints: 1,590,754 edges, 0 `EVIDENCE_WORK_MISSING`, 0 `EVIDENCE_NOT_AUTHORED` ([`docs/agent-runs/phase-07/human-gate.md`](agent-runs/phase-07/human-gate.md)).
- **Store copy.** `LoadS3Cli` uploaded the store-conversion objects in window 2, 609 of 609 matching the manifest by SHA-256 (Phase 14 ruling 40). The data bucket was kept by the runtime cleanup ([`operations/cleanup/cleanup-20261120T041658Z-runtime/`](../operations/cleanup/cleanup-20261120T041658Z-runtime/record.json)).

### 3.4 Spark run

- **Stage run.** The evidence provenance `runId` `run-10ceed31b3f602bb998eae7c00929ae9` is the Phase 6 stage run of jobs 1 and 2 (CanonicalGraphJob), which wrote `graph/coauthor-edges`. The Phase 6 gate lists it with the other stage runs: `run-d7865ad3f5f8195546c1dab817328ee5` (jobs 3, 4, 8), `run-83b496169099a614ec519133077a1993` (jobs 5 to 7), `run-0375535e0174b7330b18a78988c07211` (jobs 9 to 11, the export), in pipeline run `run-de1f982ae8f83848b4f869d8718f5e3d`, code version `src-93bc726cd50a` ([`docs/agent-runs/phase-06/human-gate.md`](agent-runs/phase-06/human-gate.md)). The manifests are in `docs/agent-runs/phase-06/evidence/` (`pipeline-manifest.json`, `load-manifest.json`, `stage-manifest-*.json`).
- **Edge semantics.** Job 2 maps Hadoop pair candidates to canonical researchers and works, drops self-pairs, orders each pair, and aggregates per window: `jointPublicationCount` is the number of distinct canonical works, the first and last years are the minimum and maximum pair year, and `representativeWorkIds` is the complete sorted list ([`modules/spark/JOBS.md`](../modules/spark/JOBS.md) "Coauthorship graph").
- **Researcher IDs.** `scr:canon-v1.er-v2:<hash>` is `StableIds.researcherId` over the canonical policy `canon-v1`, the match policy `er-v2` and the smallest member mention ID. Every member mention, with its source dataset and source record ID, is a row of `canonical/mention-map` of the same stage run.
- **Store conversion input.** `StoreConversionJob` read every Phase 6 table through the export's `load-manifest.json` and the benchmark path run `run-ee4ffd21aa20dbe4c078a4be96fe8c9f` ([`modules/storage/STORAGE.md`](../modules/storage/STORAGE.md) line 5; [`docs/agent-runs/phase-07/human-gate.md`](agent-runs/phase-07/human-gate.md)).

### 3.5 Hadoop run and source records

- **Hadoop run.** The Phase 6 run read the Hadoop run `run-035e75abfa6798c938cbd534273dbd8d`, the Phase 5 repair-02 decisions under policy `er-v2` ([`docs/agent-runs/phase-06/human-gate.md`](agent-runs/phase-06/human-gate.md); Phase 5 records in [`docs/agent-runs/phase-05/`](agent-runs/phase-05/)). In that run, J07+J08 expanded each DBLP work into its coauthor pair candidates, each with work ID, year, author positions and the work's source provenance, and J09 exported them with the match decisions ([`modules/hadoop/JOBS.md`](../modules/hadoop/JOBS.md)).
- **Source records.** The 23 supporting work IDs are DBLP work IDs. J01 maps a DBLP record with key `k` to `workId = dblp:k` and `sourceRecordId = k`. For example `dblp:conf/ipps/DongarraPRSV07` is the record with key `conf/ipps/DongarraPRSV07` in the DBLP snapshot of 2026-10-03, and `dblp:journals/pc/HerrmannBHMRD16` is `journals/pc/HerrmannBHMRD16`.

### 3.6 What this trace did not open

The evidence object, the Neptune CSV files, the Phase 6 manifests and the Hadoop run directory were not opened for this document. The trace rests on the records cited above. The Hadoop and Spark run directories of the local runs are under the gitignored `scratch/`; their store-ready outputs are in the kept data bucket.
