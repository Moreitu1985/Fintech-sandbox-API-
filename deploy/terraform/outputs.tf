output "cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "configure_kubectl" {
  description = "Command that points kubectl at the cluster."
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "ecr_repositories" {
  description = "Where to push the images."
  value       = { for k, r in aws_ecr_repository.service : k => r.repository_url }
}

output "database_endpoint" {
  description = "RDS PostgreSQL address (private)."
  value       = aws_db_instance.postgres.address
}

output "redis_endpoint" {
  description = "ElastiCache primary endpoint (private, TLS)."
  value       = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "kustomize_params" {
  description = "Contents for deploy/k8s/overlays/aws/params.env (no secrets)."
  value       = <<-EOT
    AWS_REGION=${var.region}
    VPC_CIDR=${var.vpc_cidr}
    LEDGER_API_IMAGE=${aws_ecr_repository.service["ledger-api"].repository_url}:${var.image_tag}
    FRAUD_SERVICE_IMAGE=${aws_ecr_repository.service["fraud-service"].repository_url}:${var.image_tag}
    DATASOURCE_URL=jdbc:postgresql://${aws_db_instance.postgres.address}:5432/ledgerforge?sslmode=verify-full&sslrootcert=/etc/ledgerforge/rds/global-bundle.pem
    REDIS_HOST=${aws_elasticache_replication_group.redis.primary_endpoint_address}
    SECRETS_READER_ROLE_ARN=${aws_iam_role.secrets_reader.arn}
    SECRETS_PREFIX=${local.secrets_prefix}
    ACM_CERTIFICATE_ARN=${var.acm_certificate_arn}
    API_HOSTNAME=${var.api_hostname}
  EOT
}
