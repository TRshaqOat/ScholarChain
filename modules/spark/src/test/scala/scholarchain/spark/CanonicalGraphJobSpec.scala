package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader
import scholarchain.core.{CanonicalPair, StableIds}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Jobs 1 and 2 end to end on the hand-built Fixture run. Expected values are worked out by hand
  * from the scenario comments in Fixture; IDs come from the core StableIds functions.
  */
class CanonicalGraphJobSpec extends AnyFunSuite:
  import Fixture.*
  private lazy val spark = TestSpark.spark
  private lazy val tmp = Files.createTempDirectory("sc-spark-job")
  private lazy val input = Fixture.writeRun(tmp.resolve("hadoop"))
  private lazy val settings = SparkSettings(
    input = input.toString,
    out = tmp.resolve("out").toString,
    codeVersion = "test",
    attempt = None,
    master = "local[2]",
    shufflePartitions = 2,
    checkpointDir = tmp.resolve("ck").toString,
    canonicalPolicyVersion = "canon-v1",
    ccMaxIterations = 30,
    minFreeDiskGiB = 0,
    pairs = None,
    communityMaxIterations = 10,
    pathMaxDepth = 6,
    pathSamplePairs = 50,
    pathSampleSources = 20,
    sampleSeed = 1,
    summaryMinPublications = 2,
    summaryMinCommunitySize = 3
  )
  private lazy val cfg = ConfigLoader.load(Map.empty).toOption.get
  private lazy val first = CanonicalGraphJob.run(spark, settings, cfg)
  private lazy val second = CanonicalGraphJob.run(spark, settings, cfg)

  private def table(name: String, run: CanonicalGraphJob.RunResult = first) =
    spark.read.parquet(s"${run.runDir}/$name")
  private def byKey(name: String, key: String): Map[String, Row] =
    table(name).collect().map(r => r.getAs[String](key) -> r).toMap
  private lazy val mentions = byKey("canonical/mention-map", "mentionId")
  private lazy val researchers = byKey("canonical/researchers", "researcherId")
  private def researcherOf(m: String) = mentions(m).getAs[String]("researcherId")
  private def tierReason(m: String) =
    (
      mentions(m).getAs[String]("assignedTier"),
      Option(mentions(m).getAs[String]("separationReason"))
    )
  private def rules(r: Row, c: String) =
    Option(r.getAs[scala.collection.Seq[String]](c)).map(_.toSeq)

  test("fixture ORCID iDs are valid") {
    Seq(O1, O2, O3).foreach(o => assert(StableIds.normalizeOrcid(o).contains(o)))
  }

  test("chain A-B-C with two ORCIDs is refused at tier 2; A and B keep their tier-1 cluster") {
    val (a, b, c) = ("dblp:s1/W1#1", "dblp:s1/W2#1", "dblp:s1/W3#1")
    assert(researcherOf(a) == rid(a) && researcherOf(b) == rid(a))
    assert(researcherOf(c) == rid(c))
    assert(tierReason(a) == ("TIER1", Some("REFUSED_TIER2")))
    assert(tierReason(c) == ("SINGLETON", Some("REFUSED_TIER2")))
    assert(rules(mentions(a), "tier2RefusalRules").contains(Seq("TWO_VALID_ORCIDS")))
    assert(rules(mentions(a), "tier1RefusalRules").isEmpty)
    val r = researchers(rid(a))
    assert(r.getAs[String]("displayName") == "Alice Chain")
    assert(r.getAs[String]("orcid") == O1 && r.getAs[Long]("mentionCount") == 2L)
    assert(r.getAs[Double]("identityConfidence") == 0.95) // min of Det 0.95 and Exact 1.0
    assert(r.getAs[String]("uncertainty").contains("TWO_VALID_ORCIDS"))
    assert(researchers(rid(c)).getAs[String]("orcid") == O2)
  }

  test("a CONFLICT decision inside a tier-1 component refuses it; every member stays apart") {
    Seq("dblp:s2/W1#1", "openalex:W2001#1", "dblp:s2/W2#1").foreach { m =>
      assert(researcherOf(m) == rid(m))
      assert(tierReason(m) == ("SINGLETON", Some("REFUSED_TIER1")))
      assert(rules(mentions(m), "tier1RefusalRules").contains(Seq("CONFLICT_DECISION")))
    }
  }

  test("DBLP suffixes 0001 and 0002 in one component refuse the tier-2 cluster") {
    val (g, h, i) = ("dblp:s3/W1#1", "dblp:s3/W2#1", "dblp:s3/W3#1")
    assert(researcherOf(g) == rid(g) && researcherOf(i) == rid(g))
    assert(researcherOf(h) == rid(h))
    assert(rules(mentions(h), "tier2RefusalRules").contains(Seq("HOMONYM_SUFFIX_DISAGREEMENT")))
    assert(tierReason(i) == ("TIER1", Some("REFUSED_TIER2")))
  }

  test("a suffix-conflict group of three plus an unsuffixed mention") {
    val (j, k, l, m) = ("dblp:s4/W1#1", "dblp:s4/W2#1", "dblp:s4/W3#1", "dblp:s4/W4#1")
    // Tier 2 {J, K, L, M} breaks 5b (J03 conflict pairs), 5c (0001 vs 0002), and 5d (3 members).
    assert(
      rules(mentions(m), "tier2RefusalRules")
        .contains(
          Seq("CONFLICT_DECISION", "HOMONYM_SUFFIX_DISAGREEMENT", "IDENTIFIER_CONFLICT_GROUP")
        )
    )
    // Tier 1 {L, M} holds one group member and is kept; J and K are alone.
    assert(researcherOf(l) == rid(l) && researcherOf(m) == rid(l))
    assert(tierReason(m) == ("TIER1", Some("REFUSED_TIER2")))
    assert(researcherOf(j) == rid(j) && researcherOf(k) == rid(k))
    val check = first
      .manifest("counts")("researcherConflictGroupCheck")
      .arr
      .map(o => o("identifier").str -> (o("groupSize").num, o("memberCount").num))
      .toMap
    assert(check == Map(s"orcid:$O3" -> (3.0, 3.0), "openalex:A9" -> (2.0, 2.0)))
  }

  test(
    "two members of an oversize-conflict group linked at tier 1 are refused at tier 1 (rule 5d)"
  ) {
    Seq("openalex:W4001#1", "openalex:W4002#1").foreach { n =>
      assert(researcherOf(n) == rid(n))
      assert(tierReason(n) == ("SINGLETON", Some("REFUSED_TIER1")))
      assert(rules(mentions(n), "tier1RefusalRules").contains(Seq("IDENTIFIER_CONFLICT_GROUP")))
    }
  }

  test("UNRESOLVED mentions stay separate canonical researchers") {
    assert(researcherOf("dblp:s5/W1#1") == rid("dblp:s5/W1#1"))
    assert(researcherOf("dblp:s5/W2#1") == rid("dblp:s5/W2#1"))
    assert(tierReason("dblp:s5/W1#1") == ("SINGLETON", Some("UNRESOLVED")))
    assert(tierReason("dblp:s5/W3#1") == ("SINGLETON", Some("NO_ACCEPTED_LINK")))
    assert(researchers(rid("dblp:s5/W1#1")).getAs[String]("uncertainty").contains("UNRESOLVED"))
  }

  test("a self-pair created by a merge is dropped and counted; AUTHORED lists all its mentions") {
    val s = rid("dblp:s6/W1#1")
    Seq("dblp:s6/W1#1", "dblp:s6/W1#2", "openalex:W6001#1").foreach(m =>
      assert(researcherOf(m) == s)
    )
    assert(tierReason("dblp:s6/W1#2") == ("TIER2", None))
    val self = table("graph/self-pairs").collect()
    assert(self.length == 1 && self.head.getAs[String]("researcherId") == s)
    assert(first.manifest("counts")("selfPairsDropped").num == 1)
    val auth = table("canonical/authored").where("workId = 'dblp:s6/W1'").collect()
    assert(auth.length == 1)
    assert(
      auth.head.getAs[scala.collection.Seq[String]]("mentionIds").toSeq ==
        Seq("dblp:s6/W1#1", "dblp:s6/W1#2", "openalex:W6001#1")
    )
    assert(auth.head.getAs[Int]("position") == 1)
  }

  test(
    "duplicate pairs across two works in different years aggregate per window, in canonical order"
  ) {
    val p = rid("dblp:s7/W1#1") // Pat: anchor s7/W1#1
    val q = rid("dblp:s7/W1#2") // Quinn: anchor min(s7/W1#2, s7/W2#1) = s7/W1#2
    val (a, b) = CanonicalPair(p, q).toOption.get
    val edges =
      table("graph/coauthor-edges").collect().map(r => r.getAs[String]("window") -> r).toMap
    assert(edges.keySet == Set("all-years", "2000-2014", "2015-2026"))
    def check(w: String, s: Int, e: Int, n: Long, first: Int, last: Int, ws: Seq[String]) =
      val r = edges(w)
      assert(r.getAs[String]("researcherA") == a && r.getAs[String]("researcherB") == b)
      assert(r.getAs[Long]("jointPublicationCount") == n)
      assert(r.getAs[Int]("firstCollaborationYear") == first)
      assert(r.getAs[Int]("lastCollaborationYear") == last)
      assert(r.getAs[scala.collection.Seq[String]]("representativeWorkIds").toSeq == ws)
      assert(
        r.getAs[String]("edgeId") == StableIds.coauthorEdgeId(q, p, s, e, "graph-v1").toOption.get
      )
    check("all-years", 2000, 2026, 2, 2003, 2016, Seq("dblp:s7/W1", "dblp:s7/W2"))
    check("2000-2014", 2000, 2014, 1, 2003, 2003, Seq("dblp:s7/W1"))
    check("2015-2026", 2015, 2026, 1, 2016, 2016, Seq("dblp:s7/W2"))
  }

  test(
    "a work cluster with a DOI conflict is refused; a clean DOI match merges onto the DBLP work"
  ) {
    val works = byKey("canonical/work-map", "sourceWorkId")
    Seq("dblp:s9/W1", "openalex:W9001", "openalex:W9002", "doi:10.1000/s9").foreach { w =>
      assert(works(w).getAs[String]("workId") == w)
      assert(works(w).getAs[String]("separationReason") == "REFUSED_TIER1")
    }
    assert(works("openalex:W6001").getAs[String]("workId") == "dblp:s6/W1")
    assert(works("openalex:W6001").getAs[String]("assignedTier") == "TIER2")
    val wt = table("canonical/work-topics").collect()
    assert(
      wt.map(r => (r.getAs[String]("workId"), r.getAs[String]("topicId"))).toSeq == Seq(
        ("dblp:s6/W1", "openalex:T1")
      )
    )
  }

  test("counts: researchers, works, AUTHORED, edges, refusals, integrity violations") {
    val c = first.manifest("counts")
    // 26 mentions -> 19 researchers; 26 source works -> 25 canonical works (W6001 merges).
    assert(c("researchers").num == 19 && c("works").num == 25)
    // 26 mentions, minus the ghost (no work), minus 2 (three s6 mentions make one edge).
    assert(c("authoredEdges").num == 23)
    def counted(k: String) =
      c(k).arr.map(o => (o("tier").str, o("rule").str) -> o("count").num).toMap
    assert(
      counted("researcherRefusalsByTierAndRule") == Map(
        ("TIER1", "CONFLICT_DECISION") -> 1.0,
        ("TIER1", "IDENTIFIER_CONFLICT_GROUP") -> 1.0,
        ("TIER2", "CONFLICT_DECISION") -> 2.0,
        ("TIER2", "HOMONYM_SUFFIX_DISAGREEMENT") -> 2.0,
        ("TIER2", "IDENTIFIER_CONFLICT_GROUP") -> 2.0,
        ("TIER2", "TWO_VALID_ORCIDS") -> 1.0
      )
    )
    assert(
      counted("workRefusalsByTierAndRule") ==
        Map(("TIER1", "CONFLICT_DECISION") -> 1.0, ("TIER2", "CONFLICT_DECISION") -> 1.0)
    )
    val violations =
      c("integrityViolationsByCheck").arr.map(o => o("check").str -> o("count").num).toMap
    assert(
      violations == Map(
        "AUTHORED_WORK_MISSING" -> 1.0,
        "PAIR_ENDPOINT_MISSING" -> 1.0,
        "PAIR_WORK_MISSING" -> 1.0,
        "RESEARCHER_DECISION_ENDPOINT_MISSING" -> 1.0,
        "WORK_VENUE_MISSING" -> 1.0
      )
    )
    assert(table("integrity-violations").count() == 5)
    val perWindow = c("perWindow").arr.map(o => o("window").str -> o("coauthorEdges").num).toMap
    assert(perWindow == Map("all-years" -> 1.0, "2000-2014" -> 1.0, "2015-2026" -> 1.0))
    val ri = table("canonical/researcher-institutions")
      .collect()
      .map(r =>
        (r.getAs[String]("researcherId"), r.getAs[scala.collection.Seq[String]]("via").toSeq)
      )
      .toSet
    assert(
      ri == Set(
        (rid("dblp:s6/W1#1"), Seq("MENTION")),
        (rid("dblp:s1/W1#1"), Seq("ORCID_EMPLOYMENT"))
      )
    )
  }

  test(
    "the manifest lists every input with its count and every table with path, schema, and rows"
  ) {
    val inputs = first
      .manifest("inputs")
      .arr
      .map(o => o("path").str.drop(input.toString.length + 1) -> o("rows").num)
      .toMap
    assert(inputs("j09-graph-export/author-mentions") == 26)
    assert(inputs("j09-graph-export/match-decisions/entity_type=RESEARCHER/outcome=ACCEPTED") == 17)
    assert(inputs("j09-graph-export/match-decisions/entity_type=WORK/outcome=UNRESOLVED") == 0)
    val tables = first.manifest("tables").arr.map(o => o("name").str -> o).toMap
    assert(tables("canonical/researchers")("rowCount").num == 19)
    assert(tables("graph/coauthor-edges")("partitionColumns").arr.map(_.str).toSeq == Seq("window"))
    assert(tables.values.forall(t => t("schema").str.nonEmpty && t("intendedTargets").arr.nonEmpty))
    assert(tables("canonical/researchers")("fileCount").num >= 1)
    val c = first.manifest("counts")
    assert(c("largestDblpTeams").arr.head("teamSize").num == 2) // s6/W1, s7/W1, s7/W2
    assert(c("worksWithSeveralDblpMembers").num == 0)
    assert(
      c("highestDegreeResearchersWidestWindow").arr.map(_("degree").num).toSeq == Seq(1.0, 1.0)
    )
  }

  test("every table carries schemaVersion and this run's provenance; maps point at sources") {
    val tables = first.manifest("tables").arr.map(_("name").str).toSeq
    assert(tables.size == 15)
    for t <- tables do
      val df = table(t)
      assert(JobRun.provenanceProblems(df.schema).isEmpty, t)
      // Read back from the run directory: every row names this run, the snapshot, the table version.
      val stamps = df
        .select(
          col("schemaVersion"),
          col("provenance.runId"),
          col("provenance.snapshot"),
          col("provenance.sourceRecordId").isNull
        )
        .distinct()
        .collect()
        .map(r => (r.getString(0), r.getString(1), r.getString(2), r.getBoolean(3)))
        .toSet
      assert(
        stamps.forall(_ == (s"spark-${t.replace('/', '-')}-v1", first.runId, "2026-10-03", false)),
        t
      )
    // Fixture: a mention's J09 sourceRecordId is its mention ID; a work's is its ID without prefix.
    assert(mentions("dblp:s1/W1#1").getAs[String]("sourceRecordId") == "dblp:s1/W1#1")
    assert(mentions("dblp:s1/W1#1").getAs[String]("sourceDataset") == "DBLP")
    val w = byKey("canonical/work-map", "sourceWorkId")("openalex:W2001")
    assert(
      (w.getAs[String]("sourceDataset"), w.getAs[String]("sourceRecordId")) == ("OPENALEX", "W2001")
    )
    // Passed-through source entities keep their J09 provenance beside this run's.
    val v = table("canonical/venues").select("provenance.runId", "sourceProvenance.runId").head()
    assert((v.getString(0), v.getString(1)) == (first.runId, HadoopRunId))
  }

  test("a rerun gets the next attempt, a new run ID, and identical tables") {
    assert(first.attempt == 1 && second.attempt == 2 && first.runId != second.runId)
    for t <- Seq(
        "canonical/mention-map",
        "canonical/researchers",
        "canonical/works",
        "canonical/authored"
      )
    do
      val cols = table(t).columns.filterNot(_ == "provenance").toSeq
      def rowsOf(r: CanonicalGraphJob.RunResult) =
        table(t, r)
          .select(cols.map(org.apache.spark.sql.functions.col)*)
          .collect()
          .map(_.toString)
          .toSet
      assert(rowsOf(first) == rowsOf(second), t)
    val e1 = table("graph/coauthor-edges").select("edgeId").collect().map(_.getString(0)).toSet
    assert(
      e1 == table("graph/coauthor-edges", second)
        .select("edgeId")
        .collect()
        .map(_.getString(0))
        .toSet
    )
    assert(Files.exists(java.nio.file.Paths.get(second.runDir, "_manifest.json")))
    assert(Files.list(tmp.resolve("ck")).iterator().asScala.isEmpty) // checkpoints removed
  }
