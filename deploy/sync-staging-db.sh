#!/usr/bin/env bash
# Dump Extrablatt production Postgres and restore it onto the staging
# environment's own database. Staging must keep a separate instance:
# schema migrations and feed refresh on staging would otherwise hit prod.
#
# Requires: railway CLI (logged in or RAILWAY_TOKEN / RAILWAY_API_TOKEN),
#           pg_dump, pg_restore
set -euo pipefail

PROJECT_ID="${RAILWAY_PROJECT_ID:-5acb693d-239d-4354-9146-ded20c78a027}"
DUMP="${DUMP_PATH:-$(mktemp /tmp/extrablatt-prod.XXXXXX.dump)}"
cleanup() {
  rm -f "$DUMP"
}
trap cleanup EXIT

railway_ssh() {
  local environment="$1"
  shift
  railway ssh \
    --project "$PROJECT_ID" \
    --environment "$environment" \
    --service Postgres \
    -- "$@"
}

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
