## R1: entity-resolution-engineer

````text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Repair-02 work order R1 (docs/agent-runs/phase-05/prompt.md). Fix review 1 finding 1 with a pure, versioned exact-group conflict function for researcher `orcid:` groups, per "Human rulings after review 1". Do not edit modules/hadoop or scholarchain.conf, run sbt check twice, and report the signature and rule ID for the hadoop-engineer.

FILES CREATED: none
FILES MODIFIED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/MatchPolicy.scala (untracked file, edited). Adds the rule ID at line 139 and the function at lines 262-276. The er-v2 scaladoc paragraph about the new group rule is at lines 99-103.
- /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/MatchPolicyV2Spec.scala (untracked file, 3 tests appended)
FILES DELETED: none

FOR THE HADOOP-ENGINEER (R2):
- Function signature (object scholarchain.entityresolution.MatchPolicy):
  ```scala
  def orcidGroupSuffixConflict(key: String, memberRawNames: Seq[String], version: String): Boolean
  ```
  - `key` is the J03+J04 group key, for example `orcid:0000-0001-2345-6789`.
  - `memberRawNames` holds the AuthorMention.rawName of every member mention in the group.
  - `version` is the configured policy version. It has no default on purpose, which avoids the review 1 minor finding about defaulting to er-v1.
  - An unknown version throws IllegalArgumentException (requireVersion).
- It returns true only when all of these hold: the version is er-v2, the key starts with `orcid:`, and `memberRawNames.flatMap(Normalize.name(_).homonymSuffix).distinct.size >= 2`.
- Rule ID: `MatchPolicy.Rule.OrcidGroupSuffixConflict` = `"er-v2:orcid-group-homonym-suffix-conflict"`
- Integration note: `IndexEntry(kind, id, source, workId)` in modules/hadoop Logic.scala does not carry the raw name today. R2 needs to bring each mention's rawName into the J03+J04 group, either by adding it to IndexEntry or by another route of the hadoop-engineer's choosing. Per the ruling, when the function is true the whole group goes to conflicts/, is counted, and reaches J10 as EXACT_CONFLICT. That already decides as CONFLICT with `er-v1:exact-id-group-conflict` / `EXACT_ID_GROUP_CONFLICT`, so MatchPolicy.decide needed no change.

POLICY VERSION: er-v2, amended in place per the human ruling (no er-v3). er-v1 is unchanged.
FEATURES AND RULES:
- The new group-level rule is `er-v2:orcid-group-homonym-suffix-conflict`.
- It uses the same suffix extraction as feat-v1 homonym_suffix_agreement: `Normalize.name(raw).homonymSuffix`, which comes from splitHomonym and matches a trailing 4-digit number.
- A mention with no suffix adds nothing, so {0001, none} is not a conflict.
- `openalex:` groups are out of scope.
- No feature, weight, or blocked-pair rule changed.
THRESHOLDS: unchanged (accept, reject, and LLM band all come from configuration; scholarchain.conf was not touched)
MODEL AND PROMPT VERSIONS: unchanged. No LLM path is involved.
EVALUATION: not run. Gold labels are pending, and the human restricted all access to labels/. Nothing under labels/ was read, listed, or searched.
REPRESENTATIVE ERRORS: none measured. The number of orcid groups this rule marks in the full run is R2's to report.
LEAKAGE CHECK: the function takes only the group key, the mentions' raw names, and the version. It reads no labels and no configuration.
COMMANDS RUN: several grep/sed reads of the docs and module sources, a python3 in-place edit of MatchPolicy.scala, a heredoc append to MatchPolicyV2Spec.scala, `sbt check` (twice), and `git status --porcelain modules/hadoop modules/core`.
TEST RESULTS (observed):
- Run 1: entityResolution "Tests: succeeded 94, failed 0, canceled 0, ignored 0, pending 0"; hadoop "Tests: succeeded 64, failed 0, ..."; markTestRun wrote .claude/.last-test-run; "[success] Total time: 155 s ... completed Nov 4, 2026, 1:22:10 AM". I did not capture the core count line in run 1's tail.
- Run 2: core "Tests: succeeded 151, failed 0"; entityResolution "Tests: succeeded 94, failed 0"; hadoop "Tests: succeeded 64, failed 0"; markTestRun wrote .claude/.last-test-run; "[success] Total time: 156 s (0:02:36.0), completed Nov 4, 2026, 1:24:53 AM".
- entityResolution went from 91 to 94: the 3 new tests.
VALIDATION RESULTS: the three new tests pass, and each can be checked by hand.
- (1) The reviewer's failure input: `orcid:0000-0001-2345-6789` on "Wei Wang 0001" and "Wei Wang 0002" is a conflict under er-v2. A mixed-order version with repeats and an unsuffixed mention is also a conflict.
- (2) These are not conflicts under er-v2:
  - {"Wei Wang 0001", "Wei Wang"}, where one mention has no suffix;
  - one shared suffix;
  - no suffixes at all;
  - an empty group;
  - an `openalex:` key.
- (3) Under er-v1 the failure input is not a conflict, and its EXACT_MATCH candidate still decides ACCEPTED / EXACT_ORCID_MATCH. An unknown version throws.
- The scalafmt check passed inside sbt check.
- modules/hadoop and modules/core still carry their earlier uncommitted changes, which were already present at session start. I did not edit them. Nothing was committed.
ASSUMPTIONS:
- "Non-empty suffix" means the Normalize.name homonymSuffix is present. The 4-digit regex guarantees it is never empty.
- The rule looks only at suffix disagreement across the whole group. The existing same-work clash rule in J03+J04 stays as it is.
RISKS:
- Per-decision auditability: J10 decisions for these groups record the generic `er-v1:exact-id-group-conflict` rule, not the new rule ID. The candidate carries no marker that would distinguish the two. Until the hadoop-engineer emits one (for example a counter plus a conflict-reason field), the new rule ID is visible only in counters and J03+J04 output.
- Transitive chains across groups are covered only by the Phase 6 Spark refusal rule, which is the human's carry-forward.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R2 hadoop-engineer applies the function in J03+J04, then review 2 by the code-reviewer, then the gold-set evaluation once labels exist, then the match-policy review.

Not done or not checked: the gold-set evaluation (labels are off limits) and anything inside modules/hadoop. The main risk is the per-decision rule-ID gap described under RISKS.
````

## R2: hadoop-engineer

````text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 05 repair-02, work order R2 (docs/agent-runs/phase-05/prompt.md). Apply the R1 function `MatchPolicy.orcidGroupSuffixConflict` in J03+J04 under the configured policy version. Send the groups it marks to conflicts/ and count them with a new counter. Add LogicSpec and PipelineSpec cases with the reviewer's failure input under er-v2 and er-v1. Rerun the full chain under er-v2 into scratch/hadoop/wo5-v2b/. Run sbt check twice, run packageJobs, report the jar listing, and report deltas against work order 10.

Summary: the R1 rule now runs in J03+J04, and both sbt check runs passed. The er-v2 rerun (run-035e75abfa6798c938cbd534273dbd8d) marked 16 ORCID groups (410 mentions) as conflicts. Every WORK decision is the same as in work order 10, record by record. One of the 16 groups (152 mentions) is larger than max-conflict-group-size = 100, so it writes no conflict pairs and does not reach J10 as EXACT_CONFLICT (RISKS 1).

FILES CREATED: none in the repository.
FILES MODIFIED (all under /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/; these files already had uncommitted changes at session start, so `git diff` does not isolate R2):
- src/main/scala/scholarchain/hadoop/Logic.scala:
  - `IndexEntry` gains `rawName: Option[String] = None` (the mention's printed name; None for works).
  - `ExactIdentifiers.resolve` gains a required `policyVersion: String` parameter. In the researcher branch it calls `MatchPolicy.orcidGroupSuffixConflict(key, members.flatMap(_.rawName), policyVersion)`. When that is true, the whole group is one conflict group: all n(n-1)/2 member pairs go to conflicts, nothing matches, and the existing cap still applies.
  - `Outcome` gains `suffixConflict: Boolean = false`.
- src/main/scala/scholarchain/hadoop/Jobs.scala:
  - `ExactKeyMapper` puts `Some(m.rawName)` into the mention IndexEntry.
  - `ExactJoinReducer` reads `scholarchain.entity-resolution.policy-version` in setup through `MatchPolicy.requireVersion`, so an unknown version fails the task, and registers ORCID_SUFFIX_CONFLICT at 0.
  - For each flagged group the reducer counts ORCID_SUFFIX_CONFLICT (as well as CONFLICT) and writes one `suffix-conflicts/` line `{job, identifier, groupSize, rule, policyVersion}`.
- src/main/scala/scholarchain/hadoop/Common.scala: new counter `Counters.OrcidSuffixConflict = "ORCID_SUFFIX_CONFLICT"`.
- JOBS.md: the J03+J04 mapper, reducer, outputs, counters and test fixtures are updated. suffix-conflicts/ is listed as not exported by J09.
- src/test/scala/scholarchain/hadoop/LogicSpec.scala: the existing `resolve` calls now pass V2. 4 new tests:
  - the reviewer's input under er-v2: one ORCID 0000-0001-2345-6789 on "Wei Wang 0001" (dblp:conf/x/A1#1) and "Wei Wang 0002" (dblp:conf/y/B7#1). Expected: exactly one RESEARCHER conflict (A1#1, B7#1) and no matches.
  - the same input under er-v1: both mentions match the iD and there is no conflict.
  - a whole group of 4 (including an unsuffixed mention and a same-work clash): 6 pairs, no match. With the cap at 3: no pairs, an OversizeConflict of 4, and still no match.
  - {"Wei Wang 0001", "Wei Wang"}: not a conflict. An `openalex:` key: the rule does not apply.
- src/test/scala/scholarchain/hadoop/PipelineSpec.scala: the reviewer's input as a second `--dblp` XML file, run end to end through J10 in 2 new tests:
  - er-v2: no match under the key, the conflict pair in conflicts/, ORCID_SUFFIX_CONFLICT = 1, the suffix-conflicts/ line, and one exact-ID J10 decision CONFLICT with `er-v1:exact-id-group-conflict`. No ACCEPTED decision exists between the two mentions. The full `checkDecisions` recomputation passes.
  - er-v1: both mentions are in matches/ and ACCEPTED, the counter is 0, there is no suffix-conflicts/ output, and `checkDecisions` passes.
FILES DELETED: none. scratch/hadoop/wo5-full/ and scratch/hadoop/wo5-v2/ are untouched: `find -newer` finds 0 files modified during this session.

JOB GRAPH: unchanged. J01, J02, J03+J04, J05 p0/p1/p2, J06, J10, J07+J08, J09, run in sequence; all ran.

KEY-VALUE CONTRACTS:
- J03+J04 map value: IndexEntry JSON, now `{kind, id, source, workId, rawName?}`. It is internal to the shuffle.
- Reduce: (identifier, [IndexEntry]) goes to matches/, conflicts/, oversize-conflicts/, the new suffix-conflicts/, stats/ and rejects-v1/.
- J10 and J09 contracts are unchanged.
- Pipeline.scala is not touched. The driver already set the policy-version key for every job.

PARTITIONING AND COMPRESSION: unchanged. HashPartitioner, 8 reducers, zlib map output, gzip final output. J10 is map-only.

COUNTERS (R2 run, with delta against work order 10 run-4db46b8bcc1d3473446298ac6705f149):
- J01, J02, J05 p0/p1/p2, J06, J07+J08: every counter has delta 0. Key values:
  - J01: INPUT 110867, ACCEPTED 110867, REJECTED 0.
  - J02: INPUT 237207, ACCEPTED 237206, REJECTED 1, SCHEMA_FAILURE 1.
  - J06: MATCH_CANDIDATE 26674065, WORK_PAIR 621201.
  - J07+J08: EMITTED_PAIR 1019535, OVERSIZE_TEAM 4.
- J03+J04:
  - INPUT 1598294 (+0), ACCEPTED 1598294 (+0), REJECTED 0, MISSING_KEY 5051 (+0), EXACT_UNMATCHED 2134 (+0)
  - CONFLICT 303 (+16), ORCID_SUFFIX_CONFLICT 16 (+16), OVERSIZE_CONFLICT 1 (+1), EXACT_MATCH 1110282 (-410)
  - Outputs: conflicts 3924 (+3594), matches 1110282 (-410), suffix-conflicts 16 (+16), oversize-conflicts 1 (+1)
- J10: INPUT 28409472 (+3184), ACCEPTED 27788271 (+3184), DEFERRED_WORK_CANDIDATE 621201 (+0), LLM_ELIGIBLE 8324927 (+0), REJECTED 0.
- J09: INPUT and ACCEPTED 58522239 (+6368), REJECTED 0. match-decisions 27788271 (+3184), exact-id-conflicts 3924 (+3594), match-candidates 1110282 (-410).

DECISIONS BY ENTITY TYPE, PATH, AND OUTCOME (R2, work order 10, delta):

| Entity | Path | Outcome | R2 | WO10 | Delta |
|---|---|---|---|---|---|
| RESEARCHER | DETERMINISTIC | ACCEPTED | 297,105 | 297,105 | 0 |
| RESEARCHER | EXACT_ID | ACCEPTED | 4,867,814 | 4,868,224 | -410 |
| RESEARCHER | EXACT_ID | CONFLICT | 95,862 | 92,268 | +3,594 |
| RESEARCHER | EXACT_ID | REJECTED | 1,279,682 | 1,279,682 | 0 |
| RESEARCHER | EXACT_ID | UNRESOLVED | 397,287 | 397,287 | 0 |
| RESEARCHER | LLM_BAND | UNRESOLVED | 8,324,838 | 8,324,838 | 0 |
| RESEARCHER | PROBABILISTIC | ACCEPTED | 6,294,917 | 6,294,917 | 0 |
| RESEARCHER | PROBABILISTIC | REJECTED | 4,584,179 | 4,584,179 | 0 |
| RESEARCHER | PROBABILISTIC | UNRESOLVED | 815,167 | 815,167 | 0 |
| WORK | (all 8 cells) | | identical | identical | 0 |

- Totals: RESEARCHER 26,956,851 (+3,184); WORK 831,420 (+0).
- Reconciliation: 410 EXACT_ORCID ACCEPTED decisions (`er-v1:exact-id-match`) were removed. 3,594 CONFLICT decisions (`er-v1:exact-id-group-conflict`) were added, which equals the sum of n(n-1)/2 over the 15 uncapped groups. Net +3,184.
- Every other RESEARCHER decision is identical record by record: 26,953,257 lines in both runs, order-independent digest 8349925826785108028 in both, with runId and decidedAt masked.

ORCID GROUPS MARKED BY THE NEW RULE: 16 groups, 410 mentions in total (137 DBLP, 197 OpenAlex, 76 Crossref). 15 of the groups mix sources. Mention counts per group:

| Group | Mentions |
|---|---|
| orcid:0000-0001-5832-0347 | 152 (capped) |
| orcid:0000-0002-0726-2025 | 57 |
| orcid:0000-0002-6645-6161 | 34 |
| orcid:0000-0002-7353-8798 | 30 |
| orcid:0000-0002-0414-7910 | 24 |
| orcid:0000-0001-7260-0521 | 20 |
| orcid:0000-0003-3486-6016 | 18 |
| orcid:0000-0002-1445-3700 | 16 |
| orcid:0000-0002-9405-4485 | 15 |
| orcid:0000-0001-7988-8943 | 13 |
| orcid:0000-0001-9358-8808 | 9 |
| orcid:0000-0002-2656-7705 | 6 |
| orcid:0000-0002-4186-7618 | 6 |
| orcid:0000-0002-7240-3541 | 5 |
| orcid:0000-0002-6293-1041 | 3 |
| orcid:0000-0002-9092-720X | 2 |

- The member sets taken from the work order 10 J03+J04 output equal groupSize for every key.
- No matches/ line remains under any of the 16 keys.

REQUIRED CONFIRMATION: every WORK decision is unchanged from work order 10. The same 831,420 sourceRecordId keys appear in both runs, and every record is byte-identical after masking runId and decidedAt.

REJECTS: 1 (j02-source-normalize/rejects-v1, the same as work orders 6 and 10). Path: scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d/j02-source-normalize/rejects-v1/

TEAM AND BLOCK EXTREMES: unchanged from work order 10.
- Largest team: 287 authors, which is also the largest pair expansion at 41,041 pairs.
- Largest blocking group: 4,606 (r0:zhang|y). 73 blocks were still oversize at p2.
- Largest exact-ID group: 629 (orcid:0000-0002-3090-1059). The new rule does not mark it.
- Largest suffix-conflict group: 152.

OUTPUT PATHS:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d/ (4,977,652 KB by `du -sk`).
- The manifest has matchPolicyVersion er-v2 and codeVersion 9cbef743bfa5e8c07e69526c43c33bcc7c2c2d85-dirty. Apart from runId, codeVersion and startedAt (2026-11-04T07:43:47.521709Z), it equals the work order 10 manifest, including the input fingerprints.
- The new run ID comes from the new HEAD in codeVersion. The policy version is still not a run-ID input (the human-accepted residual risk).

DISK AND TIME:
- Free disk before the run: 19,286,116 KB. Lowest during the run: 12,122,048 KB (11.6 GiB). After: 17,197,840 KB. The 5 GiB watchdog never fired.
- 1079.22 s wall, 5347.13 s user CPU, peak RSS 5.20 GB.

COMMANDS RUN:
- `df -h`, `du -sh` on scratch/hadoop (18 GiB free at start; about 13 GiB would remain after a 4.7 GiB run).
- `sbt -batch hadoop/scalafmtAll "hadoop/testOnly *LogicSpec *PipelineSpec"`. Exit 1: 33 passed, 1 failed. The failing test assertion was wrong: the pair also met in a block, so J10 has a second, BLOCKED decision on it, which is also CONFLICT. I narrowed the assertion to exact-ID decisions by sourceRecordId `<key>|a|b`.
- `sbt -batch hadoop/scalafmtAll "hadoop/testOnly *PipelineSpec"`: exit 0, 16 of 16.
- `sbt check` (1): exit 0.
- `sbt -batch packageJobs`: exit 0.
- `stat` and `unzip -Z1` on the assembly.
- `sbt -batch "export hadoop/Compile/dependencyClasspath"`: the same set of entries as work order 10.
- The full run with scratchpad/full-run.sh (the work order 10 script with `--out scratch/hadoop/wo5-v2b`, no env override). Log: scratchpad/full-run.log.
- `python3 -I scratchpad/compare_r2.py <wo10 run> <r2 run>`: output in scratchpad/compare.out.
- `python3 -I scratchpad/groups_r2.py`: output in scratchpad/groups.out.
- `find -newer` and `du -sk` on wo5-v2 and wo5-full.
- `sbt check` (2): exit 0.
- `git status --porcelain` on modules/. Nothing was committed.
- The scratchpad is /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b332d650-4929-4f86-ae65-5acec2fa2093/scratchpad/.
- Nothing under labels/ was read, listed, searched or touched.

PACKAGE:
- Hadoop assembly: /Users/achyutpatel/Developer/Cloud/ScholarChain/modules/hadoop/target/scala-3.3.7/hadoop-assembly-0.1.0.jar, 10,988,100 bytes, built Nov 4 01:43.
- `unzip -Z1 <jar> | grep -c software/amazon/awssdk` printed: 0

TEST RESULTS (both sbt check runs, exit 0, identical counts):
- core: "Tests: succeeded 151, failed 0, canceled 0, ignored 0, pending 0"
- entityResolution: "Tests: succeeded 94, failed 0, ..."
- hadoop: "Tests: succeeded 70, failed 0, ...". That is up from 64: LogicSpec +4, PipelineSpec +2.
- Both runs: "markTestRun: tests passed, wrote .../.claude/.last-test-run". Run 1 finished at 1:43:08 AM, run 2 at 2:07:59 AM, each in 258 s. No source changed between them.
- The existing PipelineSpec check that er-v1 and er-v2 researcher decisions are equal on the samples still passes, so the repository samples contain no suffix-conflict group.

VALIDATION RESULTS: every J03+J04, J10 and J09 delta reconciles exactly (+3594 conflicts, -410 matches, +3184 decisions, J09 +6368 = 3594 + 3184 - 410). J01, J02 and J05 to J08 have delta 0.

ASSUMPTIONS:
1. "Whole group to conflicts/" means every member pair (n(n-1)/2), including pairs that share a suffix and pairs with unsuffixed mentions. This follows the ruling's "conservative; never merged".
2. The existing max-conflict-group-size cap (100, configuration) also applies to these groups. A capped group writes no pairs and matches nothing.
3. The rule receives the raw names of all members, from all sources. Only DBLP names carry suffixes in practice.

RISKS:
1. Human decision, for the entity-resolution-engineer and spark-engineer: orcid:0000-0001-5832-0347 (152 mentions: 35 DBLP, 82 OpenAlex, 35 Crossref) is capped.
   - It reaches oversize-conflicts/ only. It produces no EXACT_CONFLICT decisions, which falls short of the ruling's "reaches J10 as EXACT_CONFLICT".
   - None of its mentions is ACCEPTED to the iD any more. But 6,648 blocked RESEARCHER pairs between its members are ACCEPTED (none with homonym_suffix_agreement = 0).
   - Only the Phase 6 suffix-disagreement refusal rule stops a merge through those pairs.
   - Options: raise entity-resolution.max-conflict-group-size (owned by domain-engineer and entity-resolution-engineer; 152 needs 11,476 pairs), or make oversize-conflicts/ a J10 or Phase 6 input.
2. In the 15 uncapped groups, 3,282 blocked RESEARCHER pairs between members are ACCEPTED, while the same pairs have an EXACT_ID CONFLICT decision. The two decisions disagree by design. The Phase 6 rule "refuse a cluster containing two records with a CONFLICT decision between them" covers this. Precedence between the two belongs to Phase 6.
3. Per-decision auditability (R1 risk): J10 records the generic `er-v1:exact-id-group-conflict` for these pairs. The new rule ID appears in suffix-conflicts/ and in the counter, not in each decision.

MANUAL ACTION REQUIRED: scratch/hadoop/wo5-full/ (4.5G), wo5-v2/ (4.6G by du -sh; 4.7G earlier, rounding only, 0 files modified) and wo5-v2b/ (4.7G) all stay in place. Deleting any of them is a human decision. Free disk is now about 16.4 GiB.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: R3 (entity-resolution-engineer) re-keys the queue against run-035e75abfa6798c938cbd534273dbd8d, then R4 code-reviewer review 2. The human rules on RISKS 1, the capped suffix-conflict group.

Not done or not checked: I did not rerun the full chain a second time for full-scale determinism; it is shown only through matching every unaffected record against work order 10. I did not check whether the 152-member group is a real person or bad ORCID metadata.
````

## R3: entity-resolution-engineer

````text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 05 repair-02, work order R3. Re-key the work order 7 gold queue to the R2 run (scratch/hadoop/wo5-v2b/.../run=run-035e75abfa6798c938cbd534273dbd8d) with `ReviewQueue --rekey`, using the er-v1 strata file scratch/er/review-queue-wo5/review-queue-20261103-strata.csv as input. Write the output under scratch/er/review-queue-wo5/repair-02/, do not resample, leave every existing file unchanged, and report the rows that changed decision or path compared with the work order 11 er-v2 strata file, by entity type. Do not read labels/.

**Summary.** The re-keyed file is at /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/er/review-queue-wo5/repair-02/review-queue-20261103-strata-er-v2.csv. Compared with the work order 11 er-v2 file, no rows changed decision or path: 0 of 260 researcher rows and 0 of 50 work rows. The only column that differs on any row is `er_run_id`. The reason is that none of the 310 queued rows involves any of the 410 mentions in the 16 ORCID suffix-conflict groups that R2 changed. I made no source changes, so I did not run `sbt check`, as the work order allowed.

FILES CREATED:
- /Users/achyutpatel/Developer/Cloud/ScholarChain/scratch/er/review-queue-wo5/repair-02/review-queue-20261103-strata-er-v2.csv (scratch output, 310 rows, 106,346 bytes, sha256 15bb5dc256f9ffb293e5b83056cd4d6401cd59777d8302f081a8e369ef288e70). The directory repair-02/ was created by the tool.
- Read-only check scripts in my scratchpad (not repository files): /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b332d650-4929-4f86-ae65-5acec2fa2093/scratchpad/cmp_r3.py and /private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/b332d650-4929-4f86-ae65-5acec2fa2093/scratchpad/members_r3.py
FILES MODIFIED: none. No source changes.
FILES DELETED: none. The three existing files in scratch/er/review-queue-wo5/ had the same sha256 before and after the run, matching the work order 7 and 11 reports:
- review-queue-20261103.csv (blind): 1e9ad1af818dab6ba9110dc19bf3355b47214356e4e0d856e97e7c39dee39d8d
- review-queue-20261103-strata.csv (er-v1): 5c14e343395b4220875c9774074eff585be0d439295e8b0d909b0fed0f3b7045
- review-queue-20261103-strata-er-v2.csv (work order 11): 2318f12c192a255a2dcd51c019a247f48819f85f4fceb8ab7a61df81dfc7e8d7

POLICY VERSION: er-v2 on all 310 rows. `er_run_id` is run-035e75abfa6798c938cbd534273dbd8d on all 310 rows. The R2 manifest's matchPolicyVersion is er-v2.
FEATURES AND RULES: unchanged.
THRESHOLDS: unchanged, read from the R2 run manifest: accept 0.93, reject 0.35, LLM band [0.65, 0.93].
MODEL AND PROMPT VERSIONS: no LLM was used.

EVALUATION: not run, because labels/ is off limits. Re-keying results:

1. Compared with the work order 11 er-v2 file (scratch/er/review-queue-wo5/review-queue-20261103-strata-er-v2.csv):

| Entity | Rows | Decision changed | Path changed | Decision or path changed | Any other column changed (excluding er_run_id) |
|---|---|---|---|---|---|
| RESEARCHER | 260 | 0 | 0 | 0 | 0 |
| WORK | 50 | 0 | 0 | 0 | 0 |

   - The `row_id` order, `entity_type`, `a_id` and `b_id` are identical on all 310 rows.
   - Only `er_run_id` differs: run-4db46b8bcc1d3473446298ac6705f149 became run-035e75abfa6798c938cbd534273dbd8d.

2. Compared with the er-v1 input file (the tool's own printout): RESEARCHER 0 of 260 changed. WORK 7 of 50 changed: 4 went from ACCEPTED/PROBABILISTIC to ACCEPTED/DETERMINISTIC, and 3 went from UNRESOLVED/LLM_BAND to ACCEPTED/DETERMINISTIC. These are the same 7 as in work order 11.

3. er-v2 counts by entity type, path and decision. These are the same as the work order 11 file.

| Entity | Path | Decision | Rows |
|---|---|---|---|
| RESEARCHER | DETERMINISTIC | ACCEPTED | 13 |
| RESEARCHER | EXACT_ID | ACCEPTED | 39 |
| RESEARCHER | EXACT_ID | CONFLICT | 13 |
| RESEARCHER | EXACT_ID | REJECTED | 39 |
| RESEARCHER | LLM_BAND | UNRESOLVED | 39 |
| RESEARCHER | PROBABILISTIC | ACCEPTED | 39 |
| RESEARCHER | PROBABILISTIC | REJECTED | 39 |
| RESEARCHER | PROBABILISTIC | UNRESOLVED | 39 |
| WORK | DETERMINISTIC | ACCEPTED | 14 |
| WORK | EXACT_ID | ACCEPTED | 6 |
| WORK | EXACT_ID | CONFLICT | 6 |
| WORK | EXACT_ID | REJECTED | 7 |
| WORK | LLM_BAND | UNRESOLVED | 3 |
| WORK | PROBABILISTIC | ACCEPTED | 2 |
| WORK | PROBABILISTIC | REJECTED | 6 |
| WORK | PROBABILISTIC | UNRESOLVED | 6 |

4. Why nothing changed:
   - I collected the 16 keys from the R2 suffix-conflicts/ output.
   - I collected their members from the work order 10 matches/ output. That gives 410 member mentions, which matches R2's count, and includes the capped orcid:0000-0001-5832-0347.
   - 0 of the 310 queued rows have a member mention as a_id or b_id.

REPRESENTATIVE ERRORS: none, because there are no labels.

LEAKAGE CHECK:
- `rekey` reads only the input strata file and the R2 run's J10 decisions and manifest.
- My scratchpad scripts read only the scratch strata files and the scratch Hadoop outputs.
- I never read, listed, searched or touched labels/, and I wrote no label values.

COMMANDS RUN:
- `shasum -a 256 scratch/er/review-queue-wo5/*.csv`, plus a grep of Io.writeString (it creates parent directories). I also read matchPolicyVersion and the thresholds from the R2 `_manifest.json` with python3.
- `sbt -batch -J-Xmx8g "entityResolution/runMain scholarchain.entityresolution.tools.ReviewQueue --run scratch/hadoop/wo5-v2b/snapshot=2026-10-03/run=run-035e75abfa6798c938cbd534273dbd8d --out scratch/er/review-queue-wo5/repair-02 --rekey scratch/er/review-queue-wo5/review-queue-20261103-strata.csv"`
  - "[success] Total time: 116 s", exit 0.
  - Printed: "RESEARCHER rows 260 decision or path changed 0"; "WORK rows 50 decision or path changed 7"; "rows: 310 -> scratch/er/review-queue-wo5/repair-02/review-queue-20261103-strata-er-v2.csv".
- `python3 -I scratchpad/cmp_r3.py <wo11 er-v2 file> <repair-02 file>`: the row and pair identity asserts passed, and no DIFF lines were printed (results above).
- `shasum -a 256` and `ls -la` after the run: the existing files are unchanged.
- `gzcat ... | head` on the R2 suffix-conflicts/, conflicts/ and oversize-conflicts/ outputs and on the work order 10 matches/ output, to read the line formats.
- `python3 -I scratchpad/members_r3.py`: printed "keys 16 members 410 queued rows touching a member 0".

TEST RESULTS: not run, because no source changed and the work order says to run `sbt check` only if a source changes. The last observed `sbt check` results are R2's (core 151, entityResolution 94, hadoop 70, all passed).
VALIDATION RESULTS: 310 rows. Row IDs and pairs are identical to both earlier strata files. Policy version and run ID are each a single value. All existing files are byte-identical to before.

ASSUMPTIONS:
1. The output name review-queue-20261103-strata-er-v2.csv is the same as the work order 11 file's name. It is kept apart only by the repair-02/ directory, as the work order requires. To evaluate against R2, pass `--key scratch/er/review-queue-wo5/repair-02/review-queue-20261103-strata-er-v2.csv`.
2. "Changed decision or path compared with work order 11" compares the `decision` and `path` columns row by row, joined on row_id. I also checked every other column.

RISKS:
- Because no queued row touches a suffix-conflict group, the gold set gives no evidence about the R1 rule (`er-v2:orcid-group-homonym-suffix-conflict`) or the 3,594 new EXACT_ID CONFLICT decisions. Gold metrics for er-v2 at R2 will equal those for work order 11, apart from the run ID. Measuring the rule would need a targeted, human-approved supplementary sample from those groups.
- These are carried from work order 11: the er-v1 stratification leaves uneven er-v2 cells (WORK LLM_BAND has 3 rows), and R2's RISK 1 (the capped 152-mention group) still awaits a human ruling.

MANUAL ACTION REQUIRED: none to finish this work order. Gold evaluation is still pending the human's labels. The human also needs to rule on R2 RISK 1 and decide whether to commission a supplementary sample targeting the suffix-conflict groups.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: R4 code-reviewer review 2, then the gold-set evaluation with the repair-02 strata file, then match-policy review and code-reviewer APPROVE.

Not done: I did not run `sbt check` (no source change), did not run Evaluate, and did not touch labels/. Risk: this gold sample cannot measure the new ORCID suffix-conflict rule.
````
