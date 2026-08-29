#!/usr/bin/env bash
# Idempotent repository bootstrap for the Extrablatt Cloud Agent environment.
# Installs the system services the app needs at runtime (PostgreSQL for the
# datastore, Mailpit as a local SMTP sink so registration/verification e-mail
# works without a real provider) and warms the Maven dependency cache.
set -euo pipefail

echo "==> Installing system packages (idempotent)"
if ! command -v pg_ctlcluster >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq postgresql postgresql-contrib
else
  echo "PostgreSQL already installed"
fi

if ! command -v mailpit >/dev/null 2>&1; then
  echo "==> Installing Mailpit"
  tmp="$(mktemp -d)"
  curl -sSL -o "$tmp/mailpit.tar.gz" \
    "https://github.com/axllent/mailpit/releases/latest/download/mailpit-linux-amd64.tar.gz"
  tar xzf "$tmp/mailpit.tar.gz" -C "$tmp"
  sudo mv "$tmp/mailpit" /usr/local/bin/mailpit
  sudo chmod +x /usr/local/bin/mailpit
  rm -rf "$tmp"
else
  echo "Mailpit already installed"
fi

echo "==> Warming Maven dependency cache and compiling"
cd "$(dirname "$0")/.."
./mvnw -B -q -DskipTests package

echo "==> install.sh complete"
