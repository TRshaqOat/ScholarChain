package scholarchain.core.config

import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import scholarchain.core.{CollaborationMetric, IdFormat}

import java.io.File
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A named inclusive year window. */
final case class TimeWindow(name: String, startYear: Int, endYear: Int)

/** The top-level key prefixes of the environment bucket (Phase 7 ruling 7), each ending in "/". */
final case class StoragePrefixes(
    raw: String,
    curated: String,
    graph: String,
    semanticContext: String,
    vectors: String,
    reports: String,
    logs: String,
    runs: String
):
  def all: Vector[String] =
    Vector(raw, curated, graph, semanticContext, vectors, reports, logs, runs)

/** One S3 bucket per environment; data is separated by [[StoragePrefixes]], not by bucket. */
final case class StorageConfig(bucket: String, prefixes: StoragePrefixes)
final case class NeptuneConfig(
    endpoint: String,
    port: Int,
    queryTimeoutMs: Int,
    loaderRoleArn: String
)

/** Physical DynamoDB table name = tablePrefix + logical table name (modules/storage/STORAGE.md). */
final case class DynamoDbConfig(tablePrefix: String)
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
    bridgeMeasures: Vector[CollaborationMetric],
    rankingSize: Int
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
    plannerModel: String,
    synthesisModel: String,
    verificationModel: String,
    adjudicationModel: String
)
final case class OperationsConfig(
    synchronousDeadlineMs: Int,
    maxWorkflowRetries: Int,
    maxConcurrentLlmCalls: Int
)

// ---- Graph-RAG runtime (Phase 9, `runtime.*`; meanings in modules/rag/RUNTIME.md section 20) --
final case class QuestionConfig(maxChars: Int, deadlineMs: Int)
final case class PlannerConfig(
    promptVersion: String,
    maxOutputTokens: Int,
    maxOperations: Int,
    maxResearcherNames: Int,
    maxTermChars: Int,
    maxFilterTerms: Int
)
final case class SynthesisConfig(promptVersion: String, maxOutputTokens: Int, maxClaims: Int)
final case class LlmCallConfig(
    requestsPerMinute: Int,
    maxAttemptsPerCall: Int,
    timeoutMs: Int,
    temperature: Double
)
final case class EmbeddingCallConfig(requestsPerMinute: Int, timeoutMs: Int)
final case class ResolverConfig(
    policyVersion: String,
    dominantShare: Double,
    dominantMinWorks: Int,
    maxCandidates: Int,
    candidateCoauthors: Int,
    candidateInstitutions: Int
)
final case class RankingsConfig(defaultTopN: Int, maxTopN: Int)
final case class SemanticConfig(topK: Int, maxIndexes: Int)
final case class BudgetConfig(
    graphEvidenceTokens: Int,
    semanticContextTokens: Int,
    maxEvidenceItems: Int,
    charsPerToken: Int,
    maxInputTokensPerQuestion: Int,
    maxOutputTokensPerQuestion: Int
)
final case class AuditConfig(ttlDays: Int)
final case class RuntimeConfig(
    promptsDir: String,
    question: QuestionConfig,
    planner: PlannerConfig,
    synthesis: SynthesisConfig,
    llm: LlmCallConfig,
    embedding: EmbeddingCallConfig,
    resolver: ResolverConfig,
    rankings: RankingsConfig,
    semantic: SemanticConfig,
    budget: BudgetConfig,
    audit: AuditConfig
):
  def positiveInts: Seq[Int] = Seq(
    question.maxChars,
    question.deadlineMs,
    planner.maxOutputTokens,
    planner.maxOperations,
    planner.maxResearcherNames,
    planner.maxTermChars,
    planner.maxFilterTerms,
    synthesis.maxOutputTokens,
    synthesis.maxClaims,
    llm.requestsPerMinute,
    llm.maxAttemptsPerCall,
    llm.timeoutMs,
    embedding.requestsPerMinute,
    embedding.timeoutMs,
    resolver.dominantMinWorks,
    resolver.maxCandidates,
    resolver.candidateCoauthors,
    resolver.candidateInstitutions,
    rankings.defaultTopN,
    rankings.maxTopN,
    semantic.topK,
    semantic.maxIndexes,
    budget.graphEvidenceTokens,
    budget.semanticContextTokens,
    budget.maxEvidenceItems,
    budget.charsPerToken,
    budget.maxInputTokensPerQuestion,
    budget.maxOutputTokensPerQuestion,
    audit.ttlDays
  )

/** Bounds of the storage graph read templates (`graph-read.*`, Phase 9 ruling 7). */
final case class GraphReadConfig(
    maxResults: Int,
    idBatchSize: Int,
    pathMaxFrontier: Int,
    pathTimeoutMs: Int,
    communityMembersLimit: Int,
    communityNeighborsLimit: Int,
    communityFacetMemberCap: Int,
    facetLimit: Int,
    lookupLimit: Int,
    maxLookupIds: Int,
    neighborsMaxDepth: Int,
    neighborsMaxLimit: Int
):
  def all: Seq[Int] = productIterator.collect { case i: Int => i }.toSeq

/** Typed view of the `scholarchain` configuration tree (see scholarchain.conf). */
final case class ScholarChainConfig(
    configVersion: String,
    environment: String,
    region: String,
    awsAccountId: String,
    storage: StorageConfig,
    neptune: NeptuneConfig,
    dynamoDb: DynamoDbConfig,
    s3Vectors: S3VectorsConfig,
    entityResolution: EntityResolutionConfig,
    graph: GraphConfig,
    analytics: AnalyticsConfig,
    retrieval: RetrievalConfig,
    models: ModelsConfig,
    operations: OperationsConfig,
    runtime: RuntimeConfig,
    graphRead: GraphReadConfig
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
    "SCHOLARCHAIN_BUCKET" -> "storage.bucket",
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT" -> "neptune.endpoint",
    "SCHOLARCHAIN_NEPTUNE_PORT" -> "neptune.port",
    "SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS" -> "neptune.query-timeout-ms",
    "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" -> "neptune.loader-role-arn",
    "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> "dynamodb.table-prefix",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "s3-vectors.vector-bucket",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> "entity-resolution.policy-version",
    "SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "entity-resolution.max-block-size",
    "SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE" -> "entity-resolution.max-conflict-group-size",
    "SCHOLARCHAIN_GRAPH_POLICY_VERSION" -> "graph.policy-version",
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "models.embedding-model",
    "SCHOLARCHAIN_PLANNER_MODEL_ID" -> "models.planner-model",
    "SCHOLARCHAIN_SYNTHESIS_MODEL_ID" -> "models.synthesis-model",
    "SCHOLARCHAIN_VERIFICATION_MODEL_ID" -> "models.verification-model",
    "SCHOLARCHAIN_ADJUDICATION_MODEL_ID" -> "models.adjudication-model",
    "SCHOLARCHAIN_PROMPTS_DIR" -> "runtime.prompts-dir"
  )

  /** Keys removed by the one-bucket change (Phase 7 ruling 20). A layer that still sets one is an
    * error, not a silently ignored value.
    */
  val RemovedKeys: Vector[String] = Vector(
    "storage.raw-bucket",
    "storage.curated-bucket",
    "storage.graph-bucket",
    "storage.rag-bucket"
  )

  def load(env: Map[String, String] = sys.env): Either[List[String], ScholarChainConfig] =
    Try(merged(env)).toEither.left
      .map(e => List(s"configuration error: ${e.getMessage}"))
      .flatMap(c =>
        RemovedKeys.filter(c.hasPath) match
          case Seq() => Right(c)
          case ks => Left(ks.map(k => s"removed configuration key '$k': use storage.bucket").toList)
      )
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
        str("storage.bucket"),
        StoragePrefixes(
          str("storage.prefixes.raw"),
          str("storage.prefixes.curated"),
          str("storage.prefixes.graph"),
          str("storage.prefixes.semantic-context"),
          str("storage.prefixes.vectors"),
          str("storage.prefixes.reports"),
          str("storage.prefixes.logs"),
          str("storage.prefixes.runs")
        )
      ),
      neptune = NeptuneConfig(
        str("neptune.endpoint"),
        int("neptune.port"),
        int("neptune.query-timeout-ms"),
        str("neptune.loader-role-arn")
      ),
      dynamoDb = DynamoDbConfig(str("dynamodb.table-prefix")),
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
          .toVector,
        int("analytics.ranking-size")
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
        str("models.planner-model"),
        str("models.synthesis-model"),
        str("models.verification-model"),
        str("models.adjudication-model")
      ),
      operations = OperationsConfig(
        int("operations.synchronous-deadline-ms"),
        int("operations.max-workflow-retries"),
        int("operations.max-concurrent-llm-calls")
      ),
      runtime = RuntimeConfig(
        str("runtime.prompts-dir"),
        QuestionConfig(int("runtime.question.max-chars"), int("runtime.question.deadline-ms")),
        PlannerConfig(
          str("runtime.planner.prompt-version"),
          int("runtime.planner.max-output-tokens"),
          int("runtime.planner.max-operations"),
          int("runtime.planner.max-researcher-names"),
          int("runtime.planner.max-term-chars"),
          int("runtime.planner.max-filter-terms")
        ),
        SynthesisConfig(
          str("runtime.synthesis.prompt-version"),
          int("runtime.synthesis.max-output-tokens"),
          int("runtime.synthesis.max-claims")
        ),
        LlmCallConfig(
          int("runtime.llm.requests-per-minute"),
          int("runtime.llm.max-attempts-per-call"),
          int("runtime.llm.timeout-ms"),
          dbl("runtime.llm.temperature")
        ),
        EmbeddingCallConfig(
          int("runtime.embedding.requests-per-minute"),
          int("runtime.embedding.timeout-ms")
        ),
        ResolverConfig(
          str("runtime.resolver.policy-version"),
          dbl("runtime.resolver.dominant-share"),
          int("runtime.resolver.dominant-min-works"),
          int("runtime.resolver.max-candidates"),
          int("runtime.resolver.candidate-coauthors"),
          int("runtime.resolver.candidate-institutions")
        ),
        RankingsConfig(int("runtime.rankings.default-top-n"), int("runtime.rankings.max-top-n")),
        SemanticConfig(int("runtime.semantic.top-k"), int("runtime.semantic.max-indexes")),
        BudgetConfig(
          int("runtime.budget.graph-evidence-tokens"),
          int("runtime.budget.semantic-context-tokens"),
          int("runtime.budget.max-evidence-items"),
          int("runtime.budget.chars-per-token"),
          int("runtime.budget.max-input-tokens-per-question"),
          int("runtime.budget.max-output-tokens-per-question")
        ),
        AuditConfig(int("runtime.audit.ttl-days"))
      ),
      graphRead = GraphReadConfig(
        int("graph-read.max-results"),
        int("graph-read.id-batch-size"),
        int("graph-read.path-max-frontier"),
        int("graph-read.path-timeout-ms"),
        int("graph-read.community-members-limit"),
        int("graph-read.community-neighbors-limit"),
        int("graph-read.community-facet-member-cap"),
        int("graph-read.facet-limit"),
        int("graph-read.lookup-limit"),
        int("graph-read.max-lookup-ids"),
        int("graph-read.neighbors-max-depth"),
        int("graph-read.neighbors-max-limit")
      )
    )

  // S3 general purpose bucket naming: 3 to 63 of a-z 0-9 . -, starting and ending alphanumeric.
  private val BucketName = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]".r
  private val PrefixShape = "[a-z0-9][a-z0-9-]*/".r
  // DynamoDB table names allow A-Z a-z 0-9 _ . - (3 to 255); 200 leaves room for the logical name.
  private val TablePrefix = "[A-Za-z0-9_.-]{1,200}".r
  private val IamRoleArn = "arn:aws[a-z-]*:iam::\\d{12}:role/[A-Za-z0-9+=,.@_/-]+".r

  /** Rule violations of a loaded configuration; empty means valid. */
  def validate(c: ScholarChainConfig): List[String] =
    def check(ok: Boolean, msg: String) = if ok then Nil else List(msg)
    val er = c.entityResolution
    val rt = c.runtime
    val placeholders = Seq(
      c.awsAccountId,
      c.storage.bucket,
      c.neptune.endpoint,
      c.neptune.loaderRoleArn,
      c.dynamoDb.tablePrefix,
      c.s3Vectors.vectorBucket,
      c.models.embeddingModel,
      c.models.plannerModel,
      c.models.synthesisModel,
      c.models.verificationModel,
      c.models.adjudicationModel
    ).filter(v =>
      v.startsWith("example-") || v.endsWith(".invalid") || v == "000000000000" ||
        v.contains(":000000000000:") || v.contains("/example-")
    )
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
      check(
        BucketName.matches(c.storage.bucket),
        s"storage.bucket '${c.storage.bucket}' is not a valid S3 bucket name"
      ) ++
      c.storage.prefixes.all.toList.flatMap(p =>
        check(
          PrefixShape.matches(p),
          s"storage prefix '$p' must be one lower-case segment ending in '/'"
        )
      ) ++
      check(
        c.storage.prefixes.all.distinct.size == c.storage.prefixes.all.size,
        "storage prefixes must be distinct"
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
        TablePrefix.matches(c.dynamoDb.tablePrefix),
        s"dynamodb.table-prefix '${c.dynamoDb.tablePrefix}' must be 1 to 200 of A-Z a-z 0-9 _ . -"
      ) ++
      check(
        IamRoleArn.matches(c.neptune.loaderRoleArn),
        s"neptune.loader-role-arn '${c.neptune.loaderRoleArn}' is not an IAM role ARN"
      ) ++
      check(c.analytics.rankingSize >= 1, "analytics.ranking-size must be positive") ++
      check(rt.promptsDir.trim.nonEmpty, "runtime.prompts-dir must not be blank") ++
      Seq(
        "runtime.planner.prompt-version" -> rt.planner.promptVersion,
        "runtime.synthesis.prompt-version" -> rt.synthesis.promptVersion,
        "runtime.resolver.policy-version" -> rt.resolver.policyVersion
      ).toList.flatMap((k, v) => check(IdFormat.Version.matches(v), s"$k '$v' is malformed")) ++
      check(
        rt.positiveInts.forall(_ >= 1) && c.graphRead.all.forall(_ >= 1),
        "runtime and graph-read limits, budgets, rates, and timeouts must be positive"
      ) ++
      // Phase 9 rulings 3 and 11: Haiku at most 8 requests per minute (quota 10), one retry per
      // call; Titan at most 50 per minute.
      check(rt.llm.requestsPerMinute <= 8, "runtime.llm.requests-per-minute must be at most 8") ++
      check(
        rt.llm.maxAttemptsPerCall <= 2,
        "runtime.llm.max-attempts-per-call must be at most 2"
      ) ++
      check(
        rt.embedding.requestsPerMinute <= 50,
        "runtime.embedding.requests-per-minute must be at most 50"
      ) ++
      check(
        rt.llm.temperature >= 0 && rt.llm.temperature <= 1,
        "runtime.llm.temperature must be within [0, 1]"
      ) ++
      // Above 0.5, so at most one candidate can be dominant.
      check(
        rt.resolver.dominantShare > 0.5 && rt.resolver.dominantShare <= 1,
        "runtime.resolver.dominant-share must be above 0.5 and at most 1"
      ) ++
      check(
        rt.resolver.maxCandidates <= 1000,
        "runtime.resolver.max-candidates must be at most 1000"
      ) ++
      check(
        rt.budget.graphEvidenceTokens + rt.budget.semanticContextTokens <=
          c.retrieval.contextTokenBudget,
        "runtime.budget graph-evidence-tokens + semantic-context-tokens must be at most " +
          "retrieval.context-token-budget"
      ) ++
      check(
        rt.rankings.defaultTopN <= c.analytics.rankingSize &&
          rt.rankings.defaultTopN <= rt.rankings.maxTopN,
        "runtime.rankings.default-top-n must be at most analytics.ranking-size and max-top-n"
      ) ++
      check(c.retrieval.maxPathDepth <= 6, "retrieval.max-path-depth must be at most 6") ++
      check(
        c.environment == "local" || placeholders.isEmpty,
        s"environment '${c.environment}' still uses placeholder values: ${placeholders.mkString(", ")}"
      )
