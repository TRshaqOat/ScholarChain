package scholarchain.storage.cli

import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import scholarchain.storage.{Hashing, Violation}
import scholarchain.storage.live.LiveTarget

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.{Random, Using}

/** Parsed command line: --name value options and --flag switches. */
final case class Args(opts: Map[String, String], flags: Set[String]):
  def get(k: String): Option[String] = opts.get(k)
  def req(k: String): Either[String, String] = opts.get(k).toRight(s"missing --$k")
  def flag(k: String): Boolean = flags(k)
  def int(k: String, default: Option[Int]): Either[String, Int] =
    opts.get(k) match
      case Some(v) => v.toIntOption.toRight(s"--$k must be an integer")
      case None    => default.toRight(s"missing --$k")

/** Shared CLI plumbing. Exit codes: 0 success, 1 violations or failed work, 2 usage error, 3 not
  * enough free disk (ruling 27: BLOCKED-ON, under 5 GiB).
  */
object Cli:
  val Usage = 2
  val LowDisk = 3
  val MinFreeBytes: Long = 5L << 30
  val LiveFlags = Set("live")
  val LiveOpts = Set("profile", "region")

  def parse(
      args: Seq[String],
      flagNames: Set[String],
      optNames: Set[String]
  ): Either[String, Args] =
    def go(rest: List[String], acc: Args): Either[String, Args] = rest match
      case Nil                             => Right(acc)
      case s"--$f" :: tail if flagNames(f) => go(tail, acc.copy(flags = acc.flags + f))
      case s"--$o" :: v :: tail if optNames(o) && !v.startsWith("--") =>
        go(tail, acc.copy(opts = acc.opts + (o -> v)))
      case x :: _ => Left(s"unexpected argument '$x'")
    go(args.toList, Args(Map.empty, Set.empty))

  /** Live calls need both --live and --profile (rulings 1 and 26); both are off by default. */
  def liveTarget(a: Args, defaultRegion: String): Either[String, Option[LiveTarget]] =
    (a.flag("live"), a.get("profile")) match
      case (false, None)    => Right(None)
      case (true, Some(p))  => Right(Some(LiveTarget(p, a.get("region").getOrElse(defaultRegion))))
      case (true, None)     => Left("--live needs --profile <name>")
      case (false, Some(_)) => Left("--profile is used only with --live")

  def config(env: Map[String, String] = sys.env): Either[String, ScholarChainConfig] =
    ConfigLoader.load(env).left.map(_.mkString("; "))

  /** Live store loads use names from a non-local configuration (placeholders are rejected there).
    */
  def liveConfig(cfg: ScholarChainConfig): Either[String, Unit] =
    Either.cond(
      cfg.environment != "local",
      (),
      "live loads need SCHOLARCHAIN_ENVIRONMENT set to a non-local environment"
    )

  def freeDiskProblem(dir: Path): Option[String] =
    val existing =
      Iterator.iterate(dir.toAbsolutePath)(_.getParent).takeWhile(_ != null).find(Files.exists(_))
    existing.flatMap { p =>
      val free = Files.getFileStore(p).getUsableSpace
      Option.when(free < MinFreeBytes)(
        s"BLOCKED-ON: ${free >> 20} MiB free under $p, below the 5 GiB floor (ruling 27)"
      )
    }

  def files(dir: Path, suffix: String): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      Using.resource(Files.walk(dir))(
        _.iterator.asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(suffix))
          .toVector
          .sortBy(_.toString)
      )

  /** The snapshot=<d> and run=<r> segments of a store path. */
  def runSegments(p: Path): Option[(String, String)] =
    val segs = p.toAbsolutePath.normalize.iterator.asScala.map(_.toString).toVector
    for
      s <- segs.collectFirst { case s"snapshot=$d" => d }
      r <- segs.collectFirst { case s"run=$r" => r }
    yield (s, r)

  /** sha256 over the relative names and sha256 of the files: one value for a whole input set. */
  def contentHash(root: Path, fs: Seq[Path]): String =
    Hashing.sha256(
      fs.map(f => s"${root.relativize(f)} ${Hashing.sha256(Files.readAllBytes(f))}").mkString("\n")
    )

  def now(): String = Instant.now.toString

  /** Backoff sleep with equal jitter for live calls (half fixed, half random). */
  def jitteredSleep(ms: Long): Unit = Thread.sleep(ms / 2 + Random.nextLong(ms / 2 + 1))

  /** Prints violations grouped by rule: the count and the first `show` of each. */
  def report(vs: Vector[Violation], show: Int, out: String => Unit): Unit =
    out(s"violations ${vs.size}")
    vs.groupBy(_.code).toVector.sortBy(_._1).foreach { (code, xs) =>
      out(s"  $code ${xs.size}")
      xs.take(show).foreach(v => out(s"    ${v.source}:${v.line} ${v.detail}"))
    }

  /** Entry point: run, print an error for an uncaught failure, exit only when non-zero (so sbt can
    * run several mains in one session).
    */
  def main(name: String, args: Array[String])(run: (Seq[String], String => Unit) => Int): Unit =
    val code = exitCode(name, args.toSeq, println)(run)
    if code != 0 then sys.exit(code)

  /** run's exit code. Any Throwable, including a JVM error such as OutOfMemoryError, gives 1 (M1),
    * so a failure can never end as success.
    */
  def exitCode(name: String, args: Seq[String], out: String => Unit)(
      run: (Seq[String], String => Unit) => Int
  ): Int =
    try run(args, out)
    catch
      case e: Throwable =>
        try out(s"$name failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        catch case _: Throwable => () // printing can fail too (out of memory); the code still holds
        1

  /** Runs body with parsed args and configuration; usage problems print and return 2. */
  def withArgs(
      args: Seq[String],
      flags: Set[String],
      opts: Set[String],
      usage: String,
      out: String => Unit,
      env: Map[String, String] = sys.env
  )(
      body: (Args, ScholarChainConfig) => Either[String, Int]
  ): Int =
    val result = for
      a <- parse(args, flags, opts)
      c <- config(env)
      r <- body(a, c)
    yield r
    result.fold(
      e =>
        out(s"error: $e")
        out(s"usage: $usage")
        Usage
      ,
      identity
    )
