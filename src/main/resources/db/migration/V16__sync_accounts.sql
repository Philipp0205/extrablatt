-- Two-way sync of feeds and read state with an external RSS provider
-- (Nextcloud News first). One connection per account.

CREATE TABLE sync_accounts (
    user_id           BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    provider          TEXT NOT NULL,
    base_url          TEXT NOT NULL,
    username          TEXT NOT NULL,
    secret_encrypted  TEXT NOT NULL,
    cursor            TEXT,
    last_synced_at    TIMESTAMPTZ,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Provider-side ids. category_dirty marks a local category change not yet pushed.
ALTER TABLE feeds ADD COLUMN remote_id BIGINT;
ALTER TABLE feeds ADD COLUMN category_dirty BOOLEAN NOT NULL DEFAULT FALSE;

-- read_changed_at is set whenever the read flag is changed locally and cleared
-- once the change has been pushed to (or overridden by) the provider.
ALTER TABLE articles ADD COLUMN remote_id BIGINT;
ALTER TABLE articles ADD COLUMN read_changed_at TIMESTAMPTZ;

CREATE INDEX idx_articles_read_changed ON articles(feed_id) WHERE read_changed_at IS NOT NULL;
CREATE INDEX idx_articles_remote ON articles(remote_id) WHERE remote_id IS NOT NULL;

-- Feeds deleted locally that still exist remotely, so the deletion can be pushed.
CREATE TABLE sync_feed_tombstones (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    remote_id  BIGINT NOT NULL,
    PRIMARY KEY (user_id, remote_id)
);
