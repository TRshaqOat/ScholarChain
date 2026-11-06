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

## Human rulings after work order 1 (binding)

Work order 1 (storage-engineer) is COMPLETE. Its contract, modules/storage/STORAGE.md, is the base for everything below. Rulings 1 to 16 still apply.

17. Git: no agent runs any git command, including read-only ones such as git status. Use file listings instead. Work order 1 ran `git status --porcelain` once; it is recorded as a process deviation. The orchestrator alone may run read-only git status and git diff, only to build the code-review change set.
18. Region: us-east-2 (D-04). The DynamoDB write cost stays an estimate (under 1 USD per full load) and is verified in the Phase 12 cost plan.
19. Vector selection: accept the proposed 13,019 vectors (profiles 5,000; communities 4,000; paths 50 plus the benchmark pairs of ruling 25; topics 2,969; papers 1,000), within 10,000 to 15,000. rag-engineer owns the exact rule (work order 5).
20. Core (domain-engineer, work order 2):
    - Core configuration changes from four buckets to one bucket per environment, with the prefixes of ruling 7.
    - Core gains a Community vertex label and a MEMBER_OF edge type.
21. Top-N rankings (benchmark questions B03, B19, B21 and similar):
    - Spark precomputes top-50 lists per metric and window, with ties broken by researcher ID. The metrics are publication count, distinct coauthors, maximum team size, cross-community degree, communities connected, participation coefficient, and coreness.
    - The lists are stored as DynamoDB items. storage-engineer defines the key layout, inside ResearcherMetricCache or a dedicated partition prefix, with no secondary index.
22. Runtime audit records are write-once objects under logs/. QuerySession and EvidenceBundle TTL is 30 days. raw/ keys include run=.
23. File handoff between modules, with no new module dependencies:
    - Spark writes store files and embedding candidates.
    - The rag render CLI turns candidates into rendered documents.
    - The storage CLIs validate files and run embedding and loading.
    - Each module may run another module's CLI through sbt runMain, but never edits another module's files.
24. Dependencies: add software.amazon.awssdk s3, dynamodb, neptunedata and s3vectors, plus the already-pinned bedrockruntime, to the storage project. All use the existing Versions.awsSdk value (2.41.32). This adds artifacts at an existing pinned value and changes no version value, so build-engineer does it (work order 3). sbt may download these pinned artifacts; that is the only network download allowed.
25. Benchmark paths: the human-authored experiments/benchmark/benchmark-questions-v1.csv (Claude-drafted, human-reviewed) may be read but never edited.
    - Rows with both researcher_a and researcher_b give path pairs. Spark resolves the names to canonical IDs by exact canonical-name match. Unknown or ambiguous names are recorded as UNKNOWN_RESEARCHER or AMBIGUOUS, never guessed.
    - A new bounded Spark run recomputes path evidence (job 5) and path summaries (job 10, paths only) for the union of the seeded 50 pairs and the benchmark pairs. The Phase 6 outputs stay untouched.
26. Live calls: agents never call AWS, including Bedrock.
    - Live adapters compile and are unit-tested only on request building and response parsing, with no network.
    - The live embedding is run by the human from Terminal, using the exact command that work order 7 produces: throttled to at most 50 per minute, resumable, with --max.
27. Memory and disk: validators stream files. Any in-memory graph load uses a heap of at most 6g; if the full graph does not fit, load a bounded subgraph (the benchmark researchers and their 2-hop neighborhoods) and say so. Check free disk before each run; stop with BLOCKED-ON if it would drop below 5 GiB.
28. Spark runs in this phase follow Phase 6 rulings 14 and 24. The code version is src-<12 hex> from: find modules/spark/src/main build.sbt -type f | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-12

## Work order 2: domain-engineer (core alignment)

Read rulings 17 to 28 and modules/storage/STORAGE.md.
1. Change the core storage configuration to one bucket per environment with the prefixes of ruling 7. Keep configuration keys stable where possible, and report every renamed key.
2. Add the Community vertex label and the MEMBER_OF edge type to core, consistent with STORAGE.md.
3. Update contract tests. Report every downstream reference that must change (storage, spark, or others) without editing it.
Run sbt check twice.

## Work order 3: build-engineer (storage dependencies)

Apply ruling 24: add s3, dynamodb, neptunedata, s3vectors and bedrockruntime at Versions.awsSdk to the storage project only. Keep the mixed-classpath checks. Report the resolved versions and confirm that no other project's classpath changed. If your instructions say this belongs to dependency-updater, return BLOCKED-ON dependency-updater (the orchestrator then runs dependency-updater with this same text).
Run sbt check twice.

## Work order 4: storage-engineer (CLIs, core alignment, embedding runner, live adapters)

Read the work order 2 and 3 reports.
1. Align modules/storage with the core changes from work order 2.
2. Add to STORAGE.md:
   - the ranking item layout (ruling 21);
   - a rendered-document JSONL schema (rendered-document-v1: vectorKey, index, documentType, text, textHash = sha256 of text, and the vector metadata fields), which is what the rag render CLI must produce.
3. Add a validator CLI (runMain) that streams and validates a directory of Neptune CSV files, DynamoDB item files, embedding candidates, or rendered documents. It reports counts and the first violations per rule and exits non-zero on any violation.
4. Add the embedding batch runner (manifest-driven, ruling 11):
   - an Embedder port with a fake embedder for tests and a live Titan V2 adapter (1024 dimensions, normalized);
   - a throttle of at most 50 per minute, single-threaded;
   - resume that skips by textHash plus model version;
   - a hard --max cap;
   - RunLedger progress and failure entries;
   - a rejected-records file;
   - output as validated vector records under scratch/stores/.
   The live adapter runs only behind an explicit --live flag plus a --profile argument, both off by default.
5. Add live adapters for S3, DynamoDB, Neptune (the loader request and status) and S3 Vectors, behind the same opt-in. Unit-test only request building and response parsing (ruling 26).
6. Add the loader CLIs for each store, with dry-run mode (fakes) as the default.
Run sbt check twice. Report per ruling 16, with the exact CLI commands.

## Work order 5: rag-engineer (selection, templates, render CLI, semantic context, smoke tests)

Read Phase 6 ruling 23, rulings 17 to 28, modules/spark/JOBS.md (the summaries and their facts), and modules/storage/STORAGE.md (the embedding-candidate and rendered-document schemas).
1. Write modules/rag/SELECTION.md, an exact, deterministic, seeded selection rule for ruling 19 that spark-engineer can implement without interpretation: which summaries, the windows, the ordering, and ties.
2. Write production rendering templates (version rendering-v1) for researcher profiles, community profiles, collaboration paths, paper summaries, and topic summaries, built from the facts column. They are deterministic and versioned; no LLM.
3. Write a render CLI (runMain) that reads embedding-candidate JSONL and writes rendered-document JSONL per STORAGE.md, with textHash = sha256 of text.
4. Define embedding model versioning (model ID, dimensions, normalization, and the template version in the model-version string or metadata) and the compact semantic-context format for the Phase 9 synthesis agent.
5. Define retrieval smoke tests with expected neighbors derived from data structure only, such as a researcher's own profile ranking first for a query built from that profile's facts. Never use the benchmark file for these. They run on the fake vector store with fake embeddings now, and on real embeddings after the human's live run.
Run sbt check twice.

## Work order 6: spark-engineer (benchmark paths, rankings, store conversion)

Read the reports of work orders 2, 4 and 5.
1. Benchmark paths (ruling 25): a new bounded run of job 5 and the path part of job 10 for the union of the seeded 50 pairs and the benchmark pairs. Report each benchmark pair's resolution (ID, UNKNOWN_RESEARCHER, or AMBIGUOUS) and path result.
2. Rankings (ruling 21): top-50 lists per metric and window.
3. A store-conversion job reading the Phase 6 tables (ruling 2) plus the new path run. It writes, under scratch/stores/ with the STORAGE.md layout:
   - the Neptune openCypher CSV files;
   - the DynamoDB item files (ResearcherMetricCache including rankings, EntityLinkCache, ArtifactRegistry entries);
   - the coauthor-evidence objects;
   - the embedding candidates selected exactly per modules/rag/SELECTION.md.
4. Run the storage validator CLI on every output. All must pass.
Report row and file counts per output, vertex and edge counts by type, item counts by table, candidate counts per index, validator results, wall time, free disk before and after, and output paths. Run sbt check twice.

## Work order 7: storage-engineer (local end-to-end dry run and the live embedding command)

Read the reports of work orders 4 to 6.
1. Run the rag render CLI on the work order 6 candidates, then the validator.
2. Run the embedding runner with the fake embedder on all rendered documents, then load the fake vector store and run the rag smoke tests on it (they must pass mechanically).
3. Load the DynamoDB item files into the in-memory fake (ruling 27 for memory). Load the Neptune CSV into the in-memory graph, or a bounded subgraph, and check COAUTHORED_WITH lookups for the benchmark pairs against the Spark path evidence.
4. Prove idempotence: a second run of each loader reports everything Unchanged.
5. Produce, without running it, the exact Terminal command for the human's live embedding run: profile scholarchain, us-east-2, --live, --max equal to the rendered-document count, the throttle, and the output path. Include how to resume after an interruption and how to check progress, and estimate the time from the 50-per-minute throttle.
Report per ruling 16, plus every count, path and command.

## Code review 1: code-reviewer

Review the complete Phase 07 change set (modules/storage, modules/rag, the modules/spark additions, modules/core changes, and build files) against work orders 1 to 7, rulings 1 to 28, spec Phases 7 and 8 (work orders and gates), and D-04, D-06, D-09 and D-10. The orchestrator supplies the change set as in Phase 6.
Check especially:
- no text in vector metadata, and a key can never silently point to different text;
- Neptune IDs equal the Phase 6 IDs, and COAUTHORED_WITH edges trace to supporting works;
- DynamoDB keys support the stated access patterns, and conditional writes are correct;
- loaders are idempotent;
- live calls sit behind the explicit opt-in and no test touches AWS;
- the throttle and resume logic are correct;
- read and load ports are separated;
- no edits outside owned paths, no reads of labels/, and the benchmark file is unchanged.
Verify each candidate finding against the files. Give critical and major findings in full (severity, file and line, failure input, impact, owner) and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

## Human ruling 29 (before work order 7, binding)

29. The 50-per-minute throttle exists only because of the Bedrock on-demand quota, so it applies only to the live Titan embedder. The fake embedder runs unthrottled. Work order 7 gains a step 0 for storage-engineer, done before its step 1:
    - Make the throttle apply only when --live is set, or attach it to the live Embedder rather than the runner.
    - Test the throttle with an injected clock, with no real sleeping in tests.
    - Add a test showing that the fake path is not throttled.
    - Document in STORAGE.md the facts field that embedding candidates now carry (reported by work order 5), and update the candidate validator if it should check that field.
    - Run sbt check twice after step 0, then continue with steps 1 to 5 unchanged.

## Human rulings after work order 6 (binding)

30. Name resolution (replaces the exact-match-only part of ruling 25):
    - Candidates are the canonical researchers whose canonical display name equals the given name under the ruling 25 normalization.
    - If one candidate holds at least 90% of the AUTHORED works summed over all candidates, and at least 5 works, the name resolves to it as RESOLVED_DOMINANT, recording its share and the candidate count.
    - Otherwise the name is AMBIGUOUS; with no candidates it is UNKNOWN_RESEARCHER.
    - This is a general entity-linking rule, not specific to the benchmark. Document it in modules/spark/JOBS.md so the Phase 9 runtime can adopt it. No change to Phase 5 or 6 clustering in this phase.
31. config-version stays config-v1. The work order 2 change consolidated bucket names that no Phase 4 to 6 output depends on; this is recorded at the gate.
32. Work order 7 step 0 (ruling 29) also documents, in STORAGE.md, the new item schema names that spark-engineer and rag-engineer reported, and updates the validators if they should check them.

## Work order 6b: spark-engineer (dominant-candidate resolution and benchmark paths)

1. Implement ruling 30 and report, for every benchmark name, the candidate count, the top candidate's share, and the decision.
2. Report the corpus-wide scale of split identities: the number of exact display names with more than one canonical researcher where one candidate dominates under ruling 30, and the mentions and works held by the non-dominant candidates. This is report-only, for the final report.
3. Rerun job 5 and the path part of job 10 as a new bounded run, for the union of the seeded 50 pairs and the resolved benchmark pairs. Report each pair's path result.
4. Rerun what is needed so that a single conversion run under scratch/stores/ contains the updated path evidence and path embedding candidates; say which parts were rerun. Earlier runs stay untouched.
5. Run the storage validator CLI on every new output; all must pass.
Run sbt check twice. Report the new run IDs, the counts that changed, wall time, and free disk.

## Human rulings after review 1 (binding)

Review 1 returned REQUEST_CHANGES with two major findings (F1, F2) and minors M1 to M13. There is one repair round (repair-01), then review 2. Rulings 1 to 32 still apply. The human's live embedding run (run-b28edbd40f932f0342c05d5b6f57b46c, 13,023 Titan vectors, 0 rejected, ValidateCli 0 violations) finished before this repair and is Phase 8 evidence; agents never rerun it. The human also loaded the live backup into the file vector store scratch/stores-live/ and ran the live retrieval smoke test on 2026-11-06: 6/6 passed (profile-self@1 0.98, community-self@5 1.00, path-self@1 1.00, topic-self@1 1.00, paper-self@1 0.98, profile-community-filter 1.00). No agent reads from, writes to, or deletes anything under scratch/stores-live/.

33. F1 (DynamoDB number normalization): accepted; fix it in repair-01.
    - Normalize every N value to DynamoDB's canonical number text before PutItem (for example 4.0 becomes 4, 0.50 becomes 0.5), and decide Unchanged versus Conflict by comparing numbers numerically or as normalized text, in the live adapter and in InMemoryDynamo alike.
    - Confirm DynamoDB's number normalization against current AWS documentation (aws-docs MCP) and cite the page.
    - Add tests with "4.0", "0.0", "1.50", a large plain decimal, and a simulated readback where "4.0" was written and "4" is read back (must be Unchanged).
    - No change to the Spark item files. Canonical number text from Spark, if wanted, is a later spark-engineer item.
34. F2 (evidence tracing) and M4: accepted; fix them in repair-01.
    - The Neptune validator checks that every supporting work ID, inline and in evidence objects, exists as a Work vertex (EVIDENCE_WORK_MISSING), and that both edge endpoints have an AUTHORED edge to every cited work (EVIDENCE_NOT_AUTHORED).
    - The evidence-object check also covers sorted and distinct workIds, runId, and snapshot (M4).
    - Memory per ruling 27 (6g). If the AUTHORED set does not fit, say so and propose a streaming design; do not drop the check.
    - Rerun ValidateCli --kind neptune with --evidence-root scratch/stores/s3 on conversion run run-1d6a86491812ff94887270c1ab885e69 as evidence, and report the counts per new rule.
35. Cheap fixes in the same repair (storage-engineer):
    - M1: Cli.main exits non-zero on any Throwable, including JVM errors such as OutOfMemoryError, so a failure can never report success.
    - M3: the live Neptune pre-load check compares content hashes against the run manifest (preferred), or STORAGE.md is reworded to say it compares names only.
    - M8: CliSpec passes an explicit environment instead of reading the ambient shell environment.
    - Credential refresh. On 2026-11-05 the human's live embedding session 2 failed with "The security token included in the request is expired" after about 55 minutes. Diagnosis on 2026-11-06: the scholarchain-sdk profile's credential_process output (aws configure export-credentials --profile scholarchain --format process) carries an Expiration about 15 minutes ahead, and the overnight resume pass then ran about 3.5 hours without the error, so the cause is not settled. First inspect how each live client builds its credentials provider and report which case applies (credentials resolved once at startup, a provider that ignores Expiration, or something else). Then make every live client (Bedrock, S3, DynamoDB, Neptune, S3 Vectors) use a refreshing credentials provider for the named profile, never credentials resolved once at startup, and on an expired-token error refresh and retry the call once. Add unit tests for the provider wiring and the retry-on-expired path, with no network.
    - The repair must not change the embedding output format, vector keys, metadata, or the EmbedCli run-ID inputs. If a change would alter them, report BLOCKED-ON instead.
36. Residual risks and acknowledgements recorded at the gate (no repair):
    - M2: the vector key is chunkId over the rendering-v1 text, not the Phase 6 summaryId. Accepted, because it keeps key and text bound. A summaryId back-reference is a Phase 9 option.
    - M5: cross-check test of the two community-ID formulas (spark-engineer, Phase 11).
    - M6: live read and load sides share one object; Phase 10 wiring uses read-only constructors, and Phase 12 IAM enforces read-only runtime roles.
    - M7: the live Titan client sits in storage at the human's direction (work order 4); embedding-model client ownership (docs/agents.md exception 5) is acknowledged; the rag CLIs are local composition roots.
    - M9: Phase 9 applies dominant-name-v1 with one normalization shared with EntityLinkCache, and gets works counts from EntityLinkCache or ResearcherMetricCache (a Phase 9 work order).
    - M10: table prefix, Neptune loader role ARN, and other tunables become configuration keys before cloud window 2 (domain-engineer).
    - M11 and the work order 1 git status call: process deviations.
    - M12: parameterized openCypher query templates and a live GraphReadPort move to Phase 9 (storage-engineer, docs/agents.md exception 6); read-only graph credentials to Phase 12 (IAM).
    - M13: the human records the Bedrock embedding usage and cost in the gate; the S3 Vectors cost estimate goes to the Phase 12 cost plan.
37. Review 2 covers repair-01 against rulings 33 to 36. The items in ruling 36 are not re-raised unless the repair made them worse.

## Repair 1: storage-engineer (repair-01)

Read the Review 1 section of review-report.md (F1, F2, M1, M3, M4, M8) and rulings 33 to 37. Edit only modules/storage/**. If a change outside it seems unavoidable, report BLOCKED-ON with the owner.
1. F1 (ruling 33).
2. F2 and M4 (ruling 34), then the ValidateCli evidence run.
3. M1, M3, M8, and credential refresh (ruling 35), including the credential diagnosis.
4. Rerun the DynamoDB dry-run load on run-1d6a86491812ff94887270c1ab885e69 with sbt -J-Xmx6g, --passes 2 --recheck, to show pass 2 all Unchanged under the new normalization. Quote the printed result lines.
5. Update STORAGE.md for every change.
Run sbt check twice. Report per ruling 16, plus what changed for each finding with file paths, the credential diagnosis, and the new validator counts.

## Review 2: code-reviewer

Review the Phase 07 change set again; the orchestrator supplies it as for review 1. Focus on repair-01 against rulings 33 to 37. Verify, with file and line evidence, that F1, F2, M1, M3, M4, M8, and the credential refresh are resolved and tested without network access. The items in ruling 36 are accepted residual risks or acknowledgements: do not re-raise them unless the repair made them worse. Report any new critical or major finding introduced by the repair in full, and minors briefly. Return APPROVE or REQUEST_CHANGES. Do not edit any file.
