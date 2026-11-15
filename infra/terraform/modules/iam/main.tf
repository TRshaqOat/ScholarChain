# ECS runtime IAM (runtime_enabled; modules/services/SERVICES.md section 4, STORAGE.md). Task
# execution roles (image pull, logs, the API key secret) are separate from task roles (what the
# code calls). Every statement names its actions and resources; none uses a wildcard action. The
# runtime graph grant is neptune-db:ReadDataViaQuery only. EMR roles live in the emr module, the
# CodeBuild role in the build module, and the Neptune bulk-load role in the neptune module.
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

variable "name" {
  type = string
}

variable "account_id" {
  type = string
}

variable "region" {
  type = string
}

variable "partition" {
  type = string
}

variable "data_bucket_arn" {
  type = string
}

variable "table_arns" {
  description = "Logical table name -> ARN."
  type        = map(string)
}

variable "evidence_index_arn" {
  type = string
}

variable "queue_arn" {
  type = string
}

variable "api_key_secret_arn" {
  type = string
}

variable "neptune_cluster_resource_id" {
  type = string
}

variable "vector_index_arns" {
  type = map(string)
}

variable "ecr_repository_arn" {
  type = string
}

variable "log_group_arns" {
  description = "Task definition name -> log group ARN."
  type        = map(string)
}

variable "embedding_model_id" {
  type = string
}

variable "haiku_inference_profile_id" {
  type = string
}

variable "haiku_destination_regions" {
  type = list(string)
}

locals {
  b       = var.data_bucket_arn
  p       = var.partition
  neptune = "arn:${local.p}:neptune-db:${var.region}:${var.account_id}:${var.neptune_cluster_resource_id}/*"

  haiku_profile_arn = "arn:${local.p}:bedrock:${var.region}:${var.account_id}:inference-profile/${var.haiku_inference_profile_id}"
  haiku_model_arns = [
    for r in var.haiku_destination_regions :
    "arn:${local.p}:bedrock:${r}::foundation-model/${trimprefix(var.haiku_inference_profile_id, "us.")}"
  ]
  titan_arn = "arn:${local.p}:bedrock:${var.region}::foundation-model/${var.embedding_model_id}"

  # Tables the rag runtime reads (RUNTIME.md section 8) and writes create-only (QuerySession,
  # EvidenceBundle), plus ServiceState (jobs and idempotency keys).
  runtime_read_tables  = ["EntityLinkCache", "ResearcherMetricCache", "ArtifactRegistry", "QuerySession", "EvidenceBundle"]
  runtime_write_tables = ["QuerySession", "EvidenceBundle"]

  task_roles = toset(["query-api", "graph-evidence-api", "worker", "neptune-loader"])
}

data "aws_iam_policy_document" "ecs_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.p}:ecs:${var.region}:${var.account_id}:*"]
    }
  }
}

# ---------------------------------------------------------------------------------------------
# Task execution roles: the services role reads the API key secret; the loader role does not.
# ---------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "execution" {
  for_each = toset(["services", "loader"])

  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "EcrPull"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [var.ecr_repository_arn]
  }

  statement {
    sid     = "Logs"
    actions = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      for k, arn in var.log_group_arns : "${arn}:*"
      if(each.key == "loader") == (k == "neptune-loader")
    ]
  }

  dynamic "statement" {
    for_each = each.key == "services" ? [1] : []
    content {
      sid       = "ApiKeySecret"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = [var.api_key_secret_arn]
    }
  }
}

resource "aws_iam_role" "execution" {
  for_each           = toset(["services", "loader"])
  name               = "${var.name}-ecs-exec-${each.key}"
  description        = "ECS task execution role (${each.key}): image pull, logs${each.key == "services" ? ", API key secret" : ""}"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "execution" {
  for_each = toset(["services", "loader"])
  name     = "execution"
  role     = aws_iam_role.execution[each.key].id
  policy   = data.aws_iam_policy_document.execution[each.key].json
}

# ---------------------------------------------------------------------------------------------
# Task roles
# ---------------------------------------------------------------------------------------------

# query-api and the worker run the rag workflow: identical store, vector, and model grants.
data "aws_iam_policy_document" "answering" {
  # Unconditioned on purpose: S3 answers a GET of a missing key with 404 only to a caller that holds
  # s3:ListBucket on the bucket; otherwise 403, which the services map to 502 and count against the s3
  # breaker (SERVICES.md section 4: the readiness probe and unknown query IDs read missing keys).
  statement {
    sid       = "ListDataBucket"
    actions   = ["s3:ListBucket"]
    resources = [local.b]
  }

  statement {
    sid     = "ReadRuntimeObjects"
    actions = ["s3:GetObject"]
    resources = [
      "${local.b}/logs/rag-runtime/*",
      "${local.b}/reports/rag-runtime/*",
      "${local.b}/curated/window-summary/*",
      "${local.b}/curated/coauthor-evidence/*",
      "${local.b}/semantic-context/text/*",
    ]
  }

  statement {
    sid     = "WriteRuntimeRecords"
    actions = ["s3:PutObject"]
    resources = [
      "${local.b}/logs/rag-runtime/*",
      "${local.b}/reports/rag-runtime/*",
    ]
  }

  statement {
    sid       = "ReadStoreTables"
    actions   = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = [for t in local.runtime_read_tables : var.table_arns[t]]
  }

  statement {
    sid       = "CreateRuntimeRecords"
    actions   = ["dynamodb:PutItem"]
    resources = [for t in local.runtime_write_tables : var.table_arns[t]]
  }

  statement {
    sid       = "ServiceState"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem"]
    resources = [var.table_arns["ServiceState"]]
  }

  statement {
    sid       = "GraphRead"
    actions   = ["neptune-db:ReadDataViaQuery"]
    resources = [local.neptune]
  }

  statement {
    sid       = "VectorQuery"
    actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors"]
    resources = values(var.vector_index_arns)
  }

  statement {
    sid       = "EmbedQuestion"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.titan_arn]
  }

  statement {
    sid       = "HaikuProfile"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.haiku_profile_arn]
  }

  # The US profile routes to the foundation model in its destination Regions; allowed only through
  # that profile.
  statement {
    sid       = "HaikuThroughProfile"
    actions   = ["bedrock:InvokeModel"]
    resources = local.haiku_model_arns

    condition {
      test     = "StringEquals"
      variable = "bedrock:InferenceProfileArn"
      values   = [local.haiku_profile_arn]
    }
  }
}

data "aws_iam_policy_document" "query_api_queue" {
  statement {
    sid       = "SendJobs"
    actions   = ["sqs:SendMessage"]
    resources = [var.queue_arn]
  }
}

data "aws_iam_policy_document" "worker_queue" {
  statement {
    sid       = "ConsumeJobs"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"]
    resources = [var.queue_arn]
  }
}

# graph-evidence-api: evidence by ID (GSI), the payload objects it names, and graph reads. No
# window-summary, vector, model, queue, or ServiceState grant (SERVICES.md section 4).
data "aws_iam_policy_document" "graph_evidence_api" {
  statement {
    sid       = "EvidenceById"
    actions   = ["dynamodb:Query"]
    resources = [var.evidence_index_arn]
  }

  statement {
    sid       = "EvidencePayloads"
    actions   = ["s3:GetObject"]
    resources = ["${local.b}/logs/rag-runtime/*"]
  }

  statement {
    sid       = "GraphRead"
    actions   = ["neptune-db:ReadDataViaQuery"]
    resources = [local.neptune]
  }
}

# One-off Neptune loader task (LoadNeptuneCli in the VPC): read the store-conversion manifest, the
# graph CSV, and the coauthor evidence objects; start a bulk load and read its status. The bulk load
# itself reads S3 with the neptune module's bulk-load role.
data "aws_iam_policy_document" "neptune_loader" {
  statement {
    sid       = "ListLoadInputs"
    actions   = ["s3:ListBucket"]
    resources = [local.b]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/neptune/*", "curated/coauthor-evidence/*", "runs/store-conversion/*"]
    }
  }

  statement {
    sid     = "ReadLoadInputs"
    actions = ["s3:GetObject"]
    resources = [
      "${local.b}/graph/neptune/*",
      "${local.b}/curated/coauthor-evidence/*",
      "${local.b}/runs/store-conversion/*",
    ]
  }

  statement {
    sid       = "BulkLoad"
    actions   = ["neptune-db:StartLoaderJob", "neptune-db:GetLoaderJobStatus"]
    resources = [local.neptune]
  }
}

data "aws_iam_policy_document" "task" {
  for_each = local.task_roles

  source_policy_documents = compact([
    contains(["query-api", "worker"], each.key) ? data.aws_iam_policy_document.answering.json : "",
    each.key == "query-api" ? data.aws_iam_policy_document.query_api_queue.json : "",
    each.key == "worker" ? data.aws_iam_policy_document.worker_queue.json : "",
    each.key == "graph-evidence-api" ? data.aws_iam_policy_document.graph_evidence_api.json : "",
    each.key == "neptune-loader" ? data.aws_iam_policy_document.neptune_loader.json : "",
  ])
}

resource "aws_iam_role" "task" {
  for_each           = local.task_roles
  name               = "${var.name}-task-${each.key}"
  description        = "ECS task role: ${each.key}"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "task" {
  for_each = local.task_roles
  name     = "runtime"
  role     = aws_iam_role.task[each.key].id
  policy   = data.aws_iam_policy_document.task[each.key].json
}

output "execution_role_arns" {
  description = "services (query-api, graph-evidence-api, worker) and loader."
  value       = { for k, r in aws_iam_role.execution : k => r.arn }
}

output "task_role_arns" {
  value = { for k, r in aws_iam_role.task : k => r.arn }
}
