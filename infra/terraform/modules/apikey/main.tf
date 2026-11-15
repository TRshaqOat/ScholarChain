# API key secret (runtime_enabled; D-10 item 9; the work order's "secrets" module, named apikey because
# the permission policy denies any path segment named secrets/). Terraform creates the secret container
# only; it never holds a value, so no secret enters source, variables, plans, or state. The human sets
# the value after apply (infra/terraform/README.md). ECS injects it as SCHOLARCHAIN_API_KEY by ARN.
# Encryption: the AWS-managed key aws/secretsmanager.
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

variable "recovery_window_days" {
  type = number
}

resource "aws_secretsmanager_secret" "api_key" {
  name                    = "${var.name}/api-key"
  description             = "ScholarChain REST API key (X-Api-Key). Value set by the human, never by Terraform."
  recovery_window_in_days = var.recovery_window_days
}

output "api_key_secret_arn" {
  value = aws_secretsmanager_secret.api_key.arn
}

output "api_key_secret_name" {
  value = aws_secretsmanager_secret.api_key.name
}
