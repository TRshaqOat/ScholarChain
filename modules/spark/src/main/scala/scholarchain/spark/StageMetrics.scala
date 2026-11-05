package scholarchain.spark

import org.apache.spark.scheduler.{
  SparkListener,
  SparkListenerStageCompleted,
  SparkListenerStageSubmitted
}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Per-stage task, shuffle, and I/O metrics, labelled with the job description (the pipeline step)
  * active when the stage was submitted. Written to `_stages.json` and summarized in the manifest.
  */
final class StageMetrics extends SparkListener:
  private val steps = new ConcurrentHashMap[Int, String]()
  private val rows = new ConcurrentHashMap[Int, ujson.Obj]()

  override def onStageSubmitted(e: SparkListenerStageSubmitted): Unit =
    val d = Option(e.properties).flatMap(p => Option(p.getProperty("spark.job.description")))
    steps.put(e.stageInfo.stageId, d.getOrElse("(none)"))

  override def onStageCompleted(e: SparkListenerStageCompleted): Unit =
    val i = e.stageInfo
    val m = i.taskMetrics
    val ms = for s <- i.submissionTime; c <- i.completionTime yield c - s
    rows.put(
      i.stageId,
      ujson.Obj(
        "stageId" -> i.stageId,
        "attempt" -> i.attemptNumber(),
        "step" -> steps.getOrDefault(i.stageId, "(none)"),
        "name" -> i.name,
        "numTasks" -> i.numTasks,
        "durationMs" -> ms.getOrElse(0L).toDouble,
        "inputBytes" -> m.inputMetrics.bytesRead.toDouble,
        "outputBytes" -> m.outputMetrics.bytesWritten.toDouble,
        "shuffleReadBytes" -> m.shuffleReadMetrics.totalBytesRead.toDouble,
        "shuffleReadRecords" -> m.shuffleReadMetrics.recordsRead.toDouble,
        "shuffleWriteBytes" -> m.shuffleWriteMetrics.bytesWritten.toDouble,
        "shuffleWriteRecords" -> m.shuffleWriteMetrics.recordsWritten.toDouble,
        "spillDiskBytes" -> m.diskBytesSpilled.toDouble,
        "failed" -> i.failureReason.isDefined
      )
    )

  def stages: Seq[ujson.Obj] = rows.values.asScala.toSeq.sortBy(_("stageId").num)

  /** Totals per step: stages, tasks, shuffle read and write bytes, wall time of its stages. */
  def byStep: ujson.Arr =
    ujson.Arr.from(
      stages
        .groupBy(_("step").str)
        .toSeq
        .sortBy(_._2.map(_("stageId").num).min)
        .map((step, ss) =>
          def total(k: String) = ss.map(_(k).num).sum
          ujson.Obj(
            "step" -> step,
            "stages" -> ss.size,
            "tasks" -> total("numTasks"),
            "maxTasksInAStage" -> ss.map(_("numTasks").num).max,
            "shuffleReadBytes" -> total("shuffleReadBytes"),
            "shuffleWriteBytes" -> total("shuffleWriteBytes"),
            "spillDiskBytes" -> total("spillDiskBytes"),
            "stageMs" -> total("durationMs")
          )
        )
    )
