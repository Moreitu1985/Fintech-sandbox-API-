terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.95"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # State holds generated secrets (database, Redis and app tokens, the token-signing key), so it lives in
  # an encrypted, versioned, access-restricted S3 bucket. Supply the bucket at init time:
  #   terraform init -backend-config="bucket=<state bucket>" -backend-config="key=ledgerforge/prod.tfstate" \
  #                  -backend-config="region=af-south-1" -backend-config="use_lockfile=true" -backend-config="encrypt=true"
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}
