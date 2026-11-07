package scholarchain.storage.live

import scholarchain.storage.*
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetOutputVector,
  GetVectorsRequest,
  PutInputVector,
  PutVectorsRequest,
  QueryOutputVector,
  QueryVectorsRequest,
  VectorData
}

import scala.jdk.CollectionConverters.*

/** S3 Vectors requests and response parsing (S3 Vectors API, accessed 2026-11-05). */
object VectorRequests:
  def data(v: Array[Float]): VectorData =
    VectorData.fromFloat32(v.toVector.map(java.lang.Float.valueOf).asJava)

  def put(bucket: String, index: String, records: Seq[VectorRecord]): PutVectorsRequest =
    require(records.nonEmpty && records.size <= VectorContract.MaxPutBatch, "1 to 500 vectors")
    PutVectorsRequest
      .builder()
      .vectorBucketName(bucket)
      .indexName(index)
      .vectors(
        records
          .map(r =>
            PutInputVector
              .builder()
              .key(r.key)
              .data(data(r.data))
              .metadata(Documents.toDocument(r.metadata))
              .build()
          )
          .asJava
      )
      .build()

  /** Metadata only: enough for VectorWriteRule (textHash, model version, runId). */
  def get(bucket: String, index: String, keys: Seq[String]): GetVectorsRequest =
    require(keys.nonEmpty && keys.size <= VectorContract.MaxGetBatch, "1 to 100 keys")
    GetVectorsRequest
      .builder()
      .vectorBucketName(bucket)
      .indexName(index)
      .keys(keys.asJava)
      .returnData(false)
      .returnMetadata(true)
      .build()

  def query(
      bucket: String,
      index: String,
      vector: Array[Float],
      topK: Int,
      filter: Option[VectorFilter]
  ): QueryVectorsRequest =
    val b = QueryVectorsRequest
      .builder()
      .vectorBucketName(bucket)
      .indexName(index)
      .queryVector(data(vector))
      .topK(topK)
      .returnMetadata(true)
      .returnDistance(true)
    filter.foreach(f => b.filter(Documents.toDocument(filterJson(f))))
    b.build()

  /** The service's JSON filter syntax (s3-vectors-metadata-filtering.html). */
  def filterJson(f: VectorFilter): ujson.Value =
    def op(k: String, o: String, v: ujson.Value) = ujson.Obj(k -> ujson.Obj(o -> v))
    import VectorFilter.*
    f match
      case Eq(k, v)         => op(k, "$eq", v)
      case Ne(k, v)         => op(k, "$ne", v)
      case Gt(k, v)         => op(k, "$gt", v)
      case Gte(k, v)        => op(k, "$gte", v)
      case Lt(k, v)         => op(k, "$lt", v)
      case Lte(k, v)        => op(k, "$lte", v)
      case In(k, vs)        => op(k, "$in", ujson.Arr.from(vs))
      case Nin(k, vs)       => op(k, "$nin", ujson.Arr.from(vs))
      case Exists(k, p)     => op(k, "$exists", p)
      case StartsWith(k, p) => op(k, "$startsWith", p)
      case And(fs)          => ujson.Obj("$and" -> ujson.Arr.from(fs.map(filterJson)))
      case Or(fs)           => ujson.Obj("$or" -> ujson.Arr.from(fs.map(filterJson)))

  private def obj(v: ujson.Value): ujson.Obj = v match
    case o: ujson.Obj => o
    case _            => ujson.Obj()

  /** A stored vector as GetVectors returns it (metadata only; data is empty). */
  def parseGet(v: GetOutputVector): VectorRecord =
    VectorRecord(
      v.key,
      Option(v.data)
        .filter(_.hasFloat32)
        .fold(Array.empty[Float])(_.float32.asScala.map(_.floatValue).toArray),
      obj(Documents.fromDocument(v.metadata))
    )

  def parseHit(v: QueryOutputVector): VectorHit =
    VectorHit(
      v.key,
      Option(v.distance).fold(Double.NaN)(_.doubleValue),
      obj(Documents.fromDocument(v.metadata))
    )

/** Live adapter of the vector bucket, with the same checks and write rule as FileVectorStore. */
final class S3VectorStore(client: S3VectorsClient, bucket: String):
  private def known(index: String) =
    Either.cond(VectorContract.index(index).nonEmpty, (), s"unknown index $index")

  val reader: VectorReadPort = new VectorReadPort:
    def get(index: String, keys: Seq[String]): Either[String, Vector[VectorRecord]] =
      known(index).flatMap(_ =>
        if keys.isEmpty then Right(Vector.empty)
        else if keys.size > VectorContract.MaxGetBatch then
          Left(s"${keys.size} keys, max ${VectorContract.MaxGetBatch}")
        else
          Right(
            LiveAws
              .guarded(client.getVectors(VectorRequests.get(bucket, index, keys)))
              .vectors
              .asScala
              .map(VectorRequests.parseGet)
              .toVector
          )
      )

    def query(index: String, vector: Array[Float], topK: Int, filter: Option[VectorFilter]) =
      val nonFilterable =
        filter.toSeq.flatMap(_.keys).filter(VectorContract.NonFilterableKeys.contains)
      known(index).flatMap(_ =>
        if vector.length != VectorContract.Dimension then Left(s"query dimension ${vector.length}")
        else if topK < 1 || topK > VectorContract.MaxTopK then Left(s"topK $topK out of range")
        else if nonFilterable.nonEmpty then
          Left(s"filter on non-filterable keys ${nonFilterable.mkString(",")}")
        else if filter.exists(_.constraints > VectorContract.MaxFilterConstraints) then
          Left("too many filter constraints")
        else
          Right(
            LiveAws
              .guarded(
                client.queryVectors(VectorRequests.query(bucket, index, vector, topK, filter))
              )
              .vectors
              .asScala
              .map(VectorRequests.parseHit)
              .toVector
          )
      )

  /** Reads the stored metadata of the batch keys (GetVectors, at most 100 keys per call), applies
    * VectorWriteRule on the client (PutVectors itself would overwrite), then writes in one call.
    */
  val loader: VectorLoadPort = new VectorLoadPort:
    def put(index: String, batch: Seq[VectorRecord], mode: WriteMode) =
      known(index).flatMap(_ =>
        if batch.isEmpty || batch.size > VectorContract.MaxPutBatch then
          Left(s"batch of ${batch.size}, allowed 1 to ${VectorContract.MaxPutBatch}")
        else
          val stored = batch
            .map(_.key)
            .distinct
            .grouped(VectorContract.MaxGetBatch)
            .flatMap(ks =>
              reader.get(index, ks).fold(e => throw IllegalStateException(e), identity)
            )
            .map(r => r.key -> r)
            .toMap
          val plan = VectorBatchPlan.plan(index, batch, mode, stored.get)
          if plan.toWrite.nonEmpty then
            LiveAws.guarded(client.putVectors(VectorRequests.put(bucket, index, plan.toWrite)))
          Right(plan.result)
      )
