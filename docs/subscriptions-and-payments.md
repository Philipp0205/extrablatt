# Subscriptions and payments — €1.50 a month, and what it takes to charge it legally

Extrablatt now has a free plan and a paid **Supporter** plan: €1.50 a month billed
yearly (€18.00 at once), or €2.50 a month billed monthly. This note records why
those numbers, what a Swiss company changes about charging European consumers,
how the code implements it, and what still has to be filled in by hand before
anyone is charged.

The short version of the money: the prices are right, the interval matters more
than the amount, and a merchant of record is now the clear choice rather than a
close call. The short version of the law: two requirements — the order button and
the cancellation page — changed the routing rather than the copy, and one of them
is why the app has a public `/cancel` page instead of relying on the payment
provider's customer portal.

## Why €1.50 and €2.50

A payment processor's fixed per-transaction fee does not shrink with the price, so
at these amounts it is the only thing that matters. Stripe in Germany takes
1.5% + €0.25 on a standard EEA card, plus 0.7% for Billing and 0.5% for Tax:

| Price | Stripe EEA card | Paddle (merchant of record) |
|---|---|---|
| €1 / month | 27.7% | 51.0% |
| €2 / month | 15.2% | 28.0% |
| €2.50 / month | 12.7% | 23.4% |
| €18 / year | 4.1% | 7.6% |

Twelve small charges cost twelve fixed fees; one larger charge costs one. Hence
the shape of the offer: **the yearly plan is the one to steer people to**, at
€18.00 — advertised as €1.50 a month, which is what it works out to — and the
monthly plan exists for people who will not prepay a year, priced at €2.50 so
that choosing it is not a loss.

Running costs are about €29 a month, almost all of it fixed (VPS, Resend Pro,
domain, backups), plus roughly €0.03–0.13 a month per active reader, because the
only cost with a real unit price is e-mail: one article sent is one e-mail. About
22 subscribers cover the infrastructure. The full model, with the fee and cost
figures it is built from, is in the appendix at the end.

Because e-mail is the cost, **the gate sits on Kindle delivery and nothing else**:

| | Free | Supporter |
|---|---|---|
| Send to Kindle | 3 / day | 50 / day |
| Feeds | 15 | 200 (configured: 50) |
| Newsletter inbox | — | yes |
| Reading, categories, saved articles, paged reader | yes | yes |
| Accessibility edition | yes | yes |

Three sends a day costs about €0.08 a month to serve, so the free plan is
affordable even if it never converts, and it is not a trial — it does not run out.
The accessibility edition stays free in full: it exists because the readers who
need it are the ones least served by everything else, and the audience is small
enough that it changes no number above.

Every account that existed before charging began is **grandfathered permanently**.
The marketing page promised "no ads, no subscriptions, nothing to sell"; that
promise was made to those people. `V9__subscriptions.sql` writes them all in as
`GRANDFATHERED` with no end date.

## Do you need a company, and does the Swiss KlG work?

**No, a company is not strictly required — but you have one, and it is the better
answer.** Stripe's Swiss terms open the door to "businesses (including sole
proprietors) and non-profit organisations located in Switzerland", so an
Einzelfirma would be enough for Stripe alone. A merchant of record like Paddle
expects a registered business, and a Kollektivgesellschaft entered in the
cantonal Handelsregister is one.

The entity is registered as follows, from the Zürich commercial register entry for
CHE-349.063.262. These are the details now in `/imprint`:

| | |
|---|---|
| Registered name | Kubri by Philipp Kurrle und Ali Abriani KLG |
| Legal form | Kollektivgesellschaft |
| Seat and address | Wehntalerstrasse 17, 8057 Zürich |
| Register | Handelsregisteramt des Kantons Zürich |
| Registration number | CH-020.2.010.172-0 |
| UID | CHE-349.063.262 |
| Registered | 11 September 2025 |
| Partners | Dr. Ali Abriani (Zürich) and Philipp Kurrle (Stuttgart), each with sole signing authority |
| Registered purpose | Development and distribution of software solutions, in particular for dental and medical practices |

Three things about that entry are worth acting on rather than filing away.

**The registered purpose does not cover this.** It describes dental and medical
practice software; Extrablatt is a consumer reader. That does not limit the
partnership's capacity to enter contracts, but it is worth settling with the
accountant whether this revenue belongs in the KlG's books, and widening the
purpose if it does.

**Liability is unlimited and joint.** That is what a Kollektivgesellschaft is. For a
business collecting €18 at a time the exposure is small, but it is the reason to
keep the terms, the withdrawal policy and the refund practice tidy rather than
approximate.

**One partner is domiciled in Stuttgart**, which is inside the EU. That single fact
bears on both of the hard questions below — the VAT one and the EU representative
one — and it is why they should go to an advisor as one question rather than three.

### The Swiss part that changes everything

Two registration thresholds apply on the Swiss side. Commercial-register entry is
mandatory once annual revenue passes CHF 100,000, and Swiss VAT registration is
mandatory once **worldwide** taxable turnover passes CHF 100,000 — worldwide, not
Swiss. Below that, no Swiss VAT is charged.

For EU VAT, Switzerland is a third country, and this is the single most consequential
fact in this document:

> A business established outside the EU that sells digital services to EU
> consumers must charge EU VAT **from its first sale**. There is no €10,000
> threshold — that threshold exists only for businesses established inside the EU.

So the comfortable reading in the first draft of this note — stay under €10,000 of
cross-border sales and charge no VAT — is not available to a Swiss company. From
subscriber number one, VAT is due at each customer's local rate: 19% for a German
customer, 22% for an Italian one, and so on.

Handling that yourself means registering for the **Non-Union One Stop Shop** in one
EU member state of your choosing (Germany's BZSt runs one), charging destination
rates, filing quarterly and keeping the records to back it up. Registration takes
effect from the start of the quarter after you apply, so it has to be done before
you sell, not after.

One caveat, and it is the Stuttgart partner again: all of the above assumes the
partnership has no fixed establishment inside the EU. If the business is in fact
partly run from Germany, that assumption may not hold, and the VAT position changes
— potentially to German registration, or to the Union scheme with its €10,000
threshold. Which way it falls depends on facts an advisor has to establish, not on
anything in this repository. A merchant of record makes the question moot for VAT
purposes, which is one more reason to prefer one.

## Stripe or Paddle: the actual difference

Both take card payments and both fire signed webhooks. The difference is not the
fee, and it is not the API — it is **who the law considers the seller.**

| | Stripe | Paddle |
|---|---|---|
| What it is | A payment processor | A merchant of record — the legal reseller |
| Who sells to your customer | You do | Paddle does; you sell to Paddle |
| EU VAT registration | Yours (Non-Union OSS, from sale one) | Paddle's; you need none |
| Charging correct local VAT rates | Yours to get right (Stripe Tax, +0.5%) | Paddle's problem |
| Quarterly OSS filings | Yours | None |
| Invoices to consumers | Yours to issue | Paddle issues them |
| Chargebacks and fraud | Yours to absorb | Paddle absorbs them |
| Cost on €18/year | ~€0.74 (4.1%) | ~€1.36 (7.6%) |
| Cost on €2.50/month | ~€0.32 (12.7%) | ~€0.58 (23.4%) |
| Payout | Direct, rolling | Weekly or monthly, net of fees |

**For this app, as a Swiss company, Paddle is the recommendation.** The reasoning
is entirely about the row that says "from sale one":

- Paddle costs about €0.62 more per subscriber per year — €124 a year at 200
  subscribers. That is less than one afternoon of a tax advisor's time, and OSS
  filings are quarterly, forever.
- Because Paddle is the seller, there are no cross-border consumer sales in
  Extrablatt's own books at all. The Non-Union OSS registration, the destination
  rate table, the quarterly returns and the consumer invoices all stop being
  yours.
- It also covers Swiss VAT (Paddle is registered for it at 8.1%), so the same
  arrangement handles Swiss customers.

Choose Stripe instead if you would rather own the tax stack — the fee is roughly
half — or if monthly billing becomes the main plan, where a merchant of record's
23% is not payable. Nothing in the code prefers either: the integration is a
hosted checkout link plus a signed webhook, and both signature schemes are
implemented and tested.

## What the law requires, and what the code does about it

None of this is legal advice; a Swiss operator selling to German consumers should
have this reviewed. But the items below are specific, and three of them are
already in the code because they could not be left to the copy.

### 1. The cancellation button (§ 312k BGB) — done, and it is why `/cancel` exists

German law requires a permanently available, clearly labelled cancellation button
that leads straight to a confirmation page, and requires the declaration to be
confirmed in text form with the date and time it arrived.

Two findings made this an engineering problem:

- **It may not sit behind a login.** The OLG Köln held (10 January 2025,
  6 U 62/24) that the button must be immediately visible and the path must not
  require credentials. Every payment provider's customer portal is reached by
  signing in or via an e-mailed login link, so **no provider portal satisfies
  this.**
- **It applies to prepaid annual plans too.** The BGH extended § 312k to contracts
  paid once that end automatically (22 May 2025, I ZR 161/24), so "it is a yearly
  prepay, not a subscription" is not a way out.

Getting it wrong is not fined; under § 312k Abs. 6 it gives every affected
customer the right to cancel at any time without notice.

Implemented in `CancellationController`: `/cancel` is in `SecurityConfig`'s public
paths, presents exactly the fields the statute lists (no more — asking for more is
itself a defect, which is why there is no password box), confirms with a second
button that says it cancels now, records the declaration in
`cancellation_requests`, shows it back for printing and e-mails the same
confirmation. Linked from the footer of both editions and from the marketing page.

### 2. The order button (§ 312j BGB) — done, and it is why the order page is ours

The button that concludes the contract must be labelled so that it says ordering
costs money, and the essential terms — service, total price, term, renewal,
cancellation — must be shown prominently immediately before it. § 312j Abs. 4 makes
the consequence blunt: without a compliant button, **the contract does not come
into existence at all.** The BGH restated this on 9 October 2025 (I ZR 159/24),
holding a "Senden" button insufficient and the contract void.

A provider's hosted checkout labelled "Subscribe" is not something to gamble a
contract on, so `BillingController` serves the order page itself — the boxed
summary, then a button reading "Order with obligation to pay" — and only then
redirects to the provider for card details.

### 3. Withdrawal (§ 356 Abs. 4 BGB) — done

Consumers get 14 days. For a digital service the right lapses only on full
performance, and only where the consumer both consented to an immediate start and
acknowledged losing the right. Two separate statements, neither pre-ticked. The
order page has exactly that, `subscriptions.withdrawal_consent_at` records when it
happened, and `/withdrawal` carries the instructions and the model form.

### 4. Imprint (§ 5 DDG) — page exists, content is yours to fill in

A third-country provider has no origin-country shield: the OLG Hamm held
(17 December 2013, 4 U 100/13) that German information duties reach a site aimed at
German consumers whatever country it is run from. `/imprint` exists with every
required line marked. It must name the company and legal form, a real postal
address, a telephone number or equivalent fast contact, **the Swiss Handelsregister
and CHE number** (the LG Frankfurt held on 28 March 2003, 3-12 O 151/02, that a
foreign register must be disclosed), the VAT numbers, and the EU representative
below.

### 5. EU representative (Art. 27 GDPR) — establish whether you need one at all

The usual advice is that a non-EU controller offering a service to people in the EU
must designate a representative in the Union in writing, and that the "occasional
processing" exemption never applies to a service people sign up to and use
continuously. Both halves of that are true. But there is a prior question that is
easy to skip, and skipping it here would probably get the wrong answer.

**Art. 27 only applies "where Article 3(2) applies"** — that is, where the GDPR
reaches you *because* you offer services into the Union from outside it. If the GDPR
reaches you through Art. 3(1) instead, because you have an establishment in the
Union and the processing is carried out in the context of its activities, then no
representative is required. The EDPB says so explicitly in Guidelines 3/2018: a
controller subject to the GDPR under Art. 3(1) does not have to appoint one.

That is a live question here, not a technicality. A partner with sole signing
authority is domiciled in Stuttgart. The EDPB notes that the threshold for a "stable
arrangement" is low where the centre of activities is an online service — a single
person acting with sufficient stability can amount to an establishment. The catch is
the second limb: mere residence is not enough, the processing must actually be
carried out in the context of that person's activities in the Union. So the answer
turns on how the work is really organised, which is a fact, not a reading.

Two outcomes, and the imprint has room for either:

- **Established in Germany (Art. 3(1)).** No Art. 27 representative. Instead you get
  a lead supervisory authority in Germany and the one-stop-shop mechanism, and the
  VAT question above reopens.
- **Not established in the EU (Art. 3(2)).** Appoint a representative. What that
  actually takes is below.

Worth knowing either way: appointing a representative does **not** create an
establishment, does not trigger the one-stop-shop, and under Art. 27(5) does not
shield the partnership from action. It is a point of contact, not a liability buffer.

#### What appointing one involves

Not much, and it is mostly a contract rather than a project:

- **A written mandate.** Art. 27(1) requires the designation to be in writing; an
  e-mail exchange is not really it. The mandate should name the representative
  unambiguously and set out the scope of processing covered, the authority to be
  addressed by supervisory authorities and data subjects, how requests get forwarded
  in both directions and how fast, the representative's access to your Art. 30
  record, termination, and indemnities.
- **Established in a member state where your data subjects are.** One is enough even
  when readers are spread across the EU; Germany or Ireland are the usual choices.
  It can be a person or a company, and it can serve several clients — but it must not
  also be your data protection officer.
- **An Art. 30 record of processing activities.** This is the part with real work in
  it. Art. 30(4) puts an independent obligation on the representative to hold the
  record and produce it to a supervisory authority on request, so you have to write
  one and keep it current: purposes, categories of data subjects and data,
  recipients, transfers, retention, security measures. For Extrablatt that is a short
  document — accounts, feeds and articles, Kindle addresses, subscription state, and
  the e-mail and payment providers as recipients.
- **Naming it publicly**, in the imprint and the privacy notice, so data subjects and
  authorities can find it. Both pages have the slot.
- **A yearly fee.** Commodity services run roughly €200–€1,500 a year for something
  this size; the €2,000–€10,000 figures quoted around the web are for larger
  operations with audit support attached.

Sequence matters slightly: settle the Art. 3(1) question first, because if you are
established in Germany you would be paying a yearly fee for something the law does
not ask of you.

### 6. Prices are gross — done

Consumer prices must be total prices. Everything the app quotes is gross and says
"incl. VAT", and prices live in `app.billing.*-price-cents` so no page can quote a
different number from the confirmation e-mail.

### Checklist before switching `BILLING_ENABLED` on

- [ ] **Settle one question with an advisor first:** does the partnership have a
      fixed establishment in Germany, given a partner in Stuttgart? The answer
      decides the VAT route, whether an Art. 27 representative is needed, and which
      supervisory authority leads.
- [ ] Decide Stripe or Paddle; open the account under the KlG.
- [ ] If Stripe: register for Non-Union OSS **before** the first sale, and enable
      Stripe Tax. If Paddle: nothing, which is the point.
- [ ] Check whether worldwide turnover will pass CHF 100,000 (Swiss VAT) and
      whether the KlG's Handelsregister entry is current.
- [ ] Appoint an EU representative under Art. 27 GDPR — **unless** the establishment
      question above says you are covered by Art. 3(1), in which case say so on the
      imprint instead.
- [ ] Finish `/imprint`: a monitored contact mailbox, optionally a telephone number,
      the VAT position and the representative line. The registered company details
      are already in.
- [ ] Consider widening the KlG's registered purpose, which currently covers dental
      and medical practice software rather than a consumer reader.
- [ ] Have terms, withdrawal notice and privacy reviewed by a German lawyer. If the
      site is ever presented in German, the two statutory buttons must carry the
      literal wording "Verträge hier kündigen" and "jetzt kündigen"; the English
      equivalents in the code are written to be the "entsprechende eindeutige
      Formulierung" the statute allows for an English-language site, and that
      judgement is worth confirming.
- [ ] Set `BILLING_OPERATOR_EMAIL` — without it nobody is told to stop a payment
      when someone cancels.
- [ ] E-mail existing users first, telling them they are grandfathered.

## How it is built

Billing is **off unless `BILLING_ENABLED` is set**, following the pattern of
`app.newsletters.*`: unconfigured, there are no prices, no subscription menu, no
cancellation page, and every account keeps the full `app.limits.*` allowances. A
self-hosted copy is unaffected, because it is not the one collecting the money.

### Data

`V9__subscriptions.sql` adds three tables:

- `subscriptions` — one row per account: plan, status, interval, provider ids,
  `current_period_end`, `cancel_at_period_end`, `withdrawal_consent_at`. A missing
  row means the free plan. Existing accounts are inserted as `GRANDFATHERED`.
- `billing_events` — the provider's own event id as the primary key. Both providers
  retry and occasionally deliver twice; the insert is the lock, so a replay is a
  no-op instead of a second period.
- `cancellation_requests` — the declaration itself, with the time it arrived,
  because § 312k Abs. 3 and 4 require it to be keepable and confirmable.

### Entitlements

`EntitlementService` is the single answer to "what may this account do". Limits used
to be read from configuration at each call site; they now come from here, in this
order of precedence:

1. An administrator's custom daily limit (`user_send_limits`) — a decision about
   one account outranks the plan.
2. The plan: `app.limits.*` for Supporter, `app.billing.free-*` for free.
3. Billing disabled — everything is Supporter.

Administrative *blocking* deliberately stayed in `KindleMailService`: a block is not
an allowance, and an administrator pausing an account should outrank what that
account has paid for.

Access is not a simple status read. A cancelled subscription keeps its access to
the end of the period it paid for; a failed renewal keeps it for
`app.billing.grace-days` on top, which also covers the case where an active
subscription's renewal confirmation never arrived. That last one is the failure
mode of a lost webhook, and it is why the grace period exists at all.

### The payment path

```
Settings ▸ Subscription
        └─ /billing/order        our page: § 312j summary + consent + priced button
             └─ POST             records PENDING, stores consent, redirects out
                  └─ provider hosted checkout (card details never touch this app)
                       ├─ /billing/return   "being confirmed" — grants nothing
                       └─ POST /webhooks/billing   signed; the only thing that grants
```

Three rules hold this together:

- **The webhook is authoritative and the return page grants nothing.** A reader who
  closes the tab still ends up subscribed; someone who crafts a request to
  `/billing/return` does not.
- **The webhook authenticates itself.** It follows `NewsletterInboundController` —
  public, CSRF-exempt — but a shared secret in a URL is not good enough for money,
  so the caller is verified by HMAC-SHA256 over the raw body with a timestamp
  freshness check. The body is read as bytes: re-serializing parsed JSON would
  change them and break every signature.
- **Nothing is deleted on the way down.** Losing a subscription changes allowances.
  Feeds, articles and reading position stay. A reader who lapses and returns a year
  later finds things where they left them, which is the only thing that makes
  someone resubscribe.

`SubscriptionService.expireLapsed` runs nightly and expires subscriptions whose paid
period passed and whose provider never said so. Without it a lost webhook fails
silently and permanently, in either direction.

### The two seams left open

1. **Cancelling at the provider.** The app holds no provider API key, so a
   cancellation is recorded, confirmed to the reader, and e-mailed to
   `BILLING_OPERATOR_EMAIL` so the money is stopped by hand. At 25–100 subscribers
   that is a few minutes a month and no untested payment code. Closing it means one
   API call per provider in a `BillingProvider` implementation.
2. **Manual activation.** Callbacks go missing. Settings ▸ Telemetry has a per-user
   "Grant Supporter" control so a reader who paid and was not credited can be fixed
   without a database client, and so a complimentary subscription can be given out.

An anonymous cancellation is accepted and confirmed — that is what the statute
requires — and applied when it only stops a future renewal. An *immediate*
cancellation is applied on the spot only for the signed-in owner, because it can
involve refunding a period already paid for; anyone else's is recorded and passed to
the operator. The worst an abused form can do is stop a renewal the owner can start
again, and the owner is e-mailed either way.

## Appendix: reproducing the numbers

Rates current in August 2026: Stripe Germany 1.5% + €0.25 for standard EEA cards and
3.25% + €0.25 for non-EEA, Stripe Billing 0.7%, Stripe Tax 0.5%, SEPA Direct Debit
0.8% + €0.30, Paddle 5% + $0.50, Resend Pro $20 for 50,000 e-mails with $0.90 per
1,000 over, Hetzner CX23 €5.49. Check them before acting on any of it.

```python
USD = 0.92
FIXED = 5.49 + 20 * USD + 18 / 12 + 3.81          # EUR 29.20 / month
EMAIL = 0.90 * USD / 1000                          # per e-mail beyond 50,000

def stripe(gross, pct=0.015, fixed=0.25, tax=True):
    return gross * pct + fixed + gross * 0.007 + (gross * 0.005 if tax else 0)

def paddle(gross):
    return gross * 0.05 + 0.50 * USD

# The whole argument for billing yearly, in two lines:
#   stripe(2.50) * 12 = EUR 3.81/yr  (12.7%)
#   stripe(18.00)     = EUR 0.74/yr  ( 4.1%)
#
# And the whole argument for a merchant of record, given VAT from sale one:
#   paddle(18.00) - stripe(18.00) = EUR 0.62/subscriber/year
```
