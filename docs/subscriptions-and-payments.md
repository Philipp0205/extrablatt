# Subscriptions and payments — pricing that survives its own transaction fees

Running Extrablatt for free has stopped being sustainable, and the instinct is a
small monthly price: one to three euros. That instinct is right about the
*amount* and wrong about the *interval*. At €1–3 a month, a payment processor's
fixed per-transaction fee eats 11–28% of the money before anything reaches the
bank account, and the legal apparatus that switching on payments drags behind it
costs more attention than the code does.

This note works out what the app actually costs, what a payment at these prices
actually yields, and then lays out how to build subscriptions into the codebase
as it stands.

## The short answer

**Charge once a year, not once a month.** The fee on a €2 monthly charge is
€0.30 — 15% — and it is almost all the fixed €0.25 component, which does not
shrink with the price. The same money collected annually as €18 costs €0.74 for
the whole year, 4.1%. Identical revenue, a quarter of the fee, one twelfth of
the dunning, failed cards and support mail.

The recommendation is:

| | Price | Effective fee | Kept per year |
|---|---|---|---|
| **Supporter, yearly** | €18 / year | 4.1% | €17.26 |
| Supporter, monthly (for people who won't prepay) | €2.50 / month | 12.7% | €26.19 |
| Free | €0 | — | — |

€18 a year is €1.50 a month — inside the intended range, and moderate by any
measure. The monthly option exists because some readers genuinely will not
prepay a year, but it is priced so that choosing it is not a loss: at €2.50 the
fee is 12.7%, and the extra €12 a year over the annual plan more than covers it.

**Do not offer €1 a month.** €0.28 of every euro goes to Stripe, the app keeps
€8.68 a year, and roughly 49 subscribers are needed just to cover the server
bill. The same reader on the €18 annual plan is worth twice as much and costs a
twelfth as many transactions.

Keep a real free tier, and grandfather everyone who already has an account. The
marketing page currently promises "No ads, no subscriptions, nothing to sell";
that promise was made to the people using it today, and breaking it for them
would cost more goodwill than the revenue is worth.

## What the app actually costs to run

The numbers below come from a model that is reproduced in full at the end of
this note, so they can be re-run when the real figures are known. Everything is
in euros, with USD converted at 0.92.

### 1. Fixed costs dominate

| | Per month |
|---|---|
| Hetzner CX23 (2 vCPU, 4 GB, Germany) | €5.49 |
| Resend Pro — 50,000 e-mails ($20) | €18.40 |
| Domain and DNS | €1.50 |
| Off-box backup storage | €3.81 |
| **Total** | **€29.20** |

E-mail is the single largest line, and it is the one that has to be paid before
there is any revenue at all: Resend's free plan allows 100 e-mails a *day*, and
since every Send-to-Kindle delivery is one e-mail, a handful of active readers
sending a few articles each is already through the ceiling. The €18.40 is
effectively a fixed cost from the first dozen users onwards.

### 2. One more reader adds almost nothing

Beyond the e-mail, a reader consumes feed polls, some Postgres rows and stored
extracted HTML — all of which sit inside a VPS that is already paid for. E-mail
is the only cost with a real unit price (€0.90 per 1,000 beyond the included
50,000):

| Articles sent per day | E-mails / month | Marginal cost |
|---|---|---|
| 1 | 30 | €0.03 / month |
| 3 | 91 | €0.08 / month |
| 5 | 152 | €0.13 / month |
| 20 | 608 | €0.50 / month |
| 50 (today's default cap) | 1,520 | €1.26 / month |

The last row is worth staring at. `MAX_SENDS_PER_DAY` defaults to 50, so 33
maximally active accounts would exhaust the entire Resend Pro allowance on their
own. Today's limits were written to stop abuse, not to bound cost, and they are
too generous to hand out for free indefinitely.

### 3. Break-even is small

At €18 a year, with a paying reader assumed to send five articles a day:

| Paying accounts | Revenue | Cost | Result |
|---|---|---|---|
| 10 | €14.39 / month | €30.46 | **−€16.07** |
| 25 | €35.97 / month | €32.35 | +€3.62 |
| 50 | €71.93 / month | €35.49 | +€36.44 |
| 100 | €143.87 / month | €41.79 | +€102.08 |
| 250 | €359.67 / month | €60.66 | +€299.00 |

Roughly 22 subscribers cover the infrastructure. At a 5% conversion rate that
means about 445 registered accounts — a real number, but not a large one. The
shape of the business is: a small fixed cost, a near-zero marginal cost, and a
break-even measured in dozens of people rather than thousands.

It also means the upside is modest in absolute terms. At 100 subscribers this is
about €1,700 a year. That is enough to stop the hosting bill hurting; it is not
enough to justify spending a lot of money on tax advice, which matters for the
processor choice below.

## What a payment costs at these prices

### 1. The fixed fee is the whole problem

Stripe in Germany charges 1.5% + €0.25 for a standard EEA consumer card, plus
0.7% for Stripe Billing on recurring volume, plus 0.5% if Stripe Tax handles
VAT. The percentages are irrelevant at these prices; the €0.25 is everything.

| Price | Stripe EEA card | Stripe non-EEA card | Stripe SEPA Direct Debit | Paddle (merchant of record) |
|---|---|---|---|---|
| €1 / month | 27.7% | 29.4% | 32.0% | 51.0% |
| €2 / month | 15.2% | 17.0% | 17.0% | 28.0% |
| €2.50 / month | 12.7% | 14.5% | 14.0% | 23.4% |
| €3 / month | 11.0% | 12.8% | 12.0% | 20.3% |
| €12 / year | 4.8% | 6.5% | 4.5% | 8.8% |
| €18 / year | 4.1% | 5.8% | 3.7% | 7.6% |
| €24 / year | 3.7% | 5.5% | 3.2% | 6.9% |

Twelve small charges cost twelve fixed fees. One larger charge costs one. That
single fact is the strongest argument in this document.

Stripe does operate a micropayments rate (around 5% + €0.05) that inverts the
trade-off below roughly €12 a transaction, but it is not self-serve — it is
granted per account on request, after review, and cannot be assumed while
planning. Annual billing needs no one's permission.

### 2. VAT comes out of the sticker price, not off the top

Consumer prices in the EU are quoted gross. If VAT has to be charged, a €2
sticker price is €1.68 of revenue and €0.32 of someone else's money:

| Sticker | VAT (19%) | Fees | Kept per year | Share of sticker |
|---|---|---|---|---|
| €1 / month | €0.16 | €0.28 | €6.76 | 56% |
| €2 / month | €0.32 | €0.30 | €16.52 | 69% |
| €3 / month | €0.48 | €0.33 | €26.28 | 73% |
| €18 / year | €2.87 | €0.74 | €14.39 | 80% |

Under the German small-business rule (§ 19 UStG) no VAT is charged on domestic
sales, so this table is a ceiling rather than a forecast. But the exemption does
not travel: once cross-border B2C sales to other EU countries pass €10,000 in a
calendar year, VAT is owed at each customer's local rate, and an RSS reader
sold in English will not stay domestic. Price as if VAT will eventually apply,
so that the day it does is not a 16% pay cut.

### 3. Which processor

Two credible options, and the difference between them is not really the fee.

**Stripe direct** is cheapest on paper (4.1% on €18) and the integration is well
understood. In exchange, Extrablatt is the seller of record: VAT registration
and OSS filings, compliant invoices, and the record-keeping behind them all
belong to the operator.

**A merchant of record** (Paddle; Lemon Squeezy is the same 5% + $0.50 and now
Stripe-owned) is the legal seller. It collects and remits VAT everywhere,
issues the invoices, and absorbs chargebacks. On €18 a year it costs €1.36
instead of €0.74 — €0.62 more per subscriber per year, or €124 a year at 200
subscribers.

€124 a year is less than one afternoon of a tax advisor's time, and OSS filings
are quarterly. Because the merchant of record is the seller, there are no
cross-border consumer sales in Extrablatt's own books to threshold against at
all. **For an annual plan at this scale it is the better trade** — it converts an
open-ended compliance obligation into a line item.

That flips if monthly billing ever becomes the main plan: a merchant of record
costs 23–28% at €2–2.50 a month, which is not payable, leaving Stripe direct
plus real tax advice as the only option. One more reason to lead with the annual
price.

The implementation below is deliberately provider-agnostic: both integrations
are "redirect to a hosted checkout, then trust a signed webhook", and the code
differs only inside one adapter class.

## Which features are free and which are paid

The gate belongs on the thing that costs money. Sending an article is one
e-mail; reading it in the browser is a database row. So the paid tier buys
*delivery volume*, and everything about reading stays free.

| | Free | Supporter |
|---|---|---|
| Feeds | 15 | 200 |
| Send to Kindle | 3 / day | 50 / day |
| Newsletter inbox | — | yes |
| Reading, categories, saved articles, paged reader | yes | yes |
| Accessibility edition | yes | yes |

Three sends a day is a genuinely usable reader — a morning's worth of articles —
and costs about €0.08 a month to serve, so the free tier stays affordable even
if it never converts. The newsletter inbox is paid because it needs a second
provider (inbound e-mail) on top of the outbound one.

Three things should stay free on principle, not as a concession:

- **The accessibility edition.** `docs/accessibility-edition.md` exists because
  the readers who need it most are the ones least served by everything else. A
  paywall in front of that is not a business decision worth making, and the
  audience is small enough that it changes no number in this document.
- **Account e-mail.** Verification, password reset and the welcome message are
  never gated. An account that cannot reset its password is a support ticket,
  not a conversion.
- **Existing accounts.** Everyone registered before the switch keeps full
  limits permanently. They were promised no subscriptions.

## What charging money obliges you to do

This is not legal advice, and a German operator should have an actual lawyer or
tax advisor look at the result. But three of these requirements change the
*architecture*, not just the copy, so they cannot be left to the end.

### 1. The cancellation button (§ 312k BGB) shapes the routing

German law requires any consumer subscription concluded online to be cancellable
through a clearly labelled button reading *"Verträge hier kündigen"*, leading
directly to a confirmation page with a second button reading *"jetzt kündigen"*,
after which the declaration must be confirmed to the customer in text form with
its date and time.

Two details make this an engineering constraint rather than a template change:

- **It may not sit behind a login.** The OLG Köln held (10 January 2025,
  6 U 62/24) that the required button must be immediately visible and that the
  cancellation path must not sit behind account credentials. A provider's hosted
  customer portal fails on both counts — it is reached by signing in, or through
  an e-mailed login link, and it is not a permanently visible button on the
  site — so **the provider's portal does not satisfy this on its own.** The app
  needs its own public cancellation route.
- **It applies to prepaid annual plans too.** The BGH extended § 312k to
  contracts paid once that end automatically (22 May 2025, I ZR 161/24), so
  "it's an annual prepay, not a subscription" is not an exit.

Getting this wrong is not fined; under § 312k(6) it gives every affected
customer the right to cancel at any time without notice. That is why a public
`/cancel` route appears in Phase 2 below rather than as a nice-to-have.

### 2. An Impressum, which does not exist yet

There is no Impressum anywhere in `marketing/` or `templates/` today. Once the
site is commercial, § 5 DDG requires the operator's name, postal address and
contact details. This is the single cheapest item on the list and the one most
likely to attract an Abmahnung if skipped.

### 3. Terms, withdrawal and privacy need real payment language

`templates/terms.html` currently says the service is provided as-is with
"reasonable per-account limits" and mentions payment nowhere; `privacy.html`
does not name a payment processor as a recipient of personal data. Both need
updating, along with the 14-day right of withdrawal for consumers and what
happens to it when the service starts immediately.

### 4. VAT

Covered above: § 19 UStG domestically, the €10,000 cross-border threshold, OSS
if the threshold is passed and a merchant of record is not used.

## Implementation plan

The codebase is unusually well prepared for this. There is already a per-user
quota with an admin override (`user_send_limits`), a rolling 24-hour send count
backed by an event table (`article_send_events`), a settings page built from
subview tabs, and an unauthenticated, CSRF-exempt webhook endpoint
(`/inbound/newsletters`) whose shape a payment webhook can copy. The work is
mostly wiring, not invention.

### Phase 0 — decide before writing code

Pick the price (€18/year is the recommendation), pick the provider, register the
business details, and open the account. Nothing below can be tested end to end
without a provider sandbox, and the schema in Phase 1 is deliberately neutral so
this decision can slip without blocking.

### Phase 1 — an entitlement layer, with billing switched off

This phase ships on its own and changes nothing that a user can see. It is worth
keeping separate precisely because it is the risky part: it touches the send
path, which is the app's core function.

**`V9__subscriptions.sql`** — following the existing style (snake_case,
`TIMESTAMPTZ`, named `chk_` constraints, `ON DELETE CASCADE`):

```sql
CREATE TABLE subscriptions (
    user_id               BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    plan                  TEXT NOT NULL,
    status                TEXT NOT NULL,
    provider              TEXT,
    provider_customer_id  TEXT,
    provider_subscription_id TEXT UNIQUE,
    current_period_end    TIMESTAMPTZ,
    cancel_at_period_end  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_subscription_plan CHECK (plan IN ('FREE', 'SUPPORTER')),
    CONSTRAINT chk_subscription_status
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED', 'GRANDFATHERED'))
);

-- Everyone who is already here keeps full limits, with no end date.
INSERT INTO subscriptions (user_id, plan, status)
SELECT id, 'SUPPORTER', 'GRANDFATHERED' FROM users;
```

A second table makes the webhook idempotent, which matters because every
provider retries and every provider occasionally delivers twice:

```sql
CREATE TABLE billing_events (
    provider_event_id TEXT PRIMARY KEY,
    type              TEXT NOT NULL,
    payload           TEXT NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at      TIMESTAMPTZ
);
```

**Domain and repository.** `Subscription` as a record beside `UserSendLimit`,
with `Plan` and `SubscriptionStatus` enums, and `SubscriptionRepository` /
`BillingEventRepository` on `JdbcTemplate` in the style of
`UserSendLimitRepository`.

**`EntitlementService`** — the one new idea, and the only place plan rules live:

```java
public record Entitlement(Plan plan, int maxSendsPerDay, int maxFeeds, boolean newsletters) { }

Entitlement forUser(long userId);
```

Precedence, from strongest to weakest, keeps the existing admin tooling intact:
an admin block still wins over everything; an admin's custom
`max_sends_per_day` still overrides the plan; the plan supplies the default; and
when billing is disabled the plan is always `SUPPORTER`, so
`app.limits.*` behaves exactly as it does today.

An entitlement is "active" when the status is `ACTIVE` or `GRANDFATHERED`, or
when it is `PAST_DUE` and `current_period_end` is less than seven days ago. The
grace window is deliberate: a card that fails on renewal should not cut off a
reader mid-morning.

**Call sites.** Three, all small:

- `KindleMailService.requireWithinDailyQuota` — replace the injected
  `maxSendsPerDay` field with `entitlements.forUser(userId).maxSendsPerDay()`.
- `FeedService.addFeed` and `receiveNewsletterIssue` — same substitution for the
  feed cap; `receiveNewsletterIssue` also drops the issue when the plan has no
  newsletter entitlement, reusing its existing "dropped" return path.
- `AccountAdvice` — publish `plan` and `subscriptionActive` model attributes
  next to `admin` and `emailVerified`, so both editions' templates can branch.

**Configuration.** A `Billing` record on `AppProperties`, following
`Newsletters`: absent configuration means the feature does not exist. This is
not just tidiness — the repo is self-hostable, and a self-hoster must never be
asked to pay the operator of a different deployment. With `BILLING_PROVIDER`
unset there is no billing UI, no `/billing` routes, and every account is
`SUPPORTER`.

### Phase 2 — the provider

**`BillingController`**, mirroring `SettingsController`'s conventions (POST,
flash attributes, redirect back to a settings view):

| Route | Purpose |
|---|---|
| `GET /settings?view=billing` | Plan, renewal date, upgrade or manage |
| `POST /billing/checkout` | Create a hosted checkout session, 303 to it |
| `GET /billing/return` | Landing page after payment — shows "activating", grants nothing |
| `POST /billing/portal` | Redirect to the provider's payment-method portal |
| `POST /webhooks/billing` | Signed provider callback — the only thing that grants access |
| `GET /cancel`, `POST /cancel` | Public § 312k cancellation path |

The webhook is authoritative and the return URL grants nothing. This is the
single most important rule in the integration: a user who closes the tab, or
whose browser never comes back, must still end up subscribed, and a user who
crafts a request to `/billing/return` must not.

Wiring it follows the newsletter webhook exactly — add `/webhooks/billing` to
the `permitAll` matchers and to `csrf().ignoringRequestMatchers(...)` in
`SecurityConfig` — with one difference: authentication is the provider's HMAC
signature over the raw request body, not a shared secret in a query parameter.
That means reading the body as `byte[]` before any JSON parsing.

Events worth handling, and what each one does:

| Stripe event | Effect |
|---|---|
| `checkout.session.completed` | Link `provider_customer_id` to the user, set `ACTIVE` |
| `customer.subscription.updated` | Refresh `current_period_end`, `cancel_at_period_end`, status |
| `customer.subscription.deleted` | `CANCELED`, keep access until `current_period_end` |
| `invoice.paid` | Extend `current_period_end` |
| `invoice.payment_failed` | `PAST_DUE`, start the grace window |
| `charge.refunded` / dispute | `CANCELED` immediately |

**A nightly reconciliation job** — `@Scheduled` beside
`FeedService.scheduledRefresh` — re-reads every subscription that is `PAST_DUE`
or whose `current_period_end` has passed, and asks the provider for the truth.
Webhooks get lost, and without this the failure is silent and lasts forever: a
paying customer quietly downgraded, or a cancelled one served indefinitely. The
app already runs as a single instance, so no distributed locking is needed.

**The cancellation route** is the part that has no provider equivalent. `/cancel`
is added to `PUBLIC_PATHS`, presents the § 312k form (who you are, which
contract, ordinary or immediate termination, optional reason), and on POST
records the declaration with its timestamp, cancels at the provider, and e-mails
a confirmation in text form through the existing `AccountMailService`. The
button in Settings links to the same page so there is one implementation.

### Phase 3 — copy, and the pages that do not exist yet

Legal first: an Impressum page (public, linked from both the marketing footer
and the app), payment sections in `terms.html` and `privacy.html` — which the
accessible edition inherits automatically through
`th:replace="~{terms :: content}"` — and a refund and withdrawal policy.

Then the copy that is now false. `marketing/index.html` needs a pricing section,
and four places currently promise the opposite of what will be true:

- the meta description — "Free, ad-free, and built for reading a page at a time"
- the hero note — "Free to use. No ads, no tracking, nothing to buy."
- the feature tile headed "Free, and it stays that way" — "No ads, no
  subscriptions, nothing to sell."
- the accessibility section — "Same free account, same articles" (this one
  stays true, and should be made explicit)

In the app, `settings.html` gains a `billing` subview between **Version** and
**Support the project**, and `accessible/settings.html` gains the matching
section — the two settings pages do not share a template, so both need editing.
The donation link stays: some readers prefer to give once, and grandfathered
accounts have no other way to help.

One nudge changes character. `KindleMailService` returns a donation prompt every
tenth lifetime send; for a free account with billing enabled, that becomes an
upgrade prompt, and the daily-cap error — today a flash message reading "Daily
send limit reached (50)" — becomes the actual paywall. It is worth writing that
one string carefully, because it is where most people will meet the price.

### Phase 4 — flip the switch

Announce it by e-mail before the deploy, not after: what is changing, what the
price is, and that the recipient's own account keeps its limits permanently.
Then set `BILLING_PROVIDER` and watch the first renewals.

### Failure modes worth designing for

| Failure | Handling |
|---|---|
| Webhook lost | Nightly reconciliation against the provider |
| Webhook replayed | `billing_events.provider_event_id` primary key |
| Webhook before checkout return | Fine — the webhook is authoritative |
| Renewal card declines | `PAST_DUE` plus seven days of grace, then free limits |
| Downgrade | Limits change; **no data is ever deleted** |
| Account deleted while subscribed | Cancel at the provider inside `UserService.deleteAccount` |
| Chargeback | Immediate downgrade |
| Provider outage during checkout | Nothing is granted; the user retries |

The "no data is ever deleted" row is worth stating as policy. A reader who
lapses and comes back a year later should find their feeds where they left them.
Feeds and articles cost almost nothing to keep, and deleting them buys a
rounding error of storage at the price of the only thing that makes someone
resubscribe.

### Tests

Following the existing conventions — plain JUnit with Mockito for services,
`@WebMvcTest` with `@Import(SecurityConfig.class)` for controllers:

- `EntitlementServiceTest` — plan limits, admin override precedence, the grace
  window boundary, and billing-disabled meaning full limits.
- `KindleMailServiceTest` — extend with a free-plan account hitting the lower
  cap, alongside the existing block and quota cases.
- `BillingWebhookControllerTest` — reachable unauthenticated and without a CSRF
  token, a bad signature is rejected, a replayed event is a no-op.
- `CancellationControllerTest` — `/cancel` is reachable while signed out, and a
  confirmation is sent.
- `PostgresRepositoryTest` — add subscription round-trips.
- `AppPropertiesTest` — billing absent by default.

### Deliberately out of scope

No card data touches the app, ever — hosted checkout only. No self-built
invoicing, no dunning e-mail sequences, no proration maths, no coupons, no
multiple currencies, no usage-metered billing. Every one of those is available
from the provider, and none of them is worth building for a product whose whole
plan is one price and a free tier.

## Reproducing the numbers

Every figure in this note comes from the model below. The rates it encodes were
current in August 2026: Stripe Germany 1.5% + €0.25 for standard EEA cards and
3.25% + €0.25 for non-EEA, Stripe Billing 0.7%, Stripe Tax 0.5%, SEPA Direct
Debit 0.8% + €0.30, Paddle 5% + $0.50, Resend Pro $20 for 50,000 e-mails with
$0.90 per 1,000 over, Hetzner CX23 €5.49. They should be checked before any of
this is acted on.

```python
USD = 0.92
FIXED = 5.49 + 20 * USD + 18 / 12 + 3.81          # EUR 29.20 / month
EMAIL = 0.90 * USD / 1000                          # per e-mail beyond 50,000

def stripe(gross, pct=0.015, fixed=0.25, tax=True):
    return gross * pct + fixed + gross * 0.007 + (gross * 0.005 if tax else 0)

def paddle(gross):
    return gross * 0.05 + 0.50 * USD

# EUR 2/month vs EUR 18/year, same money, very different fees:
#   stripe(2.00) * 12 = EUR 3.65/yr   (15.2%)
#   stripe(18.00)     = EUR 0.74/yr   ( 4.1%)
```
