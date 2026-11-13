package scholarchain.services.main

import scholarchain.services.{Apps, HttpService, JsonLog, Launcher}

/** The local runner (ruling 13, runMain only): query-api, graph-evidence-api, and worker in one JVM
  * on 127.0.0.1, sharing one fake-mode backend (so the in-memory job queue and the file-backed
  * evidence are shared). Fake mode only: --live is refused.
  */
object LocalRun:
  /** The started services, or the exit code. port maps each configured port (tests pass 0). */
  def start(
      args: Seq[String],
      env: Map[String, String] = sys.env,
      out: String => Unit = println,
      log: JsonLog = JsonLog.stdout,
      port: Int => Int = identity
  ): Either[Int, Vector[HttpService]] =
    if args.contains("--live") then
      out("usage error: LocalRun runs fake mode only; start the service mains for live mode")
      Left(2)
    else
      Launcher.prepare(args, env, out).map { (_, cfg, auth, b) =>
        val s = cfg.services
        Vector(
          Apps.queryApi(cfg, b, auth, log) -> s.queryApi.port,
          Apps.graphEvidenceApi(cfg, b, auth, log) -> s.graphEvidenceApi.port,
          Apps.worker(cfg, b, auth, log) -> s.worker.port
        ).map { (svc, p) =>
          log.event(svc.name, "started", "port" -> svc.start("127.0.0.1", port(p)))
          svc
        }
      }

  def main(args: Array[String]): Unit =
    start(args.toSeq) match
      case Left(code)  => sys.exit(code)
      case Right(svcs) => sys.addShutdownHook(svcs.reverse.foreach(_.stop()))
