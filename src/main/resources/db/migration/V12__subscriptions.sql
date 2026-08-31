-- Paid subscriptions. One row per account; a missing row is treated as the free
-- plan, so nothing has to be backfilled for accounts created later.
CREATE TABLE subscriptions (
    user_id                  BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    plan                     TEXT NOT NULL,
    status                   TEXT NOT NULL,
    billing_interval         TEXT,
    provider                 TEXT,
    provider_customer_id     TEXT,
    provider_subscription_id TEXT UNIQUE,
    -- Access runs to this moment even after a cancellation: a year that was paid
    -- for is a year that was bought.
    current_period_end       TIMESTAMPTZ,
    cancel_at_period_end     BOOLEAN NOT NULL DEFAULT FALSE,
    -- Kept because § 356 Abs. 4 BGB only lets the withdrawal right lapse when the
    -- consumer agreed to an immediate start and acknowledged the consequence.
    withdrawal_consent_at    TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_subscription_plan
        CHECK (plan IN ('FREE', 'SUPPORTER')),
    CONSTRAINT chk_subscription_status
        CHECK (status IN ('FREE', 'PENDING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED', 'GRANDFATHERED')),
    CONSTRAINT chk_subscription_interval
        CHECK (billing_interval IS NULL OR billing_interval IN ('MONTHLY', 'YEARLY'))
);

CREATE INDEX idx_subscriptions_period_end
    ON subscriptions(current_period_end)
    WHERE current_period_end IS NOT NULL;

-- Every account that exists before charging begins keeps the full allowances for
-- good. They signed up for something advertised as having no subscriptions, and
-- retroactively capping them would be both unfair and bad for the project.
INSERT INTO subscriptions (user_id, plan, status)
SELECT id, 'SUPPORTER', 'GRANDFATHERED' FROM users;

-- Provider callbacks are retried and occasionally delivered twice. The provider's
-- own event id is the primary key, so replaying one is a no-op rather than a
-- second period extension.
CREATE TABLE billing_events (
    provider_event_id TEXT PRIMARY KEY,
    provider          TEXT NOT NULL,
    type              TEXT NOT NULL,
    payload           TEXT NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at      TIMESTAMPTZ,
    error             TEXT
);

-- § 312k Abs. 3 BGB requires the consumer to be able to keep their cancellation
-- declaration, and Abs. 4 requires it to be confirmed with the date and time it
-- arrived. Both need the declaration itself on record, including for people who
-- cancel without signing in — which Abs. 2 entitles them to do.
CREATE TABLE cancellation_requests (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT REFERENCES users(id) ON DELETE SET NULL,
    email          TEXT NOT NULL,
    name           TEXT,
    contract_ref   TEXT,
    kind           TEXT NOT NULL,
    requested_end  DATE,
    reason         TEXT,
    effective_at   TIMESTAMPTZ,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at   TIMESTAMPTZ,
    CONSTRAINT chk_cancellation_kind CHECK (kind IN ('ORDINARY', 'IMMEDIATE'))
);

CREATE INDEX idx_cancellation_requests_email
    ON cancellation_requests(lower(email), received_at DESC);
