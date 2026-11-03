# Phase 04 work order

## Work order 1 of 1: hadoop-engineer

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
