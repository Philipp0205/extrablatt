#!/usr/bin/env bash
set -euo pipefail

# Backup Postgres from the running compose stack.
# Usage: ./backup.sh [output-dir]
#
# Settings (environment first, then the stack's .env; see .env.example):
#   POSTGRES_DB / POSTGRES_USER   database and role to dump (as in compose)
#   BACKUP_KEEP                   keep this many newest backups, delete older
#                                 ones (default 14; 0 keeps everything)
#   BACKUP_AGE_RECIPIENT          encrypt with age to this public key
#   BACKUP_GPG_RECIPIENT          or encrypt with gpg to this key id / e-mail
# Only these keys are read from .env; the file is never sourced.

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/env-file.sh
. "$ROOT/env-file.sh"

OUT_DIR="${1:-$ROOT/backups}"
ENV_FILE="$(find_env_file "$ROOT")"
load_env_vars "$ENV_FILE" POSTGRES_DB POSTGRES_USER BACKUP_KEEP BACKUP_AGE_RECIPIENT BACKUP_GPG_RECIPIENT

DB_NAME="${POSTGRES_DB:-kindle_rss}"
DB_USER="${POSTGRES_USER:-kindle}"
KEEP="${BACKUP_KEEP:-14}"
AGE_RECIPIENT="${BACKUP_AGE_RECIPIENT:-}"
GPG_RECIPIENT="${BACKUP_GPG_RECIPIENT:-}"

if ! [[ "$KEEP" =~ ^[0-9]+$ ]]; then
  echo "BACKUP_KEEP must be a whole number (got '$KEEP')" >&2
  exit 1
fi
if [[ -n "$AGE_RECIPIENT" && -n "$GPG_RECIPIENT" ]]; then
  echo "Set only one of BACKUP_AGE_RECIPIENT and BACKUP_GPG_RECIPIENT" >&2
  exit 1
fi

EXT="sql.gz"
ENCRYPT=(cat)
if [[ -n "$AGE_RECIPIENT" ]]; then
  command -v age >/dev/null 2>&1 || { echo "age is not installed but BACKUP_AGE_RECIPIENT is set" >&2; exit 1; }
  EXT="sql.gz.age"
  ENCRYPT=(age -r "$AGE_RECIPIENT")
elif [[ -n "$GPG_RECIPIENT" ]]; then
  command -v gpg >/dev/null 2>&1 || { echo "gpg is not installed but BACKUP_GPG_RECIPIENT is set" >&2; exit 1; }
  EXT="sql.gz.gpg"
  ENCRYPT=(gpg --batch --yes --trust-model always --encrypt --recipient "$GPG_RECIPIENT" --output -)
fi

# Dumps hold every account's data: readable by the owner only.
umask 077
mkdir -p "$OUT_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$OUT_DIR/kindle_rss_${STAMP}.${EXT}"
PARTIAL="$FILE.partial"
trap 'rm -f "$PARTIAL"' EXIT

echo "Writing backup to $FILE"
docker compose -f "$ROOT/docker-compose.yml" exec -T postgres \
  pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists \
  | gzip -c \
  | "${ENCRYPT[@]}" > "$PARTIAL"

if [[ ! -s "$PARTIAL" ]]; then
  echo "Backup came out empty; keeping the existing ones untouched." >&2
  exit 1
fi
# Renamed only once complete, so a failed run never counts towards rotation.
mv "$PARTIAL" "$FILE"

if [[ "$KEEP" -gt 0 ]]; then
  # Timestamps sort by name; everything past the newest $KEEP goes.
  mapfile -t OLD < <(find "$OUT_DIR" -maxdepth 1 -type f \
    \( -name 'kindle_rss_*.sql.gz' -o -name 'kindle_rss_*.sql.gz.age' -o -name 'kindle_rss_*.sql.gz.gpg' \) \
    | sort -r | tail -n "+$((KEEP + 1))")
  for old in "${OLD[@]}"; do
    echo "Removing old backup $old"
    rm -f -- "$old"
  done
fi

echo "Done."
