package scholarchain.storage.live

import scholarchain.storage.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  ChecksumMode,
  GetObjectRequest,
  HeadObjectRequest,
  ListObjectsV2Request,
  NoSuchKeyException,
  PutObjectRequest,
  S3Exception
}

import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** S3 requests. putNew uses If-None-Match: * so S3 itself refuses to overwrite an existing key
  * (conditional-writes.html, accessed 2026-11-05), and a SHA-256 checksum so S3 verifies the bytes.
  */
object S3Requests:
  def checksum(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

  def putNew(bucket: String, objectKey: String, bytes: Array[Byte]): PutObjectRequest =
    PutObjectRequest
      .builder()
      .bucket(bucket)
      .key(objectKey)
      .ifNoneMatch("*")
      .checksumSHA256(checksum(bytes))
      .contentLength(bytes.length.toLong)
      .build()

  def get(bucket: String, objectKey: String): GetObjectRequest =
    GetObjectRequest.builder().bucket(bucket).key(objectKey).build()

  /** HEAD with checksum mode on, so S3 returns the SHA-256 checksum stored by putNew. */
  def head(bucket: String, objectKey: String): HeadObjectRequest =
    HeadObjectRequest
      .builder()
      .bucket(bucket)
      .key(objectKey)
      .checksumMode(ChecksumMode.ENABLED)
      .build()

  /** "sha256:<hex>" (the manifest form) from a base64 full-object ChecksumSHA256; None when absent
    * or composite (a multipart "<base64>-<parts>" value is not a hash of the whole object).
    */
  def sha256Of(checksumSha256: String): Option[String] =
    Option(checksumSha256)
      .filterNot(_.contains("-"))
      .flatMap(b => scala.util.Try(Base64.getDecoder.decode(b)).toOption)
      .filter(_.length == 32)
      .map(d => "sha256:" + d.map(x => f"${x & 0xff}%02x").mkString)

  def list(bucket: String, prefix: String): ListObjectsV2Request =
    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()

  /** 412: the key exists (If-None-Match failed). 409: a concurrent conditional write; retry. */
  def outcomeOf(e: S3Exception): Option[String] = e.statusCode match
    case 412 => Some("EXISTS")
    case 409 => Some("RETRY")
    case _   => None

/** Live adapter for the environment bucket, with the same write-once semantics as FileObjectStore.
  */
final class S3ObjectStore(client: S3Client, bucket: String):

  /** The object's stored SHA-256 ("sha256:<hex>"), without downloading it; None when the object is
    * missing or has no full-object SHA-256 checksum.
    */
  def sha256(objectKey: String): Option[String] =
    try
      S3Requests.sha256Of(
        LiveAws
          .guardedWith(expired = LiveAws.isHeadExpiredToken)(
            client.headObject(S3Requests.head(bucket, objectKey))
          )
          .checksumSHA256
      )
    catch
      case _: NoSuchKeyException                 => None
      case e: S3Exception if e.statusCode == 404 => None

  val reader: ObjectReadPort = new ObjectReadPort:
    def get(objectKey: String): Option[Array[Byte]] =
      try
        Some(
          LiveAws.guarded(client.getObjectAsBytes(S3Requests.get(bucket, objectKey))).asByteArray
        )
      catch case _: NoSuchKeyException => None

    def list(prefix: String): Vector[String] = LiveAws.guarded(
      client
        .listObjectsV2Paginator(S3Requests.list(bucket, prefix))
        .contents
        .asScala
        .map(_.key)
        .toVector
        .sorted
    )

  val loader: ObjectLoadPort = new ObjectLoadPort:
    def putNew(objectKey: String, bytes: Array[Byte]): Either[List[String], PutObjectOutcome] =
      S3Layout.keyProblems(objectKey) match
        case Nil =>
          try
            LiveAws.guarded(
              client.putObject(
                S3Requests.putNew(bucket, objectKey, bytes),
                RequestBody.fromBytes(bytes)
              )
            )
            Right(PutObjectOutcome.Inserted)
          catch
            case e: S3Exception if S3Requests.outcomeOf(e).contains("RETRY") =>
              throw TransientStoreException(s"conditional write conflict on $objectKey")
            case e: S3Exception if S3Requests.outcomeOf(e).contains("EXISTS") =>
              val existing = reader.get(objectKey).map(Hashing.sha256).getOrElse("")
              Right(
                if existing == Hashing.sha256(bytes) then PutObjectOutcome.Unchanged
                else PutObjectOutcome.Conflict(existing)
              )
        case problems => Left(problems)
