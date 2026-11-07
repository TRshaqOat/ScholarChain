package scholarchain.rag.cli

import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import scholarchain.storage.cli.{Args, Cli}

/** storage Cli.withArgs with the environment passed in, so tests can set the model ID. */
private[cli] object RagCli:
  def withArgs(
      args: Seq[String],
      flags: Set[String],
      opts: Set[String],
      usage: String,
      out: String => Unit,
      env: Map[String, String]
  )(body: (Args, ScholarChainConfig) => Either[String, Int]): Int =
    val result = for
      a <- Cli.parse(args, flags, opts)
      c <- ConfigLoader.load(env).left.map(_.mkString("; "))
      r <- body(a, c)
    yield r
    result.fold(
      e =>
        out(s"error: $e")
        out(s"usage: $usage")
        Cli.Usage
      ,
      identity
    )
