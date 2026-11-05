package scholarchain.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.sql.SparkSession
import scholarchain.core.StableIds
import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** The end-to-end Spark driver: jobs 1 to 11 in order in one SparkSession, each stage writing its
  * own JobRun directory (CanonicalGraphJob -> ResearcherAnalyticsJob -> CommunityPathJob ->
  * TemporalSummaryExportJob), then a pipeline run directory whose `_manifest.json` names every
  * stage run, the load manifest, and the per-window counts. Stage run IDs are deterministic from
  * their inputs; `--attempt` applies to the pipeline run only.
  *
  * Usage: `sbt "sparkAnalytics/runMain scholarchain.spark.PipelineJob --input <hadoop run dir>
  * --code-version <git sha> [--pairs <csv>] [--shuffle-partitions 40] [--attempt n]"`
  */
object PipelineJob:
  val Stage = "spark-pipeline"

  final case class RunResult(runId: String, attempt: Int, runDir: String, manifest: ujson.Obj)

  def main(args: Array[String]): Unit =
    val s = SparkSettings.parse(args.toSeq) match
      case Right(v) => v
      case Left(e)  => System.err.println(e); sys.exit(2)
    val cfg = ConfigLoader.load().fold(es => sys.error(es.mkString("; ")), identity)
    val spark = JobRun.session(s, Stage)
    try
      val r = run(spark, s, cfg)
      println(s"runId=${r.runId} attempt=${r.attempt} runDir=${r.runDir}")
    finally spark.stop()

  def run(spark: SparkSession, s: SparkSettings, cfg: ScholarChainConfig): RunResult =
    val hconf = spark.sparkContext.hadoopConfiguration
    val inPath = new Path(s.input)
    val ifs = inPath.getFileSystem(hconf)
    val hm = ujson.read(JobRun.readText(ifs, new Path(inPath, "_manifest.json")))
    val pairsText = s.pairs.map(p => JobRun.readText(new Path(p).getFileSystem(hconf), new Path(p)))
    val ws = cfg.graph.windows
    val fingerprints = Seq(
      s"hadoop-run=${hm("runId").str}",
      s"input:hadoop=${s.input}:${ifs.getContentSummary(inPath).getLength}",
      s"config:canonical-policy-version=${s.canonicalPolicyVersion}",
      s"config:graph-policy=${cfg.graph.policyVersion}",
      s"config:windows=${ws.map(w => s"${w.name}:${w.startYear}-${w.endYear}").mkString(",")}",
      s"config:cc-max-iterations=${s.ccMaxIterations}",
      s"config:kcore-max-iterations=${cfg.analytics.kcoreMaxIterations}",
      s"config:community-max-iterations=${s.communityMaxIterations}",
      s"config:path-max-depth=${s.pathMaxDepth}",
      s"config:path-sample-sources=${s.pathSampleSources}",
      s"config:sample-seed=${s.sampleSeed}",
      s"config:summary-min-publications=${s.summaryMinPublications}",
      s"config:summary-min-community-size=${s.summaryMinCommunitySize}",
      pairsText.fold(s"pairs=sample:${s.pathSamplePairs}")(t =>
        s"pairs=file:${StableIds.textHash(t)}"
      )
    )
    val jr = new JobRun(spark, Stage, s, cfg, CanonicalGraphJob.dblpSnapshot(hm), fingerprints)
    val head = jr.head(
      "input" -> ujson.Obj(
        "path" -> s.input,
        "hadoopRunId" -> hm("runId").str,
        "pairsFile" -> s.pairs.fold[ujson.Value](ujson.Null)(ujson.Str(_))
      ),
      "lineage" -> ujson.Obj(
        "inputHadoopRunId" -> hm("runId").str,
        "matchPolicyVersion" -> hm("matchPolicyVersion").str
      ),
      "settings" -> ujson.Obj(
        "master" -> spark.sparkContext.master,
        "shufflePartitions" -> s.shufflePartitions,
        "checkpointDir" -> s.checkpointDir,
        "minFreeDiskGiB" -> s.minFreeDiskGiB.toDouble,
        "driverMaxHeapBytes" -> Runtime.getRuntime.maxMemory.toDouble,
        "availableProcessors" -> Runtime.getRuntime.availableProcessors
      )
    )
    jr.start(head)
    val sub = s.copy(attempt = None)
    // Each stage starts with an empty cache, as a standalone run does; earlier stages leave small
    // inputs cached, and in one JVM they would hold storage memory the later stages need.
    def fresh[A](name: String)(body: => A): A = jr.step(name) { spark.catalog.clearCache(); body }
    val g = fresh("pipeline: jobs 1-2")(CanonicalGraphJob.run(spark, sub, cfg))
    val a = fresh("pipeline: jobs 3, 4, 8") {
      ResearcherAnalyticsJob.run(spark, sub.copy(input = g.runDir), cfg)
    }
    val c =
      fresh("pipeline: jobs 5-7")(CommunityPathJob.run(spark, sub.copy(input = a.runDir), cfg))
    val t = fresh("pipeline: jobs 9-11") {
      TemporalSummaryExportJob.run(spark, sub.copy(input = c.runDir), cfg)
    }
    def stage(jobs: String, runId: String, attempt: Int, dir: String, m: ujson.Obj) = ujson.Obj(
      "jobs" -> jobs,
      "stage" -> m("stage"),
      "runId" -> runId,
      "attempt" -> attempt,
      "runDir" -> dir,
      "wallSeconds" -> m("wallSeconds"),
      "stageCount" -> m("stageCount"),
      "tables" -> m("tables").arr.size,
      "integrityViolationsByCheck" -> m("counts")("integrityViolationsByCheck")
    )
    val manifest = jr.finish(
      head,
      "stages" -> ujson.Arr(
        stage("1, 2", g.runId, g.attempt, g.runDir, g.manifest),
        stage("3, 4, 8", a.runId, a.attempt, a.runDir, a.manifest),
        stage("5, 6, 7", c.runId, c.attempt, c.runDir, c.manifest),
        stage("9, 10, 11", t.runId, t.attempt, t.runDir, t.manifest)
      ),
      "loadManifest" -> t.manifest("loadManifestPath"),
      "perWindow" -> t.manifest("counts")("windowSummary"),
      "summariesByTypeAndWindow" -> t.manifest("counts")("summariesByTypeAndWindow")
    )
    RunResult(jr.runId, jr.attempt, jr.runDir.toString, manifest)
