package scholarchain.tests.contracts

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Phase 11 WO3, spec category "configuration omissions" (CLAUDE.md "Externalized configuration":
  * every endpoint, bucket, model ID, threshold, and algorithm parameter comes from versioned
  * configuration). An omitted, blank, or ill-typed value must stop the load with an error that
  * names the problem; it must never fall back silently or reach a service as a blank.
  */
class ConfigOmissionContractSpec extends AnyFunSuite:
  private val base = Map("SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "amazon.titan-embed-text-v2:0")

  private def withFile[A](hocon: String)(body: Map[String, String] => A): A =
    val f = Files.createTempFile("adv-config-", ".conf")
    try
      Files.writeString(f, hocon)
      body(base + ("SCHOLARCHAIN_CONFIG_FILE" -> f.toString))
    finally Files.deleteIfExists(f)

  /** Every leaf path of the reference configuration (under scholarchain). */
  private lazy val leaves: Vector[String] =
    val ref = ConfigFactory
      .parseResources(getClass.getClassLoader, ConfigLoader.ReferenceResource)
      .getConfig("scholarchain")
    ref.entrySet.asScala.map(_.getKey).toVector.sorted

  /** Real-looking values for every environment-specific key, so a non-local environment loads. */
  private val production: String =
    """scholarchain {
      |  application { environment = "prod", aws-account-id = "123456789012" }
      |  storage.bucket = "sc-prod-data"
      |  neptune { endpoint = "db.cluster-abc.us-east-2.neptune.amazonaws.com", loader-role-arn = "arn:aws:iam::123456789012:role/sc-neptune-loader" }
      |  dynamodb.table-prefix = "sc-prod-"
      |  s3-vectors.vector-bucket = "sc-prod-vectors"
      |  models {
      |    embedding-model = "amazon.titan-embed-text-v2:0"
      |    verification-model = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
      |    adjudication-model = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
      |  }
      |  services.queue {
      |    url = "https://sqs.us-east-2.amazonaws.com/123456789012/sc-prod-jobs"
      |    dead-letter-url = "https://sqs.us-east-2.amazonaws.com/123456789012/sc-prod-jobs-dlq"
      |  }
      |}""".stripMargin

  test("control: the reference loads locally, and the production-shaped file loads as prod") {
    assert(ConfigLoader.load(base).isRight)
    withFile(production)(env =>
      assert(ConfigLoader.load(env).map(_.environment) == Right("prod"), ConfigLoader.load(env))
    )
  }

  test("every configuration key omitted (set to null) stops the load with an error") {
    // No variable layer: the file's null is the highest-precedence value of every key.
    val accepted = leaves.filter { path =>
      withFile(s"scholarchain { $path = null }")(vars =>
        ConfigLoader.load(vars - "SCHOLARCHAIN_EMBEDDING_MODEL_ID").isRight
      )
    }
    assert(leaves.size > 100, s"only ${leaves.size} leaves found")
    assert(accepted.isEmpty, s"loaded with these keys omitted: ${accepted.mkString(", ")}")
  }

  test("a blank value for a required production setting is refused outside the local environment") {
    // Each of these names an endpoint, a bucket, a model, a region, or a policy version that a
    // service or job uses as is; a blank would only fail later, at the first AWS call or run ID.
    val required = Vector(
      "application.region",
      "storage.bucket",
      "neptune.endpoint",
      "neptune.loader-role-arn",
      "dynamodb.table-prefix",
      "s3-vectors.vector-bucket",
      "s3-vectors.indexes.researchers",
      "models.embedding-model",
      "models.planner-model",
      "models.synthesis-model",
      "graph.policy-version",
      "entity-resolution.policy-version",
      "retrieval.fusion-policy-version",
      "runtime.prompts-dir",
      "services.queue.url",
      "services.api-key-env"
    )
    // The file is the only layer that sets these, so the embedding variable is left unset.
    val accepted = required.filter { path =>
      withFile(production + s"\nscholarchain.$path = \"  \"")(vars =>
        ConfigLoader.load(vars - "SCHOLARCHAIN_EMBEDDING_MODEL_ID").isRight
      )
    }
    assert(accepted.isEmpty, s"a blank value loaded in prod for: ${accepted.mkString(", ")}")
  }

  test("an empty environment variable overriding a required setting is refused outside local") {
    // ECS passes an unset Terraform variable as an empty string.
    val vars = Vector(
      "SCHOLARCHAIN_BUCKET",
      "SCHOLARCHAIN_NEPTUNE_ENDPOINT",
      "SCHOLARCHAIN_VECTOR_BUCKET",
      "SCHOLARCHAIN_EMBEDDING_MODEL_ID",
      "SCHOLARCHAIN_PLANNER_MODEL_ID",
      "SCHOLARCHAIN_SYNTHESIS_MODEL_ID",
      "SCHOLARCHAIN_GRAPH_POLICY_VERSION",
      "SCHOLARCHAIN_ER_POLICY_VERSION",
      "SCHOLARCHAIN_AWS_REGION",
      "SCHOLARCHAIN_JOB_QUEUE_URL"
    )
    val accepted =
      vars.filter(v => withFile(production)(env => ConfigLoader.load(env + (v -> "")).isRight))
    assert(accepted.isEmpty, s"an empty $accepted loaded in prod")
  }

  test("ill-typed and out-of-range environment overrides are refused") {
    Vector(
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "abc",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "80000",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "0",
      "SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS" -> "-1",
      "SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "0",
      "SCHOLARCHAIN_ER_MAX_CONFLICT_GROUP_SIZE" -> "-3",
      "SCHOLARCHAIN_STUB_LLM" -> "maybe",
      "SCHOLARCHAIN_JOB_MAX_RECEIVE_COUNT" -> "0",
      "SCHOLARCHAIN_JOB_MAX_RECEIVE_COUNT" -> "1001",
      "SCHOLARCHAIN_WINDOW_SUMMARY_RUN" -> "run-NOT-HEX",
      "SCHOLARCHAIN_WINDOW_SUMMARY_RUN" -> "../../etc/passwd",
      "SCHOLARCHAIN_JOB_QUEUE_URL" -> "http://sqs.example.com/123456789012/q",
      "SCHOLARCHAIN_BUCKET" -> "Bad_Bucket_Name",
      "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> "prefix with spaces",
      "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" -> "arn:aws:iam::123:user/x"
    ).foreach((k, v) => assert(ConfigLoader.load(base + (k -> v)).isLeft, s"$k=$v loaded"))
  }

  test("a fractional value for an integer setting is refused, not truncated") {
    // A block size of 1.5 or a port of 8182.9 is a typo; loading 1 or 8182 hides it.
    val truncated = Vector(
      "SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "150.5",
      "SCHOLARCHAIN_NEPTUNE_PORT" -> "8182.9",
      "SCHOLARCHAIN_JOB_MAX_RECEIVE_COUNT" -> "3.7"
    ).filter((k, v) => ConfigLoader.load(base + (k -> v)).isRight)
    val fromFile =
      Vector("services.query-api.threads = 8.5", "graph.oversize-team-threshold = 99.9")
        .filter(h => withFile(s"scholarchain { $h }")(vars => ConfigLoader.load(vars).isRight))
    assert(truncated.isEmpty && fromFile.isEmpty, s"truncated silently: $truncated $fromFile")
  }

  test(
    "repair-01 D11 to D13 boundaries: whole decimals load, fractional years and empty indexes do not"
  ) {
    // D13: a whole number written as a decimal is that number, not a typo.
    val whole = ConfigLoader.load(base + ("SCHOLARCHAIN_NEPTUNE_PORT" -> "8182.0"))
    assert(whole.map(_.neptune.port) == Right(8182), whole)
    // D13 covers every integer key, the window years included.
    withFile(
      """scholarchain.graph.windows = [{ name = "a", start-year = 2000.5, end-year = 2010 }]"""
    )(env => assert(ConfigLoader.load(env).isLeft))
    // D11: a null index entry is refused in every environment, local too; the other indexes are
    // still named in the error-free control.
    val names = ConfigLoader.load(base).map(_.s3Vectors.indexes.keySet).getOrElse(Set.empty)
    assert(names.size == 5, names)
    names.foreach(n =>
      withFile(s"scholarchain.s3-vectors.indexes.$n = null")(env =>
        assert(ConfigLoader.load(env).isLeft, s"$n = null loaded")
      )
    )
    // D12: outside local, a value of only spaces or tabs is blank too.
    withFile(production + "\nscholarchain.neptune.endpoint = \"\\t \"")(env =>
      assert(ConfigLoader.load(env).isLeft)
    )
  }

  test("finding 2: the core community round cap equals the one the Spark jobs read") {
    // Two sources of one algorithm parameter (Phase 06 m8): the Spark jobs read
    // scholarchain-spark.conf; core keeps an equal value. This contract fails on any drift.
    val core = ConfigLoader.load(base).map(_.analytics.communityMaxIterations)
    val spark = scholarchain.spark.SparkSettings
      .parse(Seq("--input", "adv-input", "--code-version", "src-adv"))
      .map(_.communityMaxIterations)
    assert(spark == Right(10), spark)
    assert(core.toOption == spark.toOption, s"core $core, spark $spark")
  }

  test("a missing, unreadable, or malformed configuration file stops the load") {
    assert(
      ConfigLoader
        .load(base + ("SCHOLARCHAIN_CONFIG_FILE" -> "/nonexistent/adv/scholarchain.conf"))
        .isLeft
    )
    val dir = Files.createTempDirectory("adv-config-dir-")
    try assert(ConfigLoader.load(base + ("SCHOLARCHAIN_CONFIG_FILE" -> dir.toString)).isLeft)
    finally Files.deleteIfExists(dir)
    withFile("scholarchain { storage { bucket = ")(env => assert(ConfigLoader.load(env).isLeft))
    withFile("scholarchain.storage.raw-bucket = \"old-layout\"")(env =>
      assert(
        ConfigLoader
          .load(env)
          .left
          .toOption
          .exists(_.exists(_.contains("removed configuration key")))
      )
    )
    withFile("scholarchain.config-version = \"config-v0\"")(env =>
      assert(ConfigLoader.load(env).isLeft)
    )
  }

  test("placeholder values and inconsistent limits never load outside local") {
    withFile(production + "\nscholarchain.neptune.endpoint = \"neptune.example.invalid\"")(env =>
      assert(ConfigLoader.load(env).isLeft)
    )
    withFile(production + "\nscholarchain.models.embedding-model = \"example-embedding-model\"")(
      vars => assert(ConfigLoader.load(vars - "SCHOLARCHAIN_EMBEDDING_MODEL_ID").isLeft)
    )
    Vector(
      "entity-resolution { accept-threshold = 0.3, reject-threshold = 0.5 }",
      "entity-resolution.llm-band-max = 1.5",
      "graph.windows = []",
      """graph.windows = [{ name = "a", start-year = 2020, end-year = 2010 }]""",
      """graph.windows = [{ name = "a", start-year = 2000, end-year = 2010 }, { name = "a", start-year = 2011, end-year = 2012 }]""",
      "services.api-key-env = \"SCHOLARCHAIN_BUCKET\"",
      "services.api-key-env = \"lower_case\"",
      "services.worker.visibility-timeout-seconds = 60",
      "services.ttl { idempotency-hours = 500, job-hours = 24 }",
      "runtime.llm.requests-per-minute = 9",
      "runtime.llm.max-attempts-per-call = 3",
      "retrieval.max-path-depth = 7",
      "analytics.bridge-measures = [\"NOT_A_MEASURE\"]",
      "services.timeouts.neptune-ms = 10"
    ).foreach(h =>
      withFile(s"scholarchain { $h }")(env => assert(ConfigLoader.load(env).isLeft, h))
    )
  }

  test(
    "only the documented variables are read: an unknown SCHOLARCHAIN_ variable changes nothing"
  ) {
    val a = ConfigLoader.load(base).toOption.get
    val b = ConfigLoader
      .load(
        base ++ Map(
          "SCHOLARCHAIN_STORAGE_BUCKET" -> "other",
          "scholarchain.storage.bucket" -> "other"
        )
      )
      .toOption
      .get
    assert(a == b)
  }
