variable "region" {
  description = "AWS region. Cape Town by default, keeping South African customer data in the country (POPIA)."
  type        = string
  default     = "af-south-1"
}

variable "environment" {
  description = "Environment name, used in resource names and the secrets prefix."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment))
    error_message = "Use 2-16 lowercase letters, digits or hyphens."
  }
}

variable "vpc_cidr" {
  description = "Address range for the VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.33"
}

variable "cluster_endpoint_public_access_cidrs" {
  description = "Networks allowed to reach the Kubernetes API from the internet. Empty = private endpoint only (reach it through a VPN or bastion)."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  description = "Instance types for the EKS managed node group."
  type        = list(string)
  default     = ["m6i.large"]
}

variable "node_count" {
  description = "Minimum, desired and maximum number of worker nodes."
  type = object({
    min     = number
    desired = number
    max     = number
  })
  default = { min = 3, desired = 3, max = 6 }
}

variable "db_instance_class" {
  description = "RDS instance class for PostgreSQL."
  type        = string
  default     = "db.m6g.large"
}

variable "db_allocated_storage_gb" {
  description = "Initial database storage; grows automatically up to 4x."
  type        = number
  default     = 50
}

variable "redis_node_type" {
  description = "ElastiCache node type. Redis only holds rate-limit buckets, so a small node is enough."
  type        = string
  default     = "cache.t4g.small"
}

variable "multi_az" {
  description = "Run the database and Redis in two availability zones with automatic failover."
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Protect the database from deletion (turn off only to tear down a test environment)."
  type        = bool
  default     = true
}

variable "api_hostname" {
  description = "Public host name of the API (for the ingress and the generated kustomize parameters)."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9-]+\\.)+[a-z]{2,}$", var.api_hostname))
    error_message = "Give a host name such as api.example.com."
  }
}

variable "acm_certificate_arn" {
  description = "ACM certificate for api_hostname, used by the load balancer."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:acm:", var.acm_certificate_arn))
    error_message = "Give the ARN of an ACM certificate in the deployment region."
  }
}

variable "image_tag" {
  description = "Image tag to deploy (images are immutable in ECR, so tags are never overwritten)."
  type        = string
  default     = "0.3.0"
}
