package scholarchain.storage.live

import scholarchain.core.config.TimeWindow
import scholarchain.storage.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue as Sdk,
  ConditionalCheckFailedException,
  GetItemRequest,
  PutItemRequest,
  QueryRequest,
  ReturnValuesOnConditionCheckFailure
}

import scala.jdk.CollectionConverters.*

/** DynamoDB JSON attribute values <-> SDK attribute values. */
object DynamoCodec:
  def toSdk(v: AttributeValue): Sdk = v match
    case AttributeValue.S(s)    => Sdk.builder().s(s).build()
    case AttributeValue.N(n)    => Sdk.builder().n(n).build()
    case AttributeValue.Bool(b) => Sdk.builder().bool(b).build()
    case AttributeValue.Null    => Sdk.builder().nul(true).build()
    case AttributeValue.L(xs)   => Sdk.builder().l(xs.map(toSdk).asJava).build()
    case AttributeValue.M(m)    => Sdk.builder().m(m.map((k, x) => k -> toSdk(x)).asJava).build()

  def fromSdk(v: Sdk): Either[String, AttributeValue] = v.`type`.toString match
    case "S"    => Right(AttributeValue.S(v.s))
    case "N"    => Right(AttributeValue.N(v.n))
    case "BOOL" => Right(AttributeValue.Bool(v.bool))
    case "NUL"  => Right(AttributeValue.Null)
    case "L"    => all(v.l.asScala.toVector.map(fromSdk)).map(AttributeValue.L(_))
    case "M"    => fromSdkItem(v.m).map(AttributeValue.M(_))
    case t      => Left(s"unsupported attribute type $t")

  def toSdkItem(item: Item): java.util.Map[String, Sdk] =
    item.map((k, x) => k -> toSdk(x)).asJava

  def fromSdkItem(m: java.util.Map[String, Sdk]): Either[String, Item] =
    all(m.asScala.toVector.map((k, x) => fromSdk(x).map(k -> _))).map(_.toMap)

  private def all[A](xs: Vector[Either[String, A]]): Either[String, Vector[A]] =
    xs.collectFirst { case Left(e) => e }.toLeft(xs.collect { case Right(a) => a })

/** DynamoDB requests for the six tables. Physical table name = tablePrefix + logical name. */
object DynamoRequests:
  private def str(s: String) = Sdk.builder().s(s).build()

  /** PutItem only when no item has this key; on failure DynamoDB returns the stored item. */
  def putNew(table: String, spec: TableSpec, item: Item): PutItemRequest = PutItemRequest
    .builder()
    .tableName(table)
    .item(DynamoCodec.toSdkItem(item))
    .conditionExpression("attribute_not_exists(#pk)")
    .expressionAttributeNames(Map("#pk" -> spec.partitionKey).asJava)
    .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
    .build()

  /** Explicit supersede: PutItem only when the stored item's runId is the expected one. */
  def replace(table: String, item: Item, expectedRunId: String): PutItemRequest = PutItemRequest
    .builder()
    .tableName(table)
    .item(DynamoCodec.toSdkItem(item))
    .conditionExpression("#r = :expected")
    .expressionAttributeNames(Map("#r" -> "runId").asJava)
    .expressionAttributeValues(Map(":expected" -> str(expectedRunId)).asJava)
    .build()

  def get(table: String, spec: TableSpec, pk: String, sk: String): GetItemRequest = GetItemRequest
    .builder()
    .tableName(table)
    .key(Map(spec.partitionKey -> str(pk), spec.sortKey -> str(sk)).asJava)
    .consistentRead(true)
    .build()

  def query(
      table: String,
      spec: TableSpec,
      pk: String,
      skPrefix: String,
      limit: Int
  ): QueryRequest =
    val names = Map("#pk" -> spec.partitionKey) ++
      Option.when(skPrefix.nonEmpty)("#sk" -> spec.sortKey)
    val values = Map(":pk" -> str(pk)) ++ Option.when(skPrefix.nonEmpty)(":p" -> str(skPrefix))
    QueryRequest
      .builder()
      .tableName(table)
      .keyConditionExpression(
        if skPrefix.isEmpty then "#pk = :pk" else "#pk = :pk AND begins_with(#sk, :p)"
      )
      .expressionAttributeNames(names.asJava)
      .expressionAttributeValues(values.asJava)
      .limit(limit)
      .build()

/** Live adapter of the six tables with the same ports and semantics as InMemoryDynamo. */
final class DynamoStore(
    client: DynamoDbClient,
    tablePrefix: String,
    windows: Seq[TimeWindow],
    nowEpochSeconds: () => Long
):
  private val byWindow = ProvenanceRules.windowsByName(windows)
  private def name(t: TableName) = tablePrefix + t.toString
  private def decode(m: java.util.Map[String, Sdk]): Item =
    DynamoCodec.fromSdkItem(m).fold(e => throw IllegalStateException(e), identity)

  private def putNewIn(allowed: Set[TableName])(table: TableName, item: Item) =
    val spec = DynamoContract.Tables(table)
    if !allowed(table) then Left(List(s"$table is not writable through this port"))
    else
      DynamoContract.problems(spec, item, byWindow) match
        case Nil =>
          val stored = AttributeValue.canonicalItem(item) // the text DynamoDB returns (F1)
          try
            LiveAws.guarded(client.putItem(DynamoRequests.putNew(name(table), spec, stored)))
            Right(LoadOutcome.Inserted)
          catch
            case e: ConditionalCheckFailedException =>
              Right(
                if e.hasItem && AttributeValue.sameItem(decode(e.item), stored) then
                  LoadOutcome.Unchanged
                else LoadOutcome.Conflict
              )
        case problems => Left(problems)

  val reader: KeyValueReadPort = new KeyValueReadPort:
    def get(table: TableName, pk: String, sk: String): Option[Item] =
      val spec = DynamoContract.Tables(table)
      val r = LiveAws.guarded(client.getItem(DynamoRequests.get(name(table), spec, pk, sk)))
      Option
        .when(r.hasItem && !r.item.isEmpty)(decode(r.item))
        .filter(DynamoContract.unexpired(spec, _, nowEpochSeconds()))

    // shortcut: one page (at most 1 MB); enough for the bounded runtime queries, page if needed.
    def query(table: TableName, pk: String, skPrefix: String, limit: Int): Vector[Item] =
      require(limit >= 1 && limit <= DynamoContract.QueryMaxLimit, s"limit $limit out of range")
      val spec = DynamoContract.Tables(table)
      LiveAws
        .guarded(client.query(DynamoRequests.query(name(table), spec, pk, skPrefix, limit)))
        .items
        .asScala
        .map(decode)
        .filter(DynamoContract.unexpired(spec, _, nowEpochSeconds()))
        .toVector

  val loader: KeyValueLoadPort = new KeyValueLoadPort:
    def putNew(table: TableName, item: Item): Either[List[String], LoadOutcome] =
      putNewIn(DynamoContract.LoadTables)(table, item)
    def replace(table: TableName, item: Item, expectedRunId: String) =
      val spec = DynamoContract.Tables(table)
      if !DynamoContract.LoadTables(table) then
        Left(List(s"$table is not writable through this port"))
      else if DynamoContract.appendOnly(table, item) then Left(List(s"$table entry is append-only"))
      else
        DynamoContract.problems(spec, item, byWindow) match
          case Nil =>
            try
              LiveAws.guarded(
                client.putItem(
                  DynamoRequests.replace(
                    name(table),
                    AttributeValue.canonicalItem(item),
                    expectedRunId
                  )
                )
              )
              Right(PutOutcome.Written)
            catch case _: ConditionalCheckFailedException => Right(PutOutcome.ConditionFailed)
          case problems => Left(problems)

  val runtimeWriter: RuntimeRecordPort = new RuntimeRecordPort:
    def create(table: TableName, item: Item): Either[List[String], LoadOutcome] =
      putNewIn(DynamoContract.RuntimeTables)(table, item)
