-- Multi-user support: accounts, email verification/reset tokens, and per-user
-- ownership of feeds (and, through them, articles).

CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    email               TEXT NOT NULL UNIQUE,
    password_hash       TEXT NOT NULL,
    kindle_email        TEXT,
    email_verified_at   TIMESTAMPTZ,
    disabled_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One-time tokens for e-mail verification and password reset. A short opaque
-- token is stored directly; rows are pruned once used or expired.
CREATE TABLE email_tokens (
    token       TEXT PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_email_tokens_user ON email_tokens(user_id);
CREATE INDEX idx_email_tokens_purpose ON email_tokens(user_id, purpose);

-- Adopt any pre-existing single-user feeds under a disabled legacy account so
-- the NOT NULL owner constraint can be added without dropping data. The bcrypt
-- hash below matches no password; the operator resets it if the data matters.
INSERT INTO users (email, password_hash, email_verified_at, disabled_at)
SELECT 'legacy@kindle-rss.local',
       '$2a$10$N9qo8uLOickgx2ZMRZoMun0000000000000000000000000000000000',
       NOW(),
       NOW()
WHERE EXISTS (SELECT 1 FROM feeds);

ALTER TABLE feeds ADD COLUMN user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;

UPDATE feeds
SET user_id = (SELECT id FROM users WHERE email = 'legacy@kindle-rss.local')
WHERE user_id IS NULL;

ALTER TABLE feeds ALTER COLUMN user_id SET NOT NULL;

-- Feed URLs are unique per user now, not globally, so two users can follow the
-- same source.
ALTER TABLE feeds DROP CONSTRAINT feeds_url_key;
ALTER TABLE feeds ADD CONSTRAINT uq_feeds_user_url UNIQUE (user_id, url);

CREATE INDEX idx_feeds_user ON feeds(user_id);
