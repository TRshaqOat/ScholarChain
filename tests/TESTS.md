# ScholarChain cross-module and adversarial tests (`tests/`)

Owner: test-engineer. Phase 11 work order 3 (the spec Phase 11 test work order) created this tree, work order 4 (the rerun after repair-01) extended it, and work order 5 (the rerun after repair-02) removed the D14 wrapper and added the verifier-v8 tests. It holds the cross-module, integration, end-to-end, and adversarial tests. Module-local tests stay in `modules/<module>/src/test/` and belong to the module owners.

Territory (Phase 11 ruling 11): test-engineer writes under `tests/**` only. Ruling 11 withdrew ruling 4's grant of `modules/*/src/test/scala/**/adversarial/`, and `.claude/hooks/guard-paths.sh` enforces this. Every adversarial test therefore lives here.

## Layout

The sbt project `tests` (in `build.sbt`) compiles four source directories into its Test configuration. `tests/fixtures/` is its resource directory.

| Directory | Package | Contents |
|---|---|---|
| `tests/adversarial/scholarchain/tests/support/` | `scholarchain.tests.support` | `StoreWorld`: a synthetic Phase 7 store-file world with injected text, an HTTP client, a capture log, and recorded model clients. `RawHttp`: raw loopback HTTP. `ScriptedGraph`: a graph port that blocks or throws. `GraphSpy`: records the graph-port calls the runtime makes and replays them through the live `NeptuneGraphReader` over a capturing client. `SparkWorld`: the one shared local SparkSession. |
| `tests/adversarial/scholarchain/tests/adversarial/` | `scholarchain.tests.adversarial` | the adversarial suites |
| `tests/contracts/scholarchain/tests/contracts/` | `scholarchain.tests.contracts` | configuration contract; the Spark and entity-resolution MatchPath contract |
| `tests/integration/scholarchain/tests/integration/` | `scholarchain.tests.integration` | known-graph reads on the graph store; Spark known-graph analytics |
| `tests/end-to-end/scholarchain/tests/endtoend/` | `scholarchain.tests.endtoend` | one bounded end-to-end fixture over the services |
| `tests/fixtures/` | resources | `logback-test.xml`: library logging at WARN in the tests JVM. Logback reaches this classpath through services' Runtime scope. Without the file, logback logs Spark at DEBUG, which is about 120 MB per run. No project code logs through SLF4J. |

The project sees the modules' compiled main code (`dependsOn(... % Test)`), not their test fixtures. Since repair-01 Part A it also has Spark SQL and the Hadoop client on its Test classpath, and it forks one JVM with the Spark JVM options and `-Xmx2g`. That JVM runs suites one at a time, with the repository root as working directory.

## How to run

Every sbt run sets `SPARK_LOCAL_IP=127.0.0.1`, with heap at most 6g.

```text
SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g tests/test                       # all 151 tests (about 50 s)
SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g "tests/testOnly scholarchain.tests.adversarial.*"
SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g "tests/testOnly *SparkKnownGraphSpec"
SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx6g check                            # runs these with every module
```

As of work order 5: 151 tests, of which 141 pass and 10 are pending (the table below).

No test calls AWS, Bedrock, or any network service. AWS SDK clients are JDK dynamic proxies of the client interfaces. Services, the fetch-counting server, and the Spark driver bind `127.0.0.1` only, with ephemeral ports. Every file goes to a JVM temporary directory that the test deletes, and Spark checkpoints go to one that is removed at JVM exit. No test is tagged `RequiresAws`.

## Conventions (Phase 11 ruling 5)

- **Determinism.**
  - Concurrency is ordered with latches (`CountDownLatch`), so a held call stays held until the test releases it.
  - Clocks are injected: `TickClock`, a frozen rate-limiter clock, a settable queue clock, and a settable DynamoDB `now`.
  - Socket, client, and latch timeouts (5 to 30 s) are hang guards only. A healthy service answers in milliseconds, and the timeout fires only when the behaviour is broken.
  - A latch that the code under test may count down after the response is awaited, never assumed (work order 4, finding 4).
  - Spark fixtures use fixed graphs or `java.util.Random` with a fixed seed.
- **One real-clock condition.** `WorkerRedeliveryAdversarialSpec`, "the composed query-api (Apps.queryApi) applies the rate limit to async requests too", uses the production limiter. `Apps.queryApi` injects no clock, so the test assumes three loopback requests take less than the limiter's 60 s window. Each request is already bounded by the 30 s client hang guard. The frozen-clock test next to it covers the limiter logic itself.
- **Spark.**
  - `SparkWorld.spark` is a lazily created `local[2]` session with UI off, 2 shuffle partitions, and the driver on 127.0.0.1. It is never stopped, because stopping it breaks later suites.
  - Fixtures stay tiny: at most a few dozen nodes, which fits the shared `-Xmx2g`.
  - Expected values come from hand-worked cases or from independent in-test references: union-find, min-degree peeling, synchronous label propagation, and per-node bridge sums.
- **Hadoop.** Record readers run on local `file:` paths with a `Configuration`, `FileSplit`, and `TaskAttemptContextImpl`. Splits come from `DblpInputFormat.getSplits` itself. There is no mini-cluster.
- **Synthetic data only.** Injection strings carry the marker `SYNTHETIC-INJECTION`, and secrets carry `SYNTHETICSECRET` or `SYNTHETIC-SECRET`. Researchers are labelled with classic computing names. No real personal data is used.
- **Own logs.** Each services test passes its own `CaptureLog`, so it never reads or writes the services module's shared fixture log.
- **Defect-exposing tests** (rulings 3 and 14) are wrapped in ScalaTest `pendingUntilFixed`.
  - Each has a `// PENDING Dn (owner ...)` line.
  - Without the wrapper the test fails. The evidence is in `scratch/phase-11-logs/wo3/pending-evidence.log` (D6 to D9), `scratch/phase-11-logs/rerun/pending-evidence.log` (D14 to D17), and `scratch/phase-11-logs/rerun-2/pending-evidence.log` (D18 to D20).
  - Once the owner fixes the defect, the wrapper makes the test fail ("did not throw an exception"). The next test-engineer rerun then removes the wrapper, and the test must pass (ruling 14). Owners never edit `tests/**` (ruling 11).

## Suites by category

| Spec category or ruling 1 target | Suite (tests) |
|---|---|
| Malformed and truncated DBLP XML | `DblpXmlAdversarialSpec` (8, parser), `DblpRecordReaderAdversarialSpec` (4, Hadoop record reader: splits at every size, truncation, gzip, a record inside an XML comment) |
| Unicode normalization and confusable characters | `IdentityAdversarialSpec` (Unicode tests) |
| Duplicate and conflicting DOI records | `IdentityAdversarialSpec` (DOI tests) |
| Common author names and reordered author lists; common-name resolution | `IdentityAdversarialSpec`, `RagModelOutputAdversarialSpec` (AMBIGUOUS and UNKNOWN abstentions) |
| Very large blocking groups; large-team pair expansion | `BlockingTeamAdversarialSpec` (6) |
| Coauthor edges with unresolved or conflicting researcher endpoints | `CoauthorGraphEndpointAdversarialSpec` (5, Spark job 2) |
| Empty and disconnected graphs | `GraphReadKnownGraphSpec` (graph store), `SparkKnownGraphSpec` (Spark: no node, isolated nodes only, a window with no edges) |
| Distinct-degree, component, shortest-path, bridge, and k-core known graphs; communities | `GraphReadKnownGraphSpec` (5: shortest paths, degree, tampered counts), `SparkKnownGraphSpec` (7: both component algorithms, k-core, label propagation, bridge measures, and the window summary's storage identities) |
| Match-path copy in Spark (Phase 06 m5) | `MatchPathContractSpec` (2) |
| Missing topic, institution, abstract, or profile metadata | `RagModelOutputAdversarialSpec` (missing-metadata tests) |
| Duplicate vector keys and stale model versions | `StoreFailureAdversarialSpec` |
| Prompt injection in title, abstract, and full-text chunks; injection through every text field | `RagModelOutputAdversarialSpec` (20; including injected filter terms replayed through the live openCypher templates), `ApiBodyAdversarialSpec`, `LoggingRedactionAdversarialSpec`, `BoundedFixtureEndToEndSpec` |
| LLM malformed JSON and fabricated identifiers; LLM budget exhaustion | `RagModelOutputAdversarialSpec` (including a recorded synthesis whose claims, summary, and uncertainty lines use the verifier-v8 wordings, and D20) |
| Unsupported answer claims | `UnsupportedClaimAdversarialSpec` (14: verifier-v7 and verifier-v8 breadth and controls, the corpus template wordings through the services composition, D18 and D19) |
| Neptune timeout | `StoreFailureAdversarialSpec`, `GraphBoundsTimeoutAdversarialSpec` |
| DynamoDB conditional-write conflict; idempotency races | `StoreFailureAdversarialSpec` (the proxy evaluates the request's own ConditionExpression), `IdempotencyRaceAdversarialSpec` (6) |
| S3 Vectors throttling or transient failure | `StoreFailureAdversarialSpec`, `RagModelOutputAdversarialSpec` (D4 and its repair-01 detail) |
| Replayed workflow and idempotency; job redelivery; queue flooding | `IdempotencyRaceAdversarialSpec`, `WorkerRedeliveryAdversarialSpec` (7) |
| API authentication bypass, authorization, malformed bodies, timeouts; unbounded graph requests | `ApiAuthAdversarialSpec` (5), `ApiBodyAdversarialSpec` (11), `GraphBoundsTimeoutAdversarialSpec` (5) |
| Logging redaction | `LoggingRedactionAdversarialSpec` (3) |
| Configuration omissions | `ConfigOmissionContractSpec` (11) |
| Window-summary object tampering | `WindowSummaryTamperAdversarialSpec` (5) |
| End-to-end bounded fixture | `BoundedFixtureEndToEndSpec` (1) |

## Pending defects (pendingUntilFixed)

| ID | Owner | Test (file:line of the PENDING marker) | Failing input | Production location |
|---|---|---|---|---|
| D6 | hadoop-engineer | `DblpXmlAdversarialSpec.scala:169` | `<author></author>`, `<author>   </author>`, `<author><i></i></author>`, `<author/>`: accepted with `rawName ""`; JOBS.md lists "an author without a name" as a schema-validation reject | `modules/hadoop/.../DblpParser.scala:150`. Residual by ruling 15. |
| D7 | entity-resolution-engineer | `IdentityAdversarialSpec.scala:100` | "Wei Mül" + U+00AD, U+200B, U+200C, U+200D, U+2060, U+FEFF, U+200E, or U+202E + "ler": family key "ler" instead of "muller" | `modules/entity-resolution/.../Normalize.scala:87-88`. Residual by ruling 15. |
| D8 | domain-engineer | `IdentityAdversarialSpec.scala:269` | `doi: 10.1145/abc.123`, `https://doi.org/ 10.1145/abc.123`, `doi:https://doi.org/...`, `https://doi.org/doi:...`: normalizeDoi is not idempotent | `modules/core/.../StableIds.scala:59`. Residual by ruling 19: an idempotent normalizeDoi would change 36 stored ORCID workDoi values in 26 researcher-profile records of Hadoop run run-035e75abfa6798c938cbd534273dbd8d; it loses recall only. |
| D9 | domain-engineer | `IdentityAdversarialSpec.scala:287` | `https://doi.org/10.1002/(SICI)1097-4571(199806)49:8%3C693::AID-ASI4%3E3.0.CO;2-O` versus the plain DOI with `<` and `>`: different keys | `modules/core/.../StableIds.scala:59`. Residual by ruling 15. |
| D15 | service-engineer | `ApiBodyAdversarialSpec.scala:111` | POST /v1/query whose question bytes hold invalid UTF-8: 0xC3 then `?`, 0xFF 0xFE, the overlong 0xC0 0xAF, or the encoded surrogate 0xED 0xA0 0x80. Each is answered 200 instead of 400 MALFORMED_JSON (RFC 8259 section 8.1; openapi.yaml MALFORMED_JSON). | `modules/services/.../Http.scala:66` (`ujson.read(bytes)` decodes leniently). Found in work order 4. Minor residual by ruling 21 (Phase 12+ backlog). |
| D16 | service-engineer | `ApiBodyAdversarialSpec.scala:142` | `{"question": "Have Ada Lovelace and Grace Hopper coauthored a paper \ud800?"}`, a JSON-escaped lone high surrogate: answered 200, not a typed 400. The lone low surrogates `\udfff` and `\udc00\ud800` are already refused. | `modules/services/.../Models.scala:455-457`, which accepts what `modules/rag/.../runtime/Planner.scala:20-26` `Question.normalize` accepts. Found in work order 4. Minor residual by ruling 21 (Phase 12+ backlog). |
| D17 | hadoop-engineer | `DblpRecordReaderAdversarialSpec.scala:175` | A DBLP file with `<!-- withdrawn: <article key="journals/x/Fake1">...</article> -->` and `<!-- <article key="journals/x/Open"> ... -->` before three real records. The reader returns Fake1, Real1, and Open, and Fake1 is accepted as work `dblp:journals/x/Fake1`. Real2 and Real3 are swallowed into the unclosed Open record. | `modules/hadoop/.../DblpInputFormat.scala:85-104` (`findStart` takes any `<name` as a record start, inside comments too). Found in work order 4. Minor residual by ruling 21 (Phase 12+ backlog). The human's read-only measurement found 0 XML comments in the committed sample and in every full and sample DBLP file under the snapshot, so no existing output is affected. |
| D18 | rag-engineer | `UnsupportedClaimAdversarialSpec.scala:657` | METRIC_VALUE claims citing ada's metric with `publicationCount` 10: "has 10 more publications than her coauthor", "has 10 fewer publications than her coauthor", "the records dispute that Ada Lovelace has 10 publications", "it is unlikely that ...", "it is doubtful that ...". All five are kept VERIFIED, though each is false or unsupported for 10. | `modules/rag/.../runtime/Verifier.scala:193-196` (`After` has no bare "more" or "fewer" after the number) and `:211-214` (`Negation` has "deny" but not "dispute", and no doubt words). Found in work order 5; proposed major (the D14 class). None of these wordings is in repair-02's knowingly-uncovered list. |
| D19 | rag-engineer | `UnsupportedClaimAdversarialSpec.scala:682` | The same claim with "more or less", "give or take", "in the region of", "on the order of", "practically", "virtually", "an estimated", "possibly", or "perhaps" directly before 10, and "it is unclear whether Ada Lovelace has 10 publications". All ten are kept VERIFIED, though each states 10 inexactly (verifier-v7 already removes "about 10" and "roughly 10"). | `modules/rag/.../runtime/Verifier.scala:186-192` (`Before`). Found in work order 5; proposed minor. None of these wordings is in repair-02's knowingly-uncovered list. |
| D20 | rag-engineer | `RagModelOutputAdversarialSpec.scala:488` | A recorded synthesis keeps the claim "Ada Lovelace and Grace Hopper have 2 joint publications in all-years." and drafts the uncertainty lines "... have barely 2 joint publications ...", "... have more than 2 joint publications ...", "... lack 2 joint publications ...". The answer's identity note says "among 2 researchers with this display name", so 2 is a free number of uncertainty lines, and all three lines are shown in the answer. The same kind of line with 10, which is not a note number, is left out. | `modules/rag/.../runtime/Verifier.scala:638-640` (`hedged` is skipped for any number equal to a fixed-note number, `freeNumbers(n)`), with `:1089-1103` (`noteNumbers` and the uncertainty-line check). RUNTIME.md section 12 documents that note numbers are not checked; a claim value that coincides with one is not separated from it. Found in work order 5; proposed minor. |

### Wrapper removed in work order 5 (rulings 14 and 20)

Repair-02 Part A (rag-engineer, verifier-v8) reports D14 fixed. The test passes without its wrapper.

| ID | Repair part | Test |
|---|---|---|
| D14 | Repair-02 Part A (rag-engineer, verifier-v8) | `UnsupportedClaimAdversarialSpec`: "a comparison or negation outside the verifier-v7 word lists (short of 10, lacks 10) is not verified" |

### Wrappers removed in work order 4 (ruling 14)

Repair-01 reports each of these fixed. Each test now passes without its wrapper.

| ID | Repair part | Test |
|---|---|---|
| D1, D2, D3 | Part B (rag-engineer, verifier-v7) | `UnsupportedClaimAdversarialSpec`: quantity words; comparisons; negations |
| D4 | Part B | `RagModelOutputAdversarialSpec`: "a throttled vector query (once, or every time) does not fail an answer the graph supports" |
| D5 | Part C (service-engineer) | `ApiBodyAdversarialSpec`: "a declared body that never arrives does not hold the exchange open (refused, 401, or unread)" |
| D10 | Part E (storage-engineer) | `WindowSummaryTamperAdversarialSpec`: "counts no coauthorship graph can have are refused ..." |
| D11, D12 (two tests), D13 | Part D (domain-engineer) | `ConfigOmissionContractSpec` |

## Human rulings 19 to 23 (after repair-01 and work order 4)

- **Ruling 19.** D8 stays a residual risk with D6, D7, and D9. Its wrapper stays.
- **Ruling 20.** D14 is confirmed major (rag-engineer). Repair-02 fixed it (verifier-v8), and work order 5 removed its wrapper; the test passes. Code review 2 covers repair-01, work order 4, repair-02, and work order 5.
- **Ruling 21.** D15 and D16 (service-engineer) and D17 (hadoop-engineer) are minor residual risks for the Phase 12+ backlog. Their wrappers stay. The human's D17 measurement found no XML comment in any DBLP input, so D17 affects no existing output.
- **Ruling 22.** The guard blocks of repair-01 and work order 4, and Part D's `du` over `scratch/`, are accepted deviations. No `du` or `find` runs over `scratch/` without pruning `scratch/stores-live/` and `scratch/er/adjudication-live/`.
- **Ruling 23.** The justified wrappers at code review 2 are D6, D7, D8, D9, D15, D16, and D17. D18, D19, and D20 are new in work order 5, and code review 2 confirms or changes their severity and owner.

## Not covered here, and why

- **Live AWS** (ruling 2). There are no `RequiresAws` tests. The live adapters run against client proxies only. The DynamoDB proxy's semantics, a condition-expression subset with TTL by `:now`, are this suite's model of DynamoDB.
- **Hadoop mini-cluster jobs and counters.** The record reader and input format are covered on local files. The J01 to J10 MapReduce drivers on a mini-cluster are not: they would need the Hadoop minicluster test artifacts, which are not on the classpath (build-engineer).
- **The 30 s request read timeout itself.** The module's `RequestReadSpec` waits it out. Here, only "the timeout is set for every service" and the 1 s unread-body bound are tested, to keep check fast.
- **Unreachable Spark inputs.** A mention map that sends one mention to two researchers cannot come from `Canonical.mentionMap`, which has one row per mention. `CoauthorGraph.build` has no check of its own for it.
- **Verifier wordings repair-02 left uncovered on purpose.** RUNTIME.md section 21 lists them. Work order 5 measured them with a temporary probe, which was deleted afterwards (`scratch/phase-11-logs/rerun-2/probe-results.log`). The tests here do not assert them, so a later fix will not break a test. Against ada's stored 10, these are kept VERIFIED:
  - "past 10", "is missing 10", "a minimum of 10", "a maximum of 10", "within 10", "toward 10", "pushing 10", "the better part of 10";
  - "besides", "apart from", "other than", "nobody", "nothing";
  - after-measure words ("10 publications and counting", "at minimum", "at most", "or more", ", roughly"), and comparison words separated from the number ("less than or equal to 10", "at the very least 10");
  - "a mere 10".
  Two of them fail through another rule: "none of" (0 is not a fact) and "exceeds the stored total of 10" (no measure name next to 10). Rules that RUNTIME.md section 12 documents are not asserted either: a negation after the number ("10 publications are absent from ..."), the ratio exception ("has publications above 10"), and a negation inside a measure-naming aside.
