-- Personal API tokens for agents and scripts (/api/v1). Only a SHA-256 hash of
-- the secret is stored, so the table is no use to anyone who can read it; the
-- prefix is the few leading characters shown in the token list so a person can
-- tell their tokens apart. A revoked token keeps its row, which also keeps its
-- hash from ever being issued again.
CREATE TABLE api_tokens (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    token_hash    TEXT NOT NULL UNIQUE,
    token_prefix  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);

CREATE INDEX idx_api_tokens_user ON api_tokens(user_id);
CREATE INDEX idx_api_tokens_hash ON api_tokens(token_hash);
