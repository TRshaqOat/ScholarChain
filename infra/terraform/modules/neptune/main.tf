# Neptune (runtime_enabled): one provisioned instance of the cheapest class (db.t4g.medium, ruling 2)
# in the private subnets, with subnet, cluster parameter, and instance parameter groups, storage
# encryption (AWS-managed key), and IAM database authentication. Course settings, documented in
# infra/terraform/README.md: deletion protection off and skip-final-snapshot on, because the runtime
# window is ephemeral (D-13) and the graph is rebuilt by the bulk loader from graph/.
# Also the bulk-load role that Neptune assumes to read graph/ (STORAGE.md, NeptuneBulkLoader).
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

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "engine_version" {
  type = string
}

variable "parameter_group_family" {
  type = string
}

variable "instance_class" {
  type = string
}

variable "port" {
  type = number
}

variable "deletion_protection" {
  type = bool
}

variable "skip_final_snapshot" {
  type = bool
}

variable "data_bucket_arn" {
  type = string
}

# ---------------------------------------------------------------------------------------------
# Bulk-load role: read graph/ only (the loader source holds only Neptune CSV, STORAGE.md).
# ---------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "loader_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_iam_role" "bulk_loader" {
  name               = "${var.name}-neptune-bulk-loader"
  description        = "Assumed by Neptune to read graph/ for bulk loads"
  assume_role_policy = data.aws_iam_policy_document.loader_trust.json
}

data "aws_iam_policy_document" "loader_read" {
  statement {
    sid       = "ListGraphPrefix"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/*"]
    }
  }

  statement {
    sid       = "ReadGraphPrefix"
    actions   = ["s3:GetObject"]
    resources = ["${var.data_bucket_arn}/graph/*"]
  }
}

resource "aws_iam_role_policy" "bulk_loader" {
  name   = "read-graph-prefix"
  role   = aws_iam_role.bulk_loader.id
  policy = data.aws_iam_policy_document.loader_read.json
}

# ---------------------------------------------------------------------------------------------
# Cluster
# ---------------------------------------------------------------------------------------------

resource "aws_neptune_subnet_group" "this" {
  name        = "${var.name}-neptune"
  description = "Private subnets for Neptune"
  subnet_ids  = var.private_subnet_ids
}

resource "aws_neptune_cluster_parameter_group" "this" {
  name        = "${var.name}-neptune-cluster"
  family      = var.parameter_group_family
  description = "ScholarChain Neptune cluster parameters"

  # The storage templates add USING QUERY:TIMEOUTMILLISECONDS (neptune.query-timeout-ms, 5 s); the
  # effective timeout is the smaller of that hint and this cluster bound (STORAGE.md).
  parameter {
    name  = "neptune_query_timeout"
    value = "30000"
  }
}

resource "aws_neptune_parameter_group" "this" {
  name        = "${var.name}-neptune-instance"
  family      = var.parameter_group_family
  description = "ScholarChain Neptune instance parameters"
}

resource "aws_neptune_cluster" "this" {
  cluster_identifier                   = "${var.name}-graph"
  engine                               = "neptune"
  engine_version                       = var.engine_version
  port                                 = var.port
  neptune_subnet_group_name            = aws_neptune_subnet_group.this.name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.this.name
  vpc_security_group_ids               = [var.security_group_id]
  iam_database_authentication_enabled  = true
  storage_encrypted                    = true
  iam_roles                            = [aws_iam_role.bulk_loader.arn]
  backup_retention_period              = 1
  preferred_backup_window              = "07:00-08:00"
  deletion_protection                  = var.deletion_protection
  skip_final_snapshot                  = var.skip_final_snapshot
  final_snapshot_identifier            = var.skip_final_snapshot ? null : "${var.name}-graph-final"
  copy_tags_to_snapshot                = true
  apply_immediately                    = true
}

resource "aws_neptune_cluster_instance" "this" {
  identifier                   = "${var.name}-graph-1"
  cluster_identifier           = aws_neptune_cluster.this.id
  engine                       = "neptune"
  instance_class               = var.instance_class
  neptune_parameter_group_name = aws_neptune_parameter_group.this.name
  # Engine version is pinned by the cluster; no minor upgrade during the window.
  auto_minor_version_upgrade = false
  apply_immediately          = true
}

output "cluster_id" {
  value = aws_neptune_cluster.this.id
}

output "endpoint" {
  description = "Cluster (writer) endpoint: SCHOLARCHAIN_NEPTUNE_ENDPOINT."
  value       = aws_neptune_cluster.this.endpoint
}

output "port" {
  value = aws_neptune_cluster.this.port
}

output "cluster_resource_id" {
  value = aws_neptune_cluster.this.cluster_resource_id
}

output "bulk_loader_role_arn" {
  description = "SCHOLARCHAIN_NEPTUNE_LOADER_ROLE_ARN."
  value       = aws_iam_role.bulk_loader.arn
}
