#!/usr/bin/env bash
# Dump Extrablatt production Postgres and restore it onto the staging
# environment's own database. Staging must keep a separate instance:
# schema migrations and feed refresh on staging would otherwise hit prod.
#
# Requires: railway CLI (logged in or RAILWAY_TOKEN / RAILWAY_API_TOKEN),
#           pg_dump, pg_restore. Registers an SSH key with Railway when
#           the environment has none (GitHub Actions).
set -euo pipefail

PROJECT_ID="${RAILWAY_PROJECT_ID:-5acb693d-239d-4354-9146-ded20c78a027}"
DUMP="${DUMP_PATH:-$(mktemp /tmp/extrablatt-prod.XXXXXX.dump)}"
SSH_KEY="${SSH_KEY_PATH:-$HOME/.ssh/id_ed25519}"
EPHEMERAL_KEY=0
REGISTERED_FP=""

if ! command -v railway >/dev/null 2>&1; then
  echo "railway CLI not on PATH" >&2
  exit 1
fi
if ! command -v pg_dump >/dev/null 2>&1; then
  echo "pg_dump is required on PATH" >&2
  exit 1
fi

cleanup() {
  rm -f "$DUMP"
  if [[ -n "$REGISTERED_FP" ]]; then
    railway ssh keys remove "$REGISTERED_FP" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

ensure_railway_ssh() {
  mkdir -p "$HOME/.ssh"
  chmod 700 "$HOME/.ssh"
  if [[ ! -f "$SSH_KEY" ]]; then
    ssh-keygen -t ed25519 -N "" -f "$SSH_KEY" -C "extrablatt-staging-sync" >/dev/null
    EPHEMERAL_KEY=1
  fi
  if [[ ! -f "$HOME/.ssh/config" ]] || ! grep -q "StrictHostKeyChecking accept-new" "$HOME/.ssh/config"; then
    cat >> "$HOME/.ssh/config" << 'EOF'
Host *
  StrictHostKeyChecking accept-new
  UserKnownHostsFile ~/.ssh/known_hosts
  IdentityFile ~/.ssh/id_ed25519
  IdentitiesOnly yes
EOF
    chmod 600 "$HOME/.ssh/config"
  fi
  railway ssh keys add -k "${SSH_KEY}.pub" -n "extrablatt-staging-sync-$$" >/dev/null || true
  if [[ "$EPHEMERAL_KEY" -eq 1 ]]; then
    REGISTERED_FP="$(ssh-keygen -lf "${SSH_KEY}.pub" | awk '{print $2}')"
  fi
}

railway_ssh() {
  local environment="$1"
  shift
  railway ssh \
    --project "$PROJECT_ID" \
    --environment "$environment" \
    --service Postgres \
    -- "$@"
}

ensure_railway_ssh

echo "Dumping production Postgres (custom format, no owner/ACL)…"
railway_ssh production -- \
  pg_dump -Fc --no-owner --no-acl -d railway > "$DUMP"

if [[ ! -s "$DUMP" ]]; then
  echo "Dump is empty; aborting so staging is left untouched." >&2
  exit 1
fi

echo "Restoring onto staging (drop existing objects, then reload)…"
# Terminate other sessions so DROP is not blocked by the running app.
railway_ssh staging -- \
  psql -d railway -v ON_ERROR_STOP=1 -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid();" \
  >/dev/null || true

railway_ssh staging -- \
  pg_restore --clean --if-exists --no-owner --no-acl --dbname=railway < "$DUMP"

echo "Staging database now matches this production dump."
