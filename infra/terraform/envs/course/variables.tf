# Root variables of the course environment. Values for this environment are in course.tfvars (no
# secrets). Every bound below is a default the human approved through rulings 1 and 2; changing one
# beyond its validation range is a human-gated scale change (docs/agents.md, escalation 6).

# ---------------------------------------------------------------------------------------------
# Foundation and tags
# ---------------------------------------------------------------------------------------------

variable "region" {
  description = "AWS Region (D-04)."
  type        = string
  default     = "us-east-2"

  validation {
    condition     = var.region == "us-east-2"
    error_message = "D-04 ratified us-east-2; another Region is an architecture change."
  }
}

variable "environment" {
  description = "Environment name; part of every resource name and the Environment tag."
  type        = string
  default     = "course"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment))
    error_message = "environment must be 2 to 16 lower-case letters, digits, or hyphens."
  }
}

variable "owner" {
  description = "Owner tag: a role name, never a personal name or email."
  type        = string
}

variable "expiration_date" {
  description = "Expiration tag (YYYY-MM-DD): the date after which every resource may be cleaned up."
  type        = string

  validation {
    condition     = can(regex("^20[0-9]{2}-[01][0-9]-[0-3][0-9]$", var.expiration_date))
    error_message = "expiration_date must be YYYY-MM-DD."
  }
}

variable "runtime_enabled" {
  description = "Cost stage (D-13, ruling 2). false: cloud window 1 (network, data bucket, EMR roles and security groups, ECR, CodeBuild, log groups). true: also Neptune, DynamoDB, S3 Vectors, SQS, the API key secret, ECS, the ALB, and the runtime alarms (cloud window 2)."
  type        = bool
  default     = false

  validation {
    condition     = !var.runtime_enabled || (var.image_tag != null && var.runtime_graph_run != null && var.runtime_items_run != null && var.runtime_vectors_run != null)
    error_message = "runtime_enabled = true needs image_tag (from scripts/package.sh) and runtime_graph_run, runtime_items_run, and runtime_vectors_run."
  }
}

# ---------------------------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------------------------

variable "availability_zones" {
  description = "The two AZs of the VPC (ruling 1), pinned rather than read from a data source (code review 1 F6). Neptune 1.4.8.1 on db.t4g.medium is orderable in us-east-2a and us-east-2b (ruling 18). Changing a value replaces the subnets and everything in them."
  type        = list(string)
  default     = ["us-east-2a", "us-east-2b"]

  validation {
    condition     = length(var.availability_zones) == 2 && length(distinct(var.availability_zones)) == 2 && alltrue([for z in var.availability_zones : can(regex("^${var.region}[a-z]$", z))])
    error_message = "availability_zones must be exactly two distinct AZ names in var.region (for example us-east-2a and us-east-2b)."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR; split into two public and two private /20 subnets."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) <= 16
    error_message = "vpc_cidr must be a valid CIDR of /16 or larger."
  }
}

variable "alb_ingress_cidr_blocks" {
  description = "CIDR blocks allowed to reach the ALB listener (the only broad ingress, ruling 1). Narrow it to the demo client when possible."
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition     = length(var.alb_ingress_cidr_blocks) >= 1 && alltrue([for c in var.alb_ingress_cidr_blocks : can(cidrhost(c, 0))])
    error_message = "alb_ingress_cidr_blocks must hold at least one valid IPv4 CIDR."
  }
}

# ---------------------------------------------------------------------------------------------
# Data bucket
# ---------------------------------------------------------------------------------------------

variable "data_bucket_force_destroy" {
  description = "Course-only switch: when true, destroying the bucket deletes every object version. Keep false until the instructor approves the evidence archive (spec Cleanup requirements); only scripts/cleanup.sh documents when to flip it."
  type        = bool
  default     = false
}

variable "logs_expiration_days" {
  description = "Days before current objects under logs/ expire (Phase 7 ruling 22: 30-day audit retention)."
  type        = number
  default     = 30

  validation {
    condition     = var.logs_expiration_days >= 30
    error_message = "logs/ holds 30-day runtime audits (Phase 7 ruling 22); keep at least 30 days."
  }
}

variable "artifacts_expiration_days" {
  description = "Days before current objects under artifacts/ (job jars, image build contexts) expire."
  type        = number
  default     = 90

  validation {
    condition     = var.artifacts_expiration_days >= 30
    error_message = "artifacts_expiration_days must be at least 30."
  }
}

variable "noncurrent_version_expiration_days" {
  description = "Days before noncurrent versions under logs/ and artifacts/ expire."
  type        = number
  default     = 7

  validation {
    condition     = var.noncurrent_version_expiration_days >= 1
    error_message = "noncurrent_version_expiration_days must be at least 1."
  }
}

# ---------------------------------------------------------------------------------------------
# Observability
# ---------------------------------------------------------------------------------------------

variable "log_retention_days" {
  description = "CloudWatch log retention (work order 2: 14 days)."
  type        = number
  default     = 14

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30], var.log_retention_days)
    error_message = "log_retention_days must be a CloudWatch retention value from 1 to 30."
  }
}

variable "alarm_email" {
  description = "Alarm notification email. No default on purpose: pass it as TF_VAR_alarm_email at plan time, never in course.tfvars (it is personal data). An empty string creates no SNS subscription."
  type        = string

  validation {
    condition     = var.alarm_email == "" || can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alarm_email))
    error_message = "alarm_email must be empty or an email address."
  }
}

variable "alb_5xx_threshold" {
  description = "ALB 5xx responses per 5 minutes that raise an alarm."
  type        = number
  default     = 5
}

variable "cpu_alarm_threshold" {
  description = "Average CPU percent (ECS services and Neptune) that raises an alarm."
  type        = number
  default     = 80
}

# ---------------------------------------------------------------------------------------------
# EMR (no cluster in Terraform; scripts/run-bounded-pipeline.sh launches ephemeral clusters)
# ---------------------------------------------------------------------------------------------

variable "emr_release_label" {
  description = "EMR release (D-01)."
  type        = string
  default     = "emr-spark-8.0.0"

  validation {
    condition     = var.emr_release_label == "emr-spark-8.0.0"
    error_message = "D-01 ratified emr-spark-8.0.0."
  }
}

variable "emr_instance_type" {
  description = "Graviton (ARM64) instance type for the primary and core nodes (ruling 2). On-demand only."
  type        = string
  default     = "m6g.xlarge"

  validation {
    condition     = can(regex("^[a-z][0-9]+g[a-z]*\\.[a-z0-9]+$", var.emr_instance_type))
    error_message = "emr_instance_type must be a Graviton type (for example m6g.xlarge or m7g.xlarge)."
  }
}

variable "emr_core_instance_count" {
  description = "Core nodes per ephemeral cluster (ruling 2: at most 2). The primary count is always 1."
  type        = number
  default     = 2

  validation {
    condition     = var.emr_core_instance_count >= 1 && var.emr_core_instance_count <= 2
    error_message = "Ruling 2 bounds EMR at 1 primary and at most 2 core instances."
  }
}

# ---------------------------------------------------------------------------------------------
# Runtime stores (runtime_enabled = true)
# ---------------------------------------------------------------------------------------------

variable "dynamodb_table_prefix" {
  description = "Physical DynamoDB table name prefix: the dynamodb.table-prefix value (SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX)."
  type        = string
  default     = "scholarchain-course-"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]{1,180}$", var.dynamodb_table_prefix)) && !startswith(var.dynamodb_table_prefix, "example-")
    error_message = "dynamodb_table_prefix: A-Z a-z 0-9 _ . - only (core rule), and never the example- placeholder."
  }
}

variable "dynamodb_deletion_protection" {
  description = "DynamoDB deletion protection. false for the ephemeral course runtime window (D-13: destroyed after about 48 hours; every loaded table is rebuilt from S3 by the loaders)."
  type        = bool
  default     = false
}

variable "dynamodb_point_in_time_recovery" {
  description = "Point-in-time recovery. Off for the course environment: loaded tables are rebuilt from the write-once S3 item files, runtime tables expire by TTL, and the window lasts about 48 hours."
  type        = bool
  default     = false
}

variable "neptune_engine_version" {
  description = "Neptune engine version: 1.4.6.0 or later (work order 2), at least 14 days old (D-09)."
  type        = string
  default     = "1.4.8.1"

  validation {
    condition     = can(regex("^1\\.(4\\.([6-9]|[1-9][0-9])|[5-9]\\.[0-9]+)\\.[0-9]+$", var.neptune_engine_version))
    error_message = "neptune_engine_version must be 1.4.6.0 or later (the openCypher query timeout hint needs it)."
  }
}

variable "neptune_parameter_group_family" {
  description = "Parameter group family matching neptune_engine_version."
  type        = string
  default     = "neptune1.4"
}

variable "neptune_instance_class" {
  description = "Neptune instance class: the cheapest class (ruling 2). db.t4g.medium has 2 vCPU and 4 GiB."
  type        = string
  default     = "db.t4g.medium"

  validation {
    condition     = contains(["db.t4g.medium", "db.t3.medium"], var.neptune_instance_class)
    error_message = "neptune_instance_class must stay within the approved small classes; a larger class is a human-gated scale change."
  }
}

variable "neptune_deletion_protection" {
  description = "Neptune deletion protection. false for the ephemeral course runtime window (documented; the graph is rebuilt by the bulk loader from graph/)."
  type        = bool
  default     = false
}

variable "neptune_skip_final_snapshot" {
  description = "Skip the final snapshot on destroy. true for the course window (no snapshot storage cost; the graph is reproducible from graph/)."
  type        = bool
  default     = true
}

variable "secret_recovery_window_days" {
  description = "Secrets Manager recovery window for the API key secret (7 to 30 days; 0 deletes at once). A re-create within the window needs the secret restored first."
  type        = number
  default     = 7

  validation {
    condition     = var.secret_recovery_window_days == 0 || (var.secret_recovery_window_days >= 7 && var.secret_recovery_window_days <= 30)
    error_message = "secret_recovery_window_days must be 0 or 7 to 30."
  }
}

# ---------------------------------------------------------------------------------------------
# Runtime services (runtime_enabled = true)
# ---------------------------------------------------------------------------------------------

variable "image_tag" {
  description = "Immutable services image tag: the full 40-hex Git commit SHA written by scripts/package.sh. Required when runtime_enabled."
  type        = string
  default     = null

  validation {
    condition     = var.image_tag == null || can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full 40-hex Git SHA."
  }
}

variable "runtime_snapshot" {
  description = "Served DBLP snapshot date (--snapshot)."
  type        = string
  default     = "2026-10-03"

  validation {
    condition     = can(regex("^20[0-9]{2}-[01][0-9]-[0-3][0-9]$", var.runtime_snapshot))
    error_message = "runtime_snapshot must be YYYY-MM-DD."
  }
}

variable "runtime_graph_run" {
  description = "Store-conversion run whose Neptune graph is served (--graph-run). Required when runtime_enabled."
  type        = string
  default     = null

  validation {
    condition     = var.runtime_graph_run == null || can(regex("^run-[0-9a-f]{32}$", var.runtime_graph_run))
    error_message = "runtime_graph_run must be run-<32 hex>."
  }
}

variable "runtime_items_run" {
  description = "Store-conversion run whose DynamoDB items are served (--items-run). Required when runtime_enabled."
  type        = string
  default     = null

  validation {
    condition     = var.runtime_items_run == null || can(regex("^run-[0-9a-f]{32}$", var.runtime_items_run))
    error_message = "runtime_items_run must be run-<32 hex>."
  }
}

variable "runtime_vectors_run" {
  description = "Embedding run whose vectors are served (--vectors-run). Required when runtime_enabled."
  type        = string
  default     = null

  validation {
    condition     = var.runtime_vectors_run == null || can(regex("^run-[0-9a-f]{32}$", var.runtime_vectors_run))
    error_message = "runtime_vectors_run must be run-<32 hex>."
  }
}

variable "window_summary_run" {
  description = "Load run of the served corpus window-summary object (SCHOLARCHAIN_WINDOW_SUMMARY_RUN; query-api and worker only)."
  type        = string
  default     = "run-6fbad498beb0d8f570df67665fb97544"

  validation {
    condition     = can(regex("^run-[0-9a-f]{32}$", var.window_summary_run))
    error_message = "window_summary_run must be run-<32 hex> (an empty value is forbidden, Phase 11 D12)."
  }
}

variable "embedding_model_id" {
  description = "Bedrock embedding model (D-12): Titan Text Embeddings V2."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "haiku_inference_profile_id" {
  description = "Bedrock US inference profile of Claude Haiku 4.5 (D-12), used for the planner and synthesis."
  type        = string
  default     = "us.anthropic.claude-haiku-4-5-20251001-v1:0"

  validation {
    condition     = startswith(var.haiku_inference_profile_id, "us.")
    error_message = "haiku_inference_profile_id must be a US geo inference profile (us.)."
  }
}

variable "haiku_destination_regions" {
  description = "Regions the US Haiku 4.5 profile routes to (verified read-only by the human, ruling 18). The IAM grant on the foundation model covers exactly these."
  type        = list(string)
  default     = ["us-east-1", "us-east-2", "us-west-2"]
}

variable "service_max_counts" {
  description = "ECS maximum task count per service (ruling 2: desired 1, maximum at most 2). query-api and worker are 1 so that their Haiku calls stay within haiku_calls_per_minute_cap (see llm_requests_per_minute)."
  type = object({
    query_api          = number
    graph_evidence_api = number
    worker             = number
  })
  default = {
    query_api          = 1
    graph_evidence_api = 2
    worker             = 1
  }

  validation {
    condition     = alltrue([for n in values(var.service_max_counts) : n >= 1 && n <= 2])
    error_message = "Ruling 2: every ECS service maximum is 1 or 2."
  }
}

variable "service_cpu_target_percent" {
  description = "Target-tracking average CPU percent for the ECS services whose maximum is above 1. 60 is the production value; course.tfvars may lower it for a scale-out experiment (Phase 15 ruling 2)."
  type        = number
  default     = 60

  validation {
    condition     = floor(var.service_cpu_target_percent) == var.service_cpu_target_percent && var.service_cpu_target_percent >= 10 && var.service_cpu_target_percent <= 90
    error_message = "service_cpu_target_percent must be a whole number from 10 to 90."
  }
}

variable "haiku_calls_per_minute_cap" {
  description = "Upper bound on live Haiku calls per minute from query-api and the worker together (work order 2: 8)."
  type        = number
  default     = 8

  validation {
    condition     = var.haiku_calls_per_minute_cap >= 1 && var.haiku_calls_per_minute_cap <= 8
    error_message = "haiku_calls_per_minute_cap must be 1 to 8 (code review 1 F5); more is a human-gated scale change."
  }
}

variable "llm_requests_per_minute" {
  description = "Per-process Haiku throttle (runtime.llm.requests-per-minute), passed as SCHOLARCHAIN_LLM_REQUESTS_PER_MINUTE. The product with the query-api plus worker maximums must not exceed haiku_calls_per_minute_cap. The core loader honours the variable (Phase 12 work order 4) and caps each process at 8."
  type        = number
  default     = 4

  validation {
    condition     = var.llm_requests_per_minute >= 1 && var.llm_requests_per_minute * (var.service_max_counts.query_api + var.service_max_counts.worker) <= var.haiku_calls_per_minute_cap
    error_message = "llm_requests_per_minute x (query_api + worker maximum tasks) must not exceed haiku_calls_per_minute_cap."
  }
}

variable "task_sizes" {
  description = "Fargate ARM64 task CPU units and memory MiB per task definition."
  type = map(object({
    cpu    = number
    memory = number
  }))
  default = {
    query-api          = { cpu = 1024, memory = 2048 }
    graph-evidence-api = { cpu = 1024, memory = 2048 }
    worker             = { cpu = 1024, memory = 2048 }
    neptune-loader     = { cpu = 2048, memory = 8192 }
  }

  validation {
    condition     = alltrue([for k in ["query-api", "graph-evidence-api", "worker", "neptune-loader"] : contains(keys(var.task_sizes), k)]) && alltrue([for s in values(var.task_sizes) : s.cpu <= 2048 && s.memory <= 8192])
    error_message = "task_sizes needs all four task definitions, each at most 2 vCPU and 8 GiB."
  }
}
