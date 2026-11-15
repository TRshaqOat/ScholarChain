# DynamoDB runtime tables (runtime_enabled): the seven tables of modules/storage (DynamoContract.Tables,
# STORAGE.md "DynamoDB" and "Service state"), on demand, named <dynamodb.table-prefix><Table>.
# TTL on expiresAt for QuerySession, EvidenceBundle, and ServiceState; GSI evidenceById on
# EvidenceBundle. Default encryption (AWS owned key). Point-in-time recovery and deletion
# protection are course variables (off: the window is ephemeral and every loaded table is rebuilt
# from the write-once S3 item files).
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

variable "table_prefix" {
  type = string
}

variable "deletion_protection" {
  type = bool
}

variable "point_in_time_recovery" {
  type = bool
}

locals {
  # Logical name -> keys (modules/storage/src/main/scala/scholarchain/storage/Dynamo.scala).
  tables = {
    RunLedger             = { hash = "runId", range = "entryKey", ttl = false }
    EntityLinkCache       = { hash = "nameKey", range = "researcherId", ttl = false }
    QuerySession          = { hash = "sessionId", range = "turnKey", ttl = true }
    EvidenceBundle        = { hash = "queryId", range = "itemKey", ttl = true }
    ArtifactRegistry      = { hash = "artifactKey", range = "versionKey", ttl = false }
    ResearcherMetricCache = { hash = "researcherId", range = "metricKey", ttl = false }
    ServiceState          = { hash = "recordKey", range = "recordType", ttl = true }
  }
  evidence_index = "evidenceById" # DynamoContract.EvidenceByIdIndex
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name                        = "${var.table_prefix}${each.key}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = each.value.hash
  range_key                   = each.value.range
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = each.value.hash
    type = "S"
  }

  attribute {
    name = each.value.range
    type = "S"
  }

  dynamic "ttl" {
    for_each = each.value.ttl ? [1] : []
    content {
      attribute_name = "expiresAt"
      enabled        = true
    }
  }

  # evidenceById: hash itemKey, range queryId, projection ALL (STORAGE.md "Evidence by ID").
  dynamic "global_secondary_index" {
    for_each = each.key == "EvidenceBundle" ? [1] : []
    content {
      name            = local.evidence_index
      hash_key        = "itemKey"
      range_key       = "queryId"
      projection_type = "ALL"
    }
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }
}

output "table_names" {
  value = { for k, t in aws_dynamodb_table.this : k => t.name }
}

output "table_arns" {
  value = { for k, t in aws_dynamodb_table.this : k => t.arn }
}

output "evidence_index_arn" {
  value = "${aws_dynamodb_table.this["EvidenceBundle"].arn}/index/${local.evidence_index}"
}
