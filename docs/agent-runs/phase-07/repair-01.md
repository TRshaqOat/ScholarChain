# Phase 07 repair reports

## Repair 1: storage-engineer

````text
STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Phase 07, Repair 1 (repair-01). Covers F1, F2 with M4, M1, M3, M8, and the credential refresh (rulings 33 to 35), plus the ValidateCli and DynamoDB evidence runs and the STORAGE.md update. I edited only files under modules/storage/**.

**Summary.** Both major findings are fixed and shown on real data:
- **F2:** the Neptune validator now checks every one of the 1,590,754 COAUTHORED_WITH edges on run-1d6a86491812ff94887270c1ab885e69 against the Work vertices and AUTHORED edges. That is 2,038,078 work IDs, with 0 violations.
- **F1:** the DynamoDB dry-run reload is all Unchanged on pass 2. The fake now stores numbers as DynamoDB does (`4`), while the files still say `4.0`, so this run exercises exactly the case the review flagged.
- **Tests:** `sbt check` passed twice: exit 0, 448 tests, 0 failed.

**Credential diagnosis (ruling 35).** The case is "something else". The cause of the single failure is not settled.
- Before this repair, each client built its own `ProfileCredentialsProvider.create(profile)`. For a `credential_process` profile this hands off to `ProcessCredentialsProvider`.
- I disassembled `auth-2.41.32.jar` with `javap` (no network). That provider caches the credentials and refreshes them from their Expiration: it refreshes early 15 s before Expiration (default threshold 15 s, one caller blocks), and they go stale exactly at Expiration.
- So credentials were not resolved once at startup, and the provider did not ignore Expiration.
- What turned one bad request into a dead run was our own code: the expired-token error is a 403, which `RetryPolicy` does not retry, so one expired request stopped the whole run as FAILED.
- Two unproven explanations for why one request carried an expired token:
  - at the 15 s refresh point, `credential_process` returned credentials that expire at the same moment (for example the AWS CLI handing back its cached ones);
  - the laptop clock was behind AWS by more than 15 s.
- A 15-minute Expiration means about 4 refreshes per hour, so a rare race at that boundary fits one failure in 55 minutes and none in 3.5 hours.

FILES CREATED: none

FILES MODIFIED (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/storage/):
- **F1:**
  - `src/main/scala/scholarchain/storage/Dynamo.scala`: new `AttributeValue.canonicalNumber`, `canonical`, `canonicalItem` and `sameItem`. They use `java.math.BigDecimal(n).stripTrailingZeros.toPlainString`, which is exact (no 34-digit rounding) and applies inside L and M too. `InMemoryDynamo` stores the canonical item and decides Unchanged by `sameItem`.
  - `src/main/scala/scholarchain/storage/live/DynamoLive.scala`: PutItem (both putNew and replace) sends the canonical item. On a failed condition it compares the returned item with `sameItem`.
- **F2 and M4:**
  - `src/main/scala/scholarchain/storage/Neptune.scala`:
    - The scan keeps an AUTHORED set of (researcher, work) pairs and reads AUTHORED files before the other edge files (explicit sort).
    - New rules: `EVIDENCE_WORK_MISSING` and `EVIDENCE_NOT_AUTHORED`, for inline lists and evidence objects alike. M4 adds `EVIDENCE_OBJECT_ORDER` (workIds sorted and distinct) and `EVIDENCE_OBJECT_PROVENANCE` (runId and snapshot equal the edge's).
    - `GraphScan` gains `ruleCounts` (not capped) and `evidenceChecks`.
  - `src/main/scala/scholarchain/storage/cli/ValidateCli.scala`: prints `evidence <name> <n>` lines and a `rule <code> <n>` line for all four rules, zero included.
- **M1:** `src/main/scala/scholarchain/storage/cli/Cli.scala`: new `Cli.exitCode` catches any Throwable and returns 1. `Cli.main` uses it. The same file also gets the M8 change: `config(env)` and `withArgs(..., env)`.
- **M8:** the six CLIs (`ValidateCli`, `EmbedCli`, `LoadDynamoCli`, `LoadNeptuneCli`, `LoadS3Cli`, `LoadVectorsCli` under `src/main/scala/scholarchain/storage/cli/`) take `env: Map[String, String] = sys.env`. `main` uses `run(_, _)`.
- **M3:**
  - `src/main/scala/scholarchain/storage/live/S3Live.scala`: new `S3Requests.head` (HeadObject with ChecksumMode ENABLED), `S3Requests.sha256Of` (base64 to `sha256:<hex>`; a composite multipart checksum gives None), and `S3ObjectStore.sha256(key)`.
  - `src/main/scala/scholarchain/storage/cli/LoadNeptuneCli.scala`: new `preloadProblems`. Against the store-conversion run manifest, the local copy and the bucket must both hold exactly the manifest's objects under the load prefix, local files must hash to the manifest SHA-256, and each live object's stored checksum must equal it. This replaces the names-only check.
- **Credentials:** `src/main/scala/scholarchain/storage/live/LiveAws.scala`:
  - New `RefreshingCredentials`, one per profile in a TrieMap, shared by all five clients. It builds its delegate on first use; `refresh()` drops the delegate without closing it, so concurrent loader threads are safe.
  - The client constructors share one `configure` helper and take an optional override configuration. The default keeps `LiveAws.bedrock(t)`, which rag's SmokeCli calls, source-compatible; rag compiled and its tests passed.
  - New `isExpiredToken` (error code ExpiredToken or ExpiredTokenException, or the error message) and `retryOnExpiredToken`. `guarded` refreshes and retries the call once.
- **Tests:**
  - `src/test/scala/scholarchain/storage/DynamoSpec.scala`: 3 new tests.
    - Canonical text: `4.0`→`4`, `0.0` and `-0.0`→`0`, `1.50`→`1.5`, `1E+3`→`1000`, and a 37-digit plain decimal kept unrounded.
    - Fake: reload is Unchanged against either `4.0` or `4`; a different value is a Conflict.
    - Live adapter with a stub `DynamoDbClient` (no network): the wire carries `4`, `0` and `1.5`. A readback of `4` for a written `4.0` is Unchanged, and a readback of `5` is a Conflict.
  - `src/test/scala/scholarchain/storage/NeptuneSpec.scala`:
    - New F2 inline test using the review's failure input `dblp:conf/x/Y20`, plus a work that rb never AUTHORED.
    - The long-evidence test now covers object order (reversed and duplicate IDs), runId, snapshot, a missing work, a missing AUTHORED edge, and the case with no evidence root.
    - Expected graph counts are now 7 vertices and 7 edges.
  - `src/test/scala/scholarchain/storage/Fixtures.scala`: the fixture graph now has AUTHORED edges ra/rb × w1/w2.
  - `src/test/scala/scholarchain/storage/CliSpec.scala`:
    - Every CLI call passes an explicit empty `localEnv` (M8).
    - New M1 test (OutOfMemoryError, StackOverflowError, an Exception, and normal codes) and a new M8 test (an explicit `SCHOLARCHAIN_ENVIRONMENT=prod` is refused for placeholder values before any client is built).
    - New M3 test of `preloadProblems`: same names but different bytes is refused, plus missing and extra objects, a local mismatch, a wrong run, and the `sha256Of` and `head` requests.
    - The validator test checks the `rule ... 0` and `evidence ...` lines.
  - `src/test/scala/scholarchain/storage/LiveRequestSpec.scala`: 4 new tests, none touching the network:
    - lazy build and rebuild after refresh;
    - refresh and retry once, with no retry on a second expiry or on another 403;
    - `guarded` refreshes the profile's provider;
    - provider wiring: all five clients are built with an empty in-memory profile file and an injected credentials factory, and each one's `serviceClientConfiguration().credentialsProvider()` is the same `RefreshingCredentials` instance; nothing is resolved during the build.
- `STORAGE.md` covers every change:
  - number normalization, with the doc citation;
  - the new validator rules, rule counts, scan order and memory;
  - the "Credentials (ruling 35)" section with the diagnosis;
  - S3 `sha256` and the M3 pre-load check;
  - M1 exit behaviour and the M8 explicit environment;
  - the ValidateCli output;
  - the DynamoDB Number service-limit row.

FILES DELETED: none. No file outside modules/storage was edited. scratch/spark, scratch/hadoop, scratch/stores-live, experiments/benchmark and labels/ were not touched. The only scratch/ write is the DynamoDB dry run's LOAD_SESSION entries appended to scratch/stores/ledger/run-62359da6f98c0613ea9684d7db3dafc6.jsonl. I created no temporary checkpoints under scratch/, so I deleted nothing.

SCHEMAS AND KEYS: no table, key, prefix, graph label, vector index, or item file format changed.
- **DynamoDB:** the six tables, partition and sort keys, and TTL only on QuerySession and EvidenceBundle are unchanged. The one change is that stored Number text is now canonical (trimmed), which matches what DynamoDB stores anyway.
- **Neptune:** same CSV contract. New validator rules: `EVIDENCE_WORK_MISSING`, `EVIDENCE_NOT_AUTHORED`, `EVIDENCE_OBJECT_ORDER`, `EVIDENCE_OBJECT_PROVENANCE`.
- **S3 and S3 Vectors:** unchanged.

METADATA FIELDS: unchanged.
- Non-filterable: vectorKey, textHash, textRef.
- Filterable: entityIds, window, documentType, sourceDataset, embeddingModelVersion, runId, topicIds, institutionIds, communityId.
- The embedding output format, vector keys, metadata and EmbedCli run-ID inputs are unchanged (the only EmbedCli change is the env parameter).

BATCH SIZES AND RETRY POLICY: batch sizes are unchanged.
- DynamoDB: conditional PutItem in groups of 1,000.
- Vectors: 100 per batch, at most 500.
- Embedding: one text per call; live throttle at most 50 per minute.
- `RetryPolicy` is unchanged. New: every live call gets one extra attempt after an expired-token error, preceded by a credentials refresh. AWS rejects such a request before acting on it, so the retry cannot repeat an effect.

IDEMPOTENCY STRATEGY:
- DynamoDB: conditional `attribute_not_exists`. Unchanged versus Conflict now compares canonical numbers on both sides, in the live adapter and the fake alike. ArtifactRegistry `contentHash` still skips identical reloads.
- Neptune: unchanged (all-or-nothing load; PROPERTY_CONFLICT on a changed element).
- Live Neptune pre-load: now checks SHA-256 content hashes against the run manifest.

SERVICE LIMITS:
- DynamoDB Number: up to 38 digits of precision; "In DynamoDB, numbers are represented as variable length. Leading and trailing zeroes are trimmed." Source: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html, read with aws-docs on 2026-11-06. The Constraints page (https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html, same date) confirms the 38 digits.
- SDK credential behaviour, from `javap` on auth-2.41.32.jar, not a documentation page: ProcessCredentialsProvider refreshes early 15 s before Expiration, credentials go stale at Expiration, and asynchronous refresh is off by default.
- All other limits are unchanged; they are in STORAGE.md "Service limits", accessed 2026-11-05.

CLEANUP PROCEDURE: unit tests use only JVM temporary directories and delete only those. The client-wiring test builds and closes 5 SDK clients in memory with no calls. This repair's only scratch output is the ledger entries named under FILES DELETED; only the human removes anything under scratch/ (scripts/cleanup.sh).

COMMANDS RUN (from the repository root; no git, no aws, no AWS or Bedrock calls, no `--live`, no downloads, nothing under labels/):
- `sbt -batch storage/Test/compile rag/Test/compile`, then `sbt -batch storage/Test/compile`
- `sbt -batch storage/test`: 60 succeeded, 0 failed
- `sbt -batch storage/scalafmtAll storage/Test/scalafmtAll`
- `sbt -batch -J-Xmx6g "storage/runMain scholarchain.storage.cli.ValidateCli --kind neptune --dir scratch/stores/s3/graph/neptune/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --evidence-root scratch/stores/s3"`
- `sbt -batch -J-Xmx6g "storage/runMain scholarchain.storage.cli.LoadDynamoCli --dir scratch/stores/s3/curated/dynamodb/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69 --out scratch/stores --passes 2 --recheck"`
- `sbt check`: started 3 times. I stopped the first one myself (`kill` on my own sbt process) to make a final LiveAws change; it never reached markTestRun (the marker still read 20:31:27). Runs 1 and 2 below are on the final tree.
- Read-only helpers: `javap` on the cached auth-2.41.32.jar; python3 -I counting evidence-object runIds; `df`, `find`, `grep`, `sed`, `ls`, `pgrep`; aws-docs search and read.
- The guard blocked two Bash heredoc edits as false positives: a bare `env` token, and `e.key` matching the private-key file pattern. I made those edits with the Edit tool, as docs/agents.md directs, not through a script indirection.

TEST RESULTS (unit tests on local fakes; no AWS-profile tests):
- Storage tests: 60 (49 before, 11 new), 0 failed.
- `sbt check` run 1: exit 0; test blocks 166 + 15 + 94 + 60 + 43 + 70 = 448, 0 failed; markTestRun wrote `.claude/.last-test-run` at 2026-11-06 18:02:19; 259 s.
- `sbt check` run 2: exit 0; the same 448 tests, 0 failed; marker 2026-11-06 18:06:54; 259 s.
- After run 2, `find modules/storage -newer .claude/.last-test-run` (excluding target/) found no source newer than the marker. No sbt or jshell process is left running, and all background tasks finished.

VALIDATION RESULTS:
- **ValidateCli neptune** on run-1d6a86491812ff94887270c1ab885e69 with `--evidence-root scratch/stores/s3`, `-Xmx6g`: exit 0, 31 s. Printed lines, verbatim:
  ```
  count total 3635231
  evidence edges-inline 1590180
  evidence edges-object 574
  evidence work-ids-traced 2038078
  rule EVIDENCE_WORK_MISSING 0
  rule EVIDENCE_NOT_AUTHORED 0
  rule EVIDENCE_OBJECT_ORDER 0
  rule EVIDENCE_OBJECT_PROVENANCE 0
  violations 0
  OK
  ```
  - Per-family counts are unchanged: authored 535898, coauthored-with 1590754, work 113205, researcher 259057, and so on.
  - 1,590,180 inline plus 574 object edges equals all 1,590,754 COAUTHORED_WITH edges, so every edge was traced.
  - The AUTHORED set fits in 6g, so no streaming design is needed.
- **LoadDynamoCli dry run** (`--passes 2 --recheck`, `-Xmx6g`): exit 0, 39 s. Printed lines, verbatim:
  ```
  load run run-62359da6f98c0613ea9684d7db3dafc6 from source run run-1d6a86491812ff94887270c1ab885e69 snapshot 2026-10-03
  pass 1 ArtifactRegistry conflict=0 inserted=9 invalid=0 registry=RECORDED unchanged=0
  pass 1 EntityLinkCache conflict=0 inserted=287780 invalid=0 registry=RECORDED unchanged=0
  pass 1 ResearcherMetricCache conflict=0 inserted=588439 invalid=0 registry=RECORDED unchanged=0
  pass 2 ArtifactRegistry conflict=0 inserted=0 invalid=0 registry=ALREADY_RECORDED unchanged=9
  pass 2 EntityLinkCache conflict=0 inserted=0 invalid=0 registry=ALREADY_RECORDED unchanged=287780
  pass 2 ResearcherMetricCache conflict=0 inserted=0 invalid=0 registry=ALREADY_RECORDED unchanged=588439
  ```
- **Free disk:** 17 GiB before and after every run (before and after the ValidateCli run, the DynamoDB run, and both `sbt check` runs). At the start of the repair it was also 17 GiB (the work order said about 19).

ASSUMPTIONS:
- Evidence objects must carry the edge's runId and snapshot. The data agrees: all 574 objects in this run carry run-10ceed31b3f602bb998eae7c00929ae9 and 2026-10-03, the same as their edges.
- AWS returns the expired-token error under the codes ExpiredToken (S3) or ExpiredTokenException (JSON services), or with the message "security token included in the request is expired". The message match is a fallback.
- The M3 pre-load check needs objects uploaded with a full-object SHA-256 checksum. LoadS3Cli uses single PutObject calls with `checksumSHA256`, so this holds. A multipart upload would show "none" and be refused.

RISKS:
1. The cause of the expired token is not proven. The fix makes one refresh and retry per call, so a second expiry in a row still stops the run as FAILED, which is resumable. It does not widen the SDK's 15 s refresh margin (that is not exposed through ProfileCredentialsProvider).
2. The client-wiring test builds SDK clients with an empty in-memory profile file. I did not independently check that the SDK reads no other default file during the build.
3. The ValidateCli and DynamoDB runs used the build from before the last LiveAws change (refresh no longer closes the old delegate). That change does not touch the Neptune or DynamoDB fake code paths, and both `sbt check` runs are on the final tree.
4. The live paths are still unit-tested only (ruling 26). Bounded AWS integration tests remain an open gate item for cloud window 2.

MANUAL ACTION REQUIRED: none for this repair. If the human wants a longer credential refresh margin than the SDK's 15 s, that needs a decision. It would mean building ProcessCredentialsProvider directly with a larger `credentialRefreshThreshold`, which reruns `credential_process` more often.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review 2 against rulings 33 to 36; contract tests; the bounded live load test in cloud window 2.

Not checked: any live AWS behaviour, including the real error codes for an expired token and S3 HeadObject checksum responses. I did not run mutation checks to show each new test fails without its change; the F1, M3 and credentials tests call functions that did not exist before.
````
