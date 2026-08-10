# Kindle RSS

Self-hosted RSS/Atom reader that extracts readable article HTML and emails EPUB files to your Kindle. Plain server-rendered UI (no JavaScript required).

## Features

- Add feeds by RSS/Atom URL or homepage (autodiscovery via `link rel=alternate`)
- Scheduled refresh every 30 minutes, plus manual refresh
- Article extraction (Readability4J) with sanitized HTML caching
- Send-to-Kindle as EPUB 3 over SMTP
- Single-user login (`kindle`) with BCrypt password and optional remember-me

## Requirements

- Java 21 and Maven 3.9+ (or the included Maven Wrapper)
- PostgreSQL 16+ (17 recommended)
- SMTP credentials that Amazon Kindle will accept as a personal document sender

## Local configuration

1. Create a database and user, or use Docker Postgres.
2. Copy `.env.example` to `.env` and set at least:

| Variable | Purpose |
|---|---|
| `APP_PASSWORD` | Login password for user `kindle` |
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/kindle_rss` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | DB credentials |
| `KINDLE_EMAIL` | Your Send-to-Kindle address (`@kindle.com` / `@free.kindle.com`, etc.) |
| `MAIL_FROM` | From address Amazon has approved |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP |
| `REMEMBER_ME_KEY` | Secret for remember-me tokens |

3. Export variables (or use your shell dotenv tooling) and run:

```bash
./mvnw spring-boot:run
```

Open http://localhost:8080 and sign in as `kindle` with `APP_PASSWORD`.

### Tests

```bash
./mvnw test
```

Tests do not require PostgreSQL or Docker. They cover EPUB layout, HTML sanitization, SSRF address checks, and MVC/security smoke paths with mocked services.

## Using the app

1. **Add a feed** on the home page (direct feed URL or site homepage).
2. Open **Articles** / **Unread**, filter by feed, paginate.
3. Open an article to mark it read and view extracted content (images off by default).
4. **Send to Kindle** builds an EPUB and emails it; `sent_at` is recorded only after SMTP succeeds.

## Send-to-Kindle (Amazon)

Amazon only accepts documents from approved sender addresses:

1. In Amazon account settings, open **Content & Devices** → **Preferences** → **Personal Document Settings**.
2. Note your **Send-to-Kindle Email**.
3. Add your `MAIL_FROM` address under **Approved Personal Document E-mail List**.
4. Ensure your SMTP provider sends from that exact From address.

Without approval, messages are silently dropped by Amazon.

## DNS / TLS

Point an A/AAAA record for your `DOMAIN` at the VPS. Caddy obtains certificates automatically when ports 80/443 are reachable.

## Build / run with Docker

From the repo root (with a filled `.env`):

```bash
docker compose -f deploy/docker-compose.yml --env-file .env up -d --build
```

Services:

- `app` — Spring Boot (production profile)
- `postgres:17-alpine` — internal only (no published ports), persistent volume
- `caddy` — reverse proxy + TLS (`deploy/Caddyfile`)

Healthchecks are configured on Postgres and the app (`/actuator/health`).

### Behind an existing reverse proxy

If the host already terminates TLS (its own Caddy, nginx, Traefik), the bundled
`caddy` container cannot bind ports 80/443. Add the overlay, which parks that
container behind an unused profile and publishes the app on loopback instead:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.host-proxy.yml \
  --env-file .env up -d --build
```

Set `APP_HTTP_PORT` in `.env` if 8090 is taken, then point the host proxy at
`127.0.0.1:$APP_HTTP_PORT`. See `deploy/host-caddy-site.example` for a site block.
The app already runs with `server.forward-headers-strategy=framework`, so it
honors `X-Forwarded-Proto` and issues Secure cookies and https redirects.

## Deploy to a VPS

`deploy/deploy.sh` syncs the project over SSH and runs Compose remotely.

```bash
export VPS_HOST=203.0.113.10
export VPS_USER=root
export VPS_SSH_KEY_B64="$(base64 -w0 ~/.ssh/id_ed25519)"   # or VPS_SSH_KEY=/path/to/key
# optional: VPS_SSH_PORT=22 REMOTE_DIR=/opt/kindle-rss ENV_FILE=./.env
chmod +x deploy/deploy.sh
./deploy/deploy.sh
```

The SSH user needs Docker access (membership in the `docker` group, or root).
`REMOTE_DIR` must be writable by that user; use a path under `$HOME` when it is
not. Set `COMPOSE_OVERRIDE=deploy/docker-compose.host-proxy.yml` when the host
runs its own proxy. If the server holds the only copy of `.env`, point
`ENV_FILE` at a nonexistent path so the sync does not overwrite it.

Never commit `.env`, private keys, or `VPS_SSH_KEY_B64`.

## Backup / restore

```bash
chmod +x deploy/backup.sh deploy/restore.sh
./deploy/backup.sh              # writes deploy/backups/*.sql.gz via pg_dump
./deploy/restore.sh deploy/backups/kindle_rss_YYYYMMDD.sql.gz
```

## Security notes

- CSRF protection stays enabled; forms include tokens.
- Feed/article HTML is sanitized with jsoup Safelist before `th:utext`.
- Outbound fetches allow only `http`/`https`, resolve DNS, and reject loopback/private/link-local/multicast/CGNAT/ULA addresses; response size and timeouts are capped.
- Redirect targets from form posts are restricted to same-app relative paths.
- Session cookies are not marked Secure in the default profile (local HTTP). The `production` profile sets Secure cookies; use a TLS-terminating proxy with forwarded headers.

## Stack

- Spring Boot 3.5.3, Java 21, Maven Wrapper
- Thymeleaf, Spring Security, JDBC, Mail, Flyway, Actuator
- ROME 2.1.0, Readability4J 1.0.8, jsoup 1.22.2 (explicit override; Readability4J otherwise pulls 1.11.2)
