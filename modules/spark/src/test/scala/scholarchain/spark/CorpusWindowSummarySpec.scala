package scholarchain.spark

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.TimeWindow

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Corpus-level window summaries (Phase 10 work order 7). Expected values are worked out by hand.
  */
class CorpusWindowSummarySpec extends AnyFunSuite:
  private lazy val spark = TestSpark.spark

  private def df(schema: String, rows: Seq[Any]*): DataFrame =
    spark.createDataFrame(
      java.util.Arrays.asList(rows.map(r => Row.fromSeq(r))*),
      StructType.fromDDL(schema)
    )

  // Window e (2000-2009): triangle a-b-c (component X, community E1) and isolated d (Y, E2).
  //   edges a-b (2 joint works), a-c (1), b-c (1): 3 edges, 4 joint works.
  // Window l (2010-2020): a-b (1) and d-e (3); components P {a,b} and Q {d,e} tie at 2, so the
  //   largest is P (smallest ID); communities L1 {a,b} and L2 {d,e} tie the same way.
  // Window z (2021-2022): no researcher at all.
  private val windows =
    CoauthorGraph.windows(
      spark,
      Seq(TimeWindow("e", 2000, 2009), TimeWindow("l", 2010, 2020), TimeWindow("z", 2021, 2022))
    )
  private val metrics = df(
    "window STRING, researcherId STRING",
    Seq("e", "a"),
    Seq("e", "b"),
    Seq("e", "c"),
    Seq("e", "d"),
    Seq("l", "a"),
    Seq("l", "b"),
    Seq("l", "d"),
    Seq("l", "e")
  )
  private val edges = df(
    "window STRING, jointPublicationCount BIGINT",
    Seq("e", 2L),
    Seq("e", 1L),
    Seq("e", 1L),
    Seq("l", 1L),
    Seq("l", 3L)
  )
  private val components = df(
    "window STRING, componentId STRING, componentSize BIGINT",
    Seq("e", "X", 3L),
    Seq("e", "Y", 1L),
    Seq("l", "Q", 2L),
    Seq("l", "P", 2L)
  )
  private val communities = df(
    "window STRING, communityId STRING, communitySize BIGINT",
    Seq("e", "E1", 3L),
    Seq("e", "E2", 1L),
    Seq("l", "L2", 2L),
    Seq("l", "L1", 2L)
  )
  private def summary(m: DataFrame = metrics) =
    CorpusWindowSummaryJob.summarize(windows, m, edges, components, communities, 3)

  test("per window: nodes, edges, components, largest component, singletons, communities") {
    val cols = Seq(
      "window",
      "years",
      "researchers",
      "coauthorEdges",
      "sumJointPublicationCount",
      "components",
      "largestComponentId",
      "largestComponentSize",
      "largestComponentFraction",
      "singletonResearchers",
      "communities",
      "largestCommunityId",
      "largestCommunitySize",
      "largestCommunityFraction",
      "singletonCommunities",
      "communityMinSize",
      "communitiesAtLeastMinSize"
    )
    val got = summary().select(cols.map(col)*).orderBy("window").collect().map(_.toSeq).toSeq
    assert(
      got == Seq(
        Seq("e", 10, 4L, 3L, 4L, 2L, "X", 3L, 0.75, 1L, 2L, "E1", 3L, 0.75, 1L, 3, 1L),
        Seq("l", 11, 4L, 2L, 4L, 2L, "P", 2L, 0.5, 0L, 2L, "L1", 2L, 0.5, 0L, 3, 0L),
        Seq("z", 2, 0L, 0L, 0L, 0L, null, 0L, null, 0L, 0L, null, 0L, null, 0L, 3, 0L)
      )
    )
  }

  test("checks: member sums and every shared value against the Phase 6 window summary") {
    val s = summary()
    val same = s.select(
      (col("window") +: (CorpusWindowSummaryJob.Phase6Checked :+ "communitiesAtLeastMinSize")
        .map(col))*
    )
    assert(CorpusWindowSummaryJob.violations(s, same, sameMinSize = true).count() == 0)
    // Phase 6 differs on e.components and has no row for l or z.
    val tampered = same.where(col("window") === "e").withColumn("components", lit(9L))
    def got(v: DataFrame) =
      v.select("check", "subjectId", "relatedId").collect().map(r => (r(0), r(1), r(2))).toSet
    val p6 = got(CorpusWindowSummaryJob.violations(s, tampered, sameMinSize = false))
    val missing = for
      w <- Seq("l", "z")
      c <- CorpusWindowSummaryJob.Phase6Checked
    yield ("PHASE6_WINDOW_SUMMARY_MISMATCH", w, c)
    assert(p6 == (missing :+ ("PHASE6_WINDOW_SUMMARY_MISMATCH", "e", "components")).toSet)
    // The threshold column is compared only when both runs used the same minimum size (then the
    // tampered e value and the missing l and z rows fail it too).
    val thr = tampered.withColumn("communitiesAtLeastMinSize", lit(7L))
    assert(
      got(CorpusWindowSummaryJob.violations(s, thr, sameMinSize = true)) -- p6 ==
        Seq("e", "l", "z")
          .map(w => ("PHASE6_WINDOW_SUMMARY_MISMATCH", w, "communitiesAtLeastMinSize"))
          .toSet
    )
    // A node missing from the metrics leaves component and community sizes summing past it.
    val short = summary(metrics.where(!(col("window") === "e" && col("researcherId") === "d")))
    assert(
      got(CorpusWindowSummaryJob.violations(short, same, sameMinSize = true))
        .filterNot(_._1 == "PHASE6_WINDOW_SUMMARY_MISMATCH") == Set(
        ("COMPONENT_MEMBERS_MISMATCH", "e", "componentMembers"),
        ("COMMUNITY_MEMBERS_MISMATCH", "e", "communityMembers")
      )
    )
  }

  // ---- End to end on the Fixture pipeline: a new run that leaves the Phase 6 runs untouched ------
  test("the job writes a new run equal to the Phase 6 window summary, with provenance") {
    val p = PipelineFixture.result
    val st = p.manifest("stages").arr
    val dirs = st.map(_("runDir").str).toSeq
    val (cgDir, raDir, cpDir, exDir) = (dirs(0), dirs(1), dirs(2), dirs(3))
    def listing(dir: String) =
      Files.walk(Path.of(dir)).iterator.asScala.map(f => f.toString -> f.toFile.lastModified).toSet
    val before = Seq(cgDir, raDir, cpDir, exDir).map(listing)
    val tmp = Files.createTempDirectory("sc-corpus")
    val s = PipelineFixture.base.copy(
      input = exDir,
      out = tmp.resolve("out").toString,
      checkpointDir = tmp.resolve("ck").toString
    )
    val r = CorpusWindowSummaryJob.run(spark, s, PipelineFixture.cfg)
    assert(Seq(cgDir, raDir, cpDir, exDir).map(listing) == before)
    assert(r.manifest("stage").str == CorpusWindowSummaryJob.Stage)
    assert(r.manifest("counts")("integrityViolations").arr.isEmpty)

    val t = spark.read.parquet(s"${r.runDir}/${CorpusWindowSummaryJob.Table}")
    val names = PipelineFixture.cfg.graph.windows.map(_.name)
    assert(t.count() == names.size)
    val phase6 = spark.read.parquet(s"$exDir/temporal/window-summary")
    val shared = "window" +: CorpusWindowSummaryJob.Phase6Checked :+ "communitiesAtLeastMinSize"
    def rows(d: DataFrame) = d.select(shared.map(c => col(c).cast("string"))*).collect().toSet
    assert(rows(t) == rows(phase6))
    assert(t.where(col("schemaVersion") =!= "spark-corpus-window-summary-v1").count() == 0)
    assert(
      t.where(
        col("provenance.runId") =!= r.runId || col("provenance.sourceRecordId") =!= col("window") ||
          col("provenance.sourceDataset") =!= "DERIVED" ||
          col("sourceRuns.temporalSummaryExportRunId") =!= st(3)("runId").str ||
          col("sourceRuns.communityPathRunId") =!= st(2)("runId").str ||
          col("sourceRuns.researcherAnalyticsRunId") =!= st(1)("runId").str ||
          col("sourceRuns.canonicalGraphRunId") =!= st(0)("runId").str ||
          col("sourceRuns.inputHadoopRunId") =!= Fixture.HadoopRunId ||
          col("nodeBasis") =!= CorpusWindowSummaryJob.NodeBasis
      ).count() == 0
    )
    // The largest component's ID names a component of that size in the Phase 6 table.
    val comps = spark.read.parquet(s"$raDir/analytics/components")
    assert(
      t.join(
        comps,
        t("window") === comps("window") && t("largestComponentId") === comps("componentId") &&
          t("largestComponentSize") === comps("componentSize")
      ).count() == t.where(col("largestComponentId").isNotNull).count()
    )
    // A rerun is the next attempt with the same values.
    val again = CorpusWindowSummaryJob.run(spark, s, PipelineFixture.cfg)
    assert(again.attempt == 2 && again.runDir != r.runDir)
    def values(dir: String) = spark.read
      .parquet(s"$dir/${CorpusWindowSummaryJob.Table}")
      .drop("provenance")
      .collect()
      .map(_.toSeq)
      .toSet
    assert(values(again.runDir) == values(r.runDir))
  }
