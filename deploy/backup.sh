#!/usr/bin/env bash
set -euo pipefail

# Backup Postgres from the running compose stack.
# Usage: ./backup.sh [output-dir]

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${1:-$ROOT/backups}"
mkdir -p "$OUT_DIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$OUT_DIR/kindle_rss_${STAMP}.sql.gz"

cd "$ROOT"

if [[ -f "$ROOT/../.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/../.env"
  set +a
elif [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

DB="${POSTGRES_DB:-kindle_rss}"
USER="${POSTGRES_USER:-kindle}"

echo "Writing backup to $FILE"
docker compose -f "$ROOT/docker-compose.yml" exec -T postgres \
  pg_dump -U "$USER" -d "$DB" --clean --if-exists \
  | gzip -c > "$FILE"

echo "Done."
