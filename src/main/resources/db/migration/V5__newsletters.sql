-- Newsletters: a feed can now be filled by inbound e-mail instead of polling a
-- URL. `url` stays NOT NULL/unique for existing RSS logic, so a newsletter feed
-- gets a synthetic, never-fetched placeholder ('newsletter:<token>') there.

ALTER TABLE feeds
    ADD COLUMN source TEXT NOT NULL DEFAULT 'RSS',
    ADD COLUMN inbound_token TEXT;

ALTER TABLE feeds
    ADD CONSTRAINT chk_feeds_source CHECK (source IN ('RSS', 'NEWSLETTER'));

-- One inbound address per newsletter feed, looked up without knowing the owner.
CREATE UNIQUE INDEX uq_feeds_inbound_token ON feeds(inbound_token) WHERE inbound_token IS NOT NULL;
