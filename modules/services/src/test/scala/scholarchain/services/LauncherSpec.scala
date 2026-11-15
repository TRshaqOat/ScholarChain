package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader
import scholarchain.storage.live.LiveTarget

/** Phase 12 work order 6: live mode without --profile (an ECS task) selects the SDK default
  * credentials chain. The arguments and variable names are those of the ECS task definitions
  * (infra/terraform/modules/ecs/main.tf locals.args, envs/course/main.tf module "ecs"); the values
  * are non-placeholder stand-ins. Nothing here builds a client or calls AWS.
  */
class LauncherSpec extends AnyFunSuite:
  private val ecsArgs = Seq(
    "--live",
    "--region",
    "us-east-2",
    "--snapshot",
    "2026-10-03",
    "--graph-run",
    "run-" + "a" * 32,
    "--items-run",
    "run-" + "b" * 32,
    "--vectors-run",
    "run-" + "c" * 32
  )
  private val haiku = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
  private val ecsEnv = Map(
    "SCHOLARCHAIN_ENVIRONMENT" -> "course",
    "SCHOLARCHAIN_AWS_REGION" -> "us-east-2",
    "SCHOLARCHAIN_AWS_ACCOUNT_ID" -> "123456789012",
    "SCHOLARCHAIN_BUCKET" -> "scholarchain-course-data-123456789012",
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT" -> "sc.cluster-x.us-east-2.neptune.amazonaws.com",
    "SCHOLARCHAIN_NEPTUNE_PORT" -> "8182",
    "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN" -> "arn:aws:iam::123456789012:role/sc-neptune-loader",
    "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> "scholarchain-course-",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "scholarchain-course-vectors",
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "amazon.titan-embed-text-v2:0",
    "SCHOLARCHAIN_PLANNER_MODEL_ID" -> haiku,
    "SCHOLARCHAIN_SYNTHESIS_MODEL_ID" -> haiku,
    "SCHOLARCHAIN_VERIFICATION_MODEL_ID" -> haiku,
    "SCHOLARCHAIN_ADJUDICATION_MODEL_ID" -> haiku,
    "SCHOLARCHAIN_PROMPTS_DIR" -> "/opt/docker/prompts",
    "SCHOLARCHAIN_STUB_LLM" -> "false",
    "SCHOLARCHAIN_JOB_QUEUE_URL" -> "https://sqs.us-east-2.amazonaws.com/123456789012/sc-jobs",
    "SCHOLARCHAIN_JOB_DLQ_URL" -> "https://sqs.us-east-2.amazonaws.com/123456789012/sc-jobs-dlq",
    "SCHOLARCHAIN_JOB_MAX_RECEIVE_COUNT" -> "3",
    "SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE" -> "4"
  )

  test("ECS form: --live without --profile gives profileName None; --profile x gives Some(x)") {
    // query-api and worker add the window-summary run; graph-evidence-api does not (ruling 14a).
    Seq(ecsEnv, ecsEnv + ("SCHOLARCHAIN_WINDOW_SUMMARY_RUN" -> ("run-" + "d" * 32))).foreach {
      env =>
        val cfg = ConfigLoader.load(env).fold(e => fail(e.mkString("; ")), identity)
        assert(cfg.runtime.llm.requestsPerMinute == 4 && !cfg.services.stubLlm)
        val a = Launcher.parse(ecsArgs).fold(fail(_), identity)
        val t = Launcher.liveTarget(a, cfg)
        assert(t == LiveTarget(None, "us-east-2"))
        assert(t.profile == LiveTarget.DefaultChainLabel)
        val named = Launcher.parse(ecsArgs ++ Seq("--profile", "x")).fold(fail(_), identity)
        assert(Launcher.liveTarget(named, cfg).profileName == Some("x"))
    }
    val cfg = ConfigLoader.load(ecsEnv + ("SCHOLARCHAIN_AWS_REGION" -> "eu-west-1")).toOption.get
    // No --region: the core application.region.
    val noRegion = Launcher.parse(Seq("--live")).fold(fail(_), identity)
    assert(Launcher.liveTarget(noRegion, cfg) == LiveTarget(None, "eu-west-1"))
  }

  test("--live alone is no longer refused for --profile; the next missing argument is named") {
    val cfg = ConfigLoader.load(ecsEnv).fold(e => fail(e.mkString("; ")), identity)
    val a = Launcher.parse(Seq("--live")).fold(fail(_), identity)
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    assert(Launcher.backend(a, cfg, out += _) == Left("missing --snapshot"))
    assert(out.isEmpty)
    assert(Launcher.Usage.contains("--live [--profile <name>]"))
  }
