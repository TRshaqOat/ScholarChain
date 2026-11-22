# ScholarChain Demo Recording Script

Nov 19, 2026 · @Achyut

## When to finish

Part A was recorded on Nov 19. What's left: voiceovers for A5–A11, the Part B scenes, and putting the video together. All times are Central (CST).

| When | What | Done by | Why |
| --- | --- | --- | --- |
| Any time before Nov 24 | Voiceovers for A5–A11 in iMovie (see "Recording the voiceovers" below) | Tue Nov 24 | The microphone was off from A5 on; the screen recordings themselves are fine. |
| Mon Nov 23–Tue Nov 24 | Part B scenes B1–B12 | Tue Nov 24 | B11 needs the Phase 17 README and a fresh-clone run. |
| Tue Nov 24 | Join all clips in the final order, export, upload with a restricted link | Tue Nov 24 | Leaves Nov 25 for the final commit. |
| Wed Nov 25 | Video link in the README, final commit and tag, repository made public, link submitted on Teams | Link committed before the 6:00 PM tag; Teams by 11:00 PM | Only the last commit before the deadline counts. |

The spec sets no length. Aim for 20–30 minutes in the final video: Part A about 12–15 minutes, Part B about 10–15. Record each scene as its own clip, so a mistake costs one clip, not the whole video.

## Before you record

Spend 5 minutes on setup so every clip looks clean and nothing secret appears on screen.

**How to record (macOS).**

1. Press Cmd+Shift+5. Choose "Record Entire Screen" (or "Record Selected Portion" around one window).
2. Click Options: Microphone = MacBook Air Microphone, Save to = Desktop. Never save inside the ScholarChain folder.
3. Click Record. Stop with the stop button in the menu bar, or Cmd+Ctrl+Esc.
4. Name each clip after its scene (for example `A2-query.mov`) and number it later by the final order. Recording order doesn't matter: "Assembling the final video" below gives the order to join them in.

**Screen setup.**

- Turn on Do Not Disturb (Control Center, Focus) so no notifications pop up.
- Terminal: make the font large (Cmd+Plus three or four times) and clear the screen (`clear`) before each clip.
- Browser: sign in to the AWS console. In the top-right corner, set the Region to **US East (Ohio) us-east-2**. If a page looks empty later, the Region is the first thing to check.
- Close browser tabs you don't need. Keep one tab per console page you will show.

## Part A: voiceover scripts for A5–A11 (recorded Nov 19; narration added later)

All Part A clips are recorded. A0–A4 have good audio and need nothing more. From A5 on, the microphone was off, so each of those clips gets a voiceover recorded on top of it in iMovie (see "Recording the voiceovers" under Assembling the final video). Mute the clip's own audio first.

For each clip: what you'll see on screen, then the words to say, with cues in brackets for when to say each part. Speak a little slower than normal reading pace. If a clip runs out before the words do, slow the clip or add a freeze frame rather than rushing.

### A5. S3 Vectors source pointers (item 13)

**On screen:** the `jq` output of the explain record (`queryId`, `embeddingModelVersion`, `embeddingCalls`, `vectorIndexes`, `semanticEvidenceIds`), then one semantic evidence item resolved through the evidence endpoint. If your clip also shows the earlier attempts that printed `[]`, trim those out first.

**Voiceover:**

&#91;As the first output appears\] "Now the semantic side. Each answer's explain record lists how semantic context was used. For this question, the runtime embedded the text once with the Titan text embedding model, and searched two S3 Vectors indexes: researcher profiles and paper abstracts. These are the IDs of the semantic evidence it kept."

&#91;As the evidence item appears\] "Resolving one of those IDs shows a profile item. It carries the vector key it was found under, and a pointer to its source text in S3, with a hash, so text that has been changed since it was embedded is dropped. Semantic items are kept apart from graph facts: they can add description to an answer, but they can never verify a number, an edge or a path."

### A6. DynamoDB records (item 12)

**On screen:** the DynamoDB console: `scholarchain-course-QuerySession` items, then `scholarchain-course-EvidenceBundle`, then `scholarchain-course-ResearcherMetricCache`.

**Voiceover:**

&#91;QuerySession table and an item\] "DynamoDB holds the operational state. Every query writes a session record: the query ID, the runtime run, the model and prompt versions, the timings, and references to the answer's evidence. It deliberately stores no question text."

&#91;EvidenceBundle\] "The evidence bundle of each answer is stored item by item, so any evidence ID in an answer can be looked up later through the evidence endpoint. These items expire after a set time."

&#91;ResearcherMetricCache\] "And this cache holds the Spark-computed metrics of each researcher per time window, such as distinct coauthors, weighted degree, community and k-core, each with provenance back to the Spark run that produced it. The runtime reads these instead of recomputing them."

### A7. CloudWatch dashboard and alarms (item 19)

**On screen:** the `scholarchain-course-runtime` dashboard over 6 hours, then the Alarms list.

**Voiceover:**

&#91;Dashboard\] "This is the runtime dashboard. It shows CPU for each of the three services, load balancer requests, latency percentiles and errors, healthy targets, the dead-letter queue depth, and Neptune's CPU. The large Neptune spike earlier in the day is the bulk load of about 34 million graph records."

&#91;Alarms\] "Alarms cover load balancer errors, unhealthy targets, high CPU on each service and on Neptune, and any message in the dead-letter queue. The two TargetTracking alarms were created by the autoscaling policy itself; they are what drives scale-out and scale-in, which I'll show shortly."

### A8. Failure and recovery (item 21)

**On screen:** the listing of `experiments/results/exp-f-failure-recovery-v1/20261118T062000Z`, its `run-record.json`, then the end of `stdout.txt`.

**Voiceover:**

&#91;File listing\] "This is experiment F, failure and recovery, run in the previous cloud window. Its raw results are committed and read-only."

&#91;run-record.json\] "The run record ties the result to the exact commit, the manifest and its checksum, the time limits and the exit code."

&#91;stdout\] "I injected a job that always fails. The worker retried it three times, then SQS moved the message to the dead-letter queue and the alarm fired. After I redrove the message, the job completed exactly once, and replaying the same request with the same idempotency key returned the same answer instead of running it again."

### A9. Guard hooks blocking dangerous commands (item 4)

**On screen:** the Claude Code sidebar, the probe prompt, and the three blocked commands with the hook's messages.

**Voiceover:**

&#91;Prompt pasted\] "The agents work under executable policy, not just instructions. Here I ask Claude Code to run three dangerous commands, each one safe by construction: a dry-run recursive delete, and two commands that name resources that don't exist."

&#91;First block\] "A recursive S3 delete is blocked before it runs, by the guard-dangerous hook."

&#91;Second block\] "Terminating an EMR cluster is blocked too."

&#91;Third block\] "And setting a service to a hundred tasks is refused as a scale change beyond the approved bounds, which needs human approval. All three were stopped by the hook before anything reached AWS. The settings file also has deny rules for destructive commands, and the gate script self-tests these hooks with 304 cases."

### A10. Scale-out and scale-in (item 20)

**On screen:** the experiment's output lines, the scaling-activities table, the current task count, the ECS service Events tab, then the dashboard from 6:10 to 7:00 PM. (The live-clip version of A10 was not used.)

**Voiceover:**

&#91;Experiment output\] "This is the elasticity experiment, run earlier that evening. graph-evidence-api has a target-tracking policy on average CPU, with one to two tasks. For ten minutes, eight parallel clients sent graph and evidence requests: almost 22,000 requests, about 37 per second, with zero errors and a 95th-percentile latency of 683 milliseconds. One task was running before the load, two at the end of it, and one again after the observation period."

&#91;Scaling activities\] "Application Auto Scaling recorded both decisions: at 6:24 PM, five minutes into the load, it raised the desired count to two, and at 6:46 PM, about seventeen minutes after the load stopped, it set it back to one. Nobody changed the task count by hand."

&#91;Task count\] "The service is back at its normal size: one task desired, one running."

&#91;ECS Events\] "ECS's own event log shows the full cycle. The high-CPU alarm went into alarm, the desired count rose to two, and ECS started a second task and registered it with the load balancer. Later the low-CPU alarm fired, and ECS deregistered that same task, drained its connections and stopped it." \[If the morning's failed deployment row is visible:\] "The failed deployment that morning is from before the API key was set; the resumed deployment completed."

&#91;Dashboard\] "Here is the service's CPU during the experiment. For this run the target was 15 percent, a declared experiment setting: in the previous window the service never got near the normal 60 percent, because Neptune was the bottleneck. Neptune was at about 97 percent CPU throughout, so the scaling shows the mechanism working, not extra capacity; that's discussed in the evaluation."

### A11. Cleanup evidence (item 22)

**On screen:** the end of the cleanup apply log, the count of managed resources kept, the empty post-cleanup checks, then the empty ECS, Neptune, DynamoDB and Load Balancer console pages.

**Voiceover:**

&#91;Apply log\] "The runtime is removed through the same reviewed path that created it. The cleanup script wrote a plan of 89 resources to destroy, I approved it by its checksum, and only then did it destroy them: 89 destroyed, nothing else changed."

&#91;Kept count and checks\] "43 managed resources remain, each kept on purpose, and the checks find no Neptune cluster, no ECS cluster, no DynamoDB tables, no load balancer and no running EMR cluster."

&#91;Console pages\] "The consoles agree: nothing billable from the runtime is left running. What remains is the network, the encrypted data bucket with the pipeline outputs and evidence, the container registry, the image build project, the EMR roles, and log groups that expire on their own. They cost almost nothing at rest and are needed to reproduce the pipeline. The final cleanup, including the data bucket, happens after grading."

## Part B later: offline scenes (Nov 23–24, about 12–15 minutes of recording)

None of these need the cloud runtime. Use VS Code (`code /Users/achyutpatel/Developer/Cloud/ScholarChain`) to show files, and Terminal for commands. B11 needs the Phase 17 README, so record Part B after Phase 17.

### B1. Repository structure (item 1)

**Do:** in the VS Code Explorer, collapse everything, then open the top-level folders one by one: `.claude/`, `modules/`, `tests/`, `prompts/`, `scripts/`, `infra/terraform/`, `datasets/`, `experiments/`, `load-tests/`, `docs/agent-runs/`, `operations/`, `spec/`, plus `CLAUDE.md`, `.mcp.json` and `build.sbt`. Don't open the file inside `labels/` on camera: it holds your evaluation labels.

**Say:** "modules holds the Scala code, one sbt project per part: core contracts, Hadoop jobs, entity resolution, Spark analytics, storage, the RAG runtime and the services. tests holds the cross-module contract, adversarial and end-to-end tests, and prompts the versioned planner and synthesis prompts. scripts and infra hold the reproducible automation and Terraform. docs/agent-runs holds every phase's prompts, agent reports, reviews, repairs and my human gate decisions. operations holds the cloud records: plans, releases, pipeline runs and cleanup." (The spec says "private repository"; the professor's newer rule requires a public one, so just call it the repository.)

### B2. Agent ownership map (item 2)

**Do:** open `docs/agents.md`. Scroll to the ownership table.

**Say:** "The software was built by a Claude Code orchestrator and 18 specialized subagents. Every writable path has exactly one owning agent, so every file has one writer, and the reviewers write nothing. I acted as architect and reviewer: I wrote the work orders and rulings, and approved every phase."

### B3. One agent role file (item 3)

**Do:** open `.claude/agents/code-reviewer.md`. Point at the tools line (Read, Grep, Glob only), the refusal boundary, and the report format.

**Say:** "This is the code reviewer. Its tools are read-only, so it can never change the code it reviews. Each agent file states what it owns, what it must refuse, and the structured report it returns."

### B4. Test-stop hook and deterministic gate (item 5)

**Do:** open `.claude/settings.json` and point at the hooks section. Then open `.claude/hooks/verify-tests-ran.sh` and scroll to where it compares the test marker with the source changes.

**Say:** "verify-tests-ran.sh stops an agent from finishing after it changed source code unless sbt check has passed since: sbt writes a marker file when all tests pass, and the hook refuses a stale marker. Other hooks format Scala after edits and fence each agent to its own paths."

### B5. A phase report and a repair record (item 6)

**Do:** open `docs/agent-runs/phase-15/`. Show, in order: `prompt.md` (work orders and rulings 1–9), `agent-report.md`, `review-report.md` (code review 1 found seven minor issues), `repair-01.md`, then the "Code review 2" section of `review-report.md` (approved).

**Say:** "Every phase follows the same loop. I write work orders, the agents report exactly what they changed and ran, an independent reviewer finds problems, one repair round fixes them, the reviewer checks again, and I record the decision as a numbered ruling with the commit hash."

### B6. A clean check (item 7; about 6 minutes, cut in editing)

**Do:**

```bash
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
./scripts/check.sh 2>&1 | tail -40
```

You should end with `==> OK check: every gate passed` and test totals of about 845 passed, 0 failed, 10 pending.

**Say:** "check.sh is the gate: it validates the agent files and hooks, checks every script's syntax, validates the datasets, formats and validates Terraform, and runs sbt check, about 845 tests. Ten adversarial tests are marked pending: known limits that are documented, not hidden."

### B7. Dataset manifest and real samples (item 8)

**Do:** open the `datasets/` folder. Show datasets/dataset-manifest.yaml (it lists dblp, openalex, crossref and orcid with license, size, checksum and snapshot fields), one schema note in `datasets/schemas/`, and one sample file per source in `datasets/samples/` (DBLP XML, OpenAlex JSON lines, Crossref JSON lines; the ORCID samples are ORCID's published fictional test record and clearly marked synthetic records, not real people's data).

**Say:** "DBLP is the seed. OpenAlex and Crossref are linked by DOI and ORCID by researcher iD. The manifest records each source's license, snapshot date, checksums, the bounded subset I selected, and known staleness."

### B8. Hadoop execution and counters (item 9)

**Do:**

1. In the AWS console (Ohio), open EMR, Clusters, filter "All", and open `j-03327281MRPKUSN1LE0M` (the complete 25% run, Nov 17). Show the Steps tab: the classpath probe, the Hadoop pipeline step, and the Spark step, all completed. (Terminated clusters stay listed for about two months.)
2. In VS Code, open `operations/pipeline-runs/` and the record for `20261117T045335Z-fraction25`. Show the counts it recorded (for example J01: 27,993 works accepted).
3. Optionally open `docs/agent-runs/phase-14/evidence/window-1/p14-window1-results.tsv` for the timing table.

**Say:** "Hadoop MapReduce does the first heavy pass on EMR: parsing DBLP XML without splitting records, normalizing, joining the enrichment sources by DOI and ORCID, blocking for entity resolution, and expanding each paper into coauthor pairs. Every job keeps counters and writes rejected records to a reject output."

### B9. Spark execution and graph outputs (item 10)

**Do:** in the same EMR cluster, show the Spark step. Then in S3 (data bucket), open `curated/` and one Spark output folder with its `_manifest.json`, or show the run IDs in the pipeline record.

**Say:** "Spark builds the temporal weighted coauthorship graph from the Hadoop outputs, then computes distinct and weighted degree, connected components, communities, a bridge measure, k-core and shortest paths, per time window, all distributed. The full pipeline completed on EMR at the 25% size. Hadoop also completed at 50% and 100%, in about 17, 28 and 53 minutes, while Spark job 10 ran out of memory at those two sizes; I report that as an open finding rather than hiding it."

### B10. Terraform plan evidence (item 18)

**Do:** open `operations/plans/20261119T173346Z-c5428063732a.md` (the plan summary) and `operations/plans/20261119T173346Z-c5428063732a-APPROVED.txt`. Optionally show `infra/terraform/modules/`.

**Say:** "All infrastructure is Terraform. plan.sh saves a plan with its checksum, I approve that checksum, and deploy.sh refuses to apply anything else, or from a different commit."

### B11. Reproduction and a clean-checkout bounded run (item 17)

**Do:** after Phase 17, show the README sections "Reproducing the agentic workflow" and the script commands. Then run the fresh-clone check the chat gives you in Phase 17 (clone into `/tmp`, follow the README, run check.sh and one bounded run).

**Say:** "Everything is reproducible from a fresh clone with the scripts the README lists. Here is a clean checkout running the gate and a bounded sample run."

### B12. Claim verification with an unsupported claim (item 16)

**Do:**

```bash
cd /Users/achyutpatel/Developer/Cloud/ScholarChain
sbt "testOnly *UnsupportedClaimAdversarialSpec *VerifierSpec" 2>&1 | grep -E 'Spec:|^\[info\] - |Tests:'
```

**Say:** "These tests feed the verifier claims that look plausible but are wrong: a changed digit, a fabricated evidence ID, a vague quantity, a negation. Each one is rejected and removed from the answer, while exact statements of the stored value still verify."

### Closing (30 seconds)

**Say:** "To summarize: a bounded but real big-data pipeline on EMR, a verified evidence chain from source records to answers, live elasticity and failure evidence, and a fully recorded agentic build process with human gates. Thank you."

## Assembling the final video

Record the clips in any order, then join them in the order below, so the video tells one story: what was built, how it was built, then the live system. The total comes to about 30 minutes before trimming.

**Making clips that join cleanly.**

- Start each clip with one second of a still screen, then a one-sentence lead-in naming what comes, for example "Next, a live Neptune graph query."
- End each clip on a still screen for a second, with nothing half-typed.
- Record A2 to A5 in the same Terminal session: A3 to A5 reuse the query ID and evidence ID from A2. If you rerun the setup block, record A2 again before A3.
- A9 is recorded tonight but is placed with the other factory scenes in the final order. That is fine: the order of the video and the order of recording don't have to match.
- Re-record any clip freely. Keep only the best take of each.

**Final order.**

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

### Recording the voiceovers and joining the clips (iMovie)

Use **iMovie**. It's free on the Mac, it records narration straight onto the timeline while the clip plays, it can mute a clip's own audio, and it exports the joined video. QuickTime can trim and join but can't lay a voice track over a video. ScreenFlow and Camtasia can, but they're paid, and DaVinci Resolve is free but much more complex than this job needs.

1. Open iMovie (free in the App Store if it's missing). Choose File, New Movie. Then File, Import Media, and select all your clips from the Desktop.
2. Drag the clips into the timeline in the final order (table above). Trim silence or long waits by dragging a clip's edges.
3. Mute the original audio of A5 to A11: select the clip, click the volume button above the viewer and drag it to 0 (or right-click the clip, choose Detach Audio, and delete the detached audio).
4. Test the microphone first, because that's what failed last time. Click the microphone button below the viewer (or press V). In the voiceover options (the small settings icon beside the record button), set Input Source to MacBook Air Microphone, set the volume, and turn on Mute Project. Say a few words and check that the level meter moves.
5. Put the playhead at the start of A5 and click the red record button. After the 3-second countdown, read the A5 voiceover while the clip plays, following the cues in brackets. Click again to stop. The narration appears as a green audio clip under the video.
6. Play it back. To redo it, select the green clip, press Delete, and record again. Drag the green clip to shift its timing, or drag its ends to trim it.
7. If the words run longer than the scene, select the video clip and slow it (the speedometer button, Slow), or put the playhead on a still moment, choose Modify, Add Freeze Frame, and drag the freeze to the length you need.
8. Repeat for A6 to A11. Part B clips keep their own audio and need no voiceover.
9. Choose File, Share, File: resolution 1080p, quality High. Save the video outside the repository.

**Uploading.** The spec forbids sharing final videos with other students, so upload with restricted access: OneDrive or Microsoft Stream shared with the professor, or YouTube as Unlisted. Check that the link opens in a private browser window, then put it in the README before the Nov 25 tag.

## Troubleshooting while recording

Most problems have a one-step fix.

| What you see or hear | Likely cause | What to do |
| --- | --- | --- |
| A voiceover is silent | Wrong input source, or the microphone is off | In the voiceover options, set Input Source to MacBook Air Microphone and check that the level meter moves before recording. |
| Noise or old sound under the voiceover | The clip's own audio isn't muted | Select the video clip and set its volume to 0. |
| The voiceover is too loud or too quiet | Recording level | Select the green voiceover clip and adjust its volume. |
| The words run longer than the scene | The clip is short | Slow the clip, or add a freeze frame and stretch it. |
| Something secret appears in a Part B clip (a key, a token, the labels file) | A safety slip while recording | Stop, delete the clip, and record it again. |
| A file was created or changed inside the repo while recording Part B | A command wrote into the repo folder | Run `git status --short` and tell me. |

## Checklist of the 22 required items

Every item from the spec's "Demonstration requirements" section is covered by one scene. Tick each one when its clip is recorded.

- [ ] 1\. Repository structure: B1
- [ ] 2\. Agent ownership map: B2
- [ ] 3\. One agent role file: B3
- [ ] 4\. Dangerous-command hook blocking a probe (three probes): A9
- [ ] 5\. Test-stop hook or deterministic gate: B4
- [ ] 6\. A phase report and repair record: B5
- [ ] 7\. A clean `sbt check` result: B6
- [ ] 8\. Dataset manifest and real source samples: B7
- [ ] 9\. Hadoop execution evidence and counters: B8
- [ ] 10\. Spark execution evidence and graph outputs: B9
- [ ] 11\. Neptune graph query: A4
- [ ] 12\. DynamoDB run or query record: A6
- [ ] 13\. S3 Vectors query with source pointers: A5
- [ ] 14\. A complete user query through the API: A2
- [ ] 15\. Evidence and explain endpoints: A3
- [ ] 16\. Claim verification with an unsupported-claim test: B12
- [ ] 17\. Reproduction scripts, agent replay, clean-checkout bounded run: B11
- [ ] 18\. Terraform plan or deployed resources: A1 and B10
- [ ] 19\. CloudWatch elasticity dashboard: A7
- [ ] 20\. Scale-out and scale-in evidence: A10
- [ ] 21\. One failure and recovery example: A8
- [ ] 22\. Cleanup or resource inventory: A11

Items 11–15 and 19–21 need the live runtime, so they must be recorded tonight. A clip that goes wrong can be re-recorded; the final video only needs one good take of each.
