# main.tf — AWS Free Tier deployment for the AuthLock RMI server, per
# Architecture.md §3 and Implementation.md Phase 11.
#
# What this creates (and nothing more — no NAT gateway, no load balancer,
# no managed database; ADR-003/ADR-009 are explicit that AuthLock is a
# deliberately single-server architecture):
#   - One EC2 instance (t2.micro by default — AWS Free Tier eligible)
#   - One security group: SSH (22), RMI registry (1099), RMI object port
#     (5000 — RmiConfig.SERVICE_PORT)
#   - A boot-time provisioning script (scripts/provision-vm.sh) that installs
#     Java 17, prepares the AuthLock systemd service, and (if
#     var.authlock_source_url is set) builds and starts the server
#     automatically
#
# Nothing here is provider-locked by ambition — only by what's actually
# implemented. Oracle Cloud/GCP free tiers were considered (OQ-06) and AWS
# was chosen; scripts/provision-vm.sh and scripts/setup-authlock.sh are
# themselves cloud-agnostic bash (they auto-detect their environment) and
# work unmodified on a non-AWS VM — see terraform/README.md.

provider "aws" {
  region = var.aws_region
}

# Latest Ubuntu 22.04 LTS AMI, resolved dynamically (never hardcode an AMI
# ID — they're region- and time-specific and go stale).
data "aws_ami" "ubuntu_2204" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_security_group" "authlock" {
  name        = "${var.project_tag}-sg"
  description = "AuthLock server: SSH + RMI registry/object port (Architecture.md §3)"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  ingress {
    description = "AuthLock RMI registry (RmiConfig.REGISTRY_PORT)"
    from_port   = 1099
    to_port     = 1099
    protocol    = "tcp"
    cidr_blocks = [var.allowed_rmi_cidr]
  }

  ingress {
    description = "AuthLock RMI object port, fixed not ephemeral (RmiConfig.SERVICE_PORT) — TRD §2.6"
    from_port   = 5000
    to_port     = 5000
    protocol    = "tcp"
    cidr_blocks = [var.allowed_rmi_cidr]
  }

  egress {
    description = "Unrestricted outbound (package installs, etc.)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Project = var.project_tag
    Name    = "${var.project_tag}-sg"
  }
}

resource "aws_instance" "authlock_server" {
  ami                    = data.aws_ami.ubuntu_2204.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.authlock.id]

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
  }

  # scripts/provision-vm.sh is plain, portable bash — no Terraform template
  # syntax inside it, so it stays copy-paste-usable on a non-Terraform VM
  # too. The one dynamic value Terraform contributes (an optional source
  # tarball URL) is exported as an env var ahead of the script content,
  # rather than templated into it, to keep that separation clean.
  user_data = <<-EOT
    #!/bin/bash
    export AUTHLOCK_SOURCE_URL="${var.authlock_source_url}"
    ${file("${path.module}/../scripts/provision-vm.sh")}
  EOT

  tags = {
    Project = var.project_tag
    Name    = "${var.project_tag}-server"
  }
}
