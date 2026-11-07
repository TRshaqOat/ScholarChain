package scholarchain.storage.live

import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  GetLoaderJobStatusRequest,
  StartLoaderJobRequest
}

import scala.jdk.CollectionConverters.*

/** Parsed Neptune loader Get-Status (load-api-reference-status-response.html, accessed 2026-11-05).
  */
final case class LoaderStatus(
    status: String,
    fullUri: String,
    totalRecords: Long,
    totalDuplicates: Long,
    parsingErrors: Long,
    datatypeMismatchErrors: Long,
    insertErrors: Long,
    errorLogs: Vector[String]
):
  def finished: Boolean = status != "LOAD_NOT_STARTED" && status != "LOAD_IN_PROGRESS" &&
    status != "LOAD_IN_QUEUE"
  def succeeded: Boolean = status == "LOAD_COMPLETED"

/** Neptune bulk-loader requests (load-api-reference-load.html, accessed 2026-11-05). */
object NeptuneLoadRequests:
  /** One load of a whole graph/neptune/snapshot=<d>/run=<r>/ prefix in openCypher CSV format.
    * userProvidedEdgeIds keeps the contract edge IDs; updateSingleCardinalityProperties=false keeps
    * an existing element unchanged; failOnError stops on the first bad record.
    */
  def start(
      bucket: String,
      prefix: String,
      roleArn: String,
      region: String
  ): StartLoaderJobRequest =
    require(prefix.startsWith("graph/neptune/") && prefix.endsWith("/"), s"bad source $prefix")
    StartLoaderJobRequest
      .builder()
      .source(s"s3://$bucket/$prefix")
      .format("opencypher")
      .s3BucketRegion(region)
      .iamRoleArn(roleArn)
      .failOnError(true)
      .parallelism("MEDIUM")
      .updateSingleCardinalityProperties(false)
      .userProvidedEdgeIds(true)
      .queueRequest(true)
      .build()

  def status(loadId: String): GetLoaderJobStatusRequest = GetLoaderJobStatusRequest
    .builder()
    .loadId(loadId)
    .details(true)
    .errors(true)
    .page(1)
    .errorsPerPage(10)
    .build()

  def loadIdOf(payload: Map[String, String]): Either[String, String] =
    payload.get("loadId").filter(_.nonEmpty).toRight(s"no loadId in $payload")

  def parseStatus(payload: ujson.Value): Either[String, LoaderStatus] =
    scala.util
      .Try {
        val o = payload("overallStatus")
        def n(k: String) = o.obj.get(k).flatMap(_.numOpt).fold(0L)(_.toLong)
        val logs = payload.obj
          .get("errors")
          .flatMap(_.obj.get("errorLogs"))
          .flatMap(_.arrOpt)
          .fold(Vector.empty[String])(_.map(_.render()).toVector)
        LoaderStatus(
          o("status").str,
          o.obj.get("fullUri").flatMap(_.strOpt).getOrElse(""),
          n("totalRecords"),
          n("totalDuplicates"),
          n("parsingErrors"),
          n("datatypeMismatchErrors"),
          n("insertErrors"),
          logs
        )
      }
      .toEither
      .left
      .map(e => s"unreadable loader status: ${e.getMessage}")

/** Live Neptune bulk loader: start a load and read its status. Runtime graph reads use read-only
  * credentials and are not part of this class.
  */
final class NeptuneBulkLoader(client: NeptunedataClient):
  def start(
      bucket: String,
      prefix: String,
      roleArn: String,
      region: String
  ): Either[String, String] =
    val r = LiveAws.guarded(
      client.startLoaderJob(NeptuneLoadRequests.start(bucket, prefix, roleArn, region))
    )
    NeptuneLoadRequests.loadIdOf(r.payload.asScala.toMap)

  def status(loadId: String): Either[String, LoaderStatus] =
    val r = LiveAws.guarded(client.getLoaderJobStatus(NeptuneLoadRequests.status(loadId)))
    NeptuneLoadRequests.parseStatus(Documents.fromDocument(r.payload))
