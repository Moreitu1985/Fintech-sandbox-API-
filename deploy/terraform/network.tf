# Three availability zones. Public subnets hold only the load balancer and NAT gateways; the cluster,
# database and Redis sit in private subnets with no route from the internet.
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.21"

  name = local.name
  cidr = var.vpc_cidr
  azs  = local.azs

  public_subnets      = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i)]
  private_subnets     = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 4, i + 1)]
  database_subnets    = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i + 100)]
  elasticache_subnets = [for i, _ in local.azs : cidrsubnet(var.vpc_cidr, 8, i + 110)]

  create_database_subnet_group           = true
  create_database_subnet_route_table     = true
  create_database_internet_gateway_route = false
  create_elasticache_subnet_group        = true

  enable_nat_gateway     = true
  one_nat_gateway_per_az = var.multi_az
  single_nat_gateway     = !var.multi_az

  map_public_ip_on_launch = false
  enable_dns_hostnames    = true
  enable_dns_support      = true

  # Network flow logs for incident investigation.
  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_max_aggregation_interval               = 60
  flow_log_cloudwatch_log_group_retention_in_days = 365

  # The default security group allows nothing.
  manage_default_security_group  = true
  default_security_group_ingress = []
  default_security_group_egress  = []

  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}
