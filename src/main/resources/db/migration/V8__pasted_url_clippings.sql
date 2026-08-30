-- Articles sent by pasting a page URL (rather than coming from a polled feed or
-- a newsletter) live on one synthetic feed per account. `url` stays NOT NULL /
-- unique, so that feed uses a never-fetched placeholder ('clippings:').

ALTER TABLE feeds DROP CONSTRAINT chk_feeds_source;
ALTER TABLE feeds ADD CONSTRAINT chk_feeds_source
    CHECK (source IN ('RSS', 'NEWSLETTER', 'CLIPPING'));
