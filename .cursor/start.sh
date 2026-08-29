#!/usr/bin/env bash
# Per-boot service reconciliation for the Extrablatt Cloud Agent environment.
# Brings up PostgreSQL and Mailpit, ensures the app database/role exist, and
# returns once both are ready. Safe to run repeatedly.
set -euo pipefail

echo "==> Ensuring PostgreSQL is running"
if ! sudo pg_lsclusters -h 2>/dev/null | grep -q online; then
  sudo pg_ctlcluster 16 main start || true
fi
# Wait for the server socket to accept connections.
for _ in $(seq 1 30); do
  if sudo -u postgres pg_isready -q 2>/dev/null; then break; fi
  sleep 1
done

echo "==> Ensuring app database and role exist"
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='kindle'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE USER kindle WITH PASSWORD 'kindle';"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='kindle_rss'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE kindle_rss OWNER kindle;"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE kindle_rss TO kindle;" >/dev/null

echo "==> Ensuring Mailpit (local SMTP sink) is running"
# SMTP on :1025, web UI on :8025. Skip if a Mailpit process is already up.
if ! pgrep -x mailpit >/dev/null 2>&1; then
  nohup mailpit --smtp 0.0.0.0:1025 --listen 0.0.0.0:8025 \
    >/tmp/mailpit.log 2>&1 &
fi
for _ in $(seq 1 30); do
  if curl -sS -m 2 -o /dev/null http://localhost:8025/ 2>/dev/null; then break; fi
  sleep 1
done

echo "==> start.sh complete: PostgreSQL + Mailpit ready"
