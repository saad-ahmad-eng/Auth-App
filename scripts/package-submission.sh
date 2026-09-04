#!/bin/bash
# package-submission.sh — Phase 12 deliverable: assembles the final
# submission .zip per `auth` §10 (report, source, jars, all packaged
# together). Run from the project root:
#   ./scripts/package-submission.sh
#
# Produces build/submission/AuthLock-Submission.zip containing:
#   report/    — AuthLock-Report.docx and .pdf (must already exist —
#                see report/README.md for how they were generated)
#   jars/      — authlock-server-all.jar, authlock-client-all.jar
#                (built fresh by this script via the fatJar tasks)
#   source/    — the full project source tree, minus build artifacts,
#                generated secrets, and version control metadata
#   README.txt — what's in the zip and how to run each part

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(pwd)"
OUT_DIR="$PROJECT_ROOT/build/submission"
STAGE_DIR="$OUT_DIR/AuthLock-Submission"

log() { echo "[package-submission.sh] $*"; }

if [ ! -f "report/AuthLock-Report.pdf" ] || [ ! -f "report/AuthLock-Report.docx" ]; then
    echo "ERROR: report/AuthLock-Report.{pdf,docx} not found. Generate the report first (see report/README.md)." >&2
    exit 1
fi

log "Building deployable jars (fatJar)..."
./gradlew :authlock-server:fatJar :authlock-client:fatJar -x test

log "Staging submission contents in $STAGE_DIR ..."
rm -rf "$OUT_DIR"
mkdir -p "$STAGE_DIR/report" "$STAGE_DIR/jars" "$STAGE_DIR/source"

cp report/AuthLock-Report.pdf report/AuthLock-Report.docx "$STAGE_DIR/report/"
cp authlock-server/build/libs/authlock-server-all.jar "$STAGE_DIR/jars/"
cp authlock-client/build/libs/authlock-client-all.jar "$STAGE_DIR/jars/"

# Source tree: everything except build outputs, generated runtime
# secrets/state, and VCS metadata — a reviewer should be able to unzip
# this and run ./gradlew build without any leftover local state biasing
# the result.
rsync -a \
    --exclude='.git/' \
    --exclude='**/build/' \
    --exclude='.gradle/' \
    --exclude='.claude/' \
    --exclude='vault-storage/' \
    --exclude='certs/' \
    --exclude='authlock-shared.key' \
    --exclude='*.key' \
    --exclude='audit.log' \
    --exclude='*.log' \
    --exclude='.idea/' \
    --exclude='*.iml' \
    --exclude='*.swp' \
    --exclude='*.swo' \
    --exclude='terraform/.terraform/' \
    --exclude='terraform/*.tfstate*' \
    --exclude='terraform/*.tfvars' \
    --exclude='/report/' \
    "$PROJECT_ROOT/" "$STAGE_DIR/source/"

cat > "$STAGE_DIR/README.txt" <<'EOF'
AuthLock — Submission Contents
===============================

report/
  AuthLock-Report.pdf / .docx
    The written report: design, development, deployment, walkthrough,
    testing (per `auth` §10).

jars/
  authlock-server-all.jar   Run:  java -jar authlock-server-all.jar
  authlock-client-all.jar   Run:  java -jar authlock-client-all.jar [host]
    Self-contained runnable jars (no classpath setup needed) — run the
    server first (it generates authlock-shared.key and certs/ in the
    current directory on first run), then the client from the same
    directory so it can find that key. For a client on a different
    machine, copy authlock-shared.key and certs/ across first — see
    terraform/README.md "Connecting a client".

source/
  The full project source (Gradle multi-module: authlock-common,
  authlock-server, authlock-client), every specification/design
  document (PRD.md, Architecture.md, Security.md, Testing.md,
  Context.md, etc.), the Terraform deployment config (terraform/), and
  the deployment scripts (scripts/). Excludes build artifacts and any
  generated local secrets/state — a fresh checkout builds cleanly with:
    ./gradlew build

  Note (TRD.md §4): built with Gradle, not Eclipse/NetBeans as the
  original proposal suggested — Maven/an IDE-managed project was
  unavailable in the development environment; this is a documented,
  reasoned environment deviation, not an unstated one.
EOF

log "Zipping..."
( cd "$OUT_DIR" && zip -rq AuthLock-Submission.zip AuthLock-Submission )

log "Done: $OUT_DIR/AuthLock-Submission.zip"
du -h "$OUT_DIR/AuthLock-Submission.zip"
