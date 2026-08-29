-- Two things the accessibility edition needs, both shared with the standard one.
--
-- Saved articles: a bookmark is the feature readers ask for most often, and the
-- one that has to survive an article being marked read, so it gets a timestamp
-- of its own rather than riding along on the read flag.
ALTER TABLE articles
    ADD COLUMN saved_at TIMESTAMPTZ;

CREATE INDEX idx_articles_saved_at ON articles(saved_at DESC NULLS LAST)
    WHERE saved_at IS NOT NULL;

-- Display preferences: colours, type size and line spacing decide whether a
-- reader with failing sight can read the page at all, so they belong to the
-- account and follow it to another device, not just to one browser's cookie jar.
-- The settings travel as one opaque, tolerant-to-parse string (the same value the
-- cookie carries), so adding a further preference needs no migration and an older
-- row stays readable.
CREATE TABLE display_preferences (
    user_id             BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    settings            TEXT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
