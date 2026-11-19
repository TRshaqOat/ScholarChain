# Data bucket: one per environment (Phase 7 rulings 7 and 20), exported as SCHOLARCHAIN_BUCKET.
# Prefixes are key conventions, not objects: the eight core storage.prefixes plus artifacts/ for job
# jars and image build contexts. Versioning, SSE-S3, all public access blocked, TLS-only access,
# and lifecycle rules for logs/ and artifacts/.
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

variable "force_destroy" {
  type = bool
}

variable "logs_expiration_days" {
  type = number
}

variable "artifacts_expiration_days" {
  type = number
}

variable "noncurrent_version_expiration_days" {
  type = number
}

locals {
  bucket = "${var.name}-data-${var.account_id}-${var.region}"
  # Core storage.prefixes (modules/core scholarchain.conf) plus artifacts/. The work order's "rag"
  # prefix is semantic-context/ (rendered text, candidates) and vectors/ (vector backups).
  prefixes = {
    raw              = "raw/"
    curated          = "curated/"
    graph            = "graph/"
    semantic_context = "semantic-context/"
    vectors          = "vectors/"
    reports          = "reports/"
    logs             = "logs/"
    runs             = "runs/"
    artifacts        = "artifacts/"
  }
}

resource "aws_s3_bucket" "data" {
  bucket = local.bucket
  # Course variable only; false keeps every object version on destroy (cleanup.sh documents when the
  # human may set it, after the evidence archive is approved).
  force_destroy = var.force_destroy
}

resource "aws_s3_bucket_ownership_controls" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "data" {
  bucket                  = aws_s3_bucket.data.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "data" {
  bucket = aws_s3_bucket.data.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  # S3 blocks SSE-C on the bucket by default; declaring it keeps plans from undoing the block
  # (Phase 14 rulings 38 and 48). Bucket keys apply to SSE-KMS only.
  rule {
    blocked_encryption_types = ["SSE-C"]
    bucket_key_enabled       = false

    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.data.arn,
      "${aws_s3_bucket.data.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "data" {
  bucket = aws_s3_bucket.data.id
  policy = data.aws_iam_policy_document.tls_only.json

  depends_on = [aws_s3_bucket_public_access_block.data]
}

resource "aws_s3_bucket_lifecycle_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  # logs/: runtime audits (30-day retention, Phase 7 ruling 22) and EMR logs.
  rule {
    id     = "logs"
    status = "Enabled"

    filter {
      prefix = local.prefixes.logs
    }

    expiration {
      days = var.logs_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }
  }

  # artifacts/: job jars and image build contexts, reproducible from Git.
  rule {
    id     = "artifacts"
    status = "Enabled"

    filter {
      prefix = local.prefixes.artifacts
    }

    expiration {
      days = var.artifacts_expiration_days
    }

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.data]
}

output "bucket_name" {
  value = aws_s3_bucket.data.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.data.arn
}

output "prefixes" {
  value = local.prefixes
}
