package scholarchain.storage.cli

import scholarchain.storage.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Streams and validates a directory of store files (STORAGE.md, "CLIs"). Prints counts and, per
  * rule, the violation count and the first --show violations. Exits 1 on any violation.
  */
object ValidateCli:
  val Kinds = Vector("neptune", "dynamodb", "candidates", "rendered", "vectors")
  val UsageText =
    "storage/runMain scholarchain.storage.cli.ValidateCli --kind " + Kinds.mkString("|") +
      " --dir <path> [--bounds <lo>:<hi>] [--evidence-root <s3 fake root>] [--show <n>]"

  def main(args: Array[String]): Unit = Cli.main("ValidateCli", args)(run(_, _))

  def run(args: Seq[String], out: String => Unit, env: Map[String, String] = sys.env): Int =
    Cli.withArgs(
      args,
      Set.empty,
      Set("kind", "dir", "bounds", "evidence-root", "show"),
      UsageText,
      out,
      env
    ) { (a, cfg) =>
      for
        kind <- a
          .req("kind")
          .filterOrElse(Kinds.contains, s"--kind must be one of ${Kinds.mkString(", ")}")
        dir <- a.req("dir").map(Paths.get(_))
        show <- a.int("show", Some(5))
        bounds <- a.get("bounds") match
          case None                                                                   => Right(None)
          case Some(s"$lo:$hi") if lo.toIntOption.nonEmpty && hi.toIntOption.nonEmpty =>
            Right(Some(lo.toInt -> hi.toInt))
          case Some(b) => Left(s"--bounds $b is not <lo>:<hi>")
      yield
        val windows = cfg.graph.windows
        var extra = Vector.empty[String]
        val (violations, counts) = kind match
          case "neptune" =>
            val ev = a.get("evidence-root").map(r => FileObjectStore(Paths.get(r)).reader)
            val s = NeptuneCsv.scan(dir, windows, ev)
            extra = s.evidenceChecks.toVector.sorted.map((k, n) => s"evidence $k $n") ++
              NeptuneCsv.EvidenceRules.map(r => s"rule $r ${s.ruleCounts.getOrElse(r, 0L)}")
            (s.violations, s.counts)
          case "dynamodb"   => dynamo(dir, windows)
          case "candidates" =>
            nonEmpty(dir, ".jsonl")(fs => EmbeddingCandidates.validate(fs, windows, bounds))
          case "rendered" =>
            nonEmpty(dir, ".jsonl")(fs => RenderedDocuments.validate(fs, windows, bounds))
          case _ => nonEmpty(dir, ".jsonl")(VectorRecordFiles.validate)
        out(s"kind $kind")
        out(s"dir $dir")
        counts.toVector.sortBy(_._1).foreach((k, n) => out(s"count $k $n"))
        out(s"count total ${counts.values.sum}")
        extra.foreach(out)
        Cli.report(violations, show, out)
        if violations.isEmpty then
          out("OK")
          0
        else 1
    }

  private def nonEmpty(dir: Path, suffix: String)(
      f: Seq[Path] => (Vector[Violation], Map[String, Int])
  ): (Vector[Violation], Map[String, Long]) =
    val fs = Cli.files(dir, suffix)
    if fs.isEmpty then
      (Vector(Violation(dir.toString, 0, "EMPTY_INPUT", s"no $suffix files")), Map.empty)
    else
      val (v, c) = f(fs)
      (v, c.view.mapValues(_.toLong).toMap)

  /** dir is one table directory (named after the table) or a directory of table directories. */
  private def dynamo(dir: Path, windows: Seq[scholarchain.core.config.TimeWindow]) =
    def table(name: String) = TableName.values.find(_.toString == name)
    val dirs =
      if table(dir.getFileName.toString).nonEmpty then Vector(dir)
      else if !Files.isDirectory(dir) then Vector.empty
      else
        Using.resource(Files.list(dir))(
          _.iterator.asScala.filter(Files.isDirectory(_)).toVector.sortBy(_.toString)
        )
    val results = dirs.map { d =>
      table(d.getFileName.toString) match
        case None => (Vector(Violation(d.toString, 0, "UNKNOWN_TABLE", "not a table name")), None)
        case Some(t) =>
          val fs = Cli.files(d, ".jsonl")
          if fs.isEmpty then
            (Vector(Violation(d.toString, 0, "EMPTY_INPUT", "no .jsonl files")), None)
          else
            val (v, n) = DynamoItemFile.validate(t, fs, windows)
            (v, Some(t.toString -> n))
    }
    val none = Option.when(dirs.isEmpty)(Violation(dir.toString, 0, "EMPTY_INPUT", "no tables"))
    (results.flatMap(_._1) ++ none, results.flatMap(_._2).toMap)
