#!/usr/bin/env bash
# Build the small committed samples under datasets/samples/ from the SAMPLE-mode fetch outputs
# in scratch/datasets/. Run fetch_dblp.sh, fetch_openalex.sh, fetch_crossref.sh, and fetch_orcid.sh
# (all in default SAMPLE mode) first. Network is never used here.
#
#   DBLP (verbatim records), OpenAlex and Crossref (field-minimized, no abstracts), and ORCID's fictional
#   test record (minimized). Real ORCID records are never committed (human ruling, phase-03 repair-01,
#   Decision 1); their minimized projection is written to scratch/datasets/orcid/sample/ for review only.
#   datasets/samples/orcid/synthetic-orcid-records.jsonl is hand-authored and is not touched here.
#
# Deterministic: records are sorted, so a re-run with unchanged inputs changes no file.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() { sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown argument: $1" 2 ;;
  esac
  shift
done
require_tools jq gzip comm

DBLP_IN="$(dblp_out_dir sample)"
OA_IN="$SCRATCH/openalex/sample"
CR_IN="$SCRATCH/crossref/sample"
OR_IN="$SCRATCH/orcid/sample"
OR_TEST="$SCRATCH/orcid/test-record"
for d in "$DBLP_IN" "$OA_IN" "$CR_IN" "$OR_IN" "$OR_TEST"; do
  [ -f "$d/_SUCCESS" ] || die "missing SAMPLE output $d; run the fetch scripts first" 6
done
S="$DATASETS_DIR/samples"
mkdir -p "$S/dblp" "$S/openalex" "$S/crossref" "$S/orcid"

# install SRC DEST: replace DEST only when the content differs.
install_file() {
  if [ -f "$2" ] && cmp -s "$1" "$2"; then rm -f "$1"; log SKIP "sample: ${2#"$REPO_ROOT"/} unchanged"
  else mv "$1" "$2"; log DONE "sample: ${2#"$REPO_ROOT"/} $(bytes_of "$2") bytes"; fi
}

log STEP "dblp: verbatim subset records, DTD, DOI and ORCID lists"
DTD_NAME="$(sed -n 's/^<!DOCTYPE dblp SYSTEM "\([^"]*\)">.*/\1/p' "$DBLP_IN/works.xml" | awk 'NR == 1')"
[ -n "$DTD_NAME" ] || die "dblp: no DOCTYPE in $DBLP_IN/works.xml" 4
cp "$DBLP_IN/works.xml" "$S/dblp/dblp-sample.xml.new" && install_file "$S/dblp/dblp-sample.xml.new" "$S/dblp/dblp-sample.xml"
cp "$(dirname "$DBLP_IN")/raw/dblp.dtd" "$S/dblp/$DTD_NAME.new" && install_file "$S/dblp/$DTD_NAME.new" "$S/dblp/$DTD_NAME"
cp "$DBLP_IN/dois.txt" "$S/dblp/dois.txt.new" && install_file "$S/dblp/dois.txt.new" "$S/dblp/dois.txt"
cp "$DBLP_IN/orcids.txt" "$S/dblp/orcids.txt.new" && install_file "$S/dblp/orcids.txt.new" "$S/dblp/orcids.txt"

log STEP "openalex: minimized works (no abstracts, no raw affiliation strings)"
jq -s -c 'sort_by(.doi)[] | {
  id, doi, title, publication_year, type, ids, cited_by_count, updated_date,
  primary_location: (.primary_location | if . == null then null else {
    raw_source_name, landing_page_url,
    source: (.source | if . == null then null else {id, display_name, issn_l, type} end)} end),
  primary_topic: (.primary_topic | if . == null then null else
    {id, display_name, subfield: .subfield.display_name, field: .field.display_name} end),
  authorships: [.authorships[]? | {author_position, raw_author_name, is_corresponding,
    author: {id: .author.id, display_name: .author.display_name, orcid: .author.orcid},
    institutions: [.institutions[]? | {id, display_name, ror, country_code, type}]}]
}' "$OA_IN/works.jsonl" >"$S/openalex/works-sample.jsonl.new"
install_file "$S/openalex/works-sample.jsonl.new" "$S/openalex/works-sample.jsonl"

log STEP "crossref: minimized works (no abstracts, no references)"
jq -s -c 'sort_by(.DOI | ascii_downcase)[] | {
  DOI, title, type, "container-title", ISSN, issued, "published-print", "published-online",
  publisher, member, page, volume, issue, "references-count", "is-referenced-by-count", URL,
  indexed: .indexed."date-time",
  license: [.license[]? | {URL, "content-version", start: .start."date-time"}],
  funder: [.funder[]? | {name, DOI, award}],
  author: [.author[]? | {given, family, name, sequence, ORCID, "authenticated-orcid",
    affiliation: [.affiliation[]? | {name}]}]
}' "$CR_IN/works.jsonl" >"$S/crossref/works-sample.jsonl.new"
install_file "$S/crossref/works-sample.jsonl.new" "$S/crossref/works-sample.jsonl"

log STEP "orcid: minimized records (no biography, emails, addresses, keywords, or researcher URLs)"
ORCID_PROJ='{
  orcid: ."orcid-identifier".path,
  name: (.person.name | if . == null then null else
    {given: ."given-names".value?, family: ."family-name".value?, credit: ."credit-name".value?} end),
  other_names: [.person."other-names"."other-name"[]?.content],
  external_ids: [.person."external-identifiers"."external-identifier"[]? |
    {type: ."external-id-type", value: ."external-id-value"}],
  employments: [."activities-summary".employments."affiliation-group"[]?.summaries[]?."employment-summary" |
    {org: .organization.name, country: .organization.address.country,
     org_id: (.organization."disambiguated-organization" | if . == null then null else
       {source: ."disambiguation-source", id: ."disambiguated-organization-identifier"} end),
     department: ."department-name", role: ."role-title",
     start_year: ."start-date".year.value?, end_year: ."end-date".year.value?}],
  work_dois: ([."activities-summary".works.group[]?."external-ids"."external-id"[]? |
    select(."external-id-type" == "doi") | ."external-id-value" | ascii_downcase] | unique),
  last_modified_ms: .history."last-modified-date".value?
}'
gzip -dc "$OR_IN/records.jsonl.gz" | jq -s -c 'sort_by(."orcid-identifier".path)[] | '"$ORCID_PROJ" >"$OR_IN/orcid-sample.minimized.jsonl"
log INFO "orcid: real records stay in scratch (review copy $OR_IN/orcid-sample.minimized.jsonl, $(bytes_of "$OR_IN/orcid-sample.minimized.jsonl") bytes)"
TID="$(cfg .orcid.fictional_test_record_id)"
gzip -dc "$OR_TEST/$TID.json.gz" | jq -c --slurpfile info "$OR_TEST/run-info.json" \
  '{sample_kind: "orcid-fictional-test-record", synthetic: false, source_url: $info[0].source_url,
    retrieved_utc: $info[0].retrieved_utc} + '"$ORCID_PROJ" >"$S/orcid/orcid-fictional-test-record.jsonl.new"
install_file "$S/orcid/orcid-fictional-test-record.jsonl.new" "$S/orcid/orcid-fictional-test-record.jsonl"

log STEP "checksums: datasets/samples/SHA256SUMS"
write_sha256sums "$S"

log STEP "links: cross-dataset DOI and ORCID overlap in the samples"
jq -r '.doi | ascii_downcase | sub("^https://doi.org/"; "")' "$S/openalex/works-sample.jsonl" | LC_ALL=C sort -u >"$SCRATCH/.oa-dois"
jq -r '.DOI | ascii_downcase' "$S/crossref/works-sample.jsonl" | LC_ALL=C sort -u >"$SCRATCH/.cr-dois"
jq -r '.work_dois[]' "$OR_IN/orcid-sample.minimized.jsonl" | LC_ALL=C sort -u >"$SCRATCH/.or-dois"
jq -r '.orcid' "$OR_IN/orcid-sample.minimized.jsonl" | LC_ALL=C sort -u >"$SCRATCH/.or-ids"
log INFO "links: dblp_dois=$(wc -l <"$S/dblp/dois.txt" | tr -d ' ') dblp&openalex=$(LC_ALL=C comm -12 "$S/dblp/dois.txt" "$SCRATCH/.oa-dois" | wc -l | tr -d ' ') dblp&crossref=$(LC_ALL=C comm -12 "$S/dblp/dois.txt" "$SCRATCH/.cr-dois" | wc -l | tr -d ' ') dblp&orcid_work_dois=$(LC_ALL=C comm -12 "$S/dblp/dois.txt" "$SCRATCH/.or-dois" | wc -l | tr -d ' ') orcid_ids_in_dblp=$(LC_ALL=C comm -12 "$S/dblp/orcids.txt" "$SCRATCH/.or-ids" | wc -l | tr -d ' ')"
rm -f "$SCRATCH/.oa-dois" "$SCRATCH/.cr-dois" "$SCRATCH/.or-dois" "$SCRATCH/.or-ids"
log DONE "samples: built under ${S#"$REPO_ROOT"/}"
