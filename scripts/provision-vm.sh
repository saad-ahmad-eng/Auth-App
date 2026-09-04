#!/bin/bash
# provision-vm.sh — AuthLock VM provisioning (OS + runtime layer).
#
# What this is: the automated script that provisions a *fresh* VM for
# AuthLock — installs Java 17, creates the service account and systemd
# unit, opens the local firewall, and discovers the VM's public
# address so later steps (this script's own optional auto-deploy path, or
# scripts/setup-authlock.sh run manually afterward) can configure
# java.rmi.server.hostname and the TLS certificate's SAN correctly
# (Architecture.md §3, Security.md §7 — the "Phase 11 cloud deployment
# TLS/hostname pitfall", now resolved via DevTlsSetup's
# -Dauthlock.tls.extraSan support).
#
# How it's meant to run:
#   - Automatically, as root, as EC2 user_data (terraform/main.tf splices
#     this file's content into user_data verbatim — see that file's
#     comment for why plain bash, not a Terraform template, is used here).
#   - Or manually, as root, on ANY fresh Linux VM (AWS or not):
#       sudo bash provision-vm.sh
#   Either way it is idempotent — safe to re-run.
#
# What it deliberately does NOT do: build or start AuthLock itself, unless
# AUTHLOCK_SOURCE_URL is set (see below) — this script only prepares the
# machine. scripts/setup-authlock.sh (run once the AuthLock source is
# actually present on the VM — via that URL, or via `scp`) does the
# build-and-start step. This split matches Implementation.md Phase 11's own
# task breakdown: "provision VM; install JRE; ... start server" are related
# but distinct concerns, and keeping them in separate scripts means either
# one is independently useful (e.g. re-running setup-authlock.sh alone
# after a `git pull`/code update, without re-provisioning the whole VM).
#
# Env vars this script reads (all optional):
#   AUTHLOCK_SOURCE_URL   HTTPS URL to a .tar.gz of the AuthLock project
#                         source. If set, this script downloads, extracts,
#                         and hands off to setup-authlock.sh automatically
#                         — a fully hands-off `terraform apply`. If unset
#                         (the default — this project ships with no public
#                         git remote, see Context.md), the VM still ends up
#                         fully OS-provisioned; you finish the app layer
#                         yourself (see the log output this script prints
#                         at the end).
#   AUTHLOCK_APP_DIR      Where the AuthLock project lives/will live.
#                         Default: /opt/authlock/app

set -euo pipefail

APP_DIR="${AUTHLOCK_APP_DIR:-/opt/authlock/app}"
SCRIPTS_DIR="/opt/authlock/scripts"
LOG_FILE="/var/log/authlock-provision.log"
MARKER_FILE="/opt/authlock/.provisioned"
SERVICE_USER="authlock"

# --- logging: everything goes to both the console (useful when run
# manually) and a log file (useful when run as unattended user_data,
# where nobody is watching stdout live) ---
mkdir -p "$(dirname "$LOG_FILE")"
exec > >(tee -a "$LOG_FILE") 2>&1

log() { echo "[provision-vm.sh] $(date -u '+%Y-%m-%dT%H:%M:%SZ') $*"; }

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "provision-vm.sh must run as root (sudo bash provision-vm.sh)." >&2
        exit 1
    fi
}

# --- 1. OS packages -----------------------------------------------------
install_packages() {
    log "Installing packages (Java 17 JDK, curl, unzip, git, ufw)..."
    if command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -y
        apt-get install -y openjdk-17-jdk curl unzip git ufw ca-certificates
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y java-17-openjdk-devel curl unzip git firewalld ca-certificates
    elif command -v yum >/dev/null 2>&1; then
        yum install -y java-17-openjdk-devel curl unzip git firewalld ca-certificates
    else
        log "ERROR: no supported package manager found (need apt-get, dnf, or yum). Install Java 17 manually and re-run."
        exit 1
    fi
    log "Java: $(java -version 2>&1 | head -1)"
}

# --- 2. Service account + directories -----------------------------------
create_service_user() {
    if ! id "$SERVICE_USER" >/dev/null 2>&1; then
        log "Creating service account '$SERVICE_USER'..."
        useradd --system --create-home --home-dir /opt/authlock --shell /usr/sbin/nologin "$SERVICE_USER"
    fi
    mkdir -p "$APP_DIR" "$SCRIPTS_DIR"
    # setup-authlock.sh needs to be reachable on the VM regardless of how
    # this script itself was invoked (user_data leaves no file on disk by
    # default) — copy ourselves and our sibling script into place.
    SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if [ -f "$SELF_DIR/setup-authlock.sh" ]; then
        cp "$SELF_DIR/setup-authlock.sh" "$SCRIPTS_DIR/setup-authlock.sh"
        chmod +x "$SCRIPTS_DIR/setup-authlock.sh"
    fi
    cp "${BASH_SOURCE[0]}" "$SCRIPTS_DIR/provision-vm.sh" 2>/dev/null || true
    chown -R "$SERVICE_USER":"$SERVICE_USER" /opt/authlock
}

# --- 3. Local firewall (belt-and-suspenders alongside any cloud security
# group — matters most for the "not on AWS" case, where there may be no
# separate cloud-level firewall at all) ----------------------------------
configure_firewall() {
    if command -v ufw >/dev/null 2>&1; then
        log "Configuring ufw (22/tcp, 1099/tcp, 5000/tcp)..."
        ufw allow 22/tcp >/dev/null || true
        ufw allow 1099/tcp >/dev/null || true
        ufw allow 5000/tcp >/dev/null || true
        # Only enable if not already active with a policy that would lock
        # out this very SSH session — --force skips the interactive prompt,
        # but we do NOT want to flip a previously-disabled firewall on
        # unexpectedly during an unattended user_data run without SSH
        # already allowed, so allow-22 is issued first, above.
        ufw --force enable >/dev/null 2>&1 || log "ufw enable skipped (already active or unavailable) — continuing."
    elif command -v firewall-cmd >/dev/null 2>&1; then
        log "Configuring firewalld (22/tcp, 1099/tcp, 5000/tcp)..."
        systemctl enable --now firewalld >/dev/null 2>&1 || true
        firewall-cmd --permanent --add-port=22/tcp >/dev/null || true
        firewall-cmd --permanent --add-port=1099/tcp >/dev/null || true
        firewall-cmd --permanent --add-port=5000/tcp >/dev/null || true
        firewall-cmd --reload >/dev/null || true
    else
        log "No local firewall tool found (ufw/firewalld) — relying on the cloud security group alone. Fine on AWS with terraform/main.tf's security group; on a bare VM with no equivalent, open these ports some other way."
    fi
}

# --- 4. Public address discovery — tries EC2's metadata service first
# (IMDSv2, token-based), falls back to a generic public-IP echo service so
# this same script works unmodified on a non-AWS VM too. -----------------
discover_public_address() {
    local ip="" dns=""

    # AWS IMDSv2 (token required on newer AMIs/hardened instances).
    local token
    token="$(curl -s -m 2 -X PUT "http://169.254.169.254/latest/api/token" \
        -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)"
    if [ -n "$token" ]; then
        ip="$(curl -s -m 2 -H "X-aws-ec2-metadata-token: $token" \
            "http://169.254.169.254/latest/meta-data/public-ipv4" 2>/dev/null || true)"
        dns="$(curl -s -m 2 -H "X-aws-ec2-metadata-token: $token" \
            "http://169.254.169.254/latest/meta-data/public-hostname" 2>/dev/null || true)"
    fi

    # Generic fallback (non-AWS VM, or metadata service unreachable).
    if [ -z "$ip" ]; then
        ip="$(curl -s -m 3 https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]' || true)"
    fi
    if [ -z "$ip" ]; then
        ip="$(curl -s -m 3 https://ifconfig.me 2>/dev/null | tr -d '[:space:]' || true)"
    fi

    echo "$ip" > /opt/authlock/public_ip
    echo "$dns" > /opt/authlock/public_dns
    chown "$SERVICE_USER":"$SERVICE_USER" /opt/authlock/public_ip /opt/authlock/public_dns 2>/dev/null || true

    if [ -z "$ip" ]; then
        log "WARNING: could not auto-discover a public IP. setup-authlock.sh will try again later, or you can pass one explicitly (see its --public-ip flag)."
    else
        log "Discovered public address: ip=$ip dns=${dns:-<none>}"
    fi
}

# --- 5. systemd unit (installed, NOT started yet — nothing to run until
# the AuthLock source is actually present and built) ---------------------
install_systemd_unit() {
    log "Installing systemd unit authlock-server.service (disabled until setup-authlock.sh runs)..."
    cat > /etc/systemd/system/authlock-server.service <<'EOF'
[Unit]
Description=AuthLock RMI Vault Server
After=network.target

[Service]
Type=simple
User=authlock
WorkingDirectory=/opt/authlock/app
EnvironmentFile=-/opt/authlock/authlock-server.env
ExecStart=/opt/authlock/app/authlock-server/build/install/authlock-server/bin/authlock-server
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
}

# --- 6. Optional fully-automated path ------------------------------------
maybe_auto_deploy() {
    local url="${AUTHLOCK_SOURCE_URL:-}"
    if [ -z "$url" ]; then
        log "AUTHLOCK_SOURCE_URL not set — stopping here. Upload the project and run setup-authlock.sh yourself (see next-steps below)."
        return
    fi
    log "AUTHLOCK_SOURCE_URL is set — attempting fully-automated deploy from $url ..."
    local tmp_tar="/tmp/authlock-source.tar.gz"
    if ! curl -fsSL -m 30 --retry 3 "$url" -o "$tmp_tar"; then
        log "WARNING: download of AUTHLOCK_SOURCE_URL failed. VM is still fully OS-provisioned — finish deployment manually (see next-steps below)."
        return
    fi
    mkdir -p "$APP_DIR"
    # Strip one leading path component in case the tarball has a single
    # top-level directory (the common case for `git archive`/GitHub
    # tarballs) — falls back to a flat extract if that assumption is wrong.
    tar -xzf "$tmp_tar" -C "$APP_DIR" --strip-components=1 2>/dev/null \
        || tar -xzf "$tmp_tar" -C "$APP_DIR"
    chown -R "$SERVICE_USER":"$SERVICE_USER" "$APP_DIR"
    log "Source extracted to $APP_DIR — handing off to setup-authlock.sh."
    "$SCRIPTS_DIR/setup-authlock.sh" || log "WARNING: setup-authlock.sh reported an error — check $LOG_FILE and finish manually."
}

print_next_steps() {
    local ip dns
    ip="$(cat /opt/authlock/public_ip 2>/dev/null || echo '<unknown>')"
    dns="$(cat /opt/authlock/public_dns 2>/dev/null || echo '')"
    cat <<EOF | tee /etc/motd >/dev/null

=====================================================================
 AuthLock VM provisioning: done.
 Public IP : ${ip}
 Public DNS: ${dns:-<none> (non-AWS VM, or not yet resolved)}

 If AUTHLOCK_SOURCE_URL was not set, the app layer is NOT deployed yet.
 Finish it with:
   1. From your machine: scp -r "AuthLock Project" <user>@${ip}:/opt/authlock/app
   2. On this VM:        sudo /opt/authlock/scripts/setup-authlock.sh
 Full instructions: terraform/README.md in the project.
=====================================================================

EOF
}

main() {
    require_root
    log "provision-vm.sh: starting."
    if [ -f "$MARKER_FILE" ]; then
        log "Marker $MARKER_FILE exists — machine-level provisioning already done, re-running lightweight steps only (address discovery, firewall) in case anything changed."
        discover_public_address
        configure_firewall
    else
        install_packages
        create_service_user
        configure_firewall
        discover_public_address
        install_systemd_unit
        touch "$MARKER_FILE"
    fi
    maybe_auto_deploy
    print_next_steps
    log "provision-vm.sh: done."
}

main "$@"
