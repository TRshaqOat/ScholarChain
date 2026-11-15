# Foundation: account, Region, partition, the two Availability Zones, and the name prefix. Provider
# pins and default tags live in the root module (envs/course/versions.tf), because a module cannot
# configure providers.
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

variable "project" {
  description = "Lower-case project name used in resource names."
  type        = string
}

variable "environment" {
  description = "Environment name used in resource names."
  type        = string
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_partition" "current" {}

variable "availability_zones" {
  description = "The two AZs of the VPC, pinned (code review 1 F6) so an AZ state change never replans the subnets. Validated in the root module."
  type        = list(string)
}

output "name_prefix" {
  description = "Prefix of every resource name, for example scholarchain-course."
  value       = "${var.project}-${var.environment}"
}

output "account_id" {
  value = data.aws_caller_identity.current.account_id
}

output "region" {
  value = data.aws_region.current.region
}

output "partition" {
  value = data.aws_partition.current.partition
}

output "azs" {
  description = "The two pinned zones (ruling 1: a two-AZ VPC)."
  value       = var.availability_zones
}
