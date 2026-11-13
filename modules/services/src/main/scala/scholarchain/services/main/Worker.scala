package scholarchain.services.main

import scholarchain.services.{Apps, Launcher}

/** bin/worker: the async job worker (SQS long poll), with health, readiness, and metrics on
  * services.worker.port.
  */
object Worker:
  def main(args: Array[String]): Unit =
    Launcher.main(args, Apps.worker(_, _, _, _), _.services.worker.port)
