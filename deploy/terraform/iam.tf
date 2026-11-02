# IAM role for External Secrets Operator (IRSA). Only the "ledgerforge-secrets-reader" service account
# in the ledgerforge namespace can assume it, and it can read only this environment's secrets.
data "aws_iam_policy_document" "secrets_reader_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:sub"
      values   = ["system:serviceaccount:${local.k8s_namespace}:ledgerforge-secrets-reader"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "secrets_reader" {
  name               = "${local.name}-secrets-reader"
  description        = "External Secrets Operator: read LedgerForge ${var.environment} secrets"
  assume_role_policy = data.aws_iam_policy_document.secrets_reader_trust.json
}

data "aws_iam_policy_document" "secrets_reader" {
  statement {
    sid       = "ReadThisEnvironmentsSecrets"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [for s in aws_secretsmanager_secret.service : s.arn]
  }

  statement {
    sid       = "DecryptThroughSecretsManagerOnly"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.data.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "secrets_reader" {
  name   = "read-secrets"
  role   = aws_iam_role.secrets_reader.id
  policy = data.aws_iam_policy_document.secrets_reader.json
}
