# Terraform and provider pins (ruling 4, D-09). Local state only (ruling 1): no backend block, so
# Terraform uses the local backend; terraform.tfstate is gitignored and backed up by the human outside
# the repository. The remote-state design is documented in infra/terraform/README.md.
terraform {
  required_version = "1.16.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.28.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "ScholarChain"
      Environment = var.environment
      Course      = "CS441"
      Owner       = var.owner
      Expiration  = var.expiration_date
      ManagedBy   = "terraform"
    }
  }
}
