# Every secret the services need is generated here and stored in Secrets Manager (encrypted with the
# data key). External Secrets Operator copies them into the cluster; nothing secret is kept in Git.
# They are also in Terraform state, which is why the state bucket must be encrypted and locked down.

resource "random_password" "db" {
  length  = 40
  special = false
}

# ElastiCache auth tokens: 16-128 printable characters, no @ " /
resource "random_password" "redis" {
  length  = 64
  special = false
}

resource "random_password" "admin_token" {
  length  = 48
  special = false
}

resource "random_password" "metrics_token" {
  length  = 48
  special = false
}

resource "random_password" "fraud_service_token" {
  length  = 48
  special = false
}

# AES-256 key that encrypts webhook signing secrets in the database.
resource "random_bytes" "webhook_encryption_key" {
  length = 32
}

# RSA key that signs OAuth access tokens (ADR 0008); shared by all API replicas.
resource "tls_private_key" "oauth_signing" {
  algorithm = "RSA"
  rsa_bits  = 3072
}

locals {
  secrets = {
    database = jsonencode({
      username = aws_db_instance.postgres.username
      password = random_password.db.result
    })
    redis = jsonencode({
      auth_token = random_password.redis.result
    })
    app = jsonencode({
      admin_token            = random_password.admin_token.result
      metrics_token          = random_password.metrics_token.result
      fraud_service_token    = random_password.fraud_service_token.result
      webhook_encryption_key = random_bytes.webhook_encryption_key.base64
    })
    oauth-signing-key = tls_private_key.oauth_signing.private_key_pem_pkcs8
  }
}

resource "aws_secretsmanager_secret" "service" {
  for_each = toset(keys(local.secrets))

  name                    = "${local.secrets_prefix}/${each.key}"
  description             = "LedgerForge ${var.environment}: ${each.key}"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret_version" "service" {
  for_each = aws_secretsmanager_secret.service

  secret_id     = each.value.id
  secret_string = local.secrets[each.key]
}
