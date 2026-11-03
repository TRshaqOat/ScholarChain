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
    assert(c.storage.rawBucket == "example-scholarchain-raw")
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

  test("precedence: the config file overrides the reference") {
    val f = file("scholarchain.storage.raw-bucket = file-raw\nscholarchain.neptune.port = 9000")
    val c = load(ConfigLoader.ConfigFileEnv -> f.toString)
    assert(c.storage.rawBucket == "file-raw")
    assert(c.neptune.port == 9000)
    assert(c.storage.curatedBucket == "example-scholarchain-curated") // untouched keys fall through
  }

  test("precedence: environment variables override the config file and the reference") {
    val f = file("scholarchain.storage.raw-bucket = file-raw\nscholarchain.neptune.port = 9000")
    val c = load(
      ConfigLoader.ConfigFileEnv -> f.toString,
      "SCHOLARCHAIN_RAW_BUCKET" -> "env-raw",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "9100"
    )
    assert(c.storage.rawBucket == "env-raw")
    assert(c.neptune.port == 9100)
  }

  test("unlisted environment variables are ignored") {
    assert(
      load("RAW_BUCKET" -> "x", "scholarchain.storage.raw-bucket" -> "y").storage.rawBucket ==
        "example-scholarchain-raw"
    )
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
      Map("SCHOLARCHAIN_ENVIRONMENT" -> "dev", "SCHOLARCHAIN_RAW_BUCKET" -> "real")
    )
    val errors = result.left.toOption.get
    assert(errors.exists(_.contains("still uses placeholder values")))
    assert(!errors.mkString.contains("real"))
  }

  test("a non-local environment with every value supplied loads") {
    val env = ConfigLoader.EnvOverrides.keys.map(k => k -> s"value-${k.toLowerCase}").toMap ++ Map(
      "SCHOLARCHAIN_ENVIRONMENT" -> "dev",
      "SCHOLARCHAIN_AWS_ACCOUNT_ID" -> "111111111111",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "8182",
      "SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS" -> "5000",
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
