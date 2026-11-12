package scholarchain.core.config

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.CollaborationMetric

import java.nio.file.{Files, Path}

class ConfigLoaderSpec extends AnyFunSuite:
  private def file(hocon: String): Path =
    val f = Files.createTempFile("scholarchain-test-", ".conf")
    f.toFile.deleteOnExit()
    Files.writeString(f, hocon)

  private def load(env: (String, String)*): ScholarChainConfig =
    ConfigLoader.load(env.toMap).fold(es => fail(es.mkString("; ")), identity)

  test("the reference file alone loads as a valid local configuration") {
    val c = load()
    assert(c.configVersion == "config-v1")
    assert(c.environment == "local")
    assert(c.region == "us-east-2")
    assert(c.storage.bucket == "example-scholarchain")
    assert(c.s3Vectors.indexes("researchers") == "researcher-profile-v1")
    assert(
      c.analytics.bridgeMeasures == Vector(
        CollaborationMetric.CROSS_COMMUNITY_DEGREE,
        CollaborationMetric.COMMUNITIES_CONNECTED,
        CollaborationMetric.PARTICIPATION_COEFFICIENT
      )
    )
  }

  test("default graph windows are the three D-10 windows") {
    assert(
      load().graph.windows == Vector(
        TimeWindow("all-years", 2000, 2026),
        TimeWindow("2000-2014", 2000, 2014),
        TimeWindow("2015-2026", 2015, 2026)
      )
    )
  }

  test("one bucket per environment with the eight ruling-7 prefixes") {
    val c = load().storage
    assert(
      c.prefixes.all == Vector(
        "raw/",
        "curated/",
        "graph/",
        "semantic-context/",
        "vectors/",
        "reports/",
        "logs/",
        "runs/"
      )
    )
    assert(ConfigLoader.RemovedKeys.forall(k => !ConfigLoader.merged(Map.empty).hasPath(k)))
    assert(!ConfigLoader.EnvOverrides.contains("SCHOLARCHAIN_RAW_BUCKET"))
  }

  test("a layer that still sets a removed per-purpose bucket key is rejected") {
    val f = file("scholarchain.storage.graph-bucket = old-graph")
    val errors = ConfigLoader.load(Map(ConfigLoader.ConfigFileEnv -> f.toString)).left.toOption.get
    assert(errors == List("removed configuration key 'storage.graph-bucket': use storage.bucket"))
  }

  test("precedence: the config file overrides the reference") {
    val f = file("scholarchain.storage.bucket = file-bucket\nscholarchain.neptune.port = 9000")
    val c = load(ConfigLoader.ConfigFileEnv -> f.toString)
    assert(c.storage.bucket == "file-bucket")
    assert(c.neptune.port == 9000)
    assert(c.storage.prefixes.graph == "graph/") // untouched keys fall through
  }

  test("precedence: environment variables override the config file and the reference") {
    val f = file("scholarchain.storage.bucket = file-bucket\nscholarchain.neptune.port = 9000")
    val c = load(
      ConfigLoader.ConfigFileEnv -> f.toString,
      "SCHOLARCHAIN_BUCKET" -> "env-bucket",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "9100"
    )
    assert(c.storage.bucket == "env-bucket")
    assert(c.neptune.port == 9100)
  }

  test("unlisted environment variables are ignored") {
    assert(
      load("BUCKET" -> "x", "scholarchain.storage.bucket" -> "y").storage.bucket ==
        "example-scholarchain"
    )
  }

  test("an invalid bucket name or malformed or duplicate prefix is rejected") {
    def errors(env: (String, String)*) = ConfigLoader.load(env.toMap).left.toOption.get
    assert(errors("SCHOLARCHAIN_BUCKET" -> "s3://Bad_Bucket").exists(_.contains("valid S3 bucket")))
    for bad <- Seq("raw", "/raw/", "raw/x/", "Raw/", "") do
      val f = file(s"""scholarchain.storage.prefixes.raw = "$bad"""")
      assert(
        errors(ConfigLoader.ConfigFileEnv -> f.toString)
          .exists(_.contains("one lower-case segment")),
        bad
      )
    val dup = file("scholarchain.storage.prefixes.logs = \"runs/\"")
    assert(errors(ConfigLoader.ConfigFileEnv -> dup.toString).exists(_.contains("distinct")))
  }

  test("every documented environment variable maps to a path in the reference") {
    val reference = ConfigLoader.merged(Map.empty)
    for (env, path) <- ConfigLoader.EnvOverrides do
      assert(reference.hasPath(path), s"$env -> $path")
  }

  test("a missing config file is an error, not a silent fallback") {
    val result =
      ConfigLoader.load(Map(ConfigLoader.ConfigFileEnv -> "/nonexistent/scholarchain.conf"))
    assert(result.isLeft)
  }

  test("a non-local environment must replace every placeholder") {
    val result = ConfigLoader.load(
      Map("SCHOLARCHAIN_ENVIRONMENT" -> "dev", "SCHOLARCHAIN_BUCKET" -> "real-bucket")
    )
    val errors = result.left.toOption.get
    assert(errors.exists(_.contains("still uses placeholder values")))
    assert(errors.exists(_.contains("example-scholarchain-vectors")))
    assert(!errors.mkString.contains("real-bucket"))
  }

  test("a non-local environment with every value supplied loads") {
    val env = ConfigLoader.EnvOverrides.keys.map(k => k -> s"value-${k.toLowerCase}").toMap ++ Map(
      "SCHOLARCHAIN_ENVIRONMENT" -> "dev",
      "SCHOLARCHAIN_AWS_ACCOUNT_ID" -> "111111111111",
      "SCHOLARCHAIN_BUCKET" -> "scholarchain-dev-111111111111-us-east-2",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "8182",
      "SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS" -> "5000",
      "SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "1000",
      "SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE" -> "100",
      "SCHOLARCHAIN_ER_POLICY_VERSION" -> "er-v1",
      "SCHOLARCHAIN_GRAPH_POLICY_VERSION" -> "graph-v1",
      "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" ->
        "arn:aws:iam::111111111111:role/scholarchain-dev-neptune-loader",
      "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> "scholarchain-dev-"
    )
    assert(ConfigLoader.load(env).map(_.environment) == Right("dev"))
  }

  test("M10: table prefix and Neptune loader role ARN are keys with env overrides") {
    val c = load()
    assert(c.dynamoDb.tablePrefix == "example-scholarchain-")
    assert(c.neptune.loaderRoleArn == "arn:aws:iam::000000000000:role/example-neptune-loader")
    val arn = "arn:aws:iam::111111111111:role/scholarchain-dev-neptune-loader"
    val o = load(
      "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> "scholarchain-dev-",
      "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" -> arn
    )
    assert(o.dynamoDb.tablePrefix == "scholarchain-dev-" && o.neptune.loaderRoleArn == arn)
  }

  test("M10: malformed prefixes and ARNs are rejected; placeholders are rejected outside local") {
    def errors(vars: (String, String)*) = ConfigLoader.load(vars.toMap).left.toOption.get
    for bad <- Seq("bad prefix", "a/b", "x" * 201) do
      assert(
        errors("SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> bad).exists(_.contains("table-prefix")),
        bad
      )
    for bad <- Seq("scholarchain-loader", "arn:aws:iam::1234:role/x", "arn:aws:s3:::bucket") do
      assert(
        errors("SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" -> bad).exists(_.contains("IAM role ARN")),
        bad
      )
    val dev = errors("SCHOLARCHAIN_ENVIRONMENT" -> "dev").mkString
    assert(dev.contains("role/example-neptune-loader, example-scholarchain-, "))
    val keptRole = errors(
      "SCHOLARCHAIN_ENVIRONMENT" -> "dev",
      "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" ->
        "arn:aws:iam::111111111111:role/example-neptune-loader"
    ).mkString
    assert(keptRole.contains("111111111111:role/example-neptune-loader"))
  }

  test("Phase 9: planner and synthesis models default to Haiku 4.5 and are env-overridable") {
    val haiku = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
    val m = load().models
    assert(m.plannerModel == haiku && m.synthesisModel == haiku)
    assert(load("SCHOLARCHAIN_PLANNER_MODEL_ID" -> "p").models.plannerModel == "p")
    assert(load("SCHOLARCHAIN_PROMPTS_DIR" -> "/opt/prompts").runtime.promptsDir == "/opt/prompts")
    val dev = ConfigLoader.load(Map("SCHOLARCHAIN_ENVIRONMENT" -> "dev")).left.toOption.get
    assert(!dev.mkString.contains(haiku)) // a real model ID is not a placeholder
    val ex = ConfigLoader.load(
      Map("SCHOLARCHAIN_ENVIRONMENT" -> "dev", "SCHOLARCHAIN_PLANNER_MODEL_ID" -> "example-p")
    )
    assert(ex.left.toOption.get.mkString.contains("example-p"))
  }

  test("Phase 9: runtime, ranking, and graph-read defaults (RUNTIME.md section 20)") {
    val c = load()
    val r = c.runtime
    assert(r.promptsDir == "prompts")
    assert(r.question == QuestionConfig(2000, 180000))
    assert(r.planner == PlannerConfig("planner-v2", 800, 6, 4, 200, 5))
    assert(r.synthesis == SynthesisConfig("synthesis-v2", 2000, 20)) // ruling 28
    assert(r.llm == LlmCallConfig(8, 2, 30000, 0.0))
    assert(r.embedding == EmbeddingCallConfig(50, 10000))
    assert(r.resolver == ResolverConfig("dominant-name-v1", 0.9, 5, 500, 3, 3))
    assert(r.rankings == RankingsConfig(10, 1000))
    assert(r.semantic == SemanticConfig(5, 3))
    assert(r.budget == BudgetConfig(8000, 3000, 300, 3, 40000, 5600))
    assert(r.audit == AuditConfig(30))
    assert(c.analytics.rankingSize == 50)
    assert(
      c.graphRead == GraphReadConfig(1000, 100, 50000, 20000, 100, 20, 2000, 10, 20, 10, 3, 200)
    )
  }

  test("Phase 9: runtime bounds and cross-key checks are enforced") {
    def errors(hocon: String): String =
      val f = file(hocon)
      ConfigLoader.load(Map(ConfigLoader.ConfigFileEnv -> f.toString)).left.toOption.get.mkString
    val cases = Seq(
      "runtime.llm.requests-per-minute = 9" -> "requests-per-minute must be at most 8",
      "runtime.llm.max-attempts-per-call = 3" -> "max-attempts-per-call must be at most 2",
      "runtime.embedding.requests-per-minute = 51" -> "at most 50",
      "runtime.llm.temperature = 1.5" -> "temperature must be within",
      "runtime.resolver.dominant-share = 0.5" -> "dominant-share must be above 0.5",
      "runtime.resolver.dominant-share = 1.01" -> "dominant-share must be above 0.5",
      "runtime.resolver.max-candidates = 1001" -> "max-candidates must be at most 1000",
      "runtime.budget.graph-evidence-tokens = 9001" -> "retrieval.context-token-budget",
      "runtime.rankings.default-top-n = 51" -> "default-top-n must be at most",
      "analytics.ranking-size = 0" -> "ranking-size must be positive",
      "retrieval.max-path-depth = 7" -> "max-path-depth must be at most 6",
      "runtime.audit.ttl-days = 0" -> "must be positive",
      "graph-read.path-timeout-ms = 0" -> "must be positive",
      "runtime.planner.prompt-version = \"planner v1\"" -> "prompt-version",
      "runtime.prompts-dir = \" \"" -> "prompts-dir must not be blank"
    )
    for (hocon, msg) <- cases do assert(errors(s"scholarchain.$hocon").contains(msg), hocon)
  }

  test("every runtime and graph-read leaf in the reference is read by the loader") {
    import scala.jdk.CollectionConverters.*
    val reference = ConfigLoader.merged(Map.empty)
    val leaves = Seq("runtime", "graph-read").flatMap(s =>
      reference.getConfig(s).entrySet.asScala.map(e => s"$s.${e.getKey}")
    )
    val c = load()
    // Typed fields: the Int lists, plus prompts-dir, three versions, temperature, dominant-share.
    val typed = c.runtime.positiveInts.size + c.graphRead.all.size + 1 + 3 + 2
    assert(leaves.size == typed, leaves.sorted.mkString(", "))
  }

  test("entity-resolution thresholds must be ordered within [0, 1]") {
    val f = file("scholarchain.entity-resolution.reject-threshold = 0.8")
    val result = ConfigLoader.load(Map(ConfigLoader.ConfigFileEnv -> f.toString))
    assert(result.left.toOption.get.exists(_.contains("entity-resolution thresholds")))
  }

  test("entity-resolution policy version defaults to er-v2 and is env-overridable") {
    assert(load().entityResolution.policyVersion == "er-v2")
    val overridden = load("SCHOLARCHAIN_ER_POLICY_VERSION" -> "er-v1").entityResolution
    assert(overridden.policyVersion == "er-v1")
  }

  test("entity-resolution block and conflict-group limits: defaults, env overrides, positive") {
    val er = load().entityResolution
    assert(er.maxBlockSize == 150 && er.maxConflictGroupSize == 100)
    val f = file("scholarchain.entity-resolution.max-block-size = 7")
    val c = load(
      ConfigLoader.ConfigFileEnv -> f.toString,
      "SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE" -> "9"
    ).entityResolution
    assert(c.maxBlockSize == 7 && c.maxConflictGroupSize == 9)
    assert(load("SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "11").entityResolution.maxBlockSize == 11)
    for (k, v) <- Seq(
        "SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "0",
        "SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE" -> "-1"
      )
    do
      val errors = ConfigLoader.load(Map(k -> v)).left.toOption.get
      assert(errors.exists(_.contains("max-conflict-group-size must be positive")), s"$k=$v")
  }

  test("a window whose start year exceeds its end year is rejected") {
    val f = file(
      """scholarchain.graph.windows = [{ name = "bad", start-year = 2020, end-year = 2010 }]"""
    )
    val result = ConfigLoader.load(Map(ConfigLoader.ConfigFileEnv -> f.toString))
    assert(result.left.toOption.get.exists(_.contains("start year 2020 exceeds end year 2010")))
  }

  test("an unsupported config version is rejected") {
    val f = file("scholarchain.config-version = config-v0")
    val result = ConfigLoader.load(Map(ConfigLoader.ConfigFileEnv -> f.toString))
    assert(result.left.toOption.get.exists(_.contains("unsupported config-version")))
  }
