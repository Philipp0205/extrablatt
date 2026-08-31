# Going live — the four things left, in the order they should be done

The code is finished. What remains is four decisions and about an hour of clicking in
other people's dashboards. They are listed in dependency order, because the first one
decides the second.

## 1. Settle the German establishment question

This is first because it changes the answer to almost everything else. The operator is
a Swiss Kollektivgesellschaft; one of its two partners, with sole signing authority,
lives in Stuttgart and is the person who builds and runs Extrablatt. That single fact
puts three separate legal tests in play at once, and they do not have the same answer.

### The three tests, and why they differ

**Data protection — is there an establishment in the Union?** If yes, the GDPR applies
through Art. 3(1) and **no Art. 27 representative is needed at all**. The EDPB's
Guidelines 3/2018 say the threshold for a "stable arrangement" is low where the centre
of activities is an online service: a single person acting with sufficient stability can
be enough. The second limb is what matters — the processing has to be carried out *in
the context of that person's activities* in the Union, and mere residence is not enough.
Someone who develops, operates and supports the service from Stuttgart is a long way
past mere residence.

**VAT — is there a fixed establishment?** A different test, from Art. 11 of the VAT
Implementing Regulation: sufficient permanence, plus a suitable structure of human and
technical resources. If there is one, the business is EU-established for VAT, which
means the **Union** OSS scheme and its €10,000 threshold apply instead of the
Non-Union scheme with no threshold — or German VAT registration outright. That is the
opposite of the position assumed in `docs/subscriptions-and-payments.md`, and it is
much more favourable.

**Income tax — is there a permanent establishment (Betriebsstätte)?** A third test
again, and the one with real money attached. It also matters that a German-resident
partner in a foreign partnership generally has German income tax exposure on their
share regardless of where the partnership sits.

### How to settle it

One appointment with a **Steuerberater who handles cross-border cases**, ideally one
used to DE/CH work. Two hours, a few hundred euros, and it closes all three questions
at once. Take this with you:

- The Handelsregister extract (CHE-349.063.262) and the partnership agreement.
- Where each partner lives, and what each actually does.
- Where the work happens: which country the code is written in, where support e-mail is
  answered, where decisions get made.
- Where the infrastructure is (VPS/Railway region) and where the customers are.
- Expected turnover, and the split between German, other-EU and non-EU customers.

The four questions to put, in writing so the answer is on record:

1. Does the KlG have a **fixed establishment in Germany for VAT purposes**? If so, do
   we register for German VAT and use the Union OSS above €10,000, or something else?
2. Does it have a **Betriebsstätte for income tax**, and what does that mean for
   filing in both countries?
3. Given the above, are we **established in the Union for GDPR purposes** (Art. 3(1)),
   so that no Art. 27 representative is required? If yes, which authority is our lead —
   presumably the LfDI Baden-Württemberg, since Stuttgart is in BW.
4. Is the KlG's registered purpose — dental and medical practice software — a problem
   for booking consumer subscription revenue from a reader app, and should it be
   widened?

**Expected outcome, stated so it can be checked rather than assumed:** established in
Germany on all three tests. If that is right, the consequences are all good — the
Non-Union OSS registration is not needed, the €10,000 threshold comes back, and the
Art. 27 representative and its yearly fee disappear. It also flips the provider
recommendation in the next section. But it is an expectation, not a finding, and it is
cheap to have it confirmed before money starts moving.

## 2. Set up the payment provider

### Which one, given the above

The earlier recommendation was Paddle, and the whole argument for it was that a
non-EU seller owes EU VAT from its first sale with no threshold, which makes a merchant
of record worth 3.5 percentage points. **If the KlG turns out to be established in
Germany, that argument disappears** and Stripe becomes the better choice on two counts:

| | Stripe | Paddle |
|---|---|---|
| Fee on €18/year | ~€0.74 (4.1%) | ~€1.36 (7.6%) |
| Works with this code as built | **Yes** | Needs one addition (below) |
| VAT | Yours, but with the €10,000 threshold if EU-established | Theirs |

So: **wait for the tax answer, then pick.** Established in Germany → Stripe.
Not established in the EU → Paddle, and budget the extra integration work.

### Stripe, step by step

Everything below is dashboard work; the app needs no code change.

1. **Create the account** at stripe.com under the KlG. You will need the
   Handelsregister number, the Zürich address and a bank account in the company's name.
   Expect an identity check on both partners.
2. **Two products, two prices.** Products → Add product → "Extrablatt Supporter".
   Add a recurring price of **€18.00 / year**, then a second recurring price of
   **€2.50 / month** on the same product. Prices are gross; if you enable Stripe Tax,
   set the prices as **tax-inclusive** so the sticker matches what the app shows.
3. **Two payment links.** Payment links → Create → pick the yearly price → under
   *After payment*, choose **Redirect** and set
   `https://reader.extrablatt.app/billing/return`. Repeat for the monthly price. Copy
   both URLs — they become `BILLING_YEARLY_CHECKOUT_URL` and
   `BILLING_MONTHLY_CHECKOUT_URL`.
4. **The webhook.** Developers → Webhooks → Add endpoint,
   `https://reader.extrablatt.app/webhooks/billing`. Select exactly these events:
   - `checkout.session.completed`
   - `customer.subscription.created`
   - `customer.subscription.updated`
   - `customer.subscription.deleted`

   Copy the signing secret (`whsec_…`) into `BILLING_WEBHOOK_SECRET`.
5. **Set the variables** and redeploy:

   ```
   BILLING_ENABLED=true
   BILLING_PROVIDER=stripe
   BILLING_WEBHOOK_SECRET=whsec_...
   BILLING_YEARLY_CHECKOUT_URL=https://buy.stripe.com/...
   BILLING_MONTHLY_CHECKOUT_URL=https://buy.stripe.com/...
   BILLING_PORTAL_URL=https://billing.stripe.com/p/login/...   # optional
   BILLING_OPERATOR_EMAIL=hello@extrablatt.app
   BILLING_REFERENCE_PARAM=client_reference_id                  # the default
   ```

6. **Test in a sandbox before touching live.** Do the whole thing once with test keys
   and a test payment link. Order from Settings → Subscription, pay with `4242 4242
   4242 4242`, and confirm the subscription page flips to Supporter within a few
   seconds. Then check the webhook log in Stripe shows `200`.

**Why it works without code changes.** The app appends `?client_reference_id=<account
id>` to the payment link, and Stripe echoes it back on `checkout.session.completed`.
That first event is where the app records Stripe's customer and subscription ids;
every later event — including renewals, where `client_reference_id` is *not* present —
is matched by subscription id instead. That is why `BillingWebhookService` looks the
subscription up three ways rather than one.

### Paddle, step by step

1. **Create the seller account** and get through Paddle's verification, which is more
   involved than Stripe's because they become the seller of record. Have the
   Handelsregister extract and a description of the product ready.
2. **Catalog → Products** → new product "Extrablatt Supporter", then two prices:
   €18.00 yearly and €2.50 monthly. Set them tax-inclusive.
3. **Developer tools → Notifications → New destination**, pointed at
   `https://reader.extrablatt.app/webhooks/billing`, subscribed to
   `subscription.created`, `subscription.updated` and `subscription.canceled`. Copy the
   secret key (`pdl_ntfset_…`) into `BILLING_WEBHOOK_SECRET`, and set
   `BILLING_PROVIDER=paddle`.
4. **The one thing that needs code.** Paddle does not take an account reference as a
   URL query parameter the way a Stripe payment link does — it takes `customData`
   through Paddle.js (`Paddle.Checkout.open({ customData: { user_id: … } })`) or on a
   checkout created server-side through its API. The app reads
   `data.custom_data.user_id` from the webhook already, so the parser is ready; what is
   missing is getting the value in. Either:
   - add Paddle.js to the order page and open the checkout from there (about fifteen
     lines, and the order page keeps its § 312j button because the reader still
     confirms here before the overlay opens), or
   - create the checkout per order through Paddle's API, which needs a server-side key
     and turns `placeOrder` into a real API call.

   Until one of those exists, a Paddle payment would arrive with no account attached
   and land in `billing_events` with an error — recoverable by hand from
   Settings → Telemetry, but not something to run on.

### Either way

- Keep `BILLING_ENABLED=false` until the sandbox run passes end to end.
- The nightly reconciliation (`app.billing.expiry-cron`) and the manual
  **Grant Supporter** control in Settings → Telemetry are the safety net for a lost
  callback. Try the grant control once so you know where it is before you need it.

## 3 and 4. A mailbox that actually reaches you

Two addresses are needed and they can be the same one: the imprint contact (a legal
requirement — § 5 DDG wants a monitored address) and `BILLING_OPERATOR_EMAIL`, which is
where a cancellation notice goes so the payment gets stopped at the provider.

Three facts found while looking at the DNS, all worth knowing before changing anything:

- **The domain is `extrablatt.app`, not `extrablatt.com`.** There is no `.com` zone in
  the Cloudflare account. Everything — the app, the marketing site, the Resend
  verification — is on `.app`.
- **The root domain's MX records already point at IONOS** (`mx00.ionos.de`,
  `mx01.ionos.de`), with an IONOS SPF record and an IONOS DMARC CNAME alongside them.
  Mail to `hello@extrablatt.app` is being routed to IONOS today, whether or not there
  is a mailbox there to catch it.
- **Resend is already set up for outbound** on this domain: `resend._domainkey` and a
  `send.extrablatt.app` SPF record are both in place. Sending works; only receiving is
  missing.

### Resend is the wrong tool for this

Resend can receive mail, but it has no forwarding *setting*. Forwarding is a
webhook plus an API call — Resend delivers an `email.received` event carrying only
metadata, and you then fetch the body and attachments and send a new message. That is
code to write, host and monitor, for something a mail provider does natively. Use
Resend for what it is already doing well, which is sending.

### Option A — an IONOS alias, and nothing changes (recommended)

The MX records already point at IONOS, so if there is a mail product on that domain,
the whole job is one alias in the IONOS control panel: `hello@extrablatt.app` forwards
to `philippk@mailbox.org`. No DNS change, no risk to anything, five minutes.

Check first: log in to IONOS and look for an active Mail Basic/Business package on
`extrablatt.app`. If there is one, do it there and stop reading.

### Option B — Cloudflare Email Routing, if IONOS mail is dead

Free, native forwarding with no code. The catch is in the second fact above:
**enabling it on the root domain replaces the IONOS MX records**, so anything currently
delivered to an IONOS mailbox on `extrablatt.app` stops arriving. Only do this if you
are sure nothing there is in use.

1. Cloudflare dashboard → the `extrablatt.app` zone → **Email → Email Routing** →
   *Get started*. Let it add the MX and TXT records it proposes; it will replace the
   IONOS MX.
2. **Destination addresses** → add `philippk@mailbox.org`. Cloudflare e-mails it a
   verification link — open it, or the rule stays disabled.
3. **Routing rules** → `hello` → *Send to an email* → `philippk@mailbox.org`. Leave the
   catch-all off unless you want every made-up address to reach you.
4. Send a test from an outside address and confirm it lands in mailbox.org.
5. Afterwards, tidy the IONOS leftovers: the `_dmarc` CNAME to `dmarc.ionos.de`, the
   `autodiscover` CNAME, and the IONOS `include:_spf-eu.ionos.com` in the root SPF —
   that SPF record should end up listing Resend rather than IONOS, or outbound mail from
   the app will be authorised by the wrong sender.

I could not do this part directly: the Cloudflare API token available to the agent
carries DNS permissions but not Email Routing, and the destination address needs a
verification link clicked in your mailbox in any case.

### Then

Set `BILLING_OPERATOR_EMAIL=hello@extrablatt.app` (or `philippk@mailbox.org` directly,
which is one hop fewer and never depends on the forwarding working). Send yourself a
test cancellation through `/cancel` and confirm both messages arrive: the confirmation
to the declarant, and the operator notice to you.

## The imprint, once the above is done

Three of the four gaps close on their own:

- **Contact** — `hello@extrablatt.app`, once it forwards. A telephone number is *not*
  strictly required: the CJEU held in C-649/17 that a trader need not set one up, and
  in C-298/07 that another equally direct and efficient channel suffices. A monitored
  mailbox is that channel. Add a number if one exists anyway; it is the safest option.
- **Responsible for the website** — Philipp Kurrle.
- **Art. 27 representative** — resolved by question 3 above. If Art. 3(1) applies, the
  right thing on the page is the German data protection contact, not a representative,
  and that is how the privacy notice is now worded.

What is left is **VAT**, and it cannot be filled in before the tax answer and the
provider choice. Two possible final texts:

- Stripe, EU-established: `USt-IdNr.: DE…` plus the Swiss position.
- Paddle: a line saying Paddle is the seller of record and charges the VAT, plus the
  Swiss position.
