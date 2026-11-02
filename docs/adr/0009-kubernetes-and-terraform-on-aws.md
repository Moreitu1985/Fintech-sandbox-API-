# ADR 0009: Kubernetes on EKS, provisioned with Terraform

**Status:** Accepted

## Context

LedgerForge runs as two services (the ledger API and the fraud scorer) plus PostgreSQL and Redis. Docker Compose is fine for one machine, but a fintech will ask how it runs in production: several replicas across data centres, managed backups, secrets that never sit in Git, and evidence that the setup is reviewed like code.

## Decision

- **AWS, Cape Town region (`af-south-1`) by default**, so South African customer data stays in the country (POPIA). The region is a variable.
- **Terraform** for everything in the AWS account, using the widely used community modules for the VPC and EKS and plain resources for the rest. CI runs `terraform fmt`, `terraform validate` and Trivy on every change; no cloud credentials are involved.
- **Managed data stores, not databases in Kubernetes:** RDS PostgreSQL 16 (Multi-AZ, point-in-time recovery) and ElastiCache Redis. Losing ledger data is the one unacceptable failure, so it goes to the service with automated backups and failover.
- **Kustomize, with a cloud-neutral base and an AWS overlay.** The base holds what never changes between environments (hardening, probes, autoscaling, NetworkPolicies, mTLS). The overlay holds AWS specifics, and its environment values come from a `params.env` that Terraform generates, so nobody edits YAML by hand.
- **Secrets:** Terraform generates them into Secrets Manager; External Secrets Operator copies them into Kubernetes; pods mount them as files that Spring reads as a config tree. Nothing secret is in Git, image layers or environment variables (except the fraud service's token, which that service reads from its environment).
- **Service-to-service mTLS through cert-manager**, keeping the design of ADR 0008: a CA used only for this link, short-lived certificates renewed automatically.
- **Least exposure:** private subnets, a private Kubernetes API by default, default-deny NetworkPolicies, Pod Security "restricted", and only `/v1` and `/oauth` routed from the internet.

## Alternatives considered

- **Helm chart** instead of Kustomize: better for distributing to other teams, but templates hide the final YAML. Kustomize's output is plain manifests that reviewers and scanners can read. A chart can come later if the platform is offered to customers to self-host.
- **ECS Fargate:** simpler to run, but Kubernetes is what most fintech platform teams use and lets the same manifests run on other clouds or on premises.
- **Postgres operator inside the cluster:** cheaper, but backups, failover and patching become our job.

## Consequences

- One `terraform apply`, a few Helm add-ons and one `kubectl apply -k` produce the environment, and the same review and scanning applies to infrastructure as to code.
- Terraform state contains the generated secrets, so the state bucket must be encrypted, versioned and tightly restricted. Write-only attributes would remove this; they were not used yet so that the configuration works on older Terraform versions.
- A running EKS cluster with Multi-AZ RDS and Redis costs real money (several hundred dollars a month). For demos, `multi_az = false` and smaller instance classes cut that substantially; the public demo stays on Render.
- Accepted Trivy findings are recorded in `deploy/terraform/.trivyignore.yaml` with a reason and an expiry date, so they come back for review.
