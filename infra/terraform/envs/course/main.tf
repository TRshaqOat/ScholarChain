# ScholarChain course environment (ruling 2: one root module composed of infra/terraform/modules).
# Two cost stages (D-13): runtime_enabled = false is cloud window 1; true adds the runtime stores,
# services, and alarms for cloud window 2. See infra/terraform/README.md.

locals {
  name = module.foundation.name_prefix
  rt   = var.runtime_enabled ? 1 : 0

  # Core services.*.port defaults (modules/core scholarchain.conf).
  service_ports = {
    query_api          = 8080
    graph_evidence_api = 8081
  }
  neptune_port      = 8182 # core neptune.port
  max_receive_count = 3    # core services.queue.max-receive-count
}

# ---------------------------------------------------------------------------------------------
# Always (cloud window 1)
# ---------------------------------------------------------------------------------------------

module "foundation" {
  source             = "../../modules/foundation"
  project            = "scholarchain"
  environment        = var.environment
  availability_zones = var.availability_zones
}

module "network" {
  source                  = "../../modules/network"
  name                    = local.name
  region                  = module.foundation.region
  vpc_cidr                = var.vpc_cidr
  azs                     = module.foundation.azs
  runtime_enabled         = var.runtime_enabled
  alb_ingress_cidr_blocks = var.alb_ingress_cidr_blocks
  service_ports           = local.service_ports
  neptune_port            = local.neptune_port
}

module "data" {
  source                             = "../../modules/data"
  name                               = local.name
  account_id                         = module.foundation.account_id
  region                             = module.foundation.region
  force_destroy                      = var.data_bucket_force_destroy
  logs_expiration_days               = var.logs_expiration_days
  artifacts_expiration_days          = var.artifacts_expiration_days
  noncurrent_version_expiration_days = var.noncurrent_version_expiration_days
}

module "emr" {
  source           = "../../modules/emr"
  name             = local.name
  account_id       = module.foundation.account_id
  region           = module.foundation.region
  partition        = module.foundation.partition
  vpc_id           = module.network.vpc_id
  data_bucket_arn  = module.data.bucket_arn
  data_bucket_name = module.data.bucket_name
}

module "build" {
  source             = "../../modules/build"
  name               = local.name
  account_id         = module.foundation.account_id
  region             = module.foundation.region
  data_bucket_arn    = module.data.bucket_arn
  data_bucket_name   = module.data.bucket_name
  log_retention_days = var.log_retention_days
}

# Log groups always; alarms, dashboard, and SNS only with runtime_enabled.
module "observability" {
  source              = "../../modules/observability"
  name                = local.name
  region              = module.foundation.region
  runtime_enabled     = var.runtime_enabled
  log_retention_days  = var.log_retention_days
  alarm_email         = var.alarm_email
  alb_5xx_threshold   = var.alb_5xx_threshold
  cpu_alarm_threshold = var.cpu_alarm_threshold

  alb_arn_suffix            = one(module.ecs[*].alb_arn_suffix)
  target_group_arn_suffixes = one(module.ecs[*].target_group_arn_suffixes)
  cluster_name              = one(module.ecs[*].cluster_name)
  service_names             = one(module.ecs[*].service_names)
  dlq_name                  = one(module.messaging[*].dlq_name)
  neptune_cluster_id        = one(module.neptune[*].cluster_id)
}

# ---------------------------------------------------------------------------------------------
# runtime_enabled (cloud window 2)
# ---------------------------------------------------------------------------------------------

module "dynamodb" {
  source                 = "../../modules/dynamodb"
  count                  = local.rt
  table_prefix           = var.dynamodb_table_prefix
  deletion_protection    = var.dynamodb_deletion_protection
  point_in_time_recovery = var.dynamodb_point_in_time_recovery
}

module "vectors" {
  source     = "../../modules/vectors"
  count      = local.rt
  name       = local.name
  account_id = module.foundation.account_id
}

module "messaging" {
  source            = "../../modules/messaging"
  count             = local.rt
  name              = local.name
  max_receive_count = local.max_receive_count
}

# The work order's "secrets" module (named apikey: the permission policy denies a secrets/ path).
module "apikey" {
  source               = "../../modules/apikey"
  count                = local.rt
  name                 = local.name
  recovery_window_days = var.secret_recovery_window_days
}

module "neptune" {
  source                 = "../../modules/neptune"
  count                  = local.rt
  name                   = local.name
  account_id             = module.foundation.account_id
  private_subnet_ids     = module.network.private_subnet_ids
  security_group_id      = module.network.security_group_ids.neptune
  engine_version         = var.neptune_engine_version
  parameter_group_family = var.neptune_parameter_group_family
  instance_class         = var.neptune_instance_class
  port                   = local.neptune_port
  deletion_protection    = var.neptune_deletion_protection
  skip_final_snapshot    = var.neptune_skip_final_snapshot
  data_bucket_arn        = module.data.bucket_arn
}

module "iam" {
  source                      = "../../modules/iam"
  count                       = local.rt
  name                        = local.name
  account_id                  = module.foundation.account_id
  region                      = module.foundation.region
  partition                   = module.foundation.partition
  data_bucket_arn             = module.data.bucket_arn
  table_arns                  = module.dynamodb[0].table_arns
  evidence_index_arn          = module.dynamodb[0].evidence_index_arn
  queue_arn                   = module.messaging[0].queue_arn
  api_key_secret_arn          = module.apikey[0].api_key_secret_arn
  neptune_cluster_resource_id = module.neptune[0].cluster_resource_id
  vector_index_arns           = module.vectors[0].index_arns
  ecr_repository_arn          = module.build.repository_arn
  log_group_arns              = module.observability.log_group_arns
  embedding_model_id          = var.embedding_model_id
  haiku_inference_profile_id  = var.haiku_inference_profile_id
  haiku_destination_regions   = var.haiku_destination_regions
}

module "ecs" {
  source              = "../../modules/ecs"
  count               = local.rt
  name                = local.name
  region              = module.foundation.region
  vpc_id              = module.network.vpc_id
  public_subnet_ids   = module.network.public_subnet_ids
  security_group_ids  = module.network.security_group_ids
  repository_url      = module.build.repository_url
  image_tag           = var.image_tag
  task_sizes          = var.task_sizes
  service_max_counts  = var.service_max_counts
  service_ports       = local.service_ports
  execution_role_arns = module.iam[0].execution_role_arns
  task_role_arns      = module.iam[0].task_role_arns
  log_group_names     = module.observability.log_group_names
  api_key_secret_arn  = module.apikey[0].api_key_secret_arn
  window_summary_run  = var.window_summary_run

  runtime_args = {
    snapshot    = var.runtime_snapshot
    graph_run   = var.runtime_graph_run
    items_run   = var.runtime_items_run
    vectors_run = var.runtime_vectors_run
  }

  # Core ConfigLoader.EnvOverrides. Outside environment "local" the loader refuses placeholder and
  # blank values, so every model key is set, including two the runtime does not call.
  environment = {
    SCHOLARCHAIN_ENVIRONMENT             = var.environment
    SCHOLARCHAIN_AWS_REGION              = module.foundation.region
    SCHOLARCHAIN_AWS_ACCOUNT_ID          = module.foundation.account_id
    SCHOLARCHAIN_BUCKET                  = module.data.bucket_name
    SCHOLARCHAIN_NEPTUNE_ENDPOINT        = module.neptune[0].endpoint
    SCHOLARCHAIN_NEPTUNE_PORT            = tostring(local.neptune_port)
    SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN = module.neptune[0].bulk_loader_role_arn
    SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX   = var.dynamodb_table_prefix
    SCHOLARCHAIN_VECTOR_BUCKET           = module.vectors[0].vector_bucket_name
    SCHOLARCHAIN_EMBEDDING_MODEL_ID      = var.embedding_model_id
    SCHOLARCHAIN_PLANNER_MODEL_ID        = var.haiku_inference_profile_id
    SCHOLARCHAIN_SYNTHESIS_MODEL_ID      = var.haiku_inference_profile_id
    SCHOLARCHAIN_VERIFICATION_MODEL_ID   = var.haiku_inference_profile_id
    SCHOLARCHAIN_ADJUDICATION_MODEL_ID   = var.haiku_inference_profile_id
    SCHOLARCHAIN_PROMPTS_DIR             = "/opt/docker/prompts"
    SCHOLARCHAIN_STUB_LLM                = "false"
    SCHOLARCHAIN_JOB_QUEUE_URL           = module.messaging[0].queue_url
    SCHOLARCHAIN_JOB_DLQ_URL             = module.messaging[0].dlq_url
    SCHOLARCHAIN_JOB_MAX_RECEIVE_COUNT   = tostring(local.max_receive_count)
    SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE = tostring(var.llm_requests_per_minute)
  }
}
