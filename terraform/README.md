# AuthLock — Phase 11 Cloud Deployment

Infrastructure-as-code for deploying the AuthLock RMI server to AWS's free
tier, per [Implementation.md](../Implementation.md) Phase 11 and
[Architecture.md](../Architecture.md) §3. Resolves Open Question OQ-06
(cloud provider) as **AWS**.

This directory only provisions infrastructure. The actual app-layer setup
(build, TLS cert with the correct SAN, systemd service) is done by
[`../scripts/setup-authlock.sh`](../scripts/setup-authlock.sh) — either
automatically (if you set `authlock_source_url`) or by you running it once
over SSH after uploading the project. Both paths are below.

## What gets created

- One EC2 instance (`t2.micro` by default — AWS Free Tier: 750 hrs/month
  for 12 months on a new account)
- One security group: SSH (22), RMI registry (1099), RMI object port
  (5000 — `RmiConfig.SERVICE_PORT`)
- Nothing else. No load balancer, no managed database, no NAT gateway —
  AuthLock is deliberately a single-server architecture (ADR-003/ADR-009).

## Before you start

1. An AWS account with free-tier eligibility, and credentials configured
   for the `aws` CLI/Terraform (`aws configure`, or the usual
   `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` env vars). **This repo
   never stores or requests your AWS credentials** — Terraform reads them
   from your own environment/AWS config the same way the `aws` CLI does.
2. An existing EC2 key pair in the region you'll deploy to (EC2 console →
   Key Pairs → Create key pair, or `aws ec2 create-key-pair`). Terraform
   references it by name (`key_name`) — it does not create or manage the
   private key file for you; keep the `.pem` it gives you somewhere safe.
3. [Terraform](https://developer.hashicorp.com/terraform/install) ≥ 1.5.

## Deploying

```bash
cd terraform
terraform init
terraform apply -var="key_name=<your-key-pair-name>"
```

Review the plan, type `yes`. Takes a couple of minutes. Terraform prints
`public_ip`, `public_dns`, `ssh_command`, and `next_steps` when done.

### Path A — manual finish (default; no public source hosting required)

This project ships with no public git remote (see `Context.md`), so by
default `terraform apply` fully provisions the *VM* (Java 17, systemd unit,
firewall, service account) and stops there — the *app* layer needs one
more step from you:

```bash
# from your machine, in this project's root:
scp -i /path/to/<key>.pem -r "AuthLock Project" ubuntu@<public_ip>:/opt/authlock/app

ssh -i /path/to/<key>.pem ubuntu@<public_ip>
sudo /opt/authlock/scripts/setup-authlock.sh
```

`setup-authlock.sh` builds the server, discovers the VM's public
IP/hostname, generates the TLS certificate with that address in its SAN
(see "TLS and the SAN pitfall" below), installs/starts the systemd
service, and prints the exact files to copy back down for a remote client.

### Path B — fully automated (if you host a source tarball somewhere)

```bash
terraform apply \
  -var="key_name=<your-key-pair-name>" \
  -var="authlock_source_url=https://example.com/authlock-source.tar.gz"
```

`scripts/provision-vm.sh` downloads and extracts that URL automatically at
boot, then runs `setup-authlock.sh` itself — no SSH step needed at all.
(A tarball like this is easy to produce yourself: `git archive --format=tar.gz -o authlock-source.tar.gz HEAD`
from the project root, uploaded anywhere reachable over HTTPS.)

## Connecting a client

Both paths end with the same printed instructions: copy
`authlock-shared.key` and `certs/` down from the VM to your client
machine's copy of the project (the AES-GCM key and the self-signed
TLS cert **must** be shared files, not independently generated — see
Security.md §7), then:

```bash
./gradlew :authlock-client:run -Dauthlock.server.host=<public_ip>
```

## TLS and the SAN pitfall (why this matters, and what's already handled)

`DevTlsSetup`'s self-signed certificate defaults to `CN=localhost`, SAN
`dns:localhost,ip:127.0.0.1` — fine for local development, but a genuinely
remote client's TLS handshake will fail hostname verification against a
cert that doesn't list the VM's actual address. This was flagged as an
explicit, not-yet-done Phase 11 task in `Architecture.md`/`Security.md`
since Phase 6, and is now resolved: `DevTlsSetup` accepts
`-Dauthlock.tls.extraSan=ip:<addr>[,dns:<name>]`, and both scripts here
set it automatically from the VM's discovered public address before the
server's first run. The same applies to `java.rmi.server.hostname` (a
related, separate RMI-stub pitfall, already documented and fixed back in
Phase 6 for the *local* case — these scripts set it explicitly to the
VM's public address for the *remote* case).

If you ever delete and let the server regenerate its certificate/key by
hand instead of via these scripts, remember both need to happen again:
`java.rmi.server.hostname` and `authlock.tls.extraSan` (or the client's
TLS handshake and stub resolution will both point at the wrong place).

## Cleaning up

```bash
terraform destroy
```

## Using a VM instead of AWS entirely

`scripts/provision-vm.sh` and `scripts/setup-authlock.sh` are plain,
portable bash — no AWS SDK, no Terraform dependency baked into either
script. On any other Linux VM (Oracle Cloud/GCP free tier, a spare
machine, whatever):

```bash
sudo bash scripts/provision-vm.sh   # OS + Java + systemd unit + firewall
# upload the project, then:
sudo scripts/setup-authlock.sh      # build, configure, start
```

Both auto-detect their environment (AWS metadata service if present,
falling back to a generic public-IP lookup otherwise) — no AWS-specific
input required either way.

## Status

Infrastructure and scripts are written, code-reviewed, and locally
verified where testable without a real target VM (build step confirmed
producing the expected `installDist` output; the address-discovery
fallback chain confirmed correctly skipping the AWS metadata service and
resolving a real public IP via the generic fallback in this
non-AWS sandbox; `DevTlsSetup`'s new SAN parameter confirmed via a direct
keytool inspection to produce a certificate with the expected extra SAN
entries). **Not yet executed against a real AWS account** — this
environment has no AWS credentials to run `terraform apply` with, so
TEST-DEPLOY-001/002 (Testing.md) remain the concrete next action for
whoever has an AWS account to run this against.
