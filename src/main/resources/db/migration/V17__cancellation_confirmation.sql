-- Cancellations declared by someone who was not signed in as the account's owner
-- are confirmed by a single-use link sent to the account's address before they
-- change anything. Only a SHA-256 hash of the link's token is stored, so the
-- table is no use to anyone who can read it.
CREATE TABLE cancellation_tokens (
    token_hash  TEXT PRIMARY KEY,
    request_id  BIGINT NOT NULL REFERENCES cancellation_requests(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cancellation_tokens_user ON cancellation_tokens(user_id);
CREATE INDEX idx_cancellation_tokens_expires ON cancellation_tokens(expires_at);

-- When the declaration changed the subscription. Null while it waits for the
-- link to be confirmed, or when it was passed to the operator instead.
ALTER TABLE cancellation_requests
    ADD COLUMN applied_at TIMESTAMPTZ;
