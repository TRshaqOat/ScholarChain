# Every value the scripts and the ECS tasks need. No output holds a secret value: the API key output
# is the secret's ARN and name only. Runtime outputs are null while runtime_enabled is false.

output "runtime_enabled" {
  value = var.runtime_enabled
}

output "account_id" {
  value = module.foundation.account_id
}

output "region" {
  value = module.foundation.region
}

output "name_prefix" {
  value = local.name
}

# ---------------------------------------------------------------------------------------------
# Data bucket and network
# ---------------------------------------------------------------------------------------------

output "data_bucket_name" {
  description = "SCHOLARCHAIN_BUCKET."
  value       = module.data.bucket_name
}

output "data_bucket_prefixes" {
  value = module.data.prefixes
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "Subnets for EMR clusters and one-off ECS tasks (public IP, no NAT)."
  value       = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

# ---------------------------------------------------------------------------------------------
# EMR (scripts/run-bounded-pipeline.sh)
# ---------------------------------------------------------------------------------------------

output "emr" {
  description = "Inputs of each ephemeral cluster (ruling 2 bounds: 1 primary, at most 2 core, Graviton, on-demand)."
  value = {
    release_label             = var.emr_release_label
    instance_type             = var.emr_instance_type
    primary_instance_count    = 1
    core_instance_count       = var.emr_core_instance_count
    service_role              = module.emr.service_role_name
    instance_profile          = module.emr.instance_profile_name
    subnet_id                 = module.network.public_subnet_ids[0]
    primary_security_group_id = module.emr.primary_security_group_id
    core_security_group_id    = module.emr.core_security_group_id
    log_uri                   = module.emr.log_uri
    artifacts_prefix          = "artifacts/jobs/"
  }
}

# ---------------------------------------------------------------------------------------------
# Image build (scripts/package.sh)
# ---------------------------------------------------------------------------------------------

output "ecr_repository_url" {
  value = module.build.repository_url
}

output "ecr_repository_name" {
  value = module.build.repository_name
}

output "codebuild_project_name" {
  value = module.build.codebuild_project_name
}

output "codebuild_source_prefix" {
  value = module.build.codebuild_source_prefix
}

output "log_group_names" {
  value = merge(module.observability.log_group_names, { codebuild = module.build.codebuild_log_group })
}

# ---------------------------------------------------------------------------------------------
# Runtime (null while runtime_enabled is false)
# ---------------------------------------------------------------------------------------------

output "vector_bucket_name" {
  description = "SCHOLARCHAIN_VECTOR_BUCKET."
  value       = one(module.vectors[*].vector_bucket_name)
}

output "dynamodb_table_prefix" {
  description = "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX."
  value       = var.runtime_enabled ? var.dynamodb_table_prefix : null
}

output "dynamodb_table_names" {
  value = one(module.dynamodb[*].table_names)
}

output "job_queue_url" {
  description = "SCHOLARCHAIN_JOB_QUEUE_URL."
  value       = one(module.messaging[*].queue_url)
}

output "job_dlq_url" {
  description = "SCHOLARCHAIN_JOB_DLQ_URL."
  value       = one(module.messaging[*].dlq_url)
}

output "api_key_secret_arn" {
  description = "ARN of the API key secret (no value)."
  value       = one(module.apikey[*].api_key_secret_arn)
}

output "api_key_secret_name" {
  value = one(module.apikey[*].api_key_secret_name)
}

output "neptune_endpoint" {
  description = "SCHOLARCHAIN_NEPTUNE_ENDPOINT."
  value       = one(module.neptune[*].endpoint)
}

output "neptune_port" {
  value = var.runtime_enabled ? local.neptune_port : null
}

output "neptune_cluster_id" {
  value = one(module.neptune[*].cluster_id)
}

output "neptune_loader_role_arn" {
  description = "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN (the role Neptune assumes to read graph/)."
  value       = one(module.neptune[*].bulk_loader_role_arn)
}

output "ecs_cluster_name" {
  value = one(module.ecs[*].cluster_name)
}

output "ecs_service_names" {
  value = one(module.ecs[*].service_names)
}

output "ecs_task_definition_arns" {
  value = one(module.ecs[*].task_definition_arns)
}

output "ecs_task_definition_families" {
  value = one(module.ecs[*].task_definition_families)
}

output "alb_dns_name" {
  value = one(module.ecs[*].alb_dns_name)
}

output "alb_arn_suffix" {
  description = "LoadBalancer dimension of the ALB metrics (scripts/archive-evidence.sh)."
  value       = one(module.ecs[*].alb_arn_suffix)
}

output "runtime_environment" {
  description = "The common SCHOLARCHAIN_* task variables (no secret; the API key is injected by ARN), for scripts/load-stores.sh."
  value       = one(module.ecs[*].environment)
}

output "task_role_arns" {
  value = one(module.iam[*].task_role_arns)
}

output "execution_role_arns" {
  value = one(module.iam[*].execution_role_arns)
}

output "security_group_ids" {
  description = "Runtime security groups by role; loader is the group for one-off tasks."
  value       = module.network.security_group_ids
}

output "dashboard_name" {
  value = module.observability.dashboard_name
}
