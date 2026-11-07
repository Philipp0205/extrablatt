#!/usr/bin/env bash
set -euo pipefail

# Restore a backup created by backup.sh into the compose stack's database.
# Usage: ./restore.sh [--yes] path/to/backup.sql.gz[.age|.gpg]
#
# This replaces the current contents of the database. It asks for
# confirmation first; pass --yes (or -y) to skip that in scripts.
# The restore runs in a single transaction and stops at the first error, so
# a broken dump leaves the database as it was.
#
# Settings (environment first, then the stack's .env; never sourced):
#   POSTGRES_DB / POSTGRES_USER   database and role to restore into
#   BACKUP_AGE_IDENTITY           age identity file for *.age backups
#                                 (*.gpg backups use the gpg keyring)

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/env-file.sh
. "$ROOT/env-file.sh"

ASSUME_YES=0
BACKUP=""
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=1 ;;
    -h|--help)
      sed -n '4,15p' "$0"
      exit 0
      ;;
    -*) echo "Unknown option: $arg" >&2; exit 2 ;;
    *)
      if [[ -n "$BACKUP" ]]; then
        echo "Usage: $0 [--yes] backup.sql.gz" >&2
        exit 2
      fi
      BACKUP="$arg"
      ;;
  esac
done
if [[ -z "$BACKUP" ]]; then
  echo "Usage: $0 [--yes] backup.sql.gz" >&2
  exit 2
fi
if [[ ! -s "$BACKUP" ]]; then
  echo "Backup $BACKUP does not exist or is empty" >&2
  exit 1
fi

ENV_FILE="$(find_env_file "$ROOT")"
load_env_vars "$ENV_FILE" POSTGRES_DB POSTGRES_USER BACKUP_AGE_IDENTITY

DB_NAME="${POSTGRES_DB:-kindle_rss}"
DB_USER="${POSTGRES_USER:-kindle}"

DECRYPT=(cat)
case "$BACKUP" in
  *.age)
    : "${BACKUP_AGE_IDENTITY:?set BACKUP_AGE_IDENTITY to the age identity file for $BACKUP}"
    DECRYPT=(age -d -i "$BACKUP_AGE_IDENTITY")
    ;;
  *.gpg)
    DECRYPT=(gpg --batch --quiet --decrypt)
    ;;
esac

if [[ "$ASSUME_YES" -ne 1 ]]; then
  if [[ ! -t 0 ]]; then
    echo "Refusing to restore without confirmation; rerun with --yes." >&2
    exit 1
  fi
  echo "This replaces everything in database '$DB_NAME' with $BACKUP."
  read -r -p "Type the database name to continue: " answer
  if [[ "$answer" != "$DB_NAME" ]]; then
    echo "Aborted; nothing was changed."
    exit 1
  fi
fi

echo "Restoring $BACKUP into $DB_NAME"
"${DECRYPT[@]}" < "$BACKUP" | gzip -dc \
  | docker compose -f "$ROOT/docker-compose.yml" exec -T postgres \
      psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 --single-transaction --quiet

echo "Done."
