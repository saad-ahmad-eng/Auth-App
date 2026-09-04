# variables.tf — every value here has a free-tier-safe default except
# key_name, which you must supply (Terraform never generates or stores a
# private key for you — see README.md "Before you start").

variable "aws_region" {
  description = "AWS region to deploy into. Free tier (750 hrs/month t2.micro) applies in every region, but us-east-1 is the most commonly used default."
  type        = string
  default     = "us-east-1"
}

variable "key_name" {
  description = "Name of an EXISTING EC2 key pair in this region (Console: EC2 > Key Pairs, or `aws ec2 create-key-pair`). Required — no default, so a fresh `terraform apply` never silently creates unmanaged key material."
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type. t2.micro is the classic 12-month AWS Free Tier type (750 hrs/month); some newer accounts get t3.micro instead — override if `terraform apply` reports t2.micro unavailable in your account/region."
  type        = string
  default     = "t2.micro"
}

variable "root_volume_size_gb" {
  description = "Root EBS volume size in GiB. AWS Free Tier includes up to 30 GiB of gp2/gp3 EBS storage — kept well under that by default."
  type        = number
  default     = 10
}

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to reach port 22 (SSH). Defaults to open (0.0.0.0/0) for coursework demo convenience — Architecture.md §3 documents this as a conscious, reviewed trade-off, not an oversight. Restrict to your own IP (e.g. \"203.0.113.4/32\") for anything beyond a short-lived demo."
  type        = string
  default     = "0.0.0.0/0"
}

variable "allowed_rmi_cidr" {
  description = "CIDR allowed to reach the RMI ports (1099 registry, 5000 object port — see RmiConfig). Defaults open so the Swing client can connect from wherever you're demoing from; narrow this down for anything longer-lived than a demo."
  type        = string
  default     = "0.0.0.0/0"
}

variable "authlock_source_url" {
  description = <<-EOT
    Optional HTTPS URL to a .tar.gz of the AuthLock project source (e.g. `git archive` output uploaded somewhere you control, or a GitHub release tarball if you later push one). When set, the VM downloads, builds, and starts AuthLock automatically on first boot — a fully hands-off `terraform apply`.

    Left empty (the default) because this project has no public git remote as shipped (see Context.md) — in that case the VM still fully provisions the OS/Java/systemd layer via scripts/provision-vm.sh, and you finish the app layer yourself: `scp` the project up, then run `scripts/setup-authlock.sh` over SSH. See terraform/README.md for both paths.
  EOT
  type    = string
  default = ""
}

variable "project_tag" {
  description = "Value for the Project/Name tag on every resource this config creates — makes cleanup (`terraform destroy` or manual console review) unambiguous."
  type        = string
  default     = "authlock"
}
