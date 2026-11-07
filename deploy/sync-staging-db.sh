#!/usr/bin/env bash
# Copy Extrablatt production Postgres onto the staging environment's own
# database, with personal data scrubbed on the way in. Staging must keep a
# separate instance: schema migrations and feed refresh on staging would
# otherwise hit prod.
#
# The production dump, a keep list and deploy/staging-scrub.sql are streamed
# into ONE psql transaction on staging. If the dump is broken or the scrub's
# final check fails, nothing is committed and staging keeps what it had.
#
# Requires: railway CLI, ssh, ssh-keygen, ssh-agent. Authenticate with
# `railway login` or RAILWAY_API_TOKEN (an account or workspace token; the CLI
# refuses to register SSH keys with a project RAILWAY_TOKEN).
#
# Settings (all optional):
#   RAILWAY_PROJECT_ID     Railway project (defaults to the hosted Extrablatt one)
#   RAILWAY_APP_SERVICE    app service whose SMTP settings are compared
#                          (default kindle-rss-app)
#   RAILWAY_DB_SERVICE     Postgres service (default Postgres)
#   STAGING_KEEP_EMAILS    comma or space separated accounts left unscrubbed so
#                          testers and admins can still sign in on staging
#   ALLOW_SHARED_SMTP=1    sync even though staging sends with the production
#                          SMTP credentials
#   SSH_KEY_PATH           private key already registered with Railway; without
#                          it a throwaway key is registered and removed again
#   RAILWAY_SSH_KNOWN_HOSTS  known_hosts file with the relay host key; when set
#                          the key is checked strictly instead of trust on first use
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SCRUB_SQL="$ROOT/staging-scrub.sql"
PROJECT_ID="${RAILWAY_PROJECT_ID:-5acb693d-239d-4354-9146-ded20c78a027}"
APP_SERVICE="${RAILWAY_APP_SERVICE:-kindle-rss-app}"
DB_SERVICE="${RAILWAY_DB_SERVICE:-Postgres}"
DUMP_COMPLETE_MARKER="-- PostgreSQL database dump complete"

for tool in railway ssh ssh-keygen ssh-agent sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required on PATH" >&2
    exit 1
  fi
done
if [[ ! -s "$SCRUB_SQL" ]]; then
  echo "Missing $SCRUB_SQL" >&2
  exit 1
fi

# Validated here so the addresses can go into SQL as plain literals.
KEEP_EMAILS=()
read -r -a keep_words <<< "$(tr ',\n\t' '   ' <<< "${STAGING_KEEP_EMAILS:-}")"
for email in "${keep_words[@]}"; do
  if [[ ! "$email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
    echo "STAGING_KEEP_EMAILS contains something that is not an e-mail address" >&2
    exit 1
  fi
  KEEP_EMAILS+=("$email")
done

umask 077
WORK="$(mktemp -d "${TMPDIR:-/tmp}/extrablatt-sync.XXXXXX")"
REGISTERED_NAME=""
AGENT_STARTED=0

cleanup() {
  if [[ -n "$REGISTERED_NAME" ]]; then
    if ! railway ssh keys remove "$REGISTERED_NAME" >/dev/null 2>&1; then
      echo "Could not remove the temporary Railway SSH key '$REGISTERED_NAME'; remove it by hand." >&2
    fi
  fi
  if [[ "$AGENT_STARTED" -eq 1 ]]; then
    ssh-agent -k >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# --- SMTP preflight -----------------------------------------------------------
# Staging sending with production's SMTP account means real mail from a test
# build. Values stay in memory and are only compared as hashes, never printed.
# The token needs access to both environments for the SSH steps anyway, so a
# failed read stops the sync instead of skipping the check.
service_vars() {
  railway variable list --project "$PROJECT_ID" --environment "$1" \
    --service "$APP_SERVICE" --kv 2>/dev/null
}

var_hash() {
  grep -E "^($2)=" <<< "$1" | sort | sha256sum | cut -d' ' -f1
}

smtp_check_failed() {
  if [[ "${ALLOW_SHARED_SMTP:-0}" == "1" ]]; then
    echo "Warning: $1 Continuing because ALLOW_SHARED_SMTP=1." >&2
    return
  fi
  echo "$1" >&2
  echo "Point staging at its own SMTP account or a mail sink, or set ALLOW_SHARED_SMTP=1." >&2
  exit 1
}

SMTP_KEYS='SMTP_HOST|SMTP_USERNAME|SMTP_PASSWORD'
if ! prod_vars="$(service_vars production)" || ! staging_vars="$(service_vars staging)"; then
  smtp_check_failed "Could not read the $APP_SERVICE variables of both environments to compare SMTP settings."
elif grep -q '^# SMTP_[A-Z]* is sealed' <<< "$prod_vars"$'\n'"$staging_vars"; then
  smtp_check_failed "SMTP variables are sealed, so staging and production cannot be compared."
elif ! grep -q '^SMTP_PASSWORD=.' <<< "$staging_vars"; then
  : # no SMTP password on staging, so it cannot send through an authenticated account
elif [[ "$(var_hash "$prod_vars" "$SMTP_KEYS")" == "$(var_hash "$staging_vars" "$SMTP_KEYS")" ]]; then
  smtp_check_failed "Staging uses the same SMTP credentials as production."
fi
if [[ -n "${staging_vars:-}" ]]; then
  if ! grep -q '^REMEMBER_ME_KEY=.' <<< "$staging_vars" \
      || [[ "$(var_hash "${prod_vars:-}" REMEMBER_ME_KEY)" == "$(var_hash "$staging_vars" REMEMBER_ME_KEY)" ]]; then
    echo "Warning: staging should have its own REMEMBER_ME_KEY (unset or same as production)." >&2
  fi
fi
unset prod_vars staging_vars

# --- SSH ----------------------------------------------------------------------
# railway ssh runs the system ssh. A wrapper first on PATH hands it a private
# config, so nothing in ~/.ssh is read or changed.
REAL_SSH="$(command -v ssh)"
mkdir -p "$WORK/bin"
if [[ -n "${RAILWAY_SSH_KNOWN_HOSTS:-}" ]]; then
  cp "$RAILWAY_SSH_KNOWN_HOSTS" "$WORK/known_hosts"
  HOST_KEY_CHECKING=yes
else
  : > "$WORK/known_hosts"
  HOST_KEY_CHECKING=accept-new
fi
cat > "$WORK/ssh_config" << EOF
Host ssh.railway.com ssh.railway-staging.com ssh.railway-develop.com
  UserKnownHostsFile "$WORK/known_hosts"
  StrictHostKeyChecking $HOST_KEY_CHECKING
  IdentitiesOnly yes
  BatchMode yes
EOF
cat > "$WORK/bin/ssh" << EOF
#!/usr/bin/env bash
exec "$REAL_SSH" -F "$WORK/ssh_config" "\$@"
EOF
chmod 700 "$WORK/bin/ssh"
export PATH="$WORK/bin:$PATH"

if [[ -n "${SSH_KEY_PATH:-}" ]]; then
  IDENTITY="$SSH_KEY_PATH"
else
  if [[ -n "${RAILWAY_TOKEN:-}" ]]; then
    echo "The Railway CLI cannot register SSH keys with a project RAILWAY_TOKEN." >&2
    echo "Use RAILWAY_API_TOKEN (account or workspace token), or pass SSH_KEY_PATH." >&2
    exit 1
  fi
  IDENTITY="$WORK/id_ed25519"
  name="extrablatt-staging-sync-${GITHUB_RUN_ID:-$$}"
  ssh-keygen -q -t ed25519 -N "" -f "$IDENTITY" -C "$name" >/dev/null
  # The CLI registers keys it finds in the agent or ~/.ssh. A private agent
  # holding only this key keeps it out of ~/.ssh.
  eval "$(ssh-agent -s)" >/dev/null
  AGENT_STARTED=1
  ssh-add -q "$IDENTITY" 2>/dev/null
  # `keys add --key` matches part of the fingerprint; `keys remove` takes the
  # key's name, so cleanup does not depend on how the API formats fingerprints.
  fp="$(ssh-keygen -lf "$IDENTITY.pub" | awk '{print $2}')"
  railway ssh keys add --key "${fp#SHA256:}" --name "$name" >/dev/null
  REGISTERED_NAME="$name"
fi

railway_ssh() {
  local environment="$1"
  shift
  railway ssh \
    --project "$PROJECT_ID" \
    --environment "$environment" \
    --service "$DB_SERVICE" \
    --identity-file "$IDENTITY" \
    -- "$@"
}

# --- Dump ---------------------------------------------------------------------
# Kept locally (in the private temp dir) until it is known to be complete, so
# a dump cut off halfway is never loaded.
DUMP="$WORK/production.sql"
echo "Dumping production Postgres (plain SQL, no owner/ACL)..."
railway_ssh production \
  pg_dump --no-owner --no-acl --clean --if-exists -d railway > "$DUMP"

# pg_dump runs inside the production Postgres container, so its output matches
# that server's version. If staging runs an older major version the load fails
# on unknown settings and rolls back; keep both services on the same image.
if ! grep -qF -- "$DUMP_COMPLETE_MARKER" "$DUMP"; then
  echo "Production dump is empty or incomplete; staging is left untouched." >&2
  exit 1
fi

# --- Restore + scrub, one transaction -------------------------------------------
echo "Restoring onto staging and scrubbing personal data (one transaction)..."
# Terminate other sessions so DROP is not blocked by the running app.
railway_ssh staging \
  psql -d railway -v ON_ERROR_STOP=1 -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid();" \
  >/dev/null || true

{
  echo "CREATE TEMP TABLE staging_keep_emails (email TEXT PRIMARY KEY) ON COMMIT DROP;"
  for email in "${KEEP_EMAILS[@]}"; do
    echo "INSERT INTO pg_temp.staging_keep_emails VALUES ('$email');"
  done
  cat "$DUMP"
  cat "$SCRUB_SQL"
} | railway_ssh staging \
  psql -d railway -v ON_ERROR_STOP=1 --single-transaction --quiet -f - >/dev/null

echo "Staging database now holds a scrubbed copy of production (${#KEEP_EMAILS[@]} account(s) kept)."
