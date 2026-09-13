#!/bin/bash
# setup-authlock.sh — build, configure, and start the AuthLock server on
# THIS machine. This is the "run it on any VM instead of the cloud" script:
# it is self-contained (installs Java itself if missing) and does not
# require scripts/provision-vm.sh to have run first, though it reuses the
# same systemd unit / firewall / address-discovery conventions if it did.
#
# Usage:
#   sudo ./setup-authlock.sh [--app-dir PATH] [--public-ip IP] [--no-service]
#
#   --app-dir PATH    Where the AuthLock project source lives.
#                      Default: /opt/authlock/app if it exists and looks
#                      like the project (has settings.gradle), else the
#                      directory this script's parent folder lives in
#                      (so `./scripts/setup-authlock.sh` from a checked-out
#                      copy of the repo just works), else /opt/authlock/app.
#   --public-ip IP     Skip auto-discovery, use this address for both
#                      java.rmi.server.hostname and the TLS cert's SAN.
#                      Useful behind a NAT/port-forward setup where the
#                      metadata-service/echo-service discovery in this
#                      script would find the wrong address.
#   --no-service       Build and print instructions, but don't touch
#                      systemd — for a quick foreground test run instead of
#                      installing a persistent service.
#
# What this does, in order:
#   1. Ensures Java 17 is present (installs it if not — apt/dnf/yum).
#   2. Builds the server distribution: ./gradlew :authlock-server:installDist
#   3. Discovers this machine's public address (or uses --public-ip).
#   4. Writes /opt/authlock/authlock-server.env with JAVA_OPTS set to
#      -Djava.rmi.server.hostname=<addr> -Dauthlock.tls.extraSan=... — see
#      Architecture.md §3 / Security.md §7 for why both matter for a
#      genuinely remote client's RMI stub resolution AND TLS handshake.
#   5. Installs (if not already present) and (re)starts the
#      authlock-server systemd service.
#   6. Prints exactly what to copy to a remote client machine and the
#      command to run it.

set -euo pipefail

APP_DIR=""
PUBLIC_IP_OVERRIDE=""
INSTALL_SERVICE=1

while [ $# -gt 0 ]; do
    case "$1" in
        --app-dir) APP_DIR="$2"; shift 2 ;;
        --public-ip) PUBLIC_IP_OVERRIDE="$2"; shift 2 ;;
        --no-service) INSTALL_SERVICE=0; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

log() { echo "[setup-authlock.sh] $(date -u '+%Y-%m-%dT%H:%M:%SZ') $*"; }

# --- 0. Resolve APP_DIR --------------------------------------------------
if [ -z "$APP_DIR" ]; then
    if [ -f "/opt/authlock/app/settings.gradle" ]; then
        APP_DIR="/opt/authlock/app"
    else
        CANDIDATE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
        if [ -f "$CANDIDATE/settings.gradle" ]; then
            APP_DIR="$CANDIDATE"
        else
            APP_DIR="/opt/authlock/app"
        fi
    fi
fi
if [ ! -f "$APP_DIR/settings.gradle" ]; then
    echo "ERROR: $APP_DIR doesn't look like the AuthLock project (no settings.gradle found there)." >&2
    echo "Upload the project there first (e.g. scp -r \"AuthLock Project\" <user>@<host>:$APP_DIR), or pass --app-dir." >&2
    exit 1
fi
log "Using APP_DIR=$APP_DIR"

# --- 1. Java 17 -----------------------------------------------------------
ensure_java() {
    if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"17'; then
        log "Java 17 already present: $(java -version 2>&1 | head -1)"
        return
    fi
    log "Java 17 not found — installing..."
    if command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -y && apt-get install -y openjdk-17-jdk
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y java-17-openjdk-devel
    elif command -v yum >/dev/null 2>&1; then
        yum install -y java-17-openjdk-devel
    else
        echo "ERROR: no supported package manager found. Install a JDK 17 manually and re-run." >&2
        exit 1
    fi
    log "Java: $(java -version 2>&1 | head -1)"
}

# --- 2. Build --------------------------------------------------------------
build_server() {
    log "Building the server distribution (./gradlew :authlock-server:installDist)..."
    (cd "$APP_DIR" && chmod +x gradlew && ./gradlew :authlock-server:installDist -x test)
    local launcher="$APP_DIR/authlock-server/build/install/authlock-server/bin/authlock-server"
    if [ ! -x "$launcher" ]; then
        echo "ERROR: build did not produce the expected launcher at $launcher" >&2
        exit 1
    fi
    log "Build OK: $launcher"
}

# --- 3. Public address discovery (mirrors provision-vm.sh's logic — kept
# independent so this script is fully standalone-usable) -------------------
discover_public_address() {
    if [ -n "$PUBLIC_IP_OVERRIDE" ]; then
        PUBLIC_IP="$PUBLIC_IP_OVERRIDE"
        PUBLIC_DNS=""
        log "Using --public-ip override: $PUBLIC_IP"
        return
    fi
    if [ -f /opt/authlock/public_ip ] && [ -s /opt/authlock/public_ip ]; then
        PUBLIC_IP="$(cat /opt/authlock/public_ip)"
        PUBLIC_DNS="$(cat /opt/authlock/public_dns 2>/dev/null || true)"
        log "Using address discovered earlier by provision-vm.sh: ip=$PUBLIC_IP dns=${PUBLIC_DNS:-<none>}"
        return
    fi

    local token ip="" dns=""
    token="$(curl -s -m 2 -X PUT "http://169.254.169.254/latest/api/token" \
        -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)"
    if [ -n "$token" ]; then
        ip="$(curl -s -m 2 -H "X-aws-ec2-metadata-token: $token" \
            "http://169.254.169.254/latest/meta-data/public-ipv4" 2>/dev/null || true)"
        dns="$(curl -s -m 2 -H "X-aws-ec2-metadata-token: $token" \
            "http://169.254.169.254/latest/meta-data/public-hostname" 2>/dev/null || true)"
    fi
    if [ -z "$ip" ]; then
        ip="$(curl -s -m 3 https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]' || true)"
    fi
    if [ -z "$ip" ]; then
        ip="$(curl -s -m 3 https://ifconfig.me 2>/dev/null | tr -d '[:space:]' || true)"
    fi
    if [ -z "$ip" ]; then
        echo "ERROR: could not auto-discover a public IP. Pass one explicitly: --public-ip <address>" >&2
        exit 1
    fi
    PUBLIC_IP="$ip"
    PUBLIC_DNS="$dns"
    log "Discovered public address: ip=$PUBLIC_IP dns=${PUBLIC_DNS:-<none>}"
}

# --- 4. Environment file for the systemd unit (or for a manual foreground
# run — printed either way) -------------------------------------------------
write_env_file() {
    local san="ip:${PUBLIC_IP}"
    if [ -n "${PUBLIC_DNS:-}" ]; then
        san="${san},dns:${PUBLIC_DNS}"
    fi
    mkdir -p /opt/authlock
    cat > /opt/authlock/authlock-server.env <<EOF
# Generated by setup-authlock.sh — safe to regenerate, do not hand-edit.
JAVA_OPTS=-Djava.rmi.server.hostname=${PUBLIC_IP} -Dauthlock.tls.extraSan=${san}
EOF
    log "Wrote /opt/authlock/authlock-server.env (java.rmi.server.hostname=${PUBLIC_IP}, TLS SAN += ${san})"
    log "NOTE: the TLS SAN setting only takes effect the FIRST time the server generates its certificate. If certs/authlock-dev.p12 already exists under $APP_DIR from an earlier local/localhost run, delete it before (re)starting so it regenerates with this address included."
}

# --- 5. systemd service ------------------------------------------------------
install_and_start_service() {
    if [ "$INSTALL_SERVICE" -ne 1 ]; then
        log "--no-service given — skipping systemd; run the server directly with:"
        log "  JAVA_OPTS=\"\$(grep JAVA_OPTS /opt/authlock/authlock-server.env | cut -d= -f2-)\" \\"
        log "    $APP_DIR/authlock-server/build/install/authlock-server/bin/authlock-server"
        return
    fi
    if ! id authlock >/dev/null 2>&1; then
        log "Service account 'authlock' not found — creating it (normally provision-vm.sh does this)."
        useradd --system --create-home --home-dir /opt/authlock --shell /usr/sbin/nologin authlock
    fi
    # Always (re)write the unit so it matches THIS run's APP_DIR — not just
    # when absent. provision-vm.sh installs a unit pointing at its own
    # default (/opt/authlock/app) before the real source location is known;
    # if this script is later run with a different --app-dir (e.g. a source
    # tree under $HOME instead of /opt), a "write once" guard here would
    # leave that stale unit in place and the service would fail with
    # "Unable to locate executable" against the wrong path. Regenerating
    # unconditionally is cheap and keeps this idempotent either way.
    log "Writing systemd unit for APP_DIR=$APP_DIR (regenerated every run so it can't go stale)..."
    cat > /etc/systemd/system/authlock-server.service <<EOF
[Unit]
Description=AuthLock RMI Vault Server
After=network.target

[Service]
Type=simple
User=authlock
WorkingDirectory=${APP_DIR}
EnvironmentFile=-/opt/authlock/authlock-server.env
ExecStart=${APP_DIR}/authlock-server/build/install/authlock-server/bin/authlock-server
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload

    chown -R authlock:authlock "$APP_DIR" /opt/authlock/authlock-server.env 2>/dev/null || true

    log "Enabling and (re)starting authlock-server.service..."
    systemctl enable authlock-server >/dev/null
    systemctl restart authlock-server

    sleep 3
    if systemctl is-active --quiet authlock-server; then
        log "authlock-server is running. journalctl -u authlock-server -f to follow logs."
    else
        echo "ERROR: authlock-server failed to start. Check: journalctl -u authlock-server -n 50" >&2
        exit 1
    fi
}

print_client_instructions() {
    cat <<EOF

=====================================================================
 AuthLock server is set up and $( [ "$INSTALL_SERVICE" -eq 1 ] && echo "running" || echo "built (not started — --no-service given)" ).

 Public address : ${PUBLIC_IP} ${PUBLIC_DNS:+(also: $PUBLIC_DNS)}
 RMI registry   : ${PUBLIC_IP}:1099
 RMI object port: ${PUBLIC_IP}:5000

 To connect a remote Swing client, that machine needs its OWN copies of
 two files this server just generated — the AES-256-GCM shared key and
 the TLS certificate (Security.md §7: the same self-signed cert doubles
 as keystore AND truststore, so client and server must share the exact
 file, not each generate their own):

   scp <user>@${PUBLIC_IP}:${APP_DIR}/authlock-shared.key .
   scp -r <user>@${PUBLIC_IP}:${APP_DIR}/certs .

 Then, from the client machine, in its own copy of the project
 (with authlock-shared.key and certs/ placed in that project's root):

   ./gradlew :authlock-client:run -Dauthlock.server.host=${PUBLIC_IP}

 Server-side logs   : journalctl -u authlock-server -f
 Server-side files  : ${APP_DIR}/vault-storage/, ${APP_DIR}/audit.log
=====================================================================

EOF
}

main() {
    ensure_java
    build_server
    discover_public_address
    write_env_file
    install_and_start_service
    print_client_instructions
}

main
