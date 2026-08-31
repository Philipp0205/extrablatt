# Data protection — what Extrablatt holds, why, and for how long

This is the record of processing activities Art. 30 GDPR asks for, plus the decisions
behind it. It is written to be handed over: an Art. 27 representative has an
independent obligation under Art. 30(4) to hold this record and produce it to a
supervisory authority on request, and a document nobody outside the project can read
would not survive that.

It describes the deployment at `reader.extrablatt.app`. A self-hosted copy is its own
controller and needs its own version of this.

## Controller

| | |
|---|---|
| Controller | Kubri by Philipp Kurrle und Ali Abriani KLG, Wehntalerstrasse 17, 8057 Zürich, Switzerland |
| Register | Handelsregisteramt des Kantons Zürich, CH-020.2.010.172-0, UID CHE-349.063.262 |
| Contact | The address on `/imprint` |
| Data protection contact | Philipp Kurrle, Stuttgart, Germany, via the address on `/imprint`. He is a partner with sole signing authority and the person who builds and runs the service. |
| Data protection officer | None. Art. 37 GDPR does not require one here: no large-scale processing of special categories, no systematic monitoring. |
| Representative in the EU | Probably not required, pending confirmation. Art. 27 applies only where the GDPR reaches the controller through Art. 3(2). With a managing partner running the service from Stuttgart, Art. 3(1) is the likely route instead, and the EDPB's Guidelines 3/2018 are explicit that a controller caught by Art. 3(1) need not designate a representative. See `docs/going-live.md`, question 3, for how this is being settled and what changes if the answer goes the other way. |
| Likely lead supervisory authority | LfDI Baden-Württemberg, if Art. 3(1) applies — Stuttgart is in Baden-Württemberg. Worth knowing before a breach, not after. |
| Transfers out of the EU | To Switzerland, where the service runs. The European Commission renewed Switzerland's adequacy decision on 15 January 2024, so no further safeguard is needed for the transfer itself. |

## Processing activities

### 1. Running an account

**Purpose** — letting someone sign in and own their own feeds.
**Legal basis** — Art. 6(1)(b), performance of the contract the account is.
**Data** — `users`: e-mail address, bcrypt password hash, Kindle address, verification
and disabled timestamps, newsletter inbox token, reading preference, last changelog
seen. `email_tokens`: confirmation and password-reset tokens.
**Recipients** — the e-mail provider, for confirmation and reset messages.
**Retention** — until the account is deleted. Spent or expired tokens go after
30 days.

### 2. Fetching and storing feeds and articles

**Purpose** — showing the reader what they subscribed to.
**Legal basis** — Art. 6(1)(b).
**Data** — `feeds`: title, URL, site, category, source type, last error. `articles`:
title, URL, author, publication time, summary and body HTML, cached extracted text,
read state and time, last send time. Which feeds a person follows and
which articles they read is behavioural data about them, even though the article text
is the publisher's.
**Recipients** — none. The publishers being polled see the server's IP address and the
URL requested; nothing identifying the reader is sent, and `SafeHttpClient` sends no
cookies and no referrer.
**Retention** — until the account or the feed is deleted. Cached extracted text is
cleared after 365 days for articles already read, and re-extracted from
the article's own URL when next needed.

### 3. Delivering articles to a Kindle

**Purpose** — the point of the product.
**Legal basis** — Art. 6(1)(b).
**Data** — `article_send_events`: account, article, time. The EPUB itself is built in
memory and not stored.
**Recipients** — the SMTP provider (Resend by default), which sees the reader's Kindle
address, the article title and the article text as an attachment; and Amazon, which
receives it.
**Retention** — 730 days for the delivery history. The rolling quota needs one day of
it and the telemetry page seven; the rest only ever fed a lifetime counter, which is
not a reason to keep a record of everything somebody has ever read.

### 4. Newsletters arriving by e-mail (optional)

**Purpose** — letting a reader follow things that only publish by e-mail.
**Legal basis** — Art. 6(1)(b) for the reader. Third-party data — the sender's address
and name, and anyone named in a newsletter — arrives as an unavoidable part of
delivering a message the reader asked for.
**Data** — the sender's address becomes a feed URL and title; the message subject and
body become an article.
**Recipients** — the inbound e-mail provider, which receives the message first.
**Retention** — as for articles.

### 5. Abuse prevention

**Purpose** — stopping open registration being used to hammer login endpoints or the
send path.
**Legal basis** — Art. 6(1)(f), legitimate interest in keeping a free service usable.
**Data** — IP addresses, held in memory only by `RateLimitingFilter`, never written to
the database or a log. `user_send_limits`: an administrator's per-account send cap or
temporary block.
**Retention** — the IP counters die with the process. Limits last until an
administrator clears them.

### 6. Operational telemetry

**Purpose** — knowing whether the service is being used and what it costs.
**Legal basis** — Art. 6(1)(f).
**Data** — no separate storage: `/settings?view=telemetry` aggregates the tables above.
Administrators can see every account's e-mail address, signup date and usage counts.
This is the most privacy-relevant thing in the app that a reader cannot see, and it
exists because the alternative is running a service blind.
**Retention** — derived; nothing of its own.

### 7. Subscriptions and payments (only where billing is switched on)

**Purpose** — taking payment and knowing who has paid.
**Legal basis** — Art. 6(1)(b) for the subscription; Art. 6(1)(c) for the records tax
and commercial law require to be kept.
**Data** — `subscriptions`: plan, status, interval, the provider's customer and
subscription identifiers, period end, withdrawal-consent timestamp.
`billing_events`: the provider's event id, type, and the raw webhook payload.
`cancellation_requests`: the declaration as submitted, including the declarant's
e-mail address, optional name and free-text reason, and the time it arrived.
**Recipients** — the payment provider, which receives the reader's e-mail address and
the account id passed into the checkout, and which holds the card details we never
see. Where a merchant of record is used it is the seller and an independent
controller.
**Retention** — the raw webhook payload is emptied after 90 days, and immediately when
an account is deleted; the event id is kept for ever, because it is the only thing
that makes a replayed webhook a no-op. Cancellation records survive account deletion
with the account link removed, because § 312k Abs. 4 BGB requires the declaration and
its exact time to be confirmable and commercial law requires it to be kept.

## Cookies

Four, all inside the "strictly necessary" exception in § 25 Abs. 2 Nr. 2 TDDDG, which
is why there is no consent banner and adding one would ask permission for something
that needs none. There is no analytics, no advertising and no third-party script
anywhere in the app.

| Cookie | Purpose | Lifetime | Set when | Flags |
|---|---|---|---|---|
| `JSESSIONID` | Session and CSRF token | Session | Signing in or posting a form | HttpOnly, Secure in production, SameSite=Lax |
| `remember-me` | Signed-in state between visits; encodes the account e-mail and an HMAC | 1 year | Only on ticking "remember me" | HttpOnly, Secure in production |

Those two are the whole list. The display and edition cookies went with the
accessibility edition when it moved to Klarblatt, so nothing is stored in the browser
for a visitor who never signs in, and no consent banner is owed under § 25 TDDDG:
both remaining cookies are strictly necessary for a service the user asked for.
Both are cleared on logout and on account deletion.

## Data subject rights, and how each one is actually answered

| Right | How |
|---|---|
| Access (Art. 15) | **Settings → Your data → Download my data.** Self-service JSON, no request to handle. |
| Portability (Art. 20) | The same file: structured, machine-readable, in a common format. |
| Erasure (Art. 17) | **Settings → Delete account.** Cascades through feeds, articles, delivery history, tokens, display preferences, send limits and subscription; empties stored payment payloads; clears the display and edition cookies. The one thing that survives is a payment or cancellation record, unlinked from the account, where law requires it to be kept. |
| Rectification (Art. 16) | Kindle address and reading preference in Settings; anything else by writing to the address on `/imprint`. |
| Restriction and objection (Art. 18, 21) | By writing. The only Art. 6(1)(f) processing is abuse prevention and aggregate telemetry. |
| Withdrawal of consent (Art. 7(3)) | No processing here relies on consent, so there is none to withdraw. |
| Complaint (Art. 77) | To the supervisory authority where the reader lives. |

A written request has to be answered within one month (Art. 12(3)). The export exists
so that the most common request needs no answering at all.

## Security (Art. 32)

Passwords are bcrypt-hashed and never logged. Sessions are HttpOnly and Secure in
production, behind TLS terminated by Caddy. CSRF protection is on for every form. All
feed and article HTML is sanitised with a jsoup safelist before rendering. Outbound
fetches allow only HTTP and HTTPS, resolve DNS and reject loopback, private,
link-local, CGNAT and ULA addresses, with response size and timeouts capped — an SSRF
guard, because the app fetches URLs a stranger chose. The payment webhook is
authenticated by HMAC-SHA256 over the raw body with a timestamp freshness check.
Backups are taken with `pg_dump` (`deploy/backup.sh`); they contain personal data and
belong wherever the rest of the operator's confidential material lives.

**Logs** deliberately contain no e-mail addresses, Kindle addresses or IP addresses.
Account ids appear, which are pseudonymous. Feed and article URLs appear at DEBUG,
which production does not enable. This is worth guarding when adding a log line: log
files are where personal data quietly accumulates with no retention period at all.

## Breach notification (Art. 33, 34)

Not something code can do. A personal data breach has to be reported to the lead
supervisory authority within 72 hours of becoming aware of it, and to affected readers
where the risk to them is high. The practical prerequisite is knowing who to contact
before it happens rather than looking it up at hour 70 — most likely the LfDI
Baden-Württemberg, which the establishment question confirms.

## Known gaps

Honest list, so nobody has to rediscover them:

- **Deleting an account does not tell the payment provider.** The app holds no provider
  API key by design. The subscription must be cancelled at the provider by hand, and
  the provider keeps its own records under its own retention rules.
- **Backups are not selectively erasable.** A deleted account remains in older
  `pg_dump` files until they rotate out. This is normal and generally accepted, but the
  rotation period should be short enough to state, and stated.
- **The Art. 30 record above is only true as of the last migration.** V13 is the
  current schema. A migration that adds personal data has to update this file and
  `DataExportRepository` together, or the export quietly stops being complete.
