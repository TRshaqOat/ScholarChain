# Network (ruling 1, D-10 item 8): a two-AZ VPC. Public subnets hold the ALB, the ECS tasks (public
# IP, no NAT gateway) and the EMR nodes; private subnets hold Neptune only. S3 and DynamoDB gateway
# endpoints serve both route tables. Runtime security groups (runtime_enabled) are chained by role:
# only the ALB listener accepts a CIDR. EMR security groups live in the emr module.
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

variable "region" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "azs" {
  type = list(string)
}

variable "runtime_enabled" {
  type = bool
}

variable "alb_ingress_cidr_blocks" {
  type = list(string)
}

variable "service_ports" {
  description = "Container ports: query-api and graph-evidence-api (core services.*.port)."
  type = object({
    query_api          = number
    graph_evidence_api = number
  })
}

variable "neptune_port" {
  type = number
}

locals {
  # Tag the AWS-managed EMR service policy (AmazonEMRServicePolicy_v2) requires on the VPC
  # resources EMR may use.
  emr_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }
  rt      = var.runtime_enabled ? 1 : 0
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.emr_tag, { Name = "${var.name}-vpc" })
}

# The default security group is left with no rules, so nothing can use it by accident.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-default-closed" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-igw" }
}

resource "aws_subnet" "public" {
  count = 2

  vpc_id            = aws_vpc.this.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index)
  # No NAT gateway (ruling 1): EMR nodes need public IPs to reach AWS endpoints. Security groups
  # admit no inbound traffic from the internet except to the ALB.
  map_public_ip_on_launch = true

  tags = merge(local.emr_tag, { Name = "${var.name}-public-${var.azs[count.index]}", Tier = "public" })
}

resource "aws_subnet" "private" {
  count = 2

  vpc_id            = aws_vpc.this.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 8)

  tags = { Name = "${var.name}-private-${var.azs[count.index]}", Tier = "private" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

# Private route table: no route to the internet; only the VPC and the gateway endpoints.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${var.name}-private" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# Gateway endpoints (no hourly charge). The Neptune bulk loader reaches S3 only through this
# endpoint. The default (full access) endpoint policy is kept: ECR image layers and EMR packages
# come from AWS-owned buckets, and IAM scopes every role to its own resources.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]
  tags              = { Name = "${var.name}-s3" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]
  tags              = { Name = "${var.name}-dynamodb" }
}

# ---------------------------------------------------------------------------------------------
# Runtime security groups (runtime_enabled). Each role has its own group.
# ---------------------------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  count       = local.rt
  name        = "${var.name}-alb"
  description = "Public ALB: HTTP listener only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-alb" }
}

resource "aws_security_group" "query_api" {
  count       = local.rt
  name        = "${var.name}-query-api"
  description = "query-api tasks: from the ALB only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-query-api" }
}

resource "aws_security_group" "graph_evidence_api" {
  count       = local.rt
  name        = "${var.name}-graph-evidence-api"
  description = "graph-evidence-api tasks: from the ALB only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-graph-evidence-api" }
}

resource "aws_security_group" "worker" {
  count       = local.rt
  name        = "${var.name}-worker"
  description = "worker tasks: no inbound traffic"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-worker" }
}

resource "aws_security_group" "loader" {
  count       = local.rt
  name        = "${var.name}-neptune-loader"
  description = "One-off Neptune loader tasks: no inbound traffic"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-neptune-loader" }
}

resource "aws_security_group" "neptune" {
  count       = local.rt
  name        = "${var.name}-neptune"
  description = "Neptune: from the service and loader groups only"
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name}-neptune" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = var.runtime_enabled ? toset(var.alb_ingress_cidr_blocks) : toset([])
  security_group_id = aws_security_group.alb[0].id
  description       = "HTTP listener"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_query_api" {
  count                        = local.rt
  security_group_id            = aws_security_group.alb[0].id
  description                  = "To query-api targets"
  referenced_security_group_id = aws_security_group.query_api[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.service_ports.query_api
  to_port                      = var.service_ports.query_api
}

resource "aws_vpc_security_group_egress_rule" "alb_to_graph_evidence_api" {
  count                        = local.rt
  security_group_id            = aws_security_group.alb[0].id
  description                  = "To graph-evidence-api targets"
  referenced_security_group_id = aws_security_group.graph_evidence_api[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.service_ports.graph_evidence_api
  to_port                      = var.service_ports.graph_evidence_api
}

resource "aws_vpc_security_group_ingress_rule" "query_api_from_alb" {
  count                        = local.rt
  security_group_id            = aws_security_group.query_api[0].id
  description                  = "From the ALB"
  referenced_security_group_id = aws_security_group.alb[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.service_ports.query_api
  to_port                      = var.service_ports.query_api
}

resource "aws_vpc_security_group_ingress_rule" "graph_evidence_api_from_alb" {
  count                        = local.rt
  security_group_id            = aws_security_group.graph_evidence_api[0].id
  description                  = "From the ALB"
  referenced_security_group_id = aws_security_group.alb[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.service_ports.graph_evidence_api
  to_port                      = var.service_ports.graph_evidence_api
}

locals {
  # Neptune clients: every task group that reads the graph or starts a bulk load.
  neptune_clients = var.runtime_enabled ? {
    query_api          = aws_security_group.query_api[0].id
    graph_evidence_api = aws_security_group.graph_evidence_api[0].id
    worker             = aws_security_group.worker[0].id
    loader             = aws_security_group.loader[0].id
  } : {}
}

# Tasks run in public subnets without NAT or interface endpoints, so AWS APIs (ECR, CloudWatch Logs,
# Secrets Manager, SQS, Bedrock, S3 Vectors) are reached over HTTPS on public endpoints. S3 and
# DynamoDB traffic stays on the gateway endpoints.
resource "aws_vpc_security_group_egress_rule" "clients_https" {
  for_each          = local.neptune_clients
  security_group_id = each.value
  description       = "HTTPS to AWS service endpoints"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "clients_to_neptune" {
  for_each                     = local.neptune_clients
  security_group_id            = each.value
  description                  = "To Neptune"
  referenced_security_group_id = aws_security_group.neptune[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_clients" {
  for_each                     = local.neptune_clients
  security_group_id            = aws_security_group.neptune[0].id
  description                  = "From ${each.key}"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
}

# The bulk loader reads graph/ from S3 through the gateway endpoint.
resource "aws_vpc_security_group_egress_rule" "neptune_to_s3" {
  count             = local.rt
  security_group_id = aws_security_group.neptune[0].id
  description       = "Bulk loader to S3 (gateway endpoint)"
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "s3_prefix_list_id" {
  value = aws_vpc_endpoint.s3.prefix_list_id
}

output "security_group_ids" {
  description = "Runtime security groups by role (null when runtime_enabled is false)."
  value = var.runtime_enabled ? {
    alb                = aws_security_group.alb[0].id
    query_api          = aws_security_group.query_api[0].id
    graph_evidence_api = aws_security_group.graph_evidence_api[0].id
    worker             = aws_security_group.worker[0].id
    loader             = aws_security_group.loader[0].id
    neptune            = aws_security_group.neptune[0].id
  } : null
}
