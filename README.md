# Extrablatt

Multi-user RSS/Atom reader that extracts readable article HTML and emails EPUB files to each user's Kindle. Plain server-rendered UI that stays usable without JavaScript. Runs as one always-on service (single VPS or a managed platform such as Railway).

## Features

- Email + password accounts with e-mail verification and password reset
- Per-user feeds and articles — every account has its own, isolated subscriptions
- Add feeds by RSS/Atom URL or homepage (autodiscovery from `<link>` tags,
  feed-like links, and the site's conventional feed paths)
- Send any web article to Kindle by pasting its URL (extracted, stored, emailed as EPUB)
- Optional newsletter subscriptions: one inbound e-mail address per account —
  subscribe any newsletter to it and issues show up as articles, sent to Kindle
  the same way as any other
- Optional quick-start feed suggestions and categories for organizing subscriptions
- Scheduled refresh of every account's feeds every 30 minutes, plus a background
  poll when someone opens Feeds or Articles, asking each feed for more than the
  handful of entries it publishes by default
- Article extraction (Readability4J) with sanitized HTML caching
- Page-at-a-time reading sized to the device screen, instead of scrolling
- Send-to-Kindle as EPUB 3 through one shared, provider-verified sender, with the
  article's own address printed under the title so the page can be found again
- Per-account limits and IP-based rate limiting on auth endpoints
- Optional paid subscriptions: a week of the full plan at no charge, then a
  "Supporter" plan at €2.99/month billed yearly (€35.88 for 12 months) or €3.99/month, with a subscription menu, a
  publicly reachable cancellation page, and the order and withdrawal wording
  German consumer law requires. Off unless `BILLING_ENABLED` is set, so a
  self-hosted copy charges nobody (see [Subscriptions](#subscriptions-optional))

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
| `MAIL_FROM` | Shared sender on your verified domain (`mail@yourdomain.com`) |
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

1. Open **Add feed** beside the Feeds heading (direct feed URL or site homepage).
   You do not need to hunt down an XML URL: open the Extrablatt website on your
   phone, paste the normal website address, and feed autodiscovery will usually
   find its RSS/Atom feed. The optional **Quick start** checkboxes can populate a
   new reader without typing URLs; they disappear after the first subscription,
   and no suggested feed is added unless you select it.
   Give a feed a category while adding it, or change its category later. The
   Feeds page first shows one compact row per category with feed and unread
   counts; choose a category to see and manage only its feeds. A category can
   also be renamed there, which moves every feed in it to the new name at once.
2. **Send a URL** from **Feeds**: paste any article address. The page is fetched,
   stripped to readable HTML, saved under a **Pasted URLs** feed, and emailed to
   your Kindle as an EPUB.
3. Open **Articles** and filter by feed, category, or the **Unread** toggle in the
   filter bar.
4. Page through the list; articles you page past are marked read.
5. Tap an article's title to mark it read and view extracted content (images off by default).
   An unread list keeps articles opened during that visit in place, so returning
   to the list does not make the entries jump. A feed-provided discussion link
   (for example Hacker News comments) remains available beside **Original**.
6. **Send to Kindle** builds an EPUB and emails it; `sent_at` is recorded only after SMTP succeeds.
   With JavaScript available it sends in place, without reloading or moving the
   current page; the normal form submission remains as a no-JavaScript fallback.
   Less common article actions — opening the original, comments, image controls,
   and marking unread — are available under **More** so the reading toolbar stays
   on one row on small e-readers.

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
of those articles one page of the list holds; **Mark read and load more** at the
end of the last Kindle screen of a batch loads the next ones.

## Reading a page at a time

E-ink panels redraw slowly, so scrolling on a Kindle feels laggy. Articles and the
article list are therefore laid out as whole pages:

- The text area is sized to what is left of the device screen, so one page turn
  replaces exactly one screenful and never scrolls.
- **Previous page** / **Next page** sit under the text. Tapping the left quarter of
  the page goes back, tapping anywhere else goes forward, and the arrow, space and
  page keys work on a keyboard.
- In the article list, the last page of a loaded batch names both halves of what
  pressing it does: **Mark read and load more** with **Mark articles as read when
  I go to the next page** on in Settings (the default), or **Load more articles**
  with it off. One press is enough — the next batch arrives straight away, and the
  page it lands on reports how many articles were marked. So a list can be cleared
  by reading through it instead of marking every article by hand. On the last batch
  of the list there is nothing further to fetch, and the label reads **Mark read
  and continue**. Turn the setting off and new feed articles arrive already read,
  so a refresh does not fill Unread with a backlog. An article that was opened by
  mistake takes **Mark unread** on its own page.
- Opening a list or an article shows the finished page: the reader stays blank
  until the columns have been measured, rather than painting the whole batch in
  normal flow and then collapsing it to one screen once the script has run.
- Your position is remembered per article, so sending to Kindle or marking an
  article unread returns you to the page you were on.
- The list remembers which of its pages you are on for as long as it is that list:
  reading an entry and going back with the browser returns to the page the entry
  was picked from, not to the first one. A list opened afresh from **Articles** or
  a filter holds other articles and still starts at its beginning.
- Rotating the device or changing the browser font re-splits the pages and keeps
  your place.

This is the one place the UI uses JavaScript (`static/js/reader.js`). With
JavaScript disabled, or in a browser that cannot lay out the columns, pages fall
back to a normally scrolling document with the same content and links.

## Send-to-Kindle (Amazon)

Delivery uses one shared sender (`MAIL_FROM`) for everyone. Amazon only accepts
documents from approved sender addresses, so each user does this once. Hosted
Extrablatt sends from `mail@extrablatt.app` (shown as **Extrablatt**); do not
use Amazon trademarks such as `kindle` in the local part.

1. In Amazon account settings, open **Content & Devices** → **Preferences** → **Personal Document Settings**.
2. Note your **Send-to-Kindle Email** and enter it in the app under **Settings**.
3. Add the app's `MAIL_FROM` address (shown on the Settings page) under **Approved
   Personal Document E-mail List**.

Without approval, messages are silently dropped by Amazon.

## Newsletters

Some sources only publish by e-mail, not RSS. Once `NEWSLETTER_INBOUND_DOMAIN`
and `NEWSLETTER_INBOUND_SECRET` are set, **Settings** shows one newsletter
inbox address per account, generated the first time that page is opened (e.g.
`3f9c2a1b4e...@news.yourdomain.com`). Subscribe *any* newsletter to that same
address — there is nothing to add up front. The first issue from a given
sender creates a feed for it automatically (titled from the sender, filed
under a "Newsletters" category); later issues from that sender land as
articles on the same feed. From there it behaves exactly like an RSS feed:
read it, mark it read, **Send to Kindle**, recategorize, or **Delete** it.
**New address** in Settings rotates the inbox (e.g. once it starts collecting
spam) without losing anything already received.

This needs an inbound e-mail provider in front of the app, since Extrablatt
itself never receives mail directly:

1. Pick a subdomain for newsletter addresses (`NEWSLETTER_INBOUND_DOMAIN`, e.g.
   `news.yourdomain.com`) and point its MX record at an inbound e-mail provider —
   [Postmark](https://postmarkapp.com/inbound-email) (an "Inbound" server stream)
   is the easiest fit, since its webhook payload is what the app expects
   out of the box. Mailgun Routes, SendGrid Inbound Parse, or a Cloudflare Email
   Routing worker all work too with a small JSON reshape in front of the webhook.
2. Configure that provider's inbound webhook to `POST` to
   `https://<your-app>/inbound/newsletters?secret=<NEWSLETTER_INBOUND_SECRET>`
   (`NEWSLETTER_INBOUND_SECRET` is a long random value you choose; the query
   parameter is the only thing standing in for a login on this endpoint, since
   the provider cannot authenticate like a browser).
3. Set both variables in `.env` (or your platform's env vars) and redeploy.

The webhook expects Postmark's inbound JSON shape: `To`/`ToFull`/
`OriginalRecipient` (to find which account's inbox an issue arrived at, by the
address's local part), `From`/`FromName` (identifies which sender's feed it
belongs to, and names that feed the first time), `Subject`, `HtmlBody`/
`TextBody`, `MessageID` (deduplicates re-deliveries the way a feed's `guid`
does), and `Date`. Leaving `NEWSLETTER_INBOUND_DOMAIN` unset hides the feature
entirely; existing RSS feeds are unaffected either way.

## Subscriptions (optional)

Left alone, this feature does not exist: with `BILLING_ENABLED` unset there are no
prices, no subscription menu, no cancellation page, and every account keeps the
full `MAX_*` allowances. That is the right setting for a self-hosted copy, which is
not the one collecting the money.

Turned on, a new account gets a week of the paid allowances at no charge. After
that week the gate sits on Kindle delivery, because that is the only thing with
a real unit cost — one article sent is one e-mail — so reading in the browser
stays free and unmetered.

| | After the free week | Supporter |
|---|---|---|
| Send to Kindle | no (unless `BILLING_FREE_SENDS_PER_MONTH` is set) | no monthly limit, up to `MAX_SENDS_PER_DAY` (50) a day |
| Feeds | existing feeds stay; adding more needs a plan | `MAX_FEEDS_PER_USER` (50) |
| Newsletter inbox | — | yes |
| Price | €0 for seven days | €2.99/month billed yearly (€35.88 for 12 months), or €3.99/month |

`MAX_SENDS_PER_DAY` stays in force for everyone, including subscribers, as an
abuse guardrail rather than a plan limit. The complimentary week is
`BILLING_TRIAL_DAYS` (7) and does not renew.

Every account that exists when the original subscriptions migration ran is
grandfathered permanently: it was never advertised as something with a
subscription, and capping it afterwards would be both unfair and bad for the
project. An administrator can also grant the plan by hand from **Settings →
Telemetry**, which is how a reader whose payment went through but whose callback
went missing gets fixed.

Setup is a hosted checkout link per interval plus a webhook:

1. Create two prices at your payment provider — one yearly, one monthly — and take
   the hosted checkout (Stripe payment link or Paddle checkout) URL for each.
2. Point the provider's webhook at `https://<your-app>/webhooks/billing` and copy
   the signing secret. Stripe's `Stripe-Signature` and Paddle's `Paddle-Signature`
   schemes are both understood; `BILLING_PROVIDER` says which to expect.
3. Set the `BILLING_*` variables (see `.env.example`) and redeploy.

The callback is the only thing that grants a subscription — the page a reader lands
on after paying grants nothing — so a reader who closes the tab still ends up
subscribed. Losing a subscription never deletes anything; feeds, articles and
reading position stay. Reading in the browser continues; sending to Kindle needs
a paid period.

Two things are deliberately manual, because the app holds no provider API key: a
cancellation is e-mailed to `BILLING_OPERATOR_EMAIL` so the payment is stopped at
the provider by hand, and a missed callback is fixed with the admin grant above.

Before charging anyone there is work no configuration flag covers.
[`docs/going-live.md`](docs/going-live.md) is the runbook: the tax and establishment
question that has to be settled first because it decides the provider, then the
dashboard steps for Stripe or Paddle, then the mailbox the imprint needs.
[`docs/subscriptions-and-payments.md`](docs/subscriptions-and-payments.md) has the
pricing arithmetic and the reasoning behind the Stripe-versus-Paddle choice.

## Data protection

Every account can download everything held about it from **Settings → Your data** —
a JSON file, no request to make and nobody to ask. That covers the right of access
and the right to portability without anyone having to remember to answer an e-mail
within a month.

Deleting an account really deletes it. Feeds, articles, delivery history, tokens,
display preferences, send limits and subscription all go through database cascades;
the stored payment payload is emptied explicitly, since a payment event's id has to
outlive the account to keep a replayed webhook harmless. The one thing kept is a
payment or cancellation record, unlinked from the account, where tax and commercial
law require it.

A nightly sweep stops anything growing for ever — delivery history, spent
confirmation links, raw payment payloads, and cached article text for articles
already read and not saved, which is re-extracted from its own URL when next needed.
The periods are `RETENTION_*` in `.env.example`, and `0` switches any of them off.

There is **no cookie banner and no need for one**: the four cookies (session,
opt-in "remember me", and two display settings written only when a reader changes
them) all fall inside the strictly-necessary exception in § 25 TDDDG, and there is
no analytics, advertising or third-party script anywhere in the app. They are listed
in the privacy notice, which is what that exception does require.

Logs deliberately carry no e-mail addresses, Kindle addresses or IP addresses. Worth
keeping in mind when adding a log line: log files are where personal data quietly
accumulates with no retention period at all.

[`docs/data-protection.md`](docs/data-protection.md) is the Art. 30 record of
processing activities — what is held, on what legal basis, for how long, who receives
it, and the known gaps. It is written to be handed to an EU representative, who has
their own obligation to hold it.

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
   MAIL_FROM         = mail@yourdomain.com
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

### Production vs staging

This repo's Railway project has two environments:

| | Production | Staging |
|---|---|---|
| GitHub branch | `main` | `staging` |
| App URL | https://reader.extrablatt.app | https://staging.extrablatt.app |
| Landing page URL | https://extrablatt.app | https://marketing-site-staging-staging.up.railway.app |
| Database | live Postgres | **copy** of production (own instance) |
| How it deploys | Railway GitHub trigger on `main` | Railway GitHub trigger on `staging`, plus `.github/workflows/deploy-railway.yml` |

Merge (or push) to `staging` to ship a build you can try before it reaches
readers. Merge to `main` when that build should go live. Staging has its own
Postgres so Flyway and feed refresh cannot touch production; `deploy/sync-staging-db.sh`
(and the **Sync staging database** GitHub Action, daily plus manual) dumps
production and restores it onto staging.

Staging still uses the production SMTP sender, so Kindle sends and account
e-mail from that host are real. `APP_PUBLIC_URL` is `https://staging.extrablatt.app`,
so verification and reset links stay on staging.

To let GitHub Actions talk to Railway, add a repository secret named
`RAILWAY_TOKEN` (a Railway account or project token). The dashboard trigger
keeps deploying even without that secret. Until the secret is set, both
`deploy-railway.yml` and **Sync staging database** skip their Railway steps
with exit 0 instead of failing the Actions run.

The steps above cover the application service. `marketing/` also has its own
`Dockerfile` (a tiny Caddy container serving the folder on `$PORT`), so it can
run as a second Railway service in the same project:

```bash
railway add --service marketing-site           # empty service
railway up marketing --path-as-root --service marketing-site
railway domain extrablatt.app --service marketing-site   # reader.extrablatt.app stays on the app service
```

Any static file host (GitHub Pages, Cloudflare Pages, Netlify, …) works
just as well if you'd rather not run it on Railway.

#### The staging landing page

The landing page quotes prices and links to the cancellation and withdrawal
pages, so it needs somewhere to be read before it is live. Staging gets its own
`marketing-site` service, in the `staging` environment, with one variable the
production one does not have:

It already exists, as **`marketing-site-staging`**, live on
<https://marketing-site-staging-staging.up.railway.app>. Its configuration is the
production service with one branch changed:

| | `marketing-site` (production) | `marketing-site-staging` |
|---|---|---|
| Branch | `main` | `staging` |
| Root directory | `marketing` | `marketing` |
| Builder | Dockerfile | Dockerfile |
| `SITE_ENV` | unset (so, production) | `staging` |
| Domain | `extrablatt.app` | generated `*.up.railway.app` |

The name has to differ because **Railway service names are unique across a
project, not per environment** — `railway add --service marketing-site` fails
with "already exists in this project" even from the staging environment. The app
service is called `kindle-rss-app` in both only because it predates the split.

A generated domain rather than a custom one keeps the DNS zone free of a
hostname only you will visit. If you do add one, note that the production zone
is behind Cloudflare, which serves its own content-signals `robots.txt` — worth
checking that it does not shadow the one in the image. The `noindex` tag in the
page holds either way, which is why the build writes both.

To recreate it from scratch, or to build a second one:

```bash
railway environment staging                                          # switch the linked environment
railway add --service marketing-site-staging --repo Philipp0205/kindle-rss \
    --branch staging --variables "SITE_ENV=staging"
railway domain --service marketing-site-staging --environment staging
```

Then, in the dashboard, **Settings → Source → Root Directory → `marketing`**.
The CLI has no flag for it, and without it Railway builds the repo root and
deploys the app instead of the landing page.

`railway add` has no `--environment` flag — it creates the service in whichever
environment is currently linked, which is why the switch comes first. Check with
`railway status` if you are not sure where you are.

Deploys come from Railway's own GitHub trigger on the `staging` branch, the same
mechanism that ships the production page. The `railway up` step in
`deploy-railway.yml` is a belt-and-braces extra that does nothing today: it
guards on a `RAILWAY_TOKEN` repository secret that is not set, so it prints a
skip notice and exits 0 on every run.

To change `SITE_ENV` later, it is `railway variable set SITE_ENV=staging
--service marketing-site-staging --environment staging` (`railway variables
--set …` is the deprecated spelling).

`SITE_ENV=staging` is what makes it a staging copy rather than a second live
one. Railway passes it into the Docker build, where `marketing/make-staging.sh`:

- **points every link at `staging.extrablatt.app`.** The page mentions the app
  in twenty-odd places — buttons, the address to type on the Kindle, the meta
  description — and they are all the same string, so one substitution moves all
  of them. Skip this and "Choose yearly" on staging opens the *live* checkout.
- **keeps the copy out of search results,** with `robots.txt` and a `noindex`
  meta tag. It is the live page word for word, so an indexable second copy
  competes with the real one for the same searches.
- **puts a banner across the top** saying which site you are on.

The build refuses to finish if the substitution left a production hostname
behind, so a misconfigured staging site fails to deploy rather than quietly
pointing at production.

One thing this does *not* solve: the staging app's own `BILLING_*` variables
decide what its checkout does. Point them at your payment provider's test mode,
or the staging landing page will walk you into a real payment.

## Marketing / landing page

`marketing/` is a small, static (plain HTML/CSS, no JavaScript, no build step)
landing page: a short pitch plus screenshots of the app running on an actual
Kindle, with a link through to the app itself. It is deliberately **not**
built with Spring/Java — it is pure static content, so the simplest, cheapest
thing to serve it with is a file server, not another JVM process. The bundled
Caddy container already sits in front of the app, so it serves this folder
directly as a second site (see `deploy/Caddyfile`); nothing else needs to run.

The production split is two names against this deployment:

- `extrablatt.app` (`MARKETING_DOMAIN`) — the static page in `marketing/`.
- `reader.extrablatt.app` (`DOMAIN`) — the actual application (this repo's Spring
  Boot service).

To update the landing page's copy or screenshots, edit files under
`marketing/` and redeploy as usual — `deploy/deploy.sh` syncs the whole repo,
including this folder, and Caddy serves whatever is on disk with no rebuild.

The committed files are the *production* page: they name `reader.extrablatt.app`
throughout, and that is what ships to `extrablatt.app`. The staging copy is
built from the same files by `marketing/make-staging.sh`, so there is one page
to edit rather than two that drift apart. See
[The staging landing page](#the-staging-landing-page) for how that is wired.

## DNS / TLS

Point an A/AAAA record at the VPS for `DOMAIN` (the app, e.g.
`reader.extrablatt.app`) and, if used, for `MARKETING_DOMAIN` (the landing page,
e.g. `extrablatt.app`). Caddy obtains certificates for them automatically when
ports 80/443 are reachable.

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

Set `DOMAIN` to the app's subdomain (e.g. `reader.extrablatt.app`) and, to also
serve the landing page from the same bundled Caddy container, `MARKETING_DOMAIN`
to the bare domain (e.g. `extrablatt.app`) in `.env`. Leave `MARKETING_DOMAIN`
unset to run the app on its own, with no landing page.

Never commit `.env`, private keys, or `VPS_SSH_KEY_B64`.

## Which version is running

The Feeds page ends with a line like:

```
Version 1.0.0-SNAPSHOT · revision a1b2c3d · built 2026-08-10 08:45 UTC
```

Compare `revision` with `git rev-parse --short HEAD` to see whether the VPS runs
the code you have locally; a `-dirty` suffix means the deploy included uncommitted
changes. The same values are logged once at startup (`docker compose logs app | grep
'Extrablatt'`) and served by `/actuator/info`, which requires a login.

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
