# Phase 04 work order

## Work order 1 of 4: hadoop-engineer

Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline in modules/hadoop.

Inputs to read first: CLAUDE.md, docs/agents.md, spec/ScholarChain-spec.md (Phase 4 and "Detailed Hadoop requirements"), spec/architect-decisions.md (D-01 to D-03, D-10, section 3 landmines, AWS capability check), docs/agent-runs/phase-03/human-gate.md (Remaining risk), datasets/schemas/, datasets/samples/, datasets/config/subset.v1.json, modules/core/ (records, StableIds, Validation, ConfigLoader, scholarchain.conf), build.sbt, project/Dependencies.scala.

Scope rule: every item below is required. Minimal implementations are fine; anything skipped must be reported under RISKS.

Jobs (Java MapReduce API, D-03). You may merge adjacent jobs (J03+J04, J05+J06, J07+J08) if you document the merged contract:

- J01 DblpExtractJob: read the DBLP subset produced by datasets/scripts (see datasets/schemas/dblp.md) with an input format that never splits a record across mappers. Resolve DBLP character entities from the DTD. Emit WorkRecord and AuthorMention.
- J02 SourceNormalizeJob: one normalizer each for OpenAlex, Crossref, and ORCID, mapping into the core contracts without losing source IDs, author identifiers, affiliations, topics, or work identifiers. Malformed or unsupported records go to versioned reject outputs.
- J03 ExactIdentifierIndexJob and J04 ExactIdentifierJoinJob: DOI, ORCID, and OpenAlex keyed indexes; exact matches and exact conflicts, using StableIds.normalizeDoi and normalizeOrcid from core.
- J05 BlockingKeyJob and J06 CandidateJoinJob: bounded researcher and work blocks (normalized names, initials, coauthor context, title, year, venue). Enforce a configurable maximum block size with fallback keys and an oversize counter. Emit MatchCandidate records with deterministic features, not decisions.
- J07 PublicationTeamJob: one PublicationTeam per retained work, plus author-paper records.
- J08 CoauthorPairExpansionJob: n(n-1)/2 CoauthorPairCandidate records per paper with at least two authors, canonical pair order from core, work ID, year, author positions, and provenance on every record. Count oversize teams using graph.oversize-team-threshold from configuration.
- J09 GraphCandidateExportJob: Spark-ready, partitioned outputs for works, author mentions, teams, pair candidates, match candidates, and enrichment records.

Operational requirements:

- Output format: JSON Lines encoded with the core uPickle codecs, compressed. Spark must be able to read every output. Add no new dependency; if one is needed, report BLOCKED-ON build-engineer.
- Every input and output path comes from configuration or arguments, and works with both local paths and s3a:// URIs (EMRFS is removed in emr-spark-8.0.0). Output paths contain the dataset snapshot and the run ID.
- Run IDs come from StableIds with attempt incremented on every rerun of the same inputs (Phase 3 review risk 4).
- Hadoop counters for input, accepted, rejected, duplicate, conflict, oversize block, oversize author team, emitted pair, missing key, and schema failure records.
- Explicit partitioner and reducer counts, compressed intermediate outputs, no mutable shared state across tasks, and deterministic output for deterministic input.
- Document every job's input format, mapper and reducer key-value contracts, combiner, partitioner, counters, reject conditions, reducer count, paths, compression, idempotency rule, and test fixture in modules/hadoop/JOBS.md.

Tests under modules/hadoop/src/test/ only:

- Unit tests for the DBLP record reader (records near split boundaries, multiline titles, entities, Unicode, missing optional fields, repeated authors), each normalizer, key functions, and pair generation for papers with 1, 2, 3, and a larger number of authors.
- A local integration test that runs the job chain with Hadoop's local job runner on datasets/samples/ and checks counters and outputs.

Run sbt check twice. Then run the full local job chain on datasets/samples/, writing under scratch/hadoop/. If full dataset outputs already exist under scratch/datasets/, also run the chain locally on them and report the counters. Never write job output into the repository.

Report, in the standard report contract: the job graph, mapper and reducer key-value contracts, partition counts, all counters for each run, rejected records, output paths, maximum observed team size, largest pair expansion, the largest blocking group, and exact test counts.

After the agent returns, save its report verbatim to docs/agent-runs/phase-04/agent-report.md, wrapped in a ````text fence. Do not invoke any other agent.

## Human rulings after work order 1 (BLOCKED-ON entity-resolution-engineer and domain-engineer)

- ORCID profiles output as GraphNode plus Institution records with AFFILIATED_WITH edges: accepted; no new core record type.
- Affiliation IDs without an external identifier move into core StableIds (domain-engineer), replacing the hadoop-defined scheme.
- Oversize teams keep writing all pairs (required for correct team-size metrics); the counter stays.
- J03+J04 conflict output gets a configurable cap and counter.
- Run attempt may be passed explicitly by run scripts; auto-claim stays the default for local runs.
- Reports for work orders 2-4 are appended to agent-report.md, one section each.

## Work order 2 of 4: domain-engineer

Use the domain-engineer agent to make two small core changes for Phase 4:

1. Add configuration keys entity-resolution.max-block-size and entity-resolution.max-conflict-group-size, with SCHOLARCHAIN\_\* environment overrides, PROVISIONAL defaults, and validation that both are positive.
2. Add a documented, tested stable ID function for affiliations that have no external identifier, based on a canonical form of the affiliation name, to replace the hadoop module's affiliation-name:<hash> scheme.
   Change frozen wire examples only if a record changes. Run sbt check twice. Return a standard report.

## Work order 3 of 4: entity-resolution-engineer

Use the entity-resolution-engineer agent to create, in modules/entity-resolution (you create its source tree in this phase), the pure functions that Hadoop jobs J05 and J06 need. No Hadoop or Spark dependencies; use the core contracts, StableIds.normalizeDoi, and StableIds.normalizeOrcid; add no new dependency.

1. Normalization: Unicode (decomposition and diacritic folding), punctuation, whitespace, title markup, initials, name order including "Family, Given", and DBLP homonym suffixes such as "Wei Wang 0001" (stripped from the comparable name but kept as separate evidence).
2. Blocking keys: a primary researcher key and at least one fallback key, and a primary work key and at least one fallback key. Deterministic and documented.
3. Oversize policy: given entity-resolution.max-block-size, a deterministic rule that re-keys oversize blocks with fallback keys, and reports blocks that remain oversize.
4. Match-feature functions, each returning a value in [0, 1] deterministically: name similarity, initials compatibility, author-order agreement, coauthor overlap, affiliation compatibility, ORCID agreement, title similarity, year distance, and venue compatibility.
   No thresholds, scores, or match decisions; those are Phase 5. Tests under modules/entity-resolution/src/test/ only: every normalizer, every blocking key, the oversize policy, and every feature on hand-checkable pairs. Run sbt check twice. Return a standard report.

## Work order 4 of 4: hadoop-engineer

Use the hadoop-engineer agent to finish Phase 4 in modules/hadoop:

1. Implement J05 BlockingKeyJob and J06 CandidateJoinJob with the entity-resolution functions and entity-resolution.max-block-size.
2. Replace the name and title placeholders with the entity-resolution normalization.
3. Use core's affiliation ID function instead of the hadoop-defined scheme.
4. Cap J03+J04 conflict output with entity-resolution.max-conflict-group-size, plus an oversize-conflict counter.
5. Accept the run attempt as an explicit argument; keep auto-claim as the default for local runs.
6. Keep writing all pairs for oversize teams, and keep the oversize-team counter.
7. Run sbt packageJobs and confirm that the hadoop job jar has no top-level scala/ entries and contains no Hadoop classes.
   Update modules/hadoop/JOBS.md. Run sbt check twice, then the job chain on datasets/samples/ twice to show determinism. If the full DBLP subset extract exists under scratch/datasets/, also run the chain locally on it, with whatever enrichment data exists, and report the counters. Report, in the standard report contract: the updated job graph, the J05 and J06 contracts, all counters for each run, maximum observed team size, largest pair expansion, the largest blocking group, jar inspection results, and exact test counts.
