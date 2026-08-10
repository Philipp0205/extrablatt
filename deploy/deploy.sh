#!/usr/bin/env bash
set -euo pipefail

# Deploy Kindle RSS to a VPS over SSH.
# Required env:
#   VPS_HOST, VPS_USER
#   VPS_SSH_KEY_B64  (base64-encoded private key) OR VPS_SSH_KEY (path)
# Optional:
#   VPS_SSH_PORT (default 22; VPS_PORT is accepted for compatibility)
#   REMOTE_DIR (default /opt/kindle-rss)
#   ENV_FILE (default ./.env) — copied to the server (never commit secrets)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VPS_HOST="${VPS_HOST:?VPS_HOST is required}"
VPS_USER="${VPS_USER:?VPS_USER is required}"
VPS_SSH_PORT="${VPS_SSH_PORT:-${VPS_PORT:-22}}"
REMOTE_DIR="${REMOTE_DIR:-/opt/kindle-rss}"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"

TMP_KEY="$(mktemp)"
cleanup() {
  rm -f "$TMP_KEY"
}
trap cleanup EXIT

if [[ -n "${VPS_SSH_KEY_B64:-}" ]]; then
  echo "$VPS_SSH_KEY_B64" | base64 -d > "$TMP_KEY"
  chmod 600 "$TMP_KEY"
  SSH_KEY="$TMP_KEY"
elif [[ -n "${VPS_SSH_KEY:-}" ]]; then
  SSH_KEY="$VPS_SSH_KEY"
else
  echo "Set VPS_SSH_KEY_B64 or VPS_SSH_KEY" >&2
  exit 1
fi

SSH=(ssh -i "$SSH_KEY" -p "$VPS_SSH_PORT" -o StrictHostKeyChecking=accept-new "${VPS_USER}@${VPS_HOST}")
RSYNC=(rsync -az --delete -e "ssh -i $SSH_KEY -p $VPS_SSH_PORT -o StrictHostKeyChecking=accept-new")

echo "Ensuring remote directory $REMOTE_DIR"
"${SSH[@]}" "mkdir -p '$REMOTE_DIR'"

echo "Syncing project (excluding secrets, git, build output)"
"${RSYNC[@]}" \
  --exclude '.git/' \
  --exclude '.env' \
  --exclude 'target/' \
  --exclude '.idea/' \
  --exclude 'deploy/backups/' \
  "$ROOT/" "${VPS_USER}@${VPS_HOST}:${REMOTE_DIR}/"

if [[ -f "$ENV_FILE" ]]; then
  echo "Copying env file to remote .env"
  scp -i "$SSH_KEY" -P "$VPS_SSH_PORT" -o StrictHostKeyChecking=accept-new \
    "$ENV_FILE" "${VPS_USER}@${VPS_HOST}:${REMOTE_DIR}/.env"
else
  echo "WARNING: $ENV_FILE not found; remote must already have ${REMOTE_DIR}/.env" >&2
fi

echo "Building and starting stack"
"${SSH[@]}" "cd '$REMOTE_DIR' && docker compose -f deploy/docker-compose.yml --env-file .env up -d --build"

echo "Deploy complete."
