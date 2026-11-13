package scholarchain.services.main

import scholarchain.services.{Apps, Launcher}

/** bin/query-api: POST /v1/query and GET /v1/explain/{queryId} on services.query-api.port. */
object QueryApi:
  def main(args: Array[String]): Unit =
    Launcher.main(args, Apps.queryApi(_, _, _, _), _.services.queryApi.port)
