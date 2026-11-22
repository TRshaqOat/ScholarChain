# ScholarChain demonstration script

Owner: documentation-engineer. Provenance: the script was drafted by Claude in the web chat, as the Claude Doc "ScholarChain Demo Recording Script", and reviewed by the human (Phase 17 ruling 5; [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md) "External assistance"). This file adapts the exported text, [`docs/agent-runs/phase-17/inputs/demo-script-source.md`](agent-runs/phase-17/inputs/demo-script-source.md), and applies the human's recording notes, [`docs/agent-runs/phase-17/inputs/demo-recording-notes.md`](agent-runs/phase-17/inputs/demo-recording-notes.md); where the two differ, the notes win (Phase 17 ruling 13). Commands keep their exact text. The video link is added to the README at submission.

## 1. What was recorded

| Part | Scenes | Recorded | Notes |
|---|---|---|---|
| A (live runtime, cloud window 3) | A0 to A11 | Nov 19, 2026, during window 3 | The microphone was off from A5 on; voiceovers for A5 to A11 were recorded in iMovie on Nov 20. Window 3 record: Phase 15 ruling 10(f) ([`docs/agent-runs/phase-15/prompt.md`](agent-runs/phase-15/prompt.md)). |
| B (offline, no cloud) | B1 to B10, B12 | Nov 21, 2026 | B6, B9 and B12 differ from the source (section 4). |
| B | B11 | during the Phase 17 fresh-clone verification (Step F) | Needs the Phase 17 README. |

Assembly: the final order is the table in section 3. Export at 1080p, quality Medium (compress "Better Quality"); upload with a restricted link.

## 2. Demonstration requirements mapped to clips

Each item of the specification's "Demonstration requirements" is covered by one scene. "Clip" is the position in the final order (section 3).

| # | Requirement | Scene | Clip | Evidence shown or behind it |
|---|---|---|---|---|
| 1 | Repository structure | B1 | 2 | the repository tree |
| 2 | Agent ownership map | B2 | 3 | [`docs/agents.md`](agents.md) ownership table |
| 3 | One agent role file | B3 | 3 | [`.claude/agents/code-reviewer.md`](../.claude/agents/code-reviewer.md) |
| 4 | Dangerous-command hook blocking a probe (three probes) | A9 | 4 | three blocks by `guard-dangerous.sh` (Phase 15 ruling 10(f)) |
| 5 | Test-stop hook or deterministic gate | B4 | 4 | [`.claude/settings.json`](../.claude/settings.json), [`.claude/hooks/verify-tests-ran.sh`](../.claude/hooks/verify-tests-ran.sh) |
| 6 | A phase report and repair record | B5 | 5 | [`docs/agent-runs/phase-15/`](agent-runs/phase-15/) |
| 7 | A clean `sbt check` result | B6 | 6 | `scripts/check.sh` output |
| 8 | Dataset manifest and real source samples | B7 | 7 | [`datasets/dataset-manifest.yaml`](../datasets/dataset-manifest.yaml), [`datasets/samples/`](../datasets/samples/) |
| 9 | Hadoop execution evidence and counters | B8 | 8 | EMR console (cluster `j-03327281MRPKUSN1LE0M`), [`operations/pipeline-runs/20261117T045335Z-fraction25/`](../operations/pipeline-runs/20261117T045335Z-fraction25/run.json) |
| 10 | Spark execution evidence and graph outputs | B9 | 9 | the Spark step and the run IDs in the same pipeline record |
| 11 | Neptune graph query | A4 | 12 | live query in window 3 |
| 12 | DynamoDB run or query record | A6 | 13 | QuerySession, EvidenceBundle, ResearcherMetricCache items |
| 13 | S3 Vectors query returning source pointers | A5 | 12 | the explain record and one semantic evidence item |
| 14 | A complete user query through the API | A2 | 11 | live query in window 3; same question as [`operations/releases/smoke-20261119T235713Z/`](../operations/releases/smoke-20261119T235713Z/) |
| 15 | Evidence and explain endpoints | A3 | 11 | the evidence and explain responses for the A2 query |
| 16 | Claim verification with an unsupported-claim test | B12 | 14 | `UnsupportedClaimAdversarialSpec`, `VerifierSpec` |
| 17 | Reproduction scripts, agent replay, clean-checkout bounded run | B11 | 17 | README "Reproducing the agentic workflow"; `docs/agent-runs/phase-17/evidence/fresh-clone/` |
| 18 | Terraform plan or deployed resources | A1 and B10 | 10 | [`operations/plans/20261119T173346Z-c5428063732a.md`](../operations/plans/20261119T173346Z-c5428063732a.md) and its approval record; the deployed resources in the console |
| 19 | CloudWatch elasticity dashboard | A7 | 15 | `scholarchain-course-runtime` dashboard and alarms |
| 20 | Scale-out and scale-in evidence | A10 | 15 | [`experiments/results/exp-d-scale-v1/20261120T001853Z/`](../experiments/results/exp-d-scale-v1/20261120T001853Z/), [`experiments/derived/exp-d-scale/README.md`](../experiments/derived/exp-d-scale/README.md) |
| 21 | One failure and recovery example | A8 | 16 | [`experiments/results/exp-f-failure-recovery-v1/20261118T062000Z/`](../experiments/results/exp-f-failure-recovery-v1/20261118T062000Z/) |
| 22 | Cleanup or resource inventory | A11 | 18 | [`operations/cleanup/cleanup-20261120T041658Z-runtime/`](../operations/cleanup/cleanup-20261120T041658Z-runtime/record.json) |

Items 11 to 15 and 19 to 21 needed the live runtime and were recorded in window 3. A10 shows the scale-out and scale-in from the recorded history (experiment output, scaling activities, ECS events, dashboard), not a live clip (Phase 15 ruling 9).

## 3. Final order

| # | Clip | Scenes | About (min) |
| --- | --- | --- | --- |
| 1 | Opening | A0 | 0.5 |
| 2 | Repository structure | B1 | 1 |
| 3 | Agents and ownership | B2, B3 | 2 |
| 4 | Hooks and gates | A9, B4 | 3 |
| 5 | Phase records and repairs | B5 | 1.5 |
| 6 | Clean check | B6 (waiting trimmed) | 1.5 |
| 7 | Datasets | B7 | 1 |
| 8 | Hadoop on EMR | B8 | 1.5 |
| 9 | Spark on EMR | B9 | 1.5 |
| 10 | Infrastructure: Terraform and the deployed resources | B10, A1 | 4 |
| 11 | A live question, its evidence and explain | A2, A3 | 3 |
| 12 | Neptune and S3 Vectors | A4, A5 | 2.5 |
| 13 | DynamoDB records | A6 | 1.5 |
| 14 | Claim verification | B12 | 1 |
| 15 | Dashboard and elasticity | A7, A10 | 4 |
| 16 | Failure and recovery | A8 | 1.5 |
| 17 | Reproduction from a clean checkout | B11 | 1.5 |
| 18 | Cleanup | A11 | 2 |
| 19 | Closing | Part B closing line | 0.5 |

## 4. Part A: live runtime (window 3)

A0 to A4 have their original audio; the source holds no voiceover text for them. A0 is the opening, A1 the deployed resources, A2 a complete query through the API, A3 its evidence and explain responses, A4 a Neptune graph query. A2 to A5 were recorded in one Terminal session and share the query ID and evidence ID of A2.

### A5. S3 Vectors source pointers (item 13)

**On screen:** the `jq` output of the explain record (`queryId`, `embeddingModelVersion`, `embeddingCalls`, `vectorIndexes`, `semanticEvidenceIds`), then one semantic evidence item resolved through the evidence endpoint.

**Voiceover:**

[As the first output appears] "Now the semantic side. Each answer's explain record lists how semantic context was used. For this question, the runtime embedded the text once with the Titan text embedding model, and searched two S3 Vectors indexes: researcher profiles and paper abstracts. These are the IDs of the semantic evidence it kept."

[As the evidence item appears] "Resolving one of those IDs shows a profile item. It carries the vector key it was found under, and a pointer to its source text in S3, with a hash, so text that has been changed since it was embedded is dropped. Semantic items are kept apart from graph facts: they can add description to an answer, but they can never verify a number, an edge or a path."

### A6. DynamoDB records (item 12)

**On screen:** the DynamoDB console: `scholarchain-course-QuerySession` items, then `scholarchain-course-EvidenceBundle`, then `scholarchain-course-ResearcherMetricCache`.

**Voiceover:**

[QuerySession table and an item] "DynamoDB holds the operational state. Every query writes a session record: the query ID, the runtime run, the model and prompt versions, the timings, and references to the answer's evidence. It deliberately stores no question text."

[EvidenceBundle] "The evidence bundle of each answer is stored item by item, so any evidence ID in an answer can be looked up later through the evidence endpoint. These items expire after a set time."

[ResearcherMetricCache] "And this cache holds the Spark-computed metrics of each researcher per time window, such as distinct coauthors, weighted degree, community and k-core, each with provenance back to the Spark run that produced it. The runtime reads these instead of recomputing them."

### A7. CloudWatch dashboard and alarms (item 19)

**On screen:** the `scholarchain-course-runtime` dashboard over 6 hours, then the Alarms list.

**Voiceover:**

[Dashboard] "This is the runtime dashboard. It shows CPU for each of the three services, load balancer requests, latency percentiles and errors, healthy targets, the dead-letter queue depth, and Neptune's CPU. The large Neptune spike earlier in the day is the bulk load of about 34 million graph records."

[Alarms] "Alarms cover load balancer errors, unhealthy targets, high CPU on each service and on Neptune, and any message in the dead-letter queue. The two TargetTracking alarms were created by the autoscaling policy itself; they are what drives scale-out and scale-in, which I'll show shortly."

### A8. Failure and recovery (item 21)

**On screen:** the listing of `experiments/results/exp-f-failure-recovery-v1/20261118T062000Z`, its `run-record.json`, then the end of `stdout.txt`.

**Voiceover:**

[File listing] "This is experiment F, failure and recovery, run in the previous cloud window. Its raw results are committed and read-only."

[run-record.json] "The run record ties the result to the exact commit, the manifest and its checksum, the time limits and the exit code."

[stdout] "I injected a job that always fails. The worker retried it three times, then SQS moved the message to the dead-letter queue and the alarm fired. After I redrove the message, the job completed exactly once, and replaying the same request with the same idempotency key returned the same answer instead of running it again."

### A9. Guard hooks blocking dangerous commands (item 4)

**On screen:** the Claude Code sidebar, the probe prompt, and the three blocked commands with the hook's messages.

**Voiceover:**

[Prompt pasted] "The agents work under executable policy, not just instructions. Here I ask Claude Code to run three dangerous commands, each one safe by construction: a dry-run recursive delete, and two commands that name resources that don't exist."

[First block] "A recursive S3 delete is blocked before it runs, by the guard-dangerous hook."

[Second block] "Terminating an EMR cluster is blocked too."

[Third block] "And setting a service to a hundred tasks is refused as a scale change beyond the approved bounds, which needs human approval. All three were stopped by the hook before anything reached AWS. The settings file also has deny rules for destructive commands, and the gate script self-tests these hooks with 304 cases."

### A10. Scale-out and scale-in (item 20)

**On screen:** the experiment's output lines, the scaling-activities table, the current task count, the ECS service Events tab, then the dashboard from 6:10 to 7:00 PM. The live-clip version of A10 was not used.

**Voiceover:**

[Experiment output] "This is the elasticity experiment, run earlier that evening. graph-evidence-api has a target-tracking policy on average CPU, with one to two tasks. For ten minutes, eight parallel clients sent graph and evidence requests: almost 22,000 requests, about 37 per second, with zero errors and a 95th-percentile latency of 683 milliseconds. One task was running before the load, two at the end of it, and one again after the observation period."

[Scaling activities] "Application Auto Scaling recorded both decisions: at 6:24 PM, five minutes into the load, it raised the desired count to two, and at 6:46 PM, about seventeen minutes after the load stopped, it set it back to one. Nobody changed the task count by hand."

[Task count] "The service is back at its normal size: one task desired, one running."

[ECS Events] "ECS's own event log shows the full cycle. The high-CPU alarm went into alarm, the desired count rose to two, and ECS started a second task and registered it with the load balancer. Later the low-CPU alarm fired, and ECS deregistered that same task, drained its connections and stopped it." [If the morning's failed deployment row is visible:] "The failed deployment that morning is from before the API key was set; the resumed deployment completed."

[Dashboard] "Here is the service's CPU during the experiment. For this run the target was 15 percent, a declared experiment setting: in the previous window the service never got near the normal 60 percent, because Neptune was the bottleneck. Neptune was at about 97 percent CPU throughout, so the scaling shows the mechanism working, not extra capacity; that's discussed in the evaluation."

Sources for the narrated figures: Phase 15 ruling 10(e) (21,962 requests in 600 s, 0 errors, p95 683 ms, desired count 1 to 2 at 18:24:01 CST and 2 to 1 at 18:46:39 CST) Phase 15 rulings 12(e) and 13(c) (Neptune CPU about 97 percent in both phases; the throughput difference is not attributed to the second task); [`docs/evaluation.md`](evaluation.md) section 5.

### A11. Cleanup evidence (item 22)

**On screen:** the end of the cleanup apply log, the count of managed resources kept, the empty post-cleanup checks, then the empty ECS, Neptune, DynamoDB and Load Balancer console pages.

**Voiceover:**

[Apply log] "The runtime is removed through the same reviewed path that created it. The cleanup script wrote a plan of 89 resources to destroy, I approved it by its checksum, and only then did it destroy them: 89 destroyed, nothing else changed."

[Kept count and checks] "43 managed resources remain, each kept on purpose, and the checks find no Neptune cluster, no ECS cluster, no DynamoDB tables, no load balancer and no running EMR cluster."

[Console pages] "The consoles agree: nothing billable from the runtime is left running. What remains is the network, the encrypted data bucket with the pipeline outputs and evidence, the container registry, the image build project, the EMR roles, and log groups that expire on their own. They cost almost nothing at rest and are needed to reproduce the pipeline. The final cleanup, including the data bucket, happens after grading."

## 5. Part B: offline scenes

None of these needs the cloud runtime. VS Code shows files (`code /Users/achyutpatel/Developer/Cloud/ScholarChain`); Terminal runs commands. The file inside `labels/` is not opened on camera.

### B1. Repository structure (item 1)

**Do:** in the VS Code Explorer, collapse everything, then open the top-level folders one by one: `.claude/`, `modules/`, `tests/`, `prompts/`, `scripts/`, `infra/terraform/`, `datasets/`, `experiments/`, `load-tests/`, `docs/agent-runs/`, `operations/`, `spec/`, plus `CLAUDE.md`, `.mcp.json` and `build.sbt`.

**Say:** "modules holds the Scala code, one sbt project per part: core contracts, Hadoop jobs, entity resolution, Spark analytics, storage, the RAG runtime and the services. tests holds the cross-module contract, adversarial and end-to-end tests, and prompts the versioned planner and synthesis prompts. scripts and infra hold the reproducible automation and Terraform. docs/agent-runs holds every phase's prompts, agent reports, reviews, repairs and my human gate decisions. operations holds the cloud records: plans, releases, pipeline runs and cleanup."

### B2. Agent ownership map (item 2)

**Do:** open `docs/agents.md` and scroll to the ownership table.

**Say:** "The software was built by a Claude Code orchestrator and 18 specialized subagents. Every writable path has exactly one owning agent, so every file has one writer, and the reviewers write nothing. I acted as architect and reviewer: I wrote the work orders and rulings, and approved every phase."

### B3. One agent role file (item 3)

**Do:** open `.claude/agents/code-reviewer.md`. Point at the tools line (Read, Grep, Glob only), the refusal boundary, and the report format.

**Say:** "This is the code reviewer. Its tools are read-only, so it can never change the code it reviews. Each agent file states what it owns, what it must refuse, and the structured report it returns."

### B4. Test-stop hook and deterministic gate (item 5)

**Do:** open `.claude/settings.json` and point at the hooks section. Then open `.claude/hooks/verify-tests-ran.sh` and scroll to where it compares the test marker with the source changes.

**Say:** "verify-tests-ran.sh stops an agent from finishing after it changed source code unless sbt check has passed since: sbt writes a marker file when all tests pass, and the hook refuses a stale marker. Other hooks format Scala after edits and fence each agent to its own paths."

### B5. A phase report and a repair record (item 6)

**Do:** open `docs/agent-runs/phase-15/`. Show, in order: `prompt.md` (work orders and rulings), `agent-report.md`, `review-report.md` (code review 1), `repair-01.md`, then a later code review section of `review-report.md`. The clip may close with the code review 5, repair-03, code review 6 loop.

**Say:** "Every phase follows the same loop. I write work orders, the agents report exactly what they changed and ran, an independent reviewer finds problems, one repair round fixes them, the reviewer checks again, and I record the decision as a numbered ruling with the commit hash."

### B6. A clean check (item 7; waiting trimmed)

**Do:**

```bash
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
./scripts/check.sh 2>&1 | grep -E '==> (OK|FAIL)|Tests: succeeded'
```

Each gate shows as it passes. The gate run on the accepted Phase 15 tree ended with `==> OK check: every gate passed` and 845 passed, 0 failed, 10 pending ([`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md)).

**Say:** "check.sh is the gate: it validates the agent files and hooks, checks every script's syntax, validates the datasets, formats and validates Terraform, and runs sbt check, about 845 tests. Ten adversarial tests are marked pending: known limits that are documented, not hidden."

### B7. Dataset manifest and real samples (item 8)

**Do:** open the `datasets/` folder. Show `datasets/dataset-manifest.yaml` (dblp, openalex, crossref and orcid with license, size, checksum and snapshot fields), one schema note in `datasets/schemas/`, and one sample file per source in `datasets/samples/` (DBLP XML, OpenAlex JSON lines, Crossref JSON lines; the ORCID samples are ORCID's published fictional test record and clearly marked synthetic records, not real people's data).

**Say:** "DBLP is the seed. OpenAlex and Crossref are linked by DOI and ORCID by researcher iD. The manifest records each source's license, snapshot date, checksums, the bounded subset I selected, and known staleness."

### B8. Hadoop execution and counters (item 9)

**Do:**

1. In the AWS console (Ohio), open EMR, Clusters, filter "All", and open `j-03327281MRPKUSN1LE0M` (the complete 25% run, Nov 17). Show the Steps tab: the classpath probe, the Hadoop pipeline step, and the Spark step, all completed.
2. In VS Code, open `operations/pipeline-runs/` and the record for `20261117T045335Z-fraction25`. Show the counts it recorded (for example J01: 27,993 works accepted).
3. Optionally open `docs/agent-runs/phase-14/evidence/window-1/p14-window1-results.tsv` for the timing table.

**Say:** "Hadoop MapReduce does the first heavy pass on EMR: parsing DBLP XML without splitting records, normalizing, joining the enrichment sources by DOI and ORCID, blocking for entity resolution, and expanding each paper into coauthor pairs. Every job keeps counters and writes rejected records to a reject output."

### B9. Spark execution and graph outputs (item 10)

**Do:** in the same EMR cluster, show the Spark step. Then show the run IDs in the pipeline record (`operations/pipeline-runs/20261117T045335Z-fraction25/`). The S3 console was not shown, because the bucket name contains the account ID.

**Say:** "Spark builds the temporal weighted coauthorship graph from the Hadoop outputs, then computes distinct and weighted degree, connected components, communities, a bridge measure, k-core and shortest paths, per time window, all distributed. The full pipeline completed on EMR at the 25% size. Hadoop also completed at 50% and 100%, in about 17, 28 and 53 minutes, while Spark job 10 ran out of memory at those two sizes; I report that as an open finding rather than hiding it."

Source for the narrated times: Hadoop 17.2, 27.6 and 53.3 minutes ([`docs/agent-runs/phase-14/human-gate.md`](agent-runs/phase-14/human-gate.md)).

### B10. Terraform plan evidence (item 18)

**Do:** open `operations/plans/20261119T173346Z-c5428063732a.md` (the plan summary) and `operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt`. Optionally show `infra/terraform/modules/`.

**Say:** "All infrastructure is Terraform. plan.sh saves a plan with its checksum, I approve that checksum, and deploy.sh refuses to apply anything else, or from a different commit."

### B11. Reproduction and a clean-checkout bounded run (item 17)

**Do:** show the README sections "Reproducing the agentic workflow" and the script commands. Then run the Phase 17 fresh-clone check (clone into `/tmp`, follow the README, run check.sh and one bounded run). The logs go to `docs/agent-runs/phase-17/evidence/fresh-clone/` (Phase 17 ruling 3).

**Say:** "Everything is reproducible from a fresh clone with the scripts the README lists. Here is a clean checkout running the gate and a bounded sample run."

### B12. Claim verification with an unsupported claim (item 16)

**Do:**

```bash
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
sbt "testOnly *UnsupportedClaimAdversarialSpec *VerifierSpec" 2>&1 | grep -E 'Spec:|^\[info\] - |Tests:'
```

The recording printed 33 passed, then 12 passed and 2 pending; the 2 pending tests are verifier limits documented in [`docs/evaluation.md`](evaluation.md) (section 4.1, D18 to D20).

**Say:** "These tests feed the verifier claims that look plausible but are wrong: a changed digit, a fabricated evidence ID, a vague quantity, a negation. Each one is rejected and removed from the answer, while exact statements of the stored value still verify."

### Closing (30 seconds)

**Say:** "To summarize: a bounded but real big-data pipeline on EMR, a verified evidence chain from source records to answers, live elasticity and failure evidence, and a fully recorded agentic build process with human gates. Thank you."

## 6. Assembly

Clips are joined in the section 3 order in iMovie. The clip audio of A5 to A11 is muted and the voiceover recorded on top; Part B keeps its own audio. Export: 1080p, quality Medium (compress "Better Quality"), saved outside the repository, uploaded with a restricted link. The step-by-step iMovie and troubleshooting notes stay in the source file.
