#!/usr/bin/env bash
set -euo pipefail

# Restore a gzipped pg_dump created by backup.sh
# Usage: ./restore.sh path/to/backup.sql.gz

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKUP="${1:?Usage: $0 backup.sql.gz}"

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

echo "Restoring $BACKUP into $DB"
gzip -dc "$BACKUP" | docker compose -f "$ROOT/docker-compose.yml" exec -T postgres \
  psql -U "$USER" -d "$DB"

echo "Done."
