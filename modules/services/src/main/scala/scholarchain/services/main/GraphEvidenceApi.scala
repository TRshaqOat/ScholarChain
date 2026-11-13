package scholarchain.services.main

import scholarchain.services.{Apps, Launcher}

/** bin/graph-evidence-api: evidence, graph node, and neighbors on services.graph-evidence-api.port.
  * It answers no questions, so it never opens the window-summary object (repair-01 m3).
  */
object GraphEvidenceApi:
  def main(args: Array[String]): Unit =
    Launcher.main(
      args,
      Apps.graphEvidenceApi(_, _, _, _),
      _.services.graphEvidenceApi.port,
      answers = false
    )
