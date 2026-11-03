package scholarchain.core.config

import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import scholarchain.core.CollaborationMetric

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A named inclusive year window. */
final case class TimeWindow(name: String, startYear: Int, endYear: Int)

final case class StorageConfig(
    rawBucket: String,
    curatedBucket: String,
    graphBucket: String,
    ragBucket: String
)
final case class NeptuneConfig(endpoint: String, port: Int, queryTimeoutMs: Int)
final case class S3VectorsConfig(vectorBucket: String, indexes: Map[String, String])
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandMin: Double,
    llmBandMax: Double,
    maxBlockSize: Int,
    maxConflictGroupSize: Int
)
final case class GraphConfig(
    policyVersion: String,
    oversizeTeamThreshold: Int,
    windows: Vector[TimeWindow]
)
final case class AnalyticsConfig(
    communityAlgorithm: String,
    communityMaxIterations: Int,
    kcoreMaxIterations: Int,
    checkpointInterval: Int,
    bridgeMeasures: Vector[CollaborationMetric]
)
final case class RetrievalConfig(
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    contextTokenBudget: Int,
    fusionPolicyVersion: String
)
final case class ModelsConfig(
    embeddingModel: String,
    synthesisModel: String,
    verificationModel: String,
    adjudicationModel: String
)
final case class OperationsConfig(
    synchronousDeadlineMs: Int,
    maxWorkflowRetries: Int,
    maxConcurrentLlmCalls: Int
)

/** Typed view of the `scholarchain` configuration tree (see scholarchain.conf). */
final case class ScholarChainConfig(
    configVersion: String,
    environment: String,
    region: String,
    awsAccountId: String,
    storage: StorageConfig,
    neptune: NeptuneConfig,
    s3Vectors: S3VectorsConfig,
    entityResolution: EntityResolutionConfig,
    graph: GraphConfig,
    analytics: AnalyticsConfig,
    retrieval: RetrievalConfig,
    models: ModelsConfig,
    operations: OperationsConfig
)

/** Loads configuration. Precedence, highest first:
  *   1. environment variables in [[EnvOverrides]];
  *   2. the HOCON file named by SCHOLARCHAIN_CONFIG_FILE (must exist when set);
  *   3. the versioned reference `scholarchain.conf` on the core classpath.
  * JVM system properties and other environment variables are ignored. Never point
  * SCHOLARCHAIN_CONFIG_FILE at a secrets or `.env` file; secrets come from Secrets Manager.
  */
object ConfigLoader:
  val SupportedConfigVersion = "config-v1"
  val ConfigFileEnv = "SCHOLARCHAIN_CONFIG_FILE"
  val ReferenceResource = "scholarchain.conf"

  /** Documented environment variable -> configuration path (under `scholarchain`). */
  val EnvOverrides: Map[String, String] = Map(
    "SCHOLARCHAIN_ENVIRONMENT" -> "application.environment",
    "SCHOLARCHAIN_AWS_REGION" -> "application.region",
    "SCHOLARCHAIN_AWS_ACCOUNT_ID" -> "application.aws-account-id",
    "SCHOLARCHAIN_RAW_BUCKET" -> "storage.raw-bucket",
    "SCHOLARCHAIN_CURATED_BUCKET" -> "storage.curated-bucket",
    "SCHOLARCHAIN_GRAPH_BUCKET" -> "storage.graph-bucket",
    "SCHOLARCHAIN_RAG_BUCKET" -> "storage.rag-bucket",
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT" -> "neptune.endpoint",
    "SCHOLARCHAIN_NEPTUNE_PORT" -> "neptune.port",
    "SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS" -> "neptune.query-timeout-ms",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "s3-vectors.vector-bucket",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> "entity-resolution.policy-version",
    "SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "entity-resolution.max-block-size",
    "SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE" -> "entity-resolution.max-conflict-group-size",
    "SCHOLARCHAIN_GRAPH_POLICY_VERSION" -> "graph.policy-version",
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "models.embedding-model",
    "SCHOLARCHAIN_SYNTHESIS_MODEL_ID" -> "models.synthesis-model",
    "SCHOLARCHAIN_VERIFICATION_MODEL_ID" -> "models.verification-model",
    "SCHOLARCHAIN_ADJUDICATION_MODEL_ID" -> "models.adjudication-model"
  )

  def load(env: Map[String, String] = sys.env): Either[List[String], ScholarChainConfig] =
    Try(merged(env)).toEither.left
      .map(e => List(s"configuration error: ${e.getMessage}"))
      .flatMap(c =>
        Try(typed(c)).toEither.left.map(e => List(s"configuration error: ${e.getMessage}"))
      )
      .flatMap(c => validate(c) match { case Nil => Right(c); case es => Left(es) })

  /** The merged, resolved `scholarchain` tree. */
  def merged(env: Map[String, String]): Config =
    val strict = ConfigParseOptions.defaults().setAllowMissing(false)
    val reference = ConfigFactory.parseResources(getClass.getClassLoader, ReferenceResource, strict)
    val file = env
      .get(ConfigFileEnv)
      .fold(ConfigFactory.empty())(f => ConfigFactory.parseFile(new File(f), strict))
    val envLayer = ConfigFactory.parseMap(
      EnvOverrides.collect {
        case (k, path) if env.contains(k) => s"scholarchain.$path" -> env(k)
      }.asJava
    )
    envLayer
      .withFallback(file)
      .withFallback(reference)
      .resolve(ConfigResolveOptions.noSystem())
      .getConfig("scholarchain")

  private def typed(c: Config): ScholarChainConfig =
    def str(p: String) = c.getString(p)
    def int(p: String) = c.getInt(p)
    def dbl(p: String) = c.getDouble(p)
    ScholarChainConfig(
      configVersion = str("config-version"),
      environment = str("application.environment"),
      region = str("application.region"),
      awsAccountId = str("application.aws-account-id"),
      storage = StorageConfig(
        str("storage.raw-bucket"),
        str("storage.curated-bucket"),
        str("storage.graph-bucket"),
        str("storage.rag-bucket")
      ),
      neptune = NeptuneConfig(
        str("neptune.endpoint"),
        int("neptune.port"),
        int("neptune.query-timeout-ms")
      ),
      s3Vectors = S3VectorsConfig(
        str("s3-vectors.vector-bucket"),
        c.getConfig("s3-vectors.indexes")
          .entrySet
          .asScala
          .map(e => e.getKey -> e.getValue.unwrapped.toString)
          .toMap
      ),
      entityResolution = EntityResolutionConfig(
        str("entity-resolution.policy-version"),
        dbl("entity-resolution.accept-threshold"),
        dbl("entity-resolution.reject-threshold"),
        dbl("entity-resolution.llm-band-min"),
        dbl("entity-resolution.llm-band-max"),
        int("entity-resolution.max-block-size"),
        int("entity-resolution.max-conflict-group-size")
      ),
      graph = GraphConfig(
        str("graph.policy-version"),
        int("graph.oversize-team-threshold"),
        c.getConfigList("graph.windows")
          .asScala
          .map(w => TimeWindow(w.getString("name"), w.getInt("start-year"), w.getInt("end-year")))
          .toVector
      ),
      analytics = AnalyticsConfig(
        str("analytics.community-algorithm"),
        int("analytics.community-max-iterations"),
        int("analytics.kcore-max-iterations"),
        int("analytics.checkpoint-interval"),
        c.getStringList("analytics.bridge-measures")
          .asScala
          .map(CollaborationMetric.valueOf)
          .toVector
      ),
      retrieval = RetrievalConfig(
        int("retrieval.graph-limit"),
        int("retrieval.vector-limit"),
        int("retrieval.max-path-depth"),
        int("retrieval.context-token-budget"),
        str("retrieval.fusion-policy-version")
      ),
      models = ModelsConfig(
        str("models.embedding-model"),
        str("models.synthesis-model"),
        str("models.verification-model"),
        str("models.adjudication-model")
      ),
      operations = OperationsConfig(
        int("operations.synchronous-deadline-ms"),
        int("operations.max-workflow-retries"),
        int("operations.max-concurrent-llm-calls")
      )
    )

  /** Rule violations of a loaded configuration; empty means valid. */
  def validate(c: ScholarChainConfig): List[String] =
    def check(ok: Boolean, msg: String) = if ok then Nil else List(msg)
    val er = c.entityResolution
    val placeholders = Seq(
      c.awsAccountId,
      c.storage.rawBucket,
      c.storage.curatedBucket,
      c.storage.graphBucket,
      c.storage.ragBucket,
      c.neptune.endpoint,
      c.s3Vectors.vectorBucket,
      c.models.embeddingModel,
      c.models.synthesisModel,
      c.models.verificationModel,
      c.models.adjudicationModel
    ).filter(v => v.startsWith("example-") || v.endsWith(".invalid") || v == "000000000000")
    check(
      c.configVersion == SupportedConfigVersion,
      s"unsupported config-version '${c.configVersion}'"
    ) ++
      check(
        0 <= er.rejectThreshold && er.rejectThreshold <= er.llmBandMin && er.llmBandMin <= er.llmBandMax &&
          er.llmBandMax <= er.acceptThreshold && er.acceptThreshold <= 1,
        "entity-resolution thresholds must satisfy 0 <= reject <= llm-band-min <= llm-band-max <= accept <= 1"
      ) ++
      check(
        er.maxBlockSize >= 1 && er.maxConflictGroupSize >= 1,
        "entity-resolution.max-block-size and entity-resolution.max-conflict-group-size must be positive"
      ) ++
      check(c.graph.windows.nonEmpty, "graph.windows must not be empty") ++
      c.graph.windows.toList.flatMap(w =>
        check(
          w.startYear <= w.endYear,
          s"graph window '${w.name}' start year ${w.startYear} exceeds end year ${w.endYear}"
        )
      ) ++
      check(
        c.graph.windows.map(_.name).distinct.size == c.graph.windows.size,
        "graph window names must be unique"
      ) ++
      check(
        c.neptune.port >= 1 && c.neptune.port <= 65535,
        s"neptune.port ${c.neptune.port} out of range"
      ) ++
      check(
        Seq(
          c.neptune.queryTimeoutMs,
          c.graph.oversizeTeamThreshold,
          c.analytics.communityMaxIterations,
          c.analytics.kcoreMaxIterations,
          c.analytics.checkpointInterval,
          c.retrieval.graphLimit,
          c.retrieval.vectorLimit,
          c.retrieval.maxPathDepth,
          c.retrieval.contextTokenBudget,
          c.operations.synchronousDeadlineMs,
          c.operations.maxConcurrentLlmCalls
        ).forall(_ >= 1) && c.operations.maxWorkflowRetries >= 0,
        "limits, timeouts, and iteration counts must be positive"
      ) ++
      check(
        c.environment == "local" || placeholders.isEmpty,
        s"environment '${c.environment}' still uses placeholder values: ${placeholders.mkString(", ")}"
      )
