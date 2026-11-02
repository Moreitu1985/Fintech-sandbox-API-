module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.37"

  cluster_name    = local.name
  cluster_version = var.kubernetes_version

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  # Private API endpoint by default; public only from the listed networks.
  cluster_endpoint_private_access      = true
  cluster_endpoint_public_access       = length(var.cluster_endpoint_public_access_cidrs) > 0
  cluster_endpoint_public_access_cidrs = var.cluster_endpoint_public_access_cidrs

  # Kubernetes Secrets are envelope-encrypted with a KMS key (the module creates and rotates it).
  # Control-plane audit logs go to CloudWatch.
  cluster_enabled_log_types              = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
  cloudwatch_log_group_retention_in_days = 365

  enable_cluster_creator_admin_permissions = true
  enable_irsa                              = true

  cluster_addons = {
    coredns    = {}
    kube-proxy = {}
    vpc-cni = {
      before_compute = true
      # Enforce Kubernetes NetworkPolicies (deploy/k8s uses default-deny).
      configuration_values = jsonencode({
        enableNetworkPolicy = "true"
      })
    }
    eks-pod-identity-agent = {}
  }

  eks_managed_node_groups = {
    default = {
      ami_type       = "AL2023_x86_64_STANDARD"
      instance_types = var.node_instance_types
      min_size       = var.node_count.min
      desired_size   = var.node_count.desired
      max_size       = var.node_count.max

      # IMDSv2 only, and one network hop: pods can't borrow the node's AWS credentials.
      metadata_options = {
        http_endpoint               = "enabled"
        http_tokens                 = "required"
        http_put_response_hop_limit = 1
      }

      block_device_mappings = {
        xvda = {
          device_name = "/dev/xvda"
          ebs = {
            volume_size = 50
            volume_type = "gp3"
            # Encrypted with the AWS-managed EBS key: node disks hold only images and scratch space,
            # never ledger data, and this avoids granting Auto Scaling use of the data key.
            encrypted             = true
            delete_on_termination = true
          }
        }
      }
    }
  }
}
