data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  name           = "ledgerforge-${var.environment}"
  secrets_prefix = "ledgerforge/${var.environment}"
  azs            = slice(data.aws_availability_zones.available.names, 0, 3)
  k8s_namespace  = "ledgerforge"
  public_range   = cidrsubnet(var.vpc_cidr, 4, 0)  # load balancers and NAT only
  data_range     = cidrsubnet(var.vpc_cidr, 4, 15) # RDS and ElastiCache only

  tags = {
    Project     = "ledgerforge"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

# One customer-managed key for data at rest: database, Redis, secrets and container images.
# Rotated yearly by AWS; access is controlled by IAM and the key policy.
resource "aws_kms_key" "data" {
  description             = "${local.name} data at rest"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "data" {
  name          = "alias/${local.name}-data"
  target_key_id = aws_kms_key.data.key_id
}
