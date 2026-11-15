# EMR support (ruling 2): the service role, the EC2 instance role and profile, the EMR-managed
# security groups, and the log location. No cluster: scripts/run-bounded-pipeline.sh launches each
# run as an ephemeral cluster (release, instance type, and counts from this module's outputs) that
# terminates after its steps.
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

variable "vpc_id" {
  type = string
}

variable "data_bucket_arn" {
  type = string
}

variable "data_bucket_name" {
  type = string
}

locals {
  emr_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }
  b       = var.data_bucket_arn
  # Only what the steps touch (code review 1 F1): run-bounded-pipeline.sh and emr/step.sh read raw
  # inputs, job jars and step.sh under artifacts/jobs/, and the Hadoop run under curated/hadoop; the
  # jobs write curated/hadoop and curated/spark; EMR pushes logs to logs/emr/. No graph/,
  # semantic-context/, runs/, or reports/ grant: StoreConversion and the loaders never run on EMR.
  read_prefixes = ["raw/", "curated/", "artifacts/"]
  # DeleteObject is needed by the s3a committers (temporary and rename files); versioning keeps
  # every overwritten or deleted version.
  write_prefixes = ["curated/", "logs/emr/"]
  # S3 rule (ruling 23; iam/main.tf:181-183): S3 answers a GET or HEAD of a missing key with 404 only
  # to a principal that holds s3:GetObject on that key and s3:ListBucket on the bucket; otherwise
  # 403, which S3A raises as AccessDeniedException, not "not found". An S3A getFileStatus or mkdirs
  # walk HEADs each parent of a write root ("curated", "logs/emr", "logs"), and output checks and the
  # first write under curated/ HEAD keys that do not exist yet. Those bare parent keys match no
  # "<prefix>*" grant, so they get exact-key GetObject here; nothing writes them, so they hold no data.
  parent_keys = ["curated", "logs", "logs/emr"]
}

# ---------------------------------------------------------------------------------------------
# Service role
# ---------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "service_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${var.partition}:elasticmapreduce:${var.region}:${var.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "service" {
  name               = "${var.name}-emr-service"
  description        = "EMR service role for ephemeral ScholarChain clusters"
  assume_role_policy = data.aws_iam_policy_document.service_trust.json
}

# AWS-managed v2 service policy: its EC2 actions are limited to resources tagged
# for-use-with-amazon-emr-managed-policies=true (the VPC, public subnets, and EMR groups here).
resource "aws_iam_role_policy_attachment" "service_v2" {
  role       = aws_iam_role.service.name
  policy_arn = "arn:${var.partition}:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

# The v2 policy passes only the default instance role name; a custom one needs this grant.
data "aws_iam_policy_document" "service_pass_role" {
  statement {
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.instance.arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "service_pass_role" {
  name   = "pass-instance-role"
  role   = aws_iam_role.service.id
  policy = data.aws_iam_policy_document.service_pass_role.json
}

# ---------------------------------------------------------------------------------------------
# EC2 instance role and profile (same name, as EMR requires)
# ---------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "instance_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${var.name}-emr-instance"
  description        = "EMR EC2 instance role: the data bucket only"
  assume_role_policy = data.aws_iam_policy_document.instance_trust.json
}

resource "aws_iam_instance_profile" "instance" {
  name = aws_iam_role.instance.name
  role = aws_iam_role.instance.name
}

data "aws_iam_policy_document" "instance_s3" {
  statement {
    sid       = "BucketLevel"
    actions   = ["s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
    resources = [local.b]
  }

  # Unconditioned on purpose (ruling 23): a GetObject or HeadObject request carries no s3:prefix, so
  # a prefix-conditioned ListBucket never applies to it, and every HEAD of a missing key would answer
  # 403. This exposes key names in the data bucket, never object data: the same pattern and rationale
  # as query-api (iam/main.tf:181-183; ruling 11). Reads and writes stay narrowed to the prefixes.
  statement {
    sid       = "ListDataBucket"
    actions   = ["s3:ListBucket"]
    resources = [local.b]
  }

  statement {
    sid       = "ReadInputs"
    actions   = ["s3:GetObject"]
    resources = [for p in local.read_prefixes : "${local.b}/${p}*"]
  }

  statement {
    sid       = "ProbeWriteRootParents"
    actions   = ["s3:GetObject"]
    resources = [for k in local.parent_keys : "${local.b}/${k}"]
  }

  statement {
    sid = "WriteOutputs"
    actions = [
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = [for p in local.write_prefixes : "${local.b}/${p}*"]
  }
}

resource "aws_iam_role_policy" "instance_s3" {
  name   = "data-bucket"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.instance_s3.json
}

# ---------------------------------------------------------------------------------------------
# EMR-managed security groups (public subnets). EMR adds and maintains the ingress rules between the
# primary and core nodes itself, so Terraform ignores rule drift and revokes them on delete. Terraform
# therefore can neither show nor enforce the ingress EMR adds (code review 1 F7c). Terraform itself
# creates no ingress rule, and EMR block public access (account default, on) should stop any
# internet ingress, but that is unverified until the first cluster runs: run-bounded-pipeline.sh
# describes both groups after each cluster and records and warns about any 0.0.0.0/0 or ::/0 ingress.
# ---------------------------------------------------------------------------------------------

resource "aws_security_group" "primary" {
  name                   = "${var.name}-emr-primary"
  description            = "EMR-managed group for the primary node"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = merge(local.emr_tag, { Name = "${var.name}-emr-primary" })

  lifecycle {
    ignore_changes = [ingress, egress]
  }
}

resource "aws_security_group" "core" {
  name                   = "${var.name}-emr-core"
  description            = "EMR-managed group for core nodes"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = merge(local.emr_tag, { Name = "${var.name}-emr-core" })

  lifecycle {
    ignore_changes = [ingress, egress]
  }
}

# Without NAT, nodes reach S3 (gateway endpoint), the EMR service, and package repositories over
# their public IPs. Outbound only.
resource "aws_vpc_security_group_egress_rule" "emr_all" {
  for_each          = { primary = aws_security_group.primary.id, core = aws_security_group.core.id }
  security_group_id = each.value
  description       = "Outbound to AWS endpoints"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

output "service_role_name" {
  value = aws_iam_role.service.name
}

output "service_role_arn" {
  value = aws_iam_role.service.arn
}

output "instance_role_arn" {
  value = aws_iam_role.instance.arn
}

output "instance_profile_name" {
  value = aws_iam_instance_profile.instance.name
}

output "primary_security_group_id" {
  value = aws_security_group.primary.id
}

output "core_security_group_id" {
  value = aws_security_group.core.id
}

output "log_uri" {
  description = "EMR LogUri (expires with the logs/ lifecycle rule)."
  value       = "s3://${var.data_bucket_name}/logs/emr/"
}
