# Extrablatt

Multi-user RSS/Atom reader that extracts readable article HTML and emails EPUB files to each user's Kindle. Plain server-rendered UI that stays usable without JavaScript. Runs as one always-on service (single VPS or a managed platform such as Railway).

## License and personal self-hosting

Extrablatt is source-available under the
[PolyForm Noncommercial License 1.0.0](LICENSE)
(`PolyForm-Noncommercial-1.0.0`). It is not OSI-approved open-source software.

- You can use, modify, and self-host it for personal, noncommercial purposes
  without paying a software license fee. Other permitted purposes, including
  use by the organizations listed in the license, are defined in the full terms.
- You provide and pay for your own server, database, and e-mail delivery. Leave
  `BILLING_ENABLED` unset or `false`; no hosted Extrablatt subscription is needed.
- Commercial use, including selling hosted access, is not granted by this license
  and requires separate permission from the copyright holder. For licensing
  inquiries, contact [hello@extrablatt.app](mailto:hello@extrablatt.app).
- The official [hosted service](https://reader.extrablatt.app) is operated
  commercially by the project owner. Its subscriptions pay for that service;
  they do not grant rights to resell or commercially host the software.

The license terms govern; this is a summary. Third-party dependencies retain
their own licenses. Start with [Local configuration](#local-configuration) or
[Build / run with Docker](#build--run-with-docker) to self-host.

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
  handful of entries it publishes by default. A feed followed by several accounts
  is fetched once per refresh, and no database transaction is held open while a
  publisher answers
- Article extraction (Readability4J) with sanitized HTML caching
- A continuously scrolling, mobile-first article list that grows with **Load more
  articles**, and marks articles read only when you open them or ask it to
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
| `REMEMBER_ME_KEY` | Secret for remember-me tokens. At least 32 characters and not a placeholder (`openssl rand -base64 48`); startup fails otherwise under the `production` profile or a non-localhost `APP_PUBLIC_URL` |
| `ADMIN_EMAILS` | Comma-separated account e-mails allowed to view telemetry and manage per-user send limits |
| `TRUSTED_PROXIES` | `app.trusted-proxies`: comma-separated CIDRs of proxies allowed to add to `X-Forwarded-For`. Blank (default) means `127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10, 169.254.0.0/16, ::1/128, fc00::/7, fe80::/10`; a value replaces that list. See [Client address behind proxies](#client-address-behind-proxies) |
| `TRUST_CLOUDFLARE` | `app.trust-cloudflare`, default `true`: also trust Cloudflare's published edge ranges. Set `false` when Cloudflare is not in front of the app |

Flyway applies migrations strictly in version order (`out-of-order` is off). If a
database is missing a lower version (for example a branch that skipped one was
deployed first), Flyway refuses to start. Set `FLYWAY_OUT_OF_ORDER=true` for a
single repair deploy so the missing script runs, then remove it again.

3. Export variables (or use your shell dotenv tooling) and run:

```bash
./mvnw spring-boot:run
```

Open http://localhost:8080, create an account, confirm your e-mail, then add your
Kindle address under **Settings**.

## Accounts and data isolation

- Anyone can register with an e-mail and password; a confirmation link is e-mailed.
  Signing up again with an address that is already taken keeps the existing
  account and its password, and mails that address instead: a fresh confirmation
  link while it is unconfirmed, otherwise a note that the account exists. The
  browser sees the same "check your inbox" reply either way, so the form does not
  reveal which addresses are registered.
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

GitHub Actions run the tests on every push and pull request (`ci.yml`) and
CodeQL analysis of the Java code (`codeql.yml`, also weekly). Third-party
actions are pinned to commit SHAs with the release noted alongside, and
Dependabot (`.github/dependabot.yml`) opens weekly update PRs against
`staging` for Maven, Actions and the Docker base images.

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
4. Scroll through the list and press **Load more articles** at its end for the next
   batch. Scrolling and loading never mark anything read; **Mark all as read**
   under the list marks the articles you have loaded.
5. Tap an article's title to mark it read and view extracted content (images off by default).
   An unread list keeps articles opened during that visit in place, so returning
   to the list does not make the entries jump. A feed-provided discussion link
   (for example Hacker News comments) remains available beside **Original**.
6. **Send to Kindle** builds an EPUB and emails it; `sent_at` is recorded only after SMTP succeeds.
   With JavaScript available it sends in place, without reloading or moving the
   current page; the normal form submission remains as a no-JavaScript fallback.
   **Send to Kindle** sits both above the article and where it ends. Less common
   article actions (opening the original, comments, image controls, and marking
   unread) are under **More**, so the toolbar stays on one row on a phone.

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
of those articles one batch of the list holds; **Load more articles** at the end
of the list loads the next batch.

## Reading the list

The article list is one continuous, scrolling column, laid out for a phone first:

- Each row shows the feed, how long ago the article was published, and its title,
  with a dot (and, for screen readers, the word "Unread") on unread articles and a
  **Send** button beside it.
- **Load more articles** at the end of the list appends the next batch. It is a
  button you press, never triggered by scrolling, so the site footer and its legal
  links stay reachable. The address keeps track of how much was loaded, so
  reloading the page, or reading an entry and going back with the browser, shows
  the same articles in the same place.
- Loading and scrolling never mark anything read. An article is marked read when
  you open it, or when you press **Mark all as read** under the list, which marks
  exactly the articles you have loaded and no others. An unread list then starts
  afresh. An article opened by mistake takes **Mark unread** under **More** on its
  own page.
- An unread list keeps the articles read during that visit in place, so the rows
  on screen do not jump while you read and load more.
- On a phone, **Articles**, **Feeds** and **Settings** sit in a tab bar at the
  bottom of the screen; on a wider screen they move into the top bar.
- **Settings → Reading → Keep track of unread articles** is on by default. Turn it
  off and new feed articles arrive already read, so a refresh does not fill Unread
  with a backlog and the list simply shows everything, newest first.

On an e-ink Kindle the list and articles turn page by page instead: tap the right
of the screen (or swipe, or press Next) for the next page, the left third to go
back. Phones, tablets and Fire tablets keep scrolling. If a device picks wrong,
**Settings → Reading → Page layout** switches it, or add `?layout=kindle` or
`?layout=scroll` to any address. See [docs/mobile-ui.md](docs/mobile-ui.md).

JavaScript (`static/js/app.js`) only adds conveniences: load-more appends in
place, Send to Kindle sends without reloading, and **Mark all as read** includes
the batches that were appended. With JavaScript disabled, **Load more articles**
is an ordinary link to the next batch, **Newer articles** leads back, and every
form still works as a normal page submission.

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

With billing on, the inbox belongs to the Supporter plan (and the free week).
Once an account no longer has it, an issue from a sender it has never heard
from is dropped instead of creating a feed (the inbound webhook answers 200
with `"status": "dropped"`), while senders that already have a feed keep
delivering.

This needs an inbound e-mail provider in front of the app, since Extrablatt
itself never receives mail directly:

1. Pick a subdomain for newsletter addresses (`NEWSLETTER_INBOUND_DOMAIN`, e.g.
   `news.yourdomain.com`) and point its MX record at an inbound e-mail provider —
   [Postmark](https://postmarkapp.com/inbound-email) (an "Inbound" server stream)
   is the easiest fit, since its webhook payload is what the app expects
   out of the box. Mailgun Routes, SendGrid Inbound Parse, or a Cloudflare Email
   Routing worker all work too with a small JSON reshape in front of the webhook.
2. Configure that provider's inbound webhook to `POST` to
   `https://<your-app>/inbound/newsletters` with `NEWSLETTER_INBOUND_SECRET` (a
   long random value you choose) sent in an `X-Newsletter-Secret` header or as
   `Authorization: Bearer <secret>`. Postmark cannot set custom headers, so give it
   `https://inbound:<NEWSLETTER_INBOUND_SECRET>@<your-app>/inbound/newsletters`;
   it sends that as HTTP Basic auth and the app reads the password. The secret is
   the only thing standing in for a login on this endpoint, since the provider
   cannot authenticate like a browser. The older
   `?secret=<NEWSLETTER_INBOUND_SECRET>` form still works but logs a deprecation
   warning, because URLs end up in proxy and access logs. A message larger than
   `NEWSLETTER_MAX_BODY_BYTES` (default 10 MiB) is refused with 413.
3. Set both variables in `.env` (or your platform's env vars) and redeploy.

The webhook expects Postmark's inbound JSON shape: `To`/`ToFull`/
`OriginalRecipient` (to find which account's inbox an issue arrived at, by the
address's local part), `From`/`FromName` (identifies which sender's feed it
belongs to, and names that feed the first time), `Subject`, `HtmlBody`/
`TextBody`, `MessageID` (deduplicates re-deliveries the way a feed's `guid`
does), and `Date`. Leaving `NEWSLETTER_INBOUND_DOMAIN` unset hides the feature
entirely; existing RSS feeds are unaffected either way.

## Subscriptions (optional)

The billing configuration below is for the project owner's hosted service or
operators with separate commercial permission; it does not grant commercial
hosting rights under the [license](LICENSE).

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
project. A grandfathered account cannot place an order: the order page sends it
back to **Settings → Subscription** with a note, because a pending order would
otherwise replace the permanent plan. An administrator can also grant the plan by hand from **Settings →
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

A callback that cannot be applied yet, usually a subscription event that overtook
the checkout naming its account, is answered with a 503 so the provider delivers it
again, and the app also retries it itself from the stored payload. The first retry
waits `BILLING_WEBHOOK_RETRY_BACKOFF` (default `PT1M`), each one after that twice as
long up to six hours, until `BILLING_WEBHOOK_MAX_ATTEMPTS` (default 12, about a day)
is used up. A retry that arrives after a newer event for the same subscription was
applied is skipped rather than rolling the subscription back.

Two things are deliberately manual, because the app holds no provider API key: a
cancellation is e-mailed to `BILLING_OPERATOR_EMAIL` so the payment is stopped at
the provider by hand, and a missed callback is fixed with the admin grant above.

`/cancel` needs no login, as § 312k BGB requires, but a declaration only takes
effect at once for the signed-in owner of the account. Anyone else's is recorded
with the time it arrived, and a single-use link (24 hours, at most three per account
per day, only its SHA-256 hash stored) goes to the account's own address; following
it and pressing the button applies the cancellation. The page shown afterwards is
the same whether or not the address has an account, and the link e-mail repeats
nothing the requester typed. See
[`docs/subscriptions-and-payments.md`](docs/subscriptions-and-payments.md) for the
legal reasoning, which needs a lawyer's look before charging anyone.

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
   REMEMBER_ME_KEY   = <output of: openssl rand -base64 48>
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

Behind Cloudflare and Railway the defaults for `TRUSTED_PROXIES` and
`TRUST_CLOUDFLARE` are correct; leave both unset (see
[Client address behind proxies](#client-address-behind-proxies)). Turning off the
`*.up.railway.app` domain once a custom domain works is optional hardening.

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
| How it deploys | Railway GitHub trigger on `main` | Railway GitHub trigger on `staging` |

Merge (or push) to `staging` to ship a build you can try before it reaches
readers. Merge to `main` when that build should go live. Staging has its own
Postgres so Flyway and feed refresh cannot touch production; `deploy/sync-staging-db.sh`
(and the **Sync staging database** GitHub Action, daily plus manual) copies
production onto staging with personal data scrubbed.

The copy is streamed into staging as one transaction: the production dump,
then `deploy/staging-scrub.sql`. Either the scrubbed copy is committed or
staging keeps what it had, so unscrubbed production data is never committed
there. The scrub:

- rewrites every account's e-mail and Kindle address to `...@staging.invalid`,
  replaces its password hash with one that matches nothing and drops its
  newsletter inbox address;
- deletes all verification and reset tokens;
- renames newsletter feeds and blanks their issues (sender, reader, content);
- strips credentials and query strings from feed URLs and clears feed errors;
- replaces payment provider ids, webhook payloads and cancellation details;
- refuses to commit if any non-kept account still has a real address.

`StagingScrubTest` fails when a migration adds a text column the scrub does
not know about, so new personal data has to be classified before it ships.

Accounts listed in `STAGING_KEEP_EMAILS` are left as they are so testers and
admins (`ADMIN_EMAILS`) can still sign in; everyone else has to register anew
on staging. Remember-me cookies are signed with `REMEMBER_ME_KEY` and the
password hash, so after a sync only kept accounts stay signed in, and only if
staging shares the key: give staging its own `REMEMBER_ME_KEY`.

Staging must not send with the production SMTP account. Give it its own
sender or a mail sink (for example Mailpit, or a Resend key restricted to a
test domain), and optionally set `MAIL_ALLOWED_RECIPIENT_DOMAINS`. The sync
compares the SMTP settings of both environments (hashes only, nothing is
printed) and stops if they are the same or cannot be read; `ALLOW_SHARED_SMTP=1`
overrides that. Independently, the app refuses to send to any `.invalid`
address, so scrubbed accounts cannot receive mail even with shared SMTP.
`APP_PUBLIC_URL` is `https://staging.extrablatt.app`, so verification and
reset links stay on staging.

The script registers a throwaway SSH key with Railway (held in a private
`ssh-agent`, never written to `~/.ssh`), uses a private ssh config and
known_hosts in a temp directory, and removes the key again on exit. It does
not read or change `~/.ssh/config`. Pass `SSH_KEY_PATH` to use a key that is
already registered, and `RAILWAY_SSH_KNOWN_HOSTS` to pin the relay host key
instead of trusting it on first use.

To let the **Sync staging database** GitHub Action talk to Railway, add a
repository secret named `RAILWAY_API_TOKEN` (a Railway account or workspace
token; the Railway CLI refuses to manage SSH keys with a project token) and,
optionally, a secret `STAGING_KEEP_EMAILS` and a variable `RAILWAY_PROJECT_ID`.
Deploys themselves don't need it: those go through Railway's own GitHub
trigger on `main`/`staging` regardless. Until the secret is set, **Sync
staging database** skips its Railway steps with exit 0 instead of failing the
Actions run.

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
railway add --service marketing-site-staging --repo Philipp0205/extrablatt \
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
mechanism that ships the production page.

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
landing page: a short pitch plus screenshots of the app inside a drawn Kindle,
with a link through to the app itself. It is deliberately **not**
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

The screenshots in `marketing/assets/img/` are the running app captured at
1236 × 1648, a Kindle Paperwhite's panel. Take them from a browser window of
618 × 824 CSS pixels at twice the device pixel ratio, signed in to an account
holding a few feeds, so the app lays out exactly as it would on the
device. Keep that 3:4: the hero's device frame crops anything taller.

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

The `app` service loads the repo-root `.env` with `env_file`, so every setting
from `.env.example` (`BILLING_*`, `RETENTION_*`, `NEWSLETTER_*`, ...) reaches the
application without being listed in the compose file. The `.env` file must
exist next to `deploy/` (Compose fails otherwise). Values under `environment:`
in `deploy/docker-compose.yml` take precedence: `DATABASE_URL` always points at
the bundled Postgres, and `POSTGRES_PASSWORD`, `MAIL_FROM`, `REMEMBER_ME_KEY` and
`DOMAIN` are still required.

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
honors `X-Forwarded-Proto` and issues Secure cookies and https redirects. The
host proxy reaches the app through Docker's port publishing, so the app sees a
private-range peer, which the default `TRUSTED_PROXIES` already covers.

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
Version 1.0.0 · revision a1b2c3d · built 2026-08-10 08:45 UTC
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

- Both scripts read only the keys they need (`POSTGRES_DB`, `POSTGRES_USER`,
  `BACKUP_*`) from `.env`, as plain text; `.env` is never sourced or executed.
  Variables already set in the environment win.
- `backup.sh` keeps the newest `BACKUP_KEEP` backups (default 14, `0` keeps all)
  and deletes older ones. Backups are created with mode `600`.
- Set `BACKUP_AGE_RECIPIENT` (an age public key) or `BACKUP_GPG_RECIPIENT` (a gpg
  key id or e-mail) to encrypt each backup; the file then ends in `.age` / `.gpg`.
  Restoring an `.age` backup needs `BACKUP_AGE_IDENTITY` (path to the identity
  file); `.gpg` backups use the local gpg keyring.
- `restore.sh` replaces the whole database, so it asks you to type the database
  name first. Pass `--yes` to skip that in scripts (it refuses to run without a
  terminal otherwise). It restores in a single transaction with
  `ON_ERROR_STOP=1`, so a broken dump leaves the database unchanged.

## Security notes

- CSRF protection stays enabled; forms include tokens.
- Feed/article HTML is sanitized with jsoup Safelist before `th:utext`.
- Outbound fetches allow only `http`/`https` and refuse loopback, private, link-local, CGNAT, ULA, multicast, documentation, benchmarking and reserved ranges, plus IPv6 prefixes that embed an IPv4 address (mapped, NAT64, 6to4, Teredo). The check runs inside the resolver the connection uses, so every DNS answer is checked at connect time and a host that switches to a private address after a first lookup (DNS rebinding) is still refused. Redirects are followed by hand (at most 5, each hop re-checked); no proxy, cookies or retries. Response size is capped (`app.http.max-bytes`), each wait for data is capped (`app.http.read-timeout`), and a whole fetch, redirects included, is capped by `app.http.total-timeout` (30s).
- Redirect targets from form posts are restricted to same-app relative paths.
- The public `/cancel` form changes a subscription only for the signed-in owner. For anyone else it records the declaration and mails a single-use, hashed, 24-hour link to the account's own address (at most three per account per day), without the requester's text; `/cancel/confirm` acts only on a CSRF-protected POST. The response is the same whether or not an account exists.
- Every page is served with a Content-Security-Policy: scripts only from the app's own `/js/` files and styles only from its own `/css/app.css` (`script-src 'self'; style-src 'self'`), with no inline script, style or event handler. Images may come from any http(s) origin, since articles show their source's images; forms may post to the app and to the origins of the configured checkout and portal URLs. Framing is refused (`frame-ancestors 'none'`, `X-Frame-Options: DENY`). Confirmation prompts on destructive forms use a `data-confirm` attribute read by `js/app.js`; new templates must not add `style="..."` or `on...=` attributes (a test checks).
- Errors render the error page with a fixed message: malformed or missing input is a 400, unknown paths a 404, a wrong HTTP method a 405, and anything unexpected a 500 that is logged in full but never shows the exception text.
- Session cookies are not marked Secure in the default profile (local HTTP). The `production` profile sets Secure cookies; use a TLS-terminating proxy with forwarded headers.
- Rate limiting keys on the client address `ClientIpResolver` works out (see [Client address behind proxies](#client-address-behind-proxies)), not on `getRemoteAddr()` and not on the leftmost `X-Forwarded-For` entry, both of which the client controls behind `server.forward-headers-strategy: framework`. No proxy has to overwrite the header; appending is fine. The limiter is in memory, bounded to 10,000 keys, and per instance.

### Client address behind proxies

Production runs client, Cloudflare, Railway's edge, app. Each proxy appends the
address it saw to `X-Forwarded-For`, so only the right-hand end of that header is
trustworthy; the leftmost entry is whatever the client sent. The app therefore
works out the client address itself, from the original socket peer and the raw
header (it unwraps Spring's `ForwardedHeaderFilter`, which only stays on for
`X-Forwarded-Proto` / `-Host` / `-Port`):

- If the socket peer is not a trusted proxy, `X-Forwarded-For` is ignored and the
  peer is the client.
- Otherwise the header is walked from the right, skipping trusted proxies
  (Railway's private hops, then Cloudflare). The first untrusted address is the
  client. If every entry is trusted, the leftmost one is used. An entry that is
  not an IP literal stops the walk at the nearest trusted hop.
- `CF-Connecting-IP` is never read. The Railway origin is also reachable directly
  at `*.up.railway.app`, bypassing Cloudflare, and a Cloudflare Worker can set
  that header too. A request straight to the origin still resolves correctly: to
  the caller address Railway's edge appended.

Settings:

- `app.trusted-proxies` (env `TRUSTED_PROXIES`): comma-separated CIDRs. Blank
  (default) means loopback, RFC 1918, `100.64.0.0/10` (Railway's private network
  and CGNAT), `169.254.0.0/16`, `::1/128`, `fc00::/7` and `fe80::/10`. A value
  replaces that list. A malformed range fails start-up.
- `app.trust-cloudflare` (env `TRUST_CLOUDFLARE`, default `true`): also trust
  Cloudflare's published IPv4 and IPv6 ranges, hardcoded in
  `ClientIpResolver.CLOUDFLARE_RANGES` (copied from cloudflare.com/ips-v4 and
  ips-v6 on 2026-11-07; refresh when Cloudflare changes them). Set `false` on a
  deployment without Cloudflare, so a Cloudflare address in the header is not
  skipped.

The bundled VPS setup works with the defaults: Caddy sits on the Docker network
(a private-range peer) and sets `X-Forwarded-For` to the client it saw.

Optional hardening: switch off the `*.up.railway.app` domain in Railway
(service, Settings, Networking) so all traffic goes through Cloudflare. The rate
limiter is correct either way.

## Stack

- Spring Boot 3.5.16, Java 21, Maven Wrapper
- Thymeleaf, Spring Security, JDBC, Mail, Flyway, Actuator
- ROME 2.1.0, Readability4J 1.0.8, jsoup 1.22.2 (explicit override; Readability4J otherwise pulls 1.11.2)
- Apache HttpClient 5 for outbound fetches (version from the Spring Boot BOM)
