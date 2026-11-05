# Phase 07 (Phases 7+8 merged, D-10): storage adapters, loading, embeddings, and S3 Vectors

Session plan: session 1 (2026-11-05) runs work order 1 only. Later work orders, the code review, and the human-run live embedding are appended after the human reviews the work order 1 report. Each work order report is appended verbatim to agent-report.md under "## Work order N: <agent>", wrapped in a ````text fence.

## Human rulings before work order 1 (binding)

1. No cloud resources in Phase 7+8. All AWS infrastructure comes from Terraform (Phase 12+13) and is applied in cloud window 2 (2026-11-13 to 14). Agents run no aws commands and make no AWS API calls. Every store has a local fake used by tests and local dry runs. Live adapters run only in cloud window 2, behind an explicit opt-in that defaults to off. The bounded AWS integration tests stay an open gate item until then, like the Phase 6 EMR run. The one exception is the live Bedrock embedding run (ruling 9), which the human runs from Terminal; no agent runs it.

2. Input: the Phase 6 export, read-only. The load manifest is scratch/spark/snapshot=2026-10-03/run=run-0375535e0174b7330b18a78988c07211/export/load-manifest.json and lists every table. The other runs are run-10ceed31b3f602bb998eae7c00929ae9, run-d7865ad3f5f8195546c1dab817328ee5 and run-83b496169099a614ec519133077a1993 (stages), and run-de1f982ae8f83848b4f869d8718f5e3d (pipeline). modules/spark/JOBS.md describes every table, including the table-to-core mapping. Never modify or delete anything under scratch/spark/ or scratch/hadoop/. Phase 7+8 outputs go under scratch/stores/, with the snapshot and run ID in the path, deterministic run IDs, and a run manifest per run (the same conventions as Phase 6 ruling 14).

3. Data flow and ownership:
   - Large transforms stay in Spark. A later spark-engineer work order converts the Phase 6 Parquet tables into store-ready files: Neptune bulk-load CSV, DynamoDB item files, and embedding-candidate JSONL.
   - storage-engineer owns:
     - the store formats and keys (the contract the Spark job must meet);
     - validators for those files;
     - ports, adapters, fakes, and loaders;
     - the embedding batch runner.
   - rag-engineer owns the rendering templates (Phase 6 ruling 23), the embedding-text policy, the selection policy, model versioning, the semantic-context format, and the retrieval smoke tests.
   - A written format contract plus validators is enough to share a contract between modules. If shared code seems unavoidable, report BLOCKED-ON domain-engineer (modules/core) or build-engineer (module dependencies) instead of editing outside owned paths.

4. Identifiers: Neptune vertex and edge IDs, DynamoDB keys, and vector keys reuse the Phase 6 IDs unchanged (researcher scr: IDs, work IDs, community and component IDs, summaryId). There is no new ID scheme. Edge IDs are deterministic from (edge type, endpoints, window).

5. Neptune: use the openCypher bulk-load CSV format. Check it against current AWS documentation and cite the page.
   - Vertices: Researcher, Work, Venue, Institution, Topic, Community.
   - Edges: AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, and MEMBER_OF (researcher to community, per window).
   - COAUTHORED_WITH has one edge per researcher pair per window. Its properties are jointPublicationCount, firstCollaborationYear, lastCollaborationYear, window, and an evidence pointer: the supporting work IDs, or an S3 pointer when the list is long.
   - Every vertex and edge carries runId, snapshot, and schemaVersion.
   - RESOLVES_TO, DERIVED_FROM, and SUPPORTED_BY are not loaded into Neptune in this version. The mention map lives in S3 and in EntityLinkCache. State this scope cut in STORAGE.md.
   - Per-window researcher metrics live in DynamoDB (ruling 6), not as vertex properties.
   - Runtime services get read ports only (ruling 12).

6. DynamoDB tables:
   - The tables are RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry, and ResearcherMetricCache. ResearcherMetricCache is justified by the runtime lookup of metrics by researcher and window.
   - Each table has an explicit partition key and sort key, with its access patterns written next to them.
   - Writes are conditional for idempotency.
   - TTL is used only on QuerySession and EvidenceBundle.
   - Capacity mode is on-demand.
   - Items stay well under the 400 KB limit; long lists go to S3 pointers.
   - EntityLinkCache serves runtime name-to-researcher lookup; mention-level data stays in S3.
   - Report the item count per table and an on-demand write cost estimate taken from current pricing documentation. Anything no runtime access pattern needs stays in S3.

7. S3: there is one bucket per environment, created by Terraform.
   - Prefixes: raw/, curated/, graph/ (Neptune CSV), semantic-context/ (rendered text), vectors/ (a vector backup), reports/, logs/, and runs/ (manifests).
   - Object keys include the snapshot and run ID.
   - The run manifest records a sha256 content hash and a source reference for every object.
   - The local fake is file-backed under scratch/stores/ and mirrors the same keys.

8. S3 Vectors:
   - One vector bucket with five indexes: researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, and topic-institution-summary-v1. Cosine distance, with the dimension from ruling 9.
   - The vector key is the stable document ID (summaryId where one exists).
   - Metadata stays compact. It holds the vector key, entity ID(s), window, documentType, sourceDataset, embeddingModelVersion, textHash, runId, topicIds, institutionIds or communityId where they apply, and the S3 pointer to the text.
   - Never put the text in metadata. Mark the S3 pointer and other fields that are never filtered on as non-filterable.
   - Check the metadata size, filterable-key, and batch limits against current documentation and report them.
   - A write that reuses an existing key with a different textHash is refused unless the model version or run changes explicitly. A key never silently points to different text.
   - Writes are batched. Transient failures are retried, and rejected vectors go to a rejected-vectors file.
   - The local fake is file-backed, with brute-force cosine search and the same filter semantics.

9. Embeddings:
   - Model amazon.titan-embed-text-v2:0, 1024 dimensions, normalized.
   - A bounded, deterministic, seeded selection of 10,000 to 15,000 vectors in total across the five indexes (D-13), with per-index counts and the selection rule reported.
   - The account quota is 60 requests per minute with one text per call. The live runner therefore:
     - throttles to at most 50 requests per minute;
     - is resumable, skipping by textHash plus model version;
     - records progress and failures in RunLedger (file-backed locally);
     - has a hard --max cap.
   - Agents test only with a fake embedder. The human runs the live embedding from Terminal; expect 3 to 5 hours.

10. paper-abstract-v1: use abstracts only if the inputs carry them and their reuse is permitted. Otherwise this index holds representative-paper summaries rendered from metadata (title, year, venue, authors, topics). Report which applies.

11. The embedding pipeline is a manifest-driven batch design. There is no SQS in Phase 7+8; SQS belongs to the Phase 10 worker (D-10).

12. Read ports and load ports are separate types, so the runtime read path cannot write. IAM for read-only runtime access is set in Phase 12.

13. Tests, all against local fakes with hand-made fixtures: serialization round trip, idempotent reload, missing and malformed metadata, duplicate vector keys, coauthor-edge endpoint and evidence validation, and DynamoDB conditional-write behavior. Keep the added sbt check time modest. test-engineer does not run in this phase (D-10).

14. Dependencies: only AWS SDK v2 artifacts, at the pinned version 2.41.32 (D-09, the same as bedrockruntime), and no new non-AWS library. If a needed SDK module (for example s3, dynamodb, neptunedata, or s3vectors) is not pinned yet, report BLOCKED-ON build-engineer and dependency-updater with the exact artifact names. Do not edit build files.

15. No agent reads, writes, or lists anything under labels/. No aws commands, no AWS API calls, no git, and no network downloads. Documentation lookups through the aws-docs MCP server are allowed.

16. Every storage report covers schemas, keys, indexes, metadata fields, batch sizes, retry policy, idempotency strategy, service limits captured from current documentation (with links), and cleanup procedures.

## Work order 1: storage-engineer (store contracts, ports, fakes, validators)

First read build.sbt, project/Dependencies.scala, modules/core (records, storage and S3 Vectors configuration), modules/spark/JOBS.md, and the Phase 6 load manifest (ruling 2). Report what already exists in modules/storage and which AWS SDK modules are pinned.

1. Write modules/storage/STORAGE.md, the contract for every store under rulings 4 to 8:
   - the S3 key layout;
   - the Neptune CSV files (file names, headers with types, and the Phase 6 table and column each one comes from);
   - the DynamoDB tables (keys, attributes, access patterns, TTL, conditional-write rules, expected item counts, and the write cost estimate);
   - the S3 Vectors bucket, indexes, dimension, distance, and metadata schema with filterable flags;
   - the embedding-candidate JSONL schema the later Spark job must produce;
   - service limits from current AWS documentation, with links.
2. Ports, with read and load separated (ruling 12), and local fakes:
   - S3: file-backed.
   - Neptune: a loader that validates the CSV files, loads them into an in-memory graph, and supports a coauthor-edge lookup by researcher pair and window.
   - DynamoDB: in-memory, with conditional writes and a TTL attribute.
   - S3 Vectors: file-backed, with cosine search and metadata filters.
   - RunLedger: file-backed.
3. Validators for the Neptune CSV files, DynamoDB item files, embedding-candidate JSONL, and vector records. They check headers and types, ID formats, that every edge endpoint exists, that every COAUTHORED_WITH edge has evidence, that provenance is present, item and metadata size limits, and duplicate keys.
4. Tests per ruling 13.
Live SDK adapters come in a later work order; do not write them now. Run sbt check twice. Report per ruling 16, plus files created, test counts, and any BLOCKED-ON.
