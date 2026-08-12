# Kindle RSS

Multi-user RSS/Atom reader that extracts readable article HTML and emails EPUB files to each user's Kindle. Plain server-rendered UI that stays usable without JavaScript. Runs as one always-on service (single VPS or a managed platform such as Railway).

## Features

- Email + password accounts with e-mail verification and password reset
- Per-user feeds and articles — every account has its own, isolated subscriptions
- Add feeds by RSS/Atom URL or homepage (autodiscovery via `link rel=alternate`)
- Optional quick-start feed suggestions and categories for organizing subscriptions
- Feeds refresh while you read them, on a 30-minute schedule and on demand,
  asking each feed for more than the handful of entries it publishes by default
- Article extraction (Readability4J) with sanitized HTML caching, and Hacker News
  items read as their submitted text plus the discussion
- Page-at-a-time reading sized to the device screen, instead of scrolling
- Send-to-Kindle as EPUB 3 through one shared, provider-verified sender
- Per-account limits and IP-based rate limiting on auth endpoints

## Requirements

- Java 21 and Maven 3.9+ (or the included Maven Wrapper)
- PostgreSQL 16+ (17 recommended)
- A transactional e-mail provider with SMTP and a verified sending domain
  (defaults target [Resend](https://resend.com)); the same sender delivers
  account e-mail and Kindle documents

## Local configuration

1. Create a database and user, or use Docker Postgres.
2. Copy `.env.example` to `.env` and set at least:

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/kindle_rss` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | DB credentials |
| `APP_PUBLIC_URL` | Base URL used in verification / reset e-mails (e.g. `http://localhost:8080`) |
| `MAIL_FROM` | Shared sender on your verified domain (`noreply@yourdomain.com`) |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP (Resend: host `smtp.resend.com`, username `resend`, password = API key) |
| `REMEMBER_ME_KEY` | Secret for remember-me tokens |
| `ADMIN_EMAILS` | Comma-separated account e-mails allowed to view telemetry and manage per-user send limits |

3. Export variables (or use your shell dotenv tooling) and run:

```bash
./mvnw spring-boot:run
```

Open http://localhost:8080, create an account, confirm your e-mail, then add your
Kindle address under **Settings**.

## Accounts and data isolation

- Anyone can register with an e-mail and password; a confirmation link is e-mailed.
- Sending to Kindle is unlocked once the e-mail is verified and a Kindle address is
  set in **Settings**.
- Each account only ever sees and manages its own feeds and articles; access is
  checked on every request.
- Per-account guardrails (`MAX_FEEDS_PER_USER`, `MAX_SENDS_PER_DAY`) and rate
  limiting on login/register/reset keep open registration from being abused.
- Administrators listed in `ADMIN_EMAILS` get a protected `/admin` dashboard
  showing total/24-hour/7-day sends and per-user usage. They can assign a custom
  rolling daily send limit or temporarily block an account from Kindle sending.
- The app runs as a single instance (one scheduled refresh, in-process rate
  limiter). Running multiple replicas would need a shared lock and store first.

### Tests

```bash
./mvnw test
```

Tests do not require PostgreSQL or Docker. They cover EPUB layout, HTML sanitization, SSRF address checks, and MVC/security smoke paths with mocked services.

## Using the app

1. **Add a feed** on the home page (direct feed URL or site homepage).
   You do not need to hunt down an XML URL: open the Kindle RSS website on your
   phone, paste the normal website address, and feed autodiscovery will usually
   find its RSS/Atom feed. The optional **Quick start** checkboxes can populate a
   new reader without typing URLs; no suggested feed is added unless you select it.
   Give a feed a category while adding it, or change its category later.
2. Open **Articles** / **Unread** and filter by feed.
3. Page through the list; articles you page past are marked read.
4. Tap an article's title to mark it read and view extracted content (images off by default).
   **Back** returns to the list you came from — same feed, category, page and unread
   selection — and **Feeds** goes to the feed overview, so neither needs the browser's
   own back button. An unread list keeps articles opened during that visit in place,
   so returning to the list does not make the entries jump. A feed-provided discussion
   link (for example Hacker News comments) remains available beside **Original**.
5. **Send to Kindle** builds an EPUB and emails it; `sent_at` is recorded only after SMTP succeeds.
   With JavaScript available it sends in place, without reloading or moving the
   current page; the normal form submission remains as a no-JavaScript fallback.

### How much gets loaded

A feed publishes only its newest entries, and how many is up to the publisher —
`https://hnrss.org/frontpage` sends 20 unless it is asked for more, which is a
fraction of the front page. Every refresh therefore asks for `FEED_MAX_ENTRIES`
(100 by default) through a `count` parameter. Services that understand it answer
with everything they have, the rest ignore a parameter they do not know, and a
server that rejects it is asked again for the URL as it stands. A URL that
already says how many it wants (`?count=`, `?limit=`, `?n=`) is left alone, and
`FEED_MAX_ENTRIES=0` turns the whole thing off.

Nothing is thrown away afterwards, so a feed keeps growing past what it
publishes at any one moment. `ARTICLE_PAGE_SIZE` (50, at most 100) sets how many
of those articles one page of the list holds; **Older articles** loads the next
ones.

### When feeds are refreshed

Feeds are fetched every 30 minutes by the scheduler, and again when you open the
feeds page or an article list after `FEED_AUTO_REFRESH_AFTER` (10 minutes by
default) has passed since the last refresh of your account. Fetching every feed
of an account takes longer than a page should, so it runs in the background: the
page you asked for renders from what is stored, and anything new is on the next
one. `FEED_AUTO_REFRESH_AFTER=0` leaves refreshing to the schedule and the button.

**Refresh** returns to the page it was pressed on, filters and page number
included, instead of starting over on the feeds page.

### What "the full article" means

Opening an article fetches the page it links to and extracts the readable part,
which is then cached. Some pages cannot be read that way — a paywall, a bot wall,
a PDF — and the article then says so, in place of silently showing the two or
three lines the feed happened to carry.

Hacker News entries carry no text at all: the feed summary is the article link,
the comments link and a score. Those are read as follows:

- A story that links elsewhere is fetched and extracted like any other article.
- An **Ask HN** or text submission links back to its Hacker News item, so the item
  page is read instead: the submitted text followed by the discussion, with replies
  indented by how deep they sit.
- When the linked page cannot be fetched, the discussion is shown instead, with a
  line saying why. That is not cached, so a page that is readable later still wins.

## Reading a page at a time

E-ink panels redraw slowly, so scrolling on a Kindle feels laggy. Articles and the
article list are therefore laid out as whole pages:

- The text area is sized to what is left of the device screen, so one page turn
  replaces exactly one screenful and never scrolls.
- **Previous page** / **Next page** sit under the text. Tapping the left quarter of
  the page goes back, tapping anywhere else goes forward, and the arrow, space and
  page keys work on a keyboard.
- In the article list, **Next page** on the last page marks the articles you paged
  past as read and loads the next ones, so a list can be cleared by reading through
  it instead of marking every article by hand. The button says **Mark read** when
  that is what it will do, and the next page reports how many were marked.
  *Older articles* at the end of the list moves on without marking anything, and
  an article that was opened by mistake takes **Mark unread** on its own page.
- Your position is remembered per article, so sending to Kindle or marking an
  article unread returns you to the page you were on.
- Rotating the device or changing the browser font re-splits the pages and keeps
  your place.

This is the one place the UI uses JavaScript (`static/js/reader.js`). With
JavaScript disabled, or in a browser that cannot lay out the columns, pages fall
back to a normally scrolling document with the same content and links.

## Send-to-Kindle (Amazon)

Delivery uses one shared sender (`MAIL_FROM`) for everyone. Amazon only accepts
documents from approved sender addresses, so each user does this once:

1. In Amazon account settings, open **Content & Devices** → **Preferences** → **Personal Document Settings**.
2. Note your **Send-to-Kindle Email** and enter it in the app under **Settings**.
3. Add the app's `MAIL_FROM` address (shown on the Settings page) under **Approved
   Personal Document E-mail List**.

Without approval, messages are silently dropped by Amazon.

## Deploy on Railway (recommended, no personal VPS)

The app is a small always-on service, which fits [Railway](https://railway.app)
well: managed Postgres, TLS, and Dockerfile builds with low ops.

1. Create a Railway project and add the **PostgreSQL** plugin.
2. Add a service from this repo; Railway builds the included `Dockerfile`
   (`railway.toml` sets the build and `/actuator/health` healthcheck).
3. Set service variables:

   ```
   SPRING_PROFILES_ACTIVE = production
   DATABASE_URL      = jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DATABASE_USER     = ${{Postgres.PGUSER}}
   DATABASE_PASSWORD = ${{Postgres.PGPASSWORD}}
   APP_PUBLIC_URL    = https://<your-service>.up.railway.app
   REMEMBER_ME_KEY   = <long random string>
   ADMIN_EMAILS      = you@yourdomain.com
   MAIL_FROM         = noreply@yourdomain.com
   SMTP_HOST         = smtp.resend.com
   SMTP_PORT         = 587
   SMTP_USERNAME     = resend
   SMTP_PASSWORD     = <Resend API key, starts with re_>
   ```

4. In [Resend](https://resend.com), verify your sending domain (SPF/DKIM) and
   create an API key; move out of the sandbox to e-mail arbitrary recipients.
5. Deploy, open the service URL, and register the first account. Set a billing
   alert on day one.

Flyway runs the schema migrations automatically on first boot. Rely on Railway's
managed Postgres backups. Any SMTP provider (Postmark, SES, …) works by changing
the `SMTP_*` / `MAIL_FROM` variables — no code change.

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

## Deploy to a VPS (self-host alternative)

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

## Which version is running

The Feeds page ends with a line like:

```
Version 1.0.0-SNAPSHOT · revision a1b2c3d · built 2026-08-10 08:45 UTC
```

Compare `revision` with `git rev-parse --short HEAD` to see whether the VPS runs
the code you have locally; a `-dirty` suffix means the deploy included uncommitted
changes. The same values are logged once at startup (`docker compose logs app | grep
'Kindle RSS'`) and served by `/actuator/info`, which requires a login.

Version and build time come from `META-INF/build-info.properties`, written by the
Spring Boot Maven plugin. The revision has to be passed in, because the deploy sync
and the Docker build context both exclude `.git`:

- `deploy/deploy.sh` reads the revision from your local checkout and forwards it as
  the `GIT_REVISION` build argument, so a normal deploy needs no extra steps.
- Building the image by hand: `GIT_REVISION=$(git rev-parse --short HEAD) docker
  compose -f deploy/docker-compose.yml --env-file .env build app`.
- Building the jar by hand: `./mvnw package -Dgit.revision=$(git rev-parse --short HEAD)`.

Without a revision the page reports `unknown`; version and build time are still
correct, and a build time in the past is itself a good sign that a deploy did not
take effect.

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
