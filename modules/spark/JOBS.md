# ScholarChain Spark jobs

Owner: spark-engineer. Code: `modules/spark/src/main/scala/scholarchain/spark/`. Tests: `modules/spark/src/test/scala/scholarchain/spark/`.

Spark 4.0.2 through `CrossVersion.for3Use2_13` (D-01, D-02). DataFrame and Java APIs only (no typed encoders, no GraphFrames, D-10). Spark reads Hadoop outputs only (modules/hadoop/JOBS.md) and never reparses raw data.

## CanonicalGraphJob (spec Phase 6 jobs 1 and 2)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.CanonicalGraphJob --input <hadoop run dir> --code-version <git sha> \
  [--out scratch/spark] [--shuffle-partitions 40] [--checkpoint-dir scratch/spark/checkpoints] [--attempt n]"
```

Settings: defaults in `src/main/resources/scholarchain-spark.conf`, each overridable by a flag of the same name. Windows, `graph.policy-version`, and analytics parameters come from the core configuration. Heap: `SCHOLARCHAIN_SPARK_HEAP` (build.sbt, default 6g).

### Inputs (under the Hadoop run directory)

`_manifest.json` (run ID, DBLP snapshot, `matchPolicyVersion`); J09 `author-mentions/`, `works/`, `coauthor-pair-candidates/`, `match-decisions/entity_type={RESEARCHER,WORK}/outcome={ACCEPTED,CONFLICT,UNRESOLVED}/`, `venues/`, `topics/`, `institutions/`, `graph-edges/edge_type={HAS_TOPIC,AFFILIATED_WITH}/`; J03+J04 `suffix-conflicts/` and `oversize-conflicts/`. REJECTED decisions, candidates, and publication teams are not read. Every input is read with an explicit schema in FAILFAST mode; an absent directory reads as empty.

### Canonical clustering (rulings 4 to 6)

- Only ACCEPTED decisions link records. Tier 1: connected components over EXACT_ID and DETERMINISTIC ACCEPTED edges. Tier 2: all ACCEPTED edges, computed on tier-1 components contracted to their roots. The match path comes from `rulesFired` (a copy of entity-resolution `MatchPolicy.pathOf`, `Inputs.scala` `MatchPath`).
- Researcher nodes are author mentions (all three sources) plus `orcid:<iD>` identifier keys that exact-ID matches point to. Keys link mentions but are not members.
- Refusal rules (codes in `Clustering.Rule`): `TWO_VALID_ORCIDS` (5a, ORCIDs from mentions and keys, checksum-valid), `CONFLICT_DECISION` (5b), `HOMONYM_SUFFIX_DISAGREEMENT` (5c, the `Normalize.splitHomonym` pattern on `rawName`), `IDENTIFIER_CONFLICT_GROUP` (5d, two or more mentions carrying the identifier of a J03+J04 suffix-conflict or RESEARCHER oversize-conflict group). Works: 5b, plus capped WORK oversize groups (two works of one source with the capped identifier).
- A refused tier-2 cluster falls back to its tier-1 clusters; a refused tier-1 cluster falls back to singletons. Rules are monotone, so a kept tier-2 cluster never contains a refused tier-1 cluster.
- Researcher ID: `scr:<canonical-policy-version>.<matchPolicyVersion>:H("researcher", policy, smallest member mention ID)` (StableIds.researcherId). Always `scr:` (never `orcid:`), so a refused ORCID cluster split into several researchers cannot produce duplicate IDs. Work ID: the smallest member work ID itself (a valid core work ID; the DBLP member whenever there is one, because `dblp:` sorts first).
- `assignedTier`: `TIER2` (its tier-2 cluster was kept; it may hold only tier-1 edges), `TIER1` (fell back to its tier-1 cluster), `SINGLETON` (no other member). `separationReason`: `REFUSED_TIER2`, `REFUSED_TIER1`, `UNRESOLVED` (alone and has an UNRESOLVED decision), `NO_ACCEPTED_LINK` (alone, no UNRESOLVED decision), or null.

### Connected components algorithm

Alternating large-star / small-star (Kiveris et al., SoCC 2014) over LONG keys (xxhash64 of the string ID; a collision fails the run). Stops when the edges form stars; the root is the component minimum. Every round is a reliable checkpoint under `<checkpoint-dir>/<runId>/`, deleted at the end of the run. Parameter `cc-max-iterations` (the run fails if not converged). Limitation: O(log^2 n) rounds, each a full pass over the current edges; hub nodes form large groups in the min aggregation.

### Coauthorship graph

Pair candidates are mapped to canonical researchers (mention map) and canonical works (work map). Pairs whose two mentions resolve to one researcher are self-pairs (written to `graph/self-pairs`, never edges). Other pairs are put in CanonicalPair order and aggregated per window (inclusive years, a pair counts in every window containing its year): `jointPublicationCount` = distinct canonical works, `first/lastCollaborationYear` = min/max pair year, `representativeWorkIds` = the complete sorted list of supporting canonical work IDs. `edgeId` = StableIds.coauthorEdgeId(A, B, start, end, graph policy).

### Outputs: `<out>/snapshot=<dblp snapshot>/run=<runId>/`

| Table | Grain | Notes |
|---|---|---|
| `canonical/mention-map` | mention | researcherId, sourceWorkId, sourceDataset, sourceRecordId (the J09 mention's source record), assignedTier, clusterMentionCount, separationReason, tier2/tier1RefusalRules, identityConfidence |
| `canonical/researchers` | researcher | displayName (anchor mention's raw name), alternateNames, orcid (exactly one valid iD, else null), mentionCount, sourceDatasets, dblpAnchored, assignedTier, identityConfidence (min confidence of accepted edges inside the cluster), uncertainty, provenance |
| `canonical/work-map` | source work | workId (canonical), sourceDataset, sourceRecordId, assignedTier, separationReason, refusal rules |
| `canonical/works` | canonical work | anchor title, normalizedTitle, venueId, workType; year and doi fall back to members; memberWorkIds, dblpAnchored |
| `canonical/authored` | (researcher, work) | mentionIds, position (of the smallest mention), year, sourceDatasets |
| `canonical/venues`, `topics`, `institutions` | entity | J09 records; their J09 provenance is kept as `sourceProvenance` |
| `canonical/work-topics` | (work, topic) | HAS_TOPIC mapped to canonical works |
| `canonical/researcher-institutions` | (researcher, institution) | via MENTION (mention institutionIds) and ORCID_EMPLOYMENT (AFFILIATED_WITH when exactly one researcher has the iD) |
| `canonical/researcher-refusals`, `work-refusals` | refused cluster and tier | rules, member and node counts, a sample member |
| `graph/coauthor-edges` | (window, pair) | partitioned by `window` |
| `graph/self-pairs` | pair candidate | dropped self-pairs |
| `integrity-violations` | violation | check, subjectId, relatedId, detail |

Run scaffolding (run ID, attempt, free-disk checks, table writes, manifest) is `JobRun.scala`, shared by every job. `_started.json` claims the attempt; `_stages.json` holds per-stage metrics; `_manifest.json` (`spark-run-manifest-v1`, written last) lists inputs with row counts, every table with path, schema, row count, write partitioning, and intended target stores, plus counts, step timings, stage summaries, and free disk before and after. Run ID: `StableIds.runId("spark-canonical-graph", code version, config version, [hadoop run, input size, canonical policy, graph policy, windows, cc-max-iterations], attempt)`; the attempt is the smallest unused one unless `--attempt` is given.

### Shuffle boundaries

Per clustering: node union and de-duplication, each connected-components round (two group-bys, two joins, the star check), tier-1-root contraction joins, refusal joins and group-bys, the final mapping joins. Then hash repartitioning on the key column before each Parquet write (`researcherId`, `workId`, `researcherA`), and one group-by per output table (researchers, works, authored, edges per window).

## ResearcherAnalyticsJob (spec Phase 6 jobs 3, 4, and 8)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.ResearcherAnalyticsJob --input <canonical-graph run dir> --code-version <git sha> \
  [--out scratch/spark] [--shuffle-partitions 40] [--checkpoint-dir scratch/spark/checkpoints] [--cc-max-iterations 60] [--attempt n]"
```

Input: one complete CanonicalGraphJob run directory (its `_manifest.json` must exist and name stage `spark-canonical-graph`), read-only: `canonical/researchers`, `canonical/authored`, `canonical/works` (member work IDs only, for the W_r check report), `graph/coauthor-edges`. The configured `graph.windows` must equal the input run's windows, or the run fails. Output: `<out>/snapshot=<snapshot>/run=<runId>/` (JobRun). Run ID: `StableIds.runId("spark-researcher-analytics", code version, config version, [canonical-graph run, input size, graph policy, windows, cc-max-iterations, kcore-max-iterations], attempt)`.

### The graph of a window

Nodes: researchers with at least one DBLP publication in the window (the rows of job 3). Edges: that window's `graph/coauthor-edges`, unweighted for jobs 4 and 8. Researchers with only OpenAlex or Crossref authorship are not in the coauthorship graph (job 2 builds it from DBLP pairs), so they get no metric row. A researcher with only single-author papers is a node with no edge: a singleton component with coreness 0.

### Job 3: collaboration breadth and team size (`ResearcherStats.scala`)

Basis: DBLP authorship, the same basis as the coauthor edges. An AUTHORED row (researcher, canonical work) counts when one of its mentions is a DBLP mention (`sourceDatasets` contains `DBLP`). team(w) is the number of distinct canonical researchers with a DBLP mention on canonical work w. A work is in a window when its canonical year is in the window (inclusive); a null year is in no window.

| Measure | Column | Definition (researcher r, window) |
|---|---|---|
| P_r | `publicationCount` | distinct canonical works of r in the window |
| C_r | `distinctCoauthorCount` | number of coauthor edges of r in the window (distinct canonical coauthors) |
| T_r | `totalCoauthorOccurrences` | sum over r's works of (team(w) - 1) |
| A_r | `averageCoauthorsPerPaper` | T_r / P_r |
| M_r | `maximumCoauthorsOnOnePaper` | max over r's works of (team(w) - 1) |
| M_r works | `maximumTeamWorkIds` | every canonical work of r in the window with team(w) - 1 = M_r, sorted (the complete list) |
| W_r | `weightedDegree` | sum of `jointPublicationCount` over r's coauthor edges in the window |

The six measures are stored as separate columns and never combined. C_r is breadth; T_r and W_r are intensity; M_r flags large teams. Self-pairs are not coauthors: team(w) counts distinct canonical researchers.

Identity: W_r = T_r whenever edges and teams come from the same authorship, because each work shared with a coauthor adds one to both sums. The job checks it per row (`WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES`). It can fail only where the two bases differ: a canonical work that merges two DBLP works (job 2 pairs authors only within one DBLP work, team(w) counts across both), or a pair year that differs from the canonical work year. The same cause can make coreness < M_r (`CORENESS_BELOW_MAX_TEAM`), which otherwise cannot happen.

### Job 4: connected components (`MinLabelComponents.scala`)

- Definition: iterative min-label propagation. Every node starts with its own researcher ID as label. Each round, every node whose label dropped in the previous round (the frontier; all nodes in round 1) sends its label to its neighbours, and each node keeps the smallest label seen. The loop stops after a round with no change. The final label (`componentRoot`) is the smallest researcher ID in the component. `componentId` = `comp-` + H("component", graph policy, window, start year, end year, root), stable across reruns of the same input. All windows run in one loop keyed by (window, node).
- Parameters: `cc-max-iterations` (the run fails if not converged). Labels and frontier are reliable checkpoints every round under `<checkpoint-dir>/<runId>/`.
- Approximation: none.
- Limitations: rounds = the largest distance from any node to its component minimum plus one confirming round (more rounds than large-star/small-star on long-diameter components; the work order asked for min-label propagation). The frontier keeps later rounds cheap. Hub nodes produce large min-aggregation groups.
- Outputs: `componentId` and `componentSize` per researcher and window (in researcher-metrics) and one row per component (`analytics/components`). The manifest reports components, the largest component's size and fraction of the window's researchers, singleton researchers, a size histogram, the three largest components, and labels changed per round.
- Test: two components plus an isolated node in one window, another window over the same IDs, labels changed per round (5, 1, 0), and the iteration cap.

### Job 8: k-core coreness (`KCore.scala`)

- Definition: the k-core is the largest subgraph in which every node has degree at least k; the coreness of a node is the largest k whose k-core contains it. Simple graph (edge weights ignored); an isolated node has coreness 0.
- Algorithm (iterative peeling): keep each unpeeled node's residual degree. Each round sets k = max(k, smallest residual degree), peels every unpeeled node with residual degree <= k (coreness k), removes its edges, and recomputes residual degrees. k never decreases and only rises when no unpeeled node is at or below it, so a node peeled at level k is in the k-core and not in the (k+1)-core. Jumping to the smallest residual degree skips empty levels. All windows run together; the shared k is still correct, because a window with no node at or below k peels nothing that round.
- Parameters: `analytics.kcore-max-iterations` from the core configuration, used as the maximum number of peeling rounds within one level (the run fails beyond it). State and remaining edges are reliable checkpoints every round.
- Approximation: none (exact coreness).
- Limitations: rounds = the sum over coreness levels of the peeling depth at that level. Giant author teams form cliques with high coreness, so the graph has many distinct levels, and each round is a pass over the remaining edges. Coreness ignores edge weight and repeated collaboration.
- Outputs: `coreness` and `graphDegree` per researcher and window; the manifest reports the coreness histogram, the highest-coreness researchers, rounds, levels, and every round as (k, nodes peeled).
- Test: a 4-clique attached to a path plus an isolated node, and a triangle with a pendant node in a second window (coreness and the round log worked out by hand), and the per-level cap.

### Outputs

| Table | Grain | Notes |
|---|---|---|
| `analytics/researcher-metrics` | (window, researcher) | partitioned by `window`; core `ResearcherMetrics` field names: P_r to W_r, `maximumTeamWorkIds`, `componentId`, `componentSize`, `coreness`, plus `graphDegree`, window columns, provenance (DERIVED, graph policy). Community membership and bridge measures are in CommunityPathJob's `analytics/researcher-communities` (same window and researcher key). |
| `analytics/components` | (window, component) | partitioned by `window`; `componentId`, `componentRoot`, `componentSize` |
| `analytics/integrity-violations` | violation | `METRIC_RESEARCHER_MISSING`, `EDGE_ENDPOINT_WITHOUT_PUBLICATION`, `WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES` (W_r != T_r), `CORENESS_BELOW_MAX_TEAM` (coreness < M_r; a team of n distinct researchers is an (n-1)-clique, so coreness >= M_r), `METRIC_INVARIANT` (C_r > T_r, M_r > T_r, A_r > M_r, graph degree != C_r, coreness > degree, a missing component or coreness, an empty M_r work list). The manifest's `mergedDblpWorkChecks` counts, per check, how many `WEIGHTED_DEGREE_NE_TOTAL_OCCURRENCES` and `CORENESS_BELOW_MAX_TEAM` rows belong to researchers with a publication in the window on a canonical work with several DBLP members (team(w) counts authors across the merged DBLP records; job 2 pairs authors only within one record, so neither identity holds there). |

### Shuffle boundaries

Job 3: the team-size group-by on `workId` and its join back, the group-by on (window, researcher), the degree group-by over edges, the join on (window, researcherId). Job 4, per round: the frontier-edge join on (window, src), the min group-by on (window, node), the old/new label join. Job 8, per round: two anti-joins of the remaining edges against the peeled set, the residual-degree group-by, the state join. Then the component-size group-by, the membership and coreness joins, and a hash repartition on `researcherId` before the metrics write.

## CommunityPathJob (spec Phase 6 jobs 5, 6, and 7)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.CommunityPathJob --input <researcher-analytics run dir> --code-version <git sha> \
  [--pairs <csv file>] [--out scratch/spark] [--shuffle-partitions 40] [--community-max-iterations 10] [--path-max-depth 6] \
  [--path-sample-pairs 50] [--path-sample-sources 20] [--sample-seed 20261003] [--cc-max-iterations 60] [--attempt n]"
```

Input: one complete ResearcherAnalyticsJob run directory, read-only: `analytics/researcher-metrics` (the nodes of each window, their `componentId` and `graphDegree`) and `analytics/components`. Its manifest's `input.path` names the CanonicalGraphJob run, from which `graph/coauthor-edges` and `canonical/researchers` are read; the run ID there must match. The configured `graph.windows` must equal the input run's windows. Settings come from `scholarchain-spark.conf` (the core `analytics.community-max-iterations` = 20 and `retrieval.max-path-depth` are not read; ruling 15 puts Spark job settings here, and ruling 8 sets the default cap to 10). Run ID: `StableIds.runId("spark-community-path", code version, config version, [researcher-analytics run, canonical-graph run, both input sizes, graph policy, windows, community-max-iterations, path-max-depth, path-sample-sources, sample-seed, cc-max-iterations, pairs], attempt)`, where pairs is `sample:<n>` or `file:<text hash>`.

The graph of a window is the one jobs 3, 4, and 8 use: nodes = researchers with a DBLP publication in the window, edges = that window's coauthor edges, unweighted. All windows run in one loop keyed by window, so windows never mix.

### Job 5: bounded path evidence (`ShortestPaths.scala`)

- Pairs: `--pairs <csv>` with the header `window,sourceResearcherId,targetResearcherId` (each window a configured window name, each ID a core researcher ID; duplicate lines dropped; a malformed file fails the run). Without it, a seeded sample: the members of the largest component of the first configured window (`all-years`, which holds the largest component of any window) are ranked by H(seed, "path-pair", window, researcherId), and ranks 1-2, 3-4, ... form `path-sample-pairs` pairs (distinct ends). Either way the pairs are written to `<run dir>/paths/selected-pairs.csv`. To add the benchmark pairs later, rerun only this job with `--pairs`.
- Definition: the shortest path between two researchers is the smallest number of coauthor edges (hops) joining them in the window's graph. Status: `FOUND` (path of at most `path-max-depth` hops), `UNREACHABLE` (the two researchers are in different connected components, from `componentId`, so there is no path at any depth), `BEYOND_DEPTH` (same component, but the shortest path is longer than the limit), `UNKNOWN_RESEARCHER` (an end is not a node of the window). A pair with the same researcher at both ends is `FOUND` with length 0.
- Algorithm: multi-source breadth-first search from the sources of the pairs in one component, as DataFrame iteration. Round d+1 sends every frontier node at distance d to its neighbours, drops nodes the source already reached, and keeps for each newly reached node the smallest neighbour at distance d as its parent. A source stops expanding once every target of its pairs is reached; the search stops after `path-max-depth` rounds or when nothing new is reached. The path is read back from the target through parents (one broadcast join per hop), so it is a shortest path, and the same input always gives the same path: at each step back it takes the smallest predecessor. Each hop is joined to its canonical coauthor edge: `edgeId`, `jointPublicationCount`, first and last year, and `supportingWorkIds` (the edge's complete `representativeWorkIds`).
- Parameters: `path-max-depth` (ruling 11: 6), `path-sample-pairs` (50), `sample-seed`. Visited and frontier are reliable checkpoints every round.
- Approximation: none. Paths are exact shortest paths within the depth limit; one path is reported when several tie.
- Limitations: only one shortest path per pair is kept (others of equal length are not listed). Cost grows with the size of the depth-d ball around each source: hubs and the 287-author team clique put most of the largest component within 5 or 6 hops, so a far pair visits most of the component. Edge weights are ignored (fewest hops, not the strongest collaborations).

### Job 5: sampled average path length and effective diameter

- Method: in each window's largest component, the members ranked first by H(seed, "bfs-source", window, researcherId) are the `path-sample-sources` sources (20). An exact, unbounded BFS from each gives its distance to every other member of the component (the BFS must finish within `cc-max-iterations` rounds, or the run fails). Over the sampled (source, node) pairs: `meanDistance` (average path length), `meanDistanceStdError` (sample standard deviation of the per-source mean distances divided by the square root of the number of sources), `medianDistance` and `effectiveDiameter90` (the smallest d with at least 50% and 90% of the sampled pairs within d hops), `maxObservedDistance` (the largest eccentricity among the sources, a lower bound on the diameter), and the distance histogram.
- Approximation: sampling of sources only; every distance from a sampled source is exact. With sources drawn uniformly at random from the component, the mean over sampled pairs is an unbiased estimate of the mean over all ordered pairs. The percentiles are estimates, and the true diameter can exceed `maxObservedDistance`.
- Limitations: 20 sources is a small sample (the standard error is reported); the hash ranking stands in for a uniform random draw. Only the largest component is measured; distances between components are infinite and are not averaged. D-10 deferred effective-diameter estimation; ruling 11 asks for it, and this is the sampled form.

### Job 6: communities by label propagation (`Communities.scala`, `LabelPropagation`)

- Definition (Raghavan, Albert, and Kumara 2007, synchronous form): every node starts with its own researcher ID as label. In each round every node takes the label held by the most of its neighbours and itself, with a tie going to the smallest label (ruling 8). All nodes update together from the previous round's labels, so the result does not depend on partitioning or row order. A community is the set of nodes with one final label. A researcher without coauthors keeps its own label (a community of one).
- The node's own vote: plain synchronous label propagation can swap labels forever on a pair or any bipartite pattern (each end takes the other's label). Counting the node's own label as one vote makes a pair settle on its smaller label.
- Parameters: `community-max-iterations` (ruling 8: 10). The loop stops after a round with no change; reaching the cap is not a failure. The last labels are kept and the window is reported as not converged. Labels changed per round and window are in the manifest. Labels are reliable checkpoints every round.
- `communityId` = `comm-` + H("community", graph policy, window, start year, end year, final label), stable across reruns of the same input. `communityLabel` is the researcher ID whose label won.
- Approximation: none (exact for the stated rule), but label propagation does not optimise any quality measure such as modularity.
- Limitations: the result depends on the tie-break. The smallest-label rule favours lexicographically small IDs, so a community's label carries no meaning. Synchronous updates converge more slowly than asynchronous ones and can stop at the cap without converging. The method can merge weakly linked groups through hubs, and it splits nothing it cannot see: edges are unweighted, and repeated collaboration does not count more. Communities never cross connected components. Results are not comparable across windows community by community, because each window is labelled on its own.

### Job 7: bridge measures (`Communities.scala`, `Bridges`)

For researcher r in a window, with k_r distinct coauthors (graph degree, equal to C_r) and k_rs of them in community s:

| Measure | Column | Definition | Range |
|---|---|---|---|
| Cross-community degree | `crossCommunityDegree` | number of r's coauthors whose community differs from r's | 0 to k_r |
| Communities connected | `communitiesConnected` | number of distinct communities among r's coauthors (r's own counts when a coauthor is in it) | 0 to k_r; 0 only for k_r = 0 |
| Participation coefficient (Guimera and Amaral 2005) | `participationCoefficient` | 1 - sum over s of (k_rs / k_r)^2 | 0 to 1 - 1/communitiesConnected; 0 when every coauthor is in one community; 0 by convention for k_r = 0 |

- Approximation: none (exact given the communities). Approximate betweenness is deferred (D-10).
- Limitations, all three:
  - The measures inherit every limitation of the community assignment. A different tie-break, iteration cap, or window gives different communities and so different bridge values.
  - Edges are unweighted: one joint paper counts as much as fifty.
  - They are local (one hop). A researcher whose coauthors sit in different communities scores high even if those communities are also joined elsewhere. Betweenness would capture global brokerage; these measures do not.
- Cross-community degree grows with degree, so hubs dominate it.
- Communities connected ignores how the coauthors are spread across communities: one coauthor in a second community counts as much as half of them.
- The participation coefficient is size-independent but noisy at low degree. A researcher with 2 coauthors in 2 communities scores 0.5, the same as one with 200 coauthors split evenly. Read it together with `graphDegree`.

### Outputs: `<out>/snapshot=<snapshot>/run=<runId>/`

| Table | Grain | Notes |
|---|---|---|
| `paths/selected-pairs.csv` | pair | the pairs searched (CSV, header `window,sourceResearcherId,targetResearcherId`) |
| `paths/path-evidence` | (window, pair) | partitioned by `window`; `pairId` = `pair-` + H("path-pair", graph policy, window, start, end, source, target); `status`, `pathLength`, `maxDepth`, `componentId` (when both ends share one), `researcherIds` (source to target), `hops` (array of hop, from, to, `edgeId`, `jointPublicationCount`, first/last year, `supportingWorkIds`), provenance |
| `paths/path-length-estimate` | window | largest component, sampling parameters and sources, mean, standard error, median, effective diameter (90%), max observed distance, histogram |
| `analytics/communities` | (window, community) | partitioned by `window`; `communityId`, `communityLabel`, `communitySize`, `internalEdges`, `crossCommunityEdges` (edges with one end in the community) |
| `analytics/cross-community-edges` | (window, edge) | partitioned by `window`; coauthor edges whose ends are in different communities, with both `communityId`s |
| `analytics/researcher-communities` | (window, researcher) | partitioned by `window`; membership (`communityId`, `communitySize`) and the three bridge measures, plus `graphDegree`; core `ResearcherMetrics` field names |
| `analytics/community-path-violations` | violation | `COMMUNITY_RESEARCHER_MISMATCH` (a researcher-metrics row without a community row or the reverse, or a degree that differs from researcher-metrics `graphDegree`), `BRIDGE_INVARIANT` (a measure outside its range), `PATH_HOP_EDGE_MISSING` (a hop with no coauthor edge), `PATH_LENGTH_MISMATCH` (hops or researcherIds disagree with pathLength, or pathLength > maxDepth) |

The manifest's `counts` hold path status counts and the path-length histogram per window, BFS nodes reached per round, the path-length estimate, community counts, size histogram and largest communities per window, convergence (rounds, labels changed per round and window, converged per window), bridge distributions (min, median, p90, p99, max), researchers with a cross-community edge, the top five researchers by participation coefficient and by cross-community degree, and violations by check.

### Shuffle boundaries

- Job 5 paths, per BFS round: the frontier-edge join on (window, node), the anti-join against visited on (window, source, node), and the min-parent group-by. The pending-target filter is a broadcast semi-join. The walk back is one broadcast join per hop (the visited table is not shuffled), and the hops are joined to edges on (window, researcherA, researcherB) and grouped by pair.
- Job 5 estimate: the same BFS rounds, then the histogram group-by on (window, dist) and per-source means.
- Job 6, per round: the edge-label join on (window, src), the vote count group-by on (window, node, label), the winner group-by on (window, node), and the old/new label join.
- Job 7: the neighbour-label join on (window, dst), the group-by on (window, node, community), and the group-by on (window, node). Communities: the two endpoint-label joins on (window, researcherA) and (window, researcherB), and the group-bys on (window, label).
- Writes: a hash repartition on `researcherId` for researcher-communities; one partition per window for the smaller tables.

## TemporalSummaryExportJob (spec Phase 6 jobs 9, 10, and 11)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.TemporalSummaryExportJob --input <community-path run dir> --code-version <git sha> \
  [--out scratch/spark] [--shuffle-partitions 40] [--summary-min-publications 2] [--summary-min-community-size 3] [--attempt n]"
```

Input: one complete CommunityPathJob run directory, read-only. Its manifest names the ResearcherAnalyticsJob run (`input.path`) and the CanonicalGraphJob run (`input.canonicalGraphPath`); the three run IDs must form one chain, or the run fails. Tables read: researcher-metrics and components (jobs 3, 4, 8); researcher-communities, communities, cross-community-edges, path-evidence (jobs 5 to 7); researchers, authored, works, venues, topics, work-topics, researcher-institutions, institutions, coauthor-edges (jobs 1, 2). A table whose manifest rowCount is 0 (an empty partitioned table has no files) is read as empty with its recorded schema. No iterative algorithm, so no checkpoints. Run ID: `StableIds.runId("spark-temporal-summary-export", code version, config version, [the three input runs and their sizes, graph policy, windows, summary-min-publications, summary-min-community-size, template version], attempt)`.

DBLP authorship per window (`dblpWorks`): AUTHORED rows with a DBLP mention, joined to their canonical work (title, venue), in every window that contains the AUTHORED year. It is the basis of jobs 3 to 8, so publication counts here equal theirs.

### Job 9: temporal comparison (`Temporal.scala`)

- Windows compared: every ordered pair of configured windows that do not overlap, earlier first. With D-10 that is `2000-2014 -> 2015-2026`. `all-years` contains both and is only summarised, never compared, because a researcher's two rows would share publications.
- A researcher is in a window when it has a DBLP publication there (a researcher-metrics row). Identity is resolved once (job 1), so the same canonical researcherId is compared across windows.
- `temporal/researcher-changes`, one row per (comparison, researcher of either window):
  - `presence`: `BOTH`, `FROM_ONLY`, `TO_ONLY`.
  - `from<X>` and `to<X>` for P_r, C_r, A_r, M_r, coreness, component size, `inLargestComponent`, communityId, community size, and the three bridge measures.
  - `delta<X>` = to - from for C_r, A_r, M_r, coreness, cross-community degree, and participation coefficient (null unless `BOTH`).
  - Distinct coauthors: `retainedCoauthors` = coauthors with an edge in both windows (null unless `BOTH`).
  - Component membership: component IDs are per window and never compared. `largestComponentTransition` is `IN`, `OUT`, or `ABSENT` in each window (for example `OUT->IN`).
  - Community membership: community IDs are per window and never compared. `communityRetention` = (s - 1) / (n - 1), where n is the size of r's earlier community and s counts the researchers present in both windows who are in r's earlier community and in r's later community (r included). It is the share of r's earlier community co-members who stay with r. It is null unless `BOTH`, and null when the earlier community has one member.
  - Bridge behaviour: `crossCommunityTransition` is `CROSS` (crossCommunityDegree > 0), `NO_CROSS`, or `ABSENT` in each window, plus the deltas.
- `temporal/community-transitions`: (from community, to community, shared researchers, both sizes), over researchers in both windows. It is the input of `communityRetention`, kept so that value can be checked.
- `temporal/window-comparison`, one row per comparison: researchers from, to, in both, from-only, to-only; researchers keeping a coauthor; mean and median community retention; the four largest-component transitions; became, stopped, and stayed cross-community; mean from/to values of the compared measures over researchers in both windows; and `inclusionLimitation` (text below).
- `temporal/window-summary`, one row per window, all three windows: publications, `years`, publications per year, researchers, coauthor edges, sum of joint publications, components, largest component and its fraction, singleton researchers, communities, largest community, communities of at least the summary size, mean and median C_r, mean A_r, mean and maximum M_r, mean and maximum coreness, mean and median participation coefficient, and researchers with a cross-community edge (count and fraction).
- Limitations:
  - Inclusion. The windows share one identity resolution and one graph definition, but the DBLP subset (D-10, about 100k works) is a bounded selection. Its coverage of venues and years, and the OpenAlex, Crossref, and ORCID enrichment driven by it, can differ between periods. The windows have different lengths (15 and 12 years), and the last year may be incomplete. The comparison mixes real change with these coverage differences. `publicationsPerYear` is reported so differences in length are visible.
  - Communities are computed separately per window by capped label propagation (job 6, not converged at 10 rounds on the full data). Retention measures stability of a researcher's neighbourhood, not identity of a community.
  - A researcher present in only one window has no deltas. Entry and exit are counted, not explained.
- Shuffles: the full outer join of the two windows on researcherId, the edge join on (researcherA, researcherB) and its group-by for retained coauthors, the group-by on (from community, to community) and its join back. The window summary is six group-bys on `window` joined to the windows table.

### Job 10: semantic-context summaries (`Summaries.scala`)

Template text only, from computed tables and source records (ruling 12; no LLM). The `facts` column (JSON) holds every value the text uses: the structured summary inputs. `text` is a deterministic rendering of those facts under `templateVersion` = `summary-template-v1`. The rag-engineer owns the rendering templates and the embedding work, so this text may be replaced; the facts stay the input.

| documentType | Subjects | Facts and text |
|---|---|---|
| `researcher-profile` | researchers with at least `summary-min-publications` (2) publications in the first configured window (`all-years`; ruling 7 calls it "all") | P_r, C_r, T_r, A_r, M_r, W_r, coreness, component (size, whether largest), community size and the three bridge measures, first and last year, top 3 coauthors by joint publications, top 3 topics and venues by works, top 3 institutions on record (by evidence count; not windowed), 3 most recent works |
| `collaboration-community` | communities with at least `summary-min-community-size` (3) members, every window | size, internal edges, density = internal / (n(n-1)/2), cross-community edges, linked communities, members' publications in the window, top 5 members by degree, top 3 topics and venues |
| `collaboration-path` | every path-evidence row (the selected pairs) | status, length, the path with each hop's joint publications, years, and the first supporting work title; or the reason no path is given (`BEYOND_DEPTH`, `UNREACHABLE`, `UNKNOWN_RESEARCHER`). The facts also carry `sourceName` and `targetName` (display name, or the ID when null; Phase 7 work order 6, required by rag rendering-v1). The Phase 6 run's path facts predate this and lack them. |
| `topic-summary` | every topic with at least one DBLP-authored work in the window, every window | name, field, subfield, works, researchers, top 3 researchers and venues |

Selected paper summaries are not produced. Work records carry only `abstractTextRef`, an S3 pointer (core WorkRecord; the text is never inlined), which Spark does not read, and work order 5 asks for topics.

- Lists: top N with explicit orderings (count descending, then name, then ID), so the same input gives the same text.
- `summaryId` = StableIds.chunkId(`<subjectId>|<window>`, templateVersion, documentType, no ordinal, textHash); `textHash` = StableIds.textHash(text) (`sha256:` + hex).
- `supportingIds`: every record ID the text names (researchers, edges, works, topics, venues, institutions, the community, component, or pair).
- `vectorIndex`: the core `s3-vectors.indexes` name for the document type.
- Table: `summaries/semantic-summaries`, partitioned by `documentType` and `window`, hash-repartitioned on `subjectId` (2 partitions). Targets: s3vectors, s3.
- Limitations: the text is fixed-phrase English with no pluralisation; counts in it are as exact as the tables they come from. Institutions are not windowed (mention affiliations and ORCID employment carry no reliable year here). Topics come from OpenAlex HAS_TOPIC edges, so works without an OpenAlex match have none.
- Shuffles: for each top-N list, a `row_number` window on the subject key and a `collect_list` group-by; then left joins on the subject key; the topic and venue counts are group-bys on (subject, topic or venue).

### Job 11: export and load manifest

- The Parquet tables are already partitioned and written by each stage. The export does not copy them. `export/load-manifest.json` (`spark-export-manifest-v1`) lists every table of the four runs: logical name in the spec's layout (`nodes/type=researcher`, `edges/type=coauthored_with`, `metrics/type=bridges`, `path-evidence`, `semantic-summaries`, ...), producing stage and run ID, path, schema DDL, partition columns, manifest rowCount, and `verifiedRowCount`, which is re-read from Parquet. Then, per store (`neptune`, `dynamodb`, `s3vectors`, `s3`), it lists the logical names of the tables whose intended targets include that store.
- k-core coreness and component membership are columns of `metrics/type=researcher_collaboration+kcore` (researcher-metrics), not separate tables.
- Store-specific formats (Neptune bulk-load CSV, DynamoDB items, S3 Vectors records) are Phase 7+8 (ruling 13).

### Outputs

| Table | Grain | Targets |
|---|---|---|
| `temporal/window-summary` | window | dynamodb, s3 |
| `temporal/window-comparison` | comparison | dynamodb, s3 |
| `temporal/researcher-changes` | (comparison, researcher) | dynamodb, s3 |
| `temporal/community-transitions` | (comparison, from community, to community) | s3 |
| `summaries/semantic-summaries` | (documentType, subject, window) | s3vectors, s3 |
| `summary-export-violations` | violation | s3 |
| `export/load-manifest.json` | JSON | Phase 7 loaders |

Checks in `summary-export-violations`:
- `SUMMARY_MISSING`: an expected profile, community, or path subject has no summary.
- `SUMMARY_TEXT_EMPTY`
- `TEMPORAL_PRESENCE_MISMATCH`: the from or to researcher count of a comparison differs from that window's researcher-metrics rows.
- `EXPORT_ROWCOUNT_MISMATCH`: a table's re-read row count differs from its manifest.

## PipelineJob (the end-to-end driver, jobs 1 to 11)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.PipelineJob --input <hadoop run dir> --code-version <git sha> \
  [--pairs <csv>] [--out scratch/spark] [--shuffle-partitions 40] [--attempt n]"
```

- One SparkSession runs CanonicalGraphJob, ResearcherAnalyticsJob, CommunityPathJob, and TemporalSummaryExportJob in order. Each feeds the next its run directory. The cache is cleared before each stage, so every stage starts as a standalone run does. Without this, inputs left cached by earlier stages held storage memory, and job 10's sorts failed with `UNABLE_TO_ACQUIRE_MEMORY` (work order 5, first attempt).
- Each stage writes its own JobRun directory with its own deterministic run ID (smallest unused attempt).
- The pipeline run directory `<out>/snapshot=<snapshot>/run=<pipeline runId>/` holds `_manifest.json`. It lists the four stage runs (run ID, attempt, directory, wall time, stage count, table count, violations by check), the load manifest path, the per-window counts (`perWindow`, from `temporal/window-summary`), and the summary counts. `_stages.json` covers every Spark stage of the whole run.
- Pipeline run ID: `StableIds.runId("spark-pipeline", code version, config version, [Hadoop run, input size, every algorithm parameter, pairs], attempt)`. `--attempt` applies to the pipeline run only.
- `lineage` (pipeline and every stage manifest): `inputHadoopRunId` and `matchPolicyVersion` of the Hadoop run; `codeVersion` is in every manifest head.
- Determinism: the same input, code version, and configuration give the same stage tables and counts. CanonicalGraphJobSpec and TemporalSummarySpec check reruns on fixtures. Work order 5 compared a full local run with the staged runs of work orders 2 to 4.

## BenchmarkPathJob (Phase 7 rulings 25 and 30: benchmark paths)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.BenchmarkPathJob --input <community-path run dir> \
  --benchmark experiments/benchmark/benchmark-questions-v1.csv [--dominant-share 0.9] \
  [--dominant-min-works 5] --code-version <v> --out scratch/stores/spark \
  --checkpoint-dir scratch/stores/checkpoints"
```

A bounded rerun of job 5 (path evidence) and the path part of job 10 for the union of the input run's seeded pairs (`paths/selected-pairs.csv`) and the benchmark pairs. The Phase 6 outputs are only read. Run ID: `StableIds.runId("spark-benchmark-paths", code version, config version, [community-path run, the three input sizes, graph policy, windows, path-max-depth, summary template, benchmark text hash, resolution rule with its share and min-works], attempt)`.

- Benchmark pairs: questions whose `researcher_a` and `researcher_b` are both non-blank (the CSV is read with Spark's CSV reader, quoted fields allowed). Window `all` is the first configured window (`all-years`); any other window must be configured.
- Name resolution: rule `dominant-name-v1` (below). A pair is searched only when both names are `RESOLVED_DOMINANT`; otherwise its status is `UNKNOWN_RESEARCHER` (if either name is unknown) or `AMBIGUOUS`, and it has no path-evidence row and no summary. The candidates are cut to the benchmark names (a broadcast semi-join) before ranking; one row per name reaches the driver.

### Entity-linking rule `dominant-name-v1` (Phase 7 ruling 30; for adoption by the Phase 9 runtime)

A general rule for linking a printed researcher name to one canonical researcher. It is not specific to the benchmark, and it changes no Phase 5 or 6 clustering.

- Normalization (ruling 25): the given name is trimmed of leading and trailing spaces (Spark `trim`) and compared exactly, case and accents included, with `canonical/researchers.displayName`. `Ann Lee` does not match `Ann Lee 0001`. Alternate names are not candidates (work order 6's `exact-name-v1` also matched `alternateNames`; ruling 30 names the display name only).
- Candidates: every canonical researcher with that display name. Each candidate's works are its rows in `canonical/authored` (one row per researcher and work, so the AUTHORED edges). Candidates are ranked by works descending, then researcherId ascending.
- Decision, with share `s` = 0.9 and minimum `m` = 5 (job flags `--dominant-share`, `--dominant-min-works`; recorded in the manifest settings and the run-ID fingerprint; `s` must be above 0.5 so at most one candidate can dominate):
  - no candidate: `UNKNOWN_RESEARCHER`;
  - the top candidate holds at least `s` of the works summed over all candidates, and at least `m` works: `RESOLVED_DOMINANT` to it, recording its share and the candidate count;
  - otherwise `AMBIGUOUS` (this includes a single candidate with fewer than `m` works, a literal reading of ruling 30, and any tie, whose share is at most 0.5).
- Output per name: candidate count, total works, top candidate ID and works, top share (null when the candidates hold no works), the first 10 candidates in rank order, decision. `BenchmarkPathJob.dominance` computes it for any set of names in one window shuffle (partitioned by name) and one group-by; nothing is collected.
- Limitations: a dominant share is evidence of a split identity (unmerged singletons sharing a well-known researcher's printed name), not proof. A different person with few works under the same name is linked to the dominant researcher. The rule ignores time, venue, coauthors, and ORCID, and a name printed differently from the anchor mention's display name is not found. Runtime answers that use it should say the name was linked by dominance and give the share.
- Corpus-wide scale (work order 6b run `run-ee4ffd21aa20dbe4c078a4be96fe8c9f`, manifest `counts.splitIdentities`): 161,077 distinct display names over 259,057 researchers. 18,055 names have more than one researcher (116,035 researchers). Of those, 1,080 have a dominant candidate; their 1,893 non-dominant candidates hold 2,350 mentions and 1,970 authored rows (researcher-work pairs), against 107,374 mentions and 39,704 authored rows of the 1,080 dominant ones. The other 16,975 shared names stay ambiguous. The largest dominant name has 17 candidates.
- Outputs (JobRun layout under `--out`): `paths/benchmark-pairs` (one row per question: both names, their statuses, IDs, candidate counts, top shares, top and total works, samples, rule, pair status, `pairId`), `paths/selected-pairs.csv`, `paths/path-evidence` (job 5 columns), `summaries/path-summaries` (the job 10 `collaboration-path` rows with `vectorIndex` and provenance), `paths/benchmark-path-violations` (`PATH_HOP_EDGE_MISSING`, `PATH_LENGTH_MISMATCH`, `SUMMARY_MISSING`, and `SEEDED_PATH_CHANGED`: a seeded pair whose status, length, or researcher sequence differs from the input run; job 5 is deterministic, so this must stay empty).
- The manifest's `counts.splitIdentities` reports the corpus-wide scale of split identities under the rule (one aggregate row; report only).
- Shuffle boundaries: the job 5 BFS rounds (see job 5); the name match (a broadcast semi-join of the benchmark names against `canonical/researchers`, a group-by of `canonical/authored` on researcherId, a window partitioned by name, a group-by on name); the corpus-wide split-identity count (the same group-by, window, and group-by over every display name, then one global aggregate); the summary joins of job 10; the violation joins (broadcast on the seeded pair IDs).
- Results: work order 6 (`exact-name-v1`) resolved 0 of 8 questions. Work order 6b (`dominant-name-v1`, run `run-ee4ffd21aa20dbe4c078a4be96fe8c9f`) resolves B07, B08, B10, B11, B29, B30 (4 distinct pairs, all FOUND); B09 (`Lei Zhang`, `Wei Wang`) stays AMBIGUOUS and B12 UNKNOWN_RESEARCHER.

## StoreConversionJob (Phase 7 work order 6: store-ready files)

```text
sbt "sparkAnalytics/runMain scholarchain.spark.StoreConversionJob --input <temporal-summary-export run dir> \
  --paths <benchmark path run dir> --stores-root scratch/stores/s3 --code-version <v> \
  --out scratch/stores/spark --checkpoint-dir scratch/stores/checkpoints"
```

Reads every Phase 6 table through the export's `export/load-manifest.json` (row counts checked against it) and the path run's `paths/path-evidence` and `summaries/path-summaries`; the path run must belong to the same chain. Writes the files of modules/storage/STORAGE.md under `--stores-root` (the local S3 fake), every key `<prefix>/<artifact>/snapshot=<d>/run=<this run>/...`, write-once (an existing run prefix stops the run). Run ID: `StableIds.runId("store-conversion", code version, config version, [export run, path run and size, windows, selection policy and seed, inline-evidence max, ranking size, every input table with its run and row count], attempt)`. The JobRun directory under `--out` holds `_manifest.json` (counts, timings, free disk) and the Parquet audit table `rankings/researcher-rankings`.

| Output (key under the store root) | Built from | Rows per file family |
|---|---|---|
| `graph/neptune/.../vertices/{researcher,work,venue,institution,topic,community}/part-NNNNN.csv` | canonical researchers, works, venues, institutions, topics; analytics/communities (all windows) | one row per entity |
| `graph/neptune/.../edges/{authored,coauthored-with,published-in,affiliated-with,has-topic,member-of}/part-NNNNN.csv` | canonical authored, graph/coauthor-edges, works with a venue, researcher-institutions, work-topics, analytics/researcher-communities | one row per edge; COAUTHORED_WITH one per pair and window |
| `curated/coauthor-evidence/.../window=<w>/<edgeId>.json` | coauthor edges with more than 20 joint works | one object per edge |
| `curated/dynamodb/.../ResearcherMetricCache/part-NNNNN.jsonl` | `WINDOW#<w>`: researcher-metrics joined with researcher-communities; `CHANGE#<from>><to>`: temporal/researcher-changes; `RANKING#<metric>`/`TOP#<w>`: the rankings | one item per (researcher, window), (researcher, comparison), (metric, window) |
| `curated/dynamodb/.../EntityLinkCache/part-NNNNN.jsonl` | canonical researchers: displayName and alternateNames | one item per distinct (name key, researcher) |
| `curated/dynamodb/.../ArtifactRegistry/part-00000.jsonl` | this run's objects | one `RUN#<run>` item per artifact (`neptune/graph`, `dynamodb/ResearcherMetricCache`, `dynamodb/EntityLinkCache`, `s3/coauthor-evidence`, `candidates/<index>`), with `contentHash` = storage `Cli.contentHash` over the artifact's objects, `rowCount`, `manifestKey` |
| `semantic-context/candidates/.../<index>/part-00000.jsonl` | selection-v1 (below) | one line per candidate, in selectionRank order |
| `runs/store-conversion/.../manifest.json` | every object above | `store-run-manifest-v1`: key, sha256, bytes, sourceRef (`<table>@<run>`), written last |

Neptune values: every element carries `runId` and `snapshot` from the source row's provenance (the Phase 6 stage run that computed it) and `schemaVersion` `neptune-<name>-v1`; edges add `policyVersion` from the row's provenance. Edge IDs: COAUTHORED_WITH keeps the Phase 6 `cae-` ID; every other edge is `ge-` + H("graph-edge", TYPE, start, end, policyVersion). Up to 20 supporting work IDs are inline (space-separated, sorted); above that `supportingWorkIdsRef` names the evidence object. Fields are RFC 4180 (quoted when they hold a comma, quote, CR, or LF; quotes doubled); null is an empty field; doubles are plain decimals without an exponent.

DynamoDB values: DynamoDB JSON (`S`, `N`, `BOOL`, `L`, `M`); a null column is no attribute. `WINDOW#` items carry every researcher-metrics measure, `maximumTeamWorkIds` (a list; a list longer than 50 IDs stops the run, because STORAGE.md defines no pointer layout for it; Phase 6 lists hold at most 17), the community and bridge columns, `runId` (researcher-analytics run), `communityRunId` (community-path run), `snapshot`, `schemaVersion` `researcher-metrics-window-v1`, `policyVersion`. `CHANGE#` items carry every researcher-changes column (`researcher-change-v1`). EntityLinkCache: `nameKey` = DynamoContract.nameKey (drop a trailing 4-digit DBLP homonym number, then affn-v1 canonical form, `name-v1:` prefix); the matched name is the display name when it gives the key, else the smallest alternate name giving it (`entity-link-v1`).

### Rankings (ruling 21)

- Definition: for each metric (publicationCount, distinctCoauthorCount, maximumCoauthorsOnOnePaper, coreness from researcher-metrics; crossCommunityDegree, communitiesConnected, participationCoefficient from researcher-communities) and window, the 50 researchers with the highest value; ties by researcherId ascending; rank 1..50 with no shared ranks; a null value is not ranked.
- Algorithm: one `row_number` window per (metric, window) ordered by (value desc, researcherId). Exact; no approximation.
- Limitations: a hard cut at 50, so a tie at rank 50 is broken by ID and the 51st equal researcher is not listed. The bridge rankings inherit every limitation of the communities (job 6); hubs dominate the degree measures.
- Stored as 21 items `RANKING#<metric>` / `TOP#<window>` (`researcher-ranking-v1`, runId = this run) and as `rankings/researcher-rankings` in the JobRun directory.

### Embedding candidates (selection-v1)

Implements modules/rag/SELECTION.md exactly (owner: rag-engineer): `rankKey` = H("embed-select", "selection-v1", "20261003", index, subjectId, window) (the two frozen values are tested); paths: every path summary of the path run, hash order; profiles: tier 1 path researchers (source, target, hop IDs), tier 2 researchers of the all-years rankings, tier 3 the rest, first 5,000; communities: per-window quotas 2,000/1,000/1,000, tier 1 the all-years communities of tier 1 and 2 profiles; topics: all; papers: tier 1 hop works of FOUND paths (path rank, hop order), tier 2 recent works of the selected profiles (profile rank, list order), tier 3 eligible works by hash, skipping ineligible and repeated works, first 1,000. Paper text is the title trimmed like Java `String.trim`; paper facts are `paper-facts-v1`. The metadata lists (entityIds, topicIds, institutionIds, communityId) are derived from the facts as SELECTION.md's table says. Each line is `embedding-candidate-v1` (nulls written as null) plus `facts` as a JSON object.

### Publishing and shuffle boundaries

- Each family is a DataFrame of (k, line): hash-repartitioned on k (the element ID or item key) into ceil(rows / 250,000) partitions, sorted by k within each, so the same input gives the same bytes. Spark writes the lines as text under the JobRun directory; the driver then streams each part file, with the CSV header first, to its final key while computing its sha256, and deletes the temp part. The final store file system is a private Hadoop FileSystem instance with checksums off, so no `.crc` file reaches `graph/`.
- Shuffles: one repartition per family; the WINDOW# join on (window, researcherId); the rankings window per (metric, window) and their group-by; the entity-link window per (nameKey, researcherId); selection: broadcast joins of the tier sets, a `row_number` per window for communities, a `row_number` per workId for papers, and the paper fact joins and top-N windows on workId for the 1,000 selected works only.
- Driver: holds a 64 KB copy buffer, the manifest entries (one per object: 608 in work order 6), the at most 21 ranking items' counts, and bounded report rows. Limitation: every output byte passes through the driver once (copy and hash), and the evidence objects are written by the driver from one Spark-written file: about 1.7 GB locally; on EMR this is one sequential S3 stream per file.

## Running locally

1. `sysctl -n hw.memsize`: keep the driver heap at most half of RAM (`SCHOLARCHAIN_SPARK_HEAP`, default 6g, set in build.sbt).
2. `df -k .`: each stage stops itself below `min-free-disk-gib` (5), but check before a full run. A full run writes about 0.7 GiB (work order 5: 410M + 42M + 84M + 153M + 2M).
3. `sbt "sparkAnalytics/runMain scholarchain.spark.PipelineJob --input scratch/hadoop/<hadoop out>/snapshot=<date>/run=<hadoop run id> --code-version <code version> --shuffle-partitions 40"`, or the four stage mains one at a time.
   - `--code-version` is required and has no default (ruling 24); it goes into every run ID and manifest. On EMR it is the git commit SHA. For an uncommitted local tree use `src-` plus the first 12 hex digits of `find modules/spark/src/main build.sbt -type f | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-12`, computed after the last source edit.
   - Master (ruling 25): the code sets one only when `spark.master` is not already set. `spark-submit --master` (YARN on EMR) wins; otherwise the `--master` flag, else the conf fallback `local[*]`. The manifests record the master actually used.
4. Outputs land under `scratch/spark/snapshot=<date>/`. Checkpoints go under `scratch/spark/checkpoints/<runId>/` and are deleted at the end of each stage.

## The later EMR run (cloud window 1; not run in Phase 6)

All paths go through the Hadoop FileSystem API, so the same flags take `s3a://` URIs. The infra-engineer's scripts launch the assembly jar from `sbt packageJobs` (`modules/spark/target/scala-3.3.7/sparkAnalytics-assembly-0.1.0.jar`) with `spark-submit --class scholarchain.spark.PipelineJob`, and the deploy-engineer runs it.

```text
spark-submit --master yarn ... (sets spark.master; the job then sets none)
--input          s3a://<bucket>/curated/hadoop/snapshot=<dblp snapshot>/run=<hadoop run id>
--out            s3a://<bucket>/curated/spark
--checkpoint-dir hdfs:///scholarchain/spark/checkpoints
--code-version   <git sha>
--shuffle-partitions <about 2-3 x total executor cores>
```

- `<bucket>` is the one environment bucket, core `storage.bucket` (`SCHOLARCHAIN_BUCKET`), with the core `storage.prefixes` (Phase 7 ruling 7; the four per-purpose buckets were removed). Hadoop outputs sit under `curated/hadoop/` (modules/hadoop/JOBS.md still names the old buckets; its owner updates it).
- Outputs: `s3a://<bucket>/curated/spark/snapshot=<date>/run=<runId>/...` (modules/storage/STORAGE.md, `curated/spark`), the same layout as locally. The load manifest is under the export stage's `export/`. `graph/` holds only Neptune bulk-load CSV, written by StoreConversionJob.
- Checkpoints: cluster HDFS is suggested. Iterative jobs write a reliable checkpoint every round, and S3 rename semantics make checkpoint writes slow.
- Unverified until cloud window 1:
  - the free-disk check on S3A (S3A reports no capacity limit, so the check cannot stop a run there);
  - executor sizing;
  - the unshaded Scala 3 library on EMR's classpath (work order 1).

## Provenance, schema versions, and the core records (rulings 21, 22)

Every table a stage writes, and so every table in `load-manifest.json`, carries on every row:
- `schemaVersion`: `spark-<table path with / as ->-v1`, for example `spark-canonical-researchers-v1` (`JobRun.schemaVersionOf`). These are Spark analytic tables, not serialized core records, so they do not reuse core version strings. The stage manifest and the load manifest record it per table. A column change bumps the version.
- `provenance`: a struct with the core `Provenance` field names. `sourceDataset` = `DERIVED`, `sourceRecordId` = the row's own key (below), `snapshot` = the DBLP snapshot of the run, `runId` = the Phase 6 stage run that wrote the row, `policyVersion` = the canonical policy (`canon-v1.<er policy>`), the graph policy, or the summary template version.
- A source pointer: either source record IDs directly (mention-map and work-map `sourceDataset` + `sourceRecordId`; self-pairs `authorA`, `authorB`, `workId`; `sourceProvenance` on venues, topics, institutions), or canonical IDs that resolve to source records through the mention map (researcherId) and the work map (workId).

Checks: `JobRun.write` refuses to write a table without the provenance struct. Job 11 (`TemporalSummaryExportJob.checkedRowCount`) re-reads every table and refuses the export (no load manifest, no `_manifest.json`) if a table lacks the columns, or if any row lacks `schemaVersion` or `provenance.sourceRecordId`, or names another run ID or snapshot. The stage manifests, the pipeline manifest (`lineage`) and `load-manifest.json` record the input Hadoop run ID, the entity-resolution policy version (`matchPolicyVersion`), and the code version.

| Table (logical name) | `provenance.sourceRecordId` | Nearest core record | Renamed | Split out | Added | Missing |
|---|---|---|---|---|---|---|
| `canonical/researchers` (`nodes/type=researcher`) | researcherId | `CanonicalResearcher` | none | none | mentionCount, anchorMentionId, sourceDatasets, dblpAnchored, assignedTier, separationReason | `externalIds` (ORCID is the only external ID; OpenAlex author IDs stay on the mentions) |
| `canonical/works` (`nodes/type=work`) | workId | `WorkRecord` | none | authors -> `canonical/authored` and the mention map | memberWorkIds, sourceDatasets, dblpAnchored, assignedTier, separationReason, identityConfidence, uncertainty | `source`, `sourceRecordId` (per member, in the work map), `arxivId`, `externalIds`, `abstractTextRef` |
| `canonical/authored` (`edges/type=authored`) | researcherId\|workId | `AuthorshipRecord` | mentionId -> `mentionIds` (one row per researcher and work, not per mention) | the per-mention link is the mention map (RESOLVES_TO) | sourceDatasets | `matchConfidence` (per mention: the mention map's `identityConfidence`) |
| `canonical/mention-map` | mentionId | `AuthorMention` (link part) and the RESOLVES_TO relation | workId -> sourceWorkId | none | researcherId, assignedTier, separationReason, refusal rules, identityConfidence (uncertainty of each resolution) | name, ORCID, institutions (in the J09 mention) |
| `canonical/work-map` | sourceWorkId | `WorkRecord` (link part) | workId -> sourceWorkId, canonical -> workId | none | assignedTier, separationReason, refusal rules, identityConfidence | the work fields (in the J09 work) |
| `canonical/venues`, `topics`, `institutions` | venueId, topicId, institutionId | `Venue`, `Topic`, `Institution` | none | none | `sourceProvenance` (the J09 provenance) | none |
| `canonical/work-topics` (`edges/type=has_topic`) | workId\|topicId | `GraphEdge` (HAS_TOPIC) | fromId/toId -> workId/topicId | none | evidenceEdgeIds | edgeId, edgeType, properties |
| `canonical/researcher-institutions` (`edges/type=affiliated_with`) | researcherId\|institutionId | `GraphEdge` (AFFILIATED_WITH) | fromId/toId -> researcherId/institutionId | none | via, evidenceIds | edgeId, edgeType, properties |
| `canonical/researcher-refusals`, `work-refusals` | entityType\|tier\|sampleMember | none | | | | |
| `graph/coauthor-edges` (`edges/type=coauthored_with`) | edgeId | `CoauthorEdge` | none | none | window | none |
| `graph/self-pairs` | authorA\|authorB | `CoauthorPairCandidate` | none | none | researcherId | none |
| `analytics/researcher-metrics` (`metrics/type=researcher_collaboration+kcore`) | researcherId\|window | `ResearcherMetrics` | none | communityId, crossCommunityDegree, communitiesConnected, participationCoefficient -> `analytics/researcher-communities` (join on researcherId and window) | window, graphDegree | none |
| `analytics/researcher-communities` (`metrics/type=bridges`) | researcherId\|window | `ResearcherMetrics` (community and bridge fields) | none | none | communitySize, graphDegree, window columns | the collaboration fields (in researcher-metrics) |
| `analytics/components` (`metrics/type=components`) | componentId\|window | none | | | | |
| `analytics/communities` (`metrics/type=communities`) | communityId | none | | | | |
| `analytics/cross-community-edges` (`edges/type=cross_community`) | edgeId | `CoauthorEdge` (subset) | none | none | communityA, communityB | first/last year and work IDs (join `graph/coauthor-edges` on edgeId) |
| `paths/path-evidence` (`path-evidence`) | pairId | `EvidenceItem` (graph path, nearest) | none | none | | |
| `paths/path-length-estimate` | largestComponentId | none | | | | |
| `temporal/window-summary`, `window-comparison` | window; fromWindow->toWindow | none | | | | |
| `temporal/researcher-changes` | researcherId\|fromWindow\|toWindow | none | | | | |
| `temporal/community-transitions` | fromCommunityId\|toCommunityId | none | | | | |
| `summaries/semantic-summaries` (`semantic-summaries`) | summaryId (policy = template version) | `EvidenceItem` (semantic chunk, nearest) | none | none | | |
| `integrity-violations`, `analytics/integrity-violations`, `analytics/community-path-violations`, `summary-export-violations` | check\|subjectId\|relatedId | none | | | | |

"none" as the nearest core record means core has no such record; the table is a Spark analytic output. Store-specific shapes that follow the core records are built in Phase 7+8 from these tables (ruling 13).

## Job index (operational summary)

| Spec job | Class | Inputs | Outputs (partition columns) | Write partitioning | Checkpoints | Broadcast | Persisted |
|---|---|---|---|---|---|---|---|
| 1, 2 | CanonicalGraphJob | Hadoop J09 and J03+J04 outputs (JSON Lines, explicit schemas) | canonical/*, graph/coauthor-edges (window), graph/self-pairs, integrity-violations | hash on the key column; 8 for large tables, 1-2 for small | every connected-components round | windows table; dangling sets (0 rows) | inputs, MEMORY_AND_DISK (decisions DISK_ONLY) |
| 3, 4, 8 | ResearcherAnalyticsJob | canonical/researchers, authored, works; graph/coauthor-edges | analytics/researcher-metrics (window), components (window), integrity-violations | 8 per window on researcherId; 1 per window | every min-label and k-core round | windows table | inputs, stats, labels, coreness |
| 5, 6, 7 | CommunityPathJob | researcher-metrics, components; coauthor-edges, researchers | paths/* (window), analytics/communities, cross-community-edges, researcher-communities (window), violations | 8 per window on researcherId; 1 for the others | every BFS and label-propagation round | windows table; largest components; pending targets; parents during the walk back | inputs, labels |
| 9, 10, 11 | TemporalSummaryExportJob | the three runs above | temporal/*, summaries/semantic-summaries (documentType, window), summary-export-violations, export/load-manifest.json | 8 on researcherId for researcher-changes; 2 on subjectId for summaries; 1 for the others | none (no iteration) | windows table; largest components | inputs and dblpWorks, DISK_ONLY (execution memory is left to the top-N sorts) |
| all | PipelineJob | Hadoop run | the four stage runs plus the pipeline manifest | n/a | n/a | n/a | n/a |
| 5, 10 (paths) | BenchmarkPathJob | a CommunityPathJob run (and its canonical researchers and authored); the benchmark CSV | paths/benchmark-pairs, selected-pairs.csv, path-evidence (window), summaries/path-summaries (documentType, window), violations | 1 | every BFS round | windows table; benchmark names; seeded pair IDs | inputs |
| store files | StoreConversionJob | the Phase 6 load manifest's tables; a BenchmarkPathJob run | Neptune CSV, evidence objects, DynamoDB items, candidates, store run manifest (under --stores-root); rankings/researcher-rankings | ceil(rows / 250,000) per family, hash on the element ID | none (no iteration) | windows table; selection tier sets | inputs DISK_ONLY; each family's lines while counted and written |

- Schema versions and provenance: see "Provenance, schema versions, and the core records" above. Table schemas are recorded as DDL in each manifest.
- Driver memory: only bounded results reach the driver: counts, top-N lists, histograms, at most `2 x path-sample-pairs` sampled IDs, and per-round convergence numbers. A 6g heap was enough for every local run.
