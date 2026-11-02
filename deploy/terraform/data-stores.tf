# ---------- PostgreSQL (RDS) ----------

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "PostgreSQL: reachable only from the EKS worker nodes"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_nodes" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from EKS nodes (ledger API pods)"
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_parameter_group" "postgres" {
  name_prefix = "${local.name}-pg16-"
  family      = "postgres16"
  description = "LedgerForge PostgreSQL 16: TLS required, connections and slow queries logged"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_connections"
    value = "1"
  }
  parameter {
    name  = "log_disconnections"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "postgres" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  db_name  = "ledgerforge"
  username = "ledgerforge"
  password = random_password.db.result
  port     = 5432

  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_allocated_storage_gb * 4
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.data.arn

  multi_az               = var.multi_az
  db_subnet_group_name   = module.vpc.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.postgres.name
  ca_cert_identifier     = "rds-ca-rsa2048-g1"

  # Point-in-time recovery for two weeks; a final snapshot is kept if the database is ever deleted.
  backup_retention_period   = 14
  backup_window             = "01:00-02:00"
  maintenance_window        = "sun:02:30-sun:03:30"
  copy_tags_to_snapshot     = true
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name}-final"

  auto_minor_version_upgrade          = true
  iam_database_authentication_enabled = true
  enabled_cloudwatch_logs_exports     = ["postgresql", "upgrade"]
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = aws_kms_key.data.arn
}

# ---------- Redis (ElastiCache) ----------

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Redis: reachable only from the EKS worker nodes"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_nodes" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from EKS nodes (ledger API pods)"
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = local.name
  description          = "LedgerForge shared rate-limit buckets"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.redis_node_type
  port                 = 6379
  parameter_group_name = "default.redis7"

  num_cache_clusters         = var.multi_az ? 2 : 1
  automatic_failover_enabled = var.multi_az
  multi_az_enabled           = var.multi_az

  subnet_group_name  = module.vpc.elasticache_subnet_group_name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result
  # A new token is added next to the old one, so pods still holding the old token keep working.
  auth_token_update_strategy = "ROTATE"

  snapshot_retention_limit   = 1
  auto_minor_version_upgrade = true
  apply_immediately          = false
}
