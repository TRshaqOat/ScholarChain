package scholarchain.services.main

import scholarchain.core.config.ScholarChainConfig
import scholarchain.services.{Apps, HttpService, JsonLog, Launcher}

/** bin/graph-evidence-api: evidence, graph node, and neighbors on services.graph-evidence-api.port.
  * It answers no questions, so it never opens the window-summary object (repair-01 m3).
  */
object GraphEvidenceApi:
  /** What main starts; tests call it with port 0 (Phase 11 N2). */
  def start(
      args: Seq[String],
      env: Map[String, String] = sys.env,
      out: String => Unit = println,
      log: JsonLog = JsonLog.stdout,
      port: ScholarChainConfig => Int = _.services.graphEvidenceApi.port
  ): Either[Int, HttpService] =
    Launcher.start(args, Apps.graphEvidenceApi(_, _, _, _), port, env, out, log, answers = false)

  def main(args: Array[String]): Unit = Launcher.run(start(args.toSeq))
