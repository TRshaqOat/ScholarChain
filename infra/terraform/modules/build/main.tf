# Images without Docker on the laptop (ruling 3): one ECR repository (immutable tags, scan on push)
# and one CodeBuild project (ARM container, privileged for docker) that builds the services image
# from the Docker context scripts/package.sh stages with `sbt services/Docker/stage`, zips, and
# uploads under artifacts/codebuild/source/. The project tags the image with the Git SHA passed as
# IMAGE_TAG and pushes it.
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

variable "data_bucket_arn" {
  type = string
}

variable "data_bucket_name" {
  type = string
}

variable "log_retention_days" {
  type = number
}

locals {
  source_prefix = "artifacts/codebuild/source/"
  log_group     = "/scholarchain/${var.name}/codebuild/services-image"
  project       = "${var.name}-services-image"
}

resource "aws_ecr_repository" "services" {
  name                 = "${var.name}-services"
  image_tag_mutability = "IMMUTABLE"
  # Never delete a repository that still holds images (law 3).
  force_delete = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Removes only untagged images (failed or superseded pushes); every Git SHA tag is kept.
resource "aws_ecr_lifecycle_policy" "services" {
  repository = aws_ecr_repository.services.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after 7 days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 7
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "codebuild" {
  name              = local.log_group
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["codebuild.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_iam_role" "codebuild" {
  name               = "${var.name}-codebuild-services-image"
  description        = "CodeBuild: read the build source prefix, push to the services repository, write its log group"
  assume_role_policy = data.aws_iam_policy_document.trust.json
}

data "aws_iam_policy_document" "codebuild" {
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.codebuild.arn}:*"]
  }

  statement {
    sid       = "SourceBucket"
    actions   = ["s3:GetBucketLocation", "s3:GetBucketAcl"]
    resources = [var.data_bucket_arn]
  }

  statement {
    sid       = "SourceObjects"
    actions   = ["s3:GetObject", "s3:GetObjectVersion"]
    resources = ["${var.data_bucket_arn}/${local.source_prefix}*"]
  }

  # GetAuthorizationToken has no resource-level scope.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [aws_ecr_repository.services.arn]
  }
}

resource "aws_iam_role_policy" "codebuild" {
  name   = "services-image"
  role   = aws_iam_role.codebuild.id
  policy = data.aws_iam_policy_document.codebuild.json
}

resource "aws_codebuild_project" "services_image" {
  name                   = local.project
  description            = "Builds and pushes the ScholarChain services image (linux/arm64) tagged with the Git SHA"
  service_role           = aws_iam_role.codebuild.arn
  build_timeout          = 30
  queued_timeout         = 30
  concurrent_build_limit = 1

  # scripts/package.sh overrides the location per build with artifacts/codebuild/source/<sha>.zip.
  source {
    type      = "S3"
    location  = "${var.data_bucket_name}/${local.source_prefix}services-docker-context.zip"
    buildspec = <<-YAML
      version: 0.2
      env:
        shell: bash
      phases:
        pre_build:
          commands:
            - '[[ "$IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]] || { echo "IMAGE_TAG must be a 40-hex Git SHA"; exit 1; }'
            - test -f Dockerfile
            - aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$${REPOSITORY_URI%%/*}"
        build:
          commands:
            - docker build --platform linux/arm64 -t "$REPOSITORY_URI:$IMAGE_TAG" .
        post_build:
          commands:
            - '[ "$CODEBUILD_BUILD_SUCCEEDING" = "1" ] || exit 1'
            - docker push "$REPOSITORY_URI:$IMAGE_TAG"
            - echo "==> IMAGE $(docker inspect --format '{{index .RepoDigests 0}}' "$REPOSITORY_URI:$IMAGE_TAG")"
    YAML
  }

  artifacts {
    type = "NO_ARTIFACTS"
  }

  environment {
    type            = "ARM_CONTAINER"
    compute_type    = "BUILD_GENERAL1_SMALL"
    image           = "aws/codebuild/amazonlinux-aarch64-standard:3.0"
    privileged_mode = true

    environment_variable {
      name  = "REPOSITORY_URI"
      value = aws_ecr_repository.services.repository_url
    }

    # Placeholder; scripts/package.sh overrides it, and the buildspec refuses anything but a SHA.
    environment_variable {
      name  = "IMAGE_TAG"
      value = "unset"
    }
  }

  logs_config {
    cloudwatch_logs {
      group_name = aws_cloudwatch_log_group.codebuild.name
      status     = "ENABLED"
    }

    s3_logs {
      status = "DISABLED"
    }
  }
}

output "repository_url" {
  value = aws_ecr_repository.services.repository_url
}

output "repository_name" {
  value = aws_ecr_repository.services.name
}

output "repository_arn" {
  value = aws_ecr_repository.services.arn
}

output "codebuild_project_name" {
  value = aws_codebuild_project.services_image.name
}

output "codebuild_source_prefix" {
  value = local.source_prefix
}

output "codebuild_log_group" {
  value = aws_cloudwatch_log_group.codebuild.name
}
