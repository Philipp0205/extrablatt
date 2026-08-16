-- Simplify newsletters to one inbound address per account instead of one per
-- newsletter: an account hands out the same address to every newsletter it
-- subscribes to, and each distinct sender becomes its own feed automatically
-- the first time it delivers something. The per-feed address from V5 is no
-- longer needed.

DROP INDEX IF EXISTS uq_feeds_inbound_token;

ALTER TABLE feeds
    DROP COLUMN inbound_token;

ALTER TABLE users
    ADD COLUMN newsletter_inbound_token TEXT;

CREATE UNIQUE INDEX uq_users_newsletter_inbound_token
    ON users(newsletter_inbound_token) WHERE newsletter_inbound_token IS NOT NULL;
