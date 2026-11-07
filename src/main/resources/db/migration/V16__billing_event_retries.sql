-- Webhook events that fail to apply are retried instead of being parked for good.
--
-- attempts        how many times the event has been claimed for applying. Rows
--                 from before this migration have been tried once.
-- claimed_at      when the current attempt started. A claim that never finished
--                 (the process died mid-apply) is taken over once it is stale.
-- next_attempt_at when the retry sweep may try a failed event again. NULL means
--                 no retry is scheduled: processed, in progress, out of attempts,
--                 or failed before retries existed.
-- subscription_ref and occurred_at let a retried event recognise that a newer
--                 event for the same subscription has already been applied, so a
--                 late retry cannot roll a subscription back to an older state.
ALTER TABLE billing_events
    ADD COLUMN attempts         INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN claimed_at       TIMESTAMPTZ,
    ADD COLUMN next_attempt_at  TIMESTAMPTZ,
    ADD COLUMN subscription_ref TEXT,
    ADD COLUMN occurred_at      TIMESTAMPTZ;

CREATE INDEX idx_billing_events_retry
    ON billing_events(next_attempt_at)
    WHERE processed_at IS NULL;

CREATE INDEX idx_billing_events_subscription_ref
    ON billing_events(subscription_ref, occurred_at)
    WHERE subscription_ref IS NOT NULL;
