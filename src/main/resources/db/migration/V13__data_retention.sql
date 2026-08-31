-- Data protection housekeeping: make erasure complete, and give the rows that
-- grow without bound something to be pruned against.

-- A billing event had no link to an account, so deleting an account left the raw
-- provider payload behind — and that payload can carry the customer's e-mail. The
-- event id has to survive deletion, because it is what makes a replayed webhook a
-- no-op, but nothing else does.
ALTER TABLE billing_events
    ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

-- Nightly pruning scans by age, on three tables that only ever grew.
CREATE INDEX idx_billing_events_received ON billing_events(received_at);
CREATE INDEX idx_email_tokens_expires ON email_tokens(expires_at);
CREATE INDEX idx_articles_created ON articles(created_at);
