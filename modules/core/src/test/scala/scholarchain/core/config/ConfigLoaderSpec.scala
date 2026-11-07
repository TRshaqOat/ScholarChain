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
      "SCHOLARCHAIN_GRAPH_POLICY_VERSION" -> "graph-v1"
    )
    assert(ConfigLoader.load(env).map(_.environment) == Right("dev"))
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
