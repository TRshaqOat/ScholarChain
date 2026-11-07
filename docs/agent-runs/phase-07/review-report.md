# Phase 07 review reports

## Review 1: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 07, code review 1. Covers the complete Phase 7+8 change set (work orders 1 to 7 including 6b, rulings 1 to 32), checked against spec Phases 7 and 8 and D-04, D-06, D-09, D-10.
VERDICT: REQUEST_CHANGES

**Summary.** Two MAJOR findings block approval:
- On a live reload, the DynamoDB loader would report about 690,000 already-stored items as conflicts.
- The Neptune validator never checks that a COAUTHORED_WITH edge's supporting work IDs exist as Work vertices.

The other areas you asked me to check hold:
- Vector metadata never carries text, and a key cannot silently point to different text.
- Neptune IDs reuse the Phase 6 IDs.
- Read and load ports are separate types.
- Live calls need both `--live` and `--profile`, and no test builds an SDK client.
- The throttle and resume logic are correct, and the fake embedder is not throttled.
- Every change stays within its author's rows.
- `labels/` and the benchmark file are untouched.

SCOPE REVIEWED:
- The change set `/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/fce83340-d6b3-415c-bf98-6d74c2b42d52/scratchpad/review-changeset.txt`:
  - git status;
  - the diff of `build.sbt`, `project/Dependencies.scala`, `modules/core/**` and the modified `modules/spark/**` files;
  - the new files in `modules/storage/**`, `modules/rag/**` and `modules/spark`.
- In the tree, I read these in full: storage `STORAGE.md`, `Vectors.scala`, `Embedding.scala`, `Dynamo.scala`, `Neptune.scala`, `ObjectStore.scala`, `RenderedDocuments.scala`, `Common.scala`, every file under `live/*`, and the CLIs `Cli`, `EmbedCli`, `LoadDynamoCli`, `LoadNeptuneCli`, `LoadVectorsCli`, `LoadS3Cli`.
- Also read: rag `Render.scala`, `RenderCli.scala`, `SmokeCli.scala`, part of `Templates.scala`, and the live-smoke section of `RAG.md`.
- Spark, read in part: `StoreFiles.scala`, `StoreConversionJob.scala` (lines 1-560), `BenchmarkPathJob.scala` (resolution).
- Also read: `docs/agent-runs/phase-07/agent-report.md` (all work orders), `prompt.md`, spec Phases 7 and 8, `architect-decisions.md` D-04/06/09/10/13.
- I made two read-only Grep counts on the work order 6b DynamoDB item files under `scratch/stores/s3/curated/dynamodb/`.
- Nothing under `labels/` was read or listed.

FINDINGS (ranked):

**F1. MAJOR: live DynamoDB reload is not idempotent; a resumed or `--recheck` load reports Conflict for items it already wrote.**
- **Where:**
  - `/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/live/DynamoLive.scala:119-123`: `if e.hasItem && decode(e.item) == item then Unchanged else Conflict`.
  - The fake does the same at `/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/Dynamo.scala:549`: an exact `AttributeValue.N` string comparison.
  - Data source: `/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/StoreFiles.scala:30-34,81`. `plain()` writes Java `Double.toString` text such as `4.0` and `0.0`.
- **Observed in the item files (read-only Grep counts):**
  - ResearcherMetricCache run-1d6a8649…: 568,746 number values of the form `"N":"<int>.0"` across 3 part files. Examples: `"averageCoauthorsPerPaper":{"N":"4.0"}`, `"weightedDegree":{"N":"4.0"}`, `"participationCoefficient":{"N":"0.0"}`.
  - EntityLinkCache: 121,322 `identityConfidence` values of the form `"<int>.0"`.
- **Failure input:**
  - DynamoDB trims leading and trailing zeros from Number values (DynamoDB Developer Guide, supported data types; please confirm with the aws-docs server). So the stored `"4.0"` comes back as `"4"`.
  - A live `LoadDynamoCli` that stops part-way (retries exhausted, so ArtifactRegistry is NOT_RECORDED) is then rerun, or any rerun uses `--recheck`.
  - Every already-written item with such a number then fails the condition, its ALL_OLD copy differs from the file text, and it is counted as Conflict.
- **Impact:**
  - The reload exits 1, and ArtifactRegistry stays NOT_RECORDED, so the load cannot finish cleanly without manual work.
  - This breaks the spec Phase 7 gate "Loaders are idempotent" in exactly the resume case a load of 876,228 items needs.
  - The dry run's "pass 2 all Unchanged" (work order 7) does not show live idempotence, because the fake does not model DynamoDB's number normalization.
- **Owner:** storage-engineer.
  - Compare N values numerically, or normalize them before put and compare.
  - Make InMemoryDynamo store numbers normalized the same way.
  - Add a test with a `"4.0"` item.
  - Optionally, have STORAGE.md require canonical number text. Emitting it would then be a spark-engineer change in StoreFiles.

**F2. MAJOR: COAUTHORED_WITH evidence is checked only for shape, not traced to Work vertices (spec Phase 7 gate "COAUTHORED_WITH edges can be traced to supporting work evidence"; ruling 13).**
- **Where:** `/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/Neptune.scala`:
  - `137-154`: inline `supportingWorkIds` is checked only for `IdFormat.isWorkId`, sorted and distinct order, and count equal to jointPublicationCount.
  - `429-454`: `edgeIssues` checks edge endpoints only.
  - `456-472`: the evidence object is checked for edgeId, window, count and ID format only.
- **Gap:** no supporting work ID is ever looked up in `vertexInfo`, even though the Work vertices are already loaded there before edges are scanned. Nothing checks that both endpoints AUTHORED the cited works.
- **Failure input:** a coauthored-with row with `supportingWorkIds = dblp:conf/x/Y20` (well formed, count 1) where no such Work vertex exists. The validator and `InMemoryGraph` accept it, and Neptune would load an edge whose evidence cannot be resolved.
- **Impact:**
  - The gate is unproven for 1,590,754 edges.
  - Work order 7's 202-hop check compares counts and years, not work IDs.
  - Runtime answers would cite unresolvable evidence IDs, which breaks the provenance invariant.
- **Owner:** storage-engineer.
  - Add an `EVIDENCE_WORK_MISSING` check against the Work vertex labels already in `vertexInfo`, for inline lists and for evidence objects.
  - Optionally check AUTHORED membership.
  - Rerun ValidateCli on run-1d6a8649… as evidence.

**Minor findings:**
- **M1. `Cli.main` catches only `Exception`** (`/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/cli/Cli.scala:116-122`).
  - Work order 7 observed that a JVM Error (an OOM) in a non-forked runMain ended with sbt `[success]` and exit 0.
  - Validators and loaders can therefore fail open on exit code.
  - Owner: storage-engineer (the author already proposed the one-line fix). Until it is fixed, gate evidence must quote the printed result lines, not only the exit code.
- **M2. Vector key is not the Phase 6 summaryId** (`/Users/achyutpatel/Developer/Cloud/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/Render.scala:104-110`, `RenderedDocuments.scala:37-45`).
  - The key is chunkId over the rendering-v1 text, which differs from the literal wording of rulings 4 and 8 ("summaryId where one exists").
  - The choice is defensible, because it keeps key and text bound, and it is documented in STORAGE.md. But the vector metadata has no back-reference to the Phase 6 summaryId.
  - Owner: human acknowledgement at the gate; rag-engineer and storage-engineer if a back-reference is wanted.
- **M3. Live Neptune pre-load check compares key names only** (`LoadNeptuneCli.scala:105-108`). STORAGE.md:399 says it checks that the bucket holds "exactly those objects". Owner: storage-engineer (compare hashes, or reword).
- **M4. The evidence-object check skips some fields** (`Neptune.scala:456-472`): it does not check sorted or distinct `workIds`, `runId` or `snapshot`. Owner: storage-engineer.
- **M5. No test cross-checks the two community-ID formulas.** Spark's `CommunityPathJob.communityId` expression (`CommunityPathJob.scala:364`) and core `StableIds.communityId` are never compared, although work order 2 suggested it. The Neptune validator checks Community IDs by format only. Owner: spark-engineer.
- **M6. Read/write separation of the live adapters relies on composition.** `DynamoStore`, `S3ObjectStore` and `S3VectorStore` expose `.reader` and `.loader` on one object, so a runtime composition root could wire a loader. The port types are separate, but the read-only guarantee relies on composition and Phase 12 IAM. Owner: storage-engineer (consider separate read-only constructors) and service-engineer later.
- **M7. Two boundary notes on the embedding client.**
  - rag uses storage beyond "ports": `SmokeCli.scala:5-6` imports `storage.live.{BedrockTitanEmbedder, LiveAws}`; `RenderCli` uses `FileObjectStore` and `storage.cli.Cli`. CLAUDE.md allows rag -> storage (ports).
  - The live Titan client (`BedrockLive.scala`) was written by storage-engineer at the human's work order 4 direction, while docs/agents.md exception 5 assigns embedding-model clients to rag-engineer. Paths stayed in the author's rows.
  - Owner: human to record at the gate; factory-engineer to clarify exception 5 if needed.
- **M8. The CliSpec live opt-in test depends on the shell environment** (`CliSpec.scala:156`). It relies on the ambient environment being local. In a shell with a fully configured non-local `SCHOLARCHAIN_*` environment it would build a live DynamoDB client. Owner: storage-engineer (pass an explicit environment, as the rag CLIs do).
- **M9. Name-linking mismatch for Phase 9.**
  - `dominant-name-v1` (exact display name, trim) and the EntityLinkCache `name-v1` key (canonical form, homonym number dropped) are different normalizations.
  - EntityLinkCache items carry no AUTHORED works count, so the Phase 9 runtime cannot apply ruling 30 from that table alone.
  - Owner: human decision; then spark-engineer and storage-engineer.
- **M10. Some tunable values are code constants, not configuration (externalized-configuration invariant).**
  - In code: the selection seed and quotas, InlineEvidenceMax, ranking size, retry and throttle defaults, `--table-prefix`, `--loader-role-arn`.
  - These values are versioned through policy names and run fingerprints.
  - Owner: domain-engineer (configuration keys before cloud window 2), as work order 4 RISK 1 says.
- **M11. Process: a guard block was worked around.** In work order 4, after `guard-dangerous.sh` blocked a heredoc, the agent re-ran the edit as a script file in the scratchpad. That is an indirection around a guard (a permission prompt applied). Work orders 2 and 5 correctly switched to the Edit tool instead. Work order 1's `git status` deviation is already recorded. Owner: human and factory-engineer awareness.
- **M12. Spec Phase 7 items not delivered.** "Parameterized openCypher queries" and "read-only graph credentials" were not delivered: there is no live GraphReadPort or query templates. They are not in the gate list, and no ruling explicitly defers them. Owner: human, to defer to Phase 9 explicitly (storage-engineer per agents.md exception 6).
- **M13. Spec Phase 8 gate "Cost and object counts are reported" is only partly met.** There is no Bedrock embedding cost estimate and no S3 Vectors storage or put cost estimate. The DynamoDB cost uses an unverified price (ruling 18). Owner: storage-engineer (estimate) and documentation-engineer later.

OWNERSHIP CHECK (changed path -> owner -> author matches):
- `build.sbt`, `project/Dependencies.scala` -> build-engineer -> work order 3, build-engineer: yes.
- `modules/core/**` (the 6 main and 6 test files in the diff) -> domain-engineer -> work order 2: yes.
- `modules/spark/**` (JOBS.md, CommunityPathJob, SparkSettings, Summaries, TemporalSummarySpec, BenchmarkPathJob, Selection, StoreConversionJob, StoreFiles, PipelineFixture, StoreConversionSpec) -> spark-engineer -> work orders 6 and 6b: yes.
- `modules/storage/**` -> storage-engineer -> work orders 1, 4, 7: yes. Artifact-class note on the Titan client: see M7.
- `modules/rag/**` -> rag-engineer -> work order 5: yes. `prompts/**` is untouched.
- `docs/agent-runs/phase-07/agent-report.md` -> orchestrator assembly into a phase folder with no `commit.txt` -> yes.
- `spec/`, `labels/`, `experiments/`: unchanged, per the change set section 2. The benchmark CSV is only read (`BenchmarkPathJob`).
- Run outputs under `scratch/stores/` follow ruling 2. No `scratch/spark` or `scratch/hadoop` change is claimed, and I did not verify that.

FLOOR AND FENCE CHECK:
- No change to `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, hooks, permission rules, `check` or `markTestRun`.
- The `build.sbt` change adds only `libraryDependencies` to `storage`. The module graph is unchanged, and the new SDK artifacts use the existing `Versions.awsSdk` 2.41.32 (D-09).
- No widening found. One process note is M11.

EVIDENCE GAPS:
- **sbt check:** only work order 7's summary is available ("437, 0 failed, marker written 20:31:27"). There is no raw sbt output, the orchestrator did not run sbt, and I cannot confirm that `.claude/.last-test-run` is newer than every changed source.
- **Work order 3:** the classpath before/after diff ("+52 jars", "35 artifacts all 2.41.32") is claimed without output.
- **Work order 6:** the "independent Python recheck" of the selection order is claimed without output.
- **Work order 7:** the live run ID `run-b28edbd4…`, computed offline in jshell, is a claim. It holds only if the human's environment uses config-v1 and identical rendered files.
- **Service limits and dates:** these come from aws-docs reads I cannot repeat. Examples: S3 Vectors ENHANCED as the default for buckets created on or after 2026-09-30, the 40 KB / 2 KB metadata limits, the 100 filter constraints. Re-verify them in the Phase 12 plan.
- **DynamoDB price:** 0.625 USD per million WRU is UNVERIFIED (accepted by ruling 18).
- **Live adapters:** request building and response parsing are unit-tested only (ruling 26). The bounded AWS integration tests stay an open gate item (ruling 1).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.

TEST RESULTS (as supplied by the orchestrator, quoted): "the last observed `sbt check` (work order 7, runs 3 and 4) reported exit 0, test blocks 166 + 49 + 15 + 94 + 43 + 70 = 437, 0 failed, marker written. The orchestrator did not run sbt itself."

VALIDATION RESULTS (from the agent reports, not re-run):
- ValidateCli on the work order 6b and 7 outputs:
  - neptune: 3,635,231 elements, 0 violations;
  - dynamodb: 876,228 items, 0 violations;
  - candidates and rendered: 13,023 each, 0 violations;
  - vectors: 13,023, 0 violations.
- Dry-run second passes were Unchanged or skipped. The SmokeCli fake run passed 6 of 6 tests.
- F1 shows that the DynamoDB dry-run idempotence does not carry over to live, and F2 shows the 0-violation Neptune result does not cover evidence-to-Work resolution.

ASSUMPTIONS:
- DynamoDB trims trailing zeros of Number values, as the DynamoDB Developer Guide documents. I could not open the page with my tools; the owner should confirm it with aws-docs before fixing F1.
- `representativeWorkIds` is complete wherever the validator's count check passed.

RISKS:
- The human's live embedding run (work order 7 command) is not blocked by F1 or F2. The throttle (1,200 ms, all attempts), resume (key, textHash and model), `--max`, and write-once publish are correct as written.
- F1 bites in cloud window 2 when the live DynamoDB load runs.

MANUAL ACTION REQUIRED:
- Human to acknowledge M2 (vector key policy) and M12 (openCypher templates and read-only credentials deferred) at the gate.
- Route M7 (embedding-client ownership and the rag -> storage boundary) to the human, and to factory-engineer if a clarification is wanted.

BLOCKED-ON OWNER: none for this review.
BLOCKED-ON EVIDENCE: none.

NEXT GATE: route F1 and F2 (and the minors) to storage-engineer, M5 to spark-engineer, M10 to domain-engineer; then re-review. The human gate follows an APPROVE.
````

## Review 2: code-reviewer

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 07, Review 2. This checks repair-01 (storage-engineer) against rulings 33 to 37. It verifies F1, F2, M1, M3, M4, M8 and the credential refresh, and looks for new findings introduced by the repair.
VERDICT: APPROVE

**Summary.** F1, F2 (with M4), M1, M3, M8 and the credential refresh are all fixed. Each fix has a test that runs without network access. The repair stayed inside `modules/storage/**`. It does not change the embedding output format, the vector keys, the metadata, or the EmbedCli run-ID inputs. I found no new CRITICAL or MAJOR finding, and six MINOR ones (N1 to N6). Nothing in ruling 36 got worse.

SCOPE REVIEWED:
- **Change set:** `/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/78fa8591-24cc-40b0-ab9d-fc55d80eef3b/scratchpad/review2-changeset.txt`, sections 1 to 3.
- **Compared against the review 1 change set** (`.../fce83340-.../scratchpad/review-changeset.txt`), section by section:
  - All tracked diffs, and every `modules/rag/**` and `modules/spark/**` section, have the same line counts as in review 1 (offset by 4 header lines). So nothing outside storage changed. This is a line-count check, not a byte-for-byte diff.
  - In storage, the files that changed are `Dynamo`, `Neptune`, `Cli`, the six CLIs, `DynamoLive`, `LiveAws`, `S3Live`, the tests, and `STORAGE.md`.
  - `Embedding.scala`, `BedrockLive.scala`, `Vectors.scala`, `VectorsLive.scala`, `NeptuneLive.scala`, `ObjectStore.scala`, `RunLedger.scala` and `EmbeddingCandidates.scala` have unchanged lengths.
  - `EmbeddingRunner.runIdFor` (`Embedding.scala:123-124`) is identical to review 1.
- **Read in the tree:**
  - `Dynamo.scala` (lines 1-130, 500-606), `live/DynamoLive.scala`, `live/LiveAws.scala`, `live/S3Live.scala`, `live/BedrockLive.scala`;
  - `Neptune.scala` (lines 100-190, 325-724), `cli/Cli.scala`, `cli/ValidateCli.scala`, `cli/LoadNeptuneCli.scala`, `cli/EmbedCli.scala`, `cli/LoadDynamoCli.scala` (lines 180-239);
  - `CliSpec.scala` and `NeptuneSpec.scala` (lines 95-210) in full, `DynamoSpec.scala` (lines 210-291), `LiveRequestSpec.scala` (lines 1-30, 125-249);
  - `STORAGE.md` (lines 96-143, 370-419, 451);
  - `docs/agent-runs/phase-07/repair-01.md`, `review-report.md`, and the rulings quoted in the work order.
- I grepped the `docs/agent-runs/phase-07/evidence/` files for credential patterns: 0 matches.
- Nothing under `labels/` or `scratch/stores-live/` was read, listed or grepped.

VERIFICATION OF RULINGS 33 TO 35

**F1 (ruling 33): RESOLVED.**
- `Dynamo.scala:30-31`: `canonicalNumber` uses `java.math.BigDecimal(n).stripTrailingZeros.toPlainString`, which is exact. `canonical` and `canonicalItem` (34-41) apply it at every depth of L and M. `sameItem` (44-45) compares canonical forms on both sides.
- Fake: `InMemoryDynamo` stores `canonicalItem` (557) and decides Unchanged with `sameItem` (570).
- Live: `DynamoLive.scala:115-125` sends the canonical item and compares the ALL_OLD item with `sameItem`. Supersede also sends the canonical item (164).
- Because both sides are canonicalized, the result does not depend on the exact text DynamoDB returns.
- Tests (`DynamoSpec.scala:222-290`) cover `4.0`, `0.0`/`-0.0`, `1.50`, `1E+3`, a 37-digit plain decimal, and nesting. They cover fake reloads against `4.0` and `4`, and a Conflict on a different value. With a stub `DynamoDbClient` they check the wire text (`4`, `0`, `1.5`), a readback of `4` for a written `4.0` (Unchanged), and a readback of `5` (Conflict). No network.
- The documentation citation is in STORAGE.md:141 and :451. I cannot re-open it with my tools (see evidence gaps).
- The Spark item files are unchanged, as ruling 33 requires.

**F2 and M4 (ruling 34): RESOLVED.**
- Scan order: `Neptune.scala:407-412` sorts vertex files first, then AUTHORED edge files, then the other edges. `ElementKind` is `Vertex, Edge` (38-39), so the ordinals sort correctly.
- The AUTHORED pairs are collected at 396 and 443.
- `traceWorks` (495-517) emits:
  - `EVIDENCE_WORK_MISSING` when a cited work is not a Work vertex in `vertexInfo`;
  - `EVIDENCE_NOT_AUTHORED` when an endpoint has no AUTHORED edge to a cited work.
  It runs for inline lists (481-483) and for evidence objects (484-486, 557).
- M4 (544-555): `EVIDENCE_OBJECT_ORDER` checks that workIds are sorted and distinct. `EVIDENCE_OBJECT_PROVENANCE` checks that runId and snapshot equal the edge's.
- Rule counts are uncapped (Collector `byCode`, 357-368). ValidateCli prints them, zeros included (`ValidateCli.scala:45-48`).
- Tests:
  - `NeptuneSpec.scala:101-120` uses review 1's failure input `dblp:conf/x/Y20`, plus a work that rb never authored.
  - 143-202 cover object order (reversed and duplicate), runId, snapshot, a missing work, a missing AUTHORED edge, and the case with no evidence root.
  - `CliSpec.scala:30-32` checks the `rule ... 0` lines.
- The real-data rerun is quoted under VALIDATION RESULTS. Inline plus object edges equal all 1,590,754 COAUTHORED_WITH edges, so every edge was traced.

**M1: RESOLVED.**
- `Cli.scala:122-130`: `exitCode` catches any Throwable and returns 1. Printing the message is itself guarded, in case printing fails too.
- `Cli.main` calls `sys.exit` with that code (115-117).
- `CliSpec.scala:232-239` covers OutOfMemoryError, StackOverflowError, an ordinary Exception, and normal return codes.

**M3: RESOLVED.** I checked the hash comparison (preferred), not the reword.
- `LoadNeptuneCli.scala:163-190`, `preloadProblems`, checks:
  - that the manifest run matches;
  - missing and extra objects, for the local copy and for the bucket;
  - the local SHA-256 against the manifest;
  - the live stored SHA-256 against the manifest.
- The live check reads `ChecksumSHA256` with HEAD (`S3Live.scala:41-57, 75-82`). A composite multipart value counts as no checksum, and the check refuses it.
- `CliSpec.scala:254-290` includes the case of same names with different bytes.
- STORAGE.md:417 describes the hash check.

**M8: RESOLVED.**
- All six CLIs take `env` and pass it to `Cli.withArgs` and `Cli.config` (`Cli.scala:53, 133-147`; grep of `cli/`).
- `CliSpec.scala:17` defines an explicit empty `localEnv`, and every CLI call in the spec passes it.
- 241-252 shows that an explicit `SCHOLARCHAIN_ENVIRONMENT=prod` is honoured: it is refused for placeholder values before any client is built.

**Credential refresh (ruling 35): RESOLVED.**
- The diagnosis ("something else": the SDK's `ProcessCredentialsProvider` honours Expiration, but our RetryPolicy did not retry the 403) is recorded in repair-01 and in STORAGE.md:384-394. It rests on a `javap` reading I cannot reproduce.
- `LiveAws.scala:39-57`: `RefreshingCredentials` builds its delegate lazily, and `refresh()` drops it. 71-83 hold one provider per profile in a TrieMap.
- 85-107: all five clients (Bedrock, S3, DynamoDB, S3 Vectors, Neptune) are configured with that provider.
- 115-142: `isExpiredToken` matches the codes or the message, and `retryOnExpiredToken` makes exactly one retry. `guarded` refreshes and retries.
- Every live SDK call in storage goes through `guarded`. I grepped every `client.` call in `live/*.scala`: `NeptuneLive` 100-106, `DynamoLive` 117/131/141/160, `S3Live` 78/88/92/107, `VectorsLive` 125/146/176, `BedrockLive` 21.
- `LiveRequestSpec.scala:159-248` covers lazy build and rebuild, retrying once, no retry on a second expiry, no retry on another 403, `guarded` refreshing, and the wiring of all five clients to the same provider instance, with nothing resolved during the build. No network.
- The repair leaves `entity-resolution` `BedrockLlmClient` alone. It is outside storage and outside ruling 35's list.

**Ruling 35 output constraint: HELD.** EmbedCli changed only by the `env` parameter (`EmbedCli.scala:25, 33`). `runIdFor` and `Embedding.scala` are unchanged in length. `VectorsLive` and `Vectors` are unchanged in length.

FINDINGS (ranked; no CRITICAL, no MAJOR):

**N1. MINOR: the expired-token retry bypasses the Bedrock throttle, and the code comment contradicts this.**
- **Where:** `LiveAws.scala:128-133` (the retry runs inside the call that `BedrockLive.scala:21` makes). The runner takes the throttle slot before `embed`, so the second InvokeModel is not throttled. `LiveAws.scala:59-62` still says "every Bedrock attempt passes the throttle".
- **Failure input:** an expired token on the 50th call in a 60-second window.
- **Impact:** 51 InvokeModel requests in that window, the first rejected at authentication. Negligible against the 60-per-minute quota, but a literal deviation from rulings 9 and 29, and a stale comment.
- **Owner:** storage-engineer (route the retry through the throttle, or document the exception).

**N2. MINOR: an expired token on HeadObject is probably not detected.**
- **Where:** `S3Live.scala:75-82` with `LiveAws.scala:121-123`.
- **Failure input:** an expired token during the M3 pre-load HEAD calls. S3 HEAD error responses have no body, so the exception most likely carries neither the `ExpiredToken` code nor the message. This is unverified SDK behaviour.
- **Impact:** no retry. `LoadNeptuneCli` aborts with exit 1 through `Cli.exitCode`. This fails safe, but it does not meet "refresh and retry once" for this one call.
- **Owner:** storage-engineer (document it, or treat a 403 on HEAD as a candidate for one refresh).

**N3. MINOR: detection by error code is probably not independently tested.**
- **Where:** `LiveRequestSpec.scala:140-150, 171-172`. `expired(code)` sets `errorMessage` to the expired-token phrase. `AwsServiceException.getMessage` falls back to that text when no message is set, so the message fallback likely satisfies the "ExpiredToken" and "ExpiredTokenException" assertions on its own.
- **Failure input:** a typo in `ExpiredTokenCodes` would probably still pass.
- **Impact:** the real S3 message ("The provided token has expired.") does not contain the phrase, so S3 detection depends on the code alone. The code is correct by reading.
- **Owner:** storage-engineer (build a test exception whose message lacks the phrase).

**N4. MINOR: `canonicalNumber` can expand a huge exponent, and numbers are not range-checked.**
- **Where:** `Dynamo.scala:30-31, 79-80`. `numberOk` checks only significant digits, not DynamoDB's range (1E-130 to below 1E+126).
- **Failure input:** an item value such as `{"N":"1E+999999999"}`. It passes `numberOk`, then `toPlainString` builds a string of about 1 GB.
- **Impact:** OutOfMemoryError, which now exits 1 because of M1. Also, an `N` value built in code with invalid text makes `canonicalItem` throw `NumberFormatException` instead of returning `Left`. The inputs come from the pipeline, so this is low risk.
- **Owner:** storage-engineer (add a range check in `numberOk`).

**N5. MINOR: a refresh storm is possible under loader concurrency.**
- **Where:** `LiveAws.scala:44-55, 139`. `guarded` refreshes every profile on each expired-token error.
- **Failure input:** `LoadDynamoCli --concurrency 32` with an expiry seen by many requests in flight.
- **Impact:** the delegate can be dropped and rebuilt repeatedly, rerunning `credential_process` up to about once per thread. This costs efficiency only; it stays correct.
- **Owner:** storage-engineer.

**N6. MINOR (existing behaviour, documented): ValidateCli without `--evidence-root` exits 0 with object-evidence edges untraced.**
- **Where:** `Neptune.scala:487-489`; documented at STORAGE.md:105. The untraced edges are counted as `edges-object-not-opened`.
- **Impact:** gate evidence must always pass `--evidence-root`. The repair's own run did.
- **Owner:** storage-engineer (optional: exit 1, or warn, when that count is non-zero).

Ruling 36 items (M2, M5, M6, M7, M9, M10, M11, M12, M13): none got worse. The new `S3ObjectStore.sha256` is read-only (M6). No new configurable values were added (M10). The rag -> storage use is unchanged (M7).

OWNERSHIP CHECK (changed path -> owner -> author matches):
- `modules/storage/**` (repair-01 edits) -> storage-engineer -> yes.
- `build.sbt`, `project/Dependencies.scala`, `modules/core/**`, `modules/spark/**`, `modules/rag/**` -> unchanged since review 1 (same section line counts) -> n/a.
- `docs/agent-runs/phase-07/repair-01.md`, `review-report.md`, `agent-report.md` -> orchestrator assembly into a phase folder with no `commit.txt` -> yes.
- `docs/agent-runs/phase-07/evidence/*` (4 files, new since review 1) -> human architect (`docs/agent-runs/**`). No agent report claims them, and repair-01 says FILES CREATED: none. I assume the human wrote them after the live run; the human should confirm. They contain no credential patterns.
- `scratch/stores/ledger/run-62359da6....jsonl` (appended entries) -> run output allowed by ruling 2.
- `spec/`, `labels/`, `experiments/`, `scratch/spark`, `scratch/hadoop`, `scratch/stores-live`: no change is claimed, and I did not verify that.

FLOOR AND FENCE CHECK:
- No change to `.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, hooks, permission rules, `check`, `markTestRun`, or the build.
- `LiveAws.overrides` went from a `private def` to a public `def`. This is a code-visibility change only.
- The repair says the guard blocked two heredoc edits as false positives, and it then switched to the Edit tool, not a script workaround. That is correct handling, and the review 1 M11 pattern did not recur.
- No widening found.

EVIDENCE GAPS:
- **sbt check:** I have only the repair's summary (two runs, exit 0, 448 tests = 166 + 15 + 94 + 60 + 43 + 70, 0 failed) and no raw output.
  - Supporting it: the orchestrator's read-only observation that the marker is 2026-11-06 18:06:54 and that `find ... -newer` printed nothing.
  - My `test(` count in storage is 60, which matches the 49 + 11 claimed.
- **ValidateCli neptune and LoadDynamoCli dry-run outputs:** quoted from the repair only. By the repair's own account (risk 3), both ran on a build from before the final LiveAws change. That change does not touch the code paths these runs exercise.
- **DynamoDB number-trimming citation and the `javap` reading of `ProcessCredentialsProvider`:** I cannot re-verify either with my tools.
- **No mutation runs.** By reading, the F1, F2, M3, M8 and credentials tests use functions or rule codes that did not exist before, so they would fail without the change.
- **Live behaviour is untested:** the real expired-token error codes, HEAD checksum responses, and the SDK's handling of a supplied credentials provider. Bounded AWS integration tests remain open for cloud window 2 (rulings 1 and 26).

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash tool). Read, Grep and Glob only.

TEST RESULTS (as supplied by the orchestrator, quoted): "From the repair report: `sbt check` run 1 exit 0, 448 tests (166 + 15 + 94 + 60 + 43 + 70), 0 failed, marker 2026-11-06 18:02:19; run 2 exit 0, the same 448, 0 failed, marker 18:06:54. Orchestrator observation, read-only: `.claude/.last-test-run` mtime is 2026-11-06 18:06:54, and `find modules build.sbt project -newer .claude/.last-test-run -type f -not -path '*/target/*'` printed nothing."

VALIDATION RESULTS (from repair-01, not re-run):
- **ValidateCli neptune** on run-1d6a86491812ff94887270c1ab885e69 with `--evidence-root scratch/stores/s3`:
  - evidence: edges-inline 1590180, edges-object 574, work-ids-traced 2038078;
  - rules: EVIDENCE_WORK_MISSING 0, EVIDENCE_NOT_AUTHORED 0, EVIDENCE_OBJECT_ORDER 0, EVIDENCE_OBJECT_PROVENANCE 0;
  - violations 0, OK.
- **LoadDynamoCli dry run:**
  - pass 2 all Unchanged: 9 ArtifactRegistry, 287780 EntityLinkCache, 588439 ResearcherMetricCache;
  - 0 conflicts, 0 invalid.

ASSUMPTIONS:
- `AwsServiceException.getMessage` falls back to `errorMessage` when no message is set (basis for N3).
- S3 HEAD errors carry no error code (basis for N2). Both are SDK behaviours I did not observe.
- The `docs/agent-runs/phase-07/evidence/` files were written by the human.

RISKS:
- The root cause of the expired token is still not settled. A second expiry in a row still stops a run as FAILED, which is resumable.
- The SDK's 15 s refresh margin is unchanged. The repair offers a human decision on this under MANUAL ACTION.

MANUAL ACTION REQUIRED:
- Human: confirm that you wrote `docs/agent-runs/phase-07/evidence/*`.
- Human: decide whether to widen the SDK credential refresh margin (repair-01, MANUAL ACTION).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: route N1 to N6 to storage-engineer (optional, non-blocking; they can go into a later storage work order). Then the human gate for Phase 07 on this APPROVE.
````
