# versions.tf — Terraform/provider version pinning for AuthLock's Phase 11
# (Cloud Deployment) infrastructure. See ../Implementation.md Phase 11 and
# ../Architecture.md §3 for the deployment design this implements.

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}
