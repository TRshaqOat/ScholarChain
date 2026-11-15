# S3 Vectors (runtime_enabled): one vector bucket (SCHOLARCHAIN_VECTOR_BUCKET) and the five indexes of
# the storage contract (core s3-vectors.indexes; STORAGE.md "S3 Vectors"): float32, 1024 dimensions,
# cosine. The non-filterable keys vectorKey, textHash, and textRef are fixed at creation and cannot
# change later. Encryption: SSE-S3 (AES256), the service default.
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

locals {
  indexes = toset([
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1",
  ])
}

resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = "${var.name}-vectors-${var.account_id}"
  # Indexes are Terraform resources, so destroy removes them first; no force_destroy needed.
  force_destroy = false

  encryption_configuration {
    sse_type = "AES256"
  }
}

resource "aws_s3vectors_index" "this" {
  for_each = local.indexes

  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  index_name         = each.value
  data_type          = "float32"
  dimension          = 1024
  distance_metric    = "cosine"

  metadata_configuration {
    non_filterable_metadata_keys = ["vectorKey", "textHash", "textRef"]
  }
}

output "vector_bucket_name" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_name
}

output "vector_bucket_arn" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "index_arns" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_arn }
}
