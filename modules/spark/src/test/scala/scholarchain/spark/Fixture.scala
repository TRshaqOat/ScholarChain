package scholarchain.spark

import scholarchain.core.*
import upickle.default.{ReadWriter, write}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** A hand-built Hadoop run directory in the J09 / J03+J04 layout (modules/hadoop/JOBS.md). Every
  * expected value in CanonicalGraphJobSpec is worked out by hand from the scenarios below.
  */
object Fixture:
  val O1 = "0000-0002-1825-0097" // ORCID's fictional test iD
  val O2 = "0000-0001-5109-3700"
  val O3 = "0000-0002-1694-233X"
  val Policy = "canon-v1.er-v2" // canonical-policy-version . matchPolicyVersion
  val HadoopRunId = "run-" + "0" * 32
  def rid(anchorMention: String): String = StableIds.researcherId(None, Policy, anchorMention)

  private def src(id: String) =
    if id.startsWith("dblp:") then SourceType.DBLP
    else if id.startsWith("openalex:") then SourceType.OPENALEX
    else SourceType.CROSSREF
  private def prov(s: SourceType, id: String) =
    Provenance(SchemaVersions.Provenance, s, id, "2026-10-03", HadoopRunId, None, None)

  final case class M(
      id: String,
      name: String,
      orcid: Option[String] = None,
      author: Option[String] = None
  )
  final case class W(
      id: String,
      year: Int,
      doi: Option[String] = None,
      venue: Option[String] = None
  )

  // Rule IDs as J10 writes them; confidences are distinct so identityConfidence is checkable.
  val Exact = (Vector("er-v1:exact-id-match"), 1.0)
  val IdAgree = (Vector("er-v1:identifier-agreement"), 1.0)
  val Det = (Vector("er-v1:deterministic-bibliographic"), 0.95)
  val Prob = (Vector("er-v1:weighted-score", "er-v1:band-accept"), 0.94)
  val Llm = (Vector("er-v1:weighted-score", "er-v1:band-llm"), 0.8)
  val Group = (Vector("er-v1:exact-id-group-conflict"), 0.5)
  val Disagree = (Vector("er-v1:identifier-disagreement"), 0.5)

  val mentions: Seq[M] = Seq(
    // 1: chain A-B-C, A and C carry different ORCIDs
    M("dblp:s1/W1#1", "Alice Chain", Some(O1)),
    M("dblp:s1/W2#1", "Alice Chain"),
    M("dblp:s1/W3#1", "Alice Chain", Some(O2)),
    // 2: CONFLICT inside a tier-1 component
    M("dblp:s2/W1#1", "Dan Conflict"),
    M("openalex:W2001#1", "Dan Conflict"),
    M("dblp:s2/W2#1", "Dan Conflict"),
    // 3: DBLP suffixes 0001 and 0002 in one component
    M("dblp:s3/W1#1", "Wei Wang 0001"),
    M("dblp:s3/W2#1", "Wei Wang 0002"),
    M("dblp:s3/W3#1", "Wei Wang"),
    // 4: suffix-conflict group orcid:O3 (three members) plus an unsuffixed mention
    M("dblp:s4/W1#1", "Li Na 0001", Some(O3)),
    M("dblp:s4/W2#1", "Li Na 0002", Some(O3)),
    M("dblp:s4/W3#1", "Li Na", Some(O3)),
    M("dblp:s4/W4#1", "Li Na"),
    // 4b: oversize-conflict group openalex:A9 (two members) linked at tier 1
    M("openalex:W4001#1", "Nia Over", author = Some("openalex:A9")),
    M("openalex:W4002#1", "Nia Over", author = Some("openalex:A9")),
    // 5: UNRESOLVED stays separate; a mention with no decision at all
    M("dblp:s5/W1#1", "Uma Unres"),
    M("dblp:s5/W2#1", "Uma Unres"),
    M("dblp:s5/W3#1", "Rex Alone"),
    // 6: a merge puts two authors of one work into one researcher (self-pair)
    M("dblp:s6/W1#1", "Sam Self"),
    M("dblp:s6/W1#2", "S. Self"),
    M("openalex:W6001#1", "Sam Self"),
    // 7 and 8: one pair on two works in different years, in opposite mention order
    M("dblp:s7/W1#1", "Pat Pair"),
    M("dblp:s7/W1#2", "Quinn Pair"),
    M("dblp:s7/W2#1", "Quinn Pair"),
    M("dblp:s7/W2#2", "Pat Pair"),
    // integrity: a mention whose work has no work record
    M("dblp:s10/Missing#1", "Ghost Author")
  )

  val works: Seq[W] = Seq(
    W("dblp:s1/W1", 2005),
    W("dblp:s1/W2", 2006),
    W("dblp:s1/W3", 2007),
    W("dblp:s2/W1", 2008),
    W("openalex:W2001", 2009),
    W("dblp:s2/W2", 2009),
    W("dblp:s3/W1", 2010),
    W("dblp:s3/W2", 2011),
    W("dblp:s3/W3", 2012),
    W("dblp:s4/W1", 2013),
    W("dblp:s4/W2", 2014),
    W("dblp:s4/W3", 2015),
    W("dblp:s4/W4", 2016),
    W("openalex:W4001", 2016),
    W("openalex:W4002", 2017),
    W("dblp:s5/W1", 2017),
    W("dblp:s5/W2", 2018),
    W("dblp:s5/W3", 2018),
    W("dblp:s6/W1", 2019, Some("10.1000/s6"), Some("dblp-venue:conf/x")),
    W("openalex:W6001", 2019, Some("10.1000/s6")),
    W("dblp:s7/W1", 2003, venue = Some("dblp-venue:conf/x")),
    W("dblp:s7/W2", 2016, venue = Some("dblp-venue:conf/x")),
    // 9: one DOI on a DBLP, two OpenAlex, and a Crossref work, with an OpenAlex-OpenAlex CONFLICT
    W("dblp:s9/W1", 2020, Some("10.1000/s9"), Some("dblp-venue:conf/missing")),
    W("openalex:W9001", 2020, Some("10.1000/s9")),
    W("openalex:W9002", 2020, Some("10.1000/s9")),
    W("doi:10.1000/s9", 2020, Some("10.1000/s9"))
  )

  type D = (String, String, (Vector[String], Double))
  val researcherDecisions: Seq[(String, D)] = Seq(
    "ACCEPTED" -> ("dblp:s1/W1#1", "dblp:s1/W2#1", Det),
    "ACCEPTED" -> ("dblp:s1/W2#1", "dblp:s1/W3#1", Prob),
    "ACCEPTED" -> ("dblp:s1/W1#1", s"orcid:$O1", Exact),
    "ACCEPTED" -> ("dblp:s1/W3#1", s"orcid:$O2", Exact),
    "ACCEPTED" -> ("dblp:s2/W1#1", "openalex:W2001#1", IdAgree),
    "ACCEPTED" -> ("dblp:s2/W2#1", "openalex:W2001#1", Det),
    "CONFLICT" -> ("dblp:s2/W1#1", "dblp:s2/W2#1", Disagree),
    "ACCEPTED" -> ("dblp:s3/W1#1", "dblp:s3/W3#1", Det),
    "ACCEPTED" -> ("dblp:s3/W2#1", "dblp:s3/W3#1", Prob),
    "ACCEPTED" -> ("dblp:s4/W1#1", "dblp:s4/W4#1", Prob),
    "ACCEPTED" -> ("dblp:s4/W2#1", "dblp:s4/W4#1", Prob),
    "ACCEPTED" -> ("dblp:s4/W3#1", "dblp:s4/W4#1", Det),
    "CONFLICT" -> ("dblp:s4/W1#1", "dblp:s4/W2#1", Group),
    "CONFLICT" -> ("dblp:s4/W1#1", "dblp:s4/W3#1", Group),
    "CONFLICT" -> ("dblp:s4/W2#1", "dblp:s4/W3#1", Group),
    "ACCEPTED" -> ("openalex:W4001#1", "openalex:W4002#1", Det),
    "UNRESOLVED" -> ("dblp:s5/W1#1", "dblp:s5/W2#1", Llm),
    "ACCEPTED" -> ("dblp:s6/W1#1", "openalex:W6001#1", Det),
    "ACCEPTED" -> ("dblp:s6/W1#2", "openalex:W6001#1", Prob),
    "ACCEPTED" -> ("dblp:s7/W1#1", "dblp:s7/W2#2", Det),
    "ACCEPTED" -> ("dblp:s7/W1#2", "dblp:s7/W2#1", Det),
    "ACCEPTED" -> ("dblp:s10/Missing#1", "dblp:s10/Nope#1", Det)
  )
  val workDecisions: Seq[(String, D)] = Seq(
    "ACCEPTED" -> ("openalex:W6001", "dblp:s6/W1", Exact),
    "ACCEPTED" -> ("openalex:W9001", "dblp:s9/W1", IdAgree),
    "ACCEPTED" -> ("openalex:W9002", "dblp:s9/W1", IdAgree),
    "ACCEPTED" -> ("doi:10.1000/s9", "dblp:s9/W1", Exact),
    "CONFLICT" -> ("openalex:W9001", "openalex:W9002", Group)
  )
  val pairs: Seq[(String, String, Int)] = Seq(
    ("dblp:s6/W1#1", "dblp:s6/W1#2", 2019),
    ("dblp:s7/W1#1", "dblp:s7/W1#2", 2003),
    ("dblp:s7/W2#1", "dblp:s7/W2#2", 2016),
    ("dblp:s10/Missing#1", "dblp:s10/Missing#2", 2010)
  )

  private def enc[A: ReadWriter](a: A) = write(a)

  /** Writes the fixture run and returns its directory. */
  def writeRun(root: Path): Path =
    val run = root.resolve(s"run=$HadoopRunId")
    def put(rel: String, lines: Seq[String]): Unit =
      val d = run.resolve(rel)
      Files.createDirectories(d)
      Files.write(d.resolve("part-r-00000"), lines.mkString("", "\n", "\n").getBytes(UTF_8))
    val manifest = ujson.Obj(
      "runId" -> HadoopRunId,
      "inputSnapshots" -> ujson.Arr("dblp=2026-10-03", "openalex=2026-11-03"),
      "matchPolicyVersion" -> "er-v2"
    )
    Files.createDirectories(run)
    Files.write(run.resolve("_manifest.json"), ujson.write(manifest).getBytes(UTF_8))
    val j09 = "j09-graph-export"
    mentions.groupBy(m => src(m.id)).foreach { (s, ms) =>
      put(
        s"$j09/author-mentions/source_dataset=$s",
        ms.map(m =>
          enc(
            AuthorMention(
              SchemaVersions.AuthorMention,
              m.id,
              m.id.takeWhile(_ != '#'),
              m.id.split('#').last.toInt,
              m.name,
              m.name.toLowerCase,
              m.orcid,
              m.author,
              if m.id == "openalex:W6001#1" then Vector("ror:01") else Vector.empty,
              prov(src(m.id), m.id)
            )
          )
        )
      )
    }
    works.groupBy(w => (src(w.id), w.year)).foreach { case ((s, y), ws) =>
      put(
        s"$j09/works/source_dataset=$s/publication_year=$y",
        ws.map(w =>
          enc(
            WorkRecord(
              SchemaVersions.WorkRecord,
              w.id,
              src(w.id),
              w.id.dropWhile(_ != ':').drop(1),
              Some(s"Title of ${w.id}"),
              Some(s"title of ${w.id}"),
              Some(w.year),
              w.venue,
              Some("article"),
              w.doi,
              None,
              Map.empty,
              Vector.empty,
              None,
              prov(src(w.id), w.id)
            )
          )
        )
      )
    }
    def decisions(entity: MatchEntityType, ds: Seq[(String, D)]): Unit =
      ds.groupBy(_._1).foreach { (outcome, group) =>
        put(
          s"$j09/match-decisions/entity_type=$entity/outcome=$outcome",
          group.map { case (_, (a, b, (rules, conf))) =>
            enc(
              MatchDecision(
                SchemaVersions.MatchDecision,
                entity,
                a,
                b,
                Map.empty,
                rules,
                MatchOutcome.valueOf(outcome),
                conf,
                Vector.empty,
                None,
                "2026-11-04T00:00:00Z",
                Provenance(
                  SchemaVersions.Provenance,
                  SourceType.DERIVED,
                  s"k|$a|$b",
                  "2026-10-03",
                  HadoopRunId,
                  Some("er-v2"),
                  None
                )
              )
            )
          }
        )
      }
    decisions(MatchEntityType.RESEARCHER, researcherDecisions)
    decisions(MatchEntityType.WORK, workDecisions)
    pairs.groupBy(_._3).foreach { (y, ps) =>
      put(
        s"$j09/coauthor-pair-candidates/publication_year=$y",
        ps.map((a, b, _) =>
          enc(
            CoauthorPairCandidate(
              SchemaVersions.CoauthorPairCandidate,
              a,
              b,
              a.takeWhile(_ != '#'),
              Some(y),
              prov(SourceType.DBLP, a.takeWhile(_ != '#'))
            )
          )
        )
      )
    }
    put(
      "j03-j04-exact-identifier/suffix-conflicts",
      Seq(
        s"""{"job":"j03-j04-exact-identifier","identifier":"orcid:$O3","groupSize":3,"rule":"er-v2:orcid-group-homonym-suffix-conflict","policyVersion":"er-v2"}"""
      )
    )
    put(
      "j03-j04-exact-identifier/oversize-conflicts",
      Seq(
        """{"job":"j03-j04-exact-identifier","identifier":"openalex:A9","entityType":"RESEARCHER","groupSize":2,"maxConflictGroupSize":1}"""
      )
    )
    put(
      s"$j09/venues",
      Seq(
        enc(
          Venue(
            SchemaVersions.Venue,
            "dblp-venue:conf/x",
            "X",
            Some("conference"),
            Map.empty,
            prov(SourceType.DBLP, "conf/x")
          )
        )
      )
    )
    put(
      s"$j09/topics",
      Seq(
        enc(
          Topic(
            SchemaVersions.Topic,
            "openalex:T1",
            "Topic One",
            None,
            None,
            prov(SourceType.OPENALEX, "T1")
          )
        )
      )
    )
    put(
      s"$j09/institutions",
      Seq(
        enc(
          Institution(
            SchemaVersions.Institution,
            "ror:01",
            "Inst One",
            Some("US"),
            Map.empty,
            prov(SourceType.OPENALEX, "I1")
          )
        )
      )
    )
    put(
      s"$j09/graph-edges/edge_type=HAS_TOPIC",
      Seq(
        enc(
          GraphEdge(
            SchemaVersions.GraphEdge,
            "ge-" + "1" * 32,
            EdgeType.HAS_TOPIC,
            "openalex:W6001",
            "openalex:T1",
            Map.empty,
            prov(SourceType.OPENALEX, "W6001")
          )
        )
      )
    )
    put(
      s"$j09/graph-edges/edge_type=AFFILIATED_WITH",
      Seq(
        enc(
          GraphEdge(
            SchemaVersions.GraphEdge,
            "ge-" + "2" * 32,
            EdgeType.AFFILIATED_WITH,
            s"orcid:$O1",
            "ror:01",
            Map.empty,
            prov(SourceType.ORCID, O1)
          )
        )
      )
    )
    run
