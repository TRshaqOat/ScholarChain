# ECS Fargate ARM64 runtime (runtime_enabled; D-10 item 7, ruling 1): one cluster, task definitions
# for query-api, graph-evidence-api, and worker from one image tag, one one-off Neptune loader task
# definition, three services (desired 1, bounded maximums), and the public ALB.
#
# Image entrypoint. The image keeps the sbt-native-packager default ENTRYPOINT
# (/opt/docker/bin/services, a launcher that needs -main <class> because the package has several
# main classes; Phase 10 ruling 18). An ECS `command` is appended to that entrypoint, so a command
# "bin/query-api" would run "bin/services bin/query-api" and stop with "You need to pass -main
# argument". Each task definition therefore sets entryPoint to its own start script
# (/opt/docker/bin/query-api, ...) and passes only arguments as the command. The image is unchanged.
#
# Credentials (STORAGE.md "Credentials on ECS"): every task definition sets taskRoleArn and sets no
# AWS_PROFILE, static key, session token, web identity, or AWS_ROLE_ARN variable; the Region comes
# from --region and SCHOLARCHAIN_AWS_REGION. Live mode starts without --profile (the post-fix
# launcher form; see infra/terraform/README.md, service-engineer dependency).
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

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "security_group_ids" {
  description = "Runtime security groups by role (network module)."
  type        = map(string)
}

variable "repository_url" {
  type = string
}

variable "image_tag" {
  type = string
}

variable "task_sizes" {
  type = map(object({
    cpu    = number
    memory = number
  }))
}

variable "service_max_counts" {
  type = object({
    query_api          = number
    graph_evidence_api = number
    worker             = number
  })
}

variable "service_ports" {
  type = object({
    query_api          = number
    graph_evidence_api = number
  })
}

variable "execution_role_arns" {
  type = map(string)
}

variable "task_role_arns" {
  type = map(string)
}

variable "log_group_names" {
  type = map(string)
}

variable "api_key_secret_arn" {
  type = string
}

variable "runtime_args" {
  description = "Live-mode arguments shared by the three services (SERVICES.md section 5)."
  type = object({
    snapshot    = string
    graph_run   = string
    items_run   = string
    vectors_run = string
  })
}

variable "environment" {
  description = "SCHOLARCHAIN_* variables common to every task. Values must be non-empty (Phase 11 D12)."
  type        = map(string)
}

variable "window_summary_run" {
  type = string
}

locals {
  image = "${var.repository_url}:${var.image_tag}"

  args = [
    "--live",
    "--region", var.region,
    "--snapshot", var.runtime_args.snapshot,
    "--graph-run", var.runtime_args.graph_run,
    "--items-run", var.runtime_args.items_run,
    "--vectors-run", var.runtime_args.vectors_run,
  ]

  # query-api and the worker must compute the same runtime run ID (SERVICES.md section 5), so they
  # share exactly one argument list and one environment. Only they open the window-summary object.
  answering_env = merge(var.environment, { SCHOLARCHAIN_WINDOW_SUMMARY_RUN = var.window_summary_run })

  services = {
    query-api = {
      script = "query-api"
      env    = local.answering_env
      port   = var.service_ports.query_api
      max    = var.service_max_counts.query_api
      sg     = var.security_group_ids.query_api
    }
    graph-evidence-api = {
      script = "graph-evidence-api"
      env    = var.environment
      port   = var.service_ports.graph_evidence_api
      max    = var.service_max_counts.graph_evidence_api
      sg     = var.security_group_ids.graph_evidence_api
    }
    worker = {
      script = "worker"
      env    = local.answering_env
      port   = null
      max    = var.service_max_counts.worker
      sg     = var.security_group_ids.worker
    }
  }

  routed = {
    query-api = {
      port     = var.service_ports.query_api
      priority = 10
      paths    = ["/v1/query", "/v1/jobs/*", "/v1/explain/*"]
    }
    graph-evidence-api = {
      port     = var.service_ports.graph_evidence_api
      priority = 20
      paths    = ["/v1/evidence/*", "/v1/graph/*"]
    }
  }

  log_config = { for k, g in var.log_group_names : k => {
    logDriver = "awslogs"
    options = {
      awslogs-group         = g
      awslogs-region        = var.region
      awslogs-stream-prefix = k
    }
  } }
}

resource "aws_ecs_cluster" "this" {
  name = "${var.name}-runtime"

  # Container Insights adds per-metric charges; the AWS/ECS service metrics the alarms use are free.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_task_definition" "service" {
  for_each = local.services

  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_sizes[each.key].cpu)
  memory                   = tostring(var.task_sizes[each.key].memory)
  execution_role_arn       = var.execution_role_arns["services"]
  task_role_arn            = var.task_role_arns[each.key]

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name         = each.key
    image        = local.image
    essential    = true
    entryPoint   = ["/opt/docker/bin/${each.value.script}"]
    command      = local.args
    stopTimeout  = 30
    portMappings = each.value.port == null ? [] : [{ containerPort = each.value.port, protocol = "tcp" }]
    environment  = [for k in sort(keys(each.value.env)) : { name = k, value = each.value.env[k] }]
    secrets = [{
      name      = "SCHOLARCHAIN_API_KEY"
      valueFrom = var.api_key_secret_arn
    }]
    logConfiguration = local.log_config[each.key]
  }])

  lifecycle {
    precondition {
      condition     = alltrue([for v in values(each.value.env) : trimspace(v) != ""])
      error_message = "Every SCHOLARCHAIN_* value must be non-empty (Phase 11 D12)."
    }
  }
}

# One-off Neptune loader (the in-VPC loader path). scripts/load-stores.sh runs it with
# `aws ecs run-task` and a command override. It reuses the services image and the query-api start
# script with -main, which the native-packager script honours, so LoadNeptuneCli runs with the same
# classpath and JVM options (MaxRAMPercentage 75: a 6 GiB heap in 8 GiB). No API key.
resource "aws_ecs_task_definition" "neptune_loader" {
  family                   = "${var.name}-neptune-loader"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_sizes["neptune-loader"].cpu)
  memory                   = tostring(var.task_sizes["neptune-loader"].memory)
  execution_role_arn       = var.execution_role_arns["loader"]
  task_role_arn            = var.task_role_arns["neptune-loader"]

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name             = "neptune-loader"
    image            = local.image
    essential        = true
    entryPoint       = ["/opt/docker/bin/query-api"]
    command          = ["-main", "scholarchain.storage.cli.LoadNeptuneCli"]
    stopTimeout      = 30
    environment      = [for k in sort(keys(var.environment)) : { name = k, value = var.environment[k] }]
    logConfiguration = local.log_config["neptune-loader"]
  }])
}

# ---------------------------------------------------------------------------------------------
# ALB (public, HTTP). HTTPS needs a domain and certificate, which the course window does not have;
# the API key therefore crosses the internet in the X-Api-Key header in clear text (README, risks).
# ---------------------------------------------------------------------------------------------

resource "aws_lb" "this" {
  name                       = "${var.name}-api"
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = var.public_subnet_ids
  security_groups            = [var.security_group_ids.alb]
  idle_timeout               = 60 # above the 40 s synchronous deadline
  drop_invalid_header_fields = true
  enable_deletion_protection = false
}

resource "aws_lb_target_group" "service" {
  for_each = local.routed

  name                 = substr("${var.name}-${each.key}", 0, 32)
  port                 = each.value.port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  # Liveness decides target health, so a downstream outage (which /readyz reports) does not make ECS
  # replace healthy tasks. /readyz is routed for the smoke test.
  health_check {
    path                = "/healthz"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = "{\"code\":\"NOT_FOUND\",\"message\":\"no such route\"}"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "service" {
  for_each = local.routed

  listener_arn = aws_lb_listener.http.arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service[each.key].arn
  }

  condition {
    path_pattern {
      values = each.value.paths
    }
  }
}

# Health and readiness of query-api through the ALB (smoke test). /metrics is not routed.
resource "aws_lb_listener_rule" "health" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 30

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/healthz", "/readyz"]
    }
  }
}

# ---------------------------------------------------------------------------------------------
# Services: desired 1 each, in public subnets with a public IP (no NAT, ruling 1).
# ---------------------------------------------------------------------------------------------

resource "aws_ecs_service" "this" {
  for_each = local.services

  name                               = each.key
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = contains(keys(local.routed), each.key) ? 120 : null
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [each.value.sg]
    assign_public_ip = true
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = contains(keys(local.routed), each.key) ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.service[each.key].arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  # Application Auto Scaling owns the running count between 1 and the service maximum.
  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener_rule.service]
}

resource "aws_appautoscaling_target" "this" {
  for_each = local.services

  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = 1
  max_capacity       = each.value.max
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each = { for k, s in local.services : k => s if s.max > 1 }

  name               = "${var.name}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_names" {
  value = { for k, s in aws_ecs_service.this : k => s.name }
}

output "task_definition_arns" {
  value = merge(
    { for k, t in aws_ecs_task_definition.service : k => t.arn },
    { neptune-loader = aws_ecs_task_definition.neptune_loader.arn },
  )
}

output "task_definition_families" {
  value = merge(
    { for k, t in aws_ecs_task_definition.service : k => t.family },
    { neptune-loader = aws_ecs_task_definition.neptune_loader.family },
  )
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

# The common SCHOLARCHAIN_* variables (no secret), for the laptop-run loaders in
# scripts/load-stores.sh, so they use exactly the configuration the tasks use.
output "environment" {
  value = var.environment
}

output "alb_arn_suffix" {
  value = aws_lb.this.arn_suffix
}

output "target_group_arn_suffixes" {
  value = { for k, t in aws_lb_target_group.service : k => t.arn_suffix }
}
