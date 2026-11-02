# Phase 03 work orders

Phase 3 runs in one orchestrator session with three work orders in order, followed by one code review. Each work order is appended here before it is sent.

## Work order 1 of 3: dataset-engineer

Use the dataset-engineer agent to create the ScholarChain dataset manifest, bounded retrieval scripts, samples, and schema notes.

Inputs to read first: CLAUDE.md, docs/agents.md, spec/ScholarChain-spec.md (sections "Dataset portfolio", "Required dataset scope", "Dataset manifest", "Entity linkage policy", and Phase 3), spec/architect-decisions.md (D-09, D-10, section 3 landmines, section 7 working assumptions).

Scope rule: every item below is required. Minimal implementations are fine; anything skipped must be reported under RISKS.

Datasets (exactly four, D-10): DBLP (seed), OpenAlex, Crossref, ORCID.

Subset design:
- DBLP subset: article and inproceedings records from a documented venue list centred on distributed systems, systems, and parallel computing, years 2000 to 2026, sized to roughly 100,000 works. Put the venue list and year range in a versioned config file under datasets/, not hardcoded in scripts.
- Every enrichment subset is driven by the DBLP subset. OpenAlex and Crossref are fetched by the DBLP subset's DOIs. ORCID records are fetched only for ORCID iDs found in DBLP person records or in fetched OpenAlex authorships.
- Use official public APIs or official dumps. Verify current access rules, rate limits, polite-pool or API-key requirements, and license terms from each provider's official documentation, and cite the URL and retrieval date in the manifest. Never invent a license, endpoint, or limit. If something cannot be verified, mark it UNVERIFIED in the manifest and list it under RISKS.

Create:
1. datasets/dataset-manifest.yaml, with one entry per dataset containing every field in the spec's "Dataset manifest" section: name and purpose; official source and documentation links; license or reuse terms; snapshot or retrieval date; retrieval command; format and compression; expected and observed size; planned S3 raw prefix (s3://<raw-bucket>/<dataset>/snapshot=<date>/, bucket name from configuration, not hardcoded); checksum or integrity policy; schema version; subset rule; incremental update strategy; linkage fields; personal data and privacy notes; known limitations; validation commands; owning agent.
2. Bounded, idempotent retrieval scripts under datasets/scripts/:
   - DBLP: download dblp.xml.gz and dblp.dtd with integrity checks, then extract the subset.
   - OpenAlex, Crossref, ORCID: batched, rate-limited, resumable fetchers driven by the DBLP subset's DOI and ORCID lists.
   - Default mode is SAMPLE (tiny and safe). Full subset runs require an explicit flag such as --full, and print the expected request count and download size before starting.
   - Write all downloads under scratch/datasets/ (already git-ignored). Never write downloaded data into the repository.
   - Use bash, curl, jq, and the Python 3 standard library only. Install nothing.
   - Read the contact email for polite API pools from SCHOLARCHAIN_CONTACT_EMAIL. Store no secrets.
   - Use strict mode, greppable step markers, and explicit exit codes. A re-run skips completed work.
3. Small, legally redistributable sample files under datasets/samples/: a few dozen records per dataset, linked to each other through shared DOIs so later phases can test cross-dataset linking. Keep every sample file under 200 KB.
4. Schema notes under datasets/schemas/: one note per dataset listing the fields ScholarChain uses, their types, nullability, and linkage keys.
5. A validation script under datasets/scripts/ that checks the manifest's required fields, sample file sizes, and script syntax (bash -n), and exits nonzero on failure.

Verify: run the validation script, run every fetcher in SAMPLE mode, and run each script a second time to show it is idempotent. Do not run any --full mode.

Report, in the standard report contract: the subset rule and venue list; the estimated work count and request counts for the full run; each dataset's verified license and access rules with source URLs; sample record counts and cross-dataset DOI overlap in the samples; the exact commands the human must run for the full subset; and unresolved dataset questions.

After the agent returns, save its report verbatim to docs/agent-runs/phase-03/agent-report-dataset.md, wrapped in a ````text fence. Do not invoke any other agent.

## Work order 2 of 3: build-engineer

Use the build-engineer agent to make two small build changes for Phase 3.

Inputs to read first: CLAUDE.md, docs/agents.md, project/Dependencies.scala, build.sbt, docs/agent-runs/phase-02/review-report.md (RISKS), spec/architect-decisions.md (D-02, D-05, D-09).

1. Add Typesafe Config (com.typesafe:config) to the core module in Compile scope, so the domain-engineer can load versioned HOCON configuration. Pin an exact version at least 14 days old (D-09) in project/Dependencies.scala, with its publication date in a comment.
2. Close the Phase 2 review risk: pin scala-library to 2.13.16 with dependencyOverrides in every module, so no future dependency can silently upgrade it past the version EMR emr-spark-8.0.0 ships.

Do not create sources under modules/ or tests/. Run sbt check twice. Return a standard report.

After the agent returns, save its report verbatim to docs/agent-runs/phase-03/agent-report-build.md, wrapped in a ````text fence. Do not invoke any other agent.

## Work order 3 of 3: domain-engineer

Use the domain-engineer agent to create the ScholarChain shared domain contracts in the core module.

Inputs to read first: CLAUDE.md, docs/agents.md, spec/ScholarChain-spec.md (sections "Core data model", "Research graph model", "Entity linkage policy", "Configuration", Phase 3 domain-engineer work and "Required stable ID principles"), spec/architect-decisions.md (D-02, D-05, D-10), datasets/schemas/, datasets/samples/, project/Dependencies.scala.

Scope rule: every item below is required. Minimal implementations are fine; anything skipped must be reported under RISKS.

Create, under modules/core/ only (you create its source tree in this phase):
1. Immutable Scala 3 records, each with a schema version and a uPickle ReadWriter: WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, Venue, Institution, Topic, Provenance, MatchCandidate, MatchDecision, GraphNode, GraphEdge, EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim, AnswerResponse. Field meanings must agree with datasets/schemas/ and the spec's core data model. Keep core free of Spark and Hadoop dependencies.
2. Enums: source type, match outcome (ACCEPTED, REJECTED, UNRESOLVED, CONFLICT), evidence type, collaboration metric, query operation, verification status (VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING).
3. Stable ID functions for work, researcher, coauthor edge, graph edge, chunk or profile, evidence, and run IDs, following the spec's "Required stable ID principles". Document each formula in a code comment.
4. Undirected pair canonicalization: one canonical ordering rule for researcher pairs, used by CoauthorPairCandidate and CoauthorEdge.
5. Validation that rejects: missing provenance, confidence outside [0, 1], unsupported schema versions, malformed stable IDs, non-canonical pair ordering, and time windows where the start year exceeds the end year.
6. A configuration loader using Typesafe Config: a versioned HOCON reference file plus environment-variable overrides, with documented precedence. Nothing is hardcoded: bucket names, endpoints, thresholds, model IDs, AWS account IDs, graph windows, and bridge-algorithm parameters all come from configuration. Default graph windows are the three D-10 windows (all years, 2000-2014, 2015-2026).
7. Tests under modules/core/src/test/ only (not tests/, which belongs to the test-engineer): serialization round trips, schema-version checks, frozen JSON wire examples as contract tests, stable-ID determinism, pair canonicalization, every validation rule above, and configuration precedence.

Run sbt check twice. Report, in the standard report contract: sample serialized records, stable ID formulas, the pair canonicalization rule, configuration precedence, schema versions, exact test counts, and known unresolved dataset questions.

After the agent returns, save its report verbatim to docs/agent-runs/phase-03/agent-report-domain.md, wrapped in a ````text fence. Do not invoke any other agent.
