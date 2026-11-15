# Observability. Always: one CloudWatch log group per ECS task definition (14-day retention), so the
# window 1 plan has them (ruling 2). runtime_enabled only: the alarms (ALB 5xx, unhealthy targets,
# DLQ depth above 0, ECS CPU, Neptune CPU), one small dashboard, and an SNS topic when an alarm email
# is given (the email comes from a variable with no default).
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

variable "runtime_enabled" {
  type = bool
}

variable "log_retention_days" {
  type = number
}

variable "alarm_email" {
  type = string
}

variable "alb_5xx_threshold" {
  type = number
}

variable "cpu_alarm_threshold" {
  type = number
}

variable "alb_arn_suffix" {
  type    = string
  default = null
}

variable "target_group_arn_suffixes" {
  description = "Service name -> target group ARN suffix."
  type        = map(string)
  default     = null
}

variable "cluster_name" {
  type    = string
  default = null
}

variable "service_names" {
  description = "Service key -> ECS service name."
  type        = map(string)
  default     = null
}

variable "dlq_name" {
  type    = string
  default = null
}

variable "neptune_cluster_id" {
  type    = string
  default = null
}

locals {
  task_definitions = toset(["query-api", "graph-evidence-api", "worker", "neptune-loader"])
  rt               = var.runtime_enabled ? 1 : 0
  notify           = var.runtime_enabled && var.alarm_email != ""
  actions          = local.notify ? [aws_sns_topic.alarms[0].arn] : []
  services         = var.runtime_enabled ? var.service_names : {}
  target_groups    = var.runtime_enabled ? var.target_group_arn_suffixes : {}
}

resource "aws_cloudwatch_log_group" "ecs" {
  for_each          = local.task_definitions
  name              = "/scholarchain/${var.name}/ecs/${each.key}"
  retention_in_days = var.log_retention_days
}

# Alarm notifications carry no data. The topic keeps the default (unencrypted) setting because
# CloudWatch cannot publish to a topic encrypted with the AWS-managed aws/sns key, and ruling 1
# allows no customer-managed key.
resource "aws_sns_topic" "alarms" {
  count = local.notify ? 1 : 0
  name  = "${var.name}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count     = local.notify ? 1 : 0
  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  for_each = var.runtime_enabled ? {
    target = "HTTPCode_Target_5XX_Count"
    elb    = "HTTPCode_ELB_5XX_Count"
  } : {}

  alarm_name          = "${var.name}-alb-${each.key}-5xx"
  alarm_description   = "ALB ${each.value} at or above ${var.alb_5xx_threshold} in 5 minutes"
  namespace           = "AWS/ApplicationELB"
  metric_name         = each.value
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.alb_5xx_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
}

resource "aws_cloudwatch_metric_alarm" "unhealthy_targets" {
  for_each = local.target_groups

  alarm_name          = "${var.name}-${each.key}-unhealthy-targets"
  alarm_description   = "${each.key}: at least one unhealthy target for 2 minutes"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = each.value }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
}

resource "aws_cloudwatch_metric_alarm" "dlq_depth" {
  count = local.rt

  alarm_name          = "${var.name}-jobs-dlq-not-empty"
  alarm_description   = "Job dead-letter queue holds a message"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.dlq_name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  for_each = local.services

  alarm_name          = "${var.name}-${each.key}-cpu"
  alarm_description   = "${each.key}: average CPU at or above ${var.cpu_alarm_threshold}% for 10 minutes"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = { ClusterName = var.cluster_name, ServiceName = each.value }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.cpu_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
}

resource "aws_cloudwatch_metric_alarm" "neptune_cpu" {
  count = local.rt

  alarm_name          = "${var.name}-neptune-cpu"
  alarm_description   = "Neptune: average CPU at or above ${var.cpu_alarm_threshold}% for 10 minutes"
  namespace           = "AWS/Neptune"
  metric_name         = "CPUUtilization"
  dimensions          = { DBClusterIdentifier = var.neptune_cluster_id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.cpu_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
}

resource "aws_cloudwatch_dashboard" "runtime" {
  count          = local.rt
  dashboard_name = "${var.name}-runtime"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title  = "ALB requests and 5xx"
          region = var.region
          stat   = "Sum"
          period = 60
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title  = "ALB target response time (p50, p95, p99)"
          region = var.region
          period = 60
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p50" }],
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p95" }],
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p99" }],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title   = "ECS CPU by service"
          region  = var.region
          stat    = "Average"
          period  = 60
          metrics = [for k, s in local.services : ["AWS/ECS", "CPUUtilization", "ClusterName", var.cluster_name, "ServiceName", s]]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "Healthy targets, job DLQ depth, Neptune CPU"
          region = var.region
          stat   = "Maximum"
          period = 60
          metrics = concat(
            [for k, tg in local.target_groups : ["AWS/ApplicationELB", "HealthyHostCount", "TargetGroup", tg, "LoadBalancer", var.alb_arn_suffix]],
            [
              ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.dlq_name],
              ["AWS/Neptune", "CPUUtilization", "DBClusterIdentifier", var.neptune_cluster_id],
            ]
          )
        }
      },
    ]
  })
}

output "log_group_names" {
  value = { for k, g in aws_cloudwatch_log_group.ecs : k => g.name }
}

output "log_group_arns" {
  value = { for k, g in aws_cloudwatch_log_group.ecs : k => g.arn }
}

output "dashboard_name" {
  value = var.runtime_enabled ? aws_cloudwatch_dashboard.runtime[0].dashboard_name : null
}
